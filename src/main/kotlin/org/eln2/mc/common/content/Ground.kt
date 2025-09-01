package org.eln2.mc.common.content

import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicPartVisual
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.parts.foundation.GridCellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.specs.foundation.CellSpec
import org.eln2.mc.common.specs.foundation.SpecCreateInfo
import org.eln2.mc.data.findDirActualPlanarOrNull
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d

class GroundCell(ci: CellCreateInfo) : Cell(ci) {
    @SimObject
    val ground = GroundObject(self())

    @Node
    val grid = GridNode(self())

    override fun cellConnectionPredicate(remote: Cell): Boolean {
        return super.cellConnectionPredicate(remote) && (remote.hasNode<GridNode>() || locator.findDirActualPlanarOrNull(remote.locator) == Base6Direction3d.Front)
    }

    fun submitDisplay(builder: ComponentDisplayList) {
        builder.resistance(ground.resistors.resistance)
        builder.current(ground.resistors.totalCurrent)
        builder.power(ground.resistors.totalPower)
    }
}

class GroundPart(ci: PartCreateInfo) : GridCellPart<GroundCell>(ci, Content.GROUND_CELL.get()), WrenchRotatablePart, ComponentDisplay {
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
