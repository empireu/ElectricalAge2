package org.eln2.mc.common.content.processing

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.PushReaction
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.ServerOnly
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.fluids.foundation.FractionalFluidStack
import org.eln2.mc.common.fluids.foundation.IFractionalFluidHandler
import org.eln2.mc.common.fluids.foundation.fractional
import org.eln2.mc.extensions.eln2StandardBlockProperties
import org.eln2.mc.extensions.plus
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.requireIsOnServerThread

@ServerOnly
object LeadChamberExecutionManager {
    private class LevelData {
        val blockEntities = HashMap<LeadChamberBlockEntity, LeadChamberBlockEntity.LeadChamberSimulation>()
        val simulationList = ArrayList<LeadChamberBlockEntity.LeadChamberSimulation>()
    }

    private val levels = HashMap<ServerLevel, LevelData>()

    private fun getDataForLevel(level: Level) : LevelData {
        requireIsOnServerThread()

        val serverLevel = level as? ServerLevel
            ?: error("The lead chamber's level isn't a ServerLevel")

        return levels.computeIfAbsent(serverLevel) {
            LevelData()
        }
    }

    /**
     * Registers a lead chamber for execution.
     * Must be called only once for the lifecycle of the given block entity.
     * */
    fun registerBlockEntity(blockEntity: LeadChamberBlockEntity) {
        requireIsOnServerThread()

        val data = getDataForLevel(blockEntity.level!!)

        data.blockEntities.putUnique(blockEntity, blockEntity.simulation) {
            "Duplicate add lead chamber ${blockEntity.blockPos}"
        }

        data.simulationList.add(blockEntity.simulation)
    }

    /**
     * Unregisters a lead chamber. Called when its chunk is unloaded, or when it gets destroyed.
     * Allows multiple calls.
     * */
    fun unregisterBlockEntity(blockEntity: LeadChamberBlockEntity) {
        requireIsOnServerThread()

        val data = getDataForLevel(blockEntity.level!!)

        if(data.blockEntities.remove(blockEntity) != null) {
            data.simulationList.remove(blockEntity.simulation)
        }
    }

    /**
     * Called on the server thread by [org.eln2.mc.common.ForgeEvents.onServerTick].
     * */
    fun dispatch() {
        levels.values.forEach {
            dispatch(it)
        }
    }

    /**
     * Executes the three passes of the simulation. Also removes stale neighbor references from [LeadChamberBlockEntity.linkedCells].
     * */
    private fun dispatch(data: LevelData) {
        val simulationList = data.simulationList
        val count = simulationList.size

        for (i in 0 until count) {
            val simulation = simulationList[i]
            val blockEntity = simulation.blockEntity

            blockEntity.rebuildLinksIfRequired()

            val level = blockEntity.level!!
            for (i in 0 until 6) {
                val neighbor = blockEntity.linkedCells[i]
                    ?: continue

                // TODO Optimization: bake unique chunk positions
                if (!level.isLoaded(neighbor.blockPos)) {
                    blockEntity.linkedCells[i] = null
                }
            }

            simulation.transformationReaction()
            simulation.initialPass()
        }

        for (i in 0 until count) {
            simulationList[i].transferPass()
        }

        for (i in 0 until count) {
            simulationList[i].finalizePass()
        }
    }
}

class LeadChamberBlock : Block(eln2StandardBlockProperties().noOcclusion().pushReaction(PushReaction.BLOCK)), EntityBlock {
    override fun newBlockEntity(pPos: BlockPos, pState: BlockState): BlockEntity {
        return LeadChamberBlockEntity(pPos, pState)
    }

    private fun getBlockEntity(level: Level, pos: BlockPos): LeadChamberBlockEntity? {
        return level.getBlockEntity(pos) as? LeadChamberBlockEntity
    }

    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pBlock: Block,
        pFromPos: BlockPos,
        pIsMoving: Boolean,
    ) {
        super.neighborChanged(pState, pLevel, pPos, pBlock, pFromPos, pIsMoving)

        if (!pLevel.isClientSide) {
            getBlockEntity(pLevel, pPos)?.markForRebuild()
        }
    }

    override fun onNeighborChange(pState: BlockState, pLevel: LevelReader, pPos: BlockPos, pNeighborPos: BlockPos) {
        super.onNeighborChange(pState, pLevel, pPos, pNeighborPos)

        if (pLevel is Level && !pLevel.isClientSide) {
            getBlockEntity(pLevel, pPos)?.markForRebuild()
        }
    }
}

class LeadChamberBlockEntity(pPos: BlockPos, pState: BlockState) : BlockEntity(Eln2Processing.LEAD_CHAMBER_BLOCK_ENTITY.get(), pPos, pState), ComponentDisplay {
    companion object {
        private const val SULFURIC_ACID = "sulfuric_acid"
        private const val SULFUR_DIOXIDE = "sulfur_dioxide"
        private const val STEAM = "steam"
        private const val NITROGEN_DIOXIDE = "nitrogen_dioxide"
    }

    /**
     * Set to true when a neighbor change is detected (via [LeadChamberBlock.neighborChanged] or [LeadChamberBlock.onNeighborChange]).
     */
    @ServerOnly
    var markedForRebuildLinks = false

    fun markForRebuild() {
        markedForRebuildLinks = true
    }

    /**
     * The neighbor cells, indexed by [Direction.get3DDataValue].
     * */
    @ServerOnly
    var linkedCells = Array<LeadChamberBlockEntity?>(6) { null }

    //#region Capability

    /**
     * Fluid handler for the chamber block. Handles dilute sulfuric acid, sulfur dioxide, steam, and nitrogen dioxide.
     * Allows insertion of all, and extraction of dilute sulfuric acid.
     * */
    class FluidHandler(val capacity: Double) : IFractionalFluidHandler {
        companion object {
            const val SULFURIC_ACID_INDEX = 0
            const val SULFUR_DIOXIDE_INDEX = 1
            const val STEAM_INDEX = 2
            const val NITROGEN_DIOXIDE_INDEX = 3

            val sulfuricAcidFluid: Fluid get() = Eln2ForgeFluids.DILUTE_SULFURIC_ACID.get()
            val sulfurDioxideFluid: Fluid get() = Eln2ForgeFluids.SULFUR_DIOXIDE.get()
            val steamFluid: Fluid get() = Eln2ForgeFluids.STEAM.get()
            val nitrogenDioxideFluid: Fluid get() = Eln2ForgeFluids.NITROGEN_DIOXIDE.get()
        }

        var sulfuricAcid = 0.0
        var sulfurDioxide = 0.0
        var steam = 0.0
        var nitrogenDioxide = 0.0

        val totalAmount: Double get() = sulfuricAcid + sulfurDioxide + steam + nitrogenDioxide

        val remainingCapacity: Double get() = (capacity - totalAmount).coerceAtLeast(0.0)

        override fun getFractionalFluidInTank(tank: Int) = when (tank) {
            SULFURIC_ACID_INDEX -> FractionalFluidStack(sulfuricAcidFluid, sulfuricAcid)
            SULFUR_DIOXIDE_INDEX -> FractionalFluidStack(sulfurDioxideFluid, sulfurDioxide)
            STEAM_INDEX -> FractionalFluidStack(steamFluid, steam)
            NITROGEN_DIOXIDE_INDEX -> FractionalFluidStack(nitrogenDioxideFluid, nitrogenDioxide)
            else -> FractionalFluidStack.EMPTY
        }

        override fun getFractionalTankCapacity(tank: Int) = capacity
        override fun getTanks(): Int = 4
        override fun getFluidInTank(tank: Int): FluidStack = getFractionalFluidInTank(tank).quantized()
        override fun getTankCapacity(tank: Int): Int = capacity.toInt()

        override fun isFluidValid(tank: Int, stack: FluidStack): Boolean = when (tank) {
            SULFURIC_ACID_INDEX -> stack.fluid == sulfuricAcidFluid
            SULFUR_DIOXIDE_INDEX -> stack.fluid == sulfurDioxideFluid
            STEAM_INDEX -> stack.fluid == steamFluid
            NITROGEN_DIOXIDE_INDEX -> stack.fluid == nitrogenDioxideFluid
            else -> false
        }

        //#region Filling

        /**
         * Only accepts gases and catalyst: sulfur dioxide, steam, and NOx.
         * Rejects sulfuric acid; it is only produced internally by the simulation.
         */
        override fun fillFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): Double {
            if (resource.isEmpty) {
                return 0.0
            }

            val fluid = resource.fluid

            if (fluid == sulfuricAcidFluid) {
                return 0.0
            }

            val accepted = resource.amount.coerceAtMost(remainingCapacity)

            if (accepted < FractionalFluidStack.EPSILON) {
                return 0.0
            }

            if (action == IFluidHandler.FluidAction.EXECUTE) {
                when (fluid) {
                    sulfurDioxideFluid -> sulfurDioxide += accepted
                    steamFluid -> steam += accepted
                    nitrogenDioxideFluid -> nitrogenDioxide += accepted
                    else -> return 0.0
                }
            }

            return accepted
        }

        override fun fill(resource: FluidStack?, action: IFluidHandler.FluidAction?): Int {
            if (resource == null || action == null) {
                return 0
            }

            return fillFractional(resource.fractional(), action).toInt()
        }

        //#endregion

        //#region Draining

        /**
         * Only allows draining sulfuric acid. Gases and catalyst stay in the chamber.
         */
        override fun drainFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): FractionalFluidStack {
            if (resource.isEmpty) {
                return FractionalFluidStack.EMPTY
            }

            if (resource.fluid != sulfuricAcidFluid) {
                return FractionalFluidStack.EMPTY
            }

            val drained = resource.amount.coerceAtMost(sulfuricAcid)

            if (drained < FractionalFluidStack.EPSILON) {
                return FractionalFluidStack.EMPTY
            }

            if (action == IFluidHandler.FluidAction.EXECUTE) {
                sulfuricAcid -= drained
            }

            return FractionalFluidStack(sulfuricAcidFluid, drained)
        }

        override fun drainFractional(maxDrain: Double, action: IFluidHandler.FluidAction): FractionalFluidStack {
            if (maxDrain < FractionalFluidStack.EPSILON || sulfuricAcid < FractionalFluidStack.EPSILON) {
                return FractionalFluidStack.EMPTY
            }

            val drained = maxDrain.coerceAtMost(sulfuricAcid)

            if (action == IFluidHandler.FluidAction.EXECUTE) {
                sulfuricAcid -= drained
            }

            return FractionalFluidStack(sulfuricAcidFluid, drained)
        }

        override fun drain(resource: FluidStack?, action: IFluidHandler.FluidAction?): FluidStack {
            if (resource == null || action == null) {
                return FluidStack.EMPTY
            }

            return drainFractional(resource.fractional(), action).quantized()
        }

        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction?): FluidStack {
            if (action == null) {
                return FluidStack.EMPTY
            }

            return drainFractional(maxDrain.toDouble(), action).quantized()
        }

        //#endregion
    }

    val tank = FluidHandler(128000.0)
    val tankLazy: LazyOptional<FluidHandler> = LazyOptional.of { tank }

    /**
     * Wrapper for [FluidHandler] that prevents extraction.
     * */
    class InsertOnlyHandler(parent: FluidHandler) : IFractionalFluidHandler by parent {
        override fun drainFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction) = FractionalFluidStack.EMPTY
        override fun drainFractional(maxDrain: Double, action: IFluidHandler.FluidAction) = FractionalFluidStack.EMPTY
        override fun drain(resource: FluidStack?, action: IFluidHandler.FluidAction?): FluidStack = FluidStack.EMPTY
        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction?): FluidStack = FluidStack.EMPTY
    }

    val insertOnlyTank = InsertOnlyHandler(tank)
    val insertOnlyTankLazy: LazyOptional<InsertOnlyHandler> = LazyOptional.of { insertOnlyTank }

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T?> {
        if(cap == ForgeCapabilities.FLUID_HANDLER) {
            if(side != null) {
                return when(side) {
                    Direction.DOWN -> tankLazy.cast()
                    else -> insertOnlyTankLazy.cast()
                }
            }
        }

        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        tankLazy.invalidate()
        insertOnlyTankLazy.invalidate()
    }

    //#endregion

    @ServerOnly
    class LeadChamberSimulation(val blockEntity: LeadChamberBlockEntity) {
        /**
         *
         * */
        fun transformationReaction() {

        }

        fun initialPass() {

        }

        fun transferPass() {

        }

        fun finalizePass() {

        }
    }

    @ServerOnly
    val simulation = LeadChamberSimulation(this)

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)

        pTag.putDouble(SULFURIC_ACID, tank.sulfuricAcid)
        pTag.putDouble(SULFUR_DIOXIDE, tank.sulfurDioxide)
        pTag.putDouble(STEAM, tank.steam)
        pTag.putDouble(NITROGEN_DIOXIDE, tank.nitrogenDioxide)
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)

        tank.sulfuricAcid = pTag.getDouble(SULFURIC_ACID)
        tank.sulfurDioxide = pTag.getDouble(SULFUR_DIOXIDE)
        tank.steam = pTag.getDouble(STEAM)
        tank.nitrogenDioxide = pTag.getDouble(NITROGEN_DIOXIDE)
    }

    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if (!pLevel.isClientSide) {
            ensureRegistered()
            markForRebuild()
        }
    }

    @ServerOnly
    private var registeredWithManager = false

    @ServerOnly
    private fun ensureRegistered() {
        if (!registeredWithManager && level != null && !level!!.isClientSide) {
            LeadChamberExecutionManager.registerBlockEntity(this)
            registeredWithManager = true
        }
    }

    @ServerOnly
    private fun ensureUnregistered() {
        if (registeredWithManager) {
            LeadChamberExecutionManager.unregisterBlockEntity(this)
            registeredWithManager = false
        }
    }

    override fun onChunkUnloaded() {
        ensureUnregistered()
        super.onChunkUnloaded()
    }

    override fun setRemoved() {
        ensureUnregistered()
        super.setRemoved()
    }

    /**
     * Called during the tick, when a neighbor change is detected, after [markedForRebuildLinks] was set.
     */
    @ServerOnly
    fun rebuildLinksIfRequired() {
        requireIsOnServerThread()

        if (!markedForRebuildLinks) {
            return
        }

        markedForRebuildLinks = false

        val pos = blockPos!!
        val level = level as ServerLevel

        for (i in 0 until 6) {
            val face = Direction.from3DDataValue(i)
            val remotePos = pos + face

            if (!level.isLoaded(remotePos)) {
                linkedCells[i] = null
                continue
            }

            linkedCells[i] = level.getBlockEntity(remotePos) as? LeadChamberBlockEntity
        }
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Acid: ${tank.sulfuricAcid.rounded()}" }
        builder.debugInIDE { "Sulfur Dioxide: ${tank.sulfurDioxide.rounded()}" }
        builder.debugInIDE { "Steam: ${tank.steam.rounded()}" }
        builder.debugInIDE { "Nitrogen Dioxide: ${tank.nitrogenDioxide.rounded()}" }
    }
}
