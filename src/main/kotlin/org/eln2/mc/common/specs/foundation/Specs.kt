package org.eln2.mc.common.specs.foundation

import com.jozufozu.flywheel.core.PartialModel
import com.jozufozu.flywheel.core.materials.model.ModelData
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.vertex.PoseStack
import it.unimi.dsi.fastutil.objects.ReferenceArraySet
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.RenderHighlightEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.gui.overlay.ForgeGui
import net.minecraftforge.client.gui.overlay.IGuiOverlay
import net.minecraftforge.client.settings.KeyConflictContext
import net.minecraftforge.network.NetworkEvent
import org.ageseries.libage.data.put
import org.ageseries.libage.data.requireLocator
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.*
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.*
import org.eln2.mc.client.render.foundation.*
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.*
import org.eln2.mc.common.items.foundation.PartItem
import org.eln2.mc.common.network.Networking
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.parts.foundation.*
import org.eln2.mc.common.specs.SpecRegistry
import org.eln2.mc.data.Locators
import org.eln2.mc.extensions.*
import org.eln2.mc.integration.ComponentDisplayList
import org.eln2.mc.integration.DebugComponentDisplay
import org.eln2.mc.mathematics.FacingDirection
import org.joml.Quaternionf
import org.lwjgl.glfw.GLFW
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Supplier
import kotlin.collections.HashSet
import kotlin.math.abs

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
        Rotation3dBuilder()
            .rotate(Rotation3d.exp(Vector3d.unitY * specHorizontalFacing.ln()))
            .rotate(partFacing.rotation3d)
            .rotate(partFace.rotation3d)
            .rotation

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
    val placementId: Int,
) {
    val level by part.placement::level
    val blockPos by part.placement::position
    val face by part.placement::face
    val multipart by part.placement::multipart
    val mountingPointSpecial by specialPose::translation
    val orientation by specialPose::rotation

    val orientedBoundingBoxWorld = SpecGeometry.boundingBox(
        mountingPointWorld,
        orientation,
        provider.placementCollisionSize,
        part.placement.facing,
        part.placement.face
    )

    val positiveX = orientedBoundingBoxWorld.transform.rotation * Vector3d.unitX
    val positiveY = orientedBoundingBoxWorld.transform.rotation * Vector3d.unitY
    val positiveZ = orientedBoundingBoxWorld.transform.rotation * Vector3d.unitZ

    val shapeBoundingBox = run {
        val offset = PartGeometry.faceOffset(
            provider.placementCollisionSize,
            part.placement.face
        )

        BoundingBox3d.fromOrientedBoundingBox(
            OrientedBoundingBox3d(
                Vector3d(offset.x, offset.y, offset.z) + (mountingPointWorld - part.placement.mountingPointWorld),
                SpecGeometry.rotationWorld(
                    orientation,
                    part.placement.facing,
                    part.placement.face
                ),
                provider.placementCollisionSize * 0.5
            )
        )
    }

    fun createLocator() = Locators.buildLocator {
        it.put(BLOCK, blockPos)
        it.put(FACE, face)
        it.put(MOUNTING_POINT, mountingPointWorld)
        it.put(PLACEMENT_ID, placementId)
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

data class SpecUseInfo(
    val player: Player,
    val hand: InteractionHand,
    val playerViewRay: Ray3d,
    val intersection: RayIntersection,
)

data class SpecCreateInfo(val id: ResourceLocation, val placement: SpecPlacementInfo)

class SpecContainerPart(ci: PartCreateInfo) : Part<SpecPartRenderer>(ci), DebugComponentDisplay, PartCellContainer {
    @ServerOnly
    var containerID = UUID.randomUUID()
        private set

    val substratePlane = Plane3d(placement.face.vector3d, placement.mountingPointWorld)

    override fun shouldDrop(): Boolean {
        return false
    }

    private var lastPlacementId = 0
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

    private val specsInternal = HashMap<Int, Spec<*>>()

    val specs : Map<Int, Spec<*>> get() = specsInternal

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

        placement.multipart.parts.values.forEach { container ->
            if(container is SpecContainerPart) {
                for (spec in container.specsInternal.values) {
                    if(spec.placement.orientedBoundingBoxWorld intersectsWith boundingBox) {
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun createSpecPlacementInfo(player: LivingEntity, desiredOrientation: Rotation2d, provider: SpecProvider) : SpecPlacementInfo {
        val mountingPointWorld = getSpecMountingPointWorld(player)

        val id = if(placement.level.isClientSide) {
            -1
        }
        else {
            lastPlacementId++
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

    fun pickSpec(ray3d: Ray3d) = specsInternal.values
        .flatMap { spec ->
            val intersections = ArrayList<Pair<RayIntersection, Spec<*>>>(1)

            fun test(box: OrientedBoundingBox3d) {
                val intersection = (ray3d intersectionWith box)

                if(intersection != null) {
                    intersections.add(intersection to spec)
                }
            }

            test(spec.placement.orientedBoundingBoxWorld)

            if(spec is GridSpec<*>) {
                spec.gridTerminalSystem.instances.values.forEach {
                    test(it.boundingBox)
                }
            }

            intersections
        }
        .minByOrNull { ray3d.origin..ray3d.evaluate(it.first.entry) }

    fun pickSpec(player: Entity) = pickSpec(player.getViewRay())

    fun getSpecByPlacementID(id: Int) = specsInternal[id]

    /**
     * Adds the [spec] and notifies via [Spec.onAdded]
     * Throws if the [spec] is already added.
     * */
    private fun addSpec(spec: Spec<*>) {
        specsInternal.putUnique(spec.placement.placementId, spec)
        spec.onAdded()
    }

    /**
     * Removes the [spec] and notifies via [Spec.setRemoved].
     * @return True if the spec was removed. Otherwise, false.
     * */
    private fun removeSpec(spec: Spec<*>) : Boolean {
        if(specsInternal.remove(spec.placement.placementId) == null) {
            return false
        }

        spec.setRemoved()

        return true
    }

    /**
     * Attempts to place a spec.
     * @param isFreshlyPlacedSpecContainer If true, then the placement update shall be dropped.
     * Use true if and only if the spec part container was placed right before the spec was added.
     * In this case, the initial client update will include this already existing spec. Enqueueing a placement update too would lead to duplicate saves.
     * @return True, if the spec was placed successfully. Otherwise, false.
     * */
    @ServerOnly
    fun placeSpec(
        player: LivingEntity,
        desiredOrientation: Rotation2d,
        provider: SpecProvider,
        isFreshlyPlacedSpecContainer: Boolean = false,
        saveTag: CompoundTag? = null,
    ) : Boolean {
        requireIsOnServerThread {
            "Cannot explicitly place spec on client side"
        }

        if(placementCollides(player, desiredOrientation, provider)) {
            return false
        }

        val context = createSpecPlacementInfo(player, desiredOrientation, provider)

        val spec = provider.create(context)

        addSpec(spec)

        if(!isFreshlyPlacedSpecContainer) {
            placementUpdates.add(SpecUpdate(spec, SpecUpdateType.Add))
        }

        rebuildCollider()

        if (spec is ItemPersistent && spec.order == ItemPersistentLoadOrder.BeforeSim) {
            spec.loadFromItemNbt(saveTag)
        }

        spec.onPlaced()

        if (spec is SpecWithCell<*>) {
            CellConnections.insertFresh(this, spec.cell)
        }

        if (spec is ItemPersistent && spec.order == ItemPersistentLoadOrder.AfterSim) {
            spec.loadFromItemNbt(saveTag)
        }

        if (spec is SpecWithCell<*>) {
            spec.cell.bindGameObjects(listOf(this, spec))
        }

        setSaveDirty()
        setSyncDirty()

        return true
    }

    @ServerOnly
    override fun tryBreakByPlayer(player: Player): Boolean {
        val intersection = pickSpec(player.getViewRay())

        if(intersection != null) {
            if(intersection.second.tryBreakByPlayer(player)) {
                val tag = CompoundTag()
                breakSpec(intersection.second, tag)

                if(!player.isCreative) {
                    spawnDrop(placement.level as ServerLevel, intersection.second, tag)
                }
            }
        }

        return specsInternal.isEmpty()
    }

    override fun onBroken() {
        if(!placement.level.isClientSide) {
            specsInternal.values.toList().forEach {
                val tag = CompoundTag()
                breakSpec(it, tag, true)
                spawnDrop(placement.level as ServerLevel, it, tag)
            }
        }
    }

    /**
     * Removes a spec with full synchronization.
     * Notifies via [Spec.onBroken].
     * @param spec The spec to remove.
     * @param saveTag A tag to save part data, if required (for [ItemPersistent]s).
     * @param isContainerBreak If true, this means the container part is being destroyed. Cell specs got cleaned up by [PartCellContainer.cellUnbindAndDestroySuggested] so we must not clean them up here.
     * */
    @ServerOnly
    fun breakSpec(spec: Spec<*>, saveTag: CompoundTag? = null, isContainerBreak: Boolean = false) {
        require(specsInternal.values.contains(spec)) {
            "Tried to break spec $spec which was not present"
        }

        if(!isContainerBreak) {
            if (spec is SpecWithCell<*>) {
                spec.cell.unbindGameObjects()
                CellConnections.destroy(spec.cell, this)
            }
        }

        if (spec is ItemPersistent && saveTag != null) {
            spec.saveToItemNbt(saveTag)
        }

        removeSpec(spec)
        placementUpdates.add(SpecUpdate(spec, SpecUpdateType.Remove))

        spec.onBroken()

        setSaveDirty()
        setSyncDirty()

        rebuildCollider()
    }

    @ServerOnly
    override fun getServerSaveTag(): CompoundTag {
        val tag = CompoundTag()

        tag.putInt(PLACEMENT_ID, lastPlacementId)
        tag.putUUID(CONTAINER_ID, containerID)

        saveSpecs(tag, SaveType.ServerData)

        return tag
    }

    @ServerOnly
    override fun loadServerSaveTag(tag: CompoundTag) {
        lastPlacementId = tag.getInt(PLACEMENT_ID)
        containerID = tag.getUUID(CONTAINER_ID)

        loadSpecs(tag, SaveType.ServerData)

        specsInternal.values.forEach {
            it.onLoaded()
        }

        rebuildCollider()
    }

    override fun onUnloaded() {
        specsInternal.values.forEach {
            it.onUnloaded()
        }
    }

    override fun getClientSaveTag(): CompoundTag {
        val tag = CompoundTag()
        saveSpecs(tag, SaveType.ClientData)
        return tag
    }

    override fun loadClientSaveTag(tag: CompoundTag) {
        loadSpecs(tag, SaveType.ClientData)

        specsInternal.values.forEach {
            clientAddSpec(it)
        }

        rebuildCollider()
    }

    override fun onSyncSuggested() {
        specsInternal.values.forEach {
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
                    updateTag.put(NEW_SPEC, saveSpecCommon(spec, SaveType.ClientData))
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
                    val placementId = newSpecTag.getInt(PLACEMENT_ID)

                    if(specsInternal.containsKey(placementId)) {
                        LOG.error("Duplicate spec placement update!")
                    }
                    else {
                        val spec = unpackSpec(newSpecTag, SaveType.ClientData)
                        check(placementId == spec.placement.placementId)
                        specsInternal.putUnique(placementId, spec)
                        clientAddSpec(spec)
                    }
                }
                SpecUpdateType.Remove -> {
                    val id = updateTag.getInt(REMOVED_ID)
                    val spec = specsInternal[id]

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

        rebuildCollider()
    }

    @ServerOnly
    private fun packSpecUpdates(tag: CompoundTag) {
        requireIsOnServerThread()

        if(dirtySpecs.size == 0) {
            return
        }

        val partUpdatesTag = ListTag()

        dirtySpecs.forEach { spec ->
            if(!specsInternal.containsKey(spec.placement.placementId)) {
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

            val spec = specsInternal[placementId]

            if (spec == null) {
                LOG.error("Spec container $this $placement received update on $placementId, but spec is null!")
            } else {
                val syncTag = compound.get(TAG) as CompoundTag
                spec.handleSyncTag(syncTag)
            }
        }
    }

    private enum class SaveType {
        ServerData,
        ClientData
    }

    @ServerOnly
    private fun saveSpecCommon(spec: Spec<*>, saveType: SaveType): CompoundTag {
        requireIsOnServerThread()

        val tag = CompoundTag()

        tag.putResourceLocation(ID, spec.id)
        val placement = spec.placement
        tag.putVector3d(MOUNTING_POINT_WORLD, placement.mountingPointWorld)
        tag.putPose2d(SPECIAL_POSE, placement.specialPose)
        tag.putInt(PLACEMENT_ID, placement.placementId)

        val customTag = when(saveType) {
            SaveType.ServerData -> spec.getServerSaveTag()
            SaveType.ClientData -> spec.getClientSaveTag()
        }

        if (customTag != null) {
            tag.put(TAG, customTag)
        }

        return tag
    }

    @ServerOnly
    private fun saveSpecs(tag: CompoundTag, saveType: SaveType) {
        requireIsOnServerThread()

        val specsTag = ListTag()

        specsInternal.values.forEach { spec ->
            specsTag.add(saveSpecCommon(spec, saveType))
        }

        tag.put(SPECS, specsTag)
    }

    private fun loadSpecs(tag: CompoundTag, saveType: SaveType) {
        if (tag.contains(SPECS)) {
            val partsTag = tag.get(SPECS) as ListTag
            partsTag.forEach { partTag ->
                val specCompoundTag = partTag as CompoundTag
                val part = unpackSpec(specCompoundTag, saveType)

                addSpec(part)
            }
        } else {
            LOG.error("Spec at ${placement.position} had no saved data")
        }
    }

    private fun unpackSpec(tag: CompoundTag, saveType: SaveType): Spec<*> {
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
            when(saveType) {
                SaveType.ServerData -> spec.loadServerSaveTag(customTag)
                SaveType.ClientData -> spec.loadClientSaveTag(customTag)
            }
        }

        return spec
    }

    /**
     * Enqueues a part for renderer setup.
     * */
    @ClientOnly
    private fun clientAddSpec(spec: Spec<*>) {
        requireIsOnRenderThread()
        spec.onAddedToClient()
        renderUpdates.add(SpecUpdate(spec, SpecUpdateType.Add))
    }

    /**
     * Removes a part from the renderer.
     * */
    @ClientOnly
    private fun clientRemoveSpec(spec: Spec<*>) {
        requireIsOnRenderThread()
        spec.onBroken()
        renderUpdates.add(SpecUpdate(spec, SpecUpdateType.Remove))
    }

    private fun rebuildCollider() {
        var shape = if(specsInternal.isEmpty()) {
            partProviderShape
        }
        else {
            Shapes.empty()
        }

        specsInternal.values.forEach {
            shape = Shapes.joinUnoptimized(
                shape,
                Shapes.create(it.placement.shapeBoundingBox.cast()),
                BooleanOp.OR
            )
        }

        updateShape(shape)
    }

    override fun createRenderer(): SpecPartRenderer {
        return SpecPartRenderer(this)
    }

    fun bindRenderer(instance: SpecPartRenderer) {
        specsInternal.values.forEach {
            renderUpdates.add(SpecUpdate(it, SpecUpdateType.Add))
        }
    }

    override fun onUsedBy(context: PartUseInfo): InteractionResult {
        val item = context.player.getItemInHand(context.hand).item

        if(item is SpecItem || item is PartItem) {
            return InteractionResult.PASS
        }

        val ray = context.player.getViewRay()

        val intersection = pickSpec(ray)
            ?: return InteractionResult.FAIL

        val specContext = SpecUseInfo(
            context.player,
            context.hand,
            ray,
            intersection.first
        )

        return intersection.second.onUsedBy(specContext)
    }

    override fun submitDebugDisplay(builder: ComponentDisplayList) {
        builder.debug("Specs: ${specsInternal.size}")
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
        private const val CONTAINER_ID = "containerId"

        fun canPlaceSpecInSubstrate(
            level: Level,
            substratePos: BlockPos,
            mountingPointWorld: Vector3d,
            facing: FacingDirection,
            face: Direction,
            orientation: Rotation2d,
            collisionSize: Vector3d,
        ) : Boolean {
            if (!MultipartBlockEntity.isValidPartSubstrateBlock(level, substratePos)) {
                return false
            }

            val multipartPos = substratePos + face
            val blockEntity = level.getBlockEntity(multipartPos)

            if (blockEntity == null) {
                val state = level.getBlockState(multipartPos)

                if(!state.isAir && !state.`is`(Blocks.WATER)) {
                    return false
                }
            }
            else {
                if(blockEntity !is MultipartBlockEntity) {
                    return false
                }
            }

            val box = SpecGeometry.boundingBox(mountingPointWorld, orientation, collisionSize, facing, face)

            return !GridCollisions.intersects(level, box)
        }

        fun createSpecDropStack(id: ResourceLocation, saveTag: CompoundTag?, count: Int = 1): ItemStack {
            val item = SpecRegistry.getSpecItem(id)
            val stack = ItemStack(item, count)

            stack.tag = saveTag

            return stack
        }

        private fun spawnDrop(pLevel: ServerLevel, removedSpec: Spec<*>, saveTag: CompoundTag) {
            val center = removedSpec.placement.orientedBoundingBoxWorld.center

            pLevel.addItem(center.x, center.y, center.z, createSpecDropStack(removedSpec.id, saveTag))
        }

        @ClientOnly
        fun renderHighlightEvent(event: RenderHighlightEvent.Block) {
            val level = Minecraft.getInstance().level
                ?: return

            val player = event.camera.entity as? LivingEntity
                ?: return

            val multipart = level.getBlockEntity(event.target.blockPos) as? MultipartBlockEntity
                ?: return

            val part = multipart.pickPart(player) as? SpecContainerPart
                ?: return

            event.isCanceled = true // Prevent collider bounding box

            val spec = part.pickSpec(player)?.second
                ?: return

            event.multiBufferSource.getBuffer(RenderType.lines()).eln2SubmitOBBAtLevelStage(
                event.poseStack,
                spec.placement.orientedBoundingBoxWorld,
                RGBAFloat(0.1f, 0.5f, 0.8f, 0.9f),
                event.camera
            )
        }
    }

    private inline fun forEachCellSpec(action: (SpecWithCell<*>) -> Unit) {
        specsInternal.values.forEach {
            if(it is SpecWithCell<*>) {
                action(it)
            }
        }
    }

    override fun cellUnbindAndDestroySuggested() {
        forEachCellSpec {
            it.cell.unbindGameObjects()
            CellConnections.destroy(it.cell, this)
        }
    }

    // These 2 probably don't get called:

    override fun cellConnectionsInsertFreshSuggested() {
        forEachCellSpec {
            CellConnections.insertFresh(this, it.cell)
        }
    }

    override fun cellBindGameObjectsSuggested() {
        forEachCellSpec {
            it.cell.bindGameObjects(listOf(this, it))
        }
    }

    private fun getCellSpec(actualCell: Cell): SpecWithCell<*> {
        val placementId = actualCell.locator.requireLocator(Locators.PLACEMENT_ID) {
            "actual cell did not have placement ID!"
        }

        val spec = checkNotNull(specsInternal[placementId]) {
            "Actual cell had placement id that is not in this container!"
        }

        check(spec is SpecWithCell<*>) {
            "Cell had spec that is not a cell spec!"
        }

        return spec
    }

    override fun getCells() = specsInternal.values.mapNotNull { (it as? SpecWithCell<*>)?.cell }
    override fun neighborScan(actualCell: Cell) = getCellSpec(actualCell).neighborScan()
    override fun onCellConnected(actualCell: Cell, remoteCell: Cell) = getCellSpec(actualCell).onConnected(remoteCell)
    override fun onCellDisconnected(actualCell: Cell, remoteCell: Cell) = getCellSpec(actualCell).onDisconnected(remoteCell)

    override fun onTopologyChanged() {
        setSaveDirty()
    }

    override val manager: CellGraphManager
        get() = CellGraphManager.getFor(placement.level as? ServerLevel ?: error("Tried to get spec cell graph manager on ${placement.level}"))
}

class SpecPartRenderer(val specPart: SpecContainerPart) : PartRenderer() {
    private val specs = ReferenceArraySet<Spec<*>>()

    private var frameInstance: ModelData? = null

    override fun setupRendering() {
        frameInstance?.delete()
        //frameInstance = createPartInstance(multipart, PartialModels.SPEC_PART_FRAME, specPart, 0.0)
        multipart.relightModels(frameInstance)
        specPart.bindRenderer(this)
    }

    override fun beginFrame() {
        handleSpecUpdates()

        for (spec in specs) {
            spec.renderer.beginFrame()
        }
    }

    private fun handleSpecUpdates() {
        while (true) {
            val update = specPart.renderUpdates.poll() ?: break
            val spec = update.spec

            when (update.type) {
                SpecUpdateType.Add -> {
                    if(specs.add(spec)) {
                        spec.renderer.setupRendering(this)
                        spec.renderer.relight(RelightSource.Setup)
                    }
                }
                SpecUpdateType.Remove -> {
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

        multipart.relightModels(frameInstance)
    }

    override fun remove() {
        frameInstance?.delete()

        specs.forEach {
            it.destroyRenderer()
        }
    }
}

class SpecItem(val provider: SpecProvider) : PartItem(PartRegistry.SPEC_CONTAINER_PART.part) {
    override fun useOn(pContext: UseOnContext): InteractionResult {
        val player = pContext.player
            ?: return InteractionResult.FAIL

        val level = pContext.level
        val substratePos = pContext.clickedPos
        val face = pContext.clickedFace
        val multipartPos = substratePos + face
        val overlayState = SpecPlacementOverlay.getSnapshot(level, player)
        val partMountingPoint = multipartPos.toVector3d() + Vector3d(0.5) - face.vector3d * 0.5
        val substratePlane = Plane3d(face.vector3d, partMountingPoint)
        val mountingPoint = player.getViewRay() intersectionWith substratePlane

        if(!SpecContainerPart.canPlaceSpecInSubstrate(
                level,
                substratePos,
                mountingPoint,
                MultipartBlockEntity.getHorizontalFacing(face, player),
                face,
                overlayState.orientation,
                provider.placementCollisionSize
        )) {
            return InteractionResult.FAIL
        }

        fun getPart() : SpecContainerPart? {
            val blockEntity = level.getBlockEntity(multipartPos) as? MultipartBlockEntity
                ?: return null

            return blockEntity.getPart(face) as? SpecContainerPart
        }

        var isNewPart = false

        if(getPart() == null) {
            if(pContext.level.isClientSide) {
                return InteractionResult.SUCCESS // Assume it works out
            }

            super.useOn(pContext) // Place container part
            isNewPart = true
        }

        val part = getPart()
           ?: return InteractionResult.FAIL


        fun cleanupNew() {
            if(isNewPart) {
                check(!pContext.level.isClientSide)
                (level as ServerLevel).destroyPart(part, false)
                LOG.debug("destroy new")
            }
        }

        if(part.placementCollides(player, overlayState.orientation, provider)) {
            cleanupNew()
            return InteractionResult.FAIL
        }

        LOG.debug("Placing")
        
        if(pContext.level.isClientSide) {
            return InteractionResult.SUCCESS // Assume it works out
        }

        if(!part.placeSpec(player, overlayState.orientation, provider, isFreshlyPlacedSpecContainer = isNewPart)) {
            LOG.debug("Did not place")
            cleanupNew()
            return InteractionResult.FAIL
        }

        return InteractionResult.CONSUME
    }
}

abstract class SpecProvider {
    val id: ResourceLocation get() = SpecRegistry.getId(this)

    // Maybe add context to select model based on stuff
    open fun getModelForPreview() : PartialModel? = null

    fun create(context: SpecPlacementInfo): Spec<*> {
        val instance = createCore(context)
        instance.onCreated()
        return instance
    }

    protected abstract fun createCore(context: SpecPlacementInfo) : Spec<*>

    abstract val placementCollisionSize: Vector3d
}

fun interface SpecFactory {
    operator fun invoke(ci: SpecCreateInfo) : Spec<*>
}

open class BasicSpecProvider(
    val previewModel: PartialModel?,
    final override val placementCollisionSize: Vector3d,
    val factory: SpecFactory,
) : SpecProvider() {
    constructor(placementCollisionSize: Vector3d, factory: (ci: SpecCreateInfo) -> Spec<*>) : this(null, placementCollisionSize, factory)

    override fun getModelForPreview(): PartialModel? = previewModel

    override fun createCore(context: SpecPlacementInfo) = factory(SpecCreateInfo(id, context))

    companion object {
        fun setup(previewModel: PartialModel?, placementCollisionSize: Vector3d, block: () -> (SpecFactory)) = BasicSpecProvider(previewModel, placementCollisionSize, block())
    }
}

/**
 * [Spec]s are entity-like units that exist in a [SpecContainerPart]. They are similar to normal block entities,
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
     * Called after the spec was constructed by the provider.
     * */
    open fun onCreated() { }

    /**
     * Saves data that should be persisted.
     * */
    @ServerOnly
    open fun getServerSaveTag(): CompoundTag? = null

    /**
     * Saves data that should be sent to the client when the part is placed or when the part is first sent to the client.
     * */
    @ClientOnly
    open fun getClientSaveTag(): CompoundTag? = getSyncTag()

    /**
     * Loads the data saved by [getServerSaveTag].
     * */
    @ServerOnly
    open fun loadServerSaveTag(tag: CompoundTag) { }

    /**
     * Loads the data sent by [getClientSaveTag].
     * */
    @ClientOnly
    open fun loadClientSaveTag(tag: CompoundTag) = handleSyncTag(tag)

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
     * Called when the spec is right-clicked by a player.
     * */
    open fun onUsedBy(context: SpecUseInfo): InteractionResult {
        return InteractionResult.FAIL
    }

    /**
     * Called when the player tries to destroy the spec, on the server.
     * @return True, if the spec shall break. Otherwise, false.
     * */
    @ServerOnly
    open fun tryBreakByPlayer(player: Player) : Boolean {
        return true
    }

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

data class SpecPlacementOverlayState(
    val orientation: Rotation2d,
)

data class SpecOverlayMessage(val state: SpecPlacementOverlayState) {
    companion object {
        fun encode(message: SpecOverlayMessage, buf: FriendlyByteBuf) {
            val state = message.state
            buf.writeRotation2d(state.orientation)
        }

        fun decode(buf: FriendlyByteBuf) = SpecOverlayMessage(
            SpecPlacementOverlayState(
                buf.readRotation2d()
            )
        )

        fun handle(message: SpecOverlayMessage, supplier: Supplier<NetworkEvent.Context>) {
            val ctx = supplier.get()

            ctx.enqueueWork {
                SpecPlacementOverlayServer.setState(message.state, ctx.sender)
            }

            ctx.packetHandled = true
        }
    }
}

@ClientOnly
private fun getClientSpecItem() : SpecItem? {
    requireIsOnRenderThread()

    val player = Minecraft.getInstance().player
        ?: return null

    val stack = player.getItemInHand(InteractionHand.MAIN_HAND)

    return stack.item as? SpecItem
}

@ClientOnly
object SpecPlacementOverlayClient : IGuiOverlay {
    private val DEFAULT_ORIENTATIONS = listOf(
        Rotation2d(1.0, 0.0),
        Rotation2d(0.0, 1.0),
        Rotation2d(-1.0, 0.0),
        Rotation2d(0.0, -1.0)
    )

    private var orientation = Rotation2d.identity

    val CYCLE_ORIENTATION = KeyMapping(
        "key.$MODID.cycle_spec_orientation",
        KeyConflictContext.UNIVERSAL,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_R,
        "key.$MODID.category"
    )

    fun createSnapshot() = SpecPlacementOverlayState(
        orientation
    )

    override fun render(
        gui: ForgeGui,
        guiGraphics: GuiGraphics,
        partialTick: Float,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        getClientSpecItem() ?: return

        val font = gui.font

        guiGraphics.drawString(
            font,
            "θ×=${Math.toDegrees(orientation.ln()).formatted(2)}°",
            0,
            0,
            color(255, 0, 0, 255)
        )
    }

    fun onScroll(event: InputEvent.MouseScrollingEvent) {
        val player = Minecraft.getInstance().player
            ?: return

        if(!player.isShiftKeyDown) {
            return
        }

        getClientSpecItem() ?: return

        if(event.scrollDelta.approxEq(0.0)) {
            return
        }

        orientation *= Rotation2d.exp(
            event.scrollDelta * 1e-2
        )

        event.isCanceled = true

        sendSnapshot()
    }

    fun onCycleOrientation(event: InputEvent.Key) {
        getClientSpecItem() ?: return

        if(CYCLE_ORIENTATION.key.value == event.key && event.action == InputConstants.RELEASE) {
            val closest = DEFAULT_ORIENTATIONS.minBy { abs((it / orientation).ln()) }

            orientation = if(closest.approxEq(orientation)) {
                DEFAULT_ORIENTATIONS[(DEFAULT_ORIENTATIONS.indexOf(closest) + 1) % DEFAULT_ORIENTATIONS.size]
            }
            else {
                closest
            }

            sendSnapshot()
        }
    }

    private fun sendSnapshot() {
        Networking.sendToServer(SpecOverlayMessage(createSnapshot()))
    }
}

@ServerOnly
object SpecPlacementOverlayServer {
    private val states = WeakHashMap<ServerPlayer, SpecPlacementOverlayState>()

    fun getState(player: ServerPlayer) = states[player] ?: SpecPlacementOverlayState(
        Rotation2d.identity
    )

    fun clear() {
        states.clear()
    }

    fun setState(state: SpecPlacementOverlayState, sender: ServerPlayer?) {
        requireIsOnServerThread()

        if(sender == null) {
            return
        }

        states[sender] = state
    }
}

object SpecPlacementOverlay {
    fun getSnapshot(level: Level, player: Player) = if(level.isClientSide) {
        SpecPlacementOverlayClient.createSnapshot()
    }
    else {
        SpecPlacementOverlayServer.getState(player as ServerPlayer)
    }
}

@ClientOnly
object SpecPreviewRenderer {
    private val CAN_PLACE_COLOR = RGBAFloat(0.1f, 1.0f, 0.15f, 0.5f)
    private val CANNOT_PLACE_COLOR = RGBAFloat(1.0f, 0.1f, 0.2f, 0.6f)
    private val CAN_PLACE_PREVIEW_COLOR = RGBAFloat(0.1f, 1.0f, 0.15f, 0.9f)
    private val CANNOT_PLACE_PREVIEW_COLOR = RGBAFloat(1.0f, 0.1f, 0.2f, 0.2f)
    private const val AXIS_THICKNESS = 0.01
    private const val CAN_PLACE_AXIS_ALPHA = 0.8f
    private const val CANNOT_PLACE_AXIS_ALPHA = 0.1f

    fun render(event: RenderLevelStageEvent) {
        if(event.stage != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return
        }

        val player = Minecraft.getInstance().player
            ?: return

        val level = Minecraft.getInstance().level
            ?: return

        val item = getClientSpecItem()
            ?: return

        val clipSize = player.getClipStartEnd()

        val clipResult = player.pick(
            clipSize.first.distanceTo(clipSize.second),
            event.partialTick,
            false
        )

        if(clipResult.type != HitResult.Type.BLOCK) {
            return
        }

        clipResult as BlockHitResult

        val substratePos = clipResult.blockPos
        val face = clipResult.direction
        val multipartPos = substratePos + face

        val blockEntity = level.getBlockEntity(multipartPos)
        val specContainer = (blockEntity as? MultipartBlockEntity)?.getPart(face) as? SpecContainerPart

        val mountingPoint = clipResult.location.cast()

        val overlayState = SpecPlacementOverlayClient.createSnapshot()

        val canPlace = run {
            if(!SpecContainerPart.canPlaceSpecInSubstrate(
                    level,
                    substratePos,
                    mountingPoint,
                    MultipartBlockEntity.getHorizontalFacing(face, player),
                    face,
                    overlayState.orientation,
                    item.provider.placementCollisionSize
            )) {
                return@run false
            }

            if(blockEntity != null) {
                if(blockEntity !is MultipartBlockEntity) {
                    return@run false
                }

                if(specContainer != null) {
                    return@run !specContainer.placementCollides(player, overlayState.orientation, item.provider)
                }
            }

            if(!MultipartBlockEntity.canPlacePartInSubstrate(level, substratePos, face, item.partProvider.value, player)) {
                return@run false
            }

            if(blockEntity != null && (blockEntity as MultipartBlockEntity).placementCollides(player, face, item.partProvider.value)) {
                return@run false
            }

            if(!item.partProvider.value.canPlace(level, substratePos, face)) {
                return@run false
            }

            return@run true
        }

        val stack = event.poseStack

        stack.pushPose()

        stack.translate(
            -event.camera.position.x + substratePos.x.toDouble(),
            -event.camera.position.y + substratePos.y.toDouble(),
            -event.camera.position.z + substratePos.z.toDouble()
        )

        val (dx, dy, dz) = partOffsetTable[face.get3DDataValue()]
        val (dx1, dy1, dz1) = mountingPoint - (substratePos.toVector3d() + Vector3d(0.5) - face.vector3d * 0.5)

        stack.translate(dx + dx1, dy + dy1, dz + dz1)

        stack.mulPose(face.rotationFast)

        stack.mulPose(
            if (specContainer != null) {
                Quaternionf(specContainer.placement.facing.rotation)
            } else {
                Quaternionf(MultipartBlockEntity.getHorizontalFacing(face, player).rotation)
            }
        )

        stack.mulPose(Quaternionf().rotateY(overlayState.orientation.ln().toFloat()))
        stack.translate(0.0, item.provider.placementCollisionSize.y * 0.5, 0.0)

        submitBoxAndAxes(
            stack.last(),
            if (canPlace) CAN_PLACE_COLOR else CANNOT_PLACE_COLOR,
            if (canPlace) CAN_PLACE_AXIS_ALPHA else CANNOT_PLACE_AXIS_ALPHA,
            item.provider.placementCollisionSize
        )

        stack.translate(-0.5, -item.provider.placementCollisionSize.y * 0.5, -0.5)

        submitPreviewModel(
            stack.last(),
            if(canPlace) CAN_PLACE_PREVIEW_COLOR else CANNOT_PLACE_PREVIEW_COLOR,
            item.provider
        )

        stack.popPose()
    }

    private fun submitBoxAndAxes(pose: PoseStack.Pose, placeColor: RGBAFloat, placeAlpha: Float, placementCollisionSize: Vector3d) {
        val vertexConsumer = Minecraft.getInstance()
            .renderBuffers()
            .bufferSource()
            .getBuffer(RenderType.lines())

        vertexConsumer.eln2SubmitAABBLines(
            pose,
            BoundingBox3d.fromCenterSize(
                Vector3d.zero,
                placementCollisionSize
            ),
            placeColor
        )

        vertexConsumer.eln2SubmitAABBLines(
            pose,
            BoundingBox3d.fromCenterSize(
                Vector3d(0.5, 0.0, 0.0),
                Vector3d(0.5, AXIS_THICKNESS, AXIS_THICKNESS)
            ),
            RGBAFloat(1.0f, 0.0f, 0.0f, placeAlpha)
        )

        vertexConsumer.eln2SubmitAABBLines(
            pose,
            BoundingBox3d.fromCenterSize(
                Vector3d(0.0, 0.5, 0.0),
                Vector3d(AXIS_THICKNESS, 0.5, AXIS_THICKNESS)
            ),
            RGBAFloat(0.0f, 1.0f, 0.0f, placeAlpha)
        )

        vertexConsumer.eln2SubmitAABBLines(
            pose,
            BoundingBox3d.fromCenterSize(
                Vector3d(0.0, 0.0, 0.5),
                Vector3d(AXIS_THICKNESS, AXIS_THICKNESS, 0.5)
            ),
            RGBAFloat(0.0f, 0.0f, 1.0f, placeAlpha)
        )
    }

    private fun submitPreviewModel(pose: PoseStack.Pose, previewColor: RGBAFloat, provider: SpecProvider) {
        val model = provider.getModelForPreview()
            ?: return

        val renderType = RenderType.solid()
        val vertexConsumer = Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(renderType)

        vertexConsumer.eln2SubmitUnshadedBakedModelQuads(
            renderType,
            pose,
            model.get(),
            previewColor
        )
    }
}

/**
 * [Spec] that uses the grid system by managing a [gridTerminalSystem].
 * */
abstract class GridSpec<Renderer : SpecRenderer>(ci: SpecCreateInfo) : Spec<Renderer>(ci), GridTerminalContainer {
    val locator = placement.createLocator()

    /**
     * Gets the grid terminal system of this spec. Its lifetime is managed by the spec.
     * */
    val gridTerminalSystem = GridTerminalSystem(placement.level)

    /**
     * Creates a bounding box in the fixed frame.
     * @param x Center X in the local frame.
     * @param y Center Y in the local frame.
     * @param z Center Z in the local frame.
     * @param sizeX Size along X in the local frame.
     * @param sizeY Size along Y in the local frame.
     * @param sizeZ Size along Z in the local frame.
     * @return A bounding box in the world frame.
     * */
    protected fun boundingBox(
        x: Double,
        y: Double,
        z: Double,
        sizeX: Double,
        sizeY: Double,
        sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
    ) = SpecGeometry.boundingBox(
        placement.mountingPointWorld +
            placement.positiveX * x +
            placement.positiveY * y +
            placement.positiveZ * z,
        orientation * placement.orientation,
        Vector3d(sizeX, sizeY, sizeZ),
        placement.part.placement.facing,
        placement.face
    )

    @ServerOnly
    override fun onPlaced() {
        // called when placed, which initializes the terminals server-side
        // then, the spec gets sent to the client and they deserialize
        gridTerminalSystem.initializeFresh()
    }

    override fun getServerSaveTag(): CompoundTag? {
        return gridTerminalSystem.save(GridTerminalSystem.SaveType.Server)
    }

    override fun getClientSaveTag(): CompoundTag? {
        // This is the only way clients get initialized
        return gridTerminalSystem.save(GridTerminalSystem.SaveType.Client)
    }

    override fun loadServerSaveTag(tag: CompoundTag) {
        gridTerminalSystem.initializeSaved(tag)
    }

    override fun loadClientSaveTag(tag: CompoundTag) {
        gridTerminalSystem.initializeSaved(tag)
    }

    override fun pickTerminal(player: LivingEntity) = gridTerminalSystem.pick(player)

    override fun getTerminalByEndpointID(endpointID: UUID) = gridTerminalSystem.getByEndpointID(endpointID)

    override fun onBroken() {
        gridTerminalSystem.destroy()
    }
}

/**
 * Represents a part that has a cell.
 * */
interface SpecWithCell<C : Cell> {
    /**
     * This is the cell owned by the part.
     * */
    val cell: C

    /**
     * Indicates if the cell is available (loaded).
     * */
    val hasCell: Boolean

    /**
     * @return The provider associated with the cell.
     * */
    val provider: CellProvider<C>

    /**
     * Called when the cell part is connected to another cell.
     * */
    fun onConnected(remoteCell: Cell)

    /**
     * Called when the cell part is disconnected from another cell. This may happen when the part is being destroyed.
     * */
    fun onDisconnected(remoteCell: Cell)

    /**
     * Called when the cell part is connected/disconnected. This is not called if the part is being destroyed.
     * */
    fun onConnectivityChanged() { }

    fun neighborScan() : List<CellAndContainerHandle>
}

abstract class CellSpec<C : Cell, R : SpecRenderer>(
    ci: SpecCreateInfo,
    final override val provider: CellProvider<C>,
) : GridSpec<R>(ci), SpecWithCell<C> {
    companion object {
        private const val GRAPH_ID = "GraphID"
        private const val CUSTOM_SIMULATION_DATA = "SimulationData"
    }

    private var cellField: C? = null

    /**
     * The actual cell contained within this spec.
     * It only exists on the server (it is a simulation-only item)
     * */
    @ServerOnly
    final override val cell: C get() = cellField
        ?: error(
            if(placement.level.isClientSide) {
                "TRIED TO ACCESS SPEC CELL ON CLIENT"
            } else {
                "Tried to get spec cell before it is set $this"
            }
        )

    final override val hasCell: Boolean
        get() = cellField != null

    /**
     * Used by the loading procedures.
     * */
    @ServerOnly
    private lateinit var loadGraphId: UUID

    @ServerOnly
    private var customSimulationData: CompoundTag? = null

    /**
     * Creates the cell, sets [Cell.container] and notifies via [onCellAcquired].
     * */
    override fun onPlaced() {
        super.onPlaced()
        cellField = provider.create(locator, CellEnvironment.evaluate(placement.level, locator))
        cell.container = placement.part
        if(cell.hasNode<GridNode>()) {
            cell.requireNode<GridNode>().mapFromGridTerminalSystem(gridTerminalSystem)
        }
        onCellAcquired()
    }

    /**
     * Notifies the cell that the container has been removed.
     * */
    override fun onUnloaded() {
        super.onUnloaded()
        if (hasCell) {
            requireIsOnServerThread { "onUnloaded spec cell is null $this" }
            cell.onContainerUnloading()
            cell.container = null
            cell.onContainerUnloaded()
            cell.unbindGameObjects()
            onCellReleased()
        }
    }

    /**
     * The saved data includes the Graph ID. This is used to fetch the cell after loading.
     * */
    override fun getServerSaveTag(): CompoundTag? {
        val tag = super.getServerSaveTag() ?: CompoundTag()

        if (!hasCell) {
            LOG.fatal("Spec saving, but cell not initialized!")
            return tag
        }

        tag.putUUID(GRAPH_ID, cell.graph.id)

        saveCustomSimData()?.also {
            tag.put(CUSTOM_SIMULATION_DATA, it)
        }

        return tag
    }

    /**
     * This method gets the graph ID from the saved data.
     * The level is not available at this point, so we defer cell fetching to the onLoaded method.
     * */
    override fun loadServerSaveTag(tag: CompoundTag) {
        super.loadServerSaveTag(tag)

        if (placement.level.isClientSide) {
            return
        }

        if (tag.contains(GRAPH_ID)) {
            loadGraphId = tag.getUUID(GRAPH_ID)
        } else {
            LOG.info("Part at $locator did not have saved data")
        }

        tag.useSubTagIfPreset(CUSTOM_SIMULATION_DATA) { customSimulationData = it }
    }

    /**
     * This is the final stage of loading. We have the level, so we can fetch the cell using the saved data.
     * */
    @Suppress("UNCHECKED_CAST")
    override fun onLoaded() {
        super.onLoaded()

        if (placement.level.isClientSide) {
            return
        }

        cellField = if (!this::loadGraphId.isInitialized) {
            LOG.fatal("Spec cell not initialized!")
            // Should we blow up the game or make the cell fresh?
            provider.create(locator, CellEnvironment.evaluate(placement.level, locator))
        } else {
            CellGraphManager.getFor(placement.level as ServerLevel)
                .getGraph(loadGraphId)
                .getCellByLocator(locator) as C
        }

        cell.container = placement.multipart
        cell.onContainerLoaded()

        if (this.customSimulationData != null) {
            loadCustomSimDataPre(customSimulationData!!)
        }

        onCellAcquired()

        if (this.customSimulationData != null) {
            loadCustomSimDataPost(customSimulationData!!)
            this.customSimulationData = null
        }

        cell.bindGameObjects(listOf(this, placement.multipart))
    }

    /**
     * Saves custom data to the simulation storage (separate from the block entity and chunks)
     * */
    open fun saveCustomSimData(): CompoundTag? {
        return null
    }

    /**
     * Loads custom data from the simulation storage, just before the cell is acquired.
     * */
    open fun loadCustomSimDataPre(tag: CompoundTag) {}

    /**
     * Loads custom data from the simulation storage, after the cell is acquired.
     * */
    open fun loadCustomSimDataPost(tag: CompoundTag) {}

    override fun onConnected(remoteCell: Cell) {}

    override fun onDisconnected(remoteCell: Cell) {}

    open fun onCellAcquired() {}
    open fun onCellReleased() {}

    override fun neighborScan() : List<CellAndContainerHandle> {
        val results = ArrayList<CellAndContainerHandle>()

        gridTerminalSystem.forEachTerminalOfType<CellTerminal> {
            if(it.stagingCell != null) {
                results.add(CellAndContainerHandle.captureInScope(it.stagingCell!!))
            }
        }

        if(hasCell) {
            cell.ifNode<GridNode> { node ->
                node.forEachConnectionCell {
                    results.add(CellAndContainerHandle.captureInScope(it))
                }
            }
        }

        return results
    }

    protected fun defineCellBoxTerminal(box3d: OrientedBoundingBox3d, attachment: Vector3d? = null, highlightColor : RGBAFloat? = RGBAFloat(
        1f,
        0.58f,
        0.44f,
        0.8f
    ), categories: List<GridMaterialCategory>) = gridTerminalSystem.defineTerminal<GridTerminal>(
        TerminalFactories(
            { ci ->
                CellTerminal(ci, locator, attachment ?: box3d.center, box3d) { this.cell }.also {
                    it.categories.addAll(categories)
                }
            },
            { GridTerminalClient(it, locator, attachment ?: box3d.center, box3d, highlightColor) }
        )
    )
    protected fun defineCellBoxTerminal(
        x: Double, y: Double, z: Double,
        sizeX: Double, sizeY: Double, sizeZ: Double,
        orientation: Rotation2d = Rotation2d.identity,
        attachment: Vector3d? = null,
        highlightColor: RGBAFloat? = RGBAFloat(1f, 0.58f, 0.44f, 0.8f),
        categories: List<GridMaterialCategory> = listOf(GridMaterialCategory.MicroGrid),
    ) = defineCellBoxTerminal(boundingBox(x, y, z, sizeX, sizeY, sizeZ, orientation), attachment, highlightColor, categories)
}
