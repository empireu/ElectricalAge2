package org.eln2.mc.client.input

import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import org.eln2.mc.common.specs.foundation.SpecPlacementOverlayClient

object KeyMappingRegistry {
    fun register(event: RegisterKeyMappingsEvent) {
        event.register(SpecPlacementOverlayClient.CYCLE_ORIENTATION)
    }
}
