import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.Material
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MaterialTests {
    private fun assertWithinFactorOfTwo(expected: Double, actual: Double, label: String) {
        assertTrue(actual > expected / 2.0 && actual < expected * 2.0) {
            "$label: expected about $expected, got $actual"
        }
    }

    private fun assertElement(
        element: ChemicalElement,
        density: Double,
        specificHeat: Double,
        thermalConductivity: Double,
        electricalResistivity: Double
    ) {
        val material = element.asMaterial

        assertWithinFactorOfTwo(density, !material.density, "$element density in kg/m3")
        assertWithinFactorOfTwo(specificHeat, !material.specificHeat, "$element specific heat in J/kgK")
        assertWithinFactorOfTwo(thermalConductivity, !material.thermalConductivity, "$element conductivity in W/mK")
        assertWithinFactorOfTwo(electricalResistivity, !material.electricalResistivity, "$element resistivity in ohm m")
    }

    @Test
    fun copperPropertiesAreInSiUnits() {
        assertElement(ChemicalElement.Copper, 8960.0, 385.0, 401.0, 1.68e-8)
    }

    @Test
    fun ironPropertiesAreInSiUnits() {
        assertElement(ChemicalElement.Iron, 7874.0, 449.0, 80.0, 9.7e-8)
    }

    @Test
    fun tinPropertiesAreInSiUnits() {
        assertElement(ChemicalElement.Tin, 7310.0, 227.0, 67.0, 1.09e-7)
    }

    @Test
    fun leadPropertiesAreInSiUnits() {
        assertElement(ChemicalElement.Lead, 11340.0, 129.0, 35.0, 2.08e-7)
    }

    @Test
    fun latexRubberDensityIsInSiUnits() {
        assertWithinFactorOfTwo(920.0, !Material.LATEX_RUBBER.density, "Latex rubber density")
    }

    @Test
    fun glassDensityIsInSiUnits() {
        assertWithinFactorOfTwo(2500.0, !Material.GLASS.density, "Glass density")
    }
}
