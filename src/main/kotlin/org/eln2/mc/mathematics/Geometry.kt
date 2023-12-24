@file:Suppress("LocalVariableName", "NOTHING_TO_INLINE")

package org.eln2.mc.mathematics

import net.minecraft.core.BlockPos
import org.ageseries.libage.mathematics.Ray3d
import org.ageseries.libage.mathematics.Vector3d
import org.ageseries.libage.mathematics.Vector3di
import org.ageseries.libage.mathematics.o
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.floor
import kotlin.math.sqrt

data class Vector2I(val x: Int, val y: Int) {
    companion object {
        fun one(): Vector2I = Vector2I(1, 1)
        fun zero(): Vector2I = Vector2I(0, 0)
    }

    operator fun plus(other: Vector2I): Vector2I = Vector2I(x + other.x, y + other.y)

    fun toVector2F(): Vector2F = Vector2F(x.toFloat(), y.toFloat())
}

data class Vector2F(val x: Float, val y: Float) {
    companion object {
        fun one(): Vector2F = Vector2F(1f, 1f)
        fun zero(): Vector2F = Vector2F(0f, 0f)
    }

    fun toVector2I(): Vector2I {
        return Vector2I(x.toInt(), y.toInt())
    }
}

data class Rectangle4I(val x: Int, val y: Int, val width: Int, val height: Int) {
    constructor(pos: Vector2I, width: Int, height: Int) : this(pos.x, pos.y, width, height)
    constructor(pos: Vector2I, size: Vector2I) : this(pos.x, pos.y, size.x, size.y)
    constructor(x: Int, y: Int, size: Vector2I) : this(x, y, size.x, size.y)

    val left get() = x
    val right get() = x + width
    val top get() = y
    val bottom get() = y + height
}

data class Rectangle4F(val x: Float, val y: Float, val width: Float, val height: Float) {
    constructor(pos: Vector2F, width: Float, height: Float) : this(pos.x, pos.y, width, height)
    constructor(pos: Vector2F, size: Vector2F) : this(pos.x, pos.y, size.x, size.y)
    constructor(x: Float, y: Float, size: Vector2F) : this(x, y, size.x, size.y)

    val left get() = x
    val right get() = x + width
    val top get() = y
    val bottom get() = y + height
}

fun Vector3d.floorBlockPos() = BlockPos(floor(this.x).toInt(), floor(this.y).toInt(), floor(this.z).toInt())

/**
 * Computes the surface area of a cylinder with specified [length] and [radius].
 * */
fun cylinderSurfaceArea(length: Double, radius: Double): Double {
    return 2 * PI * radius * length + 2 * PI * radius * radius
}

@JvmInline
value class BlockPosInt(val value: Int) {
    constructor(x: Int, y: Int, z: Int) : this(pack(x, y, z))

    val x get() = unpackX(value)
    val y get() = unpackY(value)
    val z get() = unpackZ(value)

    val isZero get() = value == 0

    fun toBlockPos() = unpackBlockPos(value)

    operator fun not() = value

    companion object {
        val zero = pack(0, 0, 0)
        const val MAX_VALUE = +511
        const val MIN_VALUE = -511
        const val SIGN_BIT = 1 shl 9
        const val SIGN_TABLE = (-1L shl 32) or 1

        inline fun packMember(value: Int): Int {
            val bit = value shr 31
            val mod = (value + bit) xor bit
            return ((value ushr 31) shl 9) or mod
        }

        inline fun unpackMember(value: Int): Int {
            val idx = (value and SIGN_BIT) ushr 4
            val sign = (SIGN_TABLE ushr idx).toInt()
            return sign * (value and MAX_VALUE)
        }

        inline fun pack(x: Int, y: Int, z: Int): Int {
            if(x < MIN_VALUE || x > MAX_VALUE || y < MIN_VALUE || y > MAX_VALUE || z < MIN_VALUE || z > MAX_VALUE) {
                error("XYZ $x $y $z are out of bounds")
            }

            val i = packMember(x)
            val j = packMember(y) shl 10
            val k = packMember(z) shl 20

            return i or j or k
        }

        inline fun pack(blockPos: BlockPos) = pack(blockPos.x, blockPos.y, blockPos.z)

        inline fun pack(vector: Vector3di) = pack(vector.x, vector.y, vector.z)

        inline fun of(x: Int, y: Int, z: Int) = BlockPosInt(pack(x, y, z))

        inline fun of(blockPos: BlockPos) = BlockPosInt(pack(blockPos.x, blockPos.y, blockPos.z))

        inline fun unpackX(v: Int) = unpackMember(v)

        inline fun unpackY(v: Int) = unpackMember(v shr 10)

        inline fun unpackZ(v: Int) = unpackMember(v shr 20)

        inline fun unpackBlockPos(value: Int) = BlockPos(unpackX(value), unpackY(value), unpackZ(value))
    }
}

/**
 * 3D Plane. TODO add to libage
 * @param normal The normal of the plane.
 * @param d The distance from the origin along the normal to the plane.
 *
 * */
data class Plane3d(val normal: Vector3d, val d: Double) {
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
    fun distanceToPoint(point: Vector3d) = signedDistanceToPoint(point).absoluteValue

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

/**
 * Gets the intersection point of the ray with the [plane].
 * @return The point of intersection, a point which lines in the plane and the normal line of the ray. You will get NaNs and infinities if no intersection occurs.
 * */
infix fun Ray3d.intersectionWith(plane: Plane3d) = this.origin + this.direction * -((plane.normal o this.origin) + plane.d) / (plane.normal o this.direction)
