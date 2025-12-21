package org.eln2.mc.common.content.processing

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.extensions.forEachCompound
import org.eln2.mc.extensions.getListTag
import kotlin.math.floor
import kotlin.math.min

/**
 * Represents a fluid tank that supports multiple fluids, with a total max volume for the entire tank.
 * The default extraction logic allows separating the fluids freely. This is simply a data structure for holding multiple fluids (**NBT tags are stripped from the fluid stacks**).
 *
 * Rules:
 *  1. Each entry in [fluids] is unique in its [Fluid]
 *  2. All entries have a nonzero amount and non-empty fluid
 *  3. The total sum of the amounts is less than or equal to [capacity]
 * */
open class MultipleFluidTank(val capacity: Int) : IFluidHandler  {
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

    override fun isFluidValid(tank: Int, stack: FluidStack) = true

    /**
     * Increments the existing stack in [fluids] or creates a new one, while respecting the [capacity].
     * */
    override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
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
        }

        return resourceToFill
    }

    /**
     * Decrements the fluid at [index] or removes the stack entirely, if all fluid was removed.
     * [fluidToDrain] must be less than or equal to the stack's amount!
     * */
    fun removeAmount(index: Int, fluidToDrain: Int) {
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
    }
}

/**
 * Wrapper for [MultipleFluidTank] that allows extraction of a main product (based on purity).
 * Fluid is always extractable if there is only one fluid stack in the tank.
 * If there are multiple fluid stacks and one of them occupies more than [purityThreshold]`%` of the total amount in the tank, that *representative* fluid stack can be extracted, and this process voids part of the other fluids in the tank.
 * Check [drain] for more information (the details aren't exactly trivial due to the amounts being integers).
 * */
class PurityBasedMultipleFluidTank(val parent: MultipleFluidTank, val purityThreshold: Double = 0.95) : IFluidHandler by parent {
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
