package org.eln2.mc.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.Quantity
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.cells.foundation.CellGraphManager
import org.eln2.mc.common.content.DiodeCell
import org.eln2.mc.common.content.SwitchCell
import org.eln2.mc.common.content.ThermalWireCell
import org.eln2.mc.common.content.VoltageSourceCell
import org.eln2.mc.common.content.modules.Eln2BasicComponents
import org.eln2.mc.common.content.modules.Eln2Wires
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.common.parts.foundation.PartWithCell
import org.eln2.mc.mathematics.FacingDirection
import kotlin.math.abs

private const val SAVED_TEMPERATURE = 500.0
private const val DEFAULT_SOURCE_POTENTIAL = 240.0
private const val DIODE_REVERSE_RESISTANCE = 10000.0

private val ORIGIN = BlockPos(1024, -40, 1024)

private val SOURCE_POSITION = ORIGIN.offset(0, 0, 4)
private val FIRST_WIRE_POSITION = ORIGIN.offset(0, 0, 3)
private val DIODE_POSITION = ORIGIN.offset(0, 0, 2)
private val SECOND_WIRE_POSITION = ORIGIN.offset(0, 0, 1)
private val GROUND_POSITION = ORIGIN
private val HOT_WIRE_POSITION = ORIGIN.offset(3, 0, 0)
private val HOT_NEIGHBOR_POSITION = ORIGIN.offset(4, 0, 0)
private val SWITCH_POSITION = ORIGIN.offset(3, 0, 3)
private val REPLACED_WIRE_POSITION = ORIGIN.offset(3, 0, 6)
private val REPLACED_NEIGHBOR_POSITION = ORIGIN.offset(4, 0, 6)

private val POWER_WIRE get() = Eln2Wires.STANDARD_INSULATED_COPPER_ELECTRICAL_WIRE.part
private val THERMAL_WIRE get() = Eln2Wires.STANDARD_INSULATED_COPPER_THERMAL_WIRE.part

private fun ServerLevel.forceOrigin(forced: Boolean) {
    val chunk = ChunkPos(ORIGIN)

    setChunkForced(chunk.x, chunk.z, forced)
}

private fun GameTestHelper.placeAbsolute(
    position: BlockPos,
    partItem: PartRegistry.PartRegistryItem,
    facing: FacingDirection = FacingDirection.NORTH
): Part {
    level.setBlockAndUpdate(position.below(), Blocks.STONE.defaultBlockState())

    if (level.getBlockEntity(position) == null) {
        level.setBlockAndUpdate(position, BlockRegistry.MULTIPART_BLOCK.get().defaultBlockState())
    }

    val multipart = level.getBlockEntity(position) as MultipartBlockEntity

    val isPlaced = multipart.place(makeMockPlayer(), position, Direction.UP, partItem.part.get(), null, facing)

    checkThat(isPlaced) {
        "Could not place ${partItem.name} at $position"
    }

    return multipart.getPart(Direction.UP)!!
}

private fun GameTestHelper.absolutePartOrNull(position: BlockPos) =
    (level.getBlockEntity(position) as? MultipartBlockEntity)?.getPart(Direction.UP)

private fun GameTestHelper.absolutePart(position: BlockPos) =
    absolutePartOrNull(position)
        ?: gameTestFail("No part at $position after the restart")

/**
 * Builds the scene that is checked after the restart by [RestartLoadGameTests].
 * */
@GameTestHolder("eln2_save")
@PrefixGameTestTemplate(false)
object RestartSaveGameTests {
    @GameTest(template = ARENA)
    @JvmStatic
    fun buildSceneToBeSaved(helper: GameTestHelper) {
        helper.level.forceOrigin(true)

        val source = helper.placeAbsolute(SOURCE_POSITION, Eln2BasicComponents.VOLTAGE_SOURCE_PART)
        val firstWire = helper.placeAbsolute(FIRST_WIRE_POSITION, POWER_WIRE)
        val diode = helper.placeAbsolute(DIODE_POSITION, Eln2BasicComponents.DIODE_PART, FacingDirection.SOUTH)
        val secondWire = helper.placeAbsolute(SECOND_WIRE_POSITION, POWER_WIRE)
        val ground = helper.placeAbsolute(GROUND_POSITION, Eln2BasicComponents.GROUND_PART)

        checkConnected(source, firstWire)
        checkConnected(firstWire, diode)
        checkConnected(diode, secondWire)
        checkConnected(secondWire, ground)

        val hotWire = helper.placeAbsolute(HOT_WIRE_POSITION, THERMAL_WIRE)
        val hotNeighbor = helper.placeAbsolute(HOT_NEIGHBOR_POSITION, THERMAL_WIRE)

        checkConnected(hotWire, hotNeighbor)

        hotWire.graph().runSuspended {
            hotWire.cellAs<ThermalWireCell>().thermalWire.thermalBody.temperature = Quantity(SAVED_TEMPERATURE, KELVIN)
            hotNeighbor.cellAs<ThermalWireCell>().thermalWire.thermalBody.temperature = Quantity(SAVED_TEMPERATURE, KELVIN)
        }

        val switch = helper.placeAbsolute(SWITCH_POSITION, Eln2BasicComponents.SWITCH_PART)
        val switchCell = switch.cellAs<SwitchCell>()

        switchCell.requestSetClosed(true)

        helper.placeAbsolute(REPLACED_WIRE_POSITION, POWER_WIRE)
        helper.placeAbsolute(REPLACED_NEIGHBOR_POSITION, POWER_WIRE)

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkThat(switchCell.isClosed) {
                    "The switch did not close before saving"
                }

                helper.level.forceOrigin(false)
            }
            .thenSucceed()
    }
}

/**
 * Checks the scene built by [RestartSaveGameTests] after the server restarted.
 * */
@GameTestHolder("eln2_load")
@PrefixGameTestTemplate(false)
object RestartLoadGameTests {
    private fun GameTestHelper.waitForScene(checks: () -> Unit) {
        level.forceOrigin(true)

        succeedWhen {
            val part = absolutePartOrNull(SOURCE_POSITION)
                ?: gameTestFail("Waiting for the chunk to load")

            checkThat((part as PartWithCell<*>).hasCell) {
                "Waiting for the part to acquire its cell"
            }

            checks()
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun circuitIsRestoredAndConducts(helper: GameTestHelper) {
        helper.waitForScene {
            val source = helper.absolutePart(SOURCE_POSITION)
            val firstWire = helper.absolutePart(FIRST_WIRE_POSITION)
            val diode = helper.absolutePart(DIODE_POSITION)
            val secondWire = helper.absolutePart(SECOND_WIRE_POSITION)
            val ground = helper.absolutePart(GROUND_POSITION)

            checkConnected(source, firstWire)
            checkConnected(firstWire, diode)
            checkConnected(diode, secondWire)
            checkConnected(secondWire, ground)

            checkThat(source.graph().size == 5) {
                "Expected 5 cells in the restored circuit, got ${source.graph().size}"
            }

            val current = source.graph().runSuspended {
                abs(source.cellAs<VoltageSourceCell>().voltageSource.source.current)
            }

            val expected = DEFAULT_SOURCE_POTENTIAL / DIODE_REVERSE_RESISTANCE

            checkClose(expected, current, expected * 0.02, "Current after the restart")

            checkThat(!diode.cellAs<DiodeCell>().electrical.diode.isConducting) {
                "The reversed diode is conducting after the restart"
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun temperatureIsRestored(helper: GameTestHelper) {
        helper.waitForScene {
            val hotWire = helper.absolutePart(HOT_WIRE_POSITION)
            val hotNeighbor = helper.absolutePart(HOT_NEIGHBOR_POSITION)

            checkConnected(hotWire, hotNeighbor)

            val temperature = hotWire.graph().runSuspended {
                !hotWire.cellAs<ThermalWireCell>().thermalWire.thermalBody.temperature
            }

            checkClose(SAVED_TEMPERATURE, temperature, 5.0, "Temperature after the restart")
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun switchStateIsRestored(helper: GameTestHelper) {
        helper.waitForScene {
            val switch = helper.absolutePart(SWITCH_POSITION)

            checkThat(switch.cellAs<SwitchCell>().isClosed) {
                "The switch is open after the restart"
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun restoredGraphsCanStillBeEdited(helper: GameTestHelper) {
        helper.waitForScene {
            val wire = helper.absolutePart(REPLACED_WIRE_POSITION)
            val neighbor = helper.absolutePart(REPLACED_NEIGHBOR_POSITION)
            val manager = CellGraphManager.getFor(helper.level)

            checkConnected(wire, neighbor)

            helper.level.getBlockEntity(REPLACED_WIRE_POSITION).let {
                (it as MultipartBlockEntity).breakPart(wire)
            }

            checkThat(neighbor.cell().connections.isEmpty()) {
                "The neighbor is still connected after breaking the restored wire"
            }

            val replacement = helper.placeAbsolute(REPLACED_WIRE_POSITION, POWER_WIRE)

            checkConnected(replacement, neighbor)

            checkThat(manager.contains(replacement.graph().id)) {
                "The manager does not track the graph of the replacement"
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun everyPartHasACellFromTheLoadedManager(helper: GameTestHelper) {
        helper.waitForScene {
            val manager = CellGraphManager.getFor(helper.level)

            listOf(SOURCE_POSITION, FIRST_WIRE_POSITION, DIODE_POSITION, HOT_WIRE_POSITION, SWITCH_POSITION).forEach { position ->
                val cell = helper.absolutePart(position).cell()

                checkThat(manager.contains(cell.graph.id)) {
                    "The cell at $position is in a graph that the manager does not track"
                }

                checkThat(cell.graph.getCellByLocator(cell.locator) === cell) {
                    "The cell at $position is not the instance tracked by its graph"
                }

                checkThat(cell.container != null) {
                    "The cell at $position has no container after the restart"
                }
            }
        }
    }
}
