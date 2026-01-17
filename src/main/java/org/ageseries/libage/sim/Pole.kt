package org.ageseries.libage.sim

/**
 * Conventional enum for identifying the ends or terminals of a bipolar device.
 * */
enum class Pole(val symbol: String, val index: Int) {
    Positive("+", 0),
    Negative("-", 1);

    val opposite get() = when(this) {
        Positive -> Negative
        Negative -> Positive
    }

    companion object {
        val byIndex = listOf(Positive, Negative)
    }
}