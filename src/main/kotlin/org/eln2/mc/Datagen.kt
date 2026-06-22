package org.eln2.mc

import net.minecraft.client.renderer.block.model.BlockModel.GuiLight
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.data.PackOutput
import net.minecraft.data.loot.BlockLootSubProvider
import net.minecraft.data.recipes.*
import net.minecraft.data.tags.TagsProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.item.*
import net.minecraft.world.item.crafting.Ingredient
import net.minecraftforge.client.model.generators.BlockStateProvider
import net.minecraftforge.client.model.generators.ItemModelBuilder
import net.minecraftforge.client.model.generators.ItemModelProvider
import net.minecraftforge.client.model.generators.loaders.DynamicFluidContainerModelBuilder
import net.minecraftforge.client.model.generators.loaders.ItemLayerModelBuilder
import net.minecraftforge.common.data.BlockTagsProvider
import net.minecraftforge.common.data.ExistingFileHelper
import net.minecraftforge.registries.ForgeRegistries
import org.eln2.mc.common.content.modules.*
import org.eln2.mc.common.content.modules.Eln2ForgeFluids.requireBottle
import org.eln2.mc.common.content.modules.world.Eln2Ores
import org.eln2.mc.common.content.processing.AlloyingRecipeBuilder
import org.eln2.mc.common.fluids.ForgeFluidRegistry
import org.eln2.mc.common.recipes.foundation.CatalyzedSimpleProcessingRecipeBuilder
import org.eln2.mc.common.recipes.foundation.DirectSimpleProcessingRecipeBuilder
import org.eln2.mc.common.recipes.foundation.Eln2WeightedItemIngredient
import org.eln2.mc.extensions.blockID
import org.eln2.mc.extensions.itemID
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

            LOG.info("Registered self drop for {} ({})", block, block.blockID)
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

            LOG.info("Registered ore drop for {} ({})", block, block.blockID)
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
 * Generates item tag JSON files from [ContentManager.ITEM_TAGS_FOR_DATAGEN].
 * Tags our items with forge convention tags (e.g. `forge:ingots/tin`) so other mods' recipes that accept those tags can consume our items, and our recipes can accept their items.
 * */
class Eln2ItemTagsDatagen(output: PackOutput, lookupProvider: CompletableFuture<HolderLookup.Provider>, existingFileHelper: ExistingFileHelper) : TagsProvider<Item>(output, Registries.ITEM, lookupProvider, MODID, existingFileHelper) {
    override fun addTags(provider: HolderLookup.Provider) {
        ContentManager.ITEM_TAGS_FOR_DATAGEN.forEach { (itemSupplier, tagKey) ->
            val item = itemSupplier.get()
            val itemKey = ForgeRegistries.ITEMS.getResourceKey(item).orElseThrow()

            tag(tagKey).add(itemKey)

            LOG.info("Registered item tag {} for {} ({})", tagKey, item, item.itemID)
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

            /**
             * Creates a model using two overlay textures:
             * - Stone base
             * - Overlay texture we prepared (see the markdown file), with tint index `1` (which we register into)
             * */
            val model = models().getBuilder(oreBlock.blockID.path)
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
                .texture("overlay", modLoc("block/ore_overlay"))

            simpleBlock(oreBlock, model)
        }
    }
}

/**
 * - Registers raw ore smelting, ore item smelting, raw ore blasting, from [Eln2Ores.ORE_SMELTING_FOR_DATAGEN].
 * - Registers assembly and disassembly recipes, from [Eln2Processing.PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN].
 * - Registers recipes from all repositories in [Eln2Ingredients].
 * - Builds manual recipes.
 * */
class Eln2RecipeProviderDatagen(output: PackOutput) : RecipeProvider(output) {
    /**
     * Returns an [Ingredient] that prefers the forge convention tag for [item] if one exists, falling back to a concrete item ingredient. This lets our recipes accept items from other mods that are registered to the same forge convention tag.
     */
    private fun taggedIngredient(item: Item): Ingredient {
        val tag = ContentManager.tagForItem(item)

        return if (tag != null) {
            Ingredient.of(tag)
        }
        else {
            Ingredient.of(item)
        }
    }

    private fun buildManualRecipes(pWriter: Consumer<FinishedRecipe?>) {
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, Eln2ForgeFluids.INSULATING_VARNISH.requireBottle().bottleItem.get())
            .requires(Eln2ForgeFluids.NAPHTHA.requireBottle().bottleItem.get())
            .requires(Eln2Ingredients.RAW_RESIN.get())
            .requires(Items::GLASS_BOTTLE)
            .unlockedBy("has_raw_resin", has(Eln2Ingredients.RAW_RESIN.get()))
            .save(pWriter, resource("crafting/insulating_varnish_bottle_from_resin"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.ENAMELED_COPPER_WIRE.get(), 8)
            .pattern("WWW")
            .pattern("WBW")
            .pattern("WWW")
            .define('W', Eln2Ingredients.COPPER_WIRE.get())
            .define('B', Eln2ForgeFluids.INSULATING_VARNISH.requireBottle().bottleItem.get())
            .unlockedBy("has_insulating_varnish_bottle", has(Eln2ForgeFluids.INSULATING_VARNISH.requireBottle().bottleItem.get()))
            .save(pWriter, resource("crafting/enameled_copper_wire"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.CARBON_PUTTY.get(), 8)
            .pattern("CCC")
            .pattern("CBC")
            .pattern("CCC")
            .define('C', Eln2Ingredients.COKE_DUST.get())
            .define('B', Eln2ForgeFluids.PITCH.requireBottle().bottleItem.get())
            .unlockedBy("has_coke_dust", has(Eln2Ingredients.COKE_DUST.get()))
            .save(pWriter, resource("crafting/carbon_putty"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.RAW_CARBON_BRUSH.get())
            .pattern("CCW")
            .define('C', Eln2Ingredients.CARBON_PUTTY.get())
            .define('W', Eln2Ingredients.COPPER_WIRE.get())
            .unlockedBy("has_putty", has(Eln2Ingredients.CARBON_PUTTY.get()))
            .save(pWriter, resource("crafting/raw_carbon_brush"))

        SimpleCookingRecipeBuilder.smelting(Ingredient.of(Eln2Ingredients.RAW_CARBON_BRUSH.get()), RecipeCategory.MISC, Eln2Ingredients.CARBON_BRUSH.get(), 0.5f, 200)
            .unlockedBy("has_raw_carbon_brush", has(Eln2Ingredients.RAW_CARBON_BRUSH.get()))
            .save(pWriter, resource("smelting/raw_carbon_brush_to_carbon_brush_smelting"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.IRON_AXLE_MOUNT.get(), 4)
            .pattern(" I ")
            .pattern("P P")
            .pattern(" I ")
            .define('I', Items::IRON_INGOT)
            .define('P', Eln2Ingredients.IRON_PLATE.get())
            .unlockedBy("has_iron_plate", has(Eln2Ingredients.IRON_PLATE.get()))
            .save(pWriter, resource("crafting/iron_axle_mount"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.IRON_GEAR.get(), 4)
            .pattern(" I ")
            .pattern("IPI")
            .pattern(" I ")
            .define('I', Items::IRON_INGOT)
            .define('P', Eln2Ingredients.IRON_PLATE.get())
            .unlockedBy("has_iron_plate", has(Eln2Ingredients.IRON_PLATE.get()))
            .save(pWriter, resource("crafting/iron_gear"))

        CatalyzedSimpleProcessingRecipeBuilder(Eln2Processing.EXTRUDING_RECIPE)
            .withInput(Eln2Ingredients.HOT_IRON_INGOT.get())
            .withCatalyst(Eln2Processing.EXTRUDER_SHAFT_DIE.get())
            .withOutput(Eln2Ingredients.IRON_SHAFT.get())
            .withDuration(20.0)
            .unlockedBy("has_shaft_die", has(Eln2Processing.EXTRUDER_SHAFT_DIE.get()))
            .save(pWriter, resource("extruding/iron_shaft"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Kinetic.PRIMITIVE_STANDARD_IRON_STRAIGHT_JOINT.partInfo.item.get())
            .pattern(" L ")
            .pattern("BSB")
            .pattern(" P ")
            .define('L', Eln2ForgeFluids.CREOSOTE.requireBottle().bottleItem.get())
            .define('B', Eln2Ingredients.IRON_AXLE_MOUNT.get())
            .define('S', Eln2Ingredients.IRON_SHAFT.get())
            .define('P', Eln2Ingredients.IRON_PLATE.get())
            .unlockedBy("has_mount", has(Eln2Ingredients.IRON_AXLE_MOUNT.get()))
            .save(pWriter, resource("crafting/primitive_standard_iron_straight_joint"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Kinetic.PRIMITIVE_STANDARD_IRON_HUB_JOINT.partInfo.item.get())
            .pattern(" M ")
            .pattern("RGR")
            .pattern(" M ")
            .define('M', Eln2Ingredients.IRON_AXLE_MOUNT.get())
            .define('R', Eln2Ingredients.IRON_SHAFT.get())
            .define('G', Eln2Ingredients.IRON_GEAR.get())
            .unlockedBy("has_gear", has(Eln2Ingredients.IRON_GEAR.get()))
            .save(pWriter, resource("crafting/primitive_standard_iron_hub_joint"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Processing.COKE_OVEN_BLOCK_ITEM.get())
            .pattern("PBP")
            .pattern("B B")
            .pattern("BFB")
            .define('P', Eln2Ingredients.IRON_PLATE.get())
            .define('B', Items.BRICKS)
            .define('F', Items.FURNACE)
            .unlockedBy("has_bricks", has(Items.BRICKS))
            .save(pWriter, resource("crafting/coke_oven"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2ForgeFluids.FLUID_PIPE_BLOCK.item.get(), 8)
            .pattern("PPP")
            .define('P', Eln2Ingredients.IRON_PLATE.get())
            .unlockedBy("has_iron_plate", has(Eln2Ingredients.IRON_PLATE.get()))
            .save(pWriter, resource("crafting/fluid_pipe"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2ForgeFluids.IRON_TANK.blockAndItem.item.get())
            .pattern("PPP")
            .pattern("P P")
            .pattern("PPP")
            .define('P', Eln2Ingredients.IRON_PLATE.get())
            .unlockedBy("has_iron_plate", has(Eln2Ingredients.IRON_PLATE.get()))
            .save(pWriter, resource("crafting/iron_tank"))

        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, Eln2Wires.STANDARD_INSULATED_COPPER_ELECTRICAL_WIRE.part.item.get())
            .requires(Eln2Ingredients.COPPER_WIRE.get())
            .requires(Eln2Ingredients.RUBBER.get())
            .unlockedBy("has_rubber", has(Eln2Ingredients.RUBBER.get()))
            .save(pWriter, resource("crafting/standard_insulated_copper_wire"))

        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, Eln2Wires.SIGNAL_WIRE.part.item.get(), 8)
            .requires(Eln2Ingredients.COPPER_WIRE.get())
            .requires(Eln2Ingredients.RUBBER.get())
            .requires(Items.REDSTONE)
            .unlockedBy("has_rubber", has(Eln2Ingredients.RUBBER.get()))
            .save(pWriter, resource("crafting/signal_wire"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Wires.STANDARD_UNINSULATED_COPPER_THERMAL_WIRE.part.item.get(), 4)
            .pattern("P")
            .pattern("P")
            .pattern("P")
            .define('P', Eln2Ingredients.COPPER_PLATE.get())
            .unlockedBy("has_copper_plate", has(Eln2Ingredients.COPPER_PLATE.get()))
            .save(pWriter, resource("crafting/standard_uninsulated_copper_thermal_wire"))

        //#region Tools

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Tools.WRENCH.get())
            .pattern("I I")
            .pattern(" I ")
            .pattern(" I ")
            .define('I', Items.IRON_INGOT)
            .unlockedBy("has_iron_ingot", has(Items.IRON_INGOT))
            .save(pWriter, resource("crafting/wrench"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Tools.SCREWDRIVER.get())
            .pattern(" I ")
            .pattern("III")
            .define('I', Items.IRON_INGOT)
            .unlockedBy("has_iron_ingot", has(Items.IRON_INGOT))
            .save(pWriter, resource("crafting/screwdriver"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Grid.GRID_CABLE_PLIERS.get())
            .pattern("I I")
            .pattern("I I")
            .pattern(" I ")
            .define('I', Items.IRON_INGOT)
            .unlockedBy("has_iron_ingot", has(Items.IRON_INGOT))
            .save(pWriter, resource("crafting/grid_cable_pliers"))

        //#endregion

        //#region Simple Plate Recipes

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.LEAD_PLATE.get())
            .pattern("I  ")
            .pattern("I  ")
            .pattern("   ")
            .define('I', taggedIngredient(Eln2Ingredients.LEAD_INGOT.get()))
            .unlockedBy("has_lead_ingot", has(Eln2ConventionTags.INGOT_LEAD))
            .save(pWriter, resource("crafting/lead_plate_from_ingots"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.TIN_PLATE.get())
            .pattern("I  ")
            .pattern("I  ")
            .pattern("   ")
            .define('I', taggedIngredient(Eln2Ingredients.TIN_INGOT.get()))
            .unlockedBy("has_tin_ingot", has(Eln2ConventionTags.INGOT_TIN))
            .save(pWriter, resource("crafting/tin_plate_from_ingots"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.IRON_PLATE.get())
            .pattern("I  ")
            .pattern("I  ")
            .pattern("   ")
            .define('I', taggedIngredient(Items.IRON_INGOT))
            .unlockedBy("has_iron_ingot", has(Eln2ConventionTags.INGOT_IRON))
            .save(pWriter, resource("crafting/iron_plate_from_ingots"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.COPPER_PLATE.get())
            .pattern("I  ")
            .pattern("I  ")
            .pattern("   ")
            .define('I', taggedIngredient(Items.COPPER_INGOT))
            .unlockedBy("has_copper_ingot", has(Eln2ConventionTags.INGOT_COPPER))
            .save(pWriter, resource("crafting/copper_plate_from_ingots"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.BRONZE_PLATE.get())
            .pattern("I  ")
            .pattern("I  ")
            .pattern("   ")
            .define('I', taggedIngredient(Eln2Ingredients.BRONZE_INGOT.get()))
            .unlockedBy("has_bronze_ingot", has(Eln2ConventionTags.INGOT_BRONZE))
            .save(pWriter, resource("crafting/bronze_plate_from_ingots"))

        //#endregion

        //#region Simple Component Recipes

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.IRON_SHAFT.get())
            .pattern("   ")
            .pattern("III")
            .pattern("   ")
            .define('I', taggedIngredient(Items.IRON_INGOT))
            .unlockedBy("has_iron_ingot", has(Eln2ConventionTags.INGOT_IRON))
            .save(pWriter, resource("crafting/iron_shaft_from_ingots"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.COPPER_ROD.get())
            .pattern("   ")
            .pattern("II ")
            .pattern("   ")
            .define('I', taggedIngredient(Items.COPPER_INGOT))
            .unlockedBy("has_copper_ingot", has(Eln2ConventionTags.INGOT_COPPER))
            .save(pWriter, resource("crafting/copper_rod_from_ingots"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.IRON_GEAR.get())
            .pattern(" N ")
            .pattern("NIN")
            .pattern(" N ")
            .define('I', taggedIngredient(Items.IRON_INGOT))
            .define('N', Items.IRON_NUGGET)
            .unlockedBy("has_iron_ingot", has(Eln2ConventionTags.INGOT_IRON))
            .save(pWriter, resource("crafting/iron_gear_simple"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Processing.PRIMITIVE_KINETIC_WORK_BOX.item.get())
            .pattern("P P")
            .pattern("S G")
            .pattern("   ")
            .define('P', taggedIngredient(Eln2Ingredients.IRON_PLATE.get()))
            .define('S', Eln2Ingredients.IRON_SHAFT.get())
            .define('G', Eln2Ingredients.IRON_GEAR.get())
            .unlockedBy("has_iron_plate", has(Eln2Ingredients.IRON_PLATE.get()))
            .save(pWriter, resource("crafting/primitive_kinetic_work_box"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.MACHINE_FRAME.get(), 4)
            .pattern(" P ")
            .pattern("P P")
            .pattern(" P ")
            .define('P', taggedIngredient(Eln2Ingredients.IRON_PLATE.get()))
            .unlockedBy("has_iron_plate", has(Eln2Ingredients.IRON_PLATE.get()))
            .save(pWriter, resource("crafting/machine_frame"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.CRUSHER_DRUM.get(), 2)
            .pattern("POP")
            .pattern("OSO")
            .pattern("POP")
            .define('P', taggedIngredient(Eln2Ingredients.IRON_PLATE.get()))
            .define('O', Items.OBSIDIAN)
            .define('S', Eln2Ingredients.IRON_SHAFT.get())
            .unlockedBy("has_obsidian", has(Items.OBSIDIAN))
            .save(pWriter, resource("crafting/crusher_drum"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.CRUSHER_ASSEMBLY.get())
            .pattern("GPG")
            .pattern("D D")
            .pattern("GPG")
            .define('G', Eln2Ingredients.IRON_GEAR.get())
            .define('P', taggedIngredient(Eln2Ingredients.IRON_PLATE.get()))
            .define('D', Eln2Ingredients.CRUSHER_DRUM.get())
            .unlockedBy("has_crusher_drum", has(Eln2Ingredients.CRUSHER_DRUM.get()))
            .save(pWriter, resource("crafting/crusher_assembly"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Processing.CRUSHER_HULL.hullItem.get())
            .pattern("MAG")
            .pattern("B  ")
            .pattern("   ")
            .define('M', Eln2Ingredients.MACHINE_FRAME.get())
            .define('A', Eln2Ingredients.CRUSHER_ASSEMBLY.get())
            .define('G', Eln2Ingredients.IRON_GEAR.get())
            .define('B', Ingredient.of(Eln2ConventionTags.GLUE))
            .unlockedBy("has_machine_frame", has(Eln2Ingredients.MACHINE_FRAME.get()))
            .save(pWriter, resource("crafting/crusher_hull"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.EXTRUDING_PORT.get(), 2)
            .pattern("GSG")
            .pattern("GSG")
            .pattern("   ")
            .define('G', Eln2Ingredients.IRON_GEAR.get())
            .define('S', Eln2Ingredients.IRON_SHAFT.get())
            .unlockedBy("has_iron_gear", has(Eln2Ingredients.IRON_GEAR.get()))
            .save(pWriter, resource("crafting/extruding_port"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.EXTRUDING_ASSEMBLY.get())
            .pattern("EPE")
            .define('E', Eln2Ingredients.EXTRUDING_PORT.get())
            .define('P', taggedIngredient(Eln2Ingredients.IRON_PLATE.get()))
            .unlockedBy("has_extruding_port", has(Eln2Ingredients.EXTRUDING_PORT.get()))
            .save(pWriter, resource("crafting/extruding_assembly"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Processing.EXTRUDER_HULL.hullItem.get())
            .pattern("MAB")
            .pattern("   ")
            .pattern("   ")
            .define('M', Eln2Ingredients.MACHINE_FRAME.get())
            .define('A', Eln2Ingredients.EXTRUDING_ASSEMBLY.get())
            .define('B', Ingredient.of(Eln2ConventionTags.GLUE))
            .unlockedBy("has_machine_frame", has(Eln2Ingredients.MACHINE_FRAME.get()))
            .save(pWriter, resource("crafting/extruder_hull"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Ingredients.ROLLING_ASSEMBLY.get())
            .pattern("E E")
            .define('E', Eln2Ingredients.EXTRUDING_PORT.get())
            .unlockedBy("has_extruding_port", has(Eln2Ingredients.EXTRUDING_PORT.get()))
            .save(pWriter, resource("crafting/rolling_assembly"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Processing.ROLLING_MACHINE_HULL.hullItem.get())
            .pattern("MAB")
            .pattern("   ")
            .pattern("   ")
            .define('M', Eln2Ingredients.MACHINE_FRAME.get())
            .define('A', Eln2Ingredients.ROLLING_ASSEMBLY.get())
            .define('B', Ingredient.of(Eln2ConventionTags.GLUE))
            .unlockedBy("has_machine_frame", has(Eln2Ingredients.MACHINE_FRAME.get()))
            .save(pWriter, resource("crafting/rolling_machine_hull"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2HeatGenerators.PRIMITIVE_BURNER_BLOCK.get())
            .pattern("SCS")
            .pattern("S S")
            .pattern("SCS")
            .define('S', Items.STONE)
            .define('C', Eln2Ingredients.COPPER_PLATE.get())
            .unlockedBy("has_copper_plate", has(Eln2Ingredients.COPPER_PLATE.get()))
            .save(pWriter, resource("crafting/primitive_burner"))

        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, Eln2Processing.EXTRUDER_WIRE_DIE.get())
            .requires(Items.STONE_SLAB)
            .requires(Items.IRON_NUGGET)
            .unlockedBy("has_stone_slab", has(Items.STONE_SLAB))
            .save(pWriter, resource("crafting/extruder_wire_die"))

        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, Eln2Processing.EXTRUDER_ROD_DIE.get())
            .requires(Eln2Processing.EXTRUDER_WIRE_DIE.get())
            .requires(Items.IRON_NUGGET)
            .unlockedBy("has_extruder_wire_die", has(Eln2Processing.EXTRUDER_WIRE_DIE.get()))
            .save(pWriter, resource("crafting/extruder_rod_die"))

        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, Eln2Processing.EXTRUDER_SHAFT_DIE.get())
            .requires(Eln2Processing.EXTRUDER_ROD_DIE.get())
            .requires(Items.IRON_NUGGET)
            .unlockedBy("has_extruder_rod_die", has(Eln2Processing.EXTRUDER_ROD_DIE.get()))
            .save(pWriter, resource("crafting/extruder_shaft_die"))

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, Eln2Processing.TREE_TAP_PART.item.get())
            .pattern("S S")
            .pattern("SPS")
            .pattern("SSS")
            .define('S', Ingredient.of(Eln2ConventionTags.WOODEN_SLABS))
            .define('P', taggedIngredient(Eln2Ingredients.IRON_PLATE.get()))
            .unlockedBy("has_iron_plate", has(Eln2Ingredients.IRON_PLATE.get()))
            .save(pWriter, resource("crafting/tree_tap"))

        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, Eln2Ingredients.RUBBER_COMPOUND.get(), 4)
            .requires(Eln2Ingredients.RAW_LATEX.get())
            .requires(Eln2Ingredients.SULFUR_DUST.get())
            .unlockedBy("has_sulfur_dust", has(Eln2Ingredients.SULFUR_DUST.get()))
            .save(pWriter, resource("crafting/rubber_compound"))

        LOG.info("Generated manual recipes.")
    }

    override fun buildRecipes(pWriter: Consumer<FinishedRecipe?>) {
        Eln2Ores.ORE_SMELTING_FOR_DATAGEN.forEach { (obj, resultSupplier) ->
            val rawOreItem = obj.rawOreItem.get()
            val blockItem = obj.oreBlockItem.get()
            val result = resultSupplier.get()

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
                unlockedBy("has_${result.itemID.path}", has(rawOreItem))
                save(pWriter, resource("smelting/${rawOreItem.itemID.path}_to_${result.itemID.path}_smelting"))
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
                unlockedBy("has_${blockItem.itemID.path}", has(blockItem))
                save(pWriter, resource("smelting/${blockItem.itemID.path}_to_${result.itemID.path}_smelting"))
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
                unlockedBy("has_${result.itemID.path}", has(rawOreItem))
                save(pWriter, resource("blasting/${rawOreItem.itemID.path}_to_${result.itemID.path}_blasting"))
            }

            LOG.info(
                "Registered smelting recipes for {}, {} -> {}",
                rawOreItem.itemID,
                blockItem.itemID,
                result.itemID
            )
        }

        Eln2Processing.PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN.forEach { obj ->
            val blockItem = obj.blockAndItem.item.get()
            val hull = obj.hullItem.get()
            val boxItem = obj.box.item.get()

            /**
             * Assembly recipe:
             * */
            ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, blockItem)
                .requires(hull)
                .requires(boxItem)
                .unlockedBy("has_hull", has(hull))
                .unlockedBy("has_work_box", has(boxItem))
                .save(pWriter, resource("crafting/processing_machine/${blockItem.itemID.path}_assembly"))

            /**
             * Disassembly recipe. The hull is left behind thanks to the registered craft remainder:
             * */
            ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, hull)
                .requires(blockItem)
                .unlockedBy("has_machine", has(blockItem))
                .save(pWriter, resource("crafting/processing_machine/${blockItem.itemID.path}_disassembly"))

            LOG.info("Registered assembly and disassembly recipes for: {}", blockItem.itemID)
        }

        sequentialForEach(Eln2Ingredients.HOT_INGOTS.itemsForRecipeDatagen, Eln2Ingredients.HOT_PLATES.itemsForRecipeDatagen) { obj ->
            val sourceItem = obj.sourceItemForVanillaHeating?.get() ?: return@sequentialForEach
            val hotItem = obj.info.get()

            /**
             * Smelts into hot ingots:
             * */
            SimpleCookingRecipeBuilder.smelting(
                taggedIngredient(sourceItem),
                RecipeCategory.MISC,
                hotItem,
                0.5f,
                200
            ).apply {
                unlockedBy("has_item", has(sourceItem))
                save(pWriter, resource("smelting/${sourceItem.itemID.path}_to_${hotItem.itemID.path}_smelting"))
            }

            /**
             * Blasts into hot ingots:
             * */
            SimpleCookingRecipeBuilder.blasting(
                taggedIngredient(sourceItem),
                RecipeCategory.MISC,
                hotItem,
                0.5f,
                100
            ).apply {
                unlockedBy("has_item", has(sourceItem))
                save(pWriter, resource("blasting/${sourceItem.itemID.path}_to_${hotItem.itemID.path}_blasting"))
            }

            LOG.info("Added smelting and blasting to heat {} into {}", sourceItem.itemID, hotItem.itemID)
        }

        Eln2Ingredients.PLATES.itemsForRecipeDatagen.forEach { obj ->
            val plateItem = obj.info.get()
            val rollingItem = obj.sourceItemForRolling?.get()

            if(rollingItem != null) {
                DirectSimpleProcessingRecipeBuilder(Eln2Processing.ROLLING_RECIPE)
                    .withInput(taggedIngredient(rollingItem))
                    .withOutput(plateItem)
                    .withDuration(obj.rollingDuration)
                    .unlockedBy("has_item_to_roll", has(rollingItem))
                    .save(pWriter, resource("rolling/${rollingItem.itemID.path}_to_${plateItem.itemID.path}"))

                LOG.info("Added plate rolling recipe from {} to {}", rollingItem.itemID, plateItem.itemID)
            }
        }

        Eln2Ingredients.WIRES.itemsForRecipeDatagen.forEach { obj ->
            val wireItem = obj.info.get()
            val itemForExtruding = obj.sourceItemForExtruding?.get()

            if(itemForExtruding != null) {
                CatalyzedSimpleProcessingRecipeBuilder(Eln2Processing.EXTRUDING_RECIPE)
                    .withInput(taggedIngredient(itemForExtruding))
                    .withCatalyst(Eln2Processing.EXTRUDER_WIRE_DIE.get())
                    .withOutput(wireItem)
                    .withDuration(obj.extrudingDuration)
                    .unlockedBy("has_item_for_wire_extrusion", has(itemForExtruding))
                    .save(pWriter, resource("extruding/${itemForExtruding.itemID.path}_to_${wireItem.itemID.path}"))

                LOG.info("Added wire extruding recipe from {} to {}", itemForExtruding.itemID, wireItem.itemID)
            }
        }

        Eln2Ingredients.DUSTS.itemsForRecipeDatagen.forEach { obj ->
            val dustItem = obj.info.get()

            obj.sourceItemsForCrushing.forEach { crushingInfo ->
                val crushingSource = crushingInfo.sourceItem.get()

                DirectSimpleProcessingRecipeBuilder(Eln2Processing.CRUSHING_RECIPE)
                    .withInput(taggedIngredient(crushingSource))
                    .withOutput(dustItem)
                    .withDuration(crushingInfo.duration)
                    .withTier(crushingInfo.tier)
                    .unlockedBy("has_item_to_crush", has(crushingSource))
                    .save(pWriter, resource("crushing/${crushingInfo.sourceItem.get().itemID.path}_to_${dustItem.itemID.path}"))

                LOG.info("Added dust crushing recipe from {} to {}", crushingInfo.sourceItem.get().itemID, dustItem.itemID)
            }
        }

        AlloyingRecipeBuilder(Eln2Processing.ALLOYING_RECIPE)
            .withInput(Eln2WeightedItemIngredient(Ingredient.of(Eln2ConventionTags.INGOT_COPPER), 3))
            .withInput(Eln2WeightedItemIngredient(Ingredient.of(Eln2ConventionTags.INGOT_TIN), 1))
            .withOutput(ItemStack(Eln2Ingredients.BRONZE_INGOT.get(), 4))
            .withDuration(600)
            .unlockedBy("has_copper", has(Eln2ConventionTags.INGOT_COPPER))
            .unlockedBy("has_tin", has(Eln2ConventionTags.INGOT_TIN))
            .save(pWriter, resource("alloying/copper_tin_to_bronze"))

        buildManualRecipes(pWriter)
    }
}

/**
 * - Generates the raw ore item model and the block item model from [Eln2Ores.ORE_FOR_MODEL_DATAGEN], which is built by [Eln2Ores.withModelDatagen] which registers tints on indices we use in the models.
 * - Generates bucket models, from [ForgeFluidRegistry.FORGE_FLUID_BUCKETS].
 * - Generates chemical bottle models, from [Eln2ForgeFluids.CHEMICAL_BOTTLES_FOR_RESOLVE_AND_DATAGEN].
 * - Generates models for the machine hulls and final machines, from [Eln2Processing.PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN].
 * - Generates models for all the repositories in [Eln2Ingredients].
 * */
class Eln2ItemModelProviderDatagen(output: PackOutput, existingFileHelper: ExistingFileHelper) : ItemModelProvider(output, MODID, existingFileHelper) {
    override fun registerModels() {
        Eln2Ores.ORE_FOR_MODEL_DATAGEN.forEach { obj ->
            val rawOreItem = obj.rawOreItem.get()
            val oreBlock = obj.oreBlock.get()

            /**
             * For the block item, we parent the block's model:
             * */
            withExistingParent(oreBlock.blockID.toString(), modLoc("block/${oreBlock.blockID.path}"))

            /**
             * For the raw ore, we create a model with 2 layers, like the block model:
             * */
            singleTexture(
                rawOreItem.itemID.path,
                mcLoc("item/generated"),
                "layer0",
                modLoc("item/raw_ore_overlay")
            )

            LOG.info("Registered ore item models for {}", oreBlock.blockID)
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

            getBuilder(bottle.itemID.path)
                .parent(getExistingFile(mcLoc("item/generated")))
                .texture("layer0", mcLoc("item/glass_bottle"))
                .texture("layer1", modLoc("item/bottle_fluid_overlay"))

            LOG.info("Registered bottle item {} for ELN2 fluid {}", bottle.itemID, eln2Fluid.id)
        }

        Eln2Processing.PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN.forEach { obj ->
            val hullItem = obj.hullItem.get()
            val machineBlockItem = obj.blockAndItem.item.get()

            val model = obj.modelSupplier.get()
            val bodyLocation = model.body.modelLocation()

            withExistingParent(hullItem.itemID.path, bodyLocation)

            /**
             * Registers the finished machine's model as simply the hull model.
             * We might be able to refine this in the future.
             * It might be possible to take the box's item model (which would be a generated model, from a sprite) and layer it on top of the model used here.
             * */
            withExistingParent(machineBlockItem.itemID.path, bodyLocation)

            LOG.info("Registered processing machine models for {} and {}", hullItem.itemID, machineBlockItem.itemID)
        }

        /**
         * Does some tricks so the item model glows, ignoring light, for hot items.
         * We need to write this data in the model file; parenting doesn't work.
         * That's the first thing I tried, and it has some undefined behavior which makes all children use the texture of the (I think) first child that is loaded with that parent.
         * */
        fun ItemModelBuilder.loadGlowingTemplate(texture: ResourceLocation): ItemModelBuilder {
            this.texture("layer0", texture)
            this.guiLight(GuiLight.FRONT)

            this.customLoader { a, b -> ItemLayerModelBuilder.begin(a, b) }
                .emissive(15, 15, 0)
                .end()

            /**
             * Source: `minecraft:item/generated`
             * */
            this.transforms()
                .transform(ItemDisplayContext.GROUND)
                    .rotation(0f, 0f, 0f)
                    .translation(0f, 2f, 0f)
                    .scale(0.5f, 0.5f, 0.5f)
                    .end()
                .transform(ItemDisplayContext.HEAD)
                    .rotation(0f, 180f, 0f)
                    .translation(0f, 13f, 7f)
                    .scale(1f, 1f, 1f)
                    .end()
                .transform(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
                    .rotation(0f, 0f, 0f)
                    .translation(0f, 3f, 1f)
                    .scale(0.55f, 0.55f, 0.55f)
                    .end()
                .transform(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)
                    .rotation(0f, -90f, 25f)
                    .translation(1.13f, 3.2f, 1.13f)
                    .scale(0.68f, 0.68f, 0.68f)
                    .end()
                .transform(ItemDisplayContext.FIXED)
                    .rotation(0f, 180f, 0f)
                    .scale(1f, 1f, 1f)
                    .end()
                .end()

            return this
        }

        /**
         * Generates item models that simply tint a base texture.
         * */
        fun fromBase(source: Iterable<Supplier<Item>>, baseTexture: String, name: String, glowing: Boolean = false) {
            source.forEach { obj ->
                val item = obj.get()

                val baseTextureResource = modLoc("item/$baseTexture")

                if(glowing) {
                    getBuilder(item.itemID.path)
                        .loadGlowingTemplate(baseTextureResource)
                }
                else {
                    getBuilder(item.itemID.path)
                        .parent(getExistingFile(mcLoc("item/generated")))
                        .texture("layer0", baseTextureResource)
                }

                LOG.info("Registered {} model for {}", name, item.itemID)
            }
        }

        fromBase(Eln2Ingredients.INGOTS.itemsForModelDatagen, "ingot_base", "ingot")
        fromBase(Eln2Ingredients.HOT_INGOTS.itemsForModelDatagen, "hot_ingot_base", "hot ingot", true)
        fromBase(Eln2Ingredients.PLATES.itemsForModelDatagen, "plate_base", "plate")
        fromBase(Eln2Ingredients.HOT_PLATES.itemsForModelDatagen, "hot_plate_base", "hot plate", true)
        fromBase(Eln2Ingredients.WIRES.itemsForModelDatagen, "wire_base", "wire")
        fromBase(Eln2Ingredients.DUSTS.itemsForModelDatagen, "dust_base", "dust")
    }
}
