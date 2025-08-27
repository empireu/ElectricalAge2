@file:Suppress("unused")

package org.eln2.mc.common.items

import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.MutableSetMapMultiMap
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.content.Content
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.specs.SpecRegistry
import java.util.function.Supplier

object CreativeTabRegistry {
    @Suppress("MemberVisibilityCanBePrivate") // Used for item registration and fetching
    val REGISTRY = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID)!! // Yeah, if this fails blow up the game

    fun setup(bus: IEventBus) {
        REGISTRY.register(bus)
        LOG.info("Prepared creative tab registry.")
    }

    private val BLACKLIST = mutableSetOf<Supplier<Item>>()

    fun<T : Item> blacklistTab(supplier: Supplier<T>) {
        check(!realized)

        BLACKLIST.add {
            supplier.get()
        }
    }

    private var realized = false

    private val VARIANTS = ArrayList<Supplier<ItemStack>>()

    fun creativeTabVariant(variant: Supplier<ItemStack>) {
        check(!realized) {
            "Tried to add variant after realized"
        }

        VARIANTS.add(variant)
    }

    val ELN2_ALL: RegistryObject<CreativeModeTab> = REGISTRY.register("eln2") {
        CreativeModeTab.builder()
            .title(Component.translatable("item_group.${MODID}.all"))
            .icon { ItemStack(Content.VOLTAGE_SOURCE_PART.item.get()) }
            .displayItems { _: ItemDisplayParameters?, output: CreativeModeTab.Output ->
                realized = true

                val variantLookup = MutableSetMapMultiMap<Item, ItemStack>()

                VARIANTS.forEach {
                    val stack = it.get()
                    variantLookup[stack.item].add(stack)
                }

                val blacklisted = BLACKLIST.mapTo (HashSet()) { it.get() }

                fun map(registry: DeferredRegister<Item>) {
                    output.acceptAll(
                        registry.entries
                            .map { it.get() }
                            .filter { !blacklisted.contains(it) }
                            .flatMap { variantLookup.map.remove(it) ?: listOf(ItemStack(it)) }
                    )
                }

                map(ItemRegistry.ITEMS)
                map(BlockRegistry.BLOCK_ITEMS)
                map(PartRegistry.PART_ITEMS)
                map(SpecRegistry.SPEC_ITEMS)

                if(variantLookup.map.isNotEmpty()) {
                    LOG.error("Did not map variants $variantLookup")
                }
            }.build()
    }
}

fun<T : Block> BlockRegistry.BlockRegistryItem<T>.blacklistCreativeTab() : BlockRegistry.BlockRegistryItem<T> {
    CreativeTabRegistry.blacklistTab(this.item)
    return this
}

fun PartRegistry.PartRegistryItem.blacklistCreativeTab() : PartRegistry.PartRegistryItem {
    CreativeTabRegistry.blacklistTab(this.item)
    return this
}
