package org.eln2.mc.sim

import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import org.ageseries.libage.sim.thermal.Temperature
import org.ageseries.libage.sim.thermal.ThermalUnits
import org.eln2.mc.common.cells.foundation.CellPos
import java.util.concurrent.ConcurrentHashMap

data class EnvironmentInformation(
    val temperature: Temperature,
    val airThermalConductivity: Double)

object BiomeEnvironments {
    private val biomes = ConcurrentHashMap<Biome, EnvironmentInformation>()

    fun get(level: Level, pos: CellPos): EnvironmentInformation{
        val biome = level.getBiome(pos.blockPos).value()

        return biomes.computeIfAbsent(biome) {
            return@computeIfAbsent EnvironmentInformation(
                Temperature.from(biome.baseTemperature.toDouble(), ThermalUnits.CELSIUS),
                Datasets.AIR_THERMAL_CONDUCTIVITY.evaluate(biome.baseTemperature.toDouble())
            )
        }
    }
}
