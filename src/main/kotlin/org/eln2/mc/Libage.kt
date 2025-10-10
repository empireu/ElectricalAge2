package org.eln2.mc

import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.SYMFORCE_EPS
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.lerp
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.electrical.mna.Circuit
import org.ageseries.libage.sim.electrical.mna.CircuitBuilder
import org.ageseries.libage.sim.electrical.mna.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.LARGE_RESISTANCE
import org.ageseries.libage.sim.electrical.mna.NEGATIVE
import org.ageseries.libage.sim.electrical.mna.POSITIVE
import org.ageseries.libage.sim.electrical.mna.VirtualComponent
import org.ageseries.libage.sim.electrical.mna.component.Component
import org.ageseries.libage.sim.electrical.mna.component.IResistor
import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.ageseries.libage.sim.electrical.mna.component.Term
import org.ageseries.libage.sim.electrical.mna.component.VoltageSource
import org.ageseries.libage.sim.electrical.mna.component.updateResistance
import org.ageseries.libage.utils.Stopwatch
import org.ageseries.libage.utils.addUnique
import org.ageseries.libage.utils.putUnique
import org.ageseries.libage.utils.sourceName
import java.util.function.Supplier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.reflect.jvm.kotlinProperty

//#region Sim

/**
 * Frak you Grissess (gently and with love though ♡).
 * */
class ElectricalSubSolverSystemBuilder : ElectricalComponentSet, ElectricalConnectivityMap {
    class SubSolverData : SubSolverSystemBuilder.PerSubSolverData<SubSolverData, Term>() {
        data class Connection(val a: Term, val aIdx: Int, val b: Term, val bIdx: Int)

        val connections = ArrayList<Connection>()

        override fun copyFrom(other: SubSolverData) {
            super.copyFrom(other)

            connections.addAll(other.connections)
        }

        override fun recycle() {
            super.recycle()

            connections.clear()
        }
    }

    private val builder = SubSolverSystemBuilder<Term, SubSolverData> { SubSolverData() }
    private var built = false

    private fun validateUsage() {
        require(!built) {
            "Cannot re-use electrical simulation builder"
        }
    }

    override fun add(component: VirtualComponent): Boolean {
        validateUsage()
        return builder.addNode(component)
    }

    override fun add(component: Component): Boolean {
        validateUsage()
        return builder.addNode(component)
    }

    override fun add(component: Term): Boolean {
        validateUsage()
        return builder.addNode(component)
    }

    override fun connect(a: VirtualComponent, aIdx: Int, b: VirtualComponent, bIdx: Int) {
        validateUsage()
        builder
            .unite(a, b)
            .connections
            .add(SubSolverData.Connection(a, aIdx, b, bIdx))
    }

    override fun connect(a: VirtualComponent, aIdx: Int, b: Component, bIdx: Int) {
        validateUsage()
        builder
            .unite(a, b)
            .connections
            .add(SubSolverData.Connection(a, aIdx, b, bIdx))
    }

    override fun connect(a: Component, aIdx: Int, b: VirtualComponent, bIdx: Int) {
        validateUsage()
        builder
            .unite(a, b)
            .connections
            .add(SubSolverData.Connection(a, aIdx, b, bIdx))
    }

    override fun connect(a: Component, aIdx: Int, b: Component, bIdx: Int) {
        validateUsage()
        builder
            .unite(a, b)
            .connections
            .add(SubSolverData.Connection(a, aIdx, b, bIdx))
    }

    override fun connect(a: Term, aIdx: Int, b: Term, bIdx: Int) {
        validateUsage()
        builder
            .unite(a, b)
            .connections
            .add(SubSolverData.Connection(a, aIdx, b, bIdx))
    }

    fun build() = SubSolverSet(run {
        validateUsage()
        built = true

        builder.subSolvers.map {
            val circuit = Circuit()
            val builder = CircuitBuilder(circuit)

            it.nodes.forEach { term ->
                builder.add(term)
            }

            it.connections.forEach { (a, aIdx, b, bIdx) ->
                builder.connect(a, aIdx, b, bIdx)
            }

            builder.build()
            circuit
        }
    })
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

class SubSolverSet<Solver>(val solvers: List<Solver>)

/**
 * Helper for identifying and separating disjoint networks of nodes for a simulation.
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
 * Represents a rigid line of shafts connected end-to-end. They have the same inertia, same max lambdas, gear ratio of 1.
 * */
class LineShaft(val lineGraph: Array<KineticShaft>) : KineticNode(false), KineticNodeProxy {
    val e1 = RigidKineticExtension(this)
    val e2 = RigidKineticExtension(this)

    private var previousLineOmega = 0.0
    private val originalExternalTorque = DoubleArray(lineGraph.size)

    init {
        inertia = lineGraph.sumOf { it.inertia }
        e1.ratio = lineGraph[0].e1.ratio
        e2.ratio = e1.ratio
    }

    private fun importShaftAngularVelocities() {
        // Import angular velocity by conserving angular momentum (inelastic collision):
        omega = lineGraph.sumOf { it.inertia * it.omega } / inertia
        // Writing the values back is not necessary, it is done in [distributeResults] after the simulation runs.
    }

    fun prepareForStep() {
        importShaftAngularVelocities()

        var totalExternalTorque = 0.0
        for (i in lineGraph.indices) {
            val shaft = lineGraph[i]

            originalExternalTorque[i] = shaft.externalTorque

            totalExternalTorque += shaft.externalTorque
            shaft.externalTorque = 0.0

            shaft.previousAngle = shaft.angle
        }

        var totalFriction = 0.0
        val lineOmega = this.omega
        for (i in lineGraph.indices) {
            val shaft = lineGraph[i]
            val viscous = -shaft.viscousDamping * lineOmega

            val frictionForShaft = if (abs(lineOmega) > shaft.velocityEps) {
                -shaft.coulombFriction * sign(lineOmega) + viscous
            } else {
                val t = originalExternalTorque[i]
                if (abs(t) <= shaft.staticFriction) {
                    -t
                } else {
                    -shaft.coulombFriction * sign(t) + viscous
                }
            }

            shaft.frictionTorque = frictionForShaft
            totalFriction += frictionForShaft
        }

        externalTorque = totalExternalTorque + totalFriction
        previousLineOmega = omega
    }

    override fun setSimulation(id: Int, simulation: KineticSimulation, proxy: KineticNodeProxy?) {
        super.setSimulation(id, simulation, proxy)

        lineGraph.forEachIndexed { id, shaft ->
            shaft.setSimulation(id, simulation, this)
        }
    }

    override fun simulationDestroyed() {
        super.simulationDestroyed()

        lineGraph.forEach { shaft ->
            shaft.simulationDestroyed()
        }
    }

    fun distributeResults() {
        val deltaAngle = angle - previousAngle
        val deltaOmega = omega - previousLineOmega

        lineGraph.forEach { shaft ->
            shaft.angle += deltaAngle
            shaft.omega = this.omega
        }

        if (e1.constraints.isNotEmpty()) {
            check(e1.constraints.size == 1)
            val leftPhysicalConstraint = lineGraph.first().e1.constraints[0]
            leftPhysicalConstraint.lambda = e1.constraints[0].lambda
        }

        if (e2.constraints.isNotEmpty()) {
            check(e2.constraints.size == 1)
            val rightPhysicalConstraint = lineGraph.last().e2.constraints[0]
            rightPhysicalConstraint.lambda = e2.constraints[0].lambda
        }

        fun impulseOnExtensionFromConstraint(constraint: RigidExtensionConstraint, extension: RigidKineticExtension): Double {
            if (constraint.a === extension) {
                return constraint.lambda
            }

            if (constraint.b === extension) {
                return constraint.lambda * (-constraint.b.ratio / constraint.a.ratio)
            }

            error("Constraint does not reference extension")
        }

        fun coefficientForExtension(constraint: RigidExtensionConstraint, extension: RigidKineticExtension): Double {
            return if (constraint.a !== extension) {
                -constraint.b.ratio / constraint.a.ratio
            }
            else 1.0
        }

        for (i in 0 until lineGraph.size - 1) {
            val node = lineGraph[i]
            val leftExtension = node.e1
            val rightExtension = node.e2

            check(rightExtension.constraints.isNotEmpty()) {
                "Missing internal constraint on e2 of node $i"
            }

            val rightConstraint = rightExtension.constraints[0] as RigidExtensionConstraint

            val leftContribution = if (leftExtension.constraints.isNotEmpty()) {
                val leftConstraint = leftExtension.constraints[0] as RigidExtensionConstraint
                impulseOnExtensionFromConstraint(leftConstraint, leftExtension)
            } else {
                0.0
            }

            val neededImpulse = node.inertia * deltaOmega

            val c = coefficientForExtension(rightConstraint, rightExtension)

            val lambdaRight = if (abs(c) < SYMFORCE_EPS) {
                0.0
            } else {
                (neededImpulse - leftContribution) / c
            }

            // assign to the internal constraint
            rightConstraint.lambda = lambdaRight
        }
    }
}

class KineticNetworkOptimizer(val nodes: Set<KineticNode>, val rigidConstraints: List<RigidExtensionConstraint>) {
    companion object {
        /**
         * Gets the neighbor for the [extension]. Returns a node if:
         * - there is a single rigid constraint between the two nodes
         * - the two extensions have equal max lambda
         * - the two nodes have equal inertia and ratio of 1
         * */
        private fun getNeighborFromExtension(extension: RigidKineticExtension) : KineticShaft? {
            if(extension.constraints.size == 1) {
                val constraint = extension.constraints[0]

                if(constraint is RigidExtensionConstraint) {
                    check(constraint.a == extension || constraint.b == extension)

                    val otherExtension = if(constraint.a == extension) constraint.b else constraint.a

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

                    if(otherNode is KineticShaft && otherNode.inertia == extension.node.inertia && otherExtension.constraints.size == 1) {
                        return otherNode
                    }
                }
            }

            return null
        }
    }

    /**
     * All the line graphs with two or more shafts from the eligible set.
     * */
    private val lineGraphs = ArrayList<ArrayList<KineticShaft>>()

    // Re-written nodes and rigid constraints:
    val newNodes = ArrayList<KineticNode>()
    val newRigidConstraints = ArrayList<RigidExtensionConstraint>()

    // The optimized line shafts:
    val lineShafts = ArrayList<LineShaft>()

    private fun gatherGraphs() {
        val eligibleShafts = nodes
            .asSequence()
            .mapNotNull { it as? KineticShaft }
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

                return@filter getNeighborFromExtension(e1) != null || getNeighborFromExtension(e2) != null
            }
            .toHashSet()

        while (eligibleShafts.isNotEmpty()) {
            var current = eligibleShafts.first()

            // We will construct the line graph in order.

            // Get left-most node:
            while (true) {
                val left = getNeighborFromExtension(current.e1)

                if(left != null && eligibleShafts.contains(left)) {
                    current = left
                }
                else {
                    break
                }
            }

            val leftMost = current

            // Then traverse toward the right and add to list.
            // But also make sure we didn't create a cycle.
            val lineGraph = ArrayList<KineticShaft>()

            while (true) {
                lineGraph.add(current)

                val right = getNeighborFromExtension(current.e2)

                if(right == leftMost) {
                    break  // Cycle
                }

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
        }
    }

    fun execute() {
        /**
         * Gathers all the line graphs in [lineGraphs].
         * */
        gatherGraphs()

        // Make lookups for all internal nodes and internal constraints (this doesn't include constraints at the edge nodes):
        val nodesToSubtract = HashSet<KineticNode>()
        val constraintsToSubtract = HashSet<RigidExtensionConstraint>()

        lineGraphs.forEach { graph ->
            nodesToSubtract.addAll(graph) // Subtract all nodes from line graphs. They will be owned by the super nodes

            // Subtract constraints on the interior only for now.
            for (i in 0 until graph.size - 1) {
                constraintsToSubtract.add(graph[i].e2.constraints[0] as RigidExtensionConstraint)
            }
        }

        // For each line graph, create a new combined node. Also blacklist the edge constraints.
        // Then, we can re-create the edge constraints to be with this new master node:
        lineGraphs.forEach { graph ->
            val superNode = LineShaft(graph.toTypedArray())
            lineShafts.add(superNode)
            newNodes.add(superNode)

            val leftNode = graph.first()
            val rightNode = graph.last()

            fun processEdge(virtualExtension: RigidKineticExtension, physicalExtension: RigidKineticExtension) {
                if(virtualExtension.constraints.isEmpty()) {
                    return
                }

                check(virtualExtension.constraints.size == 1)

                val constraint = virtualExtension.constraints[0] as RigidExtensionConstraint

                // Blacklist old constraint with the now virtual nodes:
                constraintsToSubtract.add(constraint)

                // Create a new constraint with the super node:
                if(constraint.a == virtualExtension) {
                    newRigidConstraints.add(
                        RigidExtensionConstraint(physicalExtension, constraint.b)
                    )
                }
                else {
                    check(constraint.b == virtualExtension)
                    newRigidConstraints.add(
                        RigidExtensionConstraint(constraint.a, physicalExtension)
                    )
                }
            }

            // Blacklist edge constraints of the internal nodes and rebuild them for the super node:
            processEdge(leftNode.e1, superNode.e1)
            processEdge(rightNode.e2, superNode.e2)
        }

        // Create new collections:

        rigidConstraints.forEach { constraint ->
            if(!constraintsToSubtract.contains(constraint)) {
                newRigidConstraints.add(constraint)
            }
        }

        nodes.forEach { node ->
            if(!nodesToSubtract.contains(node)) {
                newNodes.add(node)
            }
        }
    }
}

class KineticSubSolverSystemBuilder : KineticNodeSet, KineticConstraintMap {
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
        }
        else builder.subSolvers.map {
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

class KineticSimulation(
    val dt: Double,
    val nodes: Array<KineticNode>,
    val optimizedShafts: Array<LineShaft>,
    val rigidConstraints: Array<RigidExtensionConstraint>,
    val clutchConstraints: Array<ClutchConstraint>
) {
    /**
     * Nodes which have built-in friction.
     * */
    val frictionNodes = nodes.mapNotNull { it as? FrictionKineticNode }.toTypedArray()

    /**
     * Baumgarte stabilization factor.
     * */
    var biasFactor = 0.1

    //#region Gauss-Seidel Options

    var minIterations = 4
    var maxIterations = 8192
    var maxIterationsFirstStep = maxIterations * 16
    var dLEps = 1e-3
    var residualEps = 1e-4

    /**
     * If true, the constraints will also be iterated in reverse order.
     * */
    var useSymmetricGaussSeidel = true

    /**
     * Adaptive successive relaxation max factor.
     * */
    var maxOmega = 2.0

    /**
     * When the sign of the step is consistent, the new relaxation factor becomes `omega * [omegaIncreaseFactor]`, at most [maxOmega].
     * */
    var omegaIncreaseFactor = 1.02

    /**
     * Adaptive successive relaxation min factor.
     * */
    var minOmega = 0.9

    /**
     * When the sign of the step is flipping, the new relaxation factor becomes `omega * [omegaDecreaseFactor]`, at least [minOmega].
     * */
    var omegaDecreaseFactor = 0.7

    /**
     * If the applied impulse is within [saturationLimit] of the max impulse, then the constraint is saturated (residual becomes 0).
     * */
    var saturationLimit = 1e-6

    //#endregion

    /**
     * Packed storage for constraints during solve.
     * In the data:
     * - first long is the 2 indices of the nodes
     * - second long is the second jacobian entry
     * - third long is the bias
     * - fourth long is the relaxation coefficient (used for adaptive relaxation)
     * - fifth long is the delta lambda (used for adaptive relaxation)
     * */
    @Suppress("NOTHING_TO_INLINE") @JvmInline
    private value class ConstraintArray(val backingStorage: LongArray) {
        val count get() = backingStorage.size / 5

        constructor(constraintCount: Int) : this(LongArray(constraintCount * 5))

        init {
            for (i in 0 until count) {
                setOmega(i, 1.0)
            }
        }

        inline fun getIndexA(constraint: Int) = (backingStorage[constraint * 5] shr 32).toInt()
        inline fun getIndexB(constraint: Int) = (backingStorage[constraint * 5] and 0xFFFFFFFF).toInt()
        inline fun getJacobian(constraint: Int) = Double.fromBits(backingStorage[constraint * 5 + 1])
        inline fun getBias(constraint: Int) = Double.fromBits(backingStorage[constraint * 5 + 2])
        inline fun getOmega(constraint: Int) = Double.fromBits(backingStorage[constraint * 5 + 3])
        inline fun getDl(constraint: Int) = Double.fromBits(backingStorage[constraint * 5 + 4])

        inline fun setIndices(constraint: Int, indexA: Int, indexB: Int) { backingStorage[constraint * 5] = (indexA.toLong() shl 32) or indexB.toLong() }
        inline fun setJacobian(constraint: Int, jacobian: Double) { backingStorage[constraint * 5 + 1] = jacobian.toBits() }
        inline fun setBias(constraint: Int, bias: Double) { backingStorage[constraint * 5 + 2] = bias.toBits() }
        inline fun setOmega(constraint: Int, omega: Double) { backingStorage[constraint * 5 + 3] = omega.toBits() }
        inline fun setDl(constraint: Int, dL: Double) { backingStorage[constraint * 5 + 4] = dL.toBits() }
    }

    /**
     * Packed storage for lambda and max lambda during solve.
     * - first double is the lambda
     * - second double is the maxLambda
     * */
    @Suppress("NOTHING_TO_INLINE") @JvmInline
    private value class LambdaArray(val backingStorage: DoubleArray) {
        constructor(constraintCount: Int) : this(DoubleArray(constraintCount * 2))

        inline fun getLambda(constraint: Int) = backingStorage[constraint * 2]
        inline fun getMaxLambda(constraint: Int) = backingStorage[constraint * 2 + 1]

        inline fun setLambda(constraint: Int, lambda: Double) { backingStorage[constraint * 2] = lambda }
        inline fun setMaxLambda(constraint: Int, maxLambda: Double) { backingStorage[constraint * 2 + 1] = maxLambda }
    }

    var destroyed = false
        private set

    var firstStep = true
        private set

    private val omegaStar = DoubleArray(nodes.size)
    private val inverseI = DoubleArray(nodes.size)

    private val rigidConstraintData = ConstraintArray(rigidConstraints.size)
    private val rigidConstraintLambda = LambdaArray(rigidConstraints.size)
    private val clutchConstraintData = ConstraintArray(clutchConstraints.size)
    private val clutchConstraintLambda = LambdaArray(clutchConstraints.size)
    private val clutchCandidates = BooleanArray(clutchConstraints.size)

    init {
        // Assign (remaining, after optimization) constraints to simulation:
        rigidConstraints.forEachIndexed { index, constraint ->
            constraint.setSimulation(index)
        }

        clutchConstraints.forEachIndexed { index, constraint ->
            constraint.setSimulation(index)
        }

        // Assign nodes to simulation:
        var nodeIndex = 0
        nodes.forEach { node ->
            node.setSimulation(nodeIndex, this, null)
            nodeIndex++
        }

        // Set rigid constraint indices and max lambda:
        rigidConstraints.forEach { rigid ->
            rigidConstraintData.setIndices(rigid.id, rigid.a.node.idInOwner, rigid.b.node.idInOwner)
            rigidConstraintLambda.setMaxLambda(rigid.id, min(rigid.a.maxLambda, rigid.b.maxLambda))
        }

        // Set clutch indices:
        clutchConstraints.forEach { clutch ->
            clutchConstraintData.setIndices(clutch.id, clutch.a.idInOwner, clutch.b.idInOwner)
            // maxLambda is calculated at solve time
        }
    }

    /**
     * The total energy in rotating nodes, updated after [step].
     * */
    var kineticEnergy = 0.0
        private set

    private fun validateUsage() {
        if(destroyed) {
            error("Cannot use kinetic simulation after destroyed")
        }
    }

    private fun warmStart(constraints: ConstraintArray, lambdas: LambdaArray) {
        val omegaStar = this.omegaStar
        val inverseI = this.inverseI

        val count = constraints.count
        var constraintId = 0
        while (constraintId < count) {
            val nodeA = constraints.getIndexA(constraintId)
            val nodeB = constraints.getIndexB(constraintId)
            val jacobian = constraints.getJacobian(constraintId)
            val lambda = lambdas.getLambda(constraintId)

            omegaStar[nodeA] += inverseI[nodeA] * lambda
            omegaStar[nodeB] += inverseI[nodeB] * jacobian * lambda

            constraintId++
        }
    }

    private class IterationError {
        var maxDeltaL = Double.NaN
        var maxResidual = Double.NaN

        fun begin() {
            maxDeltaL = Double.NEGATIVE_INFINITY
            maxResidual = Double.NEGATIVE_INFINITY
        }

        @Suppress("NOTHING_TO_INLINE")
        inline fun apply(deltaL: Double, residual: Double) {
            val deltaLAbs = abs(deltaL)
            val residualAbs = abs(residual)

            if(deltaLAbs > maxDeltaL) {
                maxDeltaL = deltaLAbs
            }

            if(residualAbs > maxResidual) {
                maxResidual = residualAbs
            }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun projectedGaussSeidelConstraint(constraintId: Int, constraints: ConstraintArray, lambdas: LambdaArray, error: IterationError) {
        val nodeA = constraints.getIndexA(constraintId)
        val nodeB = constraints.getIndexB(constraintId)
        val jacobian = constraints.getJacobian(constraintId)
        val bias = constraints.getBias(constraintId)
        val omega = constraints.getOmega(constraintId)
        val prevDl = constraints.getDl(constraintId)

        val lambda = lambdas.getLambda(constraintId)
        val lambdaMax = lambdas.getMaxLambda(constraintId)

        val inverseIA = inverseI[nodeA]
        val inverseIB = inverseI[nodeB]

        // Relative velocity:
        val dv = omegaStar[nodeA] + jacobian * omegaStar[nodeB]

        // Effective mass:
        val k = inverseIA + inverseIB * (jacobian * jacobian)

        // Solve for new lambda:
        val lambdaStar = (lambda - (dv + bias) / k)
        val lambdaNew = ((1.0 - omega) * lambda + omega * lambdaStar).coerceIn(-lambdaMax, lambdaMax)

        lambdas.setLambda(constraintId, lambdaNew)

        val dL = lambdaNew - lambda
        omegaStar[nodeA] += inverseIA * dL
        omegaStar[nodeB] += inverseIB * jacobian * dL

        // Adaptive relaxation:
        constraints.setOmega(constraintId,
            if (dL * prevDl < 0.0) {
                // Sign flip. Reduce omega:
                max(minOmega, omega * omegaDecreaseFactor)
            }
            else {
                // Steady. Increase omega:
                min(maxOmega, omega * omegaIncreaseFactor)
            }
        )

        constraints.setDl(constraintId, dL)

        val residual = if(!(abs(lambdaNew).approxEq(lambdaMax, saturationLimit))) {
            dv + bias
        }
        else {
            0.0 // Saturated
        }

        error.apply(dL, residual)

        // Can also calculate the energy error from the residuals, it was interesting to see it decrease during iterations.
    }

    /**
     * Executes one PGS iteration for the given constraints and writes the error into [error].
     * @param reversed If true, the constraints will be iterated in reverse order.
     * */
    private fun projectedGaussSeidelIteration(constraints: ConstraintArray, lambdas: LambdaArray, error: IterationError, reversed: Boolean) {
        // Tested unrolling, the cost is insignificant so it's not worth it.

        if(reversed) {
            var constraintId = constraints.count - 1
            while (constraintId > -1) {
                projectedGaussSeidelConstraint(constraintId, constraints, lambdas, error)
                constraintId--
            }
        }
        else {
            var constraintId = 0
            while (constraintId < constraints.count) {
                projectedGaussSeidelConstraint(constraintId, constraints, lambdas, error)
                constraintId++
            }
        }
    }

    /**
     * Steps the simulation.
     * @return The number of PGS iterations taken.
     * */
    fun step() : Int {
        validateUsage()

        optimizedShafts.forEach { shaft ->
            shaft.prepareForStep()
        }

        // Applies viscous friction torque for unlocked clutches:
        clutchConstraints.forEach { clutch ->
            val p = clutch.prototype

            if (!p.locked && p.pressure > 0.0) {
                val jacobian = clutchConstraintData.getJacobian(clutch.id)

                val a = clutch.a
                val b = clutch.b
                val tau = -p.viscousFrictionCoefficient * p.pressure * (a.omega + jacobian * b.omega)
                p.frictionTorque = tau
                a.externalTorque += tau
                b.externalTorque -= tau
            }
            else {
                p.frictionTorque = 0.0
            }
        }

        // Applies friction to individual nodes:
        frictionNodes.forEach { node ->
            node.calculateFrictionTorque()
            node.externalTorque += node.frictionTorque
        }

        // Stores the old angle, updates the inverse inertia and writes the unconstrained (predicted) velocities based on external torque:
        nodes.forEach { node ->
            node.previousAngle = node.angle
            val invI = 1.0 / node.inertia
            inverseI[node.idInOwner] = invI
            omegaStar[node.idInOwner] = node.omega + invI * node.externalTorque * dt
            node.externalTorque = 0.0
        }

        // Sets up rigid constraints.
        // Only calculates the jacobian and the bias. The max lambda is constant.
        rigidConstraints.forEach { rigid ->
            val a = rigid.a
            val b = rigid.b

            val j = -b.ratio / a.ratio
            val posError = a.ratio * a.node.angle - b.ratio * b.node.angle
            val bias = biasFactor / dt * (posError / a.ratio)

            rigidConstraintData.setJacobian(rigid.id, j)
            rigidConstraintData.setBias(rigid.id, bias)
        }

        // Sets up clutch constraints.
        // Calculates the jacobian and bias, the initial guess lambda and the max lambda and finds clutches which are candidates for locking:
        clutchConstraints.forEach { clutch ->
            val a = clutch.a
            val b = clutch.b
            val p = clutch.prototype

            val nodeA = a.idInOwner
            val nodeB = b.idInOwner

            val j = -1.0

            val dTheta = a.angle - b.angle
            val dv = omegaStar[nodeA] + j * omegaStar[nodeB]

            val k = inverseI[nodeA] + inverseI[nodeB] * (j * j)

            val lambdaLimit = p.slipTorqueLimit * p.pressure * dt

            val lambdaNeededOmega = if (abs(k) < SYMFORCE_EPS) 0.0 else -dv / k

            val engageDv = p.dvLock * p.pressure
            val canLockVel = abs(lambdaNeededOmega) <= lambdaLimit + SYMFORCE_EPS && abs(dv) <= engageDv + SYMFORCE_EPS

            val candidateLocked = when {
                p.locked -> true
                p.pressure <= 0.0 -> false
                else -> canLockVel
            }

            clutchCandidates[clutch.id] = candidateLocked

            // If we are about to become candidate-locked (and previously unlocked), set the reference angle,
            // so positional bias is zero for this timestep (no instantaneous snap).
            if (candidateLocked && !p.locked) {
                p.referenceAngle = dTheta
            }

            val posErrorForSolver = if (p.locked) {
                val raw = dTheta - p.referenceAngle
                (raw + PI) % (2.0 * PI) - PI
            } else {
                0.0
            }

            val rawBias = if (posErrorForSolver == 0.0) 0.0 else biasFactor / dt * posErrorForSolver

            val maxBias = if (lambdaLimit > 0.0 && k > SYMFORCE_EPS) k * lambdaLimit else Double.POSITIVE_INFINITY
            val bias = if (maxBias.isFinite()) rawBias.coerceIn(-maxBias, maxBias) else rawBias

            clutchConstraintData.setJacobian(clutch.id, j)
            clutchConstraintData.setBias(clutch.id, bias)

            if (candidateLocked) {
                if (!p.locked) {
                    clutchConstraintLambda.setLambda(clutch.id, lambdaNeededOmega)
                }

                clutchConstraintLambda.setMaxLambda(clutch.id, lambdaLimit)
            } else {
                clutchConstraintLambda.setLambda(clutch.id, 0.0)
                clutchConstraintLambda.setMaxLambda(clutch.id, 0.0)
            }
        }

        // Applies warm start with previous impulses:
        warmStart(rigidConstraintData, rigidConstraintLambda)
        warmStart(clutchConstraintData, clutchConstraintLambda)

        var endIterations = 0
        val error = IterationError()

        val maxIterations = if(firstStep) maxIterationsFirstStep else maxIterations

        var forward = false
        for(iteration in 1..maxIterations) {
            error.begin()

            if(forward) {
                // Forward pass:
                projectedGaussSeidelIteration(rigidConstraintData, rigidConstraintLambda, error, false)
                projectedGaussSeidelIteration(clutchConstraintData, clutchConstraintLambda, error, false)
            }
            else {
                // Backward pass:
                projectedGaussSeidelIteration(clutchConstraintData, clutchConstraintLambda, error, true)
                projectedGaussSeidelIteration(rigidConstraintData, rigidConstraintLambda, error, true)
            }

            if(useSymmetricGaussSeidel) {
                forward = !forward
            }

            endIterations = iteration

            if(iteration >= minIterations) {
                // Exit condition:
                if(error.maxDeltaL < dLEps && error.maxResidual < residualEps) {
                    break
                }
            }
        }

        kineticEnergy = 0.0

        // Integrate for angle and copy back:
        nodes.forEach { node ->
            node.omega = omegaStar[node.idInOwner]
            node.angle += node.omega * dt
            kineticEnergy += node.energy
        }

        // Calculate clutch heating:
        clutchConstraints.forEach { clutch ->
            val p = clutch.prototype
            val a = p.a
            val b = p.b

            val lambda = clutchConstraintLambda.getLambda(clutch.id)
            val jacobian = clutchConstraintData.getJacobian(clutch.id)

            val dThetaA = a.angle - a.previousAngle
            val dThetaB = b.angle - b.previousAngle

            // Work done by the constraint on the nodes:
            val workConstraint = if (dt == 0.0) 0.0 else (lambda / dt) * (dThetaA + jacobian * dThetaB)

            val tau = p.frictionTorque
            val workFriction = tau * dThetaA + (-tau) * dThetaB  // equals tau * (dThetaA - dThetaB)

            // Heat contribution is the mechanical energy removed from the system by the clutch/friction.
            // Constraint heat is not strictly physical, it includes small bias errors and integration errors.
            val heatFromConstraint = if (workConstraint < 0.0) -workConstraint else 0.0
            val heatFromFriction = if (workFriction < 0.0) -workFriction else 0.0

            val totalHeat = heatFromConstraint + heatFromFriction

            p.heat += totalHeat
            p.deltaHeat = totalHeat
        }

        // Copies lambda back to the rigid:
        rigidConstraints.forEach { rigid ->
            rigid.lambda = rigidConstraintLambda.getLambda(rigid.id)
        }

        // Copies lambda back to the clutch prototype and potentially locks/unlocks:
        clutchConstraints.forEach { clutch ->
            val nodeA = clutch.a.idInOwner
            val nodeB = clutch.b.idInOwner
            val p = clutch.prototype

            p.lambda = clutchConstraintLambda.getLambda(clutch.id)

            val lambdaLimit = p.slipTorqueLimit * p.pressure * dt

            // Computes dv after solve to decide velocity unlock:
            val j = clutchConstraintData.getJacobian(clutch.id)
            val dvAfter = omegaStar[nodeA] + j * omegaStar[nodeB]

            val saturated = (p.pressure > 0.0) && (abs(p.lambda) >= (lambdaLimit - SYMFORCE_EPS))
            val velocityUnlock = abs(dvAfter) > (p.dvUnlock * p.pressure) + SYMFORCE_EPS

            if (p.locked && (saturated || velocityUnlock)) {
                // Slipping or too fast. Unlock for next timestep:
                p.locked = false
                clutchConstraintLambda.setLambda(clutch.id, 0.0)
                clutchConstraintLambda.setMaxLambda(clutch.id, 0.0)
            }
            else if (!p.locked && clutchCandidates[clutch.id]) {
                // If we started candidate-locked and final lambda is inside limit, accept the lock:
                if (p.pressure > 0.0 && abs(p.lambda) <= lambdaLimit + SYMFORCE_EPS) {
                    p.locked = true
                }
            }
        }

        // Distribute angles and velocities:
        optimizedShafts.forEach { shaft ->
            shaft.distributeResults()
        }

        // Calculate friction heat for individual nodes:
        frictionNodes.forEach { node ->
            val dTheta = node.angle - node.previousAngle
            val workByFriction = node.frictionTorque * dTheta
            val heatFromNodeFriction = if (workByFriction < 0.0) -workByFriction else 0.0
            node.heatFromFriction += heatFromNodeFriction
            node.deltaHeatFromFriction = heatFromNodeFriction
        }

        // Calculate friction heat for internal shafts inside each optimized LineShaft:
        optimizedShafts.forEach { line ->
            line.lineGraph.forEach { shaft ->
                val dTheta = shaft.angle - shaft.previousAngle
                val workByFriction = shaft.frictionTorque * dTheta
                val heatFromNodeFriction = if (workByFriction < 0.0) -workByFriction else 0.0
                shaft.heatFromFriction += heatFromNodeFriction
                shaft.deltaHeatFromFriction = heatFromNodeFriction
            }
        }

        firstStep = false

        return endIterations
    }

    fun destroy() {
        validateUsage()

        nodes.forEach { node ->
            node.simulationDestroyed()
        }

        destroyed = true
    }
}

interface KineticNodeProxy

/**
 * Represents a homogenous rotating element in the simulation.
 * To connect to other nodes, each node exports one or more [KineticExtension]s.
 * Direct constraints can also be created, but impulse semantics will be different. Used only inside Objects.
 * The backend then resolves pairs of extensions to the corresponding constraint.
 * */
abstract class KineticNode(val allowOptimization: Boolean) {
    private var idInternal = -1
    private var simulatorInternal: KineticSimulation? = null
    private var proxyInternal: KineticNodeProxy? = null

    val isInSimulation get() = idInternal != -1

    /**
     * If [proxy] is null, this is the index in the backing storage of the solver. Otherwise, it's the index in whatever storage [proxy] uses.
     * */
    val idInOwner get() = if(idInternal == -1) error("Cannot get ID before added to simulation") else idInternal
    val simulation get() = simulatorInternal ?: error("Cannot get simulator before added")
    val proxy get() = if(simulatorInternal == null) error("Cannot get proxy before added") else proxyInternal

    val isInProxy get() = proxyInternal != null

    private val constraintsInternal = ArrayList<NodeConstraint<*, *>>()
    val nodeConstraints: List<NodeConstraint<*, *>> get() = constraintsInternal

    /**
     * Gets the impulse applied by [nodeConstraints]. Doesn't include extensions.
     * */
    val nodeConstraintImpulse: Double
        get() {
            var impulse = 0.0

            for (c in constraintsInternal) {
                // c.impulseA/impulseB are expected to be implemented by the constraint
                impulse += if (c.a === this) c.impulseA else c.impulseB
            }

            return impulse
        }

    open fun setSimulation(id: Int, simulation: KineticSimulation, proxy: KineticNodeProxy?) {
        if(isInSimulation) {
            error("Tried to set simulator without destroying old one")
        }

        if(!allowOptimization && proxy != null) {
            error("Tried to set proxy but optimization is now allowed")
        }

        this.idInternal = id
        this.simulatorInternal = simulation
        this.proxyInternal = proxy
    }

    fun addConstraint(constraint: NodeConstraint<*, *>) {
        require(!constraintsInternal.contains(constraint)) {
            "Cannot add to the same constraint"
        }

        constraintsInternal.add(constraint)
    }

    open fun simulationDestroyed() {
        idInternal = -1
        simulatorInternal = null
        proxyInternal = null
        constraintsInternal.clear()
    }

    var angle = 0.0
    var omega = 0.0
    var externalTorque = 0.0

    var inertia: Double = 1.0
        set(value) {
            if(field != value) {
                if(allowOptimization) {
                    if(isInProxy) {
                        error("Changing inertia while optimized is not allowed")
                    }
                }

                field = value
            }
        }

    val energy get() = 0.5 * inertia * (omega * omega)

    var previousAngle = 0.0

    fun setExternalAngle(angle: Double) {
        this.angle = angle
        this.previousAngle = angle
    }
}

/**
 * Node that has some built-in friction.
 * */
abstract class FrictionKineticNode(allowOptimization: Boolean) : KineticNode(allowOptimization) {
    /**
     * Viscous friction(`𝜏 = -[viscousDamping] * ω`).
     * */
    var viscousDamping = 0.0
    /**
     * Sliding friction.
     * */
    var coulombFriction = 0.0
    /**
     * Threshold torque below which the node sticks.
     * */
    var staticFriction = 0.0
    /**
     * If |[omega]| is higher than this, then the node is considered to be moving.
     * */
    var velocityEps = 1e-6

    /**
     * Torque calculated by [calculateFrictionTorque].
     * */
    var frictionTorque = 0.0
    /**
     * Total heat from friction.
     * */
    var heatFromFriction = 0.0
    /**
     * Heat from friction generated this step.
     * */
    var deltaHeatFromFriction = 0.0

    /**
     * Calculates [frictionTorque] based on the parameters and the current angular velocity and external torque.
     * */
    fun calculateFrictionTorque() {
        val viscous = -viscousDamping * omega

        frictionTorque = if (abs(omega) > velocityEps) {
            // Node is moving: kinetic friction opposes velocity:
            -coulombFriction * sign(omega) + viscous
        } else {
            // Node is (nearly) stationary - consider static friction:
            if (abs(externalTorque) <= staticFriction) {
                // Static friction holds: cancel external torque:
                -externalTorque
            } else {
                // Static friction broken, friction opposes the direction of the driving torque:
                -coulombFriction * sign(externalTorque) + viscous
            }
        }
    }
}

/**
 * A two-ended node.
 * It's pretty much the bread and butter of all simulations.
 * */
class KineticShaft(allowOptimization: Boolean = true) : FrictionKineticNode(allowOptimization) {
    val e1 = RigidKineticExtension(this)
    val e2 = RigidKineticExtension(this)

    override fun simulationDestroyed() {
        super.simulationDestroyed()
        e1.simulationDestroyed()
        e2.simulationDestroyed()
    }
}

fun KineticShaft.minus() = this.e1
fun KineticShaft.plus() = this.e2

/**
 * A one-ended node.
 * Useful for e.g. flywheels, and for the gears of a gearbox or the halves of a clutch.
 * */
class KineticMono : FrictionKineticNode(false) {
    val ext = RigidKineticExtension(this)

    override fun simulationDestroyed() {
        super.simulationDestroyed()
        ext.simulationDestroyed()
    }
}

/**
 * Constraint between two [KineticNode]s. Instanced at network build time and thrown away (this class is only a wrapper that adds the [id] used for indexing).
 * The real state is stored in `Prototype`s, which are kept during the lifetime of the simulation object, just like the nodes.
 * */
abstract class NodeConstraint<A : KineticNode, B: KineticNode>(val a: A, val b: B) {
    private var idInternal = -1

    val id get() = if(idInternal == -1) error("Cannot get ID before constraint added to simulation") else idInternal

    val isInSimulation get() = idInternal != -1

    init {
        a.addConstraint(this)
        b.addConstraint(this)
    }

    open fun setSimulation(id: Int) {
        if(isInSimulation) {
            error("Tried to set simulator on one-use constraint")
        }

        this.idInternal = id
    }

    abstract val impulseA: Double
    abstract val impulseB: Double
}

/**
 * State of a clutch constraint.
 * Note that the [pressure] is used to scale the parameters used in the following explanation.
 *
 * When [locked]:
 *  - the clutch is not producing heat through friction, and the two nodes are rigidly constrained.
 *  - if the torque transmitted by the rigid clutch exceeds [slipTorqueLimit] or the velocity difference between the (**rigidly**) locked nodes is larger than [dvUnlock], the clutch unlocks.
 *
 * When not [locked]:
 *  - the clutch is coupling the two nodes through friction ([viscousFrictionCoefficient]), and it is producing [heat]. This torque is stored in [frictionTorque].
 *  - if the torque needed to equalize the velocities is less than [slipTorqueLimit] * [slipTorqueLimitLockFactor], and the velocity difference is less than [dvLock], the clutch attempts to lock.
 *  - if, after solve, all the needed conditions are met, the clutch is locked at the current angle difference.
 *
 *  The implementation is just an approximation. Multiple solve steps would be needed in critical moments if the behavior needed to be tick-perfect, which would be more expensive.
 * */
class ClutchPrototype(val a: KineticNode, val b: KineticNode) {
    /**
     * The torque that can be transmitted at `[pressure] = 1.0`
     * */
    var slipTorqueLimit = 100.0

    /**
     * Scales [slipTorqueLimit] when deciding if the clutch can equalize the velocity difference.
     * */
    var slipTorqueLimitLockFactor = 0.9

    /**
     * Friction at `[pressure] = 1`.
     * */
    var viscousFrictionCoefficient = 10.0

    /**
     * The friction torque calculated by the solver.
     * Only happens when the clutch is not locked.
     * */
    var frictionTorque = 0.0

    /**
     * How pressed the clutch is. `0` means completely free.
     * */
    var pressure = 1.0

    /**
     * The velocity difference threshold for locking.
     * */
    var dvLock = 3.0

    /**
     * The velocity difference threshold for unlocking.
     * */
    var dvUnlock = 0.1

    var lambda = 0.0

    /**
     * Set by the solver. If false, it means that the clutch is grinding, and it's producing [heat] by friction.
     * If true, the clutch acts as a rigid constraint, and [heat] changes are ~0 (some solver error is included too so [heat] changes are not exactly 0).
     * */
    var locked = false

    /**
     * When locked, represents the angle difference between the two nodes.
     * */
    var referenceAngle = 0.0

    /**
     * Total heat produced by the clutch.
     * */
    var heat = 0.0

    /**
     * Heat produced this tick.
     * */
    var deltaHeat = 0.0
}

class ClutchConstraint(val prototype: ClutchPrototype) : NodeConstraint<KineticNode, KineticNode>(prototype.a, prototype.b) {
    override val impulseA: Double
        get() = prototype.lambda

    override val impulseB: Double
        get() = -prototype.lambda // j = -1
}

/**
 * Constraint "between" two [KineticExtension]s of a certain type.
 * The extensions are sorted by type, so only `ExtensionConstraint<LesserPriority, HigherPriority>` and `ExtensionConstraint<SameType, SameType>` need to be implemented.
 * Unlike [NodeConstraint] prototypes, these constraints are generated at network build time and are thrown away.
 * */
abstract class ExtensionConstraint<A : KineticExtension, B : KineticExtension>(val a: A, val b: B) {
    init {
        a.addConstraint(this)
        b.addConstraint(this)
    }

    private var idInternal = -1
    val id get() = if(idInternal == -1) error("Cannot get extension constraint ID before added to simulation") else idInternal

    fun setSimulation(newId: Int) {
        require(idInternal == -1) {
            "Cannot re-add extension constraint"
        }

        idInternal = newId
    }

    /**
     * Gets the impulse applied to [a].
     * */
    abstract val impulseA: Double

    /**
     * Gets the impulse applied to [b].
     * */
    abstract val impulseB: Double

    /**
     * The impulse set by the solver.
     * */
    var lambda = 0.0
}

/**
 * Rigid constraint that applies a gear ratio as well.
 * */
class RigidExtensionConstraint(a: RigidKineticExtension, b: RigidKineticExtension) : ExtensionConstraint<RigidKineticExtension, RigidKineticExtension>(a, b) {
    override val impulseA get() = lambda
    override val impulseB get() = lambda * (-b.ratio / a.ratio)
}

/**
 * An extension is a "semi-constraint" exported by a node. This is made to fit with the object architecture, where each object "exports" some connection to neighbor objects.
 * This lives throughout the lifetime of the [KineticNode] (it is instanced and immutable in the node).
 * Constraints are formed "between" two [KineticExtension]s. The extensions are analyzed and a fitting constraint is created between the nodes, based on the type of both extensions.
 * Example: for a shaft, you have a "left" extension, and a "right" extension. If you build a line of shafts, the right extension of the first shaft is joined with the left extension of the second one; the right extension of the second shaft with the left extension of the third one, and so on.
 * */
abstract class KineticExtension(val node: KineticNode) {
    /**
     * Priority, used for sorting, used for creating the [ExtensionConstraint].
     * This is done so, if you have say extension of type `A` and extension of type `B`, you need only implement `ExtensionConstraint<A, B>` and not `ExtensionConstraint<B, A>` too.
     * */
    abstract val priority: Int

    private val constraintsInternal = ArrayList<ExtensionConstraint<*, *>>()

    /**
     * Gets the actual constraints that were generated which include this extension.
     * */
    val constraints: List<ExtensionConstraint<*, *>> get() = constraintsInternal

    /**
     * Gets the total impulse applied to this extension from the [constraints].
     * */
    val impulse: Double get() {
        var result = 0.0

        constraints.forEach { constraint ->
            result += if(this == constraint.a) constraint.impulseA else constraint.impulseB
        }

        return result
    }

    fun addConstraint(constraint: ExtensionConstraint<*, *>) {
        require(!constraintsInternal.contains(constraint)) {
            "Cannot add to the same constraint"
        }

        constraintsInternal.add(constraint)
    }

    fun simulationDestroyed() {
        constraintsInternal.clear()
    }
}

/**
 * Extension that, when combined with another [RigidKineticExtension], creates a [RigidExtensionConstraint].
 * @param maxLambda The max solver impulse. Setting a bound is useful if destruction is needed (prevents a big jolt being transmitted before the node is destroyed).
 * */
class RigidKineticExtension(node: KineticNode, val maxLambda: Double = Double.POSITIVE_INFINITY) : KineticExtension(node) {
    override val priority: Int
        get() = 0

    var ratio: Double = 1.0
        set(value) {
            if(field != value) {
                if(node.isInProxy) {
                    error("Setting ratio while optimized is not allowed")
                }

                field = value
            }
        }
}

//#endregion

class FramerateIndependentSmoother1d(val tau: Double) {
    var value = 0.0

    private var initialized = false
    private val watch = Stopwatch()

    fun reset() {
        initialized = false
        value = 0.0
    }

    fun update(target: Double) : Double {
        val dt = !watch.sample()

        if(!initialized) {
            value = target
            initialized = true
            return dt
        }

        val alpha = 1.0 - exp(-dt / tau)
        value += (target - value) * alpha

        return dt
    }

    fun pullDown(eps: Double = 1e-6) {
        if(abs(value) < eps) {
            value = 0.0
        }
    }
}

class FramerateIndependentSmoother2d(val tau: Double) {
    var x = 0.0
    var y = 0.0

    private var initialized = false
    private val watch = Stopwatch()

    fun update(targetX: Double, targetY: Double) : Double {
        val dt = !watch.sample()

        if(!initialized) {
            x = targetX
            y = targetY
            initialized = true
            return dt
        }

        val alpha = 1.0 - exp(-dt / tau)
        x += (targetX - x) * alpha
        y += (targetY - y) * alpha

        return dt
    }
}

/**
 * Libage extensions class. Ideally, most things here (but most necessarily, quantities) would be moved to libage.
 * However, changing it and redeploying is slow, so things end up here instead.
 * */

private const val JVM_NAME = "LibageKt"
private const val CLASS_NAME = "org.eln2.mc.$JVM_NAME"

private val SELF by lazy {
    checkNotNull(Class.forName(CLASS_NAME)) {
        "Failed to resolve Libage extensions class $CLASS_NAME"
    }
}

/**
 * Convention for the pin "exported" to other Electrical Objects.
 * */
const val EXTERNAL_PIN: Int = POSITIVE

/**
 * Convention for the pin used "internally" by Electrical Objects.
 * */
const val INTERNAL_PIN: Int = NEGATIVE

data class TermRef(val component: Term, val index: Int)

fun Term.offerPositive() = TermRef(this, POSITIVE)
fun Term.offerNegative() = TermRef(this, NEGATIVE)
fun Term.offerInternal() = TermRef(this, INTERNAL_PIN)
fun Term.offerExternal() = TermRef(this, EXTERNAL_PIN)

fun ElectricalConnectivityMap.join(a: TermRef, b: TermRef) {
    this.connect(a.component, a.index, b.component, b.index)
}

class MyPowerVoltageSource : VoltageSource() {
    companion object {
        private const val EPS = 1e-5
    }

    var potentialMax = 0.0
    var powerIdeal = 0.0

    private fun solve(): Boolean {
        val factor = if(powerIdeal.approxEq(0.0, EPS)) {
            0.0
        } else {
            power / powerIdeal
        }

        if(factor.approxEq(1.0, EPS)){
            return false // close enough
        }

        // We assume a quadratic relationship between the target and the power. This is perfectly true for LTI circuits,
        // but breaks down in the presence of non-linear components in (VERY) potentially-exciting ways, including ways that
        // may prevent convergence. (TODO: account for these cases*)
        var desTarget = if(factor.approxEq(0.0, EPS)) {
            // Degenerate case: power is very small (absolutely) relative to powerIdeal.
            // This usually happens under open-circuit conditions (ELN calls them "highImpedance").
            // In those cases, just float to the maximum target, or zero if we don't have one.
            potentialMax
        } else {
            // Safety: t^2 is always positive, as is abs(factor)
            sqrt(potential * potential / abs(factor))
        }

        desTarget = desTarget.coerceIn(-potentialMax, +potentialMax)
        desTarget = lerp(potential, desTarget, 0.5)

        if(potential.approxEq(desTarget, EPS)) {
            return false // No change in target--usually because we hit AbsMax
        }

        potential = desTarget
        return true
    }

    override fun simStep() {
        solve()
        super.simStep()
    }
}

@Suppress("PrivatePropertyName", "LocalVariableName")
class TheveninEstimatingResistor(
    val lambda: Double = 0.95,
    val alpha: Double = 0.4,
    val ditherFraction: Double = 0.01 / 1000.0,
    val maxSubsteps: Int = 25,
    val minResistanceEstimate: Double = 1e-6,
    val maxResistanceEstimate: Double = LARGE_RESISTANCE,
    val minEffectiveSamples: Double = 5.0,
    val minDeltaICutoff: Double = 1e-6,
    val maxChangeFactorPerStep: Double = 1.2,
) : Resistor() {
    private var SI = 0.0 // Sum I
    private var SV = 0.0 // Sum V
    private var SII = 0.0 // Sum I^2
    private var SIV = 0.0 // Sum I * V
    private var NEff = 0.0 // Effective number of samples

    private var baseResistance = 0.0
    private var previousPotential = 0.0
    private var previousCurrent = 0.0

    var substeps = 0
        private set

    private var ditherSign = 1

    var theveninResistanceEstimate = LARGE_RESISTANCE
    var openCircuitPotentialEstimate = 0.0

    override fun preStep(dt: Double) {
        baseResistance = resistance
        substeps = 0
        ditherSign = 1
    }

    override fun postStep(dt: Double) {
        if (resistance != baseResistance) {
            resistance = baseResistance
        }
    }

    override fun simStep() {
        if (substeps >= maxSubsteps) {
            resistance = baseResistance
            return
        }

        val V = potential
        val I = current

        if (abs(I) > minDeltaICutoff) {
            SI = lambda * SI + I
            SV = lambda * SV + V
            SII = lambda * SII + I * I
            SIV = lambda * SIV + I * V
            NEff = lambda * NEff + 1.0

            val d = (NEff * SII - SI * SI)

            // Compute regression:
            if (NEff >= minEffectiveSamples && abs(d) > 1e-12) {
                val slope = (NEff * SIV - SI * SV) / d // -RTh
                val intercept = (SV - slope * SI) / NEff // OC

                var newRth = -slope
                if (newRth.isFinite() && newRth > 0.0) {
                    newRth = newRth.coerceIn(minResistanceEstimate, maxResistanceEstimate)

                    // Rate limit:
                    val maxUp = theveninResistanceEstimate * maxChangeFactorPerStep
                    val maxDown = theveninResistanceEstimate / maxChangeFactorPerStep
                    newRth = newRth.coerceIn(maxDown, maxUp)

                    // Additional smoothing:
                    theveninResistanceEstimate = if (theveninResistanceEstimate.isFinite()) {
                        alpha * newRth + (1.0 - alpha) * theveninResistanceEstimate
                    } else {
                        newRth
                    }

                    openCircuitPotentialEstimate = intercept
                }
            }
        }

        previousPotential = V
        previousCurrent = I

        perturb()
        substeps++
    }

    private fun perturb() {
        val dithering = 1.0 + ditherSign * ditherFraction
        resistance = (baseResistance * dithering)
        ditherSign *= -1
    }
}

fun TheveninEstimatingResistor.setLoad(power: Double, minResistance: Double = 1e-6, maxResistance: Double = LARGE_RESISTANCE, resistanceEps: Double = 1e-5) : Double {
    require(power >= 0.0) { "TheveninEstimatingResistor#setLoad" }

    if(power.approxEq(0.0)) {
        this.updateResistance(maxResistance, resistanceEps)
        return maxResistance
    }

    val v = this.openCircuitPotentialEstimate

    var loadResistance = (v * v) / power

    if(loadResistance.isNaN() || loadResistance.isInfinite()) {
        loadResistance = 0.0
    }

    loadResistance = loadResistance.coerceIn(minResistance, maxResistance)

    this.updateResistance(loadResistance, resistanceEps)

    return loadResistance
}

/**
 * Helper for displaying the various state variables of components, meant to be used for the jade integration.
 * The jade integration collects data on the server thread, by accessing the simulation cross-thread.
 * This is fine in principle, but the collector will also sometimes collect data during subticks, which will give fluctuating display values.
 * This utility duplicates the state variables for reading by the server thread, and updates them after each simulation step. It is considered acceptable that the reader can see mis-matched values (read while new values were being loaded).
 * Extra data is also derived: [SimulationDisplayer.DisplayThermalMass.thermalPower], [SimulationDisplayer.DisplayThermalMass.temperatureRate].
 * */
interface SimulationDisplayer {
    fun display(thermalMass: ThermalMass) : DisplayThermalMass
    fun display(voltageSource: VoltageSource) : DisplayVoltageSource
    fun display(resistor: IResistor) : DisplayResistor
    fun display(resistor: TheveninEstimatingResistor) : DisplayTheveninResistor
    fun display(powerSource: MyPowerVoltageSource) : DisplayVoltagePowerSource
    fun display(kineticNode: KineticNode) : DisplayKineticNode
    fun remove(source: DisplaySource)

    interface DisplaySource

    interface DisplayThermalMass : DisplaySource {
        val energy: Quantity<Energy>
        val temperature: Quantity<Temperature>
        /**
         * The rate of change of the thermal energy, derived internally.
         * */
        val thermalPower: Quantity<Power>
        /**
         * The rate of change of the temperature, derived internally.
         * */
        val temperatureRate: Quantity<TemperatureRate>
    }

    interface DisplayVoltageSource : DisplaySource {
        val potential: Quantity<Potential>
        val current: Quantity<Current>
        val power: Quantity<Power>
    }

    interface DisplayResistor : DisplaySource{
        val resistance: Quantity<Resistance>
        val current: Quantity<Current>
        val potential: Quantity<Potential>
        val power: Quantity<Power>
    }

    interface DisplayTheveninResistor : DisplaySource {
        val theveninResistance: Quantity<Resistance>
        val openCircuitPotentialEstimate: Quantity<Potential>
        val resistance: Quantity<Resistance>
        val current: Quantity<Current>
        val potential: Quantity<Potential>
        val power: Quantity<Power>
    }

    interface DisplayVoltagePowerSource : DisplaySource {
        val potential: Quantity<Potential>
        val potentialMax: Quantity<Potential>
        val current: Quantity<Current>
        val power: Quantity<Power>
        val powerIdeal: Quantity<Power>
    }

    interface DisplayKineticNode : DisplaySource {
        val angle: Quantity<Angle>
        val angularVelocity: Quantity<AngularVelocity>
        val angularAcceleration: Quantity<AngularAcceleration>
        val inertia: Quantity<Inertia>
        val kineticEnergy: Quantity<Energy>
    }
}

class SimulationDisplayerImpl() : SimulationDisplayer {
    private val implementations = ArrayList<Implementation>()
    private var firstStep = true

    private inline fun<reified Target> Implementation.add() : Target {
        check(!implementations.any { it === this || it.obj === this.obj }) {
            "Duplicate add repository $this"
        }

        implementations.add(this)
        // this.step(-1.0) DANG it grissess, some properties need the pins and doesn't return 0 if they aren't initialized

        return this as Target
    }

    override fun display(thermalMass: ThermalMass) = DisplayThermalMassImpl(thermalMass)
        .add<SimulationDisplayer.DisplayThermalMass>()

    override fun display(voltageSource: VoltageSource) = DisplayVoltageSourceImpl(voltageSource)
        .add<SimulationDisplayer.DisplayVoltageSource>()

    override fun display(resistor: IResistor) = DisplayResistorImpl(resistor)
        .add<SimulationDisplayer.DisplayResistor>()

    override fun display(resistor: TheveninEstimatingResistor) = DisplayTheveninResistorImpl(resistor)
        .add<SimulationDisplayer.DisplayTheveninResistor>()

    override fun display(powerSource: MyPowerVoltageSource) = DisplayVoltagePowerSourceImpl(powerSource)
        .add<SimulationDisplayer.DisplayVoltagePowerSource>()

    override fun display(kineticNode: KineticNode) = DisplayKineticNodeImpl(kineticNode)
        .add<SimulationDisplayer.DisplayKineticNode>()

    fun step(dt: Double) {
        implementations.forEach {
            it.step(if(firstStep) -1.0 else dt)
        }

        if(implementations.size > 25) {
            error(DEBUGGER_BREAK("Dangling displays! ${implementations.size}"))
        }

        firstStep = false
    }

    override fun remove(source: SimulationDisplayer.DisplaySource) {
        check(implementations.remove(source)) {
            DEBUGGER_BREAK("Tried to remove non-added display source $source")
        }
    }

    private interface Implementation : SimulationDisplayer.DisplaySource {
        /**
         * Updates the stored values with the data from the simulation.
         * It is considered acceptable that the reader can see mis-matched values (read while new values were being loaded).
         * The only requirement is that the values are concrete, and not from subticks.
         * @param dt The timestep, used for updating derived quantities. For the first timestep, [dt] is `-1.0`.
         * */
        fun step(dt: Double)

        val obj: Any
    }

    private class DisplayThermalMassImpl(override val obj: ThermalMass) : SimulationDisplayer.DisplayThermalMass, Implementation {
        override var energy = Quantity<Energy>(0.0)
        override var temperature = Quantity<Temperature>(0.0)

        private var previousEnergy = Quantity<Energy>(0.0)
        private var previousTemperature = Quantity<Temperature>(0.0)

        override var thermalPower = Quantity<Power>(0.0)
        override var temperatureRate = Quantity<TemperatureRate>(0.0)

        override fun step(dt: Double) {
            energy = obj.energy
            temperature = obj.temperature

            if(dt != -1.0) {
                thermalPower = Quantity((!energy - !previousEnergy) / dt, WATT)
                temperatureRate = Quantity((!temperature - !previousTemperature) / dt, KELVIN_PER_SECOND)
            }

            previousEnergy = energy
            previousTemperature = temperature
        }

    }

    private class DisplayVoltageSourceImpl(override val obj: VoltageSource) : SimulationDisplayer.DisplayVoltageSource, Implementation {
        override var potential = Quantity<Potential>(0.0)
        override var current = Quantity<Current>(0.0)
        override var power = Quantity<Power>(0.0)

        override fun step(dt: Double) {
            potential = Quantity(obj.potential, VOLT)
            current = Quantity(obj.current, AMPERE)
            power = Quantity(obj.power, WATT)
        }
    }

    private class DisplayResistorImpl(override val obj: IResistor) : SimulationDisplayer.DisplayResistor, Implementation {
        override var resistance = Quantity<Resistance>(0.0)
        override var current = Quantity<Current>(0.0)
        override var potential = Quantity<Potential>(0.0)
        override var power = Quantity<Power>(0.0)

        override fun step(dt: Double) {
            resistance = Quantity(obj.resistance, OHM)
            current = Quantity(obj.current, AMPERE)
            potential = Quantity(obj.potential, VOLT)
            power = Quantity(obj.power, WATT)
        }
    }

    private class DisplayTheveninResistorImpl(override val obj: TheveninEstimatingResistor) : SimulationDisplayer.DisplayTheveninResistor, Implementation {
        override var theveninResistance = Quantity<Resistance>(0.0)
        override var openCircuitPotentialEstimate = Quantity<Potential>(0.0)
        override var resistance = Quantity<Resistance>(0.0)
        override var current = Quantity<Current>(0.0)
        override var potential = Quantity<Potential>(0.0)
        override var power = Quantity<Power>(0.0)

        override fun step(dt: Double) {
            theveninResistance = Quantity(obj.theveninResistanceEstimate, OHM)
            openCircuitPotentialEstimate = Quantity(obj.openCircuitPotentialEstimate, VOLT)
            resistance = Quantity(obj.resistance, OHM)
            current = Quantity(obj.current, AMPERE)
            potential = Quantity(obj.potential, VOLT)
            power = Quantity(obj.power, WATT)
        }
    }

    private class DisplayVoltagePowerSourceImpl(override val obj: MyPowerVoltageSource) : SimulationDisplayer.DisplayVoltagePowerSource, Implementation {
        override var potential = Quantity<Potential>(0.0)
        override var potentialMax = Quantity<Potential>(0.0)
        override var current = Quantity<Current>(0.0)
        override var power = Quantity<Power>(0.0)
        override var powerIdeal = Quantity<Power>(0.0)

        override fun step(dt: Double) {
            potential = Quantity(obj.potential, VOLT)
            potentialMax = Quantity(obj.potentialMax, VOLT)
            current = Quantity(obj.current, AMPERE)
            power = Quantity(obj.power, WATT)
            powerIdeal = Quantity(obj.powerIdeal, WATT)
        }
    }

    private class DisplayKineticNodeImpl(override val obj: KineticNode) : SimulationDisplayer.DisplayKineticNode, Implementation {
        override var angle = Quantity<Angle>(0.0)
        override var angularVelocity = Quantity<AngularVelocity>(0.0)
        var previousAngularVelocity = Quantity<AngularVelocity>(0.0)
        override var angularAcceleration = Quantity<AngularAcceleration>(0.0)
        override var inertia = Quantity<Inertia>(0.0)
        override var kineticEnergy = Quantity<Energy>(0.0)

        override fun step(dt: Double) {
            angle = Quantity(obj.angle, RADIAN)
            angularVelocity = Quantity(obj.omega, RADIAN_PER_SECOND)

            if(dt != -1.0) {
                val dw = angularVelocity - previousAngularVelocity
                angularAcceleration = Quantity(!dw / dt, RADIAN_PER_SECOND2)
            }

            previousAngularVelocity = angularVelocity

            inertia = Quantity(obj.inertia, KILOGRAM_METER2)
            kineticEnergy = Quantity(obj.energy, JOULE)
        }
    }
}

/**
 * Calculates the resistance of a conductor with this electrical resistivity, in the shape of a cylinder of length [L] and cross-sectional area [A].
 * @return The resistance of the conductor.
 * */
@Suppress("LocalVariableName")
fun Quantity<ElectricalResistivity>.cylinderResistance(L: Quantity<Distance>, A: Quantity<Area>) = Quantity((!this * !L) / !A, OHM)

@DimensionClassifier("kg×m²") interface Inertia
val KILOGRAM_METER2 = standardScale<Inertia>()

@DimensionClassifier("Nms") interface ViscousFriction
val NEWTON_METER_SECOND = standardScale<ViscousFriction>()

@DimensionClassifier("rad") interface Angle
val RADIAN = standardScale<Angle>()

@DimensionClassifier("rad/s") interface AngularVelocity
val RADIAN_PER_SECOND = standardScale<AngularVelocity>()

@DimensionClassifier("rad/s²")interface AngularAcceleration
val RADIAN_PER_SECOND2 = standardScale<AngularAcceleration>()

@DimensionClassifier("Nm") interface Torque
val NEWTON_METER = standardScale<Torque>()

@ScaleClassifier("rps")
val REVOLUTION_PER_SECOND = RADIAN_PER_SECOND sourceAmplify 1.0 / 0.1591549430919

@DimensionClassifier("K/s") interface TemperatureRate
val KELVIN_PER_SECOND = standardScale<TemperatureRate>()

// Why is it private in libage? :
internal infix fun <U> SourceQuantityScale<U>.sourceAmplify(amplify: Double) =
    SourceQuantityScale<U>(dimensionType, Scale(scale.factor / amplify, scale.base))

inline fun<reified T> Double.classifyAs(scale: SourceQuantityScale<T>) = Quantity<T>(this, scale).classify()

val ELN2_DIMENSION_TYPES : BiMap<Class<*>, String> = run {
    val additional = run {
        val dimensionTypes = HashSet<Class<*>>()

        SELF.declaredFields.forEach { field ->
            if((QuantityScale::class.java).isAssignableFrom(field.type)) {
                val property = field.kotlinProperty
                    ?: return@forEach

                val scale = checkNotNull(property.getter.call() as? QuantityScale<*>) {
                    "Failed to fetch $property"
                }

                dimensionTypes.add(scale.dimensionType)
            }
        }

        dimensionTypes
    }.associateWithBi { it.sourceName() }

    val map = mutableBiMapOf<Class<*>, String>()

    DIMENSION_TYPES.forward.forEach {
        map.add(it.key, it.value)
    }

    additional.forward.forEach {
        map.add(it.key, it.value)
    }

    map
}

class RotationUpdateProfile2d(val p0: Rotation2d, val v0: Double, val a1: Double, val a2: Double, val duration: Double) {
    var currentTime = 0.0
    val timeRemaining get() = (duration - currentTime).coerceIn(0.0, duration)

    var sampleP = p0
        private set

    var sampleV = v0
        private set

    fun sampleTrajectory() : Double {
        val x = currentTime.coerceIn(0.0, duration)
        val t = duration / 2.0

        return if (x <= t) {
            sampleP = p0 + (v0 * x + 0.5 * a1 * x * x)
            sampleV = v0 + a1 * x
            a1
        }
        else {
            val p1 = p0 + (v0 * t + 0.5 * a1 * t * t)
            val v1 = v0 + a1 * t
            val y = x - t

            sampleP = p1 + (v1 * y + 0.5 * a2 * y * y)
            sampleV = v1 + a2 * y
            a2
        }
    }
}

@Suppress("LocalVariableName")
fun computeRotationUpdateAccelerationProfile(targetPos: Rotation2d, targetVel: Double, sourcePos: Rotation2d, sourceVel: Double, T: Double) : RotationUpdateProfile2d {
    val dp = targetPos - sourcePos
    val dv = targetVel - sourceVel

    val t = T / 2.0
    val t2 = t * t

    val a1 = (dp + targetVel * T) / t2 - (2.0 * sourceVel) / t - dv / T
    val a2 = dv / t - a1

    return RotationUpdateProfile2d(sourcePos, sourceVel, a1, a2, T)
}

fun computeRotationUpdateAccelerationProfileWithAccelerationEstimate(
    accelerationEstimate: Double,
    targetPos: Rotation2d, targetVel: Double,
    sourcePos: Rotation2d, sourceVel: Double,
    maxTransitionTime: Double = 0.25
) : RotationUpdateProfile2d {

    val dv = abs(targetVel - sourceVel)
    val accelEstimate = abs(accelerationEstimate).coerceAtLeast(dv / maxTransitionTime)
    val duration = dv / accelEstimate

    return computeRotationUpdateAccelerationProfile(
        targetPos, targetVel,
        sourcePos, sourceVel,
        duration
    )
}
