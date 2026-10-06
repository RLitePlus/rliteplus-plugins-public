package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.api.coords.WorldPoint;

final class PitTrap
{
	enum Action { SET, READY, CHECK, DISMANTLE, WAIT, CAUGHT, CLEARED, BLOCKED }

	final int baseId;
	final WorldPoint location;
	private boolean owned;
	private Action pending;
	private int logsBefore;
	private int productsBefore;

	PitTrap(int baseId, WorldPoint location)
	{
		this.baseId = baseId;
		this.location = location;
	}

	boolean crossingPads(WorldPoint from, WorldPoint to)
	{
		if (from == null || to == null || from.getPlane() != location.getPlane() || to.getPlane() != location.getPlane()) return false;
		if (from.getY() == location.getY() && to.getY() == location.getY())
			return Math.min(from.getX(), to.getX()) == location.getX() - 1 && Math.max(from.getX(), to.getX()) == location.getX() + 2;
		if (from.getX() == location.getX() && to.getX() == location.getX())
			return Math.min(from.getY(), to.getY()) == location.getY() - 1 && Math.max(from.getY(), to.getY()) == location.getY() + 2;
		return false;
	}

	static boolean aligned(WorldPoint player, WorldPoint prey, int size, WorldPoint takeoff, WorldPoint landing)
	{
		if (player == null || prey == null || takeoff == null || landing == null || size < 1
			|| !player.equals(takeoff) || prey.getPlane() != takeoff.getPlane() || landing.getPlane() != takeoff.getPlane()) return false;
		int x = takeoff.getX(), y = takeoff.getY();
		int left = prey.getX(), right = left + size - 1, south = prey.getY(), north = south + size - 1;
		if (landing.getX() == x && landing.getY() > y) return north == y - 1 && left <= x && right >= x;
		if (landing.getX() == x && landing.getY() < y) return south == y + 1 && left <= x && right >= x;
		if (landing.getY() == y && landing.getX() > x) return right == x - 1 && south <= y && north >= y;
		if (landing.getY() == y && landing.getX() < x) return left == x + 1 && south <= y && north >= y;
		return false;
	}

	synchronized boolean prepared()
	{
		return owned && pending == null;
	}

	synchronized boolean ownsTrap()
	{
		return owned || pending != null;
	}

	synchronized Action next(int tick, int state, int logs, int products, boolean cleanup)
	{
		if (state < 0 || state > 4 || logs < 0 || products < 0) return Action.BLOCKED;
		if (pending == Action.SET)
		{
			if (state >= 1 && state <= 4 && logs == logsBefore - 1)
			{
				pending = null;
				owned = true;
			}
			else return Action.WAIT;
		}
		if (owned && pending == Action.DISMANTLE && (state == 3 || state == 4)
			&& logs == logsBefore && products == productsBefore) pending = null;
		if (pending == Action.CHECK || pending == Action.DISMANTLE)
		{
			if (state == 0 && (pending == Action.DISMANTLE || products > productsBefore))
			{
				Action result = pending == Action.CHECK ? Action.CAUGHT : Action.CLEARED;
				pending = null;
				owned = false;
				return result;
			}
			return Action.WAIT;
		}
		if (!owned)
		{
			if (cleanup) return Action.CLEARED;
			return state == 0 && logs > 0 ? Action.SET : Action.BLOCKED;
		}
		if (state == 3 || state == 4) return Action.CHECK;
		if (state == 1) return cleanup ? Action.DISMANTLE : Action.READY;
		if (state == 2) return Action.WAIT;
		if (state == 0)
		{
			owned = false;
			return Action.CLEARED;
		}
		return Action.BLOCKED;
	}

	synchronized void submitted(Action action, int tick, int logs, int products)
	{
		if (action != Action.SET && action != Action.CHECK && action != Action.DISMANTLE) return;
		pending = action;
		logsBefore = logs;
		productsBefore = products;
	}
}
