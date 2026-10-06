package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.api.coords.WorldPoint;

final class DeadfallHunt
{
	enum Action { SET, CHECK, DISMANTLE, WAIT, CAUGHT, CLEARED, BLOCKED }

	private final int capturing;
	private final int capturingSouth;
	private final int full;

	DeadfallHunt(RumourAssignment.Creature creature)
	{
		boolean wild = creature == RumourAssignment.Creature.WILD_KEBBIT;
		boolean barbed = creature == RumourAssignment.Creature.BARB_TAILED_KEBBIT;
		boolean prickly = creature == RumourAssignment.Creature.PRICKLY_KEBBIT;
		boolean pyre = creature == RumourAssignment.Creature.PYRE_FOX;
		capturing = wild ? 20131 : barbed ? 20129 : prickly ? 19218 : pyre ? 50724 : 19851;
		capturingSouth = wild ? 20647 : barbed ? 20130 : prickly ? 19219 : pyre ? 50725 : 20128;
		full = wild ? 20651 : barbed ? 20650 : prickly ? 20648 : pyre ? 50726 : 20649;
	}

	boolean knownId(int id)
	{
		return id == 19215 || id == 19216 || id == 19217 || id == 20652
			|| id == capturing || id == capturingSouth || id == full;
	}

	private boolean placing;
	private boolean owned;
	private boolean checking;
	private boolean catchExpected;
	private int started;
	private boolean confirmed;
	private int productsBefore;

	synchronized boolean ownsTrap()
	{
		return placing || owned || checking;
	}

	synchronized boolean pending()
	{
		return placing || checking;
	}

	synchronized void confirmPlacement(Object[] args, WorldPoint site, int tick)
	{
		if (!placing || tick < started || site == null || args == null || args.length != 8) return;
		for (Object arg : args) if (!(arg instanceof Integer)) return;
		int packed = (Integer) args[1];
		WorldPoint location = new WorldPoint((packed >> 14) & 16383, packed & 16383, packed >>> 28);
		if ((Integer) args[0] == 5474 && (Integer) args[2] == 19217 && (Integer) args[3] == 10
			&& (Integer) args[4] == 4 && site.equals(location)) confirmed = true;
	}

	synchronized Action next(int tick, int id, int logs, int products, boolean leave)
	{
		if (placing)
		{
			if (confirmed && (id == 19217 || id == capturing || id == capturingSouth || id == full))
			{
				placing = false;
				owned = true;
			}
			else return Action.WAIT;
		}
		if (owned && checking && !catchExpected && id == full && products == productsBefore) checking = false;
		if (checking)
		{
			if (id == 19215 && (!catchExpected || products > productsBefore))
			{
				checking = owned = false;
				return catchExpected ? Action.CAUGHT : Action.CLEARED;
			}
			return Action.WAIT;
		}
		if (owned)
		{
			if (id == full)
			{
				return Action.CHECK;
			}
			if (id == 19215)
			{
				owned = false;
				return Action.CLEARED;
			}
			if (leave && id == 19217) return Action.DISMANTLE;
			else return knownId(id) || id == -1 ? Action.WAIT : Action.BLOCKED;
		}
		if (leave) return Action.CLEARED;
		if (id != 19215 || logs < 1) return Action.BLOCKED;
		return Action.SET;
	}

	synchronized void submitted(Action action, int tick, int products)
	{
		if (action == Action.SET)
		{
			placing = true;
			confirmed = false;
			started = tick;
		}
		else if (action == Action.CHECK || action == Action.DISMANTLE)
		{
			checking = true;
			catchExpected = action == Action.CHECK;
			started = tick;
			productsBefore = products;
		}
	}

}
