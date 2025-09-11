package org.eln2.mc.extensions

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import org.ageseries.libage.data.MutableSetMapMultiMap
import org.eln2.mc.LOG
import org.eln2.mc.common.network.serverToClient.BlockEntityMessage
import org.eln2.mc.common.network.serverToClient.BulkMessageHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.BulkMessages
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

fun<T> T.enqueueBulkMessage(payload: ByteArray) where T : BlockEntity, T : BulkMessageHandlerBlockEntity {
    val level = this.level as? ServerLevel

    if(level == null) {
        LOG.error("The level of $this is null in enqueueBulkMessage")
        return
    }

    BulkMessages.enqueueBlockEntityMessage(level, BlockEntityMessage(this.blockPos, payload))
}

fun Boolean.toInt() = if(this) 1 else 0

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



