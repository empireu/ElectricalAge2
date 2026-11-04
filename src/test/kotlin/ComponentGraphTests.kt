import org.ageseries.libage.data.ComponentGraph
import org.ageseries.libage.data.ComponentListener
import org.ageseries.libage.data.ConnectedComponent
import org.ageseries.libage.data.Edge
import org.ageseries.libage.data.EdgeListener
import org.ageseries.libage.data.Vertex
import org.ageseries.libage.data.VertexListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random

class ComponentGraphTests {
    private class RecordingListener : ComponentListener, VertexListener, EdgeListener {
        val liveComponents = HashSet<ConnectedComponent>()
        val realizedCount = HashMap<ConnectedComponent, Int>()
        var unrealizedEvents = 0
        var modificationStarted = 0
        var modificationFinished = 0
        var verticesAdded = 0
        var verticesRemoved = 0
        var edgesAdded = 0
        var edgesRemoved = 0

        override fun componentRealized(c: ConnectedComponent) {
            assertTrue(liveComponents.add(c), "component realized twice")
            realizedCount.merge(c, 1, Int::plus)
        }

        override fun componentUnrealized(c: ConnectedComponent) {
            assertTrue(liveComponents.remove(c), "unrealized a component that was not live")
            unrealizedEvents++
        }

        override fun realizedComponentModificationStarted(c: ConnectedComponent) {
            assertTrue(c in liveComponents)
            modificationStarted++
        }

        override fun realizedComponentModificationFinished(c: ConnectedComponent) {
            assertTrue(c in liveComponents)
            modificationFinished++
        }

        override fun vertexAdded(v: Vertex) {
            verticesAdded++
        }

        override fun vertexRemoved(v: Vertex) {
            verticesRemoved++
        }

        override fun edgeAdded(e: Edge) {
            edgesAdded++
        }

        override fun edgeRemoved(e: Edge) {
            edgesRemoved++
        }
    }

    private fun referencePartition(graph: ComponentGraph): Set<Set<Vertex>> {
        val unvisited = graph.vertices.toMutableSet()
        val result = HashSet<Set<Vertex>>()

        while (unvisited.isNotEmpty()) {
            val group = HashSet<Vertex>()
            val queue = ArrayDeque<Vertex>()
            val first = unvisited.first()
            queue.addLast(first)
            group.add(first)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()

                for (neighbor in current.incident.keys) {
                    if (group.add(neighbor)) {
                        queue.addLast(neighbor)
                    }
                }
            }

            unvisited.removeAll(group)
            result.add(group)
        }

        return result
    }

    private fun assertGraphConsistent(graph: ComponentGraph, listener: RecordingListener) {
        assertTrue(graph.isImmutable)

        val expected = referencePartition(graph)
        val actual = graph.components.map { it.vertices.toSet() }

        assertEquals(expected.size, graph.components.size)
        assertEquals(expected, actual.toSet())

        graph.vertices.forEach {
            assertTrue(it in it.component.vertices)
            assertTrue(it.component in graph.components)
        }

        assertEquals(graph.components, listener.liveComponents)

        val incidentEdges = HashSet<Edge>()
        graph.vertices.forEach { vertex ->
            vertex.incident.forEach { (other, edge) ->
                assertSame(edge, other.incident[vertex])
                incidentEdges.add(edge)
            }
        }

        assertEquals(incidentEdges, graph.edges)
    }

    @Test
    fun emptyGraphHasNoComponents() {
        val graph = ComponentGraph()

        graph.mutate { }

        assertTrue(graph.components.isEmpty())
        assertTrue(graph.isImmutable)
    }

    @Test
    fun newVerticesStartAsSingletonComponents() {
        val graph = ComponentGraph()
        val listener = RecordingListener()
        graph.addListener(listener)

        val vertices = graph.mutate { List(3) { newVertex() } }

        assertEquals(3, graph.components.size)
        assertEquals(3, listener.verticesAdded)
        assertEquals(3, listener.liveComponents.size)

        vertices.forEach {
            assertEquals(setOf(it), it.component.vertices)
        }

        assertGraphConsistent(graph, listener)
    }

    @Test
    fun connectingVerticesMergesComponents() {
        val graph = ComponentGraph()
        val listener = RecordingListener()
        graph.addListener(listener)

        val (first, second, third) = graph.mutate { List(3) { newVertex() } }

        graph.mutate { connect(first, second) }

        assertEquals(2, graph.components.size)
        assertSame(first.component, second.component)
        assertNotSame(first.component, third.component)
        assertGraphConsistent(graph, listener)

        graph.mutate { connect(second, third) }

        assertEquals(1, graph.components.size)
        assertEquals(setOf(first, second, third), first.component.vertices)
        assertEquals(2, listener.edgesAdded)
        assertGraphConsistent(graph, listener)
    }

    @Test
    fun disconnectingSplitsComponentsAndReportsWhetherAnEdgeExisted() {
        val graph = ComponentGraph()
        val listener = RecordingListener()
        graph.addListener(listener)

        val (first, second, third) = graph.mutate { List(3) { newVertex() } }

        graph.mutate {
            connect(first, second)
            connect(second, third)
        }

        val removedExisting = graph.mutate { disconnect(second, third) }
        val removedMissing = graph.mutate { disconnect(second, third) }

        assertTrue(removedExisting)
        assertFalse(removedMissing)
        assertEquals(2, graph.components.size)
        assertNotSame(second.component, third.component)
        assertEquals(1, listener.edgesRemoved)
        assertGraphConsistent(graph, listener)
    }

    @Test
    fun componentsAreOnlyUpdatedWhenOutermostGuardIsDropped() {
        val graph = ComponentGraph()
        val first = graph.mutate { newVertex() }
        val second = graph.mutate { newVertex() }

        graph.mutate {
            connect(first, second)

            graph.mutate {
                assertFalse(graph.isImmutable)
                assertEquals(2, graph.outstandingGuards)
            }

            assertEquals(1, graph.outstandingGuards)
            assertEquals(2, graph.components.size)
            assertNotSame(first.component, second.component)
        }

        assertTrue(graph.isImmutable)
        assertEquals(1, graph.components.size)
        assertSame(first.component, second.component)
    }

    @Test
    fun guardIsDroppedWhenMutationBodyThrows() {
        val graph = ComponentGraph()

        try {
            graph.mutate {
                newVertex()
                error("failure inside mutation")
            }
        } catch (exception: IllegalStateException) {
            assertEquals("failure inside mutation", exception.message)
        }

        assertTrue(graph.isImmutable)
        assertEquals(1, graph.components.size)
    }

    @Test
    fun mutateReturnsTheBodyResult() {
        val graph = ComponentGraph()

        assertEquals(42, graph.mutate { 42 })
    }

    @Test
    fun removingVertexWithSingleEdgeRemovesItFromComponents() {
        val graph = ComponentGraph()
        val listener = RecordingListener()
        graph.addListener(listener)

        val (first, second) = graph.mutate { List(2) { newVertex() } }

        graph.mutate { connect(first, second) }
        graph.mutate { removeVertex(second) }

        assertEquals(setOf(first), graph.vertices)
        assertEquals(1, graph.components.size)
        assertEquals(setOf(first), first.component.vertices)
        assertTrue(first.incident.isEmpty())
        assertEquals(1, listener.verticesRemoved)
        assertGraphConsistent(graph, listener)
    }

    @Test
    fun removingIsolatedVertexIsIdempotentOnTheGraph() {
        val graph = ComponentGraph()
        val vertex = graph.mutate { newVertex() }

        graph.mutate {
            removeVertex(vertex)
            removeVertex(vertex)
        }

        assertTrue(graph.vertices.isEmpty())
        assertTrue(graph.components.isEmpty())
    }

    @Test
    fun removingVertexWithSeveralEdgesDisconnectsThemAll() {
        val graph = ComponentGraph()
        val listener = RecordingListener()
        graph.addListener(listener)

        val (hub, first, second, third) = graph.mutate { List(4) { newVertex() } }

        graph.mutate {
            connect(hub, first)
            connect(hub, second)
            connect(hub, third)
        }

        graph.mutate { removeVertex(hub) }

        assertEquals(3, graph.components.size)
        assertEquals(0, graph.edges.size)
        assertGraphConsistent(graph, listener)
    }

    @Test
    fun disconnectingVertexWithSeveralEdgesRemovesAllIncidentEdges() {
        val graph = ComponentGraph()
        val (hub, first, second) = graph.mutate { List(3) { newVertex() } }

        graph.mutate {
            connect(hub, first)
            connect(hub, second)
        }

        graph.mutate { disconnect(hub) }

        assertTrue(hub.incident.isEmpty())
        assertEquals(3, graph.components.size)
    }

    @Test
    fun connectingTheSamePairTwiceKeepsEdgeSetConsistentWithIncidenceMaps() {
        val graph = ComponentGraph()
        val (first, second) = graph.mutate { List(2) { newVertex() } }

        graph.mutate {
            connect(first, second)
            connect(first, second)
        }

        val incidentEdges = first.incident.values.toSet() + second.incident.values.toSet()

        assertEquals(incidentEdges, graph.edges)
    }

    @Test
    fun selfLoopDoesNotAffectComponents() {
        val graph = ComponentGraph()
        val listener = RecordingListener()
        graph.addListener(listener)

        val (first, second) = graph.mutate { List(2) { newVertex() } }

        graph.mutate { connect(first, first) }

        assertEquals(2, graph.components.size)
        assertNotSame(first.component, second.component)
        assertGraphConsistent(graph, listener)
    }

    @Test
    fun edgePreservesArgumentOrderAndIteratesBothEnds() {
        val graph = ComponentGraph()
        val (first, second) = graph.mutate { List(2) { newVertex() } }
        val edge = graph.mutate { connect(first, second) }

        assertSame(first, edge.a)
        assertSame(second, edge.b)
        assertEquals(listOf(first, second), edge.toList())
    }

    @Test
    fun listenersCanBeRemovedAndNonListenersAreIgnored() {
        val graph = ComponentGraph()
        val listener = RecordingListener()

        graph.addListener(listener)
        graph.addListener("not a listener")
        graph.mutate { newVertex() }
        graph.removeListener(listener)
        graph.removeListener("not a listener")
        graph.mutate { newVertex() }

        assertEquals(1, listener.verticesAdded)
        assertTrue(graph.componentListeners.isEmpty())
    }

    @Test
    fun modifyingRealizedComponentNotifiesStartAndFinish() {
        val graph = ComponentGraph()
        val listener = RecordingListener()
        graph.addListener(listener)

        val (first, second) = graph.mutate { List(2) { newVertex() } }

        graph.mutate { connect(first, second) }

        assertTrue(listener.modificationStarted >= 1)
        assertEquals(listener.modificationStarted, listener.modificationFinished)
        assertTrue(listener.unrealizedEvents >= 1)
    }

    @Test
    fun randomMutationsKeepComponentsMatchingReferencePartition() {
        val random = Random(71)
        val graph = ComponentGraph()
        val listener = RecordingListener()
        graph.addListener(listener)

        val alive = ArrayList<Vertex>()

        repeat(600) {
            graph.mutate {
                repeat(random.nextInt(1, 4)) {
                    val choice = random.nextInt(0, 10)

                    when {
                        alive.size < 3 || choice < 2 -> alive.add(newVertex())

                        choice < 6 -> {
                            val first = alive[random.nextInt(alive.size)]
                            val second = alive[random.nextInt(alive.size)]

                            if (first !== second && second !in first.incident) {
                                connect(first, second)
                            }
                        }

                        choice < 9 -> {
                            val vertex = alive[random.nextInt(alive.size)]

                            if (vertex.incident.isNotEmpty()) {
                                disconnect(vertex, vertex.incident.keys.first())
                            }
                        }

                        else -> {
                            val vertex = alive[random.nextInt(alive.size)]

                            if (vertex.incident.isEmpty()) {
                                removeVertex(vertex)
                                alive.remove(vertex)
                            }
                        }
                    }
                }
            }

            assertGraphConsistent(graph, listener)
        }
    }

    @Test
    fun depthFirstVisitReachesEveryVertexDownwardAtLeastOnce() {
        val graph = ComponentGraph()
        val vertices = graph.mutate { List(6) { newVertex() } }

        graph.mutate {
            connect(vertices[0], vertices[1])
            connect(vertices[1], vertices[2])
            connect(vertices[3], vertices[4])
        }

        val visited = HashSet<Vertex>()
        val roots = ArrayList<Vertex>()

        graph.visitDepthFirst({ vertex, _, isRoot, _ ->
            visited.add(vertex)

            if (isRoot) {
                roots.add(vertex)
            }
        }, { _, _ -> })

        assertEquals(graph.vertices, visited)
        assertEquals(3, roots.size)
    }

    @Test
    fun depthFirstVisitStartsAtRequestedVertex() {
        val graph = ComponentGraph()
        val vertices = graph.mutate { List(4) { newVertex() } }

        graph.mutate {
            connect(vertices[0], vertices[1])
            connect(vertices[1], vertices[2])
            connect(vertices[2], vertices[3])
        }

        val order = ArrayList<Vertex>()

        graph.visitDepthFirst({ vertex, _, _, _ -> order.add(vertex) }, { _, _ -> }, vertices[2])

        assertSame(vertices[2], order.first())
    }

    @Test
    fun depthFirstVisitReportsIncreasingDepthAlongPath() {
        val graph = ComponentGraph()
        val vertices = graph.mutate { List(4) { newVertex() } }

        graph.mutate {
            connect(vertices[0], vertices[1])
            connect(vertices[1], vertices[2])
            connect(vertices[2], vertices[3])
        }

        val depths = HashMap<Vertex, Int>()

        graph.visitDepthFirst({ vertex, depth, _, _ -> depths[vertex] = depth }, { _, _ -> }, vertices[0])

        assertEquals(listOf(0, 1, 2, 3), vertices.map { depths[it] })
    }

    @Test
    fun depthFirstVisitCallsUpPhaseForEveryVertex() {
        val graph = ComponentGraph()
        val vertices = graph.mutate { List(3) { newVertex() } }

        graph.mutate {
            connect(vertices[0], vertices[1])
            connect(vertices[1], vertices[2])
        }

        val finished = HashSet<Vertex>()

        graph.visitDepthFirst({ _, _, _, _ -> }, { vertex, _ -> finished.add(vertex) })

        assertEquals(graph.vertices, finished)
    }

    @Test
    fun visitRootHintDoesNotPreventRealizingOtherComponents() {
        val graph = ComponentGraph()
        val (first, second, third) = graph.mutate { List(3) { newVertex() } }

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            graph.mutate {
                connect(first, second)
                setRoot(first)
            }
        }

        assertEquals(2, graph.components.size)
        assertNotSame(first.component, third.component)
    }

    @Test
    fun toDotMentionsEveryVertexAndEdge() {
        val graph = ComponentGraph()
        val (first, second, third) = graph.mutate { List(3) { newVertex() } }

        graph.mutate {
            connect(first, second)
            connect(second, third)
        }

        val dot = graph.toDot()

        assertTrue(dot.startsWith("graph {"))
        assertEquals(2, dot.lines().count { it.contains(" -- ") })
        assertEquals(3, dot.lines().count { it.contains("[label=\"v") })
    }
}
