package org.eln2.mc.common.content.modules

import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.RenderType
import net.minecraftforge.fluids.FluidType
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItem
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.content.fluid.FluidPipeBlock
import org.eln2.mc.common.content.fluid.FluidPipeBlockEntity
import org.eln2.mc.common.content.fluid.FluidPipeModuleItem
import org.eln2.mc.common.fluids.ForgeFluidRegistry
import org.eln2.mc.common.fluids.ForgeFluidRegistry.basicForgeFluid
import org.eln2.mc.common.items.ItemRegistry.item

object Eln2ForgeFluids : ContentModule() {
    private val renderLayerSetups = ArrayList<Pair<ForgeFluidRegistry.ForgeFluidRegistryItem, RenderType>>()

    fun ForgeFluidRegistry.ForgeFluidRegistryItem.withRenderLayer(layer: RenderType) {
        renderLayerSetups.add(Pair(this, layer))
    }

    override fun setRenderLayers() {
        renderLayerSetups.forEach { (fluid, renderLayer) ->
            ItemBlockRenderTypes.setRenderLayer(fluid.source.get(), renderLayer)
            ItemBlockRenderTypes.setRenderLayer(fluid.flowing.get(), renderLayer)
        }
    }

    //#region Pipe

    val FLUID_PIPE_BLOCK = blockAndItem("fluid_pipe", ::FluidPipeBlock)

    val FLUID_PIPE_BLOCK_ENTITY = blockEntityOnly(
        "fluid_pipe",
        FLUID_PIPE_BLOCK,
        ::FluidPipeBlockEntity
    )

    val FLUID_PIPE_PUMP_MODULE = item("fluid_pipe_pump_module", ::FluidPipeModuleItem)
    val FLUID_PIPE_GATED_PUMP_MODULE = item("fluid_pipe_gated_pump_module", ::FluidPipeModuleItem)

    //#endregion

    //#region Coal Products

    val COAL_TAR = basicForgeFluid("coal_tar") {
        tintColor = MyColor(255, 20, 20, 20) // Opaque Black
        properties {
            FluidType.Properties.create()
                .density(1180) // Sinks in water
                .viscosity(5000) // Thick
        }
    }

    val COAL_GAS = basicForgeFluid("coal_gas") {
        tintColor = MyColor(100, 200, 200, 200) // Translucent Grey Vapor
        properties {
            FluidType.Properties.create()
                .density(-1000) // Gas
                .viscosity(100)
        }
    }.withRenderLayer(RenderType.translucent())

    //#endregion

    //#region Oil Products

    val CRUDE_OIL = basicForgeFluid("crude_oil") {
        tintColor = MyColor(255, 30, 20, 10) // Opaque Dark Brown
        properties {
            FluidType.Properties.create()
                .density(850)
                .viscosity(2000)
        }
    }

    val NAPHTHA_GAS = basicForgeFluid("naphtha_gas") {
        tintColor = MyColor(100, 200, 240, 255) // Faint Blue Vapor
            properties {
            FluidType.Properties.create()
                .density(-1000)
                .viscosity(100)
        }
    }.withRenderLayer(RenderType.translucent())

    val NAPHTHA = basicForgeFluid("naphtha") {
        tintColor = MyColor(200, 255, 255, 220) // Transparent Pale Yellow
        properties {
            FluidType.Properties.create()
                .density(700)
                .viscosity(800)
        }
    }.withRenderLayer(RenderType.translucent())

    val HEAVY_OIL = basicForgeFluid("heavy_oil") {
        tintColor = MyColor(255, 40, 20, 10) // Opaque Deep Brown
        properties {
            FluidType.Properties.create()
                .density(950)
                .viscosity(4000)
        }
    }

    val DIESEL_GAS = basicForgeFluid("diesel_gas") {
        tintColor = MyColor(100, 220, 200, 150) // Faint Amber Vapor
        properties {
            FluidType.Properties.create()
                .density(-500)
                .viscosity(200)
        }
    }.withRenderLayer(RenderType.translucent())

    val DIESEL = basicForgeFluid("diesel") {
        tintColor = MyColor(200, 255, 200, 0) // Transparent Gold
        properties {
            FluidType.Properties.create()
                .density(830)
                .viscosity(1500)
        }
    }.withRenderLayer(RenderType.translucent())

    val BITUMEN = basicForgeFluid("bitumen") {
        tintColor = MyColor(255, 10, 10, 10) // Opaque Black
        properties {
            FluidType.Properties.create()
                .density(1200) // Sinks
                .viscosity(10000) // Very thick
        }
    }

    //#endregion
}
