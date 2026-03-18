package org.eln2.mc.mixin.flywheel;


import dev.engine_room.flywheel.backend.engine.LightDataCollector;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightDataCollector.class)
public abstract class MixinLightDataCollector {
    @Inject(
        at = @At("HEAD"),
        method = {"createFastBlockDataGetter(Lnet/minecraft/world/level/lighting/LayerLightEventListener;)Lit/unimi/dsi/fastutil/longs/Long2ObjectFunction;"},
        cancellable = true,
        remap = false
    )
    private static void createFastBlockDataGetter(CallbackInfoReturnable<Long2ObjectFunction<DataLayer>> cir) {
        cir.setReturnValue(null);
    }
}
