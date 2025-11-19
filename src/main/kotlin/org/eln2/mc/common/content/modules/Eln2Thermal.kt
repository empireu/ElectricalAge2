@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import net.minecraft.core.Direction
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.KILOGRAM_METER2
import org.ageseries.libage.data.NEWTON_METER
import org.ageseries.libage.data.NEWTON_METER_SECOND
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.REVOLUTION_PER_SECOND
import org.ageseries.libage.data.VOLT
import org.ageseries.libage.data.WATT
import org.ageseries.libage.data.WATT_PER_KELVIN
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Pole
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry.setPartVisualizer
import org.eln2.mc.common.LightFieldPrimitives
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.cells.foundation.ElectricalSize
import org.eln2.mc.common.cells.foundation.RadiantBodyEmissionDescription
import org.eln2.mc.common.cells.foundation.ThermalSize
import org.eln2.mc.common.content.ElectricalHeatEngineCell
import org.eln2.mc.common.content.ElectricalHeatEnginePart
import org.eln2.mc.common.content.ElectricalHeatEnginePartVisual
import org.eln2.mc.common.content.ThermalElectricGeneratorModel
import org.eln2.mc.common.parts.PartRegistry.partImmediateBB
import org.eln2.mc.common.parts.foundation.transformPartWorld
import org.eln2.mc.data.directionMonopolarMapPlanar
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.mathematics.Base6Direction3d

object Eln2Thermal : ContentModule() {
    override fun registerPartVisualizers() {
        setPartVisualizer<ElectricalHeatEnginePart>(Eln2Thermal.ELECTRICAL_HEAT_ENGINE_PART.part.get()) { ctx, part ->
            ElectricalHeatEnginePartVisual(
                ctx, part
            )
        }
    }

    val ELECTRICAL_HEAT_ENGINE_CELL = cellMemoize("electrical_heat_engine") {
        // The electrical plus and minus:
        val electricalA = Base6Direction3d.Left
        val electricalB = Base6Direction3d.Right

        // The hot side:
        val thermalA = Base6Direction3d.Front

        val electricalMap = directionPoleMapPlanar(
            plusDir = electricalA,
            minusDir = electricalB
        )

        val thermalMap = directionMonopolarMapPlanar(
            thermalA,
            Pole.Negative
        )

        val coldSideDefinition = ThermalMassDefinition(
            ChemicalElement.Copper.asMaterial,
            mass = Quantity(5.0, KILOGRAM)
        )

        val hotSideDefinition = ThermalMassDefinition(
            ChemicalElement.Copper.asMaterial,
            mass = Quantity(5.0, KILOGRAM)
        )

        // Radiator:
        val leakageCold = ConnectionParameters(
            conductance = Quantity(10.0, WATT_PER_KELVIN)
        )

        val leakageHot = ConnectionParameters(
            conductance = Quantity(0.1, WATT_PER_KELVIN)
        )

        val generatorModel = ThermalElectricGeneratorModel(
            Quantity(20.0, REVOLUTION_PER_SECOND),
            Quantity(120.0, VOLT),
            0.5,
            Quantity(10.0, WATT_PER_KELVIN),
            Quantity(0.05, WATT_PER_KELVIN),
            Quantity(1.0, KILOGRAM_METER2),
            Quantity(0.025, NEWTON_METER_SECOND),
            Quantity(5.0, NEWTON_METER),
            Quantity(2400.0, WATT),
            0.9,
            Quantity(2.5, WATT),
            0.01
        )

        val hemispheres = Direction.entries.associateWith {
            val volume = LightFieldPrimitives.hemisphereIncremental(
                15 + 64,
                3.0,
                it,
                1,
            ).volume

            RadiantBodyEmissionDescription({ volume })
        }

        CellFactory {
            ElectricalHeatEngineCell(
                it,
                electricalMap,
                thermalMap,
                coldSideDefinition, hotSideDefinition,
                leakageCold, leakageHot,
                generatorModel,
                0.075,
                hemispheres[it.locator.transformPartWorld(Base6Direction3d.Front)]!!,
                hemispheres[it.locator.transformPartWorld(Base6Direction3d.Back)]!!,
                ElectricalSize.Standard,
                ThermalSize.Standard
            )
        }
    }

    val ELECTRICAL_HEAT_ENGINE_PART = partImmediateBB("electrical_heat_engine", 4.0, 10.0, 14.0, ::ElectricalHeatEnginePart)
}
