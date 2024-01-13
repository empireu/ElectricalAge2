package org.eln2.mc.common.blocks.foundation

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.FluidState
import org.ageseries.libage.data.put
import org.ageseries.libage.mathematics.geometry.OrientedBoundingBox3d
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.LOG
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.foundation.RGBAFloat
import org.eln2.mc.common.cells.CellRegistry
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.*
import org.eln2.mc.common.specs.foundation.SpecGeometry
import org.eln2.mc.data.*
import org.eln2.mc.extensions.toVector3d
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.mathematics.toHorizontalFacing
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

abstract class CellBlock<C : Cell>(p : Properties? = null) : HorizontalDirectionalBlock(p ?: Properties.of().noOcclusion()), EntityBlock {
    init {
        @Suppress("LeakingThis")
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH))
    }

    override fun getStateForPlacement(pContext: BlockPlaceContext): BlockState? {
        return super.defaultBlockState().setValue(FACING, pContext.horizontalDirection.opposite)
    }

    override fun createBlockStateDefinition(pBuilder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(pBuilder)
        pBuilder.add(FACING)
    }

    @Suppress("UNCHECKED_CAST")
    override fun setPlacedBy(level: Level, blockPos: BlockPos, blockState: BlockState, entity: LivingEntity?, itemStack: ItemStack) {
        val cellEntity = level.getBlockEntity(blockPos)!! as CellBlockEntity<C>
        cellEntity.setPlacedBy(level, getCellProvider())
    }

    override fun onBlockExploded(blockState: BlockState?, level: Level?, blockPos: BlockPos?, explosion: Explosion?) {
        markCellDestroyed(level ?: error("Level was null"), blockPos ?: error("Position was null"))
        super.onBlockExploded(blockState, level, blockPos, explosion)
    }

    override fun onDestroyedByPlayer(blockState: BlockState?, level: Level?, blockPos: BlockPos?, player: Player?, willHarvest: Boolean, fluidState: FluidState?): Boolean {
        markCellDestroyed(
            level
            ?: error("Level was null"),
            blockPos ?: error("Position was null")
        )
        return super.onDestroyedByPlayer(blockState, level, blockPos, player, willHarvest, fluidState)
    }

    fun markCellDestroyed(level: Level, blockPos: BlockPos) {
        if (!level.isClientSide) {
            val cellEntity = level.getBlockEntity(blockPos)!! as CellBlockEntity<*>
            cellEntity.setDestroyed()
        }
    }

    abstract fun getCellProvider(): CellProvider<C>
}

open class CellBlockEntity<C : Cell>(pos: BlockPos, state: BlockState, targetType: BlockEntityType<*>) : BlockEntity(targetType, pos, state), CellContainer {
    open val cellFace = Direction.UP

    val locator = Locators.buildLocator {
        it.put(BLOCK, blockPos)
        it.put(FACE, cellFace)
        it.put(FACING, blockState.getValue(HorizontalDirectionalBlock.FACING).toHorizontalFacing())
    }

    private lateinit var graphManager: CellGraphManager
    private lateinit var cellProvider: CellProvider<C>
    private lateinit var savedGraphID: UUID

    private var cellField: C? = null

    val hasCell get() = cellField != null

    @ServerOnly
    val cell: C get() = cellField ?: error("Tried to get cell too early! $this")

    open fun setPlacedBy(level: Level, cellProvider: CellProvider<C>) {
        this.cellProvider = cellProvider

        if (level.isClientSide) {
            return
        }

        // Create the cell based on the provider.

        cellField = cellProvider.create(locator, CellEnvironment.evaluate(level, locator))

        cell.container = this

        CellConnections.insertFresh(this, cell)
        setChanged()

        cell.bindGameObjects(createObjectList())

        onCellAcquired()
    }

    fun disconnect() {
        CellConnections.disconnectCell(cell, this, false)
    }

    fun reconnect() {
        CellConnections.connectCell(cell, this)
    }

    protected open fun createObjectList() = listOf(this)

    open fun setDestroyed() {
        val level = this.level ?: error("Level is null in setDestroyed")
        val cell = this.cell

        if (cellField == null) {
            // This means we are on the client.
            // Otherwise, something is going on here.

            require(level.isClientSide) {
                "Cell is null in setDestroyed"
            }

            return
        }

        cell.unbindGameObjects()
        CellConnections.destroy(cell, this)
    }

    override fun saveAdditional(pTag: CompoundTag) {
        if (level!!.isClientSide) {
            // No saving is done on the client
            return
        }

        if (cell.hasGraph) {
            pTag.putString("GraphID", cell.graph.id.toString())
        } else {
            LOG.error("Save additional: graph null")
        }
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)

        if (pTag.contains("GraphID")) {
            savedGraphID = UUID.fromString(pTag.getString("GraphID"))!!
            LOG.info("Deserialized cell entity at $blockPos")
        } else {
            LOG.warn("Cell entity at $blockPos does not have serialized data.")
        }
    }

    override fun onChunkUnloaded() {
        super.onChunkUnloaded()

        if (!level!!.isClientSide) {
            cell.onContainerUnloading()
            cell.container = null
            cell.onContainerUnloaded()
            cell.unbindGameObjects()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if (level!!.isClientSide) {
            return
        }

        // here, we can get our manager. We have the level at this point.

        graphManager = CellGraphManager.getFor(level as ServerLevel)

        if (this::savedGraphID.isInitialized && graphManager.contains(savedGraphID)) {
            // fetch graph with ID
            val graph = graphManager.getGraph(savedGraphID)

            // fetch cell instance
            println("Loading cell at location $blockPos")

            cellField = graph.getCellByLocator(locator) as C

            cellProvider = CellRegistry.getCellProvider(cell.id) as CellProvider<C>
            cell.container = this
            cell.onContainerLoaded()
            cell.bindGameObjects(createObjectList())

            onCellAcquired()
        }
    }

    protected open fun onCellAcquired() { }

    //#endregion

    override fun getCells(): ArrayList<Cell> {
        return arrayListOf(cell)
    }

    override fun neighborScan(actualCell: Cell): List<CellAndContainerHandle> {
        val level = this.level ?: error("Level is null in queryNeighbors")

        val results = HashSet<CellAndContainerHandle>()

        Base6Direction3dMask.HORIZONTALS.directionList.forEach { searchDir ->
            planarCellScan(level, cell, searchDir) {
                check(results.add(it)) {
                    "Duplicate planar cell scan $it"
                }
            }
        }

        addExtraConnections(results)

        return results.toList()
    }

    protected open fun addExtraConnections(results: MutableSet<CellAndContainerHandle>) { }

    override fun onCellConnected(actualCell: Cell, remoteCell: Cell) {
        LOG.debug("Cell Block recorded connection from {} to {}", actualCell, remoteCell)
    }

    override fun onCellDisconnected(actualCell: Cell, remoteCell: Cell) {
        LOG.debug("Cell Block recorded deleted connection from {} to {}", actualCell, remoteCell)
    }

    override fun onTopologyChanged() {
        setChanged()
    }

    override val manager: CellGraphManager
        get() = CellGraphManager.getFor(level as ServerLevel)
}

abstract class GridCellBlockEntity<C : Cell>(pos: BlockPos, state: BlockState, targetType: BlockEntityType<*>) : CellBlockEntity<C>(pos, state, targetType),
    GridTerminalContainer {
    var containerID = UUID.randomUUID()
        private set

    private var gridTerminalSystemField: GridTerminalSystem? = null
    // FRAK YOU!
    val gridTerminalSystem get() = checkNotNull(gridTerminalSystemField) {
        "Grid terminal system is not initialized yet!"
    }

    val positiveX get() = blockState.getValue(HorizontalDirectionalBlock.FACING).toHorizontalFacing().rotation3d * Vector3d.unitX
    val positiveZ get() = blockState.getValue(HorizontalDirectionalBlock.FACING).toHorizontalFacing().rotation3d * Vector3d.unitZ

    /**
     * Creates a bounding box in the world frame.
     * @param x Center X in the local frame.
     * @param y Center Y in the local frame.
     * @param z Center Z in the local frame.
     * @param sizeX Size along X in the local frame.
     * @param sizeY Size along Y in the local frame.
     * @param sizeZ Size along Z in the local frame.
     * @return A bounding box in the world frame.
     * */
    protected fun boundingBox(
        x: Double,
        y: Double,
        z: Double,
        sizeX: Double,
        sizeY: Double,
        sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
    ) = SpecGeometry.boundingBox(
        blockPos.toVector3d() + Vector3d(0.5, 0.0, 0.5) +
            positiveX * x +
            Vector3d.unitY * y +
            positiveZ * z,
        orientation * blockState.getValue(HorizontalDirectionalBlock.FACING).toHorizontalFacing().rotation2d,
        Vector3d(sizeX, sizeY, sizeZ),
        blockState.getValue(HorizontalDirectionalBlock.FACING).toHorizontalFacing(),
        Direction.UP
    )

    protected fun defineCellBoxTerminal(box3d: OrientedBoundingBox3d, attachment: Vector3d? = null, highlightColor : RGBAFloat? = RGBAFloat(
        1f,
        0.58f,
        0.44f,
        0.8f
    ), categories: List<GridMaterialCategory>) = gridTerminalSystem.defineTerminal<GridTerminal>(
        TerminalFactories(
            { ci ->
                CellTerminal(ci, locator, attachment ?: box3d.center, box3d) { this.cell }.also {
                    it.categories.addAll(categories)
                }
            },
            { GridTerminalClient(it, locator, attachment ?: box3d.center, box3d, highlightColor) }
        )
    )

    protected fun defineCellBoxTerminal(
        x: Double, y: Double, z: Double,
        sizeX: Double, sizeY: Double, sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
        attachment: Vector3d? = null,
        highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
        categories: List<GridMaterialCategory>,
    ) = defineCellBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation), attachment, highlightColor, categories)


    private var gridTerminalSystemTag: CompoundTag? = null

    override fun setPlacedBy(level: Level, cellProvider: CellProvider<C>) {
        gridTerminalSystem.initializeFresh()
        super.setPlacedBy(level, cellProvider)

        if(!level.isClientSide) {
            cell.ifNode<GridNode> {
                it.mapFromGridTerminalSystem(gridTerminalSystem)
            }
        }
    }

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)

        pTag.putUUID(CONTAINER_ID, containerID)
        pTag.put(GRID_TERMINAL_SYSTEM, gridTerminalSystem.save(GridTerminalSystem.SaveType.Server))
    }

    override fun getUpdateTag(): CompoundTag {
        val tag = CompoundTag()
        tag.put(GRID_TERMINAL_SYSTEM, gridTerminalSystem.save(GridTerminalSystem.SaveType.Client))
        return tag
    }

    override fun handleUpdateTag(tag: CompoundTag?) {
        if(tag == null) {
            return
        }

        if(tag.contains(GRID_TERMINAL_SYSTEM)) {
            gridTerminalSystem.initializeSaved(tag.getCompound(GRID_TERMINAL_SYSTEM))
        }
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)

        if(pTag.contains(CONTAINER_ID)) {
            containerID = pTag.getUUID(CONTAINER_ID)
        }

        if(pTag.contains(GRID_TERMINAL_SYSTEM)) {
            gridTerminalSystemTag = pTag.getCompound(GRID_TERMINAL_SYSTEM)
        }
    }

    override fun setLevel(pLevel: Level) {
        check(gridTerminalSystemField == null) {
            "Grid terminal system was present in setLevel"
        }

        gridTerminalSystemField = GridTerminalSystem(pLevel)
        createTerminals()

        super.setLevel(pLevel)

        if(gridTerminalSystemTag != null) {
            check(!pLevel.isClientSide)
            gridTerminalSystem.initializeSaved(gridTerminalSystemTag!!)
            gridTerminalSystemTag = null
        }
    }

    protected abstract fun createTerminals()

    override fun pickTerminal(player: LivingEntity) = gridTerminalSystem.pick(player)

    override fun getTerminalByEndpointID(endpointID: UUID) = gridTerminalSystem.getByEndpointID(endpointID)

    override fun setDestroyed() {
        super.setDestroyed()
        gridTerminalSystem.destroy()
    }

    override fun addExtraConnections(results: MutableSet<CellAndContainerHandle>) {
        gridTerminalSystem.forEachTerminalOfType<CellTerminal> {
            if(it.stagingCell != null) {
                results.add(CellAndContainerHandle.captureInScope(it.stagingCell!!))
            }
        }

        if(hasCell) {
            cell.ifNode<GridNode> { node ->
                node.forEachConnectionCell {
                    results.add(CellAndContainerHandle.captureInScope(it))
                }
            }
        }
    }

    companion object {
        private const val CONTAINER_ID = "containerID"
        private const val GRID_TERMINAL_SYSTEM = "gridTerminalSystem"
    }
}
