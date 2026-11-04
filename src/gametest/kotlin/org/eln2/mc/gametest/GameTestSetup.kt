package org.eln2.mc.gametest

import net.minecraft.gametest.framework.BeforeBatch
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GlobalTestReporter
import net.minecraft.gametest.framework.TestReporter
import net.minecraft.server.level.ServerLevel
import net.minecraftforge.gametest.GameTestHolder
import org.eln2.mc.LOG
import org.eln2.mc.MODID

const val DEFAULT_BATCH = "defaultBatch"

/**
 * Logs the stack trace of failed tests, because the vanilla reporter only logs the message.
 * */
private object StackTraceTestReporter : TestReporter {
    override fun onTestFailed(info: GameTestInfo) {
        LOG.error("GAME TEST FAILED: ${info.testName}", info.error)
    }

    override fun onTestSuccess(info: GameTestInfo) {
        LOG.info("GAME TEST PASSED: ${info.testName}")
    }
}

@GameTestHolder(MODID)
object GameTestSetup {
    @BeforeBatch(batch = DEFAULT_BATCH)
    @JvmStatic
    fun installReporter(level: ServerLevel) {
        GlobalTestReporter.replaceWith(StackTraceTestReporter)
    }
}
