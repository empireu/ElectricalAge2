package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.lib.instance.InstanceTypes
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
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.InternalTemperatureConsumer
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.client.render.foundation.MyColor

/**
 * Represents a game object that is rendered with a [RadiantBodyPartVisual].
 * */
interface RadiantMonopoleGameObject {
    /**
     * Gets the temperature of the object.
     * */
    @ClientOnly
    val renderTemperature: Quantity<Temperature>
}

class RadiatorPart(
    ci: PartCreateInfo,
    val radiantColor: ThermalTint = ThermalTintBuilder().apply {
        coldTint = MyColor(0.0f, 1f, 1f, 1f)
        hotTint = MyColor( 0.4f, 1f, 0.1f, 0.1f)
    }.build()
) : CellPart<ThermalWireCell>(ci, Content.THERMAL_RADIATOR_CELL.get()), InternalTemperatureConsumer, RadiantMonopoleGameObject, ComponentDisplay {
    override var renderTemperature: Quantity<Temperature> = STANDARD_TEMPERATURE
        private set

    override fun createVisual(ctx: MultipartVisualizationContext) =
        RadiantBodyPartVisual(ctx, this, FlwModels.RADIATOR, radiantColor)

    override fun setupPacketsOnClient(builder: ClientSidePacketHandlerBuilder) {
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

class RadiantBodyPartVisual<P>(
    ctx: MultipartVisualizationContext,
    part: P,
    val model: PartialModel,
    val color: ThermalTint,
    val rotation: Double = 0.0
) : AbstractPartVisual<P>(ctx, part), SimpleDynamicVisual where P : Part, P : RadiantMonopoleGameObject {
    private val instance = ctx.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, Models.partial(model))
        .createInstance()
        .also { it.partTransformation(visualizationContext.parent, part, yRotation = rotation) }

    private var temperature = Quantity(-1.0, KELVIN)

    override fun beginFrame(ctx: DynamicVisual.Context?) {
        val desiredTemperature = part.renderTemperature
        if (desiredTemperature != temperature) {
            temperature = desiredTemperature

            val color = color.evaluate(temperature)
            instance.color(color.r, color.g, color.b)
            instance.lightOverride = color.a / 255.0f
            instance.handle().setChanged()
        }
    }

    override fun updateLight(partialTick: Float) {
        visualizationContext.parent.relightInstances(instance)
    }

    override fun _delete() {
        instance.delete()
    }
}

/**
 * Represents a game object that is rendered with a [RadiantBipolePartVisual].
 * */
interface RadiantBipoleGameObject {
    @ClientOnly
    val renderTemperature1: Quantity<Temperature>

    @ClientOnly
    val renderTemperature2: Quantity<Temperature>
}

class RadiantBipolePartVisual<P>(
    ctx: MultipartVisualizationContext,
    part: P,
    body: PartialModel,
    model1: PartialModel,
    model2: PartialModel,
    val flip: Boolean = false,
    val tint1: ThermalTint = ThermalTint.DEFAULT_LIGHT_OVERRIDE,
    val tint2: ThermalTint = ThermalTint.DEFAULT_LIGHT_OVERRIDE
) : AbstractPartVisual<P>(ctx, part), SimpleDynamicVisual where P : Part, P : RadiantBipoleGameObject {
    private var bodyInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(body))
        .createInstance()
        .also { it.partTransformation(ctx.parent, part) }

    private var instance1 = visualizationContext.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, Models.partial(model1))
        .createInstance()
        .also { it.partTransformation(ctx.parent, part) }

    private var instance2 = visualizationContext.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, Models.partial(model2))
        .createInstance()
        .also { it.partTransformation(ctx.parent, part) }

    private var temperature1 = Quantity(-1.0, KELVIN)
    private var temperature2 = Quantity(-1.0, KELVIN)

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val desiredTemperature1 = if(flip) part.renderTemperature2 else part.renderTemperature1

        if(temperature1 != desiredTemperature1) {
            temperature1 = desiredTemperature1
            instance1.colorWithOverride(tint1, desiredTemperature1).handle().setChanged()
        }

        val desiredTemperature2 = if(flip) part.renderTemperature1 else part.renderTemperature2
        if(temperature2 != desiredTemperature2) {
            temperature2 = desiredTemperature2
            instance2.colorWithOverride(tint2, desiredTemperature2).handle().setChanged()
        }
    }

    override fun updateLight(p0: Float) {
        visualizationContext.parent.relightInstances(bodyInstance, instance1, instance2)
    }

    override fun _delete() {
        bodyInstance.delete()
        instance1.delete()
        instance2.delete()
    }

    companion object {

    }
}

