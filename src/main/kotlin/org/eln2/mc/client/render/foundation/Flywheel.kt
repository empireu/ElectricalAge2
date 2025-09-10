package org.eln2.mc.client.render.foundation

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.instance.InstanceHandle
import dev.engine_room.flywheel.api.instance.InstanceType
import dev.engine_room.flywheel.api.layout.FloatRepr
import dev.engine_room.flywheel.api.layout.IntegerRepr
import dev.engine_room.flywheel.api.layout.LayoutBuilder
import dev.engine_room.flywheel.api.material.Material
import dev.engine_room.flywheel.api.model.Model
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.TickableVisual
import dev.engine_room.flywheel.api.visual.Visual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.SimpleInstanceType
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.task.PlanMap
import dev.engine_room.flywheel.lib.transform.Affine
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps
import dev.engine_room.flywheel.lib.util.RendererReloadCache
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.entity.BlockEntity
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.buildDirectionTable
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.WirePatchType.Inner
import org.eln2.mc.client.render.foundation.WirePatchType.Wrapped
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntityVisual
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.content.*
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.common.specs.foundation.*
import org.eln2.mc.extensions.bind
import org.eln2.mc.extensions.rotationFast
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.resource
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.function.Consumer
import kotlin.math.PI

object FlwVisualizerRegistry {
    private val partVisualizerRegistry = HashMap<PartProvider, PartVisualizer<*>>()
    private val specVisualizerRegistry = HashMap<SpecProvider, SpecVisualizer<*>>()

    private fun <P : Part> setPartVisualizer(partProvider: PartProvider, visualizer: PartVisualizer<P>) {
        if(partVisualizerRegistry.contains(partProvider)) {
            error("Duplicate register part visualizer ${partProvider.id}")
        }

        partVisualizerRegistry[partProvider] = visualizer
    }

    private fun <S : Spec> setSpecVisualizer(specProvider: SpecProvider, visualizer: SpecVisualizer<S>) {
        if(specVisualizerRegistry.contains(specProvider)) {
            error("Duplicate register spec visualizer ${specProvider.id}")
        }

        specVisualizerRegistry[specProvider] = visualizer
    }

    fun getPartVisualizer(provider: PartProvider) = partVisualizerRegistry[provider]
        ?: error("Visualizer for part $provider not registered")

    fun getSpecVisualizer(provider: SpecProvider) = specVisualizerRegistry[provider]
        ?: error("Visualizer for spec $provider not registered")

    fun registerBlockEntityVisualizers() {
        VisualizerRegistry.setVisualizer(
            BlockRegistry.MULTIPART_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::MultipartBlockEntityVisual) { true }
        )

        VisualizerRegistry.setVisualizer(
            Content.GRID_PASS_THROUGH_POLE_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer({ ctx, blockEntity, partialTick ->
                TestBlockEntityVisual(ctx, blockEntity, partialTick,FlwModels.POLE_TEMPORARY) { instance, renderer ->
                    instance.translate(renderer.visualPosition)
                }
            }) { true }
        )

        VisualizerRegistry.setVisualizer(
            Content.LAMP_POLE_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::LampPoleBlockEntityVisual) { true }
        )
    }

    fun registerPartVisualizers() {
        setPartVisualizer<SolarLightPart>(Content.SMALL_GARDEN_LIGHT.part.get()) { ctx, part ->
            BasicPartVisual(
                ctx,
                part,
                FlwModels.SMALL_GARDEN_LIGHT
            )
        }

        setPartVisualizer<SolarLightPart>(Content.TALL_GARDEN_LIGHT.part.get()) { ctx, part ->
            LightFixturePartVisual(
                ctx, part,
                FlwModels.TALL_GARDEN_LIGHT_CAGE,
                FlwModels.TALL_GARDEN_LIGHT_EMITTER
            )
        }

        setPartVisualizer<PolarPoweredLightPart>(Content.LIGHT_PART.part.get()) { ctx, part ->
            LightFixturePartVisual(
                ctx, part,
                FlwModels.SMALL_WALL_LAMP_CAGE,
                FlwModels.SMALL_WALL_LAMP_EMITTER
            )
        }

        setPartVisualizer<TerminalPoweredLightPart>(Content.LIGHT_PART_MICRO_GRID.part.get()) { ctx, part ->
            LightFixturePartVisual(
                ctx, part,
                FlwModels.SMALL_WALL_LAMP_CAGE_MICRO_GRID,
                FlwModels.SMALL_WALL_LAMP_EMITTER
            )
        }

        setPartVisualizer<ElectricalHeatEnginePart>(Content.ELECTRICAL_HEAT_ENGINE_PART.part.get()) { ctx, part ->
            ElectricalHeatEnginePartVisual(
                ctx, part
            )
        }

        setPartVisualizer<GridInterfacePart>(Content.MICRO_GRID_INTERFACE_PART.part.get()) { ctx, part ->
            ConnectedPartVisual(
                ctx, part,
                FlwModels.MICRO_GRID_INTERFACE,
                FlwModels.STANDARD_CONNECTION
            )
        }

        setPartVisualizer<GridInterfacePart>(Content.POWER_GRID_INTERFACE_PART.part.get()) { ctx, part ->
            ConnectedPartVisual(
                ctx, part,
                FlwModels.POWER_GRID_INTERFACE,
                FlwModels.STANDARD_CONNECTION
            )
        }

        setPartVisualizer<PhotovoltaicPanelPart>(Content.PHOTOVOLTAIC_PANEL_PART.part.get()) { ctx, part ->
            BasicPartVisual(
                ctx, part,
                FlwModels.SOLAR_PANEL_ONE_BLOCK
            )
        }

        setPartVisualizer<BatteryPart>(Content.BATTERY_PART_12V.part.get()) { ctx, part ->
            BasicPartVisual(
                ctx, part,
                FlwModels.BATTERY
            )
        }
    }

    fun registerSpecVisualizers() {
        setSpecVisualizer<GroundSpec>(Content.GROUND_SPEC.spec.get()) { ctx, spec ->
            BasicSpecVisual(
                ctx, spec,
                FlwModels.GROUND_MICRO_GRID
            )
        }

        setSpecVisualizer<GridAnchorSpec>(Content.MICRO_GRID_ANCHOR_SPEC.spec.get()) { ctx, spec ->
            BasicSpecVisual(
                ctx, spec,
                FlwModels.MICRO_GRID_ANCHOR
            )
        }

        setSpecVisualizer<BatterySpec>(Content.BATTERY_SPEC_12V.spec.get()) { ctx, spec ->
            BasicSpecVisual(
                ctx, spec,
                FlwModels.BATTERY,
                scale = Vector3d(Content.BATTERY_SPEC_12V_SCALE)
            )
        }

        setSpecVisualizer<DcToDcConverterSpec>(Content.DC_TO_DC_CONVERTER_SPEC.spec.get()) { ctx, spec ->
            BasicSpecVisual(
                ctx, spec,
                FlwModels.SMALL_DC_TO_DC_CONVERTER
            )
        }
    }
}

object FlwMaterials {
    fun init() {
        LOG.info("Created ELN2 Flw materials.")
    }
}

object FlwInstanceTypes {
    val TRANSFORMED_POLAR: SimpleInstanceType<TransformedPolarInstance> = SimpleInstanceType.builder(::TransformedPolarInstance)
        .cullShader(resource("instance/cull/default.glsl"))
        .vertexShader(resource("instance/transformed_polar.vert"))
        .layout(LayoutBuilder.create()
            .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
            .vector("light", IntegerRepr.SHORT, 2)
            .vector("overlay", IntegerRepr.SHORT, 2)
            .vector("color1", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
            .vector("color2", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
            .matrix("pose", FloatRepr.FLOAT, 4)
            .build()
        )
        .writer { ptr, instance ->
            MemoryUtil.memPutByte(ptr + 0, instance.red)
            MemoryUtil.memPutByte(ptr + 1, instance.green)
            MemoryUtil.memPutByte(ptr + 2, instance.blue)
            MemoryUtil.memPutByte(ptr + 3, instance.alpha)
            ExtraMemoryOps.put2x16(ptr + 4, instance.light)
            ExtraMemoryOps.put2x16(ptr + 8, instance.overlay)
            instance.color1.blit(ptr + 12)
            instance.color2.blit(ptr + 16)
            ExtraMemoryOps.putMatrix4f(ptr + 20, instance.pose)
        }
        .build()

    val TRANSFORMED_LIGHT_OVERRIDE: InstanceType<TransformedLightOverrideInstance> = SimpleInstanceType.builder(::TransformedLightOverrideInstance)
        .cullShader(resource("instance/cull/default.glsl"))
        .vertexShader(resource("instance/transformed_light_override.vert"))
        .layout(
            LayoutBuilder.create()
                .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                .vector("overlay", IntegerRepr.SHORT, 2)
                .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                .matrix("pose", FloatRepr.FLOAT, 4)
                .scalar("lightOverride", FloatRepr.FLOAT)
                .build()
        )
        .writer { ptr: Long, instance: TransformedLightOverrideInstance ->
            MemoryUtil.memPutByte(ptr + 0, instance.red)
            MemoryUtil.memPutByte(ptr + 1, instance.green)
            MemoryUtil.memPutByte(ptr + 2, instance.blue)
            MemoryUtil.memPutByte(ptr + 3, instance.alpha)
            ExtraMemoryOps.put2x16(ptr + 4, instance.overlay)
            ExtraMemoryOps.put2x16(ptr + 8, instance.light)
            ExtraMemoryOps.putMatrix4f(ptr + 12, instance.pose)
            MemoryUtil.memPutFloat(ptr + 76, instance.lightOverride)
        }
        .build()

    fun init() {
        LOG.info("Created ELN2 Flw instance types.")
    }
}

class TransformedPolarInstance(
    type: InstanceType<TransformedPolarInstance>,
    handle: InstanceHandle,
) : TransformedInstance(type, handle) {
    var color1 = MyColor(0)
    var color2 = MyColor(0)
}

class TransformedLightOverrideInstance(
    type: InstanceType<TransformedLightOverrideInstance>,
    handle: InstanceHandle
) : TransformedInstance(type, handle) {
    var lightOverride = 0.0f

    fun colorWithOverride(tint: ThermalTint, temperature: Quantity<Temperature>) : TransformedLightOverrideInstance {
        val color = tint.evaluate(temperature)
        color(color.r, color.g, color.b)
        lightOverride = color.a / 255.0f
        return this
    }
}

class SpecialVisualStorage<V : Visual> {
    val visuals = ArrayList<V>()
    val dynamicVisuals = PlanMap<DynamicVisual, DynamicVisual.Context>()
    val tickableVisuals = PlanMap<TickableVisual, TickableVisual.Context>()

    fun add(visual: V) {
        // Done once so no performance issues
        if(!visuals.add(visual)){
            error("Duplicate add visual $visual")
        }

        if(visual is DynamicVisual) {
            dynamicVisuals.add(visual, visual.planFrame())
        }

        if(visual is TickableVisual) {
            tickableVisuals.add(visual, visual.planTick())
        }
    }

    fun delete() {
        visuals.forEach { it.delete() }
        visuals.clear()
        dynamicVisuals.clear()
        tickableVisuals.clear()
    }

    fun remove(visual: Visual){
        visuals.remove(visual)

        if(visual is DynamicVisual) {
            dynamicVisuals.remove(visual)
        }

        if(visual is TickableVisual) {
            tickableVisuals.remove(visual)
        }
    }
}

object SpecialModels {
    private val PARTIAL_WITH_MATERIAL = RendererReloadCache<PartialWithMaterial, Model> { (partial, material) ->
        BakedModelBuilder.create(partial.get())
            .materialFunc { _, _ -> material }
            .build()
    }

    private data class PartialWithMaterial(val partialModel: PartialModel, val material: Material)

    fun partial(model: PartialModel, material: Material) = PARTIAL_WITH_MATERIAL.get(
        PartialWithMaterial(model, material)
    )
}

/**
 * Wraps a [PartialModel] and applies changes.
 * When the [get] is requested for the first time, [applyChanges] is called to modify a copy of the partial model.
 * */
abstract class ProcessedModel(modelLocation: ResourceLocation) {
    companion object {
        val CACHE: RendererReloadCache<ProcessedModel, Model> =
            RendererReloadCache<ProcessedModel, Model> { it: ProcessedModel ->
                BakedModelBuilder.create(it.model ?: error("Partial model was null ${it.partialModel.modelLocation()}")).build()
            }
    }

    val partialModel: PartialModel = PartialModel.of(modelLocation)

    private val obj = Any()
    private var lastInitialModel: BakedModel? = null
    private var processedModel: BakedModel? = null

    private val model: BakedModel? get() {
        synchronized(obj) {
            val partial = partialModel.get()

            if(partial == null) {
                lastInitialModel = null
                processedModel = null
                return null
            }

            if(lastInitialModel != partial) {
                lastInitialModel = partial
                processedModel = applyChanges(partial.bind())
            }

            return processedModel!!
        }
    }

    /**
     * Applies the changes to the [bakedModel]. The [bakedModel] is a copy of the model baked for the [partialModel], so it can be mutated freely.
     * @return The changed [bakedModel] or another model entirely.
     * */
    protected abstract fun applyChanges(bakedModel: BakedModel): BakedModel

    fun get(): Model = CACHE.get(this)
}

/**
 * Applies a post-processing step needed by the polar instance. The model must:
 * - Not have quads oriented towards north and south
 * - Be like a tube
 *
 * The vertex data is rotated so that, when it gets written to the vertex buffer, a special ordering of vertices is obtained:
 * Vertices 0, 1 are on one "pole" of the model (min z) and vertices 2, 3 are on the other (max z)
 * */
open class PolarModel(modelLocation: ResourceLocation) : ProcessedModel(modelLocation) {
    override fun applyChanges(bakedModel: BakedModel): BakedModel {
        val quadPositionAttributes = HashMap<BakedQuad, ArrayList<Vector3d>>()

        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        bakedModel.getQuads(null, null, null).forEach { quad ->
            if(quad.direction == Direction.NORTH || quad.direction == Direction.SOUTH) {
                error("Invalid connection model")
            }

            val positionList = ArrayList<Vector3d>()
            quadPositionAttributes[quad] = positionList

            require(quad.vertices.size == 32)

            val buffer = ByteBuffer.allocate(32)
            val intView = buffer.asIntBuffer()

            for (i in 0 until 4) {
                intView.clear()
                intView.put(quad.vertices, i * 8, 8)

                positionList.add(
                    Vector3d(
                        buffer.getFloat(0).toDouble(),
                        buffer.getFloat(4).toDouble(),
                        buffer.getFloat(8).toDouble(),
                    )
                )
            }
        }

        quadPositionAttributes.forEach { (quad, positions) ->
            require(positions.size == 4)

            data class VertexNode(
                val index: Int,
                val position: Vector3d,
                val next: Int,
            )

            val nodes = positions.mapIndexed { index, position ->
                val nextIndex = if(index == 3) {
                    0 // wrap around (circular linked list)
                }
                else {
                    index + 1
                }

                VertexNode(
                    index,
                    position,
                    nextIndex
                )
            }

            val headNode = nodes
                .sortedBy { it.position.z }
                .take(2)
                .let {
                    if(it[0].next == it[1].index) {
                        it[0]
                    }
                    else if(it[1].next == it[0].index) {
                        it[1]
                    }
                    else {
                        error("Disjoint head vertices")
                    }
                }

            val indices = headNode.let { head ->
                val nodeList = arrayListOf(head)

                repeat(3) {
                    nodeList.add(nodes[nodeList.last().next])
                }

                require(nodeList.distinct().size == nodeList.size && nodeList.size == 4)

                nodeList.map { it.index }
            }

            val vertexRecords = let {
                val buffer = ByteBuffer.allocate(8 * 4)
                val intView = buffer.asIntBuffer()
                val results = ArrayList<IntArray>(4)

                for (i in 0 until 4) {
                    intView.clear()
                    intView.put(quad.vertices, i * 8, 8)

                    val array = IntArray(8)

                    intView.rewind()
                    intView.get(array)
                    results.add(array)
                }

                results
            }

            val writer = IntBuffer.wrap(quad.vertices)
            writer.clear()

            indices.forEach { index ->
                writer.put(vertexRecords[index])
            }
        }

        return bakedModel
    }
}

/**
 * Represents a type of modification needed to a standard "tube" wire model that is placed in a specific part configuration.
 * The three possible configurations are:
 * - Straight - the wire connects with another wire in the same plane. The model is designed for this, so no changes are required.
 * - [Wrapped] - the wire connects to another wire on a perpendicular face of the same block, around the exterior. The patcher needs to extend the endpoint vertices so there is no gap at the corner.
 * - [Inner] - the wire connects to another wire on a perpendicular face of the same block, inside the wire. The patcher needs to retract the endpoint vertices so there is no overlap at the corner.
 * */
enum class WirePatchType {
    /**
     * Patches the chosen face to wrap around the corner of a block by translating vertices forward to create a sleeve
     * */
    Wrapped,
    /**
     * Patches the chosen face to pack in the corner of a block by translating vertices backward to create a slit
     * */
    Inner
}

class WirePatchPolarModel(modelLocation: ResourceLocation, val patchType: WirePatchType) : PolarModel(modelLocation) {
    override fun applyChanges(bakedModel: BakedModel): BakedModel {
        val polarModelSource = super.applyChanges(bakedModel)

        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        val quads = polarModelSource.getQuads(null, null, null).map {
            if(it.direction == Direction.NORTH || it.direction == Direction.SOUTH) {
                error("Invalid connection model")
            }

            it
        }.associateBy { it.direction }

        val headPositions = let {
            val results = HashMap<BakedQuad, List<Pair<Int, Vector3d>>>()

            quads.values.forEach { quad ->
                val positionList = ArrayList<Pair<Int, Vector3d>>(2)
                val buffer = ByteBuffer.allocate(32)
                val intView = buffer.asIntBuffer()

                for (i in 0 until 2) {
                    intView.clear()
                    intView.put(quad.vertices, i * 8, 8)

                    val vector = Vector3d(
                        buffer.getFloat(0).toDouble(),
                        buffer.getFloat(4).toDouble(),
                        buffer.getFloat(8).toDouble(),
                    )

                    positionList.add(i to vector)
                }

                positionList.sortBy { it.second.y }

                results[quad] = positionList
            }

            results
        }

        val size = headPositions[quads[Direction.EAST]!!]!!.let {
            it[1].second.y - it[0].second.y
        }

        require(size > 0.0)

        fun write(quad: BakedQuad, i: Int, value: Double) {
            val writer = IntBuffer.wrap(quad.vertices)
            writer.position(8 * i + 2)
            writer.put(value.toFloat().toBits())
        }

        val dz = when(patchType) {
            WirePatchType.Wrapped -> -size
            WirePatchType.Inner -> +size
        }

        quads[Direction.UP]!!.also { roof ->
            headPositions[roof]!!.forEach { p ->
                write(roof, p.first, p.second.z + dz)
            }
        }

        listOf(Direction.EAST, Direction.WEST).map { quads[it]!! }.forEach { wall ->
            val hVertex = headPositions[wall]!![1]
            write(wall, hVertex.first, hVertex.second.z + dz)
        }

        return polarModelSource
    }
}

/**
 * Holds variants of a wire connection model.
 * @param hubConnectionPlanar Planar variant of junction-hub connection
 * @param hubConnectionInner Inner variant of junction-hub connection
 * @param hubConnectionWrapped Wrapped variant of junction-hub connection
 * @param fullConnectionPlanar Planar variant of junction-center connection
 * @param fullConnectionInner Inner variant of junction-center connection
 * @param fullConnectionWrapped Wrapped variant of junction-center connection
 * */
class WireConnectionModel(
    hubConnectionPlanar: PolarModel,
    hubConnectionInner: PolarModel,
    hubConnectionWrapped: PolarModel,
    fullConnectionPlanar: PolarModel,
    fullConnectionInner: PolarModel,
    fullConnectionWrapped: PolarModel,
) {
    /**
     * Gets the Planar, Inner and Wrapped variants by fullness.
     * */
    val variants = mapOf(
        false to mapOf(
            CellPartConnectionMode.Planar to hubConnectionPlanar,
            CellPartConnectionMode.Inner to hubConnectionInner,
            CellPartConnectionMode.Wrapped to hubConnectionWrapped
        ),
        true to mapOf(
            CellPartConnectionMode.Planar to fullConnectionPlanar,
            CellPartConnectionMode.Inner to fullConnectionInner,
            CellPartConnectionMode.Wrapped to fullConnectionWrapped
        )
    )
}

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
     * Tracks the last acknowledged version of the connections from [org.eln2.mc.common.content.WirePart.RenderState].
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
    when (it) {
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

class TestBlockEntityVisual<T : BlockEntity>(
    ctx: VisualizationContext,
    blockEntity: T,
    partialTick: Float,
    model: PartialModel,
    transformer: (instance: TransformedInstance, visual: TestBlockEntityVisual<T>) -> Unit,
) : AbstractBlockEntityVisual<T>(ctx, blockEntity, partialTick) {
    var instance: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, SpecialModels.partial(model, FlwMaterials.TRANSLUCENT_SMOOTH_LIT))
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
