package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.content.GridInterfaceCell
import org.eln2.mc.common.content.GridInterfacePart
import org.eln2.mc.common.content.VoltageSourceCell
import org.eln2.mc.common.content.modules.Eln2Grid
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
object GridGameTests {
    private const val TAP_RESISTANCE = 0.05e-3
    private const val ANCHOR_RESISTANCE = 0.0156e-3

    private val MICRO_GRID_INTERFACE get() = Eln2Grid.MICRO_GRID_INTERFACE_PART
    private val MICRO_GRID_CABLE get() = Eln2Grid.MICRO_GRID_CONNECT_COPPER.item.get()

    private class GridCircuit(val source: Part, val first: Part, val second: Part) {
        fun current() = source.graph().runSuspended {
            abs(source.cellAs<VoltageSourceCell>().voltageSource.source.current)
        }
    }

    private fun GameTestHelper.placeInterfaceCircuit(): GridCircuit {
        val source = placePart(2, 6, VOLTAGE_SOURCE_ITEM, FacingDirection.EAST)
        val firstWire = placePart(3, 6, ELECTRICAL_WIRE_ITEM)
        val first = placePart(4, 6, MICRO_GRID_INTERFACE)
        val second = placePart(10, 6, MICRO_GRID_INTERFACE)
        val secondWire = placePart(11, 6, ELECTRICAL_WIRE_ITEM)
        placePart(12, 6, GROUND_ITEM)

        checkConnected(firstWire, first)
        checkConnected(second, secondWire)

        source.setSourcePotential(0.01)

        return GridCircuit(source, first, second)
    }

    private fun GameTestHelper.connectWithCable(first: Part, second: Part): GridConnection {
        first as GridInterfacePart
        second as GridInterfacePart

        val firstTerminal = first.terminal.get() as CellTerminal
        val secondTerminal = second.terminal.get() as CellTerminal
        val firstHandle = GridTerminalHandle.PartHandle(firstTerminal, first)
        val secondHandle = GridTerminalHandle.PartHandle(secondTerminal, second)

        val pair = GridEndpointPair.create(firstTerminal.gridEndpointInfo, secondTerminal.gridEndpointInfo)
        val connection = GridConnection.create(pair, MICRO_GRID_CABLE.material)
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

    private fun Cell.connectionCells() = connections.filterIsInstance<GridConnectionCell>()

    @GameTest(template = ARENA)
    @JvmStatic
    fun interfacesWithoutACableAreElectricallyIsolated(helper: GameTestHelper) {
        val circuit = helper.placeInterfaceCircuit()

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkClose(0.0, circuit.current(), 1e-3, "Current without a cable")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun cableBetweenInterfacesRegistersAConnectionCellOnBothEnds(helper: GameTestHelper) {
        val circuit = helper.placeInterfaceCircuit()

        helper.connectWithCable(circuit.first, circuit.second)

        val firstCells = circuit.first.cellAs<GridInterfaceCell>().connectionCells()
        val secondCells = circuit.second.cellAs<GridInterfaceCell>().connectionCells()

        checkThat(firstCells.size == 1 && secondCells.size == 1) {
            "Expected one connection cell on each end, got ${firstCells.size} and ${secondCells.size}"
        }

        checkThat(firstCells[0] === secondCells[0]) {
            "The two ends should share the same connection cell"
        }

        checkThat(circuit.first.graph() === circuit.second.graph()) {
            "The interfaces should be in the same graph after connecting"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun cableResistanceFollowsTheMaterialResistivityAndGeometry(helper: GameTestHelper) {
        val circuit = helper.placeInterfaceCircuit()
        val connection = helper.connectWithCable(circuit.first, circuit.second)
        val connectionCell = circuit.first.cellAs<GridInterfaceCell>().connectionCells().single()

        val expected = !connection.material.physicalMaterial.electricalResistivity *
            (connection.cable.arcLength / connection.cable.crossSectionArea)

        checkThat(connection.cable.arcLength > 5.9) {
            "Cable between interfaces six blocks apart is only ${connection.cable.arcLength} long"
        }

        circuit.first.graph().runSuspended {
            val actual = connectionCell.electrical.resistor.resistance

            checkClose(expected, actual, expected * 1e-6, "Cable resistance")
        }

        helper.succeed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun currentThroughACableMatchesOhmsLawOverTheWholeLoop(helper: GameTestHelper) {
        val circuit = helper.placeInterfaceCircuit()
        val connection = helper.connectWithCable(circuit.first, circuit.second)
        val potential = 0.01

        helper.startSequence()
            .thenExecuteAfter(15) {
                val resistance = VOLTAGE_SOURCE_RESISTANCE + 2.0 * ELECTRICAL_WIRE_RESISTANCE +
                    2.0 * TAP_RESISTANCE + 2.0 * ANCHOR_RESISTANCE + connection.resistance + GROUND_RESISTANCE

                val expected = potential / resistance

                checkClose(expected, circuit.current(), expected * 0.02, "Current through the cable")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun breakingAnInterfaceRemovesTheCableAndStopsTheCurrent(helper: GameTestHelper) {
        val circuit = helper.placeInterfaceCircuit()
        helper.connectWithCable(circuit.first, circuit.second)
        val firstCell = circuit.first.cellAs<GridInterfaceCell>()

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkThat(circuit.current() > 1.0) {
                    "The cable should carry current before the interface is broken"
                }

                helper.breakPartAt(10, 6)
            }
            .thenExecuteAfter(10) {
                checkThat(firstCell.connectionCells().isEmpty()) {
                    "The cable connection cell should be removed with the interface"
                }

                checkClose(0.0, circuit.current(), 1e-3, "Current after breaking the interface")
            }
            .thenSucceed()
    }
}
