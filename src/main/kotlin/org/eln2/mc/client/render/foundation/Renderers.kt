@file:Suppress("MemberVisibilityCanBePrivate")

package org.eln2.mc.client.render.foundation

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.task.Plan
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.LightUpdatedVisual
import dev.engine_room.flywheel.api.visual.TickableVisual
import dev.engine_room.flywheel.api.visual.Visual
import dev.engine_room.flywheel.api.visualization.VisualEmbedding
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.FlatLit
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.task.RunnablePlan
import dev.engine_room.flywheel.lib.transform.Affine
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import net.minecraft.client.Camera
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.util.RandomSource
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.OrientedBoundingBox3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.sim.STANDARD_TEMPERATURE
import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.buildDirectionTable
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.extensions.cast
import org.eln2.mc.extensions.rotationFast
import org.eln2.mc.mathematics.ArgbColor
import org.eln2.mc.requireIsOnRenderThread
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.sqrt

fun createPartInstance(
    multipart: MultipartBlockEntityVisual,
    model: PartialModel,
    part: Part<*>,
    scale: Vector3d = Vector3d.one,
    yRotation: Double = 0.0,
) = multipart.context
        .instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
        .createInstance()
        .transformPart(multipart, part, scale, yRotation)

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
 * Part renderer with a single model.
 * */
open class BasicPartRenderer(val part: Part<*>, val model: PartialModel, val scale: Vector3d = Vector3d.one) : PartRenderer() {
    var yRotation = 0.0

    private var modelInstance: TransformedInstance? = null

    override fun setupRendering() {
        buildInstance()
    }

    fun buildInstance() {
        modelInstance?.delete()
        modelInstance = createPartInstance(multipart, model, part, scale, yRotation)
    }

    override fun relight(source: RelightSource) {
        multipart.relightModels(modelInstance)
    }

    override fun beginFrame() {}

    override fun remove() {
        modelInstance?.delete()
    }
}
/*

fun CellPart<*, ConnectedPartRenderer>.getConnectedPartTag() = CompoundTag().also { compoundTag ->
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
*/
/*

fun Part<ConnectedPartRenderer>.handleConnectedPartTag(tag: CompoundTag) = this.renderer.acceptConnections(
    if(tag.contains("connections")) {
        tag.getIntArray("connections")
    }
    else {
        IntArray(0)
    }
)
*/
/*
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
}*/
/*
class ConnectedPartRenderer(
    val part: Part<*>,
    val body: PartialModel,
    val connections: Map<Base6Direction3d, WireConnectionModelPartial>,
) : PartRenderer(), PartConnectionRenderInfoSetConsumer, PartRendererStateStorage {
    constructor(part: Part<*>, body: PartialModel, connection: WireConnectionModelPartial) : this(
        part,
        body,
        mapOf(
            Base6Direction3d.Front to connection,
            Base6Direction3d.Back to connection,
            Base6Direction3d.Left to connection,
            Base6Direction3d.Right to connection
        )
    )

    private var bodyInstance: ModelData? = null
    private val connectionDirectionsUpdate = AtomicUpdate<IntArray>()
    private val connectionInstances = Int2ObjectOpenHashMap<ModelData>()

    private var connectionsRestore = IntArray(0)

    override fun restoreSnapshot(renderer: PartRenderer) {
        if(renderer is ConnectedPartRenderer) {
            this.acceptConnections(renderer.connectionsRestore)
        }
    }

    override fun acceptConnections(connections: IntArray) {
        connectionDirectionsUpdate.setLatest(connections)
    }

    override fun setupRendering() {
        buildBody()
        applyConnectionData(connectionInstances.keys.toIntArray())
    }

    private fun buildBody() {
        bodyInstance?.delete()
        bodyInstance = createPartInstance(multipart, body, part)
    }

    private fun applyConnectionData(values: IntArray) {
        connectionInstances.values.forEach { it.delete() }
        connectionInstances.clear()

        for (value in values) {
            val info = PartConnectionRenderInfo(value)
            val direction = info.directionPart

            val model = connections[direction]
                ?: continue

            val instance = createPartInstance(
                multipart,
                model.variants[info.mode]!!,
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

        multipart.relightModels(connectionInstances.values)
    }

    override fun relight(source: RelightSource) {
        multipart.relightModels(bodyInstance)
        multipart.relightModels(connectionInstances.values)
    }

    override fun beginFrame() {
        connectionDirectionsUpdate.consume { values ->
            applyConnectionData(values)
        }
    }

    override fun remove() {
        connectionsRestore = connectionInstances.keys.toIntArray()

        bodyInstance?.delete()
        connectionInstances.values.forEach { it.delete() }
    }
}*/

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

fun<T : Affine<T>> T.transformPart(instance: MultipartBlockEntityVisual, part: Part<*>, scale: Vector3d = Vector3d.one, yRotation: Double = 0.0): T {
    val (dx, dy, dz) = partOffsetTable[part.placement.face.get3DDataValue()]

    return this
        .translate(instance.visualPosition) // todo is it right?
        .translate(dx, dy, dz)
        .rotate(part.placement.face.rotationFast)
        .rotateY((yRotation + part.placement.facing.angle).toFloat())
        .scale(scale.x.toFloat(), scale.y.toFloat(), scale.z.toFloat())
        .translate(-0.5, 0.0, -0.5)
}
/*

fun<T : Transform<T>> T.transformSpec(instance: SpecPartRenderer, spec: Spec<*>, scale: Vector3d, yRotation: Double): T {
    val (dx, dy, dz) = partOffsetTable[instance.specPart.placement.face.get3DDataValue()]
    val (dx1, dy1, dz1) = spec.placement.mountingPointWorld - instance.specPart.placement.mountingPointWorld

    return this
        .translate(instance.instancePosition)
        .translate(dx + dx1, dy + dy1, dz + dz1)
        .multiply(instance.specPart.placement.face.rotationFast)
        .rotateYRadians(yRotation + instance.specPart.placement.facing.angle + spec.placement.orientation.ln())
        .scale(scale.x.toFloat(), scale.y.toFloat(), scale.z.toFloat())
        .translate(-0.5, 0.0, -0.5)
}
*/

class RadiantBodyColorBuilder {
    var coldTint = ArgbColor(1f, 1f, 1f, 1f)
    var hotTint = ArgbColor(1f, 0.1f, 0.1f, 1f)
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

fun defaultRadiantBodyColor(): ThermalTint {
    return RadiantBodyColorBuilder().build()
}

class ThermalTint(
    val coldTint: ArgbColor,
    val hotTint: ArgbColor,
    val coldTemperature: Quantity<Temperature>,
    val hotTemperature: Quantity<Temperature>,
) {
    fun evaluate(temperature: Quantity<Temperature>) =
        ArgbColor.lerp(
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
    fun evaluateRGBL(temperature: Quantity<Temperature>, light: Double = 0.0): ArgbColor {
        val rgb = evaluate(temperature)

        return ArgbColor(
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

abstract class PartVisual(val part: Part, val visualizationContext: VisualizationContext) : Visual {
    protected val instancerProvider = visualizationContext.instancerProvider()

    private var deleted = false

    override fun update(partialTick: Float) { }

    final override fun delete() {
        if(deleted) {
            return
        }

        _delete()
        deleted = true
    }

    protected fun _delete() { }
}

class MultipartBlockEntityVisual2(
    ctx: VisualizationContext,
    blockEntity: MultipartBlockEntity,
    partialTick: Float
): AbstractBlockEntityVisual<MultipartBlockEntity>(ctx, blockEntity, partialTick), DynamicVisual, TickableVisual, LightUpdatedVisual {
    val parts = ArrayList<Part<*>>()
    val embedding: VisualEmbedding
    val storage = SpecialVisualStorage<PartVisual>()

    init {
        embedding = ctx.createEmbedding(Vec3i.ZERO)

        //blockEntity.bindRenderer()
    }

    override fun _delete() {
        storage.delete()
    }

    override fun collectCrumblingInstances(consumer: Consumer<Instance?>?) {
        // add the part being broken
    }

    override fun updateLight(partialTick: Float) {
        TODO("Not yet implemented")
    }

    override fun planFrame(): Plan<DynamicVisual.Context> = storage.dynamicVisuals

    override fun planTick(): Plan<TickableVisual.Context> = RunnablePlan
        .of(::handlePartUpdates)
        .then(storage.tickableVisuals)

    /**
     * This method is called per tick. But it runs in parallel with the other multipart visuals.
     * It dequeues all the part updates that were queued up.
     * These updates may indicate:
     *  - New parts added to the multipart.
     *  - Parts that were destroyed.
     * */
    private fun handlePartUpdates(ctx: TickableVisual.Context) {
        while (true) {
            val update = blockEntity.renderUpdates.poll()
                ?: break

            val part = update.part

            when (update.type) {
                PartUpdateType.Add -> {
                    if (!parts.contains(part)) {
                        parts.add(part)
                        part.renderer.setupRendering(this)
                    }

                    // Can get duplicate adds if the client first receives the parts (and clientAddPart enqueues updates)
                    // but just then the multipart renderer gets created and calls bindRenderer, which duplicate enqueues some more updates
                }
                PartUpdateType.Remove -> {
                    parts.remove(part)
                    part.destroyRenderer()
                }
            }
        }
    }
}


@ClientOnly
class MultipartBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: MultipartBlockEntity,
    partialTick: Float
) : AbstractBlockEntityVisual<MultipartBlockEntity>(ctx, blockEntity, partialTick), DynamicVisual {
    val context get() = this.visualizationContext

    private class Entry(val part: Part<*>) {
        var renderer = part.renderer
    }

    private val entries = ArrayList<Entry>()

    init {
        blockEntity.bindRenderer(this)
    }

    fun readBlockBrightness() = blockEntity.level!!.getBrightness(LightLayer.BLOCK, pos)

    fun readSkyBrightness() = blockEntity.level!!.getBrightness(LightLayer.SKY, pos)

    override fun planFrame(): Plan<DynamicVisual.Context> {
        LOG.warn("planFrame called")

        return RunnablePlan.of(::beginFrame)
    }

    /**
     * Called by flywheel at the start of each frame.
     * This applies any part updates (new or removed parts), and notifies the part renderers about the new frame.
     * */
    fun beginFrame(ctx: DynamicVisual.Context) {
        handlePartUpdates()

        for (entry in entries) {
            val part = entry.part
            val actualRenderer = part.renderer

            /**
             * This is a frakking pinch.
             * Flywheel creates new instances when breaking in survival, to render the whatever as crumbling.
             * But it doesn't destroy the old instance, and after crumbling is done, the crumbling instance gets destroyed,
             * which causes the part renderers get destroyed in [Part.destroyRenderer], and then flywheel dispatches beginFrame to that old instance,
             * and we need to do extra work to see if the part's renderer changed, and we need to re-frak it the old renderer (this one).
             * Also, I don't really see how we can make crumbling only affect a part, since flywheel doesn't tell us if the renderer is for crumbling or anything like that
             * probably need some sort of mixin?
             * */
            if(actualRenderer !== entry.renderer) {
                actualRenderer.setupRendering(this)
                entry.renderer = actualRenderer
            }

            actualRenderer.beginFrame()
        }
    }

    /**
     * Called by flywheel when a re-light is required.
     * This applies a re-light to all the part renderers.
     * */
    override fun updateLight(partialTick: Float) {
        for (part in entries) {
            part.renderer.relight(RelightSource.BlockEvent)
        }
    }

    /**
     * This method is called at the start of each frame.
     * It dequeues all the part updates that were handled on the game thread.
     * These updates may indicate:
     *  - New parts added to the multipart.
     *  - Parts that were destroyed.
     * */
    private fun handlePartUpdates() {
        while (true) {
            val update = blockEntity.renderUpdates.poll()
                ?: break

            val part = update.part

            when (update.type) {
                PartUpdateType.Add -> {
                    if (entries.none { it.part === part }) {
                        entries.add(Entry(part))
                        part.renderer.setupRendering(this)
                    }

                    // Can get duplicate adds if the client first receives the parts (and clientAddPart enqueues updates)
                    // but just then the multipart renderer gets created and calls bindRenderer, which duplicate enqueues some more updates
                }
                PartUpdateType.Remove -> {
                    entries.removeIf { it.part === part }
                    part.destroyRenderer()
                }
            }
        }
    }

    /**
     * Called by flywheel when this renderer is no longer needed.
     * This also calls a cleanup method on the part renderers.
     * */
    override fun _delete() {
        for (entry in entries) {
            entry.part.destroyRenderer()
        }

        blockEntity.unbindRenderer()
    }

    // Nullable for convenience

    /**
     * Relights the [models] using the block and skylight at this position.
     * */
    fun relightModels(models: Iterable<FlatLit?>) {
        val block = readBlockBrightness()
        val sky = readSkyBrightness()

        for (it in models) {
            it?.light(block, sky)?.handle()?.setChanged()
        }
    }

    /**
     * Relights the [models] using the block and skylight at this position.
     * */
    fun relightModels(vararg models: FlatLit?) = relightModels(models.asIterable())

    override fun collectCrumblingInstances(consumer: Consumer<Instance?>?) {
        // TODO: do
    }
}

fun interface PartRendererSupplier<T : Part<R>, R : PartRenderer> {
    fun create(part: T) : R
}
/*
fun interface SpecRendererSupplier<T : Spec<R>, R : SpecRenderer> {
    fun create(part: T) : R
}*/

class TestBlockEntityInstance<T : BlockEntity>(
    materialManager: VisualizationContext,
    blockEntity: T,
    partialTick: Float,
    val model: PartialModel,
    val transformer: (instance: TransformedInstance, renderer: TestBlockEntityInstance<T>, blockEntity: T) -> Unit,
) : AbstractBlockEntityVisual<T>(materialManager, blockEntity, partialTick) {
    var instance: TransformedInstance? = null

    init {
        instance?.delete()

        instance = visualizationContext.instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
            .createInstance()

        transformer(instance!!, this, blockEntity)
    }

    override fun _delete() {
        instance?.delete()
    }

    override fun collectCrumblingInstances(consumer: Consumer<Instance?>?) {

    }

    override fun updateLight(partialTick: Float) {
        relight(instance)
    }
}
/*
*//**
 * Part renderer with a single model.
 * *//*
open class BasicSpecRenderer(val spec: Spec<*>, val model: PartialModel, val scale: Vector3d = Vector3d.one) : SpecRenderer() {
    var yRotation = 0.0

    private var modelInstance: ModelData? = null

    override fun setupRendering() {
        buildInstance()
    }

    fun buildInstance() {
        modelInstance?.delete()
        modelInstance = createSpecInstance(partRenderer, model, spec, scale, yRotation)
    }

    override fun relight(source: RelightSource) {
        partRenderer.multipart.relightModels(modelInstance)
    }

    override fun beginFrame() {}

    override fun remove() {
        modelInstance?.delete()
    }
}*/

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
        net.minecraftforge.client.model.data.ModelData.EMPTY,
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
    rgba: ArgbColor,
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

fun VertexConsumer.eln2SubmitAABBLines(pose: PoseStack.Pose, aabb: BoundingBox3d, rgba: ArgbColor) {
    this.eln2SubmitAABBLines(
        pose,
        aabb,
        rgba.rF, rgba.gF, rgba.bF, rgba.aF
    )
}

fun VertexConsumer.eln2SubmitOBBAtLevelStage(stack: PoseStack, obb: OrientedBoundingBox3d, rgba: ArgbColor, camX: Double, camY: Double, camZ: Double) {
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

fun VertexConsumer.eln2SubmitOBBAtLevelStage(stack: PoseStack, obb: OrientedBoundingBox3d, rgba: ArgbColor, camera: Camera) = this.eln2SubmitOBBAtLevelStage(
    stack, obb, rgba,
    camera.position.x, camera.position.y, camera.position.z
)
