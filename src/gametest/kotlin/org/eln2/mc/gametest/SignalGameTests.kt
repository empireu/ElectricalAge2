package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.content.ElectricalProbeCell
import org.eln2.mc.common.content.RelayCell
import org.eln2.mc.common.content.SignalClamperCell
import org.eln2.mc.common.content.SignalOpAmpCell
import org.eln2.mc.common.content.SignalPidCell
import org.eln2.mc.common.content.SignalReferenceCell
import org.eln2.mc.common.content.VoltageSourceCell
import org.eln2.mc.common.content.modules.Eln2Signal
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.mathematics.FacingDirection
import kotlin.math.abs

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object SignalGameTests {
    private const val CLOSED_RELAY_RESISTANCE = 1e-5
    private const val PROBE_CLOSED_RESISTANCE = 1e-5

    private fun Part.setReference(value: Double) {
        graph().runSuspended {
            cellAs<SignalReferenceCell>().value = value
        }
    }

    private fun GameTestHelper.placeReference(x: Int, z: Int, value: Double): Part {
        val reference = placePart(x, z, Eln2Signal.SIGNAL_REFERENCE_PART)
        reference.setReference(value)

        return reference
    }

    private class OpAmpSetup(val opAmp: Part, val cell: SignalOpAmpCell)

    private fun GameTestHelper.placeOpAmp(valueA: Double, valueB: Double): OpAmpSetup {
        val opAmp = placePart(6, 6, Eln2Signal.SIGNAL_OPAMP_PART)
        val referenceA = placeReference(5, 6, valueA)
        val referenceB = placeReference(7, 6, valueB)

        checkConnected(referenceA, opAmp)
        checkConnected(referenceB, opAmp)

        return OpAmpSetup(opAmp, opAmp.cellAs())
    }

    private fun OpAmpSetup.configure(gain: Double, subtractMode: Boolean) {
        opAmp.graph().runSuspended {
            cell.gain = gain
            cell.subtractMode = subtractMode
        }
    }

    private fun OpAmpSetup.output() = opAmp.graph().runSuspended {
        cell.opamp.signalSource.signal
    }

    private fun OpAmpSetup.inputs() = opAmp.graph().runSuspended {
        Pair(cell.opamp.inputAResistor.potential, cell.opamp.inputBResistor.potential)
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun referenceOutputIsClampedToTheSignalRange(helper: GameTestHelper) {
        val reference = helper.placeReference(4, 4, 500.0)
        val cell = reference.cellAs<SignalReferenceCell>()

        helper.startSequence()
            .thenExecuteAfter(5) {
                checkClose(100.0, cell.value, 1e-9, "Stored reference value")

                val signal = reference.graph().runSuspended {
                    cell.reference.signalSource.signal
                }

                checkClose(100.0, signal, 1e-9, "Reference output signal")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun opAmpInputsSeeTheReferenceThroughTheSeriesResistance(helper: GameTestHelper) {
        val setup = helper.placeOpAmp(3.0, 2.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val (inputA, inputB) = setup.inputs()

                checkClose(signalAtInput(3.0), inputA, 0.01, "Potential at input A")
                checkClose(signalAtInput(2.0), inputB, 0.01, "Potential at input B")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun opAmpAddsItsInputsScaledByTheGain(helper: GameTestHelper) {
        val setup = helper.placeOpAmp(3.0, 2.0)
        val gain = 2.0

        setup.configure(gain, false)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val expected = gain * (signalAtInput(3.0) + signalAtInput(2.0))

                checkClose(expected, setup.output(), expected * 0.01, "Op amp sum")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun opAmpSubtractModeOutputsTheDifference(helper: GameTestHelper) {
        val setup = helper.placeOpAmp(7.0, 2.0)

        setup.configure(1.0, true)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val expected = signalAtInput(7.0) - signalAtInput(2.0)

                checkClose(expected, setup.output(), expected * 0.01, "Op amp difference")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun opAmpOutputSaturatesAtTheSignalLimit(helper: GameTestHelper) {
        val setup = helper.placeOpAmp(60.0, 60.0)

        setup.configure(1.0, false)

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkClose(100.0, setup.output(), 1e-9, "Saturated op amp output")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun opAmpGainIsLimitedToTen(helper: GameTestHelper) {
        val setup = helper.placeOpAmp(1.0, 0.0)

        setup.configure(50.0, false)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val expected = 10.0 * signalAtInput(1.0)

                checkClose(expected, setup.output(), expected * 0.01, "Output with out of range gain")
            }
            .thenSucceed()
    }

    private fun GameTestHelper.placeClamper(value: Double): Pair<Part, SignalClamperCell> {
        val clamper = placePart(4, 5, Eln2Signal.SIGNAL_CLAMPER_PART, FacingDirection.SOUTH)
        val reference = placeReference(4, 6, value)

        checkConnected(reference, clamper)

        return Pair(clamper, clamper.cellAs())
    }

    private fun Pair<Part, SignalClamperCell>.setLimits(minimum: Double, maximum: Double) {
        first.graph().runSuspended {
            second.clampMin = minimum
            second.clampMax = maximum
        }
    }

    private fun Pair<Part, SignalClamperCell>.output() = first.graph().runSuspended {
        second.clamper.signalSource.signal
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun clamperLimitsTheSignalFromAbove(helper: GameTestHelper) {
        val clamper = helper.placeClamper(80.0)

        clamper.setLimits(-20.0, 30.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkClose(30.0, clamper.output(), 1e-9, "Output above the upper limit")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun clamperLimitsTheSignalFromBelow(helper: GameTestHelper) {
        val clamper = helper.placeClamper(-80.0)

        clamper.setLimits(-20.0, 30.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkClose(-20.0, clamper.output(), 1e-9, "Output below the lower limit")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun clamperPassesSignalsInsideTheRange(helper: GameTestHelper) {
        val clamper = helper.placeClamper(10.0)

        clamper.setLimits(-20.0, 30.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val expected = signalAtInput(10.0)

                checkClose(expected, clamper.output(), expected * 0.01, "Output inside the range")
            }
            .thenSucceed()
    }

    private class PidSetup(val pid: Part, val cell: SignalPidCell)

    private fun GameTestHelper.placePid(inputValue: Double, referenceValue: Double?): PidSetup {
        val pid = placePart(6, 6, Eln2Signal.SIGNAL_PID_PART)
        val input = placeReference(5, 6, inputValue)

        checkConnected(input, pid)

        if (referenceValue != null) {
            val reference = placeReference(7, 6, referenceValue)
            checkConnected(reference, pid)
        }

        return PidSetup(pid, pid.cellAs())
    }

    private fun PidSetup.configure(proportional: Double, integral: Double) {
        pid.graph().runSuspended {
            cell.kP = proportional
            cell.kI = integral
        }
    }

    private fun PidSetup.output() = pid.graph().runSuspended {
        cell.pid.signalSource.signal
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun pidWithoutReferenceOutputsProportionalToTheInput(helper: GameTestHelper) {
        val setup = helper.placePid(4.0, null)

        setup.configure(2.0, 0.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val expected = 2.0 * signalAtInput(4.0)

                checkClose(expected, setup.output(), expected * 0.01, "Proportional output")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun pidWithReferenceOutputsProportionalToTheError(helper: GameTestHelper) {
        val setup = helper.placePid(4.0, 10.0)

        setup.configure(3.0, 0.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val expected = 3.0 * (signalAtInput(10.0) - signalAtInput(4.0))

                checkClose(expected, setup.output(), expected * 0.01, "Proportional output")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun pidIntegralGrowsAtTheIntegralGainTimesTheError(helper: GameTestHelper) {
        val setup = helper.placePid(4.0, 10.0)
        val integralGain = 1.5
        val measuredTicks = 20
        var initialOutput = 0.0

        setup.configure(0.0, integralGain)

        helper.startSequence()
            .thenExecuteAfter(10) {
                initialOutput = setup.output()
            }
            .thenExecuteAfter(measuredTicks) {
                val error = signalAtInput(10.0) - signalAtInput(4.0)
                val expected = integralGain * error * measuredTicks * SECONDS_PER_TICK

                checkClose(expected, setup.output() - initialOutput, expected * 0.1, "Integral growth")
            }
            .thenSucceed()
    }

    private class ProbeSetup(val probe: Part, val source: Part, val cell: ElectricalProbeCell)

    private fun ProbeSetup.configureRange(inputHigh: Double, outputHigh: Double) {
        probe.graph().runSuspended {
            cell.signalMap.inputLow = 0.0
            cell.signalMap.inputHigh = inputHigh
            cell.signalMap.outputLow = 0.0
            cell.signalMap.outputHigh = outputHigh
        }
    }

    private fun ProbeSetup.signal() = probe.graph().runSuspended {
        cell.probe.signalSource.signal
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun potentialProbeOutputsTheMappedPotential(helper: GameTestHelper) {
        val source = helper.placePart(4, 4, VOLTAGE_SOURCE_ITEM, FacingDirection.SOUTH)
        helper.placePart(4, 5, ELECTRICAL_WIRE_ITEM)
        helper.placePart(4, 6, ELECTRICAL_WIRE_ITEM)
        val probe = helper.placePart(4, 7, Eln2Signal.POTENTIAL_PROBE_PART, FacingDirection.NORTH)
        helper.placePart(4, 8, GROUND_ITEM)
        val setup = ProbeSetup(probe, source, probe.cellAs())
        val potential = 5.0

        checkConnected(helper.partAt(4, 6), probe)

        setup.configureRange(10.0, 50.0)
        source.setSourcePotential(potential)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val expected = potential / 10.0 * 50.0

                checkClose(expected, setup.signal(), expected * 0.01, "Potential probe signal")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun currentProbeOutputsTheMappedCurrent(helper: GameTestHelper) {
        val source = helper.placePart(4, 4, VOLTAGE_SOURCE_ITEM, FacingDirection.SOUTH)
        helper.placePart(4, 5, ELECTRICAL_WIRE_ITEM)
        val probe = helper.placePart(4, 6, Eln2Signal.CURRENT_PROBE_PART, FacingDirection.NORTH)
        helper.placePart(4, 7, ELECTRICAL_WIRE_ITEM)
        helper.placePart(4, 8, GROUND_ITEM)
        val setup = ProbeSetup(probe, source, probe.cellAs())
        val potential = 0.01

        checkConnected(helper.partAt(4, 5), probe)
        checkConnected(probe, helper.partAt(4, 7))

        setup.configureRange(1000.0, 100.0)
        source.setSourcePotential(potential)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val resistance = VOLTAGE_SOURCE_RESISTANCE + 2.0 * ELECTRICAL_WIRE_RESISTANCE +
                    PROBE_CLOSED_RESISTANCE + GROUND_RESISTANCE

                val expected = potential / resistance / 1000.0 * 100.0

                checkClose(expected, abs(setup.signal()), expected * 0.02, "Current probe signal")
            }
            .thenSucceed()
    }

    private class RelaySetup(val relay: Part, val source: Part, val reference: Part) {
        val cell: RelayCell get() = relay.cellAs()
    }

    private fun GameTestHelper.placeRelayCircuit(): RelaySetup {
        val source = placePart(4, 10, VOLTAGE_SOURCE_ITEM)
        placePart(4, 9, ELECTRICAL_WIRE_ITEM)
        val relay = placePart(4, 8, Eln2Signal.RELAY_PART, FacingDirection.NORTH)
        placePart(4, 7, ELECTRICAL_WIRE_ITEM)
        placePart(4, 6, GROUND_ITEM)
        val reference = placeReference(5, 8, 0.0)

        checkConnected(partAt(4, 9), relay)
        checkConnected(reference, relay)

        source.setSourcePotential(0.01)

        return RelaySetup(relay, source, reference)
    }

    private fun RelaySetup.current() = source.graph().runSuspended {
        abs(source.cellAs<VoltageSourceCell>().voltageSource.source.current)
    }

    private fun RelaySetup.isClosed() = relay.graph().runSuspended {
        cell.isClosed
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun relayClosesAboveItsThresholdAndOpensBelowIt(helper: GameTestHelper) {
        val setup = helper.placeRelayCircuit()
        val closedCurrent = 0.01 / (VOLTAGE_SOURCE_RESISTANCE + 2.0 * ELECTRICAL_WIRE_RESISTANCE +
            CLOSED_RELAY_RESISTANCE + GROUND_RESISTANCE)

        helper.startSequence()
            .thenExecuteAfter(5) {
                checkThat(!setup.isClosed()) {
                    "The relay should start open"
                }

                checkClose(0.0, setup.current(), 1e-3, "Current through the open relay")
                setup.reference.setReference(40.0)
            }
            .thenExecuteAfter(15) {
                checkThat(!setup.isClosed()) {
                    "The relay closed below its threshold"
                }

                setup.reference.setReference(80.0)
            }
            .thenExecuteAfter(15) {
                checkThat(setup.isClosed()) {
                    "The relay did not close above its threshold"
                }

                checkClose(closedCurrent, setup.current(), closedCurrent * 0.02, "Current through the closed relay")
                setup.reference.setReference(10.0)
            }
            .thenExecuteAfter(30) {
                checkThat(!setup.isClosed()) {
                    "The relay did not open after the signal dropped"
                }

                checkClose(0.0, setup.current(), 1e-3, "Current through the reopened relay")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun invertedRelayIsClosedWithoutSignal(helper: GameTestHelper) {
        val setup = helper.placeRelayCircuit()

        setup.relay.graph().runSuspended {
            setup.cell.inverted = true
        }

        helper.startSequence()
            .thenExecuteAfter(15) {
                checkThat(setup.isClosed()) {
                    "The inverted relay should close when the signal is below the threshold"
                }

                setup.reference.setReference(80.0)
            }
            .thenExecuteAfter(30) {
                checkThat(!setup.isClosed()) {
                    "The inverted relay should open when the signal is above the threshold"
                }
            }
            .thenSucceed()
    }
}
