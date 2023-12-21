package org.eln2.mc.common.blocks.foundation

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import org.eln2.mc.LOG
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.content.Content
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.noop
import java.util.function.Supplier
import kotlin.math.ceil

object MultiblockTransformations {
    fun transformMultiblockWorld(facing: Direction, origin: BlockPos, posMultiblock: BlockPos): BlockPos {
        val posActual = rot(facing) * posMultiblock
        return origin + posActual
    }

    fun transformWorldMultiblock(facing: Direction, origin: BlockPos, posWorld: BlockPos): BlockPos {
        val posActual = posWorld - origin
        val txActualId = rot(facing).inverse()
        return txActualId * posActual
    }
}

data class BigBlockDelegateDefinition(val delegatesId: Map<BlockPos, BlockState>) {
    val volume = run {
        var box = AABB(BlockPos.ZERO)

        delegatesId.keys.forEach {
            box = box.minmax(AABB(it))
        }

        box
    }

    val radius = ceil(listOf(volume.xsize, volume.ysize, volume.zsize).max() / 2).toInt()

    fun destroyInWorld(level: Level, facing: Direction, origin: BlockPos) {
        if(level.isClientSide) {
            return
        }

        delegatesId.forEach { (delegatePosId, state) ->
            val delegatePosWorld = MultiblockTransformations.transformMultiblockWorld(facing, origin, delegatePosId)
            val actualState = level.getBlockState(delegatePosWorld)

            if(actualState == state) {
                level.destroyBlock(delegatePosWorld, false)
            }
        }
    }

    companion object {
        class Builder(private val blocks: MutableMap<BlockPos, BlockState>) {
            fun state(cell: BlockPos, state: BlockState) {
                require(cell.x != 0 || cell.y != 0 || cell.z != 0) {
                    "Tried to replace representative"
                }

                require(blocks.put(cell, state) == null) {
                    "Duplicate resource $cell $state"
                }
            }

            fun default(cell: BlockPos, supplier: Supplier<Block>) {
                state(cell, supplier.get().defaultBlockState())
            }

            fun default(cell: BlockPos, block: Block) {
                state(cell, block.defaultBlockState())
            }
        }

        fun build(action: Builder.() -> Unit) : BigBlockDelegateDefinition {
            val blocks = LinkedHashMap<BlockPos, BlockState>()
            val builder = Builder(blocks)
            action(builder)
            return BigBlockDelegateDefinition(blocks)
        }
    }
}

class MultiblockScan(
    val definition: BigBlockDelegateDefinition,
    val worldAccess: ((BlockPos) -> BlockState),
    val origin: BlockPos,
    val facing: Direction,
) {
    fun scanMissing(): List<BlockPos> {
        val results = ArrayList<BlockPos>()

        definition.delegatesId.forEach { (requiredPosId, requiredBlock) ->
            val requiredPosWorld = MultiblockTransformations.transformMultiblockWorld(
                facing,
                origin,
                requiredPosId
            )

            val actualBlock = worldAccess(requiredPosWorld)

            if (actualBlock != requiredBlock) {
                results.add(requiredPosWorld)
            }
        }

        return results
    }
}

interface BigBlockRepresentative {
    fun onDelegateUse(pDelegate: BigBlockDelegateBlockEntity, pPlayer: Player, pHand: InteractionHand, pHit: BlockHitResult) : InteractionResult {
        return InteractionResult.PASS
    }

    fun onDelegateDestroyed(pDelegate: BigBlockDelegateBlockEntity) {

    }
}

class BigBlockDelegateBlock : BaseEntityBlock(
    Properties.copy(Blocks.STONE)
        .noOcclusion()
        .destroyTime(0.2f))
{
    override fun newBlockEntity(pPos: BlockPos, pState: BlockState): BlockEntity {
        return BigBlockDelegateBlockEntity(pPos, pState)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("true"))
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean {
        return true
    }

    private fun getRepresentativeAndDelegate(pLevel: LevelAccessor, pPos: BlockPos) : Pair<BigBlockDelegateBlockEntity, BigBlockRepresentative>? {
        val blockEntity = pLevel.getBlockEntity(pPos) as? BigBlockDelegateBlockEntity
            ?: return null

        val representativePos = blockEntity.representativePos

        if(representativePos == null) {
            LOG.error("Did not have representative for interaction $pLevel $pPos")
            return null
        }

        val representative = pLevel.getBlockEntity(representativePos) as? BigBlockRepresentative
            ?: pLevel.getBlockState(representativePos).block as? BigBlockRepresentative

        return if(representative == null) {
            LOG.error("Representative is missing $pLevel $pPos $representativePos")
            null
        } else {
            blockEntity to representative
        }
    }

    @Deprecated("Deprecated in Java")
    override fun use(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult
    ): InteractionResult {
        val (delegate, representative) = getRepresentativeAndDelegate(pLevel, pPos)
            ?: return InteractionResult.FAIL

        return representative.onDelegateUse(delegate, pPlayer, pHand, pHit)
    }

    private fun setDestroyed(pLevelAccessor: LevelAccessor, pPos: BlockPos) {
        val (delegate, representative) = getRepresentativeAndDelegate(pLevelAccessor, pPos)
            ?: return

        representative.onDelegateDestroyed(delegate)
    }

    override fun onDestroyedByPlayer(
        state: BlockState?,
        level: Level?,
        pos: BlockPos?,
        player: Player?,
        willHarvest: Boolean,
        fluid: FluidState?
    ): Boolean {
        if(level != null && pos != null) {
            setDestroyed(level, pos)
        }

        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid)
    }

    override fun onBlockExploded(state: BlockState?, level: Level?, pos: BlockPos?, explosion: Explosion?) {
        if(level != null && pos != null) {
            setDestroyed(level, pos)
        }

        super.onBlockExploded(state, level, pos, explosion)
    }
}

class BigBlockDelegateBlockEntity(pPos: BlockPos, pBlockState: BlockState) : BlockEntity(BlockRegistry.BIG_BLOCK_DELEGATE_BLOCK_ENTITY.get(), pPos, pBlockState), ComponentDisplay {
    var representativePos: BlockPos? = null
        private set

    fun setRepresentative(representative: BlockPos) {
        check(representativePos == null) {
            "Tried to set representative multiple times"
        }

        this.representativePos = representative
        this.setChanged()
    }

    fun removeRepresentative() {
        check(representativePos != null) {
            "Tried to remove non-exiting representative"
        }

        representativePos = null
        this.setChanged()
    }

    override fun saveAdditional(pTag: CompoundTag) {
        val representativePos = this.representativePos

        if(representativePos != null) {
            pTag.putBlockPos(REPRESENTATIVE_POS, representativePos)
        }

        super.saveAdditional(pTag)
    }

    override fun load(pTag: CompoundTag) {
        if(pTag.contains(REPRESENTATIVE_POS)) {
            this.representativePos = pTag.getBlockPos(REPRESENTATIVE_POS)
        }

        super.load(pTag)
    }

    companion object {
        private const val REPRESENTATIVE_POS = "representativePos"
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debug("Rep: $representativePos")
    }
}

class BigBlockItem(val definition: BigBlockDelegateDefinition, representative: HorizontalDirectionalBlock) : BlockItem(representative, Properties()) {
    override fun place(pContext: BlockPlaceContext): InteractionResult {
        val facing = checkNotNull((block as HorizontalDirectionalBlock).getStateForPlacement(pContext)) {
            "Expected default state $block"
        }.getValue(HorizontalDirectionalBlock.FACING)

        fun transform(blockPosId: BlockPos) = MultiblockTransformations.transformMultiblockWorld(
            facing,
            pContext.clickedPos,
            blockPosId
        )

        definition.delegatesId.keys.forEach { blockPosId ->
            if(!pContext.level.getBlockState(transform(blockPosId)).isAir) {
                return InteractionResult.FAIL
            }
        }

        val result = super.place(pContext)

        if(pContext.level.isClientSide) {
            return result
        }

        if(result == InteractionResult.CONSUME) {
            definition.delegatesId.forEach { (delegatePosId, state) ->
                val delegatePosWorld = transform(delegatePosId)

                pContext.level.setBlockAndUpdate(delegatePosWorld, state)

                val blockEntity = pContext.level.getBlockEntity(delegatePosWorld)

                if(blockEntity is BigBlockDelegateBlockEntity) {
                    blockEntity.setRepresentative(pContext.clickedPos)
                }
            }
        }

        return result
    }
}

class TestRep : HorizontalDirectionalBlock(Properties.copy(Blocks.STONE)), BigBlockRepresentative {
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

    override fun onDelegateDestroyed(pDelegate: BigBlockDelegateBlockEntity) {
        super.onDelegateDestroyed(pDelegate)
    }

    override fun onDelegateUse(
        pDelegate: BigBlockDelegateBlockEntity,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult
    ): InteractionResult {
        return super.onDelegateUse(pDelegate, pPlayer, pHand, pHit)
    }
}
