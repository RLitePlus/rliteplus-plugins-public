package net.runelite.client.plugins.microbot.giantsfoundry;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(GiantsFoundryConfig.GROUP)
public interface GiantsFoundryConfig extends Config
{
	String GROUP = "privateGiantsFoundry";

	@ConfigItem(keyName = "alloy", name = "Alloy recipe", position = 0,
		description = "Bars to use after restarting the plugin")
	default Alloy alloy()
	{
		return Alloy.STEEL_MITHRIL;
	}

	@ConfigItem(keyName = "coolingMethod", name = "Cooling method", position = 1,
		description = "Ice gloves (including smiths gloves (i)), or fetch and fill a bucket of water")
	default CoolingMethod coolingMethod()
	{
		return CoolingMethod.ICE_GLOVES;
	}

	enum CoolingMethod
	{
		ICE_GLOVES("Ice gloves"), WATER_BUCKET("Bucket of water");

		private final String label;

		CoolingMethod(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum Alloy
	{
		STEEL_MITHRIL("14 steel / 14 mithril", 2, 3, 14),
		MITHRIL_ADAMANT("14 mithril / 14 adamantite", 3, 4, 14),
		MITHRIL_ADAMANT_18_10("18 mithril / 10 adamantite", 3, 4, 18),
		BRONZE_IRON("9 bronze / 19 iron", 0, 1, 9),
		BRONZE_STEEL("9 bronze / 19 steel", 0, 2, 9),
		BRONZE_MITHRIL("7 bronze / 21 mithril", 0, 3, 7),
		BRONZE_ADAMANT("5 bronze / 23 adamantite", 0, 4, 5),
		BRONZE_RUNITE("4 bronze / 24 runite", 0, 5, 4),
		IRON_STEEL("14 iron / 14 steel", 1, 2, 14),
		IRON_MITHRIL("12 iron / 16 mithril", 1, 3, 12),
		IRON_ADAMANT("11 iron / 17 adamantite", 1, 4, 11),
		IRON_RUNITE("10 iron / 18 runite", 1, 5, 10),
		STEEL_ADAMANT("13 steel / 15 adamantite", 2, 4, 13),
		STEEL_RUNITE("12 steel / 16 runite", 2, 5, 12),
		MITHRIL_RUNITE("14 mithril / 14 runite", 3, 5, 14),
		ADAMANT_RUNITE("14 adamantite / 14 runite", 4, 5, 14),
		ADAMANT_RUNITE_19_9("19 adamantite / 9 runite", 4, 5, 19),
		BRONZE_ONLY("28 bronze", 0, 0, 28),
		IRON_ONLY("28 iron", 1, 1, 28),
		STEEL_ONLY("28 steel", 2, 2, 28),
		MITHRIL_ONLY("28 mithril", 3, 3, 28),
		ADAMANTITE_ONLY("28 adamantite", 4, 4, 28),
		RUNITE_ONLY("28 runite", 5, 5, 28);

		final int firstMetal;
		final int secondMetal;
		final int firstCount;
		private final String label;

		Alloy(String label, int firstMetal, int secondMetal, int firstCount)
		{
			this.label = label;
			this.firstMetal = firstMetal;
			this.secondMetal = secondMetal;
			this.firstCount = firstCount;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}
}
