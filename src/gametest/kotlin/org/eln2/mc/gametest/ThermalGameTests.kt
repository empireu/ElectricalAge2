package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.sim.EnvironmentConnection
import org.ageseries.libage.sim.MassConnection
import org.eln2.mc.MODID
import org.eln2.mc.common.content.ThermalWireCell
import org.eln2.mc.common.content.modules.Eln2Wires
import org.eln2.mc.common.parts.foundation.Part

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object ThermalGameTests {
    private const val LEAKAGE_CONDUCTANCE = 0.05

    private val THERMAL_WIRE get() = Eln2Wires.STANDARD_UNINSULATED_COPPER_THERMAL_WIRE.part

    private fun Part.thermalBody() = cellAs<ThermalWireCell>().thermalWire.thermalBody

    private fun Part.temperature() = graph().runSuspended {
        !thermalBody().temperature
    }

    private fun Part.energy() = graph().runSuspended {
        !thermalBody().energy
    }

    private fun Part.setTemperature(temperature: Double) {
        graph().runSuspended {
            thermalBody().temperature = Quantity(temperature, KELVIN)
        }
    }

    private fun Part.simulation() = cellAs<ThermalWireCell>().thermalWire.simulation
        ?: gameTestFail("The thermal object has no simulation")

    @GameTest(template = ARENA)
    @JvmStatic
    fun placedWireStartsAtAmbientTemperature(helper: GameTestHelper) {
        val wire = helper.placePart(5, 5, THERMAL_WIRE)
        val ambient = !wire.cell().environmentData.ambientTemperature

        checkThat(ambient in 250.0..320.0) {
            "Ambient temperature of the test biome is not plausible: $ambient K"
        }

        checkClose(ambient, wire.temperature(), 1e-6, "Initial temperature")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun connectedWiresShareASimulation(helper: GameTestHelper) {
        val first = helper.placePart(5, 5, THERMAL_WIRE)
        val second = helper.placePart(6, 5, THERMAL_WIRE)
        val separate = helper.placePart(8, 5, THERMAL_WIRE)

        checkThat(first.simulation() === second.simulation()) {
            "Connected wires are in different thermal simulations"
        }

        checkThat(first.simulation() !== separate.simulation()) {
            "Disconnected wires share a thermal simulation"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun eachWireHasOneEnvironmentConnection(helper: GameTestHelper) {
        val first = helper.placePart(5, 5, THERMAL_WIRE)
        helper.placePart(6, 5, THERMAL_WIRE)

        val count = first.simulation().connections.count { it is EnvironmentConnection }

        checkThat(count == 2) {
            "Expected 2 environment connections for 2 wires, got $count"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun connectedWiresAreJoinedByOneMassConnection(helper: GameTestHelper) {
        val first = helper.placePart(5, 5, THERMAL_WIRE)
        helper.placePart(6, 5, THERMAL_WIRE)

        val count = first.simulation().connections.count { it is MassConnection }

        checkThat(count == 1) {
            "Expected 1 mass connection between 2 wires, got $count, which doubles the conductance"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun wiresInARingAreJoinedByOneMassConnectionEach(helper: GameTestHelper) {
        val first = helper.placePart(5, 5, THERMAL_WIRE)
        helper.placePart(6, 5, THERMAL_WIRE)
        helper.placePart(6, 6, THERMAL_WIRE)
        helper.placePart(5, 6, THERMAL_WIRE)

        val count = first.simulation().connections.count { it is MassConnection }

        checkThat(count == 4) {
            "Expected 4 mass connections in a ring of 4 wires, got $count"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun heatSpreadsBetweenConnectedWires(helper: GameTestHelper) {
        val first = helper.placePart(5, 5, THERMAL_WIRE)
        val second = helper.placePart(6, 5, THERMAL_WIRE)
        val ambient = !first.cell().environmentData.ambientTemperature

        first.setTemperature(ambient + 100.0)

        helper.startSequence()
            .thenExecuteAfter(20) {
                checkClose(first.temperature(), second.temperature(), 0.5, "Temperature difference")
                checkClose(ambient + 50.0, first.temperature(), 2.0, "Shared temperature")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun heatDoesNotReachDisconnectedWires(helper: GameTestHelper) {
        val hot = helper.placePart(5, 5, THERMAL_WIRE)
        val separate = helper.placePart(7, 5, THERMAL_WIRE)
        val ambient = !hot.cell().environmentData.ambientTemperature

        hot.setTemperature(ambient + 100.0)

        helper.startSequence()
            .thenExecuteAfter(20) {
                checkClose(ambient, separate.temperature(), 1e-6, "Disconnected temperature")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun hotWireLeaksAtItsConfiguredConductance(helper: GameTestHelper) {
        val wire = helper.placePart(5, 5, THERMAL_WIRE)
        val ambient = !wire.cell().environmentData.ambientTemperature
        val excess = 100.0
        val measuredTicks = 100
        var initialEnergy = 0.0

        wire.setTemperature(ambient + excess)

        helper.startSequence()
            .thenExecute {
                initialEnergy = wire.energy()
            }
            .thenExecuteAfter(measuredTicks) {
                val expected = LEAKAGE_CONDUCTANCE * excess * measuredTicks * SECONDS_PER_TICK

                checkClose(expected, initialEnergy - wire.energy(), expected * 0.1, "Leaked energy")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun breakingAWireKeepsTheTemperatureOfTheOthers(helper: GameTestHelper) {
        val first = helper.placePart(5, 5, THERMAL_WIRE)
        helper.placePart(6, 5, THERMAL_WIRE)
        val ambient = !first.cell().environmentData.ambientTemperature

        first.setTemperature(ambient + 100.0)

        helper.startSequence()
            .thenExecuteAfter(20) {
                val before = first.temperature()

                helper.breakPartAt(6, 5)

                checkClose(before, first.temperature(), 1e-6, "Temperature across the rebuild")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun overheatedWireBurnsOut(helper: GameTestHelper) {
        val position = arenaPosition(5, 5)
        val wire = helper.placePart(position, THERMAL_WIRE)

        wire.setTemperature(5000.0)

        helper.succeedWhen {
            checkThat(helper.partOrNullAt(position) == null) {
                "The overheated wire has not burned out"
            }
        }
    }
}
