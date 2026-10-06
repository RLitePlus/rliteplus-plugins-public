package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.CollisionDataFlag;

final class GoatPit
{
	enum Action { TAKE_SPIKES, LINE, LURE, HARVEST, BANK, WAIT, CAUGHT, FINISHED, BLOCKED }

	static final WorldPoint LOCATION = new WorldPoint(2571, 2194, 0);
	private Action pending;
	private int started;
	private int countBefore;
	private int spikesBefore;
	private int experienceBefore;
	private int productsBefore;
	private int harvestExperience;
	private int caught;

	static int capacity(int level)
	{
		if (level < 60 || level > 99) return 0;
		return level < 69 ? 16 : level < 77 ? 18 : level < 85 ? 20 : level < 93 ? 22 : 24;
	}

	static int harvestExperience(int level)
	{
		return capacity(level) == 0 ? 0 : 100 + 3 * (Math.min(80, level) - 60) + Math.max(0, level - 80);
	}

	static boolean emptyDialogue(String text)
	{
		return "There are no more goat remains in the pit.".equals(text);
	}

	static WorldPoint behind(WorldPoint goat)
	{
		if (goat == null || goat.getPlane() != LOCATION.getPlane()) return null;
		int x = goat.getX() - LOCATION.getX(), y = goat.getY() - LOCATION.getY();
		if (x >= 0 && x < 3)
		{
			if (y >= 3 && y <= 7) return goat.dy(1);
			if (y >= -5 && y < 0) return goat.dy(-1);
		}
		if (y >= 0 && y < 3)
		{
			if (x >= 3 && x <= 7) return goat.dx(1);
			if (x >= -5 && x < 0) return goat.dx(-1);
		}
		return null;
	}

	static WorldPoint behind(WorldPoint goat, int[][] flags, int baseX, int baseY)
	{
		WorldPoint approach = behind(goat);
		if (approach == null || flags == null) return null;
		int x = approach.getX() - baseX, y = approach.getY() - baseY;
		return x < 0 || x >= flags.length || flags[x] == null || y < 0 || y >= flags[x].length
			|| (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0 ? null : approach;
	}

	synchronized boolean pending()
	{
		return pending != null;
	}

	synchronized int caught()
	{
		return caught;
	}

	synchronized void submitted(Action action, int tick, int count, int spikes, int experience, int products, int level)
	{
		if (pending() || (action != Action.LINE && action != Action.LURE && action != Action.HARVEST)) return;
		pending = action;
		started = tick;
		countBefore = count;
		spikesBefore = spikes;
		experienceBefore = experience;
		productsBefore = products;
		harvestExperience = harvestExperience(level);
	}

	synchronized Action next(int tick, int lined, int count, int state, int spikes, int experience,
		int products, int level, int freeSlots, boolean finish)
	{
		caught = 0;
		int capacity = capacity(level);
		if (capacity == 0 || lined < 0 || lined > 1 || count < 0 || count > 24
			|| spikes < 0 || experience < 0 || products < 0 || freeSlots < 0 || freeSlots > 28
			|| (count == 0 ? state != lined : count == 1 ? state != 2 : state != 3 && state != 4))
			return Action.BLOCKED;
		if (pending())
		{
			if (tick < started || experience < experienceBefore || products < productsBefore) return Action.BLOCKED;
			if (pending == Action.LINE && lined == 1 && count == 0 && spikes == spikesBefore - 1)
				pending = null;
			else if (pending == Action.LURE && lined == 1 && count == countBefore + 1 && experience - experienceBefore == 20)
				pending = null;
			else if (pending == Action.HARVEST && count == 0 && lined == 0
				&& experience - experienceBefore == countBefore * harvestExperience
				&& products - productsBefore >= countBefore)
			{
				caught = countBefore;
				pending = null;
				return Action.CAUGHT;
			}
			else
			{
				return Action.WAIT;
			}
		}
		if (count == 0 && finish) return Action.FINISHED;
		if (count > 0 && (lined == 0 || finish || count >= Math.min(capacity, freeSlots - 2)))
			return freeSlots >= count + 2 ? Action.HARVEST : Action.BANK;
		if (freeSlots < 3) return Action.BANK;
		if (lined == 0) return spikes > 0 ? Action.LINE : Action.TAKE_SPIKES;
		return Action.LURE;
	}
}
