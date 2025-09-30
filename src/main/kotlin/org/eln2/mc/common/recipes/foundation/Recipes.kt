package org.eln2.mc.common.recipes.foundation

import com.google.gson.JsonObject
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.GsonHelper
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.*
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraftforge.items.ItemStackHandler
import org.eln2.mc.CrossThreadAccess
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.OnServerThread
import org.eln2.mc.ServerOnly
import java.util.*

// Standard inventory slots for input and output item in processing:
const val INPUT_SLOT = 0
const val OUTPUT_SLOT = 1

interface Eln2Recipe

/**
 * Recipe for converting an item into another item. Can be used for e.g. a crusher.
 * @param input The input ingredient. Must be a single item stack with 1 count.
 * @param output The output item. Must be a single item stack with 1 or more count.
 * @param duration The base duration, in seconds.
 * */
class SimpleProcessingRecipe(val recipeSerializer: Serializer, val recipeId: ResourceLocation, val input: Ingredient, val output: ItemStack, val duration: Double) : Recipe<SimpleContainer>, Eln2Recipe {
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

        return currentRecipe.get().recipeId != previousRecipe.recipeId
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

        check(super.extractItem(INPUT_SLOT, 1, false).count == 1) {
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

    override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        if(slot == INPUT_SLOT) {
            return ItemStack.EMPTY // prevents automation from extracting input
        }

        return super.extractItem(slot, amount, simulate)
    }
}

/**
 * Cell that allows starting/stopping its operation and provides the processing speed.
 * */
interface ProcessingDevice {
    /**
     * Set by the game object when processing is needed.
     * */
    @CrossThreadAccess @OnServerThread
    var isActive: Boolean

    /**
     * Read by the game object and used to advance the recipe.
     * */
    @CrossThreadAccess @OnServerThread
    val processingSpeed: Double
}

/**
 * Server tick for a machine that uses a [ProcessingDevice] and applies a [SimpleProcessingRecipe].
 * */
@ServerOnly
class SimpleProcessingRecipeLoop(val blockEntity: BlockEntity) {
    companion object {
        private const val IS_WORKING = "hasRecipe"
        private const val TIME_PROGRESS = "timeProgress"
    }

    class Operation(val recipe: SimpleProcessingRecipe) {
        var timeProgress = 0.0
    }

    var operation: Operation? = null
    var savedProgress: Double? = null // Level not available in [load], we do the trick the cell block entity does.

    /**
     * @param speed The [ProcessingDevice.processingSpeed], used for rendering.
     * @param progress The progress to sync to the GUI.
     * */
    data class Result(val speed: Double, val progress: Float)

    /**
     * Advances the processing, if the inventory is eligible for operation.
     * Calls [BlockEntity.setChanged] if the NBT needs to be serialized.
     * */
    fun tick(device: ProcessingDevice, inventoryHandler: SimpleProcessingRecipeInventoryHandler<*>) : Result {
        val processingSpeed = device.processingSpeed
        val progress: Float

        if(operation == null) {
            progress = 0.0f

            val recipe = inventoryHandler.searchForRecipe() // Should be fast

            if(recipe.isPresent) {
                device.isActive = true

                // Create new operation:
                operation = Operation(recipe.get())

                if(savedProgress != null) {
                    operation!!.timeProgress = savedProgress!!
                    savedProgress = null
                }

                blockEntity.setChanged()
            }
            else {
                device.isActive = false
            }
        }
        else {
            val op = operation!!

            // Check if input changed, and reset operation if the recipe is different:
            if(inventoryHandler.wasRecipeChanged(op.recipe)) {
                operation = null
                blockEntity.setChanged()
                progress = 0.0f
            }
            else {
                // Progress if we have space for the output.
                // If we don't, we just wait with the current recipe.
                progress = (op.timeProgress / op.recipe.duration).toFloat().coerceIn(0.0f, 1.0f)

                if(inventoryHandler.hasSpaceForExport(op.recipe)) {
                    device.isActive = true
                    op.timeProgress += processingSpeed * (1.0 / 20.0)
                    op.timeProgress = op.timeProgress.coerceIn(0.0, op.recipe.duration)

                    if(op.timeProgress == op.recipe.duration) {
                        // Finish processing:
                        inventoryHandler.exportProcessingResult()
                        operation = null
                    }

                    blockEntity.setChanged()
                }
                else {
                    device.isActive = false
                }
            }
        }

        return Result(processingSpeed, progress)
    }

    fun saveAdditional(pTag: CompoundTag) {
        pTag.putBoolean(IS_WORKING, operation != null)

        if(operation != null) {
            pTag.putDouble(TIME_PROGRESS, operation!!.timeProgress)
        }
    }

    fun load(pTag: CompoundTag) {
        val isWorking = pTag.getBoolean(IS_WORKING)

        if(isWorking) {
            savedProgress = pTag.getDouble(TIME_PROGRESS)
        }
    }
}
