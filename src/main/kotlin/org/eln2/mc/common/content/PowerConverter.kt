package org.eln2.mc.common.content

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.Capacitor
import org.ageseries.libage.sim.electrical.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.PowerConsumer
import org.ageseries.libage.sim.electrical.PowerSource
import org.ageseries.libage.sim.electrical.Resistor
import org.eln2.mc.*
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.content.modules.Eln2PowerDevices
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.specs.foundation.CellSpec
import org.eln2.mc.common.specs.foundation.SpecCreateInfo
import org.eln2.mc.extensions.loadNbt
import org.eln2.mc.extensions.saveNbt
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import kotlin.math.min

/**
 * @param powerRating The maximum power **output** of the device.
 * @param eta The conversion efficiency of the device. `Eout,electrical ≈ Ein,electrical * [eta]`
 * @param energyK Energy buffer capacity multiplier. `Capacity = ([powerRating] / [eta]) * dt * energyK`.
 * This value should always be greater than 1.
 * @param potentialRating The max potential across the device's inputs.
 * This value is used to calculate the initial resistance of the sink resistor, such that the input power is bounded regardless of the source circuit's potential, as long as its open-circuit voltage is, at most, [potentialRating].
 * @param inputSeriesResistance Impedance in series with the input circuit.
 * @param inputSmoothingCapacitance Capacitance in parallel with the input circuit.
 * @param outputSeriesResistance Impedance in series with the output circuit.
 *
 * */
@Suppress("SpellCheckingInspection")
data class DcToDcConverterModel(
    val powerRating: Quantity<Power>,
    val eta: Double,
    val energyK: Double,
    val potentialRating: Quantity<Potential>,
    val inputSeriesResistance: Quantity<Resistance>,
    val inputSmoothingCapacitance: Quantity<Capacitance>,
    val inputEquivalentResistance: Quantity<Resistance>,
    val outputSeriesResistance: Quantity<Resistance>,
) {
    val bufferCapacity = Quantity((!powerRating / eta) * CellGraph.DT * energyK, JOULE)
}

fun interface RejectedEnergyAcceptor {
    fun accept(energy: Quantity<Energy>)
}

/**
 * DC-DC converter implemented as a power sink + power source.
 * This can generate 2 sub-solvers.
 * */
abstract class DcToDcConverterObject<C : Cell>(cell: C, val model: DcToDcConverterModel, val rejectedEnergyAcceptor: RejectedEnergyAcceptor? = null) : ElectricalObject<C>(cell), PersistentObject {
    /**
     * The internal energy in the device.
     * This value bounds the maximum output power and the maximum input power.
     * */
    var energyBuffer = 0.0
        private set

    /**
     * Changed externally. This is the target open-circuit potential.
     * */
    var setpointPotential = Quantity(0.0, VOLT)

    val inputSeriesResistor = Resistor()
    val inputParallelCapacitor = Capacitor()
    val inputConsumer = PowerConsumer()

    val outputSource = PowerSource()
    val outputSeriesResistor = Resistor()
    // Bypass diode might also be necessary!

    init {
        inputSeriesResistor.resistance = !model.inputSeriesResistance
        inputParallelCapacitor.capacitance = !model.inputSmoothingCapacitance
        inputConsumer.minEquivalentResistance = !model.inputEquivalentResistance

        outputSource.maxPotential = !model.potentialRating

        outputSeriesResistor.resistance = !model.outputSeriesResistance
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(
            inputSeriesResistor, inputParallelCapacitor, inputConsumer,
            outputSource, outputSeriesResistor
        )
    }

    protected fun offerInputNegative() = inputParallelCapacitor.negative
    protected fun offerInputPositive() = inputSeriesResistor.positive
    protected fun offerOutputNegative() = outputSource.negative
    protected fun offerOutputPositive() = outputSeriesResistor.positive

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)

        map.join(
            inputSeriesResistor.negative,
            inputParallelCapacitor.positive
        )

        map.join(
            inputParallelCapacitor.positive,
            inputConsumer.positive
        )

        map.join(
            inputParallelCapacitor.negative,
            inputConsumer.negative
        )

        map.join(
            outputSource.positive,
            outputSeriesResistor.negative
        )
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::tickPre)
        subscribers.addPost(this::tickPost)
    }

    /**
     * Updates the max power input, based on the [energyBuffer].
     * */
    private fun setTargetPowerInput(dt: Double) {
        /**
         * The energy needed to fill up the buffer to maximum capacity:
         * */
        val missingEnergy = (!model.bufferCapacity - energyBuffer).coerceAtLeast(0.0)

        /**
         * Sets the desired input rate:
         * */
        inputConsumer.targetPower = missingEnergy / dt
    }

    /**
     * Updates the max power output, based on the [energyBuffer].
     * */
    private fun setTargetOutput(dt: Double) {
        /**
         * The max energy the buffer can provide (including the efficiency):
         * */
        val maxInstantaneousPower = (energyBuffer / dt) * model.eta

        outputSource.targetPower = min(maxInstantaneousPower, !model.powerRating)
        outputSource.maxPotential = !setpointPotential
    }

    private fun tickPre(dt: Double, phase: SubscriberPhase) {
        setTargetPowerInput(dt)
        setTargetOutput(dt)
    }

    /**
     * Calculates the energy absorbed by the consumer and adds it to the buffer.
     * */
    private fun acceptInputEnergy(dt: Double) {
        val inputPower = inputConsumer.power.coerceAtLeast(0.0 /* Specified in the docs: numerical instability */)
        val energy = inputPower * dt

        energyBuffer += energy

        if(energyBuffer > !model.bufferCapacity) {
            energyBuffer = !model.bufferCapacity
            cell.setChanged()
        }
        else {
            cell.setChangedIf(!energy.approxEq(0.0))
        }
    }

    /**
     * Calculates the energy transferred by the source and removes it from the buffer.
     * @return The waste energy due to efficiency.
     * */
    private fun drainDeliveredEnergy(dt: Double) : Double {
        val deliveredEnergy = outputSource.power * dt

        // Inputting power!
        if(deliveredEnergy < 0.0) {
            return -deliveredEnergy // waste it away, diode?
        }

        val consumedFromBuffer = deliveredEnergy / model.eta

        energyBuffer -= consumedFromBuffer

        if(energyBuffer < 0.0) {
            if(energyBuffer < -1e-3) {
                LOG.debug("Outputted more energy ($consumedFromBuffer) than possible which left buffer at $energyBuffer")
            }

            energyBuffer = 0.0
            cell.setChanged()
        }

        cell.setChangedIf(!consumedFromBuffer.approxEq(0.0))

        return (consumedFromBuffer - deliveredEnergy).coerceAtLeast(0.0)
    }

    private fun tickPost(dt: Double, phase: SubscriberPhase) {
        acceptInputEnergy(dt)

        var rejectedEnergy = drainDeliveredEnergy(dt)
        rejectedEnergy += inputSeriesResistor.power * dt
        rejectedEnergy += outputSeriesResistor.power * dt

        rejectedEnergyAcceptor?.accept(Quantity(rejectedEnergy, JOULE))
    }

    override fun saveObjectNbt(): CompoundTag {
        val tag = CompoundTag()
        tag.putDouble(ENERGY_BUFFER, energyBuffer)
        tag.put(CAPACITOR, inputParallelCapacitor.saveNbt())
        tag.putDouble(SETPOINT, !setpointPotential)
        return tag
    }

    override fun loadObjectNbt(tag: CompoundTag) {
        energyBuffer = tag.getDouble(ENERGY_BUFFER)
        inputParallelCapacitor.loadNbt(tag.getCompound(CAPACITOR))
        setpointPotential = Quantity(tag.getDouble(SETPOINT))
    }

    companion object {
        private const val ENERGY_BUFFER = "energyBuffer"
        private const val CAPACITOR = "capacitor"
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
    CellSpec<TerminalDcToDcConverterCell>(ci, Eln2PowerDevices.TERMINAL_DC_TO_DC_CONVERTER_CELL_800W.get()),
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
        var inputPower = cell.converter.inputConsumer.readouts.power // Let the negative value exist, for out debug log

        builder.debugInIDE { "Buffer: ${cell.converter.energyBuffer.classifyAs(JOULE)}" }
        builder.debugInIDE { "Charge: ${cell.converter.inputParallelCapacitor.readouts.charge.classify()}" }
        builder.debugInIDE { "Input power: ${inputPower.classify()}" }

        if(cell.converter.outputSource.isInSimulation) {
            builder.debugInIDE {
                "SRC Iter: ${cell.converter.outputSource.simulation.lastPowerSourceIterationCount}, " +
                "SRC Res: ${cell.converter.outputSource.simulation.lastPowerSourceMaxResidual}"
            }
        }

        // Clean up the numerical error for player readout:
        inputPower = max(inputPower, Quantity(0.0, WATT))

        builder.quantity(cell.thermalWire.thermalBody.temperature)
        builder.quantityInput(inputPower)
        builder.quantityOutput(cell.converter.outputSource.readouts.potential)
        builder.quantityOutput(cell.converter.outputSource.readouts.current)
        builder.quantityOutput(cell.converter.outputSource.readouts.power)
        builder.quantitySetpoint(cell.converter.setpointPotential)
    }
}
