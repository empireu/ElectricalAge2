package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.Quantity
import org.eln2.mc.MODID
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.cells.foundation.CellGraph
import org.eln2.mc.common.cells.foundation.CellGraphLoadError
import org.eln2.mc.common.cells.foundation.CellGraphManager
import org.eln2.mc.common.content.DiodeCell
import org.eln2.mc.common.content.SwitchCell
import org.eln2.mc.common.content.ThermalWireCell
import org.eln2.mc.common.content.VoltageSourceCell
import org.eln2.mc.common.content.modules.Eln2BasicComponents
import org.eln2.mc.common.content.modules.Eln2Wires

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object PersistenceGameTests {
    private val WIRE get() = Eln2Wires.STANDARD_INSULATED_COPPER_ELECTRICAL_WIRE.part

    private fun assertLoadRejected(helper: GameTestHelper, tag: CompoundTag) {
        try {
            CellGraphManager.load(tag, helper.level)
        } catch (error: CellGraphLoadError) {
            return
        }

        gameTestFail("Loading was not rejected: $tag")
    }

    private fun CellGraph.saved() = runSuspended {
        toNbt()
    }

    private fun CellGraph.cellAtLocatorOf(cell: Cell) = getCellByLocator(cell.locator)

    @GameTest(template = ARENA)
    @JvmStatic
    fun loadingRejectsADifferentSaveVersion(helper: GameTestHelper) {
        val tag = CompoundTag()

        tag.putInt(CellGraphManager.NBT_SAVE_VERSION, CellGraphManager.SAVE_VERSION + 1)

        assertLoadRejected(helper, tag)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun loadingRejectsAnOlderSaveVersion(helper: GameTestHelper) {
        val tag = CompoundTag()

        tag.putInt(CellGraphManager.NBT_SAVE_VERSION, CellGraphManager.SAVE_VERSION - 1)

        assertLoadRejected(helper, tag)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun loadingRejectsASaveWithoutAVersion(helper: GameTestHelper) {
        assertLoadRejected(helper, CompoundTag())
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun loadingAcceptsAnEmptySaveOfTheCurrentVersion(helper: GameTestHelper) {
        val saved = CellGraphManager(helper.level).save(CompoundTag())
        val loaded = CellGraphManager.load(saved, helper.level)
        var graphCount = 0

        loaded.forEachGraph {
            graphCount++
        }

        checkThat(graphCount == 0) {
            "Expected no graphs, got $graphCount"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun corruptSaveIsRejectedInsteadOfBeingDiscarded(helper: GameTestHelper) {
        val wire = helper.placePart(5, 5, WIRE)
        val graphTag = wire.graph().saved()

        graphTag.getList("cells", 10).getCompound(0).putInt("type", 12345)

        val manager = CellGraphManager(helper.level).save(CompoundTag())

        manager.getList("graphs", 10).add(graphTag)

        assertLoadRejected(helper, manager)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun savedGraphKeepsItsIdentityCellsAndConnections(helper: GameTestHelper) {
        val source = helper.placePart(5, 8, Eln2BasicComponents.VOLTAGE_SOURCE_PART)
        val firstWire = helper.placePart(5, 7, WIRE)
        val secondWire = helper.placePart(5, 6, WIRE)
        val ground = helper.placePart(5, 5, Eln2BasicComponents.GROUND_PART)
        val branch = helper.placePart(6, 7, WIRE)
        val graph = source.graph()

        val restored = CellGraph.fromNbt(graph.saved(), CellGraphManager(helper.level), helper.level)

        checkThat(restored.id == graph.id) {
            "The graph id changed from ${graph.id} to ${restored.id}"
        }

        checkThat(restored.size == graph.size) {
            "Expected ${graph.size} cells, got ${restored.size}"
        }

        listOf(source, firstWire, secondWire, ground, branch).forEach { part ->
            val original = part.cell()
            val copy = restored.cellAtLocatorOf(original)

            checkThat(copy !== original) {
                "The restored graph reuses the original cell ${original.locator}"
            }

            checkThat(copy.id == original.id) {
                "Cell type changed from ${original.id} to ${copy.id}"
            }

            checkThat(copy.graph === restored) {
                "Restored cell ${original.locator} does not point to the restored graph"
            }

            val originalConnections = original.connections.map { it.locator }.toSet()
            val copyConnections = copy.connections.map { it.locator }.toSet()

            checkThat(originalConnections == copyConnections) {
                "Connections of ${original.locator} changed from $originalConnections to $copyConnections"
            }

            checkThat(copy.connections.all { it.graph === restored }) {
                "A restored connection of ${original.locator} points outside the restored graph"
            }

            checkThat(copy.connections.size == original.connections.size) {
                "Connection count of ${original.locator} changed"
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun savedCellStateIsRestored(helper: GameTestHelper) {
        val hotWire = helper.placePart(7, 7, Eln2Wires.STANDARD_INSULATED_COPPER_THERMAL_WIRE.part)
        val switch = helper.placePart(5, 5, Eln2BasicComponents.SWITCH_PART)
        val switchCell = switch.cellAs<SwitchCell>()
        val hotWireCell = hotWire.cellAs<ThermalWireCell>()

        hotWire.graph().runSuspended {
            hotWireCell.thermalWire.thermalBody.temperature = Quantity(500.0, KELVIN)
        }

        switchCell.requestSetClosed(true)

        helper.startSequence()
            .thenExecuteAfter(5) {
                checkThat(switchCell.isClosed) {
                    "The switch did not close"
                }

                val expectedTemperature = hotWire.graph().runSuspended {
                    !hotWireCell.thermalWire.thermalBody.temperature
                }

                val restoredWire = CellGraph
                    .fromNbt(hotWire.graph().saved(), CellGraphManager(helper.level), helper.level)
                    .cellAtLocatorOf(hotWireCell) as ThermalWireCell

                checkClose(expectedTemperature, !restoredWire.thermalWire.thermalBody.temperature, 1e-6, "Restored temperature")

                val restoredSwitch = CellGraph
                    .fromNbt(switch.graph().saved(), CellGraphManager(helper.level), helper.level)
                    .cellAtLocatorOf(switchCell) as SwitchCell

                checkThat(restoredSwitch.isClosed) {
                    "The restored switch is not closed"
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fullManagerRoundTripBuildsSolvers(helper: GameTestHelper) {
        val source = helper.placePart(5, 7, Eln2BasicComponents.VOLTAGE_SOURCE_PART)
        val wire = helper.placePart(5, 6, WIRE)
        helper.placePart(5, 5, Eln2BasicComponents.GROUND_PART)

        val graph = source.graph()
        val isolatedManager = CellGraphManager(helper.level)
        val managerTag = isolatedManager.save(CompoundTag())

        managerTag.getList("graphs", 10).add(graph.saved())

        val loaded = CellGraphManager.load(managerTag, helper.level)

        checkThat(loaded.contains(graph.id)) {
            "The loaded manager does not contain the saved graph"
        }

        val restored = loaded.getGraph(graph.id)

        checkThat(restored.electricalSubSolverSets.isNotEmpty()) {
            "Loading did not build the electrical solver"
        }

        checkThat(restored.thermalSims.isNotEmpty()) {
            "Loading did not build the thermal simulation"
        }

        checkThat(restored.cellAtLocatorOf(wire.cell()).connections.size == 2) {
            "The restored wire lost its connections"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun savedSourcePotentialIsRestored(helper: GameTestHelper) {
        val source = helper.placePart(5, 5, Eln2BasicComponents.VOLTAGE_SOURCE_PART)
        val sourceCell = source.cellAs<VoltageSourceCell>()

        source.graph().runSuspended {
            sourceCell.voltageSource.source.potential = 120.0
        }

        val restored = CellGraph
            .fromNbt(source.graph().saved(), CellGraphManager(helper.level), helper.level)
            .cellAtLocatorOf(sourceCell) as VoltageSourceCell

        checkClose(120.0, restored.voltageSource.source.potential, 1e-9, "Restored potential")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun savedDiodeStateIsRestored(helper: GameTestHelper) {
        val source = helper.placePart(5, 7, Eln2BasicComponents.VOLTAGE_SOURCE_PART)
        helper.placePart(5, 6, WIRE)
        val diode = helper.placePart(5, 5, Eln2BasicComponents.DIODE_PART)
        helper.placePart(5, 4, WIRE)
        helper.placePart(5, 3, Eln2BasicComponents.GROUND_PART)
        val diodeCell = diode.cellAs<DiodeCell>()

        source.graph().runSuspended {
            source.cellAs<VoltageSourceCell>().voltageSource.source.potential = 1.0
        }

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkThat(diodeCell.electrical.diode.isConducting) {
                    "The diode should be conducting before saving"
                }

                val restored = CellGraph
                    .fromNbt(diode.graph().saved(), CellGraphManager(helper.level), helper.level)
                    .cellAtLocatorOf(diodeCell) as DiodeCell

                checkThat(restored.electrical.diode.isConducting) {
                    "The restored diode lost its conducting state"
                }
            }
            .thenSucceed()
    }
}
