package org.eln2.mc.common

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.event.level.ChunkWatchEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.event.server.ServerStartingEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent
import net.minecraftforge.server.ServerLifecycleHooks
import org.ageseries.libage.utils.Stopwatch
import org.eln2.mc.LOG
import org.eln2.mc.client.render.DebugVisualizer
import org.eln2.mc.common.blocks.BlockRegistry
import org.eln2.mc.common.cells.foundation.CellGraph
import org.eln2.mc.common.cells.foundation.CellGraphManager
import org.eln2.mc.common.events.schedulePost
import org.eln2.mc.common.parts.PartRegistry
import org.eln2.mc.common.specs.foundation.SpecPlacementOverlayServer
import org.eln2.mc.data.AveragingList

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
object ModEvents {
    var isFullyLoaded = false
        private set

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
        CellGraph.makePool()
    }

    @SubscribeEvent @JvmStatic
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.END) {
            var tickRate = 0.0
            var tickTime = 0.0

            forEachGraphManager {
                tickRate += it.sampleTickRate()
                tickTime += it.totalSpentTime
            }

            upsAveragingList.addSample(tickRate)
            tickTimeAveragingList.addSample(tickTime)

            if (++lastLog == 100) {
                lastLog = 0

                //LOG.debug("Simulation rate: ${upsAveragingList.calculate().formatted()} U/S")
                //LOG.debug("Simulation time: ${Quantity(tickTimeAveragingList.calculate(), SECOND).classify()}")
            }

            GhostLightServer.applyChanges()

            lastTickStopwatch.resetTotal()
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
        GridConnectionManagerServer.clear()
        SpecPlacementOverlayServer.clear()
    }

    private fun scheduleGhostEvent(event: BlockEvent) {
        if(event.level.isClientSide) {
            return
        }

        schedulePost(0) {
            if(!event.isCanceled) {
                GhostLightServer.handleBlockEvent(event.level as ServerLevel, event.pos)
            }
        }
    }

    @SubscribeEvent @JvmStatic
    fun onBlockBreakEvent(event: BlockEvent.BreakEvent) {
        if(!event.isCanceled) {
            scheduleGhostEvent(event)
        }
    }

    @SubscribeEvent @JvmStatic
    fun onEntityPlaceEvent(event: BlockEvent.EntityPlaceEvent) { // not called client side?
        if(event.level is Level && GridCollisions.intersectsPlacementBlock(event.level as Level, event.pos, event.state)) {
            event.isCanceled = true
        }

        if(!event.isCanceled) {
            scheduleGhostEvent(event)
        }
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
