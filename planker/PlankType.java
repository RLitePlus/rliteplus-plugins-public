package net.runelite.client.plugins.microbot.planker;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.planker.PlankerConfig.PriceDirection;

public enum PlankType
{
	REGULAR(ItemID.LOGS, ItemID.WOODPLANK, "Logs", "Plank", 100),
	OAK(ItemID.OAK_LOGS, ItemID.PLANK_OAK, "Oak logs", "Oak plank", 250),
	TEAK(ItemID.TEAK_LOGS, ItemID.PLANK_TEAK, "Teak logs", "Teak plank", 500),
	MAHOGANY(ItemID.MAHOGANY_LOGS, ItemID.PLANK_MAHOGANY, "Mahogany logs", "Mahogany plank", 1500),
	CAMPHOR(ItemID.CAMPHOR_LOGS, ItemID.PLANK_CAMPHOR, "Camphor logs", "Camphor plank", 2500),
	IRONWOOD(ItemID.IRONWOOD_LOGS, ItemID.PLANK_IRONWOOD, "Ironwood logs", "Ironwood plank", 5000),
	ROSEWOOD(ItemID.ROSEWOOD_LOGS, ItemID.PLANK_ROSEWOOD, "Rosewood logs", "Rosewood plank", 7500);

	private final int logId;
	private final int plankId;
	private final String logName;
	private final String plankName;
	private final int fee;

	PlankType(int logId, int plankId, String logName, String plankName, int fee)
	{
		this.logId = logId;
		this.plankId = plankId;
		this.logName = logName;
		this.plankName = plankName;
		this.fee = fee;
	}

	public int getLogId()
	{
		return logId;
	}

	public int getPlankId()
	{
		return plankId;
	}

	public String getLogName()
	{
		return logName;
	}

	public String getPlankName()
	{
		return plankName;
	}

	public int getFee()
	{
		return fee;
	}

	public static int price(PriceDirection direction, int guide, int percent)
	{
		if (direction == null || guide <= 0 || percent < 0)
		{
			throw new IllegalArgumentException("Choose a price direction, a positive guide price and a non-negative percentage.");
		}
		long adjusted = (long) guide * (direction == PriceDirection.ABOVE ? 100L + percent : 100L - percent);
		long price = direction == PriceDirection.ABOVE ? (adjusted + 99) / 100 : adjusted / 100;
		if (price < 1 || price > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException("The adjusted price must be between 1 and 2,147,483,647 coins.");
		}
		return (int) price;
	}

	public static int affordablePurchase(int quantity, long coins, int price, int fee)
	{
		if (quantity < 0 || coins < 0 || price <= 0 || fee <= 0)
		{
			throw new IllegalArgumentException("Purchase quantities and coins must be non-negative; prices and fees must be positive.");
		}
		return (int) Math.min(quantity, coins / ((long) price + fee));
	}

	public static int loadSize(int slots, int logs, long coins, int fee)
	{
		if (slots < 0 || logs < 0 || coins < 0 || fee <= 0)
		{
			throw new IllegalArgumentException("Available slots, logs and coins must be non-negative; the sawmill fee must be positive.");
		}
		return (int) Math.min(Math.min(slots, logs), coins / fee);
	}

	@Override
	public String toString()
	{
		return plankName;
	}
}
