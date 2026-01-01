@file:Suppress("unused")

package org.eln2.mc.common.content.processing

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.ShaderLightVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import kotlinx.serialization.Serializable
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.FramerateIndependentSmoother1d
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Pole
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.ElectricalSimulation
import org.ageseries.libage.sim.electrical.Inductor
import org.ageseries.libage.sim.electrical.LinearDiode
import org.ageseries.libage.sim.electrical.PotentialSource
import org.ageseries.libage.sim.kinetic.KineticDouble
import org.ageseries.libage.sim.kinetic.KineticNodeSet
import org.eln2.mc.*
import org.eln2.mc.common.blocks.foundation.UprightHorizontalDirectionCellBlock
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.content.ThermalWireObject
import org.eln2.mc.common.network.serverToClient.BulkPacketHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.sendBulkPacket
import org.eln2.mc.common.sounds.foundation.SimpleLoopingBlockEntitySoundInstance
import org.eln2.mc.common.sounds.foundation.SoundInfo
import org.eln2.mc.common.sounds.foundation.SoundInstanceTickEvent
import org.eln2.mc.PoleMap
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.cells.CellRegistry
import org.eln2.mc.common.containers.ProgressContainerData
import org.eln2.mc.common.items.ItemRegistry
import org.eln2.mc.common.recipes.foundation.Eln2SimpleOutputProcessingLoopRecipe
import org.eln2.mc.common.recipes.foundation.INPUT_SLOT
import org.eln2.mc.common.recipes.foundation.ProcessingDevice
import org.eln2.mc.common.recipes.foundation.ProcessingRecipeLoop
import org.eln2.mc.common.recipes.foundation.SimpleProcessingRecipeInventoryHandler
import org.eln2.mc.evaluate
import org.eln2.mc.extensions.constructMenuHelper2
import org.eln2.mc.extensions.debugInIDE
import org.eln2.mc.extensions.loadNbt
import org.eln2.mc.extensions.saveNbt
import org.eln2.mc.extensions.transformFacingBlock
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Abstraction for (single-block) machines that have both kinetic and electrical variants, and possibly multiple tiers of those variants.
 * The machine implements one block and one block entity, that has all the needed recipe and A/V logic.
 * It controls everything using [processingSpeed].
 *
 * To implement multiple variants, we define generic "work boxes", which are cells such as [KineticProcessingCell] and [MotorProcessingCell], with different parameters (tiers).
 * These boxes are then wrapped in a craftable item, that is reused for all machines.
 * Then, we register the specific block and block entity for our machine with the work box cells (by changing the cell provider).
 * Finally, we add a recipe for the base machine, that isn't placeable and doesn't work by itself, but we define a recipe taking the base machine and one of the work boxes, and this creates one of those registered variants which we can place and use.
 * TODO I will implement this automatically with datagen, gotta update this documentation
 * */
abstract class ProcessingCell(ci: CellCreateInfo) : Cell(ci), ProcessingDevice {
    override var isActive: Boolean = false

    override var processingSpeed = 0.0
        protected set

    /**
     * Read by the game object and used for rendering. For the kinetic work box, this should match the node exactly, so the shaft seems rigidly connected.
     * For the electrical variant, this should be some emulation of the internal motor's rotation, but it would usually be ignored and the speed would be used for any animations.
     * */
    @CrossThreadAccess @OnServerThread
    abstract val kineticState: RotatingKineticState

    /**
     * Factor set by the block entity. It is used to scale the consumption of the machine.
     * For example, the crusher will use a large value, but the extruder will use a small value.
     * In other words, this can be used to make some machines consume a different amount of power compared to other machines, even with the same work box.
     * */
    @CrossThreadAccess @OnServerThread
    var loadFactor: Double = 1.0

    /**
     * Factor set by the block entity. It is used to calculate the portion of simulated work that gets converted into heat.
     * The simulated work is friction, in both the kinetic box and the electrical box.
     * For example, the extruder would have a small thermal factor, since there isn't much friction. But the crusher, for example, would have a much larger factor.
     * */
    @CrossThreadAccess @OnServerThread
    var thermalFactor: Double = 1.0

    enum class Direction {
        Forward,
        Reverse
    }

    /**
     * The direction currently being imposed by the external device. For electrical boxes, the polarity will set this, and for kinetic boxes, the rotation direction will set this.
     * */
    var direction: ProcessingCell.Direction = ProcessingCell.Direction.Forward
        protected set

    /**
     * Replicator for the processing speed (handled by [ProcessingMachineBlockEntity.onSpeedChanged]).
     * */
    class ProcessingCellSpeedReplicator(val supplier: Supplier<Double>, val consumer: ProcessingMachineBlockEntity<*>) : ReplicatorBehavior {
        var trackedSpeed = 0.0

        override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
            subscribers.addSubscriber(SubscriberOptions(10, SimulationPhase.Post), this::tick)
        }

        private fun tick(dt: Double, phase: SimulationPhase) {
            val targetSpeed = supplier.get()

            if(targetSpeed.approxEq(trackedSpeed, 0.01)) {
                return
            }

            trackedSpeed = targetSpeed
            consumer.onSpeedChanged(targetSpeed)
        }
    }
}

/**
 * The standard connection directions used for all work box cells.
 * */
val PROCESSING_CELL_POLE_MAP =  directionPoleMapPlanar(
    Base6Direction3d.Left,
    Base6Direction3d.Right
)

//#region Work Box Parameters

data class ProcessingCellThermalOptions(
    val massDef: ThermalMassDefinition,
    val leakageParameters: ConnectionParameters,
    val destroyTemperature: Quantity<Temperature>,
)

/**
 * Work box modeled as a friction load.
 * @param idleFriction The friction of the node when not processing.
 * @param runningFriction The friction of the node when processing. This models the load.
 * @param nominalAngularVelocity The angular velocity where the device is running at 100% speed.
 * @param maxTorque Torque breaking limit.
 * */
data class KineticProcessingCellOptions(
    val baseSpeedFactor: Double,
    val inertia: Quantity<Inertia>,
    val idleFriction: NodeFrictionDescription,
    val runningFriction: NodeFrictionDescription,
    val nominalAngularVelocity: Quantity<AngularVelocity>,
    val kineticBreakdownVelocity: Quantity<AngularVelocity>,
    val maxTorque: Quantity<Torque>,
    val thermal: ProcessingCellThermalOptions,
)

/**
 * Work box modeled as an electrical motor.
 * @param baseSpeedFactor Base efficiency of the box. Better boxes have larger values.
 * @param idleResistance The armature resistance when not active.
 * @param loadDependentFriction Omega-dependent friction, that simulates the load.
 * @param omegaThreshold If the internal motor's angular velocity is below this, processing speed is 0.
 * @param omegaNominal At this angular velocity, speed is 1.
 * */
data class MotorProcessingCellOptions(
    val baseSpeedFactor: Double,
    val inertia: Quantity<Inertia>,
    val idleResistance: Quantity<Resistance>,
    val armatureResistance: Quantity<Resistance>,
    val armatureInductance: Quantity<Inductance>,
    val backEmfConstant: Quantity<MotorBackEmfConstant>,
    val torqueConstant: Quantity<MotorTorqueConstant>,
    val loadDependentFriction: Double,
    val omegaThreshold: Double,
    val omegaNominal: Double,
    val dielectricBreakdownPotential: Quantity<Potential>,
    val overPowerThreshold: Quantity<Power>,
    val thermal: ProcessingCellThermalOptions,
)

//#endregion

//#region Work Box Implementation

enum class ProcessingCellType {
    Kinetic,
    Electrical
}

/**
 * Represents a registered box cell with an associated item.
 * @param boxType The domain of the implementation (kinetic or electrical).
 * @param cellProvider The registered cell.
 * @param item The associated item.
 * @param prefixToApply A prefix applied to the machine hull, to get the ID of the machine + work box.
 * */
class ProcessingCellRegistryItem<C : ProcessingCell>(
    val boxType: ProcessingCellType,
    val cellProvider: RegistryObject<CellProvider<C>>,
    val item: RegistryObject<Item>,
    val prefixToApply: String,
)

/**
 * Kinetic work box. Accepts kinetic connections and models the consumption as friction.
 * */
class KineticProcessingCell private constructor(ci: CellCreateInfo, val options: KineticProcessingCellOptions) : ProcessingCell(ci), SidedKineticMapped<KineticProcessingCell> {
    companion object {
        fun register(name: String, prefix: String, options: KineticProcessingCellOptions) : ProcessingCellRegistryItem<KineticProcessingCell> {
            val cell = CellRegistry.cellImmediate(name) {
                KineticProcessingCell(it, options)
            }

            val item = ItemRegistry.item(name) {
                Item(Item.Properties())
            }

            return ProcessingCellRegistryItem(ProcessingCellType.Kinetic, cell, item, prefix)
        }
    }

    override val kineticMap: PoleMap
        get() = PROCESSING_CELL_POLE_MAP

    override val kineticSize: KineticSize
        get() = KineticSize.Standard

    @SimObject
    val thermalWire = ThermalWireObject(
        this,
        options.thermal.massDef(),
        options.thermal.leakageParameters
    )

    @SimObject
    val kinetic = KineticProcessingObject(this)

    override val kineticState: RotatingKineticState
        get() = RotatingKineticState(kinetic.node.angle, kinetic.node.angularVelocity)

    @Replicator
    fun speedReplicator(target: ProcessingMachineBlockEntity<*>) = ProcessingCellSpeedReplicator(
        this::processingSpeed,
        target
    )

    @Replicator
    fun kineticReplicator(target: InternalKineticStateConsumer) = InternalKineticReplicatorBehavior(
        this::kineticState,
        target,
        this,
        kinetic.node::simulation
    )

    @Behavior
    val thermalBreakdown = ThermalBreakdownBehavior.create(
        options.thermal.destroyTemperature,
        this,
        thermalWire.thermalBody::temperature
    )

    @Behavior
    val kineticBreakdown = KineticBreakdownBehavior.create(options.kineticBreakdownVelocity, this, kinetic.node)

    @Behavior
    val stress = KineticStressBehavior.create(options.maxTorque, this, kinetic.node)

    class KineticProcessingObject(cell: KineticProcessingCell) : KineticObject<KineticProcessingCell>(cell), PersistentObject {
        val node = KineticDouble()

        init {
            node.inertia = !cell.options.inertia
            node.setSafeTorque(cell.options.maxTorque)
            cell.options.idleFriction.applyTo(node)
        }

        override fun offerExtension(remote: KineticObject<*>) = node.chooseExtension(cell.kineticMap, remote)

        override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
            subscribers.addPre(this::tick)
        }

        override fun addNodes(builder: KineticNodeSet) {
            builder.add(node)
        }

        /**
         * Converts the input power into some thermal power and updates the [processingSpeed].
         * */
        private fun tick(dt: Double, subscriberPhase: SimulationPhase) {
            val options = cell.options

            /**
             * Converts a fraction of the friction into heat:
             * */
            val energy = cell.thermalFactor * node.deltaHeatFromFriction

            if(!energy.approxEq(0.0)) {
                cell.thermalWire.thermalBody.energy += Quantity(energy, JOULE)
                cell.setChanged()
            }

            if(!cell.isActive) {
                cell.processingSpeed = 0.0
                cell.options.idleFriction.applyTo(node)
                return
            }

            /**
             * Applies the load factor:
             * */
            val parameter = cell.options.runningFriction.copy(
                damping = cell.options.runningFriction.damping * cell.loadFactor
            )

            parameter.applyTo(node)

            cell.processingSpeed = options.baseSpeedFactor * (abs(node.angularVelocity) / !options.nominalAngularVelocity)

            cell.direction = if(node.angularVelocity >= 0.0) {
                ProcessingCell.Direction.Forward
            }
            else {
                ProcessingCell.Direction.Reverse
            }
        }

        override fun saveObjectNbt() = node.saveNbt()
        override fun loadObjectNbt(tag: CompoundTag) = node.loadNbt(tag)
    }
}

/**
 * DC motor work box. Accepts electrical connections and models the consumption as the internal inefficiency of the motor, plus velocity-dependent friction.
 * */
class MotorProcessingCell private constructor(ci: CellCreateInfo, val options: MotorProcessingCellOptions) : ProcessingCell(ci), SidedElectricalMapped<MotorProcessingCell> {
    companion object {
        fun register(name: String, prefix: String, options: MotorProcessingCellOptions) : ProcessingCellRegistryItem<MotorProcessingCell> {
            val cell = CellRegistry.cellImmediate(name) {
                MotorProcessingCell(it, options)
            }

            val item = ItemRegistry.item(name) {
                Item(Item.Properties())
            }

            return ProcessingCellRegistryItem(ProcessingCellType.Electrical, cell, item, prefix)
        }
    }

    override val electricalMap: PoleMap
        get() = PROCESSING_CELL_POLE_MAP

    override val electricalSize: ElectricalSize
        get() = ElectricalSize.Any

    @SimObject
    val thermalWire = ThermalWireObject(
        this,
        options.thermal.massDef(),
        options.thermal.leakageParameters
    )

    @SimObject
    val motor = MotorProcessingObject(this)

    override val kineticState: RotatingKineticState
        get() = RotatingKineticState(motor.angle, motor.angularVelocity)

    @Replicator
    fun speedReplicator(target: ProcessingMachineBlockEntity<*>) = ProcessingCellSpeedReplicator(
        this::processingSpeed,
        target
    )

    @Replicator
    fun kineticReplicator(target: InternalKineticStateConsumer) = InternalKineticReplicatorBehavior(
        this::kineticState,
        target,
        this,
        null
    )

    @Behavior
    val temperatureExplosion = ThermalBreakdownBehavior.create(
        options.thermal.destroyTemperature,
        this,
        thermalWire.thermalBody::temperature
    )

    @Behavior
    val dielectricBreakdown = DielectricBreakdownBehavior.create(this).also {
        it.addPort(
            motor.armatureResistor,
            !options.dielectricBreakdownPotential,
            !options.dielectricBreakdownPotential
        )
    }

    @Behavior
    val overPower = OverPowerBehavior.create(options.overPowerThreshold, this) {
        motor.armatureResistor.power
    }

    class MotorProcessingObject(cell: MotorProcessingCell) : ElectricalObject<MotorProcessingCell>(cell), PersistentObject {
        val armatureResistor = LinearDiode()
        val armatureInductor = Inductor()
        val potentialSource = PotentialSource()

        /**
         * Emulated angle of the simulated motor (for rendering).
         * */
        var angle = 0.0

        /**
         * Angular velocity of the simulated motor.
         * */
        var angularVelocity = 0.0

        init {
            armatureResistor.forwardResistance = !cell.options.idleResistance
            armatureResistor.reverseResistance = ElectricalSimulation.MAX_RESISTANCE
            armatureInductor.inductance = !cell.options.armatureInductance
            potentialSource.potential = 0.0
        }

        override fun offerPolar(remote: ElectricalObject<*>) = when(cell.electricalMap.evaluate(cell, remote.cell)) {
            Pole.Positive -> armatureResistor.negative
            Pole.Negative -> potentialSource.negative
        }

        override fun addComponents(circuit: ElectricalComponentSet) {
            circuit.add(armatureResistor, armatureInductor, potentialSource)
        }

        override fun build(map: ElectricalConnectivityMap) {
            super.build(map)

            map.join(armatureResistor.positive, armatureInductor.positive)
            map.join(armatureInductor.negative, potentialSource.positive)
        }

        override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
            subscribers.addPre(this::tickPre)
            subscribers.addPost(this::tickPost)
        }

        /**
         * Applies load friction, sets the armature resistance and Back-EMF.
         * */
        private fun tickPre(dt: Double, phase: SimulationPhase) {
            val options = cell.options

            /**
             * Applies friction (load):
             * */
            val loadTorque = angularVelocity * (options.loadDependentFriction * cell.loadFactor)
            var dw = loadTorque / !options.inertia * dt

            /**
             * Limits explicit step:
             * */
            dw = if(angularVelocity < 0.0) {
                max(dw, angularVelocity)
            } else {
                min(dw, angularVelocity)
            }

            if(!dw.approxEq(0.0)) {
                cell.setChanged()
                angularVelocity -= dw

                /**
                 * Converts a fraction of the consumed energy into heat:
                 * */
                cell.thermalWire.thermalBody.energy += Quantity(cell.thermalFactor * (0.5 * !options.inertia * (dw * dw)))
            }

            /**
             * On-off switch:
             * */
            armatureResistor.forwardResistance = if(cell.isActive) {
                !options.armatureResistance
            }
            else {
                !options.idleResistance
            }

            /**
             * Back-EMF:
             * */
            potentialSource.potential = !options.backEmfConstant * angularVelocity
        }

        /**
         * Applies the motor torque, and calculates the processing speed.
         * */
        private fun tickPost(dt: Double, phase: SimulationPhase) {
            val options = cell.options

            /**
             * Integrates the resistive heating:
             * */
            cell.thermalWire.thermalBody.energy += Quantity(abs(armatureResistor.power) * dt)

            /**
             * Torque for the current across the device:
             * */
            val torque = armatureResistor.current * !options.torqueConstant
            val dw = torque / !options.inertia * dt

            if(!dw.approxEq(0.0)) {
                cell.setChanged()
            }

            angularVelocity += dw

            cell.direction = if (angularVelocity >= 0.0) {
                ProcessingCell.Direction.Forward
            } else {
                ProcessingCell.Direction.Reverse
            }

            /**
             * Calculates the (unsigned) processing speed:
             * */
            cell.processingSpeed = if(abs(angularVelocity) < options.omegaThreshold) {
                0.0
            }
            else {
                map(
                    abs(angularVelocity),
                    options.omegaThreshold, options.omegaNominal,
                    0.0, 1.0
                )
            }

            val dTheta = angularVelocity * dt
            if(!dTheta.approxEq(0.0, 1e-5)) {
                angle += dTheta
                cell.setChanged()
            }
        }

        override fun saveObjectNbt() : CompoundTag {
            val tag = CompoundTag()

            tag.put(ARMATURE_RESISTOR, armatureResistor.saveNbt())
            tag.put(INDUCTOR, armatureInductor.saveNbt())
            tag.putDouble(ANGLE, angle)
            tag.putDouble(ANGULAR_VELOCITY, angularVelocity)

            return tag
        }

        override fun loadObjectNbt(tag: CompoundTag) {
            armatureResistor.loadNbt(tag.getCompound(ARMATURE_RESISTOR))
            armatureInductor.loadNbt(tag.getCompound(INDUCTOR))
            angle = tag.getDouble(ANGLE)
            angularVelocity = tag.getDouble(ANGULAR_VELOCITY)
        }

        companion object {
            private const val ARMATURE_RESISTOR = "diode"
            private const val INDUCTOR = "inductor"
            private const val ANGLE = "angle"
            private const val ANGULAR_VELOCITY = "angularVelocity"
        }
    }
}

//#endregion

/**
 * @param C The work box cell, which abstracts away as a [ProcessingCell].
 * */
abstract class ProcessingMachineBlock<C : ProcessingCell, BE : ProcessingMachineBlockEntity<C>>(val cell: RegistryObject<CellProvider<C>>) : UprightHorizontalDirectionCellBlock<C>() {
    override fun getCellProvider() = cell.get()

    abstract fun getTitle() : Component

    abstract fun createMenu(pBlockEntity: BE, pContainerId: Int, pPlayerInventory: Inventory) : AbstractContainerMenu

    @Deprecated("Deprecated in Java", ReplaceWith("true"))
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction) = true

    override fun <T : BlockEntity?> getTicker(pLevel: Level, pState: BlockState, pBlockEntityType: BlockEntityType<T?>): BlockEntityTicker<T> {
        return BlockEntityTicker { pLevel: Level?, _: BlockPos?, _: BlockState?, pBlockEntity: BlockEntity? ->
            if (pLevel == null || pBlockEntity == null) {
                return@BlockEntityTicker DEBUGGER_BREAK()
            }

            if (pBlockEntity !is ProcessingMachineBlockEntity<*>) {
                LOG.error(DEBUGGER_BREAK("Got $pBlockEntity instead of machine block entity"))
                return@BlockEntityTicker
            }

            if (pLevel.isClientSide) {
                pBlockEntity.clientTick()
            }
            else {
                pBlockEntity.serverTick()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun use(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult,
    ): InteractionResult {
        return pLevel.constructMenuHelper2<BlockEntity>(
            pPos,
            pPlayer,
            getTitle()
        ) { a, b, c ->
            @Suppress("UNCHECKED_CAST")
            createMenu(a as BE, b, c)
        }
    }

    /**
     * Called by [animateTick], to animate particles.
     * @param avSpeed Smooth speed from [ProcessingMachineBlockEntity.ClientState.avSpeedSmoother].
     * */
    open fun animateMachineTick(blockEntity: BE, avSpeed: Double, pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) { }

    override fun animateTick(pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) {
        @Suppress("UNCHECKED_CAST") val blockEntity = pLevel.getBlockEntity(pPos) as? BE
            ?: return

        val speed = blockEntity.clientState!!.avSpeedSmoother.value

        if(speed < 0.01) {
            return
        }

        animateMachineTick(blockEntity, speed, pState, pLevel, pPos, pRandom)
    }

    //#region Collider

    open fun getCollider(pState: BlockState, pLevel: BlockGetter, pPos: BlockPos, pContext: CollisionContext) : VoxelShape? = null

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getCollisionShape(
        pState: BlockState,
        pLevel: BlockGetter,
        pPos: BlockPos,
        pContext: CollisionContext,
    ): VoxelShape = getCollider(pState, pLevel, pPos, pContext) ?: super.getCollisionShape(pState, pLevel, pPos, pContext)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getShape(
        pState: BlockState,
        pLevel: BlockGetter,
        pPos: BlockPos,
        pContext: CollisionContext,
    ): VoxelShape = getCollider(pState, pLevel, pPos, pContext) ?: super.getShape(pState, pLevel, pPos, pContext)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getVisualShape(
        pState: BlockState,
        pLevel: BlockGetter,
        pPos: BlockPos,
        pContext: CollisionContext,
    ): VoxelShape = getCollider(pState, pLevel, pPos, pContext) ?: super.getVisualShape(pState, pLevel, pPos, pContext)

    //#endregion
}

/**
 * Base machine with no processing logic. It only has replication logic and sound logic (supports a single looping sound, see [getSound]).
 * */
abstract class ProcessingMachineBlockEntity<C : ProcessingCell>(pPos: BlockPos, pState: BlockState) :
    CellBlockEntity<C>(pPos, pState, BlockRegistry.getBlockEntityType(pState.block).get()),
    BulkPacketHandlerBlockEntity,
    InternalKineticStateConsumer,
    ComponentDisplay
{
    //#region Client State

    /**
     * Created in [setLevel] on the client side.
     * */
    var clientState: ClientState? = null
        private set

    @ClientOnly
    class ClientState {
        /**
         * The raw processing speed, sent by the replicator.
         * */
        var targetClientSpeed = 0.0

        /**
         * The raw kinetic state, sent by the replicator.
         * */
        var targetClientKineticState: RotatingKineticState = RotatingKineticState(0.0, 0.0)

        var renderVersion = 0

        /**
         * Set in the first [clientTick], if [getSound] returns a non-null sound event.
         * */
        var soundInstance: SimpleLoopingBlockEntitySoundInstance<BlockEntity>? = null

        /**
         * Smoother updated in [clientTick] using [ClientState.targetClientSpeed], for **audio and particles effects only**.
         * */
        val avSpeedSmoother = FramerateIndependentSmoother1d(0.2)
    }

    /**
     * Sets up the [clientState].
     * */
    override fun setLevel(pLevel: Level) {
        if(pLevel.isClientSide) {
            clientState = ClientState()
        }

        super.setLevel(pLevel)
    }

    //#endregion

    /**
     * Called on the first [clientTick].
     * If this machine uses a single, speed-based looping sound, return the sound here, and it will be played and controlled with [ClientState.avSpeedSmoother].
     * */
    open fun getSound() : RegistryObject<SoundEvent>? = null

    //#region Tick Loops

    @ServerOnly
    open fun serverTick() { }

    /**
     * Method called by [clientTick], used to update A/V, if needed.
     * @param avSpeed The smoothed speed, from [ClientState.avSpeedSmoother].
     * */
    open fun animateClientTick(dt: Double, state: ClientState, avSpeed: Double, level: ClientLevel) { }

    @ClientOnly
    fun clientTick() {
        val state = clientState!!
        val dt = state.avSpeedSmoother.update(clientState!!.targetClientSpeed)

        if(state.avSpeedSmoother.value < 0.01) {
            return
        }

        val level = level as? ClientLevel
            ?: return

        if(clientState!!.soundInstance == null) {
            val soundEvent = getSound()

            if(soundEvent != null) {
                clientState!!.soundInstance = SimpleLoopingBlockEntitySoundInstance(this as BlockEntity, soundEvent.get()).also {
                    it.events.registerHandler<SoundInstanceTickEvent> { _ ->
                        it.soundInfo = SoundInfo.standardWithProcessingSpeed(state.avSpeedSmoother.value)
                    }

                    it.registerOnAudioManager()
                }
            }
        }

        animateClientTick(dt, state, state.avSpeedSmoother.value, level)
    }

    //#endregion

    //#region Replication

    override val clientSidePacketHandlerLazy = createClientSideHandler()

    // onSyncSuggested
    @ServerOnly
    override fun getUpdateTag(): CompoundTag {
        if(hasCell) {
            this.sendBulkPacket(SpeedSyncPacket(cell.processingSpeed))
            this.sendBulkPacket(cell.kineticState)
        }

        return super.getUpdateTag()
    }

    open fun onSyncSuggested() { }

    @ClientOnly
    override fun setupPacketsOnClient(handler: ClientSidePacketHandlerBuilder) {
        fun modifyState(block: ClientState.() -> Unit) {
            val state = clientState!!
            block.invoke(state)
            state.renderVersion++
        }

        handler.withHandler<SpeedSyncPacket> { packet ->
            modifyState {
                targetClientSpeed = packet.speed
            }
        }

        handler.withHandler<RotatingKineticState> { state ->
            modifyState {
                targetClientKineticState = state
            }
        }
    }

    @OnSimulationThread
    fun onSpeedChanged(newSpeed: Double) {
        sendBulkPacket(SpeedSyncPacket(newSpeed))
    }

    @OnSimulationThread
    override fun onKineticStateChanged(state: RotatingKineticState) {
        sendBulkPacket(state)
    }

    @Serializable
    class SpeedSyncPacket(val speed: Double)

    //#endregion

    override fun submitDisplay(builder: ComponentDisplayList) {
        val cell = cell

        if(cell is KineticProcessingCell) {
            cell.kinetic.subSolvers?.debugInIDE(builder)
            builder.debugInIDE { "Speed: ${cell.processingSpeed.rounded()}" }
            builder.debugInIDE { "Imp0: ${cell.kinetic.node.e1.impulse.rounded()}, Imp1: ${cell.kinetic.node.e2.impulse.rounded()}" }
            builder.debugInIDE { "Fric T: ${cell.kinetic.node.frictionTorque.rounded()}"}
            builder.quantity(cell.thermalWire.thermalBody.temperature)
        }
        else if(cell is MotorProcessingCell) {
            builder.debugInIDE { "Angular velocity: ${cell.motor.angularVelocity.rounded()}" }
            builder.debugInIDE { "Speed: ${cell.processingSpeed.rounded()}" }
            builder.debugInIDE { "Back-EMF: ${cell.motor.potentialSource.potential.rounded()}" }
            builder.debugInIDE { "Resistor power: ${cell.motor.armatureResistor.power.rounded()}"}
            builder.quantity(cell.thermalWire.thermalBody.temperature)
        }
    }
}

/**
 * Work box machine with a simple item processing loop.
 * @param inventorySize The size of the created inventory handler.
 * */
abstract class SimpleProcessingMachineBlockEntity<C : ProcessingCell, R : Eln2SimpleOutputProcessingLoopRecipe>(
    pPos: BlockPos,
    pState: BlockState,
    val inventorySize: Int
) : ProcessingMachineBlockEntity<C>(pPos, pState) {
    /**
     * Gets the recipe applied by the machine. Must return the same value each time.
     * */
    abstract val recipe: RecipeType<R>

    /**
     * Gets the slots holding the input items, that will be consumed.
     * */
    open val inputSlots: IntArray = intArrayOf(INPUT_SLOT)

    //#region Capability

    val inventoryHandler = SimpleProcessingRecipeInventoryHandler.create(this, recipe, inventorySize, inputSlots)
    val inventoryHandlerLazy: LazyOptional<SimpleProcessingRecipeInventoryHandler<R>> = LazyOptional.of { inventoryHandler }

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return inventoryHandlerLazy.cast()
        }

        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        inventoryHandlerLazy.invalidate()
    }

    //#endregion

    //#region Logic

    /**
     * Container with the progress parameter for the GUI.
     * */
    @ServerOnly
    val data = ProgressContainerData()

    /**
     * The server state of the processing.
     * */
    @ServerOnly
    val loop = ProcessingRecipeLoop.create<R>(this)

    /**
     * Updates the loop and sets the progress of [data].
     * */
    @ServerOnly
    override fun serverTick() {
        val result = loop.tick(cell, inventoryHandler)

        data.progress = result.progress
    }

    //#endregion

    //#region Saving

    @ServerOnly
    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)
        pTag.put("inventory", inventoryHandler.serializeNBT())
        loop.saveAdditional(pTag)
    }

    @ServerOnly
    override fun load(pTag: CompoundTag) {
        super.load(pTag)
        inventoryHandler.deserializeNBT(pTag.getCompound("inventory"))
        loop.load(pTag)
    }

    //#endregion
}

/**
 * @param body The base model of the machine, same for both kinetic and electric variants.
 * @param elements Animated rotating elements (currently constrained to rotating around one axis, will upgrade if needed).
 * */
class ProcessingMachineCompositeModel(val body: PartialModel, val elements: List<RotatingElement>) {
    /**
     * Represents a wheel or gear that is rotating based on the processing speed of the machine.
     * @param model The model, in the desired position and orientation in the model space.
     * @param factor Angular velocity multiplier. The perceived angular velocity of the element will be [factor]*`speed`, where `speed` is the processing speed of the machine.
     * */
    class RotatingElement(val model: PartialModel, val factor: Double) {
        val center = FlwModels.getModelCenter(model)
    }
}

/**
 * Handles rendering both kinetic and electrical variants of work box machines.
 * For kinetic variants, a shaft instance is automatically created and animated. For electrical variants, a wire instance is automatically created.
 * @param workBox The domain of the work box cell.
 * @param composite The model, containing the hull and the extra animated elements.
 * */
open class ProcessingMachineBlockEntityVisual<C : ProcessingCell, BE : ProcessingMachineBlockEntity<C>>(
    ctx: VisualizationContext,
    blockEntity: BE,
    partialTick: Float,
    workBox: ProcessingCellType,
    composite: ProcessingMachineCompositeModel
) : AbstractBlockEntityVisual<BE>(ctx, blockEntity, partialTick), SimpleDynamicVisual, ShaderLightVisual {
    companion object {
        val SHAFT_CENTER = FlwModels.getModelCenter(FlwModels.WORK_BOX_COMPOSITE_SHAFT)
    }

    /**
     * The body instance, created regardless of work box type.
     * */
    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(
            InstanceTypes.TRANSFORMED,
            PartialModelHelper.applyMaterial(
                composite.body,
                FlwMaterials.SMOOTH_LIT
            )
        )
        .createInstance()
        .transformFacingBlock(visualPos, blockEntity)

    interface WorkboxSpecificData {
        val instance: TransformedInstance
    }

    class KineticData(override val instance: TransformedInstance) : WorkboxSpecificData {
        var version = 0
        val interpolator = KineticInterpolatorClient()
    }

    class ElectricalData(override val instance: TransformedInstance) : WorkboxSpecificData

    val specificData: WorkboxSpecificData = when(workBox) {
        ProcessingCellType.Kinetic -> {
            KineticData(
                visualizationContext.instancerProvider()
                    .instancer(
                        InstanceTypes.TRANSFORMED,
                        PartialModelHelper.applyMaterial(
                            FlwModels.WORK_BOX_COMPOSITE_SHAFT,
                            FlwMaterials.SMOOTH_LIT
                        )
                    )
                    .createInstance().also {
                        poseShaft(it, SHAFT_CENTER, 0.0)
                    }
            )
        }
        ProcessingCellType.Electrical -> {
            ElectricalData(
                visualizationContext.instancerProvider()
                    .instancer(
                        InstanceTypes.TRANSFORMED,
                        PartialModelHelper.applyMaterial(
                            FlwModels.WORK_BOX_COMPOSITE_CABLE,
                            FlwMaterials.SMOOTH_LIT
                        )
                    )
                    .createInstance()
                    .transformFacingBlock(visualPos, blockEntity)
            )
        }
    }

    class RotatingElement(val instance: TransformedInstance, val center: Vector3d, val factor: Double)

    /**
     * Instances of the animated rotating elements.
     * */
    val rotatingElements = composite.elements.map { element ->
        val instance = visualizationContext.instancerProvider()
            .instancer(
                InstanceTypes.TRANSFORMED,
                PartialModelHelper.applyMaterial(
                    element.model,
                    FlwMaterials.SMOOTH_LIT
                )
            )
            .createInstance().also {
                poseShaft(it, element.center, 0.0)
            }

        RotatingElement(instance, element.center, element.factor)
    }.toTypedArray()

    /**
     * Rotation and smoother for the [ProcessingMachineBlockEntity.ClientState.targetClientSpeed], which is used for the rotating elements in [ProcessingMachineCompositeModel.elements].
     * */
    var processRotation = Rotation2d.identity
    val processSpeedSmoother = FramerateIndependentSmoother1d(0.1)

    open fun executeFrame(dt: Double, renderState: ProcessingMachineBlockEntity.ClientState, processSpeed: Double) { }

    final override fun beginFrame(p0: DynamicVisual.Context?) {
        val renderState = blockEntity.clientState!!
        val processSpeed = renderState.targetClientSpeed

        val dt = processSpeedSmoother.update(processSpeed)
        processSpeedSmoother.pullDown()
        val processIncr = processSpeedSmoother.value * dt

        if(processIncr != 0.0) {
            processRotation += processIncr
            val angle = processRotation.ln()

            for(i in 0 until rotatingElements.size) {
                val element = rotatingElements[i]

                poseShaft(element.instance, element.center, angle * element.factor)
            }
        }

        val specificData = specificData
        if(specificData is KineticData) {
            val targetVersion = renderState.renderVersion
            if(specificData.version != targetVersion) {
                specificData.version = targetVersion

                specificData.interpolator.applyServerState(
                    renderState.targetClientKineticState.angle,
                    renderState.targetClientKineticState.angularVelocity
                )
            }

            specificData.interpolator.update()
            poseShaft(specificData.instance, SHAFT_CENTER, specificData.interpolator.clientRotation.ln())
        }

        executeFrame(dt, renderState, processSpeed)
    }

    override fun updateLight(p0: Float) {
        relight(body, specificData.instance)
        relight(rotatingElements.map { it.instance })
    }

    override fun collectCrumblingInstances(p0: Consumer<Instance?>) {
        p0.accept(body)
        p0.accept(specificData.instance)

        rotatingElements.forEach {
            p0.accept(it.instance)
        }
    }

    override fun _delete() {
        body.delete()
        specificData.instance.delete()

        rotatingElements.forEach {
            it.instance.delete()
        }
    }

    fun poseShaft(instance: TransformedInstance, center: Vector3d, rotation: Double) {
        val z = center.z
        val y = center.y

        instance.setIdentityTransform()
            .translate(visualPos)
            .center()
            .rotateToFace(blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING))
            .uncenter()
            .translate(0.0, y, z)
            .rotateX(rotation.toFloat())
            .translate(0.0, -y, -z)
            .setChanged()
    }
}
