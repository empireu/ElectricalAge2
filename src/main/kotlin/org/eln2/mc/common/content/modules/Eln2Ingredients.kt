@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.mathematics.geometry.Vector4d
import org.ageseries.libage.utils.addUnique
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.common.content.modules.Eln2Ingredients.TRANSFORMED_ITEMS_FOR_TINT
import org.eln2.mc.common.items.ItemRegistry
import org.eln2.mc.common.items.ItemRegistry.item
import org.eln2.mc.common.items.ItemRegistry.itemDefault
import org.eln2.mc.common.items.ItemRegistry.itemNoStack
import java.util.function.Supplier

object Eln2Ingredients : ContentModule() {
    //#region Registration Helpers

    data class IngotInfo(
        val id: String,
        val tint: MyColor,
        val ingotItem: RegistryObject<Item>
    ) : Supplier<Item> by ingotItem

    /**
     * Read by [org.eln2.mc.common.ModEvents.registerItemColors].
     * */
    val INGOTS_FOR_TINT_AND_DATAGEN = LinkedHashSet<IngotInfo>()

    private fun registerIngot(name: String, tint: MyColor) : IngotInfo {
        ContentManager.requireInit()

        val ingotItem = ItemRegistry.item(name) {
            Item(Item.Properties())
        }

        val obj = IngotInfo(name, tint, ingotItem)
        INGOTS_FOR_TINT_AND_DATAGEN.addUnique(obj)

        return obj
    }

    data class TransformedItemInfo<Type>(
        val id: String,
        val tint: MyColor,
        val registeredTransformedItem: RegistryObject<Item>,
        val sourceItem: Supplier<Item>?
    ) : Supplier<Item> by registeredTransformedItem

    /**
     * Read by [org.eln2.mc.common.ModEvents.registerItemColors].
     * */
    val TRANSFORMED_ITEMS_FOR_TINT = ArrayList<TransformedItemInfo<*>>()

    /**
     * Registers a transformed item and adds the data to [set] and to the [TRANSFORMED_ITEMS_FOR_TINT].
     * */
    private fun <Type> registerTransformedItem(name: String, set: LinkedHashSet<TransformedItemInfo<Type>>, tint: MyColor, itemSupplier: Supplier<Item>?) : TransformedItemInfo<Type> {
        ContentManager.requireInit()

        val derivedItem = ItemRegistry.item(name) {
            Item(Item.Properties())
        }

        val obj = TransformedItemInfo<Type>(name, tint, derivedItem, itemSupplier)

        TRANSFORMED_ITEMS_FOR_TINT.add(obj)
        set.addUnique(obj)

        return obj
    }

    interface Hot
    interface Plate

    /**
     * Read by [org.eln2.mc.Eln2ItemModelProviderDatagen] and [org.eln2.mc.Eln2RecipeProviderDatagen].
     * */
    val HOT_ITEMS_FOR_MODEL_AND_RECIPE_DATAGEN = LinkedHashSet<TransformedItemInfo<Hot>>()

    /**
     * Read by [org.eln2.mc.Eln2ItemModelProviderDatagen].
     * */
    val PLATES_FOR_MODEL_DATAGEN = LinkedHashSet<TransformedItemInfo<Plate>>()
    /**
     * Read by [org.eln2.mc.Eln2RecipeProviderDatagen].
     * */
    val PLATES_FOR_RECIPE_DATAGEN = LinkedHashSet<PlateBuilder>()

    fun registerHotItem(name: String, tint: MyColor, sourceItemSupplier: Supplier<Item>) = registerTransformedItem(name, HOT_ITEMS_FOR_MODEL_AND_RECIPE_DATAGEN, tint, sourceItemSupplier)

    fun registerHotIngot(ingot: IngotInfo) = registerHotItem("hot_${ingot.id}", ingot.tint, ingot.ingotItem)

    class PlateBuilder(val item: TransformedItemInfo<Plate>) {
        var hasRollingRecipe = true
        var rollingDuration = 100.0
        var hasBlacksmithingRecipe = true
        var blacksmithingDuration = 40
    }

    fun registerPlate(name: String, tint: MyColor, sourceItemSupplier: Supplier<Item>, build: (PlateBuilder.() -> Unit)? = null) : TransformedItemInfo<Plate> {
        val result = registerTransformedItem(name, PLATES_FOR_MODEL_DATAGEN, tint, sourceItemSupplier)

        val builder = PlateBuilder(result)
        build?.invoke(builder)

        return result
    }

    fun registerPlate(ingotInfo: IngotInfo) = registerPlate("${ingotInfo.id}_plate", ingotInfo.tint, ingotInfo.ingotItem)

    //#endregion

    val COKE = itemDefault("coke")

    //#region Crushed Ores

    val CRUSHED_IRON_ORE = itemDefault("crushed_iron_ore")
    val CRUSHED_COPPER_ORE = itemDefault("crushed_copper_ore")
    val CRUSHED_GOLD_ORE = itemDefault("crushed_gold_ore")

    //#endregion

    //#region Ingots and Plates

    val LEAD_INGOT = registerIngot("lead_ingot", MyColor(150, 150, 160))
    val LEAD_PLATE = registerPlate("lead_plate", MyColor(165, 165, 180), LEAD_INGOT)

    val TIN_INGOT = registerIngot("tin_ingot", MyColor(244, 235, 231))
    val TIN_PLATE = registerPlate("tin_plate", TIN_INGOT.tint, TIN_INGOT)

    val HOT_IRON_INGOT = registerHotItem("hot_iron_ingot", MyColor(255, 192, 0), Items::IRON_INGOT)
    val IRON_PLATE = registerPlate("iron_plate", MyColor(255, 255, 255), HOT_IRON_INGOT)

    val HOT_COPPER_INGOT = registerHotItem("hot_copper_ingot", MyColor(255, 127, 80), Items::COPPER_INGOT)
    val COPPER_PLATE = registerPlate("copper_plate", MyColor(184, 115, 51), HOT_COPPER_INGOT)

    val BRONZE_INGOT = registerIngot("bronze_ingot", MyColor(206, 137, 70))
    val HOT_BRONZE_INGOT = registerHotIngot(BRONZE_INGOT)
    val BRONZE_PLATE = registerPlate("bronze_plate", BRONZE_INGOT.tint, HOT_BRONZE_INGOT)

    //#endregion

    val EXTRUDER_ROD_DIE = itemNoStack("extruder_rod_die")

    val IRON_SHAFT = itemDefault("iron_shaft")

    val COPPER_ROD = itemDefault("copper_rod")

    //#region Tree Extraction

    val RAW_LATEX = itemDefault("raw_latex")
    val RAW_RESIN = itemDefault("raw_resin")

    //#endregion

    //#region Rubber

    val RUBBER_COMPOUND = item("rubber_compound") { Item(Item.Properties().stacksTo(8)) }
    val RUBBER = itemDefault("rubber")
    val BURNT_RUBBER = itemDefault("burnt_rubber")

    //#endregion
}
