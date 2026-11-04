import org.ageseries.libage.mathematics.ArrayKDGrid
import org.ageseries.libage.mathematics.ArrayKDGridD
import org.ageseries.libage.mathematics.ArrayKDVectorD
import org.ageseries.libage.mathematics.ArrayKDVectorI
import org.ageseries.libage.mathematics.KDVectorIImmutable
import org.ageseries.libage.mathematics.arrayKDGridDOf
import org.ageseries.libage.mathematics.arrayKDGridOf
import org.ageseries.libage.mathematics.arrayKDVectorIOf
import org.ageseries.libage.mathematics.get
import org.ageseries.libage.mathematics.kdVectorDOf
import org.ageseries.libage.mathematics.kdVectorIOf
import org.ageseries.libage.mathematics.set
import org.ageseries.libage.mathematics.toKDVectorD
import org.ageseries.libage.mathematics.traverse
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KDVectorTests {
    @Test
    fun intVectorArithmeticProducesNewVectors() {
        val first = arrayKDVectorIOf(1, 2, 3)
        val second = arrayKDVectorIOf(10, 20, 30)

        assertArrayEquals(intArrayOf(11, 22, 33), (first + second).toArray())
        assertArrayEquals(intArrayOf(9, 18, 27), (second - first).toArray())
        assertArrayEquals(intArrayOf(1, 2, 3), first.toArray())
    }

    @Test
    fun intVectorBindAndToArrayAreIndependentCopies() {
        val vector = arrayKDVectorIOf(1, 2, 3)
        val bound = vector.bind()
        val array = vector.toArray()

        bound[0] = 99
        array[1] = 99

        assertEquals(1, vector[0])
        assertEquals(2, vector[1])
        assertEquals(3, vector.size)
    }

    @Test
    fun immutableIntVectorBehavesLikeValue() {
        val vector = kdVectorIOf(4, 5, 6)

        assertEquals(3, vector.size)
        assertEquals(5, vector[1])
        assertArrayEquals(intArrayOf(5, 7, 9), (vector + kdVectorIOf(1, 2, 3)).toArray())
        assertArrayEquals(intArrayOf(3, 3, 3), (vector - kdVectorIOf(1, 2, 3)).toArray())
        assertTrue(KDVectorIImmutable(emptyList()).isEmpty)
        assertEquals(listOf(4, 5, 6), vector.iterator().asSequence().toList())

        val array = vector.toArray()
        array[0] = 0

        assertEquals(4, vector[0])
    }

    @Test
    fun outOfRangeVectorAccessThrows() {
        assertThrows<IndexOutOfBoundsException> { arrayKDVectorIOf(1)[1] }
        assertThrows<IndexOutOfBoundsException> { kdVectorIOf(1)[-1] }
        assertThrows<IndexOutOfBoundsException> { kdVectorDOf(1.0)[2] }
    }

    @Test
    fun doubleVectorFlooredAndFractionHandleNegativeValues() {
        val vector = kdVectorDOf(1.75, -0.25, -3.0, 2.0)

        assertArrayEquals(intArrayOf(1, -1, -3, 2), vector.floored().toArray())

        val fraction = vector.fraction()

        assertEquals(0.75, fraction[0], 1e-12)
        assertEquals(0.75, fraction[1], 1e-12)
        assertEquals(0.0, fraction[2], 1e-12)
        assertEquals(0.0, fraction[3], 1e-12)
    }

    @Test
    fun doubleVectorArithmeticAndAssignment() {
        val first = kdVectorDOf(1.0, 2.0)
        val second = kdVectorDOf(0.5, 0.25)

        assertEquals(1.5, (first + second)[0], 1e-12)
        assertEquals(1.75, (first - second)[1], 1e-12)

        first += second

        assertEquals(1.5, first[0], 1e-12)

        first -= second

        assertEquals(1.0, first[0], 1e-12)
        assertNotSame(first, first.bind())
        assertEquals(first[1], first.bind()[1], 0.0)
        assertEquals(2.0, kdVectorDOf(listOf(7.0, 2.0))[1], 0.0)
        assertEquals(7.0, arrayKDVectorIOf(listOf(7, 2)).toArray().first().toDouble(), 0.0)
    }

    @Test
    fun toKDVectorDCopiesValues() {
        val source = kdVectorDOf(1.0, 2.0, 3.0)
        val copy = source.toKDVectorD()

        copy[0] = 5.0

        assertEquals(1.0, source[0], 0.0)
        assertEquals(3, copy.size)
    }

    @Test
    fun intVectorClampActuallyClamps() {
        val vector = arrayKDVectorIOf(5, -3, 1)

        vector.clamp(0, 2)

        assertArrayEquals(intArrayOf(2, 0, 1), vector.toArray())
        assertArrayEquals(intArrayOf(2, 0, 1), arrayKDVectorIOf(5, -3, 1).clamped(0, 2).toArray())
    }

    @Test
    fun doubleVectorClampActuallyClamps() {
        val vector = ArrayKDVectorD(doubleArrayOf(5.0, -3.0, 1.0))

        vector.clamp(0.0, 2.0)

        assertArrayEquals(doubleArrayOf(2.0, 0.0, 1.0), vector.values, 1e-12)
        assertArrayEquals(
            doubleArrayOf(2.0, 0.0, 1.0),
            ArrayKDVectorD(doubleArrayOf(5.0, -3.0, 1.0)).clamped(0.0, 2.0).values,
            1e-12
        )
    }

    @Test
    fun gridValidationRejectsBadSizes() {
        assertThrows<IllegalStateException> { ArrayKDGridD(KDVectorIImmutable(emptyList()), DoubleArray(1)) }
        assertThrows<IllegalStateException> { ArrayKDGridD(KDVectorIImmutable(listOf(2, 0)), DoubleArray(4)) }
        assertThrows<IllegalStateException> { ArrayKDGridD(KDVectorIImmutable(listOf(2, -2)), DoubleArray(4)) }
        assertThrows<IllegalStateException> { ArrayKDGridD(KDVectorIImmutable(listOf(2, 3)), DoubleArray(5)) }
        assertEquals(6, ArrayKDGridD(KDVectorIImmutable(listOf(2, 3)), DoubleArray(6)).grid.size)
        assertEquals(2, ArrayKDGridD(KDVectorIImmutable(listOf(2, 3)), DoubleArray(10)).dimensions)
    }

    @Test
    fun gridStoresDimensionZeroContiguously() {
        val grid = arrayKDGridDOf(3, 4, 5)

        assertEquals(3, grid.getSize(0))
        assertEquals(4, grid.getSize(1))
        assertEquals(5, grid.getSize(2))
        assertEquals(60, grid.grid.size)

        grid[1, 0, 0] = 7.0
        grid[0, 1, 0] = 8.0
        grid[0, 0, 1] = 9.0

        assertEquals(7.0, grid.grid[1], 0.0)
        assertEquals(8.0, grid.grid[3], 0.0)
        assertEquals(9.0, grid.grid[12], 0.0)
        assertEquals(7.0, grid[1, 0, 0], 0.0)
    }

    @Test
    fun gridFactoriesFillWithDefaults() {
        val filled = arrayKDGridDOf(2.5, 2, 2)
        val zeros = arrayKDGridDOf(KDVectorIImmutable(listOf(3)))

        assertTrue(filled.grid.all { it == 2.5 })
        assertTrue(zeros.grid.all { it == 0.0 })
        assertEquals(3, zeros.grid.size)
    }

    @Test
    fun gridAccessWithWrongDimensionCountThrows() {
        val grid = arrayKDGridDOf(2, 2)

        assertThrows<IllegalStateException> { grid[0] }
        assertThrows<IllegalStateException> { grid[0, 0, 0] }
    }

    @Test
    fun gridAccessOutsideTheGridThrows() {
        val grid = arrayKDGridDOf(2, 2)

        assertThrows<Exception> { grid[2, 0] }
        assertThrows<Exception> { grid[-1, 1] }
    }

    @Test
    fun traverseVisitsEveryCellOnce() {
        val grid = arrayKDGridDOf(3, 2, 4)
        val visited = ArrayList<List<Int>>()

        grid.traverse { visited.add(it.toArray().toList()) }

        assertEquals(24, visited.size)
        assertEquals(24, visited.toSet().size)

        visited.forEach {
            assertTrue(it[0] in 0 until 3 && it[1] in 0 until 2 && it[2] in 0 until 4)
        }
    }

    @Test
    fun traverseOfOneDimensionalGridIsInOrder() {
        val grid = arrayKDGridDOf(4)
        val visited = ArrayList<Int>()

        grid.traverse { visited.add(it[0]) }

        assertEquals(listOf(0, 1, 2, 3), visited)
    }

    @Test
    fun genericGridCanBeCreatedWithDefaults() {
        val grid = arrayKDGridOf("x", 2, 3)

        assertEquals(2, grid.dimensions)
        assertEquals(6, grid.grid.size)
        assertTrue(grid.grid.all { it == "x" })
    }

    @Test
    fun genericGridGetAndSetWork() {
        val grid: ArrayKDGrid<String> = arrayKDGridOf("x", 2, 3)

        grid[1, 2] = "y"

        assertEquals("y", grid[1, 2])
        assertEquals("x", grid[0, 0])
    }
}
