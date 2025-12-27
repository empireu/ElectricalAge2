import org.eln2.mc.common.cells.foundation.SubscriberPool
import org.eln2.mc.common.cells.foundation.SubscriberOptions
import org.eln2.mc.common.cells.foundation.SimulationPhase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class SubscriberPoolTests {
    @Test
    fun testIntervalAddRemove(){
        val collection = SubscriberPool<SimulationPhase>()

        val instantParams = SubscriberOptions(0, SimulationPhase.Pre)
        val slowParams = SubscriberOptions(10, SimulationPhase.Pre)
        val slowPostParams = SubscriberOptions(10, SimulationPhase.Post)

        var instantHits = 0
        var slowHits = 0
        var slowHitsPost = 0

        fun instant(dt: Double, phase: SimulationPhase){
            if(phase != SimulationPhase.Pre){
                fail("Invalid phase")
            }

            instantHits++
        }

        fun slow(dt: Double, phase: SimulationPhase){
            when(phase){
                SimulationPhase.Pre -> slowHits++
                SimulationPhase.Post -> slowHitsPost++
            }
        }

        collection.addSubscriber(instantParams, ::instant)
        collection.addSubscriber(slowParams, ::slow)
        collection.addSubscriber(slowPostParams, ::slow)

        assert(collection.subscriberCount == 2)

        repeat(100 * slowParams.interval){ iter10 ->
            collection.update(0.0, SimulationPhase.Pre)
            collection.update(0.0, SimulationPhase.Pre)
            collection.update(0.0, SimulationPhase.Post)

            val count = iter10 + 1

            assert(instantHits == (count * 2))
            assert(slowHits == count / 5)
            assert(slowHitsPost == count / 10)
        }

        collection.remove(::instant)

        assert(collection.subscriberCount == 1)

        val previousHit = instantHits

        collection.update(0.0, SimulationPhase.Pre)

        assert(previousHit == instantHits)

        fun removeWhilstIterating(dt: Double, phase: SimulationPhase){
            collection.remove(::removeWhilstIterating)
        }

        collection.addSubscriber(instantParams, ::removeWhilstIterating)

        assert(collection.subscriberCount == 2)

        collection.update(0.0, SimulationPhase.Pre)

        assert(collection.subscriberCount == 1)

        try {
            collection.remove(::removeWhilstIterating)
            fail("Non existent remove")
        }
        catch (t: Throwable){
            // Success
        }

        collection.addSubscriber(instantParams, ::removeWhilstIterating)

        try {
            collection.addSubscriber(instantParams, ::removeWhilstIterating)
            fail("Duplicate add")
        }
        catch (t: Throwable){
            // Success
        }
    }
}
