@file:Suppress("NonAsciiCharacters")

package org.eln2.mc.common.content

import com.jozufozu.flywheel.util.Color
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.SmeltingRecipe
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractFurnaceBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.items.ItemStackHandler
import net.minecraftforge.items.SlotItemHandler
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.Vector2di
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Simulator
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.electrical.mna.LARGE_RESISTANCE
import org.ageseries.libage.sim.electrical.mna.component.updateResistance
import org.eln2.mc.EnvironmentalTemperatureField
import org.eln2.mc.LOG
import org.eln2.mc.common.blocks.foundation.CellBlock
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.events.runPre
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.extensions.addPlayerGrid
import org.eln2.mc.extensions.constructMenu
import org.eln2.mc.extensions.getQuantity
import org.eln2.mc.extensions.putQuantity
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.readInto
import org.eln2.mc.resource
import kotlin.math.abs

data class FurnaceOptions(
    var idleResistance: Quantity<Resistance>,
    var runningResistance: Quantity<Resistance>,
    var temperatureThreshold: Quantity<Temperature>,
    var targetTemperature: Quantity<Temperature>,
    var surfaceArea: Quantity<Area>,
    var connectionParameters: ConnectionParameters,
)

class FurnaceCell(ci: CellCreateInfo, dir1: Base6Direction3d, dir2: Base6Direction3d) : Cell(ci) {
    companion object {
        private const val TEMPERATURE = "temperature"
    }

    @SimObject
    val resistorObj = ResistorObjectVirtual(this, directionPoleMapPlanar(dir1, dir2))

    init {
        ruleSet.withDirectionRulePlanar(dir1 + dir2)
    }

    override fun saveCellData() = CompoundTag().also {
        it.putQuantity(TEMPERATURE, resistorThermalMass.temperature)
    }

    override fun loadCellData(tag: CompoundTag) {
        resistorThermalMass.temperature = tag.getQuantity(TEMPERATURE)
    }

    val options = FurnaceOptions(
        Quantity(LARGE_RESISTANCE),
        Quantity(100.0),
        Quantity(600.0, CELSIUS),
        Quantity(800.0, CELSIUS),
        Quantity(1.0, METER2),
        ConnectionParameters.DEFAULT
    )

    private var resistorThermalMass = ThermalMass(
        ChemicalElement.Iron.asMaterial,
        mass = Quantity(0.5, KILOGRAM)
    ).also {
        environmentData.getOrNull<EnvironmentalTemperatureField>()?.readInto(it)
    }

    private var knownSmeltee: ThermalMass? = null
    private var gameThreadSmeltee: ThermalMass? = null

    val internalTemperature
        get() = resistorThermalMass.temperature

    val smelteeTemperature
        get() = knownSmeltee?.temperature ?: internalTemperature

    fun loadSmeltingBody(body: ThermalMass) {
        gameThreadSmeltee = body
    }

    fun unloadSmeltingBody() {
        gameThreadSmeltee = null
    }

    private val needsBurn get() = knownSmeltee != null

    private val internalSimulator = Simulator().also {
        it.add(resistorThermalMass)

        environmentData.getOrNull<EnvironmentalTemperatureField>()?.readTemperature()?.also { temperature ->
            it.connect(resistorThermalMass, temperature)
        }
    }

    var isHot: Boolean = false
        private set

    override fun subscribe(subs: SubscriberCollection) {
        subs.addPre(this::simulationTick)
    }

    private fun applyControlSignal() {
        resistorObj.updateResistance(
            if (resistorThermalMass.temperature < options.targetTemperature) {
                !options.runningResistance
            } else {
                !options.idleResistance
            }
        )
    }

    private fun updateThermalSimulation(dt: Double) {
        resistorThermalMass.energy += abs(resistorObj.power) * dt
        internalSimulator.step(dt)
    }

    private fun updateBurnState() {
        val knownSmeltingBody = this.knownSmeltee

        isHot = if (knownSmeltingBody == null) {
            false
        } else {
            knownSmeltingBody.temperature > options.temperatureThreshold
        }
    }

    private fun applyExternalUpdates() {
        val gameThreadSmeltee = gameThreadSmeltee

        if (gameThreadSmeltee == null) {
            if (knownSmeltee != null) {
                // We had a smeltee, but it was removed externally:
                internalSimulator.remove(knownSmeltee!!)
                knownSmeltee = null
            }
        } else {
            if (gameThreadSmeltee != knownSmeltee) {
                if (knownSmeltee != null) {
                    // Remove old body
                    internalSimulator.remove(knownSmeltee!!)
                }

                knownSmeltee = gameThreadSmeltee

                internalSimulator.add(gameThreadSmeltee)

                internalSimulator.connect(
                    gameThreadSmeltee,
                    resistorThermalMass,
                    options.connectionParameters
                )
            }
        }
    }

    private fun simulationTick(elapsed: Double, phase: SubscriberPhase) {
        applyExternalUpdates()
        updateThermalSimulation(elapsed)
        updateBurnState()

        if (needsBurn) {
            // A body needs heating, so we start updating the resistor values.
            applyControlSignal()
        }
        else {
            // No bodies are loaded in, we will idle here.
            resistorObj.updateResistance(!options.idleResistance)
            isHot = false
        }
    }
}

class FurnaceBlockEntity(pos: BlockPos, state: BlockState) : CellBlockEntity<FurnaceCell>(pos, state, Content.FURNACE_BLOCK_ENTITY.get()) {
    companion object {
        const val INPUT_SLOT = 0
        const val OUTPUT_SLOT = 1
        private const val BURN_TIME_TARGET = 40

        private const val FURNACE = "furnace"
        private const val INVENTORY = "inventory"
        private const val BURN_TIME = "burnTime"
        private const val BURNING = "burning"

        fun tick(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
            if (pLevel == null || pBlockEntity == null) {
                LOG.error("level or entity null")
                return
            }

            if (pBlockEntity !is FurnaceBlockEntity) {
                LOG.error("Got $pBlockEntity instead of furnace")
                return
            }

            if (!pLevel.isClientSide) {
                pBlockEntity.serverTick()
            }
        }
    }

    class InventoryHandler(private val furnaceBlockEntity: FurnaceBlockEntity) : ItemStackHandler(2) {
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
            LOG.info("Inventory Handler inserts $slot $stack")

            if (slot == INPUT_SLOT) {
                return if (canSmelt(stack)) {
                    super.insertItem(slot, stack, simulate).also {
                        if (it != stack) {
                            furnaceBlockEntity.inputChanged()
                        }
                    }
                } else {
                    stack
                }
            }

            if (slot == OUTPUT_SLOT) {
                return stack
            }

            error("Unknown slot $slot")
        }

        fun insertOutput(stack: ItemStack): Boolean {
            return super.insertItem(OUTPUT_SLOT, stack, false) != stack
        }

        private fun canSmelt(stack: ItemStack): Boolean {
            val recipeManager = furnaceBlockEntity.level!!.recipeManager
            val recipe = recipeManager.getRecipeFor(RecipeType.SMELTING, SimpleContainer(stack), furnaceBlockEntity.level!!)

            return recipe.isPresent
        }

        fun clear() {
            super.setStackInSlot(INPUT_SLOT, ItemStack.EMPTY)
            super.setStackInSlot(OUTPUT_SLOT, ItemStack.EMPTY)
        }

        override fun isItemValid(slot: Int, stack: ItemStack): Boolean {
            return super.isItemValid(slot, stack) && run {
                if(slot == INPUT_SLOT) {
                    canSmelt(stack)
                }
                else {
                    true
                }
            }
        }

        val isEmpty = super.getStackInSlot(INPUT_SLOT).isEmpty && super.getStackInSlot(OUTPUT_SLOT).isEmpty
    }

    class FurnaceData : SimpleContainerData(5) {
        companion object {
            private const val RESISTOR_TEMPERATURE = 0
            private const val RESISTOR_TARGET_TEMPERATURE = 1
            private const val BODY_TEMPERATURE = 2
            private const val BODY_TARGET_TEMPERATURE = 3
            private const val SMELT_PROGRESS = 4
        }

        var resistorTemperature: Int
            get() = this.get(RESISTOR_TEMPERATURE)
            set(value) {
                this.set(RESISTOR_TEMPERATURE, value)
            }

        var resistorTargetTemperature: Int
            get() = this.get(RESISTOR_TARGET_TEMPERATURE)
            set(value) {
                this.set(RESISTOR_TARGET_TEMPERATURE, value)
            }

        val resistorTemperatureProgress: Double
            get() = (resistorTemperature.toDouble() / resistorTargetTemperature.toDouble()).coerceIn(0.0, 1.0)

        var bodyTemperature: Int
            get() = this.get(BODY_TEMPERATURE)
            set(value) { this.set(BODY_TEMPERATURE, value) }

        var bodyTargetTemperature: Int
            get() = this.get(BODY_TARGET_TEMPERATURE)
            set(value) { this.set(BODY_TARGET_TEMPERATURE, value) }

        val bodyTemperatureProgress: Double
            get() = (bodyTemperature.toDouble() / bodyTargetTemperature.toDouble()).coerceIn(0.0, 1.0)

        var smeltProgress: Double
            get() = (this.get(SMELT_PROGRESS) / 16384.0).coerceIn(0.0, 1.0)
            set(value) { this.set(SMELT_PROGRESS, (value * 16384).toInt().coerceIn(0, 16384)) }
    }

    private var burnTime = 0

    val inventoryHandler = InventoryHandler(this)
    private val inventoryHandlerLazy = LazyOptional.of { inventoryHandler }
    private var saveTag: CompoundTag? = null

    val data = FurnaceData()

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return inventoryHandlerLazy.cast()
        }

        return super.getCapability(cap, side)
    }

    private var isBurning = false

    private fun loadBurningItem() {
        data.smeltProgress = 0.0

        burnTime = 0

        val inputStack = inventoryHandler.getStackInSlot(INPUT_SLOT)

        isBurning = if (!inputStack.isEmpty) {
            cell!!.loadSmeltingBody(
                ThermalMass(
                    ChemicalElement.Iron.asMaterial,
                    mass = Quantity(0.1, KILOGRAM)
                ).also {
                    cell!!.environmentData.getOrNull<EnvironmentalTemperatureField>()?.readInto(it)
                }
            )

            recipe = level!!
                .recipeManager
                .getRecipeFor(RecipeType.SMELTING, SimpleContainer(inputStack), level!!)
                .get()

            true
        } else {
            cell!!.unloadSmeltingBody()

            false
        }
    }

    private var recipe: SmeltingRecipe? = null

    fun inputChanged() {
        if (!isBurning) {
            runPre {
                if (!isRemoved) {
                    loadBurningItem()
                }
            }
        }
    }

    fun serverTick() {
        val cell = cell!!

        val isHot = cell.isHot

        if (isHot != blockState.getValue(AbstractFurnaceBlock.LIT)) {
            // A sync is needed here.

            level!!.setBlock(
                blockPos,
                blockState.setValue(AbstractFurnaceBlock.LIT, isHot),
                Block.UPDATE_ALL
            )
        }

        if (!isBurning) {
            // Nothing can be smelted.

            return
        }

        // The saved data is always changing while we're smelting.
        setChanged()

        data.resistorTemperature = cell.internalTemperature.value.toInt()
        data.resistorTargetTemperature = cell.options.targetTemperature.value.toInt()

        data.bodyTemperature = cell.smelteeTemperature.value.toInt()
        data.bodyTargetTemperature = cell.options.temperatureThreshold.value.toInt()

        inventoryHandlerLazy.ifPresent { inventory ->
            val inputStack = inventory.getStackInSlot(INPUT_SLOT)

            if (inputStack.isEmpty) {
                loadBurningItem()

                return@ifPresent
            }

            if (burnTime >= BURN_TIME_TARGET) {
                val recipe = this.recipe ?: error("Burning without recipe available")

                if (!inventory.insertOutput(ItemStack(recipe.getResultItem(RegistryAccess.EMPTY).item, 1))) {
                    LOG.error("Failed to export item")
                } else {
                    // Done, load next (also remove input item)
                    inventory.setStackInSlot(INPUT_SLOT, ItemStack(inputStack.item, inputStack.count - 1))

                    loadBurningItem()
                }

                data.smeltProgress = 1.0
            } else {
                if (isHot) {
                    burnTime++
                }

                data.smeltProgress = (burnTime / BURN_TIME_TARGET.toDouble()).coerceIn(0.0, 1.0)
            }
        }
    }

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)

        val tag = CompoundTag()

        inventoryHandlerLazy.ifPresent {
            tag.put(INVENTORY, it.serializeNBT())
        }

        tag.putInt(BURN_TIME, burnTime)

        pTag.put(FURNACE, tag)
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)

        saveTag = pTag.get(FURNACE) as? CompoundTag
    }

    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if (saveTag == null) {
            return
        }

        val inventoryTag = saveTag!!.get(INVENTORY) as? CompoundTag

        if (inventoryTag != null) {
            inventoryHandler.deserializeNBT(inventoryTag)
        }

        // This resets burnTime, so we load it before loading burnTime:
        loadBurningItem()

        burnTime = saveTag!!.getInt(BURN_TIME)

        // GC reference tracking
        saveTag = null
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        val cell = this.cell

        if(cell != null) {
            builder.power(cell.resistorObj.power)
            builder.current(cell.resistorObj.current)
            builder.quantity(cell.internalTemperature)
        }
    }
}

class FurnaceMenu(
    pContainerId: Int,
    playerInventory: Inventory,
    handler: ItemStackHandler,
    val containerData: FurnaceBlockEntity.FurnaceData,
    val entity: FurnaceBlockEntity?,
) : AbstractContainerMenu(Content.FURNACE_MENU.get(), pContainerId) {
    companion object {
        fun create(id: Int, inventory: Inventory, player: Player, entity: FurnaceBlockEntity): FurnaceMenu {
            return FurnaceMenu(
                id,
                inventory,
                entity.inventoryHandler,
                entity.data,
                entity
            )
        }
    }

    constructor(pContainerId: Int, playerInventory: Inventory) : this(
        pContainerId,
        playerInventory,
        ItemStackHandler(2),
        FurnaceBlockEntity.FurnaceData(),
        null
    )

    private val playerGridStart: Int
    private val playerGridEnd: Int

    init {
        addSlot(SlotItemHandler(handler, FurnaceBlockEntity.INPUT_SLOT, 56, 35))
        addSlot(SlotItemHandler(handler, FurnaceBlockEntity.OUTPUT_SLOT, 116, 35))
        addDataSlots(containerData)

        playerGridStart = 2
        playerGridEnd = playerGridStart + this.addPlayerGrid(playerInventory, this::addSlot)
    }

    override fun stillValid(pPlayer: Player): Boolean {
        return true
    }

    override fun quickMoveStack(pPlayer: Player, pIndex: Int): ItemStack {
        val slot = slots[pIndex]

        if (!slot.hasItem()) {
            return ItemStack.EMPTY
        }

        val stack = slot.item

        if (pIndex == FurnaceBlockEntity.INPUT_SLOT || pIndex == FurnaceBlockEntity.OUTPUT_SLOT) {
            // Quick move from input/output to player

            if (!moveItemStackTo(stack, playerGridStart, playerGridEnd, true)) {
                return ItemStack.EMPTY
            }
        } else {
            // Only move into input slot

            if (!moveItemStackTo(
                    stack,
                    FurnaceBlockEntity.INPUT_SLOT,
                    FurnaceBlockEntity.INPUT_SLOT + 1,
                    true
                )
            ) {
                return ItemStack.EMPTY
            }
        }

        slot.setChanged()

        entity?.inputChanged()

        return stack
    }
}

class FurnaceScreen(menu: FurnaceMenu, playerInventory: Inventory, title: Component) :
    AbstractContainerScreen<FurnaceMenu>(menu, playerInventory, title) {
    companion object {
        private val TEXTURE = resource("textures/gui/container/furnace.png")

        private val RESISTOR_INDICATOR_POS = Vector2di(13, 28)
        private val BODY_INDICATOR_POS = Vector2di(27, 28)

        private const val INDICATOR_HEIGHT = 57 - 28
        private const val INDICATOR_WIDTH = 21 - 13

        private val PROGRESS_ARROW_POS = Vector2di(79, 34)
        private val PROGRESS_UV_POS = Vector2di(176, 14)
        private val PROGRESS_UV_SIZE = Vector2di(32, 16)
    }

    private fun renderIndicator(pGuiGraphics: GuiGraphics, position: Vector2di, progress: Double) {
        val vertical = map(
            progress,
            0.0,
            1.0,
            0.0,
            INDICATOR_HEIGHT.toDouble()
        )

        pGuiGraphics.fill(
            leftPos + position.x,
            topPos + position.y,
            position.x + INDICATOR_WIDTH + leftPos,
            position.y + vertical.toInt() + topPos,
            Color(255, 0, 0, 100).rgb
        )
    }

    private fun renderTemperatureIndicators(pGuiGraphics: GuiGraphics) {
        renderIndicator(pGuiGraphics, RESISTOR_INDICATOR_POS, menu.containerData.resistorTemperatureProgress)
        renderIndicator(pGuiGraphics, BODY_INDICATOR_POS, menu.containerData.bodyTemperatureProgress)
    }

    private fun renderProgressArrow(pGuiGraphics: GuiGraphics) {
        pGuiGraphics.blit(
            TEXTURE,
            leftPos + PROGRESS_ARROW_POS.x,
            topPos + PROGRESS_ARROW_POS.y,
            PROGRESS_UV_POS.x.toFloat(),
            PROGRESS_UV_POS.y.toFloat(),
            map(
                menu.containerData.smeltProgress.toFloat(),
                0f,
                1f,
                0f,
                PROGRESS_UV_SIZE.x.toFloat()
            ).toInt(),
            PROGRESS_UV_SIZE.y,
            256,
            256
        )
    }

    override fun render(pGuiGraphics: GuiGraphics, pMouseX: Int, pMouseY: Int, pPartialTick: Float) {
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick)
        renderTemperatureIndicators(pGuiGraphics)
        renderProgressArrow(pGuiGraphics)
    }

    override fun renderBg(pGuiGraphics: GuiGraphics, pPartialTick: Float, pMouseX: Int, pMouseY: Int) {
        pGuiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight)
    }
}

class FurnaceBlock : CellBlock<FurnaceCell>() {
    init {
        registerDefaultState(defaultBlockState().setValue(AbstractFurnaceBlock.LIT, false))
    }

    override fun createBlockStateDefinition(pBuilder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(pBuilder)
        pBuilder.add(AbstractFurnaceBlock.LIT)
    }

    override fun getCellProvider(): CellProvider<FurnaceCell> {
        return Content.FURNACE_CELL.get()
    }

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState): BlockEntity {
        return FurnaceBlockEntity(pPos, pState)
    }

    override fun <T : BlockEntity?> getTicker(
        pLevel: Level,
        pState: BlockState,
        pBlockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T> {
        return BlockEntityTicker(FurnaceBlockEntity::tick)
    }

    @Deprecated("Fuck you", replaceWith = ReplaceWith("Go screw yourself, minecraft"))
    override fun use(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult,
    ): InteractionResult {
        return pLevel.constructMenu(pPos, pPlayer, { Component.literal("Furnace") }, FurnaceMenu::create)
    }

    override fun animateTick(pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) {
        if(pState.getValue(AbstractFurnaceBlock.LIT)) {
            val d0 = pPos.x.toDouble() + 0.5
            val d1 = pPos.y.toDouble()
            val d2 = pPos.z.toDouble() + 0.5
            if (pRandom.nextDouble() < 0.1) {
                pLevel.playLocalSound(
                    d0,
                    d1,
                    d2,
                    SoundEvents.FURNACE_FIRE_CRACKLE,
                    SoundSource.BLOCKS,
                    1.0f,
                    1.0f,
                    false
                )
            }

            val direction = pState.getValue(AbstractFurnaceBlock.FACING)
            val d4 = pRandom.nextDouble() * 0.6 - 0.3
            val d5 = if (direction.axis === Direction.Axis.X) direction.stepX.toDouble() * 0.52 else d4
            val d6 = pRandom.nextDouble() * 6.0 / 16.0
            val d7 = if (direction.axis === Direction.Axis.Z) direction.stepZ.toDouble() * 0.52 else d4
            pLevel.addParticle(ParticleTypes.SMOKE, d0 + d5, d1 + d6, d2 + d7, 0.0, 0.0, 0.0)
            pLevel.addParticle(ParticleTypes.FLAME, d0 + d5, d1 + d6, d2 + d7, 0.0, 0.0, 0.0)
        }
    }
}
