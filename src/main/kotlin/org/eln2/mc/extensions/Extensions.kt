package org.eln2.mc.extensions

import net.minecraft.nbt.CompoundTag
import org.ageseries.libage.data.MutableSetMapMultiMap
import org.eln2.mc.control.PIDController

/*
//FIXME where is multiblock manager? what is it?
inline fun <reified T : Cell> Level.getCellOrNull(mb: MultiblockManager, cellPosId: BlockPos): T? {
    val entity = this.getBlockEntity(mb.txIdWorld(cellPosId)) as? CellBlockEntity
        ?: return null

    return entity.cell as? T
}

inline fun <reified T : Cell> Level.getCell(mb: MultiblockManager, cellPosId: BlockPos): T =
    getCellOrNull(mb, cellPosId) ?: error("Cell was not present")
*/


fun Double.formattedPercentNormalized(decimals: Int = 2): String {
    return "${(this * 100.0).formatted(decimals)}%"
}

fun <K, V> MutableSetMapMultiMap<K, V>.bind(): MutableSetMapMultiMap<K, V> {
    val result = MutableSetMapMultiMap<K, V>()

    this.keys.forEach { k ->
        result[k].addAll(this[k])
    }

    return result
}

fun PIDController.stateToNbt() : CompoundTag {
    val tag = CompoundTag()
    tag.putDouble("errorSum", this.errorSum)
    tag.putDouble("lastError", this.lastError)
    return tag
}

fun PIDController.stateFromNbt(tag: CompoundTag) {
    this.errorSum = tag.getDouble("errorSum")
    this.lastError = tag.getDouble("lastError")
}



