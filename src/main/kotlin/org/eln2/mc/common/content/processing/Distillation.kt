package org.eln2.mc.common.content.processing

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.Fluid
import net.minecraftforge.client.extensions.common.IClientBlockExtensions
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.Mass
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.data.WATT
import org.ageseries.libage.data.WATT_PER_METER_KELVIN
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMass
import org.eln2.mc.ClientOnly
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.LOG
import org.eln2.mc.OnSimulationThread
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.foundation.FlwInstanceTypes
import org.eln2.mc.client.render.foundation.ThermalTint
import org.eln2.mc.client.render.foundation.TransformedLightOverrideInstance
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.blocks.foundation.ReplaceVanillaParticlesBlockExtension
import org.eln2.mc.common.blocks.foundation.UprightHorizontalDirectionCellBlock
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.chemistry.BoilingTransformation
import org.eln2.mc.common.chemistry.CondensationTransformation
import org.eln2.mc.common.chemistry.FluidTransformationManager
import org.eln2.mc.common.chemistry.PhysicalFluidManager
import org.eln2.mc.common.content.ThermalWireObject
import org.eln2.mc.common.content.WrenchInteractable
import org.eln2.mc.common.content.WrenchItem
import org.eln2.mc.common.content.processing.DistillationModuleBlockEntity.Companion.PHASE_CHANGE_RATE
import org.eln2.mc.common.fluids.foundation.*
import org.eln2.mc.common.network.serverToClient.BulkPacketHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.sendBulkPacket
import org.eln2.mc.extensions.eln2StandardBlockProperties
import org.eln2.mc.extensions.getMaterial
import org.eln2.mc.extensions.getQuantity
import org.eln2.mc.extensions.plus
import org.eln2.mc.extensions.putQuantity
import org.eln2.mc.extensions.saveNbt
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.FacingDirection
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.math.min

/**
 * Thermal body.
 * Has a synchronization point around the execution of the subsolver. It is used to execute the distillation logic on the server thread.
 * Also persists the material (which is mutated by the thermal fluid handler).
 * */
class DistillationModuleCell(ci: CellCreateInfo, leakage: ConnectionParameters, val replicatesTemperature: Boolean) :
    Cell(ci),
    SidedThermalFLBR<DistillationModuleCell>,
    SimulationExecutionSubgraph.SynchronizationPointCell<DistillationModuleCell>
{
    companion object {
        val HULL_MATERIAL = ChemicalElement.Copper.asMaterial.copy(
            thermalConductivity = Quantity(3159.0, WATT_PER_METER_KELVIN)
        )

        val HULL_MASS = Quantity(50.0, KILOGRAM)
    }

    override val thermalSize: ThermalSize
        get() = ThermalSize.Any

    @SimObject
    val wire = ThermalWireObject(
        this,
        ThermalMass(HULL_MATERIAL, mass = HULL_MASS),
        leakage
    )

    val sync = ReentrantLock()

    override fun monitorsObject(obj: SimulationObject<*>) : Boolean {
        return obj == wire
    }

    override fun prepareForSubSolverStep(obj: SimulationObject<*>, subSolver: Any) {
        sync.lock()
    }

    override fun endSubSolverStep(obj: SimulationObject<*>, subSolver: Any) {
        sync.unlock()
    }

    @Replicator
    fun replicator(target: InternalTemperatureConsumer) = if(replicatesTemperature) {
        InternalTemperatureReplicatorBehavior(target) {
            !wire.thermalBody.temperature
        }
    }
    else {
        null
    }

    val handle = ThermalObjectBasedFractionalFluidHandlerThermalExpansion.ThermalBodyHandle(
        wire.thermalBody,
        HULL_MATERIAL,
        HULL_MASS,
        sync
    )

    override fun saveCellData() : CompoundTag {
        val tag = CompoundTag()
        tag.put("material", wire.thermalBody.material.saveNbt())
        tag.putQuantity("mass", wire.thermalBody.mass)
        return tag
    }

    override fun loadCellData(tag: CompoundTag) {
        wire.thermalBody.material = tag.getCompound("material").getMaterial()
        wire.thermalBody.mass = tag.getQuantity<Mass>("mass")
    }
}

/**
 * Acts as a pipe for gas to move up from modules. Doesn't have a block entity or tanks.
 * */
class DistillationColumnBlock : HorizontalDirectionalBlock(eln2StandardBlockProperties()) {
    init {
        @Suppress("LeakingThis")
        registerDefaultState(getStateDefinition().any().setValue(
            FACING,
            Direction.NORTH
        ))
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
}

data class DistillationModuleModel(
    val isIncandescent: Boolean,
    val modelSupplier: Supplier<PartialModel>
)

class DistillationModuleBlock(
    val cell: RegistryObject<CellProvider<DistillationModuleCell>>,
    val blockEntityType: RegistryObject<BlockEntityType<DistillationModuleBlockEntity>>,
    val model: DistillationModuleModel
) : UprightHorizontalDirectionCellBlock<DistillationModuleCell>() {
    @Deprecated("Deprecated in Java")
    override fun skipRendering(pState: BlockState, pAdjacentState: BlockState, pDirection: Direction) = true

    override fun initializeClient(consumer: Consumer<IClientBlockExtensions?>) {
        consumer.accept(ReplaceVanillaParticlesBlockExtension)
    }

    override fun getCellProvider() = cell.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = DistillationModuleBlockEntity(pPos, pState)

    override fun <T : BlockEntity?> getTicker(pLevel: Level, pState: BlockState, pBlockEntityType: BlockEntityType<T>): BlockEntityTicker<T>? {
        if(pLevel.isClientSide) {
            return null
        }

        return BlockEntityTicker(DistillationModuleBlockEntity::tick)
    }
}

class DistillationModuleBlockEntity(pos: BlockPos, state: BlockState) :
    CellBlockEntity<DistillationModuleCell>(pos, state, (state.block as DistillationModuleBlock).blockEntityType.get()),
    ComponentDisplay,
    WrenchInteractable,
    InternalTemperatureConsumer,
    BulkPacketHandlerBlockEntity
{
    companion object {
        fun tick(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
            if (pLevel == null || pBlockEntity == null) {
                LOG.error("level or entity null")
                return
            }

            if (pBlockEntity !is DistillationModuleBlockEntity) {
                LOG.error(DEBUGGER_BREAK("Got $pBlockEntity instead of distillation block entity"))
                return
            }

            pBlockEntity.serverTick()
        }

        /**
         * The maximum condensation and evaporation rate (they are independent).
         * */
        private const val PHASE_CHANGE_RATE = 0.5

        /**
         * The total max fluid leaving the module to distribute to horizontal neighbors.
         * */
        private const val MAX_HORIZONTAL_FLOW_RATE = 5.0
    }

    //#region Capability

    val liquidTank = MultipleFractionalFluidTank(1000.0, true, this::setChanged)
    val gasTank = MultipleFractionalFluidTank(1000.0, true, this::setChanged)

    /**
     * Final wrapper around [parent] that accounts for the thermal exchanges.
     * The fill and drain behavior is implemented by [parent], and the thermal fill and drain are implemented by this wrapper, by delegating the actual fluid ops to [parent] and then doing the thermal changes here.
     * @param parent The wrapper that implements the filling/draining behavior.
     *
     * If you're reading this as a reference (heh, another contributor?), then the idea here is this:
     * - We implement normal [IFractionalFluidHandler]s for the faces (that have whatever filtering logic we need).
     * They could be [PurityBasedMultipleFractionalFluidTank] or [GravityBasedMultipleFractionalFluidTank].
     * In our case, [BottomFaceHandler], [SideHandler], [TopFaceHandler].
     * - Pass an instance of that as the [parent].
     *
     * Now, the [ThermalObjectBasedFractionalFluidHandlerThermalExpansion] will compose the thermal transfer logic on top of the transfer logic from [parent].
     * The thermal transfer logic will just mutate our thermal object.
     * */
    class ThermalLayer<P : IFractionalFluidHandler>(val blockEntity: DistillationModuleBlockEntity, override val parent: P) : ThermalObjectBasedFractionalFluidHandlerThermalExpansion {
        override val handle: ThermalObjectBasedFractionalFluidHandlerThermalExpansion.ThermalBodyHandle
            get() = blockEntity.cell.handle

        override val ambientTemperature: Quantity<Temperature>
            get() = blockEntity.cell.environmentData.ambientTemperature

        override fun setSimulationChanged() {
            blockEntity.cell.setChanged()
        }

        /**
         * Gets all lumped fluid in the module, to calculate the thermal properties.
         * */
        override fun getFluidStacks(): Iterable<FractionalFluidStack> =
            blockEntity
                .liquidTank.fluids.asSequence()
                .plus(blockEntity.gasTank.fluids)
                .asIterable()
    }

    /**
     * Fluid handler for the bottom face:
     * - Allows extraction of residuals via [GravityBasedMultipleFluidTank]
     * - Allows insertion of gas and liquid
     * */
    class BottomFaceHandler(val liquidTank: MultipleFractionalFluidTank, val gasTank: MultipleFractionalFluidTank) : GravityBasedMultipleFractionalFluidTank(liquidTank) {
        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            val thermalFluid = PhysicalFluidManager.getProperties(resource.fluid)
                ?: return 0

            return if(thermalFluid.isGaseous) {
                gasTank.fill(resource, action)
            }
            else {
                liquidTank.fill(resource, action)
            }
        }

        override fun fillFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): Double {
            val thermalFluid = PhysicalFluidManager.getProperties(resource.fluid)
                ?: return 0.0

            return if(thermalFluid.isGaseous) {
                gasTank.fillFractional(resource, action)
            }
            else {
                liquidTank.fillFractional(resource, action)
            }
        }
    }

    val bottomFaceHandler = ThermalLayer(this, BottomFaceHandler(liquidTank, gasTank))
    val bottomFaceHandlerLazy: LazyOptional<ThermalLayer<BottomFaceHandler>> = LazyOptional.of { bottomFaceHandler }

    /**
     * Fluid handler for the top face:
     * - Allows extraction of gas via [PurityBasedMultipleFluidTank]
     * - Allows insertion of liquid
     * */
    class TopFaceHandler(val liquidTank: MultipleFractionalFluidTank, gasTank: MultipleFractionalFluidTank) : PurityBasedMultipleFractionalFluidTank(gasTank) {
        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            val thermalFluid = PhysicalFluidManager.getProperties(resource.fluid)
                ?: return 0

            if(thermalFluid.isGaseous) {
                return 0
            }

            return liquidTank.fill(resource, action)
        }

        override fun fillFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): Double {
            val thermalFluid = PhysicalFluidManager.getProperties(resource.fluid)
                ?: return 0.0

            if(thermalFluid.isGaseous) {
                return 0.0
            }

            return liquidTank.fillFractional(resource, action)
        }


    }

    val topFaceHandler = ThermalLayer(this, TopFaceHandler(liquidTank, gasTank))
    val topFaceHandlerLazy: LazyOptional<ThermalLayer<TopFaceHandler>> = LazyOptional.of { topFaceHandler }

    /**
     * Fluid handler for the 4 sides:
     * - Allows extraction of liquids via [PurityBasedMultipleFluidTank]
     * - Allows insertion of liquids
     * - Allows insertion of gas
     * */
    class SideHandler(val liquidTank: MultipleFractionalFluidTank, val gasTank: MultipleFractionalFluidTank) : PurityBasedMultipleFractionalFluidTank(liquidTank) {
        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            val thermalFluid = PhysicalFluidManager.getProperties(resource.fluid)
                ?: return 0

            return if(thermalFluid.isGaseous) {
                gasTank.fill(resource, action)
            }
            else {
                liquidTank.fill(resource, action)
            }
        }

        override fun fillFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): Double {
            val thermalFluid = PhysicalFluidManager.getProperties(resource.fluid)
                ?: return 0.0

            return if(thermalFluid.isGaseous) {
                gasTank.fillFractional(resource, action)
            }
            else {
                liquidTank.fillFractional(resource, action)
            }
        }
    }

    val sideHandler = ThermalLayer(this, SideHandler(liquidTank, gasTank))
    val sideHandlerLazy: LazyOptional<ThermalLayer<SideHandler>> = LazyOptional.of { sideHandler }

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T?> {
        if(cap == ForgeCapabilities.FLUID_HANDLER) {
            if(side != null) {
                return when(side) {
                    Direction.DOWN -> bottomFaceHandlerLazy.cast()
                    Direction.UP -> topFaceHandlerLazy.cast()
                    else -> sideHandlerLazy.cast()
                }
            }
        }

        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        bottomFaceHandlerLazy.invalidate()
        topFaceHandlerLazy.invalidate()
        sideHandlerLazy.invalidate()
    }

    //#endregion

    /**
     * Voids the tanks.
     * */
    override fun applyWrench(wrench: WrenchItem, context: UseOnContext): InteractionResult {
        liquidTank.fluids.clear()
        gasTank.fluids.clear()

        val body = cell.handle.acquire()
        val temperature = body.temperature
        body.material = DistillationModuleCell.HULL_MATERIAL
        body.mass = DistillationModuleCell.HULL_MASS
        body.temperature = temperature
        cell.handle.release()

        setChanged()

        return InteractionResult.SUCCESS
    }

    /**
     * Thermal power calculated from enthalpy.
     * For boiling modules, it describes the power input that goes into driving evaporation.
     * */
    @ServerOnly
    private var phaseChangeThermalPower = 0.0

    //#region Distillation Loop

    // P.S. The algorithms may seem inefficient, but we are only dealing with 1-2 things at a time, so they are good for now.

    private val horizontalNeighbors = Array<DistillationModuleBlockEntity?>(4) { null }
    private val horizontalTransportAmounts = DoubleArray(4)
    private val horizontalTransportIndices = IntArray(4) { -1 }

    /**
     * Distributes fluid with horizontally adjacent distillation modules.
     * This allows building multiblock distillation towers.
     * */
    private fun horizontalDistribution() {
        if(liquidTank.fluids.isEmpty()) {
            return
        }

        val level = level as ServerLevel
        val horizontalNeighbors = horizontalNeighbors

        /**
         * Fetches neighbors.
         * P.S. we can make a cache and invalidate with [Block.neighborChanged], but we don't expect many distillation modules in the world:
         * */
        var hasNeighbors = false
        for (i in 0 until 4) {
            val targetPos = blockPos + FacingDirection.byIndex(i).direction

            horizontalNeighbors[i] = if(level.isLoaded(targetPos)) {
                hasNeighbors = true
                level.getBlockEntity(targetPos) as? DistillationModuleBlockEntity
            }
            else {
                null
            }
        }

        if(!hasNeighbors) {
            return
        }

        val horizontalTransportAmounts = horizontalTransportAmounts
        val horizontalTransportIndices = horizontalTransportIndices

        /**
         * Algorithm: For each fluid we have, we calculate the 4 transfers toward each neighbor so we equalize.
         * */
        val iterator = liquidTank.fluids.iterator()
        while (iterator.hasNext()) {
            val sourceStack = iterator.next()
            val fluid = sourceStack.fluid

            /**
             * The number of neighbors we are transferring to:
             * */
            var transferCount = 0

            for(i in 0 until 4) {
                val neighbor = horizontalNeighbors[i]
                    ?: continue

                val neighborTank = neighbor.liquidTank

                val targetStackIndex = neighborTank.fluids.indexOfFirst {
                    it.fluid == fluid
                }

                horizontalTransportIndices[i] = targetStackIndex

                if(targetStackIndex == -1) {
                    /**
                     * Split in half:
                     * */
                    val amountToTransfer = 0.5 * sourceStack.amount

                    horizontalTransportAmounts[i] = if(amountToTransfer < FractionalFluidStack.EPSILON) {
                        /**
                         * Special case. We will skip transport:
                         * */
                        0.0
                    }
                    else {
                        transferCount++
                        amountToTransfer
                    }
                }
                else {
                    /**
                     * Equalize if we have more than the target:
                     * */
                    val amountToTransfer = 0.5 * (sourceStack.amount - neighborTank.fluids[targetStackIndex].amount)

                    horizontalTransportAmounts[i] = if(amountToTransfer < FractionalFluidStack.EPSILON) {
                        /**
                         * We only transfer if we have more than the neighbor. We will skip transport:
                         * */
                        0.0
                    }
                    else {
                        transferCount++
                        amountToTransfer
                    }
                }
            }

            if(transferCount == 0) {
                /**
                 * Nothing to transfer:
                 * */
                continue
            }

            val recip = 1.0 / transferCount.toDouble()

            /**
             * Calculates the total transfer out of our stack:
             * */
            var totalTransfer = 0.0
            for (i in 0 until 4) {
                val amount = horizontalTransportAmounts[i] * recip

                if(amount < FractionalFluidStack.EPSILON) {
                    horizontalTransportAmounts[i] = 0.0
                }
                else {
                    horizontalTransportAmounts[i] = amount
                    totalTransfer += amount
                }
            }

            if(totalTransfer < FractionalFluidStack.EPSILON) {
                /**
                 * Nothing to transfer:
                 * */
                continue
            }

            if(totalTransfer > MAX_HORIZONTAL_FLOW_RATE) {
                val factor = MAX_HORIZONTAL_FLOW_RATE / totalTransfer

                for (i in 0 until 4) {
                    horizontalTransportAmounts[i] *= factor
                }

                totalTransfer = MAX_HORIZONTAL_FLOW_RATE
            }

            /**
             * Remove from source:
             * */
            sourceStack.amount -= totalTransfer
            if(sourceStack.amount < FractionalFluidStack.EPSILON) {
                iterator.remove()
            }

            setChanged()

            for (i in 0 until 4) {
                val neighbor = horizontalNeighbors[i]
                    ?: continue

                val quantity = horizontalTransportAmounts[i]

                if(quantity < FractionalFluidStack.EPSILON) {
                    continue
                }

                val targetIndex = horizontalTransportIndices[i]

                if(targetIndex == -1) {
                    neighbor.liquidTank.fluids.add(FractionalFluidStack(fluid, quantity))
                }
                else {
                    neighbor.liquidTank.fluids[targetIndex].amount += quantity
                }

                neighbor.setChanged()
            }
        }
    }

    /**
     * Moves liquid to modules below (**ignoring columns**).
     * This basically allows you to make a "module" multiple blocks tall, and separate them with columns.
     * */
    private fun reflux() {
        if(liquidTank.fluids.isEmpty()) {
            return
        }

        val target = level!!.getBlockEntity(blockPos.below()) as? DistillationModuleBlockEntity
            ?: return

        val targetTank = target.liquidTank

        val totalAmount = liquidTank.amount
        val fluidToTransfer = min(targetTank.remainingCapacity, totalAmount)

        if(fluidToTransfer < FractionalFluidStack.EPSILON) {
            return
        }

        val transferFactor = fluidToTransfer / totalAmount

        val iterator = liquidTank.fluids.iterator()
        while (iterator.hasNext()) {
            val sourceStack = iterator.next()
            val fluidToTransfer = sourceStack.amount * transferFactor

            if(fluidToTransfer > FractionalFluidStack.EPSILON) {
                val filledAmount = targetTank.fillFractional(sourceStack.copyWithAmount(fluidToTransfer), IFluidHandler.FluidAction.EXECUTE)

                sourceStack.amount -= filledAmount

                if(sourceStack.amount < FractionalFluidStack.EPSILON) {
                    iterator.remove()
                }

                target.setChanged()
                setChanged()
            }
        }
    }

    private val phaseChangeVisited = HashSet<Fluid>()

    /**
     * Evaporates liquids from the [liquidTank]. The amount to evaporate is, at most, [PHASE_CHANGE_RATE].
     * Fluids with a lower boiling point have priority.
     * */
    private fun evaporation() {
        val body = cell.wire.thermalBody
        var remainingEvaporation = PHASE_CHANGE_RATE

        while (remainingEvaporation > 0.0 && liquidTank.fluids.isNotEmpty() && gasTank.remainingCapacity > 0.0) {
            /**
             * Selects stack with the lowest boiling point:
             * */
            var target: FractionalFluidStack? = null
            var boiling: BoilingTransformation? = null
            for (stack in liquidTank.fluids) {
                if(phaseChangeVisited.contains(stack.fluid)) {
                    continue
                }

                val transformation = FluidTransformationManager.getTransformations(stack.fluid)?.boiling
                    ?: continue

                if(target == null || transformation.temperature < boiling!!.temperature) {
                    target = stack
                    boiling = transformation
                }
            }

            if(target == null) {
                break
            }

            boiling!!

            phaseChangeVisited.add(target.fluid)

            val dT = !body.temperature - !boiling.temperature

            if(dT <= 0.0) {
                /**
                 * No boiling can occur:
                 * */
                break
            }

            /**
             * Calculates the total fluid to boil by applying constraints:
             * */
            var amountToBoil = remainingEvaporation

            /**
             * Constrain by available liquid:
             * */
            amountToBoil = min(amountToBoil, target.amount)

            /**
             * Constrain by the energy limit:
             * */
            amountToBoil = min(amountToBoil, dT * !body.mass * !body.material.specificHeat / !boiling.enthalpy)

            /**
             * Constrain by the remaining capacity in the gas tank:
             * */
            amountToBoil = min(amountToBoil, gasTank.remainingCapacity * 1000.0 / boiling.resultGasProportion)

            if (amountToBoil < FractionalFluidStack.EPSILON) {
                continue
            }

            val gasGenerated = amountToBoil * boiling.resultGasProportion / 1000.0

            if(gasGenerated < FractionalFluidStack.EPSILON) {
                /**
                 * If the amount is too low, then it will round down to 0, so we can't do anything.
                 * */
                continue
            }

            var residueStack: FractionalFluidStack? = null

            if(boiling.resultLiquidResidue != null) {
                val residue = amountToBoil - gasGenerated

                if(residue >= FractionalFluidStack.EPSILON) {
                    /**
                     * Assume quantity lost. It is better not to stall the main transformation for this crap.
                     * */
                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                    residueStack = FractionalFluidStack(boiling.resultLiquidResidue!!, residue)
                }
            }

            liquidTank.drainFractional(FractionalFluidStack(target.fluid, amountToBoil), IFluidHandler.FluidAction.EXECUTE)
            gasTank.fillFractional(FractionalFluidStack(boiling.resultGas, gasGenerated), IFluidHandler.FluidAction.EXECUTE)

            if(residueStack != null) {
                liquidTank.fillFractional(residueStack, IFluidHandler.FluidAction.EXECUTE)
            }

            val enthalpy = Quantity(!boiling.enthalpy * amountToBoil, JOULE)

            phaseChangeThermalPower += !enthalpy
            body.energy -= enthalpy
            remainingEvaporation -= amountToBoil

            setChanged()
        }

        phaseChangeVisited.clear()
    }

    /**
     * Condenses gases from the [gasTank]. The amount to condense is, at most, [PHASE_CHANGE_RATE].
     * Fluids with a higher condensation point have priority.
     * */
    private fun condensation() {
        val body = cell.wire.thermalBody
        var remainingCondensation = PHASE_CHANGE_RATE

        while (remainingCondensation > 0.0 && gasTank.fluids.isNotEmpty() && liquidTank.remainingCapacity > 0.0) {
            /**
             * Selects stack with the highest condensation point:
             * */
            var target: FractionalFluidStack? = null
            var condensation: CondensationTransformation? = null
            for (stack in gasTank.fluids) {
                if(phaseChangeVisited.contains(stack.fluid)) {
                    continue
                }

                val transformation = FluidTransformationManager.getTransformations(stack.fluid)?.condensation
                    ?: continue

                if(target == null || transformation.temperature > condensation!!.temperature) {
                    target = stack
                    condensation = transformation
                }
            }

            if(target == null) {
                break
            }

            phaseChangeVisited.add(target.fluid)

            condensation!!

            val dT = !condensation.temperature - !body.temperature

            if(dT <= 0.0) {
                break
            }

            /**
             * Calculates the total gas to condense by applying constraints:
             * */
            var amountToCondense = remainingCondensation

            /**
             * Constrain by available gas:
             * */
            amountToCondense = min(amountToCondense, target.amount)

            /**
             * Constrain by the energy limit:
             * */
            amountToCondense = min(amountToCondense, dT * !body.mass * !body.material.specificHeat / !condensation.enthalpy)

            /**
             * Constrain by remaining capacity in the liquid tank:
             * */
            amountToCondense = min(amountToCondense, liquidTank.remainingCapacity * 1000.0 / condensation.resultLiquidProportion)

            if(amountToCondense < FractionalFluidStack.EPSILON) {
                continue
            }

            val liquidGenerated = amountToCondense * condensation.resultLiquidProportion / 1000.0

            if(liquidGenerated < FractionalFluidStack.EPSILON) {
                /**
                 * If the amount is too low, then it will round down to 0, so we can't do anything.
                 * */
                continue
            }

            var residueStack: FractionalFluidStack? = null

            if(condensation.resultGasResidue != null) {
                val residue = amountToCondense - liquidGenerated

                if(residue >= FractionalFluidStack.EPSILON) {
                    /**
                     * Assume quantity lost. It is better not to stall the main transformation for this crap.
                     * */
                    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                    residueStack = FractionalFluidStack(condensation.resultGasResidue!!, residue)
                }
            }

            gasTank.drainFractional(FractionalFluidStack(target.fluid, amountToCondense), IFluidHandler.FluidAction.EXECUTE)
            liquidTank.fillFractional(FractionalFluidStack(condensation.resultLiquid, liquidGenerated), IFluidHandler.FluidAction.EXECUTE)

            if(residueStack != null) {
                gasTank.fillFractional(residueStack, IFluidHandler.FluidAction.EXECUTE)
            }

            val enthalpy = Quantity(!condensation.enthalpy * amountToCondense, JOULE)

            phaseChangeThermalPower -= !enthalpy
            body.energy += enthalpy
            remainingCondensation -= amountToCondense

            setChanged()
        }

        phaseChangeVisited.clear()
    }

    /**
     * Executes evaporation and condensation.
     * */
    private fun phaseChange() {
        phaseChangeThermalPower = 0.0

        cell.sync.lock()

        try {
            evaporation()
            condensation()
        }
        finally {
            cell.sync.unlock()
        }

        phaseChangeThermalPower /= 1.0 / 20.0
    }

    /**
     * Pushes [gasTank] into the module above (gas rises). Pushes the lighter gases first.
     * Also follows [DistillationColumnBlock]s to find the module above them.
     * */
    private fun gasOutflow() {
        if (gasTank.fluids.isEmpty()) {
            return
        }

        val currentPos = blockPos.mutable()

        while (true) {
            currentPos.y++

            val block = level!!.getBlockState(currentPos).block

            if(block is DistillationColumnBlock) {
                /**
                 * Move upward:
                 * */
                continue
            }

            if(block !is DistillationModuleBlock) {
                /**
                 * No module to transfer to:
                 * */
                break
            }

            val targetModule = level?.getBlockEntity(currentPos) as? DistillationModuleBlockEntity
                ?: return

            /**
             * Order by lowest density first:
             * */
            val gases = gasTank.fluids.sortedBy {
                PhysicalFluidManager.requireProperties(it.fluid).density
            }

            for (stack in gases) {
                val transfer = targetModule.gasTank.fillFractional(stack, IFluidHandler.FluidAction.EXECUTE)

                if (transfer > 0) {
                    gasTank.drainFractional(FractionalFluidStack(stack.fluid, transfer), IFluidHandler.FluidAction.EXECUTE)
                    setChanged()
                }
                else {
                    break
                }
            }

            break
        }
    }

    /**
     * This order is important.
     * */
    fun serverTick() {
        /**
         * Transports liquids to neighbor modules.
         * */
        horizontalDistribution()

        /**
         * Moves fluid to modules below:
         * */
        reflux()

        /**
         * Performs evaporation/condensation.
         * */
        phaseChange()

        /**
         * Pushes gas up into modules above.
         * */
        gasOutflow()
    }

    //#endregion

    //#region Rendering and Sync

    @ClientOnly
    override val clientSidePacketHandlerLazy = createClientSideHandler()

    class RenderState {
        var temperature = 0.0
    }

    @ClientOnly
    var renderState: RenderState? = null
        private set

    /**
     * Creates the [renderState] if needed.
     * */
    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if(pLevel.isClientSide) {
            renderState = RenderState()
        }
    }

    @ClientOnly
    override fun setupPacketsOnClient(handler: ClientSidePacketHandlerBuilder) {
        handler.withHandler<InternalTemperatureReplicatorBehavior.InternalTemperaturePacket> { packet ->
            renderState!!.temperature = packet.temperature
        }
    }

    @ServerOnly @OnSimulationThread
    override fun onInternalTemperatureChange(temperature: Quantity<Temperature>) {
        sendBulkPacket(InternalTemperatureReplicatorBehavior.InternalTemperaturePacket(!temperature))
    }

    // onSyncSuggested
    override fun getUpdateTag(): CompoundTag {
        sendBulkPacket(InternalTemperatureReplicatorBehavior.InternalTemperaturePacket(!cell.wire.thermalBody.temperature))
        return super.getUpdateTag()
    }

    //#endregion

    //#region Saving

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)
        pTag.put("liquidTank", liquidTank.serializeNBT())
        pTag.put("gasTank", gasTank.serializeNBT())
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)
        liquidTank.deserializeNBT(pTag.getCompound("liquidTank"))
        gasTank.deserializeNBT(pTag.getCompound("gasTank"))
    }

    //#endregion

    @ServerOnly
    override fun submitDisplay(builder: ComponentDisplayList) {
        bottomFaceHandler.parent.debugView(builder)
        builder.quantity(cell.wire.thermalBody.temperature)
        builder.quantity(Quantity(phaseChangeThermalPower, WATT))
    }
}

class DistillationModuleBlockEntityVisual(ctx: VisualizationContext, blockEntity: DistillationModuleBlockEntity, partialTick: Float) :
    AbstractBlockEntityVisual<DistillationModuleBlockEntity>(ctx, blockEntity, partialTick),
    SimpleDynamicVisual
{
    val model = (blockState.block as DistillationModuleBlock).model

    val instance: TransformedLightOverrideInstance = visualizationContext.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, Models.partial(model.modelSupplier.get()))
        .createInstance()
        .also {
            it.translate(visualPosition)
            it.center()
            it.rotateToFace(blockState.getValue(HorizontalDirectionalBlock.FACING))
            it.uncenter()
        }

    private var lastTemperature = 0.0

    override fun beginFrame(p0: DynamicVisual.Context?) {
        if(!model.isIncandescent) {
            return
        }

        val targetTemperature = blockEntity.renderState!!.temperature

        if(targetTemperature != lastTemperature) {
            lastTemperature = targetTemperature
            instance.colorWithOverride(ThermalTint.DEFAULT_LIGHT_OVERRIDE, Quantity(targetTemperature, KELVIN))
            instance.setChanged()
        }
    }

    override fun updateLight(p0: Float) {
        relight(instance)
    }

    override fun collectCrumblingInstances(p0: Consumer<Instance?>) {
        p0.accept(instance)
    }

    override fun _delete() {
        instance.delete()
    }
}
