package org.eln2.mc.common.specs

import net.minecraft.resources.ResourceLocation
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.*
import org.ageseries.libage.data.mutableBiMapOf
import org.ageseries.libage.mathematics.Vector3d
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import org.eln2.mc.common.parts.foundation.MySpec
import org.eln2.mc.common.specs.foundation.BasicSpecProvider
import org.eln2.mc.common.specs.foundation.Spec
import org.eln2.mc.common.specs.foundation.SpecItem
import org.eln2.mc.common.specs.foundation.SpecProvider
import org.eln2.mc.resource
import java.util.function.Supplier

object SpecRegistry {
    val SPECS: DeferredRegister<SpecProvider> = DeferredRegister.create(resource("specs"), MODID)
    val SPEC_ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID)!!

    private lateinit var specRegistry: Supplier<IForgeRegistry<SpecProvider>>

    fun setup(bus: IEventBus) {
        specRegistry = SPECS.makeRegistry { RegistryBuilder() }
        SPECS.register(bus)
        SPEC_ITEMS.register(bus)

        LOG.info("Prepared spec registry.")
    }

    class SpecRegistryItem(
        val name: String,
        val spec: RegistryObject<SpecProvider>,
        val item: RegistryObject<SpecItem>,
    )

    private val specs = mutableBiMapOf<SpecProvider, ResourceLocation>()

    fun getId(provider: SpecProvider) = specs.forward[provider] ?: error("Failed to get spec id $provider")

    fun specAndItem(name: String, provider: SpecProvider): SpecRegistryItem {
        val spec = SPECS.register(name) { provider }
        val item = SPEC_ITEMS.register(name) { SpecItem(provider) }

        specs.add(provider, spec.id)

        return SpecRegistryItem(name, spec, item)
    }

    /**
     * Gets the Spec Provider with the specified ID, or null, if it does not exist.
     * */
    fun tryGetProvider(id: ResourceLocation): SpecProvider? {
        return specRegistry.get().getValue(id)
    }

    /**
     * Gets the [SpecItem] of the [Spec] with the specified ID.
     * */
    fun getSpecItem(id: ResourceLocation): SpecItem {
        return ForgeRegistries.ITEMS.getValue(id) as SpecItem
    }

    val TEST = specAndItem("test_spec", BasicSpecProvider(Vector3d(0.1, 0.1, 0.1)) {
        MySpec(it)
    })
}
