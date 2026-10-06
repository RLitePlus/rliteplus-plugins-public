package net.runelite.client.plugins.microbot.tithefarm;

import java.util.Arrays;
import net.runelite.client.plugins.microbot.tithefarm.TitheFarmCycle.Action;
import net.runelite.client.plugins.microbot.tithefarm.TitheFarmCycle.Crop;
import net.runelite.client.plugins.microbot.tithefarm.TitheFarmCycle.Snapshot;

final class TitheFarmCombo
{
	static final int[][] PATCHES = {
		{28, 56}, {33, 56}, {33, 53}, {28, 50}, {33, 50}, {33, 47},
		{33, 41}, {28, 38}, {33, 38}, {33, 35}, {33, 32}, {28, 32},
		{28, 26}, {23, 32}, {23, 35}, {28, 35}, {23, 38}, {23, 41},
		{28, 41}, {23, 47}, {23, 50}, {23, 53}, {28, 53}, {23, 56}, {28, 47}
	};
	static final int[] OPENING = {18, 17, 16, 14, 15, 13, 12, 10, 11, 9, 8, 7, 6,
		5, 4, 3, 2, 1, 0, 23, 21, 22, 20, 19, 24};
	static final int[] COMBO = java.util.stream.IntStream.range(0, 24).toArray();
	static final int[] SECOND = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
		15, 16, 17, 18, 19, 20, 21, 23};
	static final int[] THIRD = {0, 1, 2, 22, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
		14, 15, 16, 17, 18, 19, 20, 21, 22, 23};
	enum Phase { PREPARE, PREHARVEST, PLANT, COMBO, SECOND, THIRD, EXTRA, CLOSE, RECOVER, DEPOSIT, COMPLETE, ERROR }
	final Crop crop;
	Phase phase = Phase.PREPARE;
	int index;
	int cycles;
	int watered;
	int harvested;
	int deposited;
	int refills;
	int deaths;
	int selectedCan = -1;
	int moveX;
	int moveY;
	String error;
	String warning;
	private final int[] levels = new int[25];
	private final boolean[] dead = new boolean[25];
	private Action pending = Action.NONE;
	private Snapshot baseline;
	private int lastTick = -1;
	private int cursor;
	private int[] order = OPENING;
	private boolean opening = true;
	private boolean finish;
	private boolean selectionAhead;
	private boolean approached;
	private int phaseAt = -1;
	private int lastProgress;
	private int lastWater = -1;
	private int lastEnergy = -1;
	private int refillProgress;
	private int refillWater;
	private int playerX;
	private int playerY;
	private int progressX;
	private int progressY;
	private int progressObject;
	private int pendingProgress;

	TitheFarmCombo(Crop crop)
	{
		this.crop = crop;
	}

	Action next(Snapshot[] all, int capacity, int energy, boolean running, int refillCan,
		int chargedCan, boolean finishRequested, int limit, int playerX, int playerY, boolean arrived)
	{
		Snapshot now = all[index];
		finish |= finishRequested;
		if (now.tick <= lastTick || phase == Phase.ERROR || phase == Phase.COMPLETE)
		{
			return Action.NONE;
		}
		lastTick = now.tick;
		this.playerX = playerX; this.playerY = playerY;
		for (int i = 0; i < all.length; i++)
		{
			int id = all[i].objectId;
			if (crop.contains(id) && !crop.dry(id) && !crop.wet(id) && id != crop.firstDry + 9)
			{
				if (!dead[i])
				{
					dead[i] = true;
					deaths++;
					recover("A crop died. Finishing the remaining plants; use a lower mode before restarting.");
				}
			}
		}
		if (phase == Phase.RECOVER && (pending == Action.WATER || pending == Action.MANUAL_WATER)
			&& dead[index])
		{
			pending = Action.NONE;
		}
		if (pending != Action.NONE)
		{
			if (playerX != progressX || playerY != progressY || now.objectId != progressObject)
			{
				pendingProgress = now.tick; progressX = playerX; progressY = playerY; progressObject = now.objectId;
			}
			boolean effect;
			switch (pending)
			{
				case SELECT_SEED: effect = now.selectedItem == crop.seed; break;
				case SELECT_CAN: effect = now.selectedItem == selectedCan; break;
				case DESELECT: effect = now.selectedItem < 0; break;
				case PLANT: effect = now.seeds == baseline.seeds - 1 && now.objectId == crop.firstDry; break;
				case WATER:
				case MANUAL_WATER:
					effect = now.water == baseline.water - 1 && now.objectId == baseline.objectId + 1;
					if (effect) { levels[index]++; watered++; }
					break;
				case HARVEST:
					effect = now.fruit == baseline.fruit + 1 && now.objectId == TitheFarmCycle.EMPTY_PATCH;
					if (effect) { harvested++; levels[index] = 0; }
					break;
				case CLEAR: effect = now.objectId == TitheFarmCycle.EMPTY_PATCH; break;
				case MOVE: effect = arrived && playerX == moveX && playerY == moveY; break;
				case ENABLE_RUN: effect = running; break;
				case REFILL:
					if (now.water > refillWater) { refillWater = now.water; refillProgress = now.tick; }
					effect = now.water > baseline.water && (now.water == capacity || now.tick - refillProgress >= 8);
					if (effect) { refills++; }
					break;
				case DEPOSIT:
					effect = now.fruit < baseline.fruit && now.xp > baseline.xp
						&& TitheFarmCycle.depositAdvanced(baseline.sack, now.sack, baseline.fruit - now.fruit);
					if (effect) { deposited += baseline.fruit - now.fruit; }
					break;
				default: effect = false;
			}
			if (!effect)
			{
				if (!selectionAhead && now.selectedItem < 0
					&& (pending == Action.PLANT || pending == Action.HARVEST && phase == Phase.COMBO))
				{
					selectionAhead = true;
					selectedCan = chargedCan;
					return pending == Action.PLANT ? Action.SELECT_CAN : Action.SELECT_SEED;
				}
				if (now.tick - pendingProgress >= (pending == Action.REFILL || pending == Action.MOVE ? 60 : 20)
					|| now.tick - baseline.tick >= 200)
				{
					if (phase == Phase.RECOVER) { fail("Recovery could not confirm " + pending + ". Tend the remaining crop manually."); }
					else { recover("Could not confirm " + pending + ". Finishing the remaining plants before stopping."); }
				}
				return Action.NONE;
			}
			Action completed = pending;
			pending = Action.NONE;
			if (completed == Action.HARVEST && phase == Phase.EXTRA)
			{
				opening = false;
				enter(Phase.PREPARE, COMBO, now.tick);
			}
		}
		for (int steps = 0; steps < 30; steps++)
		{
			now = all[index];
			if (phase == Phase.RECOVER)
			{
				if (now.selectedItem >= 0) { return dispatch(Action.DESELECT, now); }
				for (int pass = 0; pass < 3; pass++)
				{
					for (int i = 0; i < all.length; i++)
					{
						int id = all[i].objectId;
						if (pass == 0 && crop.dry(id) || pass == 1 && id == crop.firstDry + 9
							|| pass == 2 && dead[i] && id != TitheFarmCycle.EMPTY_PATCH)
						{
							index = i;
							return dispatch(pass == 0 ? Action.WATER : pass == 1 ? Action.HARVEST : Action.CLEAR, all[i]);
						}
					}
				}
				if (Arrays.stream(all).allMatch(s -> s.objectId == TitheFarmCycle.EMPTY_PATCH))
				{
					enter(Phase.DEPOSIT, COMBO, now.tick);
					continue;
				}
				if (now.tick - phaseAt > 500) { fail("Recovery timed out. Tend any remaining crop manually."); }
				return Action.NONE;
			}
			if (phase == Phase.DEPOSIT)
			{
				if (now.fruit > 0) { return dispatch(Action.DEPOSIT, now); }
				phase = Phase.COMPLETE;
				error = warning;
				return Action.NONE;
			}
			if (phase == Phase.PREPARE)
			{
				if (!opening && (finish || limit > 0 && cycles >= limit || now.seeds < 24))
				{
					enter(Phase.CLOSE, COMBO, now.tick);
					continue;
				}
				if (opening && now.seeds < 25 || capacity < 75)
				{
					fail("Bring at least 25 seeds and ten ordinary watering cans for the Efficient opening.");
					return Action.NONE;
				}
				if (phaseAt < 0) { phaseAt = now.tick; lastProgress = now.tick; }
				if (lastWater != now.water || lastEnergy != energy)
				{
					lastProgress = now.tick; lastWater = now.water; lastEnergy = energy;
				}
				if (now.tick - phaseAt > 2000 || now.tick - lastProgress > 100)
				{
					recover("Water or run energy stopped recovering. Finishing the crop before stopping.");
					continue;
				}
				if (now.water < capacity)
				{
					if (refillCan < 0) { recover("No refillable can is available. Finishing the crop before stopping."); continue; }
					selectedCan = refillCan;
					return dispatch(now.selectedItem == refillCan ? Action.REFILL : Action.SELECT_CAN, now);
				}
				if (energy < 100) { return Action.NONE; }
				if (!running) { return dispatch(Action.ENABLE_RUN, now); }
				enter(opening ? Phase.PLANT : Phase.PREHARVEST, opening ? OPENING : new int[]{1}, now.tick);
				continue;
			}
			if (!approached)
			{
				approached = true;
				int[] approach = approach();
				if (approach != null && (playerX != approach[0] || playerY != approach[1]))
				{
					moveX = approach[0]; moveY = approach[1];
					return dispatch(Action.MOVE, now);
				}
			}
			int wanted = phase == Phase.PLANT || phase == Phase.COMBO ? 1
				: phase == Phase.SECOND || phase == Phase.THIRD && !opening && cursor == 3 ? 2 : 3;
			boolean harvestOnly = phase == Phase.CLOSE || phase == Phase.EXTRA || phase == Phase.PREHARVEST;
			if (harvestOnly && now.objectId == TitheFarmCycle.EMPTY_PATCH
				|| !harvestOnly && levels[index] == wanted && (crop.wet(now.objectId) || now.objectId == crop.firstDry + 9))
			{
				if (++cursor < order.length) { index = order[cursor]; approached = false; continue; }
				if (phase == Phase.PREHARVEST) { enter(Phase.COMBO, COMBO, now.tick); }
				else if (phase == Phase.PLANT || phase == Phase.COMBO) { enter(Phase.SECOND, opening ? OPENING : SECOND, now.tick); }
				else if (phase == Phase.SECOND) { enter(Phase.THIRD, opening ? OPENING : THIRD, now.tick); }
				else if (phase == Phase.THIRD)
				{
					if (opening) { enter(Phase.EXTRA, new int[]{24}, now.tick); }
					else { cycles++; enter(Phase.PREPARE, COMBO, now.tick); }
				}
				else { enter(Phase.DEPOSIT, COMBO, now.tick); }
				continue;
			}
			if (now.objectId == crop.firstDry + 9 && (harvestOnly || phase == Phase.COMBO))
			{
				return dispatch(Action.HARVEST, now);
			}
			if ((phase == Phase.PLANT || phase == Phase.COMBO) && now.objectId == TitheFarmCycle.EMPTY_PATCH)
			{
				return dispatch(now.selectedItem == crop.seed ? Action.PLANT : Action.SELECT_SEED, now);
			}
			if (crop.dry(now.objectId) && !harvestOnly)
			{
				if (now.water < 1) { fail("No water remains. Refill a can and tend the remaining crop manually."); return Action.NONE; }
				if (now.objectId == crop.firstDry)
				{
					selectedCan = chargedCan;
					return dispatch(now.selectedItem == selectedCan ? Action.MANUAL_WATER : Action.SELECT_CAN, now);
				}
				return dispatch(Action.WATER, now);
			}
			if (now.tick - phaseAt > 600) { recover("The crop did not reach its expected stage. Finishing remaining plants."); continue; }
			return Action.NONE;
		}
		return Action.NONE;
	}

	private int[] approach()
	{
		if (phase == Phase.PREHARVEST) { return new int[]{32, 56}; }
		if (phase == Phase.PLANT) { return cursor == 0 ? new int[]{27, 43} : null; }
		if (phase != Phase.COMBO && phase != Phase.SECOND && phase != Phase.THIRD) { return null; }
		if (opening) { return null; }
		if (index == 22) { return phase == Phase.THIRD && cursor == 3 ? new int[]{31, 54} : new int[]{27, 55}; }
		if (phase == Phase.COMBO && index == 0) { return new int[]{31, 56}; }
		switch (index)
		{
			case 2: return new int[]{32, 54};
			case 3: return new int[]{31, 52};
			case 7: return new int[]{31, 40};
			case 10: return new int[]{32, 34};
			case 14: return new int[]{26, 35};
			case 17: return new int[]{26, 41};
			case 21: return new int[]{26, 53};
			default: return null;
		}
	}

	private Action dispatch(Action action, Snapshot now)
	{
		pending = action;
		baseline = now;
		pendingProgress = now.tick; progressX = playerX; progressY = playerY; progressObject = now.objectId;
		selectionAhead = false;
		if (action == Action.REFILL) { refillProgress = now.tick; refillWater = now.water; }
		return action;
	}

	private void enter(Phase next, int[] nextOrder, int tick)
	{
		phase = next; order = nextOrder; cursor = 0; index = order[0]; approached = false;
		phaseAt = tick; lastProgress = tick;
	}

	void movementFailed() { recover("The approach was blocked. Finishing remaining plants before stopping."); }

	private void recover(String reason)
	{
		if (warning == null) { warning = reason; }
		if (phase == Phase.RECOVER) { return; }
		pending = Action.NONE;
		enter(Phase.RECOVER, COMBO, lastTick);
	}

	private void fail(String reason)
	{
		error = reason;
		phase = Phase.ERROR;
	}
}
