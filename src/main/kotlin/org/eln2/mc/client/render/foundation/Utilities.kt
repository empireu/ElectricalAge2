@file:Suppress("MemberVisibilityCanBePrivate")

package org.eln2.mc.client.render.foundation

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import it.unimi.dsi.fastutil.doubles.Double2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.client.model.data.ModelData
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.MutableSetMapMultiMap
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.mathematics.*
import org.ageseries.libage.mathematics.geometry.*
import org.ageseries.libage.sim.STANDARD_TEMPERATURE
import org.eln2.mc.client.render.foundation.ThermalTint.Companion.DEFAULT
import org.eln2.mc.extensions.bind
import org.eln2.mc.extensions.cast
import org.eln2.mc.requireIsOnRenderThread
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.io.FileWriter
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

class ThermalTintBuilder {
    var coldTint = MyColor(1f, 1f, 1f, 1f)
    var hotTint = MyColor( 1f, 1f, 0.1f, 0.1f)
    var coldTemperature = STANDARD_TEMPERATURE
    var hotTemperature = Quantity(800.0, CELSIUS)

    fun build(): ThermalTint {
        return ThermalTint(
            coldTint,
            hotTint,
            coldTemperature,
            hotTemperature,
        )
    }
}

class ThermalTint(
    val coldTint: MyColor,
    val hotTint: MyColor,
    val coldTemperature: Quantity<Temperature>,
    val hotTemperature: Quantity<Temperature>,
) {
    companion object {
        val DEFAULT = ThermalTintBuilder().build()

        /**
         * [DEFAULT], but with the alpha of the cold tint set to 0 (meant to be used with the light override instances).
         * */
        val DEFAULT_LIGHT_OVERRIDE = ThermalTint(
            MyColor(0, DEFAULT.coldTint.r, DEFAULT.coldTint.g, DEFAULT.coldTint.b),
            DEFAULT.hotTint,
            DEFAULT.coldTemperature,
            DEFAULT.hotTemperature
        )
    }

    fun evaluate(temperature: Quantity<Temperature>) =
        MyColor.lerp(
            from = coldTint,
            to = hotTint,
            blend = map(
                temperature.value.coerceIn(
                    !coldTemperature,
                    !hotTemperature
                ),
                !coldTemperature,
                !hotTemperature,
                0.0,
                1.0
            ).toFloat()
        )

    /**
     * Evaluates the color as R, G, B and light override.
     * @param temperature The temperature of the material.
     * @param light The lower bound of the light value [[0, 15]]
     * @return The tint color to be rendered.
     * */
    fun evaluateRGBL(temperature: Quantity<Temperature>, light: Double = 0.0): MyColor {
        val rgb = evaluate(temperature)

        return MyColor(
            max(
                map(
                    light,
                    0.0,
                    15.0,
                    0.0,
                    255.0
                ),
                map(
                    !temperature,
                    !coldTemperature,
                    !hotTemperature,
                    0.0,
                    255.0
                )
            ).toInt().coerceIn(0, 255),
            rgb.r,
            rgb.g,
            rgb.b
        )
    }
}

fun VertexConsumer.eln2SubmitUnshadedBakedModelQuads(
    renderType: RenderType,
    pose: PoseStack.Pose, // this is not a pose, Minecraft, because it is not in SE(3)
    model: BakedModel,
    r: Float,
    g: Float,
    b: Float,
    a: Float,
    packedLight: Int = 15728880,
    overlayTexture: Int = OverlayTexture.NO_OVERLAY,
) {
    requireIsOnRenderThread {
        "eln2SubmitUnshadedBakedModelQuads"
    }

    val pQuads = model.getQuads(
        null,
        null,
        RandomSource.create(),
        ModelData.EMPTY,
        renderType
    )

    for (bakedQuad in pQuads) {
        this.putBulkData(
            pose,
            bakedQuad,
            r, g, b, a,
            packedLight, overlayTexture,
            true
        )
    }
}

fun VertexConsumer.eln2SubmitUnshadedBakedModelQuads(
    renderType: RenderType,
    pose: PoseStack.Pose, // this is not a pose, Minecraft, because it is not in SE(3)
    model: BakedModel,
    rgba: MyColor,
    packedLight: Int = 15728880,
    overlayTexture: Int = OverlayTexture.NO_OVERLAY,
) = this.eln2SubmitUnshadedBakedModelQuads(renderType, pose, model, rgba.rF, rgba.gF, rgba.bF, rgba.aF, packedLight, overlayTexture)

fun VertexConsumer.eln2SubmitVoxelShapeLines(pose: PoseStack.Pose, shape: VoxelShape, r: Float, g: Float, b: Float, a: Float) {
    shape.forAllEdges { pX1: Double, pY1: Double, pZ1: Double, pX2: Double, pY2: Double, pZ2: Double ->
        var nx = (pX2 - pX1).toFloat()
        var ny = (pY2 - pY1).toFloat()
        var nz = (pZ2 - pZ1).toFloat()

        val norm = 1f / sqrt(nx * nx + ny * ny + nz * nz)

        nx *= norm
        ny *= norm
        nz *= norm

        val poseModel = pose.pose()
        val poseNormal = pose.normal()

        this.vertex(poseModel, pX1.toFloat(), pY1.toFloat(), pZ1.toFloat())
            .color(r, g, b, a)
            .normal(poseNormal, nx, ny, nz)
            .endVertex()

        this.vertex(poseModel, pX2.toFloat(), pY2.toFloat(), pZ2.toFloat())
            .color(r, g, b, a)
            .normal(poseNormal, nx, ny, nz)
            .endVertex()
    }
}

fun VertexConsumer.eln2SubmitAABBLines(pose: PoseStack.Pose, aabb: BoundingBox3d, r: Float, g: Float, b: Float, a: Float) {
    this.eln2SubmitVoxelShapeLines(
        pose,
        Shapes.create(aabb.cast()),
        r, g, b, a
    )
}

fun VertexConsumer.eln2SubmitAABBLines(pose: PoseStack.Pose, aabb: BoundingBox3d, rgba: MyColor) {
    this.eln2SubmitAABBLines(
        pose,
        aabb,
        rgba.rF, rgba.gF, rgba.bF, rgba.aF
    )
}

fun VertexConsumer.eln2SubmitOBBAtLevelStage(stack: PoseStack, obb: OrientedBoundingBox3d, rgba: MyColor, camX: Double, camY: Double, camZ: Double) {
    stack.pushPose()

    stack.translate(-camX, -camY, -camZ)
    stack.translate(obb.transform.translation.x, obb.transform.translation.y, obb.transform.translation.z)
    stack.mulPose(obb.transform.rotation.cast())

    val min = -obb.halfSize
    val max = +obb.halfSize

    LevelRenderer.renderLineBox(
        stack,
        this,
        min.x, min.y, min.z,
        max.x, max.y, max.z,
        rgba.rF, rgba.gF, rgba.bF, rgba.aF,
        rgba.rF, rgba.gF, rgba.bF
    )

    stack.popPose()
}

fun VertexConsumer.eln2SubmitOBBAtLevelStage(stack: PoseStack, obb: OrientedBoundingBox3d, rgba: MyColor, camera: Camera) = this.eln2SubmitOBBAtLevelStage(
    stack, obb, rgba,
    camera.position.x, camera.position.y, camera.position.z
)

fun eln2UploadSubImage2DRGBA(image: NativeImage, textureId: ResourceLocation, x: Int, y: Int, w: Int, h: Int) {
    RenderSystem.assertOnRenderThread()

    MemoryStack.stackPush().use { stack ->
        val buf = stack.malloc(w * h * 4)

        var row = 0
        while (row < h) {
            var column = 0
            while (column < w) {
                buf.putInt(image.getPixelRGBA(x + column, y + row))
                column++
            }

            row++
        }

        buf.flip()

        GL11.glPixelStorei(
            GL11.GL_UNPACK_ALIGNMENT,
            1
        )

        GL11.glTexSubImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            x,
            y,
            w,
            h,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            buf
        )
    }
}

class ObjWriter {
    val file = FileWriter(File("test.obj"), false)

    private fun getValue(d: Double) = d.toBigDecimal().toPlainString()
    private fun getValue(v: Vector3d) = "${getValue(v.x)} ${getValue(v.y)} ${getValue(v.z)}"

    fun vert(v: Vector3d) = file.appendLine("v ${getValue(v)}")
    fun vert(x: Double, y: Double, z: Double) = vert(Vector3d(x, y, z))
    fun vert(v: List<Vector3d>) = file.appendLine("v ${v.joinToString(" ") { getValue(it) }}")
    fun indices(indices: List<Int>) = file.appendLine("f ${indices.map { it + 1 }.joinToString(" ")}")

    fun close() {
        file.close()
    }
}

fun polygralScan(ref: Vector3d, vectors: List<Vector3d>): Vector3d {
    fun get(i: Int) = if (i == vectors.size) vectors[0] else vectors[i]

    var sum = Vector3d.Companion.zero

    vectors.indices.forEach {
        sum += ((get(it) - ref) x (get(it + 1) - ref)) * 0.5
    }

    return sum
}

data class IndexedTri(val a: Int, val b: Int, val c: Int) {
    val indices get() = listOf(a, b, c)
}

data class IndexedQuad(val a: Int, val b: Int, val c: Int, val d: Int) {
    val indices get() = listOf(a, b, c, d)
    fun rewind() = IndexedQuad(a, d, c, b)
}

interface Quads
interface Triangles

data class MeshBuilder<Vertex, Primitive>(val vertices: ArrayList<Vertex>, val edges: MutableSetMapMultiMap<Int, Int>) {
    constructor() : this(ArrayList(), MutableSetMapMultiMap())

    fun <P> reparam() = MeshBuilder<Vertex, P>(ArrayList(vertices), edges.bind())

    fun addVertex(v: Vertex): Int {
        val idx = vertices.size
        vertices.add(v)
        return idx
    }

    fun addEdge(a: Int, b: Int) {
        edges[a] = b
        edges[b] = a
    }

    fun cycleScan(vert: Int, targetLength: Int): ArrayList<IntArrayList> {
        val visits = Int2IntOpenHashMap()
        val cycles = ArrayList<IntArrayList>()

        fun levelScan(
            remaining: Int,
            actualVert: Int,
            actualVertSource: Int,
            cycleVert: Int,
            user: (IntArrayList) -> Unit,
        ) {
            visits.put(actualVert, actualVertSource)

            if (remaining > 0) {
                for (actualVertConn in edges[actualVert]) {
                    if (!visits.containsKey(actualVertConn)) {
                        levelScan(
                            remaining = remaining - 1,
                            actualVert = actualVertConn,
                            actualVertSource = actualVert,
                            cycleVert,
                            user
                        )
                    }
                }

                return
            }

            if (edges[actualVert].contains(cycleVert)) {
                val pathway = IntArrayList(targetLength)

                var current = actualVert

                while (true) {
                    pathway.add(current)
                    current = visits.get(current)

                    if (current == cycleVert) {
                        user(pathway)
                        break
                    }
                }
            }

            visits.remove(actualVert)
        }

        levelScan(
            remaining = targetLength - 1,
            actualVert = vert,
            actualVertSource = vert,
            cycleVert = vert,
            cycles::add
        )

        return cycles
    }
}

fun <Vertex> MeshBuilder<Vertex, Quads>.quadScan(consumer: (IndexedQuad) -> Unit) {
    val visited = HashSet<IndexedQuad>()
    val buffer = IntArray(4)

    for (vertIdx in this.vertices.indices) {
        this.cycleScan(vertIdx, 4).forEach { loop ->
            val l0 = loop.getInt(0)
            val l1 = loop.getInt(1)
            val l2 = loop.getInt(2)

            buffer[0] = l0
            buffer[1] = l1
            buffer[2] = l2
            buffer[3] = vertIdx

            buffer.sort()

            if (visited.add(IndexedQuad(buffer[0], buffer[1], buffer[2], buffer[3]))) {
                consumer(IndexedQuad(vertIdx, l0, l1, l2))
            }
        }
    }
}

data class Sketch(val vertices: List<Vector2d>)

fun sketchCircle(vertices: Int, radius: Double = 1.0): Sketch {
    require(vertices >= 3)

    val results = ArrayList<Vector2d>()

    repeat(vertices) { vertIdx ->
        val angle = vertIdx
            .toDouble()
            .mappedTo(
                srcMin = 0.0,
                srcMax = vertices.toDouble(),
                dstMin = -PI,
                dstMax = PI
            )

        results.add(Rotation2d.Companion.exp(angle).direction * radius)
    }

    return Sketch(results)
}

fun extrudeSketchFrenet(sketch: Sketch, spline: Spline3d, samples: List<Double>): SketchExtrusion {
    val f0 = spline.frenetPose(samples.first())
    val f1 = spline.frenetPose(samples.last())
    return extrudeSketch(sketch, spline, samples, f0, f1)
}

// RMF assumes no "twists and turns", fix in the future?
fun extrudeSketch(sketch: Sketch, spline: Spline3d, samples: List<Double>, f0: Pose3d, f1: Pose3d): SketchExtrusion {
    val builder = MeshBuilder<Vector3dParametric, Quads>()

    val y0 = samples.first()
    val y1 = samples.last()

    val increment = f1.rotation / f0.rotation

    val rmf = ArrayList<Pose3dParametric>(samples.size)
    val rmfLookup = Double2ObjectOpenHashMap<Pose3d>(samples.size)

    fun extrude(previousMapSketchModel: Int2IntOpenHashMap?, t: Double): Int2IntOpenHashMap {
        val mapSketchModel = Int2IntOpenHashMap()

        val pose = Pose3d(
            spline.evaluate(t),
            f0.rotation * increment(
                map(t, y0, y1, 0.0, 1.0)
            )
        )

        rmf.add(Pose3dParametric(pose, t))
        rmfLookup.put(t, pose)

        sketch.vertices.forEachIndexed { vertexIdSketch, (xSketch, ySketch) ->
            mapSketchModel.put(
                vertexIdSketch,
                builder.addVertex(
                    Vector3dParametric(
                        value = pose * Vector3d(0.0, xSketch, ySketch),
                        t = t
                    )
                )
            )

            // Build edges between consecutive rings:
            if (previousMapSketchModel != null) {
                builder.addEdge(
                    mapSketchModel.get(vertexIdSketch),
                    previousMapSketchModel.get(vertexIdSketch)
                )
            }
        }

        // Build ring edges:
        sketch.vertices.indices.forEach { vertIdxSketch ->
            builder.addEdge(
                mapSketchModel.get(vertIdxSketch),
                if (vertIdxSketch == sketch.vertices.size - 1) mapSketchModel.get(0) // Loop around to first to complete ring
                else mapSketchModel.get(vertIdxSketch + 1)
            )
        }

        return mapSketchModel
    }

    var previous: Int2IntOpenHashMap? = null
    samples.forEach {
        previous = extrude(previous, it)
    }

    return SketchExtrusion(builder, f0, f1, rmf, rmfLookup)
}

data class SketchExtrusion(
    val mesh: MeshBuilder<Vector3dParametric, Quads>,
    val f0: Pose3d,
    val f1: Pose3d,
    val rmfProgression: ArrayList<Pose3dParametric>,
    val rmfLookup: Double2ObjectOpenHashMap<Pose3d>,
)

/**
 * ARGB color with 8 bits per channel implemented as a value class.
 * */
@JvmInline
value class MyColor(val data : Int) {
    companion object {
        val RED = MyColor(255, 255, 0, 0)
        val GREEN = MyColor(255, 0, 255, 0)
        val BLUE = MyColor(255, 0, 0, 255)
        val WHITE = MyColor(255, 255, 255,  255)

        fun lerp(from: MyColor, to: MyColor, blend: Float): MyColor =
            MyColor(
                lerp(from.aF, to.aF, blend),
                lerp(from.rF, to.rF, blend),
                lerp(from.gF, to.gF, blend),
                lerp(from.bF, to.bF, blend),
            )

        fun lerpA(from: MyColor, to: MyColor, blend: Float) : Float = lerp(from.aF, to.aF, blend)
        fun lerpR(from: MyColor, to: MyColor, blend: Float) : Float = lerp(from.rF, to.rF, blend)
        fun lerpG(from: MyColor, to: MyColor, blend: Float) : Float = lerp(from.gF, to.gF, blend)
        fun lerpB(from: MyColor, to: MyColor, blend: Float) : Float = lerp(from.bF, to.bF, blend)

        fun fromVector(argbVector: Vector4d) = MyColor(argbVector)
        fun fromRGBAVector(rgbaVector: Vector4d) = MyColor(Vector4d(rgbaVector.w, rgbaVector.x, rgbaVector.y, rgbaVector.z))

        /**
         * Creates a color from the [argbVector], given any positive components.
         * The components are normalized to at most 1, given the largest component.
         * */
        fun fromVectorNormalizing(argbVector: Vector4d) : MyColor {
            val max = max(max(argbVector.x, argbVector.y), max(argbVector.z, argbVector.w))

            return if(max <= 1.0) {
                MyColor(argbVector)
            }
            else {
                MyColor(argbVector / max)
            }
        }
    }

    /**
     * Converts from hex.
     * */
    constructor(hex: Long) : this(hex.toInt())

    val a get() = FastColor.ARGB32.alpha(data)
    val r get() = FastColor.ARGB32.red(data)
    val g get() = FastColor.ARGB32.green(data)
    val b get() = FastColor.ARGB32.blue(data)

    val aF get() = a / 255f
    val rF get() = r / 255f
    val gF get() = g / 255f
    val bF get() = b / 255f

    constructor(a: Byte, r: Byte, g: Byte, b: Byte) : this(
        FastColor.ARGB32.color(
            a.toInt(),
            r.toInt(),
            g.toInt(),
            b.toInt()
        )
    )

    constructor(a: Int, r: Int, g: Int, b: Int) : this(
        FastColor.ARGB32.color(
            a.coerceIn(0, 255),
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255)
        )
    )

    constructor(a: Float, r: Float, g: Float, b: Float) : this(
        FastColor.ARGB32.color(
            (a * 255).toInt().coerceIn(0, 255),
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255)
        )
    )

    constructor(argbVector: Vector4d) : this(
        FastColor.ARGB32.color(
            (argbVector.x * 255).toInt().coerceIn(0, 255),
            (argbVector.y * 255).toInt().coerceIn(0, 255),
            (argbVector.z * 255).toInt().coerceIn(0, 255),
            (argbVector.w * 255).toInt().coerceIn(0, 255)
        )
    )

    constructor(r: Byte, g: Byte, b: Byte) : this(Byte.MAX_VALUE, r, g, b)
    constructor(r: Int, g: Int, b: Int) : this(255, r, g, b)
    constructor(r: Float, g: Float, b: Float) : this(1f, r, g, b)

    override fun toString() = "ARGB[$a, $r, $g, $b]"

    fun toVector4d() = Vector4d(
        aF.toDouble(),
        rF.toDouble(),
        gF.toDouble(),
        bF.toDouble()
    )

    fun toRGBAVector4d() = Vector4d(
        rF.toDouble(),
        gF.toDouble(),
        bF.toDouble(),
        aF.toDouble()
    )

    fun toVector3d() = Vector3d(
        rF.toDouble(),
        gF.toDouble(),
        bF.toDouble()
    )

    fun blit(ptr: Long) {
        MemoryUtil.memPutByte(ptr + 0, r.toByte())
        MemoryUtil.memPutByte(ptr + 1, g.toByte())
        MemoryUtil.memPutByte(ptr + 2, b.toByte())
        MemoryUtil.memPutByte(ptr + 3, a.toByte())
    }

    operator fun not() = data
}

class DummyBlockEntityRendererProvider<T : BlockEntity>(val expectsVanillaRenderCalls: Boolean = false) : BlockEntityRendererProvider<T> {
    companion object {
        private var warned = false
    }

    override fun create(p0: BlockEntityRendererProvider.Context): BlockEntityRenderer<T> {
        return Impl(expectsVanillaRenderCalls)
    }

    private class Impl<T : BlockEntity>(val expectsVanillaRenderCalls: Boolean) : BlockEntityRenderer<T> {
        override fun render(
            p0: T,
            p1: Float,
            p2: PoseStack,
            p3: MultiBufferSource,
            p4: Int,
            p5: Int,
        ) {
            if(expectsVanillaRenderCalls) {
                return
            }

            if(warned) {
                return
            }

            val player = Minecraft.getInstance().player
                ?: return

            player.sendSystemMessage(Component.literal("ELN2 only supports rendering with flywheel at the moment!"))
            warned = true
        }
    }
}
