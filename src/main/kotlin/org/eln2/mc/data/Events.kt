package org.eln2.mc.data

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
