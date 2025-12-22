package org.eln2.mc.common.chemistry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.registries.ForgeRegistries
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.*
import org.eln2.mc.extensions.*

/**
 * Attaches properties to a Forge Fluid.
 * @param cacheId Unique ID chosen by the [ThermalFluidManager].
 * @param specificHeatCapacity The specific heat capacity.
 * @param density The density of the fluid. Used for gravity separation.
 * @param isGaseous Indicates if this fluid is treated as gaseous (usually for fluid handler routing).
 * @param mixabilityTags If this fluid shares any of the [mixabilityTags] with another fluid, these two fluids mix and cannot be separated by gravity.
 *  */
class ThermalFluid(
    val forgeFluidId: ResourceLocation,
    val forgeFluid: Fluid,
    val cacheId: Int,
    val specificHeatCapacity: Quantity<ForgeFluidSpecificHeatCapacity>,
    val density: Quantity<ForgeFluidDensity>,
    val isGaseous: Boolean,
    val mixabilityTags: List<String>
)

private fun resolveForgeFluid(id: ResourceLocation): Fluid {
    val result = ForgeRegistries.FLUIDS.getValue(id)

    if(result == null || result == Fluids.EMPTY) {
        error(DEBUGGER_BREAK("Invalid forge fluid \"$id\""))
    }

    return result
}

/**
 * Loader for all defined [ThermalFluid]s. Also has utility methods that cache results (to make use of the reload event).
 * */
object ThermalFluidManager : SimpleJsonResourceReloadListener(GsonBuilder().create(), "thermal_fluid") {
    private val thermalFluids = HashMap<Fluid, ThermalFluid>()

    override fun apply(pObject: Map<ResourceLocation, JsonElement>, pResourceManager: ResourceManager, pProfiler: ProfilerFiller) {
        thermalFluids.clear()
        mixablePairs.clear()

        var cacheId = 0
        val fluids = ArrayList<ThermalFluid>()
        pObject.forEach { (id, json) ->
            json as JsonObject

            val forgeFluidId = json.getResourceLocation("forgeFluid")
            val forgeFluid = resolveForgeFluid(forgeFluidId)
            val specificHeatCapacity = Quantity(json.getDouble("specificHeatCapacity"), JOULE_PER_MILLIBUCKET_KELVIN)
            val density = Quantity(json.getDouble("density"), KILOGRAM_PER_MILLIBUCKET)
            val isGaseous = json.getBool("isGaseous")
            val mixabilityTags = if(json.has("mixabilityTags")) json.getAsJsonArray("mixabilityTags").map { it.asString } else emptyList<String>()

            val result = ThermalFluid(
                forgeFluidId,
                forgeFluid,
                cacheId++,
                specificHeatCapacity,
                density,
                isGaseous,
                mixabilityTags
            )

            fluids.add(result)

            thermalFluids.putUnique(result.forgeFluid, result) {
                DEBUGGER_BREAK("Duplicate thermal fluid entry $id, ${result.forgeFluidId}")
            }
        }

        fluids.forEach { a ->
            fluids.forEach { b ->
                if(a.cacheId < b.cacheId) {
                    mixablePairs.putUnique(getPairKey(a, b), a.mixabilityTags.any { b.mixabilityTags.contains(it) })
                }
            }
        }
    }

    fun getThermalFluid(fluid: Fluid) = thermalFluids[fluid]

    fun requireThermalFluid(fluid: Fluid) = getThermalFluid(fluid) ?: error("$fluid was not a thermal fluid!")

    private val mixablePairs = Long2BooleanOpenHashMap()

    @Suppress("NOTHING_TO_INLINE")
    private inline fun getPairKey(a: ThermalFluid, b: ThermalFluid) : Long {
        val fluid1: ThermalFluid
        val fluid2: ThermalFluid

        if(a.cacheId < b.cacheId) {
            fluid1 = a
            fluid2 = b
        }
        else {
            fluid1 = b
            fluid2 = a
        }

        return (fluid1.cacheId.toLong() shl 32) or (fluid2.cacheId.toLong() and 0xFFFFFFFFL)
    }

    /**
     * Checks if [a] and [b] are mixable, based on their tags.
     * */
    fun areMixable(a: ThermalFluid, b: ThermalFluid) = mixablePairs.get(getPairKey(a, b))
}

/**
 * @param temperature The boiling point of the liquid.
 * @param enthalpy The heat of vaporization.
 * @param resultGas The resulting gas.
 * @param resultGasProportion How many `mB` of [resultGas] are created from `1000 mB` of liquid.
 * @param resultLiquidResidue The liquid remaining in the container. The amount will be proportional to `1000 - `[resultGasProportion]`.
 * */
class BoilingTransformation(
    val temperature: Quantity<Temperature>,
    val enthalpy: Quantity<ForgeFluidEnergyDensity>,
    val resultGas: Fluid, val resultGasProportion: Int,
    val resultLiquidResidue: Fluid?
    // We can also add an item, we'll see
)

/**
 * @param temperature The boiling point of the liquid.
 * @param enthalpy The heat of vaporization.
 * @param resultLiquid The resulting liquid ("quantity" mapped 1mB to 1mB).
 * @param resultLiquidProportion How many `mB` of [resultLiquid] are created from `1000 mB` of gas.
 * @param resultGasResidue The gas that remains in gas phase. The amount will be proportional to `100 - `[resultLiquidProportion]`.
 * */
class CondensationTransformation(
    val temperature: Quantity<Temperature>,
    val enthalpy: Quantity<ForgeFluidEnergyDensity>,
    val resultLiquid: Fluid, val resultLiquidProportion: Int,
    val resultGasResidue: Fluid?
)

/**
 * Describes all phase changes a thermal fluid can undergo.
 * @param file The data file's ID.
 * @param fluid The fluid in question.
 * @param boiling The boiling pathway (when temperature is larger than the boiling point).
 * @param condensation The condensation pathway (when temperature is lower than the boiling point).
 * */
class ThermalFluidTransformation(
    val file: ResourceLocation,
    val fluid: Fluid,
    val boiling: BoilingTransformation?,
    val condensation: CondensationTransformation?
)

/**
 * Loader for all defined [ThermalFluidTransformation]s.
 * */
object FluidTransformationManager : SimpleJsonResourceReloadListener(GsonBuilder().create(), "distillation") {
    val transformationsByFluid = HashMap<Fluid, ThermalFluidTransformation>()

    override fun apply(pObject: Map<ResourceLocation, JsonElement>, pResourceManager: ResourceManager, pProfiler: ProfilerFiller, ) {
        transformationsByFluid.clear()

        pObject.forEach { (file, json) ->
            json as JsonObject

            val fluidId = json.getResourceLocation("fluid")
            val fluid = resolveForgeFluid(fluidId)

            val boilingTransformation = json.mapNullable("boiling") {
                val temperature = Quantity(it.getDouble("temperature"), CELSIUS)
                val enthalpy = Quantity(it.getDouble("enthalpy"), JOULE_PER_MILLIBUCKET)
                val resultGas = resolveForgeFluid(it.getResourceLocation("resultGas"))
                val resultGasProportion = it.getInt("resultGasProportion", 1000)
                val resultLiquidResidue = it.getNullable("resultLiquidResidue") { _ ->
                    resolveForgeFluid(ResourceLocation.parse(it.getString("resultLiquidResidue")))
                }

                BoilingTransformation(temperature, enthalpy, resultGas, resultGasProportion, resultLiquidResidue)
            }

            val condensationTransformation = json.mapNullable("condensation") {
                val temperature = Quantity(it.getDouble("temperature"), CELSIUS)
                val enthalpy = Quantity(it.getDouble("enthalpy"), JOULE_PER_MILLIBUCKET)
                val resultLiquid = resolveForgeFluid(it.getResourceLocation("resultLiquid"))
                val resultLiquidProportion = it.getInt("resultLiquidProportion", 1000)
                val resultGasResidue = it.getNullable("resultGasResidue") { _ ->
                    resolveForgeFluid(ResourceLocation.parse(it.getString("resultGasResidue")))
                }

                CondensationTransformation(temperature, enthalpy, resultLiquid, resultLiquidProportion, resultGasResidue)
            }

            val transformations = ThermalFluidTransformation(
                file,
                fluid,
                boilingTransformation, condensationTransformation
            )

            val duplicate = transformationsByFluid.put(fluid, transformations)

            if(duplicate != null) {
                error(DEBUGGER_BREAK("Duplicate transformations for fluid $fluidId: \"${duplicate.file}\""))
            }
        }
    }

    fun getTransformations(fluid: Fluid) = transformationsByFluid[fluid]
}
