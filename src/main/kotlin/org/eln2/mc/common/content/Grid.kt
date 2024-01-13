package org.eln2.mc.common.content

import com.jozufozu.flywheel.core.PartialModel
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.VirtualResistor
import org.eln2.mc.*
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.blocks.foundation.BigBlockRepresentativeBlockEntity
import org.eln2.mc.common.blocks.foundation.CellBlock
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateMap
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridMaterialCategory
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.specs.foundation.CellSpec
import org.eln2.mc.common.blocks.foundation.GridCellBlockEntity
import org.eln2.mc.common.parts.foundation.GridCellPart
import org.eln2.mc.common.specs.foundation.SpecCreateInfo
import org.eln2.mc.data.UnsafeLazyResettable
import org.eln2.mc.extensions.toVector3d
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import java.util.function.Supplier
import kotlin.collections.HashMap
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
    private val anchorResistors = HashMap<GridConnectionCell, VirtualResistor>()

    val totalPower get() = anchorResistors.values.sumOf { abs(it.power) }
    val totalCurrent get() = anchorResistors.values.sumOf { abs(it.current) }

    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo) =
        anchorResistors.computeIfAbsent(gc) {
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

class GridAnchorCell(ci: CellCreateInfo, crossResistance: Double) : Cell(ci) {
    @Node
    val grid = GridNode(this)

    @SimObject
    val electricalAnchor = GridAnchorElectricalObject(this, crossResistance)

    override fun cellConnectionPredicate(remote: Cell): Boolean {
        return remote is GridConnectionCell && super.cellConnectionPredicate(remote)
    }
}

class GridAnchorSpec(ci: SpecCreateInfo, terminalSize: Vector3d, categories: List<GridMaterialCategory>) : CellSpec<GridAnchorCell, BasicSpecRenderer>(ci, Content.MICRO_GRID_ANCHOR_CELL.get()),
    ComponentDisplay {
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
    }
}

class GridInterfaceObject(cell: GridInterfaceCell, val tapResistance: Double, val anchorResistance: Double) : ElectricalObject<GridInterfaceCell>(cell) {
    private val tapResistor = UnsafeLazyResettable {
        val resistor = VirtualResistor()
        resistor.resistance = tapResistance
        resistor
    }

    private val anchorResistors = HashMap<GridConnectionCell, VirtualResistor>()

    // Is this useful?
    val totalCurrent get() = anchorResistors.values.sumOf { abs(it.current) }
    val totalPower get() = anchorResistors.values.sumOf { abs(it.power) }

    override fun offerPolar(remote: ElectricalObject<*>) = tapResistor.value.offerExternal()

    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo) =
        anchorResistors.computeIfAbsent(gc) {
            val resistor = VirtualResistor()
            resistor.resistance = anchorResistance
            resistor
        }.offerExternal()

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

class GridInterfaceCell(ci: CellCreateInfo, tapResistance: Double, anchorResistance: Double) : Cell(ci) {
    @Node
    val grid = GridNode(this)

    @SimObject
    val electricalInterface = GridInterfaceObject(this, tapResistance, anchorResistance)
}

class GridInterfacePart(ci: PartCreateInfo, terminalSize: Vector3d, categories: List<GridMaterialCategory>, val bodySupplier: Supplier<PartialModel>) : GridCellPart<GridInterfaceCell, ConnectedPartRenderer>(ci, Content.MICRO_GRID_INTERFACE_CELL.get()),
    ComponentDisplay {
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
    }
}

