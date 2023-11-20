package org.eln2.mc

import mcp.mobius.waila.api.IPluginConfig
import org.ageseries.libage.data.MutableSetMapMultiMap
import org.ageseries.libage.data.biMapOf
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.electrical.mna.Circuit
import org.ageseries.libage.sim.electrical.mna.component.*
import org.ageseries.libage.sim.thermal.Temperature
import org.ageseries.libage.sim.thermal.ThermalMass
import org.eln2.mc.common.cells.foundation.ComponentHolder
import org.eln2.mc.common.cells.foundation.NEGATIVE_PIN
import org.eln2.mc.common.cells.foundation.POSITIVE_PIN
import org.eln2.mc.data.HashDataTable
import org.eln2.mc.integration.WailaNode
import org.eln2.mc.integration.WailaTooltipBuilder

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

class ThermalBody(var thermal: ThermalMass, var area: Double) : WailaNode {
    var temperature: Temperature
        get() = thermal.temperature
        set(value) {
            thermal.temperature = value
        }

    var temperatureKelvin: Double
        get() = temperature.kelvin
        set(value) {
            thermal.temperature = Temperature(value)
        }

    var energy: Double
        get() = thermal.energy
        set(value) {
            thermal.energy = value
        }

    override fun appendWaila(builder: WailaTooltipBuilder, config: IPluginConfig?) {
        thermal.appendBody(builder, config)
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

fun Circuit.addAll(components: Iterable<Component>) {
    components.forEach {
        this.add(it)
    }
}

interface ImaginaryComponent

// marker interface for libage components to replace Any

interface ElectricalComponentSet {
    fun add(component: Component)
    fun add(component: ImaginaryComponent)
    fun <T> add(holder: ComponentHolder<T>) where T : Component
    fun add(component: Any)
}

interface ElectricalConnectivityMap {
    fun connect(a: ImaginaryComponent, aIdx: Int, b: ImaginaryComponent, bIdx: Int)
    fun connect(a: Component, aIdx: Int, b: ImaginaryComponent, bIdx: Int)
    fun connect(a: ImaginaryComponent, aIdx: Int, b: Component, bIdx: Int)
    fun connect(a: Component, aIdx: Int, b: Component, bIdx: Int)
    fun connect(a: Any, aIdx: Int, b: Any, bIdx: Int)
}

class CircuitBuilder(val circuit: Circuit): ElectricalComponentSet, ElectricalConnectivityMap {
    private open class CircuitCallList {
        open fun addComponent(component: Component) { }
        open fun markConnection(a: Component, aIdx: Int, b: Component, bIdx: Int) { }
    }

    private class DebugCallList : CircuitCallList() {
        data class Connection(val aIdx: Int, val b: Component, val bIdx: Int) {
            override fun toString() = "$aIdx - $b $bIdx"
        }

        val components = HashSet<Component>()
        val connections = MutableSetMapMultiMap<Component, Connection>()

        override fun addComponent(component: Component) {
            components.add(component)
        }

        override fun markConnection(a: Component, aIdx: Int, b: Component, bIdx: Int) {
            connections[a].add(Connection(aIdx, b, bIdx))
        }
    }

    val image = HashSet<Any>()

    private var realized = false
    private val lineResistors = HashSet<ResistorVirtual>()
    private val lineToRealConnections = MutableSetMapMultiMap<ResistorVirtual, RealLineConnectionInfo>()
    private val lineToLineConnections = MutableSetMapMultiMap<ResistorVirtual, LineLineConnectionInfo>()

    // Null implementation
    private val callList = CircuitCallList()

    private fun checkRealized() = require(!realized) {
        "Tried to continue building after realized"
    }

    private fun checkContains(component: Any) = require(image.contains(component)) {
        "Tried to use component $component which was not added"
    }

    private fun checkPair(a: Any, b: Any) {
        if(a === b) {
            error("Cannot connect $a to itself")
        }

        checkContains(a)
        checkContains(b)
    }

    override fun add(component: Component) {
        checkRealized()

        if(!image.add(component)) {
            return
        }

        circuit.add(component)
        callList.addComponent(component)
    }

    override fun add(component: ImaginaryComponent) {
        checkRealized()

        if(!image.add(component)) {
            return
        }

        when (component) {
            is ResistorVirtual -> {
                lineResistors.add(component)
            }

            else -> {
                error("Cannot add $component")
            }
        }
    }

    override fun add(component: Any) {
        checkRealized()

        when (component) {
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

    override fun <T> add(holder: ComponentHolder<T>) where T : Component {
        checkRealized()
        add(holder.instance)
    }

    override fun connect(a: Component, aIdx: Int, b: Component, bIdx: Int) {
        checkRealized()
        checkPair(a, b)
        require(circuit.components.contains(a) && circuit.components.contains(b)) {
            "A, B not all in circuit"
        }
        a.connect(aIdx, b, bIdx)
        callList.markConnection(a, aIdx, b, bIdx)
    }

    override fun connect(a: Component, aIdx: Int, b: ImaginaryComponent, bIdx: Int) {
        checkRealized()
        checkPair(a, b)

        if(b is ResistorVirtual) {
            if(bIdx != POSITIVE_PIN && bIdx != NEGATIVE_PIN) {
                error("Invalid line pin $bIdx")
            }

            lineToRealConnections[b].add(RealLineConnectionInfo(a, aIdx, bIdx))

            if(lineToLineConnections[b].any { it.localPin == bIdx }) {
                error("Fused line and real connection on same pin is not allowed")
            }
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
            lineToLineConnections[a].add(LineLineConnectionInfo(b, bIdx, aIdx))
            lineToLineConnections[b].add(LineLineConnectionInfo(a, aIdx, bIdx))
        }
        else {
            error("Invalid components $a $b")
        }
    }

    override fun connect(a: Any, aIdx: Int, b: Any, bIdx: Int) {
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
        compileLines()
        realized = true
    }

    /**
     * Gets the connections from [source], separated into 2 sets: one that concerns the positive pin of the virtual resistor, and one that concerns the negative pin of the virtual resistor.
     * */
    private fun getDisjointConnections(source: Iterable<LineLineConnectionInfo>) : Pair<List<LineLineConnectionInfo>, List<LineLineConnectionInfo>> {
        val positive = ArrayList<LineLineConnectionInfo>(1)
        val negative = ArrayList<LineLineConnectionInfo>(1)

        source.forEach { connection ->
            when (connection.localPin) {
                POSITIVE_PIN -> {
                    positive.add(connection)
                }
                NEGATIVE_PIN -> {
                    negative.add(connection)
                }
                else -> {
                    error("Invalid connection ${connection.localPin}")
                }
            }
        }

        return Pair(positive, negative)
    }

    /**
     * Gets all line graphs.
     * A line graph has:
     * - One or two end points (outers) - these are virtual resistors that will "become the two ends" of the real Line resistor, because they have connections to other non-line resistors, and/or are the first/last resistors in a line (they have 0 or 1 connections)
     * - Zero or multiple inner parts (inners) - these are virtual resistors that have 2 connections to other virtual resistors. These do not materialize into real Line resistors later on.
     * */
    private fun getLineGraphs() : List<LineGraph> {
        val pending = HashSet(lineResistors)
        val queue = ArrayDeque<ResistorVirtual>()
        val results = ArrayList<LineGraph>()

        while (pending.isNotEmpty()) {
            val graph = LineGraph()

            queue.add(pending.first())

            while (queue.isNotEmpty()) {
                val front = queue.removeFirst()

                if(!graph.resistors.add(front)) {
                    continue
                }

                pending.remove(front)

                val realConnections = lineToRealConnections[front]
                val lineConnections = lineToLineConnections[front]

                val isInner = realConnections.isEmpty() && // If it has real connections, we need to do Line connections, therefore, it cannot be inner
                    lineConnections.size == 2 && // If it has 0 or 1, it is either the single resistor in the graph or at one of the ends of the graph
                    lineConnections.any { it.localPin == POSITIVE_PIN } && // The inner component will have 2 connections to other virtual resistors, one on the "positive" pin and one on the "negative" pin
                    lineConnections.any { it.localPin == NEGATIVE_PIN }

                if(isInner) {
                    check(graph.inners.add(front))

                    lineConnections.forEach { connection ->
                        // We can go both left and right here, and we will encounter another inner or one of the end points
                        queue.add(connection.remote)
                    }
                }
                else {
                    check(graph.outers.add(front))

                    require(graph.outers.size <= 2) {
                        "Got more than 2 end points"
                    }

                    /**
                     * We are at an endpoint. Cases:
                     * - This is the only resistor in the graph - the search stops here
                     * - Both pins have real connections - this means [getDisjointConnections] will return empty-handed (we put a condition in our [connect] method that prevents a real and imaginary connection on the same pin of a virtual resistor)
                     * - Only one of the pins may have a connection to another virtual resistor. We will explore towards that, to find the rest of the graph.
                     * */
                    val (positive, negative) = getDisjointConnections(lineConnections)

                    require(!(positive.size == 1 && negative.size == 1)) {
                        "Endpoint condition broken"
                    }

                    if(positive.size == 1) {
                        queue.add(positive[0].remote)
                    }

                    if(negative.size == 1) {
                        queue.add(negative[0].remote)
                    }
                }
            }

            results.add(graph)
        }

        return results
    }

    private fun compileLines() {
        if(lineResistors.isEmpty()) {
            return
        }

        // what to do with the pins?
        // probably has something to do with the sign of the current

        val graphs = getLineGraphs()

        /**
         * Maps an endpoint virtual resistor (R1) to its graph's connectivity map, at that endpoint.
         * The connectivity map maps a remote virtual resistor (R2) that is connected via [lineToLineConnections] to R1 to the pin of the real Line component (owned by R1's line graph) assigned for the real connection between R1's line and R2's line.
         * */
        val imaginaryEdges = HashMap<ResistorVirtual, HashMap<ResistorVirtual, Pair<Line, Int>>>()
        var pin = 0

        graphs.forEach { graph ->
            val line = graph.line

            add(line)

            graph.resistors.forEach { resistor ->
                resistor.setHandle(line.add(line.size))
            }

            if(graph.resistors.size == 1) {
                // Single resistor. The imaginary pins will be the same as the pins used by the real pins:
                require(graph.outers.size == 1)
                require(graph.inners.size == 0)
                val outer = graph.outers.first()

                lineToRealConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                    connect(line, localIdx, remote, remoteIdx)
                }

                val connectivityMap = HashMap<ResistorVirtual, Pair<Line, Int>>(2)
                imaginaryEdges.putUnique(outer, connectivityMap)

                // There may be some imaginary resistors connected - they just didn't fit the line graph invariant, so they are not included in this graph
                lineToLineConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                    connectivityMap.putUnique(remote, Pair(line, localIdx))
                }
            }
            else {
                // A line of resistors. We will assign arbitrary pins to the (at most) 2 outers:
                graph.outers.forEach { outer ->
                    val connectivityMap = HashMap<ResistorVirtual, Pair<Line, Int>>(2)
                    imaginaryEdges.putUnique(outer, connectivityMap)

                    val arbitraryPin = (pin++) % 2

                    // This time, the outer resistor's real connections will all receive the same arbitrary pin of the Line.
                    lineToRealConnections[outer].forEach { (remote, remoteIdx, localIdx) ->
                        connect(line, arbitraryPin, remote, remoteIdx)
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
                    if(graph.resistors.contains(connection.remote)) {
                        // Residual connection to a component in the graph
                        // Those will not materialize into real connections.
                        continue
                    }

                    // This will contain the pin of the remote line that we need to connect to:
                    val remoteEdgeMap = imaginaryEdges[connection.remote].requireNotNull {
                        "Did not have remote edge map"
                    }

                    val (remoteLine, remotePin) = remoteEdgeMap[outer].requireNotNull {
                        "Did not have remote imaginary edge"
                    }

                    // This will contain the pin of our line that we need to connect with the remote line.
                    val localEdgeMap = imaginaryEdges[outer].requireNotNull {
                        "Did not have local edge map"
                    }

                    val (localLine, localPin) = localEdgeMap[connection.remote].requireNotNull {
                        "Did not have local imaginary edge"
                    }

                    check(graph.line == localLine)
                    check(localLine != remoteLine)

                    // Finally, this real connection will connect together the two Line components:
                    connect(localLine, localPin, remoteLine, remotePin)
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

        /**
         * Gets all resistors in the cluster
         * */
        val resistors = HashSet<ResistorVirtual>()

        /**
         * Gets all inner resistors in the cluster
         * */
        val inners = HashSet<ResistorVirtual>()

        /**
         * Gets all end point resistors in the cluster
         * */
        val outers = HashSet<ResistorVirtual>()
    }
}

interface ResistorLike : IPower {
    var resistance: Double
    val potential: Double
    val current: Double
}

/**
 * Resistor-like [ImaginaryComponent] that may or may not materialize into a real [Line] component.
 * These resistors are grouped into lines, which brings enormous optimizations to circuits that have large sections of virtual resistors in series.
 * */
class ResistorVirtual : ResistorLike, ImaginaryComponent {
    var part: Line.Part? = null
        private set

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

// Libage when?
class ResistorLikeResistor : Resistor(), ResistorLike
