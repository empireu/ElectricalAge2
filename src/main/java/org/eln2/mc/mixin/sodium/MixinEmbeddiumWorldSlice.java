package org.eln2.mc.mixin.sodium;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.eln2.mc.common.blocks.foundation.GhostLightHackClient;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/*
* We should also try Oculus compat at some point (if there's interest in the mod).
* I've tested it and (apart from all the flywheel-rendered stuff disappearing) the lights worked fine, but looked kind of dull with shaders (looked ~exactly like they did before applying shaders).
*/

@Mixin(WorldSlice.class)
public abstract class MixinEmbeddiumWorldSlice {
    @Shadow
    @Final
    public ClientLevel world;

    @Unique
    private void ELN2$worldSliceLightOverrideHelper(CallbackInfoReturnable<Integer> cir, BlockPos pBlockPos) {
        ClientLevel level = this.world;

        if(level != null && level == Minecraft.getInstance().level) {
            cir.setReturnValue(Math.max(cir.getReturnValueI(), GhostLightHackClient.getBlockBrightness(pBlockPos)));
        }
    }

    @Inject(
        at = @At("RETURN"),
        method = {"getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I"},
        cancellable = true
    )
    public void injectInLayer(LightLayer type, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        ELN2$worldSliceLightOverrideHelper(cir, pos);
    }

    @Inject(
        at = @At("RETURN"),
        method = {"getRawBrightness(Lnet/minecraft/core/BlockPos;I)I"},
        cancellable = true
    )
    public void injectRaw(BlockPos pos, int ambientDarkness, CallbackInfoReturnable<Integer> cir) {
        ELN2$worldSliceLightOverrideHelper(cir, pos);
    }
}
