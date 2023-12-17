@file:Suppress("unused")

package org.eln2.mc.common.items

import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.RegistryObject
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.content.Content
import org.eln2.mc.common.parts.PartRegistry

object CreativeTabRegistry {
    @Suppress("MemberVisibilityCanBePrivate") // Used for item registration and fetching
    val REGISTRY = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID)!! // Yeah, if this fails blow up the game

    fun setup(bus: IEventBus) {
        REGISTRY.register(bus)
        LOG.info("Prepared creative tab registry.")
    }

    private val BLACKLIST by lazy {
        setOf(
            BlockRegistry.MULTIPART_BLOCK.item.get(),
            ItemRegistry.MULTIBLOCK_SCAN_TOOL.item.get()
        )
    }

    val ELN2_ALL: RegistryObject<CreativeModeTab> = REGISTRY.register("eln2") {
        CreativeModeTab.builder()
            .title(Component.translatable("item_group.${MODID}.all"))
            .icon { ItemStack(Content.VOLTAGE_SOURCE_PART.item.get()) }
            .displayItems { _: ItemDisplayParameters?, output: CreativeModeTab.Output ->
                fun map(registry: DeferredRegister<Item>) {
                    output.acceptAll(
                        registry.entries
                            .map { it.get() }
                            .filter { !BLACKLIST.contains(it) }
                            .map { ItemStack(it) }
                    )
                }

                map(ItemRegistry.ITEMS)
                map(BlockRegistry.BLOCK_ITEMS)
                map(PartRegistry.PART_ITEMS)
            }.build()
    }
}
