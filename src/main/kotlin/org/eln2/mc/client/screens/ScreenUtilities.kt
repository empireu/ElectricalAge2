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

/**
 * Very basic menu meant for a machine that shows one graphical indicator for progress.
 * The menu works in the following way:
 * - A "base" texture is drawn
 * - A slice of the "progress" texture that is almost identical to the "base" texture is drawn, but this texture has a progress arrow or some other element drawn, that the other texture doesn't have.
 * Progress is supplied by [ProgressSupplierMenu.getProgressForRender], which is from 0 to 1.
 * The slice is all the texture to the left of [startParameter] when the progress is 0, and all the texture to the left of [endParameter] when the progress is 1.
 * */
class BasicProgressScreen<Menu>(
    menu: Menu, playerInventory:
    Inventory, title: Component,
    val baseTexture: ResourceLocation,
    val progressTexture: ResourceLocation,
    val startParameter: Float,
    val endParameter: Float,
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
                startParameter,
                endParameter
            ).toInt(),
            256,
            256,
            256
        )
    }
}
