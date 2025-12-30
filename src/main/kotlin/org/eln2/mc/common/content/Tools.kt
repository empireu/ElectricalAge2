package org.eln2.mc.common.content

import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.HitResult
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.network.NetworkEvent
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.LOG
import org.eln2.mc.ServerOnly
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.content.modules.Eln2Tools
import org.eln2.mc.getPlayerPOVHitResult
import org.eln2.mc.common.network.Networking
import org.eln2.mc.common.specs.foundation.SpecContainerPart
import org.eln2.mc.extensions.toVector3d
import java.util.WeakHashMap
import java.util.function.Supplier
import kotlin.random.Random

interface WrenchRotatable {
    @ServerOnly
    fun canRotateWithWrench(wrench: WrenchItem, context: UseOnContext): Boolean = true
}

interface WrenchInteractable {
    @ServerOnly
    fun applyWrench(wrench: WrenchItem, context: UseOnContext) : InteractionResult
}

class WrenchItem : Item(Properties().stacksTo(1)) {
    override fun useOn(pContext: UseOnContext): InteractionResult {
        if(pContext.level.isClientSide) {
            return InteractionResult.PASS
        }

        val player = pContext.player ?: return InteractionResult.FAIL
        val blockEntity = pContext.level.getBlockEntity(pContext.clickedPos)

        if(blockEntity is WrenchInteractable) {
            if(player.isShiftKeyDown) {
                return blockEntity.applyWrench(this, pContext)
            }

            return InteractionResult.FAIL
        }

        val multipart = blockEntity as? MultipartBlockEntity ?: return InteractionResult.FAIL

        val part = multipart.pickPart(player) ?: return InteractionResult.FAIL

        if(part is WrenchInteractable) {
            if(player.isShiftKeyDown) {
                return part.applyWrench(this, pContext)
            }
        }

        if(part !is WrenchRotatable || !part.canRotateWithWrench(this, pContext)) {
            return InteractionResult.FAIL
        }

        val tag = CompoundTag()

        multipart.breakPart(part, tag)

        val orientation = if(player.isShiftKeyDown) {
            part.placement.facing.clockWise
        }
        else {
            part.placement.facing.counterClockWise
        }

        val flag = multipart.place(
            player,
            part.placement.position,
            part.placement.face,
            part.placement.provider,
            saveTag = tag,
            orientation = orientation
        )

        if(!flag) {
            LOG.error("FAILED TO PLACE PART WITH WRENCH! $player ${part.placement.position} ${part.placement.face} ${part.placement.provider} $tag $orientation $part ${part.placement.provider}")
        }

        return InteractionResult.SUCCESS
    }
}

interface ScrewdriverInteractable {
    @ServerOnly
    fun applyScrewdriver(screwdriver: ScrewdriverItem, context: UseOnContext)
}

interface ScrewdriverScrollable {
    @ServerOnly
    fun scrollScrewdriver(player: ServerPlayer, delta: Double) : Boolean
}

class ScrewdriverItem : Item(Properties().stacksTo(1)) {
    override fun useOn(pContext: UseOnContext): InteractionResult {
        if(pContext.level.isClientSide) {
            return InteractionResult.PASS
        }

        val player = pContext.player ?: return InteractionResult.FAIL

        val multipart = pContext.level.getBlockEntity(pContext.clickedPos) as? MultipartBlockEntity ?: return InteractionResult.FAIL

        val part = multipart.pickPart(player) ?: return InteractionResult.FAIL

        if(part is ScrewdriverInteractable) {
            part.applyScrewdriver(this, pContext)
            return InteractionResult.SUCCESS
        }

        return InteractionResult.FAIL
    }

    data class Scroll(val delta: Double) {
        companion object {
            // Cooldown for sounds. Noticed it is *really* important with my G502's infinite scroll.
            private const val COOLDOWN = 1
            private val cooldowns = WeakHashMap<Any, Int>()

            fun isDeltaValid(delta: Double) = !delta.isNaN() && delta.isFinite() && delta != 0.0

            fun encode(packet: Scroll, buf: FriendlyByteBuf): FriendlyByteBuf = buf.also {
                buf.writeDouble(packet.delta)
            }

            fun decode(buf: FriendlyByteBuf) = Scroll(
                buf.readDouble()
            )

            fun handle(packet: Scroll, ctx: Supplier<NetworkEvent.Context>) {
                ctx.get().enqueueWork {
                    if(!isDeltaValid(packet.delta)) {
                        return@enqueueWork
                    }

                    val sender = ctx.get().sender
                        ?: return@enqueueWork

                    val interactable = pickGameObject<ScrewdriverScrollable>(sender)
                        ?: return@enqueueWork

                    val interacted = interactable.first.scrollScrewdriver(sender, packet.delta)

                    if(!interacted) {
                        return@enqueueWork
                    }

                    sender.swing(InteractionHand.MAIN_HAND, true)

                    if(!cooldowns.containsKey(interactable)) {
                        sender.level().playSound(
                            null,
                            interactable.second.x, interactable.second.y, interactable.second.z,
                            SoundEvents.LAVA_POP,
                            SoundSource.BLOCKS,
                            Random.nextDouble(0.25, 0.3).toFloat(),
                            Random.nextDouble(3.0, 3.25).toFloat()
                        )

                        cooldowns[interactable] = COOLDOWN
                    }
                }
            }

            fun tickCooldowns() {
                cooldowns.toList().forEach {
                    val obj = it.first
                    val ticksLeft = it.second - 1

                    if(ticksLeft <= 0) {
                        cooldowns.remove(obj)
                    }
                    else {
                        cooldowns[obj] = ticksLeft
                    }
                }
            }
        }
    }

    companion object {
        private inline fun<reified T> pickGameObject(player: Player?) : Pair<T, Vector3d>? {
            if(player == null) {
                return null
            }

            val level = player.level()
                ?: return null

            val hit = getPlayerPOVHitResult(level, player)

            if (hit.type != HitResult.Type.BLOCK) {
                return null
            }

            val targetBlockEntity = level.getBlockEntity(hit.blockPos)
                ?: return null

            if(targetBlockEntity is T) {
                return Pair(targetBlockEntity, targetBlockEntity.blockPos.toVector3d() + Vector3d.one * 0.5)
            }

            val multipart = targetBlockEntity as? MultipartBlockEntity
                ?: return null

            val part = multipart.pickPart(player)
                ?: return null

            if(part is T) {
                val c = part.worldBoundingBox.center
                return Pair(part, Vector3d(c.x, c.y, c.z))
            }

            val specContainer = part as? SpecContainerPart
                ?: return null

            val spec = specContainer.pickSpec(player)?.second
                ?: return null

            if(spec is T) {
                return Pair(spec, spec.placement.orientedBoundingBoxWorld.center)
            }

            return null
        }

        fun onScroll(event: InputEvent.MouseScrollingEvent) {
            val player = Minecraft.getInstance().player
                ?: return

            if(player.mainHandItem.item != Eln2Tools.SCREWDRIVER.get()) {
                return
            }

            if(!player.isShiftKeyDown) {
                return
            }

            event.isCanceled = true

            if(Scroll.isDeltaValid(event.scrollDelta)) {
                Networking.sendToServer(Scroll(event.scrollDelta))
            }
        }
    }
}
