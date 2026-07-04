@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package org.eln2.mc.common.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.kinetic.KineticExtension
import org.ageseries.libage.sim.kinetic.KineticMono
import org.ageseries.libage.sim.kinetic.KineticNodeSet
import org.eln2.mc.FrictionNodeDescription
import org.eln2.mc.Locators
import org.eln2.mc.MonopoleMap
import org.eln2.mc.NodeFrictionDescription
import org.eln2.mc.common.blocks.foundation.BigBlockRepresentativeBlockEntity
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateBlock
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateBlockEntity
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateCellBlockEntity
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateMap
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateUprightHorizontalDirectionCellBlock
import org.eln2.mc.common.blocks.foundation.UprightHorizontalDirectionCellBlock
import org.eln2.mc.common.blocks.foundation.getRepresentativeFromDelegateBlockEntity
import org.eln2.mc.common.cells.foundation.CellAndContainerHandle
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.cells.foundation.CellCreateInfo
import org.eln2.mc.common.cells.foundation.KineticObject
import org.eln2.mc.common.cells.foundation.KineticSize
import org.eln2.mc.common.cells.foundation.PersistentObject
import org.eln2.mc.common.cells.foundation.SimObject
import org.eln2.mc.common.cells.foundation.SidedKineticMonoMapped
import org.eln2.mc.common.cells.foundation.SidedThermalMonoMapped
import org.eln2.mc.common.cells.foundation.ThermalSize
import org.eln2.mc.common.cells.foundation.planarCellScan
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

class SteamTurbineCell(ci: CellCreateInfo) : Cell(ci)

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

class SteamTurbineKineticPortKineticObject(cell: SteamTurbineKineticPortCell) :
    KineticObject<SteamTurbineKineticPortCell>(cell), PersistentObject
{
    val node = KineticMono()

    init {
        KINETIC_PORT_FRICTION_DESCRIPTION.applyTo(node)
    }

    override fun addNodes(builder: KineticNodeSet) {
        builder.add(node)
    }

    override fun offerExtension(remote: KineticObject<*>): KineticExtension = node.extension

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
    override val kineticMap: MonopoleMap,
    override val kineticSize: KineticSize,
) : Cell(ci), SidedKineticMonoMapped<SteamTurbineKineticPortCell>
{
    @SimObject
    val kinetic = SteamTurbineKineticPortKineticObject(this)
}

class SteamTurbineKineticDelegateBlock(val portLabel: String) :
    MultiblockDelegateUprightHorizontalDirectionCellBlock<SteamTurbineKineticPortCell>()
{
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
    }
}

class SteamTurbineThermalPortCell(
    ci: CellCreateInfo,
    thermalDef: ThermalMassDefinition,
    override val thermalMap: MonopoleMap,
    override val thermalSize: ThermalSize,
) : Cell(ci), SidedThermalMonoMapped<SteamTurbineThermalPortCell>
{
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
