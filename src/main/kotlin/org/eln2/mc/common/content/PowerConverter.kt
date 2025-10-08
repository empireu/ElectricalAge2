package org.eln2.mc.common.content

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.mna.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.LARGE_RESISTANCE
import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.eln2.mc.*
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.specs.foundation.CellSpec
import org.eln2.mc.common.specs.foundation.SpecCreateInfo
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import kotlin.math.min

/**
 * @param powerRating The maximum power **output** of the device.
 * The maximum power input is `[powerRating] / [eta] * [inputPowerRateFactor]`, which is used to fill the energy buffer.
 * @param eta The conversion efficiency of the device. `Eout,electrical ≈ Ein,electrical * [eta]`
 * @param energyK Energy buffer capacity multiplier. `Capacity = ([powerRating] / [eta]) * dt * energyK`.
 * This value should always be greater than 1.
 * @param potentialRating The max potential across the device's inputs.
 * This value is used to calculate the initial resistance of the sink resistor, such that the input power is bounded regardless of the source circuit's potential, as long as its open-circuit voltage is, at most, [potentialRating].
 * @param outputResistance Impedance in series with the output circuit. Necessary for stability (currently, the power sources are prone to blowing up unless they have some resistance in series).
 * @param inputThreshold The potential threshold to allow the device to start harnessing energy from the source circuit.
 * @param inputPowerRateFactor Factor for the input power (actual `input power = [powerRating] / [eta] * [inputPowerRateFactor]`).
 * If the device is at 100% load, the power across the buffer would be ~0W, so this factor ensures the buffer is getting filled even at 100% load.
 * @param sourceNetworkDrainFactor The max power drained from the network will be the `max estimated power (from the numerical approximation) * [sourceNetworkDrainFactor]`.
 * */
@Suppress("SpellCheckingInspection")
data class DcToDcConverterModel(
    val powerRating: Quantity<Power>,
    val eta: Double,
    val energyK: Double,
    val potentialRating: Quantity<Potential>,
    val outputResistance: Quantity<Resistance>,
    val inputThreshold: Quantity<Potential>,
    val inputPowerRateFactor: Double = 1.1,
    val sourceNetworkDrainFactor: Double = 0.9
) {
    val bufferCapacity = Quantity((!powerRating / eta) * CellGraph.DT * energyK, JOULE)
    val initialResistance = Quantity((!potentialRating * !potentialRating) / (!powerRating / eta), OHM)
    val maxInputPower = powerRating / eta * inputPowerRateFactor
}

fun interface RejectedEnergyAcceptor {
    fun accept(energy: Quantity<Energy>)
}

/**
 * DC-DC converter implemented as a power sink + power source with a control loop.
 * This can generate 2 sub-solvers.
 * */
abstract class DcToDcConverterObject<C : Cell>(cell: C, val model: DcToDcConverterModel, val rejectedEnergyAcceptor: RejectedEnergyAcceptor? = null) : ElectricalObject<C>(cell), PersistentObject {
    var energyBuffer = 0.0
        private set

    // Also saved to NBT, for a start as close as possible to the last state
    private val theveninResistor = TheveninEstimatingResistor().also { it.resistance = !model.initialResistance }
    private val source = MyPowerVoltageSource()
    private val outputResistor = Resistor().also { it.resistance = !model.outputResistance }

    // P.S. the nbt saving might screw these for the first tick, meh
    val inputResistorDisplay = theveninResistor.display()
    val sourceDisplay = source.display()

    var setpointPotential = Quantity(0.0, VOLT)

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(theveninResistor)
        circuit.add(source)
        circuit.add(outputResistor)
    }

    protected fun offerInputNegative() = theveninResistor.offerNegative()
    protected fun offerInputPositive() = theveninResistor.offerPositive()
    protected fun offerOutputNegative() = outputResistor.offerNegative()
    protected fun offerOutputPositive() = source.offerPositive()

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)
        map.join(source.offerNegative(), outputResistor.offerPositive())
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::tickPre)
        subscribers.addPost(this::tickPost)
    }

    /**
     * Updates the [theveninResistor]'s resistance so the buffer gets filled to max capacity.
     * */
    private fun updateInputSink(dt: Double) {
        val missingEnergy = (!model.bufferCapacity - energyBuffer).coerceAtLeast(0.0)

        // Max power the device can accept:
        val maxInputPower = min((missingEnergy / dt), !model.maxInputPower)

        val sourcePotential = if (theveninResistor.openCircuitPotentialEstimate > !model.inputThreshold){
            theveninResistor.openCircuitPotentialEstimate
        }
        else {
            0.0
        }

        val resistanceEps = 1e-6 // Can't realistically be that low

        // Prevents choking the upstream supplier with small resistances.
        // Max power the network can deliver:
        val maxNetworkPower = if(theveninResistor.theveninResistanceEstimate > resistanceEps) {
            (sourcePotential * sourcePotential) / theveninResistor.theveninResistanceEstimate * model.sourceNetworkDrainFactor
        }
        else {
            0.0
        }

        val desiredInputPower = min(maxInputPower, maxNetworkPower)

        val powerEps = 0.1
        val sinkResistance = if(desiredInputPower > powerEps && sourcePotential != 0.0) {
            (sourcePotential * sourcePotential) / desiredInputPower
        }
        else {
            !model.initialResistance
        }

        val previousResistance = theveninResistor.resistance
        val desiredResistance =  sinkResistance.coerceIn(1e-6, LARGE_RESISTANCE)

        theveninResistor.resistance = desiredResistance

        cell.setChangedIf(!previousResistance.approxEq(desiredResistance))
    }

    /**
     * Updates the [source]'s max power, based on the [energyBuffer].
     * */
    private fun updateOutputRate(dt: Double) {
        val previousPotentialMax = source.potentialMax ?: 0.0
        val previousPowerIdeal = source.powerIdeal

        if(energyBuffer <= 0.0) {
            source.potentialMax = 0.0
            source.powerIdeal = 0.0
        }
        else {
            val maxInstantaneousPower = (energyBuffer / dt) * model.eta

            source.powerIdeal = min(maxInstantaneousPower, !model.powerRating)
            source.potentialMax = !setpointPotential
        }

        cell.setChangedIf(!previousPotentialMax.approxEq(source.potentialMax ?: 0.0))
        cell.setChangedIf(!previousPowerIdeal.approxEq(source.powerIdeal))
    }

    private fun tickPre(dt: Double, phase: SubscriberPhase) {
        updateInputSink(dt)
        updateOutputRate(dt)
    }

    /**
     * Collects the energy dissipated by the [theveninResistor] and returns any excess energy (caused, most probably, by numerical inaccuracies).
     * */
    private fun collectInputEnergy(dt: Double) : Double {
        var rejectedEnergy = 0.0

        val inputEnergy = theveninResistor.power.coerceAtLeast(0.0) * dt

        // Reverse polarity:
        if(theveninResistor.potential < 0.0) {
            rejectedEnergy += inputEnergy
        }
        else {
            energyBuffer += inputEnergy

            if(energyBuffer > !model.bufferCapacity) {
                rejectedEnergy += (energyBuffer - !model.bufferCapacity)
                energyBuffer = !model.bufferCapacity
                cell.setChanged()
            }
        }

        cell.setChangedIf(!inputEnergy.approxEq(0.0))

        return rejectedEnergy
    }

    private fun drainOutputtedEnergy(dt: Double) : Double {
        val deliveredEnergy = source.power * dt

        // Inputting power!
        if(deliveredEnergy < 0.0) {
            return -deliveredEnergy // waste it away
        }

        val consumedFromBuffer = deliveredEnergy / model.eta

        energyBuffer -= consumedFromBuffer

        if(energyBuffer < 0.0) {
            if(energyBuffer < -1e-6) {
                LOG.debug("Outputted more energy ($consumedFromBuffer) than possible which left buffer at $energyBuffer")
            }

            energyBuffer = 0.0
            cell.setChanged()
        }

        cell.setChangedIf(!consumedFromBuffer.approxEq(0.0))

        return (consumedFromBuffer - deliveredEnergy).coerceAtLeast(0.0)
    }

    private fun tickPost(dt: Double, phase: SubscriberPhase) {
        var rejectedEnergy = 0.0

        rejectedEnergy += collectInputEnergy(dt)
        rejectedEnergy += drainOutputtedEnergy(dt)

        rejectedEnergyAcceptor?.accept(Quantity(rejectedEnergy, JOULE))
    }

    override fun saveObjectNbt(): CompoundTag {
        val tag = CompoundTag()
        tag.putDouble(ENERGY_BUFFER, energyBuffer)
        tag.putDouble(RESISTANCE, theveninResistor.resistance)
        tag.putDouble(THEVENIN_RESISTANCE_ESTIMATE, theveninResistor.theveninResistanceEstimate)
        tag.putDouble(OPEN_CIRCUIT_POTENTIAL_ESTIMATE, theveninResistor.openCircuitPotentialEstimate)
        tag.putDouble(SOURCE_POWER, source.powerIdeal)
        tag.putDouble(SETPOINT, !setpointPotential)
        return tag
    }

    override fun loadObjectNbt(tag: CompoundTag) {
        energyBuffer = tag.getDouble(ENERGY_BUFFER)
        theveninResistor.resistance = tag.getDouble(RESISTANCE)
        theveninResistor.theveninResistanceEstimate = tag.getDouble(THEVENIN_RESISTANCE_ESTIMATE)
        theveninResistor.openCircuitPotentialEstimate = tag.getDouble(OPEN_CIRCUIT_POTENTIAL_ESTIMATE)
        source.powerIdeal = tag.getDouble(SOURCE_POWER)
        setpointPotential = Quantity(tag.getDouble(SETPOINT))
    }

    companion object {
        private const val ENERGY_BUFFER = "energyBuffer"
        private const val RESISTANCE = "sinkResistance"
        private const val THEVENIN_RESISTANCE_ESTIMATE = "RthEstimate"
        private const val OPEN_CIRCUIT_POTENTIAL_ESTIMATE = "ocPotentialEstimate"
        private const val SOURCE_POWER = "sourcePower"
        private const val SETPOINT = "setpoint"
    }
}

class TerminalDcToDcConverterObject<C : Cell>(
    cell: C,
    model: DcToDcConverterModel,
    val inputNegative: Int,
    val inputPositive: Int,
    val outputNegative: Int,
    val outputPositive: Int,
    rejectedEnergyAcceptor: RejectedEnergyAcceptor? = null
) : DcToDcConverterObject<C>(cell, model, rejectedEnergyAcceptor) {
    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo) = when(m0.terminal) {
        inputNegative -> offerInputNegative()
        inputPositive -> offerInputPositive()
        outputNegative -> offerOutputNegative()
        outputPositive -> offerOutputPositive()
        else -> null
    }
}

class TerminalDcToDcConverterCell(
    ci: CellCreateInfo,
    thermalDef: ThermalMassDefinition,
    leakage: ConnectionParameters,
    model: DcToDcConverterModel,
    inputNegative: Int = 0,
    inputPositive: Int = 1,
    outputNegative: Int = 2,
    outputPositive: Int = 3
) : Cell(ci) {
    override val isExclusivelyGridConnected: Boolean
        get() = true

    @SimObject
    val thermalWire = ThermalWireObject(this, thermalDef(), leakage)

    @SimObject
    val converter = TerminalDcToDcConverterObject(this, model, inputNegative, inputPositive, outputNegative, outputPositive) { rejectedEnergy ->
        thermalWire.thermalBody.energy += rejectedEnergy
    }.also { it.setpointPotential = Quantity(24.0, VOLT) }

    @Node
    val grid = GridNode(this)
}

class DcToDcConverterSpec(ci: SpecCreateInfo) :
    CellSpec<TerminalDcToDcConverterCell>(ci, Content.TERMINAL_DC_TO_DC_CONVERTER_CELL_800W.get()),
    ScrewdriverScrollable,
    ComponentDisplay
{
    val inputNegative = defineCellBoxTerminalBB(
        3.1, 0.275, 6.425,
        0.4, 0.475, 0.575,
        highlightColor = MyColor.BLUE,
    )

    val inputPositive = defineCellBoxTerminalBB(
        3.1, 0.275, 9.0,
        0.4, 0.475, 0.575,
        highlightColor = MyColor.RED
    )

    val outputNegative = defineCellBoxTerminalBB(
        12.5, 0.275, 9.0,
        0.4, 0.475, 0.575,
        highlightColor = MyColor.BLUE,
    )

    val outputPositive = defineCellBoxTerminalBB(
        12.5, 0.275, 6.425,
        0.4, 0.475, 0.575,
        highlightColor = MyColor.RED,
    )

    override fun scrollScrewdriver(player: ServerPlayer, delta: Double) : Boolean {
        if(!hasCell) {
            return false
        }

        val increment = delta / 10.0
        val newPotential = (!cell.converter.setpointPotential + increment).coerceIn(0.0, !cell.converter.model.potentialRating)

        cell.converter.setpointPotential = Quantity(newPotential)
        cell.setChanged()

        return true
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Buffer: ${cell.converter.energyBuffer.classifyAs(JOULE)}" }
        builder.debugInIDE { "In RTh: ${cell.converter.inputResistorDisplay.theveninResistance.classify()}" }
        builder.debugInIDE { "In OCV: ${cell.converter.inputResistorDisplay.openCircuitPotentialEstimate.classify()}" }
        builder.debugInIDE { "In sink res: ${cell.converter.inputResistorDisplay.resistance.classify()}" }
        builder.debugInIDE { "Out powerIdeal: ${cell.converter.sourceDisplay.powerIdeal.classify()}" }
        builder.debugInIDE { "Out potentialMax: ${cell.converter.sourceDisplay.potentialMax.classify()}" }

        builder.quantity(cell.thermalWire.thermalBodyDisplay.temperature)
        builder.quantityInput(cell.converter.inputResistorDisplay.power)
        builder.quantityOutput(cell.converter.sourceDisplay.power)
        builder.quantityOutput(cell.converter.sourceDisplay.potential)
        builder.quantitySetpoint(cell.converter.setpointPotential)
    }
}
