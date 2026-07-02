@file:Suppress("unused", "UNCHECKED_CAST")

package org.eln2.mc.integration

import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.category.IRecipeCategory
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.registration.IRecipeRegistration
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeManager
import net.minecraft.world.level.ItemLike
import net.minecraftforge.fluids.FluidType
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.data.classify
import org.ageseries.libage.data.classifyAuxiliary
import org.eln2.mc.Eln2Config
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.content.processing.*
import org.eln2.mc.common.recipes.foundation.CatalyzedSimpleProcessingRecipe
import org.eln2.mc.common.recipes.foundation.DirectSimpleProcessingRecipe
import org.eln2.mc.resource
import java.util.function.Supplier

/**
 * Each processing machine recipe type gets one category
 * Categories are built in [registerCategories], then recipes are fetched from the [RecipeManager] and assigned in [registerRecipes].
 * */
@JeiPlugin
class Eln2Jei : IModPlugin {
    private lateinit var crushingCategory: CrushingCategory
    private lateinit var rollingCategory: RollingCategory
    private lateinit var extrudingCategory: ExtrudingCategory
    private lateinit var cokingCategory: CokingCategory
    private lateinit var alloyingCategory: AlloyingCategory
    private lateinit var vulcanizingCategory: VulcanizingCategory
    private lateinit var burningCategory: BurningCategory
    private lateinit var hydrogenReductionCategory: HydrogenReductionCategory
    private lateinit var electrolysisCategory: ElectrolysisCategory

    override fun getPluginUid(): ResourceLocation = resource("jei_plugin")

    /**
     * Builds all six categories. The `guiHelper` provides JEI drawable factories and is passed to each category constructor for slot/background creation.
     * */
    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        crushingCategory = CrushingCategory(registration.jeiHelpers.guiHelper)
        rollingCategory = RollingCategory(registration.jeiHelpers.guiHelper)
        extrudingCategory = ExtrudingCategory(registration.jeiHelpers.guiHelper)
        cokingCategory = CokingCategory(registration.jeiHelpers.guiHelper)
        alloyingCategory = AlloyingCategory(registration.jeiHelpers.guiHelper)
        vulcanizingCategory = VulcanizingCategory(registration.jeiHelpers.guiHelper)
        burningCategory = BurningCategory(registration.jeiHelpers.guiHelper)
        hydrogenReductionCategory = HydrogenReductionCategory(registration.jeiHelpers.guiHelper)

        electrolysisCategory = ElectrolysisCategory(registration.jeiHelpers.guiHelper)
        registration.addRecipeCategories(
            crushingCategory,
            rollingCategory,
            extrudingCategory,
            cokingCategory,
            alloyingCategory,
            vulcanizingCategory,
            burningCategory,
            hydrogenReductionCategory,
            electrolysisCategory
        )
    }

    /**
     * Fetches recipes from the client-level [RecipeManager] and assigns them to each category.
     * */
    override fun registerRecipes(registration: IRecipeRegistration) {
        val level = Minecraft.getInstance().level
            ?: return

        val recipeManager = level.recipeManager

        registerCategory(registration, crushingCategory, recipeManager, Eln2Processing.CRUSHING_RECIPE)
        registerCategory(registration, rollingCategory, recipeManager, Eln2Processing.ROLLING_RECIPE)
        registerCategory(registration, extrudingCategory, recipeManager, Eln2Processing.EXTRUDING_RECIPE)
        registerCategory(registration, cokingCategory, recipeManager, Eln2Processing.COKING_RECIPE)
        registerCategory(registration, alloyingCategory, recipeManager, Eln2Processing.ALLOYING_RECIPE)
        registerCategory(registration, vulcanizingCategory, recipeManager, Eln2Processing.VULCANIZING_RECIPE)
        registerCategory(registration, burningCategory, recipeManager, Eln2Processing.BURNING_RECIPE)
        registerCategory(registration, electrolysisCategory, recipeManager, Eln2Processing.ELECTROLYSIS_RECIPE)
        registerCategory(registration, hydrogenReductionCategory, recipeManager, Eln2Processing.HYDROGEN_REDUCTION_RECIPE)
    }

    /**
     * Registers machine blocks as JEI catalysts (**P.S. the name "catalyst" is unrelated to ELN2 recipe catalysts!**).
     * Clicking these in JEI shows recipes for that category.
     * Only machine blocks are registered here (not recipe ingredients, like dies).
     * */
    override fun registerRecipeCatalysts(registration: IRecipeCatalystRegistration) {
        listOf(
            crushingCategory,
            rollingCategory,
            extrudingCategory,
            cokingCategory,
            alloyingCategory,
            vulcanizingCategory,
            burningCategory,
            hydrogenReductionCategory,
            electrolysisCategory
        ).forEach { category ->
            category.collectCatalysts().forEach { catalyst ->
                registration.addRecipeCatalyst(catalyst.get(), category.jeiRecipeType)
            }
        }
    }

    private inline fun <reified T : Recipe<SimpleContainer>> registerCategory(
        registration: IRecipeRegistration,
        category: Eln2RecipeCategory<T>,
        recipeManager: RecipeManager,
        recipeType: net.minecraft.world.item.crafting.RecipeType<T>
    ) {
        val recipes = recipeManager.getAllRecipesFor(recipeType)
        category.setRecipes(recipes)
        registration.addRecipes(category.jeiRecipeType, recipes)
    }
}

/**
 * Base class for all ELN2 JEI recipe categories.
 * Wraps [IRecipeCategory] with shared state (recipes, catalysts), a tick timer for the animated progress arrow, and the furnace texture arrow drawing helper.
 * */
abstract class Eln2RecipeCategory<T : Recipe<*>>(
    val jeiRecipeType: RecipeType<T>,
    val categoryTitle: Component,
    private val categoryWidth: Int,
    private val categoryHeight: Int,
    val categoryIcon: IDrawable,
    private val categoryCatalysts: List<Supplier<ItemStack>>,
    guiHelper: IGuiHelper
) : IRecipeCategory<T> {
    private val arrowTimer = guiHelper.createTickTimer(40, 24, false)

    private var recipes: List<T> = emptyList()

    fun setRecipes(recipes: List<T>) {
        this.recipes = recipes
    }

    fun collectRecipes(): List<T> = recipes

    fun collectCatalysts(): List<Supplier<ItemStack>> = categoryCatalysts

    override fun getRecipeType(): RecipeType<T> = jeiRecipeType

    override fun getTitle(): Component = categoryTitle

    override fun getWidth(): Int = categoryWidth

    override fun getHeight(): Int = categoryHeight

    override fun getIcon(): IDrawable = categoryIcon

    protected fun noteArrow(graphics: GuiGraphics, x: Int, y: Int) {
        val texture = resource("textures/gui/container/progress_arrows.png")
        graphics.blit(
            texture,
            x, y,
            0.0f, 0.0f,
            22, 15,
            32, 32
        )

        val progress = arrowTimer.value * 22.0f / 24.0f

        if (progress > 0) {
            graphics.blit(
                texture,
                x, y,
                0.0f, 16.0f,
                progress.toInt(), 16,
                32, 32
            )
        }
    }
}

/**
 * Drawable that renders an [ItemStack] as a category icon.
 * */
class ItemIcon(private val stacks: List<ItemStack>) : IDrawable {
    constructor(item: ItemLike, count: Int = 1) : this(listOf(ItemStack(item, count)))
    constructor(stack: ItemStack) : this(listOf(stack))

    override fun getWidth(): Int = 16

    override fun getHeight(): Int = 16

    override fun draw(graphics: GuiGraphics, xOffset: Int, yOffset: Int) {
        if (stacks.isNotEmpty()) {
            graphics.renderFakeItem(stacks[0], xOffset, yOffset)
        }
    }
}

class SlotBackground(private val type: Type = Type.STANDARD) : IDrawable {
    enum class Type(val u: Int, val v: Int, val width: Int, val height: Int) {
        STANDARD(0, 0, 18, 18),
        BIG(32, 0, 32, 32),
        SMALL(40, 33, 16, 16)
    }

    override fun getWidth(): Int = type.width

    override fun getHeight(): Int = type.height

    override fun draw(graphics: GuiGraphics, xOffset: Int, yOffset: Int) {
        graphics.blit(
            resource("textures/gui/container/inventory_slots.png"),
            xOffset, yOffset,
            type.u.toFloat(), type.v.toFloat(),
            type.width, type.height,
            64, 64
        )
    }
}

/**
 * JEI category for the Alloying Smelter.
 * Uses [AlloyingRecipe] with weighted multi-item inputs from the ingredient grid.
 * Shows the first option from each weighted requirement group as input, and a single output slot.
 * */
class AlloyingCategory(guiHelper: IGuiHelper) : Eln2RecipeCategory<AlloyingRecipe>(
    jeiRecipeType = RecipeType(resource("alloying"), AlloyingRecipe::class.java),
    categoryTitle = Component.translatable("recipe.eln2.alloying"),
    categoryWidth = 177,
    categoryHeight = 85,
    categoryIcon = ItemIcon(ItemStack(Eln2Processing.ALLOYING_SMELTER_BLOCK.item.get())),
    categoryCatalysts = listOf(
        Supplier { ItemStack(Eln2Processing.ALLOYING_SMELTER_BLOCK.item.get()) }
    ),
    guiHelper
) {
    private val slot = SlotBackground()

    private val inputPositions = listOf(
        21 to 24,
        48 to 24,
        75 to 24,
        102 to 24
    )

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: AlloyingRecipe, focuses: IFocusGroup) {
        for ((index, requirement) in recipe.inputItems.requirements.withIndex()) {
            if (index >= inputPositions.size) {
                break
            }

            val (x, y) = inputPositions[index]
            val firstOption = requirement.options.firstOrNull()
                ?: continue

            builder.addSlot(RecipeIngredientRole.INPUT, x, y)
                .setBackground(slot, -1, -1)
                .addIngredients(firstOption.ingredient)
        }

        builder.addSlot(RecipeIngredientRole.OUTPUT, 129, 35)
            .setBackground(slot, -1, -1)
            .addItemStack(recipe.output)
    }

    override fun draw(
        recipe: AlloyingRecipe,
        recipeSlotsView: IRecipeSlotsView,
        graphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double
    ) {
        noteArrow(graphics, 62, 38)
    }
}

/**
 * JEI category for the Coke Oven.
 * Uses [CokingRecipe] with weighted multi-item inputs, up to 4 output items, and an optional fluid output.
 * The first ingredient option from each weighted requirement group is shown in the input grid.
 * */
class CokingCategory(guiHelper: IGuiHelper) : Eln2RecipeCategory<CokingRecipe>(
    jeiRecipeType = RecipeType(resource("coking"), CokingRecipe::class.java),
    categoryTitle = Component.translatable("recipe.eln2.coking"),
    categoryWidth = 177,
    categoryHeight = 110,
    categoryIcon = ItemIcon(ItemStack(Eln2Processing.COKE_OVEN_BLOCK_ITEM.get())),
    categoryCatalysts = listOf(
        Supplier { ItemStack(Eln2Processing.COKE_OVEN_BLOCK_ITEM.get()) }
    ),
    guiHelper
) {
    private val slot = SlotBackground()

    private val inputPositions = listOf(
        21 to 24,
        48 to 24,
        75 to 24,
        102 to 24
    )

    private val outputPositions = listOf(
        25 to 49,
        51 to 49,
        77 to 49,
        103 to 49
    )

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: CokingRecipe, focuses: IFocusGroup) {
        for ((index, requirement) in recipe.inputItems.requirements.withIndex()) {
            if (index >= inputPositions.size) {
                break
            }

            val (x, y) = inputPositions[index]
            val firstOption = requirement.options.firstOrNull()
                ?: continue

            builder.addSlot(RecipeIngredientRole.INPUT, x, y)
                .setBackground(slot, -1, -1)
                .addIngredients(firstOption.ingredient)
        }

        if (recipe.outputItems != null) {
            for ((index, stack) in recipe.outputItems.withIndex()) {
                if (index >= outputPositions.size) {
                    break
                }

                val (x, y) = outputPositions[index]
                builder.addSlot(RecipeIngredientRole.OUTPUT, x, y)
                    .setBackground(slot, -1, -1)
                    .addItemStack(stack)
            }
        }

        if (recipe.outputFluid != null && !recipe.outputFluid.isEmpty) {
            val fluidStack = recipe.outputFluid
            builder.addSlot(RecipeIngredientRole.OUTPUT, 132, 24)
                .setBackground(slot, -1, -1)
                .addFluidStack(fluidStack.fluid, fluidStack.amount.toLong())
                .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)
        }
    }

    override fun draw(
        recipe: CokingRecipe,
        recipeSlotsView: IRecipeSlotsView,
        graphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double
    ) {
        noteArrow(graphics, 62, 40)
    }
}

/**
 * JEI category for the Crusher. Single input ingredient, single output item.
 * */
class CrushingCategory(guiHelper: IGuiHelper) : Eln2RecipeCategory<DirectSimpleProcessingRecipe>(
    jeiRecipeType = RecipeType(resource("crushing"), DirectSimpleProcessingRecipe::class.java),
    categoryTitle = Component.translatable("recipe.eln2.crushing"),
    categoryWidth = 177,
    categoryHeight = 70,
    categoryIcon = ItemIcon(ItemStack(Eln2Processing.CRUSHER_PRIMITIVE_KINETIC.blockAndItem.item.get())),
    categoryCatalysts = listOf(
        Supplier { ItemStack(Eln2Processing.CRUSHER_PRIMITIVE_KINETIC.blockAndItem.item.get()) },
        Supplier { ItemStack(Eln2Processing.CRUSHER_BRUSHED_DC_MOTOR.blockAndItem.item.get()) }
    ),
    guiHelper
) {
    private val slot = SlotBackground()

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: DirectSimpleProcessingRecipe, focuses: IFocusGroup) {
        builder.addSlot(RecipeIngredientRole.INPUT, 27, 29)
            .setBackground(slot, -1, -1)
            .addIngredients(recipe.input)

        builder.addSlot(RecipeIngredientRole.OUTPUT, 132, 29)
            .setBackground(slot, -1, -1)
            .addItemStack(recipe.output)
    }

    override fun draw(
        recipe: DirectSimpleProcessingRecipe,
        recipeSlotsView: IRecipeSlotsView,
        graphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double
    ) {
        noteArrow(graphics, 62, 32)
    }
}

/**
 * JEI category for the Extruder.
 * Uses [CatalyzedSimpleProcessingRecipe]: shows an input slot, a catalyst slot (the die), and an output slot.
 * */
class ExtrudingCategory(guiHelper: IGuiHelper) : Eln2RecipeCategory<CatalyzedSimpleProcessingRecipe>(
    jeiRecipeType = RecipeType(resource("extruding"), CatalyzedSimpleProcessingRecipe::class.java),
    categoryTitle = Component.translatable("recipe.eln2.extruding"),
    categoryWidth = 177,
    categoryHeight = 80,
    categoryIcon = ItemIcon(ItemStack(Eln2Processing.EXTRUDER_PRIMITIVE_KINETIC.blockAndItem.item.get())),
    categoryCatalysts = listOf(
        Supplier { ItemStack(Eln2Processing.EXTRUDER_PRIMITIVE_KINETIC.blockAndItem.item.get()) },
        Supplier { ItemStack(Eln2Processing.EXTRUDER_BRUSHED_DC_MOTOR.blockAndItem.item.get()) }
    ),
    guiHelper
) {
    private val slot = SlotBackground()

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: CatalyzedSimpleProcessingRecipe, focuses: IFocusGroup) {
        builder.addSlot(RecipeIngredientRole.INPUT, 27, 24)
            .setBackground(slot, -1, -1)
            .addIngredients(recipe.input)

        builder.addSlot(RecipeIngredientRole.CATALYST, 27, 49)
            .setBackground(slot, -1, -1)
            .addIngredients(recipe.catalyst)

        builder.addSlot(RecipeIngredientRole.OUTPUT, 132, 35)
            .setBackground(slot, -1, -1)
            .addItemStack(recipe.output)
    }

    override fun draw(
        recipe: CatalyzedSimpleProcessingRecipe,
        recipeSlotsView: IRecipeSlotsView,
        graphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double
    ) {
        noteArrow(graphics, 62, 35)
    }
}

/**
 * JEI category for the Rolling Machine. Same recipe structure as crushing ([DirectSimpleProcessingRecipe]) but maps to rolling recipes and the rolling machine blocks.
 * */
class RollingCategory(guiHelper: IGuiHelper) : Eln2RecipeCategory<DirectSimpleProcessingRecipe>(
    jeiRecipeType = RecipeType(resource("rolling"), DirectSimpleProcessingRecipe::class.java),
    categoryTitle = Component.translatable("recipe.eln2.rolling"),
    categoryWidth = 177,
    categoryHeight = 70,
    categoryIcon = ItemIcon(ItemStack(Eln2Processing.ROLLING_MACHINE_PRIMITIVE_KINETIC.blockAndItem.item.get())),
    categoryCatalysts = listOf(
        Supplier { ItemStack(Eln2Processing.ROLLING_MACHINE_PRIMITIVE_KINETIC.blockAndItem.item.get()) },
        Supplier { ItemStack(Eln2Processing.ROLLING_MACHINE_BRUSHED_DC_MOTOR.blockAndItem.item.get()) }
    ),
    guiHelper
) {
    private val slot = SlotBackground()

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: DirectSimpleProcessingRecipe, focuses: IFocusGroup) {
        builder.addSlot(RecipeIngredientRole.INPUT, 27, 29)
            .setBackground(slot, -1, -1)
            .addIngredients(recipe.input)

        builder.addSlot(RecipeIngredientRole.OUTPUT, 132, 29)
            .setBackground(slot, -1, -1)
            .addItemStack(recipe.output)
    }

    override fun draw(
        recipe: DirectSimpleProcessingRecipe,
        recipeSlotsView: IRecipeSlotsView,
        graphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double
    ) {
        noteArrow(graphics, 62, 32)
    }
}

/**
 * JEI category for the Burner Reactor.
 * Uses [BurningRecipe] with up to 4 weighted item inputs, an optional output item, and an optional fluid output.
 * Follows the same layout pattern as [CokingCategory].
 * */
class BurningCategory(guiHelper: IGuiHelper) : Eln2RecipeCategory<BurningRecipe>(
    jeiRecipeType = RecipeType(resource("burning"), BurningRecipe::class.java),
    categoryTitle = Component.translatable("recipe.eln2.burning"),
    categoryWidth = 177,
    categoryHeight = 110,
    categoryIcon = ItemIcon(ItemStack(Eln2Processing.BURNING_BLOCK.item.get())),
    categoryCatalysts = listOf(
        Supplier { ItemStack(Eln2Processing.BURNING_BLOCK.item.get()) }
    ),
    guiHelper
) {
    private val slot = SlotBackground()

    private val inputPositions = listOf(
        21 to 24,
        48 to 24,
        75 to 24,
        102 to 24
    )

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: BurningRecipe, focuses: IFocusGroup) {
        for ((index, requirement) in recipe.inputItems.requirements.withIndex()) {
            if (index >= inputPositions.size) {
                break
            }

            val (x, y) = inputPositions[index]
            val firstOption = requirement.options.firstOrNull()
                ?: continue

            builder.addSlot(RecipeIngredientRole.INPUT, x, y)
                .setBackground(slot, -1, -1)
                .addIngredients(firstOption.ingredient)
        }

        if (recipe.outputItem != null && !recipe.outputItem.isEmpty) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, 25, 49)
                .setBackground(slot, -1, -1)
                .addItemStack(recipe.outputItem)
        }

        if (recipe.outputFluid != null && !recipe.outputFluid.isEmpty) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, 132, 24)
                .setBackground(slot, -1, -1)
                .addFluidStack(recipe.outputFluid.fluid, recipe.outputFluid.amount.toLong())
                .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)
        }
    }

    override fun draw(
        recipe: BurningRecipe,
        recipeSlotsView: IRecipeSlotsView,
        graphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double
    ) {
        noteArrow(graphics, 62, 40)
    }
}

/**
 * JEI category for the Vulcanizing Autoclave. Shows the input item, the successful output, and the burnt output (when temperature exceeds the recipe's max temperature).
 * Tooltips display the temperature range and which result is the burn failure.
 * */
class VulcanizingCategory(guiHelper: IGuiHelper) : Eln2RecipeCategory<VulcanizingRecipe>(
    jeiRecipeType = RecipeType(resource("vulcanizing"), VulcanizingRecipe::class.java),
    categoryTitle = Component.translatable("recipe.eln2.vulcanizing"),
    categoryWidth = 177,
    categoryHeight = 85,
    categoryIcon = ItemIcon(ItemStack(Eln2Processing.VULCANIZING_AUTOCLAVE_BLOCK_ITEM.get())),
    categoryCatalysts = listOf(
        Supplier { ItemStack(Eln2Processing.VULCANIZING_AUTOCLAVE_BLOCK_ITEM.get()) }
    ),
    guiHelper
) {
    private val slot = SlotBackground()

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: VulcanizingRecipe, focuses: IFocusGroup) {
        builder.addSlot(RecipeIngredientRole.INPUT, 27, 35)
            .setBackground(slot, -1, -1)
            .addIngredients(recipe.input)

        builder.addSlot(RecipeIngredientRole.OUTPUT, 115, 24)
            .setBackground(slot, -1, -1)
            .addItemStack(recipe.output.copy())
            .addRichTooltipCallback { view, tooltip ->
                tooltip.add(Component.translatable("jei.eln2.vulcanizing.temperature_range",
                    String.format("%.0f", recipe.minTemperature),
                    String.format("%.0f", recipe.maxTemperature)))
            }

        if (!recipe.burnOutput.isEmpty) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, 115, 49)
                .setBackground(slot, -1, -1)
                .addItemStack(recipe.burnOutput.copy())
                .addRichTooltipCallback { view, tooltip ->
                    tooltip.add(Component.translatable("jei.eln2.vulcanizing.burnt"))
                }
        }
    }

    override fun draw(
        recipe: VulcanizingRecipe,
        recipeSlotsView: IRecipeSlotsView,
        graphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double
    ) {
        noteArrow(graphics, 60, 38)
    }
}

/**
 * JEI category for the Hydrogen Reduction Furnace.
 * Shows the 4 input items, the progress arrow with a temperature label above,
 * the hydrogen amount below the arrow, and the output item.
 * */
class HydrogenReductionCategory(guiHelper: IGuiHelper) : Eln2RecipeCategory<HydrogenReductionRecipe>(
    jeiRecipeType = RecipeType(resource("hydrogen_reduction"), HydrogenReductionRecipe::class.java),
    categoryTitle = Component.translatable("recipe.eln2.hydrogen_reduction"),
    categoryWidth = 177,
    categoryHeight = 85,
    categoryIcon = ItemIcon(ItemStack(Eln2Processing.HYDROGEN_REDUCTION_FURNACE_BLOCK.item.get())),
    categoryCatalysts = listOf(
        Supplier { ItemStack(Eln2Processing.HYDROGEN_REDUCTION_FURNACE_BLOCK.item.get()) }
    ),
    guiHelper
) {
    private val slot = SlotBackground()

    private val inputPositions = listOf(
        21 to 24,
        48 to 24,
        75 to 24,
        102 to 24,
    )

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: HydrogenReductionRecipe, focuses: IFocusGroup) {
        for ((index, requirement) in recipe.inputItems.requirements.withIndex()) {
            if (index >= inputPositions.size) break

            val (x, y) = inputPositions[index]
            val firstOption = requirement.options.firstOrNull() ?: continue

            builder.addSlot(RecipeIngredientRole.INPUT, x, y)
                .setBackground(slot, -1, -1)
                .addIngredients(firstOption.ingredient)
        }

        builder.addSlot(RecipeIngredientRole.OUTPUT, 129, 35)
            .setBackground(slot, -1, -1)
            .addItemStack(recipe.output)
    }

    override fun draw(
        recipe: HydrogenReductionRecipe,
        recipeSlotsView: IRecipeSlotsView,
        graphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double
    ) {
        noteArrow(graphics, 62, 38)

        val scale = Eln2Config.clientConfig.getScaleOverride(Temperature::class.java)

        // Temperature label above the arrow:
        val tempText = if(scale != null) {
            "${classifyAuxiliary(scale, !recipe.minimumTemperature)} - ${classifyAuxiliary(scale, !recipe.optimalTemperature)}"
        }
        else {
            recipe.minimumTemperature.classify()
        }
        graphics.drawString(
            Minecraft.getInstance().font,
            tempText,
            62 + 11 - Minecraft.getInstance().font.width(tempText) / 2,
            24,
            0xFF5555,
            false
        )

        // Hydrogen amount below the arrow:
        val h2Text = "${recipe.hydrogenAmount} mB H₂"
        graphics.drawString(
            Minecraft.getInstance().font,
            h2Text,
            62 + 11 - Minecraft.getInstance().font.width(h2Text) / 2,
            58,
            0x5555FF,
            false
        )
    }
}

class ElectrolysisCategory(guiHelper: IGuiHelper) : Eln2RecipeCategory<AqueousElectrolysisRecipe>(
    jeiRecipeType = RecipeType(resource("electrolysis"), AqueousElectrolysisRecipe::class.java),
    categoryTitle = Component.translatable("recipe.eln2.electrolysis"),
    categoryWidth = 177,
    categoryHeight = 95,
    categoryIcon = ItemIcon(ItemStack(Eln2Processing.ELECTROLYSIS_BLOCK_ITEM.get())),
    categoryCatalysts = listOf(
        Supplier { ItemStack(Eln2Processing.ELECTROLYSIS_BLOCK_ITEM.get()) }
    ),
    guiHelper
) {
    private val slot = SlotBackground()

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: AqueousElectrolysisRecipe, focuses: IFocusGroup) {
        if (!recipe.isSeparated) {
            recipe as NonSeparatedAqueousElectrolysisRecipe

            builder.addSlot(RecipeIngredientRole.INPUT, 21, 28)
                .setBackground(slot, -1, -1)
                .addFluidStack(recipe.inputFluid.fluid, recipe.inputFluid.amount.toLong())
                .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)

            if (recipe.outputGas != null && !recipe.outputGas.isEmpty) {
                builder.addSlot(RecipeIngredientRole.OUTPUT, 115, 5)
                    .setBackground(slot, -1, -1)
                    .addFluidStack(recipe.outputGas.fluid, recipe.outputGas.amount.toLong())
                    .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)
            }

            if (recipe.outputFluid != null && !recipe.outputFluid.isEmpty) {
                builder.addSlot(RecipeIngredientRole.OUTPUT, 115, 28)
                    .setBackground(slot, -1, -1)
                    .addFluidStack(recipe.outputFluid.fluid, recipe.outputFluid.amount.toLong())
                    .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)
            }

            if (recipe.anodeOutputItem != null && !recipe.anodeOutputItem.isEmpty) {
                builder.addSlot(RecipeIngredientRole.OUTPUT, 115, 51)
                    .setBackground(slot, -1, -1)
                    .addItemStack(recipe.anodeOutputItem)
            }

            // Electrode info slots:
            builder.addSlot(RecipeIngredientRole.CATALYST, 40, 75)
                .setBackground(slot, -1, -1)
                .addIngredients(recipe.anodeElectrode)

            builder.addSlot(RecipeIngredientRole.CATALYST, 80, 75)
                .setBackground(slot, -1, -1)
                .addIngredients(recipe.cathodeElectrode)

            if (recipe.cathodeOutputItem != null && !recipe.cathodeOutputItem.isEmpty) {
                builder.addSlot(RecipeIngredientRole.OUTPUT, 140, 51)
                    .setBackground(slot, -1, -1)
                    .addItemStack(recipe.cathodeOutputItem)
            }
        }
        else {
            recipe as SeparatedAqueousElectrolysisRecipe

            builder.addSlot(RecipeIngredientRole.INPUT, 15, 10)
                .setBackground(slot, -1, -1)
                .addFluidStack(recipe.anodeInputFluid.fluid, recipe.anodeInputFluid.amount.toLong())
                .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)

            builder.addSlot(RecipeIngredientRole.INPUT, 57, 10)
                .setBackground(slot, -1, -1)
                .addFluidStack(recipe.cathodeInputFluid.fluid, recipe.cathodeInputFluid.amount.toLong())
                .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)

            // Separator:
            builder.addSlot(RecipeIngredientRole.CATALYST, 36, 36)
                .setBackground(slot, -1, -1)
                .addIngredients(recipe.separator)

            // Anode outputs:
            if (recipe.anodeOutputGas != null && !recipe.anodeOutputGas.isEmpty) {
                builder.addSlot(RecipeIngredientRole.OUTPUT, 105, 5)
                    .setBackground(slot, -1, -1)
                    .addFluidStack(recipe.anodeOutputGas.fluid, recipe.anodeOutputGas.amount.toLong())
                    .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)
            }

            if (recipe.anodeOutputFluid != null && !recipe.anodeOutputFluid.isEmpty) {
                builder.addSlot(RecipeIngredientRole.OUTPUT, 105, 28)
                    .setBackground(slot, -1, -1)
                    .addFluidStack(recipe.anodeOutputFluid.fluid, recipe.anodeOutputFluid.amount.toLong())
                    .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)
            }

            if (recipe.anodeOutputItem != null && !recipe.anodeOutputItem.isEmpty) {
                builder.addSlot(RecipeIngredientRole.OUTPUT, 105, 51)
                    .setBackground(slot, -1, -1)
                    .addItemStack(recipe.anodeOutputItem)
            }

            // Cathode outputs:
            if (recipe.cathodeOutputGas != null && !recipe.cathodeOutputGas.isEmpty) {
                builder.addSlot(RecipeIngredientRole.OUTPUT, 137, 5)
                    .setBackground(slot, -1, -1)
                    .addFluidStack(recipe.cathodeOutputGas.fluid, recipe.cathodeOutputGas.amount.toLong())
                    .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)
            }

            if (recipe.cathodeOutputFluid != null && !recipe.cathodeOutputFluid.isEmpty) {
                builder.addSlot(RecipeIngredientRole.OUTPUT, 137, 28)
                    .setBackground(slot, -1, -1)
                    .addFluidStack(recipe.cathodeOutputFluid.fluid, recipe.cathodeOutputFluid.amount.toLong())
                    .setFluidRenderer(FluidType.BUCKET_VOLUME.toLong(), false, 16, 16)
            }

            if (recipe.cathodeOutputItem != null && !recipe.cathodeOutputItem.isEmpty) {
                builder.addSlot(RecipeIngredientRole.OUTPUT, 137, 51)
                    .setBackground(slot, -1, -1)
                    .addItemStack(recipe.cathodeOutputItem)
            }

            // Electrode info slots:
            builder.addSlot(RecipeIngredientRole.CATALYST, 21, 75)
                .setBackground(slot, -1, -1)
                .addIngredients(recipe.anodeElectrode)

            builder.addSlot(RecipeIngredientRole.CATALYST, 80, 75)
                .setBackground(slot, -1, -1)
                .addIngredients(recipe.cathodeElectrode)
        }
    }

    override fun draw(
        recipe: AqueousElectrolysisRecipe,
        recipeSlotsView: IRecipeSlotsView,
        graphics: GuiGraphics,
        mouseX: Double,
        mouseY: Double
    ) {
        if (!recipe.isSeparated) {
            noteArrow(graphics, 62, 32)
        }
        else {
            noteArrow(graphics, 62, 15)
        }
    }
}
