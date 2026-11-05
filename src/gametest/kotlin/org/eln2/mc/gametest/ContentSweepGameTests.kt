package org.eln2.mc.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import org.eln2.mc.common.cells.foundation.CellContainer
import org.eln2.mc.common.cells.foundation.CellGraph
import org.eln2.mc.common.cells.foundation.CellGraphManager

private const val SWEEP_RADIUS = 12
private const val SWEEP_HEIGHT = 16
private const val TICKS_PER_ITEM = 6

private val SWEEP_CENTER = BlockPos(4104, 100, 4104)

/**
 * Places, simulates, saves and breaks every placeable item of the mod on a dedicated server.
 * */
@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object ContentSweepGameTests {
    private class Sweep(val helper: GameTestHelper, val player: ServerPlayer) {
        val level = helper.level
        val manager = CellGraphManager.getFor(level)
        val failures = ArrayList<String>()
        val notPlaced = ArrayList<String>()
        val graphs = HashSet<CellGraph>()
        var placedCount = 0

        fun forEachPosition(consumer: (BlockPos) -> Unit) {
            for (y in 0..SWEEP_HEIGHT) {
                for (x in -SWEEP_RADIUS..SWEEP_RADIUS) {
                    for (z in -SWEEP_RADIUS..SWEEP_RADIUS) {
                        consumer(SWEEP_CENTER.offset(x, y, z))
                    }
                }
            }
        }

        fun prepareArea() {
            val center = ChunkPos(SWEEP_CENTER)

            for (x in -1..1) {
                for (z in -1..1) {
                    level.setChunkForced(center.x + x, center.z + z, true)
                }
            }

            for (x in -SWEEP_RADIUS..SWEEP_RADIUS) {
                for (z in -SWEEP_RADIUS..SWEEP_RADIUS) {
                    level.setBlock(SWEEP_CENTER.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2)
                }
            }

            forEachPosition {
                if (!level.getBlockState(it).isAir) {
                    level.setBlock(it, Blocks.AIR.defaultBlockState(), 2)
                }
            }
        }

        fun releaseArea() {
            val center = ChunkPos(SWEEP_CENTER)

            for (x in -1..1) {
                for (z in -1..1) {
                    level.setChunkForced(center.x + x, center.z + z, false)
                }
            }
        }

        fun guarded(item: Item, stage: String, action: () -> Unit) {
            try {
                action()
            } catch (exception: Exception) {
                LOG.error("SWEEP {} failed at {}", item, stage, exception)
                failures.add("$item failed at $stage: $exception")
            }
        }

        fun place(item: Item) {
            val floor = SWEEP_CENTER.below()

            player.moveTo(SWEEP_CENTER.x + 0.5, SWEEP_CENTER.y + 4.0, SWEEP_CENTER.z + 0.5, 0.0f, 90.0f)
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(item))

            val hit = BlockHitResult(Vec3.atCenterOf(floor).add(0.0, 0.5, 0.0), Direction.UP, floor, false)

            item.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))

            if (level.getBlockState(SWEEP_CENTER).isAir) {
                notPlaced.add(item.toString())
            } else {
                placedCount++
            }
        }

        fun saveAndRestore(item: Item) {
            graphs.clear()

            forEachPosition {
                (level.getBlockEntity(it) as? CellContainer)?.getCells()?.forEach { cell ->
                    graphs.add(cell.graph)
                }
            }

            graphs.forEach { graph ->
                val tag = graph.runSuspended {
                    graph.toNbt()
                }

                val restored = CellGraph.fromNbt(tag, CellGraphManager(level), level)

                if (restored.size != graph.size) {
                    failures.add("$item: expected ${graph.size} cells after restoring, got ${restored.size}")
                }
            }
        }

        fun breakPlaced() {
            if (!level.getBlockState(SWEEP_CENTER).isAir) {
                level.destroyBlock(SWEEP_CENTER, false)
            }
        }

        fun checkClean(item: Item) {
            val leftovers = ArrayList<String>()

            forEachPosition {
                val state = level.getBlockState(it)

                if (!state.isAir) {
                    leftovers.add("$state at ${it.subtract(SWEEP_CENTER)}")
                    level.setBlock(it, Blocks.AIR.defaultBlockState(), 2)
                }
            }

            if (leftovers.isNotEmpty()) {
                failures.add("$item left ${leftovers.size} blocks behind: ${leftovers.take(4)}")
            }

            graphs.forEach { graph ->
                if (manager.contains(graph.id)) {
                    failures.add("$item left its graph ${graph.id} with ${graph.size} cells in the manager")
                }
            }

            level.getEntitiesOfClass(ItemEntity::class.java, AABB(SWEEP_CENTER).inflate(SWEEP_RADIUS + 4.0)).forEach {
                it.discard()
            }
        }
    }

    private fun placeableItems() = ForgeRegistries.ITEMS.entries
        .filter { it.key.location().namespace == MODID && it.value is BlockItem }
        .sortedBy { it.key.location().path }
        .map { it.value }

    @GameTest(template = ARENA, timeoutTicks = 4000)
    @JvmStatic
    fun everyItemCanBePlacedSimulatedSavedAndBroken(helper: GameTestHelper) {
        val sweep = Sweep(helper, FakePlayerFactory.getMinecraft(helper.level))
        val items = placeableItems()
        var sequence = helper.startSequence().thenExecute { sweep.prepareArea() }

        items.forEach { item ->
            sequence = sequence
                .thenExecuteAfter(1) { sweep.guarded(item, "placing") { sweep.place(item) } }
                .thenExecuteAfter(TICKS_PER_ITEM - 3) { sweep.guarded(item, "saving") { sweep.saveAndRestore(item) } }
                .thenExecuteAfter(1) { sweep.guarded(item, "breaking") { sweep.breakPlaced() } }
                .thenExecuteAfter(1) { sweep.guarded(item, "checking") { sweep.checkClean(item) } }
        }

        sequence
            .thenExecute {
                sweep.releaseArea()

                LOG.info(
                    "SWEEP placed {} of {} items, not placed: {}",
                    sweep.placedCount,
                    items.size,
                    sweep.notPlaced
                )

                checkThat(sweep.failures.isEmpty()) {
                    sweep.failures.forEach { LOG.error("SWEEP {}", it) }

                    "${sweep.failures.size} problems, first: ${sweep.failures.take(3)}"
                }

                checkThat(sweep.placedCount > 40) {
                    "Only ${sweep.placedCount} of ${items.size} items could be placed"
                }
            }
            .thenSucceed()
    }
}
