@file:Suppress("MemberVisibilityCanBePrivate")

package org.eln2.mc.client.render

import com.jozufozu.flywheel.util.Color
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import org.ageseries.libage.mathematics.BoundingBox3d
import org.ageseries.libage.mathematics.Vector3d
import org.ageseries.libage.utils.Stopwatch
import org.eln2.mc.ClientOnly
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.extensions.toVec3

@ClientOnly
object DebugVisualizer {
    private val elements = HashSet<RenderElement>()
    private val obj = Any()

    fun interface Remover {
        fun shouldRemove() : Boolean
    }

    abstract class RenderElement {
        protected val removers = ArrayList<Remover>()
        protected val obj = Any()

        var removed = false
            protected set

        open fun remove() {
            removed = true
        }

        abstract fun render(
            pPoseStack: PoseStack,
            pBufferSource: MultiBufferSource.BufferSource,
            pCamX: Double,
            pCamY: Double,
            pCamZ: Double,
            level: ClientLevel,
            player: LocalPlayer,
        )

        open fun shouldRemove() : Boolean {
            if(removed) {
                return true
            }

            synchronized(obj) {
                return removers.any {
                    it.shouldRemove()
                }
            }
        }

        open fun withRemover(remover: Remover) : RenderElement {
            synchronized(obj) {
                removers.add(remover)
            }

            return this
        }

        open fun removeAfter(time: Double) : RenderElement {
            val stopwatch = Stopwatch()

            return withRemover {
                stopwatch.total > time
            }
        }

        open fun withinScopeOf(part: Part<*>) = withRemover(part::isRemoved)
    }

    private class CompositeRenderElement : RenderElement() {
        private val children = ArrayList<RenderElement>()

        fun with(child: RenderElement) : CompositeRenderElement {
            synchronized(obj) {
                children.add(child)
            }

            return this
        }

        override fun shouldRemove(): Boolean {
            synchronized(obj) {
                if(super.shouldRemove()) {
                    return true
                }

                return children.any { it.shouldRemove() }
            }
        }

        override fun render(
            pPoseStack: PoseStack,
            pBufferSource: MultiBufferSource.BufferSource,
            pCamX: Double,
            pCamY: Double,
            pCamZ: Double,
            level: ClientLevel,
            player: LocalPlayer,
        ) {
            synchronized(obj) {
                children.forEach {
                    it.render(pPoseStack, pBufferSource, pCamX, pCamY, pCamZ, level, player)
                }
            }
        }

    }

    private class LineBox(val aabb: AABB, val color: Color, val thickness: Float) : RenderElement() {
        override fun render(
            pPoseStack: PoseStack,
            pBufferSource: MultiBufferSource.BufferSource,
            pCamX: Double,
            pCamY: Double,
            pCamZ: Double,
            level: ClientLevel,
            player: LocalPlayer,
        ) {
            RenderSystem.lineWidth(thickness)

            LevelRenderer.renderLineBox(
                pPoseStack,
                pBufferSource.getBuffer(RenderType.lines()),
                aabb.minX - pCamX,
                aabb.minY - pCamY,
                aabb.minZ - pCamZ,
                aabb.maxX - pCamX,
                aabb.maxY - pCamY,
                aabb.maxZ - pCamZ,
                color.redAsFloat,
                color.greenAsFloat,
                color.blueAsFloat,
                color.alphaAsFloat,
                color.redAsFloat,
                color.greenAsFloat,
                color.blueAsFloat
            )
        }
    }

    @JvmStatic
    fun render(
        pPoseStack: PoseStack,
        pBufferSource: MultiBufferSource.BufferSource,
        pCamX: Double,
        pCamY: Double,
        pCamZ: Double,
    ) {
        val level = Minecraft.getInstance().level ?: return
        val player = Minecraft.getInstance().player ?: return

        synchronized(obj) {
            val removed = HashSet<RenderElement>()

            elements.forEach {
                if(it.shouldRemove()) {
                    removed.add(it)
                }
            }

            removed.forEach {
                elements.remove(it)
            }

            elements.forEach {
                it.render(pPoseStack, pBufferSource, pCamX, pCamY, pCamZ, level, player)
            }
        }
    }

    private fun add(element: RenderElement) : RenderElement {
        synchronized(obj) {
            check(elements.add(element))
        }

        return element
    }

    fun createCompositionOf(vararg elements: RenderElement) : RenderElement {
        val composite = CompositeRenderElement()

        elements.forEach {
            composite.with(it)
        }

        return composite
    }

    fun createLineBox(vararg aabbs: AABB, color: Color = Color.WHITE, thickness: Float = 5.0f) =
        createCompositionOf(*aabbs.map { LineBox(it, color, thickness) }.toTypedArray())

    fun createLineBox(vararg aabbs: BoundingBox3d, color: Color = Color.WHITE, thickness: Float = 5.0f) =
        createCompositionOf(*aabbs.map { LineBox(AABB(it.min.toVec3(), it.max.toVec3()), color, thickness) }.toTypedArray())

    fun createDirection(origin: Vector3d, direction: Direction, size: Double = 0.05, color: Color = Color.WHITE, thickness: Float = 5.0f) : RenderElement {
        val t = size / 2.0

        val box = when(direction) {
            Direction.DOWN -> BoundingBox3d(
                origin + Vector3d(-t, -1.0, -t),
                origin + Vector3d(t, 0.0, t)
            )

            Direction.UP -> BoundingBox3d(
                origin + Vector3d(-t, 0.0, -t),
                origin + Vector3d(t, 1.0, t)
            )

            Direction.NORTH -> BoundingBox3d(
                origin + Vector3d(-t, -t, -1.0),
                origin + Vector3d(t, t, 0.0)
            )

            Direction.SOUTH -> BoundingBox3d(
                origin + Vector3d(-t, -t, 0.0),
                origin + Vector3d(t, t, 1.0)
            )

            Direction.WEST -> BoundingBox3d(
                origin + Vector3d(-1.0, -t, -t),
                origin + Vector3d(0.0, t, t)
            )

            Direction.EAST -> BoundingBox3d(
                origin + Vector3d(0.0, -t, -t),
                origin + Vector3d(1.0, t, t)
            )
        }

        return createLineBox(box, color = color, thickness = thickness)
    }

    fun createPartFrame(part: Part<*>) = createCompositionOf(
        createDirection(part.placement.mountingPointWorld, part.placement.positiveX, color = Color(1f, 0f, 0f, 0.8f)),
        createDirection(part.placement.mountingPointWorld, part.placement.positiveY, color = Color(0f, 1f, 0f, 0.8f)),
        createDirection(part.placement.mountingPointWorld, part.placement.positiveZ, color = Color(0f, 0f, 1f, 0.8f))
    ).withinScopeOf(part)

    fun createPartBounds(part: Part<*>) =
        createLineBox(part.worldBoundingBox, thickness = 1.0f).withinScopeOf(part)

    fun compositionOf(vararg elements: RenderElement) = add(createCompositionOf(*elements))

    fun lineBox(vararg aabbs: AABB, color: Color = Color.WHITE, thickness: Float = 5.0f) =
        add(createLineBox(*aabbs, color = color, thickness = thickness))

    fun lineBox(vararg aabbs: BoundingBox3d, color: Color = Color.WHITE, thickness: Float = 5.0f) =
        add(createLineBox(*aabbs, color = color, thickness = thickness))

    fun direction(origin: Vector3d, direction: Direction, size: Double = 0.05, color: Color = Color.WHITE, thickness: Float = 5.0f) =
        add(createDirection(origin, direction, size, color, thickness))

    fun partFrame(part: Part<*>) = add(createPartFrame(part))

    fun partBounds(part: Part<*>) = add(createPartBounds(part))

    fun clear() {
        synchronized(obj) {
            elements.clear()
        }
    }
}
