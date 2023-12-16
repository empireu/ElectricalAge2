package org.eln2.mc.common.content

import com.jozufozu.flywheel.core.PartialModel
import com.jozufozu.flywheel.core.materials.model.ModelData
import kotlinx.serialization.Serializable
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.sim.ThermalMass
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.ThermalTint
import org.eln2.mc.client.render.foundation.createPartInstance
import org.eln2.mc.client.render.foundation.defaultRadiantBodyColor
import org.eln2.mc.common.cells.foundation.InternalTemperatureConsumer
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.network.serverToClient.PacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.integration.ComponentDisplay

class RadiatorPart(
    ci: PartCreateInfo,
    val radiantColor: ThermalTint
) : CellPart<ThermalWireCell, RadiantBodyRenderer>(ci, Content.THERMAL_RADIATOR_CELL.get()), InternalTemperatureConsumer, ComponentDisplay {
    override fun createRenderer() = RadiantBodyRenderer(this, PartialModels.RADIATOR, radiantColor, 0.0)

    override fun registerPackets(builder: PacketHandlerBuilder) {
        builder.withHandler<Sync> {
            renderer.update(Quantity(it.temperature, KELVIN))
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

class RadiantBodyRenderer(
    val part: Part<*>,
    val model: PartialModel,
    val color: ThermalTint,
    val yRotation: Double
) : PartRenderer() {
    private var bodyInstance: ModelData? = null
    private val updates = AtomicUpdate<Quantity<Temperature>>()

    fun update(temperature: Quantity<Temperature>) = updates.setLatest(temperature)

    override fun setupRendering() {
        bodyInstance = createPartInstance(multipart, model, part, yRotation)
    }

    override fun beginFrame() {
        if(bodyInstance != null) {
            updates.consume { temperature ->
                bodyInstance?.setColor(color.evaluate(temperature))
            }
        }
    }

    override fun relight(source: RelightSource) {
        multipart.relightModels(bodyInstance)
    }

    override fun remove() {
        bodyInstance?.delete()
    }
}

class RadiantBipoleRenderer(
    val part: Part<*>,
    val body: PartialModel,
    val left: PartialModel,
    val right: PartialModel,
    val bodyRotation: Double,
    val leftRotation: Double,
    val rightRotation: Double,
    val leftColor: ThermalTint,
    val rightColor: ThermalTint,
) : PartRenderer() {
    constructor(
        part: Part<*>,
        body: PartialModel,
        left: PartialModel,
        right: PartialModel,
        rotation: Double,
        leftColor: ThermalTint,
        rightColor: ThermalTint,
    ) :
        this(
            part,
            body,
            left,
            right,
            rotation,
            rotation,
            rotation,
            leftColor,
            rightColor
        )

    constructor(
        part: Part<*>,
        body: PartialModel,
        left: PartialModel,
        right: PartialModel,
        rotation: Double,
    ) : this(part, body, left, right, rotation, defaultRadiantBodyColor(), defaultRadiantBodyColor())

    private var bodyInstance: ModelData? = null
    private var leftInstance: ModelData? = null
    private var rightInstance: ModelData? = null

    private val leftSideUpdate = AtomicUpdate<Quantity<Temperature>>()
    private val rightSideUpdate = AtomicUpdate<Quantity<Temperature>>()

    fun updateLeftSideTemperature(value: Quantity<Temperature>) = leftSideUpdate.setLatest(value)

    fun updateRightSideTemperature(value: Quantity<Temperature>) = rightSideUpdate.setLatest(value)

    override fun setupRendering() {
        bodyInstance?.delete()
        leftInstance?.delete()
        rightInstance?.delete()
        bodyInstance = createPartInstance(multipart, body, part, bodyRotation)
        leftInstance = createPartInstance(multipart, left, part, leftRotation)
        rightInstance = createPartInstance(multipart, right, part, rightRotation)
    }

    override fun beginFrame() {
        leftSideUpdate.consume { leftInstance?.setColor(leftColor.evaluate(it)) }
        rightSideUpdate.consume { rightInstance?.setColor(rightColor.evaluate(it)) }
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

