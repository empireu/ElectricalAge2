import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.WATT_PER_KELVIN
import org.ageseries.libage.data.WATT_PER_METER_KELVIN
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Simulator
import org.ageseries.libage.sim.ThermalMass
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

class ThermalBenchmark {
    private fun buildGrid(side: Int): Pair<Simulator, List<ThermalMass>> {
        val simulator = Simulator()
        val material = ChemicalElement.Copper.asMaterial.copy(
            thermalConductivity = Quantity(3500.0, WATT_PER_METER_KELVIN)
        )
        val leakage = ConnectionParameters.DEFAULT.copy(conductance = Quantity(0.01, WATT_PER_KELVIN))

        val bodies = List(side * side) { index ->
            ThermalMass(material, mass = Quantity(1.0, KILOGRAM)).also {
                it.temperature = Quantity(280.0 + index % 7, KELVIN)
                simulator.add(it)
                simulator.connect(it, Quantity(290.0, KELVIN), leakage)
            }
        }

        for (x in 0 until side) {
            for (y in 0 until side) {
                val body = bodies[x + y * side]

                if (x + 1 < side) {
                    val right = bodies[x + 1 + y * side]
                    simulator.connect(body, right, ConnectionParameters.DEFAULT)
                    simulator.connect(right, body, ConnectionParameters.DEFAULT)
                }

                if (y + 1 < side) {
                    val down = bodies[x + (y + 1) * side]
                    simulator.connect(body, down, ConnectionParameters.DEFAULT)
                    simulator.connect(down, body, ConnectionParameters.DEFAULT)
                }
            }
        }

        return Pair(simulator, bodies)
    }

    private fun allocatedBytesOfThisThread(): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

        return threads.getThreadAllocatedBytes(Thread.currentThread().id)
    }

    private fun measure(side: Int, warmupSteps: Int, measuredSteps: Int) {
        val (simulator, bodies) = buildGrid(side)

        repeat(warmupSteps) {
            simulator.step(0.01)
        }

        val allocatedBefore = allocatedBytesOfThisThread()
        val startTime = System.nanoTime()

        repeat(measuredSteps) {
            simulator.step(0.01)
        }

        val elapsedNanoseconds = System.nanoTime() - startTime
        val allocated = allocatedBytesOfThisThread() - allocatedBefore
        val checksum = bodies.sumOf { !it.temperature }

        println(
            "bodies=${bodies.size} connections=${simulator.connections.size} " +
                "microseconds/step=${elapsedNanoseconds / 1000.0 / measuredSteps} " +
                "bytes/step=${allocated / measuredSteps} " +
                "averageTemperature=${checksum / bodies.size}"
        )
    }

    @Disabled("Manual benchmark, remove this to measure the step cost.")
    @Test
    fun measureStepCost() {
        measure(10, 20000, 20000)
        measure(32, 5000, 5000)
        measure(100, 500, 500)
    }
}
