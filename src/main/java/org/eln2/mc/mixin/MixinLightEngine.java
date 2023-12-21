package org.eln2.mc.mixin;

import kotlin.jvm.functions.Function1;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.eln2.mc.Eln2Kt;
import org.eln2.mc.common.GhostLightHackClient;
import org.eln2.mc.common.GhostLightServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightEngine.class)
public abstract class MixinLightEngine {
    @Final
    @Shadow
    protected LightChunkGetter chunkSource;

    @Unique
    private Function1<BlockPos, Integer> ELN2$reader = MixinLightEngine::ELN2$emptyGetter;

    @Inject(
        at = @At("RETURN"),
        method = {"<init>(Lnet/minecraft/world/level/chunk/LightChunkGetter;Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;)V"}
    )
    private void initializeReader(CallbackInfo ci) {
        ELN2$reader = ELN2$createReader(
            (LightEngine<?, ?>) ((Object)this),
            chunkSource
        );

        Eln2Kt.getLOG().info("ELN2 Injected Light Reader in " + this + " as " + ELN2$reader);
    }

    @Unique
    private static Function1<BlockPos, Integer> ELN2$createReader(LightEngine<?, ?> self, LightChunkGetter chunkSource) {
        if(!(self instanceof BlockLightEngine)) {
            return MixinLightEngine::ELN2$emptyGetter;
        }

        BlockGetter blockAccessor = chunkSource.getLevel();

        if(!(blockAccessor instanceof Level level)) {
            Eln2Kt.getLOG().fatal("Unknown blockAccessor " + blockAccessor);
            return MixinLightEngine::ELN2$emptyGetter;
        }

        if(level.isClientSide) {
            return GhostLightHackClient::getBlockBrightness;
        }
        else if(level instanceof ServerLevel) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

            if(server != null && server.isSameThread()) {
                return GhostLightServer.createReader(level);
            }
            else {
                Eln2Kt.getLOG().fatal("Invalid server " + server + " or thread " + Thread.currentThread());
            }
        }
        else {
            Eln2Kt.getLOG().fatal("Invalid level " + level);
        }

        return MixinLightEngine::ELN2$emptyGetter;
    }

    @Unique
    private static int ELN2$emptyGetter(BlockPos blockPos) {
        return 0;
    }

    @Inject(
        at = @At("RETURN"),
        method = {"getLightValue(Lnet/minecraft/core/BlockPos;)I"},
        cancellable = true
    )
    public void getLightValue(BlockPos pBlockPos, CallbackInfoReturnable<Integer> cir) {
        int dwOverride = ELN2$reader.invoke(pBlockPos);

        if(dwOverride > cir.getReturnValue()) {
            cir.setReturnValue(dwOverride);
        }
    }
}
