@file:Suppress("NOTHING_TO_INLINE")

package org.eln2.mc.client.render.foundation

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockAndTintGetter
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.avg
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.mathematics.*
import kotlin.math.roundToInt
import kotlin.math.sqrt

inline fun unpackBlockLight(value: Int) = value and 0xFF
inline fun unpackSkyLight(value: Int) = value shr 16 and 0xFF

class CachingLightReader(val level: BlockAndTintGetter) {
    val cache = Long2IntOpenHashMap()

    inline fun getLightColor(long: Long): Int = cache.computeIfAbsent(long) {
        LevelRenderer.getLightColor(level, BlockPos.of(long))
    }
}

class NeighborLightReader(val reader: CachingLightReader) {
    val values = IntArray(6)
    var lastX = Int.MAX_VALUE
    var lastY = Int.MAX_VALUE
    var lastZ = Int.MAX_VALUE

    inline fun get(layer: Int, neighbor: Int) = (values[neighbor] shr (layer * 16)) and 0xFF

    inline fun load(x: Int, y: Int, z: Int) {
        if(lastX == x && lastY == y && lastZ == z) {
            return
        }

        lastX = x
        lastY = y
        lastZ = z

        for (i in 0..5) {
            val neighbor = BlockPos.asLong(
                x + directionIncrementX(i),
                y + directionIncrementY(i),
                z + directionIncrementZ(i)
            )

            values[i] = reader.getLightColor(neighbor)
        }
    }
}

inline fun getDiffuseLight(layer: Int, neighborValues: NeighborLightReader, normalX: Float, normalY: Float, normalZ: Float): Double {
    var light = 0.0
    var normSqr = 0.0

    if (normalX > 0) {
        val n5 = neighborValues.get(layer, 5)
        light += normalX * n5
        normSqr += (n5 * n5) / 65025.0
    } else {
        val n4 = neighborValues.get(layer, 4)
        light -= normalX * n4
        normSqr += (n4 * n4) / 65025.0
    }

    if (normalY > 0) {
        val n1 = neighborValues.get(layer, 1)
        light += normalY * n1
        normSqr += (n1 * n1) / 65025.0
    } else {
        val n0 = neighborValues.get(layer, 0)
        light -= normalY * n0
        normSqr += (n0 * n0) / 65025.0
    }

    if (normalZ > 0) {
        val n3 = neighborValues.get(layer, 3)
        light += normalZ * n3
        normSqr += (n3 * n3) / 65025.0
    } else {
        val n2 = neighborValues.get(layer, 2)
        light -= normalZ * n2
        normSqr += (n2 * n2) / 65025.0
    }

    if(light.approxEq(0.0) || normSqr.approxEq(0.0)) {
        return 0.0
    }

    return light / sqrt(normSqr)
}

inline fun combineLight(layer: Int, neighborLightValues: NeighborLightReader, normalX: Float, normalY: Float, normalZ: Float, localLight: Double) : Int {
    val diffuseContribution = getDiffuseLight(layer, neighborLightValues, normalX, normalY, normalZ)

    if(diffuseContribution.isNaN() || localLight.isNaN()) {
        return 0
    }

    return avg(diffuseContribution, localLight).roundToInt() shr 4
}
