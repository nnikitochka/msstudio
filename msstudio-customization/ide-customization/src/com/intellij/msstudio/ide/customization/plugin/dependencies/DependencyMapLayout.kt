// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.plugin.dependencies

import com.intellij.msstudio.ide.customization.plugin.manifest.PluginManifest
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The geometry of the plugin dependency map.
 *
 * Every plugin renders in one card. A shared dependency appears once.
 * A card carries its own width from [cardWidthOf], so no text is cut off.
 * Plugins without relations park in a side column at the right.
 *
 * Each dependency arrow uses a path through a free grid. The grid keeps a
 * clearance around every card and never reuses an arrow segment. Therefore,
 * an arrow cannot run on or under a card, or lie on another arrow.
 * The router tries several edge orders. A relation stays in [unroutedEdges]
 * only when the graph has no clear route for it.
 */
class DependencyMapLayout(
  manifests: List<PluginManifest>,
  cardWidthOf: (Node) -> Int,
  scale: Float = 1f,
) {
  enum class Kind { INSTALLED, MISSING }

  data class Node(
    val name: String,
    val kind: Kind,
    val version: String?,
    val inCycle: Boolean,
    val cycleChain: List<String>?,
  )

  data class Pt(val x: Int, val y: Int)

  data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
    val top: Int get() = y
    val bottom: Int get() = y + height
    val left: Int get() = x
    val right: Int get() = x + width
    fun contains(px: Int, py: Int): Boolean = px in x..right && py >= y && py <= bottom
  }

  data class Connection(
    val from: Node,
    val to: Node,
    val points: List<Pt>,
    val alarm: Boolean,
    /** The points where this route leaves a visual break for another route. */
    val bridges: List<Pt>,
  )

  data class UnroutedEdge(val from: Node, val to: Node)

  class CardPlacement(val node: Node, val rect: Rect)

  private class Edge(val from: Node, val to: Node, val cycle: Boolean)

  private enum class Side { TOP, RIGHT, BOTTOM, LEFT }

  private data class Endpoint(val point: Pt, val launch: Pt)

  private data class Ports(val from: Endpoint, val to: Endpoint)

  private data class PortRequest(
    val edge: Edge,
    val isFrom: Boolean,
    val node: Node,
    val side: Side,
    val other: Node,
  )

  private data class RouteAttempt(
    val paths: Map<Edge, List<Pt>>,
    val failed: List<Edge>,
    val length: Int,
  )

  private val scaleValue: Float = scale

  val cardHeight: Int = s(CARD_H)
  val maxLineWidth: Int = s(MAX_LINE_WIDTH)

  private val graph = DependencyGraph(manifests)
  private val byName: Map<String, PluginManifest> =
    manifests.filter { !it.name.isNullOrEmpty() }.associateBy { it.name!! }
  private val nodes: LinkedHashMap<String, Node> = buildNodes()
  private val edges: List<Edge> = buildEdges()
  private val maxCardConnections: Int = nodes.values.maxOfOrNull { node ->
    edges.count { it.from == node || it.to == node }
  } ?: 0
  /** The gap has one lane for each arrow of the busiest card. */
  val vGap: Int = max(s(V_GAP), maxCardConnections * s(ROUTE_LANE_GAP) + s(CLEARANCE) * 2)
  private val routeMargin: Int = max(s(BASE_ROUTE_MARGIN), edges.size * s(ROUTE_MARGIN_PER_EDGE))
  private val mapPad: Int = s(PAD) + routeMargin
  private val widths: Map<Node, Int> = nodes.values.associateWith { node ->
    val ports = edges.count { it.from == node || it.to == node }
    max(cardWidthOf(node), (ports + 1) * s(CARD_PORT_GAP))
  }

  val cards: List<CardPlacement>
  val connectors: List<Connection>
  val unroutedEdges: List<UnroutedEdge>
  val bounds: Rect

  init {
    val connected = HashSet<Node>()
    edges.forEach { connected.add(it.from); connected.add(it.to) }
    val standalone = nodes.values.filter { it !in connected }.sortedBy { it.name }
    val geometry = layoutConnected(connected)
    val routed = routeConnections(geometry)
    val placements = ArrayList(geometry.placements)
    val right = placeStandalone(standalone, geometry, placements)
    cards = placements
    connectors = routed.first
    unroutedEdges = routed.second
    bounds = Rect(0, 0, right, max(geometry.height, placements.maxOf { it.rect.bottom } + mapPad))
  }

  private fun s(v: Int): Int = (v * scaleValue).roundToInt()

  private fun buildNodes(): LinkedHashMap<String, Node> {
    val created = LinkedHashMap<String, Node>()
    byName.values.sortedBy { it.name }.forEach { manifest ->
      val name = manifest.name!!
      val chain = graph.cycleChainOf(name)
      created[name] = Node(name, Kind.INSTALLED, manifest.version, chain != null, chain)
    }
    byName.values.sortedBy { it.name }.forEach { manifest ->
      manifest.depend.distinct().sorted().forEach { dep ->
        if (dep !in byName && !created.containsKey(dep)) {
          created[dep] = Node(dep, Kind.MISSING, null, false, null)
        }
      }
    }
    return created
  }

  private fun buildEdges(): List<Edge> {
    val adjacency = LinkedHashMap<Node, MutableList<Node>>()
    byName.values.sortedBy { it.name }.forEach { manifest ->
      adjacency[nodes[manifest.name!!]!!] = manifest.depend.distinct().sorted().mapNotNull { dep ->
        if (dep == manifest.name) null else nodes[dep]
      }.toMutableList()
    }
    val cycleEdges = HashSet<Pair<Node, Node>>()
    val state = HashMap<Node, Int>()
    fun visit(node: Node) {
      state[node] = VISITING
      adjacency[node].orEmpty().forEach { target ->
        when (state[target]) {
          null -> visit(target)
          VISITING -> cycleEdges.add(node to target)
          else -> {}
        }
      }
      state[node] = VISITED
    }
    nodes.values.forEach { if (state[it] == null) visit(it) }
    return buildList {
      adjacency.forEach { (from, targets) ->
        targets.forEach { target -> add(Edge(from, target, (from to target) in cycleEdges)) }
      }
    }
  }

  private class Geometry(
    val placements: List<CardPlacement>,
    val width: Int,
    val height: Int,
  )

  private fun layoutConnected(connected: Set<Node>): Geometry {
    if (connected.isEmpty()) return Geometry(emptyList(), mapPad, mapPad * 2)
    val alive = edges.filter { !it.cycle }
    val dependents = HashMap<Node, MutableList<Node>>()
    alive.forEach { dependents.getOrPut(it.to) { mutableListOf() }.add(it.from) }
    val deps = HashMap<Node, MutableList<Node>>()
    alive.forEach { deps.getOrPut(it.from) { mutableListOf() }.add(it.to) }

    val level = HashMap<Node, Int>()
    val pending = HashMap<Node, Int>()
    alive.forEach { pending[it.to] = (pending[it.to] ?: 0) + 1 }
    val queue = ArrayDeque<Node>()
    connected.forEach { if ((pending[it] ?: 0) == 0) { level[it] = 0; queue.add(it) } }
    while (queue.isNotEmpty()) {
      val node = queue.poll()
      deps[node].orEmpty().forEach { target ->
        val left = pending[target]!! - 1
        pending[target] = left
        if (left == 0) {
          level[target] = dependents[target]!!.maxOf { level[it]!! } + 1
          queue.add(target)
        }
      }
    }
    connected.forEach { if (!level.containsKey(it)) level[it] = 0 }

    val maxLevel = level.values.max()
    val order = (0..maxLevel).map { depth ->
      connected.filter { level[it] == depth }.sortedBy { it.name }.toMutableList()
    }.toMutableList()

    var geometry = geometryPass(order)
    repeat(2) {
      for (depth in 1..maxLevel) {
        val centers = geometry.placements.associate { it.node to it.rect.centerX }
        order[depth] = order[depth]
          .mapIndexed { index, node ->
            val bary = dependents[node].orEmpty().mapNotNull { centers[it] }.average().takeUnless { it.isNaN() }
            Triple(bary ?: Double.NEGATIVE_INFINITY, index, node)
          }
          .sortedWith(compareBy({ it.first }, { it.second }))
          .map { it.third }
          .toMutableList()
      }
      geometry = geometryPass(order)
    }
    return geometry
  }

  private fun geometryPass(order: List<List<Node>>): Geometry {
    val rows = ArrayList<List<Node>>()
    order.forEach { levelNodes ->
      var band = ArrayList<Node>()
      var bandWidth = 0
      levelNodes.forEach { node ->
        val width = widths.getValue(node)
        if (band.isNotEmpty() && bandWidth + s(H_GAP) + width > maxLineWidth) {
          rows.add(band)
          band = ArrayList()
          bandWidth = 0
        }
        bandWidth += (if (band.isEmpty()) 0 else s(H_GAP)) + width
        band.add(node)
      }
      if (band.isNotEmpty()) rows.add(band)
    }

    val rowWidthOf = { row: List<Node> -> row.sumOf { widths.getValue(it) } + s(H_GAP) * (row.size - 1) }
    val contentWidth = rows.maxOf(rowWidthOf)
    val placements = ArrayList<CardPlacement>()
    rows.forEachIndexed { rowIndex, row ->
      val rowTop = mapPad + (rows.size - 1 - rowIndex) * (cardHeight + vGap)
      val rowWidth = rowWidthOf(row)
      var x = mapPad + (contentWidth - rowWidth) / 2
      row.forEach { node ->
        val width = widths.getValue(node)
        placements.add(CardPlacement(node, Rect(x, rowTop, width, cardHeight)))
        x += width + s(H_GAP)
      }
    }
    val height = rows.size * cardHeight + (rows.size - 1) * vGap + mapPad * 2
    return Geometry(placements, contentWidth + mapPad * 2, height)
  }

  private fun placeStandalone(standalone: List<Node>, geometry: Geometry, placements: MutableList<CardPlacement>): Int {
    if (standalone.isEmpty()) return geometry.width
    val step = cardHeight + s(STANDALONE_V_GAP)
    val inner = geometry.height - mapPad * 2
    val perColumn = max(6, (inner + s(STANDALONE_V_GAP)) / step)
    var x = geometry.width + s(STANDALONE_GAP)
    standalone.chunked(perColumn).forEach { column ->
      val columnWidth = column.maxOf { widths.getValue(it) }
      var y = mapPad
      column.forEach { node ->
        placements.add(CardPlacement(node, Rect(x, y, widths.getValue(node), cardHeight)))
        y += step
      }
      x += columnWidth + s(H_GAP)
    }
    return x - s(H_GAP) + mapPad
  }

  private fun routeConnections(geometry: Geometry): Pair<List<Connection>, List<UnroutedEdge>> {
    if (geometry.placements.isEmpty()) return emptyList<Connection>() to emptyList()
    val rectOf = geometry.placements.associate { it.node to it.rect }
    val ports = allocatePorts(rectOf)
    val nameOrder = edges.sortedWith(compareBy({ it.from.name }, { it.to.name }))
    val targetLeftToRight = edges.sortedBy { rectOf.getValue(it.to).centerX }
    val targetTopToBottom = edges.sortedBy { rectOf.getValue(it.to).centerY }
    val orders = ArrayList<List<Edge>>()
    orders.addAll(listOf(
      edges.sortedByDescending { distance(ports.getValue(it).from.launch, ports.getValue(it).to.launch) },
      edges.sortedBy { distance(ports.getValue(it).from.launch, ports.getValue(it).to.launch) },
      nameOrder,
      edges.sortedWith(compareByDescending<Edge> { it.from.name }.thenByDescending { it.to.name }),
      targetLeftToRight,
      targetLeftToRight.reversed(),
      targetTopToBottom,
      targetTopToBottom.reversed(),
    ))
    // The early route claims a corridor. Try each deterministic first
    // edge, so a dense fan can choose an open set of corridors.
    nameOrder.indices.forEach { offset ->
      orders.add(nameOrder.drop(offset) + nameOrder.take(offset))
    }
    val routes = orders.distinct().map { routeInOrder(it, geometry, ports) }
    val best = routes.minWith(compareBy({ it.failed.size }, { it.length }))
    val bridgePoints = HashMap<Edge, MutableList<Pt>>()
    edges.forEachIndexed { index, first ->
      val firstPath = best.paths[first] ?: return@forEachIndexed
      edges.drop(index + 1).forEach { second ->
        val secondPath = best.paths[second] ?: return@forEach
        pathIntersections(firstPath, secondPath).forEach { point ->
          bridgePoints.getOrPut(second) { ArrayList() }.add(point)
        }
      }
    }
    val connections = edges.mapNotNull { edge ->
      best.paths[edge]?.let { points ->
        Connection(edge.from, edge.to, points, edge.cycle || edge.to.kind == Kind.MISSING, bridgePoints[edge].orEmpty())
      }
    }
    return connections to best.failed.map { UnroutedEdge(it.from, it.to) }
  }

  private fun allocatePorts(rectOf: Map<Node, Rect>): Map<Edge, Ports> {
    val fromSides = edges.associateWith { edge ->
      if (edge.cycle) sideToward(rectOf.getValue(edge.from), rectOf.getValue(edge.to)) else Side.TOP
    }
    val toSides = edges.associateWith { edge ->
      if (edge.cycle) sideToward(rectOf.getValue(edge.to), rectOf.getValue(edge.from)) else Side.BOTTOM
    }
    val from = HashMap<Edge, Endpoint>()
    val to = HashMap<Edge, Endpoint>()
    val requests = buildList {
      edges.forEach { edge ->
        add(PortRequest(edge, true, edge.from, fromSides.getValue(edge), edge.to))
        add(PortRequest(edge, false, edge.to, toSides.getValue(edge), edge.from))
      }
    }
    requests.groupBy { it.node to it.side }.forEach { (key, group) ->
      val rect = rectOf.getValue(key.first)
      group.sortedWith(compareBy(
        { portOrder(rect, rectOf.getValue(it.other), key.second) },
        { it.edge.from.name },
        { it.edge.to.name },
        { it.isFrom },
      )).forEachIndexed { index, request ->
        val port = endpoint(rect, key.second, index, group.size)
        if (request.isFrom) {
          from[request.edge] = port
        }
        else {
          to[request.edge] = port
        }
      }
    }
    return edges.associateWith { Ports(from.getValue(it), to.getValue(it)) }
  }

  private fun sideToward(from: Rect, to: Rect): Side {
    val dx = to.centerX - from.centerX
    val dy = to.centerY - from.centerY
    return if (abs(dx) > abs(dy)) {
      if (dx > 0) Side.RIGHT else Side.LEFT
    }
    else {
      if (dy > 0) Side.BOTTOM else Side.TOP
    }
  }

  private fun portOrder(from: Rect, to: Rect, side: Side): Double = when (side) {
    Side.TOP, Side.BOTTOM -> (to.centerX - from.centerX).toDouble() / max(1, abs(to.centerY - from.centerY))
    Side.LEFT, Side.RIGHT -> (to.centerY - from.centerY).toDouble() / max(1, abs(to.centerX - from.centerX))
  }

  private fun endpoint(rect: Rect, side: Side, index: Int, count: Int): Endpoint {
    val clearance = s(CLEARANCE)
    return when (side) {
      Side.TOP -> {
        val x = rect.left + rect.width * (index + 1) / (count + 1)
        Endpoint(Pt(x, rect.top), Pt(x, rect.top - clearance))
      }
      Side.RIGHT -> {
        val y = rect.top + rect.height * (index + 1) / (count + 1)
        Endpoint(Pt(rect.right, y), Pt(rect.right + clearance, y))
      }
      Side.BOTTOM -> {
        val x = rect.left + rect.width * (index + 1) / (count + 1)
        Endpoint(Pt(x, rect.bottom), Pt(x, rect.bottom + clearance))
      }
      Side.LEFT -> {
        val y = rect.top + rect.height * (index + 1) / (count + 1)
        Endpoint(Pt(rect.left, y), Pt(rect.left - clearance, y))
      }
    }
  }

  private fun routeInOrder(order: List<Edge>, geometry: Geometry, ports: Map<Edge, Ports>): RouteAttempt {
    val rects = geometry.placements.map { inflate(it.rect, s(CLEARANCE)) }
    val coordinates = createCoordinates(geometry, rects, ports.values)
    val reservedNodes = nodeIndexes(ports.values.flatMap { listOf(it.from.launch, it.to.launch) }, coordinates)
    val usedSegments = HashSet<Long>()
    val paths = HashMap<Edge, List<Pt>>()
    val failed = ArrayList<Edge>()
    var length = 0
    order.forEach { edge ->
      val pair = ports.getValue(edge)
      val path = findPath(pair.from.launch, pair.to.launch, rects, coordinates, reservedNodes, usedSegments)
      if (path == null) {
        failed.add(edge)
      }
      else {
        val points = simplify(listOf(pair.from.point) + path + pair.to.point)
        paths[edge] = points
        length += points.zipWithNext().sumOf { distance(it.first, it.second) }
      }
    }
    return RouteAttempt(paths, failed, length)
  }

  private fun inflate(rect: Rect, clearance: Int): Rect =
    Rect(rect.left - clearance, rect.top - clearance, rect.width + clearance * 2, rect.height + clearance * 2)

  private data class Coordinates(val xs: IntArray, val ys: IntArray)

  private fun createCoordinates(geometry: Geometry, obstacles: List<Rect>, ports: Collection<Ports>): Coordinates {
    val xs = sortedSetOf(0, geometry.width)
    val ys = sortedSetOf(0, geometry.height)
    val step = s(ROUTING_GRID_STEP)
    for (x in step until geometry.width step step) xs.add(x)
    for (y in step until geometry.height step step) ys.add(y)
    obstacles.forEach { rect ->
      xs.add(rect.left.coerceIn(0, geometry.width))
      xs.add(rect.right.coerceIn(0, geometry.width))
      ys.add(rect.top.coerceIn(0, geometry.height))
      ys.add(rect.bottom.coerceIn(0, geometry.height))
    }
    ports.forEach { pair ->
      listOf(pair.from.launch, pair.to.launch).forEach { point ->
        xs.add(point.x.coerceIn(0, geometry.width))
        ys.add(point.y.coerceIn(0, geometry.height))
      }
    }
    // Each route can use a different lane around the cards. These lanes
    // let a later route pass an earlier route without reusing its point.
    val clearance = s(CLEARANCE)
    val laneGap = s(ROUTE_LANE_GAP)
    for (index in 1..edges.size) {
      val offset = clearance + index * laneGap
      xs.add((mapPad - offset).coerceIn(0, geometry.width))
      xs.add((geometry.width - mapPad + offset).coerceIn(0, geometry.width))
      ys.add((mapPad - offset).coerceIn(0, geometry.height))
      ys.add((geometry.height - mapPad + offset).coerceIn(0, geometry.height))
    }
    return Coordinates(xs.toIntArray(), ys.toIntArray())
  }

  private fun findPath(
    start: Pt,
    target: Pt,
    obstacles: List<Rect>,
    coordinates: Coordinates,
    reservedNodes: Set<Int>,
    usedSegments: MutableSet<Long>,
  ): List<Pt>? {
    val xs = coordinates.xs
    val ys = coordinates.ys
    val width = xs.size
    val height = ys.size
    val xIndex = xs.withIndex().associate { it.value to it.index }
    val yIndex = ys.withIndex().associate { it.value to it.index }
    val startNode = yIndex.getValue(start.y) * width + xIndex.getValue(start.x)
    val targetNode = yIndex.getValue(target.y) * width + xIndex.getValue(target.x)
    fun point(node: Int): Pt = Pt(xs[node % width], ys[node / width])
    fun insideObstacle(node: Int): Boolean {
      if (node == startNode || node == targetNode) return false
      val p = point(node)
      return obstacles.any { p.x > it.left && p.x < it.right && p.y > it.top && p.y < it.bottom }
    }
    fun clear(from: Pt, to: Pt): Boolean {
      return obstacles.none { obstacle ->
        when {
          from.x == to.x && from.x > obstacle.left && from.x < obstacle.right ->
            max(min(from.y, to.y), obstacle.top) < min(max(from.y, to.y), obstacle.bottom)
          from.y == to.y && from.y > obstacle.top && from.y < obstacle.bottom ->
            max(min(from.x, to.x), obstacle.left) < min(max(from.x, to.x), obstacle.right)
          else -> false
        }
      }
    }

    val stateCount = width * height * DIRECTIONS
    val distances = IntArray(stateCount) { Int.MAX_VALUE }
    val previous = IntArray(stateCount) { -1 }
    data class QueueItem(val cost: Int, val state: Int)
    val queue = PriorityQueue(compareBy<QueueItem> { it.cost })
    val startState = startNode * DIRECTIONS + NO_DIRECTION
    distances[startState] = 0
    queue.add(QueueItem(0, startState))
    var targetState = -1
    val steps = arrayOf(intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0))
    while (queue.isNotEmpty()) {
      val current = queue.poll()
      if (current.cost != distances[current.state]) continue
      val node = current.state / DIRECTIONS
      val direction = current.state % DIRECTIONS
      if (node == targetNode) {
        targetState = current.state
        break
      }
      val x = node % width
      val y = node / width
      steps.forEachIndexed { nextDirection, step ->
        val nextX = x + step[0]
        val nextY = y + step[1]
        if (nextX !in 0 until width || nextY !in 0 until height) return@forEachIndexed
        val nextNode = nextY * width + nextX
        if (insideObstacle(nextNode) || (nextNode in reservedNodes && nextNode != targetNode) ||
            segmentKey(node, nextNode) in usedSegments) {
          return@forEachIndexed
        }
        val from = point(node)
        val to = point(nextNode)
        if (!clear(from, to)) return@forEachIndexed
        val nextState = nextNode * DIRECTIONS + nextDirection
        val cost = current.cost + distance(from, to) + if (direction == NO_DIRECTION || direction == nextDirection) 0 else s(BEND_COST)
        if (cost < distances[nextState]) {
          distances[nextState] = cost
          previous[nextState] = current.state
          queue.add(QueueItem(cost, nextState))
        }
      }
    }
    if (targetState == -1) return null
    val nodePath = ArrayList<Int>()
    var state = targetState
    while (state != -1) {
      nodePath.add(state / DIRECTIONS)
      state = previous[state]
    }
    nodePath.reverse()
    nodePath.zipWithNext().forEach { (from, to) -> usedSegments.add(segmentKey(from, to)) }
    return nodePath.map(::point)
  }

  private fun segmentKey(first: Int, second: Int): Long {
    val low = min(first, second)
    val high = max(first, second)
    return low.toLong() shl 32 or high.toLong()
  }

  private fun nodeIndexes(points: List<Pt>, coordinates: Coordinates): Set<Int> {
    val xIndex = coordinates.xs.withIndex().associate { it.value to it.index }
    val yIndex = coordinates.ys.withIndex().associate { it.value to it.index }
    return points.mapTo(HashSet()) { point -> yIndex.getValue(point.y) * coordinates.xs.size + xIndex.getValue(point.x) }
  }

  private fun pathIntersections(first: List<Pt>, second: List<Pt>): List<Pt> = buildList {
    first.zipWithNext().forEach { (firstFrom, firstTo) ->
      second.zipWithNext().forEach { (secondFrom, secondTo) ->
        segmentIntersection(firstFrom, firstTo, secondFrom, secondTo)?.let(::add)
      }
    }
  }.distinct()

  private fun segmentIntersection(firstFrom: Pt, firstTo: Pt, secondFrom: Pt, secondTo: Pt): Pt? {
    val firstVertical = firstFrom.x == firstTo.x
    val secondVertical = secondFrom.x == secondTo.x
    return when {
      firstVertical && secondVertical && firstFrom.x == secondFrom.x -> {
        val start = max(min(firstFrom.y, firstTo.y), min(secondFrom.y, secondTo.y))
        val end = min(max(firstFrom.y, firstTo.y), max(secondFrom.y, secondTo.y))
        if (start <= end) Pt(firstFrom.x, start) else null
      }
      !firstVertical && !secondVertical && firstFrom.y == secondFrom.y -> {
        val start = max(min(firstFrom.x, firstTo.x), min(secondFrom.x, secondTo.x))
        val end = min(max(firstFrom.x, firstTo.x), max(secondFrom.x, secondTo.x))
        if (start <= end) Pt(start, firstFrom.y) else null
      }
      firstVertical && pointOnSegment(Pt(firstFrom.x, secondFrom.y), firstFrom, firstTo) &&
      pointOnSegment(Pt(firstFrom.x, secondFrom.y), secondFrom, secondTo) -> Pt(firstFrom.x, secondFrom.y)
      !firstVertical && pointOnSegment(Pt(secondFrom.x, firstFrom.y), firstFrom, firstTo) &&
      pointOnSegment(Pt(secondFrom.x, firstFrom.y), secondFrom, secondTo) -> Pt(secondFrom.x, firstFrom.y)
      else -> null
    }
  }

  private fun pointOnSegment(point: Pt, from: Pt, to: Pt): Boolean =
    point.x in min(from.x, to.x)..max(from.x, to.x) && point.y in min(from.y, to.y)..max(from.y, to.y)

  private fun simplify(points: List<Pt>): List<Pt> {
    val result = ArrayList<Pt>()
    points.forEach { point ->
      if (result.lastOrNull() == point) return@forEach
      while (result.size >= 2) {
        val first = result[result.size - 2]
        val second = result.last()
        if ((first.x == second.x && second.x == point.x) || (first.y == second.y && second.y == point.y)) {
          result.removeAt(result.lastIndex)
        }
        else break
      }
      result.add(point)
    }
    return result
  }

  private fun distance(first: Pt, second: Pt): Int = abs(first.x - second.x) + abs(first.y - second.y)

  private companion object {
    const val CARD_H: Int = 46
    const val H_GAP: Int = 34
    const val V_GAP: Int = 64
    const val PAD: Int = 40
    const val MAX_LINE_WIDTH: Int = 5 * 178 + 4 * H_GAP
    const val STANDALONE_GAP: Int = 64
    const val STANDALONE_V_GAP: Int = 16
    const val CLEARANCE: Int = 10
    const val BASE_ROUTE_MARGIN: Int = 192
    const val ROUTE_MARGIN_PER_EDGE: Int = 48
    const val ROUTE_LANE_GAP: Int = 16
    const val ROUTING_GRID_STEP: Int = 16
    const val CARD_PORT_GAP: Int = 16
    const val BEND_COST: Int = 18
    const val DIRECTIONS: Int = 5
    const val NO_DIRECTION: Int = 4
    const val VISITING: Int = 1
    const val VISITED: Int = 2
  }
}
