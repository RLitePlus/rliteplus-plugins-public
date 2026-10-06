package net.runelite.client.plugins.microbot.huntersrumours;

final class ButterflyHunt
{
	enum Action { CATCH, WAIT, CAUGHT, BLOCKED }

	private int target = -1;
	private int started;
	private int experienceBefore;
	private boolean disappeared;

	synchronized boolean pending()
	{
		return target >= 0;
	}

	synchronized void submitted(int index, int tick, int experience)
	{
		if (pending() || index < 0 || experience < 0) return;
		target = index;
		started = tick;
		experienceBefore = experience;
		disappeared = false;
	}

	synchronized void despawned(int index, int tick)
	{
		if (pending() && target == index && tick >= started) disappeared = true;
	}

	synchronized Action next(int tick, int experience, int catchExperience, boolean busy)
	{
		if (experience < 0 || catchExperience <= 0) return Action.BLOCKED;
		if (!pending()) return busy ? Action.WAIT : Action.CATCH;
		if (tick < started || experience < experienceBefore) return Action.BLOCKED;
		if (disappeared && experience - experienceBefore >= catchExperience)
		{
			target = -1;
			return Action.CAUGHT;
		}
		return Action.WAIT;
	}
}
