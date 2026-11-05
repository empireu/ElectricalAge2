package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.content.DcMotorCell
import org.eln2.mc.common.content.MultiJointCell
import org.eln2.mc.common.content.modules.Eln2Kinetic
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.mathematics.FacingDirection
import kotlin.math.abs

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object KineticGameTests {
    private const val ARMATURE_RESISTANCE = 0.0667
    private const val BACK_EMF_CONSTANT = 2.05
    private const val TORQUE_CONSTANT = 2.05
    private const val COULOMB_FRICTION = 1.0
    private const val VISCOUS_DAMPING = 0.01
    private const val CORE_LOSS = 1e-6
    private const val BREAKDOWN_VELOCITY = 100.0 * 2.0 * Math.PI

    private val MOTOR get() = Eln2Kinetic.BASIC_DC_MOTOR_PART
    private val STRAIGHT_JOINT get() = Eln2Kinetic.PRIMITIVE_STANDARD_IRON_STRAIGHT_JOINT.partInfo
    private val HUB_JOINT get() = Eln2Kinetic.PRIMITIVE_STANDARD_IRON_HUB_JOINT.partInfo

    private class MotorCircuit(val source: Part, val motor: Part) {
        val cell: DcMotorCell get() = motor.cellAs()

        fun angularVelocity() = motor.graph().runSuspended {
            cell.kinetic.node.angularVelocity
        }

        fun armatureCurrent() = motor.graph().runSuspended {
            cell.electrical.armatureResistor.current
        }
    }

    private fun GameTestHelper.placeMotorCircuit(z: Int, potential: Double): MotorCircuit {
        val source = placePart(2, z, VOLTAGE_SOURCE_ITEM, FacingDirection.EAST)
        val firstWire = placePart(3, z, ELECTRICAL_WIRE_ITEM)
        val motor = placePart(4, z, MOTOR, FacingDirection.NORTH)
        val secondWire = placePart(5, z, ELECTRICAL_WIRE_ITEM)
        placePart(6, z, GROUND_ITEM)

        checkConnected(firstWire, motor)
        checkConnected(motor, secondWire)

        source.setSourcePotential(potential)

        return MotorCircuit(source, motor)
    }

    private fun expectedAngularVelocity(potential: Double): Double {
        val resistance = ARMATURE_RESISTANCE + VOLTAGE_SOURCE_RESISTANCE + 2.0 * ELECTRICAL_WIRE_RESISTANCE +
            GROUND_RESISTANCE

        val firstEstimate = potential / BACK_EMF_CONSTANT
        val current = (COULOMB_FRICTION + VISCOUS_DAMPING * abs(firstEstimate)) / TORQUE_CONSTANT

        return (abs(potential) - resistance * current) / BACK_EMF_CONSTANT
    }

    @GameTest(template = ARENA, timeoutTicks = 150)
    @JvmStatic
    fun unloadedMotorSpeedFollowsThePotentialOverTheBackEmfConstant(helper: GameTestHelper) {
        val slow = helper.placeMotorCircuit(4, 5.0)
        val fast = helper.placeMotorCircuit(9, 10.0)

        helper.startSequence()
            .thenExecuteAfter(60) {
                val slowExpected = expectedAngularVelocity(5.0)
                val fastExpected = expectedAngularVelocity(10.0)

                checkClose(slowExpected, abs(slow.angularVelocity()), slowExpected * 0.02, "Speed at 5 V")
                checkClose(fastExpected, abs(fast.angularVelocity()), fastExpected * 0.02, "Speed at 10 V")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 150)
    @JvmStatic
    fun steadyStateArmatureCurrentBalancesTheFrictionTorque(helper: GameTestHelper) {
        val circuit = helper.placeMotorCircuit(4, 10.0)

        helper.startSequence()
            .thenExecuteAfter(60) {
                val speed = abs(circuit.angularVelocity())
                val expected = (COULOMB_FRICTION + VISCOUS_DAMPING * speed) / TORQUE_CONSTANT

                checkClose(expected, abs(circuit.armatureCurrent()), expected * 0.05, "Armature current")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun coreLossBrakesTheRotor(helper: GameTestHelper) {
        val circuit = helper.placeMotorCircuit(4, 10.0)

        checkClose(VISCOUS_DAMPING + CORE_LOSS, circuit.cell.kinetic.node.viscousDamping, 1e-12, "Viscous damping")
        helper.succeed()
    }

    @GameTest(template = ARENA, timeoutTicks = 150)
    @JvmStatic
    fun steadyStateBackEmfPowerBecomesFrictionHeat(helper: GameTestHelper) {
        val circuit = helper.placeMotorCircuit(4, 10.0)

        helper.startSequence()
            .thenExecuteAfter(60) {
                val (backEmfPower, frictionPower) = circuit.motor.graph().runSuspended {
                    val electrical = circuit.cell.electrical
                    val node = circuit.cell.kinetic.node

                    Pair(
                        abs(electrical.voltageSource.potential * electrical.armatureResistor.current),
                        node.deltaHeatFromFriction / node.simulation.dt
                    )
                }

                checkClose(backEmfPower, frictionPower, backEmfPower * 0.002, "Friction heat power")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 150)
    @JvmStatic
    fun motorSpinsInOppositeDirectionsForOppositePotentials(helper: GameTestHelper) {
        val forward = helper.placeMotorCircuit(4, 8.0)
        val reverse = helper.placeMotorCircuit(9, -8.0)

        helper.startSequence()
            .thenExecuteAfter(60) {
                val forwardSpeed = forward.angularVelocity()
                val reverseSpeed = reverse.angularVelocity()

                checkThat(forwardSpeed * reverseSpeed < 0.0) {
                    "Expected opposite spin directions, got $forwardSpeed and $reverseSpeed"
                }

                checkClose(abs(forwardSpeed), abs(reverseSpeed), abs(forwardSpeed) * 0.02, "Speed magnitude")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 150)
    @JvmStatic
    fun unpoweredMotorStaysAtRest(helper: GameTestHelper) {
        val circuit = helper.placeMotorCircuit(4, 0.0)

        helper.startSequence()
            .thenExecuteAfter(20) {
                checkClose(0.0, circuit.angularVelocity(), 1e-3, "Unpowered motor speed")
            }
            .thenSucceed()
    }

    private fun Part.jointSpeed() = graph().runSuspended {
        cellAs<MultiJointCell>().kinetic.nodes.filterNotNull().first().angularVelocity
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun straightJointsCarryTheMotorSpeed(helper: GameTestHelper) {
        val circuit = helper.placeMotorCircuit(8, 10.0)
        val firstJoint = helper.placePart(4, 7, STRAIGHT_JOINT, FacingDirection.NORTH)
        val secondJoint = helper.placePart(4, 6, STRAIGHT_JOINT, FacingDirection.NORTH)

        helper.startSequence()
            .thenExecuteAfter(80) {
                val motorSpeed = circuit.angularVelocity()

                checkThat(abs(motorSpeed) > 1.0) {
                    "The motor with shafts attached did not spin, speed $motorSpeed"
                }

                checkClose(motorSpeed, firstJoint.jointSpeed(), abs(motorSpeed) * 0.02, "First joint speed")
                checkClose(motorSpeed, secondJoint.jointSpeed(), abs(motorSpeed) * 0.02, "Second joint speed")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun hubJointReversesTheRotationOnItsSideShafts(helper: GameTestHelper) {
        val circuit = helper.placeMotorCircuit(10, 10.0)
        helper.placePart(4, 9, HUB_JOINT, FacingDirection.NORTH)
        val straightOut = helper.placePart(4, 8, STRAIGHT_JOINT, FacingDirection.NORTH)
        val sideOut = helper.placePart(3, 9, STRAIGHT_JOINT, FacingDirection.WEST)

        helper.startSequence()
            .thenExecuteAfter(80) {
                val motorSpeed = circuit.angularVelocity()
                val straightSpeed = straightOut.jointSpeed()
                val sideSpeed = sideOut.jointSpeed()

                checkThat(abs(motorSpeed) > 1.0) {
                    "The motor with a hub attached did not spin, speed $motorSpeed"
                }

                checkClose(motorSpeed, straightSpeed, abs(motorSpeed) * 0.02, "Speed on the straight shaft")
                checkClose(-motorSpeed, sideSpeed, abs(motorSpeed) * 0.02, "Speed on the side shaft")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 300)
    @JvmStatic
    fun motorBreaksDownWhenSpunPastItsLimit(helper: GameTestHelper) {
        val motor = helper.placePart(4, 6, MOTOR, FacingDirection.NORTH)
        val position = arenaPosition(4, 6)

        motor.graph().runSuspended {
            motor.cellAs<DcMotorCell>().kinetic.node.angularVelocity = BREAKDOWN_VELOCITY * 1.5
        }

        helper.succeedWhen {
            checkThat(helper.partOrNullAt(position) == null) {
                "The overspeed motor has not broken down"
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun motorBelowItsSpeedLimitSurvives(helper: GameTestHelper) {
        val motor = helper.placePart(4, 6, MOTOR, FacingDirection.NORTH)
        val position = arenaPosition(4, 6)

        motor.graph().runSuspended {
            motor.cellAs<DcMotorCell>().kinetic.node.angularVelocity = BREAKDOWN_VELOCITY * 0.5
        }

        helper.startSequence()
            .thenExecuteAfter(60) {
                checkThat(helper.partOrNullAt(position) != null) {
                    "The motor broke down below its speed limit"
                }
            }
            .thenSucceed()
    }
}
