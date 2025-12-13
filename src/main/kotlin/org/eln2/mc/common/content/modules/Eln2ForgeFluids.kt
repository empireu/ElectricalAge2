package org.eln2.mc.common.content.modules

import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.RenderType
import net.minecraftforge.fluids.FluidType
import org.eln2.mc.common.fluids.ForgeFluidRegistry.basicForgeFluid
import org.eln2.mc.resource

object Eln2ForgeFluids : ContentModule() {
    override fun setRenderLayers() {
        ItemBlockRenderTypes.setRenderLayer(COAL_TAR.source.get(), RenderType.translucent())
        ItemBlockRenderTypes.setRenderLayer(COAL_TAR.flowing.get(), RenderType.translucent())
    }

    val COAL_TAR = basicForgeFluid("coal_tar") {
        stillTexture = resource("block/fluid/coal_tar_still")
        flowingTexture = resource("block/fluid/coal_tar_flowing")
    }

    val COAL_GAS = basicForgeFluid("coal_gas") {
        stillTexture = resource("block/fluid/coal_tar_still")
        flowingTexture = resource("block/fluid/coal_tar_flowing")

        properties {
            FluidType.Properties.create()
                .density(-1000)
        }
    }
}
