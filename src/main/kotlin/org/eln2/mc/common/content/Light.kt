package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.SectionTrackedVisual
import dev.engine_room.flywheel.api.visual.ShaderLightVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import it.unimi.dsi.fastutil.longs.LongSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.client.extensions.common.IClientBlockExtensions
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.registerHandler
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.electrical.ElectricalSimulation
import org.ageseries.libage.sim.electrical.Resistor
import org.eln2.mc.*
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.FlwInstanceTypes
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.client.render.foundation.TransformedLightOverrideInstance
import org.eln2.mc.client.render.foundation.partTransformation
import org.eln2.mc.common.*
import org.eln2.mc.common.blocks.foundation.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.modules.Eln2Lights
import org.eln2.mc.common.events.EventListener
import org.eln2.mc.common.events.EventQueue
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.common.grids.GridCableItem
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.network.serverToClient.BulkMessageHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.with
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.data.PoleMap
import org.eln2.mc.extensions.enqueueBulkMessage
import org.eln2.mc.extensions.evaluateDiffuseIrradianceFactor
import org.eln2.mc.extensions.plus
import org.eln2.mc.extensions.vector3d
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask
import java.nio.ByteBuffer
import java.util.function.Consumer
import kotlin.math.absoluteValue
import kotlin.math.round

abstract class LightCell(ci: CellCreateInfo, val lightVariantType: LightVariantType) : Cell(ci), LightView, LightBulbEmitterView {
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

    /**
     * Don't worry, it *is* implemented by an electrical object.
     * We just used `by` to redirect the object's resistor to this field.
     * */
    abstract val resistor : Resistor

    @SimObject
    val thermalWire = ThermalWireObject(self())

    @Behavior
    val explosion = ThermalBreakdownBehavior.create(
        Quantity(200.0, CELSIUS),
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
        resistor.resistance = ElectricalSimulation.MAX_RESISTANCE
    }

    override fun resetValues() {
        resistor.updateResistance(ElectricalSimulation.MAX_RESISTANCE)
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

    @OnSimulationThread
    private fun simulationTick(dt: Double, phase: SubscriberPhase) {
        val lightModel = this.lightBulb?.model

        if(lightModel == null || life approxEq 0.0) {
            return
        }

        // Fetch volume if not fetched:
        volume = volume ?: lightModel
            .getVolumeProvider(lightVariantType)
            .getVolume(locator)

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

class PolarLightCell(
    ci: CellCreateInfo,
    override val electricalMap: PoleMap,
    variantType: LightVariantType,
    override val electricalSize: ElectricalSize?
) : LightCell(ci, variantType), SidedElectricalMapped<PolarLightCell> {
    @SimObject
    val resistorObj = PolarResistorObject(self(), electricalMap)

    override val resistor: Resistor
        get() = resistorObj.component
}

class TerminalLightCell(ci: CellCreateInfo, variantType: LightVariantType, plus: Int = PLUS, minus: Int = MINUS) : LightCell(ci, variantType) {
    override val isExclusivelyGridConnected: Boolean
        get() = true
    
    @Node
    val grid = GridNode(self())

    @SimObject
    val resistorObj = TerminalResistorObject(self(), plus, minus)

    override val resistor: Resistor
        get() = resistorObj.component
}

abstract class PoweredLightPart<T : LightCell>(
    ci: PartCreateInfo,
    cellProvider: CellProvider<T>,
) : GridCellPart<LightCell>(ci, cellProvider), EventListener, WrenchRotatable, ComponentDisplay, LightFixtureGameObject {
    @ClientOnly
    override var visualBrightness = 0.0
        protected set

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
        visualBrightness = buffer.getDouble()
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
        builder.quantity(cell.resistor.readouts.current)
        builder.quantity(cell.resistor.readouts.power)
        builder.integrity(cell.life)
    }
}

class PolarPoweredLightPart(
    ci: PartCreateInfo,
    cellProvider: CellProvider<PolarLightCell>
) : PoweredLightPart<PolarLightCell>(ci, cellProvider)

class TerminalPoweredLightPart(
    ci: PartCreateInfo,
    cellProvider: CellProvider<TerminalLightCell>,
    neg: BoundingBox3d,
    pos: BoundingBox3d,
    negAttachment: Vector3d? = null,
    posAttachment: Vector3d? = null,
) : PoweredLightPart<TerminalLightCell>(ci, cellProvider) {
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
    val volumeProvider: LocatorLightVolumeProvider,
)

class SolarLightPart(
    ci: PartCreateInfo,
    val model: SolarLightModel,
    normalSupplier: ((SolarLightPart) -> Vector3d)? = null,
) : Part(ci), TickablePart, ComponentDisplay, LightFixtureGameObject {
    val volume = model.volumeProvider.getVolume(placement.createLocator(Base6Direction3dMask.EMPTY))
    val normal = if(normalSupplier == null) placement.face.vector3d else normalSupplier(this)

    private val lightVolume = serverOnlyHolder {
        LightVolumeInstance(
            placement.level as ServerLevel,
            placement.position
        )
    }

    var energy = 0.5
    private var savedEnergy = 0.0
    private var isOn = true
    private var trackedState = false

    @ClientOnly
    override var visualBrightness: Double = 0.0

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

    override fun onAdded() {
        if(!placement.level.isClientSide) {
            placement.multipart.addTicker(this)
        }
    }

    override fun serverTick() {
        val state: Boolean

        // Is day -> sky darken
        if(placement.level.isDay && placement.level.canSeeSky(placement.position)) {
            energy += model.rechargeRate * placement.level.evaluateDiffuseIrradianceFactor(normal)
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

        lightVolume().checkoutState(volume, stateIncrement)

        if(state != trackedState) {
            trackedState = state
            setSyncDirty()
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
        visualBrightness = if(tag.getBoolean(STATE)) {
            1.0
        }
        else {
            0.0
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
            lightVolume().destroyCells()
        }
    }

    companion object {
        private const val ENERGY = "energy"
        private const val IS_ON = "isOn"
        private const val STATE = "state"
    }
}

/**
 * Implemented by game objects that are rendered with a [LightFixturePartVisual].
 * The [visualBrightness] is polled by the renderer, so safety must be guaranteed.
 * */
interface LightFixtureGameObject {
    /**
     * The intensity of the light, used to blend between the two tint colors.
     * Range is from 0 to 1, but it is clamped by the renderer.
     * */
    @ClientOnly
    val visualBrightness : Double
}

class LightFixturePartVisual<P>(
    ctx: MultipartVisualizationContext,
    part: P,
    cageModel: PartialModel,
    emitterModel: PartialModel,
    val rotation: Double = 0.0,
    val coldTint: MyColor = MyColor(255, 255, 255, 255),
    val warmTint: MyColor = MyColor(255, 255, 196, 127),
) : AbstractPartVisual<P>(ctx, part), SimpleDynamicVisual where P : Part, P : LightFixtureGameObject {
    private val cageInstance = create(cageModel)
    private val emitterInstance = create(emitterModel)
    private var brightness = 0.0

    private fun create(model: PartialModel): TransformedInstance {
        return visualizationContext
            .instancerProvider()
            .instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
            .createInstance()
            .partTransformation(visualizationContext.parent, part, yRotation = rotation)
    }

    override fun updateLight(partialTick: Float) {
        visualizationContext.parent.relightInstances(cageInstance, emitterInstance)
    }

    private fun applyLightTint() {
        val t = brightness.toFloat()

        emitterInstance
            .color(
                MyColor.lerpR(coldTint, warmTint, t),
                MyColor.lerpG(coldTint, warmTint, t),
                MyColor.lerpB(coldTint, warmTint, t),
                MyColor.lerpA(coldTint, warmTint, t)
            )
            .handle()
            .setChanged()
    }

    override fun beginFrame(ctx: DynamicVisual.Context) {
        val desiredBrightness = part.visualBrightness.coerceIn(0.0, 1.0)

        if(desiredBrightness != brightness) {
            brightness = desiredBrightness
            applyLightTint()
        }
    }

    override fun _delete() {
        cageInstance.delete()
        emitterInstance.delete()
    }
}

class LampPoleBlock(private val cellProvider: RegistryObject<CellProvider<PolarLightCell>>, val lightOffset: BlockPos) : UprightHorizontalDirectionCellBlock<PolarLightCell>() {
    override fun initializeClient(consumer: Consumer<IClientBlockExtensions?>) {
        consumer.accept(ReplaceVanillaParticlesBlockExtension)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("true"))
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean {
        return true
    }

    override fun getCellProvider() = cellProvider.get()
    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = LampPoleBlockEntity(pPos, pState)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun use(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult,
    ): InteractionResult {
        val blockEntity = pLevel.getBlockEntity(pPos) as? LampPoleBlockEntity
        return blockEntity?.onUsedBy(pPlayer, pHand) ?: InteractionResult.FAIL
    }
}

class LampPoleBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: LampPoleBlockEntity,
    partialTick: Float,
) : AbstractBlockEntityVisual<LampPoleBlockEntity>(ctx, blockEntity, partialTick), ShaderLightVisual, SimpleDynamicVisual {
    companion object {
        private val coldTint = MyColor(0, 255, 255, 255)
        private val warmTint = MyColor(255, 255, 196, 127)
    }

    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.LAMP_POLE_BODY, FlwMaterials.TRANSLUCENT_SMOOTH_LIT))
        .createInstance()
        .also {
            it.translate(visualPosition)
            it.center()
            it.rotateToFace(blockEntity.representativeFacing.clockWise)
            it.uncenter()
        }

    val emitter: TransformedLightOverrideInstance = visualizationContext.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, PartialModelHelper.applyMaterial(FlwModels.LAMP_POLE_EMITTER, FlwMaterials.SMOOTH_LIT))
        .createInstance()
        .also {
            it.translate(
                visualPosition.x.toDouble(),
                visualPosition.y.toDouble() + 5.0,
                visualPosition.z.toDouble()
            )
            it.center()
            it.rotateToFace(blockEntity.representativeFacing.clockWise)
            it.uncenter()
        }

    private var lastRenderBrightness = 0.0

    override fun setSectionCollector(sectionCollector: SectionTrackedVisual.SectionCollector) {
        this.lightSections = sectionCollector

        val s0 = SectionPos.asLong(pos)
        val s1 = SectionPos.asLong(pos.above(5))

        if(s0 == s1) {
            lightSections.sections(LongSet.of(s0))
        }
        else {
            lightSections.sections(LongSet.of(s0, s1))
        }
    }

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val targetRenderBrightness = blockEntity.visualBrightness

        if(lastRenderBrightness != targetRenderBrightness) {
            lastRenderBrightness = targetRenderBrightness
            val tint = MyColor.lerp(coldTint, warmTint, targetRenderBrightness.toFloat())

            emitter.color(tint.r, tint.g, tint.b)
            emitter.lightOverride = tint.a / 255.0f
            emitter.setChanged()
        }
    }

    override fun collectCrumblingInstances(consumer: Consumer<Instance?>) {
        consumer.accept(body)
        consumer.accept(emitter)
    }

    override fun updateLight(partialTick: Float) {
        // No-op since it looks like the smooth lights handle themselves
        //FlatLit.relight(LevelRenderer.getLightColor(level, pos.above(2)), body)
    }

    override fun _delete() {
        body.delete()
        emitter.delete()
    }
}

class LampPoleBlockEntity(pos: BlockPos, state: BlockState) :
    CellBlockEntity<PolarLightCell>(pos, state, Eln2Lights.LAMP_POLE_BLOCK_ENTITY.get()),
    BigBlockRepresentativeBlockEntity<LampPoleBlockEntity>,
    EventListener,
    ComponentDisplay,
    LightFixtureGameObject,
    BulkMessageHandlerBlockEntity
{
    val instance = serverOnlyHolder {
        LightVolumeInstance(
            level as ServerLevel,
            blockPos + (blockState.block as LampPoleBlock).lightOffset
        )
    }

    override var visualBrightness = 0.0
        private set

    override fun handleBulkMessage(payload: ByteArray) {
        visualBrightness = ByteBuffer.wrap(payload).getDouble()
    }

    override val delegateMap: MultiblockDelegateMap
        get() = Eln2Lights.LAMP_POLE_BLOCK_DELEGATE_MAP.value

    override fun setDestroyed() {
        destroyDelegates()

        val level = this.level
        if (level != null && !level.isClientSide) {
            instance().destroyCells()
        }

        super.setDestroyed()
    }

    fun onUsedBy(player: Player, hand: InteractionHand): InteractionResult {
        val level = this.level ?: return InteractionResult.FAIL

        if (level.isClientSide || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS
        }

        val instance = instance()
        val stack = player.mainHandItem

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

    override fun getUpdateTag(): CompoundTag {
        if(hasCell) {
            sendClientBrightness(cell.modelTemperature)
        }

        return super.getUpdateTag()
    }

    @ServerOnly
    @OnServerThread
    private fun onLightBurnedOut(event: LightBurnedOutEvent) {
        sendClientBrightness(0.0)
        instance().destroyCells()
        level?.playLocalSound(
            blockPos.x.toDouble(),
            blockPos.y.toDouble(),
            blockPos.z.toDouble(),
            SoundEvents.FIRE_EXTINGUISH,
            SoundSource.BLOCKS,
            1.0f,
            randomFloat(0.9f, 1.1f),
            false
        )
    }

    override fun onChunkUnloaded() {
        super.onChunkUnloaded()

        if(hasCell) {
            cell.unbind()
            Scheduler.remove(this)
            instance().destroyCells()
        }
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(cell.thermalWire.thermalBody.temperature)
        builder.quantity(cell.resistor.readouts.current)
        builder.quantity(cell.resistor.readouts.power)
        builder.integrity(cell.life)
    }
}
