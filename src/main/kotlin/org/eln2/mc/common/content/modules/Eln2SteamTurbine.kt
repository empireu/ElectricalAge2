@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.MonopoleMap
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockItemOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockOnly
import org.eln2.mc.common.blocks.BlockRegistry.defineDelegateMap
import org.eln2.mc.common.blocks.foundation.BigBlockItem
import org.eln2.mc.common.cells.CellRegistry.cellImmediate
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.cells.foundation.KineticSize
import org.eln2.mc.common.cells.foundation.ThermalSize
import org.eln2.mc.common.content.*
import org.eln2.mc.common.content.modules.ContentManager.withSelfDrop

object Eln2SteamTurbine : ContentModule() {
    val STEAM_TURBINE_MODEL = SteamTurbineGeneratorModel(
        etaFactor = 0.5,
        maxFlowRate = 100.0,
        maxTorque = Quantity(100.0, NEWTON_METER),
        maxPower = Quantity(50000.0, WATT),
        coldSideMass = Quantity(50.0, KILOGRAM),
        coldSideMaterial = ChemicalElement.Copper.asMaterial,
        coldSideLeakage = ConnectionParameters.DEFAULT,
        referenceAngularVelocity = Quantity(104.72, RADIAN_PER_SECOND),
        breakdownAngularVelocity = Quantity(500.0, RADIAN_PER_SECOND),
        breakdownTemperature = Quantity(200.0, CELSIUS),
        overcapacityThreshold = 1.5,
    )

    val STEAM_TURBINE_CELL = cellImmediate("steam_turbine", ::SteamTurbineCell)

    val STEAM_TURBINE_BLOCK = blockOnly("steam_turbine", ::SteamTurbineBlock).withSelfDrop()

    val STEAM_TURBINE_BLOCK_ENTITY = blockEntityOnly(
        "steam_turbine",
        STEAM_TURBINE_BLOCK,
        ::SteamTurbineBlockEntity,
    )

    val STEAM_TURBINE_KINETIC_DELEGATE_CELL = cellMemoize("steam_turbine_kinetic_port") {
        val map = MonopoleMap { _, _ -> true }
        CellFactory { SteamTurbineKineticPortCell(it, map, KineticSize.Standard) }
    }

    val STEAM_TURBINE_KINETIC_DELEGATE_BLOCK_LEFT =
        blockOnly("steam_turbine_kinetic_port_left") { SteamTurbineKineticDelegateBlock("Left Shaft") }

    val STEAM_TURBINE_KINETIC_DELEGATE_BLOCK_RIGHT =
        blockOnly("steam_turbine_kinetic_port_right") { SteamTurbineKineticDelegateBlock("Right Shaft") }

    val STEAM_TURBINE_KINETIC_DELEGATE_BLOCK_ENTITY = blockEntityOnly(
        "steam_turbine_kinetic_port",
        ::SteamTurbineKineticDelegateBlockEntity,
        { STEAM_TURBINE_KINETIC_DELEGATE_BLOCK_LEFT.get() },
        { STEAM_TURBINE_KINETIC_DELEGATE_BLOCK_RIGHT.get() },
    )

    val STEAM_TURBINE_THERMAL_DELEGATE_CELL = cellMemoize("steam_turbine_thermal_port") {
        val thermalDef = ThermalMassDefinition(
            ChemicalElement.Copper.asMaterial,
            mass = Quantity(5.0, KILOGRAM)
        )
        val map = MonopoleMap { _, _ -> true }
        CellFactory { SteamTurbineThermalPortCell(it, thermalDef, map, ThermalSize.Standard) }
    }

    val STEAM_TURBINE_THERMAL_DELEGATE_BLOCK_A =
        blockOnly("steam_turbine_thermal_port_a") { SteamTurbineThermalDelegateBlock("Cooling A") }

    val STEAM_TURBINE_THERMAL_DELEGATE_BLOCK_B =
        blockOnly("steam_turbine_thermal_port_b") { SteamTurbineThermalDelegateBlock("Cooling B") }

    val STEAM_TURBINE_THERMAL_DELEGATE_BLOCK_ENTITY = blockEntityOnly(
        "steam_turbine_thermal_port",
        ::SteamTurbineThermalDelegateBlockEntity,
        { STEAM_TURBINE_THERMAL_DELEGATE_BLOCK_A.get() },
        { STEAM_TURBINE_THERMAL_DELEGATE_BLOCK_B.get() },
    )

    val STEAM_TURBINE_FLUID_DELEGATE_BLOCK_INPUT =
        blockOnly("steam_turbine_fluid_port_input") { SteamTurbineFluidDelegateBlock("Steam Input") }

    val STEAM_TURBINE_FLUID_DELEGATE_BLOCK_OUTPUT =
        blockOnly("steam_turbine_fluid_port_output") { SteamTurbineFluidDelegateBlock("Water/Steam Output") }

    val STEAM_TURBINE_FLUID_DELEGATE_BLOCK_ENTITY = blockEntityOnly(
        "steam_turbine_fluid_port",
        ::SteamTurbineFluidDelegateBlockEntity,
        { STEAM_TURBINE_FLUID_DELEGATE_BLOCK_INPUT.get() },
        { STEAM_TURBINE_FLUID_DELEGATE_BLOCK_OUTPUT.get() },
    )

    val STEAM_TURBINE_DELEGATE_MAP = defineDelegateMap("steam_turbine") {
        principal(-1, 1, -1, STEAM_TURBINE_KINETIC_DELEGATE_BLOCK_LEFT)
        principal(+2, 1, -1, STEAM_TURBINE_KINETIC_DELEGATE_BLOCK_RIGHT)

        principal(0, 0, -2, STEAM_TURBINE_THERMAL_DELEGATE_BLOCK_A)
        principal(+1, 0, -2, STEAM_TURBINE_THERMAL_DELEGATE_BLOCK_B)

        principal(-1, 0, -1, STEAM_TURBINE_FLUID_DELEGATE_BLOCK_INPUT)
        principal(+2, 0, -1, STEAM_TURBINE_FLUID_DELEGATE_BLOCK_OUTPUT)

        for (x in -1..2) {
            for (y in 0..2) {
                for (z in -2..0) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue
                    }

                    val isPort = when (Triple(x, y, z)) {
                        Triple(-1, 1, -1) -> true
                        Triple(2, 1, -1) -> true
                        Triple(0, 0, -2) -> true
                        Triple(1, 0, -2) -> true
                        Triple(-1, 0, -1) -> true
                        Triple(2, 0, -1) -> true
                        else -> false
                    }

                    if (!isPort) {
                        delegateBlock(x, y, z)
                    }
                }
            }
        }
    }

    val STEAM_TURBINE_BLOCK_ITEM = blockItemOnly("steam_turbine") {
        BigBlockItem(
            STEAM_TURBINE_DELEGATE_MAP.value,
            STEAM_TURBINE_BLOCK.get()
        )
    }
}
