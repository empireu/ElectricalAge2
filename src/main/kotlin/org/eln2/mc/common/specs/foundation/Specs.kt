package org.eln2.mc.common.specs.foundation

import com.jozufozu.flywheel.core.materials.model.ModelData
import com.jozufozu.flywheel.util.Color
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import org.ageseries.libage.mathematics.BoundingBox3d
import org.ageseries.libage.mathematics.Pose2d
import org.ageseries.libage.mathematics.Vector3d
import org.ageseries.libage.mathematics.o
import org.eln2.mc.ClientOnly
import org.eln2.mc.CrossThreadAccess
import org.eln2.mc.client.render.DebugVisualizer
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.createPartInstance
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.common.specs.SpecRegistry
import org.eln2.mc.extensions.formatted
import org.eln2.mc.extensions.getViewRay
import org.eln2.mc.extensions.toVector3d
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.integration.DebugComponentDisplay
import org.eln2.mc.mathematics.Plane3d
import org.eln2.mc.mathematics.intersectionWith
import java.util.concurrent.ConcurrentLinkedQueue

class SpecPartRenderer(val specPart: SpecContainerPart) : PartRenderer() {
    private val specs = ArrayList<Spec<*>>()

    /* FIXME FIXME FIXME FIXME FIXME */
    var modelInstance: ModelData? = null
    /* FIXME FIXME FIXME FIXME FIXME */

    override fun setupRendering() {
        modelInstance?.delete()
        modelInstance = createPartInstance(multipart, PartialModels.GROUND, specPart, 0.0)
    }

    override fun beginFrame() {
        handleSpecUpdates()

        for (spec in specs) {
            val renderer = spec.renderer

            if (!renderer.isSetupWith(this)) {
                renderer.setupRendering(this)
            }

            renderer.beginFrame()
        }
    }

    private fun handleSpecUpdates() {
        while (true) {
            val update = specPart.renderUpdates.poll() ?: break
            val spec = update.spec

            when (update.type) {
                SpecUpdateType.Add -> {
                    check(!specs.contains(spec))
                    specs.add(spec)
                    spec.renderer.setupRendering(this)
                    spec.renderer.relight(RelightSource.Setup)
                }

                SpecUpdateType.Remove -> {
                    check(specs.contains(spec))
                    specs.remove(spec)
                    spec.destroyRenderer()
                }
            }
        }
    }

    override fun relight(source: RelightSource) {
        specs.forEach {
            it.renderer.relight(source)
        }
    }

    override fun remove() {
        modelInstance?.delete()

        specs.forEach {
            it.destroyRenderer()
        }
    }
}

class SpecContainerPart(ci: PartCreateInfo) : Part<SpecPartRenderer>(ci), DebugComponentDisplay {
    val substratePlane = Plane3d(placement.face.toVector3d(), placement.mountingPointWorld)

    val renderUpdates = ConcurrentLinkedQueue<SpecUpdate>()

    override fun createRenderer(): SpecPartRenderer {
        return SpecPartRenderer(this)
    }

    override fun onAddedToClient() {
        DebugVisualizer.partFrame(this)
        DebugVisualizer.partBounds(this)
    }

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if(placement.level.isClientSide) {
            val intersection = context.player.getViewRay() intersectionWith substratePlane

            DebugVisualizer.lineBox(
                BoundingBox3d.fromCenterSize(
                    intersection,
                    0.1
                )
            ).removeAfter(5.0).withinScopeOf(this)

            val px = placement.positiveX.toVector3d()
            val pz = placement.positiveZ.toVector3d()

            val specialX = (intersection - placement.mountingPointWorld) o px
            val specialZ = (intersection - placement.mountingPointWorld) o pz

            println("Special x=${specialX.formatted()} z=${specialZ.formatted()}")

            DebugVisualizer.lineBox(
                BoundingBox3d.fromCenterSize(
                    px * specialX + pz * specialZ + placement.mountingPointWorld,
                    0.05
                ),
                color = Color.GREEN
            ).removeAfter(5.0).withinScopeOf(this)
        }

        return super.onUsedBy(context)
    }

    override fun submitDebugDisplay(builder: ComponentDisplayList) {

    }
}

enum class SpecUpdateType(val id: Int) {
    Add(1),
    Remove(2);

    companion object {
        fun fromId(id: Int): SpecUpdateType {
            return when (id) {
                Add.id -> Add
                Remove.id -> Remove
                else -> error("Invalid spec update type id $id")
            }
        }
    }
}

data class SpecUpdate(val spec: Spec<*>, val type: SpecUpdateType)

data class SpecCreateInfo(
    val id: ResourceLocation,
    val placement: SpecPlacementInfo
)

class SpecItem(val provider: SpecProvider) : Item(Properties()) {

}

abstract class SpecProvider {
    val id: ResourceLocation get() = SpecRegistry.getId(this)

    abstract fun create(context: SpecCreateInfo): Spec<*>

    abstract val placementCollisionSize: Vector3d

    open fun canPlace(context: SpecPlacementInfo): Boolean = true
}

/**
 * @param position The world position of the spec, on the substrate plane.
 * */
data class SpecPlacementInfo(
    val part: SpecContainerPart,
    val provider: SpecProvider,
    val position: Vector3d,
    val specialPose: Pose2d
) {
    val level by part.placement::level
    val blockPos by part.placement::position
    val face by part.placement::face
    val multipart by part.placement::multipart
    val specialPosition by specialPose::translation
    val specialRotation by specialPose::rotation
}

abstract class Spec<Renderer : SpecRenderer>(ci: SpecCreateInfo) {
    val id = ci.id
    val placement = ci.placement

    @ClientOnly
    protected var previousRenderer: Renderer? = null

    @ClientOnly
    protected var activeRenderer: Renderer? = null
        private set

    /**
     * Gets the [Renderer] instance for this spec.
     * By default, it calls the [createRenderer] method, and stores the result.
     * */
    val renderer: Renderer get() {
        if (!placement.level.isClientSide) {
            error("Tried to get spec renderer on non-client side!")
        }

        if (activeRenderer == null) {
            activeRenderer = createRenderer().also {
                val previousRenderer = this.previousRenderer
                this.previousRenderer = null

                if(previousRenderer != null) {
                    if(it is SpecRendererStateStorage) {
                        it.restoreSnapshot(previousRenderer)
                    }
                }
            }

            initializeRenderer()
        }

        return activeRenderer!!
    }

    /**
     * Creates a renderer instance for this spec.
     * @return A new instance of the spec renderer.
     * */
    @ClientOnly
    abstract fun createRenderer(): Renderer

    /**
     * Called to initialize the [Renderer], right after it is created by [createRenderer]
     * */
    @ClientOnly
    open fun initializeRenderer() { }

    @ClientOnly
    open fun destroyRenderer() {
        previousRenderer = activeRenderer
        activeRenderer?.remove()
        activeRenderer = null
    }
}

/**
 * This is the per-spec renderer. One is created for every instance of a spec.
 * The various methods may be called from separate threads.
 * Thread safety must be guaranteed by the implementation.
 * */
@CrossThreadAccess
abstract class SpecRenderer {
    lateinit var partRenderer: SpecPartRenderer
        private set

    val hasPartRenderer get() = this::partRenderer.isInitialized

    val instancePosition : BlockPos get() {
        if(!hasPartRenderer) {
            error("Tried to get instance position before init of spec part renderer")
        }

        return partRenderer.instancePosition
    }

    fun isSetupWith(partRenderer: SpecPartRenderer): Boolean {
        return this::partRenderer.isInitialized && this.partRenderer == partRenderer
    }

    /**
     * Called when the spec is picked up by the [SpecContainerPart]'s renderer.
     * @param partRenderer The spec part's renderer.
     * */
    fun setupRendering(partRenderer: SpecPartRenderer) {
        this.partRenderer = partRenderer
        setupRendering()
    }

    /**
     * Called to set up rendering, when [partRenderer] has been acquired.
     * */
    protected open fun setupRendering() { }

    /**
     * Called when a light update occurs, or this renderer is set up (after [setupRendering]).
     * Models should be re-lit here
     * */
    open fun relight(source: RelightSource) { }

    /**
     * Called each frame.
     * This method may be used to play animations or to apply general per-frame updates.
     * */
    open fun beginFrame() { }

    /**
     * Called when the renderer is no longer required **OR** the rendering pipeline/backend/whatever is re-created. In that case, the renderer might be re-created just after this one is destroyed.
     * As an example, this will happen if the user switches flywheel backends, (I think) when the user changes some graphics settings, and it can also happen when the floating origin shifts.
     * All resources must be released here. If you have any data that you stored in this renderer but not in the spec, and you would like to get it back, implement [SpecRendererStateStorage].
     * */
    open fun remove() { }
}

/**
 * Helper interface for renderers that store state in the [SpecRenderer] instance.
 * */
interface SpecRendererStateStorage {
    /**
     * Called to restore the information from a previous renderer instance.
     * Could happen when the renderer is re-created, after being destroyed. Could happen when origin shifts, etc. Passed as [SpecRenderer] because the type can actually change (e.g. if switching backends and the part chooses to create another renderer)
     * */
    fun restoreSnapshot(renderer: SpecRenderer)
}
