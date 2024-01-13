package org.eln2.mc.common.grids

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraftforge.client.event.RenderHighlightEvent
import org.ageseries.libage.data.Locator
import org.ageseries.libage.data.MutableMapPairBiMap
import org.ageseries.libage.mathematics.geometry.OrientedBoundingBox3d
import org.ageseries.libage.mathematics.geometry.Ray3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.ClientOnly
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.foundation.RGBAFloat
import org.eln2.mc.client.render.foundation.eln2SubmitOBBAtLevelStage
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.cells.foundation.requireNode
import org.eln2.mc.common.blocks.foundation.GridCellBlockEntity
import org.eln2.mc.common.parts.foundation.GridCellPart
import org.eln2.mc.common.specs.foundation.GridSpec
import org.eln2.mc.common.specs.foundation.SpecContainerPart
import org.eln2.mc.data.Notifier
import org.eln2.mc.extensions.*
import org.eln2.mc.requireIsOnRenderThread
import org.eln2.mc.requireIsOnServerThread
import java.util.*
import java.util.function.Supplier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Holds a reference to a [GridTerminal], along with the game object and any other metadata.
 * @param terminal The terminal to handle.
 * */
abstract class GridTerminalHandle(val terminal: GridTerminal) {
    abstract val gameObject: Any

    /**
     * Checks if the container handling this terminal is the same as the container handling [other].
     * */
    fun isSameContainer(other: GridTerminalHandle) : Boolean {
        if(this is SpecHandle && other is SpecHandle) {
            return this.spec === other.spec
        }

        return false
    }

    /**
     * Used to store all information necessary to get the [GridTerminal] from the world at a later time.
     * This is how [GridTerminal] edges are meant to be stored long-term.
     * A reference to [GridTerminal] should not be stored directly; usually, the [GridTerminal] will reference its game object and will keep it in-scope, even though it shouldn't (e.g. the chunk gets unloaded).
     * */
    abstract fun toNbt() : CompoundTag

    enum class Type {
        Spec,
        Part,
        BlockEntity
    }

    /**
     * Handle for a [GridTerminalContainer] implemented by a [GridSpec].
     * */
    class SpecHandle(terminal: GridTerminal, val part: SpecContainerPart, val spec: GridSpec<*>) : GridTerminalHandle(terminal) {
        override val gameObject: Any
            get() = spec

        override fun toNbt(): CompoundTag {
            val tag = CompoundTag()

            tag.putInt(TYPE, Type.Spec.ordinal)
            tag.putBlockPos(BLOCK_POS, part.placement.position)
            tag.putDirection(FACE, part.placement.face)
            tag.putUUID(CONTAINER_ID, part.containerID)
            tag.putInt(PLACEMENT_ID, spec.placement.placementId)
            tag.putUUID(ENDPOINT_ID, terminal.gridEndpointInfo.id)

            return tag
        }
    }

    class PartHandle(terminal: GridTerminal, val part: GridCellPart<*, *>) : GridTerminalHandle(terminal) {
        override val gameObject: Any
            get() = part

        override fun toNbt(): CompoundTag {
            val tag = CompoundTag()

            tag.putInt(TYPE, Type.Part.ordinal)
            tag.putBlockPos(BLOCK_POS, part.placement.position)
            tag.putDirection(FACE, part.placement.face)
            tag.putUUID(CONTAINER_ID, part.containerID)
            tag.putUUID(ENDPOINT_ID, terminal.gridEndpointInfo.id)

            return tag
        }
    }

    class BlockEntityHandle(terminal: GridTerminal, val blockEntity: GridCellBlockEntity<*>) : GridTerminalHandle(terminal) {
        override val gameObject: Any
            get() = blockEntity

        override fun toNbt(): CompoundTag {
            val tag = CompoundTag()

            tag.putInt(TYPE, Type.BlockEntity.ordinal)
            tag.putBlockPos(BLOCK_POS, blockEntity.blockPos)
            tag.putUUID(CONTAINER_ID, blockEntity.containerID)
            tag.putUUID(ENDPOINT_ID, terminal.gridEndpointInfo.id)

            return tag
        }
    }

    companion object {
        private const val TYPE = "type"
        private const val BLOCK_POS = "pos"
        private const val FACE = "face"
        private const val PLACEMENT_ID = "placementId"
        private const val ENDPOINT_ID = "endpointId"
        private const val CONTAINER_ID = "uuid"

        /**
         * Restores a handle from the saved data immediately (the chunks will get loaded).
         * @return The restored handle or null, if something went wrong (the game object is no longer there), or the saved data is invalid.
         * */
        fun restoreImmediate(pLevel: ServerLevel, tag: CompoundTag) : GridTerminalHandle? {
            if(!tag.contains(TYPE)) {
                return null
            }

            return when(Type.entries[tag.getInt(TYPE)]) {
                Type.Spec -> {
                    val pos = tag.getBlockPos(BLOCK_POS)

                    val multipart = pLevel.getBlockEntity(pos) as? MultipartBlockEntity
                        ?: return null

                    val part = multipart.getPart(tag.getDirection(FACE)) as? SpecContainerPart
                        ?: return null

                    if(part.containerID != tag.getUUID(CONTAINER_ID)) {
                        return null
                    }

                    val spec = part.getSpecByPlacementID(tag.getInt(PLACEMENT_ID)) as? GridSpec<*>
                        ?: return null

                    val terminal = spec.getTerminalByEndpointID(tag.getUUID(ENDPOINT_ID))
                        ?: return null

                    SpecHandle(terminal, part, spec)
                }

                Type.Part -> {
                    val pos = tag.getBlockPos(BLOCK_POS)

                    val multipart = pLevel.getBlockEntity(pos) as? MultipartBlockEntity
                        ?: return null

                    val part = multipart.getPart(tag.getDirection(FACE)) as? GridCellPart<*, *>
                        ?: return null

                    if(part.containerID != tag.getUUID(CONTAINER_ID)) {
                        return null
                    }

                    val terminal = part.getTerminalByEndpointID(tag.getUUID(ENDPOINT_ID))
                        ?: return null

                    PartHandle(terminal, part)
                }

                Type.BlockEntity -> {
                    val pos = tag.getBlockPos(BLOCK_POS)

                    val blockEntity = pLevel.getBlockEntity(pos) as? GridCellBlockEntity<*>
                        ?: return null

                    if(blockEntity.containerID != tag.getUUID(CONTAINER_ID)) {
                        return null
                    }

                    val terminal = blockEntity.getTerminalByEndpointID(tag.getUUID(ENDPOINT_ID))
                        ?: return null

                    BlockEntityHandle(terminal, blockEntity)
                }
            }
        }

        private fun getPlayerPOVHitResult(pLevel: Level, pPlayer: Player): BlockHitResult {
            val f = pPlayer.xRot
            val f1 = pPlayer.yRot
            val vec3 = pPlayer.eyePosition
            val f2 = cos(-f1 * (PI.toFloat() / 180f) - PI.toFloat())
            val f3 = sin(-f1 * (PI.toFloat() / 180f) - PI.toFloat())
            val f4 = -cos(-f * (PI.toFloat() / 180f))
            val f5 = sin(-f * (PI.toFloat() / 180f))
            val f6 = f3 * f4
            val f7 = f2 * f4
            val d0 = pPlayer.getBlockReach()
            val vec31 = vec3.add(f6.toDouble() * d0, f5.toDouble() * d0, f7.toDouble() * d0)
            return pLevel.clip(
                ClipContext(
                    vec3,
                    vec31,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.SOURCE_ONLY,
                    pPlayer
                )
            )
        }

        /**
         * Picks a grid terminal from the world.
         * */
        fun pick(pLevel: Level, pPlayer: Player) : GridTerminalHandle? {
            val hit = getPlayerPOVHitResult(pLevel, pPlayer)

            if (hit.type != HitResult.Type.BLOCK) {
                return null
            }

            val targetBlockEntity = pLevel.getBlockEntity(hit.blockPos)
                ?: return null

            if(targetBlockEntity !is MultipartBlockEntity) {
                if(targetBlockEntity !is GridCellBlockEntity<*>) {
                    return null
                }

                val terminal = targetBlockEntity.pickTerminal(pPlayer)
                    ?: return null

                return BlockEntityHandle(terminal, targetBlockEntity)
            }

            val part = targetBlockEntity.pickPart(pPlayer)
                ?: return null

            when (part) {
                is SpecContainerPart -> {
                    val spec = part.pickSpec(pPlayer)?.second as? GridSpec<*>
                        ?: return null

                    val terminal = spec.pickTerminal(pPlayer)
                        ?: return null

                    return SpecHandle(terminal, part, spec)
                }

                is GridCellPart<*, *> -> {
                    val terminal = part.pickTerminal(pPlayer)
                        ?: return null

                    return PartHandle(terminal, part)
                }

                else -> {
                    return null
                }
            }
        }
    }
}

/**
 * Describes the connection and indicates the remote terminal.
 * @param handle A handle to the remote terminal.
 * @param description Description of the connection itself.
 * **This holds a reference to the remote terminal, which may hold the remote game object in scope. Do not store this persistently! Store a capture using [capture].**
 * */
data class RemoteTerminalConnection(val handle: GridTerminalHandle, val description: GridConnectionDescription)

/**
 * Captures the information encapsulated by a [RemoteTerminalConnection] into a [GridPeer].
 * This removes the references to the remote terminals.
 * */
fun RemoteTerminalConnection.capture() = GridPeer(handle.terminal.gridEndpointInfo, handle.toNbt())

/**
 * Terminal (connection point) for *grid connections*.
 * Usually managed by a game object.
 * @param terminalID The unique ID of this terminal, assigned by the [gridTerminalSystem].
 * @param uuid The unique grid endpoint ID of this terminal, assigned by the [gridTerminalSystem].
 * @param locator A locator that best describes this terminal's location. Usually this is just the locator of the game object that owns the terminal, but may change in the future.
 * @param attachment The attachment point of the cable, in the fixed frame.
 * */
abstract class GridTerminal(
    val gridTerminalSystem: GridTerminalSystem,
    val terminalID: Int,
    uuid: UUID,
    locator: Locator,
    attachment: Vector3d,
    val boundingBox: OrientedBoundingBox3d
) {
    val gridEndpointInfo = GridEndpointInfo(uuid, attachment, locator)

    /**
     * Gets the closest point of intersection with [ray].
     * @return The point of intersection or null, if no intersection occurs.
     * */
    fun intersect(ray: Ray3d) : Vector3d? {
        val intersection = (ray intersectionWith boundingBox)
            ?: return null

        return ray.evaluate(intersection.entry)
    }

    /**
     * Destroys the terminal and associated things. Called by the [gridTerminalSystem] when it is destroyed.
     * */
    open fun destroy() { }
}

/**
 * Client-only [GridTerminal]. This has no connection logic, but is useful for rendering.
 * */
open class GridTerminalClient(
    ci: TerminalCreateInfoClient,
    locator: Locator,
    attachment: Vector3d,
    boundingBox: OrientedBoundingBox3d,
    var color: RGBAFloat? = RGBAFloat(1f, 0f, 0f, 1f)
) : GridTerminal(
    ci.gridTerminalSystem,
    ci.terminalID,
    ci.uuid,
    locator,
    attachment,
    boundingBox
) {
    init {
        requireIsOnRenderThread {
            "Cannot create client-only terminal on non-client!"
        }
    }
}

/**
 * Container for grid graph data, focused on storing data needed by one terminal.
 * */
interface TerminalConnectionStorage {
    /**
     * Adds a connection to the remote terminal.
     * @param handle A handle to the remote terminal.
     * @param description Information about the connection.
     * Produces an error if a connection to the remote terminal already exists.
     * */
    fun addConnection(handle: GridTerminalHandle, description: GridConnectionDescription) { }

    /**
     * Removes the connection with the remote endpoint with id [remoteEndpointID].
     * */
    fun removeConnection(remoteEndpointID: UUID) { }

    /**
     * Checks if this terminal has a connection to the remote endpoint with id [remoteEndpointID]'s termininal with id [remoteTerminal].
     * */
    fun hasConnectionWith(remoteEndpointID: UUID, remoteTerminal: Int) : Boolean

    /**
     * Called when the terminal is destroyed. This only happens when the entire game object is destroyed.
     * */
    fun destroy() { }
}

/**
 * Server-only [GridTerminal]. This has connection logic, storage, etc.
 * */
abstract class GridTerminalServer(
    ci: TerminalCreateInfoServer,
    locator: Locator,
    attachment: Vector3d,
    boundingBox: OrientedBoundingBox3d
) : GridTerminal(
    ci.gridTerminalSystem,
    ci.terminalID,
    ci.uuid,
    locator,
    attachment,
    boundingBox
) {
    val level = ci.level
    /**
     * Set of grid material categories this terminal accepts.
     * */
    val categories = HashSet<GridMaterialCategory>()

    abstract val storage: TerminalConnectionStorage

    /**
     * Raised when the stored connections change, and the game object may want to ensure that this terminal gets saved.
     * */
    val connectionsChanged = Notifier()

    init {
        requireIsOnServerThread {
            "Cannot create server-only terminal on non-server!"
        }
    }

    /**
     * Temporarily holds information about a remote peer, when the [GridCableItem] is making the connection.
     * */
    private var stagingInfo: RemoteTerminalConnection? = null

    /**
     * Called to start making a connection to a remote end point.
     * */
    open fun beginConnectStaging(info: RemoteTerminalConnection) {
        check(stagingInfo == null) {
            "Multiple begin connection staging"
        }

        this.stagingInfo = info
    }

    /**
     * Records the connection that is currently staging.
     * Throws an exception if a connection with the same ID exists.
     * */
    @ServerOnly
    open fun addConnection() {
        requireIsOnServerThread {
            "Cannot add connection on non-server"
        }

        val info = checkNotNull(stagingInfo)
        storage.addConnection(info.handle, info.description)
        connectionsChanged.run()
    }

    /**
     * Ends connection staging by clearing [stagingInfo], and notifies [connectionsChanged] so the terminal gets saved.
     * */
    open fun endConnectStaging() {
        checkNotNull(this.stagingInfo)
        this.stagingInfo = null
        connectionsChanged.run()
    }

    /**
     * Removes the connection with [remoteEndpointID].
     * **This does not remove the connection from the remote terminal's storage!**
     * Throws an exception if no connection with [remoteEndpointID] exists.
     * */
    @ServerOnly
    fun removeConnection(remoteEndpointID: UUID) {
        requireIsOnServerThread {
            "Cannot remove connection on non-server"
        }

        storage.removeConnection(remoteEndpointID)
        connectionsChanged.run()
    }

    /**
     * Destroys this terminal.
     * It restores the handles to the remote terminals and removes the connection to this terminal, and also destroys the connections from [GridConnectionManagerServer].
     * */
    override fun destroy() {
        requireIsOnServerThread {
            "destroyTerminal is illegal to call on non-server"
        }

        storage.destroy()
    }

    /**
     * Gets a tag to save on the server.
     * */
    open fun getServerSaveTag(): CompoundTag? = null

    /**
     * Gets a tag to send to the client on initial loading.
     * */
    open fun getClientSaveTag(): CompoundTag? = null

    /**
     * Checks if the terminal or overall container accepts connections made of [material].
     * */
    open fun acceptsMaterial(player: LivingEntity, material: GridMaterial) = categories.contains(material.category)
}

/**
 * Container of multiple [GridTerminal]s.
 * Usually implemented by a game object.
 * */
interface GridTerminalContainer {
    /**
     * Gets the terminal selected by [player].
     * @return The terminal selected by [player] or null, if the player's view does not intersect any terminals.
     * */
    fun pickTerminal(player: LivingEntity) : GridTerminal?

    /**
     * Gets the terminal by its [endpointID].
     * @return The terminal whose [GridTerminal.gridEndpointInfo]'s id is [endpointID] or null, if there is no such terminal.
     * */
    fun getTerminalByEndpointID(endpointID: UUID) : GridTerminal?
}

data class TerminalCreateInfoServer(
    val gridTerminalSystem: GridTerminalSystem,
    val terminalID: Int,
    val level: ServerLevel,
    val uuid: UUID,
    val serverTag: CompoundTag?,
)

data class TerminalCreateInfoClient(
    val gridTerminalSystem: GridTerminalSystem,
    val terminalID: Int,
    val level: Level,
    val uuid: UUID,
    val clientTag: CompoundTag?,
)

data class TerminalFactories(
    val server: (TerminalCreateInfoServer) -> GridTerminalServer,
    val client: (TerminalCreateInfoClient) -> GridTerminalClient,
)

/**
 * State management for a collection of [GridTerminal]s.
 * Usage is as follows:
 * - [GridTerminalSystem] is initialized in a field
 * - Following that, the desired terminals are deferred for creation using the [defineTerminal] methods. The results (suppliers that will return the instance, but are valid to call only after the terminals are created) are also stored in fields.
 * - Terminals are finally created freshly (e.g. when the game object is first placed) or loaded (from previously saved data).
 * - After that, no more terminals can be created, and the [instances] are safe to get.
 * */
class GridTerminalSystem(val level: Level) {
    private val factories = HashMap<Int, TerminalFactories>()
    private val instancesInternal = MutableMapPairBiMap<Int, GridTerminal>()
    private var currentID = 0
    private var isInitialized = false
    private var isDestroyed = false

    /**
     * Gets the instances. Only valid to call after initialization.
     * */
    val instances: Map<Int, GridTerminal>
        get() {
            check(isInitialized) {
                "Tried to get instances before initialized"
            }

            return instancesInternal.forward
        }

    inline fun<reified T> instancesOfType() : Map<Int, T> {
        val results = HashMap<Int, T>()

        instances.forEach { (id, instance) ->
            if(instance is T) {
                results.putUnique(id, instance)
            }
        }

        return results
    }

    inline fun<reified T> forEachTerminalOfType(use: (T) -> Unit) = instances.values.forEach {
        if(it is T) {
            use(it)
        }
    }

    inline fun<reified T> terminalsOfType() : List<T> = instances.values.mapNotNull { it as? T }

    private fun create(factories: TerminalFactories, id: Int, uuid: UUID, tag: CompoundTag?) = if(level.isClientSide) {
        factories.client(
            TerminalCreateInfoClient(
                this,
                id,
                level,
                uuid,
                tag
            )
        )
    }
    else {
        factories.server(
            TerminalCreateInfoServer(
                this,
                id,
                level as ServerLevel,
                uuid,
                tag
            )
        )
    }

    /**
     * Initializes the current deferred terminals.
     * This should be called when the game object is first placed (no saved data exists).
     * */
    fun initializeFresh() {
        check(!isInitialized) {
            "Tried to initialize fresh grid terminal system multiple times"
        }

        check(!isDestroyed) {
            "Tried to initialize fresh grid terminals after destroyed"
        }

        isInitialized = true

        factories.forEach { (id, factories) ->
            val terminal = create(factories, id, UUID.randomUUID(), null)
            instancesInternal.add(id, terminal)
        }

        factories.clear()
    }

    /**
     * Initializes the current deferred terminals. This should be called to restore the state from saved data (server) or to restore client-specific data when the game object is sent to the client.
     * @param tag The server/client data tag.
     * */
    fun initializeSaved(tag: CompoundTag) {
        check(!isInitialized) {
            "Tried to initialize saved grid terminal system multiple times"
        }

        check(!isDestroyed) {
            "Tried to initialize saved grid terminals after destroyed"
        }

        isInitialized = true

        if(!tag.contains(TERMINALS)) {
            return
        }

        val terminals = tag.getListTag(TERMINALS)
        var missing = factories.size

        terminals.forEach { terminalCompound ->
            terminalCompound as CompoundTag

            val terminalID = terminalCompound.getInt(TERMINAL_ID)
            val endpointID = terminalCompound.getUUID(ENDPOINT_ID)

            val customTag = if(terminalCompound.contains(TAG)) {
                terminalCompound.getCompound(TAG)
            }
            else {
                null
            }

            val factories = checkNotNull(factories[terminalID]) {
                "Mis-matched terminal $terminalID in saved data"
            }

            val terminal = create(factories, terminalID, endpointID, customTag)

            instancesInternal.add(terminalID, terminal)

            missing--
        }

        check(missing == 0) {
            "Did not have all terminals in saved data!"
        }
    }

    /**
     * Destroys all terminals.
     * */
    fun destroy() {
        check(!isDestroyed) {
            "Tried to destroy grid terminals multiple times"
        }

        isDestroyed = true

        instancesInternal.forward.values.forEach {
            it.destroy()
        }
    }

    enum class SaveType {
        Server,
        Client
    }

    /**
     * Saves the state of the terminals to a [CompoundTag]. This is meant to be loaded with [initializeSaved] at a later time.
     * @param saveType The destination of the data. [SaveType.Server] saves using [GridTerminalServer.getServerSaveTag], and [SaveType.Client] saves using [GridTerminalServer.getClientSaveTag].
     * */
    @ServerOnly
    fun save(saveType: SaveType) : CompoundTag {
        requireIsOnServerThread {
            "Cannot save grid terminal system non-server"
        }

        check(isInitialized) {
            "Tried to save grid terminal system before initialized"
        }

        check(!isDestroyed) {
            "Tried to save grid terminals after destroyed"
        }

        val tag = CompoundTag()
        val list = ListTag()

        instancesInternal.forward.forEach { (terminalID, terminal) ->
            terminal as GridTerminalServer

            val terminalCompound = CompoundTag()

            terminalCompound.putInt(TERMINAL_ID, terminalID)
            terminalCompound.putUUID(ENDPOINT_ID, terminal.gridEndpointInfo.id)

            val customTag = when(saveType) {
                SaveType.Server -> terminal.getServerSaveTag()
                SaveType.Client -> terminal.getClientSaveTag()
            }

            if(customTag != null) {
                terminalCompound.put(TAG, customTag)
            }

            list.add(terminalCompound)
        }

        tag.put(TERMINALS, list)

        return tag
    }

    /**
     * Defines a terminal for deferred creation.
     * @param factory The factories to create the terminal.
     * @return A supplier that will return the instance of the terminal. Only valid to use after [initializeFresh] or [initializeSaved] was called.
     * */
    fun <T : GridTerminal> defineTerminal(factory: TerminalFactories): Supplier<T> {
        check(!isInitialized) {
            "Tried to define terminal after initialized"
        }

        check(!isDestroyed) {
            "Tried to define terminals after destroyed"
        }

        val id = currentID++

        factories.putUnique(id, TerminalFactories(
            { pCreateInfo ->
                factory.server(pCreateInfo)
            },
            { pCreateInfo ->
                factory.client(pCreateInfo)
            }
        ))

        return Supplier {
            check(isInitialized) {
                "Tried to get terminal before initialized"
            }

            @Suppress("UNCHECKED_CAST")
            return@Supplier checkNotNull(instancesInternal.forward[id]) {
                "Expected terminal instance for $id"
            } as T
        }
    }

    /**
     * Gets the terminal [player] is looking at.
     * @return The terminal intersected by [player]'s view or null, if no terminals intersect.
     * */
    fun pick(player: LivingEntity) : GridTerminal? {
        check(isInitialized) {
            "Tried to pick micro grid terminal before initialized"
        }

        val ray = player.getViewRay()

        return instancesInternal.forward.values.mapNotNull {
            val intersection = it.intersect(ray)
                ?: return@mapNotNull null

            it to intersection
        }.minByOrNull { (_, intersection) ->
            intersection distanceToSqr ray.origin
        }?.first
    }

    /**
     * Gets a terminal by its [endpointID].
     * */
    fun getByEndpointID(endpointID: UUID) = instancesInternal.forward.values.firstOrNull { it.gridEndpointInfo.id == endpointID }

    companion object {
        private const val TERMINALS = "terminals"
        private const val ENDPOINT_ID = "endpointID"
        private const val TERMINAL_ID = "id"
        private const val TAG = "tag"
    }
}

@ClientOnly
object TerminalHighlightRenderer {
    fun render(event: RenderHighlightEvent.Block) {
        val level = Minecraft.getInstance().level
            ?: return

        val player = Minecraft.getInstance().player
            ?: return

        val handle = GridTerminalHandle.pick(level, player)
            ?: return

        handle.terminal as GridTerminalClient

        val color = handle.terminal.color
            ?: return

        val obb = handle.terminal.boundingBox

        event.multiBufferSource.getBuffer(RenderType.lines()).eln2SubmitOBBAtLevelStage(
           event.poseStack,
           obb,
           color,
           event.camera
       )
    }
}

/**
 * Represents a terminal that ties a cell to the grid.
 * @param cellAccessor Getter for the cell. A [GridNode] must be attached to the cell before any queries made to [storage].
 * */
@ServerOnly
open class CellTerminal(
    ci: TerminalCreateInfoServer,
    locator: Locator,
    attachment: Vector3d,
    boundingBox: OrientedBoundingBox3d,
    private val cellAccessor: () -> Cell,
) : GridTerminalServer(ci, locator, attachment, boundingBox) {
    override val storage = Storage()

    var stagingCell: GridConnectionCell? = null

    val cell: Cell
        get() = cellAccessor.invoke()

    inner class Storage : TerminalConnectionStorage {
        override fun hasConnectionWith(remoteEndpointID: UUID, remoteTerminal: Int) = cell
            .requireNode<GridNode>()
            .hasAnyConnectionWith(terminalID, remoteEndpointID, remoteTerminal)
    }
}
