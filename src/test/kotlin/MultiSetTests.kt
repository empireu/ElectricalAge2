import org.ageseries.libage.data.MutableMapMultiSet
import org.ageseries.libage.data.emptyMultiSet
import org.ageseries.libage.data.multiSetOf
import org.ageseries.libage.data.mutableMultiSetOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MultiSetTests {
    @Test
    fun absentElementHasZeroMultiplicity() {
        val multiSet = mutableMultiSetOf<String>()

        assertEquals(0, multiSet["x"])
        assertFalse(multiSet.contains("x"))
        assertEquals(0, multiSet.size)
    }

    @Test
    fun addAccumulatesMultiplicityAndReturnsNewValue() {
        val multiSet = mutableMultiSetOf<String>()

        assertEquals(1, multiSet.add("x"))
        assertEquals(4, multiSet.add("x", 3))
        assertEquals(4, multiSet["x"])
        assertTrue(multiSet.contains("x"))
        assertEquals(1, multiSet.size)
    }

    @Test
    fun takeToZeroRemovesElement() {
        val multiSet = mutableMultiSetOf("x" to 2)

        assertEquals(1, multiSet.take("x"))
        assertTrue(multiSet.contains("x"))
        assertEquals(0, multiSet.take("x"))
        assertFalse(multiSet.contains("x"))
        assertEquals(0, multiSet.size)
        assertFalse(multiSet.keys.contains("x"))
    }

    @Test
    fun takingMoreThanPresentThrowsAndKeepsState() {
        val multiSet = mutableMultiSetOf("x" to 2)

        assertThrows<IllegalArgumentException> { multiSet.take("x", 3) }
        assertThrows<IllegalArgumentException> { multiSet.take("missing") }

        assertEquals(2, multiSet["x"])
        assertEquals(0, multiSet["missing"])
    }

    @Test
    fun settingNegativeMultiplicityThrows() {
        val multiSet = mutableMultiSetOf<String>()

        assertThrows<IllegalArgumentException> { multiSet["x"] = -1 }
        assertThrows<IllegalArgumentException> { multiSet.add("x", -1) }
        assertEquals(0, multiSet.size)
    }

    @Test
    fun settingZeroRemovesElement() {
        val multiSet = mutableMultiSetOf("x" to 5)

        multiSet["x"] = 0

        assertFalse(multiSet.contains("x"))
        assertEquals(0, multiSet.size)
    }

    @Test
    fun plusAssignAndMinusAssignChangeByOne() {
        val multiSet = mutableMultiSetOf<String>()

        multiSet += "x"
        multiSet += "x"
        multiSet -= "x"

        assertEquals(1, multiSet["x"])
    }

    @Test
    fun removeReturnsPreviousMultiplicityOrZero() {
        val multiSet = mutableMultiSetOf("x" to 3)

        assertEquals(3, multiSet.remove("x"))
        assertEquals(0, multiSet.remove("x"))
        assertFalse(multiSet.contains("x"))
    }

    @Test
    fun putReturnsPreviousMultiplicityAndValidates() {
        val multiSet = MutableMapMultiSet<String>()

        assertEquals(0, multiSet.put("x", 2))
        assertEquals(2, multiSet.put("x", 5))
        assertEquals(5, multiSet["x"])
        assertThrows<IllegalArgumentException> { multiSet.put("y", -2) }
    }

    @Test
    fun putAllRejectsNegativeMultiplicityWithoutPartialInsert() {
        val multiSet = MutableMapMultiSet<String>()

        assertThrows<IllegalArgumentException> { multiSet.putAll(mapOf("a" to 1, "b" to -1)) }

        assertEquals(0, multiSet.size)
    }

    @Test
    fun constructionFromPairsSkipsZeroMultiplicity() {
        val multiSet = multiSetOf("a" to 2, "b" to 0, "c" to 1)

        assertEquals(2, multiSet.size)
        assertEquals(2, multiSet["a"])
        assertFalse(multiSet.contains("b"))

        val fromIterable = MutableMapMultiSet(listOf("a" to 3, "z" to 0))

        assertEquals(3, fromIterable["a"])
        assertEquals(1, fromIterable.size)
    }

    @Test
    fun constructionFromPairsRejectsNegativeMultiplicity() {
        assertThrows<IllegalArgumentException> { multiSetOf("a" to -1) }
        assertThrows<IllegalArgumentException> { MutableMapMultiSet(listOf("a" to -1).iterator()) }
    }

    @Test
    fun clearEmptiesTheSet() {
        val multiSet = mutableMultiSetOf("a" to 1, "b" to 2)

        multiSet.clear()

        assertEquals(0, multiSet.size)
        assertEquals(0, multiSet["b"])
    }

    @Test
    fun iterationYieldsDistinctElementsOnly() {
        val multiSet = mutableMultiSetOf("a" to 3, "b" to 2)

        assertEquals(setOf("a", "b"), multiSet.iterator().asSequence().toSet())
        assertEquals(2, multiSet.iterator().asSequence().count())
    }

    @Test
    fun emptyMultiSetIsEmpty() {
        val multiSet = emptyMultiSet<Int>()

        assertEquals(0, multiSet.size)
        assertEquals(0, multiSet[1])
    }

    @Test
    fun equalityFollowsBackingMap() {
        val first = mutableMultiSetOf("a" to 1, "b" to 2)
        val second = mutableMultiSetOf("b" to 2, "a" to 1)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())

        second.add("a")

        assertTrue(first != second)
    }
}
