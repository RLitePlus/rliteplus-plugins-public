package net.runelite.client.plugins.microbot.mahoganyhomes;

import java.util.Arrays;
import java.util.Comparator;
import net.runelite.api.coords.WorldPoint;

enum MahoganyHomesContractorData
{
	AMY("Amy", new WorldPoint(2990, 3365, 0)),
	MARLO("Marlo", new WorldPoint(3241, 3471, 0)),
	ELLIE("Ellie", new WorldPoint(2635, 3294, 0)),
	ANGELO("Angelo", new WorldPoint(1782, 3624, 0));

	final String name;
	final WorldPoint location;

	MahoganyHomesContractorData(String name, WorldPoint location)
	{
		this.name = name;
		this.location = location;
	}

	static MahoganyHomesContractorData nearest(WorldPoint point)
	{
		return Arrays.stream(values())
			.min(Comparator.comparingInt(contractor -> point.distanceTo2D(contractor.location)))
			.orElseThrow();
	}
}
