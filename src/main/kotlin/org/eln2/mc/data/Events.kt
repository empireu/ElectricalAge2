package org.eln2.mc.data

import org.ageseries.libage.data.Event
import org.ageseries.libage.data.EventHandler
import org.ageseries.libage.data.EventSource
import java.util.concurrent.CopyOnWriteArrayList

class Notifier {
    private val handlers = CopyOnWriteArrayList<Runnable>()

    operator fun plusAssign(handler: Runnable) {
        handlers.add(handler)
    }

    fun run() {
        handlers.forEach {
            it.run()
        }
    }
}

inline operator fun<reified TEvent : Event> EventSource.plusAssign(handler: EventHandler<TEvent>) {
    registerHandler(TEvent::class) {
        handler.handle(it as TEvent)
    }
}
