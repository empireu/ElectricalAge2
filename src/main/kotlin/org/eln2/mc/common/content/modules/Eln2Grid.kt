@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.phys.AABB
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.G_PER_CM3
import org.ageseries.libage.data.MILLI
import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.OHM_METER
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.ChemicalElement
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.BasicSpecVisual
import org.eln2.mc.client.render.foundation.ConnectedPartVisual
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry.setPartVisualizer
import org.eln2.mc.client.render.foundation.FlwVisualizerRegistry.setSpecVisualizer
import org.eln2.mc.client.render.foundation.TestBlockEntityVisual
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockItemOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockOnly
import org.eln2.mc.common.blocks.BlockRegistry.defineDelegateMap
import org.eln2.mc.common.blocks.foundation.BigBlockItem
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateMap
import org.eln2.mc.common.cells.CellRegistry.cellImmediate
import org.eln2.mc.common.cells.foundation.CellProvider
import org.eln2.mc.common.cells.foundation.ElectricalSize
import org.eln2.mc.common.content.GridAnchorCell
import org.eln2.mc.common.content.GridAnchorSpec
import org.eln2.mc.common.content.GridInterfaceCell
import org.eln2.mc.common.content.GridInterfacePart
import org.eln2.mc.common.content.GridPoleBlock
import org.eln2.mc.common.content.GridPoleBlockEntity
import org.eln2.mc.common.grids.GridCablePliersItem
import org.eln2.mc.common.grids.GridMaterial
import org.eln2.mc.common.grids.GridMaterialCategory
import org.eln2.mc.common.grids.GridMaterials
import org.eln2.mc.common.items.ItemRegistry.item
import org.eln2.mc.common.parts.PartRegistry.partMemoizeBB
import org.eln2.mc.common.parts.foundation.PartFactory
import org.eln2.mc.common.specs.SpecRegistry.specMemoizeBB
import org.eln2.mc.common.specs.foundation.SpecFactory
import kotlin.math.PI

object Eln2Grid : ContentModule() {
    override fun registerBlockEntityVisualizers() {
        VisualizerRegistry.setVisualizer(
            GRID_PASS_THROUGH_POLE_BLOCK_ENTITY.get(),
            SimpleBlockEntityVisualizer({ ctx, blockEntity, partialTick ->
                TestBlockEntityVisual(ctx, blockEntity, partialTick,FlwModels.POLE_TEMPORARY) { instance, renderer ->
                    instance.translate(renderer.visualPosition)
                }
            }) { true }
        )
    }

    override fun registerPartVisualizers() {
        setPartVisualizer<GridInterfacePart>(POWER_GRID_INTERFACE_PART.part.get()) { ctx, part ->
            ConnectedPartVisual(
                ctx, part,
                FlwModels.POWER_GRID_INTERFACE,
                FlwModels.STANDARD_CONNECTION
            )
        }

        setPartVisualizer<GridInterfacePart>(MICRO_GRID_INTERFACE_PART.part.get()) { ctx, part ->
            ConnectedPartVisual(
                ctx, part,
                FlwModels.MICRO_GRID_INTERFACE,
                FlwModels.STANDARD_CONNECTION
            )
        }

        setPartVisualizer<GridInterfacePart>(SIGNAL_GRID_INTERFACE_PART.part.get()) { ctx, part ->
            ConnectedPartVisual(
                ctx, part,
                FlwModels.SIGNAL_GRID_INTERFACE,
                FlwModels.SIGNAL_WIRE_CONNECTION.hub
            )
        }
    }

    override fun registerSpecVisualizers() {
        setSpecVisualizer<GridAnchorSpec>(Eln2Grid.MICRO_GRID_ANCHOR_SPEC.spec.get()) { ctx, spec ->
            BasicSpecVisual(
                ctx, spec,
                FlwModels.MICRO_GRID_ANCHOR
            )
        }

        setSpecVisualizer<GridAnchorSpec>(Eln2Grid.SIGNAL_GRID_ANCHOR_SPEC.spec.get()) { ctx, spec ->
            BasicSpecVisual(
                ctx, spec,
                FlwModels.SIGNAL_GRID_ANCHOR
            )
        }
    }

    val GRID_CABLE_PLIERS = item("grid_cable_pliers", ::GridCablePliersItem)

    // PS. this is a supplier, it's fine on the server side.
    val GRID_COPPER_TEXTURE = GridMaterials.gridAtlasSprite("copper_cable")
    val GRID_IRON_TEXTURE = GridMaterials.gridAtlasSprite("iron_cable")
    val GRID_INSULATED_TEXTURE = GridMaterials.gridAtlasSprite("insulated_cable")

    //#region Grid Render Shapes

    val POWER_GRID_SHAPE = GridMaterial.Catenary(
        8, 0.05,
        2.0 * PI * 0.1, 0.25
    )

    val MICRO_GRID_SHAPE = GridMaterial.Straight(
        8, 0.01,
        2.0 * PI * 0.1, 1.0
    )

    val SIGNAL_GRID_SHAPE = GridMaterial.Straight(
        4, 0.01,
        2.0 * PI * 0.1, 1.0
    )

    //#endregion

    //#region Simulation Materials (physical properties of conductor material)

    private val GRID_COPPER_MATERIAL = ChemicalElement.Copper.asMaterial.copy(
        label = "Grid Copper Wire",
        density = Quantity(4.0, G_PER_CM3),
        electricalResistivity = Quantity(1.7e-9, OHM_METER) // one order of magnitude
    )

    private val GRID_IRON_MATERIAL = ChemicalElement.Iron.asMaterial.copy(
        label = "Grid Iron Wire",
        density = Quantity(4.5, G_PER_CM3),
        electricalResistivity = Quantity(9.700000000001e-9, OHM_METER) // one order of magnitude
    )

    private val GRID_SIGNAL_MATERIAL = GRID_COPPER_MATERIAL.copy(
        label = "Signal Copper Wire"
    )

    //#endregion

    //#region Connects (cable items and materials)

    val POWER_GRID_CONNECT_COPPER = GridMaterials.gridConnect(
        "power_grid_copper_cable",
        GridMaterial(
            GRID_COPPER_TEXTURE,
            GRID_COPPER_MATERIAL,
            POWER_GRID_SHAPE,
            GridMaterialCategory.PowerGrid,
            ChemicalElement.Copper.meltingPoint * 0.9,
            25
        )
    )

    val POWER_GRID_CONNECT_IRON = GridMaterials.gridConnect(
        "power_grid_iron_cable",
        GridMaterial(
            GRID_IRON_TEXTURE,
            GRID_IRON_MATERIAL,
            POWER_GRID_SHAPE,
            GridMaterialCategory.PowerGrid,
            ChemicalElement.Iron.meltingPoint * 0.9,
            150
        )
    )

    val MICRO_GRID_CONNECT_COPPER = GridMaterials.gridConnect(
        "micro_grid_copper_cable",
        GridMaterial(
            GRID_COPPER_TEXTURE,
            GRID_COPPER_MATERIAL,
            MICRO_GRID_SHAPE,
            GridMaterialCategory.MicroGrid,
            ChemicalElement.Copper.meltingPoint * 0.9,
            10
        )
    )

    val MICRO_GRID_CONNECT_IRON = GridMaterials.gridConnect(
        "micro_grid_iron_cable",
        GridMaterial(
            GRID_IRON_TEXTURE,
            GRID_IRON_MATERIAL,
            MICRO_GRID_SHAPE,
            GridMaterialCategory.MicroGrid,
            ChemicalElement.Iron.meltingPoint * 0.9,
            35
        )
    )

    val SIGNAL_GRID_CONNECT = GridMaterials.gridConnect(
        "signal_grid_cable",
        GridMaterial(
            GRID_INSULATED_TEXTURE,
            GRID_SIGNAL_MATERIAL,
            SIGNAL_GRID_SHAPE,
            GridMaterialCategory.SignalGrid,
            Quantity(140.0, CELSIUS),
            5
        )
    )

    //#endregion

    //#region Anchors (anchor cells and anchor specs)

    val MICRO_GRID_ANCHOR_CELL = cellImmediate("micro_grid_anchor") {
        GridAnchorCell(
            it,
            !Quantity(1e-5, OHM)
        )
    }

    val SIGNAL_GRID_ANCHOR_CELL = cellImmediate("signal_grid_anchor") {
        GridAnchorCell(
            it,
            !Quantity(1e-5, OHM)
        )
    }

    val MICRO_GRID_ANCHOR_SPEC = specMemoizeBB("micro_grid_anchor", FlwModels.MICRO_GRID_ANCHOR, 1.0, 1.5, 1.0) {
        val terminalSize = Vector3d(1.0 / 16.0, 1.5 / 16.0, 1.0 / 16.0)
        val categories = listOf(GridMaterialCategory.MicroGrid)

        SpecFactory {
            GridAnchorSpec(
                it,
                terminalSize,
                categories
            )
        }
    }

    val SIGNAL_GRID_ANCHOR_SPEC = specMemoizeBB("signal_grid_anchor", FlwModels.SIGNAL_GRID_ANCHOR, 1.0, 1.5, 1.0) {
        val terminalSize = Vector3d(1.0 / 16.0, 1.5 / 16.0, 1.0 / 16.0)
        val categories = listOf(GridMaterialCategory.SignalGrid)

        SpecFactory {
            GridAnchorSpec(
                it,
                terminalSize,
                categories
            )
        }
    }

    //#endregion

    //#region Interfaces (interface cells and interface parts)

    val POWER_GRID_INTERFACE_CELL = cellImmediate("power_grid_interface") {
        GridInterfaceCell(
            it,
            !Quantity(0.25, MILLI * OHM),
            !Quantity(0.2, MILLI * OHM),
            ElectricalSize.Standard
        )
    }

    val POWER_GRID_INTERFACE_PART = partMemoizeBB("power_grid_interface", 4.0, 8.0, 4.0) {
        val terminalSize = Vector3d(4.0 / 16.0, 8.0 / 16.0, 4.0 / 16.0) * 1.01
        val categories = listOf(GridMaterialCategory.PowerGrid)

        PartFactory {
            GridInterfacePart(
                it,
                terminalSize,
                categories,
                POWER_GRID_INTERFACE_CELL
            )
        }
    }

    val MICRO_GRID_INTERFACE_CELL = cellImmediate("micro_grid_interface") {
        GridInterfaceCell(
            it,
            !Quantity(0.05, MILLI * OHM),
            !Quantity(0.0156, MILLI * OHM),
            ElectricalSize.Standard
        )
    }

    val SIGNAL_GRID_INTERFACE_CELL = cellImmediate("signal_grid_interface") {
        GridInterfaceCell(
            it,
            !Quantity(3.5, MILLI * OHM),
            !Quantity(6.1, MILLI * OHM),
            ElectricalSize.Signal
        )
    }

    val MICRO_GRID_INTERFACE_PART = partMemoizeBB("micro_grid_interface", 4.0, 4.0, 4.0) {
        val categories = listOf(GridMaterialCategory.MicroGrid)

        PartFactory {
            GridInterfacePart(
                it,
                Vector3d(2.0 / 16.0, 4.0 / 16.0, 2.0 / 16.0),
                categories,
                MICRO_GRID_INTERFACE_CELL
            )
        }
    }

    val SIGNAL_GRID_INTERFACE_PART = partMemoizeBB("signal_grid_interface", 2.85, 4.0, 2.85) {
        val categories = listOf(GridMaterialCategory.SignalGrid)

        PartFactory {
            GridInterfacePart(
                it,
                Vector3d(2.0 / 16.0, 4.0 / 16.0, 2.0 / 16.0),
                categories,
                SIGNAL_GRID_INTERFACE_CELL
            )
        }
    }

    //#endregion

    //#region Poles (delegate maps and block entities)

    val GRID_POLE_DELEGATE_MAP = defineDelegateMap("grid_pole") {
        val column = registerDelegate(
            0.35, 0.0, 0.35,
            0.65, 1.0, 0.65
        )

        principal(0, 1, 0, column)
        principal(0, 2, 0, column)
    }

    @Suppress("SameParameterValue")
    private fun registerGridPole(
        name: String,
        delegateMap: Lazy<MultiblockDelegateMap>,
        attachment: Vector3d,
        cell: RegistryObject<CellProvider<GridAnchorCell>>
    ) : RegistryObject<BlockEntityType<GridPoleBlockEntity>> {
        val block = blockOnly(name) {
            GridPoleBlock(
                delegateMap.value,
                attachment,
                cell
            )
        }

        val blockEntity = blockEntityOnly(name, block) { pos, state ->
            GridPoleBlockEntity(
                representativeBlock = block.get(),
                pos,
                state
            )
        }

        blockItemOnly(name) {
            BigBlockItem(
                delegateMap.value,
                block.get()
            )
        }

        return blockEntity
    }

    val GRID_PASS_THROUGH_POLE_BLOCK_ENTITY = registerGridPole(
        "grid_pass_pole",
        GRID_POLE_DELEGATE_MAP,
        Vector3d(0.5, 2.5, 0.5),
        MICRO_GRID_ANCHOR_CELL
    )

    //#endregion
}
