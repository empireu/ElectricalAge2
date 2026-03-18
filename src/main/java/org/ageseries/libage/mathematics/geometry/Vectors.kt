@file:Suppress("LocalVariableName", "MemberVisibilityCanBePrivate")

package org.ageseries.libage.mathematics.geometry

import org.ageseries.libage.mathematics.*
import kotlin.math.*

data class Vector2di(val x: Int, val y: Int) {
    constructor(value: Int) : this(value, value)

    operator fun unaryPlus() = this
    operator fun unaryMinus() = Vector2di(-x, -y)
    operator fun times(b: Int) = Vector2di(x * b, y * b)
    operator fun div(b: Int) = Vector2di(x / b, y / b)
    operator fun minus(b: Vector2di) = Vector2di(x - b.x, y - b.y)
    operator fun plus(b: Vector2di) = Vector2di(x + b.x, y + b.y)

    companion object {
        val zero = Vector2di(0, 0)
        val one = Vector2di(1, 1)
        val unitX = Vector2di(1, 0)
        val unitY = Vector2di(0, 1)

        fun manhattan(a: Vector2di, b: Vector2di) : Int {
            val dx = abs(a.x - b.x)
            val dy = abs(a.y - b.y)

            return dx + dy
        }
    }
}

data class Vector2d(val x: Double, val y: Double) {
    constructor(value: Double) : this(value, value)

    val isNaN get() = x.isNaN() || y.isNaN()
    val isInfinity get() = x.isInfinite() || y.isInfinite()

    infix fun dot(b: Vector2d) = x * b.x + y * b.y
    val normSqr get() = this dot this
    val norm get() = sqrt(normSqr)
    infix fun distanceTo(b: Vector2d) = (this - b).norm
    infix fun distanceToSqr(b: Vector2d) = (this - b).normSqr
    fun nz() = Vector2d(x.nz(), y.nz())
    fun normalized() = this / norm
    fun normalizedNz() = this.nz() / norm.nz()
    val perpendicularLeft get() = Vector2d(-y, x)
    val perpendicularRight get() = Vector2d(y, -x)

    fun approxEq(other: Vector2d, eps: Double = GEOMETRY_COMPARE_EPS) = x.approxEq(other.x, eps) && y.approxEq(other.y, eps)

    override fun toString() = "x=$x, y=$y"

    operator fun unaryPlus() = this
    operator fun unaryMinus() = Vector2d(-x, -y)
    operator fun plus(other: Vector2d) = Vector2d(x + other.x, y + other.y)
    operator fun minus(other: Vector2d) = Vector2d(x - other.x, y - other.y)
    operator fun times(other: Vector2d) = Vector2d(x * other.x, y * other.y)
    operator fun div(other: Vector2d) = Vector2d(x / other.x, y / other.y)
    operator fun times(scalar: Double) = Vector2d(x * scalar, y * scalar)
    operator fun div(scalar: Double) = Vector2d(x / scalar, y / scalar)

    operator fun compareTo(other: Vector2d) = this.normSqr.compareTo(other.normSqr)

    companion object {
        val zero = Vector2d(0.0, 0.0)
        val one = Vector2d(1.0, 1.0)
        val unitX = Vector2d(1.0, 0.0)
        val unitY = Vector2d(0.0, 1.0)

        fun lerp(a: Vector2d, b: Vector2d, t: Double) = Vector2d(
            lerp(a.x, b.x, t),
            lerp(a.y, b.y, t)
        )

        fun min(a: Vector2d, b: Vector2d) = Vector2d(min(a.x, b.x), min(a.y, b.y))

        fun max(a: Vector2d, b: Vector2d) = Vector2d(max(a.x, b.x), max(a.y, b.y))
    }
}

fun Double.plus(vector2d: Vector2d) = Vector2d(this + vector2d.x, this + vector2d.y)
fun Double.minus(vector2d: Vector2d) = Vector2d(this - vector2d.x, this - vector2d.y)
fun Double.times(vector2d: Vector2d) = Vector2d(this * vector2d.x, this * vector2d.y)
fun Double.div(vector2d: Vector2d) = Vector2d(this / vector2d.x, this / vector2d.y)

infix fun Vector2d.o(other: Vector2d) = this dot other
operator fun Vector2d.rangeTo(b: Vector2d) = this distanceTo b

data class Vector2dDual(val x: Dual, val y: Dual) {
    constructor(value: Dual) : this(value, value)

    constructor(values: List<Vector2d>) : this(
        Dual(values.map { it.x }),
        Dual(values.map { it.y })
    )

    init {
        require(x.size == y.size) { "Dual X and Y must be of the same size" }
        require(x.size > 0) { "X and Y must not be empty" }
    }

    val size get() = x.size
    val isReal get() = size == 1
    infix fun dot(b: Vector2dDual) = x * b.x + y * b.y
    val normSqr get() = this dot this
    val norm get() = sqrt(normSqr)
    fun normalized() = this / norm
    val value get() = Vector2d(x.value, y.value)
    fun head(n: Int = 1) = Vector2dDual(x.head(n), y.head(n))
    fun tail(n: Int = 1) = Vector2dDual(x.tail(n), y.tail(n))

    override fun toString() = "x=$x, y=$y"

    operator fun unaryPlus() = this
    operator fun unaryMinus() = Vector2dDual(-x, -y)
    operator fun plus(other: Vector2dDual) = Vector2dDual(x + other.x, y + other.y)
    operator fun minus(other: Vector2dDual) = Vector2dDual(x - other.x, y - other.y)
    operator fun times(other: Vector2dDual) = Vector2dDual(x * other.x, y * other.y)
    operator fun div(other: Vector2dDual) = Vector2dDual(x / other.x, y / other.y)
    operator fun times(scalar: Dual) = Vector2dDual(x * scalar, y * scalar)
    operator fun div(scalar: Dual) = Vector2dDual(x / scalar, y / scalar)
    operator fun times(constant: Double) = Vector2dDual(x * constant, y * constant)
    operator fun div(constant: Double) = Vector2dDual(x / constant, y / constant)
    operator fun get(n: Int) = Vector2d(x[n], y[n])

    companion object {
        fun const(x: Double, y: Double, n: Int = 1) = Vector2dDual(Dual.const(x, n), Dual.const(y, n))
        fun const(value: Vector2d, n: Int = 1) = const(value.x, value.y, n)
        fun of(vararg values: Vector2d) = Vector2dDual(values.asList())
    }
}

infix fun Vector2dDual.o(other: Vector2dDual) = this dot other

data class Vector3di(val x: Int, val y: Int, val z: Int) {
    constructor(value: Int) : this(value, value, value)

    operator fun unaryPlus() = this
    operator fun unaryMinus() = Vector3di(-x, -y, -z)
    operator fun times(b: Int) = Vector3di(x * b, y * b, z * b)
    operator fun div(b: Int) = Vector3di(x / b, y / b, z / b)
    operator fun minus(b: Vector3di) = Vector3di(x - b.x, y - b.y, z - b.z)
    operator fun plus(b: Vector3di) = Vector3di(x + b.x, y + b.y, z + b.z)

    companion object {
        val zero = Vector3di(0, 0, 0)
        val one = Vector3di(1, 1, 1)
        val unitX = Vector3di(1, 0, 0)
        val unitY = Vector3di(0, 1, 0)
        val unitZ = Vector3di(0, 0, 1)

        fun manhattan(a: Vector3di, b: Vector3di) : Int {
            val dx = abs(a.x - b.x)
            val dy = abs(a.y - b.y)
            val dz = abs(a.z - b.z)

            return dx + dy + dz
        }
    }
}

data class Vector3d(val x: Double, val y: Double, val z: Double) {
    constructor(x: Int, y: Int, z: Int) : this(x.toDouble(), y.toDouble(), z.toDouble())
    constructor(value: Double) : this(value, value, value)

    val isNaN get() = x.isNaN() || y.isNaN() || z.isNaN()
    val isInfinity get() = x.isInfinite() || y.isInfinite() || z.isInfinite()

    infix fun dot(b: Vector3d) = x * b.x + y * b.y + z * b.z
    val normSqr get() = this dot this
    val norm get() = sqrt(normSqr)
    val isUnit get() = normSqr.approxEq(1.0, GEOMETRY_NORMALIZED_EPS)
    infix fun distanceTo(b: Vector3d) = (this - b).norm
    infix fun distanceToSqr(b: Vector3d) = (this - b).normSqr
    infix fun cosAngle(b: Vector3d) = ((this dot b) / (this.norm * b.norm)).coerceIn(-1.0, 1.0)
    infix fun angle(b: Vector3d) = acos(this cosAngle b)

    fun nz() = Vector3d(x.nz(), y.nz(), z.nz())
    fun normalized() = this / norm
    fun normalizedNz() = this.nz() / norm.nz()

    infix fun cross(b: Vector3d) = Vector3d(
        this.y * b.z - this.z * b.y,
        this.z * b.x - this.x * b.z,
        this.x * b.y - this.y * b.x
    )

    fun approxEq(other: Vector3d, eps: Double = GEOMETRY_COMPARE_EPS) = x.approxEq(other.x, eps) && y.approxEq(other.y, eps) && z.approxEq(other.z, eps)

    override fun toString() = "x=$x, y=$y, z=$z"

    operator fun not() = if(this.isUnit) {
        this
    } else if(this != zero) {
        this.normalized()
    } else {
        this
    }

    operator fun unaryPlus() = this
    operator fun unaryMinus() = Vector3d(-x, -y, -z)
    operator fun plus(b: Vector3d) = Vector3d(x + b.x, y + b.y, z + b.z)
    operator fun plus(b: Double) = Vector3d(x + b, y + b, z + b)
    operator fun minus(b: Vector3d) = Vector3d(x - b.x, y - b.y, z - b.z)
    operator fun minus(b: Double) = Vector3d(x - b, y - b, z - b)
    operator fun times(b: Vector3d) = Vector3d(x * b.x, y * b.y, z * b.z)
    operator fun times(scalar: Double) = Vector3d(x * scalar, y * scalar, z * scalar)
    operator fun div(b: Vector3d) = Vector3d(x / b.x, y / b.y, z / b.z)
    operator fun div(scalar: Double) = Vector3d(x / scalar, y / scalar, z / scalar)
    operator fun compareTo(other: Vector3d) = this.normSqr.compareTo(other.normSqr)

    fun projectOnPlane(n: Vector3d) = this - n * ((this dot n) / n.normSqr)
    fun projectOnVector(v: Vector3d) = if (this == zero || v == zero) zero else v * (this dot v) / v.normSqr

    fun frac() = Vector3d(frac(this.x), frac(this.y), frac(this.z))
    fun floor() = Vector3d(floor(this.x), floor(this.y), floor(this.z))
    fun ceil() = Vector3d(ceil(this.x), ceil(this.y), ceil(this.z))
    fun round() = Vector3d(round(this.x), round(this.y), round(this.z))
    fun floorInt() = Vector3di(floor(this.x).toInt(), floor(this.y).toInt(), floor(this.z).toInt())
    fun ceilInt() = Vector3di(ceil(this.x).toInt(), ceil(this.y).toInt(), ceil(this.z).toInt())
    fun roundInt() = Vector3di(round(this.x).toInt(), round(this.y).toInt(), round(this.z).toInt())
    fun intCast() = Vector3di(x.toInt(), y.toInt(), z.toInt())

    fun perpendicular() : Vector3d {
        val (x, y, z) = if(!this.isUnit && this != zero) {
            this.normalized()
        }
        else {
            this
        }

        val result = if (abs(y + z) > GEOMETRY_NORMALIZED_EPS || abs(x) > GEOMETRY_NORMALIZED_EPS) {
            Vector3d(-y - z, x, x)
        }
        else {
            Vector3d(z, z, -x - y)
        }

        return result.normalized()
    }

    // pixar
    fun onb() : Pair<Vector3d, Vector3d> {
        val sign = z.sign
        val a = -1.0 / (sign + z)
        val b = x * y * a

        return Pair(
            Vector3d(
                1.0 + sign * x * x * a,
                sign * b,
                -sign * x
            ),
            Vector3d(
                b,
                sign + y * y * a,
                -y
            )
        )
    }

    companion object {
        val zero = Vector3d(0.0, 0.0, 0.0)
        val one = Vector3d(1.0, 1.0, 1.0)
        val unitX = Vector3d(1.0, 0.0, 0.0)
        val unitY = Vector3d(0.0, 1.0, 0.0)
        val unitZ = Vector3d(0.0, 0.0, 1.0)

        fun lerp(from: Vector3d, to: Vector3d, t: Double) = Vector3d(
            lerp(from.x, to.x, t),
            lerp(from.y, to.y, t),
            lerp(from.z, to.z, t)
        )

        fun min(a: Vector3d, b: Vector3d) = Vector3d(
            min(a.x, b.x),
            min(a.y, b.y),
            min(a.z, b.z)
        )

        fun max(a: Vector3d, b: Vector3d) = Vector3d(
            max(a.x, b.x),
            max(a.y, b.y),
            max(a.z, b.z)
        )
    }
}

fun Double.plus(vector3d: Vector3d) = Vector3d(this + vector3d.x, this + vector3d.y, this + vector3d.z)
fun Double.minus(vector3d: Vector3d) = Vector3d(this - vector3d.x, this - vector3d.y, this - vector3d.z)
fun Double.times(vector3d: Vector3d) = Vector3d(this * vector3d.x, this * vector3d.y, this * vector3d.z)
fun Double.div(vector3d: Vector3d) = Vector3d(this / vector3d.x, this / vector3d.y, this / vector3d.z)

infix fun Vector3d.o(other: Vector3d) = this dot other
infix fun Vector3d.x(other: Vector3d) = this cross other
operator fun Vector3d.rangeTo(b: Vector3d) = this distanceTo b
infix fun Vector3d.directionTo(b: Vector3d) = (b - this).normalized()

data class Vector3dDual(val x: Dual, val y: Dual, val z: Dual) {
    constructor(value: Dual) : this(value, value, value)

    constructor(values: List<Vector3d>) : this(
        Dual(values.map { it.x }),
        Dual(values.map { it.y }),
        Dual(values.map { it.z })
    )

    init {
        require(x.size == y.size && y.size == z.size) {
            "Dual X, Y and Z must be of the same size"
        }
    }

    val size get() = x.size
    val isReal get() = size == 1
    infix fun dot(b: Vector3d) = x * b.x + y * b.y + z * b.z
    infix fun dot(b: Vector3dDual) = x * b.x + y * b.y + z * b.z
    val normSqr get() = this dot this
    val norm get() = sqrt(normSqr)
    fun normalized() = this / norm

    infix fun cross(b: Vector3d) = Vector3dDual(
        this.y * b.z - this.z * b.y,
        this.z * b.x - this.x * b.z,
        this.x * b.y - this.y * b.x
    )

    infix fun cross(b: Vector3dDual) = Vector3dDual(
        this.y * b.z - this.z * b.y,
        this.z * b.x - this.x * b.z,
        this.x * b.y - this.y * b.x
    )

    val value get() = Vector3d(x.value, y.value, z.value)
    fun head(n: Int = 1) = Vector3dDual(x.head(n), y.head(n), z.head(n))
    fun tail(n: Int = 1) = Vector3dDual(x.tail(n), y.tail(n), z.tail(n))

    fun projectOnPlane(n: Vector3dDual) = this - n * ((this dot n) / n.normSqr)
    fun projectOnPlane(n: Vector3d) = projectOnPlane(const(n, size))
    fun projectOnVector(v: Vector3dDual) = v * (this dot v) / v.normSqr
    fun projectOnVector(v: Vector3d) = projectOnVector(const(v, size))

    operator fun unaryPlus() = this
    operator fun unaryMinus() = Vector3dDual(-x, -y, -z)
    operator fun plus(other: Vector3dDual) = Vector3dDual(x + other.x, y + other.y, z + other.z)
    operator fun plus(other: Vector3d) = Vector3dDual(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3dDual) = Vector3dDual(x - other.x, y - other.y, z - other.z)
    operator fun minus(other: Vector3d) = Vector3dDual(x - other.x, y - other.y, z - other.z)
    operator fun times(other: Vector3dDual) = Vector3dDual(x * other.x, y * other.y, z * other.z)
    operator fun times(other: Vector3d) = Vector3dDual(x * other.x, y * other.y, z * other.z)
    operator fun div(other: Vector3dDual) = Vector3dDual(x / other.x, y / other.y, z / other.z)
    operator fun div(other: Vector3d) = Vector3dDual(x / other.x, y / other.y, z / other.z)
    operator fun times(scalar: Dual) = Vector3dDual(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Dual) = Vector3dDual(x / scalar, y / scalar, z / scalar)
    operator fun times(constant: Double) = Vector3dDual(x * constant, y * constant, z * constant)
    operator fun div(constant: Double) = Vector3dDual(x / constant, y / constant, z / constant)
    operator fun get(n: Int) = Vector3d(x[n], y[n], z[n])

    companion object {
        fun const(x: Double, y: Double, z: Double, n: Int = 1) =
            Vector3dDual(Dual.const(x, n), Dual.const(y, n), Dual.const(z, n))
        fun const(value: Vector3d, n: Int = 1) = const(value.x, value.y, value.z, n)
        fun of(vararg values: Vector3d) = Vector3dDual(values.asList())

        fun lerp(from: Vector3dDual, to: Vector3dDual, t: Dual) = Vector3dDual(
            lerp(from.x, to.x, t),
            lerp(from.y, to.y, t),
            lerp(from.z, to.z, t)
        )

        fun lerp(from: Vector3d, to: Vector3d, t: Dual) = lerp(
            const(from, t.size),
            const(to, t.size),
            t
        )
    }
}

fun Vector3dDual.o(other: Vector3d) = this dot other
fun Vector3dDual.o(other: Vector3dDual) = this dot other
fun Vector3dDual.x(other: Vector3d) = this cross other
fun Vector3dDual.x(other: Vector3dDual) = this cross other

data class Vector4d(val x: Double, val y: Double, val z: Double, val w: Double) {
    constructor(value: Double) : this(value, value, value, value)

    val isNaN get() = x.isNaN() || y.isNaN() || z.isNaN() || w.isNaN()
    val isInfinity get() = x.isInfinite() || y.isInfinite() || z.isInfinite() || w.isInfinite()

    infix fun dot(b: Vector4d) = x * b.x + y * b.y + z * b.z + w * b.w
    val normSqr get() = this dot this
    val norm get() = sqrt(normSqr)
    infix fun distanceTo(b: Vector4d) = (this - b).norm
    infix fun distanceToSqr(b: Vector4d) = (this - b).normSqr
    infix fun cosAngle(b: Vector4d) = (this o b) / (this.norm * b.norm)
    infix fun angle(b: Vector4d) = acos((this cosAngle b).coerceIn(-1.0, 1.0))

    fun nz() = Vector4d(x.nz(), y.nz(), z.nz(), w.nz())
    fun normalized() = this / norm
    fun normalizedNz() = this.nz() / norm.nz()

    fun approxEq(other: Vector4d, eps: Double = GEOMETRY_COMPARE_EPS) = x.approxEq(other.x, eps) && y.approxEq(other.y, eps) && z.approxEq(other.z, eps) && w.approxEq(other.w, eps)

    override fun toString() = "x=$x, y=$y, z=$z, w=$w"

    operator fun rangeTo(b: Vector4d) = this distanceTo b
    operator fun unaryPlus() = this
    operator fun unaryMinus() = Vector4d(-x, -y, -z, -w)
    operator fun plus(b: Vector4d) = Vector4d(x + b.x, y + b.y, z + b.z, w + b.w)
    operator fun minus(b: Vector4d) = Vector4d(x - b.x, y - b.y, z - b.z, w - b.w)
    operator fun times(b: Vector4d) = Vector4d(x * b.x, y * b.y, z * b.z, w * b.w)
    operator fun div(b: Vector4d) = Vector4d(x / b.x, y / b.y, z / b.z, w / b.w)
    operator fun rem(b: Vector4d) = this angle b
    operator fun times(scalar: Double) = Vector4d(x * scalar, y * scalar, z * scalar, w * scalar)
    operator fun div(scalar: Double) = Vector4d(x / scalar, y / scalar, z / scalar, w / scalar)

    operator fun compareTo(other: Vector4d) = this.normSqr.compareTo(other.normSqr)

    companion object {
        val zero = Vector4d(0.0, 0.0, 0.0, 0.0)
        val one = Vector4d(1.0, 1.0, 1.0, 1.0)
        val unitX = Vector4d(1.0, 0.0, 0.0, 0.0)
        val unitY = Vector4d(0.0, 1.0, 0.0, 0.0)
        val unitZ = Vector4d(0.0, 0.0, 1.0, 0.0)
        val unitW = Vector4d(0.0, 0.0, 0.0, 1.0)

        fun lerp(a: Vector4d, b: Vector4d, t: Double) = Vector4d(
            lerp(a.x, b.x, t),
            lerp(a.y, b.y, t),
            lerp(a.z, b.z, t),
            lerp(a.w, b.w, t)
        )
    }
}

fun Double.plus(vector4d: Vector4d) = Vector4d(this + vector4d.x, this + vector4d.y, this + vector4d.z, this + vector4d.w)
fun Double.minus(vector4d: Vector4d) = Vector4d(this - vector4d.x, this - vector4d.y, this - vector4d.z, this - vector4d.w)
fun Double.times(vector4d: Vector4d) = Vector4d(this * vector4d.x, this * vector4d.y, this * vector4d.z, this * vector4d.w)
fun Double.div(vector4d: Vector4d) = Vector4d(this / vector4d.x, this / vector4d.y, this / vector4d.z, this / vector4d.w)

infix fun Vector4d.o(other: Vector4d) = this dot other

data class Vector4dDual(val x: Dual, val y: Dual, val z: Dual, val w: Dual) {
    constructor(value: Dual) : this(value, value, value, value)
    constructor(values: List<Vector4d>) : this(
        Dual(values.map { it.x }),
        Dual(values.map { it.y }),
        Dual(values.map { it.z }),
        Dual(values.map { it.w })
    )

    init {
        require(x.size == y.size && y.size == z.size && z.size == w.size) { "Dual X, Y, Z and W must be of the same size" }
    }

    val size get() = x.size
    val isReal get() = size == 1
    infix fun o(b: Vector4dDual) = x * b.x + y * b.y + z * b.z + w * b.w
    val normSqr get() = this o this
    val norm get() = sqrt(normSqr)
    val value get() = Vector4d(x.value, y.value, z.value, w.value)
    fun head(n: Int = 1) = Vector4dDual(x.head(n), y.head(n), z.head(n), w.head(n))
    fun tail(n: Int = 1) = Vector4dDual(x.tail(n), y.tail(n), z.tail(n), w.tail(n))

    fun normalized() = this / norm

    operator fun unaryPlus() = this
    operator fun unaryMinus() = Vector4dDual(-x, -y, -z, -w)
    operator fun plus(b: Vector4dDual) = Vector4dDual(x + b.x, y + b.y, z + b.z, w + b.w)
    operator fun plus(b: Vector4d) = Vector4dDual(x + b.x, y + b.y, z + b.z, w + b.w)
    operator fun minus(b: Vector4dDual) = Vector4dDual(x - b.x, y - b.y, z - b.z, w - b.w)
    operator fun minus(b: Vector4d) = Vector4dDual(x - b.x, y - b.y, z - b.z, w - b.w)
    operator fun times(b: Vector4dDual) = Vector4dDual(x * b.x, y * b.y, z * b.z, w * b.w)
    operator fun times(b: Vector4d) = Vector4dDual(x * b.x, y * b.y, z * b.z, w * b.w)
    operator fun div(b: Vector4dDual) = Vector4dDual(x / b.x, y / b.y, z / b.z, w / b.w)
    operator fun div(b: Vector4d) = Vector4dDual(x / b.x, y / b.y, z / b.z, w / b.w)
    operator fun times(scalar: Dual) = Vector4dDual(x * scalar, y * scalar, z * scalar, w * scalar)
    operator fun times(constant: Double) = Vector4dDual(x * constant, y * constant, z * constant, w * constant)
    operator fun div(scalar: Dual) = Vector4dDual(x / scalar, y / scalar, z / scalar, w / scalar)
    operator fun div(constant: Double) = Vector4dDual(x / constant, y / constant, z / constant, w / constant)
    operator fun get(n: Int) = Vector4d(x[n], y[n], z[n], w[n])

    companion object {
        fun const(x: Double, y: Double, z: Double, w: Double, n: Int = 1) =
            Vector4dDual(Dual.const(x, n), Dual.const(y, n), Dual.const(z, n), Dual.const(w, n))

        fun const(value: Vector4d, n: Int = 1) = const(value.x, value.y, value.z, value.w, n)
        fun of(vararg values: Vector4d) = Vector4dDual(values.asList())
    }
}

fun avg(a: Vector2d, b: Vector2d) = (a + b) / 2.0
fun avg(a: Vector2d, b: Vector2d, c: Vector2d) = (a + b + c) / 3.0
fun avg(a: Vector2d, b: Vector2d, c: Vector2d, d: Vector2d) = (a + b + c + d) / 4.0
fun avg(vectors: List<Vector2d>) = vectors.reduce { a, b -> a + b } / vectors.size.toDouble()
fun avg(a: Vector3d, b: Vector3d) = (a + b) / 2.0
fun avg(a: Vector3d, b: Vector3d, c: Vector3d) = (a + b + c) / 3.0
fun avg(a: Vector3d, b: Vector3d, c: Vector3d, d: Vector3d) = (a + b + c + d) / 4.0
fun avg(vectors: List<Vector3d>) = vectors.reduce { a, b -> a + b } / vectors.size.toDouble()
fun avg(a: Vector4d, b: Vector4d) = (a + b) / 2.0
fun avg(a: Vector4d, b: Vector4d, c: Vector4d) = (a + b + c) / 3.0
fun avg(a: Vector4d, b: Vector4d, c: Vector4d, d: Vector4d) = (a + b + c + d) / 4.0
fun avg(vectors: List<Vector4d>) = vectors.reduce { a, b -> a + b } / vectors.size.toDouble()