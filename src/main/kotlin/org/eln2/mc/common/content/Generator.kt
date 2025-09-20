@file:Suppress("UNUSED_VARIABLE", "LocalVariableName", "NonAsciiCharacters")

package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import kotlinx.serialization.Serializable
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractFurnaceBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.common.ForgeHooks
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.items.ItemStackHandler
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.nz
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.STANDARD_TEMPERATURE
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.mna.LARGE_RESISTANCE
import org.ageseries.libage.utils.Stopwatch
import org.eln2.mc.*
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.blocks.foundation.CellBlock
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.containers.ContainerHelper
import org.eln2.mc.common.containers.MyAbstractContainerScreen
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicate
import org.eln2.mc.common.content.FuelBurnState.Companion.canBurn
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.network.serverToClient.PacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.AbstractPartVisual
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.control.PIDController
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask
import kotlin.math.abs
import kotlin.math.min

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

    val isBurning get() = thermalPower > 10.0

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
        builder.quantityOutput(Quantity(thermalPower, WATT), ComponentDisplayList.Domain.Thermal)
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

    override fun getContactTemperature(other: Cell) = thermalWire.thermalBody.temperature
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
        val isHot = cell.burner.isBurning

        if (isHot != blockState.getValue(AbstractFurnaceBlock.LIT)) {
            level!!.setBlock(
                blockPos,
                blockState.setValue(AbstractFurnaceBlock.LIT, isHot),
                Block.UPDATE_ALL
            )
        }

        if (!cell.needsFuel) {
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
    init {
        registerDefaultState(defaultBlockState().setValue(AbstractFurnaceBlock.LIT, false))
    }

    override fun createBlockStateDefinition(pBuilder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(pBuilder)
        pBuilder.add(AbstractFurnaceBlock.LIT)
    }

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

    override fun animateTick(pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) {
        if(pState.getValue(AbstractFurnaceBlock.LIT)) {
            val d0 = pPos.x.toDouble() + 0.5
            val d1 = pPos.y.toDouble()
            val d2 = pPos.z.toDouble() + 0.5
            if (pRandom.nextDouble() < 0.5) {
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

            repeat(4) {
                val d4 = pRandom.nextDouble() * 0.6 - 0.3
                val d5 = if (direction.axis === Direction.Axis.X) direction.stepX.toDouble() * 0.52 else d4
                val d6 = pRandom.nextDouble() * 6.0 / 16.0
                val d7 = if (direction.axis === Direction.Axis.Z) direction.stepZ.toDouble() * 0.52 else d4
                pLevel.addParticle(ParticleTypes.SMOKE, d0 + d5, d1 + d6, d2 + d7, 0.0, 0.0, 0.0)
                pLevel.addParticle(ParticleTypes.FLAME, d0 + d5, d1 + d6, d2 + d7, 0.0, 0.0, 0.0)
            }
        }
    }
}

/**
 * @param referenceAngularVelocity Reference **ω** for scaling the potential and torque.
 * @param nominalPotential Open-circuit potential at reference **ΔT** at steady state.
 * @param etaFactorEngine The efficiency factor of the engine, multiplied by the Carnot efficiency.
 * @param heatExchangerConductance The conductance of the heat exchanger which limits how much heat can move from the hot to the cold side, so it puts a limit on the power output.
 * @param leakConductance Leakage conductance from hot to cold or cold to hot. Applied regardless of their temperatures.
 * @param inertia Rotational inertia of the whole shaft assembly.
 * @param friction The viscous friction of the whole shaft assembly.
 * @param maxElectricalTorque Maximum torque the alternator can apply to generate power.
 * @param maxDevicePower A hard limit on the max power conversion of the alternator.
 * @param etaElectrical Efficiency of the alternator.
 * @param coreConstLoss Constant power loss in the core of the generator.
 * @param coreDependentLoss Power loss in the core of the generator, dependent on **ω** (W / (rad/s)).
 * */
data class ThermalElectricGeneratorModel(
    val referenceAngularVelocity: Quantity<AngularVelocity>,
    val nominalPotential: Quantity<Potential>,
    val etaFactorEngine: Double,
    val heatExchangerConductance: Quantity<ThermalConductance>, val leakConductance: Quantity<ThermalConductance>,
    val inertia: Quantity<Inertia>, val friction: Quantity<ViscousFriction>,
    val maxElectricalTorque: Quantity<Torque>, val maxDevicePower: Quantity<Power>,
    val etaElectrical: Double, val coreConstLoss: Quantity<Power>, val coreDependentLoss: Double
)

class ThermalElectricGenerator(val coldSide: ThermalMass, val hotSide: ThermalMass, val model: ThermalElectricGeneratorModel) {
    /**
     * Gets the current angular velocity of the shaft.
     * */
    var angularVelocity = Quantity(0.0, RADIAN_PER_SECOND)
        private set

    /**
     * Gets the temperature difference calculated in [preTick].
     * */
    var deltaT = Quantity(0.0, KELVIN)
        private set

    /**
     * Gets the efficiency of the engine ([ThermalElectricGeneratorModel.etaFactorEngine] × Carnot).
     * */
    var etaEngine = 0.0
        private set

    /**
     * Gets the torque provided by the engine.
     * */
    var engineTorque = Quantity(0.0, NEWTON_METER)
        private set

    /**
     * Gets the available mechanical/electrical power.
     * */
    var availablePower = Quantity(0.0, WATT)
        private set

    /**
     * Gets the open-circuit voltage of the electrical component.
     * */
    var potentialOpenCircuit = Quantity(0.0, VOLT)
        private set

    /**
     * Gets the expected resistance of the generator (if modeled as a Thevenin source).
     * If used, the maximum of the [targetResistanceSuggestion] and the internal resistance of the source should be used.
     * [LARGE_RESISTANCE] is set if the engine is not spinning.
     * */
    var targetResistanceSuggestion = Quantity(LARGE_RESISTANCE, OHM)
        private set

    /**
     * Gets the kinetic energy of the shaft assembly, calculated in [preTick].
     * */
    var kineticEnergy = Quantity(0.0, JOULE)
        private set

    /**
     * Max thermal power available for conversion.
     * */
    private var heatConversionAvailable = 0.0

    /**
     * The hard cap for available power.
     * */
    private var powerHardCap = 0.0

    fun preTick(dt: Double) {
        val coldT = coldSide.temperature
        val hotT = hotSide.temperature
        deltaT = (hotT - coldT)
        etaEngine = (1.0 - !coldT / !hotT) * model.etaFactorEngine

        // Heat available for conversion (larger than or equal to zero).
        heatConversionAvailable = !model.heatExchangerConductance * (!deltaT).coerceAtLeast(0.0)
        // Max thermal power that can be converted to mechanical power.
        val maxThermalPower = etaEngine * heatConversionAvailable

        // Engine torque proportional to ΔT and thermal conductance, scaled by reference ω.
        val kTorque = (etaEngine * !model.heatExchangerConductance) / !model.referenceAngularVelocity
        engineTorque = Quantity(kTorque * (!deltaT).coerceAtLeast(0.0), NEWTON_METER)

        val omegaNz = (!angularVelocity).nz()

        // Kinetic energy of the shaft assembly.
        kineticEnergy = Quantity((0.5 * !model.inertia * (!angularVelocity * !angularVelocity)), JOULE)
        // Upper bound on the power that can be provided this tick if all the kinetic energy was converted.
        val inertiaPower = !kineticEnergy / dt // Upper bound for converting the kinetic energy stored

        // Limit on power due to generator torque and device maximum power.
        powerHardCap = min(
            maxThermalPower + inertiaPower,
            min(!model.maxElectricalTorque * omegaNz, !model.maxDevicePower)
        ).coerceAtLeast(0.0)

        availablePower = Quantity(powerHardCap)

        // Scale potential with ΔT and angular velocity:
        val velocityScale = (angularVelocity / model.referenceAngularVelocity)
        potentialOpenCircuit = Quantity((!model.nominalPotential * velocityScale).coerceAtLeast(0.0))

        // Compute target electrical resistance for load (based on power and voltage).
        targetResistanceSuggestion = if(availablePower > 0.0 && potentialOpenCircuit > 0.0) {
            Quantity((!potentialOpenCircuit * !potentialOpenCircuit) / (4.0 * !availablePower), OHM)
        } else{
            Quantity(LARGE_RESISTANCE, OHM)
        }
    }

    fun postTick(circuitPower: Quantity<Power>, dt: Double) {
        val electricalPower = (!circuitPower).coerceIn(0.0, powerHardCap)

        val omegaNz = (!angularVelocity).nz()

        // Torque required by electrical load:
        val requiredElectricalTorque = electricalPower / omegaNz
        val electricalTorque = min(requiredElectricalTorque, !model.maxElectricalTorque)

        // Actual electrical power delivered:
        val actualElectricalPower = electricalTorque * omegaNz

        val frictionTorque = !model.friction * !angularVelocity
        val netTorque = !engineTorque - electricalTorque - frictionTorque
        val newAngularVelocity = (!angularVelocity + (netTorque / !model.inertia) * dt).coerceAtLeast(0.0)

        val kineticEnergyOld = 0.5 * !model.inertia * (!angularVelocity * !angularVelocity)
        val kineticEnergyNew = 0.5 * !model.inertia * (newAngularVelocity * newAngularVelocity)
        val deltaKineticEnergy = kineticEnergyNew - kineticEnergyOld

        // Power into/out of rotational kinetic energy.
        val kineticPower = deltaKineticEnergy / dt

        // Average friction power.
        val frictionPower = frictionTorque * (0.5 * (!angularVelocity + newAngularVelocity))

        // Generator core losses:
        val coreLossPower = !model.coreConstLoss + model.coreDependentLoss * omegaNz

        // Mechanical power required to generate electrical output including losses:
        val mechanicalToElectricalPower = actualElectricalPower / model.etaElectrical
        val generatorLossPower = (mechanicalToElectricalPower - actualElectricalPower).coerceAtLeast(0.0) + coreLossPower.coerceAtLeast(0.0)

        // Total mechanical power extracted from shaft:
        val mechanicalPower = actualElectricalPower + kineticPower + frictionPower + generatorLossPower

        // Thermal energy actually converted:
        val actualThermalEnergy = if(etaEngine > 0.0) {
            (mechanicalPower / etaEngine).coerceIn(0.0, heatConversionAvailable)
        }
        else {
            0.0
        }

        val rejectedThermalPower = actualThermalEnergy - mechanicalPower
        val leakThermalPower = !model.leakConductance * (!deltaT)

        hotSide.energy -= (actualThermalEnergy + leakThermalPower) * dt
        coldSide.energy += (rejectedThermalPower + leakThermalPower + generatorLossPower + frictionPower) * dt
        angularVelocity = Quantity(newAngularVelocity, RADIAN_PER_SECOND)
    }

    fun saveToTag(tag: CompoundTag) {
        tag.putDouble("angularVelocity", !angularVelocity)
    }

    fun loadFromTag(tag: CompoundTag) {
        angularVelocity = Quantity(tag.getDouble("angularVelocity"))
    }
}

class ElectricalHeatEngineCell(
    ci: CellCreateInfo,
    override val electricalMap: PoleMap,
    thermalMap: PoleMap,
    coldDef: ThermalMassDefinition,
    hotDef: ThermalMassDefinition,
    coldLeakage: ConnectionParameters,
    hotLeakage: ConnectionParameters,
    generatorModel: ThermalElectricGeneratorModel,
    sourceResistance: Double,
    radiantInfoB1: RadiantBodyEmissionDescription?,
    radiantInfoB2: RadiantBodyEmissionDescription?,
    override val electricalSize: ElectricalWireSize
) : Cell(ci), SidedWireSizeInfoMapped<ElectricalHeatEngineCell> {
    @SimObject
    val source = PowerVoltageSourceObject(this, electricalMap).also {
        it.resistor.resistance = sourceResistance
    }

    @SimObject
    val thermalBipole = ThermalBipoleObject(
        this,
        thermalMap,
        coldDef(), hotDef(),
        coldLeakage, hotLeakage
    )

    val cold by thermalBipole::b1
    val hot by thermalBipole::b2

    val coldDisplay = displayer.display(cold)
    val hotDisplay = displayer.display(hot)

    val generator = ThermalElectricGenerator(cold, hot,  generatorModel)

    var shaftRotation = Rotation2d.identity
        private set

    val kineticState get() = RotatingKineticState(shaftRotation.ln(), !generator.angularVelocity)

    @Behavior
    val radiantEmitter = if(radiantInfoB1 != null || radiantInfoB2 != null) {
        RadiantEmissionBehavior.create(
            self(),
            if(radiantInfoB1 != null) thermalBipole.b1 to radiantInfoB1 else null,
            if(radiantInfoB2 != null) thermalBipole.b2 to radiantInfoB2 else null,
        )
    }
    else {
        null
    }

    @Replicator
    fun temperatureReplicator(target: InternalTemperatureConsumer) = InternalTemperatureReplicatorBehavior(
        listOf(thermalBipole.b1, thermalBipole.b2), target
    )

    @Replicator
    fun kineticReplicator(target: InternalKineticStateConsumer) = InternalKineticReplicatorBehavior(
        this::kineticState,
        target
    )

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::preTick)
        subscribers.addPost(this::postTick)
    }

    private fun preTick(dt: Double, phase: SubscriberPhase) {
        generator.preTick(dt)
        source.generator.potentialMax = !generator.potentialOpenCircuit
        source.generator.powerIdeal = !generator.availablePower
    }

    private fun postTick(dt: Double, phase: SubscriberPhase) {
        val power = source.generator.power

        if(power < 0.0) {
            // Alternatively, we could spin the shaft. Or add a diode.
            // For testing, let's just sink it.
            cold.energy -= power * dt
            generator.postTick(Quantity(0.0), dt)
        }
        else {
            generator.postTick(Quantity(source.generator.power), dt)
        }

        shaftRotation += !generator.angularVelocity * dt
    }

    override fun saveCellData() = CompoundTag().also { tag ->
        generator.saveToTag(tag)
        tag.putDouble(ROTATION, shaftRotation.ln())
    }

    override fun loadCellData(tag: CompoundTag) = run {
        generator.loadFromTag(tag)
        shaftRotation = Rotation2d.exp(tag.getDouble(ROTATION))
    }

    companion object {
        private const val ROTATION = "rotation"
    }
}

class ElectricalHeatEnginePart(ci: PartCreateInfo) :
    CellPart<ElectricalHeatEngineCell>(ci, Content.ELECTRICAL_HEAT_ENGINE_CELL.get()),
    InternalTemperatureConsumer,
    InternalKineticStateConsumer,
    ComponentDisplay
{
    @ClientOnly
    interface RenderState {
        val b1Temperature: Quantity<Temperature>
        val b2Temperature: Quantity<Temperature>
        val angle: Double
        val angularVelocity: Double
        val angularAccelerationEstimate: Double
        val kinematicVersion: Int
    }

    @ClientOnly
    private class RenderStateImpl : RenderState {
        override var b1Temperature: Quantity<Temperature> = STANDARD_TEMPERATURE
        override var b2Temperature: Quantity<Temperature> = STANDARD_TEMPERATURE
        override var angle = 0.0
        override var angularVelocity = 0.0
        override var angularAccelerationEstimate = 0.0
        override var kinematicVersion = 0
    }

    @ClientOnly
    private var renderStateImpl: RenderStateImpl? = if(ci.placement.level.isClientSide) {
        RenderStateImpl()
    } else {
        null
    }

    @ClientOnly
    val renderState: RenderState get() = renderStateImpl!!

    @ClientOnly
    override fun registerPackets(builder: PacketHandlerBuilder) {
        val renderState = renderStateImpl!!

        builder.withHandler<TemperatureSyncPacket> {
            renderState.b1Temperature = Quantity(it.b1Temp, KELVIN)
            renderState.b2Temperature = Quantity(it.b2Temp, KELVIN)
        }

        builder.withHandler<RotationSyncPacket> {
            renderState.angle = it.angle
            renderState.angularVelocity = it.angularVelocity
            renderState.angularAccelerationEstimate = it.angularAccelerationEstimate
            renderState.kinematicVersion++
        }
    }

    @ServerOnly
    override fun onInternalTemperatureChanges(dirty: List<ThermalMass>) {
        sendBulkPacket(
            TemperatureSyncPacket(
                !cell.thermalBipole.b1.temperature,
                !cell.thermalBipole.b2.temperature
            )
        )
    }

    @ServerOnly
    override fun onKineticStateChanged(state: RotatingKineticState, angularAccelerationEstimate: Double) {
        sendBulkPacket(
            RotationSyncPacket(
                state.angle,
                state.angularVelocity,
                angularAccelerationEstimate
            )
        )
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Gen availablePower: ${cell.generator.availablePower.classify()} "}
        builder.debugInIDE { "Gen potentialOpenCircuit: ${cell.generator.potentialOpenCircuit.classify()}" }
        builder.coldTemperature(cell.coldDisplay.temperature)
        builder.hotTemperature(cell.hotDisplay.temperature)
        builder.efficiency(cell.generator.etaEngine)
        builder.quantity(cell.generator.angularVelocity)
        builder.quantity(cell.generator.engineTorque)
        builder.quantityOutput(cell.source.generatorDisplay.potential)
        builder.quantityOutput(cell.source.generatorDisplay.current)
        builder.quantityOutput(cell.source.generatorDisplay.power)
    }

    @Serializable
    private data class TemperatureSyncPacket(
        val b1Temp: Double,
        val b2Temp: Double
    )

    @Serializable
    private data class RotationSyncPacket(
        val angle: Double,
        val angularVelocity: Double,
        val angularAccelerationEstimate: Double
    )
}

class RotationUpdateProfile2d(val p0: Rotation2d, val v0: Double, val a1: Double, val a2: Double, val duration: Double) {
    var currentTime = 0.0
    val timeRemaining get() = (duration - currentTime).coerceIn(0.0, duration)

    var sampleP = p0
        private set

    var sampleV = v0
        private set

    fun sampleTrajectory() : Double {
        val x = currentTime.coerceIn(0.0, duration)
        val t = duration / 2.0

        return if (x <= t) {
            sampleP = p0 + (v0 * x + 0.5 * a1 * x * x)
            sampleV = v0 + a1 * x
            a1
        }
        else {
            val p1 = p0 + (v0 * t + 0.5 * a1 * t * t)
            val v1 = v0 + a1 * t
            val y = x - t

            sampleP = p1 + (v1 * y + 0.5 * a2 * y * y)
            sampleV = v1 + a2 * y
            a2
        }
    }
}

@Suppress("LocalVariableName")
fun computeRotationUpdateAccelerationProfile(targetPos: Rotation2d, targetVel: Double, sourcePos: Rotation2d, sourceVel: Double, T: Double) : RotationUpdateProfile2d {
    val dp = targetPos - sourcePos
    val dv = targetVel - sourceVel

    val t = T / 2.0
    val t2 = t * t

    val a1 = (dp + targetVel * T) / t2 - (2.0 * sourceVel) / t - dv / T
    val a2 = dv / t - a1

    return RotationUpdateProfile2d(sourcePos, sourceVel, a1, a2, T)
}

fun computeRotationUpdateAccelerationProfileWithAccelerationEstimate(
    accelerationEstimate: Double,
    targetPos: Rotation2d, targetVel: Double,
    sourcePos: Rotation2d, sourceVel: Double,
    maxTransitionTime: Double = 0.25
) : RotationUpdateProfile2d {

    val dv = abs(targetVel - sourceVel)
    val accelEstimate = abs(accelerationEstimate).coerceAtLeast(dv / maxTransitionTime)
    val duration = dv / accelEstimate

    return computeRotationUpdateAccelerationProfile(
        targetPos, targetVel,
        sourcePos, sourceVel,
        duration
    )
}

class ElectricalHeatEnginePartVisual(
    visualizationContext: MultipartVisualizationContext,
    part: ElectricalHeatEnginePart
) : AbstractPartVisual<ElectricalHeatEnginePart>(visualizationContext, part), SimpleDynamicVisual {
    companion object {
        private val tint = ThermalTint.DEFAULT_LIGHT_OVERRIDE
        private val flywheelsCenter = FlwModels.getModelCenter(FlwModels.SMALL_THERMAL_ELECTRIC_GENERATOR_FLYWHEELS)
    }

    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, SpecialModels.partial(FlwModels.SMALL_THERMAL_ELECTRIC_GENERATOR_BODY, Materials.CUTOUT_BLOCK))
        .createInstance()
        .also {
            it.partTransformation(visualizationContext.parent, part)
            it.translate(0.5, 0.0, 0.5)
        }

    val coldSide: TransformedLightOverrideInstance = visualizationContext.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, Models.partial(FlwModels.SMALL_THERMAL_ELECTRIC_GENERATOR_COLD_SIDE))
        .createInstance()
        .also {
            it.partTransformation(visualizationContext.parent, part)
            it.translate(0.5, 0.0, 0.5)
        }

    val hotSide: TransformedLightOverrideInstance = visualizationContext.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, Models.partial(FlwModels.SMALL_THERMAL_ELECTRIC_GENERATOR_HOT_SIDE))
        .createInstance()
        .also {
            it.partTransformation(visualizationContext.parent, part)
            it.translate(0.5, 0.0, 0.5)
        }

    val flywheels: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.SMALL_THERMAL_ELECTRIC_GENERATOR_FLYWHEELS))
        .createInstance()

    var temperatureCold = Quantity(-1.0, KELVIN)
    var temperatureHot = Quantity(-1.0, KELVIN)

    var kinematicVersion = 0
    var flywheelRotation = Rotation2d.identity
    var flywheelVelocity = 0.0
    var interpolationState: RotationUpdateProfile2d? = null
    val frameTimer = Stopwatch()

    private fun poseFlywheels() {
        val y = flywheelsCenter.y
        val z = flywheelsCenter.z

        flywheels.setIdentityTransform()
            .partTransformation(visualizationContext.parent, part)
            .translate(0.5, 0.0, 0.5)
            .translate(0.0, y, z)
            .rotateX(flywheelRotation.ln().toFloat())
            .translate(0.0, -y, -z)
            .handle()
            .setChanged()
    }

    init {
        poseFlywheels()
    }

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val renderState = part.renderState

        val targetColdTemp = renderState.b1Temperature
        if(temperatureCold != targetColdTemp) {
            temperatureCold = targetColdTemp
            coldSide.colorWithOverride(tint, targetColdTemp).handle().setChanged()
        }

        val targetHotTemp = renderState.b2Temperature
        if(temperatureHot != targetHotTemp) {
            temperatureHot = targetHotTemp
            hotSide.colorWithOverride(tint, targetHotTemp).handle().setChanged()
        }

        val targetKinematicVersion = renderState.kinematicVersion
        if(kinematicVersion != targetKinematicVersion) {
            kinematicVersion = targetKinematicVersion

            interpolationState = computeRotationUpdateAccelerationProfileWithAccelerationEstimate(
                renderState.angularAccelerationEstimate,
                Rotation2d.exp(renderState.angle), renderState.angularVelocity,
                flywheelRotation, flywheelVelocity
            )
        }

        val dt = !frameTimer.sample()

        if(interpolationState == null) {
            flywheelRotation += flywheelVelocity * dt
        }
        else {
            val state = interpolationState!!
            state.currentTime += dt
            state.sampleTrajectory()
            flywheelRotation = state.sampleP
            flywheelVelocity = state.sampleV

            if(state.timeRemaining == 0.0) {
                interpolationState = null
            }
        }

        poseFlywheels()
    }

    override fun updateLight(p0: Float) {
        visualizationContext.parent.relightInstances(
            body,
            coldSide,
            hotSide,
            flywheels
        )
    }

    override fun _delete() {
        body.delete()
        coldSide.delete()
        hotSide.delete()
        flywheels.delete()
    }
}
