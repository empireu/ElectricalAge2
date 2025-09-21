package org.eln2.mc.common.content

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.shaders.AbstractUniform
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.client.event.RegisterShadersEvent
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Rotation3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.Vector4d
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.mathematics.snz
import org.ageseries.libage.mathematics.snzi
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.NEGATIVE
import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.ageseries.libage.utils.Stopwatch
import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.ServerOnly
import org.eln2.mc.TermRef
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.client.render.foundation.partOffsetTable
import org.eln2.mc.common.blocks.foundation.AdditionalRenderingPart
import org.eln2.mc.common.cells.foundation.Cell
import org.eln2.mc.common.cells.foundation.CellCreateInfo
import org.eln2.mc.common.cells.foundation.CellGraph
import org.eln2.mc.common.cells.foundation.ElectricalObject
import org.eln2.mc.common.cells.foundation.Node
import org.eln2.mc.common.cells.foundation.SimObject
import org.eln2.mc.common.cells.foundation.SubscriberCollection
import org.eln2.mc.common.cells.foundation.SubscriberPhase
import org.eln2.mc.common.cells.foundation.addPost
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridMaterialCategory
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.network.serverToClient.PacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.GridCellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.extensions.mulPose
import org.eln2.mc.extensions.rotationFast
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.offerPositive
import org.eln2.mc.requireIsOnRenderThread
import org.eln2.mc.resource
import java.util.UUID
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sign

@ClientOnly
class OscilloscopeTexture(val resourceId: ResourceLocation, val columnCount: Int, val channelCount: Int) {
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
            var sample = -column[y]

            if(sample.isNaN()) {
                sample = 0.0f // channel mask
            }

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

/**
 * Defines an oscilloscope's colors per channel.
 * @param colors The colors for each channel, in order.
 * */
data class OscilloscopePalette(val colors: List<Vector4d>) {
    val colorsInt = colors.map { MyColor(it.w.toFloat(), it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }

    /**
     * Raw buffer holding the data to upload to the shader, that needs to be masked by the channel mask.
     * */
    private val rawData = FloatArray(colors.size * 4)

    init {
        colors.indices.forEach { i ->
            val color = colors[i]
            val j = i * 4

            rawData[j + 0] = color.x.toFloat()
            rawData[j + 1] = color.y.toFloat()
            rawData[j + 2] = color.z.toFloat()
            rawData[j + 3] = color.w.toFloat()
        }
    }

    /**
     * Sets the color palette.
     * @param mask The latest samples. The channels which have NaN will be set to alpha = 0.
     * */
    fun setUniform(unform: AbstractUniform, mask: FloatArray) {
        val data = FloatArray(rawData.size)

        val valuesToCopy = min(rawData.size, mask.size * 4)
        for (i in 0 until valuesToCopy step 4) {
            val sample = mask[i / 4]

            if(!sample.isNaN()) {
                data[i + 0] = rawData[i + 0]
                data[i + 1] = rawData[i + 1]
                data[i + 2] = rawData[i + 2]
                data[i + 3] = rawData[i + 3]
            }
        }

        unform.set(data)
    }

    companion object {
        fun defineRGBA(vararg components: Float) : OscilloscopePalette {
            require(components.size % 4 == 0 && components.isNotEmpty()) {
                "Invalid palette component count ${components.size}"
            }

            val vectors = ArrayList<Vector4d>(components.size / 4)

            for (i in 0 until components.size step 4) {
                vectors.add(
                    Vector4d(
                        components[i + 0].toDouble(),
                        components[i + 1].toDouble(),
                        components[i + 2].toDouble(),
                        components[i + 3].toDouble()
                    )
                )
            }

            return OscilloscopePalette(vectors)
        }

        val DEFAULT = defineRGBA(
            0.0f, 0.0f, 1.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f
        )
    }
}

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

    /**
     * Sets up the shader for rendering.
     * @param thickness The approximate thickness of the line in UV-space.
     * @param alpha Factor for the output alpha.
     * @param palette The colors to use for each channel.
     * @param texture The raw data buffer.
     * */
    fun bindAndSetup(thickness: Float, alpha: Float, palette: OscilloscopePalette, texture: OscilloscopeTexture, mask: FloatArray) {
        RenderSystem.assertOnRenderThread()

        val shader = shader ?: error("Oscilloscope shader didn't load")

        RenderSystem.setShader { shader }
        shader.safeGetUniform("Sampler0").set(texture.glTex.id)
        shader.safeGetUniform("u_writeX").set(texture.writeX.toFloat())
        shader.safeGetUniform("u_count").set(texture.count.toFloat())
        shader.safeGetUniform("u_thickness").set(thickness)
        shader.safeGetUniform("u_alpha").set(alpha)
        palette.setUniform(shader.safeGetUniform("u_channelColors"), mask)
    }

    fun unbind() {
        RenderSystem.setShader { GameRenderer.getPositionTexShader() }
    }
}

/**
 * Oscilloscope settings that don't change at runtime.
 * @param channelCount The number of channels. For each channel, a resistor will be created, that is grounded at the negative terminal and connected to the external circuit at the positive terminal.
 * @param minWindow The minimum number of samples on screen.
 * @param maxWindow The maximum number of samples on screen. This defines the time horizon based on the sampling rate (in optimal conditions, 100 samples/s).
 * */
data class OscilloscopeSpecification(val channelCount: Int, val minWindow: Int, val maxWindow: Int, val palette: OscilloscopePalette)

/**
 * The electrical part of the oscilloscope. Handles creating resistors with a very high resistance to ground.
 * */
class OscilloscopeObject(cell: OscilloscopeCell, val specification: OscilloscopeSpecification) : ElectricalObject<OscilloscopeCell>(cell) {
    val channelRange = 0 until specification.channelCount
    val resistors = Array<Resistor?>(specification.channelCount) { null }

    override fun offerTerminal(gc: GridConnectionCell, m0: GridConnectionCell.NodeInfo): TermRef? {
        val terminal = m0.terminal

        if(!channelRange.contains(terminal)) {
            return null
        }

        val storedResistor = resistors[terminal]
        if(storedResistor != null) {
            return storedResistor.offerPositive()
        }

        val resistor = Resistor()
        resistor.resistance = 1e8
        resistors[terminal] = resistor

        return resistor.offerPositive()
    }

    override fun build(map: ElectricalConnectivityMap) {
        super.build(map)

        resistors.forEach {
            it?.ground(NEGATIVE)
        }
    }

    override fun clearComponents() {
        resistors.fill(null)
    }

    /**
     * Gets the read potential for [channel].
     * If the channel is not connected, [Double.NaN] is returned. This will make the channel invisible later down the line.
     * */
    fun getPotential(channel: Int) : Double {
        check(channelRange.contains(channel)) {
            "Cannot read channel $channel of an oscilloscope with $channelRange"
        }

        val resistor = resistors[channel]
            ?: return Double.NaN

        return resistor.potential
    }
}

fun interface OscilloscopeSampleConsumer {
    fun consume(samples: FloatArray, timestamp: Double)
}

class OscilloscopeCell(ci: CellCreateInfo, specification: OscilloscopeSpecification) : Cell(ci) {
    @SimObject
    val oscilloscope = OscilloscopeObject(this, specification)

    @Node
    val grid = GridNode(this)

    // For attaching real-time timestamps onto the columns.
    val timer = Stopwatch()

    private var listener: OscilloscopeSampleConsumer? = null

    override fun subscribe(subscribers: SubscriberCollection) {
        super.subscribe(subscribers)
        subscribers.addPost(this::sampleAndRaiseEvent)
    }

    private fun sampleAndRaiseEvent(dt: Double, phase: SubscriberPhase) {
        val consumer = listener
            ?: return

        val buffer = FloatArray(oscilloscope.specification.channelCount)

        for (i in 0 until oscilloscope.specification.channelCount) {
            buffer[i] = oscilloscope.getPotential(i).toFloat()
        }

        consumer.consume(buffer, !timer.total)
    }

    fun bind(consumer: OscilloscopeSampleConsumer) {
        listener = consumer
    }

    fun unbind() {
        listener = null
    }
}

/**
 * @param desiredSize The desired size of the buffer, in samples.
 * @param maxSize Hard cap on the size. If samples aren't consumed in time, the buffer will discard the oldest samples so this threshold isn't broken.
 * @param dtControl The time interval of the control loop. The time interval at which [extractOrNull] is called should ideally be much larger than this.
 * */
class OscilloscopeJitterBuffer(
    val desiredSize: Int,
    val maxSize: Int,
    val kP: Double,
    val kI: Double,
    val dtControl: Double = 1 / 30.0
) {
    private val obj = Any()
    private val queue = ArrayDeque<FloatArray>()

    val size get() = queue.size

    private val extractionWatch = Stopwatch()
    private val controlWatch = Stopwatch()

    private var nextPlayTime = desiredSize * CellGraph.DT

    var playRate = 1.0 / CellGraph.DT
        private set

    private var int = 0.0

    var latestSet : FloatArray? = null
        private set

    fun insertMessage(samples: FloatArray, serverTimeStamp: Double) {
        synchronized(obj) {
            while(queue.size >= maxSize) {
                LOG.warn("Dropping unrendered oscilloscope sample $serverTimeStamp")
                queue.removeFirst()
            }

            queue.add(samples)
        }
    }

    fun extractOrNull() : FloatArray? {
        val extractionTime = !extractionWatch.total

        if(extractionTime < nextPlayTime) {
            control()
            return null
        }

        var samples: FloatArray? = null
        synchronized(obj) {
            if(queue.isNotEmpty()) {
                samples = queue.removeFirst()
            }
        }

        if(samples != null) {
            latestSet = samples
        }

        nextPlayTime += 1.0 / playRate
        control()

        return samples
    }

    private fun control() {
        if(controlWatch.total >= dtControl) {
            controlWatch.resetTotal()

            val error = (desiredSize - size).toDouble()

            int += error * dtControl
            val deltaRate = kP * error + kI * int

            playRate -= deltaRate
            playRate = playRate.coerceIn(1.0, 1.0 / CellGraph.DT)
        }
    }
}

class OscilloscopePart(ci: PartCreateInfo, val specification: OscilloscopeSpecification) :
    GridCellPart<OscilloscopeCell>(ci, Content.BASIC_TWO_CHANNEL_OSCILLOSCOPE_CELL.get()),
    AdditionalRenderingPart,
    ComponentDisplay
{
    val channel0 = defineCellBoxTerminalBB(
        0.375, 0.1, 6.0,
        0.625, 0.5, 0.5,
        highlightColor = specification.palette.colorsInt[0],
        categories = listOf(GridMaterialCategory.SignalGrid)
    )

    val channel1 = defineCellBoxTerminalBB(
        0.375, 0.1, 9.475,
        0.625, 0.5, 0.5,
        highlightColor = specification.palette.colorsInt[1],
        categories = listOf(GridMaterialCategory.SignalGrid)
    )

    @ClientOnly
    private class RenderState(horizonColumns: Int, channels: Int) {
        init {
            requireIsOnRenderThread {
                "Tried to create oscilloscope render state on non-render thread"
            }
        }

        val texture = OscilloscopeTexture(
            resource("oscilloscope_${UUID.randomUUID()}"),
            horizonColumns,
            channels
        )

        val buffer = OscilloscopeJitterBuffer(
            25,
            1000,
            0.035,
            0.0085
        )

        fun close() {
            texture.close()
        }
    }

    @ServerOnly // Saved in NBT
    private var timeWindow = specification.maxWindow / 2

    // Initialize on render thread (first call to [levelRender]
    @ClientOnly
    private var renderStateImpl : RenderState? = null

    //#region Rendering Only

    @ClientOnly
    private fun destroyRenderState() {
        if(placement.level.isClientSide) {
            renderStateImpl?.close()
        }
    }

    override fun onUnloaded() {
        super.onUnloaded()
        destroyRenderState()
    }

    override fun onBroken() {
        super.onBroken()
        destroyRenderState()
    }

    override fun levelRender(context: AdditionalRenderingPart.Context) {
        val renderState = this.renderStateImpl
            ?: return

        while (true) {
            val newSamples = renderState.buffer.extractOrNull()

            if(newSamples != null) {
                renderState.texture.writeColumnAndUpload(newSamples)
            }
            else {
                break
            }
        }

        val poseStack = context.poseStack

        poseStack.pushPose()

        val transVert = 0.075 / 16.0 + 0.001

        poseStack.translate(
            placement.face.stepX.toFloat() * transVert,
            placement.face.stepY.toFloat() * transVert,
            placement.face.stepZ.toFloat() * transVert
        )

        val (dx, dy, dz) = partOffsetTable[placement.face.get3DDataValue()]
        poseStack.translate(dx, dy, dz)
        poseStack.mulPose(placement.face.rotationFast)
        poseStack.mulPose(Rotation3d.exp(Vector3d.unitY * placement.facing.angle))
        // x = left-right, z = up-down (neg = up)
        poseStack.translate(-0.063f, 0.0f, 0.01f)
        // z = height, x = width
        poseStack.scale(0.6f, 1.0f, 0.4f);
        poseStack.mulPose(Rotation3d.exp(Vector3d.unitX * PI / 2.0))

        val texLoc = renderState.texture.resourceId

        RenderSystem.setShaderTexture(0, texLoc)

        val latestSamples = renderState.buffer.latestSet ?: FloatArray(renderState.texture.channelCount)
        OscilloscopeShader.bindAndSetup(
            0.02f,
            1.0f,
            specification.palette,
            renderState.texture,
            latestSamples
        )

        dispatchOscilloscopeQuad(poseStack)
        OscilloscopeShader.unbind()

        submitOscilloscopeText(poseStack, context.buffer, latestSamples, specification.palette)

        poseStack.popPose()
    }

    override fun shouldRenderOffScreen(): Boolean {
        return true
    }

    // One draw call per oscilloscope, it's fine
    private fun dispatchOscilloscopeQuad(poseStack: PoseStack) {
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

    private fun submitOscilloscopeText(poseStack: PoseStack, bufferSource: MultiBufferSource, samples: FloatArray, palette: OscilloscopePalette) {
        val font = Minecraft.getInstance().font

        val scale = 0.007f
        val verticalSpacing = 0.1f

        samples.indices.forEach { i ->
            poseStack.pushPose()

            var sample = samples[i].toDouble()

            if(sample.approxEq(0.0, 1e-8)){
                sample = 0.0
            }

            poseStack.translate(-0.5f, -0.5f + i * verticalSpacing, 0.0f)
            poseStack.scale(scale, scale, scale)

            val text = if(sample.isNaN()) {
                "N/A"
            } else {
                var text = "${sample.rounded(3)}V"

                if(snzi(sample) == 1) {
                    text = "+$text"
                }

                text
            }

            val channelColor = palette.colorsInt[i]

            val textColor = MyColor((channelColor.a * 0.8).toInt(), channelColor.r, channelColor.g, channelColor.b)

            font.drawInBatch(
                text,
                0.0f,
                0.0f,
                textColor.data,
                false,
                poseStack.last().pose(),
                bufferSource,
                Font.DisplayMode.SEE_THROUGH,
                0,
                15728880
            )

            poseStack.popPose()
        }
    }

    //#endregion

    @ServerOnly
    override fun getServerSaveTag(): CompoundTag {
        val tag = super.getServerSaveTag()
        tag.putInt(TIME_WINDOW, timeWindow)
        return tag
    }

    @ServerOnly
    override fun loadServerSaveTag(tag: CompoundTag) {
        super.loadServerSaveTag(tag)
        timeWindow = tag.getInt(TIME_WINDOW).coerceIn(specification.minWindow, specification.maxWindow)
    }

    @ServerOnly
    override fun onCellAcquired() {
        super.onCellAcquired()
        cell.bind { samples, timestamp ->
            sendBulkPacket(UpdateMessage(samples, timestamp, timeWindow))
        }
    }

    @ServerOnly
    override fun onCellReleased() {
        super.onCellReleased()
        cell.unbind()
    }

    override fun registerPackets(builder: PacketHandlerBuilder) {
        super.registerPackets(builder)
        builder.withHandler<UpdateMessage>(this::handleMessage)
    }

    private fun handleMessage(message: UpdateMessage) {
        var currentState = renderStateImpl

        if(currentState == null) {
            currentState = RenderState(message.window, specification.channelCount)
            renderStateImpl = currentState
        }
        else {
            if(currentState.texture.columnCount != message.window) {
                currentState.close()
                currentState = RenderState(message.window, specification.channelCount)
                renderStateImpl = currentState
            }
        }

        currentState.buffer.insertMessage(message.samples, message.timestamp)
    }

    @Serializable
    private class UpdateMessage(val samples: FloatArray, val timestamp: Double, val window: Int)

    override fun submitDisplay(builder: ComponentDisplayList) {
        for(i in 0 until specification.channelCount) {
            builder.debugInIDE { "Ch$i: ${cell.oscilloscope.getPotential(i).rounded()}" }
        }
    }

    companion object {
        private const val TIME_WINDOW = "timeWindow"
    }
}
