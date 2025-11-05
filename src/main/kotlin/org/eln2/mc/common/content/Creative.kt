package org.eln2.mc.common.content

import net.minecraft.world.InteractionResult
import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.classify
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicPartVisual
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.PartUseInfo
import org.eln2.mc.data.MonopoleMap
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import kotlin.math.sin

class VoltageSourceCell(
    ci: CellCreateInfo,
    override val electricalMap: MonopoleMap,
    override val electricalSize: ElectricalSize
) : Cell(ci), SidedElectricalMonoMapped<VoltageSourceCell> {
    @SimObject
    val voltageSource = VoltageSourceObject(this).also {
        it.source.potential = 240.0
    }
}

class VoltageSourcePart(ci: PartCreateInfo) : CellPart<VoltageSourceCell>(ci, Content.VOLTAGE_SOURCE_CELL.get()), ComponentDisplay, WrenchRotatable {
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
        builder.debugInIDE { "crossResistance: ${Quantity(cell.voltageSource.resistors.crossResistance, OHM).classify()}" }
        builder.quantityOutput(cell.voltageSource.source.readouts.potential)
        builder.quantityOutput(cell.voltageSource.source.readouts.current)
        builder.quantityOutput(cell.voltageSource.source.readouts.power)
    }
}
