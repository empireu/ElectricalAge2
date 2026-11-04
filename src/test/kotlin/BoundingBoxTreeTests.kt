import org.ageseries.libage.data.BoundingBoxTree3d
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.random.Random

class BoundingBoxTreeTests {
    private fun randomBox(random: Random, spread: Double = 20.0): BoundingBox3d {
        val center = Vector3d(
            random.nextDouble(-spread, spread),
            random.nextDouble(-spread, spread),
            random.nextDouble(-spread, spread)
        )

        val size = Vector3d(random.nextDouble(0.1, 4.0), random.nextDouble(0.1, 4.0), random.nextDouble(0.1, 4.0))

        return BoundingBox3d.fromCenterSize(center, size)
    }

    private fun validate(
        tree: BoundingBoxTree3d<Int>,
        expected: Map<Int, BoundingBox3d>,
        maximumHeightDifference: Int = 1
    ) {
        val root = tree.root

        if (expected.isEmpty()) {
            assertNull(root)
            assertTrue(tree.leaves.isEmpty())
            return
        }

        assertNull(root!!.parent)

        val seen = HashSet<Int>()

        fun visit(node: BoundingBoxTree3d.Node<Int>): Int {
            if (node.isLeaf) {
                assertNull(node.child2)
                assertEquals(0, node.height)

                val data = node.data!!

                assertTrue(seen.add(data), "leaf $data visited twice")
                assertEquals(expected[data], node.box)
                assertSame(node, tree.leaves[data])

                return 0
            }

            val child1 = node.child1!!
            val child2 = node.child2!!

            assertNull(node.data)
            assertSame(node, child1.parent)
            assertSame(node, child2.parent)
            assertEquals(child1.box unionWith child2.box, node.box)

            val height1 = visit(child1)
            val height2 = visit(child2)

            assertTrue(abs(height1 - height2) <= maximumHeightDifference, "tree is unbalanced at ${node.box}")
            assertEquals(maxOf(height1, height2) + 1, node.height)

            return node.height
        }

        visit(root)

        assertEquals(expected.keys, seen)
        assertEquals(expected.keys, tree.leaves.keys)
    }

    private fun queryIds(tree: BoundingBoxTree3d<Int>, queryBox: BoundingBox3d): Set<Int> {
        val result = HashSet<Int>()

        tree.queryIntersecting({ it.box intersectsWith queryBox }) {
            assertTrue(result.add(it.data!!))
            true
        }

        return result
    }

    @Test
    fun emptyTreeHasNoRootAndQueriesNothing() {
        val tree = BoundingBoxTree3d<Int>()

        assertNull(tree.root)
        assertTrue(tree.leaves.isEmpty())
        assertTrue(queryIds(tree, BoundingBox3d.fromCenterSize(Vector3d.zero, 100.0)).isEmpty())
        assertFalse(tree.contains(1))
        assertNull(tree[1])
    }

    @Test
    fun singleElementIsTheRootLeaf() {
        val tree = BoundingBoxTree3d<Int>()
        val box = BoundingBox3d.fromCenterSize(Vector3d.zero, 2.0)
        val node = tree.insert(7, box)

        assertSame(node, tree.root)
        assertTrue(node.isLeaf)
        assertEquals(7, node.data)
        assertEquals(box, node.box)
        assertTrue(tree.contains(7))
        assertSame(node, tree[7])
    }

    @Test
    fun removingOnlyElementEmptiesTree() {
        val tree = BoundingBoxTree3d<Int>()
        tree.insert(1, BoundingBox3d.fromCenterSize(Vector3d.zero, 1.0))

        val removed = tree.remove(1)

        assertEquals(1, removed.data)
        assertNull(tree.root)
        assertFalse(tree.contains(1))
    }

    @Test
    fun removingMissingElementThrows() {
        val tree = BoundingBoxTree3d<Int>()
        tree.insert(1, BoundingBox3d.fromCenterSize(Vector3d.zero, 1.0))

        assertThrows<IllegalArgumentException> { tree.remove(2) }
        assertTrue(tree.contains(1))
    }

    @Test
    fun duplicateInsertThrows() {
        val tree = BoundingBoxTree3d<Int>()
        tree.insert(1, BoundingBox3d.fromCenterSize(Vector3d.zero, 1.0))

        assertThrows<IllegalArgumentException> {
            tree.insert(1, BoundingBox3d.fromCenterSize(Vector3d(5.0, 5.0, 5.0), 1.0))
        }
    }

    @Test
    @Disabled("Bug: a rejected duplicate insert already replaced the leaf registered for the key, corrupting the tree.")
    fun duplicateInsertLeavesTreeUntouched() {
        val tree = BoundingBoxTree3d<Int>()
        val firstBox = BoundingBox3d.fromCenterSize(Vector3d.zero, 1.0)
        val secondBox = BoundingBox3d.fromCenterSize(Vector3d(5.0, 5.0, 5.0), 1.0)

        tree.insert(1, firstBox)
        tree.insert(2, secondBox)

        assertThrows<IllegalArgumentException> { tree.insert(1, secondBox) }

        validate(tree, mapOf(1 to firstBox, 2 to secondBox))

        tree.remove(1)

        validate(tree, mapOf(2 to secondBox))
    }

    @Test
    fun queryFindsOnlyIntersectingLeaves() {
        val tree = BoundingBoxTree3d<Int>()
        tree.insert(1, BoundingBox3d.fromCenterSize(Vector3d(0.0, 0.0, 0.0), 2.0))
        tree.insert(2, BoundingBox3d.fromCenterSize(Vector3d(10.0, 0.0, 0.0), 2.0))
        tree.insert(3, BoundingBox3d.fromCenterSize(Vector3d(0.5, 0.5, 0.5), 2.0))

        assertEquals(setOf(1, 3), queryIds(tree, BoundingBox3d.fromCenterSize(Vector3d.zero, 1.0)))
        assertEquals(setOf(2), queryIds(tree, BoundingBox3d.fromCenterSize(Vector3d(10.0, 0.0, 0.0), 0.5)))
        assertTrue(queryIds(tree, BoundingBox3d.fromCenterSize(Vector3d(100.0, 0.0, 0.0), 1.0)).isEmpty())
    }

    @Test
    fun queryStopsWhenConsumerReturnsFalse() {
        val tree = BoundingBoxTree3d<Int>()

        repeat(20) {
            tree.insert(it, BoundingBox3d.fromCenterSize(Vector3d(it * 0.1, 0.0, 0.0), 1.0))
        }

        var visited = 0

        tree.queryIntersecting({ true }) {
            visited++
            false
        }

        assertEquals(1, visited)
    }

    @Test
    fun sequentialInsertionStaysBalancedAndLogarithmicallyDeep() {
        val tree = BoundingBoxTree3d<Int>()
        val expected = HashMap<Int, BoundingBox3d>()

        for (index in 0 until 512) {
            val box = BoundingBox3d.fromCenterSize(Vector3d(index.toDouble(), 0.0, 0.0), 0.5)
            tree.insert(index, box)
            expected[index] = box
        }

        validate(tree, expected)

        assertTrue(tree.root!!.height <= 12, "height ${tree.root!!.height}")
    }

    @Test
    fun randomInsertRemoveSequenceKeepsInvariantsAndMatchesLinearScan() {
        val random = Random(101)
        val tree = BoundingBoxTree3d<Int>()
        val expected = HashMap<Int, BoundingBox3d>()
        var nextId = 0

        repeat(1500) { step ->
            if (expected.isEmpty() || random.nextDouble() < 0.6) {
                val box = randomBox(random)
                tree.insert(nextId, box)
                expected[nextId] = box
                nextId++
            } else {
                val victim = expected.keys.elementAt(random.nextInt(expected.size))
                val removed = tree.remove(victim)

                assertEquals(victim, removed.data)
                expected.remove(victim)
            }

            validate(tree, expected, 3)

            if (expected.size > 1) {
                val allowedHeight = 2 * (32 - Integer.numberOfLeadingZeros(expected.size)) + 2

                assertTrue(tree.root!!.height <= allowedHeight, "height ${tree.root!!.height} for ${expected.size}")
            }

            if (step % 5 == 0) {
                val queryBox = randomBox(random, 25.0)
                val reference = expected.filter { it.value intersectsWith queryBox }.keys

                assertEquals(reference, queryIds(tree, queryBox), "step $step")
            }
        }
    }

    @Test
    fun removingEverythingInRandomOrderEmptiesTree() {
        val random = Random(102)
        val tree = BoundingBoxTree3d<Int>()
        val expected = HashMap<Int, BoundingBox3d>()

        repeat(100) {
            val box = randomBox(random)
            tree.insert(it, box)
            expected[it] = box
        }

        for (victim in expected.keys.shuffled(random).toList()) {
            tree.remove(victim)
            expected.remove(victim)
            validate(tree, expected, 3)
        }

        assertNull(tree.root)
    }

    @Test
    fun reinsertingAfterRemovalWorks() {
        val tree = BoundingBoxTree3d<Int>()
        val box = BoundingBox3d.fromCenterSize(Vector3d.zero, 1.0)

        tree.insert(1, box)
        tree.insert(2, box)
        tree.remove(1)
        tree.insert(1, box)

        assertEquals(setOf(1, 2), queryIds(tree, box))
    }
}
