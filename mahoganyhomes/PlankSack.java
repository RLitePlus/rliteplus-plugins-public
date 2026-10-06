package net.runelite.client.plugins.microbot.mahoganyhomes;

import java.util.Arrays;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

final class PlankSack
{
	static final int CAPACITY = 28;
	private static final int[] PLANK_IDS =
	{
		ItemID.WOODPLANK,
		ItemID.PLANK_OAK,
		ItemID.PLANK_TEAK,
		ItemID.PLANK_MAHOGANY,
		ItemID.PLANK_CAMPHOR,
		ItemID.PLANK_IRONWOOD,
		ItemID.PLANK_ROSEWOOD
	};
	private static final int[] CONTENT_VARBITS =
	{
		VarbitID.PLANK_SACK_PLAIN,
		VarbitID.PLANK_SACK_OAK,
		VarbitID.PLANK_SACK_TEAK,
		VarbitID.PLANK_SACK_MAHOGANY,
		VarbitID.PLANK_SACK_CAMPHOR,
		VarbitID.PLANK_SACK_IRONWOOD,
		VarbitID.PLANK_SACK_ROSEWOOD
	};

	private PlankSack()
	{
	}

	static boolean isCarried()
	{
		return Rs2Inventory.contains(ItemID.PLANK_SACK);
	}

	static int count(MahoganyHomesData tier)
	{
		return isCarried() ? Microbot.getVarbitValue(varbitFor(tier)) : 0;
	}

	static int total()
	{
		return isCarried()
			? Arrays.stream(CONTENT_VARBITS).map(Microbot::getVarbitValue).sum()
			: 0;
	}

	static int available(MahoganyHomesData tier)
	{
		return available(Rs2Inventory.count(tier.getPlankId()), count(tier), true);
	}

	static int available(int loose, int stored, boolean carried)
	{
		return loose + (carried ? stored : 0);
	}

	static int freeCapacity()
	{
		return Math.max(0, CAPACITY - total());
	}

	static boolean hasWrongContents(MahoganyHomesData tier)
	{
		return total() > count(tier);
	}

	static int firstWrongInventoryPlank(MahoganyHomesData tier)
	{
		return Arrays.stream(PLANK_IDS)
			.filter(id -> id != tier.getPlankId() && Rs2Inventory.contains(id))
			.findFirst()
			.orElse(-1);
	}

	static int fillWithdrawal(int stored, int loose, int banked)
	{
		return Math.min(Math.max(0, CAPACITY - stored - loose), Math.max(0, banked));
	}

	static boolean fill()
	{
		return Rs2Inventory.interact(ItemID.PLANK_SACK, "Fill");
	}

	static boolean empty()
	{
		return Rs2Inventory.interact(ItemID.PLANK_SACK, "Empty");
	}

	static int varbitFor(MahoganyHomesData tier)
	{
		switch (tier)
		{
			case BEGINNER:
				return VarbitID.PLANK_SACK_PLAIN;
			case NOVICE:
				return VarbitID.PLANK_SACK_OAK;
			case ADEPT:
				return VarbitID.PLANK_SACK_TEAK;
			case EXPERT:
				return VarbitID.PLANK_SACK_MAHOGANY;
			default:
				throw new IllegalArgumentException("Unsupported contract tier: " + tier);
		}
	}
}
