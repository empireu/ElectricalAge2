package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.FlatLit
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import kotlinx.serialization.Serializable
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
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
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.AbstractFurnaceBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
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
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.FlwInstanceTypes
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.client.render.foundation.PartialModelHelper
import org.eln2.mc.client.render.foundation.ThermalTint
import org.eln2.mc.client.render.foundation.TransformedLightOverrideInstance
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.blocks.foundation.UprightHorizontalDirectionCellBlock
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.containers.ContainerHelper
import org.eln2.mc.common.containers.MyAbstractContainerScreen
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicate
import org.eln2.mc.common.content.FuelBurnState.Companion.canBurn
import org.eln2.mc.common.content.modules.Eln2HeatGenerators
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.network.serverToClient.BulkPacketHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.sendBulkPacket
import org.eln2.mc.control.PIDController
import org.eln2.mc.data.MonopoleMap
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.resource
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * @param conductivityGasCoal The conductivity between the input gas and the coal. This, most importantly, determines how much heat the coal can absorb from the chemical reaction.
 * @param conductivityGasHull The conductivity between the input gas and the hull.
 * @param conductanceCoalCoal The conductance between each coal slice. This is basically the internal thermal conductance of the coal body.
 * @param conductivityCoalHull The conductance between the coal slices and the hull. This controls how much heat can be pulled away by the hull from the coal body.
 * @param temperatureParameter The temperature-based parameter for the reaction rate. Given a test temperature, the smaller [temperatureParameter] is, the larger the reaction rate.
 * @param rateParameter Independent scale factor for the entire reaction rate.
 * @param gasCaptureFactor Determines how much of the packet can interact with a layer (this parameter is also scaled with the quantity left in the cell). `1` means a perfect interaction (the layer can use up all the oxygen, if the temperature rate allows it).
 * */
data class BurnerDeviceDescription(
    val conductivityGasCoal: Quantity<ThermalConductivity>,
    val conductivityGasHull: Quantity<ThermalConductivity>,
    val conductanceCoalCoal: Quantity<ThermalConductance>,
    val conductivityCoalHull: Quantity<ThermalConductivity>,
    val temperatureParameter: Quantity<Temperature> = Quantity(681.0195, CELSIUS),
    val rateParameter: Double = 0.05,
    val gasCaptureFactor: Double = 0.25
)

class BurnerSimulation(val ambientTemperature: Quantity<Temperature>, val hull: ThermalMass, val options: BurnerDeviceDescription) {
    /**
     * Represents the entire content of gas inside the reaction chamber. Constructed by [tick] when air is injected for reaction.
     * @param airMass The injected amount of air.
     * @param airTemperature The temperature of the injected air.
     * */
    @Suppress("PropertyName")
    class AirPacket(val ambientTemperature: Quantity<Temperature>, airMass: Double, airTemperature: Double) {
        companion object {
            const val N2_FRACTION = 0.764
            const val O2_FRACTION = 0.235
            const val CO2_FRACTION = 0.001

            const val CP_N2 = 1040.0
            const val CP_O2 = 919.0
            const val CP_CO2 = 844.0
        }

        /**
         * The mass of nitrogen remaining in the packet.
         * */
        var N2 = airMass * N2_FRACTION

        /**
         * The mass of oxygen remaining in the packet.
         * */
        var O2 = airMass * O2_FRACTION

        /**
         * The mass of carbon dioxide remaining in the packet.
         * */
        var CO2 = airMass * CO2_FRACTION

        /**
         * Gets the total mass remaining in the packet.
         * */
        val mass: Double get() = N2 + O2 + CO2

        /**
         * Gets the heat capacity of the mixture.
         * */
        val heatCapacity: Double get() = (N2 * CP_N2) + (O2 * CP_O2) + (CO2 * CP_CO2)

        /**
         * The energy of the air inside the packet.
         * */
        var totalEnergy: Double = heatCapacity * airTemperature

        /**
         * Gets the temperature of the mixture. Returns [ambientTemperature] if [heatCapacity] is `0`.
         * */
        val temperature: Double get() {
            val thermalMass = heatCapacity

            if (thermalMass <= 0.0) {
                return !ambientTemperature
            }

            return totalEnergy / thermalMass
        }
    }

    class CoalGrade(
        val energyDensity: Quantity<MassEnergyDensity> = Quantity(24.0, MEGA * JOULE_PER_KILOGRAM),
        val specificHeat: Quantity<SpecificHeatCapacity> = Quantity(1200.0, JOULE_PER_KILOGRAM_KELVIN),
        val characteristicSurfaceArea: Double = 17.6,
        val ignitionThreshold: Quantity<Temperature> = Quantity(126.01, CELSIUS),
        val ignitionWindow: Quantity<Temperature> = Quantity(50.0, CELSIUS),
        val airToCoalRatio: Double = 11.5
    )

    /**
     * One cell in the simulation's domain. Represents a small, homogenous (in temperature and composition) volume of coal.
     * @param initialMass The initial mass added to this slice.
     * @param surfaceAreaFactor Scaling factor for the effective mass of the device.
     * @param energy The internal energy of the slice.
     * */
    class CoalSlice(val grade: CoalGrade, val initialMass: Double, var energy: Double) {
        /**
         * The mass remaining in the slice.
         * */
        var mass = initialMass

        /**
         * The temperature of the coal in the slice. Undefined around [mass]` = 0`
         * */
        var temperature get() = energy / (mass * !grade.specificHeat)
            set(value) {
                energy = value * (mass * !grade.specificHeat)
            }
    }

    var slices: Array<CoalSlice> = emptyArray()

    /**
     * Loads a lump of coal as [slices] individual slices for simulation.
     * */
    fun loadCoal(grade: CoalGrade, mass: Double, sliceCount: Int) {
        slices = Array(sliceCount) {
            val sliceMass = mass / sliceCount

            CoalSlice(
                grade,
                sliceMass,
                sliceMass * !grade.specificHeat * !ambientTemperature
            )
        }
    }

    private fun clipEnergyTransfer(transfer: Double, from: Double, to: Double) =
        if(transfer > 0.0) {
            transfer.coerceAtMost(from)
        }
        else {
            transfer.coerceAtLeast(-to)
        }

    val remainingMass get() = slices.sumOf { it.mass }

    val isDepleted get() = slices.isEmpty() || slices.all { it.mass.approxEq(0.0) }

    /**
     * Calculates the mass flow rate due to the chimney effect.
     * @param chimneyGasTemperature The temperature of the gas inside the device. Usually, you should use [lastChimneyTemperature].
     * @param draftStrength A combined area + friction + other things term.
     * */
    fun calculateNaturalDraftFlowRate(chimneyGasTemperature: Double, draftStrength: Double): Double {
        if (chimneyGasTemperature <= !ambientTemperature + 0.1) {
            return 0.0
        }

        val buoyancyTerm = 1.0 - (!ambientTemperature / chimneyGasTemperature)

        return if (buoyancyTerm > 0.0) {
            draftStrength * sqrt(buoyancyTerm)
        }
        else {
            0.0
        }
    }

    /**
     * Gets the last temperature of the output gas.
     * */
    var lastChimneyTemperature = !ambientTemperature

    fun tick(dt: Double, inputAirMass: Double, inputAirTemperature: Double) : AirPacket {
        val options = options

        val packet = AirPacket(ambientTemperature, inputAirMass, inputAirTemperature)

        var isDepleted = true
        slices.forEach { layer ->
            if(layer.mass.approxEq(0.0)) {
                return@forEach
            }

            val grade = layer.grade

            isDepleted = false

            val ignitionState = ((layer.temperature - !grade.ignitionThreshold) / !grade.ignitionWindow).coerceIn(0.0, 1.0)

            /**
             * Temperature-dependent reaction rate calculation. Not Arrhenius because it's harder to tune and more unstable.
             * */
            val temperatureFactor = ignitionState * (layer.temperature / !options.temperatureParameter).pow(2).coerceAtMost(5.0)

            /**
             * Maximum burnable mass based on the physical construction of the device and its temperature:
             * */
            val maxBurnByBurnerState = options.rateParameter * (layer.grade.characteristicSurfaceArea * layer.mass.pow(2.0 / 3.0)) * temperatureFactor * dt

            /**
             * Maximum burnable mass based on available oxygen:
             * */
            val maxBurnByOxygen = (options.gasCaptureFactor * packet.O2 * (layer.mass / layer.initialMass)) / (grade.airToCoalRatio * AirPacket.O2_FRACTION)

            val carbonMassBurned = min(layer.mass, min(maxBurnByBurnerState, maxBurnByOxygen))

            /**
             * Releases the chemical energy. We actually add this energy to the gas itself, not the coal.
             * This is more stable and realistic; if we add the energy directly to the coal, the reaction is much more violent in testing.
             * */
            if (carbonMassBurned > 0.0) {
                layer.mass -= carbonMassBurned

                val oxygenConsumed = carbonMassBurned * (grade.airToCoalRatio * AirPacket.O2_FRACTION)
                val co2Produced = carbonMassBurned + oxygenConsumed

                packet.O2 -= oxygenConsumed
                packet.CO2 += co2Produced

                val energyReleased = carbonMassBurned * !grade.energyDensity
                packet.totalEnergy += energyReleased
            }

            /**
             * Heat exchange between the gas and the coal:
             * */
            val qGasCoal = clipEnergyTransfer(
                (packet.temperature - layer.temperature) * packet.heatCapacity *! options.conductivityGasCoal * layer.mass.pow(2.0 / 3.0) * dt,
                packet.totalEnergy,
                layer.energy
            )

            packet.totalEnergy -= qGasCoal
            layer.energy += qGasCoal

            /**
             * Heat exchange between the coal and the hull:
             * */
            val qCoalHull = clipEnergyTransfer(
                (layer.temperature - !hull.temperature) * layer.mass * !grade.specificHeat * !options.conductivityCoalHull * dt,
                layer.energy,
                !hull.energy
            )

            layer.energy -= qCoalHull
            hull.energy += Quantity(qCoalHull)
        }

        if(!isDepleted) {
            /**
             * Heat exchange between the coal slices:
             * */
            for (i in 1 until slices.size) {
                val s0 = slices[i - 1]
                val s1 = slices[i]

                if(s0.mass.approxEq(0.0) || s1.mass.approxEq(0.0)) {
                    continue
                }

                val q = clipEnergyTransfer(
                    (s0.temperature - s1.temperature) * !options.conductanceCoalCoal * dt,
                    s0.energy,
                    s1.energy
                )

                s0.energy -= q
                s1.energy += q
            }
        }

        /**
         * Heat exchange between the gas and the hull:
         * */
        val qGasHull = clipEnergyTransfer(
            (packet.temperature - !hull.temperature) * packet.heatCapacity * !options.conductivityGasHull * dt * 10.0,
            packet.totalEnergy,
            !hull.energy
        )
        packet.totalEnergy -= qGasHull
        hull.energy += Quantity(qGasHull)

        lastChimneyTemperature = if(isDepleted) {
            !ambientTemperature
        } else {
            packet.temperature
        }

        return packet
    }
}

/**
 * Options for the [BurnerCell].
 * @param deviceDescription The options for the [BurnerSimulation].
 * @param hullDef The thermal body that actually is a part of the thermal simulation. The coal simulation will exchange temperature with this body.
 * @param leakageParameters Leakage connection between the hull thermal body and the environment.
 * @param substeps The burner simulation does an undamped explicit step for the exponentially-decaying heat equations and whatnot. We substep the simulation to make sure it's good.
 * */
data class BurnerCellOptions(
    val deviceDescription: BurnerDeviceDescription,
    val hullDef: ThermalMassDefinition,
    val leakageParameters: ConnectionParameters,
    val substeps: Int = 16
)

object CoalGradeRegistry {
    val REGISTRY = MutableMapPairBiMap<ResourceLocation, BurnerSimulation.CoalGrade>()

    fun register(id: ResourceLocation, grade: BurnerSimulation.CoalGrade) : BurnerSimulation.CoalGrade {
        REGISTRY.add(id, grade)
        return grade
    }

    val DEFAULT = register(resource("default_coal"), BurnerSimulation.CoalGrade())

    fun getId(grade: BurnerSimulation.CoalGrade) = REGISTRY.backward[grade] ?: error("Coal grade is not registered!")
    fun getById(id: ResourceLocation) = REGISTRY.forward[id] ?: error("Could not get coal grade \"$id\"")
}

/**
 * Base for a cell that burns fuel to get energy.
 * Only handles integrating the burning simulation with the cells.
 * */
abstract class BurnerCell(ci: CellCreateInfo, val burnerCellOptions: BurnerCellOptions) : Cell(ci) {
    @SimObject
    val hull = ThermalWireObject(
        this,
        burnerCellOptions.hullDef(),
        burnerCellOptions.leakageParameters
    )

    val simulation = BurnerSimulation(
        ci.environment.ambientTemperature,
        hull.thermalBody,
        burnerCellOptions.deviceDescription
    )

    @Replicator
    fun hullTemperatureReplicator(target: InternalTemperatureConsumer) =
        InternalTemperatureReplicatorBehavior(listOf(hull.thermalBody), target)

    @Replicator
    fun externalTemperatureReplicator(target: ExternalTemperatureConsumer) =
        ExternalTemperatureReplicatorBehavior(this, target)

    /**
     * Represents an ignition time interval.
     * @param ticksLeft The number of simulation ticks left for applying this ignition.
     * @param targetTemperature The minimum temperature to enforce for the bottom slice.
     * @param airInjectionRate The extra air injection rate (mass flow rate).
     * */
    private class Ignition(var ticksLeft: Int, var targetTemperature: Quantity<Temperature>, var airInjectionRate: Double)

    /**
     * Ignition to apply:
     * */
    private var ignition: Ignition? = null
    private val ignitionUpdate = AtomicUpdate<Ignition>()

    /**
     * Ignites the device.
     * */
    fun ignite() {
        ignitionUpdate.setLatest(
            Ignition(
                200,
                Quantity(300.0, CELSIUS),
                !Quantity(2.5, GRAM)
            )
        )
    }

    /**
     * Inserts coal into the device.
     * If called when the simulation is not depleted, this will overwrite the currently stored slices.
     * */
    fun replaceCoal(grade: BurnerSimulation.CoalGrade, amount: Quantity<Mass>) {
        // Atomic operation wrt the simulation method.
        // However, it is not atomic wrt to the cell's tick method.
        simulation.loadCoal(grade, !amount, 16)
        setChanged()
    }

    /**
     * The air inflow rate for the tick.
     * This is only the base value; if an [ignition] is present, more air may be inserted into the simulation.
     * */
    var injectionRate: Double = 0.0
        protected set

    /**
     * The temperature of the flue gas.
     * Ambient if the simulation is not running.
     * */
    var lastOutputGasTemperature = simulation.ambientTemperature
        private set

    /**
     * The oxygen output rate, in kilograms per second.
     * This is basically the oxygen that did not manage to react with the coal.
     * */
    var lastOutputOxygenMassFlowRate = 0.0
        private set

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::tick)
    }

    /**
     * Called by [tick] so the specific implementation can calculate the [injectionRate].
     * */
    protected abstract fun calculateFlow(dt: Double)

    protected fun tick(dt: Double, phase: SubscriberPhase) {
        ignitionUpdate.consume {
            ignition = it
        }

        val ignition = ignition

        /**
         * Ticks down the ignition:
         * */
        if(ignition != null) {
            ignition.ticksLeft--

            if(ignition.ticksLeft <= 0) {
                this.ignition = null
            }

            setChanged()
        }

        calculateFlow(dt)

        /**
         * The air injection rate:
         * */
        val massFlowRate = injectionRate + (ignition?.airInjectionRate ?: 0.0)

        /**
         * The quantity of air to inject each substep:
         * */
        val substepInjection = (massFlowRate * dt) / burnerCellOptions.substeps

        val substepDt = dt / burnerCellOptions.substeps

        for (substep in 0 until burnerCellOptions.substeps) {
            if(simulation.isDepleted) {
                if(simulation.slices.isNotEmpty()) {
                    simulation.slices = emptyArray()
                    setChanged()
                }

                break
            }

            /**
             * Enforces the minimum temperature of the bottom slice when igniting:
             * */
            if(ignition != null && simulation.slices[0].temperature < !ignition.targetTemperature) {
                simulation.slices[0].temperature = !ignition.targetTemperature
            }

            val output = simulation.tick(
                substepDt,
                substepInjection,
                !simulation.ambientTemperature
            )

            lastOutputGasTemperature = Quantity(output.temperature, KELVIN)
            lastOutputOxygenMassFlowRate = output.O2 / substepDt

            setChanged() // Just sets a flag, it's fine
        }
    }

    override fun saveCellData(): CompoundTag {
        val tag = CompoundTag()

        tag.putDouble(LAST_CHIMNEY_TEMPERATURE, simulation.lastChimneyTemperature)

        val ignition = ignition

        tag.putBoolean(HAS_IGNITION, ignition != null)

        if(ignition != null) {
            tag.putInt(IGNITION_TICKS_LEFT, ignition.ticksLeft)
            tag.putQuantity(IGNITION_TARGET_TEMPERATURE, ignition.targetTemperature)
            tag.putDouble(IGNITION_AIR_INJECTION_RATE, ignition.airInjectionRate)
        }

        val list = ListTag()
        simulation.slices.forEach { slice ->
            val sliceTag = CompoundTag()

            sliceTag.putResourceLocation(COAL_GRADE, CoalGradeRegistry.getId(slice.grade))
            sliceTag.putDouble(INITIAL_MASS, slice.initialMass)
            sliceTag.putDouble(ENERGY, slice.energy)
            sliceTag.putDouble(MASS, slice.mass)

            list.add(sliceTag)
        }

        tag.put(SLICE_LIST, list)

        return tag
    }

    override fun loadCellData(tag: CompoundTag) {
        simulation.lastChimneyTemperature = tag.getDouble(LAST_CHIMNEY_TEMPERATURE)

        if(tag.getBoolean(HAS_IGNITION)) {
            val ticksLeft = tag.getInt(IGNITION_TICKS_LEFT)
            val targetTemperature = tag.getQuantity<Temperature>(IGNITION_TARGET_TEMPERATURE)
            val airInjectionRate = tag.getDouble(IGNITION_AIR_INJECTION_RATE)

            ignition = Ignition(ticksLeft, targetTemperature, airInjectionRate)
        }

        val list = tag.getListTag(SLICE_LIST)
        val slices = ArrayList<BurnerSimulation.CoalSlice>(list.size)

        list.forEachCompound { sliceTag ->
            val coalGrade = CoalGradeRegistry.getById(sliceTag.getResourceLocation(COAL_GRADE))
            val initialMass = sliceTag.getDouble(INITIAL_MASS)
            val energy = sliceTag.getDouble(ENERGY)
            val mass = sliceTag.getDouble(MASS)

            val slice = BurnerSimulation.CoalSlice(
                coalGrade,
                initialMass,
                energy
            )

            slice.mass = mass

            slices.add(slice)
        }

        simulation.slices = slices.toTypedArray()
    }

    companion object {
        private const val LAST_CHIMNEY_TEMPERATURE = "lastChimneyTemperature"
        private const val HAS_IGNITION = "hasIgnition"
        private const val IGNITION_TICKS_LEFT = "ignitionTicksLeft"
        private const val IGNITION_TARGET_TEMPERATURE = "ignitionTargetTemperature"
        private const val IGNITION_AIR_INJECTION_RATE = "ignitionInjectionRate"

        private const val SLICE_LIST = "sliceList"
        private const val COAL_GRADE = "coalGrade"
        private const val INITIAL_MASS = "initialMass"
        private const val ENERGY = "energy"
        private const val MASS = "mass"

    }
}

/**
 * Burner fed by the chimney effect.
 * Has only one output side.
 * */
class PrimitiveBurnerCell(
    ci: CellCreateInfo,
    burnerCellOptions: BurnerCellOptions,
    override val thermalMap: MonopoleMap,
    val maxDraftStrength: Double
) : BurnerCell(ci, burnerCellOptions), SidedThermalMonoMapped<PrimitiveBurnerCell> {
    override val thermalSize: ThermalSize
        get() = ThermalSize.Standard

    /**
     * The control parameter, in the range `[0, 1]`.
     * */
    var controlParameter = 0.0

    /**
     * Sets the injection rate based on the control parameter
     * */
    override fun calculateFlow(dt: Double) {
        injectionRate = if(simulation.isDepleted) {
            0.0
        } else {
            simulation.calculateNaturalDraftFlowRate(
                max(
                    max(
                        simulation.lastChimneyTemperature,
                        !hull.thermalBody.temperature
                    ),
                    if(simulation.isDepleted) 0.0 else simulation.slices.maxOf { it.temperature }
                ),
                controlParameter * maxDraftStrength
            )
        }
    }
}

class PrimitiveBurnerBlock : UprightHorizontalDirectionCellBlock<PrimitiveBurnerCell>() {
    @Deprecated("Deprecated in Java")
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean = true

    override fun getCellProvider() = Eln2HeatGenerators.PRIMITIVE_BURNER_CELL.get()

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = PrimitiveBurnerBlockEntity(pPos, pState)

    @Deprecated("Deprecated in Java")
    override fun use(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult,
    ): InteractionResult {
        val blockEntity = pLevel.getBlockEntity(pPos) as? PrimitiveBurnerBlockEntity
            ?: return InteractionResult.FAIL

        if(pHand != InteractionHand.MAIN_HAND) {
            return InteractionResult.FAIL
        }

        return blockEntity.interact(pPlayer)
    }
}

class PrimitiveBurnerBlockEntity(pos: BlockPos, state: BlockState) :
    CellBlockEntity<PrimitiveBurnerCell>(pos, state, Eln2HeatGenerators.PRIMITIVE_BURNER_BLOCK_ENTITY.get()),
    BulkPacketHandlerBlockEntity,
    InternalTemperatureConsumer,
    ExternalTemperatureConsumer,
    ScrewdriverScrollable,
    ComponentDisplay
{
    @ServerOnly
    fun interact(player: Player) : InteractionResult {
        val stack = player.mainHandItem

        if(stack.count <= 0) {
            return InteractionResult.FAIL
        }

        when(stack.item) {
            Items.FLINT_AND_STEEL -> {
                if(player.level().isClientSide) {
                    return InteractionResult.SUCCESS
                }

                cell.ignite()

                return InteractionResult.CONSUME_PARTIAL
            }

            Items.COAL -> {
                if(player.level().isClientSide) {
                    return InteractionResult.SUCCESS
                }

                if(!cell.simulation.isDepleted) {
                    return InteractionResult.FAIL
                }

                cell.replaceCoal(
                    CoalGradeRegistry.DEFAULT,
                    Quantity(10.0, KILOGRAM)
                )

                return InteractionResult.CONSUME
            }

            else -> {
                return InteractionResult.FAIL
            }
        }
    }

    @ServerOnly
    override fun scrollScrewdriver(player: ServerPlayer, delta: Double): Boolean {
        val parameter = cell.controlParameter
        val newParameter = (parameter + delta / 10.0).coerceIn(0.0, 1.0)

        if(newParameter != parameter) {
            cell.controlParameter = newParameter
            cell.setChanged()
            sendBulkPacket(ControlValvePacket(newParameter))
            return true
        }

        return false
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Input rate: ${(cell.injectionRate * 1000.0).rounded()} g/s" }
        builder.debugInIDE { "Output gas temperature: ${cell.lastOutputGasTemperature.classify()}"}
        builder.debugInIDE { "Output flow rate O2: ${(cell.lastOutputOxygenMassFlowRate * 1000.0).rounded()} g/s" }
        builder.quantity(cell.hull.thermalBody.temperature)
        builder.quantity(Quantity(cell.simulation.slices.sumOf { it.mass }, KILOGRAM))
    }

    //#region Client-Side Rendering State

    override val clientSidePacketHandlerLazy = createClientSideHandler()

    class RenderState {
        var hullTemperature = 0.0
        var externalTemperature: Double? = null
        var controlParameter = 0.0
    }

    var renderState: RenderState? = null
        private set

    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if(pLevel.isClientSide) {
            renderState = RenderState()
        }
    }

    @ClientOnly
    override fun setupPacketsOnClient(handler: ClientSidePacketHandlerBuilder) {
        fun modifyState(action: RenderState.() -> Unit) {
            val renderState = renderState
                ?: return

            action(renderState)
        }

        handler.withHandler<HullTemperaturePacket> {
            modifyState {
                hullTemperature = it.hullTemperature
            }
        }

        handler.withHandler<ExternalTemperaturePacket> {
            modifyState {
                externalTemperature = it.externalTemperature
            }
        }

        handler.withHandler<ControlValvePacket> {
            modifyState {
                controlParameter = it.controlParameter
            }
        }
    }

    //#endregion

    //#region Sync

    @ServerOnly
    override fun onInternalTemperatureChanges(dirty: List<ThermalMass>) {
        sendBulkPacket(HullTemperaturePacket(!dirty[0].temperature))
    }

    @ServerOnly
    override fun onExternalTemperatureChanges(removed: HashSet<ThermalObject<*>>, dirty: HashMap<ThermalObject<*>, Double>, all: HashMap<ThermalObject<*>, Double>, ) {
        sendBulkPacket(
            if (removed.isNotEmpty()) {
                ExternalTemperaturePacket(null)
            }
            else {
                ExternalTemperaturePacket(dirty.values.first())
            }
        )

    }

    // onSyncSuggested
    @ServerOnly
    override fun getUpdateTag(): CompoundTag {
        sendBulkPacket(HullTemperaturePacket(!cell.hull.thermalBody.temperature))

        sendBulkPacket(
            ExternalTemperaturePacket(
                if (cell.hull.connections.isNotEmpty()) {
                    !cell.hull.getContactTemperature(cell.hull.connections[0].cell)
                }
                else {
                    null
                }
            )
        )

        sendBulkPacket(ControlValvePacket(cell.controlParameter))

        return super.getUpdateTag()
    }

    @Serializable data class HullTemperaturePacket(val hullTemperature: Double)
    @Serializable data class ExternalTemperaturePacket(val externalTemperature: Double?)
    @Serializable data class ControlValvePacket(val controlParameter: Double)

    //#endregion
}

class PrimitiveBurnerBlockEntityVisual(ctx: VisualizationContext, blockEntity: PrimitiveBurnerBlockEntity, partialTick: Float) : AbstractBlockEntityVisual<PrimitiveBurnerBlockEntity>(ctx, blockEntity, partialTick), SimpleDynamicVisual {
    companion object {
        val TINT = ThermalTint.DEFAULT_LIGHT_OVERRIDE
        const val SLIDING_RANGE = 3.5 / 16.0
    }

    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.PRIMITIVE_COAL_BURNER_BODY))
        .createInstance()
        .transformFacingBlock(visualPos, blockEntity)

    val hull: TransformedLightOverrideInstance = visualizationContext.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, Models.partial(FlwModels.PRIMITIVE_COAL_BURNER_HULL))
        .createInstance()
        .transformFacingBlock(visualPos, blockEntity)

    val conduit: TransformedLightOverrideInstance = visualizationContext.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, Models.partial(FlwModels.PRIMITIVE_COAL_BURNER_CONDUIT))
        .createInstance()
        .transformFacingBlock(visualPos, blockEntity)

    val door: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.PRIMITIVE_COAL_BURNER_DOOR))
        .createInstance()

    var hullTemperature = 0.0
    var externalTemperature: Double? = null
    var controlParameter = 0.0

    private fun applyHullTemperature() {
        val color = TINT.evaluate(Quantity(hullTemperature, KELVIN))
        hull.color(color.r, color.g, color.b)
        hull.lightOverride = color.a / 255.0f
        hull.setChanged()
    }

    private fun getCoreColor() : MyColor {
        val lightLevel = LightTexture.block(
            LevelRenderer.getLightColor(
                level,
                blockEntity.blockPos
            )
        )

        return TINT.evaluateRGBL(
            Quantity(hullTemperature), lightLevel.toDouble()
        )
    }

    private fun applyExternalTemperature() {
        val remoteLightLevel = LightTexture.block(
            LevelRenderer.getLightColor(
                level,
                blockEntity.blockPos - blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING)
            )
        )

        val coreColor = getCoreColor()
        val selfColor = TINT.evaluateRGBL(
            Quantity(
                externalTemperature ?: hullTemperature,
                KELVIN
            ),
            remoteLightLevel.toDouble()
        )

        val color = MyColor.lerp(coreColor, selfColor, 0.5f)

        conduit.light(0,
            LevelRenderer.getLightColor(
                level,
                blockEntity.blockPos - blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING)
            )
        )

        conduit.color(color.r, color.g, color.b)
        conduit.lightOverride = color.a / 255.0f + 0.0301f
        conduit.setChanged()
    }

    private fun applyControlParameter() {
        door.setIdentityTransform()
        door.transformFacingBlock(visualPos, blockEntity)
        door.translateX(-(SLIDING_RANGE * controlParameter).toFloat())
        door.setChanged()
    }

    init {
        applyHullTemperature()
        applyExternalTemperature()
        applyControlParameter()
    }

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val renderState = blockEntity.renderState
            ?: return

        val targetHullTemperature = renderState.hullTemperature
        val targetExternalTemperature = renderState.externalTemperature
        val targetControlParameter = renderState.controlParameter

        if(targetHullTemperature != hullTemperature) {
            hullTemperature = targetHullTemperature
            applyHullTemperature()

            if(targetExternalTemperature == null && externalTemperature == null) {
                applyExternalTemperature()
            }
        }

        if(targetExternalTemperature != externalTemperature) {
            externalTemperature = targetExternalTemperature
            applyExternalTemperature()
        }

        if(targetControlParameter != controlParameter) {
            controlParameter = targetControlParameter
            applyControlParameter()
        }
    }

    override fun updateLight(p0: Float) {
        relight(body, hull, door)
        applyExternalTemperature()
    }

    override fun collectCrumblingInstances(p0: Consumer<Instance?>) {
        p0.accept(body)
        p0.accept(hull)
    }

    override fun _delete() {
        body.delete()
        hull.delete()
        conduit.delete()
        door.delete()
    }
}

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
        fuelAmount - !energy / !energyDensity
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

class HeatGeneratorCell(ci: CellCreateInfo, thermalDef: ThermalMassDefinition, leakageParameters: ConnectionParameters) : Cell(ci),
    ThermalContactInfo, SidedThermalFLBR<HeatGeneratorCell> {
    companion object {
        private const val BURNER_BEHAVIOR = "burner"
    }

    override val thermalSize: ThermalSize
        get() = ThermalSize.Any

    @SimObject
    val thermalWire = ThermalWireObject(this, thermalDef(), leakageParameters)

    @Behavior
    val burner = FuelBurnerBehavior(this, thermalWire.thermalBody)

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

class HeatGeneratorBlockEntity(pos: BlockPos, state: BlockState) : CellBlockEntity<HeatGeneratorCell>(pos, state, Eln2HeatGenerators.HEAT_GENERATOR_BLOCK_ENTITY.get()),
    ComponentDisplay {
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

class HeatGeneratorMenu(pContainerId: Int, playerInventory: Inventory, handler: ItemStackHandler, private val access: ContainerLevelAccess) : AbstractContainerMenu(
    Eln2HeatGenerators.HEAT_GENERATOR_MENU.get(), pContainerId) {
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

    override fun stillValid(pPlayer: Player) = stillValid(access, pPlayer, Eln2HeatGenerators.HEAT_GENERATOR_BLOCK.block.get())
}

class HeatGeneratorScreen(menu: HeatGeneratorMenu, playerInventory: Inventory, title: Component) : MyAbstractContainerScreen<HeatGeneratorMenu>(menu, playerInventory, title) {
    override fun renderBg(pGuiGraphics: GuiGraphics, pPartialTick: Float, pMouseX: Int, pMouseY: Int) {
        blitHelper(pGuiGraphics, resource("textures/gui/container/heat_generator.png"))
    }
}

class HeatGeneratorBlock : UprightHorizontalDirectionCellBlock<HeatGeneratorCell>() {
    init {
        registerDefaultState(defaultBlockState().setValue(AbstractFurnaceBlock.LIT, false))
    }

    override fun createBlockStateDefinition(pBuilder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(pBuilder)
        pBuilder.add(AbstractFurnaceBlock.LIT)
    }

    override fun getCellProvider() = Eln2HeatGenerators.HEAT_GENERATOR_CELL.get()

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
