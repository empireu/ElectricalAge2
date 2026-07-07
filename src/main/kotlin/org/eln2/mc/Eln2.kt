@file:Suppress("unused")

package org.eln2.mc

import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.Resource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import org.ageseries.libage.utils.libageUseValidation
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.eln2.mc.client.input.KeyMappingRegistry
import org.eln2.mc.client.overlays.OverlayRegistry
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.FlwInstanceTypes
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.cells.CellRegistry
import org.eln2.mc.common.containers.ContainerRegistry
import org.eln2.mc.common.content.OscilloscopeCopyManager
import org.eln2.mc.common.content.OscilloscopeShader
import org.eln2.mc.common.content.ScrewdriverItem
import org.eln2.mc.client.dynamicLight.DynamicLightManager
import org.eln2.mc.common.content.fluid.ChemicalBottleItem
import org.eln2.mc.common.content.modules.ContentManager
import org.eln2.mc.common.entities.EntityRegistry
import org.eln2.mc.common.fluids.ForgeFluidRegistry
import org.eln2.mc.common.grids.TerminalHighlightRenderer
import org.eln2.mc.common.items.CreativeTabRegistry
import org.eln2.mc.common.items.ItemRegistry
import org.eln2.mc.common.network.Networking
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.recipes.RecipeRegistry
import org.eln2.mc.common.sounds.SoundRegistry
import org.eln2.mc.common.specs.SpecRegistry
import org.eln2.mc.common.specs.foundation.SpecContainerPart
import org.eln2.mc.common.specs.foundation.SpecPlacementOverlayClient
import org.eln2.mc.common.specs.foundation.SpecPreviewRenderer
import org.eln2.mc.integration.sodium.EmbeddiumCompat
import org.eln2.mc.integration.sodium.SodiumPlugin
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.io.path.Path

val LOG: Logger = LogManager.getLogger()

const val MODID = "eln2"

/**
 * Conversion factor between Forge Energy (FE/RF) and ELN2 joules.
 * FE is integer-valued, so this factor is chosen large enough to keep quantization error small for typical per-tick transfers while staying within [Int.MAX_VALUE] for reasonable cell capacities.
 * A 840 Wh lead-acid cell stores ~30M FE.
 * */
const val RF_PER_JOULE = 10

@Mod(MODID)
class Eln2 {
    init {
        libageUseValidation(ELN2_DEBUG)

        val context = ModLoadingContext.get()

        Eln2Config.registerSpecs(context)

        val modEventBus = FMLJavaModLoadingContext.get().modEventBus
        val forgeEventBus = MinecraftForge.EVENT_BUS

        BlockRegistry.setup(modEventBus)
        EntityRegistry.setup(modEventBus)
        ItemRegistry.setup(modEventBus)
        CreativeTabRegistry.setup(modEventBus)
        ContainerRegistry.setup(modEventBus)
        RecipeRegistry.setup(modEventBus)
        SoundRegistry.setup(modEventBus)
        ForgeFluidRegistry.setup(modEventBus)

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
            Runnable {
                clientSetup(forgeEventBus, modEventBus)
            }
        }

        Networking.setup()

        CellRegistry.setup(modEventBus)
        PartRegistry.setup(modEventBus)
        SpecRegistry.setup(modEventBus)
        ContentManager.initialize()

        LOG.info("Prepared registries.")
    }

    private fun clientSetup(forgeEventBus: IEventBus, modEventBus: IEventBus) {
        modEventBus.addListener { event: FMLClientSetupEvent ->
            event.enqueueWork {
                FlwInstanceTypes.init()
                FlwVisualizerRegistry.registerFoundationalVisualizers()
                ContentManager.registerBlockEntityVisualizers()
                ContentManager.registerPartVisualizers()
                ContentManager.registerSpecVisualizers()
                ContentManager.setupScreens()
                ContentManager.setRenderLayers()
            }
        }

        modEventBus.addListener(OverlayRegistry::register)
        modEventBus.addListener(KeyMappingRegistry::register)
        modEventBus.addListener(OscilloscopeShader::register)
        modEventBus.addListener(DynamicLightManager::register)

        forgeEventBus.addListener(DynamicLightManager::render)
        forgeEventBus.addListener(Eln2Config::registerClientCommands);

        forgeEventBus.addListener(EventPriority.LOWEST, SpecContainerPart::renderHighlightEvent)
        forgeEventBus.addListener(TerminalHighlightRenderer::render)
        forgeEventBus.addListener(SpecPlacementOverlayClient::onScroll)
        forgeEventBus.addListener(EventPriority.HIGHEST, ScrewdriverItem::onScroll)
        forgeEventBus.addListener(SpecPlacementOverlayClient::onKey)
        forgeEventBus.addListener(SpecPreviewRenderer::render)
        forgeEventBus.addListener(OscilloscopeCopyManager::execute)

        if(SodiumPlugin.shouldApply()) {
            forgeEventBus.addListener(EmbeddiumCompat::`eln2GridRenderer$handleEvent`)
        }

        forgeEventBus.addListener(EventPriority.LOWEST, ChemicalBottleItem::onRightClickBlockEvent)

        FlwModels.initialize()

        LOG.info("Prepared client-side")
    }
}

/**
 * Gets a [ResourceLocation] with ELN2's modid.
 * */
fun resource(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MODID, path)

/**
 * Gets the [Resource] at the specified [location]. If it does not exist, an exception is thrown.
 * */
fun getResource(location: ResourceLocation): Resource = Minecraft.getInstance().resourceManager.getResource(location).orElseThrow()

/**
 * Opens an [InputStream] for the [Resource] at the specified [location]. If it does not exist, an exception is thrown.
 * */
fun getResourceStream(location: ResourceLocation): InputStream = getResource(location).open()

/**
 * Gets a byte array that contains the content from the [Resource] at the specified [location].
 * */
fun getResourceBinary(location: ResourceLocation) : ByteArray {
    val stream = getResourceStream(location)
    val result = stream.readAllBytes()
    stream.close()

    return result
}

/**
 * Gets a string that contains the content from the [Resource] at the specified [location].
 * */
fun getResourceString(location: ResourceLocation, charset: Charset = Charset.defaultCharset()) : String =
    getResourceBinary(location).toString(charset)

val ELN2_DEBUG get() = true
val ELN2_LOG_STATS get() = false

fun getResourceStringHelper(resource: String) : String =
    if (!SharedConstants.IS_RUNNING_IN_IDE) getResourceString(resource(resource))
    else Files.readString(Path("./src/main/resources/assets/eln2/$resource"))

fun getResourceBinaryHelper(resource: String): ByteArray =
    if (!SharedConstants.IS_RUNNING_IN_IDE) getResourceBinary(resource(resource))
    else Files.readAllBytes(Path("./src/main/resources/assets/eln2/$resource"))
