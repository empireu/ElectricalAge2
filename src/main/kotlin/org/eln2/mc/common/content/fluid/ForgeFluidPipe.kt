package org.eln2.mc.common.content.fluid

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.event.TickEvent
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.OnServerThread
import org.eln2.mc.ServerOnly
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.common.fluids.foundation.FractionalFluidStack
import org.eln2.mc.common.fluids.foundation.IFractionalFluidHandler
import org.eln2.mc.extensions.addItem
import org.eln2.mc.extensions.getBase6Direction3dMask
import org.eln2.mc.extensions.plus
import org.eln2.mc.extensions.putBase6Direction3dMask
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask
import java.util.*

// P.S. I load all chunks synchronously. It's up to the player to add chunk-loaders, for now...

/**
 * mB/tick
 * */
private const val PUMP_RATE = 1

/**
 * Gets the neighbors of [pipe], by loading all required chunks.
 * */
@Suppress("NOTHING_TO_INLINE")
private inline fun getNeighborPipes(pipe: FluidPipeBlockEntity, results: ArrayList<FluidPipeBlockEntity>, exclude: FluidPipeBlockEntity?) {
    val level = pipe.level as ServerLevel

    pipe.whitelist.forEach { dirPipe ->
        val targetBlockPos = pipe.blockPos + dirPipe

        /**
         * This call will load the chunk fully:
         * */
        val neighborBlockEntity = level.getBlockEntity(targetBlockPos) as? FluidPipeBlockEntity
            ?: return@forEach

        if(neighborBlockEntity == exclude) {
            return@forEach
        }

        if(neighborBlockEntity.whitelist.has(dirPipe.opposite)) {
            results.add(neighborBlockEntity)
        }
    }
}

/**
 * Manages pipe networks for the server. Each level gets a [Repository], which handles allocation of new networks and building the networks based on pipe adjacency.
 * */
@ServerOnly @OnServerThread
object FluidPipeNetworkManager {
    /**
     * Manages networks for a level.
     * */
    class Repository(val level: ServerLevel) {
        /**
         * All [FluidPipeNetwork]s by their unique ID.
         * */
        private val networksByID = HashMap<UUID, FluidPipeNetwork>()

        private fun allocateNetwork() : FluidPipeNetwork {
            val result = FluidPipeNetwork(level)
            networksByID.putUnique(result.id, result)
            return result
        }

        private fun freeNetwork(network: FluidPipeNetwork) {
            check(networksByID.remove(network.id) != null)
        }

        private fun isCommonNetwork(neighborList: List<FluidPipeBlockEntity>) : Boolean {
            if(neighborList.size == 1) {
                return true
            }

            val a = neighborList[0].network

            for (i in 1 until neighborList.size) {
                if(a != neighborList[i].network) {
                    return false
                }
            }

            return true
        }

        /**
         * Inserts [pipe] into the world, handling all topological changes and returning the network [pipe] will be part of.
         * The cases are:
         * 1. There are no valid neighbor pipes, so a new network is created.
         * 2. There is only one valid neighbor pipe, so the neighbor's network is joined.
         * 3. There are multiple valid neighbors, but they are part of the same network, so that common network is joined.
         * 4. There are multiple valid neighbors, and there are at least 2 distinct networks. All the distinct networks are destroyed and a new network, with all the pipes from the previous networks and this new pipe is created.
         * */
        fun insertPipe(pipe: FluidPipeBlockEntity) : FluidPipeNetwork {
            val neighborList = ArrayList<FluidPipeBlockEntity>(2)
            getNeighborPipes(pipe, neighborList, null)

            if(neighborList.isEmpty()) {
                /**
                 * Case 1. Create new network:
                 * */
                val network = allocateNetwork()
                network.insert(pipe)
                return network
            }
            else if(isCommonNetwork(neighborList)) {
                /**
                 * Case 2 and 3. Join the existing network:
                 * */
                val network = neighborList[0].network
                network.insert(pipe)
                return network
            }
            else {
                /**
                 * Case 4. We need to create a new network, with all pipes and this one.
                 * */
                val network = allocateNetwork()
                network.insert(pipe)

                /**
                 * Identify separate networks, copy their pipes into the new one and notify pipes:
                 * */
                neighborList.map { it.network }.distinct().forEach { existingNetwork ->
                    existingNetwork.pipes.forEach { relocatedPipe ->
                        network.insert(relocatedPipe)
                    }

                    existingNetwork.pipes.forEach { relocatedPipe ->
                        relocatedPipe.networkChanged(network)
                    }

                    freeNetwork(existingNetwork)
                }

                return network
            }
        }

        /**
         * Removes [pipe] from the world. Can cause topological changes (e.g. splitting the network, if [pipe] is a cut vertex).
         * The cases are:
         * 1. There are no neighbors, so the network is destroyed.
         * 2. There is a single neighbor, so the pipe is removed from the network.
         * 3. There are multiple neighbors, and the pipe isn't a cut vertex, so the pipe is removed from the network.
         * 4. There are multiple neighbors, and the pipe is a cut vertex. The pipe is removed from the network, then the network is split into multiple.
         *
         * Case 3 and 4 are complicated to distinguish without introducing a lot of state, so they are handled by the same scanning routine.
         * */
        fun removePipe(pipe: FluidPipeBlockEntity) {
            val network = pipe.network

            if(network.pipes.size == 1) {
                /**
                 * Case 1. Destroy the network:
                 * */
                freeNetwork(network)
            }
            else {
                val neighborList = ArrayList<FluidPipeBlockEntity>(2)
                getNeighborPipes(pipe, neighborList, null)

                if(neighborList.size == 1) {
                    /**
                     * Case 2. Remove the pipe from the network:
                     * */
                    network.remove(pipe)
                }
                else {
                    /**
                     * Cases 3 and 4. The topology is re-built from scratch.
                     * The algorithm starts a search at each neighbor (excluding the removed pipe) to find each network.
                     * When an original neighbor of the pipe is encountered, it is removed from the list to process.
                     * */

                    val queue = ArrayDeque<FluidPipeBlockEntity>()
                    val visited = HashSet<FluidPipeBlockEntity>()
                    val adjacentNodes = ArrayList<FluidPipeBlockEntity>(6)

                    while (neighborList.isNotEmpty()) {
                        queue.add(neighborList.removeLast())

                        val newNetwork = allocateNetwork()
                        while (queue.isNotEmpty()) {
                            val front = queue.removeFirst()

                            if(!visited.add(front)) {
                                continue
                            }

                            /**
                             * Remove from the neighbor queue:
                             * */
                            neighborList.remove(front)

                            /**
                             * Move the pipe into the network:
                             * */
                            newNetwork.insert(front)
                            front.networkChanged(newNetwork)

                            /**
                             * Enqueue neighbors for processing:
                             * */
                            getNeighborPipes(front, adjacentNodes, pipe)
                            queue.addAll(adjacentNodes)
                            adjacentNodes.clear()
                        }

                        visited.clear()
                    }

                    freeNetwork(network)
                }
            }
        }

        fun update() {
            networksByID.values.forEach {
                it.update()
            }
        }
    }

    private val repositories = HashMap<ServerLevel, Repository>()

    /**
     * Gets the repository for the level.
     * */
    @OnServerThread
    fun getRepositoryFor(level: ServerLevel): Repository {
        var result = repositories[level]

        if(result == null) {
            result = Repository(level)
            repositories.putUnique(level, result)
        }

        return result
    }

    fun update() {
        repositories.values.forEach {
            it.update()
        }
    }
}

/**
 * Transient data structure storing endpoints in a pipe network.
 * The pipe network transports fluids between endpoints, so we don't need to have an internal buffer that needs saving.
 * This just acts as a holder for the endpoint machines, which allows us to run an algorithm to determine the transports each tick.
 *
 * The network building algorithm is very similar to the cell graph.
 * */
@OnServerThread
class FluidPipeNetwork(val level: ServerLevel) {
    val id: UUID = UUID.randomUUID()

    val pipes = HashSet<FluidPipeBlockEntity>()

    /**
     * List of machines connected to a pipe. The network will automatically extract fluid from these machines (pump module).
     * */
    val importEndpointsByPipe = HashMap<FluidPipeBlockEntity, ArrayList<IFluidHandler>>()

    /**
     * List of machines connected to a pipe. The network will automatically push fluid into these machines.
     * */
    val exportEndpointsByPipe = HashMap<FluidPipeBlockEntity, ArrayList<IFluidHandler>>()

    /**
     * List of target machines sorted by distance (along pipes) from the key.
     * */
    val nearestExportEndpointCache = HashMap<FluidPipeBlockEntity, ArrayList<IFluidHandler>>()

    /**
     * Invalidates all caches. Called when pipes are added/removed and when endpoints are added/removed.
     * */
    private fun invalidateCaches() {
        nearestExportEndpointCache.clear()
    }

    /**
     * Inserts the [pipe] and computes the endpoints.
     * */
    fun insert(pipe: FluidPipeBlockEntity) {
        check(pipes.add(pipe)) {
            DEBUGGER_BREAK("Duplicate add to network $pipe")
        }

        invalidateCaches()
        computeEndpoints(pipe)
    }

    /**
     * Removes the [pipe] and all associated data.
     * */
    fun remove(pipe: FluidPipeBlockEntity) {
        check(pipes.remove(pipe)) {
            DEBUGGER_BREAK("Removed non-existent pipe $pipe")
        }

        invalidateCaches()
        importEndpointsByPipe.remove(pipe)
        exportEndpointsByPipe.remove(pipe)
    }

    /**
     * Re-computes the endpoints for [pipe].
     * */
    fun onNeighborsChanged(pipe: FluidPipeBlockEntity) {
        invalidateCaches()
        computeEndpoints(pipe)
    }

    /**
     * Adds entries for [pipe] into [importEndpointsByPipe] and [exportEndpointsByPipe].
     * */
    private fun computeEndpoints(pipe: FluidPipeBlockEntity) {
        val importEndpoints = importEndpointsByPipe[pipe] ?: ArrayList(6)
        val exportEndpoints = exportEndpointsByPipe[pipe] ?: ArrayList(6)

        importEndpoints.clear()
        exportEndpoints.clear()

        pipe.whitelist.forEach { dir ->
            val targetPos = pipe.blockPos + dir
            val targetBlockEntity = level.getBlockEntity(targetPos)

            val moduleIndex = dir.get3DDataValue()
            val installedModule = pipe.modules[moduleIndex]

            fun dropModule() {
                if(installedModule != FluidPipeBlockEntity.ModuleType.None) {
                    pipe.modules[moduleIndex] = FluidPipeBlockEntity.ModuleType.None
                    if(installedModule.item != null){
                        level.addItem(
                            pipe.blockPos.x + 0.5,
                            pipe.blockPos.y + 0.5,
                            pipe.blockPos.z + 0.5,
                            ItemStack(installedModule.item.get(), 1)
                        )
                    }
                    pipe.setChanged()
                }
            }

            if(targetBlockEntity == null || targetBlockEntity is FluidPipeBlockEntity) {
                /**
                 * If the machine is gone, or a pipe somehow got connected quickly on that side, drop the module as an item:
                 * */
                dropModule()
                return@forEach
            }

            val capabilityLazy = targetBlockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, dir.opposite)

            if(!capabilityLazy.isPresent) {
                /**
                 * If the machine doesn't have a capability, drop the module as an item:
                 * */
                dropModule()
                return@forEach
            }

            val endpoint = capabilityLazy.resolve().get()

            if(installedModule.networkImportsFromEndpoint) {
                importEndpoints.add(endpoint)
            }

            if(installedModule.networkExportsToEndpoint) {
                exportEndpoints.add(endpoint)
            }
        }

        /**
         * Trims empty endpoint lists:
         * */

        if(importEndpoints.isNotEmpty()) {
            importEndpointsByPipe.putIfAbsent(pipe, importEndpoints)
        }
        else {
            importEndpointsByPipe.remove(pipe)
        }

        if(exportEndpoints.isNotEmpty()) {
            exportEndpointsByPipe.putIfAbsent(pipe, exportEndpoints)
        }
        else {
            exportEndpointsByPipe.remove(pipe)
        }
    }

    /**
     * Checks if any endpoints are accepting the fluid.
     * */
    fun canImport(fluid: FluidStack) : Boolean {
        /**
         * P.S. Iterating the jagged array shouldn't be an issue, we expect like 10 of those endpoints to exist per network.
         * */
        for (endpoints in importEndpointsByPipe.values) {
            for (handler in endpoints) {
                for (i in 0 until handler.tanks) {
                    if(handler.isFluidValid(i, fluid)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Gets the endpoints sorted by distance from [source].
     * */
    private fun getOrderedEndpointList(source: FluidPipeBlockEntity) = nearestExportEndpointCache.computeIfAbsent(source) {
        val result = ArrayList<IFluidHandler>()

        val neighborPipes = ArrayList<FluidPipeBlockEntity>(6)
        val visited = HashSet<FluidPipeBlockEntity>()
        val queue = ArrayDeque<FluidPipeBlockEntity>()

        queue.add(source)

        while (queue.isNotEmpty()) {
            val front = queue.remove()

            if(!visited.add(front)) {
                continue
            }

            if(front != source) {
                val endpoints = exportEndpointsByPipe[front]

                if(endpoints != null) {
                    result.addAll(endpoints)
                }
            }

            getNeighborPipes(front, neighborPipes, null)
            queue.addAll(neighborPipes)
            neighborPipes.clear()
        }

        result
    }

    /**
     * Called to move fluid from an endpoint to the rest of the endpoints.
     * Distributes as much as possible to the endpoints, ordered by distance from [source].
     * @param source The pipe attached to the endpoint.
     * */
    fun fillDiscrete(source: FluidPipeBlockEntity, resource: FluidStack, action: IFluidHandler.FluidAction) : Int {
        if (resource.fluid == Fluids.EMPTY || resource.amount <= 0) {
            return 0
        }

        val totalToFill = resource.amount
        var remaining = totalToFill
        var totalFilled = 0

        val targets = getOrderedEndpointList(source)

        for (i in targets.indices) {
            val handler = targets[i]

            val filled = handler.fill( FluidStack(resource.fluid, remaining), action)

            totalFilled += filled
            remaining -= filled

            if (remaining <= 0) {
                break
            }
        }

        return totalFilled
    }

    /**
     * Called to move fluid from an endpoint to the rest of the endpoints.
     * Distributes as much as possible to the endpoints, ordered by distance from [source].
     * If a discrete handler is encountered and the quantity has dropped below `1mB`, the handler is skipped, but discrete handlers may still receive the rest of the request.
     * @param source The pipe attached to the endpoint.
     * */
    fun fillFractional(source: FluidPipeBlockEntity, resource: FractionalFluidStack, action: IFluidHandler.FluidAction) : Double {
        if (resource.isEmpty) {
            return 0.0
        }

        val totalToFill = resource.amount
        var remaining = totalToFill
        var totalFilled = 0.0
        var skipDiscreteHandlers = false

        val targets = getOrderedEndpointList(source)

        for (i in targets.indices) {
            val handler = targets[i]

            if (remaining < FractionalFluidStack.EPSILON) {
                break
            }

            if (handler is IFractionalFluidHandler) {
                val filled = handler.fillFractional(FractionalFluidStack(resource.fluid, remaining), action)

                totalFilled += filled
                remaining -= filled
            }
            else if (!skipDiscreteHandlers) {
                val quantizedStack =  FractionalFluidStack(resource.fluid, remaining).quantized()

                if (quantizedStack.isEmpty) {
                    skipDiscreteHandlers = true
                }
                else {
                    val filled = handler.fill(quantizedStack, action)
                    totalFilled += filled.toDouble()
                    remaining -= filled
                }
            }
        }

        return totalFilled
    }

    /**
     * Tries to extract fluid from the endpoints and push it into the network.
     * The algorithm applied for every endpoint (source) works as follows:
     * - An extraction of [PUMP_RATE] is simulated from the source
     * - A network fill is simulated with that result, to obtain the amount that can be filled
     * - The amount that can be filled is extracted from the endpoint
     * - The result of that extraction is then network filled
     *
     * This prevents mass duplication but will allow voiding if handler logic is bad.
     * */
    private fun executePump(pipe: FluidPipeBlockEntity, endpoints: ArrayList<IFluidHandler>) {
        for (endpoint in endpoints) {
            if(endpoint is IFractionalFluidHandler) {
                val drainSimulation = endpoint.drainFractional(PUMP_RATE.toDouble(), IFluidHandler.FluidAction.SIMULATE)

                if(drainSimulation.isEmpty) {
                    continue
                }

                val fillSimulation = fillFractional(pipe, drainSimulation, IFluidHandler.FluidAction.SIMULATE)

                if(fillSimulation < FractionalFluidStack.EPSILON) {
                    continue
                }

                val drain = endpoint.drainFractional(FractionalFluidStack(drainSimulation.fluid, fillSimulation), IFluidHandler.FluidAction.EXECUTE)

                if(drain.isEmpty) {
                    continue
                }

                fillFractional(pipe, drain, IFluidHandler.FluidAction.EXECUTE)
            }
            else {
                val drainSimulation = endpoint.drain(PUMP_RATE, IFluidHandler.FluidAction.SIMULATE)

                if(drainSimulation.fluid == Fluids.EMPTY || drainSimulation.amount <= 0) {
                    continue
                }

                val fillSimulation = fillDiscrete(pipe, drainSimulation, IFluidHandler.FluidAction.SIMULATE)

                if(fillSimulation <= 0) {
                    continue
                }

                val drain = endpoint.drain(FluidStack(drainSimulation.fluid, fillSimulation), IFluidHandler.FluidAction.EXECUTE)

                if(drain.fluid == Fluids.EMPTY || drain.amount <= 0) {
                    continue
                }

                fillDiscrete(pipe, drain, IFluidHandler.FluidAction.EXECUTE)
            }
        }
    }

    /**
     * Applies all automatic fluid transport, created by the pump module.
     * */
    fun update() {
        importEndpointsByPipe.forEach { (pipe, endpoints) ->
            executePump(pipe, endpoints)
        }
    }
}

class FluidPipeBlock : Block(Properties.of()), EntityBlock {
    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = FluidPipeBlockEntity(pPos, pState)

    /**
     * Schedules the [FluidPipeBlockEntity.neighborChanged] to execute on the server.
     * */
    override fun onNeighborChange(state: BlockState, level: LevelReader, pos: BlockPos, neighbor: BlockPos) {
        super.onNeighborChange(state, level, pos, neighbor)

        if(!level.isClientSide) {
            Scheduler.scheduleWork(0, {
                val blockEntity = level.getBlockEntity(pos) as? FluidPipeBlockEntity
                    ?: return@scheduleWork

                blockEntity.neighborChanged()
            }, TickEvent.Phase.END)
        }
    }
}

class FluidPipeModuleItem : Item(Properties())

class FluidPipeBlockEntity(pPos: BlockPos, pState: BlockState) : BlockEntity(Eln2ForgeFluids.FLUID_PIPE_BLOCK_ENTITY.get(), pPos, pState), ComponentDisplay {
    //#region Block Entity Lifetime Hooks

    /**
     * Acquires the repository and registers the pipe into the network, if on the server.
     * */
    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if(!pLevel.isClientSide) {
            val level = level as? ServerLevel
                ?: error(DEBUGGER_BREAK("Could not cast level to ServerLevel for pipe"))

            repositoryInternal = FluidPipeNetworkManager.getRepositoryFor(level)
            registerIntoNetwork()
        }
    }

    override fun setRemoved() {
        if(level?.isClientSide == false) {
            removeFromNetwork()
        }

        super.setRemoved()
    }

    override fun onChunkUnloaded() {
        if(level?.isClientSide == false) {
            removeFromNetwork()
        }

        super.onChunkUnloaded()
    }

    //#endregion

    private var repositoryInternal : FluidPipeNetworkManager.Repository? = null
    val repository: FluidPipeNetworkManager.Repository get() = repositoryInternal ?: error(DEBUGGER_BREAK("Tried to get repository for pipe at $blockPos before it was acquired"))

    private var networkInternal: FluidPipeNetwork? = null
    val network: FluidPipeNetwork get() = networkInternal ?: error(DEBUGGER_BREAK("Tried to get network for pipe at $blockPos before it was acquired"))

    /**
     * Module item installed on the connection to a machine.
     * @param item The item corresponding to the module, or null, for the default value.
     * @param networkImportsFromEndpoint True if the network actively pulls from the machine.
     * @param networkExportsToEndpoint True if the network actively pushes into the machine.
     * @param machineCanPushIntoNetwork True if the machine is allowed to push into the network.
     * */
    enum class ModuleType(val item: RegistryObject<FluidPipeModuleItem>?, val networkImportsFromEndpoint: Boolean, val networkExportsToEndpoint: Boolean, val machineCanPushIntoNetwork: Boolean) {
        /**
         * The default value.
         * - The network tries to push fluid into the machine.
         * - The network allows the machine to push fluid.
         * - The network doesn't extract fluid from the machine.
         * */
        None(null, false, true, true),

        /**
         * Pumps from the machine into the network, but doesn't block the network from filling the machine.
         * - The network tries to extract fluid from the machine.
         * - The network tries to insert fluid into the machine.
         * - The network allows the machine to push fluid.
         * */
        Pump(Eln2ForgeFluids.FLUID_PIPE_PUMP_MODULE, true, true, true),

        /**
         * Pumps from the machine into the network, and blocks the network from filling the machine.
         * - The network tries to extract fluid from the machine.
         * - The network doesn't push fluid into the machine.
         * - The network allows the machine to push fluid.
         * */
        GatedPump(Eln2ForgeFluids.FLUID_PIPE_GATED_PUMP_MODULE, true, false, true)
    }

    /**
     * Whitelist of sides that allow pipe-pipe connections.
     * */
    var whitelist = Base6Direction3dMask.FULL
        private set

    /**
     * The modules applied to each side of the pipe.
     * */
    var modules = Array<ModuleType>(6) {
        ModuleType.None
    }

    //#region Capability

    /**
     * Capability for a specific side. The side basically decides if `fill` is allowed based on the installed module.
     * */
    class Handler(val pipe: FluidPipeBlockEntity, val side: Direction) : IFractionalFluidHandler {
        //#region Dummy Implementation

        /**
         * Indicates that there is a tank to work with:
         * */
        override fun getTanks() = 1

        /**
         * Related to drain methods. Shows that draining is not possible:
         * */
        override fun getFluidInTank(tank: Int): FluidStack = FluidStack.EMPTY
        override fun getFractionalFluidInTank(tank: Int) = FractionalFluidStack.EMPTY

        /**
         * Indicates that there is space for insert:
         * */
        override fun getTankCapacity(tank: Int) = 1000
        override fun getFractionalTankCapacity(tank: Int) = 1000.0

        /**
         * Pipes don't allow the machines to pull from the network. The network pushes fluid into the machines.
         * */
        override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack = FluidStack.EMPTY
        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack = FluidStack.EMPTY
        override fun drainFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction) = FractionalFluidStack.EMPTY
        override fun drainFractional(maxDrain: Double, action: IFluidHandler.FluidAction) = FractionalFluidStack.EMPTY

        //#endregion

        val module get() = pipe.modules[side.get3DDataValue()]

        /**
         * Checks if the fluid is valid for any handler. Also makes sure the machine is allowed to push.
         * */
        override fun isFluidValid(tank: Int, stack: FluidStack) : Boolean {
            if(!module.machineCanPushIntoNetwork) {
                return false
            }

            return pipe.network.canImport(stack)
        }

        /**
         * If the machine is allowed to push, forwards the action to the network.
         * */
        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            if(!module.machineCanPushIntoNetwork) {
                return 0
            }

            return pipe.network.fillDiscrete(pipe, resource, action)
        }

        /**
         * If the machine is allowed to push, forwards the action to the network.
         * */
        override fun fillFractional(resource: FractionalFluidStack, action: IFluidHandler.FluidAction): Double {
            if(!module.machineCanPushIntoNetwork) {
                return 0.0
            }

            return pipe.network.fillFractional(pipe, resource, action)
        }
    }

    var handlers = Array<Handler>(6) {
        Handler(this, Direction.from3DDataValue(it))
    }

    var handlersLazy = Array<LazyOptional<Handler>>(6) {
        LazyOptional.of { handlers[it] }
    }

    override fun <T : Any?> getCapability(cap: Capability<T?>, side: Direction?): LazyOptional<T?> {
        if(side != null) {
            return handlersLazy[side.get3DDataValue()].cast()
        }

        return super.getCapability(cap, side)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()

        handlersLazy.forEach {
            it.invalidate()
        }
    }

    //#endregion

    //#region Network Lifetime

    /**
     * Called when the block entity is added into the world.
     * */
    @OnServerThread
    private fun registerIntoNetwork() {
        check(networkInternal == null) {
            DEBUGGER_BREAK("Tried to register pipe that was already in a network")
        }

        networkInternal = repository.insertPipe(this)
    }

    /**
     * Called when the block entity is removed from the world.
     * */
    @OnServerThread
    private fun removeFromNetwork() {
        if(networkInternal != null) {
            repository.removePipe(this)
            networkInternal = null
        }
    }

    /**
     * Called when a neighbor has changed, and the connections may need to be updated.
     * Called by the scheduler, after the block receives the event (see the call site).
     * */
    @OnServerThread
    fun neighborChanged() {
        network.onNeighborsChanged(this)
    }

    /**
     * Called by the manager when topological changes have occurred.
     * */
    @OnServerThread
    fun networkChanged(newNetwork: FluidPipeNetwork) {
        networkInternal = newNetwork
    }

    //#endregion

    //#region Saving

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)
        pTag.putBase6Direction3dMask("pipeWhitelist", whitelist)
        pTag.putIntArray("modules", modules.map { it.ordinal })
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)
        whitelist = pTag.getBase6Direction3dMask("pipeWhitelist")
        pTag.getIntArray("modules").forEachIndexed { idx, module ->
            modules[idx] = ModuleType.entries[module]
        }
    }

    //#endregion

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Network: ${network.id}" }
    }
}
