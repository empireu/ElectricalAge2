package org.eln2.mc.client.render

import dev.engine_room.flywheel.api.material.Transparency
import dev.engine_room.flywheel.lib.material.CutoutShaders
import dev.engine_room.flywheel.lib.material.LightShaders
import dev.engine_room.flywheel.lib.material.SimpleMaterial

object FlwMaterials {
    val SMOOTH_LIT: SimpleMaterial = SimpleMaterial.builder()
        .light(LightShaders.SMOOTH)
        .build()

    val CUTOUT_SMOOTH_LIT: SimpleMaterial = SimpleMaterial.builder()
        .cutout(CutoutShaders.EPSILON)
        .light(LightShaders.SMOOTH)
        .build()

    val CUTOUT_TRANSLUCENT_SMOOTH_LIT: SimpleMaterial = SimpleMaterial.builder()
        .cutout(CutoutShaders.EPSILON)
        .transparency(Transparency.TRANSLUCENT)
        .light(LightShaders.SMOOTH)
        .build()

    val OIT_NON_MIP_SMOOTH_LIT: SimpleMaterial = SimpleMaterial.builder()
        .transparency(Transparency.ORDER_INDEPENDENT)
        .mipmap(false)
        .light(LightShaders.SMOOTH)
        .build()
}
