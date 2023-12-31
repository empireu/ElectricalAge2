package org.eln2.mc.common.specs.foundation

import com.mojang.blaze3d.vertex.VertexConsumer
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
import org.ageseries.libage.data.Locator
import org.ageseries.libage.mathematics.geometry.OrientedBoundingBox3d
import org.ageseries.libage.mathematics.geometry.Ray3d
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.BasicSpecRenderer
import org.eln2.mc.client.render.foundation.RGBAFloat
import org.eln2.mc.client.render.foundation.eln2SubmitOBBAtLevelStage
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.content.*
import org.eln2.mc.extensions.*
import org.eln2.mc.requireIsOnServerThread
import java.util.*
import java.util.function.Supplier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Holds a reference to a [MicroGridTerminal].
 * @param terminal The terminal to handle.
 * */
abstract class MicroGridTerminalHandle(val terminal: MicroGridTerminal) {
    /**
     * Used to store all information necessary to get the [MicroGridTerminal] from the world at a later time.
     * This is how [MicroGridTerminal] edges are meant to be stored long-term.
     * A reference to [MicroGridTerminal] should not be stored directly; usually, the [MicroGridTerminal] will reference its game object and will keep it in-scope, even though it shouldn't (e.g. the chunk gets unloaded).
     * */
    abstract fun toNbt() : CompoundTag

    enum class Type {
        Spec
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

                    SpecHandle(
                        terminal,
                        part,
                        spec
                    )
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

            if(part is SpecContainerPart) {
                val spec = part.pickSpec(pPlayer)?.second as? MicroGridSpec<*>
                    ?: return null

                val terminal = spec.pickTerminal(pPlayer)
                    ?: return null

                return SpecHandle(
                    terminal,
                    part,
                    spec
                )
            }
            else {
                return null
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
     * Removs the connection to the remote end point with id [remoteEndpointID].
     * This will be called once per remote, and only for remotes that are known to be connected to this terminal.
     * It is recommended to validate that with an exception.
     * */
    fun removeConnection(remoteEndpointID: UUID)

    /**
     * Called when a connection is being made/destroyed between two terminals. Called after the edge sets of both terminals have been updated.
     * */
    fun stagePrimary()

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
    override fun stagePrimary() = error("Cannot stage primary on fake terminal")
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
class GridTerminalSystem<Terminal>(val level: Level) where Terminal : MicroGridTerminal, Terminal : Any {
    private val factories = HashMap<Int, TerminalFactories<Terminal>>()
    private val instances = HashMap<Int, Terminal>()
    private var currentID = 0
    private var isInitialized = false
    private var isDestroyed = false

    private fun create(factories: TerminalFactories<Terminal>, uuid: UUID, tag: CompoundTag?) = if(level.isClientSide) {
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
            instances.putUnique(id, terminal)
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

            instances.putUnique(terminalID, terminal) {
                "Saved data had a duplicate terminal"
            }

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

        instances.values.forEach {
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

        instances.forEach { (terminalID, terminal) ->
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
    fun <T : Terminal> defineTerminal(factory: TerminalFactories<T>): Supplier<T> {
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
            return@Supplier checkNotNull(instances[id]) {
                "Expected terminal instance for $id"
            } as T
        }
    }

    /**
     * Defines a terminal for deferred creation.
     * @param factory The factory to create the terminal. This is used on both server and client. You may just check [Level.isClientSide] instead of using two different functions.
     * @return A supplier that will return the instance of the terminal. Only valid to use after [initializeFresh] or [initializeSaved] was called.
     * */
    fun <T : Terminal> defineTerminal(factory: (level: Level, uuid: UUID, tag: CompoundTag?) -> T) : Supplier<T> = defineTerminal(
        TerminalFactories(factory, factory)
    )

    /**
     * Gets the terminal [player] is looking at.
     * @return The terminal intersected by [player]'s view or null, if no terminals intersect.
     * */
    fun pick(player: LivingEntity) : Terminal? {
        check(isInitialized) {
            "Tried to pick micro grid terminal before initialized"
        }

        val ray = player.getViewRay()

        return instances.values.mapNotNull {
            val intersection = it.intersect(ray)
                ?: return@mapNotNull null

            it to intersection
        }.minByOrNull { (_, intersection) ->
            intersection distanceToSqr ray.origin
        }?.first
    }

    fun getByEndpointID(endpointID: UUID) = instances.values.firstOrNull { it.gridEndpointInfo.id == endpointID }

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
 * */
abstract class StandaloneBoxTerminal(
    val level: Level,
    val locator: Locator,
    val boundingBox: OrientedBoundingBox3d,
    uuid: UUID,
    tag: CompoundTag?,
) : MicroGridTerminal {
    override val gridEndpointInfo = GridEndpointInfo(uuid, boundingBox.center, locator)
    val remoteEndPoints = HashMap<MicroGridRemote, GridConnectionDescription>()

    /**
     * Deserializes the connections from the tag, if on the server side.
     * */
    init {
        if(tag != null && !level.isClientSide) {
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
                    level as ServerLevel,
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
    }

    /**
     * Destroys this terminal. Valid to call from both sides.
     * On the server, it restores the handles to the remote terminals and removes the edge, and also destroys the edges from [GridConnectionManagerServer].
     * */
    override fun destroy() {
        if(!level.isClientSide) {
            MicroGridOperations.destroyTerminal(
                level as ServerLevel,
                this,
                remoteEndPoints.keys.toList()
            )
        }
    }

    override fun stagePrimary() {}

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

abstract class MicroGridSpec<Renderer : SpecRenderer>(ci: SpecCreateInfo) : Spec<Renderer>(ci), MicroGridNode {
    val locator = placement.createLocator()

    /**
     * Gets the grid terminal system of this spec. Its lifetime is managed by the spec.
     * */
    protected val gridTerminalSystem = GridTerminalSystem<MicroGridTerminal>(placement.level)

    /**
     * Defines a simple bounding box terminal.
     * Uses [SpecFakeBoxTerminal] on the client and [SpecStandaloneBoxTerminal] on the server.
     * */
    protected fun defineBoxTerminal(box3d: OrientedBoundingBox3d, highlightColor : RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f)) = gridTerminalSystem.defineTerminal { level, uuid, compoundTag ->
        if(level.isClientSide) {
            SpecFakeBoxTerminal(box3d, uuid, compoundTag, highlightColor)
        }
        else {
            SpecStandaloneBoxTerminal(box3d, uuid, compoundTag)
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

    override fun getServerSaveTag(): CompoundTag {
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

    /**
     * [StandaloneBoxTerminal] with infrastructure to save data when the connections change (with [setSaveDirty]).
     * */
    protected open inner class SpecStandaloneBoxTerminal(
        obb: OrientedBoundingBox3d,
        uuid: UUID,
        tag: CompoundTag?,
    ) : StandaloneBoxTerminal(placement.level, locator, obb, uuid, tag) {
        override fun addConnection(info: MicroGridConnectionDescription) {
            super.addConnection(info)
            setSaveDirty()
        }

        override fun removeConnection(remoteEndpointID: UUID) {
            super.removeConnection(remoteEndpointID)
            setSaveDirty()
        }
    }

    @ClientOnly
    protected open inner class SpecFakeBoxTerminal(
        obb: OrientedBoundingBox3d,
        uuid: UUID,
        tag: CompoundTag?,
        highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
    ) : FakeBoxTerminal(placement.level, locator, obb, uuid, tag, highlightColor)
}

class TestMicroGridSpec(ci: SpecCreateInfo) : MicroGridSpec<BasicSpecRenderer>(ci) {
    private val terminal = defineBoxTerminal(
        0.05, 0.0, 0.0,
        0.15, 0.2, 0.15
    )

    override fun createRenderer(): BasicSpecRenderer {
        return BasicSpecRenderer(this, PartialModels.ELECTRICAL_WIRE_HUB)
    }
}

object MicroGridOperations {
    /**
     * Call when the [terminal] is destroyed.
     * @param terminal The terminal being destroyed.
     * @param remotes All connected terminals.
     *
     * The remote terminals are restored and their connections are updated to remove [terminal].
     * Then, [terminal] is [MicroGridTerminal.stagePrimary] is called, if [stage] is true.
     * Finally, [GridConnectionManagerServer] is notified of the removed end point.
     * */
    @ServerOnly
    fun destroyTerminal(level: ServerLevel, terminal: MicroGridTerminal, remotes: List<MicroGridRemote>, stage: Boolean = true) {
        requireIsOnServerThread {
            "destroyTerminal is illegal to call on non-server"
        }

        val handle = remotes.mapNotNull {
            val result = MicroGridTerminalHandle.restoreImmediate(level, it.snapshot)

            if(result == null) {
                LOG.fatal("Failed to restore end point")
            }

            result
        }

        handle.forEach {
            it.terminal.removeConnection(terminal.gridEndpointInfo.id)
        }

        if(stage) {
            terminal.stagePrimary()
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

        val targetTerminalInfo = MicroGridTerminalHandle.pick(pLevel, pPlayer)
            ?: return fail("No valid terminal selected!")

        val targetTerminal = targetTerminalInfo.terminal

        if (actualStack.tag != null && !actualStack.tag!!.isEmpty) {
            val tag = actualStack.tag!!

            val remoteTerminalInfo = MicroGridTerminalHandle.restoreImmediate(pLevel, tag)
                ?: return fail("The remote terminal has disappeared!")

            val remoteTerminal = remoteTerminalInfo.terminal

            if(remoteTerminal === targetTerminal) {
                return fail("Can't connect a terminal with itself!")
            }

            if(targetTerminal.hasConnectionWith(remoteTerminal.gridEndpointInfo.id)) {
                check(remoteTerminal.hasConnectionWith(targetTerminal.gridEndpointInfo.id)) {
                    "Invalid reciprocal state - missing remote"
                }

                return fail("Can't do that!")
            }
            else {
                check(!remoteTerminal.hasConnectionWith(targetTerminal.gridEndpointInfo.id)) {
                    "Invalid reciprocal state - unexpected remote"
                }
            }

            val pair = GridConnectionPair.create(
                targetTerminal.gridEndpointInfo,
                remoteTerminal.gridEndpointInfo
            )

            val gridCatenary = GridConnectionManagerServer.createGridCatenary(pair, material)

            val connectionInfo = GridConnectionDescription(
                gridCatenary.material,
                gridCatenary.resistance
            )

            targetTerminal.addConnection(MicroGridConnectionDescription(remoteTerminalInfo, connectionInfo))
            remoteTerminal.addConnection(MicroGridConnectionDescription(targetTerminalInfo, connectionInfo))
            targetTerminal.stagePrimary()

            GridConnectionManagerServer.createPair(pLevel, pair, gridCatenary)

            return success("Connected successfully!")
        }

        actualStack.tag = targetTerminalInfo.toNbt()

        tell("Start recorded!")

        return InteractionResultHolder.success(actualStack)
    }
}
