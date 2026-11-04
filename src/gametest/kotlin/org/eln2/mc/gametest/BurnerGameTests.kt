package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.Quantity
import org.eln2.mc.MODID
import org.eln2.mc.common.content.CoalGradeRegistry
import org.eln2.mc.common.content.PrimitiveBurnerBlockEntity
import org.eln2.mc.common.content.PrimitiveBurnerCell
import org.eln2.mc.common.content.modules.Eln2HeatGenerators

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object BurnerGameTests {
    private fun GameTestHelper.placeBurner(): PrimitiveBurnerBlockEntity {
        val position = arenaPosition(6, 6)

        setBlock(position.below(), Blocks.STONE)
        setBlock(position, Eln2HeatGenerators.PRIMITIVE_BURNER_BLOCK.block.get())

        val blockEntity = getBlockEntity(position) as? PrimitiveBurnerBlockEntity
            ?: gameTestFail("No burner block entity at $position")

        blockEntity.setPlacedBy(level, Eln2HeatGenerators.PRIMITIVE_BURNER_CELL.get())

        return blockEntity
    }

    private fun PrimitiveBurnerBlockEntity.hullTemperature() = cell.graph.runSuspended {
        !cell.hull.thermalBody.temperature
    }

    private fun PrimitiveBurnerBlockEntity.coalMass() = cell.graph.runSuspended {
        cell.totalMass
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun burnerWithoutFuelStaysAtAmbientTemperature(helper: GameTestHelper) {
        val burner = helper.placeBurner()
        val ambient = !burner.cell.environmentData.ambientTemperature

        helper.startSequence()
            .thenExecuteAfter(40) {
                checkClose(ambient, burner.hullTemperature(), 0.05, "Hull temperature of an idle burner")
                checkClose(0.0, burner.coalMass(), 1e-9, "Coal mass of an empty burner")
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 400)
    @JvmStatic
    fun ignitedBurnerConsumesCoalAndWarmsItsHull(helper: GameTestHelper) {
        val burner = helper.placeBurner()
        val cell: PrimitiveBurnerCell = burner.cell
        val ambient = !cell.environmentData.ambientTemperature
        var initialMass = 0.0

        cell.graph.runSuspended {
            cell.controlParameter = 1.0
            cell.addCoal(CoalGradeRegistry.DEFAULT, Quantity(1.0, KILOGRAM))
            cell.ignite()
        }

        helper.startSequence()
            .thenExecuteAfter(2) {
                initialMass = burner.coalMass()

                checkClose(1.0, initialMass, 0.01, "Coal mass after adding one kilogram")
            }
            .thenExecuteAfter(300) {
                val finalMass = burner.coalMass()
                val temperature = burner.hullTemperature()

                checkThat(finalMass < initialMass) {
                    "The burning coal was not consumed, mass went from $initialMass to $finalMass kg"
                }

                checkThat(temperature > ambient + 2.0) {
                    "The hull did not warm up, temperature $temperature K, ambient $ambient K"
                }
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun burnerRefusesCoalBeyondItsCapacity(helper: GameTestHelper) {
        val burner = helper.placeBurner()
        val cell: PrimitiveBurnerCell = burner.cell

        cell.graph.runSuspended {
            cell.addCoal(CoalGradeRegistry.DEFAULT, Quantity(9.5, KILOGRAM))
        }

        helper.startSequence()
            .thenExecuteAfter(2) {
                cell.graph.runSuspended {
                    checkThat(cell.canAddCoal(Quantity(0.25, KILOGRAM))) {
                        "A burner holding 9.5 kg of a 10 kg capacity should accept 0.25 kg"
                    }

                    checkThat(!cell.canAddCoal(Quantity(1.0, KILOGRAM))) {
                        "A burner holding 9.5 kg of a 10 kg capacity should refuse 1 kg"
                    }
                }
            }
            .thenSucceed()
    }
}
