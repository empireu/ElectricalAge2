package org.eln2.mc

import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.lerp
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.LARGE_RESISTANCE
import org.ageseries.libage.sim.electrical.mna.NEGATIVE
import org.ageseries.libage.sim.electrical.mna.POSITIVE
import org.ageseries.libage.sim.electrical.mna.component.IResistor
import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.ageseries.libage.sim.electrical.mna.component.Term
import org.ageseries.libage.sim.electrical.mna.component.VoltageSource
import org.ageseries.libage.sim.electrical.mna.component.updateResistance
import org.ageseries.libage.utils.Stopwatch
import org.ageseries.libage.utils.sourceName
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.reflect.jvm.kotlinProperty

class FramerateIndependentSmoother1d(val tau: Double) {
    var value = 0.0

    private var initialized = false
    private val watch = Stopwatch()

    fun reset() {
        initialized = false
        value = 0.0
    }

    fun update(target: Double) : Double {
        val dt = !watch.sample()

        if(!initialized) {
            value = target
            initialized = true
            return dt
        }

        val alpha = 1.0 - exp(-dt / tau)
        value += (target - value) * alpha

        return dt
    }

    fun pullDown(eps: Double = 1e-6) {
        if(abs(value) < eps) {
            value = 0.0
        }
    }
}

class FramerateIndependentSmoother2d(val tau: Double) {
    var x = 0.0
    var y = 0.0

    private var initialized = false
    private val watch = Stopwatch()

    fun update(targetX: Double, targetY: Double) : Double {
        val dt = !watch.sample()

        if(!initialized) {
            x = targetX
            y = targetY
            initialized = true
            return dt
        }

        val alpha = 1.0 - exp(-dt / tau)
        x += (targetX - x) * alpha
        y += (targetY - y) * alpha

        return dt
    }
}

/**
 * Libage extensions class. Ideally, most things here (but most necessarily, quantities) would be moved to libage.
 * However, changing it and redeploying is slow, so things end up here instead.
 * */

private const val JVM_NAME = "LibageKt"
private const val CLASS_NAME = "org.eln2.mc.$JVM_NAME"

private val SELF by lazy {
    checkNotNull(Class.forName(CLASS_NAME)) {
        "Failed to resolve Libage extensions class $CLASS_NAME"
    }
}

/**
 * Convention for the pin "exported" to other Electrical Objects.
 * */
const val EXTERNAL_PIN: Int = POSITIVE

/**
 * Convention for the pin used "internally" by Electrical Objects.
 * */
const val INTERNAL_PIN: Int = NEGATIVE

data class TermRef(val component: Term, val index: Int)

fun Term.offerPositive() = TermRef(this, POSITIVE)
fun Term.offerNegative() = TermRef(this, NEGATIVE)
fun Term.offerInternal() = TermRef(this, INTERNAL_PIN)
fun Term.offerExternal() = TermRef(this, EXTERNAL_PIN)

fun ElectricalConnectivityMap.join(a: TermRef, b: TermRef) {
    this.connect(a.component, a.index, b.component, b.index)
}

class MyPowerVoltageSource : VoltageSource() {
    companion object {
        private const val EPS = 1e-5
    }

    var potentialMax = 0.0
    var powerIdeal = 0.0

    private fun solve(): Boolean {
        val factor = if(powerIdeal.approxEq(0.0, EPS)) {
            0.0
        } else {
            power / powerIdeal
        }

        if(factor.approxEq(1.0, EPS)){
            return false // close enough
        }

        // We assume a quadratic relationship between the target and the power. This is perfectly true for LTI circuits,
        // but breaks down in the presence of non-linear components in (VERY) potentially-exciting ways, including ways that
        // may prevent convergence. (TODO: account for these cases*)
        var desTarget = if(factor.approxEq(0.0, EPS)) {
            // Degenerate case: power is very small (absolutely) relative to powerIdeal.
            // This usually happens under open-circuit conditions (ELN calls them "highImpedance").
            // In those cases, just float to the maximum target, or zero if we don't have one.
            potentialMax
        } else {
            // Safety: t^2 is always positive, as is abs(factor)
            sqrt(potential * potential / abs(factor))
        }

        desTarget = desTarget.coerceIn(-potentialMax, +potentialMax)
        desTarget = lerp(potential, desTarget, 0.5)

        if(potential.approxEq(desTarget, EPS)) {
            return false // No change in target--usually because we hit AbsMax
        }

        potential = desTarget
        return true
    }

    override fun simStep() {
        solve()
        super.simStep()
    }
}

@Suppress("PrivatePropertyName", "LocalVariableName")
class TheveninEstimatingResistor(
    val lambda: Double = 0.95,
    val alpha: Double = 0.4,
    val ditherFraction: Double = 0.01 / 1000.0,
    val maxSubsteps: Int = 25,
    val minResistanceEstimate: Double = 1e-6,
    val maxResistanceEstimate: Double = LARGE_RESISTANCE,
    val minEffectiveSamples: Double = 5.0,
    val minDeltaICutoff: Double = 1e-6,
    val maxChangeFactorPerStep: Double = 1.2,
) : Resistor() {
    private var SI = 0.0 // Sum I
    private var SV = 0.0 // Sum V
    private var SII = 0.0 // Sum I^2
    private var SIV = 0.0 // Sum I * V
    private var NEff = 0.0 // Effective number of samples

    private var baseResistance = 0.0
    private var previousPotential = 0.0
    private var previousCurrent = 0.0

    var substeps = 0
        private set

    private var ditherSign = 1

    var theveninResistanceEstimate = LARGE_RESISTANCE
    var openCircuitPotentialEstimate = 0.0

    override fun preStep(dt: Double) {
        baseResistance = resistance
        substeps = 0
        ditherSign = 1
    }

    override fun postStep(dt: Double) {
        if (resistance != baseResistance) {
            resistance = baseResistance
        }
    }

    override fun simStep() {
        if (substeps >= maxSubsteps) {
            resistance = baseResistance
            return
        }

        val V = potential
        val I = current

        if (abs(I) > minDeltaICutoff) {
            SI = lambda * SI + I
            SV = lambda * SV + V
            SII = lambda * SII + I * I
            SIV = lambda * SIV + I * V
            NEff = lambda * NEff + 1.0

            val d = (NEff * SII - SI * SI)

            // Compute regression:
            if (NEff >= minEffectiveSamples && abs(d) > 1e-12) {
                val slope = (NEff * SIV - SI * SV) / d // -RTh
                val intercept = (SV - slope * SI) / NEff // OC

                var newRth = -slope
                if (newRth.isFinite() && newRth > 0.0) {
                    newRth = newRth.coerceIn(minResistanceEstimate, maxResistanceEstimate)

                    // Rate limit:
                    val maxUp = theveninResistanceEstimate * maxChangeFactorPerStep
                    val maxDown = theveninResistanceEstimate / maxChangeFactorPerStep
                    newRth = newRth.coerceIn(maxDown, maxUp)

                    // Additional smoothing:
                    theveninResistanceEstimate = if (theveninResistanceEstimate.isFinite()) {
                        alpha * newRth + (1.0 - alpha) * theveninResistanceEstimate
                    } else {
                        newRth
                    }

                    openCircuitPotentialEstimate = intercept
                }
            }
        }

        previousPotential = V
        previousCurrent = I

        perturb()
        substeps++
    }

    private fun perturb() {
        val dithering = 1.0 + ditherSign * ditherFraction
        resistance = (baseResistance * dithering)
        ditherSign *= -1
    }
}

fun TheveninEstimatingResistor.setLoad(power: Double, minResistance: Double = 1e-6, maxResistance: Double = LARGE_RESISTANCE, resistanceEps: Double = 1e-5) : Double {
    require(power >= 0.0) { "TheveninEstimatingResistor#setLoad" }

    if(power.approxEq(0.0)) {
        this.updateResistance(maxResistance, resistanceEps)
        return maxResistance
    }

    val v = this.openCircuitPotentialEstimate

    var loadResistance = (v * v) / power

    if(loadResistance.isNaN() || loadResistance.isInfinite()) {
        loadResistance = 0.0
    }

    loadResistance = loadResistance.coerceIn(minResistance, maxResistance)

    this.updateResistance(loadResistance, resistanceEps)

    return loadResistance
}

/**
 * Helper for displaying the various state variables of components, meant to be used for the jade integration.
 * The jade integration collects data on the server thread, by accessing the simulation cross-thread.
 * This is fine in principle, but the collector will also sometimes collect data during subticks, which will give fluctuating display values.
 * This utility duplicates the state variables for reading by the server thread, and updates them after each simulation step. It is considered acceptable that the reader can see mis-matched values (read while new values were being loaded).
 * Extra data is also derived: [SimulationDisplayer.DisplayThermalMass.thermalPower], [SimulationDisplayer.DisplayThermalMass.temperatureRate].
 * */
interface SimulationDisplayer {
    fun display(thermalMass: ThermalMass) : DisplayThermalMass
    fun display(voltageSource: VoltageSource) : DisplayVoltageSource
    fun display(resistor: IResistor) : DisplayResistor
    fun display(resistor: TheveninEstimatingResistor) : DisplayTheveninResistor
    fun display(powerSource: MyPowerVoltageSource) : DisplayVoltagePowerSource
    fun remove(source: DisplaySource)

    interface DisplaySource

    interface DisplayThermalMass : DisplaySource {
        val energy: Quantity<Energy>
        val temperature: Quantity<Temperature>
        /**
         * The rate of change of the thermal energy, derived internally.
         * */
        val thermalPower: Quantity<Power>
        /**
         * The rate of change of the temperature, derived internally.
         * */
        val temperatureRate: Quantity<TemperatureRate>
    }

    interface DisplayVoltageSource : DisplaySource {
        val potential: Quantity<Potential>
        val current: Quantity<Current>
        val power: Quantity<Power>
    }

    interface DisplayResistor : DisplaySource{
        val resistance: Quantity<Resistance>
        val current: Quantity<Current>
        val potential: Quantity<Potential>
        val power: Quantity<Power>
    }

    interface DisplayTheveninResistor : DisplaySource {
        val theveninResistance: Quantity<Resistance>
        val openCircuitPotentialEstimate: Quantity<Potential>
        val resistance: Quantity<Resistance>
        val current: Quantity<Current>
        val potential: Quantity<Potential>
        val power: Quantity<Power>
    }

    interface DisplayVoltagePowerSource : DisplaySource {
        val potential: Quantity<Potential>
        val potentialMax: Quantity<Potential>
        val current: Quantity<Current>
        val power: Quantity<Power>
        val powerIdeal: Quantity<Power>
    }
}

class SimulationDisplayerImpl() : SimulationDisplayer {
    private val implementations = ArrayList<Implementation>()
    private var firstStep = true

    private inline fun<reified Target> Implementation.add() : Target {
        check(!implementations.any { it === this || it.obj === this.obj }) {
            "Duplicate add repository $this"
        }

        implementations.add(this)
        // this.step(-1.0) DANG it grissess, some properties need the pins and doesn't return 0 if they aren't initialized

        return this as Target
    }

    override fun display(thermalMass: ThermalMass) = DisplayThermalMassImpl(thermalMass)
        .add<SimulationDisplayer.DisplayThermalMass>()

    override fun display(voltageSource: VoltageSource) = DisplayVoltageSourceImpl(voltageSource)
        .add<SimulationDisplayer.DisplayVoltageSource>()

    override fun display(resistor: IResistor) = DisplayResistorImpl(resistor)
        .add<SimulationDisplayer.DisplayResistor>()

    override fun display(resistor: TheveninEstimatingResistor) = DisplayTheveninResistorImpl(resistor)
        .add<SimulationDisplayer.DisplayTheveninResistor>()

    override fun display(powerSource: MyPowerVoltageSource) = DisplayVoltagePowerSourceImpl(powerSource)
        .add<SimulationDisplayer.DisplayVoltagePowerSource>()

    fun step(dt: Double) {
        implementations.forEach {
            it.step(if(firstStep) -1.0 else dt)
        }

        if(implementations.size > 25) {
            error("Dangling displays! ${implementations.size}")
        }

        firstStep = false
    }

    override fun remove(source: SimulationDisplayer.DisplaySource) {
        check(implementations.remove(source)) {
            "Tried to remove non-added display source $source"
        }
    }

    private interface Implementation : SimulationDisplayer.DisplaySource {
        /**
         * Updates the stored values with the data from the simulation.
         * It is considered acceptable that the reader can see mis-matched values (read while new values were being loaded).
         * The only requirement is that the values are concrete, and not from subticks.
         * @param dt The timestep, used for updating derived quantities. For the first timestep, [dt] is `-1.0`.
         * */
        fun step(dt: Double)

        val obj: Any
    }

    private class DisplayThermalMassImpl(override val obj: ThermalMass) : SimulationDisplayer.DisplayThermalMass, Implementation {
        override var energy = Quantity<Energy>(0.0)
        override var temperature = Quantity<Temperature>(0.0)

        private var previousEnergy = Quantity<Energy>(0.0)
        private var previousTemperature = Quantity<Temperature>(0.0)

        override var thermalPower = Quantity<Power>(0.0)
        override var temperatureRate = Quantity<TemperatureRate>(0.0)

        override fun step(dt: Double) {
            energy = obj.energy
            temperature = obj.temperature

            if(dt != -1.0) {
                thermalPower = Quantity((!energy - !previousEnergy) / dt, WATT)
                temperatureRate = Quantity((!temperature - !previousTemperature) / dt, KELVIN_PER_SECOND)
            }

            previousEnergy = energy
            previousTemperature = temperature
        }

    }

    private class DisplayVoltageSourceImpl(override val obj: VoltageSource) : SimulationDisplayer.DisplayVoltageSource, Implementation {
        override var potential = Quantity<Potential>(0.0)
        override var current = Quantity<Current>(0.0)
        override var power = Quantity<Power>(0.0)

        override fun step(dt: Double) {
            potential = Quantity(obj.potential, VOLT)
            current = Quantity(obj.current, AMPERE)
            power = Quantity(obj.power, WATT)
        }
    }

    private class DisplayResistorImpl(override val obj: IResistor) : SimulationDisplayer.DisplayResistor, Implementation {
        override var resistance = Quantity<Resistance>(0.0)
        override var current = Quantity<Current>(0.0)
        override var potential = Quantity<Potential>(0.0)
        override var power = Quantity<Power>(0.0)

        override fun step(dt: Double) {
            resistance = Quantity(obj.resistance, OHM)
            current = Quantity(obj.current, AMPERE)
            potential = Quantity(obj.potential, VOLT)
            power = Quantity(obj.power, WATT)
        }
    }

    private class DisplayTheveninResistorImpl(override val obj: TheveninEstimatingResistor) : SimulationDisplayer.DisplayTheveninResistor, Implementation {
        override var theveninResistance = Quantity<Resistance>(0.0)
        override var openCircuitPotentialEstimate = Quantity<Potential>(0.0)
        override var resistance = Quantity<Resistance>(0.0)
        override var current = Quantity<Current>(0.0)
        override var potential = Quantity<Potential>(0.0)
        override var power = Quantity<Power>(0.0)

        override fun step(dt: Double) {
            theveninResistance = Quantity(obj.theveninResistanceEstimate, OHM)
            openCircuitPotentialEstimate = Quantity(obj.openCircuitPotentialEstimate, VOLT)
            resistance = Quantity(obj.resistance, OHM)
            current = Quantity(obj.current, AMPERE)
            potential = Quantity(obj.potential, VOLT)
            power = Quantity(obj.power, WATT)
        }
    }

    private class DisplayVoltagePowerSourceImpl(override val obj: MyPowerVoltageSource) : SimulationDisplayer.DisplayVoltagePowerSource, Implementation {
        override var potential = Quantity<Potential>(0.0)
        override var potentialMax = Quantity<Potential>(0.0)
        override var current = Quantity<Current>(0.0)
        override var power = Quantity<Power>(0.0)
        override var powerIdeal = Quantity<Power>(0.0)

        override fun step(dt: Double) {
            potential = Quantity(obj.potential, VOLT)
            potentialMax = Quantity(obj.potentialMax, VOLT)
            current = Quantity(obj.current, AMPERE)
            power = Quantity(obj.power, WATT)
            powerIdeal = Quantity(obj.powerIdeal, WATT)
        }
    }
}

/**
 * Calculates the resistance of a conductor with this electrical resistivity, in the shape of a cylinder of length [L] and cross-sectional area [A].
 * @return The resistance of the conductor.
 * */
@Suppress("LocalVariableName")
fun Quantity<ElectricalResistivity>.cylinderResistance(L: Quantity<Distance>, A: Quantity<Area>) = Quantity((!this * !L) / !A, OHM)

@DimensionClassifier("kg×m²") interface Inertia
val KILOGRAM_METER_SQUARED = standardScale<Inertia>()

@DimensionClassifier("Nms") interface ViscousFriction
val NEWTON_METER_SECOND = standardScale<ViscousFriction>()

@DimensionClassifier("rad") interface Angle
val RADIAN = standardScale<Angle>()

@DimensionClassifier("rad/s") interface AngularVelocity
val RADIAN_PER_SECOND = standardScale<AngularVelocity>()

@DimensionClassifier("Nm") interface Torque
val NEWTON_METER = standardScale<Torque>()

@ScaleClassifier("rps")
val REVOLUTION_PER_SECOND = RADIAN_PER_SECOND sourceAmplify 1.0 / 0.1591549430919

@DimensionClassifier("K/s") interface TemperatureRate
val KELVIN_PER_SECOND = standardScale<TemperatureRate>()

// Why is it private in libage? :
internal infix fun <U> SourceQuantityScale<U>.sourceAmplify(amplify: Double) =
    SourceQuantityScale<U>(dimensionType, Scale(scale.factor / amplify, scale.base))

inline fun<reified T> Double.classifyAs(scale: SourceQuantityScale<T>) = Quantity<T>(this, scale).classify()

val ELN2_DIMENSION_TYPES : BiMap<Class<*>, String> = run {
    val additional = run {
        val dimensionTypes = HashSet<Class<*>>()

        SELF.declaredFields.forEach { field ->
            if((QuantityScale::class.java).isAssignableFrom(field.type)) {
                val property = field.kotlinProperty
                    ?: return@forEach

                val scale = checkNotNull(property.getter.call() as? QuantityScale<*>) {
                    "Failed to fetch $property"
                }

                dimensionTypes.add(scale.dimensionType)
            }
        }

        dimensionTypes
    }.associateWithBi { it.sourceName() }

    val map = mutableBiMapOf<Class<*>, String>()

    DIMENSION_TYPES.forward.forEach {
        map.add(it.key, it.value)
    }

    additional.forward.forEach {
        map.add(it.key, it.value)
    }

    map
}
