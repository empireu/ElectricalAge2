package org.eln2.mc.common.content

import net.minecraft.nbt.CompoundTag
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.*
import org.eln2.mc.client.render.foundation.BasicKineticPart
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.data.MonopoleMap
import org.eln2.mc.data.Pole
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.anyEvaluates
import org.eln2.mc.extensions.debugInIDE
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d

private fun saveNodeNbt(node: KineticNode) = CompoundTag().also {
    it.putDouble("angle", node.angle)
    it.putDouble("omega", node.angularVelocity)
}

private fun loadNodeNbt(tag: CompoundTag, node: KineticNode) {
    node.setExternalAngle(tag.getDouble("angle"))
    node.angularVelocity = tag.getDouble("omega")
}

interface JointCell {
    val node: KineticNode
}

//#region Double Joint

/**
 * Kinetic transfer device with one node and two extensions.
 * Can be used for straight shafts and 90-degree bevel transmissions.
 * */
class DoubleJointObject(cell: DoubleJointCell, friction: FrictionNodeDescription, ratio: Double, val thermalBody: ThermalMass?) : KineticObject<DoubleJointCell>(cell), PersistentObject {
    val node = KineticShaft(ratio == 1.0)
    val display = node.display()

    init {
        friction.applyTo(node)
        node.e2.ratio = ratio
    }

    override fun addNodes(builder: KineticNodeSet) {
        builder.add(node)
    }

    override fun offerExtension(remote: KineticObject<*>) = when(cell.map.evaluateOrNull(this.cell, remote.cell)) {
        Pole.Plus -> node.plus()
        Pole.Minus -> node.minus()
        null -> null
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        if(thermalBody != null) {
            subscribers.addPost { dt, phase ->
                thermalBody.energy += Quantity(node.deltaHeatFromFriction, JOULE)
            }
        }
    }

    override fun saveObjectNbt() = saveNodeNbt(node)
    override fun loadObjectNbt(tag: CompoundTag) = loadNodeNbt(tag, node)
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
    val kinetic = DoubleJointObject(this, shaftDef, ratio, thermal.thermalBody)

    val kineticState get() = RotatingKineticState(kinetic.node.angle, kinetic.node.angularVelocity)

    @Replicator
    fun kineticReplicator(target: InternalKineticStateConsumer) = InternalKineticReplicatorBehavior(
        this::kineticState,
        target
    )

    override val node: KineticNode
        get() = kinetic.node
}

//#endregion

//#region Triple Joint

/**
 * Kinetic transfer device with one node and three extensions.
 * Can be used for T-joints and corner joints.
 * */
class TripleJointObject(cell: TripleJointCell, friction: FrictionNodeDescription, val thermalBody: ThermalMass?) : KineticObject<TripleJointCell>(cell), PersistentObject {
    val node = KineticTriple()
    val display = node.display()

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

    override fun saveObjectNbt() = saveNodeNbt(node)
    override fun loadObjectNbt(tag: CompoundTag) = loadNodeNbt(tag, node)
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
    val kinetic = TripleJointObject(this, shaftDef, thermal.thermalBody)

    val kineticState get() = RotatingKineticState(kinetic.node.angle, kinetic.node.angularVelocity)

    @Replicator
    fun kineticReplicator(target: InternalKineticStateConsumer) = InternalKineticReplicatorBehavior(
        this::kineticState,
        target
    )

    override val node: KineticNode
        get() = kinetic.node
}

//#endregion

/**
 * Generalized part for joints with one node.
 * Implements replication of the kinetic state (the state of the single node).
 * The rendered direction of rotation is left to the visual to deal with.
 * */
class JointPart<C>(ci: PartCreateInfo, cellProvider: RegistryObject<CellProvider<C>>) :
    CellPart<C>(ci, cellProvider.get()),
    BasicKineticPart,
    InternalKineticStateConsumer,
    ComponentDisplay where C : Cell, C : JointCell
{
    @ClientOnly
    override val renderState = BasicKineticPart.RenderStateImpl.createFor(this)

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
        cell.objects.kineticObject.subSolvers?.debugInIDE(builder)
        builder.quantity(cell.node.angleQuantity)
        builder.quantity(cell.node.angularVelocityQuantity)
        builder.quantity(cell.node.kineticEnergyQuantity)
    }
}
