@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraftforge.client.event.EntityRenderersEvent
import org.ageseries.libage.data.JOULE_PER_KILOGRAM_KELVIN
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT_PER_KELVIN
import org.ageseries.libage.data.WATT_PER_METER_KELVIN
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.client.render.foundation.DummyBlockEntityRendererProvider
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItem
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.containers.ContainerRegistry.menu
import org.eln2.mc.common.content.BurnerCellOptions
import org.eln2.mc.common.content.BurnerDeviceDescription
import org.eln2.mc.common.content.HeatGeneratorBlock
import org.eln2.mc.common.content.HeatGeneratorBlockEntity
import org.eln2.mc.common.content.HeatGeneratorCell
import org.eln2.mc.common.content.HeatGeneratorMenu
import org.eln2.mc.common.content.HeatGeneratorScreen
import org.eln2.mc.common.content.PrimitiveBurnerBlock
import org.eln2.mc.common.content.PrimitiveBurnerBlockEntity
import org.eln2.mc.common.content.PrimitiveBurnerBlockEntityVisual
import org.eln2.mc.common.content.PrimitiveBurnerCell
import org.eln2.mc.data.monopolarMapPlanar
import org.eln2.mc.mathematics.Base6Direction3d

object Eln2HeatGenerators : ContentModule() {
    override fun setupScreens() {
        MenuScreens.register(HEAT_GENERATOR_MENU.get(), ::HeatGeneratorScreen)
    }

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
                Quantity(4.617, WATT_PER_METER_KELVIN)
            ),
            ThermalMassDefinition(
                ChemicalElement.Copper.asMaterial,
                mass = Quantity(26.1145, KILOGRAM)
            ),
            ConnectionParameters()
        )

        val map = monopolarMapPlanar(Base6Direction3d.Back)
        val maxDraft = 0.02

        CellFactory {
            PrimitiveBurnerCell(it, options, map, maxDraft)
        }
    }

    val PRIMITIVE_BURNER_BLOCK = blockAndItem("primitive_burner", ::PrimitiveBurnerBlock)

    val PRIMITIVE_BURNER_BLOCK_ENTITY = blockEntityOnly("primitive_burner", PRIMITIVE_BURNER_BLOCK.block, ::PrimitiveBurnerBlockEntity)

    //#endregion

    val HEAT_GENERATOR_CELL = cellMemoize("heat_generator") {
        val thermalDefinition = ThermalMassDefinition(
            Material(
                label = "Heat generator",
                electricalResistivity = Quantity(Double.POSITIVE_INFINITY),
                thermalConductivity = Quantity(5000.0, WATT_PER_METER_KELVIN),
                specificHeat = ChemicalElement.Copper.specificHeat,
                density = ChemicalElement.Copper.density
            ),
            mass = Quantity(10.0, KILOGRAM)
        )

        val leakageParameters = ConnectionParameters.DEFAULT.copy(
            conductance = Quantity(0.001, WATT_PER_KELVIN)
        )

        CellFactory {
            HeatGeneratorCell(it, thermalDefinition, leakageParameters)
        }
    }

    val HEAT_GENERATOR_BLOCK = blockAndItem("heat_generator") { HeatGeneratorBlock() }

    val HEAT_GENERATOR_BLOCK_ENTITY = blockEntityOnly("heat_generator", HEAT_GENERATOR_BLOCK, ::HeatGeneratorBlockEntity)

    val HEAT_GENERATOR_MENU = menu("heat_generator", ::HeatGeneratorMenu)
}
