package org.eln2.mc.common.cells.foundation

import org.ageseries.libage.data.mutableMultiMapOf
import kotlin.math.max

/**
 * Represents a function that is executed periodically from the simulation thread.
 * */
fun interface SimulationSubscriber<Phase> {
    /**
     * Called when the simulation updates.
     * @param dt The fixed time step.
     * @param phase The update phase, as specified in [SubscriberOptions].
     * */
    fun update(dt: Double, phase: Phase)
}

/**
 * Execution points during the simulation.
 * */
enum class SimulationPhase {
    /**
     * Called before all subsolvers are dispatched in parallel.
     * */
    Pre,
    /**
     * Called after all subsolvers have finished.
     * */
    Post
}

/**
 * Execution points of the server's loop called by forge events.
 * */
enum class ServerPhase {
    /**
     * Called when [net.minecraftforge.event.TickEvent.ServerTickEvent], [net.minecraftforge.event.TickEvent.Phase.START] is received:
     * - After the [org.eln2.mc.common.events.Scheduler] is executed
     * - **Before any simulations are dispatched**. Use this fact to execute all Game State <-> Cell State changes without any locking.
     * */
    Start,
    /**
     * Called right after [Start] is dispatched, if algorithms need a second pass.
     * */
    AfterStart1,
    /**
     * Called right after [AfterStart1] is dispatched, if algorithms need a third pass.
     * */
    AfterStart2,
    /**
     * Called when [net.minecraftforge.event.TickEvent.ServerTickEvent], [net.minecraftforge.event.TickEvent.Phase.END] is received:
     * - After the [org.eln2.mc.common.events.Scheduler] is executed
     * - After the simulations are all awaited
     * - Before the bulk messages are flushed
     * - Before the fluid pipes are updated
     * */
    End,
    /**
     * Called right after [End] is dispatched, if algorithms need a second pass.
     * */
    AfterEnd
}

/**
 * Describes the execution policy of a subscriber.
 * @param interval The interval, in ticks.
 * @param phase The update phase to listen for.
 * */
data class SubscriberOptions<Phase>(val interval: Int, val phase: Phase)

/**
 * The Subscriber Collection is used to manage sets of subscribers, with different execution policies.
 * [SubscriberOptions] will be used to choose a [SubscriberPool].
 * */
class SubscriberPool<Phase> : SubscriberCollection<Phase> {
    private val pools = HashMap<SubscriberOptions<Phase>, SubscriberPool<Phase>>()
    private val subscribers = mutableMultiMapOf<SimulationSubscriber<Phase>, SubscriberPool<Phase>>()

    private var iterating = false

    private val updates = ArrayDeque<Update>()

    val poolCount get() = pools.size
    val subscriberCount get() = subscribers.keyMappingSize

    private fun getPool(parameters: SubscriberOptions<Phase>): SubscriberPool<Phase> {
        return pools.computeIfAbsent(parameters) { SubscriberPool(parameters) }
    }

    fun hasPool(parameters: SubscriberOptions<Phase>): Boolean {
        return pools.containsKey(parameters)
    }

    private fun applyUpdate(update: Update) {
        when (update) {
            is AddUpdate<*> -> {
                @Suppress("UNCHECKED_CAST")
                val subscriber = update.subscriber as SimulationSubscriber<Phase>
                @Suppress("UNCHECKED_CAST")
                val parameters = update.parameters as SubscriberOptions<Phase>
                val pool = getPool(parameters)

                pool.add(subscriber)

                subscribers[subscriber].add(pool)
            }

            is RemoveAllUpdate<*> -> {
                @Suppress("UNCHECKED_CAST")
                val subscriber = update.subscriber as SimulationSubscriber<Phase>

                subscribers[subscriber].forEach { pool ->
                    pool.remove(subscriber)

                    if (pool.isEmpty) {
                        pools.remove(pool.parameters)
                    }
                }

                subscribers.clear(subscriber)
            }

            else -> {
                error("Unknown update $update")
            }
        }
    }

    private fun enqueueOrApply(update: Update) {
        if (iterating) {
            updates.add(update)
        } else {
            applyUpdate(update)
        }
    }

    override fun addSubscriber(parameters: SubscriberOptions<Phase>, subscriber: SimulationSubscriber<Phase>) {
        enqueueOrApply(AddUpdate(subscriber, parameters))
    }

    override fun remove(subscriber: SimulationSubscriber<Phase>) {
        enqueueOrApply(RemoveAllUpdate(subscriber))
    }

    fun update(dt: Double, phase: Phase) {
        iterating = true

        pools.values
            .filter { it.parameters.phase == phase }
            .forEach { it.update(dt) }

        iterating = false

        updates.forEach { applyUpdate(it) }
        updates.clear()
    }

    private interface Update
    private class AddUpdate<Phase>(val subscriber: SimulationSubscriber<Phase>, val parameters: SubscriberOptions<Phase>) : Update
    private class RemoveAllUpdate<Phase>(val subscriber: SimulationSubscriber<Phase>) : Update

    class SubscriberPool<Phase>(val parameters: SubscriberOptions<Phase>) {
        private val pool = ArrayList<SimulationSubscriber<Phase>>()
        private var isIterating = false

        val isEmpty get() = pool.isEmpty()
        val size get() = pool.size

        private var countdown = parameters.interval

        fun update(dtId: Double): Boolean {
            try {
                val dt = dtId * max(parameters.interval, 1)

                if (--countdown <= 0) {
                    countdown = parameters.interval
                    isIterating = true
                    for (sub in pool) {
                        sub.update(dt, parameters.phase)
                    }
                    isIterating = false
                    return true
                }

                return false
            }
            finally {
                isIterating = false
            }
        }

        fun add(subscriber: SimulationSubscriber<Phase>) {
            require(!isIterating) { "Tried to add subscriber $subscriber while iterating" }

            if (pool.contains(subscriber)) {
                error("Duplicate add $subscriber in $parameters")
            }

            pool.add(subscriber)
        }

        fun remove(subscriber: SimulationSubscriber<Phase>) {
            require(!isIterating) { "Tried to remove subscriber $subscriber while iterating" }

            if (!pool.remove(subscriber)) {
                error("Failed to remove $subscriber from $parameters")
            }
        }
    }
}

interface SubscriberCollection<Phase> {
    fun addSubscriber(parameters: SubscriberOptions<Phase>, subscriber: SimulationSubscriber<Phase>)

    fun remove(subscriber: SimulationSubscriber<Phase>)
}

/**
 * Adds a subscriber that runs on [SimulationPhase.Pre] every tick (interval is 0).
 * */
fun SubscriberCollection<SimulationPhase>.addPre(subscriber: SimulationSubscriber<SimulationPhase>) {
    this.addSubscriber(SubscriberOptions(0, SimulationPhase.Pre), subscriber)
}

/**
 * Adds a subscriber that runs on [SimulationPhase.Post] every tick (interval is 0).
 * */
fun SubscriberCollection<SimulationPhase>.addPost(subscriber: SimulationSubscriber<SimulationPhase>) {
    this.addSubscriber(SubscriberOptions(0, SimulationPhase.Post), subscriber)
}

/**
 * Adds a subscriber that runs on [ServerPhase.Start] every tick (interval is 0).
 * */
fun SubscriberCollection<ServerPhase>.addStart(subscriber: SimulationSubscriber<ServerPhase>) {
    this.addSubscriber(SubscriberOptions(0, ServerPhase.Start), subscriber)
}

/**
 * Adds a subscriber that runs on [ServerPhase.AfterStart1] every tick (interval is 0).
 * */
fun SubscriberCollection<ServerPhase>.addAfterStart1(subscriber: SimulationSubscriber<ServerPhase>) {
    this.addSubscriber(SubscriberOptions(0, ServerPhase.AfterStart1), subscriber)
}

/**
 * Adds a subscriber that runs on [ServerPhase.AfterStart2] every tick (interval is 0).
 * */
fun SubscriberCollection<ServerPhase>.addAfterStart2(subscriber: SimulationSubscriber<ServerPhase>) {
    this.addSubscriber(SubscriberOptions(0, ServerPhase.AfterStart2), subscriber)
}

/**
 * Adds a subscriber that runs on [ServerPhase.End] every tick (interval is 0).
 * */
fun SubscriberCollection<ServerPhase>.addEnd(subscriber: SimulationSubscriber<ServerPhase>) {
    this.addSubscriber(SubscriberOptions(0, ServerPhase.End), subscriber)
}
