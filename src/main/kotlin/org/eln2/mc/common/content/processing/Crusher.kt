package org.eln2.mc.common.content.processing

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.items.ItemStackHandler
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.mathematics.map
import org.eln2.mc.ClientOnly
import org.eln2.mc.MODID
import org.eln2.mc.ServerOnly
import org.eln2.mc.common.cells.foundation.CellProvider
import org.eln2.mc.common.containers.ContainerHelper
import org.eln2.mc.common.containers.MyAbstractContainerScreen
import org.eln2.mc.common.containers.ProgressContainerData
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicate
import org.eln2.mc.common.containers.SlotItemHandlerWithPlacePredicateAndSkipPickupCheck
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.recipes.foundation.DirectSimpleProcessingRecipe
import org.eln2.mc.common.recipes.foundation.INPUT_SLOT
import org.eln2.mc.common.recipes.foundation.OUTPUT_SLOT
import org.eln2.mc.extensions.eln2StillValid
import org.eln2.mc.extensions.nextDouble
import org.eln2.mc.extensions.recipeExists
import org.eln2.mc.resource
import org.joml.Vector3f
import kotlin.math.ceil

class CrusherBlock<C : ProcessingCell>(cell: RegistryObject<CellProvider<C>>) : ProcessingMachineBlock<C, CrusherBlockEntity<C>>(cell) {
    override fun getTitle(): Component = Component.translatable("menu.$MODID.crusher")

    override fun createMenu(pBlockEntity: CrusherBlockEntity<C>, pContainerId: Int, pPlayerInventory: Inventory) = CrusherMenu(pBlockEntity, pContainerId, pPlayerInventory)

    override fun newBlockEntity(pPos: BlockPos, pState: BlockState) = CrusherBlockEntity<C>(pPos, pState)

    override fun animateMachineTick(blockEntity: CrusherBlockEntity<C>, avSpeed: Double, pState: BlockState, pLevel: Level, pPos: BlockPos, pRandom: RandomSource) {
        val sparkBurst = ceil(avSpeed * 4).toInt()
        val dustBurst = ceil(avSpeed * 2).toInt()

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
            val vx = rx * 0.02 * (0.5 + avSpeed * 2.0)
            val vy = 0.06 + pRandom.nextDouble() * 0.06 * (0.5 + avSpeed)
            val vz = rz * 0.02 * (0.5 + avSpeed * 2.0)

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

            val vx = rx * 0.01 * avSpeed
            val vy = 0.02 + pRandom.nextDouble() * 0.03 * avSpeed
            val vz = rz * 0.01 * avSpeed

            val dust = DustParticleOptions(Vector3f(0.72f, 0.62f, 0.5f), 1.0f)
            pLevel.addParticle(dust, x, topY, z, vx, vy, vz)
        }
    }
}

class CrusherBlockEntity<C : ProcessingCell>(pPos: BlockPos, pState: BlockState) : SimpleProcessingMachineBlockEntity<C, DirectSimpleProcessingRecipe>(pPos, pState, 2) {
    override val recipe: RecipeType<DirectSimpleProcessingRecipe>
        get() = Eln2Processing.CRUSHING_RECIPE

    override val sound: RegistryObject<SoundEvent>
        get() = Eln2Processing.CRUSHER_SOUND

    override val allowProcessingInReverse: Boolean
        get() = false

    override fun animateClientTick(dt: Double, state: ClientState, avSpeed: Double, level: ClientLevel) {
        val random = level.getRandom()
        val chance = 0.5 * avSpeed

        if (random.nextDouble() < chance) {
            var x = blockPos.x + 0.5
            val y = blockPos.y + 0.8
            var z = blockPos.z + 0.5

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
}

class CrusherMenu(
    pContainerId: Int,
    playerInventory: Inventory,
    handler: ItemStackHandler,
    val containerData: ProgressContainerData,
    val access: ContainerLevelAccess,
    val level: Level,
) : AbstractContainerMenu(Eln2Processing.CRUSHER_MENU.get(), pContainerId) {
    @ServerOnly
    constructor(entity: CrusherBlockEntity<*>, id: Int, inventory: Inventory): this(
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
        ProgressContainerData(),
        ContainerLevelAccess.NULL,
        playerInventory.player.level()
    )

    init {
        addSlot(
            SlotItemHandlerWithPlacePredicateAndSkipPickupCheck(handler, INPUT_SLOT, 34, 35) {
                level.recipeExists(Eln2Processing.CRUSHING_RECIPE, it)
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

    override fun stillValid(pPlayer: Player) = eln2StillValid<CrusherBlock<*>>(access, pPlayer)

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

