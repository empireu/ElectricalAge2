package org.eln2.mc.client.render.foundation

import com.jozufozu.flywheel.util.Color
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.lerp

fun colorLerp(from: Color, to: Color, blend: Float): Color =
    Color(
        lerp(from.redAsFloat, to.redAsFloat, blend),
        lerp(from.greenAsFloat, to.greenAsFloat, blend),
        lerp(from.blueAsFloat, to.blueAsFloat, blend),
        lerp(from.alphaAsFloat, to.alphaAsFloat, blend)
    )

fun color(r: Int, g: Int, b: Int, a: Int = 255) =
    (a and 0xff shl 24) or
    (r and 0xff shl 16) or
    (g and 0xff shl 8) or
    (b and 0xff shl 0)

fun color(r: Float, g: Float, b: Float, a: Float = 1.0f) =
    color(
        (r * 255).toInt(),
        (g * 255).toInt(),
        (b * 255).toInt(),
        (a * 255).toInt()
    )

data class RGBFloat(val r: Float, val g: Float, val b: Float) {
    operator fun times(scalar: Float) = RGBFloat(r * scalar, g * scalar, b * scalar)
    operator fun div(scalar: Float) = RGBFloat(r / scalar, g / scalar, b / scalar)
    operator fun plus(other: RGBFloat) = RGBFloat(r + other.r, g + other.g, b + other.b)
    operator fun minus(other: RGBFloat) = RGBFloat(r - other.r, g - other.g, b - other.b)
    operator fun times(other: RGBFloat) = RGBFloat(r * other.r, g * other.g, b * other.b)
    operator fun div(other: RGBFloat) = RGBFloat(r / other.r, g / other.g, b / other.b)

    fun toVector3d() = Vector3d(r.toDouble(), g.toDouble(), b.toDouble())

    companion object {
        val zero = RGBFloat(0f, 0f, 0f)
        val one = RGBFloat(1f, 1f, 1f)

        fun createClamped(r: Float, g: Float, b: Float) = RGBFloat(
            r.coerceIn(0f, 1f),
            g.coerceIn(0f, 1f),
            b.coerceIn(0f, 1f)
        )
    }
}


data class RGBAFloat(val r: Float, val g: Float, val b: Float, val a: Float) {
    operator fun times(scalar: Float) = RGBAFloat(r * scalar, g * scalar, b * scalar, a * scalar)
    operator fun div(scalar: Float) = RGBAFloat(r / scalar, g / scalar, b / scalar, a / scalar)
    operator fun plus(other: RGBAFloat) = RGBAFloat(r + other.r, g + other.g, b + other.b, a + other.a)
    operator fun minus(other: RGBAFloat) = RGBAFloat(r - other.r, g - other.g, b - other.b, a - other.a)
    operator fun times(other: RGBAFloat) = RGBAFloat(r * other.r, g * other.g, b * other.b, a * other.a)
    operator fun div(other: RGBAFloat) = RGBAFloat(r / other.r, g / other.g, b / other.b, a / other.a)

    val rgb get() = RGBFloat(r, g, b)
    val value get() = color(r, g, b, a)

    companion object {
        val zero = RGBAFloat(0f, 0f, 0f, 0f)
        val one = RGBAFloat(1f, 1f, 1f, 1f)
    }
}
