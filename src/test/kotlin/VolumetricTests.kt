import org.ageseries.libage.mathematics.geometry.BoundingBox2d
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.BoundingSphere3d
import org.ageseries.libage.mathematics.geometry.ContainmentMode
import org.ageseries.libage.mathematics.geometry.Cylinder3d
import org.ageseries.libage.mathematics.geometry.Line3d
import org.ageseries.libage.mathematics.geometry.OrientedBoundingBox3d
import org.ageseries.libage.mathematics.geometry.Plane3d
import org.ageseries.libage.mathematics.geometry.PlaneIntersectionType
import org.ageseries.libage.mathematics.geometry.Pose3d
import org.ageseries.libage.mathematics.geometry.Ray3d
import org.ageseries.libage.mathematics.geometry.Rotation3d
import org.ageseries.libage.mathematics.geometry.Vector2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.dda
import org.ageseries.libage.mathematics.geometry.n
import org.ageseries.libage.mathematics.geometry.u
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class VolumetricTests {
    private fun randomVector(random: Random, range: Double) = Vector3d(
        random.nextDouble(-range, range),
        random.nextDouble(-range, range),
        random.nextDouble(-range, range)
    )

    private fun randomUnitVector(random: Random): Vector3d {
        while (true) {
            val candidate = randomVector(random, 1.0)

            if (candidate.norm > 0.1) {
                return candidate.normalized()
            }
        }
    }

    private fun randomRay(random: Random, target: Vector3d): Ray3d {
        val origin = randomVector(random, 6.0)

        if (random.nextBoolean()) {
            return Ray3d(origin, randomUnitVector(random))
        }

        val jitteredTarget = target + randomVector(random, 1.0)

        return Ray3d(origin, (jitteredTarget - origin).normalized())
    }

    private fun randomRotation(random: Random) =
        Rotation3d.fromAxisAngle(randomUnitVector(random), random.nextDouble(-PI, PI))

    private fun randomBox(random: Random, spread: Double, minimumSize: Double, maximumSize: Double) =
        BoundingBox3d.fromCenterSize(
            randomVector(random, spread),
            Vector3d(
                random.nextDouble(minimumSize, maximumSize),
                random.nextDouble(minimumSize, maximumSize),
                random.nextDouble(minimumSize, maximumSize)
            )
        )

    private fun randomOrientedBox(random: Random, spread: Double, minimumHalf: Double, maximumHalf: Double) =
        OrientedBoundingBox3d(
            randomVector(random, spread),
            randomRotation(random),
            Vector3d(
                random.nextDouble(minimumHalf, maximumHalf),
                random.nextDouble(minimumHalf, maximumHalf),
                random.nextDouble(minimumHalf, maximumHalf)
            )
        )

    private fun corners(box: OrientedBoundingBox3d): List<Vector3d> {
        val result = ArrayList<Vector3d>()
        box.forEachCorner { result.add(it) }
        return result
    }

    private fun axes(box: OrientedBoundingBox3d) = listOf(
        box.transform.rotation * Vector3d.unitX,
        box.transform.rotation * Vector3d.unitY,
        box.transform.rotation * Vector3d.unitZ
    )

    private fun referenceSeparated(first: OrientedBoundingBox3d, second: OrientedBoundingBox3d): Boolean {
        val firstAxes = axes(first)
        val secondAxes = axes(second)
        val candidates = ArrayList<Vector3d>(firstAxes + secondAxes)

        for (firstAxis in firstAxes) {
            for (secondAxis in secondAxes) {
                val cross = firstAxis cross secondAxis

                if (cross.norm > 1e-9) {
                    candidates.add(cross.normalized())
                }
            }
        }

        val firstCorners = corners(first)
        val secondCorners = corners(second)

        for (axis in candidates) {
            val firstProjection = firstCorners.map { it dot axis }
            val secondProjection = secondCorners.map { it dot axis }

            if (firstProjection.max() < secondProjection.min() || secondProjection.max() < firstProjection.min()) {
                return true
            }
        }

        return false
    }

    @Test
    fun boundingBoxMeasures() {
        val box = BoundingBox3d(Vector3d(1.0, 2.0, 3.0), Vector3d(2.0, 4.0, 6.0))

        assertEquals(1.0, box.width, 1e-12)
        assertEquals(2.0, box.height, 1e-12)
        assertEquals(3.0, box.depth, 1e-12)
        assertEquals(6.0, box.capacity, 1e-12)
        assertEquals(22.0, box.surface, 1e-12)
        assertEquals(Vector3d(1.5, 3.0, 4.5), box.center)
        assertEquals(Vector3d(1.0, 2.0, 3.0), box.size)
        assertEquals(Vector3d(0.5, 1.0, 1.5), box.halfSize)
        assertFalse(box.isZero)
        assertTrue(BoundingBox3d.zero.isZero)
    }

    @Test
    fun boundingBoxValidity() {
        assertTrue(BoundingBox3d(Vector3d.zero, Vector3d.one).isValid)
        assertTrue(BoundingBox3d.zero.isValid)
        assertFalse(BoundingBox3d(Vector3d.one, Vector3d.zero).isValid)
        assertFalse(BoundingBox3d(Vector3d.zero, Vector3d(Double.POSITIVE_INFINITY, 1.0, 1.0)).isValid)
        assertFalse(BoundingBox3d(Vector3d(Double.NaN, 0.0, 0.0), Vector3d.one).isValid)
        assertTrue(BoundingBox3d(Vector3d(Double.NaN, 0.0, 0.0), Vector3d.one).isNaN)
    }

    @Test
    fun boundingBoxUnionAndIntersection() {
        val first = BoundingBox3d(Vector3d(0.0, 0.0, 0.0), Vector3d(2.0, 2.0, 2.0))
        val second = BoundingBox3d(Vector3d(1.0, 1.0, 1.0), Vector3d(3.0, 3.0, 3.0))
        val far = BoundingBox3d(Vector3d(10.0, 10.0, 10.0), Vector3d(11.0, 11.0, 11.0))

        assertEquals(BoundingBox3d(Vector3d.zero, Vector3d(3.0, 3.0, 3.0)), first u second)
        assertEquals(BoundingBox3d(Vector3d.one, Vector3d(2.0, 2.0, 2.0)), first n second)
        assertEquals(BoundingBox3d.zero, first n far)
        assertTrue(first intersectsWith second)
        assertTrue(second intersectsWith first)
        assertFalse(first intersectsWith far)
        assertEquals(first u second, second u first)
    }

    @Test
    fun touchingBoxesDoNotIntersect() {
        val first = BoundingBox3d(Vector3d.zero, Vector3d.one)
        val second = BoundingBox3d(Vector3d(1.0, 0.0, 0.0), Vector3d(2.0, 1.0, 1.0))

        assertFalse(first intersectsWith second)
        assertEquals(ContainmentMode.Disjoint, first.evaluateContainment(second))
    }

    @Test
    fun boundingBoxContainmentModes() {
        val outer = BoundingBox3d(Vector3d(-5.0, -5.0, -5.0), Vector3d(5.0, 5.0, 5.0))
        val inner = BoundingBox3d(Vector3d(-1.0, -1.0, -1.0), Vector3d(1.0, 1.0, 1.0))
        val crossing = BoundingBox3d(Vector3d(4.0, 0.0, 0.0), Vector3d(6.0, 1.0, 1.0))
        val far = BoundingBox3d(Vector3d(10.0, 10.0, 10.0), Vector3d(11.0, 11.0, 11.0))

        assertEquals(ContainmentMode.ContainedFully, outer.evaluateContainment(inner))
        assertEquals(ContainmentMode.Intersected, inner.evaluateContainment(outer))
        assertEquals(ContainmentMode.Intersected, outer.evaluateContainment(crossing))
        assertEquals(ContainmentMode.Disjoint, outer.evaluateContainment(far))
    }

    @Test
    fun boundingBoxContainsPointsStrictly() {
        val box = BoundingBox3d(Vector3d.zero, Vector3d.one)

        assertTrue(box.contains(Vector3d(0.5, 0.5, 0.5)))
        assertFalse(box.contains(Vector3d(0.0, 0.5, 0.5)))
        assertFalse(box.contains(Vector3d(1.5, 0.5, 0.5)))
    }

    @Test
    fun boundingBoxInflationAndFactories() {
        val box = BoundingBox3d(Vector3d.zero, Vector3d(2.0, 4.0, 6.0))

        assertEquals(BoundingBox3d(Vector3d(-1.0, -1.0, -1.0), Vector3d(3.0, 5.0, 7.0)), box.inflated(1.0))
        val percentInflated = box.inflatedBy(0.1)

        assertTrue(percentInflated.min.approxEq(Vector3d(-0.2, -0.4, -0.6), 1e-12))
        assertTrue(percentInflated.max.approxEq(Vector3d(2.2, 4.4, 6.6), 1e-12))

        assertEquals(
            BoundingBox3d(Vector3d(-1.0, -2.0, -3.0), Vector3d(1.0, 2.0, 3.0)),
            BoundingBox3d.fromCenterSize(Vector3d.zero, Vector3d(2.0, 4.0, 6.0))
        )

        assertEquals(
            BoundingBox3d(Vector3d(0.5, 0.5, 0.5), Vector3d(1.5, 1.5, 1.5)),
            BoundingBox3d.fromCenterSize(Vector3d.one, 1.0)
        )

        assertEquals(
            BoundingBox3d(Vector3d(-1.0, 0.0, 0.0), Vector3d(2.0, 1.0, 1.0)),
            BoundingBox3d(Vector3d.zero, Vector3d.one)
                .including(Vector3d(-1.0, 0.5, 0.5))
                .including(Vector3d(2.0, 0.5, 0.5))
        )
    }

    @Test
    fun boundingBoxFromPoints() {
        val points = listOf(Vector3d(1.0, 5.0, -2.0), Vector3d(-3.0, 0.0, 4.0), Vector3d(2.0, 2.0, 2.0))

        val expected = BoundingBox3d(Vector3d(-3.0, 0.0, -2.0), Vector3d(2.0, 5.0, 4.0))

        assertEquals(expected, BoundingBox3d.fromPoints(points))
        assertEquals(BoundingBox3d.zero, BoundingBox3d.fromPoints(emptyList()))

        val single = Vector3d(7.0, 8.0, 9.0)

        assertEquals(BoundingBox3d(single, single), BoundingBox3d.fromPoints(listOf(single)))
    }

    @Test
    fun boundingBoxFromOrientedBoxMatchesRotatedExtents() {
        val rotated = OrientedBoundingBox3d(
            Vector3d(1.0, 2.0, 3.0),
            Rotation3d.fromAxisAngle(Vector3d.unitZ, PI / 4.0),
            Vector3d(1.0, 1.0, 2.0)
        )

        val box = BoundingBox3d.fromOrientedBoundingBox(rotated)

        assertTrue(box.min.approxEq(Vector3d(1.0 - sqrt(2.0), 2.0 - sqrt(2.0), 1.0), 1e-9))
        assertTrue(box.max.approxEq(Vector3d(1.0 + sqrt(2.0), 2.0 + sqrt(2.0), 5.0), 1e-9))
    }

    @Test
    fun boundingBoxFromSphereAndSphereInclusion() {
        val sphere = BoundingSphere3d(Vector3d(1.0, 2.0, 3.0), 2.0)

        val sphereBox = BoundingBox3d(Vector3d(-1.0, 0.0, 1.0), Vector3d(3.0, 4.0, 5.0))

        assertEquals(sphereBox, BoundingBox3d.fromBoundingSphere(sphere))

        val included = BoundingBox3d(Vector3d.zero, Vector3d.one) including sphere

        assertEquals(Vector3d(-1.0, 0.0, 0.0), included.min)
        assertEquals(Vector3d(3.0, 4.0, 5.0), included.max)
    }

    @Test
    fun boundingBoxFromCylinderIsTightAroundRims() {
        val axisAligned = Cylinder3d(Line3d(Vector3d.zero, Vector3d.unitY, 2.0), 1.0)

        assertEquals(
            BoundingBox3d(Vector3d(-1.0, 0.0, -1.0), Vector3d(1.0, 2.0, 1.0)),
            BoundingBox3d.fromCylinder(axisAligned)
        )

        val random = Random(51)

        repeat(50) {
            val extent = Line3d.fromStartEnd(randomVector(random, 5.0), randomVector(random, 5.0))
            val cylinder = Cylinder3d(extent, random.nextDouble(0.1, 2.0))
            val box = BoundingBox3d.fromCylinder(cylinder)
            val perpendicularA = extent.direction.perpendicular()
            val perpendicularB = extent.direction cross perpendicularA

            var minimum = Vector3d(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
            var maximum = Vector3d(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)

            for (step in 0 until 3600) {
                val angle = step * 2.0 * PI / 3600.0
                val offset = (perpendicularA * cos(angle) + perpendicularB * sin(angle)) * cylinder.radius

                for (point in listOf(extent.origin + offset, extent.end + offset)) {
                    minimum = Vector3d.min(minimum, point)
                    maximum = Vector3d.max(maximum, point)
                }
            }

            assertTrue(box.min.approxEq(minimum, 1e-4), "min ${box.min} vs $minimum")
            assertTrue(box.max.approxEq(maximum, 1e-4), "max ${box.max} vs $maximum")
        }
    }

    @Test
    fun boundingBox2dBasics() {
        val box = BoundingBox2d(Vector2d(0.0, 0.0), Vector2d(2.0, 3.0))
        val other = BoundingBox2d(Vector2d(1.0, 1.0), Vector2d(4.0, 5.0))

        assertEquals(6.0, box.capacity, 1e-12)
        assertEquals(Vector2d(1.0, 1.5), box.center)
        assertEquals(BoundingBox2d(Vector2d(0.0, 0.0), Vector2d(4.0, 5.0)), box u other)
        assertEquals(BoundingBox2d(Vector2d(1.0, 1.0), Vector2d(2.0, 3.0)), box n other)
        assertEquals(BoundingBox2d.zero, box n BoundingBox2d(Vector2d(9.0, 9.0), Vector2d(10.0, 10.0)))
        assertTrue(box intersectsWith other)
        assertEquals(ContainmentMode.Intersected, box.evaluateContainment(other))
        val inside = BoundingBox2d(Vector2d(2.0, 2.0), Vector2d(3.0, 3.0))

        assertEquals(ContainmentMode.ContainedFully, other.evaluateContainment(inside))
        assertTrue(box.contains(Vector2d(1.0, 1.0)))
        assertFalse(box.contains(Vector2d(2.0, 1.0)))
        assertEquals(BoundingBox2d(Vector2d(-1.0, -1.0), Vector2d(3.0, 4.0)), box.inflated(1.0))
        val centered = BoundingBox2d(Vector2d(-1.0, -1.0), Vector2d(1.0, 1.0))

        assertEquals(centered, BoundingBox2d.fromCenterSize(Vector2d.zero, 2.0))
    }

    @Test
    fun orientedBoxMeasuresAndCorners() {
        val box = OrientedBoundingBox3d(Vector3d(1.0, 2.0, 3.0), Rotation3d.identity, Vector3d(1.0, 2.0, 3.0))

        assertEquals(48.0, box.capacity, 1e-12)
        assertEquals(88.0, box.surface, 1e-12)
        assertEquals(Vector3d(2.0, 4.0, 6.0), box.size)

        val boxCorners = corners(box)

        assertEquals(8, boxCorners.size)
        assertEquals(8, boxCorners.toSet().size)

        boxCorners.forEach {
            assertEquals(1.0, abs(it.x - 1.0), 1e-12)
            assertEquals(2.0, abs(it.y - 2.0), 1e-12)
            assertEquals(3.0, abs(it.z - 3.0), 1e-12)
        }
    }

    @Test
    fun orientedBoxContainsPointsInLocalFrame() {
        val box = OrientedBoundingBox3d(
            Vector3d(0.0, 0.0, 0.0),
            Rotation3d.fromAxisAngle(Vector3d.unitZ, PI / 4.0),
            Vector3d(2.0, 0.5, 1.0)
        )

        assertTrue(box contains Vector3d(1.0, 1.0, 0.0))
        assertFalse(box contains Vector3d(1.0, -1.0, 0.0))
        assertFalse(box contains Vector3d(0.0, 0.0, 1.5))
        assertTrue(box contains Vector3d.zero)
    }

    @Test
    fun orientedBoxFromTransformAndAxisAlignedBox() {
        val pose = Pose3d(Vector3d(10.0, 0.0, 0.0), Rotation3d.fromAxisAngle(Vector3d.unitZ, PI / 2.0))
        val box = OrientedBoundingBox3d(pose, BoundingBox3d(Vector3d(1.0, 0.0, 0.0), Vector3d(3.0, 2.0, 2.0)))

        assertTrue(box.center.approxEq(Vector3d(9.0, 2.0, 1.0), 1e-9))
        assertEquals(Vector3d(1.0, 1.0, 1.0), box.halfSize)
        assertTrue(OrientedBoundingBox3d.createFromBoundingBox(BoundingBox3d(Vector3d.zero, Vector3d.one))
            .center.approxEq(Vector3d(0.5, 0.5, 0.5)))
    }

    @Test
    fun orientedBoxInflationGrowsEveryAxis() {
        val box = OrientedBoundingBox3d(Vector3d.zero, Rotation3d.identity, Vector3d(1.0, 1.0, 1.0))
        val inflated = box.inflated(0.5)

        assertEquals(Vector3d(1.5, 1.5, 1.5), inflated.halfSize)
    }

    @Test
    fun orientedBoxInflationGrowsXAndY() {
        val box = OrientedBoundingBox3d(Vector3d.zero, Rotation3d.identity, Vector3d(1.0, 2.0, 3.0))
        val inflated = box.inflated(0.5, 0.25, 0.0)

        assertEquals(1.5, inflated.halfSize.x, 1e-12)
        assertEquals(2.25, inflated.halfSize.y, 1e-12)
        assertEquals(3.0, inflated.halfSize.z, 1e-12)
    }

    @Test
    fun orientedBoxContainmentKnownCases() {
        val outer = OrientedBoundingBox3d(Vector3d.zero, Rotation3d.identity, Vector3d(5.0, 5.0, 5.0))
        val inner = OrientedBoundingBox3d(
            Vector3d(1.0, 0.0, 0.0),
            Rotation3d.fromAxisAngle(Vector3d.unitZ, PI / 4.0),
            Vector3d(1.0, 1.0, 1.0)
        )

        val crossing = OrientedBoundingBox3d(Vector3d(5.0, 0.0, 0.0), Rotation3d.identity, Vector3d(1.0, 1.0, 1.0))
        val far = OrientedBoundingBox3d(Vector3d(20.0, 0.0, 0.0), Rotation3d.identity, Vector3d(1.0, 1.0, 1.0))

        assertEquals(ContainmentMode.ContainedFully, outer.evaluateContainment(inner))
        assertEquals(ContainmentMode.Intersected, outer.evaluateContainment(crossing))
        assertEquals(ContainmentMode.Disjoint, outer.evaluateContainment(far))
        assertEquals(ContainmentMode.Intersected, inner.evaluateContainment(outer))
        assertTrue(outer contains inner)
        assertTrue(outer intersectsWith crossing)
        assertFalse(outer intersectsWith far)
    }

    @Test
    fun orientedBoxesRotatedDiagonallyIntersectOnlyWithinTheirDiagonalExtent() {
        val first = OrientedBoundingBox3d(
            Vector3d.zero,
            Rotation3d.fromAxisAngle(Vector3d.unitZ, PI / 4.0),
            Vector3d(1.0, 1.0, 1.0)
        )

        val nearCorner = OrientedBoundingBox3d(
            Vector3d(2.5, 0.0, 0.0),
            Rotation3d.fromAxisAngle(Vector3d.unitZ, PI / 4.0),
            Vector3d(1.0, 1.0, 1.0)
        )

        val separatedAlongDiagonalExtent = OrientedBoundingBox3d(
            Vector3d(2.9, 0.0, 0.0),
            Rotation3d.fromAxisAngle(Vector3d.unitZ, PI / 4.0),
            Vector3d(1.0, 1.0, 1.0)
        )

        assertTrue(first intersectsWith nearCorner)
        assertFalse(first intersectsWith separatedAlongDiagonalExtent)
    }

    @Test
    fun orientedBoxVersusOrientedBoxMatchesSeparatingAxisReference() {
        val random = Random(52)
        var disjointCount = 0
        var intersectingCount = 0

        repeat(3000) {
            val first = randomOrientedBox(random, 3.0, 0.2, 1.5)
            val second = randomOrientedBox(random, 3.0, 0.2, 1.5)
            val result = first.evaluateContainment(second)
            val separated = referenceSeparated(first, second)

            assertEquals(separated, result == ContainmentMode.Disjoint, "$first vs $second")

            if (separated) {
                disjointCount++
            } else {
                intersectingCount++
            }
        }

        assertTrue(disjointCount > 200 && intersectingCount > 200)
    }

    @Test
    fun orientedBoxFullContainmentMatchesCornerTest() {
        val random = Random(53)
        var containedCount = 0

        repeat(3000) {
            val outer = randomOrientedBox(random, 1.0, 1.5, 3.0)
            val inner = randomOrientedBox(random, 1.0, 0.05, 0.8)
            val allCornersInside = corners(inner).all { outer contains it }
            val result = outer.evaluateContainment(inner)

            assertEquals(allCornersInside, result == ContainmentMode.ContainedFully, "$outer vs $inner")

            if (allCornersInside) {
                containedCount++
            }
        }

        assertTrue(containedCount > 100)
    }

    @Test
    fun orientedBoxAxisAlignedContainmentMatchesBoundingBox() {
        val random = Random(54)

        repeat(2000) {
            val first = randomBox(random, 3.0, 0.2, 4.0)
            val second = randomBox(random, 3.0, 0.2, 4.0)

            assertEquals(
                first.evaluateContainment(second),
                OrientedBoundingBox3d.createFromBoundingBox(first).evaluateContainment(second),
                "$first vs $second"
            )
        }
    }

    @Test
    fun orientedBoxVersusSphereMatchesClosestPointReference() {
        val random = Random(55)

        repeat(3000) {
            val box = randomOrientedBox(random, 2.0, 0.3, 2.0)
            val sphere = BoundingSphere3d(randomVector(random, 4.0), random.nextDouble(0.1, 2.0))
            val local = box.transformLocal(sphere.origin)

            val closest = Vector3d(
                local.x.coerceIn(-box.halfSize.x, box.halfSize.x),
                local.y.coerceIn(-box.halfSize.y, box.halfSize.y),
                local.z.coerceIn(-box.halfSize.z, box.halfSize.z)
            )

            val distance = (local - closest).norm

            val inside = abs(local.x) + sphere.radius <= box.halfSize.x &&
                abs(local.y) + sphere.radius <= box.halfSize.y &&
                abs(local.z) + sphere.radius <= box.halfSize.z

            val expected = when {
                inside -> ContainmentMode.ContainedFully
                distance >= sphere.radius -> ContainmentMode.Disjoint
                else -> ContainmentMode.Intersected
            }

            assertEquals(expected, box.evaluateContainment(sphere), "$box vs $sphere")
        }
    }

    @Test
    fun boundingSphereFromBoxCoversCorners() {
        val box = BoundingBox3d(Vector3d(-1.0, -2.0, -3.0), Vector3d(1.0, 2.0, 3.0))
        val sphere = BoundingSphere3d(box)

        assertEquals(Vector3d.zero, sphere.origin)
        assertEquals(sqrt(14.0), sphere.radius, 1e-12)
        assertEquals(14.0, sphere.radiusSqr, 1e-9)
    }

    @Test
    fun boundingSphereUnionContainsBothSpheres() {
        val random = Random(56)

        repeat(500) {
            val first = BoundingSphere3d(randomVector(random, 5.0), random.nextDouble(0.1, 3.0))
            val second = BoundingSphere3d(randomVector(random, 5.0), random.nextDouble(0.1, 3.0))
            val union = first unionWith second

            val firstReach = (union.origin distanceTo first.origin) + first.radius
            val secondReach = (union.origin distanceTo second.origin) + second.radius

            assertTrue(firstReach <= union.radius + 1e-9, "first not contained")
            assertTrue(secondReach <= union.radius + 1e-9, "second not contained")
            val loosestBound = maxOf(first.radius, second.radius) + (first.origin distanceTo second.origin)

            assertTrue(union.radius <= loosestBound + 1e-9)
        }
    }

    @Test
    fun boundingSphereUnionOfSeparatedAndNestedSpheres() {
        val first = BoundingSphere3d(Vector3d.zero, 1.0)
        val second = BoundingSphere3d(Vector3d(10.0, 0.0, 0.0), 2.0)
        val union = first unionWith second

        assertEquals(6.5, union.radius, 1e-12)
        assertTrue(union.origin.approxEq(Vector3d(5.5, 0.0, 0.0), 1e-12))

        val big = BoundingSphere3d(Vector3d.zero, 10.0)
        val small = BoundingSphere3d(Vector3d(1.0, 0.0, 0.0), 1.0)

        assertEquals(big, big unionWith small)
        assertEquals(big, small unionWith big)
        assertEquals(first, first unionWith first)
    }

    @Test
    fun boundingSphereContainsPointsStrictly() {
        val sphere = BoundingSphere3d(Vector3d.zero, 1.0)

        assertTrue(sphere.contains(Vector3d(0.5, 0.0, 0.0)))
        assertFalse(sphere.contains(Vector3d(1.0, 0.0, 0.0)))
    }

    @Test
    fun rayEvaluationAndValidity() {
        val ray = Ray3d(Vector3d(1.0, 2.0, 3.0), Vector3d.unitX)

        assertEquals(Vector3d(4.0, 2.0, 3.0), ray.evaluate(3.0))
        assertTrue(ray.isValid)
        assertFalse(Ray3d(Vector3d.zero, Vector3d(2.0, 0.0, 0.0)).isValid)
        assertFalse(Ray3d(Vector3d(Double.NaN, 0.0, 0.0), Vector3d.unitX).isValid)
        val towardsY = Ray3d.fromSourceAndDestination(Vector3d.zero, Vector3d(0.0, 5.0, 0.0))

        assertTrue(towardsY.direction.approxEq(Vector3d.unitY))
    }

    @Test
    fun rayVersusBoxKnownCases() {
        val box = BoundingBox3d(Vector3d.zero, Vector3d.one)

        val hit = Ray3d(Vector3d(-5.0, 0.5, 0.5), Vector3d.unitX) intersectionWith box

        assertEquals(5.0, hit!!.entry, 1e-12)
        assertEquals(6.0, hit.exit, 1e-12)

        val inside = Ray3d(Vector3d(0.5, 0.5, 0.5), Vector3d.unitX) intersectionWith box

        assertEquals(0.0, inside!!.entry, 1e-12)
        assertEquals(0.5, inside.exit, 1e-12)

        assertNull(Ray3d(Vector3d(-5.0, 0.5, 0.5), -Vector3d.unitX) intersectionWith box)
        assertNull(Ray3d(Vector3d(-5.0, 2.0, 0.5), Vector3d.unitX) intersectionWith box)
        assertNull(Ray3d(Vector3d(0.5, 5.0, 0.5), Vector3d.unitX) intersectionWith box)
        assertTrue(Ray3d(Vector3d(-1.0, -1.0, -1.0), Vector3d.one.normalized()) intersectsWith box)
    }

    @Test
    fun rayVersusBoxMatchesSamplingReference() {
        val random = Random(57)
        var hits = 0

        repeat(1500) {
            val box = randomBox(random, 2.0, 0.3, 3.0)
            val ray = randomRay(random, box.center)
            val intersection = ray intersectionWith box

            if (intersection == null) {
                var step = 0.0

                while (step < 30.0) {
                    assertFalse(box.contains(ray.evaluate(step)), "missed hit at t=$step for $ray vs $box")
                    step += 0.01
                }
            } else {
                hits++

                assertTrue(intersection.entry >= 0.0)
                assertTrue(intersection.entry <= intersection.exit)
                assertTrue(box.inflated(1e-9).contains(ray.evaluate((intersection.entry + intersection.exit) / 2.0)))
                assertTrue(box.inflated(1e-9).contains(ray.evaluate(intersection.entry + 1e-6)) ||
                    intersection.exit - intersection.entry < 1e-6)
            }
        }

        assertTrue(hits > 100)
    }

    @Test
    fun rayVersusOrientedBoxMatchesLocalSpaceAxisAlignedReference() {
        val random = Random(58)
        var hits = 0

        repeat(3000) {
            val box = randomOrientedBox(random, 2.0, 0.3, 1.5)
            val ray = randomRay(random, box.center)
            val inverse = box.transform.rotation.inverse

            val localRay = Ray3d(inverse * (ray.origin - box.center), inverse * ray.direction)
            val localBox = BoundingBox3d(-box.halfSize, box.halfSize)

            val expected = localRay intersectionWith localBox
            val actual = ray intersectionWith box

            if (expected == null) {
                assertNull(actual, "unexpected hit for $ray vs $box")
            } else {
                hits++
                assertNotNull(actual, "missed hit for $ray vs $box")
                assertEquals(expected.entry, actual!!.entry, 1e-6)
                assertEquals(expected.exit, actual.exit, 1e-6)
            }
        }

        assertTrue(hits > 200)
    }

    @Test
    fun rayVersusPlane() {
        val plane = Plane3d(Vector3d.unitY, -2.0)
        val ray = Ray3d(Vector3d(1.0, 5.0, 1.0), Vector3d(0.0, -1.0, 1.0).normalized())
        val point = ray intersectionWith plane

        assertTrue(point.approxEq(Vector3d(1.0, 2.0, 4.0), 1e-9))
        assertTrue(ray intersectsWith plane)
        assertFalse(Ray3d(Vector3d(0.0, 5.0, 0.0), Vector3d.unitX) intersectsWith plane)
    }

    @Test
    fun rayVersusCylinderKnownCases() {
        val cylinder = Cylinder3d(Line3d(Vector3d(0.0, -1.0, 0.0), Vector3d.unitY, 2.0), 1.0)

        val hit = Ray3d(Vector3d(-5.0, 0.0, 0.0), Vector3d.unitX) intersectionWith cylinder

        assertEquals(4.0, hit!!.entry, 1e-9)
        assertEquals(6.0, hit.exit, 1e-9)
        assertNull(Ray3d(Vector3d(-5.0, 0.0, 2.0), Vector3d.unitX) intersectionWith cylinder)
        assertNull(Ray3d(Vector3d(-5.0, 3.0, 0.0), Vector3d.unitX) intersectionWith cylinder)
        assertTrue(Ray3d(Vector3d(-5.0, 0.5, 0.5), Vector3d.unitX) intersectsWith cylinder)
    }

    @Test
    fun rayVersusTiltedCylinderHitsLieOnSurface() {
        val random = Random(59)
        var hits = 0

        repeat(2000) {
            val extent = Line3d.fromStartEnd(randomVector(random, 2.0), randomVector(random, 2.0))

            if (extent.length > 0.5) {
                val cylinder = Cylinder3d(extent, random.nextDouble(0.2, 1.0))
                val ray = randomRay(random, cylinder.center)
                val intersection = ray intersectionWith cylinder

                if (intersection != null) {
                    hits++

                    val point = ray.evaluate(intersection.entry)
                    val relative = point - extent.origin
                    val axial = relative dot extent.direction
                    val radial = (relative - extent.direction * axial).norm

                    assertEquals(cylinder.radius, radial, 1e-6)
                    assertTrue(axial >= -1e-6 && axial <= extent.length + 1e-6, "axial $axial of ${extent.length}")
                }
            }
        }

        assertTrue(hits > 20)
    }

    @Test
    fun lineSegmentVersusBoxRespectsLength() {
        val box = BoundingBox3d(Vector3d.zero, Vector3d.one)
        val origin = Vector3d(-5.0, 0.5, 0.5)

        assertNotNull(Line3d(origin, Vector3d.unitX, 6.0) intersectionWith box)
        assertNull(Line3d(origin, Vector3d.unitX, 4.0) intersectionWith box)
        assertTrue(Line3d(origin, Vector3d.unitX, 10.0) intersectsWith box)
        assertFalse(Line3d(origin, Vector3d.unitX, 1.0) intersectsWith box)
    }

    @Test
    fun lineSegmentFactoriesAndPoints() {
        val line = Line3d.fromStartEnd(Vector3d(1.0, 1.0, 1.0), Vector3d(1.0, 1.0, 5.0))

        assertEquals(4.0, line.length, 1e-12)
        assertEquals(Vector3d.unitZ, line.direction)
        assertEquals(Vector3d(1.0, 1.0, 5.0), line.end)
        assertEquals(Vector3d(1.0, 1.0, 3.0), line.center)
        assertTrue(line.isValid)
        assertEquals(Ray3d(line.origin, line.direction), line.ray)
        assertFalse(Line3d(Vector3d.zero, Vector3d.unitX, Double.POSITIVE_INFINITY).isValid)
    }

    @Test
    fun cylinderVersusBoxUsesInflation() {
        val cylinder = Cylinder3d(Line3d(Vector3d.zero, Vector3d.unitX, 4.0), 1.0)
        val nearBox = BoundingBox3d(Vector3d(1.0, 0.5, -0.5), Vector3d(2.0, 1.5, 0.5))
        val farBox = BoundingBox3d(Vector3d(1.0, 1.5, -0.5), Vector3d(2.0, 2.5, 0.5))

        assertTrue(cylinder intersectsWith nearBox)
        assertFalse(cylinder intersectsWith farBox)
    }

    @Test
    fun planeFromVerticesHasExpectedNormalAndOffset() {
        val plane = Plane3d.createFromVertices(
            Vector3d(0.0, 2.0, 0.0),
            Vector3d(1.0, 2.0, 0.0),
            Vector3d(0.0, 2.0, 1.0)
        )

        assertTrue(plane.normal.approxEq(-Vector3d.unitY, 1e-12))
        assertEquals(2.0, plane.d, 1e-12)
        assertTrue(plane.isValid)
        assertEquals(0.0, plane.signedDistanceToPoint(Vector3d(7.0, 2.0, -3.0)), 1e-12)
    }

    @Test
    fun planeFromNormalAndPointContainsThatPoint() {
        val plane = Plane3d(Vector3d(0.0, 0.0, 2.0).normalized(), Vector3d(1.0, 2.0, 3.0))

        assertTrue(plane contains Vector3d(5.0, -6.0, 3.0))
        assertEquals(2.0, plane.signedDistanceToPoint(Vector3d(0.0, 0.0, 5.0)), 1e-12)
        assertEquals(2.0, plane.distanceToPoint(Vector3d(0.0, 0.0, 1.0)), 1e-12)
        assertEquals(-2.0, plane.signedDistanceToPoint(Vector3d(0.0, 0.0, 1.0)), 1e-12)
    }

    @Test
    fun planePointClassification() {
        val plane = Plane3d.unitY

        assertEquals(PlaneIntersectionType.Positive, plane.evaluateIntersection(Vector3d(0.0, 1.0, 0.0)))
        assertEquals(PlaneIntersectionType.Negative, plane.evaluateIntersection(Vector3d(0.0, -1.0, 0.0)))
        assertEquals(PlaneIntersectionType.Intersects, plane.evaluateIntersection(Vector3d(5.0, 1e-9, 5.0)))
        assertEquals(PlaneIntersectionType.Positive, plane.evaluateIntersection(Vector3d(0.0, 0.1, 0.0), 0.01))
        assertEquals(PlaneIntersectionType.Intersects, plane.evaluateIntersection(Vector3d(0.0, 0.1, 0.0), 0.5))
    }

    @Test
    fun planeVersusBoxesMatchesCornerReference() {
        val random = Random(60)

        repeat(2000) {
            val plane = Plane3d(randomUnitVector(random), random.nextDouble(-3.0, 3.0))
            val orientedBox = randomOrientedBox(random, 3.0, 0.2, 1.5)
            val axisAlignedBox = randomBox(random, 3.0, 0.2, 3.0)

            val orientedDistances = corners(orientedBox).map { plane.signedDistanceToPoint(it) }
            val axisAlignedDistances = corners(OrientedBoundingBox3d.createFromBoundingBox(axisAlignedBox))
                .map { plane.signedDistanceToPoint(it) }

            fun classify(distances: List<Double>) = when {
                distances.all { it > 0.0 } -> PlaneIntersectionType.Positive
                distances.all { it < 0.0 } -> PlaneIntersectionType.Negative
                else -> PlaneIntersectionType.Intersects
            }

            assertEquals(classify(orientedDistances), plane.evaluateIntersection(orientedBox))
            assertEquals(classify(axisAlignedDistances), plane.evaluateIntersection(axisAlignedBox))
        }
    }

    @Test
    fun planeVersusSphere() {
        val plane = Plane3d.unitX
        val above = BoundingSphere3d(Vector3d(5.0, 0.0, 0.0), 1.0)
        val below = BoundingSphere3d(Vector3d(-5.0, 0.0, 0.0), 1.0)
        val crossing = BoundingSphere3d(Vector3d(0.5, 0.0, 0.0), 1.0)

        assertEquals(PlaneIntersectionType.Positive, plane.evaluateIntersection(above))
        assertEquals(PlaneIntersectionType.Negative, plane.evaluateIntersection(below))
        assertEquals(PlaneIntersectionType.Intersects, plane.evaluateIntersection(crossing))
        assertTrue(plane intersectsWith crossing)
        assertFalse(plane intersectsWith above)
    }

    @Test
    fun ddaVisitsOnlyFaceAdjacentCellsStartingAtOriginCell() {
        val random = Random(61)

        repeat(300) {
            val ray = Ray3d(randomVector(random, 10.0), randomUnitVector(random))
            val visited = ArrayList<Triple<Int, Int, Int>>()

            dda(ray) { x, y, z ->
                visited.add(Triple(x, y, z))
                visited.size < 60
            }

            assertEquals(
                Triple(floor(ray.origin.x).toInt(), floor(ray.origin.y).toInt(), floor(ray.origin.z).toInt()),
                visited.first()
            )

            for (index in 1 until visited.size) {
                val previous = visited[index - 1]
                val current = visited[index]

                val manhattan = abs(current.first - previous.first) +
                    abs(current.second - previous.second) +
                    abs(current.third - previous.third)

                assertEquals(1, manhattan, "step $index of $ray")
            }
        }
    }

    @Test
    fun ddaCoversEveryCellAlongTheRay() {
        val random = Random(62)

        repeat(300) {
            val ray = Ray3d(randomVector(random, 10.0), randomUnitVector(random))
            val visited = HashSet<Triple<Int, Int, Int>>()

            dda(ray) { x, y, z ->
                visited.add(Triple(x, y, z))
                visited.size < 80
            }

            var step = 0.0

            while (step < 20.0) {
                val point = ray.evaluate(step)

                val cell = Triple(floor(point.x).toInt(), floor(point.y).toInt(), floor(point.z).toInt())

                assertTrue(cell in visited, "cell $cell at t=$step not visited for $ray")

                step += 0.05
            }
        }
    }

    @Test
    fun ddaAxisAlignedRaysWalkStraightLines() {
        val positive = ArrayList<Triple<Int, Int, Int>>()

        dda(Ray3d(Vector3d(0.5, 0.5, 0.5), Vector3d.unitX)) { x, y, z ->
            positive.add(Triple(x, y, z))
            positive.size < 4
        }

        assertEquals(listOf(Triple(0, 0, 0), Triple(1, 0, 0), Triple(2, 0, 0), Triple(3, 0, 0)), positive)

        val negative = ArrayList<Triple<Int, Int, Int>>()

        dda(Ray3d(Vector3d(0.5, 0.5, 0.5), -Vector3d.unitZ)) { x, y, z ->
            negative.add(Triple(x, y, z))
            negative.size < 3
        }

        assertEquals(listOf(Triple(0, 0, 0), Triple(0, 0, -1), Triple(0, 0, -2)), negative)
    }

    @Test
    fun ddaCanSkipTheSourceCell() {
        val visited = ArrayList<Triple<Int, Int, Int>>()

        dda(Ray3d(Vector3d(0.5, 0.5, 0.5), Vector3d.unitY), withSource = false) { x, y, z ->
            visited.add(Triple(x, y, z))
            visited.size < 2
        }

        assertEquals(listOf(Triple(0, 1, 0), Triple(0, 2, 0)), visited)
    }
}
