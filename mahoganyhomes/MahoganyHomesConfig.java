package net.runelite.client.plugins.microbot.mahoganyhomes;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(MahoganyHomesConfig.GROUP)
public interface MahoganyHomesConfig extends Config
{
	String GROUP = "mahoganyhomes";

	@ConfigItem(
		keyName = "contractTier",
		name = "Contract level",
		description = "Materials to prepare when no current contract is detected",
		position = 0
	)
	default MahoganyHomesData contractTier()
	{
		return MahoganyHomesData.EXPERT;
	}

	@ConfigItem(
		keyName = "hideOverlay",
		name = "Hide overlay",
		description = "Hide the Mahogany Homes metrics overlay",
		position = 1
	)
	default boolean hideOverlay()
	{
		return false;
	}
}
