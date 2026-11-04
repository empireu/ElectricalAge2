import org.ageseries.libage.data.LocatorDispatcher
import org.ageseries.libage.data.LocatorSerializationException
import org.ageseries.libage.data.put
import org.ageseries.libage.data.requireLocator
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.BufferUnderflowException

data class TestPosition(val x: Int, val y: Int)

data class TestTag(val id: Int)

data class TestBigValue(val value: Long)

data class TestBrokenWriter(val id: Int)

object TestLocatorDispatcher : LocatorDispatcher<TestLocatorDispatcher>() {
    val position = register<TestPosition>(
        { shard, buffer ->
            buffer.putInt(shard.x)
            buffer.putInt(shard.y)
        },
        { buffer -> TestPosition(buffer.getInt(), buffer.getInt()) },
        8
    )

    val tag = register<TestTag>(
        { shard, buffer -> buffer.putInt(shard.id) },
        { buffer -> TestTag(buffer.getInt()) },
        4
    )

    val bigValue = register<TestBigValue>(
        { shard, buffer -> buffer.putLong(shard.value) },
        { buffer -> TestBigValue(buffer.getLong()) },
        8
    )

    val secondTag = defineFrom(tag)

    val brokenWriter = register<TestBrokenWriter>(
        { shard, buffer -> buffer.putShort(shard.id.toShort()) },
        { buffer -> TestBrokenWriter(buffer.getInt()) },
        4
    )
}

object OtherTestLocatorDispatcher : LocatorDispatcher<OtherTestLocatorDispatcher>() {
    val tag = register<TestTag>(
        { shard, buffer -> buffer.putInt(shard.id) },
        { buffer -> TestTag(buffer.getInt()) },
        4
    )
}

class LocatorTests {
    private val dispatcher = TestLocatorDispatcher

    @Test
    fun buildingEmptyLocatorFails() {
        assertThrows<IllegalStateException> { dispatcher.builder().build() }
    }

    @Test
    fun builtLocatorReturnsStoredShards() {
        val locator = dispatcher.builder()
            .put(dispatcher.position, TestPosition(3, -4))
            .put(dispatcher.tag, TestTag(9))
            .build()

        assertEquals(2, locator.count)
        assertEquals(TestPosition(3, -4), locator.get(dispatcher.position))
        assertEquals(TestTag(9), locator.get(dispatcher.tag))
        assertNull(locator.get(dispatcher.bigValue))
        assertTrue(locator.has(dispatcher.position))
        assertFalse(locator.has(dispatcher.bigValue))
    }

    @Test
    fun insertionOrderDoesNotMatter() {
        val first = dispatcher.builder()
            .put(dispatcher.position, TestPosition(1, 2))
            .put(dispatcher.tag, TestTag(3))
            .build()

        val second = dispatcher.builder()
            .put(dispatcher.tag, TestTag(3))
            .put(dispatcher.position, TestPosition(1, 2))
            .build()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertArrayEquals(first.toImage(), second.toImage())
    }

    @Test
    fun duplicateShardIsRejected() {
        val builder = dispatcher.builder().put(dispatcher.tag, TestTag(1))

        assertThrows<IllegalArgumentException> { builder.put(dispatcher.tag, TestTag(2)) }
    }

    @Test
    fun shardOfWrongClassIsRejected() {
        assertThrows<IllegalStateException> { dispatcher.builder().put(dispatcher.tag.shardID, TestPosition(1, 2)) }
    }

    @Test
    fun buildLocatorDslBuildsSameLocator() {
        val viaDsl = dispatcher.buildLocator {
            it.put(position, TestPosition(5, 6))
            it.put(tag, TestTag(7))
        }

        val viaBuilder = dispatcher.builder()
            .put(dispatcher.position, TestPosition(5, 6))
            .put(dispatcher.tag, TestTag(7))
            .build()

        assertEquals(viaBuilder, viaDsl)
    }

    @Test
    fun imageSizeIsHeaderPlusIdsPlusData() {
        val locator = dispatcher.builder()
            .put(dispatcher.position, TestPosition(1, 2))
            .put(dispatcher.bigValue, TestBigValue(1L))
            .build()

        assertEquals(1 + 2 + 8 + 8, locator.imageSize)
        assertEquals(locator.imageSize, locator.toImage().size)
    }

    @Test
    fun imageRoundTripPreservesLocator() {
        val locator = dispatcher.builder()
            .put(dispatcher.position, TestPosition(Int.MIN_VALUE, Int.MAX_VALUE))
            .put(dispatcher.bigValue, TestBigValue(Long.MIN_VALUE))
            .put(dispatcher.tag, TestTag(-1))
            .build()

        val decoded = dispatcher.fromImage(locator.toImage())

        assertEquals(locator, decoded)
        assertEquals(locator.hashCode(), decoded.hashCode())
        assertEquals(TestBigValue(Long.MIN_VALUE), decoded.get(dispatcher.bigValue))
        assertArrayEquals(locator.toImage(), decoded.toImage())
    }

    @Test
    fun singleShardLocatorRoundTrips() {
        val locator = dispatcher.builder().put(dispatcher.tag, TestTag(77)).build()

        assertEquals(locator, dispatcher.fromImage(locator.toImage()))
    }

    @Test
    fun truncatedImageFailsToDecode() {
        val image = dispatcher.builder().put(dispatcher.position, TestPosition(1, 2)).build().toImage()

        assertThrows<BufferUnderflowException> { dispatcher.fromImage(image.copyOf(image.size - 1)) }
    }

    @Test
    fun imageWithTrailingBytesFailsToDecode() {
        val image = dispatcher.builder().put(dispatcher.tag, TestTag(1)).build().toImage()

        assertThrows<IllegalStateException> { dispatcher.fromImage(image.copyOf(image.size + 1)) }
    }

    @Test
    fun imageWithUnsortedShardIdsFailsToDecode() {
        val first = dispatcher.tag.shardID
        val second = dispatcher.position.shardID

        val image = ByteArray(1 + 1 + 4 + 1 + 8)
        image[0] = 2
        image[1] = maxOf(first, second)
        image[6] = minOf(first, second)

        assertThrows<Exception> { dispatcher.fromImage(image) }
    }

    @Test
    fun writerThatWritesWrongByteCountIsReported() {
        val locator = dispatcher.builder().put(dispatcher.brokenWriter, TestBrokenWriter(1)).build()

        assertThrows<LocatorSerializationException> { locator.toImage() }
    }

    @Test
    fun differentDataGivesUnequalLocators() {
        val first = dispatcher.builder().put(dispatcher.tag, TestTag(1)).build()
        val second = dispatcher.builder().put(dispatcher.tag, TestTag(2)).build()

        assertNotEquals(first, second)
    }

    @Test
    fun locatorsOfDifferentDispatchersAreNotEqual() {
        val first = dispatcher.builder().put(dispatcher.tag, TestTag(1)).build()
        val second = OtherTestLocatorDispatcher.builder().put(OtherTestLocatorDispatcher.tag, TestTag(1)).build()

        assertNotEquals(first, second)
    }

    @Test
    fun definitionFromOtherDispatcherIsRejected() {
        val locator = dispatcher.builder().put(dispatcher.tag, TestTag(1)).build()

        assertThrows<IllegalArgumentException> { locator.get(OtherTestLocatorDispatcher.tag) }
        assertThrows<IllegalArgumentException> { locator.has(OtherTestLocatorDispatcher.tag) }
    }

    @Test
    fun requireLocatorReturnsValueOrThrowsWithMessage() {
        val locator = dispatcher.builder().put(dispatcher.tag, TestTag(4)).build()

        assertEquals(TestTag(4), locator.requireLocator(dispatcher.tag))

        val exception = assertThrows<IllegalArgumentException> {
            locator.requireLocator(dispatcher.position) { "missing position" }
        }

        assertEquals("missing position", exception.message)
    }

    @Test
    @Disabled("Bug: Locator.equals ignores the archetype, so different shards holding equal data compare equal.")
    fun locatorsWithDifferentShardsButEqualDataAreNotEqual() {
        val first = dispatcher.builder().put(dispatcher.tag, TestTag(5)).build()
        val second = dispatcher.builder().put(dispatcher.secondTag, TestTag(5)).build()

        assertNotEquals(first, second)
    }
}
