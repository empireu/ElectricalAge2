@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import net.minecraft.world.item.Item
import org.eln2.mc.common.items.ItemRegistry.item
import org.eln2.mc.common.items.ItemRegistry.itemDefault
import org.eln2.mc.common.items.ItemRegistry.itemNoStack

object Eln2Ingredients : ContentModule() {
    val COKE = itemDefault("coke")

    val MAGNETITE = itemDefault("magnetite")

    //#region Crushed Ores

    val CRUSHED_IRON_ORE = itemDefault("crushed_iron_ore")
    val CRUSHED_COPPER_ORE = itemDefault("crushed_copper_ore")
    val CRUSHED_GOLD_ORE = itemDefault("crushed_gold_ore")

    //#endregion

    //#region Hot Metals

    val HOT_IRON_INGOT = itemDefault("hot_iron_ingot")
    val HOT_COPPER_INGOT = itemDefault("hot_copper_ingot")

    //#endregion

    val EXTRUDER_ROD_DIE = itemNoStack("extruder_rod_die")

    val IRON_PLATE = itemDefault("iron_plate")

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
