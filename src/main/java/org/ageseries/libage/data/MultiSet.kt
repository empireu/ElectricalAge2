@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")

package org.ageseries.libage.data

/**
 * [The Multiset](https://en.wikipedia.org/wiki/Multiset) is an extension of normal sets. It allows an element to be "repeated", in the sense that
 * its **multiplicity** (number of times it occurs) is tracked.
 * Consequently, it can be represented as a map of keys to their **multiplicity**.
 * */
interface MultiSet<K> : Set<K>, Map<K, Int> {
    /**
     * Gets the **multiplicity** of [key].
     * @return The multiplicity of [key] or 0, if [key] is not present in this set.
     * */
    override operator fun get(key: K): Int

    /**
     * Checks if this set contains the [element].
     * This implies that its **multiplicity** is larger than 0.
     * */
    override fun contains(element: K) = get(element) > 0
}

fun<K> MultiSet<K>.isNotEmpty() = !this.isEmpty()

/**
 * Mutable version of the [MultiSet]. Operations are similar to the [MutableMap], but incur extra validation.
 * */
interface MutableMultiSet<K> : MultiSet<K> {
    /**
     * Sets the **multiplicity** of the [element] to [n].
     * Setting the **multiplicity** to 0 means removing the element.
     * @param n The multiplicity of the [element]. It must be larger than or equal to 0.
     * */
    operator fun set(element: K, n: Int)
    /**
     * Increases the **multiplicity** of [element] by [n].
     * @return The new **multiplicity** of [element].
     * */
    fun add(element: K, n: Int = 1) : Int
    /**
     * Decreases the **multiplicity** of [element] by [n].
     * @param n The amount to decrease the multiplicity of [element] by. It must be less than or equal to the current **multiplicity**; __negative **multiplicity** is not allowed__.
     * @return The new **multiplicity** of [element]. It is larger than or equal to 0.
     * */
    fun take(element: K, n: Int = 1) : Int
    /**
     * Increases the **multiplicity** of [element] by 1.
     * */
    operator fun plusAssign(element: K) { add(element, 1) }
    /**
     * Decreases the **multiplicity** of [element] by 1.
     * The same constraints apply; the current **multiplicity** must be larger than or equal to 1.
     * Decreasing the **multiplicity** to 0 means removing the element.
     * */
    operator fun minusAssign(element: K) { take(element, 1) }
    /**
     * Removes [element] and returns its **multiplicity**.
     * @return The **multiplicity** of [element]. It will be 0 if the element was not in the multiset at the time of removal.
     * */
    fun remove(element: K) : Int
    /**
     * Removes all elements in the multiset.
     * */
    fun clear()
}

/**
 * [MutableMultiSet] implementation, backed by a [MutableMap].
 * @param map The backing mutable map.
 * */
class MutableMapMultiSet<K>(val map: MutableMap<K, Int>) : MutableMultiSet<K>, MutableMap<K, Int> by map {
    /**
     * Constructs a new instance of the [MutableMapMultiSet], backed by a [LinkedHashMap] with default constructor.
     * */
    constructor() : this(LinkedHashMap())

    /**
     * Constructs a new instance of the [MutableMapMultiSet], backed by a [LinkedHashMap] with predefined size [initialCapacity].
     * */
    constructor(initialCapacity: Int) : this(LinkedHashMap(initialCapacity))

    /**
     * Constructs a new instance of the [MutableMapMultiSet], backed by a [LinkedHashMap] initialized from the [iterator].
     * **Negative values as keys are not allowed.**
     * */
    constructor(iterator: Iterator<Pair<K, Int>>) : this(LinkedHashMap()) {
        iterator.forEach { (k, n) ->
            this[k] = n
        }
    }

    /**
     * Constructs a new instance of the [MutableMapMultiSet], backed by a [LinkedHashMap] initialized from the [iterable].
     * If the [iterable] is a [Collection], the size of the collection will be used.
     * **Negative values as keys are not allowed.**
     * */
    constructor(iterable: Iterable<Pair<K, Int>>) : this(
        if(iterable is Collection<*>) {
            LinkedHashMap(iterable.size)
        }
        else {
            LinkedHashMap()
        }
    ) {
        iterable.forEach { (k, n) ->
            this[k] = n
        }
    }

    override fun put(key: K, value: Int) : Int {
        val previous = this[key]
        this[key] = value
        return previous
    }

    override fun putAll(from: Map<out K, Int>) {
        // TODO is this faster than putting them one-by-one?
        from.forEach { (k, n) ->
            require(n >= 0) {
                "Tried to put $k with $n"
            }
        }

        map.putAll(from)
    }

    override fun get(key: K) = map[key] ?: 0

    override fun set(element: K, n: Int) {
        require(n >= 0) { "Tried to set $element to $n" }

        if(n == 0) {
            map.remove(element)
        }
        else {
            map[element] = n
        }
    }

    override fun add(element: K, n: Int) : Int {
        val result = get(element) + n
        this[element] = result // Go through validation
        return result
    }

    override fun take(element: K, n: Int) : Int {
        val result = get(element) - n
        this[element] = result // Go through validation
        return result
    }

    override fun remove(element: K) = map.remove(element) ?: 0

    override fun isEmpty() = map.isEmpty()

    override fun iterator() = map.keys.iterator()

    override fun containsAll(elements: Collection<K>) = map.keys.containsAll(elements)

    override fun equals(other: Any?) = map == other

    override fun hashCode() = map.hashCode()

    override fun toString() = map.toString()

    override fun clear() = map.clear()
}

fun <K> multiSetOf() : MultiSet<K> = MutableMapMultiSet()

fun <K> mutableMultiSetOf() : MutableMultiSet<K> = MutableMapMultiSet()

fun <K> emptyMultiSet() : MultiSet<K> = MutableMapMultiSet(0)

fun <K> emptyMutableMultiSet() : MutableMultiSet<K> = MutableMapMultiSet(0)

fun <K> multiSetOf(vararg pairs: Pair<K, Int>) : MultiSet<K> {
    val result = MutableMapMultiSet<K>(pairs.size)
    pairs.forEach { (k, n) -> result[k] = n }
    return result
}

fun <K> mutableMultiSetOf(vararg pairs: Pair<K, Int>) : MutableMultiSet<K> {
    val result = MutableMapMultiSet<K>(pairs.size)
    pairs.forEach { (k, n) -> result[k] = n }
    return result
}