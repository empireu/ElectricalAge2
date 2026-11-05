package org.eln2.mc.gametest

import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.OptionalDouble
import org.ageseries.libage.data.Quantity
import org.eln2.mc.MODID
import org.eln2.mc.common.content.SteamExpansion
import org.eln2.mc.common.content.SteamProperties
import org.eln2.mc.common.content.SteamTurbineBlockEntity
import org.eln2.mc.common.content.SteamTurbineCell
import org.eln2.mc.common.content.SteamTurbineKineticPortCell
import org.eln2.mc.common.content.SteamTurbineThermalPortCell
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.content.modules.Eln2SteamTurbine
import org.eln2.mc.common.fluids.foundation.FractionalFluidStack
import org.eln2.mc.common.cells.foundation.CellGraph
import kotlin.math.abs

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object SteamTurbineGameTests {
    private const val HOTTEST_TANK_STEAM = 773.15
    private const val FULL_INPUT = 12800.0
    private const val EXPANSION_FACTOR = 100.0

    private val MODEL get() = Eln2SteamTurbine.STEAM_TURBINE_MODEL
    private val STEAM get() = Eln2ForgeFluids.STEAM.get()
    private val POSITION = arenaPosition(6, 6)

    private fun GameTestHelper.placeTurbine(): SteamTurbineBlockEntity {
        val absolute = absolutePos(POSITION)
        val floor = absolute.below()
        val player = FakePlayerFactory.getMinecraft(level)
        val item = Eln2SteamTurbine.STEAM_TURBINE_BLOCK_ITEM.get()

        setBlock(POSITION.below(), Blocks.STONE)
        player.moveTo(absolute.x + 0.5, absolute.y + 4.0, absolute.z + 0.5, 0.0f, 90.0f)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(item))

        val hit = BlockHitResult(Vec3.atCenterOf(floor).add(0.0, 0.5, 0.0), Direction.UP, floor, false)

        item.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))

        return getBlockEntity(POSITION) as? SteamTurbineBlockEntity
            ?: gameTestFail("Could not place a turbine at $POSITION")
    }

    private fun <T> SteamTurbineBlockEntity.read(reader: SteamTurbineCell.() -> T): T = cell.graph.runSuspended {
        cell.reader()
    }

    private fun SteamTurbineBlockEntity.fillSteam(amount: Double, temperature: Double) {
        cell.graph.runSuspended {
            fluidHandler.inputHandler.fillThermal(
                FractionalFluidStack(STEAM, amount),
                OptionalDouble.wrap(temperature),
                IFluidHandler.FluidAction.EXECUTE
            )
        }
    }

    private fun SteamTurbineCell.kineticPorts() = connections.filterIsInstance<SteamTurbineKineticPortCell>()

    private fun SteamTurbineCell.thermalPorts() = connections.filterIsInstance<SteamTurbineThermalPortCell>()

    private fun SteamTurbineCell.expectedPortSpeed(port: SteamTurbineKineticPortCell): Double {
        val shaftEnd = kinetic.offerExtension(port.kinetic) ?: gameTestFail("The shaft does not reach the port")
        val portEnd = port.kinetic.offerExtension(kinetic) ?: gameTestFail("The port does not reach the shaft")

        return -shaftEnd.pole * portEnd.pole * kinetic.node.angularVelocity
    }

    private fun SteamTurbineCell.shaftEnergy() =
        kinetic.node.kineticEnergy + kineticPorts().sumOf { it.kinetic.node.kineticEnergy }

    private fun SteamTurbineCell.thermalEnergy() =
        (thermal.simulation ?: gameTestFail("The thermal object has no simulation")).masses.sumOf { !it.energy }

    private fun SteamTurbineCell.steamConsumedSoFar() =
        FULL_INPUT - (fluidHandler() ?: gameTestFail("The cell has no fluid handler")).inputSteam + deltaSteamConsumed

    private fun fullExpansion(properties: SteamProperties, coldTemperature: Double, angularVelocity: Double) =
        SteamExpansion.calculate(
            MODEL, properties, FULL_INPUT, HOTTEST_TANK_STEAM, coldTemperature, angularVelocity, 1.0, CellGraph.DT
        ) ?: gameTestFail("The steam did not expand")

    private fun GameTestHelper.checkEnergyIsAccountedFor(initialVelocity: Double) {
        val turbine = placeTurbine()

        turbine.read {
            kinetic.node.angularVelocity = initialVelocity
            kineticPorts().forEach { it.kinetic.node.angularVelocity = expectedPortSpeed(it) }
        }

        val energyBefore = turbine.read { thermalEnergy() + shaftEnergy() }
        val coldBefore = turbine.read { !thermal.thermalBody.temperature }

        turbine.fillSteam(FULL_INPUT, HOTTEST_TANK_STEAM)

        startSequence()
            .thenExecuteAfter(10) {
                val properties = SteamProperties.load() ?: gameTestFail("Steam has no properties")

                turbine.read {
                    val consumed = steamConsumedSoFar()
                    val cold = 0.5 * (coldBefore + !thermal.thermalBody.temperature)
                    val exhaust = properties.exhaustEnthalpy(cold, !MODEL.condensationBand)
                    val released = consumed * (properties.steamEnthalpy(HOTTEST_TANK_STEAM) - exhaust)
                    val gained = thermalEnergy() + shaftEnergy() - energyBefore

                    checkThat(consumed > 0.0) {
                        "The turbine consumed no steam"
                    }

                    checkClose(released, gained, released * 0.01, "Energy gained by the turbine")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun steamPropertiesMatchTheDataFiles(helper: GameTestHelper) {
        val properties = SteamProperties.load() ?: gameTestFail("Steam has no properties")

        checkClose(2.0, properties.steamHeatCapacity, 1e-9, "Steam heat capacity per gas mB")
        checkClose(4.186, properties.waterHeatCapacity, 1e-9, "Water heat capacity per gas mB")
        checkClose(2260.0, properties.latentHeat, 1e-9, "Latent heat per gas mB")
        checkClose(373.15, properties.boilingPoint, 1e-9, "Boiling point")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun hottestTankSteamReachesTheRating(helper: GameTestHelper) {
        val properties = SteamProperties.load() ?: gameTestFail("Steam has no properties")
        val expansion = fullExpansion(properties, 320.0, !MODEL.referenceAngularVelocity)
        val rejected = expansion.thermalPower - expansion.mechanicalPower

        checkClose(!MODEL.maxPower, expansion.mechanicalPower, !MODEL.maxPower * 0.05, "Power at the design point")
        checkClose(!MODEL.maxRejectedHeat, rejected, !MODEL.maxRejectedHeat * 0.05, "Heat at the design point")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun shaftFrictionAtTheReferenceSpeedIsASmallPartOfTheRating(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()
        val node = turbine.cell.kinetic.node
        val reference = !MODEL.referenceAngularVelocity
        val frictionPower = (node.viscousDamping * reference + node.coulombFriction) * reference

        checkThat(frictionPower < 0.02 * !MODEL.maxPower) {
            "The shaft loses $frictionPower W at its reference speed"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun coldSideTakesOverAMinuteToReachBoilingAtFullFlow(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()
        val properties = SteamProperties.load() ?: gameTestFail("Steam has no properties")
        val ambient = !turbine.cell.environmentData.ambientTemperature
        val heatCapacity = turbine.read { !thermal.thermalBody.energy / !thermal.thermalBody.temperature }
        val expansion = fullExpansion(properties, ambient, !MODEL.referenceAngularVelocity)
        val wasteHeat = expansion.thermalPower - expansion.mechanicalPower
        val warmUpTime = heatCapacity * (properties.boilingPoint - ambient) / wasteHeat

        checkThat(warmUpTime > 60.0) {
            "The cold side of $heatCapacity J/K reaches boiling from $ambient K in $warmUpTime s under $wasteHeat W"
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun turbineWithoutSteamStaysAtRest(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()

        helper.startSequence()
            .thenExecuteAfter(20) {
                checkClose(0.0, turbine.read { kinetic.node.angularVelocity }, 1e-9, "Shaft speed")
                checkClose(0.0, turbine.fluidHandler.outputWater, 1e-9, "Water produced")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun steamIsConsumedAtTheExpectedFlowAndCondensesIntoWater(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()

        turbine.fillSteam(FULL_INPUT, 600.0)

        var steamBefore = 0.0
        var tickBefore = 0L

        helper.startSequence()
            .thenExecuteAfter(5) {
                steamBefore = turbine.fluidHandler.inputSteam
                tickBefore = helper.tick
            }
            .thenExecuteAfter(20) {
                val properties = SteamProperties.load() ?: gameTestFail("Steam has no properties")
                val handler = turbine.fluidHandler
                val elapsed = (helper.tick - tickBefore) * SECONDS_PER_TICK
                val consumedTotal = FULL_INPUT - handler.inputSteam

                val expected = turbine.read {
                    SteamExpansion.calculate(
                        MODEL,
                        properties,
                        FULL_INPUT,
                        600.0,
                        !thermal.thermalBody.temperature,
                        kinetic.node.angularVelocity,
                        1.0,
                        CellGraph.DT
                    )
                } ?: gameTestFail("The steam did not expand")

                checkClose(expected.flow * elapsed, steamBefore - handler.inputSteam, 2.0, "Steam consumed")
                checkClose(consumedTotal / EXPANSION_FACTOR, handler.outputWater, 1e-6, "Water produced")
                checkClose(600.0, handler.inputTemperature, 1e-6, "Input temperature")
                checkClose(0.0, handler.outputSteam, 1e-9, "Exhaust steam")

                checkThat(turbine.read { kinetic.node.angularVelocity } > 0.0) {
                    "The steam did not spin the shaft"
                }

                checkThat(abs(handler.outputTemperature - turbine.read { !thermal.thermalBody.temperature }) < 2.0) {
                    "The water left at ${handler.outputTemperature} K, not at the temperature of the cold side"
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun turbineCannotSpendMoreSteamThanItHas(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()

        turbine.fillSteam(1.0, 600.0)

        helper.startSequence()
            .thenExecuteAfter(20) {
                checkClose(0.0, turbine.fluidHandler.inputSteam, 1e-3, "Steam left")
                checkClose(1.0 / EXPANSION_FACTOR, turbine.fluidHandler.outputWater, 1e-6, "Water produced")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun spinUpFollowsTheScaledCarnotEfficiency(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()

        turbine.fillSteam(FULL_INPUT, HOTTEST_TANK_STEAM)

        var energyBefore = 0.0
        var frictionBefore = 0.0
        var steamBefore = 0.0

        fun SteamTurbineCell.frictionPower() = abs(kinetic.node.frictionTorque * kinetic.node.angularVelocity)

        helper.startSequence()
            .thenExecuteAfter(5) {
                turbine.read {
                    energyBefore = shaftEnergy()
                    frictionBefore = frictionPower()
                    steamBefore = steamConsumedSoFar()
                }
            }
            .thenExecuteAfter(20) {
                val properties = SteamProperties.load() ?: gameTestFail("Steam has no properties")

                turbine.read {
                    val cold = !thermal.thermalBody.temperature
                    val expected = fullExpansion(properties, cold, kinetic.node.angularVelocity)
                    val elapsed = (steamConsumedSoFar() - steamBefore) / expected.flow
                    val energyGain = shaftEnergy() - energyBefore
                    val friction = 0.5 * (frictionBefore + frictionPower())

                    checkClose(
                        expected.mechanicalPower,
                        energyGain / elapsed + friction,
                        expected.mechanicalPower * 0.03,
                        "Shaft power"
                    )
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun energyOfTheSteamIsAccountedForFromRest(helper: GameTestHelper) {
        helper.checkEnergyIsAccountedFor(0.0)
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun energyOfTheSteamIsAccountedForWhileSpinningBackwards(helper: GameTestHelper) {
        helper.checkEnergyIsAccountedFor(-50.0)
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun heatReachesTheThermalPortsAndTheShaftReachesTheKineticPorts(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()
        val ambient = !turbine.cell.environmentData.ambientTemperature

        turbine.fillSteam(FULL_INPUT, HOTTEST_TANK_STEAM)

        helper.startSequence()
            .thenExecuteAfter(40) {
                turbine.read {
                    val thermalPorts = thermalPorts()
                    val kineticPorts = kineticPorts()

                    checkThat(thermalPorts.size == 2 && kineticPorts.size == 2) {
                        "Expected 2 thermal and 2 kinetic ports, got ${thermalPorts.size} and ${kineticPorts.size}"
                    }

                    checkThat(!thermal.thermalBody.temperature > ambient + 0.5) {
                        "The cold side did not warm up"
                    }

                    thermalPorts.forEach {
                        checkThat(!it.thermalWire.thermalBody.temperature > ambient + 0.2) {
                            "The thermal port did not warm up"
                        }
                    }

                    kineticPorts.forEach {
                        checkClose(expectedPortSpeed(it), it.kinetic.node.angularVelocity, 0.1, "Port speed")
                    }

                    checkClose(0.0, kineticPorts.sumOf { it.kinetic.node.angularVelocity }, 0.1, "Ports on both ends")
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun hotColdSideExhaustsSteamInsteadOfWater(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()

        turbine.read {
            thermal.thermalBody.temperature = Quantity(420.0, KELVIN)
        }

        turbine.fillSteam(FULL_INPUT, HOTTEST_TANK_STEAM)

        helper.startSequence()
            .thenExecuteAfter(10) {
                val handler = turbine.fluidHandler

                checkClose(FULL_INPUT - handler.inputSteam, handler.outputSteam, 1e-6, "Exhaust steam")
                checkClose(0.0, handler.outputWater, 1e-9, "Water produced")
            }
            .thenSucceed()
    }

    private fun GameTestHelper.checkTurbineIsDestroyed() {
        succeedWhen {
            checkThat(getBlockState(POSITION).isAir) {
                "The turbine is still standing"
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 300)
    @JvmStatic
    fun turbineBreaksDownWhenSpunPastItsLimit(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()
        val floor = POSITION.below()

        helper.setBlock(floor, Blocks.DIRT)

        turbine.read {
            kinetic.node.angularVelocity = !MODEL.breakdownAngularVelocity * 1.5
        }

        helper.succeedWhen {
            checkThat(helper.getBlockState(POSITION).isAir) {
                "The turbine is still standing"
            }

            checkThat(helper.getBlockState(floor).isAir) {
                "The breakdown did not blow up the floor"
            }
        }
    }

    @GameTest(template = ARENA, timeoutTicks = 300)
    @JvmStatic
    fun turbineBreaksDownWhenTheColdSideOverheats(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()

        turbine.read {
            thermal.thermalBody.temperature = MODEL.breakdownTemperature + Quantity(50.0, KELVIN)
        }

        helper.checkTurbineIsDestroyed()
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun turbineWaitsForItsFullOutputToBeDrained(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()
        val handler = turbine.fluidHandler
        val capacity = SteamTurbineBlockEntity.SteamTurbineFluidHandler.OUTPUT_WATER_CAPACITY

        handler.outputWater = capacity
        handler.outputFluidEnergy = capacity * handler.waterCp * 300.0

        turbine.fillSteam(FULL_INPUT, HOTTEST_TANK_STEAM)

        helper.startSequence()
            .thenExecuteAfter(40) {
                checkThat(!helper.getBlockState(POSITION).isAir) {
                    "The turbine did not survive a full output"
                }

                checkClose(FULL_INPUT, handler.inputSteam, 1e-9, "Steam used while the output is full")
                checkClose(capacity, handler.outputWater, 1e-9, "Water in the full output")

                handler.outputHandler.drainThermal(capacity, IFluidHandler.FluidAction.EXECUTE)
            }
            .thenExecuteAfter(20) {
                checkThat(handler.inputSteam < FULL_INPUT) {
                    "The turbine did not resume once its output was drained"
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 500)
    @JvmStatic
    fun unloadedTurbineHoldsItsSpeedBelowTheGovernorLimit(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()

        turbine.fillSteam(FULL_INPUT, HOTTEST_TANK_STEAM)

        var previousSpeed = 0.0

        val sequence = helper.startSequence().thenIdle(100)

        repeat(30) {
            sequence.thenExecuteAfter(10) {
                val speed = turbine.read { kinetic.node.angularVelocity }

                checkThat(speed >= previousSpeed - 1e-3) {
                    "The governor oscillates, the shaft slowed from $previousSpeed to $speed rad/s"
                }

                checkThat(speed < !MODEL.governorClosedAngularVelocity) {
                    "The shaft passed the governor limit at $speed rad/s"
                }

                previousSpeed = speed
            }
        }

        sequence
            .thenExecute {
                checkThat(previousSpeed > !MODEL.governorOpenAngularVelocity) {
                    "The unloaded shaft only reached $previousSpeed rad/s"
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun steamBelowTheBoilingPointDoesNotRunTheTurbine(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()

        turbine.fillSteam(FULL_INPUT, 320.0)

        helper.startSequence()
            .thenExecuteAfter(20) {
                checkClose(FULL_INPUT, turbine.fluidHandler.inputSteam, 1e-9, "Steam used")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun steamWithoutATemperatureArrivesAtTheBoilingPoint(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()
        val handler = turbine.fluidHandler
        val properties = SteamProperties.load() ?: gameTestFail("Steam has no properties")

        turbine.cell.graph.runSuspended {
            handler.inputHandler.fill(FluidStack(STEAM, 1000), IFluidHandler.FluidAction.EXECUTE)
        }

        checkClose(properties.boilingPoint, handler.inputTemperature, 1e-9, "Temperature of the steam")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fluidHandlersOnlyAcceptWhatTheyShould(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()
        val handler = turbine.fluidHandler

        checkClose(
            0.0,
            handler.inputHandler.fillFractional(
                FractionalFluidStack(Fluids.WATER, 10.0),
                IFluidHandler.FluidAction.EXECUTE
            ),
            1e-9,
            "Water accepted by the input"
        )

        checkThat(handler.outputHandler.fill(FluidStack(STEAM, 10), IFluidHandler.FluidAction.EXECUTE) == 0) {
            "The output accepted fluid"
        }

        turbine.fillSteam(FULL_INPUT * 2.0, 600.0)

        checkClose(FULL_INPUT, handler.inputSteam, 1e-9, "Input is limited by its capacity")

        handler.outputWater = 10.0
        handler.outputFluidEnergy = 10.0 * handler.waterCp * 320.0

        val drained = handler.outputHandler.drainThermal(4.0, IFluidHandler.FluidAction.EXECUTE)
            ?: gameTestFail("Nothing drained from the output")

        checkClose(4.0, drained.packet.amount, 1e-9, "Water drained")
        checkClose(320.0, drained.temperature, 1e-9, "Temperature of the drained water")
        checkClose(6.0, handler.outputWater, 1e-9, "Water left")
        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fluidHandlerKeepsItsContentsWhenSaved(helper: GameTestHelper) {
        val turbine = helper.placeTurbine()
        val handler = turbine.fluidHandler

        handler.inputSteam = 100.0
        handler.inputFluidEnergy = 100.0 * handler.steamCp * 500.0
        handler.outputWater = 2.0
        handler.outputSteam = 30.0
        handler.outputFluidEnergy = 1234.0

        val tag = CompoundTag()

        handler.saveNbt(tag)

        handler.inputSteam = 0.0
        handler.inputFluidEnergy = 0.0
        handler.outputWater = 0.0
        handler.outputSteam = 0.0
        handler.outputFluidEnergy = 0.0

        handler.loadNbt(tag)

        checkClose(100.0, handler.inputSteam, 1e-9, "Input steam")
        checkClose(500.0, handler.inputTemperature, 1e-9, "Input temperature")
        checkClose(2.0, handler.outputWater, 1e-9, "Output water")
        checkClose(30.0, handler.outputSteam, 1e-9, "Output steam")
        checkClose(1234.0, handler.outputFluidEnergy, 1e-9, "Output energy")
        helper.succeed()
    }
}
