@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import org.ageseries.libage.data.FARAD
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.VOLT
import org.ageseries.libage.data.WATT
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicSpecVisual
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry.setSpecVisualizer
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.content.DcToDcConverterModel
import org.eln2.mc.common.content.DcToDcConverterSpec
import org.eln2.mc.common.content.TerminalDcToDcConverterCell
import org.eln2.mc.common.specs.SpecRegistry.specImmediateBB

object Eln2PowerDevices : ContentModule() {
    override fun registerSpecVisualizers() {
        setSpecVisualizer<DcToDcConverterSpec>(Eln2PowerDevices.DC_TO_DC_CONVERTER_SPEC.spec.get()) { ctx, spec ->
            BasicSpecVisual(
                ctx, spec,
                FlwModels.SMALL_DC_TO_DC_CONVERTER
            )
        }
    }

    val TERMINAL_DC_TO_DC_CONVERTER_CELL_800W = cellMemoize("terminal_dc_to_dc_converter_800w") {
        val thermalDef = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(1.0, KILOGRAM)
        )

        val environmentParameters = ConnectionParameters(
            conductance = Quantity(2.5)
        )

        val model = DcToDcConverterModel(
            Quantity(800.0, WATT),
            0.8,
            2.5,
            Quantity(800.0, VOLT),
            Quantity(0.0006571, OHM),
            Quantity(0.1027, FARAD),
            Quantity(0.0001167, OHM),
            Quantity(0.000891, OHM)
        )

        CellFactory {
            TerminalDcToDcConverterCell(it, thermalDef, environmentParameters, model)
        }
    }

    val DC_TO_DC_CONVERTER_SPEC = specImmediateBB(
        "micro_grid_dc_to_dc_converter_800w",
        FlwModels.SMALL_DC_TO_DC_CONVERTER,
        9.8, 2.625, 4.85,
        ::DcToDcConverterSpec
    )
}
