package org.eln2.mc.extensions

import com.google.gson.JsonObject
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.GsonHelper
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import org.ageseries.libage.data.MutableSetMapMultiMap
import org.ageseries.libage.data.OptionalDouble
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.SubSolverSet
import org.ageseries.libage.sim.kinetic.KineticNode
import org.ageseries.libage.sim.kinetic.KineticSimulation
import org.eln2.mc.LOG
import org.eln2.mc.common.network.serverToClient.BlockEntityMessage
import org.eln2.mc.common.network.serverToClient.BulkMessageHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.BulkMessages
import org.eln2.mc.PIDController
import org.eln2.mc.integration.ComponentDisplayList
import org.joml.Vector3f

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

fun FriendlyByteBuf.writeDoubleArray(values: DoubleArray) {
    this.writeVarInt(values.size)

    for (i in 0 until values.size) {
        this.writeDouble(values[i])
    }
}

fun FriendlyByteBuf.readDoubleArray() : DoubleArray {
    val size = this.readVarInt()
    val result = DoubleArray(size)

    for (i in 0 until size) {
        result[i] = this.readDouble()
    }

    return result
}

fun FriendlyByteBuf.writeFloatArray(values: FloatArray) {
    this.writeVarInt(values.size)

    for (i in 0 until values.size) {
        this.writeFloat(values[i])
    }
}

fun FriendlyByteBuf.readFloatArray() : FloatArray {
    val size = this.readVarInt()
    val result = FloatArray(size)

    for (i in 0 until size) {
        result[i] = this.readFloat()
    }

    return result
}

fun FriendlyByteBuf.writeOptionalDouble(value: OptionalDouble) {
    if(value.isPresent) {
        this.writeDouble(value.unwrap())
    }
    else {
        this.writeDouble(Double.NaN)
    }
}

fun FriendlyByteBuf.readOptionalDouble() : OptionalDouble {
    val value = this.readDouble()

    return if(value.isNaN()) {
        OptionalDouble.EMPTY
    }
    else {
        OptionalDouble.wrap(value)
    }
}

fun JsonObject.getBool(memberName: String): Boolean = GsonHelper.getAsBoolean(this, memberName)
fun JsonObject.getString(memberName: String): String = GsonHelper.getAsString(this, memberName)
fun JsonObject.getInt(memberName: String): Int = GsonHelper.getAsInt(this, memberName)
fun JsonObject.getDouble(memberName: String): Double = GsonHelper.getAsDouble(this, memberName)
fun JsonObject.getResourceLocation(memberName: String): ResourceLocation = ResourceLocation.parse(GsonHelper.getAsString(this, memberName))

fun JsonObject.getBool(memberName: String, fallback: Boolean): Boolean = GsonHelper.getAsBoolean(this, memberName, fallback)
fun JsonObject.getString(memberName: String, fallback: String): String = GsonHelper.getAsString(this, memberName, fallback)!!
fun JsonObject.getInt(memberName: String, fallback: Int): Int = GsonHelper.getAsInt(this, memberName, fallback)
fun JsonObject.getDouble(memberName: String, fallback: Double): Double = GsonHelper.getAsDouble(this, memberName, fallback)

fun Vector3f.toVector3d() = Vector3d(this.x.toDouble(), this.y.toDouble(), this.z.toDouble())
fun Vector3d.toVector3f() = Vector3f(this.x.toFloat(), this.y.toFloat(), this.z.toFloat())

inline fun<T> JsonObject.getNullable(memberName: String, function: (memberName: String) -> T) : T? {
    if(this.has(memberName)) {
        return function(memberName)
    }

    return null
}

inline fun<T> JsonObject.mapNullable(memberName: String, function: (obj: JsonObject) -> T) : T? {
    if(this.has(memberName)) {
        val obj = this.getAsJsonObject(memberName)
        return function(obj)
    }

    return null
}

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

inline fun<reified I> I.transformFacingBlock(visualPos: BlockPos, blockEntity: BlockEntity) : I where I : TransformedInstance {
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


fun KineticNode.saveNbt() = CompoundTag().also {
    it.putDouble("angle", this.angle)
    it.putDouble("omega", this.angularVelocity)
}

fun KineticNode.loadNbt(tag: CompoundTag) {
    this.setExternalAngle(tag.getDouble("angle"))
    this.angularVelocity = tag.getDouble("omega")
}
