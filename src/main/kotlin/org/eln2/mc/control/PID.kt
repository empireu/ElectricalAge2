package org.eln2.mc.control

class PIDController(var kP: Double, var kI: Double, var kD: Double) {
    constructor() : this(1.0, 0.0, 0.0)

    var errorSum = 0.0
    var lastError = 0.0

    /**
     * Gets or sets the setpoint (desired value).
     * */
    var setPoint = 0.0

    /**
     * Gets or sets the minimum control signal returned by [update]
     * */
    var minControl = Double.MIN_VALUE

    /**
     * Gets or sets the maximum control signal returned by [update]
     * */
    var maxControl = Double.MAX_VALUE

    fun update(value: Double, dt: Double): Double {
        val error = setPoint - value

        errorSum += (error + lastError) * 0.5 * dt

        val derivative = (error - lastError) / dt

        lastError = error

        return (kP * error + kI * errorSum + kD * derivative).coerceIn(minControl, maxControl)
    }

    fun reset() {
        errorSum = 0.0
        lastError = 0.0
    }
}
