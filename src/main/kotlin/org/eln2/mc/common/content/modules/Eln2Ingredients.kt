@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import org.eln2.mc.common.items.ItemRegistry.itemDefault
import org.eln2.mc.common.items.ItemRegistry.itemNoStack

object Eln2Ingredients : ContentModule() {
    val CRUSHED_IRON_ORE = itemDefault("crushed_iron_ore")
    val CRUSHED_COPPER_ORE = itemDefault("crushed_copper_ore")
    val CRUSHED_GOLD_ORE = itemDefault("crushed_gold_ore")

    val HOT_COPPER_INGOT = itemDefault("hot_copper_ingot")

    val EXTRUDER_ROD_DIE = itemNoStack("extruder_rod_die")

    val COPPER_ROD = itemDefault("copper_rod")

    val LATEX_ITEM = itemDefault("raw_latex")
}
