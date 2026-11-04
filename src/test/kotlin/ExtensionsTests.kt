import org.ageseries.libage.utils.addUnique
import org.ageseries.libage.utils.putUnique
import org.ageseries.libage.utils.removeElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExtensionsTests {
    @Test
    fun putUniqueInsertsNewKey() {
        val map = HashMap<Int, String>()

        map.putUnique(1, "one")

        assertEquals("one", map[1])
    }

    @Test
    fun putUniqueThrowsOnExistingKeyWithDefaultMessage() {
        val map = hashMapOf(1 to "one")

        val exception = assertThrows<IllegalArgumentException> { map.putUnique(1, "uno") }

        assertTrue(exception.message!!.contains("1"))
        assertTrue(exception.message!!.contains("uno"))
    }

    @Test
    fun putUniqueUsesCustomMessage() {
        val map = hashMapOf(1 to "one")

        val exception = assertThrows<IllegalArgumentException> { map.putUnique(1, "uno") { "custom failure" } }

        assertEquals("custom failure", exception.message)
    }

    @Test
    fun addUniqueInsertsNewElement() {
        val set = HashSet<Int>()

        set.addUnique(5)

        assertTrue(set.contains(5))
    }

    @Test
    fun addUniqueThrowsOnDuplicateWithCustomMessage() {
        val set = hashSetOf(5)

        val exception = assertThrows<IllegalArgumentException> { set.addUnique(5) { "duplicate five" } }

        assertEquals("duplicate five", exception.message)
        assertThrows<IllegalArgumentException> { set.addUnique(5) }
    }

    @Test
    fun removeElementTakesFirstOfOrderedSet() {
        val set = linkedSetOf(3, 1, 2)

        assertEquals(3, set.removeElement())
        assertEquals(listOf(1, 2), set.toList())
    }

    @Test
    fun removeElementThrowsOnEmptySet() {
        assertThrows<NoSuchElementException> { linkedSetOf<Int>().removeElement() }
    }
}
