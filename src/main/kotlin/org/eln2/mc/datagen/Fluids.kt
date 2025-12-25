package org.eln2.mc.datagen

import net.minecraft.data.PackOutput
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BucketItem
import net.minecraftforge.client.model.generators.ItemModelProvider
import net.minecraftforge.client.model.generators.loaders.DynamicFluidContainerModelBuilder
import net.minecraftforge.common.data.ExistingFileHelper
import org.eln2.mc.MODID
import org.eln2.mc.common.fluids.ForgeFluidRegistry

class Eln2BucketModels(output: PackOutput, existingFileHelper: ExistingFileHelper) : ItemModelProvider(output, MODID, existingFileHelper) {
    override fun registerModels() {
        ForgeFluidRegistry.FORGE_FLUID_BUCKETS.entries.forEach { bucketEntry ->
            val item = bucketEntry.get() as BucketItem
            val id = bucketEntry.id

            withExistingParent(id.path, ResourceLocation.parse("forge:item/bucket"))
                .customLoader { parent, helper ->
                    DynamicFluidContainerModelBuilder.begin(parent, helper)
                }
                .fluid(item.fluid)
                .coverIsMask(false)
                .end()
        }
    }
}
