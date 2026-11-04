import org.ageseries.libage.data.DisjointSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DisjointSetTests {
    @Test
    fun freshSetIsItsOwnRepresentative() {
        val set = DisjointSet()

        assertSame(set, set.representative)
        assertEquals(1, set.size)
    }

    @Test
    fun uniteGivesSameRepresentative() {
        val first = DisjointSet()
        val second = DisjointSet()

        first.unite(second)

        assertSame(first.representative, second.representative)
        assertEquals(2, first.representative.size)
    }

    @Test
    fun uniteWithSelfOrAlreadyUnitedSetsDoesNotChangeSize() {
        val first = DisjointSet()
        val second = DisjointSet()

        first.unite(second)
        first.unite(second)
        first.unite(first)
        second.unite(first)

        assertEquals(2, first.representative.size)
    }

    @Test
    fun separateSetsKeepSeparateRepresentatives() {
        val first = DisjointSet()
        val second = DisjointSet()

        assertNotSame(first.representative, second.representative)
    }

    @Test
    fun uniteBySizeKeepsLargerTreeRepresentative() {
        val large = DisjointSet()
        val largeMembers = List(4) { DisjointSet() }
        largeMembers.forEach { large.unite(it) }

        val single = DisjointSet()
        single.unite(large)

        assertSame(large.representative, single.representative)
        assertEquals(6, single.representative.size)

        val otherSingle = DisjointSet()
        val representativeBefore = large.representative
        otherSingle.unite(large)

        assertSame(representativeBefore, otherSingle.representative)
    }

    @Test
    fun higherPriorityOverridesSizeBasedMerge() {
        val large = DisjointSet()
        repeat(5) { large.unite(DisjointSet()) }

        val prioritized = DisjointSet(priority = 10)
        large.unite(prioritized)

        assertSame(prioritized, large.representative)
        assertSame(prioritized, prioritized.representative)
        assertEquals(7, prioritized.size)
    }

    @Test
    fun higherPriorityWinsRegardlessOfArgumentOrder() {
        val low = DisjointSet(priority = 1)
        val high = DisjointSet(priority = 2)

        high.unite(low)

        assertSame(high, low.representative)
    }

    @Test
    fun longChainRepresentativeLookupIsStable() {
        val sets = List(10000) { DisjointSet() }

        for (index in 1 until sets.size) {
            sets[index - 1].unite(sets[index])
        }

        val representative = sets[0].representative

        sets.forEach {
            assertSame(representative, it.representative)
        }

        assertEquals(sets.size, representative.size)
    }

    @Test
    fun randomizedUnionsMatchNaiveComponentLabelling() {
        val random = Random(42)
        val count = 200
        val sets = List(count) { DisjointSet() }
        val labels = IntArray(count) { it }

        repeat(300) {
            val first = random.nextInt(count)
            val second = random.nextInt(count)

            sets[first].unite(sets[second])

            val oldLabel = labels[second]
            val newLabel = labels[first]

            if (oldLabel != newLabel) {
                for (index in 0 until count) {
                    if (labels[index] == oldLabel) {
                        labels[index] = newLabel
                    }
                }
            }

            val a = random.nextInt(count)
            val b = random.nextInt(count)

            assertEquals(labels[a] == labels[b], sets[a].representative === sets[b].representative)
        }

        val sizeByLabel = labels.toList().groupingBy { it }.eachCount()

        for (index in 0 until count) {
            assertEquals(sizeByLabel[labels[index]], sets[index].representative.size)
        }
    }
}
