@file:Suppress("RemoveRedundantQualifierName")

package org.eln2.mc.common.recipes.foundation

import com.google.gson.JsonElement
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
import org.eln2.mc.common.content.processing.CokingRecipe
import org.eln2.mc.extensions.bindToSimpleContainer
import org.eln2.mc.extensions.recipeExists
import java.util.*
import java.util.function.Supplier
import kotlin.math.min

// Standard inventory slots for input and output item in processing.
// Applies to [SimpleProcessingRecipe], [SimpleCatalyzedProcessingRecipe]
const val INPUT_SLOT = 0
const val OUTPUT_SLOT = 1
const val CATALYST_SLOT = 2

// LOL, it's an abuse of the type system, but what are you gonna' do to me?

/**
 * Standard recipe serializer form. Meant to be implemented as a subclass in the custom recipe class.
 * The Recipe ([R]) must implement [Eln2CustomRecipe].
 * */
interface Eln2RecipeSerializer<R> : RecipeSerializer<R> where R : Recipe<SimpleContainer>, R : Eln2CustomRecipe<R> {
    val recipeType: RecipeType<CokingRecipe>
}

/**
 * Standard custom recipe form. The various information (ingredients, durations, and so on) required by the recipe are meant to be added as fields in the constructor.
 * The [recipeSerializer] must be an [Eln2RecipeSerializer] and should be a subclass.
 * The [recipeId] is meant to be implemented as a field in the constructor.
 * */
interface Eln2CustomRecipe<Self> : Recipe<SimpleContainer> where Self : Recipe<SimpleContainer>, Self : Eln2CustomRecipe<Self> {
    val recipeSerializer : Eln2RecipeSerializer<Self>
    val recipeId: ResourceLocation

    override fun getId() = recipeId
    override fun getSerializer() = recipeSerializer
    override fun getType() = recipeSerializer.recipeType
}

/**
 * It seems like we *need* to implement [Recipe] for correctness.
 * But its API is meant for only one output item, so this overrides those boilerplate methods.
 * */
interface Eln2NonStandardRecipe : Recipe<SimpleContainer> {
    override fun assemble(pContainer: SimpleContainer, pRegistryAccess: RegistryAccess): ItemStack = ItemStack.EMPTY
    override fun canCraftInDimensions(pWidth: Int, pHeight: Int) = false
    override fun getResultItem(pRegistryAccess: RegistryAccess): ItemStack = ItemStack.EMPTY
}

/**
 * Recipe for the [ProcessingRecipeInventoryHandler] and [ProcessingRecipeLoop].
 * */
interface Eln2ProcessingLoopRecipe {
    /**
     * The ID of the recipe, including the path of the data file.
     * */
    val recipeId: ResourceLocation

    /**
     * The base duration, in seconds. Granularity of this value is in game ticks.
     * The processing takes [duration] (in equivalent ticks) (excluding I/O) if the device's speed is 1.
     * */
    val duration: Double
}

interface Eln2SimpleOutputProcessingLoopRecipe : Eln2ProcessingLoopRecipe {
    val output: ItemStack
}

/**
 * Processor (probably a [org.eln2.mc.common.cells.foundation.Cell]) that allows starting/stopping its operation and provides the processing speed.
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
 * Recipe for converting an item into another item. Can be used for e.g. a crusher.
 * @param input The input ingredient. Must be a single item stack with 1 count.
 * @param output The output item. Must be a single item with 1 or more count.
 * @param duration The base duration, in seconds.
 * */
class DirectSimpleProcessingRecipe(
    val recipeSerializer: Serializer,
    override val recipeId: ResourceLocation,
    val input: Ingredient,
    override val output: ItemStack,
    override val duration: Double
) : Recipe<SimpleContainer>, Eln2SimpleOutputProcessingLoopRecipe {
    init {
        require(input.items.size > 0 && input.items[0].count == 1) {
            DEBUGGER_BREAK("Simple processing recipe requires exactly one/one input!")
        }
    }

    override fun matches(pContainer: SimpleContainer, pLevel: Level) = input.test(pContainer.getItem(INPUT_SLOT))
    override fun assemble(pContainer: SimpleContainer, pRegistryAccess: RegistryAccess): ItemStack = output.copy()
    override fun canCraftInDimensions(pWidth: Int, pHeight: Int) = true
    override fun getResultItem(pRegistryAccess: RegistryAccess): ItemStack = output.copy()

    override fun getId() = recipeId
    override fun getSerializer() = recipeSerializer
    override fun getType() = recipeSerializer.recipeType

    class Serializer(val recipeType: RecipeType<DirectSimpleProcessingRecipe>) : RecipeSerializer<DirectSimpleProcessingRecipe> {
        override fun fromJson(pRecipeId: ResourceLocation, pSerializedRecipe: JsonObject): DirectSimpleProcessingRecipe {
            val input = Ingredient.fromJson(pSerializedRecipe.get("ingredient"))
            val output = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(pSerializedRecipe, "result"))
            val duration = pSerializedRecipe.getAsJsonPrimitive("duration").asDouble

            return DirectSimpleProcessingRecipe(
                this,
                pRecipeId,
                input,
                output,
                duration
            )
        }

        override fun fromNetwork(pRecipeId: ResourceLocation, pBuffer: FriendlyByteBuf): DirectSimpleProcessingRecipe {
            val input = Ingredient.fromNetwork(pBuffer)
            val output = pBuffer.readItem()
            val duration = pBuffer.readDouble()

            return DirectSimpleProcessingRecipe(
                this,
                pRecipeId,
                input,
                output,
                duration
            )
        }

        override fun toNetwork(pBuffer: FriendlyByteBuf, pRecipe: DirectSimpleProcessingRecipe) {
            pRecipe.input.toNetwork(pBuffer)
            pBuffer.writeItem(pRecipe.output)
            pBuffer.writeDouble(pRecipe.duration)
        }
    }
}

/**
 * Recipe for converting an item into another item, using a catalyst item that isn't consumed. Can be used for e.g. a press.
 * @param input The input ingredient. Must be a single item stack with 1 count.
 * @param catalyst The catalyst. Must abe a single item stack with 1 count.
 * @param output The output item. Must be a single item with 1 or more count.
 * @param duration The base duration, in seconds.
 * */
class CatalyzedSimpleProcessingRecipe(
    val recipeSerializer: Serializer,
    override val recipeId: ResourceLocation,
    val input: Ingredient,
    val catalyst: Ingredient,
    override val output: ItemStack,
    override val duration: Double
) : Recipe<SimpleContainer>, Eln2SimpleOutputProcessingLoopRecipe {
    init {
        require(input.items.size > 0 && input.items.all { it.count == 1 }) {
            DEBUGGER_BREAK("Simple catalyzed processing recipe requires exactly one/one input!")
        }

        require(catalyst.items.size > 0 && catalyst.items.all { it.count == 1 }) {
            DEBUGGER_BREAK("Simple catalyzed processing recipe requires exactly one/one catalyst!")
        }
    }

    override fun matches(pContainer: SimpleContainer, pLevel: Level) =
        input.test(pContainer.getItem(INPUT_SLOT)) &&
        catalyst.test(pContainer.getItem(CATALYST_SLOT))

    override fun assemble(pContainer: SimpleContainer, pRegistryAccess: RegistryAccess): ItemStack = output.copy()
    override fun canCraftInDimensions(pWidth: Int, pHeight: Int) = true
    override fun getResultItem(pRegistryAccess: RegistryAccess): ItemStack = output.copy()

    override fun getId() = recipeId
    override fun getSerializer() = recipeSerializer
    override fun getType() = recipeSerializer.recipeType

    class Serializer(val recipeType: RecipeType<CatalyzedSimpleProcessingRecipe>) : RecipeSerializer<CatalyzedSimpleProcessingRecipe> {
        override fun fromJson(pRecipeId: ResourceLocation, pSerializedRecipe: JsonObject): CatalyzedSimpleProcessingRecipe {
            val input = Ingredient.fromJson(pSerializedRecipe.get("ingredient"))
            val catalyst = Ingredient.fromJson(pSerializedRecipe.get("catalyst"))
            val output = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(pSerializedRecipe, "result"))
            val duration = pSerializedRecipe.getAsJsonPrimitive("duration").asDouble

            return CatalyzedSimpleProcessingRecipe(
                this,
                pRecipeId,
                input,
                catalyst,
                output,
                duration
            )
        }

        override fun fromNetwork(pRecipeId: ResourceLocation, pBuffer: FriendlyByteBuf): CatalyzedSimpleProcessingRecipe {
            val input = Ingredient.fromNetwork(pBuffer)
            val catalyst = Ingredient.fromNetwork(pBuffer)
            val output = pBuffer.readItem()
            val duration = pBuffer.readDouble()

            return CatalyzedSimpleProcessingRecipe(
                this,
                pRecipeId,
                input,
                catalyst,
                output,
                duration
            )
        }

        override fun toNetwork(pBuffer: FriendlyByteBuf, pRecipe: CatalyzedSimpleProcessingRecipe) {
            pRecipe.input.toNetwork(pBuffer)
            pRecipe.catalyst.toNetwork(pBuffer)
            pBuffer.writeItem(pRecipe.output)
            pBuffer.writeDouble(pRecipe.duration)
        }
    }
}

interface ProcessingRecipeInventoryHandler<R : Eln2ProcessingLoopRecipe> {
    /**
     * Checks if the input was recently changed, and resets the flag.
     * */
    fun wasInputChanged() : Boolean

    /**
     * Searches for the recipe in the current input slot. Must be fast (think: called per-tick).
     * Guaranteed to be present if the input slot is not empty because the input is filtered.
     * (**Implementations of the interface must guarantee this filtering logic!**)
     * */
    fun searchForRecipe() : Optional<R>

    /**
     * If the input was recently changed, checks if the previous recipe is the same as the current one.
     * */
    fun wasRecipeChanged(previousRecipe: R) : Boolean {
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
     * Checks if the output slot is compatible with the output item and has enough space.
     * */
    fun hasSpaceForExport(recipe: R) : Boolean

    /**
     * Consumes the input and exports the output.
     * **Only allowed if the input is present and if [hasSpaceForExport] returns true for the recipe returned by [searchForRecipe].**
     * */
    fun execute()
}

class SimpleProcessingRecipeInventoryHandler<R>(
    val onChanged: Runnable,
    val levelSupplier: Supplier<Level>,
    val recipeType: RecipeType<R>,
    size: Int,
    val inputSlots: IntArray
) : ItemStackHandler(size), ProcessingRecipeInventoryHandler<R> where R : Eln2SimpleOutputProcessingLoopRecipe, R : Recipe<SimpleContainer>{
    companion object {
        fun<R> create(
            blockEntity: BlockEntity,
            recipeType: RecipeType<R>,
            size: Int,
            inputSlots: IntArray = intArrayOf(INPUT_SLOT)
        ) where R : Eln2SimpleOutputProcessingLoopRecipe, R : Recipe<SimpleContainer> =
            SimpleProcessingRecipeInventoryHandler<R>(
                blockEntity::setChanged,
                { blockEntity.level ?: error(DEBUGGER_BREAK("Level null in block entity simple processing inventory handler")) },
                recipeType, size, inputSlots
            )
    }

    private var inputChanged = false

    override fun wasInputChanged() : Boolean {
        val result = inputChanged
        inputChanged = false
        return result
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

    override fun hasSpaceForExport(recipe: R) = super.insertItem(OUTPUT_SLOT, recipe.output, true).isEmpty

    override fun execute() {
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

    override fun searchForRecipe(): Optional<R> {
        val stack = getStackInSlot(INPUT_SLOT)

        if(stack.isEmpty) {
            return Optional.empty<R>()
        }

        val level = levelSupplier.get()

        return level.recipeManager.getRecipeFor(
            recipeType,
            this.bindToSimpleContainer(),
            level
        )
    }

    override fun isItemValid(slot: Int, stack: ItemStack): Boolean {
        return if(slot == INPUT_SLOT) {
            val copy = this.bindToSimpleContainer()
            copy.setItem(slot, stack)
            return levelSupplier.get().recipeExists(recipeType, copy)
        }
        else {
            true
        }
    }

    override fun onContentsChanged(slot: Int) {
        if(inputSlots.contains(slot)) {
            inputChanged = true
        }

        onChanged.run()
    }

    override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        if(slot == INPUT_SLOT) {
            return ItemStack.EMPTY // prevents automation from extracting input
        }

        return super.extractItem(slot, amount, simulate)
    }
}

/**
 * Server tick for a machine that uses a [ProcessingDevice] and applies a [DirectSimpleProcessingRecipe] or [CatalyzedSimpleProcessingRecipe].
 * */
@ServerOnly
class ProcessingRecipeLoop<R : Eln2ProcessingLoopRecipe>(val onChanged: Runnable) {
    companion object {
        private const val IS_WORKING = "hasRecipe"
        private const val TIME_PROGRESS = "timeProgress"

        fun<R : Eln2ProcessingLoopRecipe> create(blockEntity: BlockEntity) = ProcessingRecipeLoop<R> {
            blockEntity.setChanged()
        }
    }

    class Operation<R : Eln2ProcessingLoopRecipe>(val recipe: R) {
        var timeProgress = 0.0
    }

    var operation: Operation<R>? = null
    var savedProgress: Double? = null // Level not available in [load] for block entities, we do the trick the cell block entity does.

    /**
     * @param speed The [ProcessingDevice.processingSpeed], used for rendering.
     * @param progress The progress to sync to the GUI.
     * */
    data class Result(val speed: Double, val progress: Float)

    /**
     * Advances the processing, if the inventory is eligible for operation.
     * Calls [BlockEntity.setChanged] if the NBT needs to be serialized.
     * */
    fun tick(device: ProcessingDevice, inventoryHandler: ProcessingRecipeInventoryHandler<R>) : Result {
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

                onChanged.run()
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
                onChanged.run()
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
                        inventoryHandler.execute()
                        operation = null
                    }

                    onChanged.run()
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

/**
 * Represents one valid ingredient for a recipe.
 * The [value] is used to set how many of this specific item are required to substitute into the recipe.
 * */
data class Eln2WeightedItemIngredient(val ingredient: Ingredient, val value: Int) {
    fun toNetwork(buf: FriendlyByteBuf) {
        ingredient.toNetwork(buf)
        buf.writeInt(value)
    }

    companion object {
        fun fromJson(json: JsonObject) : Eln2WeightedItemIngredient {
            val ingredient = Ingredient.fromJson(json)
            val value = GsonHelper.getAsInt(json, "value")

            return Eln2WeightedItemIngredient(ingredient, value)
        }

        fun fromNetwork(buf: FriendlyByteBuf) : Eln2WeightedItemIngredient {
            val ingredient = Ingredient.fromNetwork(buf)
            val value = buf.readInt()

            return Eln2WeightedItemIngredient(ingredient, value)
        }
    }
}

/**
 * Represents all possible variants of an ingredient in a recipe. Example: allows you to substitute 1 coal for e.g. 8 coal fragments, or 1 coal coke dust, and so on.
 * I'm not sure that's how it should be done. If not, please correct (join the discord or open a pull request).
 *
 * @param options All possible allowed ingredients.
 * @param requiredValue The total value required. Basically, the value of an item stack is the number of items multiplied by [Eln2WeightedItemIngredient.value].
 * */
data class Eln2WeightedItemRecipeRequirement(val options: List<Eln2WeightedItemIngredient>, val requiredValue: Int) {
    fun toNetwork(buf: FriendlyByteBuf) {
        buf.writeCollection(options) { _, ingredient ->
            ingredient.toNetwork(buf)
        }

        buf.writeInt(requiredValue)
    }

    companion object {
        fun fromJson(json: JsonObject) : Eln2WeightedItemRecipeRequirement {
            val options = json.getAsJsonArray("options")
                .map { it as JsonObject }
                .map(Eln2WeightedItemIngredient::fromJson)

            val requiredValue = GsonHelper.getAsInt(json, "requiredValue")

            return Eln2WeightedItemRecipeRequirement(options, requiredValue)
        }

        fun fromNetwork(buf: FriendlyByteBuf) : Eln2WeightedItemRecipeRequirement {
            val options = buf.readList {
                Eln2WeightedItemIngredient.fromNetwork(buf)
            }

            val requiredValue = buf.readInt()

            return Eln2WeightedItemRecipeRequirement(options, requiredValue)
        }
    }
}

/**
 * Represents a recipe's multiple possible substituted inputs.
 * */
data class Eln2WeightedItemRecipeRequirements(val requirements: List<Eln2WeightedItemRecipeRequirement>) {
    fun toNetwork(buf: FriendlyByteBuf) {
        buf.writeCollection(requirements) { _, requirement ->
            requirement.toNetwork(buf)
        }
    }

    companion object {
        fun fromJson(json: JsonElement) : Eln2WeightedItemRecipeRequirements {
            val requirements = json.asJsonArray
                .map { it as JsonObject }
                .map { Eln2WeightedItemRecipeRequirement.fromJson(it) }

            return Eln2WeightedItemRecipeRequirements(requirements)
        }

        fun fromNetwork(buf: FriendlyByteBuf) : Eln2WeightedItemRecipeRequirements {
            val requirements = buf.readList {
                Eln2WeightedItemRecipeRequirement.fromNetwork(buf)
            }

            return Eln2WeightedItemRecipeRequirements(requirements)
        }
    }
}

/**
 * Checks if the [recipe] is matched by item stacks, and also mutates the item stacks along the way.
 * @return True if the container satisfies the recipe. Otherwise, false (but the container is still mutated!).
 * */
fun List<ItemStack>.applyRecipeWeighted(recipe: Eln2WeightedItemRecipeRequirements) : Boolean {
    /**
     * The algorithm is very inefficient, but we assume the inventories are very small, and it's also not called in a hot path.
     * */
    for (requirement in recipe.requirements) {
        /**
         * The remaining value to satisfy:
         * */
        var valueRequirementRemaining = requirement.requiredValue

        /**
         * Tries to match each option to the inventory:
         * */
        for (option in requirement.options) {
            for (currentStack in this) {

                if(currentStack.isEmpty) {
                    continue
                }

                if(!option.ingredient.test(currentStack)) {
                    continue
                }

                /**
                 * Calculates how many items of this type are needed to satisfy the remaining value.
                 * */
                val itemsNeeded = (valueRequirementRemaining + option.value - 1) / option.value
                val itemsToTake = min(itemsNeeded, currentStack.count)

                val valueContributed = itemsToTake * option.value
                valueRequirementRemaining -= valueContributed

                /**
                 * Removes the items from the inventory:
                 * */
                currentStack.shrink(itemsToTake)

                if(valueRequirementRemaining <= 0) {
                    break
                }
            }

            if(valueRequirementRemaining <= 0) {
                break
            }
        }

        /**
         * Didn't match this specific requirement:
         * */
        if (valueRequirementRemaining > 0) {
            return false
        }
    }

    return true
}

/**
 * Tries to insert the [items] into this handler, in the slots in [slotRange]. **Mutates the handler!**
 * @return True if all items were inserted. Otherwise, false (still mutates the handler!).
 * */
fun ItemStackHandler.insertRange(items: List<ItemStack>, slotRange: IntRange) : Boolean {
    for (sourceStack in items) {
        var remainingStack = sourceStack.copy()

        for (slot in slotRange) {
            remainingStack = this.insertItem(slot, remainingStack, false)

            if(remainingStack.isEmpty) {
                break
            }
        }

        if(!remainingStack.isEmpty) {
            return false
        }
    }

    return true
}
