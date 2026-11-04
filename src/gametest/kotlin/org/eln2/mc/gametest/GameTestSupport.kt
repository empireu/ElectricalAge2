package org.eln2.mc.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import org.eln2.mc.LOG
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.cells.foundation.CellGraph
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.common.parts.foundation.PartWithCell
import org.eln2.mc.mathematics.FacingDirection
import kotlin.math.abs

const val ARENA = "arena"
const val PART_LAYER = 2

fun gameTestFail(message: String): Nothing {
    throw GameTestAssertException(message)
}

fun checkThat(condition: Boolean, message: () -> String) {
    if (!condition) {
        gameTestFail(message())
    }
}

fun checkClose(expected: Double, actual: Double, tolerance: Double, label: String) {
    checkThat(abs(expected - actual) <= tolerance) {
        "$label: expected $expected within $tolerance, got $actual"
    }
}

/**
 * Places a part on top of a fresh stone block, at [x], [z] in the arena.
 * */
fun GameTestHelper.placePart(
    x: Int,
    z: Int,
    partItem: PartRegistry.PartRegistryItem,
    facing: FacingDirection = FacingDirection.NORTH,
    player: Player = makeMockPlayer()
): Part {
    try {
        setBlock(BlockPos(x, PART_LAYER - 1, z), Blocks.STONE)

        val relativePos = BlockPos(x, PART_LAYER, z)

        if (getBlockEntity(relativePos) == null) {
            setBlock(relativePos, BlockRegistry.MULTIPART_BLOCK.get())
        }

        val multipart = getBlockEntity(relativePos) as? MultipartBlockEntity
            ?: gameTestFail("No multipart at $relativePos")

        val isPlaced = multipart.place(
            player,
            absolutePos(relativePos),
            Direction.UP,
            partItem.part.get(),
            null,
            facing
        )

        checkThat(isPlaced) {
            "Could not place ${partItem.name} at $relativePos"
        }

        return multipart.getPart(Direction.UP)
            ?: gameTestFail("Part ${partItem.name} missing after placement")
    } catch (exception: RuntimeException) {
        LOG.error("Placing ${partItem.name} failed", exception)
        throw exception
    }
}

/**
 * Gets the part at [x], [z] in the arena.
 * */
fun GameTestHelper.partAt(x: Int, z: Int): Part {
    val relativePos = BlockPos(x, PART_LAYER, z)

    val multipart = getBlockEntity(relativePos) as? MultipartBlockEntity
        ?: gameTestFail("No multipart at $relativePos")

    return multipart.getPart(Direction.UP)
        ?: gameTestFail("No part at $relativePos")
}

/**
 * Breaks the part at [x], [z] in the arena.
 * */
fun GameTestHelper.breakPartAt(x: Int, z: Int) {
    val relativePos = BlockPos(x, PART_LAYER, z)

    val multipart = getBlockEntity(relativePos) as? MultipartBlockEntity
        ?: gameTestFail("No multipart at $relativePos")

    multipart.breakPart(partAt(x, z))
}

@Suppress("UNCHECKED_CAST")
fun <C : Cell> Part.cellAs(): C {
    val partWithCell = this as? PartWithCell<*>
        ?: gameTestFail("$this does not have a cell")

    return partWithCell.cell as C
}

fun Part.cell(): Cell = cellAs()

fun Part.graph(): CellGraph = cell().graph
