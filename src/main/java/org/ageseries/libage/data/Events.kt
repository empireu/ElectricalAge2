package org.ageseries.libage.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

/**
 * Producer of events which are distributed to [EventHandler]s.
 * */
interface EventSource {
    /**
     * Adds a [handler] for the event [eventClass].
     * */
    fun registerHandler(eventClass: KClass<*>, handler: EventHandler<Event>)

    /**
     * Removes [handler] for the event [eventClass].
     * */
    fun unregisterHandler(eventClass: KClass<*>, handler: EventHandler<Event>)
}

/**
 * Dispatcher for events of different types.
 * */
interface EventDispatcher {
    /**
     * Sends the [event] to all handlers capable of handling it.
     * @return The number of handlers which received the event.
     * */
    fun send(event: Event): Int
}

/**
 * The Event Bus oversees a collection of event handlers.
 * When an event is dispatched, it is relayed to all handlers capable of managing that specific event type.
 * Currently, event polymorphism is not implemented so a handler must be registered with the exact class of the event.
 * The system is designed with thread safety in mind.
 * @param allowList An optional whitelist of event classes. If a handler for an event which is not allowed is registered, an error is raised. Same goes for events that are sent.
 * */
class EventBus(private val allowList: Set<KClass<*>>? = null) : EventSource, EventDispatcher {
    private val handlers = ConcurrentHashMap<KClass<*>, CopyOnWriteArrayList<EventHandler<Event>>>()

    private fun validateEvent(eventClass: KClass<*>) {
        if (allowList != null) {
            check(allowList.contains(eventClass)) {
                "The event manager prohibits $eventClass"
            }
        }

    }

    override fun registerHandler(eventClass: KClass<*>, handler: EventHandler<Event>) {
        validateEvent(eventClass)

        val handlers = handlers.computeIfAbsent(eventClass) {
            CopyOnWriteArrayList()
        }

        handlers.add(handler)
    }

    override fun unregisterHandler(eventClass: KClass<*>, handler: EventHandler<Event>) {
        validateEvent(eventClass)

        val handlers = handlers[eventClass]
            ?: error("Could not find handlers for $eventClass")

        if (!handlers.remove(handler)) {
            error("Could not remove handler $handler")
        }
    }

    /**
     * Sends an event to all subscribed listeners.
     * */
    override fun send(event: Event): Int {
        validateEvent(event.javaClass.kotlin)

        val listeners = this.handlers[event::class]
            ?: return 0

        var visited = 0
        listeners.forEach {
            it.handle(event)
            ++visited
        }

        return visited
    }
}

/**
 * Registers an event handler for events of type [TEvent].
 * */
@Suppress("UNCHECKED_CAST")
inline fun <reified TEvent : Event> EventSource.registerHandler(handler: EventHandler<TEvent>) {
    registerHandler(TEvent::class, handler as EventHandler<Event>)
}

/**
 * Removes an event handler for events of type [TEvent].
 * */
@Suppress("UNCHECKED_CAST")
inline fun <reified TEvent : Event> EventSource.unregisterHandler(handler: EventHandler<TEvent>) {
    unregisterHandler(TEvent::class, handler as EventHandler<Event>)
}

/**
 * Marker interface implemented by all events.
 * */
interface Event

/**
 * A handler for events of the specified type.
 * */
fun interface EventHandler<T : Event> {
    fun handle(event: T)
}