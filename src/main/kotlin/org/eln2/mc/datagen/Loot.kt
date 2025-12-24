package org.eln2.mc.datagen

import net.minecraft.data.loot.BlockLootSubProvider
import net.minecraft.world.flag.FeatureFlags
import org.eln2.mc.LOG
import org.eln2.mc.common.blocks.BlockRegistry

class Eln2BlockLoot : BlockLootSubProvider(emptySet(), FeatureFlags.REGISTRY.allFlags()) {
    override fun generate() {
        BlockRegistry.LOOT_TABLE_BLOCKS.forEach { obj ->
            val block = obj.get()
            this.dropSelf(block)
            LOG.debug("Registered drop for {}", block)
        }
    }

    override fun getKnownBlocks() = BlockRegistry.LOOT_TABLE_BLOCKS.map { it.get() }
}
