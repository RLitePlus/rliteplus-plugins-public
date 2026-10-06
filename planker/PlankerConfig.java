package net.runelite.client.plugins.microbot.planker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(PlankerConfig.GROUP)
public interface PlankerConfig extends Config
{
	String GROUP = "planker";

	@ConfigItem(
		keyName = "plankType",
		name = "Plank type",
		description = "The type of planks to make and sell",
		position = 0
	)
	default PlankType plankType()
	{
		return PlankType.OAK;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "logsPerPurchase",
		name = "Logs per purchase",
		description = "Logs to buy when the bank runs out, limited by coins for buying and converting them. 0 disables automatic buying.",
		position = 1
	)
	default int logsPerPurchase()
	{
		return 1000;
	}

	@ConfigItem(
		keyName = "buyDirection",
		name = "Buy price direction",
		description = "Start buying above or below the guide price. Unfilled offers rise 5% after each minute.",
		position = 2
	)
	default PriceDirection buyDirection()
	{
		return PriceDirection.ABOVE;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "buyPercent",
		name = "Buy price adjustment (%)",
		description = "Initial whole percentage above or below the guide price to offer for logs",
		position = 3
	)
	default int buyPercent()
	{
		return 5;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "sellThreshold",
		name = "Sell threshold",
		description = "When not 0, sell every selected plank after all remaining logs are converted. 0 disables automatic selling.",
		position = 4
	)
	default int sellThreshold()
	{
		return 1000;
	}

	@ConfigItem(
		keyName = "sellDirection",
		name = "Sell price direction",
		description = "Start selling above or below the guide price. Unfilled offers fall 5% after each minute.",
		position = 5
	)
	default PriceDirection sellDirection()
	{
		return PriceDirection.ABOVE;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "sellPercent",
		name = "Sell price adjustment (%)",
		description = "Initial whole percentage above or below the guide price to ask for planks",
		position = 6
	)
	default int sellPercent()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "useStaminaPotions",
		name = "Use stamina potions",
		description = "Drink a banked or carried stamina potion before loading logs when energy is at most 80% and stamina is inactive. Continue normally if unavailable.",
		position = 7
	)
	default boolean useStaminaPotions()
	{
		return true;
	}

	@Range(min = 1, max = 10)
	@ConfigItem(
		keyName = "offerWaitMinutes",
		name = "Offer wait (minutes)",
		description = "Minutes to leave a live Grand Exchange offer before cancelling it to reprice. Unfilled offers then move 5%.",
		position = 8
	)
	default int offerWaitMinutes()
	{
		return 3;
	}

	enum PriceDirection
	{
		ABOVE("Above"),
		BELOW("Below");

		private final String label;

		PriceDirection(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}
}
