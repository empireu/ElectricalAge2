@file:Suppress("MemberVisibilityCanBePrivate")

package org.eln2.mc.client.render.foundation

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.task.Plan
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.LightUpdatedVisual
import dev.engine_room.flywheel.api.visual.SectionTrackedVisual
import dev.engine_room.flywheel.api.visual.TickableVisual
import dev.engine_room.flywheel.api.visual.Visual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.FlatLit
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.task.RunnablePlan
import dev.engine_room.flywheel.lib.transform.Affine
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.client.Camera
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.client.model.data.ModelData
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.OrientedBoundingBox3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.sim.STANDARD_TEMPERATURE
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.buildDirectionTable
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.content.PartConnectionRenderInfo
import org.eln2.mc.common.content.WirePart
import org.eln2.mc.common.content.getPartConnectionAsContactSectionConnectionOrNull
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.CellPartConnectionMode
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.common.parts.foundation.PartUpdateType
import org.eln2.mc.common.specs.foundation.AbstractSpecVisual
import org.eln2.mc.common.specs.foundation.Spec
import org.eln2.mc.common.specs.foundation.SpecContainerPartVisual
import org.eln2.mc.common.specs.foundation.SpecVisualizationContext
import org.eln2.mc.extensions.cast
import org.eln2.mc.extensions.rotationFast
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.MyColor
import org.eln2.mc.requireIsOnRenderThread
import java.util.function.Consumer
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

fun createPartInstance(
    ctx: MultipartVisualizationContext,
    model: PartialModel,
    part: Part,
    scale: Vector3d = Vector3d.one,
    yRotation: Double = 0.0,
): TransformedInstance = ctx
    .instancerProvider()
    .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
    .createInstance()
    .partTransformation(ctx.parent, part, scale, yRotation)

/*
fun createSpecInstance(
    part: SpecPartRenderer,
    model: PartialModel,
    spec: Spec<*>,
    scale: Vector3d = Vector3d.one,
    yRotation: Double = 0.0,
) = part.multipart.materialManager
        .defaultSolid()
        .material(Materials.TRANSFORMED)
        .getModel(model)
        .createInstance()
        .loadIdentity()
        .transformSpec(part, spec, scale, yRotation)
*/

/**
 * Part visual with a single model.
 * */
open class BasicPartVisual<P : Part>(
    ctx: MultipartVisualizationContext,
    part: P,
    model: PartialModel,
    scale: Vector3d = Vector3d.one,
    rotation: Double = 0.0,
) : AbstractPartVisual<P>(ctx, part) {
    private val instance = ctx.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
        .createInstance()
        .also { it.partTransformation(ctx.parent, part, scale, rotation) }

    override fun updateLight(partialTick: Float) {
        visualizationContext.parent.relightInstances(instance)
    }

    override fun _delete() {
        instance.delete()
    }
}

data class WireConnectionModelPartial(
    val planar: PolarModel,
    val inner: PolarModel,
    val wrapped: PolarModel,
) {
    val variants = mapOf(
        CellPartConnectionMode.Planar to planar,
        CellPartConnectionMode.Inner to inner,
        CellPartConnectionMode.Wrapped to wrapped
    )
}

interface ConnectedPartRenderState {
    val version: Int
    val connections: IntArray
}

class ConnectedPartRenderStateImpl : ConnectedPartRenderState {
    override var version = 0
        private set

    override var connections = IntArray(0)
        private set

    fun set(connections: IntArray) {
        this.connections = connections
        version++
    }
}

fun CellPart<*>.getConnectedPartTag() = CompoundTag().also { compoundTag ->
    if(this.hasCell) {
        val values = IntArrayList(2)

        for (it in this.cell.connections) {
            val solution = getPartConnectionAsContactSectionConnectionOrNull(this.cell, it)
                ?: continue

            values.add(solution.value)
        }

        compoundTag.putIntArray("connections", values)
    }
}

fun<T> T.getConnectedPartsFromTag(tag: CompoundTag): IntArray where T : Part, T : ConnectedPart {
    return if(tag.contains("connections")) {
        tag.getIntArray("connections")
    }
    else {
        IntArray(0)
    }
}

/**
 * Represents a part that forms connections with neighbor game objects, used by the [ConnectedPartVisual].
 * */
interface ConnectedPart {
    @ClientOnly
    val renderState: ConnectedPartRenderState
}

class ConnectedPartVisual<P>(
    ctx: MultipartVisualizationContext,
    part: P,
    body: PartialModel,
    val connectionModels: Map<Base6Direction3d, WireConnectionModelPartial>,
) : AbstractPartVisual<P>(ctx, part), SimpleDynamicVisual where P : Part, P : ConnectedPart {
    constructor(ctx: MultipartVisualizationContext, part: P, body: PartialModel, connection: WireConnectionModelPartial) : this(
        ctx,
        part,
        body,
        mapOf(
            Base6Direction3d.Front to connection,
            Base6Direction3d.Back to connection,
            Base6Direction3d.Left to connection,
            Base6Direction3d.Right to connection
        )
    )

    val bodyInstance: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(body))
        .createInstance()
        .also { it.partTransformation(visualizationContext.parent, part) }

    private val connectionInstances = Int2ObjectOpenHashMap<TransformedInstance>()

    /**
     * Tracks the last acknowledged version of the connections from [WirePart.RenderState].
     * */
    private var connectionsVersion = -1

    override fun beginFrame(ctx: DynamicVisual.Context) {
        val partialTick = ctx.partialTick()
        val partRenderState = part.renderState

        val latestConnectionsVersion = partRenderState.version
        if(connectionsVersion != latestConnectionsVersion) {
            connectionsVersion = latestConnectionsVersion
            applyConnectionData(partRenderState.connections, partialTick)
        }
    }

    private fun applyConnectionData(values: IntArray, partialTick: Float) {
        deleteConnectionInstances()

        for (value in values) {
            val info = PartConnectionRenderInfo(value)
            val direction = info.directionPart

            val model = connectionModels[direction]
                ?: continue

            val instance = visualizationContext.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, model.variants[info.mode]!!.get())
                .createInstance()
                .partTransformation(
                    visualizationContext.parent,
                    part,
                    yRotation = when (direction) {
                        Base6Direction3d.Front -> 0.0
                        Base6Direction3d.Back -> PI
                        Base6Direction3d.Left -> PI / 2.0
                        Base6Direction3d.Right -> -PI / 2.0
                        else -> error("Invalid connected part direction $direction")
                    }
                )

            connectionInstances.putUnique(value, instance)
        }

        updateLight(partialTick)
    }

    override fun updateLight(partialTick: Float) {
        visualizationContext.parent.relightInstances(bodyInstance)
        visualizationContext.parent.relightInstances(connectionInstances.values)
    }

    override fun _delete() {
        bodyInstance.delete()
        deleteConnectionInstances()
    }

    private fun deleteConnectionInstances() {
        for (instance in connectionInstances.values) {
            instance.delete()
        }

        connectionInstances.clear()
    }
}

val partOffsetTable = buildDirectionTable {
    when(it) {
        Direction.DOWN -> Vector3d(0.5, 1.0, 0.5)
        Direction.UP -> Vector3d(0.5, 0.0, 0.5)
        Direction.NORTH -> Vector3d(0.5, 0.5, 1.0)
        Direction.SOUTH -> Vector3d(0.5, 0.5, 0.0)
        Direction.WEST -> Vector3d(1.0, 0.5, 0.5)
        Direction.EAST -> Vector3d(0.0, 0.5, 0.5)
    }
}

fun<T : Affine<T>> T.partTransformation(parent: MultipartBlockEntityVisual, part: Part, scale: Vector3d = Vector3d.one, yRotation: Double = 0.0): T {
    val (dx, dy, dz) = partOffsetTable[part.placement.face.get3DDataValue()]

    return this
        .translate(parent.visualPosition)
        .translate(dx, dy, dz)
        .rotate(part.placement.face.rotationFast)
        .rotateY((yRotation + part.placement.facing.angle).toFloat())
        .scale(scale.x.toFloat(), scale.y.toFloat(), scale.z.toFloat())
        .translate(-0.5, 0.0, -0.5)
}


fun<T : Affine<T>> T.specTransformation(parent: SpecContainerPartVisual, spec: Spec, scale: Vector3d = Vector3d.one, yRotation: Double = 0.0): T {
    val (dx, dy, dz) = partOffsetTable[parent.part.placement.face.get3DDataValue()]
    val (dx1, dy1, dz1) = spec.placement.mountingPointWorld - parent.part.placement.mountingPointWorld

    return this
        .translate(parent.visualizationContext.parent.visualPosition)
        .translate(dx + dx1, dy + dy1, dz + dz1)
        .rotate(parent.part.placement.face.rotationFast)
        .rotateY((yRotation + parent.part.placement.facing.angle + spec.placement.orientation.ln()).toFloat())
        .scale(scale.x.toFloat(), scale.y.toFloat(), scale.z.toFloat())
        .translate(-0.5, 0.0, -0.5)
}

class ThermalTintBuilder {
    var coldTint = MyColor(1f, 1f, 1f, 1f)
    var hotTint = MyColor( 1f, 1f, 0.1f, 0.1f)
    var coldTemperature = STANDARD_TEMPERATURE
    var hotTemperature = Quantity(800.0, CELSIUS)

    fun build(): ThermalTint {
        return ThermalTint(
            coldTint,
            hotTint,
            coldTemperature,
            hotTemperature,
        )
    }
}

class ThermalTint(
    val coldTint: MyColor,
    val hotTint: MyColor,
    val coldTemperature: Quantity<Temperature>,
    val hotTemperature: Quantity<Temperature>,
) {
    companion object {
        val DEFAULT = ThermalTintBuilder().build()
        val DEFAULT_LIGHT_OVERRIDE = ThermalTint(
            MyColor(0, DEFAULT.coldTint.r, DEFAULT.coldTint.g, DEFAULT.coldTint.b),
            DEFAULT.hotTint,
            DEFAULT.coldTemperature,
            DEFAULT.hotTemperature
        )
    }

    fun evaluate(temperature: Quantity<Temperature>) =
        MyColor.lerp(
            from = coldTint,
            to = hotTint,
            blend = map(
                temperature.value.coerceIn(
                    !coldTemperature,
                    !hotTemperature
                ),
                !coldTemperature,
                !hotTemperature,
                0.0,
                1.0
            ).toFloat()
        )

    /**
     * Evaluates the color as R, G, B and light override.
     * @param temperature The temperature of the material.
     * @param light The lower bound of the light value [[0, 15]]
     * @return The tint color to be rendered.
     * */
    fun evaluateRGBL(temperature: Quantity<Temperature>, light: Double = 0.0): MyColor {
        val rgb = evaluate(temperature)

        return MyColor(
            max(
                map(
                    light,
                    0.0,
                    15.0,
                    0.0,
                    255.0
                ),
                map(
                    !temperature,
                    !coldTemperature,
                    !hotTemperature,
                    0.0,
                    255.0
                )
            ).toInt().coerceIn(0, 255),
            rgb.r,
            rgb.g,
            rgb.b
        )
    }
}

fun interface PartVisualizer<P : Part> {
    fun create(ctx: MultipartVisualizationContext, part: P): AbstractPartVisual<*>
}

abstract class AbstractPartVisual<T : Part>(val visualizationContext: MultipartVisualizationContext, val part: T) : Visual, LightUpdatedVisual {
    private var deleted = false

    override fun update(partialTick: Float) { }

    final override fun delete() {
        if(deleted) {
            return
        }

        _delete()
        deleted = true
    }

    override fun setSectionCollector(collector: SectionTrackedVisual.SectionCollector?) {
        // Should be already done by the parent, right?
    }

    @Suppress("FunctionName") // Keep consistent with the flywheel API
    protected abstract fun _delete()
}

class MultipartVisualizationContext(
    ctx: VisualizationContext,
    val parent: MultipartBlockEntityVisual
) : VisualizationContext by ctx

class MultipartBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: MultipartBlockEntity,
    partialTick: Float,
): AbstractBlockEntityVisual<MultipartBlockEntity>(ctx, blockEntity, partialTick), DynamicVisual, TickableVisual, LightUpdatedVisual {
    val parts = HashMap<Part, AbstractPartVisual<*>>()
    val multipartVisualizationContext = MultipartVisualizationContext(visualizationContext, this)
    val storage = SpecialVisualStorage<AbstractPartVisual<*>>()

    init {
        blockEntity.parts.values.forEach {
            addPart(it, partialTick)
        }
    }

    override fun _delete() {
        storage.delete()
        parts.clear()
    }

    override fun collectCrumblingInstances(consumer: Consumer<Instance?>?) {
        // add the part being broken TODO
    }

    override fun updateLight(partialTick: Float) {
        parts.values.forEach {
            it.updateLight(partialTick) // TODO examine what he says about safety
        }
    }

    override fun planFrame(): Plan<DynamicVisual.Context> = RunnablePlan
        .of(::handlePartUpdates)
        .then(storage.dynamicVisuals)

    override fun planTick(): Plan<TickableVisual.Context> = storage.tickableVisuals

    /**
     * This method is called per tick. But it runs in parallel with the other multipart visuals.
     * It dequeues all the part updates that were queued up.
     * These updates may indicate:
     *  - New parts added to the multipart.
     *  - Parts that were destroyed.
     * */
    private fun handlePartUpdates(ctx: DynamicVisual.Context) {
        while (true) {
            val update = blockEntity.renderUpdates.poll()
                ?: break

            val part = update.part

            when (update.type) {
                PartUpdateType.Add -> {
                    addPart(part, ctx.partialTick())

                    // Can get duplicate adds if the client first receives the parts (and clientAddPart enqueues updates)
                    // but just then the multipart renderer gets created and calls bindRenderer, which duplicate enqueues some more updates
                }
                PartUpdateType.Remove -> {
                    val visual = parts.remove(part)

                    if(visual != null) {
                        storage.remove(visual)
                        visual.delete()
                    }
                }
            }
        }
    }

    private fun addPart(part: Part, partialTick: Float) {
        if (!parts.contains(part)) {
            val visual = part.createVisual(multipartVisualizationContext)

            if(visual == null) {
                LOG.debug("Part {} didn't create a visual", part)
                return
            }

            storage.add(visual)
            parts[part] = visual

            visual.updateLight(partialTick)
        }
    }

    fun relightInstances(vararg instances: FlatLit?) {
        relight(pos, *instances)
    }

    fun relightInstances(instances: Iterable<FlatLit?>) {
        relight(pos, instances)
    }
}

class TestBlockEntityVisual<T : BlockEntity>(
    ctx: VisualizationContext,
    blockEntity: T,
    partialTick: Float,
    model: PartialModel,
    transformer: (instance: TransformedInstance, visual: TestBlockEntityVisual<T>) -> Unit,
) : AbstractBlockEntityVisual<T>(ctx, blockEntity, partialTick) {
    var instance: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
        .createInstance()
        .also { transformer(it, this) }

    override fun updateLight(partialTick: Float) {
        relight(instance)
    }

    override fun collectCrumblingInstances(consumer: Consumer<Instance?>) {
        consumer.accept(instance)
    }

    override fun _delete() {
        instance.delete()
    }
}

open class BasicSpecVisual<S : Spec>(
    ctx: SpecVisualizationContext,
    spec: S,
    model: PartialModel,
    scale: Vector3d = Vector3d.one,
    rotation: Double = 0.0
) : AbstractSpecVisual<S>(ctx, spec) {
    private val instance = ctx.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
        .createInstance()
        .also { it.specTransformation(ctx.parent, spec, scale, rotation) }

    override fun updateLight(p0: Float) {
        visualizationContext.grandparent.relightInstances(instance)
    }

    override fun _delete() {
        instance.delete()
    }
}

fun VertexConsumer.eln2SubmitUnshadedBakedModelQuads(
    renderType: RenderType,
    pose: PoseStack.Pose, // this is not a pose, Minecraft, because it is not in SE(3)
    model: BakedModel,
    r: Float,
    g: Float,
    b: Float,
    a: Float,
    packedLight: Int = 15728880,
    overlayTexture: Int = OverlayTexture.NO_OVERLAY,
) {
    requireIsOnRenderThread {
        "eln2SubmitUnshadedBakedModelQuads"
    }

    val pQuads = model.getQuads(
        null,
        null,
        RandomSource.create(),
        ModelData.EMPTY,
        renderType
    )

    for (bakedQuad in pQuads) {
        this.putBulkData(
            pose,
            bakedQuad,
            r, g, b, a,
            packedLight, overlayTexture,
            true
        )
    }
}

fun VertexConsumer.eln2SubmitUnshadedBakedModelQuads(
    renderType: RenderType,
    pose: PoseStack.Pose, // this is not a pose, Minecraft, because it is not in SE(3)
    model: BakedModel,
    rgba: MyColor,
    packedLight: Int = 15728880,
    overlayTexture: Int = OverlayTexture.NO_OVERLAY,
) = this.eln2SubmitUnshadedBakedModelQuads(renderType, pose, model, rgba.rF, rgba.gF, rgba.bF, rgba.aF, packedLight, overlayTexture)

fun VertexConsumer.eln2SubmitVoxelShapeLines(pose: PoseStack.Pose, shape: VoxelShape, r: Float, g: Float, b: Float, a: Float) {
    shape.forAllEdges { pX1: Double, pY1: Double, pZ1: Double, pX2: Double, pY2: Double, pZ2: Double ->
        var nx = (pX2 - pX1).toFloat()
        var ny = (pY2 - pY1).toFloat()
        var nz = (pZ2 - pZ1).toFloat()

        val norm = 1f / sqrt(nx * nx + ny * ny + nz * nz)

        nx *= norm
        ny *= norm
        nz *= norm

        val poseModel = pose.pose()
        val poseNormal = pose.normal()

        this.vertex(poseModel, pX1.toFloat(), pY1.toFloat(), pZ1.toFloat())
            .color(r, g, b, a)
            .normal(poseNormal, nx, ny, nz)
            .endVertex()

        this.vertex(poseModel, pX2.toFloat(), pY2.toFloat(), pZ2.toFloat())
            .color(r, g, b, a)
            .normal(poseNormal, nx, ny, nz)
            .endVertex()
    }
}

fun VertexConsumer.eln2SubmitAABBLines(pose: PoseStack.Pose, aabb: BoundingBox3d, r: Float, g: Float, b: Float, a: Float) {
    this.eln2SubmitVoxelShapeLines(
        pose,
        Shapes.create(aabb.cast()),
        r, g, b, a
    )
}

fun VertexConsumer.eln2SubmitAABBLines(pose: PoseStack.Pose, aabb: BoundingBox3d, rgba: MyColor) {
    this.eln2SubmitAABBLines(
        pose,
        aabb,
        rgba.rF, rgba.gF, rgba.bF, rgba.aF
    )
}

fun VertexConsumer.eln2SubmitOBBAtLevelStage(stack: PoseStack, obb: OrientedBoundingBox3d, rgba: MyColor, camX: Double, camY: Double, camZ: Double) {
    stack.pushPose()

    stack.translate(-camX, -camY, -camZ)
    stack.translate(obb.transform.translation.x, obb.transform.translation.y, obb.transform.translation.z)
    stack.mulPose(obb.transform.rotation.cast())

    val min = -obb.halfSize
    val max = +obb.halfSize

    LevelRenderer.renderLineBox(
        stack,
        this,
        min.x, min.y, min.z,
        max.x, max.y, max.z,
        rgba.rF, rgba.gF, rgba.bF, rgba.aF,
        rgba.rF, rgba.gF, rgba.bF
    )

    stack.popPose()
}

fun VertexConsumer.eln2SubmitOBBAtLevelStage(stack: PoseStack, obb: OrientedBoundingBox3d, rgba: MyColor, camera: Camera) = this.eln2SubmitOBBAtLevelStage(
    stack, obb, rgba,
    camera.position.x, camera.position.y, camera.position.z
)
