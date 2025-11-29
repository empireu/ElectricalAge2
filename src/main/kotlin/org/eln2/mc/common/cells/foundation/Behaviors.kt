package org.eln2.mc.common.cells.foundation

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import org.ageseries.libage.data.*
import org.ageseries.libage.mathematics.map
import org.ageseries.libage.sim.ThermalMass
import org.ageseries.libage.sim.electrical.Port
import org.ageseries.libage.sim.kinetic.KineticDouble
import org.ageseries.libage.sim.kinetic.KineticNode
import org.ageseries.libage.sim.kinetic.KineticTriple
import org.eln2.mc.*
import org.eln2.mc.common.LightVolume
import org.eln2.mc.common.LightVolumeInstance
import org.eln2.mc.common.blocks.foundation.CellBlockEntity
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntity
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.common.events.schedulePre
import org.eln2.mc.common.specs.foundation.CellSpec
import org.eln2.mc.common.specs.foundation.SpecContainerPart
import org.eln2.mc.common.specs.foundation.SpecContainerPart.Companion.spawnDrop
import org.eln2.mc.data.Locators
import org.eln2.mc.extensions.destroyPart
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

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
     * Called when the subscriber collection is being set up.
     * Subscribers that are executed on the server thread can be added here. **The timing of these updates is designed especially for synchronization!**
     *
     * - The `Pre` updates are executed just before the simulations are dispatched, so all subscribers will see a coherent image of the simulation state.
     * - The `Post` updates are executed just after the simulations have finished and just before the bulk data is dispatched, which means this is the ideal place to synchronize.
     * */
    @OnServerThread
    fun subscribeServerThread(subscribers: SubscriberCollection) { }

    /**
     * Called when the behavior is destroyed.
     * This can be caused by the cell being destroyed.
     * It can also be caused by the game object being detached, in the case of [ReplicatorBehavior]s.
     * */
    @OnServerThread
    fun destroy() { }
}

/**
 * [CellBehavior] that is allowed to be added multiple times.
 * */
interface RepeatableCellBehavior : CellBehavior

/**
 * Container for multiple [CellBehavior]s. It is a Set. As such, there may be one instance of each behavior type.
 * */
class CellBehaviorContainer {
    val behaviors = ArrayList<CellBehavior>()

    fun addToCollection(b: CellBehavior) {
        requireIsOnServerThread { "addToCollection" }

        if(b !is RepeatableCellBehavior) {
            if (behaviors.any { it.javaClass == b.javaClass }) {
                error("Duplicate behavior $b")
            }
        }

        behaviors.add(b)
        b.onAdded(this)
    }

    fun destroy(behavior: CellBehavior) {
        requireIsOnServerThread {
            DEBUGGER_BREAK("CellBehaviorContainer#destroy")
        }

        require(behaviors.remove(behavior)) {
            DEBUGGER_BREAK("Illegal behavior remove $behavior")
        }

        behavior.destroy()
    }

    fun destroy() {
        requireIsOnServerThread {
            "CellBehaviorContainer#destroy"
        }

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

    fun sound(level: net.minecraft.world.level.Level, x: Double, y: Double, z: Double) {
        level.playSound(
            null,
            x, y, z,
            SoundEvents.GENERIC_EXPLODE,
            SoundSource.BLOCKS,
            randomFloat(0.9f, 1.1f),
            randomFloat(0.9f, 1.1f)
        )
    }

    when (container) {
        /**
         * Cell owned by a part:
         * */
        is MultipartBlockEntity -> {
            if (container.isRemoved) {
                return true
            }

            val part = container.getPart(cell.locator.requireLocator(Locators.SUBSTRATE_FACE))
                ?: return true // Already removed

            val level = part.placement.level as ServerLevel

            level.destroyPart(part, true)

            sound(
                level,
                part.placement.position.x + 0.5,
                part.placement.position.y + 0.5,
                part.placement.position.z + 0.5
            )

            return true
        }

        /**
         * Cell owned by a block entity:
         * */
        is CellBlockEntity<*> -> {
            if (container.isRemoved) {
                return true
            }

            val level = container.level as? ServerLevel
                ?: return false

            val blockPos = container.blockPos
                ?: return false

            level.destroyBlock(blockPos, true)

            sound(
                container.level!!,
                blockPos.x + 0.5,
                blockPos.y + 0.5,
                blockPos.z + 0.5
            )

            return true
        }

        /**
         * Cell owned by a spec:
         * */
        is SpecContainerPart -> {
            if(container.isRemoved) {
                return true
            }

            val spec = container.specs.values.firstOrNull {
                it is CellSpec<*> && it.hasCell && it.cell == cell
            }

            if(spec == null) {
                return false
            }

            val specTag = CompoundTag()

            // Removes spec:
            container.breakSpec(
                spec,
                specTag,
                false // Doesn't destroy the cell otherwise. Don't get fooled
            )

            // Spawns the spec item:
            spawnDrop(
                container.placement.level as ServerLevel,
                spec,
                specTag
            )

            // Destroys the spec container part:
            if(container.specs.isEmpty()) {
                container.placement.multipart.breakPart(
                    container,
                    null
                )
            }

            val bounds = spec.placement.orientedBoundingBoxWorld.center

            sound(
                container.placement.level,
                bounds.x,
                bounds.y,
                bounds.z
            )

            return true
        }

        else -> {
            error(DEBUGGER_BREAK("Cannot explode $container"))
        }
    }
}

private const val EXPLOSION_BEHAVIOR_RANDOM_FACTOR = 0.05

private fun randomizeThreshold(threshold: Double, index: Int, locator: Locator) : Double {
    val random = Random(locator.hashCode())

    repeat(index) {
        random.nextDouble()
    }

    val factor = random.nextDouble(-EXPLOSION_BEHAVIOR_RANDOM_FACTOR, EXPLOSION_BEHAVIOR_RANDOM_FACTOR)
    return threshold * (1.0 + factor)
}

private fun addTolerance(threshold: Double?, index: Int, locator: Locator) : Double? {
    if(threshold == null) {
        return null
    }

    val random = Random(31 * locator.hashCode() + index)
    val factor = random.nextDouble(-EXPLOSION_BEHAVIOR_RANDOM_FACTOR, EXPLOSION_BEHAVIOR_RANDOM_FACTOR)
    return threshold * (1.0 + factor)
}

private fun<T> addToleranceQ(q: Quantity<T>, index: Int, locator: Locator) = Quantity<T>(randomizeThreshold(!q, index, locator))

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
            LOG.error(DEBUGGER_BREAK("Getting $this explosion ticks while finalized"))
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
 * The [ThermalBreakdownBehavior] will destroy the game object if a temperature is held
 * above a threshold for a certain time period, as specified in [TemperatureExplosionBehaviorOptions]
 * A **score** is used to determine if the object should blow up. The score is increased when the temperature is above threshold
 * and decreased when the temperature is under threshold. Once a score of 1 is reached, the explosion is enqueued
 * using the [Scheduler]
 * The explosion uses an [ExplosionConsumer] to access the game object. [ExplosionConsumer.explode] is called from the game thread.
 * If no consumer is specified, a default one is used.
 * */
class ThermalBreakdownBehavior private constructor(
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
        private fun create(options: TemperatureExplosionBehaviorOptions, consumer: ExplosionConsumer, temperatureAccessor: () -> Quantity<Temperature>) =
            if(Eln2Config.serverConfig.explodeWhenHot.get()) {
                ThermalBreakdownBehavior(temperatureAccessor, options, consumer)
            }
            else {
                null
            }

        private fun create(options: TemperatureExplosionBehaviorOptions, cell: Cell, temperatureAccessor: () -> Quantity<Temperature>) =
            create(options, { defaultNotifier(cell) }, temperatureAccessor)

        fun create(
            temperature: Quantity<Temperature>,
            cell: Cell, temperatureAccessor: () -> Quantity<Temperature>
        ) = create(TemperatureExplosionBehaviorOptions(
            addToleranceQ(temperature, 0, cell.locator)), cell, temperatureAccessor)
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

class DielectricBreakdownBehavior private constructor(val locator: Locator, val options: DielectricBreakdownBehaviorOptions, consumer: ExplosionConsumer) : ExplosionBehavior(consumer) {
    val examined = ArrayList<ExaminedNPole>()
    private var i = 0

    /**
     * Adds a port for watching.
     * @param breakdownToSelf If not null, the device will break down if the potential across the [port] is higher than [breakdownToSelf].
     * @param breakdownToEarth If not null, the device will break down if the potential of any terminal of [port] relative to ground is higher than [breakdownToEarth].
     * */
    fun addPort(port: Port, breakdownToSelf: Double?, breakdownToEarth: Double?) : ExaminedPort {
        val result = ExaminedPort(
            port,
            addTolerance(breakdownToSelf, i++, locator),
            addTolerance(breakdownToEarth, i++, locator)
        )

        examined.add(result)
        return result
    }

    fun clear() {
        examined.clear()
        i = 0
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
        protected abstract fun getPotentialAcross() : Double
        protected abstract fun getPotential1() : Double
        protected abstract fun getPotential2() : Double

        override fun isBreakingDown(): Boolean {
            if(breakdownToSelf != null) {
                if(abs(getPotentialAcross()) > breakdownToSelf) {
                    return true
                }
            }

            if(breakdownToEarth != null) {
                if(abs(getPotential1()) > breakdownToEarth) {
                    return true
                }

                if(abs(getPotential2()) > breakdownToEarth) {
                    return true
                }
            }

            return false
        }
    }

    class ExaminedPort(val port: Port, breakdownToSelf: Double?, breakdownToEarth: Double?) : ExaminedBipole(breakdownToSelf, breakdownToEarth) {
        override fun getPotentialAcross() = port.potential
        override fun getPotential1() = port.negative.potential
        override fun getPotential2() = port.positive.potential
    }

    companion object {
        private fun create(
            options: DielectricBreakdownBehaviorOptions,
            cell: Cell
        ) = DielectricBreakdownBehavior(cell.locator, options) { defaultNotifier(cell) }

        fun create(cell: Cell) = create(DielectricBreakdownBehaviorOptions(), cell)

        fun createToGround(
            options: DielectricBreakdownBehaviorOptions,
            cell: Cell,
            groundBreakdownPotential: Double,
            vararg ports: Port
        ) = create(options, cell).also { behavior ->
            var i = 0
            ports.forEach {
                behavior.addPort(
                    it,
                    null,
                    randomizeThreshold(groundBreakdownPotential, i++, cell.locator)
                )
            }
        }

        fun createToGround(cell: Cell, groundBreakdownPotential: Double) = createToGround(
            DielectricBreakdownBehaviorOptions(),
            cell,
            groundBreakdownPotential
        )
    }
}

data class OverPowerBehaviorOptions(
    val powerThreshold: Quantity<Power>,

    /**
     * The score increase speed.
     * This value is **not** scaled by the difference between the power and the threshold power.
     * I've chosen not to scale it, so very small power spikes don't cause annoying explosions.
     * As a result, at breakdown, it takes `1 / [increaseSpeed]` seconds to explode, regardless of power.
     * */
    val increaseSpeed: Double = 0.75,

    /**
     * The score decrease speed. This value is not controlled by power.
     * */
    val decayRate: Double = 0.5
)

class OverPowerBehavior private constructor(
    val powerAccessor: () -> Double,
    val options: OverPowerBehaviorOptions,
    consumer: ExplosionConsumer,
) : ExplosionBehavior(consumer) {
    override fun updateScore(dt: Double, phase: SubscriberPhase) {
        val power = abs(powerAccessor())

        if (power > !options.powerThreshold) {
            score += options.increaseSpeed * dt
        } else {
            score -= options.decayRate * dt
        }
    }

    companion object {
        fun create(power: Quantity<Power>, cell: Cell, powerAccessor: () -> Double) = OverPowerBehavior(
            powerAccessor,
            OverPowerBehaviorOptions(addToleranceQ(power, 0, cell.locator))
        ) { defaultNotifier(cell) }
    }
}

data class KineticBreakdownBehaviorOptions(
    val angularVelocityThreshold: Quantity<AngularVelocity>,
    val increaseSpeed: Double = 0.75,
    val decayRate: Double = 0.5
)

class KineticBreakdownBehavior private constructor(
    val omegaAccessor: () -> Double,
    val options: KineticBreakdownBehaviorOptions,
    consumer: ExplosionConsumer
) : ExplosionBehavior(consumer) {
    init {
        interval = 0
    }

    override fun updateScore(dt: Double, phase: SubscriberPhase) {
        val speed = abs(omegaAccessor())

        if(speed > !options.angularVelocityThreshold) {
            score += options.increaseSpeed * dt
        }
        else {
            score -= options.decayRate * dt
        }
    }

    companion object {
        fun create(velocity: Quantity<AngularVelocity>, cell: Cell, omegaAccessor: () -> Double) = KineticBreakdownBehavior(
            omegaAccessor,
            KineticBreakdownBehaviorOptions(velocity)
        ) { defaultNotifier(cell) }

        fun create(velocity: Quantity<AngularVelocity>, cell: Cell, node: KineticNode) = create(
            addToleranceQ(velocity, 0, cell.locator),
            cell
        ) { node.angularVelocity }
    }
}

data class KineticStressBehaviorOptions(
    val torqueThreshold: Quantity<Torque>,
    val increaseSpeed: Double = 0.75,
    val decayRate: Double = 0.5
)

class KineticStressBehavior private constructor(
    val torqueAccessor: () -> Double,
    val options: KineticStressBehaviorOptions,
    consumer: ExplosionConsumer
) : ExplosionBehavior(consumer) {
    init {
        interval = 0
    }

    override fun updateScore(dt: Double, phase: SubscriberPhase) {
        val torque = abs(torqueAccessor())

        if(torque > !options.torqueThreshold) {
            score += (torque / !options.torqueThreshold) * options.increaseSpeed * dt
        }
        else {
            score -= options.decayRate * dt
        }
    }

    companion object {
        fun create(torque: Quantity<Torque>, cell: Cell, node: KineticDouble) = KineticStressBehavior(
            {
                max(
                    abs(node.e1.impulse / node.simulation.dt),
                    abs(node.e2.impulse / node.simulation.dt)
                )
            },
            KineticStressBehaviorOptions(addToleranceQ(torque, 0, cell.locator)),
            { defaultNotifier(cell) }
        )

        fun create(torque: Quantity<Torque>, cell: Cell, node: KineticTriple) = KineticStressBehavior(
            {
                if(!node.isInSimulation) {
                    DEBUGGER_BREAK(0.0)
                }
                else {
                    max(
                        abs(node.e1.impulse / node.simulation.dt),
                        max(
                            abs(node.e2.impulse / node.simulation.dt),
                            abs(node.e3.impulse / node.simulation.dt)
                        )
                    )
                }
            },
            KineticStressBehaviorOptions(addToleranceQ(torque, 0, cell.locator)),
            { defaultNotifier(cell) }
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
