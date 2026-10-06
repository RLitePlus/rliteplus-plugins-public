package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("huntersrumours")
public interface HuntersRumoursConfig extends Config
{
	enum HunterChoice
	{
		GILMAN, CERVUS, ORNUS, ACO, TECO;

		RumourAssignment.Hunter hunter()
		{
			return RumourAssignment.Hunter.valueOf(name());
		}

		@Override
		public String toString()
		{
			return hunter().toString();
		}
	}

	@Range(min = 0, max = 1000000)
	@ConfigItem(keyName = "sessionTarget", name = "Session completion target",
		description = "Finish this many rumours this run; 0 disables this limit", position = 1)
	default int sessionTarget()
	{
		return 10;
	}

	@Range(min = 0, max = 99)
	@ConfigItem(keyName = "hunterTarget", name = "Hunter level target",
		description = "Finish the current rumour after reaching this real Hunter level; 0 disables this limit", position = 2)
	default int hunterTarget()
	{
		return 0;
	}

	@Range(min = 0, max = 1000000)
	@ConfigItem(keyName = "runtimeMinutes", name = "Runtime limit (minutes)",
		description = "Finish the current rumour after this elapsed time, including pauses. All limits 0 runs continuously", position = 3)
	default int runtimeMinutes()
	{
		return 0;
	}

	@ConfigItem(keyName = "hunter", name = "Hunter",
		description = "Use this hunter for rumours, switching to their stored task when needed. Restart to apply changes", position = 0)
	default HunterChoice hunter()
	{
		return HunterChoice.ACO;
	}

}
