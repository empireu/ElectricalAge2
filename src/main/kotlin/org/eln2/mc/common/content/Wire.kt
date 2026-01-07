@file:Suppress("NonAsciiCharacters", "MemberVisibilityCanBePrivate")

package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.PartialModel
import dev.engine_room.flywheel.lib.transform.Affine
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import kotlinx.serialization.Serializable
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.LightLayer
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.ImmutableIntArrayView
import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.sim.*
import org.ageseries.libage.sim.electrical.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.ElectricalConnectivityMap
import org.eln2.mc.*
import org.eln2.mc.client.render.FlwMaterials
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.blocks.foundation.MultipartVisualizationContext
import org.eln2.mc.common.cells.CellRegistry
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.*
import org.eln2.mc.client.render.foundation.MyColor
import java.util.function.Supplier
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.PI

/**
 * Generalized thermal conductor, in the form of a single thermal body that gets connected to all neighbor cells.
 * */
class ThermalWireObject(cell: Cell, val thermalBody: ThermalMass, val environmentLeakageParameters: ConnectionParameters = ConnectionParameters.DEFAULT) : ThermalObject<Cell>(cell), PersistentObject, ThermalContactInfo {
    /**
     * The way the thermal state is persisted.
     * */
    enum class ThermalStateSavingPolicy {
        /**
         * The default policy. Saves temperature; useful if the mass and material is constant for the specific use case.
         * If the mod updates and the material or mass gets changed, temperature will be the same, which is the most stable option.
         * */
        Temperature,
        /**
         * Used when the thermal body is mutated externally (specifically, during the loading of another object, which sets mass and material).
         * Persisting by temperature would not work if the thermal object is deserialized before the object that sets the mass and material acts. The thermal energy would change.
         * Shuffling the fields around might not work if the other object accesses this one in its constructor, so this gives more flexibility.
         * */
        Energy
    }

    companion object {
        // Storing temperature. If I change the properties of the material, it will be the same temperature in game.
        private const val TEMPERATURE = "temperature"
        private const val ENERGY = "energy"
    }

    constructor(cell: Cell) : this(cell, ThermalMass(ChemicalElement.Copper.asMaterial))

    constructor(cell: Cell, definition: ThermalMassDefinition) : this(cell, definition())

    private var lastTemperature: Double

    init {
        cell.environmentData.loadTemperature(thermalBody)
        lastTemperature = !thermalBody.temperature
    }

    override fun offerComponent(remote: ThermalObject<*>) = ThermalComponentInfo(thermalBody)

    override fun addComponents(simulator: Simulator) {
        simulator.add(thermalBody)

        cell.environmentData.connect(simulator, environmentLeakageParameters, thermalBody)
    }

    var savePolicy = ThermalStateSavingPolicy.Temperature

    override fun saveObjectNbt(): CompoundTag {
        val tag = CompoundTag()

        when(savePolicy) {
            ThermalStateSavingPolicy.Temperature -> {
                tag.putQuantity(TEMPERATURE, thermalBody.temperature)
            }
            ThermalStateSavingPolicy.Energy -> {
                tag.putQuantity(ENERGY, thermalBody.energy)
            }
        }

        return tag
    }

    override fun loadObjectNbt(tag: CompoundTag) {
        when(savePolicy) {
            ThermalStateSavingPolicy.Temperature -> {
                thermalBody.temperature = tag.getQuantity(TEMPERATURE)

            }
            ThermalStateSavingPolicy.Energy -> {
                thermalBody.energy = tag.getQuantity(ENERGY)

            }
        }
    }

    override fun getContactTemperature(other: Cell) = thermalBody.temperature

    override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        subscribers.addSubscriber(
            SubscriberOptions(100, SimulationPhase.Post),
            this::simulationTick
        )
    }

    private fun simulationTick(dt: Double, phase: SimulationPhase) {
        cell.setChangedIf(!thermalBody.temperature.value.approxEq(lastTemperature)) {
            lastTemperature = thermalBody.temperature.value
        }
    }
}

/**
 * Generalized electrical wire, created by joining the "internal" pins of a [ResistorBundle].
 * The "external" pins are offered to other cells.
 * */
class SingleElectricalWireObject(cell: Cell) : ElectricalObject<Cell>(cell) {
    // Optimization opportunity: make bundle create one resistor when possible. But it isn't that worthwhile because it is virtual.
    val resistors = ResistorBundle(cell, 0.05)

    val totalPowerSimulation get() = resistors.totalPowerSimulation

    val totalCurrentDisplay get() = resistors.totalCurrentDisplay
    val totalPowerDisplay get() = resistors.totalPowerDisplay

    /**
     * Gets or sets the resistance of the bundle.
     * Only applied when the circuit is re-built.
     * */
    var resistance by resistors::crossResistance

    override fun offerPolar(remote: ElectricalObject<*>) = resistors.getOfferedResistor(remote)

    override fun clearComponents() = resistors.clear()

    override fun addComponents(circuit: ElectricalComponentSet) = resistors.addComponents(connections, circuit)

    override fun build(map: ElectricalConnectivityMap) {
        // The Wire uses a bundle of 4 resistors. Every resistor's "Internal Pin" is connected to every
        // other resistor's internal pin. "External Pins" are offered to connection candidates:

        resistors.build(connections, this, map)

        resistors.forEach { a ->
            resistors.forEach { b ->
                if (a != b) {
                    map.join(a.offerInternal(), b.offerInternal())
                }
            }
        }
    }

    fun setupDielectricBreakdown(behavior: DielectricBreakdownBehavior, breakdownToEarth: Double) {
        resistors.forEach {
            behavior.addPort(it, null, breakdownToEarth)
        }
    }
}

/**
 * Holds all data required to render a wire connection.
 * @param hub Hub (junction) model
 * @param connection The connection models
 * */
data class WireRenderModel(val hub: PartialModel, val connection: WireConnectionModel, val tintColor: ThermalTint = ThermalTint.DEFAULT)

/**
 * Holds information regarding a registered thermal wire.
 * @param thermalProperties The strictly thermal properties of the wire.
 * @param id The ID of the wire cell.
 * */
data class ThermalWireRegistryObject(
    val size: ThermalSize,
    val thermalProperties: WireThermalProperties,
    val id: ResourceLocation
)

/**
 * Holds information regarding a registered electrical-thermal wire.
 * @param thermalProperties The strictly thermal properties of the wire.
 * @param electricalProperties The strictly electrical properties of the wire.
 * @param id The ID of the wire cell.
 * */
data class ElectricalWireRegistryObject(
    val size: ElectricalSize,
    val thermalProperties: WireThermalProperties,
    val electricalProperties: WireElectricalProperties,
    val id: ResourceLocation
)

abstract class WireBuilder<C : WireCell>(val id: String) {
    var material = ThermalMassDefinition(ChemicalElement.Copper.asMaterial)
    var contactSurfaceArea = PI * (0.05 * 0.05)
    var replicatesInternalTemperature = true
    var isIncandescent: Boolean = true
    var hubSize = Vector3d(3.5 / 16.0, 2.0 / 16.0, 3.5 / 16.0)
    var temperatureThreshold = Quantity(150.0, CELSIUS)
    var smokeTemperature: Double? = null
    private var renderInfo: Supplier<WireRenderModel>? = null
    var connectionSize = Vector3d(2.0 / 16.0, 1.5 / 16.0, 6.25 / 16.0)
    var wireShapes : Map<Pair<Direction, Direction>, VoxelShape>? = null
    var wireShapesFilled : Map<Pair<Direction, Direction>, VoxelShape>? = null
    var leakageParameters: ConnectionParameters = ConnectionParameters.DEFAULT
    var radiantDescription: RadiantBodyEmissionDescription? = null

    fun renderer(supplier: Supplier<WireRenderModel>) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
            Runnable {
                renderInfo = supplier
            }
        }
    }

    protected fun createThermalProperties() = WireThermalProperties(
        material,
        temperatureThreshold,
        replicatesInternalTemperature,
        isIncandescent,
        radiantDescription,
        leakageParameters
    )

    protected fun registerPart(properties: WireThermalProperties, provider: RegistryObject<CellProvider<C>>) {
        fun createShapes(size: Vector3d) : HashMap<Pair<Direction, Direction>, VoxelShape> {
            val results = HashMap<Pair<Direction, Direction>, VoxelShape>()

            val boundingBox = AABB(
                (-size / 2.0).toVec3(),
                (+size / 2.0).toVec3()
            ).move((-Vector3d.unitZ / 2.0 + Vector3d.unitZ * (size.z / 2.0)).toVec3())

            Base6Direction3dMask.FULL.directionList.forEach { face ->
                FacingDirection.entries.forEach { facing ->
                    results[
                        Pair(
                            face,
                            incrementFromForwardUp(
                                facing,
                                face,
                                Direction.NORTH
                            )
                        )
                    ] = Shapes.create(
                        PartGeometry.transform(
                            boundingBox,
                            facing,
                            face
                        )
                    )
                }
            }

            return results
        }

        val connections = wireShapes ?: createShapes(connectionSize)
        val connectionsFilled = wireShapesFilled ?: createShapes(Vector3d(connectionSize.x, connectionSize.y, 0.5))

        val smokeTemperature = this.smokeTemperature ?: (!properties.temperatureThreshold * 0.9)

        PartRegistry.partAndItemWithProvider(
            id,
            BasicPartProvider(hubSize) { ci ->
                WirePart(
                    ci,
                    cellProvider = provider.get(),
                    isIncandescent,
                    smokeTemperature,
                    connections,
                    connectionsFilled,
                    renderModel = this.renderInfo?.get()
                )
            }
        )
    }
}

class ThermalWireBuilder(id: String) : WireBuilder<ThermalWireCell>(id) {
    var size = ThermalSize.Standard

    @OptIn(ExperimentalContracts::class)
    inline fun applyAndRegister(block: ThermalWireBuilder.() -> Unit): ThermalWireRegistryObject {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        block()

        return register()
    }

    fun register(): ThermalWireRegistryObject {
        val material = createThermalProperties()

        val cell = CellRegistry.cellImmediate(id) {
            ThermalWireCell(
                it,
                contactSurfaceArea,
                size,
                material,
            )
        }

        registerPart(material, cell)

        return ThermalWireRegistryObject(
            size,
            material,
            cell.id
        )
    }
}

class ElectricalWireBuilder(id: String) : WireBuilder<ElectrothermalWireCell>(id) {
    var size = ElectricalSize.Standard
    var resistance: Double = 2.14 * 1e-5
    var breakdownPotential: Double = 400.0

    @OptIn(ExperimentalContracts::class)
    inline fun applyAndRegister(block: ElectricalWireBuilder.() -> Unit): ElectricalWireRegistryObject {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        block()

        return register()
    }

    fun register(): ElectricalWireRegistryObject {
        val material = createThermalProperties()

        val electrical = WireElectricalProperties(
            resistance,
            breakdownPotential
        )

        val cell = CellRegistry.cellImmediate(id) {
            ElectrothermalWireCell(
                it,
                contactSurfaceArea,
                material,
                size,
                electrical
            )
        }

        registerPart(material, cell)

        return ElectricalWireRegistryObject(
            size,
            material,
            electrical,
            cell.id
        )
    }
}

/**
 * Thermal properties of a wire.
 * @param thermalDef The definition used to create the thermal body of the wire.
 * @param damageOptions The damage config, passed to the [ThermalBreakdownBehavior]
 * @param replicatesInternalTemperature Indicates if the wire should replicate the internal temperature (temperature of the wire's thermal body)
 * @param replicatesExternalTemperature Indicates if the wire should replicate the external temperatures (temperatures of connected thermal objects)
 * @param radiantInfo If not null, this wire will emit light based on the description.
 * @param leakageParameters Environment connection info.
 * */
data class WireThermalProperties(
    val thermalDef: ThermalMassDefinition,
    val temperatureThreshold: Quantity<Temperature>,
    val replicatesInternalTemperature: Boolean,
    val replicatesExternalTemperature: Boolean,
    val radiantInfo: RadiantBodyEmissionDescription?,
    val leakageParameters: ConnectionParameters
)

/**
 * Electrical properties of a wire.
 * @param electricalResistance The electrical resistance.
 * @param breakdownPotential The dielectric breakdown potential.
 * */
data class WireElectricalProperties(
    val electricalResistance: Double,
    val breakdownPotential: Double
)

interface DirectionBlacklist {
    fun addToBlacklist(directionPart: Base6Direction3d) : Boolean
    fun removeFromBlacklist(directionPart: Base6Direction3d) : Boolean
}

open class WireCell(ci: CellCreateInfo, val connectionCrossSection: Double) : Cell(ci), DirectionBlacklist, CellContactPointSurface {
    companion object {
        private const val BLACKLIST = "blacklist"
    }

    private val blacklist = HashSet<Base6Direction3d>()

    override fun addToBlacklist(directionPart: Base6Direction3d) = blacklist.add(directionPart)

    override fun removeFromBlacklist(directionPart: Base6Direction3d) = blacklist.remove(directionPart)

    override fun saveCellData() = CompoundTag().also { tag ->
        tag.putIntArray(BLACKLIST, blacklist.map { it.id })
    }

    override fun loadCellData(tag: CompoundTag) {
        if(tag.contains(BLACKLIST)) {
            blacklist.addAll(tag.getIntArray(BLACKLIST).map { Base6Direction3d.entries[it] })
        }
    }

    override fun getContactSection(cell: Cell) = connectionCrossSection

    /**
     * Adds the connection [blacklist] on top of the default predicate (and also checks if the physical solution exists).
     * */
    override fun cellConnectionPredicate(remote: Cell): Boolean {
        if(!super.cellConnectionPredicate(remote)) {
            return false
        }

        val solution = getPartConnectionOrNull(this.locator, remote.locator)
            ?: return true

        return !blacklist.contains(solution.directionSpecificFrame)
    }
}

open class ThermalWireCell(
    ci: CellCreateInfo, connectionCrossSection: Double,
    override val thermalSize: ThermalSize?,
    val thermalProperties: WireThermalProperties
) : WireCell(ci, connectionCrossSection), SidedThermalFLBR<ThermalWireCell> {
    @SimObject
    val thermalWire = ThermalWireObject(
        self(),
        thermalProperties.thermalDef(),
        thermalProperties.leakageParameters
    )

    @Behavior
    val explosion = ThermalBreakdownBehavior.create(
        thermalProperties.temperatureThreshold,
        self(),
        thermalWire.thermalBody::temperature
    )

    @Behavior
    val radiantEmitter = if(thermalProperties.radiantInfo != null) {
        RadiantEmissionBehavior.create(
            self(),
            thermalWire.thermalBody to thermalProperties.radiantInfo
        )
    }
    else {
        null
    }

    /**
     * Replicates the temperature of [thermalWire] if [WireThermalProperties.replicatesInternalTemperature]
     * */
    @Replicator
    fun internalTemperatureReplicator(consumer: InternalMultiThermalBodyTemperatureConsumer) =
        if (thermalProperties.replicatesInternalTemperature)
            InternalMultiThermalBodyTemperatureReplicatorBehavior(listOf(thermalWire.thermalBody), consumer)
        else null

    /**
     * Replicates the external temperatures if [WireThermalProperties.replicatesExternalTemperature]
     * */
    @Replicator
    fun externalTemperatureReplicator(consumer: ExternalTemperatureConsumer) =
        if(thermalProperties.replicatesExternalTemperature)
            ExternalTemperatureReplicatorBehavior(this, consumer)
        else null
}

open class ElectrothermalWireCell(
    ci: CellCreateInfo,
    contactCrossSection: Double,
    thermalProperties: WireThermalProperties,
    override val electricalSize: ElectricalSize?,
    val electricalProperties: WireElectricalProperties
) : ThermalWireCell(ci, contactCrossSection, null, thermalProperties), SidedElectricalFLBR<ElectrothermalWireCell> {
    /**
     * Disallow connections with thermal-only devices:
     * */
    override val isExclusivelyElectricalConnected: Boolean
        get() = true

    @SimObject
    val electricalWire = SingleElectricalWireObject(self()).also {
        it.resistance = electricalProperties.electricalResistance
    }

    @Behavior
    val heater = PowerHeatingBehavior(
        electricalWire::totalPowerSimulation,
        thermalWire.thermalBody
    )

    @Behavior
    val breakdown = DielectricBreakdownBehavior.create(this)

    override fun onBuildFinished() {
        super.onBuildFinished()
        electricalWire.setupDielectricBreakdown(breakdown, electricalProperties.breakdownPotential)
    }

    override fun clearObjectConnections() {
        super.clearObjectConnections()
        breakdown.clear()
    }
}

class WirePart<C : WireCell>(
    ci: PartCreateInfo,
    cellProvider: CellProvider<C>,
    val isIncandescent: Boolean,
    val smokeTemperature: Double,
    val connectionBounds: Map<Pair<Direction, Direction>, VoxelShape>,
    val connectionBoundsFilled: Map<Pair<Direction, Direction>, VoxelShape>,
    val renderModel: WireRenderModel?,
) : CellPart<C>(ci, cellProvider),
    InternalMultiThermalBodyTemperatureConsumer,
    ExternalTemperatureConsumer,
    AnimatedPart,
    WrenchInteractable,
    ComponentDisplay
{
    companion object {
        private const val DIRECTIONS = "directions"
    }

    @ClientOnly
    interface RenderState {
        val connectionsVersion: Int
        val connections: IntArray
        val internalTemperatureVersion: Int
        val internalTemperature: Double
        val externalTemperatureVersion: Int
        val externalTemperatures: Map<Int, Double>
    }

    @ClientOnly
    private class RenderStateImpl : RenderState {
        override var connectionsVersion = 0
            private set

        override var connections = IntArray(0)
            private set

        override var internalTemperatureVersion = 0
            private set

        override var internalTemperature = 0.0
            private set

        override var externalTemperatureVersion = 0
            private set

        override var externalTemperatures = emptyMap<Int, Double>()
            private set

        fun setConnections(connections: IntArray) {
            this.connections = connections
            connectionsVersion++
        }

        fun setInternalTemperature(internalTemperature: Double) {
            this.internalTemperature = internalTemperature
            internalTemperatureVersion++
        }

        fun setExternalTemperatures(externalTemperatures: Map<Int, Double>) {
            this.externalTemperatures = externalTemperatures
            externalTemperatureVersion++
        }
    }

    @ClientOnly
    private var renderStateImpl: RenderStateImpl? = if(ci.placement.level.isClientSide) {
        RenderStateImpl()
    } else {
        null
    }

    @ClientOnly
    val renderState: RenderState get() = renderStateImpl!!

    override fun createVisual(ctx: MultipartVisualizationContext): AbstractPartVisual<*>? {
        val model = renderModel
            ?: return null

        return if(isIncandescent) {
            IncandescentWirePartVisual(ctx, this, model)
        } else {
            InsulatedWirePartVisual(ctx, this, model)
        }
    }

    @ServerOnly
    private fun getConnectionsInfo() : List<Int> {
        check(!placement.level.isClientSide)

        if(!hasCell) {
            return emptyList()
        }

        return cell.connections.mapNotNull {
            getPartConnectionOrNull(cell.locator, it.locator)?.value
        }
    }

    @ServerOnly
    private fun getConnectionShapeKey(connectionInfo: Int) = Pair(
        placement.face,
        incrementFromForwardUp(
            placement.facing,
            placement.face,
            PartConnectionDirection(connectionInfo).directionSpecificFrame
        )
    )

    override fun applyWrench(wrench: WrenchItem, context: UseOnContext): InteractionResult {
        if(!hasCell) {
            return InteractionResult.FAIL
        }

        val connections = getConnectionsInfo()

        val shapeSet = if(getIsFilledVariant(connections)) {
            connectionBoundsFilled
        }
        else {
            connectionBounds
        }

        val boxes = ArrayList<Pair<AABB, Int>>()

        val x = placement.position.x
        val y = placement.position.y
        val z = placement.position.z

        for (connectionInfo in connections) {
            val shape = shapeSet[getConnectionShapeKey(connectionInfo)]
                ?: continue

            shape.forAllBoxes { x0, y0, z0, x1, y1, z1 ->
                val aabb = AABB(
                    x0 + x, y0 + y, z0 + z,
                    x1 + x, y1 + y, z1 + z
                )

                boxes.add(Pair(aabb, connectionInfo))
            }
        }

        val target = clipScene(context.player!!, { it.first }, boxes)

        if(target != null) {
            if(cell.addToBlacklist(PartConnectionDirection(target.second).directionSpecificFrame)) {
                CellConnections.retopologize(cell, placement.multipart)
                setSyncDirty()
                return InteractionResult.SUCCESS
            }

            // Kind of weird if it fails, how was the connection here?
            return InteractionResult.FAIL
        }
        else {
            val player = context.player!!

            val hit = modelBoundingBox.viewClipExtra(player, placement.position) ?:
            return InteractionResult.FAIL

            val directionPart = Base6Direction3d.fromForwardUp(
                placement.facing,
                placement.face,
                hit.direction
            )

            if(cell.removeFromBlacklist(directionPart)) {
                CellConnections.retopologize(cell, placement.multipart)
                setSyncDirty()
                return InteractionResult.SUCCESS
            }

            return InteractionResult.FAIL
        }
    }

    private fun updateShape(connectionsInfo: List<Int>) {
        val isFilled = getIsFilledVariant(connectionsInfo)

        var shape = if(isFilled) {
            Shapes.empty()
        } else {
            partProviderShape
        }

        val shapeSet = if(isFilled) {
            connectionBoundsFilled
        } else {
            connectionBounds
        }

        for (connectionInfo in connectionsInfo) {
            val connection = shapeSet[getConnectionShapeKey(connectionInfo)]
                ?: continue

            shape = Shapes.joinUnoptimized(shape, connection, BooleanOp.OR)
        }

        updateShape(shape)
    }

    @ServerOnly
    private fun updateShapeServer() {
        check(!placement.level.isClientSide)
        updateShape(getConnectionsInfo())
    }

    @ClientOnly
    private fun updateShapeClient(data: IntArray) {
        check(placement.level.isClientSide)
        updateShape(ImmutableIntArrayView(data))
    }

    override fun onPlaced() {
        super.onPlaced()

        if (!placement.level.isClientSide) {
            setSyncDirty()
        }
    }

    /**
     * Called when sending the connections to the client.
     * */
    @ServerOnly
    override fun getSyncTag(): CompoundTag {
        return CompoundTag().also { tag ->
            val directionList = ListTag()

            for (it in cell.connections) {
                val directSolution = getPartConnectionAsContactSectionConnectionOrNull(cell, it)
                    ?: continue

                directionList.add(directSolution.toNbt())
            }

            tag.put(DIRECTIONS, directionList)
        }
    }

    /**
     * Called when the client is loading the connections.
     * */
    @ClientOnly
    override fun handleSyncTag(tag: CompoundTag) {
        if (tag.contains(DIRECTIONS)) {
            val directionList = tag.get(DIRECTIONS) as ListTag
            val data = IntArray(directionList.size)

            directionList.forEachIndexed { i, t ->
                data[i] = PartConnectionRenderInfo.fromNbt(t as CompoundTag).value
            }

            updateShapeClient(data)
            renderStateImpl!!.setConnections(data)
        }
    }

    @ServerOnly
    override fun getClientSaveTag() = getSyncTag()

    @ClientOnly
    override fun loadClientSaveTag(tag: CompoundTag) = handleSyncTag(tag)

    @ServerOnly
    override fun onConnectivityChanged() {
        setSyncDirty()
        updateShapeServer()
    }

    @ClientOnly
    override fun setupPacketsOnClient(builder: ClientSidePacketHandlerBuilder) {
        builder.withHandler<InternalTemperaturePacket> {
            renderStateImpl!!.setInternalTemperature(it.temperature)

            if(it.temperature >= smokeTemperature) {
                placement.multipart.addAnimated(this)
            }
            else {
                placement.multipart.markRemoveAnimated(this)
            }
        }

        if(isIncandescent) {
            builder.withHandler<ExternalTemperaturesPacket> {
                renderStateImpl!!.setExternalTemperatures(it.temperatures)
            }
        }
    }

    @ServerOnly
    override fun onInternalTemperatureChanges(dirty: List<ThermalMass>) {
        sendBulkPacket(InternalTemperaturePacket(!dirty.first().temperature))
    }

    /**
     * Receives the temperatures of neighbor cells, from the simulation thread.
     * All temperatures are sent with a [ExternalTemperaturesPacket].
     * */
    @ServerOnly @OnSimulationThread
    override fun onExternalTemperatureChanges(removed: HashSet<ThermalObject<*>>, dirty: HashMap<ThermalObject<*>, Double>, all: HashMap<ThermalObject<*>, Double>) {
        val temperatures = Int2DoubleOpenHashMap()

        for ((thermalObject, temperature) in all) {
            val solution = getPartConnectionAsContactSectionConnectionOrNull(cell, thermalObject.cell)
                ?: continue

            temperatures.put(solution.value, temperature)
        }

        sendBulkPacket(ExternalTemperaturesPacket(temperatures))
    }

    /**
     * Sends the latest internal temperature (from reading the cell's temperature field) and the latest neighbor temperatures (from [cellNeighborCache])
     * */
    @ServerOnly
    override fun onSyncSuggested() {
        if(isIncandescent) {
            if(hasCell) {
                val cell = this.cell

                if(cell is ThermalWireCell) {
                    sendBulkPacket(InternalTemperaturePacket(!cell.thermalWire.thermalBody.temperature))
                }

                val externalTemperatures = Int2DoubleOpenHashMap()

                ExternalTemperatureReplicatorBehavior.scanNeighbors(cell) { remoteThermalObject, temperature ->
                    val solution = getPartConnectionOrNull(this.cell.locator, remoteThermalObject.cell.locator)
                        ?: return@scanNeighbors

                    externalTemperatures.put(solution.value, !temperature)
                }

                if(externalTemperatures.isNotEmpty()) {
                    sendBulkPacket(ExternalTemperaturesPacket(externalTemperatures))
                }
            }
        }
    }

    override fun onCellAcquired() {
        updateShapeServer()
    }

    @Serializable
    private data class InternalTemperaturePacket(val temperature: Double)

    @Serializable
    private data class ExternalTemperaturesPacket(val temperatures: Map<Int, Double>)

    override fun animationTick(random: RandomSource) {
        repeat(5) {
            placement.level.addParticle(
                ParticleTypes.SMOKE,
                placement.position.x + 0.5 + random.nextDouble(-0.25, 0.25),
                placement.position.y + random.nextDouble(0.05, 0.15),
                placement.position.z + 0.5 + random.nextDouble(-0.25, 0.25),
                random.nextDouble(-0.01, 0.01),
                random.nextDouble(0.01, 0.1),
                random.nextDouble(-0.01, 0.01)
            )
        }
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        val cell = this.cell

        if(cell is ThermalWireCell) {
            builder.quantity(cell.thermalWire.thermalBody.temperature)
        }

        if(cell is ElectrothermalWireCell) {
            builder.quantity(Quantity(cell.electricalWire.resistance, OHM))
            builder.quantity(cell.electricalWire.totalCurrentDisplay)
            builder.quantity(cell.electricalWire.totalPowerDisplay)
        }
    }
}

@JvmInline
value class PartConnectionRenderInfo(val value: Int) {
    val mode get() = CellPartConnectionMode.byId[(value and 7)]
    val directionPart get() = Base6Direction3d.entries[(value shr 3) and 7]
    val flag get() = (value and 64) != 0

    constructor(mode: CellPartConnectionMode, directionPart: Base6Direction3d, flag: Boolean) : this(
        mode.index or (directionPart.id shl 3) or (if(flag) 64 else 0))

    fun toNbt(): CompoundTag {
        val tag = CompoundTag()

        tag.putBase6Direction3d(DIR, directionPart)
        tag.putConnectionMode(MODE, mode)
        tag.putBoolean(FLAG, flag)

        return tag
    }

    companion object {
        private const val MODE = "mode"
        private const val DIR = "dir"
        private const val FLAG = "flag"

        fun cast(from: PartConnectionDirection) = PartConnectionRenderInfo(from.mode, from.directionSpecificFrame, false)

        fun fromNbt(tag: CompoundTag) = PartConnectionRenderInfo(
            tag.getConnectionMode(MODE),
            tag.getBase6Direction3d(DIR),
            tag.getBoolean(FLAG)
        )
    }
}

interface CellContactPointSurface {
    fun getContactSection(cell: Cell) : Double
}
// to improve
fun getPartConnectionAsContactSectionConnectionOrNull(cell: Cell, remoteCell: Cell) : PartConnectionRenderInfo? {
    val partSolution = getPartConnectionOrNull(cell.locator, remoteCell.locator)
        ?: return null

    if(cell !is CellContactPointSurface || remoteCell !is CellContactPointSurface) {
        return PartConnectionRenderInfo.cast(partSolution)
    }

    val flag = cell.getContactSection(remoteCell) < remoteCell.getContactSection(cell)

    return PartConnectionRenderInfo(
        partSolution.mode,
        partSolution.directionSpecificFrame,
        flag
    )
}

private fun getIsFilledVariant(connections: IntArray) = if (connections.size == 2) {
    val c1 = PartConnectionDirection(connections[0])
    val c2 = PartConnectionDirection(connections[1])
    c1.directionSpecificFrame == c2.directionSpecificFrame.opposite
} else false

private fun getIsFilledVariant(connections: List<Int>) = if (connections.size == 2) {
    val c1 = PartConnectionDirection(connections[0])
    val c2 = PartConnectionDirection(connections[1])
    c1.directionSpecificFrame == c2.directionSpecificFrame.opposite
} else false

data class WireConnectionModelPartial(val planar: PolarModel, val inner: PolarModel, val wrapped: PolarModel) {
    val variants = mapOf(
        CellPartConnectionMode.Planar to planar,
        CellPartConnectionMode.Inner to inner,
        CellPartConnectionMode.Wrapped to wrapped
    )
}

abstract class WirePartVisual<H : TransformedInstance, C : TransformedInstance>(
    ctx: MultipartVisualizationContext,
    part: WirePart<*>,
    val model: WireRenderModel
) : AbstractPartVisual<WirePart<*>>(ctx, part), SimpleDynamicVisual {
    protected var hubInstance: H? = null
    protected var connectionInstances = Int2ObjectOpenHashMap<C>(4)

    /**
     * Tracks the last acknowledged version of the connections from [WirePart.RenderState].
     * */
    private var connectionsVersion = -1

    /**
     * Polls the part's [WirePart.RenderState] for changes to the connections.
     * If there are any changes, [applyConnectionData] is called to re-model the wire.
     * */
    override fun beginFrame(ctx: DynamicVisual.Context) {
        val partialTick = ctx.partialTick()
        val partRenderState = part.renderState

        val latestConnectionsVersion = partRenderState.connectionsVersion
        if(connectionsVersion != latestConnectionsVersion) {
            connectionsVersion = latestConnectionsVersion
            applyConnectionData(partRenderState.connections, partialTick)
        }
    }

    /**
     * Applies the received directions by creating a hub instance and connection instances.
     * @param partConnections An array of [PartConnectionDirection]. If empty, no connection instances will be created.
     * */
    protected abstract fun applyConnectionData(partConnections: IntArray, partialTick: Float)

    override fun updateLight(partialTick: Float) {
        visualizationContext.parent.relightInstances(hubInstance)

        connectionInstances.values.forEach {
            relightConnection(it, partialTick)
        }
    }

    open fun relightConnection(instance: C, partialTick: Float) {
        visualizationContext.parent.relightInstances(instance)
    }

    override fun _delete() {
        deleteHubInstance()
        deleteConnectionInstances()
    }

    protected fun deleteHubInstance() {
        hubInstance?.delete()
        hubInstance = null
    }

    protected fun deleteConnectionInstances() {
        for (instance in connectionInstances.values) {
            instance.delete()
        }

        connectionInstances.clear()
    }

    protected fun putUniqueConnection(key: Int, instance: C) {
        require(connectionInstances.put(key, instance) == null) {
            "Duplicate $this wire renderer direction"
        }
    }

    protected fun<T : Affine<T>> T.poseHub(): T = this.partTransformation(visualizationContext.parent, part)

    protected fun<T : Affine<T>> T.poseConnection(info: PartConnectionDirection): T =
        this.partTransformation(
            visualizationContext.parent,
            part,
            yRotation = when (info.directionSpecificFrame) {
                Base6Direction3d.Front -> 0.0
                Base6Direction3d.Back -> PI
                Base6Direction3d.Left -> PI / 2.0
                Base6Direction3d.Right -> -PI / 2.0
                else -> error("Invalid wire direction ${info.directionSpecificFrame}")
            }
        )
}

/**
 * Wire renderer without any temperature visualization.
 * To be used for insulated wires or non-thermal wires.
 * We need to apply [PolarModel]'s light override in order to get a smooth transition across the wire.
 * Flywheel's GPU lights won't help us with these discrete instances.
 * The methods are pretty much copied over from [IncandescentWirePartVisual], but with the temperature bits stripped.
 * */
class InsulatedWirePartVisual(
    ctx: MultipartVisualizationContext,
    part: WirePart<*>,
    renderModel: WireRenderModel,
) : WirePartVisual<TransformedLightOverrideInstance, TransformedPolarInstance>(ctx, part, renderModel) {
    private fun createHubInstance() = visualizationContext.instancerProvider()
        .instancer(
            FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE,
            PartialModelHelper.applyMaterial(model.hub, FlwMaterials.SMOOTH_LIT)
        )
        .createInstance()
        .also { it.poseHub() }

    private fun createConnectionInstance(info: PartConnectionDirection, model: PolarModel) = visualizationContext.instancerProvider()
        .instancer(FlwInstanceTypes.TRANSFORMED_POLAR, model.get())
        .createInstance()
        .also { it.poseConnection(info) }

    private fun convertToLightOverride(block: Int) = MyColor(
        map(
            block.toFloat(),
            0.0f, 15.0f,
            0.0f, 255.0f
        ).toInt(),
        255, 255, 255
    )

    private fun evaluateCoreColor() = convertToLightOverride(
        LightTexture.block(
            LevelRenderer.getLightColor(
                part.placement.level,
                part.placement.position
            )
        )
    )

    override fun applyConnectionData(partConnections: IntArray, partialTick: Float) {
        deleteHubInstance()
        deleteConnectionInstances()

        val isFilledVariant = getIsFilledVariant(partConnections)

        if (!isFilledVariant) {
            // If not filled, it means we need a hub (junction):
            hubInstance = createHubInstance()
        }

        val variants = model.connection.variants[isFilledVariant]!!

        for (connection in partConnections) {
            val info = PartConnectionDirection(connection)
            putUniqueConnection(info.value, createConnectionInstance(info, variants[info.mode]!!))
        }

        uploadCoreData()
        uploadRemoteData()
        updateLight(partialTick)
    }

    private fun uploadCoreData() {
        val coreColor = evaluateCoreColor()

        hubInstance?.also {
            it.color(coreColor.r, coreColor.g, coreColor.b)
            it.lightOverride = coreColor.a / 255f
            it.setChanged()
        }

        connectionInstances.values.forEach { instance ->
            instance.color2 = coreColor
            instance.setChanged()
        }
    }

    private fun uploadRemoteData() {
        val coreColor = evaluateCoreColor()

        connectionInstances.forEach { (remoteInfo, instance) ->
            setExteriorPoleColor(instance, coreColor, remoteInfo)
            instance.setChanged()
        }
    }

    private fun setExteriorPoleColor(instance: TransformedPolarInstance, coreColor: MyColor, remoteInfo: Int) {
        val remotePositionWorld = part.placement.position + PartConnectionDirection(remoteInfo).getIncrementInWorldFrame(
            part.placement.facing,
            part.placement.face
        )

        val remoteLightLevel = part.placement.level.getBrightness(
            LightLayer.BLOCK,
            remotePositionWorld
        )

        instance.color1 = MyColor.lerp(
            coreColor,
            convertToLightOverride(remoteLightLevel),
            0.5f
        )
    }

    override fun relightConnection(instance: TransformedPolarInstance, partialTick: Float) {
        val sky = LightTexture.sky(
            LevelRenderer.getLightColor(
                part.placement.level,
                part.placement.position
            )
        )

        instance.light(0, sky)
        uploadCoreData()
        uploadRemoteData()
    }
}

/**
 * Wire renderer that also incorporates the temperature of the wire, and the temperature of the neighbors in the visual.
 * Terminology:
 *  - Core (Internal) Temperature - the temperature of the game object this renderer is bound to.
 *  - External Temperatures - the temperatures of the game objects adjacent to the wire this renderer is bound to.
 *
 * Technique:
 *  - The hub is tinted using our core temperature
 *  - Connections are implemented as [PolarModel]s
 *  1. The poles visually adjacent to our hub are tinted with the color of our hub
 *  2. The pole of a connection that is adjacent to another thermal object is tinted using a geometric rule:
 *      - The desired look is basically like 1 big connection that joins the two hubs
 *      - The poles adjacent to the two hubs are tinted with the color of the respective hubs (1)
 *      - The external poles are tinted with the color of the average temperature between the two respective hubs.
 *          This rule doesn't behave exactly as expected if the remote game object is not a wire, but it is fine for now.
 *          If fixing is needed, the easiest solution is probably to flag the external temperature data from the server so we can know if it's a neighbor wire or not.
 *
 * */
class IncandescentWirePartVisual(
    context: MultipartVisualizationContext,
    part: WirePart<*>,
    model: WireRenderModel
) : WirePartVisual<TransformedLightOverrideInstance, TransformedPolarInstance>(context, part, model) {
    var internalTemperatureVersion = -1
    var externalTemperaturesVersion = -1

    private fun createHubInstance() =
        visualizationContext.instancerProvider()
            .instancer(FlwInstanceTypes.TRANSFORMED_LIGHT_OVERRIDE, Models.partial(model.hub))
            .createInstance()
            .also { it.poseHub() }

    private fun createConnectionInstance(info: PartConnectionDirection, model: PolarModel) =
        visualizationContext.instancerProvider()
            .instancer(FlwInstanceTypes.TRANSFORMED_POLAR, model.get())
            .createInstance()
            .also {
                it.color1 = MyColor(0.0f, 1.0f, 1.0f, 1.0f)
                it.color2 = MyColor(0.0f, 1.0f, 1.0f, 1.0f)
                it.poseConnection(info)
            }

    private fun evaluateCoreColor() : MyColor {
        val renderState = part.renderState

        val lightLevel = LightTexture.block(
            LevelRenderer.getLightColor(
                part.placement.level,
                part.placement.position
            )
        )

        return model.tintColor.evaluateRGBL(
            Quantity(renderState.internalTemperature), lightLevel.toDouble()
        )
    }

    override fun applyConnectionData(partConnections: IntArray, partialTick: Float) {
        deleteHubInstance()
        deleteConnectionInstances()

        val isFilledVariant = getIsFilledVariant(partConnections)

        if (!isFilledVariant) {
            // If not filled, it means we need a hub (junction):
            hubInstance = createHubInstance()
        }

        val models = model.connection.variants[isFilledVariant]!!

        for (connection in partConnections) {
            val info = PartConnectionDirection(connection)
            putUniqueConnection(info.value, createConnectionInstance(info, models[info.mode]!!))
        }

        uploadCoreData()
        uploadRemoteData()
        updateLight(partialTick)
    }

    override fun beginFrame(ctx: DynamicVisual.Context) {
        super.beginFrame(ctx)

        val partRenderState = part.renderState

        val latestInternalTemperatureVersion = partRenderState.internalTemperatureVersion
        if(internalTemperatureVersion != latestInternalTemperatureVersion) {
            internalTemperatureVersion = latestInternalTemperatureVersion
            uploadCoreData()
        }

        val latestExternalTemperaturesVersion = partRenderState.externalTemperatureVersion
        if(externalTemperaturesVersion != latestExternalTemperaturesVersion) {
            externalTemperaturesVersion = latestExternalTemperaturesVersion
            uploadRemoteData()
        }
    }

    /**
     * Updates the core color (the tint of the hub), based on the [WirePart.RenderState.internalTemperature].
     * Also adjusts the core contact colors for all [connectionInstances].
     * */
    private fun uploadCoreData() {
        val coreColor = evaluateCoreColor()

        hubInstance?.also {
            it.color(coreColor.r, coreColor.g, coreColor.b)
            it.lightOverride = coreColor.a / 255f
            it.handle().setChanged()
        }

        for ((remoteInfo, instance) in connectionInstances) {
            instance.color2 = coreColor

            val remoteTemperature = part.renderState.externalTemperatures[remoteInfo]
                ?: // The update scheme checks if the version was incremented and then applies changes based on the data in the render state.
                // There is no synchronization so there is the remote possibility of the data in the render state having changed already.
                // Unless it causes issues, I don't see why we'd capture some immutable snapshots which is more work.
                continue

            // Since the pole color depends on core color, we need to update that as well:
            setExteriorPoleColor(instance, coreColor, remoteTemperature, remoteInfo)

            instance.handle().setChanged()
        }
    }

    /**
     * Updates the remote/external colors (the tint of the outer poles of the [connectionInstances]),
     * for the instances corresponding to entries in [temperatures].
     * */
    private fun uploadRemoteData() {
        val coreColor = evaluateCoreColor()

        for ((remoteInfo, remoteTemperature) in part.renderState.externalTemperatures) {
            val instance = connectionInstances.get(remoteInfo)
                ?: continue

            setExteriorPoleColor(instance, coreColor, remoteTemperature, remoteInfo)

            instance.handle().setChanged()
        }
    }

    /**
     * Sets the exterior pole color of the [instance] to be a blend of the colors of the two hubs.
     * This was done because it's the easiest way to get a continuous-looking wire without having the renderers know about each other.
     * It's not correct if the remote object is not an incandescent wire, but, later down the line, we can also export a flag from the server that tells us if we should apply the average or not.
     * */
    private fun setExteriorPoleColor(instance: TransformedPolarInstance, coreColor: MyColor, remoteTemperature: Double, remoteInfo: Int) {
        val remotePositionWorld = part.placement.position + PartConnectionDirection(remoteInfo).getIncrementInWorldFrame(
            part.placement.facing,
            part.placement.face
        )

        val remoteLightLevel = part.placement.level.getBrightness(
            LightLayer.BLOCK,
            remotePositionWorld
        )

        val remoteColor = model.tintColor.evaluateRGBL(Quantity(remoteTemperature), remoteLightLevel.toDouble())

        instance.color1 = MyColor.lerp(
            coreColor,
            remoteColor,
            0.5f
        )
    }

    override fun relightConnection(instance: TransformedPolarInstance, partialTick: Float) {
        val sky = LightTexture.sky(
            LevelRenderer.getLightColor(
                part.placement.level,
                part.placement.position
            )
        )

        instance.light(0, sky)
        uploadCoreData()
        uploadRemoteData()
    }
}
