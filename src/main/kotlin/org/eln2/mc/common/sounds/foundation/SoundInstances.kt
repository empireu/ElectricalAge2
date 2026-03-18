package org.eln2.mc.common.sounds.foundation

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.entity.BlockEntity
import org.ageseries.libage.data.Event
import org.ageseries.libage.data.EventBus
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.requireIsOnRenderThread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

data class SoundInfo(val pitch: Double, val volume: Double) {
    companion object {
        val QUIET = SoundInfo(1.0, 0.0)

        fun standardWithProcessingSpeed(speed: Double) : SoundInfo {
            if(speed < 0.01) {
                return QUIET
            }

            return SoundInfo(
                pitch = 0.6 + speed * 0.4,
                volume = speed.pow(3) + 0.5
            )
        }

        fun standardWithKineticScraping(omega: Double, refOmega: Double) : SoundInfo {
            val speed = abs(omega) / abs(refOmega)

            if(speed < 1e-8) {
                return QUIET
            }

            val base = 0.1

            return SoundInfo(
                pitch = (speed.pow(2) + 0.6).coerceIn(0.0, 2.0),
                volume = base * speed * (1.0 + sin(speed.coerceIn(0.0, 1.0) * (PI / 2.0))).coerceIn(0.0, 3.0)
            )
        }

        fun electromagnetic(
            power: Double,
            nominalPower: Double,
            basePitch: Double = 0.9,
            pitchRange: Double = 1.6,
            powExponent: Double = 0.6
        ): SoundInfo {
            if (power <= 1e-9 || nominalPower <= 0.0) {
                return QUIET
            }

            val p = abs(power / nominalPower)

            val saturation = p / (1.0 + p)
            val pitch = (basePitch + pitchRange * saturation.pow(powExponent)).coerceIn(0.4, 4.0)

            val quietDb = -48.0
            val loudDb  = -6.0
            val db = quietDb * (1.0 - saturation) + loudDb * saturation
            val amplitude = (10.0).pow(db / 20.0)

            val masterScale = 1.0
            val volume = (amplitude * masterScale).coerceIn(0.0, 3.0)

            return SoundInfo(
                pitch = pitch,
                volume = volume
            )
        }
    }
}

/**
 * Sent when the sound instance is about to tick.
 * */
object SoundInstanceTickEvent : Event

abstract class SimpleLoopingMachineSoundInstance<T>(soundEvent: SoundEvent) :
    AbstractTickableSoundInstance(
        soundEvent,
        SoundSource.BLOCKS,
        SoundInstance.createUnseededRandom()
) {
    init {
        super.looping = true
        super.delay = 0
        super.volume = 0.0f
    }

    // We set volume to 0 initially:
    override fun canStartSilent(): Boolean {
        return true
    }

    var initialized = false
        private set

    fun registerOnAudioManager() {
        requireIsOnRenderThread {
            DEBUGGER_BREAK("SimpleLoopingMachineSoundInstance#register")
        }

        Minecraft.getInstance().soundManager.queueTickingSound(this)
    }

    var soundInfo = SoundInfo(1.0, 0.0)
    val events = EventBus(setOf(SoundInstanceTickEvent::class))

    /**
     * Called in the first tick to set the position of the sound.
     * */
    abstract fun getPosition() : Vector3d

    /**
     * Called per-tick to see if the sound instance should be removed.
     * */
    open fun shouldRemove() : Boolean {
        return false
    }

    override fun tick() {
        if(!initialized) {
            val position = getPosition()
            super.x = position.x
            super.y = position.y
            super.z = position.z
            initialized = true
        }

        events.send(SoundInstanceTickEvent)

        if(shouldRemove()) {
            this.stop()
            return
        }

        val options = soundInfo
        pitch = options.pitch.toFloat()
        volume = options.volume.toFloat()
    }

    fun remove() {
        this.stop()
    }
}

class SimpleLoopingBlockEntitySoundInstance<T : BlockEntity>(val machine: T, soundEvent: SoundEvent) : SimpleLoopingMachineSoundInstance<T>(soundEvent) {
    override fun getPosition(): Vector3d {
        val pos = machine.blockPos ?: error(DEBUGGER_BREAK("BlockEntity#blockPos null for SimpleLoopingBlockEntitySoundInstance"))
        return Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
    }

    override fun shouldRemove(): Boolean {
        return machine.isRemoved
    }
}

class SimpleLoopingPartSoundInstance<T : Part>(val machine: T, soundEvent: SoundEvent) : SimpleLoopingMachineSoundInstance<T>(soundEvent) {
    override fun getPosition(): Vector3d {
        return machine.placement.mountingPointWorld
    }

    override fun shouldRemove(): Boolean {
        return machine.isRemoved
    }
}
