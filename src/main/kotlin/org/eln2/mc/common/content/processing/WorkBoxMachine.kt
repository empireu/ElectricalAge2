package org.eln2.mc.common.content.processing

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
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
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
import org.eln2.mc.common.containers.ProgressContainerData
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
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Abstraction for (single-block) machines that have both kinetic and electrical variants, and possibly multiple tiers of those variants.
 * The machine implements one block and one block entity, that has all the needed recipe and A/V logic.
 * It controls everything using [processingSpeed].
 *
 * To implement multiple variants, we define generic "work boxes", which are cells such as [KineticWorkBoxCell] and [MotorWorkBoxCell], with different parameters (tiers).
 * These boxes are then wrapped in a craftable item, that is reused for all machines.
 * Then, we register the specific block and block entity for our machine with the work box cells (by changing the cell provider).
 * Finally, we add a recipe for the base machine, that isn't placeable and doesn't work by itself, but we define a recipe taking the base machine and one of the work boxes, and this creates one of those registered variants which we can place and use.
 * TODO I will implement this automatically with datagen, gotta update this documentation
 * */
abstract class WorkBox(ci: CellCreateInfo) : Cell(ci), ProcessingDevice {
    /**
     * Read by the game object and used for rendering. For the kinetic work box, this should match the node exactly, so the shaft seems rigidly connected.
     * For the electrical variant, this should be some emulation of the internal motor's rotation, but it would usually be ignored and the speed would be used for any animations.
     * */
    @CrossThreadAccess @OnServerThread
    abstract val kineticState: RotatingKineticState
}

//#region Work Box Parameters

/**
 * @param thermalFactor `power` * [thermalFactor] of the power is converted into heat.
 * */
data class WorkBoxThermalOptions(
    val thermalFactor: Double,
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
    val thermal: WorkBoxThermalOptions,
)

/**
 * Work box modeled as an electrical motor.
 * @param baseSpeedFactor Base efficiency of the box. Better boxes have larger values.
 * @param idleResistance The armature resistance when not active.
 * @param dependentFriction Omega-dependent friction, that simulates the load.
 * @param omegaThreshold If the internal motor's angular velocity is below this, processing speed is 0.
 * @param omegaNominal At this angular velocity, speed is 1.
 * */
data class ElectricalMotorWorkBoxCellOptions(
    val baseSpeedFactor: Double,
    val inertia: Quantity<Inertia>,
    val idleResistance: Quantity<Resistance>,
    val armatureResistance: Quantity<Resistance>,
    val armatureInductance: Quantity<Inductance>,
    val backEmfConstant: Quantity<MotorBackEmfConstant>,
    val torqueConstant: Quantity<MotorTorqueConstant>,
    val dependentFriction: Double,
    val omegaThreshold: Double,
    val omegaNominal: Double,
    val dielectricBreakdownPotential: Quantity<Potential>,
    val overPowerThreshold: Quantity<Power>,
    val thermal: WorkBoxThermalOptions,
)

//#endregion

//#region Work Box Implementation

/**
 * Replicator for the processing speed (handled by [WorkBoxMachineBlockEntity.onSpeedChanged]).
 * */
class WorkBoxSpeedReplicator(val supplier: Supplier<Double>, val consumer: WorkBoxMachineBlockEntity<*>) : ReplicatorBehavior {
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

class KineticWorkBoxObject(cell: KineticWorkBoxCell) : KineticObject<KineticWorkBoxCell>(cell), PersistentObject {
    val node = KineticDouble()

    init {
        node.inertia = !cell.options.inertia
        node.setSafeTorque(cell.options.maxTorque)
        cell.options.idleFriction.applyTo(node)
    }

    override fun offerExtension(remote: KineticObject<*>) = node.chooseExtension(cell.kineticMap, remote)

    /**
     * The base grinding speed, calculated each tick.
     * */
    var processingSpeed = 0.0

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

        // Convert fraction of power to heat:
        val energy = node.deltaHeatFromFriction

        if(!energy.approxEq(0.0)) {
            cell.thermalWire.thermalBody.energy += Quantity(energy, JOULE)
            cell.setChanged()
        }

        if(!cell.isActive) {
            processingSpeed = 0.0
            cell.options.idleFriction.applyTo(node)
            return
        }

        cell.options.runningFriction.applyTo(node)

        processingSpeed = if(node.angularVelocity.approxEq(0.0)) {
            0.0
        } else {
            options.baseSpeedFactor * (abs(node.angularVelocity) / !options.nominalAngularVelocity)
        }
    }

    override fun saveObjectNbt() = node.saveNbt()
    override fun loadObjectNbt(tag: CompoundTag) = node.loadNbt(tag)
}

/**
 * Kinetic work box. Accepts kinetic connections and models the consumption as friction.
 * */
class KineticWorkBoxCell(ci: CellCreateInfo, val options: KineticProcessingCellOptions, override val kineticMap: PoleMap) : WorkBox(ci), SidedKineticMapped<KineticWorkBoxCell> {
    override val kineticSize: KineticSize
        get() = KineticSize.Standard

    override var isActive = false

    override val processingSpeed: Double
        get() = kinetic.processingSpeed

    @SimObject
    val thermalWire = ThermalWireObject(
        this,
        options.thermal.massDef(),
        options.thermal.leakageParameters
    )

    @SimObject
    val kinetic = KineticWorkBoxObject(this)

    override val kineticState: RotatingKineticState
        get() = RotatingKineticState(kinetic.node.angle, kinetic.node.angularVelocity)

    @Replicator
    fun speedReplicator(target: WorkBoxMachineBlockEntity<*>) = WorkBoxSpeedReplicator(
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
}

class MotorWorkBoxObject(cell: MotorWorkBoxCell) : ElectricalObject<MotorWorkBoxCell>(cell), PersistentObject {
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

    /**
     * The base grinding speed, calculated in [tickPost].
     * */
    var processingSpeed = 0.0

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
        if(angularVelocity > 0.0) {
            val torque = angularVelocity * options.dependentFriction

            // Applies friction for both directions, if I want to add backwards motion in the future:
            var dw = torque / !options.inertia * dt

            // Limit explicit step:
            dw = if(angularVelocity < 0.0) {
                max(dw, angularVelocity)
            } else {
                min(dw, angularVelocity)
            }

            if(!dw.approxEq(0.0)) {
                cell.setChanged()
            }

            angularVelocity -= dw
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

        // Torque for the current across the device:
        val torque = armatureResistor.current * !options.torqueConstant
        val dw = torque / !options.inertia * dt

        if(!dw.approxEq(0.0)) {
            cell.setChanged()
        }

        angularVelocity += dw

        if(angularVelocity < 0.0) {
            // In reverse. In the future, we can run the machine "backward", i.e. reverse auto item transfers.
            // For now, clip and lose energy:
            angularVelocity = 0.0
        }

        // Calculate processing speed:
        processingSpeed = if(angularVelocity < options.omegaThreshold) {
            0.0
        }
        else {
            map(
                angularVelocity,
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
        tag.putDouble(PROCESSING_SPEED, processingSpeed)

        return tag
    }

    override fun loadObjectNbt(tag: CompoundTag) {
        armatureResistor.loadNbt(tag.getCompound(ARMATURE_RESISTOR))
        armatureInductor.loadNbt(tag.getCompound(INDUCTOR))
        angle = tag.getDouble(ANGLE)
        angularVelocity = tag.getDouble(ANGULAR_VELOCITY)
        processingSpeed = tag.getDouble(PROCESSING_SPEED)
    }

    companion object {
        private const val ARMATURE_RESISTOR = "diode"
        private const val INDUCTOR = "inductor"
        private const val ANGLE = "angle"
        private const val ANGULAR_VELOCITY = "angularVelocity"
        private const val PROCESSING_SPEED = "speed"
    }
}

/**
 * DC motor work box. Accepts electrical connections and models the consumption as the internal inefficiency of the motor, plus velocity-dependent friction.
 * */
class MotorWorkBoxCell(ci: CellCreateInfo, val options: ElectricalMotorWorkBoxCellOptions, override val electricalMap: PoleMap) : WorkBox(ci), SidedElectricalMapped<MotorWorkBoxCell> {
    override val electricalSize: ElectricalSize
        get() = ElectricalSize.Any

    override var isActive = false

    override val processingSpeed: Double
        get() = motor.processingSpeed

    @SimObject
    val thermalWire = ThermalWireObject(
        this,
        options.thermal.massDef(),
        options.thermal.leakageParameters
    )

    @SimObject
    val motor = MotorWorkBoxObject(this)

    override val kineticState: RotatingKineticState
        get() = RotatingKineticState(motor.angle, motor.angularVelocity)

    @Replicator
    fun speedReplicator(target: WorkBoxMachineBlockEntity<*>) = WorkBoxSpeedReplicator(
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
}

//#endregion

/**
 * @param C The work box cell, which abstracts away as a [WorkBox].
 * */
abstract class WorkBoxMachineBlock<C : WorkBox, BE : WorkBoxMachineBlockEntity<C>>(val cell: RegistryObject<CellProvider<C>>) : UprightHorizontalDirectionCellBlock<C>() {
    override fun getCellProvider() = cell.get()

    abstract fun getTitle() : Component

    abstract fun createMenu(pBlockEntity: BE, pContainerId: Int, pPlayerInventory: Inventory) : AbstractContainerMenu

    @Deprecated("Deprecated in Java", ReplaceWith("true"))
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction) = true

    override fun <T : BlockEntity?> getTicker(pLevel: Level, pState: BlockState, pBlockEntityType: BlockEntityType<T?>): BlockEntityTicker<T> {
        return BlockEntityTicker {pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity? ->
            if (pLevel == null || pBlockEntity == null) {
                return@BlockEntityTicker DEBUGGER_BREAK()
            }

            if (pBlockEntity !is WorkBoxMachineBlockEntity<*>) {
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
     * @param avSpeed Smooth speed from [WorkBoxMachineBlockEntity.ClientState.avSpeedSmoother].
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
abstract class WorkBoxMachineBlockEntity<C : WorkBox>(pPos: BlockPos, pState: BlockState, pType: BlockEntityType<*>) :
    CellBlockEntity<C>(pPos, pState, pType),
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
         * Set in the first [org.eln2.mc.common.content.processing.WorkBoxMachineBlockEntity.clientTick], if [org.eln2.mc.common.content.processing.WorkBoxMachineBlockEntity.getSound] returns a non-null sound event.
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

        val pos = blockPos
            ?: return

        if(clientState!!.soundInstance == null) {
            val soundEvent = getSound()

            if(soundEvent != null) {
                clientState!!.soundInstance = SimpleLoopingBlockEntitySoundInstance(this as BlockEntity, soundEvent.get()).also {
                    it.events.registerHandler<SoundInstanceTickEvent> { e ->
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

        if(cell is KineticWorkBoxCell) {
            cell.kinetic.subSolvers?.debugInIDE(builder)
            builder.debugInIDE { "Speed: ${cell.kinetic.processingSpeed.rounded()}" }
            builder.debugInIDE { "Imp0: ${cell.kinetic.node.e1.impulse.rounded()}, Imp1: ${cell.kinetic.node.e2.impulse.rounded()}" }
            builder.debugInIDE { "Fric T: ${cell.kinetic.node.frictionTorque.rounded()}"}
            builder.quantity(cell.thermalWire.thermalBody.temperature)
        }
        else if(cell is MotorWorkBoxCell) {
            builder.debugInIDE { "Angular velocity: ${cell.motor.angularVelocity.rounded()}" }
            builder.debugInIDE { "Speed: ${cell.motor.processingSpeed.rounded()}" }
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
abstract class SimpleProcessingLoopWorkBoxMachineBlockEntity<C : WorkBox, R : Eln2SimpleOutputProcessingLoopRecipe>(
    pPos: BlockPos,
    pState: BlockState,
    pType: BlockEntityType<*>,
    val inventorySize: Int
) : WorkBoxMachineBlockEntity<C>(pPos, pState, pType) {
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
