package org.eln2.mc.common.content.processing

import com.google.gson.JsonObject
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.SectionTrackedVisual
import dev.engine_room.flywheel.api.visual.ShaderLightVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.client.extensions.common.IClientBlockExtensions
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.fluids.capability.templates.FluidTank
import net.minecraftforge.items.ItemStackHandler
import net.minecraftforge.items.SlotItemHandler
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.Vector2di
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.ClientOnly
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.DebugVisualizer
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.client.screens.ProgressSupplierMenu
import org.eln2.mc.common.blocks.foundation.BigBlockRepresentativeBlockEntity
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateBlock
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateBlockEntity
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateBlockWithCustomCollider
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateMap
import org.eln2.mc.common.blocks.foundation.MultiblockTransformations
import org.eln2.mc.common.blocks.foundation.ReplaceVanillaParticlesBlockExtension
import org.eln2.mc.common.containers.ContainerHelper
import org.eln2.mc.common.containers.ProgressContainerData
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicate
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.parts.foundation.incrementFromForwardUp
import org.eln2.mc.common.recipes.foundation.*
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.mathematics.toHorizontalFacing
import java.util.*
import java.util.function.Consumer

private const val COKING_INPUT_SLOTS = 4
private const val COKING_OUTPUT_SLOTS = 4

/**
 * Multiple item input, multiple item output, single fluid output constant time recipe.
 * */
class CokingRecipe(
    override val recipeSerializer : Serializer,
    override val recipeId: ResourceLocation,
    val inputItems: Eln2WeightedItemRecipeRequirements,
    val outputItems: List<ItemStack>?,
    val outputFluid: FluidStack?,
    val duration: Int
) : Eln2CustomRecipe<CokingRecipe>, Eln2NonStandardRecipe {
    init {
        require(inputItems.requirements.size <= COKING_INPUT_SLOTS) {
            "Coking recipe \"$recipeId\" has more input items than is allowed ($COKING_INPUT_SLOTS)"
        }

        if(outputItems != null) {
            require(outputItems.size <= COKING_OUTPUT_SLOTS) {
                "Coking recipe \"$recipeId\" has more output items than is allowed ($COKING_OUTPUT_SLOTS)"
            }
        }
    }

    override fun matches(pContainer: SimpleContainer, pLevel: Level) : Boolean {
        val items = pContainer.bindToList()

        return items.applyRecipeWeighted(inputItems)
    }

    class Serializer(override val recipeType: RecipeType<CokingRecipe>) : Eln2RecipeSerializer<CokingRecipe> {
        override fun fromJson(pRecipeId: ResourceLocation, pSerializedRecipe: JsonObject): CokingRecipe {
            val inputItems = Eln2WeightedItemRecipeRequirements.fromJson(pSerializedRecipe.get("inputItems"))

            val outputItems = if(pSerializedRecipe.has("outputItems")) {
                pSerializedRecipe.getAsJsonArray("outputItems").map { ShapedRecipe.itemStackFromJson(it.asJsonObject) }
            }
            else {
                null
            }

            val outputFluid = if(pSerializedRecipe.has("outputFluid")) {
                pSerializedRecipe.getAsJsonObject("outputFluid").asFluidStack()
            }
            else {
                null
            }

            val duration = pSerializedRecipe.getAsJsonObject("duration").asInt

            return CokingRecipe(
                this, pRecipeId,
                inputItems, outputItems, outputFluid,
                duration
            )
        }

        override fun fromNetwork(pRecipeId: ResourceLocation, pBuffer: FriendlyByteBuf): CokingRecipe {
            val inputItems = Eln2WeightedItemRecipeRequirements.fromNetwork(pBuffer)

            val outputItems = pBuffer.readNullable {
                pBuffer.readList { pBuffer.readItem() }
            }

            val outputFluid = pBuffer.readNullable {
                pBuffer.readFluidStack()
            }

            val duration = pBuffer.readInt()

            return CokingRecipe(
                this, pRecipeId,
                inputItems, outputItems, outputFluid,
                duration
            )
        }

        override fun toNetwork(pBuffer: FriendlyByteBuf, pRecipe: CokingRecipe) {
            pRecipe.inputItems.toNetwork(pBuffer)

            pBuffer.writeNullable(pRecipe.outputItems) { _, outputItems ->
                pBuffer.writeCollection(outputItems) { _, stack ->
                    pBuffer.writeItem(stack)
                }
            }

            pBuffer.writeNullable(pRecipe.outputFluid) { _, outputFluid ->
                pBuffer.writeFluidStack(outputFluid)
            }

            pBuffer.writeInt(pRecipe.duration)
        }
    }
}

class CokeOvenDelegateBlock(initialShapes: List<AABB>, val capabilityMaskLocal: Base6Direction3dMask) : MultiblockDelegateBlockWithCustomCollider(initialShapes = initialShapes) {
    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = CokeOvenDelegateBlockEntity(pPos, pState)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun use(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult
    ): InteractionResult {
        return super.use(pState, pLevel, pPos, pPlayer, pHand, pHit)
    }
}

class CokeOvenDelegateBlockEntity(pPos: BlockPos, pBlockState: BlockState) : MultiblockDelegateBlockEntity(pPos, pBlockState, Eln2Processing.COKE_OVEN_DELEGATE_BLOCK_ENTITY.get()) {
    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if(side != null) {
            val sideLocal = MultiblockTransformations.rot(blockState.getValue(HorizontalDirectionalBlock.FACING))
                .rotate(side)

            if(!(blockState.block as CokeOvenDelegateBlock).capabilityMaskLocal.has(sideLocal)) {
                /**
                 * Rejects the capability on that side:
                 * */
                return LazyOptional.empty()
            }
        }

        val level = this.level
            ?: return super.getCapability(cap, side)

        val representativePos = this.representativePos
            ?: return super.getCapability(cap, side)

        if (!level.isLoaded(representativePos)) {
            return LazyOptional.empty()
        }

        val representative = level.getBlockEntity(representativePos) as? CokeOvenMainBlockEntity
            ?: return super.getCapability(cap, side)

        /**
         * Forward the fetch to the representative:
         * */
        return representative.getCapability(cap, null)
    }

    override fun invalidateCaps() {

        super.invalidateCaps()
    }
}

class CokeOvenMainBlock : HorizontalDirectionalBlock(Properties.of().noOcclusion()), EntityBlock {
    companion object {
        fun constructMenu(pLevel: Level, pPos: BlockPos, pPlayer: Player) = pLevel.constructMenuHelper2<CokeOvenMainBlockEntity>(
            pPos,
            pPlayer,
            Component.translatable("menu.$MODID.coke_oven"),
            ::CokeOvenMenu
        )
    }

    override fun getStateForPlacement(pContext: BlockPlaceContext): BlockState? {
        return super.defaultBlockState().setValue(
            FACING,
            pContext.horizontalDirection
        )
    }

    override fun createBlockStateDefinition(pBuilder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(pBuilder)
        pBuilder.add(FACING)
    }

    override fun initializeClient(consumer: Consumer<IClientBlockExtensions?>) {
        consumer.accept(ReplaceVanillaParticlesBlockExtension)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("true"))
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean {
        return true
    }

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = CokeOvenMainBlockEntity(pPos, pState)

    @Deprecated("Deprecated in Java")
    override fun use(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult,
    ) = constructMenu(pLevel, pPos, pPlayer)

    override fun <T : BlockEntity?> getTicker(pLevel: Level, pState: BlockState, pBlockEntityType: BlockEntityType<T?>): BlockEntityTicker<T?>? {
        if(pLevel.isClientSide) {
            return null
        }

        return BlockEntityTicker(CokeOvenMainBlockEntity::tickServer)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRemove(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pNewState: BlockState,
        pMovedByPiston: Boolean,
    ) {
        if(!pState.`is`(pNewState.block)) {
            if(!pLevel.isClientSide) {
                val blockEntity = pLevel.getBlockEntity(pPos) as? CokeOvenMainBlockEntity

                blockEntity?.setDestroyed()
            }
        }

        super.onRemove(pState, pLevel, pPos, pNewState, pMovedByPiston)
    }
}

class CokeOvenMainBlockEntity(pPos: BlockPos, pState: BlockState) :
    BlockEntity(Eln2Processing.COKE_OVEN_MAIN_BLOCK_ENTITY.get(), pPos, pState),
    BigBlockRepresentativeBlockEntity<CokeOvenMainBlockEntity>,
    ComponentDisplay
{
    companion object {
        @ServerOnly
        fun tickServer(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
            if(pBlockEntity !is CokeOvenMainBlockEntity) {
                return
            }

            pBlockEntity.serverTick()
        }
    }

    val data = ProgressContainerData()

    //#region Multiblock Setup

    override fun onDelegateUse(
        delegate: BlockEntity,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult
    ) = CokeOvenMainBlock.constructMenu(level!!, blockPos, pPlayer)

    fun setDestroyed() {
        destroyDelegates()
    }

    //#endregion

    //#region Inventory

    val inventoryHandler = InventoryHandlerImpl(this)
    val inventoryHandlerLazy: LazyOptional<InventoryHandlerImpl> = LazyOptional.of { inventoryHandler }

    val fluidHandler = FluidHandlerImpl(this)
    val fluidHandlerLazy: LazyOptional<FluidTank> = LazyOptional.of { fluidHandler }

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return inventoryHandlerLazy.cast()
        }

        if (cap == ForgeCapabilities.FLUID_HANDLER) {
            return fluidHandlerLazy.cast()
        }

        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        inventoryHandlerLazy.invalidate()
        fluidHandlerLazy.invalidate()
    }

    class InventoryHandlerImpl(val blockEntity: CokeOvenMainBlockEntity) : ItemStackHandler(COKING_INPUT_SLOTS + COKING_OUTPUT_SLOTS) {
        val inputRange = 0 until COKING_INPUT_SLOTS
        val outputRange = COKING_INPUT_SLOTS until (COKING_INPUT_SLOTS + COKING_OUTPUT_SLOTS)

        var dirty = false

        fun searchForRecipe() : Optional<CokingRecipe> {
            val level = blockEntity.level!!

            return level.recipeManager.getRecipeFor(
                Eln2Processing.COKING_RECIPE,
                this.bindToSimpleContainer(),
                level
            )
        }

        fun execute(recipe: CokingRecipe) {
            /**
             * Consumes the items:
             * */
            check(stacks.applyRecipeWeighted(recipe.inputItems)) {
                DEBUGGER_BREAK("Could not apply the previously verified recipe (input items) $recipe ${blockEntity.blockPos}")
            }

            /**
             * Creates the results:
             * */
            if(recipe.outputItems != null) {
                check(this.insertRange(recipe.outputItems, outputRange)) {
                    DEBUGGER_BREAK("Could not apply the previously verified recipe (output items) $recipe ${blockEntity.blockPos}")
                }
            }

            dirty = true
        }

        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
            if(outputRange.contains(slot)) {
                return stack
            }

            return super.insertItem(slot, stack, simulate)
        }

        override fun isItemValid(slot: Int, stack: ItemStack): Boolean {
            if(!inputRange.contains(slot)) {
                return true
            }

            val level = blockEntity.level!!
            val recipeManager = level.recipeManager
            val copy = this.bindToSimpleContainer()

            copy.setItem(slot, stack.copy())

            val recipe = recipeManager.getRecipeFor(
                Eln2Processing.COKING_RECIPE,
                copy,
                level
            )

            return recipe.isPresent
        }

        override fun onContentsChanged(slot: Int) {
            blockEntity.setChanged()
            dirty = true
        }
    }

    class FluidHandlerImpl(val blockEntity: CokeOvenMainBlockEntity) : FluidTank(8000) {
        override fun fill(resource: FluidStack?, action: IFluidHandler.FluidAction?): Int {
            return 0
        }

        fun execute(recipe: CokingRecipe) {
            val increment = recipe.outputFluid?.copy()
                ?: return

            /**
             * Directly increases the fluid in the tank (if the generated fluid is compatible with the current fluid):
             * */
            if(isEmpty || fluid.isFluidEqual(increment)) {
                super.fill(increment, IFluidHandler.FluidAction.EXECUTE)
                return
            }

            /**
             * Voids the previous fluid type and adds the increment:
             * */
            fluid = FluidStack.EMPTY
            super.fill(increment, IFluidHandler.FluidAction.EXECUTE)
        }

        override fun onContentsChanged() {
            blockEntity.setChanged()
            super.onContentsChanged()
        }
    }

    //#endregion

    private class Operation(val recipe: CokingRecipe, var elapsedTime: Int)

    @ServerOnly
    private var operation: Operation? = null

    @ServerOnly
    private var savedProgress: Int? = null

    override val delegateMap: MultiblockDelegateMap
        get() = Eln2Processing.COKE_OVEN_DELEGATE_MAP.value

    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if(!pLevel.isClientSide) {
            if(savedProgress != null) {
                val recipe = inventoryHandler.searchForRecipe()

                if(recipe.isEmpty) {
                    LOG.error(DEBUGGER_BREAK("Failed to restore saved recipe with progress $savedProgress in $level at $blockPos"))
                }
                else {
                    inventoryHandler.dirty = false
                    operation = Operation(recipe.get(), savedProgress!!)
                }

                savedProgress = null
            }
        }
    }

    @ServerOnly
    fun serverTick() {
        /**
         * If required, stops/starts the operation:
         * */
        if(inventoryHandler.dirty) {
            val targetRecipe = inventoryHandler.searchForRecipe()

            if(targetRecipe.isEmpty) {
                operation = null
            }
            else {
                /**
                 * Checks if we have space for export:
                 * */
                if(targetRecipe.get().outputItems != null && !inventoryHandler.bind().insertRange(targetRecipe.get().outputItems!!, inventoryHandler.outputRange)) {
                    operation = null // Cannot do anything
                }
                /**
                 * Can do this recipe:
                 * */
                else {
                    if(operation != null) {
                        /**
                         * Restart only if recipe changed:
                         * */
                        if(operation!!.recipe != targetRecipe.get()) {
                            operation = null
                            operation = Operation(targetRecipe.get(), 0)
                        }
                    }
                }
            }
        }

        /**
         * Progresses the recipe:
         * */
        if(operation != null) {
            val operation = operation!!
            operation.elapsedTime++

            /**
             * Try to finish the operation:
             * */
            if(operation.elapsedTime >= operation.recipe.duration) {
                inventoryHandler.execute(operation.recipe)
                fluidHandler.execute(operation.recipe)
                this.operation = null
            }

            setChanged()
        }
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        val operation = operation

        if(operation != null) {
            builder.debugInIDE { "Recipe: ${operation.recipe.recipeId}" }
            builder.progress(operation.elapsedTime / operation.recipe.duration.toDouble())
        }
    }

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)

        pTag.put("inventory", inventoryHandler.serializeNBT())
        fluidHandler.writeToNBT(pTag)

        if(operation != null) {
            pTag.putInt("progress", operation!!.elapsedTime)
        }
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)

        inventoryHandler.deserializeNBT(pTag.getCompound("inventory"))
        fluidHandler.readFromNBT(pTag)

        if(pTag.contains("progress")) {
            savedProgress = pTag.getInt("progress")
        }
    }
}

class CokeOvenMainBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: CokeOvenMainBlockEntity,
    partialTick: Float
) : AbstractBlockEntityVisual<CokeOvenMainBlockEntity>(ctx, blockEntity, partialTick), ShaderLightVisual {
    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.COKING_OVEN, FlwMaterials.CUTOUT_SMOOTH_LIT))
        .createInstance()
        .also {
            it.translate(visualPosition)
            it.center()
            it.rotateToFace(blockEntity.representativeFacing.clockWise)
            it.uncenter()
        }

    override fun updateLight(p0: Float) {
        // NOOP
    }

    override fun setSectionCollector(sectionCollector: SectionTrackedVisual.SectionCollector) {
        val set = LongOpenHashSet()

        set.add(SectionPos.asLong(pos))

        blockEntity.delegateMap.forEachDelegateInWorld(blockEntity.level!!, blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING), blockEntity.blockPos) {
            set.add(SectionPos.asLong(it))

            Base6Direction3d.entries.forEach { dir ->
                set.add(SectionPos.asLong(it + dir.alias))
            }
        }

        sectionCollector.sections(set)
    }

    override fun collectCrumblingInstances(p0: Consumer<Instance?>) {
        p0.accept(body)
    }

    override fun _delete() {
        body.delete()
    }
}

private val INPUT_SLOTS = mapOf(
    0 to Vector2di(21, 24),
    1 to Vector2di(48, 24),
    2 to Vector2di(75, 24),
    3 to Vector2di(102, 24)
)

private val OUTPUT_SLOTS = mapOf(
    0 to Vector2di(25, 49),
    1 to Vector2di(51, 49),
    2 to Vector2di(77, 49),
    3 to Vector2di(103, 49)
)

class CokeOvenMenu(
    pContainerId: Int,
    playerInventory: Inventory,
    handler: ItemStackHandler,
    val containerData: ProgressContainerData,
    val access: ContainerLevelAccess,
    val level: Level
) : AbstractContainerMenu(Eln2Processing.COKE_OVEN_MENU.get(), pContainerId), ProgressSupplierMenu {
    @ServerOnly
    constructor(entity: CokeOvenMainBlockEntity, id: Int, inventory: Inventory): this(
        id,
        inventory,
        entity.inventoryHandler,
        entity.data,
        ContainerLevelAccess.create(entity.level!!, entity.blockPos),
        entity.level!!
    )

    @ClientOnly
    constructor(pContainerId: Int, playerInventory: Inventory) : this(
        pContainerId,
        playerInventory,
        ItemStackHandler(COKING_INPUT_SLOTS + COKING_OUTPUT_SLOTS),
        ProgressContainerData(),
        ContainerLevelAccess.NULL,
        playerInventory.player.level()
    )

    init {
        INPUT_SLOTS.forEach { (slot, pos) ->
            addSlot(SlotItemHandler(handler, slot, pos.x, pos.y))
        }

        OUTPUT_SLOTS.forEach { (slot, pos) ->
            addSlot(SlotItemHandlerWithPlacePredicate(handler, slot, pos.x, pos.y) { false })
        }

        addDataSlots(containerData)

        ContainerHelper.addPlayerGrid(playerInventory, this::addSlot)
    }

    override fun stillValid(pPlayer: Player) = stillValid(access, pPlayer, Eln2Processing.COKE_OVEN_MAIN_BLOCK.get())

    override fun quickMoveStack(pPlayer: Player, pIndex: Int) = ContainerHelper.quickMove(slots, pPlayer, pIndex)

    override fun getProgressForRender() = containerData.progress
}
