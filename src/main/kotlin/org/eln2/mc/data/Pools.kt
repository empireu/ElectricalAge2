package org.eln2.mc.data

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

interface ObjectPool<T> {
    fun get(): T

    fun release(obj: T)
}

@OptIn(ExperimentalContracts::class)
inline fun<reified T> ObjectPool<T>.using(block: (obj: T) -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val obj = this.get()

    try {
        block.invoke(obj)
    }
    finally {
        this.release(obj)
    }
}

@OptIn(ExperimentalContracts::class)
inline fun<reified T> ObjectPool<T>.using2(block: (obj1: T, obj2: T) -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val obj1 = this.get()
    val obj2 = this.get()

    try {
        block.invoke(obj1, obj2)
    }
    finally {
        this.release(obj1)
        this.release(obj2)
    }
}

interface PooledObjectPolicy<T> {
    /**
     * Called to create a new instance of [T].
     * */
    fun create(): T

    /**
     * Called when [obj] is returned to the pool, to clean its state for reuse.
     * @return True if [obj] should be accepted back into the pool. If false, then [obj] will not be added back and will be collected by GC.
     * */
    fun release(obj: T): Boolean
}

class DefaultPooledObjectPolicy<T>(private val factory: () -> T, private val clear: (T) -> Boolean) : PooledObjectPolicy<T> {
    override fun create(): T = factory.invoke()

    override fun release(obj: T): Boolean = clear.invoke(obj)
}

class LinearObjectPool<T>(private val policy: PooledObjectPolicy<T>, val maximumRetained: Int) : ObjectPool<T> {
    init {
        require(maximumRetained > 0) {
            "Invalid pool size $maximumRetained"
        }
    }

    private val items = ArrayList<T>()

    override fun get(): T {
        if(items.isEmpty()) {
            return policy.create()
        }

        return items.removeLast()
    }

    override fun release(obj: T) {
        if(!policy.release(obj)) {
            return
        }

        if(items.size == maximumRetained) {
            return
        }

        items.add(obj)
    }
}

/**
 * Thread-safe object pool implemented as an atomic stack.
 * */
class LocklessAtomicObjectPool<T>(val policy: PooledObjectPolicy<T>, val maximumRetained: Int) : ObjectPool<T> {
    private class Node<T>(val value: T) {
        var next: Node<T>? = null
    }

    private val head = AtomicReference<Node<T>?>(null)
    private val retained = AtomicInteger(0)

    override fun get(): T {
        while (true) {
            val h = head.get()
                ?: return policy.create()

            val next = h.next

            if (head.compareAndSet(h, next)) {
                retained.decrementAndGet()
                h.next = null

                return h.value
            }
        }
    }

    override fun release(obj: T) {
        if (!policy.release(obj)) {
            return
        }

        while (true) {
            val count = retained.get()

            if (count >= maximumRetained) {
                return
            }

            if (retained.compareAndSet(count, count + 1)) {
                break
            }
        }

        val node = Node(obj)

        while (true) {
            val h = head.get()

            node.next = h

            if (head.compareAndSet(h, node)) {
                return
            }
        }
    }
}
