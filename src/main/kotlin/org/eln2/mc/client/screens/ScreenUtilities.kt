package org.eln2.mc.client.screens

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import org.ageseries.libage.mathematics.map
import org.eln2.mc.common.containers.MyAbstractContainerScreen

interface ProgressSupplierMenu {
    fun getProgressForRender() : Float
}

class BasicProgressScreen<Menu>(
    menu: Menu, playerInventory:
    Inventory, title: Component,
    val baseTexture: ResourceLocation,
    val progressTexture: ResourceLocation,
    val startX: Float,
    val endX: Float
) : MyAbstractContainerScreen<Menu>(menu, playerInventory, title) where Menu : AbstractContainerMenu, Menu : ProgressSupplierMenu {
    override fun renderBg(pGuiGraphics: GuiGraphics, pPartialTick: Float, pMouseX: Int, pMouseY: Int) {
        blitHelper(pGuiGraphics, baseTexture)

        pGuiGraphics.blit(
            progressTexture,
            leftPos, topPos,
            0.0f,
            0.0f,
            map(
                menu.getProgressForRender(),
                0f,
                1f,
                startX,
                endX
            ).toInt(),
            256,
            256,
            256
        )
    }
}
