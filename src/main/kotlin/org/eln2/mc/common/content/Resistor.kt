package org.eln2.mc.common.content

import org.ageseries.libage.sim.electrical.mna.VirtualResistor
import org.ageseries.libage.sim.electrical.mna.component.IResistor
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.BasicPartRenderer
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.offerNegative
import org.eln2.mc.offerPositive

class PolarResistorObjectVirtual<C : Cell>(cell: C, poleMap: PoleMap, virtualResistor: VirtualResistor) : PolarTermObject<C, VirtualResistor>(cell, poleMap, virtualResistor), IResistor by virtualResistor {
    constructor(cell: C, poleMap: PoleMap) : this(cell, poleMap, VirtualResistor())
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

class ResistorCell(ci: CellCreateInfo) : Cell(ci) {
    companion object {
        private val A = Base6Direction3d.Front
        private val B = Base6Direction3d.Back
    }

    init {
        ruleSet.withDirectionRulePlanar(A + B)
    }

    @SimObject
    val resistor = PolarResistorObjectVirtual(this, directionPoleMapPlanar(A, B))

    @SimObject
    val thermalWire = ThermalWireObject(this)

    @Behavior
    val heating = PowerHeatingBehavior(resistor::power, thermalWire.thermalBody)
}

class ResistorPart(ci: PartCreateInfo) : CellPart<ResistorCell, BasicPartRenderer>(ci, Content.RESISTOR_CELL.get()), ComponentDisplay {
    override fun createRenderer() = BasicPartRenderer(this, PartialModels.RESISTOR)

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(cell.thermalWire.thermalBody.temperature)
        builder.resistance(cell.resistor.resistance)
        builder.power(cell.resistor.power)
        builder.current(cell.resistor.current)
    }
}
