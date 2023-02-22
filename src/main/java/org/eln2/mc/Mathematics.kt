package org.eln2.mc

import net.minecraft.world.phys.Vec3

// Yes, how fancy. Stop making fun of me!
object Mathematics {
    fun map(v: Double, srcMin: Double, srcMax: Double, dstMin: Double, dstMax: Double): Double {
        return dstMin + (v - srcMin) * (dstMax - dstMin) / (srcMax - srcMin)
    }

    fun map(v: Float, srcMin: Float, srcMax: Float, dstMin: Float, dstMax: Float): Float {
        return dstMin + (v - srcMin) * (dstMax - dstMin) / (srcMax - srcMin)
    }

    fun bbSize(size: Int): Double {
        return bbSize(size.toDouble())
    }

    fun bbSize(size: Double): Double{
        return size / 16.0
    }

    fun bbVec(vec: Vec3): Vec3{
        return Vec3(bbSize(vec.x), bbSize(vec.y), bbSize(vec.z))
    }

    fun bbVec(x: Double, y: Double, z: Double): Vec3{
        return Vec3(x, y, z).fromBB()
    }

    fun Vec3.fromBB(): Vec3 {
        return bbVec(this)
    }

    fun vec3(value: Double): Vec3 {
        return Vec3(value, value, value)
    }

    fun vec3X(x: Double): Vec3{
        return Vec3(x, 0.0, 0.0)
    }

    fun vec3X(): Vec3{
        return vec3X(1.0)
    }

    fun vec3Y(y: Double): Vec3{
        return Vec3(0.0, y, 0.0)
    }

    fun vec3Y(): Vec3{
        return vec3Y(1.0)
    }

    fun vec3Z(z: Double): Vec3{
        return Vec3(0.0, 0.0, z)
    }

    fun vec3Z(): Vec3{
        return vec3Z(1.0)
    }

    fun vec3One(): Vec3{
        return vec3(1.0)
    }

    fun vec3Zero(): Vec3{
        return vec3(0.0)
    }
}
