package org.eln2.mc.common.content

import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicPartVisual
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d

class ResistorCell(ci: CellCreateInfo) : Cell(ci), SidedElectricalBipole<ResistorCell> {
    override val side1: Base6Direction3d
        get() = Base6Direction3d.Front

    override val side2: Base6Direction3d
        get() = Base6Direction3d.Back

    override val electricalSize: ElectricalSize
        get() = ElectricalSize.Standard

    @SimObject
    val resistor = PolarResistorObject(this, directionPoleMapPlanar(side1, side2))

    @SimObject
    val thermalWire = ThermalWireObject(this)

    @Behavior
    val heating = PowerHeatingBehavior(resistor.component::power, thermalWire.thermalBody)
}

class ResistorPart(ci: PartCreateInfo) : CellPart<ResistorCell>(ci, Content.RESISTOR_CELL.get()), ComponentDisplay {
    override fun createVisual(ctx: MultipartVisualizationContext) = BasicPartVisual(ctx, this, FlwModels.RESISTOR)

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(cell.thermalWire.thermalBody.temperature)
        builder.quantity(cell.resistor.component.readouts.resistance)
        builder.quantity(cell.resistor.component.readouts.potential)
        builder.quantity(cell.resistor.component.readouts.current)
        builder.quantity(cell.resistor.component.readouts.power)
    }
}
