package org.eln2.mc.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.MODID
import org.eln2.mc.common.content.PhotovoltaicGeneratorCell
import org.eln2.mc.common.content.PolarLightCell
import org.eln2.mc.common.content.modules.Eln2Lights
import org.eln2.mc.common.content.modules.Eln2Solar
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.extensions.evaluateDiffuseIrradianceFactor
import org.eln2.mc.mathematics.FacingDirection
import kotlin.math.abs
import kotlin.math.max

@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object SolarGameTests {
    private const val NOON_TIME = 6000L
    private const val PANEL_OPEN_CIRCUIT_POTENTIAL = 32.0
    private const val PANEL_EFFICIENCY = 0.452515661
    private const val PANEL_AREA = 1.0
    private const val INTENSITY = 1000.0
    private const val RIG_X = 4
    private const val FIRST_RIG_Z = 3
    private const val LAST_RIG_Z = 7

    private class SolarRig(val panel: Part, val height: Int, val load: Part?) {
        val panelCell: PhotovoltaicGeneratorCell get() = panel.cellAs()
        val loadCell: PolarLightCell get() = load!!.cellAs()
    }

    private fun GameTestHelper.surfaceRigHeight(): Int {
        var surface = Int.MIN_VALUE

        for (z in FIRST_RIG_Z..LAST_RIG_Z) {
            val position = absolutePos(BlockPos(RIG_X, 0, z))
            surface = max(surface, level.getHeight(Heightmap.Types.MOTION_BLOCKING, position.x, position.z))
        }

        return surface + 3 - absolutePos(BlockPos.ZERO).y
    }

    private fun GameTestHelper.placeSolarRig(withBulb: Boolean): SolarRig {
        level.setDayTime(NOON_TIME)

        val height = surfaceRigHeight()
        val up = Direction.UP

        placePart(BlockPos(RIG_X, height, 3), GROUND_ITEM, up)
        val load = if (withBulb) {
            val light = placePart(BlockPos(RIG_X, height, 4), Eln2Lights.LIGHT_PART, up, FacingDirection.EAST)
            light.installBulb()
            light
        } else {
            placePart(BlockPos(RIG_X, height, 4), ELECTRICAL_WIRE_ITEM, up)
            null
        }

        placePart(BlockPos(RIG_X, height, 5), ELECTRICAL_WIRE_ITEM, up)
        val panel = placePart(BlockPos(RIG_X, height, 6), Eln2Solar.PHOTOVOLTAIC_PANEL_PART, up, FacingDirection.NORTH)
        placePart(BlockPos(RIG_X, height, 7), GROUND_ITEM, up)

        checkConnected(partAt(BlockPos(RIG_X, height, 5)), panel)

        return SolarRig(panel, height, load)
    }

    private fun GameTestHelper.removeSolarRig(rig: SolarRig) {
        for (z in FIRST_RIG_Z..LAST_RIG_Z) {
            val position = BlockPos(RIG_X, rig.height, z)

            if (partOrNullAt(position) != null) {
                breakPartAt(position)
            }
        }
    }

    private fun GameTestHelper.irradianceAt(rig: SolarRig) = level.evaluateDiffuseIrradianceFactor(
        Vector3d.unitY,
        absolutePos(BlockPos(RIG_X, rig.height, 6))
    )

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun sunlitPanelHoldsItsOpenCircuitPotentialAcrossAHighResistanceLoad(helper: GameTestHelper) {
        val rig = helper.placeSolarRig(withBulb = true)

        helper.startSequence()
            .thenExecuteAfter(30) {
                checkThat(helper.irradianceAt(rig) > 0.1) {
                    "The surface rig at height ${rig.height} receives no sunlight at noon"
                }

                rig.panel.graph().runSuspended {
                    val potential = abs(rig.loadCell.potential)

                    checkThat(potential in (PANEL_OPEN_CIRCUIT_POTENTIAL * 0.9)..(PANEL_OPEN_CIRCUIT_POTENTIAL * 1.01)) {
                        "Panel potential across the bulb was $potential V, expected near $PANEL_OPEN_CIRCUIT_POTENTIAL V"
                    }
                }

                helper.removeSolarRig(rig)
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun shadedPanelProducesNoPower(helper: GameTestHelper) {
        val rig = helper.placeSolarRig(withBulb = true)

        helper.setBlock(BlockPos(RIG_X, rig.height + 1, 6), Blocks.STONE)

        helper.startSequence()
            .thenExecuteAfter(30) {
                checkClose(0.0, helper.irradianceAt(rig), 1e-9, "Irradiance below a roof")

                rig.panel.graph().runSuspended {
                    checkClose(0.0, abs(rig.loadCell.potential), 0.5, "Potential from a shaded panel")
                }

                helper.removeSolarRig(rig)
            }
            .thenSucceed()
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    @JvmStatic
    fun panelDeliversTheIncidentSolarPowerIntoALowResistanceLoad(helper: GameTestHelper) {
        val rig = helper.placeSolarRig(withBulb = false)

        helper.startSequence()
            .thenExecuteAfter(40) {
                val irradiance = helper.irradianceAt(rig)

                checkThat(irradiance > 0.1) {
                    "The surface rig at height ${rig.height} receives no sunlight at noon"
                }

                val expected = INTENSITY * PANEL_AREA * irradiance * PANEL_EFFICIENCY

                rig.panel.graph().runSuspended {
                    checkClose(expected, rig.panelCell.generator.powerSource.power, expected * 0.05, "Panel power")
                }

                helper.removeSolarRig(rig)
            }
            .thenSucceed()
    }
}
