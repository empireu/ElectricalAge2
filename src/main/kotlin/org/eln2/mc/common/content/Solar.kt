package org.eln2.mc.common.content

import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.frac
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.Pole
import org.ageseries.libage.sim.electrical.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.ElectricalPin
import org.ageseries.libage.sim.electrical.LinearDiode
import org.ageseries.libage.sim.electrical.PowerSource
import org.ageseries.libage.sim.electrical.Resistor
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.Locators
import org.eln2.mc.data.PoleMap
import org.eln2.mc.extensions.celestialPass
import org.eln2.mc.extensions.evaluateDiffuseIrradianceFactor
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.offerExternal
import org.eln2.mc.offerInternal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

val LEVEL_INTENSITY = Quantity(1000.0, WATT_PER_METER2) // evaluate from level (eg for planets)

/**
 * @param openCircuitPotential The open-circuit potential of the panel (when load is 0).
 * @param efficiency The efficiency factor. The factor scales the incoming solar power (defined per-level, and dependent on the time of day).
 * @param surfaceArea The surface area of the photovoltaic cell.
 * @param seriesResistance The resistance of the fixed resistor added in series with the model.
 * @param bypassResistance The resistance of the bypass resistor in forward-bias.
 * @param parallelResistance The resistance of the bypass resistor in backward-bias.
 * */
data class PhotovoltaicModel(
    val openCircuitPotential: Quantity<Potential>,
    val efficiency: Double,
    val surfaceArea: Quantity<Area>,
    val seriesResistance: Quantity<Resistance>,
    val bypassResistance: Quantity<Resistance>,
    val parallelResistance: Quantity<Resistance>
)

class PhotovoltaicGeneratorObject(cell: PhotovoltaicGeneratorCell) : ElectricalObject<PhotovoltaicGeneratorCell>(cell) {
    val powerSource = PowerSource()
    val bypassDiode = LinearDiode()
    val seriesResistor = Resistor()

    init {
        powerSource.maxPotential = !cell.model.openCircuitPotential
        powerSource.setStabilizingResistance(
            !cell.model.openCircuitPotential,
            !LEVEL_INTENSITY * !cell.model.surfaceArea * cell.model.efficiency
        )

        bypassDiode.forwardResistance = !cell.model.bypassResistance
        bypassDiode.reverseResistance = !cell.model.parallelResistance

        seriesResistor.resistance = !cell.model.seriesResistance
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(powerSource, bypassDiode, seriesResistor)
    }

    override fun offerPolar(remote: ElectricalObject<*>) = when(cell.electricalMap.evaluateOrNull(cell, remote.cell)) {
        Pole.Positive -> seriesResistor.positive
        Pole.Negative -> powerSource.negative
        null -> null
    }

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)

        // Build bypass:
        map.join(powerSource.positive, bypassDiode.negative)
        map.join(powerSource.negative, bypassDiode.positive)

        // Add series resistance:
        map.join(powerSource.positive, seriesResistor.negative)
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::tick)
    }

    private fun tick(dt: Double, phase: SubscriberPhase) {
        val irradiance = cell.irradianceFactor()

        /**
         * Available power for conversion:
         * */
        powerSource.targetPower = !LEVEL_INTENSITY * !cell.model.surfaceArea * irradiance * cell.model.efficiency
    }
}

class PhotovoltaicGeneratorCell(
    ci: CellCreateInfo,
    override val electricalMap: PoleMap,
    val model: PhotovoltaicModel,
    val normalSupplier: (PhotovoltaicGeneratorCell) -> Vector3d
) : Cell(ci), SidedElectricalMapped<PhotovoltaicGeneratorCell> {
    @SimObject
    val generator = PhotovoltaicGeneratorObject(this)

    override val electricalSize: ElectricalSize
        get() = ElectricalSize.Standard

    init {
        locator.requireLocator(Locators.BLOCK)
    }

    fun irradianceFactor() = graph.level.evaluateDiffuseIrradianceFactor(
        normalSupplier(this),
        locator.requireLocator(Locators.BLOCK)
    )
}

class PhotovoltaicPanelPart(ci: PartCreateInfo, provider: CellProvider<PhotovoltaicGeneratorCell>) : CellPart<PhotovoltaicGeneratorCell>(ci, provider), ComponentDisplay {
    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.translatePercent("Irradiance", cell.irradianceFactor())
        builder.quantity(cell.generator.powerSource.readouts.potential)
        builder.quantity(cell.generator.powerSource.readouts.current)
        builder.quantity(cell.generator.powerSource.readouts.power)
    }
}

fun solarScan(normal: Vector3d) : Double {
    var sum = 0.0

    repeat(12000) {
        val a = frac(it / 24000.0 - 0.25)
        val b = 0.5 - cos(a * PI) / 2.0
        val c = (a * 2.0 + b) / 3.0
        val d = celestialPass(2.0 * PI * c)

        sum += !Vector3d(d.re, d.im, 0.0) cosAngle normal
    }

    return 1.0 / sum
}
