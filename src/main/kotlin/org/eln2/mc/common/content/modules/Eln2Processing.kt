@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraftforge.client.event.EntityRenderersEvent
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.HENRY
import org.ageseries.libage.data.KILO
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.KILOGRAM_METER2
import org.ageseries.libage.data.MILLI
import org.ageseries.libage.data.NEWTON_METER
import org.ageseries.libage.data.NEWTON_METER_PER_AMPERE
import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.REVOLUTION_PER_SECOND
import org.ageseries.libage.data.VOLT
import org.ageseries.libage.data.VOLT_PER_RADIAN_PER_SECOND
import org.ageseries.libage.data.WATT
import org.ageseries.libage.data.WATT_PER_KELVIN
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.FrictionNodeDescription
import org.eln2.mc.client.render.foundation.DummyBlockEntityRendererProvider
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItem
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockItemOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockOnly
import org.eln2.mc.common.blocks.BlockRegistry.defineDelegateMap
import org.eln2.mc.common.blocks.foundation.BigBlockItem
import org.eln2.mc.common.cells.CellRegistry.cellImmediate
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.cells.foundation.ThermalSize
import org.eln2.mc.common.containers.ContainerRegistry.menu
import org.eln2.mc.common.content.CrusherBlock
import org.eln2.mc.common.content.CrusherBlockEntity
import org.eln2.mc.common.content.CrusherBlockEntityVisual
import org.eln2.mc.common.content.CrusherMenu
import org.eln2.mc.common.content.CrusherScreen
import org.eln2.mc.common.content.ElectricExtruderBlock
import org.eln2.mc.common.content.ElectricExtruderBlockEntity
import org.eln2.mc.common.content.ElectricExtruderBlockEntityVisual
import org.eln2.mc.common.content.ExtruderMenu
import org.eln2.mc.common.content.ExtruderScreen
import org.eln2.mc.common.content.FurnaceBlock
import org.eln2.mc.common.content.FurnaceBlockEntity
import org.eln2.mc.common.content.FurnaceCell
import org.eln2.mc.common.content.FurnaceMenu
import org.eln2.mc.common.content.FurnaceScreen
import org.eln2.mc.common.content.KineticExtruderBlock
import org.eln2.mc.common.content.KineticExtruderBlockEntity
import org.eln2.mc.common.content.KineticExtruderBlockEntityVisual
import org.eln2.mc.common.content.RubberTapPartProvider
import org.eln2.mc.common.content.VulcanizingAutoclaveMainBlock
import org.eln2.mc.common.content.VulcanizingAutoclaveMainBlockEntity
import org.eln2.mc.common.content.VulcanizingAutoclaveMainBlockEntityVisual
import org.eln2.mc.common.content.VulcanizingAutoclaveMainCell
import org.eln2.mc.common.content.VulcanizingAutoclaveThermalPortBlock
import org.eln2.mc.common.content.VulcanizingAutoclaveThermalPortBlockEntity
import org.eln2.mc.common.content.VulcanizingAutoclaveThermalPortCell
import org.eln2.mc.common.content.VulcanizingRecipe
import org.eln2.mc.common.parts.PartRegistry.partAndItemWithProvider
import org.eln2.mc.common.recipes.KineticProcessingCell
import org.eln2.mc.common.recipes.KineticProcessingCellKineticOptions
import org.eln2.mc.common.recipes.KineticProcessingCellOptions
import org.eln2.mc.common.recipes.MotorProcessingCell
import org.eln2.mc.common.recipes.MotorProcessingCellElectricalOptions
import org.eln2.mc.common.recipes.MotorProcessingCellOptions
import org.eln2.mc.common.recipes.ProcessingCellThermalOptions
import org.eln2.mc.common.recipes.RecipeRegistry
import org.eln2.mc.common.recipes.RecipeRegistry.registerCatalyzedRecipe
import org.eln2.mc.common.recipes.RecipeRegistry.registerDirectRecipe
import org.eln2.mc.common.sounds.SoundRegistry.soundEventVariableRange
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.data.monopolarMapPlanar
import org.eln2.mc.data.nullPolarMap
import org.eln2.mc.mathematics.Base6Direction3d

object Eln2Processing : ContentModule() {
    override fun registerBlockEntityVisualizers() {
        VisualizerRegistry.setVisualizer(
            CRUSHER_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::CrusherBlockEntityVisual) { true }
        )

        VisualizerRegistry.setVisualizer(
            ELECTRIC_EXTRUDER_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::ElectricExtruderBlockEntityVisual) { true }
        )

        VisualizerRegistry.setVisualizer(
            KINETIC_EXTRUDER_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::KineticExtruderBlockEntityVisual) { true }
        )

        VisualizerRegistry.setVisualizer(
            VULCANIZING_AUTOCLAVE_MAIN_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::VulcanizingAutoclaveMainBlockEntityVisual) { true }
        )
    }

    override fun registerBlockEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(
            CRUSHER_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider()
        )

        event.registerBlockEntityRenderer(
            ELECTRIC_EXTRUDER_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider()
        )

        event.registerBlockEntityRenderer(
            KINETIC_EXTRUDER_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider()
        )

        event.registerBlockEntityRenderer(
            VULCANIZING_AUTOCLAVE_MAIN_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider()
        )
    }

    override fun setupScreens() {
        MenuScreens.register(FURNACE_MENU.get(), ::FurnaceScreen)
        MenuScreens.register(CRUSHER_MENU.get(), ::CrusherScreen)
        MenuScreens.register(EXTRUDER_MENU.get(), ::ExtruderScreen)
    }

    //#region Rubber

    val RUBBER_TAP_PART = partAndItemWithProvider("rubber_tap", RubberTapPartProvider())

    //#endregion

    //#region Furnace

    val FURNACE_CELL = cellMemoize("furnace_cell") {
        val map = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)

        CellFactory {
            FurnaceCell(it, map)
        }
    }

    val FURNACE_BLOCK = blockAndItem("furnace") { FurnaceBlock() }

    val FURNACE_BLOCK_ENTITY = blockEntityOnly(
        "furnace",
        FURNACE_BLOCK.block,
        ::FurnaceBlockEntity
    )

    val FURNACE_MENU = menu("furnace_menu", ::FurnaceMenu)

    //#endregion

    //#region Crusher

    val CRUSHING_RECIPE = registerDirectRecipe("crushing")

    val BASIC_CRUSHER_CELL = cellMemoize("basic_crusher") {
        val options = MotorProcessingCellOptions(
            1.0,
            MotorProcessingCellElectricalOptions(
                Quantity(1.155, KILOGRAM_METER2),
                Quantity(10.0, KILO * OHM),
                Quantity(1.0, OHM),
                Quantity(1.25, MILLI * HENRY),
                Quantity(9.501, VOLT_PER_RADIAN_PER_SECOND),
                Quantity(2.05, NEWTON_METER_PER_AMPERE),
                50.0,
                0.124,
                3.0,
                Quantity(800.0, VOLT),
                Quantity(8155.1598, WATT)
            ),
            ProcessingCellThermalOptions(
                0.1,
                ThermalMassDefinition(
                    ChemicalElement.Iron.asMaterial,
                    mass = Quantity(5.0, KILOGRAM)
                ),
                ConnectionParameters(
                    Quantity(10.0, WATT_PER_KELVIN)
                ),
                Quantity(150.0, CELSIUS),
            )
        )

        val electricalMap = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)
        val thermalMap = directionPoleMapPlanar(Base6Direction3d.Back)

        CellFactory {
            MotorProcessingCell(it, options, electricalMap, thermalMap)
        }
    }

    val CRUSHER_BLOCK = blockAndItem("crusher", ::CrusherBlock)

    val CRUSHER_SOUND_ROCK = soundEventVariableRange("crusher.rock")

    val CRUSHER_BLOCK_ENTITY = blockEntityOnly("crusher", CRUSHER_BLOCK.block, ::CrusherBlockEntity)

    val CRUSHER_MENU = menu("crusher", ::CrusherMenu)

    //#endregion

    //#region Extruder

    val EXTRUDING_RECIPE = registerCatalyzedRecipe("extruding")

    val EXTRUDER_SOUND = soundEventVariableRange("extruder")

    val ELECTRIC_EXTRUDER_CELL = cellMemoize("electric_extruder") {
        val options = MotorProcessingCellOptions(
            1.0,
            MotorProcessingCellElectricalOptions(
                Quantity(1.155, KILOGRAM_METER2),
                Quantity(10.0, KILO * OHM),
                Quantity(41.561, OHM),
                Quantity(1.25, MILLI * HENRY),
                Quantity(2.06, VOLT_PER_RADIAN_PER_SECOND),
                Quantity(2.05, NEWTON_METER_PER_AMPERE),
                10.0,
                0.5,
                1.15,
                Quantity(800.0, VOLT),
                Quantity(8155.1598, WATT)
            ),
            null
        )

        val electricalMap = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)
        val thermalMap = nullPolarMap()

        CellFactory {
            MotorProcessingCell(it, options, electricalMap, thermalMap)
        }
    }

    val ELECTRIC_EXTRUDER_BLOCK = blockAndItem("electric_extruder", ::ElectricExtruderBlock)

    val ELECTRIC_EXTRUDER_BLOCK_ENTITY = blockEntityOnly("electric_extruder", ELECTRIC_EXTRUDER_BLOCK.block, ::ElectricExtruderBlockEntity)

    val KINETIC_EXTRUDER_CELL = cellMemoize("kinetic_extruder") {
        val inertia = Quantity(0.1251, KILOGRAM_METER2)

        val options = KineticProcessingCellOptions(
            1.0,
            KineticProcessingCellKineticOptions(
                FrictionNodeDescription(
                    inertia,
                    0.01,
                    Quantity(0.1, NEWTON_METER),
                    Quantity(1.0, NEWTON_METER)
                ),
                FrictionNodeDescription(
                    inertia,
                    10.0,
                    Quantity(0.5, NEWTON_METER),
                    Quantity(1.0, NEWTON_METER)
                ),
                Quantity(1.0, REVOLUTION_PER_SECOND),
                Quantity(25.0, REVOLUTION_PER_SECOND),
                Quantity(250.0, NEWTON_METER)
            ),
            null
        )

        val kineticMap = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)
        val thermalMap = nullPolarMap()

        CellFactory {
            KineticProcessingCell(it, options, kineticMap, thermalMap)
        }
    }

    val KINETIC_EXTRUDER_BLOCK = blockAndItem("kinetic_extruder", ::KineticExtruderBlock)

    val KINETIC_EXTRUDER_BLOCK_ENTITY = blockEntityOnly("kinetic_extruder", KINETIC_EXTRUDER_BLOCK.block, ::KineticExtruderBlockEntity)

    val EXTRUDER_MENU = menu("extruder", ::ExtruderMenu)

    //#endregion

    //#region Vulcanizing Autoclave

    val VULCANIZING_AUTOCLAVE_FAST_STEAM_RELEASE_SOUND = soundEventVariableRange("steam.release0")
    val VULCANIZING_AUTOCLAVE_SUCCESS_STEAM_RELEASE_SOUND = soundEventVariableRange("steam.release_long0")
    val VULCANIZING_AUTOCLAVE_BURNT_STEAM_RELEASE_SOUND = soundEventVariableRange("steam.release_long1")
    val VULCANIZING_AUTOCLAVE_HUM_SOUND = soundEventVariableRange("autoclave.hum")

    val VULCANIZING_RECIPE = RecipeRegistry.register<VulcanizingRecipe>("vulcanizing") {
        VulcanizingRecipe.Serializer(it)
    }

    val VULCANIZING_AUTOCLAVE_THERMAL_PORT_CELL = cellMemoize("vulcanizing_autoclave_thermal_port") {
        val thermalDef = ThermalMassDefinition(
            ChemicalElement.Copper.asMaterial,
            mass = Quantity(31.65, KILOGRAM)
        )

        val map = monopolarMapPlanar(Base6Direction3d.Left)
        val size = ThermalSize.Standard

        CellFactory {
            VulcanizingAutoclaveThermalPortCell(it, thermalDef, map, size)
        }
    }

    val VULCANIZING_AUTOCLAVE_THERMAL_PORT_BLOCK = blockOnly("vulcanizing_autoclave_thermal_port", ::VulcanizingAutoclaveThermalPortBlock)

    val VULCANIZING_AUTOCLAVE_THERMAL_PORT_BLOCK_ENTITY = blockEntityOnly(
        "vulcanizing_autoclave_thermal_port",
        VULCANIZING_AUTOCLAVE_THERMAL_PORT_BLOCK,
        ::VulcanizingAutoclaveThermalPortBlockEntity
    )

    val VULCANIZING_AUTOCLAVE_MAIN_CELL = cellImmediate("vulcanizing_autoclave", ::VulcanizingAutoclaveMainCell)

    val VULCANIZING_AUTOCLAVE_MAIN_BLOCK = blockOnly("vulcanizing_autoclave", ::VulcanizingAutoclaveMainBlock)

    val VULCANIZING_AUTOCLAVE_MAIN_BLOCK_ENTITY = blockEntityOnly(
        "vulcanizing_autoclave",
        VULCANIZING_AUTOCLAVE_MAIN_BLOCK,
        ::VulcanizingAutoclaveMainBlockEntity
    )

    val VULCANIZING_AUTOCLAVE_DELEGATE_MAP = defineDelegateMap("vulcanizing_autoclave") {
        principal(0, 0, -1, VULCANIZING_AUTOCLAVE_THERMAL_PORT_BLOCK)
    }

    val VULCANIZING_AUTOCLAVE_BLOCK_ITEM = blockItemOnly("vulcanizing_autoclave") {
        BigBlockItem(
            VULCANIZING_AUTOCLAVE_DELEGATE_MAP.value,
            VULCANIZING_AUTOCLAVE_MAIN_BLOCK.get()
        )
    }

    //#endregion
}
