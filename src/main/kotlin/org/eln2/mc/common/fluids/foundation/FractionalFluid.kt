package org.eln2.mc.common.fluids.foundation

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.registries.ForgeRegistries
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.rounded
import org.eln2.mc.common.fluids.foundation.FractionalFluidStack.Companion.EPSILON
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Fluid stack with a [Double] representation of the amount. Useful for simulations such as boiling, where `1mB` (by convention, `1L`) is too much to handle discretely.
 * For example, the boiling transformation for `1L` of oil would need `250kJ` of energy, which is too large for the smallest possible unit of fluid.
 *
 * This is only used for internal algorithms. Actual [FluidStack]s are handed out to pipes, which is done using the [quantized] API.
 * */
class FractionalFluidStack(val fluid: Fluid, var amount: Double) {
    val isEmpty: Boolean get() = fluid == Fluids.EMPTY || amount < EPSILON
    val isNotEmpty: Boolean get() = !isEmpty

    /**
     * Makes a copy of the instance.
     * */
    fun copy(): FractionalFluidStack = FractionalFluidStack(fluid, amount)

    /**
     * Rounds the [amount] to an integer, taking into account numerical errors using [EPSILON].
     * */
    val roundedAmount : Int get() {
        val upper = ceil(amount)

        if(upper.approxEq(amount, EPSILON)) {
            return upper.toInt()
        }

        return floor(amount).toInt()
    }

    /**
     * Converts the fractional fluid stack into a Minecraft fluid stack using [roundedAmount].
     *
     * If the [roundedAmount] is zero, [FluidStack.EMPTY] will be returned.
     * */
    fun quantized(): FluidStack {
        val rounded = roundedAmount

        if(rounded == 0) {
            return FluidStack.EMPTY
        }

        return FluidStack(fluid, rounded)
    }

    fun unit(): FluidStack {
        return FluidStack(fluid, 1)
    }

    fun writeToNbt(tag: CompoundTag) {
        tag.putString("Eln2FluidName", ForgeRegistries.FLUIDS.getKey(fluid).toString());
        tag.putDouble("Eln2Amount", amount)
    }

    fun toNbt() : CompoundTag {
        val result = CompoundTag()
        writeToNbt(result)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FractionalFluidStack

        if (!amount.approxEq(other.amount, EPSILON)) return false
        if (fluid != other.fluid) return false

        return true
    }

    /**
     * Computes the hash code, only considering the **rounded amount**.
     * */
    override fun hashCode(): Int {
        var result = roundedAmount
        result = 31 * result + fluid.hashCode()
        return result
    }

    override fun toString() = "FractionalFluidStack[$fluid, ${amount.rounded(4)}]"

    companion object {
        const val EPSILON = 1e-6

        val EMPTY = FractionalFluidStack(Fluids.EMPTY, 0.0)

        fun fromNbt(tag: CompoundTag) : FractionalFluidStack {
            if(!tag.contains("Eln2FluidName")) {
                return EMPTY
            }

            val fluidName = ResourceLocation.parse(tag.getString("Eln2FluidName"));
            val fluid = ForgeRegistries.FLUIDS.getValue(fluidName)
                ?: return EMPTY;

            val amount = tag.getDouble("Eln2Amount")

            return FractionalFluidStack(fluid, amount)
        }
    }
}

/**
 * Extension of [IFluidHandler] that also works with fractional fluids.
 * The normal [IFluidHandler] operations should use quantization, as implemented by [MultipleFractionalFluidTank].
 * */
interface IFractionalFluidHandler : IFluidHandler {
    /**
     * Fractional variant of [IFluidHandler.getFluidInTank].
     * */
    fun getFractionalFluidInTank(tank: Int): FractionalFluidStack

    /**
     * Fractional variant of [IFluidHandler.getTankCapacity].
     * */
    fun getFractionalTankCapacity(tank: Int): Double

    /**
     * Fractional variant of [IFluidHandler.fill]. If the [resource]'s amount is less than [FractionalFluidStack.EPSILON], the operation will be ignored.
     * */
    fun fillFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): Double

    /**
     * Fractional variant of [IFluidHandler.drain]. If the [resource]'s amount is less than [FractionalFluidStack.EPSILON], the operation will be ignored.
     * */
    fun drainFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): FractionalFluidStack

    /**
     * Fractional variant of [IFluidHandler.drain]. If the [maxDrain] is less than [FractionalFluidStack.EPSILON], the operation will be ignored.
     * */
    fun drainFractional(maxDrain: Double, action: IFluidHandler.FluidAction): FractionalFluidStack
}
