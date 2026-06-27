@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.minecraftforge.client.event.EntityRenderersEvent
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT_PER_KELVIN
import org.ageseries.libage.data.WATT_PER_METER_KELVIN
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.client.render.foundation.DummyBlockEntityRendererProvider
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItem
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.content.*
import org.eln2.mc.common.content.modules.ContentManager.withSelfDrop
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.monopolarMapPlanar

object Eln2HeatGenerators : ContentModule() {
    override fun registerBlockEntityVisualizers() {
        VisualizerRegistry.setVisualizer(
            PRIMITIVE_BURNER_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::PrimitiveBurnerBlockEntityVisual) { true }
        )
    }

    override fun registerBlockEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(
            PRIMITIVE_BURNER_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider()
        )
    }

    //#region Primitive Burner

    val PRIMITIVE_BURNER_CELL = cellMemoize("primitive_burner") {
        val options = BurnerCellOptions(
            BurnerDeviceDescription(
                Quantity(15.0, WATT_PER_METER_KELVIN),
                Quantity(171.6, WATT_PER_METER_KELVIN),
                Quantity(0.05, WATT_PER_KELVIN),
                Quantity(1.21, WATT_PER_METER_KELVIN)
            ),
            ThermalMassDefinition(
                ChemicalElement.Iron.asMaterial,
                mass = Quantity(14.019, KILOGRAM)
            ),
            ConnectionParameters(
                conductance = Quantity(9.1, WATT_PER_KELVIN)
            )
        )

        val map = monopolarMapPlanar(Base6Direction3d.Back)
        val maxDraft = 0.05

        CellFactory {
            PrimitiveBurnerCell(it, options, map, maxDraft)
        }
    }

    val PRIMITIVE_BURNER_BLOCK = blockAndItem("primitive_burner", ::PrimitiveBurnerBlock)
        .withSelfDrop()

    val PRIMITIVE_BURNER_BLOCK_ENTITY = blockEntityOnly("primitive_burner", PRIMITIVE_BURNER_BLOCK.block, ::PrimitiveBurnerBlockEntity)

    //#endregion
}
