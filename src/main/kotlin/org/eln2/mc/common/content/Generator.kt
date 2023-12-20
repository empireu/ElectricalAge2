@file:Suppress("UNUSED_VARIABLE", "LocalVariableName", "NonAsciiCharacters")

package org.eln2.mc.common.content

import kotlinx.serialization.Serializable
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.common.ForgeHooks
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.items.ItemStackHandler
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.InterpolationFunction
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.snzi
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.mna.component.PowerVoltageSource
import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.common.blocks.foundation.CellBlock
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.containers.foundation.ContainerHelper
import org.eln2.mc.common.containers.foundation.MyAbstractContainerScreen
import org.eln2.mc.common.containers.foundation.SlotItemHandlerWithPlacePredicate
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.network.serverToClient.PacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.control.PIDController
import org.eln2.mc.data.Locator
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.resource
import kotlin.math.*


/**
 * Represents a mass of fuel which is mutable (the amount of fuel can be changed).
 * @param fuelAmount The initial amount of fuel.
 * @param energyDensity The energy density (quantity of energy per unit mass).
 * */
class FuelBurnState(var fuelAmount: Quantity<Mass>, val energyDensity: Quantity<MassEnergyDensity>) {
    companion object {
        private const val AMOUNT = "amount"
        private const val ENERGY_DENSITY = "energyDensity"

        fun fromNbt(tag: CompoundTag): FuelBurnState {
            return FuelBurnState(
                tag.getQuantity(AMOUNT),
                tag.getQuantity(ENERGY_DENSITY)
            )
        }

        fun canBurn(itemStack: ItemStack) = ForgeHooks.getBurnTime(itemStack, null) > 0

        /**
         * Creates a [FuelBurnState] from the item. Precondition: [canBurn]
         * Assumes that the item is mostly carbon, similar to the allotrope Coal.
         * Based on the [ForgeHooks.getBurnTime] of the item, the mass of "coal" is adjusted.
         * Example: If the burn time is equal to coal, the fuel will be 1kg of Coal. If the burn time is half, the result will be 0.5kg of Coal.
         * */
        fun createFromStack(itemStack: ItemStack) : FuelBurnState {
            val burnTime = ForgeHooks.getBurnTime(itemStack, null)

            val coalBurnTime = 1600.0
            val amount = burnTime / coalBurnTime

            return FuelBurnState(
                Quantity(amount, KILOGRAM),
                Quantity(24.0, MEGA * JOULE_PER_KILOGRAM)
            )
        }
    }

    /**
     * Gets the amount of available energy, based on the remaining [fuelAmount].
     * */
    val availableEnergy get() = Quantity<Energy>(!fuelAmount * !energyDensity)

    /**
     * Removes a mass of fuel corresponding to [energy] amount of energy.
     * */
    fun removeEnergy(energy: Quantity<Energy>) {
        fuelAmount -= !energy / !energyDensity
    }

    fun toNbt(): CompoundTag {
        return CompoundTag().also {
            it.putQuantity(AMOUNT, fuelAmount)
            it.putQuantity(ENERGY_DENSITY, energyDensity)
        }
    }
}

class FuelBurnerBehavior(val cell: Cell, val body: ThermalMass) : CellBehavior {
    companion object {
        private const val FUEL = "fuel"
        private const val PID = "pid"
        private val DESIRED_TEMPERATURE = Quantity(700.0, CELSIUS)
        private val MAX_POWER = Quantity(100.0, KILO * WATT)
    }

    private var fuel: FuelBurnState? = null
    private val updates = AtomicUpdate<FuelBurnState>()

    private val pid = PIDController(25.0, 0.0, 0.0).also {
        it.setPoint = 1.0
        it.minControl = 0.0
        it.maxControl = 1.0
    }

    fun updateFuel(mass: FuelBurnState) = updates.setLatest(mass)

    val availableEnergy get() = fuel?.availableEnergy ?: Quantity(0.0)

    private var signal = 0.0
    private var thermalPower = 0.0

    override fun subscribe(subscribers: SubscriberCollection) = subscribers.addPre(this::simulationTick)

    private fun simulationTick(dt: Double, phase: SubscriberPhase) {
        updates.consume {
            fuel = it
            pid.reset()
            cell.setChanged()
        }

        val fuel = this.fuel ?: return

        signal = pid.update(body.temperature / DESIRED_TEMPERATURE, dt)

        val heat = min(!fuel.availableEnergy, signal * !MAX_POWER * dt)

        thermalPower = heat / dt

        if(!heat.approxEq(0.0)) {
            fuel.removeEnergy(Quantity(heat))
            body.energy += Quantity(heat)
            cell.setChanged()
        }
    }

    fun submitDisplay(builder: ComponentDisplayList) {
        builder.debug("*Control Signal ${(signal * 1000).formatted(2)}")
        builder.power(thermalPower)
        builder.translateQuantityRow("fuel_remaining", (fuel?.fuelAmount ?: Quantity(0.0)))
        builder.translateQuantityRow("energy_remaining", availableEnergy)
    }

    fun saveNbt() = CompoundTag()
        .withSubTagOptional(FUEL, fuel?.toNbt())
        .withSubTag(PID, pid.stateToNbt())

    fun loadNbt(tag: CompoundTag) = tag
        .useSubTagIfPreset(FUEL) { fuel = FuelBurnState.fromNbt(it) }
        .useSubTagIfPreset(PID) { pid.stateFromNbt(it) }
}

class HeatGeneratorCell(ci: CellCreateInfo, thermalDef: ThermalMassDefinition, leakageParameters: ConnectionParameters) : Cell(ci), ThermalContactInfo {
    companion object {
        private const val BURNER_BEHAVIOR = "burner"
    }

    @SimObject
    val thermalWire = ThermalWireObject(this, thermalDef(), leakageParameters)

    @Behavior
    val burner = FuelBurnerBehavior(this, thermalWire.thermalBody)

    init {
        ruleSet.withDirectionRulePlanar(Base6Direction3dMask.HORIZONTALS)
    }

    val needsFuel get() = burner.availableEnergy.value approxEq 0.0

    fun replaceFuel(mass: FuelBurnState) = burner.updateFuel(mass)

    override fun loadCellData(tag: CompoundTag) {
        tag.useSubTagIfPreset(BURNER_BEHAVIOR, burner::loadNbt)
    }

    override fun saveCellData(): CompoundTag {
        return CompoundTag().withSubTag(BURNER_BEHAVIOR, burner.saveNbt())
    }

    override fun getContactTemperature(other: Locator) = thermalWire.thermalBody.temperature
}

class HeatGeneratorBlockEntity(pos: BlockPos, state: BlockState) : CellBlockEntity<HeatGeneratorCell>(pos, state, Content.HEAT_GENERATOR_BLOCK_ENTITY.get()), ComponentDisplay {
    companion object {
        const val FUEL_SLOT = 0

        private const val INVENTORY = "inventory"

        fun tick(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
            if (pLevel == null || pBlockEntity == null) {
                return
            }

            if (pBlockEntity !is HeatGeneratorBlockEntity) {
                LOG.error("Got $pBlockEntity instead of heat generator")
                return
            }

            if (!pLevel.isClientSide) {
                pBlockEntity.serverTick()
            }
        }
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(cell.thermalWire.thermalBody.temperature)
        cell.burner.submitDisplay(builder)
    }

    class InventoryHandler(private val blockEntity: HeatGeneratorBlockEntity) : ItemStackHandler(1) {
        override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
            if (!FuelBurnState.canBurn(stack)) {
                return ItemStack.EMPTY
            }

            return super.insertItem(slot, stack, simulate)
        }

        override fun onContentsChanged(slot: Int) {
            blockEntity.setChanged()
        }
    }

    val inventoryHandler = InventoryHandler(this)

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return LazyOptional.of { inventoryHandler }.cast()
        }

        return super.getCapability(cap, side)
    }

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)
        pTag.put(INVENTORY, inventoryHandler.serializeNBT())
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)
        pTag.useSubTagIfPreset(INVENTORY, inventoryHandler::deserializeNBT)
    }

    fun serverTick() {
        if (!cell!!.needsFuel) {
            return
        }

        val stack = inventoryHandler.extractItem(FUEL_SLOT, 1, false)

        if (stack.isEmpty) {
            return
        }

        cell!!.replaceFuel(FuelBurnState.createFromStack(stack))
    }
}

// FRAK YOU MINECRAFT!!

class HeatGeneratorMenu(pContainerId: Int, playerInventory: Inventory, handler: ItemStackHandler, private val access: ContainerLevelAccess) : AbstractContainerMenu(Content.HEAT_GENERATOR_MENU.get(), pContainerId) {
    @ServerOnly
    constructor(pBlockEntity: HeatGeneratorBlockEntity, pContainerId: Int, pPlayerInventory: Inventory) : this(
        pContainerId,
        pPlayerInventory,
        pBlockEntity.inventoryHandler,
        ContainerLevelAccess.create(pBlockEntity.level!!, pBlockEntity.blockPos)
    )

    @ClientOnly
    constructor(pContainerId: Int, playerInventory: Inventory) : this(
        pContainerId,
        playerInventory,
        ItemStackHandler(1),
        ContainerLevelAccess.NULL
    )

    init {
        addSlot(
            SlotItemHandlerWithPlacePredicate(handler, HeatGeneratorBlockEntity.FUEL_SLOT, 56, 35) {
                FuelBurnState.canBurn(it)
            }
        )

        ContainerHelper.addPlayerGrid(playerInventory, this::addSlot)
    }

    override fun quickMoveStack(pPlayer: Player, pIndex: Int) = ContainerHelper.quickMove(slots, pPlayer, pIndex)

    override fun stillValid(pPlayer: Player) = stillValid(access, pPlayer, Content.HEAT_GENERATOR_BLOCK.block.get())
}

class HeatGeneratorScreen(menu: HeatGeneratorMenu, playerInventory: Inventory, title: Component) : MyAbstractContainerScreen<HeatGeneratorMenu>(menu, playerInventory, title) {
    override fun renderBg(pGuiGraphics: GuiGraphics, pPartialTick: Float, pMouseX: Int, pMouseY: Int) {
        blitHelper(pGuiGraphics, resource("textures/gui/container/heat_generator.png"))
    }
}

class HeatGeneratorBlock : CellBlock<HeatGeneratorCell>() {
    override fun getCellProvider() = Content.HEAT_GENERATOR_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState): BlockEntity {
        return HeatGeneratorBlockEntity(pPos, pState)
    }

    override fun <T : BlockEntity?> getTicker(
        pLevel: Level,
        pState: BlockState,
        pBlockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T> {
        return BlockEntityTicker(HeatGeneratorBlockEntity::tick)
    }

    @Deprecated("Deprecated in Java")
    override fun use(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult,
    ): InteractionResult {
        return pLevel.constructMenuHelper2<HeatGeneratorBlockEntity>(
            pPos,
            pPlayer,
            Component.literal("Test"),
            ::HeatGeneratorMenu
        )
    }
}

data class ElectricalHeatEngineModel(
    val baseEfficiency: Double,
    val potential: InterpolationFunction<Quantity<Temperature>, Quantity<Potential>>,
    val conductance: Quantity<ThermalConductance>
)

class ElectricalHeatEngineCell(
    ci: CellCreateInfo,
    electricalMap: PoleMap,
    thermalMap: PoleMap,
    b1Def: ThermalMassDefinition,
    b2Def: ThermalMassDefinition,
    val model: ElectricalHeatEngineModel,
    radiantInfo: RadiantBodyEmissionDescription?
) : Cell(ci) {
    constructor(
        ci: CellCreateInfo,
        electricalMap: PoleMap,
        thermalMap: PoleMap,
        def: ThermalMassDefinition,
        model: ElectricalHeatEngineModel,
        radiantInfo: RadiantBodyEmissionDescription?
    ) : this(
        ci,
        electricalMap,
        thermalMap,
        def,
        def,
        model,
        radiantInfo
    )

    @SimObject
    val generator = PolarTermObject(
        this,
        electricalMap,
        PowerVoltageSource()
    )

    @SimObject
    val thermalBipole = ThermalBipoleObject(
        this,
        thermalMap,
        b1Def,
        b2Def
    )

    val cold by thermalBipole::b1
    val hot by thermalBipole::b2

    @Behavior
    val radiantEmitter = if(radiantInfo != null) {
        RadiantEmissionBehavior.create(
            self(),
            thermalBipole.b1 to radiantInfo,
            thermalBipole.b2 to radiantInfo
        )
    }
    else {
        null
    }

    @Replicator
    fun replicator(target: InternalTemperatureConsumer) = InternalTemperatureReplicatorBehavior(
        listOf(thermalBipole.b1, thermalBipole.b2), target
    )

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::preTick)
        subscribers.addPost(this::postTick)
    }

    var efficiency = 0.0

    private fun preTick(dt: Double, phase: SubscriberPhase) {
        val Th: Double
        val Tc: Double

        if(cold.temperature < hot.temperature) {
            Th = !hot.temperature
            Tc = !cold.temperature
        }
        else {
            Th = !cold.temperature
            Tc = !hot.temperature
        }

        efficiency = model.baseEfficiency * ((Th - Tc) / Th)

        val ΔE = hot.energy - cold.energy
        val ΔT = hot.temperature - cold.temperature

        generator.term.potentialMax = !model.potential.evaluate(abs(ΔT))
        generator.term.powerIdeal = efficiency * sign(!ΔT) * min((0.5 * abs(!ΔE) / dt), !model.conductance * !abs(ΔT))
    }

    private fun postTick(dt: Double, phase: SubscriberPhase) {
        val electricalEnergy = generator.term.power * dt

        val electricalDirection = snzi(electricalEnergy)
        val thermalDirection = snzi((!hot.temperature - !cold.temperature))

        if (electricalDirection == thermalDirection) {
            val thermalEnergy = if(efficiency.approxEq(0.0)) {
                0.0
            }
            else {
                electricalEnergy / efficiency
            }

            val wastedEnergy = thermalEnergy * (1.0 - efficiency)

            if (electricalDirection == 1) {
                hot.energy -= electricalEnergy
                hot.energy -= wastedEnergy
                cold.energy += wastedEnergy
            } else {
                cold.energy += electricalEnergy
                cold.energy += wastedEnergy
                hot.energy -= wastedEnergy
            }
        } else {
            if(electricalDirection == 1) {
                hot.energy += electricalEnergy / 2.0
                cold.energy += electricalEnergy / 2.0
            }
            else {
                hot.energy -= electricalEnergy / 2.0
                cold.energy -= electricalEnergy / 2.0
            }
        }
    }
}

class ElectricalHeatEnginePart(ci: PartCreateInfo) : CellPart<ElectricalHeatEngineCell, RadiantBipoleRenderer>(ci, Content.ELECTRICAL_HEAT_ENGINE_CELL.get()), InternalTemperatureConsumer, ComponentDisplay {
    override fun createRenderer() = RadiantBipoleRenderer(
        this,
        PartialModels.PELTIER_BODY,
        PartialModels.PELTIER_LEFT,
        PartialModels.PELTIER_RIGHT,
    )

    @ClientOnly
    override fun registerPackets(builder: PacketHandlerBuilder) {
        builder.withHandler<SyncPacket> {
            renderer.updateRightSideTemperature(Quantity(it.b1Temp, KELVIN))
            renderer.updateLeftSideTemperature(Quantity(it.b2Temp, KELVIN))
        }
    }

    @ServerOnly
    override fun onInternalTemperatureChanges(dirty: List<ThermalMass>) {
        sendBulkPacket(
            SyncPacket(
                !cell.thermalBipole.b1.temperature,
                !cell.thermalBipole.b2.temperature
            )
        )
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.quantity(cell.thermalBipole.b1.temperature)
        builder.quantity(cell.thermalBipole.b2.temperature)
        builder.power(cell.generator.term.power)
        builder.potential(cell.generator.term.potential)
        builder.current(cell.generator.term.current)
        builder.debug("Efficiency: ${cell.efficiency.formattedPercentNormalized()}")
        builder.debug("Potential Max: ${Quantity(cell.generator.term.potentialMax ?: 0.0, VOLT).classify()}")
        builder.debug("Power Ideal: ${Quantity(cell.generator.term.powerIdeal, WATT).classify()}")
    }

    @Serializable
    private data class SyncPacket(val b1Temp: Double, val b2Temp: Double)
}
