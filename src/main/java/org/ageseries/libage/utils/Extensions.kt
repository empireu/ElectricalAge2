package org.ageseries.libage.utils

/**
 * Adds the [value] to the map.
 * Throws an error with the specified [message] if a value with [key] is already present.
 * */
inline fun<K, V> MutableMap<K, V>.putUnique(key: K, value: V, message: () -> String) {
    require(this.put(key, value) == null, message)
}

/**
 * Adds the [value] to the map.
 * Throws an error if a value with [key] is already present.
 * */
fun<K, V> MutableMap<K, V>.putUnique(key: K, value: V) {
    this.putUnique(key, value) {
        "Mapping with $key $value was not unique"
    }
}

/**
 * Adds the [value] to the set.
 * Throws an error with the specified [message] if [value] is already present.
 * */
inline fun<V> MutableSet<V>.addUnique(value: V, message: () -> String) {
    require(this.add(value), message)
}

/**
 * Adds the [value] to the set.
 * Throws an error if [value] is already present.
 * */
fun<V> MutableSet<V>.addUnique(value: V) {
    this.addUnique(value) {
        "Element $value was not unique"
    }
}

/**
 * Removes an element from the set.
 * If this set preserves order, then this operation will remove the first element in the set.
 * */
fun<T> MutableSet<T>.removeElement() : T {
    val value = this.first()
    this.remove(value)
    return value
}