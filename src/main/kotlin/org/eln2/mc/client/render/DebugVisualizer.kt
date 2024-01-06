@file:Suppress("MemberVisibilityCanBePrivate")

package org.eln2.mc.client.render

import com.jozufozu.flywheel.util.Color
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import org.ageseries.libage.mathematics.geometry.*
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.utils.Stopwatch
import org.eln2.mc.ClientOnly
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.extensions.*
import org.joml.Quaternionf
import kotlin.math.PI

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

    private class LineAABB(val aabb: AABB, val color: Color) : RenderElement() {
        override fun render(
            pPoseStack: PoseStack,
            pBufferSource: MultiBufferSource.BufferSource,
            pCamX: Double,
            pCamY: Double,
            pCamZ: Double,
            level: ClientLevel,
            player: LocalPlayer,
        ) {
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

    private class LineOBB(val obb: OrientedBoundingBox3d, val color: Color) : RenderElement() {
        override fun render(
            pPoseStack: PoseStack,
            pBufferSource: MultiBufferSource.BufferSource,
            pCamX: Double,
            pCamY: Double,
            pCamZ: Double,
            level: ClientLevel,
            player: LocalPlayer,
        ) {
            pPoseStack.pushPose()

            pPoseStack.translate(-pCamX, -pCamY, -pCamZ)
            pPoseStack.translate(obb.transform.translation)
            pPoseStack.mulPose(obb.transform.rotation)

            val min = -obb.halfSize
            val max = +obb.halfSize

            LevelRenderer.renderLineBox(
                pPoseStack,
                pBufferSource.getBuffer(RenderType.lines()),
                min.x,
                min.y,
                min.z,
                max.x,
                max.y,
                max.z,
                color.redAsFloat,
                color.greenAsFloat,
                color.blueAsFloat,
                color.alphaAsFloat,
                color.redAsFloat,
                color.greenAsFloat,
                color.blueAsFloat
            )

            pPoseStack.popPose()
        }

    }

    private class LineCylinder(val cylinder: Cylinder3d, val color: Color) : RenderElement() {
        companion object {
            private const val LINES = 16
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
            pPoseStack.pushPose()

            pPoseStack.translate(-pCamX, -pCamY, -pCamZ)

            pPoseStack.translate(cylinder.center)
            pPoseStack.mulPose(Rotation3d.createFromTwoVectors(Vector3d.unitY, cylinder.extent.direction))

            val matrix4f = pPoseStack.last().pose()
            val matrix3f = pPoseStack.last().normal()

            val vertexConsumer = pBufferSource.getBuffer(RenderType.lines())
            val r = color.redAsFloat
            val g = color.greenAsFloat
            val b = color.blueAsFloat
            val a = color.alphaAsFloat

            val radius = cylinder.radius.toFloat()
            val y1 = (-cylinder.extent.length / 2.0).toFloat()
            val y2 = (+cylinder.extent.length / 2.0).toFloat()

            for (i in 1 .. LINES) {
                val r1 = Rotation2d.exp(
                    map(
                        (i - 1).toDouble(),
                        0.0, LINES.toDouble(),
                        0.0, 2.0 * PI
                    )
                )

                val r2 = Rotation2d.exp(
                    map(
                        i.toDouble(),
                        0.0, LINES.toDouble(),
                        0.0, 2.0 * PI
                    )
                )

                val re1 = r1.re.toFloat()
                val im1 = r1.im.toFloat()
                val re2 = r2.re.toFloat()
                val im2 = r2.im.toFloat()

                val x1 = radius * re1
                val z1 = radius * im1
                val x2 = radius * re2
                val z2 = radius * im2

                vertexConsumer
                    .vertex(matrix4f, x1, y1, z1)
                    .color(r, g, b, a)
                    .normal(matrix3f, re1, 0.0f, im1)
                    .endVertex()

                vertexConsumer
                    .vertex(matrix4f, x2, y1, z2)
                    .color(r, g, b, a)
                    .normal(matrix3f, re1, 0.0f, im1)
                    .endVertex()

                vertexConsumer
                    .vertex(matrix4f, x1, y2, z1)
                    .color(r, g, b, a)
                    .normal(matrix3f, re1, 0.0f, im1)
                    .endVertex()

                vertexConsumer
                    .vertex(matrix4f, x2, y2, z2)
                    .color(r, g, b, a)
                    .normal(matrix3f, re1, 0.0f, im1)
                    .endVertex()

                vertexConsumer
                    .vertex(matrix4f, x1, y1, z1)
                    .color(r, g, b, a)
                    .normal(matrix3f, re1, 0.0f, im1)
                    .endVertex()

                vertexConsumer
                    .vertex(matrix4f, x1, y2, z1)
                    .color(r, g, b, a)
                    .normal(matrix3f, re1, 0.0f, im1)
                    .endVertex()
            }

            pPoseStack.popPose()
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

    fun createLineBox(vararg aabbs: AABB, color: Color = Color.WHITE) =
        createCompositionOf(*aabbs.map { LineAABB(it, color) }.toTypedArray())

    fun createLineBox(vararg aabbs: BoundingBox3d, color: Color = Color.WHITE) =
        createCompositionOf(*aabbs.map { LineAABB(AABB(it.min.toVec3(), it.max.toVec3()), color) }.toTypedArray())

    fun createLineOrientedBox(vararg obbs: OrientedBoundingBox3d, color: Color = Color.WHITE) =
        createCompositionOf(*obbs.map { LineOBB(it, color) }.toTypedArray())

    fun createLineCylinder(vararg cylinders: Cylinder3d, color: Color = Color.WHITE) =
        createCompositionOf(*cylinders.map { LineCylinder(it, color) }.toTypedArray())

    fun createDirection(origin: Vector3d, direction: Direction, size: Double = 0.01, color: Color = Color.WHITE) : RenderElement {
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

        return createLineBox(box, color = color)
    }

    fun createPartFrame(part: Part<*>) = createCompositionOf(
        createDirection(part.placement.mountingPointWorld, part.placement.positiveX, color = Color(1f, 0f, 0f, 0.8f)),
        createDirection(part.placement.mountingPointWorld, part.placement.positiveY, color = Color(0f, 1f, 0f, 0.8f)),
        createDirection(part.placement.mountingPointWorld, part.placement.positiveZ, color = Color(0f, 0f, 1f, 0.8f))
    ).withinScopeOf(part)

    fun createPartBounds(part: Part<*>) =
        createLineBox(part.worldBoundingBox).withinScopeOf(part)

    fun compositionOf(vararg elements: RenderElement) = add(createCompositionOf(*elements))

    fun lineBox(vararg aabbs: AABB, color: Color = Color.WHITE) =
        add(createLineBox(*aabbs, color = color))

    fun lineBox(vararg aabbs: BoundingBox3d, color: Color = Color.WHITE) =
        add(createLineBox(*aabbs, color = color))

    fun lineOrientedBox(vararg obbs: OrientedBoundingBox3d, color: Color = Color.WHITE) =
        add(createLineOrientedBox(*obbs, color = color))

    fun lineCylinder(vararg cylinders: Cylinder3d, color: Color = Color.WHITE) =
        add(createLineCylinder(*cylinders, color = color))

    fun direction(origin: Vector3d, direction: Direction, size: Double = 0.05, color: Color = Color.WHITE) =
        add(createDirection(origin, direction, size, color))

    fun partFrame(part: Part<*>) = add(createPartFrame(part))

    fun partBounds(part: Part<*>) = add(createPartBounds(part))

    fun clear() {
        synchronized(obj) {
            elements.clear()
        }
    }
}
