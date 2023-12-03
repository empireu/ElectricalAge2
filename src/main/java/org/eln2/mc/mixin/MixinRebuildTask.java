package org.eln2.mc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ChunkBufferBuilderPack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import org.eln2.mc.common.content.GridRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Set;

@Mixin(ChunkRenderDispatcher.RenderChunk.RebuildTask.class)
public abstract class MixinRebuildTask {
    @Shadow(aliases = { "f_112859_" }) // https://mcp.thiakil.com/#/class/net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask
    @Final
    private ChunkRenderDispatcher.RenderChunk this$1;

    @Inject(
        method = { "compile" },
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Set;iterator()Ljava/util/Iterator;",
            remap = false
        ),
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    public void submitGrids(
        float pX,
        float pY,
        float pZ,
        ChunkBufferBuilderPack pChunkBufferBuilderPack,
        CallbackInfoReturnable<?> cir,
        net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk.RebuildTask.CompileResults pBuildResults,
        int pI,
        BlockPos pChunkMin,
        BlockPos pChunkMax,
        VisGraph pVisGraph,
        RenderChunkRegion pRenderChunkRegion,
        PoseStack pPoseStack,
        Set<RenderType> pRenderTypeSet
    ) {
        GridRenderer.submitForRenderSection(
            this$1,
            pChunkBufferBuilderPack,
            pRenderChunkRegion,
            pRenderTypeSet
        );
    }
}
