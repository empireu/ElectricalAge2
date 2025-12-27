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
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.client.extensions.common.IClientBlockExtensions
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMass
import org.eln2.mc.*
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
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.fluids.foundation.*
import org.eln2.mc.common.network.serverToClient.BulkPacketHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.sendBulkPacket
import org.eln2.mc.data.Locators
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.math.min

/*
 * This system uses a novel architecture: exploiting the cell graph's construction to build a multiblock and execute finely-grained server thread logic.
 * I'm basically getting an event-based, non-polling incremental structure builder. This is done by listening to new connections in the cells and building the structure incrementally.
 * The structure is made of cells, which are always persistent and not subject to chunk unloads. When we want to execute logic, we simply exclude the elements that are unloaded.
 *
 * The distillation tower itself is a pseudo-multiblock, in the sense that each block is aware of neighbors (see below what neighbors are), and exchanges mass and energy with them.
 * Neighbors are other distillation module block entities. Given a module, its neighbors may or may not be direct in-world neighbors (the 6 adjacent blocks in the Moore neighborhood).
 * That distinction happens when distillation columns are used. Those don't simulate or hold anything; they are structural elements that connect modules vertically. They act as perfectly insulated and volume-less pipes.
 * Internally, we build a sixtuply-linked grid, where each cell is a distillation module. Each module cell has 6 pointers to each neighbor, and they are fetched when the graph loads or when the structure changes.
 * We can find when the structure changes based only on cell add/remove events. See how it's done in the column block entity and module block entity.
 *
 * Also, the column cell is a "symbolic cell", that doesn't have any simulation objects. To get it to connect, we override the connection rules to inject connections between the column cells other column cells, or module cells.
 *
 * The distillation module cell has a thermal body, which participates in the thermal simulation.
 * It also does the distillation and multiblock transfer logic, which are done on the server thread.
 * To accomplish that, we use the finely grained server thread subscriber pool. It allows us to execute logic in multiple passes before the simulations are dispatched on the pool.
 * This also means we don't need locking during the simulation (though we still set up a solver synchronization point, that is acquired when the thermal handler capability is accessed, to mutate the thermal body. That happens during block entity ticks and whatnot, so we need it).
 * We do the simulation in 3 phases:
 *
 * 1. Evaporation/Condensation and Transfer Recording
 *  - Phase change occurs (internal process, that basically transforms liquids into gases and gases into liquids within the module, and also twiddles with the thermal body's energy and composition)
 *  - All fluid transfers to neighbors are recorded in a buffer
 * 2. Execute Transfers
 *  - All mass transfers are scaled based on the capacity in each receiving tank
 *  - Then, mass transfers and energy transfers are executed
 * 3. Recompute Properties
 *  - If a module received/sent any fluid in the second phase, it recalculates the thermal body's composition and mass.
 * */

/**
 * Symbolic cell, used only to facilitate the linkage between [DistillationModuleCell].
 * */
class DistillationColumnCell(ci: CellCreateInfo) : Cell(ci) {
    override fun allowsConnection(remote: Cell): Boolean {
        if(remote !is DistillationColumnCell && remote !is DistillationModuleCell) {
            return false
        }

        val localBlockPos = locator.requireLocator(Locators.BLOCK)
        val remoteBlockPos = remote.locator.requireLocator(Locators.BLOCK)

        return remoteBlockPos == localBlockPos.above() || remoteBlockPos == localBlockPos.below()
    }
}

/**
 * Acts as a pipe for gas to move up from modules.
 * */
class DistillationColumnBlock : UprightHorizontalDirectionCellBlock<DistillationColumnCell>() {
    override fun getCellProvider() = Eln2Processing.DISTILLATION_COLUMN_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = DistillationColumnBlockEntity(pPos, pState)

    override fun spatialNeighborScan(level: Level, results: HashSet<CellAndContainerHandle>, cell: Cell) {
        val pos = cell.locator.requireLocator(Locators.BLOCK)

        Base6Direction3dMask.VERTICALS.forEach { dir ->
            val targetBlockEntity = level.getBlockEntity(pos + dir)

            // For some reason, the smart cast doesn't build if I combine the conditions
            if(targetBlockEntity is DistillationColumnBlockEntity) {
                results.add(CellAndContainerHandle.captureInScope(targetBlockEntity.cell))
            }
            else if(targetBlockEntity is DistillationModuleBlockEntity) {
                results.add(CellAndContainerHandle.captureInScope(targetBlockEntity.cell))
            }
        }
    }
}

class DistillationColumnBlockEntity(pos: BlockPos, state: BlockState) : CellBlockEntity<DistillationColumnCell>(pos, state, Eln2Processing.DISTILLATION_COLUMN_BLOCK_ENTITY.get()), ComponentDisplay {
    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Graph: ${cell.graph.id}" }
    }

    private fun sendNotification() {
        val visited = HashSet<Cell>()
        val queue = ArrayDeque<Cell>()

        queue.addAll(cell.connections)

        while (queue.isNotEmpty()) {
            val front = queue.removeFirst()

            if(!visited.add(front)) {
                continue
            }

            if(front is DistillationModuleCell) {
                front.markForRebuild()
            }
            else {
                queue.addAll(front.connections)
            }
        }
    }

    override fun onCellDisconnected(actualCell: Cell, remoteCell: Cell) {
        sendNotification()
        super.onCellDisconnected(actualCell, remoteCell)
    }

    override fun onCellConnected(actualCell: Cell, remoteCell: Cell) {
        sendNotification()
        super.onCellConnected(actualCell, remoteCell)
    }
}

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

    /**
     * Used when the block entity's capability is accessed.
     * */
    val solverLock = SimulationExecutionSubgraph
        .SynchronizationPrimitive
        .Reentrant()

    val handle = ThermalObjectBasedFractionalFluidHandlerThermalExpansion.ThermalBodyHandle(
        wire.thermalBody,
        HULL_MATERIAL,
        HULL_MASS,
        solverLock.sync
    )

    override fun createSynchronizationMapForSubSolvers(obj: SimulationObject<*>, subSolvers: List<Any>): Map<Any, SimulationExecutionSubgraph.SynchronizationPrimitive>? {
        if(obj == wire) {
            check(subSolvers.size == 1)

            return mapOf(subSolvers.first() to solverLock)
        }

        return DEBUGGER_BREAK(null)
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

    /**
     * Permit connections with the columns.
     * */
    override fun allowsConnection(remote: Cell): Boolean {
        if(remote is DistillationColumnCell) {
            val localBlockPos = locator.requireLocator(Locators.BLOCK)
            val remoteBlockPos = remote.locator.requireLocator(Locators.BLOCK)

            return remoteBlockPos == localBlockPos.above() || remoteBlockPos == localBlockPos.below()
        }

        return super.allowsConnection(remote)
    }

    //#region Server Thread Code

    @OnServerThread
    override fun onWorldLoadedPreSim() {
        markForRebuild()
        super.onWorldLoadedPreSim()
    }

    @OnServerThread
    private var markedForRebuildLinks = false

    @OnServerThread
    var linkedCells = Array<DistillationModuleCell?>(6) { null }

    /**
     * The complete distillation simulation. It handles phase change and thermal fluid transfer to neighbors.
     * The thermal fluid transfer is implemented in multiple passes, like a cellular automata.
     * All logic runs on the game thread (because we are accessing the fluid storage a lot), before the simulation runs, so it doesn't need locks during the simulation.
     * */
    @OnServerThread
    class DistillationSimulation(val cell: DistillationModuleCell) {
        companion object {
            // P.S. don't adjust this array, the indices are used in logic.
            val LIQUID_TARGETS = intArrayOf(
                Direction.NORTH.get3DDataValue(),
                Direction.WEST.get3DDataValue(),
                Direction.SOUTH.get3DDataValue(),
                Direction.EAST.get3DDataValue(),
                Direction.DOWN.get3DDataValue()
            )

            const val MAX_PHASE_CHANGE_RATE = 1.0
            const val MAX_LIQUID_FLOW_RATE = 1.0
            const val MAX_GAS_FLOW_RATE = 5.0
        }

        //#region Setup State

        /**
         * Temperature stored at the start of the simulation. All fluids exiting will be exiting at this temperature.
         * */
        var transferTemperature = Quantity<Temperature>(Double.NaN)
            private set

        /**
         * The block entity associated with the [cell].
         * */
        var blockEntity: DistillationModuleBlockEntity? = null
            private set

        /**
         * The block entities associated with each cell in [linkedCells]. All of them are in scope.
         * */
        val targetBlockEntities = Array<DistillationModuleBlockEntity?>(6) { null }

        /**
         * If true, the [blockEntity] is not in scope.
         * */
        private var skipSimulation = false

        //#endregion

        @Suppress("NOTHING_TO_INLINE")
        class TransferBuffer {
            var count = 0
            var fluidArray = emptyArray<Fluid>()
            var amountArray = DoubleArray(0)
            var targetTankArray = ByteArray(0)

            enum class TargetTank(val id: Byte) {
                Liquid(1),
                Gas(2);

                companion object {
                    inline fun of(id: Byte) = when(id.toInt()) {
                        1 -> Liquid
                        2 -> Gas
                        else -> error("Invalid target tank $id")
                    }
                }
            }

            inline fun push(fluid: Fluid, amount: Double, targetTank: TargetTank) {
                if(count == fluidArray.size) {
                    fluidArray = fluidArray.toList().plus(Fluids.EMPTY).toTypedArray()
                    amountArray = amountArray.toList().plus(Double.NaN).toDoubleArray()
                    targetTankArray = targetTankArray.toList().plus(-1).toByteArray()
                }

                fluidArray[count] = fluid
                amountArray[count] = amount
                targetTankArray[count] = targetTank.id
                ++count
            }

            fun clear() {
                count = 0

                for (i in 0 until fluidArray.size) {
                    fluidArray[i] = Fluids.EMPTY
                    amountArray[i] = Double.NaN
                    targetTankArray[i] = -1
                }
            }
        }

        val outboundBuffers = Array<TransferBuffer>(6) { TransferBuffer() }

        /**
         * Set when transfers occur. Marks that a recalculation of the composition is needed.
         * */
        var hasTransferred = false

        /**
         * Fetches the block entity associated with [cell] into [blockEntity] and, if that's all good and in scope, fetches the target block entities that are in scope and loads them into [targetBlockEntities].
         * If [blockEntity] is loaded and there is at least one neighbor in [targetBlockEntities], sets [skipSimulation] to `false`.
         * */
        fun prepareForSimulation() {
            transferTemperature = cell.wire.thermalBody.temperature

            skipSimulation = true

            if(cell.container == null) {
                /**
                 * The block entity is unloaded.
                 * */
                return
            }

            skipSimulation = false

            blockEntity = cell.container as DistillationModuleBlockEntity

            for (i in 0 until 6) {
                val targetCell = cell.linkedCells[i]
                    ?: continue

                val blockPos = targetCell.locator.requireLocator(Locators.BLOCK)

                if(!cell.graph.level.isLoaded(blockPos)) {
                    /**
                     * Skip (it's a horizontal neighbor, most likely).
                     * */
                    continue
                }

                val targetBlockEntity = targetCell.container as? DistillationModuleBlockEntity

                if(targetBlockEntity == null) {
                    DEBUGGER_BREAK()
                    continue
                }

                targetBlockEntities[i] = targetBlockEntity
            }
        }

        private val phaseChangeVisited = HashSet<Fluid>()

        /**
         * Evaporates liquids from the liquid tank. The amount to evaporate is, at most, [MAX_PHASE_CHANGE_RATE].
         * Fluids with a lower boiling point have priority.
         * */
        private fun evaporation() {
            val blockEntity = blockEntity!!
            val liquidTank = blockEntity.liquidTank
            val gasTank = blockEntity.gasTank

            val body = cell.wire.thermalBody
            var remainingEvaporation = MAX_PHASE_CHANGE_RATE
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
                val temperature = body.temperature
                val dT = !temperature - !boiling.temperature

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

                val liquidProperties = PhysicalFluidManager.requireProperties(target.fluid)
                val gasProperties = PhysicalFluidManager.requireProperties(boiling.resultGas)

                val liquidCapacity = amountToBoil * !liquidProperties.specificHeatCapacity
                val gasCapacity = gasGenerated * !gasProperties.specificHeatCapacity

                val residueCapacity = if (residueStack != null) {
                    val residueProperties = PhysicalFluidManager.requireProperties(residueStack.fluid)
                    residueStack.amount * !residueProperties.specificHeatCapacity
                }
                else {
                    0.0
                }

                liquidTank.drainFractional(FractionalFluidStack(target.fluid, amountToBoil), IFluidHandler.FluidAction.EXECUTE)
                gasTank.fillFractional(FractionalFluidStack(boiling.resultGas, gasGenerated), IFluidHandler.FluidAction.EXECUTE)

                if(residueStack != null) {
                    liquidTank.fillFractional(residueStack, IFluidHandler.FluidAction.EXECUTE)
                }

                val sensibleCorrection = ((gasCapacity + residueCapacity) - liquidCapacity) * !temperature
                val latentHeat = !boiling.enthalpy * gasGenerated

                body.energy += Quantity(sensibleCorrection - latentHeat, JOULE)
                remainingEvaporation -= amountToBoil

                cell.setChanged()
                blockEntity.setChanged()
                blockEntity.recalculateBody()
            }

            phaseChangeVisited.clear()
        }

        /**
         * Condenses gases from the gas tank. The amount to condense is, at most, [MAX_PHASE_CHANGE_RATE].
         * Fluids with a higher condensation point have priority.
         * */
        private fun condensation() {
            val blockEntity = blockEntity!!
            val liquidTank = blockEntity.liquidTank
            val gasTank = blockEntity.gasTank

            val body = cell.wire.thermalBody
            var remainingCondensation = MAX_PHASE_CHANGE_RATE
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

                val temperature = body.temperature
                val dT = !condensation.temperature - !temperature

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

                val gasProperties = PhysicalFluidManager.requireProperties(target.fluid)
                val liquidProperties = PhysicalFluidManager.requireProperties(condensation.resultLiquid)

                val gasCapacity = amountToCondense * !gasProperties.specificHeatCapacity
                val liquidCapacity = liquidGenerated * !liquidProperties.specificHeatCapacity

                val residueCapacity = if (residueStack != null) {
                    val residueProperties = PhysicalFluidManager.requireProperties(residueStack.fluid)
                    residueStack.amount * !residueProperties.specificHeatCapacity
                }
                else {
                    0.0
                }

                gasTank.drainFractional(FractionalFluidStack(target.fluid, amountToCondense), IFluidHandler.FluidAction.EXECUTE)
                liquidTank.fillFractional(FractionalFluidStack(condensation.resultLiquid, liquidGenerated), IFluidHandler.FluidAction.EXECUTE)

                if(residueStack != null) {
                    gasTank.fillFractional(residueStack, IFluidHandler.FluidAction.EXECUTE)
                }

                val sensibleCorrection = ((liquidCapacity + residueCapacity) - gasCapacity) * !temperature
                val latentHeat = !condensation.enthalpy * liquidGenerated

                body.energy += Quantity(sensibleCorrection + latentHeat, JOULE)
                remainingCondensation -= amountToCondense

                cell.setChanged()
                blockEntity.setChanged()
                blockEntity.recalculateBody()
            }

            phaseChangeVisited.clear()
        }

        /**
         * Executes evaporation and condensation.
         * */
        fun phaseChange() {
            if(skipSimulation) {
                return
            }

            evaporation()
            condensation()
        }

        /**
         * Records liquids sent to neighbors.
         * We equalize with horizontal neighbors (acting as a large multiblock tank) and push to the neighbor under (condenser reflux).
         * */
        private fun recordOutgoingLiquids() {
            val liquidTank = blockEntity!!.liquidTank

            if(liquidTank.fluids.isEmpty()) {
                return
            }

            /**
             * Algorithm: For each fluid we have, we calculate the 5 transfers toward each neighbor. For the horizontals, we try to equalize with them, and for downward, we try to push all.
             * We need a temporary buffer, where we write the liquid to transfer to each neighbor not taking into account the previous transfers we calculated.
             * When we have all of those, we can normalize them so they add up to, at most, the amount of fluid we have, and we also apply the transfer rate limit.
             * */
            val transferList = DoubleArray(5)
            for (fluidIdx in 0 until liquidTank.fluids.size) {
                transferList.fill(0.0)

                val sourceStack = liquidTank.fluids[fluidIdx]
                val fluid = sourceStack.fluid

                /**
                 * The number of neighbors we are transferring to, used to normalize:
                 * */
                var transferCount = 0
                for (j in 0 until 5) {
                    val neighbor = targetBlockEntities[LIQUID_TARGETS[j]]
                        ?: continue

                    val neighborTank = neighbor.liquidTank
                    var amountInNeighbor = 0.0
                    for (i in neighborTank.fluids.indices) {
                        val stack = neighborTank.fluids[i]

                        if(stack.fluid == fluid) {
                            amountInNeighbor = stack.amount
                            break
                        }
                    }

                    /**
                     * For the reflux tank, transfer regardless:
                     * */
                    if(j == 4) {
                        ++transferCount
                        transferList[j] = sourceStack.amount
                    }
                    /**
                     * For everything else, try to equalize:
                     * */
                    else {
                        val transfer = 0.5 * (sourceStack.amount - amountInNeighbor)

                        transferList[j] = if(transfer < FractionalFluidStack.EPSILON) {
                            /**
                             * We only transfer if we have more than the neighbor. We will skip transport:
                             * */
                            0.0
                        }
                        else {
                            transferCount++
                            transfer
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
                for (j in 0 until 5) {
                    val amount = transferList[j] * recip

                    if(amount < FractionalFluidStack.EPSILON) {
                        transferList[j] = 0.0
                    }
                    else {
                        transferList[j] = amount
                        totalTransfer += amount
                    }
                }

                if(totalTransfer < FractionalFluidStack.EPSILON) {
                    /**
                     * Nothing to transfer:
                     * */
                    continue
                }

                /**
                 * Calculates factor to cap by:
                 * */
                val flowRateFactor = if(totalTransfer > MAX_LIQUID_FLOW_RATE) {
                    MAX_LIQUID_FLOW_RATE / totalTransfer
                }
                else {
                    1.0
                }

                /**
                 * Push valid transfers to buffer:
                 * */
                for(j in 0 until 5) {
                    val amount = transferList[j] * flowRateFactor

                    if(amount < FractionalFluidStack.EPSILON) {
                        continue
                    }

                    val buffer = outboundBuffers[LIQUID_TARGETS[j]]

                    buffer.push(fluid, amount, TransferBuffer.TargetTank.Liquid)
                }
            }
        }

        /**
         * Records gases rising to the neighbor above.
         * */
        private fun recordOutgoingGases() {
            val neighborDir = Direction.UP.get3DDataValue()

            if(targetBlockEntities[neighborDir] == null) {
                return
            }

            val gasTank = blockEntity!!.gasTank

            if (gasTank.fluids.isEmpty()) {
                return
            }

            /**
             * Instead of equalized transfer, we actually push by density (as if the lighter gas rises first):
             * */
            gasTank.fluids.sortBy {
                PhysicalFluidManager.requireProperties(it.fluid).density
            }

            val buffer = outboundBuffers[neighborDir]
            var remainingTransfer = MAX_GAS_FLOW_RATE
            for (fluidIdx in 0 until gasTank.fluids.size) {
                val sourceStack = gasTank.fluids[fluidIdx]

                val transfer = min(sourceStack.amount, remainingTransfer)
                buffer.push(sourceStack.fluid, transfer, TransferBuffer.TargetTank.Gas)
                remainingTransfer -= transfer

                if(remainingTransfer < FractionalFluidStack.EPSILON) {
                    break
                }
            }
        }

        /**
         * Calculates the transfers to each neighbor (upper bound, we can't calculate them exactly since multiple modules might push into that neighbor, and we can scrape against the fluid capacity) and stores them in [outboundBuffers].
         * */
        fun initialPass() {
            if(skipSimulation) {
                return
            }

            recordOutgoingLiquids()
            recordOutgoingGases()
        }

        /**
         * Second pass.
         * Now that the proposed transfers are all known, we can normalize them based on the remaining capacity and execute mass and energy transfer.
         * */
        fun transferPass() {
            if(skipSimulation) {
                return
            }

            val blockEntity = blockEntity!!
            val targetBlockEntities = targetBlockEntities

            /**
             * First, we calculate the total incoming fluid into this cell
             * */
            var incomingLiquid = 0.0
            var incomingGas = 0.0
            for (i in 0 until 6) {
                val neighbor = targetBlockEntities[i]
                    ?: continue

                /**
                 * Gets the fluids coming from the neighbor into this cell:
                 * */
                val incomingBuffer = neighbor.cell.distillation.outboundBuffers[
                    Direction.from3DDataValue(i).opposite.get3DDataValue()
                ]

                for (message in 0 until incomingBuffer.count) {
                    val amount = incomingBuffer.amountArray[message]
                    val type = TransferBuffer.TargetTank.of(incomingBuffer.targetTankArray[message])

                    when(type) {
                        TransferBuffer.TargetTank.Liquid -> incomingLiquid += amount
                        TransferBuffer.TargetTank.Gas -> incomingGas += amount
                    }
                }
            }

            val liquidTank = blockEntity.liquidTank
            val gasTank = blockEntity.gasTank

            val remainingLiquidCapacity = liquidTank.remainingCapacity
            val remainingGasCapacity = gasTank.remainingCapacity

            val liquidFactor = if(incomingLiquid > remainingLiquidCapacity) {
                remainingLiquidCapacity / incomingLiquid
            }
            else {
                1.0
            }

            val gasFactor = if(incomingGas > remainingGasCapacity) {
                remainingGasCapacity / incomingGas
            }
            else {
                1.0
            }

            /**
             * Execute transfers:
             * */
            val thermalBody = cell.wire.thermalBody
            for (i in 0 until 6) {
                val neighbor = targetBlockEntities[i]
                    ?: continue

                /**
                 * Gets the fluids coming from the neighbor into this cell:
                 * */
                val incomingBuffer = neighbor.cell.distillation.outboundBuffers[
                    Direction.from3DDataValue(i).opposite.get3DDataValue()
                ]

                for (message in 0 until incomingBuffer.count) {
                    var amount = incomingBuffer.amountArray[message]
                    val type = TransferBuffer.TargetTank.of(incomingBuffer.targetTankArray[message])

                    amount *= when(type) {
                        TransferBuffer.TargetTank.Liquid -> {
                            liquidFactor
                        }

                        TransferBuffer.TargetTank.Gas -> {
                            gasFactor
                        }
                    }

                    if(amount < FractionalFluidStack.EPSILON) {
                        /**
                         * Skip transfer:
                         * */
                        continue
                    }

                    /**
                     * Execute mass transfer:
                     * */
                    val resource = FractionalFluidStack(incomingBuffer.fluidArray[message], amount)
                    when(type) {
                        TransferBuffer.TargetTank.Liquid -> {
                            liquidTank.fillFractional(resource, IFluidHandler.FluidAction.EXECUTE)
                            neighbor.liquidTank.drainFractional(resource, IFluidHandler.FluidAction.EXECUTE)
                        }
                        TransferBuffer.TargetTank.Gas -> {
                            gasTank.fillFractional(resource, IFluidHandler.FluidAction.EXECUTE)
                            neighbor.gasTank.drainFractional(resource, IFluidHandler.FluidAction.EXECUTE)
                        }
                    }

                    /**
                     * Execute energy transfer:
                     * */
                    val fluidProperties = PhysicalFluidManager.requireProperties(resource.fluid)
                    val energy = Quantity(amount * !fluidProperties.specificHeatCapacity * !neighbor.cell.distillation.transferTemperature, JOULE)
                    thermalBody.energy += energy
                    neighbor.cell.wire.thermalBody.energy -= energy

                    blockEntity.setChanged()
                    cell.setChanged()
                    hasTransferred = true

                    neighbor.setChanged()
                    neighbor.cell.setChanged()
                    neighbor.cell.distillation.hasTransferred = true
                }
            }
        }

        /**
         * Third and final pass.
         * If we transferred anything, then we need to recalculate the thermal body's material and mass.
         * */
        fun finalizePass() {
            if(!hasTransferred) {
                return
            }

            blockEntity!!.recalculateBody()
        }

        fun clear() {
            skipSimulation = false
            blockEntity = null

            for (dir in 0 until 6) {
                targetBlockEntities[dir] = null
                outboundBuffers[dir].clear()
            }

            hasTransferred = false
        }
    }

    val distillation = DistillationSimulation(this)

    /**
     * Marks that the structure of the multiblock has changed, and the linked modules will be rebuilt on the next [serverTickStart].
     * */
    @OnServerThread
    fun markForRebuild() {
        markedForRebuildLinks = true
    }

    /**
     * Registers 3 passes on the server thread.
     * All of these execute before the simulation is dispatched, so we don't even need to lock.
     * */
    @OnServerThread
    override fun subscribeServerThread(subscribers: SubscriberCollection<ServerPhase>) {
        subscribers.addStart(this::serverTickStart)
        subscribers.addAfterStart1(this::serverTickAfterStart1)
        subscribers.addAfterStart2(this::serverTickAfterStart2)
    }

    /**
     * Finds the (up to) 6 target distillation module cells.
     * The horizontal ones are found trivially, but the vertical ones may require following the [DistillationColumnCell]s to reach.
     * */
    @OnServerThread
    private fun rebuildLinksIfRequired() {
        if (!markedForRebuildLinks) {
            return
        }

        markedForRebuildLinks = false

        for (i in 0 until 6) {
            linkedCells[i] = null
        }

        val pos = locator.requireLocator(Locators.BLOCK)

        connections.forEach { other ->
            if(other is DistillationModuleCell) {
                val remotePos = other.locator.requireLocator(Locators.BLOCK)

                val face = pos.directionTo(remotePos)
                    ?: error(DEBUGGER_BREAK("Invalid remote distillation $pos $remotePos"))

                linkedCells[face.get3DDataValue()] = other
            }
            else if(other is DistillationColumnCell) {
                val remotePos = other.locator.requireLocator(Locators.BLOCK)

                val face = pos.directionTo(remotePos)
                    ?: error(DEBUGGER_BREAK("Invalid remote distillation column $pos $remotePos"))

                var current: Cell? = other
                while (current != null) {
                    if(current is DistillationModuleCell) {
                        linkedCells[face.get3DDataValue()] = current
                        break
                    }

                    val currentPos = current.locator.requireLocator(Locators.BLOCK)

                    current = current.connections.firstOrNull {
                        currentPos.directionTo(it.locator.requireLocator(Locators.BLOCK)) == face
                    }
                }
            }
            // else, it is a thermal conduit or something
        }
    }

    @OnServerThread
    private fun serverTickStart(dt: Double, phase: ServerPhase) {
        rebuildLinksIfRequired()
        distillation.prepareForSimulation()
        distillation.phaseChange()
        distillation.initialPass()
    }

    @OnServerThread
    private fun serverTickAfterStart1(dt: Double, phase: ServerPhase) {
        distillation.transferPass()
    }

    @OnServerThread
    private fun serverTickAfterStart2(dt: Double, phase: ServerPhase) {
        distillation.finalizePass()
        distillation.clear()
    }

    //#endregion
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

    override fun spatialNeighborScan(level: Level, results: HashSet<CellAndContainerHandle>, cell: Cell) {
        super.spatialNeighborScan(level, results, cell)

        val pos = cell.locator.requireLocator(Locators.BLOCK)
        Base6Direction3dMask.VERTICALS.forEach { dir ->
            val targetBlockEntity = level.getBlockEntity(pos + dir)

            // For some reason, the smart cast doesn't build if I combine the conditions
            if(targetBlockEntity is DistillationColumnBlockEntity) {
                results.add(CellAndContainerHandle.captureInScope(targetBlockEntity.cell))
            }
            else if(targetBlockEntity is DistillationModuleBlockEntity) {
                // We only allow thermal connections horizontally (I thought it's more interesting that way, and we can just couple layers with external conduits if we need to),
                // so we normally wouldn't connect to modules above and below.
                // We allow the connection explicitly here:
                results.add(CellAndContainerHandle.captureInScope(targetBlockEntity.cell))
            }
        }
    }
}

class DistillationModuleBlockEntity(pos: BlockPos, state: BlockState) :
    CellBlockEntity<DistillationModuleCell>(pos, state, (state.block as DistillationModuleBlock).blockEntityType.get()),
    ComponentDisplay,
    WrenchInteractable,
    InternalTemperatureConsumer,
    BulkPacketHandlerBlockEntity
{
    //#region Capability

    val liquidTank = MultipleFractionalFluidTank(1000.0, true, this::setChanged)
    val gasTank = MultipleFractionalFluidTank(1000.0, true, this::setChanged)

    /**
     * Gets an iterable over all fluids in the hull, for thermal calculations.
     * */
    private fun getFluids() = ListCombination(liquidTank.fluids, gasTank.fluids)

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
    class ThermalLayer<Handler : IFractionalFluidHandler>(val blockEntity: DistillationModuleBlockEntity, override val parent: Handler) : ThermalObjectBasedFractionalFluidHandlerThermalExpansion {
        override val handle: ThermalObjectBasedFractionalFluidHandlerThermalExpansion.ThermalBodyHandle
            get() = blockEntity.cell.handle

        override val ambientTemperature: Quantity<Temperature>
            get() = blockEntity.cell.environmentData.ambientTemperature

        override fun onMutated() = blockEntity.cell.setChanged()

        override fun getFluidStacks() = blockEntity.getFluids()
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

    override fun onCellConnected(actualCell: Cell, remoteCell: Cell) {
        cell.markForRebuild()
        super.onCellConnected(actualCell, remoteCell)
    }

    override fun onCellDisconnected(actualCell: Cell, remoteCell: Cell) {
        cell.markForRebuild()
        super.onCellDisconnected(actualCell, remoteCell)
    }

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
     * Recalculates the thermal body's composition and mass.
     * **Assumes already locked!**
     * */
    fun recalculateBody() {
        val body = cell.wire.thermalBody
        val (newMaterial, newMass) = ThermalFluidHandlerHelper.calculateDerivativeMaterialAndMass(DistillationModuleCell.HULL_MASS, DistillationModuleCell.HULL_MATERIAL, getFluids())
        body.material = newMaterial
        body.mass = newMass
        cell.setChanged()
        setChanged()
    }

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
        builder.debugInIDE { "Graph: ${cell.graph.id}" }

        cell.linkedCells.forEachIndexed { idx, remote ->
            val dir = Direction.from3DDataValue(idx)

            if(remote != null) {
                builder.debugInIDE { "Dir $dir: $remote (${remote.locator.requireLocator(Locators.BLOCK)})" }
            }
        }

        bottomFaceHandler.parent.debugView(builder)
        builder.quantity(cell.wire.thermalBody.temperature)
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
