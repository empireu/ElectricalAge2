package org.eln2.mc

import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ThermalMass
import org.eln2.mc.data.BlockLocator
import org.eln2.mc.data.HashDataTable
import org.eln2.mc.data.Locator
import org.eln2.mc.data.requireLocator
import java.util.concurrent.ConcurrentHashMap

fun interface EnvironmentalTemperatureField {
    fun readTemperature(): Quantity<Temperature>
}

fun interface EnvironmentalThermalConductivityField {
    fun readConductivity(): Quantity<ThermalConductivity>
}

fun EnvironmentalTemperatureField.readInto(body: ThermalMass) {
    body.temperature = this.readTemperature()
}

data class EnvironmentInformation(
    val temperature: Quantity<Temperature>,
    val airThermalConductivity: Quantity<ThermalConductivity>,
) {
    fun fieldMap() = HashDataTable()
        .withField { EnvironmentalTemperatureField { temperature } }
        .withField { EnvironmentalThermalConductivityField { airThermalConductivity } }
}

object BiomeEnvironments {
    private val biomes = ConcurrentHashMap<Biome, EnvironmentInformation>()

    private val AIR_THERMAL_CONDUCTIVITY = loadCsvSpline("air_thermal_conductivity/ds.csv", 0, 2)
    private val MINECRAFT_TEMPERATURE_CELSIUS = loadCsvSpline("minecraft_temperature/ds.csv", 0, 1)

    fun getInformationForBlock(level: Level, pos: Locator): EnvironmentInformation {
        val biome = level.getBiome(pos.requireLocator<BlockLocator> {
            "Biome Environments need a block pos locator"
        }).value()

        val temperature = MINECRAFT_TEMPERATURE_CELSIUS.evaluate(biome.baseTemperature.toDouble())

        return biomes.computeIfAbsent(biome) {
            return@computeIfAbsent EnvironmentInformation(
                Quantity(temperature, CELSIUS),
                Quantity(AIR_THERMAL_CONDUCTIVITY.evaluate(temperature), WATT_PER_METER_KELVIN)
            )
        }
    }
}
