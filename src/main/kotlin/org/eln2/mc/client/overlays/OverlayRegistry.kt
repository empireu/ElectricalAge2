package org.eln2.mc.client.overlays

import net.minecraftforge.client.event.RegisterGuiOverlaysEvent
import org.eln2.mc.common.content.processing.BlacksmithingToolItem
import org.eln2.mc.common.specs.foundation.SpecPlacementOverlayClient

object OverlayRegistry {
    fun register(event: RegisterGuiOverlaysEvent) {
        event.registerAboveAll("spec_placement", SpecPlacementOverlayClient)
        event.registerAboveAll("blacksmithing_hammer", BlacksmithingToolItem.Companion)
    }
}
