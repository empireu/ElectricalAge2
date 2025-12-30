@file:Suppress("UNUSED_VARIABLE", "LocalVariableName", "NonAsciiCharacters")

package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import kotlinx.serialization.Serializable
import net.minecraft.nbt.CompoundTag
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.RotationUpdateProfile2d
import org.ageseries.libage.mathematics.computeRotationUpdateAccelerationProfileWithAccelerationEstimate
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.nz
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.STANDARD_TEMPERATURE
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.ElectricalSimulation
import org.ageseries.libage.utils.Stopwatch
import org.eln2.mc.*
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.modules.Eln2Thermal
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.AbstractPartVisual
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.PoleMap
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import kotlin.math.min

// FRAK YOU MINECRAFT!!

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
    var targetResistanceSuggestion = Quantity(ElectricalSimulation.MAX_RESISTANCE, OHM)
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
            Quantity(ElectricalSimulation.MAX_RESISTANCE, OHM)
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
    override val thermalMap: PoleMap,
    coldDef: ThermalMassDefinition,
    hotDef: ThermalMassDefinition,
    coldLeakage: ConnectionParameters,
    hotLeakage: ConnectionParameters,
    generatorModel: ThermalElectricGeneratorModel,
    sourceResistance: Double,
    radiantInfoB1: RadiantBodyEmissionDescription?,
    radiantInfoB2: RadiantBodyEmissionDescription?,
    override val electricalSize: ElectricalSize,
    override val thermalSize: ThermalSize
) : Cell(ci), SidedElectricalMapped<ElectricalHeatEngineCell>, SidedThermalMapped<ElectricalHeatEngineCell> {
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
    fun temperatureReplicator(target: InternalMultiThermalBodyTemperatureConsumer) = InternalMultiThermalBodyTemperatureReplicatorBehavior(
        listOf(thermalBipole.b1, thermalBipole.b2), target
    )

    @Replicator
    fun kineticReplicator(target: InternalKineticStateConsumer) = InternalKineticReplicatorBehavior(
        this::kineticState,
        target,
        this,
        null
    )

    override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        subscribers.addPre(this::preTick)
        subscribers.addPost(this::postTick)
    }

    private fun preTick(dt: Double, phase: SimulationPhase) {
        generator.preTick(dt)
        source.generator.maxPotential = !generator.potentialOpenCircuit
        source.generator.targetPower = !generator.availablePower
    }

    private fun postTick(dt: Double, phase: SimulationPhase) {
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
    CellPart<ElectricalHeatEngineCell>(ci, Eln2Thermal.ELECTRICAL_HEAT_ENGINE_CELL.get()),
    InternalMultiThermalBodyTemperatureConsumer,
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
    override fun setupPacketsOnClient(builder: ClientSidePacketHandlerBuilder) {
        val renderState = renderStateImpl!!

        builder.withHandler<TemperatureSyncPacket> {
            renderState.b1Temperature = Quantity(it.b1Temp, KELVIN)
            renderState.b2Temperature = Quantity(it.b2Temp, KELVIN)
        }

        builder.withHandler<RotationSyncPacket> {
            renderState.angle = it.angle
            renderState.angularVelocity = it.angularVelocity
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
    override fun onKineticStateChanged(state: RotatingKineticState) {
        sendBulkPacket(
            RotationSyncPacket(
                state.angle,
                state.angularVelocity
            )
        )
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Gen availablePower: ${cell.generator.availablePower.classify()} "}
        builder.debugInIDE { "Gen potentialOpenCircuit: ${cell.generator.potentialOpenCircuit.classify()}" }
        builder.coldTemperature(cell.cold.temperature)
        builder.hotTemperature(cell.hot.temperature)
        builder.efficiency(cell.generator.etaEngine)
        builder.quantity(cell.generator.angularVelocity)
        builder.quantity(cell.generator.engineTorque)
        builder.quantityOutput(cell.source.generator.readouts.potential)
        builder.quantityOutput(cell.source.generator.readouts.current)
        builder.quantityOutput(cell.source.generator.readouts.power)
    }

    @Serializable
    private data class TemperatureSyncPacket(
        val b1Temp: Double,
        val b2Temp: Double
    )

    @Serializable
    private data class RotationSyncPacket(val angle: Double, val angularVelocity: Double)
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
        .instancer(InstanceTypes.TRANSFORMED, PartialModelHelper.applyMaterial(FlwModels.SMALL_THERMAL_ELECTRIC_GENERATOR_BODY, Materials.CUTOUT_BLOCK))
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
