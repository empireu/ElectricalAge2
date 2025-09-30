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
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
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
import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.Potential
import org.ageseries.libage.data.Power
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Resistance
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.data.registerHandler
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.mna.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.mna.component.updateResistance
import org.eln2.mc.ClientOnly
import org.eln2.mc.FramerateIndependentSmoother1d
import org.eln2.mc.LOG
import org.eln2.mc.ServerOnly
import org.eln2.mc.TheveninEstimatingResistor
import org.eln2.mc.common.blocks.foundation.CellBlock
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.cells.foundation.Behavior
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.cells.foundation.CellCreateInfo
import org.eln2.mc.common.cells.foundation.DielectricBreakdownBehavior
import org.eln2.mc.common.cells.foundation.ElectricalObject
import org.eln2.mc.common.cells.foundation.ElectricalSize
import org.eln2.mc.common.cells.foundation.SidedElectricalMapped
import org.eln2.mc.common.cells.foundation.SidedThermalMapped
import org.eln2.mc.common.cells.foundation.SimObject
import org.eln2.mc.common.cells.foundation.SubscriberCollection
import org.eln2.mc.common.cells.foundation.SubscriberPhase
import org.eln2.mc.common.cells.foundation.TemperatureExplosionBehavior
import org.eln2.mc.common.cells.foundation.ThermalSize
import org.eln2.mc.common.cells.foundation.addPre
import org.eln2.mc.common.containers.ProgressContainerData
import org.eln2.mc.common.content.ThermalWireObject
import org.eln2.mc.common.network.serverToClient.BulkPacketHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.sendBulkPacket
import org.eln2.mc.common.recipes.foundation.ProcessingDevice
import org.eln2.mc.common.recipes.foundation.SimpleProcessingRecipe
import org.eln2.mc.common.recipes.foundation.SimpleProcessingRecipeInventoryHandler
import org.eln2.mc.common.recipes.foundation.SimpleProcessingRecipeLoop
import org.eln2.mc.common.sounds.foundation.SimpleLoopingBlockEntitySoundInstance
import org.eln2.mc.common.sounds.foundation.SoundInfo
import org.eln2.mc.common.sounds.foundation.SoundInstanceTickEvent
import org.eln2.mc.data.Pole
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.evaluate
import org.eln2.mc.extensions.constructMenuHelper2
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.offerNegative
import org.eln2.mc.offerPositive
import org.eln2.mc.setLoad
import kotlin.math.absoluteValue

/**
 * Crusher model. If active (needs to crush):
 * - The device doesn't start (speed = 0) when the potential is under the threshold, but still draws power (tries to draw [nominalPower]).
 * - The device keeps ~[nominalPower] when the potential is in the specified range.
 * - Speed is proportional to input power.
 * - The device starts increasing in power draw like a resistor when the potential is over the specified range.
 * - A fraction of the input power is converted into heat, setting a hard cap on the amount of work you can do.
 * - The device is destroyed when it reaches a certain temperature, and also has dielectric breakdown.
 * @param nominalPotential The target potential.
 * @param nominalPower The target power. It is delivered in the range of [[potentialThresholdFactor] * [nominalPotential], [maxRatedPotentialFactor] * [nominalPotential]].
 * @param potentialThresholdFactor The device doesn't run if the potential is negative or less than [potentialThresholdFactor] * [nominalPotential].
 * @param maxRatedPotentialFactor The device's efficiency starts to degrade and the power draw starts increasing when the potential is above [maxRatedPotentialFactor] * [nominalPotential].
 * @param thermalFactor `power` * [thermalFactor] of the power is converted into heat.
 * @param baseSpeedFactor The device's raw speed is [baseSpeedFactor] * (`power` / [nominalPower]).
 * */
data class MotorProcessingCellOptions(
    val idleResistance: Quantity<Resistance>,
    val probingResistance: Quantity<Resistance>,
    val minResistance: Quantity<Resistance>,
    val nominalPotential: Quantity<Potential>,
    val nominalPower: Quantity<Power>,
    val potentialThresholdFactor: Double,
    val maxRatedPotentialFactor: Double,
    val thermalFactor: Double,
    val baseSpeedFactor: Double,
    val massDef: ThermalMassDefinition,
    val leakageParameters: ConnectionParameters,
    val destroyTemperature: Quantity<Temperature>,
    val dielectricBreakdownPotential: Quantity<Potential>,
)

/**
 * Object emulating the behavior described in [MotorProcessingCellOptions] using a [TheveninEstimatingResistor].
 * Uses the Thevenin estimates to select the behavior based on the estimated open-circuit potential, and to create the constant load in the target regions.
 * */
class MotorProcessingElectricalObject(cell: MotorProcessingCell) : ElectricalObject<MotorProcessingCell>(cell) {
    val resistor = TheveninEstimatingResistor().also {
        it.resistance = !cell.options.idleResistance
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

        // Convert fraction of power to heat:
        if(resistor.potential > 0.0 && resistor.power > 0.0) {
            val energy = options.thermalFactor * resistor.power * dt

            if(!energy.approxEq(0.0)) {
                cell.thermalWire.thermalBody.energy += Quantity(energy, JOULE)
                cell.setChanged()
            }
        }

        fun setLoad(power: Double) {
            resistor.setLoad(power, !options.minResistance, !options.probingResistance)
        }

        if(!cell.isActive) {
            processingSpeed = 0.0
            resistor.resistance = !options.idleResistance
            return
        }

        val potential = resistor.openCircuitPotentialEstimate

        if(potential <= 0.0) {
            processingSpeed = 0.0
            setLoad(0.0)
            return
        }

        val potentialThreshold = options.potentialThresholdFactor * !options.nominalPotential

        val isGrinding: Boolean

        if(potential < potentialThreshold) {
            // Draw nominal power even if we can't drive the grinding.
            setLoad(!options.nominalPower)
            isGrinding = false
        }
        else {
            val maxPotential = options.maxRatedPotentialFactor * !options.nominalPotential

            if(potential > maxPotential) {
                // Over-potential. Act like a resistor now:
                val r = ((maxPotential * maxPotential) / !options.nominalPower).coerceAtLeast(1e-6)
                resistor.updateResistance(r)
            }
            else {
                // Operating within margin. Consume ~nominalPower:
                setLoad(!options.nominalPower)
            }

            isGrinding = true
        }

        processingSpeed = if (isGrinding) {
            options.baseSpeedFactor * (resistor.power.absoluteValue / !options.nominalPower)
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
    val thermalWire = ThermalWireObject(
        this,
        options.massDef(),
        options.leakageParameters
    )

    @SimObject
    val motor = MotorProcessingElectricalObject(this)

    @Behavior
    val temperatureExplosion = TemperatureExplosionBehavior.create(
        options.destroyTemperature,
        this,
        thermalWire.thermalBody::temperature
    )

    @Behavior
    val dielectricBreakdown = DielectricBreakdownBehavior.create(this).also {
        it.addPort(
            motor.resistor,
            !options.dielectricBreakdownPotential,
            !options.dielectricBreakdownPotential
        )
    }
}

abstract class MotorProcessingBlock<E : MotorProcessingBlockEntity> : CellBlock<MotorProcessingCell>() {
    abstract fun getTitle() : Component

    abstract fun createMenu(
        pBlockEntity: E,
        pContainerId: Int,
        pPlayerInventory: Inventory
    ) : AbstractContainerMenu

    @Deprecated("Deprecated in Java", ReplaceWith("true"))
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean {
        return true
    }

    fun tick(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
        MotorProcessingBlockEntity.tick(pLevel, pPos, pState, pBlockEntity)
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
            createMenu(a as E, b, c)
        }
    }

    open fun animateMachineTick(blockEntity: MotorProcessingBlockEntity, speed: Double, pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) { }

    override fun animateTick(pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) {
        val blockEntity = pLevel.getBlockEntity(pPos) as? MotorProcessingBlockEntity
            ?: return

        val speed = blockEntity.clientTickSpeedSmoother.value

        if(speed < 0.01) {
            return
        }

        animateMachineTick(blockEntity, speed, pState, pLevel, pPos, pRandom)
    }
}

abstract class MotorProcessingBlockEntity(pos: BlockPos, state: BlockState, targetType: BlockEntityType<*>) :
    CellBlockEntity<MotorProcessingCell>(pos, state, targetType),
    ComponentDisplay,
    BulkPacketHandlerBlockEntity
{
    companion object {
        private const val INVENTORY = "inventory"

        fun tick(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
            if (pLevel == null || pBlockEntity == null) {
                LOG.error("level or entity null")
                return
            }

            if (pBlockEntity !is MotorProcessingBlockEntity) {
                LOG.error("Got $pBlockEntity instead of motor processing block entity")
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
    var soundInstance: SimpleLoopingBlockEntitySoundInstance<MotorProcessingBlockEntity>? = null

    //#endregion

    @ServerOnly
    val loop = SimpleProcessingRecipeLoop(this)

    abstract fun getRecipe() : RecipeType<SimpleProcessingRecipe>

    val inventoryHandler = SimpleProcessingRecipeInventoryHandler(this, getRecipe())
    val inventoryHandlerLazy: LazyOptional<SimpleProcessingRecipeInventoryHandler<MotorProcessingBlockEntity>> = LazyOptional.of { inventoryHandler }

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
    fun serverTick() {
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
                        it.soundInfo = SoundInfo.Companion.standardWithProcessingSpeed(clientTickSpeedSmoother.value)
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
            sendSync(cell.motor.processingSpeed)
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

    @ServerOnly
    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Speed: ${cell.motor.processingSpeed.rounded()}" }
        builder.debugInIDE { "OC: ${cell.motor.resistorDisplay.openCircuitPotentialEstimate.value.rounded()}" }
        builder.quantityInput(cell.motor.resistorDisplay.power)
        builder.quantity(cell.thermalWire.thermalBodyDisplay.temperature)

        loop.operation?.also {
            builder.progress(it.timeProgress / it.recipe.duration)
        }
    }
}
