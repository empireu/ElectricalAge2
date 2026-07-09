@file:Suppress("unused")

package org.eln2.mc.client.dynamicLight

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import dev.engine_room.flywheel.api.visualization.VisualizationManager
import dev.engine_room.flywheel.impl.event.RenderContextImpl
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.entity.EntityRenderDispatcher
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
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
 * Terrain is rendered by embeddium (real block geometry) or unit cubes (fallback).
 *
 * Block entities and entities within the light's range are also rendered into the shadow map:
 * - Flywheel-managed block entities and entities are rendered by calling [VisualizationManager.RenderDispatcher.afterEntities]
 *   with a [RenderContext] using the light's view-projection matrices. This renders actual flywheel instance geometry
 *   from the light's perspective without advancing animation state.
 * - Vanilla (non-flywheel) block entities are rendered through [BlockEntityRenderDispatcher.render] with the light's matrices.
 * - Vanilla entities (mobs, items, etc.) are rendered through [EntityRenderDispatcher.render] with the light's matrices.
 *
 * The shadow map is a depth texture sampled by the flashlight shader to determine occlusion.
 */
object ShadowMapRenderer {
    private const val SHADOW_MAP_SIZE = 1024
    private const val NEAR_PLANE = 1.0f

    private var shader: ShaderInstance? = null
    private var shadowTarget: TextureTarget? = null

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

        drawBlockEntityShadows(lightPosition, lightDirection, lightView, lightProj, range, halfAngleDeg)
        drawEntityShadows(lightPosition, lightDirection, lightView, lightProj, range, halfAngleDeg)

        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.disableDepthTest()

        modelViewStack.popPose()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.restoreProjectionMatrix()

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

    /**
     * Renders block entities within [range] of [lightPosition] into the shadow map.
     *
     * Flywheel-managed block entities are rendered by calling [VisualizationManager.RenderDispatcher.afterEntities]
     * with a [RenderContext] using the light's view-projection matrices. This draws the existing flywheel instances
     * (which were already prepared during the vanilla frame) from the light's perspective, without advancing
     * animation state.
     *
     * Vanilla (non-flywheel) block entities are rendered through [BlockEntityRenderDispatcher.render] with the
     * light's view matrices.
     */
    private fun drawBlockEntityShadows(
        lightPosition: Vec3,
        lightDirection: Vec3,
        lightView: Matrix4f,
        lightProj: Matrix4f,
        range: Float,
        halfAngleDeg: Float
    ) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return

        renderFlywheelShadows(minecraft, level, lightPosition, lightView, lightProj)

        val vanillaBlockEntities = collectBlockEntitiesInCone(level, lightPosition, lightDirection, range, halfAngleDeg)
            .filter { !VisualizationHelper.skipVanillaRender(it) }

        if (vanillaBlockEntities.isNotEmpty()) {
            drawVanillaBlockEntityShadows(vanillaBlockEntities, lightPosition, lightView, lightProj, minecraft)
        }
    }

    /**
     * Renders entities (mobs, items, etc.) within [range] of [lightPosition] into the shadow map.
     *
     * Flywheel-managed entities are skipped because they were already rendered by [renderFlywheelShadows].
     * The local player is skipped to avoid self-shadowing.
     *
     * Vanilla entities are rendered through [EntityRenderDispatcher.render] with the light's view matrices.
     * Vanilla blob shadows and hitboxes are disabled during the shadow pass.
     */
    private fun drawEntityShadows(
        lightPosition: Vec3,
        lightDirection: Vec3,
        lightView: Matrix4f,
        lightProj: Matrix4f,
        range: Float,
        halfAngleDeg: Float
    ) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val entityDispatcher = minecraft.entityRenderDispatcher
        val cosHalfAngle = kotlin.math.cos(halfAngleDeg.toDouble() * PI / 180.0).toFloat()
        val partialTick = minecraft.getPartialTick()
        val dirLen = lightDirection.length()

        val player = minecraft.player

        val entities = ArrayList<Entity>()
        for (entity in level.entitiesForRendering()) {
            if (entity === player) {
                continue
            }

            if (VisualizationHelper.skipVanillaRender(entity)) {
                continue
            }

            val interpPos = Vec3(
                Mth.lerp(partialTick.toDouble(), entity.xOld, entity.x),
                Mth.lerp(partialTick.toDouble(), entity.yOld, entity.y),
                Mth.lerp(partialTick.toDouble(), entity.zOld, entity.z)
            )
            val toEntity = interpPos.subtract(lightPosition)
            val dist = toEntity.length()
            if (dist > range || dist < 0.01) {
                continue
            }
            val cosTheta = toEntity.dot(lightDirection) / (dist * dirLen)
            if (cosTheta < cosHalfAngle - 0.15) {
                continue
            }

            entities.add(entity)
        }

        if (entities.isEmpty()) {
            return
        }

        val target = shadowTarget ?: return
        target.bindWrite(true)

        RenderSystem.setProjectionMatrix(lightProj, VertexSorting.DISTANCE_TO_ORIGIN)

        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushPose()
        modelViewStack.setIdentity()
        RenderSystem.applyModelViewMatrix()

        val rotationOnly = Matrix4f(lightView)
        rotationOnly.m30(0f)
        rotationOnly.m31(0f)
        rotationOnly.m32(0f)

        val poseStack = PoseStack()
        poseStack.mulPoseMatrix(rotationOnly)

        val lightCamera = LightCamera(lightPosition)

        val savedRenderShadow = entityDispatcher.shouldRenderHitBoxes()
        entityDispatcher.setRenderShadow(false)
        entityDispatcher.setRenderHitBoxes(false)
        entityDispatcher.prepare(level, lightCamera, minecraft.crosshairPickEntity)

        val bufferSource = minecraft.renderBuffers().bufferSource()

        for (entity in entities) {
            val x = Mth.lerp(partialTick.toDouble(), entity.xOld, entity.x) - lightPosition.x
            val y = Mth.lerp(partialTick.toDouble(), entity.yOld, entity.y) - lightPosition.y
            val z = Mth.lerp(partialTick.toDouble(), entity.zOld, entity.z) - lightPosition.z
            val yaw = Mth.lerp(partialTick, entity.yRotO, entity.yRot)

            val packedLight = entityDispatcher.getPackedLightCoords(entity, partialTick)

            entityDispatcher.render(entity, x, y, z, yaw, partialTick, poseStack, bufferSource, packedLight)
        }

        bufferSource.endBatch()

        entityDispatcher.setRenderShadow(true)
        entityDispatcher.setRenderHitBoxes(savedRenderShadow)
        val playerCamera = minecraft.gameRenderer.mainCamera
        entityDispatcher.prepare(level, playerCamera, minecraft.crosshairPickEntity)

        modelViewStack.popPose()
        RenderSystem.applyModelViewMatrix()
    }

    /**
     * Renders flywheel-managed block entities and entities from the light's perspective by calling
     * [VisualizationManager.RenderDispatcher.afterEntities] with a [RenderContext] constructed from the light's
     * view-projection matrices.
     *
     * This only draws — it does not call [RenderDispatcher.onStartLevelRender] or the frame plan, so animation
     * state is not advanced. The instances were already prepared during the vanilla [LevelRenderer.renderLevel] call.
     *
     * Flywheel's [Engine.render] uses [GlStateTracker.getRestoreState] to save/restore GL state (VAO, program,
     * buffers, active texture), so this call does not corrupt GL state for subsequent rendering.
     */
    private fun renderFlywheelShadows(
        minecraft: Minecraft,
        level: ClientLevel,
        lightPosition: Vec3,
        lightView: Matrix4f,
        lightProj: Matrix4f
    ) {
        val vizManager = VisualizationManager.get(level) ?: return
        val levelRenderer = minecraft.levelRenderer

        val rotationOnly = Matrix4f(lightView)
        rotationOnly.m30(0f)
        rotationOnly.m31(0f)
        rotationOnly.m32(0f)

        val poseStack = PoseStack()
        poseStack.mulPoseMatrix(rotationOnly)

        val lightCamera = LightCamera(lightPosition)

        val partialTick = minecraft.getPartialTick()

        val lightContext = RenderContextImpl.create(
            levelRenderer,
            level,
            minecraft.renderBuffers(),
            poseStack,
            lightProj,
            lightCamera,
            partialTick
        )

        val target = shadowTarget ?: return
        target.bindWrite(true)
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
        RenderSystem.colorMask(false, false, false, false)

        vizManager.renderDispatcher().afterEntities(lightContext)

        RenderSystem.colorMask(false, false, false, false)
        RenderSystem.disableBlend()
    }

    /**
     * Collects block entities within the flashlight's cone and range using chunk-level iteration.
     */
    private fun collectBlockEntitiesInCone(
        level: ClientLevel,
        lightPosition: Vec3,
        lightDirection: Vec3,
        range: Float,
        halfAngleDeg: Float
    ): List<BlockEntity> {
        val r = range.toInt()
        val minChunkX = (lightPosition.x - r).toInt() shr 4
        val maxChunkX = (lightPosition.x + r).toInt() shr 4
        val minChunkZ = (lightPosition.z - r).toInt() shr 4
        val maxChunkZ = (lightPosition.z + r).toInt() shr 4

        val cosHalfAngle = kotlin.math.cos(halfAngleDeg.toDouble() * PI / 180.0).toFloat()
        val dirLen = lightDirection.length()
        val result = ArrayList<BlockEntity>()

        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                val chunk = level.getChunk(chunkX, chunkZ) as LevelChunk ?: continue
                for (be in chunk.blockEntities.values) {
                    val pos = be.blockPos
                    val toEntity = Vec3(
                        pos.x + 0.5 - lightPosition.x,
                        pos.y + 0.5 - lightPosition.y,
                        pos.z + 0.5 - lightPosition.z
                    )
                    val dist = toEntity.length()
                    if (dist > range) {
                        continue
                    }
                    val cosTheta = toEntity.dot(lightDirection) / (dist * dirLen)
                    if (cosTheta < cosHalfAngle - 0.15) {
                        continue
                    }
                    result.add(be)
                }
            }
        }

        return result
    }

    /**
     * Renders vanilla (non-flywheel) block entities through [BlockEntityRenderDispatcher.render] with the light's
     * view matrices.
     */
    private fun drawVanillaBlockEntityShadows(
        blockEntities: List<BlockEntity>,
        lightPosition: Vec3,
        lightView: Matrix4f,
        lightProj: Matrix4f,
        minecraft: Minecraft
    ) {
        val dispatcher = minecraft.blockEntityRenderDispatcher

        RenderSystem.setProjectionMatrix(lightProj, VertexSorting.DISTANCE_TO_ORIGIN)

        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushPose()
        modelViewStack.setIdentity()
        RenderSystem.applyModelViewMatrix()

        val rotationOnly = Matrix4f(lightView)
        rotationOnly.m30(0f)
        rotationOnly.m31(0f)
        rotationOnly.m32(0f)

        val poseStack = PoseStack()
        poseStack.mulPoseMatrix(rotationOnly)

        val lightCamera = LightCamera(lightPosition)
        dispatcher.prepare(minecraft.level!!, lightCamera, minecraft.hitResult!!)

        val bufferSource = minecraft.renderBuffers().bufferSource()
        val partialTick = minecraft.getPartialTick()

        for (be in blockEntities) {
            val pos = be.blockPos
            poseStack.pushPose()
            poseStack.translate(
                pos.x.toDouble() - lightPosition.x,
                pos.y.toDouble() - lightPosition.y,
                pos.z.toDouble() - lightPosition.z
            )
            dispatcher.render(be, partialTick, poseStack, bufferSource)
            poseStack.popPose()
        }

        bufferSource.endBatch()

        modelViewStack.popPose()
        RenderSystem.applyModelViewMatrix()

        val playerCamera = minecraft.gameRenderer.mainCamera
        dispatcher.prepare(minecraft.level!!, playerCamera, minecraft.hitResult!!)
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
     * Iterates solid blocks within [range] of [lightPosition] and renders them as cubes.
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

    private fun isOccluder(state: BlockState, level: net.minecraft.world.level.BlockGetter, pos: BlockPos): Boolean {
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

        buffer.vertex(x0, y0, z0).endVertex()
        buffer.vertex(x1, y0, z0).endVertex()
        buffer.vertex(x1, y0, z1).endVertex()
        buffer.vertex(x0, y0, z1).endVertex()

        buffer.vertex(x0, y1, z1).endVertex()
        buffer.vertex(x1, y1, z1).endVertex()
        buffer.vertex(x1, y1, z0).endVertex()
        buffer.vertex(x0, y1, z0).endVertex()

        buffer.vertex(x0, y0, z0).endVertex()
        buffer.vertex(x0, y1, z0).endVertex()
        buffer.vertex(x1, y1, z0).endVertex()
        buffer.vertex(x1, y0, z0).endVertex()

        buffer.vertex(x1, y0, z1).endVertex()
        buffer.vertex(x1, y1, z1).endVertex()
        buffer.vertex(x0, y1, z1).endVertex()
        buffer.vertex(x0, y0, z1).endVertex()

        buffer.vertex(x0, y0, z0).endVertex()
        buffer.vertex(x0, y0, z1).endVertex()
        buffer.vertex(x0, y1, z1).endVertex()
        buffer.vertex(x0, y1, z0).endVertex()

        buffer.vertex(x1, y0, z1).endVertex()
        buffer.vertex(x1, y0, z0).endVertex()
        buffer.vertex(x1, y1, z0).endVertex()
        buffer.vertex(x1, y1, z1).endVertex()
    }

}

/**
 * A minimal [Camera] positioned at the light source, used to prepare dispatchers and construct
 * flywheel's [RenderContext] so that distance/frustum checks pass against the light position.
 */
private class LightCamera(private val position: Vec3) : net.minecraft.client.Camera() {
    override fun getPosition(): Vec3 = position
}
