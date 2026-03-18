@file:Suppress("NOTHING_TO_INLINE")

package org.ageseries.libage.mathematics

import org.jetbrains.annotations.ApiStatus.Internal
import kotlin.math.*

const val APPROX_COMPARE_EPS = 1e-7
const val APPROX_COMPARE_EPS_FLOAT = 1e-5f
const val DUAL_COMPARE_EPS = 1e-7
const val INTEGRAL_SCAN_EPS = 1e-10
const val SYMFORCE_EPS = 2.2e-15
const val SYMFORCE_EPS_FLOAT = 1.2e-6f
const val LN_2 = 0.6931471805599453
const val LN_10 = 2.302585092994046

/**
 * Raised to integer to the specified positive power relatively efficiently.
 * Optimized cases:
 *  - **1** and **-1** (constant time)
 * */
fun Int.pow(exponent: Int): Int {
    require(exponent >= 0)

    var base = this

    if(base == 1 || base == 0 || exponent == 1) {
        return base
    }

    if (base == -1) {
        return 1 * snzi(-exponent % 2)
    }

    var remaining = exponent
    var result = 1

    while (true) {
        if (remaining and 1 != 0) {
            result *= base
        }

        remaining = remaining shr 1

        if (remaining == 0) {
            break
        }

        base *= base
    }

    return result
}

/**
 * Computes the fractional part of [x].
 * */
fun frac(x: Double): Double = x - floor(x)

/**
 * Computes the fractional part of [x].
 * */
fun frac(x: Float): Float = x - floor(x)

/**
 * Maps [v] from a source range to a destination range.
 * @param srcMin The minimum value in [v]'s range.
 * @param srcMax The maximum value in [v]'s range.
 * @param dstMin The resulting range minimum.
 * @param dstMax The resulting range maximum.
 * @return [v] mapped from the source range to the destination range.
 * */
fun map(v: Double, srcMin: Double, srcMax: Double, dstMin: Double, dstMax: Double): Double {
    return dstMin + (v - srcMin) * (dstMax - dstMin) / (srcMax - srcMin)
}

/**
 * Maps [v] from a source range to a destination range.
 * @param srcMin The minimum value in [v]'s range.
 * @param srcMax The maximum value in [v]'s range.
 * @param dstMin The resulting range minimum.
 * @param dstMax The resulting range maximum.
 * @return [v] mapped from the source range to the destination range.
 * */
fun map(v: Float, srcMin: Float, srcMax: Float, dstMin: Float, dstMax: Float): Float {
    return dstMin + (v - srcMin) * (dstMax - dstMin) / (srcMax - srcMin)
}

/**
 * Maps [v] from a source range to a destination range.
 * @param srcMin The minimum value in [v]'s range.
 * @param srcMax The maximum value in [v]'s range.
 * @param dstMin The resulting range minimum.
 * @param dstMax The resulting range maximum.
 * @return [v] mapped from the source range to the destination range.
 * */
fun map(v: Dual, srcMin: Dual, srcMax: Dual, dstMin: Dual, dstMax: Dual): Dual {
    return dstMin + (v - srcMin) * (dstMax - dstMin) / (srcMax - srcMin)
}

/**
 * Maps [v] from a source range to a destination range.
 * @param srcMin The minimum value in [v]'s range.
 * @param srcMax The maximum value in [v]'s range.
 * @param dstMin The resulting range minimum.
 * @param dstMax The resulting range maximum.
 * @return [v] mapped from the source range to the destination range.
 * */
fun map(v: Int, srcMin: Int, srcMax: Int, dstMin: Int, dstMax: Int): Int {
    return dstMin + (v - srcMin) * (dstMax - dstMin) / (srcMax - srcMin)
}

fun avg(a: Double, b: Double): Double = (a + b) / 2.0
fun avg(a: Double, b: Double, c: Double): Double = (a + b + c) / 3.0
fun avg(a: Double, b: Double, c: Double, d: Double): Double = (a + b + c + d) / 4.0
fun avg(values: List<Double>) = values.sum() / values.size.toDouble()

fun Double.mappedTo(srcMin: Double, srcMax: Double, dstMin: Double, dstMax: Double) = map(this, srcMin, srcMax, dstMin, dstMax)
fun Dual.mappedTo(srcMin: Dual, srcMax: Dual, dstMin: Dual, dstMax: Dual) = map(this, srcMin, srcMax, dstMin, dstMax)

fun approxEqual(a: Double, b: Double, epsilon: Double = APPROX_COMPARE_EPS): Boolean = abs(a - b) < epsilon
fun approxEqual(a: Float, b: Float, epsilon: Float = APPROX_COMPARE_EPS_FLOAT): Boolean = abs(a - b) < epsilon
fun Double.approxEq(other: Double, epsilon: Double = APPROX_COMPARE_EPS): Boolean = approxEqual(this, other, epsilon)
infix fun Double.approxEq(other: Double): Boolean = approxEqual(this, other)
fun Float.approxEq(other: Float, epsilon: Float = APPROX_COMPARE_EPS_FLOAT) = approxEqual(this, other, epsilon)
infix fun Float.approxEq(other: Float) = approxEqual(this, other)

/**
 * Rounds the number to the specified number of [decimals].
 * */
fun Double.rounded(decimals: Int = 3): Double {
    var multiplier = 1.0
    repeat(decimals) { multiplier *= 10 }
    return round(this * multiplier) / multiplier
}

fun snz(a: Double) = if (a >= 0.0) 1.0 else -1.0
fun nsnz(a: Double) = if (a <= 0.0) -1.0 else 1.0
fun snzi(a: Double) = if (a >= 0.0) 1 else -1
fun nsnzi(a: Double) = if (a <= 0.0) -1 else 1
fun snzi(a: Int) = if (a >= 0) 1 else -1
fun nsnzi(a: Int) = if (a <= 0) -1 else 1
fun snzE(a: Double) = if (a >= 0.0) SYMFORCE_EPS else -SYMFORCE_EPS
fun snzE(a: Float) = if (a >= 0f) SYMFORCE_EPS_FLOAT else -SYMFORCE_EPS_FLOAT
fun Double.nz() = this + snzE(this)
fun Float.nz() = this + snzE(this)

private const val ADAPTLOB_ALPHA = 0.816496580927726
private const val ADAPTLOB_BETA = 0.447213595499958

private fun adaptlobStp(f: (Double) -> Double, a: Double, b: Double, fa: Double, fb: Double, `is`: Double): Double {
    val h = (b - a) / 2.0
    val m = (a + b) / 2.0
    val mll = m - ADAPTLOB_ALPHA * h
    val ml = m - ADAPTLOB_BETA * h
    val mr = m + ADAPTLOB_BETA * h
    val mrr = m + ADAPTLOB_ALPHA * h
    val fmll = f(mll)
    val fml = f(ml)
    val fm = f(m)
    val fmr = f(mr)
    val fmrr = f(mrr)

    val i2 = h / 6.0 * (fa + fb + 5.0 * (fml + fmr))
    val i1 = h / 1470.0 * (77.0 * (fa + fb) + 432.0 * (fmll + fmrr) + 625.0 * (fml + fmr) + 672.0 * fm)

    return if (`is` + (i1 - i2) == `is` || mll <= a || b <= mrr) {
        i1
    } else {
        adaptlobStp(f, a, mll, fa, fmll, `is`) +
        adaptlobStp(f, mll, ml, fmll, fml, `is`) +
        adaptlobStp(f, ml, m, fml, fm, `is`) +
        adaptlobStp(f, m, mr, fm, fmr, `is`) +
        adaptlobStp(f, mr, mrr, fmr, fmrr, `is`) +
        adaptlobStp(f, mrr, b, fmrr, fb, `is`)
    }
}

fun integralScan(a: Double, b: Double, tolerance: Double = INTEGRAL_SCAN_EPS, f: (Double) -> Double): Double {
    var tol = tolerance

    val eps = 1e-15

    val m = (a + b) / 2.0
    val h = (b - a) / 2.0

    val x1 = 0.942882415695480
    val x2 = 0.641853342345781
    val x3 = 0.236383199662150

    val y1 = f(a)
    val y2 = f(m - x1 * h)
    val y3 = f(m - ADAPTLOB_ALPHA * h)
    val y4 = f(m - x2 * h)
    val y5 = f(m - ADAPTLOB_BETA * h)
    val y6 = f(m - x3 * h)
    val y7 = f(m)
    val y8 = f(m + x3 * h)
    val y9 = f(m + ADAPTLOB_BETA * h)
    val y10 = f(m + x2 * h)
    val y11 = f(m + ADAPTLOB_ALPHA * h)
    val y12 = f(m + x1 * h)
    val y13 = f(b)

    val i2 = h / 6.0 * (y1 + y13 + 5.0 * (y5 + y9))
    val i1 = h / 1470.0 * (77.0 * (y1 + y13) + 432.0 * (y3 + y11) + 625.0 * (y5 + y9) + 672.0 * y7)

    var `is` = h * (
        0.0158271919734802 * (y1 + y13) +
        0.0942738402188500 * (y2 + y12) +
        0.155071987336585 * (y3 + y11) +
        0.188821573960182 * (y4 + y10) +
        0.199773405226859 * (y5 + y9) +
        0.224926465333340 * (y6 + y8) +
        0.242611071901408 * y7
    )

    val s = snz(`is`)
    val erri1 = abs(i1 - `is`)
    val erri2 = abs(i2 - `is`)
    var r = 1.0

    if (erri2 != 0.0) {
        r = erri1 / erri2
    }

    if (r > 0.0 && r < 1.0) {
        tol /= r
    }

    `is` = s * abs(`is`) * tol / eps

    if (`is` == 0.0) {
        `is` = b - a
    }

    return adaptlobStp(f, a, b, y1, y13, `is`)
}

fun sec(x: Double) = 1.0 / cos(x)
fun csc(x: Double) = 1.0 / sin(x)
fun cot(x: Double) = 1.0 / tan(x)
fun coth(x: Double) = cosh(x) / sinh(x)
fun sech(x: Double) = 1.0 / cosh(x)
fun csch(x: Double) = 1.0 / sinh(x)

@Target(AnnotationTarget.FUNCTION)
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class UnsafeDualAPI

class Dual private constructor(private val values: DoubleArray) : AbstractList<Double>() {
    /**
     * Constructs a [Dual] from the single [value].
     * */
    constructor(value: Double) : this(DoubleArray(1).also {
        it[0] = value
    })

    /**
     * Constructs a [Dual] from the [values], by copying the [values] into a *new* [DoubleArray].
     * */
    constructor(values: List<Double>) : this(values.toDoubleArray())

    /**
     * Constructs a [Dual] from the value [head] and the [tail] by appending [tail] to [head].
     * */
    constructor(head: Double, tail: Dual) : this(
        DoubleArray(tail.values.size + 1).also {
            it[0] = head

            val size = tail.values.size
            var i = 0

            while (i < size) {
                it[i + 1] = tail.values[i]
                i++
            }
        }
    )

    /**
     * Constructs a [Dual] from the values [head] and the [tail] by appending [tail] to [head].
     * */
    constructor(head: Dual, tail: Double) : this(
        DoubleArray(head.values.size + 1).also {
            val size = head.values.size
            var i = 0

            while (i < size) {
                it[i] = head.values[i]
                i++
            }

            it[i] = tail
        }
    )

    /**
     * Constructs a [Dual] from the [head] and the [tail] by appending [tail] to [head].
     * The [head] and [tail] will not be mutated.
     * */
    constructor(head: DoubleArray, tail: DoubleArray) : this(
        DoubleArray(head.size + tail.size).also {
            head.copyInto(it, destinationOffset = 0)
            tail.copyInto(it, destinationOffset = head.size)
        }
    )

    /**
     * Constructs a [Dual] from the [head] and the [tail] by appending [tail] to [head].
     * */
    constructor(head: List<Double>, tail: List<Double>) : this(
        DoubleArray(head.size + tail.size).also {
            var sourceIndex = 0
            var destinationIndex = 0

            while (sourceIndex < head.size) {
                it[destinationIndex++] = head[sourceIndex++]
            }

            sourceIndex = 0

            while (sourceIndex < tail.size) {
                it[destinationIndex++] = tail[sourceIndex++]
            }
        }
    )

    /**
     * Constructs a [Dual] from the [head] and the [tail] by appending [tail] to [head].
     * */
    constructor(head: Dual, tail: Dual) : this(head.values, tail.values)

    /**
     * Constructs a [Dual] from the [head] and the [tail] by appending [tail] to [head].
     * The [head] will not be mutated.
     * */
    constructor(head: DoubleArray, tail: Dual) : this(head, tail.values)

    /**
     * Constructs a [Dual] from the [head] and the [tail] by appending [tail] to [head].
     * The [tail] will not be mutated.
     * */
    constructor(head: Dual, tail: DoubleArray) : this(head.values, tail)

    /**
     * Gets a copy of the underlying array of [values].
     * */
    fun bind() = values.clone()

    override operator fun get(index: Int) = values[index]

    override val size get() = values.size

    val isReal get() = values.size == 1

    /**
     * Gets the first value in this [Dual].
     * */
    val value get() = values[0]

    /**
     * Gets the values at the start of the [Dual], ignoring the last [n] values.
     * Equivalent to [dropLast].
     * */
    fun head(n: Int = 1) = Dual(DoubleArray(size - n) { values[it] })

    /**
     * Gets the values at the end of the [Dual], ignoring the first [n] values.
     * Equivalent to [drop].
     * */
    fun tail(n: Int = 1) = Dual(DoubleArray(size - n) { values[it + n] })

    /**
     * Applies the [operation] component-wise on the values in this dual and the other dual.
     * The resulting dual respects the **truncation rule**.
     * */
    private inline fun componentWise(other: Dual, operation: (Double, Double) -> Double) : Dual {
        val a = this.values
        val b = other.values

        val size = min(a.size, b.size)
        require(size >= 0)

        val array = DoubleArray(size)

        var i = 0
        while(i < size) {
            array[i] = operation(a[i], b[i])
            i++
        }

        return Dual(array)
    }

    /**
     * Maps the [value] using [transform] and leaves the rest of the dual untouched.
     * */
    @OptIn(UnsafeDualAPI::class)
    inline fun mapValue(transform: (Double) -> Double) : Dual {
        val values = bind()

        if(values.isNotEmpty()) {
            values[0] = transform(values[0])
        }

        return wrapUnsafe(values)
    }

    /**
     * Maps the [value] using [transformValue] and the rest of the dual using [transformTail].
     * */
    @OptIn(UnsafeDualAPI::class)
    inline fun mapValueAndTail(transformValue: (Double) -> Double, transformTail: (Double) -> Double) : Dual {
        val source = unwrapUnsafe(this)
        val size = source.size
        val values = DoubleArray(size)

        if(size > 0) {
            values[0] = transformValue(source[0])

            var i = 1
            while (i < size) {
                values[i] = transformTail(source[i])
                i++
            }
        }

        return wrapUnsafe(values)
    }

    /**
     * Maps all the values using the [transform].
     * */
    @OptIn(UnsafeDualAPI::class)
    inline fun map(transform: (Double) -> Double) : Dual {
        val source = unwrapUnsafe(this)
        val size = source.size
        val values = DoubleArray(size)

        var i = 0
        while(i < size) {
            values[i] = transform(source[i])
            i++
        }

        return wrapUnsafe(values)
    }

    operator fun unaryPlus(): Dual = this

    operator fun unaryMinus(): Dual = map { -it }

    operator fun plus(other: Dual): Dual = componentWise(other) { a, b -> a + b }

    operator fun minus(other: Dual): Dual = componentWise(other) { a, b -> a - b }

    operator fun times(other: Dual): Dual =
        if (this.isReal || other.isReal) Dual(this.value * other.value)
        else Dual(this.value * other.value, this.tail() * other.head() + this.head() * other.tail())

    operator fun div(other: Dual): Dual =
        if (this.isReal || other.isReal) Dual(this.value / other.value)
        else Dual(this.value / other.value, (this.tail() * other - this * other.tail()) / (other * other))

    inline fun function(x: (Double) -> Double, dx: (Dual) -> Dual): Dual =
        if (this.isReal) Dual(x(this.value))
        else Dual(x(this.value), dx(this.head()) * this.tail())

    operator fun plus(const: Double) = mapValue { it + const }
    operator fun minus(const: Double) = mapValue { it - const }
    operator fun times(constant: Double) = if(constant == 1.0) this else map { it * constant }
    operator fun div(constant: Double) = if(constant == 1.0) this else map { it / constant }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (javaClass != other?.javaClass) {
            return false
        }

        other as Dual

        return values.contentEquals(other.values)
    }

    fun approxEq(other: Dual, eps: Double = DUAL_COMPARE_EPS) : Boolean {
        val size = this.size

        if(size != other.size) {
            return false
        }

        var i = 0
        while (i < size) {
            if(!this[i].approxEq(other[i], eps)) {
                return false
            }

            i++
        }

        return true
    }

    override fun hashCode() = values.contentHashCode()

    override fun toString(): String {
        if (values.isEmpty()) {
            return "empty"
        }

        return values.mapIndexed { i, v -> "x$i=$v" }.joinToString(", ")
    }

    companion object {
        val empty = Dual(doubleArrayOf())

        /**
         * Constructs a [Dual] referencing the [array] without a defensive copy.
         * Only appropriate to use when [array] is guaranteed to not be mutated after the resulting [Dual] is in use.
         * */
        @UnsafeDualAPI
        fun wrapUnsafe(array: DoubleArray): Dual = Dual(array)

        /**
         * Gets a reference to the underlying storage.
         * Only appropriate to use when the resulting array will not be mutated.
         * */
        @UnsafeDualAPI
        fun unwrapUnsafe(dual: Dual): DoubleArray = dual.values

        fun const(x: Double, n: Int = 1): Dual {
            val array = DoubleArray(n)

            if(n > 0) {
                array[0] = x
            }

            return Dual(array)
        }

        fun variable(v: Double, n: Int = 1): Dual {
            val array = DoubleArray(n)

            if(n > 0) {
                array[0] = v

                if(n > 1) {
                    array[1] = 1.0
                }
            }

            return Dual(array)
        }

        /**
         * Creates a [Dual] from the provided [values].
         * A defensive copy of the array is created, to guarantee immutability.
         * */
        fun create(values: DoubleArray) = Dual(values.clone())

        fun of(vararg values: Double) = Dual(values.asList())

        /**
         * Adjusts [a] and [b] using the [head] operator so that their [size] is equal to the smallest size between [a] and [b].
         * */
        fun intersect(a: Dual, b: Dual) : Pair<Dual, Dual> {
            val sizeA = a.size
            val sizeB = b.size

            return if(sizeA == sizeB) {
                Pair(a, b)
            } else if(sizeA < sizeB) {
                Pair(a, b.head(sizeB - sizeA))
            } else {
                Pair(a.head(sizeA - sizeB), b)
            }
        }
    }
}

operator fun Double.plus(dual: Dual) = dual.mapValue { this + it }
operator fun Double.minus(dual: Dual) = dual.mapValueAndTail({ this - it }, { -it })
operator fun Double.times(dual: Dual) = if(this == 1.0) dual else dual.map { this * it }
operator fun Double.div(dual: Dual) = if(this == 1.0) dual.pow(-1) else this * (dual.pow(-1))

fun sin(x: Dual): Dual = x.function({ sin(it) }) { cos(it) }
fun cos(x: Dual): Dual = x.function({ cos(it) }) { -sin(it) }
fun tan(x: Dual): Dual = sin(x) / cos(x)
fun cot(x: Dual): Dual = cos(x) / sin(x)
fun sec(x: Dual): Dual = 1.0 / cos(x)
fun csc(x: Dual): Dual = 1.0 / sin(x)
fun sinh(x: Dual): Dual = x.function({ sinh(it) }) { cosh(it) }
fun cosh(x: Dual): Dual = x.function({ cosh(it) }) { sinh(it) }
fun tanh(x: Dual): Dual = sinh(x) / cosh(x)
fun coth(x: Dual): Dual = cosh(x) / sinh(x)
fun sech(x: Dual): Dual = 1.0 / cosh(x)
fun csch(x: Dual): Dual = 1.0 / sinh(x)
fun asin(x: Dual): Dual = x.function({ asin(it) }) { 1.0 / sqrt(1.0 - x * x) }
fun acos(x: Dual): Dual = x.function({ acos(it) }) { -1.0 / sqrt(1.0 - x * x) }
fun atan(x: Dual): Dual = x.function({ atan(it) }) { 1.0 / (x * x + 1.0)}
fun asinh(x: Dual): Dual = x.function( { asinh(it) }) { 1.0 / sqrt(x * x + 1.0) }
fun acosh(x: Dual): Dual = x.function( { acosh(it) }) { 1.0 / (sqrt(x - 1.0) * sqrt(x + 1.0)) }
fun atanh(x: Dual): Dual = x.function( { atanh(it) }) { 1.0 / (1.0 - x * x) }
fun Dual.pow(n: Double): Dual = this.function({ it.pow(n) }) { n * it.pow(n - 1.0) }
fun Dual.pow(n: Int): Dual = this.function({ it.pow(n) }) { n.toDouble() * it.pow(n - 1) }
fun sqrt(x: Dual): Dual = x.function({ sqrt(it) }) { 1.0 / (2.0 * sqrt(it)) }
fun ln(x: Dual): Dual = x.function({ ln(it) }) { 1.0 / it }
fun ln1p(x: Dual): Dual = x.function({ ln1p(it) }) { 1.0 / (1.0 + it) }
fun log2(x: Dual): Dual = x.function({ log2(it) }) { 1.0 / (it * LN_2)}
fun log10(x: Dual): Dual = x.function({ log10(it) }) { 1.0 / (it * LN_10)}
fun log(x: Dual, base: Double): Dual = x.function({ log(it, base) }) { 1.0 / (it * ln(base))}
fun exp(x: Dual): Dual = x.function({ exp(it) }) { exp(it) }
fun expm1(x: Dual): Dual = x.function({ expm1(it) }) { exp(it) }
fun exp(x: Dual, base: Double): Dual = x.function({ base.pow(it) }) { exp(it, base) * ln(base) }

/**
 * Dual version of [kotlin.math.atan2]
 * The **value** is evaluated with [kotlin.math.atan2], and the **tail** is evaluated like *arctan(y / x)* normally.
 * This is justified because *arctan2(y, x)* is *arctan(y / x) + C*.
 * */
fun atan2(y: Dual, x: Dual) : Dual {
    require(y.size == x.size) {
        "Dual atan2 requires y(size=${y.size}) and x(size=${x.size}) be of same size"
    }

    val size = y.size

    return if(size == 1) {
        Dual.const(atan2(y.value, x.value))
    }
    else if(size > 1) {
        val tangent = y / x
        val head = tangent.head()
        Dual(atan2(y.value, x.value), 1.0 / (head * head + 1.0) * tangent.tail())
    }
    else {
        Dual.empty
    }
}
