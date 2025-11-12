package org.eln2.mc.common.parts.foundation

import dev.engine_room.flywheel.api.visual.LightUpdatedVisual
import dev.engine_room.flywheel.api.visual.SectionTrackedVisual
import dev.engine_room.flywheel.api.visual.Visual
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.ageseries.libage.data.Locator
import org.ageseries.libage.data.put
import org.ageseries.libage.data.requireLocator
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.OrientedBoundingBox3d
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.ClientOnly
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.LOG
import org.eln2.mc.ServerOnly
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.cells.foundation.CellAndContainerHandle
import org.eln2.mc.common.cells.foundation.CellContainer
import org.eln2.mc.common.cells.foundation.CellEnvironment
import org.eln2.mc.common.cells.foundation.CellGraphManager
import org.eln2.mc.common.cells.foundation.CellProvider
import org.eln2.mc.common.cells.foundation.ifNode
import org.eln2.mc.common.grids.CellTerminal
import org.eln2.mc.common.grids.GridMaterialCategory
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.grids.GridTerminal
import org.eln2.mc.common.grids.GridTerminalClient
import org.eln2.mc.common.grids.GridTerminalContainer
import org.eln2.mc.common.grids.GridTerminalSystem
import org.eln2.mc.common.grids.TerminalFactories
import org.eln2.mc.common.network.serverToClient.BulkMessages
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandler
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.PartMessage
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.specs.foundation.SpecGeometry
import org.eln2.mc.data.Locators
import org.eln2.mc.directionByNormal
import org.eln2.mc.extensions.*
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.BlockPosInt
import org.eln2.mc.mathematics.FacingDirection
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.cells.foundation.CellLayer
import org.eln2.mc.common.network.Networking
import org.eln2.mc.common.network.serverToClient.DimensionMessageToServerPart
import org.eln2.mc.common.network.serverToClient.ServerSidePacketHandler
import org.eln2.mc.common.network.serverToClient.ServerSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.id
import org.eln2.mc.data.hasLocalFrame
import org.eln2.mc.extensions.directionTo
import org.eln2.mc.extensions.minus
import org.eln2.mc.extensions.plus
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.mathematics.maskXY
import org.eln2.mc.requireIsOnRenderThread
import org.eln2.mc.requireIsOnServerThread
import org.joml.Vector3f
import java.util.UUID
import java.util.function.Supplier

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
    val positiveX = incrementFromForwardUp(facing, face, Direction.EAST)
    val positiveY = incrementFromForwardUp(facing, face, Direction.UP)
    val positiveZ = incrementFromForwardUp(facing, face, Direction.SOUTH)

    val facingWorld get() = positiveZ

    val mountingPointWorld = position.toVector3d() + Vector3d(0.5) - face.vector3d * 0.5

    fun createLocator(pipelikePartMaskPart: Base6Direction3dMask) = Locators.buildLocator {
        val layer = if(provider == PartRegistry.SPEC_CONTAINER_PART.part.get()) {
            CellLayer.Spec
        }
        else {
            CellLayer.Part
        }

        it.put(CELL_LAYER, layer)
        it.put(BLOCK, position)
        it.put(CONVENTIONAL_FACING, facing)
        it.put(SUBSTRATE_FACE, face)

        if(pipelikePartMaskPart.isNotEmpty) {
            it.put(PIPELIKE_MASK, pipelikePartMaskPart.transformed { directionPart ->
                incrementFromForwardUp(facing, face, directionPart)
            })
        }
    }
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

data class PartUpdate(val part: Part, val type: PartUpdateType)

data class PartUseInfo(val player: Player, val hand: InteractionHand)

data class PartCreateInfo(val id: ResourceLocation, val placement: PartPlacementInfo)

// For menu use
fun Part?.stillValid(player: Player) : Boolean {
    if(this == null) {
        return false
    }

    if(this.isRemoved) { // Is this right?
        return false
    }

    return (Vector3d(player.x, player.y, player.z) distanceTo this.placement.mountingPointWorld) < 10.0
}

@ServerOnly
fun Part.eln2WritePartGuiData(buf: FriendlyByteBuf) {
    buf.writeBlockPos(this.placement.position)
    buf.writeInt(this.placement.face.get3DDataValue())
}

@ClientOnly
inline fun<reified T : Part> FriendlyByteBuf.eln2ReadPartGuiData(inventory: Inventory) : T {
    requireIsOnRenderThread {
        "eln2ReadPartGuiData"
    }

    val blockPos = this.readBlockPos()
    val face = Direction.from3DDataValue(this.readInt())

    val level = inventory.player.level()

    val multipart = level.getBlockEntity(blockPos) as? MultipartBlockEntity
        ?: error("Got part open GUI but multipart doesn't exist $blockPos $face $inventory")

    val part = multipart.getPart(face)
        ?: error("Got part open GUI but part doesn't exist $blockPos $face $inventory")

    return (part as? T) ?: error("Got part open GUI but part $part wasn't the required type ${T::class}")
}

/**
 * Parts are entity-like units that exist in a multipart entity. They are similar to normal block entities,
 * but up to 6 can exist in the same block space.
 * They are placed on the inner faces of a multipart container block space.
 * */
abstract class Part(ci: PartCreateInfo) {
    companion object {
        fun createPartDropStack(id: ResourceLocation, saveTag: CompoundTag?, count: Int = 1): ItemStack {
            val item = PartRegistry.getPartItem(id)
            val stack = ItemStack(item, count)

            stack.tag = saveTag

            return stack
        }
    }

    /**
     * Checks if this part breaks when the substrate block is broken.
     * */
    @ServerOnly
    open fun breaksOnSubstrateBroken() = true

    /**
     * Called when the substrate changes. This is called before breaking is evaluated.
     * */
    open fun onSubstrateChanged(pos: BlockPos) { }

    val id = ci.id
    val placement = ci.placement
    val partProviderShape: VoxelShape = Shapes.create(modelBoundingBox)
    var isRemoved = false
        private set

    /**
     * Called to check if the part item should drop, just after it is destroyed.
     * */
    @ServerOnly
    open fun shouldDrop() : Boolean = true

    /**
     * Called after the part was constructed by the provider.
     * */
    open fun onCreated() { }

    /**
     * [ClientSidePacketHandler] for server -> client packets.
     * It will receive messages if and only if the base [handleBulkMessage] gets called when a bulk message is received.
     * If you override that, make sure you keep this in mind.
     * */
    @ClientOnly
    private val clientSidePacketHandlerLazy: Lazy<ClientSidePacketHandler> = lazy {
        val builder = ClientSidePacketHandlerBuilder()
        setupPacketsOnClient(builder)
        builder.build()
    }

    /**
     * [ServerSidePacketHandler] for client -> server packets.
     * It will receive messages if and only if the base [handleMessageFromClient] gets called when a message is received.
     * If you override that, make sure you keep this in mind.
     * */
    @ServerOnly
    private val serverSidePacketHandlerLazy: Lazy<ServerSidePacketHandler> = lazy {
        val builder = ServerSidePacketHandlerBuilder()
        setupPacketsOnServer(builder)
        builder.build()
    }

    /**
     * Called on the client to register handlers for bulk packets sent from the server.
     * */
    @ClientOnly
    protected open fun setupPacketsOnClient(builder: ClientSidePacketHandlerBuilder) { }

    /**
     * Called on the server to register handlers for packets sent from the client.
     * **WARNING! Make sure the data is sanitized and the client is allowed to send it!**
     * */
    @ServerOnly
    protected open fun setupPacketsOnServer(builder: ServerSidePacketHandlerBuilder) { }

    /**
     * Helper for determining if [sender] is reasonably close to the part to send changes.
     * Meant to be used for GUI packets.
     * */
    @ServerOnly
    protected open fun isAllowedToSendGUIChanges(sender: ServerPlayer) : Boolean {
        val playerPosition = Vector3d(sender.x, sender.y, sender.z)
        val partPosition = placement.mountingPointWorld

        return (playerPosition distanceTo partPosition) < 10.0
    }

    /**
     * Enqueues a bulk packet to be sent to the client.
     * This makes sense to call if and only if [P] is registered on the client in [setupPacketsOnClient], and the default behavior of [handleBulkMessage] gets executed.
     * */
    @ServerOnly
    protected inline fun <reified P> sendBulkPacket(packet: P) {
        enqueueBulkMessage(
            ClientSidePacketHandler.encode(packet)
        )
    }

    /**
     * Sends the packet to the server.
     * This makes sense to call if and only if [P] is registered on the server in [setupPacketsOnServer], and the default behavior of [handleMessageFromClient] gets executed.
     * */
    protected inline fun <reified P> sendPacketToServer(packet: P) {
        sendMessageToServer(
            ServerSidePacketHandler.encode(packet)
        )
    }

    @ClientOnly
    open fun handleBulkMessage(msg: ByteArray) {
        clientSidePacketHandlerLazy.value.handle(msg)
    }

    @ServerOnly
    open fun handleMessageFromClient(msg: ByteArray, sender: ServerPlayer) {
        serverSidePacketHandlerLazy.value.handle(msg, sender)
    }

    /**
     * Enqueues a message to be sent to the clients in bulk at the end of the tick.
     * */
    @ServerOnly
    fun enqueueBulkMessage(payload: ByteArray) {
        require(!placement.level.isClientSide) {
            "Tried to send bulk message from client"
        }

        BulkMessages.enqueuePartMessage(
            placement.level as ServerLevel,
            PartMessage(placement.position, placement.face, payload)
        )
    }

    /**
     * Sends a message to the server.
     * */
    fun sendMessageToServer(payload: ByteArray) {
        require(placement.level.isClientSide) {
            "Tried to send a message to the server from the server"
        }

        Networking.sendToServer(
            DimensionMessageToServerPart(
                placement.level.dimension().registry().id(),
                PartMessage(placement.position, placement.face, payload)
            )
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

    // TODO store these?
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

    fun translateShapeWorld(shape: VoxelShape): VoxelShape = shape.move(
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
     * Called when the part is right-clicked by a player.
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
     * Called when this part is being unloaded, on both client and server.
     * */
    open fun onUnloaded() {}

    /**
     * Called when the part is destroyed (broken).
     * */
    open fun onBroken() {}

    fun setRemoved() {
        if(this.isRemoved) {
            LOG.error(DEBUGGER_BREAK("Multiple calls to setRemoved"))
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

    @Suppress("UNCHECKED_CAST")
    open fun createVisual(ctx: MultipartVisualizationContext): AbstractPartVisual<*>? =
        (FlwVisualizerRegistry.getPartVisualizer(placement.provider) as PartVisualizer<Part>)
        .create(ctx, this)
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
    fun create(context: PartPlacementInfo): Part {
        val instance = createCore(context)
        instance.onCreated()
        return instance
    }

    protected abstract fun createCore(context: PartPlacementInfo): Part

    /**
     * This is the size used to validate placement. This is different from baseSize, because
     * you can implement a visual placement margin here.
     * */
    abstract val placementCollisionSize: Vector3d

    open fun canPlace(level: Level, substratePos: BlockPos, face: Direction): Boolean = true
}

fun interface PartFactory {
    operator fun invoke(ci: PartCreateInfo) : Part
}

/**
 * The basic part provider uses a functional interface as part factory.
 * Often, the part's constructor can be passed in as factory.
 * */
open class BasicPartProvider(
    final override val placementCollisionSize: Vector3d,
    val factory: PartFactory
) : PartProvider() {
    override fun createCore(context: PartPlacementInfo) = factory(PartCreateInfo(id, context))

    companion object {
        fun setup(placementCollisionSize: Vector3d, supplier: () -> PartFactory) = BasicPartProvider(placementCollisionSize, supplier())
    }
}

fun interface PartVisualizer<P : Part> {
    fun create(ctx: MultipartVisualizationContext, part: P): AbstractPartVisual<*>
}

abstract class AbstractPartVisual<T : Part>(val visualizationContext: MultipartVisualizationContext, val part: T) : Visual, LightUpdatedVisual {
    private var deleted = false

    override fun update(partialTick: Float) { }

    final override fun delete() {
        if(deleted) {
            return
        }

        _delete()
        deleted = true
    }

    override fun setSectionCollector(collector: SectionTrackedVisual.SectionCollector?) {
        // Should be already done by the parent, right?
    }

    @Suppress("FunctionName") // Keep consistent with the flywheel API
    protected abstract fun _delete()
}

/**
 * Represents a part that has a cell.
 * */
interface PartWithCell<C : Cell> {
    /**
     * This is the cell owned by the part.
     * */
    val cell: C

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

    fun addExtraConnections(results: MutableSet<CellAndContainerHandle>) { }
}

/**
 * Represents a part that is a fully featured cell container.
 * Some lifecycle hooks are provided for consistency with [PartWithCell].
 *
 * **Cells must have [Locators.SUBSTRATE_FACE] to resolve the part properly.**
 * */
interface PartCellContainer : CellContainer {
    fun cellUnbindAndDestroySuggested()
    fun cellConnectionsInsertFreshSuggested()
    fun cellBindGameObjectsSuggested()
}

/**
 * This part represents a simulation object. It can become part of a cell network.
 * */
abstract class CellPart<C: Cell>(
    ci: PartCreateInfo,
    final override val provider: CellProvider<C>,
    val pipelikeMaskPart: Base6Direction3dMask = Base6Direction3dMask.EMPTY
) : Part(ci), PartWithCell<C> {
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
                DEBUGGER_BREAK("TRIED TO ACCESS PART CELL ON CLIENT")
            } else {
                DEBUGGER_BREAK("Tried to get part cell before it is set $this")
            }
        )

    final override val hasCell: Boolean
        get() = cellField != null

    val locator = placement.createLocator(pipelikeMaskPart)

    /**
     * Used by the loading procedures.
     * */
    @ServerOnly
    private lateinit var loadGraphId: UUID

    @ServerOnly
    private var customSimulationData: CompoundTag? = null

    /**
     * Creates the cell, sets [Cell.container] and notifies via [onCellAcquired].
     * */
    override fun onPlaced() {
        cellField = provider.create(locator, CellEnvironment.evaluate(placement.level, locator))
        cell.container = placement.multipart
        onCellAcquired()
    }

    /**
     * Notifies the cell that the container has been removed.
     * */
    override fun onUnloaded() {
        if (hasCell) {
            requireIsOnServerThread { "onUnloaded part cell is null $this" }
            cell.onContainerUnloading()
            cell.container = null
            cell.onContainerUnloaded()
            cell.unbindGameObjects()
            onCellReleased()
        }
    }

    /**
     * The saved data includes the Graph ID. This is used to fetch the cell after loading.
     * */
    override fun getServerSaveTag(): CompoundTag? {
        if (!hasCell) {
            LOG.fatal("Part saving, but cell not initialized!")
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
            loadGraphId = tag.getUUID(GRAPH_ID)
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
            LOG.fatal("Part cell not initialized!")
            // Should we blow up the game or make the cell fresh?
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

    override val allowPlanarConnections get() = true
    override val allowInnerConnections get() = true
    override val allowWrappedConnections get() = true
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
    Wrapped(3),

    /**
     * Pipelike connections are the simplest connection types. The connections are similar to the connections between pipes from other mods.
     * */
    Pipelike(4);

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

fun Locator.transformPartWorld(directionPart: Base6Direction3d) : Direction {
    val facing = this.requireLocator(Locators.CONVENTIONAL_FACING) { "Part -> World requires facing" }
    val face = this.requireLocator(Locators.SUBSTRATE_FACE) { "Part -> World requires face" }

    return incrementFromForwardUp(facing, face, directionPart)
}

@JvmInline
value class PartConnectionDirection(val value: Int) {
    val mode get() = CellPartConnectionMode.byId[(value and 7)] // 3 bits for the 5 modes

    /**
     * If it's a part or spec or the locator simply has a local frame ([Locator.hasLocalFrame]), this direction will be in the local frame.
     * Otherwise, it will be in the world frame.
     * */
    val directionSpecificFrame get() = Base6Direction3d.entries[(value shr 3) and 7] // 3 bits for the 6 directions

    constructor(mode: CellPartConnectionMode, directionPart: Base6Direction3d) :
        this(mode.index or (directionPart.id shl 3))

    fun toNbt(): CompoundTag {
        val tag = CompoundTag()

        tag.putBase6Direction3d(DIR, directionSpecificFrame)
        tag.putConnectionMode(MODE, mode)

        return tag
    }

    // For parts only
    fun getIncrementInWorldFrame(facing: FacingDirection, faceWorld: Direction): Vec3i = when(mode) {
        CellPartConnectionMode.Unknown -> {
            error(DEBUGGER_BREAK("Undefined part connection"))
        }

        CellPartConnectionMode.Planar -> {
            incrementFromForwardUp(facing, faceWorld, directionSpecificFrame).normal
        }

        CellPartConnectionMode.Inner -> {
            Vec3i.ZERO
        }

        CellPartConnectionMode.Wrapped -> {
            val trWorld = incrementFromForwardUp(facing, faceWorld, directionSpecificFrame)
            Vec3i(
                trWorld.stepX - faceWorld.stepX,
                trWorld.stepY - faceWorld.stepY,
                trWorld.stepZ - faceWorld.stepZ
            )
        }

        CellPartConnectionMode.Pipelike -> {
            incrementFromForwardUp(facing, faceWorld, directionSpecificFrame).normal
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

fun getPartConnectionOrNull(actualCell: Locator, remoteCell: Locator): PartConnectionDirection? {
    // Required states. No connections can be done without them:
    val actualPosWorld = actualCell.get(Locators.BLOCK) ?: return null
    val remotePosWorld = remoteCell.get(Locators.BLOCK) ?: return null

    // Check for pipelike.
    // If it does exist, then it takes precedence over planar.
    val actualPipelikeMask = actualCell.get(Locators.PIPELIKE_MASK) // Both are in the world frame
    val remotePipelikeMask = remoteCell.get(Locators.PIPELIKE_MASK)

    // The connection can only happen if both objects have that mask:
    if(actualPipelikeMask != null && remotePipelikeMask != null) {
        // Get direction from the actual cell to the target cell.
        // If it does exist, then we are guaranteed the other cell is in the Von Neumann neighborhood:
        val direction = actualPosWorld.directionTo(remotePosWorld)

        if(direction != null) {
            // The conditions are now:
            // 1. The actual cell has this direction in its mask.
            // 2. The remote cell has the opposite of this direction in its mask.
            if(actualPipelikeMask.has(direction) && remotePipelikeMask.has(direction.opposite)) {
                return if(actualCell.hasLocalFrame()) {
                    // If it's possible to determine a direction in the local frame, then we will:
                    PartConnectionDirection(
                        CellPartConnectionMode.Pipelike,
                        Base6Direction3d.fromForwardUp(
                            actualCell.requireLocator(Locators.CONVENTIONAL_FACING),
                            actualCell.requireLocator(Locators.SUBSTRATE_FACE),
                            direction
                        )
                    )
                }
                else {
                    // Otherwise, we will return it in the world frame:
                    PartConnectionDirection(
                        CellPartConnectionMode.Pipelike,
                        direction.alias
                    )
                }
            }
        }
    }

    // If the connection isn't pipelike, we need all of these to determine a connection:
    val actualFaceWorld = actualCell.get(Locators.SUBSTRATE_FACE) ?: return null
    val remoteFaceWorld = remoteCell.get(Locators.SUBSTRATE_FACE) ?: return null
    val remoteFacingWorld = actualCell.get(Locators.CONVENTIONAL_FACING) ?: return null

    if (actualPosWorld == remotePosWorld) {
        if (actualFaceWorld == remoteFaceWorld) {
            // This is a very weird case, break here.
            // It's like we have two parts on the same face.
            DEBUGGER_BREAK()
            return null
        }
    }

    val mode: CellPartConnectionMode
    val dir = if (actualPosWorld == remotePosWorld) {
        if (actualFaceWorld == remoteFaceWorld) {
            error(DEBUGGER_BREAK("Invalid configuration")) // Cannot have multiple parts in same face, something is super wrong up the chain
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
            remoteFacingWorld, // what?
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
    fun serverTick() { }

    fun clientTick() { }
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

abstract class GridCellPart<C : Cell>(
    ci: PartCreateInfo,
    provider: CellProvider<C>,
) : CellPart<C>(ci, provider), GridTerminalContainer {
    var containerID: UUID = UUID.randomUUID()
        private set

    val gridTerminalSystem = GridTerminalSystem(placement.level)

    override fun onPlaced() {
        super.onPlaced()
        gridTerminalSystem.initializeFresh()

        cell.ifNode<GridNode> {
            it.mapFromGridTerminalSystem(gridTerminalSystem)
        }
    }

    override fun getServerSaveTag(): CompoundTag {
        val tag = super.getServerSaveTag() ?: CompoundTag()

        tag.put(GRID_TERMINAL_SYSTEM, gridTerminalSystem.save(GridTerminalSystem.SaveType.Server))
        tag.putUUID(CONTAINER_ID, containerID)

        return tag
    }

    override fun loadServerSaveTag(tag: CompoundTag) {
        super.loadServerSaveTag(tag)

        gridTerminalSystem.initializeSaved(tag.getCompound(GRID_TERMINAL_SYSTEM))
        containerID = tag.getUUID(CONTAINER_ID)
    }

    override fun getClientSaveTag() : CompoundTag {
        val tag = super.getClientSaveTag() ?: CompoundTag()

        tag.put(GRID_TERMINAL_SYSTEM, gridTerminalSystem.save(GridTerminalSystem.SaveType.Client))
        tag.putUUID(CONTAINER_ID, containerID)

        return tag
    }

    override fun loadClientSaveTag(tag: CompoundTag) {
        super.loadClientSaveTag(tag)

        gridTerminalSystem.initializeSaved(tag.getCompound(GRID_TERMINAL_SYSTEM))
        containerID = tag.getUUID(CONTAINER_ID)
    }

    /**
     * Creates a bounding box in the world frame.
     * @param x Center X in the local frame.
     * @param y Center Y in the local frame.
     * @param z Center Z in the local frame.
     * @param sizeX Size along X in the local frame.
     * @param sizeY Size along Y in the local frame.
     * @param sizeZ Size along Z in the local frame.
     * @return A bounding box in the world frame.
     * */
    protected fun boundingBox(
        x: Double,
        y: Double,
        z: Double,
        sizeX: Double,
        sizeY: Double,
        sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
    ) = SpecGeometry.boundingBox(
        placement.mountingPointWorld +
            placement.positiveX.vector3d * x +
            placement.positiveY.vector3d * y +
            placement.positiveZ.vector3d * z,
        orientation * placement.facing.rotation2d,
        Vector3d(sizeX, sizeY, sizeZ),
        placement.facing,
        placement.face
    )

    fun boundingBox(box: BoundingBox3d, orientation: Rotation2d = Rotation2d.identity) : OrientedBoundingBox3d {
        val center = box.center
        val size = box.size
        return boundingBox(center.x, center.y, center.z, size.x, size.y, size.z, orientation)
    }

    protected fun defineCellBoxTerminal(box3d: OrientedBoundingBox3d, attachment: Vector3d? = null, highlightColor : MyColor? = MyColor(
        0.8f,
        1f,
        0.58f,
        0.44f
    ), categories: List<GridMaterialCategory>) = gridTerminalSystem.defineTerminal<GridTerminal>(
        TerminalFactories(
            { ci ->
                CellTerminal(ci, locator, attachment ?: box3d.center, box3d) { this.cell }.also {
                    it.categories.addAll(categories)
                }
            },
            { GridTerminalClient(it, locator, attachment ?: box3d.center, box3d, highlightColor) }
        )
    )

    protected fun defineCellBoxTerminal(
        x: Double, y: Double, z: Double,
        sizeX: Double, sizeY: Double, sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
        attachment: Vector3d? = null,
        highlightColor: MyColor? = MyColor(0.8f, 1f, 0.58f, 0.44f),
        categories: List<GridMaterialCategory> = listOf(GridMaterialCategory.MicroGrid),
    ) = defineCellBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation), attachment, highlightColor, categories)

    // BB = BlockBench
    fun defineCellBoxTerminalBB(
        x: Double, y: Double, z: Double,
        sizeX: Double, sizeY: Double, sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
        attachment: Vector3d? = null,
        highlightColor: MyColor? = MyColor(0.8f, 1f, 0.58f, 0.44f),
        categories: List<GridMaterialCategory> = listOf(GridMaterialCategory.MicroGrid),
        modelScale: Double = 1.0
    ) : Supplier<GridTerminal> {
        val size = Vector3d(sizeX / 16.0, sizeY / 16.0, sizeZ / 16.0) * modelScale
        val box = BoundingBox3d.fromCenterSize(-Vector3d.unitY * size.y / 2.0 + ((Vector3d(x / 16.0, y / 16.0, z / 16.0)) - Vector3d.one * maskXY / 2.0) * modelScale + size / 2.0, size)
        return defineCellBoxTerminal(boundingBox(box, orientation), attachment, highlightColor, categories)
    }

    override fun pickTerminal(player: LivingEntity) = gridTerminalSystem.pick(player)

    override fun getTerminalByEndpointID(endpointID: UUID) = gridTerminalSystem.getByEndpointID(endpointID)

    override fun addExtraConnections(results: MutableSet<CellAndContainerHandle>) {
        gridTerminalSystem.forEachTerminalOfType<CellTerminal> {
            if(it.stagingCell != null) {
                results.add(CellAndContainerHandle.captureInScope(it.stagingCell!!))
            }
        }

        if(hasCell) {
            cell.ifNode<GridNode> { node ->
                node.forEachConnectionCell {
                    results.add(CellAndContainerHandle.captureInScope(it))
                }
            }
        }
    }

    override fun onBroken() {
        gridTerminalSystem.destroy()
    }

    companion object {
        private const val CONTAINER_ID = "containerID"
        private const val GRID_TERMINAL_SYSTEM = "gridTerminalSystem"
    }
}

/**
 * The Part Item delegates the placement of a Part to the Multipart Container.
 * */
open class PartItem(val partProvider: Lazy<PartProvider>) : BlockItem(BlockRegistry.MULTIPART_BLOCK.get(), Properties()) {
    // Patch for SpecItem
    // Items get registered before our specs, parts, etc so we don't have the registered part provider when the spec item gets registred
    // we add this lazy to mitigate, and sort of compensate by calling this ensureResolved in our finishing pass

    fun ensureResolved() {
        if(partProvider.isInitialized()) {
            return
        }

        LOG.debug("Resolved part item {}", partProvider.value)
    }

    constructor(provider: PartProvider) : this(lazy { provider }) {
        ensureResolved() // We were passed the item, we can do immediately
    }

    constructor(supplier: Supplier<PartProvider>) : this(lazy { supplier.get() })

    override fun useOn(pContext: UseOnContext): InteractionResult {
        val player = pContext.player

        if (player == null) {
            LOG.error("Null player!")
            return InteractionResult.FAIL
        }

        val level = pContext.level
        val substratePos = pContext.clickedPos
        val face = pContext.clickedFace

        val flag = MultipartBlockEntity.canPlacePartInSubstrate(
            level,
            substratePos,
            face,
            partProvider.value,
            player
        )

        if(!flag) {
            return InteractionResult.FAIL
        }

        val multipartPos = substratePos + face

        LOG.debug("Placing part at {}", multipartPos)

        var multipartBlockEntity = level.getBlockEntity(multipartPos)

        if (multipartBlockEntity == null) {
            if(level.isClientSide) {
                // Assume it works out
                return InteractionResult.SUCCESS
            }

            // Place multipart
            super.useOn(pContext)
            multipartBlockEntity = level.getBlockEntity(multipartPos)
        } else {
            multipartBlockEntity as MultipartBlockEntity

            if (multipartBlockEntity.placementCollides(player, face, partProvider.value)) {
                LOG.debug("Collides with part")
                return InteractionResult.FAIL
            }

            if(level.isClientSide) {
                return InteractionResult.SUCCESS
            }
        }

        check(!level.isClientSide)
        level as ServerLevel

        LOG.debug("Target multipart entity: {}", multipartBlockEntity)

        if (multipartBlockEntity == null) {
            LOG.error("Placed multipart is null") // Maybe an entity is standing or some other external thing? I'
            return InteractionResult.FAIL
        }

        val isPlaced = (multipartBlockEntity as MultipartBlockEntity).place(
            player,
            multipartPos,
            pContext.clickedFace,
            partProvider.value,
            pContext.itemInHand.tag
        )

        return if (isPlaced) InteractionResult.CONSUME
        else InteractionResult.FAIL
    }

    override fun getDescriptionId(): String {
        // By default, this uses the block's description ID.
        // This is not what we want.

        return orCreateDescriptionId
    }
}
