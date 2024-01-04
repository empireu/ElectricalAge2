package org.eln2.mc.extensions

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.ageseries.libage.data.Locator
import org.ageseries.libage.mathematics.geometry.Pose2d
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.common.parts.foundation.CellPartConnectionMode
import org.eln2.mc.common.parts.foundation.PartUpdateType
import org.eln2.mc.common.specs.foundation.SpecUpdateType
import org.eln2.mc.data.Locators
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.FacingDirection

fun CompoundTag.putVector3d(key: String, v: Vector3d) {
    val tag = CompoundTag()
    tag.putDouble("X", v.x)
    tag.putDouble("Y", v.y)
    tag.putDouble("Z", v.z)
    this.put(key, tag)
}

fun CompoundTag.getVector3d(key: String) : Vector3d {
    val tag = this.get(key) as CompoundTag
    val x = tag.getDouble("X")
    val y = tag.getDouble("Y")
    val z = tag.getDouble("Z")

    return Vector3d(x, y, z)
}

fun CompoundTag.putRotation2d(key: String, r: Rotation2d) {
    val tag = CompoundTag()
    tag.putDouble("Re", r.re)
    tag.putDouble("Im", r.im)
    this.put(key, tag)
}

fun CompoundTag.getRotation2d(key: String) : Rotation2d {
    val tag = this.get(key) as CompoundTag
    val re = tag.getDouble("Re")
    val im = tag.getDouble("Im")

    return Rotation2d(re, im)
}

fun CompoundTag.putPose2d(key: String, p: Pose2d) {
    val tag = CompoundTag()
    tag.putDouble("X", p.translation.x)
    tag.putDouble("Y", p.translation.y)
    tag.putDouble("Re", p.rotation.re)
    tag.putDouble("Im", p.rotation.im)
    this.put(key, tag)
}

fun CompoundTag.getPose2d(key: String) : Pose2d {
    val tag = this.get(key) as CompoundTag

    val x = tag.getDouble("X")
    val y = tag.getDouble("Y")
    val re = tag.getDouble("Re")
    val im = tag.getDouble("Im")

    return Pose2d(
        Vector2d(x, y),
        Rotation2d(re, im)
    )
}

fun CompoundTag.putBlockPos(key: String, pos: BlockPos) {
    val dataTag = CompoundTag()
    dataTag.putInt("X", pos.x)
    dataTag.putInt("Y", pos.y)
    dataTag.putInt("Z", pos.z)
    this.put(key, dataTag)
}

fun CompoundTag.getBlockPos(key: String): BlockPos {
    val dataTag = this.get(key) as CompoundTag
    val x = dataTag.getInt("X")
    val y = dataTag.getInt("Y")
    val z = dataTag.getInt("Z")

    return BlockPos(x, y, z)
}

fun CompoundTag.putLocator(id: String, locator: Locator) {
    check(locator.dispatcher === Locators) {
        "Not out locators"
    }

    this.putByteArray(id, locator.toImage())
}

fun CompoundTag.getLocator(id: String): Locator {
    return Locators.fromImage(this.getByteArray(id))
}

fun CompoundTag.putResourceLocation(key: String, resourceLocation: ResourceLocation) {
    this.putString(key, resourceLocation.toString())
}

fun CompoundTag.tryGetResourceLocation(key: String): ResourceLocation? {
    val str = this.getString(key)

    return ResourceLocation.tryParse(str)
}

fun CompoundTag.getResourceLocation(key: String): ResourceLocation {
    return this.tryGetResourceLocation(key) ?: error("Invalid resource location with key $key")
}

fun CompoundTag.putDirection(key: String, direction: Direction) {
    this.putInt(key, direction.get3DDataValue())
}

fun CompoundTag.getDirection(key: String): Direction {
    val data3d = this.getInt(key)

    return Direction.from3DDataValue(data3d)
}

fun CompoundTag.putHorizontalFacing(key: String, direction: FacingDirection) {
    this.putInt(key, direction.index)
}

fun CompoundTag.getHorizontalFacing(key: String) : FacingDirection {
    val data2d = this.getInt(key)

    return FacingDirection.byIndex(data2d)
}

fun CompoundTag.putBase6Direction3d(key: String, direction: Base6Direction3d) {
    this.putInt(key, direction.id)
}

fun CompoundTag.putConnectionMode(key: String, mode: CellPartConnectionMode) {
    this.putInt(key, mode.index)
}

fun CompoundTag.getConnectionMode(key: String): CellPartConnectionMode {
    val value = this.getInt(key)
    return CellPartConnectionMode.byId[value] ?: error("Invalid connection mode $value")
}

fun CompoundTag.getBase6Direction3d(key: String): Base6Direction3d {
    val data = this.getInt(key)
    return Base6Direction3d.entries[data]
}

fun CompoundTag.putPartUpdateType(key: String, type: PartUpdateType) {
    val data = type.id

    this.putInt(key, data)
}

fun CompoundTag.getPartUpdateType(key: String): PartUpdateType {
    val data = this.getInt(key)

    return PartUpdateType.fromId(data)
}

fun CompoundTag.putSpecUpdateType(key: String, type: SpecUpdateType) {
    val data = type.id

    this.putInt(key, data)
}

fun CompoundTag.getSpecUpdateType(key: String): SpecUpdateType {
    val data = this.getInt(key)

    return SpecUpdateType.fromId(data)
}

/**
 * Creates a new compound tag, calls the consumer method with the new tag, and adds the created tag to this instance.
 * @return The Compound Tag that was created.
 * */
fun CompoundTag.putSubTag(key: String, consumer: ((CompoundTag) -> Unit)): CompoundTag {
    val tag = CompoundTag()

    consumer(tag)

    this.put(key, tag)

    return tag
}

fun CompoundTag.withSubTag(key: String, tag: CompoundTag): CompoundTag {
    this.put(key, tag)
    return this
}

fun CompoundTag.withSubTagOptional(key: String, tag: CompoundTag?): CompoundTag {
    if (tag != null) {
        this.put(key, tag)
    }

    return this
}

fun CompoundTag.placeSubTag(key: String, consumer: ((CompoundTag) -> Unit)): CompoundTag {
    val tag = CompoundTag()
    consumer(tag)

    this.put(key, tag)

    return tag
}

/**
 * Gets the compound tag from this instance, and calls the consumer method with the found tag.
 * @return The tag that was found.
 * */
fun CompoundTag.useSubTagIfPreset(key: String, consumer: ((CompoundTag) -> Unit)): CompoundTag {
    val tag = this.get(key) as? CompoundTag
        ?: return this

    consumer(tag)

    return this
}
