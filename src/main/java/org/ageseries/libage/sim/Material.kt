package org.ageseries.libage.sim

import org.ageseries.libage.data.*

/**
 * Represents a physical material with some useful properties.
 * */
data class Material(
    val label: String,
    val electricalResistivity: Quantity<ElectricalResistivity>,
    val thermalConductivity: Quantity<ThermalConductivity>,
    val specificHeat: Quantity<SpecificHeatCapacity>,
    val density: Quantity<Density>,
) {
    override fun toString() = "<$label ρ=${electricalResistivity.classify()} κ=${thermalConductivity.classify()} c=${specificHeat.classify()} ρ=${density.classify()}>"

    companion object {
        /**
         * Constructs a material with **S.I.** properties.
         * */
        private fun def(label: String, electricalResistivity: Double, thermalConductivity: Double, specificHeat: Double, density: Double) = Material(
            label,
            Quantity(electricalResistivity, OHM_METER),
            Quantity(thermalConductivity, WATT_PER_METER_KELVIN),
            Quantity(specificHeat, JOULE_PER_KILOGRAM_KELVIN),
            Quantity(density, KILOGRAM_PER_METER3)
        )

        val LATEX_RUBBER = def(
            "Latex Rubber",
            1e13,
            0.134,
            1130.0,
            0.92
        )

        val GLASS = def(
            "Glass",
            1e13,
            1.11,
            840.0,
            2.5
        )
    }
}