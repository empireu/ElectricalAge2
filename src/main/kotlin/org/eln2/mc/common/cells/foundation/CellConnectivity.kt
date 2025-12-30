package org.eln2.mc.common.cells.foundation

import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import org.ageseries.libage.data.requireLocator
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.LOG
import org.eln2.mc.Locators
import org.eln2.mc.extensions.plus
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashSet

/**
 * Checks if a connection between [a] and [b] is even possible, given the [CellLayer]s they are on.
 * */
fun isConnectionAcceptedByGameObjectLayer(a: Cell, b: Cell) : Boolean {
    val layerA = a.locator.get(Locators.CELL_LAYER)
    val layerB = b.locator.get(Locators.CELL_LAYER)

    val layersCompatible = if(layerA != null && layerB != null) {
        when(layerA) {
            CellLayer.Block -> layerB == CellLayer.Block || layerB == CellLayer.Part
            CellLayer.Part -> layerB == CellLayer.Block || layerB == CellLayer.Part
            CellLayer.Spec -> false
        }
    }
    else {
        true
    }

    return layersCompatible && a.allowsConnection(b) && b.allowsConnection(a)
}

/**
 * [CellConnections] has all Cell-Cell connection logic and is responsible for building *physical* networks.
 * There are two key algorithms here:
 * - Cell Insertion - Inserts a cell into the world, and may form connections with other cells. It could also join together previously disjoint graphs.
 * - Cell Deletion - Deletes a cell from the world, and may result in many topological changes to the associated graph. An example would be the removal (deletion) of a cut vertex. This would result in the graph splintering into multiple disjoint graphs.
 * */
object CellConnections {
    /**
     * Inserts a cell into a graph. It may create connections with other cells, and cause
     * topological changes to related networks.
     * */
    fun insertFresh(container: CellContainer, cell: Cell) {
        connectCell(cell, container)
        cell.onCreated()
    }

    /**
     * Removes a cell from the graph. It may cause topological changes to the graph, as outlined in the top document.
     * */
    fun destroy(cellInfo: Cell, container: CellContainer) {
        cellInfo.onBeginDestroy()
        disconnectCell(cellInfo, container)
        cellInfo.onDestroyed()
    }

    fun connectCell(insertedCell: Cell, container: CellContainer) {
        val manager = container.manager
        val neighborInfoList = container.neighborScan(insertedCell).also { neighbors ->
            val testSet = neighbors.mapTo(HashSet(neighbors.size)) { it.neighbor }

            if (testSet.size != neighbors.size) {
                LOG.fatal("UNEXPECTED MULTIPLE CELLS")
                DEBUGGER_BREAK()
            }
        }
        val neighborCells = neighborInfoList.map { it.neighbor }.toHashSet()

        // Stop all running simulations

        neighborCells
            .map { it.graph }
            .distinct()
            .forEach {
                it.executionGraph.suspend()
                it.captureAllInScope()
            }

        if (insertedCell.hasGraph) {
            insertedCell.graph.captureAllInScope()
        }

        /*
        * Cases:
        *   1. We don't have any neighbors. We must create a new circuit.
        *   2. We have a single neighbor. We can add this cell to their circuit.
        *   3. We have multiple neighbors, but they are part of the same circuit. We can add this cell to the common circuit.
        *   4. We have multiple neighbors, and they are part of different circuits. We need to create a new circuit,
        *       that contains the cells of the other circuits, plus this one.
        * */

        // This is common logic for all cases

        insertedCell.connections = ArrayList(neighborInfoList.map { it.neighbor })

        neighborInfoList.forEach { neighborInfo ->
            neighborInfo.neighbor.connections.add(insertedCell)
            neighborInfo.container.onCellConnected(
                neighborInfo.neighbor,
                insertedCell
            )

            container.onCellConnected(insertedCell, neighborInfo.neighbor)
        }

        if (neighborInfoList.isEmpty()) {
            // Case 1. Create new circuit

            val graph = manager.createGraph()

            graph.addCell(insertedCell)

            graph.setChanged()
        } else if (isCommonGraph(neighborInfoList)) {
            // Case 2 and 3. Join the existing circuit.

            val graph = neighborInfoList[0].neighbor.graph

            graph.addCell(insertedCell)

            graph.setChanged()

            // Send connection update to the neighbor (the graph has not changed):
            neighborInfoList.forEach {
                it.neighbor.onUpdate(
                    connectionsChanged = true,
                    graphChanged = false
                )
            }
        } else {
            // Case 4. We need to create a new circuit, with all cells and this one.

            // Identify separate graphs:
            val disjointGraphs = neighborInfoList.map { it.neighbor.graph }.distinct()

            // Create new graph that will eventually have all cells and the inserted one:
            val graph = manager.createGraph()

            // Register inserted cell:
            graph.addCell(insertedCell)

            // Copy cells over to the new circuit and destroy previous circuits:
            disjointGraphs.forEach { existingGraph ->
                existingGraph.copyTo(graph)

                /*
                * We also need to refit the existing cells.
                * Connections of the remote cells have changed only if the remote cell is a neighbor of the inserted cell.
                * This is because inserting a cell cannot remove connections, and new connections appear only between the new cell and cells from other circuits (the inserted cell is a cut vertex)
                * */
                existingGraph.forEach { cell ->
                    cell.graph = graph

                    cell.onUpdate(
                        connectionsChanged = neighborCells.contains(cell), // As per the above explanation
                        graphChanged = true // We are destroying the old graph and copying, so this is true
                    )

                    cell.container?.onTopologyChanged()
                }

                // And now destroy the old graph:
                existingGraph.destroy()
            }

            graph.setChanged()
        }

        insertedCell.graph.buildSolver()

        /*
        * The inserted cell had a "complete" update.
        * Because it was inserted into a new network, its neighbors have changed (connectionsChanged is true).
        * Then, because it is inserted into a new graph, graphChanged is also true:
        * */
        insertedCell.onUpdate(connectionsChanged = true, graphChanged = true)
        insertedCell.container?.onTopologyChanged()

        // And now resume/start the simulation:
        insertedCell.graph.executionGraph.resume()
    }

    fun disconnectCell(actualCell: Cell, actualContainer: CellContainer, notify: Boolean = true) {
        val manager = actualContainer.manager

        val connections = actualCell.connections.map {
            CellAndContainerHandle.captureInScope(it)
        }

        connections
            .map { it.neighbor.graph }
            .distinct()
            .forEach {
                it.captureAllInScope()
            }

        val graph = actualCell.graph

        // Stop Simulation
        graph.executionGraph.suspend()

        graph.captureAllInScope()

        if (notify) {
            actualCell.onDestroying()
        }

        val markedNeighbors = actualCell.connections.toHashSet()

        connections.forEach { (neighbor, neighborContainer) ->
            val containsA = actualCell.connections.contains(neighbor)
            val containsB = neighbor.connections.contains(actualCell)

            if (containsA && containsB) {
                actualCell.removeConnection(neighbor)
                neighbor.removeConnection(actualCell)

                neighborContainer.onCellDisconnected(neighbor, actualCell)
                actualContainer.onCellDisconnected(actualCell, neighbor)

                markedNeighbors.remove(neighbor)
            } else if (containsA != containsB) {
                error(DEBUGGER_BREAK("Mismatched connection vs query result"))
            }
        }

        if (markedNeighbors.isNotEmpty()) {
            error(DEBUGGER_BREAK("Lingering connections $actualCell $markedNeighbors"))
        }

        /*
        *   Cases:
        *   1. We don't have any neighbors. We can destroy the circuit.
        *   2. We have a single neighbor. We can remove ourselves from the circuit.
        *   3. We have multiple neighbors, and we are not a cut vertex. We can remove ourselves from the circuit.
        *   4. We have multiple neighbors, and we are a cut vertex. We need to remove ourselves, find the new disjoint graphs,
        *        and rebuild the circuits.
        */

        if (connections.isEmpty()) {
            // Case 1. Destroy this circuit.

            // Make sure we don't make any logic errors somewhere else.
            check(graph.size == 1) {
                DEBUGGER_BREAK("disconnectCell - case 1")
            }

            graph.destroy()
        } else if (connections.size == 1) {
            // Case 2.

            // Remove the cell from the circuit.
            graph.removeCell(actualCell)

            val neighbor = connections[0].neighbor

            neighbor.onUpdate(connectionsChanged = true, graphChanged = false)

            graph.buildSolver()
            graph.executionGraph.resume()
            graph.setChanged()
        } else {
            // Case 3 and 4. Implement a more sophisticated algorithm, if necessary.
            graph.destroy()
            rebuildTopologies(connections, actualCell, manager)
        }
    }

    inline fun retopologize(cell: Cell, container: CellContainer, action: () -> Unit) {
        disconnectCell(cell, container, false)
        action()
        connectCell(cell, container)
    }

    fun retopologize(cell: Cell, container: CellContainer) {
        disconnectCell(cell, container, false)
        connectCell(cell, container)
    }

    /**
     * Checks whether the cells share the same graph.
     * @return True, if the specified cells share the same graph. Otherwise, false.
     * */
    private fun isCommonGraph(neighbors: List<CellAndContainerHandle>): Boolean {
        if (neighbors.size < 2) {
            return true
        }

        val graph = neighbors[0].neighbor.graph

        neighbors.drop(1).forEach { info ->
            if (info.neighbor.graph != graph) {
                return false
            }
        }

        return true
    }

    /**
     * Rebuilds the topology of a graph, presumably after a cell has been removed.
     * This will handle cases such as the graph splitting, because a cut vertex was removed.
     * This is a performance intensive operation, because it is likely to perform a search through the cells.
     * There is a case, though, that will complete in constant time: removing a cell that has zero or one neighbors.
     * Keep in mind that the simulation logic likely won't complete in constant time, in any case.
     * */
    private fun rebuildTopologies(
        neighborInfoList: List<CellAndContainerHandle>,
        removedCell: Cell,
        manager: CellGraphManager,
    ) {
        /*
        * For now, we use this simple algorithm.:
        *   We enqueue all neighbors for visitation. We perform searches through their graphs,
        *   excluding the cell we are removing.
        *
        *   If at any point we encounter an unprocessed neighbor, we remove that neighbor from the neighbor
        *   queue.
        *
        *   After a queue element has been processed, we build a new circuit with the cells we found.
        * */

        val neighbors = neighborInfoList.map { it.neighbor }.toHashSet()
        val neighborQueue = ArrayDeque<Cell>()
        neighborQueue.addAll(neighbors)

        val bfsVisited = HashSet<Cell>()
        val bfsQueue = ArrayDeque<Cell>()

        while (neighborQueue.isNotEmpty()) {
            val neighbor = neighborQueue.removeFirst()

            // Create new circuit for all cells connected to this one.
            val graph = manager.createGraph()

            // Start BFS at the neighbor.
            bfsQueue.add(neighbor)

            while (bfsQueue.isNotEmpty()) {
                val cell = bfsQueue.removeFirst()

                if (!bfsVisited.add(cell)) {
                    continue
                }

                neighborQueue.remove(cell)

                graph.addCell(cell)

                // Enqueue neighbors (excluding the cell we are removing) for processing
                cell.connections.forEach { connCell ->
                    // This must be handled above.
                    check(connCell != removedCell) {
                        DEBUGGER_BREAK("rebuildTopologies - connCell != removedCell")
                    }

                    bfsQueue.add(connCell)
                }
            }

            check(bfsQueue.isEmpty()) {
                DEBUGGER_BREAK("rebuildTopologies - bfsQueue#isEmpty")
            }

            // Refit cells
            graph.forEach { cell ->
                val isNeighbor = neighbors.contains(cell)

                cell.onUpdate(connectionsChanged = isNeighbor, graphChanged = true)
                cell.container?.onTopologyChanged()
            }

            // Finally, build the solver and start simulation.

            graph.buildSolver()
            graph.executionGraph.resume()
            graph.setChanged()

            // We don't need to keep the cells, we have already traversed all the connected ones.
            bfsVisited.clear()
        }
    }
}

/**
 * Cell scan with the following conditions:
 * - Both cells must have a [Locators.BLOCK]
 * - Both cells must have a [Locators.SUBSTRATE_FACE]
 * - The substrate faces (normals) must be equal
 * - The distance must be one block
 *
 * [Reference Image - Connection Rejection](https://media.discordapp.net/attachments/945750566066343956/1437791655393493094/iUCmbmZSUj.png?ex=69148753&is=691335d3&hm=906cf6dce7ba343c6abf5dc807fad79feb38fd07f2446b59c1cf50db5b295975&=&format=webp&quality=lossless)
 * */
inline fun planarCellScan(level: Level, actualCell: Cell, searchDirection: Direction, consumer: ((CellAndContainerHandle) -> Unit)) {
    val actualPosWorld = actualCell.locator.requireLocator(Locators.BLOCK) {
        DEBUGGER_BREAK("Planar Scan requires a block position")
    }

    val actualFaceTarget = actualCell.locator.requireLocator(Locators.SUBSTRATE_FACE) {
        DEBUGGER_BREAK("Planar Scan requires a face")
    }

    val remoteContainer = level.getBlockEntity(actualPosWorld + searchDirection) as? CellContainer ?: return

    remoteContainer
        .getCells()
        .filter {
            it.locator.has(Locators.BLOCK) &&
            it.locator.has(Locators.SUBSTRATE_FACE)
        }
        .forEach { targetCell ->
            val targetFaceTarget = targetCell.locator.requireLocator(Locators.SUBSTRATE_FACE)

            if (targetFaceTarget == actualFaceTarget) {
                if (isConnectionAcceptedByGameObjectLayer(actualCell, targetCell)) {
                    consumer(CellAndContainerHandle.captureInScope(targetCell))
                }
            }
        }
}

/**
 * Cell scan with the following conditions:
 * - Both cells must have a [Locators.BLOCK]
 * - Both cells must have a [Locators.PIPELIKE_MASK]
 * - The pipelike masks must have corresponding opposite entries
 * - The distance must be one block
 * */
inline fun pipelikeCellScan(level: Level, actualCell: Cell, consumer: ((CellAndContainerHandle) -> Unit)) {
    val actualPosWorld = actualCell.locator.requireLocator(Locators.BLOCK) {
        DEBUGGER_BREAK("Shaftlike Scan requires a block position")
    }

    val actualMaskWorld = actualCell.locator.requireLocator(Locators.PIPELIKE_MASK) {
        DEBUGGER_BREAK("Shaftlike Scan requires a mask")
    }

    actualMaskWorld.forEach { directionWorld ->
        val remoteContainer = level.getBlockEntity(actualPosWorld + directionWorld) as? CellContainer
            ?: return@forEach

        remoteContainer
            .getCells()
            .filter {
                it.locator.has(Locators.BLOCK) &&
                    it.locator.has(Locators.PIPELIKE_MASK)
            }
            .forEach { targetCell ->
                val targetMaskWorld = targetCell.locator.requireLocator(Locators.PIPELIKE_MASK)

                if(targetMaskWorld.has(directionWorld.opposite)) {
                    if (isConnectionAcceptedByGameObjectLayer(actualCell, targetCell)) {
                        consumer(CellAndContainerHandle.captureInScope(targetCell))
                    }
                }
            }
    }
}

/*
* There is a little bug that makes connections possible around the corner of a block, even if there's a block adjacent diagonally.
* I kind of like this, do we want to fix it?
* */
const val ALLOW_WRAPPED_DIAGONAL_WHATEVER = false

/**
 * Cell scan with the following conditions:
 * - Both cells must have a [Locators.BLOCK]
 * - Both cells must have a [Locators.SUBSTRATE_FACE]
 * The scan is basically trying to find a way to "wrap" an imaginary cable around the corner of a block.
 * */
inline fun wrappedCellScan(
    level: Level,
    actualCell: Cell,
    searchDirection: Direction,
    consumer: ((CellAndContainerHandle) -> Unit),
) {
    val actualPosWorld = actualCell.locator.requireLocator(Locators.BLOCK) { "Wrapped Scan requires a block position" }
    val actualFaceWorld = actualCell.locator.requireLocator(Locators.SUBSTRATE_FACE) { "Wrapped Scan requires a face" }
    val wrapDirection = actualFaceWorld.opposite

    @Suppress("KotlinConstantConditions")
    if(!ALLOW_WRAPPED_DIAGONAL_WHATEVER) {
        if(!level.getBlockState(actualPosWorld + searchDirection).isAir) {
            return
        }
    }

    val remoteContainer = level.getBlockEntity(actualPosWorld + searchDirection + wrapDirection) as? CellContainer
        ?: return

    remoteContainer
        .getCells()
        .filter { it.locator.has(Locators.BLOCK) && it.locator.has(Locators.SUBSTRATE_FACE) }
        .forEach { targetCell ->
            val targetFaceTarget = targetCell.locator.requireLocator(Locators.SUBSTRATE_FACE)

            if (targetFaceTarget == searchDirection) {
                if (isConnectionAcceptedByGameObjectLayer(actualCell, targetCell)) {
                    consumer(CellAndContainerHandle.captureInScope(targetCell))
                }
            }
        }
}
