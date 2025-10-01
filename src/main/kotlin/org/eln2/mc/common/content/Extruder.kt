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
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockState
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.FramerateIndependentSmoother1d
import org.eln2.mc.MODID
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.common.recipes.MotorProcessingBlock
import org.eln2.mc.common.recipes.MotorProcessingBlockEntity
import org.eln2.mc.common.recipes.foundation.CATALYST_SLOT
import org.eln2.mc.common.recipes.foundation.CatalyzedSimpleProcessingRecipe
import org.eln2.mc.extensions.transformFacingBlock
import java.util.function.Consumer

class ExtruderBlock : MotorProcessingBlock<CatalyzedSimpleProcessingRecipe, ExtruderBlockEntity>() {
    override fun getTitle(): MutableComponent = Component.translatable("menu.$MODID.extruder")

    override fun createMenu(
        pBlockEntity: ExtruderBlockEntity,
        pContainerId: Int,
        pPlayerInventory: Inventory,
    ): AbstractContainerMenu {
        TODO("Not yet implemented")
    }

    override fun getCellProvider() = Content.BASIC_EXTRUDER_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = ExtruderBlockEntity(pPos, pState)
}

class ExtruderBlockEntity(pos: BlockPos, state: BlockState) : MotorProcessingBlockEntity<CatalyzedSimpleProcessingRecipe>(pos, state, Content.EXTRUDER_BLOCK_ENTITY.get(), 3) {
    override fun getRecipe() = Content.EXTRUDING_RECIPE

    val hasDie get() = !inventoryHandler.getStackInSlot(CATALYST_SLOT).isEmpty
}

class ExtruderBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: ExtruderBlockEntity,
    partialTick: Float
) : AbstractBlockEntityVisual<ExtruderBlockEntity>(ctx, blockEntity, partialTick), SimpleDynamicVisual {
    companion object {
        val centerA0 = FlwModels.getModelCenter(FlwModels.EXTRUDER_SHAFT_A0)
        val centerA1 = FlwModels.getModelCenter(FlwModels.EXTRUDER_SHAFT_A1)
        val centerB0 = FlwModels.getModelCenter(FlwModels.EXTRUDER_SHAFT_B0)
        val centerB1 = FlwModels.getModelCenter(FlwModels.EXTRUDER_SHAFT_B1)
    }

    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.EXTRUDER_BODY, Materials.CUTOUT_BLOCK))
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

    var rotation = Rotation2d.identity
    val velocitySmoother = FramerateIndependentSmoother1d(0.1) // Separate smoother (flywheel thread)

    private fun poseShaft(instance: TransformedInstance, center: Vector3d, rotation: Double) {
        val z = center.z
        val y = center.y

        instance.setIdentityTransform()
            .translate(visualPos)
            .center()
            .rotateToFace(blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING))
            .uncenter()
            .translate(0.0, y, z)
            .rotateZ(rotation.toFloat())
            .translate(0.0, -y, -z)
            .setChanged()
    }

    init {
        poseShaft(shaftA0, centerA0, 0.0)
        poseShaft(shaftA1, centerA1, 0.0)
        poseShaft(shaftB0, centerB0, 0.0)
        poseShaft(shaftB1, centerB1, 0.0)
    }

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val dt = velocitySmoother.update(blockEntity.targetClientSpeed)
        velocitySmoother.pullDown()
        val incr = velocitySmoother.value * dt

        if(incr != 0.0) {
            rotation += incr
            val angle = rotation.ln()
            poseShaft(shaftA0, centerA0, -angle)
            poseShaft(shaftA1, centerA1, -angle)
            poseShaft(shaftB0, centerB0, +angle)
            poseShaft(shaftB1, centerB1, +angle)
        }

        if(die != null && !blockEntity.hasDie) {
            die!!.delete()
            die = null
        }
        else if(die == null && blockEntity.hasDie) {
            die = visualizationContext.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.EXTRUDER_DIE))
                .createInstance()
                .transformFacingBlock(visualPos, blockEntity)
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
