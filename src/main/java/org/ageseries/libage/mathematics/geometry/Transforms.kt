@file:Suppress("MemberVisibilityCanBePrivate", "NOTHING_TO_INLINE", "LocalVariableName")

package org.ageseries.libage.mathematics.geometry

import org.ageseries.libage.mathematics.*
import kotlin.math.*

data class Rotation2d(val re: Double, val im: Double) {
    val isNaN get() = re.isNaN() || im.isNaN()
    val isInfinity get() = re.isInfinite() || im.isInfinite()

    fun ln() = atan2(im, re)
    fun scaled(k: Double) = exp(ln() * k)
    val inverse get() = Rotation2d(re, -im)
    val direction get() = Vector2d(re, im)

    fun approxEq(other: Rotation2d, eps: Double = GEOMETRY_COMPARE_EPS) = re.approxEq(other.re, eps) && im.approxEq(other.im, eps)

    override fun toString() = "θ×=${Math.toDegrees(ln())} deg"

    operator fun not() = this.inverse
    operator fun times(other: Rotation2d) =
        Rotation2d(this.re * other.re - this.im * other.im, this.re * other.im + this.im * other.re)
    operator fun times(v: Vector2d) = Vector2d(this.re * v.x - this.im * v.y, this.im * v.x + this.re * v.y)
    operator fun times(transform: Pose2d) = Pose2d(this * transform.translation, this * transform.rotation)
    operator fun div(b: Rotation2d) = b.inverse * this
    operator fun plus(incr: Double) = this * exp(incr)
    operator fun minus(b: Rotation2d) = (this / b).ln()

    companion object {
        val identity = exp(0.0)

        fun exp(angleIncr: Double) = Rotation2d(cos(angleIncr), sin(angleIncr))

        fun dir(direction: Vector2d): Rotation2d {
            val dir = direction.normalized()

            return Rotation2d(dir.x, dir.y)
        }

        fun interpolate(r0: Rotation2d, r1: Rotation2d, t: Double) = exp(t * (r1 / r0).ln()) * r0
    }
}

data class Rotation2dDual(val re: Dual, val im: Dual) {
    fun ln() = atan2(im, re)
    fun scaled(k: Double) = exp(ln() * k)
    val value get() = Rotation2d(re.value, im.value)
    val angularVelocity get() = re * im.tail() - im * re.tail()
    val inverse get() = Rotation2dDual(re, -im)
    val direction get() = Vector2dDual(re, im)

    operator fun not() = this.inverse
    operator fun times(b: Rotation2dDual) =
        Rotation2dDual(this.re * b.re - this.im * b.im, this.re * b.im + this.im * b.re)
    operator fun times(b: Rotation2d) = Rotation2dDual(this.re * b.re - this.im * b.im, this.re * b.im + this.im * b.re)
    operator fun times(r2: Vector2dDual) =
        Vector2dDual(this.re * r2.x - this.im * r2.y, this.im * r2.x + this.re * r2.y)

    operator fun times(r2: Vector2d) = Vector2dDual(this.re * r2.x - this.im * r2.y, this.im * r2.x + this.re * r2.y)

    companion object {
        fun exp(angleIncr: Dual) = Rotation2dDual(cos(angleIncr), sin(angleIncr))
        fun const(value: Rotation2d, n: Int = 1) = Rotation2dDual(Dual.const(value.re, n), Dual.const(value.im, n))
        fun const(angleIncr: Double, n: Int = 1) = exp(Dual.const(angleIncr, n))
    }
}

data class Twist2dIncr(val trIncr: Vector2d, val rotIncr: Double) {
    constructor(xIncr: Double, yIncr: Double, rotIncr: Double) : this(Vector2d(xIncr, yIncr), rotIncr)

    override fun toString() = "X=${trIncr.x} Y=${trIncr.y} R=$rotIncr"
}

data class Twist2dIncrDual(val trIncr: Vector2dDual, val rotIncr: Dual) {
    constructor(xIncr: Dual, yIncr: Dual, rotIncr: Dual) : this(Vector2dDual(xIncr, yIncr), rotIncr)

    val value get() = Twist2dIncr(trIncr.value, rotIncr.value)
    val velocity get() = Twist2dDual(trIncr.tail(), rotIncr.tail())

    companion object {
        fun const(trIncr: Vector2d, rotIncr: Double, n: Int = 1) =
            Twist2dIncrDual(Vector2dDual.const(trIncr, n), Dual.const(rotIncr, n))
    }
}

data class Twist2d(val trVelocity: Vector2d, val rotVelocity: Double) {
    constructor(dx: Double, dy: Double, dTheta: Double) : this(Vector2d(dx, dy), dTheta)

    operator fun plus(other: Twist2d) = Twist2d(trVelocity + other.trVelocity, rotVelocity + other.rotVelocity)
    operator fun minus(other: Twist2d) = Twist2d(trVelocity - other.trVelocity, rotVelocity - other.rotVelocity)
    operator fun times(scalar: Double) = Twist2d(trVelocity * scalar, rotVelocity * scalar)
    operator fun div(scalar: Double) = Twist2d(trVelocity / scalar, rotVelocity / scalar)
}

data class Twist2dDual(val trVelocity: Vector2dDual, val rotVelocity: Dual) {
    constructor(dx: Dual, dy: Dual, dTheta: Dual) : this(Vector2dDual(dx, dy), dTheta)

    val value get() = Twist2d(trVelocity.value, rotVelocity.value)
    fun head(n: Int = 1) = Twist2dDual(trVelocity.head(n), rotVelocity.head(n))
    fun tail(n: Int = 1) = Twist2dDual(trVelocity.tail(n), rotVelocity.tail(n))

    operator fun plus(other: Twist2dDual) = Twist2dDual(trVelocity + other.trVelocity, rotVelocity + other.rotVelocity)
    operator fun minus(other: Twist2dDual) = Twist2dDual(trVelocity - other.trVelocity, rotVelocity - other.rotVelocity)

    companion object {
        fun const(value: Twist2d, n: Int = 1) =
            Twist2dDual(Vector2dDual.const(value.trVelocity, n), Dual.const(value.rotVelocity, n))
    }
}

data class Pose2d(val translation: Vector2d, val rotation: Rotation2d) {
    constructor(x: Double, y: Double, angle: Double) : this(Vector2d(x, y), Rotation2d.exp(angle))
    constructor(x: Double, y: Double) : this(x, y, 0.0)

    val inverse get() = Pose2d(rotation.inverse * -translation, rotation.inverse)
    operator fun not() = this.inverse

    fun ln(): Twist2dIncr {
        val angle = rotation.ln()
        val u = 0.5 * angle
        val c = rotation.re - 1.0

        val ht = if (abs(c) < 1e-9) {
            1.0 - 1.0 / 12.0 * (angle * angle)
        } else {
            -u * rotation.im / c
        }

        return Twist2dIncr(
            Vector2d(
                ht * translation.x + u * translation.y,
                -u * translation.x + ht * translation.y
            ),
            angle
        )
    }

    fun approxEq(other: Pose2d, eps: Double = GEOMETRY_COMPARE_EPS) = translation.approxEq(other.translation, eps) && rotation.approxEq(other.rotation, eps)

    override fun toString() = "$translation $rotation"

    operator fun times(b: Pose2d) = Pose2d(this.translation + this.rotation * b.translation, this.rotation * b.rotation)
    operator fun times(v: Vector2d) = this.translation + this.rotation * v
    operator fun div(b: Pose2d) = b.inverse * this
    operator fun plus(incr: Twist2dIncr) = this * exp(incr)
    operator fun minus(b: Pose2d) = (this / b).ln()

    companion object {
        val identity = Pose2d(Vector2d.zero, Rotation2d.identity)

        fun exp(tw: Twist2dIncr): Pose2d {
            val z = Rotation2d.exp(tw.rotIncr)
            val s: Double
            val c: Double

            if (abs(tw.rotIncr) < 1e-9) {
                s = 1.0 - 1.0 / 6.0 * tw.rotIncr * tw.rotIncr
                c = 0.5 * tw.rotIncr
            } else {
                s = z.im / tw.rotIncr
                c = (1.0 - z.re) / tw.rotIncr
            }

            return Pose2d(
                Vector2d(
                    s * tw.trIncr.x - c * tw.trIncr.y,
                    c * tw.trIncr.x + s * tw.trIncr.y
                ),
                z
            )
        }

    }
}

data class Pose2dDual(val translation: Vector2dDual, val rotation: Rotation2dDual) {
    val inverse get() = Pose2dDual(rotation.inverse * -translation, rotation.inverse)
    operator fun not() = this.inverse

    val value get() = Pose2d(translation.value, rotation.value)
    val velocity get() = Twist2dDual(translation.tail(), rotation.angularVelocity)

    override fun toString() = "$translation $rotation"

    operator fun times(b: Pose2dDual) =
        Pose2dDual(this.translation + this.rotation * b.translation, this.rotation * b.rotation)
    operator fun times(b: Pose2d) =
        Pose2dDual(this.translation + this.rotation * b.translation, this.rotation * b.rotation)
    operator fun times(b: Twist2dDual) = Twist2dDual(rotation * b.trVelocity, b.rotVelocity)
    operator fun div(b: Pose2dDual) = b.inverse * this
    operator fun plus(incr: Twist2dIncr) = this * Pose2d.exp(incr)

    companion object {
        fun const(v: Pose2d, n: Int = 1) =
            Pose2dDual(Vector2dDual.const(v.translation, n), Rotation2dDual.const(v.rotation, n))
    }
}

data class AxisAngle3d(val axis: Vector3d, val angle: Double)

data class Rotation3d(val x: Double, val y: Double, val z: Double, val w: Double) {
    val isNaN get() = x.isNaN() || y.isNaN() || z.isNaN() || w.isNaN()
    val isInfinity get() = x.isInfinite() || y.isInfinite() || z.isInfinite() || w.isInfinite()

    val normSqr get() = this dot this
    val norm get() = sqrt(normSqr)
    fun normalized() = this * normalizationCoefficient(this.x, this.y, this.z, this.w)
    val xyz get() = Vector3d(x, y, z)
    val inverse get() = Rotation3d(-x, -y, -z, w)

    infix fun dot(other: Rotation3d) = this.x * other.x + this.y * other.y + this.z * other.z + this.w * other.w

    fun ln(): Vector3d {
        val n = xyz.norm

        return xyz * if (n < 1e-9) {
            2.0 / w - 2.0 / 3.0 * n * n / (w * w * w)
        } else {
            2.0 * atan2(n * snz(w), w * snz(w)) / n
        }
    }

    fun toAxisAngle() : AxisAngle3d {
        val euler = ln() // Mathematics was invented by Euler, keep that in mind
        val angle = euler.norm

        return if(angle == 0.0) {
            // Possible?
            AxisAngle3d(Vector3d.zero, 0.0)
        } else {
            AxisAngle3d(euler / angle, angle)
        }
    }

    operator fun times(scalar: Double) = Rotation3d(x * scalar, y * scalar, z * scalar, w * scalar)
    operator fun div(scalar: Double) = Rotation3d(x / scalar, y / scalar, z / scalar, w / scalar)

    operator fun unaryPlus() = this
    operator fun unaryMinus() = Rotation3d(-x, -y, -z, -w)
    operator fun not() = this.inverse
    operator fun plus(w: Vector3d) = this * exp(w)
    operator fun minus(b: Rotation3d) = (this / b).ln()
    operator fun plus(angle: Double) : Rotation3d {
        if(this == identity) {
            return identity
        }

        val w = ln()
        val n = w.norm

        return exp((w / n) * (n + angle))
    }

    operator fun times(other: Rotation3d) = createNormalized(
        x * other.w + other.x * w + (y * other.z - z * other.y),
        y * other.w + other.y * w + (z * other.x - x * other.z),
        z * other.w + other.z * w + (x * other.y - y * other.x),
        w * other.w - (x * other.x + y * other.y + z * other.z)
    )

    operator fun times(v: Vector3d): Vector3d {
        val `2wx` = 2.0 * (w * x)
        val `2wy` = 2.0 * (w * y)
        val `2wz` = 2.0 * (w * z)
        val `2xx` = 2.0 * (x * x)
        val `2xy` = 2.0 * (x * y)
        val `2xz` = 2.0 * (x * z)
        val `2yy` = 2.0 * (y * y)
        val `2yz` = 2.0 * (y * z)
        val `2zz` = 2.0 * (z * z)

        return Vector3d(
            (v.x * (1.0 - `2yy` - `2zz`) + v.y * (`2xy` - `2wz`) + v.z * (`2xz` + `2wy`)),
            (v.x * (`2xy` + `2wz`) + v.y * (1.0 - `2xx` - `2zz`) + v.z * (`2yz` - `2wx`)),
            (v.x * (`2xz` - `2wy`) + v.y * (`2yz` + `2wx`) + v.z * (1.0 - `2xx` - `2yy`))
        )
    }

    operator fun times(transform: Pose3d) = Pose3d(this * transform.translation, this * transform.rotation)

    operator fun div(b: Rotation3d) = b.inverse * this

    operator fun invoke() : Matrix3x3 {
        val `2xx` = 2.0 * (x * x)
        val `2yy` = 2.0 * (y * y)
        val `2zz` = 2.0 * (z * z)
        val `2xy` = 2.0 * (x * y)
        val `2xz` = 2.0 * (x * z)
        val `2xw` = 2.0 * (x * w)
        val `2yz` = 2.0 * (y * z)
        val `2yw` = 2.0 * (y * w)
        val `2zw` = 2.0 * (z * w)

        return Matrix3x3(
            1.0 - `2yy` - `2zz`, `2xy` - `2zw`, `2xz` + `2yw`,
            `2xy` + `2zw`, 1.0 - `2xx` - `2zz`, `2yz` - `2xw`,
            `2xz` - `2yw`, `2yz` + `2xw`, 1.0 - `2xx` - `2yy`
        )
    }

    operator fun invoke(k: Double) = exp(ln() * k)

    fun approxEqComponentWise(other: Rotation3d, eps: Double = GEOMETRY_COMPARE_EPS) = x.approxEq(other.x, eps) && y.approxEq(other.y, eps) && z.approxEq(other.z, eps) && w.approxEq(other.w, eps)

    fun approxEq(other: Rotation3d, eps: Double = GEOMETRY_COMPARE_EPS) = abs(this dot other).approxEq(1.0, eps)

    companion object {
        val identity = Rotation3d(0.0, 0.0, 0.0, 1.0)

        // this is interesting, maybe we could move it to our main math package
        inline fun normalizationCoefficient(x: Double, y: Double, z: Double, w: Double) : Double {
            val normSqr = (x * x) + (y * y) + (z * z) + (w * w)

            return if(abs(1.0 - normSqr) < 2.107342e-08) {
                2.0 / (1.0 + normSqr)
            }
            else {
                1.0 / sqrt(normSqr)
            }
        }

        inline fun createNormalized(x: Double, y: Double, z: Double, w: Double) : Rotation3d {
            val k = normalizationCoefficient(x, y, z, w)
            return Rotation3d(k * x, k * y, k * z, k * w)
        }

        fun exp(w: Vector3d): Rotation3d {
            val t = w.norm

            if (t == 0.0) {
                return identity
            }

            val axis = w / t
            val s = sin(t / 2.0)

            return Rotation3d(axis.x * s, axis.y * s, axis.z * s, cos(t / 2.0))
        }

        fun fromAxisAngle(axis: Vector3d, angle: Double) = exp(axis.normalized() * angle)

        fun fromAxisAngle(axisAngle3d: AxisAngle3d) = fromAxisAngle(axisAngle3d.axis, axisAngle3d.angle)

        fun fromRotationMatrix(matrix: Matrix3x3): Rotation3d {
            val c0 = matrix.c0
            val c1 = matrix.c1
            val c2 = matrix.c2

            val m00 = c0.x
            val m01 = c0.y
            val m02 = c0.z
            val m10 = c1.x
            val m11 = c1.y
            val m12 = c1.z
            val m20 = c2.x
            val m21 = c2.y
            val m22 = c2.z

            val t: Double
            val q: Rotation3d

            if (m22 < 0) {
                if (m00 > m11) {
                    t = 1.0 + m00 - m11 - m22
                    q = Rotation3d(
                        t,
                        m01 + m10,
                        m20 + m02,
                        m12 - m21
                    )
                } else {
                    t = 1.0 - m00 + m11 - m22
                    q = Rotation3d(
                        m01 + m10,
                        t,
                        m12 + m21,
                        m20 - m02
                    )
                }
            } else {
                if (m00 < -m11) {
                    t = 1.0 - m00 - m11 + m22
                    q = Rotation3d(
                        m20 + m02,
                        m12 + m21,
                        t,
                        m01 - m10
                    )
                } else {
                    t = 1.0 + m00 + m11 + m22
                    q = Rotation3d(
                        m12 - m21,
                        m20 - m02,
                        m01 - m10,
                        t
                    )
                }
            }

            return q * 0.5 / sqrt(t)
        }

        fun alg(w: Vector3d) = Matrix3x3(
            0.0, -w.z, w.y,
            w.z, 0.0, -w.x,
            -w.y, w.x, 0.0
        )

        fun interpolate(r0: Rotation3d, r1: Rotation3d, t: Double) = exp((r1 / r0).ln() * t) * r0

        fun createFromTwoVectors(from: Vector3d, to: Vector3d) : Rotation3d {
            val c = (from o to)

            if(c.approxEq(-1.0, GEOMETRY_NORMALIZED_EPS)) {
                var x = from.y
                var y = -from.x
                var z = 0.0

                if (x * x + y * y == 0.0) {
                    x = 0.0
                    y = from.z
                    z = -from.y
                }

                return Rotation3d(x, y, z, 0.0)
            }

            val (x, y, z) = from x to
            val w = 1.0 + c

            return createNormalized(x, y, z, w)
        }
    }
}

/**
 * Builder for a [Rotation3d].
 * All operations will apply the *action* on the current [rotation].
 * As such, the transformations will be applied to the object in the order they appear in code.
 * */
class Rotation3dBuilder {
    var rotation = Rotation3d.identity

    fun identity(): Rotation3dBuilder {
        rotation = Rotation3d.identity
        return this
    }

    fun rotate(rotation3d: Rotation3d) : Rotation3dBuilder {
        rotation = rotation3d * rotation
        return this
    }

    fun transform(pose3d: Pose3d) : Rotation3dBuilder {
        rotation = pose3d * rotation
        return this
    }

    fun rotateExp(wx: Vector3d) = rotate(Rotation3d.exp(wx))

    fun rotateExp(x: Double, y: Double, z: Double) = rotateExp(Vector3d(x, y, z))

    fun rotateX(angle: Double) = rotateExp(angle, 0.0, 0.0)

    fun rotateY(angle: Double) = rotateExp(0.0, angle, 0.0)

    fun rotateZ(angle: Double) = rotateExp(0.0, 0.0, angle)

    operator fun timesAssign(rotation: Rotation3d) {
        rotate(rotation)
    }
}

data class Rotation3dDual(val x: Dual, val y: Dual, val z: Dual, val w: Dual) {
    init {
        require(x.size == y.size && y.size == z.size && z.size == w.size)
    }

    val size get() = x.size

    val xyz get() = Vector3dDual(x, y, z)
    val normSqr get() = x * x + y * y + z * z + w * w
    val norm get() = sqrt(normSqr)
    fun normalized() = this / norm
    val inverse get() = Rotation3dDual(-x, -y, -z, w)
    val value get() = Rotation3d(x.value, y.value, z.value, w.value)

    fun ln(): Vector3dDual {
        val n = xyz.norm

        return xyz * if (n.value < 1e-9) {
            2.0 / w - 2.0 / 3.0 * n * n / (w * w * w)
        } else {
            2.0 * atan2(n * snz(w.value), w * snz(w.value)) / n
        }
    }

    val angularVelocity: Vector3dDual
        get() {
            val n = xyz.norm
            val im = n * snz(w.value)
            val re = w * snz(w.value)
            return xyz * (2.0 * (re * im.tail() - im * re.tail()) / n)
        }

    fun head(n: Int = 1) = Rotation3dDual(x.head(n), y.head(n), z.head(n), w.head(n))
    fun tail(n: Int = 1) = Rotation3dDual(x.tail(n), y.tail(n), z.tail(n), w.tail(n))

    operator fun div(scalar: Dual) = Rotation3dDual(x / scalar, y / scalar, z / scalar, w / scalar)

    operator fun unaryPlus() = this
    operator fun unaryMinus() = Rotation3dDual(-x, -y, -z, -w)
    operator fun not() = this.inverse
    operator fun times(b: Rotation3dDual) =
        Rotation3dDual(
            x * b.w + b.x * w + (y * b.z - z * b.y),
            y * b.w + b.y * w + (z * b.x - x * b.z),
            z * b.w + b.z * w + (x * b.y - y * b.x),
            w * b.w - (x * b.x + y * b.y + z * b.z)
        )

    operator fun times(value: Vector3dDual): Vector3dDual {
        val a = 2.0 * (w * x)
        val b = 2.0 * (w * y)
        val c = 2.0 * (w * z)
        val d = 2.0 * (x * x)
        val e = 2.0 * (x * y)
        val f = 2.0 * (x * z)
        val g = 2.0 * (y * y)
        val h = 2.0 * (y * z)
        val i = 2.0 * (z * z)

        return Vector3dDual(
            (value.x * (1.0 - g - i) + value.y * (e - c) + value.z * (f + b)),
            (value.x * (e + c) + value.y * (1.0 - d - i) + value.z * (h - a)),
            (value.x * (f - b) + value.y * (h + a) + value.z * (1.0 - d - g))
        )
    }

    operator fun div(b: Rotation3dDual) = b.inverse * this

    operator fun invoke() : Matrix3x3Dual {
        val `2xx` = 2.0 * (x * x)
        val `2yy` = 2.0 * (y * y)
        val `2zz` = 2.0 * (z * z)
        val `2xy` = 2.0 * (x * y)
        val `2xz` = 2.0 * (x * z)
        val `2xw` = 2.0 * (x * w)
        val `2yz` = 2.0 * (y * z)
        val `2yw` = 2.0 * (y * w)
        val `2zw` = 2.0 * (z * w)

        return Matrix3x3Dual(
            1.0 - `2yy` - `2zz`, `2xy` - `2zw`, `2xz` + `2yw`,
            `2xy` + `2zw`, 1.0 - `2xx` - `2zz`, `2yz` - `2xw`,
            `2xz` - `2yw`, `2yz` + `2xw`, 1.0 - `2xx` - `2yy`
        )
    }

    companion object {
        fun fromAxisAngle(axis: Vector3dDual, angle: Dual) = exp(axis.normalized() * angle)

        fun exp(w: Vector3dDual): Rotation3dDual {
            val angle = w.norm
            val axis = w / angle
            val s = sin(angle / 2.0)

            return Rotation3dDual(axis.x * s, axis.y * s, axis.z * s, cos(angle / 2.0))
        }
    }
}

data class Twist3d(val trVelocity: Vector3d, val rotVelocity: Vector3d)

data class Twist3dDual(val trVelocity: Vector3dDual, val rotVelocity: Vector3dDual) {
    val value get() = Twist3d(trVelocity.value, rotVelocity.value)
    fun head(n: Int = 1) = Twist3dDual(trVelocity.head(n), rotVelocity.head(n))
    fun tail(n: Int = 1) = Twist3dDual(trVelocity.tail(n), rotVelocity.tail(n))
}

data class Twist3dIncr(val trIncr: Vector3d, val rotIncr: Vector3d)

data class Twist3dIncrDual(val trIncr: Vector3dDual, val rotIncr: Vector3dDual) {
    val value get() = Twist3dIncr(trIncr.value, rotIncr.value)
    val velocity get() = Twist3dDual(trIncr.tail(), rotIncr.tail())
}

data class Pose3d(val translation: Vector3d, val rotation: Rotation3d) {
    val inverse get() = Pose3d(rotation.inverse * -translation, rotation.inverse)

    fun ln(): Twist3dIncr {
        val w = rotation.ln()
        val wx = Rotation3d.alg(w)
        val t = w.norm
        val t2 = t * t

        val c = if (abs(t) < 1e-7) {
            1 / 12.0 + t2 / 720.0 + t2 * t2 / 30240.0
        } else {
            // Evaluates to -Inf at 1e-8
            (1.0 - sin(t) / t / (2.0 * ((1.0 - cos(t)) / t2))) / t2
        }

        return Twist3dIncr(
            (Matrix3x3.identity - (wx * 0.5) + ((wx * wx) * c)) * translation,
            w
        )
    }

    operator fun not() = this.inverse

    operator fun plus(incr: Twist3dIncr) = this * exp(incr)
    operator fun plus(displacement: Vector3d) = Pose3d(translation + displacement, rotation)
    operator fun plus(rotation: Rotation3d) = Pose3d(translation, rotation * this.rotation)
    operator fun minus(displacement: Vector3d) = Pose3d(translation - displacement, rotation)
    operator fun minus(rotation: Rotation3d) = Pose3d(translation, rotation.inverse / this.rotation)
    operator fun minus(other: Pose3d) = (this / other).ln()
    operator fun times(other: Pose3d) =
        Pose3d(this.translation + this.rotation * other.translation, this.rotation * other.rotation)
    operator fun times(v: Vector3d) = this.translation + this.rotation * v
    operator fun times(transform: Rotation3d) = this.rotation * transform
    operator fun times(obb: OrientedBoundingBox3d) = OrientedBoundingBox3d(this * obb.transform, obb.halfSize)
    operator fun div(b: Pose3d) = b.inverse * this

    fun approxEq(other: Pose3d, eps: Double = GEOMETRY_COMPARE_EPS) =
        translation.approxEq(other.translation, eps) &&
        rotation.approxEq(other.rotation, eps)

    operator fun invoke() : Matrix4x4 {
        val (rc0, rc1, rc2) = rotation()

        return Matrix4x4(
            rc0.x, rc1.x, rc2.x, translation.x,
            rc0.y, rc1.y, rc2.y, translation.y,
            rc0.z, rc1.z, rc2.z, translation.z,
            0.0, 0.0, 0.0, 1.0
        )
    }

    companion object {
        val identity = Pose3d(Vector3d.zero, Rotation3d.identity)

        fun fromMatrix(matrix: Matrix4x4) : Pose3d {
            val (c0, c1, c2, c3) = matrix

            return Pose3d(
                Vector3d(c3.x, c3.y, c3.z),
                Rotation3d.fromRotationMatrix(
                    Matrix3x3(
                        c0.x, c1.x, c2.x,
                        c0.y, c1.y, c2.y,
                        c0.z, c1.z, c2.z
                    )
                )
            )
        }

        fun exp(incr: Twist3dIncr): Pose3d {
            val t = incr.rotIncr.norm
            val t2 = t * t
            val t4 = t2 * t2

            val b: Double
            val c: Double

            if (abs(t) < 1e-7) {
                b = 1.0 / 2.0 - t2 / 24.0 + t4 / 720.0
                c = 1.0 / 6.0 - t2 / 120.0 + t4 / 5040.0
            } else {
                b = (1.0 - cos(t)) / t2
                c = (1.0 - sin(t) / t) / t2
            }

            val wx = Rotation3d.alg(incr.rotIncr)

            return Pose3d(
                (Matrix3x3.identity + wx * b + (wx * wx) * c) * incr.trIncr,
                Rotation3d.exp(incr.rotIncr)
            )
        }
    }
}

/**
 * Builder for a [Pose3d].
 * All operations will apply the *action* on the current [pose].
 * As such, the transformations will be applied to the object in the order they appear in code.
 * */
class Pose3dBuilder {
    var pose = Pose3d.identity

    var translation: Vector3d
        get() = pose.translation
        set(value) { pose = pose.copy(translation = value) }

    var rotation: Rotation3d
        get() = pose.rotation
        set(value) { pose = pose.copy(rotation = value) }

    fun identity(): Pose3dBuilder {
        pose = Pose3d.identity
        return this
    }

    fun transform(pose3d: Pose3d) : Pose3dBuilder {
        pose = pose3d * pose
        return this
    }

    fun transform(rotation3d: Rotation3d) : Pose3dBuilder {
        pose = rotation3d * pose
        return this
    }

    /**
     * Rotates the pose "in-place". Equivalent to transforming a pose with [Vector3d.zero] and [rotation] by [pose].
     * */
    fun rotateBy(rotation: Rotation3d) : Pose3dBuilder {
        pose = pose.copy(rotation = rotation * pose.rotation)
        return this
    }

    fun translate(x: Double, y: Double, z: Double) : Pose3dBuilder {
        val (tx, ty, tz) = pose.translation
        pose = pose.copy(translation = Vector3d(tx + x, ty + y, tz + z))
        return this
    }

    fun translate(v: Vector3d) : Pose3dBuilder {
        pose += v
        return this
    }

    fun twist(twist3d: Twist3dIncr) = transform(Pose3d.exp(twist3d))

    operator fun timesAssign(pose: Pose3d) {
        transform(pose)
    }

    operator fun timesAssign(translation: Vector3d) {
        translate(translation)
    }

    operator fun minusAssign(translation: Vector3d) {
        translate(-translation)
    }
}

data class Pose3dDual(val translation: Vector3dDual, val rotation: Rotation3dDual) {
    init {
        require(translation.size == rotation.size)
    }

    val size get() = translation.size

    val inverse get() = Pose3dDual(rotation.inverse * -translation, rotation.inverse)
    val value get() = Pose3d(translation.value, rotation.value)
    val velocity get() = Twist3dDual(translation.tail(), rotation.angularVelocity)

    operator fun not() = this.inverse
    operator fun times(b: Pose3dDual) =
        Pose3dDual(this.translation + this.rotation * b.translation, this.rotation * b.rotation)

    operator fun times(v: Vector3dDual) = this.translation + this.rotation * v
    operator fun div(b: Pose3dDual) = b.inverse * this
    operator fun invoke() = rotation().let { (rc0, rc1, rc2) ->
        val `0` = Dual.const(0.0, size)
        val `1` = Dual.const(1.0, size)

        Matrix4x4Dual(
            rc0.x, rc1.x, rc2.x, translation.x,
            rc0.y, rc1.y, rc2.y, translation.y,
            rc0.z, rc1.z, rc2.z, translation.z,
            `0`, `0`, `0`, `1`
        )
    }
}

data class CoordinateSystem(val transform: Matrix3x3) {
    init {
        require(transform.isOrthogonal)
    }

    operator fun rangeTo(b: CoordinateSystem) = b.transform * !this.transform

    companion object {
        val rfu = CoordinateSystem(Matrix3x3.identity)

        val minecraft = CoordinateSystem(
            Matrix3x3(
                Vector3d.unitX,
                Vector3d.unitZ,
                Vector3d.unitY
            )
        )
    }
}