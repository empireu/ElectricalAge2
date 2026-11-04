import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.JOULE_PER_KILOGRAM_KELVIN
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.KILOGRAM_PER_METER3
import org.ageseries.libage.data.METER
import org.ageseries.libage.data.METER2
import org.ageseries.libage.data.OHM_METER
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT_PER_KELVIN
import org.ageseries.libage.data.WATT_PER_METER_KELVIN
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.EnvironmentConnection
import org.ageseries.libage.sim.MassConnection
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.STANDARD_TEMPERATURE
import org.ageseries.libage.sim.STEFAN_BOLTZMANN_CONSTANT
import org.ageseries.libage.sim.Simulator
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.emissionPower
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

class ThermalTests {
    private fun testMaterial(thermalConductivity: Double = 100.0, specificHeat: Double = 1000.0) = Material(
        "Test",
        Quantity(1e-8, OHM_METER),
        Quantity(thermalConductivity, WATT_PER_METER_KELVIN),
        Quantity(specificHeat, JOULE_PER_KILOGRAM_KELVIN),
        Quantity(1000.0, KILOGRAM_PER_METER3)
    )

    private fun testMass(
        temperature: Double,
        mass: Double = 1.0,
        material: Material = testMaterial()
    ) = ThermalMass(material, mass = Quantity(mass, KILOGRAM)).also {
        it.temperature = Quantity(temperature, KELVIN)
    }

    private fun lossless(
        conductance: Double = 1.0,
        contactConductance: Double = Double.POSITIVE_INFINITY,
        distance: Double = 1.0,
        area: Double = 1.0,
        contactPoint: Double = 0.5
    ) = ConnectionParameters(
        conductance = Quantity(conductance, WATT_PER_KELVIN),
        contactConductance = Quantity(contactConductance, WATT_PER_KELVIN),
        distance = Quantity(distance, METER),
        area = area,
        contactPoint = contactPoint,
        efficiency = 1.0
    )

    @Test
    fun newMassDefaultsToStandardTemperature() {
        val body = ThermalMass(testMaterial(), mass = Quantity(3.0, KILOGRAM))

        assertEquals(!STANDARD_TEMPERATURE, !body.temperature, 1e-9)
        assertEquals(!STANDARD_TEMPERATURE * 3.0 * 1000.0, !body.energy, 1e-6)
    }

    @Test
    fun temperatureAndEnergyRoundTrip() {
        val body = testMass(500.0, mass = 2.0)

        assertEquals(500.0 * 2.0 * 1000.0, !body.energy, 1e-6)
        assertEquals(500.0, !body.temperature, 1e-9)
        assertEquals(250.0, !body.temperatureAt(Quantity(500000.0, JOULE)), 1e-9)
    }

    @Test
    fun massConductanceIsSeriesResistanceOfBothHalvesAndContact() {
        val first = testMass(300.0, material = testMaterial(thermalConductivity = 100.0))
        val second = testMass(400.0, material = testMaterial(thermalConductivity = 50.0))

        val connection = MassConnection(
            first,
            second,
            lossless(contactConductance = 10.0, distance = 2.0, area = 0.5, contactPoint = 0.25)
        )

        val resistanceFirst = (0.25 * 2.0) / (100.0 * 0.5)
        val resistanceSecond = (0.75 * 2.0) / (50.0 * 0.5)
        val resistanceContact = 1.0 / 10.0

        assertEquals(1.0 / (resistanceFirst + resistanceSecond + resistanceContact), connection.conductance, 1e-9)
    }

    @Test
    fun massTransferOverOneStepIsConductanceTimesDifferenceTimesTime() {
        val cold = testMass(300.0, mass = 1000.0)
        val hot = testMass(400.0, mass = 1000.0)
        val simulator = Simulator()
        val connection = simulator.connect(cold, hot, lossless())
        val deltaTime = 0.001
        val coldEnergy = !cold.energy
        val hotEnergy = !hot.energy

        simulator.step(deltaTime)

        val expected = connection.conductance * 100.0 * deltaTime

        assertEquals(expected, !cold.energy - coldEnergy, 1e-6)
        assertEquals(-expected, !hot.energy - hotEnergy, 1e-6)
    }

    @Test
    fun zeroDistanceWithInfiniteContactSettlesAtTheWeightedAverage() {
        val first = testMass(300.0, mass = 1.0)
        val second = testMass(600.0, mass = 2.0)
        val simulator = Simulator()

        simulator.connect(first, second, lossless(distance = 0.0))

        repeat(50) {
            simulator.step(0.01)
        }

        assertEquals(500.0, !first.temperature, 1e-6)
        assertEquals(500.0, !second.temperature, 1e-6)
    }

    @Test
    fun equalBodiesWithInfiniteContactSettleInOneStep() {
        val first = testMass(300.0)
        val second = testMass(500.0)
        val simulator = Simulator()

        simulator.connect(first, second, lossless(distance = 0.0))
        simulator.step(0.01)

        assertEquals(400.0, !first.temperature, 1e-6)
        assertEquals(400.0, !second.temperature, 1e-6)
    }

    @Test
    fun stiffTransferDoesNotOvershootEquilibrium() {
        val first = testMass(300.0)
        val second = testMass(600.0)
        val simulator = Simulator()

        simulator.connect(first, second, lossless(contactConductance = 1e9, distance = 0.0))

        repeat(100) {
            simulator.step(1.0)

            assertTrue(!first.temperature <= !second.temperature + 1e-6)
        }

        assertEquals(450.0, !first.temperature, 1e-6)
        assertEquals(450.0, !second.temperature, 1e-6)
    }

    @Test
    fun losslessClosedSystemConservesEnergy() {
        val bodies = listOf(testMass(300.0), testMass(500.0, mass = 2.0), testMass(900.0, mass = 0.5))
        val simulator = Simulator()

        simulator.connect(bodies[0], bodies[1], lossless())
        simulator.connect(bodies[1], bodies[2], lossless())

        val initialEnergy = bodies.sumOf { !it.energy }

        repeat(10000) {
            simulator.step(0.01)
        }

        assertEquals(initialEnergy, bodies.sumOf { !it.energy }, 1e-3)
    }

    @Test
    fun defaultEfficiencyDestroysOnePercentOfEveryMassTransfer() {
        val cold = testMass(300.0, mass = 1000.0)
        val hot = testMass(400.0, mass = 1000.0)
        val simulator = Simulator()

        simulator.connect(cold, hot, ConnectionParameters.DEFAULT)

        val initialCold = !cold.energy
        val initialHot = !hot.energy

        simulator.step(0.01)

        val gained = !cold.energy - initialCold
        val lost = initialHot - !hot.energy

        assertTrue(lost > 0.0)
        assertEquals(0.99, gained / lost, 1e-9)
    }

    @Test
    fun defaultEfficiencyLeaksEnergyOutOfAClosedSystem() {
        val cold = testMass(300.0)
        val hot = testMass(500.0)
        val simulator = Simulator()

        simulator.connect(cold, hot, ConnectionParameters.DEFAULT)

        val initialEnergy = !cold.energy + !hot.energy

        repeat(100000) {
            simulator.step(0.01)
        }

        val temperatureDrop = (initialEnergy - (!cold.energy + !hot.energy)) / (2.0 * 1000.0)

        assertEquals(!cold.temperature, !hot.temperature, 1e-6)
        assertEquals(0.5, temperatureDrop, 0.01)
    }

    @Test
    fun twoEqualBodiesFollowTheAnalyticExponential() {
        val first = testMass(300.0)
        val second = testMass(500.0)
        val simulator = Simulator()
        val conductance = 10.0
        val capacity = 1000.0
        val duration = 50.0
        val deltaTime = 0.001

        simulator.connect(first, second, lossless(contactConductance = conductance, distance = 0.0))

        repeat((duration / deltaTime).toInt()) {
            simulator.step(deltaTime)
        }

        val expectedDifference = 200.0 * exp(-2.0 * conductance * duration / capacity)

        assertEquals(expectedDifference, !second.temperature - !first.temperature, expectedDifference * 1e-3)
    }

    @Test
    fun chainConvergesToLinearProfileBetweenLargeReservoirs() {
        val hot = testMass(400.0, mass = 1e9)
        val cold = testMass(300.0, mass = 1e9)
        val interior = List(3) { testMass(300.0) }
        val simulator = Simulator()
        val parameters = lossless()

        simulator.connect(hot, interior[0], parameters)
        simulator.connect(interior[0], interior[1], parameters)
        simulator.connect(interior[1], interior[2], parameters)
        simulator.connect(interior[2], cold, parameters)

        repeat(100000) {
            simulator.step(0.01)
        }

        assertEquals(375.0, !interior[0].temperature, 1e-3)
        assertEquals(350.0, !interior[1].temperature, 1e-3)
        assertEquals(325.0, !interior[2].temperature, 1e-3)
    }

    @Test
    fun connectingTheSamePairTwiceDoublesTheTransfer() {
        val once = Simulator()
        val onceCold = testMass(300.0, mass = 1000.0)
        val onceHot = testMass(400.0, mass = 1000.0)

        once.connect(onceCold, onceHot, lossless())

        val twice = Simulator()
        val twiceCold = testMass(300.0, mass = 1000.0)
        val twiceHot = testMass(400.0, mass = 1000.0)

        twice.connect(twiceCold, twiceHot, lossless())
        twice.connect(twiceHot, twiceCold, lossless())

        once.step(0.001)
        twice.step(0.001)

        val onceTransfer = !onceCold.energy - 300.0 * 1000.0 * 1000.0
        val twiceTransfer = !twiceCold.energy - 300.0 * 1000.0 * 1000.0

        assertEquals(2.0, twiceTransfer / onceTransfer, 1e-6)
    }

    @Test
    fun environmentCoolingFollowsTheAnalyticExponential() {
        val body = testMass(500.0)
        val simulator = Simulator()
        val ambient = 300.0
        val conductance = 10.0
        val duration = 50.0
        val deltaTime = 0.001

        simulator.connect(body, Quantity(ambient, KELVIN), lossless(conductance = conductance, distance = 0.0))

        repeat((duration / deltaTime).toInt()) {
            simulator.step(deltaTime)
        }

        val expected = ambient + 200.0 * exp(-conductance * duration / 1000.0)

        assertEquals(expected, !body.temperature, 0.05)
    }

    @Test
    fun environmentConductanceAddsInSeriesWithTheBody() {
        val body = testMass(400.0, material = testMaterial(thermalConductivity = 100.0))

        val connection = EnvironmentConnection(
            body,
            Quantity(300.0, KELVIN),
            lossless(conductance = 10.0, distance = 2.0, area = 0.5)
        )

        assertEquals(1.0 / (2.0 / (100.0 * 0.5) + 1.0 / 10.0), connection.conductance, 1e-9)
    }

    @Test
    fun environmentTemperatureCanChangeBetweenSteps() {
        val body = testMass(300.0, mass = 1000.0)
        val simulator = Simulator()
        val connection = EnvironmentConnection(
            body,
            Quantity(300.0, KELVIN),
            lossless(conductance = 10.0, distance = 0.0)
        )

        simulator.add(connection)
        simulator.step(0.01)

        assertEquals(300.0, !body.temperature, 1e-9)

        connection.temperature = Quantity(400.0, KELVIN)
        val before = !body.energy
        simulator.step(0.01)

        assertEquals(10.0 * 100.0 * 0.01, !body.energy - before, 1e-6)
    }

    @Test
    fun steadyHeatingReachesAmbientPlusPowerOverConductance() {
        val body = testMass(300.0)
        val simulator = Simulator()
        val ambient = 300.0
        val conductance = 5.0
        val power = 100.0
        val deltaTime = 0.01

        simulator.connect(body, Quantity(ambient, KELVIN), ConnectionParameters.DEFAULT.copy(
            conductance = Quantity(conductance, WATT_PER_KELVIN),
            distance = Quantity(0.0, METER)
        ))

        repeat(200000) {
            body.energy += Quantity(power * deltaTime, JOULE)
            simulator.step(deltaTime)
        }

        assertEquals(ambient + power / conductance, !body.temperature, 0.01)
    }

    @Test
    fun radiationFollowsStefanBoltzmann() {
        val body = testMass(1000.0, mass = 1e6)

        body.emissiveSurfaceArea = Quantity(2.0, METER2)
        body.emissivity = 0.5

        val simulator = Simulator()

        simulator.connect(body, Quantity(300.0, KELVIN), lossless(conductance = 0.0, distance = 0.0))

        val before = !body.energy
        val deltaTime = 0.001

        simulator.step(deltaTime)

        val expectedPower = 0.5 * STEFAN_BOLTZMANN_CONSTANT * 2.0 * (300.0.pow(4) - 1000.0.pow(4))

        assertEquals(expectedPower * deltaTime, !body.energy - before, 1e-3)
    }

    @Test
    fun emissionPowerMatchesStefanBoltzmann() {
        val power = Quantity(1000.0, KELVIN).emissionPower(2.0)

        assertEquals(1000.0.pow(4) * 2.0 * STEFAN_BOLTZMANN_CONSTANT, power, 1e-6)
    }

    @Test
    fun zeroConductivityBlocksConduction() {
        val cold = testMass(300.0, material = testMaterial(thermalConductivity = 0.0))
        val hot = testMass(400.0, material = testMaterial(thermalConductivity = 0.0))
        val simulator = Simulator()

        simulator.connect(cold, hot, lossless())
        simulator.step(0.01)

        assertEquals(300.0, !cold.temperature, 0.0)
        assertEquals(400.0, !hot.temperature, 0.0)
    }

    @Test
    fun invalidTimeStepsTransferNothing() {
        val cold = testMass(300.0)
        val hot = testMass(400.0)
        val simulator = Simulator()

        simulator.connect(cold, hot, lossless())

        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { deltaTime ->
            simulator.step(deltaTime)

            assertEquals(300.0, !cold.temperature, 0.0)
        }
    }

    @Test
    fun invalidGeometryHasNoConductance() {
        val first = testMass(300.0)
        val second = testMass(400.0)

        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { area ->
            assertEquals(0.0, MassConnection(first, second, lossless(area = area)).conductance, 0.0)
            assertEquals(0.0, EnvironmentConnection(first, Quantity(300.0, KELVIN), lossless(area = area)).conductance, 0.0)
        }

        assertEquals(0.0, MassConnection(first, second, lossless(distance = Double.POSITIVE_INFINITY)).conductance, 0.0)
    }

    @Test
    fun zeroMassBodyDoesNotProduceNaN() {
        val empty = ThermalMass(testMaterial(), energy = Quantity(0.0, JOULE), mass = Quantity(0.0, KILOGRAM))
        val hot = testMass(400.0)
        val simulator = Simulator()

        simulator.connect(empty, hot, lossless())
        simulator.connect(empty, Quantity(300.0, KELVIN), lossless())
        simulator.step(0.01)

        assertFalse((!hot.energy).isNaN())
        assertFalse((!empty.energy).isNaN())
    }

    @Test
    fun nonFiniteTemperatureDoesNotSpreadToNeighbors() {
        val broken = testMass(300.0)
        val healthy = testMass(300.0)
        val simulator = Simulator()

        simulator.connect(broken, healthy, lossless())
        broken.energy = Quantity(Double.NaN, JOULE)
        simulator.step(0.01)

        assertEquals(300.0, !healthy.temperature, 0.0)
    }

    @Test
    fun removingMassRemovesItsConnections() {
        val first = testMass(300.0)
        val second = testMass(400.0)
        val third = testMass(500.0)
        val simulator = Simulator()

        simulator.connect(first, second, lossless())
        val kept = simulator.connect(second, third, lossless())

        simulator.remove(first)

        assertFalse(simulator.masses.contains(first))
        assertEquals(setOf(kept), simulator.connections.toSet())

        val firstEnergy = !first.energy

        simulator.step(0.01)

        assertEquals(firstEnergy, !first.energy, 0.0)
    }

    @Test
    fun removingConnectionKeepsMasses() {
        val first = testMass(300.0)
        val second = testMass(400.0)
        val simulator = Simulator()
        val connection = simulator.connect(first, second, lossless())

        simulator.step(0.01)
        simulator.remove(connection)

        assertTrue(simulator.masses.containsAll(listOf(first, second)))
        assertTrue(simulator.connections.isEmpty())

        val firstEnergy = !first.energy

        simulator.step(0.01)

        assertEquals(firstEnergy, !first.energy, 0.0)
    }

    @Test
    fun connectionsAddedAfterSteppingTakeEffect() {
        val first = testMass(300.0, mass = 1000.0)
        val second = testMass(400.0, mass = 1000.0)
        val simulator = Simulator()

        simulator.add(first)
        simulator.add(second)
        simulator.step(0.01)

        assertEquals(300.0, !first.temperature, 0.0)

        simulator.connect(first, second, lossless())
        simulator.step(0.01)

        assertTrue(!first.temperature > 300.0)
    }

    @Test
    fun changingMassAndMaterialBetweenStepsIsPickedUp() {
        val body = testMass(400.0, mass = 1.0)
        val neighbor = testMass(300.0, mass = 1.0)
        val simulator = Simulator()

        simulator.connect(body, neighbor, lossless(contactConductance = 10.0, distance = 0.0))
        simulator.step(0.01)

        val temperature = !body.temperature
        val energy = !body.energy

        body.mass = Quantity(2.0, KILOGRAM)
        body.energy = Quantity(energy * 2.0)

        assertEquals(temperature, !body.temperature, 1e-9)

        val neighborTemperature = !neighbor.temperature

        simulator.step(0.01)

        val expectedTransfer = 10.0 * (temperature - neighborTemperature) * 0.01

        assertEquals(expectedTransfer / 2000.0, temperature - !body.temperature, 1e-9)
        assertEquals(expectedTransfer / 1000.0, !neighbor.temperature - neighborTemperature, 1e-9)

        body.material = testMaterial(specificHeat = 500.0)

        assertEquals(temperature * 2.0, !body.temperature, 1.0)

        val before = !body.temperature

        simulator.step(0.01)

        assertTrue(!body.temperature < before)
        assertFalse((!body.temperature).isNaN())
    }

    private fun runHub(neighborCount: Int, steps: Int): List<Double> {
        val hub = testMass(400.0, mass = 0.001)
        val neighbors = List(neighborCount) { testMass(300.0, mass = 1000.0) }
        val simulator = Simulator()

        neighbors.forEach {
            simulator.connect(hub, it, lossless(contactConductance = 1e9, distance = 0.0))
        }

        val history = ArrayList<Double>()

        repeat(steps) {
            simulator.step(0.01)
            history.add(!hub.temperature)
        }

        return history
    }

    @Test
    fun lightHubOnStiffConnectionsSettlesWithoutOvershoot() {
        listOf(1, 2, 3, 6, 50).forEach { neighborCount ->
            val history = runHub(neighborCount, 50)

            assertTrue(history.all { it.isFinite() && it >= 300.0 - 1e-6 && it <= 400.0 + 1e-6 }) {
                "The hub with $neighborCount neighbors overshot: ${history.take(6)}"
            }

            assertEquals(300.0, history.last(), 0.01)
        }
    }

    @Test
    fun stiffChainOfEqualBodiesDoesNotRing() {
        val bodies = List(9) { index -> testMass(if (index % 2 == 0) 400.0 else 300.0) }
        val simulator = Simulator()

        for (index in 1 until bodies.size) {
            simulator.connect(bodies[index - 1], bodies[index], lossless(contactConductance = 1e9, distance = 0.0))
        }

        var previousSpread = Double.POSITIVE_INFINITY

        repeat(200) {
            simulator.step(0.01)

            val spread = bodies.maxOf { !it.temperature } - bodies.minOf { !it.temperature }

            assertTrue(spread <= previousSpread + 1e-9) {
                "The spread of temperatures grew from $previousSpread to $spread"
            }

            previousSpread = spread
        }

        assertTrue(previousSpread < 1.0)
    }

    @Test
    fun conductancePastTheForwardEulerLimitStaysBounded() {
        val hub = testMass(400.0)
        val neighbors = List(4) { testMass(300.0, mass = 1e6) }
        val simulator = Simulator()
        val capacity = 1000.0
        val deltaTime = 0.01
        val conductance = 2.5 * capacity / (4.0 * deltaTime)

        neighbors.forEach {
            simulator.connect(hub, it, lossless(contactConductance = conductance, distance = 0.0))
        }

        repeat(50) {
            simulator.step(deltaTime)

            assertTrue(!hub.temperature in 300.0 - 1e-3..400.0 + 1e-3)
        }

        assertEquals(300.0, !hub.temperature, 1e-1)
    }

    @Test
    fun randomStiffNetworksObeyTheMaximumPrincipleAndConserveEnergy() {
        repeat(200) { seed ->
            val random = Random(seed)
            val bodyCount = random.nextInt(2, 30)
            val bodies = List(bodyCount) {
                testMass(random.nextDouble(200.0, 2000.0), mass = 10.0.pow(random.nextDouble(-4.0, 4.0)))
            }

            val simulator = Simulator()

            repeat(random.nextInt(bodyCount, bodyCount * 4)) {
                val first = random.nextInt(bodyCount)
                val second = random.nextInt(bodyCount)

                if (first != second) {
                    val contactConductance = 10.0.pow(random.nextDouble(-2.0, 9.0))

                    simulator.connect(bodies[first], bodies[second], lossless(contactConductance = contactConductance, distance = 0.0))
                }
            }

            val initialEnergy = bodies.sumOf { !it.energy }
            var highest = bodies.maxOf { !it.temperature }
            var lowest = bodies.minOf { !it.temperature }

            repeat(200) {
                simulator.step(0.01)

                val newHighest = bodies.maxOf { !it.temperature }
                val newLowest = bodies.minOf { !it.temperature }

                assertTrue(newHighest <= highest + 1e-6) {
                    "Seed $seed: the highest temperature grew from $highest to $newHighest"
                }

                assertTrue(newLowest >= lowest - 1e-6) {
                    "Seed $seed: the lowest temperature fell from $lowest to $newLowest"
                }

                highest = newHighest
                lowest = newLowest
            }

            val energyError = abs(bodies.sumOf { !it.energy } - initialEnergy)

            assertTrue(energyError <= abs(initialEnergy) * 1e-9) {
                "Seed $seed: energy changed by $energyError"
            }
        }
    }
}
