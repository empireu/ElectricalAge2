package org.ageseries.libage.sim.electrical

import org.ageseries.libage.data.MutableSetMapMultiMap
import org.ageseries.libage.data.SuperDisjointSet
import org.ageseries.libage.sim.SubSolverSet
import org.ageseries.libage.sim.SubSolverSystemBuilder
import org.ageseries.libage.utils.putUnique

/**
 * Compiler for a single sub solver.
 * This condition imposes that all components are connected.
 *
 * The compiler takes care of:
 *  - Building the data necessary to convert the pin-connections into electrical nodes.
 *  - Optimizing the circuit.
 * */
class ElectricalCircuitCompiler(val subSolverData: ElectricalCircuitForestBuilder.SubSolverData) {
    companion object {
        var USE_VALIDATION = false
    }

    /**
     * Disjoint-set representation for an electrical node (as per Grissess).
     * @param grounded Property that is propagated on union. If one of the pins that is joint is grounded, then the entire node will become ground.
     * */
    class PinDisjointSet(var grounded: Boolean) : SuperDisjointSet<PinDisjointSet>() {
        override fun unite(other: PinDisjointSet) {
            val grounded = representative.grounded || other.representative.grounded

            super.unite(other)

            if(grounded) {
                representative.grounded = true
            }
        }
    }

    /**
     * Builds the **initial** pin forest. Each set contains pins which are all joined.
     * The [PinDisjointSet] propagates whether any of the pins is grounded, which causes the resulting node to be ground.
     * */
    private fun buildPinForest() : MutableSetMapMultiMap<PinDisjointSet, ElectricalPin> {
        /**
         * Creates a disjoint set for each pin that was touched by the builder.
         * This means pins which were involved in connections, or were grounded.
         * If the pin is grounded, [PinDisjointSet.grounded] is set to true.
         * */
        val disjointSets = HashMap<ElectricalPin, PinDisjointSet>()

        /**
         * Build nodes for floating pins as well.
         * This is necessary, unfortunately. I can explain more if you ask me and I remember why.
         * The nodes that are created for unconnected nodes are called **Synthetic Nodes**.
         * */
        subSolverData.nodes.forEach { node ->
            node.allPins.forEach { pin ->
                disjointSets.putUnique(pin, PinDisjointSet(false))
            }
        }

        /**
         * Apply groundings:
         * */
        subSolverData.groundings.forEach { pin ->
            val set = disjointSets[pin]
                ?: error("Found a grounding, but the disjoint set for the pin wasn't created!")

            set.grounded = true
        }

        /**
         * Unites all the disjoint sets, propagating [PinDisjointSet.grounded] to the representative:
         * */
        subSolverData.connections.forEach { (pinA, pinB) ->
            val setA = disjointSets[pinA]!!
            val setB = disjointSets[pinB]!!
            setA.unite(setB)
        }

        /**
         * Builds the pin forest:
         * */
        val forest = MutableSetMapMultiMap<PinDisjointSet, ElectricalPin>()

        disjointSets.forEach { (pin, set) ->
            val representative = set.representative
            forest[representative].add(pin)
        }

        // Reference node must exist:
        check(forest.map.keys.any { it.grounded }) {
            "Did not find reference node at forest stage!"
        }

        return forest
    }

    /**
     * Represents a fully assembled circuit description.
     * Operations can be done on this to optimize it.
     * After an optimization run, the circuit data must still be complete and ready to simulate.
     * @param forest The remaining nodes (which can be modified from the original). Doesn't include nodes that were optimized away.
     * @param components The remaining components (plus any new components). Doesn't include components that were optimized away.
     * */
    class MutableCircuitData(val forest: MutableSetMapMultiMap<PinDisjointSet, ElectricalPin>, val components: HashSet<ElectricalComponent>)

    /**
     * Optimizes series resistors into one single resistor.
     * It is designed to be invisible to downstream code by correctly preserving the potential and current values of the optimized-away resistors.
     * Breakdown of the process:
     *  - Identification: The **internal nodes** are found. __Def:__ An internal node connects exactly two pins; the two pins belong to a [Resistor]; the node is not grounded.
     *
     *  - Graph Traversal: Starting from these internal nodes, the networks starting at each of the two pins are explored. Traverses the resistor chain in both directions. It assembles a list of resistors in a series "line".
     *  It discards any chains that form a loop or another configuration that cannot be optimized as a simple series.
     *
     *  - Replacement: For each valid line, a [ResistorSystem] is created. This carefully re-wires the circuit, by removing the resistors in the line from the simulation, and detaching the "external pins" (the ones at the very ends of the chain). The pins of the system are then attached in their stead.
     * */
    class LineOptimizer(val circuitData: MutableCircuitData) {
        /**
         * The nodes for each pin.
         * */
        val nodesByPin = HashMap<ElectricalPin, PinDisjointSet>()

        /**
         * All internal nodes found by [gatherInternalNodes].
         * These nodes are on the interior of each line. For a line of `N` resistors, there are `N - 1` internal nodes.
         * */
        private val internalNodes = HashMap<PinDisjointSet, MutableSet<ElectricalPin>>()

        /**
         * Line graph with associated information.
         * @param resistors The resistors, in order (see below). The first and last resistor are connected to the external circuit.
         * @param internalNodes The internal nodes (`N - 1`).
         * */
        class ProtoLineGraph(val resistors: List<Resistor>, val internalNodes: List<PinDisjointSet>)

        /**
         * Holds all the found line graphs, by [isolateProtoLineGraphs].
         * Each line graph is in *some order*, meaning the first resistor is connected to the second, the second to the third, and so on.
         * The first and last resistors are end points. One of their pins will be connected to internal resistors in the graph, and the other pins will be connected to the external circuit, or not connected.
         * Evidently, each line graph contains at least 2 resistors (the starting point was a node with the internal node condition: that is, the node joins 2 resistors).
         * */
        private val protoLineGraphs = ArrayList<ProtoLineGraph>()

        /**
         * Gathers the nodes which fit the condition to be internal nodes of a line graph.
         * The condition is simply: the node must connect exactly 2 resistors, and it is also not ground.
         * */
        private fun gatherInternalNodes() {
            circuitData.forest.map.forEach { (disjointSet, pins) ->
                if(pins.size != 2) {
                    return@forEach
                }

                if(!pins.all { it.component is Resistor }) {
                    return@forEach
                }

                if(disjointSet.grounded) {
                    return@forEach
                }

                this@LineOptimizer.internalNodes.putUnique(disjointSet.representative, pins)
            }
        }

        /**
         * Based on the [internalNodes], isolates all the line graphs.
         * The line graph contain 2 endpoint resistors, and 0 or more internal resistors.
         * */
        private fun isolateProtoLineGraphs() {
            val candidateInternalNodes = internalNodes.keys.toHashSet()

            val visitedNodes = HashSet<PinDisjointSet>()
            val visitedResistors = HashSet<Resistor>()

            while (candidateInternalNodes.isNotEmpty()) {
                /**
                 * This gives us a random internal node in a **candidate** line graph.
                 * By following the two resistors connected to this node, we can discover the line graph.
                 * If the line graph forms a loop, then we must discard it.
                 * Optimizing that graph would mean creating a resistor whose pins are connected to each other, which is illegal.
                 * */
                val anchorNode = candidateInternalNodes.first()
                candidateInternalNodes.remove(anchorNode)

                // Choose one random resistor to travel toward.
                // We will call the two resistors "left" and "right" for simplicity.
                // We will follow the left and right resistors of the anchor.
                // If the graph forms a cycle, we should encounter the anchor node again, upon exploring any of the two pins.

                // Holds all the resistors and nodes in the graph, in order.
                val resistors = ArrayList<Resistor>()
                val nodes = ArrayList<PinDisjointSet>()

                visitedNodes.clear()
                visitedResistors.clear()

                var foundCycle = false
                for ((index, anchorPin) in internalNodes[anchorNode]!!.withIndex()) {
                    var currentPin = anchorPin

                    while (true) {
                        val resistor = currentPin.component as Resistor

                        if (!visitedResistors.add(resistor)) {
                            foundCycle = true
                            break
                        }

                        resistors.add(resistor)

                        /**
                         * Gets the other pin of the resistor.
                         * We will keep traveling, starting from this pin.
                         * This basically moves us across the resistor.
                         * */
                        val oppositePin = if(resistor.positive == currentPin) {
                            resistor.negative
                        } else{
                            resistor.positive
                        }

                        // Gets the node for the pin, if it exists:
                        val nodeForOppositePin = nodesByPin[oppositePin]
                            ?: break // Circuit ends.

                        if(nodeForOppositePin == anchorNode || !visitedNodes.add(nodeForOppositePin)) {
                            // Found cycle.
                            foundCycle = true
                            break
                        }

                        /**
                         * Two options:
                         * - The node is another internal node of this line.
                         * - The node connects to the external circuit.
                         * */
                        val nextPins = internalNodes[nodeForOppositePin]
                            ?: break // Endpoint: connects to the external circuit.

                        nodes.add(nodeForOppositePin.representative)

                        if(!candidateInternalNodes.remove(nodeForOppositePin)) {
                            // If it was already removed, either we hit a previously processed node, or the graph is not a simple path.
                            // Treat as cycle.
                            foundCycle = true
                            break
                        }

                        // Keep traveling:
                        currentPin = nextPins.first { it != oppositePin }
                    }

                    if(foundCycle) {
                        break
                    }

                    // By convention, one of the traversal orders is reversed:
                    // Take that to be 0:
                    if(index == 0) {
                        resistors.reverse()
                        nodes.reverse()
                        nodes.add(anchorNode)
                    }
                }

                if(foundCycle) {
                    continue // Discard results
                }

                if(USE_VALIDATION) {
                    // Verify traversal order and some conditions.
                    check(nodes.size == resistors.size - 1)

                    /**
                     * Makes sure exactly one node is an internal node.
                     * */
                    fun validateEdge(resistor: Resistor) {
                        val n1 = nodesByPin[resistor.positive]
                        val n2 = nodesByPin[resistor.negative]

                        var count = 0

                        if(n1 != null && internalNodes.contains(n1)) {
                            count++
                        }

                        if(n2 != null && internalNodes.contains(n2)) {
                            count++
                        }

                        check(count == 1) {
                            "Edge validation failed: found $count internal nodes instead of exactly 1."
                        }
                    }

                    validateEdge(resistors.first())
                    validateEdge(resistors.last())

                    // Check traversal:

                    // Forward consistency:
                    for (i in 0 until resistors.size - 1) {
                        val a = resistors[i]
                        val b = resistors[i + 1]

                        val node = circuitData.forest.map[nodes[i]]!!
                        check(node.any { it.component == a })
                        check(node.any { it.component == b })
                        check(node.size == 2)
                    }
                }

                // Record valid graph:
                protoLineGraphs.add(ProtoLineGraph(resistors, nodes))
            }
        }

        /**
         * Holds all the systems that were generated.
         * */
        val generatedSystems = ArrayList<ResistorSystem>()

        /**
         * Generates all the systems for each graph in [protoLineGraphs]:
         * - The resistors are removed from the component set, and their connections are removed too.
         * - The systems are added to the component sets and the endpoint connections are made with the new systems.
         * */
        fun generateSystems() {
            /**
             * The re-writing logic is in the system's constructor. It needs its pins to rewrite the connection.
             * */
            protoLineGraphs.forEach { graph ->
                val system = ResistorSystem(graph, this)
                generatedSystems.add(system)
            }
        }

        /**
         * Performs line optimization on [circuitData].
         * */
        fun execute() {
            /**
             * Maps the pin to each node.
             * */
            circuitData.forest.map.forEach { (disjointSet, pins) ->
                pins.forEach { pin ->
                    nodesByPin.putUnique(pin, disjointSet)
                }
            }

            /**
             * Finds the internal nodes of all lines.
             * They are a good starting point for isolating line graphs.
             * Results are in [internalNodes].
             * */
            gatherInternalNodes()

            /**
             * Isolates the line graph as a list of resistors.
             * Results are in [protoLineGraphs].
             * */
            isolateProtoLineGraphs()

            /**
             * Finally creates the optimized systems.
             * */
            generateSystems()
        }
    }

    /**
     * Compiles the circuit.
     * This is done in 3 stages:
     * - 1. The pin forest is built. A pin star is the precursor to the [ElectricalNode]. The forest is the set of all stars. All electrical nodes form a connected graph, with the edges being components.
     * - 2. Optimizers run and modify the node set and pin forest.
     * - 3. The [ElectricalSimulation] is constructed. It forms its own internal node representation and creates its system.
     * */
    fun compile(dt: Double, useLineOptimization: Boolean, constructionOptions: ElectricalSimulation.ConstructionOptions) : ElectricalSimulation {
        val pinForest = buildPinForest()
        val nodes = subSolverData.nodes.toHashSet()

        val circuitData = MutableCircuitData(pinForest, nodes)

        if(useLineOptimization) {
            val lineOptimizer = LineOptimizer(circuitData)
            lineOptimizer.execute()
        }

        return ElectricalSimulation(
            dt,
            circuitData.components.toTypedArray(),
            constructionOptions,
            circuitData.forest.map,
        )
    }
}

/**
 * Set of all electrical components, used for separating the building process into stages.
 * */
interface ElectricalComponentSet {
    /**
     * Adds the component to the circuit.
     * */
    fun add(component: ElectricalComponent)

    /**
     * Adds the components to the circuit.
     * */
    fun add(vararg components: ElectricalComponent)
}

/**
 * Set of all electrical connections, used for separating the building process into stages.
 * */
interface ElectricalConnectivityMap {
    /**
     * Connects the two electrical pins.
     * It is illegal to join the pins of the same component!
     * */
    fun join(a: ElectricalPin, b: ElectricalPin)

    /**
     * Connects all the electrical pins.
     * It is illegal to join the pins of the same component!
     * */
    fun join(vararg pins: ElectricalPin) {
        val representative = pins[0]

        for (i in 1 until pins.size) {
            join(representative, pins[i])
        }
    }

    /**
     * Grounds the specified pin.
     * */
    fun ground(pin: ElectricalPin)

    /**
     * Grounds the specified pins.
     * */
    fun ground(vararg pins: ElectricalPin) {
        pins.forEach {
            ground(it)
        }
    }
}

/**
 * Main builder for a **Electrical Circuit Forest**.
 * Building is meant to be separated into discrete stages using the [ElectricalComponentSet] and [ElectricalConnectivityMap].
 * */
class ElectricalCircuitForestBuilder : ElectricalComponentSet, ElectricalConnectivityMap {
    class SubSolverData : SubSolverSystemBuilder.PerSubSolverData<SubSolverData, ElectricalComponent>() {
        val connections = ArrayList<Connection>()
        // Pins involved in connections. Doesn't include e.g. pins that are not connected to anything, but they are grounded.
        val pinsInConnections = LinkedHashSet<ElectricalPin>()
        val groundings = HashSet<ElectricalPin>()

        data class Connection(val a: ElectricalPin, val b: ElectricalPin)

        fun connect(a: ElectricalPin, b: ElectricalPin) {
            pinsInConnections.add(a)
            pinsInConnections.add(b)
            connections.add(Connection(a, b))
        }

        fun ground(pin: ElectricalPin) {
            groundings.add(pin)
        }

        override fun copyFrom(other: SubSolverData) {
            super.copyFrom(other)

            connections.addAll(other.connections)
            pinsInConnections.addAll(other.pinsInConnections)
            groundings.addAll(other.groundings)
        }

        override fun recycle() {
            super.recycle()

            connections.clear()
            pinsInConnections.clear()
            groundings.clear()
        }
    }

    private val joint = HashSet<Pair<ElectricalPin, ElectricalPin>>()
    private val builder = SubSolverSystemBuilder<ElectricalComponent, SubSolverData> {
        SubSolverData()
    }
    private var built = false

    private fun validateUsage() {
        require(!built) {
            "Cannot re-use electrical simulation builder"
        }
    }

    /**
     * Adds a component to the builder. This must be called before any connections can be made with the pins of the component.
     * */
    override fun add(component: ElectricalComponent) {
        validateUsage()
        builder.addNode(component)
    }

    /**
     * Adds multiple components to the builder. This must be called before any connections can be made with the pins of the component.
     * */
    override fun add(vararg components: ElectricalComponent) {
        validateUsage()
        components.forEach {
            add(it)
        }
    }

    /**
     * Makes a connection between the two pins. The components that own these pins must be already added.
     * */
    override fun join(a: ElectricalPin, b: ElectricalPin) {
        validateUsage()

        if(a.component === b.component) {
            error("Cannot connect pins $a and $b (they belong to the same component)!")
        }

        if(!joint.add(Pair(a, b)) || !joint.add(Pair(b, a))) {
            return
        }

        builder
            .unite(a.component, b.component)
            .connect(a, b)
    }

    /**
     * Grounds the specified pin. This turns the whole node where [pin] is involved into the ground node.
     * */
    override fun ground(pin: ElectricalPin) {
        validateUsage()
        builder
            .getSubSolverOf(pin.component)
            .ground(pin)
    }

    /**
     * Used to select a ground node for each sub solver if a node isn't already grounded.
     * */
    private fun selectReferenceNodes() {
        // If no node is grounded, we select any node to be the reference:
        builder.subSolvers.forEach { subSolver ->
            if(subSolver.groundings.isEmpty()) {
                // Prefer not selecting resistors, so we don't break a line optimization somewhere:
                var referenceNode = subSolver.nodes.firstOrNull { it is Port && it !is Resistor }

                if(referenceNode == null) {
                    referenceNode = subSolver.nodes.firstOrNull { it is Port }
                }

                check(referenceNode != null) {
                    "Could not find a known node to select as reference"
                }

                // Arbitrary choice:
                ground((referenceNode as Port).negative)
            }
        }
    }

    /**
     * Builds all sub solvers. It also ensures each sub solver has a chosen reference node.
     * */
    fun build(dt: Double, useLineOptimization: Boolean, constructionOptions: ElectricalSimulation.ConstructionOptions) = SubSolverSet(run {
        validateUsage()
        selectReferenceNodes()

        builder.subSolvers.map { subSolver ->
            val compiler = ElectricalCircuitCompiler(subSolver)
            compiler.compile(dt, useLineOptimization, constructionOptions)
        }
    })
}