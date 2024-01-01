package org.eln2.mc.common.content

import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.nbt.CompoundTag
import org.ageseries.libage.data.CENTIMETER
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.VirtualResistor
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.specs.foundation.*
import org.eln2.mc.data.UnsafeLazyResettable
import org.eln2.mc.data.cylinderResistance
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.offerExternal
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow

class MicroGridAnchorObject(cell: MicroGridCell) : ElectricalObject<MicroGridCell>(cell),
    MicroGridHubObject<MicroGridAnchorObject, MicroGridCell> {
    private val resistors = HashMap<ElectricalObject<*>, VirtualResistor>()

    val totalPower get() = resistors.values.sumOf { abs(it.power) }
    val totalCurrent get() = resistors.values.sumOf { abs(it.current) }

    override fun offerComponent(remote: ElectricalObject<*>) = resistors.computeIfAbsent(remote) {
        val resistor = VirtualResistor()
        resistor.resistance = microgridResistanceTo(remote)
        resistor
    }.offerExternal()

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)

        resistors.values.forEach { a ->
            resistors.values.forEach { b ->
                if(a != b) {
                    map.connect(a, INTERNAL_PIN, b, INTERNAL_PIN)
                }
            }
        }
    }

    override fun clearComponents() {
        resistors.clear()
    }
}

class MicroGridAnchorCell(ci: CellCreateInfo) : MicroGridCell(ci) {
    @SimObject
    val electricalAnchor = MicroGridAnchorObject(this)
}

class MicroGridAnchorSpec(ci: SpecCreateInfo) : CellMicroGridSpec<MicroGridAnchorCell, BasicSpecRenderer>(ci, Content.MICROGRID_ANCHOR_CELL.get()), ComponentDisplay {
    val terminal = defineCellBoxTerminal(
        0.0, 0.0, 0.0,
        2.0 / 16.0, 4.0 / 16.0, 2.0 / 16.0,
        highlightColor = RGBAFloat(1.0f, 0.75f, 0.78f, 0.8f)
    )

    override fun createRenderer(): BasicSpecRenderer {
        return BasicSpecRenderer(this, PartialModels.MICRO_GRID_ANCHOR)
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.current(cell.electricalAnchor.totalCurrent)
        builder.power(cell.electricalAnchor.totalPower)
    }
}

class MicroGridInterfaceObject(cell: MicroGridInterfaceCell) : ElectricalObject<MicroGridInterfaceCell>(cell),
    MicroGridHubObject<MicroGridInterfaceObject, MicroGridInterfaceCell> {
    private val tapResistor = UnsafeLazyResettable {
        VirtualResistor().also {
            it.resistance = !ChemicalElement.Copper.asMaterial.electricalResistivity.cylinderResistance(
                L = Quantity(10.0, CENTIMETER),
                A = Quantity(PI * Quantity(5.0, CENTIMETER).value.pow(2))
            )
        }
    }

    private val gridResistors = HashMap<ElectricalObject<*>, VirtualResistor>()

    // Is this useful?
    val totalCurrent get() = gridResistors.values.sumOf { abs(it.current) }
    val totalPower get() = gridResistors.values.sumOf { abs(it.power) }

    override fun offerComponent(remote: ElectricalObject<*>) =
        if(cell.isMicroGrid(remote.cell)) {
            gridResistors.computeIfAbsent(remote) {
                val resistor = VirtualResistor()
                resistor.resistance = microgridResistanceTo(remote)
                resistor
            }.offerExternal()
        }
        else {
            tapResistor.value.offerExternal()
        }

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)

        gridResistors.values.forEach { a ->
            gridResistors.values.forEach { b ->
                if(a !== b) {
                    map.connect(a, INTERNAL_PIN, b, INTERNAL_PIN)
                }
            }
        }

        if(tapResistor.isInitialized()) {
            gridResistors.values.forEach {
                map.connect(it, INTERNAL_PIN, tapResistor.value, INTERNAL_PIN)
            }
        }
    }

    override fun clearComponents() {
        tapResistor.reset()
        gridResistors.clear()
    }
}

class MicroGridInterfaceCell(ci: CellCreateInfo) : MicroGridCell(ci) {
    @SimObject
    val electricalInterface = MicroGridInterfaceObject(this)

    override fun buildFinished() {
        // Do not validate using grid
    }

    override fun cellConnectionPredicate(remote: Cell) = true
}

class MicroGridInterfacePart(ci: PartCreateInfo) : MicroGridCellPart<MicroGridInterfaceCell, ConnectedPartRenderer>(ci, Content.MICROGRID_INTERFACE_CELL.get()), ComponentDisplay {
    val terminal = defineCellBoxTerminal(
        0.0, 0.0, 0.0,
        2.0 / 16.0, 4.0 / 16.0, 2.0 / 16.0,
        highlightColor = RGBAFloat(0.0f, 0.7f, 0.8f, 0.8f)
    )

    override fun createRenderer() = ConnectedPartRenderer(
        this,
        PartialModels.MICRO_GRID_INTERFACE,
        PartialModels.STANDARD_CONNECTION
    )

    override fun getSyncTag() : CompoundTag {
        val tag = CompoundTag()

        val values = IntArrayList(2)

        for (remoteCell in this.cell.connections) {
            if(this.cell.isMicroGrid(remoteCell)) {
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
    }
}
