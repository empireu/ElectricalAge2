package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.content.DiodeCell
import org.eln2.mc.common.content.ElectrothermalWireCell
import org.eln2.mc.common.content.SwitchCell
import org.eln2.mc.common.content.VoltageSourceCell
import org.eln2.mc.common.content.modules.Eln2BasicComponents
import org.eln2.mc.common.content.modules.Eln2Wires
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.mathematics.FacingDirection
import kotlin.math.abs

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object ElectricalGameTests {
    private const val SOURCE_RESISTANCE = 1e-4
    private const val GROUND_RESISTANCE = 1e-5
    private const val WIRE_RESISTANCE = 2.14e-5

    private val WIRE get() = Eln2Wires.STANDARD_INSULATED_COPPER_ELECTRICAL_WIRE.part
    private val SOURCE get() = Eln2BasicComponents.VOLTAGE_SOURCE_PART
    private val GROUND get() = Eln2BasicComponents.GROUND_PART

    private fun Part.sourceCell() = cellAs<VoltageSourceCell>()

    private fun Part.setPotential(potential: Double) {
        graph().runSuspended {
            sourceCell().voltageSource.source.potential = potential
        }
    }

    private fun Part.current() = graph().runSuspended {
        abs(sourceCell().voltageSource.source.current)
    }

    /**
     * Places a source, a wire and a ground in a line going north.
     * */
    private fun GameTestHelper.placeShortCircuit(): Triple<Part, Part, Part> {
        val source = placePart(4, 6, SOURCE)
        val wire = placePart(4, 5, WIRE)
        val ground = placePart(4, 4, GROUND)

        return Triple(source, wire, ground)
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun sourceOnlyConnectsOnItsFront(helper: GameTestHelper) {
        val source = helper.placePart(4, 4, SOURCE)
        val north = helper.placePart(4, 3, WIRE)
        val south = helper.placePart(4, 5, WIRE)
        val east = helper.placePart(5, 4, WIRE)
        val west = helper.placePart(3, 4, WIRE)

        checkConnected(source, north)
        checkNotConnected(source, south)
        checkNotConnected(source, east)
        checkNotConnected(source, west)

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun rotatedSourceConnectsOnItsNewFront(helper: GameTestHelper) {
        val source = helper.placePart(4, 4, SOURCE, FacingDirection.EAST)
        val north = helper.placePart(4, 3, WIRE)
        val east = helper.placePart(5, 4, WIRE)

        checkConnected(source, east)
        checkNotConnected(source, north)

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun currentMatchesOhmsLawThroughWireToGround(helper: GameTestHelper) {
        val (source, wire, ground) = helper.placeShortCircuit()
        val potential = 0.01

        checkConnected(source, wire)
        checkConnected(wire, ground)

        source.setPotential(potential)

        helper.startSequence()
            .thenExecuteAfter(5) {
                val expected = potential / (SOURCE_RESISTANCE + WIRE_RESISTANCE + GROUND_RESISTANCE)

                checkClose(expected, source.current(), expected * 0.01, "Current")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun floatingWireCarriesNoCurrent(helper: GameTestHelper) {
        val source = helper.placePart(4, 6, SOURCE)
        helper.placePart(4, 5, WIRE)

        source.setPotential(10.0)

        helper.startSequence()
            .thenExecuteAfter(5) {
                checkClose(0.0, source.current(), 1e-6, "Current")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun wireHeatsAtTheDissipatedPower(helper: GameTestHelper) {
        val (source, wire, _) = helper.placeShortCircuit()
        val wireCell = wire.cellAs<ElectrothermalWireCell>()
        val potential = 0.1
        val current = potential / (SOURCE_RESISTANCE + WIRE_RESISTANCE + GROUND_RESISTANCE)
        val power = current * current * WIRE_RESISTANCE
        val measuredTicks = 100
        var initialEnergy = 0.0

        source.setPotential(potential)

        helper.startSequence()
            .thenExecuteAfter(5) {
                initialEnergy = wire.graph().runSuspended {
                    !wireCell.thermalWire.thermalBody.energy
                }
            }
            .thenExecuteAfter(measuredTicks) {
                val finalEnergy = wire.graph().runSuspended {
                    !wireCell.thermalWire.thermalBody.energy
                }

                val expected = power * measuredTicks * SECONDS_PER_TICK

                checkClose(expected, finalEnergy - initialEnergy, expected * 0.1, "Dissipated energy")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun overloadedWireBurnsOutAndBreaksTheCircuit(helper: GameTestHelper) {
        val (source, wire, ground) = helper.placeShortCircuit()
        val wirePosition = arenaPosition(4, 5)

        checkConnected(source, wire)
        checkConnected(wire, ground)

        helper.succeedWhen {
            checkThat(helper.partOrNullAt(wirePosition) == null) {
                "The wire has not burned out"
            }

            checkThat(source.cell().connections.isEmpty()) {
                "The source is still connected after the wire burned out"
            }

            checkClose(0.0, source.current(), 1e-3, "Current after burnout")
        }
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun switchOnlyConductsWhenClosed(helper: GameTestHelper) {
        val source = helper.placePart(4, 7, SOURCE)
        val firstWire = helper.placePart(4, 6, WIRE)
        val switch = helper.placePart(4, 5, Eln2BasicComponents.SWITCH_PART)
        val secondWire = helper.placePart(4, 4, WIRE)
        helper.placePart(4, 3, GROUND)

        val potential = 0.01
        val closedResistance = 1e-5
        val switchCell = switch.cellAs<SwitchCell>()

        checkConnected(firstWire, switch)
        checkConnected(switch, secondWire)

        source.setPotential(potential)

        helper.startSequence()
            .thenExecuteAfter(5) {
                checkClose(0.0, source.current(), 1e-3, "Current through the open switch")
                switchCell.requestSetClosed(true)
            }
            .thenExecuteAfter(5) {
                checkThat(switchCell.isClosed) {
                    "The switch did not close"
                }

                val expected = potential / (
                    SOURCE_RESISTANCE + 2.0 * WIRE_RESISTANCE + closedResistance + GROUND_RESISTANCE
                )

                checkClose(expected, source.current(), expected * 0.01, "Current through the closed switch")
                switchCell.requestSetClosed(false)
            }
            .thenExecuteAfter(5) {
                checkThat(!switchCell.isClosed) {
                    "The switch did not open"
                }

                checkClose(0.0, source.current(), 1e-3, "Current through the reopened switch")
            }
            .thenSucceed()
    }

    private fun GameTestHelper.placeDiodeCircuit(diodeFacing: FacingDirection): Pair<Part, DiodeCell> {
        val source = placePart(4, 7, SOURCE)
        val firstWire = placePart(4, 6, WIRE)
        val diode = placePart(4, 5, Eln2BasicComponents.DIODE_PART, diodeFacing)
        val secondWire = placePart(4, 4, WIRE)
        placePart(4, 3, GROUND)

        checkConnected(firstWire, diode)
        checkConnected(diode, secondWire)

        return Pair(source, diode.cellAs())
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun diodeConductsForward(helper: GameTestHelper) {
        val (source, diode) = helper.placeDiodeCircuit(FacingDirection.NORTH)
        val potential = 1.0
        val forwardResistance = 0.001

        source.setPotential(potential)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val expected = potential / (
                    SOURCE_RESISTANCE + 2.0 * WIRE_RESISTANCE + forwardResistance + GROUND_RESISTANCE
                )

                checkThat(diode.electrical.diode.isConducting) {
                    "The diode is not conducting in the forward direction"
                }

                checkClose(expected, source.current(), expected * 0.02, "Forward current")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun diodeBlocksReverse(helper: GameTestHelper) {
        val (source, diode) = helper.placeDiodeCircuit(FacingDirection.SOUTH)
        val potential = 1.0
        val reverseResistance = 10000.0

        source.setPotential(potential)

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkThat(!diode.electrical.diode.isConducting) {
                    "The diode is conducting in the reverse direction"
                }

                val expected = potential / reverseResistance

                checkClose(expected, source.current(), expected * 0.02, "Reverse current")
            }
            .thenSucceed()
    }
}
