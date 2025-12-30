package org.eln2.mc

import net.minecraft.core.HolderLookup
import net.minecraft.data.PackOutput
import net.minecraft.data.loot.BlockLootSubProvider
import net.minecraft.data.recipes.FinishedRecipe
import net.minecraft.data.recipes.RecipeCategory
import net.minecraft.data.recipes.RecipeProvider
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.crafting.Ingredient
import net.minecraftforge.client.model.generators.ItemModelProvider
import net.minecraftforge.client.model.generators.loaders.DynamicFluidContainerModelBuilder
import net.minecraftforge.common.data.BlockTagsProvider
import net.minecraftforge.common.data.ExistingFileHelper
import net.minecraftforge.registries.ForgeRegistries
import org.eln2.mc.common.content.modules.ContentManager
import org.eln2.mc.common.content.modules.world.Eln2Ores
import org.eln2.mc.common.fluids.ForgeFluidRegistry
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Registers blocks dropping their block item when broken, from [ContentManager.SELF_DROP_BLOCKS_FOR_DATAGEN].
 * */
class Eln2BlockSelfDropLootDatagen : BlockLootSubProvider(emptySet(), FeatureFlags.REGISTRY.allFlags()) {
    override fun generate() {
        ContentManager.SELF_DROP_BLOCKS_FOR_DATAGEN.forEach { blockSupplier ->
            val block = blockSupplier.get()

            this.dropSelf(block)

            LOG.debug(
                "Registered self drop for {} ({})",
                block,
                ForgeRegistries.BLOCKS.getKey(block)
            )
        }
    }

    override fun getKnownBlocks() = ContentManager.SELF_DROP_BLOCKS_FOR_DATAGEN.map {
        it.get()
    }
}

/**
 * Registers ore blocks dropping raw ore with default enchant configs, from [Eln2Ores.ORE_DROP_BLOCKS_FOR_DATAGEN].
 * */
class Eln2BlockOreDropLootDatagen : BlockLootSubProvider(emptySet(), FeatureFlags.REGISTRY.allFlags()) {
    override fun generate() {
        Eln2Ores.ORE_DROP_BLOCKS_FOR_DATAGEN.forEach { obj ->
            val raw = obj.rawOreItem.get()
            val block = obj.oreBlock.get()

            this.add(block) {
                createOreDrop(block, raw)
            }

            LOG.debug(
                "Registered ore drop for {} ({})",
                block,
                ForgeRegistries.BLOCKS.getKey(block)
            )
        }
    }

    override fun getKnownBlocks() = Eln2Ores.ORE_DROP_BLOCKS_FOR_DATAGEN.map {
        it.oreBlock.get()
    }
}

/**
 * Registers tags for blocks, from [ContentManager.BLOCK_TAGS_FOR_DATAGEN].
 * */
class Eln2BlockTagsDatagen(output: PackOutput, lookupProvider: CompletableFuture<HolderLookup.Provider>, existingFileHelper: ExistingFileHelper) : BlockTagsProvider(output, lookupProvider, MODID, existingFileHelper) {
    override fun addTags(provider: HolderLookup.Provider) {
        ContentManager.BLOCK_TAGS_FOR_DATAGEN.forEach { (blockSupplier, tagKey) ->
            val block = blockSupplier.get()

            tag(tagKey).add(block)

            LOG.debug(
                "Registered block tag {} for {}",
                tagKey,
                block
            )
        }
    }
}

/**
 * Registers raw ore smelting, ore item smelting, raw ore blasting, from [Eln2Ores.ORE_SMELTING_FOR_DATAGEN].
 * */
class Eln2OreSmeltingDatagen(output: PackOutput) : RecipeProvider(output) {
    override fun buildRecipes(pWriter: Consumer<FinishedRecipe?>) {
        Eln2Ores.ORE_SMELTING_FOR_DATAGEN.forEach { (obj, resultSupplier) ->
            val rawOreItem = obj.rawOreItem.get()
            val blockItem = obj.oreBlockItem.get()
            val result = resultSupplier.get()

            val rawId = ForgeRegistries.ITEMS.getKey(rawOreItem)
                ?: error("Could not resolve ore smelting raw ore $rawOreItem")

            val blockItemId = ForgeRegistries.ITEMS.getKey(blockItem)
                ?: error("Could not resolve ore smelting block item $blockItem")

            val resultId = ForgeRegistries.ITEMS.getKey(result)
                ?: error("Could not resolve ore smelting result $result")

            /**
             * Smelts the raw ore item.
             * */
            SimpleCookingRecipeBuilder.smelting(
                Ingredient.of(rawOreItem),
                RecipeCategory.MISC,
                result,
                0.7f,
                200
            ).apply {
                unlockedBy("has_${resultId.path}", has(rawOreItem))
                save(pWriter, resource("smelting/${rawId.path}_to_${resultId.path}_smelting"))
            }

            /**
             * Smelts the block item.
             * */
            SimpleCookingRecipeBuilder.smelting(
                Ingredient.of(blockItem),
                RecipeCategory.MISC,
                result,
                0.7f,
                200
            ).apply {
                unlockedBy("has_${blockItemId.path}", has(blockItem))
                save(pWriter, resource("smelting/${blockItemId.path}_to_${resultId.path}_smelting"))
            }

            /**
             * Blasts the raw ore item.
             * */
            SimpleCookingRecipeBuilder.blasting(
                Ingredient.of(rawOreItem),
                RecipeCategory.MISC,
                result,
                0.7f,
                100
            ).apply {
                unlockedBy("has_${resultId.path}", has(rawOreItem))
                save(pWriter, resource("blasting/${rawId.path}_to_${resultId.path}_blasting"))
            }

            LOG.debug(
                "Registered smelting recipes for {}, {} -> {}",
                rawId,
                blockItemId,
                resultId
            )
        }
    }
}

/**
 * Generates bucket item models, from [ForgeFluidRegistry.FORGE_FLUID_BUCKETS].
 * */
class Eln2BucketModelsDatagen(output: PackOutput, existingFileHelper: ExistingFileHelper) : ItemModelProvider(output, MODID, existingFileHelper) {
    override fun registerModels() {
        ForgeFluidRegistry.FORGE_FLUID_BUCKETS.entries.forEach { bucketEntry ->
            val item = bucketEntry.get() as BucketItem
            val id = bucketEntry.id

            withExistingParent(id.path, ResourceLocation.parse("forge:item/bucket"))
                .customLoader { parent, helper ->
                    DynamicFluidContainerModelBuilder.begin(parent, helper)
                }
                .fluid(item.fluid)
                .coverIsMask(false)
                .end()

            LOG.debug("Registered bucket model for {}", id)
        }
    }
}
