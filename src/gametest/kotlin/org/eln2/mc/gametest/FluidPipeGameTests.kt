package org.eln2.mc.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.eln2.mc.MODID
import org.eln2.mc.common.content.fluid.FluidPipeBlockEntity
import org.eln2.mc.common.content.fluid.FluidPipeBlockEntity.ModuleType
import org.eln2.mc.common.content.fluid.FluidPipeNetwork
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.fluids.foundation.FractionalFluidStack
import org.eln2.mc.common.fluids.foundation.IFractionalFluidHandler
import org.eln2.mc.common.fluids.foundation.MultipleFluidTank
import org.eln2.mc.common.fluids.foundation.MultipleFractionalFluidTank

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object FluidPipeGameTests {
    private const val SETTLE_TICKS = 5
    private const val FLOW_TICKS = 40
    private const val AMOUNT_TOLERANCE = 1e-6

    private val WATER: Fluid get() = Fluids.WATER
    private val DIESEL: Fluid get() = Eln2ForgeFluids.DIESEL.source.get()

    private fun fractionalTank(capacity: Double, amount: Double = 0.0, fluid: Fluid = WATER) =
        MultipleFractionalFluidTank(capacity, false).also { tank ->
            if (amount > 0.0) {
                tank.fillFractional(FractionalFluidStack(fluid, amount), FluidAction.EXECUTE)
            }
        }

    private fun discreteTank(capacity: Int, amount: Int = 0) = MultipleFluidTank(capacity, false).also { tank ->
        if (amount > 0) {
            tank.fill(FluidStack(WATER, amount), FluidAction.EXECUTE)
        }
    }

    private fun GameTestHelper.pipeAt(x: Int, z: Int = 6) = getBlockEntity(arenaPosition(x, z)) as? FluidPipeBlockEntity
        ?: gameTestFail("No pipe at $x, $z")

    private fun GameTestHelper.placePipes(startX: Int, endX: Int, z: Int = 6) {
        for (x in startX..endX) {
            setBlock(arenaPosition(x, z), Eln2ForgeFluids.FLUID_PIPE_BLOCK.block.get())
        }
    }

    private fun FluidPipeBlockEntity.attach(
        handler: IFluidHandler,
        side: Direction,
        module: ModuleType = ModuleType.None
    ) {
        val endpoint = FluidPipeNetwork.Endpoint(handler, this, side)

        if (module.networkImportsFromEndpoint) {
            network.importEndpointsByPipe.getOrPut(this) { ArrayList() }.add(endpoint)
        }

        if (module.networkExportsToEndpoint) {
            network.exportEndpointsByPipe.getOrPut(this) { ArrayList() }.add(endpoint)
        }

        network.endpointsCache.clear()
        modules[side.get3DDataValue()] = module
    }

    private fun checkRate(moved: Double, label: String) {
        checkThat(moved in (FLOW_TICKS - 3.0)..(FLOW_TICKS + 3.0)) {
            "$label: expected about $FLOW_TICKS mB at 1 mB per tick, moved $moved"
        }
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun adjacentPipesShareANetworkAndSplitWhenTheMiddleBreaks(helper: GameTestHelper) {
        helper.placePipes(3, 5)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                checkThat(helper.pipeAt(3).network === helper.pipeAt(5).network) {
                    "The ends of a line of pipes should share a network"
                }

                checkThat(helper.pipeAt(3).network.pipes.size == 3) {
                    "Expected 3 pipes in the network, got ${helper.pipeAt(3).network.pipes.size}"
                }

                helper.setBlock(arenaPosition(4, 6), Blocks.AIR)

                checkThat(helper.pipeAt(3).network !== helper.pipeAt(5).network) {
                    "Removing the middle pipe should split the network"
                }

                checkThat(helper.pipeAt(3).network.pipes.size == 1 && helper.pipeAt(5).network.pipes.size == 1) {
                    "Each side should keep only its own pipe"
                }
            }
            .thenExecuteAfter(1) {
                helper.setBlock(arenaPosition(4, 6), Eln2ForgeFluids.FLUID_PIPE_BLOCK.block.get())
            }
            .thenExecuteAfter(SETTLE_TICKS) {
                checkThat(helper.pipeAt(3).network === helper.pipeAt(5).network) {
                    "Placing the pipe back should merge the networks"
                }

                checkThat(helper.pipeAt(3).network.pipes.size == 3) {
                    "Expected 3 pipes in the merged network, got ${helper.pipeAt(3).network.pipes.size}"
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fluidMovesThroughValvesAtThePumpRateAndIsConserved(helper: GameTestHelper) {
        helper.placePipes(3, 6)

        val source = fractionalTank(1000.0, 500.0)
        val destination = fractionalTank(1000.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                helper.pipeAt(3).attach(source, Direction.UP, ModuleType.ExtractionValve)
                helper.pipeAt(6).attach(destination, Direction.UP, ModuleType.InsertionValve)
            }
            .thenExecuteAfter(FLOW_TICKS) {
                checkRate(destination.amount, "Destination")
                checkClose(500.0, source.amount + destination.amount, AMOUNT_TOLERANCE, "Total fluid")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun twoPlainEndpointsTradeFluidWithoutLosingAny(helper: GameTestHelper) {
        helper.placePipes(3, 4)

        val first = fractionalTank(1000.0, 500.0)
        val second = fractionalTank(1000.0, 100.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                helper.pipeAt(3).attach(first, Direction.UP)
                helper.pipeAt(4).attach(second, Direction.UP)
            }
            .thenExecuteAfter(FLOW_TICKS) {
                checkClose(600.0, first.amount + second.amount, AMOUNT_TOLERANCE, "Total fluid")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fluidSplitsEvenlyBetweenTwoDestinations(helper: GameTestHelper) {
        helper.placePipes(3, 7)

        val source = fractionalTank(1000.0, 500.0)
        val first = fractionalTank(1000.0)
        val second = fractionalTank(1000.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                helper.pipeAt(5).attach(source, Direction.UP, ModuleType.ExtractionValve)
                helper.pipeAt(3).attach(first, Direction.UP, ModuleType.InsertionValve)
                helper.pipeAt(7).attach(second, Direction.UP, ModuleType.InsertionValve)
            }
            .thenExecuteAfter(FLOW_TICKS) {
                checkClose(first.amount, second.amount, AMOUNT_TOLERANCE, "Split between the destinations")
                checkRate(first.amount + second.amount, "Destinations together")
                checkClose(500.0, source.amount + first.amount + second.amount, AMOUNT_TOLERANCE, "Total fluid")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fullDestinationStopsTheFlowWithoutLoss(helper: GameTestHelper) {
        helper.placePipes(3, 4)

        val source = fractionalTank(1000.0, 500.0)
        val destination = fractionalTank(10.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                helper.pipeAt(3).attach(source, Direction.UP, ModuleType.ExtractionValve)
                helper.pipeAt(4).attach(destination, Direction.UP, ModuleType.InsertionValve)
            }
            .thenExecuteAfter(FLOW_TICKS) {
                checkClose(10.0, destination.amount, AMOUNT_TOLERANCE, "Destination amount")
                checkClose(490.0, source.amount, AMOUNT_TOLERANCE, "Source amount")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun aSmallTankFillsAndTheRestGoesToTheLargeTank(helper: GameTestHelper) {
        helper.placePipes(3, 7)

        val source = fractionalTank(1000.0, 500.0)
        val small = fractionalTank(3.0)
        val large = fractionalTank(1000.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                helper.pipeAt(5).attach(source, Direction.UP, ModuleType.ExtractionValve)
                helper.pipeAt(3).attach(small, Direction.UP, ModuleType.InsertionValve)
                helper.pipeAt(7).attach(large, Direction.UP, ModuleType.InsertionValve)
            }
            .thenExecuteAfter(FLOW_TICKS) {
                checkClose(3.0, small.amount, AMOUNT_TOLERANCE, "Small tank")
                checkRate(small.amount + large.amount, "Destinations together")
                checkClose(500.0, source.amount + small.amount + large.amount, AMOUNT_TOLERANCE, "Total fluid")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun aSingleEndpointDoesNotPumpIntoItself(helper: GameTestHelper) {
        helper.placePipes(3, 4)

        val tank = fractionalTank(1000.0, 500.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                helper.pipeAt(3).attach(tank, Direction.UP)
            }
            .thenExecuteAfter(10) {
                checkClose(500.0, tank.amount, AMOUNT_TOLERANCE, "Tank amount")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun discreteTanksExchangeWholeMillibuckets(helper: GameTestHelper) {
        helper.placePipes(3, 4)

        val source = discreteTank(1000, 500)
        val destination = discreteTank(1000)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                helper.pipeAt(3).attach(source, Direction.UP, ModuleType.ExtractionValve)
                helper.pipeAt(4).attach(destination, Direction.UP, ModuleType.InsertionValve)
            }
            .thenExecuteAfter(FLOW_TICKS) {
                checkRate(destination.amount.toDouble(), "Destination")

                checkThat(source.amount + destination.amount == 500) {
                    "Total fluid changed to ${source.amount + destination.amount}"
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun aDiscreteSourceFillsAFractionalDestination(helper: GameTestHelper) {
        helper.placePipes(3, 4)

        val source = discreteTank(1000, 500)
        val destination = fractionalTank(1000.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                helper.pipeAt(3).attach(source, Direction.UP, ModuleType.ExtractionValve)
                helper.pipeAt(4).attach(destination, Direction.UP, ModuleType.InsertionValve)
            }
            .thenExecuteAfter(FLOW_TICKS) {
                checkRate(destination.amount, "Destination")
                checkClose(500.0, source.amount + destination.amount, AMOUNT_TOLERANCE, "Total fluid")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun aMachineTouchingTwoPipesLosesNoFluidAndKeepsTheFullRate(helper: GameTestHelper) {
        helper.placePipes(3, 7)

        val source = fractionalTank(1000.0, 500.0)
        val twice = fractionalTank(1000.0)
        val once = fractionalTank(1000.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                helper.pipeAt(5).attach(source, Direction.UP, ModuleType.ExtractionValve)
                helper.pipeAt(3).attach(twice, Direction.UP, ModuleType.InsertionValve)
                helper.pipeAt(4).attach(twice, Direction.UP, ModuleType.InsertionValve)
                helper.pipeAt(7).attach(once, Direction.UP, ModuleType.InsertionValve)
            }
            .thenExecuteAfter(FLOW_TICKS) {
                checkClose(500.0, source.amount + twice.amount + once.amount, AMOUNT_TOLERANCE, "Total fluid")
                checkRate(twice.amount + once.amount, "Destinations together")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun insertionValveBlocksTheMachineFromPushingIntoTheNetwork(helper: GameTestHelper) {
        helper.placePipes(3, 4)

        val destination = fractionalTank(1000.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                val pipe = helper.pipeAt(3)

                pipe.attach(fractionalTank(1000.0), Direction.UP, ModuleType.InsertionValve)
                helper.pipeAt(4).attach(destination, Direction.UP, ModuleType.InsertionValve)

                val handler = pipe.handlers[Direction.UP.get3DDataValue()]
                val stack = FluidStack(WATER, 10)

                checkThat(handler.fill(stack, FluidAction.EXECUTE) == 0) {
                    "A machine behind an insertion valve should not be able to push"
                }

                checkThat(!handler.isFluidValid(0, stack)) {
                    "A machine behind an insertion valve should see the fluid as invalid"
                }

                checkClose(0.0, destination.amount, AMOUNT_TOLERANCE, "Destination amount")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun aMachinePushesIntoTheNetworkAndNothingFlowsBackIntoIt(helper: GameTestHelper) {
        helper.placePipes(3, 4)

        val pusher = fractionalTank(1000.0)
        val destination = fractionalTank(1000.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                val pipe = helper.pipeAt(3)

                pipe.attach(pusher, Direction.UP, ModuleType.ExtractionValve)
                helper.pipeAt(4).attach(destination, Direction.UP, ModuleType.InsertionValve)

                val handler = pipe.handlers[Direction.UP.get3DDataValue()]

                checkThat(handler.fill(FluidStack(WATER, 25), FluidAction.EXECUTE) == 25) {
                    "The network should accept what the machine pushes"
                }

                checkClose(25.0, destination.amount, AMOUNT_TOLERANCE, "Destination amount")
                checkClose(0.0, pusher.amount, AMOUNT_TOLERANCE, "Pusher amount")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun fluidValidityFollowsWhatTheDestinationsAccept(helper: GameTestHelper) {
        helper.placePipes(3, 4)

        val rejectsWater = object : MultipleFractionalFluidTank(1000.0, false) {
            override fun isFluidValid(tank: Int, stack: FluidStack) = stack.fluid != WATER
        }

        val acceptsEverything = fractionalTank(1000.0)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                val pipe = helper.pipeAt(3)

                pipe.attach(rejectsWater, Direction.UP, ModuleType.ExtractionValve)
                helper.pipeAt(4).attach(acceptsEverything, Direction.UP, ModuleType.InsertionValve)

                val handler = pipe.handlers[Direction.UP.get3DDataValue()]

                checkThat(handler.isFluidValid(0, FluidStack(WATER, 10))) {
                    "The insertion valve destination accepts water, so it should be valid to push"
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun aPipeDropsAModuleWhenItsMachineDisappears(helper: GameTestHelper) {
        helper.placePipes(3, 3)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                val pipe = helper.pipeAt(3)

                pipe.modules[Direction.UP.get3DDataValue()] = ModuleType.ExtractionValve
                pipe.neighborChanged()

                checkThat(pipe.modules[Direction.UP.get3DDataValue()] == ModuleType.None) {
                    "The valve should drop when there is no machine on that side"
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun pipeModulesSurviveSavingAndLoading(helper: GameTestHelper) {
        helper.placePipes(3, 3)

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                val pipe = helper.pipeAt(3)
                val index = Direction.UP.get3DDataValue()

                pipe.modules[index] = ModuleType.InsertionValve

                val tag = pipe.saveWithoutMetadata()

                pipe.modules[index] = ModuleType.None
                pipe.load(tag)

                checkThat(pipe.modules[index] == ModuleType.InsertionValve) {
                    "Expected the insertion valve after loading, got ${pipe.modules[index]}"
                }

                pipe.modules[index] = ModuleType.None
            }
            .thenSucceed()
    }

    private fun GameTestHelper.placeTank(position: BlockPos) {
        val absolute = absolutePos(position)
        val floor = absolute.below()
        val player = FakePlayerFactory.getMinecraft(level)
        val item = Eln2ForgeFluids.IRON_TANK.blockAndItem.item.get()

        setBlock(position.below(), Blocks.STONE)
        player.moveTo(absolute.x + 0.5, absolute.y + 4.0, absolute.z + 0.5, 0.0f, 90.0f)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(item))

        val hit = BlockHitResult(Vec3.atCenterOf(floor).add(0.0, 0.5, 0.0), Direction.UP, floor, false)

        item.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))

        checkThat(!getBlockState(position).isAir) {
            "Could not place an iron tank at $position"
        }
    }

    private fun GameTestHelper.tankHandler(position: BlockPos, side: Direction): IFractionalFluidHandler {
        val blockEntity = getBlockEntity(position)
            ?: gameTestFail("No tank at $position")

        val handler = blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, side).resolve()

        if (!handler.isPresent) {
            gameTestFail("The tank at $position has no fluid handler on $side")
        }

        return handler.get() as? IFractionalFluidHandler
            ?: gameTestFail("The tank handler at $position is not fractional")
    }

    private fun IFractionalFluidHandler.total(): Double {
        var result = 0.0

        for (tank in 0 until tanks) {
            result += getFractionalFluidInTank(tank).amount
        }

        return result
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    @JvmStatic
    fun pipesFindRealTanksAndMoveFluidBetweenThem(helper: GameTestHelper) {
        val sourcePosition = arenaPosition(3, 6)
        val destinationPosition = arenaPosition(6, 6)

        helper.placeTank(sourcePosition)
        helper.placeTank(destinationPosition)
        helper.setBlock(arenaPosition(4, 6), Eln2ForgeFluids.FLUID_PIPE_BLOCK.block.get())
        helper.setBlock(arenaPosition(5, 6), Eln2ForgeFluids.FLUID_PIPE_BLOCK.block.get())

        var total = 0.0

        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS) {
                val sourcePipe = helper.pipeAt(4)
                val destinationPipe = helper.pipeAt(5)

                sourcePipe.modules[Direction.WEST.get3DDataValue()] = ModuleType.ExtractionValve
                destinationPipe.modules[Direction.EAST.get3DDataValue()] = ModuleType.InsertionValve
                sourcePipe.neighborChanged()
                destinationPipe.neighborChanged()

                checkThat(sourcePipe.modules[Direction.WEST.get3DDataValue()] == ModuleType.ExtractionValve) {
                    "The pipe should see the tank and keep its extraction valve"
                }

                checkThat(destinationPipe.modules[Direction.EAST.get3DDataValue()] == ModuleType.InsertionValve) {
                    "The pipe should see the tank and keep its insertion valve"
                }

                val source = helper.tankHandler(sourcePosition, Direction.EAST)

                source.fillFractional(FractionalFluidStack(WATER, 100.0), FluidAction.EXECUTE)
                total = source.total()

                checkThat(total > 99.0) {
                    "The source tank should hold the water, it holds $total"
                }
            }
            .thenExecuteAfter(FLOW_TICKS) {
                val source = helper.tankHandler(sourcePosition, Direction.EAST)
                val destination = helper.tankHandler(destinationPosition, Direction.WEST)

                checkRate(destination.total(), "Destination tank")
                checkClose(total, source.total() + destination.total(), 1e-3, "Total fluid across real tanks")
            }
            .thenSucceed()
    }
}
