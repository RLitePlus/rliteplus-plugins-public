package net.runelite.client.plugins.microbot.madangel;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("madangel")
public interface MadAngelConfig extends Config
{
	@Range(min = 1, max = 100)
	@ConfigItem(keyName = "eatPercent", name = "Eat below HP %", description = "Eat below this percentage of your real Hitpoints level", position = 1)
	default int eatPercent() { return 50; }

	@Range(min = 1, max = 60)
	@ConfigItem(keyName = "escapePercent", name = "Escape below HP %", description = "Use the Royal seed pod when out of food and at or below this percentage of your real Hitpoints level", position = 2)
	default int escapePercent() { return 10; }

	@ConfigItem(keyName = "piety", name = "Use Piety", description = "Requires Piety to be unlocked", position = 3)
	default boolean piety() { return true; }

	@ConfigItem(keyName = "food", name = "Food to use", description = "Food to withdraw and eat", position = 4)
	default Food food() { return Food.SHARK; }

	@Range(min = 1, max = 26)
	@ConfigItem(keyName = "foodAmount", name = "Food amount to restock", description = "Total food to carry after banking", position = 5)
	default int foodAmount() { return 20; }

	@ConfigItem(keyName = "prayerPotion", name = "Prayer restoration potion", description = "Potion to withdraw and drink; all doses are recognised", position = 6)
	default PrayerPotion prayerPotion() { return PrayerPotion.PRAYER_POTION; }

	@Range(min = 1, max = 26)
	@ConfigItem(keyName = "prayerAmount", name = "Prayer potion amount to restock", description = "Total four-dose potions to carry after banking, not individual doses", position = 7)
	default int prayerAmount() { return 3; }

	enum Food
	{
		SHARK("Shark", 385), MANTA_RAY("Manta ray", 391), MONKFISH("Monkfish", 7946),
		KARAMBWAN("Cooked karambwan", 3144), TUNA_POTATO("Tuna potato", 7060);

		final String name;
		final int id;
		Food(String name, int id) { this.name = name; this.id = id; }
		@Override
		public String toString() { return name; }
	}

	enum PrayerPotion
	{
		PRAYER_POTION("Prayer potion", 2434, 139, 141, 143),
		SUPER_RESTORE("Super restore", 3024, 3026, 3028, 3030);

		final String name;
		final int[] ids;
		PrayerPotion(String name, int... ids) { this.name = name; this.ids = ids; }
		@Override
		public String toString() { return name; }
	}
}
