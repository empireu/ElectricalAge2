package org.ageseries.libage.utils

/**
 * Gets the KClass#simpleName from the class of [T]. The result is the name as defined in the source code.
 * */
inline fun<reified T> sourceName() = checkNotNull(T::class.simpleName) {
    "Failed to get name of ${T::class}"
}

/**
 * Gets the Class#simpleName. The result is the name as defined in the source code.
 * */
fun Class<*>.sourceName() = checkNotNull(this.simpleName) {
    "Failed to get name of $this"
}