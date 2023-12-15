package org.eln2.mc.extensions

import com.jozufozu.flywheel.util.transform.Translate
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import org.ageseries.libage.data.*
import org.ageseries.libage.sim.Simulator
import org.ageseries.libage.sim.ThermalMass
import org.eln2.mc.*
import org.eln2.mc.control.PIDController
import java.util.*

/*

inline fun <reified T : Cell> Level.getCellOrNull(mb: MultiblockManager, cellPosId: BlockPos): T? {
    val entity = this.getBlockEntity(mb.txIdWorld(cellPosId)) as? CellBlockEntity
        ?: return null

    return entity.cell as? T
}

inline fun <reified T : Cell> Level.getCell(mb: MultiblockManager, cellPosId: BlockPos): T =
    getCellOrNull(mb, cellPosId) ?: error("Cell was not present")
*/

fun Simulator.connect(a: ThermalMass, environmentInformation: EnvironmentInformation) {
    this.connect(a, environmentInformation.temperature)
}

fun Double.formattedPercentNormalized(decimals: Int = 2): String {
    return "${(this * 100.0).formatted(decimals)}%"
}

fun <K, V> MutableSetMapMultiMap<K, V>.bind(): MutableSetMapMultiMap<K, V> {
    val result = MutableSetMapMultiMap<K, V>()

    this.keys.forEach { k ->
        result[k].addAll(this[k])
    }

    return result
}

fun <T> ArrayList<T>.bind() = ArrayList<T>(this.size).also { it.addAll(this) }

@Suppress("UNCHECKED_CAST")
fun <K, V> HashMap<K, V>.bind() = this.clone() as HashMap<K, V>

fun <Self : Translate<Self>> Self.translateNormal(normal: Vec3, distance: Double) : Self {
    this.translate(normal * distance)
    return this
}

fun <Self : Translate<Self>> Self.translateNormal(normal: Vec3i, distance: Double) : Self {
    this.translate(normal.toVec3() * distance)
    return this
}

fun <Self : Translate<Self>> Self.translateNormal(normalDirection: Direction, distance: Double) : Self {
    return this.translateNormal(normalDirection.normal, distance)
}

fun PIDController.stateToNbt() : CompoundTag {
    val tag = CompoundTag()
    tag.putDouble("errorSum", this.errorSum)
    tag.putDouble("lastError", this.lastError)
    return tag
}

fun PIDController.stateFromNbt(tag: CompoundTag) {
    this.errorSum = tag.getDouble("errorSum")
    this.lastError = tag.getDouble("lastError")
}



