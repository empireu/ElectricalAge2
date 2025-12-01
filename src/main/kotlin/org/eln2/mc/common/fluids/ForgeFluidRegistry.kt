package org.eln2.mc.common.fluids

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.Fluid
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fluids.FluidType
import net.minecraftforge.fluids.ForgeFlowingFluid
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.eln2.mc.MODID
import org.eln2.mc.client.render.foundation.MyColor
import org.eln2.mc.common.fluids.foundation.BasicForgeFluidType
import org.eln2.mc.common.fluids.foundation.BasicForgeFluidTypeClientOptions
import org.joml.Vector2d
import org.joml.Vector3f
import java.util.function.Consumer
import java.util.function.Supplier

object ForgeFluidRegistry {
    val FORGE_FLUIDS: DeferredRegister<Fluid> = DeferredRegister.create(ForgeRegistries.FLUIDS, MODID)
    val FORGE_FLUID_TYPES: DeferredRegister<FluidType> = DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, MODID)
    val FORGE_FLUID_BLOCKS: DeferredRegister<Block> = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID)
    val FORGE_FLUID_BUCKETS: DeferredRegister<Item> = DeferredRegister.create(ForgeRegistries.ITEMS, MODID)

    fun setup(bus: IEventBus) {
        FORGE_FLUIDS.register(bus)
        FORGE_FLUID_TYPES.register(bus)
        FORGE_FLUID_BLOCKS.register(bus)
        FORGE_FLUID_BUCKETS.register(bus)
    }

    class ForgeFluidBuilder {
        var blockPropertiesFactory: Supplier<BlockBehaviour.Properties> = Supplier {
            BlockBehaviour.Properties.copy(Blocks.WATER)
        }

        var bucketPropertiesFactory: Supplier<Item.Properties> = Supplier {
            Item.Properties()
                .craftRemainder(Items.BUCKET)
                .stacksTo(1)
        }

        var slopeFindDistance = 4
        var levelDecreasePerBlock = 1
        var explosionResistance = 1.0f
        var tickRate = 5

        fun blockProperties(factory: () -> BlockBehaviour.Properties) {
            blockPropertiesFactory = Supplier(factory)
        }

        fun bucketProperties(factory: () -> Item.Properties) {
            bucketPropertiesFactory = Supplier(factory)
        }
    }

    /**
     * Factory for the fluid type.
     * */
    fun interface FluidTypeSupplier {
        fun create() : FluidType
    }

    /**
     * A class such as this is actually necessary because the various bits of the registration reference themselves cyclically, so it's easiest to use field initialization to register them.
     * */
    class ForgeFluidRegistryItem(val id: String, fluidTypeFactory: FluidTypeSupplier, val builder: ForgeFluidBuilder) {
        val type: RegistryObject<FluidType> = FORGE_FLUID_TYPES.register(id) {
            fluidTypeFactory.create()
        }

        val source: RegistryObject<ForgeFlowingFluid.Source> = FORGE_FLUIDS.register(id) {
            ForgeFlowingFluid.Source(properties)
        }

        val flowing: RegistryObject<ForgeFlowingFluid.Flowing> = FORGE_FLUIDS.register("flowing_$id") {
            ForgeFlowingFluid.Flowing(properties)
        }

        val block: RegistryObject<LiquidBlock> = FORGE_FLUID_BLOCKS.register("${id}_block") {
            LiquidBlock(source, builder.blockPropertiesFactory.get())
        }

        val bucket: RegistryObject<BucketItem> = FORGE_FLUID_BUCKETS.register("${id}_bucket") {
            BucketItem(source, builder.bucketPropertiesFactory.get())
        }

        val properties: ForgeFlowingFluid.Properties = ForgeFlowingFluid.Properties(type, source, flowing)
            .block(block)
            .bucket(bucket)
            .slopeFindDistance(builder.slopeFindDistance)
            .levelDecreasePerBlock(builder.levelDecreasePerBlock)
            .explosionResistance(builder.explosionResistance)
            .tickRate(builder.tickRate)
    }

    fun forgeFluid(id: String, build: ForgeFluidBuilder.() -> FluidTypeSupplier) : ForgeFluidRegistryItem {
        val builder = ForgeFluidBuilder()
        val typeSupplier = build.invoke(builder)
        return ForgeFluidRegistryItem(id, typeSupplier, builder)
    }

    class BasicForgeFluidBuilder {
        var propertiesFactory: Supplier<FluidType.Properties> = Supplier { FluidType.Properties.create() }
        var stillTexture: ResourceLocation? = null
        var flowingTexture: ResourceLocation? = null
        var overlayTexture: ResourceLocation? = null
        var tintColor: MyColor? = null
        var fogColor: Vector3f? = null
        var fog: Vector2d? = null

        var buildMethod: Consumer<ForgeFluidBuilder> = Consumer { }

        fun properties(factory: () -> FluidType.Properties) {
            propertiesFactory = Supplier(factory)
        }

        fun configureBase(build: ForgeFluidBuilder.() -> Unit) {
            buildMethod = Consumer(build)
        }
    }

    fun basicForgeFluid(id: String, build: BasicForgeFluidBuilder.() -> Unit) : ForgeFluidRegistryItem {
        val builder = BasicForgeFluidBuilder()
        build(builder)

        return forgeFluid(id) {
            builder.buildMethod.accept(this)

            FluidTypeSupplier {
                BasicForgeFluidType(
                    BasicForgeFluidTypeClientOptions(
                        builder.stillTexture, builder.flowingTexture, builder.overlayTexture,
                        builder.tintColor, builder.fogColor,
                        builder.fog
                    ),
                    builder.propertiesFactory.get()
                )
            }
        }
    }
}
