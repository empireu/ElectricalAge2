@file:Suppress("NOTHING_TO_INLINE")

package org.ageseries.libage.data

import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.math.max

class BoundingBoxTree3d<T> {
    private var rootInternal: NodeInternal<T>? = null

    /**
     * Gets the root of the tree. Null if no objects are present.
     * */
    val root : Node<T>? get() = rootInternal

    private val leavesInternal = HashMap<T, NodeInternal<T>>()

    /**
     * Gets a map of [T] to the leaf node assigned to it.
     * */
    val leaves : Map<T, Node<T>> get() = leavesInternal

    private inline fun cost(leaf: BoundingBox3d, parent: BoundingBox3d) = (leaf unionWith parent).surface

    private inline fun inheritedCost(leaf: BoundingBox3d, sibling: BoundingBox3d) = (leaf unionWith sibling).surface - sibling.surface

    private fun getSibling(leafBoundingBox: BoundingBox3d) : NodeInternal<T> {
        val leafSurface = leafBoundingBox.surface
        var bestCost = Double.MAX_VALUE
        var bestNode = rootInternal!!

        data class Item<T>(val node: NodeInternal<T>, val cost: Double)

        val queue = PriorityQueue<Item<T>> { a, b ->
            a.cost.compareTo(b.cost)
        }

        queue.add(Item(rootInternal!!, inheritedCost(leafBoundingBox, rootInternal!!.box)))

        while (queue.isNotEmpty()) {
            val (node, nodeCost) = queue.remove()

            val cost = nodeCost + cost(leafBoundingBox, node.box)

            if(cost < bestCost) {
                bestCost = cost
                bestNode = node
            }

            val inheritedCost = nodeCost + inheritedCost(leafBoundingBox, node.box)

            if(leafSurface + inheritedCost < bestCost) {
                val child1 = node.child1

                if(child1 != null) {
                    val child2 = checkNotNull(node.child2)
                    queue.add(Item(child1, inheritedCost))
                    queue.add(Item(child2, inheritedCost))
                }
                else {
                    check(node.child2 == null)
                }
            }
        }

        return bestNode
    }

    private fun balance(a: NodeInternal<T>) : NodeInternal<T> {
        if(a.isLeaf || a.height < 2) {
            return a
        }

        val b = checkNotNull(a.child1)
        val c = checkNotNull(a.child2)
        val balance = c.height - b.height

        return when {
            balance > 1 -> {
                val f = checkNotNull(c.child1)
                val g = checkNotNull(c.child2)

                c.child1 = a
                c.parent = a.parent
                a.parent = c

                if (c.parent == null) {
                    rootInternal = c
                }
                else {
                    val cParent = c.parent!!

                    if(cParent.child1 == a) {
                        cParent.child1 = c
                    } else {
                        check(cParent.child2 == a)
                        cParent.child2 = c
                    }
                }

                if(f.height > g.height) {
                    c.child2 = f
                    a.child2 = g
                    g.parent = a
                    a.box = b.box unionWith g.box
                    c.box = a.box unionWith f.box
                    a.height = max(b.height, g.height) + 1
                    c.height = max(a.height, f.height) + 1
                }
                else {
                    c.child2 = g
                    a.child2 = f
                    f.parent = a
                    a.box = b.box unionWith f.box
                    c.box = a.box unionWith g.box
                    a.height = max(b.height, f.height) + 1
                    c.height = max(a.height, g.height) + 1
                }

                c
            }
            balance < -1 -> {
                val d = checkNotNull(b.child1)
                val e = checkNotNull(b.child2)

                b.child1 = a
                b.parent = a.parent
                a.parent = b

                if (b.parent == null) {
                    rootInternal = b
                }
                else {
                    val bParent = b.parent!!
                    if(bParent.child1 == a) {
                        bParent.child1 = b
                    } else {
                        check(bParent.child2 == a)
                        bParent.child2 = b
                    }
                }

                if(d.height > e.height) {
                    b.child2 = d
                    a.child1 = e
                    e.parent = a
                    a.box = c.box unionWith e.box
                    b.box = a.box unionWith d.box
                    a.height = max(c.height, e.height) + 1
                    b.height = max(a.height, d.height) + 1
                }
                else {
                    b.child2 = e
                    a.child1 = d
                    d.parent = a
                    a.box = c.box unionWith d.box
                    b.box = a.box unionWith e.box
                    a.height = max(c.height, d.height) + 1
                    b.height = max(a.height, e.height) + 1
                }

                b
            }
            else -> {
                a
            }
        }
    }

    private fun fixUp(node: NodeInternal<T>?) {
        var currentNode = node

        while (currentNode != null) {
            currentNode = balance(currentNode)

            val child1 = checkNotNull(currentNode.child1)
            val child2 = checkNotNull(currentNode.child2)

            currentNode.height = max(child1.height, child2.height) + 1
            currentNode.box = child1.box unionWith child2.box

            currentNode = currentNode.parent
        }
    }

    /**
     * Gets the leaf node of [key] or null, if [key] is not present.
     * */
    operator fun get(key: T): Node<T>? = leavesInternal[key]

    /**
     * Checks if [key] is inserted.
     * */
    fun contains(key: T) = leavesInternal.containsKey(key)

    /**
     * Inserts [data] with the specified [boundingBox]. Throws if [data] is already present.
     * */
    fun insert(data: T, boundingBox: BoundingBox3d) : Node<T> {
        val leaf = NodeInternal<T>()
        leaf.data = data
        leaf.box = boundingBox

        require(leavesInternal.put(data, leaf) == null) {
            "Duplicate insert $data"
        }

        if(rootInternal == null) {
            rootInternal = leaf
            return leaf
        }

        val sibling = getSibling(boundingBox)

        val oldParent = sibling.parent
        val newParent = NodeInternal<T>()
        newParent.parent = oldParent
        newParent.box = boundingBox unionWith sibling.box
        newParent.height = sibling.height + 1

        if (oldParent == null) {
            rootInternal = newParent
        }
        else {
            if(oldParent.child1 == sibling) {
                oldParent.child1 = newParent
            } else {
                oldParent.child2 = newParent
            }
        }

        newParent.child1 = sibling
        newParent.child2 = leaf
        sibling.parent = newParent
        leaf.parent = newParent

        fixUp(newParent)

        return leaf
    }

    /**
     * Removes [data]. Throws an exception if [data] is not present.
     * */
    fun remove(data: T) : Node<T> {
        val leaf = requireNotNull(leavesInternal.remove(data)) {
            "Did not have $data"
        }

        if(leaf == rootInternal) {
            rootInternal = null
            return leaf
        }

        val parent = checkNotNull(leaf.parent)

        val sibling = checkNotNull(
            if(parent.child1 == leaf) {
                parent.child2
            }
            else {
                check(parent.child2 == leaf)
                parent.child1
            }
        )

        val grandparent = parent.parent

        if (grandparent == null) {
            rootInternal = sibling
            sibling.parent = null
        }
        else {
            if(grandparent.child1 == parent) {
                grandparent.child1 = sibling
            } else {
                check(grandparent.child2 == parent)
                grandparent.child2 = sibling
            }

            sibling.parent = grandparent

            fixUp(grandparent)
        }

        return leaf
    }

    /**
     * Queries for leaves that pass the [test] and whose ancestors pass the [test].
     * Results are passed to [consumer]. If [consumer] returns false, the search ends.
     * */
    inline fun queryIntersecting(test: (Node<T>) -> Boolean, consumer: (Node<T>) -> Boolean) {
        if(root == null) {
            return
        }

        val stack = ArrayDeque<Node<T>>()
        stack.addLast(root!!)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()

            if(test(node)) {
                if(node.isLeaf) {
                    if(!consumer(node)) {
                        return
                    }
                }
                else {
                    stack.addLast(node.child1!!)
                    stack.addLast(node.child2!!)
                }
            }
        }
    }

    interface Node<T> {
        /**
         * Gets the data stored in the node. Not null if and only if [isLeaf] is true.
         * */
        val data: T?
        /**
         * Gets the bounding box of the node. For [isLeaf] nodes, it will be the bounding box passed to [insert].
         * */
        val box: BoundingBox3d
        /**
         * Gets the parent of the node. Not null if and only if this node is not the root.
         * */
        val parent: Node<T>?
        /**
         * Gets the first child. Not null if and only if this node is not [isLeaf].
         * */
        val child1: Node<T>?
        /**
         * Gets the second child. Not null if and only if this node is not [isLeaf].
         * */
        val child2: Node<T>?
        /**
         * Gets the height at this node. 0 if [isLeaf].
         * */
        val height: Int
        /**
         * If true, this node is a leaf node. Otherwise, this node is an internal node and [child1], [child2] are not null.
         * */
        val isLeaf get() = child1 == null
    }

    private class NodeInternal<T> : Node<T> {
        override var data: T? = null
        override var box = BoundingBox3d.zero
        override var parent: NodeInternal<T>? = null
        override var child1: NodeInternal<T>? = null
        override var child2: NodeInternal<T>? = null
        override var height = 0
    }
}