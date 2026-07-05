@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package org.eln2.mc.common.content

import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.capabilities.ICapabilityProvider
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.energy.IEnergyStorage
import org.ageseries.libage.data.Energy
import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.Power
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.mathematics.approxEq
import org.eln2.mc.RF_PER_JOULE
import org.eln2.mc.common.events.Scheduler
import kotlin.math.min

// P.S. Some of these methods have explicit unit names and are methods instead of properties to make potential mod integration easier (if they use java).

/**
 * Specification for an inventory power cell.
 *
 * @param energyCapacity Total energy the cell can store.
 * @param maxPowerInput Maximum charge rate.
 * @param maxPowerOutput Maximum discharge rate.
 * @param efficiency Round-trip efficiency factor in [0, 1], applied on both charge and discharge. Charging stores `received * efficiency`; discharging costs `delivered / efficiency` from the buffer.
 * */
data class InventoryPowerCellModel(
    val energyCapacity: Quantity<Energy>,
    val maxPowerInput: Quantity<Power>,
    val maxPowerOutput: Quantity<Power>,
    val efficiency: Double,
)

/**
 * Priority for power distribution among [InventoryPowerConsumer]s.
 * The [PlayerPowerManager] grants power to higher-priority consumers first; lower-priority consumers receive what remains.
 * */
enum class InventoryPowerPriority {
    Low,
    Normal,
    High,
    Critical,
}

/**
 * A power consumer attached to a player. Implementations report their current demand; the [PlayerPowerManager] arbitrates distribution and calls [receivePower] with the granted amount.
 * The consumer talks only to the manager, never to producers directly.
 * */
interface InventoryPowerConsumer {
    /**
     * The priority at which this consumer's demand is evaluated.
     * */
    val priority: InventoryPowerPriority

    /**
     * How much power this consumer wants right now. Returns 0 when idle.
     * The manager multiplies this by the tick dt to get the energy budget request.
     * */
    fun powerDemand(): Quantity<Power>

    /**
     * Called by the manager to deliver granted energy.
     * Returns the amount actually consumed (might be less than [granted] if the consumer's state changed, though typically equals [granted]).
     * */
    fun receivePower(granted: Quantity<Energy>): Quantity<Energy>
}

/**
 * A power producer attached to a player, queried by the [PlayerPowerManager] during distribution.
 * Implementations are typically inventory items exposing [IEln2EnergyStorage].
 * */
interface InventoryPowerProducer {
    /**
     * The maximum power this producer can output this tick.
     * */
    val maxOutput: Quantity<Power>

    /**
     * The energy currently available in the buffer.
     * */
    val availableEnergy: Quantity<Energy>

    /**
     * Draws up to [request] energy from this producer, respecting [maxOutput] and [availableEnergy].
     * Returns the amount actually drawn.
     * */
    fun draw(request: Quantity<Energy>): Quantity<Energy>
}

/**
 * Extends Forge's [IEnergyStorage] with ELN2 energy view and power metadata.
 * The [IEnergyStorage] int methods convert to/from joules using [RF_PER_JOULE] and enforce power caps via [Scheduler.timeStamp].
 * External mods see rate-capped FE; ELN2 code casts to [IEln2EnergyStorage] to read [maxPowerOutput] and use lossless transfer.
 * Energy stored lives in the [ItemStack] NBT; this interface is a view over it.
 * */
interface IEln2EnergyStorage : IEnergyStorage {
    val model: InventoryPowerCellModel

    /**
     * The current stored energy.
     * */
    val energyJoules: Quantity<Energy>

    /**
     * The maximum stored energy in joules.
     * */
    val capacityJoules: Quantity<Energy>

    /**
     * Maximum charge rate.
     * */
    val maxPowerInput: Quantity<Power>

    /**
     * Maximum discharge rate.
     * */
    val maxPowerOutput: Quantity<Power>

    /**
     * Round-trip efficiency applied on both charge and discharge.
     * */
    val efficiency: Double

    /**
     * Receives up to [max] joules, respecting [maxPowerInput] (per tick), [capacityJoules], and [efficiency]. Returns the amount actually accepted into the buffer (post-efficiency).
     * If [simulate] is true, no state is mutated.
     * */
    fun receiveEnergyJoules(max: Quantity<Energy>, simulate: Boolean): Quantity<Energy>

    /**
     * Extracts up to [max] joules, respecting [maxPowerOutput] (per tick), [energyJoules], and [efficiency]. Returns the amount actually delivered (pre-efficiency cost).
     * If [simulate] is true, no state is mutated.
     * */
    fun extractEnergyJoules(max: Quantity<Energy>, simulate: Boolean): Quantity<Energy>

    companion object {
        const val NBT_ENERGY = "energy"
        const val NBT_TICK_RECEIVED = "tickReceived"
        const val NBT_TICK_EXTRACTED = "tickExtracted"
        const val NBT_RECEIVED_THIS_TICK = "receivedThisTick"
        const val NBT_EXTRACTED_THIS_TICK = "extractedThisTick"
    }
}

/**
 * Base class for inventory power cell items. Stores energy in the stack's NBT tag and exposes [IEln2EnergyStorage] via the Forge [ForgeCapabilities.ENERGY] capability.
 * This is similar to a normal FE battery. Doesn't have a simulation.
 * Mirrors the [org.eln2.mc.common.LightBulbItem] pattern for stack NBT access.
 * */
class PowerCellItem(val powerCellModel: InventoryPowerCellModel, val initialChargeFraction: Double = 0.9) : Item(Properties().stacksTo(1)) {
    /**
     * Reads the stored energy from the stack's NBT.
     * */
    fun getEnergy(stack: ItemStack): Quantity<Energy> {
        val tag = stack.tag
            ?: return Quantity(0.0, JOULE)

        return Quantity(tag.getDouble(IEln2EnergyStorage.NBT_ENERGY), JOULE)
    }

    /**
     * Writes the stored energy (joules) to the stack's NBT.
     * */
    fun setEnergy(stack: ItemStack, energy: Quantity<Energy>) {
        val clamped = energy.value.coerceIn(0.0, !powerCellModel.energyCapacity)
        stack.getOrCreateTag().putDouble(IEln2EnergyStorage.NBT_ENERGY, clamped)
    }

    /**
     * Creates a stack pre-charged to [initialChargeFraction] of capacity.
     * */
    fun createStack(count: Int = 1): ItemStack {
        val stack = ItemStack(this, count)
        setEnergy(stack, powerCellModel.energyCapacity * initialChargeFraction)
        return stack
    }

    override fun initCapabilities(stack: ItemStack, nbt: CompoundTag?, ): ICapabilityProvider {
        return PowerCellCapabilityProvider(stack, this)
    }

    /**
     * Called by the [PlayerPowerManager] to draw energy as a producer.
     * Delegates to the stack's [IEln2EnergyStorage] capability.
     * */
    fun drawEnergy(stack: ItemStack, request: Quantity<Energy>): Quantity<Energy> {
        val storage = getStorage(stack)
            ?: return Quantity(0.0, JOULE)

        return storage.extractEnergyJoules(request, simulate = false)
    }

    /**
     * Called by ELN2 chargers to push energy into the cell.
     * */
    fun chargeEnergy(stack: ItemStack, request: Quantity<Energy>): Quantity<Energy> {
        val storage = getStorage(stack)
            ?: return Quantity(0.0, JOULE)

        return storage.receiveEnergyJoules(request, simulate = false)
    }

    /**
     * Returns the [IEln2EnergyStorage] view for this stack, or null if the stack is empty.
     * */
    fun getStorage(stack: ItemStack): IEln2EnergyStorage? {
        if (stack.isEmpty) {
            return null
        }

        val cap = stack.getCapability(ForgeCapabilities.ENERGY).resolve()
        return if (cap.isPresent) cap.get() as? IEln2EnergyStorage else null
    }
}

/**
 * Provides [ForgeCapabilities.ENERGY] (as [IEln2EnergyStorage]) for a [PowerCellItem] stack.
 * The storage reads/writes energy and per-tick accumulators directly from the stack's NBT, so it stays valid across stack copies and NBT serialization.
 * */
private class PowerCellCapabilityProvider(private val stack: ItemStack, private val item: PowerCellItem) : ICapabilityProvider, IEln2EnergyStorage {
    override val model: InventoryPowerCellModel
        get() = item.powerCellModel

    override val energyJoules: Quantity<Energy>
        get() = item.getEnergy(stack)

    override val capacityJoules: Quantity<Energy>
        get() = model.energyCapacity

    override val maxPowerInput: Quantity<Power>
        get() = model.maxPowerInput

    override val maxPowerOutput: Quantity<Power>
        get() = model.maxPowerOutput

    override val efficiency: Double
        get() = model.efficiency

    private fun readTickAccumulator(key: String, tickKey: String): Pair<Long, Double> {
        val tag = stack.tag ?: return Pair(-1L, 0.0)
        val tick = tag.getLong(tickKey)
        val amount = if (tag.contains(key)) tag.getDouble(key) else 0.0
        return Pair(tick, amount)
    }

    private fun writeTickAccumulator(key: String, tickKey: String, tick: Long, amount: Double) {
        val tag = stack.getOrCreateTag()
        tag.putLong(tickKey, tick)
        tag.putDouble(key, amount)
    }

    private fun tickEnergyBudget(
        maxPerTick: Quantity<Power>,
        tickKey: String,
        amountKey: String,
    ): Quantity<Energy> {
        val currentTick = Scheduler.timeStamp
        val (lastTick, usedThisTick) = readTickAccumulator(amountKey, tickKey)
        val effectiveUsed = if (lastTick != currentTick) 0.0 else usedThisTick
        val perTickBudget = (!maxPerTick) / 20.0
        val remainingThisTick = (perTickBudget - effectiveUsed).coerceAtLeast(0.0)
        return Quantity(remainingThisTick, JOULE)
    }

    private fun accumulateTickUsage(amount: Double, tickKey: String, amountKey: String) {
        val currentTick = Scheduler.timeStamp
        val (lastTick, usedThisTick) = readTickAccumulator(amountKey, tickKey)
        val newAmount = if (lastTick != currentTick) amount else usedThisTick + amount
        writeTickAccumulator(amountKey, tickKey, currentTick, newAmount)
    }

    override fun receiveEnergyJoules(max: Quantity<Energy>, simulate: Boolean): Quantity<Energy> {
        val budget = tickEnergyBudget(maxPowerInput, IEln2EnergyStorage.NBT_TICK_RECEIVED, IEln2EnergyStorage.NBT_RECEIVED_THIS_TICK)
        val capacityRoom = (!capacityJoules - !energyJoules).coerceAtLeast(0.0)
        val rawAccepted = min(min(!max, !budget), capacityRoom)

        if (rawAccepted.approxEq(0.0)) {
            return Quantity(0.0, JOULE)
        }

        val stored = rawAccepted * efficiency

        if (!simulate) {
            setEnergyInternal(Quantity(!energyJoules + stored, JOULE))
            accumulateTickUsage(rawAccepted, IEln2EnergyStorage.NBT_TICK_RECEIVED, IEln2EnergyStorage.NBT_RECEIVED_THIS_TICK)
        }

        return Quantity(rawAccepted, JOULE)
    }

    override fun extractEnergyJoules(max: Quantity<Energy>, simulate: Boolean): Quantity<Energy> {
        val budget = tickEnergyBudget(maxPowerOutput, IEln2EnergyStorage.NBT_TICK_EXTRACTED, IEln2EnergyStorage.NBT_EXTRACTED_THIS_TICK)
        val available = !energyJoules
        val rawDelivered = min(min(!max, !budget), available)

        if (rawDelivered.approxEq(0.0)) {
            return Quantity(0.0, JOULE)
        }

        val cost = rawDelivered / efficiency

        if (!simulate) {
            setEnergyInternal(Quantity(!energyJoules - cost, JOULE))
            accumulateTickUsage(rawDelivered, IEln2EnergyStorage.NBT_TICK_EXTRACTED, IEln2EnergyStorage.NBT_EXTRACTED_THIS_TICK)
        }

        return Quantity(rawDelivered, JOULE)
    }

    private fun setEnergyInternal(energy: Quantity<Energy>) {
        item.setEnergy(stack, energy)
    }

    override fun receiveEnergy(maxReceive: Int, simulate: Boolean): Int {
        val joules = Quantity(maxReceive.toDouble() / RF_PER_JOULE, JOULE)
        val accepted = receiveEnergyJoules(joules, simulate)
        return (!accepted * RF_PER_JOULE.toDouble()).toLong().toInt().coerceAtLeast(0)
    }

    override fun extractEnergy(maxExtract: Int, simulate: Boolean): Int {
        val joules = Quantity(maxExtract.toDouble() / RF_PER_JOULE, JOULE)
        val extracted = extractEnergyJoules(joules, simulate)
        return (!extracted * RF_PER_JOULE.toDouble()).toLong().toInt().coerceAtLeast(0)
    }

    override fun getEnergyStored(): Int {
        return (!energyJoules * RF_PER_JOULE.toDouble()).toLong().toInt().coerceIn(0, Int.MAX_VALUE)
    }

    override fun getMaxEnergyStored(): Int {
        return (!capacityJoules * RF_PER_JOULE.toDouble()).toLong().toInt().coerceIn(0, Int.MAX_VALUE)
    }

    override fun canExtract(): Boolean = true

    override fun canReceive(): Boolean = true

    override fun <T> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ENERGY) {
            return LazyOptional.of { this }.cast()
        }
        return LazyOptional.empty()
    }
}
