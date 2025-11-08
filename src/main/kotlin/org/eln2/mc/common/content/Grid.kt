package org.eln2.mc.common.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.AMPERE
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT
import org.ageseries.libage.data.abs
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.electrical.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.Resistor
import org.eln2.mc.ClientOnly
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.blocks.foundation.BigBlockRepresentativeBlockEntity
import org.eln2.mc.common.blocks.foundation.CellBlock
import org.eln2.mc.common.blocks.foundation.GridCellBlockEntity
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateMap
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridMaterialCategory
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.parts.foundation.GridCellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.specs.foundation.CellSpec
import org.eln2.mc.common.specs.foundation.SpecCreateInfo
import org.eln2.mc.data.UnsafeLazyResettable
import org.eln2.mc.extensions.toVector3d
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.offerExternal
import org.eln2.mc.offerInternal
import kotlin.math.abs

class GridPoleBlock(val delegateMap: MultiblockDelegateMap, val attachment: Vector3d, private val cellProvider: RegistryObject<CellProvider<GridAnchorCell>>) : CellBlock<GridAnchorCell>() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun skipRendering(pState: BlockState, pAdjacentState: BlockState, pDirection: Direction): Boolean {
        return true
    }

    override fun getCellProvider() = cellProvider.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = GridPoleBlockEntity(this, pPos, pState)
}

class GridPoleBlockEntity(private val representativeBlock: GridPoleBlock, pos: BlockPos, state: BlockState) :
    GridCellBlockEntity<GridAnchorCell>(pos, state, Content.GRID_PASS_THROUGH_POLE_BLOCK_ENTITY.get()),
    BigBlockRepresentativeBlockEntity<GridPoleBlockEntity>
{
    override fun createTerminals() {
        defineCellBoxTerminal(
            0.0, 0.0, 0.0,
            1.0, 1.0, 1.0,
            attachment = blockPos.toVector3d() + representativeBlock.attachment,
            categories = listOf(GridMaterialCategory.PowerGrid)
        )
    }

    override val delegateMap: MultiblockDelegateMap
        get() = representativeBlock.delegateMap

    override fun setDestroyed() {
        destroyDelegates()
        super.setDestroyed()
    }
}

class GridAnchorElectricalObject(cell: Cell, val anchorResistance: Double) : ElectricalObject<Cell>(cell) {
    private val anchorResistors = HashMap<GridConnectionCell, Resistor>()

    val totalPowerDisplay get() = anchorResistors.values.sumOf { !abs(it.readouts.power) }
    val totalCurrentDisplay get() = anchorResistors.values.sumOf { !abs(it.readouts.current) }

    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo) =
        anchorResistors.computeIfAbsent(gc) {
            val resistor = Resistor()
            resistor.resistance = anchorResistance
            resistor
        }.offerExternal()

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)

        anchorResistors.values.forEach { a ->
            anchorResistors.values.forEach { b ->
                if(a != b) {
                    map.join(a.offerInternal(), b.offerInternal())
                }
            }
        }
    }

    override fun clearComponents() {
        anchorResistors.clear()
    }
}

class GridAnchorCell(ci: CellCreateInfo, crossResistance: Double) : Cell(ci) {
    override val isExclusivelyGridConnected: Boolean
        get() = true

    @Node
    val grid = GridNode(this)

    @SimObject
    val electricalAnchor = GridAnchorElectricalObject(this, crossResistance)
}

class GridAnchorSpec(ci: SpecCreateInfo, terminalSize: Vector3d, categories: List<GridMaterialCategory>) : CellSpec<GridAnchorCell>(ci, Content.MICRO_GRID_ANCHOR_CELL.get()),
    ComponentDisplay {
    val terminal = defineCellBoxTerminal(
        0.0, 0.0, 0.0,
        terminalSize.x, terminalSize.y, terminalSize.z,
        highlightColor = MyColor(0.8f, 1.0f, 0.75f, 0.78f),
        categories = categories
    )

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(Quantity(cell.electricalAnchor.totalCurrentDisplay, AMPERE))
        builder.quantity(Quantity(cell.electricalAnchor.totalPowerDisplay, WATT))
    }
}

class GridInterfaceObject(cell: GridInterfaceCell, val tapResistance: Double, val anchorResistance: Double) : ElectricalObject<GridInterfaceCell>(cell) {
    private val tapResistor = UnsafeLazyResettable {
        val resistor = Resistor()
        resistor.resistance = tapResistance
        resistor
    }

    private val anchorResistors = HashMap<GridConnectionCell, Resistor>()

    // Is this useful?
    val totalCurrent get() = anchorResistors.values.sumOf { abs(it.current) } + if(tapResistor.isInitialized()) abs(!tapResistor.value.readouts.current) else 0.0
    val totalPower get() = anchorResistors.values.sumOf { abs(it.power) } + if(tapResistor.isInitialized()) abs(!tapResistor.value.readouts.power) else 0.0

    override fun offerPolar(remote: ElectricalObject<*>) = tapResistor.value.offerExternal()

    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo) =
        anchorResistors.computeIfAbsent(gc) {
            val resistor = Resistor()
            resistor.resistance = anchorResistance
            resistor
        }.offerExternal()

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)

        anchorResistors.values.forEach { a ->
            anchorResistors.values.forEach { b ->
                if(a !== b) {
                    map.join(a.offerInternal(), b.offerInternal())
                }
            }
        }

        if(tapResistor.isInitialized()) {
            anchorResistors.values.forEach {
                map.join(it.offerInternal(), tapResistor.value.offerInternal())
            }
        }
    }

    override fun clearComponents() {
        tapResistor.reset()
        anchorResistors.clear()
    }
}

class GridInterfaceCell(
    ci: CellCreateInfo,
    tapResistance: Double,
    anchorResistance: Double,
    override val electricalSize: ElectricalSize
) : Cell(ci), SidedElectricalFLBR<GridInterfaceCell> {
    @Node
    val grid = GridNode(this)

    @SimObject
    val electricalInterface = GridInterfaceObject(this, tapResistance, anchorResistance)
}

class GridInterfacePart(
    ci: PartCreateInfo,
    terminalSize: Vector3d,
    categories: List<GridMaterialCategory>,
    cell: RegistryObject<CellProvider<GridInterfaceCell>>
) : GridCellPart<GridInterfaceCell>(ci, cell.get()), ComponentDisplay, ConnectedPart {
    val terminal = defineCellBoxTerminal(
        0.0, 0.0, 0.0,
        terminalSize.x, terminalSize.y, terminalSize.z,
        highlightColor = MyColor(0.8f, 0.0f, 0.7f, 0.8f),
        categories = categories
    )

    @ClientOnly
    private var renderStateImpl = ConnectedPartRenderStateImpl.createIfApplicable(this)

    @ClientOnly
    override val connectedRenderState: ConnectedPartRenderState get() = renderStateImpl!!

    override fun getSyncTag() = ConnectedPart.pack(this)

    override fun handleSyncTag(tag: CompoundTag) = renderStateImpl!!.set(getConnectedPartsFromTag(tag))

    override fun onConnectivityChanged() = this.setSyncDirty()

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(Quantity(cell.electricalInterface.totalCurrent, AMPERE))
        builder.quantity(Quantity(cell.electricalInterface.totalPower, WATT))
    }
}

