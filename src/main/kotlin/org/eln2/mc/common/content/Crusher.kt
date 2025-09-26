package org.eln2.mc.common.content

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import kotlinx.serialization.Serializable
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.items.ItemStackHandler
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.mathematics.geometry.Rotation2d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.mathematics.rounded
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.ageseries.libage.sim.electrical.mna.ElectricalComponentSet
import org.ageseries.libage.sim.electrical.mna.component.updateResistance
import org.eln2.mc.*
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.SpecialModels
import org.eln2.mc.common.blocks.foundation.CellBlock
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.containers.ContainerHelper
import org.eln2.mc.common.containers.MyAbstractContainerScreen
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicate
import org.eln2.mc.common.network.serverToClient.BulkPacketHandlerBlockEntity
import org.eln2.mc.common.network.serverToClient.ClientSidePacketHandlerBuilder
import org.eln2.mc.common.network.serverToClient.sendBulkPacket
import org.eln2.mc.common.recipes.foundation.INPUT_SLOT
import org.eln2.mc.common.recipes.foundation.OUTPUT_SLOT
import org.eln2.mc.common.recipes.foundation.SimpleProcessingRecipe
import org.eln2.mc.common.recipes.foundation.SimpleProcessingRecipeInventoryHandler
import org.eln2.mc.common.sounds.foundation.SimpleLoopingBlockEntitySoundInstance
import org.eln2.mc.common.sounds.foundation.SoundInfo
import org.eln2.mc.common.sounds.foundation.SoundInstanceTickEvent
import org.eln2.mc.data.Pole
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.evaluate
import org.eln2.mc.extensions.canCrush
import org.eln2.mc.extensions.constructMenuHelper2
import org.eln2.mc.extensions.nextDouble
import org.eln2.mc.integration.ComponentDisplay
import org.eln2.mc.integration.ComponentDisplayList
import org.joml.Vector3f
import java.util.function.Consumer
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Crusher model. If active (needs to crush):
 * - The device doesn't start (speed = 0) when the potential is under the threshold, but still draws power (tries to draw [nominalPower]).
 * - The device keeps ~[nominalPower] when the potential is in the specified range.
 * - Speed is proportional to input power.
 * - The device starts increasing in power draw like a resistor when the potential is over the specified range.
 * - A fraction of the input power is converted into heat, setting a hard cap on the amount of work you can do.
 * - The device is destroyed when it reaches a certain temperature, and also has dielectric breakdown.
 * @param nominalPotential The target potential.
 * @param nominalPower The target power. It is delivered in the range of [[potentialThresholdFactor] * [nominalPotential], [maxRatedPotentialFactor] * [nominalPotential]].
 * @param potentialThresholdFactor The device doesn't run if the potential is negative or less than [potentialThresholdFactor] * [nominalPotential].
 * @param maxRatedPotentialFactor The device's efficiency starts to degrade and the power draw starts increasing when the potential is above [maxRatedPotentialFactor] * [nominalPotential].
 * @param thermalFactor `power` * [thermalFactor] of the power is converted into heat.
 * @param baseSpeedFactor The device's raw speed is [baseSpeedFactor] * (`power` / [nominalPower]).
 * */
data class CrusherOptions(
    val idleResistance: Quantity<Resistance>,
    val probingResistance: Quantity<Resistance>,
    val minResistance: Quantity<Resistance>,
    val nominalPotential: Quantity<Potential>,
    val nominalPower: Quantity<Power>,
    val potentialThresholdFactor: Double,
    val maxRatedPotentialFactor: Double,
    val thermalFactor: Double,
    val baseSpeedFactor: Double,
    val massDef: ThermalMassDefinition,
    val leakageParameters: ConnectionParameters,
    val destroyTemperature: Quantity<Temperature>,
    val dielectricBreakdownPotential: Quantity<Potential>,
)

/**
 * Object emulating the behavior described in [CrusherOptions] using a [TheveninEstimatingResistor].
 * Uses the Thevenin estimates to select the behavior based on the estimated open-circuit potential, and to create the constant load in the target regions.
 * */
class CrusherElectricalObject(cell: CrusherCell) : ElectricalObject<CrusherCell>(cell) {
    val resistor = TheveninEstimatingResistor().also {
        it.resistance = !cell.options.idleResistance
    }

    val resistorDisplay = resistor.display()

    override fun offerPolar(remote: ElectricalObject<*>) = when(cell.electricalMap.evaluate(cell, remote.cell)) {
        Pole.Plus -> resistor.offerPositive()
        Pole.Minus -> resistor.offerNegative()
    }

    /**
     * The base grinding speed, calculated each tick.
     * */
    var grindingSpeed = 0.0

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::tick)
    }

    override fun addComponents(circuit: ElectricalComponentSet) {
        super.addComponents(circuit)

        circuit.add(resistor)
    }

    /**
     * Converts the input power into some thermal power and updates the [grindingSpeed].
     * */
    private fun tick(dt: Double, subscriberPhase: SubscriberPhase) {
        val options = cell.options

        // Convert fraction of power to heat:
        if(resistor.potential > 0.0 && resistor.power > 0.0) {
            val energy = options.thermalFactor * resistor.power * dt

            if(!energy.approxEq(0.0)) {
                cell.thermalWire.thermalBody.energy += Quantity(energy, JOULE)
                cell.setChanged()
            }
        }

        fun setLoad(power: Double) {
            resistor.setLoad(power, !options.minResistance, !options.probingResistance)
        }

        if(!cell.isActive) {
            grindingSpeed = 0.0
            resistor.resistance = !options.idleResistance
            return
        }

        val potential = resistor.openCircuitPotentialEstimate

        if(potential <= 0.0) {
            grindingSpeed = 0.0
            setLoad(0.0)
            return
        }

        val potentialThreshold = options.potentialThresholdFactor * !options.nominalPotential

        val isGrinding: Boolean

        if(potential < potentialThreshold) {
            // Draw nominal power even if we can't drive the grinding.
            setLoad(!options.nominalPower)
            isGrinding = false
        }
        else {
            val maxPotential = options.maxRatedPotentialFactor * !options.nominalPotential

            if(potential > maxPotential) {
                // Over-potential. Act like a resistor now:
                val r = ((maxPotential * maxPotential) / !options.nominalPower).coerceAtLeast(1e-6)
                resistor.updateResistance(r)
            }
            else {
                // Operating within margin. Consume ~nominalPower:
                setLoad(!options.nominalPower)
            }

            isGrinding = true
        }

        grindingSpeed = if (isGrinding) {
            options.baseSpeedFactor * (resistor.power.absoluteValue / !options.nominalPower)
        } else {
            0.0
        }
    }
}

class CrusherCell(ci: CellCreateInfo, val options: CrusherOptions, override val electricalMap: PoleMap, override val thermalMap: PoleMap) : Cell(ci), SidedElectricalMapped<CrusherCell>, SidedThermalMapped<CrusherCell> {
    override val electricalSize: ElectricalSize
        get() = ElectricalSize.Any

    override val thermalSize: ThermalSize
        get() = ThermalSize.Any

    /**
     * Set this flag from the game thread to indicate if the crusher is active.
     * */
    var isActive = false

    @SimObject
    val thermalWire = ThermalWireObject(
        this,
        options.massDef(),
        options.leakageParameters
    )

    @SimObject
    val crusher = CrusherElectricalObject(this)

    @Behavior
    val temperatureExplosion = TemperatureExplosionBehavior.create(
        options.destroyTemperature,
        this,
        thermalWire.thermalBody::temperature
    )

    @Behavior
    val dielectricBreakdown = DielectricBreakdownBehavior.create(this).also {
        it.addPort(
            crusher.resistor,
            !options.dielectricBreakdownPotential,
            !options.dielectricBreakdownPotential
        )
    }
}

class CrusherContainerData : SimpleContainerData(1) {
    companion object {
        private const val PROGRESS = 0
    }

    var progress: Float
        get() = Float.fromBits(this.get(PROGRESS))
        set(value) { this.set(PROGRESS, value.toBits()) }
}

class CrusherBlock : CellBlock<CrusherCell>() {
    @Deprecated("Deprecated in Java", ReplaceWith("true"))
    override fun skipRendering(pState: BlockState, pAdjacentBlockState: BlockState, pDirection: Direction): Boolean {
        return true
    }

    override fun getCellProvider(): CellProvider<CrusherCell> {
        return Content.BASIC_CRUSHER_CELL.get()
    }

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState): BlockEntity {
        return CrusherBlockEntity(pPos, pState)
    }

    override fun <T : BlockEntity?> getTicker(pLevel: Level, pState: BlockState, pBlockEntityType: BlockEntityType<T?>): BlockEntityTicker<T> {
        return BlockEntityTicker(CrusherBlockEntity::tick)
    }

    @Deprecated("Deprecated in Java")
    override fun use(
        pState: BlockState,
        pLevel: Level,
        pPos: BlockPos,
        pPlayer: Player,
        pHand: InteractionHand,
        pHit: BlockHitResult,
    ): InteractionResult {
        return pLevel.constructMenuHelper2(pPos, pPlayer, Component.literal("Crusher"), ::CrusherMenu)
    }

    /**
     * Animates bursts of particles based on speed.
     * */
    override fun animateTick(pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) {
        val blockEntity = pLevel.getBlockEntity(pPos) as? CrusherBlockEntity
            ?: return

        val speed = blockEntity.clientTickSpeedSmoother.value

        if(speed < 0.01) {
            return
        }

        val sparkBurst = ceil(speed * 4).toInt()
        val dustBurst = ceil(speed * 2).toInt()

        val cx = pPos.x + 0.5
        val topY = pPos.y + 0.9
        val cz = pPos.z + 0.5

        repeat(sparkBurst) {
            val rx = (pRandom.nextDouble() - 0.5) * 0.9
            val rz = (pRandom.nextDouble() - 0.5) * 0.9
            val x = cx + rx
            val z = cz + rz
            val y = topY + pRandom.nextDouble() * 0.1

             // velocity: biased upward and outwards; scale with speed
            val vx = rx * 0.02 * (0.5 + speed * 2.0)
            val vy = 0.06 + pRandom.nextDouble() * 0.06 * (0.5 + speed)
            val vz = rz * 0.02 * (0.5 + speed * 2.0)

            val prob = pRandom.nextDouble()

            val type = if(prob < 0.01) {
                ParticleTypes.ASH
            }
            else if(prob < 0.03) {
                ParticleTypes.FLAME
            }
            else if(prob < 0.1) {
                ParticleTypes.SMOKE
            }
            else {
                ParticleTypes.CRIT
            }

            pLevel.addParticle(
                type,
                x, y, z,
                vx, vy, vz
            )
        }

        repeat(dustBurst) {
            val rx = (pRandom.nextDouble() - 0.5) * 0.6
            val rz = (pRandom.nextDouble() - 0.5) * 0.6
            val x = cx + rx
            val z = cz + rz

            val vx = rx * 0.01 * speed
            val vy = 0.02 + pRandom.nextDouble() * 0.03 * speed
            val vz = rz * 0.01 * speed

            val dust = DustParticleOptions(Vector3f(0.72f, 0.62f, 0.5f), 1.0f)
            pLevel.addParticle(dust, x, topY, z, vx, vy, vz)
        }
    }
}

class CrusherBlockEntity(pos: BlockPos, state: BlockState) :
    CellBlockEntity<CrusherCell>(pos, state, Content.CRUSHER_BLOCK_ENTITY.get()),
    ComponentDisplay,
    BulkPacketHandlerBlockEntity
{
    companion object {
        private const val INVENTORY = "inventory"
        private const val IS_WORKING = "hasRecipe"
        private const val TIME_PROGRESS = "timeProgress"

        fun tick(pLevel: Level?, pPos: BlockPos?, pState: BlockState?, pBlockEntity: BlockEntity?) {
            if (pLevel == null || pBlockEntity == null) {
                LOG.error("level or entity null")
                return
            }

            if (pBlockEntity !is CrusherBlockEntity) {
                LOG.error("Got $pBlockEntity instead of crusher")
                return
            }

            if (pLevel.isClientSide) {
                pBlockEntity.clientTick()
            }
            else {
                pBlockEntity.serverTick()
            }
        }
    }

    val inventoryHandler = SimpleProcessingRecipeInventoryHandler(this, Content.CRUSHING_RECIPE)
    val data = CrusherContainerData()

    //#region Client State

    @ClientOnly
    var targetClientSpeed = 0.0

    // Updated in [clientTick] for audio and particles:
    @ClientOnly
    val clientTickSpeedSmoother = FramerateIndependentSmoother1d(0.15)

    @ClientOnly
    var soundInstance: SimpleLoopingBlockEntitySoundInstance<CrusherBlockEntity>? = null

    //#endregion

    //#region Server State

    @ServerOnly
    var operation: CrushingOperation? = null

    @ServerOnly
    var savedProgress: Double? = null // Level not available in [load], we do the trick the cell block entity does.

    @ServerOnly
    class CrushingOperation(val recipe: SimpleProcessingRecipe) {
        var timeProgress = 0.0
    }

    //#endregion

    override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return LazyOptional.of { inventoryHandler }.cast()
        }

        return super.getCapability(cap, side)
    }

    @ServerOnly
    fun serverTick() {
        if(operation == null) {
            cell.isActive = false
            data.progress = 0.0f

           val recipe = inventoryHandler.searchForRecipe()

            if(recipe.isPresent) {
                // Create new operation:
                operation = CrushingOperation(recipe.get())

                if(savedProgress != null) {
                    operation!!.timeProgress = savedProgress!!
                    savedProgress = null
                }

                setChanged()
            }
        }
        else {
            val op = operation!!

            // Check if input changed, and reset operation if the recipe is different:
            if(inventoryHandler.wasRecipeChanged(op.recipe)) {
                operation = null
                setChanged()
            }
            else {
                // Progress if we have space for the output.
                // If we don't, we just wait with the current recipe.
                if(inventoryHandler.hasSpaceForExport(op.recipe)) {
                    op.timeProgress += cell.crusher.grindingSpeed * (1.0 / 20.0)
                    op.timeProgress = op.timeProgress.coerceIn(0.0, op.recipe.duration)
                    data.progress = (op.timeProgress / op.recipe.duration).toFloat()

                    if(op.timeProgress == op.recipe.duration) {
                        // Finish processing:
                        inventoryHandler.exportProcessingResult()
                        operation = null
                        cell.isActive = false
                    }
                    else {
                        // Turn on cell:
                        cell.isActive = true
                    }

                    setChanged()
                }
            }
        }

        val speed = cell.crusher.grindingSpeed

        if(!speed.approxEq(lastSentSpeed, 1e-4)) {
            sendSync(speed)
        }
    }

    @ClientOnly
    fun clientTick() {
        clientTickSpeedSmoother.update(targetClientSpeed)

        if(clientTickSpeedSmoother.value < 0.01) {
            return
        }

        val level = level
            ?: return

        val pos = blockPos
            ?: return

        if(soundInstance == null) {
            soundInstance = SimpleLoopingBlockEntitySoundInstance(this, Content.CRUSHER_SOUND_ROCK.get()).also {
                it.events.registerHandler<SoundInstanceTickEvent> { e ->
                    it.soundInfo = SoundInfo(
                        0.6f + clientTickSpeedSmoother.value * 0.4f,
                        clientTickSpeedSmoother.value.pow(3) + 0.5
                    )
                }

                it.registerOnAudioManager()
            }
        }

        val random = level.getRandom()
        val chance = 0.5 * clientTickSpeedSmoother.value

        if (random.nextDouble() < chance) {
            var x = pos.x + 0.5
            val y = pos.y + 0.8
            var z = pos.z + 0.5

            val axis = blockState.getValue(HorizontalDirectionalBlock.FACING).axis

            when(axis) {
                Direction.Axis.X -> x += random.nextDouble(-0.4, 0.4)
                Direction.Axis.Y -> { }
                Direction.Axis.Z -> z += random.nextDouble(-0.4, 0.4)
            }

            val vy = -random.nextDouble(0.01, 0.02)

            val dust = DustParticleOptions(Vector3f(1.0f, 1.0f, 1.0f), 1.0f)

            level.addParticle(
                dust,
                x, y, z,
                0.0, vy, 0.0
            )
        }
    }

    @ServerOnly
    override fun saveAdditional(pTag: CompoundTag) {
        super.saveAdditional(pTag)

        pTag.put(INVENTORY, inventoryHandler.serializeNBT())
        pTag.putBoolean(IS_WORKING, operation != null)

        if(operation != null) {
            pTag.putDouble(TIME_PROGRESS, operation!!.timeProgress)
        }
    }

    @ServerOnly
    override fun load(pTag: CompoundTag) {
        super.load(pTag)

        inventoryHandler.deserializeNBT(pTag.getCompound(INVENTORY))

        val isWorking = pTag.getBoolean(IS_WORKING)

        if(isWorking) {
            savedProgress = pTag.getDouble(TIME_PROGRESS)
        }
    }

    //#region Sync

    override val clientSidePacketHandlerLazy = createClientSideHandler()

    @ServerOnly
    private var lastSentSpeed = 0.0

    // onSyncSuggested
    @ServerOnly
    override fun getUpdateTag(): CompoundTag {
        if(hasCell) {
            sendSync(cell.crusher.grindingSpeed)
        }

        return super.getUpdateTag()
    }

    @ServerOnly
    fun sendSync(speed: Double) {
        val sent = this.sendBulkPacket(SyncPacket(speed))

        if(sent) {
            lastSentSpeed = speed
        }
    }

    @ClientOnly
    override fun setupPacketsOnClient(handler: ClientSidePacketHandlerBuilder) {
        handler.withHandler<SyncPacket> {
            targetClientSpeed = it.speed
        }
    }

    @Serializable
    private class SyncPacket(val speed: Double)

    //#endregion

    @ServerOnly
    override fun submitDisplay(builder: ComponentDisplayList) {
        builder.debugInIDE { "Speed: ${cell.crusher.grindingSpeed.rounded()}" }
        builder.debugInIDE { "OC: ${cell.crusher.resistorDisplay.openCircuitPotentialEstimate.value.rounded()}" }
        builder.quantityInput(cell.crusher.resistorDisplay.power)
        builder.quantity(cell.thermalWire.thermalBodyDisplay.temperature)

        if(operation != null) {
            builder.progress(operation!!.timeProgress / operation!!.recipe.duration)
        }
    }
}

class CrusherBlockEntityVisual(
    ctx: VisualizationContext,
    blockEntity: CrusherBlockEntity,
    partialTick: Float,
) : AbstractBlockEntityVisual<CrusherBlockEntity>(ctx, blockEntity, partialTick), SimpleDynamicVisual {
    companion object {
        private val center0 = FlwModels.getModelCenter(FlwModels.CRUSHER_GRINDER_0)
        private val center1 = FlwModels.getModelCenter(FlwModels.CRUSHER_GRINDER_1)
    }

    val body: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, SpecialModels.partial(FlwModels.CRUSHER_BODY, Materials.CUTOUT_BLOCK))
        .createInstance()
        .also {
            it.translate(visualPos)
            it.center()
            it.rotateToFace(blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING))
            it.uncenter()
        }

    val grinder0: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.CRUSHER_GRINDER_0))
        .createInstance()

    val grinder1: TransformedInstance = visualizationContext.instancerProvider()
        .instancer(InstanceTypes.TRANSFORMED, Models.partial(FlwModels.CRUSHER_GRINDER_1))
        .createInstance()

    var rotation = Rotation2d.identity
    val velocitySmoother = FramerateIndependentSmoother1d(0.1) // Separate smoother (flywheel thread)

    private fun poseGrinder(instance: TransformedInstance, center: Vector3d, rotation: Double) {
        val x = center.x
        val y = center.y

        instance.setIdentityTransform()
            .translate(visualPos)
            .center()
            .rotateToFace(blockEntity.blockState.getValue(HorizontalDirectionalBlock.FACING))
            .uncenter()
            .translate(x, y, 0.0)
            .rotateZ(rotation.toFloat())
            .translate(-x, -y, 0.0)
            .setChanged()
    }

    init {
        poseGrinder(grinder0, center0, 0.0)
        poseGrinder(grinder1, center1, 0.0)
    }

    override fun beginFrame(p0: DynamicVisual.Context?) {
        val dt = velocitySmoother.update(blockEntity.targetClientSpeed)

        if(velocitySmoother.value.approxEq(0.0)) {
            velocitySmoother.value = 0.0
        }

        val incr = velocitySmoother.value * dt

        if(incr != 0.0) {
            rotation += incr
            val angle = rotation.ln()
            poseGrinder(grinder0, center0, -angle)
            poseGrinder(grinder1, center1, +angle)
        }
    }

    override fun updateLight(p0: Float) {
        relight(body, grinder0, grinder1)
    }

    override fun collectCrumblingInstances(p0: Consumer<Instance?>) {
        p0.accept(body)
        p0.accept(grinder0)
        p0.accept(grinder1)
    }

    override fun _delete() {
        body.delete()
        grinder0.delete()
        grinder1.delete()
    }
}

class CrusherMenu(
    pContainerId: Int,
    playerInventory: Inventory,
    handler: ItemStackHandler,
    val containerData: CrusherContainerData,
    val access: ContainerLevelAccess,
    val level: Level,
) : AbstractContainerMenu(Content.CRUSHER_MENU.get(), pContainerId) {
    @ServerOnly
    constructor(entity: CrusherBlockEntity, id: Int, inventory: Inventory): this(
        id,
        inventory,
        entity.inventoryHandler,
        entity.data,
        ContainerLevelAccess.create(entity.level!!, entity.blockPos),
        entity.level!!
    )

    @ClientOnly
    constructor(pContainerId: Int, playerInventory: Inventory) : this(
        pContainerId,
        playerInventory,
        ItemStackHandler(2),
        CrusherContainerData(),
        ContainerLevelAccess.NULL,
        playerInventory.player.level()
    )

    init {
        addSlot(
            SlotItemHandlerWithPlacePredicate(handler, INPUT_SLOT, 34, 35) {
                level.canCrush(it)
            }
        )

        addSlot(
            SlotItemHandlerWithPlacePredicate(handler, OUTPUT_SLOT, 130, 35) {
                false
            }
        )

        addDataSlots(containerData)

        ContainerHelper.addPlayerGrid(playerInventory, this::addSlot)
    }

    override fun stillValid(pPlayer: Player) = stillValid(access, pPlayer, Content.CRUSHER_BLOCK.block.get())

    override fun quickMoveStack(pPlayer: Player, pIndex: Int) = ContainerHelper.quickMove(slots, pPlayer, pIndex)
}

class CrusherScreen(menu: CrusherMenu, playerInventory: Inventory, title: Component) : MyAbstractContainerScreen<CrusherMenu>(menu, playerInventory, title) {
    companion object {
        private val BASE_TEXTURE = resource("textures/gui/container/crusher_base.png")
        private val PROGRESS_TEXTURE = resource("textures/gui/container/crusher_progress.png")
    }

    override fun renderBg(pGuiGraphics: GuiGraphics, pPartialTick: Float, pMouseX: Int, pMouseY: Int) {
        blitHelper(pGuiGraphics, BASE_TEXTURE)

        pGuiGraphics.blit(
            PROGRESS_TEXTURE,
            leftPos, topPos,
            0.0f,
            0.0f,
            map(
                menu.containerData.progress,
                0f,
                1f,
                53.0f,
                121.0f
            ).toInt(),
            256,
            256,
            256
        )
    }
}

