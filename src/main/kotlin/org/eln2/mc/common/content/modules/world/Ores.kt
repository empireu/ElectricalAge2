@file:Suppress("unused")

package org.eln2.mc.common.content.modules.world

import net.minecraft.tags.BlockTags
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.utils.addUnique
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.content.modules.ContentModule
import org.eln2.mc.common.content.modules.ContentManager.withTagDatagen
import org.eln2.mc.common.content.modules.Eln2Ingredients
import org.eln2.mc.common.items.ItemRegistry
import java.util.function.Supplier

object Eln2Ores : ContentModule() {
    //#region Registration Helpers

    interface OreRegistryItem : Supplier<Block> {
        val rawOreItem: RegistryObject<Item>
        val oreBlock: RegistryObject<Block>
        val oreBlockItem: RegistryObject<BlockItem>

        override fun get() = oreBlock.get()
    }

    /**
     * Read by [org.eln2.mc.Eln2BlockOreDropLootDatagen].
     * */
    val ORE_DROP_BLOCKS_FOR_DATAGEN = LinkedHashSet<OreRegistryItem>()

    private fun<T : OreRegistryItem> T.withDefaultOreLoot() : T {
        ORE_DROP_BLOCKS_FOR_DATAGEN.addUnique(this) {
            "Duplicate default ore loot $this"
        }

        return this
    }

    /**
     * Read by [org.eln2.mc.Eln2OreSmeltingDatagen].
     * */
    val ORE_SMELTING_FOR_DATAGEN = LinkedHashSet<Pair<OreRegistryItem, Supplier<Item>>>()

    private fun<T : OreRegistryItem> T.withVanillaSmelting(result: Supplier<Item>) : T {
        ORE_SMELTING_FOR_DATAGEN.addUnique(Pair(this, result)) {
            "Duplicate vanilla smelting $this"
        }

        return this
    }

    data class BasicOreRegistryItem(
        override val rawOreItem: RegistryObject<Item>,
        override val oreBlock: RegistryObject<Block>,
        override val oreBlockItem: RegistryObject<BlockItem>
    ) : OreRegistryItem

    /**
     * Registers an ore consisting of a raw item form (`raw_`[oreName]), block and item ([oreName]`_ore`).
     * Drops and worldgen need to be added in the datapacks.
     * */
    fun basicOre(oreName: String) : BasicOreRegistryItem {
        val rawOreItem = ItemRegistry.item("raw_$oreName") {
            Item(Item.Properties())
        }

        val oreBlock = BlockRegistry.blockOnly("${oreName}_ore") {
            Block(BlockBehaviour.Properties.copy(Blocks.IRON_ORE).requiresCorrectToolForDrops())
        }

        val oreBlockItem = BlockRegistry.blockItemOnly("${oreName}_ore") {
            BlockItem(oreBlock.get(), Item.Properties())
        }

        return BasicOreRegistryItem(rawOreItem, oreBlock, oreBlockItem)
    }

    //#endregion

    val MAGNETITE_ORE = basicOre("magnetite")
        .withTagDatagen(BlockTags.MINEABLE_WITH_PICKAXE)
        .withTagDatagen(BlockTags.NEEDS_IRON_TOOL)
        .withDefaultOreLoot()
        .withVanillaSmelting(Eln2Ingredients.MAGNETITE)
}
