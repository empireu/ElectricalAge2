package org.eln2.mc.client.render

import com.jozufozu.flywheel.api.Instancer
import com.jozufozu.flywheel.api.MaterialGroup
import com.jozufozu.flywheel.api.MaterialManager
import com.jozufozu.flywheel.core.Materials
import com.jozufozu.flywheel.core.PartialModel
import com.jozufozu.flywheel.core.materials.model.ModelData
import net.minecraftforge.server.ServerLifecycleHooks
import org.eln2.mc.client.render.foundation.PolarModel
import org.eln2.mc.client.render.foundation.WireConnectionModelPartial
import org.eln2.mc.common.content.WireConnectionModel
import org.eln2.mc.common.content.WirePatchType
import org.eln2.mc.common.content.WirePolarPatchModel
import org.eln2.mc.resource

typealias RenderTypedPartialModel = DefaultRenderTypePartialModel<PartialModel>

object PartialModels {
    val ELECTRICAL_WIRE_HUB = partialBlock("wire/electrical/hub")
    val ELECTRICAL_WIRE_CONNECTION = wireConnection("wire/electrical/connection_hub", "wire/electrical/connection_full")

    val THERMAL_WIRE_HUB = partialBlock("wire/thermal/hub")
    val THERMAL_WIRE_CONNECTION = wireConnection("wire/thermal/connection_hub", "wire/thermal/connection_full")

    val BATTERY = partialBlock("battery/lead_acid")

    val VOLTAGE_SOURCE = partialBlock("voltage_source")
    val RESISTOR = partialBlock("resistor")
    val GROUND = partialBlock("ground_pin")
    val SMALL_WALL_LAMP_EMITTER = partialBlock("small_wall_lamp/emitter")
    val SMALL_WALL_LAMP_CAGE = partialBlock("small_wall_lamp/cage")

    val PELTIER_BODY = partialBlock("peltier/body")
    val PELTIER_LEFT = partialBlock("peltier/left")
    val PELTIER_RIGHT = partialBlock("peltier/right")

    val RADIATOR = partialBlock("radiator")

    val SOLAR_PANEL_ONE_BLOCK = partialBlock("solar_panel_one_block")

    val GRID_TAP_BODY = partialBlock("grid_tap_body")
    val GRID_TAP_CONNECTION = patchPartial("grid_tap_connection")

    val SMALL_GARDEN_LIGHT = partialBlock("small_garden_light/full")
    val TALL_GARDEN_LIGHT_EMITTER = partialBlock("tall_garden_light/emitter")
    val TALL_GARDEN_LIGHT_CAGE = partialBlock("tall_garden_light/cage")

    val POLE_TEMPORARY = partialBlock("pole")

    val SPEC_PART_FRAME = partialBlock("spec_part_frame")

    private fun partial(path: String) = PartialModel(resource(path))

    fun partialBlock(path: String) = PartialModel(resource("block/$path"))

    fun polarBlock(path: String) = PolarModel(resource("block/$path"))

    fun wireConnection(connectionHub: String, connectionFull: String): WireConnectionModel {
        val hubResourceLocation = resource("block/$connectionHub")
        val fullResourceLocation = resource("block/$connectionFull")

       return WireConnectionModel(
            PolarModel(hubResourceLocation),
            WirePolarPatchModel(hubResourceLocation, WirePatchType.Inner),
            WirePolarPatchModel(hubResourceLocation, WirePatchType.Wrapped),
            PolarModel(fullResourceLocation),
            WirePolarPatchModel(fullResourceLocation, WirePatchType.Inner),
            WirePolarPatchModel(fullResourceLocation, WirePatchType.Wrapped)
        )
    }

    fun patchPartial(connection: String): WireConnectionModelPartial {
        val resourceLocation = resource("block/$connection")

        return WireConnectionModelPartial(
            PolarModel(resourceLocation),
            WirePolarPatchModel(resourceLocation, WirePatchType.Inner),
            WirePolarPatchModel(resourceLocation, WirePatchType.Wrapped)
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

enum class DefaultRenderType {
    Solid,
    Cutout,
    Transparent
}

// find better name
data class DefaultRenderTypePartialModel<Model : PartialModel>(val model : Model, val type: DefaultRenderType)

fun MaterialManager.group(type: DefaultRenderType): MaterialGroup = when(type) {
    DefaultRenderType.Solid -> this.defaultSolid()
    DefaultRenderType.Cutout -> this.defaultCutout()
    DefaultRenderType.Transparent -> this.defaultTransparent()
}

fun<T : PartialModel> MaterialManager.model(partial: DefaultRenderTypePartialModel<T>): Instancer<ModelData> =
    this.group(partial.type)
    .material(Materials.TRANSFORMED)
    .getModel(partial.model)

fun<T : PartialModel> T.solid() = DefaultRenderTypePartialModel<T>(this, DefaultRenderType.Solid)
fun<T : PartialModel> T.cutout() = DefaultRenderTypePartialModel<T>(this, DefaultRenderType.Cutout)
fun<T : PartialModel> T.transparent() = DefaultRenderTypePartialModel<T>(this, DefaultRenderType.Transparent)

