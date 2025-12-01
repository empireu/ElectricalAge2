package org.eln2.mc.common.fluids.foundation

import com.mojang.blaze3d.shaders.FogShape
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.FogRenderer
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions
import net.minecraftforge.fluids.FluidType
import org.eln2.mc.client.render.foundation.MyColor
import org.joml.Vector2d
import org.joml.Vector3f
import java.util.function.Consumer

data class BasicForgeFluidTypeClientOptions(
    val stillTexture: ResourceLocation?,
    val flowingTexture: ResourceLocation?,
    val overlayTexture: ResourceLocation?,
    val tintColor: MyColor?,
    val fogColor: Vector3f?,
    val fog: Vector2d? = null
)

class BasicForgeFluidType(val clientOptions: BasicForgeFluidTypeClientOptions, properties: Properties) : FluidType(properties) {
    override fun initializeClient(consumer: Consumer<IClientFluidTypeExtensions>) {
        consumer.accept(object : IClientFluidTypeExtensions {
            override fun getStillTexture() = clientOptions.stillTexture ?: super.getStillTexture()
            override fun getFlowingTexture() = clientOptions.flowingTexture ?: super.getFlowingTexture()
            override fun getOverlayTexture() = clientOptions.overlayTexture ?: super.getOverlayTexture()
            override fun getTintColor() = clientOptions.tintColor?.data ?: super.getTintColor()

            override fun modifyFogColor(
                camera: Camera?,
                partialTick: Float,
                level: ClientLevel?,
                renderDistance: Int,
                darkenWorldAmount: Float,
                fluidFogColor: Vector3f?
            ): Vector3f {
                return clientOptions.fogColor ?: super.modifyFogColor(
                    camera,
                    partialTick,
                    level,
                    renderDistance,
                    darkenWorldAmount,
                    fluidFogColor
                )
            }

            override fun modifyFogRender(
                camera: Camera?,
                mode: FogRenderer.FogMode?,
                renderDistance: Float,
                partialTick: Float,
                nearDistance: Float,
                farDistance: Float,
                shape: FogShape?
            ) {
                if(clientOptions.fog != null) {
                    RenderSystem.setShaderFogStart(clientOptions.fog.x.toFloat())
                    RenderSystem.setShaderFogEnd(clientOptions.fog.y.toFloat())
                }
            }
        })
    }
}
