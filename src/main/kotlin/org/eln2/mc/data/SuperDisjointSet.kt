@file:Suppress("UNCHECKED_CAST")

package org.eln2.mc.data

/**
 * Grissess take notes
 * */
abstract class SuperDisjointSet<Self : SuperDisjointSet<Self>> {
    var size: Int = 1
        private set

    @Suppress("LeakingThis")
    var parent: Self = this as Self

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

    open fun unite(other: Self) {
        val thisRep = this.representative
        val otherRep = other.representative

        if (thisRep === otherRep) {
            return
        }

        val bigger: Self
        val smaller: Self

        if(thisRep.size < otherRep.size) {
            bigger = otherRep
            smaller = thisRep
        }
        else {
            bigger = thisRep
            smaller = otherRep
        }

        smaller.parent = bigger.parent
        bigger.size += smaller.size
    }
}
