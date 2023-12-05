package org.eln2.mc.integration.sodium;


import com.google.common.base.Suppliers;
import net.minecraftforge.fml.loading.LoadingModList;
import org.eln2.mc.Eln2Kt;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

// Taken from Flywheel:
public class SodiumPlugin implements IMixinConfigPlugin {
    private static final Supplier<Boolean> IS_SODIUM_LOADED = Suppliers.memoize(() ->
        LoadingModList.get().getModFileById("rubidium") != null
    );

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        Eln2Kt.getLOG().warn("ELN2 Sodium compat: " + IS_SODIUM_LOADED.get());
        return IS_SODIUM_LOADED.get();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}

