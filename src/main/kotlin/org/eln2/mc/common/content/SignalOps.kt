@file:Suppress("unused")

package org.eln2.mc.common.content

import dev.engine_room.flywheel.lib.model.baked.PartialModel
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.context.UseOnContext
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.electrical.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.ElectricalPin
import org.ageseries.libage.sim.electrical.Resistor
import org.eln2.mc.ClientOnly
import org.eln2.mc.CrossThreadAccess
import org.eln2.mc.MonopoleMap
import org.eln2.mc.ServerOnly
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridMaterialCategory
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.GridCellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.PartUseInfo
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3d

/**
 * Opamp that computes output = gain * (inputA +/- inputB).
 * Right-click toggles between addition and subtraction.
 * Inputs are read via high-resistance resistors (potential probes).
 * Output is a [SignalSource].
 * */
class SignalOpAmpElectricalObject(
    cell: SignalOpAmpCell,
    val inputAMap: MonopoleMap,
    val inputBMap: MonopoleMap,
    val outputMap: MonopoleMap
) : ElectricalObject<SignalOpAmpCell>(cell) {
    val inputAResistor = Resistor().also { it.resistance = SIGNAL_SERIES_RESISTANCE }
    val inputBResistor = Resistor().also { it.resistance = SIGNAL_SERIES_RESISTANCE }
    val signalSource = SignalSource()

    override fun offerPolar(remote: ElectricalObject<*>): ElectricalPin? {
        if(inputAMap.evaluates(cell, remote.cell)) {
            return inputAResistor.positive
        }

        if(inputBMap.evaluates(cell, remote.cell)) {
            return inputBResistor.positive
        }

        if(outputMap.evaluates(cell, remote.cell)) {
            return signalSource.offerOutput()
        }

        return null
    }

    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo): ElectricalPin? {
        return when(m0.terminal) {
            cell.inputATerminal -> inputAResistor.positive
            cell.inputBTerminal -> inputBResistor.positive
            cell.outputTerminal -> signalSource.offerOutput()
            else -> null
        }
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(inputAResistor)
        circuit.add(inputBResistor)
        circuit.add(signalSource)
    }

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)
        map.ground(inputAResistor.negative)
        map.ground(inputBResistor.negative)
        signalSource.build(map)
    }

    override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        subscribers.addPost(this::tick)
    }

    fun tick(dt: Double, phase: SimulationPhase) {
        val inputA = inputAResistor.potential
        val inputB = inputBResistor.potential
        val combined = if(cell.subtractMode) inputA - inputB else inputA + inputB
        val output = (combined * cell.gain).coerceIn(-MAX_SIGNAL, MAX_SIGNAL)
        signalSource.signal = output
    }
}

class SignalOpAmpCell(
    ci: CellCreateInfo,
    inputAMap: MonopoleMap,
    inputBMap: MonopoleMap,
    outputMap: MonopoleMap,
    val inputATerminal: Int = 0,
    val inputBTerminal: Int = 1,
    val outputTerminal: Int = 2
) : Cell(ci), SidedElectrical<SignalOpAmpCell> {
    companion object {
        private const val GAIN = "gain"
        private const val SUBTRACT_MODE = "subtractMode"
    }

    var gain = 1.0
        set(value) {
            field = value.coerceIn(0.0, 10.0)
            setChanged()
        }

    @CrossThreadAccess
    var subtractMode = false
        set(value) {
            field = value
            setChanged()
        }

    @SimObject
    val opamp = SignalOpAmpElectricalObject(this, inputAMap, inputBMap, outputMap)

    @Node
    val grid = GridNode(this)

    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell): ElectricalSize? {
        if(opamp.inputAMap.evaluates(this, targetCell) ||
            opamp.inputBMap.evaluates(this, targetCell) ||
            opamp.outputMap.evaluates(this, targetCell)) {
            return ElectricalSize.Signal
        }

        return null
    }

    override fun saveCellData() = CompoundTag().also {
        it.putDouble(GAIN, gain)
        it.putBoolean(SUBTRACT_MODE, subtractMode)
    }

    override fun loadCellData(tag: CompoundTag) {
        gain = tag.getDouble(GAIN)
        subtractMode = tag.getBoolean(SUBTRACT_MODE)
    }

    fun submitDisplay(builder: ComponentDisplayList) {
        builder.translateRow("opamp_mode", if(subtractMode) "A - B" else "A + B")
        builder.translateRow("opamp_gain", String.format("%.3f", gain))
        builder.signalInput(opamp.inputAResistor.potential)
        builder.signalInput(opamp.inputBResistor.potential)
        builder.signalOutput(opamp.signalSource.signal)
    }
}

class SignalOpAmpPart(
    ci: PartCreateInfo,
    val body: PartialModel,
    val models: Map<Base6Direction3d, WireConnectionModelPartial>,
    provider: CellProvider<SignalOpAmpCell>
) :
    GridCellPart<SignalOpAmpCell>(ci, provider),
    ComponentDisplay,
    PartWithKnobs,
    ScrewdriverScrollable,
    ScrewdriverInteractable,
    ConnectedPart
{

    override val knobMap = KnobMap(this::onKnobMapChanged)

    val knobGain = knobMap.addKnobBB(
        this,
        FlwModels.POTENTIAL_PROBE_KNOB_INPUT_RANGE_MIN,
        Vector3d.unitY,
        "knob_gain",
        6.35, 2.025, 5.125,
        0.325, 0.45, 0.325
    ).configure { setLimits(0.0, 10.0); makeInteractable("waila.eln2.opamp_gain") }

    val inputATerminal = defineCellBoxTerminalBB(
        10.6, 0.775, 5.85,
        0.3, 0.55, 0.3,
        highlightColor = MyColor.RED,
        categories = listOf(GridMaterialCategory.SignalGrid)
    )

    val inputBTerminal = defineCellBoxTerminalBB(
        10.6, 0.775, 7.85,
        0.3, 0.55, 0.3,
        highlightColor = MyColor.GREEN,
        categories = listOf(GridMaterialCategory.SignalGrid)
    )

    val outputTerminal = defineCellBoxTerminalBB(
        10.6, 0.775, 9.85,
        0.3, 0.55, 0.3,
        highlightColor = MyColor.BLUE,
        categories = listOf(GridMaterialCategory.SignalGrid)
    )

    @ClientOnly
    var subtractMode = false
        private set

    @ClientOnly
    private var renderStateImpl = ConnectedPartRenderStateImpl.createIfApplicable(this)

    @ClientOnly
    override val connectedRenderState: ConnectedPartRenderState get() = renderStateImpl!!

    override fun getSyncTag() = ConnectedPart.pack(this).also {
        it.putBoolean(SUBTRACT_MODE, cell.subtractMode)
    }

    @ClientOnly
    override fun handleSyncTag(tag: CompoundTag) {
        renderStateImpl!!.set(getConnectedPartsFromTag(tag))
        subtractMode = tag.getBoolean(SUBTRACT_MODE)
    }

    override fun onConnectivityChanged() = this.setSyncDirty()

    override fun createVisual(ctx: MultipartVisualizationContext) = ConnectedPartWithKnobsVisual(
        ctx, this, body, models
    )

    override fun onCellAcquired() {
        knobMap.loadChanges {
            knobGain.rotation = cell.gain
        }
    }

    private fun onKnobMapChanged() {
        if(!placement.level.isClientSide) {
            if(hasCell) {
                cell.gain = knobGain.rotation
                sendBulkPacket(KnobMap.SyncPacket::serialize, knobMap.getSyncPacket())
            }
        }
    }

    @ServerOnly
    override fun onSyncSuggested() {
        super.onSyncSuggested()
        sendBulkPacket(KnobMap.SyncPacket::serialize, knobMap.getSyncPacket())
    }

    @ClientOnly
    override fun setupPacketsOnClient(builder: ClientSidePacketHandlerBuilder) {
        builder.withHandler<KnobMap.SyncPacket>(KnobMap.SyncPacket::deserialize) {
            knobMap.loadSyncPacket(it)
        }
    }

    @ServerOnly
    override fun scrollScrewdriver(player: ServerPlayer, delta: Double): Boolean {
        return knobMap.screwdriverInteraction(player, delta)
    }

    @ServerOnly
    override fun applyScrewdriver(screwdriver: ScrewdriverItem, context: UseOnContext, configValue: OptionalDouble) {
        knobMap.screwdriverConfigure(context.player as ServerPlayer, configValue)
    }

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if(placement.level.isClientSide) {
            return InteractionResult.SUCCESS
        }

        cell.subtractMode = !cell.subtractMode
        setSyncDirty()

        return InteractionResult.SUCCESS
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Gain knob: ${knobGain.rotation.rounded()}" }
        cell.submitDisplay(builder)
    }

    companion object {
        private const val SUBTRACT_MODE = "subtractMode"
    }
}

/**
 * Signal reference that outputs a settable signal voltage in the [-100, 100] V range.
 * The value is set via a knob and fine-tuned via screwdriver interaction.
 * */
class SignalReferenceElectricalObject(
    cell: SignalReferenceCell,
    val outputMap: MonopoleMap
) : ElectricalObject<SignalReferenceCell>(cell) {
    val signalSource = SignalSource()

    override fun offerPolar(remote: ElectricalObject<*>): ElectricalPin? {
        if(outputMap.evaluates(cell, remote.cell)) {
            return signalSource.offerOutput()
        }

        return null
    }

    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo) = signalSource.offerOutput()

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(signalSource)
    }

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)
        signalSource.build(map)
    }

    override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        subscribers.addPost(this::tick)
    }

    fun tick(dt: Double, phase: SimulationPhase) {
        signalSource.signal = cell.value
    }
}

class SignalReferenceCell(ci: CellCreateInfo, outputMap: MonopoleMap) : Cell(ci), SidedElectrical<SignalReferenceCell> {
    companion object {
        private const val VALUE = "value"
    }

    var value = 0.0
        set(value) {
            field = value.coerceIn(-MAX_SIGNAL, MAX_SIGNAL)
            setChanged()
        }

    @SimObject
    val reference = SignalReferenceElectricalObject(this, outputMap)

    @Node
    val grid = GridNode(this)

    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell): ElectricalSize? {
        if(reference.outputMap.evaluates(this, targetCell)) {
            return ElectricalSize.Signal
        }

        return null
    }

    override fun saveCellData() = CompoundTag().also {
        it.putDouble(VALUE, value)
    }

    override fun loadCellData(tag: CompoundTag) {
        value = tag.getDouble(VALUE)
    }

    fun submitDisplay(builder: ComponentDisplayList) {
        builder.signalOutput(reference.signalSource.signal)
    }
}

class SignalReferencePart(
    ci: PartCreateInfo,
    val body: PartialModel,
    val models: Map<Base6Direction3d, WireConnectionModelPartial>,
    provider: CellProvider<SignalReferenceCell>
) :
    GridCellPart<SignalReferenceCell>(ci, provider),
    ComponentDisplay,
    PartWithKnobs,
    ScrewdriverScrollable,
    ScrewdriverInteractable,
    ConnectedPart {

    override val knobMap = KnobMap(this::onKnobMapChanged)

    val knobValue = knobMap.addKnobBB(
        this,
        FlwModels.POTENTIAL_PROBE_KNOB_INPUT_RANGE_MIN,
        Vector3d.unitY,
        "knob_value",
        6.35, 2.025, 5.125,
        0.325, 0.45, 0.325
    ).configure { setLimits(-MAX_SIGNAL, MAX_SIGNAL); makeInteractable("waila.eln2.signal_reference_value") }

    val outputTerminal = defineCellBoxTerminalBB(
        10.6, 0.775, 7.85,
        0.3, 0.55, 0.3,
        highlightColor = MyColor.BLUE,
        categories = listOf(GridMaterialCategory.SignalGrid)
    )

    @ClientOnly
    private var renderStateImpl = ConnectedPartRenderStateImpl.createIfApplicable(this)

    @ClientOnly
    override val connectedRenderState: ConnectedPartRenderState get() = renderStateImpl!!

    override fun getSyncTag() = ConnectedPart.pack(this)

    @ClientOnly
    override fun handleSyncTag(tag: CompoundTag) {
        renderStateImpl!!.set(getConnectedPartsFromTag(tag))
    }

    override fun onConnectivityChanged() = this.setSyncDirty()

    override fun createVisual(ctx: MultipartVisualizationContext) = ConnectedPartWithKnobsVisual(
        ctx, this, body, models
    )

    override fun onCellAcquired() {
        knobMap.loadChanges {
            knobValue.rotation = cell.value
        }
    }

    private fun onKnobMapChanged() {
        if(!placement.level.isClientSide) {
            if(hasCell) {
                cell.value = knobValue.rotation
                sendBulkPacket(KnobMap.SyncPacket::serialize, knobMap.getSyncPacket())
            }
        }
    }

    @ServerOnly
    override fun onSyncSuggested() {
        super.onSyncSuggested()
        sendBulkPacket(KnobMap.SyncPacket::serialize, knobMap.getSyncPacket())
    }

    @ClientOnly
    override fun setupPacketsOnClient(builder: ClientSidePacketHandlerBuilder) {
        builder.withHandler<KnobMap.SyncPacket>(KnobMap.SyncPacket::deserialize) {
            knobMap.loadSyncPacket(it)
        }
    }

    @ServerOnly
    override fun scrollScrewdriver(player: ServerPlayer, delta: Double): Boolean {
        return knobMap.screwdriverInteraction(player, delta)
    }

    @ServerOnly
    override fun applyScrewdriver(screwdriver: ScrewdriverItem, context: UseOnContext, configValue: OptionalDouble) {
        knobMap.screwdriverConfigure(context.player as ServerPlayer, configValue)
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Value knob: ${knobValue.rotation.rounded()}" }
        cell.submitDisplay(builder)
    }
}

/**
 * Clamps the input signal to the range `[clampMin, clampMax]`.
 * The output is a [SignalSource] set to the clamped value.
 * */
class SignalClamperElectricalObject(cell: SignalClamperCell, val inputMap: MonopoleMap, val outputMap: MonopoleMap) : ElectricalObject<SignalClamperCell>(cell) {
    val inputResistor = Resistor().also { it.resistance = SIGNAL_SERIES_RESISTANCE }
    val signalSource = SignalSource()

    override fun offerPolar(remote: ElectricalObject<*>): ElectricalPin? {
        if(inputMap.evaluates(cell, remote.cell)) {
            return inputResistor.positive
        }

        if(outputMap.evaluates(cell, remote.cell)) {
            return signalSource.offerOutput()
        }

        return null
    }

    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo): ElectricalPin? {
        return when(m0.terminal) {
            cell.inputTerminal -> inputResistor.positive
            cell.outputTerminal -> signalSource.offerOutput()
            else -> null
        }
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        circuit.add(inputResistor)
        circuit.add(signalSource)
    }

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)
        map.ground(inputResistor.negative)
        signalSource.build(map)
    }

    override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        subscribers.addPost(this::tick)
    }

    fun tick(dt: Double, phase: SimulationPhase) {
        val input = inputResistor.potential
        val min = cell.clampMin
        val max = cell.clampMax
        val clamped = if(min <= max) input.coerceIn(min, max) else input.coerceIn(max, min)
        signalSource.signal = clamped.coerceIn(-MAX_SIGNAL, MAX_SIGNAL)
    }
}

class SignalClamperCell(
    ci: CellCreateInfo,
    inputMap: MonopoleMap,
    outputMap: MonopoleMap,
    val inputTerminal: Int = 0,
    val outputTerminal: Int = 1
) : Cell(ci), SidedElectrical<SignalClamperCell> {
    companion object {
        private const val CLAMP_MIN = "clampMin"
        private const val CLAMP_MAX = "clampMax"
    }

    var clampMin = -MAX_SIGNAL
        set(value) {
            field = value.coerceIn(-MAX_SIGNAL, MAX_SIGNAL)
            setChanged()
        }

    var clampMax = MAX_SIGNAL
        set(value) {
            field = value.coerceIn(-MAX_SIGNAL, MAX_SIGNAL)
            setChanged()
        }

    @SimObject
    val clamper = SignalClamperElectricalObject(this, inputMap, outputMap)

    @Node
    val grid = GridNode(this)

    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell): ElectricalSize? {
        if(clamper.inputMap.evaluates(this, targetCell) ||
            clamper.outputMap.evaluates(this, targetCell)) {
            return ElectricalSize.Signal
        }

        return null
    }

    override fun saveCellData() = CompoundTag().also {
        it.putDouble(CLAMP_MIN, clampMin)
        it.putDouble(CLAMP_MAX, clampMax)
    }

    override fun loadCellData(tag: CompoundTag) {
        clampMin = tag.getDouble(CLAMP_MIN)
        clampMax = tag.getDouble(CLAMP_MAX)
    }

    fun submitDisplay(builder: ComponentDisplayList) {
        builder.signalInput(clamper.inputResistor.potential)
        builder.signalOutput(clamper.signalSource.signal)
        builder.translateQuantityRow("clamper_min", Quantity(clampMin, VOLT))
        builder.translateQuantityRow("clamper_max", Quantity(clampMax, VOLT))
    }
}

class SignalClamperPart(
    ci: PartCreateInfo,
    val body: PartialModel,
    val models: Map<Base6Direction3d, WireConnectionModelPartial>,
    provider: CellProvider<SignalClamperCell>
) :
    GridCellPart<SignalClamperCell>(ci, provider),
    ComponentDisplay,
    PartWithKnobs,
    ScrewdriverScrollable,
    ScrewdriverInteractable,
    ConnectedPart {

    override val knobMap = KnobMap(this::onKnobMapChanged)

    val knobMin = knobMap.addKnobBB(
        this,
        FlwModels.POTENTIAL_PROBE_KNOB_INPUT_RANGE_MIN,
        Vector3d.unitY,
        "knob_min",
        6.35, 2.025, 5.125,
        0.325, 0.45, 0.325
    ).configure { setLimits(-MAX_SIGNAL, MAX_SIGNAL); makeInteractable("waila.eln2.clamper_min") }

    val knobMax = knobMap.addKnobBB(
        this,
        FlwModels.POTENTIAL_PROBE_KNOB_OUTPUT_RANGE_MAX,
        Vector3d.unitY,
        "knob_max",
        6.35, 2.025, 6.625,
        0.325, 0.45, 0.325
    ).configure { setLimits(-MAX_SIGNAL, MAX_SIGNAL); makeInteractable("waila.eln2.clamper_max") }

    val inputTerminal = defineCellBoxTerminalBB(
        10.6, 0.775, 5.85,
        0.3, 0.55, 0.3,
        highlightColor = MyColor.RED,
        categories = listOf(GridMaterialCategory.SignalGrid)
    )

    val outputTerminal = defineCellBoxTerminalBB(
        10.6, 0.775, 9.85,
        0.3, 0.55, 0.3,
        highlightColor = MyColor.BLUE,
        categories = listOf(GridMaterialCategory.SignalGrid)
    )

    @ClientOnly
    private var renderStateImpl = ConnectedPartRenderStateImpl.createIfApplicable(this)

    @ClientOnly
    override val connectedRenderState: ConnectedPartRenderState get() = renderStateImpl!!

    override fun getSyncTag() = ConnectedPart.pack(this)

    @ClientOnly
    override fun handleSyncTag(tag: CompoundTag) {
        renderStateImpl!!.set(getConnectedPartsFromTag(tag))
    }

    override fun onConnectivityChanged() = this.setSyncDirty()

    override fun createVisual(ctx: MultipartVisualizationContext) = ConnectedPartWithKnobsVisual(
        ctx, this, body, models
    )

    override fun onCellAcquired() {
        knobMap.loadChanges {
            knobMin.rotation = cell.clampMin
            knobMax.rotation = cell.clampMax
        }
    }

    private fun onKnobMapChanged() {
        if(!placement.level.isClientSide) {
            if(hasCell) {
                cell.clampMin = knobMin.rotation
                cell.clampMax = knobMax.rotation
                sendBulkPacket(KnobMap.SyncPacket::serialize, knobMap.getSyncPacket())
            }
        }
    }

    @ServerOnly
    override fun onSyncSuggested() {
        super.onSyncSuggested()
        sendBulkPacket(KnobMap.SyncPacket::serialize, knobMap.getSyncPacket())
    }

    @ClientOnly
    override fun setupPacketsOnClient(builder: ClientSidePacketHandlerBuilder) {
        builder.withHandler<KnobMap.SyncPacket>(KnobMap.SyncPacket::deserialize) {
            knobMap.loadSyncPacket(it)
        }
    }

    @ServerOnly
    override fun scrollScrewdriver(player: ServerPlayer, delta: Double): Boolean {
        return knobMap.screwdriverInteraction(player, delta)
    }

    @ServerOnly
    override fun applyScrewdriver(screwdriver: ScrewdriverItem, context: UseOnContext, configValue: OptionalDouble) {
        knobMap.screwdriverConfigure(context.player as ServerPlayer, configValue)
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE {
            "Min: ${knobMin.rotation.rounded()}, Max: ${knobMax.rotation.rounded()}"
        }

        cell.submitDisplay(builder)
    }
}
