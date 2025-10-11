package org.eln2.mc.common.content

import net.minecraft.nbt.CompoundTag
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.Inductance
import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Resistance
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.mna.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.component.Inductor
import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.ageseries.libage.sim.electrical.mna.component.VoltageSource
import org.eln2.mc.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.MonopoleMap
import org.eln2.mc.data.Pole
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.evaluate
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList

/**
 * Model for the [DcMotorCell].
 *
 * @param inertia The inertia of the kinetic node.
 * @param damping The viscous damping of the kinetic node.
 * @param coreDependentLoss A loss dependent on the angular velocity (`W / rad^2`).
 * @param armatureResistance The resistance of the armature (resolves into an actual resistor).
 * @param armatureInductance The inductance of the armature (resolves into an actual inductor).
 * @param backEmfConstant The Back-EMF potential applied for the angular velocity of the node.
 * @param torqueConstant The torque applied for the current through the device.
 * @param relaxation Relaxation factor for the applied torque.
 * */
data class DcMotorOptions(
    val inertia: Quantity<Inertia>,
    val damping: Quantity<ViscousFriction>,
    val coreDependentLoss: Double, // W / (rad * rad). Fuck you, I am not making a quantity
    val armatureResistance: Quantity<Resistance>,
    val armatureInductance: Quantity<Inductance>,
    val backEmfConstant: Quantity<BackEmfConstant>,
    val torqueConstant: Quantity<MotorTorqueConstant>,
    val relaxation: Double = 0.5
)

/**
 * The electrical part of the DC motor. Made up of a resistor in series with an inductor in series with a potential source.
 * */
class DcMotorElectricalObject(cell: DcMotorCell) : ElectricalObject<DcMotorCell>(cell) {
    val armatureResistor = Resistor()
    val armatureInductor = Inductor()
    val voltageSource = VoltageSource()

    val resistorDisplay = armatureResistor.display()
    val inductorDisplay = armatureInductor.display()
    val voltageSourceDisplay = voltageSource.display()

    init {
        armatureResistor.resistance = !cell.options.armatureResistance
        armatureInductor.inductance = !cell.options.armatureInductance
        voltageSource.potential = 0.0
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(armatureResistor)
        circuit.add(armatureInductor)
        circuit.add(voltageSource)
    }

    /**
     * Offers the positive terminal of the [voltageSource] to plus and the negative terminal of the [armatureResistor] to minus.
     * */
    override fun offerPolar(remote: ElectricalObject<*>) = when(cell.electricalMap.evaluate(this.cell, remote.cell)) {
        Pole.Plus -> voltageSource.offerPositive()
        Pole.Minus -> armatureResistor.offerNegative()
    }

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)
        map.join(armatureResistor.offerPositive(), armatureInductor.offerNegative())
        map.join(armatureInductor.offerPositive(), voltageSource.offerNegative())
    }
}

class DcMotorKineticObject(cell: DcMotorCell) : KineticObject<DcMotorCell>(cell) {
    val node = KineticMono()

    val nodeDisplay = node.display()

    init {
        node.inertia = !cell.options.inertia
        node.viscousDamping = !cell.options.damping
    }

    override fun addNodes(builder: KineticNodeSet) {
        builder.add(node)
    }

    override fun offerExtension(remote: KineticObject<*>) = node.extension
}

class DcMotorCell(
    ci: CellCreateInfo,
    override val electricalMap: PoleMap,
    override val kineticMap: MonopoleMap,
    override val electricalSize: ElectricalSize?,
    override val kineticSize: KineticSize?,
    val options: DcMotorOptions,
    thermalDef: ThermalMassDefinition,
    leakage: ConnectionParameters = ConnectionParameters.DEFAULT
) : Cell(ci), SidedElectricalMapped<DcMotorCell>, SidedKineticMonoMapped<DcMotorCell> {
    @SimObject
    val kinetic = DcMotorKineticObject(this)

    @SimObject
    val electrical = DcMotorElectricalObject(this)

    @SimObject
    val thermal = ThermalWireObject(this, thermalDef(), leakage)

    /**
     * Last applied torque, used for relaxation.
     * */
    var lastAppliedTorque = 0.0
        private set

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::tickPre)
        subscribers.addPost(this::tickPost)
    }

    private fun tickPre(dt: Double, phase: SubscriberPhase) {
        // Back-EMF calculation.
        // The back-EMF opposes the applied potential.
        electrical.voltageSource.potential = !options.backEmfConstant * kinetic.node.omega
    }

    /**
     * Applies torque to the kinetic node based on the electrical results.
     * The results are obtained after the tick, and this method runs just then.
     * This means the behavior has a one-tick lag.
     * */
    private fun driveKineticNode() {
        // Raw torque for the current across the device:
        val rawTorque = electrical.armatureResistor.current * !options.torqueConstant

        // Apply relaxation to smooth out the torque:
        val torqueToApply = lastAppliedTorque * (1.0 - options.relaxation) + rawTorque * options.relaxation
        lastAppliedTorque = torqueToApply

        kinetic.node.externalTorque = torqueToApply
    }

    /**
     * Converts all losses to heat.
     * */
    private fun convertLosses(dt: Double) {
        var wasteHeat = 0.0

        wasteHeat += electrical.armatureResistor.power * dt
        wasteHeat += kinetic.node.deltaHeatFromFriction
        wasteHeat += options.coreDependentLoss * (kinetic.node.omega * kinetic.node.omega) * dt

        thermal.thermalBody.energy += Quantity(wasteHeat, JOULE)
    }

    private fun tickPost(dt: Double, phase: SubscriberPhase) {
        driveKineticNode()
        convertLosses(dt)
    }

    override fun saveCellData() = CompoundTag().also {
        it.putDouble(LAST_APPLIED_TORQUE, lastAppliedTorque)
    }

    override fun loadCellData(tag: CompoundTag) {
        lastAppliedTorque = tag.getDouble(LAST_APPLIED_TORQUE)
    }

    companion object {
        private const val LAST_APPLIED_TORQUE = "lastApplied"
    }
}

class DcMotorPart(ci: PartCreateInfo, cellProvider: RegistryObject<CellProvider<DcMotorCell>>) :
    CellPart<DcMotorCell>(ci, cellProvider.get()),
    ComponentDisplay
{
    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Back-EMF: ${cell.electrical.voltageSourceDisplay.potential}" }
        builder.debugInIDE { "Constraint impulse: ${cell.kinetic.node.extension.impulse}" }
        builder.quantity(cell.kinetic.nodeDisplay.kineticEnergy)
        builder.quantity(cell.kinetic.nodeDisplay.angularVelocity)
        builder.quantity(cell.kinetic.nodeDisplay.angularAcceleration)
        builder.quantity(cell.electrical.resistorDisplay.current)
        builder.quantityOutput(Quantity(cell.lastAppliedTorque, NEWTON_METER))
    }
}
