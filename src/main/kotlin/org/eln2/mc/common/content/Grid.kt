package org.eln2.mc.common.content

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
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.*
import org.ageseries.libage.mathematics.geometry.*
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.VirtualResistor
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.*
import org.eln2.mc.client.render.*
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.blocks.foundation.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.network.Networking
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.PartRenderer
import org.eln2.mc.data.*
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.*
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.PI
import kotlin.math.abs

/**
 * Grid connection material.
 * @param spriteSupplier Supplier for the texture. It must be in the block atlas.
 * @param vertexColor Per-vertex color, applied when rendering.
 * @param physicalMaterial The physical properties of the grid cable.
 * */
class GridMaterial(
    private val spriteSupplier: Supplier<TextureAtlasSprite>,
    val vertexColor: RGBFloat,
    val physicalMaterial: Material,
    val shape: Shape
) {
    val id get() = GridMaterials.getId(this)

    val sprite get() = spriteSupplier.get()

    private val factory = when(shape) {
        is CatenaryShape -> {
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
        is StraightShape -> {
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

    class CatenaryShape(
        circleVertices: Int,
        radius: Double,
        splitDistanceHint: Double,
        splitParameterHint: Double,
        val slack: Double = 0.01,
        val splitRotIncrementMax: Double = PI / 16.0
    ) : Shape(circleVertices, radius, splitDistanceHint, splitParameterHint)

    class StraightShape(
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

    private val NEUTRAL = gridAtlasSprite("neutral_cable")
    private val COPPER = gridAtlasSprite("copper_cable")

    private val BIG_GRID_SHAPE = GridMaterial.CatenaryShape(
        8, 0.1,
        2.0 * PI * 0.1, 0.1
    )

    private val MICRO_GRID_SHAPE = GridMaterial.StraightShape(
        8, 0.025,
        2.0 * PI * 0.1, 0.1
    )

    // what the frak is this name?
    val COPPER_AS_COPPER_COPPER = register(
        "copper",
        GridMaterial(
            COPPER,
            RGBFloat(1f, 1f, 1f),
            ChemicalElement.Copper.asMaterial,
            BIG_GRID_SHAPE
        )
    )

    val COPPER_MICRO_GRID = register(
        "micro_copper",
        GridMaterial(
            COPPER,
            RGBFloat(1f, 1f, 1f),
            ChemicalElement.Copper.asMaterial,
            MICRO_GRID_SHAPE
        )
    )

    fun register(id: ResourceLocation, material: GridMaterial) = material.also { materials.add(it, id) }
    private fun register(id: String, material: GridMaterial) = register(resource(id), material)
    fun getId(material: GridMaterial) : ResourceLocation = materials.forward[material] ?: error("Failed to get grid material id $material")
    fun getMaterial(resourceLocation: ResourceLocation) : GridMaterial = materials.backward[resourceLocation] ?: error("Failed to get grid material $resourceLocation")
}

/**
 * [Cable3dA] with extra information needed by grids.
 * @param id The unique ID of the connection.
 * @param wireCatenary Catenary that models the physical connection.
 * @param material The physical properties of the grid cable.
 * */
data class GridConnectionCatenary(val id: Int, val wireCatenary: Cable3dA, val material: GridMaterial) {
    constructor(catenary: Cable3dA, material: GridMaterial) : this(getUniqueId(), catenary, material)

    /**
     * Gets the electrical resistance over the entire length of the cable.
     * */
    val resistance get() = !material.physicalMaterial.electricalResistivity * (wireCatenary.arcLength / wireCatenary.crossSectionArea)

    fun toNbt() = CompoundTag().also {
        it.putInt(ID, id)
        it.put(CATENARY, wireCatenary.toNbt())
        it.putResourceLocation(MATERIAL, GridMaterials.getId(material))
    }

    companion object {
        private const val ID = "id"
        private const val CATENARY = "catenary"
        private const val MATERIAL = "material"

        fun fromNbt(tag: CompoundTag) = GridConnectionCatenary(
            tag.getInt(ID),
            Cable3dA.fromNbt(tag.get(CATENARY) as CompoundTag),
            GridMaterials.getMaterial(tag.getResourceLocation(MATERIAL))
        )
    }
}

interface GridConnectionHandle {
    val connection: GridConnectionCatenary
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
        it.putLocatorSet(NBT_LOCATOR, locator)
    }

    companion object {
        private const val NBT_ID = "id"
        private const val NBT_ATTACHMENT = "attachment"
        private const val NBT_LOCATOR = "locator"

        fun fromNbt(tag: CompoundTag) = GridEndpointInfo(
            tag.getUUID(NBT_ID),
            tag.getVector3d(NBT_ATTACHMENT),
            tag.getLocatorSet(NBT_LOCATOR)
        )
    }
}

/**
 * Represents a sorted pair of [GridEndpointInfo].
 * */
class GridConnectionPair private constructor(val a: GridEndpointInfo, val b: GridEndpointInfo) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GridConnectionPair

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
        it.putLocatorSet(A_LOCATOR, a.locator)
        it.putLocatorSet(B_LOCATOR, b.locator)
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
        fun create(a: GridEndpointInfo, b: GridEndpointInfo) : GridConnectionPair {
            require(a.id != b.id) {
                "End points $a and $b have same UUID ${a.id}"
            }

            return if(a.id < b.id) {
                GridConnectionPair(a, b)
            }
            else {
                GridConnectionPair(b, a)
            }
        }

        fun fromNbt(tag: CompoundTag) = GridConnectionPair(
            GridEndpointInfo(
                tag.getUUID(A_UUID),
                tag.getVector3d(A_ATTACHMENT),
                tag.getLocatorSet(A_LOCATOR)
            ),
            GridEndpointInfo(
                tag.getUUID(B_UUID),
                tag.getVector3d(B_ATTACHMENT),
                tag.getLocatorSet(B_LOCATOR)
            )
        )
    }
}

data class GridConnectionCreateMessage(val connection: GridConnectionCatenary) {
    companion object {
        fun encode(message: GridConnectionCreateMessage, buf: FriendlyByteBuf) {
            buf.writeNbt(message.connection.toNbt())
        }

        fun decode(buf: FriendlyByteBuf) = GridConnectionCreateMessage(
            GridConnectionCatenary.fromNbt(buf.readNbt()!!)
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
    fun createGridCatenary(pair: GridConnectionPair, material: GridMaterial) = GridConnectionCatenary(
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

    fun createPairIfAbsent(level: ServerLevel, pair: GridConnectionPair, material: GridMaterial) : GridConnectionCatenary = invoke(level) {
        createPairIfAbsent(pair, material)
    }

    fun createPair(level: ServerLevel, pair: GridConnectionPair, connection: GridConnectionCatenary) = invoke(level) {
        createPair(pair, connection)
    }

    fun removeEndpointById(level: ServerLevel, endpointId: UUID) = invoke(level) {
        removeEndpointById(endpointId)
    }

    @JvmStatic
    fun clipsBlock(level: ServerLevel, blockPos: BlockPos) : Boolean = invoke(level) {
        clips(blockPos)
    }

    class LevelGridData(val level: ServerLevel) {
        private val handles = HashSet<Handle>()
        private val handlesByChunk = MutableSetMapMultiMap<ChunkPos, Handle>()
        private val watchedChunksByPlayer = MutableSetMapMultiMap<ServerPlayer, ChunkPos>()

        private val pairMap = PairMap()
        private val handlesByPair = HashMap<GridConnectionPair, GridConnectionHandle>()

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
                if(handle.connection.wireCatenary.blocks.contains(blockPos)) {
                    return true
                }
            }

            return false
        }

        private fun createHandle(connection: GridConnectionCatenary) : GridConnectionHandle {
            val handle = Handle(connection)

            handles.add(handle)

            connection.wireCatenary.chunks.keys.forEach { chunkPos ->
                handlesByChunk[chunkPos].add(handle)
            }

            watchedChunksByPlayer.map.forEach { (player, playerWatchedChunks) ->
                val intersectedChunks = HashSet<ChunkPos>()

                for (catenaryChunk in connection.wireCatenary.chunks.keys) {
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

        fun createPair(pair: GridConnectionPair, connection: GridConnectionCatenary) {
            pairMap.addPair(pair)
            handlesByPair[pair] = createHandle(connection)
        }

        fun createPairIfAbsent(pair: GridConnectionPair, material: GridMaterial) : GridConnectionCatenary {
            if(pairMap.hasPair(pair)) {
                return checkNotNull(handlesByPair[pair]) { "Lingering pair in pair map" }.connection
            }

            val result = createGridCatenary(pair, material)

            createPair(pair, result)

            return result
        }

        fun removeEndpointById(endPointId: UUID) {
            pairMap.removePairsById(endPointId).forEach { pair ->
                handlesByPair.remove(pair)!!.destroy()
            }
        }

        private inner class Handle(override val connection: GridConnectionCatenary) : GridConnectionHandle {
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
                    connection.wireCatenary.chunks.keys.forEach { chunk ->
                        handlesByChunk[chunk].remove(this)
                    }

                    players.keys.forEach { player ->
                        sendDeletedConnection(player, this)
                    }
                }
            }
        }

        private class PairMap {
            val pairs = HashSet<GridConnectionPair>()
            val pairsByEndpoint = MutableSetMapMultiMap<GridEndpointInfo, GridConnectionPair>()
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

            fun hasPair(pair: GridConnectionPair) = pairs.contains(pair)

            fun addPair(pair: GridConnectionPair) {
                check(pairs.add(pair))
                pairsByEndpoint[pair.a].add(pair)
                pairsByEndpoint[pair.b].add(pair)
                putId(pair.a)
                putId(pair.b)
            }

            fun removePair(pair: GridConnectionPair) {
                check(pairs.remove(pair))
                pairsByEndpoint[pair.a].remove(pair)
                pairsByEndpoint[pair.b].remove(pair)
                takeId(pair.a)
                takeId(pair.b)
            }

            fun getPairs(endPoint: GridEndpointInfo) = pairsByEndpoint[endPoint].toList()

            fun removePairsById(endpointId: UUID) : List<GridConnectionPair> {
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

    fun addConnection(connection: GridConnectionCatenary) {
        val sections = HashSet<SectionPos>()

        lock.write {
            val catenary = connection.wireCatenary
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

            connection.wireCatenary.blocks.forEach { block ->
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

    @JvmStatic
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

/**
 * Represents an electrical object that can make connections with other remote objects, to create a grid.
 * @param tapResistance The resistance of the connection between the grid and the neighboring objects. If null, then the object will not allow external connection.
 * */
class GridElectricalObject(cell: GridCell, val tapResistance: Double?) : ElectricalObject<GridCell>(cell) {
    private val gridResistors = HashMap<GridElectricalObject, VirtualResistor>()

    // Is this useful?
    val totalCurrent get() = gridResistors.values.sumOf { abs(it.current) }
    val totalPower get() = gridResistors.values.sumOf { abs(it.power) }

    // Reset on clear, isPresent -> Is connected
    private val tapResistor = UnsafeLazyResettable {
        check(tapResistance != null) {
            "Did not expect to create tap resistor"
        }

        val result = VirtualResistor()
        result.resistance = tapResistance
        result
    }

    override fun offerComponent(remote: ElectricalObject<*>) =
        if (remote is GridElectricalObject) {
            // Identify if adjacent or grid:
            val contact = cell.getGridContactResistance(remote)

            if(contact != null) {
                // Part of grid:
                gridResistors.computeIfAbsent(remote) {
                    val result = VirtualResistor()
                    result.resistance = contact / 2.0
                    result
                }.offerExternal()
            }
            else {
                tapResistor.value.offerExternal()
            }
        }
        else {
            tapResistor.value.offerExternal()
        }

    override fun clearComponents() {
        gridResistors.clear()
        tapResistor.reset()
    }

    override fun acceptsRemoteObject(remote: ElectricalObject<*>): Boolean {
        if(tapResistance != null) {
            return true
        }

        // Filter non-grid:
        if(remote is GridElectricalObject) {
            return cell.getGridContactResistance(remote) != null
        }

        return false
    }

    override fun build(map: ElectricalConnectivityMap) {
        // Connects grids to grids, and external to external:
        super.build(map)

        gridResistors.values.forEach { a ->
            gridResistors.values.forEach { b ->
                if(a != b) {
                    map.connect(
                        a,
                        INTERNAL_PIN,
                        b,
                        INTERNAL_PIN
                    )
                }
            }
        }

        if(tapResistor.isInitialized()) {
            check(tapResistance != null)

            // Connects grid to external:
            gridResistors.values.forEach { gridResistor ->
                map.connect(
                    gridResistor,
                    INTERNAL_PIN,
                    tapResistor.value,
                    INTERNAL_PIN
                )
            }
        }
    }
}

/**
 * Encapsulates information about a grid connection to a remote end point.
 * @param material The material of the grid connection.
 * @param resistance The electrical resistance of the connection, a value dependent on the physical configuration of the game object.
 * */
data class GridConnectionDescription(val material: GridMaterial, val resistance: Double)

/**
 * Encapsulates information about a grid connection that is in-progress.
 * @param remoteCell The remote grid cell, that may or may not be directly (physically) connected to the actual cell container.
 * @param properties The properties of the grid connection.
 * */
data class GridStagingInfo(val remoteCell: GridCell, val properties: GridConnectionDescription)

/**
 * Electrical-thermal cell that links power grids with standalone electrical circuits.
 * TODO -thermal
 * @param tapResistance The tap resistance passed to the [GridElectricalObject].
 * */
class GridCell(ci: CellCreateInfo, tapResistance: Double?) : Cell(ci) {
    @SimObject
    val electricalObject = GridElectricalObject(this, tapResistance)

    /**
     * Gets the connections to remote grid cells, and the properties of the respective connections.
     * All these connections are with cells that are in the same graph (they are recorded after staging)
     * */
    val endPoints = HashMap<GridEndpointInfo, GridConnectionDescription>()

    /**
     * Gets or sets the staging information, used to link two disjoint graphs, when a grid connection is being made by the player.
     * The remote cell is not part of this graph.
     * */
    var stagingInfo: GridStagingInfo? = null

    /**
     * Gets the resistance of the grid connection to [remoteObject].
     * @return The resistance of the grid connection or null, if the [remoteObject] is not connected via grid to this one.
     * */
    fun getGridContactResistance(remoteObject: GridElectricalObject) : Double? {
        val stagingInfo = this.stagingInfo

        if(stagingInfo != null) {
            if(stagingInfo.remoteCell.electricalObject == remoteObject) {
                return stagingInfo.properties.resistance
            }
        }

        val remoteEndPoint = endPoints.keys.firstOrNull { it.id == remoteObject.cell.endpointId }
            ?: return null

        return endPoints[remoteEndPoint]!!.resistance
    }

    // Set to another value when loading:
    var endpointId: UUID = UUID.randomUUID()
        private set

    /**
     * Cleans up the grid connections, by removing this end point from the remote end points.
     * */
    override fun onRemoving() {
        requireIsOnServerThread { // Maybe we'll have such a situation in the future...
            "OnRemoving grid is not on the server thread"
        }

        endPoints.keys.forEach { remoteEndPoint ->
            val remoteCell = graph.getCellByLocator(remoteEndPoint.locator)

            remoteCell as GridCell

            val localEndPoint = checkNotNull(remoteCell.endPoints.keys.firstOrNull { it.id == this.endpointId }) {
                "Failed to solve grid ${this.endPoints} $this"
            }

            check(remoteCell.endPoints.remove(localEndPoint) != null)
        }

        GridConnectionManagerServer.removeEndpointById(graph.level, this.endpointId)
    }

    override fun saveCellData() = CompoundTag().also {
        it.putUUID(ENDPOINT_ID, endpointId)

        val endpointList = ListTag()

        endPoints.forEach { (remoteEndPoint, info) ->
            val endpointCompound = CompoundTag()
            endpointCompound.put(REMOTE_END_POINT, remoteEndPoint.toNbt())
            endpointCompound.putResourceLocation(MATERIAL, info.material.id)
            endpointCompound.putDouble(RESISTANCE, info.resistance)
            endpointList.add(endpointCompound)
        }

        it.put(REMOTE_END_POINTS, endpointList)
    }

    override fun loadCellData(tag: CompoundTag) {
        endpointId = tag.getUUID(ENDPOINT_ID)

        tag.getListTag(REMOTE_END_POINTS).forEachCompound { endpointCompound ->
            val remoteEndPoint = GridEndpointInfo.fromNbt(endpointCompound.get(REMOTE_END_POINT) as CompoundTag)
            val material = GridMaterials.getMaterial(endpointCompound.getResourceLocation(MATERIAL))
            val resistance = endpointCompound.getDouble(RESISTANCE)
            endPoints.putUnique(remoteEndPoint, GridConnectionDescription(material, resistance))
        }
    }

    // Extra validation:
    override fun onLoadedFromDisk() {
        endPoints.keys.forEach { remoteEndPoint ->
            if(!graph.containsCellByLocator(remoteEndPoint.locator)) {
                LOG.error("Invalid end point $remoteEndPoint") // Break point here
            }
        }
    }

    companion object {
        private const val ENDPOINT_ID = "endpointId"
        private const val REMOTE_END_POINT = "remoteEndPoint"
        private const val MATERIAL = "material"
        private const val RESISTANCE = "resistance"
        private const val REMOTE_END_POINTS = "remoteEndPoints"
    }
}

private object GridCellOperations {
    fun setStaging(cell: GridCell, info: GridStagingInfo) {
        require(cell.stagingInfo == null) {
            "Cell $cell was already staging"
        }

        cell.stagingInfo = info
    }

    fun clearStaging(cell: GridCell) {
        require(cell.stagingInfo != null) {
            "Cell $cell was not staging when clear"
        }

        cell.stagingInfo = null
    }

    fun createEndpointInfo(cell: GridCell, attachment: Vector3d) = GridEndpointInfo(cell.endpointId, attachment, cell.locator)

    fun addExtraConnections(cell: GridCell, results: MutableSet<CellAndContainerHandle>) {
        if(cell.stagingInfo != null) {
            results.add(CellAndContainerHandle.captureInScope(cell.stagingInfo!!.remoteCell))
        }

        if(cell.hasGraph) {
            cell.endPoints.keys.forEach { remoteEndPoint ->
                results.add(
                    CellAndContainerHandle.captureInScope(
                        cell.graph.getCellByLocator(remoteEndPoint.locator)
                    )
                )
            }
        }
    }
}

interface GridEndpointContainer : ComponentDisplay {
    val gridCell: GridCell
    val gridAttachment: Vector3d
    val cellContainer: CellContainer

    fun setStaging(info: GridStagingInfo) = GridCellOperations.setStaging(gridCell, info)
    fun clearStaging() = GridCellOperations.clearStaging(gridCell)
    fun createEndpointInfo() = GridCellOperations.createEndpointInfo(gridCell, gridAttachment)

    fun lazyLoadConnection(level: Level) {
        if(!level.isClientSide) {
            level as ServerLevel

            val localEndPoint = createEndpointInfo()

            gridCell.endPoints.forEach { (remoteEndPoint, remoteEndPointInfo) ->
                val pair = GridConnectionPair.create(localEndPoint, remoteEndPoint)

                GridConnectionManagerServer.createPairIfAbsent(
                    level,
                    pair,
                    remoteEndPointInfo.material
                )
            }
        }
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        //builder.resistance(gridCell.electricalObject.tapResistance)
        builder.current(gridCell.electricalObject.totalCurrent)
        builder.power(gridCell.electricalObject.totalPower)
    }
}

abstract class GridCellPart<R : PartRenderer>(ci: PartCreateInfo, provider: CellProvider<GridCell>) :
    CellPart<GridCell, R>(ci, provider),
    ComponentDisplay,
    GridEndpointContainer
{
    override val gridCell: GridCell
        get() {
            check(hasCell) {
                "Tried to get grid part cell before it was present"
            }

            return this.cell
        }

    override val gridAttachment: Vector3d = placement.position.toVector3d() + Vector3d(0.5)

    override val cellContainer: CellContainer
        get() = placement.multipart

    override fun addExtraConnections(results: MutableSet<CellAndContainerHandle>) = GridCellOperations.addExtraConnections(cell, results)

    override fun onLoaded() {
        super.onLoaded()
        lazyLoadConnection(placement.level)
    }
}

class GridTapPart(ci: PartCreateInfo, provider: CellProvider<GridCell>) : GridCellPart<ConnectedPartRenderer>(ci, provider) {
    override val gridAttachment: Vector3d = super.gridAttachment - placement.face.vector3d * 0.15

    override fun createRenderer() = ConnectedPartRenderer(
        this,
        PartialModels.GRID_TAP_BODY,
        PartialModels.STANDARD_CONNECTION
    )

    override fun getSyncTag() = this.getConnectedPartTag()
    override fun handleSyncTag(tag: CompoundTag) = this.handleConnectedPartTag(tag)
    override fun onConnectivityChanged() = this.setSyncDirty()
}

class GridPoleBlock(val delegateMap: MultiblockDelegateMap, val attachment: Vector3d, private val cellProvider: RegistryObject<CellProvider<GridCell>>) : CellBlock<GridCell>() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun skipRendering(pState: BlockState, pAdjacentState: BlockState, pDirection: Direction): Boolean {
        return true
    }

    override fun getCellProvider() = cellProvider.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = GridPoleBlockEntity(this, pPos, pState)
}

class GridPoleBlockEntity(private val representativeBlock: GridPoleBlock, pos: BlockPos, state: BlockState) :
    CellBlockEntity<GridCell>(pos, state, Content.GRID_PASS_TROUGH_POLE_BLOCK_ENTITY.get()),
    BigBlockRepresentativeBlockEntity<GridPoleBlockEntity>,
    GridEndpointContainer
{
    override val gridCell: GridCell
        get() {
            check(hasCell) {
                "Tried to get grid pole cell before it was present"
            }

            return this.cell
        }

    override val gridAttachment: Vector3d
        get() = blockPos.toVector3d() + representativeBlock.attachment

    override val cellContainer: CellContainer
        get() = this

    override val delegateMap: MultiblockDelegateMap
        get() = representativeBlock.delegateMap

    override fun addExtraConnections(results: MutableSet<CellAndContainerHandle>) = GridCellOperations.addExtraConnections(cell, results)

    override fun onCellAcquired() {
        lazyLoadConnection(level!!)
    }

    override fun setDestroyed() {
        destroyDelegates()
        super.setDestroyed()
    }
}

open class GridConnectItem(val material: GridMaterial) : Item(Properties()) {
    private fun pickContainer(pLevel: Level, pPlayer: Player, hit: BlockHitResult) : GridEndpointContainer? {
        // make generic grid game object
        val targetBlockEntity = pLevel.getBlockEntity(hit.blockPos) ?: return null

        if(targetBlockEntity is MultipartBlockEntity) {
            return targetBlockEntity.pickPart(pPlayer) as? GridCellPart<*>
        }

        if(targetBlockEntity is MultiblockDelegateBlockEntity) {
            val representative = targetBlockEntity.representativePos ?: return null

            return pLevel.getBlockEntity(representative) as? GridEndpointContainer
        }

        return targetBlockEntity as? GridEndpointContainer
    }

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

        val hit = getPlayerPOVHitResult(pLevel, pPlayer, ClipContext.Fluid.SOURCE_ONLY)

        if (hit.type != HitResult.Type.BLOCK) {
            return fail("Cannot connect that!")
        }

        val targetContainer = pickContainer(pLevel, pPlayer, hit)
            ?: return fail("No valid endpoint selected!")

        if (actualStack.tag != null && actualStack.tag!!.contains(NBT_POS)) {
            val tag = actualStack.tag!!

            fun getRemoteContainer() : GridEndpointContainer? {
                val pos = tag.getBlockPos(NBT_POS)
                if(tag.contains(NBT_FACE)) {
                    val remoteMultipart = pLevel.getBlockEntity(pos) as? MultipartBlockEntity
                        ?: return null

                    return remoteMultipart.getPart(tag.getDirection(NBT_FACE)) as? GridEndpointContainer
                }
                else {
                    return pLevel.getBlockEntity(pos) as? GridEndpointContainer
                }
            }

            val remoteContainer = getRemoteContainer()
                ?: return fail("The remote end point has disappeared!")

            if(remoteContainer === targetContainer) {
                return fail("Can't connect an endpoint with itself!")
            }

            if(targetContainer.gridCell.endPoints.keys.any { it.id == remoteContainer.gridCell.endpointId }) {
                check(remoteContainer.gridCell.endPoints.keys.any { it.id == targetContainer.gridCell.endpointId }) {
                    "Invalid reciprocal state - missing remote"
                }

                return fail("Can't do that!")
            }
            else {
                check(remoteContainer.gridCell.endPoints.keys.none { it.id == targetContainer.gridCell.endpointId }) {
                    "Invalid reciprocal state - unexpected remote"
                }
            }

            val pair = GridConnectionPair.create(
                targetContainer.createEndpointInfo(),
                remoteContainer.createEndpointInfo()
            )

            val gridCatenary = GridConnectionManagerServer.createGridCatenary(pair, material)

            val startBlockPos = gridCatenary.wireCatenary.a.floorBlockPos()
            val endBlockPos = gridCatenary.wireCatenary.b.floorBlockPos()

            for (blockPos in gridCatenary.wireCatenary.blocks) {
                if(blockPos == startBlockPos || blockPos == endBlockPos) {
                    continue
                }

                val state = pLevel.getBlockState(blockPos)

                if(!state.isAir) {
                    return fail("Block $state is in the way at $blockPos")
                }
            }

            val connectionInfo = GridConnectionDescription(
                gridCatenary.material,
                gridCatenary.resistance
            )

            CellConnections.retopologize(targetContainer.gridCell, targetContainer.cellContainer) {
                targetContainer.setStaging(GridStagingInfo(remoteContainer.gridCell, connectionInfo))
                remoteContainer.setStaging(GridStagingInfo(targetContainer.gridCell, connectionInfo))
            }

            targetContainer.clearStaging()
            remoteContainer.clearStaging()

            check(targetContainer.gridCell.graph == remoteContainer.gridCell.graph) {
                "Grid staging failed"
            }

            targetContainer.gridCell.endPoints.putUnique(remoteContainer.createEndpointInfo(), connectionInfo)
            remoteContainer.gridCell.endPoints.putUnique(targetContainer.createEndpointInfo(), connectionInfo)

            GridConnectionManagerServer.createPair(pLevel as ServerLevel, pair, gridCatenary)

            return success("Connected successfully!")
        }

        val tag = CompoundTag()

        if(targetContainer is Part<*>) {
            tag.putBlockPos(NBT_POS, targetContainer.placement.position)
            tag.putDirection(NBT_FACE, targetContainer.placement.face)
        }
        else {
            targetContainer as BlockEntity
            tag.putBlockPos(NBT_POS, targetContainer.blockPos)
        }

        actualStack.tag = tag

        tell("Start recorded!")

        return InteractionResultHolder.success(actualStack)
    }

    companion object {
        private const val NBT_POS = "pos"
        private const val NBT_FACE = "face"
    }
}

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

    abstract fun mesh() : CatenaryCableMesh

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

        private fun meshExtrusion(spline: Spline3d, extrusion: SketchExtrusion) : CatenaryCableMesh {
            val quads = ArrayList<CatenaryCableQuad>()

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

                fun vert(vertexId: Int) : CatenaryCableVertex {
                    val vertexParametric = mesh.vertices[vertexId]
                    val vertexPosition = vertexParametric.value
                    val vertexNormal = (vertexPosition - (spline.evaluate(vertexParametric.t))).normalized()

                    return CatenaryCableVertex(vertexPosition, vertexNormal, vertexParametric.t)
                }

                val vertices = listOf(vert(ptv.a), vert(ptv.b), vert(ptv.c), vert(ptv.d))

                quads.add(CatenaryCableQuad(ordinate.floorBlockPos(), vertices))
            }

            return CatenaryCableMesh(extrusion, quads)
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

        override fun mesh(): CatenaryCableMesh {
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

        override fun mesh(): CatenaryCableMesh {
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

data class CatenaryCableMesh(val extrusion: SketchExtrusion, val quads: ArrayList<CatenaryCableQuad>)
data class CatenaryCableQuad(val principal: BlockPos, val vertices: List<CatenaryCableVertex>)
data class CatenaryCableVertex(val position: Vector3d, val normal: Vector3d, val param: Double)
