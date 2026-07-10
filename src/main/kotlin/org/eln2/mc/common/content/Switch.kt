@file:Suppress("unused")

package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionResult
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.ClientOnly
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.partTransformation
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.events.AtomicUpdate
import org.eln2.mc.common.content.modules.Eln2BasicComponents
import org.eln2.mc.common.parts.foundation.AbstractPartVisual
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.PartUseInfo
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.PoleMap

data class SwitchOptions(
    val closedResistance: Quantity<Resistance>,
    val openResistance: Quantity<Resistance>,
    val thermalDef: ThermalMassDefinition,
    val thermalBreakdown: Quantity<Temperature>,
    val dielectricBreakdown: Quantity<Potential>
)

class SwitchCell(
    ci: CellCreateInfo,
    override val electricalMap: PoleMap,
    val options: SwitchOptions,
    val leakage: ConnectionParameters
) : Cell(ci), SidedElectricalMapped<SwitchCell> {

    companion object {
        private const val IS_CLOSED = "isClosed"
    }

    override val electricalSize: ElectricalSize
        get() = ElectricalSize.Standard

    override fun thermalObjectPredicate(remote: ThermalObject<*>) = false

    @SimObject
    val electrical = PolarResistorObject(this, electricalMap).also {
        it.component.resistance = !options.openResistance
    }

    @SimObject
    val thermal = ThermalWireObject(this, options.thermalDef(), leakage)

    @Behavior
    val thermalBreakdown = ThermalBreakdownBehavior.create(options.thermalBreakdown, this) {
        thermal.thermalBody.temperature
    }

    @Behavior
    val dielectricBreakdown = DielectricBreakdownBehavior.create(this).also {
        it.addPort(
            electrical.component,
            !options.dielectricBreakdown,
            !options.dielectricBreakdown
        )
    }

    var isClosed = false
        private set

    private val closedUpdate = AtomicUpdate<Boolean>()

    fun requestSetClosed(closed: Boolean) {
        closedUpdate.setLatest(closed)
    }

    private fun updateResistance() {
        val target = if (isClosed) {
            !options.closedResistance
        } else {
            !options.openResistance
        }

        electrical.component.updateResistance(target)
    }

    override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        subscribers.addPre(this::tickPre)
        subscribers.addPost(this::tickPost)
    }

    private fun tickPre(dt: Double, phase: SimulationPhase) {
        setChangedIf(
            closedUpdate.consume {
                isClosed = it
                updateResistance()
            }
        )
    }

    private fun tickPost(dt: Double, phase: SimulationPhase) {
        thermal.thermalBody.energy += Quantity(electrical.component.power * dt, JOULE)
    }

    override fun saveCellData() = CompoundTag().also {
        it.putBoolean(IS_CLOSED, isClosed)
    }

    override fun loadCellData(tag: CompoundTag) {
        isClosed = tag.getBoolean(IS_CLOSED)
        updateResistance()
    }
}

class SwitchPart(ci: PartCreateInfo) :
    CellPart<SwitchCell>(ci, Eln2BasicComponents.SWITCH_CELL.get()),
    ComponentDisplay {

    @ClientOnly
    var isClosed = false
        private set

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if (placement.level.isClientSide) {
            return InteractionResult.PASS
        }

        cell.requestSetClosed(!cell.isClosed)
        setSyncDirty()

        return InteractionResult.SUCCESS
    }

    @ClientOnly
    override fun handleSyncTag(tag: CompoundTag) {
        isClosed = tag.getBoolean(IS_CLOSED)
    }

    @ServerOnly
    override fun getClientSaveTag(): CompoundTag? = getSyncTag()

    @ClientOnly
    override fun loadClientSaveTag(tag: CompoundTag) = handleSyncTag(tag)

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.translateBoolean("switch_closed", cell.isClosed)
        builder.quantity(cell.thermal.thermalBody.temperature)
        builder.quantity(cell.electrical.component.readouts.potential)
        builder.quantity(cell.electrical.component.readouts.current)
        builder.quantity(cell.electrical.component.readouts.power)
    }

    companion object {
        private const val IS_CLOSED = "isClosed"
    }
}

class SwitchPartVisual(
    visualizationContext: MultipartVisualizationContext,
    part: SwitchPart
) : AbstractPartVisual<SwitchPart>(visualizationContext, part), SimpleDynamicVisual {

    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.SWITCH))
        .createInstance()
        .also {
            it.partTransformation(visualizationContext.parent, part)
        }

    var isClosed = false
        private set

    init {
        updateState()
    }

    private fun updateState() {
        isClosed = part.isClosed
    }

    override fun beginFrame(ctx: DynamicVisual.Context?) {
        if (isClosed != part.isClosed) {
            updateState()
        }
    }

    override fun updateLight(partialTick: Float) {
        visualizationContext.parent.relightInstances(body)
    }

    override fun _delete() {
        body.delete()
    }
}
