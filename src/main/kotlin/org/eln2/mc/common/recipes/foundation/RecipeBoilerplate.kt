package org.eln2.mc.common.recipes.foundation

import net.minecraft.core.RegistryAccess
import net.minecraft.data.recipes.FinishedRecipe
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType

/**
 * Derives the advancement ID automatically.
 * */
interface Eln2FinishedRecipe : FinishedRecipe {
    override fun getAdvancementId(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(this.id.namespace, "recipes/" + this.id.path)
}

// Standard inventory slots for input and output item in processing.
// Applies to [SimpleProcessingRecipe], [SimpleCatalyzedProcessingRecipe]
const val INPUT_SLOT = 0
const val OUTPUT_SLOT = 1
const val CATALYST_SLOT = 2

/**
 * Standard recipe serializer form. Meant to be implemented as a subclass in the custom recipe class.
 * The Recipe ([R]) must implement [Eln2CustomRecipe].
 * */
interface Eln2RecipeSerializer<R> : RecipeSerializer<R> where R : Recipe<SimpleContainer>, R : Eln2CustomRecipe<R> {
    val recipeType: RecipeType<R>
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
