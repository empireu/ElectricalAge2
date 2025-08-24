package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.*
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.AbstractPartVisual
import org.eln2.mc.client.render.foundation.BasicPartVisual
import org.eln2.mc.client.render.foundation.MultipartVisualizationContext
import org.eln2.mc.client.render.foundation.partTransformation
import org.eln2.mc.common.*
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.extensions.evaluateDiffuseIrradianceFactor
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.ArgbColor

data class SolarLightModel(
    val rechargeRate: Double,
    val dischargeRate: Double,
    val volumeProvider: LocatorLightVolumeProvider
)

class SolarLightPart(
    ci: PartCreateInfo,
    val model: SolarLightModel,
    normalSupplier: (SolarLightPart) -> Vector3d
) : Part(ci), TickablePart, ComponentDisplay, LightFixtureGameObject {
    val volume = model.volumeProvider.getVolume(placement.createLocator())
    val normal = normalSupplier(this)

    private val lightVolume = serverOnlyHolder {
        LightVolumeInstance(
            placement.level as ServerLevel,
            placement.position
        )
    }

    var energy = 0.0
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
 * Implemented by game objects that are rendered with a [LightFixtureRenderer].
 * The [visualBrightness] is polled by the renderer, so safety must be guaranteed.
 * */
interface LightFixtureGameObject {
    /**
     * The intensity of the light, used to blend between the two tint colors.
     * Range is from 0 to 1, but it is clamped by the renderer.
     * */
    val visualBrightness : Double
}

class LightFixtureRenderer<P>(
    ctx: MultipartVisualizationContext,
    part: P,
    cageModel: PartialModel,
    emitterModel: PartialModel,
    val rotation: Double = 0.0,
    val coldTint: ArgbColor = ArgbColor(255, 255, 255, 255),
    val warmTint: ArgbColor = ArgbColor(196, 127, 255, 254),
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
                ArgbColor.lerpR(coldTint, warmTint, t),
                ArgbColor.lerpG(coldTint, warmTint, t),
                ArgbColor.lerpB(coldTint, warmTint, t),
                ArgbColor.lerpA(coldTint, warmTint, t)
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
