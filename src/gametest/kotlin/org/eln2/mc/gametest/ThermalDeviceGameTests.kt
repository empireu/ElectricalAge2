package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.Quantity
import org.eln2.mc.MODID
import org.eln2.mc.common.content.ElectricalHeatEngineCell
import org.eln2.mc.common.content.PolarLightCell
import org.eln2.mc.common.content.ThermalWireCell
import org.eln2.mc.common.content.modules.Eln2Lights
import org.eln2.mc.common.content.modules.Eln2Thermal
import org.eln2.mc.common.content.modules.Eln2Wires
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.mathematics.FacingDirection
import kotlin.math.abs

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object ThermalDeviceGameTests {
    private const val RADIATOR_CONDUCTANCE = 25.0

    private class EngineCircuit(val engine: Part, val load: Part) {
        val cell: ElectricalHeatEngineCell get() = engine.cellAs()
        val loadCell: PolarLightCell get() = load.cellAs()
    }

    private fun GameTestHelper.placeEngineCircuit(): EngineCircuit {
        placePart(2, 6, GROUND_ITEM)
        val load = placePart(3, 6, Eln2Lights.LIGHT_PART, FacingDirection.NORTH)
        val firstWire = placePart(4, 6, ELECTRICAL_WIRE_ITEM)
        placePart(5, 6, ELECTRICAL_WIRE_ITEM)
        val engine = placePart(6, 6, Eln2Thermal.ELECTRICAL_HEAT_ENGINE_PART, FacingDirection.NORTH)
        placePart(7, 6, GROUND_ITEM)

        checkConnected(firstWire, load)
        checkConnected(partAt(5, 6), engine)

        load.installBulb()

        return EngineCircuit(engine, load)
    }

    private fun EngineCircuit.heatHotSide(excess: Double) {
        engine.graph().runSuspended {
            val ambient = !cell.environmentData.ambientTemperature

            cell.hot.temperature = Quantity(ambient + excess, KELVIN)
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 250)
    @JvmStatic
    fun heatEngineTurnsHotSideHeatIntoElectricPowerAndWarmsTheColdSide(helper: GameTestHelper) {
        val circuit = helper.placeEngineCircuit()
        var initialHotEnergy = 0.0
        var initialColdEnergy = 0.0

        circuit.engine.graph().runSuspended {
            initialColdEnergy = !circuit.cell.cold.energy
        }

        circuit.heatHotSide(300.0)

        helper.startSequence()
            .thenExecuteAfter(2) {
                initialHotEnergy = circuit.engine.graph().runSuspended {
                    !circuit.cell.hot.energy
                }

                initialColdEnergy = circuit.engine.graph().runSuspended {
                    !circuit.cell.cold.energy
                }
            }
            .thenExecuteAfter(150) {
                circuit.engine.graph().runSuspended {
                    val loadPower = abs(circuit.loadCell.power)

                    checkThat(loadPower > 1.0) {
                        "The heat engine delivered only $loadPower W with a 300 K temperature difference"
                    }

                    val hotLoss = initialHotEnergy - !circuit.cell.hot.energy
                    val coldGain = !circuit.cell.cold.energy - initialColdEnergy

                    checkThat(hotLoss > 0.0) {
                        "The hot side did not lose energy, change was $hotLoss J"
                    }

                    checkThat(coldGain > 0.0) {
                        "The cold side did not gain energy, change was $coldGain J"
                    }
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun heatEngineWithoutATemperatureDifferenceProducesNoPower(helper: GameTestHelper) {
        val circuit = helper.placeEngineCircuit()

        helper.startSequence()
            .thenExecuteAfter(40) {
                circuit.engine.graph().runSuspended {
                    checkClose(0.0, abs(circuit.loadCell.power), 0.01, "Load power without a temperature difference")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 250)
    @JvmStatic
    fun radiatorLeaksHeatAtItsConfiguredConductance(helper: GameTestHelper) {
        val radiator = helper.placePart(5, 5, Eln2Wires.THERMAL_RADIATOR_PART)
        val cell = radiator.cellAs<ThermalWireCell>()
        val ambient = !cell.environmentData.ambientTemperature
        val excess = 20.0
        val measuredTicks = 100
        var initialEnergy = 0.0

        radiator.graph().runSuspended {
            cell.thermalWire.thermalBody.temperature = Quantity(ambient + excess, KELVIN)
        }

        helper.startSequence()
            .thenExecute {
                initialEnergy = radiator.graph().runSuspended {
                    !cell.thermalWire.thermalBody.energy
                }
            }
            .thenExecuteAfter(measuredTicks) {
                val finalEnergy = radiator.graph().runSuspended {
                    !cell.thermalWire.thermalBody.energy
                }

                val expected = RADIATOR_CONDUCTANCE * excess * measuredTicks * SECONDS_PER_TICK

                checkClose(expected, initialEnergy - finalEnergy, expected * 0.1, "Energy leaked by the radiator")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun radiatorSharesHeatWithAnAdjacentRadiator(helper: GameTestHelper) {
        val hot = helper.placePart(5, 5, Eln2Wires.THERMAL_RADIATOR_PART)
        val cold = helper.placePart(6, 5, Eln2Wires.THERMAL_RADIATOR_PART)
        val hotCell = hot.cellAs<ThermalWireCell>()
        val coldCell = cold.cellAs<ThermalWireCell>()
        val ambient = !hotCell.environmentData.ambientTemperature

        hot.graph().runSuspended {
            hotCell.thermalWire.thermalBody.temperature = Quantity(ambient + 100.0, KELVIN)
        }

        helper.startSequence()
            .thenExecuteAfter(40) {
                hot.graph().runSuspended {
                    val coldTemperature = !coldCell.thermalWire.thermalBody.temperature

                    checkThat(coldTemperature > ambient + 0.01) {
                        "The adjacent radiator did not warm up, temperature $coldTemperature K"
                    }

                    checkThat(!hotCell.thermalWire.thermalBody.temperature > coldTemperature) {
                        "Heat should flow from the hot radiator to the cold one"
                    }
                }
            }
            .thenSucceed()
    }
}
