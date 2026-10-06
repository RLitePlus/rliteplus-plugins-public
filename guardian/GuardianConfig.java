package net.runelite.client.plugins.microbot.guardian;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("guardian")
public interface GuardianConfig extends Config
{
	@Range(min = 1, max = 99)
	@ConfigItem(keyName = "eatHpPercent", name = "Eat below HP %", description = "Eat the first inventory item with an Eat action below this percentage; escape at this threshold when food and prayer recovery are exhausted.", position = 0)
	default int eatHpPercent() { return 50; }

	@Range(min = 1, max = 99)
	@ConfigItem(keyName = "restorePrayerPercent", name = "Restore prayer below %", description = "Drink a prayer, restore, or renewal potion below this percentage of maximum prayer.", position = 1)
	default int restorePrayerPercent() { return 25; }
}
