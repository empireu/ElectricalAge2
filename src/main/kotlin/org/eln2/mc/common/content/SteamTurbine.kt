@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package org.eln2.mc.common.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.kinetic.KineticDouble
import org.ageseries.libage.sim.kinetic.KineticExtension
import org.ageseries.libage.sim.kinetic.KineticNodeSet
import org.eln2.mc.*
import org.eln2.mc.common.blocks.foundation.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.modules.Eln2SteamTurbine
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
    override val delegateMap: MultiblockDelegateMap
        get() = Eln2SteamTurbine.STEAM_TURBINE_DELEGATE_MAP.value

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Steam Turbine Representative @ $blockPos" }
    }
}

class SteamTurbineKineticPortKineticObject(cell: SteamTurbineKineticPortCell) : KineticObject<SteamTurbineKineticPortCell>(cell), PersistentObject
{
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

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) =
        SteamTurbineKineticDelegateBlockEntity(pPos, pState).also { it.portLabel = portLabel }

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

class SteamTurbineFluidDelegateBlock(val portLabel: String) : MultiblockDelegateBlock()
{
    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) =
        SteamTurbineFluidDelegateBlockEntity(pPos, pState).also { it.portLabel = portLabel }
}

class SteamTurbineFluidDelegateBlockEntity(pos: BlockPos, state: BlockState) :
    MultiblockDelegateBlockEntity(
        pos, state, Eln2SteamTurbine.STEAM_TURBINE_FLUID_DELEGATE_BLOCK_ENTITY.get()
    ),
    ComponentDisplay
{
    var portLabel: String = "?"

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Fluid Port [$portLabel] @ $blockPos (rep: $representativePos)" }
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
