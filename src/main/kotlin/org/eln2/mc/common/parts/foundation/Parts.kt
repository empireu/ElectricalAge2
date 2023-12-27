package org.eln2.mc.common.parts.foundation

import com.jozufozu.flywheel.core.PartialModel
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.*
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.BasicPartRenderer
import org.eln2.mc.client.render.foundation.BasicSpecRenderer
import org.eln2.mc.client.render.foundation.MultipartBlockEntityInstance
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.network.serverToClient.BulkMessages
import org.eln2.mc.common.network.serverToClient.PacketHandler
import org.eln2.mc.common.network.serverToClient.PacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.PartMessage
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.specs.foundation.Spec
import org.eln2.mc.common.specs.foundation.SpecCreateInfo
import org.eln2.mc.data.*
import org.eln2.mc.extensions.*
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.BlockPosInt
import org.eln2.mc.mathematics.FacingDirection
import org.joml.Vector3f
import java.util.*

object PartGeometry {
    fun transform(aabb: AABB, face: Direction): AABB = aabb
        .transformed(face.rotationFast)
        .move(faceOffset(aabb.size3d(), face))

    fun transform(aabb: AABB, facing: FacingDirection, face: Direction): AABB = aabb
        .transformed(facing.rotation)
        .transformed(face.rotationFast)
        .move(faceOffset(aabb.size3d(), face))

    fun modelBoundingBox(translation: Vector3d, size: Vector3d, facing: FacingDirection, face: Direction): AABB {
        val extent = size / 2.0

        return transform(
            AABB(
                (translation - extent).toVec3(),
                (translation + extent).toVec3()
            ),
            facing,
            face
        )
    }

    fun modelBoundingBox(size: Vector3d, facing: FacingDirection, faceWorld: Direction) =
        modelBoundingBox(Vector3d.zero, size, facing, faceWorld)

    fun faceOffset(size: Vector3d, face: Direction): Vec3 {
        val halfSize = size / 2.0

        val positiveOffset = halfSize.y
        val negativeOffset = 1 - halfSize.y

        return when (val axis = face.axis) {
            Direction.Axis.X -> Vec3(
                (if (face.axisDirection == Direction.AxisDirection.POSITIVE) positiveOffset else negativeOffset),
                0.5,
                0.5
            )

            Direction.Axis.Y -> Vec3(
                0.5,
                (if (face.axisDirection == Direction.AxisDirection.POSITIVE) positiveOffset else negativeOffset),
                0.5
            )

            Direction.Axis.Z -> Vec3(
                0.5,
                0.5,
                (if (face.axisDirection == Direction.AxisDirection.POSITIVE) positiveOffset else negativeOffset)
            )

            else -> error("Invalid axis $axis")
        }
    }

    fun worldBoundingBox(translation: Vector3d, size: Vector3d, facing: FacingDirection, face: Direction, multipartPos: BlockPos): AABB =
        modelBoundingBox(translation, size, facing, face).move(multipartPos)

    fun worldBoundingBox(size: Vector3d, facing: FacingDirection, face: Direction, multipartPos: BlockPos): AABB =
        modelBoundingBox(size, facing, face).move(multipartPos)
}

/**
 * Encapsulates all the data associated with a part's placement.
 * */
data class PartPlacementInfo(
    val position: BlockPos,
    val face: Direction,
    val facing: FacingDirection,
    val level: Level,
    val multipart: MultipartBlockEntity,
    val provider: PartProvider
) {
    val positiveX = partX(facing, face)
    val positiveY = partY(facing, face)
    val positiveZ = partZ(facing, face)

    val mountingPointWorld = position.toVector3d() + Vector3d(0.5) - face.vector3d * 0.5

    fun createLocator() = LocatorSetBuilder().apply {
        withLocator(position)
        withLocator(FacingLocator(facing))
        withLocator(face)
    }.build()
}

enum class PartUpdateType(val id: Int) {
    Add(1),
    Remove(2);

    companion object {
        fun fromId(id: Int): PartUpdateType {
            return when (id) {
                Add.id -> Add
                Remove.id -> Remove
                else -> error("Invalid part update type id $id")
            }
        }
    }
}

data class PartUpdate(val part: Part<*>, val type: PartUpdateType)

data class PartUseInfo(val player: Player, val hand: InteractionHand)

data class PartCreateInfo(val id: ResourceLocation, val placement: PartPlacementInfo)

/**
 * Parts are entity-like units that exist in a multipart entity. They are similar to normal block entities,
 * but up to 6 can exist in the same block space.
 * They are placed on the inner faces of a multipart container block space.
 * */
abstract class Part<Renderer : PartRenderer>(ci: PartCreateInfo) {
    val id = ci.id
    val placement = ci.placement
    val partProviderShape = Shapes.create(modelBoundingBox)
    var isRemoved = false

    companion object {
        fun createPartDropStack(id: ResourceLocation, saveTag: CompoundTag?, count: Int = 1): ItemStack {
            val item = PartRegistry.getPartItem(id)
            val stack = ItemStack(item, count)

            stack.tag = saveTag

            return stack
        }
    }

    /**
     * [PacketHandler] for server -> client packets.
     * It will receive messages if and only if the base [handleBulkMessage] gets called when a bulk message is received.
     * */
    @ClientOnly
    private val packetHandlerLazy = lazy {
        val builder = PacketHandlerBuilder()
        registerPackets(builder)
        builder.build()
    }

    @ClientOnly
    protected open fun registerPackets(builder: PacketHandlerBuilder) {}

    /**
     * Enqueues a bulk packet to be sent to the client.
     * This makes sense to call if and only if [P] is registered on the client
     * in [registerPackets], and the default behavior of [handleBulkMessage] gets executed.
     * */
    @ServerOnly
    protected inline fun <reified P> sendBulkPacket(packet: P) {
        enqueueBulkMessage(
            PacketHandler.encode(packet)
        )
    }

    @ClientOnly
    open fun handleBulkMessage(msg: ByteArray) {
        packetHandlerLazy.value.handle(msg)
    }

    fun enqueueBulkMessage(payload: ByteArray) {
        require(!placement.level.isClientSide) { "Tried to send bulk message from client" }
        BulkMessages.enqueuePartMessage(
            placement.level as ServerLevel,
            PartMessage(placement.position, placement.face, payload)
        )
    }

    /**
     * This gets the relative direction towards the global direction, taking into account the facing of this part.
     * @param dirWorld A global direction.
     * @return The relative direction towards the global direction.
     * */
    fun getDirectionActual(dirWorld: Direction): Base6Direction3d {
        return Base6Direction3d.fromForwardUp(
            placement.facing,
            placement.face,
            dirWorld
        )
    }

    fun getModelBoundingBox(translation: Vector3d, size: Vector3d) =
        PartGeometry.modelBoundingBox(
            translation,
            size,
            placement.facing,
            placement.face
        )

    fun getWorldBoundingBox(translation: Vector3d, size: Vector3d) =
        PartGeometry.worldBoundingBox(
            translation,
            size,
            placement.facing,
            placement.face,
            placement.position
        )

    /**
     * Gets the model bounding box of the part, based on the provider's collision size,
     * */
    val modelBoundingBox: AABB
        get() = getModelBoundingBox(Vector3d.zero, placement.provider.placementCollisionSize)

    /**
     * This is the bounding box of the part, in its block position.
     * */
    val worldBoundingBox: AABB
        get() = getWorldBoundingBox(Vector3d.zero, placement.provider.placementCollisionSize)

    fun translateShapeWorld(shape: VoxelShape) = shape.move(
        placement.position.x.toDouble(),
        placement.position.y.toDouble(),
        placement.position.z.toDouble()
    )

    /**
     * Gets the shape of this part. Used for block highlighting and collisions.
     * By default, it is set to the [partProviderShape] of the part.
     * */
    var modelShape: VoxelShape = partProviderShape
        private set

    var worldShape: VoxelShape = translateShapeWorld(modelShape)
        private set

    var worldShapeParts = worldShape.toBoxList()
        private set

    /**
     * Updates the shape of the part and updates the multipart collider.
     * Synchronization is not done automatically. If you wish the changes to be reflected on the client, you must synchronize it yourself.
     * */
    fun updateShape(shape: VoxelShape) : Boolean {
        if(shape == this.modelShape) {
            return false
        }

        this.modelShape = shape
        this.worldShape = translateShapeWorld(shape)
        this.worldShapeParts = this.worldShape.toBoxList()

        placement.multipart.rebuildCollider()

        return true
    }

    /**
     * Called when the part is right-clicked by a living entity.
     * */
    open fun onUsedBy(context: PartUseInfo): InteractionResult {
        return InteractionResult.FAIL
    }

    /**
     * Called when the player tries to destroy the part, on the server.
     * @return True, if the part shall break. Otherwise, false.
     * */
    @ServerOnly
    open fun tryBreakByPlayer(player: Player) : Boolean {
        return true
    }

    /**
     * Saves data that should be persisted.
     * */
    @ServerOnly
    open fun getServerSaveTag(): CompoundTag? = null

    /**
     * Saves data that should be sent to the client when the part is placed or when the part is first sent to the client.
     * */
    @ClientOnly
    open fun getClientSaveTag(): CompoundTag? = getSyncTag()

    /**
     * Loads the data saved by [getServerSaveTag].
     * */
    @ServerOnly
    open fun loadServerSaveTag(tag: CompoundTag) { }

    /**
     * Loads the data sent by [getClientSaveTag].
     * */
    @ClientOnly
    open fun loadClientSaveTag(tag: CompoundTag) = handleSyncTag(tag)

    /**
     * This method is called when this part is invalidated, and in need of synchronization to clients.
     * You will receive this tag in *handleSyncTag* on the client, _if_ the tag is not null.
     * @return A compound tag with all part updates. You may return null, but that might indicate an error in logic.
     * This method is called only when an update is _requested_, so there should be data in need of synchronization.
     *
     * */
    @ServerOnly
    open fun getSyncTag(): CompoundTag? {
        return null
    }

    /**
     * This method is called on the client after the server logic of this part requested an update, and the update was received.
     * @param tag The custom data tag, as returned by the getSyncTag method on the server.
     * */
    @ClientOnly
    open fun handleSyncTag(tag: CompoundTag) { }

    /**
     * This method invalidates the saved data of the part.
     * This ensures that the part will be saved to the disk.
     * */
    @ServerOnly
    fun setSaveDirty() {
        if (placement.level.isClientSide) {
            error("Cannot save on the client")
        }

        placement.multipart.setChanged()
    }

    /**
     * This method synchronizes all changes from the server to the client.
     * It results in calls to the *getSyncTag* **(server)** / *handleSyncTag* **(client)** combo.
     * */
    @ServerOnly
    fun setSyncDirty() {
        if (placement.level.isClientSide) {
            error("Cannot sync changes from client to server!")
        }

        placement.multipart.enqueuePartSync(placement.face)
    }

    /**
     * This method invalidates the saved data and synchronizes to clients.
     * @see setSaveDirty
     * @see setSyncDirty
     * */
    @ServerOnly
    fun setSyncAndSaveDirty() {
        setSyncDirty()
        setSaveDirty()
    }

    /**
     *  Called on the server when the part is placed.
     * */
    @ServerOnly
    open fun onPlaced() {}

    /**
     * Called on the server when the part finished loading from disk
     * */
    @ServerOnly
    open fun onLoaded() {}

    /**
     * Called when this part is added to a multipart.
     * */
    open fun onAdded() {}

    /**
     * Called when this part is received and added to the client multipart, just before rendering set-up is enqueued.
     * */
    @ClientOnly
    open fun onAddedToClient() {}

    /**
     * Called when this part is being unloaded.
     * */
    open fun onUnloaded() {}

    /**
     * Called when the part is destroyed (broken).
     * */
    open fun onBroken() {}

    fun setRemoved() {
        if(this.isRemoved) {
            LOG.error("Multiple calls to setRemoved")
        }

        this.isRemoved = true
        onRemoved()
    }

    /**
     * Called when the part is removed from the multipart.
     * */
    protected open fun onRemoved() {}

    /**
     * Called when synchronization is suggested. This happens when a client enters the viewing area of the part.
     * */
    @ServerOnly
    open fun onSyncSuggested() {
        this.setSyncDirty()
    }

    @ClientOnly
    protected var previousRenderer: Renderer? = null

    @ClientOnly
    protected var activeRenderer: Renderer? = null
        private set

    /**
     * Gets the [Renderer] instance for this part.
     * By default, it calls the [createRenderer] method, and stores the result.
     * */
    @ClientOnly
    open val renderer: Renderer
        get() {
            if (!placement.level.isClientSide) {
                error("Tried to get part renderer on non-client side!")
            }

            if (activeRenderer == null) {
                activeRenderer = createRenderer().also {
                    val previousRenderer = this.previousRenderer
                    this.previousRenderer = null

                    if(previousRenderer != null) {
                        if(it is PartRendererStateStorage) {
                            it.restoreSnapshot(previousRenderer)
                        }
                    }
                }

                initializeRenderer()
            }

            return activeRenderer!!
        }

    /**
     * Creates a renderer instance for this part.
     * @return A new instance of the part renderer.
     * */
    @ClientOnly
    abstract fun createRenderer(): Renderer

    /**
     * Called to initialize the [Renderer], right after it is created by [createRenderer]
     * */
    @ClientOnly
    open fun initializeRenderer() { }

    @ClientOnly
    open fun destroyRenderer() {
        previousRenderer = activeRenderer
        activeRenderer?.remove()
        activeRenderer = null
    }
}

/**
 * This is a factory for parts. It also has the size used to validate placement (part-part collisions).
 * */
abstract class PartProvider {
    val id: ResourceLocation get() = PartRegistry.getId(this)

    /**
     * Used to create a new instance of the part. Called when the part is placed
     * or when the multipart entity is loading from disk.
     * @param context The placement context of this part.
     * @return Unique instance of the part.
     */
    abstract fun create(context: PartPlacementInfo): Part<*>

    /**
     * This is the size used to validate placement. This is different from baseSize, because
     * you can implement a visual placement margin here.
     * */
    abstract val placementCollisionSize: Vector3d

    open fun canPlace(context: PartPlacementInfo): Boolean = true
}

/**
 * The basic part provider uses a functional interface as part factory.
 * Often, the part's constructor can be passed in as factory.
 * */
open class BasicPartProvider(
    final override val placementCollisionSize: Vector3d,
    val factory: ((ci: PartCreateInfo) -> Part<*>),
) : PartProvider() {
    override fun create(context: PartPlacementInfo) = factory(PartCreateInfo(id, context))
}


class MySpec(ci: SpecCreateInfo) : Spec<BasicSpecRenderer>(ci) {
    override fun createRenderer(): BasicSpecRenderer {
        return BasicSpecRenderer(this, PartialModels.ELECTRICAL_WIRE_HUB)
    }
}

/**
 * Represents a part that has a cell.
 * */
interface PartCellContainer<C : Cell> {
    /**
     * This is the cell owned by the part.
     * */
    val cell: Cell

    /**
     * Indicates if the cell is available (loaded).
     * */
    val hasCell: Boolean

    /**
     * @return The provider associated with the cell.
     * */
    val provider: CellProvider<C>

    /**
     * Indicates whether this part allows planar connections.
     * @see CellPartConnectionMode.Planar
     * */
    val allowPlanarConnections: Boolean

    /**
     * Indicates whether if this part allows inner connections.
     * @see CellPartConnectionMode.Inner
     * */
    val allowInnerConnections: Boolean

    /**
     * Indicates if this part allows wrapped connections.
     * @see CellPartConnectionMode.Wrapped
     * */
    val allowWrappedConnections: Boolean

    /**
     * Called when the cell part is connected to another cell.
     * */
    fun onConnected(remoteCell: Cell)

    /**
     * Called when the cell part is disconnected from another cell. This may happen when the part is being destroyed.
     * */
    fun onDisconnected(remoteCell: Cell)

    /**
     * Called when the cell part is connected/disconnected. This is not called if the part is being destroyed.
     * */
    fun onConnectivityChanged() { }

    fun addExtraConnections(results: MutableSet<CellNeighborInfo>) { }
}

/**
 * This part represents a simulation object. It can become part of a cell network.
 * */
abstract class CellPart<C: Cell, R : PartRenderer>(
    ci: PartCreateInfo,
    final override val provider: CellProvider<C>,
) : Part<R>(ci), PartCellContainer<C> {
    companion object {
        private const val GRAPH_ID = "GraphID"
        private const val CUSTOM_SIMULATION_DATA = "SimulationData"
    }

    private var cellField: C? = null

    /**
     * The actual cell contained within this part.
     * It only exists on the server (it is a simulation-only item)
     * */
    @ServerOnly
    final override val cell: C get() = cellField
        ?: error(
            if(placement.level.isClientSide) {
                "TRIED TO ACCESS CELL ON CLIENT"
            } else {
                "Tried to get cell before it is set $this"
            }
        )

    final override val hasCell: Boolean
        get() = cellField != null

    val locator = placement.createLocator()

    /**
     * Used by the loading procedures.
     * */
    @ServerOnly
    private lateinit var loadGraphId: UUID

    @ServerOnly
    private var customSimulationData: CompoundTag? = null

    protected var isAlive = false // FIXME remove, replace with Part#isRemoved
        private set

    /**
     * Notifies the cell of the new container.
     * */
    override fun onPlaced() {
        cellField = provider.create(locator, CellEnvironment.evaluate(placement.level, locator))
        cell.container = placement.multipart
        isAlive = true
        onCellAcquired()
    }

    /**
     * Notifies the cell that the container has been removed.
     * */
    override fun onUnloaded() {
        requireIsOnServerThread {
            "onUnloaded"
        }

        if (hasCell) {
            cell.onContainerUnloading()
            cell.container = null
            cell.onContainerUnloaded()
            cell.unbindGameObjects()
            isAlive = false
            onCellReleased()
        }
    }

    override fun onRemoved() {
        super.onRemoved()

        isAlive = false
    }

    /**
     * The saved data includes the Graph ID. This is used to fetch the cell after loading.
     * */
    override fun getServerSaveTag(): CompoundTag? {
        if (!hasCell) {
            LOG.error("Saving, but cell not initialized!")
            return null
        }

        val tag = CompoundTag()

        tag.putUUID(GRAPH_ID, cell.graph.id)

        saveCustomSimData()?.also {
            tag.put(CUSTOM_SIMULATION_DATA, it)
        }

        return tag
    }

    /**
     * This method gets the graph ID from the saved data.
     * The level is not available at this point, so we defer cell fetching to the onLoaded method.
     * */
    override fun loadServerSaveTag(tag: CompoundTag) {
        if (placement.level.isClientSide) {
            return
        }

        if (tag.contains(GRAPH_ID)) {
            loadGraphId = tag.getUUID("GraphID")
        } else {
            LOG.info("Part at $locator did not have saved data")
        }

        tag.useSubTagIfPreset(CUSTOM_SIMULATION_DATA) { customSimulationData = it }
    }

    /**
     * This is the final stage of loading. We have the level, so we can fetch the cell using the saved data.
     * */
    @Suppress("UNCHECKED_CAST")
    override fun onLoaded() {
        if (placement.level.isClientSide) {
            return
        }

        cellField = if (!this::loadGraphId.isInitialized) {
            LOG.error("Part cell not initialized!")
            // Should we blow up the game?
            provider.create(locator, CellEnvironment.evaluate(placement.level, locator))
        } else {
            CellGraphManager.getFor(placement.level as ServerLevel)
                .getGraph(loadGraphId)
                .getCellByLocator(locator) as C
        }

        cell.container = placement.multipart
        cell.onContainerLoaded()

        if (this.customSimulationData != null) {
            loadCustomSimDataPre(customSimulationData!!)
        }

        isAlive = true
        onCellAcquired()

        if (this.customSimulationData != null) {
            loadCustomSimDataPost(customSimulationData!!)
            this.customSimulationData = null
        }

        cell.bindGameObjects(listOf(this, placement.multipart))
    }

    /**
     * Saves custom data to the simulation storage (separate from the block entity and chunks)
     * */
    open fun saveCustomSimData(): CompoundTag? {
        return null
    }

    /**
     * Loads custom data from the simulation storage, just before the cell is acquired.
     * */
    open fun loadCustomSimDataPre(tag: CompoundTag) {}

    /**
     * Loads custom data from the simulation storage, after the cell is acquired.
     * */
    open fun loadCustomSimDataPost(tag: CompoundTag) {}

    override fun onConnected(remoteCell: Cell) {}

    override fun onDisconnected(remoteCell: Cell) {}

    open fun onCellAcquired() {}
    open fun onCellReleased() {}

    override val allowPlanarConnections = true
    override val allowInnerConnections = true
    override val allowWrappedConnections = true
}

open class BasicCellPart<C: Cell, R : PartRenderer>(
    ci: PartCreateInfo,
    provider: CellProvider<C>,
    private val rendererFactory: PartRendererFactory<R>,
) : CellPart<C, R>(ci, provider) {
    override fun createRenderer(): R {
        return rendererFactory.create(this)
    }
}

/**
 * A connection mode represents the way two cells may be connected.
 * */
enum class CellPartConnectionMode(val index: Int) {
    /**
     * The connection mode could not be identified.
     * */
    Unknown(0),

    /**
     * Planar connections are connections between units placed on the same plane, in adjacent containers.
     * */
    Planar(1),

    /**
     * Inner connections are connections between units placed on perpendicular faces in the same container.
     * */
    Inner(2),

    /**
     * Wrapped connections are connections between units placed on perpendicular faces of the same block.
     * Akin to a connection wrapping around the corner of the substrate block.
     * */
    Wrapped(3);

    companion object {
        val byId = entries.toList()
    }
}

private val DIRECTIONS = Direction.entries.toTypedArray()

private val INCREMENT_FROM_FORWARD_UP = Int2IntOpenHashMap().also { map ->
    for (facingWorld in FacingDirection.entries) {
        DIRECTIONS.forEach { faceWorld ->
            DIRECTIONS.forEach { direction ->
                val direction3d = Vector3f(
                    direction.stepX.toFloat(),
                    direction.stepY.toFloat(),
                    direction.stepZ.toFloat()
                )

                facingWorld.rotation.transform(direction3d)
                faceWorld.rotationFast.transform(direction3d)

                val result = Direction.getNearest(direction3d.x, direction3d.y, direction3d.z)

                val id = BlockPosInt.pack(
                    facingWorld.index,
                    faceWorld.get3DDataValue(),
                    direction.get3DDataValue()
                )

                map[id] = result.get3DDataValue()
            }
        }
    }
}

fun incrementFromForwardUp(facing: FacingDirection, face: Direction, direction: Direction): Direction {
    val id = BlockPosInt.pack(
        facing.index,
        face.get3DDataValue(),
        direction.get3DDataValue()
    )

    return Direction.from3DDataValue(INCREMENT_FROM_FORWARD_UP.get(id))
}

fun incrementFromForwardUp(facing: FacingDirection, face: Direction, direction: Base6Direction3d) = incrementFromForwardUp(facing, face, direction.alias)

fun partX(facing: FacingDirection, faceWorld: Direction) = incrementFromForwardUp(facing, faceWorld, Direction.EAST)
fun partY(facing: FacingDirection, faceWorld: Direction) = incrementFromForwardUp(facing, faceWorld, Direction.UP)
fun partZ(facing: FacingDirection, faceWorld: Direction) = incrementFromForwardUp(facing, faceWorld, Direction.SOUTH)

fun Locator.transformPartWorld(directionPart: Base6Direction3d) : Direction {
    val facing = this.requireLocator<FacingLocator> { "Part -> World requires facing" }
    val face = this.requireLocator<FaceLocator> { "Part -> World requires face" }

    return incrementFromForwardUp(facing.facing, face, directionPart)
}

@JvmInline
value class PartConnectionDirection(val value: Int) {
    val mode get() = CellPartConnectionMode.byId[(value and 3)]
    val directionPart get() = Base6Direction3d.entries[(value shr 2) and 7]

    constructor(mode: CellPartConnectionMode, directionPart: Base6Direction3d) : this(mode.index or (directionPart.id shl 2))

    fun toNbt(): CompoundTag {
        val tag = CompoundTag()

        tag.putBase6Direction3d(DIR, directionPart)
        tag.putConnectionMode(MODE, mode)

        return tag
    }

    fun getIncrement(facing: FacingDirection, faceWorld: Direction): Vec3i = when(mode) {
        CellPartConnectionMode.Unknown -> {
            error("Undefined part connection")
        }
        CellPartConnectionMode.Planar -> {
            incrementFromForwardUp(facing, faceWorld, directionPart).normal
        }
        CellPartConnectionMode.Inner -> {
            Vec3i.ZERO
        }
        CellPartConnectionMode.Wrapped ->{
            val trWorld = incrementFromForwardUp(facing, faceWorld, directionPart)
            Vec3i(
                trWorld.stepX - faceWorld.stepX,
                trWorld.stepY - faceWorld.stepY,
                trWorld.stepZ - faceWorld.stepZ
            )
        }
    }

    companion object {
        private const val MODE = "mode"
        private const val DIR = "dir"

        fun fromNbt(tag: CompoundTag) = PartConnectionDirection(
            tag.getConnectionMode(MODE),
            tag.getBase6Direction3d(DIR),
        )
    }
}

fun getPartConnection(actualCell: Cell, remoteCell: Cell): PartConnectionDirection {
    return getPartConnection(actualCell.locator, remoteCell.locator)
}

fun getPartConnection(actualCell: Locator, remoteCell: Locator): PartConnectionDirection {
    val actualPosWorld = actualCell.requireLocator<BlockLocator>()
    val remotePosWorld = remoteCell.requireLocator<BlockLocator>()
    val actualFaceWorld = actualCell.requireLocator<FaceLocator>()
    val remoteFaceWorld = remoteCell.requireLocator<FaceLocator>()
    val remoteFacingWorld = actualCell.requireLocator<FacingLocator>()

    return getPartConnection(
        actualPosWorld,
        remotePosWorld,
        actualFaceWorld,
        remoteFaceWorld,
        remoteFacingWorld
    )
}

fun getPartConnectionOrNull(actualCell: Locator, remoteCell: Locator): PartConnectionDirection? {
    val actualPosWorld = actualCell.get<BlockLocator>() ?: return null
    val remotePosWorld = remoteCell.get<BlockLocator>() ?: return null
    val actualFaceWorld = actualCell.get<FaceLocator>() ?: return null
    val remoteFaceWorld = remoteCell.get<FaceLocator>() ?: return null
    val remoteFacingWorld = actualCell.get<FacingLocator>() ?: return null

    if (actualPosWorld == remotePosWorld) {
        if (actualFaceWorld == remoteFaceWorld) {
            // This is a very weird case, break here
            return null
        }
    }

    return getPartConnection(
        actualPosWorld,
        remotePosWorld,
        actualFaceWorld,
        remoteFaceWorld,
        remoteFacingWorld
    )
}

fun getPartConnection(
    actualPosWorld: BlockPos,
    remotePosWorld: BlockPos,
    actualFaceWorld: FaceLocator,
    remoteFaceWorld: FaceLocator,
    actualFacingWorld: FacingLocator
) : PartConnectionDirection {
    val mode: CellPartConnectionMode

    val dir = if (actualPosWorld == remotePosWorld) {
        if (actualFaceWorld == remoteFaceWorld) {
            error("Invalid configuration") // Cannot have multiple parts in same face, something is super wrong up the chain
        }

        // The only mode that uses this is the Inner mode.
        // But, if we find that the two directions are not perpendicular, this is not Inner, and as such, it is Unknown:
        if (actualFaceWorld == remoteFaceWorld.opposite) {
            // This is unknown. Inner connections happen between parts on perpendicular faces:
            mode = CellPartConnectionMode.Unknown
            actualFaceWorld
        } else {
            // This is Inner:
            mode = CellPartConnectionMode.Inner
            remoteFaceWorld.opposite
        }
    } else {
        // They are planar if the normals match up:
        if (actualFaceWorld == remoteFaceWorld) {
            val direction = actualPosWorld.directionTo(remotePosWorld)

            if (direction == null) {
                // They are not positioned correctly, which means Unknown:
                mode = CellPartConnectionMode.Unknown
                actualFaceWorld
            } else {
                // This is planar:
                mode = CellPartConnectionMode.Planar
                direction
            }
        } else {
            val direction = directionByNormal(remotePosWorld + actualFaceWorld - actualPosWorld)

            if (direction != null) {
                // Solution was found, this is wrapped:
                mode = CellPartConnectionMode.Wrapped
                direction
            } else {
                mode = CellPartConnectionMode.Unknown
                actualFaceWorld
            }
        }
    }

    return PartConnectionDirection(
        mode,
        Base6Direction3d.fromForwardUp(
            actualFacingWorld.facing,
            actualFaceWorld,
            dir
        )
    )
}

/**
 * Represents a part that can be ticked by the multipart block entity.
 * @see MultipartBlockEntity.addTicker
 * @see MultipartBlockEntity.hasTicker
 * @see MultipartBlockEntity.markRemoveTicker
 * */
interface TickablePart {
    fun tick()
}

/**
 * Represents a part that receives block animation ticks ([Block.animateTick]).
 * @see MultipartBlockEntity.addAnimated
 * @see MultipartBlockEntity.hasAnimated
 * @see MultipartBlockEntity.markRemoveAnimated
 * */
interface AnimatedPart {
    fun animationTick(random: RandomSource)
}

enum class RelightSource {
    Setup,
    BlockEvent
}

/**
 * This is the per-part renderer. One is created for every instance of a part.
 * The various methods may be called from separate threads.
 * Thread safety must be guaranteed by the implementation.
 * */
@CrossThreadAccess
abstract class PartRenderer {
    lateinit var multipart: MultipartBlockEntityInstance
        private set

    val hasMultipart get() = this::multipart.isInitialized

    val instancePosition : BlockPos get() {
        if(!hasMultipart) {
            error("Tried to get instance position before init")
        }

        return multipart.instancePosition
    }

    fun isSetupWith(multipartBlockEntityInstance: MultipartBlockEntityInstance): Boolean {
        return this::multipart.isInitialized && multipart == multipartBlockEntityInstance
    }

    /**
     * Called when the part is picked up by the [MultipartBlockEntity]'s renderer.
     * @param multipartInstance The multipart's renderer instance.
     * */
    fun setupRendering(multipartInstance: MultipartBlockEntityInstance) {
        this.multipart = multipartInstance
        setupRendering()
    }

    /**
     * Called to set up rendering, when the [multipart] has been acquired.
     * */
    protected open fun setupRendering() { }

    /**
     * Called when a light update occurs, or this renderer is set up (after [setupRendering]).
     * Models should be re-lit here
     * */
    open fun relight(source: RelightSource) { }

    /**
     * Called each frame.
     * This method may be used to play animations or to apply general per-frame updates.
     * */
    open fun beginFrame() { }

    /**
     * Called when the renderer is no longer required **OR** the rendering pipeline/backend/whatever is re-created. In that case, the renderer might be re-created just after this one is destroyed.
     * As an example, this will happen if the user switches flywheel backends, (I think) when the user changes some graphics settings, and it can also happen when the floating origin shifts.
     * All resources must be released here. If you have any data that you stored in this renderer but not in the part, and you would like to get it back, implement [PartRendererStateStorage].
     * */
    open fun remove() { }
}

/**
 * Helper interface for renderers that store state in the [PartRenderer] instance.
 * */
interface PartRendererStateStorage {
    /**
     * Called to restore the information from a previous renderer instance.
     * Could happen when the renderer is re-created, after being destroyed. Could happen when origin shifts, etc. Passed as [PartRenderer] because the type can actually change (e.g. if switching backends and the part chooses to create another renderer. Example: the wire)
     * */
    fun restoreSnapshot(renderer: PartRenderer)
}

fun interface PartRendererFactory<R : PartRenderer> {
    fun create(part: Part<R>): R
}

fun basicPartRenderer(model: PartialModel): PartRendererFactory<BasicPartRenderer> {
    return PartRendererFactory { part ->
        BasicPartRenderer(part, model)
    }
}

class SavingLifecycleTestPart(ci: PartCreateInfo) : Part<BasicPartRenderer>(ci) {
    override fun createRenderer(): BasicPartRenderer {
        return BasicPartRenderer(this, PartialModels.GROUND)
    }

    private fun print(string: String) {
        println(
            if(placement.level.isClientSide) {
                "[client] $string"
            }
            else {
                "[server] $string"
            }
        )
    }

    override fun getServerSaveTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("Disk", "Disk Data")
        print("getServerSaveTag")
        return tag
    }

    override fun loadServerSaveTag(tag: CompoundTag) {
        print("loadServerSaveTag ${tag.getString("Disk")} ${tag.size()}")
    }

    override fun getClientSaveTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("Client", "Client Data")
        print("getClientSaveTag")
        return tag
    }

    override fun loadClientSaveTag(tag: CompoundTag) {
        print("loadClientSaveTag ${tag.getString("Client")} ${tag.size()}")
    }
}
