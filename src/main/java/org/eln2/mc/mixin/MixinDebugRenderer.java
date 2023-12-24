package org.eln2.mc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.eln2.mc.client.render.DebugVisualizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugRenderer.class)
public abstract class MixinDebugRenderer {
    @Inject(
        at = @At("RETURN"),
        method = {"render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V"}
    )
    public void injectEln2DebugRendererRender(
        PoseStack pPoseStack,
        MultiBufferSource.BufferSource pBufferSource,
        double pCamX,
        double pCamY,
        double pCamZ,
        CallbackInfo ci
    ) {
        DebugVisualizer.render(pPoseStack, pBufferSource, pCamX, pCamY, pCamZ);
    }
}
