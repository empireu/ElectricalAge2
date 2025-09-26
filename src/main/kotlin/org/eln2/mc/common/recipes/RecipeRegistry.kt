package org.eln2.mc.common.recipes

import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import org.eln2.mc.MODID
import org.eln2.mc.common.recipes.foundation.SimpleProcessingRecipe
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

    fun registerProcessingRecipe(id: String) : RecipeType<SimpleProcessingRecipe> {
        val location = resource(id)
        val recipeType = RecipeType.simple<SimpleProcessingRecipe>(location)

        RECIPE_TYPES.register(id) { recipeType }
        RECIPE_SERIALIZERS.register(id) { SimpleProcessingRecipe.Serializer(recipeType) }

        return recipeType
    }
}

