import org.ageseries.libage.data.CELSIUS
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SteamTurbineTests {
    private val deltaTime = 0.01
    private val maxFlowRate = 52.0
    private val etaFactor = 0.5
    private val maxPower = 50000.0

    private val properties = SteamProperties(2.0, 4.186, 2260.0, 373.15)

    private val model = SteamTurbineGeneratorModel(
        etaFactor = etaFactor,
        maxFlowRate = maxFlowRate,
        maxTorque = Quantity(2400.0, NEWTON_METER),
        maxPower = Quantity(maxPower, WATT),
        coldSideMass = Quantity(500.0, KILOGRAM),
        coldSideMaterial = ChemicalElement.Copper.asMaterial,
        coldSideLeakage = ConnectionParameters.DEFAULT,
        referenceAngularVelocity = Quantity(104.72, RADIAN_PER_SECOND),
        breakdownAngularVelocity = Quantity(500.0, RADIAN_PER_SECOND),
        breakdownTemperature = Quantity(200.0, CELSIUS),
        overcapacityThreshold = 1.5
    )

    private fun expand(hot: Double, cold: Double, steam: Double = 1e6) =
        SteamExpansion.calculate(model, properties, steam, hot, cold, deltaTime)

    private fun expanded(hot: Double, cold: Double, steam: Double = 1e6) =
        expand(hot, cold, steam) ?: error("Expected the steam at $hot K to expand down to $cold K")

    private fun steamEnthalpy(temperature: Double) = 4.186 * 373.15 + 2260.0 + 2.0 * (temperature - 373.15)

    @Test
    fun thermalPowerIsTheFlowTimesTheDropFromSteamToWater() {
        val expansion = expanded(500.0, 300.0)
        val drop = steamEnthalpy(500.0) - 4.186 * 300.0

        assertEquals(maxFlowRate, expansion.flow, 1e-9)
        assertEquals(maxFlowRate * drop, expansion.thermalPower, 1e-6)
        assertTrue(expansion.condenses)
    }

    @Test
    fun mechanicalPowerFollowsTheScaledCarnotEfficiency() {
        val expansion = expanded(500.0, 300.0)
        val efficiency = (1.0 - 300.0 / 500.0) * etaFactor

        assertEquals(efficiency * expansion.thermalPower, expansion.mechanicalPower, 1e-6)
    }

    @Test
    fun mechanicalPowerStopsAtTheRating() {
        val expansion = expanded(3000.0, 300.0)

        assertEquals(maxPower, expansion.mechanicalPower, 1e-9)
        assertTrue(expansion.thermalPower > maxPower)
    }

    @Test
    fun mechanicalPowerNeverExceedsTheThermalPower() {
        for (hot in 310..1500 step 10) {
            for (cold in 280 until hot step 10) {
                val expansion = expanded(hot.toDouble(), cold.toDouble())

                assertTrue(expansion.mechanicalPower <= expansion.thermalPower) {
                    "Hot $hot K, cold $cold K: ${expansion.mechanicalPower} W from ${expansion.thermalPower} W"
                }
            }
        }
    }

    @Test
    fun flowIsLimitedByTheSteamThatIsLeft() {
        val available = maxFlowRate * deltaTime * 0.25
        val expansion = expanded(500.0, 300.0, available)

        assertEquals(available, expansion.flow * deltaTime, 1e-9)
    }

    @Test
    fun exhaustStaysSteamAboveTheBoilingPoint() {
        val expansion = expanded(800.0, 400.0)

        assertFalse(expansion.condenses)
        assertEquals(maxFlowRate * 2.0 * 400.0, expansion.thermalPower, 1e-6)
    }

    @Test
    fun condensingReleasesFarMoreHeatThanExpandingAlone() {
        val condensing = expanded(800.0, 373.0)
        val expanding = expanded(800.0, 373.3)

        assertTrue(condensing.thermalPower > 3.0 * expanding.thermalPower)
    }

    @Test
    fun nothingExpandsWithoutSteamOrATemperatureDifference() {
        assertNull(expand(500.0, 300.0, 0.0))
        assertNull(expand(300.0, 300.0))
        assertNull(expand(300.0, 500.0))
    }
}
