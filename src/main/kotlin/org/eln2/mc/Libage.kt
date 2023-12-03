package org.eln2.mc

import org.ageseries.libage.data.*
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.electrical.mna.Circuit
import org.ageseries.libage.sim.electrical.mna.component.*
import org.ageseries.libage.sim.thermal.ThermalMass
import org.eln2.mc.common.cells.foundation.ComponentHolder
import org.eln2.mc.common.cells.foundation.NEGATIVE_PIN
import org.eln2.mc.common.cells.foundation.POSITIVE_PIN
import org.eln2.mc.data.HashDataTable

const val LARGE_RESISTANCE = 1e9

object MaterialMapping {
    private val map = biMapOf(
        "iron" to Material.IRON,
        "copper" to Material.COPPER
    )

    fun getMaterial(name: String): Material {
        return map.forward[name] ?: error("Name $name does not correspond to any material.")
    }

    fun getName(material: Material): String {
        return map.backward[material] ?: error("Material $material does not have a mapping!")
    }
}

data class ThermalBodyDef(val material: Material, val mass: Double, val area: Double, val energy: Double? = null) {
    fun create() = ThermalBody(
        ThermalMass(
            material,
            energy,
            mass,
        ),
        area
    )
}

val nullMaterial = Material(0.0, 0.0, 0.0, 0.0)
fun nullThermalMass() = ThermalMass(nullMaterial, 0.0, 0.0)
fun nullThermalBody() = ThermalBody(nullThermalMass(), 0.0)

fun Material.hash(): Int {
    val a = this.electricalResistivity
    val b = this.thermalConductivity
    val c = this.specificHeat
    val d = this.density

    var result = a.hashCode()
    result = 31 * result + b.hashCode()
    result = 31 * result + c.hashCode()
    result = 31 * result + d.hashCode()
    return result
}

// FIXME FIXME FIXME

// Remove this or do something, it is no longer useful

// FIXME FIXME FIXME

class ThermalBody(var thermal: ThermalMass, var area: Double) {
    var temperature: Quantity<Temperature> by thermal::temperature

    var temperatureKelvin get() = !temperature
        set(value) {
            temperature = Quantity(value, KELVIN)
        }

    var energy: Double
        get() = thermal.energy
        set(value) {
            thermal.energy = value
        }


    companion object {
        fun createDefault(): ThermalBody {
            return ThermalBody(ThermalMass(Material.COPPER), 0.5)
        }

        fun createDefault(env: HashDataTable): ThermalBody {
            return createDefault().also { b ->
                env.getOrNull<EnvironmentalTemperatureField>()?.readTemperature()?.also {
                    b.temperature = it
                }
            }
        }
    }
}

interface ImaginaryComponent : Term

interface ElectricalComponentSet {
    fun add(component: Component) : Boolean
    fun add(component: ImaginaryComponent) : Boolean
    fun <T> add(holder: ComponentHolder<T>) : Boolean where T : Component
    fun add(component: Term) : Boolean
}

interface ElectricalConnectivityMap {
    fun connect(a: ImaginaryComponent, aIdx: Int, b: ImaginaryComponent, bIdx: Int)
    fun connect(a: Component, aIdx: Int, b: ImaginaryComponent, bIdx: Int)
    fun connect(a: ImaginaryComponent, aIdx: Int, b: Component, bIdx: Int)
    fun connect(a: Component, aIdx: Int, b: Component, bIdx: Int)
    fun connect(a: Term, aIdx: Int, b: Term, bIdx: Int)
}

class LineCompiler(private val circuitBuilder: CircuitBuilder) {
    private val lineResistors = HashMap<ResistorVirtual, Pair<DisjointSet, DisjointSet>>()
    private val lineToRealConnections = MutableSetMapMultiMap<ResistorVirtual, RealLineConnectionInfo>()
    private val lineToLineConnections = MutableSetMapMultiMap<ResistorVirtual, LineLineConnectionInfo>()

    fun addResistor(resistorVirtual: ResistorVirtual) : Boolean {
        if(lineResistors.containsKey(resistorVirtual)) {
            return false
        }

        lineResistors.putUnique(resistorVirtual, Pair(DisjointSet(), DisjointSet()))

        return true
    }

    fun connect(a: Component, aIdx: Int, b: ResistorVirtual, bIdx: Int) {
        if(bIdx != POSITIVE_PIN && bIdx != NEGATIVE_PIN) {
            error("Invalid line pin $bIdx")
        }

        check(lineResistors.contains(b))

        lineToRealConnections[b].add(RealLineConnectionInfo(a, aIdx, bIdx))

        if(lineToLineConnections[b].any { it.localPin == bIdx }) {
            error("Fused line and real connection on same pin is not allowed")
        }
    }

    fun connect(a: ResistorVirtual, aIdx: Int, b: ResistorVirtual, bIdx: Int) {
        val (aP, aN) = lineResistors[a].requireNotNull { "$a is not added" }
        val (bP, bN) = lineResistors[b].requireNotNull { "$b is not added" }

        if(lineToRealConnections[a].any { it.localPin == aIdx }) {
            error("Fused line and real connection on same pin is not allowed")
        }

        if(lineToRealConnections[b].any { it.localPin == bIdx }) {
            error("Fused line and real connection on same pin is not allowed")
        }

        lineToLineConnections[a].add(LineLineConnectionInfo(b, bIdx, aIdx))
        lineToLineConnections[b].add(LineLineConnectionInfo(a, aIdx, bIdx))

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

    private fun closeLine(start: ResistorVirtual, queue: ArrayDeque<ResistorVirtual>, visited: HashSet<ResistorVirtual>, outers: HashSet<ResistorVirtual>, graph: LineGraph) {
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

            val lineConnections = lineToLineConnections[front]

            require(lineConnections.size == 2) {
                "Invalid inner"
            }

            lineConnections.forEach {
                if(it.remote != start) {
                    queue.add(it.remote)
                }
            }
        }
    }

    private fun getCandidate(
        visited: HashSet<ResistorVirtual>,
        start: ResistorVirtual,
        pins: Pair<DisjointSet, DisjointSet>,
        nodes: MultiSet<DisjointSet>
    ) : ResistorVirtual? {
        var hasRealP = false
        var hasRealN = false

        val realConnections = lineToRealConnections.map[start]

        if(realConnections != null) {
            for(connection in realConnections) {
                when(connection.localPin) {
                    POSITIVE_PIN -> {
                        hasRealP = true
                    }
                    NEGATIVE_PIN -> {
                        hasRealN = true
                    }
                }

                if(hasRealP && hasRealN) {
                    break
                }
            }
        }

        var nImagP = 0
        var nImagN = 0
        var candidateP: ResistorVirtual? = null
        var candidateN: ResistorVirtual? = null

        val lineConnections = lineToLineConnections.map[start]

        if(lineConnections != null) {
            for (connection in lineConnections) {
                when(connection.localPin) {
                    POSITIVE_PIN -> {
                        nImagP++
                        candidateP = connection.remote
                    }
                    NEGATIVE_PIN -> {
                        nImagN++
                        candidateN = connection.remote
                    }
                }
            }
        }
        else {
            return null
        }

        if(hasRealP || nImagP != 1 || visited.contains(candidateP!!) || nodes[pins.first.representative] != 2) {
            candidateP = null
        }

        if(hasRealN || nImagN != 1 || visited.contains(candidateN!!) || nodes[pins.second.representative] != 2) {
            candidateN = null
        }

        require(candidateP == null || candidateN == null) {
            "Invalid exterior"
        }

        if(candidateP != null) {
            return candidateP
        }

        return candidateN
    }

    /**
     * Gets all line graphs.
     * A line graph has:
     * - One or two end points (outers) - these are virtual resistors that will "become the two ends" of the real Line resistor, because they have connections to other non-line resistors, and/or are the first/last resistors in a line (they have 0 or 1 connections)
     * - Zero or multiple inner parts (inners) - these are virtual resistors that have 2 connections to other virtual resistors. These do not materialize into real Line resistors later on.
     * */
    private fun getLineGraphs() : List<LineGraph> {
        val nodes = MutableMapMultiSet<DisjointSet>()

        lineResistors.values.forEach { (p, n) ->
            nodes += p.representative
            nodes += n.representative
        }

        val outers = HashSet<ResistorVirtual>()

        lineResistors.forEach { (resistor, pins) ->
            val isOuter = lineToRealConnections.contains(resistor) ||
                nodes[pins.first.representative] > 2 || nodes[pins.second.representative] > 2 ||
                let {
                    val lineConnections = lineToLineConnections[resistor]

                    if(lineConnections.size != 2) {
                        true
                    }
                    else {
                        val iterator = lineConnections.iterator()
                        val a = iterator.next()
                        val b = iterator.next()
                        a.localPin == b.localPin
                    }
                }

            if(isOuter) {
                outers.add(resistor)
            }
        }

        val results = ArrayList<LineGraph>()
        val visited = HashSet<ResistorVirtual>()
        val queue = ArrayDeque<ResistorVirtual>()

        while (outers.isNotEmpty()) {
            val graph = LineGraph()

            val start = outers.removeFirst()
            val pins = lineResistors[start]!!

            graph.addOuter(start)
            check(visited.add(start))

            val candidate = getCandidate(visited, start, pins, nodes)

            if(candidate != null) {
                queue.add(candidate)
                closeLine(start, queue, visited, outers, graph)
                queue.clear()
            }

            results.add(graph)
        }

        return results
    }

    fun compile() {
        if(lineResistors.isEmpty()) {
            return
        }

        val graphs = getLineGraphs()

        /**
         * Maps an endpoint virtual resistor (R1) to its graph's connectivity map, at that endpoint.
         * The connectivity map maps a remote virtual resistor (R2) that is connected via [lineToLineConnections] to R1 to the pin of the real Line component (owned by R1's line graph) assigned for the real connection between R1's line and R2's line.
         * */
        val connections = HashMap<ResistorVirtual, HashMap<ResistorVirtual, Pair<Line, Int>>>()
        var pin = 0

        graphs.forEach { graph ->
            val line = graph.line

            circuitBuilder.add(line)

            graph.forEach { resistor ->
                resistor.setHandle(line.add(line.size))
            }

            if(graph.size == 1) {
                // Single resistor. The imaginary pins will be the same as the pins used by the real pins:
                require(graph.outers.size == 1)
                require(graph.inners.size == 0)

                val outer = graph.outers.first()

                lineToRealConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                    circuitBuilder.connect(line, localIdx, remote, remoteIdx)
                }

                val connectivityMap = HashMap<ResistorVirtual, Pair<Line, Int>>(2)
                connections.putUnique(outer, connectivityMap)

                // There may be some imaginary resistors connected - they just didn't fit the line graph invariant, so they are not included in this graph
                lineToLineConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                    connectivityMap.putUnique(remote, Pair(line, localIdx))
                }
            }
            else {
                // A line of resistors. We will assign arbitrary pins to the (at most) 2 outers:
                graph.outers.forEach { outer ->
                    val connectivityMap = HashMap<ResistorVirtual, Pair<Line, Int>>(2)
                    connections.putUnique(outer, connectivityMap)

                    val arbitraryPin = (pin++) % 2

                    // This time, the outer resistor's real connections will all receive the same arbitrary pin of the Line.
                    lineToRealConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                        circuitBuilder.connect(line, arbitraryPin, remote, remoteIdx)
                    }

                    // Other imaginary connections will use the arbitrary pin to connect to our Line:
                    lineToLineConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                        connectivityMap.putUnique(remote, Pair(line, arbitraryPin))
                    }
                }
            }
        }

        // Resolve imaginary connections.
        // These will materialize into connections between real Line components.
        // These Line components are all generated here; they result from clusters of virtual resistors that need multiple Line resistors to be represented.
        // We do have some residual lineToLineConnections. Those are from the connections between elements from the same lines (inner to inner and inner to end point).
        // Only some of those will be between the end points (outers) of different lines, and those are the ones we need to tackle.
        graphs.forEach { graph ->
            graph.outers.forEach { outer ->
                for (connection in lineToLineConnections[outer]) {
                    if(graph.contains(connection.remote)) {
                        // Residual connection to a component in the graph
                        // Those will not materialize into real connections.
                        continue
                    }

                    // This will contain the pin of the remote line that we need to connect to:
                    val remoteEdgeMap = connections[connection.remote].requireNotNull {
                        "Did not have remote edge map"
                    }

                    val (remoteLine, remotePin) = remoteEdgeMap[outer].requireNotNull {
                        "Did not have remote imaginary edge"
                    }

                    // This will contain the pin of our line that we need to connect with the remote line.
                    val localEdgeMap = connections[outer].requireNotNull {
                        "Did not have local edge map"
                    }

                    val (localLine, localPin) = localEdgeMap[connection.remote].requireNotNull {
                        "Did not have local imaginary edge"
                    }

                    check(graph.line == localLine)
                    check(localLine != remoteLine)

                    // Finally, this real connection will connect together the two Line components:
                    circuitBuilder.connect(localLine, localPin, remoteLine, remotePin)
                }
            }
        }
    }

    private data class RealLineConnectionInfo(
        val remote: Component,
        val remotePin: Int,
        val localPin: Int
    )

    private data class LineLineConnectionInfo(
        val remote: ResistorVirtual,
        val remotePin: Int,
        val localPin: Int
    )

    private class LineGraph {
        val line = Line()

        val size get() = inners.size + outers.size

        fun contains(resistor: ResistorVirtual) = outers.contains(resistor) || inners.contains(resistor)

        inline fun forEach(action: (ResistorVirtual) -> Unit) {
            inners.forEach(action)
            outers.forEach(action)
        }

        /**
         * Gets all inner resistors in the cluster
         * */
        val inners = HashSet<ResistorVirtual>()

        /**
         * Gets all end point resistors in the cluster
         * */
        val outers = HashSet<ResistorVirtual>()

        fun addInner(remote: ResistorVirtual) = inners.addUnique(remote)

        fun addOuter(remote: ResistorVirtual) = outers.addUnique(remote)
    }
}

class CircuitBuilder(val circuit: Circuit): ElectricalComponentSet, ElectricalConnectivityMap {
    val components = HashSet<Term>()

    private val lineCompiler = LineCompiler(this)
    private var realized = false

    private fun checkRealized() = require(!realized) { "Tried to continue building after realized" }
    private fun checkContains(component: Term) = require(components.contains(component)) { "Tried to use component $component which was not added" }

    private fun checkPair(a: Term, b: Term) {
        if(a === b) {
            error("Cannot connect $a to itself")
        }

        checkContains(a)
        checkContains(b)
    }

    override fun add(component: Component) : Boolean {
        checkRealized()

        if(!components.add(component)) {
            return false
        }

        circuit.add(component)

        return true
    }

    override fun add(component: ImaginaryComponent) : Boolean {
        checkRealized()

        if(!components.add(component)) {
            return false
        }

        when (component) {
            is ResistorVirtual -> {
                return lineCompiler.addResistor(component)
            }

            else -> {
                error("Cannot add $component")
            }
        }
    }

    override fun add(component: Term) : Boolean {
        checkRealized()

        return when (component) {
            is Component -> {
                add(component)
            }

            is ComponentHolder<*> -> {
                add(component.instance)
            }

            is ImaginaryComponent -> {
                add(component)
            }

            else -> {
                error("Cannot add $component")
            }
        }
    }

    override fun <T> add(holder: ComponentHolder<T>) : Boolean where T : Component = add(holder.instance)

    override fun connect(a: Component, aIdx: Int, b: Component, bIdx: Int) {
        checkRealized()
        checkPair(a, b)
        require(circuit.components.contains(a) && circuit.components.contains(b)) { "A, B not all in circuit" }
        a.connect(aIdx, b, bIdx)
    }

    override fun connect(a: Component, aIdx: Int, b: ImaginaryComponent, bIdx: Int) {
        checkRealized()
        checkPair(a, b)

        if(b is ResistorVirtual) {
            lineCompiler.connect(a, aIdx, b, bIdx)
        }
        else {
            error("Cannot connect $b")
        }
    }

    override fun connect(a: ImaginaryComponent, aIdx: Int, b: Component, bIdx: Int) {
        checkRealized()
        connect(b, bIdx, a, aIdx)
    }

    override fun connect(a: ImaginaryComponent, aIdx: Int, b: ImaginaryComponent, bIdx: Int) {
        checkRealized()
        checkPair(a, b)

        if(a is ResistorVirtual && b is ResistorVirtual) {
            lineCompiler.connect(a, aIdx, b, bIdx)
        }
        else {
            error("Invalid components $a $b")
        }
    }

    override fun connect(a: Term, aIdx: Int, b: Term, bIdx: Int) {
        checkRealized()

        if(a is Component && b is Component) {
            connect(a, aIdx, b, bIdx)
        }
        else if(a is Component && b is ImaginaryComponent) {
            connect(a, aIdx, b, bIdx)
        }
        else if(a is ComponentHolder<*> && b is ImaginaryComponent) {
            connect(a.instance, aIdx, b, bIdx)
        }
        else if(a is ImaginaryComponent && b is Component) {
            connect(a, aIdx, b, bIdx)
        }
        else if(a is ImaginaryComponent && b is ComponentHolder<*>) {
            connect(a, aIdx, b.instance, bIdx)
        }
        else if(a is ImaginaryComponent && b is ImaginaryComponent) {
            connect(a, aIdx, b, bIdx)
        }
        else {
            error("Cannot connect $a and $b")
        }
    }

    fun realizeVirtual() {
        checkRealized()

        lineCompiler.compile()

        realized = true
    }
}

/**
 * Resistor-like [ImaginaryComponent] that may or may not materialize into a real [Line] component.
 * These resistors are grouped into lines, which brings enormous optimizations to circuits that have large sections of virtual resistors in series.
 * */
class ResistorVirtual : IResistor, ImaginaryComponent {
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
