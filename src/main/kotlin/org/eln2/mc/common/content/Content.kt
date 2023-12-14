@file:Suppress("MemberVisibilityCanBePrivate", "PublicApiImplicitType", "PublicApiImplicitType", "unused", "LongLine")

package org.eln2.mc.common.content

import net.minecraft.client.gui.screens.MenuScreens
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.Vector3d
import org.ageseries.libage.mathematics.evaluate
import org.ageseries.libage.mathematics.lerp
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.Datasets
import org.eln2.mc.LOG
import org.eln2.mc.client.render.PartialModels
import org.eln2.mc.client.render.cutout
import org.eln2.mc.client.render.foundation.BasicPartRenderer
import org.eln2.mc.client.render.foundation.defaultRadiantBodyColor
import org.eln2.mc.client.render.solid
import org.eln2.mc.common.blocks.BlockRegistry.block
import org.eln2.mc.common.blocks.BlockRegistry.blockEntity
import org.eln2.mc.common.cells.CellRegistry.cell
import org.eln2.mc.common.cells.foundation.BasicCellProvider
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.cells.foundation.TemperatureExplosionBehaviorOptions
import org.eln2.mc.common.containers.ContainerRegistry.menu
import org.eln2.mc.common.items.ItemRegistry.item
import org.eln2.mc.common.parts.PartRegistry.part
import org.eln2.mc.common.parts.foundation.BasicPartProvider
import org.eln2.mc.data.*
import org.eln2.mc.mathematics.*
import org.eln2.mc.extensions.toVector3d
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

    val WRENCH = item("wrench") { WrenchItem() }

    //#region Wires

    val COPPER_THERMAL_WIRE = ThermalWireBuilder("thermal_wire_copper")
        .apply {
            damageOptions = TemperatureExplosionBehaviorOptions(
                temperatureThreshold = Quantity(1000.0, CELSIUS)
            )
        }
        .register()

    val ELECTRICAL_WIRE_COPPER = ElectricalWireBuilder("electrical_cable_copper")
        .apply {
            isIncandescent = false
        }
        .register()

    val VOLTAGE_SOURCE_CELL = cell(
        "voltage_source",
        BasicCellProvider(::VoltageSourceCell)
    )

    val VOLTAGE_SOURCE_PART = part(
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

    val GROUND_PART = part(
        "ground",
        BasicPartProvider(
            Vector3d(4.0 / 16.0, 4.0 / 16.0, 4.0 / 16.0),
            ::GroundPart
        )
    )

    val BATTERY_CELL_12V = cell(
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

    val BATTERY_PART_12V = part(
        "lead_acid_battery_12v",
        BasicPartProvider(Vector3d(6.0 / 16.0, 7.0 / 16.0, 10.0 / 16.0)) { ci ->
            BatteryPart(ci, BATTERY_CELL_12V.get()) { part ->
                BasicPartRenderer(part, PartialModels.BATTERY)
            }
        }
    )

    val RESISTOR_CELL = cell(
        "resistor",
        BasicCellProvider(::ResistorCell)
    )

    val RESISTOR_PART = part(
        "resistor",
        BasicPartProvider(
            Vector3d(3.5 / 16.0, 2.25 / 16.0, 5.0 / 16.0),
            ::ResistorPart
        )
    )

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
                replicatesExternalTemperature = true
            )

            CellFactory {
                ThermalWireCell(it, Double.POSITIVE_INFINITY, thermalProperties)
            }
        }
    )
    val THERMAL_RADIATOR_PART = part(
        "thermal_radiator",
        BasicPartProvider(Vector3d(1.0, 3.0 / 16.0, 1.0)) { ci ->
            RadiatorPart(ci, defaultRadiantBodyColor())
        }
    )

    val HEAT_GENERATOR_CELL = cell(
        "heat_generator",
        BasicCellProvider.setup {
            val thermalDefinition = ThermalMassDefinition(
                ChemicalElement.Copper.asMaterial,
                mass = Quantity(10.0, KILOGRAM)
            )

            CellFactory {
                HeatGeneratorCell(it, thermalDefinition)
            }
        }
    )
    val HEAT_GENERATOR_BLOCK = block("heat_generator", tab = null) {
        HeatGeneratorBlock()
    }

    val HEAT_GENERATOR_BLOCK_ENTITY = blockEntity(
        "heat_generator",
        ::HeatGeneratorBlockEntity
    ) { HEAT_GENERATOR_BLOCK.block.get() }

    val HEAT_GENERATOR_MENU = menu(
        "heat_generator",
        ::HeatGeneratorMenu
    )

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
                    cell.locator.requireLocator<FaceLocator>().toVector3d()
                }
            }
        }
    )

    val PHOTOVOLTAIC_PANEL_PART = part(
        "photovoltaic_panel",
        BasicPartProvider(Vector3d(1.0, 2.0 / 16.0, 1.0)) { ci ->
            PhotovoltaicPanelPart(
                ci,
                PHOTOVOLTAIC_GENERATOR_CELL.get()
            )
        }
    )

    val LIGHT_CELL = cell(
        "light",
        BasicCellProvider { ci ->
            LightCell(ci, directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)).also { cell ->
                cell.ruleSet.withDirectionRulePlanar(Base6Direction3dMask.LEFT + Base6Direction3dMask.RIGHT)
            }
        }
    )

    val LIGHT_PART = part(
        "light_part",
        BasicPartProvider(Vector3d(8.0 / 16.0, (1.0 + 2.302) / 16.0, 5.0 / 16.0)) { ci ->
            PoweredLightPart(ci, LIGHT_CELL.get())
        }
    )

    val LIGHT_BULB_12V_100W = item("light_bulb_12v_100w") {
        LightBulbItem(
            LightModel(
                temperatureFunction = {
                    it.power / 100.0
                },
                resistanceFunction = {
                    Quantity(1.44, OHM)
                },
                damageFunction = { v, dt ->
                    dt * (v.power / 100.0) * 1e-6
                },
                volumeProvider = LightFieldPrimitives.cone(
                    32,
                    24.0,
                    PI / 4.0,
                    1
                )
            )
        )
    }

    val GRID_CELL = cell(
        "grid",
        BasicCellProvider { GridCell(it) })

    val GRID_TAP_PART = part(
        "grid_tap",
        BasicPartProvider(Vector3d(4.0 / 16.0, 0.5, 4.0 / 16.0)) { ci ->
            GridTapPart(ci, GRID_CELL.get())
        }
    )

    val GRID_CONNECT_COPPER = item("grid_connect_copper") {
        GridConnectItem(GridMaterials.COPPER_AS_COPPER_COPPER)
    }

    private const val GARDEN_LIGHT_INITIAL_CHARGE = 0.5

    private fun gardenLightModel(strength: Double) = SolarLightModel(
        solarScan(Vector3d.unitY),
        dischargeRate = 1.0 / 12000.0 * 0.9,
        LightFieldPrimitives.sphere(1, strength)
    )

    private val SMALL_GARDEN_LIGHT_MODEL = gardenLightModel(3.0)

    val SMALL_GARDEN_LIGHT = part(
        "small_garden_light",
        BasicPartProvider(Vector3d(4.0 / 16.0, 6.0 / 16.0, 4.0 / 16.0)) { ci ->
            SolarLightPart(
                ci,
                SMALL_GARDEN_LIGHT_MODEL,
                { it.placement.face.toVector3d() },
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

    val TALL_GARDEN_LIGHT = part(
        "tall_garden_light",
        BasicPartProvider(Vector3d(3.0 / 16.0, 15.5 / 16.0, 3.0 / 16.0)) { ci ->
            SolarLightPart(
                ci,
                SMALL_GARDEN_LIGHT_MODEL,
                { it.placement.face.toVector3d() },
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

    val HEAT_ENGINE_ELECTRICAL_CELL = cell(
        "heat_engine_electrical",
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

            val model = HeatEngineElectricalModel(
                efficiency = 0.9,
                power = Quantity(5.0, KILO * WATT),
                desiredPotential = Quantity(220.0, VOLT)
            )

            CellFactory {
                val cell = HeatEngineElectricalCell(
                    it,
                    electricalMap,
                    thermalMap,
                    thermalDefinition,
                    model
                )

                cell.generator.ruleSet.withDirectionRulePlanar(electricalA + electricalB)
                cell.thermalBipole.ruleSet.withDirectionRulePlanar(thermalA + thermalB)

                cell
            }
        }
    )

    val HEAT_ENGINE_ELECTRICAL_PART = part(
        "heat_engine_electrical",
        BasicPartProvider(Vector3d(0.5, 15.0 / 16.0, 0.5)) {
            HeatEngineElectricalPart(it)
        })

    fun clientWork() {
        MenuScreens.register(HEAT_GENERATOR_MENU.get(), ::HeatGeneratorScreen)
        LOG.info("Content client work completed")
    }
}
