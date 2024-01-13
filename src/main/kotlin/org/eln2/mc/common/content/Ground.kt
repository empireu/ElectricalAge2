package org.eln2.mc.common.content

import org.ageseries.libage.sim.electrical.mna.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.BasicPartRenderer
import org.eln2.mc.client.render.foundation.BasicSpecRenderer
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.specs.foundation.CellSpec
import org.eln2.mc.common.parts.foundation.GridCellPart
import org.eln2.mc.common.specs.foundation.GridSpec
import org.eln2.mc.common.specs.foundation.SpecCreateInfo
import org.eln2.mc.data.findDirActualPlanarOrNull
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask

class GroundObject(cell: Cell) : ElectricalObject<Cell>(cell) {
    val resistors = resistorBundle(1e-5)

    override fun offerPolar(remote: ElectricalObject<*>) = resistors.getOfferedResistor(remote)
    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo) = resistors.getOfferedResistor(gc.electrical)

    override fun clearComponents() {
        resistors.clear()
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        resistors.addComponents(connections, circuit)
    }

    override fun build(map: ElectricalConnectivityMap) {
        resistors.build(connections, this, map)
        resistors.forEach {
            it.ground(INTERNAL_PIN)
        }
    }
}

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


class GroundPart(ci: PartCreateInfo) : GridCellPart<GroundCell, BasicPartRenderer>(ci, Content.GROUND_CELL.get()), WrenchRotatablePart, ComponentDisplay {
    val terminal = defineCellBoxTerminal(
        0.0, 0.0, 0.0,
        placement.provider.placementCollisionSize.x, placement.provider.placementCollisionSize.y, placement.provider.placementCollisionSize.z
    )

    override fun createRenderer() = BasicPartRenderer(this, PartialModels.GROUND)

    override fun submitDisplay(builder: ComponentDisplayList) = cell.submitDisplay(builder)
}

class GroundSpec(ci: SpecCreateInfo) : CellSpec<GroundCell, BasicSpecRenderer>(ci, Content.GROUND_CELL.get()), ComponentDisplay {
    val terminal = defineCellBoxTerminal(
        0.0, 0.0, 0.0,
        placement.provider.placementCollisionSize.x, placement.provider.placementCollisionSize.y, placement.provider.placementCollisionSize.z
    )

    override fun createRenderer() = BasicSpecRenderer(this, PartialModels.GROUND_MICRO_GRID)

    override fun submitDisplay(builder: ComponentDisplayList) = cell.submitDisplay(builder)
}
