@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT
import org.eln2.mc.common.content.DrillItem
import org.eln2.mc.common.content.DrillModel
import org.eln2.mc.common.content.ScrewdriverItem
import org.eln2.mc.common.content.WrenchItem
import org.eln2.mc.common.items.ItemRegistry.item

object Eln2Tools : ContentModule() {
    val WRENCH = item("wrench") { WrenchItem() }

    val SCREWDRIVER = item("screwdriver") { ScrewdriverItem() }

    val DRILL_MODEL = DrillModel(
        baseSpeed = 12.0f,
        powerDemand = Quantity(200.0, WATT),
        areaPowerDemandMultiplier = 2.0,
    )

    val DRILL = item("drill") {
        DrillItem(DRILL_MODEL)
    }
}
