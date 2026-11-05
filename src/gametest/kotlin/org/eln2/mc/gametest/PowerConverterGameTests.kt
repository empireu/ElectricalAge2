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
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.VOLT
import org.eln2.mc.MODID
import org.eln2.mc.common.content.GridInterfacePart
import org.eln2.mc.common.content.PrimitivePowerConverterBlockEntity
import org.eln2.mc.common.content.TerminalDcToDcConverterCell
import org.eln2.mc.common.content.modules.Eln2Grid
import org.eln2.mc.common.content.modules.Eln2PowerDevices
import org.eln2.mc.common.grids.CellTerminal
import org.eln2.mc.common.grids.GridConnection
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridConnectionDescription
import org.eln2.mc.common.grids.GridEndpointPair
import org.eln2.mc.common.grids.GridTerminalHandle
import org.eln2.mc.common.grids.RemoteTerminalConnection
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.mathematics.FacingDirection
import kotlin.math.abs

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object PowerConverterGameTests {
    private const val POWER_RATING = 5000.0
    private const val EFFICIENCY = 0.7
    private const val MIN_POTENTIAL = 5.0
    private const val INPUT_POTENTIAL = 240.0
    private const val INPUT_NEGATIVE_TERMINAL = 0
    private const val INPUT_POSITIVE_TERMINAL = 1
    private const val OUTPUT_NEGATIVE_TERMINAL = 2
    private const val OUTPUT_POSITIVE_TERMINAL = 3

    private val POWER_INTERFACE get() = Eln2Grid.POWER_GRID_INTERFACE_PART
    private val POWER_CABLE get() = Eln2Grid.POWER_GRID_CONNECT_COPPER.item.get()

    private class ConverterCircuit(
        val blockEntity: PrimitivePowerConverterBlockEntity,
        val cell: TerminalDcToDcConverterCell,
        val source: Part
    ) {
        fun <T> read(reader: TerminalDcToDcConverterCell.() -> T): T = cell.graph.runSuspended {
            cell.reader()
        }
    }

    private fun GameTestHelper.placeConverter(position: BlockPos): PrimitivePowerConverterBlockEntity {
        val absolute = absolutePos(position)
        val floor = absolute.below()
        val player = FakePlayerFactory.getMinecraft(level)
        val item = Eln2PowerDevices.PRIMITIVE_DC_TO_DC_CONVERTER_BLOCK.item.get()

        setBlock(position.below(), Blocks.STONE)
        player.moveTo(absolute.x + 0.5, absolute.y + 4.0, absolute.z + 0.5, 0.0f, 90.0f)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(item))

        val hit = BlockHitResult(Vec3.atCenterOf(floor).add(0.0, 0.5, 0.0), Direction.UP, floor, false)

        item.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))

        return getBlockEntity(position) as? PrimitivePowerConverterBlockEntity
            ?: gameTestFail("Could not place a converter at $position")
    }

    private fun GameTestHelper.connect(
        firstTerminal: CellTerminal,
        firstHandle: GridTerminalHandle,
        secondTerminal: CellTerminal,
        secondHandle: GridTerminalHandle
    ): GridConnection {
        val pair = GridEndpointPair.create(firstTerminal.gridEndpointInfo, secondTerminal.gridEndpointInfo)
        val connection = GridConnection.create(pair, POWER_CABLE.material)
        val description = GridConnectionDescription(connection.material, connection.resistance)

        firstTerminal.beginConnectStaging(RemoteTerminalConnection(secondHandle, description))
        secondTerminal.beginConnectStaging(RemoteTerminalConnection(firstHandle, description))
        GridConnectionCell.beginStaging(firstTerminal, secondTerminal, level, connection)
        firstTerminal.addConnection()
        secondTerminal.addConnection()
        GridConnectionCell.endStaging(firstTerminal, secondTerminal)
        firstTerminal.endConnectStaging()
        secondTerminal.endConnectStaging()

        return connection
    }

    private fun GameTestHelper.connectTerminal(
        blockEntity: PrimitivePowerConverterBlockEntity,
        terminalIndex: Int,
        part: Part
    ) {
        val interfacePart = part as GridInterfacePart
        val converterTerminal = blockEntity.gridTerminalSystem.instances[terminalIndex] as? CellTerminal
            ?: gameTestFail("Converter terminal $terminalIndex is missing")
        val partTerminal = interfacePart.terminal.get() as CellTerminal

        connect(
            converterTerminal,
            GridTerminalHandle.BlockEntityHandle(converterTerminal, blockEntity),
            partTerminal,
            GridTerminalHandle.PartHandle(partTerminal, interfacePart)
        )
    }

    private fun GameTestHelper.placeConverterWithInput(): ConverterCircuit {
        val blockEntity = placeConverter(arenaPosition(6, 6))

        val source = placePart(1, 4, VOLTAGE_SOURCE_ITEM, FacingDirection.EAST)
        val inputWire = placePart(2, 4, ELECTRICAL_WIRE_ITEM)
        val inputPositive = placePart(3, 4, POWER_INTERFACE)

        placePart(1, 8, GROUND_ITEM)
        val returnWire = placePart(2, 8, ELECTRICAL_WIRE_ITEM)
        val inputNegative = placePart(3, 8, POWER_INTERFACE)

        checkConnected(inputWire, inputPositive)
        checkConnected(returnWire, inputNegative)

        connectTerminal(blockEntity, INPUT_POSITIVE_TERMINAL, inputPositive)
        connectTerminal(blockEntity, INPUT_NEGATIVE_TERMINAL, inputNegative)

        source.setSourcePotential(INPUT_POTENTIAL)

        return ConverterCircuit(blockEntity, blockEntity.cell, source)
    }

    private fun GameTestHelper.groundOutputNegative(blockEntity: PrimitivePowerConverterBlockEntity) {
        val outputNegative = placePart(9, 8, POWER_INTERFACE)
        val wire = placePart(10, 8, ELECTRICAL_WIRE_ITEM)

        placePart(11, 8, GROUND_ITEM)

        checkConnected(outputNegative, wire)
        connectTerminal(blockEntity, OUTPUT_NEGATIVE_TERMINAL, outputNegative)
    }

    private fun GameTestHelper.placeConverterWithBulb(setpoint: Double): Pair<ConverterCircuit, LoadCircuit> {
        val circuit = placeConverterWithInput()

        groundOutputNegative(circuit.blockEntity)

        val load = placeBulbLoad(11, 4)
        val outputPositive = placePart(10, 5, POWER_INTERFACE)

        checkConnected(outputPositive, partAt(11, 5))
        connectTerminal(circuit.blockEntity, OUTPUT_POSITIVE_TERMINAL, outputPositive)

        circuit.read {
            converter.setpointPotential = Quantity(setpoint, VOLT)
        }

        return Pair(circuit, load)
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun outputSettlesAtTheSetpointUnderALightLoad(helper: GameTestHelper) {
        val (circuit, load) = helper.placeConverterWithBulb(240.0)

        helper.startSequence()
            .thenExecuteAfter(60) {
                circuit.read {
                    checkClose(240.0, converter.outputSource.potential, 2.4, "Output potential")
                }

                load.load.graph().runSuspended {
                    checkClose(240.0, abs(load.loadCell.current) * BULB_RESISTANCE, 5.0, "Potential across the load")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun inputPowerIsOutputPowerOverTheEfficiency(helper: GameTestHelper) {
        val (circuit, _) = helper.placeConverterWithBulb(240.0)

        helper.startSequence()
            .thenExecuteAfter(60) {
                circuit.read {
                    val output = converter.outputSource.power
                    val input = converter.inputConsumer.power

                    checkClose(100.0, output, 5.0, "Output power of a 100 W bulb at its rated potential")
                    checkClose(output / EFFICIENCY, input, output * 0.05, "Input power")
                }
            }
            .thenSucceed()
    }

    private fun GameTestHelper.placeShortedConverter(): ConverterCircuit {
        val circuit = placeConverterWithInput()

        groundOutputNegative(circuit.blockEntity)

        val outputPositive = placePart(9, 4, POWER_INTERFACE)
        val wire = placePart(10, 4, ELECTRICAL_WIRE_ITEM)

        placePart(11, 4, GROUND_ITEM)
        checkConnected(outputPositive, wire)
        connectTerminal(circuit.blockEntity, OUTPUT_POSITIVE_TERMINAL, outputPositive)

        circuit.read {
            converter.setpointPotential = Quantity(240.0, VOLT)
        }

        return circuit
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun energyThatDoesNotReachTheOutputBecomesHeat(helper: GameTestHelper) {
        val circuit = helper.placeShortedConverter()

        var initialHeat = 0.0
        var integrated = 0.0

        fun sample() = circuit.read {
            converter.inputConsumer.power - converter.outputSource.power +
                converter.inputSeriesResistor.power + converter.outputSeriesResistor.power
        }

        val sequence = helper.startSequence()
            .thenExecuteAfter(10) {
                circuit.read {
                    initialHeat = !thermalWire.thermalBody.energy
                }
            }

        repeat(10) {
            sequence.thenExecuteAfter(1) {
                integrated += sample() * SECONDS_PER_TICK
            }
        }

        sequence
            .thenExecuteAfter(1) {
                circuit.read {
                    val gained = !thermalWire.thermalBody.energy - initialHeat

                    checkThat(integrated > 500.0) {
                        "A shorted converter should waste kilowatts, it wasted $integrated J"
                    }

                    checkClose(integrated, gained, integrated * 0.1, "Waste heat")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun outputPowerStaysWithinTheRatingIntoAShortCircuit(helper: GameTestHelper) {
        val circuit = helper.placeShortedConverter()

        helper.startSequence()
            .thenExecuteAfter(10) {
                circuit.read {
                    val power = converter.outputSource.power

                    checkThat(power <= POWER_RATING * 1.02) {
                        "Output power $power exceeds the rating of $POWER_RATING"
                    }

                    checkThat(power > POWER_RATING * 0.5) {
                        "A shorted output should draw near the rated power, got $power"
                    }

                    checkThat(converter.outputSource.potential < 240.0 * 0.5) {
                        "A shorted output cannot hold the setpoint, it reads ${converter.outputSource.potential} V"
                    }
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun withoutInputTheBufferRunsOutAndTheOutputStops(helper: GameTestHelper) {
        val (circuit, _) = helper.placeConverterWithBulb(240.0)

        helper.startSequence()
            .thenExecuteAfter(40) {
                circuit.read {
                    checkThat(converter.energyBuffer > 0.0) {
                        "The buffer should hold energy while powered"
                    }
                }

                circuit.source.setSourcePotential(0.0)
            }
            .thenExecuteAfter(60) {
                circuit.read {
                    checkClose(0.0, converter.outputSource.power, 1.0, "Output power without input")
                    checkClose(0.0, converter.energyBuffer, 1.0, "Buffer without input")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun setpointBelowTheMinimumIsHeldAtTheMinimum(helper: GameTestHelper) {
        val (circuit, _) = helper.placeConverterWithBulb(0.0)

        helper.startSequence()
            .thenExecuteAfter(40) {
                circuit.read {
                    checkClose(MIN_POTENTIAL, converter.outputSource.maxPotential, 1e-9, "Output limit")
                    checkClose(MIN_POTENTIAL, converter.outputSource.potential, 0.1, "Output potential")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun converterKeepsItsBufferAndSetpointWhenSaved(helper: GameTestHelper) {
        val (circuit, _) = helper.placeConverterWithBulb(120.0)

        helper.startSequence()
            .thenExecuteAfter(40) {
                circuit.read {
                    val buffer = converter.energyBuffer
                    val tag = converter.saveObjectNbt()

                    converter.setpointPotential = Quantity(50.0, VOLT)
                    converter.loadObjectNbt(tag)

                    checkClose(120.0, !converter.setpointPotential, 1e-9, "Setpoint after loading")
                    checkClose(buffer, converter.energyBuffer, 1e-9, "Buffer after loading")
                }
            }
            .thenSucceed()
    }
}
