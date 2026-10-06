package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.api.Item;
import net.runelite.api.ItemContainer;

final class HuntingSupplies
{
	static int count(ItemContainer container, int id)
	{
		if (container == null) return -1;
		int total = 0;
		for (Item item : container.getItems()) if (item != null && item.getId() == id) total += item.getQuantity();
		return total;
	}

	static int netCatchCount(ItemContainer inventory, RumourAssignment.Creature creature)
	{
		if (inventory == null) return -1;
		return count(inventory, creature.netCatchId())
			+ (creature.keptNetCatchId() > 0 ? count(inventory, creature.keptNetCatchId()) : 0);
	}

	static int netTrapLimit(int level)
	{
		return Math.min(5, 1 + Math.max(0, level) / 20);
	}

	static int pitTrapLimit(RumourAssignment.Creature creature, int level)
	{
		int sites = creature == RumourAssignment.Creature.SABRE_TOOTHED_KYATT ? 5
			: creature == RumourAssignment.Creature.SUNLIGHT_ANTELOPE ? 3 : 2;
		return Math.min(sites, netTrapLimit(level));
	}

	static String netSpaceBlocker(ItemContainer inventory, int traps)
	{
		if (inventory == null) return "Inventory is not available. Wait for game readiness before restarting.";
		int needed = trapSlots(inventory, null, RumourAssignment.Creature.RED_SALAMANDER, traps);
		if (freeSlots(inventory) < needed) return "Free " + (needed - freeSlots(inventory))
			+ " more inventory slots for net traps and catch outputs, then restart.";
		return null;
	}

	static String partBlocker(ItemContainer inventory, int expected)
	{
		if (inventory == null) return "Inventory is not available. Wait for game readiness before restarting.";
		int parts = 0;
		for (Item item : inventory.getItems())
		{
			if (item == null || !RumourAssignment.isRarePart(item.getId())) continue;
			if (item.getId() != expected) return "A rare part does not match the verified assignment. Keep it and confirm its assigning hunter before restarting.";
			parts += item.getQuantity();
		}
		return parts > 1 ? "Multiple rare parts are carried. Keep them and resolve the duplicate before restarting." : null;
	}

	static boolean knife(ItemContainer inventory)
	{
		return count(inventory, 946) > 0 || count(inventory, 31043) > 0;
	}

	static int freeSlots(ItemContainer inventory)
	{
		if (inventory == null) return -1;
		int slots = 28;
		for (Item item : inventory.getItems()) if (item != null && item.getId() >= 0) slots--;
		return slots;
	}

	static boolean bankBeforeTraps(int id, RumourAssignment.Creature creature)
	{
		return product(id)
			|| (creature == RumourAssignment.Creature.HERBIBOAR && (herbiboarHerb(id)
				|| id == 21562 || id == 21564 || id == 21566 || id == 21568
				|| id == 13226 || id == 24478 || id == 33135 || id == 33137))
			|| (creature != RumourAssignment.Creature.WYRMSCRAIG_GOAT && (id == 278 || id == 34016))
			|| (creature != RumourAssignment.Creature.RAZOR_BACKED_KEBBIT && id == 10150)
			|| (creature.butterflyId() > 0 && id == 10012)
			|| (creature.butterflyId() == 0 && (id == 10010 || id == 11259))
			|| (creature.netCatchId() == 0 && (id == 954 || id == 303))
			|| (creature.deadfallCatchId() == 0 && !pit(creature) && (id == 1511 || id == 1521 || id == 1519))
			|| (!pit(creature) && (id == 10029 || id == 385))
			|| (creature.deadfallCatchId() == 0 && !pit(creature) && (id == 946 || id == 31043))
			|| (creature.boxCatchId() == 0 && id == 10008)
			|| (creature != RumourAssignment.Creature.TROPICAL_WAGTAIL && id == 10006);
	}

	static String boxDeficit(ItemContainer inventory, ItemContainer bank, int traps, RumourAssignment.Creature creature)
	{
		if (inventory == null || bank == null) return "Bank supplies are not available yet. Reopen the bank and restart.";
		boolean bird = creature == RumourAssignment.Creature.TROPICAL_WAGTAIL;
		int tool = bird ? 10006 : 10008;
		int missing = traps - count(inventory, tool) - count(bank, tool);
		if (missing > 0) return "Missing " + missing + (bird ? " Bird snare" : " Box trap") + (missing == 1 ? "" : "s")
			+ " in inventory and bank. Add the supplies, then restart.";
		int needed = trapSlots(inventory, bank, creature, traps);
		return freeSlots(inventory) < needed ? "Free " + (needed - freeSlots(inventory))
			+ (bird ? " more inventory slots for Bird snares, bones, meat, feathers and the rare part, then restart."
				: " more inventory slots for Box traps, the catch stack and rare part, then restart.") : null;
	}

	static int trapSlots(ItemContainer inventory, ItemContainer bank, RumourAssignment.Creature creature, int traps)
	{
		if (inventory == null) return 28;
		if (pit(creature)) return pitSlots(creature, inventory, traps);
		if (creature.butterflyId() > 0) return 2;
		if (creature == RumourAssignment.Creature.HERBIBOAR) return 10;
		if (creature == RumourAssignment.Creature.RAZOR_BACKED_KEBBIT || creature == RumourAssignment.Creature.WYRMSCRAIG_GOAT) return 6;
		if (creature == RumourAssignment.Creature.TROPICAL_WAGTAIL)
			return 2 * Math.max(0, traps) + (count(inventory, 10006) == 0 ? 1 : 0)
				+ (count(inventory, 10087) == 0 ? 1 : 0) + (count(inventory, creature.rarePartId()) == 0 ? 1 : 0);
		if (creature.netCatchId() > 0)
			return Math.max(0, traps - count(inventory, 954)) + Math.max(0, traps - count(inventory, 303)) + 2;
		if (creature.boxCatchId() > 0)
			return (count(inventory, 10008) == 0 ? 1 : 0)
				+ (count(inventory, creature.boxCatchId()) == 0 ? 1 : 0)
				+ (count(inventory, creature.rarePartId()) == 0 ? 1 : 0);
		return (knife(inventory) ? 0 : 1) + Math.max(0, 10 - count(inventory, logId(inventory, bank))) + 3;
	}

	static int deadfallOutputSlots(RumourAssignment.Creature creature)
	{
		return creature == RumourAssignment.Creature.SABRE_TOOTHED_KEBBIT || creature == RumourAssignment.Creature.PRICKLY_KEBBIT ? 2 : 3;
	}

	static int deadfallLogTarget(ItemContainer inventory, ItemContainer bank, RumourAssignment.Creature creature, int id)
	{
		if (inventory == null || bank == null || id < 0) return 0;
		int space = freeSlots(inventory) + count(inventory, id) - (knife(inventory) ? 0 : 1) - 1;
		return Math.max(0, Math.min(10, Math.min(usableLogs(inventory, bank, id), space / (deadfallOutputSlots(creature) + 1))));
	}

	static boolean pit(RumourAssignment.Creature creature)
	{
		return creature == RumourAssignment.Creature.HORNED_GRAAHK || creature == RumourAssignment.Creature.SPINED_LARUPIA || creature == RumourAssignment.Creature.SABRE_TOOTHED_KYATT || creature == RumourAssignment.Creature.SUNLIGHT_ANTELOPE;
	}

	static int logs(ItemContainer inventory)
	{
		return inventory == null ? -1 : count(inventory, 1511) + count(inventory, 1521) + count(inventory, 1519);
	}

	static int pitOutputSlots(RumourAssignment.Creature creature, ItemContainer inventory)
	{
		return creature == RumourAssignment.Creature.SUNLIGHT_ANTELOPE
			? 4 + (count(inventory, 28924) == 0 ? 1 : 0) + (count(inventory, 29236) == 0 ? 1 : 0) : 4;
	}

	static int pitSlots(RumourAssignment.Creature creature, ItemContainer inventory, int traps)
	{
		if (inventory == null) return 28;
		return (knife(inventory) ? 0 : 1) + Math.max(0, 1 - count(inventory, 10029))
			+ Math.max(0, 4 - count(inventory, 385)) + Math.max(0, traps - logs(inventory)) + pitOutputSlots(creature, inventory);
	}

	static String pitDeficit(RumourAssignment.Creature creature, ItemContainer inventory, ItemContainer bank, int traps)
	{
		if (inventory == null || bank == null) return "Bank supplies are not available yet. Reopen the bank and restart.";
		if (!knife(inventory) && !knife(bank)) return "Missing 1 Knife in inventory and bank. Add a Knife, then restart.";
		for (int[] supply : new int[][]{{10029, 1}, {385, 4}})
		{
			int missing = supply[1] - count(inventory, supply[0]) - count(bank, supply[0]);
			if (missing > 0) return "Missing " + missing + (supply[0] == 10029 ? " Teasing stick" : " Shark")
				+ (missing == 1 ? "" : "s") + " in inventory and bank. Add the supplies, then restart.";
		}
		int space = pitSlots(creature, inventory, traps) - freeSlots(inventory);
		return space > 0 ? "Free " + space + " more inventory slots for pit supplies and catch outputs, then restart." : null;
	}

	static boolean herbiboarHerb(int id)
	{
		return (id >= 199 && id <= 219 && id % 2 == 1) || id == 2485 || id == 3051;
	}

	static int herbiboarSlots(ItemContainer inventory, ItemContainer equipment)
	{
		if (inventory == null || equipment == null) return 28;
		int herbs = count(inventory, 7409) + count(equipment, 7409) > 0 ? 4 : 3;
		return herbs * (count(equipment, 31241) > 0 ? 2 : 1) + 2;
	}

	static int herbiboarProducts(ItemContainer inventory)
	{
		if (inventory == null) return -1;
		int total = 0;
		for (Item item : inventory.getItems())
			if (item != null && (herbiboarHerb(item.getId()) || item.getId() == 29239)) total += item.getQuantity();
		return total;
	}

	static boolean product(int id)
	{
		return id == 28834 || id == 9735 || id == 34017 || id == 10107 || id == 9986 || id == 10097 || id == 10099 || id == 29119 || id == 10095 || id == 10093 || id == 29122 || id == 29163 || id == 29110 || id == 10105 || id == 10129 || id == 29101 || id == 10113 || id == 29104 || id == 526 || id == 10109 || id == 10115 || id == 10125 || id == 10127 || id == 29107 || id == 29242 || id == 29244 || id == 29246 || id == 29248 || id == 10033 || id == 10034
			|| id == 10087 || id == 9978 || id == 29166 || id == 532 || id == 10101 || id == 10103 || id == 29125
			|| id == 29116 || id == 29168 || id == 29177 || id == 28924;
	}

	static int logId(ItemContainer inventory, ItemContainer bank)
	{
		if (inventory == null || bank == null) return -1;
		for (int id : new int[]{1511, 1521, 1519})
			if (count(inventory, id) + count(bank, id) > 0) return id;
		return -1;
	}

	static int usableLogs(ItemContainer inventory, ItemContainer bank, int id)
	{
		return count(inventory, id) + count(bank, id);
	}

	static String deficit(ItemContainer inventory, ItemContainer bank)
	{
		if (inventory == null || bank == null) return "Bank supplies are not available yet. Reopen the bank and restart.";
		if (!knife(inventory) && !knife(bank)) return "Missing 1 Knife in inventory and bank. Add a Knife, then restart.";
		if (logId(inventory, bank) < 0)
			return "Missing 1 usable log in inventory and bank. Add Logs, Oak logs or Willow logs, then restart.";
		return null;
	}
}
