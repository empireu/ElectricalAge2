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
import org.eln2.mc.requireIsOnRenderThread
import kotlin.math.pow

data class SoundInfo(val pitch: Double, val volume: Double) {
    companion object {
        val QUIET = SoundInfo(1.0, 0.0)

        fun standardWithProcessingSpeed(speed: Double) : SoundInfo {
            if(speed < 0.01) {
                return QUIET
            }

            return SoundInfo(
                0.6 + speed * 0.4,
                speed.pow(3) + 0.5
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
