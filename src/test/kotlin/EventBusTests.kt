import org.ageseries.libage.data.Event
import org.ageseries.libage.data.EventBus
import org.ageseries.libage.data.EventHandler
import org.ageseries.libage.data.registerHandler
import org.ageseries.libage.data.unregisterHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class EventBusTests {
    private class FirstEvent(val payload: Int) : Event

    private open class BaseEvent : Event

    private class DerivedEvent : BaseEvent()

    private class ForbiddenEvent : Event

    @Test
    fun sendWithoutHandlersReturnsZero() {
        assertEquals(0, EventBus().send(FirstEvent(1)))
    }

    @Test
    fun handlersReceiveEventAndCountIsReturned() {
        val bus = EventBus()
        val received = ArrayList<Int>()

        bus.registerHandler<FirstEvent> { received.add(it.payload) }
        bus.registerHandler<FirstEvent> { received.add(it.payload * 10) }

        assertEquals(2, bus.send(FirstEvent(3)))
        assertEquals(listOf(3, 30), received)
    }

    @Test
    fun handlersAreInvokedInRegistrationOrder() {
        val bus = EventBus()
        val order = ArrayList<String>()

        bus.registerHandler<FirstEvent> { order.add("a") }
        bus.registerHandler<FirstEvent> { order.add("b") }
        bus.registerHandler<FirstEvent> { order.add("c") }
        bus.send(FirstEvent(0))

        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun unregisteredHandlerNoLongerReceivesEvents() {
        val bus = EventBus()
        var hits = 0
        val handler = EventHandler<FirstEvent> { hits++ }

        bus.registerHandler(handler)
        bus.send(FirstEvent(0))
        bus.unregisterHandler(handler)

        assertEquals(0, bus.send(FirstEvent(0)))
        assertEquals(1, hits)
    }

    @Test
    fun unregisteringUnknownHandlerFails() {
        val bus = EventBus()
        val handler = EventHandler<FirstEvent> { }

        assertThrows<IllegalStateException> { bus.unregisterHandler(handler) }

        bus.registerHandler(EventHandler<FirstEvent> { })

        assertThrows<IllegalStateException> { bus.unregisterHandler(handler) }
    }

    @Test
    fun eventsAreMatchedByExactClassOnly() {
        val bus = EventBus()
        var baseHits = 0

        bus.registerHandler<BaseEvent> { baseHits++ }

        assertEquals(0, bus.send(DerivedEvent()))
        assertEquals(1, bus.send(BaseEvent()))
        assertEquals(1, baseHits)
    }

    @Test
    fun allowListRejectsRegistrationAndSendingOfOtherEvents() {
        val bus = EventBus(setOf(FirstEvent::class))

        bus.registerHandler<FirstEvent> { }

        assertThrows<IllegalStateException> { bus.registerHandler<ForbiddenEvent> { } }
        assertThrows<IllegalStateException> { bus.send(ForbiddenEvent()) }
        assertEquals(1, bus.send(FirstEvent(0)))
    }

    @Test
    fun concurrentRegistrationAndSendingDoNotLoseHandlers() {
        val bus = EventBus()
        val handlerCount = AtomicInteger()
        val threadCount = 8
        val perThread = 100
        val latch = CountDownLatch(threadCount)

        val threads = List(threadCount) {
            thread {
                repeat(perThread) {
                    bus.registerHandler<FirstEvent> { handlerCount.incrementAndGet() }
                    bus.send(FirstEvent(0))
                }

                latch.countDown()
            }
        }

        threads.forEach { it.join() }

        assertEquals(threadCount * perThread, bus.send(FirstEvent(0)))
    }
}
