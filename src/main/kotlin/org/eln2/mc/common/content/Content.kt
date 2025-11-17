@file:Suppress("MemberVisibilityCanBePrivate", "PublicApiImplicitType", "PublicApiImplicitType", "unused", "LongLine",
    "NonAsciiCharacters", "LocalVariableName"
)

package org.eln2.mc.common.content

import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.phys.AABB
import net.minecraftforge.registries.RegistryObject
import org.ageseries.libage.data.AMPERE
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.FARAD
import org.ageseries.libage.data.G_PER_CM3
import org.ageseries.libage.data.HENRY
import org.ageseries.libage.data.KILO
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.KILOGRAM_METER2
import org.ageseries.libage.data.METER
import org.ageseries.libage.data.METER2
import org.ageseries.libage.data.MILLI
import org.ageseries.libage.data.NEWTON_METER
import org.ageseries.libage.data.NEWTON_METER_PER_AMPERE
import org.ageseries.libage.data.NEWTON_METER_SECOND
import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.OHM_METER
import org.ageseries.libage.data.Potential
import org.ageseries.libage.data.Power
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.REVOLUTION_PER_SECOND
import org.ageseries.libage.data.Resistance
import org.ageseries.libage.data.VOLT
import org.ageseries.libage.data.VOLT_PER_RADIAN_PER_SECOND
import org.ageseries.libage.data.WATT
import org.ageseries.libage.data.WATT_HOUR
import org.ageseries.libage.data.WATT_PER_KELVIN
import org.ageseries.libage.data.WATT_PER_METER_KELVIN
import org.ageseries.libage.data.requireLocator
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.mathematics.geometry.Vector4d
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.Material
import org.ageseries.libage.sim.Pole
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.DEBUGGER_BREAK
import org.eln2.mc.FrictionNodeDescription
import org.eln2.mc.LOG
import org.eln2.mc.client.render.DebugVisualizer
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.client.render.foundation.ThermalTint
import org.eln2.mc.common.LightBulbItem
import org.eln2.mc.common.LightFieldPrimitives
import org.eln2.mc.common.LightModel
import org.eln2.mc.common.LightVariantType
import org.eln2.mc.common.blocks.BlockRegistry.blockAndItem
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockItemOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockOnly
import org.eln2.mc.common.blocks.BlockRegistry.defineDelegateMap
import org.eln2.mc.common.blocks.foundation.BigBlockItem
import org.eln2.mc.common.blocks.foundation.MultiblockDelegateMap
import org.eln2.mc.common.cells.CellRegistry.cellImmediate
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.*
import org.eln2.mc.common.containers.ContainerRegistry.menu
import org.eln2.mc.common.content.OscilloscopePart.OscilloscopeScreen
import org.eln2.mc.common.grids.GridCablePliersItem
import org.eln2.mc.common.grids.GridMaterial
import org.eln2.mc.common.grids.GridMaterialCategory
import org.eln2.mc.common.grids.GridMaterials
import org.eln2.mc.common.items.CreativeTabRegistry
import org.eln2.mc.common.items.ItemRegistry.item
import org.eln2.mc.common.items.ItemRegistry.itemDefault
import org.eln2.mc.common.parts.PartRegistry.partAndItemWithProvider
import org.eln2.mc.common.parts.PartRegistry.partImmediateBB
import org.eln2.mc.common.parts.PartRegistry.partMemoizeBB
import org.eln2.mc.common.parts.foundation.BasicPartProvider
import org.eln2.mc.common.parts.foundation.PartFactory
import org.eln2.mc.common.parts.foundation.eln2ReadPartGuiData
import org.eln2.mc.common.parts.foundation.transformPartWorld
import org.eln2.mc.common.recipes.*
import org.eln2.mc.common.recipes.RecipeRegistry.registerCatalyzedRecipe
import org.eln2.mc.common.recipes.RecipeRegistry.registerDirectRecipe
import org.eln2.mc.common.sounds.SoundRegistry.soundEventVariableRange
import org.eln2.mc.common.specs.SpecRegistry.specImmediateBB
import org.eln2.mc.common.specs.SpecRegistry.specMemoizeBB
import org.eln2.mc.common.specs.foundation.SpecFactory
import org.eln2.mc.data.*
import org.eln2.mc.extensions.cast
import org.eln2.mc.extensions.toVector3d
import org.eln2.mc.extensions.vector3d
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask
import org.eln2.mc.mathematics.maskXY
import org.eln2.mc.requireIsOnRenderThread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow

/**
 * Joint registry for content classes.
 */
object Content {
    /**
     * Initializes the fields, in order to register the content.
     */
    fun initialize() { }

    fun clientSetup() {
        requireIsOnRenderThread()
        setupScreens()
        LOG.info("Content client work completed")
    }

    private fun setupScreens() {
        MenuScreens.register(FURNACE_MENU.get(), ::FurnaceScreen)
        MenuScreens.register(HEAT_GENERATOR_MENU.get(), ::HeatGeneratorScreen)
        MenuScreens.register(FLAT_OSCILLOSCOPE_MENU.get(), ::OscilloscopeScreen)
        MenuScreens.register(CRUSHER_MENU.get(), ::CrusherScreen)
        MenuScreens.register(EXTRUDER_MENU.get(), ::ExtruderScreen)

        LOG.info("Client screens completed.")
    }

    //#region Tools

    val WRENCH = item("wrench") { WrenchItem() }

    val SCREWDRIVER = item("screwdriver") { ScrewdriverItem() }

    //#endregion

    //#region Intermediary Items

    //#region Ingredients

    val CRUSHED_IRON_ORE = itemDefault("crushed_iron_ore")
    val CRUSHED_COPPER_ORE = itemDefault("crushed_copper_ore")
    val CRUSHED_GOLD_ORE = itemDefault("crushed_gold_ore")

    val HOT_COPPER_INGOT = itemDefault("hot_copper_ingot")

    val EXTRUDER_ROD_DIE = itemDefault("extruder_rod_die")

    val COPPER_ROD = itemDefault("copper_rod")

    //#endregion

    //#endregion

    //#region Wires

    private val UNINSULATED_WIRE_LIGHT_FIELD = LightFieldPrimitives.sourceOnlyStart(15)

    val STANDARD_UNINSULATED_COPPER_THERMAL_WIRE = ThermalWireBuilder("standard_uninsulated_copper_thermal_wire").applyAndRegister {
        temperatureThreshold = Quantity(1000.0, CELSIUS)

        material = ThermalMassDefinition(
            ChemicalElement.Copper.asMaterial.copy(
                label = "Copper Thermal Conductor",
                thermalConductivity = Quantity(3500.0, WATT_PER_METER_KELVIN),
            )
        )

        leakageParameters = ConnectionParameters.DEFAULT.copy(
            conductance = Quantity(0.05, WATT_PER_KELVIN)
        )

        radiantDescription = RadiantBodyEmissionDescription(
            UNINSULATED_WIRE_LIGHT_FIELD
        )

        renderer {
            WireRenderModel(
                FlwModels.UNINSULATED_THERMAL_WIRE_HUB,
                FlwModels.UNINSULATED_THERMAL_WIRE_CONNECTION,
                ThermalTint.DEFAULT
            )
        }
    }

    val STANDARD_INSULATED_COPPER_ELECTRICAL_WIRE = ElectricalWireBuilder("standard_insulated_copper_electrical_wire").applyAndRegister {
        isIncandescent = false

        temperatureThreshold = Quantity(150.0, CELSIUS)

        material = ThermalMassDefinition(
            ChemicalElement.Copper.asMaterial.copy(
                label = "Copper Electrical Conductor",
                thermalConductivity = Quantity(3500.0, WATT_PER_METER_KELVIN),
            )
        )

        leakageParameters = ConnectionParameters.DEFAULT.copy(
            conductance = Quantity(0.01, WATT_PER_KELVIN) // Insulation
        )

        breakdownPotential = 800.0

        renderer {
            WireRenderModel(
                FlwModels.ELECTRICAL_WIRE_HUB,
                FlwModels.ELECTRICAL_WIRE_CONNECTION
            )
        }
    }

    val SIGNAL_WIRE = ElectricalWireBuilder("signal_wire").applyAndRegister {
        isIncandescent = false

        temperatureThreshold = Quantity(133.0, CELSIUS)
        material = ThermalMassDefinition(ChemicalElement.Copper.asMaterial)
        leakageParameters = ConnectionParameters.DEFAULT.copy(conductance = Quantity(0.001, WATT_PER_KELVIN))
        breakdownPotential = 100.0

        size = ElectricalSize.Signal
        hubSize = Vector3d(1.5, 0.625, 1.5) / 16.0
        connectionSize = Vector3d(0.6, 0.4, 7.25) / 16.0

        renderer {
            WireRenderModel(
                FlwModels.SIGNAL_WIRE_HUB,
                FlwModels.SIGNAL_WIRE_CONNECTION
            )
        }
    }

    val THERMAL_RADIATOR_CELL = cellMemoize("thermal_radiator") {
        val thermalProperties = WireThermalProperties(
            ThermalMassDefinition(
                ChemicalElement.Copper.asMaterial,
                mass = Quantity(50.0, KILOGRAM)
            ),
            Quantity(900.0, CELSIUS),
            replicatesInternalTemperature = true,
            replicatesExternalTemperature = true,
            null, // TODO maybe it does radiate?
            leakageParameters = ConnectionParameters(
                area = 5.0
            )
        )

        CellFactory {
            ThermalWireCell(
                it,
                Double.POSITIVE_INFINITY,
                ThermalSize.Any,
                thermalProperties
            )
        }
    }

    val THERMAL_RADIATOR_PART = partImmediateBB("thermal_radiator", 16.0, 3.0, 16.0, ::RadiatorPart)

    //#endregion

    //#region Joints

    val JOINT_SOUND = soundEventVariableRange("shaft")

    val STANDARD_IRON_DOUBLE_JOINT_CELL_PLUS = Base6Direction3d.Front
    val STANDARD_IRON_DOUBLE_JOINT_CELL_MINUS = Base6Direction3d.Back

    val STANDARD_IRON_DOUBLE_JOINT_CELL = cellMemoize("standard_iron_double_joint") {
        val thermal = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(60.25, KILOGRAM)
        )

        val map = directionPoleMapPlanar(
            STANDARD_IRON_DOUBLE_JOINT_CELL_PLUS,
            STANDARD_IRON_DOUBLE_JOINT_CELL_MINUS
        )

        val friction = FrictionNodeDescription(
            Quantity(0.0770, KILOGRAM_METER2),
            0.01,
            Quantity(1e-5, NEWTON_METER),
            Quantity(0.1, NEWTON_METER)
        )

        CellFactory {
            DoubleJointCell(it,
                thermal,
                map,
                friction,
                1.0,
                Quantity(120.0, REVOLUTION_PER_SECOND),
                Quantity(750.0, NEWTON_METER)
            )
        }
    }

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_PLUS = Base6Direction3d.Front
    val STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_MINUS = Base6Direction3d.Left

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL = cellMemoize("standard_iron_double_joint_90deg") {
        val thermal = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(60.25, KILOGRAM)
        )

        val map = directionPoleMapPlanar(
            STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_PLUS,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_MINUS
        )

        val friction = FrictionNodeDescription(
            Quantity(0.11025, KILOGRAM_METER2),
            0.0125,
            Quantity(1e-5, NEWTON_METER),
            Quantity(0.1, NEWTON_METER)
        )

        CellFactory {
            DoubleJointCell(it,
                thermal,
                map,
                friction,
                1.0,
                Quantity(115.0, REVOLUTION_PER_SECOND),
                Quantity(679.0, NEWTON_METER)
            )
        }
    }

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_PLUS = Base6Direction3d.Front
    val STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_MINUS = Base6Direction3d.Left


    val STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL = cellMemoize("standard_iron_double_joint_90deg_2x") {
        val thermal = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(60.25, KILOGRAM)
        )

        val map = directionPoleMapPlanar(
            STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_PLUS,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_MINUS
        )

        val friction = FrictionNodeDescription(
            Quantity(0.11025, KILOGRAM_METER2),
            0.01567,
            Quantity(1e-5, NEWTON_METER),
            Quantity(0.1, NEWTON_METER)
        )

        CellFactory {
            DoubleJointCell(it,
                thermal,
                map,
                friction,
                2.0,
                Quantity(114.14, REVOLUTION_PER_SECOND),
                Quantity(658.435, NEWTON_METER)
            )
        }
    }

    val STANDARD_IRON_TRIPLE_JOINT_CELL_E1 = Base6Direction3d.Front
    val STANDARD_IRON_TRIPLE_JOINT_CELL_E2 = Base6Direction3d.Left
    val STANDARD_IRON_TRIPLE_JOINT_CELL_E3 = Base6Direction3d.Right

    val STANDARD_IRON_TRIPLE_JOINT_CELL = cellMemoize("standard_iron_triple_joint") {
        val thermal = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(90.25, KILOGRAM)
        )

        val map1 = monopolarMapPlanar(STANDARD_IRON_TRIPLE_JOINT_CELL_E1)
        val map2 = monopolarMapPlanar(STANDARD_IRON_TRIPLE_JOINT_CELL_E2)
        val map3 = monopolarMapPlanar(STANDARD_IRON_TRIPLE_JOINT_CELL_E3)

        val friction = FrictionNodeDescription(
            Quantity(0.13025, KILOGRAM_METER2),
            0.02,
            Quantity(1e-5, NEWTON_METER),
            Quantity(0.1, NEWTON_METER)
        )

        CellFactory {
            TripleJointCell(it,
                thermal,
                map1,
                map2,
                map3,
                friction,
                Quantity(100.0, REVOLUTION_PER_SECOND),
                Quantity(700.0, NEWTON_METER)
            )
        }
    }

    val STANDARD_IRON_DOUBLE_JOINT_PART = partImmediateBB("standard_iron_double_joint", 6.0, 10.0, 16.0) {
        JointPart(
            it,
            STANDARD_IRON_DOUBLE_JOINT_CELL,
            STANDARD_IRON_DOUBLE_JOINT_CELL_PLUS + STANDARD_IRON_DOUBLE_JOINT_CELL_MINUS
        )
    }

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_PART = partImmediateBB("standard_iron_double_joint_90deg", 16.0, 10.0, 16.0) {
        JointPart(
            it,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_PLUS + STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_MINUS
        )
    }

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_PART = partImmediateBB("standard_iron_double_joint_90deg_2x", 16.0, 10.0, 16.0) {
        JointPart(
            it,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_PLUS + STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_MINUS)
    }

    val STANDARD_IRON_TRIPLE_T_JOINT_PART = partImmediateBB("standard_iron_triple_t_joint", 16.0, 10.0, 16.0) {
        JointPart(
            it,
            STANDARD_IRON_TRIPLE_JOINT_CELL,
            STANDARD_IRON_TRIPLE_JOINT_CELL_E1 + STANDARD_IRON_TRIPLE_JOINT_CELL_E2 + STANDARD_IRON_TRIPLE_JOINT_CELL_E3
        )
    }

    //#endregion

    //#region Creative Components

    val VOLTAGE_SOURCE_CELL = cellMemoize("voltage_source") {
        val map = monopolarMapPlanar(Base6Direction3d.Front)
        val size = ElectricalSize.Standard

        CellFactory {
            VoltageSourceCell(it, map, size)
        }
    }

    val VOLTAGE_SOURCE_PART = partImmediateBB("voltage_source", 6.0, 2.5, 6.0, ::VoltageSourcePart)

    val GROUND_CELL = cellImmediate("ground", ::GroundCell)

    val GROUND_PART = partImmediateBB("ground", 4.0, 4.0, 4.0, ::GroundPart)

    val GROUND_SPEC = specImmediateBB(
        "ground_micro_grid",
        FlwModels.GROUND_MICRO_GRID,
        2.0, 2.0, 2.0,
        ::GroundSpec
    )

    //#endregion

    //#region Batteries

    val LEAD_ACID_BATTERY_CELL_12V_840Wh = cellMemoize("lead_acid_battery_12v_840wh") {
        val model = BatteryModels.leadAcid12v(
            Quantity(840.0, WATT_HOUR),
            Quantity(23.0, MILLI * OHM),
            Quantity(12.0, KILOGRAM),
            Quantity(0.1, METER2),
            1e-7,
            Quantity(100.0, AMPERE)
        )

        val plusDir = Base6Direction3d.Front
        val minusDir = Base6Direction3d.Back

        CellFactory {
            val cell = PolarBatteryCell(it, model, directionPoleMapPlanar(plusDir, minusDir), ElectricalSize.Standard)
            cell.energy = cell.model.energyCapacity * 0.9
            cell
        }
    }

    val GRID_LEAD_ACID_BATTERY_CELL_12V_80Wh = cellMemoize("lead_acid_battery_12v_80wh") {
        val model = BatteryModels.leadAcid12v(
            Quantity(80.0, WATT_HOUR),
            Quantity(26.0, MILLI * OHM),
            Quantity(2.6, KILOGRAM),
            Quantity(0.0006, METER2),
            1e-6,
            Quantity(10.0, AMPERE)
        )

        CellFactory {
            val cell = TerminalBatteryCell(it, model)
            cell.energy = cell.model.energyCapacity * 0.9
            cell
        }
    }

    val BATTERY_PART_12V = partImmediateBB("lead_acid_battery_12v", 6.0, 7.0, 10.0) {
        BatteryPart(it, LEAD_ACID_BATTERY_CELL_12V_840Wh.get())
    }

    val BATTERY_SPEC_12V = specImmediateBB("micro_grid_lead_acid_battery_12v", FlwModels.SPEC_LEAD_ACID_BATTERY, 1.5, 1.85, 3.0) {
        BatterySpec(it, GRID_LEAD_ACID_BATTERY_CELL_12V_80Wh.get(),
            7.5125, 1.6938, 6.6875, 0.15, 0.15, 0.225,
            8.3375,1.6938,6.6875, 0.15, 0.15,0.225
        )
    }

    //#endregion

    //#region Basic Electrical Components

    val RESISTOR_CELL = cellImmediate("resistor", ::ResistorCell)

    val RESISTOR_PART = partImmediateBB("resistor", 3.5, 2.25, 5.0, ::ResistorPart)

    //#endregion

    //#region Photovoltaics

    val PHOTOVOLTAIC_GENERATOR_CELL = cellMemoize("photovoltaic_generator") {
        val map = directionPoleMapPlanar()

        val model = PhotovoltaicModel(
            Quantity(32.0, VOLT),
            0.452515661,
            Quantity(1.0, METER2),
            Quantity(0.01, OHM),
            Quantity(0.025),
            Quantity(261.1519, OHM)
        )

        val surface = Quantity(1.0, METER2)

        CellFactory {
            PhotovoltaicGeneratorCell(it, map, model) { cell ->
                cell.locator.requireLocator(Locators.SUBSTRATE_FACE).vector3d
            }
        }
    }

    val PHOTOVOLTAIC_PANEL_PART = partImmediateBB("photovoltaic_panel", 16.0, 2.0, 16.0) {
        PhotovoltaicPanelPart(it, PHOTOVOLTAIC_GENERATOR_CELL.get())
    }

    //#endregion

    //#region Lights

    val POLAR_LIGHT_CELL_CONE_45DEG = cellImmediate("polar_light_45deg") {
        PolarLightCell(
            it,
            directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right),
            LightVariantType.Cone45Deg,
            ElectricalSize.Standard
        )
    }

    val POLAR_LIGHT_CELL_CONE_SPHERE = cellImmediate("polar_light_sphere") {
        PolarLightCell(
            it,
            directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right),
            LightVariantType.Sphere,
            ElectricalSize.Standard
        )
    }

    val TERMINAL_LIGHT_CELL_45DEG = cellImmediate("terminal_light_45deg") {
        TerminalLightCell(it, LightVariantType.Cone45Deg)
    }

    val LIGHT_PART = partImmediateBB("small_wall_lamp", 4.0, 1.0 + 2.302, 5.0) {
        PolarPoweredLightPart(it, POLAR_LIGHT_CELL_CONE_45DEG.get())
    }

    val LIGHT_PART_MICRO_GRID = partAndItemWithProvider(
        "small_wall_lamp_micro_grid",
        BasicPartProvider.setup(Vector3d(8.0 / 16.0, (1.0 + 2.302) / 16.0, 5.0 / 16.0)) {
            val size = Vector3d(0.5 / 16.0, 2.0 / 16.0, 1.0 / 16.0)
            val neg = BoundingBox3d.fromCenterSize((Vector3d(3.75 / 16.0, 0.0, 7.5 / 16.0)) - Vector3d.one * maskXY / 2.0 + size * maskXY / 2.0, size)
            val pos = BoundingBox3d.fromCenterSize((Vector3d(11.75 / 16.0, 0.0, 7.5 / 16.0)) - Vector3d.one * maskXY / 2.0 + size * maskXY / 2.0, size)

            PartFactory { ci ->
                TerminalPoweredLightPart(
                    ci,
                    TERMINAL_LIGHT_CELL_45DEG.get(),
                    neg, pos
                )
            }
        }
    )

    private fun registerLightBulbPR(
        name: String,
        powerRating: Quantity<Power>,
        resistance: Quantity<Resistance>,
        damageRate: Double,
        strength: Double,
        increments: Int = 128,
        baseRadius: Int = 1
    ) : RegistryObject<LightBulbItem> {
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
                volumeProvider45Deg = LightFieldPrimitives.coneContentOnly(
                    increments,
                    strength,
                    PI / 4.0,
                    baseRadius
                ),
                volumeProviderSphere = LightFieldPrimitives.sphere(
                    increments,
                    strength,
                    baseRadius.toDouble()
                )
            )

            LightBulbItem(model)
        }

        CreativeTabRegistry.creativeTabVariant {
            itemEntry.get().createStack(
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
        increments: Int = 128,
        baseRadius: Int = 1
    ) = registerLightBulbPR(
        name,
        powerRating,
        Quantity((!potentialRating).pow(2) / !powerRating, OHM),
        damageRate,
        strength,
        increments,
        baseRadius
    )

    val LIGHT_BULB_12V_100W = registerLightBulbPP(
        "light_bulb_12v_100w",
        powerRating = Quantity(100.0, WATT),
        potentialRating = Quantity(12.0, VOLT),
        damageRate = 1e-6,
        strength = 24.0
    )

    val LIGHT_BULB_800V_100W = registerLightBulbPP(
        "light_bulb_800v_100w",
        powerRating = Quantity(100.0, WATT),
        potentialRating = Quantity(800.0, VOLT),
        damageRate = 1e-6,
        strength = 24.0
    )

    private fun gardenLightModel(strength: Double) = SolarLightModel(
        solarScan(Vector3d.unitY),
        dischargeRate = 1.0 / 12000.0 * 0.9,
        LightFieldPrimitives.sphere(1, strength)
    )

    val SMALL_GARDEN_LIGHT = partMemoizeBB("small_garden_light", 4.0, 6.0, 4.0) {
        val model = gardenLightModel(3.0)

        PartFactory {
            SolarLightPart(it, model)
        }
    }

    val TALL_GARDEN_LIGHT = partMemoizeBB("tall_garden_light", 3.0, 15.5, 3.0) {
        val model = gardenLightModel(5.0)

        PartFactory {
            SolarLightPart(it, model)
        }
    }

    val LAMP_POLE_BLOCK_DELEGATE_MAP = defineDelegateMap("lamp_pole") {
        val column = registerDelegateOf(
            AABB(
                0.325, 0.0, 0.325,
                0.675, 1.0, 0.675
            )
        )

        val lamp = registerDelegateOf(
            AABB(
                0.25, 0.0, 0.25,
                0.75, 0.65, 0.75
            )
        )

        principal(0, 2, 0, column)
        principal(0, 3, 0, column)
        principal(0, 4, 0, column)
        principal(0, 5, 0, lamp)
    }

    val LAMP_POLE_BLOCK = blockOnly("lamp_pole") {
        LampPoleBlock(POLAR_LIGHT_CELL_CONE_SPHERE, BlockPos(0, 5, 0))
    }

    val LAMP_POLE_BLOCK_ENTITY = blockEntityOnly("lamp_pole", LAMP_POLE_BLOCK) { pos, state ->
        LampPoleBlockEntity(pos, state)
    }

    val LAMP_POLE_BLOCK_ITEM = blockItemOnly("lamp_pole") {
        BigBlockItem(
            LAMP_POLE_BLOCK_DELEGATE_MAP.value,
            LAMP_POLE_BLOCK.get()
        )
    }

    //#endregion

    //#region Heat Generator

    val HEAT_GENERATOR_CELL = cellMemoize("heat_generator") {
        val thermalDefinition = ThermalMassDefinition(
            Material(
                label = "Heat generator",
                electricalResistivity = Quantity(Double.POSITIVE_INFINITY),
                thermalConductivity = Quantity(5000.0, WATT_PER_METER_KELVIN),
                specificHeat = ChemicalElement.Copper.specificHeat,
                density = ChemicalElement.Copper.density
            ),
            mass = Quantity(10.0, KILOGRAM)
        )

        val leakageParameters = ConnectionParameters.DEFAULT.copy(
            conductance = Quantity(0.001, WATT_PER_KELVIN)
        )

        CellFactory {
            HeatGeneratorCell(it, thermalDefinition, leakageParameters)
        }
    }

    val HEAT_GENERATOR_BLOCK = blockAndItem("heat_generator") { HeatGeneratorBlock() }

    val HEAT_GENERATOR_BLOCK_ENTITY = blockEntityOnly("heat_generator", HEAT_GENERATOR_BLOCK, ::HeatGeneratorBlockEntity)

    val HEAT_GENERATOR_MENU = menu("heat_generator", ::HeatGeneratorMenu)

    //#endregion

    //#region Thermal-Electrical Generator

    val ELECTRICAL_HEAT_ENGINE_CELL = cellMemoize("electrical_heat_engine") {
        // The electrical plus and minus:
        val electricalA = Base6Direction3d.Left
        val electricalB = Base6Direction3d.Right

        // The hot side:
        val thermalA = Base6Direction3d.Front

        val electricalMap = directionPoleMapPlanar(
            plusDir = electricalA,
            minusDir = electricalB
        )

        val thermalMap = directionMonopolarMapPlanar(
            thermalA,
            Pole.Negative
        )

        val coldSideDefinition = ThermalMassDefinition(
            ChemicalElement.Copper.asMaterial,
            mass = Quantity(5.0, KILOGRAM)
        )

        val hotSideDefinition = ThermalMassDefinition(
            ChemicalElement.Copper.asMaterial,
            mass = Quantity(5.0, KILOGRAM)
        )

        // Radiator:
        val leakageCold = ConnectionParameters(
            conductance = Quantity(10.0, WATT_PER_KELVIN)
        )

        val leakageHot = ConnectionParameters(
            conductance = Quantity(0.1, WATT_PER_KELVIN)
        )

        val generatorModel = ThermalElectricGeneratorModel(
            Quantity(20.0, REVOLUTION_PER_SECOND),
            Quantity(120.0, VOLT),
            0.5,
            Quantity(10.0, WATT_PER_KELVIN),
            Quantity(0.05, WATT_PER_KELVIN),
            Quantity(1.0, KILOGRAM_METER2),
            Quantity(0.025, NEWTON_METER_SECOND),
            Quantity(5.0, NEWTON_METER),
            Quantity(2400.0, WATT),
            0.9,
            Quantity(2.5, WATT),
            0.01
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
            ElectricalHeatEngineCell(
                it,
                electricalMap,
                thermalMap,
                coldSideDefinition, hotSideDefinition,
                leakageCold, leakageHot,
                generatorModel,
                0.075,
                hemispheres[it.locator.transformPartWorld(Base6Direction3d.Front)]!!,
                hemispheres[it.locator.transformPartWorld(Base6Direction3d.Back)]!!,
                ElectricalSize.Standard,
                ThermalSize.Standard
            )
        }
    }

    val ELECTRICAL_HEAT_ENGINE_PART = partImmediateBB("electrical_heat_engine", 4.0, 10.0, 14.0, ::ElectricalHeatEnginePart)

    //#endregion

    //#region Power Converter

    val TERMINAL_DC_TO_DC_CONVERTER_CELL_800W = cellMemoize("terminal_dc_to_dc_converter_800w") {
        val thermalDef = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(1.0, KILOGRAM)
        )

        val environmentParameters = ConnectionParameters(
            conductance = Quantity(2.5)
        )

        val model = DcToDcConverterModel(
            Quantity(800.0, WATT),
            0.8,
            2.5,
            Quantity(800.0, VOLT),
            Quantity(0.0006571, OHM),
            Quantity(0.1027, FARAD),
            Quantity(0.0001167, OHM),
            Quantity(0.000891, OHM)
        )

        CellFactory {
            TerminalDcToDcConverterCell(it, thermalDef, environmentParameters, model)
        }
    }

    val DC_TO_DC_CONVERTER_SPEC = specImmediateBB(
        "micro_grid_dc_to_dc_converter_800w",
        FlwModels.SMALL_DC_TO_DC_CONVERTER,
        9.8, 2.625, 4.85,
        ::DcToDcConverterSpec
    )

    //#endregion

    //#region Furnaces

    val FURNACE_CELL = cellMemoize("furnace_cell") {
        val map = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)

        CellFactory {
            FurnaceCell(it, map)
        }
    }

    val FURNACE_BLOCK = blockAndItem("furnace") { FurnaceBlock() }

    val FURNACE_BLOCK_ENTITY = blockEntityOnly(
        "furnace",
        FURNACE_BLOCK.block,
        ::FurnaceBlockEntity
    )

    val FURNACE_MENU = menu("furnace_menu", ::FurnaceMenu)

    //#endregion

    //#region Grid

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
        val column = registerDelegateOf(
            AABB(
                0.35, 0.0, 0.35,
                0.65, 1.0, 0.65
            )
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

    //#endregion

    //#region Probes

    // All probe models should be made like this, and all cells use these directions.
    val STANDARD_PROBE_PLUS_DIRECTION = Base6Direction3d.Front
    val STANDARD_PROBE_MINUS_DIRECTION = Base6Direction3d.Back
    val STANDARD_PROBE_OUTPUT_DIRECTION = Base6Direction3d.Right

    val STANDARD_PROBE_COMPARER_MAP = directionPoleMapPlanar(STANDARD_PROBE_PLUS_DIRECTION, STANDARD_PROBE_MINUS_DIRECTION)
    val STANDARD_PROBE_OUTPUT_MAP = monopolarMapPlanar(STANDARD_PROBE_OUTPUT_DIRECTION)

    val STANDARD_PROBE_MODELS = mapOf(
        STANDARD_PROBE_PLUS_DIRECTION to FlwModels.STANDARD_CONNECTION,
        STANDARD_PROBE_MINUS_DIRECTION to FlwModels.STANDARD_CONNECTION,
        STANDARD_PROBE_OUTPUT_DIRECTION to FlwModels.SIGNAL_WIRE_CONNECTION.hub
    )

    val POTENTIAL_PROBE_CELL = cellImmediate("potential_probe") {
        PotentialProbeCell(
            it,
            STANDARD_PROBE_COMPARER_MAP,
            ElectricalSize.Standard,
            STANDARD_PROBE_OUTPUT_MAP
        )
    }

    val POTENTIAL_PROBE_PART = partImmediateBB("potential_probe", 6.0, 2.025, 9.5) {
        PotentialProbePart(it, STANDARD_PROBE_MODELS)
    }

    //#endregion

    //#region Oscilloscopes

    val BASIC_TWO_CHANNEL_OSCILLOSCOPE_SPECIFICATION = OscilloscopeSpecification(
        2,
        10,
        1000,
        OscilloscopePalette.DEFAULT,
        0.02f,
        0.0075f,
        Vector4d(0.8, 0.8, 1.0, 0.2),
        11,
        0.5f
    )

    val BASIC_TWO_CHANNEL_OSCILLOSCOPE_CELL = cellImmediate("basic_two_channel_oscilloscope") {
        OscilloscopeCell(it, nullMonopoleMap(), BASIC_TWO_CHANNEL_OSCILLOSCOPE_SPECIFICATION)
    }

    val FLAT_OSCILLOSCOPE_MENU = menu("basic_two_channel_oscilloscope_menu") { i, inv, buf ->
        OscilloscopePart.OscilloscopeMenu(i, buf.eln2ReadPartGuiData<OscilloscopePart>(inv))
    }

    val FLAT_OSCILLOSCOPE_PART = partMemoizeBB("basic_two_channel_oscilloscope", 15.2, 0.75, 10.0) {
        val generators = OscilloscopeChannelGenerators.create {
            withGridTerminal {
                defineCellBoxTerminalBB(
                    0.375, 0.1, 6.0,
                    0.625, 0.5, 0.5,
                    highlightColor = specification.palette.colorsInt[0],
                    categories = listOf(GridMaterialCategory.SignalGrid)
                )
            }

            withGridTerminal {
                defineCellBoxTerminalBB(
                    0.375, 0.1, 9.475,
                    0.625, 0.5, 0.5,
                    highlightColor = specification.palette.colorsInt[1],
                    categories = listOf(GridMaterialCategory.SignalGrid)
                )
            }
        }

        PartFactory {
            OscilloscopePart(it, BASIC_TWO_CHANNEL_OSCILLOSCOPE_SPECIFICATION, generators, BASIC_TWO_CHANNEL_OSCILLOSCOPE_CELL.get())
        }
    }

    val BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_SPECIFICATION = OscilloscopeSpecification(
        1,
        10,
        4096,
        OscilloscopePalette.DEFAULT,
        0.02f,
        0.0075f,
        Vector4d(0.8, 0.8, 1.0, 0.2),
        11,
        0.5f
    )

    val BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_CELL = cellImmediate("basic_single_channel_oscilloscope") {
        OscilloscopeCell(it, monopolarMapPlanar(Base6Direction3d.Left), BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_SPECIFICATION)
    }

    val BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_PART = partMemoizeBB("basic_single_channel_oscilloscope", 15.2, 0.75, 10.0) {
        val generators = OscilloscopeChannelGenerators.create {
            withGridTerminal {
                defineCellBoxTerminalBB(
                    0.0, 0.0, 7.75,
                    1.0, 0.4, 0.5,
                    highlightColor = specification.palette.colorsInt[0],
                    categories = listOf(GridMaterialCategory.SignalGrid)
                )
            }
        }

        PartFactory {
            OscilloscopePart(it, BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_SPECIFICATION, generators, BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_CELL.get())
        }
    }

    //#endregion

    //#region Crusher

    val CRUSHING_RECIPE = registerDirectRecipe("crushing")

    val BASIC_CRUSHER_CELL = cellMemoize("basic_crusher") {
        val options = MotorProcessingCellOptions(
            1.0,
            MotorProcessingCellElectricalOptions(
                Quantity(1.155, KILOGRAM_METER2),
                Quantity(10.0, KILO * OHM),
                Quantity(1.0, OHM),
                Quantity(1.25, MILLI * HENRY),
                Quantity(9.501, VOLT_PER_RADIAN_PER_SECOND),
                Quantity(2.05, NEWTON_METER_PER_AMPERE),
                50.0,
                0.124,
                3.0,
                Quantity(800.0, VOLT),
                Quantity(8155.1598, WATT)
            ),
            ProcessingCellThermalOptions(
                0.1,
                ThermalMassDefinition(
                    ChemicalElement.Iron.asMaterial,
                    mass = Quantity(5.0, KILOGRAM)
                ),
                ConnectionParameters(
                    Quantity(10.0, WATT_PER_KELVIN)
                ),
                Quantity(150.0, CELSIUS),
            )
        )

        val electricalMap = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)
        val thermalMap = directionPoleMapPlanar(Base6Direction3d.Back)

        CellFactory {
            MotorProcessingCell(it, options, electricalMap, thermalMap)
        }
    }

    val CRUSHER_BLOCK = blockAndItem("crusher", ::CrusherBlock)

    val CRUSHER_SOUND_ROCK = soundEventVariableRange("crusher.rock")

    val CRUSHER_BLOCK_ENTITY = blockEntityOnly("crusher", CRUSHER_BLOCK.block, ::CrusherBlockEntity)

    val CRUSHER_MENU = menu("crusher", ::CrusherMenu)

    //#endregion
    
    //#region Extruder

    val EXTRUDING_RECIPE = registerCatalyzedRecipe("extruding")

    val EXTRUDER_SOUND = soundEventVariableRange("extruder")

    val ELECTRIC_EXTRUDER_CELL = cellMemoize("electric_extruder") {
        val options = MotorProcessingCellOptions(
            1.0,
            MotorProcessingCellElectricalOptions(
                Quantity(1.155, KILOGRAM_METER2),
                Quantity(10.0, KILO * OHM),
                Quantity(41.561, OHM),
                Quantity(1.25, MILLI * HENRY),
                Quantity(2.06, VOLT_PER_RADIAN_PER_SECOND),
                Quantity(2.05, NEWTON_METER_PER_AMPERE),
                10.0,
                0.5,
                1.15,
                Quantity(800.0, VOLT),
                Quantity(8155.1598, WATT)
            ),
            null
        )

        val electricalMap = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)
        val thermalMap = nullPolarMap()

        CellFactory {
            MotorProcessingCell(it, options, electricalMap, thermalMap)
        }
    }

    val ELECTRIC_EXTRUDER_BLOCK = blockAndItem("electric_extruder", ::ElectricExtruderBlock)

    val ELECTRIC_EXTRUDER_BLOCK_ENTITY = blockEntityOnly("electric_extruder", ELECTRIC_EXTRUDER_BLOCK.block, ::ElectricExtruderBlockEntity)

    val KINETIC_EXTRUDER_CELL = cellMemoize("kinetic_extruder") {
        val inertia = Quantity(0.1251, KILOGRAM_METER2)

        val options = KineticProcessingCellOptions(
            1.0,
            KineticProcessingCellKineticOptions(
                FrictionNodeDescription(
                    inertia,
                    0.01,
                    Quantity(0.1, NEWTON_METER),
                    Quantity(1.0, NEWTON_METER)
                ),
                FrictionNodeDescription(
                    inertia,
                    10.0,
                    Quantity(0.5, NEWTON_METER),
                    Quantity(1.0, NEWTON_METER)
                ),
                Quantity(1.0, REVOLUTION_PER_SECOND),
                Quantity(25.0, REVOLUTION_PER_SECOND),
                Quantity(250.0, NEWTON_METER)
            ),
            null
        )

        val kineticMap = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)
        val thermalMap = nullPolarMap()

        CellFactory {
            KineticProcessingCell(it, options, kineticMap, thermalMap)
        }
    }

    val KINETIC_EXTRUDER_BLOCK = blockAndItem("kinetic_extruder", ::KineticExtruderBlock)

    val KINETIC_EXTRUDER_BLOCK_ENTITY = blockEntityOnly("kinetic_extruder", KINETIC_EXTRUDER_BLOCK.block, ::KineticExtruderBlockEntity)

    val EXTRUDER_MENU = menu("extruder", ::ExtruderMenu)

    //#endregion

    //#region Motors

    val MOTOR_KINETIC_SOUND = soundEventVariableRange("motor.kinetic")
    val MOTOR_ELECTROMAGNETIC_SOUND = soundEventVariableRange("motor.electromagnetic")

    val BASIC_DC_MOTOR_CELL_DIRECTION = Base6Direction3d.Front

    val BASIC_DC_MOTOR_CELL = cellMemoize("basic_dc_motor") {
        val electricalMap = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)
        val kineticMap = monopolarMapPlanar(BASIC_DC_MOTOR_CELL_DIRECTION)

        val electricalSize = ElectricalSize.Standard
        val kineticSize = KineticSize.Standard

        val model = DcMotorOptions(
            FrictionNodeDescription(
                Quantity(3.1278, KILOGRAM_METER2),
                0.01,
                Quantity(1.0),
                Quantity(0.1)
            ),
            1e-6,
            Quantity(0.0667, OHM),
            Quantity(1.25, MILLI * HENRY),
            Quantity(2.06, VOLT_PER_RADIAN_PER_SECOND),
            Quantity(2.05, NEWTON_METER_PER_AMPERE),
            Quantity(100.0, REVOLUTION_PER_SECOND),
            Quantity(781.691, VOLT),
            Quantity(173.25, CELSIUS)
        )

        val thermalDef = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(2.1451, KILOGRAM) // Mass of the wiring itself. Not the entire rotor.
        )

        val leakage = ConnectionParameters.DEFAULT

        CellFactory {
            DcMotorCell(it,
                electricalMap, kineticMap,
                electricalSize, kineticSize,
                model,
                thermalDef, leakage
            )
        }
    }

    val BASIC_DC_MOTOR_PART = partMemoizeBB("basic_dc_motor", 16.0, 13.0, 14.3) {
        val soundOptions = DcMotorSoundOptions(
            Quantity(10.0, REVOLUTION_PER_SECOND),
            Quantity(12.0, KILO * WATT)
        )

        PartFactory {
            DcMotorPart(
                it,
                soundOptions,
                BASIC_DC_MOTOR_CELL,
                Base6Direction3dMask.ofRelative(BASIC_DC_MOTOR_CELL_DIRECTION)
            )
        }
    }
    
    //#endregion

    //#region Diode

    val DIODE_CELL = cellMemoize("diode") {
        val poleMap = directionPoleMapPlanar(
            Base6Direction3d.Back,
            Base6Direction3d.Front
        )

        val model = DiodeOptions(
            Quantity(0.001, OHM),
            Quantity(10.0, KILO * OHM),
            ThermalMassDefinition(
                ChemicalElement.Iron.asMaterial,
                mass = Quantity(1.691, KILOGRAM)
            ),
            Quantity(148.61, CELSIUS),
            Quantity(800.0, VOLT)
        )

        val leakage = ConnectionParameters.DEFAULT

        CellFactory {
            DiodeCell(it, poleMap, model, leakage)
        }
    }

    val DIODE_PART = partImmediateBB("diode", 3.0, 2.275, 16.0) {
        DiodePart(it)
    }

    //#endregion

    //#region Wind Turbine

    val BASIC_WIND_TURBINE_CELL = cellMemoize("basic_wind_turbine") {
        val options = WindTurbineOptions(
            BoundingBox3d.fromCenterSize(
                Vector3d.zero,
                16.0
            ),
            BoundingBox3d.fromCenterSize(
                Vector3d.zero,
                Vector3d(36.0 / 16.0, 77.0 / 16.0, 36.0 / 16.0)
            ),
            FrictionNodeDescription(
                Quantity(12.5, KILOGRAM_METER2),
                1.251,
                Quantity(0.01),
                Quantity(0.1)
            ),
            Quantity(1.5, METER),
            0.89159015861095
        )

        CellFactory {
            WindTurbineCell(it, options)
        }

    }

    val BASIC_WIND_TURBINE_DELEGATE_MAP = defineDelegateMap("basic_wind_turbine") {
        val fullBlock = registerDelegateOf(
            AABB(
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0
            )
        )

        fun defineSlab(y: Int) {
            for(x in -1..1) {
                for(z in -1..1) {
                    principal(x, y, z, fullBlock)
                }
            }
        }

        defineSlab(1)
        defineSlab(2)
        defineSlab(3)
        defineSlab(4)
        defineSlab(5)
    }

    val BASIC_WIND_TURBINE_BLOCK = blockOnly("basic_wind_turbine") {
        WindTurbineBlock(
            BASIC_WIND_TURBINE_CELL,
            BASIC_WIND_TURBINE_DELEGATE_MAP.value,
            WindTurbine3dModel(
                FlwModels.BASIC_WIND_TURBINE_BASE,
                FlwModels.BASIC_WIND_TURBINE_ROTOR
            )
        )
    }
    
    val BASIC_WIND_TURBINE_BLOCK_ITEM = blockItemOnly("basic_wind_turbine") {
        BigBlockItem(
            BASIC_WIND_TURBINE_DELEGATE_MAP.value,
            BASIC_WIND_TURBINE_BLOCK.get()
        )
    }

    // Single block entity for all turbines (implement new turbines by adding only a new block)
    val WIND_TURBINE_BLOCK_ENTITY = blockEntityOnly(
        "wind_turbine",
        BASIC_WIND_TURBINE_BLOCK,
        ::WindTurbineBlockEntity
    )

    //#endregion
}
