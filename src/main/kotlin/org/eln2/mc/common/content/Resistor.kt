package org.eln2.mc.common.content

import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.eln2.mc.*
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.BasicPartRenderer
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.*
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.mathematics.Base6Direction3d

@NoInj
class ResistorObject(cell: Cell, val poleMap: PoleMap) : ElectricalObject<Cell>(cell) {
    private val resistor = ComponentHolder {
        Resistor().also { it.resistance = resistanceExact }
    }

    var resistanceExact: Double = 1.0
        set(value) {
            field = value
            resistor.ifPresent { it.resistance = value }
        }

    /**
     * Updates the resistance if the deviation between the current resistance and [value] is larger than [eps].
     * @return True if the resistance was updated. Otherwise, false.
     * */
    fun updateResistance(value: Double, eps: Double = LIBAGE_SET_EPS): Boolean {
        if(resistanceExact.approxEq(value, eps)) {
            return false
        }

        resistanceExact = value

        return true
    }

    val hasResistor get() = resistor.isPresent

    val current get() = if(resistor.isPresent) resistor.instance.current else 0.0
    val power get() = if(resistor.isPresent) resistor.instance.power else 0.0
    val potential get() = if(resistor.isPresent) resistor.instance.potential else 0.0

    override val maxConnections = 2

    override fun offerComponent(neighbour: ElectricalObject<*>) = ElectricalComponentInfo(
        resistor.instance,
        poleMap.evaluate(cell.locator, neighbour.cell.locator).conventionalPin
    )

    override fun clearComponents() = resistor.clear()

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(resistor)
    }
}

@NoInj
class ResistorObjectVirtual(cell: Cell, val poleMap: PoleMap) : ElectricalObject<Cell>(cell) {
    private val resistor = ResistorVirtual()

    var resistanceExact by resistor::resistance

    /**
     * Updates the resistance if the deviation between the current resistance and [value] is larger than [eps].
     * @return True if the resistance was updated. Otherwise, false.
     * */
    fun updateResistance(value: Double, eps: Double = LIBAGE_SET_EPS): Boolean {
        if(resistanceExact.approxEq(value, eps)) {
            return false
        }

        resistanceExact = value

        return true
    }

    val current get() = Double.NaN
    val power get() = resistor.power
    val potential get() = Double.NaN

    override val maxConnections = 2

    override fun offerComponent(neighbour: ElectricalObject<*>) = ElectricalComponentInfo(
        resistor,
        poleMap.evaluate(cell.locator, neighbour.cell.locator).conventionalPin
    )

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(resistor)
    }
}

class ResistorCell(ci: CellCreateInfo) : Cell(ci) {
    companion object {
        private val A = Base6Direction3d.Front
        private val B = Base6Direction3d.Back
    }

    init {
        ruleSet.withDirectionRulePlanar(A + B)
    }

    @SimObject @Inspect
    val resistor = ResistorObjectVirtual(this, directionPoleMapPlanar(A, B))

    @SimObject @Inspect
    val thermalWire = ThermalWireObject(this)

    @Behavior
    val heating = PowerHeatingBehavior({ resistor.power }, thermalWire.thermalBody)
}

class ResistorPart(ci: PartCreateInfo) : CellPart<ResistorCell, BasicPartRenderer>(ci, Content.RESISTOR_CELL.get()), ComponentDisplay {
    override fun createRenderer() = BasicPartRenderer(this, PartialModels.RESISTOR)

    override fun submitDisplay(builder: ComponentDisplayList) {
        runIfCell {
            builder.power(cell.resistor.power)
        }
    }
}
