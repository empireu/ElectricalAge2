@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT
import org.ageseries.libage.data.WATT_HOUR
import org.eln2.mc.common.content.InventoryPowerCellModel
import org.eln2.mc.common.content.PowerCellItem
import org.eln2.mc.common.items.ItemRegistry.item
import org.eln2.mc.common.items.CreativeTabRegistry

object Eln2InventoryPower : ContentModule() {
    val LEAD_ACID_POWER_CELL_MODEL = InventoryPowerCellModel(
        energyCapacity = Quantity(200.0, WATT_HOUR),
        maxPowerInput = Quantity(100.0, WATT),
        maxPowerOutput = Quantity(200.0, WATT),
        efficiency = 0.85
    )

    val LEAD_ACID_POWER_CELL = item("lead_acid_power_cell") {
        PowerCellItem(LEAD_ACID_POWER_CELL_MODEL)
    }

    init {
        CreativeTabRegistry.creativeTabVariant {
            LEAD_ACID_POWER_CELL.get().createStack(count = 1)
        }
    }
}
