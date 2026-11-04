package org.eln2.mc.gametest

import net.minecraft.gametest.framework.GameTestHelper
import org.eln2.mc.common.content.PolarLightCell
import org.eln2.mc.common.content.VoltageSourceCell
import org.eln2.mc.common.content.modules.Eln2BasicComponents
import org.eln2.mc.common.content.modules.Eln2Lights
import org.eln2.mc.common.content.modules.Eln2Wires
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.mathematics.FacingDirection

const val VOLTAGE_SOURCE_RESISTANCE = 1e-4
const val GROUND_RESISTANCE = 1e-5
const val ELECTRICAL_WIRE_RESISTANCE = 2.14e-5
const val SIGNAL_SERIES_OHMS = 1e6
const val SIGNAL_INPUT_OHMS = 1e8
const val BULB_RESISTANCE = 240.0 * 240.0 / 100.0

val ELECTRICAL_WIRE_ITEM get() = Eln2Wires.STANDARD_INSULATED_COPPER_ELECTRICAL_WIRE.part
val SIGNAL_WIRE_ITEM get() = Eln2Wires.SIGNAL_WIRE.part
val VOLTAGE_SOURCE_ITEM get() = Eln2BasicComponents.VOLTAGE_SOURCE_PART
val GROUND_ITEM get() = Eln2BasicComponents.GROUND_PART

fun signalAtInput(signal: Double) = signal * SIGNAL_INPUT_OHMS / (SIGNAL_INPUT_OHMS + SIGNAL_SERIES_OHMS)

fun Part.setSourcePotential(potential: Double) {
    graph().runSuspended {
        cellAs<VoltageSourceCell>().voltageSource.source.potential = potential
    }
}

fun Part.installBulb() {
    val lightCell = cellAs<PolarLightCell>()
    val bulb = Eln2Lights.LIGHT_BULB_240V_100W.get()

    lightCell.graph.runSuspended {
        lightCell.lightBulb = bulb
        lightCell.life = 1.0
    }
}

class LoadCircuit(val load: Part, val loadCell: PolarLightCell)

fun GameTestHelper.placeBulbLoad(x: Int, z: Int): LoadCircuit {
    val load = placePart(x, z, Eln2Lights.LIGHT_PART, FacingDirection.EAST)
    val wire = placePart(x, z + 1, ELECTRICAL_WIRE_ITEM)
    val ground = placePart(x, z - 1, GROUND_ITEM)

    checkConnected(wire, load)
    checkConnected(load, ground)

    load.installBulb()

    return LoadCircuit(load, load.cellAs())
}
