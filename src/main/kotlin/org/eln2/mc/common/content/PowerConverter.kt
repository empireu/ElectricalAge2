package org.eln2.mc.common.content

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.context.UseOnContext
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.SYMFORCE_EPS
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.mna.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.LARGE_RESISTANCE
import org.ageseries.libage.sim.electrical.mna.component.PowerVoltageSource
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
 * The maximum power input is [powerRating] / [eta], which is used to fill the energy buffer.
 * @param eta The conversion efficiency of the device. *Eout,electrical ≈ Ein,electrical * [eta]*
 * @param energyK Energy buffer capacity multiplier. *Capacity = ([powerRating] / [eta]) * dt * energyK*.
 * This value should always be greater than 1.
 * @param potentialRating The max potential across the device's inputs.
 * This value is used to calculate the initial resistance of the sink resistor, such that the input power is bounded regardless of the source circuit's potential, as long as its open-circuit voltage is, at most, [potentialRating].
 * @param outputResistance Impedance in series with the output circuit. Necessary for stability (currently, the power sources are prone to blowing up unless they have some resistance in series).
 * */
@Suppress("SpellCheckingInspection")
data class DcToDcConverterModel(
    val powerRating: Quantity<Power>,
    val eta: Double,
    val energyK: Double,
    val potentialRating: Quantity<Potential>,
    val outputResistance: Quantity<Resistance>
) {
    val bufferCapacity = Quantity((!powerRating / eta) * CellGraph.DT * energyK, JOULE)
    val initialResistance = Quantity((!potentialRating * !potentialRating) / (!powerRating / eta), OHM)
    val maxInputPower = powerRating / eta
}

fun interface RejectedEnergyAcceptor {
    fun accept(energy: Quantity<Energy>)
}

abstract class DcToDcConverterObject<C : Cell>(cell: C, val model: DcToDcConverterModel, val rejectedEnergyAcceptor: RejectedEnergyAcceptor? = null) : ElectricalObject<C>(cell), PersistentObject {
    var energyBuffer = 0.0
        private set

    // Also saved to NBT, for a start as close as possible to the last state
    val theveninResistor = TheveninEstimatingResistor().also { it.resistance = !model.initialResistance }
    val source = PowerVoltageSource()
    val outputResistor = Resistor().also { it.resistance = !model.outputResistance }
    val tempResistor = Resistor().also { it.resistance = 1e5 } // FIXME remove once solver forests are in

    var setpointPotential = 0.0

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(theveninResistor)
        circuit.add(source)
        circuit.add(outputResistor)
        circuit.add(tempResistor)
    }

    protected fun offerInputNegative() = theveninResistor.offerNegative()
    protected fun offerInputPositive() = theveninResistor.offerPositive()
    protected fun offerOutputNegative() = outputResistor.offerNegative()
    protected fun offerOutputPositive() = source.offerPositive()

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)
        map.join(source.offerNegative(), outputResistor.offerPositive())
        // FIXME:
        map.join(source.offerNegative(), tempResistor.offerPositive())
        map.join(theveninResistor.offerPositive(), tempResistor.offerNegative())
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::tickPre)
        subscribers.addPost(this::tickPost)
    }

    /**
     * Updates the [theveninResistor]'s resistance so the buffer gets filled to max capacity.
     * */
    private fun updateInputSink(dt: Double) {
        val remainingCapacity = (!model.bufferCapacity - energyBuffer).coerceAtLeast(0.0)
        val desiredInputPower = min((remainingCapacity / dt), !model.maxInputPower)

        val sourcePotential = theveninResistor.openCircuitPotentialEstimate

        val sinkResistance = if(desiredInputPower > SYMFORCE_EPS) {
            (sourcePotential * sourcePotential) / desiredInputPower
        }
        else {
            LARGE_RESISTANCE
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
            val desiredPower = min(maxInstantaneousPower, !model.powerRating)

            source.potentialMax = setpointPotential
            source.powerIdeal = desiredPower
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

        val inputEnergy = theveninResistor.power * dt

        energyBuffer += inputEnergy

        if(energyBuffer > !model.bufferCapacity) {
            rejectedEnergy += (energyBuffer - !model.bufferCapacity)
            energyBuffer = !model.bufferCapacity
            cell.setChanged()
        }

        cell.setChangedIf(!inputEnergy.approxEq(0.0))

        return rejectedEnergy
    }

    private fun drainOutputtedEnergy(dt: Double) : Double {
        val deliveredEnergy = source.power * dt
        val consumedFromBuffer = deliveredEnergy / model.eta

        energyBuffer -= consumedFromBuffer

        if(energyBuffer < 0.0) {
            LOG.debug("Outputted more energy ($consumedFromBuffer) than possible which left buffer at $energyBuffer")
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
        rejectedEnergy += tempResistor.power * dt

        rejectedEnergyAcceptor?.accept(Quantity(rejectedEnergy, JOULE))
    }

    override fun saveObjectNbt(): CompoundTag {
        val tag = CompoundTag()
        tag.putDouble(ENERGY_BUFFER, energyBuffer)
        tag.putDouble(RESISTANCE, theveninResistor.resistance)
        tag.putDouble(THEVENIN_RESISTANCE_ESTIMATE, theveninResistor.theveninResistanceEstimate)
        tag.putDouble(OPEN_CIRCUIT_POTENTIAL_ESTIMATE, theveninResistor.openCircuitPotentialEstimate)
        tag.putDouble(SOURCE_POWER, source.powerIdeal)
        tag.putDouble(SETPOINT, setpointPotential)
        return tag
    }

    override fun loadObjectNbt(tag: CompoundTag) {
        energyBuffer = tag.getDouble(ENERGY_BUFFER)
        theveninResistor.resistance = tag.getDouble(RESISTANCE)
        theveninResistor.theveninResistanceEstimate = tag.getDouble(THEVENIN_RESISTANCE_ESTIMATE)
        theveninResistor.openCircuitPotentialEstimate = tag.getDouble(OPEN_CIRCUIT_POTENTIAL_ESTIMATE)
        source.powerIdeal = tag.getDouble(SOURCE_POWER)
        setpointPotential = tag.getDouble(SETPOINT)
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
    model: DcToDcConverterModel,
    inputNegative: Int = 0,
    inputPositive: Int = 1,
    outputNegative: Int = 2,
    outputPositive: Int = 3
) : Cell(ci) {
    @SimObject
    val thermalWire = ThermalWireObject(this, thermalDef)

    @SimObject
    val converter = TerminalDcToDcConverterObject(this, model, inputNegative, inputPositive, outputNegative, outputPositive) { rejectedEnergy ->
        thermalWire.thermalBody.energy += rejectedEnergy
    }.also { it.setpointPotential = 24.0 }

    @Node
    val grid = GridNode(this)

    override fun cellConnectionPredicate(remote: Cell): Boolean {
        return super.cellConnectionPredicate(remote) && remote.hasNode<GridNode>()
    }

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
        val newPotential = (cell.converter.setpointPotential + increment).coerceIn(0.0, !cell.converter.model.potentialRating)

        cell.converter.setpointPotential = newPotential
        cell.setChanged()

        return true
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        if(!hasCell) {
            return
        }

        builder.debug("Energy buffer: ${Quantity(cell.converter.energyBuffer, JOULE).classify()}")
        builder.debug("In RTh: ${Quantity(cell.converter.theveninResistor.theveninResistanceEstimate, OHM).classify()}")
        builder.debug("In OCV: ${Quantity(cell.converter.theveninResistor.openCircuitPotentialEstimate, VOLT).classify()}")
        builder.debug("Out PowerIdeal: ${Quantity(cell.converter.source.powerIdeal, WATT).classify()}")
        builder.debug("Out Power: ${Quantity(cell.converter.source.power, WATT).classify()}")
        builder.debug("Out PotentialMax: ${Quantity(cell.converter.source.potentialMax ?: -1.0, VOLT).classify()}")
        builder.debug("Out potential: ${Quantity(cell.converter.source.potential, VOLT).classify()}")
        builder.debug("Sink res: ${Quantity(cell.converter.theveninResistor.resistance, OHM).classify()}")
        builder.debug("Setpoint: ${Quantity(cell.converter.setpointPotential, VOLT).classify()}")
    }
}
