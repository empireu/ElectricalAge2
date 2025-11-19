package org.eln2.mc.common.content.modules

import net.minecraftforge.client.event.EntityRenderersEvent
import org.ageseries.libage.utils.addUnique
import org.eln2.mc.ClientOnly
import org.eln2.mc.LOG
import org.eln2.mc.requireIsOnRenderThread

private val obj = Any()

/**
 * Validation layer for a method that executes only once. Used to ensure the content setup methods are called from the right call site, in the right order.
 * - A setup method from [ContentModuleManager] calls methods from [ContentModule] inside the block of [executeInScope].
 * - Methods from [ContentModule] ensure they are being called exactly once, from the right setup method in [ContentModuleManager], and in the correct order relative to other setup methods, with [validate].
 * @param dependencies [Scope]s [enter] depends on. All of these scopes must have already been executed, otherwise [enter] will result in an error.
 * */
private class Scope(val name: String, val dependencies: List<Scope> = listOf()) {
    private var thread: Thread? = null
    private var executed = false

    private fun enter() {
        synchronized(obj) {
            dependencies.forEach { scope ->
                check(scope.executed) {
                    "Scope $name depends on ${scope.name}, which never executed!"
                }
            }

            check(thread == null && !executed)
            thread = Thread.currentThread()
        }
    }

    private fun leave() {
        synchronized(obj) {
            check(thread != null && Thread.currentThread() == thread)
            thread = null
            executed = true
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

    fun validate() {
        synchronized(obj) {
            check(thread != null && !executed && Thread.currentThread() == thread) {
                "Was not in $name!"
            }
        }
    }
}

/**
 * All `object`s that implement [ContentModule]. They are in the `modules` package.
 * This collection is built in [ContentModuleManager.initialize], which calls [ContentModule.initialize] which causes the singleton to be instanced.
 * The constructor of [ContentModule] then validates the scope and adds itself to the set.
 * */
private val contentModules = HashSet<ContentModule>()

/**
 * Scope of the [ContentModuleManager.initialize] method. The singletons implementing [ContentModule] are constructed in this scope.
 * */
private val initScope = Scope("initialize")

/**
 * Client-only scope where screens are registered.
 * */
private val setupScreensScope = Scope(
    "setupScreens",
    listOf(initScope)
)

/**
 * Client-only scope where block entity visualizers are registered in [ContentModuleManager.registerBlockEntityVisualizers].
 * */
private val registerBlockEntityVisualizersScope = Scope(
    "registerBlockEntityVisualizers",
    listOf(initScope)
)

/**
 * Client-only scope where part visualizers are registered in [ContentModuleManager.registerPartVisualizers].
 * Depends on [registerBlockEntityVisualizersScope].
 * */
private val registerPartVisualizersScope = Scope(
    "registerPartVisualizers",
    listOf(registerBlockEntityVisualizersScope)
)

/**
 * Client-only scope where spec visualizers are registered in [ContentModuleManager.registerSpecVisualizers].
 * Depends on [registerPartVisualizersScope].
 * */
private val registerSpecVisualizersScope = Scope(
    "registerSpecVisualizers",
    listOf(registerPartVisualizersScope)
)

/**
 * Client-only scope where BERs are registered.
 * */
private val registerBlockEntityRenderersScope = Scope(
    "registerBlockEntityRenderers",
    listOf(initScope)
)

/**
 * Implemented by `object`s that hold fields for the registered blocks, items, block entities, cells, parts, specs, and other things.
 * Methods to register visualizers and block entity renderers are also present. They are called in [ContentModuleManager], with some validation.
 * */
abstract class ContentModule {
    init {
        initScope.validate()

        synchronized(obj) {
            contentModules.addUnique(this) {
                "Duplicate init content module $this!"
            }
        }
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

    fun registerBlockEntityVisualizersModule() {
        registerBlockEntityVisualizersScope.validate()
        registerBlockEntityVisualizers()
    }

    protected open fun registerBlockEntityVisualizers() { }

    fun registerPartVisualizersModule() {
        registerPartVisualizersScope.validate()
        registerPartVisualizers()
    }

    protected open fun registerPartVisualizers() { }

    fun registerSpecVisualizersModule() {
        registerSpecVisualizersScope.validate()
        registerSpecVisualizers()
    }

    protected open fun registerSpecVisualizers() { }

    fun registerBlockEntityRenderersModule(event: EntityRenderersEvent.RegisterRenderers) {
        registerBlockEntityRenderersScope.validate()
        registerBlockEntityRenderers(event)
    }

    protected open fun registerBlockEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) { }

    fun finalizeModule() {

    }
}

/**
 * Event dispatcher for content registry.
 * It handles initializing [ContentModule]s.
 */
object ContentModuleManager {
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
        Eln2Thermal.initialize()
        Eln2PowerDevices.initialize()
        Eln2Processing.initialize()
        Eln2Signal.initialize()
        Eln2Grid.initialize()

        LOG.info("Content init completed.")
    }

    @ClientOnly
    fun setupScreens() = setupScreensScope.executeInScope {
        requireIsOnRenderThread()

        contentModules.forEach {
            it.setupScreensModule()
        }

        LOG.info("Client screens completed.")
    }

    @ClientOnly
    fun registerBlockEntityVisualizers() = registerBlockEntityVisualizersScope.executeInScope {
        contentModules.forEach {
            it.registerBlockEntityVisualizersModule()
        }

        LOG.info("Register block entity visualizers completed.")
    }

    @ClientOnly
    fun registerPartVisualizers() = registerPartVisualizersScope.executeInScope {
        contentModules.forEach {
            it.registerPartVisualizersModule()
        }

        LOG.info("Register part visualizers completed.")
    }

    @ClientOnly
    fun registerSpecVisualizers() = registerSpecVisualizersScope.executeInScope {
        contentModules.forEach {
            it.registerSpecVisualizersModule()
        }

        LOG.info("Register spec visualizers completed.")
    }

    @ClientOnly
    fun registerBlockEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) = registerBlockEntityRenderersScope.executeInScope {
        contentModules.forEach {
            it.registerBlockEntityRenderersModule(event)
        }

        LOG.info("Register block entity renderers completed.")
    }
}
