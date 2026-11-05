package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.Quantity
import org.eln2.mc.MODID
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.content.processing.PhaseChangeModuleBlockEntity
import org.eln2.mc.common.fluids.foundation.FluidTransformationManager
import org.eln2.mc.common.fluids.foundation.FractionalFluidStack
import org.eln2.mc.common.fluids.foundation.PhysicalFluidManager
import kotlin.math.abs

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object PhysicalFluidGameTests {
    private const val STEAM_CAPACITY = 200.0
    private const val WATER_CAPACITY = 418.6
    private const val LATENT_HEAT = 226000.0
    private const val BOILING_POINT = 373.15
    private const val EXPANSION_FACTOR = 100.0
    private const val MASS_TOLERANCE = 0.05

    private val STEAM get() = Eln2ForgeFluids.STEAM.get()

    private fun transformations() = FluidTransformationManager.transformationsByFluid.values

    private fun Fluid.id() = ForgeRegistries.FLUIDS.getKey(this).toString()

    private fun density(fluid: Fluid) = !PhysicalFluidManager.requireProperties(fluid).density

    private fun GameTestHelper.placeTank(): PhaseChangeModuleBlockEntity {
        val position = arenaPosition(6, 6)

        setBlock(position.below(), Blocks.STONE)
        setBlock(position, Eln2ForgeFluids.IRON_TANK.blockAndItem.block.get())

        val blockEntity = getBlockEntity(position) as? PhaseChangeModuleBlockEntity
            ?: gameTestFail("No tank block entity at $position")

        blockEntity.setPlacedBy(level, Eln2ForgeFluids.IRON_TANK.cell.get())

        return blockEntity
    }

    private fun PhaseChangeModuleBlockEntity.fill(
        liquid: Pair<Fluid, Double>?,
        gas: Pair<Fluid, Double>?,
        temperature: Double
    ) {
        cell.graph.runSuspended {
            if (liquid != null) {
                liquidTank.fillFractional(
                    FractionalFluidStack(liquid.first, liquid.second),
                    IFluidHandler.FluidAction.EXECUTE
                )
            }

            if (gas != null) {
                gasTank.fillFractional(FractionalFluidStack(gas.first, gas.second), IFluidHandler.FluidAction.EXECUTE)
            }

            recalculateBody()
            cell.wire.thermalBody.temperature = Quantity(temperature, KELVIN)
        }
    }

    private fun PhaseChangeModuleBlockEntity.liquidAmount(fluid: Fluid) =
        liquidTank.fluids.firstOrNull { it.fluid == fluid }?.amount ?: 0.0

    private fun PhaseChangeModuleBlockEntity.gasAmount(fluid: Fluid) =
        gasTank.fluids.firstOrNull { it.fluid == fluid }?.amount ?: 0.0

    private fun PhaseChangeModuleBlockEntity.liquidOf(fluid: Fluid) = cell.graph.runSuspended {
        liquidAmount(fluid)
    }

    private fun PhaseChangeModuleBlockEntity.gasOf(fluid: Fluid) = cell.graph.runSuspended {
        gasAmount(fluid)
    }

    private fun PhaseChangeModuleBlockEntity.temperature() = cell.graph.runSuspended {
        !cell.wire.thermalBody.temperature
    }

    private fun PhaseChangeModuleBlockEntity.waterEnergy() = cell.graph.runSuspended {
        val steam = gasTank.fluids.firstOrNull { it.fluid == STEAM }?.amount ?: 0.0
        val steamAsWater = steam / EXPANSION_FACTOR

        !cell.wire.thermalBody.energy + steamAsWater * (LATENT_HEAT - (STEAM_CAPACITY - WATER_CAPACITY) * BOILING_POINT)
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun pureFluidsBoilAndCondenseAlongTheSamePath(helper: GameTestHelper) {
        transformations().forEach { transformation ->
            val boiling = transformation.boiling

            if (boiling != null && boiling.resultLiquidResidue == null) {
                val condensation = FluidTransformationManager.getTransformations(boiling.resultGas)?.condensation
                    ?: gameTestFail("${boiling.resultGas.id()} has no way back to ${transformation.fluid.id()}")

                checkThat(condensation.resultLiquid == transformation.fluid) {
                    "${boiling.resultGas.id()} condenses into ${condensation.resultLiquid.id()}"
                }

                checkClose(!boiling.temperature, !condensation.temperature, 1e-9, "${transformation.fluid.id()} point")
                checkClose(!boiling.enthalpy, !condensation.enthalpy, 1e-9, "${transformation.fluid.id()} enthalpy")
            }

            val condensation = transformation.condensation

            if (condensation != null && condensation.resultGasResidue == null) {
                val reverse = FluidTransformationManager.getTransformations(condensation.resultLiquid)?.boiling
                    ?: gameTestFail("${condensation.resultLiquid.id()} has no way back to ${transformation.fluid.id()}")

                checkThat(reverse.resultGas == transformation.fluid) {
                    "${condensation.resultLiquid.id()} boils into ${reverse.resultGas.id()}"
                }
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fractionalBoilingPaysTheLatentHeatOfItsGas(helper: GameTestHelper) {
        transformations().forEach { transformation ->
            val boiling = transformation.boiling

            if (boiling?.resultLiquidResidue != null) {
                val condensation = FluidTransformationManager.getTransformations(boiling.resultGas)?.condensation
                    ?: gameTestFail("${boiling.resultGas.id()} cannot condense")

                val expected = !condensation.enthalpy * boiling.resultGasProportion / 1000.0

                checkClose(expected, !boiling.enthalpy, expected * 0.01, "Enthalpy of ${transformation.fluid.id()}")
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun transformationsConserveMass(helper: GameTestHelper) {
        fun check(source: Fluid, first: Fluid, firstShare: Double, second: Fluid?) {
            val secondMass = if (second != null) {
                density(second) * (1.0 - firstShare)
            } else {
                0.0
            }

            val products = density(first) * firstShare + secondMass
            val expected = density(source)

            checkThat(abs(products - expected) <= expected * MASS_TOLERANCE) {
                "${source.id()} of density $expected turns into products of density $products"
            }
        }

        transformations().forEach { transformation ->
            transformation.boiling?.also {
                check(transformation.fluid, it.resultGas, it.resultGasProportion / 1000.0, it.resultLiquidResidue)
            }

            transformation.condensation?.also {
                check(transformation.fluid, it.resultLiquid, it.resultLiquidProportion / 1000.0, it.resultGasResidue)
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun gasesExpandAndLiquidsDoNot(helper: GameTestHelper) {
        fun check(fluid: Fluid, shouldBeGas: Boolean) {
            val properties = PhysicalFluidManager.requireProperties(fluid)

            checkThat(properties.isGaseous == shouldBeGas) {
                "${fluid.id()} has the wrong phase"
            }

            checkClose(if (shouldBeGas) EXPANSION_FACTOR else 1.0, properties.gasExpansionFactor, 1e-9, fluid.id())
        }

        transformations().forEach { transformation ->
            transformation.boiling?.also {
                check(transformation.fluid, false)
                check(it.resultGas, true)
                it.resultLiquidResidue?.also { residue -> check(residue, false) }
            }

            transformation.condensation?.also {
                check(transformation.fluid, true)
                check(it.resultLiquid, false)
                it.resultGasResidue?.also { residue -> check(residue, true) }
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun boilingKeepsTheEnergyOfTheTank(helper: GameTestHelper) {
        val tank = helper.placeTank()

        tank.fill(Fluids.WATER to 1000.0, null, 450.0)

        val energyBefore = tank.waterEnergy()

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkThat(tank.gasOf(STEAM) > 0.0) {
                    "The water did not boil"
                }

                checkClose(energyBefore, tank.waterEnergy(), 500.0, "Energy of the tank")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun condensingKeepsTheEnergyOfTheTank(helper: GameTestHelper) {
        val tank = helper.placeTank()

        tank.fill(null, STEAM to 5000.0, 300.0)

        val energyBefore = tank.waterEnergy()

        helper.startSequence()
            .thenExecuteAfter(10) {
                checkThat(tank.liquidOf(Fluids.WATER) > 0.0) {
                    "The steam did not condense"
                }

                checkClose(energyBefore, tank.waterEnergy(), 500.0, "Energy of the tank")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun boilingStopsAtTheBoilingPointAndKeepsTheVolume(helper: GameTestHelper) {
        val tank = helper.placeTank()

        tank.fill(Fluids.WATER to 100.0, null, 380.0)

        helper.startSequence()
            .thenExecuteAfter(60) {
                checkClose(BOILING_POINT, tank.temperature(), 0.1, "Temperature of the boiling tank")

                tank.cell.graph.runSuspended {
                    val steam = tank.gasAmount(STEAM)

                    checkThat(steam > 0.0) {
                        "The water did not boil"
                    }

                    checkClose(100.0, tank.liquidAmount(Fluids.WATER) + steam / EXPANSION_FACTOR, 1e-6, "Volume")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun fractionalBoilingSplitsInTheRightProportion(helper: GameTestHelper) {
        val tank = helper.placeTank()
        val crudeOil = Eln2ForgeFluids.CRUDE_OIL.get()
        val heavyOil = Eln2ForgeFluids.HEAVY_OIL.get()
        val naphthaGas = Eln2ForgeFluids.NAPHTHA_GAS.get()

        tank.fill(crudeOil to 1000.0, null, 500.0)

        helper.startSequence()
            .thenExecuteAfter(20) {
                tank.cell.graph.runSuspended {
                    val boiled = 1000.0 - tank.liquidAmount(crudeOil)

                    checkThat(boiled > 0.0) {
                        "The crude oil did not boil"
                    }

                    checkClose(boiled * 0.6, tank.liquidAmount(heavyOil), 1e-6, "Heavy oil")
                    checkClose(boiled * 0.4 * EXPANSION_FACTOR, tank.gasAmount(naphthaGas), 1e-6, "Naphtha gas")
                }
            }
            .thenSucceed()
    }
}
