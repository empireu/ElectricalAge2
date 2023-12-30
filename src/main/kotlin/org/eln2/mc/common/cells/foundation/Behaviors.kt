package org.eln2.mc.common.cells.foundation

import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.sim.ThermalMass
import org.eln2.mc.*
import org.eln2.mc.common.LightVolume
import org.eln2.mc.common.LightVolumeInstance
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.LocatorLightVolumeProvider
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.common.events.schedulePre
import org.eln2.mc.common.parts.foundation.CellPart
import org.eln2.mc.data.*
import org.eln2.mc.extensions.destroyPart
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import kotlin.collections.ArrayList

/**
 * *A cell behavior* manages routines ([Subscriber]) that run on the simulation thread.
 * It is attached to a cell.
 * */
interface CellBehavior {
    /**
     * Called when the behavior is added to the container.
     * */
    @OnServerThread
    fun onAdded(container: CellBehaviorContainer) {}

    /**
     * Called when the subscriber collection is being set up.
     * Subscribers can be added here.
     * */
    @OnServerThread
    fun subscribe(subscribers: SubscriberCollection) {}

    /**
     * Called when the behavior is destroyed.
     * This can be caused by the cell being destroyed.
     * It can also be caused by the game object being detached, in the case of [ReplicatorBehavior]s.
     * */
    @OnServerThread
    fun destroy() {}
}

/**
 * Container for multiple [CellBehavior]s. It is a Set. As such, there may be one instance of each behavior type.
 * */
class CellBehaviorContainer {
    val behaviors = ArrayList<CellBehavior>()

    fun addToCollection(b: CellBehavior) {
        requireIsOnServerThread { "addToCollection" }

        if (behaviors.any { it.javaClass == b.javaClass }) {
            error("Duplicate behavior $b")
        }

        behaviors.add(b)
        b.onAdded(this)
    }

    fun forEach(action: ((CellBehavior) -> Unit)) = behaviors.forEach(action)
    inline fun <reified T : CellBehavior> getOrNull(): T? = behaviors.first { it is T } as? T
    inline fun <reified T : CellBehavior> get(): T = getOrNull() ?: error("Failed to get behavior")

    fun destroy(behavior: CellBehavior) {
        requireIsOnServerThread { "destroy" }
        require(behaviors.remove(behavior)) { "Illegal behavior remove $behavior" }
        behavior.destroy()
    }

    fun destroy() {
        requireIsOnServerThread { "destroy" }
        behaviors.toList().forEach { destroy(it) }
    }
}

/**
 * Converts dissipated electrical energy to thermal energy.
 * */
class PowerHeatingBehavior(private val power: () -> Double, val body: ThermalMass) : CellBehavior {
    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addPre(this::simulationTick)
    }

    private fun simulationTick(dt: Double, p: SubscriberPhase) {
        body.energy += Quantity(power() * dt, JOULE)
    }
}

fun interface ExplosionConsumer {
    fun explode() : Boolean // return false if game object is not
}

data class TemperatureExplosionBehaviorOptions(
    /**
     * If the temperature is above this threshold, [increaseSpeed] will be used to increase the explosion score.
     * Otherwise, [decayRate] will be used to decrease it.
     * */
    val temperatureThreshold: Quantity<Temperature> = Quantity(350.0, KELVIN),

    /**
     * The score increase speed.
     * This value is scaled by the difference between the temperature and the threshold.
     * */
    val increaseSpeed: Double = 0.1,

    /**
     * The score decrease speed. This value is not controlled by temperature.
     * */
    val decayRate: Double = 0.25,
)

/**
 * The [TemperatureExplosionBehavior] will destroy the game object if a temperature is held
 * above a threshold for a certain time period, as specified in [TemperatureExplosionBehaviorOptions]
 * A **score** is used to determine if the object should blow up. The score is increased when the temperature is above threshold
 * and decreased when the temperature is under threshold. Once a score of 1 is reached, the explosion is enqueued
 * using the [Scheduler]
 * The explosion uses an [ExplosionConsumer] to access the game object. [ExplosionConsumer.explode] is called from the game thread.
 * If no consumer is specified, a default one is used. Currently, only [CellPart] is implemented.
 * Injection is supported using [TemperatureAccessor], [TemperatureField]
 * */
class TemperatureExplosionBehavior private constructor(
    val temperatureAccessor: () -> Quantity<Temperature>,
    val options: TemperatureExplosionBehaviorOptions,
    val consumer: ExplosionConsumer,
) : CellBehavior {
    var interval = 10
    var phase = SubscriberPhase.Post

    private var score = 0.0
    private var isTriggered = false

    private enum class State {
        Enqueued,
        Failed,
        Success
    }

    @OnServerThread @OnSimulationThread
    private val explosionResult = AtomicReference<State>(null)
    private var isGameObjectExploded = false

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addSubscriber(
            SubscriberOptions(interval, phase),
            this::simulationTick
        )
    }

    private fun simulationTick(dt: Double, phase: SubscriberPhase) {
        if(isTriggered) {
            updateTriggeredState()
        }
        else {
            val temperature = temperatureAccessor()

            if (temperature > options.temperatureThreshold) {
                val difference = temperature - options.temperatureThreshold
                score += options.increaseSpeed * !difference * dt
            } else {
                score -= options.decayRate * dt
            }

            score = score.coerceIn(0.0, 1.0)

            if (score >= 1.0) {
                isTriggered = true
                updateTriggeredState()
            }
        }
    }

    private fun updateTriggeredState() {
        if(isGameObjectExploded) {
            LOG.error("Getting explosion ticks while finalized")
            return // weird that we're still getting ticks
        }

        when (val result = explosionResult.get()) {
            null, State.Failed -> {
                explosionResult.set(State.Enqueued)

                schedulePre(0) {
                    explosionResult.set(
                        if(consumer.explode()) {
                            State.Success
                        }
                        else {
                            State.Failed
                        }
                    )
                }
            }
            State.Success -> {
                isGameObjectExploded = true
            }
            else -> {
                check(result == State.Enqueued)
            }
        }
    }

    companion object {
        fun create(options: TemperatureExplosionBehaviorOptions, consumer: ExplosionConsumer, temperatureAccessor: () -> Quantity<Temperature>) =
            if(Eln2Config.serverConfig.explodeWhenHot.get()) {
                TemperatureExplosionBehavior(temperatureAccessor, options, consumer)
            }
            else {
                null
            }

        fun create(
            options: TemperatureExplosionBehaviorOptions,
            cell: Cell,
            temperatureAccessor: () -> Quantity<Temperature>
        ) = create(options, { defaultNotifier(cell) }, temperatureAccessor)

        private fun defaultNotifier(cell: Cell) : Boolean {
            val container = cell.container ?: return false

            if (container is MultipartBlockEntity) {
                if (container.isRemoved) {
                    return true
                }

                val part = container.getPart(cell.locator.requireLocator(Locators.FACE))
                    ?: return true

                val level = (part.placement.level as ServerLevel)

                level.destroyPart(part, true)

                level.playSound(
                    null,
                    part.placement.position.x + 0.5,
                    part.placement.position.y + 0.5,
                    part.placement.position.z + 0.5,
                    SoundEvents.GENERIC_EXPLODE,
                    SoundSource.BLOCKS,
                    randomFloat(0.9f, 1.1f),
                    randomFloat(0.9f, 1.1f)
                )

                return true
            }
            else {
                error("Cannot explode $container")
            }
        }
    }
}

data class RadiantBodyEmissionDescription(
    val volumeProvider: (Cell) -> LightVolume,
    val coldTemperature: Quantity<Temperature> = Quantity(300.0, CELSIUS),
    val hotTemperature: Quantity<Temperature> = Quantity(800.0, CELSIUS)
)

class RadiantEmissionBehavior private constructor(val cell: Cell, bodies: Map<ThermalMass, RadiantBodyEmissionDescription>) : CellBehavior {
    private var isDestroyed = false

    init {
        require(cell.hasGraph) {
            "Illegal initialization of radiant emission behavior. Please move this into a Lazy<RadiantEmissionBehavior>"
        }
    }

    private val instances = bodies.map { (mass, description) ->
        require(description.coldTemperature < description.hotTemperature) {
            "Tried to create with ${description.coldTemperature} ${description.hotTemperature}"
        }

        val volume = description.volumeProvider(cell)

        val instance = LightVolumeInstance(
            cell.graph.level,
            cell.locator.requireLocator(Locators.BLOCK) {
                "Radiant Emission Behavior requires block pos locator"
            }
        )

        InstanceData(
            mass,
            volume,
            description.coldTemperature,
            description.hotTemperature,
            instance
        )
    }

    var interval = 10
    var phase = SubscriberPhase.Post

    override fun subscribe(subscribers: SubscriberCollection) {
        subscribers.addSubscriber(
            SubscriberOptions(interval, phase),
            this::simulationTick
        )
    }

    private fun simulationTick(dt: Double, phase: SubscriberPhase) {
        val commandList = Scheduler.begin()

        commandList.terminateIf {
            this.isDestroyed
        }

        instances.forEach { instance ->
            val targetIncrement = map(
                (!instance.mass.temperature).coerceIn(
                    !instance.coldTemperature,
                    !instance.hotTemperature
                ),
                !instance.coldTemperature,
                !instance.hotTemperature,
                0.0,
                instance.volume.stateIncrements.toDouble()
            ).toInt().coerceIn(0, instance.volume.stateIncrements)

            if(instance.lightInstance.isTransition(instance.volume, targetIncrement)) {
                commandList.execute {
                    check(!isDestroyed)

                    instance.lightInstance.checkoutState(
                        instance.volume,
                        targetIncrement
                    )
                }
            }
        }

        commandList.submit()
    }

    override fun destroy() {
        requireIsOnServerThread()

        isDestroyed = true

        instances.forEach {
            it.lightInstance.destroyCells()
        }
    }

    private data class InstanceData(
        val mass: ThermalMass,
        val volume: LightVolume,
        val coldTemperature: Quantity<Temperature>,
        val hotTemperature: Quantity<Temperature>,
        val lightInstance: LightVolumeInstance
    )

    companion object {
        fun create(cell: Cell, vararg bodies: Pair<ThermalMass, RadiantBodyEmissionDescription>?) =
            if(Eln2Config.serverConfig.hotRadiatesLight.get()) {
                lazy { RadiantEmissionBehavior(cell, bodies.filterNotNull().toMap()) }
            }
            else {
                null
            }
    }
}
