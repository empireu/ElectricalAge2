package org.ageseries.libage.sim

import org.ageseries.libage.utils.addUnique
import org.ageseries.libage.utils.putUnique
import java.util.function.Supplier

/**
 * Helper for identifying and separating disjoint networks that can be simulated independently.
 * */
class SubSolverSystemBuilder<Node, SubSolver : SubSolverSystemBuilder.PerSubSolverData<SubSolver, Node>>(val factory: Supplier<SubSolver>) {
    data class Handle<T>(var obj: T)

    /**
     * Implemented by the simulation builder.
     * Holds all the data needed by the builder for one simulation.
     * */
    abstract class PerSubSolverData<Self, Node> where Self : PerSubSolverData<Self, Node> {
        val nodes = HashSet<Node>()

        /**
         * Adds a node to the sub system.
         * */
        fun addNode(node: Node) = nodes.add(node)

        /**
         * Copies the data from [other] into this instance.
         * */
        open fun copyFrom(other: Self) {
            other.nodes.forEach {
                nodes.addUnique(it)
            }
        }

        /**
         * Called when the sub solver is placed back into the pool.
         * */
        open fun recycle() {
            nodes.clear()
        }
    }

    /**
     * Holds all the sub-solvers formed so far.
     * */
    val subSolvers = HashSet<SubSolver>()

    /**
     * Maps each node to the sub-solver that contains it.
     * */
    val subSolversByNode = HashMap<Node, Handle<SubSolver>>()

    private val pool = ArrayList<SubSolver>()

    /**
     * Gets a fresh sub-solver from the pool, or creates one with [factory] if the pool is empty.
     * */
    private fun getSubSolver() : SubSolver {
        val result = if(pool.isEmpty()) factory.get() else pool.removeLast()
        subSolvers.addUnique(result)
        return result
    }

    /**
     * Releases the [subSolver] back into the pool.
     * */
    private fun releaseSubSolver(subSolver: SubSolver) {
        subSolver.recycle()
        pool.add(subSolver)
        check(subSolvers.remove(subSolver))
    }

    /**
     * Adds a node. Initially, it creates a sub-solver for this one node.
     * */
    fun addNode(node: Node) : Boolean {
        if(subSolversByNode.contains(node)) {
            return false
        }

        val subSolver = getSubSolver()
        check(subSolver.addNode(node))
        subSolversByNode.putUnique(node, Handle(subSolver))

        return true
    }

    /**
     * Gets the sub-solver for [node]. The node must be added already.
     * */
    fun getSubSolverOf(node: Node) = (subSolversByNode[node] ?: error("Node $node is not added")).obj

    /**
     * Marks the two nodes as connected.
     * If the nodes belong to different sub-solvers, the smaller sub-solver is merged into the larger sub-solver and the smaller one is released back into the pool.
     * @return The final sub-solver, shared by all nodes.
     * */
    fun unite(nodeA: Node, nodeB: Node) : SubSolver {
        if(nodeA === nodeB) {
            error("Cannot unite $nodeA with itself")
        }

        val refA = subSolversByNode[nodeA] ?: error("Node $nodeA is not added")
        val refB = subSolversByNode[nodeB] ?: error("Node $nodeB is not added")

        if(refA.obj !== refB.obj) {
            val smaller: SubSolver
            val larger: SubSolver

            if(refA.obj.nodes.size <= refB.obj.nodes.size) {
                smaller = refA.obj
                larger = refB.obj
            }
            else {
                smaller = refB.obj
                larger = refA.obj
            }

            larger.copyFrom(smaller)

            smaller.nodes.forEach {
                subSolversByNode[it]!!.obj = larger
            }

            releaseSubSolver(smaller)

            refA.obj = larger
            refB.obj = larger
        }

        return refA.obj
    }
}

/**
 * The set of sub-solvers a simulation element is participating in. It should contain at least one sub-solver.
 * */
class SubSolverSet<Solver>(val solvers: List<Solver>)