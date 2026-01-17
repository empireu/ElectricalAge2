package org.ageseries.libage.sim.electrical

import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.SECOND
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.sim.electrical.ElectricalComponent.Companion.ID_GENERATOR
import org.ageseries.libage.sim.electrical.ElectricalNode.Companion.GROUND_ID
import org.ageseries.libage.sim.electrical.ElectricalNode.Companion.VIRTUAL_ID
import org.ageseries.libage.sim.electrical.ElectricalPin.Companion.ID_GENERATOR
import org.ageseries.libage.utils.measureDuration
import org.ejml.data.DMatrixRMaj
import org.ejml.data.DMatrixSparseCSC
import org.ejml.dense.row.CommonOps_DDRM
import org.ejml.dense.row.factory.LinearSolverFactory_DDRM
import org.ejml.interfaces.linsol.LinearSolverDense
import org.ejml.interfaces.linsol.LinearSolverSparse
import org.ejml.sparse.FillReducing
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Representation for an electrical pin. This allows the downstream code to deal with localized pins of components, instead of nodes.
 * The pins that are connected together form a `Pin Star`, which will be converted into an electrical node.
 * */
class ElectricalPin(val component: ElectricalComponent, val symbol: String) {
    companion object {
        private val ID_GENERATOR = AtomicInteger()
    }

    /**
     * Gets the friendly [toString] ID (see [ID_GENERATOR]).
     * */
    val displayId = ID_GENERATOR.getAndIncrement()

    private var nodeInternal: ElectricalNode? = null
    val node get() = if(component.isInSimulation) nodeInternal!! else error("Cannot get node of pin from $component. Not added to simulation!")

    /**
     * Sets the generated electrical node. It can be a real node in the simulation, the ground node, or a virtual node from the optimizer.
     * */
    internal fun setNode(node: ElectricalNode) {
        check(nodeInternal == null) {
            "Tried to set new node for pin $this, but previous simulation wasn't destroyed!"
        }

        nodeInternal = node
    }

    /**
     * Gets the potential of the node.
     * */
    val potential: Double get() {
        val node = nodeInternal
            ?: return 0.0

        return node.potential
    }

    fun simulationDestroyed() {
        nodeInternal = null
    }

    override fun toString() = "Pin$displayId$symbol$component"
}

/**
 * Data holder and stamping rule for electrical components.
 * The pin configuration must be specified (see [Port], which is the most common and probably only useful configuration).
 * */
abstract class ElectricalComponent {
    companion object {
        /**
         * Friendly number generated for each component, used by [toString].
         * Instead of showing `Resistor` + some hash code, we show `Resistor0`, `Resistor1`, `PotentialSource0`, and so on.
         * */
        private val ID_GENERATOR = ConcurrentHashMap<Class<*>, AtomicInteger>()
    }

    /**
     * Gets the friendly [toString] ID (see [ID_GENERATOR]).
     * */
    val displayId = ID_GENERATOR
        .computeIfAbsent(this.javaClass) { AtomicInteger() }
        .getAndIncrement()

    private var simulationInternal: ElectricalSimulation? = null
    val simulation get() = simulationInternal ?: error("Cannot get simulation of component $this. Not added to simulation!")
    val isInSimulation get() = simulationInternal != null

    /**
     * Used when component values are updated.
     * If true, it means the component was stamped with its previous values in [stamp].
     * If that's the case, then the previous contribution must be updated.
     * If false, then nothing needs to be done for the simulation.
     * */
    val isStamped get() = isInSimulation && simulation.constructed

    /**
     * Stamps the structure of this component, and the initial knowns vector entry.
     * This is called only once.
     * */
    internal open fun stamp() { }

    /**
     * Called before the system is solved, in order to update the companion model.
     * */
    internal open fun prepareStep() { }

    /**
     * Called after the system is solved and the results have been copied into the wrapper classes.
     * */
    internal open fun finishStep() { }

    internal open fun setSimulation(simulation: ElectricalSimulation) {
        simulationInternal = simulation
    }

    internal open fun simulationDestroyed() {
        simulationInternal = null
    }

    override fun toString() = "${this.javaClass.simpleName}$displayId"

    /**
     * List of all pins in the component.
     * */
    abstract val allPins: List<ElectricalPin>
}

/**
 * The repository for [ElectricalReadoutRepositoryLayer].
 * */
interface ReadoutRepository {
    /**
     * Reads and stores the values from the [ElectricalComponent].
     * */
    fun loadValues()
}

/**
 * [ElectricalComponent] with a double-buffer functionality.
 * We may read some values from the game thread, e.g. for display.
 * But it is very common to catch values during nonlinear iteration and other things.
 * Repositories offer logic to form a complete picture and swap it atomically after each complete step.
 * */
interface ReadoutElectricalComponent<Self, Repository>
        where Self : ReadoutElectricalComponent<Self, Repository>, Self : ElectricalComponent,
              Repository : ReadoutRepository
{
    /**
     * Gets the double-buffering layer for the readouts.
     * */
    val repositoryLayer: ElectricalReadoutRepositoryLayer<Repository>

    /**
     * Gets the data readouts for this component.
     * */
    val readouts: Repository get() = repositoryLayer.presentedCopy
}

/**
 * Double-buffer for readout repositories. [ReadoutElectricalComponent.swap] will swap the references betwen them.
 * */
class ElectricalReadoutRepositoryLayer<Repository : ReadoutRepository>(factory: () -> Repository) {
    var presentedCopy = factory()
        private set

    private var unpresentedCopy = factory()

    /**
     * Used to load the initial values of the component, before the simulation starts running.
     * */
    internal fun loadPresentation() {
        presentedCopy.loadValues()
    }

    /**
     * Loads the new values into [unpresentedCopy], and swaps the two copies.
     * */
    internal fun loadAndSwap() {
        unpresentedCopy.loadValues()
        val temp = presentedCopy
        presentedCopy = unpresentedCopy
        unpresentedCopy = temp
    }
}

/**
 * A node is a set of connected pins. It can contain 1, 2, or more [ElectricalPin]s.
 * When [id] is [GROUND_ID], then this node is a ground. When it is [VIRTUAL_ID], then it is a virtual node for optimized components.
 * */
class ElectricalNode(val id: Int, val pins: Array<ElectricalPin>) {
    companion object {
        const val GROUND_ID = -1
        const val VIRTUAL_ID = -2
    }

    val isGround get() = id == GROUND_ID
    val isVirtual get() = id == VIRTUAL_ID

    /**
     * Set by the solver or by the optimized system.
     * */
    var potential = 0.0
        internal set
}

/**
 * A port is a component with two terminals, conventionally named [positive] and [negative].
 * The name is from circuit theory: a pair of two terminals obeying the port condition: the currents flowing into the two nodes must be equal and opposite.
 * */
abstract class Port : ElectricalComponent() {
    val positive = ElectricalPin(this, Pole.Positive.symbol)
    val negative = ElectricalPin(this, Pole.Negative.symbol)

    override val allPins = listOf(positive, negative)

    /**
     * Gets the potential across the device.
     * */
    open val potential: Double get() = positive.potential - negative.potential

    override fun simulationDestroyed() {
        super.simulationDestroyed()

        positive.simulationDestroyed()
        negative.simulationDestroyed()
    }

    /**
     * Gets the pin corresponding to the [pole].
     * */
    fun getPin(pole: Pole) = when(pole) {
        Pole.Positive -> positive
        Pole.Negative -> negative
    }

    enum class Pole(val symbol: String) {
        Positive("+"),
        Negative("-");

        val opposite get() = when(this) {
            Positive -> Negative
            Negative -> Positive
        }
    }
}

/**
 * Used to model components with a companion model consisting of a conductance in parallel with a current source.
 * */
abstract class NortonSystem : Port() {
    /**
     * Gets the equivalent resistance of the Norton system.
     * @param value The characteristic value.
     * */
    protected open fun getNortonResistance(value: Double) : Double = value

    /**
     * Stamps the equivalent conductance of the Norton system.
     * */
    override fun stamp() {
        simulation.system.stampResistance(
            positive.node,
            negative.node,
            getNortonResistance(componentValue)
        )
    }

    /**
     * The component's characteristic. Causes a change in the conductance.
     * A property that is named accordingly must be created, that just defers to this property.
     *
     * P.S. The current validation only checks for weird values or 0.
     * This isn't enough; since the equivalent resistance is proportional to this value, then some limits should be imposed to make sure the solver doesn't fail (just like [Resistor] has limits).
     * I don't have time to figure out these values right now, so validation will be left as-is for now.
     * */
    protected var componentValue: Double = 1.0
        set(value) {
            // Accept positive, except NaN and infinity:
            if(field != value) {
                if(value < 0.0) {
                    error("Invalid negative component value $value!")
                }

                if(value.isNaN() || value.isInfinite()) {
                    error("Invalid component value $value!")
                }

                if(isStamped) {
                    simulation.system.changeResistance(
                        positive.node,
                        negative.node,
                        getNortonResistance(field),
                        getNortonResistance(value)
                    )
                }

                field = value
            }
        }

    /**
     * Changes the current injected by the Norton system.
     * */
    var nortonCurrent: Double = 0.0
        internal set(value) {
            if(value.isNaN() || value.isInfinite()) {
                error("Invalid Norton current $value")
            }

            if(field != value) {
                if(isStamped) {
                    simulation.system.changeCurrentKnown(
                        positive.node,
                        negative.node,
                        field,
                        value
                    )
                }

                field = value
            }
        }

    /**
     * The actual current over the device.
     * */
    val current: Double get() = if(isInSimulation) {
        potential / getNortonResistance(componentValue) - nortonCurrent
    }
    else {
        0.0
    }

    /**
     * Gets the current Norton conductance.
     * */
    val nortonConductance get() = 1.0 / getNortonResistance(componentValue)

    /**
     * Resets the Norton current.
     * Not doing so creates an invalid state in the new simulation.
     * */
    override fun simulationDestroyed() {
        super.simulationDestroyed()

        nortonCurrent = 0.0
    }
}

/**
 * Electrical circuit simulation with an immutable component set and an immutable connection set.
 *
 * Inductors and Capacitors are approximated by a Norton system, with Backward Euler integration.
 * That was a purposeful decision because we don't simulate AC or circuits, so the extra stability and damping are good.
 * Other special components (the power devices) are also implemented as Norton systems with constant conductance.
 * This is done because it's the cheapest way to model a dynamic system, which plays well into our performance philosophy.
 *
 * ## [PowerConsumer]:
 * The [PowerConsumer]s are nonlinear components that do not fit into the usual MNA components.
 * They are coupled electrically, so they must be solved simultaneously. Their constraints are both in target power, but also in effective dissipation.
 * The load is modeled with a Norton system with a constant conductance and variable current (that opposes the circuit, to simulate a load).
 * The unknown Norton currents are found using successive over-relaxation with two algorithms that can be chosen, which starts at a guess for the currents and refines it in a stable manner over time.
 * The exact solution isn't found in non-trivial circuits, but the solution gets better over time.
 * The solver is cheap, considerably cheaper and more stable than the [PowerSource] solver. It works because we don't need fine control over the input of energy into our buffer.
 * We can accept small overshoots, as long as the solution is stable.
 *
 * ## [PowerSource]:
 * The [PowerSource]s are nonlinear components that do not fit into the usual MNA components.
 * They are coupled electrically, so they must be solved simultaneously. They are also well-constrained by the potential constraint.
 * This creates a nonlinear system of equations with a unique solution that must be solved. This is done after the consumers are solved.
 * The system is solved using a *Quasi-Newton method (Broyden's Method)*, with a line search.
 * The system size is `NxN`, where `N` is the number of sources. So it can get expensive with many [PowerSource]s.
 * For a step, an initial Jacobian is computed analytically (in most cases), and then updated based on further residual evaluations. The system is solved with a dense LU decomposition.
 * The result is the unknown Norton currents to apply to the sources so their potential constraint is satisfied and the target power is reached, if possible.
 * The [PowerSource]s are intended as precise energy transfer devices (the upper bound on the energy released in a step is controlled precisely).
 * This is useful for implementing advanced devices such as the DC-DC converter's output side, which draws from a small energy buffer and responds abruptly to demand changes.
 *
 * ## [PowerConsumer]-[PowerSource] coupling:
 * The two devices are solved separately. In simplest terms, one's operating point is held constant while the other one's operating point is found.
 * First, the [PowerConsumer]s are solved. Then, the [PowerSource]s are solved. The circuit will reach an equilibrium state over multiple timesteps (if applicable).
 *
 * @param components Real components (that will be included in the system). Components that were optimized away don't show up here.
 * @param pinForest The pin forest. Used to construct the nodes.
 * */
@Suppress("NOTHING_TO_INLINE")
class ElectricalSimulation(
    val dt: Double,
    val components: Array<ElectricalComponent>,
    val constructionOptions: ConstructionOptions,
    pinForest: Map<ElectricalCircuitCompiler.PinDisjointSet, Set<ElectricalPin>>
) {
    companion object {
        // Values chosen by feeling (they usually don't disrupt the solvers):
        const val MIN_RESISTANCE = 1e-6
        const val MAX_RESISTANCE = 1e9

        /**
         * If true, the simulation may become much slower, but extra safeguards are enabled to detect some implementation errors.
         * */
        var USE_VALIDATION = false
    }

    var destroyed = false
        private set

    /**
     * If true, components need to start applying deltas when their values change (they have been stamped).
     * */
    var constructed = false
        private set

    //#region Solvers

    /**
     * Utility class for creating and manipulating the MNA matrices.
     * Has methods for stamping conductance, potential sources, and known currents and potentials.
     * */
    class System(val simulation: ElectricalSimulation) {
        /**
         * The size of the system matrix ([matrixSize] × [matrixSize]).
         * It is the *number of nodes* + *number of potential sources*.
         * */
        val matrixSize = simulation.nodes.size + simulation.potentialSources.size
        /**
         * The system matrix. Once all the components are stamped, its structure is considered locked.
         * */
        val matrix = DMatrixSparseCSC(matrixSize, matrixSize)
        /**
         * The knowns vector. Implemented as a dense matrix.
         * */
        val known = DMatrixRMaj(matrixSize, 1)
        /**
         * The unknowns vector. Implemented as a dense matrix.
         * */
        val unknown = DMatrixRMaj(matrixSize, 1)

        /**
         * The sparse solver for the system.
         * */
        val solver: LinearSolverSparse<DMatrixSparseCSC, DMatrixRMaj> = LinearSolverFactory_DSCC.lu(FillReducing.NONE)

        /**
         * In debug mode, it is used to ensure the structure of the matrix isn't mutated during simulation.
         * */
        private class Validator(val stamper: System) {
            val matrixEntries = HashSet<Pair<Int, Int>>()

            inline fun validateMatrix(row: Int, col: Int) {
                val isNew = matrixEntries.add(Pair(row, col))

                if(stamper.constructionMode) {
                    return
                }

                if(isNew) {
                    error("Mutated matrix structure after init!")
                }
            }

            inline fun requireConstruction() {
                if(!stamper.constructionMode) {
                    error("Cannot perform this action outside of system construction!")
                }
            }
        }

        /**
         * If true, the structure is allowed to mutate.
         * */
        var constructionMode = true
            private set

        private var validator = if(USE_VALIDATION) {
            Validator(this)
        }
        else {
            null
        }

        fun endConstruction() {
            constructionMode = false
        }

        /**
         * Increases the value in the specified cell at [row], [col] in the system [matrix] by [value].
         * */
        private inline fun incrementInMatrix(row: Int, col: Int, value: Double) {
            validator?.validateMatrix(row, col)
            val previous = matrix.get(row, col)
            matrix.set(row, col, previous + value)
        }

        /**
         * Increases the value in the specified [row] in the [known] vector by [value].
         * */
        private inline fun incrementInVector(row: Int, value: Double) {
            val previous = known.get(row)
            known.set(row, 0, previous + value)
        }

        /**
         * Stamps a conductance between the two nodes. Legal to call if and only if, during construction, a call for the same nodes was done so the entries were created.
         * */
        fun stampResistance(nodeP: ElectricalNode, nodeN: ElectricalNode, resistance: Double) {
            val conductance = 1.0 / resistance

            val groundNode = simulation.groundNode

            // Build diagonal:
            if(nodeP != groundNode) {
                incrementInMatrix(nodeP.id, nodeP.id, conductance)
            }

            if(nodeN != groundNode) {
                incrementInMatrix(nodeN.id, nodeN.id, conductance)
            }

            // Build off-diagonal:
            if(nodeP != groundNode && nodeN != groundNode) {
                incrementInMatrix(nodeP.id, nodeN.id, -conductance)
                incrementInMatrix(nodeN.id, nodeP.id, -conductance)
            }

            simulation.setMatrixChanged()
        }

        /**
         * Changes a conductance between the two nodes.
         * Legal to call if and only if, during construction, a [stampResistance] call for the same nodes was done so the entries were created.
         * Evidently, the [previous] should have been contributed previously, or this call will result in undefined behavior.
         * */
        fun changeResistance(nodeP: ElectricalNode, nodeN: ElectricalNode, previous: Double, new: Double) {
            /**
             * We do not apply the delta update here.
             * The difference between [previous] and [new] can be very small, so when we compute the conductance of the delta, we can have issues.
             * */
            stampResistance(nodeP, nodeN, -previous)
            stampResistance(nodeP, nodeN, new)
        }

        /**
         * Stamps the topological information for a potential source. **Only legal to call during construction.**
         * */
        fun stampVoltageStructure(i: Int, nodeP: ElectricalNode, nodeN: ElectricalNode) {
            validator?.requireConstruction()

            val column = simulation.nodes.size + i
            val groundNode = simulation.groundNode

            if(nodeP != groundNode) {
                incrementInMatrix(nodeP.id, column, 1.0)
                incrementInMatrix(column, nodeP.id, 1.0)
            }

            if(nodeN != groundNode) {
                incrementInMatrix(nodeN.id, column, -1.0)
                incrementInMatrix(column, nodeN.id, -1.0)
            }

            simulation.setMatrixChanged()
        }

        /**
         * Increments the cell [i] in the potentials vector. Legal to call anywhere.
         * */
        fun stampVoltageKnown(i: Int, potential: Double) {
            incrementInVector(simulation.nodes.size + i, potential)
            simulation.setKnownsChanged()
        }

        /**
         * Changes a potential in the cell [i] from [previous] to [new]. Legal to call anywhere.
         * */
        fun changeVoltageKnown(i: Int, previous: Double, new: Double) {
            stampVoltageKnown(i, new - previous)
        }

        /**
         * Adds a current flowing from [nodeN] to [nodeP]. Legal to call anywhere.
         * */
        fun stampCurrentKnown(nodeP: ElectricalNode, nodeN: ElectricalNode, current: Double) {
            val groundNode = simulation.groundNode

            if(nodeP != groundNode) {
                incrementInVector(nodeP.id, current)
            }

            if(nodeN != groundNode) {
                incrementInVector(nodeN.id, -current)
            }

            simulation.setKnownsChanged()
        }

        /**
         * Changes a current flowing from [nodeN] to [nodeP] from [previous] to [new]. Legal to call anywhere.
         * */
        fun changeCurrentKnown(nodeP: ElectricalNode, nodeN: ElectricalNode, previous: Double, new: Double) {
            stampCurrentKnown(nodeP, nodeN, new - previous)
        }
    }

    /**
     * Determines how much the solution vector changes when an input parameter is perturbed.
     *
     * Solves for the partial derivative of an internal state variable (node potential V and source current Iv, which together form the solutions for our MNA) with respect to an external control variable.
     * Can be used to analyze the circuit using Thévenin's theorem (I know Grissess loves that), among other things.
     * */
    class SensitivityAnalysis(val simulation: ElectricalSimulation) {
        private val rhs = DMatrixRMaj(simulation.system.matrixSize, 1)
        val results = DMatrixRMaj(simulation.system.matrixSize, 1)

        fun solve(nodeP: ElectricalNode?, nodeN: ElectricalNode?) {
            rhs.zero()

            if (nodeP != simulation.groundNode) {
                rhs.set(nodeP!!.id, 0, 1.0)
            }

            if (nodeN != simulation.groundNode) {
                rhs.set(nodeN!!.id, 0, -1.0)
            }

            // Solve G × (dx/dINk) = (db/dINk) for (db/dINk)
            simulation.system.solver.solve(rhs, results)
        }
    }

    /**
     * Nonlinear Gauss-Seidel relaxation solver for the power consumers, which may converge over multiple outer-iteration steps.
     * This is used as a one-step predictor, relaxed with the previous unknown current. For more information about how this is used, see [solvePowerDevices].
     * */
    class PowerConsumerSolver(val simulation: ElectricalSimulation) {
        @Suppress("UnnecessaryVariable")
        inline fun decompositionPredictor(device: PowerConsumer) : Double {
            val (vTh, rTh) = simulation.computeTheveninForNortonSystem(device)
            val rN = device.characteristicResistance
            val targetPower = device.targetPower
            val minR = device.minEquivalentResistance

            if (vTh <= 0.0) {
                val vOp = vTh
                val iLoad = 0.0
                return (vOp / rN) - iLoad
            }

            if (rTh.isInfinite()) {
                val vOp = vTh
                val iLoad = 0.0
                return (vOp / rN) - iLoad
            }

            val pLimited: Double
            val vOp: Double
            val iLoad: Double

            if (rTh.approxEq(0.0)) {
                vOp = vTh
                val pLimitMinR = (vOp * vOp) / minR
                pLimited = min(targetPower, pLimitMinR)

                iLoad = if (vOp.approxEq(0.0)) 0.0 else pLimited / vOp

            } else {
                val maxPowerTransfer = (vTh * vTh) / (4.0 * rTh)
                val pLimitedTemp = min(targetPower, maxPowerTransfer)
                val delta = (vTh * vTh) - 4.0 * pLimitedTemp * rTh

                vOp = if (delta < 0.0) {
                    vTh / 2.0
                } else {
                    (vTh + sqrt(delta)) / 2.0
                }

                val pLimitMinR = (vOp * vOp) / minR
                pLimited = min(pLimitedTemp, pLimitMinR)

                iLoad = if (vOp.approxEq(0.0)) 0.0 else pLimited / vOp
            }

            val finalILoad = if (iLoad.isNaN() || iLoad.isInfinite()) 0.0 else iLoad

            return (vOp / rN) - finalILoad
        }

        /**
         * Refines the power consumer currents, with a single displacement step.
         * */
        fun grissessSeidelStep(overRelaxation: Double) : Double {
            var maxDelta = 0.0

            for (i in 0 until simulation.powerConsumers.size) {
                val device = simulation.powerConsumers[i]
                val predictor = (1.0 - overRelaxation) * device.nortonCurrent + overRelaxation * decompositionPredictor(device)
                val delta = abs(predictor - device.nortonCurrent)
                device.nortonCurrent = predictor

                if(delta > maxDelta) {
                    maxDelta = delta
                }

                simulation.solveWithNewKnowns() // Gauss-Seidel
            }

            // Jacobi iteration is not stable (from testing). Circuit never reaches a stable state but it can reach a "somewhat close" state.

            return maxDelta
        }
    }

    /**
     * Quasi-Newton solver for the power sources, using the Rank-1 updates for the inverse Jacobian.
     * This solver was chosen because the changes in the outer loop are usually small enough that we can successfully carry the inverse Jacobian over many steps.
     * This amortizes the cost of the full matrix-invert and gives us even more performance.
     * */
    @Suppress("PrivatePropertyName", "PropertyName")
    class PowerSourceSystemInverseBroyden(val simulation: ElectricalSimulation) {
        private val size = simulation.powerSources.size
        /**
         * The (approximate) inverse Jacobian.
         * */
        internal val H = DMatrixRMaj(size, size)
        /**
         * Temporary matrix to hold the actual (non-inverted) Jacobian before it's inverted into H.
         */
        private val J = DMatrixRMaj(size, size)
        /**
         * Temporary row vector to hold s^T * H
         */
        private val sT_H = DMatrixRMaj(1, size)
        /**
         * The residual:
         * */
        internal val F = DMatrixRMaj(size, 1)
        /**
         * RHS for the Newton solve (`-`[F]):
         * */
        private val MinusF = DMatrixRMaj(size, 1)
        /**
         * Residuals after solve and apply new unknowns:
         * */
        private val FNew = DMatrixRMaj(size, 1)
        /**
         * Step produced by Newton:
         * */
        private val delta = DMatrixRMaj(size, 1)
        private val solver: LinearSolverDense<DMatrixRMaj> = LinearSolverFactory_DDRM.lu(size)

        // Temporary storage for finite difference fallback:
        private val f0 = DMatrixRMaj(size, 1)
        private val f1 = DMatrixRMaj(size, 1)
        private val x1 = DoubleArray(size)

        // Temporary storage for the step:
        private val x = DoubleArray(size)
        private val xNew = DoubleArray(size)
        private val yMinusJs = DMatrixRMaj(size, 1)
        private val Js = DMatrixRMaj(size, 1)

        // Line search options:
        var lineSearchC = 1e-4
        var lineSearchReduction = 0.5
        var lineSearchMaxSteps = 10

        var lastSystemEvaluations = 0
            private set

        var lastMaxResidual = 0.0
            private set

        var lastMaxDelta = 0.0
            private set

        var lastForceJacobianResets = 0
            private set

        /**
         * Gets the initial guess for the Norton current.
         * Check the method to see why *it isn't a good idea* we just use the currents from the source.
         * */
        fun initialGuess(device: PowerSource) : Double {
            /**
             * Start at the currents from the last timestep (or 0, if this is the first):
             * */
            var initialCurrent = device.nortonCurrent

            /**
             * If the current is ~0, and we're not already solved, we are in a trap where the differential is ~0.
             * This probably happened because the source is in open circuit.
             * We will provide an initial guess that doesn't result in a singular system.
             * */
            if (initialCurrent.approxEq(0.0, 1e-7)) {
                val targetPower = device.targetPower

                if (targetPower > 0.0) {
                    // Guess a V in the penalty region to get a non-zero derivative.
                    val vGuess = device.maxPotential * 1.01 + 0.1
                    initialCurrent = vGuess / device.characteristicResistance
                }
            }

            return initialCurrent
        }

        /**
         * Gets the residual for [device].
         * The residual takes into account the desired power, but instead becomes a penalty when the potential constraint is violated.
         * */
        fun getResidualForDevice(device: PowerSource) : Double {
            val targetPower = device.targetPower
            val pActual = device.power
            val v = device.potential

            if(targetPower < 1e-12) {
                return -pActual // Open-circuit behavior
            }

            /**
             * The constraint applies when `V` is positive.
             * If `V` is negative, then the circuit is "over-powering" us.
             * */
            if (v < 0.0) {
                return targetPower - pActual
            }

            val vMax = device.maxPotential

            if (v > vMax) {
                /**
                 * Violated the potential constraint.
                 * Applies a penalty proportional to the violation.
                 * */
                return -(v - vMax) - pActual
            }

            val blendStart = vMax * device.blendRegion

            if (v <= blendStart) {
                /**
                 * Constant-power region.
                 * */
                return targetPower - pActual
            }

            /**
             * Blending region. We are approaching the potential constraint.
             * We scale down the power request so the potential we drive is lower, so we satisfy the potential constraint.
             * K goes from 1, at the blend start, to 0, at the max potential.
             * */
            val k = (1.0 - (v - blendStart) / (vMax - blendStart)).coerceIn(0.0, 1.0)

            return (k * targetPower) - pActual
        }

        /**
         * Evaluates residual by setting the Norton currents and solving the system.
         * @param x The Norton currents to apply.
         * @param out The result vector.
         * */
        fun evaluateResidual(x: DoubleArray, out: DMatrixRMaj) : Double {
            ++lastSystemEvaluations

            /**
             * Updates the RHS:
             * */
            for (i in 0 until size) {
                simulation.powerSources[i].nortonCurrent = x[i]
            }

            // Solve circuit for new power:
            simulation.solveWithNewKnowns()

            var maxResidual = 0.0

            simulation.powerSources.forEachIndexed { i, device ->
                val powerResidual = getResidualForDevice(device)

                out.set(i, powerResidual)

                val magnitude = abs(powerResidual)
                if (magnitude > maxResidual) {
                    maxResidual = magnitude
                }
            }

            return maxResidual
        }

        /**
         * Evaluates the Jacobian using forward differences (N + 1 solves).
         * It uses a very simple adaptive step size.
         * This is the last fallback. If the solve fails here, we will throw.
         * It also inverts the computed approximation.
         * */
        private fun computeJacobianAndInverseFallback() {
            J.zero()
            x.copyInto(x1)
            evaluateResidual(x, f0)

            val factor = 1e-2
            val currentScale = 1.0 // A
            val maxStep = 10.0 // A

            for (j in 0 until size) {
                val dI = min(maxStep, factor * max(abs(0.01 * x1[j]), currentScale))

                x1[j] += dI
                evaluateResidual(x1, f1)
                x1[j] -= dI

                for (i in 0 until size) {
                    val differential = (f1.get(i) - f0.get(i)) / dI
                    J.set(i, j, differential) // Compute into tempJ
                }
            }

            try {
                if (!solver.setA(J)) {
                    throw PowerSourceSolverException(simulation, PowerSourceSolverFailurePoint.CouldNotDetermineStep)
                }

                solver.invert(H)
            }
            catch (e: Exception) {
                throw PowerSourceSolverException(simulation, PowerSourceSolverFailurePoint.CouldNotDetermineStep)
            }
        }

        /**
         * Computes the Jacobian analyically.
         * It can be degenerate, so we use [computeJacobianAndInverseFallback] if it happens.
         * It also inverts the computed approximation.
         * */
        @Suppress("LocalVariableName", "SpellCheckingInspection", "NonAsciiCharacters")
        private fun computeJacobianAndInverse() {
            J.zero()

            val size = simulation.powerSources.size
            val sensitivity = simulation.sensitivity.results

            var normSqr = 0.0

            // k is the column index (d/dINₖ)
            for (k in 0 until size) {
                val deviceₖ = simulation.powerSources[k]

                ++lastSystemEvaluations
                simulation.sensitivity.solve(
                    deviceₖ.positive.node,
                    deviceₖ.negative.node
                )

                /**
                 * The partial potential derivative (dVᵢ/dINₖ) is derived from the sensitivity.
                 */
                for (i in 0 until size) {
                    val deviceᵢ = simulation.powerSources[i]
                    val Vᵢ = deviceᵢ.potential

                    val positiveNodeᵢ = deviceᵢ.positive.node
                    val negativeNodeᵢ = deviceᵢ.negative.node

                    val s1 = if(positiveNodeᵢ.isGround) 0.0 else sensitivity[positiveNodeᵢ.id]
                    val s2 = if(negativeNodeᵢ.isGround) 0.0 else sensitivity[negativeNodeᵢ.id]

                    // dVᵢ/dINₖ: How potential of source `i` changes with respect to Norton current of source `k`
                    val dVᵢ_dINₖ = s1 - s2

                    val INᵢ = deviceᵢ.nortonCurrent
                    val RNᵢ = deviceᵢ.characteristicResistance // Constant

                    // Term 1: Calculate d(P_actual,i) / d(INₖ)
                    var dPa_dINₖ = (INᵢ - 2.0 * Vᵢ / RNᵢ) * dVᵢ_dINₖ
                    if (i == k) {
                        dPa_dINₖ += Vᵢ
                    }

                    // Term 2: Calculate d(Ptargetᵢ) / d(INₖ)
                    var dPt_dVᵢ = 0.0

                    val desiredPowerᵢ = deviceᵢ.targetPower

                    if(desiredPowerᵢ > 1e-12) {
                        val vMax = deviceᵢ.maxPotential
                        val vBlend = vMax * deviceᵢ.blendRegion

                        if (Vᵢ < 0.0) {
                            dPt_dVᵢ = 0.0
                        }
                        else if (Vᵢ > vMax) {
                            dPt_dVᵢ = -1.0
                        }
                        else if (Vᵢ > vBlend) {
                            val dV_dVb = vMax - vBlend

                            if (dV_dVb > 1e-9) {
                                dPt_dVᵢ = -desiredPowerᵢ / dV_dVb
                            }
                        }
                    }

                    val dPt_dINₖ = dPt_dVᵢ * dVᵢ_dINₖ
                    val Jᵢₖ = dPt_dINₖ - dPa_dINₖ
                    normSqr += Jᵢₖ * Jᵢₖ

                    J.set(i, k, Jᵢₖ)
                }
            }

            if(normSqr.isNaN() || normSqr.isInfinite() || normSqr.approxEq(0.0, 1e-3)) {
                // Seems dangerous.
                computeJacobianAndInverseFallback() // Also inverts
                return
            }

            try {
                if (!solver.setA(J)) {
                    // Decomposition failed, fallback
                    computeJacobianAndInverseFallback() // Also inverts
                    return
                }

                solver.invert(H)
            } catch (e: Exception) {
                // Inversion failed, fallback
                computeJacobianAndInverseFallback() // Also inverts
            }
        }

        /**
         * Checks if any values inside the [delta] are infinite or NaN.
         * This doesn't mean it is strictly valid; numerical errors could have driven it to bad (very large) values.
         * */
        private fun isDeltaValid() = !delta.data.any { it.isInfinite() || it.isNaN() }

        /**
         * Solve for delta using the inverse Jacobian.
         * The return value will only check if the entries are NaN or infinity.
         * */
        private fun solveForDelta() : Boolean {
            CommonOps_DDRM.mult(H, MinusF, delta)

            return isDeltaValid()
        }

        /**
         * Solves the nonlinear system for the Norton currents that satisfy the devices.
         * Throws a [PowerSourceSolverException] with a [PowerSourceSolverFailurePoint] when no solution was found.
         * @return The number of iterations.
         * */
        @Suppress("LocalVariableName")
        fun solve(needsInitialJacobian: Boolean) : Int {
            lastSystemEvaluations = 0

            /**
             * Loads the initial guess Norton currents.
             * The initial guess is equal to the current value if the current value is not ~zero.
             * */
            for (i in 0 until size) {
                x[i] = initialGuess(simulation.powerSources[i])
            }

            lastMaxResidual = evaluateResidual(x, F)

            if (lastMaxResidual <= simulation.powerSourceResidualTolerance) {
                lastMaxDelta = 0.0 // No currents were adjusted
                return 0 // Already in a good state.
            }

            var iterations = 0

            /**
             * Set in the loop to mark that the Jacobian needs to be recomputed (done at the start of the loop).
             * */
            var recomputeJacobian = needsInitialJacobian

            /**
             * The number of times the jacobian has been re-used.
             * Used for periodic recompute (restart).
             * This is done because the Jacobian from Broyden might be causing slow convergence.
             * */
            var jacobianReuseCount = 0
            lastForceJacobianResets = 0

            fun requestJacobianReset() {
                lastForceJacobianResets++
                recomputeJacobian = true
            }

            while (iterations < simulation.powerSourceMaxIterations) {
                iterations++

                if(recomputeJacobian) {
                    computeJacobianAndInverse()
                    recomputeJacobian = false
                    jacobianReuseCount = 0
                }
                else {
                    jacobianReuseCount++
                    if(jacobianReuseCount % simulation.powerSourceJacobianRecomputeInterval == 0) {
                        // Convergence might be too slow:
                        computeJacobianAndInverse()
                        jacobianReuseCount = 0
                    }
                }

                /**
                 * The (square) norm of the residual at this timestep.
                 * Used for the line search later on, to compare this previous residual with the one obtained after the update.
                 * */
                var initialResidualNormSqr = 0.0

                // Negate F and calculate the residual's norm:
                for (i in 0 until size){
                    val value = F.get(i)
                    initialResidualNormSqr += value * value
                    MinusF.set(i, -value)
                }

                if(!solveForDelta()) {
                    // Discard and recompute:
                    computeJacobianAndInverse()
                    jacobianReuseCount = 0

                    // Try the solve once more with the fresh H:
                    if (!solveForDelta()) {
                        // If the new H also fails, the inputs are bad.
                        throw PowerSourceSolverException(simulation, PowerSourceSolverFailurePoint.CouldNotDetermineStep)
                    }
                }

                // Line search:
                var alpha = 1.0 // Step size
                var maxNewResidual = 0.0

                // Residual before applying the new delta:
                val previousResidual = lastMaxResidual

                for (step in 0 until lineSearchMaxSteps) {
                    // New currents, by applying the dampened δ:
                    for (i in 0 until size) {
                        xNew[i] = x[i] + alpha * delta.get(i)
                    }

                    // P.S. this changes the residual used by Broyden, I also change the delta after this loop.
                    maxNewResidual = evaluateResidual(xNew, FNew)

                    // Calculate new norm:
                    var newNorm = 0.0
                    for (i in 0 until size) {
                        val value = FNew.get(i)
                        newNorm += value * value
                    }

                    // Armijo–Goldstein condition:
                    if (newNorm <= (1.0 - lineSearchC * alpha) * initialResidualNormSqr) {
                        break
                    }

                    // Reduce the step size:
                    alpha *= lineSearchReduction
                }

                lastMaxResidual = maxNewResidual

                // Apply alpha to the delta for Broyden:
                for (i in 0 until size) {
                    delta[i] *= alpha
                }

                // If the line search didn't find a value that satisfied its exit condition, then our Jacobian might be bad.
                // We will recompute it below based on the new residual and the previous one we stored.
                var deltaMax = 0.0
                var deltaNorm = 0.0 // Used for Broyden below.

                for (i in 0 until size) {
                    val dx = delta.get(i) // Doesn't apply alpha:
                    deltaNorm += dx * dx

                    val magnitude = abs(dx)
                    if (magnitude > deltaMax){
                        deltaMax = magnitude
                    }
                }

                lastMaxDelta = deltaMax

                /**
                 * Convergence check.
                 * The residual check is solid, but the delta check is a bit suspicious.
                 * It might be worth it to build a counter that measures how many consecutive steps resulted in the delta being low,
                 * and only then apply the criterion.
                 * */
                if (maxNewResidual <= simulation.powerSourceResidualTolerance || deltaMax <= simulation.powerSourceDeltaTolerance) {
                    // Accept xNew and copy into devices:
                    for (i in 0 until size) {
                        simulation.powerSources[i].nortonCurrent = xNew[i]
                    }

                    return iterations
                }

                if(lastMaxResidual >= previousResidual) {
                    // The residual increased or convergence is stalling.
                    // The Broyden loop is likely diverging.
                    // Forces a recompute:
                    requestJacobianReset()
                }

                /**
                 * Updates the Jacobian (Quasi-Newton) if we are not going to recompute it exactly on the next iteration:
                 * */
                if(!recomputeJacobian && ((jacobianReuseCount + 1) % simulation.powerSourceJacobianRecomputeInterval != 0)) {
                    // Broyden's method to update the Jacobian:

                    /**
                     * As the king of solvers, Broyden's Method, faced the Short-Circuited [PowerSource], Broyden asked the generator: "Are you convergent because your delta is small, or is your delta small because you are convergent?"
                     * The PowerSource simply set its targetPower to 10000W.
                     * Broyden began opening his domain: "Malevolent Jacobian!" He analytically computed the derivatives, cleaving the residual function into a linear system to solve for the step delta.
                     * However, the [PowerSource]'s potential V was clamped to 0.0 by the short.
                     * The derivative d(Power_Actual) / d(I_Norton) was zero. The Jacobian was singular.
                     * As the solver tried to divide -targetPower by zero, the [PowerSource] simply stated: "Stand proud, you are strong. But with V=0... Nah, I'd win."
                     * Broyden's LU factorization crumbled as the delta vector exploded to Infinity. In its dying moment, the [PowerSourceSystem] uttered the phrase: "With this treasure I summon... on circuits with player-made shorts, always bet on R_Series!"
                     * The [PowerSourceSolverException] was trivially caught. And those who pioneered the techniques of MNA, the one who formalized the companion model, they would all bear witness to the bare flesh of the one who is free.
                     * To the one who left it all behind and his overwhelming stability!
                     * */
                    if (deltaNorm > 1e-9) {
                        // Update H (stored in J) using the "good" Broyden formula derived from the J update via Sherman-Morrison:

                        for (i in 0 until size) {
                            yMinusJs.set(i, FNew.get(i) - F.get(i))
                        }

                        CommonOps_DDRM.mult(H, yMinusJs, Js)

                        val sT_Hy = CommonOps_DDRM.dot(delta, Js)

                        if (abs(sT_Hy) > 1e-9) {
                            for (i in 0 until size) {
                                yMinusJs.set(i, delta.get(i) - Js.get(i))
                            }

                            CommonOps_DDRM.multTransA(delta, H, sT_H)

                            val recip = 1.0 / sT_Hy
                            for (i in 0 until size) {
                                val ui = yMinusJs.get(i)
                                for (j in 0 until size) {
                                    val vTj = sT_H.get(j)
                                    H.add(i, j, recip * ui * vTj)
                                }
                            }
                        }
                        else {
                            requestJacobianReset()
                        }
                    }
                    else {
                        // The norm is too small. The update will be unstable.
                        requestJacobianReset()
                    }
                }

                for (i in 0 until size) {
                    x[i] = xNew[i]
                }

                for (i in 0 until size) {
                    F.set(i, 0, FNew.get(i, 0))
                }
            }

            // Did not converge. GG.
            throw PowerSourceSolverException(simulation, PowerSourceSolverFailurePoint.DidNotConverge)
        }
    }

    //#endregion

    /**
     * Options for hardening the simulation further.
     * @param spiceImplicitResistanceTrick A large resistance value. This resistance is stamped between every node and the ground node, to stabilize the system
     * */
    data class ConstructionOptions(val spiceImplicitResistanceTrick: Double?)

    /**
     * The ground node (also called the *reference node*). Has an ID of [ElectricalNode.GROUND_ID] and contains all the pins which are grounded.
     * **Always** contains some pins. This is ensured by the circuit compiler.
     * The node isn't included in [nodes].
     * */
    val groundNode: ElectricalNode

    /**
     * All created nodes. Doesn't include the [groundNode].
     * */
    val nodes: Array<ElectricalNode>

    /**
     * The MNA system. See [System] for more information.
     * */
    val system: System

    /**
     * The components which double-buffer their readouts for display.
     * */
    val repositoryComponents = components.mapNotNull { it as? ReadoutElectricalComponent<*, *> }.toTypedArray()

    /**
     * The potential sources, included in [components].
     * These sources contribute additional equations that solve for an unknown current.
     * */
    val potentialSources: Array<PotentialSource>

    /**
     * The power consumers, included in [components].
     * These consumers are solved using a cheaper, approximate solver (the [PowerConsumerSolver]).
     * */
    val powerConsumers: Array<PowerConsumer>

    /**
     * The power sources, included in [components].
     * These sources introduce nonlinear equations and are handled specially by the [PowerSourceSystem].
     * */
    val powerSources: Array<PowerSource>

    /**
     * MNA Sensitivity solver.
     * */
    val sensitivity: SensitivityAnalysis

    /**
     * The nonlinear solver for power consumers, used internally.
     * `Null` if no consumers exist.
     * */
    val consumerSolver: PowerConsumerSolver?

    /**
     * The nonlinear system and solver for power sources, used internally.
     * `Null` if no power sources exist.
     * */
    val sourceSystem: PowerSourceSystemInverseBroyden?

    init {
        val potentialSources = ArrayList<PotentialSource>()
        val powerConsumers = ArrayList<PowerConsumer>()
        val powerSources = ArrayList<PowerSource>()

        components.forEach {
            it.setSimulation(this)

            when (it) {
                is PotentialSource -> {
                    potentialSources.add(it)
                }

                is PowerConsumer -> {
                    powerConsumers.add(it)
                }

                is PowerSource -> {
                    powerSources.add(it)
                }
            }
        }

        this.potentialSources = potentialSources.toTypedArray()
        this.powerConsumers = powerConsumers.toTypedArray()
        this.powerSources = powerSources.toTypedArray()

        potentialSources.forEachIndexed { idx, source ->
            source.setPotentialIndex(idx)
        }

        powerSources.forEachIndexed { idx, device ->
            device.setPowerSourceIndex(idx)
        }

        val groundedPins = ArrayList<ElectricalPin>()
        val nonGroundedForest = ArrayList<Set<ElectricalPin>>()

        pinForest.forEach { (disjointSet, pins) ->
            if(disjointSet.representative.grounded) {
                groundedPins.addAll(pins)
            }
            else {
                nonGroundedForest.add(pins)
            }
        }

        check(groundedPins.isNotEmpty()) {
            "Circuit is floating. A node should be grounded implicitly!"
        }

        groundNode = ElectricalNode(GROUND_ID, groundedPins.toTypedArray())

        groundedPins.forEach { pin ->
            pin.setNode(groundNode)
        }

        nodes = Array<ElectricalNode>(nonGroundedForest.size) { id ->
            ElectricalNode(id, nonGroundedForest[id].toTypedArray())
        }

        nodes.forEach { node ->
            node.pins.forEach { pin ->
                pin.setNode(node)
            }
        }

        system = System(this)
        sensitivity = SensitivityAnalysis(this)
        consumerSolver = if(powerConsumers.isNotEmpty()) PowerConsumerSolver(this) else null
        sourceSystem = if(powerSources.isNotEmpty()) PowerSourceSystemInverseBroyden(this) else null

        repositoryComponents.forEach {
            it.repositoryLayer.loadPresentation()
        }
    }

    private fun validateUsage() {
        if(destroyed) {
            error("Cannot use electrical simulation after destroyed")
        }
    }

    var matrixChanged = false
        private set

    var knownsChanged = false
        private set

    internal fun setMatrixChanged() {
        matrixChanged = true
    }

    internal fun setKnownsChanged() {
        knownsChanged = true
    }

    /**
     * Copies MNA results into the various classes.
     * Can throw [InvalidLinearResultsException].
     * */
    private fun copyResults() {
        /**
         * Copies potential into the nodes:
         * */
        for (i in 0 until nodes.size) {
            val result = system.unknown[i]
            val node = nodes[i]

            if(result.isNaN() || result.isInfinite()) {
                throw InvalidLinearResultsException(this, node)
            }

            node.potential = result
        }

        /**
         * Copies current into the potential sources:
         * */
        for (i in 0 until potentialSources.size) {
            val result = -system.unknown[nodes.size + i]
            val source = potentialSources[i]

            if(result.isNaN() || result.isInfinite()) {
                throw InvalidLinearResultsException(this, source)
            }

            source.current = result
        }
    }

    /**
     * Solves the circuit with an updated right-hand side.
     * It is illegal to call if the matrix isn't factored.
     * */
    private fun solveWithNewKnowns() {
        check(!matrixChanged) {
            "Cannot solve with new RHS if the matrix changed!"
        }

        if(knownsChanged) {
            system.solver.solve(system.known, system.unknown)
            copyResults()
            knownsChanged = false
        }
    }

    //#region Power Device Solver Options

    /**
     * Coupling solver iteration count (in normal mode. See [powerDeviceMaxOuterLoopIterationsConsumerViolation]).
     * */
    var powerDeviceMaxOuterLoopIterations = 64

    /**
     * Coupling solver iteration count when some power consumers are generating power.
     * This count should never ever be hit. We are being generous.
     * */
    var powerDeviceMaxOuterLoopIterationsConsumerViolation = 256

    /**
     * If two successive solves yield a maximum delta (for both the consumers and the sources) less than [powerDeviceOuterLoopTolerance], the outer loop exits early.
     * */
    var powerDeviceOuterLoopTolerance = 1e-5

    /**
     * The last number of iterations taken by the solver.
     * */
    var lastOuterLoopIterations = 0
        private set

    /**
     * The approximate total time spent by the power consumer solver in the last outer solve.
     * Most of these values should be accurate (GC usually shouldn't be happening during the solver loop, but it could).
     * */
    var lastPowerConsumerTime = Quantity(0.0, SECOND)
        private set

    /**
     * Max iterations the nonlinear solver for the power sources can take.
     * If the state changed a lot since the last step, can take 1-5 iterations in the first or first few steps of the outer loop, and takes 1-2 iterations in the remaining steps of the outer loop.
     * It takes 0 steps (one residual evaluation, and instant exit) when in steady-state conditions.
     * */
    var powerSourceMaxIterations = 256

    /**
     * How many times the solver is allowed to apply the Broyden update to the same Jacobian.
     * This recompute is done because it's possible the Broyden update is calculating a low-quality Jacobian that is slowing down convergence.
     * */
    var powerSourceJacobianRecomputeInterval = 16

    /**
     * The max allowed power residual for the power sources.
     * This value **an** exit condition **for the power source solver**. The outer loop might apply more iterations to the problem.
     * */
    var powerSourceResidualTolerance = 1e-4

    /**
     * Exit if the largest current delta is less than this tolerance for the power sources.
     * This value **an** exit condition **for the power source solver**. The outer loop might apply more iterations to the problem.
     * */
    var powerSourceDeltaTolerance = 1e-4

    /**
     * The number of iterations the nonlinear solver needed in the last coupled update (the sum of all the iterations taken in the outer loops).
     * */
    var lastPowerSourceIterationCount = 0
        private set

    var lastPowerSourceForceJacobianResets = 0
        private set

    /**
     * The (absolute) max residual from the nonlinear solver in the last step of the last outer loop iteration. Not the norm of the residual vector.
     * */
    val lastPowerSourceMaxResidual get() = sourceSystem?.lastMaxResidual ?: 0.0

    /**
     * The approximate total time spent by the power source solver in the last outer solve.
     * Most of these values should be accurate (GC usually shouldn't be happening during the solver loop, but it could).
     * */
    var lastPowerSourceTime = Quantity(0.0, SECOND)
        private set

    //#endregion

    /**
     * Solves the power devices using Block-Solves. This algorithm is not a standard one. It was devised for the requirements of the game.
     * Each iteration does the following:
     *  - The [PowerConsumer]s are refined using **a single step of** Nonlinear Gauss-Seidel.
     *  - The over-relaxation factor for the power consumers is calculated (reduced when the power consumers oscillate, increased when they seem stable).
     *  - The [PowerSource]s are solved "exactly" using their Quasi-Newton solver.
     *
     * The end result is an algorithm which brings the system to some state that approximates the ideal solution for the power consumers, but is exact for the power sources.
     * If the system is not in flux, the solution for the power consumers will converge to the exact solution in multiple time steps (unless it was found in a single step).
     * */
    private fun solvePowerDevices() {
        var powerConsumerTime = Quantity(0.0, SECOND)
        var powerSourceTime = Quantity(0.0, SECOND)
        var powerSourceIterations = 0
        var powerSourceForceJacobianResets = 0

        var lastConsumerDelta = Double.MAX_VALUE
        var gaussSeidelOverRelaxation = 1.0

        /**
         * Helps with more robust convergence detection.
         *
         * If, after an iteration, there are no violations and the maximum Norton current delta is less than threshold, this flag is set.
         * If, on the next iteration, the same conditions are met, then the solver stops. Otherwise, the flag is reset.
         * */
        var convergenceFlag = false
        var iterations = 0

        while (true) {
            iterations++

            var consumerDelta = 0.0
            var sourceDelta = 0.0

            if(powerConsumers.isNotEmpty()) {
                powerConsumerTime += measureDuration {
                    consumerDelta = consumerSolver!!.grissessSeidelStep(gaussSeidelOverRelaxation)
                }

                gaussSeidelOverRelaxation = if (consumerDelta < lastConsumerDelta) {
                    // Increase descent rate:
                    min(gaussSeidelOverRelaxation * 1.05, 1.0)
                } else {
                    // Oscillating. Start dampening:
                    max(gaussSeidelOverRelaxation * 0.75, 0.05)
                }

                lastConsumerDelta = consumerDelta
            }

            if (powerSources.isNotEmpty()) {
                powerSourceTime += measureDuration {
                    val system = sourceSystem!!
                    powerSourceIterations += system.solve(iterations == 1)
                    sourceDelta = system.lastMaxDelta
                    powerSourceForceJacobianResets += system.lastForceJacobianResets
                }
            }

            /**
             * The largest change in Norton current this iteration.
             * */
            val delta = max(consumerDelta, sourceDelta)

            /**
             * If true, there are some constraint violations.
             * Because the power source solver is exact, and it is the last one that ran, then the only violations we can have are from consumers.
             * */
            val hasViolations = powerConsumers.any { it.power < 0.0 }

            if(!hasViolations && delta < powerDeviceOuterLoopTolerance) {
                if(convergenceFlag) {
                    break
                }

                convergenceFlag = true
            }
            else {
                convergenceFlag = false
            }

            /**
             * Increases the max iteration count if there are violations.
             * Normally this wouldn't be needed, but I want it for extra handling.
             * */
            val maxIterations = if(hasViolations) {
                powerDeviceMaxOuterLoopIterationsConsumerViolation
            }
            else {
                powerDeviceMaxOuterLoopIterations
            }

            if(iterations >= maxIterations) {
                break
            }
        }

        lastOuterLoopIterations = iterations
        lastPowerConsumerTime = powerConsumerTime
        lastPowerSourceTime = powerSourceTime
        lastPowerSourceIterationCount = powerSourceIterations
        lastPowerSourceForceJacobianResets = powerSourceForceJacobianResets
    }

    /**
     * Steps the simulation forward in time.
     * Can throw:
     *  - [SingularLinearSystemException] when the linear system factorization failed.
     *  - [InvalidLinearResultsException] when the result currents or potentials are NaN or Infinity.
     *  - [PowerSourceSolverException] when the nonlinear solver cannot find a solution for the power sources.
     * */
    fun step() {
        validateUsage()

        if(system.constructionMode) {
            /**
             * Constructs the shape of the matrix and sets the initial knowns vector.
             * After this is done, no matrix entries can be added or removed.
             * At best, some matrix entries can be changed (e.g. changing a conductance), which needs a re-factor.
             * This is why I aim to implement most features as norton or thevenin systems, with a constant conductance.
             * */

            /**
             * Stamps each component. [ElectricalComponent.stamp] is called here, and only this step.
             * After that, the only legal operations to perform are conductance changes (topology-related entries cannot change).
             * */
            components.forEach {
                it.stamp()
            }

            /**
             * Adds a tiny conductance between every node and ground.
             * This regularizes the matrix so the solution is more stable.
             * */
            if(constructionOptions.spiceImplicitResistanceTrick != null) {
                nodes.forEach { node ->
                    system.stampResistance(
                        node,
                        groundNode,
                        constructionOptions.spiceImplicitResistanceTrick
                    )
                }
            }

            system.endConstruction()

            // Likely not necessary:
            matrixChanged = true
            knownsChanged = true
            constructed = true
        }

        components.forEach {
            it.prepareStep()
        }

        /**
         * Updates the matrix factorization, if necessary.
         * */
        if(matrixChanged) {
            system.solver.setA(system.matrix)
        }

        /**
         * Solves the new system, if necessary.
         * */
        if(matrixChanged || knownsChanged) {
            try {
                system.solver.solve(system.known, system.unknown)
            }
            catch (e: Exception) {
                throw SingularLinearSystemException(this, e)
            }

            copyResults()
            matrixChanged = false
            knownsChanged = false
        }

        /**
         * Solves the [PowerSource]s and [PowerConsumer]s using the special solver algorithm.
         * */
        solvePowerDevices()

        components.forEach {
            it.finishStep()
        }

        repositoryComponents.forEach {
            it.repositoryLayer.loadAndSwap()
        }
    }

    /**
     * Thévenin equivalent for the circuit surrounding a [Port].
     * */
    @Suppress("PropertyName")
    data class Thevenin(val VTh: Double, val RTh: Double)

    /**
     * Computes the Thévenin equivalent for the circuit surrounding the [nortonSystem].
     * */
    @Suppress("LocalVariableName")
    fun computeTheveninForNortonSystem(nortonSystem: NortonSystem) : Thevenin {
        validateUsage()

        val nodeP = nortonSystem.positive.node
        val nodeN = nortonSystem.negative.node

        sensitivity.solve(nodeP, nodeN)
        val results = sensitivity.results

        val s1 = if (nodeP.isGround) 0.0 else results[nodeP.id]
        val s2 = if (nodeN.isGround) 0.0 else results[nodeN.id]

        val Rth = s1 - s2
        val V = nortonSystem.potential
        val Vth = V + Rth * nortonSystem.current

        val RthSafe = if (Rth < 0.0 || Rth.isNaN()) 0.0 else Rth

        return Thevenin(Vth, RthSafe)
    }

    fun destroy() {
        validateUsage()

        components.forEach {
            it.simulationDestroyed()
        }

        destroyed = true
    }

    /**
     * Thrown when the **main MNA solve** fails (power sources should handle their failures by themselves).
     * */
    class SingularLinearSystemException(val simulation: ElectricalSimulation, ejmlException: Exception) : Exception(ejmlException)

    /**
     * Thrown when the MNA results (the node potentials and potential source currents) are invalid (NaN or Infinity).
     * @param obj The node or device where the error was discovered (if it's any help).
     * */
    class InvalidLinearResultsException(val simulation: ElectricalSimulation, val obj: Any) : Exception()

    /**
     * Indicates the failure point from the nonlinear solve.
     * */
    enum class PowerSourceSolverFailurePoint {
        /**
         * The step could not be solved for even after cleaning up the Jacobian and computing a new one.
         * */
        CouldNotDetermineStep,
        /**
         * The residual was not driven to 0 after all the iterations have been exhausted.
         * */
        DidNotConverge
    }

    /**
     * Thrown when the nonlinear solver for the power sources failed.
     * */
    class PowerSourceSolverException(val simulation: ElectricalSimulation, val reason: PowerSourceSolverFailurePoint) : Exception()
}