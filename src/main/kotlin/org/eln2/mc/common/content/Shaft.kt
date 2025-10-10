package org.eln2.mc.common.content

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionResult
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.AngularVelocity
import org.eln2.mc.ClientOnly
import org.eln2.mc.Inertia
import org.eln2.mc.KineticNodeSet
import org.eln2.mc.KineticShaft
import org.eln2.mc.RADIAN_PER_SECOND
import org.eln2.mc.ServerOnly
import org.eln2.mc.Torque
import org.eln2.mc.client.render.foundation.BasicKineticPart
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.cells.foundation.CellCreateInfo
import org.eln2.mc.common.cells.foundation.CellProvider
import org.eln2.mc.common.cells.foundation.InternalKineticReplicatorBehavior
import org.eln2.mc.common.cells.foundation.InternalKineticStateConsumer
import org.eln2.mc.common.cells.foundation.KineticObject
import org.eln2.mc.common.cells.foundation.KineticSize
import org.eln2.mc.common.cells.foundation.PersistentObject
import org.eln2.mc.common.cells.foundation.Replicator
import org.eln2.mc.common.cells.foundation.RotatingKineticState
import org.eln2.mc.common.cells.foundation.SidedKinetic
import org.eln2.mc.common.cells.foundation.SidedKineticMapped
import org.eln2.mc.common.cells.foundation.SidedThermalMapped
import org.eln2.mc.common.cells.foundation.SimObject
import org.eln2.mc.common.cells.foundation.SubscriberCollection
import org.eln2.mc.common.cells.foundation.SubscriberPhase
import org.eln2.mc.common.cells.foundation.ThermalSize
import org.eln2.mc.common.cells.foundation.addPost
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.PartUseInfo
import org.eln2.mc.data.Pole
import org.eln2.mc.data.PoleMap
import org.eln2.mc.extensions.debugInIDE
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.minus
import org.eln2.mc.plus

data class KineticShaftDescription(
    val inertia: Quantity<Inertia>,
    val damping: Double,
    val coulombFriction: Quantity<Torque>,
    val staticThreshold: Quantity<Torque>,
    val velocityEps: Quantity<AngularVelocity> = Quantity(0.01, RADIAN_PER_SECOND)
) {
    fun applyTo(shaft: KineticShaft) {
        shaft.inertia = !inertia
        shaft.viscousDamping = damping
        shaft.coulombFriction = !coulombFriction
        shaft.staticFriction = !staticThreshold
        shaft.velocityEps = !velocityEps
    }
}

class KineticShaftObject(
    cell: KineticShaftCell,
    val map: PoleMap,
    shaftParameters: KineticShaftDescription,
    val thermalBody: ThermalMass?
) : KineticObject<KineticShaftCell>(cell), PersistentObject {
    val shaft = KineticShaft()

    val shaftDisplay = shaft.display()

    init {
        shaftParameters.applyTo(shaft)
    }

    override fun addNodes(builder: KineticNodeSet) {
        builder.add(shaft)
    }

    override fun offerExtension(remote: KineticObject<*>) = when(map.evaluateOrNull(this.cell, remote.cell)) {
        Pole.Plus -> shaft.plus()
        Pole.Minus -> shaft.minus()
        null -> null
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        if(thermalBody != null) {
            subscribers.addPost(this::transferHeat)
        }
    }

    private fun transferHeat(dt: Double, subscriberPhase: SubscriberPhase) {
        thermalBody!!.energy += Quantity(shaft.deltaHeatFromFriction, JOULE)
    }

    override fun saveObjectNbt() = CompoundTag().also {
        it.putDouble(ANGLE, shaft.angle)
        it.putDouble(ANGULAR_VELOCITY, shaft.omega)
    }

    override fun loadObjectNbt(tag: CompoundTag) {
        shaft.setExternalAngle(tag.getDouble(ANGLE))
        shaft.omega = tag.getDouble(ANGULAR_VELOCITY)
    }

    companion object {
        private const val ANGLE = "angle"
        private const val ANGULAR_VELOCITY = "omega"
    }
}

class KineticShaftCell(
    ci: CellCreateInfo,
    thermalDef: ThermalMassDefinition,
    val map: PoleMap,
    shaftDef: KineticShaftDescription,
    leakageParameters: ConnectionParameters = ConnectionParameters.DEFAULT
) : Cell(ci), SidedThermalMapped<KineticShaftCell>, SidedKineticMapped<KineticShaftCell> {
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
    val kinetic = KineticShaftObject(this, map, shaftDef, thermal.thermalBody)

    val kineticState get() = RotatingKineticState(kinetic.shaft.angle, kinetic.shaft.omega)

    @Replicator
    fun kineticReplicator(target: InternalKineticStateConsumer) = InternalKineticReplicatorBehavior(
        this::kineticState,
        target
    )
}

class KineticShaftPart(ci: PartCreateInfo, cellProvider: RegistryObject<CellProvider<KineticShaftCell>>) :
    CellPart<KineticShaftCell>(ci, cellProvider.get()),
    BasicKineticPart,
    InternalKineticStateConsumer,
    ComponentDisplay
{
    @ClientOnly
    override val renderState = BasicKineticPart.RenderStateImpl.createFor(this)

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if(!placement.level.isClientSide) {
            if(hasCell) {
                cell.kinetic.shaft.externalTorque += 100.0
            }

            return InteractionResult.SUCCESS
        }


        return super.onUsedBy(context)
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

    @ServerOnly
    override fun submitDisplay(builder: ComponentDisplayList) {
        cell.kinetic.subSolvers?.debugInIDE(builder)
        builder.quantity(cell.kinetic.shaftDisplay.angle)
        builder.quantity(cell.kinetic.shaftDisplay.angularVelocity)
        builder.quantity(cell.kinetic.shaftDisplay.angularAcceleration)
        builder.quantity(cell.kinetic.shaftDisplay.kineticEnergy)
    }
}
