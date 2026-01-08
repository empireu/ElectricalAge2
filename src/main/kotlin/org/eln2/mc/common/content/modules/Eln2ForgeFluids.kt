@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.level.material.Fluid
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.fluids.FluidType
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.FTL
import org.eln2.mc.client.render.foundation.DummyBlockEntityRendererProvider
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItem
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.content.fluid.*
import org.eln2.mc.common.content.modules.ContentManager.withSelfDrop
import org.eln2.mc.common.fluids.ForgeFluidRegistry
import org.eln2.mc.common.fluids.ForgeFluidRegistry.basicForgeFluid
import org.eln2.mc.common.items.ItemRegistry
import org.eln2.mc.common.items.ItemRegistry.item

object Eln2ForgeFluids : ContentModule() {
    //#region Registration Helpers

    /**
     * Deferred information for setting render layers in [setRenderLayers].
     * */
    private val renderLayerSetups = ArrayList<Pair<ForgeFluidRegistry.ForgeFluidRegistryItem, RenderType>>()

    /**
     * Sets the render layer for the source and the flowing blocks of the fluid.
     * */
    fun ForgeFluidRegistry.BasicForgeFluidBuilder.withRenderLayer(layer: RenderType) {
        ContentManager.requireInit()

        this.onRegister {
            renderLayerSetups.add(Pair(it, layer))
        }
    }

    /**
     * Read by [org.eln2.mc.Eln2ItemModelProviderDatagen] and [org.eln2.mc.common.ModEvents.registerItemColors].
     * */
    val CHEMICAL_BOTTLES_FOR_RESOLVE_AND_DATAGEN = LinkedHashMap<ForgeFluidRegistry.ForgeFluidRegistryItem, ChemicalBottleRegistryItem>()

    data class ChemicalBottleRegistryItem(
        val fluidRegistryItem: ForgeFluidRegistry.ForgeFluidRegistryItem,
        val tintColor: MyColor,
        val bottleItem: RegistryObject<ChemicalBottleItem>
    )

    /**
     * Registers a [ChemicalBottleItem] for the ELN2 fluid.
     * */
    fun ForgeFluidRegistry.BasicForgeFluidBuilder.withChemicalBottle() {
        ContentManager.requireInit()

        val tint = requireNotNull(this.tintColor) {
            DEBUGGER_BREAK("Bottle requires tint color")
        }

        this.onRegister {
            val bottle = ItemRegistry.item("${it.id}_bottle") {
                ChemicalBottleItem(it)
            }

            CHEMICAL_BOTTLES_FOR_RESOLVE_AND_DATAGEN.putUnique(it, ChemicalBottleRegistryItem(it, tint, bottle)) {
                DEBUGGER_BREAK("Duplicate chemical bottle for $this")
            }
        }
    }

    /**
     * Returns the chemical bottle for the [fluid] or null, if one isn't registered.
     * This is a slow operation.
     * */
    fun getChemicalBottle(fluid: Fluid) : ChemicalBottleItem? {
        val entry = CHEMICAL_BOTTLES_FOR_RESOLVE_AND_DATAGEN.entries.firstOrNull {
            it.key.get() == fluid
        } ?: return null

        return entry.value.bottleItem.get()
    }

    fun getChemicalBottle(fluid: ForgeFluidRegistry.ForgeFluidRegistryItem) = CHEMICAL_BOTTLES_FOR_RESOLVE_AND_DATAGEN[fluid]

    fun ForgeFluidRegistry.ForgeFluidRegistryItem.requireBottle() = getChemicalBottle(this) ?: FTL("Chemical bottle doesn't exist for $this")

    //#endregion

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

    /**
     * Registers the render layers from [renderLayerSetups].
     * */
    override fun setRenderLayers() {
        renderLayerSetups.forEach { (fluid, renderLayer) ->
            ItemBlockRenderTypes.setRenderLayer(fluid.source.get(), renderLayer)
            ItemBlockRenderTypes.setRenderLayer(fluid.flowing.get(), renderLayer)
        }
    }

    //#region Pipe

    val FLUID_PIPE_BLOCK = blockAndItem("fluid_pipe", ::FluidPipeBlock)
        .withSelfDrop()

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

        withRenderLayer(RenderType.translucent())
    }

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

        withRenderLayer(RenderType.translucent())
        withChemicalBottle()
    }

    val NAPHTHA_GAS = basicForgeFluid("naphtha_gas") {
        tintColor = MyColor(100, 200, 240, 255) // Faint Blue Vapor
        properties {
            FluidType.Properties.create()
                .density(-1000)
                .viscosity(100)
        }

        withRenderLayer(RenderType.translucent())
    }

    val CREOSOTE = basicForgeFluid("creosote") {
        tintColor = MyColor(200, 208, 218, 40)
        properties {
            FluidType.Properties.create()
                .density(1200)
                .viscosity(400)
        }

        withRenderLayer(RenderType.translucent())
        withChemicalBottle()
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

        withRenderLayer(RenderType.translucent())
    }

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

        withChemicalBottle()
    }

    val CRUDE_OIL = basicForgeFluid("crude_oil") {
        tintColor = MyColor(255, 30, 20, 10) // Opaque Dark Brown
        properties {
            FluidType.Properties.create()
                .density(850)
                .viscosity(2000)
        }

        withChemicalBottle()
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

        withRenderLayer(RenderType.translucent())
        withChemicalBottle()
    }

    val DIESEL_GAS = basicForgeFluid("diesel_gas") {
        tintColor = MyColor(100, 220, 200, 150) // Faint Amber Vapor
        properties {
            FluidType.Properties.create()
                .density(-500)
                .viscosity(200)
        }

        withRenderLayer(RenderType.translucent())
    }

    val BITUMEN = basicForgeFluid("bitumen") {
        tintColor = MyColor(255, 10, 10, 10) // Opaque Black
        properties {
            FluidType.Properties.create()
                .density(1200) // Sinks
                .viscosity(10000) // Very thick
        }

        withChemicalBottle()
    }

    val LIQUID_METHANE = basicForgeFluid("liquid_methane") {
        tintColor = MyColor(180, 100, 100, 255) // Blueish
        properties {
            FluidType.Properties.create()
                .density(420)
                .viscosity(500) // Very thin
        }

        withRenderLayer(RenderType.translucent())
        withChemicalBottle()
    }

    val METHANE = basicForgeFluid("methane") {
        tintColor = MyColor(100, 200, 200, 255) // Faint Blue gas
        properties {
            FluidType.Properties.create()
                .density(-500)
                .viscosity(100)
        }

        withRenderLayer(RenderType.translucent())
    }

    val LIQUID_PROPANE = basicForgeFluid("liquid_propane") {
        tintColor = MyColor(180, 255, 255, 255) // Clear/White
        properties {
            FluidType.Properties.create()
                .density(580)
                .viscosity(600)
        }

        withRenderLayer(RenderType.translucent())
        withChemicalBottle()
    }

    val PROPANE = basicForgeFluid("propane") {
        tintColor = MyColor(100, 255, 255, 255) // Faint White gas
        properties {
            FluidType.Properties.create()
                .density(-600)
                .viscosity(100)
        }

        withRenderLayer(RenderType.translucent())
    }

    val LIQUID_BUTANE = basicForgeFluid("liquid_butane") {
        tintColor = MyColor(180, 255, 240, 220) // Off-white
        properties {
            FluidType.Properties.create()
                .density(600)
                .viscosity(700)
        }

        withRenderLayer(RenderType.translucent())
        withChemicalBottle()
    }

    val BUTANE = basicForgeFluid("butane") {
        tintColor = MyColor(100, 255, 250, 230) // Faint Off-white
        properties {
            FluidType.Properties.create()
                .density(-600)
                .viscosity(100)
        }

        withRenderLayer(RenderType.translucent())
    }

    val LIQUID_HYDROGEN = basicForgeFluid("liquid_hydrogen") {
        tintColor = MyColor(180, 200, 180, 255) // Very faint purple/blue
        properties {
            FluidType.Properties.create()
                .density(70) // Extremely light liquid
                .viscosity(200)
        }

        withRenderLayer(RenderType.translucent())
        withChemicalBottle()
    }

    val HYDROGEN = basicForgeFluid("hydrogen") {
        tintColor = MyColor(80, 220, 200, 255) // Faint purple gas
        properties {
            FluidType.Properties.create()
                .density(-1000) // Rises fast
                .viscosity(50)
        }

        withRenderLayer(RenderType.translucent())
    }

    val LIQUID_NITROGEN = basicForgeFluid("liquid_nitrogen") {
        tintColor = MyColor(180, 200, 240, 255) // Cold Blue (Visual convention)
        properties {
            FluidType.Properties.create()
                .density(810)
                .viscosity(400)
        }

        withRenderLayer(RenderType.translucent())
        withChemicalBottle()
    }

    val NITROGEN = basicForgeFluid("nitrogen") {
        tintColor = MyColor(100, 220, 240, 255) // Faint Blue fog
        properties {
            FluidType.Properties.create()
                .density(-800)
                .viscosity(100)
        }

        withRenderLayer(RenderType.translucent())
    }

    val LIQUID_ACETYLENE = basicForgeFluid("liquid_acetylene") {
        tintColor = MyColor(180, 230, 230, 230) // Light Grey
        properties {
            FluidType.Properties.create()
                .density(620)
                .viscosity(500)
        }

        withRenderLayer(RenderType.translucent())
    }

    val ACETYLENE = basicForgeFluid("acetylene") {
        tintColor = MyColor(100, 230, 230, 230) // Faint Grey
        properties {
            FluidType.Properties.create()
                .density(-600)
                .viscosity(100)
        }

        withRenderLayer(RenderType.translucent())
    }

    val INSULATING_VARNISH = basicForgeFluid("insulating_varnish") {
        tintColor = MyColor(180, 28, 23, 23) // Opaque Black
        properties {
            FluidType.Properties.create()
                .density(1500)
                .viscosity(4000) // Thick
        }

        withRenderLayer(RenderType.translucent())
        withChemicalBottle()
    }
}
