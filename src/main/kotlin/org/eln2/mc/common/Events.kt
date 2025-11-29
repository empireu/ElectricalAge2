package org.eln2.mc.common

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.event.level.ChunkWatchEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.event.server.ServerStartingEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent
import net.minecraftforge.server.ServerLifecycleHooks
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.SECOND
import org.ageseries.libage.data.classify
import org.ageseries.libage.utils.Stopwatch
import org.eln2.mc.ELN2_LOG_STATS
import org.eln2.mc.LOG
import org.eln2.mc.client.render.DebugVisualizer
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.blocks.foundation.MultipartBlockEntityLevelRendererProvider
import org.eln2.mc.common.cells.foundation.CellGraphManager
import org.eln2.mc.common.cells.foundation.SimulationExecutionSubgraph
import org.eln2.mc.common.cells.foundation.SubscriberPhase
import org.eln2.mc.common.content.modules.ContentModuleManager
import org.eln2.mc.common.content.ScrewdriverItem
import org.eln2.mc.common.content.WindSystem
import org.eln2.mc.common.events.Scheduler
import org.eln2.mc.common.events.schedulePost
import org.eln2.mc.common.grids.GridCollisions
import org.eln2.mc.common.grids.GridConnectionManagerClient
import org.eln2.mc.common.grids.GridConnectionManagerServer
import org.eln2.mc.common.network.serverToClient.BulkMessages
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.specs.foundation.SpecPlacementOverlayServer
import org.eln2.mc.data.AveragingList
import org.eln2.mc.extensions.formatted

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
object ModEvents {
    var isFullyLoaded = false
        private set

    @SubscribeEvent @JvmStatic
    fun registerBlockEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(
            BlockRegistry.MULTIPART_BLOCK_ENTITY.get(),
            MultipartBlockEntityLevelRendererProvider()
        )

        ContentModuleManager.registerBlockEntityRenderers(event)
    }

    @SubscribeEvent @JvmStatic
    fun loadCompletedEvent(event: FMLLoadCompleteEvent) {
        event.enqueueWork {
            isFullyLoaded = true
            BlockRegistry.finalize()
            PartRegistry.finalize()
        }
    }
}

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
object ForgeEvents {
    // To remove these and add better inst
    private val upsAveragingList = AveragingList(100)
    private val tickTimeAveragingList = AveragingList(100)
    private var lastLog = 0

    // total reset every tick
    private val lastTickStopwatch = Stopwatch()

    val timeSinceLastTick get() = lastTickStopwatch.total

    private fun forEachGraphManager(user: (CellGraphManager) -> Unit) {
        ServerLifecycleHooks.getCurrentServer().allLevels.forEach {
            val graphManager = CellGraphManager.getFor(it)
            user(graphManager)
        }
    }

    @SubscribeEvent @JvmStatic
    fun onServerStarting(event: ServerStartingEvent) {
        LOG.info("Making ELN2 thread pool")
        SimulationExecutionSubgraph.makePool()
    }

    private fun simulationStats() {
        var tickRate = 0.0
        var tickTime = 0.0

        forEachGraphManager {
            tickRate += it.sampleTickRate()
            tickTime += it.totalSpentTime
        }

        upsAveragingList.addSample(tickRate)
        tickTimeAveragingList.addSample(tickTime)

        if(ELN2_LOG_STATS) {
            if (++lastLog == 100) {
                lastLog = 0

                LOG.debug("Simulation rate: ${upsAveragingList.calculate().formatted()} U/S")
                LOG.debug("Simulation time: ${Quantity(tickTimeAveragingList.calculate(), SECOND).classify()}")
            }
        }

        lastTickStopwatch.resetTotal()
    }

    private fun dispatchServerSubscribers(phase: SubscriberPhase) {
        forEachGraphManager { manager ->
            manager.forEachGraph { graph ->
                graph.serverThreadSubscribers.update(1.0 / 20.0, phase)
            }
        }
    }

    private fun dispatchAllSimulations() {
        forEachGraphManager { manager ->
            manager.forEachGraph { graph ->
                graph.executionGraph.orchestrateFrame()
            }
        }
    }

    private fun awaitAllSimulations() {
        forEachGraphManager { manager ->
            manager.forEachGraph { graph ->
                graph.executionGraph.awaitCompletion()
            }
        }
    }

    private fun advanceServerFrames() {
        forEachGraphManager { manager ->
            manager.forEachGraph { graph ->
                graph.advanceServerFrame()
            }
        }
    }

    /**
     * Orchestrates all per-tick work.
     * The order of the calls is important here.
     * */
    @SubscribeEvent @JvmStatic
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if(event.phase == TickEvent.Phase.START) {
            Scheduler.onServerTick(event)

            // Schedule the simulations after those subscribers ran, so they don't get a torn frame:
            dispatchServerSubscribers(SubscriberPhase.Pre)
            dispatchAllSimulations()
        }
        else {
            // Dispatch with high priority:
            Scheduler.onServerTick(event)

            simulationStats()

            // The order of these 3 doesn't matter:
            GhostLightServer.applyChanges()
            ScrewdriverItem.Scroll.tickCooldowns()
            WindSystem.update()

            // Await simulation completion, dispatch those subscribers, clear simulation flags, then flush messages:
            awaitAllSimulations()
            dispatchServerSubscribers(SubscriberPhase.Post)
            advanceServerFrames()
            BulkMessages.flush()
        }
    }

    @SubscribeEvent @JvmStatic
    fun onPlayerWatch(event: ChunkWatchEvent.Watch) {
        GhostLightServer.playerWatch(event.level, event.player, event.pos)
        GridConnectionManagerServer.playerWatch(event.level, event.player, event.pos)
    }

    @SubscribeEvent @JvmStatic
    fun onPlayerUnwatch(event: ChunkWatchEvent.UnWatch) {
        GhostLightServer.playerUnwatch(event.level, event.player, event.pos)
        GridConnectionManagerServer.playerUnwatch(event.level, event.player, event.pos)
    }

    @SubscribeEvent @JvmStatic
    fun onServerStopping(event: ServerStoppingEvent) {
        event.server.allLevels.forEach {
            LOG.info("Stopping simulations for $it")
            CellGraphManager.getFor(it).serverStop()
        }

        GhostLightServer.clear()
        WindSystem.clear()

        GridConnectionManagerServer.clear()
        SpecPlacementOverlayServer.clear()
    }

    private fun scheduleWorldTrackingEventServer(event: BlockEvent, handler: (ServerLevel, BlockPos) -> Unit) {
        if(event.level.isClientSide || event.isCanceled) {
            return
        }

        schedulePost(0) {
            if(!event.isCanceled) {
                handler(event.level as ServerLevel, event.pos)
            }
        }
    }

    @SubscribeEvent @JvmStatic
    fun onBlockBreakEvent(event: BlockEvent.BreakEvent) {
        scheduleWorldTrackingEventServer(event, GhostLightServer::handleBlockEvent)
        scheduleWorldTrackingEventServer(event, WindSystem::handleBlockEvent)
    }

    @SubscribeEvent @JvmStatic
    fun onEntityPlaceEvent(event: BlockEvent.EntityPlaceEvent) { // not called client side? Done for the clients in MixinBlockPlaceContext
        if(event.level is Level && GridCollisions.intersectsPlacementBlock(event.level as Level, event.pos, event.state)) {
            event.isCanceled = true
        }

        scheduleWorldTrackingEventServer(event, GhostLightServer::handleBlockEvent)
        scheduleWorldTrackingEventServer(event, WindSystem::handleBlockEvent)
    }

    @SubscribeEvent @JvmStatic
    fun onClientLevelClosed(event: LevelEvent.Unload) {
        if(event.level.isClientSide) {
            GhostLightHackClient.clear()
            GridConnectionManagerClient.clear()
            DebugVisualizer.clear()
        }
    }
}
