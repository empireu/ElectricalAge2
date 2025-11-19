package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.model.Model
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.ShaderLightVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.items.ItemStackHandler
import net.minecraftforge.items.SlotItemHandler
import org.ageseries.libage.mathematics.FramerateIndependentSmoother1d
import org.ageseries.libage.mathematics.RotationUpdateProfile2d
import org.ageseries.libage.mathematics.computeRotationUpdateAccelerationProfileWithAccelerationEstimate
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.utils.Stopwatch
import org.eln2.mc.*
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicKineticPart
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.client.render.foundation.partTransformation
import org.eln2.mc.common.cells.foundation.InternalKineticReplicatorBehavior
import org.eln2.mc.common.cells.foundation.InternalKineticStateConsumer
import org.eln2.mc.common.cells.foundation.RotatingKineticState
import org.eln2.mc.common.containers.*
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.sendBulkPacket
import org.eln2.mc.common.recipes.KineticProcessingBlock
import org.eln2.mc.common.recipes.KineticProcessingBlockEntity
import org.eln2.mc.common.recipes.MotorProcessingBlock
import org.eln2.mc.common.recipes.MotorProcessingBlockEntity
import org.eln2.mc.common.recipes.foundation.CATALYST_SLOT
import org.eln2.mc.common.recipes.foundation.CatalyzedSimpleProcessingRecipe
import org.eln2.mc.common.recipes.foundation.INPUT_SLOT
import org.eln2.mc.common.recipes.foundation.OUTPUT_SLOT
import org.eln2.mc.extensions.recipeExists
import org.eln2.mc.extensions.transformFacingBlock
import java.util.function.Consumer

abstract class ExtruderBlockEntityVisual<BE>(
    body: Model,
    ctx: VisualizationContext,
    blockEntity: BE,
    partialTick: Float
) : AbstractBlockEntityVisual<BE>(ctx, blockEntity, partialTick), SimpleDynamicVisual where BE : BlockEntity {
    companion object {
        val centerA0 = FlwModels.getModelCenter(FlwModels.EXTRUDER_SHAFT_A0)
        val centerA1 = FlwModels.getModelCenter(FlwModels.EXTRUDER_SHAFT_A1)
        val centerB0 = FlwModels.getModelCenter(FlwModels.EXTRUDER_SHAFT_B0)
        val centerB1 = FlwModels.getModelCenter(FlwModels.EXTRUDER_SHAFT_B1)
    }

    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, body)
        .createInstance()
        .transformFacingBlock(visualPos, blockEntity)

    var die: TransformedInstance? = null

    val shaftA0: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.EXTRUDER_SHAFT_A0))
        .createInstance()

    val shaftA1: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.EXTRUDER_SHAFT_A1))
        .createInstance()

    val shaftB0: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.EXTRUDER_SHAFT_B0))
        .createInstance()

    val shaftB1: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.EXTRUDER_SHAFT_B1))
        .createInstance()

    var processRotation = Rotation2d.identity
    val processSpeedSmoother = FramerateIndependentSmoother1d(0.1) // Separate smoother (flywheel thread)

    protected fun poseShaft(instance: TransformedInstance, center: Vector3d, rotation: Double) {
        val z = center.z
        val y = center.y

        instance.setIdentityTransform()
            .translate(visualPos)
            .center()
            .rotateToFace(blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING))
            .uncenter()
            .translate(0.0, y, z)
            .rotateX(rotation.toFloat())
            .translate(0.0, -y, -z)
            .setChanged()
    }

    init {
        poseShaft(shaftA0, centerA0, 0.0)
        poseShaft(shaftA1, centerA1, 0.0)
        poseShaft(shaftB0, centerB0, 0.0)
        poseShaft(shaftB1, centerB1, 0.0)
    }

    abstract fun getProcessSpeed() : Double

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val dt = processSpeedSmoother.update(getProcessSpeed())
        processSpeedSmoother.pullDown()
        val incr = processSpeedSmoother.value * dt

        if(incr != 0.0) {
            processRotation += incr
            val angle = processRotation.ln()
            poseShaft(shaftA0, centerA0, -angle)
            poseShaft(shaftA1, centerA1, -angle)
            poseShaft(shaftB0, centerB0, +angle)
            poseShaft(shaftB1, centerB1, +angle)
        }

        val hasDie = blockState.getValue(WITH_DIE)

        if(die != null && !hasDie) {
            die!!.delete()
            die = null
        }
        else if(die == null && hasDie) {
            die = visualizationContext.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.EXTRUDER_DIE, Materials.CUTOUT_BLOCK))
                .createInstance()
                .transformFacingBlock(visualPos, blockEntity)

            relight(die)
        }
    }

    override fun updateLight(p0: Float) {
        relight(body, shaftA0, shaftA1, shaftB0, shaftB1, die)
    }

    override fun collectCrumblingInstances(p0: Consumer<Instance?>) {
        p0.accept(body)
    }

    override fun _delete() {
        body.delete()
        die?.delete()
        shaftA0.delete()
        shaftA1.delete()
        shaftB0.delete()
        shaftB1.delete()
    }
}

private val WITH_DIE: BooleanProperty = BooleanProperty.create("has_die")

private val size = Vector3d(16.0, 11.0, 16.0) / 32.0

private val collider = Shapes.box(
    0.5 - size.x, 0.5 - size.y, 0.5 - size.z,
    0.5 + size.x, 0.5 + size.y, 0.5 + size.z
)

//#region Electric

class ElectricExtruderBlock : MotorProcessingBlock<CatalyzedSimpleProcessingRecipe, ElectricExtruderBlockEntity>() {
    init {
        registerDefaultState(defaultBlockState().setValue(WITH_DIE, false))
    }

    override fun createBlockStateDefinition(pBuilder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(pBuilder)
        pBuilder.add(WITH_DIE)
    }

    override fun getTitle(): MutableComponent = Component.translatable("menu.$MODID.extruder")

    override fun createMenu(
        pBlockEntity: ElectricExtruderBlockEntity,
        pContainerId: Int,
        pPlayerInventory: Inventory,
    ) = ExtruderMenu(pBlockEntity, pContainerId, pPlayerInventory)

    override fun getCellProvider() = Eln2Processing.ELECTRIC_EXTRUDER_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = ElectricExtruderBlockEntity(pPos, pState)

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

class ElectricExtruderBlockEntity(pos: BlockPos, state: BlockState) : MotorProcessingBlockEntity<CatalyzedSimpleProcessingRecipe>(pos, state, Eln2Processing.ELECTRIC_EXTRUDER_BLOCK_ENTITY.get(), 3) {
    override fun getRecipe() = Eln2Processing.EXTRUDING_RECIPE

    override fun getSound() = Eln2Processing.EXTRUDER_SOUND

    override fun getInputSlots() = intArrayOf(INPUT_SLOT, CATALYST_SLOT)

    override fun serverTick() {
        super.serverTick()

        val hasDieItem = !inventoryHandler.getStackInSlot(CATALYST_SLOT).isEmpty

        if(hasDieItem != blockState.getValue(WITH_DIE)) {
            level!!.setBlock(
                blockPos,
                blockState.setValue(WITH_DIE, hasDieItem),
                Block.UPDATE_ALL
            )
        }
    }
}

class ElectricExtruderBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: ElectricExtruderBlockEntity,
    partialTick: Float
) : ExtruderBlockEntityVisual<ElectricExtruderBlockEntity>(
    PartialModelHelper.applyMaterial(FlwModels.EXTRUDER_BODY, Materials.CUTOUT_BLOCK),
    ctx,
    blockEntity,
    partialTick
) {
    override fun getProcessSpeed() = blockEntity.targetClientSpeed
}

//#endregion

//#region Kinetic

class KineticExtruderBlock : KineticProcessingBlock<CatalyzedSimpleProcessingRecipe, KineticExtruderBlockEntity>() {
    init {
        registerDefaultState(defaultBlockState().setValue(WITH_DIE, false))
    }

    override fun createBlockStateDefinition(pBuilder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(pBuilder)
        pBuilder.add(WITH_DIE)
    }

    override fun getTitle(): MutableComponent = Component.translatable("menu.$MODID.extruder")

    override fun createMenu(
        pBlockEntity: KineticExtruderBlockEntity,
        pContainerId: Int,
        pPlayerInventory: Inventory,
    ) = ExtruderMenu(pBlockEntity, pContainerId, pPlayerInventory)

    override fun getCellProvider() = Eln2Processing.KINETIC_EXTRUDER_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = KineticExtruderBlockEntity(pPos, pState)

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

class KineticExtruderBlockEntity(pos: BlockPos, state: BlockState) :
    KineticProcessingBlockEntity<CatalyzedSimpleProcessingRecipe>(pos, state, Eln2Processing.KINETIC_EXTRUDER_BLOCK_ENTITY.get(), 3),
    InternalKineticStateConsumer
{
    var renderState: BasicKineticPart.RenderStateImpl? = null
        private set

    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if(pLevel.isClientSide) {
            renderState = BasicKineticPart.RenderStateImpl()
        }
    }

    override fun getRecipe() = Eln2Processing.EXTRUDING_RECIPE

    override fun getSound() = Eln2Processing.EXTRUDER_SOUND

    override fun getInputSlots() = intArrayOf(INPUT_SLOT, CATALYST_SLOT)

    override fun serverTick() {
        super.serverTick()

        val hasDieItem = !inventoryHandler.getStackInSlot(CATALYST_SLOT).isEmpty

        if(hasDieItem != blockState.getValue(WITH_DIE)) {
            level!!.setBlock(
                blockPos,
                blockState.setValue(WITH_DIE, hasDieItem),
                Block.UPDATE_ALL
            )
        }
    }

    @ClientOnly
    override fun setupPacketsOnClient(handler: ClientSidePacketHandlerBuilder) {
        super.setupPacketsOnClient(handler)

        handler.withHandler<BasicKineticPart.RotationSyncPacket> {
            renderState!!.load(it)
        }
    }

    @ServerOnly
    override fun onKineticStateChanged(state: RotatingKineticState, angularAccelerationEstimate: Double, ) {
        sendBulkPacket(BasicKineticPart.RotationSyncPacket(
            state.angle,
            state.angularVelocity,
            angularAccelerationEstimate
        ))
    }
}

class KineticExtruderBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: KineticExtruderBlockEntity,
    partialTick: Float
) : ExtruderBlockEntityVisual<KineticExtruderBlockEntity>(
    PartialModelHelper.applyMaterial(FlwModels.EXTRUDER_KINETIC_BODY, Materials.CUTOUT_BLOCK),
    ctx,
    blockEntity,
    partialTick
), ShaderLightVisual {
    companion object {
        val shaftCenter = FlwModels.getModelCenter(FlwModels.EXTRUDER_KINETIC_SHAFT)
    }

    override fun getProcessSpeed() = blockEntity.targetClientSpeed

    val shaft: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.EXTRUDER_KINETIC_SHAFT, FlwMaterials.SMOOTH_LIT))
        .createInstance()

    var version = 0
    var rotation = Rotation2d.identity
    var velocity = 0.0
    var interpolationState: RotationUpdateProfile2d? = null
    val frameTimer = Stopwatch()

    init {
        poseShaft(shaft, shaftCenter, 0.0)
    }

    override fun beginFrame(p0: DynamicVisual.Context?) {
        super.beginFrame(p0)

        val renderState = blockEntity.renderState!!

        val targetVersion = renderState.version
        if(version != targetVersion) {
            version = targetVersion

            interpolationState = computeRotationUpdateAccelerationProfileWithAccelerationEstimate(
                renderState.angularAccelerationEstimate,
                Rotation2d.exp(renderState.angle), renderState.angularVelocity,
                rotation, velocity
            )
        }

        val dt = !frameTimer.sample()

        if(interpolationState == null) {
            rotation += velocity * dt
        }
        else {
            val state = interpolationState!!
            state.currentTime += dt
            state.sampleTrajectory()
            rotation = state.sampleP
            velocity = state.sampleV

            if(state.timeRemaining == 0.0) {
                interpolationState = null
            }
        }

        poseShaft(shaft, shaftCenter, rotation.ln())
    }

    override fun _delete() {
        super._delete()
        shaft.delete()
    }
}

//#endregion

class ExtruderMenu(
    pContainerId: Int,
    playerInventory: Inventory,
    handler: ItemStackHandler,
    val containerData: ProgressContainerData,
    val access: ContainerLevelAccess,
    val level: Level
) : AbstractContainerMenu(Eln2Processing.EXTRUDER_MENU.get(), pContainerId) {
    @ServerOnly
    constructor(entity: ElectricExtruderBlockEntity, id: Int, inventory: Inventory): this(
        id,
        inventory,
        entity.inventoryHandler,
        entity.data,
        ContainerLevelAccess.create(entity.level!!, entity.blockPos),
        entity.level!!
    )

    constructor(entity: KineticExtruderBlockEntity, id: Int, inventory: Inventory): this(
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
        ItemStackHandler(3),
        ProgressContainerData(),
        ContainerLevelAccess.NULL,
        playerInventory.player.level()
    )

    init {
        addSlot(
            SlotItemHandlerWithPlacePredicateAndSkipPickupCheck(handler, INPUT_SLOT, 33, 35) {
                val container = SimpleContainer(3)

                container.setItem(INPUT_SLOT, it)
                container.setItem(CATALYST_SLOT, handler.getStackInSlot(CATALYST_SLOT))

                level.recipeExists(Eln2Processing.EXTRUDING_RECIPE, container)
            }
        )

        addSlot(
            SlotItemHandlerWithPlacePredicate(handler, OUTPUT_SLOT, 131, 35) {
                false
            }
        )

        addSlot(
            SlotItemHandler(handler, CATALYST_SLOT, 85, 35)
        )

        addDataSlots(containerData)

        ContainerHelper.addPlayerGrid(playerInventory, this::addSlot)
    }

    override fun stillValid(pPlayer: Player) =
        stillValid(access, pPlayer, Eln2Processing.ELECTRIC_EXTRUDER_BLOCK.block.get()) ||
        stillValid(access, pPlayer, Eln2Processing.KINETIC_EXTRUDER_BLOCK.block.get())

    override fun quickMoveStack(pPlayer: Player, pIndex: Int) = ContainerHelper.quickMove(slots, pPlayer, pIndex)
}

class ExtruderScreen(menu: ExtruderMenu, playerInventory: Inventory, title: Component) : MyAbstractContainerScreen<ExtruderMenu>(menu, playerInventory, title) {
    companion object {
        private val BASE_TEXTURE = resource("textures/gui/container/extruder_base.png")
        private val PROGRESS_TEXTURE = resource("textures/gui/container/extruder_progress.png")
    }

    override fun renderBg(pGuiGraphics: GuiGraphics, pPartialTick: Float, pMouseX: Int, pMouseY: Int) {
        blitHelper(pGuiGraphics, BASE_TEXTURE)

        pGuiGraphics.blit(
            PROGRESS_TEXTURE,
            leftPos, topPos,
            0.0f,
            0.0f,
            map(
                menu.containerData.progress,
                0f,
                1f,
                54.0f,
                129.0f
            ).toInt(),
            256,
            256,
            256
        )
    }
}
