package org.eln2.mc

import org.ageseries.libage.mathematics.*
import org.eln2.mc.common.ModEvents
import org.eln2.mc.data.CsvLoader

private fun readPairs(name: String): List<Pair<String, String>> = readDatasetString(name)
    .lines().filter { it.isNotBlank() }.map { line ->
        line.split("\\s".toRegex()).toTypedArray().let {
            require(it.size == 2) {
                "KVP \"$line\" mapped to ${it.joinToString(" ")}"
            }

            Pair(it[0], it[1])
        }
    }

private fun loadPairInterpolator(
    name: String,
    mergeDuplicates: Boolean = true,
    t: Double = 0.0,
    b: Double = 0.0,
    c: Double = 0.0,
) = InterpolatorBuilder().let { sb ->
    readPairs(name).map { (kStr, vStr) ->
        Pair(
            kStr.toDoubleOrNull() ?: error("Failed to parse key \"$kStr\""),
            vStr.toDoubleOrNull() ?: error("Failed to parse value \"$vStr\"")
        )
    }.let {
        if (mergeDuplicates) {
            val buckets = ArrayList<Pair<Double, ArrayList<Double>>>()

            it.forEach { (k, v) ->
                fun create() = buckets.add(Pair(k, arrayListOf(v)))

                if (buckets.isEmpty()) {
                    create()
                } else {
                    val (lastKey, lastBucket) = buckets.last()

                    if (lastKey == k) lastBucket.add(v)
                    else create()
                }
            }

            val results = ArrayList<Pair<Double, Double>>()

            buckets.forEach { (key, values) ->
                results.add(
                    Pair(
                        key,
                        values.sum() / values.size
                    )
                )
            }

            results
        } else {
            it
        }
    }.forEach { (k, v) -> sb.with(k, v) }

    sb.buildCubic(t, b, c)
}

private fun readDatasetString(name: String) = getResourceString(resource("datasets/$name"))

private fun readCsvNumbers(name: String) = CsvLoader.loadNumericData(readDatasetString(name))

private fun loadCsvSpline(name: String, keyIndex: Int, valueIndex: Int): Spline1d {
    val builder = InterpolatorBuilder()

    readCsvNumbers(name).also { csv ->
        csv.entries.forEach {
            builder.with(it[keyIndex], it[valueIndex])
        }
    }

    return builder.buildCubic()
}

private fun loadCsvGrid2(name: String): MappedGridInterpolator {
    val csv = readCsvNumbers(name)

    var xSize = 0
    var ySize = 0

    val xMapping = InterpolatorBuilder().apply {
        csv.headers.drop(1).forEach { header ->
            with(header.toDouble(), (xSize++).toDouble())
        }
    }.buildCubic()

    val yMapping = InterpolatorBuilder().apply {
        csv.entries.forEach {
            with(it[0], (ySize++).toDouble())
        }
    }.buildCubic()

    val grid = arrayKDGridDOf(xSize, ySize)

    for (y in 0 until ySize) {
        val row = csv.entries[y]

        row.values.drop(1).forEachIndexed { x, d ->
            grid[x, y] = d
        }
    }

    return MappedGridInterpolator(grid.interpolator(), listOf(xMapping, yMapping))
}

object Datasets {
    init {
        check(ModEvents.isFullyLoaded) {
            "Tried to get datasets too early"
        }
    }

    val LEAD_ACID_VOLTAGE = loadCsvGrid2("lead_acid_12v/ds_wet.csv")
    val AIR_THERMAL_CONDUCTIVITY = loadCsvSpline("air_thermal_conductivity/ds.csv", 0, 2)
    val MINECRAFT_TEMPERATURE_CELSIUS = loadCsvSpline("minecraft_temperature/ds.csv", 0, 1)
}
