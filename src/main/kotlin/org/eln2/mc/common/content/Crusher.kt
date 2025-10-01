package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.items.ItemStackHandler
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.map
import org.eln2.mc.*
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.recipes.MotorProcessingCell
import org.eln2.mc.common.containers.ContainerHelper
import org.eln2.mc.common.containers.MyAbstractContainerScreen
import org.eln2.mc.common.containers.ProgressContainerData
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicate
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicateAndSkipPickupCheck
import org.eln2.mc.common.recipes.MotorProcessingBlock
import org.eln2.mc.common.recipes.MotorProcessingBlockEntity
import org.eln2.mc.common.recipes.foundation.DirectSimpleProcessingRecipe
import org.eln2.mc.common.recipes.foundation.INPUT_SLOT
import org.eln2.mc.common.recipes.foundation.OUTPUT_SLOT
import org.eln2.mc.extensions.canCrush
import org.eln2.mc.extensions.nextDouble
import org.eln2.mc.extensions.transformFacingBlock
import org.joml.Vector3f
import java.util.function.Consumer
import kotlin.math.ceil

class CrusherBlock : MotorProcessingBlock<DirectSimpleProcessingRecipe, CrusherBlockEntity>() {
    override fun getTitle(): MutableComponent = Component.translatable("manu.$MODID.crusher")

    override fun createMenu(
        pBlockEntity: CrusherBlockEntity,
        pContainerId: Int,
        pPlayerInventory: Inventory,
    ) = CrusherMenu(pBlockEntity, pContainerId, pPlayerInventory)

    override fun getCellProvider() = Content.BASIC_CRUSHER_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = CrusherBlockEntity(pPos, pState)

    /**
     * Animates bursts of particles based on speed.
     * */
    override fun animateMachineTick(blockEntity: CrusherBlockEntity, speed: Double, pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) {
        val sparkBurst = ceil(speed * 4).toInt()
        val dustBurst = ceil(speed * 2).toInt()

        val cx = pPos.x + 0.5
        val topY = pPos.y + 0.9
        val cz = pPos.z + 0.5

        repeat(sparkBurst) {
            val rx = (pRandom.nextDouble() - 0.5) * 0.9
            val rz = (pRandom.nextDouble() - 0.5) * 0.9
            val x = cx + rx
            val z = cz + rz
            val y = topY + pRandom.nextDouble() * 0.1

            // velocity: biased upward and outwards; scale with speed
            val vx = rx * 0.02 * (0.5 + speed * 2.0)
            val vy = 0.06 + pRandom.nextDouble() * 0.06 * (0.5 + speed)
            val vz = rz * 0.02 * (0.5 + speed * 2.0)

            val prob = pRandom.nextDouble()

            val type = if(prob < 0.01) {
                ParticleTypes.ASH
            }
            else if(prob < 0.03) {
                ParticleTypes.FLAME
            }
            else if(prob < 0.1) {
                ParticleTypes.SMOKE
            }
            else {
                ParticleTypes.CRIT
            }

            pLevel.addParticle(
                type,
                x, y, z,
                vx, vy, vz
            )
        }

        repeat(dustBurst) {
            val rx = (pRandom.nextDouble() - 0.5) * 0.6
            val rz = (pRandom.nextDouble() - 0.5) * 0.6
            val x = cx + rx
            val z = cz + rz

            val vx = rx * 0.01 * speed
            val vy = 0.02 + pRandom.nextDouble() * 0.03 * speed
            val vz = rz * 0.01 * speed

            val dust = DustParticleOptions(Vector3f(0.72f, 0.62f, 0.5f), 1.0f)
            pLevel.addParticle(dust, x, topY, z, vx, vy, vz)
        }
    }
}

class CrusherBlockEntity(pos: BlockPos, state: BlockState) : MotorProcessingBlockEntity<DirectSimpleProcessingRecipe>(pos, state, Content.CRUSHER_BLOCK_ENTITY.get(), 2) {
    override fun getRecipe() = Content.CRUSHING_RECIPE

    override fun getSound() = Content.CRUSHER_SOUND_ROCK

    override fun animateClientTick(dt: Double, speed: Double, level: Level, blockPos: BlockPos) {
        val random = level.getRandom()
        val chance = 0.5 * clientTickSpeedSmoother.value

        if (random.nextDouble() < chance) {
            var x = blockPos.x + 0.5
            val y = blockPos.y + 0.8
            var z = blockPos.z + 0.5

            val axis = blockState.getValue(HorizontalDirectionalBlock.FACING).axis

            when(axis) {
                Direction.Axis.X -> x += random.nextDouble(-0.4, 0.4)
                Direction.Axis.Y -> { }
                Direction.Axis.Z -> z += random.nextDouble(-0.4, 0.4)
            }

            val vy = -random.nextDouble(0.01, 0.02)

            val dust = DustParticleOptions(Vector3f(1.0f, 1.0f, 1.0f), 1.0f)

            level.addParticle(
                dust,
                x, y, z,
                0.0, vy, 0.0
            )
        }
    }
}

class CrusherBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: CrusherBlockEntity,
    partialTick: Float,
) : AbstractBlockEntityVisual<CrusherBlockEntity>(ctx, blockEntity, partialTick), SimpleDynamicVisual {
    companion object {
        private val center0 = FlwModels.getModelCenter(FlwModels.CRUSHER_GRINDER_0)
        private val center1 = FlwModels.getModelCenter(FlwModels.CRUSHER_GRINDER_1)
    }

    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.CRUSHER_BODY, Materials.CUTOUT_BLOCK))
        .createInstance()
        .transformFacingBlock(visualPos, blockEntity)

    val grinder0: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.CRUSHER_GRINDER_0))
        .createInstance()

    val grinder1: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.CRUSHER_GRINDER_1))
        .createInstance()

    var rotation = Rotation2d.identity
    val velocitySmoother = FramerateIndependentSmoother1d(0.1) // Separate smoother (flywheel thread)

    private fun poseGrinder(instance: TransformedInstance, center: Vector3d, rotation: Double) {
        val x = center.x
        val y = center.y

        instance.setIdentityTransform()
            .translate(visualPos)
            .center()
            .rotateToFace(blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING))
            .uncenter()
            .translate(x, y, 0.0)
            .rotateZ(rotation.toFloat())
            .translate(-x, -y, 0.0)
            .setChanged()
    }

    init {
        poseGrinder(grinder0, center0, 0.0)
        poseGrinder(grinder1, center1, 0.0)
    }

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val dt = velocitySmoother.update(blockEntity.targetClientSpeed)
        velocitySmoother.pullDown()
        val incr = velocitySmoother.value * dt

        if(incr != 0.0) {
            rotation += incr
            val angle = rotation.ln()
            poseGrinder(grinder0, center0, -angle)
            poseGrinder(grinder1, center1, +angle)
        }
    }

    override fun updateLight(p0: Float) {
        relight(body, grinder0, grinder1)
    }

    override fun collectCrumblingInstances(p0: Consumer<Instance?>) {
        p0.accept(body)
        p0.accept(grinder0)
        p0.accept(grinder1)
    }

    override fun _delete() {
        body.delete()
        grinder0.delete()
        grinder1.delete()
    }
}

class CrusherMenu(
    pContainerId: Int,
    playerInventory: Inventory,
    handler: ItemStackHandler,
    val containerData: ProgressContainerData,
    val access: ContainerLevelAccess,
    val level: Level,
) : AbstractContainerMenu(Content.CRUSHER_MENU.get(), pContainerId) {
    @ServerOnly
    constructor(entity: CrusherBlockEntity, id: Int, inventory: Inventory): this(
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
            SlotItemHandlerWithPlacePredicateAndSkipPickupCheck(handler, INPUT_SLOT, 34, 35) {
                level.canCrush(it)
            }
        )

        addSlot(
            SlotItemHandlerWithPlacePredicate(handler, OUTPUT_SLOT, 130, 35) {
                false
            }
        )

        addDataSlots(containerData)

        ContainerHelper.addPlayerGrid(playerInventory, this::addSlot)
    }

    override fun stillValid(pPlayer: Player) = stillValid(access, pPlayer, Content.CRUSHER_BLOCK.block.get())

    override fun quickMoveStack(pPlayer: Player, pIndex: Int) = ContainerHelper.quickMove(slots, pPlayer, pIndex)
}

class CrusherScreen(menu: CrusherMenu, playerInventory: Inventory, title: Component) : MyAbstractContainerScreen<CrusherMenu>(menu, playerInventory, title) {
    companion object {
        private val BASE_TEXTURE = resource("textures/gui/container/crusher_base.png")
        private val PROGRESS_TEXTURE = resource("textures/gui/container/crusher_progress.png")
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
                53.0f,
                121.0f
            ).toInt(),
            256,
            256,
            256
        )
    }
}

