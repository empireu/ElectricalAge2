package org.eln2.mc.common.content

import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicPartVisual
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d

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

class ResistorPart(ci: PartCreateInfo) : CellPart<ResistorCell>(ci, Content.RESISTOR_CELL.get()), ComponentDisplay {
    override fun createVisual(ctx: MultipartVisualizationContext) = BasicPartVisual(ctx, this, FlwModels.RESISTOR)

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(cell.thermalWire.thermalBodyDisplay.temperature)
        builder.quantity(cell.resistor.resistorDisplay.resistance)
        builder.quantity(cell.resistor.resistorDisplay.potential)
        builder.quantity(cell.resistor.resistorDisplay.current)
        builder.quantity(cell.resistor.resistorDisplay.power)
    }
}
