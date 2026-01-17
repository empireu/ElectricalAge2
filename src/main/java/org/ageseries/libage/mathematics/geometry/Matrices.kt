@file:Suppress("LocalVariableName", "MemberVisibilityCanBePrivate")

package org.ageseries.libage.mathematics.geometry

import org.ageseries.libage.mathematics.*
import kotlin.math.absoluteValue
import kotlin.math.sqrt

// Only for completeness. If you need a matrix, be sane and use JOML

data class Matrix3x3(val c0: Vector3d, val c1: Vector3d, val c2: Vector3d) {
    constructor(
        m00: Double, m01: Double, m02: Double,
        m10: Double, m11: Double, m12: Double,
        m20: Double, m21: Double, m22: Double,
    ) : this(
        Vector3d(m00, m10, m20),
        Vector3d(m01, m11, m21),
        Vector3d(m02, m12, m22)
    )

    constructor(mat: DoubleArray) : this(mat[0], mat[1], mat[2], mat[3], mat[4], mat[5], mat[6], mat[7], mat[8])

    val r0 get() = Vector3d(c0.x, c1.x, c2.x)
    val r1 get() = Vector3d(c0.y, c1.y, c2.y)
    val r2 get() = Vector3d(c0.z, c1.z, c2.z)
    val determinant get() = c0.x * (c1.y * c2.z - c2.y * c1.z) - c1.x * (c0.y * c2.z - c2.y * c0.z) + c2.x * (c0.y * c1.z - c1.y * c0.z)
    val transpose get() = Matrix3x3(r0, r1, r2)
    val trace get() = c0.x + c1.y + c2.z
    val normFrobeniusSqr get() = c0.normSqr + c1.normSqr + c2.normSqr
    val normFrobenius get() = sqrt(normFrobeniusSqr)
    val isOrthogonal get() = (this * this.transpose).approxEq(identity) && this.determinant.absoluteValue.approxEq(1.0)
    val isSpecialOrthogonal get() = (this * this.transpose).approxEq(identity) && this.determinant.approxEq(1.0)

    override fun toString(): String {
        fun rowStr(row: Vector3d) = "${row.x} ${row.y} ${row.z}"
        return "${rowStr(r0)}\n${rowStr(r1)}\n${rowStr(r2)}"
    }

    operator fun not() = Matrix3x3(
        (c1.y * c2.z - c2.y * c1.z), -(c1.x * c2.z - c2.x * c1.z), (c1.x * c2.y - c2.x * c1.y),
        -(c0.y * c2.z - c2.y * c0.z), (c0.x * c2.z - c2.x * c0.z), -(c0.x * c2.y - c2.x * c0.y),
        (c0.y * c1.z - c1.y * c0.z), -(c0.x * c1.z - c1.x * c0.z), (c0.x * c1.y - c1.x * c0.y)
    ) * (1.0 / determinant)

    operator fun times(scalar: Double) = Matrix3x3(c0 * scalar, c1 * scalar, c2 * scalar)
    operator fun times(v: Vector3d) = c0 * v.x + c1 * v.y + c2 * v.z
    operator fun times(m: Matrix3x3) = Matrix3x3(this * m.c0, this * m.c1, this * m.c2)
    operator fun plus(m: Matrix3x3) = Matrix3x3(c0 + m.c0, c1 + m.c1, c2 + m.c2)
    operator fun minus(m: Matrix3x3) = Matrix3x3(c0 - m.c0, c1 - m.c1, c2 - m.c2)

    fun getColumn(c: Int) = when (c) {
        0 -> c0
        1 -> c1
        2 -> c2
        else -> error("Column $c out of bounds")
    }

    fun getRow(r: Int) = when (r) {
        0 -> r0
        1 -> r1
        2 -> r2
        else -> error("Row $r out of bounds")
    }

    operator fun get(r: Int, c: Int) = when (r) {
        0 -> getColumn(c).x
        1 -> getColumn(c).y
        2 -> getColumn(c).z
        else -> error("Row $r out of bounds")
    }

    fun approxEq(other: Matrix3x3, eps: Double = GEOMETRY_COMPARE_EPS) =
        this.c0.approxEq(other.c0, eps) && this.c1.approxEq(other.c1, eps) && this.c2.approxEq(other.c2, eps)

    fun toArray() : DoubleArray {
        val array = DoubleArray(9)

        array[0] = c0.x
        array[1] = c1.x
        array[2] = c2.x
        array[3] = c0.y
        array[4] = c1.y
        array[5] = c2.y
        array[6] = c0.z
        array[7] = c1.z
        array[8] = c2.z

        return array
    }

    companion object {
        val identity = Matrix3x3(Vector3d.unitX, Vector3d.unitY, Vector3d.unitZ)

        inline fun get(array: DoubleArray, c: Int, r: Int) = r * 3 + c
    }
}

data class Matrix3x3Dual(val c0: Vector3dDual, val c1: Vector3dDual, val c2: Vector3dDual) {
    constructor(
        m00: Dual, m01: Dual, m02: Dual,
        m10: Dual, m11: Dual, m12: Dual,
        m20: Dual, m21: Dual, m22: Dual,
    ) : this(
        Vector3dDual(m00, m10, m20),
        Vector3dDual(m01, m11, m21),
        Vector3dDual(m02, m12, m22)
    )

    constructor(mat: List<Dual>) : this(
        mat[0], mat[1], mat[2],
        mat[3], mat[4], mat[5],
        mat[6], mat[7], mat[8]
    )

    init {
        require(c0.size == c1.size && c1.size == c2.size)
    }

    val r0 get() = Vector3dDual(c0.x, c1.x, c2.x)
    val r1 get() = Vector3dDual(c0.y, c1.y, c2.y)
    val r2 get() = Vector3dDual(c0.z, c1.z, c2.z)

    val size get() = c0.size
    val isReal get() = size == 1
    val value get() = Matrix3x3(c0.value, c1.value, c2.value)
    fun head(n: Int) = Matrix3x3Dual(c0.head(n), c1.head(n), c2.head(n))
    fun tail(n: Int) = Matrix3x3Dual(c0.tail(n), c1.tail(n), c2.tail(n))
    val transpose get() = Matrix3x3Dual(r0, r1, r2)
    val trace get() = c0.x + c1.y + c2.z
    val normFrobeniusSqr get() = c0.normSqr + c1.normSqr + c2.normSqr
    val normFrobenius get() = sqrt(normFrobeniusSqr)
    val determinant get() = c0.x * (c1.y * c2.z - c2.y * c1.z) - c1.x * (c0.y * c2.z - c2.y * c0.z) + c2.x * (c0.y * c1.z - c1.y * c0.z)

    operator fun not() = Matrix3x3Dual(
        (c1.y * c2.z - c2.y * c1.z), -(c1.x * c2.z - c2.x * c1.z), (c1.x * c2.y - c2.x * c1.y),
        -(c0.y * c2.z - c2.y * c0.z), (c0.x * c2.z - c2.x * c0.z), -(c0.x * c2.y - c2.x * c0.y),
        (c0.y * c1.z - c1.y * c0.z), -(c0.x * c1.z - c1.x * c0.z), (c0.x * c1.y - c1.x * c0.y)
    ) * (1.0 / determinant)

    operator fun times(scalar: Dual) = Matrix3x3Dual(c0 * scalar, c1 * scalar, c2 * scalar)
    operator fun times(constant: Double) = Matrix3x3Dual(c0 * constant, c1 * constant, c2 * constant)
    operator fun div(scalar: Dual) = Matrix3x3Dual(c0 / scalar, c1 / scalar, c2 / scalar)
    operator fun div(constant: Double) = Matrix3x3Dual(c0 / constant, c1 / constant, c2 / constant)
    operator fun times(v: Vector3dDual) = c0 * v.x + c1 * v.y + c2 * v.z
    operator fun times(v: Vector3d) = c0 * v.x + c1 * v.y + c2 * v.z
    operator fun times(m: Matrix3x3Dual) = Matrix3x3Dual(this * m.c0, this * m.c1, this * m.c2)
    operator fun times(m: Matrix3x3) = Matrix3x3Dual(this * m.c0, this * m.c1, this * m.c2)
    operator fun get(n: Int) = Matrix3x3(c0[n], c1[n], c2[n])

    companion object {
        fun const(v: Matrix3x3, n: Int = 1) = Matrix3x3Dual(
            Vector3dDual.const(v.c0, n),
            Vector3dDual.const(v.c1, n),
            Vector3dDual.const(v.c2, n)
        )
    }
}

data class Matrix4x4(val c0: Vector4d, val c1: Vector4d, val c2: Vector4d, val c3: Vector4d) {
    constructor(
        m00: Double, m01: Double, m02: Double, m03: Double,
        m10: Double, m11: Double, m12: Double, m13: Double,
        m20: Double, m21: Double, m22: Double, m23: Double,
        m30: Double, m31: Double, m32: Double, m33: Double,
    ) : this(
        Vector4d(m00, m10, m20, m30),
        Vector4d(m01, m11, m21, m31),
        Vector4d(m02, m12, m22, m32),
        Vector4d(m03, m13, m23, m33)
    )

    val r0 get() = Vector4d(c0.x, c1.x, c2.x, c3.x)
    val r1 get() = Vector4d(c0.y, c1.y, c2.y, c3.y)
    val r2 get() = Vector4d(c0.z, c1.z, c2.z, c3.z)
    val r3 get() = Vector4d(c0.w, c1.w, c2.w, c3.w)
    val transpose get() = Matrix4x4(r0, r1, r2, r3)
    val trace get() = c0.x + c1.y + c2.z + c3.w
    val normFrobeniusSqr get() = c0.normSqr + c1.normSqr + c2.normSqr + c3.normSqr
    val normFrobenius get() = sqrt(normFrobeniusSqr)

    val determinant get() =
        Matrix3x3(c1.y, c2.y, c3.y, c1.z, c2.z, c3.z, c1.w, c2.w, c3.w).determinant * c0.x -
        Matrix3x3(c1.x, c2.x, c3.x, c1.z, c2.z, c3.z, c1.w, c2.w, c3.w).determinant * c0.y +
        Matrix3x3(c1.x, c2.x, c3.x, c1.y, c2.y, c3.y, c1.w, c2.w, c3.w).determinant * c0.z -
        Matrix3x3(c1.x, c2.x, c3.x, c1.y, c2.y, c3.y, c1.z, c2.z, c3.z).determinant * c0.w

    fun eliminate(eliminateR: Int, eliminateC: Int): Matrix3x3 {
        val values = DoubleArray(3 * 3)

        var irActual = 0
        var icActual = 0

        for (ir in 0 until 4) {
            if (ir != eliminateR) {
                for (ic in 0 until 4) {
                    if (ic != eliminateC) {
                        values[(irActual * 3) + icActual++] = this[ic, ir]
                    }
                }

                irActual++
                icActual = 0
            }
        }

        return Matrix3x3(values)
    }

    fun minor(c: Int, r: Int) = eliminate(c, r).determinant
    fun cofactor(c: Int, r: Int) = minor(c, r) * (-1).pow(c + r)

    val cofactorMatrix get() =
        Matrix4x4(
            cofactor(0, 0), cofactor(1, 0), cofactor(2, 0), cofactor(3, 0),
            cofactor(0, 1), cofactor(1, 1), cofactor(2, 1), cofactor(3, 1),
            cofactor(0, 2), cofactor(1, 2), cofactor(2, 2), cofactor(3, 2),
            cofactor(0, 3), cofactor(1, 3), cofactor(2, 3), cofactor(3, 3)
        )

    val adjugateMatrix get() = cofactorMatrix.transpose

    fun approxEq(other: Matrix4x4, eps: Double = GEOMETRY_COMPARE_EPS) =
        c0.approxEq(other.c0, eps) &&
        c1.approxEq(other.c1, eps) &&
        c2.approxEq(other.c2, eps) &&
        c3.approxEq(other.c3, eps)

    override fun toString(): String {
        fun rowStr(row: Vector4d) = "${row.x} ${row.y} ${row.z} ${row.w}"
        return "${rowStr(r0)}\n${rowStr(r1)}\n${rowStr(r2)}\n${rowStr(r3)}"
    }

    operator fun not() = adjugateMatrix * (1.0 / determinant)
    operator fun times(scalar: Double) = Matrix4x4(c0 * scalar, c1 * scalar, c2 * scalar, c3 * scalar)
    operator fun times(v: Vector4d) = c0 * v.x + c1 * v.y + c2 * v.z + c3 * v.w
    operator fun times(v: Vector3d) = (this * Vector4d(v.x, v.y, v.z, 1.0)).let { Vector3d(it.x, it.y, it.z) }
    operator fun times(m: Matrix4x4) = Matrix4x4(this * m.c0, this * m.c1, this * m.c2, this * m.c3)

    fun getColumn(c: Int) = when (c) {
        0 -> c0
        1 -> c1
        2 -> c2
        3 -> c3
        else -> error("Column $c out of bounds")
    }

    fun getRow(r: Int) = when (r) {
        0 -> r0
        1 -> r1
        2 -> r2
        3 -> r3
        else -> error("Row $r out of bounds")
    }

    operator fun get(r: Int, c: Int) = when (r) {
        0 -> getColumn(c).x
        1 -> getColumn(c).y
        2 -> getColumn(c).z
        3 -> getColumn(c).w
        else -> error("Row $r out of bounds")
    }

    companion object {
        val identity = Matrix4x4(Vector4d.unitX, Vector4d.unitY, Vector4d.unitZ, Vector4d.unitW)
    }
}

data class Matrix4x4Dual(val c0: Vector4dDual, val c1: Vector4dDual, val c2: Vector4dDual, val c3: Vector4dDual) {
    constructor(
        m00: Dual, m01: Dual, m02: Dual, m03: Dual,
        m10: Dual, m11: Dual, m12: Dual, m13: Dual,
        m20: Dual, m21: Dual, m22: Dual, m23: Dual,
        m30: Dual, m31: Dual, m32: Dual, m33: Dual,
    ) : this(
        Vector4dDual(m00, m10, m20, m30),
        Vector4dDual(m01, m11, m21, m31),
        Vector4dDual(m02, m12, m22, m32),
        Vector4dDual(m03, m13, m23, m33)
    )

    constructor(mat: List<Dual>) : this(
        mat[0], mat[1], mat[2], mat[3],
        mat[4], mat[5], mat[6], mat[7],
        mat[8], mat[9], mat[10], mat[11],
        mat[12], mat[13], mat[14], mat[15]
    )

    init {
        require(c0.size == c1.size && c1.size == c2.size && c2.size == c3.size)
    }

    val size get() = c0.size
    val isReal get() = size == 1
    val value get() = Matrix4x4(c0.value, c1.value, c2.value, c3.value)
    fun head(n: Int) = Matrix4x4Dual(c0.head(n), c1.head(n), c2.head(n), c3.head(n))
    fun tail(n: Int) = Matrix4x4Dual(c0.tail(n), c1.tail(n), c2.tail(n), c3.tail(n))

    val r0 get() = Vector4dDual(c0.x, c1.x, c2.x, c3.x)
    val r1 get() = Vector4dDual(c0.y, c1.y, c2.y, c3.y)
    val r2 get() = Vector4dDual(c0.z, c1.z, c2.z, c3.z)
    val r3 get() = Vector4dDual(c0.w, c1.w, c2.w, c3.w)

    val transpose get() = Matrix4x4Dual(r0, r1, r2, r3)
    val trace get() = c0.x + c1.y + c2.z + c3.w
    val normFrobeniusSqr get() = c0.normSqr + c1.normSqr + c2.normSqr + c3.normSqr
    val normFrobenius get() = sqrt(normFrobeniusSqr)

    // Inlined for performance. We're already down a deep rabbit hole of performance issues
    // because of our design (and ⅅ, like this matrix), so at least let me have this one.
    val determinant get() =
        Matrix3x3Dual(c1.y, c2.y, c3.y, c1.z, c2.z, c3.z, c1.w, c2.w, c3.w).determinant * c0.x -
        Matrix3x3Dual(c1.x, c2.x, c3.x, c1.z, c2.z, c3.z, c1.w, c2.w, c3.w).determinant * c0.y +
        Matrix3x3Dual(c1.x, c2.x, c3.x, c1.y, c2.y, c3.y, c1.w, c2.w, c3.w).determinant * c0.z -
        Matrix3x3Dual(c1.x, c2.x, c3.x, c1.y, c2.y, c3.y, c1.z, c2.z, c3.z).determinant * c0.w

    fun eliminate(eliminateC: Int, eliminateR: Int): Matrix3x3Dual {
        val values = ArrayList<Dual>(9)

        repeat(9) { values.add(Dual.empty) }

        var irActual = 0
        var icActual = 0

        for (ir in 0 until 4) {
            if (ir != eliminateR) {
                for (ic in 0 until 4) {
                    if (ic != eliminateC) {
                        values[(irActual * 3) + icActual++] = this[ic, ir]
                    }
                }

                irActual++
                icActual = 0
            }
        }

        return Matrix3x3Dual(values)
    }

    fun minor(c: Int, r: Int) = eliminate(c, r).determinant
    fun cofactor(c: Int, r: Int) = minor(c, r) * (-1).pow(c + r).toDouble()
    val cofactorMatrix
        get() = Matrix4x4Dual(
            cofactor(0, 0), cofactor(1, 0), cofactor(2, 0), cofactor(3, 0),
            cofactor(0, 1), cofactor(1, 1), cofactor(2, 1), cofactor(3, 1),
            cofactor(0, 2), cofactor(1, 2), cofactor(2, 2), cofactor(3, 2),
            cofactor(0, 3), cofactor(1, 3), cofactor(2, 3), cofactor(3, 3)
        )
    val adjugateMatrix get() = cofactorMatrix.transpose

    operator fun not() = adjugateMatrix * (1.0 / determinant)
    operator fun times(scalar: Dual) = Matrix4x4Dual(c0 * scalar, c1 * scalar, c2 * scalar, c3 * scalar)
    operator fun times(constant: Double) = Matrix4x4Dual(c0 * constant, c1 * constant, c2 * constant, c3 * constant)
    operator fun times(v: Vector4dDual) = c0 * v.x + c1 * v.y + c2 * v.z + c3 * v.w
    operator fun times(v: Vector4d) = c0 * v.x + c1 * v.y + c2 * v.z + c3 * v.w
    operator fun times(m: Matrix4x4Dual) = Matrix4x4Dual(this * m.c0, this * m.c1, this * m.c2, this * m.c3)
    operator fun times(m: Matrix4x4) = Matrix4x4Dual(this * m.c0, this * m.c1, this * m.c2, this * m.c3)
    operator fun get(n: Int) = Matrix4x4(c0[n], c1[n], c2[n], c3[n])

    fun getColumn(c: Int) = when (c) {
        0 -> c0
        1 -> c1
        2 -> c2
        3 -> c3
        else -> error("Column $c out of bounds")
    }

    fun getRow(r: Int) = when (r) {
        0 -> r0
        1 -> r1
        2 -> r2
        3 -> r3
        else -> error("Row $r out of bounds")
    }

    operator fun get(c: Int, r: Int) = when (r) {
        0 -> getColumn(c).x
        1 -> getColumn(c).y
        2 -> getColumn(c).z
        3 -> getColumn(c).w
        else -> error("Row $r out of bounds")
    }

    companion object {
        fun const(v: Matrix4x4, n: Int = 1) = Matrix4x4Dual(
            Vector4dDual.const(v.c0, n),
            Vector4dDual.const(v.c1, n),
            Vector4dDual.const(v.c2, n),
            Vector4dDual.const(v.c3, n)
        )
    }
}