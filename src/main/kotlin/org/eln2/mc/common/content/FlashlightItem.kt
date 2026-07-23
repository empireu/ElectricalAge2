@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package org.eln2.mc.common.content

import net.minecraft.client.Minecraft
import net.minecraft.ChatFormatting
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.network.chat.Component
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Item
import org.ageseries.libage.mathematics.smoothstep
import org.eln2.mc.Eln2Config
import org.eln2.mc.requireIsOnRenderThread
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.approxEq
import net.minecraftforge.network.NetworkEvent
import org.eln2.mc.client.dynamicLight.DynamicLightManager
import org.eln2.mc.common.network.Networking
import org.eln2.mc.client.dynamicLight.DynamicLightSource
import org.joml.Vector3f
import net.minecraft.world.phys.Vec3
import java.util.*
import java.util.function.Supplier
import kotlin.math.abs

/**
 * Specification for the [FlashlightItem].
 *
 * @param powerDemand The power drawn continuously while held and active.
 * @param nominalIntensity The light intensity at full power.
 * @param nominalRange The light range at full power, in meters.
 * @param halfAngleDeg The cone half-angle in degrees.
 * @param color The light color as RGB.
 * */
data class FlashlightModel(
    val powerDemand: Quantity<Power>,
    val nominalIntensity: Float,
    val nominalRange: Float,
    val halfAngleDeg: Float,
    val color: Vector3f,
)

/**
 * Per-player power consumer for the [FlashlightItem]. Created when a flashlight is held, destroyed when it leaves the player's hands.
 * Reports a constant demand to [PlayerPowerManager] while active. The granted/demand fraction modulates the flashlight's intensity and range.
 * */
private class FlashlightPowerConsumer(
    private val player: ServerPlayer,
    private val flashlight: FlashlightItem,
) : InventoryPowerConsumer {
    override val priority = InventoryPowerPriority.Low

    var grantedThisTick = 0.0
        private set

    var demandThisTick = 0.0
        private set

    private var ticked = false

    var fraction: Float = 0.0f
        private set

    override fun powerDemand(): Quantity<Power> {
        if (!isHoldingFlashlight(player, flashlight)) {
            return Quantity(0.0, WATT)
        }
        return flashlight.flashlightModel.powerDemand
    }

    override fun receivePower(granted: Quantity<Energy>): Quantity<Energy> {
        grantedThisTick += !granted
        return granted
    }

    override fun preTick() {
        grantedThisTick = 0.0
        demandThisTick = !powerDemand()
        ticked = true
    }

    override fun postTick(dt: Double) {
        fraction = if (!ticked || demandThisTick.approxEq(0.0)) {
            0.0f
        } else {
            (grantedThisTick / (demandThisTick * dt)).toFloat().coerceIn(0.0f, 1.0f)
        }
    }
}

private fun isHoldingFlashlight(player: Player, flashlight: FlashlightItem): Boolean {
    return player.mainHandItem.item === flashlight || player.offhandItem.item === flashlight
}

/**
 * An electrically-powered flashlight. Draws energy from [PowerCellItem]s in the player's inventory via [PlayerPowerManager].
 * While held, it registers a [DynamicLightSource] with [DynamicLightManager] on the client, producing a shadow-cast cone light.
 * The light's intensity and range are modulated by the power fraction (granted energy / demand).
 * Works in multiplayer: each player holding a flashlight produces a light source on all nearby clients.
 * The power fraction is computed server-side and synced to all tracking players via [FlashlightPowerMessage].
 * */
class FlashlightItem(val flashlightModel: FlashlightModel) : Item(Properties().stacksTo(1)) {
    override fun appendHoverText(
        pStack: ItemStack,
        pLevel: Level?,
        pTooltipComponents: MutableList<Component>,
        pIsAdvanced: TooltipFlag,
    ) {
        super.appendHoverText(pStack, pLevel, pTooltipComponents, pIsAdvanced)

        val yellow = ChatFormatting.YELLOW
        val gray = ChatFormatting.GRAY

        pTooltipComponents.add(
            Component.translatable("tooltip.eln2.flashlight.power")
                .append(": ")
                .append(Component.literal(Eln2Config.clientConfig.classifyWithOverride(flashlightModel.powerDemand)).withStyle(gray))
                .withStyle(yellow)
        )
    }
    companion object {
        private const val DT = 1.0 / 20.0
        private const val SYNC_INTERVAL_TICKS = 5

        private val consumers = HashMap<UUID, FlashlightPowerConsumer>()
        private val syncTickCounters = HashMap<UUID, Int>()

        fun clearAll() {
            consumers.clear()
            syncTickCounters.clear()
        }

        fun clearPlayer(uuid: UUID) {
            consumers.remove(uuid)
            syncTickCounters.remove(uuid)
        }

        /**
         * Called from [org.eln2.mc.common.ForgeEvents] on PlayerTickEvent END, after [PlayerPowerManager.tick].
         * Manages the flashlight power consumer lifecycle and syncs the fraction to tracking players.
         * */
        fun onPlayerTickEnd(player: ServerPlayer) {
            val holdingFlashlight = findHeldFlashlight(player)

            if (holdingFlashlight == null) {
                consumers.remove(player.uuid)?.let {
                    PlayerPowerManager.unregisterConsumer(player, it)
                }
                syncTickCounters.remove(player.uuid)
                return
            }

            val consumer = consumers[player.uuid]
            if (consumer == null) {
                val newConsumer = FlashlightPowerConsumer(player, holdingFlashlight)
                consumers[player.uuid] = newConsumer
                PlayerPowerManager.registerConsumer(player, newConsumer)
                return
            }

            val counter = syncTickCounters.getOrDefault(player.uuid, 0) + 1
            syncTickCounters[player.uuid] = counter

            if (counter >= SYNC_INTERVAL_TICKS) {
                syncTickCounters[player.uuid] = 0
                Networking.sendToTrackingAndSelf(
                    FlashlightPowerMessage(player.uuid, consumer.fraction),
                    player
                )
            }
        }

        private fun findHeldFlashlight(player: ServerPlayer): FlashlightItem? {
            val mainHand = player.mainHandItem.item as? FlashlightItem
            if (mainHand != null) {
                return mainHand
            }
            return player.offhandItem.item as? FlashlightItem
        }

        private val clientLightSources = HashMap<UUID, DynamicLightSource>()
        private var updateCallbackRegistered = false

        /**
         * Called from client setup to register the per-frame light source update callback with [DynamicLightManager].
         * */
        fun registerClient() {
            if (updateCallbackRegistered) {
                return
            }
            DynamicLightManager.addUpdateCallback(::updateClientLightSources)
            updateCallbackRegistered = true
        }

        /**
         * Clears all client-side light sources. Called on level unload / disconnect.
         * */
        fun clearClient() {
            requireIsOnRenderThread()
            for (source in clientLightSources.values) {
                DynamicLightManager.removeLightSource(source)
            }
            clientLightSources.clear()
            FlashlightPowerMessage.clearClient()
        }

        /**
         * Per-frame callback that scans all players in the client level, registering/unregistering [DynamicLightSource]s
         * for players holding a [FlashlightItem], and updating each source's intensity from the synced power fraction.
         * */
        private fun updateClientLightSources(level: ClientLevel, partialTick: Float) {
            requireIsOnRenderThread()
            val seenPlayers = HashSet<UUID>()

            for (player in level.players()) {
                val flashlight = findHeldFlashlightClient(player) ?: continue
                val uuid = player.uuid
                seenPlayers.add(uuid)

                if (!clientLightSources.containsKey(uuid)) {
                    val source = createClientLightSource(player, flashlight)
                    clientLightSources[uuid] = source
                }

                val source = clientLightSources[uuid]!!
                val fraction = FlashlightPowerMessage.getClientFraction(uuid)
                applyFraction(source, flashlight, fraction)
            }

            val removed = ArrayList<UUID>()
            for ((uuid, source) in clientLightSources) {
                if (uuid !in seenPlayers) {
                    DynamicLightManager.removeLightSource(source)
                    removed.add(uuid)
                }
            }
            for (uuid in removed) {
                clientLightSources.remove(uuid)
            }
        }

        private fun findHeldFlashlightClient(player: AbstractClientPlayer): FlashlightItem? {
            val mainHand = player.mainHandItem.item as? FlashlightItem
            if (mainHand != null) {
                return mainHand
            }
            return player.offhandItem.item as? FlashlightItem
        }

        private fun createClientLightSource(
            player: AbstractClientPlayer,
            flashlight: FlashlightItem,
        ): DynamicLightSource {
            val model = flashlight.flashlightModel
            return DynamicLightManager.createLightSource(
                poseUpdater = { partialTick -> computeFlashlightPose(player, partialTick) },
                color = model.color,
                intensity = 0.0f,
                range = model.nominalRange,
                halfAngleDeg = model.halfAngleDeg,
            )
        }

        private fun computeFlashlightPose(player: LivingEntity, partialTick: Float): Pair<Vec3, Vec3> {
            val eyePosition = player.getEyePosition(partialTick)
            val lookDirection = player.getViewVector(partialTick)

            val forwardOffset = lookDirection.scale(0.3)
            val right = lookDirection.cross(Vec3(0.0, 1.0, 0.0)).normalize()
                .scale(if (abs(lookDirection.y) > 0.99) 0.0 else 0.3)
            val downOffset = Vec3(0.0, -0.2, 0.0)
            val position = eyePosition.add(forwardOffset).add(right).add(downOffset)

            return Pair(position, lookDirection)
        }

        private fun applyFraction(source: DynamicLightSource, flashlight: FlashlightItem, fraction: Float) {
            val model = flashlight.flashlightModel
            val smoothFraction = smoothstep(fraction.toDouble()).toFloat()
            source.intensity = model.nominalIntensity * smoothFraction
            source.range = model.nominalRange * (0.3f + 0.7f * smoothFraction)
        }
    }
}

/**
 * Server-to-client message syncing a player's flashlight power fraction to all tracking clients.
 * Sent via [PacketDistributor.TRACKING_ENTITY_AND_SELF] so every player who can see the flashlight holder
 * knows how bright the light should be.
 * */
class FlashlightPowerMessage(val playerId: UUID, val fraction: Float) {

    companion object {
        private val clientFractions = HashMap<UUID, Float>()

        fun getClientFraction(playerId: UUID): Float {
            return clientFractions.getOrDefault(playerId, 0.0f)
        }

        fun clearClient() {
            clientFractions.clear()
        }

        fun reset() {
            clientFractions.clear()
        }

        fun encode(message: FlashlightPowerMessage, buf: FriendlyByteBuf) {
            buf.writeUUID(message.playerId)
            buf.writeFloat(message.fraction)
        }

        fun decode(buf: FriendlyByteBuf): FlashlightPowerMessage {
            return FlashlightPowerMessage(buf.readUUID(), buf.readFloat())
        }

        fun handle(message: FlashlightPowerMessage, ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork {
                clientFractions[message.playerId] = message.fraction
            }
            ctx.get().packetHandled = true
        }
    }
}
