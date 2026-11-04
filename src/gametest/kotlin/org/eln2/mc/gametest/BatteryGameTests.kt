package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.content.PolarBatteryCell
import org.eln2.mc.common.content.modules.Eln2Batteries
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.mathematics.FacingDirection
import kotlin.math.abs

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object BatteryGameTests {
    private const val INTERNAL_RESISTANCE = 0.023
    private const val CAPACITY_JOULES = 840.0 * 3600.0
    private const val SHORT_RESISTANCE = INTERNAL_RESISTANCE + ELECTRICAL_WIRE_RESISTANCE + 2.0 * GROUND_RESISTANCE

    private val BATTERY get() = Eln2Batteries.BATTERY_PART_12V

    private fun GameTestHelper.placeBulbBattery(facing: FacingDirection, x: Int): Pair<Part, LoadCircuit> {
        val load = placeBulbLoad(x, 4)
        val battery = placePart(x, 6, BATTERY, facing)
        placePart(x, 7, GROUND_ITEM)

        checkConnected(battery, partAt(x, 5))

        return Pair(battery, load)
    }

    private fun GameTestHelper.placeShortedBattery(x: Int): Part {
        placePart(x, 4, GROUND_ITEM)
        placePart(x, 5, ELECTRICAL_WIRE_ITEM)
        val battery = placePart(x, 6, BATTERY)
        placePart(x, 7, GROUND_ITEM)

        checkConnected(battery, partAt(x, 5))

        return battery
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun batteryStartsMostlyChargedWithTwelveVoltOpenCircuit(helper: GameTestHelper) {
        val battery = helper.placePart(4, 6, BATTERY)
        helper.placePart(4, 7, GROUND_ITEM)
        val cell = battery.cellAs<PolarBatteryCell>()

        helper.startSequence()
            .thenExecuteAfter(5) {
                battery.graph().runSuspended {
                    checkClose(0.9, cell.charge, 1e-3, "Initial charge fraction")

                    val potential = cell.generator.source.potential

                    checkThat(potential in 12.0..13.5) {
                        "Open circuit potential of a 90 percent charged 12 V battery was $potential"
                    }

                    checkClose(
                        !cell.model.voltageFunction.computeVoltage(cell),
                        potential,
                        0.05,
                        "Source potential against the voltage function"
                    )
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun batteryCurrentIsPotentialOverLoadPlusInternalResistance(helper: GameTestHelper) {
        val (battery, load) = helper.placeBulbBattery(FacingDirection.NORTH, 4)
        val cell = battery.cellAs<PolarBatteryCell>()

        helper.startSequence()
            .thenExecuteAfter(10) {
                battery.graph().runSuspended {
                    val potential = cell.generator.source.potential
                    val totalResistance = BULB_RESISTANCE + INTERNAL_RESISTANCE + 2.0 * GROUND_RESISTANCE +
                        ELECTRICAL_WIRE_RESISTANCE

                    val expectedCurrent = potential / totalResistance

                    checkClose(expectedCurrent, abs(load.loadCell.current), expectedCurrent * 0.01, "Load current")

                    checkClose(
                        expectedCurrent * BULB_RESISTANCE,
                        abs(load.loadCell.potential),
                        expectedCurrent * BULB_RESISTANCE * 0.01,
                        "Load potential"
                    )

                    checkClose(
                        expectedCurrent,
                        cell.generator.source.current,
                        expectedCurrent * 0.01,
                        "Source current leaving the battery"
                    )
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 300)
    @JvmStatic
    fun dischargeRemovesStoredEnergyAtTheEfficiencyOfTheModel(helper: GameTestHelper) {
        val battery = helper.placeShortedBattery(4)
        val cell = battery.cellAs<PolarBatteryCell>()
        val measuredTicks = 40
        var initialEnergy = 0.0
        var initialTransferred = 0.0

        helper.startSequence()
            .thenExecuteAfter(5) {
                battery.graph().runSuspended {
                    initialEnergy = !cell.energy
                    initialTransferred = !cell.totalEnergyTransferred
                }
            }
            .thenExecuteAfter(measuredTicks) {
                battery.graph().runSuspended {
                    val removedEnergy = initialEnergy - !cell.energy
                    val deliveredEnergy = !cell.totalEnergyTransferred - initialTransferred
                    val potential = cell.generator.source.potential
                    val current = potential / SHORT_RESISTANCE
                    val expectedDelivered = potential * current * measuredTicks * SECONDS_PER_TICK

                    checkClose(
                        expectedDelivered,
                        deliveredEnergy,
                        expectedDelivered * 0.03,
                        "Energy delivered by the source"
                    )

                    val efficiency = cell.model.efficiencyFunction.computeEfficiency(cell)

                    checkThat(efficiency in 0.3..1.0) {
                        "Efficiency out of range: $efficiency"
                    }

                    checkClose(
                        deliveredEnergy / efficiency,
                        removedEnergy,
                        removedEnergy * 0.05,
                        "Stored energy removed"
                    )

                    checkThat(removedEnergy < CAPACITY_JOULES * 0.01) {
                        "Unexpectedly large stored energy removal: $removedEnergy J"
                    }
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 300)
    @JvmStatic
    fun internalResistanceAndRejectedEnergyHeatTheBattery(helper: GameTestHelper) {
        val battery = helper.placeShortedBattery(4)
        val cell = battery.cellAs<PolarBatteryCell>()
        val measuredTicks = 40
        var initialThermalEnergy = 0.0
        var initialTransferred = 0.0

        helper.startSequence()
            .thenExecuteAfter(5) {
                battery.graph().runSuspended {
                    initialThermalEnergy = !cell.thermalWire.thermalBody.energy
                    initialTransferred = !cell.totalEnergyTransferred
                }
            }
            .thenExecuteAfter(measuredTicks) {
                battery.graph().runSuspended {
                    val potential = cell.generator.source.potential
                    val current = potential / SHORT_RESISTANCE
                    val resistiveHeat = current * current * INTERNAL_RESISTANCE * measuredTicks * SECONDS_PER_TICK
                    val deliveredEnergy = !cell.totalEnergyTransferred - initialTransferred
                    val efficiency = cell.model.efficiencyFunction.computeEfficiency(cell)
                    val rejectedHeat = deliveredEnergy * (1.0 / efficiency - 1.0)
                    val gain = !cell.thermalWire.thermalBody.energy - initialThermalEnergy

                    checkThat(gain > resistiveHeat * 0.9) {
                        "Battery gained $gain J, less than the internal resistance heat $resistiveHeat J"
                    }

                    val expected = resistiveHeat + rejectedHeat

                    checkClose(expected, gain, expected * 0.1, "Thermal energy gain")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 300)
    @JvmStatic
    fun voltageSourceAboveBatteryPotentialChargesTheBattery(helper: GameTestHelper) {
        val battery = helper.placePart(4, 7, BATTERY, FacingDirection.SOUTH)
        helper.placePart(4, 6, GROUND_ITEM)
        val wire = helper.placePart(4, 8, ELECTRICAL_WIRE_ITEM)
        val source = helper.placePart(4, 9, VOLTAGE_SOURCE_ITEM, FacingDirection.NORTH)
        val cell = battery.cellAs<PolarBatteryCell>()
        val sourcePotential = 14.0
        val measuredTicks = 100
        var initialEnergy = 0.0
        var initialTransferred = 0.0

        checkConnected(source, wire)
        checkConnected(wire, battery)

        source.setSourcePotential(sourcePotential)

        helper.startSequence()
            .thenExecuteAfter(5) {
                battery.graph().runSuspended {
                    initialEnergy = !cell.energy
                    initialTransferred = !cell.totalEnergyTransferred
                }
            }
            .thenExecuteAfter(measuredTicks) {
                battery.graph().runSuspended {
                    val batteryPotential = cell.generator.source.potential

                    val expectedCurrent = (sourcePotential - batteryPotential) / (
                        VOLTAGE_SOURCE_RESISTANCE + ELECTRICAL_WIRE_RESISTANCE + INTERNAL_RESISTANCE +
                            GROUND_RESISTANCE
                        )

                    checkClose(
                        expectedCurrent,
                        abs(cell.generator.source.current),
                        expectedCurrent * 0.03,
                        "Charging current"
                    )

                    checkThat(cell.generator.source.current < 0.0) {
                        "Charging current should enter the battery, got ${cell.generator.source.current}"
                    }

                    val gainedEnergy = !cell.energy - initialEnergy
                    val acceptedEnergy = !cell.totalEnergyTransferred - initialTransferred
                    val efficiency = cell.model.efficiencyFunction.computeEfficiency(cell)

                    checkThat(gainedEnergy > 0.0) {
                        "Stored energy did not increase while charging"
                    }

                    checkClose(
                        acceptedEnergy * efficiency,
                        gainedEnergy,
                        gainedEnergy * 0.05,
                        "Stored energy gained"
                    )
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun reversedBatteryDrivesTheOppositePotentialAcrossTheLoad(helper: GameTestHelper) {
        val (forwardBattery, forwardLoad) = helper.placeBulbBattery(FacingDirection.NORTH, 4)
        val (reversedBattery, reversedLoad) = helper.placeBulbBattery(FacingDirection.SOUTH, 9)

        helper.startSequence()
            .thenExecuteAfter(10) {
                forwardBattery.graph().runSuspended {
                    val forwardPotential = forwardLoad.loadCell.potential
                    val reversedPotential = reversedLoad.loadCell.potential

                    checkThat(abs(forwardPotential) > 1.0) {
                        "The load should see most of the battery potential, got $forwardPotential"
                    }

                    checkThat(forwardPotential * reversedPotential < 0.0) {
                        "Load potentials should have opposite signs, got $forwardPotential and $reversedPotential"
                    }

                    checkClose(
                        abs(forwardPotential),
                        abs(reversedPotential),
                        abs(forwardPotential) * 0.01,
                        "Load potential magnitude"
                    )

                    checkThat(forwardBattery.cellAs<PolarBatteryCell>().generator.source.current > 0.0) {
                        "A discharging battery must have positive source current"
                    }

                    checkThat(reversedBattery.cellAs<PolarBatteryCell>().generator.source.current > 0.0) {
                        "A discharging reversed battery must have positive source current"
                    }
                }
            }
            .thenSucceed()
    }
}
