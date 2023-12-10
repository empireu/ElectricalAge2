package org.eln2.mc.common.cells.foundation

import net.minecraft.nbt.CompoundTag
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Simulator
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.mna.Circuit
import org.ageseries.libage.sim.electrical.mna.component.PowerVoltageSource
import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.ageseries.libage.sim.electrical.mna.component.Term
import org.ageseries.libage.sim.electrical.mna.component.VoltageSource
import org.eln2.mc.*
import org.eln2.mc.data.*

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
    abstract fun update(connectionsChanged: Boolean, graphChanged: Boolean)

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

    open fun acceptsRemoteLocation(remoteDesc: Locator): Boolean {
        return ruleSet.accepts(cell.locator, remoteDesc)
    }
}

data class ThermalComponentInfo(val body: ThermalMass)

interface ThermalContactInfo {
    fun getContactTemperature(other: Locator) : Quantity<Temperature>?
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
    abstract fun offerComponent(neighbour: ThermalObject<*>): ThermalComponentInfo

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

    override fun update(connectionsChanged: Boolean, graphChanged: Boolean) {}

    override fun clear() {
        connections.clear()
    }

    fun build() {
        if (simulation == null) {
            error("Tried to build thermal obj with null simulation")
        }

        connections.forEach { remote ->
            assert(remote.simulation == simulation)

            simulation!!.connect(
                this.offerComponent(remote).body,
                remote.offerComponent(this).body,
                ConnectionParameters.DEFAULT
            )
        }
    }

    /**
     * Called when the simulation must be updated with the components owned by this object.
     * */
    protected abstract fun addComponents(simulator: Simulator)
}

data class ElectricalComponentInfo(val component: Term, val index: Int)

abstract class ElectricalObject<C : Cell>(cell: C) : SimulationObject<C>(cell) {
    /**
     * The circuit this object is part of.
     * It is initialized while the solver is being built.
     * @see setNewCircuit
     * */
    var circuit: Circuit? = null
        private set

    protected val connections = ArrayList<ElectricalObject<*>>()

    val connectionList get() = connections as List<ElectricalObject<*>>

    final override val type = SimulationObjectType.Electrical

    /**
     * This is used to validate new connections.
     * If more connections than what is specified are created, an error will occur.
     * */
    open val maxConnections = Int.MAX_VALUE

    protected fun indexOf(obj: ElectricalObject<*>): Int {
        val index = connections.indexOf(obj)

        if (index == -1) {
            error("Connections did not have $obj")
        }

        return index
    }

    /**
     * Called by electrical objects to fetch a connection candidate.
     * The same component and pin **must** be returned by subsequent calls to this method, during same re-building moment.
     * */
    abstract fun offerComponent(neighbour: ElectricalObject<*>): ElectricalComponentInfo

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
        require(!connections.contains(remoteObj)) { "Duplicate connection" }

        connections.add(remoteObj)

        if (connections.size > maxConnections) {
            error("Electrical object received more connections than were allowed")
        }
    }

    /**
     * Called when this object is destroyed. Connections are also cleaned up.
     * */
    override fun destroy() {
        connections.forEach { it.connections.remove(this) }
    }

    override fun update(connectionsChanged: Boolean, graphChanged: Boolean) {}

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
     * Called when the circuit must be updated with the components owned by this object.
     * This is called before build.
     * By default, offers for all [connections] are gathered using [offerComponent], and the offered components are all added to the [circuit]
     * */
    protected open fun addComponents(circuit: ElectricalComponentSet) {
        connections.forEach { connection ->
            val offer = offerComponent(connection)
            circuit.add(offer.component)
        }
    }

    /**
     * Builds the connections, after the circuit was acquired in [setNewCircuit] and the components were added in [addComponents].
     * By default, offers for all [connections] are gathered using [offerComponent], and the components are connected using the pins indicated in the offers.
     * */
    open fun build(map: ElectricalConnectivityMap) {
        // Suggested by Grissess (and should have crossed my mind too, shame on me):
        connections.forEach { remote ->
            val localInfo = offerComponent(remote)
            val remoteInfo = remote.offerComponent(this)
            map.connect(localInfo.component, localInfo.index, remoteInfo.component, remoteInfo.index)
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
    constructor(vararg objects: SimulationObject<*>) : this(objects.asList())

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

    companion object {
        val values: List<SimulationObjectType> = values().toList()
    }
}

interface ThermalBipole {
    val b1: ThermalMass
    val b2: ThermalMass
}

/**
 * Thermal object with two connection sides.
 * */
class ThermalBipoleObject<C : Cell>(
    cell: C,
    val map: PoleMap,
    override val b1: ThermalMass,
    override val b2: ThermalMass
) : ThermalObject<C>(cell), ThermalBipole, ThermalContactInfo {

    constructor(cell: C, map: PoleMap, d1: ThermalMassDefinition, d2: ThermalMassDefinition) : this(cell, map, d1(), d2())

    init {
        cell.environmentData.getOrNull<EnvironmentalTemperatureField>()?.readTemperature()?.also {
            b1.temperature = it
            b2.temperature = it
        }
    }

    override fun offerComponent(neighbour: ThermalObject<*>) = ThermalComponentInfo(
        when (map.evaluate(cell.locator, neighbour.cell.locator)) {
            Pole.Plus -> b1
            Pole.Minus -> b2
        }
    )

    override fun addComponents(simulator: Simulator) {
        simulator.add(b1)
        simulator.add(b2)
    }

    override fun getContactTemperature(other: Locator): Quantity<Temperature>? {
        val direction = map.evaluateOrNull(this.cell.locator, other)
            ?: return null

        return when(direction) {
            Pole.Plus -> b1.temperature
            Pole.Minus -> b2.temperature
        }
    }
}

/**
 * Generator model consisting of a Voltage Source + Resistor
 * */
open class VRGeneratorObject<C : Cell>(cell: Cell, val map: PoleMap) : ElectricalObject<Cell>(cell) {
    private val resistor = ComponentHolder {
        val result = Resistor()
        result.resistance = resistanceExact
        result
    }

    private val source = ComponentHolder {
        val result = VoltageSource()
        result.potential = potentialExact
        result
    }

    /**
     * Gets the exact resistance of the [resistor].
     * */
    var resistanceExact: Double = 1.0
        set(value) {
            field = value
            resistor.ifPresent { it.resistance = value }
        }

    /**
     * Gets the exact potential of the [resistor].
     * */
    var potentialExact: Double = 1.0
        set(value) {
            field = value
            source.ifPresent { it.potential = value }
        }

    /**
     * Updates the resistance if the deviation between the current resistance and [value] is larger than [eps].
     * This should be used instead of setting [resistanceExact] whenever possible, because setting the resistance is expensive.
     * @return True if the resistance was updated. Otherwise, false.
     * */
    fun updateResistance(value: Double, eps: Double = LIBAGE_SET_EPS): Boolean {
        if(resistanceExact.approxEq(value, eps)) {
            return false
        }

        resistanceExact = value

        return true
    }

    /**
     * Updates the potential if the deviation between the current potential and [value] is larger than [eps].
     * Using this instead of setting [potentialExact] doesn't have a large performance impact.
     * @return True if the voltage was updated. Otherwise, false.
     * */
    fun updatePotential(value: Double, eps: Double = LIBAGE_SET_EPS): Boolean {
        if(potentialExact.approxEq(value, eps)) {
            return false
        }

        potentialExact = value

        return true
    }

    val hasResistor get() = resistor.isPresent
    val hasSource get() = source.isPresent

    val resistorCurrent get() = if(resistor.isPresent) resistor.instance.current else 0.0
    val sourceCurrent get() = if(source.isPresent) source.instance.current else 0.0

    val resistorPower get() = if (resistor.isPresent) resistor.instance.power else 0.0
    val sourcePower get() = if (source.isPresent) source.instance.power else 0.0

    override val maxConnections = 2

    /**
     * Gets the offered component by evaluating the map.
     * @return The resistor's external pin when the pole evaluates to *plus*. The source's negative pin when the pole evaluates to *minus*.
     * */
    override fun offerComponent(neighbour: ElectricalObject<*>): ElectricalComponentInfo =
        when (map.evaluate(this.cell.locator, neighbour.cell.locator)) {
            Pole.Plus -> resistor.offerExternal()
            Pole.Minus -> source.offerNegative()
        }

    override fun clearComponents() {
        resistor.clear()
        source.clear()
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(resistor)
        circuit.add(source)
    }

    override fun build(map: ElectricalConnectivityMap) {
        resistor.connectInternal(source.offerPositive(), map)
        super.build(map)
    }
}

open class PVSObject<C : Cell>(cell: Cell, val map: PoleMap) : ElectricalObject<Cell>(cell) {
    private val source = ComponentHolder {
        PowerVoltageSource().also {
            it.potentialMax = potentialMaxExact
            it.powerIdeal = powerIdealExact
        }
    }

    var potentialMaxExact: Double = 0.0
        set(value) {
            field = value
            source.ifPresent { it.potentialMax = value }
        }

    var powerIdealExact: Double = 0.0
        set(value) {
            field = value
            source.ifPresent { it.powerIdeal = value }
        }

    fun updatePotentialMax(value: Double, eps: Double = LIBAGE_SET_EPS): Boolean {
        if(potentialMaxExact.approxEq(value, eps)) {
            return false
        }

        potentialMaxExact = value

        return true
    }

    fun updatePowerIdeal(value: Double, eps: Double = LIBAGE_SET_EPS): Boolean {
        if(powerIdealExact.approxEq(value, eps)) {
            return false
        }

        powerIdealExact = value

        return true
    }

    val hasSource get() = source.isPresent
    val sourceCurrent get() = if(source.isPresent) source.instance.current else 0.0
    val sourcePower get() = if (source.isPresent) source.instance.power else 0.0

    override val maxConnections = 2

    /**
     * Gets the offered component by evaluating the map.
     * @return The resistor's external pin when the pole evaluates to *plus*. The source's negative pin when the pole evaluates to *minus*.
     * */
    override fun offerComponent(neighbour: ElectricalObject<*>): ElectricalComponentInfo =
        when (map.evaluate(this.cell.locator, neighbour.cell.locator)) {
            Pole.Plus -> source.offerPositive()
            Pole.Minus -> source.offerNegative()
        }

    override fun clearComponents() {
        source.clear()
    }
}
