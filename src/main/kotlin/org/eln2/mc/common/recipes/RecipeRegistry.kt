package org.eln2.mc.common.recipes

import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.MapPairBiMap
import org.ageseries.libage.data.MutableMapPairBiMap
import org.eln2.mc.MODID
import org.eln2.mc.common.recipes.foundation.CatalyzedSimpleProcessingRecipe
import org.eln2.mc.common.recipes.foundation.DirectSimpleProcessingRecipe
import org.eln2.mc.resource

object RecipeRegistry {
    val RECIPE_TYPES: DeferredRegister<RecipeType<*>> =
        DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, MODID)

    val RECIPE_SERIALIZERS: DeferredRegister<RecipeSerializer<*>> =
        DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, MODID)

    fun setup(bus: IEventBus) {
        RECIPE_TYPES.register(bus)
        RECIPE_SERIALIZERS.register(bus)
    }

    val REGISTERED_RECIPE_SERIALIZERS = MutableMapPairBiMap<RecipeType<*>, RegistryObject<RecipeSerializer<*>>>()

    fun getRecipeSerializer(recipeType: RecipeType<*>) = REGISTERED_RECIPE_SERIALIZERS.forward[recipeType]

    inline fun<reified R> register(id: String, crossinline serializer: (RecipeType<R>) -> RecipeSerializer<R>) : RecipeType<R> where R : Recipe<SimpleContainer> {
        val location = resource(id)
        val recipeType = RecipeType.simple<R>(location)

        RECIPE_TYPES.register(id) { recipeType }
        val serializerObj = RECIPE_SERIALIZERS.register(id) { serializer(recipeType) }

        @Suppress("UNCHECKED_CAST")
        REGISTERED_RECIPE_SERIALIZERS.add(recipeType, serializerObj as RegistryObject<RecipeSerializer<*>>)

        return recipeType
    }

    fun registerDirectRecipe(id: String) : RecipeType<DirectSimpleProcessingRecipe> = register<DirectSimpleProcessingRecipe>(id) {
        DirectSimpleProcessingRecipe.Serializer(it)
    }

    fun registerCatalyzedRecipe(id: String) : RecipeType<CatalyzedSimpleProcessingRecipe> = register<CatalyzedSimpleProcessingRecipe>(id) {
        CatalyzedSimpleProcessingRecipe.Serializer(it)
    }
}

