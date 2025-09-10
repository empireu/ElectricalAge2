package org.eln2.mc

import net.minecraft.nbt.CompoundTag
import org.ageseries.libage.mathematics.SYMFORCE_EPS
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.LARGE_RESISTANCE
import org.ageseries.libage.sim.electrical.mna.NEGATIVE
import org.ageseries.libage.sim.electrical.mna.POSITIVE
import org.ageseries.libage.sim.electrical.mna.component.*
import kotlin.math.abs

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

class TheveninEstimatingResistor(
    val alphaRth: Double = 0.047619047619047616, // dt / (t + dt), dt = 1/100, t = 0.2 seconds
    val ditherFraction: Double = 0.1 / 1000.0,
    val maxSubsteps: Int = 10,
    val minResistanceEstimate: Double = 1e-6,
    val maxResistanceEstimate: Double = LARGE_RESISTANCE
) : Resistor() {
    private var baseResistance = 0.0
    private var previousPotential = 0.0
    private var previousCurrent = 0.0

    var substeps = 0
        private set

    private var gotFirstEstimate = false
    private var ditherSign = 0

    override fun preStep(dt: Double) {
        baseResistance = resistance
        substeps = 0
        ditherSign = 1
    }

    override fun postStep(dt: Double) {
        if(resistance != baseResistance) {
            resistance = baseResistance
        }
    }

    override fun simStep() {
        if(substeps >= maxSubsteps) {
            resistance = baseResistance
            return
        }

        val potential = potential
        val current = current

        if(substeps != 0) {
            val dV = potential - previousPotential
            val dI = current - previousCurrent

            if (abs(dI) > SYMFORCE_EPS) {
                val rThInstant = -dV / dI

                if (rThInstant.isFinite() && rThInstant > 0.0) {
                    theveninResistanceEstimate = if (gotFirstEstimate) {
                        (alphaRth * rThInstant + (1.0 - alphaRth) * theveninResistanceEstimate)
                    } else {
                        gotFirstEstimate = true
                        rThInstant
                    }

                    theveninResistanceEstimate = theveninResistanceEstimate.coerceIn(minResistanceEstimate, maxResistanceEstimate)
                }
            }

            openCircuitPotentialEstimate = potential + current * theveninResistanceEstimate
        }

        previousPotential = potential
        previousCurrent = current
        perturb()

        substeps++
    }

    private fun perturb() {
        resistance = baseResistance * (1.0 + ditherSign * ditherFraction)
        ditherSign *= -1
    }

    var theveninResistanceEstimate = LARGE_RESISTANCE
    var openCircuitPotentialEstimate = 0.0
}
