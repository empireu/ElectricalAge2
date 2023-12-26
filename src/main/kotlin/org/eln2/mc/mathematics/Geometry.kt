@file:Suppress("LocalVariableName", "NOTHING_TO_INLINE", "MemberVisibilityCanBePrivate")

package org.eln2.mc.mathematics

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.ageseries.libage.mathematics.*
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Rotation3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.Vector3di
import org.joml.Quaternionf
import org.joml.Quaternionfc
import kotlin.math.*

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

enum class FacingDirection(val angle: Double, val direction: Direction) {
    SOUTH(PI, Direction.SOUTH),
    WEST(PI / 2.0, Direction.WEST),
    NORTH(0.0, Direction.NORTH),
    EAST(-PI / 2.0, Direction.EAST);

    val clockWise = direction.clockWise.toHorizontalFacing()
    val counterClockWise = direction.counterClockWise.toHorizontalFacing()

    val index get() = direction.get2DDataValue()

    val rotation = Quaternionf().rotateY(angle.toFloat()) as Quaternionfc
    val rotation2d = Rotation2d.exp(angle)
    val rotation3d = Rotation3d.exp(Vector3d.unitY * angle)

    companion object {
        fun byIndex(index: Int) = entries[index]
    }
}

fun Direction.toHorizontalFacing() = when(this) {
    Direction.DOWN -> error("Down is not a good horizontal facing")
    Direction.UP -> error("Up is not a good horizontal facing")
    Direction.NORTH -> FacingDirection.NORTH
    Direction.SOUTH -> FacingDirection.SOUTH
    Direction.WEST -> FacingDirection.WEST
    Direction.EAST -> FacingDirection.EAST
}
