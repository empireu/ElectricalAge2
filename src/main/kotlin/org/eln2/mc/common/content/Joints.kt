package org.eln2.mc.common.content

import net.minecraft.nbt.CompoundTag
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.AngularVelocity
import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.REVOLUTION_PER_SECOND
import org.ageseries.libage.data.Torque
import org.ageseries.libage.data.registerHandler
import org.ageseries.libage.mathematics.FramerateIndependentSmoother1d
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.kinetic.KineticDouble
import org.ageseries.libage.sim.kinetic.KineticExtension
import org.ageseries.libage.sim.kinetic.KineticNode
import org.ageseries.libage.sim.kinetic.KineticNodeSet
import org.ageseries.libage.sim.kinetic.KineticTriple
import org.eln2.mc.*
import org.eln2.mc.client.render.foundation.BasicKineticPart
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.TickablePart
import org.eln2.mc.common.sounds.foundation.SimpleLoopingPartSoundInstance
import org.eln2.mc.common.sounds.foundation.SoundInfo
import org.eln2.mc.common.sounds.foundation.SoundInstanceTickEvent
import org.eln2.mc.data.MonopoleMap
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.anyEvaluates
import org.eln2.mc.extensions.debugInIDE
import org.eln2.mc.extensions.loadNbt
import org.eln2.mc.extensions.saveNbt
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask

interface JointCell {
    val node: KineticNode

    fun submitDebug(builder: ComponentDisplayList)
}

//#region Double Joint

/**
 * Kinetic transfer device with one node and two extensions.
 * Can be used for straight shafts and 90-degree bevel transmissions.
 * */
class DoubleJointObject(cell: DoubleJointCell, friction: FrictionNodeDescription, ratio: Double, val thermalBody: ThermalMass?) : KineticObject<DoubleJointCell>(cell), PersistentObject {
    val node = KineticDouble(ratio == 1.0)

    init {
        friction.applyTo(node)
        node.e2.ratio = ratio
    }

    override fun addNodes(builder: KineticNodeSet) {
        builder.add(node)
    }

    override fun offerExtension(remote: KineticObject<*>) = node.chooseExtension(cell.map, remote)

    override fun subscribe(subscribers: SubscriberCollection) {
        if(thermalBody != null) {
            subscribers.addPost { dt, phase ->
                thermalBody.energy += Quantity(node.deltaHeatFromFriction, JOULE)
            }
        }
    }

    override fun saveObjectNbt() = node.saveNbt()
    override fun loadObjectNbt(tag: CompoundTag) = node.loadNbt(tag)
}

/**
 * Cell for the [DoubleJointObject]. Uses a polar map for filtering.
 * */
class DoubleJointCell(
    ci: CellCreateInfo,
    thermalDef: ThermalMassDefinition,
    val map: PoleMap,
    shaftDef: FrictionNodeDescription,
    ratio: Double,
    breakdownAngularVelocity: Quantity<AngularVelocity>,
    maxTorque: Quantity<Torque>,
    leakageParameters: ConnectionParameters = ConnectionParameters.DEFAULT
) : Cell(ci), SidedThermalMapped<DoubleJointCell>, SidedKineticMapped<DoubleJointCell>, JointCell {
    override val thermalMap: PoleMap
        get() = map

    override val thermalSize: ThermalSize
        get() = ThermalSize.Any

    override val kineticMap: PoleMap
        get() = map

    override val kineticSize: KineticSize
        get() = KineticSize.Standard

    override val isExclusivelyKineticConnected: Boolean
        get() = true

    @SimObject
    val thermal = ThermalWireObject(this, thermalDef(), leakageParameters)

    @SimObject
    val kinetic = DoubleJointObject(this, shaftDef, ratio, thermal.thermalBody).also {
        it.node.setSafeTorque(maxTorque)
    }

    @Behavior
    val kineticBreakdown = KineticBreakdownBehavior.create(breakdownAngularVelocity, this, kinetic.node)

    @Behavior
    val stress = KineticStressBehavior.create(maxTorque, this, kinetic.node)

    val kineticState get() = RotatingKineticState(kinetic.node.angle, kinetic.node.angularVelocity)

    @Replicator
    fun kineticReplicator(target: InternalKineticStateConsumer) = InternalKineticReplicatorBehavior(
        this::kineticState,
        target
    )

    override val node: KineticNode
        get() = kinetic.node

    override fun submitDebug(builder: ComponentDisplayList) {
        builder.debugInIDE { "I0: ${kinetic.node.e1.impulse.rounded()}, I1: ${kinetic.node.e2.impulse.rounded()}" }
    }
}

//#endregion

//#region Triple Joint

/**
 * Kinetic transfer device with one node and three extensions.
 * Can be used for T-joints and corner joints.
 * */
class TripleJointObject(cell: TripleJointCell, friction: FrictionNodeDescription, val thermalBody: ThermalMass?) : KineticObject<TripleJointCell>(cell), PersistentObject {
    val node = KineticTriple()

    init {
        node.e2.ratio *= -1
        friction.applyTo(node)
    }

    override fun addNodes(builder: KineticNodeSet) {
        builder.add(node)
    }

    override fun offerExtension(remote: KineticObject<*>) : KineticExtension? {
        if(cell.mapE1.evaluates(this.cell, remote.cell)) {
            return node.e1
        }

        if(cell.mapE2.evaluates(this.cell, remote.cell)) {
            return node.e2
        }

        if(cell.mapE3.evaluates(this.cell, remote.cell)) {
            return node.e3
        }

        return null
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        if(thermalBody != null) {
            subscribers.addPost { dt, phase ->
                thermalBody.energy += Quantity(node.deltaHeatFromFriction, JOULE)
            }
        }
    }

    override fun saveObjectNbt() = node.saveNbt()
    override fun loadObjectNbt(tag: CompoundTag) = node.loadNbt(tag)
}

/**
 * Cell for the [TripleJointObject]. Uses three separate [MonopoleMap]s to map each extension to a remote object.
 * Care must be taken so the maps are exclusive (two maps cannot evaluate for the same input).
 * */
class TripleJointCell(
    ci: CellCreateInfo,
    thermalDef: ThermalMassDefinition,
    val mapE1: MonopoleMap,
    val mapE2: MonopoleMap,
    val mapE3: MonopoleMap,
    shaftDef: FrictionNodeDescription,
    breakdownAngularVelocity: Quantity<AngularVelocity>,
    maxTorque: Quantity<Torque>,
    leakageParameters: ConnectionParameters = ConnectionParameters.DEFAULT
) : Cell(ci), SidedThermal<TripleJointCell>, SidedKinetic<TripleJointCell>, JointCell {
    override fun getThermalSizeOnSide(side: Base6Direction3d, targetCell: Cell) =
        if(anyEvaluates(this, targetCell, mapE1, mapE2, mapE3)) {
            ThermalSize.Any
        }
        else {
            null
        }

    override fun getKineticSizeOnSide(side: Base6Direction3d, targetCell: Cell) =
        if(anyEvaluates(this, targetCell, mapE1, mapE2, mapE3)) {
            KineticSize.Standard
        }
        else {
            null
        }

    override val isExclusivelyKineticConnected: Boolean
        get() = true

    @SimObject
    val thermal = ThermalWireObject(this, thermalDef(), leakageParameters)

    @SimObject
    val kinetic = TripleJointObject(this, shaftDef, thermal.thermalBody).also {
        it.node.setSafeTorque(maxTorque)
    }

    @Behavior
    val kineticBreakdown = KineticBreakdownBehavior.create(breakdownAngularVelocity, this, kinetic.node)

    @Behavior
    val stress = KineticStressBehavior.create(maxTorque, this, kinetic.node)

    val kineticState get() = RotatingKineticState(kinetic.node.angle, kinetic.node.angularVelocity)

    @Replicator
    fun kineticReplicator(target: InternalKineticStateConsumer) = InternalKineticReplicatorBehavior(
        this::kineticState,
        target
    )

    override val node: KineticNode
        get() = kinetic.node

    override fun submitDebug(builder: ComponentDisplayList) {
        builder.debugInIDE { "I0: ${kinetic.node.e1.impulse.rounded()}, I1: ${kinetic.node.e2.impulse.rounded()}, I2: ${kinetic.node.e3.impulse.rounded()}" }
    }
}

//#endregion

/**
 * Generalized part for joints with one node.
 * Implements replication of the kinetic state (the state of the single node).
 * The rendered direction of rotation is left to the visual to deal with.
 * */
class JointPart<C>(
    ci: PartCreateInfo,
    cellProvider: RegistryObject<CellProvider<C>>,
    pipelikeConnectionMaskPart: Base6Direction3dMask
) :
    CellPart<C>(ci, cellProvider.get(), pipelikeConnectionMaskPart),
    BasicKineticPart,
    InternalKineticStateConsumer,
    TickablePart,
    ComponentDisplay
    where C : Cell, C : JointCell
{
    companion object {
        private val NOMINAL_SPEED = Quantity(25.0, REVOLUTION_PER_SECOND)
    }

    @ClientOnly
    override val renderState = BasicKineticPart.RenderStateImpl.createFor(this)

    // Updated in [clientTick] for audio:
    @ClientOnly
    private val clientTickSpeedSmoother = FramerateIndependentSmoother1d(0.2)
    private var soundInstance: SimpleLoopingPartSoundInstance<JointPart<C>>? = null

    @ClientOnly
    override fun onAdded() {
        if(placement.level.isClientSide) {
            placement.multipart.addTicker(this)
        }
    }

    @ClientOnly
    override fun setupPacketsOnClient(builder: ClientSidePacketHandlerBuilder) {
        builder.withHandler<BasicKineticPart.RotationSyncPacket> {
            renderState!!.load(it)
        }
    }

    @ServerOnly
    override fun onKineticStateChanged(state: RotatingKineticState, angularAccelerationEstimate: Double) {
        sendBulkPacket(BasicKineticPart.RotationSyncPacket(
            state.angle,
            state.angularVelocity,
            angularAccelerationEstimate
        ))
    }

    /**
     * Does the sound.
     * */
    @ClientOnly
    override fun clientTick() {
        if (soundInstance != null) {
            return
        }

        soundInstance = SimpleLoopingPartSoundInstance(this, Content.JOINT_SOUND.get()).also {
            it.events.registerHandler<SoundInstanceTickEvent> { e ->
                // Treat as the standard processing speed for machines, using a nominal speed as a baseline:
                clientTickSpeedSmoother.update(renderState!!.angularVelocity)
                it.soundInfo = SoundInfo.standardWithKineticScraping(
                    clientTickSpeedSmoother.value, !NOMINAL_SPEED
                )
            }

            it.registerOnAudioManager()
        }
    }

    @ServerOnly
    override fun submitDisplay(builder: ComponentDisplayList) {
        cell.objects.kineticObject.subSolvers?.debugInIDE(builder)
        cell.submitDebug(builder)
        builder.quantity(cell.node.angleQuantity)
        builder.quantity(cell.node.angularVelocityQuantity)
        builder.quantity(cell.node.kineticEnergyQuantity)
    }
}
