package net.runelite.client.plugins.microbot.mahoganyhomes;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

final class AmysSaw
{
	private static final int[] PREFERRED_SAWS =
	{
		ItemID.WEARABLE_SAW_OFFHAND,
		ItemID.WEARABLE_SAW,
		ItemID.EYEGLO_CRYSTAL_SAW,
		ItemID.POH_SAW
	};

	private AmysSaw()
	{
	}

	static int[] preferredSawIds()
	{
		return PREFERRED_SAWS.clone();
	}

	static boolean isEquipped()
	{
		return Rs2Equipment.isWearing(ItemID.WEARABLE_SAW, ItemID.WEARABLE_SAW_OFFHAND);
	}

	static int inventoryItemId()
	{
		if (Rs2Inventory.contains(ItemID.WEARABLE_SAW_OFFHAND))
		{
			return ItemID.WEARABLE_SAW_OFFHAND;
		}
		return Rs2Inventory.contains(ItemID.WEARABLE_SAW) ? ItemID.WEARABLE_SAW : -1;
	}

	static boolean prepare(int itemId)
	{
		return itemId == ItemID.WEARABLE_SAW_OFFHAND
			? Rs2Inventory.wield(itemId) : Rs2Inventory.interact(itemId, "Swap");
	}
}
