package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.ShaderLightVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
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
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.Shapes
import org.ageseries.libage.data.requireLocator
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.OnServerThread
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.client.render.foundation.partTransformation
import org.eln2.mc.common.blocks.foundation.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.modules.Eln2Ingredients
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.data.Locators
import org.eln2.mc.data.MonopoleMap
import org.eln2.mc.extensions.addItem
import org.eln2.mc.extensions.minus
import org.eln2.mc.extensions.vector3d
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.mathematics.BlockPosInt
import java.util.function.Consumer
import kotlin.math.PI
import kotlin.random.Random

//#region Rubber Tap

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

        level.addItem(x, y, z, ItemStack(Eln2Ingredients.LATEX_ITEM.get(), 1))

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

//#endregion

/**
 * Multi-block, Multi-cell device.
 * There still is a representative block with its cell, that has the main logic: [VulcanizingAutoclaveMainCell], [VulcanizingAutoclaveMainBlock], [VulcanizingAutoclaveMainBlockEntity].
 * The main cell doesn't have simulation objects and doesn't create connections with the exterior.
 * It creates a connection with the [VulcanizingAutoclaveThermalPortCell].
 *
 * The [VulcanizingAutoclaveThermalPortCell], [VulcanizingAutoclaveThermalPortBlock], [VulcanizingAutoclaveThermalPortBlockEntity] has a cell and a thermal simulation object.
 * It forms a connection to any wire that is hooked up to it, and it connects to the [VulcanizingAutoclaveMainCell].
 * It doesn't have any logic.
 * */
//#region Vulcanizing Autoclave

class VulcanizingAutoclaveThermalPortCell(
    ci: CellCreateInfo,
    override val thermalMap: MonopoleMap,
    override val thermalSize: ThermalSize
) : Cell(ci), SidedThermalMonoMapped<VulcanizingAutoclaveThermalPortCell> {
    @SimObject
    val thermalWire = ThermalWireObject(this)
}

class VulcanizingAutoclaveThermalPortBlock : MultiblockDelegateUprightHorizontalDirectionCellBlock<VulcanizingAutoclaveThermalPortCell>() {
    @Deprecated("Deprecated in Java")
    override fun skipRendering(pState: BlockState, pAdjacentState: BlockState, pDirection: Direction) = true

    override fun getCellProvider() = Eln2Processing.VULCANIZING_AUTOCLAVE_THERMAL_PORT_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = VulcanizingAutoclaveThermalPortBlockEntity(pPos, pState)

    override fun spatialNeighborScan(level: Level, results: HashSet<CellAndContainerHandle>, cell: Cell) {
        val blockEntity = level.getBlockEntity(cell.locator.requireLocator(Locators.BLOCK)) as VulcanizingAutoclaveThermalPortBlockEntity

        val representative = getRepresentativeFromDelegateBlockEntity<VulcanizingAutoclaveMainBlockEntity?>(blockEntity, blockEntity.representativePos)
            ?: return

        /**
         * Scans the port itself:
         * */
        planarCellScan(
            level,
            cell,
            cell.locator.requireLocator(Locators.CONVENTIONAL_FACING).direction.opposite,
            results::add
        )

        /**
         * Links up the main:
         * */
        results.add(CellAndContainerHandle.captureInScope(representative.cell))
    }
}

class VulcanizingAutoclaveThermalPortBlockEntity(pPos: BlockPos, pBlockState: BlockState) :
    MultiblockDelegateCellBlockEntity<VulcanizingAutoclaveThermalPortCell>(pPos, pBlockState, Eln2Processing.VULCANIZING_AUTOCLAVE_THERMAL_PORT_BLOCK_ENTITY.get()),
    ValidateFormationBlockEntity<VulcanizingAutoclaveThermalPortBlockEntity>
{
    override fun validateMultiblockFormation() {
        check(cell.connections.isNotEmpty() && cell.connections.any { it is VulcanizingAutoclaveMainCell }) {
            DEBUGGER_BREAK("Vulcanizing autoclave didn't form correctly: the thermal port cell doesn't have the required connection (${cell.connections.size})")
        }
    }
}

class VulcanizingAutoclaveMainCell(ci: CellCreateInfo) : Cell(ci) {
    override fun subscribe(subscribers: SubscriberCollection) {
        super.subscribe(subscribers)
    }
}

class VulcanizingAutoclaveMainBlock : UprightHorizontalDirectionCellBlock<VulcanizingAutoclaveMainCell>() {
    @Deprecated("Deprecated in Java")
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean = true

    override fun getCellProvider() = Eln2Processing.VULCANIZING_AUTOCLAVE_MAIN_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = VulcanizingAutoclaveMainBlockEntity(pPos, pState)

    override fun spatialNeighborScan(level: Level, results: HashSet<CellAndContainerHandle>, cell: Cell) {
        val pos = cell.locator.requireLocator(Locators.BLOCK)
        val facing = cell.locator.requireLocator(Locators.CONVENTIONAL_FACING)
        val blockEntity = level.getBlockEntity(pos) as VulcanizingAutoclaveMainBlockEntity

        /**
         * Only links up the delegate:
         * */
        blockEntity.delegateMap.forEachDelegateInWorld(level, facing.direction, pos) {
            val delegate = level.getBlockEntity(it) as? VulcanizingAutoclaveThermalPortBlockEntity
                ?: return@forEachDelegateInWorld

            results.add(CellAndContainerHandle.captureInScope(delegate.cell))
        }
    }
}

class VulcanizingAutoclaveMainBlockEntity(pos: BlockPos, state: BlockState) :
    CellBlockEntity<VulcanizingAutoclaveMainCell>(pos, state, Eln2Processing.VULCANIZING_AUTOCLAVE_MAIN_BLOCK_ENTITY.get()),
    BigBlockRepresentativeBlockEntity<VulcanizingAutoclaveMainBlockEntity>,
    ValidateFormationBlockEntity<VulcanizingAutoclaveMainBlockEntity>
{
    override val delegateMap: MultiblockDelegateMap
        get() = Eln2Processing.VULCANIZING_AUTOCLAVE_DELEGATE_MAP.value

    override fun validateMultiblockFormation() {
        check(cell.connections.size == 1 && cell.connections[0] is VulcanizingAutoclaveThermalPortCell) {
            DEBUGGER_BREAK("Vulcanizing autoclave didn't form correctly: the main cell doesn't have the correct connections (${cell.connections.size})")
        }
    }

    override fun setDestroyed() {
        destroyDelegates()

        super.setDestroyed()
    }
}

class VulcanizingAutoclaveMainBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: VulcanizingAutoclaveMainBlockEntity,
    partialTick: Float
) : AbstractBlockEntityVisual<VulcanizingAutoclaveMainBlockEntity>(ctx, blockEntity, partialTick), ShaderLightVisual {
    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.VULCANIZING_AUTOCLAVE_BODY, FlwMaterials.TRANSLUCENT_SMOOTH_LIT))
        .createInstance()
        .also {
            it.translate(visualPosition)
            it.center()
            it.rotateToFace(blockEntity.representativeFacing.clockWise)
            it.uncenter()
        }


    override fun updateLight(p0: Float) {
        // NOOP
    }

    override fun collectCrumblingInstances(p0: Consumer<Instance?>) {
        p0.accept(body)
    }

    override fun _delete() {
        body.delete()
    }
}

//#endregion
