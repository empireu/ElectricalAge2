package org.eln2.mc

import org.ageseries.libage.data.MutableSetMapMultiMap
import org.ageseries.libage.sim.electrical.mna.Circuit
import org.ageseries.libage.sim.electrical.mna.component.*
import org.eln2.mc.common.cells.foundation.ComponentHolder
import org.eln2.mc.common.cells.foundation.NEGATIVE_PIN
import org.eln2.mc.common.cells.foundation.POSITIVE_PIN
import org.eln2.mc.data.SuperDisjointSet

const val LARGE_RESISTANCE = 1e9

/**
 * Represents a virtual electrical component, where the term "virtual" denotes the possibility that it may or may not manifest as an actual [Component] incorporated into the circuit.
 * */
interface VirtualComponent : Term

/**
 * Represents a set of electrical components. The components are either real [Component]s or [VirtualComponent]s.
 * */
interface ElectricalComponentSet {
    /**
     * Adds the real component to the underlying circuit.
     * @return True if the component was added. Otherwise, false (the component was already added).
     * */
    fun add(component: Component) : Boolean

    /**
     * Adds the virtual component to the underlying compiler.
     * @return True if the component was added. Otherwise, false (the component was already added).
     * */
    fun add(component: VirtualComponent) : Boolean

    // To remove
    fun <T> add(holder: ComponentHolder<T>) : Boolean where T : Component

    /**
     * Adds the [Term] ([Component] or [VirtualComponent]) to the underlying data structure.
     * @return True if the term was added. Otherwise, false (the term was already added).
     * */
    fun add(component: Term) : Boolean
}

/**
 * Represent a set of edges (connections) between [Component]s and other [Component]s, [VirtualComponent]s and other [VirtualComponent]s and [Component]s and [VirtualComponent]s.
 * */
interface ElectricalConnectivityMap {
    /**
     * Connects the *[aIdx]ᵗʰ* pin of virtual component [a] to the *[bIdx]ᵗʰ* pin of virtual component [b].
     * This connection shall be denoted as *virtual-to-virtual* connection.
     * */
    fun connect(a: VirtualComponent, aIdx: Int, b: VirtualComponent, bIdx: Int)

    /**
     * Connects the *[aIdx]ᵗʰ* pin of real component [a] to the *[bIdx]ᵗʰ* pin of virtual component [b].
     * This connection shall be denoted as *virtual-to-real* connection.
     * */
    fun connect(a: Component, aIdx: Int, b: VirtualComponent, bIdx: Int)

    /**
     * Connects the *[aIdx]ᵗʰ* pin of virtual component [a] to the *[bIdx]ᵗʰ* pin of real component [b].
     * This connection is denoted as a *virtual-to-real* connection.
     * */
    fun connect(a: VirtualComponent, aIdx: Int, b: Component, bIdx: Int)

    /**
     * Connects the *[aIdx]ᵗʰ* pin of real component [a] to the *[bIdx]ᵗʰ* pin of real component [b].
     * This connection isn't indicated differently in the documentation.
     * The final outcome is the same as using **libage's** connection API, except that validation is performed (take notes, Grissess, don't just return if the component is not added, **throw an exception!**)
     * */
    fun connect(a: Component, aIdx: Int, b: Component, bIdx: Int)

    /**
     * Connects the *[aIdx]ᵗʰ* pin of underlying component of [a] to the *[bIdx]ᵗʰ* pin of underlying component of [b].
     * This is equivalent to calling the other connection methods if the concrete types of [a] and [b] were known.
     * */
    fun connect(a: Term, aIdx: Int, b: Term, bIdx: Int)
}

class LineCompiler(private val circuitBuilder: CircuitBuilder) {
    private class Pin : SuperDisjointSet<Pin>() {
        private var hasReals = false

        /**
         * Indicates veracity when considering whether this pin functions as a "break-point," signifying its shared status with real components or its failure to precisely connect two components.
         * We shall consider all virtual resistors that share this pin to be **outer**s.
         * */
        val isBreakPoint: Boolean get() {
            val representative = representative

            return representative.hasReals || representative.size != 2
        }

        fun markReal() {
            representative.hasReals = true
        }

        override fun unite(other: Pin) {
            val hasReals = this.representative.hasReals || other.representative.hasReals

            super.unite(other)

            if(hasReals) {
                markReal()
            }
        }
    }

    private val virtualResistors = HashMap<VirtualResistor, Pair<Pin, Pin>>()
    private val virtualToRealConnections = MutableSetMapMultiMap<VirtualResistor, VirtualToRealInfo>()
    private val virtualToVirtualConnections = MutableSetMapMultiMap<VirtualResistor, VirtualToVirtualInfo>()

    fun addResistor(resistor: VirtualResistor) : Boolean {
        if(virtualResistors.containsKey(resistor)) {
            return false
        }

        virtualResistors.putUnique(resistor, Pair(Pin(), Pin()))

        return true
    }

    private fun validatePinIndex(pin: Int) {
        require(pin == POSITIVE_PIN || pin == NEGATIVE_PIN) {
            "Pin $pin is not valid"
        }
    }

    fun connect(a: Component, aIdx: Int, b: VirtualResistor, bIdx: Int) {
        validatePinIndex(bIdx)

        val (p, n) = virtualResistors[b].requireNotNull { "$b is not added" }

        virtualToRealConnections[b].add(VirtualToRealInfo(a, aIdx, bIdx))

        if(bIdx == POSITIVE_PIN) {
            p.markReal()
        }
        else {
            n.markReal()
        }
    }

    fun connect(a: VirtualResistor, aIdx: Int, b: VirtualResistor, bIdx: Int) {
        validatePinIndex(aIdx)
        validatePinIndex(bIdx)

        val (aP, aN) = virtualResistors[a].requireNotNull { "$a is not added" }
        val (bP, bN) = virtualResistors[b].requireNotNull { "$b is not added" }

        virtualToVirtualConnections[a].add(VirtualToVirtualInfo(b, bIdx, aIdx))
        virtualToVirtualConnections[b].add(VirtualToVirtualInfo(a, aIdx, bIdx))

        if(aIdx == POSITIVE_PIN && bIdx == POSITIVE_PIN) {
            aP.unite(bP)
        }
        else if(aIdx == POSITIVE_PIN && bIdx == NEGATIVE_PIN) {
            aP.unite(bN)
        }
        else if(aIdx == NEGATIVE_PIN && bIdx == POSITIVE_PIN) {
            aN.unite(bP)
        }
        else if(aIdx == NEGATIVE_PIN && bIdx == NEGATIVE_PIN) {
            aN.unite(bN)
        }
    }

    private fun getCandidate(visited: HashSet<VirtualResistor>, start: VirtualResistor, pins: Pair<Pin, Pin>) : VirtualResistor? {
        val pBreak = pins.first.isBreakPoint
        val nBreak = pins.second.isBreakPoint

        if(pBreak && nBreak) {
            // We conclude that there are no candidates.
            return null
        }

        // If both pins are valid, then [start] is playing hide and seek as a valid endpoint,
        // and we've accidentally stumbled into the comedy section of the algorithm.
        require(pBreak || nBreak) {
            "Catastrophic blunder: both candidates are valid"
        }

        val connections = virtualToVirtualConnections[start]

        val candidate = if(!pBreak) {
            connections.firstOrNull { it.localPin == POSITIVE_PIN }?.remote
        }
        else {
            connections.firstOrNull { it.localPin == NEGATIVE_PIN }?.remote
        }

        require(candidate != null) {
            "Candidate expected"
        }

        require(!visited.contains(candidate)) {
            "Candidate is already visited"
        }

        return candidate
    }

    /**
     * Closes the line. The next resistor (could be inner or endpoint) is expected to be present in [queue].
     *
     * The algorithm employs a basic graph search. It terminates early under a specific condition:
     * If a resistor from the [outers] set is encountered, it signifies the discovery of the other endpoint, concluding the search.
     * */
    private fun closeLine(queue: ArrayDeque<VirtualResistor>, visited: HashSet<VirtualResistor>, outers: HashSet<VirtualResistor>, graph: LineGraph) {
        while (queue.isNotEmpty()) {
            val front = queue.removeFirst()

            if(!visited.add(front)) {
                continue
            }

            if(outers.remove(front)) {
                graph.addOuter(front)
                break
            }

            graph.addInner(front)

            val lineConnections = virtualToVirtualConnections[front]

            require(lineConnections.size == 2) {
                "Invalid inner"
            }

            // Validation:

            require(!virtualToRealConnections.contains(front))

            lineConnections.forEach {
                queue.add(it.remote)
            }
        }
    }

    /**
     * Retrieves all line graphs. A line graph consists of:
     *  - One or two end points (outers): Virtual resistors that serve as the two ends of the actual [Line] resistor. They connect to other non-line components and may be the first or last resistors in a line (with 0 or 1 connections).
     *  - Zero or multiple inner parts (inners): Virtual resistors with two connections to other virtual resistors in their line graph. These do not transform into real [Line] resistors later on.
     * */
    private fun getLineGraphs() : List<LineGraph> {
        val outers = HashSet<VirtualResistor>()

        virtualResistors.forEach { (resistor, pins) ->
            if(pins.first.isBreakPoint || pins.second.isBreakPoint) {
                outers.add(resistor)
            }
        }

        val results = ArrayList<LineGraph>()
        val visited = HashSet<VirtualResistor>()
        val queue = ArrayDeque<VirtualResistor>()

        while (outers.isNotEmpty()) {
            val graph = LineGraph()

            val start = outers.removeFirst()
            val pins = virtualResistors[start]!!

            graph.addOuter(start)
            visited.addUnique(start)

            val candidate = getCandidate(visited, start, pins)

            if(candidate != null) {
                graph.anchor = candidate
                queue.add(candidate)
                closeLine(queue, visited, outers, graph)
                queue.clear()
            }

            results.add(graph)
        }

        return results
    }

    /**
     * Compiles all lines.
     * - Line graphs are retrieved by [getLineGraphs]
     * - Connections between endpoints are resolved as connections between the [Line]s that were generated
     * - Connections between endpoints and real components are also resolved in the same manner
     * */
    fun compile() {
        if(virtualResistors.isEmpty()) {
            return
        }

        val graphs = getLineGraphs()

        /**
         * Keys represent endpoint virtual resistors (denoted as K).
         * The values consist of maps ("connectivityMap") corresponding to remote virtual resistors (all connected to K, referred to as R).
         * Each map indicates the [Line] K is part of (for quick retrieval), and the specific pin of that [Line] where R is to connect with K.
         * */
        val connections = HashMap<VirtualResistor, HashMap<VirtualResistor, Pair<Line, Int>>>()

        // An arbitrary pin number:
        var pin = 0

        graphs.forEach { graph ->
            val line = graph.line

            circuitBuilder.add(line)

            graph.forEachOrdered { resistor ->
                val part = line.add(line.size);

                resistor.setHandle(part)
            }

            if(graph.size == 1) {
                // Single resistor. The virtual pins will be the same as the pins used by the real pins:
                require(graph.outers.size == 1)
                require(graph.inners.size == 0)

                val outer = graph.outers.first()

                virtualToRealConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                    circuitBuilder.connect(line, localIdx, remote, remoteIdx)
                }

                val connectivityMap = HashMap<VirtualResistor, Pair<Line, Int>>(2)

                // There may be some imaginary resistors connected - they just didn't fit the line graph invariant, so they are not included in this graph
                virtualToVirtualConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                    connectivityMap.putUnique(remote, Pair(line, localIdx))
                }

                connections.putUnique(outer, connectivityMap)
            }
            else {
                // A line of resistors. We will assign the pins arbitrarily.

                check(graph.outers.size == 2) { "Expected 2 outers " }

                graph.outers.forEach { outer ->
                    val connectivityMap = HashMap<VirtualResistor, Pair<Line, Int>>(2)

                    val arbitraryPin = (pin++) % 2

                    // This time, the outer resistor's real connections will all receive the same arbitrary pin of the Line.
                    virtualToRealConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                        circuitBuilder.connect(line, arbitraryPin, remote, remoteIdx)
                    }

                    // Other imaginary connections will use the arbitrary pin to connect to our Line:
                    virtualToVirtualConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                        connectivityMap.putUnique(remote, Pair(line, arbitraryPin))
                    }

                    connections.putUnique(outer, connectivityMap)
                }
            }
        }

        /**
         * Resolve virtual connections, which will become real connections between Line components.
         * These Line components are generated here; they stem from connected components of virtual resistors that require multiple real [Line] resistors for representation.
         * There are some remaining line-to-line connections, originating from connections between elements within the same lines (inner to inner and inner to end point).
         * We only need to address the subset of these connections occurring between the endpoints (outers) of different lines.
         * */
        graphs.forEach { graph ->
            graph.outers.forEach { outer ->
                for (connection in virtualToVirtualConnections[outer]) {
                    if(graph.contains(connection.remote)) {
                        // Residual connection to a component in the graph
                        // Those will not materialize into real connections.
                        continue
                    }

                    // This will contain the pin of the remote line that we need to connect to.
                    // *[connection.remote] is K
                    val remoteEdgeMap = connections[connection.remote].requireNotNull {
                        "Did not have remote edge map"
                    }

                    // *[outer] is R
                    val (remoteLine, remotePin) = remoteEdgeMap[outer].requireNotNull {
                        "Did not have remote virtual edge"
                    }

                    // This will contain the pin of our line that we need to connect with the remote line.
                    // *[outer] is K
                    val localEdgeMap = connections[outer].requireNotNull {
                        "Did not have local edge map"
                    }

                    // *[connection.remote] is R
                    val (localLine, localPin) = localEdgeMap[connection.remote].requireNotNull {
                        "Did not have local imaginary edge"
                    }

                    check(graph.line == localLine) { "Line was not expected one " }
                    check(localLine != remoteLine) { "Local line is remote line" }

                    // Finally, this real connection will connect together the two Line components:
                    circuitBuilder.connect(localLine, localPin, remoteLine, remotePin)
                }
            }
        }
    }

    /**
     * @param remote The other real [Component].
     * @param remotePin The pin of [remote] where the connection will occur.
     * @param localPin The pin of the virtual resistor to connect to [remote]'s [remotePin].
     * */
    private data class VirtualToRealInfo(val remote: Component, val remotePin: Int, val localPin: Int)

    /**
     * @param remote The other virtual resistor.
     * @param remotePin The pin of [remote] where the connection will occur.
     * @param localPin The pin of the virtual resistor to connect to [remote]'s [remotePin].
     * */
    private data class VirtualToVirtualInfo(val remote: VirtualResistor, val remotePin: Int, val localPin: Int)

    private class LineGraph {
        val line = Line()

        val size get() = inners.size + outers.size

        fun contains(resistor: VirtualResistor) = outers.contains(resistor) || inners.contains(resistor)

        /**
         * Iterates over the resistors in order:
         *  1. First outer
         *  2. Anchor and the rest of the inners
         *  3. Second outer
         * */
        inline fun forEachOrdered(action: (VirtualResistor) -> Unit) {
            if(outers.size == 1) {
                // Single graph with one resistor
                check(inners.isEmpty()) { "Outers size 1 had inners" }
                check(anchor == null) { "Outers size 1 had anchor" }
                action(outers.first())
            }
            else {
                check(outers.size == 2) { "Outers size is ${outers.size}" }

                val iterator = outers.iterator()

                action(iterator.next())

                if(inners.isNotEmpty()) {
                    check(inners.first() == anchor) {
                        "Expected first inner to be the anchor"
                    }

                    inners.forEach(action)
                }

                action(iterator.next())
            }
        }

        // Linked hash set - order is preserved:

        /**
         * Gets all inner resistors in the cluster.
         * */
        val inners = LinkedHashSet<VirtualResistor>()

        /**
         * Gets all end point resistors in the graph.
         * */
        val outers = LinkedHashSet<VirtualResistor>()

        /**
         * Gets or sets the anchor - the resistor candidate that started the graph and is connected to the first outer.
         * */
        var anchor: VirtualResistor? = null

        fun addInner(remote: VirtualResistor) = inners.addUnique(remote)

        fun addOuter(remote: VirtualResistor) = outers.addUnique(remote)
    }
}

/**
 * [CircuitBuilder]:
 * This is a wrapper around [Circuit], designed to streamline the process of incorporating [VirtualComponent]s and establishing connections through these virtual entities. The ultimate outcome is the creation and integration of tangible (real) [Component]s into the [Circuit].
 * Notably, it implements both [ElectricalComponentSet] and [ElectricalConnectivityMap].
 *
 * This [CircuitBuilder] is structured for use in multiple stages:
 *  - Component Acquisition:
 *      - During this phase, [Component]s and [VirtualComponent]s are added, typically by traversing game objects and providing them this as [ElectricalComponentSet].
 *  - Connection Mapping:
 *      - In this stage, connections between [Component]s, [VirtualComponent]s are established. This is also achieved by traversing game objects and presenting this as [ElectricalConnectivityMap].
 *  - Compilation:
 *      - The final step involves calling [realizeVirtual], which generates [Component]s from the existing [VirtualComponent]s and establishes the necessary connections.
 * */
class CircuitBuilder(val circuit: Circuit): ElectricalComponentSet, ElectricalConnectivityMap {
    private val terms = HashSet<Term>()
    private val lineCompiler = LineCompiler(this)
    private var realized = false

    private fun validateUsage() = require(!realized) {
        "Tried to continue building after realized"
    }

    private fun requireIsAdded(component: Term) = require(terms.contains(component)) {
        "Tried to use component $component which was not added"
    }

    private fun checkPair(a: Term, b: Term) {
        if(a === b) {
            error("Cannot connect $a to itself")
        }

        requireIsAdded(a)
        requireIsAdded(b)
    }

    override fun add(component: Component) : Boolean {
        validateUsage()

        if(!terms.add(component)) {
            return false
        }

        circuit.add(component)

        return true
    }

    override fun add(component: VirtualComponent) : Boolean {
        validateUsage()

        if(!terms.add(component)) {
            return false
        }

        when (component) {
            is VirtualResistor -> {
                return lineCompiler.addResistor(component)
            }

            else -> {
                error("Cannot add $component")
            }
        }
    }

    override fun add(component: Term) : Boolean {
        validateUsage()

        return when (component) {
            is Component -> {
                add(component)
            }

            is VirtualComponent -> {
                add(component)
            }

            else -> {
                error("Cannot add $component")
            }
        }
    }

    override fun <T> add(holder: ComponentHolder<T>) : Boolean where T : Component = add(holder.instance)

    override fun connect(a: Component, aIdx: Int, b: Component, bIdx: Int) {
        validateUsage()
        checkPair(a, b)
        a.connect(aIdx, b, bIdx)
    }

    override fun connect(a: Component, aIdx: Int, b: VirtualComponent, bIdx: Int) {
        validateUsage()
        checkPair(a, b)

        if(b is VirtualResistor) {
            lineCompiler.connect(a, aIdx, b, bIdx)
        }
        else {
            error("Cannot connect $b")
        }
    }

    override fun connect(a: VirtualComponent, aIdx: Int, b: Component, bIdx: Int) {
        validateUsage()
        connect(b, bIdx, a, aIdx)
    }

    override fun connect(a: VirtualComponent, aIdx: Int, b: VirtualComponent, bIdx: Int) {
        validateUsage()
        checkPair(a, b)

        if(a is VirtualResistor && b is VirtualResistor) {
            lineCompiler.connect(a, aIdx, b, bIdx)
        }
        else {
            error("Invalid components $a $b")
        }
    }

    override fun connect(a: Term, aIdx: Int, b: Term, bIdx: Int) {
        validateUsage()

        if(a is Component && b is Component) {
            connect(a, aIdx, b, bIdx)
        }
        else if(a is Component && b is VirtualComponent) {
            connect(a, aIdx, b, bIdx)
        }
        else if(a is ComponentHolder<*> && b is VirtualComponent) {
            connect(a.instance, aIdx, b, bIdx)
        }
        else if(a is VirtualComponent && b is Component) {
            connect(a, aIdx, b, bIdx)
        }
        else if(a is VirtualComponent && b is ComponentHolder<*>) {
            connect(a, aIdx, b.instance, bIdx)
        }
        else if(a is VirtualComponent && b is VirtualComponent) {
            connect(a, aIdx, b, bIdx)
        }
        else {
            error("Cannot connect $a and $b")
        }
    }

    /**
     * Realizes all virtual components and connections, thereby finalizing [Circuit].
     * */
    fun realizeVirtual() {
        validateUsage()
        lineCompiler.compile()
        realized = true
    }
}

/**
 * A [VirtualComponent] resembling a resistor, may or may not become an actual [Line] component.
 * These are organized into lines, providing significant optimizations for circuits with extensive
 * sections of virtual resistors in series, such as a straight wire or a power grid.
 * **Polarity is not guaranteed to match that of a [Resistor]. Maybe fix?**
 * */
class VirtualResistor : IResistor, VirtualComponent {
    var part: Line.Part? = null
        private set

    fun clear() {
        part = null
    }

    fun setHandle(handle: Line.Part) {
        this.part = handle
        handle.resistance = this.resistance
    }

    override val power: Double
        get() = part?.power ?: 0.0

    override val potential: Double
        get() = part?.potential ?: 0.0

    override val current: Double
        get() = part?.current ?: 0.0

    override var resistance: Double = 1.0
        set(value) {
            if(field != value) {
                field = value
                part?.resistance = value
            }
        }

    override fun toString() = "Virtual[L=${part?.line?.hashCode()}, P=${part?.hashCode()}, R=$resistance]"
}
