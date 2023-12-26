@file:Suppress("MemberVisibilityCanBePrivate")

package org.eln2.mc.client.render.foundation

import com.jozufozu.flywheel.api.MaterialManager
import com.jozufozu.flywheel.api.instance.DynamicInstance
import com.jozufozu.flywheel.backend.instancing.blockentity.BlockEntityInstance
import com.jozufozu.flywheel.core.Materials
import com.jozufozu.flywheel.core.PartialModel
import com.jozufozu.flywheel.core.materials.FlatLit
import com.jozufozu.flywheel.core.materials.model.ModelData
import com.jozufozu.flywheel.util.Color
import com.jozufozu.flywheel.util.transform.Transform
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.entity.BlockEntity
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.mathematics.Vector3d
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.sim.STANDARD_TEMPERATURE
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.ClientOnly
import org.eln2.mc.buildDirectionTable
import org.eln2.mc.client.render.DefaultRenderTypePartialModel
import org.eln2.mc.client.render.model
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.content.PartConnectionRenderInfo
import org.eln2.mc.common.content.PartConnectionRenderInfoSetConsumer
import org.eln2.mc.common.content.getPartConnectionAsContactSectionConnectionOrNull
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.common.specs.foundation.Spec
import org.eln2.mc.common.specs.foundation.SpecContainerPart
import org.eln2.mc.common.specs.foundation.SpecPartRenderer
import org.eln2.mc.common.specs.foundation.SpecRenderer
import org.eln2.mc.extensions.rotationFast
import org.eln2.mc.mathematics.Base6Direction3d
import kotlin.collections.HashSet
import kotlin.collections.Iterable
import kotlin.collections.Map
import kotlin.collections.asIterable
import kotlin.collections.forEach
import kotlin.collections.mapOf
import kotlin.math.max

fun createPartInstance(
    multipart: MultipartBlockEntityInstance,
    model: PartialModel,
    part: Part<*>,
    yRotation: Double = 0.0,
): ModelData {
    return multipart.materialManager
        .defaultSolid()
        .material(Materials.TRANSFORMED)
        .getModel(model)
        .createInstance()
        .loadIdentity()
        .transformPart(multipart, part, yRotation)
}

fun createSpecInstance(
    part: SpecPartRenderer,
    model: PartialModel,
    spec: Spec<*>,
    yRotation: Double
): ModelData {
    return part.multipart.materialManager
        .defaultSolid()
        .material(Materials.TRANSFORMED)
        .getModel(model)
        .createInstance()
        .loadIdentity()
        .transformSpec(part, spec, yRotation)
}

/**
 * Part renderer with a single model.
 * */
open class BasicPartRenderer(val part: Part<*>, val model: PartialModel) : PartRenderer() {
    var yRotation = 0.0

    private var modelInstance: ModelData? = null

    override fun setupRendering() {
        buildInstance()
    }

    fun buildInstance() {
        modelInstance?.delete()
        modelInstance = createPartInstance(multipart, model, part, yRotation)
    }

    override fun relight(source: RelightSource) {
        multipart.relightModels(modelInstance)
    }

    override fun beginFrame() {}

    override fun remove() {
        modelInstance?.delete()
    }
}

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

fun Part<ConnectedPartRenderer>.handleConnectedPartTag(tag: CompoundTag) = this.renderer.acceptConnections(
    if(tag.contains("connections")) {
        tag.getIntArray("connections")
    }
    else {
        IntArray(0)
    }
)

data class WireConnectionModelPartial(
    val planar: PolarModel,
    val inner: PolarModel,
    val wrapped: PolarModel
) {
    val variants = mapOf(
        CellPartConnectionMode.Planar to planar,
        CellPartConnectionMode.Inner to inner,
        CellPartConnectionMode.Wrapped to wrapped
    )
}

class ConnectedPartRenderer(
    val part: Part<*>,
    val body: PartialModel,
    val connections: Map<Base6Direction3d, WireConnectionModelPartial>
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
                when (direction) {
                    Base6Direction3d.Front -> 0.0
                    Base6Direction3d.Back -> kotlin.math.PI
                    Base6Direction3d.Left -> kotlin.math.PI / 2.0
                    Base6Direction3d.Right -> -kotlin.math.PI / 2.0
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

fun<T : Transform<T>> T.transformPart(instance: MultipartBlockEntityInstance, part: Part<*>, yRotation: Double = 0.0): T {
    val (dx, dy, dz) = partOffsetTable[part.placement.face.get3DDataValue()]

    return this
        .translate(instance.instancePosition)
        .translate(dx, dy, dz)
        .multiply(part.placement.face.rotationFast)
        .rotateYRadians(yRotation + part.placement.facing.angle)
        .translate(-0.5, 0.0, -0.5)
}

fun<T : Transform<T>> T.transformSpec(instance: SpecPartRenderer, spec: Spec<*>, yRotation: Double): T {
    val (dx, dy, dz) = partOffsetTable[instance.specPart.placement.face.get3DDataValue()]
    val (dx1, dy1, dz1) = spec.placement.mountingPointWorld - instance.specPart.placement.mountingPointWorld

    return this
        .translate(instance.instancePosition)
        .translate(dx + dx1, dy + dy1, dz + dz1)
        .multiply(instance.specPart.placement.face.rotationFast)
        .rotateYRadians(yRotation + instance.specPart.placement.facing.angle + spec.placement.orientation.ln())
        .translate(-0.5, 0.0, -0.5)
}

class RadiantBodyColorBuilder {
    var coldTint = Color(1f, 1f, 1f, 1f)
    var hotTint = Color(1f, 0.2f, 0.1f, 1f)
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
    val coldTint: Color,
    val hotTint: Color,
    val coldTemperature: Quantity<Temperature>,
    val hotTemperature: Quantity<Temperature>,
) {
    fun evaluate(temperature: Quantity<Temperature>) =
        colorLerp(
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
    fun evaluateRGBL(temperature: Quantity<Temperature>, light: Double = 0.0): Color {
        val rgb = evaluate(temperature)

        return Color(
            rgb.red,
            rgb.green,
            rgb.blue,
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
            ).toInt().coerceIn(0, 255)
        )
    }
}

@ClientOnly
class MultipartBlockEntityInstance(
    val materialManager: MaterialManager,
    blockEntity: MultipartBlockEntity,
) : BlockEntityInstance<MultipartBlockEntity>(materialManager, blockEntity), DynamicInstance {

    private val parts = HashSet<Part<*>>()

    override fun init() {
        // When this is called on an already initialized renderer (e.g. changing graphics settings),
        // we will get the parts in handlePartUpdates
        blockEntity.bindRenderer(this)
    }

    fun readBlockBrightness() = world.getBrightness(LightLayer.BLOCK, pos)

    fun readSkyBrightness() = world.getBrightness(LightLayer.SKY, pos)

    /**
     * Called by flywheel at the start of each frame.
     * This applies any part updates (new or removed parts), and notifies the part renderers about the new frame.
     * */
    override fun beginFrame() {
        handlePartUpdates()

        for (part in parts) {
            val renderer = part.renderer

            if (!renderer.isSetupWith(this)) {
                renderer.setupRendering(this)
            }

            renderer.beginFrame()
        }
    }

    /**
     * Called by flywheel when a re-light is required.
     * This applies a re-light to all the part renderers.
     * */
    override fun updateLight() {
        for (part in parts) {
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
            val update = blockEntity.renderUpdates.poll() ?: break
            val part = update.part

            when (update.type) {
                PartUpdateType.Add -> {
                    parts.add(part)
                    part.renderer.setupRendering(this)
                    part.renderer.relight(RelightSource.Setup)
                }

                PartUpdateType.Remove -> {
                    parts.remove(part)
                    part.destroyRenderer()
                }
            }
        }
    }

    /**
     * Called by flywheel when this renderer is no longer needed.
     * This also calls a cleanup method on the part renderers.
     * */
    override fun remove() {
        for (part in parts) {
            part.destroyRenderer()
        }

        blockEntity.unbindRenderer()
    }

    // Nullable for convenience

    /**
     * Relights the [models] using the block and skylight at this position.
     * */
    fun relightModels(models: Iterable<FlatLit<*>?>) {
        val block = readBlockBrightness()
        val sky = readSkyBrightness()

        for (it in models) {
            if(it != null) {
                it.setBlockLight(block)
                it.setSkyLight(sky)
            }
        }
    }

    /**
     * Relights the [models] using the block and skylight at this position.
     * */
    fun relightModels(vararg models: FlatLit<*>?) = relightModels(models.asIterable())
}

fun interface PartRendererSupplier<T : Part<R>, R : PartRenderer> {
    fun create(part: T) : R
}

class SimpleBlockEntityInstance<T : BlockEntity>(
    materialManager: MaterialManager,
    blockEntity: T,
    val model: DefaultRenderTypePartialModel<PartialModel>,
    val transformer: (instance : ModelData, renderer : SimpleBlockEntityInstance<T>, blockEntity : T) -> Unit
) : BlockEntityInstance<T>(materialManager, blockEntity) {
    var instance: ModelData? = null

    override fun init() {
        instance?.delete()

        instance = materialManager
            .model(model)
            .createInstance()
            .loadIdentity()

        transformer(instance!!, this, blockEntity)
    }

    override fun remove() {
        instance?.delete()
    }

    override fun updateLight() {
        if(instance != null) {
            relight(pos, instance)
        }
    }
}

/**
 * Part renderer with a single model.
 * */
open class BasicSpecRenderer(val spec: Spec<*>, val model: PartialModel) : SpecRenderer() {
    var yRotation = 0.0

    private var modelInstance: ModelData? = null

    override fun setupRendering() {
        buildInstance()
    }

    fun buildInstance() {
        modelInstance?.delete()
        modelInstance = createSpecInstance(partRenderer, model, spec, yRotation)
    }

    override fun relight(source: RelightSource) {
        partRenderer.multipart.relightModels(modelInstance)
    }

    override fun beginFrame() {}

    override fun remove() {
        modelInstance?.delete()
    }
}
