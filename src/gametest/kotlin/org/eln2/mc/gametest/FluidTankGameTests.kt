package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.Mass
import org.ageseries.libage.data.OptionalDouble
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.ThermalMass
import org.eln2.mc.MODID
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.fluids.foundation.EscapingMultipleFractionalFluidTank
import org.eln2.mc.common.fluids.foundation.FractionalFluidStack
import org.eln2.mc.common.fluids.foundation.FractionalFluidTankCapacityConstraint
import org.eln2.mc.common.fluids.foundation.GravityBasedMultipleFluidTank
import org.eln2.mc.common.fluids.foundation.GravityBasedMultipleFractionalFluidTank
import org.eln2.mc.common.fluids.foundation.IFractionalFluidHandler
import org.eln2.mc.common.fluids.foundation.MultipleFluidTank
import org.eln2.mc.common.fluids.foundation.MultipleFractionalFluidTank
import org.eln2.mc.common.fluids.foundation.PhysicalFluidManager
import org.eln2.mc.common.fluids.foundation.PurityBasedMultipleFluidTank
import org.eln2.mc.common.fluids.foundation.PurityBasedMultipleFractionalFluidTank
import org.eln2.mc.common.fluids.foundation.ThermalObjectBasedFractionalFluidHandlerThermalExpansion
import java.util.concurrent.locks.ReentrantLock
import kotlin.random.Random

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object FluidTankGameTests {
    private val WATER: Fluid get() = Fluids.WATER
    private val DIESEL: Fluid get() = Eln2ForgeFluids.DIESEL.source.get()
    private val NAPHTHA: Fluid get() = Eln2ForgeFluids.NAPHTHA.source.get()

    private fun tank(capacity: Int, vararg contents: Pair<Fluid, Int>) = MultipleFluidTank(capacity, false).also { tank ->
        contents.forEach { (fluid, amount) ->
            tank.fill(FluidStack(fluid, amount), FluidAction.EXECUTE)
        }
    }

    private fun fractionalTank(capacity: Double, vararg contents: Pair<Fluid, Double>) =
        MultipleFractionalFluidTank(capacity, false).also { tank ->
            contents.forEach { (fluid, amount) ->
                tank.fillFractional(FractionalFluidStack(fluid, amount), FluidAction.EXECUTE)
            }
        }

    private fun MultipleFluidTank.amountOf(fluid: Fluid) = fluids.firstOrNull { it.fluid == fluid }?.amount ?: 0

    private fun checkTankRules(tank: MultipleFluidTank) {
        checkThat(tank.amount <= tank.capacity) {
            "The tank holds ${tank.amount} over its capacity of ${tank.capacity}"
        }

        checkThat(tank.fluids.all { !it.isEmpty && it.amount > 0 }) {
            "The tank holds an empty stack"
        }

        checkThat(tank.fluids.map { it.fluid }.toSet().size == tank.fluids.size) {
            "The tank holds two stacks of the same fluid"
        }
    }

    private fun checkTankRules(tank: MultipleFractionalFluidTank) {
        checkThat(tank.amount <= tank.capacity + FractionalFluidStack.EPSILON) {
            "The tank holds ${tank.amount} over its capacity of ${tank.capacity}"
        }

        checkThat(tank.fluids.all { it.isNotEmpty }) {
            "The tank holds an empty stack"
        }

        checkThat(tank.fluids.map { it.fluid }.toSet().size == tank.fluids.size) {
            "The tank holds two stacks of the same fluid"
        }
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fillRespectsTheCapacityAndReportsWhatItFilled(helper: GameTestHelper) {
        val tank = tank(1000)

        checkThat(tank.fill(FluidStack(WATER, 700), FluidAction.EXECUTE) == 700) {
            "Expected to fill 700 mB"
        }

        checkThat(tank.fill(FluidStack(DIESEL, 700), FluidAction.EXECUTE) == 300) {
            "Expected only 300 mB to fit"
        }

        checkThat(tank.fill(FluidStack(DIESEL, 1), FluidAction.EXECUTE) == 0) {
            "A full tank should refuse fluid"
        }

        checkThat(tank.remainingCapacity == 0 && tank.amount == 1000) {
            "Expected a full tank, got ${tank.amount}"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun simulatedOperationsChangeNothing(helper: GameTestHelper) {
        val tank = tank(1000, WATER to 500)
        val version = tank.version

        checkThat(tank.fill(FluidStack(WATER, 100), FluidAction.SIMULATE) == 100) {
            "A simulated fill should report the amount"
        }

        checkThat(tank.drain(FluidStack(WATER, 100), FluidAction.SIMULATE).amount == 100) {
            "A simulated drain should report the amount"
        }

        checkThat(tank.drain(100, FluidAction.SIMULATE).amount == 100) {
            "A simulated drain should report the amount"
        }

        checkThat(tank.amount == 500 && tank.version == version) {
            "A simulation changed the tank"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun drainingTakesTheRequestedFluidAndRemovesEmptyStacks(helper: GameTestHelper) {
        val tank = tank(1000, WATER to 300, DIESEL to 200)
        val drained = tank.drain(FluidStack(DIESEL, 500), FluidAction.EXECUTE)

        checkThat(drained.fluid == DIESEL && drained.amount == 200) {
            "Expected 200 mB of diesel, got ${drained.amount}"
        }

        checkThat(tank.fluids.size == 1 && tank.amountOf(WATER) == 300) {
            "The drained stack should be removed"
        }

        checkThat(tank.drain(FluidStack(DIESEL, 1), FluidAction.EXECUTE).isEmpty) {
            "There is no more diesel to drain"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun thermalTankRefusesFluidsWithoutProperties(helper: GameTestHelper) {
        val tank = MultipleFluidTank(1000, true)

        checkThat(PhysicalFluidManager.getProperties(WATER) != null) {
            "Water should have physical properties"
        }

        checkThat(tank.fill(FluidStack(WATER, 10), FluidAction.EXECUTE) == 10) {
            "A thermal tank should accept water"
        }

        val withoutProperties = listOf(Fluids.LAVA, Fluids.FLOWING_WATER).firstOrNull {
            PhysicalFluidManager.getProperties(it) == null
        } ?: gameTestFail("Expected a fluid without physical properties")

        checkThat(tank.fill(FluidStack(withoutProperties, 10), FluidAction.EXECUTE) == 0) {
            "A thermal tank should refuse a fluid without properties"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun randomOperationsConserveFluidInAnIntegerTank(helper: GameTestHelper) {
        val fluids = listOf(WATER, DIESEL, NAPHTHA)
        val random = Random(1)
        val tank = tank(1000)
        val expected = HashMap<Fluid, Int>()

        repeat(5000) {
            val fluid = fluids.random(random)
            val amount = random.nextInt(1, 400)

            when (random.nextInt(3)) {
                0 -> {
                    val filled = tank.fill(FluidStack(fluid, amount), FluidAction.EXECUTE)

                    expected.merge(fluid, filled) { first, second -> first + second }
                }

                1 -> {
                    val drained = tank.drain(FluidStack(fluid, amount), FluidAction.EXECUTE)

                    if (!drained.isEmpty) {
                        expected.merge(drained.fluid, -drained.amount) { first, second -> first + second }
                    }
                }

                else -> {
                    val drained = tank.drain(amount, FluidAction.EXECUTE)

                    if (!drained.isEmpty) {
                        expected.merge(drained.fluid, -drained.amount) { first, second -> first + second }
                    }
                }
            }

            checkTankRules(tank)

            fluids.forEach {
                checkThat(tank.amountOf(it) == (expected[it] ?: 0)) {
                    "$it: the tank holds ${tank.amountOf(it)}, expected ${expected[it]}"
                }
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun integerTankSurvivesSaving(helper: GameTestHelper) {
        val tank = tank(1000, WATER to 300, DIESEL to 200)
        val restored = MultipleFluidTank(1000, false)

        restored.deserializeNBT(tank.serializeNBT())

        checkThat(restored.amountOf(WATER) == 300 && restored.amountOf(DIESEL) == 200) {
            "The restored tank has different contents"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun purityTankDrainsAPureFluidNormally(helper: GameTestHelper) {
        val parent = tank(1000, WATER to 500)
        val tank = PurityBasedMultipleFluidTank(parent)
        val drained = tank.drain(100, FluidAction.EXECUTE)

        checkThat(drained.fluid == WATER && drained.amount == 100 && parent.amount == 400) {
            "Expected to drain 100 mB of the pure fluid"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun purityTankRefusesAMixtureBelowItsThreshold(helper: GameTestHelper) {
        val parent = tank(1000, WATER to 500, DIESEL to 500)
        val tank = PurityBasedMultipleFluidTank(parent)

        checkThat(tank.drain(100, FluidAction.EXECUTE).isEmpty) {
            "An impure mixture should not drain"
        }

        checkThat(tank.getFluidInTank(0).isEmpty) {
            "An impure mixture has no representative"
        }

        checkThat(parent.amount == 1000) {
            "Refusing should not change the tank"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun purityTankVoidsImpuritiesInProportion(helper: GameTestHelper) {
        val parent = tank(1000, WATER to 970, DIESEL to 30)
        val tank = PurityBasedMultipleFluidTank(parent)

        checkThat(tank.getFluidInTank(0).fluid == WATER) {
            "Water should be the representative"
        }

        val drained = tank.drain(100, FluidAction.EXECUTE)

        checkThat(drained.fluid == WATER && drained.amount == 97) {
            "Expected 97 mB of water, got ${drained.amount}"
        }

        checkThat(parent.amountOf(WATER) == 873 && parent.amountOf(DIESEL) == 27) {
            "Expected 873 + 27 mB, got ${parent.amountOf(WATER)} + ${parent.amountOf(DIESEL)}"
        }

        checkTankRules(parent)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun purityTankIgnoresTooSmallRequestsAndOtherFluids(helper: GameTestHelper) {
        val parent = tank(1000, WATER to 970, DIESEL to 30)
        val tank = PurityBasedMultipleFluidTank(parent)

        checkThat(tank.drain(1, FluidAction.EXECUTE).isEmpty) {
            "A request that rounds to nothing should do nothing"
        }

        checkThat(tank.drain(FluidStack(DIESEL, 10), FluidAction.EXECUTE).isEmpty) {
            "An impurity should not be extractable"
        }

        checkThat(parent.amount == 1000) {
            "Nothing should have been removed"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun purityTankNeverReturnsMoreThanItRemoves(helper: GameTestHelper) {
        val random = Random(2)

        repeat(500) {
            val impurity = random.nextInt(1, 50)
            val parent = tank(2000, WATER to 1900, DIESEL to impurity, NAPHTHA to random.nextInt(1, 40))
            val tank = PurityBasedMultipleFluidTank(parent)

            repeat(30) {
                val before = parent.amount
                val request = random.nextInt(1, 300)
                val drained = tank.drain(request, FluidAction.EXECUTE)

                checkThat(drained.amount <= request && before - parent.amount >= drained.amount) {
                    "Drained ${drained.amount} of $request, the tank lost ${before - parent.amount}"
                }

                checkThat(before - parent.amount <= request) {
                    "The request of $request removed ${before - parent.amount}"
                }

                checkTankRules(parent)
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun gravityTankExtractsTheDensestPhaseFirst(helper: GameTestHelper) {
        val parent = tank(1000, DIESEL to 300, WATER to 200)
        val tank = GravityBasedMultipleFluidTank(parent)

        checkThat(tank.findSeparatingStack()?.fluid == WATER) {
            "Water is denser than diesel, it should be at the bottom"
        }

        checkThat(tank.drain(FluidStack(DIESEL, 100), FluidAction.EXECUTE).isEmpty) {
            "Diesel floats on water, it should not be extractable"
        }

        val water = tank.drain(500, FluidAction.EXECUTE)

        checkThat(water.fluid == WATER && water.amount == 200) {
            "Expected all of the water"
        }

        val diesel = tank.drain(500, FluidAction.EXECUTE)

        checkThat(diesel.fluid == DIESEL && diesel.amount == 300) {
            "The diesel should be extractable after the water is gone"
        }

        checkThat(tank.drain(500, FluidAction.EXECUTE).isEmpty && tank.findSeparatingStack() == null) {
            "An empty tank has nothing to extract"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun gravityTankNoticesChangesToItsContents(helper: GameTestHelper) {
        val parent = tank(1000, DIESEL to 300)
        val tank = GravityBasedMultipleFluidTank(parent)

        checkThat(tank.findSeparatingStack()?.fluid == DIESEL) {
            "Diesel is the only fluid"
        }

        parent.fill(FluidStack(WATER, 100), FluidAction.EXECUTE)

        checkThat(tank.findSeparatingStack()?.fluid == WATER) {
            "The new denser fluid should sink to the bottom"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fractionalTankKeepsFractionsAndRoundsDiscreteRequests(helper: GameTestHelper) {
        val tank = fractionalTank(10.5)

        checkThat(tank.fill(FluidStack(WATER, 100), FluidAction.EXECUTE) == 10) {
            "A discrete fill should round the remaining capacity down"
        }

        checkClose(0.5, tank.fillFractional(FractionalFluidStack(DIESEL, 3.0), FluidAction.EXECUTE), 1e-9, "Fractional fill")

        val drained = tank.drain(FluidStack(DIESEL, 1), FluidAction.EXECUTE)

        checkThat(drained.isEmpty) {
            "Half a millibucket should not be drained by a discrete request"
        }

        checkClose(0.5, tank.drainFractional(FractionalFluidStack(DIESEL, 3.0), FluidAction.EXECUTE).amount, 1e-9, "Fractional drain")
        checkTankRules(tank)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fractionalDrainByAmountTakesFromTheStackItReports(helper: GameTestHelper) {
        val tank = fractionalTank(1000.0, DIESEL to 0.25, WATER to 100.0)
        val drained = tank.drain(50, FluidAction.EXECUTE)

        checkThat(drained.fluid == WATER && drained.amount == 50) {
            "Expected 50 mB of water, the diesel does not reach one millibucket"
        }

        checkClose(50.0, tank.getAmountOf(WATER), 1e-9, "Water left in the tank")
        checkClose(0.25, tank.getAmountOf(DIESEL), 1e-9, "Diesel left in the tank")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fillWithDisplacementFillsWhatItReports(helper: GameTestHelper) {
        val tank = fractionalTank(100.0, WATER to 99.0, DIESEL to 1.0)
        val reported = tank.fillWithDisplacement(FractionalFluidStack(NAPHTHA, 50.0), FluidAction.EXECUTE)

        checkClose(reported, tank.getAmountOf(NAPHTHA), 1e-9, "Displaced fill")
        checkClose(100.0, tank.amount, 1e-9, "Total after displacing")
        checkTankRules(tank)
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fractionalTankSurvivesSaving(helper: GameTestHelper) {
        val tank = fractionalTank(100.0, WATER to 12.25, DIESEL to 0.5)
        val restored = MultipleFractionalFluidTank(100.0, false)

        restored.deserializeNBT(tank.serializeNBT())

        checkClose(12.25, restored.getAmountOf(WATER), 0.0, "Restored water")
        checkClose(0.5, restored.getAmountOf(DIESEL), 0.0, "Restored diesel")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fractionalPurityTankVoidsImpuritiesInProportion(helper: GameTestHelper) {
        val parent = fractionalTank(1000.0, WATER to 970.0, DIESEL to 30.0)
        val tank = PurityBasedMultipleFractionalFluidTank(parent)
        val drained = tank.drainFractional(100.0, FluidAction.EXECUTE)

        checkThat(drained.fluid == WATER) {
            "Water should be drained"
        }

        checkClose(97.0, drained.amount, 1e-9, "Drained water")
        checkClose(873.0, parent.getAmountOf(WATER), 1e-9, "Remaining water")
        checkClose(27.0, parent.getAmountOf(DIESEL), 1e-9, "Remaining diesel")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fractionalGravityTankExtractsTheDensestPhaseFirst(helper: GameTestHelper) {
        val parent = fractionalTank(1000.0, DIESEL to 300.5, WATER to 200.5)
        val tank = GravityBasedMultipleFractionalFluidTank(parent)

        checkThat(tank.drainFractional(FractionalFluidStack(DIESEL, 10.0), FluidAction.EXECUTE).isEmpty) {
            "Diesel floats on water, it should not be extractable"
        }

        val water = tank.drainFractional(1000.0, FluidAction.EXECUTE)

        checkThat(water.fluid == WATER) {
            "Water should come out first"
        }

        checkClose(200.5, water.amount, 1e-9, "Drained water")

        val diesel = tank.drainFractional(1000.0, FluidAction.EXECUTE)

        checkThat(diesel.fluid == DIESEL) {
            "Diesel should come out after the water"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun capacityConstraintSharesTheVolumeBetweenTanks(helper: GameTestHelper) {
        val liquid = fractionalTank(0.0)
        val gas = fractionalTank(0.0)

        FractionalFluidTankCapacityConstraint(100.0, arrayOf(liquid, gas))

        checkClose(70.0, liquid.fillFractional(FractionalFluidStack(WATER, 70.0), FluidAction.EXECUTE), 1e-9, "Liquid fill")
        checkClose(30.0, gas.fillFractional(FractionalFluidStack(DIESEL, 50.0), FluidAction.EXECUTE), 1e-9, "Gas fill")
        checkClose(0.0, liquid.fillFractional(FractionalFluidStack(WATER, 1.0), FluidAction.EXECUTE), 1e-9, "Fill past the limit")

        liquid.drainFractional(20.0, FluidAction.EXECUTE)

        checkClose(20.0, gas.fillFractional(FractionalFluidStack(DIESEL, 50.0), FluidAction.EXECUTE), 1e-9, "Fill after draining")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun capacityConstraintRespectsTheMaximumOfEachTank(helper: GameTestHelper) {
        val liquid = fractionalTank(0.0)
        val gas = fractionalTank(0.0)

        FractionalFluidTankCapacityConstraint(100.0, arrayOf(liquid, gas), doubleArrayOf(40.0, 100.0))

        checkClose(40.0, liquid.fillFractional(FractionalFluidStack(WATER, 70.0), FluidAction.EXECUTE), 1e-9, "Capped fill")
        checkClose(60.0, gas.fillFractional(FractionalFluidStack(DIESEL, 70.0), FluidAction.EXECUTE), 1e-9, "Shared fill")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun escapingTankMovesFluidTowardTheOutletAndVoidsTheOverflow(helper: GameTestHelper) {
        val tank = EscapingMultipleFractionalFluidTank(3, 100.0, false)

        checkClose(100.0, tank.fillFractional(FractionalFluidStack(WATER, 150.0), FluidAction.EXECUTE), 1e-9, "Fill")

        checkThat(tank.drainFractional(10.0, FluidAction.EXECUTE).isEmpty) {
            "The fluid has not reached the outlet yet"
        }

        tank.flow()
        tank.flow()

        checkClose(100.0, tank.extractionEnd.amount, 1e-9, "Fluid at the outlet")
        checkClose(0.0, tank.insertionEnd.amount, 1e-9, "Fluid at the inlet")

        repeat(2) {
            tank.fillFractional(FractionalFluidStack(WATER, 100.0), FluidAction.EXECUTE)
            tank.flow()
            tank.flow()
            tank.flow()
        }

        checkThat(tank.extractionEnd.amount <= 300.0) {
            "The fluid at the outlet grew to ${tank.extractionEnd.amount}"
        }

        checkClose(40.0, tank.drainFractional(40.0, FluidAction.EXECUTE).amount, 1e-9, "Drain at the outlet")
        helper.succeed()
    }

    private class ThermalTank(
        override val parent: IFractionalFluidHandler,
        private val tank: MultipleFractionalFluidTank,
        val body: ThermalMass,
        hullMaterial: Material,
        hullMass: Quantity<Mass>
    ) : ThermalObjectBasedFractionalFluidHandlerThermalExpansion {
        override val handle = ThermalObjectBasedFractionalFluidHandlerThermalExpansion.ThermalBodyHandle(
            body,
            hullMaterial,
            hullMass,
            ReentrantLock()
        )

        override val ambientTemperature = Quantity(300.0, KELVIN)

        override fun getFluidStacks() = tank.fluids

        override fun onMutated() {}
    }

    private fun thermalTank(wrap: (MultipleFractionalFluidTank) -> IFractionalFluidHandler = { it }): ThermalTank {
        val material = ChemicalElement.Iron.asMaterial
        val mass = Quantity(10.0, KILOGRAM)
        val body = ThermalMass(material, mass = mass)
        val tank = fractionalTank(10000.0)

        body.temperature = Quantity(300.0, KELVIN)

        return ThermalTank(wrap(tank), tank, body, material, mass)
    }

    private fun waterCapacityPerMillibucket() = !PhysicalFluidManager.requireProperties(WATER).specificHeatCapacity

    @GameTest(template = ARENA)
    @JvmStatic
    fun hotFluidMixesWithTheHullByHeatCapacity(helper: GameTestHelper) {
        val tank = thermalTank()
        val hullCapacity = 10.0 * !ChemicalElement.Iron.asMaterial.specificHeat
        val fluidCapacity = 100.0 * waterCapacityPerMillibucket()

        tank.fillThermal(FractionalFluidStack(WATER, 100.0), OptionalDouble.wrap(400.0), FluidAction.EXECUTE)

        val expected = (hullCapacity * 300.0 + fluidCapacity * 400.0) / (hullCapacity + fluidCapacity)

        checkClose(expected, !tank.body.temperature, 1e-6, "Temperature after mixing")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fluidFilledWithoutATemperatureArrivesAtAmbient(helper: GameTestHelper) {
        val tank = thermalTank()

        tank.body.temperature = Quantity(300.0, KELVIN)
        tank.fillFractional(FractionalFluidStack(WATER, 100.0), FluidAction.EXECUTE)

        checkClose(300.0, !tank.body.temperature, 1e-6, "Temperature after an ambient fill")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun drainingKeepsTheTemperatureAndExportsIt(helper: GameTestHelper) {
        val tank = thermalTank()

        tank.fillThermal(FractionalFluidStack(WATER, 100.0), OptionalDouble.wrap(400.0), FluidAction.EXECUTE)

        val temperature = !tank.body.temperature
        val simulated = tank.drainThermal(40.0, FluidAction.SIMULATE)

        checkClose(temperature, simulated!!.temperature, 1e-9, "Simulated export temperature")
        checkClose(temperature, !tank.body.temperature, 1e-9, "Temperature after a simulation")

        val drained = tank.drainThermal(FractionalFluidStack(WATER, 40.0), FluidAction.EXECUTE)

        checkClose(temperature, drained!!.temperature, 1e-9, "Export temperature")
        checkClose(temperature, !tank.body.temperature, 1e-6, "Temperature after draining")

        tank.drainThermal(1000.0, FluidAction.EXECUTE)

        checkClose(temperature, !tank.body.temperature, 1e-6, "Temperature after draining everything")
        checkClose(10.0, !tank.body.mass, 1e-9, "Mass of the empty hull")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun voidingImpuritiesDoesNotHeatTheTank(helper: GameTestHelper) {
        val tank = thermalTank { PurityBasedMultipleFractionalFluidTank(it) }

        tank.fillThermal(FractionalFluidStack(WATER, 970.0), OptionalDouble.wrap(400.0), FluidAction.EXECUTE)
        tank.fillThermal(FractionalFluidStack(DIESEL, 30.0), OptionalDouble.wrap(!tank.body.temperature), FluidAction.EXECUTE)

        val temperature = !tank.body.temperature

        repeat(5) {
            tank.drainThermal(100.0, FluidAction.EXECUTE)
        }

        checkClose(temperature, !tank.body.temperature, 1e-6, "Temperature after voiding impurities")
        helper.succeed()
    }
}
