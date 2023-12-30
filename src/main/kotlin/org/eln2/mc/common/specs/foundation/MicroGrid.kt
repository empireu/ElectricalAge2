package org.eln2.mc.common.specs.foundation

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
import org.ageseries.libage.data.Locator
import org.ageseries.libage.mathematics.geometry.OrientedBoundingBox3d
import org.ageseries.libage.mathematics.geometry.Ray3d
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.LOG
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.BasicSpecRenderer
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.content.*
import org.eln2.mc.extensions.*
import org.eln2.mc.requireIsOnServerThread
import java.util.*
import java.util.function.Supplier

abstract class MicroGridTerminalHandle(val terminal: MicroGridTerminal) {
    abstract fun toNbt() : CompoundTag

    enum class Type {
        Spec
    }

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

        fun pick(pLevel: Level, pPlayer: Player, hit: BlockHitResult) : MicroGridTerminalHandle? {
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

data class MicroGridEndpoint(val endpointInfo: GridEndpointInfo, val snapshot: CompoundTag)

data class MicroGridStagingInfo(val remoteTerminalInfo: MicroGridTerminalHandle, val description: GridConnectionDescription)

fun MicroGridStagingInfo.capture() = MicroGridEndpoint(
    remoteTerminalInfo.terminal.gridEndpointInfo,
    remoteTerminalInfo.toNbt()
)

interface MicroGridTerminal {
    fun hasConnectionWith(endpointID: UUID): Boolean

    val gridEndpointInfo: GridEndpointInfo

    fun addConnection(info: MicroGridStagingInfo)
    fun removeConnection(remoteEndpointID: UUID)
    fun stagePrimary()

    fun getServerSaveTag(): CompoundTag? = null
    fun getClientSaveTag(): CompoundTag? = null

    fun destroy()

    fun intersectionDistance(ray: Ray3d) : Vector3d?
}

interface MicroGridNode {
    fun pickTerminal(player: LivingEntity) : MicroGridTerminal?

    fun getTerminalByEndpointID(endpointID: UUID) : MicroGridTerminal?
}

data class TerminalFactories<Terminal : MicroGridTerminal>(
    val server: (level: ServerLevel, uuid: UUID, serverTag: CompoundTag?) -> Terminal,
    val client: (level: Level, uuid: UUID, clientTag: CompoundTag?) -> Terminal
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
     * @param factory The factory to create the terminal. This is used on both server and client.
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
            val intersection = it.intersectionDistance(ray)
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

abstract class BoxTerminal(
    val level: Level,
    val locator: Locator,
    val boundingBox: OrientedBoundingBox3d,
    uuid: UUID,
    tag: CompoundTag?,
) : MicroGridTerminal {
    override val gridEndpointInfo = GridEndpointInfo(uuid, boundingBox.center, locator)
    val remoteEndPoints = HashMap<MicroGridEndpoint, GridConnectionDescription>()

    init {
        if(tag != null && !level.isClientSide) {
            val list = tag.getListTag(ENDPOINTS)

            list.forEachCompound { endpointCompound ->
                val info = GridEndpointInfo.fromNbt(endpointCompound.getCompound(INFO))
                val snapshot = endpointCompound.getCompound(SNAPSHOT)
                val material = GridMaterials.getMaterial(endpointCompound.getResourceLocation(MATERIAL))
                val resistance = endpointCompound.getDouble(RESISTANCE)

                val endpoint = MicroGridEndpoint(info, snapshot)
                val description = GridConnectionDescription(material, resistance)

                remoteEndPoints.putUnique(endpoint, description)

                MicroGridOperations.initialize(
                    level as ServerLevel,
                    this,
                    endpoint.endpointInfo,
                    description
                )
            }
        }
    }

    override fun getServerSaveTag(): CompoundTag {
        requireIsOnServerThread()

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

    override fun addConnection(info: MicroGridStagingInfo) {
        requireIsOnServerThread()

        remoteEndPoints.putUnique(info.capture(), info.description)
    }

    override fun removeConnection(remoteEndpointID: UUID) {
        requireIsOnServerThread()

        val key = checkNotNull(remoteEndPoints.keys.firstOrNull { it.endpointInfo.id == remoteEndpointID }) {
            "Did not have expected connection"
        }

        remoteEndPoints.remove(key)
    }

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

    override fun intersectionDistance(ray: Ray3d): Vector3d? {
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

abstract class MicroGridSpec<Renderer : SpecRenderer>(ci: SpecCreateInfo) : Spec<Renderer>(ci), MicroGridNode {
    val locator = placement.createLocator()

    protected val gridTerminalSystem = GridTerminalSystem<MicroGridTerminal>(placement.level)

    protected fun defineBoxTerminal(box3d: OrientedBoundingBox3d) = gridTerminalSystem.defineTerminal { level, uuid, compoundTag ->
        SpecBoxTerminal(box3d, uuid, compoundTag)
    }

    protected fun boundingBox(
        x: Double,
        y: Double,
        z: Double,
        sizeX: Double,
        sizeY: Double,
        sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity
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
        orientation: Rotation2d = Rotation2d.identity
    ) = defineBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation))

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

    protected open inner class SpecBoxTerminal(obb: OrientedBoundingBox3d, uuid: UUID, tag: CompoundTag?) : BoxTerminal(placement.level, locator, obb, uuid, tag) {
        override fun addConnection(info: MicroGridStagingInfo) {
            super.addConnection(info)
            setSaveDirty()
        }

        override fun removeConnection(remoteEndpointID: UUID) {
            super.removeConnection(remoteEndpointID)
            setSaveDirty()
        }
    }
}

class TestMicroGridSpec(ci: SpecCreateInfo) : MicroGridSpec<BasicSpecRenderer>(ci) {
    private val terminal = defineBoxTerminal(
        0.05, 0.0, 0.0,
        0.1, 0.2, 0.1
    )

    override fun createRenderer(): BasicSpecRenderer {
        return BasicSpecRenderer(this, PartialModels.ELECTRICAL_WIRE_HUB)
    }
}

object MicroGridOperations {
    fun destroyTerminal(level: ServerLevel, terminal: MicroGridTerminal, remotes: List<MicroGridEndpoint>) {
        requireIsOnServerThread()

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

        terminal.stagePrimary()

        GridConnectionManagerServer.removeEndpointById(level, terminal.gridEndpointInfo.id)
    }

    fun initialize(level: ServerLevel, terminal: MicroGridTerminal, remoteInfo: GridEndpointInfo, description: GridConnectionDescription) {
        requireIsOnServerThread()

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

        val hit = getPlayerPOVHitResult(pLevel, pPlayer, ClipContext.Fluid.SOURCE_ONLY)

        if (hit.type != HitResult.Type.BLOCK) {
            return fail("Cannot connect that!")
        }

        val targetTerminalInfo = MicroGridTerminalHandle.pick(pLevel, pPlayer, hit)
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

            targetTerminal.addConnection(MicroGridStagingInfo(remoteTerminalInfo, connectionInfo))
            remoteTerminal.addConnection(MicroGridStagingInfo(targetTerminalInfo, connectionInfo))
            targetTerminal.stagePrimary()

            GridConnectionManagerServer.createPair(pLevel, pair, gridCatenary)

            return success("Connected successfully!")
        }

        actualStack.tag = targetTerminalInfo.toNbt()

        tell("Start recorded!")

        return InteractionResultHolder.success(actualStack)
    }
}
