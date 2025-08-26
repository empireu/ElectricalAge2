package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import kotlinx.serialization.Serializable
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.sim.STANDARD_TEMPERATURE
import org.ageseries.libage.sim.ThermalMass
import org.eln2.mc.ClientOnly
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.cells.foundation.InternalTemperatureConsumer
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.network.serverToClient.PacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.mathematics.ArgbColor

/**
 * Represents a game object that is rendered with a [RadiantBodyVisual].
 * */
interface RadiantGameObject {
    /**
     * Gets the temperature of the object.
     * */
    @ClientOnly
    val renderTemperature: Quantity<Temperature>
}

class RadiatorPart(
    ci: PartCreateInfo,
    val radiantColor: ThermalTint = ThermalTintBuilder().apply {
        coldTint = ArgbColor(0.0f, 1f, 1f, 1f)
        hotTint = ArgbColor( 0.4f, 1f, 0.1f, 0.1f)
    }.build()
) : CellPart<ThermalWireCell>(ci, Content.THERMAL_RADIATOR_CELL.get()), InternalTemperatureConsumer, RadiantGameObject, ComponentDisplay {
    override var renderTemperature: Quantity<Temperature> = STANDARD_TEMPERATURE
        private set

    override fun createVisual(ctx: MultipartVisualizationContext) =
        RadiantBodyVisual(ctx, this, FlwModels.RADIATOR, radiantColor)

    override fun registerPackets(builder: PacketHandlerBuilder) {
        builder.withHandler<Sync> {
            renderTemperature = (Quantity(it.temperature, KELVIN))
        }
    }

    override fun onInternalTemperatureChanges(dirty: List<ThermalMass>) {
        sendBulkPacket(Sync(!dirty.first().temperature))
    }

    @Serializable
    private data class Sync(val temperature: Double)

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(cell.thermalWire.thermalBody.temperature)
    }
}

class RadiantBodyVisual<P>(
    ctx: MultipartVisualizationContext,
    part: P,
    val model: PartialModel,
    val color: ThermalTint,
    val rotation: Double = 0.0
) : AbstractPartVisual<P>(ctx, part), SimpleDynamicVisual where P : Part, P : RadiantGameObject {
    private val bodyInstance = ctx.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, Models.partial(model))
        .createInstance()
        .also { it.partTransformation(part, yRotation = rotation) }

    private var temperature = Quantity(-1.0, KELVIN)

    override fun beginFrame(ctx: DynamicVisual.Context?) {
        val desiredTemperature = part.renderTemperature

        if (desiredTemperature != temperature) {
            temperature = desiredTemperature

            val color = color.evaluate(temperature)
            bodyInstance.color(color.r, color.g, color.b)
            bodyInstance.lightOverride = color.a / 255.0f
            bodyInstance.handle().setChanged()
        }
    }

    override fun updateLight(partialTick: Float) {
        visualizationContext.parent.relightInstances(bodyInstance)
    }

    override fun _delete() {
        bodyInstance.delete()
    }
}
/*

class RadiantBipoleRenderer(
    val part: Part<*>,
    val body: PartialModel,
    val left: PartialModel,
    val right: PartialModel,
    val leftColor: ThermalTint,
    val rightColor: ThermalTint,
) : PartRenderer(), PartRendererStateStorage {
    constructor(
        part: Part<*>,
        body: PartialModel,
        left: PartialModel,
        right: PartialModel,
    ) : this(part, body, left, right, defaultRadiantBodyColor(), defaultRadiantBodyColor())

    private var bodyInstance: ModelData? = null
    private var leftInstance: ModelData? = null
    private var rightInstance: ModelData? = null

    private val leftSideUpdate = AtomicUpdate<Quantity<Temperature>>()
    private val rightSideUpdate = AtomicUpdate<Quantity<Temperature>>()
    private var leftSide: Quantity<Temperature>? = null
    private var rightSide: Quantity<Temperature>? = null

    override fun restoreSnapshot(renderer: PartRenderer) {
        if(renderer is RadiantBipoleRenderer) {
            renderer.leftSide?.run(this::updateLeftSideTemperature)
            renderer.rightSide?.run(this::updateRightSideTemperature)
        }
    }

    fun updateLeftSideTemperature(value: Quantity<Temperature>) = leftSideUpdate.setLatest(value)

    fun updateRightSideTemperature(value: Quantity<Temperature>) = rightSideUpdate.setLatest(value)

    private fun createPoleInstance(model: PartialModel) =
        multipart.materialManager
            .defaultSolid()
            .material(ModelLightOverrideType)
            .getModel(model)
            .createInstance()
            .loadIdentity()
            .transformPart(multipart, part)
            .also {
                it.setColor(Color(1.0f, 1.0f, 1.0f, 0.0f))
            }

    override fun setupRendering() {
        bodyInstance?.delete()
        leftInstance?.delete()
        rightInstance?.delete()
        bodyInstance = createPartInstance(multipart, body, part)
        leftInstance = createPoleInstance(left)
        rightInstance = createPoleInstance(right)
    }

    private fun applyTemperature(tint: ThermalTint, model: ModelData?, temperature: Quantity<Temperature>) {
        if(model == null) {
            return
        }

        val coreLightLevel = multipart.readBlockBrightness().toDouble()

        model.setColor(tint.evaluateRGBL(temperature, coreLightLevel))
    }

    override fun beginFrame() {
        leftSideUpdate.consume {
            leftSide = it
            applyTemperature(leftColor, leftInstance, it)
        }

        rightSideUpdate.consume {
            rightSide = it
            applyTemperature(rightColor, rightInstance, it)
        }
    }

    override fun relight(source: RelightSource) {
        multipart.relightModels(bodyInstance, leftInstance, rightInstance)
    }

    override fun remove() {
        bodyInstance?.delete()
        leftInstance?.delete()
        rightInstance?.delete()
    }
}
*/

