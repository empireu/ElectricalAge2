@file:Suppress("unused") // Because block variables here would be suggested for deletion.

package org.eln2.mc.common.items

import com.jozufozu.flywheel.util.Color
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import org.eln2.mc.client.render.DebugVisualizer
import org.eln2.mc.common.GridConnectionManagerClient
import org.eln2.mc.extensions.getViewLine
import java.util.function.Supplier

object ItemRegistry {
    val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID)!! // Yeah, if this fails blow up the game

    fun setup(bus: IEventBus) {
        ITEMS.register(bus)
        LOG.info("Prepared item registry.")
    }

    data class ItemRegistryItem(
        val name: String,
        val item: RegistryObject<Item>,
    ) : Supplier<Item> by item

    fun item(name: String, supplier: () -> Item): ItemRegistryItem {
        val item = ITEMS.register(name) { supplier() }
        return ItemRegistryItem(name, item)
    }

    val TEST = item("test") { TestItem() }

    class TestItem : Item(Properties()) {
        override fun use(
            pLevel: Level,
            pPlayer: Player,
            pUsedHand: InteractionHand
        ): InteractionResultHolder<ItemStack> {
            if(pLevel.isClientSide) {
                val line = pPlayer.getViewLine()

                val int = GridConnectionManagerClient.pick(line)

                if(int != null) {
                    DebugVisualizer.lineBox(
                        BoundingBox3d.fromCenterSize(line.evaluate(int.second.entry), 0.05),
                        color = Color(255, 0, 0, 255)
                    )

                    DebugVisualizer.lineBox(
                        BoundingBox3d.fromCenterSize(line.evaluate(int.second.exit), 0.05),
                        color = Color(0, 0, 255, 255)
                    )

                    DebugVisualizer.lineBox(
                        BoundingBox3d.Companion.fromCylinder(int.first.cylinder)
                    )
                }
            }

            return super.use(pLevel, pPlayer, pUsedHand)
        }
    }
}
