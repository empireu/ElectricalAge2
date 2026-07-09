@file:Suppress("unused")

/**
 * Dynamic light rendering system with shadow map support.
 *
 * Multiple [DynamicLightSource]s can be registered. Each frame, the nearest [MAX_LIGHTS] sources to the camera
 * are selected. For each, a shadow map is rendered from the light's point of view by [ShadowMapRenderer],
 * then a fullscreen additive lighting pass is run using [dynamic_light] shader.
 *
 * The system fires at [RenderLevelStageEvent.Stage.AFTER_LEVEL], after the full scene is composited but before the hand.
 * It reads the main render target's depth and color buffers (copied to a temporary target to avoid the OpenGL feedback loop).
 *
 * Light source providers (e.g. [org.eln2.mc.common.content.FlashlightItem]) register update callbacks via [addUpdateCallback]
 * to manage their sources each frame. The manager is light-source-agnostic: it only iterates whatever is registered.
 * */
package org.eln2.mc.client.dynamicLight

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RegisterShadersEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import org.eln2.mc.LOG
import org.eln2.mc.resource
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL30
import kotlin.math.PI
import kotlin.math.cos

object DynamicLightManager {
    private const val MAX_LIGHTS = 4

    private var shader: ShaderInstance? = null
    private var depthCopyTarget: TextureTarget? = null
    private var depthCopyWidth = 0
    private var depthCopyHeight = 0

    private val lightSources = mutableListOf<DynamicLightSource>()
    private val updateCallbacks = mutableListOf<(ClientLevel, Float) -> Unit>()

    fun register(event: RegisterShadersEvent) {
        val src = ShaderInstance(
            Minecraft.getInstance().resourceManager,
            resource("dynamic_light"),
            DefaultVertexFormat.POSITION
        )

        event.registerShader(src) {
            shader = it
            LOG.info("Loaded dynamic light shader.")
        }

        ShadowMapRenderer.registerShader(event)
    }

    /**
     * Registers a [DynamicLightSource] with the manager. The source will be rendered each frame if among the nearest [MAX_LIGHTS] to the camera.
     * Returns the source so the caller can modify it (intensity, range, etc.) or unregister it later.
     * */
    fun createLightSource(
        poseUpdater: (Float) -> Pair<Vec3, Vec3>,
        color: Vector3f = Vector3f(1.0f, 0.95f, 0.8f),
        intensity: Float = 0.6f,
        range: Float = 24.0f,
        halfAngleDeg: Float = 30.0f,
    ): DynamicLightSource {
        val source = DynamicLightSourceImpl(poseUpdater, color, intensity, range, halfAngleDeg)
        lightSources.add(source)
        return source
    }

    /**
     * Removes a [DynamicLightSource] from the manager.
     * */
    fun removeLightSource(source: DynamicLightSource) {
        lightSources.remove(source)
    }

    /**
     * Adds a callback invoked each render frame before rendering lights.
     * The callback receives the [ClientLevel] and partial tick, and should register/unregister its [DynamicLightSource]s as needed.
     * */
    fun addUpdateCallback(callback: (ClientLevel, Float) -> Unit) {
        updateCallbacks.add(callback)
    }

    /**
     * Clears all light sources and update callbacks. Called on level unload / disconnect.
     * */
    fun clear() {
        lightSources.clear()
        updateCallbacks.clear()
    }

    /**
     * Renders all active light sources at [RenderLevelStageEvent.Stage.AFTER_LEVEL].
     * */
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return
        }

        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val shader = this.shader ?: return
        val camera = event.camera
        val partialTick = event.partialTick

        RenderSystem.assertOnRenderThread()

        for (callback in updateCallbacks) {
            callback(level, partialTick)
        }

        for (source in lightSources) {
            source.updatePose(partialTick)
        }

        val activeSources = lightSources
            .filter { it.intensity > 0.0f }
            .sortedBy { it.position.distanceToSqr(camera.position) }
            .take(MAX_LIGHTS)

        if (activeSources.isEmpty()) {
            return
        }

        val savedBlendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
        val savedBlendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
        val savedBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
        val savedBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
        val savedBlendEnabled = GL11.glGetBoolean(GL11.GL_BLEND)
        val savedDepthTestEnabled = GL11.glGetBoolean(GL11.GL_DEPTH_TEST)
        val savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        val savedShader = RenderSystem.getShader()

        val mainTarget = minecraft.mainRenderTarget
        val depthCopy = ensureDepthCopyTarget(mainTarget.width, mainTarget.height)
        copyDepthAndColor(mainTarget, depthCopy)
        mainTarget.bindWrite(true)

        for (source in activeSources) {
            val lightViewProj = ShadowMapRenderer.renderShadowMap(
                source.position,
                source.direction,
                source.halfAngleDeg,
                source.range
            )

            renderLightPass(
                shader,
                event.projectionMatrix,
                camera,
                source,
                lightViewProj,
                depthCopy
            )
        }

        if (savedDepthTestEnabled) {
            RenderSystem.enableDepthTest()
        } else {
            RenderSystem.disableDepthTest()
        }
        if (savedDepthMask) {
            RenderSystem.depthMask(true)
        } else {
            RenderSystem.depthMask(false)
        }

        BlendMode.lastApplied = null
        val positionShader = GameRenderer.getPositionShader()
        if (positionShader != null) {
            positionShader.apply()
            positionShader.clear()
        }

        if (savedBlendEnabled) {
            RenderSystem.enableBlend()
        } else {
            RenderSystem.disableBlend()
        }
        GlStateManager._blendFuncSeparate(
            savedBlendSrcRgb, savedBlendDstRgb,
            savedBlendSrcAlpha, savedBlendDstAlpha
        )

        BufferUploader.invalidate()
        savedShader?.let { RenderSystem.setShader { it } }
    }

    private fun ensureDepthCopyTarget(width: Int, height: Int): TextureTarget {
        val current = depthCopyTarget
        if (current != null && depthCopyWidth == width && depthCopyHeight == height) {
            return current
        }

        current?.destroyBuffers()

        val target = TextureTarget(width, height, true, false)
        target.setClearColor(0f, 0f, 0f, 0f)
        depthCopyTarget = target
        depthCopyWidth = width
        depthCopyHeight = height
        LOG.info("Resized depth copy target to ${width}x${height}")
        return target
    }

    private fun copyDepthAndColor(source: com.mojang.blaze3d.pipeline.RenderTarget, dest: TextureTarget) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dest.frameBufferId)
        GlStateManager._glBlitFrameBuffer(
            0, 0, source.width, source.height,
            0, 0, dest.width, dest.height,
            16384 or 256, 9728
        )
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
    }

    private fun renderLightPass(
        shader: ShaderInstance,
        projectionMatrix: Matrix4f,
        camera: Camera,
        source: DynamicLightSource,
        lightViewProj: Matrix4f,
        depthCopy: TextureTarget,
    ) {
        val mainTarget = Minecraft.getInstance().mainRenderTarget
        mainTarget.bindWrite(true)

        val deg2rad = PI.toFloat() / 180f

        val viewMatrix = Matrix4f()
            .rotateX(camera.xRot * deg2rad)
            .rotateY((camera.yRot + 180f) * deg2rad)
            .translate(
                -camera.position.x.toFloat(),
                -camera.position.y.toFloat(),
                -camera.position.z.toFloat()
            )

        val viewProj = Matrix4f(projectionMatrix).mul(viewMatrix)
        val invViewProj = viewProj.invert()

        val direction = Vector3f(
            source.direction.x.toFloat(),
            source.direction.y.toFloat(),
            source.direction.z.toFloat()
        )
        val position = Vector3f(
            source.position.x.toFloat(),
            source.position.y.toFloat(),
            source.position.z.toFloat()
        )

        val cosHalfAngle = cos(source.halfAngleDeg.toDouble() * PI / 180.0).toFloat()

        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(Matrix4f(), VertexSorting.DISTANCE_TO_ORIGIN)

        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushPose()
        modelViewStack.setIdentity()
        RenderSystem.applyModelViewMatrix()

        RenderSystem.enableBlend()
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE)
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)

        RenderSystem.setShader { shader }
        shader.setSampler("DepthSampler", depthCopy.depthTextureId)
        shader.setSampler("ShadowMap", ShadowMapRenderer.getDepthTextureId())
        shader.setSampler("SceneColorSampler", depthCopy.colorTextureId)

        shader.safeGetUniform("ModelViewMat").set(Matrix4f())
        shader.safeGetUniform("ProjMat").set(Matrix4f())
        shader.safeGetUniform("InvViewProjMat").set(invViewProj)
        shader.safeGetUniform("u_lightViewProj").set(lightViewProj)
        shader.safeGetUniform("u_lightPosition").set(position)
        shader.safeGetUniform("u_lightDirection").set(direction)
        shader.safeGetUniform("u_lightColor").set(source.color)
        shader.safeGetUniform("u_cosHalfAngle").set(cosHalfAngle)
        shader.safeGetUniform("u_range").set(source.range)
        shader.safeGetUniform("u_intensity").set(source.intensity)
        shader.safeGetUniform("u_screenSize").set(
            mainTarget.width.toFloat(),
            mainTarget.height.toFloat()
        )

        drawFullscreenQuad()

        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
        RenderSystem.disableBlend()
        RenderSystem.defaultBlendFunc()

        modelViewStack.popPose()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.restoreProjectionMatrix()
    }

    /**
     * Draws a fullscreen quad in NDC coordinates (-1..1) with no UVs or normals.
     * */
    private fun drawFullscreenQuad() {
        val tesselator = Tesselator.getInstance()
        val buffer = tesselator.builder
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)
        buffer.vertex(-1.0, -1.0, 0.0).endVertex()
        buffer.vertex(1.0, -1.0, 0.0).endVertex()
        buffer.vertex(1.0, 1.0, 0.0).endVertex()
        buffer.vertex(-1.0, 1.0, 0.0).endVertex()
        BufferUploader.drawWithShader(buffer.end())
    }
}
