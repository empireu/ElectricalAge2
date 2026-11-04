import org.ageseries.libage.mathematics.Dual
import org.ageseries.libage.mathematics.geometry.CoordinateSystem
import org.ageseries.libage.mathematics.geometry.Matrix3x3
import org.ageseries.libage.mathematics.geometry.Matrix4x4
import org.ageseries.libage.mathematics.geometry.Pose2d
import org.ageseries.libage.mathematics.geometry.Pose3d
import org.ageseries.libage.mathematics.geometry.Pose3dBuilder
import org.ageseries.libage.mathematics.geometry.Pose3dDual
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Rotation3d
import org.ageseries.libage.mathematics.geometry.Rotation3dBuilder
import org.ageseries.libage.mathematics.geometry.Rotation3dDual
import org.ageseries.libage.mathematics.geometry.Twist2dIncr
import org.ageseries.libage.mathematics.geometry.Twist3dIncr
import org.ageseries.libage.mathematics.geometry.Vector2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.Vector3dDual
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

class TransformTests {
    private fun randomUnitVector(random: Random): Vector3d {
        while (true) {
            val candidate = Vector3d(
                random.nextDouble(-1.0, 1.0),
                random.nextDouble(-1.0, 1.0),
                random.nextDouble(-1.0, 1.0)
            )

            if (candidate.norm > 0.1) {
                return candidate.normalized()
            }
        }
    }

    private fun randomRotation(random: Random) =
        Rotation3d.fromAxisAngle(randomUnitVector(random), random.nextDouble(-PI, PI))

    private fun randomPose(random: Random) = Pose3d(
        Vector3d(random.nextDouble(-5.0, 5.0), random.nextDouble(-5.0, 5.0), random.nextDouble(-5.0, 5.0)),
        randomRotation(random)
    )

    private fun assertSameRotation(expected: Rotation3d, actual: Rotation3d, message: String = "") {
        val direct = expected.approxEqComponentWise(actual, 1e-9)
        val negated = expected.approxEqComponentWise(-actual, 1e-9)

        assertTrue(direct || negated, "$message expected $expected but was $actual")
    }

    @Test
    fun rotation2dRotatesVectorsCounterClockwise() {
        val quarterTurn = Rotation2d.exp(PI / 2.0)

        assertTrue((quarterTurn * Vector2d.unitX).approxEq(Vector2d.unitY, 1e-12))
        assertTrue((quarterTurn * Vector2d.unitY).approxEq(-Vector2d.unitX, 1e-12))
        assertEquals(PI / 2.0, quarterTurn.ln(), 1e-12)
    }

    @Test
    fun rotation2dCompositionAddsAnglesAndInverseCancels() {
        val first = Rotation2d.exp(0.4)
        val second = Rotation2d.exp(1.1)

        assertEquals(1.5, (first * second).ln(), 1e-12)
        assertTrue((first * !first).approxEq(Rotation2d.identity))
        assertEquals(0.7, (second / first).ln(), 1e-12)
        assertEquals(0.7, second - first, 1e-12)
        assertEquals(1.6, (first + 1.2).ln(), 1e-12)
        assertEquals(0.2, first.scaled(0.5).ln(), 1e-12)
        assertTrue(Rotation2d.dir(Vector2d(0.0, 5.0)).approxEq(Rotation2d.exp(PI / 2.0)))
    }

    @Test
    fun rotation2dInterpolationHitsEndpointsAndTakesShortestPath() {
        val start = Rotation2d.exp(0.2)
        val end = Rotation2d.exp(1.0)

        assertTrue(Rotation2d.interpolate(start, end, 0.0).approxEq(start))
        assertTrue(Rotation2d.interpolate(start, end, 1.0).approxEq(end))
        assertEquals(0.6, Rotation2d.interpolate(start, end, 0.5).ln(), 1e-12)

        val wrapStart = Rotation2d.exp(PI - 0.1)
        val wrapEnd = Rotation2d.exp(-PI + 0.1)

        assertEquals(PI, abs(Rotation2d.interpolate(wrapStart, wrapEnd, 0.5).ln()), 1e-9)
    }

    @Test
    fun pose2dCompositionAndInverse() {
        val pose = Pose2d(1.0, 2.0, 0.5)
        val other = Pose2d(-3.0, 0.5, -1.2)

        assertTrue((pose * !pose).approxEq(Pose2d.identity))
        assertTrue((!pose * pose).approxEq(Pose2d.identity))
        assertTrue(((pose * other) / pose).approxEq(other))

        val point = Vector2d(0.3, -0.7)

        assertTrue(((pose * other) * point).approxEq(pose * (other * point)))
        assertTrue((!pose * (pose * point)).approxEq(point))
    }

    @Test
    fun pose2dExpAndLnAreInverses() {
        val random = Random(31)

        repeat(200) {
            val twist = Twist2dIncr(
                Vector2d(random.nextDouble(-3.0, 3.0), random.nextDouble(-3.0, 3.0)),
                random.nextDouble(-3.0, 3.0)
            )

            val recovered = Pose2d.exp(twist).ln()

            assertTrue(recovered.trIncr.approxEq(twist.trIncr, 1e-9), "$twist vs $recovered")
            assertEquals(twist.rotIncr, recovered.rotIncr, 1e-9)
        }
    }

    @Test
    fun pose2dExpOfTinyAndZeroRotationIsPureTranslation() {
        val translation = Pose2d.exp(Twist2dIncr(Vector2d(1.0, 2.0), 0.0))

        assertTrue(translation.translation.approxEq(Vector2d(1.0, 2.0)))
        assertTrue(translation.rotation.approxEq(Rotation2d.identity))

        val nearlyZero = Pose2d.exp(Twist2dIncr(Vector2d(1.0, 2.0), 1e-12))

        assertTrue(nearlyZero.translation.approxEq(Vector2d(1.0, 2.0), 1e-9))
    }

    @Test
    fun pose2dExpOfQuarterTurnArcFollowsCircle() {
        val pose = Pose2d.exp(Twist2dIncr(Vector2d(PI / 2.0, 0.0), PI / 2.0))

        assertTrue(pose.translation.approxEq(Vector2d(1.0, 1.0), 1e-12))
        assertEquals(PI / 2.0, pose.rotation.ln(), 1e-12)
    }

    @Test
    fun rotation3dAxisAngleRotatesBasisVectors() {
        val about = Rotation3d.fromAxisAngle(Vector3d.unitZ, PI / 2.0)

        assertTrue((about * Vector3d.unitX).approxEq(Vector3d.unitY, 1e-12))
        assertTrue((about * Vector3d.unitY).approxEq(-Vector3d.unitX, 1e-12))
        assertTrue((about * Vector3d.unitZ).approxEq(Vector3d.unitZ, 1e-12))
    }

    @Test
    fun rotation3dIdentityAndZeroExponent() {
        assertEquals(Rotation3d.identity, Rotation3d.exp(Vector3d.zero))
        assertTrue((Rotation3d.identity * Vector3d(1.0, 2.0, 3.0)).approxEq(Vector3d(1.0, 2.0, 3.0), 1e-12))
        assertTrue(Rotation3d.identity.ln().approxEq(Vector3d.zero, 1e-12))
    }

    @Test
    fun rotation3dPreservesLengthAndAngles() {
        val random = Random(32)

        repeat(200) {
            val rotation = randomRotation(random)
            val first = randomUnitVector(random) * random.nextDouble(0.1, 3.0)
            val second = randomUnitVector(random) * random.nextDouble(0.1, 3.0)

            assertEquals(first.norm, (rotation * first).norm, 1e-9)
            assertEquals(first dot second, (rotation * first) dot (rotation * second), 1e-9)
            assertEquals(1.0, rotation.norm, 1e-9)
        }
    }

    @Test
    fun rotation3dCompositionIsAssociativeWithVectorApplication() {
        val random = Random(33)

        repeat(200) {
            val first = randomRotation(random)
            val second = randomRotation(random)
            val vector = randomUnitVector(random) * 2.5

            assertTrue(((first * second) * vector).approxEq(first * (second * vector), 1e-9))
        }
    }

    @Test
    fun rotation3dInverseCancels() {
        val random = Random(34)

        repeat(100) {
            val rotation = randomRotation(random)
            val vector = randomUnitVector(random)

            assertSameRotation(Rotation3d.identity, rotation * rotation.inverse)
            assertSameRotation(Rotation3d.identity, !rotation * rotation)
            assertTrue((rotation.inverse * (rotation * vector)).approxEq(vector, 1e-9))
            assertSameRotation(rotation, (rotation * rotation) / rotation)
        }
    }

    @Test
    fun rotation3dMatrixAgreesWithVectorRotation() {
        val random = Random(35)

        repeat(100) {
            val rotation = randomRotation(random)
            val vector = randomUnitVector(random) * 3.0

            assertTrue((rotation() * vector).approxEq(rotation * vector, 1e-9))
            assertTrue(rotation().isSpecialOrthogonal)
        }
    }

    @Test
    fun rotation3dMatrixRoundTripCoversAllConversionBranches() {
        val random = Random(36)
        val special = listOf(
            Rotation3d.identity,
            Rotation3d.fromAxisAngle(Vector3d.unitX, PI),
            Rotation3d.fromAxisAngle(Vector3d.unitY, PI),
            Rotation3d.fromAxisAngle(Vector3d.unitZ, PI),
            Rotation3d.fromAxisAngle(Vector3d.unitZ, PI / 2.0),
            Rotation3d.fromAxisAngle(Vector3d(1.0, 1.0, 0.0), PI),
            Rotation3d.fromAxisAngle(Vector3d(1.0, -1.0, 0.0), PI)
        )

        (special + List(500) { randomRotation(random) }).forEach {
            assertSameRotation(it, Rotation3d.fromRotationMatrix(it()), "matrix round trip")
        }
    }

    @Test
    fun rotation3dLnAndExpAreInverses() {
        val random = Random(37)

        repeat(300) {
            val rotationVector = randomUnitVector(random) * random.nextDouble(0.0, PI * 0.99)

            assertTrue(Rotation3d.exp(rotationVector).ln().approxEq(rotationVector, 1e-9))
        }

        val tiny = Vector3d(1e-12, 2e-12, -1e-12)

        assertTrue(Rotation3d.exp(tiny).ln().approxEq(tiny, 1e-15))
    }

    @Test
    fun rotation3dLnTakesShortestRepresentationForLargeAngles() {
        val large = Rotation3d.fromAxisAngle(Vector3d.unitZ, 1.5 * PI)

        assertTrue(large.ln().approxEq(Vector3d(0.0, 0.0, -PI / 2.0), 1e-9))
        assertTrue((-large).ln().approxEq(Vector3d(0.0, 0.0, -PI / 2.0), 1e-9))
    }

    @Test
    fun rotation3dAxisAngleRoundTrip() {
        val axisAngle = Rotation3d.fromAxisAngle(Vector3d(0.0, 3.0, 0.0), 0.8).toAxisAngle()

        assertTrue(axisAngle.axis.approxEq(Vector3d.unitY, 1e-12))
        assertEquals(0.8, axisAngle.angle, 1e-12)
        assertEquals(0.0, Rotation3d.identity.toAxisAngle().angle)
        assertSameRotation(Rotation3d.fromAxisAngle(axisAngle), Rotation3d.fromAxisAngle(Vector3d.unitY, 0.8))
    }

    @Test
    fun rotation3dInterpolationHitsEndpointsAndMidpointHalvesAngle() {
        val start = Rotation3d.fromAxisAngle(Vector3d.unitX, 0.2)
        val end = Rotation3d.fromAxisAngle(Vector3d.unitX, 1.0)

        assertSameRotation(start, Rotation3d.interpolate(start, end, 0.0))
        assertSameRotation(end, Rotation3d.interpolate(start, end, 1.0))
        assertSameRotation(Rotation3d.fromAxisAngle(Vector3d.unitX, 0.6), Rotation3d.interpolate(start, end, 0.5))
    }

    @Test
    fun rotation3dScalingByInvokeSplitsRotation() {
        val random = Random(38)

        repeat(50) {
            val rotation = randomRotation(random)
            val half = rotation(0.5)

            assertSameRotation(rotation, half * half)
        }
    }

    @Test
    fun rotation3dPlusAngleAddsToRotationAngle() {
        val rotation = Rotation3d.fromAxisAngle(Vector3d.unitY, 0.5)

        assertSameRotation(Rotation3d.fromAxisAngle(Vector3d.unitY, 0.9), rotation + 0.4)
        assertEquals(Rotation3d.identity, Rotation3d.identity + 0.4)
    }

    @Test
    fun rotation3dNormalizationProducesUnitQuaternion() {
        val unnormalized = Rotation3d(1.0, 2.0, 3.0, 4.0)

        assertEquals(1.0, unnormalized.normalized().norm, 1e-12)
        assertEquals(1.0, Rotation3d(0.0, 0.0, 0.0, 1.0000000001).normalized().norm, 1e-9)
    }

    @Test
    fun rotationFromTwoVectorsMapsFirstOntoSecond() {
        val random = Random(39)

        repeat(300) {
            val from = randomUnitVector(random)
            val to = randomUnitVector(random)
            val rotation = Rotation3d.createFromTwoVectors(from, to)

            assertTrue((rotation * from).approxEq(to, 1e-9), "from $from to $to")
            assertEquals(1.0, rotation.norm, 1e-9)
        }
    }

    @Test
    fun rotationFromTwoVectorsHandlesParallelAndAxisAlignedOpposites() {
        assertSameRotation(Rotation3d.identity, Rotation3d.createFromTwoVectors(Vector3d.unitX, Vector3d.unitX))

        listOf(Vector3d.unitX, Vector3d.unitY, Vector3d.unitZ, -Vector3d.unitX, -Vector3d.unitY, -Vector3d.unitZ)
            .forEach {
                val rotation = Rotation3d.createFromTwoVectors(it, -it)

                assertTrue((rotation * it).approxEq(-it, 1e-9), "opposite of $it")
                assertEquals(1.0, rotation.norm, 1e-9)
            }
    }

    @Test
    @Disabled("Bug: createFromTwoVectors returns a non-unit quaternion for opposite vectors that are not axis-aligned.")
    fun rotationFromTwoVectorsHandlesGenericOppositeVectors() {
        val from = Vector3d(1.0, 1.0, 1.0).normalized()
        val rotation = Rotation3d.createFromTwoVectors(from, -from)

        assertEquals(1.0, rotation.norm, 1e-9)
        assertTrue((rotation * from).approxEq(-from, 1e-9))
    }

    @Test
    fun rotation3dBuilderAppliesRotationsInCodeOrder() {
        val builder = Rotation3dBuilder()
        builder.rotateX(PI / 2.0)
        builder.rotateZ(PI / 2.0)

        val result = builder.rotation * Vector3d.unitY

        assertTrue(result.approxEq(Vector3d.unitZ, 1e-12), "result $result")
        assertSameRotation(Rotation3d.identity, builder.identity().rotation)
    }

    @Test
    fun pose3dInverseAndCompositionAreConsistent() {
        val random = Random(40)

        repeat(200) {
            val first = randomPose(random)
            val second = randomPose(random)
            val point = randomUnitVector(random) * 4.0

            assertTrue((first * first.inverse).approxEq(Pose3d.identity, 1e-9))
            assertTrue(((first * second) * point).approxEq(first * (second * point), 1e-9))
            assertTrue((first.inverse * (first * point)).approxEq(point, 1e-9))
            assertTrue(((first * second) / first).approxEq(second, 1e-9))
        }
    }

    @Test
    fun pose3dMatrixAgreesWithPoseApplicationAndRoundTrips() {
        val random = Random(41)

        repeat(100) {
            val pose = randomPose(random)
            val point = randomUnitVector(random) * 3.0

            assertTrue((pose() * point).approxEq(pose * point, 1e-9))
            assertTrue(Pose3d.fromMatrix(pose()).approxEq(pose, 1e-9))
        }

        assertTrue(Pose3d.identity().approxEq(Matrix4x4.identity))
    }

    @Test
    fun pose3dExpAndLnAreInverses() {
        val random = Random(42)

        repeat(200) {
            val twist = Twist3dIncr(
                randomUnitVector(random) * random.nextDouble(0.0, 4.0),
                randomUnitVector(random) * random.nextDouble(0.0, 3.0)
            )

            val recovered = Pose3d.exp(twist).ln()

            assertTrue(recovered.trIncr.approxEq(twist.trIncr, 1e-7), "$twist vs $recovered")
            assertTrue(recovered.rotIncr.approxEq(twist.rotIncr, 1e-7))
        }
    }

    @Test
    fun pose3dExpOfPureTranslationAndTinyRotation() {
        val translation = Vector3d(1.0, -2.0, 3.0)

        assertTrue(Pose3d.exp(Twist3dIncr(translation, Vector3d.zero)).translation.approxEq(translation, 1e-12))
        val tinyRotation = Pose3d.exp(Twist3dIncr(translation, Vector3d(1e-9, 0.0, 0.0)))

        assertTrue(tinyRotation.translation.approxEq(translation, 1e-6))
        assertTrue(Pose3d(translation, Rotation3d.identity).ln().trIncr.approxEq(translation, 1e-12))
    }

    @Test
    fun pose3dPlusAndMinusDisplacement() {
        val pose = Pose3d(Vector3d(1.0, 2.0, 3.0), Rotation3d.fromAxisAngle(Vector3d.unitX, 0.3))
        val displacement = Vector3d(0.5, 0.5, 0.5)

        assertEquals(Vector3d(1.5, 2.5, 3.5), (pose + displacement).translation)
        assertEquals(pose, (pose + displacement) - displacement)
        assertEquals(pose.rotation, (pose + displacement).rotation)
    }

    @Test
    fun pose3dPlusRotationComposesRotationOnTheLeft() {
        val pose = Pose3d(Vector3d(1.0, 2.0, 3.0), Rotation3d.fromAxisAngle(Vector3d.unitX, 0.3))
        val extra = Rotation3d.fromAxisAngle(Vector3d.unitZ, 0.9)
        val result = pose + extra

        assertEquals(pose.translation, result.translation)
        assertSameRotation(extra * pose.rotation, result.rotation)
    }

    @Test
    @Disabled("Bug: Pose3d.minus(Rotation3d) does not invert plus(Rotation3d), so (pose + r) - r changes the rotation.")
    fun pose3dMinusRotationUndoesPlusRotation() {
        val pose = Pose3d(Vector3d(1.0, 2.0, 3.0), Rotation3d.fromAxisAngle(Vector3d.unitX, 0.3))
        val extra = Rotation3d.fromAxisAngle(Vector3d.unitZ, 0.9)

        assertSameRotation(pose.rotation, ((pose + extra) - extra).rotation)
    }

    @Test
    fun pose3dBuilderAccumulatesTransformsInOrder() {
        val builder = Pose3dBuilder()
        builder.translate(1.0, 0.0, 0.0)
        builder.translate(Vector3d(0.0, 2.0, 0.0))

        assertEquals(Vector3d(1.0, 2.0, 0.0), builder.pose.translation)

        builder.rotateBy(Rotation3d.fromAxisAngle(Vector3d.unitZ, PI / 2.0))

        assertEquals(Vector3d(1.0, 2.0, 0.0), builder.pose.translation)

        builder.transform(Pose3d(Vector3d(10.0, 0.0, 0.0), Rotation3d.identity))

        assertEquals(Vector3d(11.0, 2.0, 0.0), builder.pose.translation)

        builder.identity()

        assertEquals(Pose3d.identity, builder.pose)
    }

    @Test
    fun rotation3dDualConstantsMatchRealRotation() {
        val random = Random(43)

        repeat(50) {
            val rotation = randomRotation(random)
            val dualRotation = Rotation3dDual(
                Dual.const(rotation.x, 2),
                Dual.const(rotation.y, 2),
                Dual.const(rotation.z, 2),
                Dual.const(rotation.w, 2)
            )

            val vector = randomUnitVector(random)
            val rotated = dualRotation * Vector3dDual.const(vector, 2)

            assertTrue(rotated.value.approxEq(rotation * vector, 1e-9))
            assertTrue(dualRotation.ln().value.approxEq(rotation.ln(), 1e-9))
            assertTrue(dualRotation.value.approxEqComponentWise(rotation, 1e-12))
            assertTrue((dualRotation * dualRotation.inverse).value.approxEq(Rotation3d.identity, 1e-9))
        }
    }

    @Test
    fun rotation3dDualAngularVelocityOfConstantRateRotation() {
        val angle = Dual.variable(0.5, 2)
        val rotation = Rotation3dDual.exp(Vector3dDual(Dual.const(0.0, 2), Dual.const(0.0, 2), angle))
        val velocity = rotation.angularVelocity

        assertTrue(velocity.value.approxEq(Vector3d(0.0, 0.0, 1.0), 1e-9), "angular velocity ${velocity.value}")
    }

    @Test
    fun pose3dDualConstantMatchesRealPoseApplication() {
        val random = Random(44)
        val pose = randomPose(random)
        val dualPose = Pose3dDual(
            Vector3dDual.const(pose.translation, 2),
            Rotation3dDual(
                Dual.const(pose.rotation.x, 2),
                Dual.const(pose.rotation.y, 2),
                Dual.const(pose.rotation.z, 2),
                Dual.const(pose.rotation.w, 2)
            )
        )

        val point = randomUnitVector(random)

        assertTrue((dualPose * Vector3dDual.const(point, 2)).value.approxEq(pose * point, 1e-9))
        assertTrue((dualPose * dualPose.inverse).value.approxEq(Pose3d.identity, 1e-9))
        assertTrue(dualPose().value.approxEq(pose(), 1e-9))
    }

    @Test
    fun coordinateSystemsRequireOrthogonalTransforms() {
        assertThrows<IllegalArgumentException> { CoordinateSystem(Matrix3x3.identity * 2.0) }
        assertTrue(CoordinateSystem.minecraft.transform.isOrthogonal)
    }

    @Test
    fun coordinateSystemConversionSwapsYAndZ() {
        val conversion = CoordinateSystem.rfu..CoordinateSystem.minecraft

        assertEquals(Vector3d(1.0, 3.0, 2.0), conversion * Vector3d(1.0, 2.0, 3.0))
        val reverse = CoordinateSystem.minecraft..CoordinateSystem.rfu

        assertTrue((reverse * Vector3d(1.0, 3.0, 2.0)).approxEq(Vector3d(1.0, 2.0, 3.0)))
        assertFalse(conversion.isSpecialOrthogonal)
    }
}
