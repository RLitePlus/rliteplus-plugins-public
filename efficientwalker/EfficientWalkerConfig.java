package net.runelite.client.plugins.microbot.efficientwalker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup(EfficientWalkerConfig.GROUP)
public interface EfficientWalkerConfig extends Config
{
	String GROUP = "efficientwalker";

	@ConfigSection(
		name = "General",
		description = "General walking and interaction settings",
		position = -2
	)
	String generalSection = "generalSection";

	@ConfigItem(
		keyName = "handleInteractions",
		name = "Automate quest steps",
		description = "Press Walk to quest step once to follow quest targets, handle supported interactions and dialogue; Cancel walk stops automation",
		position = 0,
		section = generalSection
	)
	default boolean handleInteractions()
	{
		return false;
	}

	@ConfigItem(
		keyName = "disableAutoRetaliate",
		name = "Disable auto retaliate",
		description = "Turn off Auto Retaliate before walking; when unchecked, leave its current setting unchanged",
		position = 1,
		section = generalSection
	)
	default boolean disableAutoRetaliate()
	{
		return true;
	}

	@ConfigSection(
		name = "Hotkeys",
		description = "Keyboard shortcuts for walking",
		position = -1
	)
	String hotkeysSection = "hotkeysSection";

	@ConfigItem(
		keyName = "walkToNearestBankHotkey",
		name = "Walk to nearest bank",
		description = "Walk to the nearest reachable bank",
		position = 0,
		section = hotkeysSection
	)
	default Keybind walkToNearestBankHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "walkToPrayerAltarHotkey",
		name = "Walk to altar",
		description = "Walk to the nearest reachable prayer restoration altar or pool and use it",
		position = 1,
		section = hotkeysSection
	)
	default Keybind walkToPrayerAltarHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "walkToQuestStepHotkey",
		name = "Walk to quest step",
		description = "Walk to the current Quest Helper step; requires Quest Helper and a selected quest",
		position = 2,
		section = hotkeysSection
	)
	default Keybind walkToQuestStepHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "cancelWalkHotkey",
		name = "Cancel walk",
		description = "Cancel the current walk and clear its route",
		position = 3,
		section = hotkeysSection
	)
	default Keybind cancelWalkHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigSection(
		name = "Route modification",
		description = "Choose which optional shortcuts Efficient Walker may include",
		position = 0
	)
	String routeModificationSection = "routeModificationSection";

	@ConfigItem(
		keyName = "useTeleportations",
		name = "Teleport spells and tablets",
		description = "Use supported standard spells and tablets when they improve a route",
		position = 0,
		section = routeModificationSection
	)
	default boolean useTeleportations()
	{
		return true;
	}

	@ConfigItem(
		keyName = "useItemsFromBank",
		name = "Items from bank",
		description = "Fetch eligible banked teleport supplies and missing Coins for faster charter routes. Leave enough inventory space for the required supplies",
		position = 6,
		section = routeModificationSection
	)
	default boolean useItemsFromBank()
	{
		return false;
	}

	@ConfigItem(
		keyName = "useFairyRings",
		name = "Fairy rings",
		description = "Include available fairy rings when calculating routes",
		position = 3,
		section = routeModificationSection
	)
	default boolean useFairyRings()
	{
		return true;
	}

	@ConfigItem(
		keyName = "useCharterShips",
		name = "Charter ships",
		description = "Include available charter ships when calculating routes",
		position = 4,
		section = routeModificationSection
	)
	default boolean useCharterShips()
	{
		return true;
	}

	@ConfigItem(
		keyName = "useSpiritTrees",
		name = "Spirit trees",
		description = "Include available spirit trees when calculating routes",
		position = 5,
		section = routeModificationSection
	)
	default boolean useSpiritTrees()
	{
		return true;
	}

	@ConfigItem(
		keyName = "allowAgilityShortcuts",
		name = "Agility shortcuts",
		description = "Include eligible agility shortcuts when calculating routes",
		position = 1,
		section = routeModificationSection
	)
	default boolean allowAgilityShortcuts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "allowGrappleShortcuts",
		name = "Grapple shortcuts",
		description = "Include eligible crossbow and Mith grapple shortcuts when calculating routes",
		position = 2,
		section = routeModificationSection
	)
	default boolean allowGrappleShortcuts()
	{
		return false;
	}

	@ConfigSection(
		name = "Player-grown spirit trees",
		description = "Select the spirit trees your character has grown",
		position = 1,
		closedByDefault = true
	)
	String playerGrownSpiritTreesSection = "playerGrownSpiritTreesSection";

	@ConfigItem(keyName = "spiritTreePortSarim", name = "Port Sarim",
		description = "Use your player-grown Port Sarim spirit tree", position = 0, section = playerGrownSpiritTreesSection)
	default boolean spiritTreePortSarim() { return false; }

	@ConfigItem(keyName = "spiritTreeEtceteria", name = "Etceteria",
		description = "Use your player-grown Etceteria spirit tree", position = 1, section = playerGrownSpiritTreesSection)
	default boolean spiritTreeEtceteria() { return false; }

	@ConfigItem(keyName = "spiritTreeBrimhaven", name = "Brimhaven",
		description = "Use your player-grown Brimhaven spirit tree", position = 2, section = playerGrownSpiritTreesSection)
	default boolean spiritTreeBrimhaven() { return false; }

	@ConfigItem(keyName = "spiritTreeHosidius", name = "Hosidius",
		description = "Use your player-grown Hosidius spirit tree", position = 3, section = playerGrownSpiritTreesSection)
	default boolean spiritTreeHosidius() { return false; }

	@ConfigItem(keyName = "spiritTreeFarmingGuild", name = "Farming Guild",
		description = "Use your player-grown Farming Guild spirit tree", position = 4, section = playerGrownSpiritTreesSection)
	default boolean spiritTreeFarmingGuild() { return false; }

	@ConfigSection(
		name = "Debug",
		description = "Diagnostic options",
		position = 2,
		closedByDefault = true
	)
	String debugSection = "debugSection";

	@ConfigItem(
		keyName = "enableDebugging",
		name = "Enable debugging",
		description = "Writes route planning, movement, obstacle, and transition diagnostics to the client log",
		position = 0,
		section = debugSection
	)
	default boolean enableDebugging()
	{
		return false;
	}
}
