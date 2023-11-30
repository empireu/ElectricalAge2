package org.eln2.mc.data

// or multiset
interface Histogram<K> {
    operator fun get(k: K): Int
    fun contains(k: K) = get(k) > 0
}

interface MutableHistogram<K> : Histogram<K> {
    operator fun set(k: K, n: Int)
    fun add(k: K, n: Int = 1) : Int
    fun take(k: K, n: Int = 1) : Int
    operator fun plusAssign(k: K) { add(k) }
    operator fun minusAssign(k: K) { take(k) }
    fun remove(k: K) = set(k, 0)
    fun clear()
}

class MutableMapHistogram<K>(val map: MutableMap<K, Int>) : MutableHistogram<K> {
    constructor() : this(HashMap())

    override fun get(k: K) = map[k] ?: 0

    override fun set(k: K, n: Int) { map[k] = n }

    override fun add(k: K, n: Int) : Int {
        val result = get(k) + n
        map[k] = result
        return result
    }

    override fun take(k: K, n: Int) : Int {
        val result = get(k) - n

        if (result < 0) {
            error("Tried to remove more elements than were available")
        }

        this[k] = result

        return result
    }

    override fun toString(): String {
        val sb = StringBuilder()

        map.forEach { (k, n) ->
            sb.appendLine("$k: $n")
        }

        return sb.toString()
    }

    override fun clear() = map.clear()

    fun removeMapping(k: K) = map.remove(k)
}
