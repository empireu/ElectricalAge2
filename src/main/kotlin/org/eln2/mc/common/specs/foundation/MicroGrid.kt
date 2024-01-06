package org.eln2.mc.common.specs.foundation

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraftforge.client.event.RenderHighlightEvent
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.geometry.OrientedBoundingBox3d
import org.ageseries.libage.mathematics.geometry.Ray3d
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.*
import org.eln2.mc.client.render.foundation.RGBAFloat
import org.eln2.mc.client.render.foundation.eln2SubmitOBBAtLevelStage
import org.eln2.mc.common.*
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.PartRenderer
import org.eln2.mc.data.Notifier
import org.eln2.mc.extensions.*
import org.eln2.mc.mathematics.toHorizontalFacing
import java.util.*
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.*

/**
 * Holds a reference to a [MicroGridTerminalCommon].
 * @param terminal The terminal to handle.
 * */
abstract class MicroGridTerminalHandle(val terminal: MicroGridTerminalCommon) {
    abstract val gameObject: Any

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
     * Used to store all information necessary to get the [MicroGridTerminalCommon] from the world at a later time.
     * This is how [MicroGridTerminalCommon] edges are meant to be stored long-term.
     * A reference to [MicroGridTerminalCommon] should not be stored directly; usually, the [MicroGridTerminalCommon] will reference its game object and will keep it in-scope, even though it shouldn't (e.g. the chunk gets unloaded).
     * */
    abstract fun toNbt() : CompoundTag

    enum class Type {
        Spec,
        Part,
        BlockEntity
    }

    /**
     * Handle for a [MicroGridNode] implemented by a [MicroGridSpec].
     * */
    class SpecHandle(terminal: MicroGridTerminalCommon, val part: SpecContainerPart, val spec: MicroGridSpec<*>) : MicroGridTerminalHandle(terminal) {
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

    class PartHandle(terminal: MicroGridTerminalCommon, val part: MicroGridCellPart<*, *>) : MicroGridTerminalHandle(terminal) {
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

    class BlockEntityHandle(terminal: MicroGridTerminalCommon, val blockEntity: MicroGridCellBlockEntity<*>) : MicroGridTerminalHandle(terminal) {
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

                    PartHandle(terminal, part)
                }

                Type.BlockEntity -> {
                    val pos = tag.getBlockPos(BLOCK_POS)

                    val blockEntity = pLevel.getBlockEntity(pos) as? MicroGridCellBlockEntity<*>
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
                if(targetBlockEntity !is MicroGridCellBlockEntity<*>) {
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
 * Holds information about a remote [MicroGridTerminalCommon].
 * @param endpointInfo The [MicroGridTerminalCommon.gridEndpointInfo] of the remote terminal.
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
abstract class MicroGridTerminalCommon(
    val gridTerminalSystem: GridTerminalSystem,
    val terminalID: Int,
    uuid: UUID,
    locator: Locator,
    attachment: Vector3d
) {
    val gridEndpointInfo = GridEndpointInfo(uuid, attachment, locator)

    /**
     * Gets the closest point of intersection with [ray].
     * @return The point of intersection or null, if no intersection occurs.
     * */
    abstract fun intersect(ray: Ray3d) : Vector3d?

    open fun destroy() { }
}

abstract class MicroGridTerminalClient(
    ci: TerminalCreateInfoClient,
    locator: Locator,
    attachment: Vector3d
) : MicroGridTerminalCommon(
    ci.gridTerminalSystem,
    ci.terminalID,
    ci.uuid,
    locator,
    attachment
)
{
    init {
        require(ci.level.isClientSide) {
            "Client terminal is illegal on server!"
        }
    }
}

abstract class MicroGridTerminalServer(
    ci: TerminalCreateInfoServer,
    locator: Locator,
    attachment: Vector3d,
) : MicroGridTerminalCommon(
    ci.gridTerminalSystem,
    ci.terminalID,
    ci.uuid,
    locator,
    attachment
) {
    val categories = HashSet<GridMaterialCategory>()

    val connectionsChanged = Notifier()

    val level = ci.level
    val remoteEndPoints = HashMap<MicroGridRemote, GridConnectionDescription>()

    /**
     * Deserializes the connections from the tag, if on the server side.
     * */
    init {
        if(ci.serverTag != null) {
            val list = ci.serverTag.getListTag(ENDPOINTS)

            list.forEachCompound { endpointCompound ->
                val info = GridEndpointInfo.fromNbt(endpointCompound.getCompound(INFO))
                val snapshot = endpointCompound.getCompound(SNAPSHOT)
                val material = GridMaterials.getMaterial(endpointCompound.getResourceLocation(MATERIAL))
                val resistance = endpointCompound.getDouble(RESISTANCE)

                val endpoint = MicroGridRemote(info, snapshot)
                val description = GridConnectionDescription(material, resistance)

                remoteEndPoints.putUnique(endpoint, description)

                initializeEdge(endpoint.endpointInfo, description)
            }
        }
    }

    protected open fun initializeEdge(remoteInfo: GridEndpointInfo, description: GridConnectionDescription) {
        GridConnectionManagerServer.createPairIfAbsent(
            level,
            GridEndpointPair.create(this.gridEndpointInfo, remoteInfo),
            description.material
        )
    }

    fun hasConnectionWith(endpointID: UUID) = remoteEndPoints.keys.any {
        it.endpointInfo.id == endpointID
    }

    private var stagingInfo: MicroGridConnectionDescription? = null

    open fun beginConnectStaging(info: MicroGridConnectionDescription) {
        check(stagingInfo == null)
        this.stagingInfo = info
    }

    /**
     * Adds a connection by capturing the state of the handle (does not hold a reference to the remote game object).
     * Only legal on the server.
     * Throws an exception if a connection with the same ID exists.
     * */
    @ServerOnly
    open fun addConnection() {
        requireIsOnServerThread {
            "Cannot add connection on non-server"
        }

        val info = checkNotNull(stagingInfo)

        requireNotNull(!remoteEndPoints.keys.any { it.endpointInfo.id == info.handle.terminal.gridEndpointInfo.id }) {
            "Duplicate connection with id ${info.handle.terminal.gridEndpointInfo.id}"
        }

        remoteEndPoints.putUnique(info.capture(), info.description)
    }

    open fun endConnectStaging() {
        checkNotNull(this.stagingInfo)
        this.stagingInfo = null
        connectionsChanged.run()
    }

    /**
     * Removes a connection.
     * Only legal on the server.
     * Throws an exception if no connection with [remoteEndpointID] exists.
     * */
    @ServerOnly
    fun removeConnection(remoteEndpointID: UUID) {
        requireIsOnServerThread {
            "Cannot remove connection on non-server"
        }

        val key = requireNotNull(remoteEndPoints.keys.firstOrNull { it.endpointInfo.id == remoteEndpointID }) {
            "Did not have expected connection"
        }

        remoteEndPoints.remove(key)
        connectionsChanged.run()
    }

    /**
     * Destroys this terminal. Valid to call from both sides.
     * On the server, it restores the handles to the remote terminals and removes the edge, and also destroys the edges from [GridConnectionManagerServer].
     * */
    override fun destroy() {
        requireIsOnServerThread {
            "destroyTerminal is illegal to call on non-server"
        }

        val remotes = remoteEndPoints.keys.toList()
        remoteEndPoints.clear()

        val handles = remotes.mapNotNull {
            val result = MicroGridTerminalHandle.restoreImmediate(level, it.snapshot)

            if(result == null) {
                LOG.warn("Failed to restore end point") // not as severe, may happen
            }

            result
        }

        handles.forEach {
            destroyForRemote(it)
        }

        GridConnectionManagerServer.removeEndpointById(level, gridEndpointInfo.id)
    }

    protected open fun destroyForRemote(remote: MicroGridTerminalHandle) {
        (remote.terminal as MicroGridTerminalServer).removeConnection(gridEndpointInfo.id)
    }

    open fun getServerSaveTag(): CompoundTag? = null
    open fun getClientSaveTag(): CompoundTag? = null

    /**
     * Checks if the terminal or overall container accepts connections made of [material].
     * */
    open fun acceptsMaterial(player: LivingEntity, material: GridMaterial) = categories.contains(material.category)

    companion object {
        private const val ENDPOINTS = "endpoints"
        private const val INFO = "info"
        private const val SNAPSHOT = "snapshot"
        private const val MATERIAL = "material"
        private const val RESISTANCE = "resistance"
    }
}

/**
 * Container of multiple [MicroGridTerminalCommon]s.
 * Usually implemented by a game object.
 * */
interface MicroGridNode {
    /**
     * Gets the terminal selected by [player].
     * @return The terminal selected by [player] or null, if the player's view does not intersect any terminals.
     * */
    fun pickTerminal(player: LivingEntity) : MicroGridTerminalCommon?

    /**
     * Gets the terminal by its [endpointID].
     * @return The terminal whose [MicroGridTerminalCommon.gridEndpointInfo]'s id is [endpointID] or null, if there is no such terminal.
     * */
    fun getTerminalByEndpointID(endpointID: UUID) : MicroGridTerminalCommon?
}

data class TerminalCreateInfoServer(
    val gridTerminalSystem: GridTerminalSystem,
    val terminalID: Int,
    val level: ServerLevel,
    val uuid: UUID,
    val serverTag: CompoundTag?
)

data class TerminalCreateInfoClient(
    val gridTerminalSystem: GridTerminalSystem,
    val terminalID: Int,
    val level: Level,
    val uuid: UUID,
    val clientTag: CompoundTag?
)

data class TerminalFactories(
    val server: (TerminalCreateInfoServer) -> MicroGridTerminalServer,
    val client: (TerminalCreateInfoClient) -> MicroGridTerminalClient,
)

/**
 * State management for a collection of [MicroGridTerminalCommon]s.
 * Usage is as follows:
 * - [GridTerminalSystem] is initialized in a field
 * - Following that, the desired terminals are deferred for creation using the [defineTerminal] methods. The results are also stored in fields.
 * - Terminals are finally created freshly (e.g. when the game object is first placed) or loaded (from previously saved data).
 * - After that, no more terminals may be created
 * */
class GridTerminalSystem(val level: Level) {
    private val factories = HashMap<Int, TerminalFactories>()
    private val instances = MutableMapPairBiMap<Int, MicroGridTerminalCommon>()
    private var currentID = 0
    private var isInitialized = false
    private var isDestroyed = false

    /**
     * Gets the instances. Valid to call after initialization.
     * */
    fun getInstances(): Map<Int, MicroGridTerminalCommon> {
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

    inline fun<reified T> forEachTerminalOfType(use: (T) -> Unit) = getInstances().values.forEach {
        if(it is T) {
            use(it)
        }
    }

    inline fun<reified T> terminalsOfType() : List<T> = getInstances().values.mapNotNull { it as? T }

    private fun create(factories: TerminalFactories, id: Int, uuid: UUID, tag: CompoundTag?) = if(level.isClientSide) {
        factories.client(TerminalCreateInfoClient(
            this,
            id,
            level,
            uuid,
            tag
        ))
    }
    else {
        factories.server(TerminalCreateInfoServer(
            this,
            id,
            level as ServerLevel,
            uuid,
            tag
        ))
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

            val terminal = create(factories, terminalID, endpointID, customTag)

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
     * @param saveType The destination of the data. [SaveType.Server] saves using [MicroGridTerminalCommon.getServerSaveTag] and [SaveType.Client] saves using [MicroGridTerminalCommon.getClientSaveTag].
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
            terminal as MicroGridTerminalServer

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
    fun <T : MicroGridTerminalCommon> defineTerminal(factory: TerminalFactories): Supplier<T> {
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
            return@Supplier checkNotNull(instances.forward[id]) {
                "Expected terminal instance for $id"
            } as T
        }
    }

    /**
     * Gets the terminal [player] is looking at.
     * @return The terminal intersected by [player]'s view or null, if no terminals intersect.
     * */
    fun pick(player: LivingEntity) : MicroGridTerminalCommon? {
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

    fun idOf(terminal: MicroGridTerminalCommon) : Int {
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

@ClientOnly
interface GridTerminalHighlight<Self> where Self : GridTerminalHighlight<Self>, Self : MicroGridTerminalClient {
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

        if(handle.terminal !is GridTerminalHighlight<*>) {
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

@ServerOnly
open class ServerBoxTerminal(
    ci: TerminalCreateInfoServer,
    locator: Locator,
    val boundingBox: OrientedBoundingBox3d,
    attachment: Vector3d? = null,
) : MicroGridTerminalServer(ci, locator, attachment ?: boundingBox.center) {
    /**
     * Gets the intersection distance with the [boundingBox].
     * */
    override fun intersect(ray: Ray3d): Vector3d? {
        val intersection = (ray intersectionWith boundingBox)
            ?: return null

        return ray.evaluate(intersection.entry)
    }
}

@ClientOnly
open class ClientBoxTerminal(
    ci: TerminalCreateInfoClient,
    locator: Locator,
    final override val boundingBox: OrientedBoundingBox3d,
    attachment: Vector3d? = null,
    override var highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
) : MicroGridTerminalClient(ci, locator, attachment ?: boundingBox.center), GridTerminalHighlight<ClientBoxTerminal> {
    init {
        require(ci.level.isClientSide) {
            "Fake box terminal is illegal on server!"
        }

        if(ci.clientTag != null) {
            require(ci.clientTag.isEmpty) {
                "Fake box terminal shouldn't have any data!"
            }
        }
    }

    /**
     * Gets the intersection distance with the [boundingBox].
     * */
    override fun intersect(ray: Ray3d): Vector3d? {
        val intersection = (ray intersectionWith boundingBox)
            ?: return null

        return ray.evaluate(intersection.entry)
    }
}

@ServerOnly
open class CellBoxTerminal(
    ci: TerminalCreateInfoServer,
    locator: Locator,
    obb: OrientedBoundingBox3d,
    attachment: Vector3d? = null,
    val cellAccessor: () -> GridNodeCell
) : ServerBoxTerminal(ci, locator, obb, attachment), MicroGridCellTerminal {
    override var stagingCell: GridConnectionCell? = null

    override val cell: GridNodeCell
        get() = cellAccessor.invoke()

    override fun initializeEdge(remoteInfo: GridEndpointInfo, description: GridConnectionDescription) {
        // ignored
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
     * Uses [ClientBoxTerminal] on the client and [ServerBoxTerminal] on the server.
     * */
    protected fun defineBoxTerminal(box3d: OrientedBoundingBox3d, attachment: Vector3d? = null, highlightColor : RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f)) = gridTerminalSystem.defineTerminal<MicroGridTerminalCommon>(
        TerminalFactories(
            { ci ->
                ServerBoxTerminal(ci, locator, box3d, attachment).also {
                    it.connectionsChanged += {
                        this.setSaveDirty()
                    }
                } },
            { ClientBoxTerminal(it, locator, box3d, attachment, highlightColor) }
        )
    )

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
        attachment: Vector3d? = null,
        highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
    ) = defineBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation), attachment, highlightColor)

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

@ServerOnly
interface MicroGridCellTerminal {
    val cell: GridNodeCell
    var stagingCell: GridConnectionCell?
}

abstract class CellMicroGridSpec<C : GridNodeCell, R : SpecRenderer>(
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
        cell.mapFromGridTerminalSystem(gridTerminalSystem)
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

    override fun neighborScan() : List<CellAndContainerHandle> {
        val results = ArrayList<CellAndContainerHandle>()

        gridTerminalSystem.forEachTerminalOfType<MicroGridCellTerminal> {
            if(it.stagingCell != null) {
                results.add(CellAndContainerHandle.captureInScope(it.stagingCell!!))
            }
        }

        cell.getGridMapping().forward.keys.forEach {
            results.add(CellAndContainerHandle.captureInScope(it))
        }

        return results
    }

    protected fun defineCellBoxTerminal(box3d: OrientedBoundingBox3d, attachment: Vector3d? = null, highlightColor : RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f), categories: List<GridMaterialCategory>) = gridTerminalSystem.defineTerminal<MicroGridTerminalCommon>(
        TerminalFactories(
            { ci ->
                CellBoxTerminal(ci, locator, box3d, attachment) { this.cell }.also {
                    it.connectionsChanged += {
                        this.setSaveDirty()
                    }

                    it.categories.addAll(categories)
                }
            },
            { ClientBoxTerminal(it, locator, box3d, attachment, highlightColor) }
        )
    )
    protected fun defineCellBoxTerminal(
        x: Double, y: Double, z: Double,
        sizeX: Double, sizeY: Double, sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
        attachment: Vector3d? = null,
        highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
        categories: List<GridMaterialCategory>
    ) = defineCellBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation), attachment, highlightColor, categories)
}

abstract class MicroGridCellBlockEntity<C : GridNodeCell>(pos: BlockPos, state: BlockState, targetType: BlockEntityType<*>) : CellBlockEntity<C>(pos, state, targetType), MicroGridNode {
    var containerID = UUID.randomUUID()
        private set

    private var gridTerminalSystemField: GridTerminalSystem? = null
    // FRAK YOU!
    val gridTerminalSystem get() = checkNotNull(gridTerminalSystemField) {
        "Grid terminal system is not initialized yet!"
    }

    val positiveX get() = blockState.getValue(HorizontalDirectionalBlock.FACING).toHorizontalFacing().rotation3d * Vector3d.unitX
    val positiveZ get() = blockState.getValue(HorizontalDirectionalBlock.FACING).toHorizontalFacing().rotation3d * Vector3d.unitZ

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
        blockPos.toVector3d() + Vector3d(0.5, 0.0, 0.5) +
            positiveX * x +
            Vector3d.unitY * y +
            positiveZ * z,
        orientation * blockState.getValue(HorizontalDirectionalBlock.FACING).toHorizontalFacing().rotation2d,
        Vector3d(sizeX, sizeY, sizeZ),
        blockState.getValue(HorizontalDirectionalBlock.FACING).toHorizontalFacing(),
        Direction.UP
    )

    protected fun defineCellBoxTerminal(box3d: OrientedBoundingBox3d, attachment: Vector3d? = null, highlightColor : RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f), categories: List<GridMaterialCategory>) = gridTerminalSystem.defineTerminal<MicroGridTerminalCommon>(
        TerminalFactories(
            { ci ->
                CellBoxTerminal(ci, locator, box3d, attachment) { this.cell }.also {
                    it.connectionsChanged += {
                        this.setChanged()
                    }

                    it.categories.addAll(categories)
                }
            },
            { ClientBoxTerminal(it, locator, box3d, attachment, highlightColor) }
        )
    )

    protected fun defineCellBoxTerminal(
        x: Double, y: Double, z: Double,
        sizeX: Double, sizeY: Double, sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
        attachment: Vector3d? = null,
        highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
        categories: List<GridMaterialCategory>
    ) = defineCellBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation), attachment, highlightColor, categories)


    private var gridTerminalSystemTag: CompoundTag? = null

    override fun setPlacedBy(level: Level, cellProvider: CellProvider<C>) {
        gridTerminalSystem.initializeFresh()
        super.setPlacedBy(level, cellProvider)

        if(!level.isClientSide) {
            cell.mapFromGridTerminalSystem(gridTerminalSystem)
        }
    }

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)

        pTag.putUUID(CONTAINER_ID, containerID)
        pTag.put(GRID_TERMINAL_SYSTEM, gridTerminalSystem.save(GridTerminalSystem.SaveType.Server))
    }

    override fun getUpdateTag(): CompoundTag {
        val tag = CompoundTag()
        tag.put(GRID_TERMINAL_SYSTEM, gridTerminalSystem.save(GridTerminalSystem.SaveType.Client))
        return tag
    }

    override fun handleUpdateTag(tag: CompoundTag?) {
        if(tag == null) {
            return
        }

        if(tag.contains(GRID_TERMINAL_SYSTEM)) {
            gridTerminalSystem.initializeSaved(tag.getCompound(GRID_TERMINAL_SYSTEM))
        }
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)

        if(pTag.contains(CONTAINER_ID)) {
            containerID = pTag.getUUID(CONTAINER_ID)
        }

        if(pTag.contains(GRID_TERMINAL_SYSTEM)) {
            gridTerminalSystemTag = pTag.getCompound(GRID_TERMINAL_SYSTEM)
        }
    }

    override fun setLevel(pLevel: Level) {
        check(gridTerminalSystemField == null) {
            "Grid terminal system was present in setLevel"
        }

        gridTerminalSystemField = GridTerminalSystem(pLevel)
        createTerminals()

        super.setLevel(pLevel)

        if(gridTerminalSystemTag != null) {
            check(!pLevel.isClientSide)
            gridTerminalSystem.initializeSaved(gridTerminalSystemTag!!)
            gridTerminalSystemTag = null
        }
    }

    protected abstract fun createTerminals()

    override fun pickTerminal(player: LivingEntity) = gridTerminalSystem.pick(player)

    override fun getTerminalByEndpointID(endpointID: UUID) = gridTerminalSystem.getByEndpointID(endpointID)

    override fun setDestroyed() {
        super.setDestroyed()
        gridTerminalSystem.destroy()
    }

    override fun addExtraConnections(results: MutableSet<CellAndContainerHandle>) {
        gridTerminalSystem.forEachTerminalOfType<MicroGridCellTerminal> {
            if(it.stagingCell != null) {
                results.add(CellAndContainerHandle.captureInScope(it.stagingCell!!))
            }
        }

        cell.getGridMapping().forward.keys.forEach {
            results.add(CellAndContainerHandle.captureInScope(it))
        }
    }

    companion object {
        private const val CONTAINER_ID = "containerID"
        private const val GRID_TERMINAL_SYSTEM = "gridTerminalSystem"
    }
}

abstract class MicroGridCellPart<C : GridNodeCell, R : PartRenderer>(
    ci: PartCreateInfo,
    provider: CellProvider<C>
) : CellPart<C, R>(ci, provider), MicroGridNode {
    var containerID: UUID = UUID.randomUUID()
        private set

    val gridTerminalSystem = GridTerminalSystem(placement.level)

    override fun onPlaced() {
        super.onPlaced()
        gridTerminalSystem.initializeFresh()
        cell.mapFromGridTerminalSystem(gridTerminalSystem)
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

    protected fun defineCellBoxTerminal(box3d: OrientedBoundingBox3d, attachment: Vector3d? = null, highlightColor : RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f), categories: List<GridMaterialCategory>) = gridTerminalSystem.defineTerminal<MicroGridTerminalCommon>(
        TerminalFactories(
            { ci ->
                CellBoxTerminal(ci, locator, box3d, attachment) { this.cell }.also {
                    it.connectionsChanged += {
                        this.setSaveDirty()
                    }

                    it.categories.addAll(categories)
                }
            },
            { ClientBoxTerminal(it, locator, box3d, attachment, highlightColor) }
        )
    )

    protected fun defineCellBoxTerminal(
        x: Double, y: Double, z: Double,
        sizeX: Double, sizeY: Double, sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
        attachment: Vector3d? = null,
        highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
        categories: List<GridMaterialCategory>
    ) = defineCellBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation), attachment, highlightColor, categories)

    override fun pickTerminal(player: LivingEntity) = gridTerminalSystem.pick(player)

    override fun getTerminalByEndpointID(endpointID: UUID) = gridTerminalSystem.getByEndpointID(endpointID)

    override fun addExtraConnections(results: MutableSet<CellAndContainerHandle>) {
        gridTerminalSystem.forEachTerminalOfType<MicroGridCellTerminal> {
            if(it.stagingCell != null) {
                results.add(CellAndContainerHandle.captureInScope(it.stagingCell!!))
            }
        }

        cell.getGridMapping().forward.keys.forEach {
            results.add(CellAndContainerHandle.captureInScope(it))
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

open class GridConnectItem(val material: GridMaterial) : Item(Properties()) {
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

        if(!(hTarget.terminal as MicroGridTerminalServer).acceptsMaterial(pPlayer, material)) {
            return fail("Incompatible material!")
        }

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

            hTarget.terminal as MicroGridTerminalServer
            hRemote.terminal as MicroGridTerminalServer

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

            val pair = GridEndpointPair.create(hTarget.terminal.gridEndpointInfo, hRemote.terminal.gridEndpointInfo)
            val gridCatenary = GridConnectionManagerServer.createCable(pair, material)

            if(GridCollisions.cablePlacementIntersects(pLevel, gridCatenary.cable, hTarget, hRemote)) {
                return fail("Stuff in the way!")
            }

            val connectionInfo = GridConnectionDescription(
                gridCatenary.material,
                gridCatenary.resistance
            )

            hTarget.terminal.beginConnectStaging(MicroGridConnectionDescription(hRemote, connectionInfo))
            hRemote.terminal.beginConnectStaging(MicroGridConnectionDescription(hTarget, connectionInfo))

            if(hTarget.terminal is MicroGridCellTerminal && hRemote.terminal is MicroGridCellTerminal) {
                GridConnectionCell.beginStaging(hTarget.terminal, hRemote.terminal, pLevel, gridCatenary)
            }

            hTarget.terminal.addConnection()
            hRemote.terminal.addConnection()

            if(hTarget.terminal is MicroGridCellTerminal && hRemote.terminal is MicroGridCellTerminal) {
                GridConnectionCell.endStaging(hTarget.terminal, hRemote.terminal)
            }

            hTarget.terminal.endConnectStaging()
            hRemote.terminal.endConnectStaging()

            GridConnectionManagerServer.createPair(pLevel, pair, gridCatenary)

            return success("Connected successfully!")
        }

        actualStack.tag = hTarget.toNbt()

        tell("Start recorded!")

        return InteractionResultHolder.success(actualStack)
    }
}
