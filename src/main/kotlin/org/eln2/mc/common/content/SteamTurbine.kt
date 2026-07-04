@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package org.eln2.mc.common.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.kinetic.KineticDouble
import org.ageseries.libage.sim.kinetic.KineticExtension
import org.ageseries.libage.sim.kinetic.KineticNodeSet
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import org.eln2.mc.*
import org.eln2.mc.common.blocks.foundation.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.content.modules.Eln2SteamTurbine
import org.eln2.mc.common.fluids.foundation.FractionalFluidStack
import org.eln2.mc.common.fluids.foundation.IThermalFluidHandler
import org.eln2.mc.common.fluids.foundation.PhysicalFluidManager
import org.eln2.mc.common.fluids.foundation.ThermalFluidStack
import org.eln2.mc.common.fluids.foundation.fractional
import org.ageseries.libage.data.OptionalDouble
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList

data class SteamTurbineGeneratorModel(
    val etaFactor: Double,
    val maxFlowRate: Double,
    val maxTorque: Quantity<Torque>,
    val maxPower: Quantity<Power>,
    val coldSideMass: Quantity<Mass>,
    val coldSideMaterial: Material,
    val coldSideLeakage: ConnectionParameters,
    val referenceAngularVelocity: Quantity<AngularVelocity>,
    val breakdownAngularVelocity: Quantity<AngularVelocity>,
    val breakdownTemperature: Quantity<Temperature>,
    val overcapacityThreshold: Double,
)

class SteamTurbineKineticObject(cell: SteamTurbineCell) : KineticObject<SteamTurbineCell>(cell), PersistentObject {
    val node = KineticDouble()

    init {
        cell.shaftFriction.applyTo(node)
    }

    override fun addNodes(builder: KineticNodeSet) {
        builder.add(node)
    }

    override fun offerExtension(remote: KineticObject<*>): KineticExtension? {
        if (remote !is SteamTurbineKineticPortKineticObject) {
            return null
        }

        val localPos = cell.locator.requireLocator(Locators.BLOCK)
        val remotePos = remote.cell.locator.requireLocator(Locators.BLOCK)
        val facing = cell.locator.requireLocator(Locators.CONVENTIONAL_FACING)

        val localRemote = MultiblockTransformations.transformWorldMultiblock(
            facing.direction, localPos, remotePos
        )

        return if (localRemote.x < 0) {
            node.e1
        } else {
            node.e2
        }
    }

    override fun saveObjectNbt() = CompoundTag().also {
        it.putDouble(ANGLE, node.angle)
        it.putDouble(OMEGA, node.angularVelocity)
    }

    override fun loadObjectNbt(tag: CompoundTag) {
        node.setExternalAngle(tag.getDouble(ANGLE))
        node.angularVelocity = tag.getDouble(OMEGA)
    }

    companion object {
        private const val ANGLE = "angle"
        private const val OMEGA = "omega"
    }
}

class SteamTurbineCell(ci: CellCreateInfo, val model: SteamTurbineGeneratorModel, val shaftFriction: FrictionNodeDescription) : Cell(ci) {
    @SimObject
    val kinetic = SteamTurbineKineticObject(this)

    @SimObject
    val thermal = ThermalWireObject(
        this,
        ThermalMassDefinition(model.coldSideMaterial, mass = model.coldSideMass)(),
        model.coldSideLeakage
    )

    @Behavior
    val kineticBreakdown = KineticBreakdownBehavior.create(
        model.breakdownAngularVelocity,
        this,
        kinetic.node::angularVelocity
    )

    @Behavior
    val thermalBreakdown = ThermalBreakdownBehavior.create(
        model.breakdownTemperature,
        this,
        thermal.thermalBody::temperature
    )

    override fun kineticObjectPredicate(remote: KineticObject<*>): Boolean {
        if (remote.cell is SteamTurbineKineticPortCell) {
            return true
        }
        return super.kineticObjectPredicate(remote)
    }

    override fun thermalObjectPredicate(remote: ThermalObject<*>): Boolean {
        if (remote.cell is SteamTurbineThermalPortCell) {
            return true
        }
        return super.thermalObjectPredicate(remote)
    }

    override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        subscribers.addPre(this::simulationPre)
        subscribers.addPost(this::simulationPost)
    }

    override fun subscribeServerThread(subscribers: SubscriberCollection<ServerPhase>) {
        subscribers.addStart(this::serverStart)
        subscribers.addEnd(this::serverEnd)
    }

    private fun simulationPre(dt: Double, phase: SimulationPhase) {
    }

    private fun simulationPost(dt: Double, phase: SimulationPhase) {
    }

    private fun serverStart(dt: Double, phase: ServerPhase) {
    }

    private fun serverEnd(dt: Double, phase: ServerPhase) {
    }
}

class SteamTurbineBlock : UprightHorizontalDirectionCellBlock<SteamTurbineCell>() {
    override fun getCellProvider() = Eln2SteamTurbine.STEAM_TURBINE_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = SteamTurbineBlockEntity(pPos, pState)

    override fun spatialNeighborScan(level: Level, results: HashSet<CellAndContainerHandle>, cell: Cell) {
        val pos = cell.locator.requireLocator(Locators.BLOCK)
        val facing = cell.locator.requireLocator(Locators.CONVENTIONAL_FACING)
        val blockEntity = level.getBlockEntity(pos) as? SteamTurbineBlockEntity ?: return

        blockEntity.delegateMap.forEachDelegateInWorld(facing.direction, pos) { delegatePos ->
            val delegate = level.getBlockEntity(delegatePos) as? MultiblockDelegateCellBlockEntity<*> ?: return@forEachDelegateInWorld
            results.add(CellAndContainerHandle.captureInScope(delegate.cell))
        }
    }
}

class SteamTurbineBlockEntity(pos: BlockPos, state: BlockState) :
    CellBlockEntity<SteamTurbineCell>(pos, state, Eln2SteamTurbine.STEAM_TURBINE_BLOCK_ENTITY.get()),
    BigBlockRepresentativeBlockEntity<SteamTurbineBlockEntity>,
    ComponentDisplay
{
    override val delegateMap: MultiblockDelegateMap get() = Eln2SteamTurbine.STEAM_TURBINE_DELEGATE_MAP.value

    //#region Capability

    class SteamTurbineFluidHandler(val ambientTemperature: () -> Quantity<Temperature>) {
        companion object {
            const val INPUT_STEAM_CAPACITY = 128.0
            const val OUTPUT_WATER_CAPACITY = 128.0
            const val OUTPUT_STEAM_CAPACITY = 128.0

            private const val INPUT_STEAM = "inputSteam"
            private const val INPUT_FLUID_ENERGY = "inputFluidEnergy"
            private const val OUTPUT_WATER = "outputWater"
            private const val OUTPUT_STEAM = "outputSteam"
            private const val OUTPUT_FLUID_ENERGY = "outputFluidEnergy"
        }

        var inputSteam = 0.0
        var inputFluidEnergy = 0.0
        var outputWater = 0.0
        var outputSteam = 0.0
        var outputFluidEnergy = 0.0

        val steamFluid: Fluid get() = Eln2ForgeFluids.STEAM.get()
        val waterFluid: Fluid get() = Fluids.WATER

        val steamCp: Double get() = !PhysicalFluidManager.getPropertiesWithFallback(steamFluid).specificHeatCapacity
        val waterCp: Double get() = !PhysicalFluidManager.getPropertiesWithFallback(waterFluid).specificHeatCapacity

        val inputTemperature: Double get() = if (inputSteam < FractionalFluidStack.EPSILON) {
            !ambientTemperature()
        } else {
            inputFluidEnergy / (inputSteam * steamCp)
        }

        val outputTemperature: Double get() {
            val totalCp = outputWater * waterCp + outputSteam * steamCp

            if (totalCp < FractionalFluidStack.EPSILON) {
                return !ambientTemperature()
            }

            return outputFluidEnergy / totalCp
        }

        val inputHandler = SteamTurbineInputFluidHandler(this)
        val outputHandler = SteamTurbineOutputFluidHandler(this)

        fun saveNbt(tag: CompoundTag) {
            tag.putDouble(INPUT_STEAM, inputSteam)
            tag.putDouble(INPUT_FLUID_ENERGY, inputFluidEnergy)
            tag.putDouble(OUTPUT_WATER, outputWater)
            tag.putDouble(OUTPUT_STEAM, outputSteam)
            tag.putDouble(OUTPUT_FLUID_ENERGY, outputFluidEnergy)
        }

        fun loadNbt(tag: CompoundTag) {
            inputSteam = tag.getDouble(INPUT_STEAM)
            inputFluidEnergy = tag.getDouble(INPUT_FLUID_ENERGY)
            outputWater = tag.getDouble(OUTPUT_WATER)
            outputSteam = tag.getDouble(OUTPUT_STEAM)
            outputFluidEnergy = tag.getDouble(OUTPUT_FLUID_ENERGY)
        }
    }

    class SteamTurbineInputFluidHandler(val parent: SteamTurbineFluidHandler) : IThermalFluidHandler {
        override fun getTanks() = 1

        override fun getFractionalFluidInTank(tank: Int) = when (tank) {
            0 -> FractionalFluidStack(parent.steamFluid, parent.inputSteam)
            else -> FractionalFluidStack.EMPTY
        }

        override fun getFractionalTankCapacity(tank: Int) = when (tank) {
            0 -> SteamTurbineFluidHandler.INPUT_STEAM_CAPACITY
            else -> 0.0
        }

        override fun getFluidInTank(tank: Int): FluidStack = getFractionalFluidInTank(tank).quantized()
        override fun getTankCapacity(tank: Int): Int = getFractionalTankCapacity(tank).toInt()

        override fun isFluidValid(tank: Int, stack: FluidStack): Boolean {
            return tank == 0 && stack.fluid == parent.steamFluid
        }

        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            if (resource.isEmpty) {
                return 0
            }

            val fractional = resource.fractional()
            val simulated = fillThermal(fractional, OptionalDouble.EMPTY, IFluidHandler.FluidAction.SIMULATE)
            val quantized = fractional.copyWithAmount(simulated).quantized()

            if (quantized.isEmpty) {
                return 0
            }

            if (action == IFluidHandler.FluidAction.SIMULATE) {
                return quantized.amount
            }

            fillThermal(quantized.fractional(), OptionalDouble.EMPTY, IFluidHandler.FluidAction.EXECUTE)

            return quantized.amount
        }

        override fun fillFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): Double {
            return fillThermal(resource, OptionalDouble.EMPTY, action)
        }

        override fun fillThermal(resource: FractionalFluidStack, temperature: OptionalDouble, action: IFluidHandler.FluidAction): Double {
            if (resource.isEmpty || resource.fluid != parent.steamFluid) {
                return 0.0
            }

            val space = SteamTurbineFluidHandler.INPUT_STEAM_CAPACITY - parent.inputSteam
            val accepted = resource.amount.coerceAtMost(space)

            if (accepted < FractionalFluidStack.EPSILON) {
                return 0.0
            }

            if (action == IFluidHandler.FluidAction.EXECUTE) {
                val incomingTemp = if (temperature.isPresent) {
                    temperature.unwrap()
                } else {
                    !parent.ambientTemperature()
                }

                parent.inputSteam += accepted
                parent.inputFluidEnergy += accepted * parent.steamCp * incomingTemp
            }

            return accepted
        }

        override fun drain(resource: FluidStack?, action: IFluidHandler.FluidAction?): FluidStack = FluidStack.EMPTY
        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction?): FluidStack = FluidStack.EMPTY
        override fun drainFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction) = FractionalFluidStack.EMPTY
        override fun drainFractional(maxDrain: Double, action: IFluidHandler.FluidAction) = FractionalFluidStack.EMPTY
        override fun drainThermal(resource: FractionalFluidStack, action: IFluidHandler.FluidAction) = null
        override fun drainThermal(maxDrain: Double, action: IFluidHandler.FluidAction) = null
    }

    class SteamTurbineOutputFluidHandler(val parent: SteamTurbineFluidHandler) : IThermalFluidHandler {
        companion object {
            private const val WATER_TANK = 0
            private const val STEAM_TANK = 1
        }

        override fun getTanks() = 2

        override fun getFractionalFluidInTank(tank: Int): FractionalFluidStack {
            return when (tank) {
                WATER_TANK -> FractionalFluidStack(parent.waterFluid, parent.outputWater)
                STEAM_TANK -> FractionalFluidStack(parent.steamFluid, parent.outputSteam)
                else -> FractionalFluidStack.EMPTY
            }
        }

        override fun getFractionalTankCapacity(tank: Int): Double {
            return when (tank) {
                WATER_TANK -> SteamTurbineFluidHandler.OUTPUT_WATER_CAPACITY
                STEAM_TANK -> SteamTurbineFluidHandler.OUTPUT_STEAM_CAPACITY
                else -> 0.0
            }
        }

        override fun getFluidInTank(tank: Int): FluidStack = getFractionalFluidInTank(tank).quantized()
        override fun getTankCapacity(tank: Int): Int = getFractionalTankCapacity(tank).toInt()

        override fun isFluidValid(tank: Int, stack: FluidStack) = false

        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction) = 0
        override fun fillFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction) = 0.0
        override fun fillThermal(resource: FractionalFluidStack, temperature: OptionalDouble, action: IFluidHandler.FluidAction) = 0.0

        override fun drainThermal(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): ThermalFluidStack? {
            if (resource.isEmpty) {
                return null
            }

            if (resource.fluid == parent.waterFluid) {
                return drainWaterThermal(resource.amount, action)
            }

            if (resource.fluid == parent.steamFluid) {
                return drainSteamThermal(resource.amount, action)
            }

            return null
        }

        override fun drainThermal(maxDrain: Double, action: IFluidHandler.FluidAction): ThermalFluidStack? {
            if (maxDrain < FractionalFluidStack.EPSILON) {
                return null
            }

            val fromWater = drainWaterThermal(maxDrain, action)

            if (fromWater != null) {
                return fromWater
            }

            return drainSteamThermal(maxDrain, action)
        }

        private fun drainWaterThermal(amount: Double, action: IFluidHandler.FluidAction): ThermalFluidStack? {
            val drained = amount.coerceAtMost(parent.outputWater)

            if (drained < FractionalFluidStack.EPSILON) {
                return null
            }

            val temp = parent.outputTemperature

            if (action == IFluidHandler.FluidAction.EXECUTE) {
                parent.outputWater -= drained
                parent.outputFluidEnergy -= drained * parent.waterCp * temp
            }

            return ThermalFluidStack(FractionalFluidStack(parent.waterFluid, drained), temp)
        }

        private fun drainSteamThermal(amount: Double, action: IFluidHandler.FluidAction): ThermalFluidStack? {
            val drained = amount.coerceAtMost(parent.outputSteam)

            if (drained < FractionalFluidStack.EPSILON) {
                return null
            }

            val temp = parent.outputTemperature

            if (action == IFluidHandler.FluidAction.EXECUTE) {
                parent.outputSteam -= drained
                parent.outputFluidEnergy -= drained * parent.steamCp * temp
            }

            return ThermalFluidStack(FractionalFluidStack(parent.steamFluid, drained), temp)
        }

        override fun drainFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): FractionalFluidStack {
            return drainThermal(resource, action)?.packet ?: FractionalFluidStack.EMPTY
        }

        override fun drainFractional(maxDrain: Double, action: IFluidHandler.FluidAction): FractionalFluidStack {
            return drainThermal(maxDrain, action)?.packet ?: FractionalFluidStack.EMPTY
        }

        override fun drain(resource: FluidStack?, action: IFluidHandler.FluidAction?): FluidStack {
            if (resource == null || action == null || resource.isEmpty) {
                return FluidStack.EMPTY
            }

            return drainThermal(resource.fractional(), action)?.packet?.quantized() ?: FluidStack.EMPTY
        }

        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction?): FluidStack {
            if (action == null || maxDrain <= 0) {
                return FluidStack.EMPTY
            }

            return drainThermal(maxDrain.toDouble(), action)?.packet?.quantized() ?: FluidStack.EMPTY
        }
    }

    val fluidHandler = SteamTurbineFluidHandler(cell.environmentData::ambientTemperature)
    val inputFluidLazy: LazyOptional<SteamTurbineInputFluidHandler> = LazyOptional.of { fluidHandler.inputHandler }
    val outputFluidLazy: LazyOptional<SteamTurbineOutputFluidHandler> = LazyOptional.of { fluidHandler.outputHandler }

    override fun invalidateCaps() {
        super.invalidateCaps()
        inputFluidLazy.invalidate()
        outputFluidLazy.invalidate()
    }

    //#endregion

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)
        fluidHandler.saveNbt(pTag)
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)
        fluidHandler.loadNbt(pTag)
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Steam Turbine Representative @ $blockPos" }
        builder.debugInIDE { "Input steam: ${fluidHandler.inputSteam} mB at ${fluidHandler.inputTemperature.rounded()} K" }
        builder.debugInIDE { "Output water: ${fluidHandler.outputWater} mB, steam: ${fluidHandler.outputSteam} mB at ${fluidHandler.outputTemperature.rounded()} K" }
    }
}

class SteamTurbineKineticPortKineticObject(cell: SteamTurbineKineticPortCell) : KineticObject<SteamTurbineKineticPortCell>(cell), PersistentObject {
    val node = KineticDouble()

    init {
        KINETIC_PORT_FRICTION_DESCRIPTION.applyTo(node)
    }

    override fun addNodes(builder: KineticNodeSet) {
        builder.add(node)
    }

    override fun offerExtension(remote: KineticObject<*>): KineticExtension? {
        return if (remote is SteamTurbineKineticObject) {
            node.e2
        } else {
            node.e1
        }
    }

    override fun saveObjectNbt() = CompoundTag().also {
        it.putDouble(ANGLE, node.angle)
        it.putDouble(OMEGA, node.angularVelocity)
    }

    override fun loadObjectNbt(tag: CompoundTag) {
        node.setExternalAngle(tag.getDouble(ANGLE))
        node.angularVelocity = tag.getDouble(OMEGA)
    }

    companion object {
        private const val ANGLE = "angle"
        private const val OMEGA = "omega"
    }
}

class SteamTurbineKineticPortCell(
    ci: CellCreateInfo,
    override val kineticMap: PoleMap,
    override val kineticSize: KineticSize,
) : Cell(ci), SidedKineticMapped<SteamTurbineKineticPortCell>
{
    override fun kineticObjectPredicate(remote: KineticObject<*>): Boolean {
        if (remote.cell is SteamTurbineCell) {
            return true
        }

        return super.kineticObjectPredicate(remote)
    }

    @SimObject
    val kinetic = SteamTurbineKineticPortKineticObject(this)
}

class SteamTurbineKineticDelegateBlock(val portLabel: String) : MultiblockDelegateUprightHorizontalDirectionCellBlock<SteamTurbineKineticPortCell>() {
    @Deprecated("Deprecated in Java")
    override fun skipRendering(pState: BlockState, pAdjacentState: BlockState, pDirection: Direction) = true

    override fun getCellProvider() = Eln2SteamTurbine.STEAM_TURBINE_KINETIC_DELEGATE_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = SteamTurbineKineticDelegateBlockEntity(pPos, pState).also { it.portLabel = portLabel }

    override fun spatialNeighborScan(level: Level, results: HashSet<CellAndContainerHandle>, cell: Cell) {
        val blockEntity = level.getBlockEntity(cell.locator.requireLocator(Locators.BLOCK))
            as? SteamTurbineKineticDelegateBlockEntity ?: return

        val representative = getRepresentativeFromDelegateBlockEntity<SteamTurbineBlockEntity>(
            blockEntity, blockEntity.representativePos
        )

        planarCellScan(
            level,
            cell,
            cell.locator.requireLocator(Locators.CONVENTIONAL_FACING).direction.opposite,
            results::add
        )

        results.add(CellAndContainerHandle.captureInScope(representative.cell))
    }
}

class SteamTurbineKineticDelegateBlockEntity(pos: BlockPos, state: BlockState) :
    MultiblockDelegateCellBlockEntity<SteamTurbineKineticPortCell>(
        pos, state, Eln2SteamTurbine.STEAM_TURBINE_KINETIC_DELEGATE_BLOCK_ENTITY.get()
    ),
    ComponentDisplay
{
    var portLabel: String = "?"

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Kinetic Port [$portLabel] @ $blockPos (rep: $representativePos)" }
        builder.debugInIDE { "Vel: ${cell.kinetic.node.angularVelocity.rounded()}" }
    }
}

class SteamTurbineThermalPortCell(
    ci: CellCreateInfo,
    thermalDef: ThermalMassDefinition,
    override val thermalMap: MonopoleMap,
    override val thermalSize: ThermalSize,
) : Cell(ci), SidedThermalMonoMapped<SteamTurbineThermalPortCell>
{
    override fun thermalObjectPredicate(remote: ThermalObject<*>): Boolean {
        if (remote.cell is SteamTurbineCell) {
            return true
        }

        return super.thermalObjectPredicate(remote)
    }

    @SimObject
    val thermalWire = ThermalWireObject(this, thermalDef())
}

class SteamTurbineThermalDelegateBlock(val portLabel: String) :
    MultiblockDelegateUprightHorizontalDirectionCellBlock<SteamTurbineThermalPortCell>()
{
    @Deprecated("Deprecated in Java")
    override fun skipRendering(pState: BlockState, pAdjacentState: BlockState, pDirection: Direction) = true

    override fun getCellProvider() = Eln2SteamTurbine.STEAM_TURBINE_THERMAL_DELEGATE_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) =
        SteamTurbineThermalDelegateBlockEntity(pPos, pState).also { it.portLabel = portLabel }

    override fun spatialNeighborScan(level: Level, results: HashSet<CellAndContainerHandle>, cell: Cell) {
        val blockEntity = level.getBlockEntity(cell.locator.requireLocator(Locators.BLOCK))
            as? SteamTurbineThermalDelegateBlockEntity ?: return

        val representative = getRepresentativeFromDelegateBlockEntity<SteamTurbineBlockEntity>(
            blockEntity, blockEntity.representativePos
        )

        planarCellScan(
            level,
            cell,
            cell.locator.requireLocator(Locators.CONVENTIONAL_FACING).direction.opposite,
            results::add
        )

        results.add(CellAndContainerHandle.captureInScope(representative.cell))
    }
}

class SteamTurbineThermalDelegateBlockEntity(pos: BlockPos, state: BlockState) :
    MultiblockDelegateCellBlockEntity<SteamTurbineThermalPortCell>(
        pos, state, Eln2SteamTurbine.STEAM_TURBINE_THERMAL_DELEGATE_BLOCK_ENTITY.get()
    ),
    ComponentDisplay
{
    var portLabel: String = "?"

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Thermal Port [$portLabel] @ $blockPos (rep: $representativePos)" }
    }
}

class SteamTurbineFluidDelegateBlock : MultiblockDelegateBlock() {
    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) =
        SteamTurbineFluidDelegateBlockEntity(pPos, pState)
}

class SteamTurbineFluidDelegateBlockEntity(pos: BlockPos, state: BlockState) :
    MultiblockDelegateBlockEntity(
        pos, state, Eln2SteamTurbine.STEAM_TURBINE_FLUID_DELEGATE_BLOCK_ENTITY.get()
    ),
    ComponentDisplay
{
    val isInputPort: Boolean
        get() = blockState.block === Eln2SteamTurbine.STEAM_TURBINE_FLUID_DELEGATE_BLOCK_INPUT.get()

    override fun <T> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.FLUID_HANDLER) {
            val representativePos = this.representativePos
                ?: return super.getCapability(cap, side)

            val level = this.level
                ?: return super.getCapability(cap, side)

            if (!level.isLoaded(representativePos)) {
                return LazyOptional.empty()
            }

            val representative = level.getBlockEntity(representativePos) as? SteamTurbineBlockEntity
                ?: return super.getCapability(cap, side)

            return if (isInputPort) {
                representative.inputFluidLazy.cast()
            } else {
                representative.outputFluidLazy.cast()
            }
        }

        return super.getCapability(cap, side)
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        val portName = if (isInputPort) "Steam Input" else "Water/Steam Output"
        builder.debugInIDE { "Fluid Port [$portName] @ $blockPos (rep: $representativePos)" }
    }
}

private val KINETIC_PORT_FRICTION_DESCRIPTION = FrictionNodeDescription(
    Quantity(0.001, KILOGRAM_METER2),
    NodeFrictionDescription(
        0.0,
        Quantity(0.0, NEWTON_METER),
        Quantity(0.0, NEWTON_METER)
    )
)
