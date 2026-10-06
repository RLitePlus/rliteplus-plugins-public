package net.runelite.client.plugins.microbot.enchanter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(EnchanterConfig.GROUP)
public interface EnchanterConfig extends Config
{
	String GROUP = "enchanter";

	@ConfigItem(
		keyName = "target",
		name = "Target",
		description = "Item to enchant after the plugin is re-enabled",
		position = 0
	)
	default Enchantment target()
	{
		return Enchantment.SAPPHIRE_RING;
	}

	@ConfigItem(
		keyName = "hideOverlay",
		name = "Hide overlay",
		description = "Hide the Enchanter status overlay",
		position = 1
	)
	default boolean hideOverlay()
	{
		return false;
	}
}
