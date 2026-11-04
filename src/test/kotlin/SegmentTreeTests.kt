import org.ageseries.libage.data.ClosedInterval
import org.ageseries.libage.data.SegmentTreeBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class SegmentTreeTests {
    @Test
    fun closedIntervalRejectsEmptyAndInvertedRanges() {
        assertThrows<IllegalStateException> { ClosedInterval(1.0, 1.0) }
        assertThrows<IllegalStateException> { ClosedInterval(2.0, 1.0) }
    }

    @Test
    fun closedIntervalContainsBothEndpoints() {
        val interval = ClosedInterval(1.0, 2.0)

        assertTrue(interval.contains(1.0))
        assertTrue(interval.contains(2.0))
        assertTrue(interval.contains(1.5))
        assertTrue(!interval.contains(0.999))
        assertTrue(!interval.contains(2.001))
    }

    @Test
    fun buildingEmptyTreeFails() {
        assertThrows<IllegalStateException> { SegmentTreeBuilder<Int>().build() }
    }

    @Test
    fun singleSegmentTreeAnswersInsideAndRejectsOutside() {
        val builder = SegmentTreeBuilder<String>()
        builder.insert("only", ClosedInterval(0.0, 10.0))
        val tree = builder.build()

        assertEquals("only", tree.query(0.0))
        assertEquals("only", tree.query(10.0))
        assertEquals("only", tree.query(5.0))
        assertNull(tree.queryOrNull(-0.001))
        assertNull(tree.queryOrNull(10.001))
        assertThrows<IllegalStateException> { tree.query(11.0) }
    }

    @Test
    fun sharedBoundaryResolvesToLeftSegment() {
        val builder = SegmentTreeBuilder<String>()
        builder.insert("left", ClosedInterval(0.0, 1.0))
        builder.insert("right", ClosedInterval(1.0, 2.0))
        val tree = builder.build()

        assertEquals("left", tree.query(1.0))
        assertEquals("left", tree.query(0.5))
        assertEquals("right", tree.query(1.5))
    }

    @Test
    fun sortAllowsOutOfOrderInsertion() {
        val builder = SegmentTreeBuilder<Int>()
        builder.insert(2, ClosedInterval(2.0, 3.0))
        builder.insert(0, ClosedInterval(0.0, 1.0))
        builder.insert(1, ClosedInterval(1.0, 2.0))
        builder.sort()
        val tree = builder.build()

        assertEquals(0, tree.query(0.5))
        assertEquals(1, tree.query(1.5))
        assertEquals(2, tree.query(2.5))
    }

    @Test
    fun pointInGapBetweenSegmentsIsNotFound() {
        val builder = SegmentTreeBuilder<Int>()
        builder.insert(0, ClosedInterval(0.0, 1.0))
        builder.insert(1, ClosedInterval(2.0, 3.0))
        val tree = builder.build()

        assertNull(tree.queryOrNull(1.5))
        assertThrows<IllegalStateException> { tree.query(1.5) }
        assertEquals(0, tree.query(1.0))
        assertEquals(1, tree.query(2.0))
    }

    @Test
    fun nanQueryIsNotFound() {
        val builder = SegmentTreeBuilder<Int>()
        builder.insert(0, ClosedInterval(0.0, 1.0))
        val tree = builder.build()

        assertNull(tree.queryOrNull(Double.NaN))
    }

    @Test
    fun randomizedQueriesMatchLinearScan() {
        val random = Random(7)

        for (segmentCount in listOf(1, 2, 3, 5, 8, 17, 64)) {
            val boundaries = ArrayList<Double>()
            var current = random.nextDouble(-10.0, 10.0)
            boundaries.add(current)

            repeat(segmentCount) {
                current += random.nextDouble(0.01, 3.0)
                boundaries.add(current)
            }

            val builder = SegmentTreeBuilder<Int>()

            for (index in 0 until segmentCount) {
                builder.insert(index, ClosedInterval(boundaries[index], boundaries[index + 1]))
            }

            val tree = builder.build()

            repeat(500) {
                val point = random.nextDouble(boundaries.first() - 1.0, boundaries.last() + 1.0)
                val expected = (0 until segmentCount).firstOrNull { point >= boundaries[it] && point <= boundaries[it + 1] }

                assertEquals(expected, tree.queryOrNull(point))
            }

            for (index in 0 until segmentCount) {
                assertEquals(index, tree.query((boundaries[index] + boundaries[index + 1]) / 2.0))
            }
        }
    }
}
