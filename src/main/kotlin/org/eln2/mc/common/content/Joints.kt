package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.ShaderLightVisual
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import kotlinx.serialization.Serializable
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.AngularVelocity
import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.Mass
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.RADIAN_PER_SECOND
import org.ageseries.libage.data.REVOLUTION_PER_SECOND
import org.ageseries.libage.data.Torque
import org.ageseries.libage.data.registerHandler
import org.ageseries.libage.mathematics.FramerateIndependentSmoother1d
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.kinetic.KineticConstraintMap
import org.ageseries.libage.sim.kinetic.KineticDouble
import org.ageseries.libage.sim.kinetic.KineticExtension
import org.ageseries.libage.sim.kinetic.KineticNodeSet
import org.eln2.mc.*
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.client.render.foundation.partOffsetTable
import org.eln2.mc.client.render.foundation.partTransformation
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.modules.Eln2Kinetic
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.AbstractPartVisual
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.TickablePart
import org.eln2.mc.common.sounds.foundation.SimpleLoopingPartSoundInstance
import org.eln2.mc.common.sounds.foundation.SoundInfo
import org.eln2.mc.common.sounds.foundation.SoundInstanceTickEvent
import org.eln2.mc.extensions.alias
import org.eln2.mc.extensions.data3D
import org.eln2.mc.extensions.debugInIDE
import org.eln2.mc.extensions.forEachCompound
import org.eln2.mc.extensions.getListTag
import org.eln2.mc.extensions.loadNbt
import org.eln2.mc.extensions.rotationFast
import org.eln2.mc.extensions.saveNbt
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask
import kotlin.math.abs
import kotlin.math.max

/**
 * Joint that dynamically allocates elements, like the wire.
 * In this case, we allocate a kinetic double for each neighbor. Then, we constrain them internally so it acts as one solid mechanism.
 * In a two element case, this gets optimized away and its equivalent to having one kinetic double.
 * It becomes slightly inefficient when we have more neighbors, because it creates multiple constraints (as opposed to modeling the shaft as a multi-extension node).
 * However, it is too flexible to pass up.
 * */
class MultiJointObject(cell: MultiJointCell) : KineticObject<MultiJointCell>(cell), PersistentObject {
    /**
     * The nodes, indexed by 3D data value of the direction in the local frame.
     * [KineticDouble.e1] is offered externally, and [KineticDouble.e2] is used to constrain internally.
     * */
    val nodes = Array<KineticDouble?>(6) { null }

    init {
        /**
         * We pre-allocate the nodes and keep them instanced permanently, if required.
         * */
        if(cell.alwaysInstanceNodes) {
            cell.mask.forEach { dir ->
                createNode(dir.get3DDataValue())
            }
        }
    }

    /**
     * Gets the maximum angular velocity (in magnitude) across the nodes.
     * */
    val maxAngularVelocityForBreakdown: Double get() {
        var result = 0.0

        for(i in 0 until 6) {
            val node = nodes[i]

            if(node != null) {
                val velocity = abs(node.angularVelocity)

                if(velocity > result) {
                    result = velocity
                }
            }
        }

        return result
    }

    /**
     * Gets the maximum torque (in magnitude) across the nodes.
     * */
    val maxTorqueForStress: Double get() {
        val recip = 1.0 / CellGraph.DT
        var result = 0.0

        for(i in 0 until 6) {
            val node = nodes[i]

            if(node != null) {
                // P.S. they are equal in magnitude in a correct state.
                val s1 = abs(node.e1.impulse * recip)
                val s2 = abs(node.e2.impulse * recip)
                val torque = max(s1, s2)

                if(torque > result) {
                    result = torque
                }
            }
        }

        return result
    }

    /**
     * Creates a node in the array.
     * */
    private fun createNode(index: Int) : KineticDouble {
        check(nodes[index] == null) {
            FTL("Tried to create node in slot that already had a node")
        }

        val node = KineticDouble(true)
        node.setSafeTorque(cell.maxTorque)

        cell.shaftNodeDef.applyTo(node)
        nodes[index] = node

        return node
    }

    private var nodeCountBeforeBuild = 0

    /**
     * Stores the number of nodes in [nodeCountBeforeBuild].
     * */
    override fun clearNodes() {
        super.clearNodes()

        nodeCountBeforeBuild = nodes.count { it != null }
    }

    /**
     * Creates missing nodes. We need to do it here, so we have all the nodes needed for [addNodes].
     * */
    override fun addConnection(remoteObj: KineticObject<*>) {
        super.addConnection(remoteObj)

        val direction = checkNotNull(cell.locator.findDirActualSpecificFrameOrNull(remoteObj.cell.locator)) {
            DEBUGGER_BREAK("Joint object got unsolvable connection")
        }

        check(cell.mask.has(direction)) {
            DEBUGGER_BREAK()
        }

        val index = direction.data3D
        if(nodes[index] == null) {
            createNode(index)
        }
    }

    /**
     * Removes nodes that don't correspond to a connection.
     * */
    override fun addNodes(builder: KineticNodeSet) {
        if (cell.alwaysInstanceNodes) {
            /**
             * Adds all nodes. The unconnected ones will still render since we are still syncing and constraining them.
             * */
            nodes.forEach { node ->
                if(node != null) {
                    builder.add(node)
                }
            }
        }
        else {
            var nodesToKeep = Base6Direction3dMask.EMPTY

            for (remoteObject in connections) {
                val direction = cell.locator.findDirActualSpecificFrameOrNull(remoteObject.cell.locator) ?: FTL()
                val node = nodes[direction.data3D] ?: FTL()
                builder.add(node)
                nodesToKeep += direction
            }

            /**
             * Only delete nodes if needed:
             * */
            for (i in 0 until 6) {
                if(nodes[i] != null && !nodesToKeep.has(Direction.from3DDataValue(i))) {
                    nodes[i] = null
                }
            }
        }
    }

    /**
     * Constrains each node to each other node and adjusts the mass and temperature of the thermal body.
     * */
    override fun build(map: KineticConstraintMap) {
        super.build(map)

        val nodesToConstrain = nodes.filterNotNull()
        val nodeCount = nodesToConstrain.size

        if(nodeCount > 1) {
            /**
             * We pick a representative to create `N - 1` constraint equations.
             * */
            val representative = nodesToConstrain[0]
            val representativeIdx = nodes.indexOf(representative)
            val representativeK = cell.ratios[representativeIdx] * (if (representativeIdx % 2 == 0) -1.0 else 1.0)

            /**
             * Use it as a reference for the other ratios:
             * */
            representative.e2.ratio = 1.0

            for (i in 1 until nodeCount) {
                val other = nodesToConstrain[i]
                val otherIdx = nodes.indexOf(other)
                val k = cell.ratios[otherIdx] * (if (otherIdx % 2 == 0) -1.0 else 1.0)

                other.e2.ratio = -(representativeK / k)

                map.join(representative.e2, other.e2)
            }
        }

        /**
         * The cell pre-sets the mass in the always case, so we don't need any thermal logic.
         * */
        if(!cell.alwaysInstanceNodes) {
            cell.thermal.thermalBody.mass = cell.baseThermalDef.mass + cell.shaftMass * nodeCount.toDouble()

            if(nodeCount != nodeCountBeforeBuild) {
                val dE = !cell.baseThermalDef.material.specificHeat * !cell.shaftMass * !cell.environmentData.ambientTemperature * (nodeCount - nodeCountBeforeBuild)
                cell.thermal.thermalBody.energy += Quantity(dE, JOULE)
                cell.setChanged()
            }
        }

        cell.syncRequired = true
    }

    override fun offerExtension(remote: KineticObject<*>): KineticExtension? {
        val direction = cell.locator.findDirActualSpecificFrameOrNull(remote.cell.locator) ?: FTL()

        return nodes[direction.alias.get3DDataValue()]?.e1
    }

    override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        subscribers.addPost(this::tick)
    }

    private fun tick(dt: Double, phase: SimulationPhase) {
        val nodes = nodes
        var energy = 0.0

        for (i in 0 until 6) {
            val node = nodes[i]
                ?: continue

            energy += node.deltaHeatFromFriction

            if(!node.angularVelocity.approxEq(0.0)) {
                cell.setChanged()
            }
        }

        cell.thermal.thermalBody.energy += Quantity(energy, JOULE)
    }

    override fun saveObjectNbt(): CompoundTag {
        val tag = CompoundTag()
        val list = ListTag()

        for (i in 0 until 6) {
            val node = nodes[i]
                ?: continue

            val nodeTag = node.saveNbt()
            nodeTag.putInt(INDEX, i)
            list.add(nodeTag)
        }

        tag.put(NODES, list)

        return tag
    }

    /**
     * P.S. Called before [build]!
     * */
    override fun loadObjectNbt(tag: CompoundTag) {
        val list = tag.getListTag(NODES)

        list.forEachCompound { nodeTag ->
            val index = nodeTag.getInt(INDEX)
            val node = nodes[index] ?: createNode(index)
            node.loadNbt(nodeTag)
        }
    }

    companion object {
        const val INDEX = "i"
        const val NODES = "nodes"
    }
}

class MultiJointCell(
    ci: CellCreateInfo,
    val baseThermalDef: ThermalMassDefinition,
    val shaftMass: Quantity<Mass>,
    val mask: Base6Direction3dMask,
    val ratios: DoubleArray,
    val kineticSize: KineticSize,
    val shaftNodeDef: FrictionNodeDescription,
    breakdownAngularVelocity: Quantity<AngularVelocity>,
    val maxTorque: Quantity<Torque>,
    val alwaysInstanceNodes: Boolean,
    leakageParameters: ConnectionParameters = ConnectionParameters.DEFAULT
) : Cell(ci), SidedKinetic<MultiJointCell> {
    override val isExclusivelyKineticConnected: Boolean
        get() = true

    override fun getKineticSizeOnSide(side: Base6Direction3d, targetCell: Cell): KineticSize? {
        return if (mask.has(side)) {
            kineticSize
        }
        else null
    }

    /**
     * Set when sync is required, so the replicator sends an update.
     * */
    @OnServerThread
    var syncRequired = false

    @SimObject
    val thermal = ThermalWireObject(this, let {
        val result = baseThermalDef()

        if(alwaysInstanceNodes) {
            result.mass += shaftMass * mask.count.toDouble()
        }

        result
    }, leakageParameters).also {
        it.savePolicy = ThermalWireObject.ThermalStateSavingPolicy.Energy
    }

    @SimObject
    val kinetic = MultiJointObject(this)

    @Behavior
    val kineticBreakdown = KineticBreakdownBehavior.create(breakdownAngularVelocity, this, kinetic::maxAngularVelocityForBreakdown)

    @Behavior
    val stress = KineticStressBehavior.create(maxTorque, this, kinetic::maxTorqueForStress)

    @Replicator
    fun replicator(target: JointPart) = KineticAndConnectivityReplicator(this, target)

    /**
     * Replicates both the kinetic states and also the connectivity (sends NaN angle and velocity for unconnected shafts).
     * */
    class KineticAndConnectivityReplicator(val cell: MultiJointCell, val consumer: JointPart) : ReplicatorBehavior {
        var angleTolerance = Math.toRadians(1.0)
        var angularVelocityTolerance = Math.toRadians(5.0)

        // Optional doubles. NaN means no shaft:
        val trackedAngles = DoubleArray(6) { Double.NaN }
        val trackedVelocities = DoubleArray(6) { Double.NaN }

        private var time = 0.0

        override fun subscribeServerThread(subscribers: SubscriberCollection<ServerPhase>) {
            subscribers.addStart(this::updatePreServer)
            subscribers.addEnd(this::updatePostServer)
        }

        private var isDirty = false

        /**
         * Checks if an update is needed based on a prediction of the client's state.
         * Sets [isDirty] and sets [KineticReSyncFlag].
         * */
        @OnServerThread
        private fun updatePreServer(dt: Double, phase: ServerPhase) {
            val nodes = cell.kinetic.nodes
            val trackedAngles = trackedAngles
            val trackedVelocities = trackedVelocities

            for (i in 0 until 6) {
                val node = nodes[i]

                if(node == null) {
                    if(!trackedAngles[i].isNaN()) {
                        /**
                         * A shaft was removed:
                         * */
                        isDirty = true
                        break
                    }
                }
                else {
                    if (trackedAngles[i].isNaN()) {
                        /**
                         * A shaft was created:
                         * */
                        isDirty = true
                        break
                    }

                    val trackedRotation = Rotation2d.exp(trackedAngles[i] + trackedVelocities[i] * time)
                    val currentRotation = Rotation2d.exp(node.angle)

                    if (abs(currentRotation - trackedRotation) > angleTolerance || abs(node.angularVelocity - trackedVelocities[i]) > angularVelocityTolerance) {
                        isDirty = true
                        val subSolver = node.simulation
                        cell.graph.kineticFlagsSimulation.setFlag(subSolver, KineticReSyncFlag)
                        break
                    }
                }
            }
        }

        @Serializable
        class State private constructor(val angles: DoubleArray, val velocities: DoubleArray) {
            companion object {
                fun create() = State(
                    DoubleArray(6) { Double.NaN },
                    DoubleArray(6) { Double.NaN }
                )
            }
        }

        /**
         * Checks if [isDirty] was set or if the sub-solver has [KineticReSyncFlag] and, if so, sends the update.
         * */
        @OnServerThread
        private fun updatePostServer(dt: Double, phase: ServerPhase) {
            val nodes = cell.kinetic.nodes
            var anySyncFlags = false
            for (i in 0 until 6) {
                val node = nodes[i]
                    ?: continue

                if(cell.graph.kineticFlagsSimulation.isSet(node.simulation, KineticReSyncFlag)) {
                    anySyncFlags = true
                    break
                }
            }

            if(!isDirty && !cell.syncRequired && !anySyncFlags) {
                time += dt
                return
            }

            isDirty = false
            cell.syncRequired = false

            val packet = State.create()
            for (i in 0 until 6) {
                val node = nodes[i]

                if(node == null) {
                    trackedAngles[i] = Double.NaN
                    trackedVelocities[i] = Double.NaN
                }
                else {
                    val angle = node.angle
                    val velocity = node.angularVelocity
                    trackedAngles[i] = angle
                    trackedVelocities[i] = velocity
                    packet.angles[i] = angle
                    packet.velocities[i] = velocity
                }
            }

            time = 0.0
            consumer.onKineticUpdate(packet)
        }
    }
}

class JointPart(
    ci: PartCreateInfo,
    cellProvider: RegistryObject<CellProvider<MultiJointCell>>,
    pipelikeConnectionMaskPart: Base6Direction3dMask
) : CellPart<MultiJointCell>(ci, cellProvider.get(), pipelikeConnectionMaskPart),
    TickablePart,
    ComponentDisplay,
    WrenchRotatable
{
    companion object {
        private val NOMINAL_SPEED = Quantity(25.0, REVOLUTION_PER_SECOND)
    }

    /**
     * Lets the shaft float.
     * */
    override fun breaksOnSubstrateBroken(): Boolean {
        return false
    }

    //#region Client

    class RenderState {
        var rotatingStates = MultiJointCell.KineticAndConnectivityReplicator.State.create()
        var version = 0
    }

    @ClientOnly
    val renderState = if(placement.level.isClientSide) RenderState() else null

    /**
     * Updated in [clientTick] for audio:
     * */
    @ClientOnly
    private val clientTickSpeedSmoother = FramerateIndependentSmoother1d(0.2)

    @ClientOnly
    private var soundInstance: SimpleLoopingPartSoundInstance<JointPart>? = null

    @ClientOnly
    override fun onAdded() {
        if(placement.level.isClientSide) {
            placement.multipart.addTicker(this)
        }
    }

    @ClientOnly
    override fun setupPacketsOnClient(builder: ClientSidePacketHandlerBuilder) {
        builder.withHandler<MultiJointCell.KineticAndConnectivityReplicator.State> {
            val renderState = renderState!!
            renderState.rotatingStates = it
            renderState.version++
        }
    }

    /**
     * Does the sound.
     * */
    @ClientOnly
    override fun clientTick() {
        if (soundInstance != null) {
            return
        }

        soundInstance = SimpleLoopingPartSoundInstance(this, Eln2Kinetic.JOINT_SOUND.get()).also {
            it.events.registerHandler<SoundInstanceTickEvent> { e ->
                val velocities = renderState!!.rotatingStates.velocities
                var maxVelocity = 0.0
                for (i in 0 until 6) {
                    var value = velocities[i]

                    if(!value.isNaN()) {
                        value = abs(value)

                        if(value > maxVelocity) {
                            maxVelocity = value
                        }
                    }
                }

                clientTickSpeedSmoother.update(maxVelocity)

                /**
                 * Treat as the standard processing speed for machines, using a nominal speed as a baseline:
                 * */
                it.soundInfo = SoundInfo.standardWithKineticScraping(clientTickSpeedSmoother.value, !NOMINAL_SPEED)
            }

            it.registerOnAudioManager()
        }
    }

    //#endregion

    @ServerOnly
    fun onKineticUpdate(state: MultiJointCell.KineticAndConnectivityReplicator.State) {
        sendBulkPacket(state)
    }

    @ServerOnly
    override fun onSyncSuggested() {
        cell.syncRequired = true
    }

    @ServerOnly
    override fun submitDisplay(builder: ComponentDisplayList) {
        cell.objects.kineticObject.subSolvers?.debugInIDE(builder)
        builder.quantity(cell.thermal.thermalBody.temperature)

        val nodes = cell.kinetic.nodes.filterNotNull()

        if (nodes.isNotEmpty()) {
            val maxSpeed = nodes.maxOf { abs(it.angularVelocity) }
            builder.quantity(Quantity(maxSpeed, RADIAN_PER_SECOND))

            val totalEnergy = nodes.sumOf { it.kineticEnergy }
            builder.quantity(Quantity(totalEnergy, JOULE))
        }
    }
}

/**
 * 3D Models for the joint.
 * @param body The base model, that doesn't change with kinetic state and connectivity.
 * */
class JointPartModel private constructor(val body: PartialModel, val shaftMap: Array<ShaftModel?>) {
    class ShaftModel(val rotatingModel: PartialModel?, val staticModel: PartialModel?)

    companion object {
        fun homogenous(body: PartialModel, rotatingModel: PartialModel?, staticModel: PartialModel?) = JointPartModel(
            body,
            Array(6) {
                ShaftModel(rotatingModel, staticModel)
            }
        )

        class Builder {
            val models = Array<ShaftModel?>(6) { null }

            fun withModel(directionPart: Direction, rotatingModel: PartialModel?, staticModel: PartialModel?) {
                models[directionPart.get3DDataValue()] = ShaftModel(rotatingModel, staticModel)
            }

            fun withModel(directionPart: Base6Direction3d, rotatingModel: PartialModel?, staticModel: PartialModel?) {
                models[directionPart.data3D] = ShaftModel(rotatingModel, staticModel)
            }
        }

        fun build(body: PartialModel, action: Builder.() -> Unit) : JointPartModel {
            val builder = Builder()
            action(builder)

            return JointPartModel(body, builder.models)
        }
    }
}

class JointPartVisual(visualizationContext: MultipartVisualizationContext, part: JointPart, val model: JointPartModel) : AbstractPartVisual<JointPart>(visualizationContext, part), SimpleDynamicVisual, ShaderLightVisual {
    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(model.body, FlwMaterials.SMOOTH_LIT))
        .createInstance()
        .also { it.partTransformation(visualizationContext.parent, part) }

    class Shaft(val rotatingInstance: TransformedInstance?, val staticInstance: TransformedInstance?, val interpolator: KineticInterpolatorClient)

    /**
     * Indexed by the 3D data of the direction in the local frame, just like the cell:
     * */
    val shafts = Array<Shaft?>(6) { null }
    var version = 0

    /**
     * Deletes instances for sides that got disconnected, and creates instances for sides that connected.
     * */
    private fun applyConnectivityChanges() {
        val renderState = part.renderState!!
        val targetVersion = renderState.version
        val targetStates = renderState.rotatingStates

        if (targetVersion == version) {
            return
        }

        version = targetVersion

        for (i in 0 until 6) {
            val shaftModel = model.shaftMap[i]
                ?: continue

            val angle = targetStates.angles[i]
            var shaft = shafts[i]

            if(angle.isNaN()) {
                if(shaft != null) {
                    shaft.rotatingInstance?.delete()
                    shaft.staticInstance?.delete()
                    shafts[i] = null
                }
            }
            else {
                if(shaft == null) {
                    val rotating: TransformedInstance? = if(shaftModel.rotatingModel != null) {
                        visualizationContext.instancerProvider()
                            .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(shaftModel.rotatingModel, FlwMaterials.SMOOTH_LIT))
                            .createInstance()
                    }
                    else null

                    val static = if(shaftModel.staticModel != null) {
                        val (dx, dy, dz) = partOffsetTable[part.placement.face.get3DDataValue()]

                        visualizationContext.instancerProvider()
                            .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(shaftModel.staticModel, FlwMaterials.SMOOTH_LIT))
                            .createInstance()
                            .setIdentityTransform()
                            .translate(visualizationContext.parent.visualPosition)
                            .translate(dx, dy, dz)
                            .rotate(part.placement.face.rotationFast)
                            .rotateY((part.placement.facing.angle).toFloat())
                            .translate(-0.5, 0.0, -0.5)
                            .translate(0.5, 0.5, 0.5)
                            .rotateToFace(Direction.from3DDataValue(i))
                            .translate(-0.5, -0.5, -0.5)
                            .also { it.setChanged() }
                    }
                    else null

                    shaft = Shaft(rotating, static, KineticInterpolatorClient())
                    shafts[i] = shaft
                }

                shaft.interpolator.applyServerState(angle, targetStates.velocities[i])
            }
        }
    }

    /**
     * Animates the rotation:
     * */
    private fun rotateShafts() {
        for (i in 0 until 6) {
            val shaft = shafts[i]
                ?: continue

            val instance = shaft.rotatingInstance
                ?: continue

            shaft.interpolator.update()

            val dirPart = Direction.from3DDataValue(i)
            val (cX, cY, _) = FlwModels.getModelCenter(model.shaftMap[i]!!.rotatingModel!!)
            val (dx, dy, dz) = partOffsetTable[part.placement.face.get3DDataValue()]

            /**
             * I do the transformation in simple parts like this so it's clearer to future maintainers.
             * */
            instance.setIdentityTransform()
                // 3. Apply part transformation:
                .translate(visualizationContext.parent.visualPosition)
                .translate(dx, dy, dz)
                .rotate(part.placement.face.rotationFast)
                .rotateY((part.placement.facing.angle).toFloat())
                .translate(-0.5, 0.0, -0.5)
                // 3
                // 2. Rotate around the hub to get into the correct position, in the local frame (see the model in BB):
                .translate(0.5, 0.5, 0.5)
                .rotateToFace(dirPart)
                .translate(-0.5, -0.5, -0.5)
                // 2
                // 1. Rotate around the X axis in place:
                .translate(cX, cY, 0.0)
                .rotateZ(shaft.interpolator.clientRotation.ln().toFloat())
                .translate(-cX, -cY, 0.0)
                // 1
                .setChanged()
        }
    }

    override fun beginFrame(p0: DynamicVisual.Context?) {
        applyConnectivityChanges()
        rotateShafts()
    }

    override fun updateLight(p0: Float) {
        // NOOP
    }

    override fun _delete() {
        body.delete()

        shafts.forEach {
            if(it != null) {
                it.rotatingInstance?.delete()
                it.staticInstance?.delete()
            }
        }
    }
}
