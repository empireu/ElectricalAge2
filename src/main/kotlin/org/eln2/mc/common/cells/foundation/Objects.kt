package org.eln2.mc.common.cells.foundation

import net.minecraft.nbt.CompoundTag
import org.ageseries.libage.data.Locator
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Simulator
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.electrical.mna.*
import org.ageseries.libage.sim.electrical.mna.component.*
import org.eln2.mc.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.data.*
import org.eln2.mc.extensions.getQuantity
import org.eln2.mc.extensions.putQuantity

/**
 * Represents a discrete simulation unit that participates in one simulation type.
 * It can connect to other objects of the same simulation type.
 * */
abstract class SimulationObject<C : Cell>(val cell: C) {
    abstract val type: SimulationObjectType

    private val rsLazy = lazy { LocatorRelationRuleSet() }

    val ruleSet get() = rsLazy.value

    /**
     * Called when the connections and/or graph changes.
     * */
    open fun update(connectionsChanged: Boolean, graphChanged: Boolean) {
        if(graphChanged) {
            subscribe(cell.persistentPool)
        }
    }

    /**
     * Called when subscribers should be added, after the graph changes.
     * This is called after [Cell.subscribe]
     * */
    protected open fun subscribe(subscribers: SubscriberCollection) { }

    /**
     * Called when the solver is being built.
     * Here, the previous state should be cleared so that the object is ready to join a new simulation.
     * */
    abstract fun clear()

    /**
     * Called when the cell is destroyed.
     * Connections should be removed here.
     * */
    abstract fun destroy()

    /**
     * Implements locator-based rules. These rules are general to all objects.
     * @param remoteLocator The locator of the remote object's cell.
     * @return True, if the connection is allowed. Otherwise, false. *This does not imply that the connection will be created; other filters might reject it down the line.*
     * */
    open fun acceptsRemoteLocation(remoteLocator: Locator): Boolean {
        return ruleSet.accepts(cell.locator, remoteLocator)
    }
}

data class ThermalComponentInfo(val body: ThermalMass)

interface ThermalContactInfo {
    fun getContactTemperature(other: Cell) : Quantity<Temperature>?
}

abstract class ThermalObject<C : Cell>(cell: C) : SimulationObject<C>(cell) {
    var simulation: Simulator? = null
        private set

    protected val connections = ArrayList<ThermalObject<*>>()

    final override val type = SimulationObjectType.Thermal

    open val maxConnections = Int.MAX_VALUE

    protected fun indexOf(obj: ThermalObject<*>): Int {
        val index = connections.indexOf(obj)

        if (index == -1) {
            error("Connections did not have $obj")
        }

        return index
    }

    /**
     * Called by the cell graph to fetch a connection candidate.
     * */
    abstract fun offerComponent(remote: ThermalObject<*>): ThermalComponentInfo

    /**
     * Called by the building logic when the thermal object is made part of a simulation.
     * Also calls the *registerComponents* method.
     * */
    fun setNewSimulation(simulator: Simulator) {
        this.simulation = simulator

        addComponents(simulator)
    }

    /**
     * Called by the cell when a valid connection candidate is discovered.
     * */
    open fun addConnection(connectionInfo: ThermalObject<*>) {
        require(!connections.contains(connectionInfo)) { "Duplicate connection" }
        connections.add(connectionInfo)

        if (connections.size > maxConnections) {
            error("Thermal object received more connections than were allowed")
        }
    }

    /**
     * Called when this object is destroyed. Connections are also cleaned up.
     * */
    override fun destroy() {
        connections.forEach { it.connections.remove(this) }
    }

    override fun clear() {
        connections.clear()
    }

    /**
     * Called when the simulation must be updated with the components owned by this object.
     * */
    protected abstract fun addComponents(simulator: Simulator)

    /**
     * Implements object-based rules.
     * @param remote The remote thermal object.
     * @return True, if the connection is allowed. Otherwise, false.
     * */
    open fun acceptsRemoteObject(remote: ThermalObject<*>) = true

    protected open fun getParameters(remote: ThermalObject<*>) = ConnectionParameters.DEFAULT

    fun build() {
        if (simulation == null) {
            error("Tried to build thermal obj with null simulation")
        }

        connections.forEach { remote ->
            assert(remote.simulation == simulation)

            simulation!!.connect(
                this.offerComponent(remote).body,
                remote.offerComponent(this).body,
                getParameters(remote)
            )
        }
    }
}


data class TermRef(val component: Term, val index: Int)

abstract class ElectricalObject<C : Cell>(cell: C) : SimulationObject<C>(cell) {
    /**
     * The circuit this object is part of.
     * It is initialized while the solver is being built.
     * @see setNewCircuit
     * */
    var circuit: Circuit? = null
        private set

    val connections = ArrayList<ElectricalObject<*>>()

    val connectionList get() = connections as List<ElectricalObject<*>>

    final override val type = SimulationObjectType.Electrical

    protected fun indexOf(obj: ElectricalObject<*>): Int {
        val index = connections.indexOf(obj)

        if (index == -1) {
            error("Connections did not have $obj")
        }

        return index
    }

    /**
     * Called by the building logic when the electrical object is made part of a circuit.
     * Also calls the *registerComponents* method.
     * */
    fun setNewCircuit(builder: CircuitBuilder) {
        this.circuit = builder.circuit

        addComponents(builder)
    }

    /**
     * Called by the cell when a valid connection candidate is discovered.
     * */
    open fun addConnection(remoteObj: ElectricalObject<*>) {
        require(!connections.contains(remoteObj)) {
            "Duplicate connection"
        }

        connections.add(remoteObj)
    }

    /**
     * Called when this object is destroyed. Connections are also cleaned up.
     * */
    override fun destroy() {
        connections.forEach { it.connections.remove(this) }
    }

    override fun clear() {
        connections.clear()
        clearComponents()
    }

    /**
     * Called when the solver is being built, and the components need to be re-created (or refreshed)
     * The connections are not available at this stage.
     * */
    protected open fun clearComponents() { }

    /**
     * Called by electrical objects to fetch a connection candidate.
     * The same component and pin **must** be returned by subsequent calls to this method, during same re-building moment.
     * */
    fun offerComponent(remote: ElectricalObject<*>) : TermRef? {
        val remoteCell = remote.cell

        return if(remoteCell is GridConnectionCell) {
            offerTerminal(remoteCell, remoteCell.getFullMetadata(cell))
        }
        else {
            offerPolar(remote)
        }
    }


    /**
     * Called by electrical objects to fetch a connection candidate.
     * The same component and pin **must** be returned by subsequent calls to this method, during same re-building moment.
     * */
    protected open fun offerPolar(remote: ElectricalObject<*>): TermRef? = null

    /**
     * Called by the grid connection cell's electrical object to fetch a connection candidate, for the specified terminal [m0].
     * The same component and pin **must** be returned by subsequent calls to this method, during same re-building moment.
     * */
    protected open fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo): TermRef? = null

    /**
     * Called when the circuit must be updated with the components owned by this object.
     * This is called before build.
     * By default, offers for all [connections] are gathered using [offerPolar], and the offered components are all added to the [circuit]
     * */
    protected open fun addComponents(circuit: ElectricalComponentSet) {
        for (remote in connections) {
            val offer = offerComponent(remote)
                ?: continue

            remote.offerComponent(this)
                ?: continue

            circuit.add(offer.component)
        }
    }

    /**
     * Implements object-based rules.
     * @param remote The remote electrical object.
     * @return True, if the connection is allowed. Otherwise, false.
     * */
    open fun acceptsRemoteObject(remote: ElectricalObject<*>) = true

    /**
     * Builds the connections, after the circuit was acquired in [setNewCircuit] and the components were added in [addComponents].
     * */
    open fun build(map: ElectricalConnectivityMap) {
        for (remote in connections) {
            val localInfo = this.offerComponent(remote)
                ?: continue

            val remoteInfo = remote.offerComponent(this)
                ?: continue

            map.join(localInfo, remoteInfo)
        }
    }
}

/**
 * Represents an object with NBT saving capabilities.
 * */
interface PersistentObject {
    fun saveObjectNbt(): CompoundTag
    fun loadObjectNbt(tag: CompoundTag)
}

class SimulationObjectSet(objects: List<SimulationObject<*>>) {
    private val objects = HashMap<SimulationObjectType, SimulationObject<*>>()

    init {
        objects.forEach {
            if (this.objects.put(it.type, it) != null) {
                error("Duplicate object of type ${it.type}")
            }
        }
    }

    fun hasObject(type: SimulationObjectType): Boolean {
        return objects.contains(type)
    }

    private fun getObject(type: SimulationObjectType): SimulationObject<*> {
        return objects[type] ?: error("Object set does not have $type")
    }

    fun getObjectOrNull(type: SimulationObjectType): SimulationObject<*>? {
        return objects[type]
    }

    val electricalObject get() = getObject(SimulationObjectType.Electrical) as ElectricalObject

    val thermalObject get() = getObject(SimulationObjectType.Thermal) as ThermalObject

    fun forEachObject(function: ((SimulationObject<*>) -> Unit)) {
        objects.values.forEach(function)
    }

    operator fun get(type: SimulationObjectType): SimulationObject<*> {
        return objects[type] ?: error("Object set does not have $type")
    }
}

enum class SimulationObjectType(val index: Int, val id: Int, val domain: String) {
    Electrical(0, 1, "electrical"),
    Thermal(1, 2, "thermal");
}

interface ThermalBipole {
    val b1: ThermalMass
    val b2: ThermalMass
}

/**
 * Thermal object made of two thermal masses.
 * @param map A map that maps the remote object to one of the two bodies.
 * @param b1 The body that maps to [Pole.Plus]
 * @param b2 The body that maps to [Pole.Minus]
 * */
class ThermalBipoleObject<C : Cell>(
    cell: C,
    val map: PoleMap,
    override val b1: ThermalMass,
    override val b2: ThermalMass,
    val b1Leakage: ConnectionParameters,
    val b2Leakage: ConnectionParameters
) : ThermalObject<C>(cell), PersistentObject, ThermalBipole, ThermalContactInfo {
    companion object {
        private const val B1 = "b1"
        private const val B2 = "b2"
    }

    private var lastTemperatureB1: Double
    private var lastTemperatureB2: Double

    init {
        cell.environmentData.loadTemperature(b1, b2)
        lastTemperatureB1 = !b1.temperature
        lastTemperatureB2 = !b2.temperature
    }

    override fun offerComponent(remote: ThermalObject<*>) = ThermalComponentInfo(
        when (map.evaluate(cell, remote.cell)) {
            Pole.Plus -> b1
            Pole.Minus -> b2
        }
    )

    override fun addComponents(simulator: Simulator) {
        simulator.add(b1)
        simulator.add(b2)
        simulator.connect(b1, cell.environmentData.ambientTemperature, b1Leakage)
        simulator.connect(b2, cell.environmentData.ambientTemperature, b2Leakage)
    }

    override fun getContactTemperature(other: Cell): Quantity<Temperature>? {
        val direction = map.evaluateOrNull(this.cell, other)
            ?: return null

        return when(direction) {
            Pole.Plus -> b1.temperature
            Pole.Minus -> b2.temperature
        }
    }

    override fun saveObjectNbt(): CompoundTag {
        val tag = CompoundTag()

        tag.putQuantity(B1, b1.temperature)
        tag.putQuantity(B2, b2.temperature)

        return tag
    }

    override fun loadObjectNbt(tag: CompoundTag) {
        b1.temperature = tag.getQuantity(B1)
        b2.temperature = tag.getQuantity(B2)
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addSubscriber(
            SubscriberOptions(100, SubscriberPhase.Post),
            this::simulationTick
        )
    }

    private fun simulationTick(dt: Double, phase: SubscriberPhase) {
        val flag = !b1.temperature.value.approxEq(lastTemperatureB1) || !b2.temperature.value.approxEq(lastTemperatureB2)

        cell.setChangedIf(flag) {
            lastTemperatureB1 = !b1.temperature
            lastTemperatureB2 = !b2.temperature
        }
    }
}

/**
 * Electrical generator modeled with a resistor and a source of potential.
 * */
abstract class VRGObject<C : Cell>(cell: C) : ElectricalObject<C>(cell) {
    /**
     * Gets the resistor used by this object. Not virtual because virtual resistor's don't guarantee polarity right now.
     * */
    val resistor = Resistor()

    /**
     * Gets the voltage source used by this object.
     * */
    val source = VoltageSource()

    /**
     * Adds [resistor] and [source] to the circuit, regardless of their connection status with other things.
     * */
    final override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(resistor)
        circuit.add(source)
    }

    /**
     * Offers a term ref that should be used when connecting to an object on the positive pole of this source.
     * */
    protected fun plusOffer() = resistor.offerExternal()

    /**
     * Offers a term ref that should be used when connecting to an object on the negative pole of this source.
     * */
    protected fun minusOffer() = source.offerNegative()

    /**
     * Builds the sub-circuit and connects to the remote objects.
     * The sub-circuit looks like this:
     * ```
     * ▁▁ ▁▁▁▁▁▁ ▁▁
     * A?│+R-+V-│?B
     * ▔▔ ▔▔▔▔▔▔ ▔▔
     * ```
     * where *A* and *B* are some external objects, *+* is the positive pin, *-* is the negative pin, *R* is [resistor] and *V* is [source].
     * */
    final override fun build(map: ElectricalConnectivityMap) {
        map.join(resistor.offerInternal(), source.offerPositive())
        super.build(map)
    }
}

/**
 * Generator model consisting of a [VoltageSource] + [Resistor], whose poles are mapped using a [PoleMap].
 * */
open class PolarVRGObject<C : Cell>(cell: C, val map: PoleMap) : VRGObject<C>(cell) {
    /**
     * Gets the offered component by evaluating the map.
     * @return
     *  The resistor's external pin when the pole evaluates to [Pole.Plus].
     *  The source's negative pin when the pole evaluates to [Pole.Minus].
     *  Null in any other case.
     * */
    override fun offerPolar(remote: ElectricalObject<*>) =
        when (map.evaluateOrNull(this.cell, remote.cell)) {
            Pole.Plus -> plusOffer()
            Pole.Minus -> minusOffer()
            else -> null
        }
}

/**
 * Generator model consisting of a [VoltageSource] + [Resistor], whose poles are mapped to grid terminals.
 * */
open class TerminalVRGObject<C : Cell>(cell: C, val plus: Int = POSITIVE, val minus: Int = NEGATIVE) : VRGObject<C>(cell) {
    /**
     * Gets the offered component by checking to see which terminal the [gc] is connected to.
     * @return
     *  The resistor's external pin when the terminal is [plus].
     *  The source's negative pin when the terminal is [minus].
     *  Null in any other case.
     * */
    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo) =
        when(m0.terminal) {
            plus -> plusOffer()
            minus -> minusOffer()
            else -> null
        }
}

/**
 * [ElectricalObject] that wraps a single [Term] meant to connect via two poles, plus and minus.
 * @param poleMap A map that maps remote cells to the pin of the term.
 * @param term The term to wrap.
 * */
open class PolarTermObject<C: Cell, T : Term>(cell: C, val poleMap: PoleMap, val term: T) : ElectricalObject<C>(cell) {
    override fun offerPolar(remote: ElectricalObject<*>) = TermRef(
        term,
        poleMap.evaluate(cell, remote.cell).conventionalPin
    )

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(term)
    }
}

/**
 * The voltage source object has a bundle of resistors, whose External Pins are exported to other objects, and
 * a voltage source, connected to the Internal Pins of the bundle.
 * */
class VoltageSourceObject(cell: Cell) : ElectricalObject<Cell>(cell) {
    val source = VoltageSource()
    val resistors = resistorBundle(1e-4)

    override fun offerPolar(remote: ElectricalObject<*>): TermRef {
        return resistors.getOfferedResistor(remote)
    }

    override fun clearComponents() {
        resistors.clear()
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(source)
        resistors.addComponents(connections, circuit)
    }

    override fun build(map: ElectricalConnectivityMap) {
        source.ground(INTERNAL_PIN)
        resistors.build(connections, this, map)

        resistors.forEach {
            map.connect(it, INTERNAL_PIN, source, EXTERNAL_PIN)
        }
    }
}

class TerminalResistorObjectVirtual<C : Cell>(cell: C, val resistor: VirtualResistor, val plus: Int, val minus: Int) : ElectricalObject<C>(cell), IResistor by resistor {
    constructor(cell: C, plus: Int, minus: Int) : this(cell, VirtualResistor(), plus, minus)

    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo) =
        when(m0.terminal) {
            plus -> resistor.offerPositive()
            minus -> resistor.offerNegative()
            else -> null
        }

    override fun acceptsRemoteObject(remote: ElectricalObject<*>): Boolean {
        return super.acceptsRemoteObject(remote) && remote.cell is GridConnectionCell
    }
}

class PolarResistorObjectVirtual<C : Cell>(cell: C, poleMap: PoleMap, virtualResistor: VirtualResistor) : PolarTermObject<C, VirtualResistor>(cell, poleMap, virtualResistor), IResistor by virtualResistor {
    constructor(cell: C, poleMap: PoleMap) : this(cell, poleMap, VirtualResistor())
}

class GroundObject(cell: Cell) : ElectricalObject<Cell>(cell) {
    val resistors = resistorBundle(1e-5)

    override fun offerPolar(remote: ElectricalObject<*>) = resistors.getOfferedResistor(remote)
    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo) = resistors.getOfferedResistor(gc.electrical)

    override fun clearComponents() {
        resistors.clear()
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        resistors.addComponents(connections, circuit)
    }

    override fun build(map: ElectricalConnectivityMap) {
        resistors.build(connections, this, map)
        resistors.forEach {
            it.ground(INTERNAL_PIN)
        }
    }
}

class PowerVoltageSourceObject<C : Cell>(cell: C, val map: PoleMap) : ElectricalObject<C>(cell) {
    val generator = PowerVoltageSource()
    val resistor = VirtualResistor()

    override fun offerPolar(remote: ElectricalObject<*>) = when(map.evaluateOrNull(cell, remote.cell)) {
        Pole.Plus -> generator.offerPositive()
        Pole.Minus -> resistor.offerNegative()
        null -> null
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(generator)
        circuit.add(resistor)
    }

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)
        map.join(generator.offerNegative(), resistor.offerPositive())
    }
}
