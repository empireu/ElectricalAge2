package org.eln2.mc.common.sounds

import net.minecraft.sounds.SoundEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.eln2.mc.MODID
import org.eln2.mc.resource

object SoundRegistry {
    val SOUND_EVENTS: DeferredRegister<SoundEvent> = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MODID)

    fun setup(bus: IEventBus) {
        SOUND_EVENTS.register(bus)
    }

    fun soundEventVariableRange(id: String): RegistryObject<SoundEvent> = SOUND_EVENTS.register(id) {
        SoundEvent.createVariableRangeEvent(resource(id))
    }
}
