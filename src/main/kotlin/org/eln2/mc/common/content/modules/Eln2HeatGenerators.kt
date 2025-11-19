@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import net.minecraft.client.gui.screens.MenuScreens
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT_PER_KELVIN
import org.ageseries.libage.data.WATT_PER_METER_KELVIN
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItem
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.containers.ContainerRegistry.menu
import org.eln2.mc.common.content.HeatGeneratorBlock
import org.eln2.mc.common.content.HeatGeneratorBlockEntity
import org.eln2.mc.common.content.HeatGeneratorCell
import org.eln2.mc.common.content.HeatGeneratorMenu
import org.eln2.mc.common.content.HeatGeneratorScreen

object Eln2HeatGenerators : ContentModule() {
    override fun setupScreens() {
        MenuScreens.register(HEAT_GENERATOR_MENU.get(), ::HeatGeneratorScreen)
    }

    val HEAT_GENERATOR_CELL = cellMemoize("heat_generator") {
        val thermalDefinition = ThermalMassDefinition(
            Material(
                label = "Heat generator",
                electricalResistivity = Quantity(Double.POSITIVE_INFINITY),
                thermalConductivity = Quantity(5000.0, WATT_PER_METER_KELVIN),
                specificHeat = ChemicalElement.Copper.specificHeat,
                density = ChemicalElement.Copper.density
            ),
            mass = Quantity(10.0, KILOGRAM)
        )

        val leakageParameters = ConnectionParameters.DEFAULT.copy(
            conductance = Quantity(0.001, WATT_PER_KELVIN)
        )

        CellFactory {
            HeatGeneratorCell(it, thermalDefinition, leakageParameters)
        }
    }

    val HEAT_GENERATOR_BLOCK = blockAndItem("heat_generator") { HeatGeneratorBlock() }

    val HEAT_GENERATOR_BLOCK_ENTITY = blockEntityOnly("heat_generator", HEAT_GENERATOR_BLOCK, ::HeatGeneratorBlockEntity)

    val HEAT_GENERATOR_MENU = menu("heat_generator", ::HeatGeneratorMenu)
}
