package org.eln2.mc.common.fluids.foundation

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.classify
import org.ageseries.libage.mathematics.approxEq
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.ELN2_DEBUG
import org.eln2.mc.KILOGRAM_PER_MILLIBUCKET
import org.eln2.mc.LOG
import org.eln2.mc.OnServerThread
import org.eln2.mc.common.chemistry.PhysicalFluidManager
import org.eln2.mc.data.LinearObjectPool
import org.eln2.mc.data.PooledObjectPolicy
import org.eln2.mc.data.using
import org.eln2.mc.extensions.forEachCompound
import org.eln2.mc.extensions.getListTag
import org.eln2.mc.integration.ComponentDisplayList
import kotlin.math.floor
import kotlin.math.min

//#region Discrete Tanks

/**
 * Represents a fluid tank that supports multiple fluids, with a total max volume for the entire tank.
 * The default extraction logic allows separating the fluids freely. This is simply a data structure for holding multiple fluids (**NBT tags are stripped from the fluid stacks**).
 *
 * Rules:
 *  1. Each entry in [fluids] is unique in its [net.minecraft.world.level.material.Fluid]
 *  2. All entries have a nonzero amount and non-empty fluid
 *  3. The total sum of the amounts is less than or equal to [capacity]
 *
 *  @param requireThermalFluid If true, only thermal fluids will be allowed for insertion.
 * */
open class MultipleFluidTank(val capacity: Int, val requireThermalFluid: Boolean) : IFluidHandler {
    var version = 0
        private set

    val fluids = ArrayList<FluidStack>()

    /**
     * Gets the total amount of fluid in this tank (unless externally modified, it should be less than or equal to [capacity]).
     * */
    val amount: Int get() = fluids.sumOf { it.amount }

    /**
     * Gets the free volume in the tank. If the [amount] is larger than [capacity], the result will be 0.
     * */
    val remainingCapacity: Int get() {
        val currentAmount = amount

        if(currentAmount >= capacity) {
            return 0
        }

        return capacity - currentAmount
    }

    open fun incrementVersion() {
        version++
    }

    /**
     * Rearranges the data so it obeys the first 2 rules. After this, the entries in [fluids] will be non-empty, all distinct fluid types.
     * Should only be used if [fluids] were modified by an algorithm externally, and the fluids don't respect the rules anymore.
     * */
    fun compact() {
        val temp = LinkedHashMap<Fluid, Int>()

        fluids.forEach { stack ->
            if(!stack.isEmpty && stack.fluid != Fluids.EMPTY && stack.amount > 0) {
                temp.merge(stack.fluid, stack.amount) { a, b -> a + b }
            }
        }

        fluids.clear()

        temp.forEach { (fluid, amount) ->
            val stack = FluidStack(fluid, amount)
            fluids.add(stack)
        }

        temp.clear()

        incrementVersion()
    }

    /**
     * We return [fluids]` + 1` to indicate there is always some available tank to accept fluid. Not sure if it's even useful.
     * */
    override fun getTanks() = fluids.size + 1

    /**
     * Gets the fluid stack in the [tank] slot. Returns an empty stack if the [tank] is outside the range of the [fluids] list.
     * */
    override fun getFluidInTank(tank: Int): FluidStack {
        if(tank in fluids.indices) {
            return fluids[tank]
        }

        return FluidStack.EMPTY
    }

    /**
     * Simply gets the remaining capacity.
     * */
    override fun getTankCapacity(tank: Int) = remainingCapacity

    /**
     * Checks if [fluid] is allowed to be inserted in the tank.
     * */
    private fun isFluidAllowed(fluid: Fluid) : Boolean {
        if(!requireThermalFluid) {
            return true
        }

        return PhysicalFluidManager.getProperties(fluid) != null
    }

    override fun isFluidValid(tank: Int, stack: FluidStack) = isFluidAllowed(stack.fluid)

    /**
     * Increments the existing stack in [fluids] or creates a new one, while respecting the [capacity].
     * */
    override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
        if(!isFluidAllowed(resource.fluid)) {
            return 0
        }

        val resourceToFill = min(resource.amount, remainingCapacity)

        if(resourceToFill == 0) {
            return 0
        }

        if(action != IFluidHandler.FluidAction.SIMULATE) {
            val existingIndex = fluids.indexOfFirst { it.fluid == resource.fluid }

            if(existingIndex == -1) {
                /**
                 * Insert new stack:
                 * */
                fluids.add(FluidStack(resource.fluid, resourceToFill))
            }
            else {
                /**
                 * Increase existing stack:
                 * */
                val newStack = FluidStack(resource.fluid, resourceToFill + fluids[existingIndex].amount)
                fluids[existingIndex] = newStack
            }

            incrementVersion()
        }

        return resourceToFill
    }

    /**
     * Decrements the fluid at [index] or removes the stack entirely, if all fluid was removed.
     * [fluidToDrain] must be less than or equal to the stack's amount!
     * */
    fun removeAmount(index: Int, fluidToDrain: Int) {
        if(fluidToDrain <= 0) {
            return
        }

        val stack = fluids[index]

        require(fluidToDrain <= stack.amount) {
            DEBUGGER_BREAK("Tried to remove $fluidToDrain from a stack with ${stack.amount}")
        }

        /**
         * Checks if this drains the entire fluid, which means it needs to be removed:
         * */
        if(fluidToDrain == stack.amount) {
            fluids.removeAt(index)
        }
        /**
         * Decrements the amount of fluid:
         * */
        else {
            fluids[index] = FluidStack(stack.fluid, stack.amount - fluidToDrain)
        }

        incrementVersion()
    }

    /**
     * Tries to drain [resource] from the stack in [fluids] that matches, and removes the stack if all fluid was drained.
     * Doesn't scan all stacks (it is assumed the tank obeys the first 2 rules).
     * */
    override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
        if(resource.amount <= 0) {
            return FluidStack.EMPTY
        }

        for (i in fluids.indices) {
            val stack = fluids[i]

            if(stack.fluid == resource.fluid) {
                val fluidToDrain = min(resource.amount, stack.amount)
                val result = FluidStack(resource.fluid, fluidToDrain)

                if(action == IFluidHandler.FluidAction.EXECUTE) {
                    removeAmount(i, fluidToDrain)
                }

                return result
            }
        }

        return FluidStack.EMPTY
    }

    /**
     * Drains from the first slot in [fluids].
     * */
    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
        if(maxDrain <= 0) {
            return FluidStack.EMPTY
        }

        if(fluids.isEmpty()) {
            return FluidStack.EMPTY
        }

        val stack = fluids[0]
        val result = FluidStack(stack.fluid, min(stack.amount, maxDrain))

        if(action != IFluidHandler.FluidAction.SIMULATE) {
            removeAmount(0, result.amount)
        }

        return result
    }

    fun serializeNBT() : CompoundTag {
        val tag = CompoundTag()

        if(fluids.isNotEmpty()) {
            val list = ListTag()

            fluids.forEach { stack ->
                val stackTag = CompoundTag()
                stack.writeToNBT(stackTag)
                list.add(stackTag)
            }

            tag.put("fluids", list)
        }

        return tag
    }

    fun deserializeNBT(tag: CompoundTag) {
        fluids.clear()

        if(tag.contains("fluids")) {
            val list = tag.getListTag("fluids")

            list.forEachCompound { stackTag ->
                val stack = FluidStack.loadFluidStackFromNBT(stackTag)
                fluids.add(stack)
            }
        }

        incrementVersion()
    }
}

/**
 * Wrapper for [MultipleFluidTank] that allows extraction of a main product (based on purity).
 * Fluid is always extractable if there is only one fluid stack in the tank.
 * If there are multiple fluid stacks and one of them occupies more than [purityThreshold]`%` of the total amount in the tank, that *representative* fluid stack can be extracted, and this process voids part of the other fluids in the tank.
 * Check [drain] for more information (the details aren't exactly trivial due to the amounts being integers).
 * */
open class PurityBasedMultipleFluidTank(val parent: MultipleFluidTank, val purityThreshold: Double = 0.95) : IFluidHandler by parent {
    /**
     * Gets the index of the representative fluid (based on [purityThreshold]), or `-1` if no fluids match.
     * */
    fun findRepresentativeIndex() : Int {
        val amount = parent.amount

        if(amount == 0) {
            return -1
        }

        val recip = 1.0 / amount.toDouble()

        parent.fluids.forEachIndexed { index, stack ->
            if((stack.amount.toDouble() * recip) > purityThreshold) {
                return index
            }
        }

        return -1
    }

    /**
     * Maybe indicates to extractor pipes that this should only have one fluid. I'm not sure if it's necessary.
     * The thinking is: pipes might see this and think to query [getFluidInTank]`(0)`, which returns the representative.
     * */
    override fun getTanks() = 1

    /**
     * If we have a representative fluid, we will give out that fluid. Otherwise, empty.
     * */
    override fun getFluidInTank(tank: Int): FluidStack {
        val index = findRepresentativeIndex()

        if(index == -1) {
            return FluidStack.EMPTY
        }

        return parent.fluids[index]
    }

    /**
     * Applies the extraction rule if the representative stack matches the [resource].
     * If applicable, it redirects to the other drain method.
     * */
    override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
        /**
         * Handles case 1:
         * */
        if(resource.amount <= 0 || parent.fluids.size <= 1) {
            return parent.drain(resource, action)
        }

        val index = findRepresentativeIndex()

        if(index == -1) {
            /**
             * Not applicable:
             * */
            return FluidStack.EMPTY
        }

        val stack = parent.fluids[index]

        if(stack.fluid != resource.fluid) {
            /**
             * Requested fluid doesn't match (not sure why mods would even make this sort of request):
             * */
            return FluidStack.EMPTY
        }

        return drain(resource.amount, action)
    }

    /**
     * Drains by purity. If there is a representative stack based on purity, we drain some part of it and destroy part of all the rest of the fluid in the tank.
     * The amounts to process are determined as follows:
     * - the total amount of fluid to remove (including impurities) is `amountToDrain = min(`[maxDrain]`, total)` where `total` is the total fluid in [parent]
     * - the amount of representative to drain is `floor(amountToDrain × purity)`, where `purity` is the actual purity of the representative (not the [purityThreshold]).
     * - if the [maxDrain] is small, the amount of representative to drain can be `0`, and the method will not do anything if that's the case. Otherwise:
     * - the amount of impurity removed will be what's remaining from `amountToDrain` (`amountToDrainVoid`)
     *
     * The result returned will be a stack with the amount of representative to drain, but some difficulty arises when trying to drain the impurities.
     * Their amounts are integers, so the following algorithm is applied:
     * - a first pass is done over all the stacks (except the representative), that voids `amountToVoid = floor((amount / totalImpurity) * amountToDrainVoid)`, where `totalImpurity` is the initial `amount` minus the representative to drain. This rounds down so it doesn't guarantee all `amountToDrainVoid` will be covered.
     * - a second pass is done if `amountToDrainVoid` wasn't covered by the first pass. This picks the largest stacks and drains the remaining amount from them.
     * */
    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
        /**
         * Handles case 1:
         * */
        if(maxDrain <= 0 || parent.fluids.size <= 1) {
            return parent.drain(maxDrain, action)
        }

        val index = findRepresentativeIndex()

        if(index == -1) {
            /**
             * Not applicable:
             * */
            return FluidStack.EMPTY
        }

        /**
         * Fluid we are draining from:
         * */
        val representativeStack = parent.fluids[index]
        val initialRepresentativeAmount = representativeStack.amount
        val totalVolume = parent.amount

        /**
         * The purity (in representative fluid) of the mixture we're removing:
         * */
        val actualPurity = initialRepresentativeAmount / totalVolume.toDouble()

        /**
         * The total fluid to remove from the tank (including voided impurities).
         * */
        val amountToDrain = min(maxDrain, totalVolume)

        /**
         * Amount of representative fluid to drain.
         * Note: if the requested amount is ~1mB or very small, the amount we get here might be 0.
         * */
        val amountToDrainRepresentative = floor(amountToDrain * actualPurity).toInt()

        /**
         * Don't run the voiding if we don't end up extracting anything.
         * This happens if the request is very small, and it got rounded down:
         * */
        if(amountToDrainRepresentative == 0) {
            return FluidStack.EMPTY
        }

        /**
         * Amount of everything else to drain:
         * */
        val amountToDrainVoid = amountToDrain - amountToDrainRepresentative

        val result = FluidStack(representativeStack.fluid, amountToDrainRepresentative)

        if(action == IFluidHandler.FluidAction.EXECUTE) {
            /**
             * Drains the representative:
             * */
            parent.drain(result, IFluidHandler.FluidAction.EXECUTE)

            if(amountToDrainVoid > 0) {
                val totalImpurity = totalVolume - initialRepresentativeAmount

                if(totalImpurity > 0) {
                    var remainingVoid = amountToDrainVoid

                    /**
                     * First pass: removes impurities volumetrically, by truncating the required amounts:
                     * */
                    val iterator = parent.fluids.listIterator()
                    while (remainingVoid > 0 && iterator.hasNext()) {
                        val stack = iterator.next()

                        if(stack.fluid == representativeStack.fluid) {
                            continue
                        }

                        var amountToVoid = floor((stack.amount.toDouble() / totalImpurity) * amountToDrainVoid).toInt()

                        if (amountToVoid == 0) {
                            /**
                             * Forces at least 1mB for this very low concentration impurity.
                             * I hope this has the effect of completely destroying small impurities over time.
                             * */
                            amountToVoid = 1
                        }

                        amountToVoid = min(amountToVoid, stack.amount)
                        amountToVoid = min(amountToVoid, remainingVoid)

                        stack.amount -= amountToVoid
                        remainingVoid -= amountToVoid

                        if (stack.amount <= 0) {
                            iterator.remove()
                        }
                    }

                    /**
                     * Second pass: if the rounding from the first pass left any unvoided amount, randomly pick a stack and remove:
                     * */
                    while (remainingVoid > 0) {
                        val validIndices = parent.fluids.indices.filter {
                            parent.fluids[it].fluid != representativeStack.fluid
                        }

                        if(validIndices.isEmpty()) {
                            /**
                             * Nothing left to void:
                             * */
                            break
                        }

                        val targetStackIndex = validIndices.maxBy {
                            parent.fluids[it].amount
                        }

                        val targetStack = parent.fluids[targetStackIndex]
                        val amountToVoid = min(targetStack.amount, remainingVoid)

                        targetStack.amount -= amountToVoid
                        remainingVoid -= amountToVoid

                        if (targetStack.amount <= 0) {
                            parent.fluids.removeAt(targetStackIndex)
                        }
                    }
                }
            }
        }

        return result
    }
}

/**
 * Wrapper for [MultipleFluidTank] that allows extraction based on phase separation.
 * The fluids in [parent] must be thermal fluids.
 *
 * This wrapper builds an internal data structure ([phases] and [phaseDensities]).
 * A phase is a set of fluid stacks that are all miscible in each other. Fluids of separate phases don't share any mixability tags.
 * The density of each phase is calculated and stored in [phaseDensities]. P.S. the [phases] are not sorted.
 * Extraction logic:
 * - Data structure gets built if the version changed
 * - The phase with the highest density is selected
 * - If that phase is composed of only one fluid, then this is the fluid that will be extracted
 * */
open class GravityBasedMultipleFluidTank(val parent: MultipleFluidTank) : IFluidHandler by parent {
    companion object {
        private val pool = LinearObjectPool<ArrayList<FluidStack>>(object : PooledObjectPolicy<ArrayList<FluidStack>> {
            override fun create(): ArrayList<FluidStack> {
                return ArrayList()
            }

            override fun release(obj: ArrayList<FluidStack>): Boolean {
                if (obj.size > 16) {
                    LOG.warn("Got phase bucket of size ${obj.size}")
                    return false
                }

                obj.clear()
                return true
            }
        }, 4096)
    }

    private var lastVersion = -1

    /**
     * Fluids sorted into buckets that dissolved in each other.
     * */
    private val phases = ArrayList<ArrayList<FluidStack>>()
    /**
     * Phase densities. Only re-allocated if it needs to grow.
     * */
    private var phaseDensities = DoubleArray(0)

    /**
     * Builds [phases] and [phaseDensities] if the version changed.
     * */
    @OnServerThread
    fun updatePhases() {
        if(lastVersion == parent.version) {
            return
        }

        lastVersion = parent.version

        phases.forEach { pool.release(it) }
        phases.clear()

        /**
         * Builds the phases:
         * */
        pool.using { remaining ->
            remaining.addAll(parent.fluids)

            while (remaining.isNotEmpty()) {
                val front = remaining.removeLast()
                val a = PhysicalFluidManager.requireProperties(front.fluid)

                val phase = pool.get()
                phase.add(front)

                remaining.removeAll { candidate ->
                    val b = PhysicalFluidManager.requireProperties(candidate.fluid)

                    if(PhysicalFluidManager.areMixable(a, b)) {
                        phase.add(candidate)
                        true
                    }
                    else {
                        false
                    }
                }

                phases.add(phase)
            }
        }

        if(phaseDensities.size < phases.size) {
            phaseDensities = DoubleArray(phases.size)
        }

        /**
         * Calculates phase densities:
         * */
        phases.forEachIndexed { index, phase ->
            var mass = 0.0
            var volume = 0.0

            phase.forEach { stack ->
                mass += !PhysicalFluidManager.requireProperties(stack.fluid).density * stack.amount
                volume += stack.amount
            }

            phaseDensities[index] = mass / volume
        }
    }

    /**
     * Gets the index of the fluid separated by gravity and density, or `-1` if no fluids match.
     * Not thread-safe!
     * */
    @OnServerThread
    fun findSeparatingIndex() : Int {
        if(parent.fluids.isEmpty()) {
            return -1
        }

        updatePhases()

        /**
         * This is the phase that would be sitting at the bottom:
         * */
        val targetPhaseIndex = phases.indices.maxBy { phaseDensities[it] }

        if(phases[targetPhaseIndex].size == 1) {
            return targetPhaseIndex
        }

        return -1
    }

    /**
     * Gets the fluid separated by gravity or null, if there isn't one.
     * */
    @OnServerThread
    fun findSeparatingStack() : FluidStack? {
        val index = findSeparatingIndex()

        if(index == -1) {
            return null
        }

        return phases[index][0]
    }

    /**
     * Drains if the [resource] matches the separating fluid stack, delegating to the other drain method.
     * */
    override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
        if(resource.amount <= 0) {
            return FluidStack.EMPTY
        }

        val targetStack = findSeparatingStack()
            ?: return FluidStack.EMPTY

        if(targetStack.fluid != resource.fluid) {
            return FluidStack.EMPTY
        }

        return parent.drain(FluidStack(targetStack.fluid, min(resource.amount, targetStack.amount)), action)
    }

    /**
     * Drains the separating fluid stack.
     * */
    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
        if(maxDrain <= 0) {
            return FluidStack.EMPTY
        }

        val targetStack = findSeparatingStack()
            ?: return FluidStack.EMPTY

        return parent.drain(FluidStack(targetStack.fluid, min(maxDrain, targetStack.amount)), action)
    }

    fun debugView(list: ComponentDisplayList) {
        if(!ELN2_DEBUG) {
            return
        }

        val phases = phases
            .mapIndexed { idx, phase -> phase to phaseDensities[idx] }
            .sortedByDescending { it.second }

        phases.forEachIndexed { idx, (phase, density) ->
            list.debugInIDE { "Phase $idx: " +
                "${phase.joinToString(", ") { it.displayName.toString() }}: " +
                Quantity(density, KILOGRAM_PER_MILLIBUCKET).classify()
            }
        }
    }
}

//#endregion

//#region Fractional Tanks

/**
 * Re-implementation of [MultipleFluidTank] that uses [FractionalFluidStack]s.
 * */
open class MultipleFractionalFluidTank(var capacity: Double, val requireThermalFluid: Boolean) : IFractionalFluidHandler {
    var version = 0
        private set

    val fluids = ArrayList<FractionalFluidStack>()

    /**
     * Gets the total amount of fluid in this tank (unless externally modified, it should be less than or equal to [capacity]).
     * */
    val amount: Double get() = fluids.sumOf { it.amount }

    /**
     * Gets the free volume in the tank. If the [amount] is larger than [capacity], the result will be 0.
     * */
    val remainingCapacity: Double get() {
        val currentAmount = amount

        if(currentAmount >= capacity) {
            return 0.0
        }

        return capacity - currentAmount
    }

    open fun incrementVersion() {
        version++
    }

    /**
     * Removes stacks with sub-epsilon amounts.
     * */
    fun trim() {
        val iterator = fluids.iterator()
        while (iterator.hasNext()) {
            val stack = iterator.next()

            if(stack.isEmpty) {
                iterator.remove()
            }
        }
    }

    /**
     * Rearranges the data so it obeys the first 2 rules. After this, the entries in [fluids] will be non-empty, all distinct fluid types.
     * Should only be used if [fluids] were modified by an algorithm externally, and the fluids don't respect the rules anymore.
     * */
    fun compact() {
        val temp = LinkedHashMap<Fluid, Double>()

        fluids.forEach { stack ->
            if(!stack.isEmpty && stack.fluid != Fluids.EMPTY && stack.amount > 0.0) {
                temp.merge(stack.fluid, stack.amount) { a, b -> a + b }
            }
        }

        fluids.clear()

        temp.forEach { (fluid, amount) ->
            val stack = FractionalFluidStack(fluid, amount)
            fluids.add(stack)
        }

        temp.clear()

        incrementVersion()
    }

    /**
     * We return [fluids]` + 1` to indicate there is always some available tank to accept fluid. Not sure if it's even useful.
     * */
    override fun getTanks() = fluids.size + 1

    /**
     * Gets the exact fluid stack in the [tank] slot. Returns an empty stack if the [tank] is outside the range of the [fluids] list.
     * */
    override fun getFractionalFluidInTank(tank: Int): FractionalFluidStack {
        if(tank in fluids.indices) {
            return fluids[tank]
        }

        return FractionalFluidStack.EMPTY
    }

    /**
     * Gets the [remainingCapacity].
     * */
    override fun getFractionalTankCapacity(tank: Int) = remainingCapacity

    /**
     * Gets the **quantized** fluid stack in the [tank] slot. Returns an empty stack if the [tank] is outside the range of the [fluids] list.
     * */
    override fun getFluidInTank(tank: Int): FluidStack {
        if(tank in fluids.indices) {
            return fluids[tank].quantized()
        }

        return FluidStack.EMPTY
    }

    /**
     * Gets the remaining capacity by **rounding down**.
     * */
    override fun getTankCapacity(tank: Int) = floor(remainingCapacity).toInt()

    /**
     * Checks if [fluid] is allowed to be inserted in the tank.
     * */
    private fun isFluidAllowed(fluid: Fluid) : Boolean {
        if(!requireThermalFluid) {
            return true
        }

        return PhysicalFluidManager.getProperties(fluid) != null
    }

    override fun isFluidValid(tank: Int, stack: FluidStack) = isFluidAllowed(stack.fluid)

    /**
     * Increments the existing stack in [fluids] or creates a new one, while respecting the [capacity].
     *
     * The remaining capacity is **rounded down**.
     * */
    override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
        if(!isFluidAllowed(resource.fluid)) {
            return 0
        }

        val resourceToFill = min(resource.amount, floor(remainingCapacity).toInt())

        if(resourceToFill == 0) {
            return 0
        }

        if(action != IFluidHandler.FluidAction.SIMULATE) {
            val existingIndex = fluids.indexOfFirst { it.fluid == resource.fluid }

            if(existingIndex == -1) {
                /**
                 * Insert new stack:
                 * */
                fluids.add(FractionalFluidStack(resource.fluid, resourceToFill.toDouble()))
            }
            else {
                /**
                 * Increase existing stack:
                 * */
                val newStack = FractionalFluidStack(resource.fluid, resourceToFill + fluids[existingIndex].amount)
                fluids[existingIndex] = newStack
            }

            incrementVersion()
        }

        return resourceToFill
    }

    /**
     * Fractional fill implementation.
     * */
    override fun fillFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): Double {
        if(!isFluidAllowed(resource.fluid)) {
            return 0.0
        }

        val resourceToFill = min(resource.amount, remainingCapacity)

        if(resourceToFill < FractionalFluidStack.EPSILON) {
            return 0.0
        }

        if(action != IFluidHandler.FluidAction.SIMULATE) {
            val existingIndex = fluids.indexOfFirst { it.fluid == resource.fluid }

            if(existingIndex == -1) {
                /**
                 * Insert new stack:
                 * */
                fluids.add(FractionalFluidStack(resource.fluid, resourceToFill))
            }
            else {
                /**
                 * Increase existing stack:
                 * */
                val newStack = FractionalFluidStack(resource.fluid, resourceToFill + fluids[existingIndex].amount)
                fluids[existingIndex] = newStack
            }

            incrementVersion()
        }

        return resourceToFill
    }

    /**
     * Decrements the fluid at [index] or removes the stack entirely, if all fluid was removed (**using comparison with [FractionalFluidStack.EPSILON]!**)
     * */
    fun removeAmount(index: Int, fluidToDrain: Double) {
        if(fluidToDrain <= 0.0) {
            return
        }

        val stack = fluids[index]

        /**
         * Checks if this drains the entire fluid, which means it needs to be removed. Slightly different from the integer implementation:
         * */
        if(fluidToDrain.approxEq(stack.amount, FractionalFluidStack.EPSILON) || fluidToDrain > stack.amount) {
            fluids.removeAt(index)
        }
        /**
         * Decrements the amount of fluid:
         * */
        else {
            fluids[index] = FractionalFluidStack(stack.fluid, stack.amount - fluidToDrain)
        }

        incrementVersion()
    }

    /**
     * Tries to drain [resource] from the stack in [fluids] that matches, and removes the stack if all fluid was drained.
     * Doesn't scan all stacks (it is assumed the tank obeys the first 2 rules).
     *
     * **Rounds down with [FractionalFluidStack.roundedAmount]!**
     * */
    override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
        if(resource.amount <= 0) {
            return FluidStack.EMPTY
        }

        for (i in fluids.indices) {
            val stack = fluids[i]

            if(stack.fluid == resource.fluid) {
                val roundedAmount = stack.roundedAmount

                if(roundedAmount == 0) {
                    /**
                     * Doesn't quite reach one full mB:
                     * */
                    return FluidStack.EMPTY
                }

                val fluidToDrain = min(resource.amount, roundedAmount)
                val result = FluidStack(resource.fluid, fluidToDrain)

                if(action == IFluidHandler.FluidAction.EXECUTE) {
                    removeAmount(i, fluidToDrain.toDouble())
                }

                return result
            }
        }

        return FluidStack.EMPTY
    }

    /**
     * Fractional implementation of drain, with the same base behavior.
     * */
    override fun drainFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): FractionalFluidStack {
        if(resource.amount <= 0.0) {
            return FractionalFluidStack.EMPTY
        }

        for (i in fluids.indices) {
            val stack = fluids[i]

            if(stack.fluid == resource.fluid) {
                val fluidToDrain = min(resource.amount, stack.amount)
                val result = FractionalFluidStack(resource.fluid, fluidToDrain)

                if(action == IFluidHandler.FluidAction.EXECUTE) {
                    removeAmount(i, fluidToDrain)
                }

                return result
            }
        }

        return FractionalFluidStack.EMPTY
    }

    /**
     * Drains from the first slot in [fluids] that has a **nonzero [FractionalFluidStack.roundedAmount]**.
     * */
    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
        if(maxDrain <= 0) {
            return FluidStack.EMPTY
        }

        if(fluids.isEmpty()) {
            return FluidStack.EMPTY
        }

        for (stack in fluids) {
            val roundedAmount = stack.roundedAmount

            if(roundedAmount == 0) {
                continue
            }

            val result = FluidStack(stack.fluid, roundedAmount)

            if(action != IFluidHandler.FluidAction.SIMULATE) {
                removeAmount(0, result.amount.toDouble())
            }

            return result
        }

        return FluidStack.EMPTY
    }

    /**
     * Fractional implementation of drain, with the same base behavior.
     * */
    override fun drainFractional(maxDrain: Double, action: IFluidHandler.FluidAction): FractionalFluidStack {
        if(maxDrain <= 0) {
            return FractionalFluidStack.EMPTY
        }

        if(fluids.isEmpty()) {
            return FractionalFluidStack.EMPTY
        }

        val stack = fluids[0]
        val result = FractionalFluidStack(stack.fluid, min(stack.amount, maxDrain))

        if(action != IFluidHandler.FluidAction.SIMULATE) {
            removeAmount(0, result.amount)
        }

        return result
    }

    fun serializeNBT() : CompoundTag {
        val tag = CompoundTag()

        if(fluids.isNotEmpty()) {
            val list = ListTag()

            fluids.forEach { stack ->
                val stackTag = CompoundTag()
                stack.writeToNbt(stackTag)
                list.add(stackTag)
            }

            tag.put("fluids", list)
        }

        return tag
    }

    fun deserializeNBT(tag: CompoundTag) {
        fluids.clear()

        if(tag.contains("fluids")) {
            val list = tag.getListTag("fluids")

            list.forEachCompound { stackTag ->
                val stack = FractionalFluidStack.fromNbt(stackTag)
                fluids.add(stack)
            }
        }

        incrementVersion()
    }
}

/**
 * Re-implementation of [PurityBasedMultipleFluidTank].
 * */
open class PurityBasedMultipleFractionalFluidTank(val parent: MultipleFractionalFluidTank, val purityThreshold: Double = 0.95) : IFractionalFluidHandler by parent {
    /**
     * Gets the index of the representative fluid (based on [purityThreshold]), or `-1` if no fluids match.
     *
     * **Doesn't round down!**
     * */
    fun findRepresentativeIndex() : Int {
        val amount = parent.amount

        // The epsilon comparison isn't strictly needed, actually:
        if(amount < FractionalFluidStack.EPSILON) {
            return -1
        }

        val recip = 1.0 / amount

        parent.fluids.forEachIndexed { index, stack ->
            if((stack.amount * recip) > purityThreshold) {
                return index
            }
        }

        return -1
    }

    /**
     * Maybe indicates to extractor pipes that this should only have one fluid. I'm not sure if it's necessary.
     * The thinking is: pipes might see this and think to query [getFluidInTank]`(0)`, which returns the representative.
     * */
    override fun getTanks() = 1

    /**
     * If we have a representative fluid, we will give out that fluid with **[FractionalFluidStack.quantized]**. Otherwise, empty.
     * */
    override fun getFluidInTank(tank: Int): FluidStack {
        val index = findRepresentativeIndex()

        if(index == -1) {
            return FluidStack.EMPTY
        }

        return parent.fluids[index].quantized()
    }

    /**
     * Applies the extraction rule if the representative stack matches the [resource].
     * If applicable, it redirects to the other drain method.
     * */
    override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
        /**
         * Handles case 1:
         * */
        if(resource.amount <= 0 || parent.fluids.size <= 1) {
            return parent.drain(resource, action)
        }

        val index = findRepresentativeIndex()

        if(index == -1) {
            /**
             * Not applicable:
             * */
            return FluidStack.EMPTY
        }

        val stack = parent.fluids[index]

        if(stack.fluid != resource.fluid) {
            /**
             * Requested fluid doesn't match (not sure why mods would even make this sort of request):
             * */
            return FluidStack.EMPTY
        }

        return drain(resource.amount, action)
    }

    /**
     * Fractional purity-based drain.
     * */
    override fun drainFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): FractionalFluidStack {
        /**
         * Handles case 1:
         * */
        if(resource.amount <= 0.0 || parent.fluids.size <= 1) {
            return parent.drainFractional(resource, action)
        }

        val index = findRepresentativeIndex()

        if(index == -1) {
            /**
             * Not applicable:
             * */
            return FractionalFluidStack.EMPTY
        }

        val stack = parent.fluids[index]

        if(stack.fluid != resource.fluid) {
            /**
             * Requested fluid doesn't match (not sure why mods would even make this sort of request):
             * */
            return FractionalFluidStack.EMPTY
        }

        return drainFractional(resource.amount, action)
    }

    /**
     * Drains by purity, **without any internal rounding**; rounding is only applied to the result.
     * If there is a representative stack based on purity, we drain some part of it and destroy part of all the rest of the fluid in the tank.
     * The amounts to process are determined as follows:
     * - the total amount of fluid to remove (including impurities) is `amountToDrain = min(`[maxDrain]`, total)` where `total` is the total fluid in [parent]
     * - the amount of representative to drain is `floor(amountToDrain × purity)`, where `purity` is the actual purity of the representative (not the [purityThreshold]).
     * - if the [maxDrain] is small, the amount of representative to drain can be `0`, and the method will not do anything if that's the case. Otherwise:
     * - the amount of impurity removed will be what's remaining from `amountToDrain` (`amountToDrainVoid`)
     *
     * The result returned will be a stack with the amount of representative to drain, but some difficulty arises when trying to drain the impurities.
     * Their amounts are integers, so the following algorithm is applied:
     * - a first pass is done over all the stacks (except the representative), that voids `amountToVoid = floor((amount / totalImpurity) * amountToDrainVoid)`, where `totalImpurity` is the initial `amount` minus the representative to drain. This rounds down so it doesn't guarantee all `amountToDrainVoid` will be covered.
     * - a second pass is done if `amountToDrainVoid` wasn't covered by the first pass. This picks the largest stacks and drains the remaining amount from them.
     * */
    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
        /**
         * Handles case 1:
         * */
        if(maxDrain <= 0 || parent.fluids.size <= 1) {
            return parent.drain(maxDrain, action)
        }

        val index = findRepresentativeIndex()

        if(index == -1) {
            /**
             * Not applicable:
             * */
            return FluidStack.EMPTY
        }

        /**
         * Fluid we are draining from:
         * */
        val representativeStack = parent.fluids[index]
        val initialRepresentativeAmount = representativeStack.amount
        val totalVolume = parent.amount

        /**
         * The purity (in representative fluid) of the mixture we're removing:
         * */
        val actualPurity = initialRepresentativeAmount / totalVolume

        /**
         * The total fluid to remove from the tank (including voided impurities).
         * */
        val amountToDrain = min(maxDrain.toDouble(), totalVolume)

        /**
         * Amount of representative fluid to drain.
         * Note: if the requested amount is ~1mB or very small, the amount we get here might be 0.
         * */
        val amountToDrainRepresentative = floor(amountToDrain * actualPurity).toInt()

        /**
         * Don't run the voiding if we don't end up extracting anything.
         * This happens if the request is very small, and it got rounded down:
         * */
        if(amountToDrainRepresentative == 0) {
            return FluidStack.EMPTY
        }

        /**
         * Amount of everything else to drain:
         * */
        val amountToDrainVoid = amountToDrain - amountToDrainRepresentative

        val result = FluidStack(representativeStack.fluid, amountToDrainRepresentative)

        if(action == IFluidHandler.FluidAction.EXECUTE) {
            /**
             * Drains the representative:
             * */
            parent.drain(result, IFluidHandler.FluidAction.EXECUTE)

            if(amountToDrainVoid > 0) {
                val totalImpurity = totalVolume - initialRepresentativeAmount

                if(totalImpurity > 0) {
                    var remainingVoid = amountToDrainVoid

                    /**
                     * Single pass: drains the impurity and removes the stacks if their amount fell below epsilon:
                     * */
                    val iterator = parent.fluids.listIterator()
                    while (remainingVoid > 0.0 && iterator.hasNext()) {
                        val stack = iterator.next()

                        if(stack.fluid == representativeStack.fluid) {
                            continue
                        }

                        var amountToVoid = (stack.amount / totalImpurity) * amountToDrainVoid
                        amountToVoid = min(amountToVoid, stack.amount)
                        amountToVoid = min(amountToVoid, remainingVoid)

                        stack.amount -= amountToVoid
                        remainingVoid -= amountToVoid

                        if (stack.amount < FractionalFluidStack.EPSILON) {
                            iterator.remove()
                        }
                    }
                }
            }
        }

        return result
    }

    /**
     * Fractional purity-based drain.
     * */
    override fun drainFractional(maxDrain: Double, action: IFluidHandler.FluidAction): FractionalFluidStack {
        /**
         * Handles case 1:
         * */
        if(maxDrain <= 0.0 || parent.fluids.size <= 1) {
            return parent.drainFractional(maxDrain, action)
        }

        val index = findRepresentativeIndex()

        if(index == -1) {
            /**
             * Not applicable:
             * */
            return FractionalFluidStack.EMPTY
        }

        /**
         * Fluid we are draining from:
         * */
        val representativeStack = parent.fluids[index]
        val initialRepresentativeAmount = representativeStack.amount
        val totalVolume = parent.amount

        /**
         * The purity (in representative fluid) of the mixture we're removing:
         * */
        val actualPurity = initialRepresentativeAmount / totalVolume

        /**
         * The total fluid to remove from the tank (including voided impurities).
         * */
        val amountToDrain = min(maxDrain, totalVolume)

        /**
         * Amount of representative fluid to drain.
         * Note: if the requested amount is ~1mB or very small, the amount we get here might be 0.
         * */
        val amountToDrainRepresentative = amountToDrain * actualPurity

        /**
         * Don't run the voiding if we don't end up extracting anything.
         * This happens if the request is very small, and it got rounded down:
         * */
        if(amountToDrainRepresentative.approxEq(0.0, FractionalFluidStack.EPSILON)) {
            return FractionalFluidStack.EMPTY
        }

        /**
         * Amount of everything else to drain:
         * */
        val amountToDrainVoid = amountToDrain - amountToDrainRepresentative

        val result = FractionalFluidStack(representativeStack.fluid, amountToDrainRepresentative)

        if(action == IFluidHandler.FluidAction.EXECUTE) {
            /**
             * Drains the representative:
             * */
            parent.drainFractional(result, IFluidHandler.FluidAction.EXECUTE)

            if(amountToDrainVoid > 0.0) {
                val totalImpurity = totalVolume - initialRepresentativeAmount

                if(totalImpurity > 0.0) {
                    var remainingVoid = amountToDrainVoid

                    /**
                     * Single pass: drains the impurity and removes the stacks if their amount fell below epsilon:
                     * */
                    val iterator = parent.fluids.listIterator()
                    while (remainingVoid > 0.0 && iterator.hasNext()) {
                        val stack = iterator.next()

                        if(stack.fluid == representativeStack.fluid) {
                            continue
                        }

                        var amountToVoid = (stack.amount / totalImpurity) * amountToDrainVoid
                        amountToVoid = min(amountToVoid, stack.amount)
                        amountToVoid = min(amountToVoid, remainingVoid)

                        stack.amount -= amountToVoid
                        remainingVoid -= amountToVoid

                        if (stack.amount < FractionalFluidStack.EPSILON) {
                            iterator.remove()
                        }
                    }
                }
            }
        }

        return result
    }
}

/**
 * Re-implementation of [GravityBasedMultipleFluidTank].
 * */
open class GravityBasedMultipleFractionalFluidTank(val parent: MultipleFractionalFluidTank) : IFractionalFluidHandler by parent {
    companion object {
        private val pool = LinearObjectPool<ArrayList<FractionalFluidStack>>(object : PooledObjectPolicy<ArrayList<FractionalFluidStack>> {
            override fun create(): ArrayList<FractionalFluidStack> {
                return ArrayList()
            }

            override fun release(obj: ArrayList<FractionalFluidStack>): Boolean {
                if (obj.size > 16) {
                    LOG.warn("Got fractional phase bucket of size ${obj.size}")
                    return false
                }

                obj.clear()
                return true
            }
        }, 4096)
    }

    private var lastVersion = -1

    /**
     * Fluids sorted into buckets that dissolved in each other.
     * */
    private val phases = ArrayList<ArrayList<FractionalFluidStack>>()
    /**
     * Phase densities. Only re-allocated if it needs to grow.
     * */
    private var phaseDensities = DoubleArray(0)

    /**
     * Builds [phases] and [phaseDensities] if the version changed.
     * */
    @OnServerThread
    fun updatePhases() {
        if(lastVersion == parent.version) {
            return
        }

        lastVersion = parent.version

        phases.forEach { pool.release(it) }
        phases.clear()

        /**
         * Builds the phases:
         * */
        pool.using { remaining ->
            remaining.addAll(parent.fluids)

            while (remaining.isNotEmpty()) {
                val front = remaining.removeLast()
                val a = PhysicalFluidManager.requireProperties(front.fluid)

                val phase = pool.get()
                phase.add(front)

                remaining.removeAll { candidate ->
                    val b = PhysicalFluidManager.requireProperties(candidate.fluid)

                    if(PhysicalFluidManager.areMixable(a, b)) {
                        phase.add(candidate)
                        true
                    }
                    else {
                        false
                    }
                }

                phases.add(phase)
            }
        }

        if(phaseDensities.size < phases.size) {
            phaseDensities = DoubleArray(phases.size)
        }

        /**
         * Calculates phase densities:
         * */
        phases.forEachIndexed { index, phase ->
            var mass = 0.0
            var volume = 0.0

            phase.forEach { stack ->
                mass += !PhysicalFluidManager.requireProperties(stack.fluid).density * stack.amount
                volume += stack.amount
            }

            phaseDensities[index] = mass / volume
        }
    }

    /**
     * Gets the index of the fluid separated by gravity and density, or `-1` if no fluids match.
     * Not thread-safe!
     * */
    @OnServerThread
    fun findSeparatingIndex() : Int {
        if(parent.fluids.isEmpty()) {
            return -1
        }

        updatePhases()

        /**
         * This is the phase that would be sitting at the bottom:
         * */
        val targetPhaseIndex = phases.indices.maxBy { phaseDensities[it] }

        if(phases[targetPhaseIndex].size == 1) {
            return targetPhaseIndex
        }

        return -1
    }

    /**
     * Gets the fluid separated by gravity or null, if there isn't one.
     * */
    @OnServerThread
    fun findSeparatingStack() : FractionalFluidStack? {
        val index = findSeparatingIndex()

        if(index == -1) {
            return null
        }

        return phases[index][0]
    }

    /**
     * Drains if the [resource] matches the separating fluid stack, delegating to the other drain method, by **rounding down the separating stack**.
     * */
    override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
        if(resource.amount <= 0) {
            return FluidStack.EMPTY
        }

        val targetStack = findSeparatingStack()
            ?: return FluidStack.EMPTY

        if(targetStack.fluid != resource.fluid) {
            return FluidStack.EMPTY
        }

        val roundedAmount = targetStack.roundedAmount

        if(roundedAmount == 0) {
            /**
             * Doesn't quite reach one full mB:
             * */
            return FluidStack.EMPTY
        }

        return parent.drain(FluidStack(targetStack.fluid, min(resource.amount, roundedAmount)), action)
    }

    /**
     * Fractional gravity separation.
     * */
    override fun drainFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): FractionalFluidStack {
        if(resource.amount < FractionalFluidStack.EPSILON) {
            return FractionalFluidStack.EMPTY
        }

        val targetStack = findSeparatingStack()
            ?: return FractionalFluidStack.EMPTY

        if(targetStack.fluid != resource.fluid) {
            return FractionalFluidStack.EMPTY
        }

        return parent.drainFractional(FractionalFluidStack(targetStack.fluid, min(resource.amount, targetStack.amount)), action)
    }

    /**
     * Drains the separating fluid stack by **rounding down the separating stack**.
     * */
    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
        if(maxDrain <= 0) {
            return FluidStack.EMPTY
        }

        val targetStack = findSeparatingStack()
            ?: return FluidStack.EMPTY

        val roundedAmount = targetStack.roundedAmount

        if(roundedAmount == 0) {
            /**
             * Doesn't quite reach one full mB:
             * */
            return FluidStack.EMPTY
        }

        return parent.drain(FluidStack(targetStack.fluid, min(maxDrain, roundedAmount)), action)
    }

    override fun drainFractional(maxDrain: Double, action: IFluidHandler.FluidAction): FractionalFluidStack {
        if(maxDrain < FractionalFluidStack.EPSILON) {
            return FractionalFluidStack.EMPTY
        }

        val targetStack = findSeparatingStack()
            ?: return FractionalFluidStack.EMPTY

        return parent.drainFractional(FractionalFluidStack(targetStack.fluid, min(maxDrain, targetStack.amount)), action)
    }

    fun debugView(list: ComponentDisplayList) {
        if(!ELN2_DEBUG) {
            return
        }

        val phases = phases
            .mapIndexed { idx, phase -> phase to phaseDensities[idx] }
            .sortedByDescending { it.second }

        phases.forEachIndexed { idx, (phase, density) ->
            list.debugInIDE { "Phase $idx: " +
                "${phase.joinToString(", ") { it.unit().displayName.toString() }}: " +
                Quantity(density, KILOGRAM_PER_MILLIBUCKET).classify()
            }
        }
    }
}

//#endregion
