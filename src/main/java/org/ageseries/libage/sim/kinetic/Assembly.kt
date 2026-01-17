package org.ageseries.libage.sim.kinetic

import org.ageseries.libage.data.MutableMapPairBiMap
import org.ageseries.libage.data.MutableSetMapMultiMap
import org.ageseries.libage.sim.SubSolverSet
import org.ageseries.libage.sim.SubSolverSystemBuilder
import org.ageseries.libage.utils.putUnique

/**
 * Optimizer for kinetic networks. It joins rigidly-connected [KineticDouble]s into one large double.
 * It's similar to the electrical circuit line optimization.
 * */
class KineticNetworkOptimizer(val originalNodes: Set<KineticNode>, val originalRigidConstraints: List<RigidExtensionConstraint>) {
    companion object {
        /**
         * Gets the neighbor for the [extension]. Returns a node if:
         * - there is a single rigid constraint between the two nodes
         * - the two extensions have equal max lambda
         * - the two nodes have equal inertia and ratio of 1
         * */
        private fun getNeighborFromExtension(extension: RigidKineticExtension) : KineticDouble? {
            if(extension.constraints.size == 1) {
                val constraint = extension.constraints[0]

                if(constraint is RigidExtensionConstraint) {
                    check(constraint.a == extension || constraint.b == extension)

                    val otherExtension = constraint.getOther(extension)

                    if(otherExtension.pole != -extension.pole) {
                        return null // Direction not preserved
                    }

                    if(otherExtension.maxLambda != extension.maxLambda) {
                        return null
                    }

                    if(otherExtension.ratio != extension.ratio) {
                        return null
                    }

                    if(otherExtension.ratio != 1.0) {
                        return null
                    }

                    val otherNode = otherExtension.node

                    if(otherNode is KineticDouble && otherNode.inertia == extension.node.inertia && otherExtension.constraints.size == 1) {
                        return otherNode
                    }
                }
            }

            return null
        }

        var USE_VALIDATION = false
    }

    /**
     * All the line graphs with two or more shafts from the eligible set.
     * */
    private val lineGraphs = ArrayList<ArrayList<KineticDouble>>()

    /**
     * All the nodes to remove from the solver's nodes.
     * */
    val nodesToSubtract = HashSet<KineticNode>()
    /**
     * All the constraints to remove from the solver's constraints.
     * */
    val constraintsToSubtract = HashSet<RigidExtensionConstraint>()

    /**
     * All created line shafts.
     * */
    val lineShafts = ArrayList<LineShaft>()
    /**
     * Map of extension of node that was optimized away to corresponding extension of owner line.
     * Two of these per line.
     * */
    val eliminatedNodeExtensionToNewNodeExtension = HashMap<RigidKineticExtension, RigidKineticExtension>()
    /**
     * Lookup to prevent double-rewriting of constraints between lines. P.S. When the constraint is created, Pair(a, b) AND Pair(b, a) for easier inspection.
     * We iterate over all the lines, and we re-write constraints. But the constraints are reciprocal, so we will end up creating a constraint twice, unless we check for it.
     * */
    val realizedLinePairs = LinkedHashSet<Pair<RigidKineticExtension, RigidKineticExtension>>()

    /**
     * Final nodes to pass to solver.
     * */
    val newNodes = ArrayList<KineticNode>()
    /**
     * Final constraints to pass to solver.
     * */
    val newRigidConstraints = ArrayList<RigidExtensionConstraint>()

    /**
     * Gathers all the line graphs in [lineGraphs].
     * */
    private fun gatherGraphs() {
        val eligibleShafts = originalNodes
            .asSequence()
            .mapNotNull { it as? KineticDouble }
            .filter {
                if(!it.allowOptimization) {
                    return@filter false
                }

                val e1 = it.e1
                val e2 = it.e2

                if (e1.maxLambda != e2.maxLambda) {
                    return@filter false
                }

                if(e1.ratio != 1.0 || e2.ratio != 1.0) {
                    return@filter false
                }

                if (it.nodeConstraints.isNotEmpty()) {
                    return@filter false // Non-extension constraints
                }

                if(e1.constraints.size > 1 || e2.constraints.size > 1) {
                    return@filter false
                }

                if(e1.constraints.isNotEmpty() && e1.constraints[0] !is RigidExtensionConstraint) {
                    return@filter false
                }

                if(e2.constraints.isNotEmpty() && e2.constraints[0] !is RigidExtensionConstraint) {
                    return@filter false
                }

                return@filter true // first pass. We need to then filter by neighbor using the shafts gathered from this first pass.
            }
            .toHashSet()

        val withoutNeighbor = eligibleShafts
            .asSequence()
            .filter { subject ->
                // |e1      e2|-----|e1 e2|-----|e1      e2|
                // neighborOnE1     subject     neighborOnE2

                // [getNeighborFromExtension] already checks the poles to make sure we are preserving direction.

                val neighborOnE1 = getNeighborFromExtension(subject.e1)

                if(neighborOnE1 != null && eligibleShafts.contains(neighborOnE1)) {
                    return@filter false
                }

                val neighborOnE2 = getNeighborFromExtension(subject.e2)

                return@filter !(neighborOnE2 != null && eligibleShafts.contains(neighborOnE2))
                // Check if direction is preserved:
            }
            .toHashSet()

        eligibleShafts.removeAll(withoutNeighbor)

        // All shafts visited to the left of current:
        val visitedLeft = HashSet<KineticDouble>()

        // All shafts visited to the right of the left-most shaft found (including left-most, including anchor):
        val visitedRight = HashSet<KineticDouble>()

        while (eligibleShafts.isNotEmpty()) {
            var current = eligibleShafts.first()

            // We will construct the line graph in order.

            // Get left-most node:
            while (true) {
                val left = getNeighborFromExtension(current.e1)

                if(left != null && eligibleShafts.contains(left) && visitedLeft.add(left)) {
                    current = left
                }
                else {
                    break
                }
            }

            // Then traverse toward the right and add to list.
            // But also make sure we didn't create a cycle.
            val lineGraph = ArrayList<KineticDouble>()

            while (true) {
                if(!visitedRight.add(current)) {
                    break
                }

                lineGraph.add(current)

                val right = getNeighborFromExtension(current.e2)

                if(right != null && eligibleShafts.contains(right)) {
                    current = right
                }
                else {
                    break
                }
            }

            lineGraph.forEach {
                eligibleShafts.remove(it)
            }

            lineGraphs.add(lineGraph)

            visitedLeft.clear()
            visitedRight.clear()
        }
    }

    /**
     * Gathers all the nodes to eliminate from the global system in [nodesToSubtract] and the constraints to eliminate in [constraintsToSubtract].
     * */
    private fun gatherNodesAndConstraintsToSubtract() {
        lineGraphs.forEach { graph ->
            // Subtract all nodes from line graphs.
            // They will be owned by the super nodes.
            nodesToSubtract.addAll(graph)

            // Subtract all constraints too.
            graph.forEach { node ->
                check(node.e1.constraints.size <= 1)
                check(node.e2.constraints.size <= 1)

                if(node.e1.constraints.isNotEmpty()) {
                    constraintsToSubtract.add(node.e1.constraints[0] as RigidExtensionConstraint)
                }

                if(node.e2.constraints.isNotEmpty()) {
                    constraintsToSubtract.add(node.e2.constraints[0] as RigidExtensionConstraint)
                }
            }
        }
    }

    /**
     * Creates a line shaft in [lineShafts] for each of the graphs in [lineGraphs].
     * Maps the left and right extensions of the eliminated nodes to the left and right extensions of the new owner line shaft.
     *
     * We can't rewrite the constraints here because there can be adjacent lines, so we need all the lines already created so we can look up their extensions in a second pass.
     *
     * Finally, the created shafts are copied into [newNodes].
     * */
    private fun createCombinedNodesAndEdgeMap() {
        lineGraphs.forEach { graph ->
            val line = LineShaft(graph.toTypedArray())

            lineShafts.add(line)

            val leftNode = graph.first()
            val rightNode = graph.last()

            // Make the edge mapping:
            eliminatedNodeExtensionToNewNodeExtension.putUnique(leftNode.e1, line.e1)
            eliminatedNodeExtensionToNewNodeExtension.putUnique(rightNode.e2, line.e2)
        }

        // Add new nodes:
        lineShafts.forEach {
            newNodes.add(it)
        }
    }

    /**
     * Translates the constraints of the eliminated node edge extensions in each graph in [lineGraphs] into constraints with the created line's extensions.
     * This is done as a separate pass to [createCombinedNodesAndEdgeMap] because all the lines need to be known, because lines can form one next to each other, so mapping needs all the line nodes ahead of time.
     * */
    private fun rewriteConstraints() {
        lineShafts.forEach { line ->
            val leftNode = line.lineGraph.first()
            val rightNode = line.lineGraph.last()

            /**
             * Translates the constraint to the [formerExtension] (a node now owned by the line and not simulated) into a constraint with the line, which is simulated.
             * @param formerExtension The extension going outside the graph, from the first or the last node.
             * If for the first node, it is `e1`, and if it's for the last node, it is `e2`.
             * The extension connected this one can be another generated line, so we use the [eliminatedNodeExtensionToNewNodeExtension] to find that out.
             * If it is, we just create a constraint, and that's all. If it is not, we need to remove the old constraint from the unaffected other node and create the new constraint.
             * @param physicalExtension The corresponding extension of the line shaft.
             * */
            fun translateExtension(formerExtension: RigidKineticExtension, physicalExtension: RigidKineticExtension) {
                if(formerExtension.constraints.isEmpty()) {
                    return // Nothing to translate
                }

                check(formerExtension.constraints.size == 1)

                /**
                 * The constraint to translate. It is already eliminated from the solver by [gatherNodesAndConstraintsToSubtract].
                 * This constraint contains an extension from the internal node, and an extension from a node outside this line.
                 * */
                val constraint = formerExtension.constraints[0] as RigidExtensionConstraint
                val otherExtension = constraint.getOther(formerExtension)

                // P.S. The constructor of [ExtensionConstraint] adds the constraint to the internal lists on the extensions.

                // otherExtension can be the extension of a node that was optimized away:
                val correspondingOwnerExtension = eliminatedNodeExtensionToNewNodeExtension[otherExtension]

                val newConstraint = if(correspondingOwnerExtension == null) {
                    // Maps to a former, unoptimized node:
                    otherExtension.removeConstraint(constraint)
                    RigidExtensionConstraint(physicalExtension, otherExtension)
                }
                else {
                    val isAlreadyRealized =
                        !realizedLinePairs.add(Pair(physicalExtension, correspondingOwnerExtension)) ||
                                !realizedLinePairs.add(Pair(correspondingOwnerExtension, physicalExtension))

                    // Make sure we didn't already remap this.
                    // Re-mapping this specific pair will occur twice (once, in this order, and second time, in the reverse order).
                    if(isAlreadyRealized) {
                        return
                    }

                    // Maps to another line:
                    RigidExtensionConstraint(physicalExtension, correspondingOwnerExtension)
                }

                newRigidConstraints.add(newConstraint)
            }

            translateExtension(leftNode.e1, line.e1)
            translateExtension(rightNode.e2, line.e2)
        }
    }

    /**
     * Transfers [originalNodes] that aren't in [nodesToSubtract] into [newNodes].
     * Transfers [originalRigidConstraints] that aren't in [constraintsToSubtract] into [newRigidConstraints].
     * */
    private fun transferNonEliminatedNodesAndConstraints() {
        originalNodes.forEach { node ->
            if(!nodesToSubtract.contains(node)) {
                newNodes.add(node)
            }
        }

        originalRigidConstraints.forEach { constraint ->
            if(!constraintsToSubtract.contains(constraint)) {
                newRigidConstraints.add(constraint)
            }
        }
    }

    fun execute() {
        gatherGraphs()
        gatherNodesAndConstraintsToSubtract()
        createCombinedNodesAndEdgeMap()
        rewriteConstraints()
        transferNonEliminatedNodesAndConstraints()

        if(USE_VALIDATION) {
            lineGraphs.forEach { graph ->
                graph.indices.forEach { i ->
                    val subject = graph[i]

                    if(i != 0) {
                        if(getNeighborFromExtension(subject.e1) !== graph[i - 1]) {
                            error("Could not get neighbor from extension")
                        }
                    }

                    if(i != graph.size - 1) {
                        if(getNeighborFromExtension(subject.e2) !== graph[i + 1]) {
                            error("Could not get neighbor from extension")
                        }
                    }
                }
            }

            newRigidConstraints.forEach { constraint ->
                if(!newNodes.contains(constraint.a.node)) {
                    error("Could not get neighbor from extension")
                }

                if(!newNodes.contains(constraint.b.node)) {
                    error("Could not get neighbor from extension")
                }
            }
        }
    }
}

/**
 * Represents a set of kinetic nodes.
 * */
interface KineticNodeSet {
    /**
     * Adds the node to the underlying builder.
     * @return True if the node was added. Otherwise, false (the node was already added).
     * */
    fun add(node: KineticNode) : Boolean
}

/**
 * Represent a set of constraints between [KineticNode]s and other [KineticNode]s.
 * The constraints are either extension-based (inter-object or inside objects) or directly node-based (for inside objects only).
 * */
interface KineticConstraintMap {
    /**
     * Creates a constraint between the two nodes, based on the supplied extensions.
     * */
    fun join(a: KineticExtension, b: KineticExtension)

    /**
     * Creates a clutch constraint between the two nodes.
     * Only valid to call for nodes owned by the same object!
     * */
    fun createClutch(prototype: ClutchPrototype)
}

/**
 * Main builder for a **Kinetic Simulation Forest**.
 * Building is meant to be separated into discrete stages using the [KineticNodeSet] and [KineticConstraintMap].
 * */
class KineticSimulationForestBuilder : KineticNodeSet, KineticConstraintMap {
    class SubSolverData : SubSolverSystemBuilder.PerSubSolverData<SubSolverData, KineticNode>() {
        val rigidConstraints = ArrayList<RigidExtensionConstraint>()
        val clutchConstraints = ArrayList<ClutchConstraint>()

        override fun copyFrom(other: SubSolverData) {
            super.copyFrom(other)

            rigidConstraints.addAll(other.rigidConstraints)
            clutchConstraints.addAll(other.clutchConstraints)
        }

        override fun recycle() {
            super.recycle()

            rigidConstraints.clear()
            clutchConstraints.clear()
        }
    }

    private val builder = SubSolverSystemBuilder<KineticNode, SubSolverData> { SubSolverData() }

    /**
     * Used to ensure no duplicate constraints are created by [join].
     * */
    private val extensionPairsMulti = MutableSetMapMultiMap<KineticExtension, KineticExtension>()

    /**
     * Used to ensure no duplicate [NodeConstraint]s are created by the user in [createClutchConstraint].
     * */
    private val nodePairs = MutableMapPairBiMap<KineticNode, KineticNode>()

    private var built = false

    private fun validateUsage() {
        require(!built) {
            "Cannot re-use kinetic simulation builder"
        }
    }

    override fun add(node: KineticNode) : Boolean {
        validateUsage()
        return builder.addNode(node)
    }

    /**
     * Called exclusively by [join].
     * */
    private fun generateExtensionRigid(a: RigidKineticExtension, b: RigidKineticExtension) = builder
        .unite(a.node, b.node)
        .rigidConstraints
        .add(RigidExtensionConstraint(a, b))

    /**
     * Creates a constraint "between" the two extensions.
     * Calling this multiple time with the same arguments (or the arguments in flipped order) will not create additional constraints.
     * */
    override fun join(a: KineticExtension, b: KineticExtension) {
        validateUsage()

        if(!extensionPairsMulti[a].add(b) || !extensionPairsMulti[b].add(a)) {
            return
        }

        val ext1: KineticExtension
        val ext2: KineticExtension
        if(a.priority == b.priority) {
            ext1 = a
            ext2 = b
        }
        else {
            if(a.priority < b.priority) {
                ext1 = a
                ext2 = b
            }
            else {
                ext1 = b
                ext2 = a
            }
        }

        if(ext1 is RigidKineticExtension) {
            if(ext2 is RigidKineticExtension) {
                generateExtensionRigid(ext1, ext2)
            }
            else error("Invalid extension $ext2")
        }
        else error("Invalid extension $ext1")
    }

    /**
     * Creates a clutch constraint from the [prototype].
     * Calling this multiple times with the same argument will result in an error.
     * */
    override fun createClutch(prototype: ClutchPrototype) {
        nodePairs.add(prototype.a, prototype.b)

        builder
            .unite(prototype.a, prototype.b)
            .clutchConstraints
            .add(ClutchConstraint(prototype))
    }

    /**
     * Creates the final set of sub-solvers.
     * @param optimize If true, lines of shafts will be optimized away. Similar to the circuit's resistor line optimization.
     * */
    fun build(dt: Double, optimize: Boolean = true) = SubSolverSet(run {
        validateUsage()
        built = true

        if (!optimize) {
            builder.subSolvers.map {
                KineticSimulation(
                    dt,
                    it.nodes.toTypedArray(),
                    emptyArray(),
                    it.rigidConstraints.toTypedArray(),
                    it.clutchConstraints.toTypedArray()
                )
            }
        } else builder.subSolvers.map {
            val optimizer = KineticNetworkOptimizer(it.nodes, it.rigidConstraints)

            optimizer.execute()

            KineticSimulation(
                dt,
                optimizer.newNodes.toTypedArray(),
                optimizer.lineShafts.toTypedArray(),
                optimizer.newRigidConstraints.toTypedArray(),
                it.clutchConstraints.toTypedArray()
            )
        }

    })
}