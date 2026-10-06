package net.runelite.client.plugins.microbot.efficientwalker;

import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.efficientwalker.LocalTransportCatalog.Transport;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;

final class WorldPathfinder
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorldPathfinder.class);
	private static final String RESOURCE = "/net/runelite/client/plugins/microbot/efficientwalker/collision-map.zip";
	private static final int REGION_SIZE = 64;
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int FLAG_COUNT = 2;
	private static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
	private static final int[] DY = {0, 0, -1, 1, -1, -1, 1, 1};
	private static final int MAX_VISITED = 750_000;
	private static final long MAX_SEARCH_NANOS = 2_000_000_000L;
	// Prefer a short walk around an optional object interaction; required transports still win.
	private static final int TRANSPORT_COST = 4;
	private static final int COST_BUCKETS = TRANSPORT_COST + 1;

	private final Int2ObjectOpenHashMap<long[]> regions;
	private final Int2ObjectOpenHashMap<List<Transport>> walkEdges = new Int2ObjectOpenHashMap<>();
	private final Int2ObjectOpenHashMap<List<Transport>> transportEdges = new Int2ObjectOpenHashMap<>();
	private final Int2ObjectOpenHashMap<List<Transport>> reverseTransportEdges = new Int2ObjectOpenHashMap<>();
	private final IntOpenHashSet transportEndpoints = new IntOpenHashSet();
	private final Predicate<Transport> available;

	private WorldPathfinder(Int2ObjectOpenHashMap<long[]> regions, List<Transport> transports,
		Predicate<Transport> available)
	{
		this.regions = regions;
		this.available = available;
		for (Transport transport : transports)
		{
			if (transport.isWalkEdge())
			{
				walkEdges.computeIfAbsent(pack(transport.approach), ignored -> new ArrayList<>())
					.add(transport);
				addTransportEndpoints(transport);
			}
			else
			{
				transportEdges.computeIfAbsent(pack(transport.approach), ignored -> new ArrayList<>())
					.add(transport);
				reverseTransportEdges.computeIfAbsent(pack(transport.landing), ignored -> new ArrayList<>())
					.add(transport);
				addTransportEndpoints(transport);
			}
		}
	}

	private void addTransportEndpoints(Transport transport)
	{
		transportEndpoints.add(pack(transport.approach));
		transportEndpoints.add(pack(transport.landing));
	}

	static WorldPathfinder load(List<Transport> transports)
	{
		return load(transports, Transport::isAvailable);
	}

	static WorldPathfinder load(List<Transport> transports, Predicate<Transport> available)
	{
		try (InputStream input = openResource())
		{
			return from(input, transports, available);
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Could not load the collision map; using local route fallback", ex);
			return new WorldPathfinder(new Int2ObjectOpenHashMap<>(), transports, available);
		}
	}

	static WorldPathfinder from(InputStream input, List<Transport> transports) throws IOException
	{
		return from(input, transports, ignored -> true);
	}

	private static WorldPathfinder from(InputStream input, List<Transport> transports,
		Predicate<Transport> available) throws IOException
	{
		final Int2ObjectOpenHashMap<long[]> regions = new Int2ObjectOpenHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(input))
		{
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null)
			{
				final String[] coordinates = entry.getName().split("_");
				if (coordinates.length == 2)
				{
					final int regionX = Integer.parseInt(coordinates[0]);
					final int regionY = Integer.parseInt(coordinates[1]);
					regions.put(packRegion(regionX, regionY), BitSet.valueOf(zip.readAllBytes()).toLongArray());
				}
			}
		}
		return new WorldPathfinder(regions, transports, available);
	}

	List<WorldPoint> find(WorldPoint start, WorldPoint target)
	{
		return findResult(start, target).path;
	}

	List<WorldPoint> find(WorldPoint start, WorldPoint target, Set<Transport> excluded)
	{
		return findResult(start, target, excluded).path;
	}

	List<WorldPoint> find(WorldPoint start, WorldPoint target, Set<Transport> excluded,
		Set<Long> liveWalkEdges)
	{
		return findResult(start, target, excluded, liveWalkEdges).path;
	}

	SearchResult findResult(WorldPoint start, WorldPoint target)
	{
		return findResult(start, target, Collections.emptySet());
	}

	SearchResult findResult(WorldPoint start, WorldPoint target, Set<Transport> excluded)
	{
		return findResult(start, target, excluded, Collections.emptySet());
	}

	SearchResult findResult(WorldPoint start, WorldPoint target, Set<Transport> excluded,
		Set<Long> liveWalkEdges)
	{
		return findResult(start, target, excluded, liveWalkEdges, available);
	}

	SearchResult findResult(WorldPoint start, WorldPoint target, Set<Transport> excluded,
		Set<Long> liveWalkEdges, Set<Transport> availableTransports)
	{
		return findResult(start, target, excluded, liveWalkEdges, availableTransports::contains);
	}

	private SearchResult findResult(WorldPoint start, WorldPoint target, Set<Transport> excluded,
		Set<Long> liveWalkEdges, Predicate<Transport> availableTransport)
	{
		return findResult(Collections.singletonList(start), target, excluded, liveWalkEdges,
			availableTransport);
	}

	SearchResult findResult(Collection<WorldPoint> starts, WorldPoint target, Set<Transport> excluded,
		Set<Long> liveWalkEdges, Set<Transport> availableTransports)
	{
		return findResult(starts, target, excluded, liveWalkEdges, availableTransports::contains);
	}

	private SearchResult findResult(Collection<WorldPoint> starts, WorldPoint target,
		Set<Transport> excluded, Set<Long> liveWalkEdges, Predicate<Transport> availableTransport)
	{
		if (isWilderness(target))
		{
			return new SearchResult(Collections.emptyList(), EfficientWalker.PlanningFailure.ROUTE_UNSUPPORTED,
				Integer.MAX_VALUE);
		}
		if (regions.isEmpty())
		{
			return new SearchResult(Collections.emptyList(), EfficientWalker.PlanningFailure.ROUTE_UNSUPPORTED,
				Integer.MAX_VALUE);
		}
		if (MotherlodeRockfalls.TILES.contains(target) || !isStandable(target))
		{
			return new SearchResult(Collections.emptyList(), EfficientWalker.PlanningFailure.TARGET_NOT_STANDABLE,
				Integer.MAX_VALUE);
		}
		return search(starts, Collections.singleton(target), excluded, primitiveEdges(liveWalkEdges),
			availableTransport, new Int2ByteOpenHashMap());
	}

	SearchResult findNearestResult(WorldPoint start, Collection<WorldPoint> targets,
		Set<Transport> excluded, Set<Long> liveWalkEdges, Set<Transport> availableTransports)
	{
		return findNearestResult(Collections.singleton(start), targets, excluded, liveWalkEdges,
			availableTransports);
	}

	SearchResult findNearestResult(Collection<WorldPoint> starts, Collection<WorldPoint> targets,
		Set<Transport> excluded, Set<Long> liveWalkEdges, Set<Transport> availableTransports)
	{
		return findNearestResult(starts, targets, excluded, liveWalkEdges, availableTransports,
			new Int2ByteOpenHashMap());
	}

	SearchResult findNearestResult(Collection<WorldPoint> starts, Collection<WorldPoint> targets,
		Set<Transport> excluded, Set<Long> liveWalkEdges, Set<Transport> availableTransports,
		Int2ByteOpenHashMap wilderness)
	{
		if (regions.isEmpty() || targets == null)
		{
			return new SearchResult(Collections.emptyList(), EfficientWalker.PlanningFailure.ROUTE_UNSUPPORTED,
				Integer.MAX_VALUE);
		}
		final List<WorldPoint> reachableTargets = targets.stream()
			.filter(target -> target != null && !MotherlodeRockfalls.TILES.contains(target) && !isWilderness(target) && isStandable(target))
			.collect(java.util.stream.Collectors.toList());
		if (reachableTargets.isEmpty())
		{
			return new SearchResult(Collections.emptyList(), EfficientWalker.PlanningFailure.ROUTE_UNSUPPORTED,
				Integer.MAX_VALUE);
		}
		return search(starts, reachableTargets, excluded, primitiveEdges(liveWalkEdges),
			availableTransports::contains, wilderness);
	}

	SearchResult findNearestResult(Collection<WorldPoint> starts, Collection<WorldPoint> targets,
		Set<Transport> excluded, Set<Long> liveWalkEdges, Set<Transport> availableTransports,
		Map<Transport, Integer> fares, int wallet)
	{
		return findNearestResult(starts, targets, excluded, liveWalkEdges, availableTransports,
			fares, wallet, new Int2ByteOpenHashMap());
	}

	SearchResult findNearestResult(Collection<WorldPoint> starts, Collection<WorldPoint> targets,
		Set<Transport> excluded, Set<Long> liveWalkEdges, Set<Transport> availableTransports,
		Map<Transport, Integer> fares, int wallet, Int2ByteOpenHashMap wilderness)
	{
		final Map<Transport, Integer> coinFares = Collections.unmodifiableMap(new HashMap<>(fares));
		if (wallet < 0 || coinFares.entrySet().stream().anyMatch(entry -> entry.getKey() == null
			|| entry.getValue() == null || entry.getValue() < 0
			|| entry.getValue() > 0 && entry.getKey().isWalkEdge())
			|| availableTransports.stream().anyMatch(edge -> CharterShips.isCharter(edge) && coinFares.getOrDefault(edge, 0) <= 0))
		{
			throw new IllegalArgumentException("Invalid transport coin budget");
		}
		long deadline = System.nanoTime() + MAX_SEARCH_NANOS;
		LongSet primitiveEdges = primitiveEdges(liveWalkEdges);
		SearchResult ordinary = findNearestResult(starts, targets, excluded, primitiveEdges,
			availableTransports, wilderness);
		if (coinFares.isEmpty()) { return ordinary; }
		List<Transport> chosen = new ArrayList<>();
		long spent = 0;
		for (int index = 1; index < ordinary.path.size(); index++)
		{
			WorldPoint from = ordinary.path.get(index - 1), to = ordinary.path.get(index);
			if (from.getPlane() == to.getPlane() && from.distanceTo2D(to) == 1
				&& canStep(from.getX(), from.getY(), from.getPlane(), to.getX(), to.getY(), primitiveEdges, availableTransports::contains)) { continue; }
			List<Transport> edges = transportEdges.get(pack(from));
			Transport edge = edges == null ? null : edges.stream()
				.filter(candidate -> candidate.landing.equals(to) && !excluded.contains(candidate) && availableTransports.contains(candidate))
				.min(Comparator.comparingInt(candidate -> coinFares.getOrDefault(candidate, 0))).orElse(null);
			if (edge != null) { chosen.add(edge); spent += coinFares.getOrDefault(edge, 0); }
		}
		if (!ordinary.path.isEmpty() && spent <= wallet)
		{
			return new SearchResult(ordinary.path, ordinary.failure, ordinary.cost, chosen, (int) spent);
		}
		if (regions.isEmpty() || targets == null) { return ordinary; }
		Set<Integer> goals = new java.util.HashSet<>();
		for (WorldPoint target : targets)
		{
			if (target != null && !MotherlodeRockfalls.TILES.contains(target) && !isWilderness(target) && isStandable(target)) { goals.add(pack(target)); }
		}
		if (goals.isEmpty()) { return ordinary; }
		return searchWithCoins(starts, goals, excluded, primitiveEdges, availableTransports,
			coinFares, wallet, deadline, wilderness);
	}

	private SearchResult searchWithCoins(Collection<WorldPoint> starts, Set<Integer> goals,
		Set<Transport> excluded, LongSet liveWalkEdges, Set<Transport> availableTransports,
		Map<Transport, Integer> fares, int wallet, long deadline, Int2ByteOpenHashMap wilderness)
	{
		Int2ObjectOpenHashMap<List<CoinNode>> labels = new Int2ObjectOpenHashMap<>();
		PriorityQueue<CoinNode> pending = new PriorityQueue<>(Comparator.comparingInt((CoinNode node) -> node.cost)
			.thenComparingInt(node -> node.coins).thenComparingInt(node -> node.point));
		int accepted = 0;
		for (WorldPoint start : starts)
		{
			if (start != null && !isWilderness(start)
				&& addCoinNode(new CoinNode(pack(start), 0, 0, null, null), labels, pending)) { accepted++; }
		}
		while (!pending.isEmpty() && accepted <= MAX_VISITED && System.nanoTime() <= deadline
			&& !Thread.currentThread().isInterrupted())
		{
			CoinNode current = pending.remove();
			if (!labels.get(current.point).contains(current)) { continue; }
			if (goals.contains(current.point))
			{
				List<WorldPoint> path = new ArrayList<>();
				List<Transport> chosen = new ArrayList<>();
				for (CoinNode node = current; node != null; node = node.parent)
				{
					path.add(new WorldPoint(unpackX(node.point), unpackY(node.point), unpackPlane(node.point)));
					if (node.transport != null) { chosen.add(node.transport); }
				}
				Collections.reverse(path);
				Collections.reverse(chosen);
				return new SearchResult(Collections.unmodifiableList(path), EfficientWalker.PlanningFailure.NONE, current.cost, chosen, current.coins);
			}
			int x = unpackX(current.point), y = unpackY(current.point), plane = unpackPlane(current.point);
			for (int direction = 0; direction < DX.length; direction++)
			{
				int nx = x + DX[direction], ny = y + DY[direction], next = pack(nx, ny, plane);
				if (isWilderness(next, nx, ny, plane, wilderness)
					|| !canStep(x, y, plane, nx, ny, liveWalkEdges, availableTransports::contains)
					&& !(direction < 4 && isBlocked(nx, ny, plane) && hasAvailableTransportFrom(next, excluded, availableTransports::contains))) { continue; }
				if (addCoinNode(new CoinNode(next, current.cost + 1, current.coins, current, null), labels, pending)) { accepted++; }
			}
			List<Transport> edges = transportEdges.get(current.point);
			if (edges == null) { continue; }
			for (Transport edge : edges)
			{
				int fare = fares.getOrDefault(edge, 0);
				if (excluded.contains(edge) || !availableTransports.contains(edge) || isWilderness(edge.landing)
					|| fare > wallet - current.coins) { continue; }
				if (addCoinNode(new CoinNode(pack(edge.landing), current.cost + TRANSPORT_COST,
					current.coins + fare, current, edge), labels, pending)) { accepted++; }
			}
		}
		return new SearchResult(Collections.emptyList(), pending.isEmpty() && !Thread.currentThread().isInterrupted()
			? EfficientWalker.PlanningFailure.ROUTE_UNSUPPORTED : EfficientWalker.PlanningFailure.SEARCH_LIMIT, Integer.MAX_VALUE);
	}

	private static boolean addCoinNode(CoinNode node, Int2ObjectOpenHashMap<List<CoinNode>> labels, PriorityQueue<CoinNode> pending)
	{
		List<CoinNode> existing = labels.computeIfAbsent(node.point, ignored -> new ArrayList<>());
		for (CoinNode old : existing)
		{
			if (old.cost <= node.cost && old.coins <= node.coins) { return false; }
		}
		existing.removeIf(old -> node.cost <= old.cost && node.coins <= old.coins);
		existing.add(node);
		pending.add(node);
		return true;
	}

	private static final class CoinNode
	{
		private final int point;
		private final int cost;
		private final int coins;
		private final CoinNode parent;
		private final Transport transport;

		private CoinNode(int point, int cost, int coins, CoinNode parent, Transport transport)
		{
			this.point = point;
			this.cost = cost;
			this.coins = coins;
			this.parent = parent;
			this.transport = transport;
		}
	}

	private SearchResult search(Collection<WorldPoint> starts, Collection<WorldPoint> targets,
		Set<Transport> excluded, LongSet liveWalkEdges, Predicate<Transport> availableTransport,
		Int2ByteOpenHashMap wilderness)
	{
		final SearchFront forward = new SearchFront(starts);
		final SearchFront backward = new SearchFront(targets);
		final long deadline = System.nanoTime() + MAX_SEARCH_NANOS;
		int bestCost = Integer.MAX_VALUE;
		int meeting = -1;
		while (forward.minimum() != Integer.MAX_VALUE && backward.minimum() != Integer.MAX_VALUE
			&& forward.previous.size() + backward.previous.size() <= MAX_VISITED
			&& System.nanoTime() <= deadline && !Thread.currentThread().isInterrupted())
		{
			if ((long) forward.minimum() + backward.minimum() >= bestCost) break;
			final boolean reverse = backward.minimum() < forward.minimum();
			final SearchFront front = reverse ? backward : forward;
			final SearchFront other = reverse ? forward : backward;
			final int current = front.remove();
			final int distance = front.costs.get(current);
			final int otherCost = other.costs.get(current);
			if (otherCost != Integer.MAX_VALUE && distance + otherCost < bestCost)
			{
				bestCost = distance + otherCost;
				meeting = current;
			}
			final int x = unpackX(current);
			final int y = unpackY(current);
			final int plane = unpackPlane(current);
			for (int direction = 0; direction < DX.length; direction++)
			{
				final int nextX = x + DX[direction];
				final int nextY = y + DY[direction];
				final int next = pack(nextX, nextY, plane);
				final int fromX = reverse ? nextX : x;
				final int fromY = reverse ? nextY : y;
				final int toX = reverse ? x : nextX;
				final int toY = reverse ? y : nextY;
				if (isWilderness(next, nextX, nextY, plane, wilderness)
					|| !canStep(fromX, fromY, plane, toX, toY, liveWalkEdges, availableTransport)
					&& !(direction < 4 && isBlocked(toX, toY, plane)
						&& hasAvailableTransportFrom(reverse ? current : next, excluded, availableTransport)))
				{
					continue;
				}
				front.add(next, current, distance + 1);
			}
			final List<Transport> edges = (reverse ? reverseTransportEdges : transportEdges).get(current);
			if (edges != null)
			{
				for (Transport edge : edges)
				{
					final WorldPoint next = reverse ? edge.approach : edge.landing;
					if (!excluded.contains(edge) && availableTransport.test(edge) && !isWilderness(next))
					{
						front.add(pack(next), current, distance + TRANSPORT_COST);
					}
				}
			}
		}
		final boolean exhausted = forward.minimum() == Integer.MAX_VALUE || backward.minimum() == Integer.MAX_VALUE;
		if (meeting != -1 && (exhausted || (long) forward.minimum() + backward.minimum() >= bestCost)
			&& !Thread.currentThread().isInterrupted())
		{
			final List<WorldPoint> path = new ArrayList<>(buildPath(meeting, forward.previous));
			final List<WorldPoint> tail = buildPath(meeting, backward.previous);
			for (int index = tail.size() - 2; index >= 0; index--) path.add(tail.get(index));
			return new SearchResult(Collections.unmodifiableList(path), EfficientWalker.PlanningFailure.NONE, bestCost);
		}
		return new SearchResult(Collections.emptyList(), exhausted
			? EfficientWalker.PlanningFailure.ROUTE_UNSUPPORTED
			: EfficientWalker.PlanningFailure.SEARCH_LIMIT, Integer.MAX_VALUE);
	}

	private static final class SearchFront
	{
		private final Int2IntOpenHashMap previous = new Int2IntOpenHashMap();
		private final Int2IntOpenHashMap costs = new Int2IntOpenHashMap();
		private final IntArrayFIFOQueue[] pending = new IntArrayFIFOQueue[COST_BUCKETS];
		private int queued;
		private int distance;

		private SearchFront(Collection<WorldPoint> seeds)
		{
			costs.defaultReturnValue(Integer.MAX_VALUE);
			for (int index = 0; index < pending.length; index++) pending[index] = new IntArrayFIFOQueue();
			for (WorldPoint seed : seeds)
			{
				if (seed != null && !isWilderness(seed)) add(pack(seed), -1, 0);
			}
		}

		private void add(int point, int parent, int cost)
		{
			if (relax(point, parent, cost, previous, costs, pending)) queued++;
		}

		private int minimum()
		{
			while (queued > 0)
			{
				final IntArrayFIFOQueue bucket = pending[distance % COST_BUCKETS];
				if (bucket.isEmpty()) distance++;
				else if (costs.get(bucket.firstInt()) != distance)
				{
					bucket.dequeueInt();
					queued--;
				}
				else return distance;
			}
			return Integer.MAX_VALUE;
		}

		private int remove()
		{
			queued--;
			return pending[distance % COST_BUCKETS].dequeueInt();
		}
	}

	static boolean isWilderness(WorldPoint point)
	{
		return point != null && Rs2Pvp.getWildernessLevelFrom(point) > 0;
	}

	private static boolean isWilderness(int point, int x, int y, int plane,
		Int2ByteOpenHashMap cache)
	{
		byte cached = cache.get(point);
		if (cached == 0)
		{
			cached = (byte) (isWilderness(new WorldPoint(x, y, plane)) ? 2 : 1);
			cache.put(point, cached);
		}
		return cached == 2;
	}

	private static LongSet primitiveEdges(Set<Long> edges)
	{
		return edges instanceof LongSet ? (LongSet) edges
			: edges.isEmpty() ? LongSets.EMPTY_SET : new LongOpenHashSet(edges);
	}

	private static boolean relax(int next, int current, int cost,
		Int2IntOpenHashMap previous, Int2IntOpenHashMap costs, IntArrayFIFOQueue[] pending)
	{
		if (cost >= costs.get(next))
		{
			return false;
		}
		previous.put(next, current);
		costs.put(next, cost);
		pending[cost % COST_BUCKETS].enqueue(next);
		return true;
	}

	List<WorldPoint> provisionalApproaches(WorldPoint anchor, int west, int east, int south, int north)
	{
		for (int radius = 1; radius <= REGION_SIZE; radius++)
		{
			List<WorldPoint> candidates = QuestNpcApproach.waypoints(anchor, radius);
			candidates.removeIf(point -> point.getX() >= west && point.getX() <= east
				&& point.getY() >= south && point.getY() <= north || !isStandable(point)
				|| isWilderness(point));
			if (!candidates.isEmpty()) { return candidates; }
		}
		return Collections.emptyList();
	}

	boolean isStandable(WorldPoint point)
	{
		return transportEndpoints.contains(pack(point))
			|| !isBlocked(point.getX(), point.getY(), point.getPlane());
	}

	boolean canStep(int fromX, int fromY, int plane, int toX, int toY)
	{
		return canStep(fromX, fromY, plane, toX, toY, LongSets.EMPTY_SET, available);
	}

	private boolean canStep(int fromX, int fromY, int plane, int toX, int toY,
		LongSet liveWalkEdges, Predicate<Transport> availableTransport)
	{
		final int dx = toX - fromX;
		final int dy = toY - fromY;
		if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || dx == 0 && dy == 0)
		{
			return false;
		}

		final int from = pack(fromX, fromY, plane);
		final int to = pack(toX, toY, plane);
		boolean fromRockfall = MotherlodeRockfalls.contains(fromX, fromY, plane);
		boolean toRockfall = MotherlodeRockfalls.contains(toX, toY, plane);
		if (fromRockfall || toRockfall)
		{
			return (dx == 0 || dy == 0) && hasWalkEdge(from, to, availableTransport)
				&& (fromRockfall || !isBlocked(fromX, fromY, plane))
				&& (toRockfall || !isBlocked(toX, toY, plane));
		}
		if (dx != 0 && dy != 0 && (MotherlodeRockfalls.contains(fromX + dx, fromY, plane)
			|| MotherlodeRockfalls.contains(fromX, fromY + dy, plane))) { return false; }

		if (dx == 0 || dy == 0)
		{
			if (hasWalkEdge(from, to, availableTransport) || liveWalkEdges.contains(edgeKey(from, to)))
			{
				return true;
			}
			if (isBlocked(fromX, fromY, plane))
			{
				return !isBlocked(toX, toY, plane);
			}
			return dx < 0 ? west(fromX, fromY, plane)
				: dx > 0 ? east(fromX, fromY, plane)
				: dy < 0 ? south(fromX, fromY, plane)
				: north(fromX, fromY, plane);
		}

		if (hasWalkEdge(from, pack(fromX + dx, fromY, plane), availableTransport)
			|| hasWalkEdge(from, pack(fromX, fromY + dy, plane), availableTransport)
			|| liveWalkEdges.contains(edgeKey(from, pack(fromX + dx, fromY, plane)))
			|| liveWalkEdges.contains(edgeKey(from, pack(fromX, fromY + dy, plane))))
		{
			return false;
		}
		if (isBlocked(fromX, fromY, plane))
		{
			return !isBlocked(toX, toY, plane)
				&& !isBlocked(fromX + dx, fromY, plane)
				&& !isBlocked(fromX, fromY + dy, plane);
		}
		return dx < 0
			? dy < 0 ? south(fromX, fromY, plane) && west(fromX, fromY - 1, plane)
				&& west(fromX, fromY, plane) && south(fromX - 1, fromY, plane)
				: north(fromX, fromY, plane) && west(fromX, fromY + 1, plane)
				&& west(fromX, fromY, plane) && north(fromX - 1, fromY, plane)
			: dy < 0 ? south(fromX, fromY, plane) && east(fromX, fromY - 1, plane)
				&& east(fromX, fromY, plane) && south(fromX + 1, fromY, plane)
				: north(fromX, fromY, plane) && east(fromX, fromY + 1, plane)
				&& east(fromX, fromY, plane) && north(fromX + 1, fromY, plane);
	}

	private boolean hasWalkEdge(int from, int to, Predicate<Transport> availableTransport)
	{
		final List<Transport> edges = walkEdges.get(from);
		return edges != null && edges.stream()
			.anyMatch(edge -> pack(edge.landing) == to && availableTransport.test(edge));
	}

	private boolean hasAvailableTransportFrom(int from, Set<Transport> excluded,
		Predicate<Transport> availableTransport)
	{
		final List<Transport> edges = transportEdges.get(from);
		return edges != null && edges.stream()
			.anyMatch(edge -> !edge.isGatedBoundary()
				&& !excluded.contains(edge) && availableTransport.test(edge));
	}

	private boolean isBlocked(int x, int y, int plane)
	{
		return !north(x, y, plane) && !south(x, y, plane)
			&& !east(x, y, plane) && !west(x, y, plane);
	}

	private boolean north(int x, int y, int plane)
	{
		return get(x, y, plane, 0);
	}

	private boolean south(int x, int y, int plane)
	{
		return north(x, y - 1, plane);
	}

	private boolean east(int x, int y, int plane)
	{
		return get(x, y, plane, 1);
	}

	private boolean west(int x, int y, int plane)
	{
		return east(x - 1, y, plane);
	}

	private boolean get(int x, int y, int plane, int flag)
	{
		final long[] words = regions.get(packRegion(x / REGION_SIZE, y / REGION_SIZE));
		if (words == null || plane < 0 || plane > 3)
		{
			return false;
		}
		final int bit = (plane * REGION_SIZE * REGION_SIZE
			+ (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK)) * FLAG_COUNT + flag;
		final int word = bit >>> 6;
		return word < words.length && (words[word] >>> (bit & 63) & 1L) != 0;
	}

	private static List<WorldPoint> buildPath(int end, Int2IntOpenHashMap previous)
	{
		final List<WorldPoint> path = new ArrayList<>();
		for (int point = end; point != -1; point = previous.get(point))
		{
			path.add(new WorldPoint(unpackX(point), unpackY(point), unpackPlane(point)));
		}
		Collections.reverse(path);
		return Collections.unmodifiableList(path);
	}

	private static int pack(WorldPoint point)
	{
		return pack(point.getX(), point.getY(), point.getPlane());
	}

	static long edgeKey(int fromX, int fromY, int plane, int toX, int toY)
	{
		return edgeKey(pack(fromX, fromY, plane), pack(toX, toY, plane));
	}

	private static long edgeKey(int from, int to)
	{
		final int first = Math.min(from, to);
		final int second = Math.max(from, to);
		return (long) first << 32 | second & 0xFFFFFFFFL;
	}

	private static int pack(int x, int y, int plane)
	{
		return x & 0x7FFF | (y & 0x7FFF) << 15 | (plane & 3) << 30;
	}

	private static int unpackX(int point)
	{
		return point & 0x7FFF;
	}

	private static int unpackY(int point)
	{
		return point >>> 15 & 0x7FFF;
	}

	private static int unpackPlane(int point)
	{
		return point >>> 30 & 3;
	}

	private static int packRegion(int x, int y)
	{
		return x & 0xFFFF | (y & 0xFFFF) << 16;
	}

	private static InputStream openResource() throws IOException
	{
		final InputStream resource = LocalTransportCatalog.resourceInput(RESOURCE);
		if (resource != null)
		{
			return resource;
		}
		Path source = Path.of("runelite-client/src/main/resources" + RESOURCE);
		if (!Files.isRegularFile(source))
		{
			source = Path.of("src/main/resources" + RESOURCE);
		}
		return Files.newInputStream(source);
	}

	static final class SearchResult
	{
		final List<WorldPoint> path;
		final EfficientWalker.PlanningFailure failure;
		final int cost;
		final List<Transport> transitions;
		final int coinsSpent;

		private SearchResult(List<WorldPoint> path, EfficientWalker.PlanningFailure failure, int cost)
		{
			this(path, failure, cost, Collections.emptyList(), -1);
		}

		private SearchResult(List<WorldPoint> path, EfficientWalker.PlanningFailure failure, int cost,
			List<Transport> transitions, int coinsSpent)
		{
			this.path = path;
			this.failure = failure;
			this.cost = cost;
			this.transitions = Collections.unmodifiableList(new ArrayList<>(transitions));
			this.coinsSpent = coinsSpent;
		}
	}

}
