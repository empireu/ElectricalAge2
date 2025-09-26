package org.eln2.mc.common.recipes.foundation

import com.google.gson.JsonObject
import net.minecraft.core.RegistryAccess
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.GsonHelper
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.*
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraftforge.items.ItemStackHandler
import org.eln2.mc.DEBUGGER_BREAK
import java.util.*

// Standard inventory slots for input and output item in processing:
const val INPUT_SLOT = 0
const val OUTPUT_SLOT = 1

/**
 * Recipe for converting an item into another item. Can be used for e.g. a crusher.
 * @param input The input ingredient. Must be a single item stack with 1 count.
 * @param output The output item. Must be a single item stack with 1 or more count.
 * @param duration The base duration, in seconds.
 * */
class SimpleProcessingRecipe(val recipeSerializer: Serializer, val recipeId: ResourceLocation, val input: Ingredient, val output: ItemStack, val duration: Double) : Recipe<SimpleContainer> {
    init {
        require(input.items.size == 1 && input.items[0].count == 1) {
            "Simple processing recipe requires exactly one/one input!"
        }
    }

    override fun matches(pContainer: SimpleContainer, pLevel: Level) = input.test(pContainer.getItem(0))
    override fun assemble(pContainer: SimpleContainer, pRegistryAccess: RegistryAccess): ItemStack = output.copy()
    override fun canCraftInDimensions(pWidth: Int, pHeight: Int) = true
    override fun getResultItem(pRegistryAccess: RegistryAccess): ItemStack = output.copy()

    override fun getId() = recipeId
    override fun getSerializer() = recipeSerializer
    override fun getType() = recipeSerializer.recipeType

    class Serializer(val recipeType: RecipeType<SimpleProcessingRecipe>) : RecipeSerializer<SimpleProcessingRecipe> {
        override fun fromJson(pRecipeId: ResourceLocation, pSerializedRecipe: JsonObject): SimpleProcessingRecipe {
            val input = Ingredient.fromJson(pSerializedRecipe.get("ingredient"))
            val output = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(pSerializedRecipe, "result"))
            val duration = pSerializedRecipe.getAsJsonPrimitive("duration").asDouble
            return SimpleProcessingRecipe(this, pRecipeId, input, output, duration)
        }

        override fun fromNetwork(pRecipeId: ResourceLocation, pBuffer: FriendlyByteBuf): SimpleProcessingRecipe {
            val input = Ingredient.fromNetwork(pBuffer)
            val output = pBuffer.readItem()
            val duration = pBuffer.readDouble()
            return SimpleProcessingRecipe(this, pRecipeId, input, output, duration)
        }

        override fun toNetwork(pBuffer: FriendlyByteBuf, pRecipe: SimpleProcessingRecipe) {
            pRecipe.input.toNetwork(pBuffer)
            pBuffer.writeItem(pRecipe.output)
            pBuffer.writeDouble(pRecipe.duration)
        }
    }
}

/**
 * Inventory handler with size 2 for a processing-like recipe (e.g. smelting, crushing):
 * - Doesn't allow any insert into [OUTPUT_SLOT]
 * - Only allows insertion into [INPUT_SLOT] if the item can be processed.
 * */
class SimpleProcessingRecipeInventoryHandler<B : BlockEntity>(val blockEntity: B, val recipeType: RecipeType<SimpleProcessingRecipe>) : ItemStackHandler(2) {
    private var inputChanged = false

    /**
     * Checks if the input was recently changed, and resets the flag.
     * */
    fun wasInputChanged() : Boolean {
        val result = inputChanged
        inputChanged = false
        return result
    }

    /**
     * If the input was recently changed, checks if the previous recipe is the same as the current one.
     * */
    fun wasRecipeChanged(previousRecipe: SimpleProcessingRecipe) : Boolean {
        if(!wasInputChanged()) {
            return false
        }

        val currentRecipe = searchForRecipe()

        if(currentRecipe.isEmpty) {
            return true
        }

        return currentRecipe.get() == previousRecipe
    }

    /**
     * Prevents inserting items into the output slot.
     * Filters input items by [isItemValid].
     * */
    override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
        if(slot == OUTPUT_SLOT) {
            return stack
        }

        return super.insertItem(slot, stack, simulate)
    }

    fun hasSpaceForExport(recipe: SimpleProcessingRecipe) = super.insertItem(OUTPUT_SLOT, recipe.output, true).isEmpty

    fun exportProcessingResult() {
        val recipeOp = searchForRecipe()

        if(recipeOp.isEmpty) {
            error(DEBUGGER_BREAK("Cannot export processing result: recipe not valid for input"))
        }

        val recipe = recipeOp.get()

        check(extractItem(INPUT_SLOT, 1, false).count == 1) {
            DEBUGGER_BREAK("Did not extract exactly one input item")
        }

        check(super.insertItem(OUTPUT_SLOT, recipe.output.copy(), false).isEmpty) {
            DEBUGGER_BREAK("Could not insert all output items")
        }
    }

    /**
     * Searches for the recipe in the current input slot.
     * Guaranteed to be present if the input slot is not empty because the input is filtered.
     * */
    fun searchForRecipe(): Optional<SimpleProcessingRecipe> {
        val stack = getStackInSlot(INPUT_SLOT)

        if(stack.isEmpty) {
            return Optional.empty<SimpleProcessingRecipe>()
        }

        return blockEntity.level!!.recipeManager.getRecipeFor(
            recipeType,
            SimpleContainer(getStackInSlot(INPUT_SLOT)),
            blockEntity.level!!
        )
    }

    override fun isItemValid(slot: Int, stack: ItemStack): Boolean {
        return if(slot == INPUT_SLOT) {
            val recipeManager = blockEntity.level!!.recipeManager

            val recipe = recipeManager.getRecipeFor(
                recipeType,
                SimpleContainer(stack),
                blockEntity.level!!
            )

            return recipe.isPresent
        }
        else {
            true
        }
    }

    override fun onContentsChanged(slot: Int) {
        if(slot == INPUT_SLOT) {
            inputChanged = true
        }

        blockEntity.setChanged()
    }
}
