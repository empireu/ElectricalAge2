package org.eln2.mc

import org.ageseries.libage.mathematics.Pose3d
import org.ageseries.libage.mathematics.Rotation3d
import org.ageseries.libage.mathematics.Vector3d
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

class Pose3dStack {
    private val poses = ArrayList<Pose3d>()

    init {
        poses.add(Pose3d.identity)
    }

    var pose get() = poses.last()
        set(value) {
            poses[poses.size - 1] = value
        }

    fun pushPose() : Pose3dStack  {
        poses.add(pose)
        return this
    }

    fun popPose() : Pose3dStack {
        check(poses.size > 1) { "Tried to pop too many" }
        poses.removeLast()
        return this
    }

    fun identity(): Pose3dStack {
        pose = Pose3d.identity
        return this
    }

    fun rotate(rotation3d: Rotation3d) : Pose3dStack {
        pose = rotation3d * pose
        return this
    }

    fun translate(translation: Vector3d) : Pose3dStack {
        val pose = this.pose
        this.pose = pose.copy(translation = pose.translation + translation)
        return this
    }

    fun translate(x: Double, y: Double, z: Double) : Pose3dStack {
        val pose = this.pose
        val translation = pose.translation
        this.pose = pose.copy(translation = Vector3d(translation.x + x, translation.y + y, translation.z + z))
        return this
    }

    fun transform(pose3d: Pose3d) : Pose3dStack {
        pose = pose3d * pose
        return this
    }
}

class Rotation3dStack {
    private val rotations = ArrayList<Rotation3d>()

    init {
        rotations.add(Rotation3d.identity)
    }

    var rotation get() = rotations.last()
        set(value) {
            rotations[rotations.size - 1] = value
        }

    fun pushPose() : Rotation3dStack  {
        rotations.add(rotation)
        return this
    }

    fun popPose() : Rotation3dStack {
        check(rotations.size > 1) { "Tried to pop too many" }
        rotations.removeLast()
        return this
    }

    fun identity(): Rotation3dStack {
        rotation = Rotation3d.identity
        return this
    }

    fun rotate(rotation3d: Rotation3d) : Rotation3dStack {
        rotation = rotation3d * rotation
        return this
    }

    fun transform(pose3d: Pose3d) : Rotation3dStack {
        rotation = pose3d * rotation
        return this
    }
}


