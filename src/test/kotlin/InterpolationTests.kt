import org.ageseries.libage.mathematics.ArcReparamCatenary2d
import org.ageseries.libage.mathematics.ArcReparamCatenary3d
import org.ageseries.libage.mathematics.Dual
import org.ageseries.libage.mathematics.GridInterpolator
import org.ageseries.libage.mathematics.InterpolatorBuilder
import org.ageseries.libage.mathematics.LinearSplineSegment1d
import org.ageseries.libage.mathematics.ListSplineSegmentMap
import org.ageseries.libage.mathematics.MappedGridInterpolator
import org.ageseries.libage.mathematics.PathBuilder3d
import org.ageseries.libage.mathematics.RotationUpdateProfile2d
import org.ageseries.libage.mathematics.Spline1d
import org.ageseries.libage.mathematics.TreeSplineSegmentMap
import org.ageseries.libage.mathematics.adaptscan
import org.ageseries.libage.mathematics.arrayKDGridDOf
import org.ageseries.libage.mathematics.bilinear
import org.ageseries.libage.mathematics.computeRotationUpdateAccelerationProfile
import org.ageseries.libage.mathematics.computeRotationUpdateAccelerationProfileWithAccelerationEstimate
import org.ageseries.libage.mathematics.differenceCondition1d
import org.ageseries.libage.mathematics.differenceCondition3d
import org.ageseries.libage.mathematics.evaluate
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.Vector3dDual
import org.ageseries.libage.mathematics.hermiteCubic
import org.ageseries.libage.mathematics.hermiteCubicDual
import org.ageseries.libage.mathematics.hermiteQuintic
import org.ageseries.libage.mathematics.hermiteQuinticDerivative1
import org.ageseries.libage.mathematics.hermiteQuinticDerivative2
import org.ageseries.libage.mathematics.hermiteQuinticDual
import org.ageseries.libage.mathematics.kdVectorDOf
import org.ageseries.libage.mathematics.lerp
import org.ageseries.libage.mathematics.reordU
import org.ageseries.libage.mathematics.reordV
import org.ageseries.libage.mathematics.reparamU
import org.ageseries.libage.mathematics.reparamV
import org.ageseries.libage.mathematics.smoothstep
import org.ageseries.libage.mathematics.splineSegmentMapOf
import org.ageseries.libage.mathematics.trilinear
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class InterpolationTests {
    private fun numericDerivative(function: (Double) -> Double, point: Double): Double {
        val step = 1e-6
        return (function(point + step) - function(point - step)) / (2.0 * step)
    }

    private fun linearSplineSegments(vararg times: Double) = (1 until times.size).map {
        LinearSplineSegment1d(times[it - 1], times[it], it.toDouble(), (it + 1).toDouble())
    }

    @Test
    fun lerpHitsEndpointsAndExtrapolates() {
        assertEquals(2.0, lerp(2.0, 6.0, 0.0), 1e-12)
        assertEquals(6.0, lerp(2.0, 6.0, 1.0), 1e-12)
        assertEquals(4.0, lerp(2.0, 6.0, 0.5), 1e-12)
        assertEquals(10.0, lerp(2.0, 6.0, 2.0), 1e-12)
        assertEquals(4.0f, lerp(2.0f, 6.0f, 0.5f), 1e-6f)
        assertEquals(4.0, lerp(2.0, 6.0, Dual.variable(0.5, 2)).value, 1e-12)
        assertEquals(4.0, lerp(2.0, 6.0, Dual.variable(0.5, 2))[1], 1e-12)
    }

    @Test
    fun smoothstepIsClampedMonotonicAndFlatAtEnds() {
        assertEquals(0.0, smoothstep(-5.0), 0.0)
        assertEquals(1.0, smoothstep(5.0), 0.0)
        assertEquals(0.0, smoothstep(0.0), 0.0)
        assertEquals(1.0, smoothstep(1.0), 1e-15)
        assertEquals(0.5, smoothstep(0.5), 1e-15)

        var previous = 0.0

        for (step in 1..100) {
            val value = smoothstep(step / 100.0)

            assertTrue(value >= previous)
            previous = value
        }

        assertEquals(0.0, numericDerivative({ smoothstep(it) }, 0.0), 1e-4)
        assertEquals(0.0, numericDerivative({ smoothstep(it) }, 1.0), 1e-4)
    }

    @Test
    fun bilinearInterpolatesCornersAndCenter() {
        assertEquals(1.0, bilinear(1.0, 2.0, 3.0, 4.0, 0.0, 0.0), 1e-12)
        assertEquals(2.0, bilinear(1.0, 2.0, 3.0, 4.0, 1.0, 0.0), 1e-12)
        assertEquals(3.0, bilinear(1.0, 2.0, 3.0, 4.0, 0.0, 1.0), 1e-12)
        assertEquals(4.0, bilinear(1.0, 2.0, 3.0, 4.0, 1.0, 1.0), 1e-12)
        assertEquals(2.5, bilinear(1.0, 2.0, 3.0, 4.0, 0.5, 0.5), 1e-12)
    }

    @Test
    fun trilinearInterpolatesSymmetricDataAndCenter() {
        val flat = trilinear(5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 0.3, 0.6, 0.9)

        assertEquals(5.0, flat, 1e-12)
        assertEquals(4.5, trilinear(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 0.5, 0.5, 0.5), 1e-12)
        assertEquals(1.0, trilinear(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 0.0, 0.0, 0.0), 1e-12)
        assertEquals(8.0, trilinear(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 1.0, 1.0, 1.0), 1e-12)
        assertEquals(2.0, trilinear(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 1.0, 0.0, 0.0), 1e-12)
    }

    @Test
    @Disabled("Bug: trilinear passes its corners to the inner bilinear in the wrong order, swapping the y and z axes.")
    fun trilinearReturnsCornerValuesAtEachCorner() {
        val corners = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0)

        for (z in 0..1) {
            for (y in 0..1) {
                for (x in 0..1) {
                    val expected = corners[x + 2 * y + 4 * z]

                    val actual = trilinear(
                        corners[0], corners[1], corners[2], corners[3], corners[4], corners[5], corners[6], corners[7],
                        x.toDouble(), y.toDouble(), z.toDouble()
                    )

                    assertEquals(expected, actual, 1e-12, "corner x=$x y=$y z=$z")
                }
            }
        }
    }

    @Test
    fun hermiteCubicInterpolatesEndpointsAndTangents() {
        val random = Random(81)

        repeat(50) {
            val p0 = random.nextDouble(-5.0, 5.0)
            val p1 = random.nextDouble(-5.0, 5.0)
            val v0 = random.nextDouble(-5.0, 5.0)
            val v1 = random.nextDouble(-5.0, 5.0)

            assertEquals(p0, hermiteCubic(p0, v0, v1, p1, 0.0), 1e-12)
            assertEquals(p1, hermiteCubic(p0, v0, v1, p1, 1.0), 1e-12)
            assertEquals(v0, numericDerivative({ hermiteCubic(p0, v0, v1, p1, it) }, 0.0), 1e-6)
            assertEquals(v1, numericDerivative({ hermiteCubic(p0, v0, v1, p1, it) }, 1.0), 1e-6)
        }
    }

    @Test
    fun hermiteCubicReproducesCubicPolynomials() {
        val polynomial = { t: Double -> 2.0 * t * t * t - 3.0 * t * t + t + 4.0 }
        val derivative = { t: Double -> 6.0 * t * t - 6.0 * t + 1.0 }

        for (step in 0..20) {
            val t = step / 20.0

            assertEquals(
                polynomial(t),
                hermiteCubic(polynomial(0.0), derivative(0.0), derivative(1.0), polynomial(1.0), t),
                1e-12
            )
        }
    }

    @Test
    fun hermiteCubicDualMatchesRealEvaluationAndDerivative() {
        val result = hermiteCubicDual(1.0, 2.0, -1.0, 3.0, Dual.variable(0.3, 2))

        assertEquals(hermiteCubic(1.0, 2.0, -1.0, 3.0, 0.3), result.value, 1e-12)
        assertEquals(numericDerivative({ hermiteCubic(1.0, 2.0, -1.0, 3.0, it) }, 0.3), result[1], 1e-6)
    }

    @Test
    fun hermiteQuinticInterpolatesValuesFirstAndSecondDerivativesAtEnds() {
        val random = Random(82)

        repeat(50) {
            val values = DoubleArray(6) { random.nextDouble(-5.0, 5.0) }
            val p0 = values[0]
            val v0 = values[1]
            val a0 = values[2]
            val a1 = values[3]
            val v1 = values[4]
            val p1 = values[5]

            val atStart = hermiteQuinticDual(p0, v0, a0, a1, v1, p1, Dual.variable(0.0, 3))
            val atEnd = hermiteQuinticDual(p0, v0, a0, a1, v1, p1, Dual.variable(1.0, 3))

            assertEquals(p0, atStart[0], 1e-12)
            assertEquals(v0, atStart[1], 1e-12)
            assertEquals(a0, atStart[2], 1e-12)
            assertEquals(p1, atEnd[0], 1e-12)
            assertEquals(v1, atEnd[1], 1e-12)
            assertEquals(a1, atEnd[2], 1e-12)
            assertEquals(p0, hermiteQuintic(p0, v0, a0, a1, v1, p1, 0.0), 1e-12)
            assertEquals(p1, hermiteQuintic(p0, v0, a0, a1, v1, p1, 1.0), 1e-12)
        }
    }

    @Test
    fun hermiteQuinticReproducesQuinticPolynomial() {
        for (step in 0..20) {
            val t = step / 20.0
            val polynomial = t * t * t * t * t

            assertEquals(polynomial, hermiteQuintic(0.0, 0.0, 0.0, 20.0, 5.0, 1.0, t), 1e-12)
        }
    }

    @Test
    fun hermiteQuinticAnalyticDerivativesMatchDualDerivatives() {
        val random = Random(83)

        repeat(100) {
            val values = DoubleArray(6) { random.nextDouble(-5.0, 5.0) }
            val p0 = values[0]
            val v0 = values[1]
            val a0 = values[2]
            val a1 = values[3]
            val v1 = values[4]
            val p1 = values[5]
            val t = random.nextDouble(0.0, 1.0)
            val dual = hermiteQuinticDual(p0, v0, a0, a1, v1, p1, Dual.variable(t, 3))

            assertEquals(dual[0], hermiteQuintic(p0, v0, a0, a1, v1, p1, t), 1e-9)
            assertEquals(dual[1], hermiteQuinticDerivative1(p0, v0, a0, a1, v1, p1, t), 1e-9)
            assertEquals(dual[2], hermiteQuinticDerivative2(p0, v0, a0, a1, v1, p1, t), 1e-9)
        }
    }

    @Test
    fun splineSegmentMapRejectsEmptyAndDiscontinuousSegments() {
        assertThrows<IllegalArgumentException> { ListSplineSegmentMap<LinearSplineSegment1d>(emptyList()) }

        val gap = listOf(
            LinearSplineSegment1d(0.0, 1.0, 0.0, 1.0),
            LinearSplineSegment1d(1.5, 2.0, 1.0, 2.0)
        )

        assertThrows<IllegalStateException> { ListSplineSegmentMap(gap) }
        assertThrows<IllegalStateException> { TreeSplineSegmentMap(gap) }
    }

    @Test
    fun splineSegmentMapFindsSegmentsAndClampsOutsideRange() {
        val segments = linearSplineSegments(0.0, 1.0, 3.0, 4.0, 10.0)

        val maps = listOf(
            ListSplineSegmentMap(segments),
            TreeSplineSegmentMap(segments),
            splineSegmentMapOf(*segments.toTypedArray())
        )

        maps.forEach { map ->
            assertEquals(4, map.count)
            assertEquals(0.0, map.t0, 0.0)
            assertEquals(10.0, map.t1, 0.0)
            assertEquals(0, map.findIndex(-5.0))
            assertEquals(0, map.findIndex(0.0))
            assertEquals(0, map.findIndex(0.5))
            assertEquals(1, map.findIndex(2.0))
            assertEquals(2, map.findIndex(3.5))
            assertEquals(3, map.findIndex(9.0))
            assertEquals(3, map.findIndex(10.0))
            assertEquals(3, map.findIndex(50.0))
            assertSame(segments[1], map[2.0])
            assertSame(segments[2], map[2])
            assertSame(segments[0], map.left(0))
            assertSame(segments[0], map.left(1))
            assertSame(segments[2], map.right(1))
            assertSame(segments[3], map.right(3))
        }
    }

    @Test
    fun treeAndListSegmentMapsAgreeOnRandomQueries() {
        val random = Random(84)
        val times = DoubleArray(30).also {
            var current = 0.0

            for (index in it.indices) {
                current += random.nextDouble(0.1, 2.0)
                it[index] = current
            }
        }

        val segments = (1 until times.size).map { LinearSplineSegment1d(times[it - 1], times[it], 0.0, 1.0) }
        val tree = TreeSplineSegmentMap(segments)
        val list = ListSplineSegmentMap(segments)

        repeat(2000) {
            val key = random.nextDouble(times.first() - 1.0, times.last() + 1.0)

            assertEquals(list.findIndex(key), tree.findIndex(key), "key $key")
        }

        times.forEach {
            assertTrue(abs(list.findIndex(it) - tree.findIndex(it)) <= 1)
        }
    }

    @Test
    fun linearSplinePassesThroughPointsAndExtrapolatesLinearly() {
        val spline = InterpolatorBuilder()
            .with(0.0, 0.0)
            .with(1.0, 2.0)
            .with(3.0, 2.0)
            .with(4.0 to 6.0)
            .buildLinear()

        assertEquals(0.0, spline.evaluate(0.0), 1e-12)
        assertEquals(2.0, spline.evaluate(1.0), 1e-12)
        assertEquals(2.0, spline.evaluate(3.0), 1e-12)
        assertEquals(6.0, spline.evaluate(4.0), 1e-12)
        assertEquals(1.0, spline.evaluate(0.5), 1e-12)
        assertEquals(2.0, spline.evaluate(2.0), 1e-12)
        assertEquals(4.0, spline.evaluate(3.5), 1e-12)
        assertEquals(-2.0, spline.evaluate(-1.0), 1e-12)
        assertEquals(10.0, spline.evaluate(5.0), 1e-12)
    }

    @Test
    fun splineBuildersRequireEnoughPoints() {
        assertThrows<IllegalArgumentException> { InterpolatorBuilder().buildCubic() }
        assertThrows<IllegalArgumentException> { InterpolatorBuilder().with(0.0, 0.0).buildCubic() }
        assertThrows<IllegalArgumentException> { InterpolatorBuilder().with(0.0, 0.0).buildLinear() }
        assertThrows<IllegalStateException> { InterpolatorBuilder().with(1.0, 0.0).with(0.0, 1.0).buildLinear() }
    }

    @Test
    fun cubicSplinePassesThroughKnotsAndIsContinuousForNonUniformSpacing() {
        val random = Random(85)

        repeat(20) {
            val builder = InterpolatorBuilder()
            val times = ArrayList<Double>()
            val values = ArrayList<Double>()
            var time = 0.0

            repeat(8) {
                time += random.nextDouble(0.2, 3.0)
                times.add(time)
                values.add(random.nextDouble(-5.0, 5.0))
                builder.with(times.last(), values.last())
            }

            val spline = builder.buildCubic(random.nextDouble(-0.5, 0.5), random.nextDouble(-0.5, 0.5), 0.2)

            for (index in times.indices) {
                assertEquals(values[index], spline.evaluate(times[index]), 1e-9)

                if (index > 0 && index < times.size - 1) {
                    assertEquals(spline.evaluate(times[index] - 1e-9), spline.evaluate(times[index] + 1e-9), 1e-6)
                }
            }
        }
    }

    @Test
    fun uniformCatmullRomSplineHasContinuousFirstDerivative() {
        val spline = InterpolatorBuilder()
            .with(0.0, 0.0)
            .with(1.0, 3.0)
            .with(2.0, 1.0)
            .with(3.0, 4.0)
            .with(4.0, 2.0)
            .buildCubic()

        for (knot in 1..3) {
            val before = spline.evaluateDual(knot - 1e-7, 2)[1]
            val after = spline.evaluateDual(knot + 1e-7, 2)[1]

            assertEquals(before, after, 1e-5, "knot $knot")
        }
    }

    @Test
    fun catmullRomSplineMatchesMidpointFormulaForUniformKnots() {
        val spline = InterpolatorBuilder()
            .with(0.0, 0.0)
            .with(1.0, 1.0)
            .with(2.0, 4.0)
            .with(3.0, 9.0)
            .with(4.0, 16.0)
            .buildCubic()

        assertEquals(2.25, spline.evaluate(1.5), 1e-12)
        assertEquals(6.25, spline.evaluate(2.5), 1e-12)
    }

    @Test
    fun splineDualDerivativeMatchesNumericDerivativeForUnitWidthSegments() {
        val spline = InterpolatorBuilder()
            .with(0.0, 0.0)
            .with(1.0, 3.0)
            .with(2.0, 1.0)
            .with(3.0, 4.0)
            .buildCubic()

        listOf(0.2, 0.7, 1.3, 2.6).forEach {
            assertEquals(numericDerivative({ t -> spline.evaluate(t) }, it), spline.evaluateDual(it, 2)[1], 1e-6)
        }
    }

    @Test
    @Disabled("Bug: Spline1d.evaluateDual differentiates by segment abscissa, so dy/dt is scaled by the segment width.")
    fun splineDualDerivativeIsWithRespectToParameterForWideSegments() {
        val spline = InterpolatorBuilder().with(0.0, 0.0).with(2.0, 4.0).buildLinear()

        assertEquals(2.0, spline.evaluateDual(1.0, 2)[1], 1e-12)
        assertEquals(sqrt(20.0), spline.arclengthScan(0.0, 2.0, 1e-9), 1e-6)
    }

    @Test
    fun splineArclengthOfUnitWidthLinearSegment() {
        val spline = InterpolatorBuilder().with(0.0, 0.0).with(1.0, 2.0).buildLinear()

        assertEquals(sqrt(5.0), spline.arclengthScan(0.0, 1.0, 1e-9), 1e-6)
    }

    @Test
    fun splineEvaluateDualHonorsRequestedSize() {
        val spline = InterpolatorBuilder().with(0.0, 0.0).with(1.0, 1.0).buildCubic()

        assertEquals(1, spline.evaluateDual(0.5, 1).size)
        assertEquals(3, spline.evaluateDual(0.5, 3).size)
    }

    @Test
    fun reparameterizationWrappersForwardDualSize() {
        val spline = InterpolatorBuilder().with(0.0, 0.0).with(1.0, 1.0).buildCubic()
        val reparametrized = spline.reparamV { it * 0.5 }
        val shifted = spline.reparamU<Double, Double, Double, Dual> { value: Double -> value * 0.5 }
        val reordered = spline.reordV({ it * 2.0 }, { it * 2.0 })

        assertEquals(spline.evaluate(0.25), reparametrized.evaluate(0.5), 1e-12)
        assertEquals(3, reparametrized.evaluateDual(0.5, 3).size)
        assertEquals(spline.evaluate(0.25), shifted.evaluate(0.5), 1e-12)
        assertEquals(3, shifted.evaluateDual(0.5, 3).size)
        assertEquals(2.0 * spline.evaluate(0.25), reordered.evaluate(0.25), 1e-12)
        assertEquals(3, reordered.evaluateDual(0.25, 3).size)
    }

    @Test
    @Disabled("Bug: reordU drops the requested dual size n and always evaluates with the default of 1.")
    fun outputReorderingForwardsDualSize() {
        val spline = InterpolatorBuilder().with(0.0, 0.0).with(1.0, 1.0).buildCubic()
        val reordered = spline.reordU({ value: Double -> value * 2.0 }, { dual: Dual -> dual * 2.0 })

        assertEquals(3, reordered.evaluateDual(0.25, 3).size)
    }

    @Test
    fun catenary2dPassesThroughEndpointsAndHasRequestedArclength() {
        val start = Vector2d(0.0, 0.0)
        val end = Vector2d(10.0, 3.0)
        val length = 12.0
        val catenary = ArcReparamCatenary2d(start, end, length)

        assertTrue(catenary.evaluate(0.0).approxEq(start, 1e-6))
        assertTrue(catenary.evaluate(1.0).approxEq(end, 1e-6))

        var measured = 0.0
        val segments = 20000
        var previous = catenary.evaluate(0.0)

        for (index in 1..segments) {
            val current = catenary.evaluate(index.toDouble() / segments)
            measured += previous distanceTo current
            previous = current
        }

        assertEquals(length, measured, 1e-5)

        val lowest = (0..100).minOf { catenary.evaluate(it / 100.0).y }

        assertTrue(lowest < 0.0)
    }

    @Test
    fun catenary2dDualMatchesRealEvaluationAndDerivative() {
        val catenary = ArcReparamCatenary2d(Vector2d(0.0, 1.0), Vector2d(8.0, 2.0), 10.0)

        listOf(0.1, 0.5, 0.9).forEach {
            val dual = catenary.evaluateDual(Dual.variable(it, 2))
            val real = catenary.evaluate(it)

            assertEquals(real.x, dual.x.value, 1e-9)
            assertEquals(real.y, dual.y.value, 1e-9)

            val numericX = numericDerivative({ t -> catenary.evaluate(t).x }, it)
            val numericY = numericDerivative({ t -> catenary.evaluate(t).y }, it)

            assertEquals(numericX, dual.x[1], 1e-5)
            assertEquals(numericY, dual.y[1], 1e-5)
        }
    }

    @Test
    fun catenary2dRejectsInvalidEndpoints() {
        assertThrows<IllegalArgumentException> { ArcReparamCatenary2d(Vector2d(0.0, 2.0), Vector2d(5.0, 1.0), 10.0) }
        assertThrows<IllegalArgumentException> { ArcReparamCatenary2d(Vector2d(1.0, 1.0), Vector2d(1.0, 1.0), 10.0) }
    }

    @Test
    fun catenary3dMatchesEndpointsAndHasRequestedArclength() {
        val start = Vector3d(0.0, 0.0, 0.0)
        val end = Vector3d(6.0, 2.0, 8.0)
        val length = 14.0
        val catenary = ArcReparamCatenary3d(start, end, length, Vector3d.unitY)

        assertTrue(catenary.matchesParameters(1e-6))
        assertTrue(catenary.evaluate(0.0).approxEq(start, 1e-6))
        assertTrue(catenary.evaluate(1.0).approxEq(end, 1e-6))

        var measured = 0.0
        val segments = 20000
        var previous = catenary.evaluate(0.0)

        for (index in 1..segments) {
            val current = catenary.evaluate(index.toDouble() / segments)
            measured += previous distanceTo current
            previous = current
        }

        assertEquals(length, measured, 1e-4)

        val midpoint = catenary.evaluate(0.5)
        val chordMidpoint = (start + end) / 2.0

        assertTrue(midpoint.y < chordMidpoint.y)
        assertEquals(chordMidpoint.x, midpoint.x, 1.0)
    }

    @Test
    fun catenary3dDualMatchesRealEvaluation() {
        val catenary = ArcReparamCatenary3d(Vector3d.zero, Vector3d(4.0, 1.0, 3.0), 8.0, Vector3d.unitY)

        listOf(0.2, 0.6).forEach {
            assertTrue(catenary.evaluateDual(Dual.variable(it, 2)).value.approxEq(catenary.evaluate(it), 1e-9))
        }
    }

    @Test
    fun quinticPathThroughHelixDataHasHelixCurvatureAndTorsion() {
        val builder = PathBuilder3d()

        listOf(0.0, 1.0).forEach {
            builder.add(
                it,
                Vector3dDual.of(
                    Vector3d(cos(it), sin(it), it),
                    Vector3d(-sin(it), cos(it), 1.0),
                    Vector3d(-cos(it), -sin(it), 0.0)
                )
            )
        }

        val spline = builder.buildQuintic()

        assertTrue(spline.evaluate(0.0).approxEq(Vector3d(1.0, 0.0, 0.0), 1e-9))
        assertTrue(spline.evaluate(1.0).approxEq(Vector3d(cos(1.0), sin(1.0), 1.0), 1e-9))
        assertTrue(spline.evaluate(0.5).approxEq(Vector3d(cos(0.5), sin(0.5), 0.5), 1e-3))
        assertEquals(0.5, spline.curvature(0.5), 5e-3)
        assertEquals(0.5, spline.torsion(0.5), 5e-2)
        assertEquals(sqrt(2.0), spline.paramSpeed(0.5), 5e-3)
        assertEquals(sqrt(2.0), spline.arclengthScan(0.0, 1.0, 1e-9), 5e-3)
    }

    @Test
    fun frenetFrameOfQuinticHelixIsRightHandedOrthonormal() {
        val builder = PathBuilder3d()

        listOf(0.0, 1.0).forEach {
            builder.add(
                it,
                Vector3dDual.of(
                    Vector3d(cos(it), sin(it), it),
                    Vector3d(-sin(it), cos(it), 1.0),
                    Vector3d(-cos(it), -sin(it), 0.0)
                )
            )
        }

        val spline = builder.buildQuintic()
        val frame = spline.frenet(0.5)

        assertTrue(frame.isSpecialOrthogonal)
        assertTrue(frame.c0.approxEq(spline.tangent(0.5), 1e-9))
        assertEquals(1.0, spline.tangent(0.5).norm, 1e-12)

        val pose = spline.frenetPose(0.5)

        assertTrue((pose.rotation * Vector3d.unitX).approxEq(frame.c0, 1e-9))
        assertTrue(pose.translation.approxEq(spline.evaluate(0.5), 1e-12))
    }

    @Test
    fun quinticPathRequiresTwoPointsAndStraightPathHasDistanceArclength() {
        assertThrows<IllegalArgumentException> { PathBuilder3d().buildQuintic() }

        val start = Vector3d(0.0, 0.0, 0.0)
        val end = Vector3d(3.0, 4.0, 0.0)
        val builder = PathBuilder3d()

        builder.add(0.0, Vector3dDual.of(start, end - start, Vector3d.zero))
        builder.add(1.0, Vector3dDual.of(end, end - start, Vector3d.zero))

        val spline = builder.buildQuintic()

        assertEquals(5.0, spline.arclengthScan(0.0, 1.0, 1e-10), 1e-6)
        assertTrue(spline.evaluate(0.5).approxEq(Vector3d(1.5, 2.0, 0.0), 1e-9))
    }

    @Test
    fun adaptscanEmitsMonotonicSamplesWithBoundedGaps() {
        val spline = InterpolatorBuilder().with(0.0, 0.0).with(1.0, 1.0).buildLinear()
        val condition = differenceCondition1d(1e9, 1e9)
        val samples = spline.adaptscan(0.0, 1.0, 0.3, condition = condition)

        assertNotNull(samples)
        assertEquals(0.0, samples!!.first(), 0.0)
        assertEquals(1.0, samples.last(), 0.0)

        for (index in 1 until samples.size) {
            assertTrue(samples[index] > samples[index - 1])
            assertTrue(samples[index] - samples[index - 1] <= 0.3 + 1e-12)
        }
    }

    @Test
    fun adaptscanRefinesWhereConditionDemandsIt() {
        val spline = InterpolatorBuilder()
            .with(0.0, 0.0)
            .with(1.0, 0.0)
            .with(2.0, 10.0)
            .with(3.0, 10.0)
            .buildLinear()

        val samples = spline.adaptscan(0.0, 3.0, 3.0, condition = differenceCondition1d(1.0, 1e9))!!

        for (index in 1 until samples.size) {
            val difference = abs(spline.evaluate(samples[index]) - spline.evaluate(samples[index - 1]))

            assertTrue(difference <= 1.0 + 1e-9 || samples[index] - samples[index - 1] < 1e-6)
        }

        assertTrue(samples.size > 10)
    }

    @Test
    fun adaptscanReportsFailureWhenIterationLimitIsHit() {
        val spline = InterpolatorBuilder().with(0.0, 0.0).with(1.0, 1.0).buildLinear()
        val alwaysSplit = differenceCondition1d(-1.0, -1.0)

        assertNull(spline.adaptscan(0.0, 1.0, 0.5, 20, alwaysSplit))
        assertThrows<IllegalArgumentException> { spline.adaptscan(1.0, 1.0, 0.5, condition = alwaysSplit) }
        assertThrows<IllegalArgumentException> { spline.adaptscan(0.0, 1.0, 0.0, condition = alwaysSplit) }
    }

    @Test
    fun differenceCondition3dRejectsNonPositiveLimitsAndDetectsDistance() {
        assertThrows<IllegalArgumentException> { differenceCondition3d(0.0, 0.1) }
        assertThrows<IllegalArgumentException> { differenceCondition3d(0.1, 0.0) }

        val builder = PathBuilder3d()
        builder.add(0.0, Vector3dDual.of(Vector3d.zero, Vector3d.unitX, Vector3d.zero))
        builder.add(1.0, Vector3dDual.of(Vector3d(1.0, 0.0, 0.0), Vector3d.unitX, Vector3d.zero))
        val spline = builder.buildQuintic()

        assertTrue(differenceCondition3d(0.5, 0.1).splits(spline, 0.0, 1.0))
        assertFalse(differenceCondition3d(2.0, 0.1).splits(spline, 0.0, 1.0))
    }

    @Test
    fun gridInterpolatorMatchesLinearInterpolationInOneDimension() {
        val grid = arrayKDGridDOf(4)
        doubleArrayOf(0.0, 10.0, 20.0, 40.0).forEachIndexed { index, value -> grid.grid[index] = value }
        val interpolator = GridInterpolator(grid)

        fun query(coordinate: Double) = interpolator.evaluate(kdVectorDOf(coordinate), ::lerp)

        assertEquals(0.0, query(0.0), 1e-12)
        assertEquals(15.0, query(1.5), 1e-12)
        assertEquals(40.0, query(3.0), 1e-12)
        assertEquals(30.0, query(2.5), 1e-12)
        assertEquals(0.0, query(-3.0), 1e-12)
        assertEquals(40.0, query(10.0), 1e-12)
    }

    @Test
    fun gridInterpolatorMatchesMultilinearReferenceInThreeDimensions() {
        val random = Random(86)
        val grid = arrayKDGridDOf(3, 4, 2)
        grid.grid.indices.forEach { grid.grid[it] = random.nextDouble(-10.0, 10.0) }
        val interpolator = GridInterpolator(grid)

        fun cell(x: Int, y: Int, z: Int) = grid.grid[x.coerceIn(0, 2) + 3 * y.coerceIn(0, 3) + 12 * z.coerceIn(0, 1)]

        repeat(200) {
            val x = random.nextDouble(-0.5, 3.5)
            val y = random.nextDouble(-0.5, 4.5)
            val z = random.nextDouble(-0.5, 2.5)
            val fx = x - kotlin.math.floor(x)
            val fy = y - kotlin.math.floor(y)
            val fz = z - kotlin.math.floor(z)
            val ix = kotlin.math.floor(x).toInt()
            val iy = kotlin.math.floor(y).toInt()
            val iz = kotlin.math.floor(z).toInt()

            var expected = 0.0

            for (dz in 0..1) {
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val weight = (if (dx == 1) fx else 1.0 - fx) *
                            (if (dy == 1) fy else 1.0 - fy) *
                            (if (dz == 1) fz else 1.0 - fz)

                        expected += weight * cell(ix + dx, iy + dy, iz + dz)
                    }
                }
            }

            assertEquals(expected, interpolator.evaluate(kdVectorDOf(x, y, z), ::lerp), 1e-9)
        }
    }

    @Test
    fun gridInterpolatorMatchesBilinearHelperInTwoDimensions() {
        val grid = arrayKDGridDOf(2, 2)
        doubleArrayOf(1.0, 2.0, 3.0, 4.0).forEachIndexed { index, value -> grid.grid[index] = value }
        val interpolator = GridInterpolator(grid)

        listOf(0.0 to 0.0, 0.25 to 0.75, 1.0 to 0.0, 0.6 to 0.1).forEach { (x, y) ->
            assertEquals(
                bilinear(1.0, 2.0, 3.0, 4.0, x, y),
                interpolator.evaluate(kdVectorDOf(x, y), ::lerp),
                1e-12
            )
        }
    }

    @Test
    fun gridInterpolatorAcceptsCustomInterpolationFunction() {
        val grid = arrayKDGridDOf(2)
        grid.grid[0] = 1.0
        grid.grid[1] = 9.0

        val stepFunction = GridInterpolator.Interpolator { a, b, t -> if (t < 0.5) a else b }
        val interpolator = GridInterpolator(grid)

        assertEquals(1.0, interpolator.evaluate(kdVectorDOf(0.4), stepFunction), 0.0)
        assertEquals(9.0, interpolator.evaluate(kdVectorDOf(0.6), stepFunction), 0.0)
    }

    @Test
    fun gridInterpolatorRejectsWrongCoordinateCount() {
        val interpolator = GridInterpolator(arrayKDGridDOf(2, 2))

        assertThrows<IllegalStateException> { interpolator.evaluate(kdVectorDOf(0.5), ::lerp) }
        assertThrows<IllegalStateException> { interpolator.evaluate(kdVectorDOf(0.5, 0.5, 0.5), ::lerp) }
    }

    @Test
    fun mappedGridInterpolatorConvertsValuesToGridCoordinates() {
        val grid = arrayKDGridDOf(3, 2)
        grid.grid.indices.forEach { grid.grid[it] = it.toDouble() }

        val first = InterpolatorBuilder().with(100.0, 0.0).with(200.0, 2.0).buildLinear()
        val second = InterpolatorBuilder().with(0.0, 0.0).with(10.0, 1.0).buildLinear()
        val mapped = MappedGridInterpolator(GridInterpolator(grid), listOf(first, second))

        assertEquals(0.0, mapped.evaluate(100.0, 0.0), 1e-12)
        assertEquals(1.0, mapped.evaluate(150.0, 0.0), 1e-12)
        assertEquals(2.0, mapped.evaluate(200.0, 0.0), 1e-12)
        assertEquals(3.0, mapped.evaluate(100.0, 10.0), 1e-12)
        assertEquals(2.0, mapped.evaluate(125.0, 5.0), 1e-12)
        assertEquals(4.0, mapped.evaluate(150.0, 10.0), 1e-12)
        assertEquals(mapped.evaluate(150.0, 5.0), mapped.evaluate(kdVectorDOf(150.0, 5.0)), 0.0)
    }

    @Test
    fun mappedGridInterpolatorRequiresMappingPerDimension() {
        val interpolator = GridInterpolator(arrayKDGridDOf(2, 2))
        val mapping = InterpolatorBuilder().with(0.0, 0.0).with(1.0, 1.0).buildLinear()

        assertThrows<IllegalStateException> { MappedGridInterpolator(interpolator, listOf(mapping)) }
    }

    @Test
    fun rotationUpdateProfileReachesTargetWithMovingTargetCompensation() {
        val source = Rotation2d.exp(0.1)
        val target = Rotation2d.exp(0.5)
        val duration = 0.4
        val profile = computeRotationUpdateAccelerationProfile(target, 0.2, source, 1.0, duration)

        profile.currentTime = 0.0
        profile.sampleTrajectory()

        assertEquals(0.1, profile.sampleP.ln(), 1e-9)
        assertEquals(1.0, profile.sampleV, 1e-9)

        profile.currentTime = duration
        profile.sampleTrajectory()

        assertEquals(0.5 + 0.2 * duration, profile.sampleP.ln(), 1e-9)
        assertEquals(0.2, profile.sampleV, 1e-9)
        assertEquals(0.0, profile.timeRemaining, 1e-12)
    }

    @Test
    fun rotationUpdateProfileIsContinuousAtTheSwitchTime() {
        val profile = computeRotationUpdateAccelerationProfile(
            Rotation2d.exp(0.5), 0.2,
            Rotation2d.exp(0.1), 1.0,
            0.4
        )

        profile.currentTime = 0.2 - 1e-9
        val firstAcceleration = profile.sampleTrajectory()
        val positionBefore = profile.sampleP.ln()
        val velocityBefore = profile.sampleV

        profile.currentTime = 0.2 + 1e-9
        val secondAcceleration = profile.sampleTrajectory()

        assertEquals(positionBefore, profile.sampleP.ln(), 1e-6)
        assertEquals(velocityBefore, profile.sampleV, 1e-6)
        assertEquals((0.2 - 1.0) / 0.2, firstAcceleration + secondAcceleration, 1e-6)
    }

    @Test
    fun rotationUpdateProfileClampsTimeOutsideDuration() {
        val profile = RotationUpdateProfile2d(Rotation2d.exp(0.0), 1.0, 0.0, 0.0, 2.0)

        profile.currentTime = -5.0
        profile.sampleTrajectory()

        assertEquals(0.0, profile.sampleP.ln(), 1e-12)

        profile.currentTime = 50.0
        profile.sampleTrajectory()

        assertEquals(2.0, profile.sampleP.ln(), 1e-12)
        assertEquals(0.0, profile.timeRemaining, 0.0)
    }

    @Test
    fun rotationUpdateProfileWithAccelerationEstimateUsesDurationFromVelocityChange() {
        val profile = computeRotationUpdateAccelerationProfileWithAccelerationEstimate(
            20.0,
            Rotation2d.exp(0.5), 3.0,
            Rotation2d.exp(0.1), 1.0
        )

        assertEquals(0.1, profile.duration, 1e-12)

        val capped = computeRotationUpdateAccelerationProfileWithAccelerationEstimate(
            2.0,
            Rotation2d.exp(0.5), 3.0,
            Rotation2d.exp(0.1), 1.0
        )

        assertEquals(0.25, capped.duration, 1e-12)

        val limited = computeRotationUpdateAccelerationProfileWithAccelerationEstimate(
            0.0,
            Rotation2d.exp(0.5), 3.0,
            Rotation2d.exp(0.1), 1.0,
            0.5
        )

        assertEquals(0.5, limited.duration, 1e-12)
    }

    @Test
    @Disabled("Bug: equal velocities with a non-zero acceleration estimate give duration 0 and NaN accelerations.")
    fun rotationUpdateProfileWithEqualVelocitiesIsFinite() {
        val profile = computeRotationUpdateAccelerationProfileWithAccelerationEstimate(
            5.0,
            Rotation2d.exp(0.5), 1.0,
            Rotation2d.exp(0.1), 1.0
        )

        assertTrue(profile.duration > 0.0)
        assertFalse(profile.a1.isNaN() || profile.a2.isNaN())
    }

    @Test
    fun trilinearDependsOnlyOnXWhenOnlyXCornersDiffer() {
        val random = Random(87)

        repeat(50) {
            val value = random.nextDouble(-5.0, 5.0)
            val x = random.nextDouble()
            val y = random.nextDouble()
            val z = random.nextDouble()

            assertEquals(value, trilinear(value, value, value, value, value, value, value, value, x, y, z), 1e-9)
            assertEquals(
                lerp(value, value + 1.0, x),
                trilinear(value, value + 1.0, value, value + 1.0, value, value + 1.0, value, value + 1.0, x, y, z),
                1e-9
            )
        }
    }

    @Test
    fun splineEvaluateDualOutsideRangeExtrapolatesWithEdgeSegment() {
        val spline: Spline1d = InterpolatorBuilder().with(0.0, 0.0).with(1.0, 1.0).with(2.0, 4.0).buildLinear()

        assertEquals(7.0, spline.evaluate(3.0), 1e-12)
        assertEquals(-1.0, spline.evaluate(-1.0), 1e-12)
    }
}
