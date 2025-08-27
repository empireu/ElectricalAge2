package org.eln2.mc.client.render.foundation

import dev.engine_room.flywheel.api.instance.InstanceHandle
import dev.engine_room.flywheel.api.instance.InstanceType
import dev.engine_room.flywheel.api.layout.FloatRepr
import dev.engine_room.flywheel.api.layout.IntegerRepr
import dev.engine_room.flywheel.api.layout.LayoutBuilder
import dev.engine_room.flywheel.api.model.Model
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.TickableVisual
import dev.engine_room.flywheel.api.visual.Visual
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.instance.SimpleInstanceType
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.task.PlanMap
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps
import dev.engine_room.flywheel.lib.util.RendererReloadCache
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.LOG
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.WirePatchType.Inner
import org.eln2.mc.client.render.foundation.WirePatchType.Wrapped
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.content.Content
import org.eln2.mc.common.content.ElectricalHeatEnginePart
import org.eln2.mc.common.content.GridAnchorSpec
import org.eln2.mc.common.content.GridInterfacePart
import org.eln2.mc.common.content.GroundSpec
import org.eln2.mc.common.content.LightFixtureRenderer
import org.eln2.mc.common.content.PolarPoweredLightPart
import org.eln2.mc.common.content.RadiantBipolePartVisual
import org.eln2.mc.common.content.SolarLightPart
import org.eln2.mc.common.content.TerminalPoweredLightPart
import org.eln2.mc.common.parts.foundation.CellPartConnectionMode
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.common.parts.foundation.PartProvider
import org.eln2.mc.common.specs.foundation.Spec
import org.eln2.mc.common.specs.foundation.SpecProvider
import org.eln2.mc.common.specs.foundation.SpecVisualizer
import org.eln2.mc.extensions.bind
import org.eln2.mc.mathematics.MyColor
import org.eln2.mc.resource
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.IntBuffer

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
                    instance.translate(renderer.visualPosition).scale(1f, 3f, 1f)
                }
            }) { true }
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
            LightFixtureRenderer(
                ctx, part,
                FlwModels.TALL_GARDEN_LIGHT_CAGE,
                FlwModels.TALL_GARDEN_LIGHT_EMITTER
            )
        }

        setPartVisualizer<PolarPoweredLightPart>(Content.LIGHT_PART.part.get()) { ctx, part ->
            LightFixtureRenderer(
                ctx, part,
                FlwModels.SMALL_WALL_LAMP_CAGE,
                FlwModels.SMALL_WALL_LAMP_EMITTER
            )
        }

        setPartVisualizer<TerminalPoweredLightPart>(Content.LIGHT_PART_MICRO_GRID.part.get()) { ctx, part ->
            LightFixtureRenderer(
                ctx, part,
                FlwModels.SMALL_WALL_LAMP_CAGE_MICRO_GRID,
                FlwModels.SMALL_WALL_LAMP_EMITTER
            )
        }

        setPartVisualizer<ElectricalHeatEnginePart>(Content.ELECTRICAL_HEAT_ENGINE_PART.part.get()) { ctx, part ->
            RadiantBipolePartVisual(
                ctx, part,
                FlwModels.PELTIER_BODY,
                FlwModels.PELTIER_LEFT,
                FlwModels.PELTIER_RIGHT,
                true
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
}

class SpecialVisualStorage<V : Visual> {
    val visuals = ArrayList<V>()
    val dynamicVisuals = PlanMap<DynamicVisual, DynamicVisual.Context>()
    val tickableVisuals = PlanMap<TickableVisual, TickableVisual.Context>()

    fun add(visual: V, partialTick: Float) {
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

//fixme
/*

object ModelLightOverrideType : ModelType() {
    private val PROGRAM_SPEC: ResourceLocation = resource("block_light_override")

    override fun getProgramSpec() = PROGRAM_SPEC
}

object PolarType : Instanced<PolarData> {
    private val FORMAT: BufferLayout = BufferLayout.builder()
        .addItems(CommonItems.LIGHT)
        .addItems(CommonItems.RGBA, CommonItems.RGBA)
        .addItems(MatrixItems.MAT4, MatrixItems.MAT3)
        .build()

    private val PROGRAM_SPEC: ResourceLocation = resource("polar")

    override fun create() = PolarData()
    override fun getLayout() = FORMAT
    override fun getWriter(backing: VecBuffer) = PolarWriterUnsafe(backing, this)
    override fun getProgramSpec() = PROGRAM_SPEC
}

class PolarData : InstanceData(), FlatLit<PolarData>, Transform<PolarData> {
    var blockLight = 0
    var skyLight = 0
    var color1 = Color.WHITE
    var color2 = Color.WHITE
    val model = Matrix4f()
    val normal = Matrix3f()

    override fun setBlockLight(blockLight: Int): PolarData {
        markDirty()
        this.blockLight = blockLight
        return this
    }

    override fun setSkyLight(skyLight: Int): PolarData {
        markDirty()
        this.skyLight = skyLight
        return this
    }

    override fun getPackedLight() = LightTexture.pack(blockLight, skyLight)

    fun setColor1(value: Color) : PolarData {
        markDirty()
        color1 = value
        return this
    }

    fun setColor2(value: Color) : PolarData {
        markDirty()
        color2 = value
        return this
    }


    fun loadIdentity(): PolarData {
        markDirty()
        model.identity()
        normal.identity()
        return this
    }

    override fun multiply(quaternion: Quaternionf): PolarData {
        markDirty()
        model.rotate(quaternion)
        normal.rotate(quaternion)
        return this
    }

    override fun scale(pX: Float, pY: Float, pZ: Float): PolarData {
        markDirty()
        model.scale(pX, pY, pZ)
        if (pX == pY && pY == pZ) {
            if (pX > 0.0f) {
                return this
            }
            normal.scale(-1.0f)
        }
        val f = 1.0f / pX
        val f1 = 1.0f / pY
        val f2 = 1.0f / pZ
        val f3 = Mth.fastInvCubeRoot(f * f1 * f2)
        normal.scale(f3 * f, f3 * f1, f3 * f2)
        return this
    }

    override fun translate(x: Double, y: Double, z: Double): PolarData {
        markDirty()
        model.translate(x.toFloat(), y.toFloat(), z.toFloat())
        return this
    }

    override fun mulPose(pose: Matrix4f?): PolarData {
        model.mul(pose)
        return this
    }

    override fun mulNormal(normal: Matrix3f?): PolarData {
        this.normal.mul(normal)
        return this
    }
}

class PolarWriterUnsafe(backingBuffer: VecBuffer, vertexType: StructType<PolarData>) : UnsafeBufferWriter<PolarData>(backingBuffer, vertexType) {
    override fun writeInternal(s: PolarData) {
        val ptr = writePointer
        MemoryUtil.memPutByte(ptr + 0, (s.blockLight shl 4).toByte())
        MemoryUtil.memPutByte(ptr + 1, (s.skyLight shl 4).toByte())

        // Todo blit in one operation? I tried to write the int value but it is not laid out like we need and I don't have time to fix it right now
        MemoryUtil.memPutByte(ptr + 2, s.color1.red.toByte())
        MemoryUtil.memPutByte(ptr + 3, s.color1.green.toByte())
        MemoryUtil.memPutByte(ptr + 4, s.color1.blue.toByte())
        MemoryUtil.memPutByte(ptr + 5, s.color1.alpha.toByte())

        MemoryUtil.memPutByte(ptr + 6, s.color2.red.toByte())
        MemoryUtil.memPutByte(ptr + 7, s.color2.green.toByte())
        MemoryUtil.memPutByte(ptr + 8, s.color2.blue.toByte())
        MemoryUtil.memPutByte(ptr + 9, s.color2.alpha.toByte())

        MatrixWrite.writeUnsafe(s.model, ptr + 10)
        MatrixWrite.writeUnsafe(s.normal, ptr + 74)
    }
}
*/

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
