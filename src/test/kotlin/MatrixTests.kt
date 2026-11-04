import org.ageseries.libage.mathematics.Dual
import org.ageseries.libage.mathematics.geometry.Matrix3x3
import org.ageseries.libage.mathematics.geometry.Matrix3x3Dual
import org.ageseries.libage.mathematics.geometry.Matrix4x4
import org.ageseries.libage.mathematics.geometry.Matrix4x4Dual
import org.ageseries.libage.mathematics.geometry.Rotation3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.Vector3dDual
import org.ageseries.libage.mathematics.geometry.Vector4d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class MatrixTests {
    private fun randomMatrix3(random: Random) = Matrix3x3(
        DoubleArray(9) { random.nextDouble(-2.0, 2.0) }
    )

    private fun randomMatrix4(random: Random): Matrix4x4 {
        val values = DoubleArray(16) { random.nextDouble(-2.0, 2.0) }

        for (diagonal in 0 until 4) {
            values[diagonal * 5] += 5.0
        }

        return Matrix4x4(
            values[0], values[1], values[2], values[3],
            values[4], values[5], values[6], values[7],
            values[8], values[9], values[10], values[11],
            values[12], values[13], values[14], values[15]
        )
    }

    @Test
    fun rowMajorConstructorPlacesElementsByRowAndColumn() {
        val matrix = Matrix3x3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)

        assertEquals(Vector3d(1.0, 2.0, 3.0), matrix.r0)
        assertEquals(Vector3d(4.0, 5.0, 6.0), matrix.r1)
        assertEquals(Vector3d(7.0, 8.0, 9.0), matrix.r2)
        assertEquals(Vector3d(1.0, 4.0, 7.0), matrix.c0)
        assertEquals(6.0, matrix[1, 2])
        assertEquals(8.0, matrix[2, 1])
        assertEquals(matrix.c1, matrix.getColumn(1))
        assertEquals(matrix.r2, matrix.getRow(2))
    }

    @Test
    fun outOfRangeAccessThrows() {
        assertThrows<IllegalStateException> { Matrix3x3.identity.getColumn(3) }
        assertThrows<IllegalStateException> { Matrix3x3.identity.getRow(-1) }
        assertThrows<IllegalStateException> { Matrix3x3.identity[3, 0] }
        assertThrows<IllegalStateException> { Matrix4x4.identity.getColumn(4) }
        assertThrows<IllegalStateException> { Matrix4x4.identity[4, 0] }
    }

    @Test
    fun arrayRoundTripIsRowMajor() {
        val array = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)
        val matrix = Matrix3x3(array)

        assertTrue(array.contentEquals(matrix.toArray()))
    }

    @Test
    fun multiplicationByIdentityAndKnownProduct() {
        val matrix = Matrix3x3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0)
        val other = Matrix3x3(0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 2.0)

        assertEquals(matrix, matrix * Matrix3x3.identity)
        assertEquals(matrix, Matrix3x3.identity * matrix)
        assertEquals(Matrix3x3(2.0, 1.0, 6.0, 5.0, 4.0, 12.0, 8.0, 7.0, 20.0), matrix * other)
        assertEquals(Vector3d(14.0, 32.0, 53.0), matrix * Vector3d(1.0, 2.0, 3.0))
    }

    @Test
    fun determinantOfKnownMatrices() {
        assertEquals(1.0, Matrix3x3.identity.determinant, 1e-12)
        assertEquals(24.0, Matrix3x3(2.0, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 4.0).determinant, 1e-12)
        assertEquals(0.0, Matrix3x3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0).determinant, 1e-12)
        assertEquals(-3.0, Matrix3x3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0).determinant, 1e-12)
    }

    @Test
    fun determinantIsMultiplicativeAndTransposeInvariant() {
        val random = Random(21)

        repeat(100) {
            val first = randomMatrix3(random)
            val second = randomMatrix3(random)

            assertEquals(first.determinant * second.determinant, (first * second).determinant, 1e-9)
            assertEquals(first.determinant, first.transpose.determinant, 1e-12)
        }
    }

    @Test
    fun transposeProperties() {
        val random = Random(22)

        repeat(50) {
            val first = randomMatrix3(random)
            val second = randomMatrix3(random)

            assertEquals(first, first.transpose.transpose)
            assertTrue((first * second).transpose.approxEq(second.transpose * first.transpose, 1e-9))
            assertEquals(first.trace, first.transpose.trace, 1e-12)
        }
    }

    @Test
    fun inverseTimesMatrixIsIdentity() {
        val random = Random(23)
        var checked = 0

        while (checked < 100) {
            val matrix = randomMatrix3(random)

            if (kotlin.math.abs(matrix.determinant) > 0.2) {
                assertTrue((matrix * !matrix).approxEq(Matrix3x3.identity, 1e-9))
                assertTrue((!matrix * matrix).approxEq(Matrix3x3.identity, 1e-9))
                assertTrue((!(!matrix)).approxEq(matrix, 1e-8))
                checked++
            }
        }
    }

    @Test
    fun inverseOfSingularMatrixHasNonFiniteEntries() {
        val inverse = !Matrix3x3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)

        assertTrue(inverse.c0.isNaN || inverse.c0.isInfinity)
    }

    @Test
    fun matrixAdditionTraceAndNorm() {
        val matrix = Matrix3x3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)

        assertEquals(15.0, matrix.trace, 1e-12)
        assertEquals(285.0, matrix.normFrobeniusSqr, 1e-12)
        assertEquals(Matrix3x3.identity * 2.0, Matrix3x3.identity + Matrix3x3.identity)
        assertEquals(Matrix3x3.identity * 0.0, Matrix3x3.identity - Matrix3x3.identity)
    }

    @Test
    fun orthogonalityDetectionFollowsRotationsAndReflections() {
        val rotation = Rotation3d.fromAxisAngle(Vector3d(1.0, 2.0, 3.0), 0.7)()
        val reflection = Matrix3x3(-1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val scaling = Matrix3x3.identity * 2.0

        assertTrue(rotation.isOrthogonal)
        assertTrue(rotation.isSpecialOrthogonal)
        assertTrue(reflection.isOrthogonal)
        assertFalse(reflection.isSpecialOrthogonal)
        assertFalse(scaling.isOrthogonal)
    }

    @Test
    fun matrix4x4IdentityAndDeterminants() {
        assertEquals(1.0, Matrix4x4.identity.determinant, 1e-12)

        val diagonal = Matrix4x4(
            2.0, 0.0, 0.0, 0.0,
            0.0, 3.0, 0.0, 0.0,
            0.0, 0.0, 4.0, 0.0,
            0.0, 0.0, 0.0, 5.0
        )

        assertEquals(120.0, diagonal.determinant, 1e-12)

        val swapped = Matrix4x4(
            0.0, 1.0, 0.0, 0.0,
            1.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        )

        assertEquals(-1.0, swapped.determinant, 1e-12)
    }

    @Test
    fun matrix4x4DeterminantIsMultiplicative() {
        val random = Random(24)

        repeat(50) {
            val first = randomMatrix4(random)
            val second = randomMatrix4(random)

            val expected = first.determinant * second.determinant

            assertEquals(expected, (first * second).determinant, 1e-9 * kotlin.math.abs(expected))
            assertEquals(first.determinant, first.transpose.determinant, 1e-6)
        }
    }

    @Test
    fun matrix4x4InverseTimesMatrixIsIdentity() {
        val random = Random(25)

        repeat(100) {
            val matrix = randomMatrix4(random)

            assertTrue((matrix * !matrix).approxEq(Matrix4x4.identity, 1e-9))
            assertTrue((!matrix * matrix).approxEq(Matrix4x4.identity, 1e-9))
        }
    }

    @Test
    fun matrix4x4InverseOfNonSymmetricTranslationScaleMatrix() {
        val matrix = Matrix4x4(
            2.0, 0.0, 0.0, 1.0,
            0.0, 4.0, 0.0, 2.0,
            0.0, 0.0, 8.0, 3.0,
            0.0, 0.0, 0.0, 1.0
        )

        val expected = Matrix4x4(
            0.5, 0.0, 0.0, -0.5,
            0.0, 0.25, 0.0, -0.5,
            0.0, 0.0, 0.125, -0.375,
            0.0, 0.0, 0.0, 1.0
        )

        assertTrue((!matrix).approxEq(expected, 1e-12))
    }

    @Test
    fun matrix4x4VectorMultiplicationAppliesTranslationOnlyToPoints() {
        val matrix = Matrix4x4(
            1.0, 0.0, 0.0, 10.0,
            0.0, 1.0, 0.0, 20.0,
            0.0, 0.0, 1.0, 30.0,
            0.0, 0.0, 0.0, 1.0
        )

        assertEquals(Vector3d(11.0, 22.0, 33.0), matrix * Vector3d(1.0, 2.0, 3.0))
        assertEquals(Vector4d(1.0, 2.0, 3.0, 0.0), matrix * Vector4d(1.0, 2.0, 3.0, 0.0))
        assertEquals(Vector4d(11.0, 22.0, 33.0, 1.0), matrix * Vector4d(1.0, 2.0, 3.0, 1.0))
    }

    @Test
    fun matrix4x4MinorsAndCofactorsOfKnownMatrix() {
        val matrix = Matrix4x4(
            1.0, 2.0, 3.0, 4.0,
            5.0, 6.0, 7.0, 8.0,
            9.0, 10.0, 11.0, 12.0,
            13.0, 14.0, 15.0, 17.0
        )

        assertEquals(6.0 * (11.0 * 17.0 - 12.0 * 15.0) - 7.0 * (10.0 * 17.0 - 12.0 * 14.0) +
            8.0 * (10.0 * 15.0 - 11.0 * 14.0), matrix.minor(0, 0), 1e-9)

        assertEquals(matrix.minor(1, 0), -matrix.cofactor(1, 0), 1e-9)
        assertEquals(matrix.minor(1, 1), matrix.cofactor(1, 1), 1e-9)

        val rowOneColumnTwoMinor = Matrix3x3(
            1.0, 2.0, 4.0,
            9.0, 10.0, 12.0,
            13.0, 14.0, 17.0
        ).determinant

        assertEquals(rowOneColumnTwoMinor, matrix.minor(2, 1), 1e-9)
    }

    @Test
    fun rotationMatrixAndPoseMatrixAreConsistent() {
        val rotation = Rotation3d.fromAxisAngle(Vector3d(0.0, 0.0, 1.0), Math.PI / 2.0)
        val matrix = rotation()

        assertTrue((matrix * Vector3d.unitX).approxEq(Vector3d.unitY, 1e-12))
        assertTrue((matrix * Vector3d.unitY).approxEq(-Vector3d.unitX, 1e-12))
    }

    @Test
    fun dualMatrixInversesMatchRealInverses() {
        val random = Random(26)

        repeat(20) {
            val matrix3 = randomMatrix3(random)

            if (kotlin.math.abs(matrix3.determinant) > 0.2) {
                assertTrue((!Matrix3x3Dual.const(matrix3, 2)).value.approxEq(!matrix3, 1e-9))
            }

            val matrix4 = randomMatrix4(random)

            assertTrue((!Matrix4x4Dual.const(matrix4, 2)).value.approxEq(!matrix4, 1e-9))
        }
    }

    @Test
    fun dualMatrixMinorsAndDeterminantMatchRealMatrix() {
        val random = Random(27)
        val matrix = randomMatrix4(random)
        val dualMatrix = Matrix4x4Dual.const(matrix, 1)

        assertEquals(matrix.determinant, dualMatrix.determinant.value, 1e-6)

        for (column in 0 until 4) {
            for (row in 0 until 4) {
                assertEquals(matrix.minor(column, row), dualMatrix.minor(column, row).value, 1e-6)
                assertEquals(matrix.cofactor(column, row), dualMatrix.cofactor(column, row).value, 1e-6)
            }
        }
    }

    @Test
    fun dualMatrixDerivativeOfScaledIdentityDeterminant() {
        val scale = Dual.variable(2.0, 2)
        val zero = Dual.const(0.0, 2)
        val matrix = Matrix3x3Dual(
            scale, zero, zero,
            zero, scale, zero,
            zero, zero, scale
        )

        assertEquals(8.0, matrix.determinant[0], 1e-12)
        assertEquals(12.0, matrix.determinant[1], 1e-12)
    }

    @Test
    fun dualMatrixTimesVectorMatchesRealProduct() {
        val random = Random(28)
        val matrix = randomMatrix3(random)
        val vector = Vector3d(1.0, -2.0, 0.5)
        val product = Matrix3x3Dual.const(matrix, 2) * Vector3dDual.const(vector, 2)

        assertTrue(product.value.approxEq(matrix * vector, 1e-12))
    }
}
