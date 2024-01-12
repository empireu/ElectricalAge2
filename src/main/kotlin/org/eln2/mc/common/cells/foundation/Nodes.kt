package org.eln2.mc.common.cells.foundation

import net.minecraft.nbt.CompoundTag

/**
 * Represents a child node of a cell, that has the same lifetime as the cell and receives the same events.
 * It also saves data, stored in the cell's tag.
 *
 * Not used directly! There are two versions which should be used: singleton and shard.
 * */
interface CellNode {
    /**
     * Saves the node to NBT.
     * @return The tag to save or null, if no data needs saving.
     * */
    fun saveNodeData(): CompoundTag? = null

    /**
     * Loads the saved tag.
     * @param tag The saved tag, as per [saveNodeData].
     * */
    fun loadNodeData(tag: CompoundTag)
}

/**
 * Represents a child node of a cell, that has the same lifetime as the cell and receives the same events.
 * Only one instance of this node may exist per cell. This makes it possible to address the storage by the type or class.
 * */
interface UniqueCellNode : CellNode

/**
 * Represents a child node of a cell, that has the same lifetime as the cell and receives the same events.
 * Multiple instances of this node may exist per cell.
 * */
interface RepeatableCellNode : CellNode
