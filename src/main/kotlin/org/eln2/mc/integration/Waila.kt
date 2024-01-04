package org.eln2.mc.integration

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.LiteralContents
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import org.ageseries.libage.data.*
import org.ageseries.libage.utils.sourceName
import org.eln2.mc.*
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateBlock
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateBlockEntity
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.specs.foundation.SpecContainerPart
import org.eln2.mc.extensions.forEachCompound
import org.eln2.mc.extensions.formattedPercentNormalized
import snownee.jade.api.*
import snownee.jade.api.config.IPluginConfig
import java.util.*
import kotlin.math.absoluteValue


@WailaPlugin
class Eln2WailaPlugin : IWailaPlugin {
    override fun register(registration: IWailaCommonRegistration) {
        registration.registerBlockDataProvider(ComponentDisplayProvider, BlockEntity::class.java)
    }

    override fun registerClient(registration: IWailaClientRegistration) {
        registration.registerBlockComponent(ComponentDisplayProvider, Block::class.java)

        registration.addRayTraceCallback { _, accessor, _ ->
            if (accessor is BlockAccessor) {
                if (accessor.block is MultiblockDelegateBlock) {
                    val delegateBlockEntity = accessor.blockEntity
                        as? MultiblockDelegateBlockEntity

                    if(delegateBlockEntity != null) {
                        val representativePos = delegateBlockEntity.representativePos

                        if(representativePos != null) {
                            return@addRayTraceCallback registration
                                .blockAccessor()
                                .from(accessor)
                                .blockState(accessor.level.getBlockState(representativePos))
                                .blockEntity(accessor.level.getBlockEntity(representativePos))
                                .build()
                        }
                    }
                }
            }

            return@addRayTraceCallback accessor
        }
    }

    private object ComponentDisplayProvider : IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        private const val COMPONENT_DISPLAY = "component_display"

        override fun getUid() = resource(COMPONENT_DISPLAY)

        private inline fun<reified T> castGameObject(p1: BlockAccessor) : T? {
            val blockEntity = p1.blockEntity
                ?: return null

            if(blockEntity is MultipartBlockEntity) {
                val part = blockEntity.pickPart(p1.player)

                if(part is SpecContainerPart) {
                    return part.pickSpec(p1.player)?.second as? T
                }

                return part as? T
            }

            return blockEntity as? T
        }

        override fun appendServerData(p0: CompoundTag, p1: BlockAccessor) {
            val display = castGameObject<ComponentDisplay>(p1)

            // FIXME SharedConstants.IS_RUNNING_IN_IDE is not working
            val debugDisplay = if(true) {
                castGameObject<DebugComponentDisplay>(p1)
            } else {
                null
            }

            val components = mutableListOf<Component>()
            val builder = ComponentDisplayList(components)

            if(display != null) {
                try {
                    display.submitDisplay(builder)
                } catch (e : Throwable) {
                    LOG.error("Display error $display: $e")
                }
            }

            if(debugDisplay != null) {
                try {
                    debugDisplay.submitDebugDisplay(builder)
                } catch (e : Throwable) {
                    LOG.error("DEBUG Display error $display: $e")
                }
            }

            if(components.isNotEmpty()) {
                p0.put(COMPONENT_DISPLAY, packComponentList(components))
            }
        }

        override fun appendTooltip(p0: ITooltip, p1: BlockAccessor, p2: IPluginConfig) {
            val tag = p1.serverData.get(COMPONENT_DISPLAY) as? CompoundTag
                ?: return

            val components = unpackComponentList(tag)

            components.forEach {
                p0.add(it)
            }
        }
    }
}

/**
 * Implemented by classes that want to export simple text data to JADE quickly.
 * Only [Component]s are supported.
 * */
interface ComponentDisplay {
    fun submitDisplay(builder: ComponentDisplayList)
}

/**
 * Debug-only version of [ComponentDisplay]. It will be used if the mod is running in the IDE.
 * */
interface DebugComponentDisplay {
    fun submitDebugDisplay(builder: ComponentDisplayList)
}

private const val ENTRIES = "entries"
private const val JSON = "json"

private fun unpackComponentList(tag: CompoundTag): List<Component> {
    val listTag = tag.get(ENTRIES) as? ListTag

    if (listTag == null || listTag.size == 0) {
        return emptyList()
    }

    val results = ArrayList<Component>(listTag.size)

    listTag.forEachCompound {
        val text = it.getString(JSON)
            ?: return@forEachCompound

        val component = Component.Serializer.fromJson(text)
            ?: return@forEachCompound

        for (i in component.siblings.indices) {
            val sibling = component.siblings[i]
            val contents = sibling.contents

            if(contents is LiteralContents) {
                if(contents.text.startsWith(ComponentDisplayList.QUANTITY_PREFIX)) {
                    val dimensionName = contents.text
                        .removePrefix(ComponentDisplayList.QUANTITY_PREFIX)
                        .substringBefore(ComponentDisplayList.QUANTITY_SUFFIX)

                    val number = contents.text
                        .substringAfter(ComponentDisplayList.QUANTITY_SUFFIX)
                        .toDouble()

                    val dimensionClass = checkNotNull(DIMENSION_TYPES.backward[dimensionName])
                    val auxiliaryScale = Eln2Config.clientConfig.getScaleOverride(dimensionClass)

                    component.siblings[i] = Component.literal(
                        if(auxiliaryScale == null) {
                            classify(dimensionClass, number)
                        }
                        else {
                            classifyAuxiliary(auxiliaryScale, number)
                        }
                    )
                }
            }
        }

        results.add(component)
    }

    return results
}

private fun packComponentList(components: List<Component>) : CompoundTag {
    val tag = CompoundTag()
    val listTag = ListTag()

    components.forEach {
        val text = Component.Serializer.toJson(it)
        val compound = CompoundTag()
        compound.putString(JSON, text)
        listTag.add(compound)
    }

    tag.put(ENTRIES, listTag)

    return tag
}

class ComponentDisplayList(private val entries: MutableList<Component>) {
    companion object {
        private const val QUANTITY_IDENTIFIER = "Eln2Quantity"
        const val QUANTITY_PREFIX = "$QUANTITY_IDENTIFIER["
        const val QUANTITY_SUFFIX = "]"
    }

    fun add(component: Component) {
        entries.add(component)
    }

    private fun translationKey(identifier: String): String {
        return "waila.$MODID.$identifier"
    }

    @Deprecated("DEBUG ONLY - NO LOCALIZATION",
        ReplaceWith("translate(...)")
    )
    fun debug(text: String) {
        add(Component.literal("*$text"))
    }

    fun translatePercent(key: String, percentage: Double) {
        add(
            Component.translatable(translationKey(key)).apply {
                append(": ")
                append(percentage.formattedPercentNormalized())
            }
        )
    }

    fun translateRow(key: String, text: String) {
        add(
            Component.translatable(translationKey(key)).apply {
                append(": ")
                append(text)
            }
        )
    }

    // Not sure how to do it with Component in a cleaner way -- Component hard-codes
    // serialization for contents, so we can't just make QuantityContents or something like that without some involved
    // mixin (there's no easy place to attach to) so I rather just do it like this

    inline fun<reified T> translateQuantityRow(key: String, quantity: Quantity<T>, eps: Double = 1e-6) {
        val name = DIMENSION_TYPES.forward[T::class.java]

        checkNotNull(name) {
            "Invalid dimension ${T::class.java}"
        }

        translateRow(
            key,
            "${QUANTITY_PREFIX}${name}${QUANTITY_SUFFIX}${if(quantity.value.absoluteValue < eps) 0.0 else quantity.value}"
        )
    }

    inline fun<reified T> quantity(quantity: Quantity<T>, eps: Double = 1e-6) {
        translateQuantityRow(T::class.java.sourceName(), quantity, eps)
    }

    fun current(value: Double) = quantity(Quantity(value, AMPERE))
    fun potential(value: Double) = quantity(Quantity(value, VOLT))
    fun resistance(value: Double) = quantity(Quantity(value, OHM))
    fun power(value: Double) = quantity(Quantity(value, WATT))
    fun energy(value: Double) = quantity(Quantity(value, JOULE))
    fun temperature(value: Double) = quantity(Quantity(value, KELVIN))
    fun charge(value: Double) = translatePercent("charge", value)
    fun integrity(value: Double) = translatePercent("integrity", value)
    fun progress(value: Double) = translatePercent("progress", value)
}
