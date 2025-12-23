package org.eln2.mc.common.content.fluid

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.event.TickEvent
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.OnServerThread
import org.eln2.mc.ServerOnly
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.data.LinearObjectPool
import org.eln2.mc.data.PooledObjectPolicy
import org.eln2.mc.extensions.getBase6Direction3dMask
import org.eln2.mc.extensions.plus
import org.eln2.mc.extensions.putBase6Direction3dMask
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.mathematics.Base6Direction3dMask
import java.util.*

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

        /**
         * Gets the neighbors of [pipe], by loading all required chunks.
         * */
        private fun getNeighbors(pipe: FluidPipeBlockEntity, results: ArrayList<FluidPipeBlockEntity>, exclude: FluidPipeBlockEntity?) {
            val level = pipe.level as ServerLevel

            pipe.pipeWhitelistMask.forEach { dirPipe ->
                val targetBlockPos = pipe.blockPos + dirPipe

                /**
                 * This call will load the chunk fully:
                 * */
                val neighborBlockEntity = level.getBlockEntity(targetBlockPos) as? FluidPipeBlockEntity
                    ?: return@forEach

                if(neighborBlockEntity == exclude) {
                    return@forEach
                }

                if(neighborBlockEntity.pipeWhitelistMask.has(dirPipe.opposite)) {
                    results.add(neighborBlockEntity)
                }
            }
        }

        private fun allocateNetwork() : FluidPipeNetwork {
            val result = FluidPipeNetwork.pool.get()
            networksByID.putUnique(result.id, result) {
                DEBUGGER_BREAK("Duplicate UUID for network $result, ${result.id}")
            }

            return result
        }

        private fun freeNetwork(network: FluidPipeNetwork) {
            check(networksByID.remove(network.id) != null) {
                DEBUGGER_BREAK("Tried to free network that wasn't present")
            }

            FluidPipeNetwork.pool.release(network)
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
            getNeighbors(pipe, neighborList, null)

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
                getNeighbors(pipe, neighborList, null)

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
                            getNeighbors(front, adjacentNodes, pipe)
                            queue.addAll(adjacentNodes)
                            adjacentNodes.clear()
                        }

                        visited.clear()
                    }

                    freeNetwork(network)
                }
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
}

/**
 * Transient data structure storing endpoints in a pipe network.
 * The pipe network transports fluids between endpoints, so we don't need to have an internal buffer that needs saving.
 * This just acts as a holder for the endpoint machines, which allows us to run an algorithm to determine the transports each tick.
 *
 * The network building algorithm is very similar to the cell graph.
 * */
class FluidPipeNetwork private constructor(var id: UUID) {
    val pipes = HashSet<FluidPipeBlockEntity>()

    fun insert(pipe: FluidPipeBlockEntity) {
        check(pipes.add(pipe)) {
            DEBUGGER_BREAK("Duplicate add to network $pipe")
        }
    }

    fun remove(pipe: FluidPipeBlockEntity) {
        check(pipes.remove(pipe)) {
            DEBUGGER_BREAK("Removed non-existent pipe $pipe")
        }
    }

    companion object {
        val pool = LinearObjectPool<FluidPipeNetwork>(object : PooledObjectPolicy<FluidPipeNetwork> {
            override fun create(): FluidPipeNetwork {
                return FluidPipeNetwork(UUID.randomUUID())
            }

            override fun release(obj: FluidPipeNetwork): Boolean {
                obj.id = UUID.randomUUID()
                obj.pipes.clear()
                return true
            }
        }, 4096)
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
     * Whitelist of sides that allow pipe-pipe connections.
     * */
    var pipeWhitelistMask = Base6Direction3dMask.FULL
        private set

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

    }

    /**
     * Called by the manager when topological changes have occurred.
     * */
    @OnServerThread
    fun networkChanged(newNetwork: FluidPipeNetwork) {
        networkInternal = newNetwork
    }

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)
        pTag.putBase6Direction3dMask("pipeWhitelist", pipeWhitelistMask)
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)
        pipeWhitelistMask = pTag.getBase6Direction3dMask("pipeWhitelist")
    }

    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Network: ${network.id}" }
    }
}
