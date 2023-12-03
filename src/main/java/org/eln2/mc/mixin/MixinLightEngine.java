package org.eln2.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.eln2.mc.common.blocks.foundation.GhostLightHackClient;
import org.eln2.mc.common.blocks.foundation.GhostLightServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightEngine.class)
public abstract class MixinLightEngine {
    @Final
    @Shadow
    protected LightChunkGetter chunkSource;

    @Unique
    private int ELN2$lightOverrideHelper(BlockPos pBlockPos) {
        LightEngine<?, ?> self = (LightEngine<?, ?>) ((Object)this);

        if(!(self instanceof BlockLightEngine)) {
            return 0;
        }

        BlockGetter getter = chunkSource.getLevel();

        if(!(getter instanceof Level level)) {
            System.out.println("chunk source - not getter");
            return 0;
        }

        if(level.isClientSide) {
            return GhostLightHackClient.getBlockBrightness(pBlockPos);
        }
        else if(level instanceof ServerLevel) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

            if(server != null && server.isSameThread()) {
                return GhostLightServer.getBlockBrightness(level, pBlockPos);
            }
        }
        else {
            System.out.println("unknown: " + level);
        }

        return 0;
    }

    @Inject(
        at = @At("RETURN"),
        method = {"getLightValue(Lnet/minecraft/core/BlockPos;)I"},
        cancellable = true
    )
    public void getLightValue(BlockPos pBlockPos, CallbackInfoReturnable<Integer> cir) {
        int dwOverride = ELN2$lightOverrideHelper(pBlockPos);

        if(dwOverride > cir.getReturnValue()) {
            cir.setReturnValue(dwOverride);
        }
    }
}
