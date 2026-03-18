@file:Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE", "MemberVisibilityCanBePrivate")

package org.ageseries.libage.data

import org.ageseries.libage.utils.putUnique
import org.ageseries.libage.utils.sourceName
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Function that serializes [T] to a byte buffer. The number of bytes written **must be equal to [LocatorShardDefinition.sizeInBytes].**
 * */
fun interface LocatorShardWriter<T> {
    fun writeToByteBuffer(shard: T, buffer: ByteBuffer)
}

/**
 * Function that deserializes [T] from a byte buffer. The number of bytes read **must be equal to [LocatorShardDefinition.sizeInBytes].**
 * */
fun interface LocatorShardReader<T> {
    fun readFromByteBuffer(buffer: ByteBuffer) : T
}

/**
 * Exception thrown when [LocatorShardWriter] or [LocatorShardReader] don't write/read the expected number of bytes.
 * */
class LocatorSerializationException(message: String) : IllegalStateException(message)

/**
 * A locator shard is a data-containing component.
 * Multiple shards make up a [Locator], forming a *set* (that doesn't allow the existence of multiple instances of the same shard).
 * Each shard stores data of type [T], and different shards can share the same data type.
 * @param dispatcher The dispatcher that registered this shard.
 * @param shardClass The class of [T].
 * @param shardID The ID of this shard, determined behind the scenes by the dispatcher.
 * @param sizeInBytes The exact size in bytes of the data.
 * @param writer A function to serialize [T]. It must write exactly [sizeInBytes] bytes to the buffer.
 * @param reader A function to deserialize [T]. It must read exactly [sizeInBytes] bytes from the buffer.
 * */
class LocatorShardDefinition<T : Any>(
    val dispatcher: LocatorDispatcher<*>,
    val shardClass: Class<T>,
    val shardID: Byte,
    val sizeInBytes: Int,
    val writer: LocatorShardWriter<T>,
    val reader: LocatorShardReader<T>
) {
    internal inline fun write(value: Any, buffer: ByteBuffer) = writer.writeToByteBuffer(value as T, buffer)
    internal inline fun read(buffer: ByteBuffer) : Any = reader.readFromByteBuffer(buffer)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LocatorShardDefinition<*>

        if (dispatcher != other.dispatcher) return false
        if (shardID != other.shardID) return false

        return true
    }

    override fun hashCode(): Int {
        var result = dispatcher.hashCode()
        result = 31 * result + shardID
        return result
    }
}

class LocatorBuilder(val dispatcher: LocatorDispatcher<*>) {
    private val data = HashMap<Byte, Any>()

    fun put(id: Byte, value: Any) : LocatorBuilder {
        val definition = dispatcher.getDefinition(id)

        check(value.javaClass == definition.shardClass) {
            "Expected ${definition.shardClass} but got ${value.javaClass}"
        }

        data.putUnique(id, value) {
            "Duplicate locator shard $id ${definition.shardClass} $value"
        }

        return this
    }

    fun build() : Locator {
        check(data.isNotEmpty()) {
            "Tried to build empty locator"
        }

        val sorted = data.toList().sortedBy { it.first }

        val archetype = sorted.map { it.first }.toByteArray()
        val shardData = sorted.map { it.second }.toTypedArray()

        return Locator(dispatcher, dispatcher.getArchetype(archetype), shardData)
    }
}

// Extension because colors
inline fun<reified T : Any> LocatorBuilder.put(definition: LocatorShardDefinition<T>, value: T) = put(definition.shardID, value)

/**
 * Registry for locator shards.
 * This class is meant to be extended by an *object*, and the locator shards registered as fields in the derived class.
 * The [Self] type parameter is meant to remove the need to qualify locator definitions when using [buildLocator].
 * */
abstract class LocatorDispatcher<Self : LocatorDispatcher<Self>> {
    protected var definitions = emptyArray<LocatorShardDefinition<*>>()
    protected var currentID = 0

    internal class ArchetypeKey(val array: ByteArray) {
        val hash = array.contentHashCode()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ArchetypeKey

            return array.contentEquals(other.array)
        }

        override fun hashCode() = hash
    }

    internal val archetypes = ConcurrentHashMap<ArchetypeKey, ByteArray>()

    /**
     * Gets or stores the [equivalent] into [archetypes].
     * This is done to somewhat increase locality by not using a unique array per locator instance.
     * */
    internal fun getArchetype(equivalent: ByteArray) : ByteArray {
        val key = ArchetypeKey(equivalent)

        return archetypes.computeIfAbsent(key) {
            for (i in 1 until equivalent.size) {
                check(equivalent[i] > equivalent[i - 1])
            }

            equivalent.clone()
        }
    }

    /**
     * Gets the shard definition by its [id].
     * */
    internal fun getDefinition(id: Byte) = definitions[id.toInt()]

    /**
     * Registers a shard.
     * @param write Serializer function for the data.
     * @param read Deserializer function for the data.
     * @param size The size in bytes of the data.
     * */
    protected inline fun<reified T : Any> register(write: LocatorShardWriter<T>, read: LocatorShardReader<T>, size: Int) : LocatorShardDefinition<T> {
        val shardClass = T::class.java
        val id = (currentID++).toByte()

        val entry = LocatorShardDefinition(
            this,
            shardClass,
            id,
            size,
            write,
            read,
        )

        definitions = definitions.plus(entry)

        return entry
    }

    /**
     * Registers a shard based on the serializers from [definition].
     * */
    protected inline fun<reified T : Any> defineFrom(definition: LocatorShardDefinition<T>) : LocatorShardDefinition<T> {
        require(definition.dispatcher === this) {
            "Cannot define from other dispatcher"
        }

        val shardClass = T::class.java
        val id = (currentID++).toByte()

        val entry = LocatorShardDefinition(
            this,
            shardClass,
            id,
            definition.sizeInBytes,
            definition.writer,
            definition.reader,
        )

        definitions = definitions.plus(entry)

        return entry
    }

    /**
     * Deserializes a locator from its [image].
     * */
    fun fromImage(image: ByteArray) : Locator {
        val buffer = ByteBuffer.wrap(image)
        val count = buffer.get().toInt()

        val archetype = ByteArray(count)
        val shardData = Array(count) {
            val id = buffer.get()
            archetype[it] = id

            val definition = getDefinition(id)

            val position = buffer.position()
            val result = definition.read(buffer)

            if((buffer.position() - position) != definition.sizeInBytes) {
                throw LocatorSerializationException("Invalid number of bytes read by ${definition.shardClass}")
            }

            result
        }

        for (i in 1 until archetype.size) {
            check(archetype[i] > archetype[i - 1]) {
                "Invalid archetype in deserialized image"
            }
        }

        check(buffer.position() == image.size)

        return Locator(this, getArchetype(archetype), shardData)
    }

    /**
     * Creates a new [LocatorBuilder] for this dispatcher.
     * */
    fun builder() = LocatorBuilder(this)

    /**
     * Builds a [Locator] under this dispatcher using a [LocatorBuilder].
     * */
    fun buildLocator(action: Self.(builder: LocatorBuilder) -> Unit) : Locator {
        val builder = LocatorBuilder(this)
        action(this as Self, builder)
        return builder.build()
    }
}

/**
 * A Locator is a set of unique data shards, each holding specific data.
 * It resembles a state vector describing an object's configuration, especially its position in a game world.
 * Shards are organized by a unique shard ID, determined at registration.
 * The Locator and its shards are immutable, meaning they can't be changed once created, and the data within the shards is also immutable (but this can't and isn't enforced so guaranteeing immutability is left to the implementers downstream).
 * */
class Locator internal constructor(val dispatcher: LocatorDispatcher<*>, internal val archetype: ByteArray, internal val shardData: Array<Any>) {
    /**
     * Gets the size in bytes of the image (locator serialized to bytes).
     * */
    val imageSize = 1 + // Number of shards
            archetype.size +  // Shard ID
            archetype.sumOf { dispatcher.getDefinition(it).sizeInBytes } // Data

    val count get() = archetype.size

    /**
     * Gets the shard with the [shardID].
     * @return The shard with the specified ID or null, if the locator doesn't have it.
     * */
    fun get(shardID: Byte) : Any? {
        val index = archetype.binarySearch(shardID)

        if(index < 0) {
            return null
        }

        return shardData[index]
    }

    /**
     * Gets the shard with the specified [definition].
     * @return The shard with the specified definition or null, if the locator doesn't have it.
     * */
    inline fun <reified T : Any> get(definition: LocatorShardDefinition<T>): T? {
        require(definition.dispatcher === dispatcher) {
            "Illegal use of other dispatcher"
        }

        val value = get(definition.shardID)
            ?: return null

        return value as T
    }

    /**
     * Checks if the locator has the specified shard.
     * @return True, if the locator has a shard with id [shardID]. Otherwise, false.
     * */
    fun has(shardID: Byte) = archetype.binarySearch(shardID) >= 0

    /**
     * Checks if the locator has a shard with the specified [definition].
     * @return True, if the locator has a shard with the specified [definition]. Otherwise, false.
     * */
    inline fun<reified T : Any> has(definition: LocatorShardDefinition<T>): Boolean {
        require(definition.dispatcher === dispatcher) {
            "Illegal use of other dispatcher"
        }

        return has(definition.shardID)
    }

    /**
     * Serializes this locator.
     * @return A byte array with the data stored in this locator, consisting of [imageSize] bytes.
     * */
    fun toImage() : ByteArray {
        val result = ByteArray(imageSize)
        val buffer = ByteBuffer.wrap(result)

        buffer.put(archetype.size.toByte())

        for (i in 0 until count) {
            val id = archetype[i]
            buffer.put(id)

            val definition = dispatcher.getDefinition(id)

            val position = buffer.position()
            definition.write(shardData[i], buffer)

            if((buffer.position() - position) != definition.sizeInBytes) {
                throw LocatorSerializationException("Invalid number of bytes written by ${definition.shardClass}")
            }
        }

        check(buffer.position() == result.size)

        return result
    }

    override fun toString() = buildString {
        archetype.forEachIndexed { index, shardId ->
            val shard = shardData[index]
            val shardClass = dispatcher.getDefinition(shardId).shardClass

            append("${shardClass.sourceName()}: $shard")

            if(index != archetype.size - 1) {
                append(", ")
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (javaClass != other?.javaClass) {
            return false
        }

        other as Locator

        return dispatcher === other.dispatcher && shardData.contentEquals(other.shardData)
    }

    private val cachedHashCode = run {
        var result = dispatcher.hashCode()
        result = 31 * result + archetype.contentHashCode()
        result = 31 * result + shardData.contentHashCode()
        result
    }

    override fun hashCode() = cachedHashCode
}


/**
 * Gets the locator. Throws an exception if it is not present.
 * */
inline fun<reified T : Any> Locator.requireLocator(definition: LocatorShardDefinition<T>, message: () -> String) : T {
    val value = this.get(definition)

    require(value != null) {
        message()
    }

    return value
}

/**
 * Gets the locator. Throws an exception if it is not present.
 * */
inline fun<reified T : Any> Locator.requireLocator(definition: LocatorShardDefinition<T>) =
    this.requireLocator(definition) {
        "Did not have required locator ${T::class}"
    }
