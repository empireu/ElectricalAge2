package org.eln2.mc.mixin;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.eln2.mc.common.GridCollisions;
import org.eln2.mc.common.items.foundation.PartItem;
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

        if(GridCollisions.intersectsPlacementBlock($this)) {
            cir.setReturnValue(false);
        }
    }
}
