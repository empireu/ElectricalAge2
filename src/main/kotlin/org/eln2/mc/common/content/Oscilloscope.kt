package org.eln2.mc.common.content

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.client.event.RegisterShadersEvent
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Rotation3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.utils.Stopwatch
import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.client.render.foundation.partOffsetTable
import org.eln2.mc.clientOnlyHolder
import org.eln2.mc.common.blocks.foundation.AdditionalRenderingPart
import org.eln2.mc.common.parts.foundation.Part
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.extensions.mulPose
import org.eln2.mc.extensions.rotationFast
import org.eln2.mc.resource
import java.util.UUID
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

@ClientOnly
private class OscilloscopeTexture(val resourceId: ResourceLocation, val columnCount: Int, val channelCount: Int) {
    // No FP format, we hack away...
    private val image = NativeImage(
        NativeImage.Format.RGBA,
        columnCount,
        channelCount,
        true
    )

    val glTex = DynamicTexture(image)

    var writeX = 0
        private set

    var count = 0
        private set

    var closed = false
        private set

    init {
        val manager = Minecraft.getInstance().textureManager

        manager.register(resourceId, glTex)

        for (x in 0 until columnCount) {
            for (y in 0 until channelCount) {
                image.setPixelRGBA(x, y, 0)
            }
        }

        glTex.bind()
        glTex.upload()
    }

    fun writeColumnAndUpload(column: FloatArray) {
        require(!closed) {
            error("Tried to upload column after texture closed!")
        }

        require(column.size == channelCount) {
            "Column ${column.size} must be as large as the texture's column ($channelCount)"
        }

        val height = channelCount
        var y = 0

        while (y < height) {
            val sample = column[y]

            val v = (sample + 1.0f) * 0.5f

            val s0 = v * 0.999f

            val encG = s0 * 255.0f
            val encB = s0 * 65025.0f
            val encA = s0 * 16581375.0f

            val r = s0 - floor(encG / 255.0f)
            val g = encG - floor(encB / 255.0f)
            val b = encB - floor(encA / 255.0f)
            val a = encA - floor(encA / 255.0f) * 255.0f

            val intR = (r * 255.0f).toInt() and 0xFF
            val intG = (g * 255.0f).toInt() and 0xFF
            val intB = (b * 255.0f).toInt() and 0xFF
            val intA = (a).toInt() and 0xFF

            val int = (intA shl 24) or (intB shl 16) or (intG shl 8) or intR

            image.setPixelRGBA(writeX, y, int)
            y++
        }

        glTex.bind()
        glTex.upload() // TODO we can upload just the slice

        writeX = (writeX + 1) % columnCount

        if(count < columnCount) {
            count++
        }
    }

    fun close() {
        if(closed) {
            return
        }

        closed = true

        image.close()
        glTex.close()
    }
}

@ClientOnly
object OscilloscopeShader {
    private var shader: ShaderInstance? = null

    fun register(event: RegisterShadersEvent) {
        val src = ShaderInstance(
            Minecraft.getInstance().resourceManager,
            resource("oscilloscope"),
            DefaultVertexFormat.POSITION_TEX
        )

        event.registerShader(src) {
            this.shader = it
            LOG.info("Loaded oscilloscope shader.")
        }
    }

    // Bind shader and set uniforms. Assumes texture bound to texture unit 0 before drawing.
    fun bindAndSetUniforms(
        thickness: Float,
        writeX: Int,
        sampleCount: Int,
        alpha: Float,
        palette: FloatArray,
        texture: Int
    ) {
        RenderSystem.assertOnRenderThread()

        // FUCK YOU MOJANG. FUCK YOU, FUCK YOU, FUCK YOU!

        val shader = shader ?: error("Oscilloscope shader didn't load")

        RenderSystem.setShader { shader }
        shader.safeGetUniform("Sampler0").set(texture)
        shader.safeGetUniform("u_writeX").set(writeX.toFloat())
        shader.safeGetUniform("u_count").set(sampleCount.toFloat())
        shader.safeGetUniform("u_thickness").set(thickness)
        shader.safeGetUniform("u_alpha").set(alpha)
        shader.safeGetUniform("u_channelColors").set(palette)
    }

    fun unbind() {
        RenderSystem.setShader { GameRenderer.getPositionTexShader() }
    }
}

class OscilloscopePart(ci: PartCreateInfo) : Part(ci), AdditionalRenderingPart {
    @ClientOnly
    private class RenderState(horizonColumns: Int, channels: Int) {
        init {
            RenderSystem.assertOnRenderThread()
        }

        val texture = OscilloscopeTexture(
            resource("oscilloscope_${UUID.randomUUID()}"),
            horizonColumns,
            channels
        )

        fun close() {
            texture.close()
        }
    }

    // Initialize on render thread (first call to [levelRender]
    private var renderStateImpl = clientOnlyHolder {
        RenderState(1000, 2)
    }

    override fun onUnloaded() {
        if(placement.level.isClientSide) {
            renderStateImpl.get().close()
        }

        super.onUnloaded()
    }

    val sww = Stopwatch()
    var t = 0.0

    override fun levelRender(context: AdditionalRenderingPart.Context) {
        val renderState = renderStateImpl.get()

        if(sww.total > 0.01) {
            sww.resetTotal()
            renderState.texture.writeColumnAndUpload(FloatArray(2) {
                0.9f * sin(Rotation2d.exp(t * 2.0f).ln() + it * PI / 2.0).toFloat()
            })

            t += 0.01
        }

        val poseStack = context.poseStack

        poseStack.pushPose()

        val tY = placement.provider.placementCollisionSize.y.toFloat() * 0.9f

        poseStack.translate(
            placement.face.stepX.toFloat() * tY,
            placement.face.stepY.toFloat() * tY,
            placement.face.stepZ.toFloat() * tY
        )

        val (dx, dy, dz) = partOffsetTable[placement.face.get3DDataValue()]
        poseStack.translate(dx, dy, dz)
        poseStack.mulPose(placement.face.rotationFast)
        poseStack.mulPose(Rotation3d.exp(Vector3d.unitY * placement.facing.angle))
        // x = left-right, z = up-down (neg = up)
        poseStack.translate(-0.075f, 0.0f, 0.01f)
        // z = height, x = width
        poseStack.scale(0.5f, 1.0f, 0.4f);
        poseStack.mulPose(Rotation3d.exp(Vector3d.unitX * PI / 2.0))
        //pPoseStack.translate(-0.5, 0.0, -0.5)

        // Bind texture to texture unit 0 (sampler 0 in shader)
        val texLoc = renderState.texture.resourceId
        // Define your palette here
        val bluePalette = floatArrayOf(
            // Channel 0: Blue
            0.0f, 0.0f, 1.0f,
            // Channel 1: (Unused, e.g., Green)
            0.0f, 1.0f, 0.0f,
            // Channel 2: (Unused, e.g., Red)
            1.0f, 0.0f, 0.0f,
            // Channel 3: (Unused, e.g., Yellow)
            1.0f, 1.0f, 0.0f
        )

        RenderSystem.setShaderTexture(0, texLoc)

        OscilloscopeShader.bindAndSetUniforms(
            0.04f,
            renderState.texture.writeX,
            renderState.texture.count,
            1.0f,
            bluePalette,
            renderState.texture.glTex.id
        )

        texturedQuad(poseStack);
        OscilloscopeShader.unbind();

        poseStack.popPose()
    }

    override fun shouldRenderOffScreen(): Boolean {
        return true
    }

    // One draw call per oscilloscope, it's fine
    private fun texturedQuad(poseStack: PoseStack) {
        RenderSystem.enableBlend()

        RenderSystem.blendFunc(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        )

        RenderSystem.enableDepthTest()

        val pose = poseStack.last().pose()
        val tesselator = Tesselator.getInstance()
        val builder = tesselator.builder

        val quadLeft = -0.5f
        val quadRight = 0.5f
        val quadTop = -0.5f
        val quadBottom = 0.5f

        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
        builder.vertex(pose, quadLeft, quadBottom, 0f).uv(0f, 1f).endVertex()
        builder.vertex(pose, quadRight, quadBottom, 0f).uv(1f, 1f).endVertex()
        builder.vertex(pose, quadRight, quadTop, 0f).uv(1f, 0f).endVertex()
        builder.vertex(pose, quadLeft, quadTop, 0f).uv(0f, 0f).endVertex()
        tesselator.end()
    }
}
