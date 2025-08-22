package org.eln2.mc.mathematics

import net.minecraft.util.FastColor
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.Vector4d
import org.ageseries.libage.mathematics.lerp

/**
 * ARGB color with 8 bits per channel implemented as a value class.
 * */
@JvmInline
value class ArgbColor(val data : Int) {
    companion object {
        val WHITE = ArgbColor(255, 255, 255,  255)

        fun lerp(from: ArgbColor, to: ArgbColor, blend: Float): ArgbColor =
            ArgbColor(
                lerp(from.aF, to.aF, blend),
                lerp(from.rF, to.rF, blend),
                lerp(from.gF, to.gF, blend),
                lerp(from.bF, to.bF, blend),
            )
    }

    val a get() = FastColor.ARGB32.alpha(data)
    val r get() = FastColor.ARGB32.red(data)
    val g get() = FastColor.ARGB32.green(data)
    val b get() = FastColor.ARGB32.blue(data)

    val aF get() = a / 255f
    val rF get() = r / 255f
    val gF get() = g / 255f
    val bF get() = b / 255f

    constructor(a: Byte, r: Byte, g: Byte, b: Byte) : this(
        FastColor.ARGB32.color(
            a.toInt(),
            r.toInt(),
            g.toInt(),
            b.toInt()
        )
    )

    constructor(a: Int, r: Int, g: Int, b: Int) : this(
        FastColor.ARGB32.color(
            a.coerceIn(0, 255),
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255)
        )
    )

    constructor(a: Float, r: Float, g: Float, b: Float) : this(
        FastColor.ARGB32.color(
            (a * 255).toInt().coerceIn(0, 255),
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255)
        )
    )

    constructor(r: Byte, g: Byte, b: Byte) : this(Byte.MAX_VALUE, r, g, b)
    constructor(r: Int, g: Int, b: Int) : this(255, r, g, b)
    constructor(r: Float, g: Float, b: Float) : this(1f, r, g, b)

    override fun toString() = "ARGB[$a, $r, $g, $b]"

    fun toVector4d() = Vector4d(
        aF.toDouble(),
        rF.toDouble(),
        gF.toDouble(),
        bF.toDouble()
    )

    fun toRGBAVector4d() = Vector4d(
        rF.toDouble(),
        gF.toDouble(),
        bF.toDouble(),
        aF.toDouble()
    )

    fun toVector3d() = Vector3d(
        rF.toDouble(),
        gF.toDouble(),
        bF.toDouble()
    )
}


