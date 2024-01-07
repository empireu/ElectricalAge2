@file:Suppress("MemberVisibilityCanBePrivate", "PublicApiImplicitType", "PublicApiImplicitType", "unused", "LongLine",
    "NonAsciiCharacters", "LocalVariableName"
)

package org.eln2.mc.common.content

import com.jozufozu.flywheel.backend.instancing.InstancedRenderRegistry
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.core.Direction
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.phys.AABB
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.evaluate
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.lerp
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.Datasets
import org.eln2.mc.LOG
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.cutout
import org.eln2.mc.client.render.foundation.BasicPartRenderer
import org.eln2.mc.client.render.foundation.TestBlockEntityInstance
import org.eln2.mc.client.render.foundation.defaultRadiantBodyColor
import org.eln2.mc.client.render.solid
import org.eln2.mc.common.*
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItem
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockItemOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockOnly
import org.eln2.mc.common.blocks.BlockRegistry.defineDelegateMap
import org.eln2.mc.common.blocks.foundation.BigBlockItem
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateMap
import org.eln2.mc.common.cells.CellRegistry.cell
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.containers.ContainerRegistry.menu
import org.eln2.mc.common.items.CreativeTabRegistry
import org.eln2.mc.common.items.ItemRegistry
import org.eln2.mc.common.items.ItemRegistry.item
import org.eln2.mc.common.parts.PartRegistry.partAndItem
import org.eln2.mc.common.parts.foundation.BasicPartProvider
import org.eln2.mc.common.parts.foundation.transformPartWorld
import org.eln2.mc.common.specs.SpecRegistry.specAndItem
import org.eln2.mc.common.specs.foundation.*
import org.eln2.mc.data.Locators
import org.eln2.mc.data.cylinderResistance
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.data.withDirectionRulePlanar
import org.eln2.mc.extensions.vector3d
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.requireIsOnRenderThread
import kotlin.math.PI
import kotlin.math.pow

/**
 * Joint registry for content classes.
 */
object Content {
    /**
     * Initializes the fields, in order to register the content.
     */
    fun initialize() {}

    //#region Tools

    val WRENCH = item("wrench") { WrenchItem() }

    //#endregion

    //#region Wires

    private val UNINSULATED_WIRE_LIGHT_FIELD = LightFieldPrimitives.sourceOnlyStart(15)

    val COPPER_THERMAL_WIRE = ThermalWireBuilder("thermal_wire_copper")
        .apply {
            damageOptions = TemperatureExplosionBehaviorOptions(
                temperatureThreshold = Quantity(1000.0, CELSIUS)
            )

            material = ThermalMassDefinition(
                ChemicalElement.Copper.asMaterial.copy(
                    label = "Copper Thermal Conductor",
                    thermalConductivity = Quantity(3500.0, WATT_PER_METER_KELVIN),
                )
            )

            leakageParameters = ConnectionParameters.DEFAULT.copy(
                conductance = Quantity(0.05, WATT_PER_KELVIN)
            )

            radiantDescription = RadiantBodyEmissionDescription({
                UNINSULATED_WIRE_LIGHT_FIELD
            })
        }
        .register()

    val ELECTRICAL_WIRE_COPPER = ElectricalWireBuilder("electrical_cable_copper")
        .apply {
            isIncandescent = false
            leakageParameters = ConnectionParameters.DEFAULT.copy(
                conductance = Quantity(0.01, WATT_PER_KELVIN) // Insulation
            )
        }
        .register()

    val THERMAL_RADIATOR_CELL = cell(
        "thermal_radiator",
        BasicCellProvider.setup {
            val thermalProperties = WireThermalProperties(
                ThermalMassDefinition(
                    ChemicalElement.Copper.asMaterial,
                    mass = Quantity(50.0, KILOGRAM)
                ),
                TemperatureExplosionBehaviorOptions(
                    temperatureThreshold = Quantity(1000.0, CELSIUS)
                ),
                replicatesInternalTemperature = true,
                replicatesExternalTemperature = true,
                null, // TODO maybe it does radiate?
                leakageParameters = ConnectionParameters(
                    area = 5.0
                ),
            )

            CellFactory {
                ThermalWireCell(it, Double.POSITIVE_INFINITY, thermalProperties)
            }
        }
    )

    val THERMAL_RADIATOR_PART = partAndItem(
        "thermal_radiator",
        BasicPartProvider(Vector3d(1.0, 3.0 / 16.0, 1.0)) { ci ->
            RadiatorPart(ci, defaultRadiantBodyColor())
        }
    )

    //#endregion

    //#region Creative Components

    val VOLTAGE_SOURCE_CELL = cell(
        "voltage_source",
        BasicCellProvider(::VoltageSourceCell)
    )

    val VOLTAGE_SOURCE_PART = partAndItem(
        "voltage_source",
        BasicPartProvider(
            Vector3d(6.0 / 16.0, 2.5 / 16.0, 6.0 / 16.0),
            ::VoltageSourcePart
        )
    )

    val GROUND_CELL = cell(
        "ground",
        BasicCellProvider(::GroundCell)
    )

    val GROUND_PART = partAndItem(
        "ground",
        BasicPartProvider(
            Vector3d(4.0 / 16.0, 4.0 / 16.0, 4.0 / 16.0),
            ::GroundPart
        )
    )

    //#endregion

    //#region Batteries

    val LEAD_ACID_BATTERY_CELL_12V = cell(
        "lead_acid_battery_12v",
        BasicCellProvider.setup {
            val model =  BatteryModel(
                voltageFunction = {
                    val voltageDataset = Datasets.LEAD_ACID_VOLTAGE
                    val temperature = it.temperature

                    if (it.charge > it.model.damageChargeThreshold) {
                        Quantity(voltageDataset.evaluate(it.charge, !temperature), VOLT)
                    } else {
                        val datasetCeiling = voltageDataset.evaluate(it.model.damageChargeThreshold, !temperature)

                        Quantity(
                            lerp(
                                0.0,
                                datasetCeiling,
                                map(
                                    it.charge,
                                    0.0,
                                    it.model.damageChargeThreshold,
                                    0.0,
                                    1.0
                                )
                            ), VOLT)
                    }
                },
                resistanceFunction = {
                    Quantity(20.0, MILLI * OHM)
                },
                damageFunction = { battery, dt ->
                    var damage = 0.0

                    damage += dt * (1.0 / 3.0) * 1e-6 // 1 month
                    damage += !(abs(battery.energyIncrement) / (!battery.model.energyCapacity * 50.0))
                    damage += dt * kotlin.math.abs(battery.current).pow(1.12783256261) * 1e-7 *
                        if(battery.safeCharge > 0.0) 1.0
                        else map(battery.charge, 0.0, battery.model.damageChargeThreshold, 1.0, 5.0)

                    //println("T: ${battery.life / (damage / dt)}")

                    damage
                },
                capacityFunction = { battery ->
                    battery.life.pow(0.5)
                },
                energyCapacity = Quantity(2.2, KILO * WATT_HOUR),
                0.5,
                BatteryMaterials.LEAD_ACID_BATTERY,
                Quantity(10.0, KILOGRAM),
                Quantity(6.0, METER2)
            )

            CellFactory {
                val cell = BatteryCell(it, model)
                cell.energy = cell.model.energyCapacity * 0.9
                cell
            }
        }
    )

    val BATTERY_PART_12V = partAndItem(
        "lead_acid_battery_12v",
        BasicPartProvider(Vector3d(6.0 / 16.0, 7.0 / 16.0, 10.0 / 16.0)) { ci ->
            BatteryPart(ci, LEAD_ACID_BATTERY_CELL_12V.get()) { part ->
                BasicPartRenderer(part, PartialModels.BATTERY)
            }
        }
    )

    //#endregion

    //#region Basic Electrical Components

    val RESISTOR_CELL = cell(
        "resistor",
        BasicCellProvider(::ResistorCell)
    )

    val RESISTOR_PART = partAndItem(
        "resistor",
        BasicPartProvider(
            Vector3d(3.5 / 16.0, 2.25 / 16.0, 5.0 / 16.0),
            ::ResistorPart
        )
    )

    //#endregion

    //#region Photovoltaics

    val PHOTOVOLTAIC_GENERATOR_CELL = cell(
        "photovoltaic_generator",
        BasicCellProvider.setup {
            val model = PhotovoltaicModel(
                Quantity(32.0, VOLT),
                7000.0,
                0.1,
                0.8,
                0.35,
            )

            val surface = Quantity(1.0, METER2)

            CellFactory {
                PhotovoltaicGeneratorCell(it, surface, model) { cell ->
                    cell.locator.requireLocator(Locators.FACE).vector3d
                }
            }
        }
    )

    val PHOTOVOLTAIC_PANEL_PART = partAndItem(
        "photovoltaic_panel",
        BasicPartProvider(Vector3d(1.0, 2.0 / 16.0, 1.0)) { ci ->
            PhotovoltaicPanelPart(
                ci,
                PHOTOVOLTAIC_GENERATOR_CELL.get()
            )
        }
    )

    //#endregion

    //#region Lights

    val LIGHT_CELL = cell(
        "light",
        BasicCellProvider { ci ->
            LightCell(ci, directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)).also { cell ->
                cell.ruleSet.withDirectionRulePlanar(Base6Direction3dMask.LEFT + Base6Direction3dMask.RIGHT)
            }
        }
    )

    val LIGHT_PART = partAndItem(
        "light_part",
        BasicPartProvider(Vector3d(8.0 / 16.0, (1.0 + 2.302) / 16.0, 5.0 / 16.0)) { ci ->
            PoweredLightPart(ci, LIGHT_CELL.get())
        }
    )

    private fun registerLightBulbPR(
        name: String,
        powerRating: Quantity<Power>,
        resistance: Quantity<Resistance>,
        damageRate: Double,
        strength: Double,
        deviationMax: Double,
        increments: Int = 128,
        baseRadius: Int = 1
    ) : ItemRegistry.ItemRegistryItem {
        val itemEntry = item(name) {
            val model = LightModel(
                temperatureFunction = {
                    it.power / !powerRating
                },
                resistanceFunction = {
                    resistance
                },
                damageFunction = { v, dt ->
                    dt * (v.power / !powerRating) * damageRate
                },
                volumeProvider = LightFieldPrimitives.coneContentOnly(
                    increments,
                    strength,
                    deviationMax,
                    baseRadius
                )
            )

            LightBulbItem(model)
        }

        CreativeTabRegistry.creativeTabVariant {
            (itemEntry.get() as LightBulbItem).createStack(
                life = 1.0,
                count = 1
            )
        }

        return itemEntry
    }

    private fun registerLightBulbPP(
        name: String,
        powerRating: Quantity<Power>,
        potentialRating: Quantity<Potential>,
        damageRate: Double,
        strength: Double,
        deviationMax: Double,
        increments: Int = 128,
        baseRadius: Int = 1
    ) = registerLightBulbPR(
        name,
        powerRating,
        Quantity((!potentialRating).pow(2) / !powerRating, OHM),
        damageRate,
        strength,
        deviationMax,
        increments,
        baseRadius
    )

    val LIGHT_BULB_12V_100W = registerLightBulbPP(
        "light_bulb_12v_100w",
        powerRating = Quantity(100.0, WATT),
        potentialRating = Quantity(12.0, VOLT),
        damageRate = 1e-6,
        strength = 24.0,
        deviationMax = PI / 4.0
    )

    val LIGHT_BULB_800V_100W = registerLightBulbPP(
        "light_bulb_800v_100w",
        powerRating = Quantity(100.0, WATT),
        potentialRating = Quantity(800.0, VOLT),
        damageRate = 1e-6,
        strength = 24.0,
        deviationMax = PI / 4.0
    )


    private const val GARDEN_LIGHT_INITIAL_CHARGE = 0.5

    private fun gardenLightModel(strength: Double) = SolarLightModel(
        solarScan(Vector3d.unitY),
        dischargeRate = 1.0 / 12000.0 * 0.9,
        LightFieldPrimitives.sphere(1, strength)
    )

    private val SMALL_GARDEN_LIGHT_MODEL = gardenLightModel(3.0)

    val SMALL_GARDEN_LIGHT = partAndItem(
        "small_garden_light",
        BasicPartProvider(Vector3d(4.0 / 16.0, 6.0 / 16.0, 4.0 / 16.0)) { ci ->
            SolarLightPart(
                ci,
                SMALL_GARDEN_LIGHT_MODEL,
                { it.placement.face.vector3d },
                {
                    BasicPartRenderer(
                        it,
                        PartialModels.SMALL_GARDEN_LIGHT
                    )
                },
                BasicPartRenderer::class.java
            ).also { it.energy = GARDEN_LIGHT_INITIAL_CHARGE }
        }
    )

    private val TALL_GARDEN_LIGHT_MODEL = gardenLightModel(7.0)

    val TALL_GARDEN_LIGHT = partAndItem(
        "tall_garden_light",
        BasicPartProvider(Vector3d(3.0 / 16.0, 15.5 / 16.0, 3.0 / 16.0)) { ci ->
            SolarLightPart(
                ci,
                SMALL_GARDEN_LIGHT_MODEL,
                { it.placement.face.vector3d },
                {
                    LightFixtureRenderer(
                        it,
                        PartialModels.TALL_GARDEN_LIGHT_CAGE.cutout(),
                        PartialModels.TALL_GARDEN_LIGHT_EMITTER.solid()
                    )
                },
                LightFixtureRenderer::class.java
            ).also { it.energy = GARDEN_LIGHT_INITIAL_CHARGE }
        }
    )

    //#endregion

    //#region Heat Generator

    val HEAT_GENERATOR_CELL = cell(
        "heat_generator",
        BasicCellProvider.setup {
            val thermalDefinition = ThermalMassDefinition(
                Material(
                    label = "Test thermal conduit - heat generator",
                    electricalResistivity = Quantity(Double.POSITIVE_INFINITY),
                    thermalConductivity = Quantity(5000.0, WATT_PER_METER_KELVIN),
                    specificHeat = ChemicalElement.Copper.specificHeat,
                    density = ChemicalElement.Copper.density
                ),
                mass = Quantity(10.0, KILOGRAM)
            )

            val leakageParameters = ConnectionParameters.DEFAULT.copy(
                conductance = Quantity(0.01, WATT_PER_KELVIN)
            )

            CellFactory {
                HeatGeneratorCell(it, thermalDefinition, leakageParameters)
            }
        }
    )

    val HEAT_GENERATOR_BLOCK = blockAndItem("heat_generator") { HeatGeneratorBlock() }

    val HEAT_GENERATOR_BLOCK_ENTITY = blockEntityOnly(
        "heat_generator",
        HEAT_GENERATOR_BLOCK,
        ::HeatGeneratorBlockEntity
    )

    val HEAT_GENERATOR_MENU = menu("heat_generator", ::HeatGeneratorMenu)

    val ELECTRICAL_HEAT_ENGINE_CELL = cell(
        "electrical_heat_engine",
        BasicCellProvider.setup {
            // The electrical plus and minus:
            val electricalA = Base6Direction3d.Front
            val electricalB = Base6Direction3d.Back

            // The hot and cold side:
            val thermalA = Base6Direction3d.Left
            val thermalB = Base6Direction3d.Right

            val electricalMap = directionPoleMapPlanar(plusDir = electricalA, minusDir = electricalB)
            val thermalMap = directionPoleMapPlanar(plusDir = thermalA, minusDir = thermalB)

            val thermalDefinition = ThermalMassDefinition(
                ChemicalElement.Copper.asMaterial,
                mass = Quantity(5.0, KILOGRAM)
            )

            val leakage = ConnectionParameters(
                conductance = Quantity(0.1, WATT_PER_KELVIN)
            )

            val model = ElectricalHeatEngineModel(
                baseEfficiency = 1.0,
                potential = { ΔT ->
                    Quantity(!ΔT * (220.0 / 180.0), VOLT)
                },
                conductance = Quantity(5.0, WATT_PER_KELVIN)
            )

            val hemispheres = Direction.entries.associateWith {
                val volume = LightFieldPrimitives.hemisphereIncremental(
                    15 + 64,
                    3.0,
                    it,
                    1,
                ).volume

                RadiantBodyEmissionDescription({ volume })
            }

            CellFactory {
                val cell = ElectricalHeatEngineCell(
                    it,
                    electricalMap,
                    thermalMap,
                    thermalDefinition, thermalDefinition,
                    leakage, leakage,
                    model,
                    hemispheres[it.locator.transformPartWorld(Base6Direction3d.Left)]!!,
                    hemispheres[it.locator.transformPartWorld(Base6Direction3d.Right)]!!
                )

                cell.generator.ruleSet.withDirectionRulePlanar(electricalA + electricalB)
                cell.thermalBipole.ruleSet.withDirectionRulePlanar(thermalA + thermalB)

                cell
            }
        }
    )

    val ELECTRICAL_HEAT_ENGINE_PART = partAndItem(
        "electrical_heat_engine",
        BasicPartProvider(Vector3d(4.0 / 16.0, 15.0 / 16.0, 14.0 / 16.0)) {
            ElectricalHeatEnginePart(it)
        }
    )

    //#endregion

    //#region Furnaces

    val FURNACE_CELL = cell(
        "furnace_cell",
        BasicCellProvider {
            FurnaceCell(it, Base6Direction3d.Left, Base6Direction3d.Right)
        }
    )

    val FURNACE_BLOCK = blockAndItem("furnace") { FurnaceBlock() }

    val FURNACE_BLOCK_ENTITY = blockEntityOnly(
        "furnace",
        FURNACE_BLOCK.block,
        ::FurnaceBlockEntity
    )

    val FURNACE_MENU = menu("furnace_menu", ::FurnaceMenu)

    //#endregion

    //#region Grid

    val GRID_KNIFE = item("grid_knife") {
        GridKnifeItem()
    }

    val MICRO_GRID_CONNECT = item("microgrid_connect") {
        GridConnectItem(GridMaterials.COPPER_MICRO_GRID)
    }

    val POWER_GRID_CONNECT = item("power_grid_connect") {
        GridConnectItem(GridMaterials.COPPER_POWER_GRID)
    }

    val MICROGRID_ANCHOR_CELL = cell(
        "microgrid_anchor",
        BasicCellProvider {
            GridAnchorCell(
                it,
                !ChemicalElement.Copper.asMaterial.electricalResistivity.cylinderResistance(
                    L = Quantity(1.0, CENTIMETER),
                    A = Quantity(PI * Quantity(2.0, CENTIMETER).value.pow(2))
                )
            )
        }
    )

    val MICROGRID_ANCHOR_SPEC = specAndItem(
        "microgrid_anchor",
        BasicSpecProvider(PartialModels.MICRO_GRID_ANCHOR, Vector3d(4.0 / 16.0)) {
            GridAnchorSpec(
                it,
                Vector3d(2.0 / 16.0, 4.0 / 16.0, 2.0 / 16.0),
                listOf(GridMaterialCategory.MicroGrid)
            )
        }
    )

    val MICROGRID_INTERFACE_CELL = cell(
        "microgrid_interface",
        BasicCellProvider {
            GridInterfaceCell(
                it,
                !ChemicalElement.Copper.asMaterial.electricalResistivity.cylinderResistance(
                    L = Quantity(2.5, CENTIMETER),
                    A = Quantity(PI * Quantity(5.0, CENTIMETER).value.pow(2))
                ),
                !ChemicalElement.Copper.asMaterial.electricalResistivity.cylinderResistance(
                    L = Quantity(1.5, CENTIMETER),
                    A = Quantity(PI * Quantity(5.0, CENTIMETER).value.pow(2))
                )
            )
        }
    )

    val MICROGRID_INTERFACE_PART = partAndItem(
        "microgrid_interface",
        BasicPartProvider(Vector3d(4.0 / 16.0)) {
            GridInterfacePart(
                it,
                Vector3d(2.0 / 16.0, 4.0 / 16.0, 2.0 / 16.0),
                listOf(GridMaterialCategory.MicroGrid)
            ) { PartialModels.MICRO_GRID_INTERFACE }
        }
    )

    val POWER_GRID_INTERFACE_CELL = cell(
        "power_grid_interface",
        BasicCellProvider {
            GridInterfaceCell(
                it,
                !ChemicalElement.Copper.asMaterial.electricalResistivity.cylinderResistance(
                    L = Quantity(10.0, CENTIMETER),
                    A = Quantity(PI * Quantity(5.0, CENTIMETER).value.pow(2))
                ),
                !ChemicalElement.Copper.asMaterial.electricalResistivity.cylinderResistance(
                    L = Quantity(2.5, CENTIMETER),
                    A = Quantity(PI * Quantity(5.0, CENTIMETER).value.pow(2))
                )
            )
        }
    )

    val POWER_GRID_INTERFACE_PART = partAndItem(
        "power_grid_interface",
        BasicPartProvider(Vector3d(4.0 / 16.0, 8.0 / 16.0, 4.0 / 16.0)) {
            GridInterfacePart(
                it,
                Vector3d(4.0 / 16.0, 8.0 / 16.0, 4.0 / 16.0) * 1.01,
                listOf(GridMaterialCategory.BIG)
            ) { PartialModels.POWER_GRID_INTERFACE }
        }
    )

    val GRID_POLE_DELEGATE_MAP = defineDelegateMap("grid_pole") {
        val column = registerDelegateOf(
            AABB(
                0.35, 0.0, 0.35,
                0.65, 1.0, 0.65
            )
        )

        principal(0, 1, 0, column)
        principal(0, 2, 0, column)
    }

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

    val GRID_PASS_TROUGH_POLE_BLOCK_ENTITY = registerGridPole(
        "grid_pass_pole",
        GRID_POLE_DELEGATE_MAP,
        Vector3d(0.5, 2.5, 0.5),
        MICROGRID_ANCHOR_CELL
    )

    //#endregion

    fun clientSetup() {
        requireIsOnRenderThread()
        setupScreens()
        setupFlywheel()
        LOG.info("Content client work completed")
    }

    private fun setupScreens() {
        MenuScreens.register(FURNACE_MENU.get(), ::FurnaceScreen)
        MenuScreens.register(HEAT_GENERATOR_MENU.get(), ::HeatGeneratorScreen)

        LOG.info("Client screens completed")
    }

    private fun setupFlywheel() {
        listOf(GRID_PASS_TROUGH_POLE_BLOCK_ENTITY).forEach {
            InstancedRenderRegistry.configure(it.get())
                .alwaysSkipRender()
                .factory { manager, entity ->
                    TestBlockEntityInstance(manager, entity, PartialModels.POLE_TEMPORARY.solid()) { instance, renderer, _ ->
                        instance.translate(renderer.instancePosition).scale(1f, 3f, 1f)
                    }
                }.apply()
        }

        LOG.info("Client flywheel completed")
    }
}
