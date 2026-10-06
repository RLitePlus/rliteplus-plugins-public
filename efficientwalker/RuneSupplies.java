package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntPredicate;
import net.runelite.client.plugins.microbot.util.magic.Runes;

final class RuneSupplies
{
	private RuneSupplies() { }

	static Map<Integer, Integer> fromBank(Map<Runes, Integer> missing, Map<Integer, Integer> bank,
		IntPredicate allowed)
	{
		Map<Runes, Integer> remaining = new EnumMap<>(Runes.class);
		remaining.putAll(missing);
		Map<Integer, Integer> supplies = new LinkedHashMap<>();
		for (Runes needed : Runes.values())
		{
			for (Runes candidate : Runes.values())
			{
				int deficit = remaining.getOrDefault(needed, 0);
				if (deficit <= 0) { break; }
				int id = candidate.getItemId();
				if (!candidate.providesRune(needed) || !allowed.test(id)) { continue; }
				int amount = Math.min(deficit, bank.getOrDefault(id, 0) - supplies.getOrDefault(id, 0));
				if (amount <= 0) { continue; }
				supplies.merge(id, amount, Integer::sum);
				for (Runes covered : Runes.values())
				{
					if (candidate.providesRune(covered))
					{
						remaining.computeIfPresent(covered, (rune, quantity) -> Math.max(0, quantity - amount));
					}
				}
			}
			if (remaining.getOrDefault(needed, 0) > 0) { return null; }
		}
		return supplies;
	}
}
