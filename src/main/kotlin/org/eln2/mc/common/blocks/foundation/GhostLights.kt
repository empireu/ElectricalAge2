@file:Suppress("NOTHING_TO_INLINE")

package org.eln2.mc.common.blocks.foundation

import com.jozufozu.flywheel.light.LightUpdater
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraftforge.network.NetworkEvent
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.*
import org.eln2.mc.common.network.Networking
import org.eln2.mc.data.DefaultPooledObjectPolicy
import org.eln2.mc.data.LinearObjectPool
import org.eln2.mc.mathematics.BlockPosInt
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * Handles a "ghost light" (block light override) for a single block.
 * */
interface GhostLight {
    /**
     * Gets the world position of the block.
     * */
    val position: BlockPos
    /**
     * Gets the in-chunk [BlockPosInt]
     * */
    val packedPos: Int
    /**
     * Gets the game level.
     * */
    val level: ServerLevel
    /**
     * If true, the block placed at the position is air.
     * */
    val isOpen: Boolean
    /**
     * Sets the lower bound of the brightness at the target block.
     * If multiple handles are modifying this block, the largest value will be applied.
     * */
    fun setBrightness(brightness: Int)
    /**
     * Destroys the handle. This will reset the brightness override, if no other handles are accessing the block.
     * */
    fun destroy()
}

private fun packKey(blockPos: BlockPos): Int {
    val cx = blockPos.x and 0xF
    val cz = blockPos.z and 0xF
    return BlockPosInt.pack(cx, blockPos.y, cz)
}

private fun unpackKey(chunkPos: ChunkPos, i: Int): BlockPos {
    val p = BlockPosInt(i)
    val x = chunkPos.minBlockX + p.x
    val z = chunkPos.minBlockZ + p.z
    return BlockPos(x, p.y, z)
}

// Of course, it is possible to optimize further.
// You can pack the position and light value in 32 bits (because x and z are [0, 15], so you don't need the 10 bits BlockPosInt uses)
// But that probably isn't worth it. The data representation is already pretty good, so the bulk of the overhead will come from light updates

/**
 * Packed light value. The first 32 bits are used for a [BlockPosInt], and the first byte in the second half for the light value.
 * */
@JvmInline
private value class PackedLightVoxel(val data: Long) {
    constructor(posInt: Int, light: Byte) : this(posInt.toLong() or (light.toLong() shl 32))

    val relativePosition get() = BlockPosInt(data.toInt())

    val lightValue get() = (data shr 32).toByte()

    operator fun not() = data
}

enum class GhostLightUpdateType {
    /**
     * Sent when a block is placed, and it closes the ghost light.
     * */
    Closed,
    /**
     * Sent when a block is removed, and it opens the ghost light.
     * */
    Opened
}

/**
 * Event handler for ghost light updates.
 * */
fun interface GhostLightNotifier {
    fun onUpdate(handle: GhostLight, type: GhostLightUpdateType)
}

class GhostLightCommandMessage(val chunkPos: ChunkPos, val type: Type) {
    enum class Type(val id: Int) {
        BeginTracking(1),
        StopTracking(2);
    }

    companion object {
        fun encode(message: GhostLightCommandMessage, buf: FriendlyByteBuf) {
            buf.writeChunkPos(message.chunkPos)
            buf.writeByte(message.type.id)
        }

        fun decode(buf: FriendlyByteBuf) = GhostLightCommandMessage(
            buf.readChunkPos(),
            when(val id = buf.readByte().toInt()) {
                Type.BeginTracking.id -> Type.BeginTracking
                Type.StopTracking.id -> Type.StopTracking
                else -> error("Unknown ghost light type id $id")
            }
        )

        fun handle(message: GhostLightCommandMessage, ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork {
                when(message.type) {
                    Type.BeginTracking -> GhostLightHackClient.addChunk(message.chunkPos)
                    Type.StopTracking -> GhostLightHackClient.removeChunk(message.chunkPos)
                }
            }

            ctx.get().packetHandled = true
        }
    }
}

class GhostLightChunkDataMessage(val data: ByteArray) {
    companion object {
        private const val HEADER_SIZE = Long.SIZE_BYTES
        private const val ENTRY_SIZE = Int.SIZE_BYTES + Byte.SIZE_BYTES

        fun encodeBinary(chunkPos: ChunkPos, values: LongArray) : ByteArray {
            require(values.isNotEmpty())

            val array = ByteArray(HEADER_SIZE + ENTRY_SIZE * values.size)
            val buffer = ByteBuffer.wrap(array)

            buffer.putLong(chunkPos.toLong())

            for (i in values.indices) {
                val value = PackedLightVoxel(values[i])
                buffer.putInt(value.relativePosition.value)
                buffer.put(value.lightValue)
            }

            return array
        }

        fun encodeBinary(chunkPos: ChunkPos, values: Int2ByteOpenHashMap) : ByteArray {
            require(values.isNotEmpty())

            val array = ByteArray(HEADER_SIZE + ENTRY_SIZE * values.size)
            val buffer = ByteBuffer.wrap(array)

            buffer.putLong(chunkPos.toLong())

            for ((relativePosition, lightValue) in values) {
                buffer.putInt(relativePosition)
                buffer.put(lightValue)
            }

            return array
        }

        fun decodeBinary(array: ByteArray) : Pair<ChunkPos, LongArray> {
            require(array.isNotEmpty())
            val buffer = ByteBuffer.wrap(array)

            val chunk = ChunkPos(buffer.long)
            val count = (array.size - HEADER_SIZE) / ENTRY_SIZE
            val result = LongArray(count)

            for (i in 0 until count) {
                val relativePosition = buffer.int
                val lightValue = buffer.get()
                result[i] = !PackedLightVoxel(relativePosition, lightValue)
            }

            require(buffer.remaining() == 0)

            return chunk to result
        }

        fun encode(message: GhostLightChunkDataMessage, buf: FriendlyByteBuf) {
            buf.writeByteArray(message.data)
        }

        fun decode(buf: FriendlyByteBuf): GhostLightChunkDataMessage {
            return GhostLightChunkDataMessage(buf.readByteArray())
        }

        fun handle(message: GhostLightChunkDataMessage, ctx: Supplier<NetworkEvent.Context>) {
            val (chunk, values) = decodeBinary(message.data)

            ctx.get().enqueueWork {
                GhostLightHackClient.commit(chunk, values)
            }

            ctx.get().packetHandled = true
        }
    }
}

/**
 * *Ghost lights* are overrides applied to block light values.
 * */
@ServerOnly
object GhostLightServer {
    private val levels = HashMap<ServerLevel, LevelLightData>()

    private fun validateUsage() {
        requireIsOnServerThread { "Ghost lights server must be on server thread" }
    }

    fun clear() {
        validateUsage()
        levels.clear()
    }

    private fun validateLevel(level: Level): ServerLevel {
        if(level !is ServerLevel) {
            error("Cannot use non server level $level")
        }

        return level
    }

    private fun getLevelData(level: Level) : LevelLightData {
        val serverLevel = validateLevel(level)
        return levels.computeIfAbsent(serverLevel) { LevelLightData(serverLevel) }
    }

    /**
     * Checks if a handle can be created in [level], at the specified [position].
     * @return True, if a handle can be created. Otherwise, false.
     * */
    fun canCreateHandle(level: Level, position: BlockPos): Boolean {
        validateUsage()
        return getLevelData(validateLevel(level)).canCreateHandle(position)
    }

    /**
     * Creates a handle in [level], at the specified [position].
     * @param notifier An optional event handler.
     * @return A ghost light handle, if a ghost light can be created (according to [canCreateHandle]). Otherwise, null.
     * */
    fun createHandle(level: Level, position: BlockPos, notifier: GhostLightNotifier? = null): GhostLight? {
        validateUsage()
        return getLevelData(validateLevel(level)).createHandle(position, notifier)
    }

    /**
     * Gets the desired brightness in [level], at the specified [position].
     * */
    @JvmStatic
    fun getBlockBrightness(level: Level, position: BlockPos): Int {
        validateUsage()
        return levels[level]?.getDesiredBrightness(position) ?: 0
    }

    fun handleBlockEvent(level: Level, position: BlockPos) {
        validateUsage()
        levels[level]?.handleBlockEvent(position)
    }

    fun playerWatch(level: ServerLevel, player: ServerPlayer, chunkPos: ChunkPos) {
        validateUsage()
        getLevelData(level).watch(player, chunkPos)
    }

    fun playerUnwatch(level: ServerLevel, player: ServerPlayer, chunkPos: ChunkPos) {
        validateUsage()
        getLevelData(level).unwatch(player, chunkPos)
    }

    fun applyChanges() {
        validateUsage()
        levels.values.forEach { it.applyChanges() }
    }

    private class LevelLightData(val gameLevel: ServerLevel) {
        private val chunks = HashMap<ChunkPos, LightChunk>()
        private val dirtyChunks = HashSet<ChunkPos>()

        private fun getOrCreateChunk(chunkPos: ChunkPos) = chunks.computeIfAbsent(chunkPos) { LightChunk(chunkPos, this) }
        private fun getOrCreateChunk(blockPos: BlockPos) = getOrCreateChunk(ChunkPos(blockPos))
        private fun getChunk(blockPos: BlockPos) = chunks[ChunkPos(blockPos)]

        fun watch(player: ServerPlayer, chunkPos: ChunkPos) = getOrCreateChunk(chunkPos).startTrackingPlayer(player)
        fun unwatch(player: ServerPlayer, chunkPos: ChunkPos) = getOrCreateChunk(chunkPos).stopTrackingPlayer(player)

        fun canCreateHandle(pos: BlockPos) = gameLevel.isInWorldBounds(pos)

        fun createHandle(pos: BlockPos, notifier: GhostLightNotifier? = null): GhostLight? {
            if(!canCreateHandle(pos)) {
                return null
            }

            return getOrCreateChunk(pos).createHandle(pos, notifier)
        }

        fun handleBlockEvent(pos: BlockPos) = getChunk(pos)?.handleBlockEvent(pos)

        fun getDesiredBrightness(pos: BlockPos) = getChunk(pos)?.getBrightness(pos) ?: 0

        fun setChunkDirty(pos: ChunkPos) {
            require(dirtyChunks.add(pos))
        }

        fun applyChanges() {
            dirtyChunks.forEach { chunkPos ->
                chunks[chunkPos]?.applyChanges()
            }

            dirtyChunks.clear()
        }
    }

    private class LightChunk(val chunkPos: ChunkPos, val lightLevel: LevelLightData) {
        private val cells = Int2ObjectOpenHashMap<Cell>()
        private val players = HashSet<ServerPlayer>()
        private val dirtyCells = IntArrayList()
        private var isDirty = false

        private fun getOrCreateCellWorld(blockPos: BlockPos) : Cell {
            val packedPos = packKey(blockPos)

            val existing = cells.get(packedPos)

            if(existing != null) {
                return existing
            }

            val cell = Cell(blockPos, packedPos)
            require(cells.put(packedPos, cell) == null)
            return cell
        }

        fun createHandle(pos: BlockPos, notifier: GhostLightNotifier? = null): GhostLight {
            validateUsage()
            return getOrCreateCellWorld(pos).createHandle(notifier)
        }

        fun handleBlockEvent(pos: BlockPos) = cells.get(packKey(pos))?.handleBlockEvent()

        fun getBrightness(pos: BlockPos) = cells.get(packKey(pos))?.targetBrightness ?: 0

        fun startTrackingPlayer(player: ServerPlayer) {
            if(!(players.add(player))) {
                return
            }

            Networking.send(GhostLightCommandMessage(chunkPos, GhostLightCommandMessage.Type.BeginTracking), player)

            val values = Int2ByteOpenHashMap()

            for (cell in cells.values) {
                if(cell.targetBrightness > 0) {
                    values.put(packKey(cell.position), cell.targetBrightness.toByte())
                }
            }

            if(values.isNotEmpty()) {
                Networking.send(GhostLightChunkDataMessage(GhostLightChunkDataMessage.encodeBinary(chunkPos, values)), player)
            }
        }

        fun stopTrackingPlayer(player: ServerPlayer) {
            if(!players.remove(player)) {
                return
            }

            Networking.send(GhostLightCommandMessage(chunkPos, GhostLightCommandMessage.Type.StopTracking), player)
        }

        fun applyChanges() {
            require(isDirty)

            isDirty = false

            val updates = LongArray(dirtyCells.size)

            for (i in dirtyCells.indices) {
                val packedPos = dirtyCells.getInt(i)
                val cell = cells.get(packedPos)

                if(cell == null) {
                    updates[i] = !PackedLightVoxel(packedPos, 0)
                    lightLevel.gameLevel.chunkSource.onLightUpdate(LightLayer.BLOCK, SectionPos.of(unpackKey(chunkPos, packedPos)))
                } else {
                    updates[i] = !PackedLightVoxel(packedPos, cell.targetBrightness.toByte())
                    cell.resetDirty()
                    lightLevel.gameLevel.chunkSource.onLightUpdate(LightLayer.BLOCK, SectionPos.of(cell.position))
                }
            }

            dirtyCells.clear()

            if(updates.isNotEmpty() && players.isNotEmpty()) {
                val binary = GhostLightChunkDataMessage.encodeBinary(chunkPos, updates)
                val message = GhostLightChunkDataMessage(binary)

                players.forEach {
                    Networking.send(message, it)
                }
            }
        }

        private fun setVoxelDirty(packedPosition: Int) {
            dirtyCells.add(packedPosition)
            if(!this@LightChunk.isDirty) {
                this@LightChunk.isDirty = true
                lightLevel.setChunkDirty(chunkPos)
            }
        }

        private fun removeCell(cell: Cell) {
            require(cells.remove(cell.packedPosition) == cell)
            // Set dirty. When sending changes, if we find that the cell isn't there, we send 0.
            // This will make the client reset the override.
            setVoxelDirty(cell.packedPosition)
        }

        private inner class Cell(val position: BlockPos, val packedPosition: Int) {
            var targetBrightness = 0
                private set

            private val handles = ArrayList<Handle>()
            private var isDirty = false

            val isOpen get() = lightLevel.gameLevel.getBlockState(position).isAir

            private fun changeHandleBrightness() {
                val brightness = handles.sumOf { it.handleBrightness }.coerceIn(0, 15)

                if(brightness == targetBrightness) {
                    return
                }

                targetBrightness = brightness

                if(!this@Cell.isDirty) {
                    this@Cell.isDirty = true
                    this@LightChunk.setVoxelDirty(packedPosition)
                }
            }

            private fun destroyHandle(handle: Handle) {
                require(handles.remove(handle))

                if (handles.size == 0) {
                    removeCell(this)
                }
                else {
                    changeHandleBrightness()
                }
            }

            fun createHandle(notifier: GhostLightNotifier?) = Handle(notifier).also {
                handles.add(it)
            }

            fun handleBlockEvent() {
                if(isOpen) {
                    handles.forEach { it.notifier?.onUpdate(it, GhostLightUpdateType.Opened) }
                }
                else {
                    handles.forEach { it.notifier?.onUpdate(it, GhostLightUpdateType.Closed) }
                }
            }

            fun resetDirty() {
                isDirty = false
            }

            private inner class Handle(val notifier: GhostLightNotifier?) : GhostLight {
                var handleBrightness: Int = 0
                var isDestroyed = false

                override val position: BlockPos get() = this@Cell.position
                override val packedPos: Int get() = this@Cell.packedPosition
                override val level: ServerLevel get() = this@LightChunk.lightLevel.gameLevel
                override val isOpen: Boolean get() = this@Cell.isOpen

                override fun setBrightness(brightness: Int) {
                    if (isDestroyed) {
                        error("Cannot set brightness, handle destroyed!")
                    }

                    if (brightness == this@Handle.handleBrightness) {
                        return
                    }

                    this@Handle.handleBrightness = brightness
                    this@Cell.changeHandleBrightness()
                }

                override fun destroy() {
                    if (!isDestroyed) {
                        this@Cell.destroyHandle(this)
                    }
                }
            }
        }
    }
}

@ClientOnly
object GhostLightHackClient {
    private val grid = ConcurrentHashMap<SectionPos, ByteArray>()
    private val preparedChunks = HashSet<ChunkPos>()

    private val pool = LinearObjectPool(
        DefaultPooledObjectPolicy(::allocateSection, ::clearSection),
        16384
    )

    /**
     * Gets the brightness at [blockPos].
     * Safe to call from multiple threads.
     * */
    @CrossThreadAccess
    @JvmStatic
    fun getBlockBrightness(blockPos: BlockPos): Int {
        val section = grid[SectionPos.of(blockPos)]
            ?: return 0

        return section[blockIndex(blockPos.x, blockPos.y, blockPos.z)].toInt()
    }

    /**
     * Prepares storage to handle ghost light data for the chunk at [chunkPos], if the chunk is not already prepared.
     * */
    @OnClientThread
    fun addChunk(chunkPos: ChunkPos) {
        requireIsOnRenderThread {
            "addChunk called on non-render thread"
        }

        if(!preparedChunks.add(chunkPos)) {
            return // Already prepared
        }

        forEachSection(chunkPos) {
            grid.putUnique(it, pool.get())
        }
    }

    /**
     * Removes the data stores for the chunk at [chunkPos], if it exists.
     * */
    @OnClientThread
    fun removeChunk(chunkPos: ChunkPos) {
        requireIsOnRenderThread {
            "removeChunk called on non-render thread"
        }

        if(!preparedChunks.remove(chunkPos)) {
            return
        }

        forEachSection(chunkPos) {
            val section = grid.remove(it)
            check(section != null)
            pool.release(section)
        }
    }

    /**
     * Updates the given stored [PackedLightVoxel] values for the chunk at [chunkPos] and notifies the game in order to update rendering.
     * The chunk must be prepared prior to calling this.
     * */
    fun commit(chunkPos: ChunkPos, values: LongArray) {
        requireIsOnRenderThread {
            "Tried to commit on non-render thread"
        }
        
        require(preparedChunks.contains(chunkPos)) {
            "Tried to commit unprepared $chunkPos"
        }

        val level = checkNotNull(Minecraft.getInstance().level) {
            "Expected level in commit"
        }

        val lightUpdater = LightUpdater.get(level)

        val renderer = checkNotNull(Minecraft.getInstance().levelRenderer) {
            "Expected level renderer in commit"
        }

        val changedSections = LongOpenHashSet()

        values.forEach { value ->
            val packedVoxel = PackedLightVoxel(value)

            val chunkRelativePos = packedVoxel.relativePosition

            val sectionPos = SectionPos.of(
                chunkPos.x,
                SectionPos.blockToSectionCoord(chunkRelativePos.y),
                chunkPos.z
            )

            val section = checkNotNull(grid[sectionPos]) {
                "Expected pre-loaded section at $sectionPos"
            }

            val index = sectionIndex(
                chunkRelativePos.x,
                SectionPos.sectionRelative(chunkRelativePos.y),
                chunkRelativePos.z
            )

            section[index] = packedVoxel.lightValue

            changedSections.add(sectionPos.asLong())
        }

        changedSections.forEach {
            val section = SectionPos.of(it)
            renderer.setSectionDirtyWithNeighbors(section.x, section.y, section.z)
            lightUpdater.onLightUpdate(LightLayer.BLOCK, it)
        }
    }

    fun clear() {
        grid.values.forEach {
            pool.release(it)
        }

        grid.clear()
        preparedChunks.clear()
    }

    private fun allocateSection() : ByteArray {
        return ByteArray(16 * 16 * 16)
    }

    private fun clearSection(array: ByteArray) : Boolean {
        array.fill(0)
        return true
    }

    private fun forEachSection(chunkPos: ChunkPos, use: (SectionPos) -> Unit) {
        val level = checkNotNull(Minecraft.getInstance().level) {
            "Level is null in column"
        }

        SectionPos.betweenClosedStream(
            chunkPos.x,
            level.minSection,
            chunkPos.z,
            chunkPos.x,
            level.maxSection,
            chunkPos.z
        ).forEach(use)
    }

    private fun blockIndex(x: Int, y: Int, z: Int) = sectionIndex(
        SectionPos.sectionRelative(x),
        SectionPos.sectionRelative(y),
        SectionPos.sectionRelative(z)
    )

    private fun sectionIndex(x: Int, y: Int, z: Int) = x + 16 * (y + 16 * z)
}
