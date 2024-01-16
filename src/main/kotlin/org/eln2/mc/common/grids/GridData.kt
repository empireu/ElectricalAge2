package org.eln2.mc.common.grids

import it.unimi.dsi.fastutil.doubles.Double2DoubleOpenHashMap
import it.unimi.dsi.fastutil.floats.FloatArrayList
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraftforge.network.NetworkEvent
import org.ageseries.libage.data.BoundingBoxTree3d
import org.ageseries.libage.data.Locator
import org.ageseries.libage.data.MutableMapPairBiMap
import org.ageseries.libage.data.MutableSetMapMultiMap
import org.ageseries.libage.mathematics.geometry.*
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.utils.addUnique
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.*
import org.eln2.mc.client.render.SketchExtrusion
import org.eln2.mc.client.render.foundation.RGBFloat
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateBlockEntity
import org.eln2.mc.common.blocks.foundation.MultipartBlock
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.items.foundation.PartItem
import org.eln2.mc.common.network.Networking
import org.eln2.mc.common.specs.foundation.SpecContainerPart
import org.eln2.mc.extensions.*
import org.eln2.mc.mathematics.ceilBlockPos
import org.eln2.mc.mathematics.floorBlockPos
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.PI

/**
 * [Cable3dA] with extra information needed for syncing and state management.
 * @param netID The unique ID of the connection. This is used for replication to clients. This ID should never be saved, it is a runtime-only thing.
 * @param cable The 3D model data.
 * @param material The physical properties of the grid cable.
 * */
data class GridConnection(val netID: Int, val cable: Cable3dA, val material: GridMaterial) {
    constructor(catenary: Cable3dA, material: GridMaterial) : this(getUniqueId(), catenary, material)

    /**
     * Gets the electrical resistance over the entire length of the cable.
     * */
    val resistance get() = !material.physicalMaterial.electricalResistivity * (cable.arcLength / cable.crossSectionArea)

    fun toNbt() = CompoundTag().also {
        it.putInt(ID, netID)
        it.put(CATENARY, cable.toNbt())
        it.putResourceLocation(MATERIAL, GridMaterials.getId(material))
    }

    companion object {
        private const val ID = "id"
        private const val CATENARY = "catenary"
        private const val MATERIAL = "material"

        @ClientOnly
        fun fromNbt(tag: CompoundTag) = GridConnection(
            tag.getInt(ID),
            Cable3dA.fromNbt(tag.get(CATENARY) as CompoundTag),
            GridMaterials.getMaterial(tag.getResourceLocation(MATERIAL))
        )

        /**
         * Creates a grid connection, between the two endpoints in [pair] and with the specified [material].
         * */
        fun create(pair: GridEndpointPair, material: GridMaterial) = GridConnection(
            material.create(
                pair.a.attachment,
                pair.b.attachment
            ),
            material
        )
    }
}

/**
 * Handles a [GridConnection] that is fully replicated to clients.
 * */
interface GridConnectionHandle {
    val pair: GridEndpointPair
    val connection: GridConnection
    val level: ServerLevel
    val owner: GridConnectionOwner?

    /**
     * Sets the rendering options for the connection. Changing this will sync it to clients.
     * */
    var options: GridRenderOptions

    fun remove()
}

/**
 * Describes a grid endpoint (a node in the grid graph).
 * @param id The unique ID of the node.
 * @param attachment The position where wires attach to, for rendering and for collisions.
 * @param locator A locator that describes the position of this endpoint. Usually just the locator of the game object.
 * */
class GridEndpointInfo(val id: UUID, val attachment: Vector3d, val locator: Locator) {
    /**
     * Compares the objects for equality. If [other] is a [GridEndpointInfo], equality is evaluated only for [GridEndpointInfo.id]
     * */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GridEndpointInfo

        return id == other.id
    }

    /**
     * Gets the hash code of the [id].
     * */
    override fun hashCode(): Int {
        return id.hashCode()
    }

    fun toNbt() = CompoundTag().also {
        it.putUUID(NBT_ID, id)
        it.putVector3d(NBT_ATTACHMENT, attachment)
        it.putLocator(NBT_LOCATOR, locator)
    }

    companion object {
        private const val NBT_ID = "id"
        private const val NBT_ATTACHMENT = "attachment"
        private const val NBT_LOCATOR = "locator"

        fun fromNbt(tag: CompoundTag) = GridEndpointInfo(
            tag.getUUID(NBT_ID),
            tag.getVector3d(NBT_ATTACHMENT),
            tag.getLocator(NBT_LOCATOR)
        )
    }
}

/**
 * Represents a sorted pair of [GridEndpointInfo].
 * */
class GridEndpointPair private constructor(val a: GridEndpointInfo, val b: GridEndpointInfo) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GridEndpointPair

        if (a != other.a) return false
        if (b != other.b) return false

        return true
    }

    override fun hashCode(): Int {
        var result = a.hashCode()
        result = 31 * result + b.hashCode()
        return result
    }

    fun toNbt() = CompoundTag().also {
        it.putVector3d(A_ATTACHMENT, a.attachment)
        it.putVector3d(B_ATTACHMENT, b.attachment)
        it.putUUID(A_UUID, a.id)
        it.putUUID(B_UUID, b.id)
        it.putLocator(A_LOCATOR, a.locator)
        it.putLocator(B_LOCATOR, b.locator)
    }

    companion object {
        private const val A_ATTACHMENT = "attachmentA"
        private const val B_ATTACHMENT = "attachmentB"
        private const val A_UUID = "uuidA"
        private const val B_UUID = "uuidB"
        private const val A_LOCATOR = "locatorA"
        private const val B_LOCATOR = "locatorB"

        /**
         * Creates a sorted pair of [a] and [b].
         * *It is true that:*
         * ```
         * create(a, b) = create(b, a)
         * ```
         * */
        fun create(a: GridEndpointInfo, b: GridEndpointInfo) : GridEndpointPair {
            require(a.id != b.id) {
                "End points $a and $b have same UUID ${a.id}"
            }

            return if(a.id < b.id) {
                GridEndpointPair(a, b)
            }
            else {
                GridEndpointPair(b, a)
            }
        }

        fun fromNbt(tag: CompoundTag) = GridEndpointPair(
            GridEndpointInfo(
                tag.getUUID(A_UUID),
                tag.getVector3d(A_ATTACHMENT),
                tag.getLocator(A_LOCATOR)
            ),
            GridEndpointInfo(
                tag.getUUID(B_UUID),
                tag.getVector3d(B_ATTACHMENT),
                tag.getLocator(B_LOCATOR)
            )
        )
    }
}

data class GridConnectionCreateMessage(val connection: GridConnection) {
    companion object {
        fun encode(message: GridConnectionCreateMessage, buf: FriendlyByteBuf) {
            buf.writeNbt(message.connection.toNbt())
        }

        fun decode(buf: FriendlyByteBuf) = GridConnectionCreateMessage(
            GridConnection.fromNbt(buf.readNbt()!!)
        )

        fun handle(message: GridConnectionCreateMessage, ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork {
                GridConnectionManagerClient.addConnection(message.connection)
            }

            ctx.get().packetHandled = true
        }
    }
}

data class GridConnectionDeleteMessage(val id: Int) {
    companion object {
        fun encode(message: GridConnectionDeleteMessage, buf: FriendlyByteBuf) {
            buf.writeInt(message.id)
        }

        fun decode(buf: FriendlyByteBuf) = GridConnectionDeleteMessage(
            buf.readInt()
        )

        fun handle(message: GridConnectionDeleteMessage, ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork {
                GridConnectionManagerClient.removeConnection(message.id)
            }

            ctx.get().packetHandled = true
        }
    }
}

data class GridConnectionUpdateRenderMessage(val id: Int, val options: GridRenderOptions) {
    companion object {
        fun encode(message: GridConnectionUpdateRenderMessage, buf: FriendlyByteBuf) {
            buf.writeInt(message.id)
            buf.writeFloat(message.options.tint.r)
            buf.writeFloat(message.options.tint.g)
            buf.writeFloat(message.options.tint.b)
            buf.writeDouble(message.options.brightnessOverride)
        }

        fun decode(buf: FriendlyByteBuf) = GridConnectionUpdateRenderMessage(
            buf.readInt(),
            GridRenderOptions(
                RGBFloat(
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat()
                ),
                buf.readDouble()
            )
        )

        fun handle(message: GridConnectionUpdateRenderMessage, ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork {
                GridConnectionManagerClient.updateRender(message.id, message.options)
            }

            ctx.get().packetHandled = true
        }
    }
}

/**
 * Data structure using [BoundingBox3d] for various collision tests.
 * */
class GridPruningStructure {
    private var boundingBoxTree = BoundingBoxTree3d<Segment>()
    private var cables = MutableSetMapMultiMap<Int, Segment>()

    fun add(connection: GridConnection) {
        require(!cables.contains(connection.netID)) {
            "Duplicate add $connection"
        }

        val set = cables[connection.netID]

        connection.cable.segments.forEach {
            val segment = Segment(it, connection)
            set.addUnique(segment)
            boundingBoxTree.insert(segment, BoundingBox3d.fromCylinder(it))
        }
    }

    fun remove(id: Int) {
        val set = checkNotNull(cables.map.remove(id)) {
            "Did not have connection $id"
        }

        set.forEach {
            boundingBoxTree.remove(it)
        }
    }

    /**
     * Checks if any cables intersect the [box].
     * */
    fun intersects(box: BoundingBox3d) : Boolean {
        var result = false

        boundingBoxTree.queryIntersecting({ it.box intersectsWith box}) {
            val cylinder = it.data!!.cylinder

            if(cylinder intersectsWith box) {
                result = true
                false
            }
            else {
                true
            }
        }

        return result
    }

    /**
     * Checks if any cables intersect the [box].
     * */
    fun intersects(box: OrientedBoundingBox3d) : Boolean {
        var result = false

        val boxAligned = BoundingBox3d.fromOrientedBoundingBox(box)

        boundingBoxTree.queryIntersecting({ it.box intersectsWith boxAligned }) {
            val cylinder = it.data!!.cylinder

            if(cylinder intersectsWith box) {
                result = true
                false
            }
            else {
                true
            }
        }

        return result
    }

    /**
     * Gets the closest segment intersected by [line].
     * @return The closest segment intersected by [line] and the [RayIntersection], or null, if no segments intersect.
     * */
    fun pick(line: Line3d) : Pair<Segment, RayIntersection>? {
        var bestSegment: Segment? = null
        var bestIntersection: RayIntersection? = null

        boundingBoxTree.queryIntersecting({ line intersectsWith it.box }) {
            val segment = it.data!!

            val intersection = line intersectionWith segment.cylinder

            if(intersection != null) {
                if(bestIntersection == null || bestIntersection!!.entry > intersection.entry) {
                    bestSegment = segment
                    bestIntersection = intersection
                }
            }

            true
        }

        return if(bestIntersection == null) {
            null
        }
        else {
            Pair(bestSegment!!, bestIntersection!!)
        }
    }

    fun clear() {
        boundingBoxTree = BoundingBoxTree3d()
        cables = MutableSetMapMultiMap()
    }

    class Segment(val cylinder: Cylinder3d, val cable: GridConnection)
}

/**
 * Represents a game object or something else, that owns a grid connection and handles e.g. cutting the connection by a player.
 * */
interface GridConnectionOwner {
    fun cutWithPliers(player: ServerPlayer, pliers: GridCablePliersItem) : Boolean
}

@ServerOnly
object GridConnectionManagerServer {
    private val levels = HashMap<ServerLevel, LevelGridData>()

    private fun validateUsage() {
        requireIsOnServerThread {
            "Grid server must be on server thread"
        }
    }

    fun clear() {
        validateUsage()
        levels.clear()
    }

    /**
     * Calls the action on the data stored for [level], running validation.
     * */
    private inline fun<T> invoke(level: ServerLevel, action: LevelGridData.() -> T) : T {
        validateUsage()

        val data = levels.computeIfAbsent(level) {
            LevelGridData(level)
        }

        return action.invoke(data)
    }

    fun playerWatch(level: ServerLevel, player: ServerPlayer, chunkPos: ChunkPos) = invoke(level) {
        watch(player, chunkPos)
    }

    fun playerUnwatch(level: ServerLevel, player: ServerPlayer, chunkPos: ChunkPos) = invoke(level) {
        unwatch(player, chunkPos)
    }

    fun registerPairIfAbsent(level: ServerLevel, pair: GridEndpointPair, material: GridMaterial) : GridConnection = invoke(level) {
        createPairIfAbsent(pair, material)
    }

    fun registerPair(level: ServerLevel, pair: GridEndpointPair, connection: GridConnection, owner: GridConnectionOwner? = null) = invoke(level) {
        registerPair(pair, connection, owner)
    }

    fun removeEndpointById(level: ServerLevel, endpointId: UUID) = invoke(level) {
        removeEndpointById(endpointId)
    }

    fun intersects(level: ServerLevel, box: BoundingBox3d) = invoke(level) {
        intersects(box)
    }

    fun intersects(level: ServerLevel, box: OrientedBoundingBox3d) = invoke(level) {
        intersects(box)
    }

    fun pick(level: ServerLevel, line: Line3d) = invoke(level) {
        pick(line)
    }

    fun getHandle(level: ServerLevel, id: Int) = invoke(level) {
        getHandle(id)
    }

    fun getOwner(level: ServerLevel, id: Int) = getHandle(level, id)?.owner

    class LevelGridData(val level: ServerLevel) {
        private val handles = MutableMapPairBiMap<Int, Handle>()
        private val handlesByChunk = MutableSetMapMultiMap<ChunkPos, Handle>()
        private val watchedChunksByPlayer = MutableSetMapMultiMap<ServerPlayer, ChunkPos>()
        private val pairMap = PairMap()
        private val handlesByPair = HashMap<GridEndpointPair, Handle>()
        private val collider = GridPruningStructure()

        fun watch(player: ServerPlayer, chunkPos: ChunkPos) {
            if(watchedChunksByPlayer[player].add(chunkPos)) {
                handlesByChunk[chunkPos].forEach { handle ->
                    if(handle.addPlayer(player, chunkPos)) {
                        sendConnection(player, handle)
                    }
                }
            }
        }

        fun unwatch(player: ServerPlayer, chunkPos: ChunkPos) {
            if(watchedChunksByPlayer[player].remove(chunkPos)) {
                handlesByChunk[chunkPos].forEach { handle ->
                    if(handle.removePlayer(player, chunkPos)) {
                        sendDeletedConnection(player, handle)
                    }
                }
            }
        }

        private fun sendConnection(player: ServerPlayer, handle: Handle) {
            Networking.send(GridConnectionCreateMessage(handle.connection), player)
        }

        private fun sendDeletedConnection(player: ServerPlayer, handle: Handle) {
            Networking.send(GridConnectionDeleteMessage(handle.connection.netID), player)
        }

        fun intersects(box: BoundingBox3d) = collider.intersects(box)
        fun intersects(box: OrientedBoundingBox3d) = collider.intersects(box)
        fun pick(line: Line3d) = collider.pick(line)
        fun getHandle(id: Int) : GridConnectionHandle? = handles.forward[id]

        private fun createHandle(pair: GridEndpointPair, connection: GridConnection, owner: GridConnectionOwner?) : Handle {
            val handle = Handle(pair, connection, owner)

            handles.add(connection.netID, handle)

            connection.cable.chunks.keys.forEach { chunkPos ->
                handlesByChunk[chunkPos].add(handle)
            }

            watchedChunksByPlayer.map.forEach { (player, playerWatchedChunks) ->
                val intersectedChunks = HashSet<ChunkPos>()

                for (catenaryChunk in connection.cable.chunks.keys) {
                    if(playerWatchedChunks.contains(catenaryChunk)) {
                        intersectedChunks.add(catenaryChunk)
                    }
                }

                if(intersectedChunks.isNotEmpty()) {
                    sendConnection(player, handle)

                    intersectedChunks.forEach { playerChunk ->
                        handle.addPlayer(player, playerChunk)
                    }
                }
            }

            return handle
        }

        fun registerPair(pair: GridEndpointPair, connection: GridConnection, owner: GridConnectionOwner?) : GridConnectionHandle {
            pairMap.addPair(pair)
            val handle = createHandle(pair, connection, owner)
            handlesByPair[pair] = handle
            return handle
        }

        fun createPairIfAbsent(pair: GridEndpointPair, material: GridMaterial) : GridConnection {
            if(pairMap.hasPair(pair)) {
                return checkNotNull(handlesByPair[pair]) { "Lingering pair in pair map" }.connection
            }

            val result = GridConnection.create(pair, material)

            registerPair(pair, result, null)

            return result
        }

        fun removeEndpointById(endPointId: UUID) {
            pairMap.removePairsById(endPointId).forEach { pair ->
                handlesByPair.remove(pair)!!.cleanup()
            }
        }

        private inner class Handle(override val pair: GridEndpointPair, override val connection: GridConnection, override val owner: GridConnectionOwner?) :
            GridConnectionHandle {
            init {
                collider.add(connection)
            }

            private var isDestroyed = false
            private val players = MutableSetMapMultiMap<ServerPlayer, ChunkPos>()

            // for modifying [options] from simulation threads
            private val lock = ReentrantReadWriteLock()

            override var options: GridRenderOptions = GridRenderOptions()
                set(value) {
                    if(field != value) {
                        field = value

                        lock.read {
                            if(!isDestroyed) {
                                val message = GridConnectionUpdateRenderMessage(
                                    connection.netID,
                                    value
                                )

                                players.map.keys.forEach {
                                    Networking.send(message, it)
                                }
                            }
                        }
                    }
                }

            fun addPlayer(player: ServerPlayer, chunkPos: ChunkPos) : Boolean {
                lock.write {
                    check(!isDestroyed) {
                        "addPlayer isDestroyed"
                    }

                    val result = !players.contains(player)
                    players[player].add(chunkPos)
                    return result
                }
            }

            fun removePlayer(player: ServerPlayer, chunkPos: ChunkPos) : Boolean {
                lock.write {
                    check(!isDestroyed) {
                        "removePlayer isDestroyed"
                    }

                    players.remove(player, chunkPos)

                    return !players.contains(player)
                }
            }

            override val level: ServerLevel
                get() = this@LevelGridData.level

            fun cleanup() {
                lock.write {
                    check(!isDestroyed) {
                        "cleanup isDestroyed"
                    }

                    validateUsage()

                    if(handles.removeBackward(this)) {
                        connection.cable.chunks.keys.forEach { chunk ->
                            handlesByChunk[chunk].remove(this)
                        }

                        players.keys.forEach { player ->
                            sendDeletedConnection(player, this)
                        }

                        collider.remove(connection.netID)
                    }
                }
            }

            override fun remove() {
                lock.write {
                    check(!isDestroyed) {
                        "Tried to remove multiple times"
                    }

                    pairMap.removePair(pair)
                    check(handlesByPair.remove(pair) === this)
                    cleanup()
                    isDestroyed = true
                }
            }
        }

        private class PairMap {
            val pairs = HashSet<GridEndpointPair>()
            val pairsByEndpoint = MutableSetMapMultiMap<GridEndpointInfo, GridEndpointPair>()
            val endpointsByEndpointId = HashMap<UUID, GridEndpointInfo>()

            private fun putId(endpoint: GridEndpointInfo) {
                val existing = endpointsByEndpointId.put(endpoint.id, endpoint)

                if(existing != null) {
                    require(existing == endpoint) {
                        "Duplicate end point $existing $endpoint"
                    }
                }
            }

            private fun takeId(endpoint: GridEndpointInfo) {
                if(!pairsByEndpoint.contains(endpoint)) {
                    require(endpointsByEndpointId.remove(endpoint.id) == endpoint) {
                        "Failed to remove end point $endpoint"
                    }
                }
            }

            fun hasPair(pair: GridEndpointPair) = pairs.contains(pair)

            fun addPair(pair: GridEndpointPair) {
                check(pairs.add(pair)) {
                    "Duplicate add $pair"
                }

                pairsByEndpoint[pair.a].add(pair)
                pairsByEndpoint[pair.b].add(pair)
                putId(pair.a)
                putId(pair.b)
            }

            fun removePair(pair: GridEndpointPair) {
                check(pairs.remove(pair))
                pairsByEndpoint[pair.a].remove(pair)
                pairsByEndpoint[pair.b].remove(pair)
                takeId(pair.a)
                takeId(pair.b)
            }

            fun getPairs(endPoint: GridEndpointInfo) = pairsByEndpoint[endPoint].toList()

            fun removePairsById(endpointId: UUID) : List<GridEndpointPair> {
                val endPoint = endpointsByEndpointId[endpointId] ?: return emptyList()

                val pairs = getPairs(endPoint)

                pairs.forEach {
                    removePair(it)
                }

                return pairs
            }
        }
    }
}

data class GridRenderOptions(
    val tint: RGBFloat = RGBFloat(1f, 1f, 1f),
    val brightnessOverride: Double = 0.0
)

@ClientOnly
object GridConnectionManagerClient {
    private class DataHolder(var options: GridRenderOptions) : Supplier<GridRenderOptions> {
        override fun get() = options
    }

    private val lock = ReentrantReadWriteLock()
    private val connections = HashMap<Int, DataHolder>()
    private val slicesByConnection = MutableSetMapMultiMap<Int, ConnectionSectionSlice>()
    private val slicesBySection = MutableSetMapMultiMap<SectionPos, ConnectionSectionSlice>()
    private val collider = GridPruningStructure()

    fun clear() {
        lock.write {
            slicesByConnection.map.clear()
            slicesBySection.map.clear()
            collider.clear()
        }
    }

    fun intersects(box: BoundingBox3d) : Boolean {
        val result: Boolean

        lock.read {
            result = collider.intersects(box)
        }

        return result
    }

    fun intersects(box: OrientedBoundingBox3d) : Boolean {
        val result: Boolean

        lock.read {
            result = collider.intersects(box)
        }

        return result
    }

    fun pick(line: Line3d) : Pair<GridPruningStructure.Segment, RayIntersection>? {
        var result: Pair<GridPruningStructure.Segment, RayIntersection>?

        lock.read {
            result = collider.pick(line)
        }

        return result
    }

    private fun scanUProgression(extrusion: SketchExtrusion, catenary: Cable3dA, u0: Double, u1: Double) : Double2DoubleOpenHashMap {
        var p0 = extrusion.rmfProgression.first()
        var arcLength = 0.0
        val uCoordinates = Double2DoubleOpenHashMap(extrusion.rmfProgression.size)

        extrusion.rmfProgression.forEach { p1 ->
            arcLength += (p0.value.translation .. p1.value.translation)

            val coordinate = if(arcLength.mod(catenary.circumference * 2.0) > catenary.circumference) {
                map(
                    arcLength.mod(catenary.circumference),
                    0.0, catenary.circumference,
                    u1, u0
                )
            }
            else {
                map(
                    arcLength.mod(catenary.circumference),
                    0.0, catenary.circumference,
                    u0, u1
                )
            }

            uCoordinates.put(p1.t, coordinate)

            p0 = p1
        }

        return uCoordinates
    }

    private fun setDirty(sectionPos: SectionPos) {
        Minecraft.getInstance().levelRenderer.setSectionDirty(
            sectionPos.x,
            sectionPos.y,
            sectionPos.z
        )
    }

    fun addConnection(connection: GridConnection) {
        val data = DataHolder(GridRenderOptions())

        val cable = connection.cable
        val (extrusion, quads) = cable.mesh()
        val sprite = connection.material.sprite

        LOG.info("Generated ${quads.size} quads")

        val uCoordinates = scanUProgression(
            extrusion,
            cable,
            sprite.u0.toDouble(),
            sprite.u1.toDouble()
        )

        val v0 = sprite.v0.toDouble()
        val v1 = sprite.v1.toDouble()

        val slicesBySection = HashMap<SectionPos, ConnectionSectionSlice>()

        fun getSlice(blockPos: BlockPos) : ConnectionSectionSlice {
            val section = SectionPos.of(blockPos)

            return slicesBySection.computeIfAbsent(section) {
                ConnectionSectionSlice(
                    connection.material,
                    section,
                    data
                )
            }
        }

        for (quad in quads) {
            val sectionData = getSlice(quad.principal)

            quad.vertices.forEach { vertex ->
                val orientation = extrusion.rmfLookup.get(vertex.param)!!.rotation
                val phase = vertex.normal angle orientation.invoke().c2

                val u = uCoordinates.get(vertex.param)
                val v = map(phase, -PI, PI, v0, v1)

                sectionData.vertices.add(
                    vertex.position,
                    vertex.normal,
                    u, v
                )
            }
        }

        connection.cable.blocks.forEach { block ->
            getSlice(block).blocks.add(block)
        }

        lock.write {
            connections.putUnique(connection.netID, data)

            val byConnection = slicesByConnection[connection.netID]

            slicesBySection.forEach { (sectionPos, sectionSlice) ->
                byConnection.addUnique(sectionSlice)
                this.slicesBySection[sectionPos].addUnique(sectionSlice)
            }

            collider.add(connection)
        }

        slicesBySection.keys.forEach {
            setDirty(it)
        }
    }

    fun updateRender(id: Int, options: GridRenderOptions) {
        lock.write {
            if(connections.containsKey(id)) {
                connections[id]!!.options = options

                slicesByConnection[id].forEach { slice ->
                    setDirty(slice.sectionPos)
                }
            }
            else {
                LOG.error("Rogue grid update")
            }
        }
    }

    fun removeConnection(id: Int) {
        lock.write {
            check(connections.remove(id) != null) {
                "Did not have connection"
            }

            slicesByConnection[id].forEach { slice ->
                slicesBySection[slice.sectionPos].remove(slice)
                setDirty(slice.sectionPos)
            }

            slicesByConnection.clear(id)

            collider.remove(id)
        }
    }

    @JvmStatic
    fun read(sectionPos: SectionPos, user: (material: GridMaterial, vertices: VertexList, data: GridRenderOptions) -> Unit) {
        val slices = ArrayList<ConnectionSectionSlice>()

        lock.read {
            slicesBySection[sectionPos].forEach {
                slices.add(it)
            }
        }

        slices.forEach {
            user(it.material, it.vertices, it.dataAccessor.get())
        }
    }

    @JvmStatic
    fun containsRangeVisual(sectionPos: SectionPos) : Boolean {
        var result = false

        lock.read {
            val slices = slicesBySection.map[sectionPos]

            result = slices != null && slices.any { it.isVisual }
        }

        return result
    }

    @JvmStatic
    fun containsRangeVisual(pStart: BlockPos, pEnd: BlockPos) : Boolean {
        var result = false

        val i = SectionPos.blockToSectionCoord(pStart.x)
        val j = SectionPos.blockToSectionCoord(pStart.y)
        val k = SectionPos.blockToSectionCoord(pStart.z)
        val l = SectionPos.blockToSectionCoord(pEnd.x)
        val m = SectionPos.blockToSectionCoord(pEnd.y)
        val n = SectionPos.blockToSectionCoord(pEnd.z)
        // ~8 sections per range

        lock.read {
            for(sectionPos in SectionPos.betweenClosedStream(i, j, k, l, m, n)) {
                val slices = slicesBySection.map[sectionPos]

                if(slices != null && slices.any { it.isVisual }) {
                    result = true
                    break
                }
            }
        }

        return result
    }

    fun clipsBlock(blockPos: BlockPos) : Boolean {
        var result = false

        lock.read {
            for (slice in slicesBySection[SectionPos.of(blockPos)]) {
                if(slice.blocks.contains(blockPos)) {
                    result = true
                    break
                }
            }
        }

        return result
    }

    class VertexList(sectionPos: SectionPos) {
        val originX = sectionPos.minBlockX()
        val originY = sectionPos.minBlockY()
        val originZ = sectionPos.minBlockZ()
        val storage = FloatArrayList()

        fun add(position: Vector3d, normal: Vector3d, u: Double, v: Double) {
            storage.add((position.x - originX).toFloat())
            storage.add((position.y - originY).toFloat())
            storage.add((position.z - originZ).toFloat())
            storage.add(normal.x.toFloat())
            storage.add(normal.y.toFloat())
            storage.add(normal.z.toFloat())
            storage.add(u.toFloat())
            storage.add(v.toFloat())
        }
    }

    private class ConnectionSectionSlice(val material: GridMaterial, val sectionPos: SectionPos, val dataAccessor: Supplier<GridRenderOptions>) {
        val vertices = VertexList(sectionPos)
        val blocks = HashSet<BlockPos>()

        val isVisual get() = vertices.storage.isNotEmpty()
    }
}

object GridCollisions {
    fun intersects(level: Level, boundingBox: BoundingBox3d) = if(level.isClientSide) {
        GridConnectionManagerClient.intersects(boundingBox)
    }
    else {
        GridConnectionManagerServer.intersects(level as ServerLevel, boundingBox)
    }

    fun intersects(level: Level, boundingBox: OrientedBoundingBox3d) = if(level.isClientSide) {
        GridConnectionManagerClient.intersects(boundingBox)
    }
    else {
        GridConnectionManagerServer.intersects(level as ServerLevel, boundingBox)
    }

    @JvmStatic
    fun intersectsPlacementBlock(context: BlockPlaceContext) : Boolean {
        val item = context.itemInHand.item

        if(item !is BlockItem) {
            return false
        }

        if(item is PartItem) {
            return false
        }

        val blockPos = context.clickedPos
        val blockState = item.block.getStateForPlacement(context)
            ?: return false

        return intersectsPlacementBlock(context.level, blockPos, blockState)
    }

    fun intersectsPlacementBlock(level: Level, blockPos: BlockPos, blockState: BlockState) = blockState.getCollisionShape(level, blockPos).toBoxList().any {
        intersects(level, it.move(blockPos).cast())
    }

    fun cablePlacementIntersects(level: Level, cable: Cable3dA, h1: GridTerminalHandle, h2: GridTerminalHandle) : Boolean {
        return intersectRange(level, cable) {
            if(it == h1.gameObject || it == h2.gameObject) {
                return@intersectRange true
            }

            if(it is MultiblockDelegateBlockEntity && it.representativePos != null) {
                val representative = level.getBlockEntity(it.representativePos!!)

                if(representative == h1.gameObject || representative == h2.gameObject) {
                    return@intersectRange true
                }
            }

            return@intersectRange false
        }
    }

    private fun intersectRange(level: Level, cable: Cable3dA, skip: (gameObject: Any) -> Boolean) : Boolean {
        for (cylinder in cable.segments) {
            val segmentAligned = BoundingBox3d.fromCylinder(cylinder)

            for (blockPos in BlockPos.betweenClosed(
                segmentAligned.min.floorBlockPos(),
                segmentAligned.max.ceilBlockPos()
            )) {
                val voxelBoundingBox = AABB(blockPos).cast()

                if(!cylinder.intersectsWith(voxelBoundingBox)) {
                    continue
                }

                val state = level.getBlockState(blockPos)

                if(state.isAir) {
                    continue
                }

                if(state.block is MultipartBlock) {
                    val multipart = level.getBlockEntity(blockPos) as? MultipartBlockEntity
                        ?: continue

                    for (part in multipart.parts.values) {
                        if(part is SpecContainerPart) {
                            for(spec in part.specs.values) {
                                if(skip(spec)) {
                                    continue
                                }

                                if(cylinder intersectsWith spec.placement.orientedBoundingBoxWorld) {
                                    return true
                                }
                            }
                        }
                        else {
                            if(skip(part)) {
                                continue
                            }

                            if(cylinder intersectsWith part.worldBoundingBox.cast()) {
                                return true
                            }
                        }
                    }
                }
                else {
                    val blockEntity = level.getBlockEntity(blockPos)

                    if(blockEntity != null && skip(blockEntity)) {
                        continue
                    }

                    for (boxModel in state.getCollisionShape(level, blockPos).toBoxList()) {
                        val box = boxModel.move(blockPos)

                        if(cylinder intersectsWith box.cast()) {
                            return true
                        }
                    }
                }
            }
        }

        return false
    }

    fun pick(level: Level, line: Line3d) = if(level.isClientSide) {
        GridConnectionManagerClient.pick(line)
    }
    else {
        GridConnectionManagerServer.pick(level as ServerLevel, line)
    }
}
