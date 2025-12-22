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
 * Extra properties attached to a Forge Fluid.
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ThermalFluid

        return cacheId == other.cacheId
    }

    override fun hashCode(): Int {
        return cacheId
    }
}

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
    private var refs = References(HashMap(), Long2BooleanOpenHashMap())

    override fun apply(pObject: Map<ResourceLocation, JsonElement>, pResourceManager: ResourceManager, pProfiler: ProfilerFiller) {
        val thermalFluids = HashMap<Fluid, ThermalFluid>()
        val mixablePairs = Long2BooleanOpenHashMap()

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

        /**
         * Computes mixability table:
         * */
        fluids.forEach { a ->
            fluids.forEach { b ->
                if(a.cacheId < b.cacheId) {
                    mixablePairs.putUnique(getPairKey(a, b), a.mixabilityTags.any { b.mixabilityTags.contains(it) })
                }
            }
        }

        refs = References(thermalFluids, mixablePairs)
    }

    /**
     * Gets the [ThermalFluid] for [fluid] or null, if the [fluid] doesn't have one attached.
     * */
    fun getThermalFluid(fluid: Fluid) = refs.thermalFluids[fluid]

    /**
     * Gets the [ThermalFluid] for [fluid] or throws, if the [fluid] doesn't have one attached.
     * */
    fun requireThermalFluid(fluid: Fluid) = getThermalFluid(fluid) ?: error("$fluid was not a thermal fluid!")

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
     * This is a fast lookup into a hashtable (faster than comparing the actual tags).
     * If [a] equals [b], then the result is `true`.
     * */
    fun areMixable(a: ThermalFluid, b: ThermalFluid) = if(a == b) {
        true
    }
    else {
        refs.mixablePairs.get(getPairKey(a, b))
    }

    private class References(val thermalFluids: HashMap<Fluid, ThermalFluid>, val mixablePairs: Long2BooleanOpenHashMap)
}

/**
 * Boils a liquid-phase fluid into a gas-phase fluid, optionally leaving behind a liquid-phase *residual* fluid.
 * This splitting is used to implement fractional distillation.
 * For example, oil could boil into Naphtha Vapor (the gas), and leave behind Heavy Oil (the residual liquid).
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
)

/**
 * Condenses a gas-phase fluid into a liquid-phase fluid, optionally leaving behind a gas-phase *residual* fluid.
 * This splitting is used to implement fractional condensation.
 * For example, coal gas could condense into Tar (the liquid), and leave behind hydrogen gas (the residual gas).
 * @param temperature The boiling point of the liquid.
 * @param enthalpy The heat of vaporization.
 * @param resultLiquid The resulting liquid ("quantity" mapped 1mB to 1mB).
 * @param resultLiquidProportion How many `mB` of [resultLiquid] are created from `1000 mB` of gas.
 * @param resultGasResidue The gas that remains in gas phase. The amount will be proportional to `1000 - `[resultLiquidProportion]`.
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
 * In simple terms, the transformations show:
 * - What the [fluid] splits into when it is boiled (following the chain of vapors and residuals, you can find the entire composition of [fluid]). Pure fluids usually wouldn't have a boiling transformation attached (right now, it is redundant), but they would have a condensation pathway attached.
 * - The liquid form of [fluid], for condensation (if [fluid] is a gas). Usually only pure fluids have a gas form and a condensation pathway.
 * @param boiling The boiling pathway (when temperature is larger than the boiling point).
 * @param condensation The condensation pathway (when temperature is lower than the condensation point).
 * */
class ThermalFluidTransformation(
    val file: ResourceLocation,
    val fluid: Fluid,
    val boiling: BoilingTransformation?,
    val condensation: CondensationTransformation?
    // Can add a freezing transformation
)

/**
 * Loader for all defined [ThermalFluidTransformation]s.
 * */
object FluidTransformationManager : SimpleJsonResourceReloadListener(GsonBuilder().create(), "distillation") {
    var transformationsByFluid = HashMap<Fluid, ThermalFluidTransformation>()

    override fun apply(pObject: Map<ResourceLocation, JsonElement>, pResourceManager: ResourceManager, pProfiler: ProfilerFiller, ) {
        val transformationsByFluid = HashMap<Fluid, ThermalFluidTransformation>()

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

        this.transformationsByFluid = transformationsByFluid
    }

    fun getTransformations(fluid: Fluid) = transformationsByFluid[fluid]
}
