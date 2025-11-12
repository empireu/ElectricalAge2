@file:Suppress("MemberVisibilityCanBePrivate", "ClassName")

package org.eln2.mc.common.cells.foundation

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.saveddata.SavedData
import net.minecraftforge.server.ServerLifecycleHooks
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Simulator
import org.ageseries.libage.sim.SubSolverSet
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.electrical.ElectricalCircuitForestBuilder
import org.ageseries.libage.sim.electrical.ElectricalSimulation
import org.ageseries.libage.sim.kinetic.KineticSimulation
import org.ageseries.libage.sim.kinetic.KineticSimulationForestBuilder
import org.ageseries.libage.utils.*
import org.eln2.mc.*
import org.eln2.mc.common.cells.CellRegistry
import org.eln2.mc.common.cells.foundation.CellLayer.*
import org.eln2.mc.common.cells.foundation.SimulationObjectType.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.data.*
import org.eln2.mc.extensions.*
import org.eln2.mc.mathematics.Base6Direction3d
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.isSuperclassOf

/**
 * [SubscriberCollection] that tracks the added subscribers, making it possible to remove all of them at a later time.
 * @param underlyingCollection The parent subscriber collection that will actually run the subscribers.
 * */
class TrackedSubscriberCollection(private val underlyingCollection: SubscriberCollection) : SubscriberCollection {
    private val subscribers = HashMap<SimulationSubscriber, SubscriberOptions>()

    override fun addSubscriber(parameters: SubscriberOptions, subscriber: SimulationSubscriber) {
        require(subscribers.put(subscriber, parameters) == null) { "Duplicate subscriber $subscriber" }
        underlyingCollection.addSubscriber(parameters, subscriber)
    }

    override fun remove(subscriber: SimulationSubscriber) {
        require(subscribers.remove(subscriber) != null) { "Subscriber $subscriber was never added" }
        underlyingCollection.remove(subscriber)
    }

    fun clear() {
        subscribers.keys.forEach { underlyingCollection.remove(it) }
        subscribers.clear()
    }
}

/**
 * Describes the environment the cell sits in.
 * @param ambientTemperature The temperature of the environment.
 * */
data class CellEnvironment(val ambientTemperature: Quantity<Temperature>) {
    companion object {
        fun evaluate(level: Level, pos: Locator): CellEnvironment {
            val positions : Iterable<BlockPos> = if(pos.has(Locators.BLOCK)) {
                listOf(pos.requireLocator(Locators.BLOCK))
            }
            else if(pos.has(Locators.BLOCK_RANGE)) {
                val (a, b) = pos.requireLocator(Locators.BLOCK_RANGE)
                BlockPos.betweenClosed(a, b)
            }
            else {
                error("Locator $pos is not enough for environment")
            }

            val average = Average()

            positions.forEach {
                val biome = level.getBiome(it).value()

                val temperature = Datasets
                    .MINECRAFT_TEMPERATURE_CELSIUS
                    .evaluate(biome.baseTemperature.toDouble())

                average.add(temperature)
            }

            return CellEnvironment(Quantity(average.value, CELSIUS))
        }
    }
}

/**
 * Sets the temperature of the [bodies] to the [CellEnvironment.ambientTemperature].
 * */
fun CellEnvironment.loadTemperature(vararg bodies: ThermalMass) {
    bodies.forEach {
        it.temperature = this.ambientTemperature
    }
}

fun CellEnvironment.connect(simulator: Simulator, vararg bodies: ThermalMass) {
    bodies.forEach {
        simulator.connect(it, this.ambientTemperature)
    }
}

fun CellEnvironment.connect(simulator: Simulator, parameters: ConnectionParameters, vararg bodies: ThermalMass) {
    bodies.forEach {
        simulator.connect(it, this.ambientTemperature, parameters)
    }
}

data class CellCreateInfo(val locator: Locator, val id: ResourceLocation, val environment: CellEnvironment)

/**
 * Marks a field in a [Cell] as [SimulationObject]. The object will be registered automatically.
 * */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class SimObject

/**
 * Marks a field in a [Cell] as [CellBehavior]. The behavior will be registered automatically.
 * */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class Behavior

/**
 * Marks a field in a [Cell] as [CellNode]. The node will be registered automatically.
 * @param id The unique name of the node, used for saving. If left empty, the [sourceName] of the class will be used.
 * */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class Node(val id: String = "")

/**
 * Interface with methods called throughout the lifetime of the cell or cell nodes.
 * Each method dispatches a [CellLifetimeEvent]. Convention:
 * ```
 * Cell_<method name>
 * ```
 * **All methods are called on the server thread, because they are called when the circuit is changed (by a player!)**
 * */
interface CellLifetime {
    /**
     * Called after all graphs in the level have been loaded, before the solver is built.
     * */
    fun onWorldLoadedPreSolver() {
        requireIsOnServerThread {
            "onWorldLoadedPreSolver non-server"
        }
    }
    /**
     * Called after all graphs in the level have been loaded, after the solver is built.
     */
    fun onWorldLoadedPostSolver() {
        requireIsOnServerThread {
            "onWorldLoadedPostSolver non-server"
        }
    }
    /**
     * Called after all graphs in the level have been loaded, before the simulations start.
     * */
    fun onWorldLoadedPreSim() {
        requireIsOnServerThread {
            "onWorldLoadedPreSim non-server"
        }
    }
    /**
     * Called after all graphs in the level have been loaded, after the simulations have started.
     * */
    fun onWorldLoadedPostSim() {
        requireIsOnServerThread {
            "onWorldLoadedPostSim non-server"
        }
    }
    /**
     * Called after the container loaded in.
     * The field is assigned before this is called.
     */
    fun onContainerLoaded() {
        requireIsOnServerThread {
            "onContainerLoaded non-server"
        }
    }
    /**
     * Called when the container is being unloaded (the game object went out-of-scope, where applicable).
     * */
    fun onContainerUnloading() {
        requireIsOnServerThread {
            "onContainerUnloading non-server"
        }
    }
    /**
     * Called after the container was unloaded.
     * */
    fun onContainerUnloaded() {
        requireIsOnServerThread {
            "onContainerUnloaded non-server"
        }
    }
    /**
     * Called when the graph manager completed loading this cell from the disk.
     */
    fun onLoadedFromDisk() {
        requireIsOnServerThread {
            "onLoadedFromDisk non-server"
        }
    }
    /**
     * Called after the cell was connected freshly.
     */
    fun onCreated() {
        requireIsOnServerThread {
            "onCreated non-server"
        }
    }
    /**
     * Called when the cell is being destroyed, right before any operations run.
     * The cell is still in a valid and connected state.
     * */
    fun onBeginDestroy() {
        requireIsOnServerThread {
            "onBeginDestroy non-server"
        }
    }
    /**
     * Called while the cell is being destroyed, just after the simulation was stopped.
     * Subscribers may be cleaned up here.
     * Guaranteed to be on the game thread.
     * */
    fun onDestroying() {
        requireIsOnServerThread {
            "onDestroying non-server"
        }
    }
    /**
     * Called after the cell was destroyed.
     */
    fun onDestroyed() {
        requireIsOnServerThread {
            "onDestroyed non-server"
        }
    }
    /**
     * Called when the graph and/or neighbouring cells are updated. This method is called after completeDiskLoad and setPlaced
     * @param connectionsChanged True if the neighbouring cells changed.
     * @param graphChanged True if the graph that owns this cell has changed.
     */
    fun onUpdate(connectionsChanged: Boolean, graphChanged: Boolean) {
        requireIsOnServerThread {
            "onUpdate non-server"
        }
    }
    /**
     * Called when subscribers should be added, after the graph changes.
     * This is called before [SimulationObject.subscribe].
     * Calling the super method is not needed, by convention.
     * */
    fun subscribe(subscribers: SubscriberCollection) {
        requireIsOnServerThread {
            "subscribe non-server"
        }
    }
    /**
     * Called when the build started, right after the connections were cleared.
     * */
    fun onBuildStarted() {
        requireIsOnServerThread {
            "onBuildStarted non-server"
        }
    }
    /**
     * Called when the solver is built, before the simulation is started.
     * */
    fun onBuildFinished() {
        requireIsOnServerThread {
            "onBuildFinished non-server"
        }
    }
}

/**
 * Represents an event sent throughout the lifetime of the cell.
 * Each event is called when a method in [CellLifetime] is called on the [Cell].
 * */
interface CellLifetimeEvent : Event

/**
 * Called after all graphs in the level have been loaded, before the solver is built.
 * */
object Cell_onWorldLoadedPreSolver : CellLifetimeEvent
/**
 * Called after all graphs in the level have been loaded, after the solver is built.
 */
object Cell_onWorldLoadedPostSolver : CellLifetimeEvent
/**
 * Called after all graphs in the level have been loaded, before the simulations start.
 * */
object Cell_onWorldLoadedPreSim : CellLifetimeEvent
/**
 * Called after all graphs in the level have been loaded, after the simulations have started.
 * */
object Cell_onWorldLoadedPostSim : CellLifetimeEvent
/**
 * Called after the container loaded in.
 * The field is assigned before this is called.
 */
object Cell_onContainerLoaded : CellLifetimeEvent
/**
 * Called when the container is being unloaded (the game object Cell_went out-of-scope, where applicable).
 * */
object Cell_onContainerUnloading : CellLifetimeEvent
/**
 * Called after the container was unloaded.
 * */
object Cell_onContainerUnloaded : CellLifetimeEvent
/**
 * Called when the graph manager completed loading this cell from the disk.
 */
object Cell_onLoadedFromDisk : CellLifetimeEvent
/**
 * Called after the cell was connected freshly.
 */
object Cell_onCreated : CellLifetimeEvent
/**
 * Called when the cell is being destroyed, right before any operations run.
 * The cell is still in a valid and connected state.
 * */
object Cell_onBeginDestroy : CellLifetimeEvent
/**
 * Called while the cell is being destroyed, just after the simulation was stopped.
 * Subscribers may be cleaned up here.
 * Guaranteed to be on the game thread.
 * */
object Cell_onDestroying : CellLifetimeEvent
/**
 * Called after the cell was destroyed.
 */
object Cell_onDestroyed : CellLifetimeEvent
/**
 * Called when the graph and/or neighbouring cells are updated. This method is called after completeDiskLoad and setPlaced
 * @param connectionsChanged True if the neighbouring cells changed.
 * @param graphChanged True if the graph that owns this cell has changed.
 */
data class Cell_onUpdate(val connectionsChanged: Boolean, val graphChanged: Boolean) : CellLifetimeEvent
/**
 * Called when subscribers should be added, after the graph changes.
 * This is called before [SimulationObject.subscribe].
 * Calling the super method is not needed, by convention.
 * */
data class Cell_subscribe(val subscribers: SubscriberCollection) : CellLifetimeEvent
/**
 * Called when the build started, right after the connections were cleared.
 * */
object Cell_onBuildStarted : CellLifetimeEvent
/**
 * Called when the solver is built, before the simulation is started.
 * */
object Cell_onBuildFinished : CellLifetimeEvent

/**
 * Indicates the container of the cell. It is used for implicit connection filtering, during placement:
 * - [Block] can connect to [Block] and [Part]
 * - [Part] can connect to [Part] and [Block]
 * - [Spec] doesn't connect on placement
 * */
enum class CellLayer(val id: Byte) {
    Block(1.toByte()),
    Part(2.toByte()),
    Spec(3.toByte());

    companion object {
        fun fromId(id: Byte) = when(id) {
            Block.id -> Block
            Part.id -> Part
            Spec.id -> Spec
            else -> error("Invalid cell layer $id")
        }
    }
}

/**
 * The cell is a physical unit, that may participate in multiple simulations. Each simulation will
 * have a Simulation Object associated with it.
 * Cells create connections with other cells, and objects create connections with other objects of the same simulation type.
 * */
@ServerOnly
abstract class Cell(val locator: Locator, val id: ResourceLocation, val environmentData: CellEnvironment) : CellLifetime {
    companion object {
        private val OBJECT_READERS = ConcurrentHashMap<Class<*>, List<FieldInfo<Cell>>>()
        private val BEHAVIOR_READERS_FIELD = ConcurrentHashMap<Class<*>, List<FieldInfo<Cell>>>()
        private val BEHAVIOR_READERS_LAZY = ConcurrentHashMap<Class<*>, List<FieldInfo<Cell>>>()
        private val CELL_NODE_READERS = ConcurrentHashMap<Class<*>, List<FieldInfo<Cell>>>()

        private const val CELL_DATA = "cellData"
        private const val OBJECT_DATA = "objectData"
        private const val NODE_DATA = "nodeData"
        private const val UNIQUE_NODES = "unique"
        private const val REPEATABLE_NODES = "repeatable"
        private const val NODE_ID = "id"
        private const val NODE_CLASS_ID = "type"
        private const val NODE_TAG = "data"
    }

    constructor(ci: CellCreateInfo) : this(ci.locator, ci.id, ci.environment)

    // Persistent behaviors are used by cell logic, and live throughout the lifetime of the cell:
    private var persistentPoolInternal: TrackedSubscriberCollection? = null

    // Transient behaviors are used when the cell is in range of a player (and the game object exists):
    private var transientPoolInternal: TrackedSubscriberCollection? = null

    val persistentPool get() = persistentPoolInternal ?: error("Invalid access to persistent pool")

    lateinit var graph: CellGraph
    var connections: ArrayList<Cell> = ArrayList(0)

    /**
     * Event bus where all calls from [CellLifetime] are also directed.
     * */
    val lifetimeEvents = EventBus()

    private val uniqueContainer = UniqueNodeContainer()
    private val repeatableContainer = RepeatableNodeContainer()

    //#region Lifetime Hooks

    private fun dispatchLifetime(event: CellLifetimeEvent) {
        lifetimeEvents.send(event)
    }

    override fun onWorldLoadedPreSolver() {
        super.onWorldLoadedPreSolver()
        dispatchLifetime(Cell_onWorldLoadedPreSolver)
    }

    override fun onWorldLoadedPostSolver() {
        super.onWorldLoadedPostSolver()
        dispatchLifetime(Cell_onWorldLoadedPostSolver)
    }

    override fun onWorldLoadedPreSim() {
        super.onWorldLoadedPreSim()
        dispatchLifetime(Cell_onWorldLoadedPreSim)
    }

    override fun onWorldLoadedPostSim() {
        super.onWorldLoadedPostSim()
        dispatchLifetime(Cell_onWorldLoadedPostSim)
    }

    override fun onContainerLoaded() {
        super.onContainerLoaded()
        dispatchLifetime(Cell_onContainerLoaded)
    }

    override fun onContainerUnloading() {
        super.onContainerUnloading()
        dispatchLifetime(Cell_onContainerUnloading)
    }

    override fun onContainerUnloaded() {
        super.onContainerUnloaded()
        dispatchLifetime(Cell_onContainerUnloaded)
    }

    override fun onLoadedFromDisk() {
        super.onLoadedFromDisk()
        dispatchLifetime(Cell_onLoadedFromDisk)
    }

    override fun onCreated() {
        super.onCreated()
        dispatchLifetime(Cell_onCreated)
    }

    override fun onBeginDestroy() {
        super.onBeginDestroy()
        dispatchLifetime(Cell_onBeginDestroy)
    }

    override fun subscribe(subscribers: SubscriberCollection) {
        super.subscribe(subscribers)
        dispatchLifetime(Cell_subscribe(subscribers))
    }

    override fun onBuildStarted() {
        super.onBuildStarted()
        dispatchLifetime(Cell_onBuildStarted)
    }

    override fun onBuildFinished() {
        super.onBuildFinished()
        dispatchLifetime(Cell_onBuildFinished)
    }

    //#endregion

    /**
     * Special function called after the provider creates an instance of this cell.
     * */
    open fun afterConstruct() {
        buildNodeContainers()
    }

    private fun buildNodeContainers() {
        fieldScan(this.javaClass, CellNode::class, Node::class.java, CELL_NODE_READERS).forEach {
            val obj = it.reader.get(this)

            val node = checkNotNull(obj as? CellNode) {
                "Invalid cell node ${it.reader.get(this)}"
            }

            val annotation = it.field.getAnnotation(Node::class.java)

            val id = annotation.id.ifBlank {
                node.javaClass.sourceName()
            }

            when (node) {
                is UniqueCellNode -> {
                    uniqueContainer.addUnique(id, node)
                }

                is RepeatableCellNode -> {
                    repeatableContainer.addUnique(id, node)
                }

                else -> {
                    error("Invalid cell node $node")
                }
            }
        }
    }

    /**
     * Gets a unique node by its class.
     * */
    fun getNode(uniqueNodeClass: Class<*>) = uniqueContainer.get(uniqueNodeClass)

    /**
     * Adds an unique node.
     * */
    fun addNode(uniqueNodeId: String, uniqueNode: UniqueCellNode) {
        uniqueContainer.addUnique(uniqueNodeId, uniqueNode)
        setChanged()
    }

    open fun allowsConnection(remote: Cell): Boolean {
        val result = cellConnectionPredicate(remote) && objectConnectionPredicate(remote)

        // Discuss with me if you want more info
        if(!result && connections.contains(remote)) {
            LOG.warn("Forcing connection rule")
            return true
        }

        return result
    }

    /*
     * Implicit filtering behavior for all cells.
     * With the growing complexity (multiple electrical sizes per cell, multiple wire sizes), each cell started needing connection predicates.
     * I have distilled that logic into these default filtering rules, based on "sizes".
    */

    /**
     * Checks if the remote cell is a [GridConnectionCell].
     * */
    protected open fun defaultExclusivelyGridConnectionPredicate(remote: Cell) : Boolean {
        return remote is GridConnectionCell
    }

    /**
     * Checks if the thermal connection sizes on the sides of this and [remote] that are in contact are compatible.
     * */
    protected open fun defaultThermalConnectionPredicate(remote: Cell) : Boolean {
        return !connectionSizeRejection<SidedThermal<*>, ThermalSize>(this, remote, ThermalSize.compatibility) {
            int, dir, b -> int.getThermalSizeOnSide(dir, b)
        }
    }

    /**
     * Checks if the electrical connection sizes on the sides of this and [remote] that are in contact are compatible, or if the remote cell is a [GridConnectionCell].
     * It's correct to allow the connection if it's a [GridConnectionCell] because this evaluation can only happen after the grid's own filtering and rules.
     * */
    protected open fun defaultElectricalConnectionPredicate(remote: Cell) : Boolean {
        return remote is GridConnectionCell || !connectionSizeRejection<SidedElectrical<*>, ElectricalSize>(this, remote, ElectricalSize.compatibility) {
            int, dir, b -> int.getElectricalSizeOnSide(dir, b)
        }
    }

    protected open fun defaultKineticConnectionPredicate(remote: Cell) : Boolean {
        return !connectionSizeRejection<SidedKinetic<*>, KineticSize>(this, remote, KineticSize.compatibility) {
            int, dir, b -> int.getKineticSizeOnSide(dir, b)
        }
    }

    // Default to just the cell predicates:

    open fun thermalObjectPredicate(remote: ThermalObject<*>) = defaultThermalConnectionPredicate(remote.cell)
    open fun electricalObjectPredicate(remote: ElectricalObject<*>) = defaultElectricalConnectionPredicate(remote.cell)
    open fun kineticObjectPredicate(remote: KineticObject<*>) = defaultKineticConnectionPredicate(remote.cell)

    /**
     * If true, the default connection predicate will check only if the remote cell is a [GridConnectionCell].
     * P.S.
     * */
    open val isExclusivelyGridConnected get() = false

    /**
     * If true, the default connection predicate will, instead of allowing connection if a **thermal OR electrical OR kinetic connection** is possible, allow the cell connection only if **the default thermal predicate reports true**.
     * Use it for transport devices only. *P.S. I haven't found a legitimate use for it yet.*
     * */
    open val isExclusivelyThermalConnected get() = false

    /**
     * If true, the default connection predicate will, instead of allowing connection if a **thermal OR electrical OR kinetic connection** is possible, allow the cell connection only if **the default electrical predicate reports true**.
     * Use it for transport devices only. For example, set it to true for an electrical wire that exports both thermal and electrical connections to the same sides, so it's not allowed to connect to a thermal conduit.
     * */
    open val isExclusivelyElectricalConnected get() = false

    /**
     * If true, the default connection predicate will, instead of allowing connection if a **thermal OR electrical OR kinetic connection** is possible, allow the cell connection only if **the default kinetic predicate reports true**.
     * Use it for transport devices only. For example, set it to true for a shaft that exports both thermal and kinetic connections to the same sides, so it's not allowed to connect to a thermal conduit.
     * */
    open val isExclusivelyKineticConnected get() = false

    /**
     * Checks if this cell accepts a connection from the remote cell.
     * By default, the connection is allowed if any of the default predicates ([defaultThermalConnectionPredicate], [defaultElectricalConnectionPredicate]) return true.
     * That behavior can be changed with the toggles:
     * - [isExclusivelyGridConnected] - only for pure grid devices (e.g. anchors)
     * - [isExclusivelyElectricalConnected] - e.g. for electrical wires, that, because they export electrical and thermal connections on all sides, would be allowed to connect to thermal wires by the default predicate
     * @return True if the connection is accepted. Otherwise, false.
     * */
    protected open fun cellConnectionPredicate(remote: Cell) : Boolean {
        // Sanity check:
        var i = 0
        if(isExclusivelyGridConnected) { i++ }
        if(isExclusivelyElectricalConnected) { i++ }
        if(isExclusivelyThermalConnected) { i++ }
        if(isExclusivelyKineticConnected) { i++ }

        if(i > 1) {
            DEBUGGER_BREAK()
        }

        if(isExclusivelyGridConnected) {
            return defaultExclusivelyGridConnectionPredicate(remote)
        }

        if(isExclusivelyThermalConnected) {
            return defaultThermalConnectionPredicate(remote)
        }

        if(isExclusivelyElectricalConnected) {
            return defaultElectricalConnectionPredicate(remote)
        }

        if(isExclusivelyKineticConnected) {
            return defaultKineticConnectionPredicate(remote)
        }

        // By default, allow *Cell-Cell* connection if any of the exported simulation domain connection sizes coincide:
        if(this.hasObject(Thermal) && defaultThermalConnectionPredicate(remote)) {
            return true
        }

        if(this.hasObject(Electrical) && defaultElectricalConnectionPredicate(remote)) {
            return true
        }

        if(this.hasObject(Kinetic) && defaultKineticConnectionPredicate(remote)) {
            return true
        }

        return false // Disallow because no connection sizes coincide.
    }

    /**
     * Checks if a connection between this cell and [remote] makes sense. This is determined to be the case if any objects will form connections.
     * **SPECIAL CARE MUST BE TAKEN to ensure that the results are consistent with the actual [connections]**
     * @return True if the connection is accepted. Otherwise, false.
     * */
    protected open fun objectConnectionPredicate(remote: Cell) : Boolean {
        return countPossibleConnections(remote) > 0
    }

    /**
     * Counts the number of object connections that will happen between this cell and the other cell.
     * */
    fun countPossibleConnections(remote: Cell) : Int {
        var count = 0

        this.objects.forEachObject { localObj ->
            if(remote.hasObject(localObj.type)) {
                val remoteObj = remote.objects[localObj.type]

                when(localObj.type) {
                    Electrical -> {
                        localObj as ElectricalObject
                        remoteObj as ElectricalObject

                        if(localObj.acceptsRemoteObject(remoteObj) && remoteObj.acceptsRemoteObject(localObj)) {
                            count++
                        }
                    }
                    Thermal -> {
                        localObj as ThermalObject
                        remoteObj as ThermalObject

                        if(localObj.acceptsRemoteObject(remoteObj) && remoteObj.acceptsRemoteObject(localObj)) {
                            count++
                        }
                    }
                    Kinetic -> {
                        localObj as KineticObject
                        remoteObj as KineticObject

                        if(localObj.acceptsRemoteObject(remoteObj) && remoteObj.acceptsRemoteObject(localObj)) {
                            count++
                        }
                    }
                }
            }
        }

        return count
    }

    private val replicators = ArrayList<ReplicatorBehavior>()

    /**
     * Marks this cell as dirty.
     * If [hasGraph], [CellGraph.setChanged] is called to ensure the cell data will be saved.
     * */
    fun setChanged() {
        if (hasGraph) {
            graph.setChanged()
        }
    }

    /**
     * Marks this cell as dirty, if [value] is true.
     * */
    fun setChangedIf(value: Boolean) {
        if(value) {
            setChanged()
        }
    }

    inline fun setChangedIf(value: Boolean, action: () -> Unit) {
        if(value) {
            setChanged()
            action()
        }
    }

    val hasGraph get() = this::graph.isInitialized

    fun removeConnection(cell: Cell) {
        if (!connections.remove(cell)) {
            error(DEBUGGER_BREAK("Tried to remove non-existent connection"))
        }
    }

    var container: CellContainer? = null

    private val objectsLazy = lazy {
        val objectFields = fieldScan(this.javaClass, SimulationObject::class, SimObject::class.java, OBJECT_READERS)

        val fields = HashMap<SimulationObject<*>, FieldInfo<Cell>>()

        SimulationObjectSet(objectFields.mapNotNull {
            val o = it.reader.get(this) as? SimulationObject<*>

            if(o != null) {
                require(fields.put(o, it) == null) {
                    DEBUGGER_BREAK("Duplicate obj $o")
                }
            }

            o
        })
    }

    val objects get() = objectsLazy.value

    var isBeingRemoved = false
        private set

    private val behaviorContainer by lazy {
        createBehaviorContainer()
    }

    protected open fun createBehaviorContainer() = CellBehaviorContainer().also { container ->
        fun behaviorCast(value: Any?) = if(value == null) {
            null
        }
        else {
            checkNotNull(value as? CellBehavior) {
                "Invalid behavior $value"
            }
        }
        
        val allowedTypes = listOf(
            CellBehavior::class,
            Lazy::class,
        )

        val disallowedTypes = listOf(
            ReplicatorBehavior::class
        )

        fun handleInvalid(property: KProperty1<*, *>) {
            val k = checkNotNull(property.returnType.classifier as? KClass<*>) {
                "Invalid return type of $property"
            }

            if(!allowedTypes.any { it.isSuperclassOf(k) }) {
                error("Invalid behavior property $property ($k)")
            }

            if(disallowedTypes.any { it.isSuperclassOf(k) }) {
                error("Using $property ($k) as behavior is disallowed")
            }
        }

        fieldScan(this.javaClass, CellBehavior::class, Behavior::class.java, BEHAVIOR_READERS_FIELD, ::handleInvalid)
            .asSequence()
            .mapNotNull { behaviorCast(it.reader.get(this)) }
            .forEach(container::addToCollection)

        fieldScan(this.javaClass, Lazy::class, Behavior::class.java, BEHAVIOR_READERS_LAZY, ::handleInvalid)
            .asSequence()
            .mapNotNull { it.reader.get(this) as? Lazy<*> }
            .mapNotNull { behaviorCast(it.value) }
            .forEach(container::addToCollection)
    }

    fun createTag() = CompoundTag().apply {
        withSubTagOptional(CELL_DATA, saveCellData())
        putSubTag(OBJECT_DATA) { saveObjectData(it) }
        putSubTag(NODE_DATA) {
            saveNodeList(uniqueContainer, it, UNIQUE_NODES)
            saveNodeList(repeatableContainer, it, REPEATABLE_NODES)
        }
    }

    fun loadTag(tag: CompoundTag) {
        tag.useSubTagIfPreset(CELL_DATA, this::loadCellData)
        tag.useSubTagIfPreset(OBJECT_DATA, this::loadObjectData)
        tag.useSubTagIfPreset(NODE_DATA) {
            loadNodeList(uniqueContainer, it, UNIQUE_NODES)
            loadNodeList(repeatableContainer, it, REPEATABLE_NODES)
        }
    }

    /**
     * Called when the graph is being saved. Custom data should be saved here.
     * */
    protected open fun saveCellData(): CompoundTag? = null

    private fun saveObjectData(tag: CompoundTag) {
        objects.forEachObject { obj ->
            if (obj is PersistentObject) {
                tag.put(obj.type.domain, obj.saveObjectNbt())
            }
        }
    }

    private fun saveNodeList(container: NodeContainer<*>, tag: CompoundTag, listId: String) {
        val list = ListTag()

        container.mapByName.forEach { (id, node) ->
            val nodeCompound = CompoundTag()

            nodeCompound.putString(NODE_ID, id)
            nodeCompound.putString(NODE_CLASS_ID, node.javaClass.sourceName())

            val nodeTag = node.saveNodeData()

            if(nodeTag != null) {
                nodeCompound.put(NODE_TAG, nodeTag)
            }

            list.add(nodeCompound)
        }

        tag.put(listId, list)
    }

    /**
     * Called when the graph is being loaded. Custom data saved by [saveCellData] will be passed here.
     * */
    protected open fun loadCellData(tag: CompoundTag) {}

    private fun loadObjectData(tag: CompoundTag) {
        objects.forEachObject { obj ->
            if (obj is PersistentObject) {
                obj.loadObjectNbt(tag.getCompound(obj.type.domain))
            }
        }
    }

    private fun loadNodeList(container: NodeContainer<*>, tag: CompoundTag, listId: String) {
        if(tag.contains(listId)) {
            tag.getListTag(listId).forEachCompound { nodeCompound ->
                val nodeId = nodeCompound.getString(NODE_ID)
                val classId = nodeCompound.getString(NODE_CLASS_ID)
                val nodeTag = if(nodeCompound.contains(NODE_TAG)) {
                    nodeCompound.getCompound(NODE_TAG)
                }
                else {
                    null
                }

                val node = container.get(nodeId)

                if(node == null) {
                    LOG.fatal("UNRECOGNISED NODE $nodeId (@$classId) [${nodeTag}]")
                    DEBUGGER_BREAK()
                }
                else {
                    if(nodeTag != null) {
                        node.loadNodeData(nodeTag)
                    }
                }
            }
        }
    }

    fun bindGameObjects(objects: List<Any>) {
        // Not null, it is initialized when added to graph (so the SubscriberCollection is available)
        val transient = this.transientPoolInternal
            ?: error("Transient pool is null in bind")

        require(replicators.isEmpty()) { "Lingering replicators in bind" }

        objects.forEach { obj ->
            fun bindReplicator(behavior: ReplicatorBehavior) {
                behaviorContainer.addToCollection(behavior)
                replicators.add(behavior)
            }

            Replicators.replicatorScan(
                cellK = this.javaClass.kotlin,
                containerK = obj.javaClass.kotlin,
                cellInst = this,
                containerInst = obj
            ).forEach { bindReplicator(it) }
        }

        replicators.forEach { replicator ->
            replicator.subscribe(transient)
        }
    }

    fun unbindGameObjects() {
        requireIsOnServerThread { "unbindGameObjects" }

        val transient = this.transientPoolInternal
            ?: error("Transient null in unbind")

        replicators.forEach {
            behaviorContainer.destroy(it)
        }

        replicators.clear()
        transient.clear()
    }

    override fun onDestroying() {
        super.onDestroying()
        dispatchLifetime(Cell_onDestroying)
        isBeingRemoved = true
        behaviorContainer.destroy()
        persistentPoolInternal?.clear()
    }

    override fun onDestroyed() {
        super.onDestroyed()
        dispatchLifetime(Cell_onDestroyed)
        objects.forEachObject { it.destroy() }
    }

    private var lastLevel: Level? = null

    override fun onUpdate(connectionsChanged: Boolean, graphChanged: Boolean) {
        super.onUpdate(connectionsChanged, graphChanged)
        dispatchLifetime(Cell_onUpdate(connectionsChanged, graphChanged))

        if (graphChanged) {
            if(lastLevel != null) {
                if(lastLevel != graph.level) {
                    LOG.fatal(DEBUGGER_BREAK("ELN2 illegal switch level $lastLevel ${graph.level}"))
                }
            }

            lastLevel = graph.level

            persistentPoolInternal?.clear()
            transientPoolInternal?.clear()

            persistentPoolInternal = TrackedSubscriberCollection(graph.simulationSubscribers)
            transientPoolInternal = TrackedSubscriberCollection(graph.simulationSubscribers)

            behaviorContainer.behaviors.forEach {
                it.subscribe(persistentPoolInternal!!)
            }

            subscribe(persistentPoolInternal!!)
        }

        objects.forEachObject {
            it.update(connectionsChanged, graphChanged)
        }
    }

    /**
     * Called when the solver is being built, in order to clear and prepare the objects.
     * */
    open fun clearObjectConnections() {
        objects.forEachObject { it.clear() }
    }

    /**
     * Called when the solver is being built, in order to record all object-object connections.
     * */
    fun recordObjectConnections() {
        objects.forEachObject { localObj ->
            for (remoteCell in connections) {
                check(remoteCell.connections.contains(this)) {
                    "Mismatched connection set"
                }

                if (!remoteCell.hasObject(localObj.type)) {
                    continue
                }

                val remoteObj = remoteCell.objects[localObj.type]

                when (localObj.type) {
                    Electrical -> {
                        localObj as ElectricalObject
                        remoteObj as ElectricalObject

                        if(!localObj.acceptsRemoteObject(remoteObj) || !remoteObj.acceptsRemoteObject(localObj)) {
                            continue
                        }

                        localObj.addConnection(remoteObj)
                    }

                    Thermal -> {
                        localObj as ThermalObject
                        remoteObj as ThermalObject

                        if(!localObj.acceptsRemoteObject(remoteObj) || !remoteObj.acceptsRemoteObject(localObj)) {
                            continue
                        }

                        localObj.addConnection(remoteObj)
                    }

                    Kinetic -> {
                        localObj as KineticObject
                        remoteObj as KineticObject

                        if(!localObj.acceptsRemoteObject(remoteObj) || !remoteObj.acceptsRemoteObject(localObj)) {
                            continue
                        }

                        localObj.addConnection(remoteObj)
                    }
                }
            }
        }
    }

    /**
     * Checks if this cell has the specified simulation object type.
     * @return True if this cell has the required object. Otherwise, false.
     * */
    fun hasObject(type: SimulationObjectType) = objects.hasObject(type)

    private abstract class NodeContainer<N : CellNode> {
        val mapByName = HashMap<String, N>()

        fun get(id: String) = mapByName[id]

        open fun addUnique(id: String, node: N) {
            requireIsOnServerThread {
                "Add node non-server"
            }

            mapByName.putUnique(id, node) { "Duplicate add cell node $node ($id)" }
        }
    }

    private class RepeatableNodeContainer : NodeContainer<RepeatableCellNode>()

    private class UniqueNodeContainer : NodeContainer<UniqueCellNode>() {
        val mapByClass = HashMap<Class<*>, UniqueCellNode>()

        fun get(nodeClass: Class<*>) = mapByClass[nodeClass]

        override fun addUnique(id: String, node: UniqueCellNode) {
            super.addUnique(id, node)

            mapByClass.putUnique(node.javaClass, node) {
                "Duplicate add unique cell node $node ($id)"
            }
        }
    }
}

fun Cell.self() = this

/**
 * Gets a unique node by its class [T].
 * */
inline fun<reified T : UniqueCellNode> Cell.getNode() = getNode(T::class.java) as? T

/**
 * Checks if cell has [T].
 * */
inline fun<reified T : UniqueCellNode> Cell.hasNode() = getNode(T::class.java) != null

inline fun<reified T : UniqueCellNode> Cell.ifNode(action: (T) -> Unit) : Boolean {
    val instance = getNode<T>()

    return if(instance != null) {
        action(instance)
        true
    }
    else {
        false
    }
}

/**
 * Gets a unique node by its class [T]. Throws if the node doesn't exist.
 * */
inline fun<reified T : UniqueCellNode> Cell.requireNode(message: () -> String) = requireNotNull(getNode<T>(), message)

/**
 * Gets a unique node by its class. Throws if the node doesn't exist.
 * */
inline fun<reified T : UniqueCellNode> Cell.requireNode() = requireNotNull(getNode<T>()) {
    "The required node ${T::class.java} was not present in $this"
}

fun isConnectionAcceptedByGameObjectProximity(a: Cell, b: Cell) : Boolean {
    val layerA = a.locator.get(Locators.CELL_LAYER)
    val layerB = b.locator.get(Locators.CELL_LAYER)

    val layersCompatible = if(layerA != null && layerB != null) {
        when(layerA) {
            Block -> layerB == Block || layerB == Part
            Part -> layerB == Block || layerB == Part
            Spec -> false
        }
    }
    else {
        true
    }

    return layersCompatible && a.allowsConnection(b) && b.allowsConnection(a)
}

/**
 * [CellConnections] has all Cell-Cell connection logic and is responsible for building *physical* networks.
 * There are two key algorithms here:
 * - Cell Insertion
 *      - Inserts a cell into the world, and may form connections with other cells.
 *
 * - Cell Deletion
 *      - Deletes a cell from the world, and may result in many topological changes to the associated graph.
 *        An example would be the removal (deletion) of a cut vertex. This would result in the graph splintering into multiple disjoint graphs.
 *        This is the most intensive part of the algorithm. It may be optimized (the algorithm implemented here is certainly suboptimal),
 *        but it has been determined that this is not a cause for concern,
 *        as it only represents a small slice of the performance impact caused by network updates.
 *
 *
 * @see <a href="https://en.wikipedia.org/wiki/Biconnected_component">Wikipedia - Bi-connected component</a>
 * */
object CellConnections {
    /**
     * Inserts a cell into a graph. It may create connections with other cells, and cause
     * topological changes to related networks.
     * */
    fun insertFresh(container: CellContainer, cell: Cell) {
        connectCell(cell, container)
        cell.onCreated()
    }

    /**
     * Removes a cell from the graph. It may cause topological changes to the graph, as outlined in the top document.
     * */
    fun destroy(cellInfo: Cell, container: CellContainer) {
        cellInfo.onBeginDestroy()
        disconnectCell(cellInfo, container)
        cellInfo.onDestroyed()
    }

    fun connectCell(insertedCell: Cell, container: CellContainer) {
        val manager = container.manager
        val neighborInfoList = container.neighborScan(insertedCell).also { neighbors ->
            val testSet = neighbors.mapTo(HashSet(neighbors.size)) { it.neighbor }

            if (testSet.size != neighbors.size) {
                LOG.fatal("UNEXPECTED MULTIPLE CELLS")
                DEBUGGER_BREAK()
            }
        }
        val neighborCells = neighborInfoList.map { it.neighbor }.toHashSet()

        // Stop all running simulations

        neighborCells
            .map { it.graph }
            .distinct()
            .forEach {
                it.ensureStopped()
                it.captureAllInScope()
            }

        if(insertedCell.hasGraph) {
            insertedCell.graph.captureAllInScope()
        }

        /*
        * Cases:
        *   1. We don't have any neighbors. We must create a new circuit.
        *   2. We have a single neighbor. We can add this cell to their circuit.
        *   3. We have multiple neighbors, but they are part of the same circuit. We can add this cell to the common circuit.
        *   4. We have multiple neighbors, and they are part of different circuits. We need to create a new circuit,
        *       that contains the cells of the other circuits, plus this one.
        * */

        // This is common logic for all cases

        insertedCell.connections = ArrayList(neighborInfoList.map { it.neighbor })

        neighborInfoList.forEach { neighborInfo ->
            neighborInfo.neighbor.connections.add(insertedCell)
            neighborInfo.container.onCellConnected(
                neighborInfo.neighbor,
                insertedCell
            )

            container.onCellConnected(insertedCell, neighborInfo.neighbor)
        }

        if (neighborInfoList.isEmpty()) {
            // Case 1. Create new circuit

            val graph = manager.createGraph()

            graph.addCell(insertedCell)

            graph.setChanged()
        } else if (isCommonGraph(neighborInfoList)) {
            // Case 2 and 3. Join the existing circuit.

            val graph = neighborInfoList[0].neighbor.graph

            graph.addCell(insertedCell)

            graph.setChanged()

            // Send connection update to the neighbor (the graph has not changed):
            neighborInfoList.forEach {
                it.neighbor.onUpdate(
                    connectionsChanged = true,
                    graphChanged = false
                )
            }
        } else {
            // Case 4. We need to create a new circuit, with all cells and this one.

            // Identify separate graphs:
            val disjointGraphs = neighborInfoList.map { it.neighbor.graph }.distinct()

            // Create new graph that will eventually have all cells and the inserted one:
            val graph = manager.createGraph()

            // Register inserted cell:
            graph.addCell(insertedCell)

            // Copy cells over to the new circuit and destroy previous circuits:
            disjointGraphs.forEach { existingGraph ->
                existingGraph.copyTo(graph)

                /*
                * We also need to refit the existing cells.
                * Connections of the remote cells have changed only if the remote cell is a neighbor of the inserted cell.
                * This is because inserting a cell cannot remove connections, and new connections appear only between the new cell and cells from other circuits (the inserted cell is a cut vertex)
                * */
                existingGraph.forEach { cell ->
                    cell.graph = graph

                    cell.onUpdate(
                        connectionsChanged = neighborCells.contains(cell), // As per the above explanation
                        graphChanged = true // We are destroying the old graph and copying, so this is true
                    )

                    cell.container?.onTopologyChanged()
                }

                // And now destroy the old graph:
                existingGraph.destroy()
            }

            graph.setChanged()
        }

        insertedCell.graph.buildSolver()

        /*
        * The inserted cell had a "complete" update.
        * Because it was inserted into a new network, its neighbors have changed (connectionsChanged is true).
        * Then, because it is inserted into a new graph, graphChanged is also true:
        * */
        insertedCell.onUpdate(connectionsChanged = true, graphChanged = true)
        insertedCell.container?.onTopologyChanged()

        // And now resume/start the simulation:
        insertedCell.graph.startSimulation()
    }

    fun disconnectCell(actualCell: Cell, actualContainer: CellContainer, notify: Boolean = true) {
        val manager = actualContainer.manager

        val connections = actualCell.connections.map {
            CellAndContainerHandle.captureInScope(it)
        }

        connections
            .map { it.neighbor.graph }
            .distinct()
            .forEach {
                it.captureAllInScope()
            }

        val graph = actualCell.graph

        if (!graph.isSimulating) {
            DEBUGGER_BREAK()
        }

        // Stop Simulation
        graph.stopSimulation()

        graph.captureAllInScope()

        if (notify) {
            actualCell.onDestroying()
        }

        val markedNeighbors = actualCell.connections.toHashSet()

        connections.forEach { (neighbor, neighborContainer) ->
            val containsA = actualCell.connections.contains(neighbor)
            val containsB = neighbor.connections.contains(actualCell)

            if (containsA && containsB) {
                actualCell.removeConnection(neighbor)
                neighbor.removeConnection(actualCell)

                neighborContainer.onCellDisconnected(neighbor, actualCell)
                actualContainer.onCellDisconnected(actualCell, neighbor)

                markedNeighbors.remove(neighbor)
            } else if (containsA != containsB) {
                error(DEBUGGER_BREAK("Mismatched connection vs query result"))
            }
        }

        if (markedNeighbors.isNotEmpty()) {
            error(DEBUGGER_BREAK("Lingering connections $actualCell $markedNeighbors"))
        }

        /*
        *   Cases:
        *   1. We don't have any neighbors. We can destroy the circuit.
        *   2. We have a single neighbor. We can remove ourselves from the circuit.
        *   3. We have multiple neighbors, and we are not a cut vertex. We can remove ourselves from the circuit.
        *   4. We have multiple neighbors, and we are a cut vertex. We need to remove ourselves, find the new disjoint graphs,
        *        and rebuild the circuits.
        */

        if (connections.isEmpty()) {
            // Case 1. Destroy this circuit.

            // Make sure we don't make any logic errors somewhere else.
            check(graph.size == 1) {
                DEBUGGER_BREAK("disconnectCell - case 1")
            }

            graph.destroy()
        } else if (connections.size == 1) {
            // Case 2.

            // Remove the cell from the circuit.
            graph.removeCell(actualCell)

            val neighbor = connections[0].neighbor

            neighbor.onUpdate(connectionsChanged = true, graphChanged = false)

            graph.buildSolver()
            graph.startSimulation()
            graph.setChanged()
        } else {
            // Case 3 and 4. Implement a more sophisticated algorithm, if necessary.
            graph.destroy()
            rebuildTopologies(connections, actualCell, manager)
        }
    }

    inline fun retopologize(cell: Cell, container: CellContainer, action: () -> Unit) {
        disconnectCell(cell, container, false)
        action()
        connectCell(cell, container)
    }

    fun retopologize(cell: Cell, container: CellContainer) {
        disconnectCell(cell, container, false)
        connectCell(cell, container)
    }

    /**
     * Checks whether the cells share the same graph.
     * @return True, if the specified cells share the same graph. Otherwise, false.
     * */
    private fun isCommonGraph(neighbors: List<CellAndContainerHandle>): Boolean {
        if (neighbors.size < 2) {
            return true
        }

        val graph = neighbors[0].neighbor.graph

        neighbors.drop(1).forEach { info ->
            if (info.neighbor.graph != graph) {
                return false
            }
        }

        return true
    }

    /**
     * Rebuilds the topology of a graph, presumably after a cell has been removed.
     * This will handle cases such as the graph splitting, because a cut vertex was removed.
     * This is a performance intensive operation, because it is likely to perform a search through the cells.
     * There is a case, though, that will complete in constant time: removing a cell that has zero or one neighbors.
     * Keep in mind that the simulation logic likely won't complete in constant time, in any case.
     * */
    private fun rebuildTopologies(
        neighborInfoList: List<CellAndContainerHandle>,
        removedCell: Cell,
        manager: CellGraphManager,
    ) {
        /*
        * For now, we use this simple algorithm.:
        *   We enqueue all neighbors for visitation. We perform searches through their graphs,
        *   excluding the cell we are removing.
        *
        *   If at any point we encounter an unprocessed neighbor, we remove that neighbor from the neighbor
        *   queue.
        *
        *   After a queue element has been processed, we build a new circuit with the cells we found.
        * */

        val neighbors = neighborInfoList.map { it.neighbor }.toHashSet()
        val neighborQueue = ArrayDeque<Cell>()
        neighborQueue.addAll(neighbors)

        val bfsVisited = HashSet<Cell>()
        val bfsQueue = ArrayDeque<Cell>()

        while (neighborQueue.isNotEmpty()) {
            val neighbor = neighborQueue.removeFirst()

            // Create new circuit for all cells connected to this one.
            val graph = manager.createGraph()

            // Start BFS at the neighbor.
            bfsQueue.add(neighbor)

            while (bfsQueue.isNotEmpty()) {
                val cell = bfsQueue.removeFirst()

                if (!bfsVisited.add(cell)) {
                    continue
                }

                neighborQueue.remove(cell)

                graph.addCell(cell)

                // Enqueue neighbors (excluding the cell we are removing) for processing
                cell.connections.forEach { connCell ->
                    // This must be handled above.
                    check(connCell != removedCell) {
                        DEBUGGER_BREAK("rebuildTopologies - connCell != removedCell")
                    }

                    bfsQueue.add(connCell)
                }
            }

            check(bfsQueue.isEmpty()) {
                DEBUGGER_BREAK("rebuildTopologies - bfsQueue#isEmpty")
            }

            // Refit cells
            graph.forEach { cell ->
                val isNeighbor = neighbors.contains(cell)

                cell.onUpdate(connectionsChanged = isNeighbor, graphChanged = true)
                cell.container?.onTopologyChanged()
            }

            // Finally, build the solver and start simulation.

            graph.buildSolver()
            graph.startSimulation()
            graph.setChanged()

            // We don't need to keep the cells, we have already traversed all the connected ones.
            bfsVisited.clear()
        }
    }
}

inline fun planarCellScan(level: Level, actualCell: Cell, searchDirection: Direction, consumer: ((CellAndContainerHandle) -> Unit)) {
    val actualPosWorld = actualCell.locator.requireLocator(Locators.BLOCK) { "Planar Scan requires a block position" }
    val actualFaceTarget = actualCell.locator.requireLocator(Locators.FACE) { "Planar Scan requires a face" }
    val remoteContainer = level.getBlockEntity(actualPosWorld + searchDirection) as? CellContainer ?: return

    remoteContainer
        .getCells()
        .filter { it.locator.has(Locators.BLOCK) && it.locator.has(Locators.FACE) }
        .forEach { targetCell ->
            val targetFaceTarget = targetCell.locator.requireLocator(Locators.FACE)

            if (targetFaceTarget == actualFaceTarget) {
                if (isConnectionAcceptedByGameObjectProximity(actualCell, targetCell)) {
                    consumer(CellAndContainerHandle.captureInScope(targetCell))
                }
            }
        }
}

inline fun pipelikeCellScan(level: Level, actualCell: Cell, consumer: ((CellAndContainerHandle) -> Unit)) {
    val actualPosWorld = actualCell.locator.requireLocator(Locators.BLOCK) {
        DEBUGGER_BREAK("Shaftlike Scan requires a block position")
    }

    val actualMaskWorld = actualCell.locator.requireLocator(Locators.PIPELIKE_MASK) {
        DEBUGGER_BREAK("Shaftlike Scan requires a mask")
    }

    actualMaskWorld.forEach { directionWorld ->
        val remoteContainer = level.getBlockEntity(actualPosWorld + directionWorld) as? CellContainer
            ?: return@forEach

        remoteContainer
            .getCells()
            .filter {
                it.locator.has(Locators.BLOCK) &&
                    it.locator.has(Locators.PIPELIKE_MASK)
            }
            .forEach { targetCell ->
                val targetMaskWorld = targetCell.locator.requireLocator(Locators.PIPELIKE_MASK)

                if(targetMaskWorld.has(directionWorld.opposite)) {
                    if (isConnectionAcceptedByGameObjectProximity(actualCell, targetCell)) {
                        consumer(CellAndContainerHandle.captureInScope(targetCell))
                    }
                }
            }
    }
}

/*
* There is a little bug that makes connections possible around the corner of a block, even if there's a block adjacent diagonally.
* I kind of like this, do we want to fix it?
* */

const val ALLOW_WRAPPED_DIAGONAL_WHATEVER = false

inline fun wrappedCellScan(
    level: Level,
    actualCell: Cell,
    searchDirection: Direction,
    consumer: ((CellAndContainerHandle) -> Unit),
) {
    val actualPosWorld = actualCell.locator.requireLocator(Locators.BLOCK) { "Wrapped Scan requires a block position" }
    val actualFaceWorld = actualCell.locator.requireLocator(Locators.FACE) { "Wrapped Scan requires a face" }
    val wrapDirection = actualFaceWorld.opposite

    @Suppress("KotlinConstantConditions")
    if(!ALLOW_WRAPPED_DIAGONAL_WHATEVER) {
        if(!level.getBlockState(actualPosWorld + searchDirection).isAir) {
            return
        }
    }

    val remoteContainer = level.getBlockEntity(actualPosWorld + searchDirection + wrapDirection) as? CellContainer
        ?: return

    remoteContainer
        .getCells()
        .filter { it.locator.has(Locators.BLOCK) && it.locator.has(Locators.FACE) }
        .forEach { targetCell ->
            val targetFaceTarget = targetCell.locator.requireLocator(Locators.FACE)

            if (targetFaceTarget == searchDirection) {
                if (isConnectionAcceptedByGameObjectProximity(actualCell, targetCell)) {
                    consumer(CellAndContainerHandle.captureInScope(targetCell))
                }
            }
        }
}

interface CellContainer {
    fun getCells(): List<Cell>
    fun neighborScan(actualCell: Cell): List<CellAndContainerHandle>
    fun onCellConnected(actualCell: Cell, remoteCell: Cell) {}
    fun onCellDisconnected(actualCell: Cell, remoteCell: Cell) {}
    fun onTopologyChanged() {}

    val manager: CellGraphManager
}

/**
 * Encapsulates information about a neighbor cell.
 * */
data class CellAndContainerHandle @Deprecated("Use [of]") constructor(val neighbor: Cell, val container: CellContainer) {
    companion object {
        @Suppress("DEPRECATION")
        fun captureInScope(cell: Cell) : CellAndContainerHandle {
            if(cell.locator.has(Locators.BLOCK)) {
                if(cell.hasGraph) {
                    val level = cell.graph.level
                    val blockPos = cell.locator.requireLocator(Locators.BLOCK)
                    val chunkPos = ChunkPos(blockPos)

                    if(!level.chunkSource.hasChunk(chunkPos.x, chunkPos.z)) {
                        LOG.warn("Forcing loading of chunk $chunkPos for $cell at $blockPos to capture in scope. Container: ${cell.container}")
                        val chunk = level.chunkSource.level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL)
                        LOG.warn("Result: $chunk, with container ${cell.container}")
                    }
                }
                else {
                    LOG.warn("Capturing $cell - without graph")
                }
            }

            return CellAndContainerHandle(
                cell,
                cell.container ?: error(DEBUGGER_BREAK("Did not have container for $cell"))
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CellAndContainerHandle

        if (neighbor !== other.neighbor) return false
        if (container !== other.container) return false

        return true
    }

    override fun hashCode(): Int {
        var result = neighbor.hashCode()
        result = 31 * result + container.hashCode()
        return result
    }
}

/**
 * Bijective map between [Cell] and the [Locator].
 * */
class CellList : Iterable<Cell> {
    private val cells = MutableMapPairBiMap<Cell, Locator>()

    val size get() = cells.size

    fun add(cell: Cell) = cells.add(cell, cell.locator)

    fun remove(cell: Cell) = check(cells.removeForward(cell)) { "Cell $cell ${cell.locator} was not present"}

    fun contains(cell: Cell) = cells.forward.contains(cell)

    fun contains(locator: Locator) = cells.backward.contains(locator)

    fun getByLocator(locator: Locator) = cells.backward[locator]

    fun addAll(source: CellList) = source.forEach { this.add(it) }

    override fun iterator(): Iterator<Cell> = cells.forward.keys.iterator()
}

/**
 * The cell graph represents a physical network of cells.
 * It may have multiple simulation subsets, formed between objects in the cells of this graph.
 * The cell graph manages the solver and simulation.
 * It also has serialization/deserialization logic for saving to the disk using NBT.
 * */
class CellGraph(val id: UUID, val manager: CellGraphManager, val level: ServerLevel) : Iterable<Cell> {
    private val cells = CellList()

    // P.S. the sub-solvers don't have any data dependency with each other or the objects.
    // The objects also don't use events fired by any simulation.
    // This means that sub-solvers can be dispatched in parallel for solve:
    //  1. The graph is picked up by a thread and the subscribers pre-step is executed.
    //  2. The sub-solvers are dispatched in parallel on a thread pool.
    //  3. The tasks finish and a thread executes the subscribers post-step.
    // This way, the parallelization factor can be increased dramatically for large networks.
    // This is because I imagine most builds consist of a power grid that distributes power to some bases or plants, and each plant has:
    //  - a DC-DC converter (transforming the grid to a useful potential). Currently, the DC-DC is implemented as an unconstrained process in the pre-step/post-step loop. So the plant and the grid would be separate sub-solvers.
    //  - some mechanical and thermal devices - those will separate all other simulations so that's more sub-solvers.
    private val electricalSubSolverSets = ArrayList<SubSolverSet<ElectricalSimulation>>()
    private val thermalSims = ArrayList<Simulator>()
    private val kineticSubSolverSets = ArrayList<SubSolverSet<KineticSimulation>>()

    private val simulationStopLock = ReentrantLock()

    // This is the simulation task. It will be null if the simulation is stopped
    private var simulationTask: ScheduledFuture<*>? = null

    val isSimulating get() = simulationTask != null

    @CrossThreadAccess
    private var updates = 0L

    private var updatesCheckpoint = 0L

    val simulationSubscribers = SubscriberPool()

    @CrossThreadAccess
    var lastTickTime = 0.0
        private set

    var isLoading = false
        private set

    fun captureAllInScope() {
        cells.forEach {
            CellAndContainerHandle.captureInScope(it)
        }
    }

    /**
     * Gets an iterator over the cells in this graph.
     * */
    override fun iterator(): Iterator<Cell> = cells.iterator()

    /**
     * Gets the number of cells in the graph.
     * */
    val size get() = cells.size

    fun isEmpty() = size == 0
    fun isNotEmpty() = !isEmpty()

    /**
     * Adds a cell to the internal sets, assigns its graph, and invalidates the saved data.
     * **This does not update the solver!
     * It is assumed that multiple operations of this type will be performed, then, the solver update will occur explicitly.**
     * The simulation must be stopped before calling this.
     * */
    fun addCell(cell: Cell) {
        validateMutationAccess()
        cells.add(cell)
        cell.graph = this
        manager.setDirty()
    }

    /**
     * Removes a cell from the internal sets, and invalidates the saved data.
     * **This does not update the solver!
     * It is assumed that multiple operations of this type will be performed, then,
     * the solver update will occur explicitly.**
     * The simulation must be stopped before calling this.
     * */
    fun removeCell(cell: Cell) {
        validateMutationAccess()
        cells.remove(cell)
        manager.setDirty()
    }

    /**
     * Copies the cells of this graph to the other graph, and invalidates the saved data.
     * The simulation must be stopped before calling this.
     * */
    fun copyTo(graph: CellGraph) {
        this.validateMutationAccess()
        graph.validateMutationAccess()
        graph.cells.addAll(this.cells)
        graph.manager.setDirty()
    }

    /**
     * Gets the cell with the specified [locator].
     * @return The cell, if found, or throws an exception, if the cell does not exist.
     * */
    fun getCellByLocator(locator: Locator): Cell {
        val result = cells.getByLocator(locator)

        if (result == null) {
            LOG.error(DEBUGGER_BREAK("Could not get cell at $locator")) // exception may be swallowed
            error("Could not get cell at $locator")
        }

        return result
    }

    fun setChanged() {
        if(!isLoading) {
            manager.setDirty()
        }
    }

    /**
     * Gets the number of updates that have occurred since the last call to this method.
     * */
    fun sampleElapsedUpdates(): Long {
        val elapsed = updates - updatesCheckpoint
        updatesCheckpoint += elapsed
        return elapsed
    }

    /**
     * Checks if the simulation is running. Presumably, this is used by logic that wants to mutate the graph.
     * It also checks if the caller is the server thread.
     * */
    private fun validateMutationAccess() {
        if (simulationTask != null) {
            error("Tried to mutate the simulation while it was running")
        }

        if (Thread.currentThread() != ServerLifecycleHooks.getCurrentServer().runningThread) {
            error("Illegal cross-thread access into the cell graph")
        }
    }

    private enum class UpdateStep {
        Start,
        UpdateSubsPre,
        UpdateElectricalSims,
        UpdateThermalSims,
        UpdateKineticSims,
        UpdateSubsPost
    }

    /**
     * Runs one simulation step. This is called from the update thread.
     * **The update is aborted if the server thread is not running. This happens if it got suspended externally or if the integrated server is paused!**
     * */
    @CrossThreadAccess
    private fun update() {
        if(isServerPaused()) {
            return
        }

        simulationStopLock.lock()

        if(!isSimulating) {
            LOG.warn("Aborting tick!")
            simulationStopLock.unlock()
            return
        }

        var stage = UpdateStep.Start

        try {
            stage = UpdateStep.UpdateSubsPre
            simulationSubscribers.update(DT, SubscriberPhase.Pre)

            lastTickTime = !measureDuration {
                stage = UpdateStep.UpdateElectricalSims
                val electricalTime = measureDuration {
                    electricalSubSolverSets.forEach {
                        it.solvers.forEach { circuit ->
                            circuit.step()
                        }

                    }
                }

                stage = UpdateStep.UpdateThermalSims
                val thermalTime = measureDuration {
                    thermalSims.forEach {
                        it.step(DT)
                    }
                }

                stage = UpdateStep.UpdateKineticSims
                val kineticTime = measureDuration {
                    kineticSubSolverSets.forEach {
                        it.solvers.forEach { solver ->
                            solver.step()
                        }
                    }
                }
            }

            stage = UpdateStep.UpdateSubsPost
            simulationSubscribers.update(DT, SubscriberPhase.Post)

            updates++

        } catch (t: Throwable) {
            LOG.error(DEBUGGER_BREAK("FAILED TO UPDATE SIMULATION at $stage: $t ${t.stackTraceToString()}"))
        } finally {
            // Maybe blow up the game instead of just allowing this to go on?
            simulationStopLock.unlock()
        }
    }

    private fun clearElectricalSimulation() {
        electricalSubSolverSets.forEach {
            it.solvers.forEach { solver ->
                solver.destroy()
            }
        }

        electricalSubSolverSets.clear()
    }

    private fun clearRotationSimulation() {
        // Reset angles and other state (so the fresh node's phase coincides with the rest of the network):
        kineticSubSolverSets.forEach {
            it.solvers.forEach { solver ->
                solver.nodes.forEach { node ->
                    node.resetForRestart()
                }
            }
        }

        // Destroy old references:
        kineticSubSolverSets.forEach {
            it.solvers.forEach { solver ->
                solver.destroy()
            }
        }

        kineticSubSolverSets.clear()
    }

    /**
     * This realizes the object subsets and creates the underlying simulations.
     * The simulation must be suspended before calling this method.
     * @see stopSimulation
     * */
    fun buildSolver() {
        validateMutationAccess()

        clearElectricalSimulation()
        clearRotationSimulation()

        cells.forEach { it.clearObjectConnections() }
        cells.forEach { it.onBuildStarted() }
        cells.forEach { it.recordObjectConnections() }

        /**
         * Realization: Finds all connected graphs of objects of the same type (objects that are connected to each other).
         * An object might contain nodes that will not be connected to each other (e.g. a potential probe).
         * This means that those graphs will further be processed into sub-solvers. Sub-solvers are the actual final simulations.
         * They are created with the underlying components that are joined/connected with each other.
         * This is done by retaining the connection data before creating the underlying sub-solvers, and then determining the connected nodes with those connections.
         * This is done by domain-specific wrappers for [org.ageseries.libage.sim.SubSolverSystemBuilder].
         * */

        val electrical = realizeElectrical()
        realizeThermal()
        val kinetic = realizeKinetic()

        /**
         * Executes build on all objects.
         * This creates the connections in retained mode (except for thermals, until we add sub-solvers for them too).
         * It doesn't involve any underlying simulation yet. It's just recording the connection data.
         * */
        cells.forEach { cell ->
            cell.objects.forEachObject { obj ->
                when(obj.type) {
                    Electrical -> {
                        (obj as ElectricalObject<*>).build(electrical.builderByObject[obj]!!)
                    }
                    Thermal -> {
                        (obj as ThermalObject<*>).build()
                    }
                    Kinetic -> {
                        (obj as KineticObject<*>).build(kinetic.builderByObject[obj]!!)
                    }
                }
            }
        }

        /**
         * Finally, the underlying simulations are created.
         * Each one's nodes/components/bodies **are connected** to at least one other body in that simulation.
         * (Except thermal, for now)
         * */

        electrical.objectsByBuilder.keys.forEach { builder ->
            val subSolvers = builder.build(DT, true, ElectricalSimulation.ConstructionOptions(
                1e12
            ))

            electrical.objectsByBuilder[builder].forEach { obj ->
                obj.setSubSolvers(subSolvers)
            }

            electricalSubSolverSets.add(subSolvers)
        }

        kinetic.objectsByBuilder.keys.forEach { builder ->
            val subSolvers = builder.build(DT, true)

            kinetic.objectsByBuilder[builder].forEach { obj ->
                obj.setSubSolvers(subSolvers)
            }

            kineticSubSolverSets.add(subSolvers)
        }

        cells.forEach { it.onBuildFinished() }
    }

    /**
     * Result of realizing the electrical object graphs based (**only**) on object connections.
     * Each builder here will create one or more sub-solvers.
     * @param builderByObject The builder assigned to each object.
     * @param objectsByBuilder The builders, mapped to all the electrical objects that have connections between each other.
     * */
    private class ElectricalRealizationData(
        val builderByObject: Map<ElectricalObject<*>, ElectricalCircuitForestBuilder>,
        val objectsByBuilder: MultiMap<ElectricalCircuitForestBuilder, ElectricalObject<*>>
    )

    /**
     * Realizes the sub-system builders for all electrical objects.
     * */
    private fun realizeElectrical() : ElectricalRealizationData {
        val builderByObject = HashMap<ElectricalObject<*>, ElectricalCircuitForestBuilder>()
        val objectsByBuilder = MutableSetMapMultiMap<ElectricalCircuitForestBuilder, ElectricalObject<*>>()

        realizeComponents(Electrical, factory = { set ->
            val builder = ElectricalCircuitForestBuilder()

            set.forEach {
                val obj = it.objects.electricalObject
                obj.addComponents(builder)
                builderByObject.putUnique(obj, builder)
                objectsByBuilder[builder].addUnique(obj)
            }
        })

        return ElectricalRealizationData(builderByObject, objectsByBuilder)
    }

    private fun realizeThermal() {
        thermalSims.clear()

        realizeComponents(Thermal, factory = { set ->
            val simulation = Simulator()
            set.forEach { it.objects.thermalObject.setNewSimulation(simulation) }
            thermalSims.add(simulation)
        })
    }

    /**
     * Result of realizing the kinetic object graphs based (**only**) on object connections.
     * Each builder here will create one or more sub-solvers.
     * @param builderByObject The builder assigned to each object.
     * @param objectsByBuilder The builders, mapped to all the kinetic objects that have connections between each other.
     * */
    private class KineticRealizationData(
        val builderByObject: Map<KineticObject<*>, KineticSimulationForestBuilder>,
        val objectsByBuilder: MultiMap<KineticSimulationForestBuilder, KineticObject<*>>
    )

    /**
     * Realizes the sub-system builders for all kinetic objects.
     * */
    private fun realizeKinetic() : KineticRealizationData {
        val builderByObject = HashMap<KineticObject<*>, KineticSimulationForestBuilder>()
        val objectsByBuilder = MutableSetMapMultiMap<KineticSimulationForestBuilder, KineticObject<*>>()

        realizeComponents(Kinetic, factory = { set ->
            val builder = KineticSimulationForestBuilder()

            set.forEach {
                val obj = it.objects.kineticObject
                obj.addNodes(builder)
                builderByObject.putUnique(obj, builder)
                objectsByBuilder[builder].addUnique(obj)
            }
        })

        return KineticRealizationData(builderByObject, objectsByBuilder)
    }

    /**
     * Realizes a subset of simulation objects that share the same simulation type.
     * This is a group of objects that:
     *  1. Are in cells that are physically connected
     *  2. Participate in the same simulation type (Electrical, Thermal, Mechanical)
     *
     * A separate solver/simulator may be created using this subset.
     *
     * This algorithm first creates a set with all cells that have the specified simulation type.
     * Then, it does a search through the cells, only taking into account connected nodes that have that simulation type.
     * When a cell is discovered, it is removed from the pending set.
     * At the end of the search, a connected component is realized.
     * The search is repeated until the pending set is exhausted.
     *
     * @param type The simulation type to search for.
     * @param factory A factory method to generate the subset from the discovered cells.
     * */
    private fun <TComponent> realizeComponents(type: SimulationObjectType, factory: ((Set<Cell>) -> TComponent), predicate: ((SimulationObject<*>, SimulationObject<*>) -> Boolean)? = null, ) {
        val pending = cells.asSequence().filter { it.hasObject(type) }.toHashSet()

        val queue = ArrayDeque<Cell>()
        val visited = HashSet<Cell>(pending.size)

        val results = ArrayList<TComponent>()

        while (pending.isNotEmpty()) {
            check(queue.isEmpty())

            visited.clear()

            queue.add(pending.first())

            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()

                if (!visited.add(cell)) {
                    continue
                }

                pending.remove(cell)

                for (connectedCell in cell.connections) {
                    if (connectedCell.hasObject(type)) {
                        if (predicate != null && !predicate(cell.objects[type], connectedCell.objects[type])) {
                            continue
                        }

                        queue.add(connectedCell)
                    }
                }
            }

            results.add(factory(visited))
        }
    }

    /**
     * Removes the graph from tracking and invalidates the saved data.
     * The simulation must be stopped before calling this.
     * */
    fun destroy() {
        validateMutationAccess()
        clearElectricalSimulation()
        clearRotationSimulation()
        manager.removeGraph(this)
        manager.setDirty()
    }

    fun ensureStopped() {
        if (isSimulating) {
            stopSimulation()
        }
    }

    /**
     * Stops the simulation. This is a sync point, so usage of this should be sparse.
     * Will result in an error if it was not running.
     * */
    fun stopSimulation() {
        if (simulationTask == null) {
            return
        }

        simulationStopLock.lock()
        simulationTask!!.cancel(true)
        simulationTask = null
        simulationStopLock.unlock()

        LOG.info("Stopped simulation for $this")
    }

    /**
     * Starts the simulation. Will result in an error if it is already running.,
     * */
    fun startSimulation() {
        if (simulationTask != null) {
            error("Tried to start simulation, but it was already running")
        }

        simulationTask = pool.scheduleAtFixedRate(this::update, 0, 10, TimeUnit.MILLISECONDS)

        LOG.info("Started simulation for $this")
    }

    /**
     * Runs the specified [action], ensuring that the simulation is paused.
     * The previous running state is preserved; if the simulation was paused, it will not be started after the [action] is completed.
     * If it was running, then the simulation will resume.
     * */
    @OptIn(ExperimentalContracts::class)
    fun runSuspended(action: (() -> Unit)) {
        contract {
            callsInPlace(action)
        }

        val running = isSimulating

        if (running) {
            stopSimulation()
        }

        action()

        if (running) {
            startSimulation()
        }
    }

    // TODO revamp the schema

    fun toNbt(): CompoundTag {
        val circuitCompound = CompoundTag()

        require(!isSimulating)

        circuitCompound.putUUID(NBT_ID, id)

        val cellListTag = ListTag()

        cells.forEach { cell ->
            val cellTag = CompoundTag()
            val connectionsTag = ListTag()

            cell.connections.forEach { conn ->
                val connectionCompound = CompoundTag()
                connectionCompound.putLocator(NBT_POSITION, conn.locator)
                connectionsTag.add(connectionCompound)
            }

            cellTag.putLocator(NBT_POSITION, cell.locator)
            cellTag.putString(NBT_ID, cell.id.toString())
            cellTag.put(NBT_CONNECTIONS, connectionsTag)

            try {
                cellTag.put(NBT_CELL_DATA, cell.createTag())
            } catch (t: Throwable) {
                LOG.fatal("CELL SAVE ERROR: $t")
            }

            cellListTag.add(cellTag)
        }

        circuitCompound.put(NBT_CELLS, cellListTag)

        return circuitCompound
    }

    fun serverStop() {
        if (simulationTask != null) {
            stopSimulation()
        }
    }

    companion object {
        const val DT = 1.0 / 100.0

        private const val NBT_CELL_DATA = "data"
        private const val NBT_ID = "id"
        private const val NBT_CELLS = "cells"
        private const val NBT_POSITION = "pos"
        private const val NBT_CONNECTIONS = "connections"

        private val threadNumber = AtomicInteger()

        private fun createThread(r: Runnable): Thread {
            val thread = Thread(r, "cell-graph-${threadNumber.getAndIncrement()}")

            if (thread.isDaemon) {
                thread.isDaemon = false
            }

            if (thread.priority != Thread.NORM_PRIORITY) {
                thread.priority = Thread.NORM_PRIORITY
            }

            return thread
        }

        private val pool = Executors.newScheduledThreadPool(
            run {
                val threadCount = Eln2Config.serverConfig.simulationThreadCount.get()

                // We do get an exception from thread pool creation, but explicit handling is better here.
                if (threadCount <= 0) {
                    error("Simulation threads is $threadCount")
                }

                LOG.info("Using $threadCount simulation threads")

                threadCount
            },
            ::createThread
        )

        fun makePool() {
            requireIsOnServerThread()
            pool
        }

        fun fromNbt(graphCompound: CompoundTag, manager: CellGraphManager, level: ServerLevel): CellGraph {
            val graphId = graphCompound.getUUID(NBT_ID)
            val result = CellGraph(graphId, manager, level)

            result.isLoading = true

            val cellListTag = graphCompound.get(NBT_CELLS) as ListTag?
                ?: // No cells are available
                return result

            // Used to assign the connections after all cells have been loaded:
            val cellConnections = HashMap<Cell, ArrayList<Locator>>()

            // Used to load cell custom data:
            val cellData = HashMap<Cell, CompoundTag>()

            cellListTag.forEach { cellNbt ->
                val cellCompound = cellNbt as CompoundTag
                val pos = cellCompound.getLocator(NBT_POSITION)
                val cellId = ResourceLocation.tryParse(cellCompound.getString(NBT_ID))!!

                val connectionPositions = ArrayList<Locator>()
                val connectionsTag = cellCompound.get(NBT_CONNECTIONS) as ListTag

                connectionsTag.forEach {
                    val connectionCompound = it as CompoundTag
                    val connectionPos = connectionCompound.getLocator(NBT_POSITION)
                    connectionPositions.add(connectionPos)
                }

                val cell = CellRegistry
                    .getCellProvider(cellId)
                    .create(pos, CellEnvironment.evaluate(level, pos))

                cellConnections[cell] = connectionPositions

                result.addCell(cell)

                cellData[cell] = cellCompound.getCompound(NBT_CELL_DATA)
            }

            // Now assign all connections and the graph to the cells:
            cellConnections.forEach { (cell, connectionPositions) ->
                val connections = ArrayList<Cell>(connectionPositions.size)

                connectionPositions.forEach { connections.add(result.getCellByLocator(it)) }

                // Now set graph and connection
                cell.graph = result
                cell.connections = connections
                cell.onUpdate(connectionsChanged = true, graphChanged = true)

                try {
                    cell.loadTag(cellData[cell]!!)
                } catch (t: Throwable) {
                    LOG.error("Cell loading exception: $t")
                }
            }

            result.cells.forEach { it.onLoadedFromDisk() }
            result.cells.forEach {
                it.onCreated()
            }

            result.isLoading = false

            return result
        }
    }
}

fun runSuspended(graphs: List<CellGraph>, action: () -> Unit) {
    if (graphs.isEmpty()) {
        action()

        return
    }

    graphs.first().runSuspended {
        runSuspended(graphs.drop(1), action)
    }
}

fun runSuspended(vararg graphs: CellGraph, action: () -> Unit) {
    runSuspended(graphs.asList(), action)
}

fun runSuspended(vararg cells: Cell, action: () -> Unit) {
    runSuspended(cells.asList().map { it.graph }, action)
}

/**
 * The Cell Graph Manager tracks the cell graphs for a single dimension.
 * This is a **server-only** construct. Simulations never have to occur on the client.
 * */
class CellGraphManager(val level: ServerLevel) : SavedData() {
    private val graphs = HashMap<UUID, CellGraph>()

    private val statisticsWatch = Stopwatch()

    fun sampleTickRate(): Double {
        val elapsedSeconds = !statisticsWatch.sample()

        return graphs.values.sumOf { it.sampleElapsedUpdates() } / elapsedSeconds
    }

    val totalSpentTime get() = graphs.values.sumOf { it.lastTickTime }

    /**
     * Checks whether this manager is tracking the specified graph.
     * @return True, if the graph is being tracked by this manager. Otherwise, false.
     * */
    fun contains(id: UUID): Boolean {
        return graphs.containsKey(id)
    }

    /**
     * Begins tracking a graph, and invalidates the saved data.
     * */
    fun addGraph(graph: CellGraph) {
        graphs[graph.id] = graph
        setDirty()
    }

    /**
     * Creates a fresh graph, starts tracking it, and invalidates the saved data.
     * */
    fun createGraph(): CellGraph {
        val graph = CellGraph(UUID.randomUUID(), this, level)
        addGraph(graph)
        setDirty()
        return graph
    }

    /**
     * Removes a graph, and invalidates the saved data.
     * **This does not call any _destroy_ methods on the graph!**
     * */
    fun removeGraph(graph: CellGraph) {
        graphs.remove(graph.id)
        LOG.info("Removed graph ${graph.id}!")
        setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val graphListTag = ListTag()

        graphs.values.forEach { graph ->
            graph.runSuspended {
                graphListTag.add(graph.toNbt())
            }
        }

        tag.put("Graphs", graphListTag)
        LOG.info("Saved ${graphs.size} graphs to disk.")
        return tag
    }

    /**
     * Gets the graph with the specified ID, or throws an exception.
     * */
    fun getGraph(id: UUID) = graphs[id]
        ?: error(DEBUGGER_BREAK("Graph with id $id not found"))

    fun serverStop() {
        graphs.values.forEach { it.serverStop() }
    }

    companion object {
        private fun load(tag: CompoundTag, level: ServerLevel): CellGraphManager {
            val manager = CellGraphManager(level)

            val graphListTag = tag.get("Graphs") as ListTag?

            if (graphListTag == null) {
                LOG.info("No nodes to be loaded!")
                return manager
            }

            graphListTag.forEach { circuitNbt ->
                val graphCompound = circuitNbt as CompoundTag
                val graph = CellGraph.fromNbt(graphCompound, manager, level)

                if (graph.isEmpty()) {
                    LOG.error("Loaded circuit with no cells!")
                    return@forEach
                }

                manager.addGraph(graph)

                LOG.info("Loaded ${graph.size} cells for ${graph.id}!")
            }

            manager.graphs.values.forEach {
                it.forEach { cell ->
                    cell.onWorldLoadedPreSolver()
                }
            }

            manager.graphs.values.forEach { it.buildSolver() }

            manager.graphs.values.forEach {
                it.forEach { cell ->
                    cell.onWorldLoadedPostSolver()
                }
            }

            manager.graphs.values.forEach {
                it.forEach { cell ->
                    cell.onWorldLoadedPreSim()
                }
            }

            manager.graphs.values.forEach {
                it.startSimulation()
            }

            manager.graphs.values.forEach {
                it.forEach { cell ->
                    cell.onWorldLoadedPostSim()
                }
            }

            return manager
        }

        /**
         * Gets or creates a graph manager for the specified level.
         * */
        fun getFor(level: ServerLevel): CellGraphManager = level.dataStorage.computeIfAbsent(
            { load(it, level) },
            { CellGraphManager(level) },
            "CellManager"
        )
    }
}

/**
 * The Cell Provider is a factory of cells, and also has connection rules for cells.
 * */
abstract class CellProvider<out T : Cell> {
    /**
     * Gets the resource ID of this cell.
     * */
    val id get() = CellRegistry.getCellId(this)

    /**
     * Creates a new instance of the cell.
     * */
    abstract fun create(ci: CellCreateInfo): T

    fun create(locator: Locator, environment: CellEnvironment) : T {
        val cell = create(CellCreateInfo(locator, id, environment))
        cell.afterConstruct()
        return cell
    }
}

fun interface CellFactory<T : Cell> {
    operator fun invoke(ci: CellCreateInfo) : T
}

class BasicCellProvider<T : Cell>(val factory: CellFactory<T>) : CellProvider<T>() {
    override fun create(ci: CellCreateInfo) = factory(ci)

    companion object {
        fun<T : Cell> setup(block: () -> (CellFactory<T>)) = BasicCellProvider(block())
    }
}

/**
 * Connection filtering based on "connection sizes".
 * If none of the two cells is [Interface], the connection isn't rejected (no filtering).
 * Otherwise, if any of the two cells can't evaluate a direction in the local frame towards the remote cell, the connection is rejected.
 * Otherwise, if one of the cells isn't [Interface], the connection is rejected.
 * Finally, both cells are [Interface]. [accessor] is called to get the [SizeEnum] on the connection sides of both cells. The connection is rejected if the sizes aren't compatible.
 *
 * This works for all general part and block devices.
 * It allows multiple wire types (signal, electrical) and multiple sizes of said type.
 * */
private inline fun<reified Interface, SizeEnum : Indexed> connectionSizeRejection(sourceCell: Cell, targetCell: Cell, map: SizeCompatibilityMap<SizeEnum>, crossinline accessor: (Interface, Base6Direction3d, Cell) -> SizeEnum?) : Boolean {
    val sourceIsInterface = sourceCell is Interface
    val targetIsInterface = targetCell is Interface

    if(!sourceIsInterface && !targetIsInterface) {
        return false // No filtering to be done
    }

    val directionInSourceFrame = sourceCell.locator.findDirActualPartOrNull(targetCell.locator)
    val directionInTargetFrame = targetCell.locator.findDirActualPartOrNull(sourceCell.locator)

    // We reject implicitly if we can't get the local directions for both cells.
    if(directionInSourceFrame == null || directionInTargetFrame == null) {
        return true
    }

    if(sourceIsInterface && !targetIsInterface) {
        return true
    }

    @Suppress("KotlinConstantConditions") // Suggestion is wrong. Jetbrains pls fix
    if(!sourceIsInterface && targetIsInterface) {
        return true
    }

    sourceCell as Interface
    targetCell as Interface

    // Now we just check if:
    // a. They both have sizes defined (not null)
    // b. The sizes are compatible.
    val sourceSize = accessor(sourceCell, directionInSourceFrame, targetCell)
    val remoteSize = accessor(targetCell, directionInTargetFrame, sourceCell)

    return (sourceSize == null || remoteSize == null) || !map.areCompatible(sourceSize, remoteSize)
}

private interface Indexed {
    val index: Int
}

fun interface SizeCompatibilityMap<SizeEnum> {
    fun areCompatible(a: SizeEnum, b: SizeEnum) : Boolean
}

private class SizeCompatibilityMatrixBuilder<SizeEnum>(last: SizeEnum) where SizeEnum : Indexed {
    private val stride = last.index + 1
    private val matrix = BooleanArray(stride * stride)

    fun compatible(a: SizeEnum, b: SizeEnum) : SizeCompatibilityMatrixBuilder<SizeEnum> {
        matrix[a.index * stride + b.index] = true
        matrix[b.index * stride + a.index] = true
        return this
    }

    fun selfCompatible(iterable: Iterable<SizeEnum>) : SizeCompatibilityMatrixBuilder<SizeEnum> {
        iterable.forEach {
            compatible(it, it)
        }

        return this
    }

    fun build() : SizeCompatibilityMap<SizeEnum>  {
        val map = matrix.clone()

        return SizeCompatibilityMap { a, b ->
            map[a.index * stride + b.index]
        }
    }
}

enum class ThermalSize(val sizeTranslationKey: String, override val index: Int) : Indexed {
    Standard("standard_thermal_size", 0),
    Any("any_thermal_size", 1);

    companion object {
        val compatibility = SizeCompatibilityMatrixBuilder<ThermalSize>(Any)
            .selfCompatible(entries)
            .compatible(Standard, Any)
            .build()
    }
}

enum class ElectricalSize(val sizeTranslationKey: String, override val index: Int) : Indexed {
    Standard("standard_electrical_size", 0),
    Signal("signal_size", 1),
    Any("any_electrical_size", 2); // Except for signal!

    companion object {
        val compatibility = SizeCompatibilityMatrixBuilder<ElectricalSize>(Any)
            .selfCompatible(entries)
            .compatible(Standard, Any)
            .build()
    }
}

enum class KineticSize(val sizeTranslationKey: String, override val index: Int) : Indexed {
    Standard("standard_kinetic_size", 0),
    Any("any_kinetic_size", 1);

    companion object {
        val compatibility = SizeCompatibilityMatrixBuilder<KineticSize>(Any)
            .selfCompatible(entries)
            .compatible(Standard, Any)
            .build()
    }
}

/**
 * Supplies the [ElectricalSize] for a side of the cell, in the local frame.
 * If the returned size is null, the connection is rejected immediately. If the returned size is not equal to the other cell's size on its respective side, the connection is also rejected.
 * The connection is accepted if both cells report the same size on their respective sides.
 * */
interface SidedElectrical<C> where C : Cell, C : SidedElectrical<C> {
    /**
     * Gets the size of the electrical wire on that side.
     * @param side The side, pre-calculated, in the cell's local frame.
     * @param targetCell The remote cell, useful if a locator map is used instead of raw directions in the connection code.
     * */
    fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell) : ElectricalSize?
}

/**
 * Cell with a constant electrical wire size on all 4 horizontal sides.
 * To be used only for devices such as wires, anchors, connection hubs and such.
 * */
interface SidedElectricalFLBR<C> : SidedElectrical<C> where C : Cell, C : SidedElectricalFLBR<C> {
    /**
     * The electrical wire size. It will be supplied to all 4 sides.
     * */
    val electricalSize: ElectricalSize?

    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell) = when(side) {
        Base6Direction3d.Front -> electricalSize
        Base6Direction3d.Back -> electricalSize
        Base6Direction3d.Left -> electricalSize
        Base6Direction3d.Right -> electricalSize
        Base6Direction3d.Up -> null
        Base6Direction3d.Down -> null
    }
}

/**
 * Cell with a constant electrical wire size on 2 specific sides.
 * */
interface SidedElectricalBipole<C> : SidedElectrical<C> where C : Cell, C : SidedElectricalBipole<C> {
    val side1: Base6Direction3d
    val side2: Base6Direction3d
    val electricalSize: ElectricalSize

    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell) = when(side) {
        side1 -> electricalSize
        side2 -> electricalSize
        else -> null
    }
}

/**
 * Electrical size provider, based on a pole map.
 * */
interface SidedElectricalMapped<C> : SidedElectrical<C> where C : Cell, C : SidedElectricalMapped<C> {
    val electricalMap : PoleMap

    /**
     * The electrical size. It will be supplied to all sides the [electricalMap] covers.
     * */
    val electricalSize: ElectricalSize?

    /**
     * Returns the [electricalSize] if the [electricalMap] covers this connection.
     * */
    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell): ElectricalSize? {
        return if(electricalMap.evaluateOrNull(this as Cell, targetCell) != null) electricalSize else null
    }
}

/**
 * Electrical size provider, based on a monopolar map.
 * */
interface SidedElectricalMonoMapped<C> : SidedElectrical<C> where C : Cell, C : SidedElectricalMonoMapped<C> {
    val electricalMap: MonopoleMap

    /**
     * The electrical size. It will be supplied to all sides the [electricalMap] covers.
     * */
    val electricalSize: ElectricalSize?

    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell): ElectricalSize? {
        if(electricalMap.evaluates(this as Cell, targetCell)) {
            return electricalSize
        }

        return null
    }
}

/**
 * Supplies the [ThermalSize] for a side of the cell, in the local frame.
 * If the returned size is null, the connection is rejected immediately. If the returned size is not equal to the other cell's size on its respective side, the connection is also rejected.
 * The connection is accepted if both cells report the same size on their respective sides.
 * */
interface SidedThermal<C> where C : Cell, C : SidedThermal<C> {
    /**
     * Gets the size of the thermal wire on that side.
     * @param side The side, pre-calculated, in the cell's local frame.
     * @param targetCell The remote cell, useful if a locator map is used instead of raw directions in the connection code.
     * */
    fun getThermalSizeOnSide(side: Base6Direction3d, targetCell: Cell) : ThermalSize?
}

/**
 * Cell with a constant thermal wire size on all 4 horizontal sides.
 * To be used only for devices such as wires, anchors, connection hubs and such.
 * */
interface SidedThermalFLBR<C> : SidedThermal<C> where C : Cell, C : SidedThermalFLBR<C> {
    /**
     * The electrical wire size. It will be supplied to all 4 sides.
     * */
    val thermalSize: ThermalSize?

    override fun getThermalSizeOnSide(side: Base6Direction3d, targetCell: Cell) = when(side) {
        Base6Direction3d.Front -> thermalSize
        Base6Direction3d.Back -> thermalSize
        Base6Direction3d.Left -> thermalSize
        Base6Direction3d.Right -> thermalSize
        Base6Direction3d.Up -> null
        Base6Direction3d.Down -> null
    }
}

/**
 * Thermal wire size provider, based on a pole map.
 * */
interface SidedThermalMapped<C> : SidedThermal<C> where C : Cell, C : SidedThermalMapped<C> {
    val thermalMap : PoleMap

    /**
     * The thermal size. It will be supplied to all sides the [thermalMap] covers.
     * */
    val thermalSize: ThermalSize?

    /**
     * Returns the [thermalSize] if the [thermalMap] covers this connection.
     * */
    override fun getThermalSizeOnSide(side: Base6Direction3d, targetCell: Cell): ThermalSize? {
        return if(thermalMap.evaluateOrNull(this as Cell, targetCell) != null) thermalSize else null
    }
}

/**
 * Supplies the [KineticSize] for a side of the cell, in the local frame.
 * If the returned size is null, the connection is rejected immediately. If the returned size is not equal to the other cell's size on its respective side, the connection is also rejected.
 * The connection is accepted if both cells report the same size on their respective sides.
 * */
interface SidedKinetic<C> where C : Cell, C : SidedKinetic<C> {
    /**
     * Gets the size of the kinetic shaft on that side.
     * @param side The side, pre-calculated, in the cell's local frame.
     * @param targetCell The remote cell, useful if a locator map is used instead of raw directions in the connection code.
     * */
    fun getKineticSizeOnSide(side: Base6Direction3d, targetCell: Cell) : KineticSize?
}

/**
 * Cell with a constant kinetic shaft size on 2 specific sides.
 * */
interface SidedKineticBipole<C> : SidedKinetic<C> where C : Cell, C : SidedKineticBipole<C> {
    val side1: Base6Direction3d
    val side2: Base6Direction3d
    val kineticSize: KineticSize

    override fun getKineticSizeOnSide(side: Base6Direction3d, targetCell: Cell) = when(side) {
        side1 -> kineticSize
        side2 -> kineticSize
        else -> null
    }
}

/**
 * Kinetic size provider, based on a pole map.
 * */
interface SidedKineticMapped<C> : SidedKinetic<C> where C : Cell, C : SidedKineticMapped<C> {
    val kineticMap : PoleMap

    /**
     * The kinetic size. It will be supplied to all sides the [kineticMap] covers.
     * */
    val kineticSize: KineticSize?

    /**
     * Returns the [kineticSize] if the [kineticMap] covers this connection.
     * */
    override fun getKineticSizeOnSide(side: Base6Direction3d, targetCell: Cell): KineticSize? {
        return if(kineticMap.evaluateOrNull(this as Cell, targetCell) != null) kineticSize else null
    }
}

/**
 * Kinetic size provider, based on a monopolar map.
 * */
interface SidedKineticMonoMapped<C> : SidedKinetic<C> where C : Cell, C : SidedKineticMonoMapped<C> {
    val kineticMap : MonopoleMap

    /**
     * The kinetic size. It will be supplied to all sides the [kineticMap] covers.
     * */
    val kineticSize: KineticSize?

    /**
     * Returns the [kineticSize] if the [kineticMap] covers this connection.
     * */
    override fun getKineticSizeOnSide(side: Base6Direction3d, targetCell: Cell): KineticSize? {
        return if(kineticMap.evaluates(this as Cell, targetCell)) kineticSize else null
    }
}

