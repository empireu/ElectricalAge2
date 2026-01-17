package org.ageseries.libage.data

/**
 * Implementation of the Disjoint-Set data structure designed for use in conjunction with the Union-Find algorithm.
 * It is intended to be extended by inheritors, where the term "Super" (akin to a **superclass**) clarifies its nature.
 * To enhance usability, a *self-type parameter* ([Self]) has been integrated.
 *
 * The [representative] can be retrieved as [Self], and the [union] operation readily accepts [Self].
 * Essentially, inheritors can work with instances of their class without necessitating casting (unlike the old implementation).
 *
 * It's important to note that controlling which instance ultimately becomes the representative can be challenging.
 * In cases where this is unavoidable, the [priority] parameter can be employed.
 * However, it's advisable to use this option sparingly, as it compromises the optimality of the algorithm and should only be considered as a last resort.
*/
@Suppress("UNCHECKED_CAST") // Side effect of self parameter. Fine in our case.
abstract class SuperDisjointSet<Self : SuperDisjointSet<Self>> {
    /**
     * The size of this tree; loosely, how many Sets have been merged, including transitively, with this one.
     *
     * This value is normally only accurate for the [representative].
     */
    var size: Int = 1

    /**
     * The parent of this Set.
     *
     * Following this recursively will lead to the [representative]. All representatives refer to themselves as their parent.
     */
    @Suppress("LeakingThis") // Fine in this case.
    var parent: Self = this as Self

    /**
     * The priority of the merge. If set, this overrides the "merge by size" algorithm in [unite].
     *
     * It is recommended you don't do this unless you have a specific need to ensure that a certain Set always ends up being the [representative].
     */
    open val priority: Int = 0

    /**
     * Find the representative of this disjoint set.
     *
     * This instance is the same as all other instances that have been [unite]d, transitively, with this one.
     *
     * This is implemented using the "Path splitting" algorithm.
     */
    val representative: Self
        get() {
            var current = this

            while (current.parent != current) {
                val next = current.parent
                current.parent = next.parent
                current = next
            }

            return current as Self
        }

    /**
     * *Union operation*.
     *
     * After this is done, both this and [other] will have the same [representative] as each other (and as all other Sets with which they were previously united).
     *
     * This is implemented using the "by size" merge algorithm, adjusted for [priority].
     */
    open fun unite(other: Self) {
        val thisRep = representative
        val otherRep = other.representative

        if (thisRep == otherRep){
            return
        }

        val bigger: Self
        val smaller: Self

        // Override with priority:
        if(thisRep.priority > otherRep.priority) {
            bigger = thisRep
            smaller = otherRep
        }
        else if(otherRep.priority > thisRep.priority) {
            bigger = otherRep
            smaller = thisRep
        }
        else {
            // By size:
            if (thisRep.size < otherRep.size) {
                bigger = otherRep
                smaller = thisRep
            } else {
                bigger = thisRep
                smaller = otherRep
            }
        }

        smaller.parent = bigger.parent
        bigger.size += smaller.size
    }
}

/**
 * Composable disjoint set, implemented as a [SuperDisjointSet].
 * This implementation proves beneficial in specific algorithms where certain elements are partitioned using union-find.
 * These elements might represent pure data or simply may not want or need to implement [SuperDisjointSet].
 * */
class DisjointSet(override var priority: Int = 0) : SuperDisjointSet<DisjointSet>() // *It also proves useful in unit tests.