@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import org.eln2.mc.common.content.processing.CrusherMenu
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.core.BlockPos
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.utils.addUnique
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.DummyBlockEntityRendererProvider
import org.eln2.mc.client.screens.BasicProgressScreen
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItem
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockItemOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockOnly
import org.eln2.mc.common.blocks.BlockRegistry.defineDelegateMap
import org.eln2.mc.common.blocks.foundation.BigBlockItem
import org.eln2.mc.common.blocks.foundation.Eln2BlockItemWithCraftingRemainder
import org.eln2.mc.common.cells.CellRegistry.cellImmediate
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.cells.foundation.CellProvider
import org.eln2.mc.common.cells.foundation.ThermalSize
import org.eln2.mc.common.containers.ContainerRegistry.menu
import org.eln2.mc.common.content.*
import org.eln2.mc.common.content.modules.ContentManager.withSelfDrop
import org.eln2.mc.common.content.processing.*
import org.eln2.mc.common.items.ItemRegistry
import org.eln2.mc.common.items.ItemRegistry.item
import org.eln2.mc.common.items.ItemRegistry.itemNoStack
import org.eln2.mc.common.parts.PartRegistry.partAndItemWithProvider
import org.eln2.mc.common.recipes.RecipeRegistry
import org.eln2.mc.common.recipes.RecipeRegistry.registerCatalyzedRecipe
import org.eln2.mc.common.recipes.RecipeRegistry.registerDirectRecipe
import org.eln2.mc.common.sounds.SoundRegistry.soundEventVariableRange
import org.eln2.mc.directionPoleMapPlanar
import org.eln2.mc.mathematics.Axis3d
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.monopolarMapPlanar
import org.eln2.mc.resource
import java.util.function.Supplier

object Eln2Processing : ContentModule() {
    //#region Registration Helpers

    val PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN = LinkedHashSet<ProcessingMachineRegistryItem>()

    /**
     * @param box The box installed in the final usable machine.
     * @param blockAndItem The final, usable machine.
     * @param hullItem The craftable item that represents the machine.
     * */
    data class ProcessingMachineRegistryItem(
        val box: ProcessingCellRegistryItem<*>,
        val blockAndItem: BlockRegistry.BlockRegistryItem<*>,
        val blockEntity: RegistryObject<BlockEntityType<ProcessingMachineBlockEntity<*>>>,
        val modelSupplier: Supplier<ProcessingMachineCompositeModel>,
        val visualConstructor: ProcessingMachineVisualConstructor<*, ProcessingMachineBlockEntity<*>>,
        val hullItem: RegistryObject<Item>
    )

    /**
     * The constructor of the [ProcessingMachineBlock].
     * */
    fun interface ProcessingMachineBlockConstructor<C : ProcessingCell, BE : ProcessingMachineBlockEntity<C>> {
        fun create(cellProvider: RegistryObject<CellProvider<C>>) : ProcessingMachineBlock<C, BE>
    }

    /**
     * The constructor of the [ProcessingMachineBlockEntity].
     * */
    fun interface ProcessingMachineBlockEntityConstructor<C : ProcessingCell> {
        fun create(pos: BlockPos, state: BlockState) : ProcessingMachineBlockEntity<C>
    }

    /**
     * The constructor of the base or derived [ProcessingMachineBlockEntityVisual].
     * */
    fun interface ProcessingMachineVisualConstructor<C : ProcessingCell, BE : ProcessingMachineBlockEntity<C>> {
        fun create(
            ctx: VisualizationContext,
            blockEntity: BE,
            partialTick: Float,
            workBox: ProcessingCellType,
            composite: ProcessingMachineCompositeModel
        ) : ProcessingMachineBlockEntityVisual<C, BE>
    }

    /**
     * @param hullName The path of the ID.
     * @param hullItem The registered hull item.
     * */
    data class MachineHullRegistryItem(val hullName: String, val hullItem: RegistryObject<Item>)

    /**
     * Registers the machine hull item, which is then used in the [registerMachine] API.
     * */
    private fun registerMachineHull(hullName: String) : MachineHullRegistryItem {
        val hullItem = ItemRegistry.item(hullName) {
            Item(Item.Properties())
        }

        return MachineHullRegistryItem(hullName, hullItem)
    }

    /**
     * Registers a machine powered by a work box ([ProcessingCell]):
     * - the [blockConstructor] and [blockEntityConstructor] create the functional machine, that can be obtained as described next
     * - a [hull] was previously registered with [registerMachineHull], which is the item that will actually be crafted using the specific components needed for the machine
     * - to obtain the functional machine, the [box]'s [ProcessingCellRegistryItem.item] and the [ProcessingMachineRegistryItem.hullItem] are combined in the crafting table. This will give the item from [ProcessingMachineRegistryItem.blockAndItem] which places the final machine. This recipe is handled by datagen.
     * @param hull The hull item. The machine's ID will be [ProcessingCellRegistryItem.prefixToApply] + [MachineHullRegistryItem.hullName].
     * @param box The registered work box cell.
     * @param blockConstructor The constructor of the machine block.
     * @param blockEntityConstructor The constructor of the block entity.
     * @param modelLazy Supplier for the composite model. See its definition for more information.
     * @param visualConstructor The constructor for the composite visual. Usually you'd pass the constructor of [ProcessingMachineBlockEntityVisual], but you might need to extend it in order to add additional dynamic elements that don't fit the rotating elements from the composite model (e.g. [ExtruderBlockEntityVisual]).
     * @return All the relevant registered information. This will be added to [PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN], which registers the visual and dummy renderer in [registerBlockEntityVisualizers] and [registerBlockEntityRenderers], and is also accessed by datagen to create the recipes (and models) linking the hull item and the final machine.
     * */
    @Suppress("RemoveRedundantQualifierName")
    private inline fun<reified C : ProcessingCell, reified BE : ProcessingMachineBlockEntity<C>> registerMachine(
        hull: MachineHullRegistryItem,
        box: ProcessingCellRegistryItem<C>,
        blockConstructor: ProcessingMachineBlockConstructor<C, BE>,
        blockEntityConstructor: ProcessingMachineBlockEntityConstructor<C>,
        modelLazy: Lazy<ProcessingMachineCompositeModel>,
        visualConstructor: ProcessingMachineVisualConstructor<C, BE>
    ) : ProcessingMachineRegistryItem {
        ContentManager.requireInit()

        val hullName = hull.hullName
        val machineName = "${box.prefixToApply}_$hullName"

        val blockAndItem: BlockRegistry.BlockRegistryItem<ProcessingMachineBlock<C, BE>> = BlockRegistry
            .blockAndItem(
                machineName,
                /**
                 * Needed to get the box out of the machine (disassembly):
                 * */
                { block: Block ->
                    /**
                     * See the documentation of this wrapper to see why we need it:
                     * */
                    Eln2BlockItemWithCraftingRemainder(box.item, block, Item.Properties())
                },
                { blockConstructor.create(box.cellProvider) }
            ).withSelfDrop()

        val blockEntity = BlockRegistry.blockEntityOnly(machineName, blockAndItem) { pPos, pState ->
            blockEntityConstructor.create(pPos, pState)
        }

        @Suppress("UNCHECKED_CAST") val obj = ProcessingMachineRegistryItem(
            box,
            blockAndItem,
            blockEntity as RegistryObject<BlockEntityType<ProcessingMachineBlockEntity<*>>>,
            modelLazy::value,
            visualConstructor as ProcessingMachineVisualConstructor<*, ProcessingMachineBlockEntity<*>>,
            hull.hullItem
        )

        PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN.addUnique(obj)

        return obj
    }

    private fun registerWorkBoxMachineVisualizers() {
        PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN.forEach { obj ->
            val model = obj.modelSupplier.get()

            VisualizerRegistry.setVisualizer(
                obj.blockEntity.get(),
                SimpleBlockEntityVisualizer({ ctx, blockEntity, partialTick ->
                    obj.visualConstructor.create(
                        ctx, blockEntity, partialTick,
                        obj.box.boxType,
                        model
                    )
                }) { true }
            )
        }
    }

    private fun registerWorkBoxMachineBlockEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN.forEach { obj ->
            event.registerBlockEntityRenderer(
                obj.blockEntity.get(),
                DummyBlockEntityRendererProvider()
            )
        }
    }

    //#endregion

    override fun registerBlockEntityVisualizers() {
        /**
         * Registers processing machine visualizers:
         * */
        registerWorkBoxMachineVisualizers()

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
        /**
         * Registers processing machine dummy renderers:
         * */
        registerWorkBoxMachineBlockEntityRenderers(event)

        event.registerBlockEntityRenderer(
            BLACKSMITHING_STATION_BLOCK_ENTITY.get(),
            BlacksmithingStationBlockEntityRenderer.Provider()
        )

        event.registerBlockEntityRenderer(
            COKE_OVEN_MAIN_BLOCK_ENTITY.get(),
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

    val BLACKSMITHING_STATION_BLOCK = blockAndItem("blacksmithing_station", ::BlacksmithingStationBlock)
        .withSelfDrop()

    val BLACKSMITHING_STATION_BLOCK_ENTITY = blockEntityOnly(
        "blacksmithing_station",
        BLACKSMITHING_STATION_BLOCK.block,
        ::BlacksmithingStationBlockEntity
    )

    const val BLACKSMITHING_HAMMER_FLATTENING = "flattening"
    const val BLACKSMITHING_HAMMER_TWO_SIDE_FLATTENING = "two_side_flattening"

    val BLACKSMITHING_HAMMER_ITEM = item("blacksmithing_hammer") {
        BlacksmithingToolItem(
            30,
            listOf(
                BLACKSMITHING_HAMMER_FLATTENING,
                BLACKSMITHING_HAMMER_TWO_SIDE_FLATTENING
            )
        )
    }

    val BLACKSMITHING_FILE_ITEM = item("blacksmithing_file") {
        BlacksmithingToolItem(14,
            listOf()
        )
    }

    const val BLACKSMITHING_CHISEL_AND_HAMMER_SLICING = "slicing"

    val BLACKSMITHING_CHISEL_AND_HAMMER_ITEM = item("blacksmithing_chisel_and_hammer") {
        BlacksmithingToolItem(
            50,
            listOf(BLACKSMITHING_CHISEL_AND_HAMMER_SLICING)
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
        .withSelfDrop()

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

    val DISTILLATION_COLUMN_BLOCK = blockAndItem("distillation_column", ::DistillationColumnBlock)
        .withSelfDrop()

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

    val INSULATED_DISTILLATION_MODULE_BLOCK = blockAndItem("insulated_distillation_module") {
        DistillationModuleBlock(
            INSULATED_DISTILLATION_MODULE_CELL,
            INSULATED_DISTILLATION_MODULE_BLOCK_ENTITY,
            DistillationModuleModel(false) {
                FlwModels.INSULATED_DISTILLATION_MODULE
            }
        )
    }
        .withSelfDrop()

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

    val CONDENSER_DISTILLATION_MODULE_BLOCK = blockAndItem("condenser_distillation_module") {
        DistillationModuleBlock(
            CONDENSER_DISTILLATION_MODULE_CELL,
            CONDENSER_DISTILLATION_MODULE_BLOCK_ENTITY,
            DistillationModuleModel(true) {
                FlwModels.CONDENSER_DISTILLATION_MODULE
            }
        )
    }.withSelfDrop()

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

    val FURNACE_BLOCK = blockAndItem("furnace") { FurnaceBlock() }
        .withSelfDrop()

    val FURNACE_BLOCK_ENTITY = blockEntityOnly(
        "furnace",
        FURNACE_BLOCK.block,
        ::FurnaceBlockEntity
    )

    val FURNACE_MENU = menu("furnace_menu", ::FurnaceMenu)

    //#endregion

    //#region Work Boxes

    val BRUSHED_DC_MOTOR_WORK_BOX = MotorProcessingCell.register(
        "brushed_dc_motor_work_box", "brushed_dc_motor",
        MotorProcessingCellOptions.create(
            0.1,
            Quantity(50.0, VOLT),
            Quantity(750.0, WATT),
            Quantity(10.0, REVOLUTION_PER_SECOND),
            0.75,
            Quantity(2.5, SECOND),
            ThermalMassDefinition(
                ChemicalElement.Copper.asMaterial,
                mass = Quantity(10.0, KILOGRAM)
            ),
            ConnectionParameters.DEFAULT.copy(
                conductance = Quantity(5.0, WATT_PER_KELVIN)
            ),
        )
    )

    //#endregion

    //#region Crusher

    val CRUSHING_RECIPE = registerDirectRecipe("crushing")

    val CRUSHER_SOUND = soundEventVariableRange("crusher.rock")

    val CRUSHER_MODEL = lazy {
        ProcessingMachineCompositeModel(FlwModels.CRUSHER_BODY) {
            withElement(FlwModels.CRUSHER_GRINDER_0, -0.4, Axis3d.Z)
            withElement(FlwModels.CRUSHER_GRINDER_1, +0.4, Axis3d.Z)
        }
    }

    val CRUSHER_HULL = registerMachineHull("crusher")

    val CRUSHER_BRUSHED_DC_MOTOR = registerMachine(
        CRUSHER_HULL, BRUSHED_DC_MOTOR_WORK_BOX,
        {
            CrusherBlock(
                it,
                Quantity(600.0, WATT),
                1.0,
                0,
                0.25
            )
        },
        ::CrusherBlockEntity,
        CRUSHER_MODEL,
        ::ProcessingMachineBlockEntityVisual
    )

    val CRUSHER_MENU = menu("crusher", ::CrusherMenu)

    //#endregion

    //#region Extruder

    val EXTRUDING_RECIPE = registerCatalyzedRecipe("extruding")

    val EXTRUDER_SOUND = soundEventVariableRange("extruder")

    val EXTRUDER_MODEL = lazy {
        ProcessingMachineCompositeModel(FlwModels.EXTRUDER_BODY) {
            withElement(FlwModels.EXTRUDER_SHAFT_A0, 0.5)
            withElement(FlwModels.EXTRUDER_SHAFT_A1, 0.5)
            withElement(FlwModels.EXTRUDER_SHAFT_B0, 0.5)
            withElement(FlwModels.EXTRUDER_SHAFT_B1, 0.5)
        }
    }

    val EXTRUDER_HULL = registerMachineHull("extruder")

    val EXTRUDER_BRUSHED_DC_MOTOR = registerMachine(
        EXTRUDER_HULL, BRUSHED_DC_MOTOR_WORK_BOX,
        ::ExtruderBlock,
        ::ExtruderBlockEntity,
        EXTRUDER_MODEL,
        ::ExtruderBlockEntityVisual
    )

    val EXTRUDER_MENU = menu("extruder", ::ExtruderMenu)

    val EXTRUDER_ROD_DIE = itemNoStack("extruder_rod_die")
    val EXTRUDER_WIRE_DIE = itemNoStack("extruder_wire_die")
    val EXTRUDER_SHAFT_DIE = itemNoStack("extruder_shaft_die")

    //#endregion

    //#region Rolling Machine

    val ROLLING_RECIPE = registerDirectRecipe("rolling")

    val ROLLING_MACHINE_MODEL = lazy {
        ProcessingMachineCompositeModel(FlwModels.ROLLING_MACHINE_BODY)
    }

    val ROLLING_MACHINE_HULL = registerMachineHull("rolling_machine")

    val ROLLING_MACHINE_BRUSHED_DC_MOTOR = registerMachine(
        ROLLING_MACHINE_HULL, BRUSHED_DC_MOTOR_WORK_BOX,
        ::RollingMachineBlock,
        ::RollingMachineBlockEntity,
        ROLLING_MACHINE_MODEL,
        ::ProcessingMachineBlockEntityVisual
    )

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
        .withSelfDrop()

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
