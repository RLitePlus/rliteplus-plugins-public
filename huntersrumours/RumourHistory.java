package net.runelite.client.plugins.microbot.huntersrumours;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.client.plugins.microbot.huntersrumours.RumourAssignment.Creature;
import net.runelite.client.plugins.microbot.huntersrumours.RumourAssignment.Hunter;

final class RumourHistory
{
	private final Map<Hunter, Creature> observed = new EnumMap<>(Hunter.class);
	private final Map<Hunter, Creature> stored = new EnumMap<>(Hunter.class);
	private final EnumSet<Hunter> verified = EnumSet.noneOf(Hunter.class);
	private final EnumSet<Hunter> temporary = EnumSet.noneOf(Hunter.class);
	private Hunter completed;

	synchronized void observe(RumourAssignment assignment)
	{
		Hunter hunter = assignment.hunter;
		if (completed != null)
		{
			if (hunter == completed)
			{
				stored.put(hunter, assignment.creature);
				verified.add(hunter);
				temporary.remove(hunter);
			}
			else
			{
				temporary.add(hunter);
			}
			completed = null;
		}
		if (temporary.contains(hunter) && stored.get(hunter) == assignment.creature)
		{
			temporary.remove(hunter);
		}
		if (!temporary.contains(hunter) && stored.get(hunter) != assignment.creature) verified.remove(hunter);
		observed.put(hunter, assignment.creature);
	}

	synchronized void loseContinuity()
	{
		verified.clear();
		temporary.clear();
		completed = null;
	}

	synchronized void complete(RumourAssignment assignment)
	{
		if (assignment == null) return;
		Hunter hunter = assignment.hunter;
		stored.remove(hunter);
		observed.remove(hunter);
		verified.remove(hunter);
		temporary.remove(hunter);
		completed = hunter;
	}

	synchronized Map<String, String> display()
	{
		Map<String, String> result = new LinkedHashMap<>();
		for (Hunter hunter : Hunter.values())
		{
			Creature saved = stored.get(hunter);
			Creature seen = observed.get(hunter);
			String value = saved == null ? "Stored: unknown" : "Stored: " + saved.name()
				+ (verified.contains(hunter) ? "" : " (unverified history)");
			if (seen != null) value += "; " + (temporary.contains(hunter) ? "Temporary: " : "Last observed: ") + seen.name();
			result.put(hunter.name(), value);
		}
		return result;
	}

	synchronized String encode()
	{
		StringBuilder value = new StringBuilder();
		for (Hunter hunter : Hunter.values())
		{
			value.append(hunter.name()).append(':').append(stored.containsKey(hunter) ? stored.get(hunter).name() : "")
				.append(':').append(observed.containsKey(hunter) ? observed.get(hunter).name() : "").append(';');
		}
		return value.toString();
	}

	synchronized void restore(String value)
	{
		stored.clear();
		observed.clear();
		verified.clear();
		temporary.clear();
		completed = null;
		if (value == null || value.length() > 2048) return;
		try
		{
			for (String row : value.split(";"))
			{
				String[] fields = row.split(":", -1);
				if (fields.length != 3) throw new IllegalArgumentException();
				Hunter hunter = Hunter.valueOf(fields[0]);
				if (!fields[1].isEmpty()) stored.put(hunter, Creature.valueOf(fields[1]));
				if (!fields[2].isEmpty()) observed.put(hunter, Creature.valueOf(fields[2]));
			}
		}
		catch (IllegalArgumentException exception)
		{
			stored.clear();
			observed.clear();
		}
	}
}
