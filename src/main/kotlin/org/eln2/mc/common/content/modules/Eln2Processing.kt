@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.phys.AABB
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.FrictionNodeDescription
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.DummyBlockEntityRendererProvider
import org.eln2.mc.client.screens.BasicProgressScreen
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItemAndDrop
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockItemOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockOnly
import org.eln2.mc.common.blocks.BlockRegistry.defineDelegateMap
import org.eln2.mc.common.blocks.BlockRegistry.withBlockDrop
import org.eln2.mc.common.blocks.foundation.BigBlockItem
import org.eln2.mc.common.cells.CellRegistry.cellImmediate
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.cells.foundation.ThermalSize
import org.eln2.mc.common.containers.ContainerRegistry.menu
import org.eln2.mc.common.content.*
import org.eln2.mc.common.content.processing.*
import org.eln2.mc.common.items.ItemRegistry.item
import org.eln2.mc.common.parts.PartRegistry.partAndItemWithProvider
import org.eln2.mc.common.recipes.*
import org.eln2.mc.common.recipes.RecipeRegistry.registerCatalyzedRecipe
import org.eln2.mc.common.recipes.RecipeRegistry.registerDirectRecipe
import org.eln2.mc.common.sounds.SoundRegistry.soundEventVariableRange
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.data.monopolarMapPlanar
import org.eln2.mc.data.nullPolarMap
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.resource

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
            KINETIC_ROLLING_MACHINE_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::KineticRollingMachineBlockEntityVisual) { true }
        )

        VisualizerRegistry.setVisualizer(
            VULCANIZING_AUTOCLAVE_MAIN_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::VulcanizingAutoclaveMainBlockEntityVisual) { true }
        )

        VisualizerRegistry.setVisualizer(
            COKE_OVEN_MAIN_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::CokeOvenMainBlockEntityVisual) { true }
        )

        VisualizerRegistry.setVisualizer(
            INSULATED_DISTILLATION_MODULE_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::DistillationModuleBlockEntityVisual) { true }
        )

        VisualizerRegistry.setVisualizer(
            CONDENSER_DISTILLATION_MODULE_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::DistillationModuleBlockEntityVisual) { true }
        )
    }

    override fun registerBlockEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(
            BLACKSMITHING_STATION_BLOCK_ENTITY.get(),
            BlacksmithingStationBlockEntityRenderer.Provider()
        )

        event.registerBlockEntityRenderer(
            COKE_OVEN_MAIN_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider()
        )

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
            KINETIC_ROLLING_MACHINE_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider()
        )

        event.registerBlockEntityRenderer(
            VULCANIZING_AUTOCLAVE_MAIN_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider()
        )

        event.registerBlockEntityRenderer(
            INSULATED_DISTILLATION_MODULE_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider()
        )

        event.registerBlockEntityRenderer(
            CONDENSER_DISTILLATION_MODULE_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider()
        )
    }

    override fun setupScreens() {
        MenuScreens.register(COKE_OVEN_MENU.get()) { menu, pInventory, title ->
            BasicProgressScreen(
                menu, pInventory, title,
                resource("textures/gui/container/coking_furnace_base.png"),
                resource("textures/gui/container/coking_furnace_progress.png"),
                134.0f,
                155.0f
            )
        }
        MenuScreens.register(FURNACE_MENU.get(), ::FurnaceScreen)
        MenuScreens.register(CRUSHER_MENU.get(), ::CrusherScreen)
        MenuScreens.register(EXTRUDER_MENU.get(), ::ExtruderScreen)

        MenuScreens.register(ROLLING_MACHINE_MENU.get()) { menu, inventory, title ->
            BasicProgressScreen(
                menu, inventory, title,
                resource("textures/gui/container/rolling_machine_base.png"),
                resource("textures/gui/container/rolling_machine_progress.png"),
                54.0f,
                129.0f
            )
        }
    }

    //#region Blacksmithing

    val BLACKSMITHING_RECIPE = RecipeRegistry.register<BlacksmithingRecipe>("blacksmithing") {
        BlacksmithingRecipe.Serializer(it)
    }

    val BLACKSMITHING_HAMMER_MANY_HITS_SOUND = soundEventVariableRange("blacksmithing/hammer_many_hits")

    val BLACKSMITHING_STATION_BLOCK = blockAndItemAndDrop("blacksmithing_station", ::BlacksmithingStationBlock)

    val BLACKSMITHING_STATION_BLOCK_ENTITY = blockEntityOnly(
        "blacksmithing_station",
        BLACKSMITHING_STATION_BLOCK.block,
        ::BlacksmithingStationBlockEntity
    )

    val BLACKSMITHING_HAMMER_ITEM = item("blacksmithing_hammer") {
        BlacksmithingToolItem(
            30,
            listOf(
                "flattening",
                "two_side_flattening"
            )
        )
    }

    val BLACKSMITHING_FILE_ITEM = item("blacksmithing_file") {
        BlacksmithingToolItem(14,
            listOf()
        )
    }

    val BLACKSMITHING_CHISEL_AND_HAMMER_ITEM = item("blacksmithing_chisel_and_hammer") {
        BlacksmithingToolItem(
            50,
            listOf()
        )
    }

    //#endregion

    //#region Coking

    val COKING_RECIPE = RecipeRegistry.register("coking") {
        CokingRecipe.Serializer(it)
    }

    val COKE_OVEN_DELEGATE_MAP = defineDelegateMap("coke_oven") {
        fun specialDelegate(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, mask: Base6Direction3dMask) =
            blockOnly("${getDelegateId()}_io") {
                CokeOvenDelegateBlock(
                    listOf(AABB(minX, minY, minZ, maxX, maxY, maxZ)),
                    mask
                )
            }

        val leftBottom = specialDelegate(
            0.0, 0.0, 0.0,
            0.5, 1.0, 1.0,
            Base6Direction3dMask.DOWN
        )

        val rightBottom = specialDelegate(
            0.5, 0.0, 0.0,
            1.0, 1.0, 1.0,
            Base6Direction3dMask.DOWN
        )

        val bottomCenter = specialDelegate(
            0.0, 0.0, 0.0,
            1.0, 1.0, 1.0,
            Base6Direction3d.Down + Base6Direction3d.Back + Base6Direction3d.Front
        )

        val top = specialDelegate(
            0.0, 0.0, 0.0,
            1.0, 1.0 - 4.0 / 16.0, 1.0,
            Base6Direction3dMask.EMPTY
        )

        val topBack = specialDelegate(
            0.0, 0.0, 0.0,
            1.0, 1.0 - 4.0 / 16.0, 1.0,
            Base6Direction3d.Back + Base6Direction3d.Front
        )

        val leftTop = specialDelegate(
            0.0, 0.0, 0.0,
            0.5, 1.0 - 4.0 / 16.0, 1.0,
            Base6Direction3d.Back + Base6Direction3d.Front
        )

        val rightTop = specialDelegate(
            0.5, 0.0, 0.0,
            1.0, 1.0 - 4.0 / 16.0, 1.0,
             Base6Direction3d.Back + Base6Direction3d.Front
        )

        principal(-1, 0, 0, rightBottom)
        principal(-1, 0, -1, rightBottom)

        principal(1, 0, 0, leftBottom)
        principal(1, 0, -1, leftBottom)

        principal(0, 0, -1, bottomCenter)

        principal(0, 1, 0, top)
        principal(0, 1, -1, topBack)

        principal(-1, 1, 0, rightTop)
        principal(-1, 1, -1, rightTop)

        principal(1, 1, 0, leftTop)
        principal(1, 1, -1, leftTop)
    }

    val COKE_OVEN_DELEGATE_BLOCK_ENTITY = blockEntityOnly("coke_oven_delegate_io", ::CokeOvenDelegateBlockEntity) {
        COKE_OVEN_DELEGATE_MAP.value.getBlocksOfType<CokeOvenDelegateBlock>()
    }

    val COKE_OVEN_MAIN_BLOCK = blockOnly("coke_oven", ::CokeOvenMainBlock)
        .withBlockDrop()

    val COKE_OVEN_MAIN_BLOCK_ENTITY = blockEntityOnly(
        "coke_oven",
        COKE_OVEN_MAIN_BLOCK,
        ::CokeOvenMainBlockEntity
    )

    val COKE_OVEN_BLOCK_ITEM = blockItemOnly("coke_oven") {
        BigBlockItem(
            COKE_OVEN_DELEGATE_MAP.value,
            COKE_OVEN_MAIN_BLOCK.get()
        )
    }

    val COKE_OVEN_MENU = menu("coke_oven", ::CokeOvenMenu)

    //#endregion

    //#region Distillation

    val DISTILLATION_COLUMN_CELL = cellImmediate("distillation_column", ::DistillationColumnCell)

    val DISTILLATION_COLUMN_BLOCK = blockAndItemAndDrop("distillation_column", ::DistillationColumnBlock)

    val DISTILLATION_COLUMN_BLOCK_ENTITY = blockEntityOnly(
        "distillation_column",
        DISTILLATION_COLUMN_BLOCK,
        ::DistillationColumnBlockEntity
    )

    val INSULATED_DISTILLATION_MODULE_CELL = cellMemoize("insulated_distillation_module") {
        val leakage = ConnectionParameters(conductance = Quantity(0.1, WATT_PER_KELVIN))

        CellFactory {
            DistillationModuleCell(it, leakage, false)
        }
    }

    val INSULATED_DISTILLATION_MODULE_BLOCK = blockAndItemAndDrop("insulated_distillation_module") {
        DistillationModuleBlock(
            INSULATED_DISTILLATION_MODULE_CELL,
            INSULATED_DISTILLATION_MODULE_BLOCK_ENTITY,
            DistillationModuleModel(false) {
                FlwModels.INSULATED_DISTILLATION_MODULE
            }
        )
    }

    val INSULATED_DISTILLATION_MODULE_BLOCK_ENTITY: RegistryObject<BlockEntityType<DistillationModuleBlockEntity>> = blockEntityOnly(
        "insulated_distillation_module",
        INSULATED_DISTILLATION_MODULE_BLOCK,
        ::DistillationModuleBlockEntity
    )

    val CONDENSER_DISTILLATION_MODULE_CELL = cellMemoize("condenser_distillation_module") {
        val leakage = ConnectionParameters(conductance = Quantity(5.0, WATT_PER_KELVIN))

        CellFactory {
            DistillationModuleCell(it, leakage, true)
        }
    }

    val CONDENSER_DISTILLATION_MODULE_BLOCK = blockAndItemAndDrop("condenser_distillation_module") {
        DistillationModuleBlock(
            CONDENSER_DISTILLATION_MODULE_CELL,
            CONDENSER_DISTILLATION_MODULE_BLOCK_ENTITY,
            DistillationModuleModel(true) {
                FlwModels.CONDENSER_DISTILLATION_MODULE
            }
        )
    }

    val CONDENSER_DISTILLATION_MODULE_BLOCK_ENTITY: RegistryObject<BlockEntityType<DistillationModuleBlockEntity>> = blockEntityOnly(
        "condenser_distillation_module",
        CONDENSER_DISTILLATION_MODULE_BLOCK,
        ::DistillationModuleBlockEntity
    )

    //#endregion

    //#region Tree Tap

    val TREE_TAP_PART = partAndItemWithProvider("tree_tap", TreeTapPartProvider())

    //#endregion

    //#region Furnace

    val FURNACE_CELL = cellMemoize("furnace_cell") {
        val map = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)

        CellFactory {
            FurnaceCell(it, map)
        }
    }

    val FURNACE_BLOCK = blockAndItemAndDrop("furnace") { FurnaceBlock() }

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

    val CRUSHER_BLOCK = blockAndItemAndDrop("crusher", ::CrusherBlock)

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

    val ELECTRIC_EXTRUDER_BLOCK = blockAndItemAndDrop("electric_extruder", ::ElectricExtruderBlock)

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

    val KINETIC_EXTRUDER_BLOCK = blockAndItemAndDrop("kinetic_extruder", ::KineticExtruderBlock)

    val KINETIC_EXTRUDER_BLOCK_ENTITY = blockEntityOnly("kinetic_extruder", KINETIC_EXTRUDER_BLOCK.block, ::KineticExtruderBlockEntity)

    val EXTRUDER_MENU = menu("extruder", ::ExtruderMenu)

    //#endregion

    //#region Rolling Machine

    val ROLLING_RECIPE = registerDirectRecipe("rolling")

    val KINETIC_ROLLING_MACHINE_CELL = cellMemoize("kinetic_rolling_machine") {
        val inertia = Quantity(0.491, KILOGRAM_METER2)

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

    val KINETIC_ROLLING_MACHINE_BLOCK = blockAndItemAndDrop("kinetic_rolling_machine", ::KineticRollingMachineBlock)

    val KINETIC_ROLLING_MACHINE_BLOCK_ENTITY = blockEntityOnly("kinetic_rolling_machine", KINETIC_ROLLING_MACHINE_BLOCK.block, ::KineticRollingMachineBlockEntity)

    val ROLLING_MACHINE_MENU = menu("rolling_machine", ::RollingMachineMenu)

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
        .withBlockDrop()

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
