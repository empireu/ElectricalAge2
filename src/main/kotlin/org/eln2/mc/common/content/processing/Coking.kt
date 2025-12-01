package org.eln2.mc.common.content.processing

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.client.extensions.common.IClientBlockExtensions
import org.eln2.mc.common.blocks.foundation.BigBlockRepresentativeBlockEntity
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateMap
import org.eln2.mc.common.blocks.foundation.ReplaceVanillaParticlesBlockExtension
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import java.util.function.Consumer

class CokeOvenBlock : HorizontalDirectionalBlock(Properties.of().noOcclusion()) {
    override fun initializeClient(consumer: Consumer<IClientBlockExtensions?>) {
        consumer.accept(ReplaceVanillaParticlesBlockExtension)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("true"))
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean {
        return true
    }
}

class CokeOvenBlockEntity(pPos: BlockPos, pState: BlockState) :
    BlockEntity(TODO(), pPos, pState),
    BigBlockRepresentativeBlockEntity<CokeOvenBlockEntity>,
    ComponentDisplay
{
    override val delegateMap: MultiblockDelegateMap
        get() = TODO("Not yet implemented")

    override fun submitDisplay(builder: ComponentDisplayList) {
        TODO("Not yet implemented")
    }
}
