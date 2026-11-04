package org.eln2.mc.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.content.modules.Eln2Wires
import org.eln2.mc.extensions.destroyPart

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object ConnectivityGameTests {
    private val WIRE get() = Eln2Wires.STANDARD_INSULATED_COPPER_ELECTRICAL_WIRE.part
    private val SIGNAL_WIRE get() = Eln2Wires.SIGNAL_WIRE.part
    private val THERMAL_WIRE get() = Eln2Wires.STANDARD_UNINSULATED_COPPER_THERMAL_WIRE.part

    @GameTest(template = ARENA)
    @JvmStatic
    fun partsOnAdjacentFacesOfOneBlockConnect(helper: GameTestHelper) {
        val position = BlockPos(5, 2, 5)
        val floor = helper.placePart(position, WIRE, Direction.UP)
        val wall = helper.placePart(position, WIRE, Direction.SOUTH)

        checkConnected(floor, wall)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun partsOnOppositeFacesOfOneBlockDoNotConnect(helper: GameTestHelper) {
        val position = BlockPos(5, 2, 5)
        val floor = helper.placePart(position, WIRE, Direction.UP)
        val ceiling = helper.placePart(position, WIRE, Direction.DOWN)

        checkNotConnected(floor, ceiling)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun wiresWrapAroundAnExposedBlockCorner(helper: GameTestHelper) {
        val top = helper.placePart(BlockPos(5, 2, 5), WIRE, Direction.UP)
        val side = helper.placePart(BlockPos(6, 1, 5), WIRE, Direction.EAST)

        checkConnected(top, side)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun blockingTheCornerPreventsWrapping(helper: GameTestHelper) {
        helper.setBlock(BlockPos(6, 2, 5), Blocks.STONE)

        val top = helper.placePart(BlockPos(5, 2, 5), WIRE, Direction.UP)
        val side = helper.placePart(BlockPos(6, 1, 5), WIRE, Direction.EAST)

        checkNotConnected(top, side)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun wiresOnDifferentPlanesDoNotConnect(helper: GameTestHelper) {
        val lower = helper.placePart(BlockPos(5, 2, 5), WIRE, Direction.UP)
        val upper = helper.placePart(BlockPos(6, 3, 5), WIRE, Direction.UP)

        checkNotConnected(lower, upper)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun diagonalWiresDoNotConnect(helper: GameTestHelper) {
        val first = helper.placePart(5, 5, WIRE)
        val second = helper.placePart(6, 6, WIRE)

        checkNotConnected(first, second)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun signalAndPowerWiresDoNotConnect(helper: GameTestHelper) {
        val power = helper.placePart(5, 5, WIRE)
        val signal = helper.placePart(6, 5, SIGNAL_WIRE)

        checkNotConnected(power, signal)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun thermalAndElectricalWiresDoNotConnect(helper: GameTestHelper) {
        val electrical = helper.placePart(5, 5, WIRE)
        val thermal = helper.placePart(6, 5, THERMAL_WIRE)

        checkNotConnected(electrical, thermal)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun wireCanFormACycle(helper: GameTestHelper) {
        val first = helper.placePart(5, 5, WIRE)
        val second = helper.placePart(6, 5, WIRE)
        val third = helper.placePart(6, 6, WIRE)
        val fourth = helper.placePart(5, 6, WIRE)

        checkConnected(first, second)
        checkConnected(second, third)
        checkConnected(third, fourth)
        checkConnected(fourth, first)

        checkThat(first.graph().size == 4) {
            "Expected 4 cells in the cycle, got ${first.graph().size}"
        }

        val originalGraph = first.graph()

        helper.breakPartAt(6, 5)

        checkThat(first.graph() == third.graph()) {
            "Breaking one wire of a cycle split the graph"
        }

        checkThat(!originalGraph.manager.contains(originalGraph.id) || first.graph() == originalGraph) {
            "The graph bookkeeping is inconsistent after breaking the cycle"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun breakingTheSubstrateBreaksThePart(helper: GameTestHelper) {
        val position = arenaPosition(5, 5)
        val wire = helper.placePart(position, WIRE)
        val graph = wire.graph()

        helper.destroyBlock(position.below())

        helper.succeedWhen {
            checkThat(helper.partOrNullAt(position) == null) {
                "The part survived the removal of its substrate"
            }

            checkThat(!graph.manager.contains(graph.id)) {
                "The graph of the broken part is still tracked"
            }
        }
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun breakingTheLastPartRemovesTheMultipart(helper: GameTestHelper) {
        val position = arenaPosition(5, 5)

        val wire = helper.placePart(position, WIRE)

        helper.level.destroyPart(wire, false)

        helper.succeedWhen {
            checkThat(helper.getBlockEntity(position) == null) {
                "The multipart is still there after breaking its last part"
            }
        }
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun twoPartsCannotShareAFace(helper: GameTestHelper) {
        val position = arenaPosition(5, 5)

        helper.placePart(position, WIRE)

        val isPlaced = helper.multipartAt(position).place(
            helper.makeMockPlayer(),
            helper.absolutePos(position),
            Direction.UP,
            WIRE.part.get()
        )

        checkThat(!isPlaced) {
            "A second part was placed on an occupied face"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun partItemPlacesPartWhenUsedOnABlock(helper: GameTestHelper) {
        val substrate = BlockPos(5, 1, 5)
        val position = substrate.above()

        helper.setBlock(substrate, Blocks.STONE)

        val player = helper.makeMockPlayer()
        val absoluteSubstrate = helper.absolutePos(substrate)

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(WIRE.item.get()))

        val context = UseOnContext(
            player,
            InteractionHand.MAIN_HAND,
            BlockHitResult(Vec3.atCenterOf(absoluteSubstrate), Direction.UP, absoluteSubstrate, false)
        )

        WIRE.item.get().useOn(context)

        val part = helper.partAt(position)

        checkThat(part.graph().size == 1) {
            "Expected the placed part to be in a graph of 1, got ${part.graph().size}"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun partItemDoesNotPlaceOnNonFullBlocks(helper: GameTestHelper) {
        val substrate = BlockPos(5, 1, 5)

        helper.setBlock(substrate, Blocks.STONE_SLAB)

        val player = helper.makeMockPlayer()
        val absoluteSubstrate = helper.absolutePos(substrate)

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(WIRE.item.get()))

        WIRE.item.get().useOn(
            UseOnContext(
                player,
                InteractionHand.MAIN_HAND,
                BlockHitResult(Vec3.atCenterOf(absoluteSubstrate), Direction.UP, absoluteSubstrate, false)
            )
        )

        checkThat(helper.getBlockEntity(substrate.above()) == null) {
            "A part was placed on a slab"
        }

        helper.succeed()
    }
}
