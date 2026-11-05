package org.eln2.mc.gametest

import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraftforge.common.world.ForgeBiomeModifiers
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import org.eln2.mc.MODID
import org.eln2.mc.common.content.modules.Eln2Processing
import org.eln2.mc.common.content.modules.world.Eln2Ores

/**
 * Checks the worldgen data that a flat world cannot exercise, without depending on terrain.
 * */
@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
object WorldgenGameTests {
    private class OreWorldgen(val ore: Eln2Ores.OreRegistryItem, val placedFeature: String, val biomeModifier: String)

    private val ORE_WORLDGEN = listOf(
        OreWorldgen(Eln2Ores.MAGNETITE_ORE, "magnetite_ore_river", "add_magnetite_river"),
        OreWorldgen(Eln2Ores.LEAD_ORE, "lead_ore", "add_lead_ore"),
        OreWorldgen(Eln2Ores.TIN_ORE, "tin_ore", "add_tin_ore"),
        OreWorldgen(Eln2Ores.SULFUR_ORE, "sulfur_ore", "add_sulfur_ore"),
        OreWorldgen(Eln2Ores.ASBESTOS_ORE, "asbestos_ore", "add_asbestos_ore"),
        OreWorldgen(Eln2Ores.SALTPETER_ORE, "saltpeter_ore", "add_saltpeter_ore"),
        OreWorldgen(Eln2Ores.SCHEELITE_ORE, "scheelite_ore", "add_scheelite_ore"),
        OreWorldgen(Eln2Ores.TRONA_ORE, "trona_ore", "add_trona_ore")
    )

    private fun placedFeature(helper: GameTestHelper, worldgen: OreWorldgen): PlacedFeature {
        val registry = helper.level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE)
        val location = ResourceLocation.fromNamespaceAndPath(MODID, worldgen.placedFeature)

        return registry.get(ResourceKey.create(Registries.PLACED_FEATURE, location))
            ?: gameTestFail("Placed feature $location is not registered")
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun everyOreIsCoveredByTheWorldgenTable(helper: GameTestHelper) {
        val covered = ORE_WORLDGEN.map { it.ore }.toSet()

        Eln2Ores.ORE_FOR_MODEL_DATAGEN.forEach { ore ->
            checkThat(ore in covered) {
                "${ore.oreBlock.id} has no placed feature in the worldgen table, so it cannot be found in survival"
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun oreFeaturesPlaceTheMatchingStoneAndDeepslateBlocks(helper: GameTestHelper) {
        val random = RandomSource.create(0L)

        ORE_WORLDGEN.forEach { worldgen ->
            val configuration = placedFeature(helper, worldgen).feature().value().config() as? OreConfiguration
                ?: gameTestFail("${worldgen.placedFeature} is not an ore feature")

            checkThat(configuration.size in 1..64) {
                "${worldgen.placedFeature} has vein size ${configuration.size}"
            }

            fun stateReplacing(replaced: net.minecraft.world.level.block.Block) =
                configuration.targetStates.firstOrNull { it.target.test(replaced.defaultBlockState(), random) }?.state

            checkThat(stateReplacing(Blocks.STONE)?.block === worldgen.ore.oreBlock.get()) {
                "${worldgen.placedFeature} does not place ${worldgen.ore.oreBlock.id} in stone"
            }

            checkThat(stateReplacing(Blocks.DEEPSLATE)?.block === worldgen.ore.deepslateOreBlock.get()) {
                "${worldgen.placedFeature} does not place ${worldgen.ore.deepslateOreBlock.id} in deepslate"
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun biomeModifiersAddTheOreFeaturesToBiomes(helper: GameTestHelper) {
        val registry = helper.level.registryAccess().registryOrThrow(ForgeRegistries.Keys.BIOME_MODIFIERS)

        ORE_WORLDGEN.forEach { worldgen ->
            val placedFeature = placedFeature(helper, worldgen)
            val location = ResourceLocation.fromNamespaceAndPath(MODID, worldgen.biomeModifier)

            val modifier = registry.get(ResourceKey.create(ForgeRegistries.Keys.BIOME_MODIFIERS, location))
                as? ForgeBiomeModifiers.AddFeaturesBiomeModifier
                ?: gameTestFail("Biome modifier $location is not registered as an add features modifier")

            checkThat(modifier.features().any { it.value() === placedFeature }) {
                "$location does not add ${worldgen.placedFeature}"
            }

            checkThat(modifier.biomes().size() > 0) {
                "$location targets no biomes"
            }

            checkThat(modifier.biomes().all { it.value().generationSettings.hasFeature(placedFeature) }) {
                "$location was not applied to every biome it targets"
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun bothOreVariantsHaveLootTables(helper: GameTestHelper) {
        val lootData = helper.level.server.lootData

        ORE_WORLDGEN.forEach { worldgen ->
            listOf(worldgen.ore.oreBlock, worldgen.ore.deepslateOreBlock).forEach { block ->
                checkThat(lootData.getLootTable(block.get().lootTable) !== LootTable.EMPTY) {
                    "${block.id} has no loot table"
                }
            }
        }

        helper.succeed()
    }

    @GameTest(template = ARENA)
    @JvmStatic
    fun everyMachineHullCanBeCrafted(helper: GameTestHelper) {
        val server = helper.level.server

        val craftedItems = server.recipeManager.recipes
            .filterNot { it.id.path.startsWith("crafting/processing_machine/") }
            .map { it.getResultItem(server.registryAccess()).item }
            .toSet()

        Eln2Processing.PROCESSING_MACHINES_FOR_VISUAL_REGISTRATION_AND_DATAGEN.forEach { machine ->
            checkThat(machine.hullItem.get() in craftedItems) {
                "${machine.hullItem.id} can only be obtained by disassembling the machine that requires it"
            }
        }

        helper.succeed()
    }
}
