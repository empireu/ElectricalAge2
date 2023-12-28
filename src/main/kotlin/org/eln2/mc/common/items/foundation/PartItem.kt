package org.eln2.mc.common.items.foundation

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import org.eln2.mc.LOG
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.content.GridConnectionManagerClient
import org.eln2.mc.common.content.GridConnectionManagerServer
import org.eln2.mc.common.parts.foundation.PartProvider
import org.eln2.mc.extensions.plus
import java.util.function.Supplier

/**
 * The Part Item delegates the placement of a Part to the Multipart Container.
 * */
open class PartItem(private val provider: Lazy<PartProvider>) : BlockItem(BlockRegistry.MULTIPART_BLOCK.get(), Properties()) {
    // Patch for SpecItem
    // Items get registered before our specs, parts, etc so we don't have the registered part provider when the spec item gets registred
    // we add this lazy to mitigate, and sort of compensate by calling this ensureResolved in our finishing pass

    fun ensureResolved() {
        if(provider.isInitialized()) {
            return
        }

        LOG.debug("Resolved part item {}", provider.value)
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
        val multipartPos = substratePos + face

        if (!MultipartBlockEntity.isValidSubstrateBlock(level, substratePos)) {
            LOG.debug("Non-valid substrate")
            return InteractionResult.FAIL
        }

        if(level.isClientSide) {
            if(GridConnectionManagerClient.clipsBlock(multipartPos)) {
                LOG.debug("Multipart clips grid")
                return InteractionResult.FAIL
            }
        }
        else {
            if(GridConnectionManagerServer.clipsBlock(level as ServerLevel, multipartPos)) {
                LOG.debug("Multipart clips grid")
                return InteractionResult.FAIL
            }
        }

        LOG.debug("Placing part at {}", multipartPos)

        var blockEntity = level.getBlockEntity(multipartPos)

        if (blockEntity == null) {
            // Need to place a multipart if the block is air:

            val state = level.getBlockState(multipartPos)

            if(!state.isAir) {
                if(!state.`is`(Blocks.WATER)) {
                    LOG.debug("Other block there")
                    return InteractionResult.FAIL
                }
                else {
                    LOG.debug("Waterlogging")
                }
            }

            LOG.debug("Placing new multipart and part")

            if(level.isClientSide) {
                // Assume it works out
                return InteractionResult.SUCCESS
            }

            // Place multipart
            super.useOn(pContext)

            blockEntity = level.getBlockEntity(multipartPos) // Checked under this block
        } else {
            LOG.debug("Existing block entity: {}", blockEntity)

            if (blockEntity !is MultipartBlockEntity) {
                LOG.debug("Non-multipart entity found!")
                return InteractionResult.FAIL
            }

            if(level.isClientSide) {
                return if (blockEntity.placementCollides(player, face, provider.value)) {
                    LOG.debug("Collides with part")
                    InteractionResult.FAIL
                } else {
                    LOG.debug("Does not collide")
                    InteractionResult.SUCCESS
                }
            }
        }

        check(!level.isClientSide)
        level as ServerLevel

        LOG.debug("Target multipart entity: {}", blockEntity)

        if (blockEntity == null) {
            LOG.error("Placed multipart is null") // Maybe an entity is standing or some other external thing?
            return InteractionResult.FAIL
        }

        val isPlaced = (blockEntity as MultipartBlockEntity).place(
            player,
            multipartPos,
            pContext.clickedFace,
            provider.value,
            pContext.itemInHand.tag
        )

        return if (isPlaced) {
            InteractionResult.CONSUME
        }
        else {
            InteractionResult.FAIL
        }
    }

    override fun getDescriptionId(): String {
        // By default, this uses the block's description ID.
        // This is not what we want.

        return orCreateDescriptionId
    }

// todo

    /*    override fun fillItemCategory(pGroup: CreativeModeTab, pItems: NonNullList<ItemStack>) {
            if (allowdedIn(pGroup)) {
                pItems.add(ItemStack(this))
            }
        }*/
}
