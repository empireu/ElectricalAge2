package org.eln2.mc.common.content.processing

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluid
import net.minecraftforge.client.extensions.common.IClientBlockExtensions
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ThermalMass
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.LOG
import org.eln2.mc.ServerOnly
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.blocks.foundation.ReplaceVanillaParticlesBlockExtension
import org.eln2.mc.common.blocks.foundation.UprightHorizontalDirectionCellBlock
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.chemistry.BoilingTransformation
import org.eln2.mc.common.chemistry.CondensationTransformation
import org.eln2.mc.common.chemistry.FluidTransformationManager
import org.eln2.mc.common.chemistry.ThermalFluidManager
import org.eln2.mc.common.content.ThermalWireObject
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.fluids.foundation.FractionalFluidStack
import org.eln2.mc.common.fluids.foundation.GravityBasedMultipleFluidTank
import org.eln2.mc.common.fluids.foundation.GravityBasedMultipleFractionalFluidTank
import org.eln2.mc.common.fluids.foundation.MultipleFluidTank
import org.eln2.mc.common.fluids.foundation.MultipleFractionalFluidTank
import org.eln2.mc.common.fluids.foundation.PurityBasedMultipleFluidTank
import org.eln2.mc.common.fluids.foundation.PurityBasedMultipleFractionalFluidTank
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import kotlin.math.floor
import kotlin.math.min

/**
 * Thermal body. Has a synchronization point around the execution of the subsolver. It is used to execute the distillation logic on the server thread.
 * */
class DistillationModuleCell(ci: CellCreateInfo) :
    Cell(ci),
    SidedThermalFLBR<DistillationModuleCell>,
    SimulationExecutionSubgraph.SynchronizationPointCell<DistillationModuleCell>
{

    override val thermalSize: ThermalSize
        get() = ThermalSize.Any

    @SimObject
    val wire = ThermalWireObject(
        this,
        ThermalMass(
            ChemicalElement.Copper.asMaterial,
            mass = Quantity(30.0, KILOGRAM)
        )
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
}

class DistillationModuleBlock : UprightHorizontalDirectionCellBlock<DistillationModuleCell>() {
    override fun initializeClient(consumer: Consumer<IClientBlockExtensions?>) {
        consumer.accept(ReplaceVanillaParticlesBlockExtension)
    }

    @Deprecated("Deprecated in Java")
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean = true

    override fun getCellProvider() = Eln2Processing.DISTILLATION_MODULE_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = DistillationModuleBlockEntity(pPos, pState)

    override fun <T : BlockEntity?> getTicker(pLevel: Level, pState: BlockState, pBlockEntityType: BlockEntityType<T>): BlockEntityTicker<T>? {
        if(pLevel.isClientSide) {
            return null
        }

        return BlockEntityTicker(DistillationModuleBlockEntity::tick)
    }
}

class DistillationModuleBlockEntity(pos: BlockPos, state: BlockState) : CellBlockEntity<DistillationModuleCell>(pos, state, Eln2Processing.DISTILLATION_MODULE_MAIN_BLOCK_ENTITY.get()), ComponentDisplay {
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

        private const val PHASE_CHANGE_RATE = 0.5
    }

    //#region Fluid Handling

    val liquidTank = MultipleFractionalFluidTank(32000.0, true)
    val gasTank = MultipleFractionalFluidTank(1024.0, true)

    /**
     * Fluid handler for the bottom face:
     * - Allows extraction of residuals via [GravityBasedMultipleFluidTank]
     * - Allows insertion of gas and liquid
     * */
    class BottomFaceHandler(val liquidTank: MultipleFractionalFluidTank, val gasTank: MultipleFractionalFluidTank) : GravityBasedMultipleFractionalFluidTank(liquidTank) {
        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            val thermalFluid = ThermalFluidManager.getThermalFluid(resource.fluid)
                ?: return 0

            return if(thermalFluid.isGaseous) {
                gasTank.fill(resource, action)
            }
            else {
                liquidTank.fill(resource, action)
            }
        }
    }

    val bottomFaceHandler = BottomFaceHandler(liquidTank, gasTank)
    val bottomFaceHandlerLazy: LazyOptional<BottomFaceHandler> = LazyOptional.of { bottomFaceHandler }

    /**
     * Fluid handler for the top face:
     * - Allows extraction of gas via [PurityBasedMultipleFluidTank]
     * - Allows insertion of liquid
     * */
    class TopFaceHandler(val liquidTank: MultipleFractionalFluidTank, gasTank: MultipleFractionalFluidTank) : PurityBasedMultipleFractionalFluidTank(gasTank) {
        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            val thermalFluid = ThermalFluidManager.getThermalFluid(resource.fluid)
                ?: return 0

            if(thermalFluid.isGaseous) {
                return 0
            }

            return liquidTank.fill(resource, action)
        }
    }

    val topFaceHandler = TopFaceHandler(liquidTank, gasTank)
    val topFaceHandlerLazy: LazyOptional<TopFaceHandler> = LazyOptional.of { topFaceHandler }

    /**
     * Fluid handler for the 4 sides:
     * - Allows extraction of liquids via [PurityBasedMultipleFluidTank]
     * - Allows insertion of liquids
     * */
    class SideHandler(val liquidTank: MultipleFractionalFluidTank) : PurityBasedMultipleFractionalFluidTank(liquidTank) {
        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            val thermalFluid = ThermalFluidManager.getThermalFluid(resource.fluid)
                ?: return 0

            if(thermalFluid.isGaseous) {
                return 0
            }

            return liquidTank.fill(resource, action)
        }
    }

    val sideHandler = SideHandler(liquidTank)
    val sideHandlerLazy: LazyOptional<SideHandler> = LazyOptional.of { sideHandler }

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

    //#region Distillation Loop

    // P.S. The algorithms may seem inefficient, but we are only dealing with 1-2 things at a time, so they are good for now.

    /**
     * Pushes [gasTank] into the module above (gas rises). Pushes the lighter gases first.
     * */
    private fun gasTransport() {
        if (gasTank.fluids.isEmpty()) {
            return
        }

        val targetModule = level?.getBlockEntity(blockPos.above()) as? DistillationModuleBlockEntity
            ?: return

        /**
         * Order by lowest density first:
         * */
        val gases = gasTank.fluids.sortedBy {
            ThermalFluidManager.requireThermalFluid(it.fluid).density
        }

        for (stack in gases) {
            val transfer = targetModule.gasTank.fillFractional(stack, IFluidHandler.FluidAction.EXECUTE)

            if (transfer > 0) {
                gasTank.drainFractional(FractionalFluidStack(stack.fluid, transfer), IFluidHandler.FluidAction.EXECUTE)
            }
            else {
                break
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


            if(boiling.resultLiquidResidue != null) {
                val residue = amountToBoil - gasGenerated

                if(residue < FractionalFluidStack.EPSILON) {
                    continue
                }

                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                liquidTank.fillFractional(FractionalFluidStack(boiling.resultLiquidResidue!!, residue), IFluidHandler.FluidAction.EXECUTE)
            }

            liquidTank.drainFractional(FractionalFluidStack(target.fluid, amountToBoil), IFluidHandler.FluidAction.EXECUTE)
            gasTank.fillFractional(FractionalFluidStack(boiling.resultGas, gasGenerated), IFluidHandler.FluidAction.EXECUTE)

            body.energy -= Quantity(!boiling.enthalpy * amountToBoil, JOULE)
            remainingEvaporation -= amountToBoil
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
             * Constrain by remaining liquid capacity:
             * */
            amountToCondense = min(amountToCondense, liquidTank.remainingCapacity)

            if(amountToCondense < FractionalFluidStack.EPSILON) {
                continue
            }

            gasTank.drainFractional(FractionalFluidStack(target.fluid, amountToCondense), IFluidHandler.FluidAction.EXECUTE)
            liquidTank.fillFractional(FractionalFluidStack(condensation.resultLiquid, amountToCondense), IFluidHandler.FluidAction.EXECUTE)

            body.energy += Quantity(!condensation.enthalpy * amountToCondense, JOULE)
            remainingCondensation -= amountToCondense
        }

        phaseChangeVisited.clear()
    }

    /**
     * Executes evaporation and condensation.
     * */
    private fun phaseChange() {
        cell.sync.lock()

        try {
            evaporation()
            condensation()
        }
        finally {
            cell.sync.unlock()
        }
    }

    fun serverTick() {
        phaseChange()
        gasTransport()
    }

    //#endregion

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

    @ServerOnly
    override fun submitDisplay(builder: ComponentDisplayList) {
        bottomFaceHandler.debugView(builder)
        builder.quantity(cell.wire.thermalBody.temperature)
    }
}
