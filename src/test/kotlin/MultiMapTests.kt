import org.ageseries.libage.data.associateByMulti
import org.ageseries.libage.data.emptyMultiMap
import org.ageseries.libage.data.multiMapOf
import org.ageseries.libage.data.mutableMultiMapOf
import org.ageseries.libage.data.toMultiMap
import org.ageseries.libage.data.toMutableMultiMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class MultiMapTests {
    @Test
    fun emptyMultiMapHasNoKeysOrValues() {
        val multiMap = emptyMultiMap<Int, String>()

        assertEquals(0, multiMap.keyMappingSize)
        assertEquals(0, multiMap.entriesSize)
        assertEquals(0, multiMap.uniqueValuesSize)
        assertFalse(1 in multiMap)
        assertNull(multiMap.one(1))
    }

    @Test
    fun valuesAccumulateUnderOneKey() {
        val multiMap = multiMapOf(1 to "a", 1 to "b", 2 to "c")

        assertEquals(setOf("a", "b"), multiMap[1])
        assertEquals(setOf("c"), multiMap[2])
        assertEquals(2, multiMap.keyMappingSize)
        assertEquals(3, multiMap.entriesSize)
    }

    @Test
    fun duplicatePairsAreStoredOnce() {
        val multiMap = multiMapOf(1 to "a", 1 to "a")

        assertEquals(1, multiMap.entriesSize)
    }

    @Test
    fun entriesListEveryPair() {
        val multiMap = multiMapOf(1 to "a", 1 to "b", 2 to "c")

        val pairs = multiMap.entries.map { it.key to it.value }.toSet()

        assertEquals(setOf(1 to "a", 1 to "b", 2 to "c"), pairs)
    }

    @Test
    fun uniqueValuesMergesAcrossKeys() {
        val multiMap = multiMapOf(1 to "a", 2 to "a", 2 to "b")

        assertEquals(setOf("a", "b"), multiMap.uniqueValues)
        assertEquals(2, multiMap.uniqueValuesSize)
    }

    @Test
    fun containsIsFalseForMissingKeyWithoutCreatingIt() {
        val multiMap = mutableMultiMapOf(1 to "a")

        assertFalse(5 in multiMap)
        assertEquals(1, multiMap.keyMappingSize)
    }

    @Test
    fun oneReturnsMemberOfSet() {
        val multiMap = multiMapOf(1 to "a", 1 to "b")

        assertTrue(multiMap.one(1) in setOf("a", "b"))
        assertNull(multiMap.one(2))
    }

    @Test
    fun setAddsAssociation() {
        val multiMap = mutableMultiMapOf<Int, String>()

        assertTrue(multiMap.set(1, "a"))
        assertFalse(multiMap.set(1, "a"))
        assertTrue(1 in multiMap)
    }

    @Test
    fun removingLastValueRemovesKey() {
        val multiMap = mutableMultiMapOf(1 to "a", 1 to "b")

        multiMap.remove(1, "a")

        assertTrue(1 in multiMap)
        assertEquals(1, multiMap.keyMappingSize)

        multiMap.remove(1, "b")

        assertFalse(1 in multiMap)
        assertEquals(0, multiMap.keyMappingSize)
        assertFalse(1 in multiMap.keys)
    }

    @Test
    fun removingMissingAssociationChangesNothing() {
        val multiMap = mutableMultiMapOf(1 to "a")

        multiMap.remove(1, "zzz")
        multiMap.remove(9, "a")

        assertEquals(1, multiMap.entriesSize)
        assertEquals(1, multiMap.keyMappingSize)
    }

    @Test
    fun clearKeyReportsWhetherValuesWereRemoved() {
        val multiMap = mutableMultiMapOf(1 to "a", 1 to "b")

        assertTrue(multiMap.clear(1))
        assertFalse(multiMap.clear(1))
        assertFalse(multiMap.clear(7))
        assertEquals(0, multiMap.entriesSize)
    }

    @Test
    fun mutatingReturnedSetUpdatesMap() {
        val multiMap = mutableMultiMapOf<Int, String>()

        multiMap[3].add("x")

        assertEquals(setOf("x"), multiMap[3])
        assertEquals(1, multiMap.entriesSize)
    }

    @Test
    fun conversionsFromMapAndIterableKeepAllPairs() {
        val fromMap = mapOf(1 to "a", 2 to "b").toMultiMap()
        val fromList = listOf(1 to "a", 1 to "b").toMutableMultiMap()

        assertEquals(setOf("a"), fromMap[1])
        assertEquals(2, fromMap.keyMappingSize)
        assertEquals(setOf("a", "b"), fromList[1])
    }

    @Test
    fun associateByMultiGroupsValuesByKey() {
        val multiMap = listOf(1, 2, 3, 4, 5, 6).associateByMulti { it % 3 }

        assertEquals(setOf(3, 6), multiMap[0])
        assertEquals(setOf(1, 4), multiMap[1])
        assertEquals(setOf(2, 5), multiMap[2])

        val fromArray = arrayOf("aa", "b", "cc").associateByMulti { it.length }

        assertEquals(setOf("aa", "cc"), fromArray[2])
        assertEquals(setOf("b"), fromArray[1])
    }

    @Test
    fun randomizedOperationsMatchSetOfPairs() {
        val random = Random(99)
        val multiMap = mutableMultiMapOf<Int, Int>()
        val reference = HashSet<Pair<Int, Int>>()

        repeat(3000) {
            val key = random.nextInt(0, 10)
            val value = random.nextInt(0, 10)

            when (random.nextInt(0, 3)) {
                0 -> {
                    multiMap[key] = value
                    reference.add(key to value)
                }

                1 -> {
                    multiMap.remove(key, value)
                    reference.remove(key to value)
                }

                else -> {
                    val removedAny = multiMap.clear(key)
                    assertEquals(reference.any { it.first == key }, removedAny)
                    reference.removeAll { it.first == key }
                }
            }

            assertEquals(reference.size, multiMap.entriesSize)
            assertEquals(reference.map { it.first }.toSet().size, multiMap.keyMappingSize)
            assertEquals(reference.map { it.second }.toSet(), multiMap.uniqueValues)
        }
    }
}
