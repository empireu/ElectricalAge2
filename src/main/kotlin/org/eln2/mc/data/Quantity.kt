@file:Suppress("LocalVariableName")

package org.eln2.mc.data

import org.ageseries.libage.data.*

/**
 * Calculates the resistance of a conductor with this electrical resistivity, in the shape of a cylinder of length [L] and cross-sectional area [A].
 * @return The resistance of the conductor.
 * */
fun Quantity<ElectricalResistivity>.cylinderResistance(L: Quantity<Distance>, A: Quantity<Area>) = Quantity((!this * !L) / !A, OHM)
@DimensionClassifier("kg×m²") interface Inertia

val KILOGRAM_METER_SQUARED = standardScale<Inertia>()

@DimensionClassifier("Nms") interface ViscousFriction

val NEWTON_METER_SECOND = standardScale<ViscousFriction>()

@DimensionClassifier("rad/s") interface AngularVelocity

val RADIAN_PER_SECOND = standardScale<AngularVelocity>()

@DimensionClassifier("Nm") interface Torque

val NEWTON_METER = standardScale<Torque>()

@ScaleClassifier("rps")
val REVOLUTION_PER_SECOND = RADIAN_PER_SECOND sourceAmplify 1.0 / 0.1591549430919

// Why is it private in libage? :
internal infix fun <U> SourceQuantityScale<U>.sourceAmplify(amplify: Double) = SourceQuantityScale<U>(dimensionType, Scale(scale.factor / amplify, scale.base))
