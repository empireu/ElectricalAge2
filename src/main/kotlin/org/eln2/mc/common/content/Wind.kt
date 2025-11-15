package org.eln2.mc.common.content

import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import org.ageseries.libage.data.BoundingBoxTree3d
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.utils.addUnique
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.*
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.extensions.cast
import org.eln2.mc.extensions.minus
import org.eln2.mc.extensions.toVector3d
import org.eln2.mc.mathematics.BlockPosInt
import org.eln2.mc.mathematics.floorBlockPos
import java.util.*

@ServerOnly @CrossThreadAccess
object WindTurbineManager {
    private val levels = HashMap<ServerLevel, LevelWindData>()
    private val obj = Any()

    @OnServerThread
    fun clear() {
        synchronized(obj) {
            levels.clear()
        }
    }

    private fun validateLevel(level: Level): ServerLevel {
        if(level !is ServerLevel) {
            error("Cannot use non server level $level")
        }

        return level
    }

    private fun getLevelDataNonSynchronized(level: Level) : LevelWindData {
        val serverLevel = validateLevel(level)

        return levels.computeIfAbsent(serverLevel) {
            LevelWindData(serverLevel)
        }
    }

    /**
     * Creates a wind turbine with the specified area of influence.
     * */
    @CrossThreadAccess
    fun createHandle(level: ServerLevel, bounds: BoundingBox3d) : WindTurbineHandle {
        synchronized(obj) {
            val data = getLevelDataNonSynchronized(level)
            return data.createTurbine(bounds)
        }
    }

    /**
     * Destroys the wind turbine.
     * */
    @CrossThreadAccess
    fun destroyHandle(handle: WindTurbineHandle) {
        synchronized(obj) {
            val data = getLevelDataNonSynchronized(handle.level)
            data.destroyTurbine(handle)
        }
    }

    @OnServerThread
    fun handleBlockEvent(level: ServerLevel, blockPos: BlockPos) {
        synchronized(obj) {
            val data = getLevelDataNonSynchronized(level)
            data.handleBlockEvent(blockPos)
        }
    }

    interface WindTurbineHandle {
        /**
         * The level of the cell that created this handle.
         * */
        val level: ServerLevel

        /**
         * Unique ID created by the manager for this turbine.
         * */
        val id: UUID

        /**
         * The volume of influence of this turbine.
         * */
        val influenceBounds: BoundingBox3d

        /**
         * Gets the clearance of this wind turbine. If the turbine is all clear, the factor is `1`.
         * If other turbines are close-by or blocks exist in the volume, this clearance factor goes down.
         *
         * When the wind turbine is created initially (possibly on the simulation thread), the factor is `0`.
         * */
        val clearanceFactor: Double
    }

    private class WindTurbineVolumeImpl(override val id: UUID, override val influenceBounds: BoundingBox3d, val owner: LevelWindData) : WindTurbineHandle {
        override val level: ServerLevel
            get() = owner.level

        /**
         * The number of voxels in [influenceBounds], as per [BlockPos.betweenClosedStream].
         * */
        private val volumeVoxels = run {
            val (minX, minY, minZ) = influenceBounds.min.floor()
            val (maxX, maxY, maxZ) = influenceBounds.max.floor()

            val width = (maxX - minX).toInt()
            val height = (maxY - minY).toInt()
            val depth = (maxZ - minZ).toInt()

            width * height * depth
        }

        /**
         * Sum of obstruction strengths. Independent of the number of blocks.
         * This score is always lower than or equal to [volumeVoxels].
         * */
        private var blockObstructionScore = 0.0

        /**
         * The clearance, taking into account only blocks in the vicinity.
         * Initially set to `0` because we cannot access the blocks from the caller's thread.
         * */
        var blockClearanceFactor = 0.0

        /**
         * The clearance, taking into account only other turbines in the vicinity.
         * Computed fully on insert.
         * */
        var turbineClearanceFactor = 0.0

        /**
         * A relative block pos for storing packed coordinates.
         * */
        val referencePosition = influenceBounds.center.floorBlockPos()

        /**
         * Blocks that intersect the [influenceBounds].
         * Stored as [org.eln2.mc.mathematics.BlockPosInt] relative to the [referencePosition].
         * The value is the obstruction score that was included in the [blockObstructionScore].
         * */
        val blocksInVolume = Int2DoubleOpenHashMap().also {
            it.defaultReturnValue(Double.NaN)
        }

        /**
         * Other turbines whose bounds intersect the [influenceBounds].
         * */
        val turbinesInVolume = HashSet<WindTurbineVolumeImpl>()

        /**
         * The final clearance factor.
         * */
        override val clearanceFactor get() = blockClearanceFactor * turbineClearanceFactor

        /**
         * Gets the block position as a packed position relative to [referencePosition] for [blocksInVolume].
         * */
        private fun getBlockKey(blockPosWorld: BlockPos) = BlockPosInt.of(blockPosWorld - referencePosition).value

        /**
         * Executed on the server thread once this turbine has been created.
         * */
        @ServerOnly
        fun serverThreadInitialization() {
            requireIsOnServerThread()

            // Guard against some stray events firing before this:
            blockObstructionScore = 0.0

            /**
             * Records all blocks in volume and calculates score:
             * */
            BlockPos.betweenClosedStream(influenceBounds.cast()).forEach { blockPosWorld ->
                evaluateBlockInWorld(blockPosWorld)
            }
        }

        /**
         * Calculates the [blockClearanceFactor] based on the [blockObstructionScore].
         * */
        fun setBlockClearanceFactor() {
            var result = (volumeVoxels - blockObstructionScore) / volumeVoxels

            if(result < 0.0) {
                DEBUGGER_BREAK {
                    "blockClearanceFactor went negative"
                }

                result = 0.0
            }

            blockClearanceFactor = result
        }

        @OnServerThread
        private fun getObstructionScoreForBlockState(blockPos: BlockPos) : Double {
            val blockState = owner.level.getBlockState(blockPos)

            if(blockState.isAir) {
                return 0.0
            }

            // Why the FRAK do you need the LEVEL to get shape information, you son of a bitch!

            val level = owner.level

            if (blockState.isCollisionShapeFullBlock(level, blockPos)) {
                return 1.0
            }

            val shape = blockState.getCollisionShape(level, blockPos)

            if (shape.isEmpty) {
                return 0.0
            }

            var volume = 0.0
            shape.forAllBoxes { minX, minY, minZ, maxX, maxY, maxZ ->
                val width = maxX - minX
                val height = maxY - minY
                val depth = maxZ - minZ

                volume += width * height * depth
            }

            return volume.coerceIn(0.0, 1.0)
        }

        /**
         * Called when a block has changed in the world, that is inside the volume.
         * This either inserts or remove a block obstruction.
         * */
        @OnServerThread
        fun evaluateBlockInWorld(blockPosWorld: BlockPos) {
            requireIsOnServerThread()

            val key = getBlockKey(blockPosWorld)
            val existingScore = blocksInVolume.get(key)
            val newScore = getObstructionScoreForBlockState(blockPosWorld)

            /**
             * Replaces a block that was previously recorded:
             * */
            if(existingScore != blocksInVolume.defaultReturnValue()) {
                if(existingScore != newScore) {
                    // Replace previous score:
                    blocksInVolume.remove(key)
                    blockObstructionScore -= existingScore

                    // Add new contribution:
                    blocksInVolume.putUnique(key, newScore)
                    blockObstructionScore += newScore
                    setBlockClearanceFactor()
                }
            }
            /**
             * Inserts a new block:
             * */
            else {
                if(newScore.approxEq(0.0)) {
                    return // Air or too small
                }

                blocksInVolume.putUnique(key, newScore)
                blockObstructionScore += newScore
                setBlockClearanceFactor()
            }
        }

        /**
         * Recomputes [turbineClearanceFactor] with all [turbinesInVolume].
         * */
        fun recomputeTurbineObstruction() {
            var result = 1.0
            val turbineVolume = influenceBounds.width * influenceBounds.height * influenceBounds.depth

            turbinesInVolume.forEach { other ->
                val intersection = other.influenceBounds intersectionWith influenceBounds
                val intersectedVolume = intersection.width * intersection.height * intersection.depth

                result *= 1.0 - (intersectedVolume / turbineVolume)
            }

            turbineClearanceFactor = result
        }
    }

    private class LevelWindData(val level: ServerLevel) {
        /**
         * BVH built on the [WindTurbineVolumeImpl.influenceBounds].
         * */
        val influenceHierarchy = BoundingBoxTree3d<WindTurbineVolumeImpl>()
        val turbines = HashSet<WindTurbineVolumeImpl>()

        private inline fun findIntersections(bounds: BoundingBox3d, consumer: (WindTurbineVolumeImpl) -> Unit) {
            influenceHierarchy.queryIntersecting({ bounds intersectsWith it.box }) { leaf ->
                val other = requireNotNull(leaf.data) { DEBUGGER_BREAK()  }
                consumer(other)
                true
            }
        }

        /**
         * Creates a new turbine volume.
         * This immediately finds all intersected turbines and records the intersections, then calculates the [WindTurbineVolumeImpl.turbineClearanceFactor].
         *
         * Then, a task is scheduled to run on the server thread to find all blocks in intersection. Only then, the block clearance is calculated, which results in a nonzero final clearance.
         * */
        fun createTurbine(influenceBounds: BoundingBox3d) : WindTurbineHandle {
            val result = WindTurbineVolumeImpl(UUID.randomUUID(), influenceBounds, this)

            /**
             * Finds all intersections between turbines and records them.
             * */
            findIntersections(influenceBounds) { other ->
                // Add intersection pair:
                other.turbinesInVolume.addUnique(result)
                result.turbinesInVolume.addUnique(result)

                // Recompute obstruction for remote turbine:
                other.recomputeTurbineObstruction()
            }

            // Recompute obstruction for new turbine:
            result.recomputeTurbineObstruction()

            influenceHierarchy.insert(result, influenceBounds)
            turbines.addUnique(result)

            /**
             * Schedule a complete scan of the in-world blocks:
             * */
            Scheduler.scheduleWork(
                0,
                result::serverThreadInitialization,
                TickEvent.Phase.END
            )

            return result
        }

        /**
         * Removes the turbine volume and re-calculates the obstruction factors for the other turbines immediately.
         * */
        fun destroyTurbine(handle: WindTurbineHandle) {
            val impl = requireNotNull(handle as? WindTurbineVolumeImpl) {
                DEBUGGER_BREAK(handle)
            }

            if(!turbines.remove(impl)) {
                return
            }

            /**
             * Removes the intersection pairs and recomputes the turbine clearance:
             * */
            findIntersections(impl.influenceBounds) { other ->
                check(other.turbinesInVolume.remove(impl)) {
                    DEBUGGER_BREAK("Other turbine did not have removed turbine recorded")
                }

                other.recomputeTurbineObstruction()
            }
        }

        /**
         * Handles a block event sent from the server thread from forge.
         * */
        @OnServerThread
        fun handleBlockEvent(blockPos: BlockPos) {
            val min = blockPos.toVector3d()
            val bounds = BoundingBox3d(min, min + Vector3d.one)

            /**
             * Updates scores for each turbine volume that intersects the block:
             * */
            findIntersections(bounds) { turbine ->
                turbine.evaluateBlockInWorld(blockPos)
            }
        }
    }
}

