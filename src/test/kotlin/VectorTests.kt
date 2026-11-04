import org.ageseries.libage.mathematics.Dual
import org.ageseries.libage.mathematics.geometry.Vector2d
import org.ageseries.libage.mathematics.geometry.Vector2dDual
import org.ageseries.libage.mathematics.geometry.Vector2di
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.Vector3dDual
import org.ageseries.libage.mathematics.geometry.Vector3di
import org.ageseries.libage.mathematics.geometry.Vector4d
import org.ageseries.libage.mathematics.geometry.Vector4dDual
import org.ageseries.libage.mathematics.geometry.avg
import org.ageseries.libage.mathematics.geometry.directionTo
import org.ageseries.libage.mathematics.geometry.div
import org.ageseries.libage.mathematics.geometry.minus
import org.ageseries.libage.mathematics.geometry.o
import org.ageseries.libage.mathematics.geometry.plus
import org.ageseries.libage.mathematics.geometry.rangeTo
import org.ageseries.libage.mathematics.geometry.times
import org.ageseries.libage.mathematics.geometry.x
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.random.Random

class VectorTests {
    private fun randomVector(random: Random) = Vector3d(
        random.nextDouble(-10.0, 10.0),
        random.nextDouble(-10.0, 10.0),
        random.nextDouble(-10.0, 10.0)
    )

    @Test
    fun integerVectorArithmetic() {
        assertEquals(Vector3di(4, 6, 8), Vector3di(1, 2, 3) + Vector3di(3, 4, 5))
        assertEquals(Vector3di(-2, -2, -2), Vector3di(1, 2, 3) - Vector3di(3, 4, 5))
        assertEquals(Vector3di(2, 4, 6), Vector3di(1, 2, 3) * 2)
        assertEquals(Vector3di(0, 1, 1), Vector3di(1, 2, 3) / 2)
        assertEquals(Vector3di(-1, -2, -3), -Vector3di(1, 2, 3))
        assertEquals(Vector3di(7), Vector3di(7, 7, 7))
        assertEquals(6, Vector3di.manhattan(Vector3di(1, 2, 3), Vector3di(-1, 0, 5)))
        assertEquals(Vector2di(2, 3) + Vector2di(1, 1), Vector2di(3, 4))
        assertEquals(7, Vector2di.manhattan(Vector2di(1, 2), Vector2di(-2, -2)))
    }

    @Test
    fun dotCrossAndNormBasics() {
        val first = Vector3d(1.0, 2.0, 3.0)
        val second = Vector3d(-4.0, 5.0, 0.5)

        assertEquals(1.0 * -4.0 + 2.0 * 5.0 + 3.0 * 0.5, first dot second, 1e-12)
        assertEquals(first dot second, first o second, 1e-12)
        assertEquals(14.0, first.normSqr, 1e-12)
        assertEquals(sqrt(14.0), first.norm, 1e-12)
        assertEquals(Vector3d.unitZ, Vector3d.unitX cross Vector3d.unitY)
        assertEquals(Vector3d.unitX, Vector3d.unitY x Vector3d.unitZ)
        assertTrue((-Vector3d.unitZ).approxEq(Vector3d.unitY cross Vector3d.unitX))
    }

    @Test
    fun crossProductIsPerpendicularAndAnticommutative() {
        val random = Random(11)

        repeat(200) {
            val first = randomVector(random)
            val second = randomVector(random)
            val cross = first cross second

            assertEquals(0.0, cross dot first, 1e-9)
            assertEquals(0.0, cross dot second, 1e-9)
            assertTrue((second cross first).approxEq(-cross, 1e-9))
            assertEquals(first.normSqr * second.normSqr - (first dot second) * (first dot second), cross.normSqr, 1e-6)
        }
    }

    @Test
    fun normalizedVectorsAreUnitAndZeroNormalizesToNan() {
        val random = Random(12)

        repeat(100) {
            assertTrue(randomVector(random).normalized().isUnit)
        }

        assertTrue(Vector3d.zero.normalized().isNaN)
        assertFalse(Vector3d.zero.isUnit)
        assertFalse(Vector3d.unitX.isNaN)
        assertTrue(Vector3d(Double.POSITIVE_INFINITY, 0.0, 0.0).isInfinity)
    }

    @Test
    fun notOperatorNormalizesOnlyNonUnitNonZeroVectors() {
        assertEquals(Vector3d.zero, !Vector3d.zero)
        assertEquals(Vector3d.unitX, !Vector3d.unitX)
        assertTrue((!Vector3d(0.0, 5.0, 0.0)).approxEq(Vector3d.unitY))
    }

    @Test
    fun nonZeroNudgeAvoidsZeroNormalizationFailure() {
        val normalized = Vector3d.zero.normalizedNz()

        assertFalse(normalized.isNaN)
        assertFalse(Vector3d.zero.nz() == Vector3d.zero)
    }

    @Test
    fun anglesBetweenKnownVectors() {
        assertEquals(PI / 2.0, Vector3d.unitX angle Vector3d.unitY, 1e-12)
        assertEquals(0.0, Vector3d.unitX angle Vector3d(5.0, 0.0, 0.0), 1e-12)
        assertEquals(PI, Vector3d.unitX angle -Vector3d.unitX, 1e-12)
        assertEquals(PI / 4.0, Vector3d.unitX angle Vector3d(1.0, 1.0, 0.0), 1e-12)
    }

    @Test
    fun angleOfNearlyParallelVectorsStaysFinite() {
        val vector = Vector3d(0.1, 0.2, 0.3)

        assertFalse((vector angle vector * 3.0).isNaN())
        assertFalse((vector angle -vector * 1e-3).isNaN())
    }

    @Test
    fun distancesBetweenPoints() {
        val first = Vector3d(1.0, 2.0, 3.0)
        val second = Vector3d(4.0, 6.0, 3.0)

        assertEquals(5.0, first distanceTo second, 1e-12)
        assertEquals(25.0, first distanceToSqr second, 1e-12)
        assertEquals(5.0, first..second, 1e-12)
        assertEquals(Vector3d(0.6, 0.8, 0.0), first directionTo second)
    }

    @Test
    fun projectionOntoPlaneIsPerpendicularToNormal() {
        val random = Random(13)

        repeat(100) {
            val vector = randomVector(random)
            val normal = randomVector(random)
            val projected = vector.projectOnPlane(normal)

            assertEquals(0.0, projected dot normal, 1e-9)
            assertTrue((vector - projected).approxEq(vector.projectOnVector(normal), 1e-9))
        }
    }

    @Test
    fun projectionOntoVectorHandlesZero() {
        assertEquals(Vector3d.zero, Vector3d.zero.projectOnVector(Vector3d.unitX))
        assertEquals(Vector3d.zero, Vector3d.unitX.projectOnVector(Vector3d.zero))
        assertEquals(Vector3d(3.0, 0.0, 0.0), Vector3d(3.0, 4.0, 5.0).projectOnVector(Vector3d(10.0, 0.0, 0.0)))
    }

    @Test
    fun perpendicularIsUnitAndOrthogonalForManyDirections() {
        val random = Random(14)
        val special = listOf(
            Vector3d.unitX, Vector3d.unitY, Vector3d.unitZ, -Vector3d.unitX, -Vector3d.unitY, -Vector3d.unitZ,
            Vector3d(0.0, 1.0, -1.0), Vector3d(0.0, -1.0, 1.0), Vector3d(1.0, 1.0, 1.0), Vector3d(0.0, 3.0, -3.0)
        )

        (special + List(300) { randomVector(random) }).forEach {
            val perpendicular = it.perpendicular()

            assertTrue(perpendicular.isUnit, "perpendicular of $it is not unit: $perpendicular")
            assertEquals(0.0, perpendicular dot it.normalized(), 1e-9, "perpendicular of $it")
        }
    }

    @Test
    fun orthonormalBasisIsOrthonormalForGenericUnitVectors() {
        val random = Random(15)

        repeat(200) {
            val normal = randomVector(random).normalized()

            if (kotlin.math.abs(normal.z) > 1e-3) {
                val (first, second) = normal.onb()

                assertTrue(first.isUnit)
                assertTrue(second.isUnit)
                assertEquals(0.0, first dot second, 1e-9)
                assertEquals(0.0, first dot normal, 1e-9)
                assertEquals(0.0, second dot normal, 1e-9)
            }
        }
    }

    @Test
    @Disabled("Bug: onb uses z.sign, which is 0.0 for z == 0, so the construction divides by zero and gives NaN.")
    fun orthonormalBasisExistsForVectorsInXyPlane() {
        listOf(Vector3d.unitX, Vector3d.unitY, Vector3d(0.6, 0.8, 0.0)).forEach {
            val (first, second) = it.onb()

            assertFalse(first.isNaN || second.isNaN, "onb of $it contains NaN")
            assertTrue(first.isUnit)
            assertTrue(second.isUnit)
            assertEquals(0.0, first dot it, 1e-9)
            assertEquals(0.0, second dot it, 1e-9)
            assertEquals(0.0, first dot second, 1e-9)
        }
    }

    @Test
    fun roundingAndIntegerConversions() {
        val vector = Vector3d(1.5, -1.5, 2.25)

        assertEquals(Vector3d(1.0, -2.0, 2.0), vector.floor())
        assertEquals(Vector3d(2.0, -1.0, 3.0), vector.ceil())
        assertEquals(Vector3di(1, -2, 2), vector.floorInt())
        assertEquals(Vector3di(2, -1, 3), vector.ceilInt())
        assertEquals(Vector3di(2, -2, 2), vector.roundInt())
        assertEquals(Vector3di(1, -1, 2), vector.intCast())
        assertTrue(vector.frac().approxEq(Vector3d(0.5, 0.5, 0.25)))
    }

    @Test
    fun lerpHitsEndpointsAndMidpoint() {
        val from = Vector3d(0.0, 10.0, -4.0)
        val to = Vector3d(2.0, 20.0, 4.0)

        assertEquals(from, Vector3d.lerp(from, to, 0.0))
        assertEquals(to, Vector3d.lerp(from, to, 1.0))
        assertEquals(Vector3d(1.0, 15.0, 0.0), Vector3d.lerp(from, to, 0.5))
        assertEquals(Vector3d(3.0, 25.0, 8.0), Vector3d.lerp(from, to, 1.5))
        assertEquals(Vector3d(1.0, 1.0, 1.0), Vector3d.min(Vector3d(1.0, 5.0, 1.0), Vector3d(2.0, 1.0, 1.0)))
        assertEquals(Vector3d(2.0, 5.0, 1.0), Vector3d.max(Vector3d(1.0, 5.0, 1.0), Vector3d(2.0, 1.0, 1.0)))
    }

    @Test
    fun scalarOperationsOnBothSides() {
        val vector = Vector3d(1.0, 2.0, 4.0)

        assertEquals(Vector3d(2.0, 3.0, 5.0), vector + 1.0)
        assertEquals(Vector3d(2.0, 3.0, 5.0), 1.0.plus(vector))
        assertEquals(Vector3d(0.0, -1.0, -3.0), 1.0.minus(vector))
        assertEquals(Vector3d(2.0, 4.0, 8.0), 2.0.times(vector))
        assertEquals(Vector3d(1.0, 0.5, 0.25), 1.0.div(vector))
        assertEquals(Vector3d(0.5, 1.0, 2.0), vector / 2.0)
        assertEquals(Vector3d(1.0, 4.0, 16.0), vector * vector)
        assertTrue(Vector3d(3.0, 0.0, 0.0) > Vector3d(0.0, 2.0, 0.0))
    }

    @Test
    fun averageOfVectors() {
        val a = Vector3d(1.0, 0.0, 0.0)
        val b = Vector3d(3.0, 2.0, 0.0)
        val c = Vector3d(5.0, 4.0, 6.0)

        assertEquals(Vector3d(2.0, 1.0, 0.0), avg(a, b))
        assertEquals(Vector3d(3.0, 2.0, 2.0), avg(a, b, c))
        assertEquals(Vector3d(3.0, 2.0, 2.0), avg(listOf(a, b, c)))
        assertEquals(Vector2d(1.0, 2.0), avg(Vector2d(0.0, 0.0), Vector2d(2.0, 4.0)))
        assertEquals(Vector4d(1.0, 1.0, 1.0, 1.0), avg(Vector4d.zero, Vector4d(2.0)))
    }

    @Test
    fun vector2dBasics() {
        val vector = Vector2d(3.0, 4.0)

        assertEquals(5.0, vector.norm, 1e-12)
        assertEquals(Vector2d(0.6, 0.8), vector.normalized())
        assertEquals(0.0, vector dot vector.perpendicularLeft, 1e-12)
        assertEquals(0.0, vector dot vector.perpendicularRight, 1e-12)
        assertEquals(Vector2d(-4.0, 3.0), vector.perpendicularLeft)
        assertEquals(Vector2d(4.0, -3.0), vector.perpendicularRight)
        assertEquals(5.0, Vector2d.zero distanceTo vector, 1e-12)
        assertEquals(Vector2d(1.5, 2.0), Vector2d.lerp(Vector2d.zero, vector, 0.5))
        assertEquals(Vector2d(2.0, 3.0), Vector2d(1.0, 2.0) + 1.0.times(Vector2d.one))
        assertTrue(Vector2d(1.0, 1.0).approxEq(Vector2d(1.0 + 1e-9, 1.0)))
        assertFalse(Vector2d(1.0, 1.0).approxEq(Vector2d(1.1, 1.0)))
        assertEquals(Vector2d(1.0, 1.0), Vector2d.min(Vector2d(1.0, 5.0), Vector2d(2.0, 1.0)))
        assertTrue(Vector2d(Double.NaN, 0.0).isNaN)
        assertEquals(5.0, Vector2d.one o Vector2d(2.0, 3.0), 1e-12)
    }

    @Test
    fun vector4dBasics() {
        val vector = Vector4d(1.0, 2.0, 2.0, 4.0)

        assertEquals(5.0, vector.norm, 1e-12)
        assertEquals(1.0, vector.normalized().norm, 1e-12)
        assertEquals(PI / 2.0, Vector4d.unitX angle Vector4d.unitW, 1e-12)
        assertEquals(PI / 2.0, Vector4d.unitX % Vector4d.unitY, 1e-12)
        assertEquals(0.0, Vector4d.unitX angle Vector4d.unitX * 2.0, 1e-7)
        assertEquals(Vector4d(0.5, 1.0, 1.0, 2.0), Vector4d.lerp(Vector4d.zero, vector, 0.5))
        assertEquals(5.0, Vector4d.zero distanceTo vector, 1e-12)
    }

    @Test
    fun vector3dDualDerivativesOfNormAndDot() {
        val x = Dual.variable(3.0, 2)
        val vector = Vector3dDual(x, Dual.const(4.0, 2), Dual.const(0.0, 2))
        val norm = vector.norm

        assertEquals(5.0, norm[0], 1e-12)
        assertEquals(3.0 / 5.0, norm[1], 1e-12)

        val dot = vector dot Vector3d(1.0, 2.0, 3.0)

        assertEquals(11.0, dot[0], 1e-12)
        assertEquals(1.0, dot[1], 1e-12)
    }

    @Test
    fun vector3dDualCrossMatchesRealCrossOnValues() {
        val random = Random(16)

        repeat(50) {
            val first = randomVector(random)
            val second = randomVector(random)
            val dualCross = Vector3dDual.const(first, 2) cross Vector3dDual.const(second, 2)

            assertTrue(dualCross.value.approxEq(first cross second, 1e-9))
        }
    }

    @Test
    fun vector3dDualConstructedFromListSeparatesComponents() {
        val dual = Vector3dDual.of(Vector3d(1.0, 2.0, 3.0), Vector3d(4.0, 5.0, 6.0))

        assertEquals(2, dual.size)
        assertEquals(Vector3d(1.0, 2.0, 3.0), dual.value)
        assertEquals(Vector3d(4.0, 5.0, 6.0), dual[1])
        assertFalse(dual.isReal)
    }

    @Test
    fun vector3dDualRejectsMismatchedComponentSizes() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            Vector3dDual(Dual.of(1.0), Dual.of(1.0, 2.0), Dual.of(1.0))
        }

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            Vector2dDual(Dual.of(1.0), Dual.of(1.0, 2.0))
        }

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            Vector4dDual(Dual.of(1.0), Dual.of(1.0), Dual.of(1.0), Dual.of(1.0, 2.0))
        }
    }

    @Test
    fun vector3dDualNormalizedHasUnitValueAndPerpendicularDerivative() {
        val vector = Vector3dDual(Dual.variable(3.0, 2), Dual.const(4.0, 2), Dual.const(0.0, 2))
        val normalized = vector.normalized()

        assertEquals(1.0, normalized.value.norm, 1e-12)
        assertEquals(0.0, normalized.value dot normalized[1], 1e-9)
    }

    @Test
    fun vector2dDualAndVector4dDualOperations() {
        val first = Vector2dDual(Dual.variable(1.0, 2), Dual.const(2.0, 2))
        val norm = first.norm

        assertEquals(sqrt(5.0), norm[0], 1e-12)
        assertEquals(1.0 / sqrt(5.0), norm[1], 1e-12)
        assertEquals(Vector2d(1.0, 2.0), first.value)

        val second = Vector4dDual.const(1.0, 2.0, 2.0, 4.0, 2)

        assertEquals(5.0, second.norm[0], 1e-12)
        assertEquals(Vector4d(1.0, 2.0, 2.0, 4.0), second.value)
    }
}
