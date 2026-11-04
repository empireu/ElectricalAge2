package org.eln2.mc.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
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

/**
 * Simulated seconds that pass per server tick: 5 substeps of 0.01 seconds.
 * */
const val SECONDS_PER_TICK = CellGraph.SUBSTEPS * CellGraph.DT

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

fun arenaPosition(x: Int, z: Int) = BlockPos(x, PART_LAYER, z)

/**
 * Places a part in the multipart at [position] on the [face], creating a stone substrate behind it if there is none.
 * */
fun GameTestHelper.placePart(
    position: BlockPos,
    partItem: PartRegistry.PartRegistryItem,
    face: Direction = Direction.UP,
    facing: FacingDirection = FacingDirection.NORTH,
    player: Player = makeMockPlayer()
): Part {
    val substrate = position.relative(face.opposite)

    if (getBlockState(substrate).isAir) {
        setBlock(substrate, Blocks.STONE)
    }

    if (getBlockEntity(position) == null) {
        setBlock(position, BlockRegistry.MULTIPART_BLOCK.get())
    }

    val multipart = multipartAt(position)

    val isPlaced = multipart.place(
        player,
        absolutePos(position),
        face,
        partItem.part.get(),
        null,
        facing
    )

    checkThat(isPlaced) {
        "Could not place ${partItem.name} at $position on $face"
    }

    return multipart.getPart(face)
        ?: gameTestFail("Part ${partItem.name} missing after placement")
}

/**
 * Places a part on top of a stone block, at [x], [z] in the arena.
 * */
fun GameTestHelper.placePart(
    x: Int,
    z: Int,
    partItem: PartRegistry.PartRegistryItem,
    facing: FacingDirection = FacingDirection.NORTH
) = placePart(arenaPosition(x, z), partItem, Direction.UP, facing)

fun GameTestHelper.multipartAt(position: BlockPos): MultipartBlockEntity =
    getBlockEntity(position) as? MultipartBlockEntity
        ?: gameTestFail("No multipart at $position")

fun GameTestHelper.partOrNullAt(position: BlockPos, face: Direction = Direction.UP): Part? =
    (getBlockEntity(position) as? MultipartBlockEntity)?.getPart(face)

fun GameTestHelper.partAt(position: BlockPos, face: Direction = Direction.UP): Part =
    partOrNullAt(position, face)
        ?: gameTestFail("No part at $position on $face")

fun GameTestHelper.partAt(x: Int, z: Int): Part = partAt(arenaPosition(x, z))

fun GameTestHelper.breakPartAt(position: BlockPos, face: Direction = Direction.UP) {
    multipartAt(position).breakPart(partAt(position, face))
}

fun GameTestHelper.breakPartAt(x: Int, z: Int) = breakPartAt(arenaPosition(x, z))

@Suppress("UNCHECKED_CAST")
fun <C : Cell> Part.cellAs(): C {
    val partWithCell = this as? PartWithCell<*>
        ?: gameTestFail("$this does not have a cell")

    return partWithCell.cell as C
}

fun Part.cell(): Cell = cellAs()

fun Part.graph(): CellGraph = cell().graph

fun Part.isConnectedTo(other: Part) = cell().connections.contains(other.cell())

/**
 * Checks that the connection between the parts is present on both sides.
 * */
fun checkConnected(first: Part, second: Part) {
    checkThat(first.isConnectedTo(second) && second.isConnectedTo(first)) {
        "Expected ${first.cell().id} and ${second.cell().id} to be connected"
    }

    checkThat(first.graph() == second.graph()) {
        "Connected cells ${first.cell().id} and ${second.cell().id} are in different graphs"
    }
}

/**
 * Checks that there is no connection between the parts on either side.
 * */
fun checkNotConnected(first: Part, second: Part) {
    checkThat(!first.isConnectedTo(second) && !second.isConnectedTo(first)) {
        "Expected ${first.cell().id} and ${second.cell().id} not to be connected"
    }
}
