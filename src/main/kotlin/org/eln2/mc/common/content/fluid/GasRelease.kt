@file:Suppress("unused")

package org.eln2.mc.common.content.fluid

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import org.eln2.mc.ServerOnly
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.fluids.foundation.FractionalFluidStack
import org.eln2.mc.common.fluids.foundation.IFractionalFluidHandler
import org.eln2.mc.common.fluids.foundation.IThermalFluidHandler
import org.eln2.mc.common.fluids.foundation.PhysicalFluidManager
import org.eln2.mc.common.fluids.foundation.ThermalFluidStack
import org.eln2.mc.common.fluids.foundation.fractional
import org.eln2.mc.extensions.eln2StandardBlockProperties
import org.eln2.mc.extensions.plus
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList

class GasReleaseBlock : Block(eln2StandardBlockProperties().noOcclusion()), EntityBlock {
    init {
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.DOWN))
    }

    override fun createBlockStateDefinition(pBuilder: StateDefinition.Builder<Block, BlockState>) {
        pBuilder.add(FACING)
    }

    /**
     * The facing stores the world-space direction the connector (model DOWN face) points to, which is into the surface the block is placed on.
     * */
    override fun getStateForPlacement(pContext: BlockPlaceContext): BlockState? {
        return defaultBlockState().setValue(FACING, pContext.clickedFace.opposite)
    }

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState): BlockEntity = GasReleaseBlockEntity(pPos, pState)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun neighborChanged(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pBlock: Block,
        pFromPos: BlockPos,
        pIsMoving: Boolean,
    ) {
        @Suppress("DEPRECATION")
        super.neighborChanged(pState, pLevel, pPos, pBlock, pFromPos, pIsMoving)

        if (!pLevel.isClientSide) {
            (pLevel.getBlockEntity(pPos) as? GasReleaseBlockEntity)?.markNeighborChanged()
        }
    }

    override fun onNeighborChange(pState: BlockState, pLevel: LevelReader, pPos: BlockPos, pNeighborPos: BlockPos) {
        super.onNeighborChange(pState, pLevel, pPos, pNeighborPos)

        if (pLevel is Level && !pLevel.isClientSide) {
            (pLevel.getBlockEntity(pPos) as? GasReleaseBlockEntity)?.markNeighborChanged()
        }
    }

    override fun <T : BlockEntity?> getTicker(
        pLevel: Level,
        pState: BlockState,
        pBlockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (pLevel.isClientSide) {
            return null
        }

        return BlockEntityTicker { _, _, _, blockEntity ->
            if (blockEntity is GasReleaseBlockEntity) {
                blockEntity.serverTick()
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getShape(
        pState: BlockState,
        pLevel: BlockGetter,
        pPos: BlockPos,
        pContext: CollisionContext,
    ): VoxelShape = COLLIDERS[pState.getValue(FACING).get3DDataValue()]

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.FACING

        private val COLLIDERS: Array<VoxelShape> = run {
            val down = box(6.0, 0.0, 6.0, 10.0, 2.0, 10.0)
            val up = box(6.0, 14.0, 6.0, 10.0, 16.0, 10.0)
            val north = box(6.0, 6.0, 0.0, 10.0, 10.0, 2.0)
            val south = box(6.0, 6.0, 14.0, 10.0, 10.0, 16.0)
            val west = box(14.0, 6.0, 6.0, 16.0, 10.0, 10.0)
            val east = box(0.0, 6.0, 6.0, 2.0, 10.0, 10.0)

            arrayOf(down, up, north, south, west, east)
        }
    }
}

class GasReleaseBlockEntity(pPos: BlockPos, pState: BlockState) : BlockEntity(Eln2ForgeFluids.GAS_RELEASE_BLOCK_ENTITY.get(), pPos, pState), ComponentDisplay {
    companion object {
        // Not really enforced, just returned as the tank's capacity. Multiple fill calls will work.
        private const val CAP = 10

        private fun isGaseous(fluid: Fluid): Boolean = PhysicalFluidManager.getProperties(fluid)?.isGaseous == true
    }

    /**
     * Accepts and voids any gaseous fluid offered to its connector face.
     * Liquids and unregistered fluids are rejected.
     * */
    class GasVoidHandler : IFractionalFluidHandler {
        override fun getTanks(): Int = 1

        override fun getFluidInTank(tank: Int): FluidStack = FluidStack.EMPTY

        override fun getFractionalFluidInTank(tank: Int): FractionalFluidStack = FractionalFluidStack.EMPTY

        /**
         * Reports an effectively unbounded capacity so connected pipes always attempt to push gas into the vent.
         * */
        override fun getTankCapacity(tank: Int): Int = CAP

        override fun getFractionalTankCapacity(tank: Int): Double = CAP.toDouble()

        override fun isFluidValid(tank: Int, stack: FluidStack): Boolean = isGaseous(stack.fluid)

        override fun fillFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): Double {
            if (resource.isEmpty || !isGaseous(resource.fluid)) {
                return 0.0
            }

            return resource.amount
        }

        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            if (resource.isEmpty || !isGaseous(resource.fluid)) {
                return 0
            }

            return resource.amount
        }

        override fun drainFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction) = FractionalFluidStack.EMPTY

        override fun drainFractional(maxDrain: Double, action: IFluidHandler.FluidAction) = FractionalFluidStack.EMPTY

        override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack = FluidStack.EMPTY

        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack = FluidStack.EMPTY
    }

    private val handler = GasVoidHandler()
    private val handlerLazy: LazyOptional<GasVoidHandler> = LazyOptional.of { handler }

    /**
     * Set when a neighbor changes or when the block entity loads on the server, so the next [serverTick] re-resolves the neighbor's fluid handler on the connector face.
     * */
    @ServerOnly
    private var neighborScanPending = false

    /**
     * The resolved fluid handler on the connector face, or null if none is present.
     * */
    @ServerOnly
    private var neighborHandler: LazyOptional<IFluidHandler>? = null

    @ServerOnly
    fun markNeighborChanged() {
        neighborScanPending = true
    }

    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if (!pLevel.isClientSide) {
            neighborScanPending = true
        }
    }

    @ServerOnly
    fun serverTick() {
        if (neighborScanPending) {
            neighborScanPending = false
            resolveNeighborHandler()
        }

        drainNeighborGas()
    }

    /**
     * Resolves the fluid handler on the block adjacent to the connector face.
     * Attaches an invalidation listener so a neighbor capability change triggers a re-scan.
     * */
    @ServerOnly
    private fun resolveNeighborHandler() {
        neighborHandler = null

        val level = level ?: return
        if (level.isClientSide) {
            return
        }

        val connectorDir = blockState.getValue(GasReleaseBlock.FACING)
        val neighborPos = blockPos + connectorDir

        if (!level.isLoaded(neighborPos)) {
            return
        }

        val neighborEntity = level.getBlockEntity(neighborPos) ?: return
        val lazy = neighborEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, connectorDir.opposite)

        if (!lazy.isPresent) {
            return
        }

        neighborHandler = lazy
        lazy.addListener { neighborScanPending = true }
    }

    /**
     * Drains all gaseous fluids from the neighbor handler, voiding the result.
     * Thermal handlers are drained via [IThermalFluidHandler.drainThermal] to avoid corrupting the source's thermal state.
     * */
    @ServerOnly
    private fun drainNeighborGas() {
        val lazy = neighborHandler ?: return

        if (!lazy.isPresent) {
            neighborScanPending = true
            return
        }

        val handler = lazy.resolve().orElse(null) ?: return

        for (tank in 0 until handler.tanks) {
            val fractional = if (handler is IFractionalFluidHandler) {
                handler.getFractionalFluidInTank(tank)
            } else {
                handler.getFluidInTank(tank).fractional()
            }

            if (fractional.isEmpty || !isGaseous(fractional.fluid)) {
                continue
            }

            if (handler is IThermalFluidHandler) {
                handler.drainThermal(fractional, IFluidHandler.FluidAction.EXECUTE)
            } else if (handler is IFractionalFluidHandler) {
                handler.drainFractional(fractional, IFluidHandler.FluidAction.EXECUTE)
            } else {
                val discrete = handler.getFluidInTank(tank)

                if (!discrete.isEmpty) {
                    handler.drain(discrete, IFluidHandler.FluidAction.EXECUTE)
                }
            }
        }
    }

    override fun <T> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.FLUID_HANDLER && side == blockState.getValue(GasReleaseBlock.FACING)) {
            return handlerLazy.cast()
        }

        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        handlerLazy.invalidate()
        neighborHandler = null
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Gas vent: connector on ${blockState.getValue(GasReleaseBlock.FACING)}" }
    }
}
