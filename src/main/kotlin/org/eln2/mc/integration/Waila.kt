package org.eln2.mc.integration

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import org.ageseries.libage.data.*
import org.eln2.mc.*
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import snownee.jade.api.*
import snownee.jade.api.config.IPluginConfig

@WailaPlugin
class Eln2WailaPlugin : IWailaPlugin {
    override fun register(registration: IWailaCommonRegistration) {
        registration.registerBlockDataProvider(ComponentDisplayProvider, BlockEntity::class.java)
    }

    override fun registerClient(registration: IWailaClientRegistration) {
        registration.registerBlockComponent(ComponentDisplayProvider, Block::class.java)
    }

    private object ComponentDisplayProvider : IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        private const val COMPONENT_DISPLAY = "component_display"

        override fun getUid() = resource(COMPONENT_DISPLAY)

        override fun appendServerData(p0: CompoundTag, p1: BlockAccessor) {
            var node = p1.blockEntity as? ComponentDisplay
                ?: return

            if(node is MultipartBlockEntity) {
                node = node.pickPart(p1.player) as? ComponentDisplay
                    ?: return
            }

            val components = mutableListOf<Component>()
            val builder = ComponentDisplayList(components)

            try {
                node.submitDisplay(builder)
            } catch (e : Exception) {
                // Handle errors caused by simulator (invalid access from user code)
                // Make sure you add a breakpoint here if you aren't getting your tooltip properly
                LOG.error("Append component error $node: $e")
            }

            p0.put(COMPONENT_DISPLAY, packComponentList(components))
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
    fun add(component: Component) {
        entries.add(component)
    }

    fun langId(identifier: String): String {
        return "waila.$MODID.$identifier"
    }

    @Deprecated("DEBUG ONLY - NO LOCALIZATION",
        ReplaceWith("translate(...)")
    )
    fun debug(text: String) {
        add(Component.literal("*$text"))
    }

    fun translatePercent(key: String, percentage: Double) {
        add(Component.translatable(langId(key)).apply {
            append(": ")
            append(percentage.formattedPercentNormalized())
        })
    }

    fun translateRow(key: String, text: String) = add(
        Component.translatable(
            langId(key)
        ).apply {
            append(": ")
            append(text)
        }
    )

    inline fun<reified T> quantity(quantity: Quantity<T>) = translateRow(
        T::class.simpleName.requireNotNull {
            "Failed to get simple name of ${T::class.java}"
        },
        quantity.classify()
    )

    inline fun<reified T1> translateQuantityRow(text: String, q1: Quantity<T1>) = translateRow(text, q1.classify())

    fun fuelMass(mass: Quantity<Mass>) = translateRow("fuel_mass", mass.classify())

    fun current(value: Double) = quantity(Quantity(value, AMPERE))
    fun potential(value: Double) = quantity(Quantity(value, VOLT))
    fun resistance(value: Double) = quantity(Quantity(value, OHM))
    fun power(value: Double) = quantity(Quantity(value, WATT))
    fun energy(value: Double) = quantity(Quantity(value, JOULE))
    fun temperature(value: Double) = quantity(Quantity(value, KELVIN))
    fun charge(value: Double) = translatePercent("charge", value)
    fun integrity(value: Double) = translatePercent("integrity", value)
}
