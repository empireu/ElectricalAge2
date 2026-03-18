package org.ageseries.libage.sim.kinetic

import org.ageseries.libage.data.JOULE
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.RADIAN
import org.ageseries.libage.data.RADIAN_PER_SECOND
import org.ageseries.libage.mathematics.SYMFORCE_EPS
import org.ageseries.libage.mathematics.approxEq
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign


interface KineticNodeProxy

/**
 * Represents a homogenous rotating element in the simulation.
 * To connect to other nodes, each node exports one or more [KineticExtension]s.
 * Direct constraints can also be created, but impulse semantics will be different. Used only inside Objects.
 * The backend then resolves pairs of extensions to the corresponding constraint.
 * */
abstract class KineticNode(val allowOptimization: Boolean) {
    private var idInternal = -1
    private var simulatorInternal: KineticSimulation? = null
    private var proxyInternal: KineticNodeProxy? = null

    val isInSimulation get() = idInternal != -1

    /**
     * If [proxy] is null, this is the index in the backing storage of the solver. Otherwise, it's the index in whatever storage [proxy] uses.
     * */
    val idInOwner get() = if(idInternal == -1) error("Cannot get ID before added to simulation") else idInternal
    val simulation get() = simulatorInternal ?: error("Cannot get simulator before added")
    val proxy get() = if(simulatorInternal == null) error("Cannot get proxy before added") else proxyInternal

    val isInProxy get() = proxyInternal != null

    private val constraintsInternal = ArrayList<NodeConstraint<*, *>>()
    val nodeConstraints: List<NodeConstraint<*, *>> get() = constraintsInternal

    /**
     * Gets the impulse applied by [nodeConstraints]. Doesn't include extensions.
     * */
    val nodeConstraintImpulse: Double
        get() {
            var impulse = 0.0

            for (c in constraintsInternal) {
                // c.impulseA/impulseB are expected to be implemented by the constraint
                impulse += if (c.a === this) c.impulseA else c.impulseB
            }

            return impulse
        }

    internal open fun setSimulation(id: Int, simulation: KineticSimulation, proxy: KineticNodeProxy?) {
        if(isInSimulation) {
            error("Tried to set simulator without destroying old one")
        }

        if(!allowOptimization && proxy != null) {
            error("Tried to set proxy but optimization is now allowed")
        }

        this.idInternal = id
        this.simulatorInternal = simulation
        this.proxyInternal = proxy
    }

    internal fun addConstraint(constraint: NodeConstraint<*, *>) {
        require(!constraintsInternal.contains(constraint)) {
            "Cannot add to the same constraint"
        }

        constraintsInternal.add(constraint)
    }

    internal open fun simulationDestroyed() {
        idInternal = -1
        simulatorInternal = null
        proxyInternal = null
        constraintsInternal.clear()
    }

    var angle = 0.0
    var angularVelocity = 0.0
    var externalTorque = 0.0

    var inertia: Double = 1.0
        set(value) {
            if(field != value) {
                if(allowOptimization) {
                    if(isInProxy) {
                        error("Changing inertia while optimized is not allowed")
                    }
                }

                field = value
            }
        }

    val kineticEnergy get() = 0.5 * inertia * (angularVelocity * angularVelocity)

    var previousAngle = 0.0

    fun setExternalAngle(angle: Double) {
        this.angle = angle
        this.previousAngle = angle
    }

    /**
     * Called when the simulation is being rebuilt (after the player placed a new device).
     * Angular velocity should be preserved, but angles should be set to 0.
     * */
    open fun resetForRestart() {
        angle = 0.0
        previousAngle = 0.0
    }

    //#region Quantity Getters
    val angleQuantity get() = Quantity(angle, RADIAN)
    val angularVelocityQuantity get() = Quantity(angularVelocity, RADIAN_PER_SECOND)
    val kineticEnergyQuantity get() = Quantity(kineticEnergy, JOULE)
    //#endregion
}

/**
 * Node that has some built-in friction.
 * */
abstract class FrictionKineticNode(allowOptimization: Boolean) : KineticNode(allowOptimization) {
    /**
     * Viscous friction(`𝜏 = -[viscousDamping] * ω`).
     * */
    var viscousDamping = 0.0
    /**
     * Sliding friction.
     * */
    var coulombFriction = 0.0
    /**
     * Threshold torque below which the node sticks.
     * */
    var staticFriction = 0.0
    /**
     * If |[angularVelocity]| is higher than this, then the node is considered to be moving.
     * */
    var velocityEps = 1e-6

    /**
     * Torque calculated by [calculateFrictionTorque].
     * */
    var frictionTorque = 0.0
    /**
     * Total heat from friction.
     * */
    var heatFromFriction = 0.0
    /**
     * Heat from friction generated this step.
     * */
    var deltaHeatFromFriction = 0.0

    /**
     * Calculates [frictionTorque] based on the parameters and the current angular velocity and external torque.
     * */
    fun calculateFrictionTorque() {
        val viscous = -viscousDamping * angularVelocity

        frictionTorque = if (abs(angularVelocity) > velocityEps) {
            // Node is moving: kinetic friction opposes velocity:
            -coulombFriction * sign(angularVelocity) + viscous
        } else {
            // Node is (nearly) stationary - consider static friction:
            if (abs(externalTorque) <= staticFriction) {
                // Static friction holds: cancel external torque:
                -externalTorque
            } else {
                // Static friction broken, friction opposes the direction of the driving torque:
                -coulombFriction * sign(externalTorque) + viscous
            }
        }
    }
}

class KineticSimulation(
    val dt: Double,
    val nodes: Array<KineticNode>,
    val optimizedShafts: Array<LineShaft>,
    val rigidConstraints: Array<RigidExtensionConstraint>,
    val clutchConstraints: Array<ClutchConstraint>
) {
    /**
     * Nodes which have built-in friction.
     * */
    val frictionNodes = nodes.mapNotNull { it as? FrictionKineticNode }.toTypedArray()

    /**
     * Baumgarte stabilization factor.
     * */
    var biasFactor = 0.1

    //#region Gauss-Seidel Options

    var minIterations = 4
    var maxIterations = 8192
    var maxIterationsFirstStep = maxIterations * 16
    var dLEps = 1e-3
    var residualEps = 1e-4

    /**
     * If true, the constraints will also be iterated in reverse order.
     * */
    var useSymmetricGaussSeidel = true

    /**
     * Adaptive successive relaxation max factor.
     * */
    var maxOmega = 2.0

    /**
     * When the sign of the step is consistent, the new relaxation factor becomes `omega * [omegaIncreaseFactor]`, at most [maxOmega].
     * */
    var omegaIncreaseFactor = 1.02

    /**
     * Adaptive successive relaxation min factor.
     * */
    var minOmega = 0.9

    /**
     * When the sign of the step is flipping, the new relaxation factor becomes `omega * [omegaDecreaseFactor]`, at least [minOmega].
     * */
    var omegaDecreaseFactor = 0.7

    /**
     * If the applied impulse is within [saturationLimit] of the max impulse, then the constraint is saturated (residual becomes 0).
     * */
    var saturationLimit = 1e-6

    //#endregion

    var lastIterationCount = 0
        private set

    var lastError: StepError = StepErrorImpl().also { it.begin() }
        private set

    /**
     * Packed storage for constraints during solve.
     * In the data:
     * - first long is the 2 indices of the nodes
     * - second long is the second jacobian entry
     * - third long is the bias
     * - fourth long is the relaxation coefficient (used for adaptive relaxation)
     * - fifth long is the delta lambda (used for adaptive relaxation)
     * */
    @Suppress("NOTHING_TO_INLINE") @JvmInline
    private value class ConstraintArray(val backingStorage: LongArray) {
        val count get() = backingStorage.size / 5

        constructor(constraintCount: Int) : this(LongArray(constraintCount * 5))

        init {
            for (i in 0 until count) {
                setOmega(i, 1.0)
            }
        }

        inline fun getIndexA(constraint: Int) = (backingStorage[constraint * 5] shr 32).toInt()
        inline fun getIndexB(constraint: Int) = (backingStorage[constraint * 5] and 0xFFFFFFFF).toInt()
        inline fun getJacobian(constraint: Int) = Double.fromBits(backingStorage[constraint * 5 + 1])
        inline fun getBias(constraint: Int) = Double.fromBits(backingStorage[constraint * 5 + 2])
        inline fun getOmega(constraint: Int) = Double.fromBits(backingStorage[constraint * 5 + 3])
        inline fun getDl(constraint: Int) = Double.fromBits(backingStorage[constraint * 5 + 4])

        inline fun setIndices(constraint: Int, indexA: Int, indexB: Int) { backingStorage[constraint * 5] = (indexA.toLong() shl 32) or indexB.toLong() }
        inline fun setJacobian(constraint: Int, jacobian: Double) { backingStorage[constraint * 5 + 1] = jacobian.toBits() }
        inline fun setBias(constraint: Int, bias: Double) { backingStorage[constraint * 5 + 2] = bias.toBits() }
        inline fun setOmega(constraint: Int, omega: Double) { backingStorage[constraint * 5 + 3] = omega.toBits() }
        inline fun setDl(constraint: Int, dL: Double) { backingStorage[constraint * 5 + 4] = dL.toBits() }
    }

    /**
     * Packed storage for lambda and max lambda during solve.
     * - first double is the lambda
     * - second double is the maxLambda
     * */
    @Suppress("NOTHING_TO_INLINE") @JvmInline
    private value class LambdaArray(val backingStorage: DoubleArray) {
        constructor(constraintCount: Int) : this(DoubleArray(constraintCount * 2))

        inline fun getLambda(constraint: Int) = backingStorage[constraint * 2]
        inline fun getMaxLambda(constraint: Int) = backingStorage[constraint * 2 + 1]

        inline fun setLambda(constraint: Int, lambda: Double) { backingStorage[constraint * 2] = lambda }
        inline fun setMaxLambda(constraint: Int, maxLambda: Double) { backingStorage[constraint * 2 + 1] = maxLambda }
    }

    var destroyed = false
        private set

    var firstStep = true
        private set

    private val omegaStar = DoubleArray(nodes.size)
    private val inverseI = DoubleArray(nodes.size)

    private val rigidConstraintData = ConstraintArray(rigidConstraints.size)
    private val rigidConstraintLambda = LambdaArray(rigidConstraints.size)
    private val clutchConstraintData = ConstraintArray(clutchConstraints.size)
    private val clutchConstraintLambda = LambdaArray(clutchConstraints.size)
    private val clutchCandidates = BooleanArray(clutchConstraints.size)

    init {
        // Assign (remaining, after optimization) constraints to simulation:
        rigidConstraints.forEachIndexed { index, constraint ->
            constraint.setSimulation(index)
        }

        clutchConstraints.forEachIndexed { index, constraint ->
            constraint.setSimulation(index)
        }

        // Assign nodes to simulation:
        var nodeIndex = 0
        nodes.forEach { node ->
            node.setSimulation(nodeIndex, this, null)
            nodeIndex++
        }

        // Set rigid constraint indices and max lambda:
        rigidConstraints.forEach { rigid ->
            rigidConstraintData.setIndices(rigid.id, rigid.a.node.idInOwner, rigid.b.node.idInOwner)
            rigidConstraintLambda.setMaxLambda(rigid.id, min(rigid.a.maxLambda, rigid.b.maxLambda))
        }

        // Set clutch indices:
        clutchConstraints.forEach { clutch ->
            clutchConstraintData.setIndices(clutch.id, clutch.a.idInOwner, clutch.b.idInOwner)
            // maxLambda is calculated at solve time
        }
    }

    /**
     * The total energy in rotating nodes, updated after [step].
     * */
    var kineticEnergy = 0.0
        private set

    private fun validateUsage() {
        if(destroyed) {
            error("Cannot use kinetic simulation after destroyed")
        }
    }

    private fun warmStart(constraints: ConstraintArray, lambdas: LambdaArray) {
        val omegaStar = this.omegaStar
        val inverseI = this.inverseI

        val count = constraints.count
        var constraintId = 0
        while (constraintId < count) {
            val nodeA = constraints.getIndexA(constraintId)
            val nodeB = constraints.getIndexB(constraintId)
            val jacobian = constraints.getJacobian(constraintId)
            val lambda = lambdas.getLambda(constraintId)

            omegaStar[nodeA] += inverseI[nodeA] * lambda
            omegaStar[nodeB] += inverseI[nodeB] * jacobian * lambda

            constraintId++
        }
    }

    /**
     * Holds the max (absolute) displacement and max (absolute) residual from the last iteration.
     * If all constraints were skipped, the value is [Double.NEGATIVE_INFINITY].
     * */
    interface StepError {
        val maxDeltaL : Double
        val maxResidual: Double
    }

    private class StepErrorImpl : StepError {
        override var maxDeltaL = Double.NaN
        override var maxResidual = Double.NaN

        fun begin() {
            maxDeltaL = Double.NEGATIVE_INFINITY
            maxResidual = Double.NEGATIVE_INFINITY
        }

        @Suppress("NOTHING_TO_INLINE")
        inline fun apply(deltaL: Double, residual: Double) {
            val deltaLAbs = abs(deltaL)
            val residualAbs = abs(residual)

            if(deltaLAbs > maxDeltaL) {
                maxDeltaL = deltaLAbs
            }

            if(residualAbs > maxResidual) {
                maxResidual = residualAbs
            }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun projectedGaussSeidelConstraint(constraintId: Int, constraints: ConstraintArray, lambdas: LambdaArray, error: StepErrorImpl) {
        val nodeA = constraints.getIndexA(constraintId)
        val nodeB = constraints.getIndexB(constraintId)
        val jacobian = constraints.getJacobian(constraintId)
        val bias = constraints.getBias(constraintId)
        val omega = constraints.getOmega(constraintId)
        val prevDl = constraints.getDl(constraintId)

        val lambda = lambdas.getLambda(constraintId)
        val lambdaMax = lambdas.getMaxLambda(constraintId)

        val inverseIA = inverseI[nodeA]
        val inverseIB = inverseI[nodeB]

        // Relative velocity:
        val dv = omegaStar[nodeA] + jacobian * omegaStar[nodeB]

        // Effective mass:
        val k = inverseIA + inverseIB * (jacobian * jacobian)

        // Solve for new lambda:
        val lambdaStar = (lambda - (dv + bias) / k)
        val lambdaNew = ((1.0 - omega) * lambda + omega * lambdaStar).coerceIn(-lambdaMax, lambdaMax)

        lambdas.setLambda(constraintId, lambdaNew)

        val dL = lambdaNew - lambda
        omegaStar[nodeA] += inverseIA * dL
        omegaStar[nodeB] += inverseIB * jacobian * dL

        // Adaptive relaxation:
        constraints.setOmega(constraintId,
            if (dL * prevDl < 0.0) {
                // Sign flip. Reduce omega:
                max(minOmega, omega * omegaDecreaseFactor)
            }
            else {
                // Steady. Increase omega:
                min(maxOmega, omega * omegaIncreaseFactor)
            }
        )

        constraints.setDl(constraintId, dL)

        val residual = if(!(abs(lambdaNew).approxEq(lambdaMax, saturationLimit))) {
            dv + bias
        }
        else {
            0.0 // Saturated
        }

        error.apply(dL, residual)

        // Can also calculate the energy error from the residuals, it was interesting to see it decrease during iterations.
    }

    /**
     * Executes one PGS iteration for the given constraints and writes the error into [error].
     * @param reversed If true, the constraints will be iterated in reverse order.
     * */
    private fun projectedGaussSeidelIteration(constraints: ConstraintArray, lambdas: LambdaArray, error: StepErrorImpl, reversed: Boolean) {
        // Tested unrolling, the cost is insignificant so it's not worth it.

        if(reversed) {
            var constraintId = constraints.count - 1
            while (constraintId > -1) {
                projectedGaussSeidelConstraint(constraintId, constraints, lambdas, error)
                constraintId--
            }
        }
        else {
            var constraintId = 0
            while (constraintId < constraints.count) {
                projectedGaussSeidelConstraint(constraintId, constraints, lambdas, error)
                constraintId++
            }
        }
    }

    /**
     * Steps the simulation. The [lastIterationCount] and [lastError] are updated.
     * */
    fun step()  {
        validateUsage()

        optimizedShafts.forEach { shaft ->
            shaft.prepareForStep()
        }

        // Applies viscous friction torque for unlocked clutches:
        clutchConstraints.forEach { clutch ->
            val p = clutch.prototype

            if (!p.locked && p.pressure > 0.0) {
                val jacobian = clutchConstraintData.getJacobian(clutch.id)

                val a = clutch.a
                val b = clutch.b
                val tau = -p.viscousFrictionCoefficient * p.pressure * (a.angularVelocity + jacobian * b.angularVelocity)
                p.frictionTorque = tau
                a.externalTorque += tau
                b.externalTorque -= tau
            }
            else {
                p.frictionTorque = 0.0
            }
        }

        // Applies friction to individual nodes:
        frictionNodes.forEach { node ->
            node.calculateFrictionTorque()
            node.externalTorque += node.frictionTorque
        }

        // Stores the old angle, updates the inverse inertia and writes the unconstrained (predicted) velocities based on external torque:
        nodes.forEach { node ->
            node.previousAngle = node.angle
            val invI = 1.0 / node.inertia
            inverseI[node.idInOwner] = invI
            omegaStar[node.idInOwner] = node.angularVelocity + invI * node.externalTorque * dt
            node.externalTorque = 0.0
        }

        // Sets up rigid constraints.
        // Only calculates the jacobian and the bias. The max lambda is constant.
        rigidConstraints.forEach { rigid ->
            val a = rigid.a
            val b = rigid.b

            // See the direction of connection. If it is [-1] ---- [1], preserve relationship. Otherwise, flip.

            // Electrical circuit analogy:
            // 1 when "positive" connected to "negative", and -1 when "positive" connected to "positive" or "negative" connected to "negative"
            val sign = -a.pole * b.pole

            val j = -b.ratio / a.ratio * sign
            val posError = a.ratio * a.node.angle - b.ratio * b.node.angle * sign
            val bias = biasFactor / dt * (posError / a.ratio)

            rigidConstraintData.setJacobian(rigid.id, j)
            rigidConstraintData.setBias(rigid.id, bias)
        }

        // Sets up clutch constraints.
        // Calculates the jacobian and bias, the initial guess lambda and the max lambda and finds clutches which are candidates for locking:
        clutchConstraints.forEach { clutch ->
            val a = clutch.a
            val b = clutch.b
            val p = clutch.prototype

            val nodeA = a.idInOwner
            val nodeB = b.idInOwner

            val j = -1.0

            val dTheta = a.angle - b.angle
            val dv = omegaStar[nodeA] + j * omegaStar[nodeB]

            val k = inverseI[nodeA] + inverseI[nodeB] * (j * j)

            val lambdaLimit = p.slipTorqueLimit * p.pressure * dt

            val lambdaNeededOmega = if (abs(k) < SYMFORCE_EPS) 0.0 else -dv / k

            val engageDv = p.dvLock * p.pressure
            val canLockVel = abs(lambdaNeededOmega) <= lambdaLimit + SYMFORCE_EPS && abs(dv) <= engageDv + SYMFORCE_EPS

            val candidateLocked = when {
                p.locked -> true
                p.pressure <= 0.0 -> false
                else -> canLockVel
            }

            clutchCandidates[clutch.id] = candidateLocked

            // If we are about to become candidate-locked (and previously unlocked), set the reference angle,
            // so positional bias is zero for this timestep (no instantaneous snap).
            if (candidateLocked && !p.locked) {
                p.referenceAngle = dTheta
            }

            val posErrorForSolver = if (p.locked) {
                val raw = dTheta - p.referenceAngle
                (raw + PI) % (2.0 * PI) - PI
            } else {
                0.0
            }

            val rawBias = if (posErrorForSolver == 0.0) 0.0 else biasFactor / dt * posErrorForSolver

            val maxBias = if (lambdaLimit > 0.0 && k > SYMFORCE_EPS) k * lambdaLimit else Double.POSITIVE_INFINITY
            val bias = if (maxBias.isFinite()) rawBias.coerceIn(-maxBias, maxBias) else rawBias

            clutchConstraintData.setJacobian(clutch.id, j)
            clutchConstraintData.setBias(clutch.id, bias)

            if (candidateLocked) {
                if (!p.locked) {
                    clutchConstraintLambda.setLambda(clutch.id, lambdaNeededOmega)
                }

                clutchConstraintLambda.setMaxLambda(clutch.id, lambdaLimit)
            } else {
                clutchConstraintLambda.setLambda(clutch.id, 0.0)
                clutchConstraintLambda.setMaxLambda(clutch.id, 0.0)
            }
        }

        // Applies warm start with previous impulses:
        warmStart(rigidConstraintData, rigidConstraintLambda)
        warmStart(clutchConstraintData, clutchConstraintLambda)

        lastIterationCount = 0
        val error = StepErrorImpl()

        val maxIterations = if(firstStep) maxIterationsFirstStep else maxIterations

        var forward = false
        for(iteration in 1..maxIterations) {
            error.begin()

            if(forward) {
                // Forward pass:
                projectedGaussSeidelIteration(rigidConstraintData, rigidConstraintLambda, error, false)
                projectedGaussSeidelIteration(clutchConstraintData, clutchConstraintLambda, error, false)
            }
            else {
                // Backward pass:
                projectedGaussSeidelIteration(clutchConstraintData, clutchConstraintLambda, error, true)
                projectedGaussSeidelIteration(rigidConstraintData, rigidConstraintLambda, error, true)
            }

            if(useSymmetricGaussSeidel) {
                forward = !forward
            }

            lastIterationCount = iteration

            if(iteration >= minIterations) {
                // Exit condition:
                if(error.maxDeltaL < dLEps && error.maxResidual < residualEps) {
                    break
                }
            }
        }

        kineticEnergy = 0.0

        // Integrate for angle and copy back:
        nodes.forEach { node ->
            node.angularVelocity = omegaStar[node.idInOwner]
            node.angle += node.angularVelocity * dt
            kineticEnergy += node.kineticEnergy
        }

        // Calculate clutch heating:
        clutchConstraints.forEach { clutch ->
            val p = clutch.prototype
            val a = p.a
            val b = p.b

            val lambda = clutchConstraintLambda.getLambda(clutch.id)
            val jacobian = clutchConstraintData.getJacobian(clutch.id)

            val dThetaA = a.angle - a.previousAngle
            val dThetaB = b.angle - b.previousAngle

            // Work done by the constraint on the nodes:
            val workConstraint = if (dt == 0.0) 0.0 else (lambda / dt) * (dThetaA + jacobian * dThetaB)

            val tau = p.frictionTorque
            val workFriction = tau * dThetaA + (-tau) * dThetaB  // equals tau * (dThetaA - dThetaB)

            // Heat contribution is the mechanical energy removed from the system by the clutch/friction.
            // Constraint heat is not strictly physical, it includes small bias errors and integration errors.
            val heatFromConstraint = if (workConstraint < 0.0) -workConstraint else 0.0
            val heatFromFriction = if (workFriction < 0.0) -workFriction else 0.0

            val totalHeat = heatFromConstraint + heatFromFriction

            p.heat += totalHeat
            p.deltaHeat = totalHeat
        }

        // Copies lambda back to the rigid:
        rigidConstraints.forEach { rigid ->
            rigid.lambda = rigidConstraintLambda.getLambda(rigid.id)
        }

        // Copies lambda back to the clutch prototype and potentially locks/unlocks:
        clutchConstraints.forEach { clutch ->
            val nodeA = clutch.a.idInOwner
            val nodeB = clutch.b.idInOwner
            val p = clutch.prototype

            p.lambda = clutchConstraintLambda.getLambda(clutch.id)

            val lambdaLimit = p.slipTorqueLimit * p.pressure * dt

            // Computes dv after solve to decide velocity unlock:
            val j = clutchConstraintData.getJacobian(clutch.id)
            val dvAfter = omegaStar[nodeA] + j * omegaStar[nodeB]

            val saturated = (p.pressure > 0.0) && (abs(p.lambda) >= (lambdaLimit - SYMFORCE_EPS))
            val velocityUnlock = abs(dvAfter) > (p.dvUnlock * p.pressure) + SYMFORCE_EPS

            if (p.locked && (saturated || velocityUnlock)) {
                // Slipping or too fast. Unlock for next timestep:
                p.locked = false
                clutchConstraintLambda.setLambda(clutch.id, 0.0)
                clutchConstraintLambda.setMaxLambda(clutch.id, 0.0)
            }
            else if (!p.locked && clutchCandidates[clutch.id]) {
                // If we started candidate-locked and final lambda is inside limit, accept the lock:
                if (p.pressure > 0.0 && abs(p.lambda) <= lambdaLimit + SYMFORCE_EPS) {
                    p.locked = true
                }
            }
        }

        // Distribute angles and velocities:
        optimizedShafts.forEach { shaft ->
            shaft.distributeResults()
        }

        // Calculate friction heat for individual nodes:
        frictionNodes.forEach { node ->
            val dTheta = node.angle - node.previousAngle
            val workByFriction = node.frictionTorque * dTheta
            val heatFromNodeFriction = if (workByFriction < 0.0) -workByFriction else 0.0
            node.heatFromFriction += heatFromNodeFriction
            node.deltaHeatFromFriction = heatFromNodeFriction
        }

        // Calculate friction heat for internal shafts inside each optimized LineShaft:
        optimizedShafts.forEach { line ->
            line.lineGraph.forEach { shaft ->
                val dTheta = shaft.angle - shaft.previousAngle
                val workByFriction = shaft.frictionTorque * dTheta
                val heatFromNodeFriction = if (workByFriction < 0.0) -workByFriction else 0.0
                shaft.heatFromFriction += heatFromNodeFriction
                shaft.deltaHeatFromFriction = heatFromNodeFriction
            }
        }

        firstStep = false
        lastError = error
    }

    fun destroy() {
        validateUsage()

        nodes.forEach { node ->
            node.simulationDestroyed()
        }

        destroyed = true
    }
}