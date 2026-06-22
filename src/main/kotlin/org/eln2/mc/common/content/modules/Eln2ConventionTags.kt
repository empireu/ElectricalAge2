@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraftforge.registries.ForgeRegistries

/**
 * Forge convention tags for cross-mod item interchangeability.
 */
object Eln2ConventionTags {
    //#region Ingot Tags

    val INGOT_LEAD = forgeTag("ingots/lead")
    val INGOT_TIN = forgeTag("ingots/tin")
    val INGOT_BRONZE = forgeTag("ingots/bronze")

    val INGOT_IRON = forgeTag("ingots/iron")
    val INGOT_COPPER = forgeTag("ingots/copper")

    //#endregion

    //#region Dust Tags

    val DUST_LEAD = forgeTag("dusts/lead")
    val DUST_TIN = forgeTag("dusts/tin")
    val DUST_IRON = forgeTag("dusts/iron")
    val DUST_COPPER = forgeTag("dusts/copper")

    //#endregion

    //#region Plate Tags

    val PLATE_LEAD = forgeTag("plates/lead")
    val PLATE_TIN = forgeTag("plates/tin")
    val PLATE_IRON = forgeTag("plates/iron")
    val PLATE_COPPER = forgeTag("plates/copper")
    val PLATE_BRONZE = forgeTag("plates/bronze")

    //#endregion

    //#region Raw Material Tags

    val RAW_MATERIAL_LEAD = forgeTag("raw_materials/lead")
    val RAW_MATERIAL_TIN = forgeTag("raw_materials/tin")

    //#endregion

    //#region Misc Tags

    val GLUE = forgeTag("glue")
    val WOODEN_SLABS: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("minecraft", "wooden_slabs"))

    //#endregion

    /**
     * Maps item registry path to [TagKey] reference.
     * Covers both our own items (for output-side tag datagen) and vanilla items (for recipe input side, so we accept any mod's version of the same material).
     */
    private val ITEM_ID_TO_TAG: Map<String, TagKey<Item>> = mapOf(
        "lead_ingot" to INGOT_LEAD,
        "tin_ingot" to INGOT_TIN,
        "bronze_ingot" to INGOT_BRONZE,

        "lead_dust" to DUST_LEAD,
        "tin_dust" to DUST_TIN,
        "iron_dust" to DUST_IRON,
        "copper_dust" to DUST_COPPER,

        "lead_plate" to PLATE_LEAD,
        "tin_plate" to PLATE_TIN,
        "iron_plate" to PLATE_IRON,
        "copper_plate" to PLATE_COPPER,
        "bronze_plate" to PLATE_BRONZE,

        "iron_ingot" to INGOT_IRON,
        "copper_ingot" to INGOT_COPPER,

        "raw_resin" to GLUE,
    )

    /**
     * Returns the convention [TagKey] for [item], or `null` if none exists.
     * Used in recipe generation to prefer `Ingredient.of(tag)` over
     * `Ingredient.of(concreteItem)` when a convention tag is available.
     */
    fun tagForItem(item: Item): TagKey<Item>? {
        val id = ForgeRegistries.ITEMS.getKey(item)
            ?: return null

        return ITEM_ID_TO_TAG[id.path]
    }

    private fun forgeTag(path: String): TagKey<Item> =
        TagKey.create(ForgeRegistries.ITEMS.registryKey, ResourceLocation.fromNamespaceAndPath("forge", path))
}
