package org.eln2.mc.gametest

import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.blocks.BlockRegistry

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object SubstrateGameTests {
    private fun GameTestHelper.checkMultipartGone(x: Int, z: Int) {
        checkThat(getBlockEntity(arenaPosition(x, z)) == null) {
            "The multipart at $x, $z should be gone"
        }

        checkThat(getBlockState(arenaPosition(x, z)).isAir) {
            "The multipart block at $x, $z should be replaced by air"
        }
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun emptyMultipartIsRemovedByANeighborUpdate(helper: GameTestHelper) {
        val position = arenaPosition(2, 2)

        helper.setBlock(position.below(), Blocks.STONE)
        helper.setBlock(position, BlockRegistry.MULTIPART_BLOCK.get())
        helper.setBlock(position.below(), Blocks.AIR)

        helper.checkMultipartGone(2, 2)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun gridPartLosingItsSubstrateOnThePlacementTickBreaks(helper: GameTestHelper) {
        val position = arenaPosition(2, 2)

        helper.placePart(position, GROUND_ITEM)
        helper.setBlock(position.below(), Blocks.AIR)

        helper.checkMultipartGone(2, 2)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun gridPartLosingItsSubstrateAfterSyncingBreaks(helper: GameTestHelper) {
        val position = arenaPosition(2, 2)

        helper.placePart(position, GROUND_ITEM)
        helper.multipartAt(position).getUpdatePacket()

        helper.setBlock(position.below(), Blocks.AIR)

        helper.checkMultipartGone(2, 2)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun wireLosingItsSubstrateBreaks(helper: GameTestHelper) {
        val position = arenaPosition(2, 2)

        helper.placePart(position, ELECTRICAL_WIRE_ITEM)
        helper.setBlock(position.below(), Blocks.AIR)

        helper.checkMultipartGone(2, 2)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun losingOneSubstrateKeepsThePartOnTheOtherFace(helper: GameTestHelper) {
        val position = arenaPosition(2, 2)
        val floor = helper.placePart(position, ELECTRICAL_WIRE_ITEM, Direction.UP)
        val wall = helper.placePart(position, GROUND_ITEM, Direction.SOUTH)
        val multipart = helper.multipartAt(position)

        checkThat(floor !== wall) {
            "Expected two separate parts"
        }

        helper.setBlock(position.below(), Blocks.AIR)

        checkThat(multipart.getPart(Direction.UP) == null) {
            "The part on the removed substrate should break"
        }

        checkThat(multipart.getPart(Direction.SOUTH) === wall) {
            "The part on the other substrate should remain"
        }

        checkThat(helper.getBlockEntity(position) === multipart) {
            "The multipart should remain while it holds a part"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun removingTheMultipartBlockBreaksAllOfItsParts(helper: GameTestHelper) {
        val position = arenaPosition(2, 2)

        helper.placePart(position, ELECTRICAL_WIRE_ITEM, Direction.UP)
        helper.placePart(position, GROUND_ITEM, Direction.SOUTH)
        helper.setBlock(position, Blocks.AIR)

        helper.checkMultipartGone(2, 2)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun partBrokenBeforeItsPlacementWasSentProducesNoPlacementUpdate(helper: GameTestHelper) {
        val position = arenaPosition(2, 2)

        helper.placePart(position, ELECTRICAL_WIRE_ITEM, Direction.UP)

        val multipart = helper.multipartAt(position)

        helper.placePart(position, GROUND_ITEM, Direction.SOUTH)
        multipart.breakPart(helper.partAt(position, Direction.SOUTH))

        val tag = (multipart.getUpdatePacket() as ClientboundBlockEntityDataPacket).tag
            ?: gameTestFail("Expected an update tag")

        val updates = tag.getList("PlacementUpdates", 10)

        checkThat(updates.size == 1) {
            "Only the wire should be sent to clients, got $updates"
        }

        helper.succeed()
    }
}
