package net.runelite.client.plugins.microbot.tithefarm;

import net.runelite.client.plugins.microbot.tithefarm.TitheFarmCycle.Action;
import net.runelite.client.plugins.microbot.tithefarm.TitheFarmCycle.Crop;
import net.runelite.client.plugins.microbot.tithefarm.TitheFarmCycle.Snapshot;

final class TitheFarmRotation
{
	// Template-region patch origins corresponding to the Wiki's basic-route markers.
	static final int[][] PATCHES = {
		{33, 56}, {38, 56}, {33, 53}, {38, 53}, {38, 50}, {33, 50}, {33, 47}, {38, 47},
		{38, 41}, {38, 38}, {38, 35}, {38, 32}, {33, 32}, {33, 35}, {33, 38}, {33, 41},
		{28, 47}, {28, 50}, {28, 53}, {28, 56}
	};

	// The 23x markers are patch centres; these coordinates are southwest origins.
	static final int[][] MODERATE_PATCHES = {
		{23, 56}, {28, 56}, {23, 53}, {28, 53}, {23, 50}, {28, 50}, {28, 47},
		{28, 41}, {28, 38}, {28, 35}, {28, 32}, {28, 26}, {18, 32}, {18, 35},
		{23, 38}, {18, 38}, {23, 41}, {18, 41}, {23, 47}, {18, 47}, {18, 50},
		{18, 53}, {18, 56}
	};

	enum Phase { PREPARE, PLANT, WATER_SECOND, WATER_THIRD, HARVEST, DEPOSIT, COMPLETE, ERROR }
	final Crop crop;
	final TitheFarmConfig.Mode mode;
	final TitheFarmCycle[] plants;
	Phase phase = Phase.PREPARE;
	int index;
	int cycles;
	int watered;
	int harvested;
	int deposited;
	int refills;
	int selectedCan = -1;
	String error;
	private Action pending = Action.NONE;
	private Snapshot baseline;
	private int lastTick = -1;
	private int preparationTick = -1;
	private int lastResourceTick;
	private int lastEnergy = -1;
	private int lastWater = -1;
	private boolean finish;
	private int refillWater;
	private int refillProgress;
	private int selectedAhead = -1;

	TitheFarmRotation(Crop crop, TitheFarmConfig.Mode mode)
	{
		this.crop = crop;
		this.mode = mode;
		plants = new TitheFarmCycle[patches(mode).length];
	}

	static int[][] patches(TitheFarmConfig.Mode mode)
	{
		return mode == TitheFarmConfig.Mode.EFFICIENT ? TitheFarmCombo.PATCHES
			: mode == TitheFarmConfig.Mode.MODERATE ? MODERATE_PATCHES : PATCHES;
	}

	static int cans(TitheFarmConfig.Mode mode)
	{
		return (patches(mode).length * 3 + 7) / 8;
	}

	Action next(Snapshot[] snapshots, int capacity, int energy, boolean running, int can,
		boolean finishRequested, int limit)
	{
		Snapshot now = snapshots[0];
		finish |= finishRequested;
		if (phase == Phase.ERROR || phase == Phase.COMPLETE || now.tick <= lastTick)
		{
			return Action.NONE;
		}
		lastTick = now.tick;
		if (pending == Action.REFILL)
		{
			if (now.water > refillWater)
			{
				refillWater = now.water;
				refillProgress = now.tick;
			}
			int target = selectedCan == TitheFarmCycle.GRICOLLERS_CAN ? plants.length * 3 : capacity;
			if (now.water < target && now.tick - refillProgress < 8)
			{
				return Action.NONE;
			}
		}
		if (pending != Action.NONE)
		{
			boolean effect = pending == Action.SELECT_CAN ? now.selectedItem == selectedCan
				: pending == Action.REFILL ? now.water > baseline.water
				: pending == Action.ENABLE_RUN ? running
				: now.fruit < baseline.fruit && now.xp > baseline.xp
					&& TitheFarmCycle.depositAdvanced(baseline.sack, now.sack, baseline.fruit - now.fruit);
			if (!effect)
			{
				if (now.tick - baseline.tick >= 40)
				{
					fail("Could not confirm " + pending.name().toLowerCase().replace('_', ' ')
						+ ". Check the supplies and scene before restarting Tithe Farm.");
				}
				return Action.NONE;
			}
			if (pending == Action.SELECT_CAN)
			{
				return dispatch(Action.REFILL, now);
			}
			if (pending == Action.REFILL)
			{
				refills++;
			}
			if (pending == Action.DEPOSIT)
			{
				deposited += baseline.fruit - now.fruit;
			}
			pending = Action.NONE;
		}
		if (phase == Phase.DEPOSIT)
		{
			if (now.fruit > 0)
			{
				return dispatch(Action.DEPOSIT, now);
			}
			cycles++;
			phase = Phase.PREPARE;
			preparationTick = -1;
		}
		if (phase == Phase.PREPARE)
		{
			if (finish || limit > 0 && cycles >= limit)
			{
				phase = Phase.COMPLETE;
				return Action.NONE;
			}
			if (now.seeds < plants.length || capacity < plants.length * 3)
			{
				fail(now.seeds < plants.length ? "Missing " + (plants.length - now.seeds) + " " + crop.name().toLowerCase()
					+ " seeds. Bring at least " + plants.length + " seeds for another " + mode + " cycle."
					: "Bring at least " + cans(mode) + " ordinary watering cans or Gricoller's can before starting " + mode + " mode.");
				return Action.NONE;
			}
			if (preparationTick < 0)
			{
				preparationTick = now.tick;
				lastResourceTick = now.tick;
			}
			if (energy != lastEnergy || now.water != lastWater)
			{
				lastResourceTick = now.tick;
				lastEnergy = energy;
				lastWater = now.water;
			}
			if (now.tick - preparationTick > 2000 || now.tick - lastResourceTick > 100)
			{
				fail("Water or run energy is not recovering. Check the supplies before restarting.");
				return Action.NONE;
			}
			int target = can == TitheFarmCycle.GRICOLLERS_CAN ? plants.length * 3 : capacity;
			if (now.water < target)
			{
				if (can < 0)
				{
					fail("No refillable watering can is available. Check the inventory.");
					return Action.NONE;
				}
				selectedCan = can;
				return dispatch(now.selectedItem == can ? Action.REFILL : Action.SELECT_CAN, now);
			}
			if (energy < 100)
			{
				return Action.NONE;
			}
			if (!running)
			{
				return dispatch(Action.ENABLE_RUN, now);
			}
			for (int i = 0; i < plants.length; i++)
			{
				if (snapshots[i].objectId != TitheFarmCycle.EMPTY_PATCH)
				{
					fail("The " + mode + " route is occupied. Clear or finish the existing crop before starting.");
					return Action.NONE;
				}
				plants[i] = new TitheFarmCycle(crop, false);
			}
			index = 0;
			selectedAhead = -1;
			phase = Phase.PLANT;
		}
		for (int step = 0; step <= plants.length; step++)
		{
			TitheFarmCycle plant = plants[index];
			int oldWater = plant.watered;
			int oldHarvest = plant.harvested;
			Action action = plant.next(snapshots[index]);
			watered += plant.watered - oldWater;
			harvested += plant.harvested - oldHarvest;
			if (plant.error != null)
			{
				fail("Patch " + (index + 1) + ": " + plant.error);
				return Action.NONE;
			}
			boolean done = phase == Phase.PLANT ? plant.watered >= 1
				: phase == Phase.WATER_SECOND ? plant.watered >= 2
				: phase == Phase.WATER_THIRD ? plant.watered >= 3 : plant.harvested == 1;
			if (!done)
			{
				// Item selection can overlap the pending watering; planting still waits for its effect.
				if (action == Action.NONE && phase == Phase.PLANT && index + 1 < plants.length
					&& plant.awaitingFirstWater() && selectedAhead != index && snapshots[index].selectedItem < 0)
				{
					selectedAhead = index;
					return Action.SELECT_SEED;
				}
				return action;
			}
			if (++index == plants.length)
			{
				index = 0;
				phase = phase == Phase.PLANT ? Phase.WATER_SECOND
					: phase == Phase.WATER_SECOND ? Phase.WATER_THIRD
					: phase == Phase.WATER_THIRD ? Phase.HARVEST : Phase.DEPOSIT;
				if (phase == Phase.DEPOSIT)
				{
					return dispatch(Action.DEPOSIT, now);
				}
			}
		}
		return Action.NONE;
	}

	private Action dispatch(Action action, Snapshot now)
	{
		pending = action;
		baseline = now;
		if (action == Action.REFILL)
		{
			refillWater = now.water;
			refillProgress = now.tick;
		}
		return action;
	}

	void fail(String reason)
	{
		error = reason;
		phase = Phase.ERROR;
	}
}
