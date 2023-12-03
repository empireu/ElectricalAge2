package org.eln2.mc.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.eln2.mc.common.content.GridConnectionManagerClient;
import org.eln2.mc.common.content.GridConnectionManagerServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockPlaceContext.class)
public abstract class MixinBlockPlaceContext {
    @Inject(
        at = @At("HEAD"),
        method = {"canPlace()Z"},
        cancellable = true
    )
    public void checkCanPlaceClipsGrid(CallbackInfoReturnable<Boolean> cir) {
        BlockPlaceContext $this = (BlockPlaceContext) ((Object)this);

        Level level = $this.getLevel();

        if(level.isClientSide) {
            if(GridConnectionManagerClient.clipsBlock($this.getClickedPos())) {
                cir.setReturnValue(false);
            }
        }
        else {
            if(GridConnectionManagerServer.clipsBlock((ServerLevel) level, $this.getClickedPos())) {
                cir.setReturnValue(false);
            }
        }
    }
}
