package org.eln2.mc.common.content

import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.OnClientThread
import org.eln2.mc.common.content.modules.*
import org.eln2.mc.requireIsOnRenderThread

private val obj = Any()

private class Scope(val name: String) {
    private var thread: Thread? = null
    private var spent = false

    fun enter() {
        synchronized(obj) {
            check(thread == null && !spent)
            thread = Thread.currentThread()
        }
    }

    fun executeInScope(action: () -> Unit) {
        try {
            enter()
            action()
            leave()
        }
        catch (t: Throwable) {
            LOG.fatal("Error occurred during execution in $name!")
            throw t
        }
    }

    fun leave() {
        synchronized(obj) {
            check(thread != null && Thread.currentThread() == thread)
            thread = null
            spent = true
        }
    }

    fun validate() {
        synchronized(obj) {
            check(thread != null && !spent && Thread.currentThread() == thread) {
                "Was not in $name!"
            }
        }
    }
}

private val contentModules = HashSet<ContentModule>()
private val initScope = Scope("initialize")
private val setupScreensScope = Scope("setupScreens")

abstract class ContentModule {
    init {
        initScope.validate()
    }

    fun initialize() {
        initScope.validate()
        LOG.info("Initialized content module ${this.javaClass}.")
    }

    fun setupScreensModule() {
        setupScreensScope.validate()
        setupScreens()
    }

    protected open fun setupScreens() { }
}

/**
 * Event dispatcher for content registry.
 * It handles initializing [ContentModule]s.
 */
object Content {
    /**
     * Initializes the fields, in order to register the content.
     */
    fun initialize() = initScope.executeInScope {
        Eln2Tools.initialize()
        Eln2Ingredients.initialize()
        Eln2Wires.initialize()
        Eln2Kinetic.initialize()
        Eln2BasicComponents.initialize()
        Eln2Batteries.initialize()
        Eln2Solar.initialize()
        Eln2Lights.initialize()
        Eln2HeatGenerators.initialize()
        Eln2Thermodynamics.initialize()
        Eln2PowerDevices.initialize()
        Eln2Processing.initialize()
        Eln2Signal.initialize()
        Eln2Grid.initialize()
        Eln2Kinetic.initialize()

        LOG.info("Content init completed.")
    }

    /**
     * Registers the screens on the client.
     * */
    @ClientOnly @OnClientThread
    fun setupScreens() = setupScreensScope.executeInScope {
        requireIsOnRenderThread()

        contentModules.forEach {
            it.setupScreensModule()
        }

        LOG.info("Client screens completed.")
    }
}
