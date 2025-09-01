package org.eln2.mc.common.content

import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.frac
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.Locators
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.extensions.celestialPass
import org.eln2.mc.extensions.evaluateDiffuseIrradianceFactor
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

val LEVEL_INTENSITY = Quantity(1000.0, WATT_PER_METER2) // evaluate from level (eg for planets)

data class PhotovoltaicModel(
    val idealPotential: Quantity<Potential>,
    val b: Double,
    val p: Double,
    val d: Double,
    val efficiency: Double,
)

class PhotovoltaicGeneratorCell(
    ci: CellCreateInfo,
    val surfaceArea: Quantity<Area>,
    val model: PhotovoltaicModel,
    val normalSupplier: (PhotovoltaicGeneratorCell) -> Vector3d
) : Cell(ci) {
    @SimObject
    val generator = PowerVoltageSourceDiodeObject<PhotovoltaicGeneratorCell>(this, directionPoleMapPlanar())

    init {
        locator.requireLocator(Locators.BLOCK)
        ruleSet.withDirectionRulePlanar(Base6Direction3d.Front + Base6Direction3d.Back)
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        super.subscribe(subscribers)
        subscribers.addPre(::update)

    }

    fun irradianceFactor() = graph.level.evaluateDiffuseIrradianceFactor(
        normalSupplier(this),
        locator.requireLocator(Locators.BLOCK)
    )

    private fun update(dt: Double, phase: SubscriberPhase) {
        val irradiance = !LEVEL_INTENSITY * irradianceFactor()
        generator.powerSource.potentialMax = !model.idealPotential * ((irradiance / model.b).pow(model.p) / model.d)
        generator.powerSource.powerIdeal = irradiance * !surfaceArea * model.efficiency
    }
}

class PhotovoltaicPanelPart(ci: PartCreateInfo, provider: CellProvider<PhotovoltaicGeneratorCell>) : CellPart<PhotovoltaicGeneratorCell>(ci, provider), ComponentDisplay {
    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.potential(cell.generator.powerSource.potential)
        builder.power(cell.generator.powerSource.power)
        builder.current(cell.generator.powerSource.current)
        builder.translatePercent("Irradiance", cell.irradianceFactor())
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
