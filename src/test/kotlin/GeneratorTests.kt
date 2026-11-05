import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.KILOGRAM_METER2
import org.ageseries.libage.data.NEWTON_METER
import org.ageseries.libage.data.NEWTON_METER_SECOND
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.RADIAN_PER_SECOND
import org.ageseries.libage.data.VOLT
import org.ageseries.libage.data.WATT
import org.ageseries.libage.data.WATT_PER_KELVIN
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ThermalMass
import org.eln2.mc.common.content.ThermalElectricGenerator
import org.eln2.mc.common.content.ThermalElectricGeneratorModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.math.min

class GeneratorTests {
    private val deltaTime = 0.01
    private val referenceAngularVelocity = 125.66
    private val nominalPotential = 120.0
    private val maxElectricalTorque = 5.0
    private val maxDevicePower = 2400.0
    private val inertia = 1.0

    private val model = ThermalElectricGeneratorModel(
        Quantity(referenceAngularVelocity, RADIAN_PER_SECOND),
        Quantity(nominalPotential, VOLT),
        0.5,
        Quantity(10.0, WATT_PER_KELVIN),
        Quantity(0.05, WATT_PER_KELVIN),
        Quantity(inertia, KILOGRAM_METER2),
        Quantity(0.025, NEWTON_METER_SECOND),
        Quantity(maxElectricalTorque, NEWTON_METER),
        Quantity(maxDevicePower, WATT),
        0.9,
        Quantity(2.5, WATT),
        0.01
    )

    private fun body(temperature: Double) =
        ThermalMass(ChemicalElement.Iron.asMaterial, mass = Quantity(1000.0, KILOGRAM)).also {
            it.temperature = Quantity(temperature, KELVIN)
        }

    private class Run(val generator: ThermalElectricGenerator, val hot: ThermalMass, val cold: ThermalMass) {
        var delivered = 0.0
        var peakPower = 0.0
        var peakTorque = 0.0
    }

    private fun Run.step(loadResistance: Double) {
        generator.preTick(deltaTime)

        val potential = !generator.potentialOpenCircuit

        val power = if (loadResistance.isFinite() && potential > 0.0) {
            min(potential * potential / loadResistance, !generator.availablePower)
        } else {
            0.0
        }

        generator.postTick(Quantity(power, WATT), deltaTime)

        delivered += power * deltaTime
        peakPower = maxOf(peakPower, power)

        val angularVelocity = !generator.angularVelocity

        if (angularVelocity > 1.0) {
            peakTorque = maxOf(peakTorque, power / angularVelocity)
        }
    }

    private fun Run.totalEnergy(): Double {
        val angularVelocity = !generator.angularVelocity

        return !hot.energy + !cold.energy + 0.5 * inertia * angularVelocity * angularVelocity + delivered
    }

    private fun run(hotTemperature: Double = 800.0, coldTemperature: Double = 300.0): Run {
        val hot = body(hotTemperature)
        val cold = body(coldTemperature)

        return Run(ThermalElectricGenerator(cold, hot, model), hot, cold)
    }

    @Test
    fun totalEnergyIsConservedWhileSpinningUpAndUnderLoad() {
        val run = run()
        val initial = run.totalEnergy()

        repeat(6000) { step ->
            run.step(if (step < 2000) Double.POSITIVE_INFINITY else 30.0)

            assertEquals(initial, run.totalEnergy(), initial * 1e-9) {
                "Step $step"
            }
        }

        assertTrue(run.delivered > 0.0)
    }

    @Test
    fun engineSpinsUpOnATemperatureDifferenceAndStaysStillWithout() {
        val spinning = run()
        val still = run(300.0, 300.0)

        repeat(2000) {
            spinning.step(Double.POSITIVE_INFINITY)
            still.step(Double.POSITIVE_INFINITY)
        }

        assertTrue(!spinning.generator.angularVelocity > 10.0)
        assertEquals(0.0, !still.generator.angularVelocity, 1e-9)
    }

    @Test
    fun engineSlowsDownWhenTheTemperatureDifferenceIsGone() {
        val run = run()

        repeat(2000) {
            run.step(Double.POSITIVE_INFINITY)
        }

        val spinningSpeed = !run.generator.angularVelocity

        run.hot.temperature = run.cold.temperature

        repeat(2000) {
            run.step(Double.POSITIVE_INFINITY)
        }

        assertTrue(!run.generator.angularVelocity < spinningSpeed * 0.8)
    }

    @Test
    fun coldSideHotterThanTheHotSideGivesNoTorque() {
        val run = run(300.0, 800.0)

        repeat(1000) {
            run.step(Double.POSITIVE_INFINITY)
        }

        assertEquals(0.0, !run.generator.angularVelocity, 1e-9)
        assertEquals(0.0, !run.generator.engineTorque, 1e-9)
    }

    @Test
    fun outputPotentialScalesWithTheAngularVelocity() {
        val run = run()

        repeat(1000) {
            run.step(Double.POSITIVE_INFINITY)
        }

        run.generator.preTick(deltaTime)

        assertEquals(
            nominalPotential * !run.generator.angularVelocity / referenceAngularVelocity,
            !run.generator.potentialOpenCircuit,
            1e-9
        )
    }

    @Test
    fun outputStaysWithinTheDevicePowerAndTorqueLimitsIntoAHeavyLoad() {
        val run = run(3000.0, 300.0)

        repeat(2000) {
            run.step(Double.POSITIVE_INFINITY)
        }

        repeat(2000) {
            run.step(0.01)
        }

        assertTrue(run.peakPower > 0.0)
        assertTrue(run.peakPower <= maxDevicePower * (1.0 + 1e-9)) {
            "Peak power ${run.peakPower}"
        }
        assertTrue(run.peakTorque <= maxElectricalTorque * (1.0 + 1e-9)) {
            "Peak torque ${run.peakTorque}"
        }
    }

    @Test
    fun heavyLoadSlowsTheEngineDown() {
        val light = run()
        val heavy = run()

        repeat(4000) {
            light.step(1000.0)
            heavy.step(5.0)
        }

        assertTrue(!heavy.generator.angularVelocity < !light.generator.angularVelocity)
    }

    @Disabled("Bug: engine torque ignores the angular velocity, so shaft power can exceed the heat drawn.")
    @Test
    fun coldSideIsNeverCooledByRunningTheEngine() {
        val run = run(1500.0, 300.0)
        var coldEnergy = !run.cold.energy
        var largestHeatDrawn = 0.0

        repeat(6000) {
            run.step(Double.POSITIVE_INFINITY)

            val currentEnergy = !run.cold.energy

            largestHeatDrawn = maxOf(largestHeatDrawn, (coldEnergy - currentEnergy) / deltaTime)
            coldEnergy = currentEnergy
        }

        assertEquals(0.0, largestHeatDrawn, 1e-6) {
            "The running engine drew up to $largestHeatDrawn W out of the cold side"
        }
    }

    @Test
    fun hotSideLosesHeatAndColdSideGainsItWhileRunning() {
        val run = run()
        val hotBefore = !run.hot.energy
        val coldBefore = !run.cold.energy

        repeat(3000) {
            run.step(30.0)
        }

        assertTrue(!run.hot.energy < hotBefore)
        assertTrue(!run.cold.energy > coldBefore)
    }
}
