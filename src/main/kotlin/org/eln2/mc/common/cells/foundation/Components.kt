package org.eln2.mc.common.cells.foundation

import org.ageseries.libage.data.AMPERE
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT
import org.ageseries.libage.data.abs
import org.ageseries.libage.sim.electrical.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.Resistor
import org.eln2.mc.offerExternal
import kotlin.math.abs

open class ResistorBundle(val cell: Cell) {
    constructor(cell: Cell, resistance: Double) : this(cell) {
        this.resistance = resistance
    }

    private val resistors = HashMap<ElectricalObject<*>, Resistor>()
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
    fun build(connections: List<ElectricalObject<*>>, sender: ElectricalObject<*>, map: ElectricalConnectivityMap) {
        if (!prepared) {
            error("Not prepared")
        }

        for (remoteObj in connections) {
            val resistor = getResistor(remoteObj)
            val offered = remoteObj.offerComponent(sender)
                ?: continue
            map.join(resistor.offerExternal(), offered)
        }
    }

    private fun getResistor(remote: ElectricalObject<*>) = resistors.computeIfAbsent(remote) {
        if (prepared) {
            error("Tried to create resistors after bundle was prepared")
        }

        val result = Resistor()
        result.resistance = resistance
        result
    }

    /**
     * Gets a resistor for the specified direction. Subsequent calls will return the same resistor,
     * unless *clear* is called.
     * If a resistor is not initialized for *direction*, and the bundle was prepared by *register*, an error will be produced.
     * */
    fun getOfferedResistor(remote: ElectricalObject<*>) = getResistor(remote).offerExternal()

    /**
     * Iterates through all the initialized resistors.
     * Keep in mind that a resistor is initialized __after__ *getOfferedResistor* is called.
     * */
    fun forEach(action: ((Resistor) -> Unit)) {
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

    val totalCurrentSimulation get() = resistors.values.sumOf { abs(it.current) }
    val totalPowerSimulation get() = resistors.values.sumOf { abs(it.power) }

    val totalCurrentDisplay get() = Quantity(resistors.values.sumOf { !abs(it.readouts.current) }, AMPERE)
    val totalPowerDisplay get() = Quantity(resistors.values.sumOf { !abs(it.readouts.power) }, WATT)
}
