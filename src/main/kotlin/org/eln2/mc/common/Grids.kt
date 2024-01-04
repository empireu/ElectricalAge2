package org.eln2.mc.common

import it.unimi.dsi.fastutil.doubles.Double2DoubleOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ChunkBufferBuilderPack
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher
import net.minecraft.client.renderer.chunk.RenderChunkRegion
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraftforge.network.NetworkEvent
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.*
import org.ageseries.libage.mathematics.geometry.*
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.electrical.mna.VirtualResistor
import org.eln2.mc.*
import org.eln2.mc.client.render.*
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.cells.CellRegistry
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.network.Networking
import org.eln2.mc.common.specs.foundation.GridTerminalSystem
import org.eln2.mc.common.specs.foundation.MicroGridCellTerminal
import org.eln2.mc.common.specs.foundation.MicroGridTerminalServer
import org.eln2.mc.data.Locators
import org.eln2.mc.data.SortedUUIDPair
import org.eln2.mc.extensions.*
import org.eln2.mc.mathematics.floorBlockPos
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.PI

enum class GridMaterialCategory {
    MicroGrid,
    BIG
}

/**
 * Grid connection material.
 * @param spriteSupplier Supplier for the texture. It must be in the block atlas.
 * @param vertexColor Per-vertex color, applied when rendering.
 * @param physicalMaterial The physical properties of the grid cable.
 * @param shape The shape of the cable. This is either [Straight] or [Catenary].
 * */
class GridMaterial(
    private val spriteSupplier: Supplier<TextureAtlasSprite>,
    val vertexColor: RGBFloat,
    val physicalMaterial: Material,
    val shape: Shape,
    val category: GridMaterialCategory
) {
    val id get() = GridMaterials.getId(this)

    val sprite get() = spriteSupplier.get()

    private val factory = when(shape) {
        is Catenary -> {
            { a: Vector3d, b: Vector3d ->
                Cable3dA.Catenary(
                    a, b,
                    shape.circleVertices, shape.radius,
                    shape.splitDistanceHint, shape.splitParameterHint,
                    shape.slack,
                    shape.splitRotIncrementMax
                )
            }
        }
        is Straight -> {
            { a: Vector3d, b: Vector3d ->
                Cable3dA.Straight(
                    a, b,
                    shape.circleVertices, shape.radius,
                    shape.splitDistanceHint, shape.splitParameterHint
                )
            }
        }
        else -> error("Invalid shape $shape")
    }

    abstract class Shape(
        val circleVertices: Int,
        val radius: Double,
        val splitDistanceHint: Double,
        val splitParameterHint: Double
    )

    class Catenary(
        circleVertices: Int,
        radius: Double,
        splitDistanceHint: Double,
        splitParameterHint: Double,
        val slack: Double = 0.01,
        val splitRotIncrementMax: Double = PI / 16.0
    ) : Shape(circleVertices, radius, splitDistanceHint, splitParameterHint)

    class Straight(
        circleVertices: Int,
        radius: Double,
        splitDistanceHint: Double,
        splitParameterHint: Double
    ) : Shape(circleVertices, radius, splitDistanceHint, splitParameterHint)

    fun create(a: Vector3d, b: Vector3d) = factory(a, b)
}

object GridMaterials {
    private val atlas by lazy {
        Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
    }

    private val materials = MutableMapPairBiMap<GridMaterial, ResourceLocation>()

    private fun gridAtlasSprite(name: String) = Supplier {
        checkNotNull(atlas.apply(resource("grid/$name"))) {
            "Did not find $name"
        }
    }

    private val COPPER = gridAtlasSprite("copper_cable")

    private val BIG_GRID_SHAPE = GridMaterial.Catenary(
        8, 0.1,
        2.0 * PI * 0.1, 0.25
    )

    private val MICRO_GRID_SHAPE = GridMaterial.Straight(
        8, 0.03,
        2.0 * PI * 0.1, 1.0
    )

    // what the frak is this name?
    val COPPER_POWER_GRID = register(
        "copper_power",
        GridMaterial(
            COPPER,
            RGBFloat(1f, 1f, 1f),
            ChemicalElement.Copper.asMaterial,
            BIG_GRID_SHAPE,
            GridMaterialCategory.BIG
        )
    )

    val COPPER_MICRO_GRID = register(
        "copper_micro",
        GridMaterial(
            COPPER,
            RGBFloat(1f, 1f, 1f),
            ChemicalElement.Copper.asMaterial,
            MICRO_GRID_SHAPE,
            GridMaterialCategory.MicroGrid
        )
    )

    fun register(id: ResourceLocation, material: GridMaterial) = material.also { materials.add(it, id) }
    private fun register(id: String, material: GridMaterial) = register(resource(id), material)
    fun getId(material: GridMaterial) : ResourceLocation = materials.forward[material] ?: error("Failed to get grid material id $material")
    fun getMaterial(resourceLocation: ResourceLocation) : GridMaterial = materials.backward[resourceLocation] ?: error("Failed to get grid material $resourceLocation")
}

/**
 * [Cable3dA] with extra information needed for syncing and state management.
 * @param id The unique ID of the connection. This is used for replication to clients.
 * @param cable The 3D model data.
 * @param material The physical properties of the grid cable.
 * */
data class GridConnectionCable(val id: Int, val cable: Cable3dA, val material: GridMaterial) {
    constructor(catenary: Cable3dA, material: GridMaterial) : this(getUniqueId(), catenary, material)

    /**
     * Gets the electrical resistance over the entire length of the cable.
     * */
    val resistance get() = !material.physicalMaterial.electricalResistivity * (cable.arcLength / cable.crossSectionArea)

    fun toNbt() = CompoundTag().also {
        it.putInt(ID, id)
        it.put(CATENARY, cable.toNbt())
        it.putResourceLocation(MATERIAL, GridMaterials.getId(material))
    }

    companion object {
        private const val ID = "id"
        private const val CATENARY = "catenary"
        private const val MATERIAL = "material"

        fun fromNbt(tag: CompoundTag) = GridConnectionCable(
            tag.getInt(ID),
            Cable3dA.fromNbt(tag.get(CATENARY) as CompoundTag),
            GridMaterials.getMaterial(tag.getResourceLocation(MATERIAL))
        )
    }
}

interface GridConnectionHandle {
    val connection: GridConnectionCable
    val level: ServerLevel

    fun destroy()
}

// Required that attachment and locator do not change if ID does not change
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

data class GridConnectionCreateMessage(val connection: GridConnectionCable) {
    companion object {
        fun encode(message: GridConnectionCreateMessage, buf: FriendlyByteBuf) {
            buf.writeNbt(message.connection.toNbt())
        }

        fun decode(buf: FriendlyByteBuf) = GridConnectionCreateMessage(
            GridConnectionCable.fromNbt(buf.readNbt()!!)
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

@ServerOnly
object GridConnectionManagerServer {
    fun createCable(pair: GridEndpointPair, material: GridMaterial) = GridConnectionCable(
        material.create(
            pair.a.attachment,
            pair.b.attachment
        ),
        material
    )

    private val levels = HashMap<ServerLevel, LevelGridData>()

    private fun validateUsage() {
        requireIsOnServerThread { "Grid server must be on server thread" }
    }

    fun clear() {
        validateUsage()
        levels.clear()
    }

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

    fun createPairIfAbsent(level: ServerLevel, pair: GridEndpointPair, material: GridMaterial) : GridConnectionCable = invoke(level) {
        createPairIfAbsent(pair, material)
    }

    fun createPair(level: ServerLevel, pair: GridEndpointPair, connection: GridConnectionCable) = invoke(level) {
        createPair(pair, connection)
    }

    fun removeEndpointById(level: ServerLevel, endpointId: UUID) = invoke(level) {
        removeEndpointById(endpointId)
    }

    fun clipsBlock(level: ServerLevel, blockPos: BlockPos) : Boolean = invoke(level) {
        clips(blockPos)
    }

    class LevelGridData(val level: ServerLevel) {
        private val handles = HashSet<Handle>()
        private val handlesByChunk = MutableSetMapMultiMap<ChunkPos, Handle>()
        private val watchedChunksByPlayer = MutableSetMapMultiMap<ServerPlayer, ChunkPos>()

        private val pairMap = PairMap()
        private val handlesByPair = HashMap<GridEndpointPair, GridConnectionHandle>()

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
                    handle.removePlayer(player, chunkPos)
                }
            }
        }

        private fun sendConnection(player: ServerPlayer, handle: Handle) {
            Networking.send(GridConnectionCreateMessage(handle.connection), player)
        }

        private fun sendDeletedConnection(player: ServerPlayer, handle: Handle) {
            Networking.send(GridConnectionDeleteMessage(handle.connection.id), player)
        }

        fun clips(blockPos: BlockPos) : Boolean {
            for (handle in handlesByChunk[ChunkPos(blockPos)]) {
                if(handle.connection.cable.blocks.contains(blockPos)) {
                    return true
                }
            }

            return false
        }

        private fun createHandle(connection: GridConnectionCable) : GridConnectionHandle {
            val handle = Handle(connection)

            handles.add(handle)

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

        fun createPair(pair: GridEndpointPair, connection: GridConnectionCable) {
            pairMap.addPair(pair)
            handlesByPair[pair] = createHandle(connection)
        }

        fun createPairIfAbsent(pair: GridEndpointPair, material: GridMaterial) : GridConnectionCable {
            if(pairMap.hasPair(pair)) {
                return checkNotNull(handlesByPair[pair]) { "Lingering pair in pair map" }.connection
            }

            val result = createCable(pair, material)

            createPair(pair, result)

            return result
        }

        fun removeEndpointById(endPointId: UUID) {
            pairMap.removePairsById(endPointId).forEach { pair ->
                handlesByPair.remove(pair)!!.destroy()
            }
        }

        private inner class Handle(override val connection: GridConnectionCable) : GridConnectionHandle {
            private val players = MutableSetMapMultiMap<ServerPlayer, ChunkPos>()

            fun addPlayer(player: ServerPlayer, chunkPos: ChunkPos) : Boolean {
                val result = !players.contains(player)
                players[player].add(chunkPos)
                return result
            }

            fun removePlayer(player: ServerPlayer, chunkPos: ChunkPos) = players.remove(player, chunkPos)

            override val level: ServerLevel
                get() = this@LevelGridData.level

            override fun destroy() {
                validateUsage()

                if(handles.remove(this)) {
                    connection.cable.chunks.keys.forEach { chunk ->
                        handlesByChunk[chunk].remove(this)
                    }

                    players.keys.forEach { player ->
                        sendDeletedConnection(player, this)
                    }
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
                check(pairs.add(pair))
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

@ClientOnly
object GridConnectionManagerClient {
    private val lock = ReentrantReadWriteLock()
    private val slicesByConnection = MutableSetMapMultiMap<Int, ConnectionSectionSlice>()
    private val slicesBySection = MutableSetMapMultiMap<SectionPos, ConnectionSectionSlice>()

    fun clear() {
        lock.write {
            slicesByConnection.map.clear()
            slicesBySection.map.clear()
        }
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

    fun addConnection(connection: GridConnectionCable) {
        val sections = HashSet<SectionPos>()

        lock.write {
            val catenary = connection.cable
            val (extrusion, quads) = catenary.mesh()
            val sprite = connection.material.sprite

            LOG.info("Generated ${quads.size} quads")

            val uCoordinates = scanUProgression(
                extrusion,
                catenary,
                sprite.u0.toDouble(),
                sprite.u1.toDouble()
            )

            val v0 = sprite.v0.toDouble()
            val v1 = sprite.v1.toDouble()

            val slicesBySection = HashMap<SectionPos, ConnectionSectionSlice>()

            fun getSlice(blockPos: BlockPos) : ConnectionSectionSlice {
                val section = SectionPos.of(blockPos)

                return slicesBySection.computeIfAbsent(section) {
                    ConnectionSectionSlice(connection.material, section)
                }
            }

            quads.forEach { quad ->
                val processedQuad = Quad()

                quad.vertices.forEachIndexed { vertexIndex, vertex ->
                    val orientation = extrusion.rmfLookup.get(vertex.param)!!.rotation
                    val phase = vertex.normal angle orientation.invoke().c2

                    val u = uCoordinates.get(vertex.param)
                    val v = map(phase, -PI, PI, v0, v1)

                    processedQuad.positions[vertexIndex] = vertex.position
                    processedQuad.normals[vertexIndex] = vertex.normal
                    processedQuad.uvs[vertexIndex] = Pair(u.toFloat(), v.toFloat())
                }

                val sectionData = getSlice(quad.principal)

                sectionData.quads.add(processedQuad)
            }

            connection.cable.blocks.forEach { block ->
                val slice = getSlice(block)

                slice.blocks.add(block)
            }

            slicesBySection.forEach { (sectionPos, sectionSlice) ->
                slicesByConnection[connection.id].add(sectionSlice)
                this.slicesBySection[sectionPos].add(sectionSlice)
                sections.add(sectionPos)
            }
        }

        sections.forEach {
            setDirty(it)
        }
    }

    fun removeConnection(id: Int) {
        lock.write {
            slicesByConnection[id].forEach { slice ->
                slicesBySection[slice.sectionPos].remove(slice)
                setDirty(slice.sectionPos)
            }

            slicesByConnection.clear(id)
        }
    }
    // Kind of waiting on this lock when parallel meshing, what to do?
    // Maybe add a concurrent object pool (to libage), get a temporary array list,
    // fill it on the lock, and then exit and call user with
    // the stuff in the array list and then return to pool
    @JvmStatic
    fun read(sectionPos: SectionPos, user: (GridMaterial, Quad) -> Unit) {
        lock.read {
            slicesBySection[sectionPos].forEach { slice ->
                slice.quads.forEach { quad ->
                    user(slice.material, quad)
                }
            }
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

    // todo optimize storage

    class Quad {
        val positions = Array(4) { Vector3d.zero }
        val normals = Array(4) { Vector3d.zero }
        val uvs = Array(4) { Pair(0f, 0f) }
    }

    private class ConnectionSectionSlice(val material: GridMaterial, val sectionPos: SectionPos) {
        val quads = ArrayList<Quad>()
        val blocks = HashSet<BlockPos>()

        val isVisual get() = quads.isNotEmpty()
    }
}

object GridCollisions {
    /**
     * Checks if the grid intersects the voxel at [blockPos].
     * */
    @JvmStatic
    fun collidesBlock(level: Level, blockPos: BlockPos) = if(level.isClientSide) {
        GridConnectionManagerClient.clipsBlock(blockPos)
    } else {
        GridConnectionManagerServer.clipsBlock(level as ServerLevel, blockPos)
    }
}

/**
 * Encapsulates information about a grid connection to a remote end point.
 * @param material The material of the grid connection.
 * @param resistance The electrical resistance of the connection, a value dependent on the physical configuration of the game object.
 * */
data class GridConnectionDescription(val material: GridMaterial, val resistance: Double)

@FunctionalInterface
fun interface GridRendererVertexConsumer {
    fun vertex(
        pX: Float, pY: Float, pZ: Float,
        pRed: Float, pGreen: Float, pBlue: Float,
        pTexU: Float, pTexV: Float,
        pOverlayUV: Int, pLightmapUV: Int,
        pNormalX: Float, pNormalY: Float, pNormalZ: Float
    )
}

object GridRenderer {
    @JvmStatic
    fun submitForRebuildSection(
        pRenderChunk: ChunkRenderDispatcher.RenderChunk,
        pChunkBufferBuilderPack: ChunkBufferBuilderPack,
        pRenderChunkRegion: RenderChunkRegion,
        pRenderTypeSet: MutableSet<RenderType>,
    ) {
        val renderType = RenderType.solid()
        val vertexConsumer = pChunkBufferBuilderPack.builder(renderType)

        if(pRenderTypeSet.add(renderType)) {
            pRenderChunk.beginLayer(vertexConsumer)
        }

        val lightReader = CachingLightReader(pRenderChunkRegion)
        val neighborLights = NeighborLightReader(lightReader)
        val section = SectionPos.of(pRenderChunk.origin)

        submitSection(section, lightReader, neighborLights) { pX, pY, pZ, pRed, pGreen, pBlue, pTexU, pTexV, pOverlayUV, pLightmapUV, pNormalX, pNormalY, pNormalZ ->
            vertexConsumer.vertex(
                pX, pY, pZ,
                pRed, pGreen, pBlue, 1.0f,
                pTexU, pTexV,
                pOverlayUV, pLightmapUV,
                pNormalX, pNormalY, pNormalZ
            )
        }
    }

    @JvmStatic
    fun submitSection(section: SectionPos, lightReader: CachingLightReader, neighborLights: NeighborLightReader, consumer: GridRendererVertexConsumer) {
        val originX = section.minBlockX()
        val originY = section.minBlockY()
        val originZ = section.minBlockZ()

        GridConnectionManagerClient.read(section) { material, quad ->
            for (i in 0 until 4) {
                val position = quad.positions[i]
                val blockPosition = position.floorBlockPos()

                val localLight = lightReader.getLightColor(blockPosition)
                val localBlockLight = unpackBlockLight(localLight).toDouble()
                val localSkyLight = unpackSkyLight(localLight).toDouble()

                neighborLights.load(blockPosition)

                val normal = quad.normals[i]
                val normalX = normal.x.toFloat()
                val normalY = normal.y.toFloat()
                val normalZ = normal.z.toFloat()

                val (u, v) = quad.uvs[i]

                val light = LightTexture.pack(
                    combineLight(0, neighborLights, normal, localBlockLight),
                    combineLight(1, neighborLights, normal, localSkyLight)
                )

                val xSection = (position.x - originX).toFloat()
                val ySection = (position.y - originY).toFloat()
                val zSection = (position.z - originZ).toFloat()

                val (r, g, b) = material.vertexColor

                consumer.vertex(
                    xSection, ySection, zSection,
                    r, g, b,
                    u, v,
                    OverlayTexture.NO_OVERLAY, light,
                    normalX, normalY, normalZ
                )
            }
        }
    }
}

/**
 * Models a cable using an arclength-parameterized catenary ([ArcReparamCatenary3d]) or a straight tube ([LinearSplineSegment3d])
 * @param a First support point.
 * @param b Second support point.
 * @param circleVertices The number of vertices in the mesh of a circle cross-section.
 * @param radius The radius of the cable (for rendering)
 * @param splitDistanceHint The maximum distance between consecutive vertex rings (for rendering to look ~good and to have enough segments to map the texture)
 * @param splitParameterHint The maximum parametric increments between consecutive vertex rings.
 * */
abstract class Cable3dA(
    val a: Vector3d,
    val b: Vector3d,
    val circleVertices: Int,
    val radius: Double,
    val splitDistanceHint: Double = 0.5,
    val splitParameterHint: Double = 0.1
) {
    /**
     * Gets the circumference of the tube, according to [radius].
     * */
    val circumference = 2.0 * PI * radius

    /**
     * Gets the surface area of a cross-section.
     * */
    val crossSectionArea = PI * radius * radius

    /**
     * Gets the supports [a] and [b], sorted in ascending order by their vertical coordinate.
     * */
    val supports = listOf(a, b).sortedBy { it.y }

    /**
     * Gets the arc length of the cable.
     * */
    abstract val arcLength: Double

    /**
     * Gets the spline that characterises the wire.
     * */
    abstract val spline: Spline3d

    /**
     * Gets a set of blocks that are intersected by the spline.
     * */
    abstract val blocks: HashSet<BlockPos>

    /**
     * Gets a multimap of blocks intersected by the spline, and the chunks they belong to.
     * */
    abstract val chunks: MultiMap<ChunkPos, BlockPos>

    protected fun sketchCrossSection() = sketchCircle(circleVertices, radius)

    fun toNbt(): CompoundTag {
        val tag = CompoundTag()

        tag.putVector3d(A, a)
        tag.putVector3d(B, b)
        tag.putInt(CIRCLE_VERTICES, circleVertices)
        tag.putDouble(RADIUS, radius)
        tag.putDouble(SPLIT_DISTANCE_HINT, splitDistanceHint)
        tag.putDouble(SPLIT_PARAMETER_HINT, splitParameterHint)

        val type = Type.determine(this)
        tag.putInt(TYPE, type.ordinal)

        when(type) {
            Type.Catenary -> {
                this as Catenary

                tag.putDouble(SLACK, slack)
                tag.putDouble(SPLIT_ROT_INCR_MAX, splitRotIncrementMax)
            }
            Type.Straight -> {
                // empty
            }
        }

        return tag
    }

    abstract fun mesh() : CableMesh3d

    private enum class Type {
        Catenary,
        Straight;

        companion object {
            fun determine(instance: Cable3dA) = when(instance) {
                is Cable3dA.Catenary -> Catenary
                is Cable3dA.Straight -> Straight
                else -> error("Invalid cable 3d implementation $instance")
            }
        }
    }

    companion object {
        private const val A = "a"
        private const val B = "b"
        private const val CIRCLE_VERTICES = "circleVertices"
        private const val RADIUS = "radius"
        private const val TYPE = "type"

        private const val SLACK = "slack"
        private const val SPLIT_DISTANCE_HINT = "splitDistanceHint"
        private const val SPLIT_PARAMETER_HINT = "splitParameterHint"
        private const val SPLIT_ROT_INCR_MAX = "splitRotIncrMax"

        fun fromNbt(tag: CompoundTag) : Cable3dA {
            val a = tag.getVector3d(A)
            val b = tag.getVector3d(B)
            val circleVertices = tag.getInt(CIRCLE_VERTICES)
            val radius = tag.getDouble(RADIUS)
            val splitDistanceHint = tag.getDouble(SPLIT_DISTANCE_HINT)
            val splitParameterHint = tag.getDouble(SPLIT_PARAMETER_HINT)

            return when(Type.entries[tag.getInt(TYPE)]) {
                Type.Catenary -> {
                    Catenary(
                        a, b,
                        circleVertices, radius,
                        splitDistanceHint, splitParameterHint,
                        tag.getDouble(SLACK), tag.getDouble(SPLIT_ROT_INCR_MAX)
                    )
                }
                Type.Straight -> {
                    Straight(
                        a, b,
                        circleVertices, radius,
                        splitDistanceHint, splitParameterHint,
                    )
                }
            }
        }

        private fun getBlocksFromSpline(radius: Double, spline: Spline3d) : HashSet<BlockPos> {
            val blocks = HashSet<BlockPos>()
            val radiusSqr = radius * radius

            require(
                spline.intersectGrid3d(0.0, 1.0, 0.1, 1024 * 1024) {
                    val ordinate = spline.evaluate(it)
                    val block = ordinate.floorBlockPos()

                    if(blocks.add(block)) {
                        for(i in -1..1) {
                            val x = block.x + i

                            for(j in -1..1) {
                                val y = block.y + j

                                for (k in -1..1) {
                                    if(i == 0 && j == 0 && k == 0) {
                                        continue
                                    }

                                    val z = block.z + k

                                    val dx = ordinate.x - ordinate.x.coerceIn(x.toDouble(), x + 1.0)
                                    val dy = ordinate.y - ordinate.y.coerceIn(y.toDouble(), y + 1.0)
                                    val dz = ordinate.z - ordinate.z.coerceIn(z.toDouble(), z + 1.0)

                                    val distanceSqr = dx * dx + dy * dy + dz * dz

                                    if(distanceSqr < radiusSqr) {
                                        blocks.add(BlockPos(x, y, z))
                                    }
                                }
                            }
                        }
                    }

                }
            ) { "Failed to intersect $this" }

            return blocks
        }

        private fun getChunksFromBlocks(blocks: HashSet<BlockPos>) = blocks.associateByMulti { ChunkPos(it) }

        private fun meshExtrusion(spline: Spline3d, extrusion: SketchExtrusion) : CableMesh3d {
            val quads = ArrayList<CableQuad3d>()

            val mesh = extrusion.mesh

            mesh.quadScan { baseQuad ->
                val ptvVerticesParametric = baseQuad.indices.map { mesh.vertices[it] }
                val ptvVerticesPositions = ptvVerticesParametric.map { it.value }

                val ptvCenter = avg(ptvVerticesPositions)
                val ptvParam = avg(ptvVerticesParametric.map { it.t })
                val ordinate = spline.evaluate(ptvParam)
                val ptvNormal = (ptvCenter - ordinate).normalized()
                val ptvNormalWinding = polygralScan(ptvCenter, ptvVerticesPositions).normalized()

                val ptv = if((ptvNormal o ptvNormalWinding) > 0.0) baseQuad
                else baseQuad.rewind()

                fun vert(vertexId: Int) : CableVertex3d {
                    val vertexParametric = mesh.vertices[vertexId]
                    val vertexPosition = vertexParametric.value
                    val vertexNormal = (vertexPosition - (spline.evaluate(vertexParametric.t))).normalized()

                    return CableVertex3d(vertexPosition, vertexNormal, vertexParametric.t)
                }

                val vertices = listOf(vert(ptv.a), vert(ptv.b), vert(ptv.c), vert(ptv.d))

                quads.add(CableQuad3d(ordinate.floorBlockPos(), vertices))
            }

            return CableMesh3d(extrusion, quads)
        }
    }

    /**
     * Models a cable using an arclength-parameterized catenary ([ArcReparamCatenary3d]) or a straight tube ([LinearSplineSegment3d]), if the catenary cannot be used (is degenerate).
     * @param a First support point.
     * @param b Second support point.
     * @param circleVertices The number of vertices in the mesh of a circle cross-section.
     * @param radius The radius of the cable (for rendering)
     * @param splitDistanceHint The maximum distance between consecutive vertex rings (for rendering to look ~good and to have enough segments to map the texture)
     * @param splitParameterHint The maximum parametric increments between consecutive vertex rings.
     * @param slack Cable slack. Arclength will be d(a, b) * (1 + slack).
     * @param splitRotIncrementMax Maximum tangent deviation between consecutive rings.
     * */
    class Catenary(
        a: Vector3d,
        b: Vector3d,
        circleVertices: Int,
        radius: Double,
        splitDistanceHint: Double,
        splitParameterHint: Double,
        val slack: Double,
        val splitRotIncrementMax: Double
    ) : Cable3dA(a, b, circleVertices, radius, splitDistanceHint, splitParameterHint) {
        override val arcLength: Double
        override val spline: Spline3d
        override val blocks: HashSet<BlockPos>
        override val chunks: MultiMap<ChunkPos, BlockPos>

        /**
         * True, if the connection was represented as a catenary.
         * Otherwise, the connection was represented as a linear segment. This may happen if the catenary is degenerate and cannot be modeled.
         * */
        val isCatenary: Boolean

        init {
            val distance = a..b
            val catenaryLength = distance * (1.0 + slack)

            val catenarySegment = ArcReparamCatenarySegment3d(
                t0 = 0.0,
                t1 = 1.0,
                p0 = supports[0],
                p1 = supports[1],
                length = catenaryLength,
                Vector3d.unitY
            )

            if(catenarySegment.catenary.matchesParameters()) {
                isCatenary = true
                spline = Spline3d(catenarySegment)
                arcLength = catenaryLength // ~approximately
            }
            else {
                isCatenary = false
                spline = Spline3d(
                    LinearSplineSegment3d(
                        t0 = 0.0,
                        t1 = 1.0,
                        p0 = supports[0],
                        p1 = supports[1],
                    )
                )
                arcLength = distance
            }

            blocks = getBlocksFromSpline(radius, spline)
            chunks = getChunksFromBlocks(blocks)
        }

        override fun mesh(): CableMesh3d {
            val samples = spline.adaptscan(
                0.0,
                1.0,
                splitParameterHint,
                condition = differenceCondition3d(
                    distMax = splitDistanceHint, //min(splitDistanceHint, circumference),
                    rotIncrMax = splitRotIncrementMax
                ),
                iMax = 1024 * 32 // way too generous...
            )

            checkNotNull(samples) {
                "Failed to get samples for catenary cable3d $this"
            }

            val extrusion = if(isCatenary) {
                extrudeSketchFrenet(
                    sketchCrossSection(),
                    spline,
                    samples
                )
            }
            else {
                Straight.linearExtrusion(
                    sketchCrossSection(),
                    spline,
                    samples,
                    supports
                )
            }

            return meshExtrusion(spline, extrusion)
        }

        override fun toString() =
            "from $a to $b, " +
            "slack=$slack, " +
            "splitDistance=$splitDistanceHint, " +
            "splitParam=$splitParameterHint, " +
            "splitRotIncrMax=$splitRotIncrementMax, " +
            "circleVertices=$circleVertices, " +
            "radius=$radius"
    }

    /**
     * Models a cable using a straight tube ([LinearSplineSegment3d])
     * @param a First support point.
     * @param b Second support point.
     * @param circleVertices The number of vertices in the mesh of a circle cross-section.
     * @param radius The radius of the cable (for rendering)
     * @param splitDistanceHint The maximum distance between consecutive vertex rings (for rendering to look ~good and to have enough segments to map the texture)
     * @param splitParameterHint The maximum parametric increments between consecutive vertex rings.
     * */
    class Straight(
        a: Vector3d,
        b: Vector3d,
        circleVertices: Int,
        radius: Double,
        splitDistanceHint: Double,
        splitParameterHint: Double,
    ) : Cable3dA(a, b, circleVertices, radius, splitDistanceHint, splitParameterHint) {
        override val arcLength: Double
        override val spline: Spline3d
        override val blocks: HashSet<BlockPos>
        override val chunks: MultiMap<ChunkPos, BlockPos>

        init {
            arcLength = a..b

            spline = Spline3d(
                LinearSplineSegment3d(
                    t0 = 0.0,
                    t1 = 1.0,
                    p0 = supports[0],
                    p1 = supports[1],
                )
            )

            blocks = getBlocksFromSpline(radius, spline)
            chunks = getChunksFromBlocks(blocks)
        }

        override fun mesh(): CableMesh3d {
            val splitDistanceMax = splitDistanceHint * splitDistanceHint

            val samples = spline.adaptscan(
                0.0,
                1.0,
                splitParameterHint,
                condition = { s, t0, t1 ->
                    val a = s.evaluate(t0)
                    val b = s.evaluate(t1)

                    (a distanceToSqr b) > splitDistanceMax

                },
                iMax = 1024 * 32 // way too generous...
            )

            checkNotNull(samples) {
                "Failed to get samples for linear cable3d $this"
            }

            val extrusion = linearExtrusion(sketchCrossSection(), spline, samples, supports)

            return meshExtrusion(spline, extrusion)
        }

        override fun toString() =
            "from $a to $b, " +
            "splitDistance=$splitDistanceHint, " +
            "splitParam=$splitParameterHint, " +
            "circleVertices=$circleVertices, " +
            "radius=$radius"

        companion object {
            fun linearExtrusion(
                crossSectionSketch: Sketch,
                spline: Spline3d,
                samples: ArrayList<Double>,
                supports: List<Vector3d>
            ) : SketchExtrusion {
                val t = (supports[1] - supports[0]).normalized()
                val n = t.perpendicular()
                val b = (t x n).normalized()

                val wx = Rotation3d.fromRotationMatrix(
                    Matrix3x3(
                        t, n, b
                    )
                )

                return extrudeSketch(
                    crossSectionSketch,
                    spline,
                    samples,
                    Pose3d(supports[0], wx),
                    Pose3d(supports[1], wx)
                )
            }
        }
    }
}

data class CableMesh3d(val extrusion: SketchExtrusion, val quads: ArrayList<CableQuad3d>)
data class CableQuad3d(val principal: BlockPos, val vertices: List<CableVertex3d>)
data class CableVertex3d(val position: Vector3d, val normal: Vector3d, val param: Double)

/**
 * Represents a cell that manages multiple grid terminals.
 * */
abstract class GridNodeCell(ci: CellCreateInfo) : Cell(ci) {
    private val terminalsInternal = MutableMapPairBiMap<Int, GridEndpointInfo>()

    /**
     * Gets the terminals used by this grid node cell. The integer is the terminal ID, and the UUID is the endpoint ID of the terminal.
     * */
    val terminals: BiMap<Int, GridEndpointInfo> get() = terminalsInternal

    /**
     * Takes the terminals from the [gridTerminalSystem] for usage within this cell.
     * This must be done once, when the cell is created. This data is saved and must be the same the next time the grid terminal system is created.
     * */
    fun mapFromGridTerminalSystem(gridTerminalSystem: GridTerminalSystem) {
        check(terminalsInternal.size == 0) {
            "Multiple map from grid terminal system"
        }

        gridTerminalSystem.getInstances().forEach { (terminalID, terminal) ->
            terminalsInternal.add(terminalID, terminal.gridEndpointInfo)
        }

        setChanged()
    }

    /**
     * Creates a bi-map of [GridConnectionCell]s to the [GridNodeCell]s that they are pairing with this cell.
     * This is only valid when [connections] are valid.
     * This is an allocation.
     * */
    fun getGridMapping() : BiMap<GridConnectionCell, GridNodeCell> {
        val results = MutableMapPairBiMap<GridConnectionCell, GridNodeCell>()

        connections.forEach {
            if (it is GridConnectionCell) {
                results.add(
                    it, if (it.cellA === this) {
                        it.cellB
                    } else if (it.cellB === this) {
                        it.cellA
                    } else {
                        error("Expected this cell to be one from the pair")
                    }
                )
            }
        }

        return results
    }

    /**
     * Destroys all [GridConnectionCell]s.
     * */
    override fun beginDestroy() {
        getGridMapping().forward.keys.forEach {
            it.isRemoving = true
            CellConnections.destroy(it, it.container!!)
        }
    }

    override fun saveCellData(): CompoundTag {
        val tag = CompoundTag()
        val listTag = ListTag()

        terminalsInternal.forward.forEach { (terminal, endpoint) ->
            val entryCompound = CompoundTag()

            entryCompound.putInt(TERMINAL, terminal)
            entryCompound.putUUID(ENDPOINT_ID, endpoint.id)
            entryCompound.putVector3d(ENDPOINT_ATTACHMENT, endpoint.attachment)
            entryCompound.putLocator(ENDPOINT_LOCATOR, endpoint.locator)

            listTag.add(entryCompound)
        }

        tag.put(TERMINALS, listTag)

        return tag
    }

    override fun loadCellData(tag: CompoundTag) {
        val listTag = tag.getListTag(TERMINALS)

        listTag.forEachCompound { entryCompound ->
            val terminal = entryCompound.getInt(TERMINAL)
            val endpointId = entryCompound.getUUID(ENDPOINT_ID)
            val attachment = entryCompound.getVector3d(ENDPOINT_ATTACHMENT)
            val endpointLocator = entryCompound.getLocator(ENDPOINT_LOCATOR)

            terminalsInternal.add(terminal, GridEndpointInfo(endpointId, attachment, endpointLocator))
        }
    }

    companion object {
        private const val TERMINALS = "entries"
        private const val TERMINAL = "terminal"
        private const val ENDPOINT_ID = "endpointID"
        private const val ENDPOINT_LOCATOR = "endpointLocator"
        private const val ENDPOINT_ATTACHMENT = "attachment"
    }
}

class GridConnectionElectricalObject(cell: GridConnectionCell) : ElectricalObject<GridConnectionCell>(cell) {
    private var resistorInternal : VirtualResistor? = null

    val resistor get() = checkNotNull(this.resistorInternal) {
        "Resistor was not set!"
    }

    fun initialize() {
        check(resistorInternal == null) {
            "Re-initialization of electrical grid connection object"
        }

        val resistor = VirtualResistor()
        resistor.resistance = cell.state.cable.resistance
        this.resistorInternal = resistor
    }

    override fun offerComponent(remote: ElectricalObject<*>): ElectricalComponentInfo {
        val remoteCell = remote.cell

        return if(remoteCell === cell.cellA) {
            resistor.offerPositive()
        }
        else if(remoteCell === cell.cellB) {
            resistor.offerNegative()
        }
        else {
            error("Unrecognised remote cell")
        }
    }
}

/**
 * Represents a connection between two [GridNodeCell]s. This is not owned by a game object; it is a containerless, game-object-less, positionless cell.
 * */
class GridConnectionCell(ci: CellCreateInfo) : Cell(ci) {
    private var stateField: State? = null

    var isRemoving = false

    val state get() = checkNotNull(stateField) {
        "Grid connection cell state was not initialized!"
    }

    private fun initializeFromStaging(
        level: ServerLevel,
        a: GridNodeCell, terminalA: Int,
        b: GridNodeCell, terminalB: Int,
        cable: GridConnectionCable
    ) {
        check(stateField == null) {
            "Multiple initializations"
        }

        check(container == null)

        container = StagingContainer(level, a, b)

        val endpointA = a.terminals.forward[terminalA]!!
        val endpointB = b.terminals.forward[terminalB]!!

        val pair = GridEndpointPair.create(endpointA, endpointB)

        stateField = if(pair.a == endpointA) {
            State(
                cable, pair,
                terminalA, terminalB
            )
        }
        else {
            State(
                cable, pair,
                terminalB, terminalA
            )
        }

        initializeObjects()
    }

    private var cellAField: GridNodeCell? = null
    private var cellBField: GridNodeCell? = null

    val cellA get() = checkNotNull(cellAField) {
        "Cell A was not initialized"
    }

    val cellB get() = checkNotNull(cellBField) {
        "Cell B was not initialized"
    }

    override fun buildStarted() {
        if(isRemoving) {
            return
        }

        check(connections.size == 2) {
            "Grid connection cell did not have the 2 connections!"
        }

        val cell1 = connections[0]
        val cell2 = connections[1]

        check(cell1 is GridNodeCell && cell2 is GridNodeCell) {
            "Connections were not grid terminal cells"
        }

        val cell1a = cell1.terminals.backward[state.pair.a]
        val cell1b = cell1.terminals.backward[state.pair.b]
        val cell2a = cell2.terminals.backward[state.pair.a]
        val cell2b = cell2.terminals.backward[state.pair.b]

        check(cell1a == null || cell1b == null) {
            "Cell 1 had both endpoints"
        }

        check(cell2a == null || cell2b == null) {
            "Cell 2 had both endpoints"
        }

        check(cell1a != null || cell2a != null) {
            "None of the cells had endpoint A"
        }

        check(cell1b != null || cell2b != null) {
            "None of the cells had endpoint B"
        }

        val state = this.state

        cellAField = if(cell1a != null) {
            check(cell1a == state.terminalA) {
                "Mismatched terminals"
            }
            cell1
        }
        else {
            check(cell2a == state.terminalA) {
                "Mismatched terminals"
            }
            cell2
        }

        cellBField = if(cell1b != null) {
            check(cell1b == state.terminalB) {
                "Mismatched terminals"
            }
            cell1
        }
        else {
            check(cell2b == state.terminalB) {
                "Mismatched terminals"
            }
            cell2
        }
    }

    private fun setPermanent() {
        this.container = PermanentContainer()
    }

    override fun onWorldLoadedPreSolver() {
        check(this.container == null)
        setPermanent()

        GridConnectionManagerServer.createPair(
            graph.level,
            state.pair,
            state.cable
        )
    }

    override fun saveCellData(): CompoundTag {
        val state = this.state

        val tag = CompoundTag()

        tag.put(PAIR, state.pair.toNbt())
        tag.putResourceLocation(MATERIAL, state.cable.material.id)
        tag.putInt(TERMINAL_A, state.terminalA)
        tag.putInt(TERMINAL_B, state.terminalB)

        return tag
    }

    override fun loadCellData(tag: CompoundTag) {
        check(stateField == null) {
            "Loading but already initialized!"
        }

        val pair = GridEndpointPair.fromNbt(tag.getCompound(PAIR))
        val material = GridMaterials.getMaterial(tag.getResourceLocation(MATERIAL))
        val terminalA = tag.getInt(TERMINAL_A)
        val terminalB = tag.getInt(TERMINAL_B)

        this.stateField = State(
            GridConnectionManagerServer.createCable(pair, material),
            pair,
            terminalA,
            terminalB
        )

        initializeObjects()
    }

    @SimObject
    val electrical = GridConnectionElectricalObject(this)

    private fun initializeObjects() {
        electrical.initialize()
    }

    data class State(
        val cable: GridConnectionCable,
        val pair: GridEndpointPair,
        val terminalA: Int,
        val terminalB: Int
    )

    inner class StagingContainer(val level: ServerLevel, val a: Cell, val b: Cell) : CellContainer {
        override fun getCells() = listOf(this@GridConnectionCell)

        override fun neighborScan(actualCell: Cell) = listOf(
            CellAndContainerHandle.captureInScope(a),
            CellAndContainerHandle.captureInScope(b)
        )

        override val manager = CellGraphManager.getFor(level)
    }

    inner class PermanentContainer : CellContainer {
        override fun getCells() = listOf(this@GridConnectionCell)

        override fun neighborScan(actualCell: Cell) = listOf(
            CellAndContainerHandle.captureInScope(this@GridConnectionCell.cellA),
            CellAndContainerHandle.captureInScope(this@GridConnectionCell.cellB)
        )

        override val manager: CellGraphManager
            get()  {
                check(this@GridConnectionCell.hasGraph) {
                    "Requires graph for permanent container"
                }

                return this@GridConnectionCell.graph.manager
            }
    }

    companion object {
        private const val MATERIAL = "material"
        private const val TERMINAL_A = "termA"
        private const val TERMINAL_B = "termB"
        private const val PAIR = "pair"

        fun createStaging(
            cellA: GridNodeCell,
            cellB: GridNodeCell,
            level: ServerLevel,
            endpointA: UUID,
            terminalA: Int,
            endpointB: UUID,
            terminalB: Int,
            cable: GridConnectionCable
        ) : GridConnectionCell {
            val locator =  Locators.buildLocator {
                it.put(GRID_ENDPOINT_PAIR, SortedUUIDPair.create(endpointA, endpointB))
                it.put(
                    BLOCK_RANGE, Pair(
                        cellA.locator.requireLocator(BLOCK) {
                            "Grid connection requires block locator A"
                        },
                        cellB.locator.requireLocator(BLOCK) {
                            "Grid connection requires block locator B"
                        }
                    )
                )
            }

            val cell = CellRegistry.GRID_CONNECTION.get().create(locator, CellEnvironment.evaluate(level, locator))

            cell.initializeFromStaging(
                level,
                cellA, terminalA,
                cellB, terminalB,
                cable
            )

            return cell
        }

        fun beginStaging(
            terminalA: MicroGridCellTerminal,
            terminalB: MicroGridCellTerminal,
            level: ServerLevel,
            cable: GridConnectionCable
        ) {
            terminalA as MicroGridTerminalServer
            terminalB as MicroGridTerminalServer

            val cell = createStaging(
                terminalA.cell,
                terminalB.cell,
                level,
                terminalA.gridEndpointInfo.id,
                terminalA.terminalID,
                terminalB.gridEndpointInfo.id,
                terminalB.terminalID,
                cable
            )

            terminalA.stagingCell = cell
            terminalB.stagingCell = cell
        }

        fun endStaging(
            terminalA: MicroGridCellTerminal,
            terminalB: MicroGridCellTerminal
        ) {
            check(terminalA.stagingCell === terminalB.stagingCell)
            val cell = checkNotNull(terminalA.stagingCell)
            check(cell.container is StagingContainer)
            CellConnections.insertFresh(cell.container!!, cell)
            terminalA.stagingCell = null
            terminalB.stagingCell = null
            cell.setPermanent()

            check(terminalA.cell.graph === terminalB.cell.graph) {
                "Staging failed - terminals did not have same graph"
            }

            check(terminalA.cell.graph === cell.graph) {
                "Staging failed - connection did not have expected graph"
            }
        }
    }
}
