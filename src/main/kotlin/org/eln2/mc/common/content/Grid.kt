package org.eln2.mc.common.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.common.GridMaterialCategory
import org.eln2.mc.common.blocks.foundation.BigBlockRepresentativeBlockEntity
import org.eln2.mc.common.blocks.foundation.CellBlock
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateMap
import org.eln2.mc.common.cells.foundation.CellProvider
import org.eln2.mc.common.specs.foundation.MicroGridCellBlockEntity
import org.eln2.mc.extensions.toVector3d

class GridPoleBlock(val delegateMap: MultiblockDelegateMap, val attachment: Vector3d, private val cellProvider: RegistryObject<CellProvider<GridAnchorCell>>) : CellBlock<GridAnchorCell>() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun skipRendering(pState: BlockState, pAdjacentState: BlockState, pDirection: Direction): Boolean {
        return true
    }

    override fun getCellProvider() = cellProvider.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = GridPoleBlockEntity(this, pPos, pState)
}

class GridPoleBlockEntity(private val representativeBlock: GridPoleBlock, pos: BlockPos, state: BlockState) :
    MicroGridCellBlockEntity<GridAnchorCell>(pos, state, Content.GRID_PASS_TROUGH_POLE_BLOCK_ENTITY.get()),
    BigBlockRepresentativeBlockEntity<GridPoleBlockEntity>
{
    override fun createTerminals() {
        defineCellBoxTerminal(
            0.0, 0.0, 0.0,
            1.0, 1.0, 1.0,
            attachment = blockPos.toVector3d() + representativeBlock.attachment,
            categories = listOf(GridMaterialCategory.BIG)
        )
    }

    override val delegateMap: MultiblockDelegateMap
        get() = representativeBlock.delegateMap

    override fun setDestroyed() {
        destroyDelegates()
        super.setDestroyed()
    }
}

