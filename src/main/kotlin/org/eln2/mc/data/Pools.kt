package org.eln2.mc.data

import java.util.concurrent.atomic.AtomicReference

interface ObjectPool<T> {
    fun get(): T

    fun release(obj: T)
}

interface PooledObjectPolicy<T> {
    fun create(): T
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
class LocklessAtomicObjectPool<T>(private val policy: PooledObjectPolicy<T>, val maximumRetained: Int) : ObjectPool<T> {
    private class Node<T>(val value: T) {
        var next: Node<T>? = null
    }

    private val head = AtomicReference<Node<T>>()

    override fun get(): T {
        while (true) {
            val h = head.get()
                ?: return policy.create()

            if(head.compareAndSet(h, h.next)) {
                return h.value!!
            }
        }
    }

    override fun release(obj: T) {
        if(!policy.release(obj)) {
            return
        }

        val n = Node(obj)

        while (true) {
            val h = head.get()
            n.next = h

            if (head.compareAndSet(h, n)) {
                return
            }
        }
    }
}
