package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;

final class FalconryPreparation
{
	static final EquipmentInventorySlot[] CLEAR_SLOTS = {
		EquipmentInventorySlot.WEAPON, EquipmentInventorySlot.SHIELD, EquipmentInventorySlot.GLOVES
	};

	static String blocker(ItemContainer inventory, ItemContainer equipment)
	{
		if (inventory == null || equipment == null) return "Inventory or equipment is unavailable. Wait for gameplay and restart.";
		int fee = feeNeeded(inventory, equipment);
		if (fee > 0) return "Withdraw " + fee + " more coins for the 500-coin falcon rental, then restart.";
		int missing = requiredSlots(equipment) - HuntingSupplies.freeSlots(inventory);
		return missing > 0 ? "Free " + missing
			+ " more inventory slots for removed equipment and the falconry catch, then restart." : null;
	}

	static int feeNeeded(ItemContainer inventory, ItemContainer equipment)
	{
		if (inventory == null || equipment == null) return -1;
		Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		boolean rented = weapon != null && (weapon.getId() == 10023 || weapon.getId() == 10024);
		return rented ? 0 : Math.max(0, 500 - HuntingSupplies.count(inventory, 995));
	}

	static int requiredSlots(ItemContainer equipment)
	{
		if (equipment == null) return 28;
		int removals = 0;
		for (EquipmentInventorySlot slot : CLEAR_SLOTS)
		{
			Item item = equipment.getItem(slot.getSlotIdx());
			if (item != null && item.getId() >= 0 && item.getId() != 10023 && item.getId() != 10024) removals++;
		}
		return 4 + removals;
	}

	static String bankBlocker(ItemContainer inventory, ItemContainer equipment, ItemContainer bank)
	{
		if (inventory == null || equipment == null || bank == null)
			return "Falconry supplies are not available yet. Reopen the bank and restart.";
		int fee = feeNeeded(inventory, equipment);
		int missing = fee - HuntingSupplies.count(bank, 995);
		if (missing > 0) return "Missing " + missing + " coins in inventory and bank for the falcon rental. Add the coins, then restart.";
		int required = requiredSlots(equipment) + (fee > 0 && HuntingSupplies.count(inventory, 995) == 0 ? 1 : 0);
		int space = required - HuntingSupplies.freeSlots(inventory);
		return space > 0 ? "Free " + space + " more inventory slots for the rental fee, removed equipment and falconry catch, then restart." : null;
	}
}
