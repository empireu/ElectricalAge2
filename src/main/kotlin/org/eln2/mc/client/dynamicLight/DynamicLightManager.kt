@file:Suppress("unused")

package org.eln2.mc.client.dynamicLight

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RegisterShadersEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import org.eln2.mc.LOG
import org.eln2.mc.resource
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos

object DynamicLightManager {
    private const val FLASHLIGHT_RANGE = 16.0f
    private const val FLASHLIGHT_INTENSITY = 0.4f
    private const val FLASHLIGHT_HALF_ANGLE_DEG = 25.0f

    private var shader: ShaderInstance? = null

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
    }

    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return
        }

        val player = Minecraft.getInstance().player ?: return
        val shader = this.shader ?: return

        RenderSystem.assertOnRenderThread()

        val camera = event.camera
        val partialTick = event.partialTick

        val lightPosition = player.getEyePosition(partialTick)
        val lookDirection = player.getViewVector(partialTick)

        renderFlashlightPass(
            shader,
            event.projectionMatrix,
            camera,
            lightPosition,
            lookDirection
        )
    }

    private fun renderFlashlightPass(
        shader: ShaderInstance,
        projectionMatrix: Matrix4f,
        camera: Camera,
        lightPosition: Vec3,
        lookDirection: Vec3
    ) {
        val mainTarget = Minecraft.getInstance().mainRenderTarget

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
        shader.setSampler("DepthSampler", mainTarget.depthTextureId)

        shader.safeGetUniform("ModelViewMat").set(Matrix4f())
        shader.safeGetUniform("ProjMat").set(Matrix4f())
        shader.safeGetUniform("InvViewProjMat").set(invViewProj)
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

        shader.clear()

        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
        RenderSystem.disableBlend()
        RenderSystem.defaultBlendFunc()

        modelViewStack.popPose()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.restoreProjectionMatrix()
    }

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
