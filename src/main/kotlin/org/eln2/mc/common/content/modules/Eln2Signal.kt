@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import net.minecraft.client.gui.screens.MenuScreens
import org.ageseries.libage.mathematics.geometry.Vector4d
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicPartVisual
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry.setPartVisualizer
import org.eln2.mc.common.cells.CellRegistry.cellImmediate
import org.eln2.mc.common.cells.foundation.ElectricalSize
import org.eln2.mc.common.containers.ContainerRegistry.menu
import org.eln2.mc.common.content.OscilloscopeCell
import org.eln2.mc.common.content.OscilloscopeChannelGenerators
import org.eln2.mc.common.content.OscilloscopePalette
import org.eln2.mc.common.content.OscilloscopePart
import org.eln2.mc.common.content.OscilloscopePart.OscilloscopeScreen
import org.eln2.mc.common.content.OscilloscopeSpecification
import org.eln2.mc.common.content.PotentialProbeCell
import org.eln2.mc.common.content.PotentialProbePart
import org.eln2.mc.common.grids.GridMaterialCategory
import org.eln2.mc.common.parts.PartRegistry.partImmediateBB
import org.eln2.mc.common.parts.PartRegistry.partMemoizeBB
import org.eln2.mc.common.parts.foundation.PartFactory
import org.eln2.mc.common.parts.foundation.eln2ReadPartGuiData
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.data.monopolarMapPlanar
import org.eln2.mc.data.nullMonopoleMap
import org.eln2.mc.mathematics.Base6Direction3d

object Eln2Signal : ContentModule() {
    override fun registerPartVisualizers() {
        setPartVisualizer<OscilloscopePart>(FLAT_OSCILLOSCOPE_PART.part.get()) { ctx, part ->
            BasicPartVisual(
                ctx, part,
                FlwModels.FLAT_OSCILLOSCOPE_PART
            )
        }

        setPartVisualizer<OscilloscopePart>(BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_PART.part.get()) { ctx, part ->
            BasicPartVisual(
                ctx, part,
                FlwModels.BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_PART,
                smoothLighting = true
            )
        }
    }

    override fun setupScreens() {
        MenuScreens.register(FLAT_OSCILLOSCOPE_MENU.get(), ::OscilloscopeScreen)
    }

    //#region Probes

    // All probe models should be made like this, and all cells use these directions.
    val STANDARD_PROBE_PLUS_DIRECTION = Base6Direction3d.Front
    val STANDARD_PROBE_MINUS_DIRECTION = Base6Direction3d.Back
    val STANDARD_PROBE_OUTPUT_DIRECTION = Base6Direction3d.Right

    val STANDARD_PROBE_COMPARER_MAP = directionPoleMapPlanar(STANDARD_PROBE_PLUS_DIRECTION, STANDARD_PROBE_MINUS_DIRECTION)
    val STANDARD_PROBE_OUTPUT_MAP = monopolarMapPlanar(STANDARD_PROBE_OUTPUT_DIRECTION)

    val STANDARD_PROBE_MODELS = mapOf(
        STANDARD_PROBE_PLUS_DIRECTION to FlwModels.STANDARD_CONNECTION,
        STANDARD_PROBE_MINUS_DIRECTION to FlwModels.STANDARD_CONNECTION,
        STANDARD_PROBE_OUTPUT_DIRECTION to FlwModels.SIGNAL_WIRE_CONNECTION.hub
    )

    val POTENTIAL_PROBE_CELL = cellImmediate("potential_probe") {
        PotentialProbeCell(
            it,
            STANDARD_PROBE_COMPARER_MAP,
            ElectricalSize.Standard,
            STANDARD_PROBE_OUTPUT_MAP
        )
    }

    val POTENTIAL_PROBE_PART = partImmediateBB("potential_probe", 6.0, 2.025, 9.5) {
        PotentialProbePart(it, STANDARD_PROBE_MODELS)
    }

    //#endregion

    //#region Oscilloscopes

    val BASIC_TWO_CHANNEL_OSCILLOSCOPE_SPECIFICATION = OscilloscopeSpecification(
        2,
        10,
        1000,
        OscilloscopePalette.DEFAULT,
        0.02f,
        0.0075f,
        Vector4d(0.8, 0.8, 1.0, 0.2),
        11,
        0.5f
    )

    val BASIC_TWO_CHANNEL_OSCILLOSCOPE_CELL = cellImmediate("basic_two_channel_oscilloscope") {
        OscilloscopeCell(it, nullMonopoleMap(), BASIC_TWO_CHANNEL_OSCILLOSCOPE_SPECIFICATION)
    }

    val FLAT_OSCILLOSCOPE_MENU = menu("basic_two_channel_oscilloscope_menu") { i, inv, buf ->
        OscilloscopePart.OscilloscopeMenu(i, buf.eln2ReadPartGuiData<OscilloscopePart>(inv))
    }

    val FLAT_OSCILLOSCOPE_PART = partMemoizeBB("basic_two_channel_oscilloscope", 15.2, 0.75, 10.0) {
        val generators = OscilloscopeChannelGenerators.create {
            withGridTerminal {
                defineCellBoxTerminalBB(
                    0.375, 0.1, 6.0,
                    0.625, 0.5, 0.5,
                    highlightColor = specification.palette.colorsInt[0],
                    categories = listOf(GridMaterialCategory.SignalGrid)
                )
            }

            withGridTerminal {
                defineCellBoxTerminalBB(
                    0.375, 0.1, 9.475,
                    0.625, 0.5, 0.5,
                    highlightColor = specification.palette.colorsInt[1],
                    categories = listOf(GridMaterialCategory.SignalGrid)
                )
            }
        }

        PartFactory {
            OscilloscopePart(it, BASIC_TWO_CHANNEL_OSCILLOSCOPE_SPECIFICATION, generators, BASIC_TWO_CHANNEL_OSCILLOSCOPE_CELL.get())
        }
    }

    val BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_SPECIFICATION = OscilloscopeSpecification(
        1,
        10,
        4096,
        OscilloscopePalette.DEFAULT,
        0.02f,
        0.0075f,
        Vector4d(0.8, 0.8, 1.0, 0.2),
        11,
        0.5f
    )

    val BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_CELL = cellImmediate("basic_single_channel_oscilloscope") {
        OscilloscopeCell(it, monopolarMapPlanar(Base6Direction3d.Left), BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_SPECIFICATION)
    }

    val BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_PART = partMemoizeBB("basic_single_channel_oscilloscope", 15.2, 0.75, 10.0) {
        val generators = OscilloscopeChannelGenerators.create {
            withGridTerminal {
                defineCellBoxTerminalBB(
                    0.0, 0.0, 7.75,
                    1.0, 0.4, 0.5,
                    highlightColor = specification.palette.colorsInt[0],
                    categories = listOf(GridMaterialCategory.SignalGrid)
                )
            }
        }

        PartFactory {
            OscilloscopePart(it, BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_SPECIFICATION, generators, BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_CELL.get())
        }
    }

    //#endregion
}
