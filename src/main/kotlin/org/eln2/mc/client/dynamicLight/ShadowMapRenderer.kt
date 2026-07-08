@file:Suppress("unused")

package org.eln2.mc.client.dynamicLight

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.eln2.mc.LOG
import org.eln2.mc.integration.sodium.EmbeddiumShadowRenderer
import org.eln2.mc.resource
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.PI

/**
 * Renders a shadow map from the light's point of view.
 *
 * When embeddium is present, delegates to [EmbeddiumShadowRenderer], which dispatches terrain
 * rendering through embeddium's [SodiumWorldRenderer] to render real block geometry (stairs, slabs,
 * fences, custom models) into the shadow depth buffer. This produces accurate shadows for all
 * block shapes and is compatible with embeddium's optimized rendering pipeline.
 *
 * When embeddium is not present, falls back to rendering unit cubes for solid blocks. This is a
 * brute-force approximation that only models cube volumes, but requires no mixins.
 *
 * The shadow map is a depth texture that stores the distance from the light to the nearest surface.
 * The flashlight shader samples this texture to determine if a pixel is occluded (shadowed) by
 * geometry between the light source and the pixel's world position.
 */
object ShadowMapRenderer {
    private const val SHADOW_MAP_SIZE = 1024
    private const val NEAR_PLANE = 0.05f

    private var shader: ShaderInstance? = null
    private var shadowTarget: TextureTarget? = null

    /**
     * Dedicated [VertexBuffer] with its own VAO, used to avoid corrupting vanilla's cached vertex array state.
     *
     * [BufferUploader] caches the last [VertexBuffer] used for immediate-mode draws by vertex format.
     * Both this shadow pass and the flashlight pass use [DefaultVertexFormat.POSITION], so they would share
     * the same cached [VertexBuffer] and its VAO. Raw GL calls like `glVertexAttribPointer` modify the
     * currently-bound VAO, which would corrupt the shared VAO's attribute state. The flashlight pass then
     * skips [VertexFormat.setupBufferState] (because the format matches), leaving the corrupted state in place.
     *
     * Using a private [VertexBuffer] isolates the shadow pass's VAO. [VertexBuffer.bind] and
     * [VertexBuffer.unbind] invalidate the [BufferUploader] cache, so the flashlight pass re-binds its own
     * (uncorrupted) VAO.
     */
    private val shadowVbo by lazy { VertexBuffer(VertexBuffer.Usage.DYNAMIC) }

    fun registerShader(event: net.minecraftforge.client.event.RegisterShadersEvent) {
        val src = ShaderInstance(
            Minecraft.getInstance().resourceManager,
            resource("shadow_depth"),
            DefaultVertexFormat.POSITION
        )

        event.registerShader(src) {
            shader = it
            LOG.info("Loaded shadow depth shader.")
        }
    }

    private fun ensureShadowTarget(): TextureTarget {
        shadowTarget?.let { return it }
        val target = TextureTarget(SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, true, false)
        target.setClearColor(1f, 1f, 1f, 1f)
        shadowTarget = target
        LOG.info("Created shadow map target ${SHADOW_MAP_SIZE}x${SHADOW_MAP_SIZE}")
        return target
    }

    /**
     * Renders the shadow map from [lightPosition] looking in [lightDirection] with a cone half-angle of [halfAngleDeg] and [range].
     * Returns the light's view-projection matrix (world-to-light-clip), which the flashlight shader uses to sample the shadow map.
     */
    fun renderShadowMap(
        lightPosition: Vec3,
        lightDirection: Vec3,
        halfAngleDeg: Float,
        range: Float
    ): Matrix4f {
        RenderSystem.assertOnRenderThread()

        val (lightView, lightProj) = computeLightViewAndProjection(lightPosition, lightDirection, halfAngleDeg, range)
        val lightViewProj = Matrix4f(lightProj).mul(lightView)

        val target = ensureShadowTarget()
        target.bindWrite(true)
        target.clear(Minecraft.ON_OSX)
        // clear() calls unbindWrite(), so rebind the shadow target for rendering.
        target.bindWrite(true)

        RenderSystem.backupProjectionMatrix()

        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushPose()
        modelViewStack.setIdentity()
        RenderSystem.applyModelViewMatrix()

        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
        RenderSystem.colorMask(false, false, false, false)

        if (EmbeddiumShadowRenderer.isAvailable()) {
            renderEmbeddiumShadow(lightPosition, lightView, lightProj)
        } else {
            drawOccluderCubes(lightPosition, range, lightViewProj)
        }

        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.disableDepthTest()

        modelViewStack.popPose()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.restoreProjectionMatrix()

        // Rebind the main render target for subsequent rendering (flashlight, hand, vignette).
        Minecraft.getInstance().mainRenderTarget.bindWrite(true)

        return lightViewProj
    }

    fun getDepthTextureId(): Int {
        return shadowTarget?.depthTextureId ?: 0
    }

    private fun renderEmbeddiumShadow(
        lightPosition: Vec3,
        lightView: Matrix4f,
        lightProj: Matrix4f
    ) {
        EmbeddiumShadowRenderer.renderTerrainShadow(lightPosition, lightView, lightProj)
    }

    private fun computeLightViewAndProjection(
        lightPosition: Vec3,
        lightDirection: Vec3,
        halfAngleDeg: Float,
        range: Float
    ): Pair<Matrix4f, Matrix4f> {
        val fovy = halfAngleDeg.toDouble() * 2.0 * PI / 180.0
        val proj = Matrix4f().setPerspective(fovy.toFloat(), 1.0f, NEAR_PLANE, range)

        val eye = Vector3f(lightPosition.x.toFloat(), lightPosition.y.toFloat(), lightPosition.z.toFloat())
        val dir = Vector3f(lightDirection.x.toFloat(), lightDirection.y.toFloat(), lightDirection.z.toFloat())

        val center = Vector3f(eye).add(dir)

        val up = if (abs(dir.y) > 0.99f) {
            Vector3f(0f, 0f, 1f)
        } else {
            Vector3f(0f, 1f, 0f)
        }

        val view = Matrix4f().setLookAt(eye, center, up)

        return Pair(view, proj)
    }

    /**
     * Iterates solid blocks within [range] of [lightPosition] and renders them as cubes using a private [VertexBuffer].
     *
     * Uses [VertexBuffer.drawWithShader] which handles VAO binding, vertex attribute setup, shader application,
     * and cleanup. The private [shadowVbo] has its own VAO, so vanilla's cached vertex array state is not corrupted.
     */
    private fun drawOccluderCubes(lightPosition: Vec3, range: Float, lightViewProj: Matrix4f) {
        val shader = this.shader ?: return
        val level = Minecraft.getInstance().level ?: return
        val tesselator = Tesselator.getInstance()
        val buffer = tesselator.builder
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)

        val origin = BlockPos.containing(lightPosition.x, lightPosition.y, lightPosition.z)
        val r = range.toInt()
        var count = 0

        for (x in -r..r) {
            for (y in -r..r) {
                for (z in -r..r) {
                    val pos = origin.offset(x, y, z)
                    val state = level.getBlockState(pos)
                    if (isOccluder(state, level, pos)) {
                        submitCube(buffer, pos)
                        count++
                    }
                }
            }
        }

        if (count == 0) {
            LOG.warn("Shadow map: no occluders found in range {} around {}", r, origin)
        } else {
            LOG.info("Shadow map: submitted {} occluder cubes", count)
        }

        shader.safeGetUniform("u_lightViewProj").set(lightViewProj)
        RenderSystem.setShader { shader }
        BufferUploader.drawWithShader(buffer.end())
    }


    private fun isOccluder(state: BlockState, level: BlockGetter, pos: BlockPos): Boolean {
        return state.isSolidRender(level, pos)
    }

    /**
     * Submits a unit cube at [pos] as 6 quads (one per face) into [buffer].
     */
    private fun submitCube(buffer: BufferBuilder, pos: BlockPos) {
        val x0 = pos.x.toDouble()
        val y0 = pos.y.toDouble()
        val z0 = pos.z.toDouble()
        val x1 = x0 + 1.0
        val y1 = y0 + 1.0
        val z1 = z0 + 1.0

        // Bottom (y0): -Y
        buffer.vertex(x0, y0, z0).endVertex()
        buffer.vertex(x1, y0, z0).endVertex()
        buffer.vertex(x1, y0, z1).endVertex()
        buffer.vertex(x0, y0, z1).endVertex()

        // Top (y1): +Y
        buffer.vertex(x0, y1, z1).endVertex()
        buffer.vertex(x1, y1, z1).endVertex()
        buffer.vertex(x1, y1, z0).endVertex()
        buffer.vertex(x0, y1, z0).endVertex()

        // North (z0): -Z
        buffer.vertex(x0, y0, z0).endVertex()
        buffer.vertex(x0, y1, z0).endVertex()
        buffer.vertex(x1, y1, z0).endVertex()
        buffer.vertex(x1, y0, z0).endVertex()

        // South (z1): +Z
        buffer.vertex(x1, y0, z1).endVertex()
        buffer.vertex(x1, y1, z1).endVertex()
        buffer.vertex(x0, y1, z1).endVertex()
        buffer.vertex(x0, y0, z1).endVertex()

        // West (x0): -X
        buffer.vertex(x0, y0, z0).endVertex()
        buffer.vertex(x0, y0, z1).endVertex()
        buffer.vertex(x0, y1, z1).endVertex()
        buffer.vertex(x0, y1, z0).endVertex()

        // East (x1): +X
        buffer.vertex(x1, y0, z1).endVertex()
        buffer.vertex(x1, y0, z0).endVertex()
        buffer.vertex(x1, y1, z0).endVertex()
        buffer.vertex(x1, y1, z1).endVertex()
    }
}
