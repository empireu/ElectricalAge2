import org.ageseries.libage.data.ImmutableBoolArrayView
import org.ageseries.libage.data.ImmutableDoubleArrayView
import org.ageseries.libage.data.ImmutableIntArrayView
import org.ageseries.libage.data.ImmutableLongArrayView
import org.ageseries.libage.data.OptionalDouble
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WrappersTests {
    @Test
    fun emptyOptionalIsNotPresentAndCannotBeUnwrapped() {
        assertFalse(OptionalDouble.EMPTY.isPresent)
        assertThrows<IllegalStateException> { OptionalDouble.EMPTY.unwrap() }
    }

    @Test
    fun wrappedValueRoundTrips() {
        listOf(0.0, -0.0, 1.5, -3.25, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach {
                val optional = OptionalDouble.wrap(it)

                assertTrue(optional.isPresent)
                assertEquals(it, optional.unwrap())
            }
    }

    @Test
    fun wrappingNanIsRejected() {
        assertThrows<IllegalArgumentException> { OptionalDouble.wrap(Double.NaN) }
    }

    @Test
    fun arrayViewReflectsContentSizeAndIndexing() {
        val array = intArrayOf(5, 6, 7)
        val view = ImmutableIntArrayView(array)

        assertEquals(3, view.size)
        assertEquals(listOf(5, 6, 7), view.toList())
        assertEquals(6, view[1])
        assertEquals(1, view.indexOf(6))
        assertThrows<IndexOutOfBoundsException> { view[3] }
    }

    @Test
    fun arrayViewObservesChangesToBackingArray() {
        val array = longArrayOf(1L, 2L)
        val view = ImmutableLongArrayView(array)

        array[0] = 99L

        assertEquals(99L, view[0])
    }

    @Test
    fun emptyAndOtherArrayViewsBehave() {
        assertTrue(ImmutableDoubleArrayView(DoubleArray(0)).isEmpty())
        assertEquals(listOf(true, false), ImmutableBoolArrayView(booleanArrayOf(true, false)).toList())
        assertEquals(ImmutableIntArrayView(intArrayOf(1, 2)), listOf(1, 2))
    }
}
