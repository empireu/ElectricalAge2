@file:Suppress("unused") // Because block variables here would be suggested for deletion.

package org.eln2.mc.common.items

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import java.util.function.Supplier

object ItemRegistry {
    val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID)!! // Yeah, if this fails blow up the game

    fun setup(bus: IEventBus) {
        ITEMS.register(bus)
        LOG.info("Prepared item registry.")
    }

    fun <T : Item> item(name: String, supplier: () -> T): RegistryObject<T> = ITEMS.register(name) { supplier() }
}
