package org.eln2.mc.common.cells.foundation

import it.unimi.dsi.fastutil.objects.Reference2DoubleArrayMap
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.Temperature
import org.ageseries.libage.mathematics.approxEq
import org.ageseries.libage.sim.ThermalMass
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.*

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Replicator

/**
 * Represents a specialized [CellBehavior], that only exists when the game object also exists.
 * */
interface ReplicatorBehavior : CellBehavior

object Replicators {
    fun replicatorScan(cellK: KClass<*>, containerK: KClass<*>, cellInst: Any, containerInst: Any) = getReplicators(cellK, containerK).mapNotNull { it.create(cellInst, containerInst) }

    private fun interface ReplicatorFactory {
        fun create(cellInst: Any, containerInst: Any): ReplicatorBehavior?
    }

    private val replicators = ConcurrentHashMap<Pair<KClass<*>, KClass<*>>, List<ReplicatorFactory>>()

    private fun getReplicators(cellK: KClass<*>, containerK: KClass<*>): List<ReplicatorFactory> =
        replicators.getOrPut(Pair(cellK, containerK)) {
            val functions = cellK.memberFunctions.filter { it.hasAnnotation<Replicator>() }

            val results = ArrayList<ReplicatorFactory>()

            functions.forEach {
                if (!(it.returnType.classifier as KClass<*>).isSubclassOf(ReplicatorBehavior::class)) {
                    error("Invalid return type of $it")
                }

                if (it.parameters.size != 2) {
                    error("Invalid parameter count of $it")
                }
            }

            functions.forEach {
                val containerParam = it.parameters[1]

                if ((containerParam.type.classifier as KClass<*>).isSuperclassOf(containerK)) {
                    results.add { self, rxContainerTarget ->
                        it.call(self, rxContainerTarget) as? ReplicatorBehavior
                    }
                }
            }

            results
        }
}

fun interface InternalTemperatureConsumer {
    fun onInternalTemperatureChanges(dirty: List<ThermalMass>)
}

fun interface ExternalTemperatureConsumer {
    fun onExternalTemperatureChanges(
        removed: HashSet<ThermalObject<*>>,
        dirty: HashMap<ThermalObject<*>, Double>,
        all: HashMap<ThermalObject<*>, Double>
    )
}

/**
 * Generalized behavior for sending temperature changes to clients (for e.g. rendering hot bodies)
 * @param bodies The list of bodies to track.
 * @param consumer The consumer for the changes.
 * */
class InternalTemperatureReplicatorBehavior(
    val bodies: List<ThermalMass>,
    val consumer: InternalTemperatureConsumer
) : ReplicatorBehavior {
    var scanInterval: Int = 5
    var scanPhase: SubscriberPhase = SubscriberPhase.Pre
    var tolerance: Double = 1.0

    private val tracked = Reference2DoubleArrayMap<ThermalMass>()

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addSubscriber(SubscriberOptions(scanInterval, scanPhase), this::scan)
    }

    private fun scan(dt: Double, phase: SubscriberPhase) {
        val dirty = bodies.filter {
            !tracked.getDouble(it).approxEq(!it.temperature, tolerance)
        }

        if (dirty.isEmpty()) {
            return
        }

        dirty.forEach {
            tracked[it] = !it.temperature
        }

        consumer.onInternalTemperatureChanges(dirty)
    }
}

/**
 * Generalized behavior for sending temperature changes of connected thermal objects to clients.
 * The temperatures are read from the [TemperatureField] of neighbor objects.
 * @param cell The cell that owns this behavior.
 * @param consumer The consumer for the changes.
 * */
class ExternalTemperatureReplicatorBehavior(
    val cell: Cell,
    val consumer: ExternalTemperatureConsumer
) : ReplicatorBehavior {
    var scanInterval: Int = 5
    var scanPhase: SubscriberPhase = SubscriberPhase.Pre
    var tolerance: Double = 1.0

    /**
     * Holds the last seen temperature for the remote thermal object.
     * */
    private val trackedObjects = HashMap<ThermalObject<*>, Double>()

    /**
     * Used to find which thermal objects have been "disconnected".
     * At the start of the scan, the map is filled with all tracked objects.
     * Then, as neighbors are found, they are removed from this map.
     * What is left over is what no longer is connected to this cell.
     * */
    private val lostObjects = HashSet<ThermalObject<*>>()

    /**
     * Holds the objects that have changed their temperature last scan.
     * */
    private val dirty = HashMap<ThermalObject<*>, Double>()

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addSubscriber(SubscriberOptions(scanInterval, scanPhase), this::scan)
    }

    private fun scan(dt: Double, phase: SubscriberPhase) {
        lostObjects.clear()
        lostObjects.addAll(trackedObjects.keys)
        dirty.clear()

        scanNeighbors(cell) { remoteThermalObject, actualTemperature ->
            lostObjects.remove(remoteThermalObject)

            val previousTemperature = trackedObjects[remoteThermalObject]

            if(previousTemperature == null || !previousTemperature.approxEq(!actualTemperature, tolerance)) {
                trackedObjects[remoteThermalObject] = !actualTemperature
                dirty[remoteThermalObject] = !actualTemperature
            }
        }

        if(lostObjects.isNotEmpty() || dirty.isNotEmpty()) {
            lostObjects.forEach {
                trackedObjects.remove(it)
            }

            consumer.onExternalTemperatureChanges(
                HashSet(lostObjects),
                HashMap(dirty),
                HashMap(trackedObjects)
            )
        }
    }

    companion object {
        inline fun scanNeighbors(cell: Cell, crossinline consumer: (ThermalObject<*>, Quantity<Temperature>) -> Unit) {
            for(remoteCell in cell.connections) {
                val remoteThermalObject = remoteCell.objects.getObjectOrNull(SimulationObjectType.Thermal) as? ThermalObject
                    ?: continue

                val contactInfo: ThermalContactInfo = if(remoteThermalObject is ThermalContactInfo) {
                    remoteThermalObject
                } else if(remoteThermalObject.cell is ThermalContactInfo) {
                    remoteThermalObject.cell
                } else {
                    continue
                }

                val temperature = contactInfo.getContactTemperature(cell)

                if(temperature != null) {
                    consumer(remoteThermalObject, temperature)
                }
            }
        }
    }
}
