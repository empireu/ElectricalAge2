@file:Suppress("UNUSED_VARIABLE", "LocalVariableName", "NonAsciiCharacters")

package org.eln2.mc.common.content

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
import org.ageseries.libage.mathematics.nz
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.mathematics.snzi
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.STANDARD_TEMPERATURE
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.mna.LARGE_RESISTANCE
import org.eln2.mc.*
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
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask
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

@DimensionClassifier("kg×m²") interface Inertia
val KILOGRAM_METER_SQUARED = standardScale<Inertia>()

@DimensionClassifier("N×m×s") interface ViscousFriction
val NEWTON_METER_SECOND = standardScale<ViscousFriction>()

@DimensionClassifier("rad/s") interface AngularVelocity
val RADIAN_PER_SECOND = standardScale<AngularVelocity>()

@DimensionClassifier("N×m") interface Torque
val NEWTON_METER = standardScale<Torque>()

@ScaleClassifier("rps")
val REVOLUTION_PER_SECOND = RADIAN_PER_SECOND sourceAmplify 1.0 / 0.1591549430919
// Why is it private in libage? :
internal infix fun <U> SourceQuantityScale<U>.sourceAmplify(amplify: Double) = SourceQuantityScale<U>(dimensionType, Scale(scale.factor / amplify, scale.base))

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
data class ThermalElectricalGeneratorModel(
    val referenceAngularVelocity: Quantity<AngularVelocity>,
    val nominalPotential: Quantity<Potential>,
    val etaFactorEngine: Double,
    val heatExchangerConductance: Quantity<ThermalConductance>, val leakConductance: Quantity<ThermalConductance>,
    val inertia: Quantity<Inertia>, val friction: Quantity<ViscousFriction>,
    val maxElectricalTorque: Quantity<Torque>, val maxDevicePower: Quantity<Power>,
    val etaElectrical: Double, val coreConstLoss: Quantity<Power>, val coreDependentLoss: Double
)

class ThermalElectricalGenerator(val coldSide: ThermalMass, val hotSide: ThermalMass, val model: ThermalElectricalGeneratorModel) {
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
     * Gets the efficiency of the engine ([ThermalElectricalGeneratorModel.etaFactorEngine] × Carnot).
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
    electricalMap: PoleMap,
    thermalMap: PoleMap,
    b1Def: ThermalMassDefinition,
    b2Def: ThermalMassDefinition,
    b1Leakage: ConnectionParameters,
    b2Leakage: ConnectionParameters,
    generatorModel: ThermalElectricalGeneratorModel,
    sourceResistance: Double,
    radiantInfoB1: RadiantBodyEmissionDescription?,
    radiantInfoB2: RadiantBodyEmissionDescription?
) : Cell(ci) {
    @SimObject
    val source = PowerVoltageSourceObject(this, electricalMap).also {
        it.resistor.resistance = sourceResistance
    }

    @SimObject
    val thermalBipole = ThermalBipoleObject(
        this,
        thermalMap,
        b1Def(), b2Def(),
        b1Leakage, b2Leakage
    )

    val cold by thermalBipole::b1
    val hot by thermalBipole::b2

    val generator = ThermalElectricalGenerator(cold, hot,  generatorModel)

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
    fun replicator(target: InternalTemperatureConsumer) = InternalTemperatureReplicatorBehavior(
        listOf(thermalBipole.b1, thermalBipole.b2), target
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
    }

    override fun saveCellData() = CompoundTag().also { generator.saveToTag(it) }

    override fun loadCellData(tag: CompoundTag) = generator.loadFromTag(tag)
}

class ElectricalHeatEnginePart(ci: PartCreateInfo) : CellPart<ElectricalHeatEngineCell>(ci, Content.ELECTRICAL_HEAT_ENGINE_CELL.get()), InternalTemperatureConsumer, ComponentDisplay, RadiantBipoleGameObject {
    @ClientOnly
    override var renderTemperature1: Quantity<Temperature> = STANDARD_TEMPERATURE
        private set

    @ClientOnly
    override var renderTemperature2: Quantity<Temperature> = STANDARD_TEMPERATURE
        private set

    @ClientOnly
    override fun registerPackets(builder: PacketHandlerBuilder) {
        builder.withHandler<SyncPacket> {
            renderTemperature1 = Quantity(it.b1Temp, KELVIN)
            renderTemperature2 = Quantity(it.b2Temp, KELVIN)
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
        builder.debug("Cold: ${cell.cold.temperature.classifyAuxiliary(::CELSIUS)}")
        builder.debug("Hot: ${cell.hot.temperature.classifyAuxiliary(::CELSIUS)}")
        builder.debug("SRC power: ${Quantity(cell.source.generator.power, WATT).classify()}")
        builder.debug("SRC potential: ${Quantity(cell.source.generator.potential, VOLT).classify()}")
        builder.debug("SRC current: ${Quantity(cell.source.generator.current, AMPERE).classify()}")
        builder.debug("GEN angular velocity: ${(cell.generator.angularVelocity.classifyAuxiliary(::REVOLUTION_PER_SECOND))}")
        builder.debug("GEN eta engine: ${(cell.generator.etaEngine * 100.0).rounded(2)}%")
        builder.debug("GEN power available: ${cell.generator.availablePower.classify()}")
        builder.debug("GEN potential OC: ${cell.generator.potentialOpenCircuit.classify()}")
        builder.debug("GEN engine torque: ${cell.generator.engineTorque.classify()}")
    }

    @Serializable
    private data class SyncPacket(val b1Temp: Double, val b2Temp: Double)
}
