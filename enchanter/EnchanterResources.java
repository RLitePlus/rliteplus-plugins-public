package net.runelite.client.plugins.microbot.enchanter;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.magic.Runes;

final class EnchanterResources
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EnchanterResources.class);
	static final int AETHER_RUNE_POUCH_VALUE = 23;

	private EnchanterResources()
	{
	}

	enum Source
	{
		EQUIPPED,
		INVENTORY,
		BANK
	}

	enum Staff
	{
		AIR(ItemID.STAFF_OF_AIR, 0, 0, false, Runes.AIR),
		WATER(ItemID.STAFF_OF_WATER, 0, 0, false, Runes.WATER),
		EARTH(ItemID.STAFF_OF_EARTH, 0, 0, false, Runes.EARTH),
		FIRE(ItemID.STAFF_OF_FIRE, 0, 0, false, Runes.FIRE),
		AIR_BATTLE(ItemID.AIR_BATTLESTAFF, 30, 30, true, Runes.AIR),
		WATER_BATTLE(ItemID.WATER_BATTLESTAFF, 30, 30, true, Runes.WATER),
		EARTH_BATTLE(ItemID.EARTH_BATTLESTAFF, 30, 30, true, Runes.EARTH),
		FIRE_BATTLE(ItemID.FIRE_BATTLESTAFF, 30, 30, true, Runes.FIRE),
		DUST_BATTLE(ItemID.DUST_BATTLESTAFF, 30, 30, true, Runes.AIR, Runes.EARTH),
		LAVA_BATTLE(ItemID.LAVA_BATTLESTAFF, 30, 30, true, Runes.FIRE, Runes.EARTH),
		MIST_BATTLE(ItemID.MIST_BATTLESTAFF, 30, 30, true, Runes.AIR, Runes.WATER),
		MUD_BATTLE(ItemID.MUD_BATTLESTAFF, 30, 30, true, Runes.WATER, Runes.EARTH),
		SMOKE_BATTLE(ItemID.SMOKE_BATTLESTAFF, 30, 30, true, Runes.AIR, Runes.FIRE),
		STEAM_BATTLE(ItemID.STEAM_BATTLESTAFF, 30, 30, true, Runes.WATER, Runes.FIRE),
		MYSTIC_AIR(ItemID.MYSTIC_AIR_STAFF, 40, 40, true, Runes.AIR),
		MYSTIC_WATER(ItemID.MYSTIC_WATER_STAFF, 40, 40, true, Runes.WATER),
		MYSTIC_EARTH(ItemID.MYSTIC_EARTH_STAFF, 40, 40, true, Runes.EARTH),
		MYSTIC_FIRE(ItemID.MYSTIC_FIRE_STAFF, 40, 40, true, Runes.FIRE),
		MYSTIC_DUST(ItemID.MYSTIC_DUST_BATTLESTAFF, 40, 40, true, Runes.AIR, Runes.EARTH),
		MYSTIC_LAVA(ItemID.MYSTIC_LAVA_STAFF, 40, 40, true, Runes.FIRE, Runes.EARTH),
		MYSTIC_MIST(ItemID.MYSTIC_MIST_BATTLESTAFF, 40, 40, true, Runes.AIR, Runes.WATER),
		MYSTIC_MUD(ItemID.MYSTIC_MUD_STAFF, 40, 40, true, Runes.WATER, Runes.EARTH),
		MYSTIC_SMOKE(ItemID.MYSTIC_SMOKE_BATTLESTAFF, 40, 40, true, Runes.AIR, Runes.FIRE),
		MYSTIC_STEAM(ItemID.MYSTIC_STEAM_BATTLESTAFF, 40, 40, true, Runes.WATER, Runes.FIRE),
		TWINFLAME(ItemID.TWINFLAME_STAFF, 0, 60, true, Runes.FIRE, Runes.WATER),
		KODAI(ItemID.KODAI_WAND, 0, 80, true, Runes.WATER),
		STEAM_OR(ItemID.STEAM_BATTLESTAFF_PRETTY, 30, 30, true, Runes.WATER, Runes.FIRE),
		MYSTIC_STEAM_OR(ItemID.MYSTIC_STEAM_BATTLESTAFF_PRETTY, 40, 40, true,
			Runes.WATER, Runes.FIRE),
		LAVA_OR(ItemID.LAVA_BATTLESTAFF_PRETTY, 30, 30, true, Runes.EARTH, Runes.FIRE),
		MYSTIC_LAVA_OR(ItemID.MYSTIC_LAVA_STAFF_PRETTY, 40, 40, true,
			Runes.EARTH, Runes.FIRE);

		private final int itemId;
		private final int attackLevel;
		private final int magicLevel;
		private final boolean membersOnly;
		private final List<Runes> runes;

		Staff(int itemId, int attackLevel, int magicLevel, boolean membersOnly, Runes... runes)
		{
			this.itemId = itemId;
			this.attackLevel = attackLevel;
			this.magicLevel = magicLevel;
			this.membersOnly = membersOnly;
			this.runes = Collections.unmodifiableList(Arrays.asList(runes));
		}

		int getItemId()
		{
			return itemId;
		}

		List<Runes> getRunes()
		{
			return runes;
		}

		boolean canWield(boolean membersWorld, int attack, int magic)
		{
			return (!membersOnly || membersWorld) && attack >= attackLevel && magic >= magicLevel;
		}
	}

	static final class StaffChoice
	{
		private final Staff staff;
		private final Source source;
		private final int savedRunes;

		private StaffChoice(Staff staff, Source source, int savedRunes)
		{
			this.staff = staff;
			this.source = source;
			this.savedRunes = savedRunes;
		}

		Staff getStaff()
		{
			return staff;
		}

		Source getSource()
		{
			return source;
		}

		int getSavedRunes()
		{
			return savedRunes;
		}
	}

	static final class BatchPlan
	{
		private final int inputQuantity;
		private final int effects;
		private final Map<Integer, Integer> withdrawals;

		private BatchPlan(int inputQuantity, int effects, Map<Integer, Integer> withdrawals)
		{
			this.inputQuantity = inputQuantity;
			this.effects = effects;
			this.withdrawals = Collections.unmodifiableMap(withdrawals);
		}

		int getInputQuantity()
		{
			return inputQuantity;
		}

		int getEffects()
		{
			return effects;
		}

		Map<Integer, Integer> getWithdrawals()
		{
			return withdrawals;
		}
	}

	static StaffChoice selectStaff(Enchantment target, boolean membersWorld, int attack, int magic,
		int equippedWeaponId, IntUnaryOperator inventoryCount, IntUnaryOperator bankCount,
		Set<Runes> tomeRunes)
	{
		StaffChoice best = null;
		for (Staff staff : Staff.values())
		{
			if (!staff.canWield(membersWorld, attack, magic))
			{
				continue;
			}
			Source source = source(staff.itemId, equippedWeaponId, inventoryCount, bankCount);
			int saved = source == null ? 0 : savedRunes(target, staff, tomeRunes);
			if (saved == 0)
			{
				continue;
			}
			StaffChoice candidate = new StaffChoice(staff, source, saved);
			if (best == null || candidate.savedRunes > best.savedRunes
				|| candidate.savedRunes == best.savedRunes
				&& candidate.source.ordinal() < best.source.ordinal())
			{
				best = candidate;
			}
		}
		return best;
	}

	static Staff staffForItem(int itemId)
	{
		return Arrays.stream(Staff.values()).filter(staff -> staff.itemId == itemId)
			.findFirst().orElse(null);
	}

	static Map<Runes, Integer> effectiveRunes(Map<Runes, Integer> sharedRunes,
		int aetherInventory, int aetherPouch, int equippedWeaponId)
	{
		EnumMap<Runes, Integer> effective = new EnumMap<>(Runes.class);
		effective.putAll(sharedRunes);
		add(effective, Runes.FIRE, effective.getOrDefault(Runes.SUNFIRE, 0));
		int aether = safeAdd(aetherInventory, aetherPouch);
		add(effective, Runes.COSMIC, aether);
		add(effective, Runes.SOUL, aether);
		Staff staff = staffForItem(equippedWeaponId);
		if (staff != null)
		{
			staff.runes.forEach(rune -> effective.put(rune, Integer.MAX_VALUE));
		}
		return effective;
	}

	static Map<Integer, Integer> planRuneWithdrawals(Enchantment target, int effects,
		Map<Runes, Integer> carried, IntUnaryOperator bankCount)
	{
		Map<Runes, Integer> required;
		try
		{
			required = target.runesForEffects(effects);
		}
		catch (ArithmeticException exception)
		{
			log.warn("Rune withdrawal quantity overflow", exception);
			return null;
		}
		EnumMap<Runes, Integer> available = new EnumMap<>(Runes.class);
		available.putAll(carried);
		Map<Integer, Integer> withdrawals = new LinkedHashMap<>();

		for (Runes rune : required.keySet())
		{
			take(rune.getItemId(), EnumSet.of(rune), shortage(required, available, rune),
				bankCount, withdrawals, available);
		}
		for (Runes rune : required.keySet())
		{
			for (Runes combination : Runes.getComboRunes(rune))
			{
				take(combination.getItemId(), EnumSet.copyOf(Arrays.asList(combination.getBaseRunes())),
					shortage(required, available, rune), bankCount, withdrawals, available);
			}
			if (rune == Runes.FIRE)
			{
				take(ItemID.SUNFIRERUNE, EnumSet.of(Runes.FIRE),
					shortage(required, available, rune), bankCount, withdrawals, available);
			}
			if (rune == Runes.COSMIC || rune == Runes.SOUL)
			{
				take(ItemID.AETHERRUNE, EnumSet.of(Runes.COSMIC, Runes.SOUL),
					shortage(required, available, rune), bankCount, withdrawals, available);
			}
		}
		return required.keySet().stream().allMatch(rune -> shortage(required, available, rune) == 0)
			? withdrawals : null;
	}

	static BatchPlan planBatch(Enchantment target, int carriedInput, int bankInput, int emptySlots,
		Map<Runes, Integer> carriedRunes, IntUnaryOperator inventoryCount,
		IntUnaryOperator bankCount)
	{
		long totalLong = (long) carriedInput + bankInput;
		if (carriedInput < 0 || bankInput < 0 || emptySlots < 0 || totalLong <= 0)
		{
			return null;
		}
		int total = (int) Math.min(Integer.MAX_VALUE, totalLong);
		if (target.isJewellery())
		{
			for (int input = Math.min(28, total); input >= Math.max(1, carriedInput); input--)
			{
				BatchPlan plan = candidate(target, input, carriedInput, emptySlots,
					carriedRunes, inventoryCount, bankCount);
				if (plan != null)
				{
					return plan;
				}
			}
			return null;
		}

		int low = Math.max(1, target.effectsForInput(carriedInput));
		int high = target.effectsForInput(total);
		BatchPlan best = null;
		while (low <= high)
		{
			int effects = low + (high - low) / 2;
			int input = (int) Math.min(total, (long) effects * 10);
			BatchPlan plan = input < carriedInput ? null : candidate(target, input, carriedInput,
				emptySlots, carriedRunes, inventoryCount, bankCount);
			if (plan == null)
			{
				high = effects - 1;
			}
			else
			{
				best = plan;
				low = effects + 1;
			}
		}
		return best;
	}

	private static BatchPlan candidate(Enchantment target, int input, int carriedInput,
		int emptySlots, Map<Runes, Integer> carriedRunes, IntUnaryOperator inventoryCount,
		IntUnaryOperator bankCount)
	{
		int inputWithdrawal = input - carriedInput;
		if (inputWithdrawal < 0 || inputWithdrawal > bankCount.applyAsInt(target.getInputId()))
		{
			return null;
		}
		int effects = target.effectsForInput(input);
		Map<Integer, Integer> runeWithdrawals = planRuneWithdrawals(target, effects,
			carriedRunes, bankCount);
		if (runeWithdrawals == null)
		{
			return null;
		}
		LinkedHashMap<Integer, Integer> withdrawals = new LinkedHashMap<>(runeWithdrawals);
		if (inputWithdrawal > 0)
		{
			withdrawals.put(target.getInputId(), inputWithdrawal);
		}
		int slots = (int) runeWithdrawals.keySet().stream()
			.filter(id -> inventoryCount.applyAsInt(id) == 0).count();
		if (target.isJewellery())
		{
			slots += inputWithdrawal;
		}
		else
		{
			if (inputWithdrawal > 0 && inventoryCount.applyAsInt(target.getInputId()) == 0)
			{
				slots++;
			}
			if (inventoryCount.applyAsInt(target.getOutputId()) == 0)
			{
				slots++;
			}
		}
		return slots <= emptySlots ? new BatchPlan(input, effects, withdrawals) : null;
	}

	private static Source source(int itemId, int equippedWeaponId,
		IntUnaryOperator inventoryCount, IntUnaryOperator bankCount)
	{
		if (itemId == equippedWeaponId)
		{
			return Source.EQUIPPED;
		}
		if (inventoryCount.applyAsInt(itemId) > 0)
		{
			return Source.INVENTORY;
		}
		return bankCount.applyAsInt(itemId) > 0 ? Source.BANK : null;
	}

	private static int savedRunes(Enchantment target, Staff staff, Set<Runes> tomeRunes)
	{
		return staff.runes.stream()
			.filter(rune -> !tomeRunes.contains(rune))
			.mapToInt(rune -> target.getRunes().getOrDefault(rune, 0))
			.sum();
	}

	private static void take(int itemId, Set<Runes> provided, int needed,
		IntUnaryOperator bankCount, Map<Integer, Integer> withdrawals,
		Map<Runes, Integer> available)
	{
		if (needed <= 0)
		{
			return;
		}
		int remaining = Math.max(0, bankCount.applyAsInt(itemId)
			- withdrawals.getOrDefault(itemId, 0));
		int quantity = remaining;
		if (quantity == 0)
		{
			return;
		}
		withdrawals.merge(itemId, quantity, EnchanterResources::safeAdd);
		provided.forEach(rune -> add(available, rune, quantity));
	}

	private static int shortage(Map<Runes, Integer> required,
		Map<Runes, Integer> available, Runes rune)
	{
		return Math.max(0, required.getOrDefault(rune, 0) - available.getOrDefault(rune, 0));
	}

	private static void add(Map<Runes, Integer> runes, Runes rune, int quantity)
	{
		if (quantity > 0)
		{
			runes.merge(rune, quantity, EnchanterResources::safeAdd);
		}
	}

	static int safeAdd(int left, int right)
	{
		long total = (long) left + right;
		return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
	}
}
