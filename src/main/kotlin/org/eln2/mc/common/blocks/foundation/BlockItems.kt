package org.eln2.mc.common.blocks.foundation

import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import java.util.function.Supplier

/**
 * Wrapper for [BlockItem] that adds the crafting remainder.
 * I tried, and it doesn't work to add it to [Properties], because it seems like the block item gets registered before the remainder item gets registered in my test.
 * So we add lazy initialization.
 * */
open class Eln2BlockItemWithCraftingRemainder(val remainderSupplier: Supplier<Item>, block: Block, properties: Properties) : BlockItem(block, properties) {
    override fun hasCraftingRemainingItem(stack: ItemStack?) : Boolean {
        return true
    }

    /**
     * P.S. looks like the [Properties] are a DTO, and the remainder gets copied into a private field in the item and these 2 methods are the only things accessing it, so we're good.
     * */
    override fun getCraftingRemainingItem(itemStack: ItemStack?): ItemStack {
        return ItemStack(remainderSupplier.get())
    }
}
