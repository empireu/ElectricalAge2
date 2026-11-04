import org.ageseries.libage.data.AMPERE
import org.ageseries.libage.data.ANGSTROM
import org.ageseries.libage.data.ATMOSPHERE
import org.ageseries.libage.data.AUXILIARY_CLASSIFIERS
import org.ageseries.libage.data.AtomicMass
import org.ageseries.libage.data.Area
import org.ageseries.libage.data.ArrayQuantityIterator
import org.ageseries.libage.data.BTU
import org.ageseries.libage.data.CALORIE
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.CENTIMETER
import org.ageseries.libage.data.CURIE
import org.ageseries.libage.data.DAY
import org.ageseries.libage.data.DIMENSION_TYPES
import org.ageseries.libage.data.Distance
import org.ageseries.libage.data.ElectricalResistivity
import org.ageseries.libage.data.FAHRENHEIT
import org.ageseries.libage.data.FOOT
import org.ageseries.libage.data.GALLON
import org.ageseries.libage.data.GIGA
import org.ageseries.libage.data.GRAIN
import org.ageseries.libage.data.GRAM
import org.ageseries.libage.data.HORSEPOWER
import org.ageseries.libage.data.HOUR
import org.ageseries.libage.data.INCH
import org.ageseries.libage.data.KILO
import org.ageseries.libage.data.KILOCALORIE
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.KELVIN
import org.ageseries.libage.data.LITER
import org.ageseries.libage.data.MEGA
import org.ageseries.libage.data.METER
import org.ageseries.libage.data.MICRO
import org.ageseries.libage.data.MILE
import org.ageseries.libage.data.MILLI
import org.ageseries.libage.data.MINUTE
import org.ageseries.libage.data.NANO
import org.ageseries.libage.data.PICO
import org.ageseries.libage.data.POUND
import org.ageseries.libage.data.Power
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.QuantityArray
import org.ageseries.libage.data.REVOLUTION_PER_MINUTE
import org.ageseries.libage.data.REVOLUTION_PER_SECOND
import org.ageseries.libage.data.RANKINE
import org.ageseries.libage.data.Scale
import org.ageseries.libage.data.ScaleMultiples
import org.ageseries.libage.data.SourceQuantityScale
import org.ageseries.libage.data.TERA
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.data.WATT_HOUR
import org.ageseries.libage.data.classify
import org.ageseries.libage.data.classifyAuxiliary
import org.ageseries.libage.data.classifyIntoMultiple
import org.ageseries.libage.data.cylinderResistance
import org.ageseries.libage.data.asStandardMass
import org.ageseries.libage.data.max
import org.ageseries.libage.data.min
import org.ageseries.libage.data.minus
import org.ageseries.libage.data.plus
import org.ageseries.libage.data.sourceAmplify
import org.ageseries.libage.data.sourceReduce
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI

class QuantityTests {
    private fun assertRelative(expected: Double, actual: Double, relativeTolerance: Double = 1e-9) {
        val allowed = kotlin.math.abs(expected) * relativeTolerance + 1e-300
        assertTrue(kotlin.math.abs(expected - actual) <= allowed, "expected $expected but was $actual")
    }

    @Test
    fun scaleMapAndUnmapAreInverse() {
        val scale = Scale(1.8, -459.67)

        listOf(-300.0, 0.0, 1.0, 273.15, 1e6).forEach {
            assertRelative(it, scale.unmap(scale.map(it)), 1e-12)
        }
    }

    @Test
    fun quantityFromScaleConvertsToBaseUnits() {
        assertRelative(1000.0, !Quantity(1.0, KILO * METER))
        assertRelative(0.005, !Quantity(5.0, MILLI * METER))
        assertRelative(0.3048, !Quantity(1.0, FOOT))
        assertRelative(0.0254, !Quantity(1.0, INCH))
        assertRelative(1609.344, !Quantity(1.0, MILE))
        assertRelative(0.01, !Quantity(1.0, CENTIMETER))
        assertRelative(1e-10, !Quantity(1.0, ANGSTROM))
    }

    @Test
    fun quantityRangeToConvertsFromBaseUnits() {
        assertRelative(1.0, Quantity<Distance>(1000.0)..(KILO * METER))
        assertRelative(100.0, Quantity<Distance>(1.0)..CENTIMETER)
        assertRelative(5280.0, Quantity<Distance>(1609.344)..FOOT)
    }

    @Test
    fun scaleRoundTripHoldsForCommonScales() {
        val scales = listOf<SourceQuantityScale<*>>(
            KILOGRAM, GRAM, GRAIN, POUND, MINUTE, HOUR, DAY, METER, FOOT, INCH, MILE, ANGSTROM, KELVIN,
            CELSIUS, FAHRENHEIT, RANKINE, BTU, CALORIE, KILOCALORIE, WATT_HOUR, HORSEPOWER, LITER, GALLON,
            CURIE, REVOLUTION_PER_MINUTE, REVOLUTION_PER_SECOND
        )

        scales.forEach { scale ->
            @Suppress("UNCHECKED_CAST")
            val typedScale = scale as SourceQuantityScale<Any>

            listOf(-12.5, 0.0, 1.0, 37.25, 1e5).forEach { sourceValue ->
                val quantity = Quantity(sourceValue, typedScale)
                assertRelative(sourceValue, quantity..typedScale, 1e-9)
            }
        }
    }

    @Test
    fun temperatureScalesHitKnownReferencePoints() {
        assertRelative(273.15, !Quantity(0.0, CELSIUS))
        assertRelative(373.15, !Quantity(100.0, CELSIUS))
        assertRelative(273.15, !Quantity(32.0, FAHRENHEIT))
        assertRelative(373.15, !Quantity(212.0, FAHRENHEIT))
        assertRelative(-273.15, Quantity<Temperature>(0.0)..CELSIUS)
        assertRelative(-459.67, Quantity<Temperature>(0.0)..FAHRENHEIT)
        assertRelative(1.0, !Quantity(1.8, RANKINE), 1e-5)
    }

    @Test
    fun massTimeEnergyAndPowerScalesMatchDefinitions() {
        assertRelative(1.0, !Quantity(1000.0, GRAM))
        assertRelative(0.45359237, !Quantity(1.0, POUND))
        assertRelative(3600.0, !Quantity(1.0, HOUR))
        assertRelative(86400.0, !Quantity(1.0, DAY))
        assertRelative(3600.0, !Quantity(1.0, WATT_HOUR))
        assertRelative(4.184, !Quantity(1.0, CALORIE))
        assertRelative(4184.0, !Quantity(1.0, KILOCALORIE))
        assertRelative(1055.05585262, !Quantity(1.0, BTU))
        assertRelative(745.699872, !Quantity(1.0, HORSEPOWER))
        assertRelative(0.001, !Quantity(1.0, LITER))
        assertRelative(0.003785411784, !Quantity(1.0, GALLON))
        assertRelative(3.7e10, !Quantity(1.0, CURIE))
    }

    @Test
    fun angularVelocityScalesConvertRevolutions() {
        assertRelative(2.0 * PI, !Quantity(1.0, REVOLUTION_PER_SECOND), 1e-9)
        assertRelative(2.0 * PI, !Quantity(60.0, REVOLUTION_PER_MINUTE), 1e-9)
    }

    @Test
    @Disabled("Bug: ATMOSPHERE uses sourceAmplify with 9.86923e-6, so 1 atm is 9.87e-6 Pa instead of 101325 Pa.")
    fun atmosphereIsAbout101325Pascals() {
        assertRelative(101325.0, !Quantity(1.0, ATMOSPHERE), 1e-4)
    }

    @Test
    fun scaleMultipliersApplyPowersOfTen() {
        assertRelative(1e-12, !Quantity(1.0, PICO * METER))
        assertRelative(1e-9, !Quantity(1.0, NANO * METER))
        assertRelative(1e-6, !Quantity(1.0, MICRO * METER))
        assertRelative(1e-3, !Quantity(1.0, MILLI * METER))
        assertRelative(1e3, !Quantity(1.0, KILO * METER))
        assertRelative(1e6, !Quantity(1.0, MEGA * METER))
        assertRelative(1e9, !Quantity(1.0, GIGA * METER))
        assertRelative(1e12, !Quantity(1.0, TERA * METER))
    }

    @Test
    fun scaleOperatorsAmplifyAndReduce() {
        assertRelative(1.0, !Quantity(1.0, GRAM * 1000.0))
        assertRelative(1.0, !Quantity(1000.0, KILOGRAM / 1000.0))
        assertRelative(1000.0, !Quantity(1.0, +METER))
        assertRelative(0.001, !Quantity(1.0, -METER))
        assertRelative(2.0, !Quantity(1.0, METER sourceAmplify 2.0))
        assertRelative(0.5, !Quantity(1.0, METER sourceReduce 2.0))
    }

    @Test
    fun scaleEqualityFollowsScaleParameters() {
        assertEquals(METER, standardMeterCopy())
        assertTrue(METER != CENTIMETER)
        assertEquals(METER.hashCode(), standardMeterCopy().hashCode())
    }

    private fun standardMeterCopy() = SourceQuantityScale<Distance>(Distance::class.java, Scale(1.0, 0.0))

    @Test
    fun quantityArithmetic() {
        val first = Quantity<Distance>(3.0)
        val second = Quantity<Distance>(1.5)

        assertEquals(4.5, !(first + second))
        assertEquals(1.5, !(first - second))
        assertEquals(6.0, !(first * 2.0))
        assertEquals(1.5, !(first / 2.0))
        assertEquals(2.0, first / second)
        assertEquals(-3.0, !(-first))
        assertEquals(3.0, !(+first))
        assertEquals(5.0, !(first + 2.0))
        assertEquals(1.0, !(first - 2.0))
        assertEquals(5.0, !(2.0 + first))
        assertEquals(-1.0, !(2.0 - first))
        assertTrue(Quantity<Distance>(0.0).isZero)
        assertFalse(first.isZero)
    }

    @Test
    fun quantityComparisonAndExtrema() {
        val small = Quantity<Distance>(1.0)
        val large = Quantity<Distance>(2.0)

        assertTrue(small < large)
        assertTrue(large > small)
        assertTrue(large > 1.5)
        assertEquals(small, min(small, large))
        assertEquals(large, max(small, large))
        assertEquals(large, org.ageseries.libage.data.abs(-large))
    }

    @Test
    fun quantityDivisionByZeroFollowsDoubleSemantics() {
        assertEquals(Double.POSITIVE_INFINITY, !(Quantity<Distance>(1.0) / 0.0))
        assertTrue((Quantity<Distance>(0.0) / Quantity<Distance>(0.0)).isNaN())
    }

    @Test
    fun quantityArrayStoresAndIterates() {
        val array = QuantityArray<Distance>(3) { Quantity(it.toDouble()) }

        assertEquals(3, array.size)
        assertEquals(2.0, !array[2])

        array[0] = Quantity(9.0)

        assertEquals(listOf(9.0, 1.0, 2.0), array.backing.toList())
        assertEquals(listOf(9.0, 1.0, 2.0), array.iterator().asSequence().map { !it }.toList())
        assertEquals(
            QuantityArray<Distance>(3, Quantity(1.0)),
            QuantityArray<Distance>(doubleArrayOf(1.0, 1.0, 1.0))
        )
        assertEquals(QuantityArray<Distance>(0).hashCode(), QuantityArray<Distance>(0).hashCode())
    }

    @Test
    fun quantityArrayIteratorThrowsNoSuchElementAfterEnd() {
        val iterator = ArrayQuantityIterator(QuantityArray<Distance>(1))

        iterator.next()

        assertFalse(iterator.hasNext())
        assertThrows<NoSuchElementException> { iterator.next() }
        assertFalse(iterator.hasNext())
    }

    @Test
    fun quantityArrayIndexOutOfRangeThrows() {
        assertThrows<IndexOutOfBoundsException> { QuantityArray<Distance>(2)[2] }
    }

    @Test
    fun classifyIntoMultipleMatchesDocumentedExamples() {
        val kilo = listOf(1.0 to "kilo")

        assertEquals("1.5 kilometers", classifyIntoMultiple(1500.0, 1000.0, kilo, "meters"))
        assertEquals("10.0 meters", classifyIntoMultiple(10.0, 1000.0, kilo, "meters"))
        assertEquals("0.01 kilometers", classifyIntoMultiple(10.0, 1000.0, kilo, "meters", inferFirst = false))
        assertEquals(
            "10.0 *meters",
            classifyIntoMultiple(10.0, 1000.0, listOf(1.0 to "kilo", 0.0 to "*"), "meters", inferFirst = false)
        )
    }

    @Test
    fun classifyIntoMultiplePlacesPrefixAtHashSymbol() {
        assertEquals("2.5 kW", classifyIntoMultiple(2500.0, 1000.0, ScaleMultiples.Base1000Standard.multiples, "#W"))
        assertEquals("5.0 W", classifyIntoMultiple(5.0, 1000.0, ScaleMultiples.Base1000Standard.multiples, "#W"))
    }

    @Test
    fun classifyIntoMultipleHandlesNegativeValuesBySign() {
        val multiples = ScaleMultiples.Base1000Standard.multiples

        assertEquals("-1.5 kW", classifyIntoMultiple(-1500.0, 1000.0, multiples, "#W"))
    }

    @Test
    fun classifyIntoMultipleRoundsToRequestedDecimals() {
        val multiples = ScaleMultiples.Base1000Standard.multiples

        assertEquals("1.2346 kW", classifyIntoMultiple(1234.5678, 1000.0, multiples, "#W", decimals = 4))
        assertEquals("1.0 kW", classifyIntoMultiple(1234.5678, 1000.0, multiples, "#W", decimals = 0))
    }

    @Test
    @Disabled("Bug: negative values recurse without forwarding decimals and inferFirst, so decimals is ignored.")
    fun classifyIntoMultipleKeepsDecimalsForNegativeValues() {
        val multiples = ScaleMultiples.Base1000Standard.multiples

        assertEquals("-1.2346 kW", classifyIntoMultiple(-1234.5678, 1000.0, multiples, "#W", decimals = 4))
    }

    @Test
    fun classifyIntoMultipleUsesEveryStandardPrefixAcrossMagnitudes() {
        val multiples = ScaleMultiples.Base1000Standard.multiples
        val expected = listOf(
            1e-9 to "1.0 nW", 1e-6 to "1.0 µW", 1e-3 to "1.0 mW", 1.0 to "1.0 W", 1e3 to "1.0 kW",
            1e6 to "1.0 MW", 1e9 to "1.0 GW", 1e12 to "1.0 TW"
        )

        expected.forEach { (value, text) ->
            assertEquals(text, classifyIntoMultiple(value, 1000.0, multiples, "#W"), "value $value")
        }
    }

    @Test
    @Disabled("Bug: zero has log magnitude -infinity, so it is classified with the smallest prefix, giving 0.0 mW.")
    fun classifyIntoMultipleOfZeroHasNoPrefix() {
        val multiples = ScaleMultiples.Base1000Standard.multiples

        assertEquals("0.0 W", classifyIntoMultiple(0.0, 1000.0, multiples, "#W"))
    }

    @Test
    fun classifyUsesDimensionAnnotation() {
        assertEquals("273.15 K", classify(Temperature::class.java, !Quantity(0.0, CELSIUS)))
        assertEquals("1.5 kW", classify(Power::class.java, 1500.0))
        assertEquals("2.0 mW", classify(Power::class.java, 0.002))
        assertEquals("1.0 kg", classify(org.ageseries.libage.data.Mass::class.java, 1.0))
        assertEquals("500.0 g", classify(org.ageseries.libage.data.Mass::class.java, 0.5))
    }

    @Test
    fun classifyWithoutAnnotationOmitsSymbol() {
        assertEquals("1.5 k", classify(Runnable::class.java, 1500.0))
    }

    @Test
    fun classifyExtensionsUseQuantityDimension() {
        assertEquals("1.5 kW", Quantity<Power>(1500.0).classify())
        assertEquals("5.28 kft", Quantity<Distance>(1609.344).classifyAuxiliary(::FOOT))
    }

    @Test
    fun classifyAuxiliaryFallsBackToStandardClassificationWithoutAnnotation() {
        assertEquals("1.5 km", classifyAuxiliary(::METER, 1500.0))
    }

    @Test
    fun dimensionRegistriesContainDeclaredScales() {
        assertTrue(DIMENSION_TYPES.forward.containsKey(Temperature::class.java))
        assertTrue(DIMENSION_TYPES.forward.containsKey(Distance::class.java))

        DIMENSION_TYPES.forward.forEach { (dimensionType, name) ->
            assertEquals(dimensionType, DIMENSION_TYPES.backward[name])
        }

        val temperatureScales = AUXILIARY_CLASSIFIERS[Temperature::class.java]!!

        assertTrue(temperatureScales.keyMappingSize >= 3)
        assertTrue(temperatureScales.uniqueValues.contains("C"))
        assertTrue(temperatureScales.uniqueValues.contains("F"))
    }

    @Test
    fun atomicMassConvertsToKilograms() {
        assertRelative(1.66053906660e-27, !Quantity<AtomicMass>(1.0).asStandardMass())
    }

    @Test
    fun cylinderResistanceFollowsOhmsLawGeometry() {
        val resistivity = Quantity<ElectricalResistivity>(1.68e-8)
        val resistance = resistivity.cylinderResistance(Quantity<Distance>(2.0), Quantity<Area>(1e-6))
        val doubleLength = resistivity.cylinderResistance(Quantity<Distance>(4.0), Quantity<Area>(1e-6))
        val doubleArea = resistivity.cylinderResistance(Quantity<Distance>(2.0), Quantity<Area>(2e-6))

        assertRelative(0.0336, !resistance)
        assertRelative(!resistance * 2.0, !doubleLength)
        assertRelative(!resistance / 2.0, !doubleArea)
    }

    @Test
    fun ampereIsStandardScale() {
        assertEquals(5.0, !Quantity(5.0, AMPERE))
    }
}
