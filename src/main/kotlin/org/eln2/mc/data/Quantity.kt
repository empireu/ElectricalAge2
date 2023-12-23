@file:Suppress("LocalVariableName")

package org.eln2.mc.data

import org.ageseries.libage.data.*

/**
 * Calculates the resistance of a conductor with this electrical resistivity, in the shape of a cylinder of length [L] and cross-sectional area [A].
 * @return The resistance of the conductor.
 * */
fun Quantity<ElectricalResistivity>.cylinderResistance(L: Quantity<Distance>, A: Quantity<Area>) = Quantity((!this * !L) / !A, OHM)
