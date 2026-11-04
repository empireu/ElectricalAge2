package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.content.PoweredLightPart
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.mathematics.FacingDirection
import kotlin.math.abs

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object LightGameTests {
    private const val BULB_POWER_RATING = 100.0
    private const val BULB_DAMAGE_RATE = 1e-6

    private class PoweredBulb(val source: Part, val load: LoadCircuit)

    private fun GameTestHelper.placePoweredBulb(withBulb: Boolean = true): PoweredBulb {
        val load = placeBulbLoad(4, 4, withBulb)
        placePart(4, 6, ELECTRICAL_WIRE_ITEM)
        val source = placePart(4, 7, VOLTAGE_SOURCE_ITEM, FacingDirection.NORTH)

        checkConnected(partAt(4, 6), source)

        return PoweredBulb(source, load)
    }

    private fun Part.lightVolumeState() = (this as PoweredLightPart<*>).instance().stateIncrement

    @GameTest(template = ARENA)
    @JvmStatic
    fun lampWithoutABulbDoesNotConduct(helper: GameTestHelper) {
        val powered = helper.placePoweredBulb(withBulb = false)

        powered.source.setSourcePotential(240.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                powered.source.graph().runSuspended {
                    checkClose(0.0, powered.load.loadCell.current, 1e-5, "Current through an empty lamp")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun bulbDrawsItsRatedPowerAtItsRatedPotential(helper: GameTestHelper) {
        val powered = helper.placePoweredBulb()

        powered.source.setSourcePotential(240.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                powered.source.graph().runSuspended {
                    val cell = powered.load.loadCell
                    val totalResistance = BULB_RESISTANCE + VOLTAGE_SOURCE_RESISTANCE +
                        2.0 * ELECTRICAL_WIRE_RESISTANCE + GROUND_RESISTANCE
                    val expectedCurrent = 240.0 / totalResistance

                    checkClose(expectedCurrent, abs(cell.current), expectedCurrent * 0.01, "Bulb current")
                    checkClose(BULB_POWER_RATING, abs(cell.power), BULB_POWER_RATING * 0.01, "Bulb power")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun bulbPowerScalesWithTheSquareOfThePotential(helper: GameTestHelper) {
        val powered = helper.placePoweredBulb()

        powered.source.setSourcePotential(120.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                powered.source.graph().runSuspended {
                    val expected = 120.0 * 120.0 / BULB_RESISTANCE

                    checkClose(expected, abs(powered.load.loadCell.power), expected * 0.01, "Bulb power at half potential")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun bulbLifeDropsInProportionToItsPower(helper: GameTestHelper) {
        val powered = helper.placePoweredBulb()
        val measuredTicks = 100
        var initialLife = 0.0

        powered.source.setSourcePotential(240.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                initialLife = powered.source.graph().runSuspended {
                    powered.load.loadCell.life
                }
            }
            .thenExecuteAfter(measuredTicks) {
                val finalLife = powered.source.graph().runSuspended {
                    powered.load.loadCell.life
                }

                val expected = measuredTicks * SECONDS_PER_TICK * BULB_DAMAGE_RATE

                checkClose(expected, initialLife - finalLife, expected * 0.1, "Bulb life consumed")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun poweredBulbEmitsLightIntoTheWorld(helper: GameTestHelper) {
        val powered = helper.placePoweredBulb()

        powered.source.setSourcePotential(240.0)

        helper.succeedWhen {
            val state = helper.partAt(4, 4).lightVolumeState()

            checkThat(state > 0) {
                "A bulb at its rated power left the light volume at state $state"
            }
        }
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun unpoweredBulbEmitsNoLight(helper: GameTestHelper) {
        val powered = helper.placePoweredBulb()

        powered.source.setSourcePotential(0.0)

        helper.startSequence()
            .thenExecuteAfter(20) {
                val state = helper.partAt(4, 4).lightVolumeState()

                checkThat(state == 0) {
                    "An unpowered bulb left the light volume at state $state"
                }
            }
            .thenSucceed()
    }
}
