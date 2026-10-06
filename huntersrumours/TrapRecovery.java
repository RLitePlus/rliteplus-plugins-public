package net.runelite.client.plugins.microbot.huntersrumours;

import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

final class TrapRecovery
{
	final int world;
	final RumourAssignment.Creature creature;
	final int tools;
	final int ropes;
	final Set<WorldPoint> sites = new LinkedHashSet<>();
	private long updated;

	TrapRecovery(int world, RumourAssignment.Creature creature, int tools, int ropes, long now)
	{
		if (world < 301 || world > 2000 || creature == null || tools < 1 || tools > 28 || ropes < 0 || ropes > 28
			|| !(creature.boxCatchId() > 0 || creature == RumourAssignment.Creature.TROPICAL_WAGTAIL || creature.netCatchId() > 0)
			|| (creature.netCatchId() > 0) != (ropes > 0) || now <= 0) throw new IllegalArgumentException("Invalid recovery record");
		this.world = world;
		this.creature = creature;
		this.tools = tools;
		this.ropes = ropes;
		updated = now;
	}

	int toolId()
	{
		return ropes > 0 ? 303 : creature == RumourAssignment.Creature.TROPICAL_WAGTAIL ? 10006 : 10008;
	}

	void placed(WorldPoint site, long now)
	{
		if (site == null || site.getX() < 0 || site.getX() > 16383 || site.getY() < 0 || site.getY() > 16383
			|| site.getPlane() < 0 || site.getPlane() > 3 || (!sites.contains(site) && sites.size() >= 5)
			|| (!sites.isEmpty() && sites.iterator().next().distanceTo(site) > 32))
			throw new IllegalArgumentException("Invalid recovery site");
		sites.add(site);
		updated = now;
	}

	boolean nearby(WorldPoint position)
	{
		return sites.stream().anyMatch(site -> site.distanceTo(position) <= (ropes > 0 ? 1 : 0));
	}

	boolean returned(int toolCount, int ropeCount)
	{
		return toolCount == tools && (ropes == 0 || ropeCount == ropes);
	}

	String blocker(int currentWorld, long now)
	{
		if (world != currentWorld) return "Outstanding traps are on world " + world + ". Return to that world before restarting.";
		return null;
	}

	String encode()
	{
		StringBuilder value = new StringBuilder("1|").append(world).append('|').append(creature.name())
			.append('|').append(tools).append('|').append(ropes).append('|').append(updated);
		for (WorldPoint site : sites) value.append('|').append(site.getX()).append(',').append(site.getY()).append(',').append(site.getPlane());
		return value.toString();
	}

	static TrapRecovery restore(String value)
	{
		if (value == null || value.isEmpty()) return null;
		if (value.length() > 512) throw new IllegalArgumentException("Invalid recovery record");
		String[] fields = value.split("\\|", -1);
		if (fields.length < 7 || fields.length > 11 || !"1".equals(fields[0])) throw new IllegalArgumentException("Invalid recovery record");
		long updated = Long.parseLong(fields[5]);
		TrapRecovery record = new TrapRecovery(Integer.parseInt(fields[1]), RumourAssignment.Creature.valueOf(fields[2]),
			Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), updated);
		for (int i = 6; i < fields.length; i++)
		{
			String[] point = fields[i].split(",", -1);
			if (point.length != 3) throw new IllegalArgumentException("Invalid recovery site");
			WorldPoint site = new WorldPoint(Integer.parseInt(point[0]), Integer.parseInt(point[1]), Integer.parseInt(point[2]));
			if (record.sites.contains(site)) throw new IllegalArgumentException("Duplicate recovery site");
			record.placed(site, updated);
		}
		return record;
	}
}
