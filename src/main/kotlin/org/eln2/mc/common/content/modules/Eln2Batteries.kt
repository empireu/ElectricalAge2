@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import org.ageseries.libage.data.AMPERE
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.METER2
import org.ageseries.libage.data.MILLI
import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT_HOUR
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicPartVisual
import org.eln2.mc.client.render.foundation.BasicSpecVisual
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry.setPartVisualizer
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry.setSpecVisualizer
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.cells.foundation.ElectricalSize
import org.eln2.mc.common.content.BatteryModels
import org.eln2.mc.common.content.BatteryPart
import org.eln2.mc.common.content.BatterySpec
import org.eln2.mc.common.content.PolarBatteryCell
import org.eln2.mc.common.content.TerminalBatteryCell
import org.eln2.mc.common.parts.PartRegistry.partImmediateBB
import org.eln2.mc.common.specs.SpecRegistry.specImmediateBB
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.mathematics.Base6Direction3d
import kotlin.math.PI

object Eln2Batteries : ContentModule() {
    override fun registerPartVisualizers() {
        setPartVisualizer<BatteryPart>(BATTERY_PART_12V.part.get()) { ctx, part ->
            BasicPartVisual(
                ctx, part,
                FlwModels.LEAD_ACID_BATTERY,
                rotation = PI
            )
        }
    }

    override fun registerSpecVisualizers() {
        setSpecVisualizer<BatterySpec>(BATTERY_SPEC_12V.spec.get()) { ctx, spec ->
            BasicSpecVisual(
                ctx, spec,
                FlwModels.SPEC_LEAD_ACID_BATTERY
            )
        }
    }

    val LEAD_ACID_BATTERY_CELL_12V_840Wh = cellMemoize("lead_acid_battery_12v_840wh") {
        val model = BatteryModels.leadAcid12v(
            Quantity(840.0, WATT_HOUR),
            Quantity(23.0, MILLI * OHM),
            Quantity(12.0, KILOGRAM),
            Quantity(0.1, METER2),
            1e-7,
            Quantity(100.0, AMPERE)
        )

        val plusDir = Base6Direction3d.Front
        val minusDir = Base6Direction3d.Back

        CellFactory {
            val cell = PolarBatteryCell(it, model, directionPoleMapPlanar(plusDir, minusDir), ElectricalSize.Standard)
            cell.energy = cell.model.energyCapacity * 0.9
            cell
        }
    }

    val GRID_LEAD_ACID_BATTERY_CELL_12V_80Wh = cellMemoize("lead_acid_battery_12v_80wh") {
        val model = BatteryModels.leadAcid12v(
            Quantity(80.0, WATT_HOUR),
            Quantity(26.0, MILLI * OHM),
            Quantity(2.6, KILOGRAM),
            Quantity(0.0006, METER2),
            1e-6,
            Quantity(10.0, AMPERE)
        )

        CellFactory {
            val cell = TerminalBatteryCell(it, model)
            cell.energy = cell.model.energyCapacity * 0.9
            cell
        }
    }

    val BATTERY_PART_12V = partImmediateBB("lead_acid_battery_12v", 6.0, 7.0, 10.0) {
        BatteryPart(it, LEAD_ACID_BATTERY_CELL_12V_840Wh.get())
    }

    val BATTERY_SPEC_12V = specImmediateBB("micro_grid_lead_acid_battery_12v", FlwModels.SPEC_LEAD_ACID_BATTERY, 1.5, 1.85, 3.0) {
        BatterySpec(it, GRID_LEAD_ACID_BATTERY_CELL_12V_80Wh.get(),
            7.5125, 1.6938, 6.6875, 0.15, 0.15, 0.225,
            8.3375,1.6938,6.6875, 0.15, 0.15,0.225
        )
    }
}
