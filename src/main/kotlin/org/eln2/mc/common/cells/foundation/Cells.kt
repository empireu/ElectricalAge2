@file:Suppress("MemberVisibilityCanBePrivate", "ClassName")

package org.eln2.mc.common.cells.foundation

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkStatus
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Simulator
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.utils.*
import org.eln2.mc.*
import org.eln2.mc.common.cells.CellRegistry
import org.eln2.mc.common.cells.foundation.CellLayer.*
import org.eln2.mc.common.cells.foundation.SimulationObjectType.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.extensions.*
import java.util.*
import java.util.concurrent.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.isSuperclassOf

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

    /**
     * Separate, tracked sets of subscribers for various dispatch stages and lifetimes:
     * - Simulation - dispatched on the simulation thread
     * - Server - dispatched on the game tick loop
     * - Persistent - these subscribers live throughout the lifetime of the cell
     * - Transient - these subscribers live as long as players are watching the game object
     * */
    class SeparatedSubscriberCollections {
        class Implementation<Phase> : SubscriberCollection<Phase> {
            var wrappedCollection: SubscriberCollection<Phase>? = null

            private val underlyingCollection get() = wrappedCollection ?: error("Wrapped collection was not set")

            private val subscribers = HashMap<SimulationSubscriber<Phase>, SubscriberOptions<Phase>>()

            fun wrap(targetCollection: SubscriberCollection<Phase>) {
                wrappedCollection = targetCollection
            }

            override fun addSubscriber(parameters: SubscriberOptions<Phase>, subscriber: SimulationSubscriber<Phase>) {
                require(subscribers.put(subscriber, parameters) == null) { "Duplicate subscriber $subscriber" }
                underlyingCollection.addSubscriber(parameters, subscriber)
            }

            override fun remove(subscriber: SimulationSubscriber<Phase>) {
                require(subscribers.remove(subscriber) != null) { "Subscriber $subscriber was never added" }
                underlyingCollection.remove(subscriber)
            }

            fun clear() {
                subscribers.keys.forEach { underlyingCollection.remove(it) }
                subscribers.clear()
            }
        }

        /**
         * Sets up the collections to add subscribers for [graph].
         * */
        fun acquire(graph: CellGraph) {
            val simulationPool = graph.simulationThreadSubscribers
            val serverPool = graph.serverThreadSubscribers

            persistentSimulation.wrap(simulationPool)
            persistentServer.wrap(serverPool)
            transientSimulation.wrap(simulationPool)
            transientServer.wrap(serverPool)
        }

        fun clear() {
            persistentSimulation.clear()
            persistentServer.clear()
            transientSimulation.clear()
            transientServer.clear()
        }

        /**
         * Persistent pool dispatched on the simulation thread.
         * */
        val persistentSimulation = Implementation<SimulationPhase>()

        /**
         * Persistent pool dispatched on the server thread.
         * */
        val persistentServer = Implementation<ServerPhase>()

        /**
         * Transient pool dispatched on the simulation thread.
         * */
        val transientSimulation = Implementation<SimulationPhase>()

        /**
         * Transient pool dispatched on the server thread.
         * */
        val transientServer = Implementation<ServerPhase>()
    }

    val subscribers = SeparatedSubscriberCollections()

    lateinit var graph: CellGraph
    var connections = ArrayList<Cell>(0)

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

    override fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        super.subscribe(subscribers)
        dispatchLifetime(Cell_subscribe(subscribers))
    }

    override fun subscribeServerThread(subscribers: SubscriberCollection<ServerPhase>) {
        super.subscribeServerThread(subscribers)
        dispatchLifetime(Cell_subscribeServer(subscribers))
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
            LOG.warn(DEBUGGER_BREAK("Forcing connection rule"))
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
    open fun countPossibleConnections(remote: Cell) : Int {
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
            replicator.subscribe(subscribers.transientSimulation)
            replicator.subscribeServerThread(subscribers.transientServer)
        }
    }

    fun unbindGameObjects() {
        requireIsOnServerThread { "unbindGameObjects" }

        replicators.forEach {
            behaviorContainer.destroy(it)
        }

        replicators.clear()
        subscribers.transientSimulation.clear()
        subscribers.transientServer.clear()
    }

    override fun onDestroying() {
        super.onDestroying()
        dispatchLifetime(Cell_onDestroying)
        isBeingRemoved = true
        behaviorContainer.destroy()
        subscribers.clear()
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

            subscribers.clear()
            subscribers.acquire(graph)

            behaviorContainer.behaviors.forEach {
                it.subscribe(subscribers.persistentSimulation)
                it.subscribeServerThread(subscribers.persistentServer)
            }

            subscribe(subscribers.persistentSimulation)
            subscribeServerThread(subscribers.persistentServer)
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

interface CellContainer {
    fun getCells(): List<Cell>
    fun neighborScan(actualCell: Cell): List<CellAndContainerHandle>
    fun onCellConnected(actualCell: Cell, remoteCell: Cell) {}
    fun onCellDisconnected(actualCell: Cell, remoteCell: Cell) {}
    fun onTopologyChanged() {}

    val manager: CellGraphManager
}

/**
 * Encapsulates information about a neighbor cell. The cell's container is considered in-scope, meaning its chunk is loaded and ready for operations.
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

