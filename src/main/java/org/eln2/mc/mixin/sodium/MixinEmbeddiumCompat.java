package org.eln2.mc.mixin.sodium;

import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.SectionPos;
import net.minecraftforge.common.MinecraftForge;
import org.eln2.mc.client.render.foundation.CachingLightReader;
import org.eln2.mc.client.render.foundation.NeighborLightReader;
import org.eln2.mc.common.grids.GridConnectionManagerClient;
import org.eln2.mc.common.grids.GridRenderer;
import org.embeddedt.embeddium.api.ChunkMeshEvent;
import org.embeddedt.embeddium.api.MeshAppender;
import org.embeddedt.embeddium.compat.EmbeddiumCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EmbeddiumCompat.class)
public abstract class MixinEmbeddiumCompat {
    @Inject(
        at = @At("RETURN"),
        method = { "init()V" },
        remap = false
    )
    private static void injectGridMeshAppenderCallback(CallbackInfo cir) {
        MinecraftForge.EVENT_BUS.addListener(MixinEmbeddiumCompat::eln2GridRenderer$handleEvent);
    }

    @Unique
    private static void eln2GridRenderer$handleEvent(ChunkMeshEvent event) {
        if(GridConnectionManagerClient.containsRangeVisual(event.getSectionOrigin())) {
            event.addMeshAppender(MixinEmbeddiumCompat::eln2GridRenderer$append);
        }
    }

    @Unique
    private static void eln2GridRenderer$append(MeshAppender.Context appender) {
        RenderType type = RenderType.solid();

        ChunkMeshBufferBuilder builder = appender.sodiumBuildBuffers().get(DefaultMaterials.forRenderLayer(type)).getVertexBuffer(ModelQuadFacing.UNASSIGNED);

        CachingLightReader lightReader = new CachingLightReader(appender.blockRenderView());
        NeighborLightReader neighborLights = new NeighborLightReader(lightReader);
        SectionPos section = appender.sectionOrigin();

        ChunkVertexEncoder.Vertex[] pPushArray = new ChunkVertexEncoder.Vertex[1];

        GridRenderer.submitSection(section, lightReader, neighborLights, (pX, pY, pZ, pRed, pGreen, pBlue, pTexU, pTexV, pOverlayUV, pLightmapUV, pNormalX, pNormalY, pNormalZ) -> {
            ChunkVertexEncoder.Vertex sodiumVertex = new ChunkVertexEncoder.Vertex();
            sodiumVertex.x = pX;
            sodiumVertex.y = pY;
            sodiumVertex.z = pZ;

            sodiumVertex.color = ColorABGR.pack(
                (int)(pRed * 255),
                (int)(pGreen * 255),
                (int)(pBlue * 255),
                255
            );

            sodiumVertex.u = pTexU;
            sodiumVertex.v = pTexV;
            sodiumVertex.light = pLightmapUV;
            pPushArray[0] = sodiumVertex;
            builder.push(pPushArray, DefaultMaterials.SOLID);
        });
    }
}
