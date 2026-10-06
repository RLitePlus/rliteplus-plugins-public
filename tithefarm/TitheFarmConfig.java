package net.runelite.client.plugins.microbot.tithefarm;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("tithefarm")
public interface TitheFarmConfig extends Config
{
	enum Mode
	{
		LAZY, MODERATE, EFFICIENT;

		@Override
		public String toString() { return this == LAZY ? "Lazy" : this == MODERATE ? "Moderate" : "Efficient (unavailable)"; }
	}

	@ConfigItem(keyName = "mode", name = "Mode", description = "Lazy: 20 plants and 8 ordinary cans. Moderate: 23 plants and 9 ordinary cans. Gricoller's can replaces ordinary cans in either mode. Efficient is unavailable. Restart to change mode.", position = 0)
	default Mode mode() { return Mode.LAZY; }

}
