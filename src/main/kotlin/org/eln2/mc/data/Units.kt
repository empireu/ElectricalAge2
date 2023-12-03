@file:Suppress("UNUSED_PARAMETER", "unused")

package org.eln2.mc.data

import org.ageseries.libage.data.*
import kotlin.math.abs

// TODO remove this crap

/**
 * @param unit The unit type (eg, for amps, A)
 *
 * // https://www.britannica.com/science/International-System-of-Units
 */
enum class UnitType(val unit: String) {
    METRE("m"),
    SECOND("s"),
    GRAM("g"),
    AMPERE("A"),
    KELVIN("K"),
    CANDELA("cd"),
    MOLE("mol"),
    SQUARE_METRE("m²"),
    CUBIC_METRE("m³"),
    LITRE("L"),
    HERTZ("Hz"),
    NEWTON("N"),
    JOULE("J"),
    PASCAL("Pa"),
    WATT("W"),
    COULOMB("C"),
    VOLT("V"),
    FARAD("F"),
    OHM("Ω"),
    SIEMENS("S"),
    WEBER("Wb"),
    TESLA("T"),
    HENRY("H"),
    LUMEN("lm"),
    LUX("lx"),

    GRAY("Gy"),
    SIEVERT("Sv")
}

/**
 * @param prefix The prefix symbol
 * @param factor The number to multiply by
 *
 * https://www.nist.gov/pml/weights-and-measures/metric-si-prefixes
 */
enum class MetricPrefix(prefix: String, factor: Double) {
    TERA("T", 1000000000000.0),
    GIGA("G", 1000000000.0),
    MEGA("M", 1000000.0),
    KILO("k", 1000.0),
    HECTO("h", 100.0),
    DEKA("da", 10.0),
    DECI("d", 0.1),
    CENTI("c", 0.01),
    MILLI("m", 0.001),
    MICRO("μ", 0.000001),
    NANO("n", 0.000000001),
    PICO("p", 0.000000000001)
}

fun valueText(value: Double, baseUnit: UnitType): String {
    val valueAbs = abs(value)
    return when {
        valueAbs < 0.0000001 ->
            "0"

        valueAbs < 0.000999 ->
            String.format("%1.2fµ", value * 1000000)

        valueAbs < 0.00999 ->
            String.format("%1.2fm", value * 1000)

        valueAbs < 0.0999 ->
            String.format("%2.1fm", value * 1000)

        valueAbs < 0.999 ->
            String.format("%3.0fm", value * 1000)

        valueAbs < 9.99 ->
            String.format("%1.2f", value)

        valueAbs < 99.9 ->
            String.format("%2.1f", value)

        valueAbs < 999 ->
            String.format("%3.0f", value)

        valueAbs < 9999 ->
            String.format("%1.2fk", value / 1000.0)

        valueAbs < 99999 ->
            String.format("%2.1fk", value / 1000.0)

        else -> // if(value < 1000000)
            String.format("%3.0fk", value / 1000.0)
    } + baseUnit.unit
}

fun parseTimeUnitOrNull(unit: String) = when (unit) {
    "days" -> DAYS
    "day" -> DAYS
    "d" -> DAYS
    "hours" -> HOURS
    "hour" -> HOURS
    "hrs" -> HOURS
    "hr" -> HOURS
    "h" -> HOURS
    "minutes" -> MINUTES
    "minute" -> MINUTES
    "min" -> MINUTES
    "seconds" -> SECOND
    "second" -> SECOND
    "sec" -> SECOND
    "s" -> SECOND
    else -> null
}

fun parseTimeUnit(unit: String) = parseTimeUnitOrNull(unit) ?: error("Unrecognised time unit $unit")

fun parseTempUnitOrNull(unit: String) = when (unit) {
    "celsius" -> CELSIUS
    "°C" -> CELSIUS
    "C" -> CELSIUS
    "kelvin" -> KELVIN
    "K" -> KELVIN
    else -> null
}

fun parseTempUnit(unit: String) = parseTempUnitOrNull(unit) ?: error("Unrecognised temp unit $unit")
