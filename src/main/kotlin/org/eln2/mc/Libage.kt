package org.eln2.mc

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap
import net.minecraft.core.Vec3i
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.ArrayKDVectorD
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.Ray3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.Vector3di
import org.ageseries.libage.sim.electrical.Port
import org.ageseries.libage.sim.kinetic.FrictionKineticNode
import org.ageseries.libage.sim.kinetic.KineticDouble
import org.ageseries.libage.sim.kinetic.KineticMono
import org.ageseries.libage.sim.kinetic.KineticTriple
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.common.cells.foundation.CellGraph
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.*

class ListCombination<T>(val a: List<T>, val b: List<T>) : Iterable<T> {
    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var index = 0

        override fun hasNext() = index < (a.size + b.size)

        override fun next(): T {
            if (!hasNext()) {
                throw NoSuchElementException()
            }

            return if (index < a.size) a[index++] else b[index++ - a.size]
        }
    }
}

class FramerateIndependentSmoother1dA(val tau: Double) {
    var value = 0.0

    private var initialized = false

    fun reset() {
        initialized = false
        value = 0.0
    }

    fun update(dt: Double, target: Double) : Double {
        if(!initialized) {
            value = target
            initialized = true
            return dt
        }

        val alpha = 1.0 - exp(-dt / tau)
        value += (target - value) * alpha

        return dt
    }

    fun pullDown(eps: Double = 1e-6) {
        if(abs(value) < eps) {
            value = 0.0
        }
    }
}

@DimensionClassifier("J/mBK") interface ForgeFluidSpecificHeatCapacity
val JOULE_PER_MILLIBUCKET_KELVIN = standardScale<ForgeFluidSpecificHeatCapacity>()

@DimensionClassifier("J/mB") interface ForgeFluidEnergyDensity
val JOULE_PER_MILLIBUCKET = standardScale<ForgeFluidEnergyDensity>()

@DimensionClassifier("kg/mB") interface ForgeFluidDensity
val KILOGRAM_PER_MILLIBUCKET = standardScale<ForgeFluidDensity>()

fun easeInOutCubic(t: Double) =  if (t < 0.5) {
    4.0 * t * t * t
} else {
    1.0 - (-2.0 * t + 2.0).pow(3) / 2.0
}

@Suppress("NOTHING_TO_INLINE")
class BitSparseVoxelOctree(val log: Int) {
    private val nodes = Long2ShortOpenHashMap()

    init {
        require(log in 1..<16) {
            DEBUGGER_BREAK("Invalid SVO log $log")
        }

        nodes.defaultReturnValue(INVALID_BIT.toShort())
        nodes.putUnique(ROOT_CODE, 0)
    }

    val edgeSize = 1 shl log
    val min = Vector3di.zero
    val max = min + Vector3di(edgeSize)

    val bounds = BoundingBox3d(
        Vector3d(min.x, min.y, min.z),
        Vector3d(max.x, max.y, max.z)
    )

    inline fun isWithinBounds(tileX: Int, tileY: Int, tileZ: Int) =
        tileX >= min.x && tileX < max.x &&
            tileY >= min.y && tileY < max.y &&
            tileZ >= min.z && tileZ < max.z

    private inline fun fetchNode(lc: Long) : Int {
        val value = nodes.get(lc).toInt()

        check((value and INVALID_BIT) == 0) {
            "Tried to fetch non-existent node $lc"
        }

        return value
    }

    private val descentStack = DescentStack()
    private val raycastStack = RaycastStack()
    private val temporaryRaycastStack1 = RaycastStack(8)

    var version = 0
        private set

    fun clear() {
        nodes.clear()
        nodes.putUnique(ROOT_CODE, 0)
    }

    //#region Insertion

    private inline fun insertCore(tileX: Int, tileY: Int, tileZ: Int) : Boolean {
        var targetX = tileX
        var targetY = tileY
        var targetZ = tileZ

        var lcNode = ROOT_CODE
        var log = log

        while (true) {
            val node = fetchNode(lcNode)

            if(getIsFilled(node)) {
                // Node is filled, so we can't insert anything.
                return false
            }

            val lsz = 1 shl (log - 1)

            val cellX = targetX / lsz
            val cellY = targetY / lsz
            val cellZ = targetZ / lsz

            targetX -= cellX * lsz
            targetY -= cellY * lsz
            targetZ -= cellZ * lsz

            val octant = cellX or (cellY shl 1) or (cellZ shl 2)

            descentStack.pushFrame(lcNode, log, octant)

            if(log == 1) {
                if(hasChild(node, octant)) {
                    // Already set, so we can't insert here.
                    return false
                }

                nodes[lcNode] = activateChild(node, octant).toShort()
                return true
            }

            val lcChild = childCode(lcNode, octant)

            if(!hasChild(node, octant)) {
                nodes[lcNode] = activateChild(node, octant).toShort()
                nodes.put(lcChild, 0)
            }

            lcNode = lcChild
            --log
        }
    }

    private inline fun fill() {
        while(descentStack.frameCount > 0) {
            descentStack.popFrame()

            val lcNode = descentStack.lc
            val node = fetchNode(lcNode)

            if(node != ALL_CHILDREN_NOT_FILLED_NODE) {
                return
            }

            if(descentStack.log > 1) {
                repeat(8) {
                    val child = fetchNode(childCode(lcNode, it))

                    if(!getIsFilled(child)) {
                        return // Can't solidify the parent node, which also means we can't solidify further up.
                    }
                }

                repeat(8) {
                    nodes.remove(childCode(lcNode, it))
                }
            }

            nodes[lcNode] = FILL_BIT.toShort()
        }
    }

    fun insert(tileX: Int, tileY: Int, tileZ: Int) : Boolean {
        require(isWithinBounds(tileX, tileY, tileZ)) {
            DEBUGGER_BREAK("The tile to insert is not within the bounds of the SVO")
        }

        val result = insertCore(tileX, tileY, tileZ)

        if(result) {
            fill()
            ++version
        }

        descentStack.clear()

        return result
    }

    //#endregion

    //#region Removal

    private inline fun removeCore(tileX: Int, tileY: Int, tileZ: Int) : Boolean {
        var targetX = tileX
        var targetY = tileY
        var targetZ = tileZ

        var lcNode = ROOT_CODE
        var log = log

        while (true) {
            var node = fetchNode(lcNode)

            val lsz = 1 shl (log - 1)

            val cellX = targetX / lsz
            val cellY = targetY / lsz
            val cellZ = targetZ / lsz

            targetX -= cellX * lsz
            targetY -= cellY * lsz
            targetZ -= cellZ * lsz

            val octant = cellX or (cellY shl 1) or (cellZ shl 2)

            descentStack.pushFrame(lcNode, log, octant)

            if(getIsFilled(node)) {
                node = ALL_CHILDREN_NOT_FILLED_NODE

                if(log > 1) {
                    // Split into 8 nodes:
                    repeat(8) {
                        nodes.put(
                            childCode(lcNode, it),
                            FILL_BIT.toShort()
                        )
                    }
                }
            }
            else if(!hasChild(node, octant)) {
                return false
            }

            if(log == 1) {
                nodes[lcNode] = resetChild(node, octant).toShort()
                return true
            }

            nodes[lcNode] = node.toShort()
            lcNode = childCode(lcNode, octant)
            --log
        }
    }

    private inline fun trim() {
        while (descentStack.frameCount > 1) {
            descentStack.popFrame()

            val lcNode = descentStack.lc
            val node = fetchNode(lcNode)

            if(node != 0) {
                // Not empty.
                return
            }

            descentStack.peekFrame()
            val parentLc = descentStack.lc
            val parentNode = nodes[parentLc].toInt()

            nodes[parentLc] = resetChild(parentNode, descentStack.octant).toShort()
            nodes.remove(lcNode)
        }
    }

    fun remove(tileX: Int, tileY: Int, tileZ: Int) : Boolean {
        require(isWithinBounds(tileX, tileY, tileZ)) {
            DEBUGGER_BREAK("The tile to remove is not within the bounds of the SVO")
        }

        val result = removeCore(tileX, tileY, tileZ)

        if(result) {
            trim()
            ++version
        }

        descentStack.clear()

        return result
    }

    //#endregion

    //#region Query

    fun contains(tileX: Int, tileY: Int, tileZ: Int) : Boolean {
        if(!isWithinBounds(tileX, tileY, tileZ)) {
            return false
        }

        var targetX = tileX
        var targetY = tileY
        var targetZ = tileZ

        var lcNode = ROOT_CODE
        var log = log

        while (true) {
            val node = fetchNode(lcNode)

            if(getIsFilled(node)) {
                return true
            }

            val lsz = 1 shl (log - 1)

            val cellX = targetX / lsz
            val cellY = targetY / lsz
            val cellZ = targetZ / lsz

            targetX -= cellX * lsz
            targetY -= cellY * lsz
            targetZ -= cellZ * lsz

            val octant = cellX or (cellY shl 1) or (cellZ shl 2)

            if(!hasChild(node, octant)) {
                return false
            }

            if(log == 1) {
                return true
            }

            lcNode = childCode(lcNode, octant)
            --log
        }
    }

    private inline fun lineTest(
        originX: Double, originY: Double, originZ: Double,
        rDirX: Double, rDirY: Double, rDirZ: Double,
        boundsMinX: Double, boundsMinY: Double, boundsMinZ: Double,
        boundsMaxX: Double, boundsMaxY: Double, boundsMaxZ: Double,
        tNear: Double, tFar: Double
    ): Pair<Double, Double>? {
        var tMin = tNear
        var tMax = tFar

        // X Slab
        var t1 = (boundsMinX - originX) * rDirX
        var t2 = (boundsMaxX - originX) * rDirX
        tMin = max(tMin, min(t1, t2))
        tMax = min(tMax, max(t1, t2))

        if (tMin > tMax){
            return null
        }

        // Y Slab
        t1 = (boundsMinY - originY) * rDirY
        t2 = (boundsMaxY - originY) * rDirY
        tMin = max(tMin, min(t1, t2))
        tMax = min(tMax, max(t1, t2))

        if (tMin > tMax) {
            return null
        }

        // Z Slab
        t1 = (boundsMinZ - originZ) * rDirZ
        t2 = (boundsMaxZ - originZ) * rDirZ
        tMin = max(tMin, min(t1, t2))
        tMax = min(tMax, max(t1, t2))

        if (tMin > tMax) {
            return null
        }

        return Pair(tMin, tMax)
    }

    // DDA might be faster, but it's difficult for octrees, I don't have time

    fun raycastIntersectsOrderedDepthFirst(ray: Ray3d, maxDistance: Double): Boolean {
        val originX = ray.origin.x
        val originY = ray.origin.y
        val originZ = ray.origin.z

        val rDirX = 1.0 / ray.direction.x
        val rDirY = 1.0 / ray.direction.y
        val rDirZ = 1.0 / ray.direction.z

        val rootIntersection = lineTest(
            originX, originY, originZ,
            rDirX, rDirY, rDirZ,
            bounds.min.x, bounds.min.y, bounds.min.z,
            bounds.max.x, bounds.max.y, bounds.max.z,
            0.0, maxDistance
        ) ?: return false

        val raycastStack = raycastStack
        raycastStack.clear()

        val temporaryRaycastStack1 = temporaryRaycastStack1
        temporaryRaycastStack1.clear()

        raycastStack.push(
            ROOT_CODE, log,
            rootIntersection.first, rootIntersection.second,
            bounds.min.x, bounds.min.y, bounds.min.z,
            bounds.max.x, bounds.max.y, bounds.max.z
        )

        val sortedIndices = IntArray(8)
        while (raycastStack.frameCount > 0) {
            raycastStack.pop()

            val node = fetchNode(raycastStack.lc)
            val log = raycastStack.log

            if (getIsFilled(node)) {
                return true
            }

            val parentBoundsMinX = raycastStack.boundsMinX
            val parentBoundsMinY = raycastStack.boundsMinY
            val parentBoundsMinZ = raycastStack.boundsMinZ
            val parentBoundsMaxX = raycastStack.boundsMaxX
            val parentBoundsMaxY = raycastStack.boundsMaxY
            val parentBoundsMaxZ = raycastStack.boundsMaxZ

            val parentMidX = 0.5 * (parentBoundsMinX + parentBoundsMaxX)
            val parentMidY = 0.5 * (parentBoundsMinY + parentBoundsMaxY)
            val parentMidZ = 0.5 * (parentBoundsMinZ + parentBoundsMaxZ)

            var tempChildCount = 0
            for (childIdx in 0 until 8) {
                if (!hasChild(node, childIdx)) {
                    continue
                }

                val childBoundsMinX = if ((childIdx and 1) != 0) parentMidX else parentBoundsMinX
                val childBoundsMinY = if ((childIdx and 2) != 0) parentMidY else parentBoundsMinY
                val childBoundsMinZ = if ((childIdx and 4) != 0) parentMidZ else parentBoundsMinZ
                val childBoundsMaxX = if ((childIdx and 1) != 0) parentBoundsMaxX else parentMidX
                val childBoundsMaxY = if ((childIdx and 2) != 0) parentBoundsMaxY else parentMidY
                val childBoundsMaxZ = if ((childIdx and 4) != 0) parentBoundsMaxZ else parentMidZ

                val childIntersection = lineTest(
                    originX, originY, originZ,
                    rDirX, rDirY, rDirZ,
                    childBoundsMinX, childBoundsMinY, childBoundsMinZ,
                    childBoundsMaxX, childBoundsMaxY, childBoundsMaxZ,
                    raycastStack.tMin, raycastStack.tMax
                ) ?: continue

                if(log == 1) {
                    return true
                }

                val (childTMin, childTMax) = childIntersection

                temporaryRaycastStack1.push(
                    childCode(raycastStack.lc, childIdx),
                    log - 1,
                    childTMin,
                    childTMax,
                    childBoundsMinX, childBoundsMinY, childBoundsMinZ,
                    childBoundsMaxX, childBoundsMaxY, childBoundsMaxZ
                )

                sortedIndices[tempChildCount] = tempChildCount
                tempChildCount++
            }

            val tMinArray = temporaryRaycastStack1.tMinArray

            // Insertion sort to order the indices:
            for (i in 1 until tempChildCount) {
                val keyIndex = sortedIndices[i]
                val keyTMin = tMinArray[keyIndex]
                var j = i - 1

                while (j >= 0 && tMinArray[sortedIndices[j]] > keyTMin) {
                    sortedIndices[j + 1] = sortedIndices[j]
                    j--
                }

                sortedIndices[j + 1] = keyIndex
            }

            // Copy ordered children to stack:
            for (i in (tempChildCount - 1) downTo 0) {
                val idxToPush = sortedIndices[i]

                raycastStack.push(
                    temporaryRaycastStack1.lcArray[idxToPush],
                    temporaryRaycastStack1.logArray[idxToPush],
                    temporaryRaycastStack1.tMinArray[idxToPush],
                    temporaryRaycastStack1.tMaxArray[idxToPush],
                    temporaryRaycastStack1.bMinXArray[idxToPush],
                    temporaryRaycastStack1.bMinYArray[idxToPush],
                    temporaryRaycastStack1.bMinZArray[idxToPush],
                    temporaryRaycastStack1.bMaxXArray[idxToPush],
                    temporaryRaycastStack1.bMaxYArray[idxToPush],
                    temporaryRaycastStack1.bMaxZArray[idxToPush]
                )
            }

            temporaryRaycastStack1.clear()
        }

        return false
    }

    //#endregion

    //#region Traversal

    fun traverseNodes(consumer: (node: Int, lc: Long, position: Vector3di, log: Int) -> Unit) {
        class Frame(val lc: Long, val position: Vector3di, val log: Int)

        val stack = ArrayList<Frame>()

        stack.add(Frame(
            ROOT_CODE,
            Vector3di.zero,
            log
        ))

        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val lcNode = frame.lc
            val node = fetchNode(lcNode)

            consumer(
                node,
                lcNode,
                frame.position,
                frame.log
            )

            if(frame.log == 1) {
                repeat(8) {
                    if(hasChild(node, it)) {
                        consumer(
                            FILL_BIT,
                            childCode(lcNode, it),
                            frame.position + octantPositionIncrement(it, frame.log),
                            0
                        )
                    }
                }
            }
            else {
                repeat(8) {
                    if(hasChild(node, it)) {
                        stack.add(Frame(
                            childCode(lcNode, it),
                            frame.position + octantPositionIncrement(it, frame.log),
                            frame.log - 1
                        ))
                    }
                }
            }
        }
    }

    //#endregion

    private class DescentStack(val capacity: Int = 32) {
        val array = IntArray(4 * capacity)

        var frameCount = 0
            private set

        var lc = 0L
        var log = 0
        var octant = 0

        fun clear() {
            frameCount = 0
        }

        inline fun pushFrame(lc: Long, log: Int, octant: Int) {
            check(frameCount < capacity) {
                "Overflowing decent stack"
            }

            val offset = frameCount * 4
            array[offset + 0] = (lc shr 32).toInt()
            array[offset + 1] = lc.toInt()
            array[offset + 2] = log
            array[offset + 3] = octant
            frameCount++
        }

        inline fun peekFrame() {
            check(frameCount > 0) {
                "Ran off the start of the descent stack"
            }

            val offset = (frameCount - 1) * 4

            val high = array[offset + 0].toLong() shl 32
            val low = array[offset + 1].toLong() and 0xFFFFFFFFL
            lc = high or low
            log = array[offset + 2]
            octant = array[offset + 3]
        }

        inline fun popFrame() {
            peekFrame()
            --frameCount
        }
    }

    private class RaycastStack(val capacity: Int = 256) {
        val lcArray = LongArray(capacity)
        val logArray = IntArray(capacity)
        val tMinArray = DoubleArray(capacity)
        val tMaxArray = DoubleArray(capacity)
        val bMinXArray = DoubleArray(capacity)
        val bMinYArray = DoubleArray(capacity)
        val bMinZArray = DoubleArray(capacity)
        val bMaxXArray = DoubleArray(capacity)
        val bMaxYArray = DoubleArray(capacity)
        val bMaxZArray = DoubleArray(capacity)

        var frameCount = 0
            private set

        fun clear() {
            frameCount = 0
        }

        inline fun push(
            lc: Long, log: Int,
            tMin: Double, tMax: Double,
            boundsMinX: Double, boundsMinY: Double, boundsMinZ: Double,
            boundsMaxX: Double, boundsMaxY: Double, boundsMaxZ: Double
        ) {
            check(frameCount < capacity) {
                "Overflowing traversal stack"
            }

            lcArray[frameCount] = lc
            logArray[frameCount] = log
            tMinArray[frameCount] = tMin
            tMaxArray[frameCount] = tMax
            bMinXArray[frameCount] = boundsMinX
            bMinYArray[frameCount] = boundsMinY
            bMinZArray[frameCount] = boundsMinZ
            bMaxXArray[frameCount] = boundsMaxX
            bMaxYArray[frameCount] = boundsMaxY
            bMaxZArray[frameCount] = boundsMaxZ
            frameCount++
        }

        var lc = 0L
        var log = 0
        var tMin = 0.0
        var tMax = 0.0
        var boundsMinX = 0.0
        var boundsMinY = 0.0
        var boundsMinZ = 0.0
        var boundsMaxX = 0.0
        var boundsMaxY = 0.0
        var boundsMaxZ = 0.0

        inline fun pop() {
            check(frameCount > 0) {
                "Ran off the start of the traversal stack"
            }

            frameCount--
            lc = lcArray[frameCount]
            log = logArray[frameCount]
            tMin = tMinArray[frameCount]
            tMax = tMaxArray[frameCount]
            boundsMinX = bMinXArray[frameCount]
            boundsMinY = bMinYArray[frameCount]
            boundsMinZ = bMinZArray[frameCount]
            boundsMaxX = bMaxXArray[frameCount]
            boundsMaxY = bMaxYArray[frameCount]
            boundsMaxZ = bMaxZArray[frameCount]
        }
    }

    companion object {
        const val INVALID_BIT = 1 shl 9
        const val FILL_BIT = 1 shl 8
        const val ALL_CHILDREN_NOT_FILLED_NODE = 0xFF
        const val ROOT_CODE = 1L

        inline fun getIsFilled(data: Int) = (data and FILL_BIT) > 0
        inline fun hasChild(data: Int, childIdx: Int) = ((data shr childIdx) and 1) > 0
        inline fun activateChild(data: Int, childIdx: Int) = data or (1 shl childIdx)
        inline fun resetChild(data: Int, childIdx: Int) = data and (1 shl childIdx).inv()
        inline fun childCode(lcParent: Long, octant: Int) = (lcParent shl 3) or octant.toLong()

        inline fun octantPositionIncrement(octant: Int, parentLog: Int) : Vector3di {
            val k = 1 shl (parentLog - 1)
            return Vector3di(
                (octant and 1) * k,
                ((octant shr 1) and 1) * k,
                ((octant shr 2) and 1) * k
            )
        }
    }
}

private const val LIMIT_LAMBDA_MULTIPLIER = 5.0

// The bias will have some trouble with this, but it's fine (we are breaking the devices):

const val PLUS = 0
const val MINUS = 1

fun Port.offerExternal() = this.positive
fun Port.offerInternal() = this.negative

fun KineticMono.setSafeTorque(threshold: Quantity<Torque>) {
    val limit = LIMIT_LAMBDA_MULTIPLIER * (!threshold * CellGraph.DT)
    this.extension.maxLambda = limit
}

fun KineticDouble.setSafeTorque(threshold: Quantity<Torque>) {
    val limit = LIMIT_LAMBDA_MULTIPLIER * (!threshold * CellGraph.DT)
    this.e1.maxLambda = limit
    this.e2.maxLambda = limit
}

fun KineticTriple.setSafeTorque(threshold: Quantity<Torque>) {
    val limit = LIMIT_LAMBDA_MULTIPLIER * (!threshold * CellGraph.DT)
    this.e1.maxLambda = limit
    this.e2.maxLambda = limit
    this.e3.maxLambda = limit
}

fun KineticDouble.minus() = this.e1
fun KineticDouble.plus() = this.e2

data class NodeFrictionDescription(
    val damping: Double,
    val coulombFriction: Quantity<Torque>,
    val staticThreshold: Quantity<Torque>,
    val velocityEps: Quantity<AngularVelocity> = Quantity(0.01, RADIAN_PER_SECOND)
) {
    fun applyTo(shaft: FrictionKineticNode) {
        shaft.viscousDamping = damping
        shaft.coulombFriction = !coulombFriction
        shaft.staticFriction = !staticThreshold
        shaft.velocityEps = !velocityEps
    }
}

data class FrictionNodeDescription(val inertia: Quantity<Inertia>, val frictionDescription: NodeFrictionDescription) {
    constructor(
        inertia: Quantity<Inertia>,
        damping: Double,
        coulombFriction: Quantity<Torque>,
        staticThreshold: Quantity<Torque>,
        velocityEps: Quantity<AngularVelocity> = Quantity(0.01, RADIAN_PER_SECOND)
    ) : this(inertia, NodeFrictionDescription(damping, coulombFriction, staticThreshold, velocityEps))

    fun applyTo(shaft: FrictionKineticNode) {
        shaft.inertia = !inertia
        frictionDescription.applyTo(shaft)
    }
}

class AveragingList(private val sampleCount: Int) {
    init {
        require(sampleCount > 0) {
            "Invalid sample count $sampleCount"
        }
    }

    private val samples = DoubleArray(sampleCount)
    private var index = 0
    private var filled = 0
    private var sum = 0.0

    fun addSample(value: Double) {
        if (filled < sampleCount) {
            filled++
        } else {
            sum -= samples[index] // remove old value
        }

        samples[index] = value
        sum += value
        index = (index + 1) % sampleCount
    }

    fun calculate(): Double {
        return if (filled == 0) 0.0 else sum / filled
    }
}

class Average {
    var count = 0
    var value = 0.0

    fun add(x: Double) {
        value += (x - value) / (count + 1)
        ++count
    }

    fun add(x: Int) {
        add(x.toDouble())
    }

    fun reset() {
        count = 0
        value = 0.0
    }

    companion object {
        fun combine(a: Average, b: Average): Average {
            val count = a.count + b.count
            val kA = a.count / count
            val kB = b.count / count

            val result = Average()

            result.count = count
            result.value = a.value * kA + b.value * kB

            return result
        }
    }
}

class Average3d {
    var count = 0
    var averageX = 0.0
    var averageY = 0.0
    var averageZ = 0.0

    var average: Vector3d
        get() = Vector3d(averageX, averageY, averageZ)
        set(value) {
            averageX = value.x
            averageY = value.y
            averageZ = value.z
        }

    fun add(x: Double, y: Double, z: Double) {
        averageX += (x - averageX) / (count + 1)
        averageY += (y - averageY) / (count + 1)
        averageZ += (z - averageZ) / (count + 1)
        ++count
    }

    fun add(x: Int, y: Int, z: Int) {
        add(x.toDouble(), y.toDouble(), z.toDouble())
    }

    fun add(value: Vector3d) {
        add(value.x, value.y, value.z)
    }

    fun add(value: Vec3i) {
        add(value.x, value.y, value.z)
    }

    fun reset() {
        count = 0
        averageX = 0.0
        averageY = 0.0
        averageZ = 0.0
    }

    companion object {
        fun combine(a: Average3d, b: Average3d): Average3d {
            val count = a.count + b.count
            val kA = a.count / count
            val kB = b.count / count

            val result = Average3d()

            result.count = count
            result.averageX = a.averageX * kA + b.averageX * kB
            result.averageY = a.averageY * kA + b.averageY * kB
            result.averageZ = a.averageZ * kA + b.averageZ * kB

            return result
        }
    }
}

/**
 * [Lazy] which can be reset (the value is discarded).
 * */
interface LazyResettable<T> : Lazy<T> {
    /**
     * Resets the stored value, if present.
     * @return True if a value was present. Otherwise, false.
     * */
    fun reset() : Boolean
}

/**
 * Non-thread safe implementation of [LazyResettable].
 * */
class LinearLazyResettable<T>(private val new: () -> T) : LazyResettable<T> {
    private var instance: T? = null

    override fun reset(): Boolean {
        val instance = this.instance
        this.instance = null
        return instance != null
    }

    override val value: T
        get() {
            val instance = this.instance

            if(instance != null) {
                return instance
            }

            val newValue = new()
            this.instance = newValue
            return newValue
        }

    override fun isInitialized() = instance != null
}

/**
 * CSV file with purely numeric data.
 * @param headers The headers of each column.
 * @param entries Each row in the file, as a vector.
 * */
class NumericCsvFile(val headers: List<String>, val entries: List<ArrayKDVectorD>) {
    companion object {
        /**
         * Parses a [NumericCsvFile].
         * The header row **must** be present.
         * */
        fun parse(csv: String): NumericCsvFile {
            val lines = csv.lines()

            val headers = ArrayList<String>()

            lines[0].split(',').forEach { headers.add(it) }

            val results = ArrayList<ArrayKDVectorD>()

            for (i in 1 until lines.size) {
                val line = lines[i]

                if (line.isEmpty()) {
                    continue
                }

                val tokens = line.split(',').map { it.toDoubleOrNull() ?: error("Could not parse double $it") }

                if (tokens.size != headers.size) {
                    error("Mismatched CSV token count")
                }

                results.add(ArrayKDVectorD(tokens.toDoubleArray()))
            }

            return NumericCsvFile(headers, results)
        }
    }
}

/**
 * Thread safe collection of runnable handlers.
 * */
class NotificationBus(val validateOnAssign: Runnable? = null) {
    private val handlers = CopyOnWriteArrayList<Runnable>()

    /**
     * Adds a handler. **Does not check for duplicate adds!**
     * */
    operator fun plusAssign(handler: Runnable) {
        validateOnAssign?.run()
        handlers.add(handler)
    }

    /**
     * Removes a handler.
     * */
    operator fun minusAssign(handler: Runnable) {
        handlers.remove(handler)
    }

    fun run() {
        handlers.forEach {
            it.run()
        }
    }
}

inline operator fun<reified TEvent : Event> EventSource.plusAssign(handler: EventHandler<TEvent>) {
    registerHandler(TEvent::class) {
        handler.handle(it as TEvent)
    }
}

class SortedUUIDPair private constructor(val a: UUID, val b: UUID) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SortedUUIDPair

        if (a != other.a) return false
        if (b != other.b) return false

        return true
    }

    override fun hashCode(): Int {
        var result = a.hashCode()
        result = 31 * result + b.hashCode()
        return result
    }

    companion object {
        fun write(pair: SortedUUIDPair, buffer: ByteBuffer) {
            val a = pair.a
            buffer.putLong(a.mostSignificantBits)
            buffer.putLong(a.leastSignificantBits)

            val b = pair.b
            buffer.putLong(b.mostSignificantBits)
            buffer.putLong(b.leastSignificantBits)
        }

        fun read(buffer: ByteBuffer) : SortedUUIDPair {
            val a = UUID(buffer.getLong(), buffer.getLong())
            val b = UUID(buffer.getLong(), buffer.getLong())

            return SortedUUIDPair(a, b)
        }

        fun create(a: UUID, b: UUID) : SortedUUIDPair {
            require(a != b) {
                "Duplicate UUID pair"
            }

            return if(a < b) {
                SortedUUIDPair(a, b)
            }
            else {
                SortedUUIDPair(b, a)
            }
        }
    }
}

/**
 * Pool of reusable objects. Thread safety is up to the implementation.
 * */
interface ObjectPool<T> {
    /**
     * Gets an object from the pool or allocates one, if the pool is empty.
     * */
    fun get(): T

    /**
     * Releases an object back into the pool (if the policy allows it), or discards it.
     * */
    fun release(obj: T)
}

/**
 * Allocates an object from the pool for use in the scope [block].
 * Storing a reference to the object outside of [block] is not allowed.
 * */
@OptIn(ExperimentalContracts::class)
inline fun<reified T> ObjectPool<T>.using(block: (obj: T) -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val obj = this.get()

    try {
        block.invoke(obj)
    }
    finally {
        this.release(obj)
    }
}

/**
 * Policy used by [ObjectPool] to allocate new objects and determine if they are allowed back into the pool.
 * */
interface PooledObjectPolicy<T> {
    /**
     * Called to create a new instance of [T].
     * */
    fun create(): T

    /**
     * Called when [obj] is returned to the pool, to clean its state for reuse.
     * @return True if [obj] should be accepted back into the pool. If false, then [obj] will not be added back and will be collected by GC.
     * */
    fun release(obj: T): Boolean
}

/**
 * Non-thread-safe object pool, using an [ArrayList] as backing storage.
 * @param maximumRetained The maximum number of objects to keep in the pool. If [release] is called and the pool has reached this number of objects, then the object will be discarded.
 * */
class LinearObjectPool<T>(private val policy: PooledObjectPolicy<T>, val maximumRetained: Int) : ObjectPool<T> {
    init {
        require(maximumRetained > 0) {
            "Invalid pool size $maximumRetained"
        }
    }

    private val items = ArrayList<T>()

    override fun get(): T {
        if(items.isEmpty()) {
            return policy.create()
        }

        return items.removeLast()
    }

    override fun release(obj: T) {
        if(!policy.release(obj)) {
            return
        }

        if(items.size == maximumRetained) {
            return
        }

        items.add(obj)
    }
}

/**
 * Simple thread-safe object pool implemented as an atomic stack.
 * @param maximumRetained The maximum number of objects to keep in the pool. If [release] is called and the pool has reached this number of objects, then the object will be discarded.
 * */
class LocklessAtomicObjectPool<T>(val policy: PooledObjectPolicy<T>, val maximumRetained: Int) : ObjectPool<T> {
    // Could we use a TLS [LinearObjectPool] for these nodes?
    private class Node<T>(val value: T) {
        var next: Node<T>? = null
    }

    private val head = AtomicReference<Node<T>?>(null)
    private val retained = AtomicInteger(0)

    override fun get(): T {
        while (true) {
            val h = head.get()
                ?: return policy.create()

            val next = h.next

            if (head.compareAndSet(h, next)) {
                retained.decrementAndGet()
                h.next = null

                return h.value
            }
        }
    }

    override fun release(obj: T) {
        if (!policy.release(obj)) {
            return
        }

        while (true) {
            val count = retained.get()

            if (count >= maximumRetained) {
                return
            }

            if (retained.compareAndSet(count, count + 1)) {
                break
            }
        }

        val node = Node(obj)

        while (true) {
            val h = head.get()

            node.next = h

            if (head.compareAndSet(h, node)) {
                return
            }
        }
    }
}
