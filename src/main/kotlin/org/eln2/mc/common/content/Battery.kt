@file:Suppress("LocalVariableName")

package org.eln2.mc.common.content

import net.minecraft.nbt.CompoundTag
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.InterpolatorBuilder
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.kdVectorDOf
import org.ageseries.libage.mathematics.lerp
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.electrical.mna.component.updateResistance
import org.eln2.mc.Datasets
import org.eln2.mc.ItemPersistent
import org.eln2.mc.ItemPersistentLoadOrder
import org.eln2.mc.LOG
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.specs.foundation.CellSpec
import org.eln2.mc.common.specs.foundation.SpecCreateInfo
import org.eln2.mc.data.PoleMap
import org.eln2.mc.extensions.getQuantity
import org.eln2.mc.extensions.useSubTagIfPreset
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.client.render.foundation.MyColor
import kotlin.math.abs
import kotlin.math.pow

interface BatteryView {
    val model: BatteryModel
    /**
     * Gets the total amount of energy stored in the battery/
     * */
    val energy: Quantity<Energy>
    /**
     * Gets the total energy exchanged by this battery.
     * */
    val totalEnergyTransferred: Quantity<Energy>
    /**
     * Gets the battery current. This value's sign depends on the direction of flow.
     * By convention, if the current is incoming, it is positive. If it is outgoing, it is negative.
     * */
    val current: Double
    /**
     * Gets the life parameter of the battery.
     * */
    val life: Double
    /**
     * Gets the number of charge-discharge cycles this battery has been trough.
     * */
    val cycles: Double get() = totalEnergyTransferred / model.energyCapacity
    /**
     * Gets the charge percentage.
     * */
    val charge: Double get() = energy / model.energyCapacity
    /**
     * Gets the charge percentage, mapped using the battery's threshold parameter, as per [BatteryModel.damageChargeThreshold].
     * This value may be negative if the charge is under threshold.
     * */
    val safeCharge: Double get() = map(charge, model.damageChargeThreshold, 1.0, 0.0, 1.0)
    /**
     * Gets the temperature of the battery.
     * */
    val temperature: Quantity<Temperature>
    /**
     * Gets the current power. If positive, this is power going out. Otherwise, this is power coming in.
     * */
    val sourcePower: Quantity<Power>
    /**
     * Gets the energy increment this tick. It is equal to **[sourcePower] * dT**.
     * The signs are as per [sourcePower]
     * */
    val electricalEnergyIncrement: Quantity<Energy>
    /**
     * Gets the capacity percentage of this battery, relative to the initial state.
     * */
    val capacityCoefficient get() = model.capacityFunction.computeCapacity(this).coerceIn(0.0, 1.0)
    /**
     * Gets the capacity of this battery, based on the [capacityCoefficient].
     * */
    val adjustedEnergyCapacity get() = model.energyCapacity * capacityCoefficient
}

/**
 * Computes the voltage of the battery based on the battery's state.
 * */
fun interface BatteryVoltageFunction {
    fun computeVoltage(battery: BatteryView): Quantity<Potential>
}

/**
 * Computes the internal resistance of the battery based on the battery's state.
 * It should never be zero, though this is not enforced and will likely result in a simulation error.
 * */
fun interface BatteryResistanceFunction {
    fun computeResistance(battery: BatteryView): Quantity<Resistance>
}

/**
 * Computes a damage increment, based on the battery's current state.
 * These values are deducted from the battery's life parameter (so they should be positive). The final parameter is clamped.
 * */
fun interface BatteryDamageFunction {
    fun computeDamage(battery: BatteryView, dt: Double): Double
}

/**
 * Computes the capacity of the battery based on the battery's state.
 * This must be a value ranging from 0-1. The result is clamped.
 * */
fun interface BatteryEnergyCapacityFunction {
    fun computeCapacity(battery: BatteryView): Double
}

/**
 * Computes the efficiency of the battery based on its state.
 * The returned value should be in [0, 1].
 */
fun interface BatteryEfficiencyFunction {
    fun computeEfficiency(battery: BatteryView): Double
}

object BatterySpecificHeats {
    // https://www.batterydesign.net/thermal/
    val PB_ACID_VENTED_FLOODED = 1080.0
    val PB_ACID_VRLA_GEL = 900.0
    val VRLA_AGM = 792.0
    val LI_ION_NCA = 830.0
    val LI_ION_NMC = 1040.0
    val LI_ION_NFP = 1145.0
}

object BatteryMaterials {
    val LEAD_ACID_BATTERY = Material(
        "LEAD ACID TEST",
        Quantity(Double.POSITIVE_INFINITY),
        Material.LATEX_RUBBER.thermalConductivity,
        Quantity(BatterySpecificHeats.PB_ACID_VENTED_FLOODED, JOULE_PER_KILOGRAM_KELVIN),
        // https://www.measuringknowhow.com/12v-car-batteries-sizes-weight/
        Quantity(22.7 / (0.26 * 0.173 * 0.225), KILOGRAM_PER_METER3)
    )
}

object BatteryModels {
    private val leadAcidTemperatureEfficiencyCurve = InterpolatorBuilder()
        .with(!Quantity(0.0, CELSIUS), 0.805)
        .with(!Quantity(10.0, CELSIUS), 0.875)
        .with(!Quantity(25.0, CELSIUS), 0.975)
        .with(!Quantity(40.0, CELSIUS), 1.0)
        .with(!Quantity(50.0, CELSIUS), 0.9)
        .with(!Quantity(80.0, CELSIUS), 0.6) // thermal runaway
        .with(!Quantity(90.0, CELSIUS), 0.4)
        .buildCubic()

    fun leadAcid12v(
        capacity: Quantity<Energy>,
        internalResistance: Quantity<Resistance>,
        mass: Quantity<Mass>,
        surfaceArea: Quantity<Area>,
        currentMultiplier: Double,
        characteristicCurrent: Quantity<Current>,
        baseEfficiency: Double = 0.90
    ) = BatteryModel(
        voltageFunction = {
            // PS go to assets/eln2/datasets/lead_acid_12v/src.md to get the documentation. It is a bilinear map, see there
            val voltageDataset = Datasets.LEAD_ACID_VOLTAGE
            val temperature = it.temperature

            if (it.charge > it.model.damageChargeThreshold) {
                Quantity(
                    voltageDataset.evaluate(
                        kdVectorDOf(
                            it.charge,
                            !temperature
                        )
                    ),
                    VOLT
                )
            } else {
                val datasetCeiling = voltageDataset.evaluate(
                    kdVectorDOf(
                        it.model.damageChargeThreshold,
                        !temperature
                    )
                )

                Quantity(
                    lerp(
                        0.0,
                        datasetCeiling,
                        map(
                            it.charge,
                            0.0,
                            it.model.damageChargeThreshold,
                            0.0,
                            1.0
                        )
                    ),
                    VOLT
                )
            }
        },
        resistanceFunction = {
            internalResistance
        },
        damageFunction = { battery, dt ->
            // wtf is that magic number? I don't remember!
            var damage = 0.0

            damage += dt * (1.0 / 3.0) * 1e-6 // 1 month
            damage += !(abs(battery.electricalEnergyIncrement) / (!battery.model.energyCapacity * 50.0))
            damage += dt * abs(battery.current).pow(1.12783256261) * currentMultiplier *
                if(battery.safeCharge > 0.0) 1.0
                else map(battery.charge, 0.0, battery.model.damageChargeThreshold, 1.0, 5.0)

            //println("T: ${battery.life / (damage / dt)}")

            damage
        },
        capacityFunction = { battery ->
            battery.life.pow(0.5) // I don't remember if this is based on anything, probably just pulled out of my ass
        },
        efficiencyFunction = { battery ->
            val tempFactor = leadAcidTemperatureEfficiencyCurve.evaluate(!battery.temperature).coerceIn(0.0, 1.0)

            val alpha = 0.15  // 15% efficiency reduction at end of life
            val cycleFactor = (1.0 - alpha * (1.0 - battery.life)).coerceIn(0.0, 1.0)

            val cRatePenalty = run {
                val current = abs(battery.current)
                1.0 - (0.25 * (current / (current + !characteristicCurrent)))
            }

            (baseEfficiency * tempFactor * cycleFactor * cRatePenalty).coerceIn(0.3, 1.0)
        },
        energyCapacity = capacity,
        0.5,
        BatteryMaterials.LEAD_ACID_BATTERY,
        mass,
        surfaceArea
    )
}

data class BatteryModel(
    val voltageFunction: BatteryVoltageFunction,
    val resistanceFunction: BatteryResistanceFunction,
    val damageFunction: BatteryDamageFunction,
    val capacityFunction: BatteryEnergyCapacityFunction,
    val efficiencyFunction: BatteryEfficiencyFunction,
    /**
     * The energy capacity of the battery. This is the total amount of energy that can be stored.
     * */
    val energyCapacity: Quantity<Energy>,
    /**
     * The charge percentage at which, if the battery continues to discharge, it should start receiving extra damage.
     * */
    val damageChargeThreshold: Double,
    /**
     * Gets the "material" the battery is made of. Since the simulation treats the battery as one homogenous mass,
     * a material should be chosen, that closely resembles the properties of the battery, as seen from the outside.
     * */
    val material: Material,
    /**
     * Gets the mass of the battery, in kilograms.
     * */
    val mass: Quantity<Mass>,
    /**
     * Gets the surface area of the battery, used in thermal connections.
     * */
    val surfaceArea: Quantity<Area>,
)

data class BatteryState(
    /**
     * The total amount of energy stored in the battery.
     * */
    val energy: Quantity<Energy>,
    /**
     * The life parameter of the battery.
     * */
    val life: Double,
    /**
     * The total amount of energy received and sent.
     * */
    val totalEnergyTransferred: Quantity<Energy>
)

private const val ENERGY = "energy"
private const val LIFE = "life"
private const val ENERGY_IO = "energyIo"
private const val LIFE_EPS = 1e-3

/**
 * Simulates the battery based on the functions provided by the [model].
 * Also registers a thermal heating behavior and a temperature explosion behavior that explodes when the battery reaches 100 deg Celsius.
 * */
abstract class BatteryCell(
    ci: CellCreateInfo,
    final override val model: BatteryModel
) : Cell(ci), BatteryView {
    abstract val generator: VRGObject<*>

    @SimObject
    val thermalWire = ThermalWireObject(self(), ThermalMass(model.material, mass = model.mass))

    @Behavior
    val heater = PowerHeatingBehavior({ generator.resistor.power }, thermalWire.thermalBody)

    @Behavior
    val explosion = ThermalBreakdownBehavior.create(Quantity(100.0, CELSIUS), this, thermalWire.thermalBody::temperature)

    final override var energy = Quantity<Energy>(0.0)
    final override var totalEnergyTransferred = Quantity<Energy>(0.0)
    override val current get() = generator.resistor.current

    final override var life = 1.0
    override val temperature get() = thermalWire.thermalBody.temperature
    override val sourcePower get() = Quantity(generator.source.power, WATT)
    override var electricalEnergyIncrement = Quantity(0.0, JOULE)

    private var savedLife = life
    private val stateUpdate = AtomicUpdate<BatteryState>()

    fun deserializeNbt(tag: CompoundTag) {
        stateUpdate.setLatest(
            BatteryState(
                tag.getQuantity(ENERGY),
                tag.getDouble(LIFE),
                tag.getQuantity(ENERGY_IO)
            )
        )
    }

    fun serializeNbt(): CompoundTag {
        val tag = CompoundTag()

        tag.putDouble(ENERGY, !energy)
        tag.putDouble(LIFE, life)
        tag.putDouble(ENERGY_IO, !totalEnergyTransferred)

        return tag
    }

    override fun saveCellData() = serializeNbt()

    override fun loadCellData(tag: CompoundTag) {
        deserializeNbt(tag)
    }

    override fun subscribe(subscribers: SubscriberCollection) = graph.simulationSubscribers.addPre(this::simulationTick)

    private fun appliesExternalUpdates() = stateUpdate.consume {
        energy = it.energy
        life = it.life
        totalEnergyTransferred = it.totalEnergyTransferred
    }

    private fun transfersEnergy(elapsed: Double): Boolean {
        // Get energy transfer:
        electricalEnergyIncrement = Quantity(generator.source.power * elapsed)

        if(electricalEnergyIncrement.value.approxEq(0.0)) {
            return false
        }

        val efficiency = model.efficiencyFunction.computeEfficiency(this).coerceIn(0.0, 1.0)

        val storageIncrement: Quantity<Energy>
        val rejectedEnergy: Quantity<Energy>

        if (electricalEnergyIncrement.value < 0.0) {
            storageIncrement = electricalEnergyIncrement * efficiency
            rejectedEnergy = abs(electricalEnergyIncrement - storageIncrement)
        } else {
            storageIncrement = electricalEnergyIncrement / efficiency
            rejectedEnergy = storageIncrement - electricalEnergyIncrement
        }

        // Update total IO:
        totalEnergyTransferred += abs(electricalEnergyIncrement)

        energy -= storageIncrement

        val capacity = adjustedEnergyCapacity

        if (energy < 0.0) {
            LOG.error("Negative battery energy $locator")
            energy = Quantity(0.0)
        } else if (energy > capacity) {
            // Battery received more energy than capacity
            val extraEnergy = energy - capacity
            energy -= extraEnergy
            // Conserve energy by increasing temperature:
            thermalWire.thermalBody.energy += extraEnergy
        }

        thermalWire.thermalBody.energy += rejectedEnergy

        return true
    }

    private fun simulationTick(elapsed: Double, phase: SubscriberPhase) {
        setChangedIf(appliesExternalUpdates())
        setChangedIf(transfersEnergy(elapsed))

        // Update with tolerance (likely, the resistance is ~constant and the voltage will update sparsely):
        generator.source.potential = !model.voltageFunction.computeVoltage(this)
        generator.resistor.updateResistance(!model.resistanceFunction.computeResistance(this))

        life -= model.damageFunction.computeDamage(this, elapsed)
        life = life.coerceIn(0.0, 1.0)

        setChangedIf(!life.approxEq(savedLife, LIFE_EPS)) {
            savedLife = life
        }
    }

    fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Efficiency: ${(model.efficiencyFunction.computeEfficiency(this) * 100).rounded(2)}%" }
        builder.charge(charge)
        builder.integrity(life)
        builder.quantity(thermalWire.thermalBody.temperature)
        builder.quantityOutput(generator.sourceDisplay.potential)
        builder.quantityOutput(generator.sourceDisplay.current)
        builder.quantityOutput(generator.sourceDisplay.power)
    }
}

class PolarBatteryCell(
    ci: CellCreateInfo,
    model: BatteryModel,
    override val electricalMap: PoleMap,
    override val electricalSize: ElectricalSize
) : BatteryCell(ci, model), SidedElectricalMapped<PolarBatteryCell> {
    @SimObject
    override val generator = PolarVRGObject(this, electricalMap)
}

class TerminalBatteryCell(ci: CellCreateInfo, model: BatteryModel) : BatteryCell(ci, model) {
    override val isExclusivelyElectricalConnected: Boolean
        get() = true

    @SimObject
    override val generator = TerminalVRGObject(this)

    @Node
    val grid = GridNode(this)
}

private const val BATTERY = "battery"

class BatteryPart(ci: PartCreateInfo, provider: CellProvider<BatteryCell>, ) : CellPart<BatteryCell>(ci, provider), ItemPersistent, WrenchRotatable, ComponentDisplay {
    override val order get() = ItemPersistentLoadOrder.AfterSim
    override fun saveToItemNbt(tag: CompoundTag) { tag.put(BATTERY, cell.serializeNbt()) }
    override fun loadFromItemNbt(tag: CompoundTag?) { tag?.useSubTagIfPreset(BATTERY, cell::deserializeNbt) }
    override fun submitDisplay(builder: ComponentDisplayList) { cell.submitDisplay(builder) }
}

class BatterySpec(
    ci: SpecCreateInfo,
    provider: CellProvider<TerminalBatteryCell>,
    negX: Double, negY: Double, negZ: Double, negSX: Double, negSY: Double, negSZ: Double,
    posX: Double, posY: Double, posZ: Double, posSX: Double, posSY: Double, posSZ: Double,
) : CellSpec<TerminalBatteryCell>(ci, provider), ItemPersistent, ComponentDisplay {
    val negative = defineCellBoxTerminalBB(
        negX, negY, negZ,
        negSX, negSY, negSZ,
        highlightColor = MyColor(1f, 0f, 0f, 1f)
    )

    val positive = defineCellBoxTerminalBB(
        posX, posY, posZ,
        posSX, posSY, posSZ,
        highlightColor = MyColor(1f, 1f, 0f, 0f)
    )

    override val order get() = ItemPersistentLoadOrder.AfterSim
    override fun saveToItemNbt(tag: CompoundTag) { tag.put(BATTERY, cell.serializeNbt()) }
    override fun loadFromItemNbt(tag: CompoundTag?) { tag?.useSubTagIfPreset(BATTERY, cell::deserializeNbt) }
    override fun submitDisplay(builder: ComponentDisplayList) { cell.submitDisplay(builder) }
}
