/*
 * Copyright (c) 2020, dekvall <https://github.com/dekvall>
 * Copyright (c) 2020, Jordan <nightfirecat@protonmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.api.coords.WorldPoint;
import java.util.Arrays;

final class HerbiboarTrail
{
	enum Action { START, INSPECT, ATTACK, HARVEST, BLOCKED }
	enum Result { WAIT, CHANGED, HARVESTED, BLOCKED }

	static final class Receipt
	{
		private final Action action;
		private final int[] before;
		private final int tick;
		private final int products;
		private boolean harvested;
		private boolean confused;

		Receipt(Action action, int[] before, int tick, int products)
		{
			this.action = action;
			this.before = before == null ? null : before.clone();
			this.tick = tick;
			this.products = products;
		}

		void message(String text, int now)
		{
			if (now < tick) return;
			if (action == Action.HARVEST
				&& "You harvest herbs from the herbiboar, whereupon it escapes.".equals(text)) harvested = true;
			if (action == Action.INSPECT
				&& "The creature has successfully confused you with its tracks, leading you round in circles. You'll need to start again.".equals(text)) confused = true;
		}

		// Snapshot is the 24 trails followed by finish, visible and used.
		Result observe(int[] current, int now, int loot)
		{
			if (before == null || before.length != 27 || current == null || current.length != 27
				|| now < tick || products < 0 || loot < 0) return Result.BLOCKED;
			for (int i = 0; i < current.length; i++)
			{
				int maximum = i < 24 ? 4 : i < 26 ? 9 : 1;
				if (before[i] < 0 || before[i] > maximum || current[i] < 0 || current[i] > maximum) return Result.BLOCKED;
			}
			if (now == tick) return Result.WAIT;
			if (action == Action.HARVEST)
			{
				if (before[25] > 0 && current[25] == 0 && harvested && loot > products) return Result.HARVESTED;
			}
			else if (action == Action.ATTACK)
			{
				if (before[24] > 0 && current[25] == before[24]) return Result.CHANGED;
			}
			else if ((action == Action.START || action == Action.INSPECT) && !Arrays.equals(before, current)
				&& (Arrays.stream(current).anyMatch(value -> value != 0) || confused)) return Result.CHANGED;
			return Result.WAIT;
		}
	}

	static final class Step
	{
		final Action action;
		final WorldPoint target;

		Step(Action action, WorldPoint target)
		{
			this.action = action;
			this.target = target;
		}

		boolean matchesObject(int id, WorldPoint location)
		{
			if (target == null || !target.equals(location)) return false;
			return action == Action.START ? id >= 30519 && id <= 30523
				: action == Action.INSPECT ? id >= 30533 && id <= 30551
				: action == Action.ATTACK && id == 30532;
		}

		boolean matchesNpc(int baseId, int transformedId, WorldPoint location)
		{
			if (action != Action.HARVEST || target == null || location == null
				|| transformedId != 7785 || target.distanceTo(location) > 4) return false;
			for (int i = 0; i < ENDS.length; i++)
				if (target.equals(point(ENDS[i]))) return baseId == 7831 + i;
			return false;
		}
	}

	// Varbit, next search point for value 1, next search point for value 2.
	private static final int[][] TRAILS = {
		{5737, 0, 0, 3697, 3875},
		{5738, 0, 0, 3672, 3890},
		{5739, 0, 0, 3681, 3859},
		{5740, 3699, 3875, 3710, 3877},
		{5741, 3699, 3875, 3728, 3893},
		{5742, 3670, 3889, 3728, 3893},
		{5743, 3670, 3889, 3667, 3862},
		{5744, 3681, 3860, 3680, 3836},
		{5745, 3681, 3860, 3698, 3847},
		{5746, 3708, 3876, 3713, 3850},
		{5747, 3713, 3850, 0, 0},
		{5748, 3706, 3811, 0, 0},
		{5749, 3706, 3811, 0, 0},
		{5750, 3713, 3840, 0, 0},
		{5768, 3708, 3876, 3694, 3847},
		{5769, 3728, 3893, 0, 0},
		{5770, 3728, 3893, 3710, 3877},
		{5771, 3668, 3865, 3681, 3860},
		{5772, 3668, 3865, 3680, 3836},
		{5773, 3680, 3838, 3706, 3811},
		{5774, 3680, 3838, 0, 0},
		{5775, 3694, 3847, 0, 0},
		{5776, 3715, 3851, 3713, 3840},
		{5777, 3715, 3851, 0, 0}
	};
	private static final int[][] ENDS = {
		{3693, 3798}, {3702, 3808}, {3703, 3826},
		{3710, 3881}, {3700, 3877}, {3715, 3840},
		{3751, 3849}, {3685, 3869}, {3681, 3863}
	};
	private static final int[][] STARTS = {
		{3686, 3870}, {3705, 3830}, {3704, 3810}, {3695, 3800}, {3751, 3850}
	};

	static int trailCount()
	{
		return TRAILS.length;
	}

	static int varbit(int index)
	{
		return TRAILS[index][0];
	}

	// finish: varbit 5766; visible: 5943; used: 5767.
	// A harvest target is the tunnel anchor; reacquire its visible NPC nearby.
	static Step next(int[] snapshot, int finish, int visible, int used, WorldPoint player)
	{
		if (snapshot == null || snapshot.length != TRAILS.length || finish < 0 || finish > ENDS.length
			|| visible < 0 || visible > ENDS.length || used < 0 || used > 1) return new Step(Action.BLOCKED, null);
		for (int value : snapshot) if (value < 0 || value > 4) return new Step(Action.BLOCKED, null);
		if (visible > 0) return new Step(Action.HARVEST, point(ENDS[visible - 1]));
		if (finish > 0) return new Step(Action.ATTACK, point(ENDS[finish - 1]));
		WorldPoint target = null;
		boolean clear = true;
		for (int i = 0; i < snapshot.length; i++)
		{
			int value = snapshot[i];
			if (value != 0) clear = false;
			if (value != 1 && value != 2) continue;
			int offset = value == 1 ? 1 : 3;
			if (target != null || TRAILS[i][offset] == 0) return new Step(Action.BLOCKED, null);
			target = new WorldPoint(TRAILS[i][offset], TRAILS[i][offset + 1], 0);
		}
		if (target != null) return new Step(Action.INSPECT, target);
		if (!clear || used != 0 || player == null || player.getPlane() != 0) return new Step(Action.BLOCKED, null);
		WorldPoint start = point(STARTS[0]);
		for (int[] coordinates : STARTS)
		{
			WorldPoint candidate = point(coordinates);
			if (candidate.distanceTo(player) < start.distanceTo(player)) start = candidate;
		}
		return new Step(Action.START, start);
	}

	private static WorldPoint point(int[] coordinates)
	{
		return new WorldPoint(coordinates[0], coordinates[1], 0);
	}

	private HerbiboarTrail() { }
}
