package org.eln2.mc.common.content

import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.*
import org.eln2.mc.client.render.foundation.transformPart
import org.eln2.mc.common.*
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.ArgbColor

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
        //energy += model.rechargeRate * placement.level.evaluateDiffuseIrradianceFactor(normal)
        // FIXME

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
        //builder.translatePercent("Irradiance", placement.level.evaluateDiffuseIrradianceFactor(normal))
        //FIXME
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
    val cageModel: PartialModel,
    val emitterModel: PartialModel,
    val coldTint: ArgbColor = ArgbColor(255, 255, 255, 255),
    val warmTint: ArgbColor = ArgbColor(196, 127, 255, 254),
) : PartRenderer() {
    private val brightnessUpdate = AtomicUpdate<Double>()
    private var brightness = 0.0

    fun updateBrightness(newValue: Double) = brightnessUpdate.setLatest(newValue)

    var yRotation = 0.0

    private var cageInstance: TransformedInstance? = null
    private var emitterInstance: TransformedInstance? = null

    override fun setupRendering() {
        cageInstance?.delete()
        emitterInstance?.delete()
        cageInstance = create(cageModel)
        emitterInstance = create(emitterModel)
        applyLightTint()
    }

    private fun create(model: PartialModel): TransformedInstance {
        return multipart.context
            .instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.partial(model))
            .createInstance()

            .transformPart(multipart, part, yRotation = yRotation)
    }

    private fun applyLightTint() {
        //emitterInstance?.setColor(colorLerp(coldTint, warmTint, brightness.toFloat()))
        //FIXME
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
