package org.eln2.mc.common.cells

import net.minecraft.resources.ResourceLocation
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.IForgeRegistry
import net.minecraftforge.registries.RegistryBuilder
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.mutableBiMapOf
import org.eln2.mc.LOG
import org.eln2.mc.MODID
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.grids.GridConnectionCell
import org.eln2.mc.resource
import java.util.function.Supplier

object CellRegistry {
    private val CELLS = DeferredRegister.create<CellProvider<*>>(resource("cells"), MODID)

    private lateinit var cellRegistry: Supplier<IForgeRegistry<CellProvider<*>>>

    fun setup(bus: IEventBus) {
        cellRegistry = CELLS.makeRegistry { RegistryBuilder() }
        CELLS.register(bus)

        LOG.info("Prepared cell registry.")
    }

    private val cells = mutableBiMapOf<CellProvider<*>, ResourceLocation>()

    fun getCellId(provider: CellProvider<*>) = cells.forward[provider] ?: error("Failed to get cell id $provider")

    /**
     * Registers a cell with the specified [id] and [provider].
     * */
    fun<T : Cell> cellWithProvider(id: String, provider: CellProvider<T>): RegistryObject<CellProvider<T>> {
        val result = CELLS.register(id) { provider }

        cells.add(provider, result.id)

        return result
    }

    /**
     * Registers a cell with the specified [id], and a provider supplied by the [memoizer].
     * Meant to be used when DTOs (models/configuration objects) need to be created.
     * They should be created in the body of the [memoizer], and then the [CellFactory] should be created, that calls the cell's constructor with all the pre-created DTOs.
     * */
    fun<T : Cell> cellMemoize(id: String, memoizer: Supplier<CellFactory<T>>) = cellWithProvider(id, BasicCellProvider(memoizer.get()))

    /**
     * Registers a cell with the specified [id]. The passed body is the cell factory. No DTOs should be created in this body.
     * */
    fun<T : Cell> cellImmediate(id: String, factory: CellFactory<T>) = cellWithProvider(id, BasicCellProvider(factory))

    fun getCellProvider(id: ResourceLocation): CellProvider<*> {
        return cellRegistry.get().getValue(id) ?: error("Could not get cell provider with id $id")
    }

    val GRID_CONNECTION = cellWithProvider("grid_connection", BasicCellProvider {
        GridConnectionCell(it)
    })
}
