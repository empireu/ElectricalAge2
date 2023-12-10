package org.eln2.mc.common.content

import org.eln2.mc.ElectricalComponentSet
import org.eln2.mc.ElectricalConnectivityMap
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.BasicPartRenderer
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.mathematics.Base6Direction3dMask

class GroundObject(cell: Cell) : ElectricalObject<Cell>(cell) {
    private val resistors = resistorBundle(0.01)

    val totalCurrent get() = resistors.totalCurrent
    val totalPower get() = resistors.totalPower

    var resistance: Double
        get() = resistors.resistance
        set(value) {
            resistors.resistance = value
        }

    override fun offerComponent(neighbour: ElectricalObject<*>): ElectricalComponentInfo {
        return resistors.getOfferedResistor(neighbour)
    }

    override fun clearComponents() {
        resistors.clear()
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        resistors.addComponents(connections, circuit)
    }

    override fun build(map: ElectricalConnectivityMap) {
        resistors.connect(connections, this, map)
        resistors.forEach { it.ground(INTERNAL_PIN) }
    }
}

class GroundCell(ci: CellCreateInfo) : Cell(ci) {
    @SimObject
    val ground = GroundObject(this)

    init {
        ruleSet.withDirectionRulePlanar(Base6Direction3dMask.FRONT)
    }
}

class GroundPart(ci: PartCreateInfo) : CellPart<GroundCell, BasicPartRenderer>(ci, Content.GROUND_CELL.get()), WrenchRotatablePart, ComponentDisplay {
    override fun createRenderer() = BasicPartRenderer(this, PartialModels.GROUND)

    override fun submitDisplay(builder: ComponentDisplayList) {
        runIfCell {
            builder.resistance(cell.ground.resistance)
            builder.current(cell.ground.totalCurrent)
            builder.power(cell.ground.totalPower)
        }
    }
}
