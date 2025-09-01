package org.eln2.mc.common.content

import net.minecraft.world.InteractionResult
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicPartVisual
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.cells.foundation.CellCreateInfo
import org.eln2.mc.common.cells.foundation.SimObject
import org.eln2.mc.common.cells.foundation.VoltageSourceObject
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.PartUseInfo
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask

class VoltageSourceCell(ci: CellCreateInfo) : Cell(ci) {
    @SimObject
    val voltageSource = VoltageSourceObject(this).also {
        it.source.potential = 1200.0
    }

    init {
        ruleSet.withDirectionRulePlanar(Base6Direction3dMask.FRONT)
    }
}

class VoltageSourcePart(ci: PartCreateInfo) : CellPart<VoltageSourceCell>(ci, Content.VOLTAGE_SOURCE_CELL.get()), ComponentDisplay, WrenchRotatablePart {
    override fun createVisual(ctx: MultipartVisualizationContext) = BasicPartVisual(ctx, this, FlwModels.VOLTAGE_SOURCE)

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
