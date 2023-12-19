package org.eln2.mc

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.common.ForgeHooks
import net.minecraftforge.server.ServerLifecycleHooks
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.*
import org.eln2.mc.common.ForgeEvents
import org.eln2.mc.extensions.minus
import org.eln2.mc.extensions.viewClip
import org.joml.Vector3f
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicInteger
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.system.measureNanoTime

fun randomFloat(min: Float, max: Float) = map(Random.nextFloat(), 0f, 1f, min, max)

fun <T> all(vararg items: T, condition: (T) -> Boolean) = items.asList().all(condition)

val digitRange = '0'..'9'
val subscriptDigitRange = '₀'..'₉'

operator fun CharRange.get(x: Int) = this.elementAt(x)

val Char.isLetter get() = this in 'a'..'z' || this in 'A'..'Z'
val Char.isDigit get() = this in digitRange
val Char.isSubscriptDigit get() = this in subscriptDigitRange
val Char.isDigitOrSubscriptDigit get() = this.isDigit || this.isSubscriptDigit

fun subscriptToDigit(c: Char): Char {
    require(c.isSubscriptDigit) { "$c is not a subscript digit" }
    return digitRange[subscriptDigitRange.indexOf(c)]
}

fun digitToSubscript(c: Char): Char {
    require(c.isDigit) { "$c is not a digit" }
    return subscriptDigitRange[digitRange.indexOf(c)]
}

fun Int.toStringSubscript() = String(this.toString().map { digitToSubscript(it) }.toCharArray())

fun charDigitValue(c: Char): Char {
    if (c.isDigit) return c
    if (c.isSubscriptDigit) return subscriptToDigit(c)
    error("$c is not a digit or subscript digit")
}

@Suppress("UNCHECKED_CAST")
fun <T : BlockEntity?, U> ticker(f: (pLevel: Level, pPos: BlockPos, pState: BlockState, pBlockEntity: U) -> Unit) =
    BlockEntityTicker<T> { level, pos, state, e ->
        f(level, pos, state, e as U)
    }

fun <T> clipScene(entity: LivingEntity, access: ((T) -> AABB), objects: Collection<T>): T? {
    val intersections = LinkedHashMap<Vec3, T>()

    val eyePos = Vec3(entity.x, entity.eyeY, entity.z)

    objects.forEach { obj ->
        val box = access(obj)

        val intersection = box.viewClip(entity)

        if (!intersection.isEmpty) {
            intersections[intersection.get()] = obj
        }
    }

    val entry = intersections.minByOrNull { entry ->
        (eyePos - entry.key).length()
    }

    return entry?.value
}

fun componentMin(a: Vector3f, b: Vector3f): Vector3f {
    return Vector3f(
        min(a.x(), b.x()),
        min(a.y(), b.y()),
        min(a.z(), b.z())
    )
}

fun componentMax(a: Vector3f, b: Vector3f): Vector3f {
    return Vector3f(
        max(a.x(), b.x()),
        max(a.y(), b.y()),
        max(a.z(), b.z())
    )
}

inline fun requireIsOnServerThread(message: () -> String) = require(ServerLifecycleHooks.getCurrentServer().isSameThread) { message() }
fun requireIsOnServerThread() = requireIsOnServerThread { "Requirement failed: not on server thread (${Thread.currentThread()})" }
inline fun requireIsOnRenderThread(message: () -> String) = require(RenderSystem.isOnRenderThread(), message)
fun requireIsOnRenderThread() = requireIsOnRenderThread { "Requirement failed: not on render thread (${Thread.currentThread()})" }

fun<K, V> ConcurrentHashMap<K, V>.atomicRemoveIf(consumer: (Map.Entry<K, V>) -> Boolean) {
    this.entries.forEach { entry ->
        if(consumer(entry)) {
            this.remove(entry.key, entry.value)
        }
    }
}

private fun validateSide(side: Dist) = when(side) {
    Dist.CLIENT -> requireIsOnRenderThread {
        "Accessed client only"
    }
    Dist.DEDICATED_SERVER -> requireIsOnServerThread {
        "Accessed server only"
    }
}
class SidedLazy<T>(factory: () -> T, val side: Dist) {
    private val lazy = lazy(factory)

    fun get() : T {
        validateSide(side)
        return lazy.value
    }

    operator fun invoke() = get()
}

fun<T> clientOnlyHolder(factory: () -> T) = SidedLazy(factory, Dist.CLIENT)
fun<T> serverOnlyHolder(factory: () -> T) = SidedLazy(factory, Dist.DEDICATED_SERVER)

private val UNIQUE_ID_ATOMIC = AtomicInteger()

fun getUniqueId() = UNIQUE_ID_ATOMIC.getAndIncrement()

fun directionByNormal(normal: Vec3i) = Direction.entries.firstOrNull { it.normal == normal }

fun isServerPaused() : Boolean {
    val server = ServerLifecycleHooks.getCurrentServer()

    if(server == null) {
        LOG.fatal("ELN2: SERVER NULL")
        return true
    }

    if(server is IntegratedServer) {
        return server.paused
    }

    val time = ForgeEvents.timeSinceLastTick

    if(time > 1.0) {
        LOG.fatal("ELN2: FOUND ${time.classify()} SINCE LAST SERVER TICK")
        return true
    }

    return false
}
