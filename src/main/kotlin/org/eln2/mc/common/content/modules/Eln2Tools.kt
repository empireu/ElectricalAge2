@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import org.eln2.mc.common.content.ContentModule
import org.eln2.mc.common.content.ScrewdriverItem
import org.eln2.mc.common.content.WrenchItem
import org.eln2.mc.common.items.ItemRegistry.item

object Eln2Tools : ContentModule() {
    val WRENCH = item("wrench") { WrenchItem() }

    val SCREWDRIVER = item("screwdriver") { ScrewdriverItem() }
}
