package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.content.modules.Eln2Wires

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object TopologyGameTests {
    private val WIRE get() = Eln2Wires.STANDARD_INSULATED_COPPER_ELECTRICAL_WIRE.part

    @GameTest(template = ARENA)
    @JvmStatic
    fun adjacentWiresShareOneGraph(helper: GameTestHelper) {
        val first = helper.placePart(2, 2, WIRE)
        val second = helper.placePart(3, 2, WIRE)

        checkThat(first.graph() == second.graph()) {
            "Adjacent wires were placed in different graphs"
        }

        checkThat(first.graph().size == 2) {
            "Expected 2 cells in the graph, got ${first.graph().size}"
        }

        checkThat(first.cell().connections.contains(second.cell())) {
            "First wire is not connected to the second"
        }

        checkThat(second.cell().connections.contains(first.cell())) {
            "Second wire is not connected to the first"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun separatedWiresHaveSeparateGraphs(helper: GameTestHelper) {
        val first = helper.placePart(2, 2, WIRE)
        val second = helper.placePart(4, 2, WIRE)

        checkThat(first.graph() != second.graph()) {
            "Separated wires share a graph"
        }

        checkThat(first.cell().connections.isEmpty() && second.cell().connections.isEmpty()) {
            "Separated wires have connections"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun placingBridgeMergesGraphs(helper: GameTestHelper) {
        val first = helper.placePart(2, 2, WIRE)
        val second = helper.placePart(4, 2, WIRE)
        val firstGraph = first.graph()
        val secondGraph = second.graph()

        val bridge = helper.placePart(3, 2, WIRE)

        checkThat(first.graph() == second.graph() && second.graph() == bridge.graph()) {
            "The bridge did not merge the graphs"
        }

        checkThat(first.graph().size == 3) {
            "Expected 3 cells in the merged graph, got ${first.graph().size}"
        }

        checkThat(first.graph() != firstGraph && first.graph() != secondGraph) {
            "Expected the merged graph to be a new graph"
        }

        val manager = first.graph().manager

        checkThat(!manager.contains(firstGraph.id) && !manager.contains(secondGraph.id)) {
            "The old graphs are still tracked by the manager"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun breakingCutVertexSplitsGraph(helper: GameTestHelper) {
        val first = helper.placePart(2, 2, WIRE)
        helper.placePart(3, 2, WIRE)
        val third = helper.placePart(4, 2, WIRE)
        val originalGraph = first.graph()

        helper.breakPartAt(3, 2)

        checkThat(first.graph() != third.graph()) {
            "Breaking the cut vertex did not split the graph"
        }

        checkThat(first.graph().size == 1 && third.graph().size == 1) {
            "Expected two graphs with one cell, got ${first.graph().size} and ${third.graph().size}"
        }

        checkThat(!originalGraph.manager.contains(originalGraph.id)) {
            "The old graph is still tracked by the manager"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun breakingLeafKeepsGraph(helper: GameTestHelper) {
        val first = helper.placePart(2, 2, WIRE)
        helper.placePart(3, 2, WIRE)
        val originalGraph = first.graph()

        helper.breakPartAt(3, 2)

        checkThat(first.graph() == originalGraph) {
            "Breaking a leaf replaced the graph"
        }

        checkThat(first.graph().size == 1) {
            "Expected 1 cell after breaking the leaf, got ${first.graph().size}"
        }

        checkThat(first.cell().connections.isEmpty()) {
            "The remaining wire still has connections"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun breakingLastCellRemovesGraph(helper: GameTestHelper) {
        val wire = helper.placePart(2, 2, WIRE)
        val graph = wire.graph()

        helper.breakPartAt(2, 2)

        checkThat(!graph.manager.contains(graph.id)) {
            "The empty graph is still tracked by the manager"
        }

        helper.succeed()
    }
}
