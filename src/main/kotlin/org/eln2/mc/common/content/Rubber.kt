package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.Shapes
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.OnServerThread
import org.eln2.mc.client.render.DebugVisualizer
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.partTransformation
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.blocks.foundation.MultipartBlock
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.extensions.addItem
import org.eln2.mc.extensions.minus
import org.eln2.mc.extensions.vector3d
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.mathematics.BlockPosInt
import kotlin.math.PI
import kotlin.random.Random

class RubberTapPartProvider : PartProvider() {
    override fun createCore(context: PartPlacementInfo) = RubberTapPart(
        PartCreateInfo(id, context)
    )

    override val placementCollisionSize: Vector3d
        get() = Vector3d.one

    override fun canPlace(level: Level, substratePos: BlockPos, face: Direction): Boolean {
        return Base6Direction3dMask.HORIZONTALS.has(face)
    }
}

class RubberTapPart(ci: PartCreateInfo) : Part(ci), TickablePart, ComponentDisplay {
    companion object {
        private const val MIN_INTERVAL = 20 * 3
        private const val MAX_INTERVAL = 20 * 5

        /**
         * The number of logs needed to consider the tree valid.
         * */
        private const val REQUIRED_LOGS = 5

        /**
         * The number of leaves needed to consider the tree valid.
         * */
        private const val REQUIRED_LEAVES = 5

        /**
         * The maximum distance between the collector and any searched blocks.
         * */
        private const val MAX_SPATIAL_DISTANCE = 5

        /**
         * The maximum nodes between the collector and any searched blocks.
         * */
        private const val MAX_GRAPH_DISTANCE = 15

        private const val MIN_BASE_PROGRESS = 0.1
        private const val MAX_BASE_PROGRESS = 0.25

        @OnServerThread
        private val visited = IntOpenHashSet()

        @OnServerThread
        private val queue = LongArrayFIFOQueue()
    }

    private var scanCountdown = 0

    /**
     * On both the client ([handleSyncTag]) and the server.
     * */
    var progress = 0.0
        private set

    init {
        updateShape(
            Shapes.create(
                getModelBoundingBox(Vector3d.zero, Vector3d(0.5, 0.315, 0.32))
                    .move(0.0, -0.245, 0.0)
            )
        )
    }

    override fun createVisual(ctx: MultipartVisualizationContext) = RubberTapPartVisual(ctx, this)

    override fun onAdded() {
        if(!placement.level.isClientSide) {
            placement.multipart.addTicker(this)
        }
    }

    override fun serverTick() {
        if(progress == 1.0) {
            placement.multipart.markRemoveTicker(this)
            return // Waits for the item to be removed.
        }

        if(--scanCountdown > 0) {
            return
        }

        scanCountdown = Random.nextInt(MIN_INTERVAL, MAX_INTERVAL)

        val substratePos = placement.position - placement.face

        if(placement.level.getBlockState(substratePos).block != Blocks.OAK_LOG) {
            return
        }

        visited.clear()
        queue.clear()
        queue.enqueue(0L)

        val mutableBlockPos = BlockPos.MutableBlockPos()

        var foundLogs = 0
        var foundLeaves = 0
        var validated = false

        while (!queue.isEmpty) {
            val front = queue.dequeueLong()
            val frontKey = front.toInt()

            val dx = BlockPosInt.unpackX(frontKey)
            val dy = BlockPosInt.unpackY(frontKey)
            val dz = BlockPosInt.unpackZ(frontKey)

            if(dx * dx + dy * dy + dz * dz > MAX_SPATIAL_DISTANCE * MAX_SPATIAL_DISTANCE) {
                continue
            }

            if(!visited.add(frontKey)) {
                continue
            }

            mutableBlockPos.x = dx + substratePos.x
            mutableBlockPos.y = dy + substratePos.y
            mutableBlockPos.z = dz + substratePos.z

            val blockState = placement.level.getBlockState(mutableBlockPos)

            when(blockState.block) {
                Blocks.OAK_LOG -> {
                    foundLogs++
                }

                Blocks.OAK_LEAVES -> {
                    foundLeaves++
                }

                // Can also check for other taps in the vicinity, to reduce the collection rate.

                else -> {
                    continue
                }
            }

            if(foundLogs >= REQUIRED_LOGS && foundLeaves >= REQUIRED_LEAVES) {
                validated = true
                break
            }

            val graphDistance = 1 + (front shr 32).toInt()

            if(graphDistance > MAX_GRAPH_DISTANCE) {
                continue
            }

            Direction.entries.forEach { direction ->
                val childKey = BlockPosInt.pack(
                    dx + direction.stepX,
                    dy + direction.stepY,
                    dz + direction.stepZ
                )

                queue.enqueue((graphDistance.toLong() shl 32) or (childKey.toLong() and 0xFFFF_FFFFL))
            }
        }

        if(!validated) {
            return
        }

        progress += Random.nextDouble(MIN_BASE_PROGRESS, MAX_BASE_PROGRESS)

        if(progress >= 1.0) {
            progress = 1.0
        }

        setSaveDirty()
        setSyncDirty()
    }

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if(progress != 1.0) {
            return InteractionResult.FAIL
        }

        if(placement.level.isClientSide) {
            return InteractionResult.PASS
        }

        val level = placement.level as ServerLevel

        val (x, y, z) = placement.mountingPointWorld + placement.face.vector3d * 0.4

        level.addItem(x, y, z, ItemStack(Content.LATEX_ITEM.get(), 1))

        progress = 0.0

        setSaveDirty()
        setSyncDirty()

        placement.multipart.addTicker(this)

        return InteractionResult.SUCCESS
    }

    override fun getServerSaveTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putDouble("progress", progress)
        return tag
    }

    override fun loadServerSaveTag(tag: CompoundTag) {
        progress = tag.getDouble("progress")
    }

    override fun getSyncTag() = getServerSaveTag()

    override fun handleSyncTag(tag: CompoundTag) = loadServerSaveTag(tag)

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Countdown: $scanCountdown" }
        builder.progress(progress)
    }
}

class RubberTapPartVisual(visualizationContext: MultipartVisualizationContext, part: RubberTapPart) : AbstractPartVisual<RubberTapPart>(visualizationContext, part), SimpleDynamicVisual {
    private val body = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.RUBBER_TAP))
        .createInstance()
        .also {
            it.translateY(-0.4f)
            it.partTransformation(visualizationContext.parent, part)
            it.translateZ(0.5f)
            it.rotateX((-PI / 2.0).toFloat())
        }

    private val latex = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.RUBBER_TAP_LATEX))
        .createInstance()

    private var progress = -1.0

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val targetProgress = part.progress

        if(targetProgress != progress) {
            progress = targetProgress

            latex.setIdentityTransform()

            latex.translateY(-0.4f)
            latex.translateY(progress.toFloat() * 0.1875f)

            latex.partTransformation(visualizationContext.parent, part)

            latex.translateZ(0.5f)
            latex.rotateX((-PI / 2.0).toFloat())

            latex.setChanged()
        }
    }

    override fun updateLight(p0: Float) {
        visualizationContext.parent.relightInstances(body, latex)
    }

    override fun _delete() {
        body.delete()
        latex.delete()
    }
}
