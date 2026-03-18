package org.eln2.mc.common.specs

import dev.engine_room.flywheel.lib.model.baked.PartialModel
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.*
import org.ageseries.libage.data.mutableBiMapOf
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import org.eln2.mc.common.specs.foundation.BasicSpecProvider
import org.eln2.mc.common.specs.foundation.Spec
import org.eln2.mc.common.specs.foundation.SpecFactory
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

    fun specAndItemWithProvider(name: String, provider: SpecProvider): SpecRegistryItem {
        val spec = SPECS.register(name) { provider }
        val item = SPEC_ITEMS.register(name) { SpecItem(provider) }

        specs.add(provider, spec.id)

        return SpecRegistryItem(name, spec, item)
    }

    fun specMemoizeBB(name: String, previewModel: PartialModel?, placementCollisionSize: Vector3d, memoizer: Supplier<SpecFactory>) =
        specAndItemWithProvider(name, BasicSpecProvider(previewModel, placementCollisionSize / 16.0, memoizer.get()))

    fun specMemoizeBB(name: String, previewModel: PartialModel?, sx: Double, sy: Double, sz: Double, memoizer: Supplier<SpecFactory>) =
        specMemoizeBB(name, previewModel, Vector3d(sx, sy, sz), memoizer)

    fun specImmediateBB(name: String, previewModel: PartialModel?, placementCollisionSize: Vector3d, factory: SpecFactory) =
        specAndItemWithProvider(name, BasicSpecProvider(previewModel, placementCollisionSize / 16.0, factory))

    fun specImmediateBB(name: String, previewModel: PartialModel?, sx: Double, sy: Double, sz: Double, factory: SpecFactory) =
        specImmediateBB(name, previewModel, Vector3d(sx, sy, sz), factory)

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
}
