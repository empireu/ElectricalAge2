package org.eln2.mc

import org.ageseries.libage.mathematics.*
import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.NEGATIVE
import org.ageseries.libage.sim.electrical.mna.POSITIVE
import org.ageseries.libage.sim.electrical.mna.component.*
import org.eln2.mc.common.cells.foundation.*

fun Term.offerPositive() = ElectricalComponentInfo(this, POSITIVE)
fun Term.offerNegative() = ElectricalComponentInfo(this, NEGATIVE)
fun Term.offerInternal() = ElectricalComponentInfo(this, INTERNAL_PIN)
fun Term.offerExternal() = ElectricalComponentInfo(this, EXTERNAL_PIN)

fun ElectricalConnectivityMap.join(a: ElectricalComponentInfo, b: ElectricalComponentInfo) {
    this.connect(a.component, a.index, b.component, b.index)
}
