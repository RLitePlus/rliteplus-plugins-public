package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

final class TrackingTrail
{
	enum Action { START, INSPECT, ATTACK, BLOCKED }

	static final class Step
	{
		final Action action;
		final int target;

		Step(Action action, int target)
		{
			this.action = action;
			this.target = target;
		}
	}

	static WorldPoint approach(WorldPoint target, WorldPoint player, int[][] flags, int baseX, int baseY)
	{
		if (target == null || player == null || target.getPlane() != player.getPlane() || flags == null) return null;
		WorldPoint best = null;
		for (int[] offset : new int[][]{{-1, 0}, {0, -1}, {1, 0}, {0, 1}})
		{
			int x = target.getX() + offset[0] - baseX;
			int y = target.getY() + offset[1] - baseY;
			if (x < 0 || x >= flags.length || flags[x] == null || y < 0 || y >= flags[x].length
				|| (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) continue;
			WorldPoint candidate = new WorldPoint(x + baseX, y + baseY, target.getPlane());
			if (best == null || candidate.distanceTo(player) < best.distanceTo(player)) best = candidate;
		}
		return best;
	}

	private TrackingTrail() { }

	// Snapshot is varbits 2974 through 2994, including the counter at 2984.
	// Inspect targets are varbit IDs; Attack targets are endpoint selector values.
	static Step next(int[] snapshot)
	{
		if (snapshot == null || snapshot.length != 21) return new Step(Action.BLOCKED, 0);
		int clue = 0;
		boolean clear = true;
		boolean ambiguous = false;
		for (int i = 0; i < snapshot.length; i++)
		{
			int value = snapshot[i];
			int maximum = i == 20 ? 8 : i == 10 ? 3 : 4;
			if (value < 0 || value > maximum) return new Step(Action.BLOCKED, 0);
			if (value != 0) clear = false;
			if (i != 10 && i != 20 && (value == 1 || value == 2))
			{
				if (clue != 0) ambiguous = true;
				clue = 2974 + i;
			}
		}
		if (snapshot[20] != 0) return new Step(Action.ATTACK, snapshot[20]);
		if (ambiguous) return new Step(Action.BLOCKED, 0);
		if (clue != 0) return new Step(Action.INSPECT, clue);
		return new Step(clear ? Action.START : Action.BLOCKED, 0);
	}
}
