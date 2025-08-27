package org.eln2.mc.common.content

import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.electrical.mna.component.PowerVoltageSource
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.Locators
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.extensions.evaluateDiffuseIrradianceFactor
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d
import kotlin.math.pow

val LEVEL_INTENSITY = Quantity(1000.0, WATT_PER_METER2) // evaluate from level (eg for planets)

data class PhotovoltaicModel(
    val idealPotential: Quantity<Potential>,
    val b: Double,
    val p: Double,
    val d: Double,
    val efficiency: Double,
)

class PhotovoltaicBehavior(
    val cell: Cell,
    val source: PolarTermObject<*, PowerVoltageSource>,
    val surfaceArea: Quantity<Area>,
    val model: PhotovoltaicModel,
    val normalSupplier: () -> Vector3d
) : CellBehavior {
    init {
        cell.locator.requireLocator(Locators.BLOCK)
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(::update)
    }

    fun irradianceFactor() = cell.graph.level.evaluateDiffuseIrradianceFactor(
        normalSupplier(),
        cell.locator.requireLocator(Locators.BLOCK)
    )

    private fun update(dt: Double, phase: SubscriberPhase) {
        val irradiance = !LEVEL_INTENSITY * irradianceFactor()

        source.term.potentialMax = !model.idealPotential * ((irradiance / model.b).pow(model.p) / model.d)
        source.term.powerIdeal = irradiance * !surfaceArea * model.efficiency
    }
}

class PhotovoltaicGeneratorCell(
    ci: CellCreateInfo,
    surfaceArea: Quantity<Area>,
    model: PhotovoltaicModel,
    val normalSupplier: (PhotovoltaicGeneratorCell) -> Vector3d
) : Cell(ci) {
    val normal get() = normalSupplier(this)

    @SimObject
    val generator = PolarTermObject(this, directionPoleMapPlanar(), PowerVoltageSource())

    @Behavior
    val photovoltaic = PhotovoltaicBehavior(this, generator, surfaceArea, model, this::normal)

    init {
        ruleSet.withDirectionRulePlanar(Base6Direction3d.Front + Base6Direction3d.Back)
    }
}

class PhotovoltaicPanelPart(ci: PartCreateInfo, provider: CellProvider<PhotovoltaicGeneratorCell>) : CellPart<PhotovoltaicGeneratorCell>(ci, provider), ComponentDisplay {
    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.potential(cell.generator.term.potential)
        builder.power(cell.generator.term.power)
        builder.current(cell.generator.term.current)
        builder.translatePercent("Irradiance", cell.photovoltaic.irradianceFactor())
    }
}
