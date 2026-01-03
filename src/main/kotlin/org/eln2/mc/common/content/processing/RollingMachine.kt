package org.eln2.mc.common.content.processing

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.items.ItemStackHandler
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.ClientOnly
import org.eln2.mc.MODID
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.screens.ProgressSupplierMenu
import org.eln2.mc.common.cells.foundation.CellProvider
import org.eln2.mc.common.containers.ContainerHelper
import org.eln2.mc.common.containers.ProgressContainerData
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicate
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicateAndSkipPickupCheck
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.recipes.foundation.DirectSimpleProcessingRecipe
import org.eln2.mc.common.recipes.foundation.INPUT_SLOT
import org.eln2.mc.common.recipes.foundation.OUTPUT_SLOT
import org.eln2.mc.extensions.eln2StillValid
import org.eln2.mc.extensions.recipeExists

class RollingMachineBlock<C : ProcessingCell>(cell: RegistryObject<CellProvider<C>>) : ProcessingMachineBlock<C, RollingMachineBlockEntity<C>>(cell) {
    companion object {
        private val size = Vector3d(16.0, 11.0, 16.0) / 32.0

        private val collider = Shapes.box(
            0.5 - size.x, 0.5 - size.y, 0.5 - size.z,
            0.5 + size.x, 0.5 + size.y, 0.5 + size.z
        )
    }

    override fun getTitle(): Component = Component.translatable("menu.$MODID.rolling_machine")

    override fun createMenu(pBlockEntity: RollingMachineBlockEntity<C>, pContainerId: Int, pPlayerInventory: Inventory) = RollingMachineMenu(pBlockEntity, pContainerId, pPlayerInventory)

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = RollingMachineBlockEntity<C>(pPos, pState)

    override fun getColliderFor(pState: BlockState, pLevel: BlockGetter, pPos: BlockPos, pContext: CollisionContext): VoxelShape = collider
}

class RollingMachineBlockEntity<C : ProcessingCell>(pPos: BlockPos, pState: BlockState) : SimpleProcessingMachineBlockEntity<C, DirectSimpleProcessingRecipe>(pPos, pState, 2) {
    override val recipe: RecipeType<DirectSimpleProcessingRecipe>
        get() = Eln2Processing.ROLLING_RECIPE

    override fun loadSettings() {
        super.loadSettings()
        cell.loadPower = Quantity(350.0, WATT)
        cell.thermalFactor = 0.1
    }
}

class RollingMachineMenu(
    pContainerId: Int,
    playerInventory: Inventory,
    handler: ItemStackHandler,
    val containerData: ProgressContainerData,
    val access: ContainerLevelAccess,
    val level: Level
) : AbstractContainerMenu(Eln2Processing.ROLLING_MACHINE_MENU.get(), pContainerId), ProgressSupplierMenu {
    @ServerOnly
    constructor(entity: RollingMachineBlockEntity<*>, id: Int, inventory: Inventory): this(
        id,
        inventory,
        entity.inventoryHandler,
        entity.data,
        ContainerLevelAccess.create(entity.level!!, entity.blockPos),
        entity.level!!
    )

    @ClientOnly
    constructor(pContainerId: Int, playerInventory: Inventory) : this(
        pContainerId,
        playerInventory,
        ItemStackHandler(2),
        ProgressContainerData(),
        ContainerLevelAccess.NULL,
        playerInventory.player.level()
    )

    init {
        addSlot(
            SlotItemHandlerWithPlacePredicateAndSkipPickupCheck(handler, INPUT_SLOT, 33, 35) {
                val container = SimpleContainer(2)
                container.setItem(INPUT_SLOT, it)
                level.recipeExists(Eln2Processing.ROLLING_RECIPE, container)
            }
        )

        addSlot(
            SlotItemHandlerWithPlacePredicate(handler, OUTPUT_SLOT, 131, 35) {
                false
            }
        )

        addDataSlots(containerData)

        ContainerHelper.addPlayerGrid(playerInventory, this::addSlot)
    }

    override fun stillValid(pPlayer: Player) = eln2StillValid<RollingMachineBlock<*>>(access, pPlayer)
    override fun quickMoveStack(pPlayer: Player, pIndex: Int) = ContainerHelper.quickMove(slots, pPlayer, pIndex)
    override fun getProgressForRender() = containerData.progress
}
