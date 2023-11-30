package org.eln2.mc.common.cells.foundation

import org.ageseries.libage.sim.electrical.mna.component.Component
import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.eln2.mc.*
import kotlin.math.abs

// TODO Please remove
class ComponentHolder<T : Component>(private val factory: () -> T) {
    var value: T? = null
        private set

    val instance: T
        get() {
            if (value == null) {
                value = factory()
            }

            return value!!
        }

    fun connect(pin: Int, component: Any, remotePin: Int, map: ElectricalConnectivityMap) {
        map.connect(instance, pin, component, remotePin)
    }

    fun connect(pin: Int, componentInfo: ElectricalComponentInfo, map: ElectricalConnectivityMap) {
        map.connect(instance, pin, componentInfo.component, componentInfo.index)
    }

    fun connectInternal(component: Any, remotePin: Int, map: ElectricalConnectivityMap) {
        connect(INTERNAL_PIN, component, remotePin, map)
    }

    fun connectInternal(componentInfo: ElectricalComponentInfo, map: ElectricalConnectivityMap) {
        connectInternal(componentInfo.component, componentInfo.index, map)
    }

    fun connectExternal(component: Any, remotePin: Int, map: ElectricalConnectivityMap) {
        connect(EXTERNAL_PIN, component, remotePin, map)
    }

    fun connectExternal(componentInfo: ElectricalComponentInfo, map: ElectricalConnectivityMap) {
        connectExternal(componentInfo.component, componentInfo.index, map)
    }

    fun connectExternal(owner: ElectricalObject<*>, connection: ElectricalObject<*>, map: ElectricalConnectivityMap) {
        connectExternal(connection.offerComponent(owner), map)
    }

    fun connectPositive(component: Any, remotePin: Int, map: ElectricalConnectivityMap) {
        connect(POSITIVE_PIN, component, remotePin, map)
    }

    fun connectPositive(componentInfo: ElectricalComponentInfo, map: ElectricalConnectivityMap) {
        connectPositive(componentInfo.component, componentInfo.index, map)
    }

    fun connectPositive(owner: ElectricalObject<*>, connection: ElectricalObject<*>, map: ElectricalConnectivityMap) {
        connectPositive(connection.offerComponent(owner), map)
    }

    fun connectNegative(component: Any, remotePin: Int, map: ElectricalConnectivityMap) {
        connect(NEGATIVE_PIN, component, remotePin, map)
    }

    fun connectNegative(componentInfo: ElectricalComponentInfo, map: ElectricalConnectivityMap) {
        connectNegative(componentInfo.component, componentInfo.index, map)
    }

    fun connectNegative(owner: ElectricalObject<*>, connection: ElectricalObject<*>, map: ElectricalConnectivityMap) {
        connectNegative(connection.offerComponent(owner), map)
    }

    fun offerInternal(): ElectricalComponentInfo {
        return ElectricalComponentInfo(instance, INTERNAL_PIN)
    }

    fun offerExternal(): ElectricalComponentInfo {
        return ElectricalComponentInfo(instance, EXTERNAL_PIN)
    }

    fun offerPositive(): ElectricalComponentInfo {
        return ElectricalComponentInfo(instance, POSITIVE_PIN)
    }

    fun offerNegative(): ElectricalComponentInfo {
        return ElectricalComponentInfo(instance, NEGATIVE_PIN)
    }

    fun clear() {
        value = null
    }

    val isPresent get() = value != null

    val isNotPresent get() = value == null

    inline fun ifPresent(action: ((T) -> Unit)): Boolean {
        if (value == null) {
            return false
        }

        action(value!!)

        return true
    }
}

fun<T> ComponentHolder<T>.ground(pin: Int) where T : Component {
    this.instance.ground(pin)
}

fun<T> ComponentHolder<T>.groundInternal() where T : Component {
    this.ground(INTERNAL_PIN)
}

fun<T> ComponentHolder<T>.groundNegative() where T : Component  {
    this.ground(NEGATIVE_PIN)
}

fun<T> ComponentHolder<T>.groundExternal() where T : Component  {
    this.ground(EXTERNAL_PIN)
}

/**
 * Utility class that holds a collection of resistors to be used as contact points for external components.
 * */
open class ResistorLikeBundle<T : ResistorLike>(val factory: () -> T) {
    constructor(resistance: Double, factory: () -> T) : this(factory) {
        this.resistance = resistance
    }

    private val resistors = HashMap<ElectricalObject<*>, T>()

    private var prepared = false

    var resistance: Double = 1.0
        set(value) {
            if(field != value) {
                field = value
                resistors.values.forEach {
                    it.resistance = value
                }
            }
        }

    var crossResistance
        get() = resistance * 2.0
        set(value) { resistance = value / 2.0 }

    /**
     * This must be called once the circuit is made available, in order to register the resistors.
     * This "prepares" the bundle, so future calls to *getOfferedResistor* that result in a new resistor being created will cause an error.
     * @see ElectricalObject.addComponents
     * */
    fun addComponents(connections: List<ElectricalObject<*>>, circuit: ElectricalComponentSet) {
        if (prepared) {
            error("Already prepared")
        }

        connections.forEach {
            val resistor = getResistor(it)
            circuit.add(resistor)
        }

        prepared = true
    }

    /**
     * This must be called after "prepare", to finalize connections.
     * @see ElectricalObject.build
     * */
    fun connect(connections: List<ElectricalObject<*>>, sender: ElectricalObject<*>, map: ElectricalConnectivityMap) {
        if (!prepared) {
            error("Not prepared")
        }

        connections.forEach { remoteObj ->
            val resistor = getResistor(remoteObj)
            val offered = remoteObj.offerComponent(sender)
            map.connect(resistor, EXTERNAL_PIN, offered.component, offered.index)
        }
    }

    private fun getResistor(remote: ElectricalObject<*>): T {
        return resistors.computeIfAbsent(remote) {
            if (prepared) {
                error("Tried to create resistors after bundle was prepared")
            }

            val result = factory()
            result.resistance = resistance
            result
        }
    }

    /**
     * Gets a resistor for the specified direction. Subsequent calls will return the same resistor,
     * unless *clear* is called.
     * If a resistor is not initialized for *direction*, and the bundle was prepared by *register*, an error will be produced.
     * */
    fun getOfferedResistor(remote: ElectricalObject<*>): ElectricalComponentInfo {
        return ElectricalComponentInfo(getResistor(remote), EXTERNAL_PIN)
    }

    /**
     * Iterates through all the initialized resistors.
     * Keep in mind that a resistor is initialized __after__ *getOfferedResistor* is called.
     * */
    fun forEach(action: ((T) -> Unit)) {
        resistors.values.forEach { action(it) }
    }

    /**
     * Clears the resistors and marks the bundle as *unprepared*.
     * @see ElectricalObject.clear
     * */
    fun clear() {
        resistors.clear()
        prepared = false
    }

    val totalCurrent get() = resistors.values.sumOf { abs(it.current) }
    val totalPower get() = resistors.values.sumOf { abs(it.power) }
}

fun resistorBundle() = ResistorLikeBundle { ResistorLikeResistor() }
fun resistorVirtualBundle() = ResistorLikeBundle { ResistorVirtual() }
fun resistorBundle(resistance: Double) = ResistorLikeBundle(resistance) { ResistorLikeResistor() }
fun resistorVirtualBundle(resistance: Double) = ResistorLikeBundle(resistance) { ResistorVirtual() }
