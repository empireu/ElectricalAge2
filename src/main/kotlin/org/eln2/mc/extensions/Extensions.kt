package org.eln2.mc.extensions

import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import org.ageseries.libage.data.MutableSetMapMultiMap
import org.ageseries.libage.mathematics.rounded
import org.eln2.mc.KineticSimulation
import org.eln2.mc.LOG
import org.eln2.mc.SubSolverSet
import org.eln2.mc.common.network.serverToClient.BlockEntityMessage
import org.eln2.mc.common.network.serverToClient.BulkMessageHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.BulkMessages
import org.eln2.mc.control.PIDController
import org.eln2.mc.integration.ComponentDisplayList

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

fun SubSolverSet<KineticSimulation>.debugInIDE(builder: ComponentDisplayList) {
    this.solvers.forEachIndexed { index, solver ->
        builder.debugInIDE {
            "K$index: ${solver.lastIterationCount}i, " +
            "maxDL: ${solver.lastError.maxDeltaL.rounded(6)}, " +
            "maxR: ${solver.lastError.maxResidual.rounded(6)}, " +
            "n: ${solver.nodes.size} (${solver.optimizedShafts.size} opt), " +
            "c/r: ${solver.rigidConstraints.size}, " +
            "c/c: ${solver.clutchConstraints.size}"
        }
    }
}

inline fun<reified I> I.transformFacingBlock(visualPos: net.minecraft.core.BlockPos, blockEntity: BlockEntity) : I where I : TransformedInstance {
    this.translate(visualPos)
    this.center()
    this.rotateToFace(blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING))
    this.uncenter()
    return this
}

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



