package net.runelite.client.plugins.microbot.huntersrumours;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spells;
import net.runelite.client.plugins.microbot.util.magic.Spell;

final class HuntingTravel
{
	static final List<Spell> SPELLS = List.of(Rs2Spells.CIVITAS_ILLA_FORTIS_TELEPORT,
		Rs2Spells.FALADOR_TELEPORT, Rs2Spells.ARDOUGNE_TELEPORT, Rs2Spells.CAMELOT_TELEPORT,
		Rs2Spells.KOUREND_CASTLE_TELEPORT, Rs2Spells.VARROCK_TELEPORT);
	private static final int[] TABLETS = {ItemID.POH_TABLET_FORTISTELEPORT,
		ItemID.POH_TABLET_FALADORTELEPORT, ItemID.POH_TABLET_ARDOUGNETELEPORT,
		ItemID.POH_TABLET_CAMELOTTELEPORT, ItemID.POH_TABLET_KOURENDTELEPORT,
		ItemID.POH_TABLET_VARROCKTELEPORT};
	private static final int[] WHISTLES = {ItemID.HG_QUETZALWHISTLE_PERFECTED_INFINITE,
		ItemID.HG_QUETZALWHISTLE_PERFECTED, ItemID.HG_QUETZALWHISTLE_ENHANCED,
		ItemID.HG_QUETZALWHISTLE_BASIC};
	private static final int[] STAFFS = {772, 9084};

	private HuntingTravel()
	{
	}

	static boolean managed(int id)
	{
		return Arrays.stream(TABLETS).anyMatch(item -> item == id)
			|| Arrays.stream(WHISTLES).anyMatch(item -> item == id)
			|| Arrays.stream(STAFFS).anyMatch(item -> item == id)
			|| id == ItemID.LAWRUNE || id == ItemID.AIRRUNE || id == ItemID.WATERRUNE
			|| id == ItemID.EARTHRUNE || id == ItemID.FIRERUNE;
	}

	static Map<Integer, Integer> plan(ItemContainer inventory, ItemContainer bank,
		ItemContainer equipment, int reservedSlots, boolean needsStaff,
		Predicate<Spell> unlocked, Predicate<Spell> castable)
	{
		if (inventory == null || bank == null || equipment == null) return null;
		int slots = HuntingSupplies.freeSlots(inventory) - reservedSlots;
		for (Item item : inventory.getItems()) if (item != null && managed(item.getId())) slots++;
		Map<Integer, Integer> result = new LinkedHashMap<>();
		addTool(result, WHISTLES, inventory, bank, equipment, slots);
		if (needsStaff) addTool(result, STAFFS, inventory, bank, equipment, slots);
		for (int index = 0; index < SPELLS.size(); index++)
		{
			Spell spell = SPELLS.get(index);
			if (!unlocked.test(spell)) continue;
			Map<Integer, Integer> runes = Spell.convertRequiredRunes(spell.getRequiredRunes(5));
			if (castable.test(spell) && runes.entrySet().stream()
				.allMatch(entry -> owned(inventory, bank, entry.getKey()) >= entry.getValue()))
			{
				Map<Integer, Integer> candidate = new LinkedHashMap<>(result);
				for (Map.Entry<Integer, Integer> rune : runes.entrySet())
					candidate.merge(rune.getKey(), stock(inventory, bank, rune.getKey(), rune.getValue(), rune.getValue() * 5), Math::max);
				if (candidate.size() <= slots)
				{
					result = candidate;
					continue;
				}
			}
			int tablet = TABLETS[index];
			if (result.size() < slots && owned(inventory, bank, tablet) > 0)
				result.put(tablet, stock(inventory, bank, tablet, 2, 10));
		}
		return result;
	}

	private static int owned(ItemContainer inventory, ItemContainer bank, int id)
	{
		return HuntingSupplies.count(inventory, id) + HuntingSupplies.count(bank, id);
	}

	private static int stock(ItemContainer inventory, ItemContainer bank, int id, int minimum, int target)
	{
		int carried = HuntingSupplies.count(inventory, id);
		return carried >= minimum ? carried : Math.min(target, owned(inventory, bank, id));
	}

	private static void addTool(Map<Integer, Integer> result, int[] variants, ItemContainer inventory,
		ItemContainer bank, ItemContainer equipment, int slots)
	{
		if (Arrays.stream(variants).anyMatch(id -> HuntingSupplies.count(equipment, id) > 0)) return;
		if (result.size() >= slots) return;
		for (ItemContainer source : new ItemContainer[]{inventory, bank})
			for (int id : variants)
				if (HuntingSupplies.count(source, id) > 0)
				{
					result.put(id, 1);
					return;
				}
	}
}
