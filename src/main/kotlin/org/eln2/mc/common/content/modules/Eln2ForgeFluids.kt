package org.eln2.mc.common.content.modules

import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.RenderType
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.fluids.FluidType
import org.eln2.mc.client.render.foundation.DummyBlockEntityRendererProvider
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItemAndDrop
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.content.fluid.FluidPipeBlock
import org.eln2.mc.common.content.fluid.FluidPipeBlockEntity
import org.eln2.mc.common.content.fluid.FluidPipeBlockEntityVisual
import org.eln2.mc.common.content.fluid.FluidPipeModuleItem
import org.eln2.mc.common.fluids.ForgeFluidRegistry
import org.eln2.mc.common.fluids.ForgeFluidRegistry.basicForgeFluid
import org.eln2.mc.common.items.ItemRegistry.item

object Eln2ForgeFluids : ContentModule() {
    override fun registerBlockEntityVisualizers() {
        VisualizerRegistry.setVisualizer(
            FLUID_PIPE_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer(::FluidPipeBlockEntityVisual) { false }
        )
    }

    override fun registerBlockEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(
            FLUID_PIPE_BLOCK_ENTITY.get(),
            DummyBlockEntityRendererProvider(true)
        )
    }

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

    val FLUID_PIPE_BLOCK = blockAndItemAndDrop("fluid_pipe", ::FluidPipeBlock)

    val FLUID_PIPE_BLOCK_ENTITY = blockEntityOnly(
        "fluid_pipe",
        FLUID_PIPE_BLOCK,
        ::FluidPipeBlockEntity
    )

    val FLUID_PIPE_EXTRACTION_VALVE = item("fluid_pipe_extraction_valve", ::FluidPipeModuleItem)
    val FLUID_PIPE_INSERTION_VALVE = item("fluid_pipe_insertion_valve", ::FluidPipeModuleItem)

    //#endregion

    val STEAM = basicForgeFluid("steam") {
        tintColor = MyColor(150, 240, 240, 240) // Translucent White
        properties {
            FluidType.Properties.create()
                .density(-1000) // Gas
                .viscosity(100)
        }
    }.withRenderLayer(RenderType.translucent())

    val FUEL_GAS = basicForgeFluid("fuel_gas") {
        tintColor = MyColor(50, 200, 200, 200) // Translucent Grey Vapor
        properties {
            FluidType.Properties.create()
                .density(-100)
                .viscosity(50)
        }
    }

    val NAPHTHA = basicForgeFluid("naphtha") {
        tintColor = MyColor(200, 255, 255, 220) // Transparent Pale Yellow
        properties {
            FluidType.Properties.create()
                .density(700)
                .viscosity(800)
        }
    }.withRenderLayer(RenderType.translucent())

    val NAPHTHA_GAS = basicForgeFluid("naphtha_gas") {
        tintColor = MyColor(100, 200, 240, 255) // Faint Blue Vapor
        properties {
            FluidType.Properties.create()
                .density(-1000)
                .viscosity(100)
        }
    }.withRenderLayer(RenderType.translucent())

    val CREOSOTE = basicForgeFluid("creosote") {
        tintColor = MyColor(200, 208, 218, 40)
        properties {
            FluidType.Properties.create()
                .density(1200)
                .viscosity(400)
        }
    }

    val CREOSOTE_GAS = basicForgeFluid("creosote_gas") {
        tintColor = MyColor(100, 208, 218, 40)
        FluidType.Properties.create()
            .density(-600)
            .viscosity(100)
    }

    val COKE_GAS = basicForgeFluid("coke_gas") {
        tintColor = MyColor(150, 216, 250, 8) // Yellow
        properties {
            FluidType.Properties.create()
                .density(-500) // Gas
                .viscosity(100)
        }
    }.withRenderLayer(RenderType.translucent())

    val COAL_TAR = basicForgeFluid("coal_tar") {
        tintColor = MyColor(255, 20, 20, 20) // Opaque Black
        properties {
            FluidType.Properties.create()
                .density(1180) // Sinks in water
                .viscosity(5000) // Thick
        }
    }

    val HEAVY_COAL_TAR = basicForgeFluid("heavy_coal_tar") {
        tintColor = MyColor(255, 10, 10, 10) // Opaque Black
        properties {
            FluidType.Properties.create()
                .density(1510) // Sinks in water
                .viscosity(5500) // Thick
        }
    }

    val PITCH = basicForgeFluid("pitch") {
        tintColor = MyColor(255, 5, 5, 5) // Opaque Black
        properties {
            FluidType.Properties.create()
                .density(2000) // Sinks in water
                .viscosity(6000) // Thick
        }
    }

    val CRUDE_OIL = basicForgeFluid("crude_oil") {
        tintColor = MyColor(255, 30, 20, 10) // Opaque Dark Brown
        properties {
            FluidType.Properties.create()
                .density(850)
                .viscosity(2000)
        }
    }

    val HEAVY_OIL = basicForgeFluid("heavy_oil") {
        tintColor = MyColor(255, 40, 20, 10) // Opaque Deep Brown
        properties {
            FluidType.Properties.create()
                .density(950)
                .viscosity(4000)
        }
    }

    val DIESEL = basicForgeFluid("diesel") {
        tintColor = MyColor(200, 255, 200, 0) // Transparent Gold
        properties {
            FluidType.Properties.create()
                .density(830)
                .viscosity(1500)
        }
    }.withRenderLayer(RenderType.translucent())

    val DIESEL_GAS = basicForgeFluid("diesel_gas") {
        tintColor = MyColor(100, 220, 200, 150) // Faint Amber Vapor
        properties {
            FluidType.Properties.create()
                .density(-500)
                .viscosity(200)
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

    val LIQUID_METHANE = basicForgeFluid("liquid_methane") {
        tintColor = MyColor(180, 100, 100, 255) // Blueish
        properties {
            FluidType.Properties.create()
                .density(420)
                .viscosity(500) // Very thin
        }
    }.withRenderLayer(RenderType.translucent())

    val METHANE = basicForgeFluid("methane") {
        tintColor = MyColor(100, 200, 200, 255) // Faint Blue gas
        properties {
            FluidType.Properties.create()
                .density(-500)
                .viscosity(100)
        }
    }.withRenderLayer(RenderType.translucent())

    val LIQUID_PROPANE = basicForgeFluid("liquid_propane") {
        tintColor = MyColor(180, 255, 255, 255) // Clear/White
        properties {
            FluidType.Properties.create()
                .density(580)
                .viscosity(600)
        }
    }.withRenderLayer(RenderType.translucent())

    val PROPANE = basicForgeFluid("propane") {
        tintColor = MyColor(100, 255, 255, 255) // Faint White gas
        properties {
            FluidType.Properties.create()
                .density(-600)
                .viscosity(100)
        }
    }.withRenderLayer(RenderType.translucent())

    val LIQUID_BUTANE = basicForgeFluid("liquid_butane") {
        tintColor = MyColor(180, 255, 240, 220) // Off-white
        properties {
            FluidType.Properties.create()
                .density(600)
                .viscosity(700)
        }
    }.withRenderLayer(RenderType.translucent())

    val BUTANE = basicForgeFluid("butane") {
        tintColor = MyColor(100, 255, 250, 230) // Faint Off-white
        properties {
            FluidType.Properties.create()
                .density(-600)
                .viscosity(100)
        }
    }.withRenderLayer(RenderType.translucent())

    val LIQUID_HYDROGEN = basicForgeFluid("liquid_hydrogen") {
        tintColor = MyColor(180, 200, 180, 255) // Very faint purple/blue
        properties {
            FluidType.Properties.create()
                .density(70) // Extremely light liquid
                .viscosity(200)
        }
    }.withRenderLayer(RenderType.translucent())

    val HYDROGEN = basicForgeFluid("hydrogen") {
        tintColor = MyColor(80, 220, 200, 255) // Faint purple gas
        properties {
            FluidType.Properties.create()
                .density(-1000) // Rises fast
                .viscosity(50)
        }
    }.withRenderLayer(RenderType.translucent())

    val LIQUID_NITROGEN = basicForgeFluid("liquid_nitrogen") {
        tintColor = MyColor(180, 200, 240, 255) // Cold Blue (Visual convention)
        properties {
            FluidType.Properties.create()
                .density(810)
                .viscosity(400)
        }
    }.withRenderLayer(RenderType.translucent())

    val NITROGEN = basicForgeFluid("nitrogen") {
        tintColor = MyColor(100, 220, 240, 255) // Faint Blue fog
        properties {
            FluidType.Properties.create()
                .density(-800)
                .viscosity(100)
        }
    }.withRenderLayer(RenderType.translucent())

    val LIQUID_ACETYLENE = basicForgeFluid("liquid_acetylene") {
        tintColor = MyColor(180, 230, 230, 230) // Light Grey
        properties {
            FluidType.Properties.create()
                .density(620)
                .viscosity(500)
        }
    }.withRenderLayer(RenderType.translucent())

    val ACETYLENE = basicForgeFluid("acetylene") {
        tintColor = MyColor(100, 230, 230, 230) // Faint Grey
        properties {
            FluidType.Properties.create()
                .density(-600)
                .viscosity(100)
        }
    }.withRenderLayer(RenderType.translucent())
}
