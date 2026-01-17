package org.ageseries.libage.utils

import org.ageseries.libage.data.NANO
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.SECOND
import org.ageseries.libage.data.Time
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.system.measureNanoTime

/**
 * Simple stopwatch using [System.nanoTime]
 * */
class Stopwatch {
    var initialTimeStamp = System.nanoTime()
        private set

    var lastSampleTimeStamp = initialTimeStamp
        private set

    /**
     * Samples the elapsed time and resets the [lastSampleTimeStamp]. This does not affect [initialTimeStamp] and [total].
     * @return The time elapsed since the last call to [sample].
     * */
    fun sample(): Quantity<Time> {
        val current = System.nanoTime()
        val elapsedNanoseconds = current - lastSampleTimeStamp
        lastSampleTimeStamp = current

        return Quantity(elapsedNanoseconds.toDouble(), NANO * SECOND)
    }

    fun resetTotal() {
        initialTimeStamp = System.nanoTime()
    }

    /**
     * Gets the time elapsed since the last call to [resetTotal].
     * */
    val total get() = Quantity((System.nanoTime() - initialTimeStamp).toDouble(), NANO * SECOND)
}

/**
 * Measures the time spend executing [block] using [System.nanoTime].
 * */
@OptIn(ExperimentalContracts::class)
inline fun measureDuration(block: () -> Unit) : Quantity<Time> {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val result = measureNanoTime(block)

    return Quantity(result.toDouble(), NANO * SECOND)
}
