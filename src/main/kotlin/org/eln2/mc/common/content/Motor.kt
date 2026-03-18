package org.eln2.mc.common.content

import kotlinx.serialization.Serializable
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.FramerateIndependentSmoother1d
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Pole
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.*
import org.ageseries.libage.sim.kinetic.KineticMono
import org.ageseries.libage.sim.kinetic.KineticNodeSet
import org.eln2.mc.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.modules.Eln2Kinetic
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.TickablePart
import org.eln2.mc.common.sounds.foundation.SimpleLoopingMachineSoundInstance
import org.eln2.mc.common.sounds.foundation.SimpleLoopingPartSoundInstance
import org.eln2.mc.common.sounds.foundation.SoundInfo
import org.eln2.mc.common.sounds.foundation.SoundInstanceTickEvent
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask
import kotlin.math.abs

/**
 * Model for the [DcMotorCell].
 *
 * @param coreDependentLoss A loss dependent on the angular velocity (`W / rad^2`).
 * @param armatureResistance The resistance of the armature (resolves into an actual resistor).
 * @param armatureInductance The inductance of the armature (resolves into an actual inductor).
 * @param backEmfConstant The Back-EMF potential applied for the angular velocity of the node.
 * @param torqueConstant The torque applied for the current through the device.
 * @param relaxation Relaxation factor for the applied torque.
 * */
data class DcMotorOptions(
    val frictionNodeDescription: FrictionNodeDescription,
    val coreDependentLoss: Double, // W / (rad * rad). Maybe add quantity?
    val armatureResistance: Quantity<Resistance>,
    val armatureInductance: Quantity<Inductance>,
    val backEmfConstant: Quantity<MotorBackEmfConstant>,
    val torqueConstant: Quantity<MotorTorqueConstant>,
    val breakdownAngularVelocity: Quantity<AngularVelocity>,
    val breakdownPotential: Quantity<Potential>,
    val breakdownTemperature: Quantity<Temperature>,
    val relaxation: Double = 0.5
) {
    companion object {
        /**
         * Creates the motor parameters from simple values, using some assumptions.
         * @param ratedPotential The potential we expect the motor to run at.
         * @param ratedPower The power we expect to be able to draw at the nominal potential and speed.
         * @param ratedSpeed The speed the motor hits unloaded at the [ratedPotential].
         * @param efficiency Number describing the approximate electrical energy to mechanical energy conversion efficiency.
         * @param spinUpTime Used to calculate inertia. The motor reaches its nominal speed in approximately this time period, at the rated potential.
         * */
        fun create(
            ratedPotential: Quantity<Potential>,
            ratedPower: Quantity<Power>,
            ratedSpeed: Quantity<AngularVelocity>,
            efficiency: Double,
            spinUpTime: Quantity<Time>
        ): DcMotorOptions {
            /**
             * Calculates input power and current at the rated load:
             * */
            val pIn = !ratedPower / efficiency
            val iRated = pIn / !ratedPotential

            /**
             * Calculates internal resistance based on the resistive loss:
             * */
            val loss = pIn - !ratedPower
            val r = loss / (iRated * iRated)

            /**
             * Calculates motor constants:
             * */
            val emf = !ratedPotential - (iRated * r)
            val kE = emf / !ratedSpeed
            val torque = !ratedPower / !ratedSpeed
            val kT = torque / iRated

            /**
             * Calculates an approximate inertia:
             * */
            val inertia = torque / (!ratedSpeed / spinUpTime.value)

            return DcMotorOptions(
                FrictionNodeDescription(
                    Quantity(inertia, KILOGRAM_METER2),
                    NodeFrictionDescription(
                        0.01,
                        Quantity(0.01, NEWTON_METER),
                        Quantity(0.1, NEWTON_METER)
                    )
                ),
                0.1,
                Quantity(r, OHM),
                Quantity(10.0, MILLI * HENRY),
                Quantity(kE, VOLT_PER_RADIAN_PER_SECOND),
                Quantity(kT, NEWTON_METER_PER_AMPERE),
                ratedSpeed * 4.0,
                ratedPotential * 3.0,
                Quantity(120.0, CELSIUS)
            )
        }
    }
}

/**
 * The electrical part of the DC motor. Made up of a resistor in series with an inductor in series with a potential source.
 * */
class DcMotorElectricalObject(cell: DcMotorCell) : ElectricalObject<DcMotorCell>(cell) {
    val armatureResistor = Resistor()
    val armatureInductor = Inductor()
    val voltageSource = PotentialSource()

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
        Pole.Positive -> voltageSource.positive
        Pole.Negative -> armatureResistor.negative
    }

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)
        map.join(armatureResistor.positive, armatureInductor.negative)
        map.join(armatureInductor.positive, voltageSource.negative)
    }
}

class DcMotorKineticObject(cell: DcMotorCell) : KineticObject<DcMotorCell>(cell), PersistentObject {
    val node = KineticMono()

    init {
        cell.options.frictionNodeDescription.applyTo(node)
    }

    override fun addNodes(builder: KineticNodeSet) {
        builder.add(node)
    }

    override fun offerExtension(remote: KineticObject<*>) = node.extension

    override fun saveObjectNbt() = CompoundTag().also {
        it.putDouble(ANGLE, node.angle)
        it.putDouble(OMEGA, node.angularVelocity)
    }

    override fun loadObjectNbt(tag: CompoundTag) {
        node.setExternalAngle(tag.getDouble(ANGLE))
        node.angularVelocity = tag.getDouble(OMEGA)
    }

    companion object {
        private const val ANGLE = "angle"
        private const val OMEGA = "omega"
    }
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

    @Behavior
    val kineticBreakdown = KineticBreakdownBehavior.create(options.breakdownAngularVelocity, this) {
        kinetic.node.angularVelocity
    }

    @Behavior
    val dielectricBreakdown = DielectricBreakdownBehavior.create(this).also {
        it.addPort(
            electrical.armatureResistor,
            !options.breakdownPotential,
            !options.breakdownPotential
        )
    }

    @Behavior
    val thermalBreakdown = ThermalBreakdownBehavior.create(options.breakdownTemperature, this) {
        thermal.thermalBody.temperature
    }

    @Replicator
    fun replicator(target: DcMotorPart) = MotorReplicator(5, target, kinetic, electrical)

    /**
     * Last applied torque, used for relaxation.
     * */
    var lastAppliedTorque = 0.0
        private set

    override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        subscribers.addPre(this::tickPre)
        subscribers.addPost(this::tickPost)
    }

    private fun tickPre(dt: Double, phase: SimulationPhase) {
        // Back-EMF calculation.
        // The back-EMF opposes the applied potential.
        electrical.voltageSource.potential = !options.backEmfConstant * kinetic.node.angularVelocity
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
        // P.S. I noticed the relaxation is applied backwards. Maybe fix?
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
        wasteHeat += options.coreDependentLoss * (kinetic.node.angularVelocity * kinetic.node.angularVelocity) * dt

        thermal.thermalBody.energy += Quantity(wasteHeat, JOULE)
    }

    private fun tickPost(dt: Double, phase: SimulationPhase) {
        driveKineticNode()
        convertLosses(dt)
    }

    override fun saveCellData() = CompoundTag().also {
        it.putDouble(LAST_APPLIED_TORQUE, lastAppliedTorque)
    }

    override fun loadCellData(tag: CompoundTag) {
        lastAppliedTorque = tag.getDouble(LAST_APPLIED_TORQUE)
    }

    class MotorReplicator(
        val interval: Int,
        val part: DcMotorPart,
        val kinetic: DcMotorKineticObject,
        val electrical: DcMotorElectricalObject
    ) : ReplicatorBehavior {
        var omegaEps = 1e-4
        var powerEps = 0.1
        private var replicatedAngularVelocity = 0.0
        private var replicatedPower = 0.0

        override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
            subscribers.addSubscriber(
                SubscriberOptions(interval, SimulationPhase.Post),
                this::scan
            )
        }

        private fun scan(dt: Double, subscriberPhase: SimulationPhase) {
            val targetAngularVelocity = kinetic.node.angularVelocity
            val targetPower = electrical.voltageSource.power

            if(!targetAngularVelocity.approxEq(replicatedAngularVelocity, omegaEps) || !targetPower.approxEq(replicatedPower, powerEps)) {
                replicatedAngularVelocity = targetAngularVelocity
                replicatedPower = targetPower
                part.replicate(targetAngularVelocity, targetPower)
            }
        }
    }

    companion object {
        private const val LAST_APPLIED_TORQUE = "lastApplied"
    }
}

data class DcMotorSoundOptions(
    val nominalSpeed: Quantity<AngularVelocity>,
    val nominalPower: Quantity<Power>
)

class DcMotorPart(
    ci: PartCreateInfo,
    val soundOptions: DcMotorSoundOptions,
    cellProvider: RegistryObject<CellProvider<DcMotorCell>>,
    pipelikeMask: Base6Direction3dMask
) :
    CellPart<DcMotorCell>(ci, cellProvider.get(), pipelikeMask),
    TickablePart,
    ComponentDisplay
{
    private val renderState = if(placement.level.isClientSide) RenderState() else null

    private class RenderState {
        var targetAngularVelocity = 0.0
        var targetPower = 0.0

        val angularVelocityInterpolator = FramerateIndependentSmoother1d(0.2)
        val powerInterpolator = FramerateIndependentSmoother1d(0.25)

        var kineticSound: SimpleLoopingPartSoundInstance<DcMotorPart>? = null
        var electromagneticSound: SimpleLoopingMachineSoundInstance<DcMotorPart>? = null
    }

    override fun onAdded() {
        if(placement.level.isClientSide) {
            placement.multipart.addTicker(this)
        }
    }

    @ClientOnly
    override fun setupPacketsOnClient(builder: ClientSidePacketHandlerBuilder) {
        builder.withHandler<SyncPacket>(SyncPacket::deserialize) {
            val state = renderState!!
            state.targetAngularVelocity = abs(it.angularVelocity)
            state.targetPower = abs(it.power)
        }
    }

    @ClientOnly
    override fun clientTick() {
        val state = renderState!!

        if(state.kineticSound == null) {
            state.kineticSound = SimpleLoopingPartSoundInstance(this, Eln2Kinetic.MOTOR_KINETIC_SOUND.get()).also {
                it.events.registerHandler<SoundInstanceTickEvent> { e ->
                    // Treat as the standard processing speed for machines, using a nominal speed as a baseline:
                    state.angularVelocityInterpolator.update(state.targetAngularVelocity)
                    it.soundInfo = SoundInfo.standardWithKineticScraping(
                        state.angularVelocityInterpolator.value,
                        !soundOptions.nominalSpeed
                    )
                }

                it.registerOnAudioManager()
            }
        }

        if(state.electromagneticSound == null) {
            state.electromagneticSound = SimpleLoopingPartSoundInstance(this, Eln2Kinetic.MOTOR_ELECTROMAGNETIC_SOUND.get()).also {
                it.events.registerHandler<SoundInstanceTickEvent> { e ->
                    // Treat as the standard processing speed for machines, using a nominal speed as a baseline:
                    state.powerInterpolator.update(state.targetPower)
                    it.soundInfo = SoundInfo.electromagnetic(
                        state.powerInterpolator.value,
                        !soundOptions.nominalPower
                    )
                }

                it.registerOnAudioManager()
            }
        }
    }

    @ServerOnly @OnSimulationThread
    fun replicate(angularVelocity: Double, power: Double) {
        sendBulkPacket(SyncPacket::serialize, SyncPacket(angularVelocity, power))
    }

    @ServerOnly
    override fun onSyncSuggested() {
        if(hasCell) {
            replicate(cell.kinetic.node.angularVelocity, cell.electrical.voltageSource.power)
        }
    }

    private data class SyncPacket(val angularVelocity: Double, val power: Double) {
        companion object {
            fun serialize(packet: SyncPacket, buffer: FriendlyByteBuf) {
                buffer.writeDouble(packet.angularVelocity)
                buffer.writeDouble(packet.power)
            }

            fun deserialize(buffer: FriendlyByteBuf) = SyncPacket(
                buffer.readDouble(),
                buffer.readDouble()
            )
        }
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Back-EMF: ${cell.electrical.voltageSource.readouts.potential}" }
        builder.debugInIDE { "Flux: ${cell.electrical.armatureInductor.flux}" }
        builder.debugInIDE { "Constraint impulse: ${cell.kinetic.node.extension.impulse}" }
        builder.quantity(cell.kinetic.node.kineticEnergyQuantity)
        builder.quantity(cell.kinetic.node.angularVelocityQuantity)
        builder.quantity(cell.electrical.armatureResistor.readouts.current)
        builder.quantity(cell.electrical.voltageSource.readouts.power)
        builder.quantityOutput(Quantity(cell.lastAppliedTorque, NEWTON_METER))
        builder.quantity(cell.thermal.thermalBody.temperature)
    }
}
