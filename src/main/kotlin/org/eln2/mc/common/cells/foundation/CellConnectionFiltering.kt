package org.eln2.mc.common.cells.foundation

import org.eln2.mc.data.MonopoleMap
import org.eln2.mc.data.PoleMap
import org.eln2.mc.data.findDirActualSpecificFrameOrNull
import org.eln2.mc.mathematics.Base6Direction3d

/**
 * Connection filtering based on "connection sizes".
 * If none of the two cells is [Interface], the connection isn't rejected (no filtering).
 * Otherwise, if any of the two cells can't evaluate a direction in the specific frame towards the remote cell, the connection is rejected.
 * Otherwise, if one of the cells isn't [Interface], the connection is rejected.
 * Finally, both cells are [Interface]. [accessor] is called to get the [SizeEnum] on the connection sides of both cells. The connection is rejected if the sizes aren't compatible.
 *
 * This works for all general part and block devices.
 * It allows multiple wire types (signal, electrical) and multiple sizes of said type.
 * */
inline fun<reified Interface, SizeEnum : ConnectionSizeEnum> connectionSizeRejection(sourceCell: Cell, targetCell: Cell, map: SizeCompatibilityMap<SizeEnum>, crossinline accessor: (Interface, Base6Direction3d, Cell) -> SizeEnum?) : Boolean {
    val sourceIsInterface = sourceCell is Interface
    val targetIsInterface = targetCell is Interface

    if(!sourceIsInterface && !targetIsInterface) {
        return false // No filtering to be done
    }

    val directionInSourceFrame = sourceCell.locator.findDirActualSpecificFrameOrNull(targetCell.locator)
    val directionInTargetFrame = targetCell.locator.findDirActualSpecificFrameOrNull(sourceCell.locator)

    // We reject implicitly if we can't get the local directions for both cells.
    if(directionInSourceFrame == null || directionInTargetFrame == null) {
        return true
    }

    if(sourceIsInterface && !targetIsInterface) {
        return true
    }

    @Suppress("KotlinConstantConditions") // Suggestion is wrong. Jetbrains pls fix
    if(!sourceIsInterface && targetIsInterface) {
        return true
    }

    sourceCell as Interface
    targetCell as Interface

    // Now we just check if:
    // a. They both have sizes defined (not null)
    // b. The sizes are compatible.
    val sourceSize = accessor(sourceCell, directionInSourceFrame, targetCell)
    val remoteSize = accessor(targetCell, directionInTargetFrame, sourceCell)

    return (sourceSize == null || remoteSize == null) || !map.areCompatible(sourceSize, remoteSize)
}

interface ConnectionSizeEnum {
    val index: Int
}

fun interface SizeCompatibilityMap<SizeEnum> {
    fun areCompatible(a: SizeEnum, b: SizeEnum) : Boolean
}

class SizeCompatibilityMatrixBuilder<SizeEnum>(last: SizeEnum) where SizeEnum : ConnectionSizeEnum {
    private val stride = last.index + 1
    private val matrix = BooleanArray(stride * stride)

    fun compatible(a: SizeEnum, b: SizeEnum) : SizeCompatibilityMatrixBuilder<SizeEnum> {
        matrix[a.index * stride + b.index] = true
        matrix[b.index * stride + a.index] = true
        return this
    }

    fun selfCompatible(iterable: Iterable<SizeEnum>) : SizeCompatibilityMatrixBuilder<SizeEnum> {
        iterable.forEach {
            compatible(it, it)
        }

        return this
    }

    fun build() : SizeCompatibilityMap<SizeEnum>  {
        val map = matrix.clone()

        return SizeCompatibilityMap { a, b ->
            map[a.index * stride + b.index]
        }
    }
}

enum class ThermalSize(val sizeTranslationKey: String, override val index: Int) : ConnectionSizeEnum {
    Standard("standard_thermal_size", 0),
    Any("any_thermal_size", 1);

    companion object {
        val compatibility = SizeCompatibilityMatrixBuilder<ThermalSize>(Any)
            .selfCompatible(entries)
            .compatible(Standard, Any)
            .build()
    }
}

enum class ElectricalSize(val sizeTranslationKey: String, override val index: Int) : ConnectionSizeEnum {
    Standard("standard_electrical_size", 0),
    Signal("signal_size", 1),
    Any("any_electrical_size", 2); // Except for signal!

    companion object {
        val compatibility = SizeCompatibilityMatrixBuilder<ElectricalSize>(Any)
            .selfCompatible(entries)
            .compatible(Standard, Any)
            .build()
    }
}

enum class KineticSize(val sizeTranslationKey: String, override val index: Int) : ConnectionSizeEnum {
    Standard("standard_kinetic_size", 0),
    Any("any_kinetic_size", 1);

    companion object {
        val compatibility = SizeCompatibilityMatrixBuilder<KineticSize>(Any)
            .selfCompatible(entries)
            .compatible(Standard, Any)
            .build()
    }
}

/**
 * Supplies the [ElectricalSize] for a side of the cell, in the local frame.
 * If the returned size is null, the connection is rejected immediately. If the returned size is not equal to the other cell's size on its respective side, the connection is also rejected.
 * The connection is accepted if both cells report the same size on their respective sides.
 * */
interface SidedElectrical<C> where C : Cell, C : SidedElectrical<C> {
    /**
     * Gets the size of the electrical wire on that side.
     * @param side The side, pre-calculated, in the cell's local frame.
     * @param targetCell The remote cell, useful if a locator map is used instead of raw directions in the connection code.
     * */
    fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell) : ElectricalSize?
}

/**
 * Cell with a constant electrical wire size on all 4 horizontal sides.
 * To be used only for devices such as wires, anchors, connection hubs and such.
 * */
interface SidedElectricalFLBR<C> : SidedElectrical<C> where C : Cell, C : SidedElectricalFLBR<C> {
    /**
     * The electrical wire size. It will be supplied to all 4 sides.
     * */
    val electricalSize: ElectricalSize?

    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell) = when(side) {
        Base6Direction3d.Front -> electricalSize
        Base6Direction3d.Back -> electricalSize
        Base6Direction3d.Left -> electricalSize
        Base6Direction3d.Right -> electricalSize
        Base6Direction3d.Up -> null
        Base6Direction3d.Down -> null
    }
}

/**
 * Cell with a constant electrical wire size on 2 specific sides.
 * */
interface SidedElectricalBipole<C> : SidedElectrical<C> where C : Cell, C : SidedElectricalBipole<C> {
    val side1: Base6Direction3d
    val side2: Base6Direction3d
    val electricalSize: ElectricalSize

    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell) = when(side) {
        side1 -> electricalSize
        side2 -> electricalSize
        else -> null
    }
}

/**
 * Electrical size provider, based on a pole map.
 * */
interface SidedElectricalMapped<C> : SidedElectrical<C> where C : Cell, C : SidedElectricalMapped<C> {
    val electricalMap : PoleMap

    /**
     * The electrical size. It will be supplied to all sides the [electricalMap] covers.
     * */
    val electricalSize: ElectricalSize?

    /**
     * Returns the [electricalSize] if the [electricalMap] covers this connection.
     * */
    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell): ElectricalSize? {
        return if(electricalMap.evaluateOrNull(this as Cell, targetCell) != null) electricalSize else null
    }
}

/**
 * Electrical size provider, based on a monopolar map.
 * */
interface SidedElectricalMonoMapped<C> : SidedElectrical<C> where C : Cell, C : SidedElectricalMonoMapped<C> {
    val electricalMap: MonopoleMap

    /**
     * The electrical size. It will be supplied to all sides the [electricalMap] covers.
     * */
    val electricalSize: ElectricalSize?

    override fun getElectricalSizeOnSide(side: Base6Direction3d, targetCell: Cell): ElectricalSize? {
        if(electricalMap.evaluates(this as Cell, targetCell)) {
            return electricalSize
        }

        return null
    }
}

/**
 * Supplies the [ThermalSize] for a side of the cell, in the local frame.
 * If the returned size is null, the connection is rejected immediately. If the returned size is not equal to the other cell's size on its respective side, the connection is also rejected.
 * The connection is accepted if both cells report the same size on their respective sides.
 * */
interface SidedThermal<C> where C : Cell, C : SidedThermal<C> {
    /**
     * Gets the size of the thermal wire on that side.
     * @param side The side, pre-calculated, in the cell's local frame.
     * @param targetCell The remote cell, useful if a locator map is used instead of raw directions in the connection code.
     * */
    fun getThermalSizeOnSide(side: Base6Direction3d, targetCell: Cell) : ThermalSize?
}

/**
 * Cell with a constant thermal wire size on all 4 horizontal sides.
 * To be used only for devices such as wires, anchors, connection hubs and such.
 * */
interface SidedThermalFLBR<C> : SidedThermal<C> where C : Cell, C : SidedThermalFLBR<C> {
    /**
     * The electrical wire size. It will be supplied to all 4 sides.
     * */
    val thermalSize: ThermalSize?

    override fun getThermalSizeOnSide(side: Base6Direction3d, targetCell: Cell) = when(side) {
        Base6Direction3d.Front -> thermalSize
        Base6Direction3d.Back -> thermalSize
        Base6Direction3d.Left -> thermalSize
        Base6Direction3d.Right -> thermalSize
        Base6Direction3d.Up -> null
        Base6Direction3d.Down -> null
    }
}

/**
 * Thermal wire size provider, based on a pole map.
 * */
interface SidedThermalMapped<C> : SidedThermal<C> where C : Cell, C : SidedThermalMapped<C> {
    val thermalMap : PoleMap

    /**
     * The thermal size. It will be supplied to all sides the [thermalMap] covers.
     * */
    val thermalSize: ThermalSize?

    /**
     * Returns the [thermalSize] if the [thermalMap] covers this connection.
     * */
    override fun getThermalSizeOnSide(side: Base6Direction3d, targetCell: Cell): ThermalSize? {
        return if(thermalMap.evaluateOrNull(this as Cell, targetCell) != null) thermalSize else null
    }
}

/**
 * Thermal size provider, based on a monopolar map.
 * */
interface SidedThermalMonoMapped<C> : SidedThermal<C> where C : Cell, C : SidedThermalMonoMapped<C> {
    val thermalMap : MonopoleMap

    /**
     * The thermal size. It will be supplied to all sides the [thermalMap] covers.
     * */
    val thermalSize: ThermalSize?

    /**
     * Returns the [thermalSize] if the [thermalMap] covers this connection.
     * */
    override fun getThermalSizeOnSide(side: Base6Direction3d, targetCell: Cell): ThermalSize? {
        return if(thermalMap.evaluates(this as Cell, targetCell)) thermalSize else null
    }
}

/**
 * Supplies the [KineticSize] for a side of the cell, in the local frame.
 * If the returned size is null, the connection is rejected immediately. If the returned size is not equal to the other cell's size on its respective side, the connection is also rejected.
 * The connection is accepted if both cells report the same size on their respective sides.
 * */
interface SidedKinetic<C> where C : Cell, C : SidedKinetic<C> {
    /**
     * Gets the size of the kinetic shaft on that side.
     * @param side The side, pre-calculated, in the cell's specific frame.
     * @param targetCell The remote cell, useful if a locator map is used instead of raw directions in the connection code.
     * */
    fun getKineticSizeOnSide(side: Base6Direction3d, targetCell: Cell) : KineticSize?
}

/**
 * Cell with a constant kinetic shaft size on 2 specific sides.
 * */
interface SidedKineticBipole<C> : SidedKinetic<C> where C : Cell, C : SidedKineticBipole<C> {
    val side1: Base6Direction3d
    val side2: Base6Direction3d
    val kineticSize: KineticSize

    override fun getKineticSizeOnSide(side: Base6Direction3d, targetCell: Cell) = when(side) {
        side1 -> kineticSize
        side2 -> kineticSize
        else -> null
    }
}

/**
 * Kinetic size provider, based on a pole map.
 * */
interface SidedKineticMapped<C> : SidedKinetic<C> where C : Cell, C : SidedKineticMapped<C> {
    val kineticMap : PoleMap

    /**
     * The kinetic size. It will be supplied to all sides the [kineticMap] covers.
     * */
    val kineticSize: KineticSize?

    /**
     * Returns the [kineticSize] if the [kineticMap] covers this connection.
     * */
    override fun getKineticSizeOnSide(side: Base6Direction3d, targetCell: Cell): KineticSize? {
        return if(kineticMap.evaluateOrNull(this as Cell, targetCell) != null) kineticSize else null
    }
}

/**
 * Kinetic size provider, based on a monopolar map.
 * */
interface SidedKineticMonoMapped<C> : SidedKinetic<C> where C : Cell, C : SidedKineticMonoMapped<C> {
    val kineticMap : MonopoleMap

    /**
     * The kinetic size. It will be supplied to all sides the [kineticMap] covers.
     * */
    val kineticSize: KineticSize?

    /**
     * Returns the [kineticSize] if the [kineticMap] covers this connection.
     * */
    override fun getKineticSizeOnSide(side: Base6Direction3d, targetCell: Cell): KineticSize? {
        return if(kineticMap.evaluates(this as Cell, targetCell)) kineticSize else null
    }
}
