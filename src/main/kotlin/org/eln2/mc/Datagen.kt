package org.eln2.mc

import net.minecraft.core.HolderLookup
import net.minecraft.data.PackOutput
import net.minecraft.data.loot.BlockLootSubProvider
import net.minecraft.data.recipes.FinishedRecipe
import net.minecraft.data.recipes.RecipeCategory
import net.minecraft.data.recipes.RecipeProvider
import net.minecraft.data.recipes.ShapelessRecipeBuilder
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.crafting.Ingredient
import net.minecraftforge.client.model.generators.BlockStateProvider
import net.minecraftforge.client.model.generators.ItemModelProvider
import net.minecraftforge.client.model.generators.loaders.DynamicFluidContainerModelBuilder
import net.minecraftforge.common.data.BlockTagsProvider
import net.minecraftforge.common.data.ExistingFileHelper
import net.minecraftforge.registries.ForgeRegistries
import org.eln2.mc.common.content.modules.ContentManager
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.content.modules.Eln2Ingredients
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.content.modules.world.Eln2Ores
import org.eln2.mc.common.content.processing.BlacksmithingRecipeBuilder
import org.eln2.mc.common.fluids.ForgeFluidRegistry
import org.eln2.mc.common.recipes.foundation.DirectSimpleProcessingRecipeBuilder
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Registers blocks dropping their block item when broken, from [ContentManager.SELF_DROP_BLOCKS_FOR_DATAGEN].
 * */
class Eln2BlockSelfDropLootDatagen : BlockLootSubProvider(emptySet(), FeatureFlags.REGISTRY.allFlags()) {
    override fun generate() {
        ContentManager.SELF_DROP_BLOCKS_FOR_DATAGEN.forEach { blockSupplier ->
            val block = blockSupplier.get()

            this.dropSelf(block)

            LOG.info(
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

            LOG.info(
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

            LOG.info(
                "Registered block tag {} for {}",
                tagKey,
                block
            )
        }
    }
}

/**
 * Generates the block model from [Eln2Ores.ORE_FOR_MODEL_DATAGEN], which is built by [Eln2Ores.withModelDatagen] which registers tints on indices we use in the models.
 * */
class Eln2BlockStateProviderDatagen(output: PackOutput, existingFileHelper: ExistingFileHelper) : BlockStateProvider(output, MODID, existingFileHelper) {
    override fun registerStatesAndModels() {
        Eln2Ores.ORE_FOR_MODEL_DATAGEN.forEach { obj ->
            val oreBlock = obj.oreBlock.get()

            val blockId = ForgeRegistries.BLOCKS.getKey(oreBlock)!!

            /**
             * Creates a model using two overlay textures:
             * - Stone base
             * - Overlay texture we prepared (see the markdown file), with tint index `1` (which we register into)
             * */
            val model = models().getBuilder(blockId.path)
                .renderType("minecraft:cutout")
                .parent(models().getExistingFile(mcLoc("block/block")))
                .texture("particle", mcLoc("block/stone"))
                .element()
                    .from(0f, 0f, 0f)
                    .to(16f, 16f, 16f)
                    .allFaces { face, builder ->
                        builder
                            .texture("#base")
                            .cullface(face)
                    }
                    .end()
                .element()
                    .from(0f, 0f, 0f)
                    .to(16f, 16f, 16f)
                    .allFaces { face, builder ->
                        builder
                            .texture("#overlay")
                            .cullface(face)
                            .tintindex(1)
                    }
                    .end()
                .texture("base", mcLoc("block/stone"))
                .texture("overlay", modLoc("block/ore_overlay")) // You need to make this PNG!

            simpleBlock(oreBlock, model)
        }
    }
}

/**
 * - Registers raw ore smelting, ore item smelting, raw ore blasting, from [Eln2Ores.ORE_SMELTING_FOR_DATAGEN].
 * - Registers assembly and disassembly recipes, from [Eln2Processing.PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN].
 * - Registers ingot heating, from [Eln2Ingredients.HOT_ITEMS_FOR_MODEL_AND_RECIPE_DATAGEN].
 * - Registers plate hammering and rolling, from [Eln2Ingredients.PLATES_FOR_RECIPE_DATAGEN].
 * */
class Eln2RecipeProviderDatagen(output: PackOutput) : RecipeProvider(output) {
    override fun buildRecipes(pWriter: Consumer<FinishedRecipe?>) {
        Eln2Ores.ORE_SMELTING_FOR_DATAGEN.forEach { (obj, resultSupplier) ->
            val rawOreItem = obj.rawOreItem.get()
            val blockItem = obj.oreBlockItem.get()
            val result = resultSupplier.get()

            val rawId = ForgeRegistries.ITEMS.getKey(rawOreItem)!!
            val blockItemId = ForgeRegistries.ITEMS.getKey(blockItem)!!
            val resultId = ForgeRegistries.ITEMS.getKey(result)!!
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

            LOG.info(
                "Registered smelting recipes for {}, {} -> {}",
                rawId,
                blockItemId,
                resultId
            )
        }

        Eln2Processing.PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN.forEach { obj ->
            val blockItem = obj.blockAndItem.item.get()
            val hull = obj.hullItem.get()
            val boxItem = obj.box.item.get()

            val blockItemId = ForgeRegistries.ITEMS.getKey(blockItem)!!

            /**
             * Assembly recipe:
             * */
            ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, blockItem)
                .requires(hull)
                .requires(boxItem)
                .unlockedBy("has_hull", has(hull))
                .unlockedBy("has_work_box", has(boxItem))
                .save(pWriter, resource("crafting/processing_machine/${blockItemId.path}_assembly"))

            /**
             * Disassembly recipe. The hull is left behind thanks to the registered craft remainder:
             * */
            ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, hull)
                .requires(blockItem)
                .unlockedBy("has_machine", has(blockItem))
                .save(pWriter, resource("crafting/processing_machine/${blockItemId.path}_disassembly"))

            LOG.info("Registered assembly and disassembly recipes for: {}", blockItemId)
        }

        Eln2Ingredients.HOT_ITEMS_FOR_MODEL_AND_RECIPE_DATAGEN.forEach { obj ->
            val sourceItem = obj.sourceItem?.get() ?: return@forEach
            val sourceItemId = ForgeRegistries.ITEMS.getKey(sourceItem)!!

            val hotItem = obj.registeredTransformedItem.get()
            val hotItemId = ForgeRegistries.ITEMS.getKey(hotItem)!!

            /**
             * Smelts into hot ingots:
             * */
            SimpleCookingRecipeBuilder.smelting(
                Ingredient.of(sourceItem),
                RecipeCategory.MISC,
                hotItem,
                0.5f,
                200
            ).apply {
                unlockedBy("has_item", has(sourceItem))
                save(pWriter, resource("smelting/${sourceItemId.path}_to_${hotItemId.path}_smelting"))
            }

            /**
             * Blasts into hot ingots:
             * */
            SimpleCookingRecipeBuilder.blasting(
                Ingredient.of(sourceItem),
                RecipeCategory.MISC,
                hotItem,
                0.5f,
                100
            ).apply {
                unlockedBy("has_item", has(sourceItem))
                save(pWriter, resource("blasting/${sourceItemId.path}_to_${hotItemId.path}_blasting"))
            }

            LOG.info("Added smelting and blasting to heat {} into {}", sourceItemId, hotItemId)
        }

        Eln2Ingredients.PLATES_FOR_RECIPE_DATAGEN.forEach { obj ->
            val sourceItem = obj.item.sourceItem?.get() ?: return@forEach
            val sourceItemId = ForgeRegistries.ITEMS.getKey(sourceItem)!!

            val plateItem = obj.item.registeredTransformedItem.get()
            val plateItemId = ForgeRegistries.ITEMS.getKey(plateItem)!!

            if(obj.hasRollingRecipe) {
                /**
                 * Rolling machine into the plate:
                 * */
                DirectSimpleProcessingRecipeBuilder(Eln2Processing.ROLLING_RECIPE)
                    .withInput(sourceItem)
                    .withOutput(plateItem)
                    .withDuration(obj.rollingDuration)
                    .save(pWriter, resource("rolling/${sourceItemId.path}_to_${plateItemId.path}"))

                LOG.info("Added plate rolling recipe from {} to {}", sourceItemId, plateItemId)
            }

            if(obj.hasBlacksmithingRecipe) {
                /**
                 * Hammering into the plate:
                 * */
                BlacksmithingRecipeBuilder(Ingredient.of(sourceItem), plateItem, 1)
                    .setTool(Eln2Processing.BLACKSMITHING_HAMMER_ITEM.get())
                    .setMode(Eln2Processing.BLACKSMITHING_HAMMER_FLATTENING)
                    .save(pWriter, resource("blacksmithing/${sourceItemId.path}_to_${plateItemId.path}"))

                LOG.info("Added plate blacksmithing recipe from {} to {}", sourceItemId, plateItemId)
            }
        }
    }
}

/**
 * - Generates the raw ore item model and the block item model from [Eln2Ores.ORE_FOR_MODEL_DATAGEN], which is built by [Eln2Ores.withModelDatagen] which registers tints on indices we use in the models.
 * - Generates bucket item models, from [ForgeFluidRegistry.FORGE_FLUID_BUCKETS].
 * - Generates chemical bottle models, from [Eln2ForgeFluids.CHEMICAL_BOTTLES_FOR_RESOLVE_AND_DATAGEN].
 * - Generates item models for the machine hulls and final machines, from [Eln2Processing.PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN].
 * - Generates item models for hot items, from [Eln2Ingredients.HOT_ITEMS_FOR_MODEL_AND_RECIPE_DATAGEN].
 * - Generates plate models, from [Eln2Ingredients.PLATES_FOR_MODEL_DATAGEN].
 * */
class Eln2ItemModelProviderDatagen(output: PackOutput, existingFileHelper: ExistingFileHelper) : ItemModelProvider(output, MODID, existingFileHelper) {
    override fun registerModels() {
        Eln2Ores.ORE_FOR_MODEL_DATAGEN.forEach { obj ->
            val rawOreItem = obj.rawOreItem.get()
            val oreBlock = obj.oreBlock.get()

            val rawId = ForgeRegistries.ITEMS.getKey(rawOreItem)!!
            val blockId = ForgeRegistries.BLOCKS.getKey(oreBlock)!!

            /**
             * For the block item, we parent the block's model:
             * */
            withExistingParent(
                blockId.toString(),
                modLoc("block/${blockId.path}")
            )

            /**
             * For the raw ore, we create a model with 2 layers, like the block model:
             * */
            singleTexture(
                rawId.path,
                mcLoc("item/generated"),
                "layer0",
                modLoc("item/raw_ore_overlay")
            )

            LOG.info("Registered ore item models for {}", blockId)
        }

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

            LOG.info("Registered bucket model for {}", id)
        }

        Eln2ForgeFluids.CHEMICAL_BOTTLES_FOR_RESOLVE_AND_DATAGEN.forEach { (eln2Fluid, registeredBottle) ->
            val bottle = registeredBottle.bottleItem.get()
            val bottleId = ForgeRegistries.ITEMS.getKey(bottle)!!

            getBuilder(bottleId.path)
                .parent(getExistingFile(mcLoc("item/generated")))
                .texture("layer0", mcLoc("item/glass_bottle"))
                .texture("layer1", modLoc("item/bottle_fluid_overlay"))

            LOG.info("Registered bottle item {} for ELN2 fluid {}", bottleId, eln2Fluid.id)
        }

        Eln2Processing.PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN.forEach { obj ->
            val hullItem = obj.hullItem.get()
            val machineBlockItem = obj.blockAndItem.item.get()

            val hullId = ForgeRegistries.ITEMS.getKey(hullItem)!!
            val blockItemId = ForgeRegistries.ITEMS.getKey(machineBlockItem)!!

            val model = obj.modelSupplier.get()
            val bodyLocation = model.body.modelLocation()

            withExistingParent(hullId.path, bodyLocation)

            /**
             * Registers the finished machine's model as simply the hull model.
             * We might be able to refine this in the future.
             * It might be possible to take the box's item model (which would be a generated model, from a sprite) and layer it on top of the model used here.
             * */
            withExistingParent(blockItemId.path, bodyLocation)

            LOG.info("Registered processing machine models for {} and {}", hullId, blockItemId)
        }

        /**
         * Generates item models that simply tint a base texture.
         * */
        fun fromTemplate(source: Iterable<Supplier<Item>>, baseTexture: String, name: String) {
            source.forEach { obj ->
                val item = obj.get()
                val itemId = ForgeRegistries.ITEMS.getKey(item)!!

                getBuilder(itemId.path)
                    .parent(getExistingFile(mcLoc("item/generated")))
                    .texture("layer0", modLoc("item/$baseTexture"))

                LOG.info("Registered {} model for {}", name, itemId)
            }
        }

        fromTemplate(Eln2Ingredients.INGOTS_FOR_TINT_AND_DATAGEN, "ingot_base", "ingot")
        fromTemplate(Eln2Ingredients.HOT_ITEMS_FOR_MODEL_AND_RECIPE_DATAGEN, "hot_ingot_base", "hot")
        fromTemplate(Eln2Ingredients.PLATES_FOR_MODEL_DATAGEN, "plate_base", "plate")
    }
}
