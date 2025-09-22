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
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraftforge.client.event.RegisterShadersEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.network.NetworkHooks
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Rotation3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.Vector4d
import org.ageseries.libage.mathematics.nz
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.mathematics.snzi
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.NEGATIVE
import org.ageseries.libage.sim.electrical.mna.component.Resistor
import org.ageseries.libage.utils.Stopwatch
import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import org.eln2.mc.OnClientThread
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
import org.eln2.mc.common.containers.MyAbstractContainerScreen
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.common.grids.GridMaterialCategory
import org.eln2.mc.common.grids.GridNode
import org.eln2.mc.common.network.serverToClient.PacketHandlerBuilder
import org.eln2.mc.common.parts.foundation.GridCellPart
import org.eln2.mc.common.parts.foundation.PartCreateInfo
import org.eln2.mc.common.parts.foundation.PartUseInfo
import org.eln2.mc.common.parts.foundation.stillValid
import org.eln2.mc.common.parts.foundation.writeGuiData
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
     * @param alpha Factor for the output alpha.
     * @param texture The raw data buffer.
     * @param mask The latest samples. Channels with NaN will be hidden.
     * */
    fun bindAndSetup(
        alpha: Float,
        texture: OscilloscopeTexture,
        mask: FloatArray,
        specification: OscilloscopeSpecification,
        aspect: Float,
        isGui: Boolean
    ) {
        RenderSystem.assertOnRenderThread()

        val shader = shader ?: error("Oscilloscope shader didn't load")

        val thicknessFactor = if(isGui) specification.thicknessFactorGui else 1.0f

        RenderSystem.setShader { shader }
        shader.safeGetUniform("Sampler0").set(texture.glTex.id)
        shader.safeGetUniform("u_writeX").set(texture.writeX.toFloat())
        shader.safeGetUniform("u_count").set(texture.count.toFloat())
        shader.safeGetUniform("u_thickness").set(specification.channelThickness * thicknessFactor)
        shader.safeGetUniform("u_alpha").set(alpha)
        specification.palette.setUniform(shader.safeGetUniform("u_channelColors"), mask)

        shader.safeGetUniform("u_axisInfo").set(
            FloatArray(7).also {
                val (x, y, z, w) = specification.axisColor

                it[0] = specification.horizontalCuts.toFloat()
                it[1] = aspect
                it[2] = specification.axisThickness * thicknessFactor
                it[3] = x.toFloat()
                it[4] = y.toFloat()
                it[5] = z.toFloat()
                it[6] = w.toFloat()
            }
        )
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
 * @param channelThickness The line thickness of the waveform.
 * @param axisThickness The line thickness of the vertical and horizontal axis.
 * @param axisColor The color of the divisions.
 * @param horizontalCuts The number of horizontal lines to draw. Vertical cuts are adjusted based on aspect ratio.
 * @param thicknessFactorGui Factor for the thickness when the GUI screen is rendered. The in-world thicknesses may look excessive in GUI.
 * */
data class OscilloscopeSpecification(
    val channelCount: Int,
    val minWindow: Int,
    val maxWindow: Int,
    val palette: OscilloscopePalette,
    val channelThickness: Float,
    val axisThickness: Float,
    val axisColor: Vector4d,
    val horizontalCuts: Int,
    val thicknessFactorGui : Float
)

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
 * Double-buffer for oscilloscope samples. It handles de-jitter and de-batching the samples coming over the network.
 * @param desiredRenderingSize The desired size of the render buffer, in samples.
 * @param maxBufferSizeAbsolute Hard cap on the size. If samples aren't consumed in time, the buffer will discard the oldest samples so this threshold isn't broken (should never be reached).
 * @param kE Control parameter.
 * @param maxDebufferQueueSize The max size of the network smoothing loop. Ideally, the queue is never filled above say ~10 packets.
 * @param samplingRateAlpha Smoothing parameter for calculating the sampling rate.
 * */
class OscilloscopeBuffer(
    val desiredRenderingSize: Int,
    val maxBufferSizeAbsolute: Int,
    val kE: Double = 30.0,
    val maxDebufferQueueSize: Int = 15,
    val samplingRateAlpha: Double = 0.1
) {
    private val obj = Any()

    /**
     * The server packets are moved here. We get batches of ~5 arrays because the bulk packets are flushed per game tick.
     * */
    private val debufferingQueue = ArrayDeque<FloatArray>()

    /**
     * The actual queue for rendering is this. Samples get copied from [debufferingQueue] to [renderQueue] at ~the sampling rate.
     * The control loop then uses the size of this queue to calculate the error.
     * */
    private val renderQueue = ArrayDeque<FloatArray>()

    private val debufferWatch = Stopwatch()
    private var debufferingTimeAccumulator = 0.0

    private val extractionWatch = Stopwatch()
    var extractionTimeAccumulator = 0.0
        private set

    private var lastServerTime = -1.0
    private var samplingRateEma = 1.0 / CellGraph.DT
    private var nextPlayTime = desiredRenderingSize * CellGraph.DT

    var playRate = 1.0 / CellGraph.DT
        private set

    /**
     * The latest samples removed by [consume].
     * */
    var latestRemovedSet : FloatArray? = null
        private set

    /**
     * Inserts samples into the buffer. This should ideally be called every game tick with the batch of ~5 samples from the simulation.
     * */
    fun insertMessage(samples: FloatArray, serverTimeStamp: Double) {
        synchronized(obj) {
            while (renderQueue.size >= maxBufferSizeAbsolute) {
                LOG.warn("Dropping unrendered oscilloscope samples from render queue $serverTimeStamp")
                renderQueue.removeFirst()
            }

            while(debufferingQueue.size >= maxBufferSizeAbsolute) {
                LOG.warn("Dropping unrendered oscilloscope samples from debuffer queue $serverTimeStamp")
                debufferingQueue.removeFirst()
            }

            debufferingQueue.addLast(samples)
        }

        if(lastServerTime != -1.0) {
            val dt = (serverTimeStamp - lastServerTime)

            if(dt > CellGraph.DT * 0.8 && dt < 10.0) {
                samplingRateEma = samplingRateAlpha * (1.0 / dt) + (1.0 - samplingRateAlpha) * samplingRateEma
            }
        }

        lastServerTime = serverTimeStamp
    }

    /**
     * Copies samples from the debuffering queue to the rendering queue at approx. 1.0 / samplingRateEma.
     * Copies samples forcefully if the buffer is at max capacity.
     * */
    private fun debuffer() {
        /**
         * Copies a sample from the debuffering queue to the rendering queue.
         * */
        fun copySample() {
            synchronized(obj) {
                if(debufferingQueue.isNotEmpty()) {
                    renderQueue.add(debufferingQueue.removeFirst())
                }
            }
        }

        debufferingTimeAccumulator += !debufferWatch.sample()

        val samplingInterval = 1.0 / samplingRateEma

        // Copy samples nominally:
        while (debufferingTimeAccumulator >= samplingInterval) {
            debufferingTimeAccumulator -= samplingInterval
            copySample()
        }

        var forcedSamples = 0
        // Safeguard against weird stuff:
        while(debufferingQueue.size > maxDebufferQueueSize) {
            copySample()
            ++forcedSamples
        }

        if(forcedSamples > 0) {
            LOG.debug("Debuffer queue desaturated $forcedSamples samples")
        }
    }

    /**
     * Time-invariant [playRate] control. It is very stable and doesn't need real tuning.
     * It works by setting the play rate to the sampling rate plus an offset proportional to the difference in desired delay and actual delay.
     * */
    private fun control() {
        val samplingRate = samplingRateEma
        val samplingPeriod = (1.0.nz() / samplingRate.nz())
        val desiredDepthSeconds = desiredRenderingSize * samplingPeriod
        val depthSeconds = renderQueue.size * samplingPeriod

        val maxRateDelta = 10.0

        playRate = (1.0 / samplingPeriod) -kE * (desiredDepthSeconds - depthSeconds)
        playRate = playRate.coerceIn(samplingRate - maxRateDelta, samplingRate + maxRateDelta).coerceAtLeast(0.1)
    }

    private fun removeSet() : FloatArray? {
        extractionTimeAccumulator += !extractionWatch.sample()

        if(extractionTimeAccumulator < nextPlayTime) {
            return null
        }

        extractionTimeAccumulator -= nextPlayTime

        var hasResult = false
        synchronized(obj) {
            if(renderQueue.isNotEmpty()) {
                latestRemovedSet = renderQueue.removeFirst()
                hasResult = true
            }
        }

        nextPlayTime = 1.0 / playRate

        return if(hasResult) latestRemovedSet else null
    }

    /**
     * Gets the next column to insert into the rendering data. Called per-frame. Ideally, the frame rate should be very high.
     * Returns null if it's not time for that yet, or samples are simply not available.
     *
     * **P.S. Call this in a loop, exiting only when it returns null!**
     * */
    fun consume() : FloatArray? {
        debuffer()
        val result = removeSet()
        control()
        return result
    }
}

/**
 * Client-side data for the oscilloscope. Must be constructed on the render thread.
 * Creates the data texture for the specified [horizonColumns] and [channels], and also registers the GPU copy task.
 * */
@ClientOnly
class OscilloscopeClientSide(horizonColumns: Int, channels: Int) {
    init {
        requireIsOnRenderThread {
            "OscilloscopeClientSide#init"
        }

        OscilloscopeCopyManager.add(this)
    }

    private var closed = false

    val texture = OscilloscopeTexture(
        resource("oscilloscope_${UUID.randomUUID()}"),
        horizonColumns,
        channels
    )

    val buffer = OscilloscopeBuffer(25, 200)

    /**
     * Uploads the columns into GPU memory. Called by [OscilloscopeCopyManager].
     * */
    fun copyIntoGPUMemory() {
        requireIsOnRenderThread {
            "OscilloscopeClientSide#copyIntoGPUMemory"
        }

        if(closed) {
            return
        }

        while (true) {
            val newSamples = buffer.consume()

            if(newSamples != null) {
                texture.writeColumnAndUpload(newSamples)
            }
            else {
                break
            }
        }
    }

    fun close() {
        requireIsOnRenderThread {
            "OscilloscopeClientSide#close"
        }

        if(closed) {
            return
        }

        closed = true
        OscilloscopeCopyManager.remove(this)
        texture.close()
    }
}

/**
 * Because the block entity renderer is frustum culled, we can't rely on it to handle the de-queueing of samples.
 * So we subscribe to the render event instead.
 * */
object OscilloscopeCopyManager {
    private val tasks = HashSet<OscilloscopeClientSide>()

    @OnClientThread
    fun add(task: OscilloscopeClientSide) {
        requireIsOnRenderThread {
            "OscilloscopeCopyManager#add"
        }

        require(tasks.add(task)) {
            "Duplicate add oscilloscope task $task"
        }
    }

    @OnClientThread
    fun remove(task: OscilloscopeClientSide) {
        requireIsOnRenderThread {
            "OscilloscopeCopyManager#remove"
        }

        require(tasks.remove(task)) {
            "Invalid remove oscilloscope task $task"
        }
    }

    @OnClientThread
    fun execute(event: RenderLevelStageEvent) {
        if(event.stage != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return
        }

        tasks.forEach {
            it.copyIntoGPUMemory()
        }
    }
}

class OscilloscopePart(ci: PartCreateInfo, val specification: OscilloscopeSpecification) :
    GridCellPart<OscilloscopeCell>(ci, Content.BASIC_TWO_CHANNEL_OSCILLOSCOPE_CELL.get()),
    AdditionalRenderingPart,
    ComponentDisplay,
    MenuProvider
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

    @ServerOnly // Saved in NBT
    private var timeWindow = specification.maxWindow / 2

    // Initialize on render thread (first call to [levelRender]
    @ClientOnly
    private var renderStateImpl : OscilloscopeClientSide? = null

    //#region Rendering Only

    override fun onUnloaded() {
        super.onUnloaded()
        renderStateImpl?.close()
    }

    override fun onBroken() {
        super.onBroken()
        renderStateImpl?.close()
    }

    override fun levelRender(context: AdditionalRenderingPart.Context) {
        val renderState = this.renderStateImpl
            ?: return

        val poseStack = context.poseStack

        poseStack.pushPose()

        val transVert = 0.075 / 16.0 + 0.001

        poseStack.translate(
            placement.face.stepX.toFloat() * transVert,
            placement.face.stepY.toFloat() * transVert,
            placement.face.stepZ.toFloat() * transVert
        )

        val sizeX = 0.6f
        val sizeZ = 0.4f

        val (dx, dy, dz) = partOffsetTable[placement.face.get3DDataValue()]
        poseStack.translate(dx, dy, dz)
        poseStack.mulPose(placement.face.rotationFast)
        poseStack.mulPose(Rotation3d.exp(Vector3d.unitY * placement.facing.angle))
        // x = left-right, z = up-down (neg = up)
        poseStack.translate(-0.063f, 0.0f, 0.01f)
        // z = height, x = width
        poseStack.scale(sizeX, 1.0f, sizeZ);
        poseStack.mulPose(Rotation3d.exp(Vector3d.unitX * PI / 2.0))

        val texLoc = renderState.texture.resourceId

        RenderSystem.setShaderTexture(0, texLoc)

        val latestSamples = renderState.buffer.latestRemovedSet ?: FloatArray(renderState.texture.channelCount)

        OscilloscopeShader.bindAndSetup(
            1.0f,
            renderState.texture,
            latestSamples,
            specification,
            sizeX / sizeZ,
            false
        )

        dispatchOscilloscopeQuad(poseStack)
        OscilloscopeShader.unbind()

        submitOscilloscopeText(poseStack, context.buffer, latestSamples, specification.palette)

        poseStack.popPose()
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

            poseStack.translate(-0.5f, -0.5f + i * verticalSpacing, -0.01f) // Z is above the quad
            poseStack.scale(scale, scale, scale)

            val text = if(sample.isNaN()) {
                "N/A"
            } else {
                var text = "${sample.rounded(3)}"

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
                Font.DisplayMode.POLYGON_OFFSET,
                MyColor(50, 255, 255, 255).data,
                15728880
            )

            poseStack.popPose()
        }
    }

    //#endregion

    //#region GUI

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if(placement.level.isClientSide) {
            return InteractionResult.PASS
        }

        NetworkHooks.openScreen(context.player as ServerPlayer, this) { buf ->
            this.writeGuiData(buf)
        }

       return InteractionResult.SUCCESS
    }

    override fun getDisplayName(): Component = TITLE

    class OscilloscopeMenu(pContainerId: Int, val part: OscilloscopePart) : AbstractContainerMenu(Content.FLAT_OSCILLOSCOPE_MENU.get(), pContainerId) {
        override fun quickMoveStack(pPlayer: Player, pIndex: Int, ): ItemStack = ItemStack.EMPTY
        override fun stillValid(pPlayer: Player) = part.stillValid(pPlayer)
    }

    class OscilloscopeScreen(menu: OscilloscopeMenu, playerInventory: Inventory, title: Component) : MyAbstractContainerScreen<OscilloscopeMenu>(menu, playerInventory, title) {
        override fun renderLabels(pGuiGraphics: GuiGraphics, pMouseX: Int, pMouseY: Int) {
            // No-op
        }

        override fun renderBg(pGuiGraphics: GuiGraphics, pPartialTick: Float, pMouseX: Int, pMouseY: Int) {
            val part = menu.part

            val renderState = menu.part.renderStateImpl
                ?: return

            val poseStack = pGuiGraphics.pose()

            val corner = (min(pGuiGraphics.guiWidth(), pGuiGraphics.guiHeight()) * 0.05f).toInt()
            val sizeX = (pGuiGraphics.guiWidth() * 0.75f).toInt()
            val sizeY = (pGuiGraphics.guiHeight() - 2.0f * corner).toInt()

            // Renders oscilloscope screen background:
            pGuiGraphics.fillGradient(
                corner, corner,
                corner + sizeX, corner + sizeY,
                MyColor(50, 100, 100, 200).data,
                MyColor(25, 50, 50, 150).data
            )

            val font = Minecraft.getInstance().font


            val padLeft = pGuiGraphics.guiWidth() * 0.01f
            val padTop = pGuiGraphics.guiHeight() * 0.01f
            val verticalSpacing = pGuiGraphics.guiHeight() * 0.04f
            val textScale = min(pGuiGraphics.guiWidth(), pGuiGraphics.guiHeight()) / 514.0f * 2.0f

            // Renders sidebar background:
            pGuiGraphics.fill(
                (padLeft / 2.0f + corner + sizeX).toInt(),
                corner,
                (pGuiGraphics.guiWidth() - padLeft).toInt(),
                corner + sizeY,
                MyColor(100, 0, 0,0).data
            )

            renderState.buffer.latestRemovedSet?.also { samples ->
                samples.indices.forEach { i ->
                    poseStack.pushPose()

                    poseStack.translate(
                        padLeft + corner + sizeX,
                        padTop + corner + verticalSpacing * i,
                        0.0f
                    )

                    poseStack.scale(textScale, textScale, 1.0f)

                    var sample = samples[i].toDouble()

                    if(sample.approxEq(0.0, 1e-8)){
                        sample = 0.0
                    }

                    val text = "Channel ${(i + 1)}: " + if(sample.isNaN()) {
                        "N/A"
                    } else {
                        var text = "${sample.rounded(3)}"

                        if(snzi(sample) == 1) {
                            text = "+$text"
                        }

                        text
                    }

                    val channelColor = part.specification.palette.colorsInt[i]

                    val textColor = MyColor((channelColor.a * 0.8).toInt(), channelColor.r, channelColor.g, channelColor.b)

                    pGuiGraphics.drawString(
                        font,
                        text,
                        0,
                        0,
                        textColor.data
                    )

                    poseStack.popPose()
                }
            }

            //#region Oscilloscope

            RenderSystem.setShaderTexture(0, renderState.texture.resourceId)

            val latestSamples = renderState.buffer.latestRemovedSet ?: FloatArray(renderState.texture.channelCount)

            OscilloscopeShader.bindAndSetup(
                1.0f,
                renderState.texture,
                latestSamples,
                part.specification,
                sizeX.toFloat() / sizeY.toFloat(),
                true
            )

            dispatchScope(
                poseStack,
                corner.toFloat(),
                corner.toFloat(),
                sizeX.toFloat(),
                sizeY.toFloat()
            )

            OscilloscopeShader.unbind()

            //#endregion
        }

        private fun dispatchScope(poseStack: PoseStack, x: Float, y: Float, width: Float, height: Float) {
            RenderSystem.enableBlend()

            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA)

            val pose = poseStack.last().pose()
            val tesselator = Tesselator.getInstance()
            val builder = tesselator.builder

            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
            builder.vertex(pose, x, y + height, 0f).uv(0f, 1f).endVertex()
            builder.vertex(pose, x + width, y + height, 0f).uv(1f, 1f).endVertex()
            builder.vertex(pose, x + width, y, 0f).uv(1f, 0f).endVertex()
            builder.vertex(pose, x, y, 0f).uv(0f, 0f).endVertex()
            tesselator.end()
        }
    }

    override fun createMenu(pContainerId: Int, pPlayerInventory: Inventory, pPlayer: Player, ): AbstractContainerMenu {
        return OscilloscopeMenu(pContainerId, this)
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

    @OnClientThread
    private fun handleMessage(message: UpdateMessage) {
        var currentState = renderStateImpl

        if(currentState == null) {
            currentState = OscilloscopeClientSide(message.window, specification.channelCount)
            renderStateImpl = currentState
        }
        else {
            if(currentState.texture.columnCount != message.window) {
                // Re-create the buffers with the new settings:
                currentState.close()
                currentState = OscilloscopeClientSide(message.window, specification.channelCount)
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
        private val TITLE = Component.translatable("screen.$MODID.oscilloscope")
    }
}
