package org.eln2.mc.common.specs.foundation

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraftforge.client.event.RenderHighlightEvent
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.geometry.OrientedBoundingBox3d
import org.ageseries.libage.mathematics.geometry.Ray3d
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.utils.addUnique
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.*
import org.eln2.mc.client.render.foundation.RGBAFloat
import org.eln2.mc.client.render.foundation.eln2SubmitOBBAtLevelStage
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.PartRenderer
import org.eln2.mc.extensions.*
import java.util.*
import java.util.function.Supplier
import kotlin.collections.HashMap
import kotlin.math.*

/**
 * Holds a reference to a [MicroGridTerminal].
 * @param terminal The terminal to handle.
 * */
abstract class MicroGridTerminalHandle(val terminal: MicroGridTerminal) {
    /**
     * Checks if the container handling this terminal is the same as the container handling [other].
     * */
    fun isSameContainer(other: MicroGridTerminalHandle) : Boolean {
        if(this is SpecHandle && other is SpecHandle) {
            return this.spec === other.spec
        }

        return false
    }

    /**
     * Used to store all information necessary to get the [MicroGridTerminal] from the world at a later time.
     * This is how [MicroGridTerminal] edges are meant to be stored long-term.
     * A reference to [MicroGridTerminal] should not be stored directly; usually, the [MicroGridTerminal] will reference its game object and will keep it in-scope, even though it shouldn't (e.g. the chunk gets unloaded).
     * */
    abstract fun toNbt() : CompoundTag

    enum class Type {
        Spec,
        Part
    }

    /**
     * Handle for a [MicroGridNode] implemented by a [MicroGridSpec].
     * */
    class SpecHandle(terminal: MicroGridTerminal, val part: SpecContainerPart, val spec: MicroGridSpec<*>) : MicroGridTerminalHandle(terminal) {
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

    class PartHandle(terminal: MicroGridTerminal, val part: MicroGridCellPart<*, *>) : MicroGridTerminalHandle(terminal) {
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
        fun restoreImmediate(pLevel: ServerLevel, tag: CompoundTag) : MicroGridTerminalHandle? {
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

                    val spec = part.getSpecByPlacementID(tag.getInt(PLACEMENT_ID)) as? MicroGridSpec<*>
                        ?: return null

                    val terminal = spec.getTerminalByEndpointID(tag.getUUID(ENDPOINT_ID))
                        ?: return null

                    SpecHandle(terminal, part, spec)
                }

                Type.Part -> {
                    val pos = tag.getBlockPos(BLOCK_POS)

                    val multipart = pLevel.getBlockEntity(pos) as? MultipartBlockEntity
                        ?: return null

                    val part = multipart.getPart(tag.getDirection(FACE)) as? MicroGridCellPart<*, *>
                        ?: return null

                    if(part.containerID != tag.getUUID(CONTAINER_ID)) {
                        return null
                    }

                    val terminal = part.getTerminalByEndpointID(tag.getUUID(ENDPOINT_ID))
                        ?: return null

                    PartHandle(terminal, part,)
                }
            }
        }

        private fun getPlayerPOVHitResult(
            pLevel: Level,
            pPlayer: Player,
            pFluidMode: ClipContext.Fluid,
        ): BlockHitResult {
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
            return pLevel.clip(ClipContext(vec3, vec31, ClipContext.Block.OUTLINE, pFluidMode, pPlayer))
        }

        /**
         * Picks a micro grid terminal from the world.
         * */
        fun pick(pLevel: Level, pPlayer: Player) : MicroGridTerminalHandle? {
            val hit = getPlayerPOVHitResult(pLevel, pPlayer, ClipContext.Fluid.SOURCE_ONLY)

            if (hit.type != HitResult.Type.BLOCK) {
                return null
            }

            val targetBlockEntity = pLevel.getBlockEntity(hit.blockPos)
                ?: return null

            if(targetBlockEntity !is MultipartBlockEntity) {
                return null
            }

            val part = targetBlockEntity.pickPart(pPlayer)
                ?: return null

            when (part) {
                is SpecContainerPart -> {
                    val spec = part.pickSpec(pPlayer)?.second as? MicroGridSpec<*>
                        ?: return null

                    val terminal = spec.pickTerminal(pPlayer)
                        ?: return null

                    return SpecHandle(terminal, part, spec)
                }

                is MicroGridCellPart<*, *> -> {
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
 * Holds information about a remote [MicroGridTerminal].
 * @param endpointInfo The [MicroGridTerminal.gridEndpointInfo] of the remote terminal.
 * @param snapshot A capture of the remote terminal, that can be restored to fetch the terminal from the world.
 * */
data class MicroGridRemote(val endpointInfo: GridEndpointInfo, val snapshot: CompoundTag)

/**
 * Describes the connection and indicates the remote terminal.
 * @param handle A handle to the remote terminal.
 * @param description Description of the connection itself.
 * **This holds a reference to the remote terminal, which may hold the remote game object in scope. Do not store this persistently! Store a capture using [capture].**
 * */
data class MicroGridConnectionDescription(val handle: MicroGridTerminalHandle, val description: GridConnectionDescription)

/**
 * Captures the information encapsulated by a [MicroGridConnectionDescription] into a [MicroGridRemote].
 * This removes the references to the remote terminals.
 * */
fun MicroGridConnectionDescription.capture() = MicroGridRemote(handle.terminal.gridEndpointInfo, handle.toNbt())

/**
 * Terminal (connection point) for *micro-grid connections*.
 * Usually managed by a game object.
 * */
interface MicroGridTerminal {
    /**
     * Gets the endpoint information that describes this terminal.
     * **Must return the same value every time!**
     * */
    val gridEndpointInfo: GridEndpointInfo

    /**
     * Checks if the terminal has a connection with the remote end point with id [endpointID].
     * @return True, if a connection with [endpointID] exists. Otherwise, false.
     * */
    fun hasConnectionWith(endpointID: UUID): Boolean

    /**
     * Adds a connection to the terminal described by [info].
     * This will be called once per remote.
     * It is recommended to validate that with an exception.
     * */
    fun addConnection(info: MicroGridConnectionDescription)

    /**
     * Removes the connection to the remote end point with id [remoteEndpointID].
     * This will be called once per remote, and only for remotes that are known to be connected to this terminal.
     * It is recommended to validate that with an exception.
     * */
    fun removeConnection(remoteEndpointID: UUID)

    /**
     * Called on some primary terminal when a connection is being made between two terminals.
     * Called after the edge sets of both terminals have been updated.
     * */
    fun primaryStageConnect() {}

    /**
     * Called when a terminal is being destroyed.
     * Called after the edge sets of both terminals have been updated.
     * */
    fun primaryStageDestroy() {}

    fun getServerSaveTag(): CompoundTag? = null
    fun getClientSaveTag(): CompoundTag? = null

    /**
     * Called when the game object is destroyed. This should clean up the edges from the remotes and any rendering-related stuff.
     * */
    fun destroy()

    /**
     * Gets the closest point of intersection with [ray].
     * @return The point of intersection or null, if no intersection occurs.
     * */
    fun intersect(ray: Ray3d) : Vector3d?
}

/**
 * Fake [MicroGridTerminal]. Meant to be used on the client exclusively for intersections (highlight).
 * Methods will throw when called (they are never called on the client), except [destroy] and [intersect].
 * The [gridEndpointInfo] must be implemented though.
 * */
@ClientOnly
interface FakeMicroGridTerminal : MicroGridTerminal {
    override fun hasConnectionWith(endpointID: UUID) = error("Cannot check hasConnectionWith on fake terminal")
    override fun addConnection(info: MicroGridConnectionDescription) = error("Cannot add connections on fake terminal")
    override fun removeConnection(remoteEndpointID: UUID) = error("Cannot remote connections on fake terminal")
    override fun primaryStageConnect() = error("Cannot stage connect on fake terminal")
    override fun primaryStageDestroy() = error("Cannot stage destroy on fake terminal")
    override fun getServerSaveTag() = error("Cannot save server data on fake terminal")
    override fun getClientSaveTag() = error("Cannot save client data on fake terminal")
}

/**
 * Container of multiple [MicroGridTerminal]s.
 * Usually implemented by a game object.
 * */
interface MicroGridNode {
    /**
     * Gets the terminal selected by [player].
     * @return The terminal selected by [player] or null, if the player's view does not intersect any terminals.
     * */
    fun pickTerminal(player: LivingEntity) : MicroGridTerminal?

    /**
     * Gets the terminal by its [endpointID].
     * @return The terminal whose [MicroGridTerminal.gridEndpointInfo]'s id is [endpointID] or null, if there is no such terminal.
     * */
    fun getTerminalByEndpointID(endpointID: UUID) : MicroGridTerminal?
}

/**
 * Factory for terminals. Called after the grid terminal system is defined, and the grid terminal system is initialized fresh or from saved data.
 * @param server The factory for the server-side terminal.
 * @param client The factory for the client-side terminal.
 *
 * The two factories may be one and the same. This separation is useful because some terminals may want to store some specific data on the server (e.g. cell-related things) and only show a "fake" terminal on the client for highlighting.
 * */
data class TerminalFactories<Terminal : MicroGridTerminal>(
    val server: (level: ServerLevel, uuid: UUID, serverTag: CompoundTag?) -> Terminal,
    val client: (level: Level, uuid: UUID, clientTag: CompoundTag?) -> Terminal,
)

/**
 * State management for a collection of [MicroGridTerminal]s.
 * Usage is as follows:
 * - [GridTerminalSystem] is initialized in a field
 * - Following that, the desired terminals are deferred for creation using the [defineTerminal] methods. The results are also stored in fields.
 * - Terminals are finally created freshly (e.g. when the game object is first placed) or loaded (from previously saved data).
 * - After that, no more terminals may be created
 * */
class GridTerminalSystem(val level: Level) {
    private val factories = HashMap<Int, TerminalFactories<MicroGridTerminal>>()
    private val instances = MutableMapPairBiMap<Int, MicroGridTerminal>()
    private var currentID = 0
    private var isInitialized = false
    private var isDestroyed = false

    /**
     * Gets the instances. Valid to call after initialization.
     * */
    fun getInstances(): Map<Int, MicroGridTerminal> {
        check(isInitialized) {
            "Tried to get instances before initialized"
        }

        check(!isDestroyed) {
            "Tried to get instances after initialized"
        }

        return instances.forward
    }

    inline fun<reified T> instancesOfType() : Map<Int, T> {
        val results = HashMap<Int, T>()

        getInstances().forEach { (id, instance) ->
            if(instance is T) {
                results.putUnique(id, instance)
            }
        }

        return results
    }

    inline fun<reified T> terminalsOfType() : List<T> = getInstances().values.mapNotNull { it as? T }

    private fun create(factories: TerminalFactories<*>, uuid: UUID, tag: CompoundTag?) = if(level.isClientSide) {
        factories.client(level, uuid, tag)
    }
    else {
        factories.server(level as ServerLevel, uuid, tag)
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
            val terminal = create(factories, UUID.randomUUID(), null)
            instances.add(id, terminal)
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

            val terminal = create(factories, endpointID, customTag)

            instances.add(terminalID, terminal)

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

        instances.forward.values.forEach {
            it.destroy()
        }
    }

    enum class SaveType {
        Server,
        Client
    }

    /**
     * Saves the state of the terminals to a [CompoundTag]. This is meant to be loaded with [initializeSaved] at a later time.
     * @param saveType The destination of the data. [SaveType.Server] saves using [MicroGridTerminal.getServerSaveTag] and [SaveType.Client] saves using [MicroGridTerminal.getClientSaveTag].
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

        instances.forward.forEach { (terminalID, terminal) ->
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
    fun <T : MicroGridTerminal> defineTerminal(factory: TerminalFactories<T>): Supplier<T> {
        check(!isInitialized) {
            "Tried to define terminal after initialized"
        }

        check(!isDestroyed) {
            "Tried to define terminals after destroyed"
        }

        val id = currentID++

        factories.putUnique(id, TerminalFactories(
            { level, uuid, tag ->
                factory.server(level, uuid, tag)
            },
            { level, uuid, tag ->
                factory.client(level, uuid, tag)
            }
        ))

        return Supplier {
            check(isInitialized) {
                "Tried to get terminal before initialized"
            }

            @Suppress("UNCHECKED_CAST")
            return@Supplier checkNotNull(instances.forward[id]) {
                "Expected terminal instance for $id"
            } as T
        }
    }

    /**
     * Defines a terminal for deferred creation.
     * @param factory The factory to create the terminal. This is used on both server and client. You may just check [Level.isClientSide] instead of using two different functions.
     * @return A supplier that will return the instance of the terminal. Only valid to use after [initializeFresh] or [initializeSaved] was called.
     * */
    fun <T : MicroGridTerminal> defineTerminal(factory: (level: Level, uuid: UUID, tag: CompoundTag?) -> T) : Supplier<T> = defineTerminal(
        TerminalFactories(factory, factory)
    )

    /**
     * Gets the terminal [player] is looking at.
     * @return The terminal intersected by [player]'s view or null, if no terminals intersect.
     * */
    fun pick(player: LivingEntity) : MicroGridTerminal? {
        check(isInitialized) {
            "Tried to pick micro grid terminal before initialized"
        }

        val ray = player.getViewRay()

        return instances.forward.values.mapNotNull {
            val intersection = it.intersect(ray)
                ?: return@mapNotNull null

            it to intersection
        }.minByOrNull { (_, intersection) ->
            intersection distanceToSqr ray.origin
        }?.first
    }

    fun getByEndpointID(endpointID: UUID) = instances.forward.values.firstOrNull { it.gridEndpointInfo.id == endpointID }

    fun idOf(terminal: MicroGridTerminal) : Int {
        check(isInitialized) {
            "Cannot get ID of terminal before initialized"
        }

        check(!isDestroyed) {
            "Cannot get ID of terminal after destroyed"
        }

        return checkNotNull(instances.backward[terminal]) {
            "Did not have $terminal"
        }
    }

    companion object {
        private const val TERMINALS = "terminals"
        private const val ENDPOINT_ID = "endpointID"
        private const val TERMINAL_ID = "id"
        private const val TAG = "tag"
    }
}

/**
 * Implemented by a [FakeMicroGridTerminal] that should be highlighted on the client.
 * */
@ClientOnly
interface MicroGridBoxTerminalHighlight<Self> where Self : MicroGridBoxTerminalHighlight<Self>, Self : FakeMicroGridTerminal {
    /**
     * If not null, the terminal will be highlighted this frame when the player is looking at it, with the [highlightColor].
     * */
    val highlightColor: RGBAFloat?

    /**
     * Gets the bounding box in the world frame.
     * */
    val boundingBox: OrientedBoundingBox3d
}

@ClientOnly
object MicroGridHighlightRenderer {
    fun render(event: RenderHighlightEvent.Block) {
        val level = Minecraft.getInstance().level
            ?: return

        val player = Minecraft.getInstance().player
            ?: return

        val handle = MicroGridTerminalHandle.pick(level, player)
            ?: return

        if(handle.terminal !is MicroGridBoxTerminalHighlight<*>) {
            return
        }

        val color = handle.terminal.highlightColor
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
 * [MicroGridTerminal] that is responsible for managing its set of edges, and its volume is defined by an oriented bounding box.
 * Also handles creating the grid connection for rendering, with [GridConnectionManagerServer].
 * Meant to be used by a game object (in a [Level]).
 * @param level The level. Can be client or server.
 * @param locator A locator that describes the configuration of the game object, for [gridEndpointInfo].
 * @param boundingBox The bounding box, in the world's frame.
 * @param uuid The endpoint ID (from the [TerminalFactories]), for [gridEndpointInfo].
 * @param tag The load tag (from the [TerminalFactories]).
 * @param changedNotifier Function to call when the connections change. This is useful if you need save your things.
 * */
@ServerOnly
open class StandaloneBoxTerminal(
    val level: ServerLevel,
    val locator: Locator,
    val boundingBox: OrientedBoundingBox3d,
    uuid: UUID,
    tag: CompoundTag?,
    private val changedNotifier: (() -> Unit)?
) : MicroGridTerminal {
    override val gridEndpointInfo = GridEndpointInfo(uuid, boundingBox.center, locator)
    val remoteEndPoints = HashMap<MicroGridRemote, GridConnectionDescription>()

    /**
     * Deserializes the connections from the tag, if on the server side.
     * */
    init {
        if(tag != null) {
            val list = tag.getListTag(ENDPOINTS)

            list.forEachCompound { endpointCompound ->
                val info = GridEndpointInfo.fromNbt(endpointCompound.getCompound(INFO))
                val snapshot = endpointCompound.getCompound(SNAPSHOT)
                val material = GridMaterials.getMaterial(endpointCompound.getResourceLocation(MATERIAL))
                val resistance = endpointCompound.getDouble(RESISTANCE)

                val endpoint = MicroGridRemote(info, snapshot)
                val description = GridConnectionDescription(material, resistance)

                remoteEndPoints.putUnique(endpoint, description)

                MicroGridOperations.initializeEdge(
                    level,
                    this,
                    endpoint.endpointInfo,
                    description
                )
            }
        }
    }

    /**
     * Saved all edges to a compound tag.
     * */
    @ServerOnly
    override fun getServerSaveTag(): CompoundTag {
        requireIsOnServerThread {
            "Cannot get server save tag on non-server"
        }

        val tag = CompoundTag()
        val list = ListTag()

        remoteEndPoints.forEach { (endpoint, description) ->
            val endpointCompound = CompoundTag()

            endpointCompound.put(INFO, endpoint.endpointInfo.toNbt())
            endpointCompound.put(SNAPSHOT, endpoint.snapshot)
            endpointCompound.putResourceLocation(MATERIAL, description.material.id)
            endpointCompound.putDouble(RESISTANCE, description.resistance)

            list.add(endpointCompound)
        }

        tag.put(ENDPOINTS, list)

        return tag
    }

    override fun hasConnectionWith(endpointID: UUID) = remoteEndPoints.keys.any {
        it.endpointInfo.id == endpointID
    }

    /**
     * Adds a connection by capturing the state of the handle (does not hold a reference to the remote game object).
     * Only legal on the server.
     * Throws an exception if a connection with the same ID exists.
     * */
    @ServerOnly
    override fun addConnection(info: MicroGridConnectionDescription) {
        requireIsOnServerThread {
            "Cannot add connection on non-server"
        }

        requireNotNull(!remoteEndPoints.keys.any { it.endpointInfo.id == info.handle.terminal.gridEndpointInfo.id }) {
            "Duplicate connection with id ${info.handle.terminal.gridEndpointInfo.id}"
        }

        remoteEndPoints.putUnique(info.capture(), info.description)

        changedNotifier?.invoke()
    }

    /**
     * Removes a connection.
     * Only legal on the server.
     * Throws an exception if no connection with [remoteEndpointID] exists.
     * */
    @ServerOnly
    override fun removeConnection(remoteEndpointID: UUID) {
        requireIsOnServerThread {
            "Cannot remove connection on non-server"
        }

        val key = requireNotNull(remoteEndPoints.keys.firstOrNull { it.endpointInfo.id == remoteEndpointID }) {
            "Did not have expected connection"
        }

        remoteEndPoints.remove(key)

        changedNotifier?.invoke()
    }

    /**
     * Destroys this terminal. Valid to call from both sides.
     * On the server, it restores the handles to the remote terminals and removes the edge, and also destroys the edges from [GridConnectionManagerServer].
     * */
    override fun destroy() {
        val remotes = remoteEndPoints.keys.toList()
        remoteEndPoints.clear()
        MicroGridOperations.destroyTerminal(
            level,
            this,
            remotes
        )
    }

    /**
     * Gets the intersection distance with the [boundingBox].
     * */
    override fun intersect(ray: Ray3d): Vector3d? {
        val intersection = (ray intersectionWith boundingBox)
            ?: return null

        return ray.evaluate(intersection.entry)
    }

    companion object {
        private const val ENDPOINTS = "endpoints"
        private const val INFO = "info"
        private const val SNAPSHOT = "snapshot"
        private const val MATERIAL = "material"
        private const val RESISTANCE = "resistance"
    }
}

/**
 * Fake version of [StandaloneBoxTerminal]. It is illegal to call any of the saving or connection methods.
 * Only legal on the client.
 * */
@ClientOnly
open class FakeBoxTerminal(
    val level: Level,
    val locator: Locator,
    final override val boundingBox: OrientedBoundingBox3d,
    uuid: UUID,
    tag: CompoundTag?,
    override var highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
) : FakeMicroGridTerminal, MicroGridBoxTerminalHighlight<FakeBoxTerminal> {
    override val gridEndpointInfo = GridEndpointInfo(uuid, boundingBox.center, locator)

    init {
        require(level.isClientSide) {
            "Fake box terminal is illegal on server!"
        }

        if(tag != null) {
            require(tag.isEmpty) {
                "Fake box terminal shouldn't have any data!"
            }
        }
    }

    override fun intersect(ray: Ray3d): Vector3d? {
        val intersection = (ray intersectionWith boundingBox)
            ?: return null

        return ray.evaluate(intersection.entry)
    }

    override fun destroy() { }
}

@ServerOnly
open class StandaloneCellBoxTerminal(
    level: ServerLevel,
    locator: Locator,
    obb: OrientedBoundingBox3d,
    uuid: UUID,
    tag: CompoundTag?,
    changedNotifier: (() -> Unit)?,
    val gridTerminalSystem: GridTerminalSystem,
    val cellAccessor: () -> MicroGridCell
) : StandaloneBoxTerminal(level, locator, obb, uuid, tag, changedNotifier), MicroGridCellTerminal {
    override fun addConnection(info: MicroGridConnectionDescription) {
        super.addConnection(info)
        cell.addTerminalConnection(info, gridTerminalSystem.idOf(this))
    }

    override fun removeConnection(remoteEndpointID: UUID) {
        super.removeConnection(remoteEndpointID)
        cell.removeTerminalConnection(remoteEndpointID, gridTerminalSystem.idOf(this))
    }

    override val cell: MicroGridCell
        get() = cellAccessor.invoke()

    // This is a bit inefficient. Is it an issue?
    override val remoteCells: List<Cell>
        get() = remoteEndPoints.keys.mapNotNull {
            val handle = MicroGridTerminalHandle.restoreImmediate(level, it.snapshot)

            if(handle == null) {
                LOG.fatal("FAILED TO RESTORE HANDLE FOR CELL TERMINAL!")
            }

            (handle?.terminal as? MicroGridCellTerminal)?.cell
        }

    override fun primaryStageConnect() {
        val container = CellAndContainerHandle.captureInScope(cell).container
        CellConnections.retopologize(cell, container)
    }
}

abstract class MicroGridSpec<Renderer : SpecRenderer>(ci: SpecCreateInfo) : Spec<Renderer>(ci), MicroGridNode {
    val locator = placement.createLocator()

    /**
     * Gets the grid terminal system of this spec. Its lifetime is managed by the spec.
     * */
    protected val gridTerminalSystem = GridTerminalSystem(placement.level)

    /**
     * Defines a simple bounding box terminal.
     * Uses [FakeBoxTerminal] on the client and [StandaloneBoxTerminal] on the server.
     * */
    protected fun defineBoxTerminal(box3d: OrientedBoundingBox3d, highlightColor : RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f)) = gridTerminalSystem.defineTerminal { level, uuid, compoundTag ->
        if(level.isClientSide) {
            FakeBoxTerminal(placement.level, locator, box3d, uuid, compoundTag, highlightColor)
        }
        else {
            StandaloneBoxTerminal(placement.level as ServerLevel, locator, box3d, uuid, compoundTag) {
                this.setSaveDirty()
            }
        }
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
        placement.part.placement.positiveX.vector3d * x +
        placement.part.placement.positiveY.vector3d * y +
        placement.part.placement.positiveZ.vector3d * z,
        orientation * placement.orientation,
        Vector3d(sizeX, sizeY, sizeZ),
        placement.part.placement.facing,
        placement.face
    )

    protected fun defineBoxTerminal(
        x: Double, y: Double, z: Double,
        sizeX: Double, sizeY: Double, sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
        highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
    ) = defineBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation), highlightColor)

    @ServerOnly
    override fun onPlaced() {
        // called when placed, which initializes the terminals server-side
        // then, the spec gets sent to the client and they deserialize
        gridTerminalSystem.initializeFresh()
    }

    override fun getServerSaveTag(): CompoundTag? {
        return gridTerminalSystem.save(GridTerminalSystem.SaveType.Server)
    }

    override fun getClientSaveTag(): CompoundTag? {
        // This is the only way clients get initialized
        return gridTerminalSystem.save(GridTerminalSystem.SaveType.Client)
    }

    override fun loadServerSaveTag(tag: CompoundTag) {
        gridTerminalSystem.initializeSaved(tag)
    }

    override fun loadClientSaveTag(tag: CompoundTag) {
        gridTerminalSystem.initializeSaved(tag)
    }

    override fun pickTerminal(player: LivingEntity) = gridTerminalSystem.pick(player)

    override fun getTerminalByEndpointID(endpointID: UUID) = gridTerminalSystem.getByEndpointID(endpointID)

    override fun onBroken() {
        gridTerminalSystem.destroy()
    }
}


/**
 * Represents a part that has a cell.
 * */
interface SpecWithCell<C : Cell> {
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

    fun neighborScan() : List<CellAndContainerHandle>
}

/**
 * Terminal for connecting cells.
 * */
@ServerOnly
interface MicroGridCellTerminal {
    /**
     * Gets the cell exposed by this terminal.
     * */
    val cell: Cell

    /**
     * Gets the remote cells connected to this terminal.
     * */
    val remoteCells: List<Cell>
}

/**
 * Cell meant to be used with the micro-grid system.
 * This just does some extra bookkeeping to track which terminals bring which connections.
 * This sort of information is stored in the cell and cannot really be gotten on loading because of different lifecycles of the world and the simulation.
 * */
abstract class MicroGridCell(ci: CellCreateInfo) : Cell(ci) {
    data class TerminalConnection(
        val terminalID: Int,
        val resistance: Double,
        val material: GridMaterial
    )

    private val connectionsByRemoteLocator = MutableSetMapMultiMap<Locator, TerminalConnection>()
    private val remoteEndpointAndRemoteLocator = MutableMapPairBiMap<Locator, UUID>()

    /**
     * Checks if this cell has a microgrid connection to the remote with the specified [endpointID].
     * @return True, if a microgrid connection with [remoteEndpointID] exists. Otherwise, false (may still be connected trough non-microgrid).
     * */
    fun hasRemote(remoteEndpointID: UUID) = remoteEndpointAndRemoteLocator.backward.contains(remoteEndpointID)

    /**
     * Filters connections using the default rule *and* checking if the [remote] is connected through the microgrid (rejects non-microgrid connections).
     * */
    override fun cellConnectionPredicate(remote: Cell): Boolean {
        return super.cellConnectionPredicate(remote) && connectionsByRemoteLocator.contains(remote.locator)
    }

    override fun saveCellData(): CompoundTag {
        val remoteInfoList = ListTag()

        connectionsByRemoteLocator.map.forEach { (remoteLocator, connections) ->
            if(connections.isEmpty()) {
                return@forEach // Grissess please :(
            }

            val remoteInfoCompound = CompoundTag()

            remoteInfoCompound.putLocatorSet(REMOTE_LOCATOR, remoteLocator)

            remoteInfoCompound.putUUID(REMOTE_ENDPOINT_ID, checkNotNull(remoteEndpointAndRemoteLocator.forward[remoteLocator]) {
                "Expected remote ID"
            })

            val connectionList = ListTag()

            connections.forEach { connection ->
                val connectionCompound = CompoundTag()

                connectionCompound.putInt(TERMINAL_ID, connection.terminalID)
                connectionCompound.putDouble(RESISTANCE, connection.resistance)
                connectionCompound.putResourceLocation(MATERIAL, connection.material.id)

                connectionList.add(connectionCompound)
            }

            remoteInfoCompound.put(CONNECTION_LIST, connectionList)

            remoteInfoList.add(remoteInfoCompound)
        }

        val tag = CompoundTag()
        tag.put(REMOTE_INFO_LIST, remoteInfoList)

        return tag
    }

    override fun loadCellData(tag: CompoundTag) {
        val remoteInfoList = tag.getListTag(REMOTE_INFO_LIST)

        remoteInfoList.forEachCompound { remoteInfoCompound ->
            val remoteLocator = remoteInfoCompound.getLocatorSet(REMOTE_LOCATOR)
            val remoteEndpointID = remoteInfoCompound.getUUID(REMOTE_ENDPOINT_ID)

            remoteEndpointAndRemoteLocator.add(remoteLocator, remoteEndpointID)

            val connectionList = remoteInfoCompound.getListTag(CONNECTION_LIST)

            connectionList.forEachCompound { connectionCompound ->
                val terminalId = connectionCompound.getInt(TERMINAL_ID)
                val resistance = connectionCompound.getDouble(RESISTANCE)
                val material = GridMaterials.getMaterial(connectionCompound.getResourceLocation(MATERIAL))

                connectionsByRemoteLocator[remoteLocator].addUnique(
                    TerminalConnection(
                        terminalId,
                        resistance,
                        material
                    )
                )

                check(connections.any { it.locator == remoteLocator }) {
                    "Saved data had microgrid info for $remoteLocator which does not exist"
                }
            }
        }
    }

    fun addTerminalConnection(info: MicroGridConnectionDescription, terminalID: Int) {
        if(info.handle.terminal is MicroGridCellTerminal) {
            val remoteCell = info.handle.terminal.cell

            val connection = TerminalConnection(
                terminalID,
                info.description.resistance,
                info.description.material
            )

            connectionsByRemoteLocator[remoteCell.locator].addUnique(connection) {
                "Duplicate record $connection"
            }

            if(remoteEndpointAndRemoteLocator.forward.contains(remoteCell.locator)) {
                check(remoteEndpointAndRemoteLocator.forward[remoteCell.locator] == info.handle.terminal.gridEndpointInfo.id) {
                    "Remote end point ID changed!"
                }
            }
            else {
                remoteEndpointAndRemoteLocator.add(
                    remoteCell.locator,
                    info.handle.terminal.gridEndpointInfo.id
                )
            }

            setChanged()
        }
    }

    fun removeTerminalConnection(remoteEndpointID: UUID, terminalID: Int) {
        if(hasRemote(remoteEndpointID)) {
            val locator = checkNotNull(remoteEndpointAndRemoteLocator.backward[remoteEndpointID]) {
                "Did not have endpoint id $remoteEndpointID"
            }

            val set = connectionsByRemoteLocator[locator]

            check(set.removeIf { it.terminalID == terminalID }) {
                "Did not have terminal id $remoteEndpointID $terminalID"
            }

            if(set.isEmpty()) {
                remoteEndpointAndRemoteLocator.removeForward(locator)
            }

            setChanged()
        }
    }

    /**
     * Gets the terminals connecting [remote] to this cell.
     * */
    fun terminalsOf(remote: Cell) = connectionsByRemoteLocator[remote.locator]

    /**
     * Gets the terminal connecting [remote] to this cell. There must be exactly one such terminal.
     * */
    fun terminalOf(remote: Cell) : TerminalConnection {
        val terminals = terminalsOf(remote)

        check(terminals.size == 1) {
            "Expected exactly one terminal"
        }

        return terminals.first()
    }

    /**
     * Checks if the [cell] is connected to this one through the micro-grid.
     * @return True, if [cell] is connected trough microgrid. Otherwise, false.
     * */
    fun isMicroGrid(cell: Cell) = remoteEndpointAndRemoteLocator.forward.contains(cell.locator)

    /**
     * Validates to see if all connections are accounted for (if they came through the micro-grid).
     * If you allow non-micro-grid connections, override this and don't allow this validation to happen (it will fail).
     * */
    override fun buildFinished() {
        connections.forEach {
            check(isMicroGrid(it)) {
                "Did not have all remotes after build is finished"
            }

            check(connectionsByRemoteLocator[it.locator].isNotEmpty()) {
                "No terminals were recorded for remote"
            }
        }
    }

    companion object {
        private const val REMOTE_INFO_LIST = "remotes"
        private const val REMOTE_LOCATOR = "remoteLocator"
        private const val REMOTE_ENDPOINT_ID = "remoteTerminalID"
        private const val CONNECTION_LIST = "connections"
        private const val TERMINAL_ID = "terminalId"
        private const val RESISTANCE = "resistance"
        private const val MATERIAL = "material"
    }
}

/**
 * Gets the resistance that an object with a resistor should use when connecting to another object with a resistor via a grid connection.
 * This is half of the resistance of the grid cable. The two resistor will be in series which works out.
 * */
val MicroGridCell.TerminalConnection.resistanceSection get() = 0.5 * this.resistance

abstract class CellMicroGridSpec<C : MicroGridCell, R : SpecRenderer>(
    ci: SpecCreateInfo,
    final override val provider: CellProvider<C>
) : MicroGridSpec<R>(ci), SpecWithCell<C> {
    companion object {
        private const val GRAPH_ID = "GraphID"
        private const val CUSTOM_SIMULATION_DATA = "SimulationData"
    }

    private var cellField: C? = null

    /**
     * The actual cell contained within this spec.
     * It only exists on the server (it is a simulation-only item)
     * */
    @ServerOnly
    final override val cell: C get() = cellField
        ?: error(
            if(placement.level.isClientSide) {
                "TRIED TO ACCESS SPEC CELL ON CLIENT"
            } else {
                "Tried to get spec cell before it is set $this"
            }
        )

    final override val hasCell: Boolean
        get() = cellField != null

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
        super.onPlaced()
        cellField = provider.create(locator, CellEnvironment.evaluate(placement.level, locator))
        cell.container = placement.part
        onCellAcquired()
    }

    /**
     * Notifies the cell that the container has been removed.
     * */
    override fun onUnloaded() {
        super.onUnloaded()
        if (hasCell) {
            requireIsOnServerThread { "onUnloaded spec cell is null $this" }
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
        val tag = super.getServerSaveTag() ?: CompoundTag()

        if (!hasCell) {
            LOG.fatal("Spec saving, but cell not initialized!")
            return tag
        }

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
        super.loadServerSaveTag(tag)

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
        super.onLoaded()

        if (placement.level.isClientSide) {
            return
        }

        cellField = if (!this::loadGraphId.isInitialized) {
            LOG.fatal("Spec cell not initialized!")
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

    override fun neighborScan() = gridTerminalSystem.terminalsOfType<MicroGridCellTerminal>().flatMap {
        it.remoteCells.map { cell ->
            CellAndContainerHandle.captureInScope(cell)
        }
    }

    /**
     * Defines a simple bounding box terminal that connects cells.
     * */
    protected fun defineCellBoxTerminal(box3d: OrientedBoundingBox3d, highlightColor : RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f)) = gridTerminalSystem.defineTerminal { level, uuid, compoundTag ->
        if(level.isClientSide) {
            FakeBoxTerminal(placement.level, locator, box3d, uuid, compoundTag, highlightColor)
        }
        else {
            StandaloneCellBoxTerminal(
                placement.level as ServerLevel,
                locator,
                box3d,
                uuid,
                compoundTag,
                changedNotifier = { this.setSaveDirty() },
                gridTerminalSystem,
                this::cell
            )
        }
    }

    protected fun defineCellBoxTerminal(
        x: Double, y: Double, z: Double,
        sizeX: Double, sizeY: Double, sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
        highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
    ) = defineCellBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation), highlightColor)
}

abstract class MicroGridCellPart<C : MicroGridCell, R : PartRenderer>(
    ci: PartCreateInfo,
    provider: CellProvider<C>
) : CellPart<C, R>(ci, provider), MicroGridNode {
    var containerID = UUID.randomUUID()
        private set

    val gridTerminalSystem = GridTerminalSystem(placement.level)

    override fun onPlaced() {
        super.onPlaced()
        gridTerminalSystem.initializeFresh()
    }

    override fun getServerSaveTag(): CompoundTag? {
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

    /**
     * Defines a simple bounding box terminal that connects cells.
     * */
    protected fun defineCellBoxTerminal(box3d: OrientedBoundingBox3d, highlightColor : RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f)) = gridTerminalSystem.defineTerminal { level, uuid, compoundTag ->
        if(level.isClientSide) {
            FakeBoxTerminal(placement.level, locator, box3d, uuid, compoundTag, highlightColor)
        }
        else {
            StandaloneCellBoxTerminal(
                placement.level as ServerLevel,
                locator,
                box3d,
                uuid,
                compoundTag,
                changedNotifier = { this.setSaveDirty() },
                gridTerminalSystem,
                this::cell
            )
        }
    }

    protected fun defineCellBoxTerminal(
        x: Double, y: Double, z: Double,
        sizeX: Double, sizeY: Double, sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
        highlightColor: RGBAFloat? = RGBAFloat(1f, 0.8f, 0.44f, 0.8f),
    ) = defineCellBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation), highlightColor)

    override fun pickTerminal(player: LivingEntity) = gridTerminalSystem.pick(player)

    override fun getTerminalByEndpointID(endpointID: UUID) = gridTerminalSystem.getByEndpointID(endpointID)

    override fun addExtraConnections(results: MutableSet<CellAndContainerHandle>) {
        gridTerminalSystem.terminalsOfType<MicroGridCellTerminal>().forEach { terminal ->
            terminal.remoteCells.forEach {
                results.add(CellAndContainerHandle.captureInScope(it))
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

object MicroGridOperations {
    /**
     * Call when the [terminal] is destroyed.
     * @param terminal The terminal being destroyed.
     * @param remotes All connected terminals.
     *
     * The remote terminals are restored and their connections are updated to remove [terminal].
     * Then, [terminal] is [MicroGridTerminal.primaryStageConnect] is called, if [stage] is true.
     * Finally, [GridConnectionManagerServer] is notified of the removed end point.
     * */
    @ServerOnly
    fun destroyTerminal(level: ServerLevel, terminal: MicroGridTerminal, remotes: List<MicroGridRemote>, stage: Boolean = true) {
        requireIsOnServerThread {
            "destroyTerminal is illegal to call on non-server"
        }

        val handles = remotes.mapNotNull {
            val result = MicroGridTerminalHandle.restoreImmediate(level, it.snapshot)

            if(result == null) {
                LOG.warn("Failed to restore end point") // not as severe, may happen
            }

            result
        }

        handles.forEach {
            it.terminal.removeConnection(terminal.gridEndpointInfo.id)
        }

        if(stage) {
            terminal.primaryStageDestroy()
        }

        GridConnectionManagerServer.removeEndpointById(level, terminal.gridEndpointInfo.id)
    }

    /**
     * Initializes the connection between [terminal] and [remoteInfo]. Meant to be called when [terminal]'s game object is loaded.
     * This just creates the pair in [GridConnectionManagerServer], if necessary.
     * If [terminal] is a remote to some other terminal that is being removed, the edge will be initialized already by the temrinal being removed, so nothing will be done.
     * */
    @ServerOnly
    fun initializeEdge(level: ServerLevel, terminal: MicroGridTerminal, remoteInfo: GridEndpointInfo, description: GridConnectionDescription) {
        requireIsOnServerThread {
            "initialize is illegal to call on non-server"
        }

        GridConnectionManagerServer.createPairIfAbsent(
            level,
            GridConnectionPair.create(terminal.gridEndpointInfo, remoteInfo),
            description.material
        )
    }
}

open class MicroGridConnectItem(val material: GridMaterial) : Item(Properties()) {
    override fun use(pLevel: Level, pPlayer: Player, pUsedHand: InteractionHand): InteractionResultHolder<ItemStack> {
        val actualStack = pPlayer.getItemInHand(pUsedHand)

        fun tell(text: String) {
            if(text.isNotEmpty()) {
                pPlayer.sendSystemMessage(Component.literal(text))
            }
        }

        fun fail(reason: String = "") : InteractionResultHolder<ItemStack> {
            actualStack.tag = null
            tell(reason)
            return InteractionResultHolder.fail(actualStack)
        }

        fun success(message: String = "") : InteractionResultHolder<ItemStack> {
            actualStack.tag = null
            tell(message)
            return InteractionResultHolder.success(actualStack)
        }

        if (pLevel.isClientSide) {
            return fail()
        }

        pLevel as ServerLevel

        val hTarget = MicroGridTerminalHandle.pick(pLevel, pPlayer)
            ?: return fail("No valid terminal selected!")

        if (actualStack.tag != null && !actualStack.tag!!.isEmpty) {
            val tag = actualStack.tag!!

            val hRemote = MicroGridTerminalHandle.restoreImmediate(pLevel, tag)
                ?: return fail("The remote terminal has disappeared!")

            if (hTarget.isSameContainer(hRemote)) {
                return fail("Can't really do that!")
            }

            if (hRemote.terminal === hTarget.terminal) {
                return fail("Can't connect a terminal with itself!")
            }

            if (hTarget.terminal.hasConnectionWith(hRemote.terminal.gridEndpointInfo.id)) {
                check(hRemote.terminal.hasConnectionWith(hTarget.terminal.gridEndpointInfo.id)) {
                    "Invalid reciprocal state - missing remote"
                }

                return fail("Can't do that!")
            } else {
                check(!hRemote.terminal.hasConnectionWith(hTarget.terminal.gridEndpointInfo.id)) {
                    "Invalid reciprocal state - unexpected remote"
                }
            }

            val pair = GridConnectionPair.create(
                hTarget.terminal.gridEndpointInfo,
                hRemote.terminal.gridEndpointInfo
            )

            val gridCatenary = GridConnectionManagerServer.createGridCatenary(pair, material)

            val connectionInfo = GridConnectionDescription(
                gridCatenary.material,
                gridCatenary.resistance
            )

            hTarget.terminal.addConnection(MicroGridConnectionDescription(hRemote, connectionInfo))
            hRemote.terminal.addConnection(MicroGridConnectionDescription(hTarget, connectionInfo))
            hTarget.terminal.primaryStageConnect()

            GridConnectionManagerServer.createPair(pLevel, pair, gridCatenary)

            return success("Connected successfully!")
        }

        actualStack.tag = hTarget.toNbt()

        tell("Start recorded!")

        return InteractionResultHolder.success(actualStack)
    }
}

/**
 * Implemented by [ElectricalObject]s which make a resistor to represent the connection to another object.
 * [MicroGridHubObject]s must know to half the resistance of their resistor if they are connecting to other [MicroGridHubObject]s objects.
 * */
interface MicroGridHubObject<Self, Cell : MicroGridCell> where Self : MicroGridHubObject<Self, Cell>, Self : ElectricalObject<Cell> {
    private val obj get() = this as ElectricalObject<*>
    private val cell get() = obj.cell as MicroGridCell

    /**
     * Evaluates the resistance to [remote]. You must check if [remote] is connected via the micro-grid beforehand.
     * Currently, only one connection is handled.
     * */
    fun microgridResistanceTo(remote: ElectricalObject<*>) : Double {
        val connection = cell.terminalOf(remote.cell)

        return if(remote is MicroGridHubObject<*, *>) {
            connection.resistanceSection
        }
        else {
            connection.resistance
        }
    }
}
