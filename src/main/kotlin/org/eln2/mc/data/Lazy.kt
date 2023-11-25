package org.eln2.mc.data

interface LazyResettable<T> : Lazy<T> {
    fun reset() : Boolean
}

class UnsafeLazyResettable<T>(private val new: () -> T) : LazyResettable<T> {
    private var instance: T? = null

    override fun reset(): Boolean {
        val instance = this.instance
        this.instance = null
        return instance != null
    }

    override val value: T
        get() {
            val instance = this.instance

            if(instance != null) {
                return instance
            }

            val newValue = new()
            this.instance = newValue
            return newValue
        }

    override fun isInitialized() = instance != null
}
