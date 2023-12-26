package org.eln2.mc.common.specs.foundation

import com.jozufozu.flywheel.core.materials.model.ModelData
import com.jozufozu.flywheel.util.Color
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import org.ageseries.libage.mathematics.*
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.*
import org.eln2.mc.client.render.DebugVisualizer
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.foundation.createPartInstance
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.common.specs.SpecRegistry
import org.eln2.mc.data.FacingLocator
import org.eln2.mc.data.LocatorSetBuilder
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.integration.DebugComponentDisplay
import org.eln2.mc.mathematics.FacingDirection
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI

object SpecGeometry {
    fun mountingPointSpecial(specMountingPointWorld: Vector3d, partPositiveX: Direction, partPositiveZ: Direction, partMountingPointWorld: Vector3d) : Vector2d {
        val specMountingPointPart = specMountingPointWorld - partMountingPointWorld

        return Vector2d(
            specMountingPointPart o partPositiveX.vector3d,
            specMountingPointPart o partPositiveZ.vector3d
        )
    }

    fun positionWorld(specMountingPointSpecial: Vector2d, partPositiveX: Direction, partPositiveZ: Direction, partMountingPointWorld: Vector3d) : Vector3d {
        return partPositiveX.vector3d * specMountingPointSpecial.x + partPositiveZ.vector3d * specMountingPointSpecial.y + partMountingPointWorld
    }

    fun rotationWorld(specHorizontalFacing: Rotation2d, partFacing: FacingDirection, partFace: Direction) =
        Rotation3dStack()
            .rotate(Rotation3d.exp(Vector3d.unitY * specHorizontalFacing.ln()))
            .rotate(partFacing.rotation3d)
            .rotate(partFace.rotation3d)
            .rotation

    fun transformWorldMounting(specMountingPointWorld: Vector3d, specHorizontalFacing: Rotation2d, partFacing: FacingDirection, partFace: Direction) =
        Pose3d(
            specMountingPointWorld,
            rotationWorld(
                specHorizontalFacing,
                partFacing,
                partFace
            )
        )

    fun transformWorldRaised(specMountingPointWorld: Vector3d, specHorizontalFacing: Rotation2d, specSize: Vector3d, partFacing: FacingDirection, partFace: Direction) =
        Pose3d(
            specMountingPointWorld + partFace.vector3d * (specSize.y * 0.5),
            rotationWorld(
                specHorizontalFacing,
                partFacing,
                partFace
            )
        )

    fun boundingBox(specMountingPointWorld: Vector3d, specHorizontalFacing: Rotation2d, specCollisionSize: Vector3d, partFacing: FacingDirection, partFace: Direction) =
        OrientedBoundingBox3d(
            transformWorldRaised(specMountingPointWorld, specHorizontalFacing, specCollisionSize, partFacing, partFace),
            specCollisionSize * 0.5
        )
}

/**
 * @param part The container.
 * @param mountingPointWorld The world position of the spec, on the substrate plane.
 * @param provider The provider that created this spec.
 * */
data class SpecPlacementInfo(
    val part: SpecContainerPart,
    val provider: SpecProvider,
    val mountingPointWorld: Vector3d,
    val specialPose: Pose2d,
    val placementId: Int
) {
    val level by part.placement::level
    val blockPos by part.placement::position
    val face by part.placement::face
    val multipart by part.placement::multipart
    val mountingPointSpecial by specialPose::translation
    val orientation by specialPose::rotation

    val boundingBox = SpecGeometry.boundingBox(
        mountingPointWorld,
        orientation,
        provider.placementCollisionSize,
        part.placement.facing,
        part.placement.face
    )
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

data class SpecCreateInfo(val id: ResourceLocation, val placement: SpecPlacementInfo)

class SpecContainerPart(ci: PartCreateInfo) : Part<SpecPartRenderer>(ci), DebugComponentDisplay {
    val substratePlane = Plane3d(placement.face.vector3d, placement.mountingPointWorld)

    private var specId = 0
        get() {
            requireIsOnServerThread {
                "specID get"
            }

            return field
        }
        set(value) {
            requireIsOnServerThread {
                "specID set"
            }

            field = value
        }

    private val specs = HashMap<Int, Spec<*>>()

    @ServerOnly
    private val dirtySpecs = HashSet<Spec<*>>()
    @ServerOnly
    private val placementUpdates = ArrayList<SpecUpdate>()

    @ClientOnly
    val renderUpdates = ConcurrentLinkedQueue<SpecUpdate>()

    @ServerOnly
    fun enqueueSpecSync(spec: Spec<*>) {
        requireIsOnServerThread {
            "Tried to enqueue spec sync on non-server thread"
        }

        dirtySpecs.add(spec)
        setSyncDirty()
    }

    /**
     * Gets the mounting position picked by the [player] in the world frame. This position will be on the [substratePlane].
     * */
    fun getSpecMountingPointWorld(player: LivingEntity) = player.getViewRay() intersectionWith substratePlane

    /**
     * Gets the bounding box of a spec with the special [desiredOrientation], provided by [provider].
     * */
    fun getSpecBoundingBox(player: LivingEntity, desiredOrientation: Rotation2d, provider: SpecProvider) =
        SpecGeometry.boundingBox(
            getSpecMountingPointWorld(player),
            desiredOrientation,
            provider.placementCollisionSize,
            placement.facing,
            placement.face
        )

    /**
     * Checks if the placement of a spec with the special [desiredOrientation], provided by [provider] collides with any other specs present in this container.
     * */
    fun placementCollides(player: LivingEntity, desiredOrientation: Rotation2d, provider: SpecProvider) : Boolean {
        val boundingBox = getSpecBoundingBox(
            player,
            desiredOrientation,
            provider
        )

        for (spec in specs.values) {
            if(spec.placement.boundingBox intersects boundingBox) {
                return true
            }
        }

        return false
    }

    fun createSpecPlacementInfo(player: LivingEntity, desiredOrientation: Rotation2d, provider: SpecProvider) : SpecPlacementInfo {
        val mountingPointWorld = getSpecMountingPointWorld(player)

        val id = if(placement.level.isClientSide) {
            -1
        }
        else {
            specId++
        }

        return SpecPlacementInfo(
            this,
            provider,
            mountingPointWorld,
            Pose2d(
                SpecGeometry.mountingPointSpecial(
                    mountingPointWorld,
                    placement.positiveX,
                    placement.positiveZ,
                    placement.mountingPointWorld
                ),
                desiredOrientation
            ),
            id
        )
    }

    /**
     * Adds the [spec] and notifies via [Spec.onAdded]
     * Throws if the [spec] is already added.
     * */
    private fun addSpec(spec: Spec<*>) {
        specs.putUnique(spec.placement.placementId, spec)
        spec.onAdded()
    }

    /**
     * Removes the [spec] and notifies via [Spec.setRemoved].
     * @return True if the spec was removed. Otherwise, false.
     * */
    private fun removeSpec(spec: Spec<*>) : Boolean {
        if(specs.remove(spec.placement.placementId) == null) {
            return false
        }

        spec.setRemoved()

        return true
    }

    fun canPlace(player: LivingEntity, desiredOrientation: Rotation2d, provider: SpecProvider) : Boolean {
        if(placementCollides(player, desiredOrientation, provider)) {
            return false
        }

        val context = createSpecPlacementInfo(player, desiredOrientation, provider)

        return provider.canPlace(context)
    }

    /**
     * Attempts to place a spec.
     * @return True, if the spec was placed successfully. Otherwise, false.
     * */
    @ServerOnly
    fun place(
        player: LivingEntity,
        desiredOrientation: Rotation2d,
        provider: SpecProvider,
        saveTag: CompoundTag? = null,
    ) : Boolean {
        requireIsOnServerThread {
            "Cannot explicitly place spec on client side"
        }

        if(placementCollides(player, desiredOrientation, provider)) {
            return false
        }

        val context = createSpecPlacementInfo(player, desiredOrientation, provider)

        if(!provider.canPlace(context)) {
            return false
        }

        val spec = provider.create(context)

        addSpec(spec)
        placementUpdates.add(SpecUpdate(spec, SpecUpdateType.Add))

        if (spec is ItemPersistent && spec.order == ItemPersistentLoadOrder.BeforeSim) {
            spec.loadFromItemNbt(saveTag)
        }

        spec.onPlaced()

        setSaveDirty()
        setSyncDirty()

        return true
    }

    @ServerOnly
    override fun getSaveTag(): CompoundTag? {
        if(specs.isEmpty()) {
            return null // Prevent loadFromTag when part is placed
        }

        val tag = CompoundTag()
        saveSpecs(tag, false)
        return tag
    }

    @ServerOnly
    override fun loadFromTag(tag: CompoundTag) {
        requireIsOnServerThread() // This is what gets called on loadInitialSynTag which we overrode, so we're peachy

        loadSpecs(tag)

        specs.values.forEach {
            it.onLoaded()
        }
    }

    override fun getInitialSyncTag(): CompoundTag {
        val tag = CompoundTag()
        saveSpecs(tag, true)
        return tag
    }

    override fun loadInitialSyncTag(tag: CompoundTag) {
        loadSpecs(tag)

        specs.values.forEach {
            clientAddSpec(it)
        }
    }

    override fun onSyncSuggested() {
        specs.values.forEach {
            it.onSyncSuggested()
        }
    }

    override fun getSyncTag(): CompoundTag {
        val tag = CompoundTag()

        packPlacementUpdates(tag)
        packSpecUpdates(tag)

        return tag
    }

    override fun handleSyncTag(tag: CompoundTag) {
        unpackPlacementUpdates(tag)
        unpackSpecUpdates(tag)
    }

    @ServerOnly
    private fun packPlacementUpdates(tag: CompoundTag) {
        requireIsOnServerThread()

        if(placementUpdates.size == 0) {
            return
        }

        val placementUpdatesTag = ListTag()

        placementUpdates.forEach { update ->
            val spec = update.spec

            val updateTag = CompoundTag()

            updateTag.putSpecUpdateType(TYPE, update.type)

            when (update.type) {
                SpecUpdateType.Add -> {
                    updateTag.put(NEW_SPEC, saveSpecCommon(spec))
                }

                SpecUpdateType.Remove -> {
                    updateTag.putInt(REMOVED_ID, spec.placement.placementId)
                }
            }

            placementUpdatesTag.add(updateTag)
        }

        placementUpdates.clear()

        tag.put(PLACEMENT_UPDATES, placementUpdatesTag)
    }

    @ClientOnly
    private fun unpackPlacementUpdates(tag: CompoundTag) {
        requireIsOnRenderThread()

        val placementUpdatesTag = tag.get(PLACEMENT_UPDATES) as? ListTag
            ?: return

        placementUpdatesTag.forEach { updateTag ->
            updateTag as CompoundTag
            when (updateTag.getSpecUpdateType(TYPE)) {
                SpecUpdateType.Add -> {
                    val newSpecTag = updateTag.get(NEW_SPEC) as CompoundTag
                    val spec = unpackSpec(newSpecTag)

                    if (specs.put(spec.placement.placementId, spec) != null) {
                        LOG.error("Client received new part, but a part was already present on the ${spec.placement.face} face!")
                    }

                    clientAddSpec(spec)
                }
                SpecUpdateType.Remove -> {
                    val id = updateTag.getInt(REMOVED_ID)
                    val spec = specs[id]

                    if(spec == null) {
                        LOG.error("Client received broken spec on $id, but there was no spec present!")
                    }
                    else {
                        removeSpec(spec)
                        clientRemoveSpec(spec)
                    }
                }
            }
        }
    }

    @ServerOnly
    private fun packSpecUpdates(tag: CompoundTag) {
        requireIsOnServerThread()

        if(dirtySpecs.size == 0) {
            return
        }

        val partUpdatesTag = ListTag()

        dirtySpecs.forEach { spec ->
            if(!specs.containsKey(spec.placement.placementId)) {
                LOG.warn("Update for removed spec $spec")
                return@forEach
            }

            val syncTag = spec.getSyncTag()
                ?: return@forEach

            val updateTag = CompoundTag()

            updateTag.putInt(PLACEMENT_ID, spec.placement.placementId)
            updateTag.put(TAG, syncTag)

            partUpdatesTag.add(updateTag)
        }

        dirtySpecs.clear()

        tag.put(SPEC_UPDATES, partUpdatesTag)
    }

    @ClientOnly
    private fun unpackSpecUpdates(tag: CompoundTag) {
        requireIsOnRenderThread()

        val specUpdatesTag = tag.get(SPEC_UPDATES) as? ListTag
            ?: return

        specUpdatesTag.forEach { updateTag ->
            val compound = updateTag as CompoundTag

            val placementId = compound.getInt(PLACEMENT_ID)

            val spec = specs[placementId]

            if (spec == null) {
                LOG.error("Spec container $this $placement received update on $placementId, but spec is null!")
            } else {
                val syncTag = compound.get(TAG) as CompoundTag
                spec.handleSyncTag(syncTag)
            }
        }
    }

    @ServerOnly
    private fun saveSpecCommon(spec: Spec<*>): CompoundTag {
        requireIsOnServerThread()

        val tag = CompoundTag()

        tag.putResourceLocation(ID, spec.id)
        val placement = spec.placement
        tag.putVector3d(MOUNTING_POINT_WORLD, placement.mountingPointWorld)
        tag.putPose2d(SPECIAL_POSE, placement.specialPose)
        tag.putInt(PLACEMENT_ID, placement.placementId)

        val customTag = spec.getSaveTag()

        if (customTag != null) {
            tag.put(TAG, customTag)
        }

        return tag
    }

    @ServerOnly
    private fun saveSpecs(tag: CompoundTag, initial: Boolean) {
        requireIsOnServerThread()

        val specsTag = ListTag()

        specs.values.forEach { spec ->
            val commonTag = saveSpecCommon(spec)

            if (initial) {
                val initialTag = spec.getInitialSyncTag()

                if (initialTag != null) {
                    commonTag.put(INITIAL_TAG, initialTag)
                }
            }

            specsTag.add(commonTag)
        }

        tag.put(SPECS, specsTag)
    }

    private fun loadSpecs(tag: CompoundTag) {
        if (tag.contains(SPECS)) {
            val partsTag = tag.get(SPECS) as ListTag
            partsTag.forEach { partTag ->
                val partCompoundTag = partTag as CompoundTag
                val part = unpackSpec(partCompoundTag)

                addSpec(part)

                val initialTag = partCompoundTag.get(INITIAL_TAG) as? CompoundTag

                if (initialTag != null) {
                    part.loadInitialSyncTag(initialTag)
                }
            }
        } else {
            LOG.error("Spec at ${placement.position} had no saved data")
        }
    }

    private fun unpackSpec(tag: CompoundTag): Spec<*> {
        val id = tag.getResourceLocation(ID)
        val mountingPointWorld = tag.getVector3d(MOUNTING_POINT_WORLD)
        val specialPose = tag.getPose2d(SPECIAL_POSE)
        val placementId = tag.getInt(PLACEMENT_ID)

        val customTag = tag.get(TAG) as? CompoundTag

        val provider = SpecRegistry.tryGetProvider(id) ?: error("Failed to get spec with id $id")

        val spec = provider.create(
            SpecPlacementInfo(
                this,
                provider,
                mountingPointWorld,
                specialPose,
                placementId
            )
        )

        if (customTag != null) {
            spec.loadFromTag(customTag)
        }

        return spec
    }

    /**
     * Enqueues a part for renderer setup.
     * */
    @ClientOnly
    private fun clientAddSpec(spec: Spec<*>) {
        spec.onAddedToClient()
        renderUpdates.add(SpecUpdate(spec, SpecUpdateType.Add))
    }

    /**
     * Removes a part from the renderer.
     * */
    @ClientOnly
    private fun clientRemoveSpec(spec: Spec<*>) {
        spec.onBroken()
        renderUpdates.add(SpecUpdate(spec, SpecUpdateType.Remove))
    }

    companion object {
        private const val ID = "id"
        private const val TYPE = "type"
        private const val NEW_SPEC = "newSpec"
        private const val REMOVED_ID = "removedId"
        private const val PLACEMENT_UPDATES = "placementUpdates"
        private const val SPEC_UPDATES = "specUpdates"
        private const val SPECS = "specs"
        private const val MOUNTING_POINT_WORLD = "position"
        private const val SPECIAL_POSE = "pose"
        private const val PLACEMENT_ID = "placementId"
        private const val TAG = "tag"
        private const val INITIAL_TAG = "initialTag"
    }

    override fun createRenderer(): SpecPartRenderer {
        return SpecPartRenderer(this)
    }

    override fun onAddedToClient() {
        DebugVisualizer.partFrame(this)
        DebugVisualizer.partBounds(this)
    }

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        if(placement.level.isClientSide) {
            val intersection = getSpecMountingPointWorld(context.player)

            DebugVisualizer.lineBox(
                BoundingBox3d.fromCenterSize(
                    intersection,
                    0.15
                )
            ).removeAfter(5.0).withinScopeOf(this)

            DebugVisualizer.lineOrientedBox(
                SpecGeometry.boundingBox(
                    intersection,
                    Rotation2d.exp(PI / 6.0),
                    Vector3d(0.1, 0.2, 0.1),
                    placement.facing,
                    placement.face
                ),
                color = Color.RED
            ).removeAfter(5.0).withinScopeOf(this)
        }

        return super.onUsedBy(context)
    }

    override fun submitDebugDisplay(builder: ComponentDisplayList) {
        builder.debug("Specs: ${specs.size}")
    }
}

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

class SpecItem(val provider: SpecProvider) : Item(Properties()) {
    override fun useOn(pContext: UseOnContext): InteractionResult {
        val player = pContext.player

        if(player == null) {
            LOG.error("Player null")
            return InteractionResult.FAIL
        }

        val multipart = pContext.level.getBlockEntity(pContext.clickedPos) as? MultipartBlockEntity
            ?: return InteractionResult.FAIL

        val part = multipart.getPart(pContext.clickedFace) as? SpecContainerPart
            ?: return InteractionResult.FAIL

        val orientation = Rotation2d.exp(PI / 6.0)

        if(!part.canPlace(player, orientation, provider)) {
            LOG.debug("Cannot place")
            return InteractionResult.FAIL
        }

        LOG.debug("Placing")
        
        if(pContext.level.isClientSide) {
            return InteractionResult.SUCCESS
        }

        if(!part.place(player, orientation, provider)) {
            LOG.debug("Did not place")
            return InteractionResult.FAIL
        }

        return InteractionResult.CONSUME
    }
}

abstract class SpecProvider {
    val id: ResourceLocation get() = SpecRegistry.getId(this)

    abstract fun create(context: SpecPlacementInfo): Spec<*>

    abstract val placementCollisionSize: Vector3d

    open fun canPlace(context: SpecPlacementInfo): Boolean = true
}

open class BasicSpecProvider(final override val placementCollisionSize: Vector3d, val factory: ((ci: SpecCreateInfo) -> Spec<*>), ) : SpecProvider() {
    override fun create(context: SpecPlacementInfo) = factory(SpecCreateInfo(id, context))
}

/**
 * Parts are entity-like units that exist in a spec part container [Part]. They are similar to normal block entities,
 * but many can exist in the same block space.
 * They are placed in a region on the substrate plane of a spec part container, with a custom orientation.
 * The position and orientation are not axis-aligned;
 * This is the biggest difference between [Spec] and any other sort of block-like game object.
 * */
abstract class Spec<Renderer : SpecRenderer>(ci: SpecCreateInfo) {
    val id = ci.id
    val placement = ci.placement

    var isRemoved = false
        private set

    /**
     * Saves the spec data to the compound tag.
     * @return A compound tag with all the save data for this spec, or null, if no data needs saving.
     * */
    @ServerOnly
    open fun getSaveTag(): CompoundTag? = null

    /**
     * Gets the synced data that should be sent when a client first loads the spec.
     * @return A compound tag with all the data, or null, if no data needs to be sent.
     * */
    @ServerOnly
    open fun getInitialSyncTag(): CompoundTag? = getSyncTag()

    /**
     * Restore the spec data from the compound tag.
     * This method is used on both logical sides. The client only receives this call when the initial chunk synchronization happens.
     * @param tag The custom data tag, as created by getSaveTag.
     * */
    open fun loadFromTag(tag: CompoundTag) { }

    /**
     * Loads the synced data that was sent when the client first loaded this spec, from [getInitialSyncTag].
     * */
    open fun loadInitialSyncTag(tag: CompoundTag) = loadFromTag(tag)

    /**
     * This method is called when this spec is invalidated, and in need of synchronization to clients.
     * You will receive this tag in *handleSyncTag* on the client, _if_ the tag is not null.
     * @return A compound tag with all spec updates. You may return null, but that might indicate an error in logic.
     * This method is called only when an update is _requested_, so there should be data in need of synchronization.
     * */
    @ServerOnly
    open fun getSyncTag(): CompoundTag? {
        return null
    }

    /**
     * This method is called on the client after the server logic of this spec requested an update, and the update was received.
     * @param tag The custom data tag, as returned by the getSyncTag method on the server.
     * */
    @ClientOnly
    open fun handleSyncTag(tag: CompoundTag) { }

    /**
     * This method invalidates the saved data of the part.
     * This ensures that the part will be saved to the disk.
     * */
    @ServerOnly
    fun setSaveDirty() {
        if (placement.level.isClientSide) {
            error("Cannot save spec on the client")
        }

        placement.part.setSaveDirty()
    }

    /**
     * This method synchronizes all changes from the server to the client.
     * It results in calls to the *getSyncTag* **(server)** / *handleSyncTag* **(client)** combo.
     * */
    @ServerOnly
    fun setSyncDirty() {
        if (placement.level.isClientSide) {
            error("Cannot sync spec changes from client to server!")
        }

        placement.part.enqueueSpecSync(this)
    }

    /**
     *  Called on the server when the spec is placed.
     * */
    @ServerOnly
    open fun onPlaced() {}

    /**
     * Called on the server when the spec finished loading from disk
     * */
    @ServerOnly
    open fun onLoaded() {}

    /**
     * Called when this spec is added to a container.
     * */
    open fun onAdded() {}

    /**
     * Called when this spec is received and added to the client container, just before rendering set-up is enqueued.
     * */
    @ClientOnly
    open fun onAddedToClient() {}

    /**
     * Called when this spec is being unloaded.
     * */
    open fun onUnloaded() {}

    /**
     * Called when the spec is destroyed (broken).
     * */
    open fun onBroken() {}

    fun setRemoved() {
        if(this.isRemoved) {
            LOG.error("Multiple calls to setRemoved spec")
        }

        this.isRemoved = true
        onRemoved()
    }

    /**
     * Called when the spec is removed from the container.
     * */
    protected open fun onRemoved() { }

    /**
     * Called when synchronization is suggested. This happens when a client enters the viewing area of the part.
     * */
    @ServerOnly
    open fun onSyncSuggested() {
        this.setSyncDirty()
    }

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
