package org.ageseries.libage.data

/**
 * A BiMap ("bidirectional" or "bijective map") is a map that associates two domains (types) in such a way that an
 * inverse always exists.
 *
 * Type [F] is the "forward" type, and type [B] is the "backward" type. By nature, the choice of direction is arbitrary,
 * except that it is consistent within this API.
 *
 * Implementation note: it would be possible to add methods
 * - get(f: F)
 * - get(t: T)
 * ... and depend on the overloading, _only if_ F and T are truly disjoint. While variance rules may permit this, it is
 * perfectly legal to have reflexive bijections (think: list of unordered pairs), which means we can't just use
 * overloading to disambiguate the cases.
 */
interface BiMap<F, B> {
    /**
     * The forward mapping. This maps from [F] to [B].
     *
     * Per the rules: if there exists in here an entry from `f`: [F] to `b`: [B], then [backward] will have a
     * corresponding entry from `b` to `f`.
     *
     * Query it like you would any other [Map].
     */
    val forward: Map<F, B>

    /**
     * The backward mapping. This maps from [B] to [F].
     *
     * Per the rules: if there exists in here an entry from `b`: [B] to `f`: [F], then [forward] will have a
     * corresponding entry from `f` to `b`.
     *
     * Query it like you would any other [Map].
     */
    val backward: Map<B, F>

    /**
     * The number of entries in this BiMap.
     *
     * This should be equal to the sizes of both [forward] and [backward], provided the invariants hold.
     */
    val size: Int get() = forward.size

    /**
     * The pairs (in [F], [B] order) owned by this BiMap, as an [Iterable].
     */
    val entries: Iterable<Pair<F, B>> get() = forward.entries.map { it.toPair() }
}

/**
 * A mutable version of the [BiMap].
 */
interface MutableBiMap<F, B>: BiMap<F, B> {
    /*
     * NB: Don't provide mutable views into the underlying maps; users could change them under our noses, invalidating
     * the guarantees above.
     */
    /**
     * Add a bijection from [f] to [b], returning true if some bijection had to be replaced.
     */
    fun addOrReplace(f: F, b: B): Boolean

    /**
     * Add a bijection from [f] to [b]. If this replaces some other bijection, raise an error.
     */
    fun add(f: F, b: B) {
        val existingF = forward[f]
        val existingB = backward[b]

        if(existingF != null || existingB != null) {
            error("Associating $f and $b would replace $existingF $existingB")
        }

        addOrReplace(f, b)
    }

    /**
     * Remove [f], and, if mapped to some `b`, any bijection between it and `b`.
     *
     * Returns if the bijection existed.
     */
    fun removeForward(f: F): Boolean

    /**
     * Remove [b], and, if mapped to some `f`, any bijection between it and `f`.
     *
     * Returns if the bijection existed.
     */
    fun removeBackward(b: B): Boolean

    /**
     * Clear the BiMap.
     *
     * This runs in time linear in [size] in the default implementation. Implementors should strive for better
     * complexities, and document them accordingly.
     */
    fun clear() {
        forward.keys.toList().forEach { removeForward(it) }
    }
}

/**
 * Bijectively associates the keys with values selected using [valueSelector].
 * Similar to Kotlin's [associateWith].
 * */
inline fun <K, V> Iterable<K>.associateWithBi(valueSelector: (K) -> V): BiMap<K, V> {
    val result = mutableBiMapOf<K, V>()

    this.forEach { k ->
        result.add(k, valueSelector(k))
    }

    return result
}

/**
 * An implementation of [MutableBiMap] using a pair of Kotlin's standard maps.
 */
class MutableMapPairBiMap<F, B>(pairs: Iterator<Pair<F, B>>): MutableBiMap<F, B> {
    constructor() : this(emptyList<Pair<F, B>>().iterator())

    // These need to have backing fields, while we override a getter in the interface. Awkward, but necessary.
    override val forward = mutableMapOf<F, B>()
    override val backward = mutableMapOf<B, F>()
    // Order important here too--the maps have to be initialized before this constructor
    init {
        for((f, b) in pairs) {
            addOrReplace(f, b)
        }
    }

    override fun addOrReplace(f: F, b: B): Boolean =
        // Break existing links
        (forward[f]?.also { ob -> backward.remove(ob) } != null)
        .let {
            // Order is important! || short-circuits, so do the actual change first.
            backward[b]?.also { of -> forward.remove(of) } != null || it
        }.also {
            // Add current links
            forward[f] = b
            backward[b] = f
        }

    override fun removeForward(f: F): Boolean =
        forward.remove(f).also {
            it?.also { ob -> backward.remove(ob) }
        } != null

    override fun removeBackward(b: B): Boolean  =
        backward.remove(b).also {
            it?.also { of -> forward.remove(of) }
        } != null

    /**
     * This implementation is O(1).
     */
    override fun clear() {
        forward.clear()
        backward.clear()
    }

    override fun toString() = buildString {
        forward.forEach { (f, b) ->
            this.appendLine("$f <-> $b")
        }
    }
}

/**
 * An immutable view of a realized [MutableBiMap].
 */
@JvmInline
value class ImmutableBiMapView<F, B>(private val inner: MutableBiMap<F, B>): BiMap<F, B> {
    override val forward: Map<F, B>
        get() = inner.forward

    override val backward: Map<B, F>
        get() = inner.backward
}

/**
 * An immutable [BiMap] implemented as a pair of Kotlin stdlib [Map]s, constructed with the given pairs.
 *
 * It is unspecified, if the pairs are not a bijective map already, which mappings will exist. Presently, later pairs
 * override earlier ones, but this may not be dependable in the future.
 *
 * Note that this does not name a type.
 */

fun<F, B> MapPairBiMap(pairs: Iterator<Pair<F, B>>) =
    ImmutableBiMapView(MutableMapPairBiMap(pairs))

/**
 * An immutable [BiMap] implemented as a pair of Kotlin stdlib [Map]s, constructed empty.
 *
 * See the [other constructor](MapPairBiMap) for details.
 */

fun<F, B> MapPairBiMap() = ImmutableBiMapView(MutableMapPairBiMap<F, B>())

/**
 * Create an empty immutable [BiMap] with the default implementation.
 */
fun<F, B> emptyBiMap(): BiMap<F, B> = MapPairBiMap()

/**
 * Create an immutable [BiMap] of the given [Pair]s with the default implementation.
 */
fun<F, B> biMapOf(vararg pairs: Pair<F, B>) = MapPairBiMap(pairs.iterator())

/**
 * Create a [MutableBiMap] of the given [Pair]s with the default implementation.
 */
fun<F, B> mutableBiMapOf(vararg pairs: Pair<F, B>) = MutableMapPairBiMap(pairs.iterator())

/**
 * Create an immutable [BiMap] from this [Iterator] over [Pair]s.
 */
fun<F, B> Iterator<Pair<F, B>>.toBiMap() = MapPairBiMap(this)

/**
 * Create a [MutableBiMap] from this [Iterator] over [Pair]s.
 */
fun<F, B> Iterator<Pair<F, B>>.toMutableBiMap() = MutableMapPairBiMap(this)

/**
 * Create an immutable [BiMap] from this [Iterable] of [Pair]s.
 */
fun<F, B> Iterable<Pair<F, B>>.toBiMap() = iterator().toBiMap()

/**
 * Create a [MutableBiMap] from this [Iterable] of [Pair]s.
 */
fun<F, B> Iterable<Pair<F, B>>.toMutableBiMap() = iterator().toMutableBiMap()

/**
 * Create an immutable [BiMap] from a [Map].
 *
 * This assumes that the given Map is invertible. If it is not, the entries kept are implementation-defined.
 */
fun<F, B> Map<F, B>.toBiMap() = entries.map { it.toPair() }.toBiMap()

/**
 * Create a [MutableBiMap] from a [Map].
 *
 * This assumes that the given Map is invertible. If it is not, the entries kept are implementation-defined.
 */
fun<F, B> Map<F, B>.toMutableBiMap() = entries.map { it.toPair() }.toMutableBiMap()