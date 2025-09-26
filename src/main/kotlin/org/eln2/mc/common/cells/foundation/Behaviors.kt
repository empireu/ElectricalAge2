package org.eln2.mc.common.cells.foundation

import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.electrical.mna.VirtualResistor
import org.ageseries.libage.sim.electrical.mna.component.Port
import org.eln2.mc.*
import org.eln2.mc.common.LightVolume
import org.eln2.mc.common.LightVolumeInstance
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.common.events.schedulePre
import org.eln2.mc.data.Locators
import org.eln2.mc.extensions.destroyPart
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * *A cell behavior* manages routines ([SimulationSubscriber]) that run on the simulation thread.
 * It is attached to a cell.
 * */
interface CellBehavior {
    /**
     * Called when the behavior is added to the container.
     * */
    @OnServerThread
    fun onAdded(container: CellBehaviorContainer) { }

    /**
     * Called when the subscriber collection is being set up.
     * Subscribers can be added here.
     * */
    @OnServerThread
    fun subscribe(subscribers: SubscriberCollection) { }

    /**
     * Called when the behavior is destroyed.
     * This can be caused by the cell being destroyed.
     * It can also be caused by the game object being detached, in the case of [ReplicatorBehavior]s.
     * */
    @OnServerThread
    fun destroy() { }
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

private fun defaultNotifier(cell: Cell) : Boolean {
    val container = cell.container ?: return false

    if (container is MultipartBlockEntity) {
        if (container.isRemoved) {
            return true
        }

        val part = container.getPart(cell.locator.requireLocator(Locators.FACE))
            ?: return true // Already removed

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

abstract class ExplosionBehavior(private val consumer: ExplosionConsumer) : CellBehavior {
    var interval = 10
    var phase = SubscriberPhase.Post

    // Not saved, I didn't think it would be worthwhile
    // Only some weird chunk unloading could cause this to matter
    protected var score = 0.0
    private var isTriggered = false

    private enum class State {
        Enqueued,
        Failed,
        Success
    }

    @OnServerThread @OnSimulationThread
    private val explosionResult = AtomicReference<State>(null)
    private var isGameObjectExploded = false

    final override fun subscribe(subscribers: SubscriberCollection) {
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
            updateScore(dt, phase)

            if(score.isNaN() || score.isInfinite()) {
                LOG.error("Explosion behavior $this generated a score of $score")
                score = 1.0 // explode
            }

            score = score.coerceIn(0.0, 1.0)

            if (score >= 1.0) {
                isTriggered = true
                updateTriggeredState()
            }
        }
    }

    protected abstract fun updateScore(dt: Double, phase: SubscriberPhase)

    private fun updateTriggeredState() {
        if(isGameObjectExploded) {
            LOG.error("Getting $this explosion ticks while finalized")
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
                check(result == State.Enqueued) {
                    "Explosion state was not enqueued!"
                }
            }
        }
    }
}

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
    consumer: ExplosionConsumer,
) : ExplosionBehavior(consumer) {
    override fun updateScore(dt: Double, phase: SubscriberPhase) {
        val temperature = temperatureAccessor()

        if (temperature > options.temperatureThreshold) {
            val difference = temperature - options.temperatureThreshold
            score += options.increaseSpeed * !difference * dt
        } else {
            score -= options.decayRate * dt
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

        fun create(options: TemperatureExplosionBehaviorOptions, cell: Cell, temperatureAccessor: () -> Quantity<Temperature>) = create(options, { defaultNotifier(cell) }, temperatureAccessor)

        fun create(temperature: Quantity<Temperature>, cell: Cell, temperatureAccessor: () -> Quantity<Temperature>) = create(TemperatureExplosionBehaviorOptions(temperature), cell, temperatureAccessor)
    }
}

data class DielectricBreakdownBehaviorOptions(
    /**
     * The score increase speed.
     * This value is **not** scaled by the difference between the potential and the threshold.
     * I've chosen not to scale it, so very small potential spikes don't cause annoying explosions.
     * As a result, at breakdown, it takes `1 / [increaseSpeed]` seconds to explode, regardless of potential.
     * */
    val increaseSpeed: Double = 0.25,

    /**
     * The score decrease speed. This value is not controlled by potential.
     * */
    val decayRate: Double = 0.5,

    // Safeguard to detect unremoved objects, only for components that actually change (wires).
    val maxObjects: Int = 25
)

class DielectricBreakdownBehavior(
    val options: DielectricBreakdownBehaviorOptions,
    consumer: ExplosionConsumer
) : ExplosionBehavior(consumer) {
    val examined = ArrayList<ExaminedNPole>()

    /**
     * Adds a port for watching.
     * @param breakdownToSelf If not null, the device will break down if the potential across the [port] is higher than [breakdownToSelf].
     * @param breakdownToEarth If not null, the device will break down if the potential of any terminal of [port] relative to ground is higher than [breakdownToEarth].
     * */
    fun addPort(port: Port, breakdownToSelf: Double?, breakdownToEarth: Double?) : ExaminedPort {
        val result = ExaminedPort(port, breakdownToSelf, breakdownToEarth)
        examined.add(result)
        return result
    }

    fun addResistor(virtualResistor: VirtualResistor, breakdownToSelf: Double?, breakdownToEarth: Double?) : ExaminedVirtualResistor {
        val result = ExaminedVirtualResistor(virtualResistor, breakdownToSelf, breakdownToEarth)
        examined.add(result)
        return result
    }

    fun clear() {
        examined.clear()
    }

    override fun updateScore(dt: Double, phase: SubscriberPhase) {
        if(examined.size > options.maxObjects) {
            error("Lingering dielectric breakdowns! ${examined.size}")
        }

        val isBreakingDown = examined.any {
            it.isBreakingDown()
        }

        if (isBreakingDown) {
            score += options.increaseSpeed * dt
        } else {
            score -= options.decayRate * dt
        }
    }

    /**
     * An examined N-Pole. Each pole can be compared to ground for breakdown. And each pole can be compared to each other pole for breakdown to self.
     * */
    interface ExaminedNPole {
        /**
         * Checks if the current potential difference across the poles, or the potential to ground causes a breakdown.
         * */
        fun isBreakingDown() : Boolean
    }

    /**
     * An examined bipole.
     * @param breakdownToSelf If not null, the potential difference across the two poles will be taken into account.
     * @param breakdownToEarth If not null, the potential difference to ground will be taken into account.
     * */
    abstract class ExaminedBipole(val breakdownToSelf: Double?, val breakdownToEarth: Double?) : ExaminedNPole {
        protected abstract fun getPotentialAcross() : Double?
        protected abstract fun getPotential1() : Double?
        protected abstract fun getPotential2() : Double?

        override fun isBreakingDown(): Boolean {
            if(breakdownToSelf != null) {
                if(abs(getPotentialAcross() ?: 0.0) > breakdownToSelf) {
                    return true
                }
            }

            if(breakdownToEarth != null) {
                if(abs(getPotential1() ?: 0.0) > breakdownToEarth) {
                    return true
                }

                if(abs(getPotential2() ?: 0.0) > breakdownToEarth) {
                    return true
                }
            }

            return false
        }
    }

    class ExaminedPort(val port: Port, breakdownToSelf: Double?, breakdownToEarth: Double?) : ExaminedBipole(breakdownToSelf, breakdownToEarth) {
        override fun getPotentialAcross() = port.potential
        override fun getPotential1() = port.neg?.potential
        override fun getPotential2() = port.pos?.potential
    }

    class ExaminedVirtualResistor(val resistor: VirtualResistor, breakdownToSelf: Double?, breakdownToEarth: Double?) : ExaminedBipole(breakdownToSelf, breakdownToEarth) {
        override fun getPotentialAcross() = resistor.potential
        override fun getPotential1() = resistor.part?.negPotential
        override fun getPotential2() = resistor.part?.posPotential
    }

    companion object {
        fun create(
            options: DielectricBreakdownBehaviorOptions,
            cell: Cell
        ) = DielectricBreakdownBehavior(options, { defaultNotifier(cell) })

        fun create(cell: Cell) = create(DielectricBreakdownBehaviorOptions(), cell)

        fun createToGround(
            options: DielectricBreakdownBehaviorOptions,
            cell: Cell,
            groundBreakdownPotential: Double,
            vararg ports: Port
        ) = create(options, cell).also { behavior ->
            ports.forEach {
                behavior.addPort(it, null, groundBreakdownPotential)
            }
        }

        fun createToGround(cell: Cell, groundBreakdownPotential: Double) = createToGround(
            DielectricBreakdownBehaviorOptions(),
            cell,
            groundBreakdownPotential
        )
    }
}

data class RadiantBodyEmissionDescription(
    val volumeProvider: (Cell) -> LightVolume,
    val coldTemperature: Quantity<Temperature> = Quantity(300.0, CELSIUS),
    val hotTemperature: Quantity<Temperature> = Quantity(800.0, CELSIUS)
) {
    constructor(
        volume: LightVolume,
        coldTemperature: Quantity<Temperature> = Quantity(300.0, CELSIUS),
        hotTemperature: Quantity<Temperature> = Quantity(800.0, CELSIUS)) : this(
            { volume },
            coldTemperature,
            hotTemperature
        )
}

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
