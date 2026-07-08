package org.eln2.mc.integration.sodium

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexSorting
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.Vec3
import org.eln2.mc.LOG
import org.joml.Matrix4f

/**
 * Renders the shadow map by dispatching terrain rendering through embeddium's [SodiumWorldRenderer].
 *
 * This calls [SodiumWorldRenderer.drawChunkLayer] with the light's view-projection matrices, causing
 * embeddium to render its compiled chunk geometry (real block shapes, not cubes) into the currently
 * bound shadow FBO. The render lists are culled against the player's camera frustum, so only chunks
 * visible to the player are rendered. For a hand-held flashlight near the player, this is acceptable.
 *
 * Color writes are disabled via [RenderSystem.colorMask] so only depth is written, producing a
 * depth-only shadow map compatible with the existing flashlight pass.
 */
object EmbeddiumShadowRenderer {

    /**
     * Returns true if embeddium is loaded and its [SodiumWorldRenderer] is available.
     */
    fun isAvailable(): Boolean {
        return SodiumWorldRenderer.instanceNullable() != null
    }

    /**
     * Renders terrain from the light's perspective into the currently bound FBO.
     *
     * The caller must have already:
     * - Bound the shadow FBO ([com.mojang.blaze3d.pipeline.RenderTarget.bindWrite])
     * - Cleared the depth buffer
     * - Set [RenderSystem.colorMask] to (false, false, false, false) for depth-only writes
     * - Saved the projection matrix via [RenderSystem.backupProjectionMatrix]
     *
     * After this call returns, the caller is responsible for restoring GL state.
     *
     * @param lightPosition The world position of the light source, used as the camera position for
     *                      the chunk offset uniform.
     * @param lightView The light's view matrix (world-to-light-view) from setLookAt. Only the rotation
     *                  part is used as the model-view matrix; the translation is handled via the chunk
     *                  offset uniform using [lightPosition].
     * @param lightProj The light's projection matrix. This is set as the projection matrix.
     */
    fun renderTerrainShadow(
        lightPosition: Vec3,
        lightView: Matrix4f,
        lightProj: Matrix4f
    ) {
        val sodiumRenderer = SodiumWorldRenderer.instanceNullable()
        if (sodiumRenderer == null) {
            LOG.warn("EmbeddiumShadowRenderer: SodiumWorldRenderer not available")
            return
        }

        RenderSystem.setProjectionMatrix(lightProj, VertexSorting.DISTANCE_TO_ORIGIN)

        // Extract the rotation-only model-view from the light view matrix.
        // Vanilla applies only camera rotation to the PoseStack (see GameRenderer.renderLevel lines 1142-1143);
        // the camera position translation is handled separately via the CHUNK_OFFSET uniform, which embeddium
        // computes as (regionOrigin - cameraPos) using the x/y/z passed to drawChunkLayer.
        // The lightView from setLookAt contains both rotation and translation (-eye). If we pass the full
        // matrix, the translation would be applied twice: once in the model-view and once via CHUNK_OFFSET.
        // So we strip the translation column, keeping only rotation, matching vanilla's convention.
        val rotationOnly = Matrix4f(lightView)
        rotationOnly.m30(0f)
        rotationOnly.m31(0f)
        rotationOnly.m32(0f)

        val poseStack = PoseStack()
        poseStack.mulPoseMatrix(rotationOnly)

        RenderDevice.enterManagedCode()
        try {
            // RenderType.solid() maps to both SOLID and CUTOUT passes in embeddium's
            // RENDER_PASS_MAPPINGS, covering all opaque and cutout-mipped geometry.
            // RenderType.cutoutMipped() and RenderType.cutout() have no mapping entries
            // in embeddium and would be no-ops, so we only call solid().
            sodiumRenderer.drawChunkLayer(
                RenderType.solid(),
                poseStack,
                lightPosition.x,
                lightPosition.y,
                lightPosition.z
            )
        } finally {
            RenderDevice.exitManagedCode()
        }
    }
}
