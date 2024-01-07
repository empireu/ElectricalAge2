package org.eln2.mc.common.content

import com.jozufozu.flywheel.core.PartialModel
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.nbt.CompoundTag
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.VirtualResistor
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.BasicSpecRenderer
import org.eln2.mc.client.render.foundation.ConnectedPartRenderer
import org.eln2.mc.client.render.foundation.RGBAFloat
import org.eln2.mc.client.render.foundation.handleConnectedPartTag
import org.eln2.mc.common.GridConnectionCell
import org.eln2.mc.common.GridMaterialCategory
import org.eln2.mc.common.GridNodeCell
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.specs.foundation.CellGridSpec
import org.eln2.mc.common.specs.foundation.MicroGridCellPart
import org.eln2.mc.common.specs.foundation.SpecCreateInfo
import org.eln2.mc.data.UnsafeLazyResettable
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.offerExternal
import java.util.function.Supplier
import kotlin.math.abs

class GridAnchorElectricalObject(cell: GridNodeCell, val anchorResistance: Double) : ElectricalObject<GridNodeCell>(cell) {
    private val anchorResistors = HashMap<ElectricalObject<*>, VirtualResistor>()

    val totalPower get() = anchorResistors.values.sumOf { abs(it.power) }
    val totalCurrent get() = anchorResistors.values.sumOf { abs(it.current) }

    override fun offerComponent(remote: ElectricalObject<*>) = anchorResistors.computeIfAbsent(remote) {
        val resistor = VirtualResistor()
        resistor.resistance = anchorResistance
        resistor
    }.offerExternal()

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)

        anchorResistors.values.forEach { a ->
            anchorResistors.values.forEach { b ->
                if(a != b) {
                    map.connect(a, INTERNAL_PIN, b, INTERNAL_PIN)
                }
            }
        }
    }

    override fun clearComponents() {
        anchorResistors.clear()
    }
}

class GridAnchorCell(ci: CellCreateInfo, crossResistance: Double) : GridNodeCell(ci) {
    @SimObject
    val electricalAnchor = GridAnchorElectricalObject(this, crossResistance)

    override fun cellConnectionPredicate(remote: Cell): Boolean {
        return remote is GridConnectionCell && super.cellConnectionPredicate(remote)
    }
}

class GridAnchorSpec(ci: SpecCreateInfo, terminalSize: Vector3d, categories: List<GridMaterialCategory>) : CellGridSpec<GridAnchorCell, BasicSpecRenderer>(ci, Content.MICROGRID_ANCHOR_CELL.get()), ComponentDisplay {
    val terminal = defineCellBoxTerminal(
        0.0, 0.0, 0.0,
        terminalSize.x, terminalSize.y, terminalSize.z,
        highlightColor = RGBAFloat(1.0f, 0.75f, 0.78f, 0.8f),
        categories = categories
    )

    override fun createRenderer(): BasicSpecRenderer {
        return BasicSpecRenderer(this, PartialModels.MICRO_GRID_ANCHOR)
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.current(cell.electricalAnchor.totalCurrent)
        builder.power(cell.electricalAnchor.totalPower)

        if(cell.hasGraph) {
            builder.debug("Gr: ${cell.graph.id}, c: ${cell.getGridMapping().size}")
        }
        else {
            builder.debug("No grphg")
        }
    }
}

class GridInterfaceObject(cell: GridInterfaceCell, val tapResistance: Double, val anchorResistance: Double) : ElectricalObject<GridInterfaceCell>(cell) {
    private val tapResistor = UnsafeLazyResettable {
        val resistor = VirtualResistor()
        resistor.resistance = tapResistance
        resistor
    }

    private val anchorResistors = HashMap<ElectricalObject<*>, VirtualResistor>()

    // Is this useful?
    val totalCurrent get() = anchorResistors.values.sumOf { abs(it.current) }
    val totalPower get() = anchorResistors.values.sumOf { abs(it.power) }

    override fun offerComponent(remote: ElectricalObject<*>) =
        if(remote.cell is GridConnectionCell) {
            anchorResistors.computeIfAbsent(remote) {
                val resistor = VirtualResistor()
                resistor.resistance = anchorResistance
                resistor
            }.offerExternal()
        }
        else {
            tapResistor.value.offerExternal()
        }

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)

        anchorResistors.values.forEach { a ->
            anchorResistors.values.forEach { b ->
                if(a !== b) {
                    map.connect(a, INTERNAL_PIN, b, INTERNAL_PIN)
                }
            }
        }

        if(tapResistor.isInitialized()) {
            anchorResistors.values.forEach {
                map.connect(it, INTERNAL_PIN, tapResistor.value, INTERNAL_PIN)
            }
        }
    }

    override fun clearComponents() {
        tapResistor.reset()
        anchorResistors.clear()
    }
}

class GridInterfaceCell(ci: CellCreateInfo, tapResistance: Double, anchorResistance: Double) : GridNodeCell(ci) {
    @SimObject
    val electricalInterface = GridInterfaceObject(this, tapResistance, anchorResistance)
}

class GridInterfacePart(ci: PartCreateInfo, terminalSize: Vector3d, categories: List<GridMaterialCategory>, val bodySupplier: Supplier<PartialModel>) : MicroGridCellPart<GridInterfaceCell, ConnectedPartRenderer>(ci, Content.MICROGRID_INTERFACE_CELL.get()), ComponentDisplay {
    val terminal = defineCellBoxTerminal(
        0.0, 0.0, 0.0,
        terminalSize.x, terminalSize.y, terminalSize.z,
        highlightColor = RGBAFloat(0.0f, 0.7f, 0.8f, 0.8f),
        categories = categories
    )

    override fun createRenderer() = ConnectedPartRenderer(
        this,
        bodySupplier.get(),
        PartialModels.STANDARD_CONNECTION
    )

    override fun getSyncTag() : CompoundTag {
        val tag = CompoundTag()

        val values = IntArrayList(2)

        for (remoteCell in this.cell.connections) {
            if(remoteCell is GridConnectionCell) {
                continue
            }

            val solution = getPartConnectionAsContactSectionConnectionOrNull(this.cell, remoteCell)
                ?: continue

            values.add(solution.value)
        }

        tag.putIntArray("connections", values)

        return tag
    }

    override fun handleSyncTag(tag: CompoundTag) = this.handleConnectedPartTag(tag)
    override fun onConnectivityChanged() = this.setSyncDirty()

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.current(cell.electricalInterface.totalCurrent)
        builder.power(cell.electricalInterface.totalPower)

        if(cell.hasGraph) {
            builder.debug("Gr: ${cell.graph.id}, c: ${cell.getGridMapping().size}")
        }
        else {
            builder.debug("No grphg")
        }
    }
}
