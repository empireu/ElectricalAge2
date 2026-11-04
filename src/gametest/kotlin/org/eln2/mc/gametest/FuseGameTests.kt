package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.content.BurntFuseItem
import org.eln2.mc.common.content.FuseItem
import org.eln2.mc.common.content.FusePanelCell
import org.eln2.mc.common.content.VoltageSourceCell
import org.eln2.mc.common.content.modules.Eln2BasicComponents
import org.eln2.mc.common.parts.foundation.Part
import kotlin.math.abs

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object FuseGameTests {
    private val FUSE_PANEL get() = Eln2BasicComponents.FUSE_PANEL_PART

    private class FuseCircuit(val source: Part, val panel: Part) {
        val panelCell: FusePanelCell get() = panel.cellAs()

        fun sourceCurrent() = source.graph().runSuspended {
            abs(source.cellAs<VoltageSourceCell>().voltageSource.source.current)
        }

        fun insert(item: FuseItem) {
            panel.graph().runSuspended {
                panelCell.insertItem(item)
            }
        }

        fun functionalState() = panel.graph().runSuspended {
            panelCell.fuseState as? FusePanelCell.FunctionalFuseState
        }
    }

    private fun GameTestHelper.placeFuseCircuit(): FuseCircuit {
        val source = placePart(4, 9, VOLTAGE_SOURCE_ITEM)
        val firstWire = placePart(4, 8, ELECTRICAL_WIRE_ITEM)
        val panel = placePart(4, 7, FUSE_PANEL)
        val secondWire = placePart(4, 6, ELECTRICAL_WIRE_ITEM)
        placePart(4, 5, GROUND_ITEM)

        checkConnected(firstWire, panel)
        checkConnected(panel, secondWire)

        return FuseCircuit(source, panel)
    }

    private fun seriesResistance(fuseResistance: Double) =
        VOLTAGE_SOURCE_RESISTANCE + 2.0 * ELECTRICAL_WIRE_RESISTANCE + fuseResistance + GROUND_RESISTANCE

    @GameTest(template = ARENA)
    @JvmStatic
    fun emptyFusePanelDoesNotConduct(helper: GameTestHelper) {
        val circuit = helper.placeFuseCircuit()

        circuit.source.setSourcePotential(1.0)

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkClose(0.0, circuit.sourceCurrent(), 1e-3, "Current through an empty panel")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fittedFuseConductsThroughItsResistance(helper: GameTestHelper) {
        val circuit = helper.placeFuseCircuit()
        val potential = 0.12
        val fuseResistance = 0.06

        circuit.insert(Eln2BasicComponents.TIN_FUSE_5A.get())
        circuit.source.setSourcePotential(potential)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val expected = potential / seriesResistance(fuseResistance)

                checkClose(expected, circuit.sourceCurrent(), expected * 0.01, "Current through the fuse")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun fuseHeatsAtTheResistivePower(helper: GameTestHelper) {
        val circuit = helper.placeFuseCircuit()
        val potential = 0.25
        val fuseResistance = 0.5
        val measuredTicks = 20
        var initialEnergy = 0.0

        circuit.insert(Eln2BasicComponents.TIN_FUSE_1A.get())
        circuit.source.setSourcePotential(potential)

        helper.startSequence()
            .thenExecuteAfter(5) {
                initialEnergy = !(circuit.functionalState() ?: gameTestFail("No fuse fitted")).thermalMass.energy
            }
            .thenExecuteAfter(measuredTicks) {
                val state = circuit.functionalState() ?: gameTestFail("The fuse is not functional")
                val current = potential / seriesResistance(fuseResistance)
                val expected = current * current * fuseResistance * measuredTicks * SECONDS_PER_TICK

                checkClose(expected, !state.thermalMass.energy - initialEnergy, expected * 0.15, "Fuse heating")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun overloadedFuseBlowsAndInterruptsTheCircuit(helper: GameTestHelper) {
        val circuit = helper.placeFuseCircuit()

        circuit.insert(Eln2BasicComponents.TIN_FUSE_1A.get())
        circuit.source.setSourcePotential(5.0)

        helper.succeedWhen {
            val state = circuit.panel.graph().runSuspended {
                circuit.panelCell.fuseState
            }

            checkThat(state === FusePanelCell.BurntFuseState) {
                "The overloaded fuse has not burnt, state is $state"
            }

            checkClose(0.0, circuit.sourceCurrent(), 1e-3, "Current after the fuse blew")
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 250)
    @JvmStatic
    fun fuseBelowItsRatingStaysIntact(helper: GameTestHelper) {
        val circuit = helper.placeFuseCircuit()
        val potential = 0.24
        val fuseResistance = 0.06

        circuit.insert(Eln2BasicComponents.TIN_FUSE_5A.get())
        circuit.source.setSourcePotential(potential)

        helper.startSequence()
            .thenExecuteAfter(150) {
                val state = circuit.functionalState()
                    ?: gameTestFail("The fuse blew at 4 A, below its 5 A rating")

                val melting = !state.fuseItem.model.meltingPoint

                checkThat(!state.thermalMass.temperature < melting) {
                    "Fuse temperature ${!state.thermalMass.temperature} reached the melting point $melting"
                }

                val expected = potential / seriesResistance(fuseResistance)

                checkClose(expected, circuit.sourceCurrent(), expected * 0.01, "Current through the intact fuse")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 150)
    @JvmStatic
    fun replacingABlownFuseRestoresConduction(helper: GameTestHelper) {
        val circuit = helper.placeFuseCircuit()
        val potential = 0.12
        val fuseResistance = 0.06

        circuit.insert(Eln2BasicComponents.TIN_FUSE_1A.get())
        circuit.source.setSourcePotential(5.0)

        helper.startSequence()
            .thenWaitUntil {
                val state = circuit.panel.graph().runSuspended {
                    circuit.panelCell.fuseState
                }

                checkThat(state === FusePanelCell.BurntFuseState) {
                    "The overloaded fuse has not burnt, state is $state"
                }
            }
            .thenExecute {
                val removed = circuit.panel.graph().runSuspended {
                    circuit.panelCell.removeItem()
                }

                checkThat(removed is BurntFuseItem) {
                    "Removing a blown fuse should return a burnt fuse, got $removed"
                }

                circuit.source.setSourcePotential(potential)
                circuit.insert(Eln2BasicComponents.TIN_FUSE_5A.get())
            }
            .thenExecuteAfter(10) {
                val expected = potential / seriesResistance(fuseResistance)

                checkClose(expected, circuit.sourceCurrent(), expected * 0.01, "Current through the new fuse")
            }
            .thenSucceed()
    }
}
