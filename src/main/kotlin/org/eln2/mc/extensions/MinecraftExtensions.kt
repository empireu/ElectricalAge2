package org.eln2.mc.extensions

import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.client.resources.model.SimpleBakedModel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.common.ForgeMod
import net.minecraftforge.network.NetworkHooks
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.mathematics.*
import org.eln2.mc.*
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.mathematics.Base6Direction3d
import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3f
import java.util.*
import kotlin.math.PI

fun Entity.getClipStartEnd() : Pair<Vec3, Vec3> {
    val viewDirection = this.lookAngle

    val start = Vec3(this.x, this.eyeY, this.z)

    val distance = if(this is Player) {
        this.blockReach
    }
    else {
        ForgeMod.BLOCK_REACH.get().defaultValue
    }

    val end = start + viewDirection * distance

    return Pair(start, end)
}

fun Entity.getViewRay(): Ray3d {
    val lookAngle = this.lookAngle
    val viewDirection = Vector3d(lookAngle.x, lookAngle.y, lookAngle.z)
    val start = Vector3d(this.x, this.eyeY, this.z)

    return Ray3d(start, viewDirection.normalized())
}

fun AABB.viewClip(entity: LivingEntity): Optional<Vec3> {
    val (start, end) = entity.getClipStartEnd()

    return this.clip(start, end)
}

fun AABB.viewClipExtra(entity: LivingEntity, blockPos: BlockPos) : BlockHitResult? {
    val (start, end) = entity.getClipStartEnd()
    return AABB.clip(listOf(this), start, end, blockPos)
}

fun AABB.minVec3(): Vec3 {
    return Vec3(this.minX, this.minY, this.minZ)
}

fun AABB.maxVec3(): Vec3 {
    return Vec3(this.maxX, this.maxY, this.maxZ)
}

fun AABB.size(): Vec3 {
    return this.maxVec3() - this.minVec3()
}

fun AABB.corners(list: MutableList<Vec3>) {
    val min = this.minVec3()
    val max = this.maxVec3()

    list.add(min)
    list.add(Vec3(min.x, min.y, max.z))
    list.add(Vec3(min.x, max.y, min.z))
    list.add(Vec3(max.x, min.y, min.z))
    list.add(Vec3(min.x, max.y, max.z))
    list.add(Vec3(max.x, min.y, max.z))
    list.add(Vec3(max.x, max.y, min.z))
    list.add(max)
}

fun AABB.corners(): ArrayList<Vec3> {
    val list = ArrayList<Vec3>()

    this.corners(list)

    return list
}

/**
 * Transforms the Axis Aligned Bounding Box by the given rotation.
 * This operation does not change the volume for axis aligned transformations.
 * */
fun AABB.transformed(quaternion: Quaternionfc): AABB {
    var min = Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    var max = Vector3f(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE)

    this.corners().forEach {
        val corner = quaternion.transform(it.toJoml())

        min = componentMin(min, corner)
        max = componentMax(max, corner)
    }

    return AABB(min.toVec3(), max.toVec3())
}

fun AABB.size3d() = Vector3d(this.maxX - this.minX, this.maxY - this.minY, this.maxZ - this.minZ)
fun BlockState.facing(): Direction = this.getValue(HorizontalDirectionalBlock.FACING)
operator fun BlockPos.plus(displacement: Vec3i): BlockPos {
    return this.offset(displacement)
}

operator fun BlockPos.plus(direction: Direction): BlockPos {
    return this + direction.normal
}

operator fun BlockPos.minus(displacement: Vec3i): BlockPos {
    return this.subtract(displacement)
}

operator fun BlockPos.minus(other: BlockPos): BlockPos {
    return BlockPos(this.x - other.x, this.y - other.y, this.z - other.z)
}

operator fun BlockPos.minus(direction: Direction): BlockPos {
    return this - direction.normal
}

fun BlockPos.directionTo(other: BlockPos) = directionByNormal(other - this)
fun Direction.isVertical(): Boolean {
    return this == Direction.UP || this == Direction.DOWN
}

fun Direction.isHorizontal(): Boolean {
    return !isVertical()
}

val Direction.alias: Base6Direction3d
    get() = when (this) {
        Direction.DOWN -> Base6Direction3d.Down
        Direction.UP -> Base6Direction3d.Up
        Direction.NORTH -> Base6Direction3d.Front
        Direction.SOUTH -> Base6Direction3d.Back
        Direction.WEST -> Base6Direction3d.Left
        Direction.EAST -> Base6Direction3d.Right
    }

private val DIRECTION_TO_VECTOR3D = buildDirectionTable {
    Vector3d(it.stepX.toDouble(), it.stepY.toDouble(), it.stepZ.toDouble())
}

val Direction.vector3d get() = DIRECTION_TO_VECTOR3D[this.get3DDataValue()]

private val DIRECTION_TO_JOML_QUATERNION = buildDirectionTable {
    it.rotation
}

val Direction.rotationFast: Quaternionf get() = DIRECTION_TO_JOML_QUATERNION[this.get3DDataValue()]

private val DIRECTION_TO_ROTATION3D = buildDirectionTable {
    it.rotation.cast()
}

val Direction.rotation3d get() = DIRECTION_TO_ROTATION3D[this.get3DDataValue()]

fun Level.playLocalSound(
    pos: Vec3,
    pSound: SoundEvent,
    pCategory: SoundSource,
    pVolume: Float,
    pPitch: Float,
    pDistanceDelay: Boolean,
) {
    this.playLocalSound(pos.x, pos.y, pos.z, pSound, pCategory, pVolume, pPitch, pDistanceDelay)
}

fun Level.addParticle(
    pParticleData: ParticleOptions,
    pos: Vec3,
    pXSpeed: Double,
    pYSpeed: Double,
    pZSpeed: Double,
) {
    this.addParticle(pParticleData, pos.x, pos.y, pos.z, pXSpeed, pYSpeed, pZSpeed)
}

fun Level.addParticle(
    pParticleData: ParticleOptions,
    pos: Vec3,
    speed: Vec3,
) {
    this.addParticle(pParticleData, pos.x, pos.y, pos.z, speed.x, speed.y, speed.z)
}

fun ServerLevel.addItem(x: Double, y: Double, z: Double, stack: ItemStack) {
    if(this.gameRules.getBoolean(GameRules.RULE_DOBLOCKDROPS) && !this.restoringBlockSnapshots) {
        val entity =  ItemEntity(
            this,
            x,
            y,
            z,
            stack
        )

        entity.setDefaultPickUpDelay()

        this.addFreshEntity(entity)
    }
}

fun ServerLevel.addItem(pos: BlockPos, stack: ItemStack) = addItem(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), stack)

@ServerOnly
fun ServerLevel.destroyPart(part: Part<*>, dropPart: Boolean) {
    val pos = part.placement.position

    val multipart = this.getBlockEntity(pos)
        as? MultipartBlockEntity

    if (multipart == null) {
        LOG.error("Multipart null at $pos")

        return
    }

    val saveTag = CompoundTag()

    multipart.breakPart(part, saveTag)

    if(dropPart) {
        val itemEntity = ItemEntity(
            this,
            pos.x.toDouble(),
            pos.y.toDouble(),
            pos.z.toDouble(),
            Part.createPartDropStack(part.id, saveTag)
        )

        this.addFreshEntity(itemEntity)
    }

    if (multipart.isEmpty) {
        this.destroyBlock(pos, false)
    }
}

inline fun <reified TBlockEntity : BlockEntity> Level.constructMenuHelper(
    pos: BlockPos,
    player: Player,
    title: Component,
    crossinline factory: (
        pBlockEntity: TBlockEntity,
        pContainerId: Int,
        pPlayerInventory: Inventory,
        pPlayer: Player
    ) -> AbstractContainerMenu,
): InteractionResult {

    if (this.isClientSide) {
        return InteractionResult.SUCCESS
    }

    val entity = this.getBlockEntity(pos) as? TBlockEntity
        ?: return InteractionResult.FAIL

    val containerProvider = object : MenuProvider {
        override fun getDisplayName() = title

        override fun createMenu(
            pContainerId: Int,
            pInventory: Inventory,
            pPlayer: Player,
        ): AbstractContainerMenu = factory(
            entity,
            pContainerId,
            pInventory,
            pPlayer
        )
    }

    NetworkHooks.openScreen(player as ServerPlayer, containerProvider, entity.blockPos)

    return InteractionResult.SUCCESS
}

inline fun <reified TBlockEntity : BlockEntity> Level.constructMenuHelper2(
    pos: BlockPos,
    player: Player,
    title: Component,
    crossinline factory: (
        pBlockEntity: TBlockEntity,
        pContainerId: Int,
        pPlayerInventory: Inventory,
    ) -> AbstractContainerMenu,
) = this.constructMenuHelper<TBlockEntity>(pos, player, title) { pBlockEntity: TBlockEntity, pContainerId: Int, pPlayerInventory: Inventory, _: Player ->
    factory(pBlockEntity, pContainerId, pPlayerInventory)
}

fun Vector3f.toVec3() = Vec3(this.x.toDouble(), this.y.toDouble(), this.z.toDouble())

val Base6Direction3d.alias: Direction
    get() = when (this) {
        Base6Direction3d.Front -> Direction.NORTH
        Base6Direction3d.Back -> Direction.SOUTH
        Base6Direction3d.Left -> Direction.WEST
        Base6Direction3d.Right -> Direction.EAST
        Base6Direction3d.Up -> Direction.UP
        Base6Direction3d.Down -> Direction.DOWN
    }

fun Rotation.inverse() = when (this) {
    Rotation.NONE -> Rotation.NONE
    Rotation.CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90
    Rotation.CLOCKWISE_180 -> Rotation.CLOCKWISE_180
    Rotation.COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90
}

operator fun Rotation.times(p: BlockPos): BlockPos = p.rotate(this)

operator fun Vec3.plus(b: Vec3): Vec3 {
    return Vec3(this.x + b.x, this.y + b.y, this.z + b.z)
}

operator fun Vec3.plus(delta: Double): Vec3 {
    return Vec3(this.x + delta, this.y + delta, this.z + delta)
}

operator fun Vec3.minus(b: Vec3): Vec3 {
    return Vec3(this.x - b.x, this.y - b.y, this.z - b.z)
}

operator fun Vec3.minus(delta: Double): Vec3 {
    return Vec3(this.x - delta, this.y - delta, this.z - delta)
}

operator fun Vec3.times(b: Vec3): Vec3 {
    return Vec3(this.x * b.x, this.y * b.y, this.z * b.z)
}

operator fun Vec3.times(scalar: Double): Vec3 {
    return Vec3(this.x * scalar, this.y * scalar, this.z * scalar)
}

operator fun Vec3.div(b: Vec3): Vec3 {
    return Vec3(this.x / b.x, this.y / b.y, this.z / b.z)
}

operator fun Vec3.div(scalar: Double): Vec3 {
    return Vec3(this.x / scalar, this.y / scalar, this.z / scalar)
}

operator fun Vec3.unaryMinus(): Vec3 {
    return Vec3(-this.x, -this.y, -this.z)
}

operator fun Vec3.unaryPlus(): Vec3 {
    // For completeness

    return Vec3(this.x, this.y, this.z)
}

fun Vec3i.toVec3(): Vec3 {
    return Vec3(this.x.toDouble(), this.y.toDouble(), this.z.toDouble())
}

fun BlockPos.toVector3d() = Vector3d(this.x.toDouble(), this.y.toDouble(), this.z.toDouble())
fun BlockPos.toVector3di() = Vector3di(this.x, this.y, this.z)
fun BlockEntity.sendClientUpdate() =
    this.level!!.sendBlockUpdated(this.blockPos, this.blockState, this.blockState, Block.UPDATE_CLIENTS)

fun Entity.moveTo(v: Vector3d) = this.moveTo(v.x, v.y, v.z)
fun Vector3d.toVec3() = Vec3(this.x, this.y, this.z)
fun Direction.valueHashCode() = this.normal.hashCode()
fun Vector3di.toBlockPos() = BlockPos(this.x, this.y, this.z)
fun ChunkPos.toVector2d() = Vector2d(this.x.toDouble(), this.z.toDouble())
fun Vec3.toJoml() = Vector3f(this.x.toFloat(), this.y.toFloat(), this.z.toFloat())
fun BakedQuad.bind() = BakedQuad(
    this.vertices.copyOf(),
    this.tintIndex,
    this.direction,
    this.sprite,
    this.isShade,
    this.hasAmbientOcclusion()
)

@Suppress("DEPRECATION", "NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
fun BakedModel.bind() : SimpleBakedModel {
    val quads = HashMap<BakedQuad, BakedQuad>()

    return SimpleBakedModel(
        this.getQuads(null, null, null)
            .map { quad ->
                quad.bind().also { boundQuad ->
                    quads[quad] = boundQuad
                }
            },
        let {
            val cull = HashMap<Direction, List<BakedQuad>>()

            Direction.values().forEach { dir ->
                cull[dir] = this.getQuads(null, dir, null).map {
                    quads[it]!!
                }
            }

            cull
        },
        this.useAmbientOcclusion(),
        this.usesBlockLight(),
        this.isGui3d,
        this.particleIcon,
        this.transforms,
        this.overrides
    )
}

fun RandomSource.nextDouble(min: Double, max: Double) = Mth.nextDouble(this, min, max)

/**
 * Gets the celestial phase from the in-game celestial angle.
 * @param sunAngle The celestial angle, as per [Level.getSunAngle]
 * @return A [Rotation2d] that represents the current celestial pass. The real axis is fixed as the ground. When the value is in the upper semicircle, the sun is passing. When the value is in the lower semicircle, the moon is passing.
 * */
fun celestialPass(sunAngle: Double) = Rotation2d.exp(sunAngle + PI / 2.0)

/**
 * Gets the deviation between the normal and the direction towards the celestial body.
 * @return The deviation angle. It is always positive, no matter if the celestial body is the sun or the moon.
 * */
fun celestialDeviation(sunAngle: Double, normal: Vector3d) : Double {
    var pass = celestialPass(sunAngle)

    if(pass.im < 0.0) {
        // Night
        pass = !pass
    }

    return !Vector3d(pass.re, pass.im, 0.0) angle !normal
}

fun Level.celestialPass() = celestialPass(this.getSunAngle(1.0f).toDouble())
fun Level.celestialDeviation(normal: Vector3d) = celestialDeviation(this.getSunAngle(1.0f).toDouble(), normal)
fun VoxelShape.toBoxList() : List<AABB> {
    val results = ArrayList<AABB>(2)

    this.forAllBoxes { pMinX, pMinY, pMinZ, pMaxX, pMaxY, pMaxZ ->
        results.add(
            AABB(
                pMinX, pMinY, pMinZ,
                pMaxX, pMaxY, pMaxZ
            )
        )
    }

    return results
}

fun Double.formatted(decimals: Int = 2): String {
    return "%.${decimals}f".format(this)
}

fun <U> CompoundTag.putQuantity(key: String, e: Quantity<U>) = this.putDouble(key, !e)
fun <U> CompoundTag.getQuantity(key: String) = Quantity<U>(this.getDouble(key))
inline fun ListTag.forEachCompound(action: (CompoundTag) -> Unit) {
    for (tag in this) {
        val compound = checkNotNull(tag as? CompoundTag) {
            "Failed to cast element in list tag to compound"
        }

        action(compound)
    }
}

fun CompoundTag.getListTag(key: String) : ListTag = checkNotNull(this.get(key) as? ListTag) {
    "Failed to get list tag from compound"
}

fun Quaternionf.cast() = Rotation3d(this.x.toDouble(), this.y.toDouble(), this.z.toDouble(), this.w.toDouble())
