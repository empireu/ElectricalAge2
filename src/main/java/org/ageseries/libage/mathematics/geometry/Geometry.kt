@file:Suppress("LocalVariableName", "MemberVisibilityCanBePrivate")

package org.ageseries.libage.mathematics.geometry

import org.ageseries.libage.mathematics.*
import kotlin.math.*

const val GEOMETRY_COMPARE_EPS = 1e-6
const val GEOMETRY_NORMALIZED_EPS = 1e-7

inline fun dda(ray: Ray3d, withSource: Boolean = true, user: (Int, Int, Int) -> Boolean) {
    var x = floor(ray.origin.x).toInt()
    var y = floor(ray.origin.y).toInt()
    var z = floor(ray.origin.z).toInt()

    if (withSource) {
        if (!user(x, y, z)) {
            return
        }
    }

    val sx = snzi(ray.direction.x)
    val sy = snzi(ray.direction.y)
    val sz = snzi(ray.direction.z)

    val nextBX = x + sx + if (sx.sign == -1) 1
    else 0
    val nextBY = y + sy + if (sy.sign == -1) 1
    else 0
    val nextBZ = z + sz + if (sz.sign == -1) 1
    else 0

    var tmx = if (ray.direction.x != 0.0) (nextBX - ray.origin.x) / ray.direction.x else Double.MAX_VALUE
    var tmy = if (ray.direction.y != 0.0) (nextBY - ray.origin.y) / ray.direction.y else Double.MAX_VALUE
    var tmz = if (ray.direction.z != 0.0) (nextBZ - ray.origin.z) / ray.direction.z else Double.MAX_VALUE
    val tdx = if (ray.direction.x != 0.0) 1.0 / ray.direction.x * sx else Double.MAX_VALUE
    val tdy = if (ray.direction.y != 0.0) 1.0 / ray.direction.y * sy else Double.MAX_VALUE
    val tdz = if (ray.direction.z != 0.0) 1.0 / ray.direction.z * sz else Double.MAX_VALUE

    while (true) {
        if (tmx < tmy) {
            if (tmx < tmz) {
                x += sx
                tmx += tdx
            } else {
                z += sz
                tmz += tdz
            }
        } else {
            if (tmy < tmz) {
                y += sy
                tmy += tdy
            } else {
                z += sz
                tmz += tdz
            }
        }

        if (!user(x, y, z)) {
            return
        }
    }
}