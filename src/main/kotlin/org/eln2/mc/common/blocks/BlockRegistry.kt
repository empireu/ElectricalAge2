@file:Suppress("unused", "MemberVisibilityCanBePrivate", "NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS") // Because block variables here would be suggested for deletion.

package org.eln2.mc.common.blocks

import net.minecraft.core.BlockPos
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.*
import org.ageseries.libage.utils.putUnique
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import org.eln2.mc.common.blocks.foundation.*
import java.util.function.Supplier

object BlockRegistry {
    val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID)!!
    val BLOCK_ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID)!!
    val BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID)!!

    fun <T : BlockEntity> blockEntityOnly(
        name: String,
        blockEntitySupplier: BlockEntityType.BlockEntitySupplier<T>,
        vararg blockSuppliers: (() -> Block),
    ): RegistryObject<BlockEntityType<T>> {

        return BLOCK_ENTITIES.register(name) {
            @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS") // Thanks, Minecraft for the high quality code.
            BlockEntityType.Builder.of(
                blockEntitySupplier,
                *blockSuppliers.map {
                    it.invoke()
                }.toTypedArray()
            ).build(null)
        }
    }

    fun <T : BlockEntity, B : Block> blockEntityOnly(
        name: String,
        blockSupplier: RegistryObject<B>,
        blockEntitySupplier: BlockEntityType.BlockEntitySupplier<T>,
    ) = blockEntityOnly(name, blockEntitySupplier, { blockSupplier.get() })

    fun <T : BlockEntity, B : Block> blockEntityOnly(
        name: String,
        blockSupplier: BlockRegistryItem<B>,
        blockEntitySupplier: BlockEntityType.BlockEntitySupplier<T>,
    ) = blockEntityOnly(name, blockEntitySupplier, { blockSupplier.get() })

    fun setup(bus: IEventBus) {
        BLOCKS.register(bus)
        BLOCK_ITEMS.register(bus)
        BLOCK_ENTITIES.register(bus)
    }

    val MULTIPART_BLOCK_ENTITY: RegistryObject<BlockEntityType<MultipartBlockEntity>> =
        BLOCK_ENTITIES.register("multipart") {
            BlockEntityType.Builder.of(::MultipartBlockEntity, MULTIPART_BLOCK.get()).build(null)
        }

    val MULTIBLOCK_DELEGATE_BLOCK_ENTITY: RegistryObject<BlockEntityType<MultiblockDelegateBlockEntity>> =
        BLOCK_ENTITIES.register("big_block_delegate") {
            BlockEntityType.Builder.of(::MultiblockDelegateBlockEntity).build(null)
        }

    data class BlockRegistryItem<T : Block>(
        val name: String,
        val block: RegistryObject<T>,
        val item: RegistryObject<BlockItem>,
    ) : Supplier<T> by block {
        val registryName get() = block.id ?: error("Invalid registry name")
    }

    fun<T : Block> blockAndItem(
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

    fun<T : Block> blockOnly(
        name: String,
        supplier: () -> T
    ) : RegistryObject<T> = BLOCKS.register(name) { supplier() }

    fun blockItemOnly(name: String, supplier: () -> BlockItem) = BLOCK_ITEMS.register(name) {
        supplier()
    }

    val MULTIPART_BLOCK = blockOnly("multipart") { MultipartBlock() }
    val MULTIBLOCK_DELEGATE_BLOCK = blockOnly("big_block_delegate") { MultiblockDelegateBlock() }

    interface DelegateMapBuilder {
        /**
         * Puts the [state] at the specified position [x] [y] [z].
         * */
        fun state(x: Int, y: Int, z: Int, state: Supplier<BlockState>)

        /**
         * Puts the default state of [block] at the specified position [x] [y] [z]
         * */
        fun <T : Block> principal(x: Int, y: Int, z: Int, block: Supplier<T>)

        /**
         * Puts a [MultiblockDelegateBlock] at the specified position [x] [y] [z] with a full block collider.
         * */
        fun delegateBlock(x: Int, y: Int, z: Int)

        /**
         * Registers a [MultiblockDelegateBlockWithCustomCollider] with a collider composed of the specified [colliders].
         * */
        fun registerDelegate(colliders: List<AABB>) : RegistryObject<MultiblockDelegateBlockWithCustomCollider>

        /**
         * Registers a [MultiblockDelegateBlockWithCustomCollider] with a collider composed of the specified [colliders].
         * */
        fun registerDelegateOf(vararg colliders: AABB) = registerDelegate(colliders.asList())

        /**
         * Registers a [MultiblockDelegateBlockWithCustomCollider] with a collider composed of the specified [colliders] and with the specified block [properties].
         * */
        fun registerDelegate(properties: Properties, colliders: List<AABB>) : RegistryObject<MultiblockDelegateBlockWithCustomCollider>

        /**
         * Registers a [MultiblockDelegateBlockWithCustomCollider] with a collider composed of the specified [colliders] and with the specified block [properties].
         * */
        fun registerDelegateOf(properties: Properties, vararg colliders: AABB) = registerDelegate(properties, colliders.asList())

        /**
         * Puts a [MultiblockDelegateBlockWithCustomCollider] at the specified position [x] [y] [z] with a collider composed of the specified [colliders].
         * */
        fun delegate(x: Int, y: Int, z: Int, vararg colliders: AABB)

        /**
         * Puts a [MultiblockDelegateBlockWithCustomCollider] at the specified position [x] [y] [z] with a collider composed of the specified [colliders] and with the specified block [properties].
         * */
        fun delegate(properties: Properties, x: Int, y: Int, z: Int, vararg colliders: AABB)
    }

    private class DelegateMapBuilderImplementation(val id: String) : DelegateMapBuilder {
        val deferredStates = LinkedHashMap<BlockPos, Supplier<BlockState>>()
        var registeredDelegateCounter = 0

        override fun state(x: Int, y: Int, z: Int, state: Supplier<BlockState>) {
            require(x != 0 || y != 0 || z != 0) {
                "Tried to replace representative"
            }

            require(deferredStates.put(BlockPos(x, y, z), state) == null) {
                "Duplicate state $x $y $z $state"
            }
        }

        override fun<T : Block> principal(x: Int, y: Int, z: Int, block: Supplier<T>) {
            state(x, y, z) {
                block.get().defaultBlockState()
            }
        }

        override fun delegateBlock(x: Int, y: Int, z: Int) {
            state(x, y, z) {
                MULTIBLOCK_DELEGATE_BLOCK.get().defaultBlockState()
            }
        }

        private fun getDelegateId() = "${id}_delegate_${registeredDelegateCounter++}"

        override fun registerDelegate(colliders: List<AABB>): RegistryObject<MultiblockDelegateBlockWithCustomCollider> {
            return blockOnly(getDelegateId()) {
                MultiblockDelegateBlockWithCustomCollider(
                    initialShapes = colliders
                )
            }
        }

        override fun registerDelegate(properties: Properties, colliders: List<AABB>): RegistryObject<MultiblockDelegateBlockWithCustomCollider> {
            return blockOnly(getDelegateId()) {
                MultiblockDelegateBlockWithCustomCollider(
                    properties = properties,
                    initialShapes = colliders
                )
            }
        }

        override fun delegate(x: Int, y: Int, z: Int, vararg colliders: AABB) {
            val delegate = registerDelegate(colliders.asList())

            state(x, y, z) {
                delegate.get().defaultBlockState()
            }
        }

        override fun delegate(properties: Properties, x: Int, y: Int, z: Int, vararg colliders: AABB) {
            val delegate = registerDelegate(properties, colliders.asList())

            state(x, y, z) {
                delegate.get().defaultBlockState()
            }
        }
    }

    private val delegateDefinitions = HashMap<String, Lazy<MultiblockDelegateMap>>()

    fun defineDelegateMap(id: String, action: DelegateMapBuilder.() -> Unit) : Lazy<MultiblockDelegateMap> {
        require(!delegateDefinitions.containsKey(id)) {
            "Duplicate delegate definition $id"
        }

        val builder = DelegateMapBuilderImplementation(id)

        action(builder)

        val lazy = lazy {
            MultiblockDelegateMap(
                builder.deferredStates.keys.associateWith {
                    builder.deferredStates[it]!!.get()
                }
            )
        }

        delegateDefinitions.putUnique(id, lazy)

        LOG.info("Registered delegate $id with ${builder.deferredStates.size} deferred states and ${builder.registeredDelegateCounter} new delegates")

        return lazy
    }

    fun finalize() {
        delegateDefinitions.forEach {
            val value = it.value.value
            LOG.debug("Resolved delegate definition {} to {}", it.key, value)
        }

        LOG.info("Finalized block registry")
    }
}
