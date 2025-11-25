package org.eln2.mc.common.content

import com.google.gson.JsonObject
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.ShaderLightVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.GsonHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.Shapes
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.items.ItemStackHandler
import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.ThermalConductance
import org.ageseries.libage.data.WATT_PER_KELVIN
import org.ageseries.libage.data.requireLocator
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.ClientOnly
import org.eln2.mc.CrossThreadAccess
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.LOG
import org.eln2.mc.OnServerThread
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.client.render.foundation.partTransformation
import org.eln2.mc.common.blocks.foundation.*
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.modules.Eln2Ingredients
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.network.serverToClient.BulkPacketHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.sendBulkPacket
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.common.recipes.foundation.INPUT_SLOT
import org.eln2.mc.common.recipes.foundation.OUTPUT_SLOT
import org.eln2.mc.data.Locators
import org.eln2.mc.data.MonopoleMap
import org.eln2.mc.extensions.addItem
import org.eln2.mc.extensions.bind
import org.eln2.mc.extensions.getResourceLocation
import org.eln2.mc.extensions.minus
import org.eln2.mc.extensions.putResourceLocation
import org.eln2.mc.extensions.toVector3d
import org.eln2.mc.extensions.vector3d
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.mathematics.BlockPosInt
import java.util.Optional
import java.util.function.Consumer
import kotlin.math.PI
import kotlin.random.Random

//#region Rubber Tap

class RubberTapPartProvider : PartProvider() {
    override fun createCore(context: PartPlacementInfo) = RubberTapPart(
        PartCreateInfo(id, context)
    )

    override val placementCollisionSize: Vector3d
        get() = Vector3d.one

    override fun canPlace(level: Level, substratePos: BlockPos, face: Direction): Boolean {
        return Base6Direction3dMask.HORIZONTALS.has(face)
    }
}

class RubberTapPart(ci: PartCreateInfo) : Part(ci), TickablePart, ComponentDisplay {
    companion object {
        private const val MIN_INTERVAL = 20 * 3
        private const val MAX_INTERVAL = 20 * 5

        /**
         * The number of logs needed to consider the tree valid.
         * */
        private const val REQUIRED_LOGS = 5

        /**
         * The number of leaves needed to consider the tree valid.
         * */
        private const val REQUIRED_LEAVES = 5

        /**
         * The maximum distance between the collector and any searched blocks.
         * */
        private const val MAX_SPATIAL_DISTANCE = 5

        /**
         * The maximum nodes between the collector and any searched blocks.
         * */
        private const val MAX_GRAPH_DISTANCE = 15

        private const val MIN_BASE_PROGRESS = 0.1
        private const val MAX_BASE_PROGRESS = 0.25

        @OnServerThread
        private val visited = IntOpenHashSet()

        @OnServerThread
        private val queue = LongArrayFIFOQueue()
    }

    private var scanCountdown = 0

    /**
     * On both the client ([handleSyncTag]) and the server.
     * */
    var progress = 0.0
        private set

    init {
        updateShape(
            Shapes.create(
                getModelBoundingBox(Vector3d.zero, Vector3d(0.5, 0.315, 0.32))
                    .move(0.0, -0.245, 0.0)
            )
        )
    }

    override fun createVisual(ctx: MultipartVisualizationContext) = RubberTapPartVisual(ctx, this)

    override fun onAdded() {
        if(!placement.level.isClientSide) {
            placement.multipart.addTicker(this)
        }
    }

    override fun serverTick() {
        if(progress == 1.0) {
            placement.multipart.markRemoveTicker(this)
            return // Waits for the item to be removed.
        }

        if(--scanCountdown > 0) {
            return
        }

        scanCountdown = Random.nextInt(MIN_INTERVAL, MAX_INTERVAL)

        val substratePos = placement.position - placement.face

        if(placement.level.getBlockState(substratePos).block != Blocks.OAK_LOG) {
            return
        }

        visited.clear()
        queue.clear()
        queue.enqueue(0L)

        val mutableBlockPos = BlockPos.MutableBlockPos()

        var foundLogs = 0
        var foundLeaves = 0
        var validated = false

        while (!queue.isEmpty) {
            val front = queue.dequeueLong()
            val frontKey = front.toInt()

            val dx = BlockPosInt.unpackX(frontKey)
            val dy = BlockPosInt.unpackY(frontKey)
            val dz = BlockPosInt.unpackZ(frontKey)

            if(dx * dx + dy * dy + dz * dz > MAX_SPATIAL_DISTANCE * MAX_SPATIAL_DISTANCE) {
                continue
            }

            if(!visited.add(frontKey)) {
                continue
            }

            mutableBlockPos.x = dx + substratePos.x
            mutableBlockPos.y = dy + substratePos.y
            mutableBlockPos.z = dz + substratePos.z

            val blockState = placement.level.getBlockState(mutableBlockPos)

            when(blockState.block) {
                Blocks.OAK_LOG -> {
                    foundLogs++
                }

                Blocks.OAK_LEAVES -> {
                    foundLeaves++
                }

                // Can also check for other taps in the vicinity, to reduce the collection rate.

                else -> {
                    continue
                }
            }

            if(foundLogs >= REQUIRED_LOGS && foundLeaves >= REQUIRED_LEAVES) {
                validated = true
                break
            }

            val graphDistance = 1 + (front shr 32).toInt()

            if(graphDistance > MAX_GRAPH_DISTANCE) {
                continue
            }

            Direction.entries.forEach { direction ->
                val childKey = BlockPosInt.pack(
                    dx + direction.stepX,
                    dy + direction.stepY,
                    dz + direction.stepZ
                )

                queue.enqueue((graphDistance.toLong() shl 32) or (childKey.toLong() and 0xFFFF_FFFFL))
            }
        }

        if(!validated) {
            return
        }

        progress += Random.nextDouble(MIN_BASE_PROGRESS, MAX_BASE_PROGRESS)

        if(progress >= 1.0) {
            progress = 1.0
        }

        setSaveDirty()
        setSyncDirty()
    }

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if(progress != 1.0) {
            return InteractionResult.FAIL
        }

        if(placement.level.isClientSide) {
            return InteractionResult.PASS
        }

        val level = placement.level as ServerLevel

        val (x, y, z) = placement.mountingPointWorld + placement.face.vector3d * 0.4

        level.addItem(x, y, z, ItemStack(Eln2Ingredients.LATEX_ITEM.get(), 1))

        progress = 0.0

        setSaveDirty()
        setSyncDirty()

        placement.multipart.addTicker(this)

        return InteractionResult.SUCCESS
    }

    override fun getServerSaveTag(): CompoundTag {
        val tag = CompoundTag()
        tag.putDouble("progress", progress)
        return tag
    }

    override fun loadServerSaveTag(tag: CompoundTag) {
        progress = tag.getDouble("progress")
    }

    override fun getSyncTag() = getServerSaveTag()

    override fun handleSyncTag(tag: CompoundTag) = loadServerSaveTag(tag)

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Countdown: $scanCountdown" }
        builder.progress(progress)
    }
}

class RubberTapPartVisual(visualizationContext: MultipartVisualizationContext, part: RubberTapPart) : AbstractPartVisual<RubberTapPart>(visualizationContext, part), SimpleDynamicVisual {
    private val body = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.RUBBER_TAP))
        .createInstance()
        .also {
            it.translateY(-0.4f)
            it.partTransformation(visualizationContext.parent, part)
            it.translateZ(0.5f)
            it.rotateX((-PI / 2.0).toFloat())
        }

    private val latex = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.RUBBER_TAP_LATEX))
        .createInstance()

    private var progress = -1.0

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val targetProgress = part.progress

        if(targetProgress != progress) {
            progress = targetProgress

            latex.setIdentityTransform()

            latex.translateY(-0.4f)
            latex.translateY(progress.toFloat() * 0.1875f)

            latex.partTransformation(visualizationContext.parent, part)

            latex.translateZ(0.5f)
            latex.rotateX((-PI / 2.0).toFloat())

            latex.setChanged()
        }
    }

    override fun updateLight(p0: Float) {
        visualizationContext.parent.relightInstances(body, latex)
    }

    override fun _delete() {
        body.delete()
        latex.delete()
    }
}

//#endregion

/**
 * Multi-block, Multi-cell device.
 * There still is a representative block with its cell, that has the main logic: [VulcanizingAutoclaveMainCell], [VulcanizingAutoclaveMainBlock], [VulcanizingAutoclaveMainBlockEntity].
 * The main cell doesn't have simulation objects and doesn't create connections with the exterior.
 * It creates a connection with the [VulcanizingAutoclaveThermalPortCell].
 *
 * The [VulcanizingAutoclaveThermalPortCell], [VulcanizingAutoclaveThermalPortBlock], [VulcanizingAutoclaveThermalPortBlockEntity] has a cell and a thermal simulation object.
 * It forms a connection to any wire that is hooked up to it, and it connects to the [VulcanizingAutoclaveMainCell].
 * It doesn't have any logic.
 * */
//#region Vulcanizing Autoclave

/**
 * Recipe for the vulcanizing autoclave.
 * @param input The input ingredient. Must be a single item stack with 1 count.
 * @param output The output item. Must be a single item with 1 or more count.
 * @param burnOutput The output item, when the temperature exceeded the [maxTemperature].
 * @param duration The base duration, in seconds.
 * @param minTemperature If the temperature is below this threshold, the recipe will not progress.
 * @param maxTemperature If the temperature is above this threshold, the ingredients will burn.
 * */
class VulcanizingRecipe(
    val recipeSerializer: Serializer,
    val recipeId: ResourceLocation,
    val input: Ingredient,
    val output: ItemStack,
    val burnOutput: ItemStack,
    val duration: Double,
    val minTemperature: Double,
    val maxTemperature: Double
) : Recipe<SimpleContainer> {
    init {
        require(input.items.size == 1 && input.items[0].count == 1) {
            DEBUGGER_BREAK("Autoclave recipe requires exactly one/one input!")
        }
    }

    override fun matches(pContainer: SimpleContainer, pLevel: Level) = input.test(pContainer.getItem(INPUT_SLOT))
    override fun assemble(pContainer: SimpleContainer, pRegistryAccess: RegistryAccess): ItemStack = output.copy()
    override fun canCraftInDimensions(pWidth: Int, pHeight: Int) = true
    override fun getResultItem(pRegistryAccess: RegistryAccess): ItemStack = output.copy()

    override fun getId() = recipeId
    override fun getSerializer() = recipeSerializer
    override fun getType() = recipeSerializer.recipeType

    class Serializer(val recipeType: RecipeType<VulcanizingRecipe>) : RecipeSerializer<VulcanizingRecipe> {
        override fun fromJson(pRecipeId: ResourceLocation, pSerializedRecipe: JsonObject): VulcanizingRecipe {
            val input = Ingredient.fromJson(pSerializedRecipe.get("ingredient"))
            val output = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(pSerializedRecipe, "result"))
            val burnOutput = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(pSerializedRecipe, "resultBurnt"))
            val duration = pSerializedRecipe.getAsJsonPrimitive("duration").asDouble
            val minTemperature = pSerializedRecipe.getAsJsonPrimitive("minTemperature").asDouble
            val maxTemperature = pSerializedRecipe.getAsJsonPrimitive("maxTemperature").asDouble

            return VulcanizingRecipe(
                this,
                pRecipeId,
                input, output, burnOutput,
                duration,
                minTemperature, maxTemperature
            )
        }

        override fun fromNetwork(pRecipeId: ResourceLocation, pBuffer: FriendlyByteBuf): VulcanizingRecipe {
            val input = Ingredient.fromNetwork(pBuffer)
            val output = pBuffer.readItem()
            val burnOutput = pBuffer.readItem()
            val duration = pBuffer.readDouble()
            val minTemperature = pBuffer.readDouble()
            val maxTemperature = pBuffer.readDouble()

            return VulcanizingRecipe(
                this,
                pRecipeId,
                input, output, burnOutput,
                duration,
                minTemperature, maxTemperature
            )
        }

        override fun toNetwork(pBuffer: FriendlyByteBuf, pRecipe: VulcanizingRecipe) {
            pRecipe.input.toNetwork(pBuffer)
            pBuffer.writeItem(pRecipe.output)
            pBuffer.writeItem(pRecipe.burnOutput)
            pBuffer.writeDouble(pRecipe.duration)
            pBuffer.writeDouble(pRecipe.minTemperature)
            pBuffer.writeDouble(pRecipe.maxTemperature)
        }
    }
}

class VulcanizingAutoclaveThermalPortCell(
    ci: CellCreateInfo,
    thermalDef: ThermalMassDefinition,
    override val thermalMap: MonopoleMap,
    override val thermalSize: ThermalSize
) : Cell(ci), SidedThermalMonoMapped<VulcanizingAutoclaveThermalPortCell> {
    @SimObject
    val thermalWire = ThermalWireObject(this, thermalDef())
}

class VulcanizingAutoclaveThermalPortBlock : MultiblockDelegateUprightHorizontalDirectionCellBlock<VulcanizingAutoclaveThermalPortCell>() {
    @Deprecated("Deprecated in Java")
    override fun skipRendering(pState: BlockState, pAdjacentState: BlockState, pDirection: Direction) = true

    override fun getCellProvider() = Eln2Processing.VULCANIZING_AUTOCLAVE_THERMAL_PORT_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = VulcanizingAutoclaveThermalPortBlockEntity(pPos, pState)

    override fun spatialNeighborScan(level: Level, results: HashSet<CellAndContainerHandle>, cell: Cell) {
        val blockEntity = level.getBlockEntity(cell.locator.requireLocator(Locators.BLOCK)) as VulcanizingAutoclaveThermalPortBlockEntity

        val representative = getRepresentativeFromDelegateBlockEntity<VulcanizingAutoclaveMainBlockEntity?>(blockEntity, blockEntity.representativePos)
            ?: return

        /**
         * Scans the port itself:
         * */
        planarCellScan(
            level,
            cell,
            cell.locator.requireLocator(Locators.CONVENTIONAL_FACING).direction.opposite,
            results::add
        )

        /**
         * Links up the main:
         * */
        results.add(CellAndContainerHandle.captureInScope(representative.cell))
    }
}

class VulcanizingAutoclaveThermalPortBlockEntity(pPos: BlockPos, pBlockState: BlockState) :
    MultiblockDelegateCellBlockEntity<VulcanizingAutoclaveThermalPortCell>(pPos, pBlockState, Eln2Processing.VULCANIZING_AUTOCLAVE_THERMAL_PORT_BLOCK_ENTITY.get()),
    ValidateFormationBlockEntity<VulcanizingAutoclaveThermalPortBlockEntity>
{
    override fun validateMultiblockFormation() {
        check(cell.connections.isNotEmpty() && cell.connections.any { it is VulcanizingAutoclaveMainCell }) {
            DEBUGGER_BREAK("Vulcanizing autoclave didn't form correctly: the thermal port cell doesn't have the required connection (${cell.connections.size})")
        }
    }
}

class VulcanizingAutoclaveMainCell(ci: CellCreateInfo) : Cell(ci) {
    /**
     * Flag set by the block entity to indicate if a recipe is currently loaded.
     * */
    @CrossThreadAccess
    var isOperating = false // Not saved. The operation happens in the server loop.
        set(value) {
            if(field != value) {
                field = value
                setChanged()
            }
        }

    @CrossThreadAccess
    var doorClosed = false
        set(value) {
            if(field != value) {
                field = value
                setChanged()
            }
        }


    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::tick)
    }

    private fun applyLoss(dt: Double, loss: Quantity<ThermalConductance>) {
        val heatPort = connections[0] as VulcanizingAutoclaveThermalPortCell
        val thermalBody = heatPort.thermalWire.thermalBody

        var newEnergy = !thermalBody.energy - !(thermalBody.temperature - heatPort.environmentData.ambientTemperature) * !loss * dt

        if(newEnergy < 0.0) {
            newEnergy = 0.0
        }
        else {
            if(newEnergy / !thermalBody.mass / !thermalBody.material.specificHeat < !heatPort.environmentData.ambientTemperature) {
                newEnergy = !heatPort.environmentData.ambientTemperature * !thermalBody.mass * !thermalBody.material.specificHeat
            }
        }

        if(!newEnergy.approxEq(!thermalBody.energy)) {
            thermalBody.energy = Quantity(newEnergy, JOULE)
            heatPort.setChanged()
        }
    }

    /**
     * Applies thermal loss due to the recipe and, if the door is open, applies a large penalty.
     * */
    private fun tick(dt: Double, phase: SubscriberPhase) {
        if(isOperating) {
            applyLoss(dt, OPERATING_CONSUMPTION)
        }

        if(!doorClosed) {
            applyLoss(dt, OPEN_DOOR_LOSS)
        }
    }

    override fun saveCellData(): CompoundTag {
        val tag = CompoundTag()
        tag.putBoolean(DOOR_CLOSED, doorClosed)
        return tag
    }

    override fun loadCellData(tag: CompoundTag) {
        doorClosed = tag.getBoolean(DOOR_CLOSED)
    }

    companion object {
        private const val DOOR_CLOSED = "doorClosed"

        private val OPEN_DOOR_LOSS = Quantity(10.0, WATT_PER_KELVIN)
        private val OPERATING_CONSUMPTION = Quantity(1.0,  WATT_PER_KELVIN)
    }
}

class VulcanizingAutoclaveMainBlock : UprightHorizontalDirectionCellBlock<VulcanizingAutoclaveMainCell>() {
    @Deprecated("Deprecated in Java")
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean = true

    override fun getCellProvider() = Eln2Processing.VULCANIZING_AUTOCLAVE_MAIN_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = VulcanizingAutoclaveMainBlockEntity(pPos, pState)

    override fun <T : BlockEntity?> getTicker(
        pLevel: Level,
        pState: BlockState,
        pBlockEntityType: BlockEntityType<T?>
    ): BlockEntityTicker<T?> {
        return BlockEntityTicker(VulcanizingAutoclaveMainBlockEntity::tick)
    }

    override fun spatialNeighborScan(level: Level, results: HashSet<CellAndContainerHandle>, cell: Cell) {
        val pos = cell.locator.requireLocator(Locators.BLOCK)
        val facing = cell.locator.requireLocator(Locators.CONVENTIONAL_FACING)
        val blockEntity = level.getBlockEntity(pos) as VulcanizingAutoclaveMainBlockEntity

        /**
         * Only links up the delegate:
         * */
        blockEntity.delegateMap.forEachDelegateInWorld(level, facing.direction, pos) {
            val delegate = level.getBlockEntity(it) as? VulcanizingAutoclaveThermalPortBlockEntity
                ?: return@forEachDelegateInWorld

            results.add(CellAndContainerHandle.captureInScope(delegate.cell))
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
        if(pHand != InteractionHand.MAIN_HAND || pLevel.isClientSide) {
            return InteractionResult.FAIL
        }

        val blockEntity = pLevel.getBlockEntity(pPos) as? VulcanizingAutoclaveMainBlockEntity
            ?: return InteractionResult.FAIL

        return blockEntity.onUseByPlayer(pPlayer)
    }
}

class VulcanizingAutoclaveMainBlockEntity(pos: BlockPos, state: BlockState) :
    CellBlockEntity<VulcanizingAutoclaveMainCell>(pos, state, Eln2Processing.VULCANIZING_AUTOCLAVE_MAIN_BLOCK_ENTITY.get()),
    BigBlockRepresentativeBlockEntity<VulcanizingAutoclaveMainBlockEntity>,
    ValidateFormationBlockEntity<VulcanizingAutoclaveMainBlockEntity>,
    BulkPacketHandlerBlockEntity,
    WrenchInteractable
{
    companion object {
        private const val INVENTORY = "inventory"
        private const val STATE_MACHINE = "stateMachine"

        fun tick(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
            if (pLevel == null || pBlockEntity == null) {
                LOG.error("level or entity null")
                return
            }

            if (pBlockEntity !is VulcanizingAutoclaveMainBlockEntity) {
                LOG.error(DEBUGGER_BREAK("Got $pBlockEntity instead of autoclave block entity"))
                return
            }

            if (pLevel.isClientSide) {
                pBlockEntity.clientTick()
            }
            else {
                pBlockEntity.serverTick()
            }
        }
    }

    //#region Multiblock Setup

    override val delegateMap: MultiblockDelegateMap
        get() = Eln2Processing.VULCANIZING_AUTOCLAVE_DELEGATE_MAP.value

    override fun validateMultiblockFormation() {
        check(cell.connections.size == 1 && cell.connections[0] is VulcanizingAutoclaveThermalPortCell) {
            DEBUGGER_BREAK("Vulcanizing autoclave didn't form correctly: the main cell doesn't have the correct connections (${cell.connections.size})")
        }
    }

    override fun setDestroyed() {
        destroyDelegates()

        super.setDestroyed()
    }

    //#endregion

    //#region Inventory

    val inventoryHandler = InventoryHandler(this)
    val inventoryHandlerLazy: LazyOptional<InventoryHandler> = LazyOptional.of { inventoryHandler }

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return inventoryHandlerLazy.cast()
        }

        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        inventoryHandlerLazy.invalidate()
    }

    class InventoryHandler(val blockEntity: VulcanizingAutoclaveMainBlockEntity) : ItemStackHandler(2) {
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
            return stack
        }

        override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
            return ItemStack.EMPTY // prevents automation from extracting input
        }
    }

    //#endregion

    //#region Recipe Processing

    private class Operation(val recipe: VulcanizingRecipe, var progress: Double)

    private var operation: Operation? = null

    @ServerOnly
    fun searchForRecipe(): Optional<VulcanizingRecipe> {
        val stack = inventoryHandler.getStackInSlot(INPUT_SLOT)

        if(stack.isEmpty) {
            return Optional.empty()
        }

        val level = level!!

        return level.recipeManager.getRecipeFor(
            Eln2Processing.VULCANIZING_RECIPE,
            inventoryHandler.bind(),
            level
        )
    }

    //#endregion

    //#region Player Interaction

    /**
     * Opens/closes the door.
     * */
    override fun applyWrench(wrench: WrenchItem, context: UseOnContext): InteractionResult {
        if(context.level.isClientSide) {
            return InteractionResult.PASS
        }

        return if(stateMachine!!.doorAction()) {
            InteractionResult.SUCCESS
        }
        else {
            InteractionResult.FAIL
        }
    }

    /**
     * Takes out outputs or inserts items into the device, if the door is open.
     * */
    @ServerOnly
    fun onUseByPlayer(pPlayer: Player) : InteractionResult {
        val stateMachine = stateMachine!!

        if(stateMachine.doorState != StateMachine.DoorState.Open) {
            return InteractionResult.FAIL
        }

        val level = level as ServerLevel

        /**
         * Takes out the output, if possible:
         * */
        if(!inventoryHandler.getStackInSlot(OUTPUT_SLOT).isEmpty) {
            val stack = inventoryHandler.getStackInSlot(OUTPUT_SLOT)
            inventoryHandler.setStackInSlot(OUTPUT_SLOT, ItemStack.EMPTY)
            setChanged()

            val (x, y, z) = blockPos.toVector3d() + Vector3d(0.5) + representativeFacing.vector3d
            level.addItem(x, y, z, stack)

            // Potentially allows new stuff to happen:
            stateMachine.invalidateRecipe()

            return InteractionResult.SUCCESS
        }

        /**
         * Makes sure the input is clear. We only allow 1 count to exist in the slot:
         * */
        if(!inventoryHandler.getStackInSlot(INPUT_SLOT).isEmpty) {
            return InteractionResult.FAIL
        }

        val stackInHand = pPlayer.mainHandItem

        if(stackInHand.isEmpty) {
            return InteractionResult.FAIL
        }

        /**
         * Checks if a recipe exists:
         * */
        inventoryHandler.setStackInSlot(INPUT_SLOT, stackInHand.copyWithCount(1))
        if(searchForRecipe().isEmpty) {
            inventoryHandler.setStackInSlot(INPUT_SLOT, ItemStack.EMPTY)
            return InteractionResult.FAIL
        }

        /**
         * Accepts the recipe:
         * */
        stateMachine.invalidateRecipe()
        setChanged()

        return InteractionResult.CONSUME // Consumes 1 (as copied above)
    }

    //#endregion

    @ClientOnly
    override val clientSidePacketHandlerLazy = createClientSideHandler()

    @ServerOnly
    private var stateMachine: StateMachine? = null
    private var savedStateMachineData: CompoundTag? = null // The same issue as the cell graph; we use the same workaround

    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if(!pLevel.isClientSide) {
            stateMachine = StateMachine(pLevel as ServerLevel)
            if(savedStateMachineData != null) {
                stateMachine!!.loadTag(savedStateMachineData!!)
                savedStateMachineData = null
            }
        }
    }

    fun clientTick() {

    }

    @ServerOnly
    fun serverTick() {
        val stateMachine = stateMachine!!

        /**
         * Updates the state machine and sends render messages:
         * */
        run {
            stateMachine.update()

            stateMachine.messageList.forEach {
                sendBulkPacket(it)
            }

            stateMachine.messageList.clear()
        }

        cell.doorClosed = !(stateMachine.doorState == StateMachine.DoorState.Opening || stateMachine.doorState == StateMachine.DoorState.Open)

        if(stateMachine.recipeInvalidated()) {
            val recipe = searchForRecipe()

            /**
             * The recipe is no longer valid:
             * */
            if(recipe.isEmpty) {
                stateMachine.clearOperation()
            }
            /**
             * Start new operation:
             * */
            else {
                stateMachine.beginOperation(recipe.get())
            }
        }
        else {
            val operation = stateMachine.operation

            if(operation != null) {
                cell.isOperating = true
                /**
                 * Tries to finish the operation:
                 * */
                if(stateMachine.progressOperation()) {
                    inventoryHandler.setStackInSlot(OUTPUT_SLOT, operation.recipe.output.copy())
                }
            }
            /**
             * Machine is idle:
             * */
            else {
                cell.isOperating = false
            }
        }

        if(stateMachine.needsSave()) {
            setChanged()
        }
    }

    override fun setupPacketsOnClient(handler: ClientSidePacketHandlerBuilder) {

    }

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)
        pTag.put(INVENTORY, inventoryHandler.serializeNBT())
        pTag.put(STATE_MACHINE, stateMachine!!.saveTag())
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)
        inventoryHandler.deserializeNBT(pTag.getCompound(INVENTORY))
        savedStateMachineData = pTag.getCompound(STATE_MACHINE)
    }

    @ServerOnly @OnServerThread
    class StateMachine(val level: ServerLevel) {
        companion object {
            const val DOOR_TURN_RATE = 0.25

            private const val DOOR_STATE = "doorState"
            private const val DOOR_SWITCH_PROGRESS = "doorSwitchProgress"
            private const val RECIPE_INVALIDATED = "recipeInvalidated"
            private const val OPERATION = "operation"
            private const val OPERATION_RECIPE = "recipe"
            private const val OPERATION_PROGRESS = "progress"
        }

        var doorState = DoorState.Closed
            private set

        var doorSwitchProgress = 0.0
            private set

        //#region Dirty

        private var saveChanged = false

        fun needsSave() : Boolean {
            val result = saveChanged
            saveChanged = false
            return result
        }

        //#endregion

        //#region Recipe State

        private var recipeInvalidated = false

        fun recipeInvalidated() : Boolean {
            val result = recipeInvalidated

            if(result) {
                saveChanged = true
            }

            recipeInvalidated = false
            return result
        }

        fun invalidateRecipe() {
            if(recipeInvalidated) {
                return
            }

            recipeInvalidated = true
            saveChanged = true
        }

        class Operation(val recipe: VulcanizingRecipe, var progress: Double)

        var operation: Operation? = null
            private set

        fun clearOperation() {
            if(operation == null) {
                return
            }

            operation = null
            saveChanged = true
        }

        fun beginOperation(recipe: VulcanizingRecipe) {
            operation = Operation(recipe, 0.0)
            saveChanged = true
        }

        fun progressOperation() : Boolean {
            val operation = operation
                ?: error("Cannot progress operation!")

            val rate = 1.0 / operation.recipe.duration
            operation.progress += rate * (1.0 / 20.0)

            saveChanged = true

            if(operation.progress >= 1.0 || operation.progress.approxEq(1.0)) {
                clearOperation()
                return true
            }

            return false
        }

        //#endregion

        val messageList = ArrayList<Any>()

        private fun updateDoor() {
            if(doorState == DoorState.Open || doorState == DoorState.Closed) {
                return
            }

            doorSwitchProgress += DOOR_TURN_RATE * (1.0 / 20.0)
            saveChanged = true

            if(doorSwitchProgress > 1.0 || doorSwitchProgress.approxEq(1.0)) {
                doorSwitchProgress = 0.0

                if(doorState == DoorState.Opening) {
                    doorState = DoorState.Open

                    messageList.add(DoorStateSwitchMessage(
                        DoorState.Opening,
                        DoorState.Open
                    ))
                }
                else {
                    doorState = DoorState.Closed

                    messageList.add(DoorStateSwitchMessage(
                        DoorState.Closing,
                        DoorState.Closed
                    ))

                    invalidateRecipe()
                }
            }
        }

        fun doorAction() = when(doorState) {
            DoorState.Open -> {
                doorState = DoorState.Closing
                true
            }
            DoorState.Closed ->  {
                doorState = DoorState.Opening
                clearOperation()
                true
            }
            else -> false
        }

        fun update() {
            check(messageList.isEmpty()) {
                "Non-consumed messages! $this"
            }

            updateDoor()
        }

        enum class DoorState(val index: Int) {
            Opening(0),
            Open(1),
            Closing(2),
            Closed(3);
        }

        @Serializable
        data class DoorStateSwitchMessage(val previousState: DoorState, val newState: DoorState)

        fun saveTag() : CompoundTag {
            val tag = CompoundTag()
            tag.putInt(DOOR_STATE, doorState.index)
            tag.putDouble(DOOR_SWITCH_PROGRESS, doorSwitchProgress)
            tag.putBoolean(RECIPE_INVALIDATED, recipeInvalidated)

            operation?.also {
                val operationTag = CompoundTag()
                operationTag.putResourceLocation(OPERATION_RECIPE, it.recipe.recipeId)
                operationTag.putDouble(OPERATION_PROGRESS, it.progress)
                tag.put(OPERATION, operationTag)
            }

            return tag
        }

        fun loadTag(tag: CompoundTag) {
            doorState = DoorState.entries[tag.getInt(DOOR_STATE)]
            doorSwitchProgress = tag.getDouble(DOOR_SWITCH_PROGRESS)
            recipeInvalidated = tag.getBoolean(RECIPE_INVALIDATED)

            if(tag.contains(OPERATION)) {
                val operationTag = tag.getCompound(OPERATION)
                val recipeId = operationTag.getResourceLocation(OPERATION_RECIPE)
                val progress = operationTag.getDouble(OPERATION_PROGRESS)

                val recipe = level.recipeManager.byKey(recipeId)

                if(recipe.isEmpty) {
                    error("Could not resolve saved recipe $recipeId!")
                }

                operation = Operation(recipe.get() as VulcanizingRecipe, progress)
            }
        }
    }
}

class VulcanizingAutoclaveMainBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: VulcanizingAutoclaveMainBlockEntity,
    partialTick: Float
) : AbstractBlockEntityVisual<VulcanizingAutoclaveMainBlockEntity>(ctx, blockEntity, partialTick), ShaderLightVisual {
    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.VULCANIZING_AUTOCLAVE_BODY, FlwMaterials.TRANSLUCENT_SMOOTH_LIT))
        .createInstance()
        .also {
            it.translate(visualPosition)
            it.center()
            it.rotateToFace(blockEntity.representativeFacing.clockWise)
            it.uncenter()
        }

    override fun updateLight(p0: Float) {
        // NOOP
    }

    override fun collectCrumblingInstances(p0: Consumer<Instance?>) {
        p0.accept(body)
    }

    override fun _delete() {
        body.delete()
    }
}

//#endregion
