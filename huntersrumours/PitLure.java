package net.runelite.client.plugins.microbot.huntersrumours;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

final class PitLure
{
	private static final int MAX_LURE_LEAD = 3;

	enum Outcome { READY, WAIT, SWITCH_PIT, COLLECT, EXPIRED, BLOCKED }

	private final NPC prey;
	private int lastCrossedPit = -1;
	private int pendingPit = -1;
	private int started;
	private boolean leapSeen;
	private boolean refused;
	private WorldPoint takeoff;
	private WorldPoint landing;

	PitLure(NPC prey)
	{
		this.prey = prey;
	}

	NPC prey()
	{
		return prey;
	}

	static boolean available(NPC prey, Player player)
	{
		return prey != null && !prey.isDead()
			&& (prey.getInteracting() == null || prey.getInteracting() == player);
	}

	synchronized boolean canJump(NPC visible, PitTrap pit, int state)
	{
		return prey != null && visible == prey && pendingPit < 0 && pit != null
			&& pit.prepared() && state == 1 && pit.baseId != lastCrossedPit;
	}

	synchronized void submitted(PitTrap pit, int tick, WorldPoint from, WorldPoint to)
	{
		if (from == null || to == null || from.getPlane() != to.getPlane() || from.equals(to)
			|| from.getX() != to.getX() && from.getY() != to.getY()) throw new IllegalArgumentException("Pit crossing must use two distinct pads on one axis.");
		pendingPit = pit.baseId;
		started = tick;
		leapSeen = false;
		refused = false;
		takeoff = from;
		landing = to;
	}

	synchronized void message(String message, int tick)
	{
		if (pendingPit >= 0 && tick >= started
			&& ("The kyatt won't jump the same pit twice in a row.".equals(message)
				|| "The graahk won't jump the same pit twice in a row.".equals(message))) refused = true;
	}

	synchronized Outcome observe(int tick, NPC visible, int baseId, int state, int animation, WorldPoint position)
	{
		if (pendingPit < 0) return Outcome.READY;
		if (baseId != pendingPit) return Outcome.BLOCKED;
		if (state == 3 || state == 4)
		{
			pendingPit = -1;
			return Outcome.COLLECT;
		}
		if (state == 0)
		{
			pendingPit = -1;
			return Outcome.EXPIRED;
		}
		if (visible != prey || position == null || takeoff == null || landing == null) return Outcome.BLOCKED;
		if (animation == 5231) leapSeen = true;
		if (state == 1 && (refused || leapSeen && crossed(position)))
		{
			lastCrossedPit = pendingPit;
			pendingPit = -1;
			return Outcome.SWITCH_PIT;
		}
		return Outcome.WAIT;
	}

	static WorldPoint guide(WorldView world, WorldArea prey, WorldPoint takeoff, WorldPoint landing)
	{
		return guide(world, prey, takeoff, landing, point -> true);
	}

	static WorldPoint approach(WorldView world, WorldPoint player, WorldArea prey, WorldPoint takeoff,
		WorldPoint landing, java.util.function.Predicate<WorldPoint> clear)
	{
		if (world == null || player == null || prey == null || takeoff == null || landing == null
			|| player.getPlane() != prey.getPlane() || prey.getWidth() < 1
			|| prey.getWidth() != prey.getHeight()) return null;
		WorldPoint target = PitTrap.aligned(takeoff, prey.toWorldPoint(), prey.getWidth(), takeoff, landing)
			? takeoff : guide(world, prey, takeoff, landing, clear, player);
		if (target == null) return null;
		int lead = Math.max(MAX_LURE_LEAD, prey.distanceTo(player));
		java.util.function.Predicate<WorldPoint> safe = point -> clear.test(point) && !prey.contains(point)
			&& prey.distanceTo(point) <= lead;
		if (!safe.test(target)) return null;
		if (player.equals(target)) return player;
		ArrayDeque<WorldPoint> queue = new ArrayDeque<>();
		Map<WorldPoint, WorldPoint> previous = new HashMap<>();
		queue.add(player);
		previous.put(player, null);
		while (!queue.isEmpty())
		{
			WorldPoint point = queue.remove();
			if (point.equals(target)) return firstLeg(previous, point);
			for (int[] direction : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {-1, -1}, {1, -1}})
			{
				WorldPoint next = point.dx(direction[0]).dy(direction[1]);
				if (previous.containsKey(next) || !canStep(world, point, next, safe)) continue;
				previous.put(next, point);
				queue.add(next);
			}
		}
		return null;
	}

	private static WorldPoint firstLeg(Map<WorldPoint, WorldPoint> previous, WorldPoint end)
	{
		WorldPoint result = end;
		for (WorldPoint point = previous.get(end); point != null; point = previous.get(point))
		{
			WorldPoint before = previous.get(point);
			if (before != null && (end.getX() - point.getX() != point.getX() - before.getX()
				|| end.getY() - point.getY() != point.getY() - before.getY())) result = point;
			end = point;
		}
		return result;
	}

	static boolean canTravel(WorldView world, WorldPoint from, WorldPoint to, java.util.function.Predicate<WorldPoint> clear)
	{
		if (from == null || to == null || from.getPlane() != to.getPlane()) return false;
		int dx = to.getX() - from.getX(), dy = to.getY() - from.getY();
		if (dx != 0 && dy != 0 && Math.abs(dx) != Math.abs(dy)) return false;
		while (!from.equals(to))
		{
			WorldPoint next = from.dx(Integer.signum(dx)).dy(Integer.signum(dy));
			if (!canStep(world, from, next, clear)) return false;
			from = next;
		}
		return true;
	}

	static boolean canStep(WorldView world, WorldPoint from, WorldPoint to, java.util.function.Predicate<WorldPoint> clear)
	{
		if (world == null || from == null || to == null || from.distanceTo(to) != 1) return false;
		CollisionData[] maps = world.getCollisionMaps();
		int plane = from.getPlane();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null) return false;
		int[][] flags = maps[plane].getFlags();
		int x = to.getX() - world.getBaseX(), y = to.getY() - world.getBaseY();
		int fromX = from.getX() - world.getBaseX(), fromY = from.getY() - world.getBaseY();
		return flags != null && x > 0 && y > 0 && x < flags.length - 1 && y < flags[x].length - 1
			&& fromX >= 0 && fromY >= 0 && fromX < flags.length && fromY < flags[fromX].length
			&& from.toWorldArea().canTravelInDirection(world, to.getX() - from.getX(), to.getY() - from.getY(), clear);
	}

	static WorldPoint guide(WorldView world, WorldArea prey, WorldPoint takeoff, WorldPoint landing,
		java.util.function.Predicate<WorldPoint> clear)
	{
		return guide(world, prey, takeoff, landing, clear, null);
	}

	private static WorldPoint guide(WorldView world, WorldArea prey, WorldPoint takeoff, WorldPoint landing,
		java.util.function.Predicate<WorldPoint> clear, WorldPoint player)
	{
		if (world == null || prey == null || takeoff == null || landing == null
			|| prey.getPlane() != takeoff.getPlane() || prey.getWidth() != prey.getHeight()) return null;
		CollisionData[] maps = world.getCollisionMaps();
		int plane = prey.getPlane(), size = prey.getWidth();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null || size < 1) return null;
		int[][] flags = maps[plane].getFlags();
		if (flags == null || flags.length == 0) return null;
		WorldPoint start = prey.toWorldPoint();
		ArrayDeque<WorldPoint> queue = new ArrayDeque<>();
		Map<WorldPoint, WorldPoint> previous = new HashMap<>();
		Map<WorldPoint, WorldPoint> guides = new HashMap<>();
		queue.add(start);
		previous.put(start, null);
		while (!queue.isEmpty())
		{
			WorldPoint point = queue.remove();
			if (PitTrap.aligned(takeoff, point, size, takeoff, landing))
			{
				point = firstLeg(previous, point);
				while (guides.get(point) != null && prey.distanceTo(guides.get(point)) > MAX_LURE_LEAD) point = previous.get(point);
				return guides.get(point);
			}
			WorldArea area = new WorldArea(point, size, size);
			for (int[] direction : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {-1, -1}, {1, -1}})
			{
				int dx = direction[0], dy = direction[1];
				WorldPoint next = point.dx(dx).dy(dy);
				int x = next.getX() - world.getBaseX(), y = next.getY() - world.getBaseY();
				if (previous.containsKey(next) || x < 1 || y < 1
					|| x + size >= flags.length || y + size >= flags[x].length || !area.canTravelInDirection(world, dx, dy, clear)) continue;
				WorldPoint lure = null;
				for (int offset = 0; offset < size; offset++)
				{
					WorldPoint candidate = new WorldPoint(point.getX() + (dx > 0 ? size + 1 : dx < 0 ? -2 : offset),
						point.getY() + (dy > 0 ? size + 1 : dy < 0 ? -2 : offset), plane);
					int cx = candidate.getX() - world.getBaseX(), cy = candidate.getY() - world.getBaseY();
					if (cx >= 0 && cy >= 0 && cx < flags.length && cy < flags[cx].length
						&& (flags[cx][cy] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0 && clear.test(candidate)
						&& (lure == null || player != null
							&& Math.abs(candidate.getX() - player.getX()) + Math.abs(candidate.getY() - player.getY())
								< Math.abs(lure.getX() - player.getX()) + Math.abs(lure.getY() - player.getY()))) lure = candidate;
				}
				if (lure == null) continue;
				previous.put(next, point);
				guides.put(next, lure);
				queue.add(next);
			}
		}
		return null;
	}

	private boolean crossed(WorldPoint position)
	{
		if (position.getPlane() != landing.getPlane()) return false;
		if (takeoff.getY() == landing.getY() && Math.abs(position.getY() - landing.getY()) <= 1)
			return landing.getX() > takeoff.getX() ? position.getX() >= landing.getX() : position.getX() <= landing.getX();
		if (takeoff.getX() == landing.getX() && Math.abs(position.getX() - landing.getX()) <= 1)
			return landing.getY() > takeoff.getY() ? position.getY() >= landing.getY() : position.getY() <= landing.getY();
		return false;
	}
}
