@file:Suppress("MemberVisibilityCanBePrivate", "ClassName")

package org.eln2.mc.common.cells.foundation

import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraftforge.server.ServerLifecycleHooks
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkStatus
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Simulator
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.electrical.mna.Circuit
import org.ageseries.libage.sim.electrical.mna.CircuitBuilder
import org.ageseries.libage.sim.electrical.mna.NEGATIVE
import org.ageseries.libage.sim.electrical.mna.POSITIVE
import org.ageseries.libage.sim.electrical.mna.component.VoltageSource
import org.ageseries.libage.utils.Stopwatch
import org.ageseries.libage.utils.measureDuration
import org.ageseries.libage.utils.putUnique
import org.ageseries.libage.utils.sourceName
import org.eln2.mc.*
import org.eln2.mc.common.cells.CellRegistry
import org.eln2.mc.common.cells.foundation.SimulationObjectType.*
import org.eln2.mc.data.*
import org.eln2.mc.extensions.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
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
    private val subscribers = HashMap<Subscriber, SubscriberOptions>()

    override fun addSubscriber(parameters: SubscriberOptions, subscriber: Subscriber) {
        require(subscribers.put(subscriber, parameters) == null) { "Duplicate subscriber $subscriber" }
        underlyingCollection.addSubscriber(parameters, subscriber)
    }

    override fun remove(subscriber: Subscriber) {
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

        private val ID_ATOMIC = AtomicInteger()
    }

    constructor(ci: CellCreateInfo) : this(ci.locator, ci.id, ci.environment)

    val uniqueCellId = ID_ATOMIC.getAndIncrement()

    // Persistent behaviors are used by cell logic, and live throughout the lifetime of the cell:
    private var persistentPoolInternal: TrackedSubscriberCollection? = null

    // Transient behaviors are used when the cell is in range of a player (and the game object exists):
    private var transientPoolInternal: TrackedSubscriberCollection? = null

    val persistentPool get() = persistentPoolInternal ?: error("Invalid access to persistent pool")

    lateinit var graph: CellGraph
    var connections: ArrayList<Cell> = ArrayList(0)

    val ruleSet by lazy {
        LocatorRelationRuleSet()
    }

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

    /**
     * Checks if this cell accepts a connection from the remote cell.
     * **SPECIAL CARE MUST BE TAKEN to ensure that the results are consistent with the actual [connections]**
     * @return True if the connection is accepted. Otherwise, false.
     * */
    protected open fun cellConnectionPredicate(remote: Cell) : Boolean {
        return ruleSet.accepts(locator, remote.locator)
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

                if(localObj.acceptsRemoteLocation(remote.locator) && remoteObj.acceptsRemoteLocation(this.locator)) {
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
            error("Tried to remove non-existent connection")
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
                    "Duplicate obj $o"
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
                    LOG.fatal("ELN2 illegal switch level $lastLevel ${graph.level}")
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
    fun clearObjectConnections() {
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

                if (!localObj.acceptsRemoteLocation(remoteCell.locator) || !remoteObj.acceptsRemoteLocation(this.locator)) {
                    continue
                }

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

fun isConnectionAccepted(a: Cell, b: Cell) = a.allowsConnection(b) && b.allowsConnection(a)

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
                error("Mismatched connection vs query result")
            }
        }

        if (markedNeighbors.isNotEmpty()) {
            error("Lingering connections $actualCell $markedNeighbors")
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
            check(graph.size == 1)

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

        while (neighborQueue.size > 0) {
            val neighbor = neighborQueue.removeFirst()

            // Create new circuit for all cells connected to this one.
            val graph = manager.createGraph()

            // Start BFS at the neighbor.
            bfsQueue.add(neighbor)

            while (bfsQueue.size > 0) {
                val cell = bfsQueue.removeFirst()

                if (!bfsVisited.add(cell)) {
                    continue
                }

                neighborQueue.remove(cell)

                graph.addCell(cell)

                // Enqueue neighbors (excluding the cell we are removing) for processing
                cell.connections.forEach { connCell ->
                    // This must be handled above.
                    check(connCell != removedCell)

                    bfsQueue.add(connCell)
                }
            }

            check(bfsQueue.isEmpty())

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
                if (isConnectionAccepted(actualCell, targetCell)) {
                    consumer(CellAndContainerHandle.captureInScope(targetCell))
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
                if (isConnectionAccepted(actualCell, targetCell)) {
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
                cell.container ?: error("Did not have container for $cell")
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
    private val electricalSims = ArrayList<Circuit>()
    private val thermalSims = ArrayList<Simulator>()

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
            LOG.error("Could not get cell at $locator") // exception may be swallowed
            error("Could not get cell at $locator")
        }

        return result
    }

    /**
     * Checks if the graph contains a cell with the specified [locator].
     * @return True if a cell with the [locator] exists in this graph. Otherwise, false.
     * */
    fun containsCellByLocator(locator: Locator) = cells.contains(locator)

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

        var stage = UpdateStep.Start

        try {
            val fixedDt = 1.0 / 100.0

            stage = UpdateStep.UpdateSubsPre
            simulationSubscribers.update(fixedDt, SubscriberPhase.Pre)

            lastTickTime = !measureDuration {

                stage = UpdateStep.UpdateElectricalSims
                val electricalTime = measureDuration {
                    electricalSims.forEach {
                        val success = it.step(fixedDt)

                        if (!success && !it.isFloating) {
                            LOG.error("Failed to update non-floating circuit!")
                        }
                    }
                }

                stage = UpdateStep.UpdateThermalSims
                val thermalTime = measureDuration {
                    thermalSims.forEach {
                        it.step(fixedDt)
                    }
                }
            }

            stage = UpdateStep.UpdateSubsPost
            simulationSubscribers.update(fixedDt, SubscriberPhase.Post)

            updates++

        } catch (t: Throwable) {
            LOG.error("FAILED TO UPDATE SIMULATION at $stage: $t")
        } finally {
            // Maybe blow up the game instead of just allowing this to go on?
            simulationStopLock.unlock()
        }
    }

    /**
     * This realizes the object subsets and creates the underlying simulations.
     * The simulation must be suspended before calling this method.
     * @see stopSimulation
     * */
    fun buildSolver() {
        validateMutationAccess()

        cells.forEach { it.clearObjectConnections() }
        cells.forEach { it.onBuildStarted() }
        cells.forEach { it.recordObjectConnections() }

        val circuitBuilders = realizeElectrical()

        realizeThermal()

        cells.forEach { cell ->
            cell.objects.forEachObject {
                when(it.type) {
                    Electrical -> {
                        (it as ElectricalObject<*>).build(circuitBuilders[it.circuit!!]!!)
                    }
                    Thermal -> {
                        (it as ThermalObject<*>).build()
                    }
                }
            }
        }

        circuitBuilders.values.forEach {
            it.build()
        }

        electricalSims.forEach { postProcessCircuit(it) }

        cells.forEach { it.onBuildFinished() }
    }

    /**
     * This method realizes the electrical circuits for all cells that have an electrical object.
     * */
    private fun realizeElectrical() : HashMap<Circuit, CircuitBuilder> {
        electricalSims.clear()

        val builders = HashMap<Circuit, CircuitBuilder>()

        realizeComponents(Electrical, factory = { set ->
            val circuit = Circuit()
            val builder = CircuitBuilder(circuit)

            set.forEach { it.objects.electricalObject.setNewCircuit(builder) }
            electricalSims.add(circuit)

            builders[circuit] = builder
        })

        return builders
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

        while (pending.size > 0) {
            check(queue.size == 0)

            visited.clear()

            queue.add(pending.first())

            while (queue.size > 0) {
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

    private fun postProcessCircuit(circuit: Circuit) {
        if (circuit.isFloating) {
            fixFloating(circuit)
        }
    }

    private fun fixFloating(circuit: Circuit) {
        var found = false
        for (comp in circuit.components) {
            if (comp is VoltageSource) {
                comp.ground(1)
                found = true
                break
            }
        }
        if (!found) {
            LOG.warn("Floating circuit and no VSource; the matrix is likely under-constrained.")
        }
    }

    /**
     * Removes the graph from tracking and invalidates the saved data.
     * The simulation must be stopped before calling this.
     * */
    fun destroy() {
        validateMutationAccess()

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
            error("Tried to stop simulation, but it was not running")
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
        ?: error("Graph with id $id not found")

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
 * Convention for the pin "exported" to other Electrical Objects.
 * */
const val EXTERNAL_PIN: Int = POSITIVE

/**
 * Convention for the pin used "internally" by Electrical Objects.
 * */
const val INTERNAL_PIN: Int = NEGATIVE
