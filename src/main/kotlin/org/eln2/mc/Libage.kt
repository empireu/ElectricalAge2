package org.eln2.mc

import org.ageseries.libage.data.*
import org.ageseries.libage.sim.electrical.Port
import org.ageseries.libage.sim.kinetic.FrictionKineticNode
import org.ageseries.libage.sim.kinetic.KineticDouble
import org.ageseries.libage.sim.kinetic.KineticMono
import org.ageseries.libage.sim.kinetic.KineticTriple
import org.eln2.mc.common.cells.foundation.CellGraph

private const val LIMIT_LAMBDA_MULTIPLIER = 5.0

// The bias will have some trouble with this, but it's fine (we are breaking the devices):

val PLUS = 0
val MINUS = 1

fun Port.offerExternal() = this.positive
fun Port.offerInternal() = this.negative

fun KineticMono.setSafeTorque(threshold: Quantity<Torque>) {
    val limit = LIMIT_LAMBDA_MULTIPLIER * (!threshold * CellGraph.DT)
    this.extension.maxLambda = limit
}

fun KineticDouble.setSafeTorque(threshold: Quantity<Torque>) {
    val limit = LIMIT_LAMBDA_MULTIPLIER * (!threshold * CellGraph.DT)
    this.e1.maxLambda = limit
    this.e2.maxLambda = limit
}

fun KineticTriple.setSafeTorque(threshold: Quantity<Torque>) {
    val limit = LIMIT_LAMBDA_MULTIPLIER * (!threshold * CellGraph.DT)
    this.e1.maxLambda = limit
    this.e2.maxLambda = limit
    this.e3.maxLambda = limit
}

fun KineticDouble.minus() = this.e1
fun KineticDouble.plus() = this.e2

data class FrictionNodeDescription(
    val inertia: Quantity<Inertia>,
    val damping: Double,
    val coulombFriction: Quantity<Torque>,
    val staticThreshold: Quantity<Torque>,
    val velocityEps: Quantity<AngularVelocity> = Quantity(0.01, RADIAN_PER_SECOND)
) {
    fun applyTo(shaft: FrictionKineticNode) {
        shaft.inertia = !inertia
        shaft.viscousDamping = damping
        shaft.coulombFriction = !coulombFriction
        shaft.staticFriction = !staticThreshold
        shaft.velocityEps = !velocityEps
    }
}
