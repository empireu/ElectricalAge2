package org.eln2.mc.common.content

import net.minecraft.world.InteractionResult
import org.ageseries.libage.sim.electrical.mna.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.component.VoltageSource
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.BasicPartRenderer
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.PartUseInfo
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask

/**
 * The voltage source object has a bundle of resistors, whose External Pins are exported to other objects, and
 * a voltage source, connected to the Internal Pins of the bundle.
 * */
class VoltageSourceObject(cell: Cell) : ElectricalObject<Cell>(cell) {
    val source = VoltageSource()
    val resistors = resistorBundle(1e-4)

    override fun offerComponent(remote: ElectricalObject<*>): ElectricalComponentInfo {
        return resistors.getOfferedResistor(remote)
    }

    override fun clearComponents() {
        resistors.clear()
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(source)
        resistors.addComponents(connections, circuit)
    }

    override fun build(map: ElectricalConnectivityMap) {
        source.ground(INTERNAL_PIN)
        resistors.build(connections, this, map)

        resistors.forEach {
            map.connect(it, INTERNAL_PIN, source, EXTERNAL_PIN)
        }
    }
}

class VoltageSourceCell(ci: CellCreateInfo) : Cell(ci) {
    @SimObject
    val voltageSource = VoltageSourceObject(this).also {
        it.source.potential = 1200.0
    }

    init {
        ruleSet.withDirectionRulePlanar(Base6Direction3dMask.FRONT)
    }
}

class VoltageSourcePart(ci: PartCreateInfo) : CellPart<VoltageSourceCell, BasicPartRenderer>(ci, Content.VOLTAGE_SOURCE_CELL.get()), ComponentDisplay {
    override fun createRenderer() = BasicPartRenderer(this, PartialModels.VOLTAGE_SOURCE)

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if(placement.level.isClientSide) {
            return InteractionResult.PASS
        }

        val increment = if(context.player.isShiftKeyDown) {
            -10.0
        }
        else {
            10.0
        }

        cell.voltageSource.source.potential = (cell.voltageSource.source.potential + increment).coerceIn(0.0, 5000.0)

        return InteractionResult.SUCCESS
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.resistance(cell.voltageSource.resistors.crossResistance)
        builder.power(cell.voltageSource.source.power)
        builder.potential(cell.voltageSource.source.potential)
        builder.current(cell.voltageSource.source.current)
    }
}
