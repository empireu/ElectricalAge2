package org.ageseries.libage.sim.kinetic

import org.ageseries.libage.mathematics.SYMFORCE_EPS
import kotlin.math.abs
import kotlin.math.sign

/**
 * A one-ended node.
 * Useful for e.g. flywheels, and for the gears of a gearbox or the halves of a clutch.
 * */
class KineticMono : FrictionKineticNode(false) {
    val extension = RigidKineticExtension(this, 1)

    override fun simulationDestroyed() {
        super.simulationDestroyed()
        extension.simulationDestroyed()
    }
}

/**
 * A two-ended node.
 * It's pretty much the bread and butter of all simulations.
 * */
class KineticDouble(allowOptimization: Boolean = true) : FrictionKineticNode(allowOptimization) {
    val e1 = RigidKineticExtension(this, 1)
    val e2 = RigidKineticExtension(this, -1)

    override fun simulationDestroyed() {
        super.simulationDestroyed()
        e1.simulationDestroyed()
        e2.simulationDestroyed()
    }

    fun KineticDouble.minus() = this.e1
    fun KineticDouble.plus() = this.e2
}

/**
 * Represents a rigid line of shafts connected end-to-end. They have the same inertia, same max lambdas, gear ratio of 1.
 * */
class LineShaft(val lineGraph: Array<KineticDouble>) : KineticNode(false), KineticNodeProxy {
    val e1 = RigidKineticExtension(this, 1)
    val e2 = RigidKineticExtension(this, -1)

    private var previousLineOmega = 0.0
    private val originalExternalTorque = DoubleArray(lineGraph.size)

    init {
        inertia = lineGraph.sumOf { it.inertia }
    }

    private fun importShaftAngularVelocities() {
        // Import angular velocity by conserving angular momentum (inelastic collision):
        angularVelocity = lineGraph.sumOf { it.inertia * it.angularVelocity } / inertia
        // Writing the values back is not necessary, it is done in [distributeResults] after the simulation runs.
    }

    fun prepareForStep() {
        importShaftAngularVelocities()

        var totalExternalTorque = 0.0
        for (i in lineGraph.indices) {
            val shaft = lineGraph[i]

            originalExternalTorque[i] = shaft.externalTorque

            totalExternalTorque += shaft.externalTorque
            shaft.externalTorque = 0.0

            shaft.previousAngle = shaft.angle
        }

        var totalFriction = 0.0
        val lineOmega = this.angularVelocity
        for (i in lineGraph.indices) {
            val shaft = lineGraph[i]
            val viscous = -shaft.viscousDamping * lineOmega

            val frictionForShaft = if (abs(lineOmega) > shaft.velocityEps) {
                -shaft.coulombFriction * sign(lineOmega) + viscous
            } else {
                val t = originalExternalTorque[i]
                if (abs(t) <= shaft.staticFriction) {
                    -t
                } else {
                    -shaft.coulombFriction * sign(t) + viscous
                }
            }

            shaft.frictionTorque = frictionForShaft
            totalFriction += frictionForShaft
        }

        externalTorque = totalExternalTorque + totalFriction
        previousLineOmega = angularVelocity
    }

    override fun setSimulation(id: Int, simulation: KineticSimulation, proxy: KineticNodeProxy?) {
        super.setSimulation(id, simulation, proxy)

        lineGraph.forEachIndexed { id, shaft ->
            shaft.setSimulation(id, simulation, this)
        }
    }

    override fun simulationDestroyed() {
        super.simulationDestroyed()

        lineGraph.forEach { shaft ->
            shaft.simulationDestroyed()
        }
    }

    fun distributeResults() {
        val deltaAngle = angle - previousAngle
        val deltaOmega = angularVelocity - previousLineOmega

        lineGraph.forEach { shaft ->
            shaft.angle += deltaAngle
            shaft.angularVelocity = this.angularVelocity
        }

        if (e1.constraints.isNotEmpty()) {
            check(e1.constraints.size == 1)
            val leftPhysicalConstraint = lineGraph.first().e1.constraints[0]
            leftPhysicalConstraint.lambda = e1.constraints[0].lambda
        }

        if (e2.constraints.isNotEmpty()) {
            check(e2.constraints.size == 1)
            val rightPhysicalConstraint = lineGraph.last().e2.constraints[0]
            rightPhysicalConstraint.lambda = e2.constraints[0].lambda
        }

        fun impulseOnExtensionFromConstraint(constraint: RigidExtensionConstraint, extension: RigidKineticExtension): Double {
            if (constraint.a === extension) {
                return constraint.lambda
            }

            if (constraint.b === extension) {
                return constraint.lambda * (-constraint.b.ratio / constraint.a.ratio)
            }

            error("Constraint does not reference extension")
        }

        fun coefficientForExtension(constraint: RigidExtensionConstraint, extension: RigidKineticExtension): Double {
            return if (constraint.a !== extension) {
                -constraint.b.ratio / constraint.a.ratio
            }
            else 1.0
        }

        for (i in 0 until lineGraph.size - 1) {
            val node = lineGraph[i]
            val leftExtension = node.e1
            val rightExtension = node.e2

            check(rightExtension.constraints.isNotEmpty()) {
                "Missing internal constraint on e2 of node $i"
            }

            val rightConstraint = rightExtension.constraints[0] as RigidExtensionConstraint

            val leftContribution = if (leftExtension.constraints.isNotEmpty()) {
                val leftConstraint = leftExtension.constraints[0] as RigidExtensionConstraint
                impulseOnExtensionFromConstraint(leftConstraint, leftExtension)
            } else {
                0.0
            }

            val neededImpulse = node.inertia * deltaOmega

            val c = coefficientForExtension(rightConstraint, rightExtension)

            val lambdaRight = if (abs(c) < SYMFORCE_EPS) {
                0.0
            } else {
                (neededImpulse - leftContribution) / c
            }

            // assign to the internal constraint
            rightConstraint.lambda = lambdaRight
        }
    }

    override fun resetForRestart() {
        super.resetForRestart()

        lineGraph.forEach {
            it.resetForRestart()
        }
    }
}

/**
 * A three-ended node.
 * Useful for modeling a triple joint as a single node.
 * */
class KineticTriple() : FrictionKineticNode(false) {
    val e1 = RigidKineticExtension(this, 1)
    val e2 = RigidKineticExtension(this, -1)
    val e3 = RigidKineticExtension(this, 1)

    override fun simulationDestroyed() {
        super.simulationDestroyed()
        e1.simulationDestroyed()
        e2.simulationDestroyed()
        e3.simulationDestroyed()
    }
}