package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.api.coords.WorldPoint;

final class BoxTrapHunt
{
	enum Action { SET, CHECK, DISMANTLE, RECOVER, WAIT, CAUGHT, CLEARED, BLOCKED }

	final WorldPoint site;
	private final RumourAssignment.Creature creature;
	private Action pending;
	private boolean owned;
	private boolean placementSeen;
	private int started;
	private int trapsBefore;
	private int catchesBefore;
	private int previousId = -1;
	private int submittedId;

	BoxTrapHunt(WorldPoint site, RumourAssignment.Creature creature)
	{
		this.site = site;
		this.creature = creature;
	}

	synchronized boolean ownsTrap()
	{
		return owned || pending != null;
	}

	synchronized void placed(WorldPoint location, int id, int tick)
	{
		if (pending == Action.SET && tick >= started
			&& site.equals(location) && id == emptyId()) placementSeen = true;
	}

	synchronized boolean pending()
	{
		return pending != null;
	}

	synchronized boolean missing()
	{
		return owned && previousId == -1;
	}

	boolean known(int id)
	{
		if (creature == RumourAssignment.Creature.TROPICAL_WAGTAIL) return id >= 9344 && id <= 9348;
		int first = creature == RumourAssignment.Creature.EMBERTAILED_JERBOA ? 50728
			: creature == RumourAssignment.Creature.RED_CHINCHOMPA ? 9390 : 9386;
		return id == 9380 || id == 9381 || id == 9385 || id == fullId()
			|| (id >= first && id < first + 4);
	}

	private int emptyId()
	{
		return creature == RumourAssignment.Creature.TROPICAL_WAGTAIL ? 9345 : 9380;
	}

	private int fullId()
	{
		return creature == RumourAssignment.Creature.TROPICAL_WAGTAIL ? 9348
			: creature == RumourAssignment.Creature.EMBERTAILED_JERBOA ? 50727
			: creature == RumourAssignment.Creature.RED_CHINCHOMPA ? 9383 : 9382;
	}

	synchronized Action next(int tick, int id, int traps, int catches, boolean leave,
		boolean busy, boolean ownDroppedTrap)
	{
		if (traps < 0 || catches < 0) return Action.BLOCKED;
		previousId = id;
		if (pending == Action.SET)
		{
			if (placementSeen && traps == trapsBefore - 1 && known(id))
			{
				owned = true;
				pending = null;
			}
			else
			{
				return Action.WAIT;
			}
		}
		int failedId = creature == RumourAssignment.Creature.TROPICAL_WAGTAIL ? 9344 : 9385;
		if (owned && pending == Action.DISMANTLE && id != submittedId
			&& (id == fullId() || id == failedId)
			&& traps == trapsBefore && catches == catchesBefore) pending = null;
		if (pending != null)
		{
			if (id == -1 && traps == trapsBefore + 1
				&& (pending != Action.CHECK || catches == catchesBefore + 1))
			{
				boolean caught = pending == Action.CHECK;
				pending = null;
				owned = false;
				return caught ? Action.CAUGHT : Action.CLEARED;
			}
			if (id == -1 && ownDroppedTrap && !busy && traps == trapsBefore)
				return Action.RECOVER;
			return Action.WAIT;
		}
		if (busy) return Action.WAIT;
		if (!owned)
		{
			if (leave) return Action.CLEARED;
			return id == -1 && !ownDroppedTrap && traps > 0 ? Action.SET : Action.BLOCKED;
		}
		if (id == fullId()) return Action.CHECK;
		if (id == failedId || (leave && id == emptyId())) return Action.DISMANTLE;
		if (id == -1)
		{
			if (!ownDroppedTrap) return Action.WAIT;
			return Action.RECOVER;
		}
		return known(id) ? Action.WAIT : Action.BLOCKED;
	}

	synchronized void submitted(Action action, int tick, int traps, int catches)
	{
		if (action != Action.SET && action != Action.CHECK && action != Action.DISMANTLE && action != Action.RECOVER) return;
		if (pending == action && (action == Action.SET || action == Action.CHECK || action == Action.DISMANTLE))
			return;
		submittedId = previousId;
		pending = action;
		started = tick;
		trapsBefore = traps;
		catchesBefore = catches;
		if (action == Action.SET) placementSeen = false;
	}
}
