@file:Suppress("UNCHECKED_CAST")

package org.eln2.mc.data

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.ageseries.libage.data.Locator
import org.ageseries.libage.data.LocatorDispatcher
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.electrical.mna.NEGATIVE
import org.ageseries.libage.sim.electrical.mna.POSITIVE
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.common.GridConnectionCell
import org.eln2.mc.common.GridNode
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.cells.foundation.getNode
import org.eln2.mc.common.cells.foundation.requireNode
import org.eln2.mc.common.network.serverToClient.getBlockPos
import org.eln2.mc.common.network.serverToClient.putBlockPos
import org.eln2.mc.common.parts.foundation.getPartConnectionOrNull
import org.eln2.mc.common.specs.foundation.GridTerminal
import org.eln2.mc.extensions.*
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.mathematics.FacingDirection
import java.nio.ByteBuffer
import java.util.UUID

class SortedUUIDPair private constructor(val a: UUID, val b: UUID) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SortedUUIDPair

        if (a != other.a) return false
        if (b != other.b) return false

        return true
    }

    override fun hashCode(): Int {
        var result = a.hashCode()
        result = 31 * result + b.hashCode()
        return result
    }

    companion object {
        fun write(pair: SortedUUIDPair, buffer: ByteBuffer) {
            val a = pair.a
            buffer.putLong(a.mostSignificantBits)
            buffer.putLong(a.leastSignificantBits)

            val b = pair.b
            buffer.putLong(b.mostSignificantBits)
            buffer.putLong(b.leastSignificantBits)
        }

        fun read(buffer: ByteBuffer) : SortedUUIDPair {
            val a = UUID(buffer.getLong(), buffer.getLong())
            val b = UUID(buffer.getLong(), buffer.getLong())

            return SortedUUIDPair(a, b)
        }

        fun create(a: UUID, b: UUID) : SortedUUIDPair {
            require(a != b) {
                "Duplicate UUID pair"
            }

            return if(a < b) {
                SortedUUIDPair(a, b)
            }
            else {
                SortedUUIDPair(b, a)
            }
        }
    }
}

object Locators : LocatorDispatcher<Locators>() {
    private fun writeInt(int: Int, buffer: ByteBuffer) { buffer.putInt(int) }

    private fun readInt(buffer: ByteBuffer) = buffer.getInt()

    private fun writeBlockPos(blockPos: BlockPos, buffer: ByteBuffer) {
        buffer.putInt(blockPos.x)
        buffer.putInt(blockPos.y)
        buffer.putInt(blockPos.z)
    }

    private fun readBlockPos(buffer: ByteBuffer) = BlockPos(
        buffer.getInt(),
        buffer.getInt(),
        buffer.getInt()
    )

    private fun writeDirection(direction: Direction, buffer: ByteBuffer) { buffer.put(direction.get3DDataValue().toByte()) }

    private fun readDirection(buffer: ByteBuffer) = Direction.from3DDataValue(buffer.get().toInt())

    private fun writeFacingDirection(direction: FacingDirection, buffer: ByteBuffer) { buffer.put(direction.index.toByte()) }

    private fun readFacingDirection(buffer: ByteBuffer) = FacingDirection.byIndex(buffer.get().toInt())

    private fun writeVector3d(vector3d: Vector3d, buffer: ByteBuffer) {
        buffer.putDouble(vector3d.x)
        buffer.putDouble(vector3d.y)
        buffer.putDouble(vector3d.z)
    }

    private fun writeBlockPair(pair: Pair<BlockPos, BlockPos>, buffer: ByteBuffer) {
        buffer.putBlockPos(pair.first)
        buffer.putBlockPos(pair.second)
    }

    private fun readBlockPair(buffer: ByteBuffer) = Pair(
        buffer.getBlockPos(),
        buffer.getBlockPos()
    )

    private fun readVector3d(buffer: ByteBuffer) = Vector3d(
        buffer.getDouble(),
        buffer.getDouble(),
        buffer.getDouble()
    )

    val BLOCK = register<BlockPos>(
        ::writeBlockPos,
        ::readBlockPos,
        12
    )

    val FACE = register<Direction>(
        ::writeDirection,
        ::readDirection,
        1
    )

    val FACING = register<FacingDirection>(
        ::writeFacingDirection,
        ::readFacingDirection,
        1
    )

    val MOUNTING_POINT = register<Vector3d>(
        ::writeVector3d,
        ::readVector3d,
        24
    )

    val PLACEMENT_ID = register<Int>(
        ::writeInt,
        ::readInt,
        4
    )

    val GRID_ENDPOINT_PAIR = register<SortedUUIDPair>(
        SortedUUIDPair::write,
        SortedUUIDPair::read,
        32
    )

    val BLOCK_RANGE = register<Pair<BlockPos, BlockPos>>(
        ::writeBlockPair,
        ::readBlockPair,
        24
    )
}

fun interface LocationRelationshipRule {
    fun acceptsRelationship(descriptor: Locator, target: Locator): Boolean
}

class LocatorRelationRuleSet {
    private val rules = ArrayList<LocationRelationshipRule>()

    fun with(rule: LocationRelationshipRule): LocatorRelationRuleSet {
        rules.add(rule)
        return this
    }

    fun accepts(descriptor: Locator, target: Locator): Boolean {
        return rules.all { r -> r.acceptsRelationship(descriptor, target) }
    }
}

fun LocatorRelationRuleSet.withDirectionRulePlanar(mask: Base6Direction3dMask): LocatorRelationRuleSet {
    return this.with { a, b ->
        mask.has(a.findDirActualPlanarOrNull(b) ?: return@with false)
    }
}

fun LocatorRelationRuleSet.withDirectionRulePart(mask: Base6Direction3dMask): LocatorRelationRuleSet {
    return this.with { a, b ->
        mask.has(a.findDirActualPartOrNull(b) ?: return@with false)
    }
}


fun Locator.findDirActualPlanarOrNull(other: Locator): Base6Direction3d? {
    val a = this.get(Locators.BLOCK) ?: return null
    val b = this.get(Locators.FACING) ?: return null
    val c = this.get(Locators.FACE) ?: return null
    val d = other.get(Locators.BLOCK) ?: return null
    val dir = a.directionTo(d) ?: return null

    return Base6Direction3d.fromForwardUp(b, c, dir)
}

fun Locator.findDirActualPartOrNull(other: Locator): Base6Direction3d? {
    return getPartConnectionOrNull(this, other)?.directionPart
}

fun Locator.findDirActualPlanar(other: Locator): Base6Direction3d {
    return this.findDirActualPlanarOrNull(other) ?: error("Failed to get relative rotation direction")
}

fun Locator.findDirActualPart(other: Locator): Base6Direction3d {
    return this.findDirActualPartOrNull(other) ?: error("Failed to get relative rotation direction (part)")
}

enum class Pole(val conventionalPin: Int) {
    Plus(POSITIVE),
    Minus(NEGATIVE)
}

fun interface PoleMap {
    fun evaluateOrNull(sourceCell: Cell, targetCell: Cell): Pole?
}

fun PoleMap.evaluate(sourceCell: Cell, targetCell: Cell): Pole =
    checkNotNull(evaluateOrNull(sourceCell, targetCell)) {
        "Unhandled pole map direction $sourceCell $targetCell $this"
    }

/**
 * Creates a [PoleMap] that maps [plusDir] to plus and [minusDir] to minus.
 * These directions are in the observer's frame. Fused positions are not allowed (like with multiparts)
 * This means that, from the object's perspective, [Pole.Plus] is returned when the other object is towards [plusDir], and [Pole.Minus] is returned when the target is towards [minusDir].
 * */
fun directionPoleMapPlanar(plusDir: Base6Direction3d = Base6Direction3d.Front, minusDir: Base6Direction3d = Base6Direction3d.Back) =
    PoleMap { c1, c2 ->
        when (c1.locator.findDirActualPlanarOrNull(c2.locator)) {
            plusDir -> Pole.Plus
            minusDir -> Pole.Minus
            else -> null
        }
    }

/**
 * Creates a [PoleMap] that maps [plusDir] to plus and [minusDir] to minus.
 * These directions are in the observer's frame.
 * This means that, from the object's perspective, [Pole.Plus] is returned when the other object is towards [plusDir], and [Pole.Minus] is returned when the target is towards [minusDir].
 * */
fun directionPoleMapPart(plusDir: Base6Direction3d = Base6Direction3d.Front, minusDir: Base6Direction3d = Base6Direction3d.Back) =
    PoleMap { c1, c2 ->
        when (c1.locator.findDirActualPartOrNull(c2.locator)) {
            plusDir -> Pole.Plus
            minusDir -> Pole.Minus
            else -> null
        }
    }
