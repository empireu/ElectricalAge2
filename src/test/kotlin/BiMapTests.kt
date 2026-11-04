import org.ageseries.libage.data.MutableBiMap
import org.ageseries.libage.data.associateWithBi
import org.ageseries.libage.data.biMapOf
import org.ageseries.libage.data.emptyBiMap
import org.ageseries.libage.data.mutableBiMapOf
import org.ageseries.libage.data.toBiMap
import org.ageseries.libage.data.toMutableBiMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class BiMapTests {
    private fun assertConsistent(biMap: MutableBiMap<Int, Int>) {
        assertEquals(biMap.forward.size, biMap.backward.size)
        assertEquals(biMap.size, biMap.forward.size)

        biMap.forward.forEach { (forwardKey, backwardKey) ->
            assertEquals(forwardKey, biMap.backward[backwardKey])
        }

        biMap.backward.forEach { (backwardKey, forwardKey) ->
            assertEquals(backwardKey, biMap.forward[forwardKey])
        }
    }

    @Test
    fun emptyBiMapHasNoEntries() {
        val biMap = emptyBiMap<Int, String>()

        assertEquals(0, biMap.size)
        assertTrue(biMap.forward.isEmpty())
        assertTrue(biMap.backward.isEmpty())
        assertFalse(biMap.entries.iterator().hasNext())
    }

    @Test
    fun addCreatesBothDirections() {
        val biMap = mutableBiMapOf<Int, String>()
        biMap.add(1, "one")

        assertEquals("one", biMap.forward[1])
        assertEquals(1, biMap.backward["one"])
        assertEquals(1, biMap.size)
    }

    @Test
    fun addThrowsWhenForwardKeyExists() {
        val biMap = mutableBiMapOf(1 to "one")

        assertThrows<IllegalStateException> { biMap.add(1, "uno") }

        assertEquals("one", biMap.forward[1])
        assertEquals(1, biMap.backward["one"])
        assertFalse(biMap.backward.containsKey("uno"))
    }

    @Test
    fun addThrowsWhenBackwardKeyExists() {
        val biMap = mutableBiMapOf(1 to "one")

        assertThrows<IllegalStateException> { biMap.add(2, "one") }

        assertEquals(1, biMap.size)
        assertEquals(1, biMap.backward["one"])
    }

    @Test
    fun addOrReplaceReturnsFalseForFreshPair() {
        val biMap = mutableBiMapOf<Int, String>()

        assertFalse(biMap.addOrReplace(1, "one"))
    }

    @Test
    fun addOrReplaceReplacingForwardKeyDropsOldBackwardEntry() {
        val biMap = mutableBiMapOf(1 to "one")

        assertTrue(biMap.addOrReplace(1, "uno"))

        assertEquals(1, biMap.size)
        assertEquals("uno", biMap.forward[1])
        assertFalse(biMap.backward.containsKey("one"))
        assertEquals(1, biMap.backward["uno"])
    }

    @Test
    fun addOrReplaceReplacingBackwardKeyDropsOldForwardEntry() {
        val biMap = mutableBiMapOf(1 to "one")

        assertTrue(biMap.addOrReplace(2, "one"))

        assertEquals(1, biMap.size)
        assertFalse(biMap.forward.containsKey(1))
        assertEquals("one", biMap.forward[2])
        assertEquals(2, biMap.backward["one"])
    }

    @Test
    fun addOrReplaceBridgingTwoExistingPairsLeavesOnePair() {
        val biMap = mutableBiMapOf(1 to "one", 2 to "two")

        assertTrue(biMap.addOrReplace(1, "two"))

        assertEquals(1, biMap.size)
        assertEquals(mapOf(1 to "two"), biMap.forward)
        assertEquals(mapOf("two" to 1), biMap.backward)
    }

    @Test
    fun addOrReplaceWithIdenticalPairKeepsMapping() {
        val biMap = mutableBiMapOf(1 to "one")

        assertTrue(biMap.addOrReplace(1, "one"))

        assertEquals(1, biMap.size)
        assertEquals("one", biMap.forward[1])
        assertEquals(1, biMap.backward["one"])
    }

    @Test
    fun removeForwardRemovesBothDirections() {
        val biMap = mutableBiMapOf(1 to "one", 2 to "two")

        assertTrue(biMap.removeForward(1))

        assertEquals(1, biMap.size)
        assertFalse(biMap.backward.containsKey("one"))
        assertEquals("two", biMap.forward[2])
    }

    @Test
    fun removeBackwardRemovesBothDirections() {
        val biMap = mutableBiMapOf(1 to "one", 2 to "two")

        assertTrue(biMap.removeBackward("two"))

        assertEquals(1, biMap.size)
        assertFalse(biMap.forward.containsKey(2))
        assertEquals(1, biMap.backward["one"])
    }

    @Test
    fun removingMissingKeysReturnsFalseAndChangesNothing() {
        val biMap = mutableBiMapOf(1 to "one")

        assertFalse(biMap.removeForward(5))
        assertFalse(biMap.removeBackward("five"))
        assertEquals(1, biMap.size)
    }

    @Test
    fun clearEmptiesBothDirections() {
        val biMap = mutableBiMapOf(1 to "one", 2 to "two", 3 to "three")

        biMap.clear()

        assertEquals(0, biMap.size)
        assertTrue(biMap.forward.isEmpty())
        assertTrue(biMap.backward.isEmpty())
    }

    @Test
    fun entriesReturnForwardBackwardPairs() {
        val biMap = biMapOf(1 to "one", 2 to "two")

        assertEquals(setOf(1 to "one", 2 to "two"), biMap.entries.toSet())
    }

    @Test
    fun reflexiveBijectionKeepsDirectionsSeparate() {
        val biMap = mutableBiMapOf(1 to 2, 2 to 3)

        assertEquals(2, biMap.forward[1])
        assertEquals(3, biMap.forward[2])
        assertEquals(1, biMap.backward[2])
        assertEquals(2, biMap.backward[3])
    }

    @Test
    fun constructionFromBijectiveCollectionsPreservesAllPairs() {
        val pairs = listOf(1 to "a", 2 to "b", 3 to "c")

        assertEquals(pairs.toMap(), pairs.toBiMap().forward)
        assertEquals(pairs.toMap(), pairs.iterator().toBiMap().forward)
        assertEquals(pairs.toMap(), pairs.toMutableBiMap().forward)
        assertEquals(pairs.toMap(), pairs.toMap().toBiMap().forward)
        assertEquals(pairs.toMap(), pairs.toMap().toMutableBiMap().forward)
        assertEquals(mapOf("a" to 1, "b" to 2, "c" to 3), pairs.toMap().toBiMap().backward)
    }

    @Test
    fun constructionFromNonBijectiveInputStaysConsistent() {
        val biMap = listOf(1 to "x", 2 to "x", 3 to "y").toMutableBiMap()

        assertEquals(biMap.forward.size, biMap.backward.size)

        biMap.forward.forEach { (forwardKey, backwardKey) ->
            assertEquals(forwardKey, biMap.backward[backwardKey])
        }
    }

    @Test
    fun associateWithBiBuildsInvertibleMap() {
        val biMap = listOf(1, 2, 3).associateWithBi { it * 10 }

        assertEquals(mapOf(1 to 10, 2 to 20, 3 to 30), biMap.forward)
        assertEquals(2, biMap.backward[20])
    }

    @Test
    fun associateWithBiThrowsOnDuplicateValues() {
        assertThrows<IllegalStateException> {
            listOf(1, 2, 3).associateWithBi { it % 2 }
        }
    }

    @Test
    fun randomizedOperationsKeepDirectionsConsistentAndMatchReference() {
        val random = Random(1234)
        val biMap = mutableBiMapOf<Int, Int>()
        val referencePairs = ArrayList<Pair<Int, Int>>()

        repeat(5000) {
            val forwardKey = random.nextInt(0, 30)
            val backwardKey = random.nextInt(0, 30)

            when (random.nextInt(0, 3)) {
                0 -> {
                    biMap.addOrReplace(forwardKey, backwardKey)
                    referencePairs.removeAll { it.first == forwardKey || it.second == backwardKey }
                    referencePairs.add(forwardKey to backwardKey)
                }

                1 -> {
                    val existed = biMap.removeForward(forwardKey)
                    assertEquals(referencePairs.any { it.first == forwardKey }, existed)
                    referencePairs.removeAll { it.first == forwardKey }
                }

                else -> {
                    val existed = biMap.removeBackward(backwardKey)
                    assertEquals(referencePairs.any { it.second == backwardKey }, existed)
                    referencePairs.removeAll { it.second == backwardKey }
                }
            }

            assertConsistent(biMap)
            assertEquals(referencePairs.toSet(), biMap.entries.toSet())
        }
    }
}
