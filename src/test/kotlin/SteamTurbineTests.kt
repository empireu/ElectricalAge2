import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.NEWTON_METER
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.RADIAN_PER_SECOND
import org.ageseries.libage.data.WATT
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.eln2.mc.common.content.SteamExpansion
import org.eln2.mc.common.content.SteamProperties
import org.eln2.mc.common.content.SteamTurbineGeneratorModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SteamTurbineTests {
    private val deltaTime = 0.01
    private val maxFlowRate = 32.0
    private val etaFactor = 0.85
    private val maxTorque = 2400.0
    private val maxPower = 50000.0
    private val maxRejectedHeat = 50000.0
    private val referenceSpeed = 104.72
    private val governorOpenSpeed = 125.66
    private val governorClosedSpeed = 157.08
    private val breakdownSpeed = 500.0
    private val regulatorClosedTemperature = 433.15
    private val lowestBreakdownTemperature = 473.15 * 0.95
    private val condensationBand = 30.0
    private val boilingPoint = 373.15
    private val hottestTankSteam = 773.15
    private val ambient = 291.15

    private val properties = SteamProperties(2.0, 4.186, 2260.0, boilingPoint)

    private val model = SteamTurbineGeneratorModel(
        etaFactor = etaFactor,
        maxFlowRate = maxFlowRate,
        maxTorque = Quantity(maxTorque, NEWTON_METER),
        maxPower = Quantity(maxPower, WATT),
        maxRejectedHeat = Quantity(maxRejectedHeat, WATT),
        coldSideMass = Quantity(250.0, KILOGRAM),
        coldSideMaterial = ChemicalElement.Copper.asMaterial,
        coldSideLeakage = ConnectionParameters.DEFAULT,
        referenceAngularVelocity = Quantity(referenceSpeed, RADIAN_PER_SECOND),
        governorOpenAngularVelocity = Quantity(governorOpenSpeed, RADIAN_PER_SECOND),
        governorClosedAngularVelocity = Quantity(governorClosedSpeed, RADIAN_PER_SECOND),
        regulatorOpenTemperature = Quantity(120.0, CELSIUS),
        regulatorClosedTemperature = Quantity(160.0, CELSIUS),
        condensationBand = Quantity(condensationBand, KELVIN),
        breakdownAngularVelocity = Quantity(breakdownSpeed, RADIAN_PER_SECOND),
        breakdownTemperature = Quantity(200.0, CELSIUS),
        breakdownExplosionRadius = 3.0f
    )

    private fun expand(
        hot: Double,
        cold: Double,
        steam: Double = 1e6,
        speed: Double = referenceSpeed,
        valve: Double = 1.0
    ) = SteamExpansion.calculate(model, properties, steam, hot, cold, speed, valve, deltaTime)

    private fun expanded(hot: Double, cold: Double, steam: Double = 1e6, speed: Double = referenceSpeed) =
        expand(hot, cold, steam, speed) ?: error("Expected the steam at $hot K to expand down to $cold K")

    private fun valve(
        hot: Double = hottestTankSteam,
        cold: Double = 320.0,
        speed: Double = 0.0,
        waterFill: Double = 0.0,
        steamFill: Double = 0.0
    ) = model.valveOpening(properties, hot, cold, speed, waterFill, steamFill)

    private fun steamEnthalpy(temperature: Double) =
        4.186 * boilingPoint + 2260.0 + 2.0 * (temperature - boilingPoint)

    @Test
    fun thermalPowerIsTheFlowTimesTheDropFromSteamToWater() {
        val expansion = expanded(500.0, 300.0)
        val drop = steamEnthalpy(500.0) - 4.186 * 300.0

        assertEquals(expansion.flow * drop, expansion.thermalPower, 1e-6)
        assertEquals(1.0, expansion.condensedFraction, 1e-12)
    }

    @Test
    fun mechanicalPowerFollowsTheScaledCarnotEfficiency() {
        val expansion = expanded(500.0, 300.0)
        val efficiency = (1.0 - 300.0 / 500.0) * etaFactor

        assertEquals(efficiency * expansion.thermalPower, expansion.mechanicalPower, 1e-6)
    }

    @Test
    fun steamIsThrottledAtTheRatingInsteadOfBeingWasted() {
        val expansion = expanded(hottestTankSteam, ambient)

        assertEquals(maxPower, expansion.mechanicalPower, 1e-6)
        assertTrue(expansion.flow < maxFlowRate) {
            "The turbine still uses ${expansion.flow} gas mB/s at its power rating"
        }
    }

    @Test
    fun hottestTankSteamMeetsBothRatingsAtTheDesignPoint() {
        val expansion = expanded(hottestTankSteam, 320.0)

        assertEquals(maxPower, expansion.mechanicalPower, maxPower * 0.02)
        assertEquals(maxRejectedHeat, expansion.thermalPower - expansion.mechanicalPower, maxRejectedHeat * 0.02)
        assertTrue(expansion.flow <= maxFlowRate)
    }

    @Test
    fun rejectedHeatAndMechanicalPowerStayWithinTheirRatings() {
        for (hot in 380..1500 step 20) {
            for (cold in 280 until hot step 5) {
                for (speed in listOf(0.0, 5.0, 20.0, referenceSpeed)) {
                    val expansion = expanded(hot.toDouble(), cold.toDouble(), speed = speed)
                    val delivered = min(expansion.mechanicalPower, maxTorque * speed)

                    assertTrue(expansion.thermalPower - delivered <= maxRejectedHeat + 1e-6) {
                        "Hot $hot K, cold $cold K, speed $speed: ${expansion.thermalPower - delivered} W rejected"
                    }

                    assertTrue(expansion.mechanicalPower <= maxPower + 1e-6)
                    assertTrue(expansion.mechanicalPower <= expansion.thermalPower)
                    assertTrue(expansion.flow <= maxFlowRate + 1e-9)
                }
            }
        }
    }

    @Test
    fun condensedFractionFallsSmoothlyAcrossTheBand() {
        assertEquals(1.0, properties.condensedFraction(boilingPoint - condensationBand, condensationBand), 1e-12)
        assertEquals(0.5, properties.condensedFraction(boilingPoint - condensationBand / 2.0, condensationBand), 1e-12)
        assertEquals(0.0, properties.condensedFraction(boilingPoint, condensationBand), 1e-12)
    }

    @Test
    fun outputChangesSmoothlyWithTheColdSideTemperature() {
        val step = 0.01
        val maxSlope = 6000.0
        var temperature = 300.0
        var previous = expanded(hottestTankSteam, temperature)

        while (temperature < 420.0) {
            temperature += step

            val current = expanded(hottestTankSteam, temperature)

            assertTrue(abs(current.thermalPower - previous.thermalPower) / step < maxSlope) {
                "Thermal power jumps at $temperature K"
            }

            assertTrue(abs(current.mechanicalPower - previous.mechanicalPower) / step < maxSlope) {
                "Mechanical power jumps at $temperature K"
            }

            previous = current
        }
    }

    @Test
    fun flowIsLimitedByTheSteamThatIsLeft() {
        val available = expanded(hottestTankSteam, 320.0).flow * deltaTime * 0.25
        val expansion = expanded(hottestTankSteam, 320.0, available)

        assertEquals(available, expansion.flow * deltaTime, 1e-9)
    }

    @Test
    fun exhaustStaysSteamAboveTheBoilingPoint() {
        val expansion = expanded(800.0, 400.0)

        assertEquals(0.0, expansion.condensedFraction, 1e-12)
        assertEquals(expansion.flow * 2.0 * 400.0, expansion.thermalPower, 1e-6)
    }

    @Test
    fun aStalledShaftCannotDumpMoreThanTheRejectedHeatLimit() {
        val expansion = expanded(hottestTankSteam, ambient, speed = 0.0)

        assertEquals(maxRejectedHeat, expansion.thermalPower, 1e-6)
        assertTrue(expansion.mechanicalPower > 0.0)
    }

    @Test
    fun nothingExpandsWithoutSteamOrATemperatureDifferenceOrAnOpenValve() {
        assertNull(expand(500.0, 300.0, 0.0))
        assertNull(expand(300.0, 300.0))
        assertNull(expand(300.0, 500.0))
        assertNull(expand(500.0, 300.0, valve = 0.0))
    }

    @Test
    fun governorClosesLongBeforeTheBreakdownSpeed() {
        assertEquals(1.0, valve(speed = governorOpenSpeed), 1e-12)
        assertEquals(0.0, valve(speed = governorClosedSpeed), 1e-12)
        assertTrue(valve(speed = 0.5 * (governorOpenSpeed + governorClosedSpeed)) in 0.4..0.6)
        assertTrue(governorClosedSpeed < breakdownSpeed * 0.5)
        assertEquals(1.0, valve(speed = -200.0), 1e-12)
    }

    @Test
    fun regulatorClosesBelowTheLowestBreakdownTemperature() {
        assertEquals(0.0, valve(cold = regulatorClosedTemperature), 1e-12)
        assertTrue(regulatorClosedTemperature < lowestBreakdownTemperature)
    }

    @Test
    fun fullOutputsCloseTheValve() {
        assertEquals(1.0, valve(waterFill = 0.5, steamFill = 0.5), 1e-12)
        assertEquals(0.0, valve(waterFill = 1.0), 1e-12)
        assertEquals(0.0, valve(steamFill = 1.0), 1e-12)
    }

    @Test
    fun steamBelowTheBoilingPointDoesNotRunTheTurbine() {
        assertEquals(0.0, valve(hot = boilingPoint - condensationBand, cold = 280.0), 1e-12)
        assertEquals(1.0, valve(hot = boilingPoint, cold = 300.0), 1e-12)
    }

    @Test
    fun valveClosesAsTheColdSideCatchesUpWithTheSteam() {
        assertEquals(0.0, valve(hot = boilingPoint, cold = boilingPoint), 1e-12)
        assertTrue(valve(hot = boilingPoint, cold = boilingPoint - 15.0) in 0.4..0.6)
    }

    @Test
    fun coldSideSettlesWithoutOscillatingForAnyCooling() {
        val heatCapacity = 96250.0
        val timeStep = 0.05

        for (conductance in listOf(100.0, 300.0, 600.0, 1000.0, 2000.0, 5000.0)) {
            var temperature = ambient
            var rate = 0.0

            repeat(72000) {
                val opening = valve(cold = temperature, speed = referenceSpeed)
                val expansion = expand(hottestTankSteam, temperature, valve = opening)
                val rejected = if (expansion != null) {
                    expansion.thermalPower - expansion.mechanicalPower
                } else {
                    0.0
                }

                rate = (rejected - conductance * (temperature - ambient)) / heatCapacity

                assertTrue(rate >= -1e-9) {
                    "The cold side started cooling at $temperature K with $conductance W/K, which is an overshoot"
                }

                temperature += rate * timeStep
            }

            assertTrue(abs(rate) < 1e-3) {
                "The cold side has not settled at $temperature K with $conductance W/K"
            }

            assertTrue(temperature < lowestBreakdownTemperature) {
                "The cold side reached $temperature K with $conductance W/K"
            }
        }
    }

    @Test
    fun unloadedShaftSettlesBelowTheGovernorLimitWithoutOscillating() {
        val inertia = 10.0
        val viscousDamping = 0.05
        val coulombFriction = 0.5
        var speed = 0.0

        repeat(120000) {
            val opening = valve(cold = ambient, speed = speed)
            val expansion = expand(hottestTankSteam, ambient, speed = speed, valve = opening)

            val torque = if (expansion != null) {
                min(expansion.mechanicalPower / max(speed, 1e-6), maxTorque)
            } else {
                0.0
            }

            val acceleration = (torque - viscousDamping * speed - coulombFriction) / inertia

            assertTrue(acceleration >= -1e-9) {
                "The shaft started slowing down at $speed rad/s, which is an overshoot"
            }

            speed += acceleration * deltaTime
        }

        assertTrue(speed in governorOpenSpeed..governorClosedSpeed) {
            "The unloaded shaft settled at $speed rad/s"
        }
    }
}
