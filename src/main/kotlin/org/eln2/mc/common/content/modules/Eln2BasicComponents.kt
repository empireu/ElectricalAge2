@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.KILO
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.VOLT
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicPartVisual
import org.eln2.mc.client.render.foundation.BasicSpecVisual
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry.setPartVisualizer
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry.setSpecVisualizer
import org.eln2.mc.common.cells.CellRegistry.cellImmediate
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.cells.foundation.ElectricalSize
import org.eln2.mc.common.content.DiodeCell
import org.eln2.mc.common.content.DiodeOptions
import org.eln2.mc.common.content.DiodePart
import org.eln2.mc.common.content.GroundCell
import org.eln2.mc.common.content.GroundPart
import org.eln2.mc.common.content.GroundSpec
import org.eln2.mc.common.content.VoltageSourceCell
import org.eln2.mc.common.content.VoltageSourcePart
import org.eln2.mc.common.parts.PartRegistry.partImmediateBB
import org.eln2.mc.common.specs.SpecRegistry.specImmediateBB
import org.eln2.mc.directionPoleMapPlanar
import org.eln2.mc.monopolarMapPlanar
import org.eln2.mc.mathematics.Base6Direction3d

object Eln2BasicComponents : ContentModule() {
    override fun registerPartVisualizers() {
        setPartVisualizer<DiodePart>(DIODE_PART.part.get()) { ctx, part ->
            BasicPartVisual(
                ctx, part,
                FlwModels.DIODE,
                smoothLighting = true
            )
        }
    }

    override fun registerSpecVisualizers() {
        setSpecVisualizer<GroundSpec>(Eln2BasicComponents.GROUND_SPEC.spec.get()) { ctx, spec ->
            BasicSpecVisual(
                ctx, spec,
                FlwModels.GROUND_MICRO_GRID
            )
        }
    }

    val VOLTAGE_SOURCE_CELL = cellMemoize("voltage_source") {
        val map = monopolarMapPlanar(Base6Direction3d.Front)
        val size = ElectricalSize.Standard

        CellFactory {
            VoltageSourceCell(it, map, size)
        }
    }

    val VOLTAGE_SOURCE_PART = partImmediateBB("voltage_source", 6.0, 2.5, 6.0, ::VoltageSourcePart)

    val GROUND_CELL = cellImmediate("ground", ::GroundCell)

    val GROUND_PART = partImmediateBB("ground", 4.0, 4.0, 4.0, ::GroundPart)

    val GROUND_SPEC = specImmediateBB(
        "ground_micro_grid",
        FlwModels.GROUND_MICRO_GRID,
        2.0, 2.0, 2.0,
        ::GroundSpec
    )

    val DIODE_CELL = cellMemoize("diode") {
        val poleMap = directionPoleMapPlanar(
            Base6Direction3d.Back,
            Base6Direction3d.Front
        )

        val model = DiodeOptions(
            Quantity(0.001, OHM),
            Quantity(10.0, KILO * OHM),
            ThermalMassDefinition(
                ChemicalElement.Iron.asMaterial,
                mass = Quantity(1.691, KILOGRAM)
            ),
            Quantity(148.61, CELSIUS),
            Quantity(800.0, VOLT)
        )

        val leakage = ConnectionParameters.DEFAULT

        CellFactory {
            DiodeCell(it, poleMap, model, leakage)
        }
    }

    val DIODE_PART = partImmediateBB("diode", 3.0, 2.275, 16.0) {
        DiodePart(it)
    }
}
