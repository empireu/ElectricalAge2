package org.eln2.mc.mixin;

import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderRegionCache.class)
public abstract class MixinRenderRegionCache {
    @Inject(
        at = @At("HEAD"),
        method = { "isAllEmpty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;II[[Lnet/minecraft/client/renderer/chunk/RenderRegionCache$ChunkInfo;)Z" },
        cancellable = true
    )
    private static void flagGridSectionsAsNotEmpty(
        BlockPos pStart,
        BlockPos pEnd,
        int p_200473_,
        int p_200474_,
        RenderRegionCache.ChunkInfo[][] pArrInfo,
        CallbackInfoReturnable<Boolean> cir
    ) {
        // FIXME
        /*if(GridConnectionManagerClient.containsRangeVisual(pStart, pEnd)) {
            cir.setReturnValue(false);
        }*/
    }
}
