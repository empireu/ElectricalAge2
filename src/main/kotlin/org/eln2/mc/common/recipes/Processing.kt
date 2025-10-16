package org.eln2.mc.common.recipes

import kotlinx.serialization.Serializable
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
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.mna.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.mna.component.updateResistance
import org.eln2.mc.*
import org.eln2.mc.common.blocks.foundation.CellBlock
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.containers.ProgressContainerData
import org.eln2.mc.common.content.ThermalWireObject
import org.eln2.mc.common.network.serverToClient.BulkPacketHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.sendBulkPacket
import org.eln2.mc.common.recipes.foundation.Eln2SimpleRecipe
import org.eln2.mc.common.recipes.foundation.INPUT_SLOT
import org.eln2.mc.common.recipes.foundation.ProcessingDevice
import org.eln2.mc.common.recipes.foundation.ProcessingRecipeLoop
import org.eln2.mc.common.recipes.foundation.SimpleProcessingRecipeInventoryHandler
import org.eln2.mc.common.sounds.foundation.SimpleLoopingBlockEntitySoundInstance
import org.eln2.mc.common.sounds.foundation.SoundInfo
import org.eln2.mc.common.sounds.foundation.SoundInstanceTickEvent
import org.eln2.mc.data.Pole
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.evaluate
import org.eln2.mc.extensions.constructMenuHelper2
import org.eln2.mc.extensions.debugInIDE
import org.eln2.mc.extensions.loadNbt
import org.eln2.mc.extensions.saveNbt
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import kotlin.math.abs
import kotlin.math.absoluteValue

/**
 * @param nominalPotential The target potential.
 * @param nominalPower The target power. It is delivered in the range of [[potentialThresholdFactor] * [nominalPotential], [maxRatedPotentialFactor] * [nominalPotential]].
 * @param potentialThresholdFactor The device doesn't run if the potential is negative or less than [potentialThresholdFactor] * [nominalPotential].
 * @param maxRatedPotentialFactor The device's efficiency starts to degrade and the power draw starts increasing when the potential is above [maxRatedPotentialFactor] * [nominalPotential].
 *
 * */
data class MotorProcessingCellElectricalOptions(
    val idleResistance: Quantity<Resistance>,
    val probingResistance: Quantity<Resistance>,
    val minResistance: Quantity<Resistance>,
    val nominalPotential: Quantity<Potential>,
    val nominalPower: Quantity<Power>,
    val potentialThresholdFactor: Double,
    val maxRatedPotentialFactor: Double,
    val dielectricBreakdownPotential: Quantity<Potential>,
    val overPowerThreshold: Quantity<Power>
)

/**
 * @param thermalFactor `power` * [thermalFactor] of the power is converted into heat.
 * */
data class ProcessingCellThermalOptions(
    val thermalFactor: Double,
    val massDef: ThermalMassDefinition,
    val leakageParameters: ConnectionParameters,
    val destroyTemperature: Quantity<Temperature>,
)

/**
 * Crusher model. If active (needs to crush):
 * - The device doesn't start (speed = 0) when the potential is under the threshold, but still draws power (tries to draw [org.eln2.mc.common.recipes.MotorProcessingCellElectricalOptions.nominalPower]).
 * - The device keeps ~[org.eln2.mc.common.recipes.MotorProcessingCellElectricalOptions.nominalPower] when the potential is in the specified range.
 * - Speed is proportional to input power.
 * - The device starts increasing in power draw like a resistor when the potential is over the specified range.
 * - A fraction of the input power is converted into heat, setting a hard cap on the amount of work you can do.
 * - The device is destroyed when it reaches a certain temperature, and also has dielectric breakdown.
 * @param thermal If applicable, the terms dictating the waste heat production of the machine.
 * @param baseSpeedFactor The device's raw speed is [baseSpeedFactor] * (`power` / [MotorProcessingCellElectricalOptions.nominalPower]).
 *  */
data class MotorProcessingCellOptions(
    val baseSpeedFactor: Double,
    val electrical: MotorProcessingCellElectricalOptions,
    val thermal: ProcessingCellThermalOptions?,
)

/**
 * Object emulating the behavior described in [MotorProcessingCellOptions] using a [TheveninEstimatingResistor].
 * Uses the Thevenin estimates to select the behavior based on the estimated open-circuit potential, and to create the constant load in the target regions.
 * */
class MotorProcessingElectricalObject(cell: MotorProcessingCell) : ElectricalObject<MotorProcessingCell>(cell) {
    val resistor = TheveninEstimatingResistor().also {
        it.resistance = !cell.options.electrical.idleResistance
    }

    val resistorDisplay = resistor.display()

    override fun offerPolar(remote: ElectricalObject<*>) = when(cell.electricalMap.evaluate(cell, remote.cell)) {
        Pole.Plus -> resistor.offerPositive()
        Pole.Minus -> resistor.offerNegative()
    }

    /**
     * The base grinding speed, calculated each tick.
     * */
    var processingSpeed = 0.0

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::tick)
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        super.addComponents(circuit)

        circuit.add(resistor)
    }

    /**
     * Converts the input power into some thermal power and updates the [processingSpeed].
     * */
    private fun tick(dt: Double, subscriberPhase: SubscriberPhase) {
        val options = cell.options

        if(options.thermal != null) {
            // Convert fraction of power to heat:
            if(resistor.potential > 0.0 && resistor.power > 0.0) {
                val energy = options.thermal.thermalFactor * resistor.power * dt

                if(!energy.approxEq(0.0)) {
                    cell.thermalWire!!.thermalBody.energy += Quantity(energy, JOULE)
                    cell.setChanged()
                }
            }
        }

        fun setLoad(power: Double) {
            resistor.setLoad(power, !options.electrical.minResistance, !options.electrical.probingResistance)
        }

        if(!cell.isActive) {
            processingSpeed = 0.0
            resistor.resistance = !options.electrical.idleResistance
            return
        }

        val potential = resistor.openCircuitPotentialEstimate

        if(potential <= 0.0) {
            processingSpeed = 0.0
            setLoad(0.0)
            return
        }

        val potentialThreshold = options.electrical.potentialThresholdFactor * !options.electrical.nominalPotential

        val isGrinding: Boolean

        if(potential < potentialThreshold) {
            // Draw nominal power even if we can't drive the grinding.
            setLoad(!options.electrical.nominalPower)
            isGrinding = false
        }
        else {
            val maxPotential = options.electrical.maxRatedPotentialFactor * !options.electrical.nominalPotential

            if(potential > maxPotential) {
                // Over-potential. Act like a resistor now:
                val r = ((maxPotential * maxPotential) / !options.electrical.nominalPower).coerceAtLeast(1e-6)
                resistor.updateResistance(r)
            }
            else {
                // Operating within margin. Consume ~nominalPower:
                setLoad(!options.electrical.nominalPower)
            }

            isGrinding = true
        }

        processingSpeed = if (isGrinding) {
            options.baseSpeedFactor * (resistor.power.absoluteValue / !options.electrical.nominalPower)
        } else {
            0.0
        }
    }
}

class MotorProcessingCell(
    ci: CellCreateInfo,
    val options: MotorProcessingCellOptions,
    override val electricalMap: PoleMap,
    override val thermalMap: PoleMap
) : Cell(ci), SidedElectricalMapped<MotorProcessingCell>, SidedThermalMapped<MotorProcessingCell>, ProcessingDevice {
    override val electricalSize: ElectricalSize
        get() = ElectricalSize.Any

    override val thermalSize: ThermalSize
        get() = ThermalSize.Any

    override var isActive = false

    override val processingSpeed: Double
        get() = motor.processingSpeed

    @SimObject
    val thermalWire = if(options.thermal != null) {
        ThermalWireObject(
            this,
            options.thermal.massDef(),
            options.thermal.leakageParameters
        )
    }
    else null

    @SimObject
    val motor = MotorProcessingElectricalObject(this)

    @Behavior
    val temperatureExplosion = if(options.thermal != null) {
        ThermalBreakdownBehavior.create(
            options.thermal.destroyTemperature,
            this,
            thermalWire!!.thermalBody::temperature
        )
    }
    else null

    @Behavior
    val dielectricBreakdown = DielectricBreakdownBehavior.create(this).also {
        it.addPort(
            motor.resistor,
            !options.electrical.dielectricBreakdownPotential,
            !options.electrical.dielectricBreakdownPotential
        )
    }

    val overPower = OverPowerBehavior.create(options.electrical.overPowerThreshold, this) {
        motor.resistor.power
    }
}

abstract class ProcessingBlock<R, C, BE> : CellBlock<C>()
    where R : Eln2SimpleRecipe, R : Recipe<SimpleContainer>,
          C : Cell, C : ProcessingDevice,
          BE : ProcessingBlockEntity<R, C>
{
    abstract fun getTitle() : Component

    abstract fun createMenu(
        pBlockEntity: BE,
        pContainerId: Int,
        pPlayerInventory: Inventory
    ) : AbstractContainerMenu

    @Deprecated("Deprecated in Java", ReplaceWith("true"))
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean {
        return true
    }

    fun tick(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
        ProcessingBlockEntity.tick(pLevel, pPos, pState, pBlockEntity)
    }

    override fun <T : BlockEntity?> getTicker(pLevel: Level, pState: BlockState, pBlockEntityType: BlockEntityType<T?>): BlockEntityTicker<T> {
        return BlockEntityTicker(this::tick)
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

    open fun animateMachineTick(blockEntity: BE, speed: Double, pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) { }

    override fun animateTick(pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) {
        @Suppress("UNCHECKED_CAST") val blockEntity = pLevel.getBlockEntity(pPos) as? BE
            ?: return

        val speed = blockEntity.clientTickSpeedSmoother.value

        if(speed < 0.01) {
            return
        }

        animateMachineTick(blockEntity, speed, pState, pLevel, pPos, pRandom)
    }
}

abstract class ProcessingBlockEntity<R, C>(
    pos: BlockPos,
    state: BlockState,
    targetType: BlockEntityType<*>,
    inventorySize: Int
) : CellBlockEntity<C>(pos, state, targetType),
    ComponentDisplay,
    BulkPacketHandlerBlockEntity
    where R : Eln2SimpleRecipe, R : Recipe<SimpleContainer>, C : Cell, C : ProcessingDevice
{
    companion object {
        private const val INVENTORY = "inventory"

        fun tick(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
            if (pLevel == null || pBlockEntity == null) {
                LOG.error("level or entity null")
                return
            }

            if (pBlockEntity !is ProcessingBlockEntity<*, *>) {
                LOG.error(DEBUGGER_BREAK("Got $pBlockEntity instead of processing block entity"))
                return
            }

            if (pLevel.isClientSide) {
                pBlockEntity.clientTick()
            }
            else {
                pBlockEntity.serverTick()
            }
        }
    }

    val data = ProgressContainerData()

    //#region Client State

    @ClientOnly
    var targetClientSpeed = 0.0

    // Updated in [clientTick] for audio and particles:
    @ClientOnly
    val clientTickSpeedSmoother = FramerateIndependentSmoother1d(0.2)

    @ClientOnly
    var soundInstance: SimpleLoopingBlockEntitySoundInstance<ProcessingBlockEntity<R, C>>? = null

    //#endregion

    @ServerOnly
    val loop = ProcessingRecipeLoop.create<R>(this)

    abstract fun getRecipe() : RecipeType<R>

    val inventoryHandler = SimpleProcessingRecipeInventoryHandler.create(
        this,
        getRecipe(),
        inventorySize,
        getInputSlots()
    )

    open fun getInputSlots() : IntArray = intArrayOf(INPUT_SLOT)

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

    @ServerOnly
    open fun serverTick() {
        val result = loop.tick(cell, inventoryHandler)

        if(!result.speed.approxEq(lastSentSpeed, 1e-4)) {
            sendSync(result.speed)
        }

        data.progress = result.progress
    }

    open fun getSound() : RegistryObject<SoundEvent>? = null

    open fun animateClientTick(dt: Double, speed: Double, level: Level, blockPos: BlockPos) { }

    @ClientOnly
    fun clientTick() {
        val dt = clientTickSpeedSmoother.update(targetClientSpeed)

        if(clientTickSpeedSmoother.value < 0.01) {
            return
        }

        val level = level
            ?: return

        val pos = blockPos
            ?: return

        if(soundInstance == null) {
            val soundEvent = getSound()

            if(soundEvent != null) {
                soundInstance = SimpleLoopingBlockEntitySoundInstance(this, soundEvent.get()).also {
                    it.events.registerHandler<SoundInstanceTickEvent> { e ->
                        it.soundInfo = SoundInfo.standardWithProcessingSpeed(clientTickSpeedSmoother.value)
                    }

                    it.registerOnAudioManager()
                }
            }
        }

        animateClientTick(dt, clientTickSpeedSmoother.value, level, pos)
    }

    @ServerOnly
    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)
        pTag.put(INVENTORY, inventoryHandler.serializeNBT())
        loop.saveAdditional(pTag)
    }

    @ServerOnly
    override fun load(pTag: CompoundTag) {
        super.load(pTag)
        inventoryHandler.deserializeNBT(pTag.getCompound(INVENTORY))
        loop.load(pTag)
    }

    //#region Sync

    override val clientSidePacketHandlerLazy = createClientSideHandler()

    @ServerOnly
    private var lastSentSpeed = 0.0

    // onSyncSuggested
    @ServerOnly
    override fun getUpdateTag(): CompoundTag {
        if(hasCell) {
            sendSync(cell.processingSpeed)
        }

        return super.getUpdateTag()
    }

    @ServerOnly
    fun sendSync(speed: Double) {
        val sent = this.sendBulkPacket(SyncPacket(speed))

        if(sent) {
            lastSentSpeed = speed
        }
    }

    @ClientOnly
    override fun setupPacketsOnClient(handler: ClientSidePacketHandlerBuilder) {
        handler.withHandler<SyncPacket> {
            targetClientSpeed = it.speed
        }
    }

    @Serializable
    private class SyncPacket(val speed: Double)

    //#endregion
}

abstract class MotorProcessingBlock<R, BE> : ProcessingBlock<R, MotorProcessingCell, BE>()
    where
        R : Eln2SimpleRecipe, R : Recipe<SimpleContainer>,
        BE : MotorProcessingBlockEntity<R>

abstract class MotorProcessingBlockEntity<R>(
    pos: BlockPos,
    state: BlockState,
    targetType: BlockEntityType<*>,
    inventorySize: Int
) : ProcessingBlockEntity<R, MotorProcessingCell>(pos, state, targetType, inventorySize)
    where R : Eln2SimpleRecipe, R : Recipe<SimpleContainer>
{
    @ServerOnly
    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Speed: ${cell.motor.processingSpeed.rounded()}" }
        builder.debugInIDE { "OC: ${cell.motor.resistorDisplay.openCircuitPotentialEstimate.value.rounded()}" }
        builder.quantityInput(cell.motor.resistorDisplay.power)

        if(cell.thermalWire != null) {
            builder.quantity(cell.thermalWire!!.thermalBodyDisplay.temperature)
        }

        loop.operation?.also {
            builder.progress(it.timeProgress / it.recipe.duration)
        }
    }
}

/**
 * @param idleFriction The friction of the node when not processing.
 * @param runningFriction The friction of the node when processing. This models the load.
 * @param nominalAngularVelocity The angular velocity where the device is running at 100% speed.
 * @param maxTorque Torque breaking limit.
 * */
data class KineticProcessingCellKineticOptions(
    val idleFriction: FrictionNodeDescription,
    val runningFriction: FrictionNodeDescription,
    val nominalAngularVelocity: Quantity<AngularVelocity>,
    val kineticBreakdownVelocity: Quantity<AngularVelocity>,
    val maxTorque: Quantity<Torque>
)

/**
 * Object emulating the behavior described in [KineticProcessingCellOptions] using a [KineticDouble].
 * Uses the kinetic simulator's friction to emulate a load.
 * */
class KineticProcessingKineticObject(cell: KineticProcessingCell) : KineticObject<KineticProcessingCell>(cell), PersistentObject {
    val node = KineticDouble(cell.options.kinetic.idleFriction.inertia == cell.options.kinetic.runningFriction.inertia)

    init {
        node.setSafeTorque(cell.options.kinetic.maxTorque)
        cell.options.kinetic.idleFriction.applyTo(node)
    }

    override fun offerExtension(remote: KineticObject<*>) = node.chooseExtension(cell.kineticMap, remote)

    /**
     * The base grinding speed, calculated each tick.
     * */
    var processingSpeed = 0.0

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::tick)
    }

    override fun addNodes(builder: KineticNodeSet) {
        builder.add(node)
    }

    /**
     * Converts the input power into some thermal power and updates the [processingSpeed].
     * */
    private fun tick(dt: Double, subscriberPhase: SubscriberPhase) {
        val options = cell.options

        if(options.thermal != null) {
            // Convert fraction of power to heat:
            val energy = node.deltaHeatFromFriction

            if(!energy.approxEq(0.0)) {
                cell.thermalWire!!.thermalBody.energy += Quantity(energy, JOULE)
                cell.setChanged()
            }
        }

        if(!cell.isActive) {
            processingSpeed = 0.0
            cell.options.kinetic.idleFriction.applyTo(node)
            return
        }

        cell.options.kinetic.runningFriction.applyTo(node)

        processingSpeed = if(node.angularVelocity.approxEq(0.0)) {
            0.0
        } else {
            options.baseSpeedFactor * (abs(node.angularVelocity) / !options.kinetic.nominalAngularVelocity)
        }
    }

    override fun saveObjectNbt() = node.saveNbt()
    override fun loadObjectNbt(tag: CompoundTag) = node.loadNbt(tag)
}

/**
 * Crusher model. If active (needs to crush):
 * - The device applies the parameters from [KineticProcessingCellKineticOptions.runningFriction].
 * - Speed is proportional to angular velocity.
 * - The max speed is capped by [KineticProcessingCellKineticOptions.maxTorque].
 * - A fraction of the friction heat is converted into heat, setting another hard cap on the amount of work you can do.
 * - The device is destroyed when it reaches a certain temperature, and also has angular velocity and torque breakdown.
 * @param thermal If applicable, the terms dictating the waste heat production of the machine.
 * @param baseSpeedFactor The device's raw speed is [baseSpeedFactor] * (`power` / [KineticProcessingCellKineticOptions.nominalAngularVelocity]).
 *  */
data class KineticProcessingCellOptions(
    val baseSpeedFactor: Double,
    val kinetic: KineticProcessingCellKineticOptions,
    val thermal: ProcessingCellThermalOptions?,
)

class KineticProcessingCell(
    ci: CellCreateInfo,
    val options: KineticProcessingCellOptions,
    override val kineticMap: PoleMap,
    override val thermalMap: PoleMap
) : Cell(ci), SidedKineticMapped<KineticProcessingCell>, SidedThermalMapped<KineticProcessingCell>, ProcessingDevice {
    override val kineticSize: KineticSize
        get() = KineticSize.Standard

    override val thermalSize: ThermalSize
        get() = ThermalSize.Any

    override var isActive = false

    override val processingSpeed: Double
        get() = kinetic.processingSpeed

    @SimObject
    val thermalWire = if(options.thermal != null) {
        ThermalWireObject(
            this,
            options.thermal.massDef(),
            options.thermal.leakageParameters
        )
    }
    else null

    @SimObject
    val kinetic = KineticProcessingKineticObject(this)

    val kineticState get() = RotatingKineticState(kinetic.node.angle, kinetic.node.angularVelocity)

    @Replicator
    fun replicator(target: InternalKineticStateConsumer) = InternalKineticReplicatorBehavior(
        this::kineticState,
        target
    )

    @Behavior
    val thermalBreakdown = if(options.thermal != null) {
        ThermalBreakdownBehavior.create(
            options.thermal.destroyTemperature,
            this,
            thermalWire!!.thermalBody::temperature
        )
    }
    else null

    @Behavior
    val kineticBreakdown = KineticBreakdownBehavior.create(options.kinetic.kineticBreakdownVelocity, this, kinetic.node)

    @Behavior
    val stress = KineticStressBehavior.create(options.kinetic.maxTorque, this, kinetic.node)
}


abstract class KineticProcessingBlock<R, BE> : ProcessingBlock<R, KineticProcessingCell, BE>()
    where
        R : Eln2SimpleRecipe, R : Recipe<SimpleContainer>,
        BE : KineticProcessingBlockEntity<R>

abstract class KineticProcessingBlockEntity<R>(
    pos: BlockPos,
    state: BlockState,
    targetType: BlockEntityType<*>,
    inventorySize: Int
) : ProcessingBlockEntity<R, KineticProcessingCell>(pos, state, targetType, inventorySize)
    where R : Eln2SimpleRecipe, R : Recipe<SimpleContainer>
{
    @ServerOnly
    override fun submitDisplay(builder: ComponentDisplayList) {
        cell.kinetic.subSolvers?.debugInIDE(builder)
        builder.debugInIDE { "Speed: ${cell.kinetic.processingSpeed.rounded()}" }
        builder.debugInIDE { "Imp0: ${cell.kinetic.node.e1.impulse.rounded()}, Imp1: ${cell.kinetic.node.e2.impulse.rounded()}" }
        builder.debugInIDE { "Fric T: ${cell.kinetic.node.frictionTorque.rounded()}"}

        if(cell.thermalWire != null) {
            builder.quantity(cell.thermalWire!!.thermalBodyDisplay.temperature)
        }

        loop.operation?.also {
            builder.progress(it.timeProgress / it.recipe.duration)
        }
    }
}
