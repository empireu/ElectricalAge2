import org.ageseries.libage.sim.kinetic.ClutchPrototype
import org.ageseries.libage.sim.kinetic.KineticDouble
import org.ageseries.libage.sim.kinetic.KineticMono
import org.ageseries.libage.sim.kinetic.KineticNode
import org.ageseries.libage.sim.kinetic.KineticSimulation
import org.ageseries.libage.sim.kinetic.KineticSimulationForestBuilder
import org.ageseries.libage.sim.kinetic.KineticTriple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

class KineticTests {
    private val deltaTime = 0.01

    private fun simulate(optimize: Boolean = false, setup: KineticSimulationForestBuilder.() -> Unit): KineticSimulation {
        return KineticSimulationForestBuilder().apply(setup).build(deltaTime, optimize).solvers.single()
    }

    private fun shaft(inertia: Double = 1.0, angularVelocity: Double = 0.0, allowOptimization: Boolean = true): KineticDouble {
        return KineticDouble(allowOptimization).also {
            it.inertia = inertia
            it.angularVelocity = angularVelocity
        }
    }

    private fun mono(inertia: Double = 1.0, angularVelocity: Double = 0.0): KineticMono {
        return KineticMono().also {
            it.inertia = inertia
            it.angularVelocity = angularVelocity
        }
    }

    private fun momentum(vararg nodes: KineticNode) = nodes.sumOf { it.inertia * it.angularVelocity }

    private fun energy(vararg nodes: KineticNode) = nodes.sumOf { it.kineticEnergy }

    @Test
    fun torqueAcceleratesAFreeNode() {
        val node = mono(inertia = 2.0)
        val simulation = simulate { add(node) }

        node.externalTorque = 10.0
        simulation.step()

        assertEquals(10.0 / 2.0 * deltaTime, node.angularVelocity, 1e-12)
        assertEquals(node.angularVelocity * deltaTime, node.angle, 1e-12)
        assertEquals(0.0, node.externalTorque, 0.0)
        assertEquals(node.kineticEnergy, simulation.kineticEnergy, 1e-12)
    }

    @Test
    fun freeNodeKeepsSpinningWithoutTorque() {
        val node = mono(angularVelocity = 3.0)
        val simulation = simulate { add(node) }

        repeat(100) {
            simulation.step()
        }

        assertEquals(3.0, node.angularVelocity, 0.0)
        assertEquals(3.0, node.angle, 1e-9)
    }

    @Test
    fun joinedNodesEqualizeAndConserveMomentum() {
        val first = shaft(inertia = 1.0, angularVelocity = 4.0)
        val second = shaft(inertia = 3.0)
        val simulation = simulate {
            add(first)
            add(second)
            join(first.e2, second.e1)
        }

        simulation.step()

        assertEquals(1.0, first.angularVelocity, 1e-3)
        assertEquals(1.0, second.angularVelocity, 1e-3)
        assertEquals(4.0, momentum(first, second), 1e-9)
    }

    @Test
    fun torqueOnJoinedNodesAcceleratesTheirCombinedInertia() {
        val first = shaft(inertia = 1.0)
        val second = shaft(inertia = 3.0)
        val simulation = simulate {
            add(first)
            add(second)
            join(first.e2, second.e1)
        }

        repeat(100) {
            first.externalTorque = 8.0
            simulation.step()
        }

        assertEquals(2.0, first.angularVelocity, 1e-3)
        assertEquals(2.0, second.angularVelocity, 1e-3)
    }

    @Test
    fun gearsRelateVelocitiesByTheirRatioAndConserveEnergyUnderTorque() {
        val pinion = shaft(inertia = 1.0)
        val wheel = shaft(inertia = 4.0)

        wheel.e1.ratio = 2.0

        val simulation = simulate {
            add(pinion)
            add(wheel)
            join(pinion.e2, wheel.e1)
        }

        repeat(100) {
            pinion.externalTorque = 10.0
            simulation.step()
        }

        assertEquals(5.0, pinion.angularVelocity, 1e-2)
        assertEquals(2.5, wheel.angularVelocity, 1e-2)
        assertEquals(10.0 * pinion.angle, energy(pinion, wheel), 0.5)
    }

    @Test
    fun joiningEqualPolesReversesDirection() {
        val first = shaft(angularVelocity = 2.0)
        val second = shaft()
        val simulation = simulate {
            add(first)
            add(second)
            join(first.e1, second.e1)
        }

        simulation.step()

        assertEquals(1.0, first.angularVelocity, 1e-3)
        assertEquals(-1.0, second.angularVelocity, 1e-3)
    }

    @Test
    fun jointsHoldTheirPositionUnderTorque() {
        val first = shaft(inertia = 1.0)
        val second = shaft(inertia = 5.0)
        val simulation = simulate {
            add(first)
            add(second)
            join(first.e2, second.e1)
        }

        repeat(1000) {
            first.externalTorque = 50.0
            simulation.step()
        }

        assertEquals(first.angle, second.angle, 1e-2)
    }

    @Test
    fun jointsCorrectAnInitialAngleErrorWithoutAddingMomentum() {
        val first = shaft()
        val second = shaft()

        first.setExternalAngle(0.5)

        val simulation = simulate {
            add(first)
            add(second)
            join(first.e2, second.e1)
        }

        repeat(300) {
            simulation.step()
        }

        assertEquals(first.angle, second.angle, 1e-3)
        assertEquals(0.0, momentum(first, second), 1e-9)
        assertEquals(0.0, first.angularVelocity, 1e-3)
    }

    @Test
    fun maxLambdaLimitsTheImpulseOfAJoint() {
        val first = shaft(angularVelocity = 100.0)
        val second = shaft()

        first.e2.maxLambda = 1.0

        val simulation = simulate {
            add(first)
            add(second)
            join(first.e2, second.e1)
        }

        simulation.step()

        assertEquals(99.0, first.angularVelocity, 1e-9)
        assertEquals(1.0, second.angularVelocity, 1e-9)

        repeat(200) {
            val previousVelocity = second.angularVelocity

            simulation.step()

            assertTrue(abs(second.angularVelocity - previousVelocity) <= 1.0 + 1e-9)
            assertEquals(100.0, momentum(first, second), 1e-9)
        }
    }

    @Test
    fun reportedImpulseMatchesTheMomentumEachNodeReceived() {
        val first = shaft(inertia = 1.0, angularVelocity = 4.0)
        val second = shaft(inertia = 3.0)

        second.e1.ratio = 2.0

        val simulation = simulate {
            add(first)
            add(second)
            join(first.e2, second.e1)
        }

        simulation.step()

        assertEquals(first.inertia * first.angularVelocity - 4.0, first.e2.impulse, 1e-3)
        assertEquals(second.inertia * second.angularVelocity, second.e1.impulse, 1e-3)
    }

    @Test
    fun reportedImpulseMatchesTheMomentumEachNodeReceivedWhenPolesAreEqual() {
        val first = shaft(inertia = 1.0, angularVelocity = 4.0)
        val second = shaft(inertia = 3.0)
        val simulation = simulate {
            add(first)
            add(second)
            join(first.e1, second.e1)
        }

        simulation.step()

        assertEquals(first.inertia * first.angularVelocity - 4.0, first.e1.impulse, 1e-3)
        assertEquals(second.inertia * second.angularVelocity, second.e1.impulse, 1e-3)
    }

    @Test
    fun tripleNodeConnectsAllOfItsExtensions() {
        val triple = KineticTriple()
        val left = shaft()
        val right = shaft()
        val branch = shaft(angularVelocity = 3.0)
        val simulation = simulate {
            add(triple)
            add(left)
            add(right)
            add(branch)
            join(left.e2, triple.e1)
            join(triple.e2, right.e1)
            join(branch.e2, triple.e3)
        }

        simulation.step()

        listOf(triple, left, right, branch).forEach {
            assertEquals(0.75, it.angularVelocity, 1e-2)
        }
    }

    @Test
    fun joiningTwiceCreatesOneConstraint() {
        val first = shaft()
        val second = shaft()
        val simulation = simulate {
            add(first)
            add(second)
            join(first.e2, second.e1)
            join(second.e1, first.e2)
        }

        assertEquals(1, simulation.rigidConstraints.size)
    }

    @Test
    fun disconnectedNodesGetSeparateSolvers() {
        val first = shaft()
        val second = shaft()
        val third = shaft()
        val builder = KineticSimulationForestBuilder()

        builder.add(first)
        builder.add(second)
        builder.add(third)
        builder.join(first.e2, second.e1)

        assertEquals(2, builder.build(deltaTime, false).solvers.size)
    }

    @Test
    fun builderCannotBeReused() {
        val builder = KineticSimulationForestBuilder()

        builder.add(shaft())
        builder.build(deltaTime)

        assertThrows(IllegalArgumentException::class.java) {
            builder.build(deltaTime)
        }
    }

    @Test
    fun destroyedSimulationCannotStepAndReleasesItsNodes() {
        val node = mono()
        val simulation = simulate { add(node) }

        assertTrue(node.isInSimulation)

        simulation.destroy()

        assertFalse(node.isInSimulation)
        assertThrows(IllegalStateException::class.java) {
            simulation.step()
        }
    }

    @Test
    fun nodeCanMoveToANewSimulationAndKeepsItsVelocity() {
        val first = shaft(angularVelocity = 2.0)
        val second = shaft(angularVelocity = 2.0)
        val oldSimulation = simulate {
            add(first)
            add(second)
            join(first.e2, second.e1)
        }

        repeat(10) {
            oldSimulation.step()
        }

        oldSimulation.destroy()
        first.resetForRestart()
        second.resetForRestart()

        assertEquals(0.0, first.angle, 0.0)

        val newSimulation = simulate(optimize = true) {
            add(first)
            add(second)
            join(first.e2, second.e1)
        }

        newSimulation.step()

        assertEquals(2.0, first.angularVelocity, 1e-9)
        assertEquals(2.0, second.angularVelocity, 1e-9)
    }

    @Test
    fun viscousFrictionFollowsTheDiscreteDecay() {
        val node = mono(inertia = 2.0, angularVelocity = 10.0)

        node.viscousDamping = 0.5

        val simulation = simulate { add(node) }

        repeat(1000) {
            simulation.step()
        }

        assertEquals(10.0 * (1.0 - 0.5 * deltaTime / 2.0).pow(1000), node.angularVelocity, 1e-9)
    }

    @Test
    fun staticFrictionHoldsATorqueBelowItsThreshold() {
        val node = mono()

        node.staticFriction = 5.0
        node.coulombFriction = 2.0

        val simulation = simulate { add(node) }

        repeat(100) {
            node.externalTorque = 4.0
            simulation.step()
        }

        assertEquals(0.0, node.angularVelocity, 0.0)
        assertEquals(0.0, node.heatFromFriction, 0.0)
    }

    @Test
    fun torqueAboveStaticFrictionMovesTheNodeAgainstCoulombFriction() {
        val node = mono()

        node.staticFriction = 5.0
        node.coulombFriction = 2.0

        val simulation = simulate { add(node) }

        repeat(100) {
            node.externalTorque = 6.0
            simulation.step()
        }

        assertEquals(4.0, node.angularVelocity, 1e-9)
    }

    @Test
    fun coulombFrictionBringsANodeToRest() {
        val node = mono(inertia = 1.0, angularVelocity = 1.02)

        node.coulombFriction = 5.0
        node.velocityEps = 0.01

        val simulation = simulate { add(node) }

        repeat(100) {
            simulation.step()
        }

        val settled = abs(node.angularVelocity)

        repeat(10) {
            simulation.step()

            assertTrue(abs(node.angularVelocity) <= 0.01) {
                "The node should rest, it moves at ${node.angularVelocity} rad/s after starting at $settled rad/s"
            }
        }
    }

    @Test
    fun coulombFrictionHeatAccountsForTheLostEnergy() {
        val node = mono(inertia = 1.0, angularVelocity = 10.0)

        node.coulombFriction = 1.0

        val simulation = simulate { add(node) }
        val initialEnergy = node.kineticEnergy

        repeat(1200) {
            simulation.step()
        }

        assertEquals(initialEnergy - node.kineticEnergy, node.heatFromFriction, 0.05 * initialEnergy)
    }

    @Test
    fun unpressedClutchLeavesTheNodesIndependent() {
        val first = mono(angularVelocity = 10.0)
        val second = mono()
        val clutch = ClutchPrototype(first, second)

        clutch.pressure = 0.0

        val simulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        repeat(10) {
            simulation.step()
        }

        assertEquals(10.0, first.angularVelocity, 0.0)
        assertEquals(0.0, second.angularVelocity, 0.0)
        assertEquals(0.0, clutch.heat, 0.0)
        assertFalse(clutch.locked)
    }

    @Test
    fun clutchOnEqualVelocitiesAppliesNoTorqueOnTheFirstStep() {
        val first = mono(angularVelocity = 10.0)
        val second = mono(angularVelocity = 10.0)
        val clutch = ClutchPrototype(first, second)
        val simulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        simulation.step()

        assertEquals(10.0, first.angularVelocity, 1e-9)
        assertEquals(10.0, second.angularVelocity, 1e-9)
    }

    @Test
    fun clutchLocksWhenTheVelocitiesAreClose() {
        val first = mono(angularVelocity = 10.0)
        val second = mono(angularVelocity = 9.0)
        val clutch = ClutchPrototype(first, second)
        val simulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        repeat(3) {
            simulation.step()
        }

        assertTrue(clutch.locked)
        assertEquals(9.5, first.angularVelocity, 1e-2)
        assertEquals(9.5, second.angularVelocity, 1e-2)
    }

    @Test
    fun lockedClutchTransmitsTorqueBelowItsLimit() {
        val first = mono()
        val second = mono()
        val clutch = ClutchPrototype(first, second)
        val simulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        repeat(200) {
            first.externalTorque = 20.0
            simulation.step()
        }

        assertTrue(clutch.locked)
        assertEquals(20.0, first.angularVelocity, 1e-1)
        assertEquals(20.0, second.angularVelocity, 1e-1)
    }

    @Test
    fun clutchSlipsAboveItsTorqueLimit() {
        val first = mono()
        val second = mono()
        val clutch = ClutchPrototype(first, second)
        val simulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        repeat(10) {
            simulation.step()
        }

        assertTrue(clutch.locked)

        repeat(50) {
            first.externalTorque = 1000.0
            simulation.step()
        }

        assertFalse(clutch.locked)
        assertTrue(first.angularVelocity > second.angularVelocity)
        assertTrue(clutch.heat > 0.0)
    }

    @Test
    fun clutchRespondsToPressureChangesWithoutARebuild() {
        val first = mono(angularVelocity = 100.0)
        val second = mono()
        val clutch = ClutchPrototype(first, second)

        clutch.pressure = 0.0

        val simulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        simulation.step()

        assertEquals(100.0, first.angularVelocity, 0.0)

        clutch.pressure = 1.0

        repeat(500) {
            simulation.step()
        }

        assertEquals(50.0, first.angularVelocity, 1e-1)
        assertEquals(50.0, second.angularVelocity, 1e-1)
    }

    @Test
    fun slippingClutchHeatAccountsForTheLostEnergy() {
        val first = mono(angularVelocity = 100.0)
        val second = mono()
        val clutch = ClutchPrototype(first, second)

        clutch.viscousFrictionCoefficient = 1.0
        clutch.dvLock = 0.0

        val simulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        val initialEnergy = energy(first, second)

        repeat(1000) {
            simulation.step()
        }

        val lost = initialEnergy - energy(first, second)

        assertEquals(2500.0, lost, 1.0)
        assertEquals(lost, clutch.heat, 0.02 * lost)
    }

    private class Line(val shafts: List<KineticDouble>, val load: KineticMono, val simulation: KineticSimulation)

    private fun buildLine(optimize: Boolean, loadFirst: Boolean = false, configure: (KineticDouble) -> Unit = { }): Line {
        val shafts = List(5) { shaft().also(configure) }
        val load = mono(inertia = 2.0)

        val simulation = simulate(optimize) {
            add(load)
            shafts.forEach { add(it) }

            for (index in 1 until shafts.size) {
                join(shafts[index - 1].e2, shafts[index].e1)
            }

            if (loadFirst) {
                join(load.extension, shafts.first().e1)
            } else {
                join(shafts.last().e2, load.extension)
            }
        }

        return Line(shafts, load, simulation)
    }

    @Test
    fun optimizationMergesARowOfEqualShafts() {
        val line = buildLine(optimize = true)

        assertEquals(1, line.simulation.optimizedShafts.size)
        assertEquals(2, line.simulation.nodes.size)
        assertEquals(1, line.simulation.rigidConstraints.size)
        assertEquals(5.0, line.simulation.optimizedShafts[0].inertia, 0.0)
    }

    @Test
    fun optimizationKeepsShaftsOfDifferentInertiaApart() {
        val first = shaft(inertia = 1.0)
        val second = shaft(inertia = 2.0)
        val simulation = simulate(optimize = true) {
            add(first)
            add(second)
            join(first.e2, second.e1)
        }

        assertEquals(0, simulation.optimizedShafts.size)
        assertEquals(2, simulation.nodes.size)
    }

    @Test
    fun optimizationKeepsShaftsThatDisallowItApart() {
        val first = shaft(allowOptimization = false)
        val second = shaft(allowOptimization = false)
        val simulation = simulate(optimize = true) {
            add(first)
            add(second)
            join(first.e2, second.e1)
        }

        assertEquals(0, simulation.optimizedShafts.size)
    }

    @Test
    fun inertiaAndRatioCannotChangeWhileOptimized() {
        val line = buildLine(optimize = true)

        assertThrows(IllegalStateException::class.java) {
            line.shafts[0].inertia = 3.0
        }

        assertThrows(IllegalStateException::class.java) {
            line.shafts[0].e2.ratio = 3.0
        }
    }

    @Test
    fun optimizedLineBehavesLikeSeparateShafts() {
        val configure: (KineticDouble) -> Unit = {
            it.viscousDamping = 0.05
            it.coulombFriction = 0.1
        }

        val separate = buildLine(false, configure = configure)
        val optimized = buildLine(true, configure = configure)

        repeat(500) {
            separate.load.externalTorque = 20.0
            optimized.load.externalTorque = 20.0
            separate.simulation.step()
            optimized.simulation.step()
        }

        for (index in separate.shafts.indices) {
            assertEquals(separate.shafts[index].angularVelocity, optimized.shafts[index].angularVelocity, 1e-2)
            assertEquals(separate.shafts[index].angle, optimized.shafts[index].angle, 1e-1)
        }

        assertEquals(separate.load.angularVelocity, optimized.load.angularVelocity, 1e-2)
    }

    @Test
    fun torqueOnAnEliminatedShaftReachesTheLine() {
        val line = buildLine(optimize = true)

        repeat(70) {
            line.shafts[2].externalTorque = 7.0
            line.simulation.step()
        }

        line.shafts.forEach {
            assertEquals(0.7, it.angularVelocity, 1e-3)
        }

        assertEquals(0.7, line.load.angularVelocity, 1e-3)
    }

    private fun assertInternalImpulsesMatch(loadFirst: Boolean) {
        val separate = buildLine(false, loadFirst)
        val optimized = buildLine(true, loadFirst)

        repeat(100) {
            separate.load.externalTorque = 70.0
            optimized.load.externalTorque = 70.0
            separate.simulation.step()
            optimized.simulation.step()
        }

        for (index in 0 until separate.shafts.size - 1) {
            assertEquals(abs(separate.shafts[index].e2.impulse), abs(optimized.shafts[index].e2.impulse), 1e-2) {
                "Impulse at the joint after shaft $index, load first is $loadFirst"
            }
        }
    }

    @Test
    fun optimizedLineReportsInternalImpulsesWithTheLoadOnTheRight() {
        assertInternalImpulsesMatch(false)
    }

    @Test
    fun optimizedLineReportsInternalImpulsesWithTheLoadOnTheLeft() {
        assertInternalImpulsesMatch(true)
    }

    @Test
    fun adjacentLinesAreJoinedAndBehaveLikeSeparateShafts() {
        fun run(optimize: Boolean): List<Double> {
            val light = List(3) { shaft(inertia = 1.0) }
            val heavy = List(3) { shaft(inertia = 2.0) }
            val all = light + heavy

            val simulation = simulate(optimize) {
                all.forEach { add(it) }

                for (index in 1 until all.size) {
                    join(all[index - 1].e2, all[index].e1)
                }
            }

            if (optimize) {
                assertEquals(2, simulation.optimizedShafts.size)
                assertEquals(1, simulation.rigidConstraints.size)
            }

            repeat(100) {
                all.first().externalTorque = 9.0
                simulation.step()
            }

            return all.map { it.angularVelocity }
        }

        val separate = run(false)
        val optimized = run(true)

        assertEquals(1.0, separate[0], 1e-2)

        for (index in separate.indices) {
            assertEquals(separate[index], optimized[index], 1e-2)
        }
    }

    @Test
    fun ringOfShaftsStaysStableAndConservesMomentum() {
        listOf(false, true).forEach { optimize ->
            val shafts = List(4) { shaft(angularVelocity = it.toDouble()) }
            val simulation = simulate(optimize) {
                shafts.forEach { add(it) }

                for (index in shafts.indices) {
                    join(shafts[index].e2, shafts[(index + 1) % shafts.size].e1)
                }
            }

            repeat(100) {
                simulation.step()
            }

            shafts.forEach {
                assertEquals(1.5, it.angularVelocity, 1e-2)
            }
        }
    }

    private fun randomTrain(seed: Int, count: Int): Pair<List<KineticDouble>, KineticSimulation> {
        val random = Random(seed)
        val nodes = List(count) {
            shaft(
                inertia = 10.0.pow(random.nextDouble(-1.5, 1.5)),
                angularVelocity = random.nextDouble(-50.0, 50.0)
            ).also {
                it.e1.ratio = 2.0.pow(random.nextDouble(-1.0, 1.0))
                it.e2.ratio = 2.0.pow(random.nextDouble(-1.0, 1.0))
            }
        }

        val simulation = simulate {
            nodes.forEach { add(it) }

            for (index in 1 until count) {
                join(nodes[index - 1].e2, nodes[index].e1)
            }
        }

        return Pair(nodes, simulation)
    }

    @Test
    fun chainOfUnequalInertiasConvergesOnTheFirstStep() {
        val random = Random(5)
        val chain = List(20) { shaft(inertia = 10.0.pow(random.nextDouble(-1.5, 1.5))) }

        chain[0].angularVelocity = 1000.0 / chain[0].inertia

        val simulation = simulate {
            chain.forEach { add(it) }

            for (index in 1 until chain.size) {
                join(chain[index - 1].e2, chain[index].e1)
            }
        }

        simulation.step()

        val expected = 1000.0 / chain.sumOf { it.inertia }

        chain.forEach {
            assertEquals(expected, it.angularVelocity, 0.01)
        }

        assertEquals(1000.0, chain.sumOf { it.inertia * it.angularVelocity }, 1e-6)
    }

    @Test
    fun randomGearTrainsDoNotGainEnergy() {
        repeat(100) { seed ->
            val random = Random(seed)
            val (nodes, simulation) = randomTrain(seed, random.nextInt(2, 12))

            simulation.step()

            var previousEnergy = nodes.sumOf { it.kineticEnergy }

            repeat(200) { step ->
                simulation.step()

                val currentEnergy = nodes.sumOf { it.kineticEnergy }

                assertTrue(currentEnergy <= previousEnergy * 1.0001 + 1e-3) {
                    "Seed $seed, step $step: the energy grew from $previousEnergy to $currentEnergy"
                }

                previousEnergy = currentEnergy
            }
        }
    }

    @Test
    fun solverSettlesQuicklyAfterAnImpactEndsAndWhileATorqueHolds() {
        val (nodes, simulation) = randomTrain(3, 8)

        simulation.step()
        simulation.step()

        repeat(20) {
            simulation.step()

            assertTrue(simulation.lastIterationCount <= 8) {
                "Nothing changes after the impact, but it took ${simulation.lastIterationCount} iterations"
            }
        }

        repeat(20) { step ->
            nodes.first().externalTorque = 5.0
            simulation.step()

            if (step > 1) {
                assertTrue(simulation.lastIterationCount <= 64) {
                    "A steady torque should reuse the impulses, it took ${simulation.lastIterationCount} iterations"
                }
            }
        }
    }

    @Test
    fun lockedClutchDoesNotSnapWhenTheSimulationRestarts() {
        val first = mono()
        val second = mono()
        val clutch = ClutchPrototype(first, second)

        first.setExternalAngle(1.0)

        val oldSimulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        repeat(3) {
            oldSimulation.step()
        }

        assertTrue(clutch.locked)
        assertEquals(1.0, clutch.referenceAngle, 1e-9)

        oldSimulation.destroy()
        first.resetForRestart()
        second.resetForRestart()

        val newSimulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        repeat(10) {
            newSimulation.step()

            assertEquals(0.0, first.angularVelocity, 1e-6)
            assertEquals(0.0, second.angularVelocity, 1e-6)
        }
    }

    @Test
    fun lockedClutchCorrectsAnAngleErrorTheShortWayAround() {
        val first = mono()
        val second = mono()
        val clutch = ClutchPrototype(first, second)
        val simulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        repeat(3) {
            simulation.step()
        }

        assertTrue(clutch.locked)

        first.setExternalAngle(first.angle - 4.0)
        simulation.step()

        assertTrue(first.angularVelocity < second.angularVelocity) {
            "An error of -4 rad is closer to the next turn, so the first node should move back to it"
        }
    }

    @Test
    fun lockedClutchStaysLockedWhileItCorrectsASmallAngleError() {
        val first = mono()
        val second = mono()
        val clutch = ClutchPrototype(first, second)
        val simulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        repeat(3) {
            simulation.step()
        }

        assertTrue(clutch.locked)

        first.setExternalAngle(first.angle + 0.02)

        repeat(50) {
            simulation.step()

            assertTrue(clutch.locked) {
                "A small angle error is not a reason to unlock"
            }
        }

        assertEquals(clutch.referenceAngle, first.angle - second.angle, 1e-3)
    }

    private fun clutchUnderTorque(
        torque: Double,
        steps: Int,
        clutch: ClutchPrototype,
        first: KineticMono,
        simulation: KineticSimulation
    ): Int {
        var transitions = 0
        var previous = clutch.locked

        repeat(steps) {
            first.externalTorque = torque
            simulation.step()

            if (clutch.locked != previous) {
                transitions++
                previous = clutch.locked
            }
        }

        return transitions
    }

    @Test
    fun clutchAtItsTorqueLimitDoesNotChatter() {
        val first = mono()
        val second = mono()
        val clutch = ClutchPrototype(first, second)
        val simulation = simulate {
            add(first)
            add(second)
            createClutch(clutch)
        }

        assertEquals(0, clutchUnderTorque(200.0, 200, clutch, first, simulation))
        assertFalse(clutch.locked)
    }

    @Test
    fun clutchLocksOnlyBelowTheLockFactorButHoldsUpToTheLimit() {
        fun build(): Triple<ClutchPrototype, KineticMono, KineticSimulation> {
            val first = mono()
            val second = mono()
            val clutch = ClutchPrototype(first, second)

            return Triple(clutch, first, simulate {
                add(first)
                add(second)
                createClutch(clutch)
            })
        }

        val (aboveFactor, aboveFactorDriver, aboveFactorSimulation) = build()
        clutchUnderTorque(190.0, 50, aboveFactor, aboveFactorDriver, aboveFactorSimulation)
        assertFalse(aboveFactor.locked)

        val (belowFactor, belowFactorDriver, belowFactorSimulation) = build()
        clutchUnderTorque(170.0, 50, belowFactor, belowFactorDriver, belowFactorSimulation)
        assertTrue(belowFactor.locked)

        assertEquals(0, clutchUnderTorque(190.0, 50, belowFactor, belowFactorDriver, belowFactorSimulation))
        assertTrue(belowFactor.locked)
    }

    @Test
    fun impulseLimitHoldsOnBothSidesOfAGearWhicheverWayItIsJoined() {
        listOf(false, true).forEach { flipped ->
            val slow = shaft(angularVelocity = 100.0)
            val fast = shaft()

            slow.e2.ratio = 10.0
            slow.e2.maxLambda = 1.0
            fast.e1.maxLambda = 1.0

            val simulation = simulate {
                add(slow)
                add(fast)

                if (flipped) {
                    join(fast.e1, slow.e2)
                } else {
                    join(slow.e2, fast.e1)
                }
            }

            simulation.step()

            assertEquals(1.0, abs(slow.e2.impulse), 1e-9)
            assertEquals(0.1, abs(fast.e1.impulse), 1e-9)
        }
    }

    @Test
    fun optimizedLineKeepsTheImpulseLimitOfItsShafts() {
        val line = buildLine(optimize = true) {
            it.e1.maxLambda = 1.0
            it.e2.maxLambda = 1.0
            it.angularVelocity = 100.0
        }

        line.simulation.step()

        assertEquals(1, line.simulation.optimizedShafts.size)
        assertEquals(0.5, line.load.angularVelocity, 1e-9)
    }

    private fun stressChain(count: Int): Pair<List<KineticDouble>, KineticSimulation> {
        val random = Random(5)
        val chain = List(count) {
            shaft(inertia = 10.0.pow(random.nextDouble(-2.0, 2.0)), angularVelocity = random.nextDouble(-50.0, 50.0))
        }

        val simulation = simulate {
            chain.forEach { add(it) }

            for (index in 1 until count) {
                join(chain[index - 1].e2, chain[index].e1)
            }
        }

        return Pair(chain, simulation)
    }

    @Test
    fun chainWithALargeInertiaRangeConvergesWithinTheFirstStepBudget() {
        listOf(30, 100).forEach { count ->
            val (chain, simulation) = stressChain(count)
            val expected = chain.sumOf { it.inertia * it.angularVelocity } / chain.sumOf { it.inertia }

            simulation.step()

            assertTrue(simulation.lastIterationCount < simulation.maxIterationsFirstStep) {
                "A chain of $count nodes took ${simulation.lastIterationCount} iterations"
            }

            chain.forEach {
                assertEquals(expected, it.angularVelocity, count * simulation.residualEps)
            }
        }
    }

    @Test
    fun invalidResultsFailTheStep() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            val first = shaft()
            val second = shaft()

            val simulation = simulate {
                add(first)
                add(second)
                join(first.e2, second.e1)
            }

            first.externalTorque = invalid

            val exception = assertThrows(KineticSimulation.InvalidResultsException::class.java) {
                simulation.step()
            }

            assertTrue(exception.simulation === simulation)
        }
    }

    @Test
    fun invalidAngleOfANodeWithoutConstraintsFailsTheStep() {
        val node = shaft()
        val simulation = simulate {
            add(node)
        }

        node.setExternalAngle(Double.NaN)

        assertThrows(KineticSimulation.InvalidResultsException::class.java) {
            simulation.step()
        }
    }

    @Test
    fun flywheelsJoinedByShaftsStayTogetherUnderAVaryingTorque() {
        val chain = List(10) { index ->
            shaft(inertia = if (index % 2 == 0) 12.5 else 0.03365, angularVelocity = 100.0)
        }

        val simulation = simulate {
            chain.forEach { add(it) }

            for (index in 1 until chain.size) {
                join(chain[index - 1].e2, chain[index].e1)
            }
        }

        repeat(300) { step ->
            chain.first().externalTorque = 200.0 + 100.0 * kotlin.math.sin(step * 0.0628)
            simulation.step()

            val velocities = chain.map { it.angularVelocity }

            assertTrue(velocities.max() - velocities.min() < 0.01) {
                "Step $step: the rigid chain spread over ${velocities.max() - velocities.min()} rad/s"
            }
        }
    }

    private fun randomGearTree(seed: Int, count: Int): Pair<List<KineticNode>, KineticSimulation> {
        val inertias = doubleArrayOf(0.001, 0.03365, 0.03365, 0.03365, 0.1, 1.0, 3.1278, 10.0, 12.5)
        val ratios = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 2.0, 0.5, 4.0, 0.25)
        val random = Random(seed)
        val nodes = ArrayList<KineticNode>()
        val builder = KineticSimulationForestBuilder()

        val root = KineticTriple()
        root.inertia = inertias[random.nextInt(inertias.size)]
        root.angularVelocity = random.nextDouble(-50.0, 50.0)
        nodes.add(root)
        builder.add(root)

        val open = arrayListOf(root.e1, root.e2, root.e3)

        for (index in 1 until count) {
            val extension = open.removeAt(random.nextInt(open.size))
            val inertia = inertias[random.nextInt(inertias.size)]
            val velocity = random.nextDouble(-50.0, 50.0)

            if (random.nextInt(5) == 0) {
                val node = KineticTriple()
                node.inertia = inertia
                node.angularVelocity = velocity
                nodes.add(node)
                builder.add(node)
                builder.join(extension, node.e1)
                open.add(node.e2)
                open.add(node.e3)
            } else {
                val node = KineticDouble(false)
                node.inertia = inertia
                node.angularVelocity = velocity
                node.e1.ratio = ratios[random.nextInt(ratios.size)]
                nodes.add(node)
                builder.add(node)
                builder.join(extension, node.e1)
                open.add(node.e2)
            }
        }

        return Pair(nodes, builder.build(deltaTime, false).solvers.single())
    }

    @Test
    fun solvesThatAreCutShortNeverAddEnergy() {
        repeat(60) { seed ->
            val (nodes, simulation) = randomGearTree(seed, 3 + seed % 30)

            simulation.biasFactor = 0.0
            simulation.maxIterations = 512
            simulation.maxIterationsFirstStep = 512
            simulation.step()

            var previousEnergy = nodes.sumOf { it.kineticEnergy }

            repeat(200) { step ->
                simulation.step()

                val currentEnergy = nodes.sumOf { it.kineticEnergy }

                assertTrue(currentEnergy <= previousEnergy * (1.0 + 1e-9) + 1e-9) {
                    "Seed $seed, step $step: the energy grew from $previousEnergy to $currentEnergy"
                }

                previousEnergy = currentEnergy
            }
        }
    }
}
