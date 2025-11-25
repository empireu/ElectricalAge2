package org.eln2.mc.client.render

import dev.engine_room.flywheel.lib.model.baked.PartialModel
import net.minecraft.client.resources.model.BakedModel
import net.minecraftforge.server.ServerLifecycleHooks
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.eln2.mc.client.render.foundation.PolarModel
import org.eln2.mc.client.render.foundation.WireConnectionModel
import org.eln2.mc.client.render.foundation.WirePatchPolarModel
import org.eln2.mc.client.render.foundation.WirePatchType
import org.eln2.mc.common.content.WireConnectionModelPartial
import org.eln2.mc.data.Average3d
import org.eln2.mc.resource
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

object FlwModels {
    //#region Wires

    val ELECTRICAL_WIRE_HUB = partialBlock(
        "wire/electrical/standard/insulated/hub"
    )

    val ELECTRICAL_WIRE_CONNECTION = wireConnection(
        "wire/electrical/standard/insulated/connection_hub",
        "wire/electrical/standard/insulated/connection_full"
    )

    val SIGNAL_WIRE_HUB = partialBlock(
        "wire/electrical/signal/hub"
    )

    val SIGNAL_WIRE_CONNECTION = wireConnection(
        "wire/electrical/signal/connection_hub",
        "wire/electrical/signal/connection_full"
    )

    val UNINSULATED_THERMAL_WIRE_HUB = partialBlock(
        "wire/thermal/standard/uninsulated/copper/hub"
    )

    val UNINSULATED_THERMAL_WIRE_CONNECTION = wireConnection(
        "wire/thermal/standard/uninsulated/copper/connection_hub",
        "wire/thermal/standard/uninsulated/copper/connection_full"
    )

    //#endregion

    //#region Shafts

    val STANDARD_IRON_DOUBLE_JOINT_BODY = partialBlock("joint/double/standard/iron/body")
    val STANDARD_IRON_DOUBLE_JOINT_SHAFT = partialBlock("joint/double/standard/iron/shaft")

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_BODY = partialBlock("joint/double_90deg/standard/iron/body")
    val STANDARD_IRON_DOUBLE_JOINT_90DEG_SHAFT1 = partialBlock("joint/double_90deg/standard/iron/shaft1")
    val STANDARD_IRON_DOUBLE_JOINT_90DEG_SHAFT2 = partialBlock("joint/double_90deg/standard/iron/shaft2")

    val STANDARD_IRON_TRIPLE_T_JOINT_BODY = partialBlock("joint/triple_t/standard/iron/body")
    val STANDARD_IRON_TRIPLE_T_JOINT_SHAFT1 = partialBlock("joint/triple_t/standard/iron/shaft1")
    val STANDARD_IRON_TRIPLE_T_JOINT_SHAFT2 = partialBlock("joint/triple_t/standard/iron/shaft2")
    val STANDARD_IRON_TRIPLE_T_JOINT_SHAFT3 = partialBlock("joint/triple_t/standard/iron/shaft3")

    //#endregion

    //#region Batteries

    val LEAD_ACID_BATTERY = partialBlock("battery/lead_acid")
    val SPEC_LEAD_ACID_BATTERY = partialBlock("battery/spec_lead_acid_battery")

    //#endregion

    val VOLTAGE_SOURCE = partialBlock("voltage_source")
    val RESISTOR = partialBlock("resistor")
    val GROUND = partialBlock("ground_pin")
    val GROUND_MICRO_GRID = partialBlock("ground_pin_micro_grid")

    val RADIATOR = partialBlock("radiator")

    val SOLAR_PANEL_ONE_BLOCK = partialBlock("solar_panel_one_block")

    val STANDARD_CONNECTION = patchPartial("standard_connection")

    val POLE_TEMPORARY = partialBlock("pole")

    val SPEC_PART_FRAME = partialBlock("spec_part_frame")

    //#region Grid Anchors and Interfaces

    val POWER_GRID_INTERFACE = partialBlock("grid/power_grid_interface")

    val MICRO_GRID_INTERFACE = partialBlock("grid/micro_grid_interface")
    val MICRO_GRID_ANCHOR = partialBlock("grid/micro_grid_anchor")

    val SIGNAL_GRID_INTERFACE = partialBlock("grid/signal_grid_interface")
    val SIGNAL_GRID_ANCHOR = partialBlock("grid/signal_grid_anchor")

    //#endregion

    //#region Lights

    val SMALL_WALL_LAMP_EMITTER = partialBlock("small_wall_lamp/emitter")
    val SMALL_WALL_LAMP_CAGE = partialBlock("small_wall_lamp/cage")
    val SMALL_WALL_LAMP_CAGE_MICRO_GRID = partialBlock("small_wall_lamp/cage_micro_grid")

    val SMALL_GARDEN_LIGHT = partialBlock("small_garden_light/full")
    val TALL_GARDEN_LIGHT_EMITTER = partialBlock("tall_garden_light/emitter")
    val TALL_GARDEN_LIGHT_CAGE = partialBlock("tall_garden_light/cage")

    val LAMP_POLE_BODY = partialBlock("lamp_pole/body")
    val LAMP_POLE_EMITTER = partialBlock("lamp_pole/emitter")

    //#endregion

    //#region Small Thermal Electric Generator

    val SMALL_THERMAL_ELECTRIC_GENERATOR_BODY = partialBlock("small_thermal_electric_generator/body")
    val SMALL_THERMAL_ELECTRIC_GENERATOR_COLD_SIDE = partialBlock("small_thermal_electric_generator/cold_side")
    val SMALL_THERMAL_ELECTRIC_GENERATOR_HOT_SIDE = partialBlock("small_thermal_electric_generator/hot_side")
    val SMALL_THERMAL_ELECTRIC_GENERATOR_FLYWHEELS = partialBlock("small_thermal_electric_generator/flywheels")

    //#endregion

    val SMALL_DC_TO_DC_CONVERTER = partialBlock("small_dc_to_dc_converter/full")

    val FLAT_OSCILLOSCOPE_PART = partialBlock("oscilloscopes/flat_oscilloscope_part")

    val BASIC_SINGLE_CHANNEL_OSCILLOSCOPE_PART = partialBlock("oscilloscopes/flat_oscilloscope_part_single_channel")

    //#region Probes

    val POTENTIAL_PROBE_BODY = partialBlock("probes/potential/body")
    val POTENTIAL_PROBE_KNOB_INPUT_RANGE_MIN = partialBlock("probes/potential/knob_input_range_min")
    val POTENTIAL_PROBE_KNOB_INPUT_RANGE_MAX = partialBlock("probes/potential/knob_input_range_max")
    val POTENTIAL_PROBE_KNOB_OUTPUT_RANGE_MIN = partialBlock("probes/potential/knob_output_range_min")
    val POTENTIAL_PROBE_KNOB_OUTPUT_RANGE_MAX = partialBlock("probes/potential/knob_output_range_max")

    //#endregion

    //#region Crusher

    val CRUSHER_BODY = partialBlock("crusher/body")
    val CRUSHER_GRINDER_0 = partialBlock("crusher/grinder0")
    val CRUSHER_GRINDER_1 = partialBlock("crusher/grinder1")

    //#endregion

    //#region Extruder

    val EXTRUDER_BODY = partialBlock("electric_extruder/body")
    val EXTRUDER_DIE = partialBlock("electric_extruder/die")
    val EXTRUDER_SHAFT_A0 = partialBlock("electric_extruder/shaft_a0")
    val EXTRUDER_SHAFT_A1 = partialBlock("electric_extruder/shaft_a1")
    val EXTRUDER_SHAFT_B0 = partialBlock("electric_extruder/shaft_b0")
    val EXTRUDER_SHAFT_B1 = partialBlock("electric_extruder/shaft_b1")

    val EXTRUDER_KINETIC_BODY = partialBlock("kinetic_extruder/body")
    val EXTRUDER_KINETIC_SHAFT = partialBlock("kinetic_extruder/shaft")

    //#endregion

    //#region Motors

    val BASIC_DC_MOTOR = partialBlock("motor/basic_dc_motor")

    //#endregion

    val DIODE = partialBlock("diode/diode")

    //#region Wind Turbines

    val BASIC_WIND_TURBINE_BASE = partialBlock("wind_turbine/basic/base")
    val BASIC_WIND_TURBINE_ROTOR = partialBlock("wind_turbine/basic/rotor")

    //#endregion

    val RUBBER_TAP = partialBlock("rubber_tap/rubber_tap")
    val RUBBER_TAP_LATEX = partialBlock("rubber_tap/latex")

    //#region Primitive Coal Burner

    val PRIMITIVE_COAL_BURNER_BODY = partialBlock("primitive_coal_burner/body")
    val PRIMITIVE_COAL_BURNER_HULL = partialBlock("primitive_coal_burner/hull_tintable")
    val PRIMITIVE_COAL_BURNER_CONDUIT = polarBlock("primitive_coal_burner/conduit")
    val PRIMITIVE_COAL_BURNER_DOOR = partialBlock("primitive_coal_burner/airflow_door")

    //#endregion

    //#region Vulcanizing Autoclave

    val VULCANIZING_AUTOCLAVE_BODY = partialBlock("vulcanizing_autoclave/body")


    //#endregion

    private fun partial(path: String): PartialModel = PartialModel.of(resource(path))
    fun partialBlock(path: String): PartialModel = PartialModel.of(resource("block/$path"))
    fun polarBlock(path: String): PolarModel = PolarModel(resource("block/$path"))

    fun wireConnection(connectionHub: String, connectionFull: String): WireConnectionModel {
        val hubResourceLocation = resource("block/$connectionHub")
        val fullResourceLocation = resource("block/$connectionFull")

       return WireConnectionModel(
            PolarModel(hubResourceLocation),
            WirePatchPolarModel(hubResourceLocation, WirePatchType.Inner),
            WirePatchPolarModel(hubResourceLocation, WirePatchType.Wrapped),
           PolarModel(fullResourceLocation),
           WirePatchPolarModel(fullResourceLocation, WirePatchType.Inner),
            WirePatchPolarModel(fullResourceLocation, WirePatchType.Wrapped)
        )
    }

    fun patchPartial(connection: String): WireConnectionModelPartial {
        val resourceLocation = resource("block/$connection")

        return WireConnectionModelPartial(
            PolarModel(resourceLocation),
            WirePatchPolarModel(resourceLocation, WirePatchType.Inner),
            WirePatchPolarModel(resourceLocation, WirePatchType.Wrapped)
        )
    }

    fun initialize() {
        ServerLifecycleHooks.getCurrentServer()?.also { server ->
            require(!server.isSameThread) {
                "Initializing partial models in server thread"
            }
        }
    }

    private val modelCentersCache = ConcurrentHashMap<BakedModel, Vector3d>()

    fun getModelCenter(model: BakedModel) : Vector3d = modelCentersCache.computeIfAbsent(model) {
        val accumulator = Average3d()

        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        model.getQuads(null, null, null).forEach { quad ->
            require(quad.vertices.size == 32)

            val buffer = ByteBuffer.allocate(32)
            val intView = buffer.asIntBuffer()

            for (i in 0 until 4) {
                intView.clear()
                intView.put(quad.vertices, i * 8, 8)

                accumulator.add(
                    buffer.getFloat(0).toDouble(),
                    buffer.getFloat(4).toDouble(),
                    buffer.getFloat(8).toDouble(),
                )
            }
        }

        accumulator.average
    }

    fun getModelCenter(model: PartialModel) : Vector3d = getModelCenter(
        model.get() ?: error(
            "Tried to get partial model $model center before baked models were available"
        )
    )
}
