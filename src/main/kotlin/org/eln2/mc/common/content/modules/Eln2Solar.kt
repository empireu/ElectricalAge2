@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import org.ageseries.libage.data.METER2
import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.VOLT
import org.ageseries.libage.data.requireLocator
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.content.ContentModule
import org.eln2.mc.common.content.PhotovoltaicGeneratorCell
import org.eln2.mc.common.content.PhotovoltaicModel
import org.eln2.mc.common.content.PhotovoltaicPanelPart
import org.eln2.mc.common.parts.PartRegistry.partImmediateBB
import org.eln2.mc.data.Locators
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.extensions.vector3d

object Eln2Solar : ContentModule() {
    val PHOTOVOLTAIC_GENERATOR_CELL = cellMemoize("photovoltaic_generator") {
        val map = directionPoleMapPlanar()

        val model = PhotovoltaicModel(
            Quantity(32.0, VOLT),
            0.452515661,
            Quantity(1.0, METER2),
            Quantity(0.01, OHM),
            Quantity(0.025),
            Quantity(261.1519, OHM)
        )

        val surface = Quantity(1.0, METER2)

        CellFactory {
            PhotovoltaicGeneratorCell(it, map, model) { cell ->
                cell.locator.requireLocator(Locators.SUBSTRATE_FACE).vector3d
            }
        }
    }

    val PHOTOVOLTAIC_PANEL_PART = partImmediateBB("photovoltaic_panel", 16.0, 2.0, 16.0) {
        PhotovoltaicPanelPart(it, PHOTOVOLTAIC_GENERATOR_CELL.get())
    }
}
