@file:Suppress("unused", "MemberVisibilityCanBePrivate", "NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS") // Because block variables here would be suggested for deletion.

package org.eln2.mc.common.blocks

import net.minecraft.world.item.*
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.*
import org.eln2.mc.MODID
import org.eln2.mc.common.blocks.foundation.*
import org.eln2.mc.resource
import java.util.function.Supplier

object BlockRegistry {
    val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID)!!
    val BLOCK_ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID)!!
    val BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID)!!

    fun <T : BlockEntity> blockEntity(
        name: String,
        blockEntitySupplier: BlockEntityType.BlockEntitySupplier<T>,
        blockSupplier: (() -> Block),
    ): RegistryObject<BlockEntityType<T>> {

        return BLOCK_ENTITIES.register(name) {
            @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS") // Thanks, Minecraft for the high quality code.
            BlockEntityType.Builder.of(
                blockEntitySupplier,
                blockSupplier()
            ).build(null)
        }
    }

    fun setup(bus: IEventBus) {
        BLOCKS.register(bus)
        BLOCK_ITEMS.register(bus)
        BLOCK_ENTITIES.register(bus)
    }

    val MULTIPART_BLOCK_ENTITY: RegistryObject<BlockEntityType<MultipartBlockEntity>> =
        BLOCK_ENTITIES.register("multipart") {
            BlockEntityType.Builder.of(::MultipartBlockEntity, MULTIPART_BLOCK.block.get()).build(null)
        }

    val BIG_BLOCK_DELEGATE_BLOCK_ENTITY: RegistryObject<BlockEntityType<BigBlockDelegateBlockEntity>> =
        BLOCK_ENTITIES.register("big_block_delegate") {
            BlockEntityType.Builder.of(::BigBlockDelegateBlockEntity, BIG_BLOCK_DELEGATE_BLOCK.block.get()).build(null)
        }

    data class BlockRegistryItem<T : Block>(
        val name: String,
        val block: RegistryObject<T>,
        val item: RegistryObject<BlockItem>,
    ) : Supplier<T> by block {
        val registryName get() = block.id ?: error("Invalid registry name")
    }

    fun<T : Block> block(
        name: String,
        supplier: () -> T,
    ): BlockRegistryItem<T> {
        val block = BLOCKS.register(name) { supplier() }
        val item = BLOCK_ITEMS.register(name) {
            BlockItem(
                block.get(),
                Item.Properties()
            )
        }

        return BlockRegistryItem(name, block, item)
    }

    fun blockItem(name: String, supplier: () -> BlockItem) = BLOCK_ITEMS.register(name) {
        supplier()
    }

    val MULTIPART_BLOCK = block("multipart") { MultipartBlock() }
    val BIG_BLOCK_DELEGATE_BLOCK = block("big_block_delegate") { BigBlockDelegateBlock() }
}
