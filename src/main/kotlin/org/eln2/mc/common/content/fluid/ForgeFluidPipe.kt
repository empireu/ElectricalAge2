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
import org.eln2.mc.common.content.modules.Eln2ForgeFluids
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.data.LinearObjectPool
import org.eln2.mc.data.PooledObjectPolicy
import org.eln2.mc.extensions.getBase6Direction3dMask
import org.eln2.mc.extensions.putBase6Direction3dMask
import org.eln2.mc.mathematics.Base6Direction3dMask
import java.util.*

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
         * [FluidPipeNetwork]s by the position of each node.
         * */
        private val networksByBlockPos = HashMap<BlockPos, FluidPipeNetwork>()

        /**
         * Inserts [pipe] into the world, handling all topological changes and returning the network [pipe] will be part of.
         * */
        fun insert(pipe: FluidPipeBlockEntity) : FluidPipeNetwork {
            val blockPos = pipe.blockPos
                ?: error(DEBUGGER_BREAK("Cannot insert pipe with null block pos"))

            check(!networksByBlockPos.contains(blockPos)) {
                DEBUGGER_BREAK("Duplicate insert pipe at $blockPos")
            }

            TODO()
        }

        /**
         * Removes [pipe] from the world. Can cause topological changes (e.g. splitting the network, if [pipe] is a cut vertex).
         * */
        fun remove(pipe: FluidPipeBlockEntity) {
            val blockPos = pipe.blockPos
                ?: error(DEBUGGER_BREAK("Cannot remove pipe with null block pos"))

            val network = networksByBlockPos[blockPos]
                ?: return // Allow multiple removal
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
    companion object {
        private val pool = LinearObjectPool<FluidPipeNetwork>(object : PooledObjectPolicy<FluidPipeNetwork> {
            override fun create(): FluidPipeNetwork {
                return FluidPipeNetwork(UUID.randomUUID())
            }

            override fun release(obj: FluidPipeNetwork): Boolean {
                obj.id = UUID.randomUUID()

                return true
            }
        }, 4096)

        @OnServerThread
        fun get() = pool.get()

        @OnServerThread
        fun release(obj: FluidPipeNetwork) = pool.release(obj)
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

class FluidPipeBlockEntity(pPos: BlockPos, pState: BlockState) : BlockEntity(Eln2ForgeFluids.FLUID_PIPE_BLOCK_ENTITY.get(), pPos, pState) {
    //#region Block Entity Lifetime Hooks

    override fun setLevel(pLevel: Level) {
        super.setLevel(pLevel)

        if(!pLevel.isClientSide) {
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
     * Gets the repository for the level.
     * */
    @OnServerThread
    private fun getRepository() : FluidPipeNetworkManager.Repository {
        val level = level as? ServerLevel
            ?: error(DEBUGGER_BREAK("Could not get repository for block entity at $blockPos, with level ${this.level}"))

        return FluidPipeNetworkManager.getRepositoryFor(level)
    }

    /**
     * Called when the block entity is added into the world.
     * */
    @OnServerThread
    private fun registerIntoNetwork() {
        repositoryInternal = getRepository()
    }

    /**
     * Called when the block entity is removed from the world.
     * */
    @OnServerThread
    private fun removeFromNetwork() {

    }

    /**
     * Called when a neighbor has changed, and the connections may need to be updated.
     * Called by the scheduler, after the block receives the event (see the call site).
     * */
    @OnServerThread
    fun neighborChanged() {

    }

    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)
        pTag.putBase6Direction3dMask("pipeWhitelist", pipeWhitelistMask)
    }

    override fun load(pTag: CompoundTag) {
        super.load(pTag)
        pipeWhitelistMask = pTag.getBase6Direction3dMask("pipeWhitelist")
    }
}
