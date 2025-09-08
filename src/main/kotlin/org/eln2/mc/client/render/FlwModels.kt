package org.eln2.mc.client.render

import dev.engine_room.flywheel.lib.model.baked.PartialModel
import net.minecraftforge.server.ServerLifecycleHooks
import org.eln2.mc.client.render.foundation.PolarModel
import org.eln2.mc.client.render.foundation.WireConnectionModel
import org.eln2.mc.client.render.foundation.WirePatchPolarModel
import org.eln2.mc.client.render.foundation.WirePatchType
import org.eln2.mc.common.content.WireConnectionModelPartial
import org.eln2.mc.resource

object FlwModels {
    val ELECTRICAL_WIRE_HUB = partialBlock("wire/electrical/hub")
    val ELECTRICAL_WIRE_CONNECTION = wireConnection("wire/electrical/connection_hub", "wire/electrical/connection_full")

    val THERMAL_WIRE_HUB = partialBlock("wire/thermal/hub")
    val THERMAL_WIRE_CONNECTION = wireConnection("wire/thermal/connection_hub", "wire/thermal/connection_full")

    val BATTERY = partialBlock("battery/lead_acid")

    val VOLTAGE_SOURCE = partialBlock("voltage_source")
    val RESISTOR = partialBlock("resistor")
    val GROUND = partialBlock("ground_pin")
    val GROUND_MICRO_GRID = partialBlock("ground_pin_micro_grid")
    val SMALL_WALL_LAMP_EMITTER = partialBlock("small_wall_lamp/emitter")
    val SMALL_WALL_LAMP_CAGE = partialBlock("small_wall_lamp/cage")
    val SMALL_WALL_LAMP_CAGE_MICRO_GRID = partialBlock("small_wall_lamp/cage_micro_grid")

    val PELTIER_BODY = partialBlock("peltier/body")
    val PELTIER_LEFT = partialBlock("peltier/left")
    val PELTIER_RIGHT = partialBlock("peltier/right")

    val RADIATOR = partialBlock("radiator")

    val SOLAR_PANEL_ONE_BLOCK = partialBlock("solar_panel_one_block")

    val POWER_GRID_INTERFACE = partialBlock("power_grid_interface")
    val STANDARD_CONNECTION = patchPartial("standard_connection")

    val SMALL_GARDEN_LIGHT = partialBlock("small_garden_light/full")
    val TALL_GARDEN_LIGHT_EMITTER = partialBlock("tall_garden_light/emitter")
    val TALL_GARDEN_LIGHT_CAGE = partialBlock("tall_garden_light/cage")

    val POLE_TEMPORARY = partialBlock("pole")

    val SPEC_PART_FRAME = partialBlock("spec_part_frame")

    val MICRO_GRID_ANCHOR = partialBlock("micro_grid_anchor")
    val MICRO_GRID_INTERFACE = partialBlock("micro_grid_interface")

    val LAMP_POLE_BODY = partialBlock("lamp_pole/body")
    val LAMP_POLE_EMITTER = partialBlock("lamp_pole/emitter")

    val SMALL_THERMAL_ELECTRIC_GENERATOR_BODY = partialBlock("small_thermal_electric_generator/body")
    val SMALL_THERMAL_ELECTRIC_GENERATOR_COLD_SIDE = partialBlock("small_thermal_electric_generator/cold_side")
    val SMALL_THERMAL_ELECTRIC_GENERATOR_HOT_SIDE = partialBlock("small_thermal_electric_generator/hot_side")
    val SMALL_THERMAL_ELECTRIC_GENERATOR_FLYWHEELS = partialBlock("small_thermal_electric_generator/flywheels")

    private fun partial(path: String) = PartialModel.of(resource(path))

    fun partialBlock(path: String) = PartialModel.of(resource("block/$path"))

    fun polarBlock(path: String) = PolarModel(resource("block/$path"))

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
}
