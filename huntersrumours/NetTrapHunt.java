package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.api.coords.WorldPoint;

final class NetTrapHunt
{
	enum Action { SET, CHECK, DISMANTLE, RECOVER, WAIT, CAUGHT, CLEARED, BLOCKED }

	final WorldPoint tree;
	private final boolean red;
	private final boolean swamp;
	private final boolean tecu;
	private WorldPoint trap;
	private boolean placing;
	private boolean owned;
	private boolean collecting;
	private boolean catchExpected;
	private boolean recovering;
	private boolean confirmed;
	private int started;
	private int ropesBefore;
	private int netsBefore;
	private int catchesBefore;

	NetTrapHunt(WorldPoint tree, RumourAssignment.Creature creature)
	{
		this.tree = this.trap = tree;
		this.red = creature == RumourAssignment.Creature.RED_SALAMANDER;
		this.swamp = creature == RumourAssignment.Creature.SWAMP_LIZARD;
		this.tecu = creature == RumourAssignment.Creature.TECU_SALAMANDER;
	}

	synchronized WorldPoint trapLocation()
	{
		return trap;
	}

	synchronized boolean pending()
	{
		return placing || collecting || recovering;
	}

	synchronized boolean needsTool(int id, int count)
	{
		return recovering && count < (id == 954 ? ropesBefore : netsBefore) + 1;
	}

	synchronized boolean ownsTrap()
	{
		return owned || pending();
	}

	int emptyId()
	{
		return tecu ? 50721 : swamp ? 9341 : red ? 8990 : 8732;
	}

	boolean knownId(int id)
	{
		return id == emptyId() || id == (tecu ? 50722 : swamp ? 9342 : red ? 8991 : 8733) || id == (tecu ? 50720 : swamp ? 9257 : red ? 8989 : 8730)
			|| id == (tecu ? 50723 : swamp ? 9343 : red ? 8992 : 8731) || id == (tecu ? 50716 : swamp ? 9003 : red ? 8985 : 8972)
			|| id == (tecu ? 50717 : swamp ? 9004 : red ? 8986 : 8734) || id == (tecu ? 50718 : swamp ? 9005 : red ? 8987 : 8974) || id == (tecu ? 50719 : swamp ? 9158 : red ? 8988 : 8973);
	}

	synchronized void timer(Object[] args, int tick)
	{
		if ((!placing && !owned) || args == null || args.length != 8) return;
		for (Object arg : args) if (!(arg instanceof Integer)) return;
		int id = (Integer) args[2];
		WorldPoint location = WorldPoint.fromCoord((Integer) args[1]);
		if ((Integer) args[0] != 5474 || (Integer) args[3] != 10 || (Integer) args[4] != 4
			|| tree.distanceTo(location) > 1 || !knownId(id)) return;
		if (placing)
		{
			if (tick < started || (id != (tecu ? 50720 : swamp ? 9257 : red ? 8989 : 8730) && id != (tecu ? 50723 : swamp ? 9343 : red ? 8992 : 8731))) return;
			confirmed = true;
		}
		trap = location;
	}

	synchronized Action next(int tick, int id, int ropes, int nets, int catches, boolean leave, boolean busy, boolean droppedTools)
	{
		if (ropes < 0 || nets < 0 || catches < 0) return Action.BLOCKED;
		if (placing)
		{
			if (confirmed && ropes == ropesBefore - 1 && nets == netsBefore - 1
				&& knownId(id) && id != emptyId())
			{
				placing = false;
				owned = true;
			}
			else
			{
				return Action.WAIT;
			}
		}
		boolean full = id == (tecu ? 50717 : swamp ? 9004 : red ? 8986 : 8734);
		if (owned && collecting && !catchExpected && full
			&& ropes == ropesBefore && nets == netsBefore && catches == catchesBefore) collecting = false;
		if (collecting || recovering)
		{
			if (id == emptyId() && ropes == ropesBefore + 1 && nets == netsBefore + 1)
			{
				if (catchExpected && catches != catchesBefore + 1) return Action.WAIT;
				owned = collecting = recovering = false;
				return catchExpected ? Action.CAUGHT : Action.CLEARED;
			}
			if (collecting && id == emptyId() && droppedTools)
			{
				collecting = false;
				recovering = true;
				catchExpected &= catches == catchesBefore + 1;
			}
			return recovering && !busy ? Action.RECOVER : Action.WAIT;
		}
		if (busy) return Action.WAIT;
		if (owned)
		{
			boolean failed = id == (tecu ? 50719 : swamp ? 9158 : red ? 8988 : 8973);
			boolean set = id == (tecu ? 50720 : swamp ? 9257 : red ? 8989 : 8730) || id == (tecu ? 50723 : swamp ? 9343 : red ? 8992 : 8731);
			if (full || failed || (leave && set) || id == emptyId())
			{
				if (id == emptyId())
				{
					started = tick;
					ropesBefore = ropes;
					netsBefore = nets;
					catchesBefore = catches;
					catchExpected = false;
					recovering = true;
					return Action.RECOVER;
				}
				return full ? Action.CHECK : Action.DISMANTLE;
			}
			return knownId(id) || id == -1 ? Action.WAIT : Action.BLOCKED;
		}
		if (leave) return Action.CLEARED;
		if (id != emptyId() || ropes < 1 || nets < 1) return Action.BLOCKED;
		return Action.SET;
	}

	synchronized void submitted(Action action, int tick, int ropes, int nets, int catches)
	{
		if (action != Action.SET && action != Action.CHECK && action != Action.DISMANTLE) return;
		if (collecting || (placing && action == Action.SET))
			return;
		started = tick;
		ropesBefore = ropes;
		netsBefore = nets;
		catchesBefore = catches;
		if (action == Action.SET)
		{
			placing = true;
			confirmed = false;
		}
		else
		{
			collecting = true;
			catchExpected = action == Action.CHECK;
		}
	}

}
