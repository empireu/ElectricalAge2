package org.eln2.mc.common.content.processing

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.ageseries.libage.data.requireLocator
import org.ageseries.libage.mathematics.rounded
import org.eln2.mc.ServerOnly
import org.eln2.mc.common.blocks.foundation.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.PoleMap
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.Locators
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList

/**
 * Proxy cell for the electrolysis port blocks. Acts as a passthrough between the wire and the main cell.
 */
class ElectrolysisProxyCell(
    ci: CellCreateInfo,
    override val electricalMap: PoleMap,
    override val electricalSize: ElectricalSize,
) : Cell(ci), SidedElectricalMapped<ElectrolysisProxyCell> {
    companion object {
        const val PROXY_RESISTANCE = 1e-4
    }

    // Could be done without the proxy resistors, but I feel like it's cleaner this way.
    // The resistors themselves get optimized away anyway.

    @SimObject
    val resistor = PolarResistorObject(this, electricalMap).also {
        it.component.resistance = PROXY_RESISTANCE
    }
}

/**
 * Main electrolysis cell.
 * Contains the load resistor that represents the actual electrolysis process.
 */
class ElectrolysisCell(
    ci: CellCreateInfo,
    override val electricalMap: PoleMap,
    override val electricalSize: ElectricalSize,
) : Cell(ci), SidedElectricalMapped<ElectrolysisCell> {

    @SimObject
    val resistor = PolarResistorObject(this, electricalMap).also {
        it.component.resistance = 100.0
    }
}

class ElectrolysisProxyBlock : MultiblockDelegateUprightHorizontalDirectionCellBlock<ElectrolysisProxyCell>() {
    @Deprecated("Deprecated in Java")
    override fun skipRendering(pState: BlockState, pAdjacentState: BlockState, pDirection: Direction) = true

    override fun getCellProvider() = Eln2Processing.ELECTROLYSIS_PROXY_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = ElectrolysisProxyBlockEntity(pPos, pState)
}

class ElectrolysisProxyBlockEntity(pPos: BlockPos, pBlockState: BlockState) :
    MultiblockDelegateCellBlockEntity<ElectrolysisProxyCell>(
        pPos, pBlockState, Eln2Processing.ELECTROLYSIS_PROXY_BLOCK_ENTITY.get()
    )
{
    // Empty
}

class ElectrolysisMainBlock : UprightHorizontalDirectionCellBlock<ElectrolysisCell>() {
    override fun getCellProvider() = Eln2Processing.ELECTROLYSIS_MAIN_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = ElectrolysisMainBlockEntity(pPos, pState)

    override fun spatialNeighborScan(level: Level, results: HashSet<CellAndContainerHandle>, cell: Cell) {
        val pos = cell.locator.requireLocator(Locators.BLOCK)

        val blockEntity = level.getBlockEntity(pos) as? ElectrolysisMainBlockEntity
            ?: return

        val facing = blockEntity.representativeFacing

        blockEntity.delegateMap.forEachDelegateInWorld(facing, pos) { delegatePos ->
            val delegate = level.getBlockEntity(delegatePos) as? ElectrolysisProxyBlockEntity
                ?: return@forEachDelegateInWorld

            results.add(CellAndContainerHandle.captureInScope(delegate.cell))
        }
    }
}

class ElectrolysisMainBlockEntity(pPos: BlockPos, pBlockState: BlockState) :
    CellBlockEntity<ElectrolysisCell>(
        pPos, pBlockState, Eln2Processing.ELECTROLYSIS_MAIN_BLOCK_ENTITY.get(),
    ),
    BigBlockRepresentativeBlockEntity<ElectrolysisMainBlockEntity>,
    ComponentDisplay
{
    override val delegateMap: MultiblockDelegateMap
        get() = Eln2Processing.ELECTROLYSIS_DELEGATE_MAP.value

    @ServerOnly
    override fun setDestroyed() {
        destroyDelegates()
        super.setDestroyed()
    }

    override fun saveAdditional(pTag: net.minecraft.nbt.CompoundTag) {
        super.saveAdditional(pTag)
    }

    override fun load(pTag: net.minecraft.nbt.CompoundTag) {
        super.load(pTag)
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Main resistor: ${cell.resistor.component.power.rounded()}" }
    }
}
