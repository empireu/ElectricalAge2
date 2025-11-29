package org.eln2.mc.data

import java.util.concurrent.ConcurrentLinkedQueue

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
 * Thread-safe object pool implemented with locking.
 * */
class LockingObjectPool<T>(private val policy: PooledObjectPolicy<T>, val maximumRetained: Int) : ObjectPool<T> {
    private val objLock = Any()
    private val items = ArrayList<T>()

    override fun get(): T {
        synchronized(objLock) {
            if(items.isEmpty()) {
                return policy.create()
            }

            return items.removeLast()
        }
    }

    override fun release(obj: T) {
        synchronized(objLock) {
            if(!policy.release(obj)) {
                return
            }

            if(items.size == maximumRetained) {
                return
            }

            items.add(obj)
        }
    }
}
