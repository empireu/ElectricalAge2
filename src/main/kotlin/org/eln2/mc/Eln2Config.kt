package org.eln2.mc

import com.mojang.brigadier.Command
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.common.ForgeConfigSpec.Builder
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.config.ModConfig
import org.ageseries.libage.data.*

class ClientConfig(builder: Builder) {
    private val unitOverrides : ConfigValue<List<String>>

    init {
        unitOverrides = builder
            .comment("Unit Overrides")
            .comment("Specify e.g. Temperature:Rk")
            .defineList("unit_overrides", emptyList<String>()) {
                if(it is String) {
                    val tokens =  it.split(":")
                    tokens.size == 2 && DIMENSION_TYPES.backward.containsKey(tokens[0])
                }
                else {
                    false
                }
            }
    }

    private fun unitOverridesValidateTokens(entry: String) : Pair<String, String> {
        val tokens = entry.split(":")

        check(tokens.size == 2) {
            "Invalid unit override $entry"
        }

        return tokens[0] to tokens[1]
    }

    fun getScaleOverride(dimensionType: Class<*>) : ScaleRef<*>? {
        val classifiers = AUXILIARY_CLASSIFIERS[dimensionType]
            ?: return null

        val name = DIMENSION_TYPES.forward[dimensionType]!!

        var override: ScaleRef<*>? = null

        unitOverrides.get().forEach {
            val (currentName, aliasOrSymbol) = unitOverridesValidateTokens(it)

            if(currentName == name) {
                // We keep scanning to detect duplicates

                check(override == null) {
                    "Duplicate override $it"
                }

                override = classifiers.entries.firstOrNull { entry ->
                    entry.value == aliasOrSymbol
                }?.key
            }
        }

        return override
    }

    fun setScaleOverride(dimensionType: Class<*>, override: String) {
        val name = DIMENSION_TYPES.forward[dimensionType]!!
        val lines = ArrayList<String>()
        var found = false

        unitOverrides.get().forEach {
            val (currentName, _) = unitOverridesValidateTokens(it)

            if(currentName == name) {
                if(found) {
                    LOG.error("Duplicate $it")
                }
                else {
                    lines.add("$name:$override")
                    found = true
                }
            }
            else {
                lines.add(it)
            }
        }

        if(!found) {
            lines.add("$name:$override")
        }

        unitOverrides.set(lines)
        unitOverrides.save()
    }

    fun resetScaleOverride(dimensionType: Class<*>) {
        val name = DIMENSION_TYPES.forward[dimensionType]!!
        val lines = ArrayList<String>()

        unitOverrides.get().forEach {
            val (currentName, _) = unitOverridesValidateTokens(it)

            if(currentName != name) {
                lines.add(it)
            }
        }

        unitOverrides.set(lines)
        unitOverrides.save()
    }
}

class ServerConfig(builder: Builder) {
    val simulationThreadCount : ConfigValue<Int>
    val explodeWhenHot : ConfigValue<Boolean>
    val hotRadiatesLight : ConfigValue<Boolean>

    init {
        simulationThreadCount = builder
            .comment("Specifies how many threads to use for simulations")
            .define("simulation_threads", 8)

        explodeWhenHot = builder
            .comment("If true, very hot things will blow up")
            .define("explode_when_hot", true)

        hotRadiatesLight = builder
            .comment("If true, hot things (wires, etc) will emit light")
            .define("hot_radiates_light", false)
    }
}

object Eln2Config {
    val clientConfig: ClientConfig
    private val clientSpec: ForgeConfigSpec
    val serverConfig: ServerConfig
    private val serverSpec: ForgeConfigSpec

    init {
        val clientPair = Builder().configure(::ClientConfig)
        clientConfig = clientPair.left
        clientSpec = clientPair.right

        val serverPair = Builder().configure(::ServerConfig)
        serverConfig = serverPair.left
        serverSpec = serverPair.right
    }

    fun registerSpecs(context: ModLoadingContext) {
        context.registerConfig(ModConfig.Type.CLIENT, clientSpec)
        context.registerConfig(ModConfig.Type.SERVER, serverSpec)
    }

    fun registerClientCommands(event: RegisterClientCommandsEvent) {
        val eln2 = Commands.literal("eln2").then(
            Commands.literal("units").then(
                Commands.literal("set").also { pSet ->
                    DIMENSION_TYPES.forward.entries.sortedBy { it.value }.forEach { (dimensionType, dimensionName) ->
                        val auxiliaryUnits = AUXILIARY_CLASSIFIERS[dimensionType]
                            ?: return@forEach

                        if(auxiliaryUnits.keys.isNotEmpty()) {
                            pSet.then(
                                Commands.literal(dimensionName).also { pDimension ->
                                    auxiliaryUnits.keys.forEach { scaleRef ->
                                        auxiliaryUnits[scaleRef].forEach { identifier ->
                                            pDimension.then(
                                                Commands.literal("to").then(
                                                    Commands.literal(identifier).executes {
                                                        clientConfig.setScaleOverride(dimensionType, identifier)

                                                        Minecraft.getInstance().player?.also { player ->
                                                            player.displayClientMessage(Component.literal("$dimensionName -> ${classifyAuxiliary(scaleRef, 1.0)}"), false)
                                                        }

                                                        Command.SINGLE_SUCCESS
                                                    }
                                                )
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            ).then(
                Commands.literal("reset").also { pReset ->
                    DIMENSION_TYPES.forward.entries.sortedBy { it.value }.forEach { (dimensionType, dimensionName) ->
                        val auxiliaryUnits = AUXILIARY_CLASSIFIERS[dimensionType]
                            ?: return@forEach

                        if(auxiliaryUnits.keys.isNotEmpty()) {
                            pReset.then(Commands.literal(dimensionName).executes {
                                clientConfig.resetScaleOverride(dimensionType)

                                Minecraft.getInstance().player?.also { player ->
                                    player.displayClientMessage(Component.literal("$dimensionName -> ${classify(dimensionType, 1.0 /*Factor*/)}"), false)
                                }

                                Command.SINGLE_SUCCESS
                            })
                        }
                    }
                }
            ).then(
                Commands.literal("preset").also { pPreset ->
                    fun definePreset(name: String, buildPreset: (applicator: (Class<*>, String) -> Unit) -> Unit) {
                        pPreset.then(Commands.literal(name).executes {
                            fun applicator(dimensionType: Class<*>, alias: String) {
                                if(alias.isEmpty()) {
                                    clientConfig.resetScaleOverride(dimensionType)
                                }
                                else {
                                    clientConfig.setScaleOverride(dimensionType, alias)
                                }
                            }

                            buildPreset(::applicator)

                            Minecraft.getInstance().player?.also { player ->
                                player.displayClientMessage(Component.literal("*$name"), false)
                            }

                            Command.SINGLE_SUCCESS
                        })
                    }

                    definePreset("SI") {
                        it(Time::class.java, "")
                        it(Distance::class.java, "")
                        it(Mass::class.java, "")
                        it(Energy::class.java, "")
                        it(Temperature::class.java, "")
                    }

                    // Todo actually add these
                }
            )
        )

        event.dispatcher.register(eln2)
    }
}
