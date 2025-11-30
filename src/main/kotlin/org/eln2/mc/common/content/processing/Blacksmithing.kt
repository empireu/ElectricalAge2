package org.eln2.mc.common.content.processing

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.client.gui.overlay.ForgeGui
import net.minecraftforge.client.gui.overlay.IGuiOverlay
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.MODID
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.content.processing.BlacksmithingToolItem.Companion.DEFAULT_VARIANT
import org.eln2.mc.extensions.preserve
import org.eln2.mc.extensions.setSyncDirty
import org.joml.Quaternionf
import kotlin.math.PI

class BlacksmithingStationBlock : HorizontalDirectionalBlock(Properties.of().noOcclusion()), EntityBlock {
    init {
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH))
    }

    override fun getStateForPlacement(pContext: BlockPlaceContext): BlockState? {
        return super.defaultBlockState().setValue(FACING, pContext.horizontalDirection.opposite)
    }

    override fun createBlockStateDefinition(pBuilder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(pBuilder)
        pBuilder.add(FACING)
    }

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = BlacksmithingStationBlockEntity(pPos, pState)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun use(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult
    ): InteractionResult {
        if(pHand != InteractionHand.MAIN_HAND) {
            return InteractionResult.FAIL
        }

        val blockEntity = pLevel.getBlockEntity(pPos) as? BlacksmithingStationBlockEntity
            ?: return InteractionResult.FAIL

        if(pLevel.isClientSide) {
            return InteractionResult.SUCCESS
        }

        return blockEntity.playerInteraction(pPlayer)
    }

    //#region Collider

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getCollisionShape(
        pState: BlockState,
        pLevel: BlockGetter,
        pPos: BlockPos,
        pContext: CollisionContext,
    ): VoxelShape = collider

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getShape(
        pState: BlockState,
        pLevel: BlockGetter,
        pPos: BlockPos,
        pContext: CollisionContext,
    ): VoxelShape = collider

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getVisualShape(
        pState: BlockState,
        pLevel: BlockGetter,
        pPos: BlockPos,
        pContext: CollisionContext,
    ): VoxelShape = collider

    //#endregion

    companion object {
        private val collider = run {
            val size = Vector3d(12.0, 14.0, 12.0) / 32.0

            Shapes.box(
                0.5 - size.x, 0.5 - size.y, 0.5 - size.z,
                0.5 + size.x, 0.5 + size.y, 0.5 + size.z
            )
        }
    }
}

class BlacksmithingStationBlockEntity(pPos: BlockPos, pState: BlockState) : BlockEntity(Eln2Processing.BLACKSMITHING_STATION_BLOCK_ENTITY.get(), pPos, pState) {
    //#region Inventory

    val inventoryHandler = InventoryHandler(this)
    val inventoryHandlerLazy: LazyOptional<InventoryHandler> = LazyOptional.of { inventoryHandler }

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return inventoryHandlerLazy.cast()
        }

        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        inventoryHandlerLazy.invalidate()
    }

    class InventoryHandler(val blockEntity: BlacksmithingStationBlockEntity) : net.minecraftforge.items.ItemStackHandler(1) {
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
            return stack
        }

        override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
            return ItemStack.EMPTY // prevents automation from extracting input
        }

        override fun onContentsChanged(slot: Int) {
            super.onContentsChanged(slot)
            blockEntity.setChanged()
            blockEntity.setSyncDirty()
        }
    }

    //#endregion

    @ServerOnly
    private fun toolInteraction(player: Player) : InteractionResult {
        val toolStack = player.getItemInHand(InteractionHand.MAIN_HAND)

        return InteractionResult.FAIL
    }

    @ServerOnly
    fun playerInteraction(player: Player) : InteractionResult {
        val stackInHand = player.getItemInHand(InteractionHand.MAIN_HAND)

        /**
         * Checks if the player is trying to apply a recipe:
         * */
        if(stackInHand.item is BlacksmithingToolItem) {
            return toolInteraction(player)
        }

        val anvilItem = inventoryHandler.getStackInSlot(SLOT)

        /**
         * Tries to remove an item from the anvil:
         * */
        if(!anvilItem.isEmpty) {
            if(player.addItem(anvilItem)) {
                inventoryHandler.setStackInSlot(SLOT, ItemStack.EMPTY)
                return InteractionResult.SUCCESS
            }

            return InteractionResult.FAIL
        }

        /**
         * Tries to insert an item into the anvil:
         * */
        if(stackInHand.isEmpty) {
            return InteractionResult.FAIL
        }

        inventoryHandler.setStackInSlot(SLOT, stackInHand.copyWithCount(1))
        return InteractionResult.CONSUME
    }

    //#region Saving and Syncing

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)
        pTag.put(INVENTORY, inventoryHandler.serializeNBT())
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)
        inventoryHandler.deserializeNBT(pTag.getCompound(INVENTORY))
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        val tag = this.updateTag

        return ClientboundBlockEntityDataPacket.create(this) { tag }
    }

    override fun getUpdateTag(): CompoundTag {
        return inventoryHandler.serializeNBT()
    }

    override fun onDataPacket(net: Connection?, pkt: ClientboundBlockEntityDataPacket?) {
        val tag = pkt?.tag
            ?: return

        handleUpdateTag(tag)
    }

    override fun handleUpdateTag(tag: CompoundTag) {
        inventoryHandler.deserializeNBT(tag)
    }

    //#endregion

    companion object {
        const val SLOT = 0
        private const val INVENTORY = "inventory"
    }
}

class BlacksmithingStationBlockEntityRenderer(val itemRenderer: ItemRenderer) : BlockEntityRenderer<BlacksmithingStationBlockEntity> {
    override fun render(
        pBlockEntity: BlacksmithingStationBlockEntity,
        pPartialTick: Float,
        pPoseStack: PoseStack,
        pBuffer: MultiBufferSource,
        pPackedLight: Int,
        pPackedOverlay: Int,
    ) {
        val level = pBlockEntity.level
            ?: return

        val blockPos = pBlockEntity.blockPos
            ?: return

        val stack = pBlockEntity.inventoryHandler.getStackInSlot(BlacksmithingStationBlockEntity.SLOT)

        if(stack.isEmpty) {
            return
        }

        pPoseStack.preserve {
            pPoseStack.translate(0.5, 14.0 / 16.0 + 0.01, 0.5)
            pPoseStack.scale(0.5f, 0.5f, 0.5f)

            val itemBlockLight = level.getBrightness(LightLayer.BLOCK, blockPos.above())
            val itemSkyLight = level.getBrightness(LightLayer.SKY, blockPos.above())

            val itemLight = LightTexture.pack(itemBlockLight, itemSkyLight)

            val pose = pPoseStack.last()

            val rotation = Quaternionf().rotateX((PI / 2.0).toFloat())
            pose.pose().rotate(rotation)
            pose.normal().rotate(rotation)

            itemRenderer.renderStatic(
                stack,
                ItemDisplayContext.FIXED,
                itemLight,
                pPackedOverlay,
                pPoseStack,
                pBuffer,
                pBlockEntity.getLevel(),
                0
            )
        }
    }

    class Provider : BlockEntityRendererProvider<BlacksmithingStationBlockEntity> {
        override fun create(pContext: BlockEntityRendererProvider.Context): BlockEntityRenderer<BlacksmithingStationBlockEntity> {
            return BlacksmithingStationBlockEntityRenderer(pContext.itemRenderer)
        }
    }
}

/**
 * Item that has various modes (called variants) for use in the blacksmithing station.
 * The variants can be cycled on right-click and an overlay is also rendered to show the current variant.
 * @param extraVariants Variants to append to the [DEFAULT_VARIANT]. They must be unique.
 * */
class BlacksmithingToolItem(extraVariants: List<String>) : Item(Properties().stacksTo(1)) {
    val variants = listOf(DEFAULT_VARIANT).plus(extraVariants).toList().also {
        check(it.toSet().size == it.size) {
            "Duplicate variant names for blacksmithing tool!"
        }
    }

    /**
     * Gets the variant name serialized in the [stack]'s tag.
     * This name is guaranteed to be valid (contained in the [variants] set).
     * */
    fun getVariantName(stack: ItemStack) : String {
        val tag = stack.tag
            ?: return DEFAULT_VARIANT

        val serializedName = if(tag.contains(VARIANT)) {
            tag.getString(VARIANT)
        }
        else {
            return DEFAULT_VARIANT
        }

        /**
         * Ensure it's valid:
         * */
        return if(variants.contains(serializedName)) {
            serializedName
        }
        else {
            /**
             * Just fallback:
             * */
            DEFAULT_VARIANT
        }
    }

    /**
     * Cycles the variant.
     * */
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)

        if (!level.isClientSide) {
            val current = getVariantName(stack)
            val index = variants.indexOf(current)
            val next = variants[(index + 1) % variants.size]

            stack.orCreateTag.putString(VARIANT, next)
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    companion object : IGuiOverlay {
        private const val VARIANT = "variant"
        const val DEFAULT_VARIANT = ""

        /**
         * Renders an overlay with the selected tool mode.
         * */
        override fun render(gui: ForgeGui?, guiGraphics: GuiGraphics?, partialTick: Float, screenWidth: Int, screenHeight: Int) {
            if(gui == null || guiGraphics == null) {
                return
            }

            val player = Minecraft.getInstance().player
                ?: return

            val stack = player.getItemInHand(InteractionHand.MAIN_HAND)

            if(stack.isEmpty || stack.item !is BlacksmithingToolItem) {
                return
            }

            val variant = (stack.item as BlacksmithingToolItem).getVariantName(stack)

            val suffix = if(variant.isEmpty()) "" else "_$variant"
            val component = Component.translatable("overlay.$MODID.blacksmithing_tool_variant$suffix")

            guiGraphics.drawString(
                gui.font,
                component,
                0, 0,
                !MyColor(255, 255, 255, 255)
            )
        }
    }
}
