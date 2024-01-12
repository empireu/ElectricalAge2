package org.eln2.mc

import org.ageseries.libage.sim.electrical.mna.ElectricalConnectivityMap
import org.ageseries.libage.sim.electrical.mna.NEGATIVE
import org.ageseries.libage.sim.electrical.mna.POSITIVE
import org.ageseries.libage.sim.electrical.mna.component.*
import org.eln2.mc.common.cells.foundation.*

fun Term.offerPositive() = TermRef(this, POSITIVE)
fun Term.offerNegative() = TermRef(this, NEGATIVE)
fun Term.offerInternal() = TermRef(this, INTERNAL_PIN)
fun Term.offerExternal() = TermRef(this, EXTERNAL_PIN)

fun ElectricalConnectivityMap.join(a: TermRef, b: TermRef) {
    this.connect(a.component, a.index, b.component, b.index)
}
