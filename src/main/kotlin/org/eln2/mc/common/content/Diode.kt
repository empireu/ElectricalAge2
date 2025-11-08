package org.eln2.mc.common.content

import net.minecraft.nbt.CompoundTag
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Pole
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.LinearDiode
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.PoleMap
import org.eln2.mc.extensions.loadNbt
import org.eln2.mc.extensions.saveNbt
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList

/**
 * Model for the ideal, zero-potential switching diode.
 * @param forwardResistance The resistance when the diode is in forward bias.
 * @param reverseResistance The resistance when the diode is in backward bias.
 * @param thermalDef The definition of the thermal body that will be heated with the diode's dissipated power.
 * */
data class DiodeOptions(
    val forwardResistance: Quantity<Resistance>,
    val reverseResistance: Quantity<Resistance>,
    val thermalDef: ThermalMassDefinition,
    val thermalBreakdown: Quantity<Temperature>,
    val dielectricBreakdown: Quantity<Potential>
)

class DiodeObject(cell: DiodeCell) : ElectricalObject<DiodeCell>(cell), PersistentObject {
    val diode = LinearDiode()

    init {
        diode.forwardResistance = !cell.model.forwardResistance
        diode.reverseResistance = !cell.model.reverseResistance
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(diode)
    }

    override fun offerPolar(remote: ElectricalObject<*>) = when(cell.electricalMap.evaluateOrNull(cell, remote.cell)) {
        Pole.Positive -> diode.positive
        Pole.Negative -> diode.negative
        null -> null
    }

    override fun saveObjectNbt() = diode.saveNbt()

    override fun loadObjectNbt(tag: CompoundTag) { diode.loadNbt(tag) }
}

class DiodeCell(
    ci: CellCreateInfo,
    override val electricalMap: PoleMap,
    val model: DiodeOptions,
    val leakage: ConnectionParameters
) : Cell(ci), SidedElectricalMapped<DiodeCell> {
    override val electricalSize: ElectricalSize
        get() = ElectricalSize.Standard

    @SimObject
    val thermal = ThermalWireObject(this, model.thermalDef(), leakage)

    @SimObject
    val electrical = DiodeObject(this)

    @Behavior
    val thermalBreakdown = ThermalBreakdownBehavior.create(model.thermalBreakdown, this, thermal.thermalBody::temperature)

    @Behavior
    val dielectricBreakdown = DielectricBreakdownBehavior.create(this).also {
        it.addPort(electrical.diode, !model.dielectricBreakdown, !model.dielectricBreakdown)
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPost(this::tickPost)
    }

    private fun tickPost(dt: Double, phase: SubscriberPhase) {
        thermal.thermalBody.energy += Quantity(electrical.diode.power * dt, JOULE)
    }
}

class DiodePart(ci: PartCreateInfo) : CellPart<DiodeCell>(ci, Content.DIODE_CELL.get()), ComponentDisplay {
    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.translateBoolean("diode_conducting", cell.electrical.diode.isConducting)
        builder.quantity(cell.thermal.thermalBody.temperature)
        builder.quantity(cell.electrical.diode.readouts.potential)
        builder.quantity(cell.electrical.diode.readouts.current)
        builder.quantity(cell.electrical.diode.readouts.power)
    }
}
