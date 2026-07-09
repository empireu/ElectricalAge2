@file:Suppress("unused")

/**
 * Screen-space deferred flashlight rendering system with shadow map support.
 *
 * This system renders a directional cone light as a fullscreen post-process pass at [RenderLevelStageEvent.Stage.AFTER_LEVEL].
 * It reads the main render target's depth buffer to reconstruct world positions and surface normals per pixel,
 * then additively applies diffuse cone lighting with shadow map occlusion testing.
 *
 * Shadows are rendered from the light's point of view by [ShadowMapRenderer], which renders solid block cubes
 * within range into a depth texture. This handles off-screen occluders that screen-space raymarching cannot detect.
 *
 * The approach requires zero mixins and is compatible with vanilla, embeddium, and flywheel, because all three
 * render into the same main [com.mojang.blaze3d.pipeline.RenderTarget], whose depth buffer is the only input.
 *
 * The player's depth buffer is copied to a temporary [TextureTarget] before sampling, because reading a texture that is
 * attached to the currently-bound draw framebuffer is undefined behavior (OpenGL feedback loop).
 */
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
import kotlin.math.abs

object DynamicLightManager {
    private const val FLASHLIGHT_RANGE = 24.0f
    private const val FLASHLIGHT_INTENSITY = 0.6f
    private const val FLASHLIGHT_HALF_ANGLE_DEG = 30.0f

    private var shader: ShaderInstance? = null
    private var depthCopyTarget: TextureTarget? = null
    private var depthCopyWidth = 0
    private var depthCopyHeight = 0


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
     * Renders the flashlight at [RenderLevelStageEvent.Stage.AFTER_LEVEL], after the full scene is composited but before the hand.
     */
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return
        }

        val player = Minecraft.getInstance().player ?: return
        val shader = this.shader ?: return

        RenderSystem.assertOnRenderThread()

        val camera = event.camera
        val partialTick = event.partialTick

        val rawLightPosition = player.getEyePosition(partialTick)
        val lookDirection = player.getViewVector(partialTick)

        // Offset the flashlight position forward and to the right, simulating a hand-held flashlight.
        // This separates the light source from the camera eye, allowing occluders near the player
        // to cast visible shadows in first person.
        val forwardOffset = lookDirection.scale(0.3)
        val right = lookDirection.cross(Vec3(0.0, 1.0, 0.0)).normalize().scale(if (abs(lookDirection.y) > 0.99) 0.0 else 0.3)
        val downOffset = Vec3(0.0, -0.2, 0.0)
        val lightPosition = rawLightPosition.add(forwardOffset).add(right).add(downOffset)

        val savedBlendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
        val savedBlendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
        val savedBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
        val savedBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
        val savedBlendEnabled = GL11.glGetBoolean(GL11.GL_BLEND)
        val savedDepthTestEnabled = GL11.glGetBoolean(GL11.GL_DEPTH_TEST)
        val savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        val savedShader = RenderSystem.getShader()

        // Render the shadow map from the light's point of view before the flashlight pass.
        val lightViewProj = ShadowMapRenderer.renderShadowMap(
            lightPosition,
            lookDirection,
            FLASHLIGHT_HALF_ANGLE_DEG,
            FLASHLIGHT_RANGE
        )

        renderFlashlightPass(
            shader,
            event.projectionMatrix,
            camera,
            lightPosition,
            lookDirection,
            lightViewProj
        )

        // Restore GL state for subsequent rendering (e.g. the hand/vignette).
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

        // BlendMode.lastApplied is a private static cache in BlendMode that ShaderInstance.apply()
        // uses to skip redundant blend state changes. BlendMode.apply() only updates lastApplied
        // when the opaque flag changes between the old and new blend mode, NOT when only the blend
        // factors change. Our dynamic_light shader (ONE, ONE) and position_tex (SRC_ALPHA,
        // ONE_MINUS_SRC_ALPHA) are both non-opaque, so calling apply() on positionShader does not
        // update lastApplied: it stays (ONE, ONE). Then the vignette's position_tex.apply() sees
        // lastApplied != position_tex.blend, calls blendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA), and
        // overwrites the blendFuncSeparate(ZERO, ONE_MINUS_SRC_COLOR, ONE, ZERO) override from
        // renderVignette. Since the vignette texture has alpha=1 everywhere, SrcAlpha=1 means the
        // black center RGB overwrites the terrain.
        // Fix: reset lastApplied to null (made public by accesstransformer.cfg, SRG name f_85499_),
        // then call positionShader.apply(). When lastApplied is null, apply() enters the update
        // branch and properly sets lastApplied to positionShader's blend (SRC_ALPHA,
        // ONE_MINUS_SRC_ALPHA). This matches what vanilla would have set (terrain and hand shaders
        // all use the same blend), so the vignette's apply() is a no-op and the blendFuncSeparate
        // override survives.
        BlendMode.lastApplied = null
        val positionShader = GameRenderer.getPositionShader()
        if (positionShader != null) {
            positionShader.apply()
            positionShader.clear()
        }

        // Restore raw GL blend state AFTER the apply() call above, which sets blend from the
        // shader's BlendMode. The order matters: apply() overrides blendFunc, so we restore after.
        if (savedBlendEnabled) {
            RenderSystem.enableBlend()
        } else {
            RenderSystem.disableBlend()
        }
        GlStateManager._blendFuncSeparate(
            savedBlendSrcRgb, savedBlendDstRgb,
            savedBlendSrcAlpha, savedBlendDstAlpha
        )

        // Clear BufferUploader's cached VBO so the vignette re-binds its own POSITION_TEX VAO.
        // Do NOT unbind GL buffers manually — unbinding GL_ELEMENT_ARRAY_BUFFER while a VAO is
        // bound corrupts that VAO's index buffer state, causing a native crash on the next draw.
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
        // Blit both depth (256) and color (16384) from the source to the dest framebuffer.
        // This is equivalent to copyDepthFrom but also copies the color buffer.
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dest.frameBufferId)
        GlStateManager._glBlitFrameBuffer(
            0, 0, source.width, source.height,
            0, 0, dest.width, dest.height,
            16384 or 256, 9728
        )
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
    }

    private fun renderFlashlightPass(
        shader: ShaderInstance,
        projectionMatrix: Matrix4f,
        camera: Camera,
        lightPosition: Vec3,
        lookDirection: Vec3,
        lightViewProj: Matrix4f
    ) {
        val mainTarget = Minecraft.getInstance().mainRenderTarget
        val depthCopy = ensureDepthCopyTarget(mainTarget.width, mainTarget.height)

        // Copy depth AND color to a temporary target to avoid the OpenGL feedback loop:
        // sampling a texture attached to the currently-bound draw framebuffer is undefined.
        // The color copy is used as the surface albedo in the lighting calculation, preserving
        // texture detail in dark areas instead of adding a flat white blob.
        copyDepthAndColor(mainTarget, depthCopy)
        mainTarget.bindWrite(true)

        val deg2rad = PI.toFloat() / 180f

        // The view matrix must match what vanilla used to render the depth buffer: RotX(xRot) * RotY(yRot+180) * Translate(-camPos), built from [Camera] angles (not the camera quaternion, which is camera-to-world and inverts left/right).
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

        val color = Vector3f(1.0f, 0.95f, 0.8f)
        val direction = Vector3f(
            lookDirection.x.toFloat(),
            lookDirection.y.toFloat(),
            lookDirection.z.toFloat()
        )
        val position = Vector3f(
            lightPosition.x.toFloat(),
            lightPosition.y.toFloat(),
            lightPosition.z.toFloat()
        )

        val cosHalfAngle = cos(FLASHLIGHT_HALF_ANGLE_DEG.toDouble() * PI / 180.0).toFloat()

        // Save and replace projection/model-view for a fullscreen NDC quad (-1..1), no camera transform needed.
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(Matrix4f(), VertexSorting.DISTANCE_TO_ORIGIN)

        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushPose()
        modelViewStack.setIdentity()
        RenderSystem.applyModelViewMatrix()

        // Additive blend (ONE, ONE): the flashlight adds light on top of the existing scene. Depth test and write are disabled since this is a pure post-process pass.
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
        shader.safeGetUniform("u_lightColor").set(color)
        shader.safeGetUniform("u_cosHalfAngle").set(cosHalfAngle)
        shader.safeGetUniform("u_range").set(FLASHLIGHT_RANGE)
        shader.safeGetUniform("u_intensity").set(FLASHLIGHT_INTENSITY)
        shader.safeGetUniform("u_screenSize").set(
            mainTarget.width.toFloat(),
            mainTarget.height.toFloat()
        )

        drawFullscreenQuad()

        // Restore projection/model-view only. Blend/depth/color mask are restored in render().
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
     */
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
