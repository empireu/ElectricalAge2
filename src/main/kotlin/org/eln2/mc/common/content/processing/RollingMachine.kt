/*
package org.eln2.mc.common.content.processing

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.ShaderLightVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.items.ItemStackHandler
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.ClientOnly
import org.eln2.mc.MODID
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicKineticPart
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.client.screens.ProgressSupplierMenu
import org.eln2.mc.common.cells.foundation.InternalKineticStateConsumer
import org.eln2.mc.common.cells.foundation.KineticInterpolatorClient
import org.eln2.mc.common.cells.foundation.RotatingKineticState
import org.eln2.mc.common.containers.ContainerHelper
import org.eln2.mc.common.containers.ProgressContainerData
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicate
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicateAndSkipPickupCheck
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.sendBulkPacket
import org.eln2.mc.common.recipes.foundation.DirectSimpleProcessingRecipe
import org.eln2.mc.common.recipes.foundation.INPUT_SLOT
import org.eln2.mc.common.recipes.foundation.OUTPUT_SLOT
import org.eln2.mc.extensions.recipeExists
import org.eln2.mc.extensions.transformFacingBlock
import java.util.function.Consumer

private val size = Vector3d(16.0, 11.0, 16.0) / 32.0

private val collider = Shapes.box(
    0.5 - size.x, 0.5 - size.y, 0.5 - size.z,
    0.5 + size.x, 0.5 + size.y, 0.5 + size.z
)

//#region Kinetic

class KineticRollingMachineBlock : KineticProcessingBlock<DirectSimpleProcessingRecipe, KineticRollingMachineBlockEntity>() {
    override fun getTitle(): Component = Component.translatable("menu.$MODID.rolling_machine")

    override fun createMenu(pBlockEntity: KineticRollingMachineBlockEntity, pContainerId: Int, pPlayerInventory: Inventory) = RollingMachineMenu(pBlockEntity, pContainerId, pPlayerInventory)

    override fun getCellProvider() = Eln2Processing.KINETIC_ROLLING_MACHINE_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = KineticRollingMachineBlockEntity(pPos, pState)

    //#region Collider

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getCollisionShape(
        pState: BlockState,
        pLevel: BlockGetter,
        pPos: BlockPos,
        pContext: CollisionContext,
    ): VoxelShape = collider

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getShape(
        pState: BlockState,
        pLevel: BlockGetter,
        pPos: BlockPos,
        pContext: CollisionContext,
    ): VoxelShape = collider

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getVisualShape(
        pState: BlockState,
        pLevel: BlockGetter,
        pPos: BlockPos,
        pContext: CollisionContext,
    ): VoxelShape = collider

    //#endregion
}

class KineticRollingMachineBlockEntity(pPos: BlockPos, pState: BlockState) :
    KineticProcessingBlockEntity<DirectSimpleProcessingRecipe>(pPos, pState, Eln2Processing.KINETIC_ROLLING_MACHINE_BLOCK_ENTITY.get(), 2),
    InternalKineticStateConsumer {
    var renderState: BasicKineticPart.RenderStateImpl? = null
        private set

    override fun getRecipe() = Eln2Processing.ROLLING_RECIPE

    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if(pLevel.isClientSide) {
            renderState = BasicKineticPart.RenderStateImpl()
        }
    }

    @ClientOnly
    override fun setupPacketsOnClient(handler: ClientSidePacketHandlerBuilder) {
        super.setupPacketsOnClient(handler)

        handler.withHandler<RotatingKineticState> {
            renderState!!.load(it)
        }
    }

    @ServerOnly
    override fun onKineticStateChanged(state: RotatingKineticState) {
        sendBulkPacket(state)
    }
}

class KineticRollingMachineBlockEntityVisual(
    ctx: VisualizationContext,
    block: KineticRollingMachineBlockEntity,
    partialTick: Float
) : AbstractBlockEntityVisual<KineticRollingMachineBlockEntity>(ctx, block, partialTick), ShaderLightVisual, SimpleDynamicVisual {
    companion object {
        val SHAFT_CENTER = FlwModels.getModelCenter(FlwModels.ROLLING_MACHINE_KINETIC_SHAFT)
    }

    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.ROLLING_MACHINE_KINETIC_BODY, FlwMaterials.CUTOUT_SMOOTH_LIT))
        .createInstance()
        .transformFacingBlock(visualPos, blockEntity)

    val shaft: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.ROLLING_MACHINE_KINETIC_SHAFT, FlwMaterials.SMOOTH_LIT))
        .createInstance()

    val interpolator = KineticInterpolatorClient()

    private fun poseShaft() {
        val z = SHAFT_CENTER.z
        val y = SHAFT_CENTER.y

        shaft.setIdentityTransform()
            .translate(visualPos)
            .center()
            .rotateToFace(blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING))
            .uncenter()
            .translate(0.0, y, z)
            .rotateX(interpolator.clientRotation.ln().toFloat())
            .translate(0.0, -y, -z)
            .setChanged()
    }

    init {
        poseShaft()
    }

    var version = 0

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val renderState = blockEntity.renderState
            ?: return

        val targetVersion = renderState.version

        if(targetVersion != version) {
            version = targetVersion
            interpolator.applyServerState(renderState.angle, renderState.angularVelocity)
        }

        interpolator.update()
        poseShaft()
    }

    override fun updateLight(p0: Float) {
        // NOOP
    }

    override fun collectCrumblingInstances(p0: Consumer<Instance?>) {
        p0.accept(body)
        p0.accept(shaft)
    }

    override fun _delete() {
        body.delete()
        shaft.delete()
    }
}

//#endregion

class RollingMachineMenu(
    pContainerId: Int,
    playerInventory: Inventory,
    handler: ItemStackHandler,
    val containerData: ProgressContainerData,
    val access: ContainerLevelAccess,
    val level: Level
) : AbstractContainerMenu(Eln2Processing.ROLLING_MACHINE_MENU.get(), pContainerId), ProgressSupplierMenu {
    @ServerOnly
    constructor(entity: KineticRollingMachineBlockEntity, id: Int, inventory: Inventory): this(
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

    override fun stillValid(pPlayer: Player) =
        stillValid(access, pPlayer, Eln2Processing.KINETIC_ROLLING_MACHINE_BLOCK.block.get())

    override fun quickMoveStack(pPlayer: Player, pIndex: Int) = ContainerHelper.quickMove(slots, pPlayer, pIndex)

    override fun getProgressForRender() = containerData.progress
}
*/
