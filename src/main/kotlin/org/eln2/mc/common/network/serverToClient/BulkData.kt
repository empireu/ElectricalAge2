@file:OptIn(ExperimentalSerializationApi::class)

package org.eln2.mc.common.network.serverToClient

import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.network.NetworkEvent
import org.eln2.mc.CrossThreadAccess
import org.eln2.mc.LOG
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.network.Networking
import org.eln2.mc.data.AveragingList
import org.eln2.mc.extensions.formatted
import org.eln2.mc.reflectId
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.function.Supplier

fun ByteBuffer.putBlockPos(pos: BlockPos) {
    this.putInt(pos.x)
    this.putInt(pos.y)
    this.putInt(pos.z)
}

fun ByteBuffer.getBlockPos() = BlockPos(this.int, this.int, this.int)

fun ByteBuffer.putDirection(dir: Direction): ByteBuffer = this.putInt(dir.get3DDataValue())
fun ByteBuffer.getDirection(): Direction = Direction.from3DDataValue(this.int)

fun ByteBuffer.putArray(array: ByteArray) {
    this.putInt(array.size)

    if (array.isNotEmpty()) {
        this.put(array)
    }
}

fun ByteBuffer.getArray(): ByteArray {
    val result = ByteArray(this.int)

    if (result.isNotEmpty()) {
        this.get(result)
    }

    return result
}

fun Int.encode(): ByteArray {
    val result = ByteArray(4)
    result[0] = (this shr 0).toByte()
    result[1] = (this shr 8).toByte()
    result[2] = (this shr 16).toByte()
    result[3] = (this shr 24).toByte()
    return result
}

fun ByteArray.decodeInt(): Int =
    (this[3].toInt() shl 24) or
        (this[2].toInt() and 0xff shl 16) or
        (this[1].toInt() and 0xff shl 8) or
        (this[0].toInt() and 0xff)

infix fun ByteBuffer.with(i: Int): ByteBuffer {
    this.putInt(i)
    return this
}

infix fun ByteBuffer.with(d: Double): ByteBuffer {
    this.putDouble(d)
    return this
}

fun saveByteArrays(messages: List<ByteArray>): ByteBuffer {
    val buffer = ByteBuffer.allocate(messages.sumOf { it.size } + messages.size * 4)

    buffer.putInt(messages.size)

    messages.forEach { message ->
        buffer.putArray(message)
    }

    return buffer
}

fun loadByteArrays(buffer: ByteBuffer): ArrayList<ByteArray> {
    val cnt = buffer.int
    val results = ArrayList<ByteArray>(cnt)

    repeat(cnt) {
        results.add(buffer.getArray())
    }

    return results
}

interface InWorldMessage {
    val pos: BlockPos
    val size: Int
}

class BlockEntityMessage(override val pos: BlockPos, val payload: ByteArray) : InWorldMessage {
    override val size get() = 3 * 4 + (4 + payload.size)

    fun save(buffer: ByteBuffer) {
        buffer.putBlockPos(pos)
        buffer.putArray(payload)
    }

    companion object {
        fun load(buffer: ByteBuffer) = BlockEntityMessage(
            buffer.getBlockPos(),
            buffer.getArray()
        )
    }
}

class PartMessage(override val pos: BlockPos, val face: Direction, val payload: ByteArray) : InWorldMessage {
    override val size get() = 3 * 4 + 1 * 4 + (4 + payload.size)

    fun save(buffer: ByteBuffer) {
        buffer.putBlockPos(pos)
        buffer.putDirection(face)
        buffer.putArray(payload)
    }

    companion object {
        fun load(buffer: ByteBuffer) = PartMessage(
            buffer.getBlockPos(),
            buffer.getDirection(),
            buffer.getArray()
        )
    }
}

fun interface MessageWriter<M : InWorldMessage> {
    fun write(message: M, buffer: ByteBuffer)
}

fun interface MessageReader<M : InWorldMessage> {
    fun read(buffer: ByteBuffer) : M
}

/**
 * "Bulk" means all the messages sent in a tick are batched into a big [BulkDimensionMessage] that is sent at the end of the tick.
 * It is useful for sending many synchronization messages each tick for visual things (e.g. temperatures of wires, rotations of knobs, ...)
 * */
//#region Server to Client (bulk) Messages

/**
 * Collection of [InWorldMessage]s aimed at a particular level.
 * */
abstract class BulkDimensionMessage<M : InWorldMessage>(val dim: Int, val messages: List<M>) {
    private fun calculateSize() = 1 * 4 + 1 * 4 + messages.sumOf { it.size }

    /**
     * Dispatches the [messages] to their target game objects.
     * */
    abstract fun dispatchInLevel(level: ClientLevel)

    companion object {
        fun<M : InWorldMessage> packArray(bulkDimensionMessage: BulkDimensionMessage<M>, writer: MessageWriter<M>): ByteArray {
            val result = ByteArray(bulkDimensionMessage.calculateSize())
            val buffer = ByteBuffer.wrap(result)

            buffer.putInt(bulkDimensionMessage.dim)
            buffer.putInt(bulkDimensionMessage.messages.size)

            bulkDimensionMessage.messages.forEach {
                writer.write(it, buffer)
            }

            return result
        }

        fun<M : InWorldMessage, B : BulkDimensionMessage<M>> unpackArray(data: ByteArray, reader: MessageReader<M>, factory: (Int, List<M>) -> B): B {
            val buffer = ByteBuffer.wrap(data)

            val dim = buffer.int
            val count = buffer.int

            val results = ArrayList<M>(count)

            repeat(count) {
                results.add(reader.read(buffer))
            }

            return factory(dim, results)
        }

        fun<M : InWorldMessage> handle(message: BulkDimensionMessage<M>, ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable {
                        val actualLevel = Minecraft.getInstance().level

                        if (actualLevel == null) {
                            LOG.error("Got bulk message, but level is null")
                            return@Runnable
                        }

                        if (actualLevel.dimension().registry().id() != message.dim) {
                            // Cheap check to make sure we don't get a badly timed packet
                            return@Runnable
                        }

                        message.dispatchInLevel(actualLevel)
                    }
                }
            }

            ctx.get().packetHandled = true
        }
    }
}

/**
 * Implemented by block entities that wish to receive bulk messages.
 * P.S. Parts have this API built into the base class, so there's no counterpart interface for them.
 * */
interface BulkMessageHandlerBlockEntity {
    fun handleBulkMessage(payload: ByteArray)
}

class BulkDimensionMessageBlockEntity(dim: Int, messages: List<BlockEntityMessage>) : BulkDimensionMessage<BlockEntityMessage>(dim, messages) {
    override fun dispatchInLevel(level: ClientLevel) {
        messages.forEach { msg ->
            val entity = level.getBlockEntity(msg.pos) as? BulkMessageHandlerBlockEntity

            if (entity == null) {
                LOG.error("Rogue block entity message $msg")
                return@forEach
            }

            entity.handleBulkMessage(msg.payload)
        }
    }

    companion object {
        fun encode(message: BulkDimensionMessage<BlockEntityMessage>, buf: FriendlyByteBuf): FriendlyByteBuf = buf.writeByteArray(
            packArray(
                message,
                BlockEntityMessage::save
            )
        )

        fun decode(buf: FriendlyByteBuf) : BulkDimensionMessageBlockEntity = unpackArray(
            buf.readByteArray(),
            BlockEntityMessage::load,
            ::BulkDimensionMessageBlockEntity
        )

        fun handle(message: BulkDimensionMessageBlockEntity, ctx: Supplier<NetworkEvent.Context>) = handle<BlockEntityMessage>(message, ctx)
    }
}

class BulkDimensionMessagePart(dim: Int, messages: List<PartMessage>) : BulkDimensionMessage<PartMessage>(dim, messages) {
    override fun dispatchInLevel(level: ClientLevel) {
        messages.forEach { msg ->
            val entity = level.getBlockEntity(msg.pos)

            if (entity !is MultipartBlockEntity) {
                LOG.error("Rogue multipart message $msg")
                return@forEach
            }

            val part = entity.getPart(msg.face)

            if (part == null) {
                LOG.error("Lingering multipart $msg")
                return@forEach
            }

            part.handleBulkMessage(msg.payload)
        }
    }

    companion object {
        fun encode(message: BulkDimensionMessage<PartMessage>, buf: FriendlyByteBuf): FriendlyByteBuf = buf.writeByteArray(
            packArray(
                message,
                PartMessage::save
            )
        )

        fun decode(buf: FriendlyByteBuf) : BulkDimensionMessagePart = unpackArray(
            buf.readByteArray(),
            PartMessage::load,
            ::BulkDimensionMessagePart
        )

        fun handle(message: BulkDimensionMessagePart, ctx: Supplier<NetworkEvent.Context>) = handle<PartMessage>(message, ctx)
    }
}

@Mod.EventBusSubscriber
object BulkMessages {
    private val blockMessagesPerTickAverage = AveragingList(100)
    private val partMessagesPerTickAverage = AveragingList(100)
    private var lastLog = 0

    @CrossThreadAccess
    private val bulkBlockEntityMessages = ConcurrentHashMap<ServerLevel, ConcurrentLinkedDeque<BlockEntityMessage>>()

    @CrossThreadAccess
    private val bulkPartMessages = ConcurrentHashMap<ServerLevel, ConcurrentLinkedDeque<PartMessage>>()

    fun enqueueBlockEntityMessage(level: ServerLevel, msg: BlockEntityMessage) = bulkBlockEntityMessages
        .getOrPut(level, ::ConcurrentLinkedDeque)
        .add(msg)

    fun enqueuePartMessage(level: ServerLevel, msg: PartMessage) = bulkPartMessages
        .getOrPut(level, ::ConcurrentLinkedDeque)
        .add(msg)

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.END) {
            //flushPartData()
            blockMessagesPerTickAverage.addSample(
                flush(bulkBlockEntityMessages, ::BulkDimensionMessageBlockEntity).toDouble()
            )

            partMessagesPerTickAverage.addSample(
                flush(bulkPartMessages, ::BulkDimensionMessagePart).toDouble()
            )

            if(++lastLog == 100) {
                lastLog = 0
                LOG.debug("Bulk messages per tick: ${blockMessagesPerTickAverage.calculate().formatted()} BE, ${partMessagesPerTickAverage.calculate().formatted()} Part")
            }
        }
    }

    private fun<I : InWorldMessage> flush(map: Map<ServerLevel, ConcurrentLinkedDeque<I>>, factory: (Int, List<I>) -> BulkDimensionMessage<I>) : Int {
        var total = 0

        map.forEach { (level, packetQueue) ->
            val perChunkPackets = HashMap<ChunkPos, ArrayList<I>>()

            while (true) {
                val packet = packetQueue.poll()
                    ?: break

                val chunkPos = ChunkPos(packet.pos)
                var packetList = perChunkPackets[chunkPos]

                if(packetList == null) {
                    packetList = ArrayList()
                    require(perChunkPackets.put(chunkPos, packetList) == null)
                }

                packetList.add(packet)
            }

            val chunkMap = level.chunkSource.chunkMap
            val dim = level.dimension().registry().id()

            perChunkPackets.forEach { (chunkPos, packets) ->
                total += packets.size

                val message = factory(dim, packets)

                chunkMap.getPlayers(chunkPos, false).forEach { player ->
                    Networking.send(message, player)
                }
            }
        }

        return total
    }
}

//#endregion

/**
 * Single messages that are directly sent, without batching, from the client to the server.
 * Meant to be used for e.g. GUI changes that need to be processed on the server.
 * */
//#region Client to Server Messages

/**
 * Single [InWorldMessage] aimed at a particular level. Sent from the client to the server.
 * */
abstract class DimensionMessageToServer<M : InWorldMessage>(val dim: Int, val message: M) {
    private fun calculateSize() = 1 * 4 + message.size

    /**
     * Dispatches the [message] to the target game object.
     * */
    abstract fun dispatchInLevel(level: ServerLevel, sender: ServerPlayer)

    companion object {
        fun<M : InWorldMessage> packArray(dimensionMessage: DimensionMessageToServer<M>, writer: MessageWriter<M>): ByteArray {
            val result = ByteArray(dimensionMessage.calculateSize())
            val buffer = ByteBuffer.wrap(result)

            buffer.putInt(dimensionMessage.dim)
            writer.write(dimensionMessage.message, buffer)

            return result
        }

        fun<M : InWorldMessage, D : DimensionMessageToServer<M>> unpackArray(data: ByteArray, reader: MessageReader<M>, factory: (Int, M) -> D): D {
            val buffer = ByteBuffer.wrap(data)

            val dim = buffer.int
            val result = reader.read(buffer)

            return factory(dim, result)
        }

        fun<M : InWorldMessage> handle(message: DimensionMessageToServer<M>, ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork {
                val sender = ctx.get().sender!!
                val actualLevel = sender.level() as ServerLevel

                if (actualLevel.dimension().registry().id() != message.dim) {
                    // Cheap check to make sure we don't get a badly timed packet
                    return@enqueueWork
                }

                message.dispatchInLevel(actualLevel, sender)
            }

            ctx.get().packetHandled = true
        }
    }
}

class DimensionMessageToServerPart(dim: Int, message: PartMessage) : DimensionMessageToServer<PartMessage>(dim, message) {
    override fun dispatchInLevel(level: ServerLevel, sender: ServerPlayer) {
        val entity = level.getBlockEntity(message.pos)

        if (entity !is MultipartBlockEntity) {
            LOG.error("Rogue multipart message $message from $sender")
            return
        }

        val part = entity.getPart(message.face)

        if (part == null) {
            LOG.error("Lingering multipart $message from $sender")
            return
        }

        part.handleMessageFromClient(message.payload, sender)
    }

    companion object {
        fun encode(message: DimensionMessageToServer<PartMessage>, buf: FriendlyByteBuf): FriendlyByteBuf = buf.writeByteArray(
            packArray(
                message,
                PartMessage::save
            )
        )

        fun decode(buf: FriendlyByteBuf) : DimensionMessageToServerPart = unpackArray(
            buf.readByteArray(),
            PartMessage::load,
            ::DimensionMessageToServerPart
        )

        fun handle(message: DimensionMessageToServerPart, ctx: Supplier<NetworkEvent.Context>) =
            handle<PartMessage>(message, ctx)
    }
}

//#endregion

fun ResourceLocation.id(): Int = this.hashCode()

fun interface ClientSidePacketConsumer {
    fun handle(binary: ByteArray)
}

fun interface ServerSidePacketConsumer {
    fun handle(binary: ByteArray, source: ServerPlayer)
}

class ClientSidePacketHandlerBuilder {
    val registeredIds = HashMap<Int, ClientSidePacketConsumer>()

    inline fun <reified P> withHandler(crossinline consume: (P) -> Unit): ClientSidePacketHandlerBuilder {
        registeredIds[P::class.reflectId] = ClientSidePacketConsumer {
            val instance = Cbor.decodeFromByteArray<P>(it)

            try {
                consume(instance)
            } catch (t: Throwable) {
                LOG.error("Failed to handle ${P::class}: $t")
            }
        }

        return this
    }

    fun build() = ClientSidePacketHandler(registeredIds.toMap())
}

class ServerSidePacketHandlerBuilder {
    val registeredIds = HashMap<Int, ServerSidePacketConsumer>()

    inline fun <reified P> withHandler(crossinline consume: (P, ServerPlayer) -> Unit): ServerSidePacketHandlerBuilder {
        registeredIds[P::class.reflectId] = ServerSidePacketConsumer { payload, player ->
            val instance = Cbor.decodeFromByteArray<P>(payload)

            try {
                consume(instance, player)
            } catch (t: Throwable) {
                LOG.error("Failed to handle on server ${P::class}: $t, from $player")
            }
        }

        return this
    }

    fun build() = ServerSidePacketHandler(registeredIds.toMap())
}

class ClientSidePacketHandler(private val registeredIds: Map<Int, ClientSidePacketConsumer>) {
    fun handle(data: ByteArray): Boolean {
        val buffer = ByteBuffer.wrap(data)

        val id = buffer.int

        val handler = registeredIds[id]

        if (handler == null) {
            LOG.error("Unhandled packet on client $id")
            return false
        }

        val payload = ByteArray(data.size - 4)
        buffer.get(payload)
        handler.handle(payload)

        return true
    }

    companion object {
        inline fun <reified P> encode(packet: P): ByteArray {
            val data = Cbor.encodeToByteArray(packet)

            val sendBuffer = ByteArray(4 + data.size)
            val result = ByteBuffer.wrap(sendBuffer)

            result.putInt(P::class.reflectId)
            result.put(data)

            return sendBuffer
        }
    }
}

class ServerSidePacketHandler(private val registeredIds: Map<Int, ServerSidePacketConsumer>) {
    fun handle(data: ByteArray, source: ServerPlayer): Boolean {
        val buffer = ByteBuffer.wrap(data)

        val id = buffer.int

        val handler = registeredIds[id]

        if (handler == null) {
            LOG.error("Unhandled packet on server $id from $source")
            return false
        }

        val payload = ByteArray(data.size - 4)
        buffer.get(payload)
        handler.handle(payload, source)

        return true
    }

    companion object {
        inline fun <reified P> encode(packet: P): ByteArray {
            val data = Cbor.encodeToByteArray(packet)

            val sendBuffer = ByteArray(4 + data.size)
            val result = ByteBuffer.wrap(sendBuffer)

            result.putInt(P::class.reflectId)
            result.put(data)

            return sendBuffer
        }
    }
}

