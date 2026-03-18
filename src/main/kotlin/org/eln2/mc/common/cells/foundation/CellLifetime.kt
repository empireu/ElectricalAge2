@file:Suppress("ClassName")

package org.eln2.mc.common.cells.foundation

import org.ageseries.libage.data.Event
import org.eln2.mc.requireIsOnServerThread

/**
 * Interface with methods called throughout the lifetime of the cell or cell nodes.
 * Each method dispatches a [CellLifetimeEvent]. Convention:
 * ```
 * Cell_<method name>
 * ```
 * **All methods are called on the server thread, because they are called when the circuit is changed (by a player!)**
 * */
interface CellLifetime {
    /**
     * Called after all graphs in the level have been loaded, before the solver is built.
     * */
    fun onWorldLoadedPreSolver() {
        requireIsOnServerThread {
            "onWorldLoadedPreSolver non-server"
        }
    }
    /**
     * Called after all graphs in the level have been loaded, after the solver is built.
     */
    fun onWorldLoadedPostSolver() {
        requireIsOnServerThread {
            "onWorldLoadedPostSolver non-server"
        }
    }
    /**
     * Called after all graphs in the level have been loaded, before the simulations start.
     * */
    fun onWorldLoadedPreSim() {
        requireIsOnServerThread {
            "onWorldLoadedPreSim non-server"
        }
    }
    /**
     * Called after all graphs in the level have been loaded, after the simulations have started.
     * */
    fun onWorldLoadedPostSim() {
        requireIsOnServerThread {
            "onWorldLoadedPostSim non-server"
        }
    }
    /**
     * Called after the container loaded in.
     * The field is assigned before this is called.
     */
    fun onContainerLoaded() {
        requireIsOnServerThread {
            "onContainerLoaded non-server"
        }
    }
    /**
     * Called when the container is being unloaded (the game object went out-of-scope, where applicable).
     * */
    fun onContainerUnloading() {
        requireIsOnServerThread {
            "onContainerUnloading non-server"
        }
    }
    /**
     * Called after the container was unloaded.
     * */
    fun onContainerUnloaded() {
        requireIsOnServerThread {
            "onContainerUnloaded non-server"
        }
    }
    /**
     * Called when the graph manager completed loading this cell from the disk.
     */
    fun onLoadedFromDisk() {
        requireIsOnServerThread {
            "onLoadedFromDisk non-server"
        }
    }
    /**
     * Called after the cell was connected freshly.
     */
    fun onCreated() {
        requireIsOnServerThread {
            "onCreated non-server"
        }
    }
    /**
     * Called when the cell is being destroyed, right before any operations run.
     * The cell is still in a valid and connected state.
     * */
    fun onBeginDestroy() {
        requireIsOnServerThread {
            "onBeginDestroy non-server"
        }
    }
    /**
     * Called while the cell is being destroyed, just after the simulation was stopped.
     * Subscribers may be cleaned up here.
     * Guaranteed to be on the game thread.
     * */
    fun onDestroying() {
        requireIsOnServerThread {
            "onDestroying non-server"
        }
    }
    /**
     * Called after the cell was destroyed.
     */
    fun onDestroyed() {
        requireIsOnServerThread {
            "onDestroyed non-server"
        }
    }
    /**
     * Called when the graph and/or neighbouring cells are updated. This method is called after completeDiskLoad and setPlaced
     * @param connectionsChanged True if the neighbouring cells changed.
     * @param graphChanged True if the graph that owns this cell has changed.
     */
    fun onUpdate(connectionsChanged: Boolean, graphChanged: Boolean) {
        requireIsOnServerThread {
            "onUpdate non-server"
        }
    }
    /**
     * Called when subscribers should be added, after the graph changes.
     * For more information, see [CellBehavior.subscribe].
     * Calling the super method is not needed, by convention.
     * */
    fun subscribe(subscribers: SubscriberCollection<SimulationPhase>) {
        requireIsOnServerThread {
            "subscribe non-server"
        }
    }
    /**
     * Called when subscribers should be added, after the graph changes. These subscribers are for the server thread.
     * For more information, see [CellBehavior.subscribeServerThread].
     * Calling the super method is not needed, by convention.
     * */
    fun subscribeServerThread(subscribers: SubscriberCollection<ServerPhase>) {
        requireIsOnServerThread {
            "subscribeServerThread server non-server"
        }
    }
    /**
     * Called when the build started, right after the connections were cleared.
     * */
    fun onBuildStarted() {
        requireIsOnServerThread {
            "onBuildStarted non-server"
        }
    }
    /**
     * Called when the solver is built, before the simulation is started.
     * */
    fun onBuildFinished() {
        requireIsOnServerThread {
            "onBuildFinished non-server"
        }
    }
}

/**
 * Represents an event sent throughout the lifetime of the cell.
 * Each event is called when a method in [CellLifetime] is called on the [Cell].
 * */
interface CellLifetimeEvent : Event

/**
 * Called after all graphs in the level have been loaded, before the solver is built.
 * */
object Cell_onWorldLoadedPreSolver : CellLifetimeEvent

/**
 * Called after all graphs in the level have been loaded, after the solver is built.
 */
object Cell_onWorldLoadedPostSolver : CellLifetimeEvent

/**
 * Called after all graphs in the level have been loaded, before the simulations start.
 * */
object Cell_onWorldLoadedPreSim : CellLifetimeEvent

/**
 * Called after all graphs in the level have been loaded, after the simulations have started.
 * */
object Cell_onWorldLoadedPostSim : CellLifetimeEvent

/**
 * Called after the container loaded in.
 * The field is assigned before this is called.
 */
object Cell_onContainerLoaded : CellLifetimeEvent

/**
 * Called when the container is being unloaded (the game object Cell_went out-of-scope, where applicable).
 * */
object Cell_onContainerUnloading : CellLifetimeEvent

/**
 * Called after the container was unloaded.
 * */
object Cell_onContainerUnloaded : CellLifetimeEvent

/**
 * Called when the graph manager completed loading this cell from the disk.
 */
object Cell_onLoadedFromDisk : CellLifetimeEvent

/**
 * Called after the cell was connected freshly.
 */
object Cell_onCreated : CellLifetimeEvent

/**
 * Called when the cell is being destroyed, right before any operations run.
 * The cell is still in a valid and connected state.
 * */
object Cell_onBeginDestroy : CellLifetimeEvent

/**
 * Called while the cell is being destroyed, just after the simulation was stopped.
 * Subscribers may be cleaned up here.
 * Guaranteed to be on the game thread.
 * */
object Cell_onDestroying : CellLifetimeEvent

/**
 * Called after the cell was destroyed.
 */
object Cell_onDestroyed : CellLifetimeEvent

/**
 * Called when the graph and/or neighbouring cells are updated. This method is called after completeDiskLoad and setPlaced
 * @param connectionsChanged True if the neighbouring cells changed.
 * @param graphChanged True if the graph that owns this cell has changed.
 */
data class Cell_onUpdate(val connectionsChanged: Boolean, val graphChanged: Boolean) : CellLifetimeEvent

/**
 * Called when subscribers should be added, after the graph changes.
 * This is called before [SimulationObject.subscribe].
 * Calling the super method is not needed, by convention.
 * */
data class Cell_subscribe(val subscribers: SubscriberCollection<SimulationPhase>) : CellLifetimeEvent

/**
 * Called when subscribers for the server thread should be added, after the graph changes.
 * */
data class Cell_subscribeServer(val subscribers: SubscriberCollection<ServerPhase>) : CellLifetimeEvent

/**
 * Called when the build started, right after the connections were cleared.
 * */
object Cell_onBuildStarted : CellLifetimeEvent

/**
 * Called when the solver is built, before the simulation is started.
 * */
object Cell_onBuildFinished : CellLifetimeEvent
