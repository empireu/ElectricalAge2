package org.eln2.mc.common.content

import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.Quantity
import org.eln2.mc.OnServerThread
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicPartVisual
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.parts.foundation.GridCellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.specs.foundation.CellSpec
import org.eln2.mc.common.specs.foundation.SpecCreateInfo
import org.eln2.mc.data.findDirActualPlanarOrNull
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d

class GroundCell(ci: CellCreateInfo) : Cell(ci), SidedElectricalFLBR<GroundCell> {
    override val electricalSize: ElectricalSize
        get() = ElectricalSize.Standard

    @SimObject
    val ground = GroundObject(self())

    @Node
    val grid = GridNode(self())

    @OnServerThread
    fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(Quantity(ground.resistors.resistance, OHM))
        builder.quantity(ground.resistors.totalCurrentDisplay)
        builder.quantity(ground.resistors.totalPowerDisplay)
    }
}

class GroundPart(ci: PartCreateInfo) : GridCellPart<GroundCell>(ci, Content.GROUND_CELL.get()), WrenchRotatable, ComponentDisplay {
    val terminal = defineCellBoxTerminal(
        0.0, 0.0, 0.0,
        placement.provider.placementCollisionSize.x,
        placement.provider.placementCollisionSize.y,
        placement.provider.placementCollisionSize.z
    )

    override fun createVisual(ctx: MultipartVisualizationContext) = BasicPartVisual(ctx, this, FlwModels.GROUND)

    override fun submitDisplay(builder: ComponentDisplayList) = cell.submitDisplay(builder)
}

class GroundSpec(ci: SpecCreateInfo) : CellSpec<GroundCell>(ci, Content.GROUND_CELL.get()), ComponentDisplay {
    val terminal = defineCellBoxTerminal(
        0.0, 0.0, 0.0,
        placement.provider.placementCollisionSize.x,
        placement.provider.placementCollisionSize.y,
        placement.provider.placementCollisionSize.z
    )

    override fun submitDisplay(builder: ComponentDisplayList) = cell.submitDisplay(builder)
}
