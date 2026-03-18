@file:Suppress("MemberVisibilityCanBePrivate", "LocalVariableName", "NonAsciiCharacters")

package org.ageseries.libage.mathematics.geometry

import org.ageseries.libage.mathematics.approxEq
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Describes the mode of containment of an object inside another object.
 * */
enum class ContainmentMode {
    /**
     * The two objects do not intersect at all.
     * */
    Disjoint,
    /**
     * The two objects intersect, but the right-hand-side object is not contained completely inside the left-hand-side object.
     * */
    Intersected,
    /**
     * The right-hand-side object is contained fully inside the left-hand-side object.
     * */
    ContainedFully
}

/**
 * Generalized (higher dimensionality) cube-like bounding box with some common operations.
 * */
interface BoundingBox<Self : BoundingBox<Self>> {
    /**
     * Checks if the bounding box is valid. This does not imply that its volume is larger than 0.
     * */
    val isValid: Boolean

    val isZero get() = capacity == 0.0

    /**
     * Gets the capacity of the bounding box.
     * For example, a 2D bounding box would supply its surface area. A 3D bounding box would supply its volume.
     * */
    val capacity: Double

    /**
     * Gets the total surface area of the bounding box.
     * For example, a 2D bounding box would supply its surface area. A 3D bounding box would supply the sum of the surface areas of its faces.
     * */
    val surface: Double

    /**
     * Gets the union of this bounding box with another bounding box of the same type.
     * */
    infix fun unionWith(other: Self): Self

    /**
     * Gets the intersection of this bounding box with another box of the same type.
     * The result will be an empty (zero-capacity) bounding box if there is no intersection.
     * */
    infix fun intersectionWith(other: Self): Self

    /**
     * Checks if this bounding box intersects with another bounding box of the same type.
     * */
    infix fun intersectsWith(other: Self): Boolean

    /**
     * Inflates the bounding box along each axis by [percent].
     * */
    fun inflatedBy(percent: Double): Self

    /**
     * Evaluates the containment of the other bounding box in this one.
     * @return The containment mode.
     * [ContainmentMode.Disjoint] means the boxes do not intersect and implicitly testing [intersectsWith] the other box will result in **false**.
     * [ContainmentMode.Intersected] means the boxes intersect but the other box is not contained inside this box.
     * [ContainmentMode.ContainedFully] means the boxes intersect and the other box is contained fully inside this box.
     * */
    fun evaluateContainment(other: Self): ContainmentMode
}

infix fun<T : BoundingBox<T>> BoundingBox<T>.u(other: T) = this unionWith other
infix fun<T : BoundingBox<T>> BoundingBox<T>.n(other: T) = this intersectionWith other

/**
 * Represents a 2D Axis-Aligned Bounding Box (AABB).
 * */
data class BoundingBox2d(val min: Vector2d, val max: Vector2d) : BoundingBox<BoundingBox2d> {
    override val isValid get() = min.x <= max.x && min.y <= max.y
    val center get() = (min + max) / 2.0
    val width get() = max.x - min.x
    val height get() = max.y - min.y
    val size get() = max - min
    override val capacity get() = width * height
    override val surface get() = width * height

    override infix fun intersectionWith(other: BoundingBox2d): BoundingBox2d {
        val result = BoundingBox2d(
            Vector2d.max(this.min, other.min),
            Vector2d.min(this.max, other.max)
        )

        return if (result.isValid) {
            result
        } else {
            zero
        }
    }

    override infix fun intersectsWith(other: BoundingBox2d) =
        other.min.x < this.max.x && this.min.x < other.max.x &&
        other.min.y < this.max.y && this.min.y < other.max.y

    override infix fun unionWith(other: BoundingBox2d) = BoundingBox2d(
        Vector2d.min(this.min, other.min),
        Vector2d.max(this.max, other.max)
    )

    fun inflated(amountX: Double, amountY: Double) = BoundingBox2d(
        Vector2d(this.min.x - amountX, this.min.y - amountY),
        Vector2d(this.max.x + amountX, this.max.y + amountY)
    )

    fun inflated(amount: Vector2d) = inflated(amount.x, amount.y)
    fun inflated(amount: Double) = inflated(amount, amount)
    override fun inflatedBy(percent: Double) = inflated(width * percent, height * percent)

    override fun evaluateContainment(other: BoundingBox2d): ContainmentMode {
        if (!this.intersectsWith(other)) {
            return ContainmentMode.Disjoint
        }

        val test = this.min.x > other.min.x || other.max.x > this.max.x ||
            this.min.y > other.min.y || other.max.y > this.max.y

        return if (test) ContainmentMode.Intersected
        else ContainmentMode.ContainedFully
    }

    fun contains(point: Vector2d) =
        this.min.x < point.x && this.min.y < point.y &&
        this.max.x > point.x && this.max.y > point.y

    companion object {
        val zero = BoundingBox2d(Vector2d.zero, Vector2d.zero)

        fun fromCenterSize(center: Vector2d, size: Vector2d) : BoundingBox2d {
            val halfX = size.x * 0.5
            val halfY = size.y * 0.5

            return BoundingBox2d(
                Vector2d(
                    center.x - halfX,
                    center.y - halfY
                ),
                Vector2d(
                    center.x + halfX,
                    center.y + halfY
                )
            )
        }

        fun fromCenterSize(center: Vector2d, size: Double) : BoundingBox2d {
            val half = size * 0.5

            return BoundingBox2d(
                Vector2d(
                    center.x - half,
                    center.y - half
                ),
                Vector2d(
                    center.x + half,
                    center.y + half
                )
            )
        }
    }
}

/**
 * Represents a 3D Axis-Aligned Bounding Box (AABB).
 * */
data class BoundingBox3d(val min: Vector3d, val max: Vector3d) : BoundingBox<BoundingBox3d> {
    val isNaN get() = min.isNaN || max.isNaN
    val isInfinity get() = min.isInfinity || max.isInfinity

    /**
     * True if a correct AABB is described (minimum coordinates are smaller than or equal to maximum coordinates) and none of the coordinates are infinity or NaN.
     * */
    override val isValid get() = !isInfinity && min.x <= max.x && min.y <= max.y && min.z <= max.z

    val center get() = (min + max) * 0.5
    val width get() = max.x - min.x
    val height get() = max.y - min.y
    val depth get() = max.z - min.z
    val size get() = max - min // also called extent
    val halfSize get() = Vector3d(
        (max.x - min.x) * 0.5,
        (max.y - min.y) * 0.5,
        (max.z - min.z) * 0.5
    ) // also called half extent

    /**
     * Gets the classical volume of the bounding box.
     * */
    override val capacity get() = width * height * depth

    /**
     * Gets the surface area of the bounding box.
     * */
    override val surface get() = 2.0 * (width * height + depth * height + width * depth)

    /**
     * Gets the intersection between this box and the [other] box. The result will be [zero] if there is no intersection.
     * */
    override infix fun intersectionWith(other: BoundingBox3d): BoundingBox3d {
        val result = BoundingBox3d(
            Vector3d.max(this.min, other.min),
            Vector3d.min(this.max, other.max)
        )

        return if (result.isValid) {
            result
        } else {
            zero
        }
    }

    /**
     * Checks if this box intersects with the [other] box.
     * */
    override infix fun intersectsWith(other: BoundingBox3d) =
        other.min.x < this.max.x && this.min.x < other.max.x &&
        other.min.y < this.max.y && this.min.y < other.max.y &&
        other.min.z < this.max.z && this.min.z < other.max.z

    /**
     * Computes the union of this box with the [other box].
     * @return A box that contains this box and the other box.
     * */
    override infix fun unionWith(other: BoundingBox3d) = BoundingBox3d(
        Vector3d.min(min, other.min),
        Vector3d.max(max, other.max)
    )

    /**
     * Creates a new bounding box from this one, extended to contain [point].
     * */
    infix fun including(point: Vector3d) = BoundingBox3d(
        Vector3d.min(point, min),
        Vector3d.max(point, max)
    )

    /**
     * Creates a new bounding box from this one, extended to contain the [sphere].
     * */
    infix fun including(sphere: BoundingSphere3d) = BoundingBox3d(
        Vector3d.min(sphere.origin - sphere.radius, min),
        Vector3d.max(sphere.origin + sphere.radius, max)
    )

    fun inflated(amountX: Double, amountY: Double, amountZ: Double) = BoundingBox3d(
        Vector3d(this.min.x - amountX, this.min.y - amountY, this.min.z - amountZ),
        Vector3d(this.max.x + amountX, this.max.y + amountY, this.max.z + amountZ)
    )

    fun inflated(amount: Vector3d) = inflated(amount.x, amount.y, amount.z)

    fun inflated(amount: Double) = inflated(amount, amount, amount)

    override fun inflatedBy(percent: Double) = inflated(width * percent, height * percent, depth * percent)

    /**
     * Evaluates the containment mode of [other] in this box.
     * */
    override fun evaluateContainment(other: BoundingBox3d): ContainmentMode {
        if (!this.intersectsWith(other)) {
            return ContainmentMode.Disjoint
        }

        val test = this.min.x > other.min.x || other.max.x > this.max.x ||
            this.min.y > other.min.y || other.max.y > this.max.y ||
            this.min.z > other.min.z || other.max.z > this.max.z

        return if (test) ContainmentMode.Intersected
        else ContainmentMode.ContainedFully
    }

    /**
     * Checks if this box contains the [point].
     * */
    fun contains(point: Vector3d) =
        min.x < point.x && min.y < point.y && min.z < point.z &&
        max.x > point.x && max.y > point.y && max.z > point.z

    companion object {
        val zero = BoundingBox3d(Vector3d.zero, Vector3d.zero)

        /**
         * Gets a bounding box centered at [center], with its width, height and depth as specified by [size].
         * */
        fun fromCenterSize(center: Vector3d, size: Vector3d) : BoundingBox3d {
            val halfX = size.x * 0.5
            val halfY = size.y * 0.5
            val halfZ = size.z * 0.5

            return BoundingBox3d(
                Vector3d(
                    center.x - halfX,
                    center.y - halfY,
                    center.z - halfZ
                ),
                Vector3d(
                    center.x + halfX,
                    center.y + halfY,
                    center.z + halfZ
                )
            )
        }

        /**
         * Gets a bounding box centered at [center], with its width, height and depth equal to [size] (a cube).
         * */
        fun fromCenterSize(center: Vector3d, size: Double) : BoundingBox3d {
            val half = size * 0.5

            return BoundingBox3d(center - half, center + half)
        }

        /**
         * Creates a bounding box that contains the [orientedBoundingBox].
         * */
        fun fromOrientedBoundingBox(orientedBoundingBox: OrientedBoundingBox3d) : BoundingBox3d {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var minZ = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var maxZ = Double.NEGATIVE_INFINITY

            orientedBoundingBox.forEachCorner { (x, y, z) ->
                if(x < minX) minX = x
                if(y < minY) minY = y
                if(z < minZ) minZ = z
                if(x > maxX) maxX = x
                if(y > maxY) maxY = y
                if(z > maxZ) maxZ = z
            }

            return BoundingBox3d(
                Vector3d(minX, minY, minZ),
                Vector3d(maxX, maxY, maxZ)
            )
        }

        /**
         * Constructs a [BoundingBox3d] that contains the [sphere].
         * */
        fun fromBoundingSphere(sphere: BoundingSphere3d) = BoundingBox3d(
            sphere.origin - sphere.radius,
            sphere.origin + sphere.radius
        )


        /**
         * Constructs a [BoundingBox3d] that contains the [cylinder].
         * */
        fun fromCylinder(cylinder: Cylinder3d) : BoundingBox3d {
            val a = cylinder.extent.origin
            val b = cylinder.extent.end

            val (ax, ay, az) = a
            val (bx, by, bz) = b

            var dx2 = ax - bx
            var dy2 = ay - by
            var dz2 = az - bz
            dx2 *= dx2
            dy2 *= dy2
            dz2 *= dz2

            val k = 1.0 / (dx2 + dy2 + dz2)

            if(k.isInfinite() || k.isNaN()) {
                val center = cylinder.center
                return BoundingBox3d(center, center)
            }

            val r = cylinder.radius
            val ix = r * sqrt(k * (dy2 + dz2))
            val iy = r * sqrt(k * (dx2 + dz2))
            val iz = r * sqrt(k * (dx2 + dy2))

            return BoundingBox3d(
                Vector3d(
                    min(ax, bx) - ix,
                    min(ay, by) - iy,
                    min(az, bz) - iz,
                ),
                Vector3d(
                    max(ax, bx) + ix,
                    max(ay, by) + iy,
                    max(az, bz) + iz,
                )
            )
        }

        /**
         * Creates a bounding box that contains all [points].
         * */
        fun fromPoints(points: Iterator<Vector3d>) : BoundingBox3d {
            if(!points.hasNext()) {
                return zero
            }

            val first = points.next()

            var minX = first.x
            var minY = first.y
            var minZ = first.z
            var maxX = first.x
            var maxY = first.y
            var maxZ = first.z

            while (points.hasNext()) {
                val (x, y, z) = points.next()

                if(x < minX) minX = x
                if(y < minY) minY = y
                if(z < minZ) minZ = z
                if(x > maxX) maxX = x
                if(y > maxY) maxY = y
                if(z > maxZ) maxZ = z
            }

            return BoundingBox3d(
                Vector3d(minX, minY, minZ),
                Vector3d(maxX, maxY, maxZ)
            )
        }

        /**
         * Creates a bounding box that contains all [points].
         * */
        fun fromPoints(points: Iterable<Vector3d>) = fromPoints(points.iterator())
    }
}

/**
 * Represents a 3D Oriented Bounding Box (OBB).
 * This is like an AABB, but has rotation (thus is not an axis-aligned box).
 * */
data class OrientedBoundingBox3d(val transform: Pose3d, val halfSize: Vector3d) {
    /**
     * Constructs an OBB from the position of its [center], its [orientation] and [halfExtent].
     * */
    constructor(center: Vector3d, orientation: Rotation3d, halfExtent: Vector3d) : this(Pose3d(center, orientation), halfExtent)

    /**
     * Constructs an OBB from the [transform] and an axis-aligned [boundingBox].
     * *The center of the resulting OBB is a composition of the transform and the [boundingBox]'s center.*
     * */
    constructor(transform: Pose3d, boundingBox: BoundingBox3d) : this(transform * boundingBox.center, transform.rotation, boundingBox.halfSize)

    val center get() = transform.translation
    val width get() = halfSize.x * 2.0
    val height get() = halfSize.y * 2.0
    val depth get() = halfSize.z * 2.0
    val size get() = halfSize * 2.0

    /**
     * Gets the classical volume of the bounding box.
     * */
    val capacity get() = width * height * depth

    /**
     * Gets the surface area of the bounding box.
     * */
    val surface get() = 2.0 * (width * height + depth * height + width * depth)


    fun inflated(amountX: Double, amountY: Double, amountZ: Double) = OrientedBoundingBox3d(
        transform,
        Vector3d(this.halfSize.x + amountX, this.halfSize.y + amountY, this.halfSize.z - amountZ),
    )

    fun inflated(amount: Vector3d) = inflated(amount.x, amount.y, amount.z)

    fun inflated(amount: Double) = inflated(amount, amount, amount)

    fun inflatedBy(percent: Double) = inflated(width * percent, height * percent, depth * percent)

    /**
     * Transitions the [point] into the local space of the bounding box.
     * */
    fun transformLocal(point: Vector3d) = transform.rotation.inverse * (point - transform.translation)

    /**
     * Evaluates the mode of containment of the [other] oriented bounding box inside this box.
     * */
    fun evaluateContainment(other: OrientedBoundingBox3d) = evaluateContainmentRelative(this.halfSize, other.halfSize, other.transform / this.transform)

    /**
     * Evaluates the mode of containment of the axis-aligned [box] inside this box.
     * */
    fun evaluateContainment(box: BoundingBox3d) = evaluateContainment(createFromBoundingBox(box))

    /**
     * Evaluates the mode of containment of the [sphere] inside this box.
     * */
    fun evaluateContainment(sphere: BoundingSphere3d) : ContainmentMode {
        val localSphere = transformLocal(sphere.origin)

        var dx = abs(localSphere.x) - halfSize.x
        var dy = abs(localSphere.y) - halfSize.y
        var dz = abs(localSphere.z) - halfSize.z

        // Check for full containment
        val radius = sphere.radius

        if (dx <= -radius && dy <= -radius && dz <= -radius) {
            return ContainmentMode.ContainedFully
        }

        // Compute distance in each dimension
        dx = max(dx, 0.0)
        dy = max(dy, 0.0)
        dz = max(dz, 0.0)

        if (dx * dx + dy * dy + dz * dz >= radius * radius) {
            return ContainmentMode.Disjoint
        }

        return ContainmentMode.Intersected
    }

    /**
     * Checks if [other] intersects this box.
     * */
    infix fun intersectsWith(other: OrientedBoundingBox3d) = evaluateContainment(other) != ContainmentMode.Disjoint

    /**
     * Checks if [box] intersects this box.
     * */
    infix fun intersectsWith(box: BoundingBox3d) = evaluateContainment(box) != ContainmentMode.Disjoint

    /**
     * Checks if [sphere] intersects this box.
     * */
    infix fun intersectsWith(sphere: BoundingSphere3d) = evaluateContainment(sphere) != ContainmentMode.Disjoint

    /**
     * Checks if [other] is contained fully in this box.
     * */
    infix fun contains(other: OrientedBoundingBox3d) = evaluateContainment(other) == ContainmentMode.ContainedFully

    /**
     * Checks if [box] is contained fully in this box.
     * */
    infix fun contains(box: BoundingBox3d) = evaluateContainment(box) == ContainmentMode.ContainedFully

    /**
     * Checks if [sphere] is contained fully in this box.
     * */
    infix fun contains(sphere: BoundingSphere3d) = evaluateContainment(sphere) == ContainmentMode.ContainedFully

    /**
     * Checks if [point] is contained within this box.
     * */
    infix fun contains(point: Vector3d) : Boolean {
        val localPoint = transformLocal(point)

        val dx = abs(localPoint.x)
        val dy = abs(localPoint.y)
        val dz = abs(localPoint.z)

        return dx <= halfSize.x && dy <= halfSize.y && dz <= halfSize.z
    }

    /**
     * Iterates the corners of this box.
     * */
    inline fun forEachCorner(consumer: (Vector3d) -> Unit) {
        val rotation = transform.rotation
        val hx = (rotation * Vector3d.unitX) * halfSize.x
        val hy = (rotation * Vector3d.unitY) * halfSize.y
        val hz = (rotation * Vector3d.unitZ) * halfSize.z

        val center = transform.translation
        consumer(center - hx + hy + hz)
        consumer(center + hx + hy + hz)
        consumer(center + hx - hy + hz)
        consumer(center - hx - hy + hz)
        consumer(center - hx + hy - hz)
        consumer(center + hx + hy - hz)
        consumer(center + hx - hy - hz)
        consumer(center - hx - hy - hz)
    }

    fun approxEq(other: OrientedBoundingBox3d, eps: Double = GEOMETRY_COMPARE_EPS) = halfSize.approxEq(other.halfSize, eps) && transform.approxEq(other.transform, eps)

    override fun toString() = "T[${transform.translation}] R[${transform.rotation.ln()}] S[$size]"

    companion object {
        /**
         * Evaluates containment of the box B with half extent [halfB] and transform [transformB] inside the box A with half extent [halfA], axis-aligned and at the origin.
         * */
        fun evaluateContainmentRelative(halfA: Vector3d, halfB: Vector3d, transformB: Pose3d): ContainmentMode {
            val BT = transformB.translation
            val BTa = Vector3d(abs(BT.x), abs(BT.y), abs(BT.z))

            // Transform the extents of B:
            val bx = transformB.rotation * Vector3d.unitX
            val by = transformB.rotation * Vector3d.unitY
            val bz = transformB.rotation * Vector3d.unitZ
            val hxB = bx * halfB.x // x extent of box B
            val hyB = by * halfB.y // y extent of box B
            val hzB = bz * halfB.z // z extent of box B

            // Check for containment first:
            val projXB = abs(hxB.x) + abs(hyB.x) + abs(hzB.x)
            val projYB = abs(hxB.y) + abs(hyB.y) + abs(hzB.y)
            val projZB = abs(hxB.z) + abs(hyB.z) + abs(hzB.z)

            if (BTa.x + projXB <= halfA.x && BTa.y + projYB <= halfA.y && BTa.z + projZB <= halfA.z) {
                return ContainmentMode.ContainedFully
            }

            if (BTa.x > halfA.x + abs(hxB.x) + abs(hyB.x) + abs(hzB.x)) {
                return ContainmentMode.Disjoint
            }

            if (BTa.y > halfA.y + abs(hxB.y) + abs(hyB.y) + abs(hzB.y)) {
                return ContainmentMode.Disjoint
            }

            if (BTa.z > halfA.z + abs(hxB.z) + abs(hyB.z) + abs(hzB.z)) {
                return ContainmentMode.Disjoint
            }

            // Check for separation along the axes box B, hxB/hyB/hzB
            if (abs(BT o bx) > abs(halfA.x * bx.x) + abs(halfA.y * bx.y) + abs(halfA.z * bx.z) + halfB.x) {
                return ContainmentMode.Disjoint
            }

            if (abs(BT o by) > abs(halfA.x * by.x) + abs(halfA.y * by.y) + abs(halfA.z * by.z) + halfB.y){
                return ContainmentMode.Disjoint
            }

            if (abs(BT o bz) > abs(halfA.x * bz.x) + abs(halfA.y * bz.y) + abs(halfA.z * bz.z) + halfB.z) {
                return ContainmentMode.Disjoint
            }

            // a.x ^ b.x = (1,0,0) ^ bX
            var axis = Vector3d(0.0, -bx.z, bx.y)
            if (abs(BT o axis) > abs(halfA.y * axis.y) + abs(halfA.z * axis.z) + abs(axis o hyB) + abs(axis o hzB)) {
                return ContainmentMode.Disjoint
            }

            // a.x ^ b.y = (1,0,0) ^ bY
            axis = Vector3d(0.0, -by.z, by.y)
            if (abs(BT o axis) > abs(halfA.y * axis.y) + abs(halfA.z * axis.z) + abs(axis o hzB) + abs(axis o hxB)) {
                return ContainmentMode.Disjoint
            }

            // a.x ^ b.z = (1,0,0) ^ bZ
            axis = Vector3d(0.0, -bz.z, bz.y)
            if (abs(BT o axis) > abs(halfA.y * axis.y) + abs(halfA.z * axis.z) + abs(axis o hxB) + abs(axis o hyB)) {
                return ContainmentMode.Disjoint
            }

            // a.y ^ b.x = (0,1,0) ^ bX
            axis = Vector3d(bx.z, 0.0, -bx.x)
            if (abs(BT o axis) > abs(halfA.z * axis.z) + abs(halfA.x * axis.x) + abs(axis o hyB) + abs(axis o hzB)) {
                return ContainmentMode.Disjoint
            }

            // a.y ^ b.y = (0,1,0) ^ bY
            axis = Vector3d(by.z, 0.0, -by.x)
            if (abs((BT o axis)) > abs(halfA.z * axis.z) + abs(halfA.x * axis.x) + abs(axis o hzB) + abs(axis o hxB)) {
                return ContainmentMode.Disjoint
            }

            // a.y ^ b.z = (0,1,0) ^ bZ
            axis = Vector3d(bz.z, 0.0, -bz.x)
            if (abs(BT o axis) > abs(halfA.z * axis.z) + abs(halfA.x * axis.x) + abs(axis o hxB) + abs(axis o hyB)) {
                return ContainmentMode.Disjoint
            }

            // a.z ^ b.x = (0,0,1) ^ bX
            axis = Vector3d(-bx.y, bx.x, 0.0)
            if (abs(BT o axis) > abs(halfA.x * axis.x) + abs(halfA.y * axis.y) + abs(axis o hyB) + abs(axis o hzB)) {
                return ContainmentMode.Disjoint
            }

            // a.z ^ b.y = (0,0,1) ^ bY
            axis = Vector3d(-by.y, by.x, 0.0)
            if (abs(BT o axis) > abs(halfA.x * axis.x) + abs(halfA.y * axis.y) + abs(axis o hzB) + abs(axis o hxB)) {
                return ContainmentMode.Disjoint
            }

            // a.z ^ b.z = (0,0,1) ^ bZ
            axis = Vector3d(-bz.y, bz.x, 0.0)
            if (abs(BT o axis) > abs(halfA.x * axis.x) + abs(halfA.y * axis.y) + abs(axis o hxB) + abs(axis o hyB)) {
                return ContainmentMode.Disjoint
            }

            return ContainmentMode.Intersected
        }

        /**
         * Constructs an OBB equivalent to [boundingBox] (the [transform] consist of [BoundingBox3d.center] and [Rotation3d.identity]).
         * */
        fun createFromBoundingBox(boundingBox: BoundingBox3d) = OrientedBoundingBox3d(
            Pose3d(boundingBox.center, Rotation3d.identity),
            boundingBox.halfSize
        )
    }
}

/**
 * Represents a 3D bounding sphere.
 * */
data class BoundingSphere3d(val origin: Vector3d, val radius: Double) {
    val radiusSqr get() = radius * radius

    /**
     * Constructs a [BoundingSphere3d] from the [box].
     * */
    constructor(box: BoundingBox3d) : this(box.center, box.center..box.max)

    /**
     * Computes the union of this sphere and the [other] sphere.
     * @return A sphere that contains both this sphere and the [other] sphere.
     * */
    infix fun unionWith(other: BoundingSphere3d): BoundingSphere3d {
        val dxAB = other.origin - this.origin
        val distance = dxAB.norm

        if (radius + other.radius >= distance) {
            if (radius - other.radius >= distance) {
                return this
            } else if (other.radius - radius >= distance) {
                return other
            }
        }

        val direction = dxAB / distance
        val a = min(-radius, distance - other.radius)
        val r = (max(radius, distance + other.radius) - a) * 0.5

        return BoundingSphere3d(this.origin + direction * (r + a), r)
    }

    /**
     * Checks if the [point] is inside the sphere.
     * */
    fun contains(point: Vector3d) = (origin distanceToSqr point) < radiusSqr

    override fun toString() = "$origin r=$radius"
}

/**
 * Describes an intersection between a ray and a volume.
 * [entry] and [exit] are the arguments to the ray equation that will yield the two points of intersection.
 * */
data class RayIntersection(val entry: Double, val exit: Double)

/**
 * Represents a 3D line segment bounded by the [origin] and with the specified [direction].
 * */
data class Ray3d(val origin: Vector3d, val direction: Vector3d) {
    /**
     * True, if the [origin] is not infinite or NaN, and the [direction] is a ~unit vector. Otherwise, false.
     * */
    val isValid get() = !origin.isNaN && !origin.isInfinity && direction.isUnit

    /**
     * Evaluates the parametric equation of the ray to get the point in space.
     * */
    fun evaluate(t: Double) = origin + direction * t

    /**
     * Gets the intersection point of the ray with the [plane].
     * @return The point of intersection, which lies in the plane and along the normal line of the ray.
     * You will get NaNs and infinities if no intersection occurs; consider [Vector3d.isNaN] and [Vector3d.isInfinity] before using the results.
     * */
    infix fun intersectionWith(plane: Plane3d) = this.origin + this.direction * -((plane.normal o this.origin) + plane.d) / (plane.normal o this.direction)

    /**
     * Evaluates the intersection with the [box].
     * @return A [RayIntersection], if an intersection exists. Otherwise, null.
     * */
    infix fun intersectionWith(box: BoundingBox3d) : RayIntersection? {
        var t1 = 0.0
        var t2 = Double.POSITIVE_INFINITY

        if (direction.x.approxEq(0.0)) {
            if (origin.x < box.min.x || origin.x > box.max.x) {
                return null
            }
        }
        else {
            val r = 1.0 / direction.x
            var a = (box.min.x - origin.x) * r
            var b = (box.max.x - origin.x) * r

            if (a > b) {
                val temp = a
                a = b
                b = temp
            }

            t1 = max(a, t1)
            t2 = min(b, t2)

            if (t1 > t2) {
                return null
            }
        }

        if (direction.y.approxEq(0.0)) {
            if (origin.y < box.min.y || origin.y > box.max.y) {
                return null
            }
        }
        else {
            val r = 1.0 / direction.y
            var a = (box.min.y - origin.y) * r
            var b = (box.max.y - origin.y) * r

            if (a > b) {
                val temp = a
                a = b
                b = temp
            }

            t1 = max(a, t1)
            t2 = min(b, t2)

            if (t1 > t2) {
                return null
            }
        }

        if (direction.z.approxEq(0.0)) {
            if (origin.z < box.min.z || origin.z > box.max.z) {
                return null
            }
        }
        else {
            val r = (1.0 / direction.z)
            var a = (box.min.z - origin.z) * r
            var b = (box.max.z - origin.z) * r

            if (a > b) {
                val temp = a
                a = b
                b = temp
            }

            t1 = max(a, t1)
            t2 = min(b, t2)

            if (t1 > t2) {
                return null
            }
        }

        return RayIntersection(t1, t2)
    }

    /**
     * Evaluates the intersection with the [box].
     * @return A [RayIntersection], if an intersection exists. Otherwise, null.
     * */
    infix fun intersectionWith(box: OrientedBoundingBox3d): RayIntersection? {
        val eps = GEOMETRY_COMPARE_EPS // way more compound error compared to AABB

        val center = box.transform.translation - this.origin

        var tMin = Double.MIN_VALUE
        var tMax = Double.MAX_VALUE

        var axis = box.transform.rotation * Vector3d.unitX

        var oCenter = axis o center
        var oDirection = axis o direction

        val halfSize = box.halfSize

        if (oDirection >= -eps && oDirection <= eps) {
            if (-oCenter - halfSize.x > 0.0 || -oCenter + halfSize.x < 0.0) {
                return null
            }
        }
        else {
            var t1 = (oCenter - halfSize.x) / oDirection
            var t2 = (oCenter + halfSize.x) / oDirection

            if (t1 > t2) {
                val temp = t1
                t1 = t2
                t2 = temp
            }

            if (t1 > tMin) {
                tMin = t1
            }

            if (t2 < tMax) {
                tMax = t2
            }

            if (tMax < 0.0 || tMin > tMax) {
                return null
            }
        }

        axis = box.transform.rotation * Vector3d.unitY

        oCenter = axis o center
        oDirection = axis o this.direction

        if (oDirection >= -eps && oDirection <= eps) {
            if (-oCenter - halfSize.y > 0.0 || -oCenter + halfSize.y < 0.0) {
                return null
            }
        }
        else {
            var t1 = (oCenter - halfSize.y) / oDirection
            var t2 = (oCenter + halfSize.y) / oDirection

            if (t1 > t2) {
                val temp = t1
                t1 = t2
                t2 = temp
            }

            if (t1 > tMin) {
                tMin = t1
            }

            if (t2 < tMax) {
                tMax = t2
            }

            if (tMax < 0.0 || tMin > tMax) {
                return null
            }
        }

        axis = box.transform.rotation * Vector3d.unitZ

        oCenter = axis o center
        oDirection = axis o direction

        if (oDirection >= -eps && oDirection <= eps) {
            if (-oCenter - halfSize.z > 0.0 || -oCenter + halfSize.z < 0.0) {
                return null
            }
        }
        else {
            var t1 = (oCenter - halfSize.z) / oDirection
            var t2 = (oCenter + halfSize.z) / oDirection

            if (t1 > t2) {
                val temp = t1
                t1 = t2
                t2 = temp
            }

            if (t1 > tMin) {
                tMin = t1
            }

            if (t2 < tMax) {
                tMax = t2
            }

            if (tMax < 0.0 || tMin > tMax) {
                return null
            }
        }

        return if(tMin in 0.0..tMax) {
            RayIntersection(tMin, tMax)
        }
        else {
            null
        }
    }

    /**
     * Evaluates the intersection with the [cylinder].
     * @return A [RayIntersection], if an intersection exists. Otherwise, null.
     * The [RayIntersection.exit] is not guaranteed to be on the bounded [cylinder], but it will be on the infinite cylinder passing through [cylinder].
     * */
    infix fun intersectionWith(cylinder: Cylinder3d): RayIntersection? {
        val transform = cylinder.transform.inverse
        val (posX, posY, posZ) = transform * origin
        val (dirX, dirY, dirZ) = transform.rotation * direction

        val radius = cylinder.radius

        val a = dirX * dirX + dirZ * dirZ
        val b = 2.0 * (dirX * posX + dirZ * posZ)
        val c = posX * posX + posZ * posZ - radius * radius

        val Δ = b * b - 4.0 * a * c

        if(Δ < GEOMETRY_NORMALIZED_EPS) {
            return null
        }

        val d = sqrt(Δ)
        var t1 = (-b - d) / (2.0 * a)
        var t2 = (-b + d) / (2.0 * a)

        if(t1 > t2) {
            val temp = t1
            t1 = t2
            t2 = temp
        }

        val hitY = posY + dirY * t1
        val h = 0.5 * cylinder.height

        if(hitY !in -h..h) {
            return null
        }

        return RayIntersection(t1, t2)
    }

    /**
     * Checks if the ray intersects with the [plane].
     * */
    infix fun intersectsWith(plane: Plane3d) : Boolean {
        val intersection = intersectionWith(plane)

        return !intersection.isNaN && !intersection.isInfinity
    }

    /**
     * Checks if the ray intersects with the [box].
     * */
    infix fun intersectsWith(box: BoundingBox3d) = intersectionWith(box) != null

    /**
     * Checks if the ray intersects with the [box].
     * */
    infix fun intersectsWith(box: OrientedBoundingBox3d) = intersectionWith(box) != null

    /**
     * Checks if the ray intersects with the [cylinder].
     * */
    infix fun intersectsWith(cylinder: Cylinder3d) = intersectionWith(cylinder) != null

    companion object {
        /**
         * Calculates a ray from a starting point [source] and a point [destination], that the ray shall pass through.
         * You will get NaNs and infinities if the source and destination are NaNs, infinities, or they are ~close to each other.
         * */
        fun fromSourceAndDestination(source: Vector3d, destination: Vector3d) = Ray3d(source, source directionTo destination)
    }
}

/**
 * Represents 3D a line segment bounded by 2 points along it.
 * */
data class Line3d(val origin: Vector3d, val direction: Vector3d, val length: Double) {
    /**
     * True, if the [origin] is not infinite or NaN, the [direction] is a ~unit vector, and the distance is not infinite or NaN. Otherwise, false.
     * */
    val isValid get() = !origin.isNaN && !origin.isInfinity && direction.isUnit && !length.isNaN() && !length.isInfinite()

    /**
     * Evaluates a parametric equation of the line to get the point in space.
     * This will yield [origin] at [t]=0 and [end] at [t]=[length].
     * */
    fun evaluate(t: Double) = origin + direction * t

    /**
     * Gets the end point of the line.
     * */
    val end get() = evaluate(length)

    /**
     * Gets the center point of the line.
     * */
    val center get() = evaluate(0.5 * length)

    /**
     * Gets a ray starting at [origin] towards [end].
     * */
    val ray get() = Ray3d(origin, direction)

    /**
     * Evaluates the intersection with the [box].
     * @return A [RayIntersection], if an intersection exists. Otherwise, null. *Non-null results do not guarantee that the exit point is within this line segment.*
     * */
    infix fun intersectionWith(box: BoundingBox3d): RayIntersection? {
        val intersection = (this.ray intersectionWith box)
            ?: return null

        if(intersection.entry !in 0.0..length) {
            return null
        }

        return intersection
    }

    /**
     * Evaluates the intersection with the [box].
     * @return A [RayIntersection], if an intersection exists. Otherwise, null. *Non-null results do not guarantee that the exit point is within this line segment.*
     * */
    infix fun intersectionWith(box: OrientedBoundingBox3d): RayIntersection? {
        val intersection = (this.ray intersectionWith box)
            ?: return null

        if(intersection.entry !in 0.0..length) {
            return null
        }

        return intersection
    }

    /**
     * Evaluates the intersection with the [cylinder].
     * @return A [RayIntersection], if an intersection exists. Otherwise, null. *Non-null results do not guarantee that the exit point is within this line segment.*
     * */
    infix fun intersectionWith(cylinder: Cylinder3d): RayIntersection? {
        val intersection = (this.ray intersectionWith cylinder)
            ?: return null

        if(intersection.entry !in 0.0..length) {
            return null
        }

        return intersection
    }

    /**
     * Checks if the ray intersects with the [box].
     * */
    infix fun intersectsWith(box: BoundingBox3d) = intersectionWith(box) != null

    /**
     * Checks if the ray intersects with the [box].
     * */
    infix fun intersectsWith(box: OrientedBoundingBox3d) = intersectionWith(box) != null

    /**
     * Checks if the ray intersects with the [cylinder].
     * */
    infix fun intersectsWith(cylinder: Cylinder3d) = intersectionWith(cylinder) != null

    companion object {
        /**
         * Creates a line from the a [start] point and an [end] point.
         * */
        fun fromStartEnd(start: Vector3d, end: Vector3d) : Line3d {
            val dx = end - start
            val distance = dx.norm
            val direction = dx / distance

            return Line3d(start, direction, distance)
        }
    }
}

/**
 * Represents a 3D cylinder, whose two ends are determined by a line segment, [extent], and whose radius is [radius].
 * */
data class Cylinder3d(val extent: Line3d, val radius: Double) {
    /**
     * Gets the center point of the cylinder.
     * */
    val center get() = extent.center

    /**
     * Gets the height of the cylinder (distance between the centers of the two caps).
     * */
    val height get() = extent.length

    /**
     * Gets a transform that would transform a cylinder with center at 0, 0, 0 and [radius] into this one.
     * */
    val transform get() = Pose3d(center, Rotation3d.createFromTwoVectors(Vector3d.unitY, extent.direction))

    /**
     * Checks if the cylinder intersects with the [box].
     * */
    infix fun intersectsWith(box: BoundingBox3d) = extent intersectsWith box.inflated(radius)

    /**
     * Checks if the cylinder intersects with the [box].
     * */
    infix fun intersectsWith(box: OrientedBoundingBox3d) = extent intersectsWith box.inflated(radius)
}

/**
 * Describes the mode of intersection between a plane and another object.
 * */
enum class PlaneIntersectionType {
    /**
     * The plane and object do not intersect, and the object is in the positive half-space.
     * */
    Positive,
    /**
     * The plane and object do not intersect, and the object is in the negative half-space.
     * */
    Negative,
    /**
     * The plane and object intersect.
     * */
    Intersects
}

/**
 * Represents a 3D Plane.
 * @param normal The normal of the plane.
 * @param d The distance from the origin along the normal to the plane.
 * *The distance is signed and is not what you would expect:*
 *
 * Plane Equation:
 * (n **o** v) + d = 0 -> d = -(n **o** v)
 * */
data class Plane3d(val normal: Vector3d, val d: Double) {
    /**
     * True, if the [normal] is a ~unit vector, and the distance [d] is not infinite or NaN. Otherwise, false.
     * */
    val isValid get() = normal.isUnit && !d.isInfinite() && !d.isNaN()

    /**
     * Constructs a [Plane3d] from the normal vector and distance from origin.
     * @param x The X component of the normal.
     * @param y The Y component of the normal.
     * @param z The Z component of the normal.
     * @param w The distance from the origin.
     * */
    constructor(x: Double, y: Double, z: Double, w: Double) : this(Vector3d(x, y, z), w)

    /**
     * Constructs a [Plane3d] from the normal vector and a point in the plane.
     * @param normal The normal of the plane.
     * @param point A point that lies within the plane.
     * */
    constructor(normal: Vector3d, point: Vector3d) : this(normal, -(point o normal))

    /**
     * Calculates the distance between the plane and the [point].
     * If the point is in the plane, the result is 0.
     * The distance is signed; if the point is above the plane, it will be positive. If below the plane, it will be negative.
     * */
    fun signedDistanceToPoint(point: Vector3d) = (normal o point) + d

    /**
     * Calculates the distance between the plane and the [point].
     * If the point is in the plane, the result is 0.
     * */
    fun distanceToPoint(point: Vector3d) = abs(signedDistanceToPoint(point))

    /**
     * Evaluates the mode of intersection with the [point].
     * Here, [PlaneIntersectionType.Intersects] means that the plane ~contains the point.
     * @param eps The tolerance. If the distance to the point is below this tolerance, it is considered that the plane contains the point.
     * */
    fun evaluateIntersection(point: Vector3d, eps: Double = GEOMETRY_COMPARE_EPS) : PlaneIntersectionType {
        val distance = signedDistanceToPoint(point)

        if(distance > eps) {
            return PlaneIntersectionType.Positive
        }

        if(distance < -eps) {
            return PlaneIntersectionType.Negative
        }

        return PlaneIntersectionType.Intersects
    }

    /**
     * Checks if this plane contains the [point].
     * */
    infix fun contains(point: Vector3d) = evaluateIntersection(point) == PlaneIntersectionType.Intersects

    /**
     * Evaluates the mode of intersection with the [box].
     * */
    fun evaluateIntersection(box: BoundingBox3d) : PlaneIntersectionType {
        val nx = normal.x
        val ny = normal.y
        val nz = normal.z

        val x1 = if (nx >= 0.0) box.min.x else box.max.x
        val y1 = if (ny >= 0.0) box.min.y else box.max.y
        val z1 = if (nz >= 0.0) box.min.z else box.max.z
        val x2 = if (nx >= 0.0) box.max.x else box.min.x
        val y2 = if (ny >= 0.0) box.max.y else box.min.y
        val z2 = if (nz >= 0.0) box.max.z else box.min.z

        if (nx * x1 + ny * y1 + nz * z1 + d > 0.0) {
            return PlaneIntersectionType.Positive
        }

        if (nx * x2 + ny * y2 + nz * z2 + d < 0.0) {
            return PlaneIntersectionType.Negative
        }

        return PlaneIntersectionType.Intersects
    }

    /**
     * Evaluates the mode of intersection with the [box].
     * */
    fun evaluateIntersection(box: OrientedBoundingBox3d) : PlaneIntersectionType {
        val distance = signedDistanceToPoint(box.center)

        val localNormal = box.transform.rotation.inverse * normal

        val dx = abs(box.halfSize.x * localNormal.x)
        val dy = abs(box.halfSize.y * localNormal.y)
        val dz = abs(box.halfSize.z * localNormal.z)
        val radius = dx + dy + dz

        if (distance > radius) {
            return PlaneIntersectionType.Positive
        }

        if (distance < -radius) {
            return PlaneIntersectionType.Negative
        }

        return PlaneIntersectionType.Intersects
    }

    /**
     * Evaluates the mode of intersection with the [sphere].
     * */
    fun evaluateIntersection(sphere: BoundingSphere3d) = evaluateIntersection(sphere.origin, sphere.radius)

    /**
     * Checks if the plane intersects with the [box].
     * @return True, if the plane cuts the box. Otherwise, false.
     * */
    infix fun intersectsWith(box: BoundingBox3d) = evaluateIntersection(box) == PlaneIntersectionType.Intersects

    /**
     * Checks if the plane intersects with the [box].
     * @return True, if the plane cuts the box. Otherwise, false.
     * */
    infix fun intersectsWith(box: OrientedBoundingBox3d) = evaluateIntersection(box) == PlaneIntersectionType.Intersects

    /**
     * Checks if the plane intersects with the [sphere].
     * @return True, if the plane cuts the sphere. Otherwise, false.
     * */
    infix fun intersectsWith(sphere: BoundingSphere3d) = evaluateIntersection(sphere) == PlaneIntersectionType.Intersects

    fun approxEq(other: Plane3d, eps: Double = GEOMETRY_COMPARE_EPS) = normal.approxEq(other.normal, eps) && d.approxEq(other.d, eps)

    override fun toString() = "$normal, d=$d"

    companion object {
        val unitX = Plane3d(Vector3d.unitX, 0.0)
        val unitY = Plane3d(Vector3d.unitY, 0.0)
        val unitZ = Plane3d(Vector3d.unitZ, 0.0)

        /**
         * Creates a plane that contains the specified points. The returned plane is valid if the points form a non-degenerate triangle.
         * */
        fun createFromVertices(a: Vector3d, b: Vector3d, c: Vector3d) : Plane3d {
            val ax = b.x - a.x
            val ay = b.y - a.y
            val az = b.z - a.z

            val bx = c.x - a.x
            val by = c.y - a.y
            val bz = c.z - a.z

            val nx = ay * bz - az * by
            val ny = az * bx - ax * bz
            val nz = ax * by - ay * bx

            val n = 1.0 / sqrt(nx * nx + ny * ny + nz * nz)

            val normal = Vector3d(nx * n, ny * n, nz * n)
            val d = -(normal.x * a.x + normal.y * a.y + normal.z * a.z)

            return Plane3d(normal, d)
        }
    }
}