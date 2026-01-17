package org.ageseries.libage.sim.kinetic

/**
 * Constraint between two [KineticNode]s. Instanced at network build time and thrown away (this class is only a wrapper that adds the [id] used for indexing).
 * The real state is stored in `Prototype`s, which are kept during the lifetime of the simulation object, just like the nodes.
 * */
abstract class NodeConstraint<A : KineticNode, B: KineticNode>(val a: A, val b: B) {
    private var idInternal = -1

    val id get() = if(idInternal == -1) error("Cannot get ID before constraint added to simulation") else idInternal
    val isInSimulation get() = idInternal != -1

    init {
        a.addConstraint(this)
        b.addConstraint(this)
    }

    internal open fun setSimulation(id: Int) {
        if(isInSimulation) {
            error("Tried to set simulator on one-use constraint")
        }

        this.idInternal = id
    }

    abstract val impulseA: Double
    abstract val impulseB: Double
}

/**
 * State of a clutch constraint.
 * Note that the [pressure] is used to scale the parameters used in the following explanation.
 *
 * When [locked]:
 *  - the clutch is not producing heat through friction, and the two nodes are rigidly constrained.
 *  - if the torque transmitted by the rigid clutch exceeds [slipTorqueLimit] or the velocity difference between the (**rigidly**) locked nodes is larger than [dvUnlock], the clutch unlocks.
 *
 * When not [locked]:
 *  - the clutch is coupling the two nodes through friction ([viscousFrictionCoefficient]), and it is producing [heat]. This torque is stored in [frictionTorque].
 *  - if the torque needed to equalize the velocities is less than [slipTorqueLimit] * [slipTorqueLimitLockFactor], and the velocity difference is less than [dvLock], the clutch attempts to lock.
 *  - if, after solve, all the needed conditions are met, the clutch is locked at the current angle difference.
 *
 *  The implementation is just an approximation. Multiple solve steps would be needed in critical moments if the behavior needed to be tick-perfect, which would be more expensive.
 * */
class ClutchPrototype(val a: KineticNode, val b: KineticNode) {
    /**
     * The torque that can be transmitted at `[pressure] = 1.0`
     * */
    var slipTorqueLimit = 100.0

    /**
     * Scales [slipTorqueLimit] when deciding if the clutch can equalize the velocity difference.
     * */
    var slipTorqueLimitLockFactor = 0.9

    /**
     * Friction at `[pressure] = 1`.
     * */
    var viscousFrictionCoefficient = 10.0

    /**
     * The friction torque calculated by the solver.
     * Only happens when the clutch is not locked.
     * */
    var frictionTorque = 0.0

    /**
     * How pressed the clutch is. `0` means completely free.
     * */
    var pressure = 1.0

    /**
     * The velocity difference threshold for locking.
     * */
    var dvLock = 3.0

    /**
     * The velocity difference threshold for unlocking.
     * */
    var dvUnlock = 0.1

    var lambda = 0.0

    /**
     * Set by the solver. If false, it means that the clutch is grinding, and it's producing [heat] by friction.
     * If true, the clutch acts as a rigid constraint, and [heat] changes are ~0 (some solver error is included too so [heat] changes are not exactly 0).
     * */
    var locked = false

    /**
     * When locked, represents the angle difference between the two nodes.
     * */
    var referenceAngle = 0.0

    /**
     * Total heat produced by the clutch.
     * */
    var heat = 0.0

    /**
     * Heat produced this tick.
     * */
    var deltaHeat = 0.0
}

class ClutchConstraint(val prototype: ClutchPrototype) : NodeConstraint<KineticNode, KineticNode>(prototype.a, prototype.b) {
    override val impulseA: Double
        get() = prototype.lambda

    override val impulseB: Double
        get() = -prototype.lambda // j = -1
}

/**
 * Constraint "between" two [KineticExtension]s of a certain type.
 * The extensions are sorted by type, so only `ExtensionConstraint<LesserPriority, HigherPriority>` and `ExtensionConstraint<SameType, SameType>` need to be implemented.
 * Unlike [NodeConstraint] prototypes, these constraints are generated at network build time and are thrown away.
 * */
abstract class ExtensionConstraint<A : KineticExtension, B : KineticExtension>(val a: A, val b: B) {
    init {
        a.addConstraint(this)
        b.addConstraint(this)
    }

    private var idInternal = -1
    val id get() = if(idInternal == -1) error("Cannot get extension constraint ID before added to simulation") else idInternal

    internal fun setSimulation(newId: Int) {
        require(idInternal == -1) {
            "Cannot re-add extension constraint"
        }

        idInternal = newId
    }

    /**
     * Gets the impulse applied to [a].
     * */
    abstract val impulseA: Double

    /**
     * Gets the impulse applied to [b].
     * */
    abstract val impulseB: Double

    /**
     * The impulse set by the solver.
     * */
    var lambda = 0.0
}

/**
 * Rigid constraint that applies a gear ratio as well.
 * */
class RigidExtensionConstraint(a: RigidKineticExtension, b: RigidKineticExtension) : ExtensionConstraint<RigidKineticExtension, RigidKineticExtension>(a, b) {
    override val impulseA get() = lambda
    override val impulseB get() = lambda * (-b.ratio / a.ratio)

    fun getOther(x: RigidKineticExtension) : RigidKineticExtension {
        if(x === a) {
            return b
        }

        if(x === b) {
            return a
        }

        error("Cannot get other $x of ($a, $b)")
    }
}

/**
 * An extension is a "semi-constraint" exported by a node. This is made to fit with the object architecture, where each object "exports" some connection to neighbor objects.
 * This lives throughout the lifetime of the [KineticNode] (it is instanced and immutable in the node).
 * Constraints are formed "between" two [KineticExtension]s. The extensions are analyzed and a fitting constraint is created between the nodes, based on the type of both extensions.
 * Example: for a shaft, you have a "left" extension, and a "right" extension. If you build a line of shafts, the right extension of the first shaft is joined with the left extension of the second one; the right extension of the second shaft with the left extension of the third one, and so on.
 * */
abstract class KineticExtension(val node: KineticNode, val pole: Int) {
    /**
     * Priority, used for sorting, used for creating the [ExtensionConstraint].
     * This is done so, if you have say extension of type `A` and extension of type `B`, you need only implement `ExtensionConstraint<A, B>` and not `ExtensionConstraint<B, A>` too.
     * */
    abstract val priority: Int

    private val constraintsInternal = ArrayList<ExtensionConstraint<*, *>>()

    /**
     * Gets the actual constraints that were generated which include this extension.
     * */
    val constraints: List<ExtensionConstraint<*, *>> get() = constraintsInternal

    /**
     * Gets the total impulse applied to this extension from the [constraints].
     * */
    val impulse: Double get() {
        var result = 0.0

        constraints.forEach { constraint ->
            result += if(this == constraint.a) {
                constraint.impulseA
            }
            else {
                constraint.impulseB
            }
        }

        return result
    }

    internal fun addConstraint(constraint: ExtensionConstraint<*, *>) {
        require(!constraintsInternal.contains(constraint)) {
            "Cannot add to the same constraint"
        }

        constraintsInternal.add(constraint)
    }

    internal fun removeConstraint(constraint: ExtensionConstraint<*, *>) {
        require(constraintsInternal.contains(constraint)) {
            "Tried to remove constraint which wasn't added $constraint"
        }

        constraintsInternal.remove(constraint)
    }

    internal fun simulationDestroyed() {
        constraintsInternal.clear()
    }
}

/**
 * Extension that, when combined with another [RigidKineticExtension], creates a [RigidExtensionConstraint].
 * @param maxLambda The max solver impulse. Setting a bound is useful if destruction is needed (prevents a big jolt being transmitted before the node is destroyed).
 * */
class RigidKineticExtension(node: KineticNode, pole: Int, var maxLambda: Double = Double.POSITIVE_INFINITY) : KineticExtension(node, pole) {
    override val priority: Int
        get() = 0

    var ratio: Double = 1.0
        set(value) {
            if(field != value) {
                if(node.isInProxy) {
                    error("Setting ratio while optimized is not allowed")
                }

                field = value
            }
        }
}