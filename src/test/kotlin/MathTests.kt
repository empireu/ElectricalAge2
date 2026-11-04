import org.ageseries.libage.mathematics.Dual
import org.ageseries.libage.mathematics.acos
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.approxEqual
import org.ageseries.libage.mathematics.asin
import org.ageseries.libage.mathematics.atan
import org.ageseries.libage.mathematics.atan2
import org.ageseries.libage.mathematics.avg
import org.ageseries.libage.mathematics.cos
import org.ageseries.libage.mathematics.cosh
import org.ageseries.libage.mathematics.div
import org.ageseries.libage.mathematics.exp
import org.ageseries.libage.mathematics.frac
import org.ageseries.libage.mathematics.integralScan
import org.ageseries.libage.mathematics.ln
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.mathematics.mappedTo
import org.ageseries.libage.mathematics.minus
import org.ageseries.libage.mathematics.nz
import org.ageseries.libage.mathematics.plus
import org.ageseries.libage.mathematics.pow
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.mathematics.sin
import org.ageseries.libage.mathematics.sinh
import org.ageseries.libage.mathematics.snz
import org.ageseries.libage.mathematics.snzi
import org.ageseries.libage.mathematics.sqrt
import org.ageseries.libage.mathematics.tan
import org.ageseries.libage.mathematics.times
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI
import kotlin.math.pow
import kotlin.random.Random

class MathTests {
    @Test
    fun integerPowerMatchesRepeatedMultiplication() {
        for (base in -5..5) {
            var expected = 1

            for (exponent in 0..9) {
                if (!(base == 0 && exponent == 0)) {
                    assertEquals(expected, base.pow(exponent), "$base ^ $exponent")
                }

                expected *= base
            }
        }
    }

    @Test
    fun integerPowerRejectsNegativeExponent() {
        assertThrows<IllegalArgumentException> { 2.pow(-1) }
    }

    @Test
    fun integerPowerOfZeroToZeroIsOne() {
        assertEquals(1, 0.pow(0))
    }

    @Test
    fun fractionalPartIsAlwaysInUnitInterval() {
        assertTrue(frac(1.25).approxEq(0.25))
        assertTrue(frac(-1.25).approxEq(0.75))
        assertTrue(frac(3.0).approxEq(0.0))
        assertEquals(0.75f, frac(-1.25f), 1e-6f)
    }

    @Test
    fun mapRemapsRangesLinearly() {
        assertEquals(5.0, map(0.5, 0.0, 1.0, 0.0, 10.0), 1e-12)
        assertEquals(10.0, map(0.0, -1.0, 1.0, 5.0, 15.0), 1e-12)
        assertEquals(5.0, map(1.0, -1.0, 1.0, 15.0, 5.0), 1e-12)
        assertEquals(-2.0, map(2.0, 0.0, 1.0, 0.0, -1.0), 1e-12)
        assertEquals(5, map(5, 0, 10, 0, 10))
        assertEquals(3.0, 1.5.mappedTo(0.0, 1.0, 0.0, 2.0), 1e-12)
        assertEquals(0.5f, map(0.25f, 0f, 1f, 0f, 2f), 1e-6f)
    }

    @Test
    fun mapOfSourceEndpointsGivesDestinationEndpoints() {
        val random = Random(5)

        repeat(200) {
            val srcMin = random.nextDouble(-100.0, 0.0)
            val srcMax = random.nextDouble(1.0, 100.0)
            val dstMin = random.nextDouble(-100.0, 100.0)
            val dstMax = random.nextDouble(-100.0, 100.0)

            assertEquals(dstMin, map(srcMin, srcMin, srcMax, dstMin, dstMax), 1e-9)
            assertEquals(dstMax, map(srcMax, srcMin, srcMax, dstMin, dstMax), 1e-9)
        }
    }

    @Test
    fun averagesOfValues() {
        assertEquals(1.5, avg(1.0, 2.0), 1e-12)
        assertEquals(2.0, avg(1.0, 2.0, 3.0), 1e-12)
        assertEquals(2.5, avg(1.0, 2.0, 3.0, 4.0), 1e-12)
        assertEquals(2.0, avg(listOf(1.0, 2.0, 3.0)), 1e-12)
        assertTrue(avg(emptyList()).isNaN())
    }

    @Test
    fun approximateEqualityUsesStrictEpsilon() {
        assertTrue(approxEqual(1.0, 1.0 + 1e-9))
        assertFalse(approxEqual(1.0, 1.0 + 1e-6))
        assertFalse(approxEqual(1.0, 1.1, 0.1))
        assertTrue(1.0 approxEq 1.00000001)
        assertTrue(1.0f approxEq 1.000001f)
        assertFalse(1.0f approxEq 1.001f)
        assertFalse(Double.NaN approxEq Double.NaN)
        assertFalse(Double.POSITIVE_INFINITY approxEq Double.POSITIVE_INFINITY)
    }

    @Test
    fun roundedKeepsRequestedDecimals() {
        assertEquals(1.235, 1.23456.rounded(3), 1e-12)
        assertEquals(1.23, 1.23456.rounded(2), 1e-12)
        assertEquals(1.0, 1.23456.rounded(0), 1e-12)
        assertEquals(-1.23, (-1.23456).rounded(2), 1e-12)
        assertEquals(1.235, 1.23456.rounded(), 1e-12)
        assertTrue(Double.NaN.rounded().isNaN())
        assertEquals(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY.rounded(2))
    }

    @Test
    fun signFunctionsTreatZeroAsPositive() {
        assertEquals(1.0, snz(0.0))
        assertEquals(1.0, snz(5.0))
        assertEquals(-1.0, snz(-5.0))
        assertEquals(1, snzi(0))
        assertEquals(-1, snzi(-3))
    }

    @Test
    fun nonZeroNudgeNeverReturnsZero() {
        assertTrue(0.0.nz() > 0.0)
        assertTrue((-0.5).nz() < -0.5)
        assertTrue(0.0f.nz() > 0.0f)
        assertTrue((-0.0).nz() != 0.0)
    }

    @Test
    fun integralScanIntegratesKnownFunctions() {
        assertEquals(2.0, integralScan(0.0, PI) { kotlin.math.sin(it) }, 1e-8)
        assertEquals(9.0, integralScan(0.0, 3.0) { it * it }, 1e-8)
        assertEquals(Math.E - 1.0, integralScan(0.0, 1.0) { kotlin.math.exp(it) }, 1e-8)
        assertEquals(0.0, integralScan(-1.0, 1.0) { it * it * it }, 1e-8)
        assertEquals(-9.0, integralScan(3.0, 0.0) { it * it }, 1e-8)
    }

    @Test
    fun integralScanOfConstantAndZeroWidthInterval() {
        assertEquals(10.0, integralScan(0.0, 5.0) { 2.0 }, 1e-9)
        assertEquals(0.0, integralScan(1.0, 1.0) { it }, 1e-9)
    }

    @Test
    fun dualVariableHoldsValueAndUnitDerivative() {
        val dual = Dual.variable(3.0, 3)

        assertEquals(3, dual.size)
        assertEquals(3.0, dual.value)
        assertEquals(listOf(3.0, 1.0, 0.0), dual.toList())
        assertFalse(dual.isReal)
        assertTrue(Dual.const(2.0).isReal)
        assertEquals(listOf(2.0, 0.0), Dual.const(2.0, 2).toList())
    }

    @Test
    fun dualConstructorsConcatenateAndCopy() {
        val source = doubleArrayOf(1.0, 2.0)
        val dual = Dual.create(source)

        source[0] = 99.0

        assertEquals(1.0, dual.value)
        assertEquals(listOf(5.0, 1.0, 2.0), Dual(5.0, dual).toList())
        assertEquals(listOf(1.0, 2.0, 5.0), Dual(dual, 5.0).toList())
        assertEquals(listOf(1.0, 2.0, 1.0, 2.0), Dual(dual, dual).toList())
        assertEquals(listOf(1.0, 2.0, 3.0), Dual(listOf(1.0), listOf(2.0, 3.0)).toList())
        assertEquals(listOf(1.0, 2.0, 3.0), Dual(doubleArrayOf(1.0), doubleArrayOf(2.0, 3.0)).toList())
        assertEquals(listOf(1.0, 2.0), Dual.of(1.0, 2.0).toList())
        assertEquals(0, Dual.empty.size)
    }

    @Test
    fun dualBindReturnsDefensiveCopy() {
        val dual = Dual.of(1.0, 2.0)

        dual.bind()[0] = 100.0

        assertEquals(1.0, dual.value)
    }

    @Test
    fun dualHeadAndTailSliceValues() {
        val dual = Dual.of(1.0, 2.0, 3.0, 4.0)

        assertEquals(listOf(1.0, 2.0, 3.0), dual.head().toList())
        assertEquals(listOf(1.0, 2.0), dual.head(2).toList())
        assertEquals(listOf(2.0, 3.0, 4.0), dual.tail().toList())
        assertEquals(listOf(3.0, 4.0), dual.tail(2).toList())
    }

    @Test
    fun dualArithmeticTruncatesToShorterOperand() {
        val longer = Dual.of(1.0, 2.0, 3.0)
        val shorter = Dual.of(10.0, 20.0)

        assertEquals(listOf(11.0, 22.0), (longer + shorter).toList())
        assertEquals(listOf(9.0, 18.0), (shorter - longer).toList())
    }

    @Test
    fun dualProductAppliesLeibnizRule() {
        val first = Dual.variable(3.0, 3)
        val product = first * first

        assertEquals(listOf(9.0, 6.0, 2.0), product.toList())
    }

    @Test
    fun dualQuotientAppliesQuotientRule() {
        val x = Dual.variable(2.0, 3)
        val quotient = 1.0 / x

        assertEquals(0.5, quotient.value, 1e-12)
        assertEquals(-0.25, quotient[1], 1e-12)
        assertEquals(0.25, quotient[2], 1e-12)
    }

    @Test
    fun dualScalarOperationsAffectOnlyExpectedComponents() {
        val dual = Dual.of(2.0, 3.0, 4.0)

        assertEquals(listOf(7.0, 3.0, 4.0), (dual + 5.0).toList())
        assertEquals(listOf(-3.0, 3.0, 4.0), (dual - 5.0).toList())
        assertEquals(listOf(10.0, 15.0, 20.0), (dual * 5.0).toList())
        assertEquals(listOf(1.0, 1.5, 2.0), (dual / 2.0).toList())
        assertSame(dual, dual * 1.0)
        assertEquals(listOf(7.0, 3.0, 4.0), (5.0 + dual).toList())
        assertEquals(listOf(3.0, -3.0, -4.0), (5.0 - dual).toList())
        assertEquals(listOf(-2.0, -3.0, -4.0), (-dual).toList())
        assertEquals(listOf(10.0, 15.0, 20.0), (5.0 * dual).toList())
    }

    @Test
    fun dualSecondDerivativesOfElementaryFunctions() {
        val point = 0.7
        val x = Dual.variable(point, 3)

        val sinResult = sin(x)
        assertEquals(kotlin.math.sin(point), sinResult[0], 1e-12)
        assertEquals(kotlin.math.cos(point), sinResult[1], 1e-12)
        assertEquals(-kotlin.math.sin(point), sinResult[2], 1e-12)

        val cosResult = cos(x)
        assertEquals(kotlin.math.cos(point), cosResult[0], 1e-12)
        assertEquals(-kotlin.math.sin(point), cosResult[1], 1e-12)
        assertEquals(-kotlin.math.cos(point), cosResult[2], 1e-12)

        val expResult = exp(x)
        assertEquals(kotlin.math.exp(point), expResult[0], 1e-12)
        assertEquals(kotlin.math.exp(point), expResult[1], 1e-12)
        assertEquals(kotlin.math.exp(point), expResult[2], 1e-12)

        val lnResult = ln(x)
        assertEquals(kotlin.math.ln(point), lnResult[0], 1e-12)
        assertEquals(1.0 / point, lnResult[1], 1e-12)
        assertEquals(-1.0 / (point * point), lnResult[2], 1e-12)

        val sqrtResult = sqrt(x)
        assertEquals(kotlin.math.sqrt(point), sqrtResult[0], 1e-12)
        assertEquals(0.5 / kotlin.math.sqrt(point), sqrtResult[1], 1e-12)
        assertEquals(-0.25 * point.pow(-1.5), sqrtResult[2], 1e-12)

        val powerResult = x.pow(3)
        assertEquals(point * point * point, powerResult[0], 1e-12)
        assertEquals(3.0 * point * point, powerResult[1], 1e-12)
        assertEquals(6.0 * point, powerResult[2], 1e-12)

        val sinhResult = sinh(x)
        assertEquals(kotlin.math.cosh(point), sinhResult[1], 1e-12)
        assertEquals(kotlin.math.sinh(point), sinhResult[2], 1e-12)

        val coshResult = cosh(x)
        assertEquals(kotlin.math.sinh(point), coshResult[1], 1e-12)
        assertEquals(kotlin.math.cosh(point), coshResult[2], 1e-12)
    }

    @Test
    fun dualFirstDerivativesMatchFiniteDifferences() {
        val point = 0.3
        val step = 1e-6
        val x = Dual.variable(point, 2)

        val functions = listOf<Pair<(Dual) -> Dual, (Double) -> Double>>(
            { dual: Dual -> tan(dual) } to { value: Double -> kotlin.math.tan(value) },
            { dual: Dual -> asin(dual) } to { value: Double -> kotlin.math.asin(value) },
            { dual: Dual -> acos(dual) } to { value: Double -> kotlin.math.acos(value) },
            { dual: Dual -> atan(dual) } to { value: Double -> kotlin.math.atan(value) },
            { dual: Dual -> exp(dual, 2.0) } to { value: Double -> 2.0.pow(value) },
            { dual: Dual -> 1.0 / dual } to { value: Double -> 1.0 / value },
            { dual: Dual -> dual.pow(2.5) } to { value: Double -> value.pow(2.5) },
        )

        functions.forEach { (dualFunction, plainFunction) ->
            val numeric = (plainFunction(point + step) - plainFunction(point - step)) / (2.0 * step)
            val result = dualFunction(x)

            assertEquals(plainFunction(point), result[0], 1e-12)
            assertEquals(numeric, result[1], 1e-6)
        }
    }

    @Test
    fun dualChainRuleComposesFunctions() {
        val point = 0.4
        val x = Dual.variable(point, 2)
        val result = sin(x * x)

        assertEquals(kotlin.math.sin(point * point), result[0], 1e-12)
        assertEquals(kotlin.math.cos(point * point) * 2.0 * point, result[1], 1e-12)
    }

    @Test
    fun dualAtan2ValueAndDerivative() {
        val y = Dual.variable(1.0, 2)
        val x = Dual.const(2.0, 2)
        val result = atan2(y, x)

        assertEquals(kotlin.math.atan2(1.0, 2.0), result[0], 1e-12)
        assertEquals(2.0 / 5.0, result[1], 1e-12)
        assertThrows<IllegalArgumentException> { atan2(Dual.of(1.0), Dual.of(1.0, 0.0)) }
        assertEquals(0, atan2(Dual.empty, Dual.empty).size)
    }

    @Test
    fun dualApproxEqRespectsSizeAndTolerance() {
        assertTrue(Dual.of(1.0, 2.0).approxEq(Dual.of(1.0 + 1e-9, 2.0)))
        assertFalse(Dual.of(1.0, 2.0).approxEq(Dual.of(1.0)))
        assertFalse(Dual.of(1.0, 2.0).approxEq(Dual.of(1.0, 2.1)))
        assertEquals(Dual.of(1.0, 2.0), Dual.of(1.0, 2.0))
        assertEquals(Dual.of(1.0, 2.0).hashCode(), Dual.of(1.0, 2.0).hashCode())
    }

    @Test
    fun dualIntersectTrimsToSmallerSize() {
        val (first, second) = Dual.intersect(Dual.of(1.0, 2.0, 3.0), Dual.of(4.0, 5.0))

        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertEquals(listOf(1.0, 2.0), first.toList())
    }

    @Test
    fun dualMapValueOnlyChangesFirstComponent() {
        val dual = Dual.of(1.0, 2.0, 3.0)

        assertEquals(listOf(10.0, 2.0, 3.0), dual.mapValue { it * 10.0 }.toList())
        assertEquals(listOf(10.0, 20.0, 30.0), dual.map { it * 10.0 }.toList())
        assertEquals(listOf(0.0, 3.0, 4.0), dual.mapValueAndTail({ it - 1.0 }, { it + 1.0 }).toList())
        assertEquals(0, Dual.empty.mapValue { it + 1.0 }.size)
    }
}
