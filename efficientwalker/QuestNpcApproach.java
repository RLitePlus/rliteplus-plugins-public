package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

final class QuestNpcApproach
{
	private QuestNpcApproach() { }

	static List<WorldPoint> waypoints(WorldPoint marker)
	{
		return waypoints(marker, 1);
	}

	static List<WorldPoint> waypoints(WorldPoint marker, int radius)
	{
		List<WorldPoint> points = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dy = -radius; dy <= radius; dy++)
			{
				if (dx != 0 || dy != 0) { points.add(marker.dx(dx).dy(dy)); }
			}
		}
		return points;
	}

}
