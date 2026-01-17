package org.ageseries.libage.utils

import org.ageseries.libage.sim.electrical.ElectricalCircuitCompiler
import org.ageseries.libage.sim.electrical.ElectricalSimulation
import org.ageseries.libage.sim.kinetic.KineticNetworkOptimizer

/**
 * Global switch for validation.
 * Sets:
 *  - [org.ageseries.libage.sim.electrical.ElectricalSimulation.USE_VALIDATION]
 *  - [org.ageseries.libage.sim.electrical.ElectricalCircuitCompiler.USE_VALIDATION]
 *  - [org.ageseries.libage.sim.kinetic.KineticNetworkOptimizer.USE_VALIDATION]
 * */
fun libageUseValidation(enabled: Boolean) {
    ElectricalSimulation.USE_VALIDATION = enabled
    ElectricalCircuitCompiler.USE_VALIDATION = enabled
    KineticNetworkOptimizer.USE_VALIDATION = enabled
    println("Libage: useValidation $enabled")
}