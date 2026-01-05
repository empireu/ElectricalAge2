package org.eln2.mc.common.content.processing

import com.google.gson.JsonObject
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
import net.minecraft.core.RegistryAccess
import net.minecraft.data.recipes.FinishedRecipe
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.GsonHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.crafting.*
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ItemLike
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
import net.minecraftforge.registries.ForgeRegistries
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.MODID
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.content.processing.BlacksmithingToolItem.Companion.DEFAULT_VARIANT
import org.eln2.mc.common.recipes.RecipeRegistry
import org.eln2.mc.extensions.eln2Consume
import org.eln2.mc.extensions.eln2StandardBlockProperties
import org.eln2.mc.extensions.preserve
import org.eln2.mc.extensions.setSyncDirty
import org.eln2.mc.extensions.takeDurability
import org.eln2.mc.extensions.toVector3d
import org.eln2.mc.randomFloat
import org.joml.Quaternionf
import java.util.*
import java.util.function.Consumer
import kotlin.math.PI

/**
 * @param input The single input item for the recipe.
 * @param output The single output item for the recipe.
 * @param tool The tool item.
 * @param toolMode The tool variant ([BlacksmithingToolItem.variants]).
 * @param cooldown The cooldown, in ticks, applied by the server to the item after this recipe has been applied.
 * */
class BlacksmithingRecipe(
    val recipeSerializer: Serializer,
    val recipeId: ResourceLocation,
    val input: Ingredient,
    val output: ItemStack,
    val tool: Ingredient,
    val toolMode: String,
    val cooldown: Int,
    val sound: ResourceLocation?
) : Recipe<SimpleContainer> {
    val toolItem: BlacksmithingToolItem

    init {
        require(input.items.size == 1 && input.items[0].count == 1) {
            DEBUGGER_BREAK("Blacksmithing recipe requires exactly one input!")
        }

        require(output.count == 1) {
            DEBUGGER_BREAK("Blacksmithing recipe requires exactly one output!")
        }

        require(input.items.size == 1 && input.items[0].count == 1) {
            DEBUGGER_BREAK("Blacksmithing recipe requires exactly one tool item!")
        }

        require(tool.items[0].item is BlacksmithingToolItem) {
            "Blacksmithing recipe requires a blacksmithing tool item implementation!"
        }

        toolItem = tool.items[0].item as BlacksmithingToolItem

        require(toolItem.variants.contains(toolMode)) {
            "The variants of $toolItem don't include the mode \"$toolMode\"!"
        }
    }

    override fun matches(pContainer: SimpleContainer, pLevel: Level) : Boolean {
        if(!input.test(pContainer.getItem(BlacksmithingStationBlockEntity.STATION_SLOT))) {
            return false
        }

        val toolStack = pContainer.getItem(BlacksmithingStationBlockEntity.TOOL_SLOT)

        if(!tool.test(toolStack)) {
            return false
        }

        return (toolStack.item as BlacksmithingToolItem).getVariantName(toolStack) == toolMode
    }

    override fun assemble(pContainer: SimpleContainer, pRegistryAccess: RegistryAccess): ItemStack = output.copy()
    override fun canCraftInDimensions(pWidth: Int, pHeight: Int) = true
    override fun getResultItem(pRegistryAccess: RegistryAccess): ItemStack = output.copy()

    override fun getId() = recipeId
    override fun getSerializer() = recipeSerializer
    override fun getType() = recipeSerializer.recipeType

    class Serializer(val recipeType: RecipeType<BlacksmithingRecipe>) : RecipeSerializer<BlacksmithingRecipe> {
        override fun fromJson(pRecipeId: ResourceLocation, pSerializedRecipe: JsonObject): BlacksmithingRecipe {
            val input = Ingredient.fromJson(pSerializedRecipe.get("ingredient"))
            val output = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(pSerializedRecipe, "result"))
            val tool = Ingredient.fromJson(pSerializedRecipe.get("tool"))
            val toolMode = pSerializedRecipe.getAsJsonPrimitive("tool_mode").asString
            val cooldown = pSerializedRecipe.getAsJsonPrimitive("cooldown").asInt

            val sound = if(pSerializedRecipe.has("sound")) {
                ResourceLocation.parse(pSerializedRecipe.getAsJsonPrimitive("sound").asString)
            }
            else {
                null
            }

            return BlacksmithingRecipe(
                this,
                pRecipeId,
                input, output,
                tool, toolMode,
                cooldown,
                sound
            )
        }

        override fun fromNetwork(pRecipeId: ResourceLocation, pBuffer: FriendlyByteBuf): BlacksmithingRecipe {
            val input = Ingredient.fromNetwork(pBuffer)
            val output = pBuffer.readItem()
            val tool = Ingredient.fromNetwork(pBuffer)
            val toolMode = pBuffer.readUtf()
            val cooldown = pBuffer.readInt()
            val sound = pBuffer.readUtf()

            return BlacksmithingRecipe(
                this,
                pRecipeId,
                input, output,
                tool, toolMode,
                cooldown,
                if(sound.isEmpty()) null else ResourceLocation.parse(sound)
            )
        }

        override fun toNetwork(pBuffer: FriendlyByteBuf, pRecipe: BlacksmithingRecipe) {
            pRecipe.input.toNetwork(pBuffer)
            pBuffer.writeItem(pRecipe.output)
            pRecipe.tool.toNetwork(pBuffer)
            pBuffer.writeUtf(pRecipe.toolMode)
            pBuffer.writeInt(pRecipe.cooldown)
            pBuffer.writeUtf(if(pRecipe.sound == null) "" else pRecipe.sound.toString())
        }
    }
}

class BlacksmithingRecipeBuilder(val input: Ingredient, val result: Item, val count: Int) {
    private var tool: BlacksmithingToolItem? = null
    private var toolMode: String = ""
    private var cooldown: Int = 100
    private var sound: ResourceLocation? = null

    companion object {
        fun blacksmithing(input: Ingredient, output: ItemLike, count: Int = 1): BlacksmithingRecipeBuilder {
            return BlacksmithingRecipeBuilder(input, output.asItem(), count)
        }
    }

    fun setTool(toolItem: BlacksmithingToolItem): BlacksmithingRecipeBuilder {
        this.tool = toolItem
        return this
    }

    fun setMode(mode: String): BlacksmithingRecipeBuilder {
        this.toolMode = mode
        return this
    }

    fun setCooldown(ticks: Int): BlacksmithingRecipeBuilder {
        this.cooldown = ticks
        return this
    }

    fun setSound(sound: ResourceLocation): BlacksmithingRecipeBuilder {
        this.sound = sound
        return this
    }

    fun save(consumer: Consumer<FinishedRecipe?>, id: ResourceLocation) {
        val tool = tool ?: error(DEBUGGER_BREAK("Did not set blacksmithing tool"))
        check(tool.variants.contains(toolMode)) { DEBUGGER_BREAK("Set invalid tool variant \"$toolMode\"")}

        consumer.accept(
            Serializer(
                id, input,
                ItemStack(result, count),
                Ingredient.of(tool),
                toolMode,
                cooldown,
                sound
            )
        )
    }

    class Serializer(
        val recipeId: ResourceLocation,
        val input: Ingredient,
        val output: ItemStack,
        val tool: Ingredient,
        val toolMode: String,
        val cooldown: Int,
        val sound: ResourceLocation?
    ) : FinishedRecipe {
        override fun serializeRecipeData(json: JsonObject) {
            json.add("ingredient", input.toJson())

            json.add(
                "result",
                JsonObject().also { resultJson ->
                    resultJson.addProperty("item", ForgeRegistries.ITEMS.getKey(output.item).toString())
                    resultJson.addProperty("count", output.count)
                }
            )

            json.add("tool", tool.toJson())
            json.addProperty("tool_mode", toolMode)
            json.addProperty("cooldown", cooldown)

            if (sound != null) {
                json.addProperty("sound", sound.toString())
            }
        }

        override fun getId(): ResourceLocation = recipeId
        override fun getType(): RecipeSerializer<*> = RecipeRegistry.getRecipeSerializer(Eln2Processing.BLACKSMITHING_RECIPE)!!.get()
        override fun serializeAdvancement(): JsonObject? = null
        override fun getAdvancementId(): ResourceLocation? = null
    }
}

class BlacksmithingStationBlock : HorizontalDirectionalBlock(eln2StandardBlockProperties().noOcclusion()), EntityBlock {
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

    fun searchForRecipe(stack: ItemStack, tool: ItemStack): Optional<BlacksmithingRecipe> {
        if(stack.isEmpty) {
            return Optional.empty()
        }

        val level = level!!

        val craftingContainer = SimpleContainer(2)
        craftingContainer.setItem(STATION_SLOT, stack)
        craftingContainer.setItem(TOOL_SLOT, tool)

        return level.recipeManager.getRecipeFor(
            Eln2Processing.BLACKSMITHING_RECIPE,
            craftingContainer,
            level
        )
    }

    @ServerOnly
    private fun toolInteraction(player: Player) : InteractionResult {
        val toolStack = player.getItemInHand(InteractionHand.MAIN_HAND)
        val recipeOptional = searchForRecipe(inventoryHandler.getStackInSlot(0), toolStack)

        if(recipeOptional.isEmpty) {
            return InteractionResult.FAIL
        }

        val recipe = recipeOptional.get()

        val output = recipe.output.copy()
        inventoryHandler.setStackInSlot(STATION_SLOT, output)

        val sound = if(recipe.sound == null) {
            SoundEvents.ANVIL_USE
        }
        else {
            ForgeRegistries.SOUND_EVENTS.getValue(recipe.sound) ?: error(
                DEBUGGER_BREAK("Invalid sound event \"${recipe.sound}\" from recipe ${recipe.recipeId}")
            )
        }

        val (x, y, z) = blockPos.toVector3d() + Vector3d(0.5)

        level!!.playSound(
            null,
            x, y, z,
            sound,
            SoundSource.BLOCKS,
            randomFloat(0.8f, 1.1f),
            randomFloat(0.9f, 1.1f)
        )

        player.cooldowns.addCooldown(recipe.toolItem, recipe.cooldown)
        toolStack.takeDurability(player)

        return InteractionResult.CONSUME_PARTIAL
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

        val anvilItem = inventoryHandler.getStackInSlot(STATION_SLOT)

        /**
         * Tries to remove an item from the anvil:
         * */
        if(!anvilItem.isEmpty) {
            if(player.addItem(anvilItem)) {
                inventoryHandler.setStackInSlot(STATION_SLOT, ItemStack.EMPTY)
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

        inventoryHandler.setStackInSlot(STATION_SLOT, stackInHand.copyWithCount(1))
        stackInHand.eln2Consume(player)

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
        const val STATION_SLOT = 0
        const val TOOL_SLOT = 1
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

        val stack = pBlockEntity.inventoryHandler.getStackInSlot(BlacksmithingStationBlockEntity.STATION_SLOT)

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
class BlacksmithingToolItem(durability: Int, extraVariants: List<String>) : Item(Properties().stacksTo(1).durability(durability)) {
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

            level.playSound(
                null,
                player.x, player.y, player.z,
                SoundEvents.BUNDLE_DROP_CONTENTS,
                SoundSource.BLOCKS,
                randomFloat(1.3f, 1.5f),
                randomFloat(0.5f, 0.8f)
            )
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
