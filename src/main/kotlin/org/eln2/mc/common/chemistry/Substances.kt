package org.eln2.mc.common.chemistry

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.registries.ForgeRegistries
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.ForgeFluidEnergyDensity
import org.eln2.mc.ForgeFluidSpecificHeatCapacity
import org.eln2.mc.JOULE_PER_MILLIBUCKET
import org.eln2.mc.JOULE_PER_MILLIBUCKET_KELVIN
import org.eln2.mc.extensions.getDouble
import org.eln2.mc.extensions.getNullable
import org.eln2.mc.extensions.getResourceLocation
import org.eln2.mc.extensions.getString
import org.eln2.mc.extensions.mapNullable

/**
 * Attaches properties to a Forge Fluid.
 * @param specificHeatCapacity The specific heat capacity, but relating mB instead of normal physical quantities. P.S. By convention, 1mB = 1L.
 * */
class ThermalFluid(
    val forgeFluidId: ResourceLocation,
    val forgeFluid: Fluid,
    val specificHeatCapacity: Quantity<ForgeFluidSpecificHeatCapacity>
)

private fun resolveForgeFluid(id: ResourceLocation): Fluid {
    val result = ForgeRegistries.FLUIDS.getValue(id)

    if(result == null || result == Fluids.EMPTY) {
        error(DEBUGGER_BREAK("Invalid forge fluid \"$id\""))
    }

    return result
}

/**
 * Loader for all defined [ThermalFluid]s.
 * */
object ThermalFluidManager : SimpleJsonResourceReloadListener(GsonBuilder().create(), "thermal_fluid") {
    private val thermalFluids = HashMap<Fluid, ThermalFluid>()

    override fun apply(pObject: Map<ResourceLocation, JsonElement>, pResourceManager: ResourceManager, pProfiler: ProfilerFiller) {
        thermalFluids.clear()

        pObject.forEach { (id, json) ->
            json as JsonObject

            val forgeFluidId = json.getResourceLocation("forgeFluid")
            val forgeFluid = resolveForgeFluid(forgeFluidId)
            val specificHeatCapacity = Quantity(json.getDouble("specificHeatCapacity"), JOULE_PER_MILLIBUCKET_KELVIN)

            val result = ThermalFluid(forgeFluidId, forgeFluid, specificHeatCapacity)

            thermalFluids.putUnique(result.forgeFluid, result) {
                DEBUGGER_BREAK("Duplicate thermal fluid entry $id, ${result.forgeFluidId}")
            }
        }
    }

    fun getThermalFluid(fluid: Fluid) = thermalFluids[fluid]
}

/**
 * @param temperature The boiling point of the liquid.
 * @param enthalpy The heat of vaporization.
 * @param resultGas The resulting gas ("quantity" mapped 1mB to 1mB).
 * @param resultLiquidResidue The liquid remaining in the container.
 * */
class BoilingTransformation(
    val temperature: Quantity<Temperature>,
    val enthalpy: Quantity<ForgeFluidEnergyDensity>,
    val resultGas: Fluid,
    val resultLiquidResidue: Fluid?
    // We can also add an item, we'll see
)

/**
 * @param temperature The boiling point of the liquid.
 * @param enthalpy The heat of vaporization.
 * @param resultLiquid The resulting liquid ("quantity" mapped 1mB to 1mB).
 * */
class CondensationTransformation(
    val temperature: Quantity<Temperature>,
    val enthalpy: Quantity<ForgeFluidEnergyDensity>,
    val resultLiquid: Fluid
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
                val temperature = Quantity(it.getDouble("temperature"), KELVIN)
                val enthalpy = Quantity(it.getDouble("enthalpy"), JOULE_PER_MILLIBUCKET)
                val resultGas = resolveForgeFluid(it.getResourceLocation("resultGas"))
                val resultLiquidResidue = it.getNullable("resultLiquidResidue") { _ ->
                    resolveForgeFluid(ResourceLocation.parse(it.getString("resultLiquidResidue")))
                }

                BoilingTransformation(temperature, enthalpy, resultGas, resultLiquidResidue)
            }

            val condensationTransformation = json.mapNullable("condensation") {
                val temperature = Quantity(it.getDouble("temperature"), KELVIN)
                val enthalpy = Quantity(it.getDouble("enthalpy"), JOULE_PER_MILLIBUCKET)
                val resultLiquid = resolveForgeFluid(it.getResourceLocation("resultLiquid"))

                CondensationTransformation(temperature, enthalpy, resultLiquid)
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
