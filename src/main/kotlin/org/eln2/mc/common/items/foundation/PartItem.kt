package org.eln2.mc.common.items.foundation

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.UseOnContext
import org.eln2.mc.LOG
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.parts.foundation.PartProvider
import org.eln2.mc.extensions.plus
import java.util.function.Supplier

/**
 * The Part Item delegates the placement of a Part to the Multipart Container.
 * */
open class PartItem(val partProvider: Lazy<PartProvider>) : BlockItem(BlockRegistry.MULTIPART_BLOCK.get(), Properties()) {
    // Patch for SpecItem
    // Items get registered before our specs, parts, etc so we don't have the registered part provider when the spec item gets registred
    // we add this lazy to mitigate, and sort of compensate by calling this ensureResolved in our finishing pass

    fun ensureResolved() {
        if(partProvider.isInitialized()) {
            return
        }

        LOG.debug("Resolved part item {}", partProvider.value)
    }

    constructor(provider: PartProvider) : this(lazy { provider }) {
        ensureResolved() // We were passed the item, we can do immediately
    }

    constructor(supplier: Supplier<PartProvider>) : this(lazy { supplier.get() })

    override fun useOn(pContext: UseOnContext): InteractionResult {
        val player = pContext.player

        if (player == null) {
            LOG.error("Null player!")
            return InteractionResult.FAIL
        }

        val level = pContext.level
        val substratePos = pContext.clickedPos
        val face = pContext.clickedFace

        val flag = MultipartBlockEntity.canPlacePartInSubstrate(
            level,
            substratePos,
            face,
            partProvider.value,
            player
        )

        if(!flag) {
            return InteractionResult.FAIL
        }

        val multipartPos = substratePos + face

        LOG.debug("Placing part at {}", multipartPos)

        var multipartBlockEntity = level.getBlockEntity(multipartPos)

        if (multipartBlockEntity == null) {
            if(level.isClientSide) {
                // Assume it works out
                return InteractionResult.SUCCESS
            }

            // Place multipart
            super.useOn(pContext)
            multipartBlockEntity = level.getBlockEntity(multipartPos)
        } else {
            multipartBlockEntity as MultipartBlockEntity

            if (multipartBlockEntity.placementCollides(player, face, partProvider.value)) {
                LOG.debug("Collides with part")
                return InteractionResult.FAIL
            }

            if(level.isClientSide) {
                return InteractionResult.SUCCESS
            }
        }

        check(!level.isClientSide)
        level as ServerLevel

        LOG.debug("Target multipart entity: {}", multipartBlockEntity)

        if (multipartBlockEntity == null) {
            LOG.error("Placed multipart is null") // Maybe an entity is standing or some other external thing? I'
            return InteractionResult.FAIL
        }

        val isPlaced = (multipartBlockEntity as MultipartBlockEntity).place(
            player,
            multipartPos,
            pContext.clickedFace,
            partProvider.value,
            pContext.itemInHand.tag
        )

        return if (isPlaced) InteractionResult.CONSUME
        else InteractionResult.FAIL
    }

    override fun getDescriptionId(): String {
        // By default, this uses the block's description ID.
        // This is not what we want.

        return orCreateDescriptionId
    }
}
