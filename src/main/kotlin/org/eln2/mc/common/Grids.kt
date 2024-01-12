package org.eln2.mc.common

import it.unimi.dsi.fastutil.doubles.Double2DoubleOpenHashMap
import it.unimi.dsi.fastutil.floats.FloatArrayList
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
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraftforge.network.NetworkEvent
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.*
import org.ageseries.libage.mathematics.geometry.*
import org.ageseries.libage.sim.*
import org.ageseries.libage.sim.electrical.mna.VirtualResistor
import org.ageseries.libage.utils.addUnique
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.*
import org.eln2.mc.client.render.*
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.GridMaterial.Catenary
import org.eln2.mc.common.GridMaterial.Straight
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateBlockEntity
import org.eln2.mc.common.blocks.foundation.MultipartBlock
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.cells.CellRegistry
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.events.schedulePre
import org.eln2.mc.common.items.foundation.PartItem
import org.eln2.mc.common.network.Networking
import org.eln2.mc.common.specs.foundation.*
import org.eln2.mc.data.Locators
import org.eln2.mc.data.SortedUUIDPair
import org.eln2.mc.data.plusAssign
import org.eln2.mc.extensions.*
import org.eln2.mc.mathematics.ceilBlockPos
import org.eln2.mc.mathematics.floorBlockPos
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.*
import kotlin.random.Random

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
    val category: GridMaterialCategory,
    val meltingTemperature: Quantity<Temperature>,
    val explosionParticlesPerMeter: Int,
    val color: ThermalTint = defaultRadiantBodyColor()
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
        val splitParameterHint: Double,
    )

    class Catenary(
        circleVertices: Int,
        radius: Double,
        splitDistanceHint: Double,
        splitParameterHint: Double,
        val slack: Double = 0.01,
        val splitRotIncrementMax: Double = PI / 16.0,
    ) : Shape(circleVertices, radius, splitDistanceHint, splitParameterHint)

    class Straight(
        circleVertices: Int,
        radius: Double,
        splitDistanceHint: Double,
        splitParameterHint: Double,
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

    private val COPPER_TEXTURE = gridAtlasSprite("copper_cable")
    private val IRON = gridAtlasSprite("iron_cable")

    private val BIG_GRID_SHAPE = Catenary(
        8, 0.05,
        2.0 * PI * 0.1, 0.25
    )

    private val MICRO_GRID_SHAPE = Straight(
        8, 0.03,
        2.0 * PI * 0.1, 1.0
    )

    private val COPPER_MATERIAL = ChemicalElement.Copper.asMaterial.copy(
        label = "High Resistance Copper",
        density = Quantity(4.0, G_PER_CM3),
        electricalResistivity = Quantity(1.7e-9, OHM_METER) // one order of magnitude
    )

    private val IRON_MATERIAL = ChemicalElement.Iron.asMaterial

    val COPPER_POWER_GRID = register(
        "copper_power",
        GridMaterial(
            COPPER_TEXTURE,
            RGBFloat(1f, 1f, 1f),
            COPPER_MATERIAL,
            BIG_GRID_SHAPE,
            GridMaterialCategory.BIG,
            ChemicalElement.Copper.meltingPoint * 0.9,
            25
        )
    )

    val COPPER_MICRO_GRID = register(
        "copper_micro",
        GridMaterial(
            COPPER_TEXTURE,
            RGBFloat(1f, 1f, 1f),
            COPPER_MATERIAL,
            MICRO_GRID_SHAPE,
            GridMaterialCategory.MicroGrid,
            ChemicalElement.Copper.meltingPoint * 0.9,
            10
        )
    )

    val IRON_POWER_GRID = register(
        "iron_power",
        GridMaterial(
            IRON,
            RGBFloat(1f, 1f, 1f),
            IRON_MATERIAL,
            BIG_GRID_SHAPE,
            GridMaterialCategory.BIG,
            ChemicalElement.Iron.meltingPoint * 0.9,
            150
        )
    )

    val IRON_MICRO_GRID = register(
        "iron_micro",
        GridMaterial(
            IRON,
            RGBFloat(1f, 1f, 1f),
            IRON_MATERIAL,
            MICRO_GRID_SHAPE,
            GridMaterialCategory.MicroGrid,
            ChemicalElement.Iron.meltingPoint * 0.9,
            35
        )
    )

    fun register(id: ResourceLocation, material: GridMaterial) = material.also { materials.add(it, id) }
    private fun register(id: String, material: GridMaterial) = register(resource(id), material)
    fun getId(material: GridMaterial) : ResourceLocation = materials.forward[material] ?: error("Failed to get grid material id $material")
    fun getMaterial(resourceLocation: ResourceLocation) : GridMaterial = materials.backward[resourceLocation] ?: error("Failed to get grid material $resourceLocation")
}

/**
 * [Cable3dA] with extra information needed for syncing and state management.
 * @param id The unique ID of the connection. This is used for replication to clients. This ID should never be saved, it is a runtime-only thing.
 * @param cable The 3D model data.
 * @param material The physical properties of the grid cable.
 * */
data class GridConnection(val id: Int, val cable: Cable3dA, val material: GridMaterial) {
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

        @ClientOnly
        fun fromNbt(tag: CompoundTag) = GridConnection(
            tag.getInt(ID),
            Cable3dA.fromNbt(tag.get(CATENARY) as CompoundTag),
            GridMaterials.getMaterial(tag.getResourceLocation(MATERIAL))
        )
    }
}

interface GridConnectionHandle {
    val pair: GridEndpointPair
    val connection: GridConnection
    val level: ServerLevel
    val owner: GridConnectionOwner?

    var options: GridRenderOptions

    fun remove()
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

interface GridConnectionOwner {
    fun knife(knife: GridKnifeItem) : InteractionResult
}

@ServerOnly
object GridConnectionManagerServer {
    fun createCable(pair: GridEndpointPair, material: GridMaterial) = GridConnection(
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

    fun createPairIfAbsent(level: ServerLevel, pair: GridEndpointPair, material: GridMaterial) : GridConnection = invoke(level) {
        createPairIfAbsent(pair, material)
    }

    fun createPair(level: ServerLevel, pair: GridEndpointPair, connection: GridConnection, owner: GridConnectionOwner? = null) = invoke(level) {
        createPair(pair, connection, owner)
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

        fun intersects(box: BoundingBox3d) = collider.intersects(box)
        fun intersects(box: OrientedBoundingBox3d) = collider.intersects(box)
        fun pick(line: Line3d) = collider.pick(line)
        fun getHandle(id: Int) : GridConnectionHandle? = handles.forward[id]

        private fun createHandle(pair: GridEndpointPair, connection: GridConnection, owner: GridConnectionOwner?) : Handle {
            val handle = Handle(pair, connection, owner)

            handles.add(connection.id, handle)

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

        fun createPair(pair: GridEndpointPair, connection: GridConnection, owner: GridConnectionOwner?) : GridConnectionHandle {
            pairMap.addPair(pair)
            val handle = createHandle(pair, connection, owner)
            handlesByPair[pair] = handle
            return handle
        }

        fun createPairIfAbsent(pair: GridEndpointPair, material: GridMaterial) : GridConnection {
            if(pairMap.hasPair(pair)) {
                return checkNotNull(handlesByPair[pair]) { "Lingering pair in pair map" }.connection
            }

            val result = createCable(pair, material)

            createPair(pair, result, null)

            return result
        }

        fun removeEndpointById(endPointId: UUID) {
            pairMap.removePairsById(endPointId).forEach { pair ->
                handlesByPair.remove(pair)!!.cleanup()
            }
        }

        private inner class Handle(override val pair: GridEndpointPair, override val connection: GridConnection, override val owner: GridConnectionOwner?) : GridConnectionHandle {
            init {
                collider.add(connection)
            }

            private var isDestroyed = false
            private val players = MutableSetMapMultiMap<ServerPlayer, ChunkPos>()
            private val lock = ReentrantReadWriteLock()

            override var options: GridRenderOptions = GridRenderOptions()
                set(value) {
                    if(field != value) {
                        field = value

                        lock.read {
                            if(!isDestroyed) {
                                val message = GridConnectionUpdateRenderMessage(
                                    connection.id,
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

            fun removePlayer(player: ServerPlayer, chunkPos: ChunkPos) {
                lock.write {
                    check(!isDestroyed) {
                        "removePlayer isDestroyed"
                    }
                    return players.remove(player, chunkPos)
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

                        collider.remove(connection.id)
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
            connections.putUnique(connection.id, data)

            val byConnection = slicesByConnection[connection.id]

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

            for (blockPos in BlockPos.betweenClosed(segmentAligned.min.floorBlockPos(), segmentAligned.max.ceilBlockPos())) {
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
        pNormalX: Float, pNormalY: Float, pNormalZ: Float,
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
        GridConnectionManagerClient.read(section) { material, vertexList, data ->
            val (mr, mg, mb) = material.vertexColor
            val (tr, tg, tb) = data.tint

            val r = mr * tr
            val g = mg * tg
            val b = mb * tb

            val originX = vertexList.originX
            val originY = vertexList.originY
            val originZ = vertexList.originZ

            var i = 0
            val storage = vertexList.storage
            val storageSize = storage.size

            while (i < storageSize) {
                val px = storage.getFloat(i + 0)
                val py = storage.getFloat(i + 1)
                val pz = storage.getFloat(i + 2)
                val nx = storage.getFloat(i + 3)
                val ny = storage.getFloat(i + 4)
                val nz = storage.getFloat(i + 5)
                val u = storage.getFloat(i + 6)
                val v = storage.getFloat(i + 7)
                i += 8

                val blockX = floor(px).toInt() + originX
                val blockY = floor(py).toInt() + originY
                val blockZ = floor(pz).toInt() + originZ
                neighborLights.load(blockX, blockY, blockZ)

                val localLight = lightReader.getLightColor(
                    BlockPos.asLong(blockX, blockY, blockZ)
                )

                val localBlock = unpackBlockLight(localLight)
                val localSky = unpackSkyLight(localLight)

                val blockLight = combineLight(0, neighborLights, nx, ny, nz, localBlock.toDouble())
                val skyLight = combineLight(1, neighborLights, nx, ny, nz, localSky.toDouble())

                val overrideLight = (data.brightnessOverride * 15).toInt().coerceIn(0, 15)

                val light = LightTexture.pack(
                    max(blockLight, overrideLight),
                    skyLight
                )

                consumer.vertex(
                    px, py, pz,
                    r, g, b,
                    u, v,
                    OverlayTexture.NO_OVERLAY, light,
                    nx, ny, nz
                )
            }
        }
    }
}

class GridPruningStructure {
    private var boundingBoxTree = BoundingBoxTree3d<Segment>()
    private var cables = MutableSetMapMultiMap<Int, Segment>()

    fun add(connection: GridConnection) {
        require(!cables.contains(connection.id)) {
            "Duplicate add $connection"
        }

        val set = cables[connection.id]

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
    val splitParameterHint: Double = 0.1,
) {
    /**
     * Gets the circumference of the tube, according to [radius].
     * */
    val circumference get() = 2.0 * PI * radius

    /**
     * Gets the surface area of a cross-section.
     * */
    val crossSectionArea get() = PI * radius * radius

    /**
     * Gets the supports [a] and [b], sorted in ascending order by their vertical coordinate.
     * */
    val supports = listOf(a, b).sortedBy { it.y }

    /**
     * Gets the arc length of the cable.
     * */
    abstract val arcLength: Double

    /**
     * Gets the surface area of the cable.
     * */
    val surfaceArea get() = 2.0 * PI * radius * (arcLength + radius)

    /**
     * Gets the volume of the cable.
     * */
    val volume get() = PI * radius * radius * arcLength

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

    /**
     * Gets cylindrical segments of the cable.
     * This is funny; we approximate the shape of cylinders with these segments, and here, we approximate the shape of these segments with cylinders.
     * */
    abstract val segments: List<Cylinder3d>

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
        val splitRotIncrementMax: Double,
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

        private val samples = checkNotNull(
            spline.adaptscan(
                0.0,
                1.0,
                splitParameterHint,
                condition = differenceCondition3d(
                    distMax = splitDistanceHint, //min(splitDistanceHint, circumference),
                    rotIncrMax = splitRotIncrementMax
                ),
                iMax = 1024 * 32 // way too generous...
            )
        ) { "Failed to get samples for catenary cable3d $this" }

        override val segments = run {
            if(samples.size < 2) {
                // what?
                DEBUGGER_BREAK()
                return@run emptyList<Cylinder3d>()
            }

            val cylinders = ArrayList<Cylinder3d>(samples.size - 1)

            var previous = spline.evaluate(samples[0])

            for (i in 1 until samples.size) {
                val p0 = previous
                val p1 = spline.evaluate(samples[i])
                previous = p1

                cylinders.add(
                    Cylinder3d(
                        Line3d.fromStartEnd(p0, p1),
                        radius
                    )
                )
            }

            cylinders
        }

        override fun mesh(): CableMesh3d {
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

        override val segments = listOf(
            Cylinder3d(
                Line3d.fromStartEnd(a, b),
                radius
            )
        )

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
                supports: List<Vector3d>,
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

class GridNode(val cell: Cell) : UniqueCellNode {
    init {
        cell.lifetimeEvents += this::onBeginDestroy
    }

    /**
     * Gets the terminals used by this grid node cell. The integer is the terminal ID, and the UUID is the endpoint ID of the terminal.
     * */
    val terminals = MutableMapPairBiMap<Int, GridEndpointInfo>()

    /**
     * Takes the terminals from the [gridTerminalSystem] for usage within this cell.
     * This must be done once, when the cell is created. This data is saved and must be the same the next time the grid terminal system is created.
     * */
    fun mapFromGridTerminalSystem(gridTerminalSystem: GridTerminalSystem) {
        check(terminals.size == 0) {
            "Multiple map from grid terminal system"
        }

        gridTerminalSystem.getInstances().forEach { (terminalID, terminal) ->
            terminals.add(terminalID, terminal.gridEndpointInfo)
        }

        cell.setChanged()
    }

    inline fun forEachConnectionCell(use: (GridConnectionCell) -> Unit) {
        cell.connections.forEach {
            if (it is GridConnectionCell) {
                use(it)
            }
        }
    }

    inline fun forEachMapping(use: (GridConnectionCell, Cell) -> Unit) {
        cell.connections.forEach {
            if (it is GridConnectionCell) {
                use(it, it.getOtherCell(cell))
            }
        }
    }

    inline fun forEachMappingWhile(use: (GridConnectionCell, Cell) -> Boolean) {
        for (it in cell.connections) {
            if (it is GridConnectionCell) {
                if(!use(it, it.getOtherCell(cell))) {
                    return
                }
            }
        }
    }


    inline fun forEachRemote(use: (Cell) -> Unit) {
        cell.connections.forEach {
            if (it is GridConnectionCell) {
                use(it.getOtherCell(cell))
            }
        }
    }

    fun captureInNeighborListWithContainer(neighbors: MutableCollection<CellAndContainerHandle>) {
        forEachRemote {
            neighbors.add(CellAndContainerHandle.captureInScope(it))
        }
    }

    /**
     * Gets the first connection cell to the endpoint [remoteEndpointID] and the endpoint's cell, if a connection to [remoteEndpointID] exists.
     * */
    fun firstPathwayTo(remoteEndpointID: UUID) : Pair<GridConnectionCell, GridConnectionCell.NodeInfo>? =
        cell.connections.firstNotNullOfOrNull {
            val connectionCell = it as? GridConnectionCell
                ?: return@firstNotNullOfOrNull null

            val other = connectionCell.getOtherCellWithFullMetadata(cell)

            if(other.endpointInfo.id == remoteEndpointID) {
                connectionCell to other
            }
            else {
                null
            }
        }

    fun hasAnyConnectionWith(terminal: Int, remoteEndpointID: UUID, remoteTerminal: Int) = cell.connections.any {
        val connectionCell = it as? GridConnectionCell
            ?: return@any false

        if(connectionCell.getFullMetadata(cell).terminal != terminal) {
            return@any false
        }

        val other = connectionCell.getOtherCellWithFullMetadata(cell)

        other.terminal == remoteTerminal && other.endpointInfo.id == remoteEndpointID
    }

    /**
     * Destroys all [GridConnectionCell]s.
     * */
    private fun onBeginDestroy(event: Cell_onBeginDestroy) {
        val list = ArrayList<GridConnectionCell>()

        forEachConnectionCell {
            it.isRemoving = true
            list.add(it)
        }

        list.forEach {
            CellConnections.destroy(it, it.container!!)
        }
    }

    override fun saveNodeData(): CompoundTag {
        val tag = CompoundTag()
        val listTag = ListTag()

        terminals.forward.forEach { (terminal, endpoint) ->
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

    override fun loadNodeData(tag: CompoundTag) {
        val listTag = tag.getListTag(TERMINALS)

        listTag.forEachCompound { entryCompound ->
            val terminal = entryCompound.getInt(TERMINAL)
            val endpointId = entryCompound.getUUID(ENDPOINT_ID)
            val attachment = entryCompound.getVector3d(ENDPOINT_ATTACHMENT)
            val endpointLocator = entryCompound.getLocator(ENDPOINT_LOCATOR)

            terminals.add(terminal, GridEndpointInfo(endpointId, attachment, endpointLocator))
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
        resistor.resistance = cell.state.connection.resistance
        this.resistorInternal = resistor
    }

    override fun offerPolar(remote: ElectricalObject<*>): TermRef {
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

class GridConnectionThermalObject(cell: GridConnectionCell) : ThermalObject<GridConnectionCell>(cell) {
    private var massInternal: ThermalMass? = null

    val mass get() = checkNotNull(this.massInternal) {
        "Thermal mass is not initialized!"
    }

    fun initialize() {
        check(massInternal == null) {
            "Re-initialization of thermal grid connection object"
        }

        val mass = ThermalMass(
            material = cell.state.connection.material.physicalMaterial,
            mass = Quantity(cell.state.connection.material.physicalMaterial.density.value * cell.state.connection.cable.volume)
        )

        cell.environmentData.loadTemperature(mass)

        this.massInternal = mass
    }

    override fun offerComponent(remote: ThermalObject<*>) = ThermalComponentInfo(mass)

    override fun addComponents(simulator: Simulator) {
        simulator.add(mass)

        cell.environmentData.connect(
            simulator,
            ConnectionParameters(
                area = cell.state.connection.cable.surfaceArea
            ),
            mass
        )
    }

    override fun getParameters(remote: ThermalObject<*>) = ConnectionParameters(area = cell.state.connection.cable.crossSectionArea)
}

/**
 * Represents a connection between two [GridNodeCell]s.
 * This is not owned by a game object; it is a container-less, game-object-less, position-less cell.
 * This cell owns the [GridConnection]. It will check if [GridConnectionManagerServer] already has a connection between the two endpoints, and if so, it will error out.
 * */
class GridConnectionCell(ci: CellCreateInfo) : Cell(ci), GridConnectionOwner {
    private var stateField: State? = null

    var isRemoving = false

    val state get() = checkNotNull(stateField) {
        "Grid connection cell state was not initialized!"
    }

    private var handle: GridConnectionHandle? = null

    private fun initializeFromStaging(
        level: ServerLevel,
        a: Cell, terminalA: Int,
        b: Cell, terminalB: Int,
        cable: GridConnection,
    ) {
        check(stateField == null) {
            "Multiple initializations"
        }

        check(container == null) {
            "Expected container to not be set when initializing from staging"
        }

        container = StagingContainer(level, a, b)

        val ax = a.requireNode<GridNode>()
        val bx = b.requireNode<GridNode>()

        val endpointA = ax.terminals.forward[terminalA]!!
        val endpointB = bx.terminals.forward[terminalB]!!

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

    private var cellAField: Cell? = null
    private var cellBField: Cell? = null

    /**
     * Gets the cell of the first endpoint.
     * */
    val cellA get() = checkNotNull(cellAField) {
        "Cell A was not initialized"
    }

    /**
     * Gets the cell of the second endpoint.
     * */
    val cellB get() = checkNotNull(cellBField) {
        "Cell B was not initialized"
    }

    /**
     * Gets the other cell in the pair. [cell] must be either [cellA] or [cellB].
     * @return [cellA], if [cell] is [cellB]. [cellB] is [cell] is [cellA]. Otherwise, error.
     * */
    fun getOtherCell(cell: Cell) = if (cellA === cell) {
        cellB
    } else if (cellB === cell) {
        cellA
    } else {
        error("Expected cell to be one from the pair")
    }

    /**
     * Gets the other cell in the pair.
     * @return [cellA], if [cell] is [cellB]. [cellB] is [cell] is [cellA]. Otherwise, null.
     * */
    fun getOtherCellOrNull(cell: Cell) = if (cellA === cell) {
        cellB
    } else if (cellB === cell) {
        cellA
    } else {
        null
    }

    /**
     * Gets the other cell in the pair, along with grid information. [cell] must be either [cellA] or [cellB].
     * @return [cellA], if [cell] is [cellB]. [cellB] is [cell] is [cellA]. Otherwise, error.
     * */
    fun getOtherCellWithFullMetadata(cell: Cell) = if (cellA === cell) {
        NodeInfo(cellB, state.terminalB, state.pair.b)
    } else if (cellB === cell) {
        NodeInfo(cellA, state.terminalA, state.pair.a)
    } else {
        error("Expected cell $cell to be one from the pair ($cellA or $cellB) to get other")
    }

    /**
     * Gets the information associated with [cell]. The [cell] must be either [cellA] or [cellB].
     * */
    fun getFullMetadata(cell: Cell) = if (cellA === cell) {
        NodeInfo(cellA, state.terminalA, state.pair.a)
    } else if (cellB === cell) {
        NodeInfo(cellB, state.terminalB, state.pair.b)
    } else {
        error("Expected cell $cell to be one from the pair ($cellA or $cellB) to get metadata")
    }

    override fun onBuildStarted() {
        super.onBuildStarted()

        if(isRemoving) {
            return
        }

        check(connections.size == 2) {
            "Grid connection cell did not have the 2 connections!"
        }

        val cell1 = connections[0]
        val cell2 = connections[1]

        val cell1x = cell1.requireNode<GridNode> {
            "$cell1 did not have grid node for connection"
        }

        val cell2x = cell2.requireNode<GridNode> {
            "$cell2 did not have grid node for connection"
        }

        val cell1a = cell1x.terminals.backward[state.pair.a]
        val cell1b = cell1x.terminals.backward[state.pair.b]
        val cell2a = cell2x.terminals.backward[state.pair.a]
        val cell2b = cell2x.terminals.backward[state.pair.b]

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

    private fun setupPermanent() {
        this.container = PermanentContainer()
        handle = GridConnectionManagerServer.createPair(graph.level, state.pair, state.connection, this)
    }

    private fun sendRenderUpdates() {
        val handle = checkNotNull(handle) {
            "Expected to have handle to send updates!"
        }

        val color = state.connection.material.color.evaluateRGBL(
            thermal.mass.temperature
        )

        handle.options = GridRenderOptions(
            tint = RGBFloat.createClamped(
                color.redAsFloat,
                color.greenAsFloat,
                color.blueAsFloat
            ),
            brightnessOverride = (color.alpha / 255.0).coerceIn(0.0, 1.0)
        )
    }

    override fun onWorldLoadedPreSolver() {
        super.onWorldLoadedPreSolver()
        check(this.container == null)
        check(this.handle == null)
        setupPermanent()
        sendRenderUpdates()
    }

    override fun saveCellData(): CompoundTag {
        val state = this.state

        val tag = CompoundTag()

        tag.put(PAIR, state.pair.toNbt())
        tag.putResourceLocation(MATERIAL, state.connection.material.id)
        tag.putInt(TERMINAL_A, state.terminalA)
        tag.putInt(TERMINAL_B, state.terminalB)
        tag.putQuantity(TEMPERATURE, thermal.mass.temperature)

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

        thermal.mass.temperature = tag.getQuantity(TEMPERATURE)
        savedTemperature = thermal.mass.temperature
        sentTemperature = thermal.mass.temperature
    }

    @SimObject
    val electrical = GridConnectionElectricalObject(this)

    @SimObject
    val thermal = GridConnectionThermalObject(this)

    private fun initializeObjects() {
        electrical.initialize()
        thermal.initialize()
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::simulationTick)
    }

    private var savedTemperature: Quantity<Temperature> = Quantity(-1.0, KELVIN)
    private var sentTemperature: Quantity<Temperature> = Quantity(-1.0, KELVIN)
    private var isMelting = false

    private fun simulationTick(dt: Double, phase: SubscriberPhase) {
        if(isMelting) {
            return
        }

        thermal.mass.energy += abs(electrical.resistor.power) * dt

        val temperature = thermal.mass.temperature

        setChangedIf(!temperature.value.approxEq(!savedTemperature, TEMPERATURE_SAVE_EPS)) {
            savedTemperature = temperature
            println("T save: ${thermal.mass.temperature.classify()}")
        }

        if(handle != null && !temperature.value.approxEq(!sentTemperature, TEMPERATURE_SEND_EPS)) {
            sentTemperature = temperature
            println("T sent: ${thermal.mass.temperature.classify()}")
            sendRenderUpdates()
        }

        if(thermal.mass.temperature > state.connection.material.meltingTemperature) {
            melt()
        }
    }

    private fun melt() {
        isMelting = true
        val material = state.connection.material
        val cable = state.connection.cable
        val level = graph.level

        schedulePre(0) {
            if(!isBeingRemoved) {
                isRemoving = true
                CellConnections.destroy(this, this.container!!)

                val numberOfParticles = ceil(cable.arcLength * material.explosionParticlesPerMeter).toInt()

                repeat(numberOfParticles) {
                    fun add(type: SimpleParticleType) {
                        val (px, py, pz) = cable.spline.evaluate(Random.nextDouble(0.0, 1.0))

                        level.sendParticles(
                            type,
                            px, py, pz,
                            1,
                            0.0, 0.0, 0.0,
                            Random.nextDouble(0.5, 2.0)
                        )
                    }

                    add(ParticleTypes.FLAME)
                    add(ParticleTypes.LARGE_SMOKE)
                }

                cable.blocks.forEach {
                    level.playSound(
                        null,
                        it,
                        SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                        randomFloat(0.9f, 1.0f), randomFloat(0.8f, 1.1f),
                    )
                }
            }
        }
    }

    override fun knife(knife: GridKnifeItem) : InteractionResult {
        isRemoving = true
        CellConnections.destroy(this, this.container!!)
        return InteractionResult.SUCCESS
    }

    override fun onDestroyed() {
        super.onDestroyed()
        handle?.remove()
    }

    data class State(
        val connection: GridConnection,
        val pair: GridEndpointPair,
        val terminalA: Int,
        val terminalB: Int
    )

    data class NodeInfo(
        val cell: Cell,
        val terminal: Int,
        val endpointInfo: GridEndpointInfo
    )

    /**
     * Fake container used when staging the connections.
     * */
    inner class StagingContainer(val level: ServerLevel, val a: Cell, val b: Cell) : CellContainer {
        override fun getCells() = listOf(this@GridConnectionCell)

        override fun neighborScan(actualCell: Cell) = listOf(
            CellAndContainerHandle.captureInScope(a),
            CellAndContainerHandle.captureInScope(b)
        )

        override val manager = CellGraphManager.getFor(level)
    }

    /**
     * Fake container used throughout the lifetime of the cell.
     * */
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
        private const val TEMPERATURE = "temperature"
        private const val PAIR = "pair"

        private const val TEMPERATURE_SAVE_EPS = 0.1
        private const val TEMPERATURE_SEND_EPS = 5.0

        fun createStaging(
            cellA: Cell,
            cellB: Cell,
            level: ServerLevel,
            endpointA: UUID,
            terminalA: Int,
            endpointB: UUID,
            terminalB: Int,
            cable: GridConnection,
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
            terminalA: CellTerminal,
            terminalB: CellTerminal,
            level: ServerLevel,
            cable: GridConnection,
        ) {
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
            terminalA: CellTerminal,
            terminalB: CellTerminal,
        ) {
            check(terminalA.stagingCell === terminalB.stagingCell)
            val cell = checkNotNull(terminalA.stagingCell)
            check(cell.container is StagingContainer)
            CellConnections.insertFresh(cell.container!!, cell)
            terminalA.stagingCell = null
            terminalB.stagingCell = null
            cell.setupPermanent()

            check(terminalA.cell.graph === terminalB.cell.graph) {
                "Staging failed - terminals did not have same graph"
            }

            check(terminalA.cell.graph === cell.graph) {
                "Staging failed - connection did not have expected graph"
            }
        }
    }
}
