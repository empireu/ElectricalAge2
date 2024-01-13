package org.eln2.mc.common.content

import com.jozufozu.flywheel.core.Materials
import com.jozufozu.flywheel.core.PartialModel
import com.jozufozu.flywheel.core.materials.model.ModelData
import com.jozufozu.flywheel.util.Color
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import org.ageseries.libage.data.registerHandler
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.electrical.mna.LARGE_RESISTANCE
import org.ageseries.libage.sim.electrical.mna.NEGATIVE
import org.ageseries.libage.sim.electrical.mna.POSITIVE
import org.ageseries.libage.sim.electrical.mna.component.IResistor
import org.ageseries.libage.sim.electrical.mna.component.updateResistance
import org.eln2.mc.*
import org.eln2.mc.client.render.DefaultRenderType
import org.eln2.mc.client.render.RenderTypedPartialModel
import org.eln2.mc.client.render.foundation.colorLerp
import org.eln2.mc.client.render.foundation.transformPart
import org.eln2.mc.common.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.events.EventListener
import org.eln2.mc.common.events.EventQueue
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.common.grids.GridCableItem
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.network.serverToClient.with
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.data.PoleMap
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import java.nio.ByteBuffer
import kotlin.math.absoluteValue
import kotlin.math.round

abstract class LightCell(ci: CellCreateInfo) : Cell(ci), LightView, LightBulbEmitterView {
    companion object {
        private const val RENDER_EPS = 1e-4
        private const val RESISTANCE_EPS = 0.1
    }

    // The last render brightness sent:
    private var trackedRenderBrightness: Double = 0.0

    // Accessor to send the render brightness:
    private var renderBrightnessConsumer: LightTemperatureConsumer? = null

    // An event queue hooked into the game object:
    private var serverThreadReceiver: EventQueue? = null

    abstract val resistor : IResistor

    @SimObject
    val thermalWire = ThermalWireObject(self())

    @Behavior
    val explosion = TemperatureExplosionBehavior.create(
        TemperatureExplosionBehaviorOptions(),
        self(),
        thermalWire.thermalBody::temperature
    )

    final override var volumeState: Int = 0

    final override var modelTemperature = 0.0
        private set

    final override val power: Double get() = resistor.power
    final override val current: Double get() = resistor.current
    final override val potential: Double get() = resistor.potential

    final override var life: Double = 0.0

    final override var lightBulb: LightBulbItem? = null
    var volume: LightVolume? = null

    override fun afterConstruct() {
        super.afterConstruct()
        resistor.resistance = LARGE_RESISTANCE
    }

    override fun resetValues() {
        resistor.updateResistance(LARGE_RESISTANCE)
        modelTemperature = 0.0
        trackedRenderBrightness = 0.0
        volumeState = 0
        life = 0.0
        lightBulb = null
        volume = null
    }

    fun bind(serverThreadAccess: EventQueue, renderBrightnessConsumer: LightTemperatureConsumer, pLoadExisting: Boolean) {
        this.serverThreadReceiver = serverThreadAccess
        this.renderBrightnessConsumer = renderBrightnessConsumer

        if(pLoadExisting) {
            // If we've been running, send the current state:
            val life = this.life
            val volume = this.volume

            if(volume != null && life > 0.0) {
                serverThreadAccess.place(VolumetricLightChangeEvent(volume, volumeState))
                renderBrightnessConsumer.consume(modelTemperature)
            }
        }
    }

    fun unbind() {
        serverThreadReceiver = null
        renderBrightnessConsumer = null
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::simulationTick) // maybe reduce interval
    }

    private fun simulationTick(dt: Double, phase: SubscriberPhase) {
        val lightModel = this.lightBulb?.model

        if(lightModel == null || life approxEq 0.0) {
            return
        }

        // Fetch volume if not fetched:
        volume = volume ?: lightModel.volumeProvider.getVolume(locator)
        val volume = volume!!

        val gameEventReceiver = this.serverThreadReceiver

        // Tick down consumption:
        val damage = lightModel.damageFunction.computeDamage(this, dt).absoluteValue

        if(damage > 0.0) {
            life = (life - damage).coerceIn(0.0, 1.0)
            setChanged()
        }

        if(life approxEq 0.0) {
            life = 0.0
            // Light has burned out:
            gameEventReceiver?.enqueue(LightBurnedOutEvent)
            resetValues()
            setChanged()
            return
        }

        // Evaluate temperature:
        modelTemperature = lightModel.temperatureFunction.computeTemperature(this).coerceIn(0.0, 1.0)

        // Update power consumption:
        resistor.updateResistance(!lightModel.resistanceFunction.computeResistance(this), RESISTANCE_EPS)

        // Send new value to client:
        if (!modelTemperature.approxEq(trackedRenderBrightness, RENDER_EPS)) {
            trackedRenderBrightness = modelTemperature
            renderBrightnessConsumer?.consume(modelTemperature)
        }

        // Find target state based on temperature:s
        val targetState = round(modelTemperature * volume.stateIncrements).toInt().coerceIn(0, volume.stateIncrements)

        // Detect changes:
        if (volumeState != targetState) {
            volumeState = targetState
            // Using this new "place" API, the game object will receive one event (with the latest values),
            // even if we do multiple updates in our simulation thread:
            gameEventReceiver?.place(VolumetricLightChangeEvent(volume, targetState))
        }
    }

    override fun saveCellData() = lightBulb?.toNbtWithState(life)

    override fun loadCellData(tag: CompoundTag) {
        LightBulbItem.fromNbtWithState(tag)?.also { (bulb, life) ->
            this.lightBulb = bulb
            this.life = life
        }
    }
}

class PolarLightCell(ci: CellCreateInfo, map: PoleMap) : LightCell(ci) {
    @SimObject
    override val resistor = PolarResistorObjectVirtual(self(), map)

    override fun cellConnectionPredicate(remote: Cell): Boolean {
        return super.cellConnectionPredicate(remote) && resistor.poleMap.evaluateOrNull(this, remote) != null
    }
}

class TerminalLightCell(ci: CellCreateInfo, plus: Int = POSITIVE, minus: Int = NEGATIVE) : LightCell(ci) {
    @Node
    val grid = GridNode(self())

    @SimObject
    override val resistor = TerminalResistorObjectVirtual(self(), plus, minus)

    override fun allowsConnection(remote: Cell): Boolean {
        return super.allowsConnection(remote)
    }

    override fun cellConnectionPredicate(remote: Cell): Boolean {
        return super.cellConnectionPredicate(remote) && remote is GridConnectionCell
    }
}

abstract class PoweredLightPart<T : LightCell>(ci: PartCreateInfo, cellProvider: CellProvider<T>, private val rendererSupplier: (PoweredLightPart<T>) -> LightFixtureRenderer) : GridCellPart<LightCell, LightFixtureRenderer>(ci, cellProvider), EventListener, WrenchRotatablePart, ComponentDisplay {
    val instance = serverOnlyHolder {
        LightVolumeInstance(
            placement.level as ServerLevel,
            placement.position
        )
    }

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if (placement.level.isClientSide || context.hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS
        }

        val instance = instance()
        val stack = context.player.mainHandItem

        var result = LightLoadResult.Fail

        cell.graph.runSuspended {
            result = LightVolumeInstance.loadLightFromBulb(instance, cell, stack)
        }

        return when (result) {
            LightLoadResult.RemoveExisting -> {
                sendClientBrightness(0.0)
                InteractionResult.SUCCESS
            }

            LightLoadResult.AddNew -> {
                InteractionResult.CONSUME
            }

            LightLoadResult.Fail -> {
                InteractionResult.FAIL
            }
        }
    }

    override fun createRenderer() = rendererSupplier.invoke(this)

    @ServerOnly
    @OnServerThread
    override fun onCellAcquired() {
        super.onCellAcquired()
        val events = Scheduler.register(this)

        events.registerHandler(this::onVolumeUpdated)
        events.registerHandler(this::onLightBurnedOut)

        cell.bind(
            serverThreadAccess = Scheduler.getEventAccess(this),
            renderBrightnessConsumer = ::sendClientBrightness,
            true
        )
    }

    private fun onVolumeUpdated(event: VolumetricLightChangeEvent) {
        // Item is only mutated on onUsedBy (server thread), when the bulb is added/removed, so it is safe to access here
        // if it is null, it means we got this update possibly after the bulb was removed by a player, so we will ignore it
        if (!hasCell || cell.lightBulb == null) {
            return
        }

        instance().checkoutState(event.volume, event.targetState)
    }

    @ServerOnly
    private fun sendClientBrightness(value: Double) {
        val buffer = ByteBuffer.allocate(8) with value
        enqueueBulkMessage(buffer.array())
    }

    @ClientOnly
    override fun handleBulkMessage(msg: ByteArray) {
        val buffer = ByteBuffer.wrap(msg)
        renderer.updateBrightness(buffer.double)
    }

    @ServerOnly
    @OnServerThread
    private fun onLightBurnedOut(event: LightBurnedOutEvent) {
        sendClientBrightness(0.0)
        instance().destroyCells()
        placement.level.playLocalSound(
            placement.position.x.toDouble(),
            placement.position.y.toDouble(),
            placement.position.z.toDouble(),
            SoundEvents.FIRE_EXTINGUISH,
            SoundSource.BLOCKS,
            1.0f,
            randomFloat(0.9f, 1.1f),
            false
        )
    }

    @ServerOnly
    @OnServerThread
    override fun onSyncSuggested() {
        super.onSyncSuggested()
        sendClientBrightness(cell.modelTemperature)
    }

    override fun onCellReleased() {
        super.onCellReleased()
        cell.unbind()
        Scheduler.remove(this)
        instance().destroyCells()
    }

    override fun onRemoved() {
        super.onRemoved()

        if (!placement.level.isClientSide) {
            instance().destroyCells()
        }
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(cell.thermalWire.thermalBody.temperature)
        builder.current(cell.current)
        builder.power(cell.power)
        builder.integrity(cell.life)
    }
}

class PolarPoweredLightPart(
    ci: PartCreateInfo,
    cellProvider: CellProvider<PolarLightCell>,
    rendererSupplier: (PoweredLightPart<PolarLightCell>) -> LightFixtureRenderer
) : PoweredLightPart<PolarLightCell>(ci, cellProvider, rendererSupplier)

class TerminalPoweredLightPart(
    ci: PartCreateInfo,
    cellProvider: CellProvider<TerminalLightCell>,
    neg: BoundingBox3d,
    pos: BoundingBox3d,
    negAttachment: Vector3d? = null,
    posAttachment: Vector3d? = null,
    rendererSupplier: (PoweredLightPart<TerminalLightCell>) -> LightFixtureRenderer
) : PoweredLightPart<TerminalLightCell>(ci, cellProvider, rendererSupplier) {
    val negative = defineCellBoxTerminal(
        neg.center.x, neg.center.y, neg.center.z,
        neg.size.x, neg.size.y, neg.size.z,
        attachment = negAttachment
    )

    val positive = defineCellBoxTerminal(
        pos.center.x, pos.center.y, pos.center.z,
        pos.size.x, pos.size.y, pos.size.z,
        attachment = posAttachment
    )

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if(context.player.getItemInHand(context.hand).item is GridCableItem) {
            return InteractionResult.FAIL
        }

        return super.onUsedBy(context)
    }
}

data class SolarLightModel(
    val rechargeRate: Double,
    val dischargeRate: Double,
    val volumeProvider: LocatorLightVolumeProvider
)

class SolarLightPart<R : PartRenderer>(
    ci: PartCreateInfo,
    val model: SolarLightModel,
    normalSupplier: (SolarLightPart<R>) -> Vector3d,
    val rendererSupplier: (SolarLightPart<R>) -> R,
    rendererClass: Class<R>,
) : Part<R>(ci), TickablePart, ComponentDisplay {
    val volume = model.volumeProvider.getVolume(placement.createLocator())
    val normal = normalSupplier(this)

    private val instance = serverOnlyHolder {
        LightVolumeInstance(
            placement.level as ServerLevel,
            placement.position
        )
    }

    var energy = 0.0
    private var savedEnergy = 0.0
    private var isOn = true
    private var trackedState = false
    private val usesSync = rendererClass == LightFixtureRenderer::class.java

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if(placement.level.isClientSide) {
            return InteractionResult.PASS
        }

        if(context.hand == InteractionHand.MAIN_HAND) {
            isOn = !isOn
            setSaveDirty()
            return InteractionResult.SUCCESS
        }

        return InteractionResult.FAIL
    }

    override fun createRenderer() = rendererSupplier(this)

    override fun onAdded() {
        if(!placement.level.isClientSide) {
            placement.multipart.addTicker(this)
        }
    }

    override fun tick() {
        energy += model.rechargeRate * placement.level.evaluateDiffuseIrradianceFactor(normal)

        val state: Boolean

        // Is day -> sky darken
        if(placement.level.isDay && placement.level.canSeeSky(placement.position)) {
            state = false
        }
        else {
            if(isOn) {
                state = energy > model.dischargeRate

                if(state) {
                    energy -= model.dischargeRate
                }
                else {
                    isOn = false
                    setSaveDirty()
                }
            }
            else {
                state = false
            }
        }

        val stateIncrement = if(state) {
            volume.stateIncrements
        }
        else {
            0
        }

        instance().checkoutState(volume, stateIncrement)

        if(state != trackedState) {
            trackedState = state

            if(usesSync) {
                setSyncDirty()
            }
        }

        energy = energy.coerceIn(0.0, 1.0)

        if(!savedEnergy.approxEq(energy)) {
            savedEnergy = energy
            setSaveDirty()
        }
    }

    override fun getServerSaveTag() = CompoundTag().also {
        it.putDouble(ENERGY, energy)
        it.putBoolean(IS_ON, isOn)
    }

    override fun loadServerSaveTag(tag: CompoundTag) {
        energy = tag.getDouble(ENERGY)
        isOn = tag.getBoolean(IS_ON)
    }

    override fun getSyncTag() = CompoundTag().also {
        it.putBoolean(STATE, trackedState)
    }

    override fun handleSyncTag(tag: CompoundTag) {
        if(usesSync) {
            (renderer as LightFixtureRenderer).updateBrightness(
                if(tag.getBoolean(STATE)) {
                    1.0
                }
                else {
                    0.0
                }
            )
        }
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.charge(energy)
        builder.translatePercent("Irradiance", placement.level.evaluateDiffuseIrradianceFactor(normal))
    }

    override fun onRemoved() {
        super.onRemoved()
        destroyLights()
    }

    override fun onUnloaded() {
        super.onUnloaded()
        destroyLights()
    }

    private fun destroyLights() {
        if(!placement.level.isClientSide) {
            instance().destroyCells()
        }
    }

    companion object {
        private const val ENERGY = "energy"
        private const val IS_ON = "isOn"
        private const val STATE = "state"
    }
}

class LightFixtureRenderer(
    val part: Part<LightFixtureRenderer>,
    val cageModel: RenderTypedPartialModel,
    val emitterModel: RenderTypedPartialModel,
    val coldTint: Color = Color(255, 255, 255, 255),
    val warmTint: Color = Color(254, 196, 127, 255),
) : PartRenderer() {
    private val brightnessUpdate = AtomicUpdate<Double>()
    private var brightness = 0.0

    fun updateBrightness(newValue: Double) = brightnessUpdate.setLatest(newValue)

    var yRotation = 0.0

    private var cageInstance: ModelData? = null
    private var emitterInstance: ModelData? = null

    override fun setupRendering() {
        cageInstance?.delete()
        emitterInstance?.delete()
        cageInstance = create(cageModel.model, cageModel.type)
        emitterInstance = create(emitterModel.model, emitterModel.type)
        applyLightTint()
    }

    private fun create(model: PartialModel, type: DefaultRenderType): ModelData {
        return multipart.materialManager
            .let {
                when(type) {
                    DefaultRenderType.Solid -> it.defaultSolid()
                    DefaultRenderType.Cutout -> it.defaultCutout()
                    DefaultRenderType.Transparent -> it.defaultTransparent()
                }
            }
            .material(Materials.TRANSFORMED)
            .getModel(model)
            .createInstance()
            .loadIdentity()
            .transformPart(multipart, part, yRotation = yRotation)
    }

    private fun applyLightTint() {
        emitterInstance?.setColor(colorLerp(coldTint, warmTint, brightness.toFloat()))
    }

    override fun relight(source: RelightSource) {
        multipart.relightModels(emitterInstance, cageInstance)
    }

    override fun beginFrame() {
        brightnessUpdate.consume {
            brightness = it.coerceIn(0.0, 1.0)
            applyLightTint()
        }
    }

    override fun remove() {
        cageInstance?.delete()
        emitterInstance?.delete()
    }
}
