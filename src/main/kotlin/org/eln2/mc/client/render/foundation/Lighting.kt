@file:Suppress("NOTHING_TO_INLINE")

package org.eln2.mc.client.render.foundation

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockAndTintGetter
import org.ageseries.libage.mathematics.Vector3d
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.avg
import org.eln2.mc.mathematics.*
import kotlin.math.roundToInt
import kotlin.math.sqrt

inline fun unpackBlockLight(value: Int) = value and 0xFF
inline fun unpackSkyLight(value: Int) = value shr 16 and 0xFF

class CachingLightReader(val level: BlockAndTintGetter) {
    val cache = Long2IntOpenHashMap()

    inline fun getLightColor(blockPos: BlockPos): Int = cache.computeIfAbsent(blockPos.asLong()) {
        LevelRenderer.getLightColor(level, blockPos)
    }
}

class NeighborLightReader(val reader: CachingLightReader) {
    val values = IntArray(12)

    private var lastBlockPos: BlockPos? = null

    inline fun get(layer: Int, neighbor: Int) : Int {
        return values[layer * 6 + neighbor]
    }

    inline fun set(layer: Int, neighbor: Int, value: Int) {
        values[layer * 6 + neighbor] = value
    }

    fun load(blockPos: BlockPos) {
        if(blockPos == lastBlockPos) {
            return
        }

        lastBlockPos = blockPos

        for (i in 0..5) {
            val value = reader.getLightColor(
                BlockPos(
                    blockPos.x + directionIncrementX(i),
                    blockPos.y + directionIncrementY(i),
                    blockPos.z + directionIncrementZ(i)
                )
            )

            set(0, i, unpackBlockLight(value))
            set(1, i, unpackSkyLight(value))
        }
    }
}

fun getDiffuseLight(layer: Int, neighborValues: NeighborLightReader, normal: Vector3d): Double {
    var light = 0.0
    var normSqr = 0.0

    if (normal.x > 0) {
        val n5 = neighborValues.get(layer, 5)
        light += normal.x * n5
        normSqr += (n5 * n5) / 65025.0
    } else {
        val n4 = neighborValues.get(layer, 4)
        light -= normal.x * n4
        normSqr += (n4 * n4) / 65025.0
    }

    if (normal.y > 0) {
        val n1 = neighborValues.get(layer, 1)
        light += normal.y * n1
        normSqr += (n1 * n1) / 65025.0
    } else {
        val n0 = neighborValues.get(layer, 0)
        light -= normal.y * n0
        normSqr += (n0 * n0) / 65025.0
    }

    if (normal.z > 0) {
        val n3 = neighborValues.get(layer, 3)
        light += normal.z * n3
        normSqr += (n3 * n3) / 65025.0
    } else {
        val n2 = neighborValues.get(layer, 2)
        light -= normal.z * n2
        normSqr += (n2 * n2) / 65025.0
    }

    if(light.approxEq(0.0) || normSqr.approxEq(0.0)) {
        return 0.0
    }

    return light / sqrt(normSqr)
}

fun combineLight(layer: Int, neighborLightValues: NeighborLightReader, normal: Vector3d, localLight: Double) : Int {
    val diffuseContribution = getDiffuseLight(layer, neighborLightValues, normal)

    if(diffuseContribution.isNaN() || localLight.isNaN()) {
        return 0
    }

    return avg(diffuseContribution, localLight).roundToInt() shr 4
}
