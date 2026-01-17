package org.ageseries.libage.data

/**
 * Optional type for [Double] implemented as an inline class.
 * It cannot handle [Double.NaN], which is used as the sentinel value.
 * */
@JvmInline
value class OptionalDouble private constructor(private val value: Double) {
    val isPresent get() = !value.isNaN()

    fun unwrap() : Double {
        if(value.isNaN()) {
            error("Cannot unwrap optional double: not present!")
        }

        return value
    }

    companion object {
        val EMPTY = OptionalDouble(Double.NaN)

        /**
         * Wraps the [value].
         * **The [value] must not be NaN!**
         * */
        fun wrap(value: Double) : OptionalDouble {
            require(!value.isNaN()) {
                "Cannot create optional double of NaN!"
            }

            return OptionalDouble(value)
        }
    }
}

class ImmutableBoolArrayView(private val array: BooleanArray) : AbstractList<Boolean>() {
    override val size: Int get() = array.size
    override fun get(index: Int) = array[index]
    override fun iterator() = array.iterator()
}

class ImmutableByteArrayView(private val array: ByteArray) : AbstractList<Byte>() {
    override val size: Int get() = array.size
    override fun get(index: Int) = array[index]
    override fun iterator() = array.iterator()
}

class ImmutableShortArrayView(private val array: ShortArray) : AbstractList<Short>() {
    override val size: Int get() = array.size
    override fun get(index: Int) = array[index]
    override fun iterator() = array.iterator()
}

class ImmutableIntArrayView(private val array: IntArray) : AbstractList<Int>() {
    override val size: Int get() = array.size
    override fun get(index: Int) = array[index]
    override fun iterator() = array.iterator()
}

class ImmutableLongArrayView(private val array: LongArray) : AbstractList<Long>() {
    override val size: Int get() = array.size
    override fun get(index: Int) = array[index]
    override fun iterator() = array.iterator()
}

class ImmutableFloatArrayView(private val array: FloatArray) : AbstractList<Float>() {
    override val size: Int get() = array.size
    override fun get(index: Int) = array[index]
    override fun iterator() = array.iterator()
}

class ImmutableDoubleArrayView(private val array: DoubleArray) : AbstractList<Double>() {
    override val size: Int get() = array.size
    override fun get(index: Int) = array[index]
    override fun iterator() = array.iterator()
}
