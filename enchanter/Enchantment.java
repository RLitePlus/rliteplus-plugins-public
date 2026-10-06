package net.runelite.client.plugins.microbot.enchanter;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.magic.Runes;

public enum Enchantment
{
	SAPPHIRE_RING("Sapphire ring", 1637, 2550, 1, true, false),
	SAPPHIRE_NECKLACE("Sapphire necklace", 1656, 3853, 1, true, false),
	SAPPHIRE_BRACELET("Sapphire bracelet", 11071, 11074, 1, true, false),
	SAPPHIRE_AMULET("Sapphire amulet", 1694, 1727, 1, false, false),
	OPAL_RING("Opal ring", 21081, 21126, 1, true, false),
	OPAL_NECKLACE("Opal necklace", 21090, 21143, 1, true, false),
	OPAL_BRACELET("Opal bracelet", 21117, 21177, 1, true, false),
	OPAL_AMULET("Opal amulet", 21108, 21160, 1, true, false),

	EMERALD_RING("Emerald ring", 1639, 2552, 2, true, false),
	EMERALD_NECKLACE("Emerald necklace", 1658, 5521, 2, true, false),
	EMERALD_BRACELET("Emerald bracelet", 11076, 11079, 2, true, false),
	EMERALD_AMULET("Emerald amulet", 1696, 1729, 2, false, false),
	JADE_RING("Jade ring", 21084, 21129, 2, true, false),
	JADE_NECKLACE("Jade necklace", 21093, 21146, 2, true, false),
	JADE_BRACELET("Jade bracelet", 21120, 21180, 2, true, false),
	JADE_AMULET("Jade amulet", 21111, 21163, 2, true, false),

	RUBY_RING("Ruby ring", 1641, 2568, 3, true, false),
	RUBY_NECKLACE("Ruby necklace", 1660, 11194, 3, true, true),
	RUBY_BRACELET("Ruby bracelet", 11085, 11088, 3, true, false),
	RUBY_AMULET("Ruby amulet", 1698, 1725, 3, false, false),
	TOPAZ_RING("Topaz ring", 21087, 21140, 3, true, false),
	TOPAZ_NECKLACE("Topaz necklace", 21096, 21157, 3, true, false),
	TOPAZ_BRACELET("Topaz bracelet", 21123, 21183, 3, true, false),
	TOPAZ_AMULET("Topaz amulet", 21114, 21166, 3, true, false),

	DIAMOND_RING("Diamond ring", 1643, 2570, 4, true, false),
	DIAMOND_NECKLACE("Diamond necklace", 1662, 11090, 4, true, false),
	DIAMOND_BRACELET("Diamond bracelet", 11092, 11095, 4, true, false),
	DIAMOND_AMULET("Diamond amulet", 1700, 1731, 4, false, false),

	DRAGONSTONE_RING("Dragonstone ring", 1645, 2572, 5, true, false),
	DRAGONSTONE_NECKLACE("Dragonstone necklace", 1664, 11113, 5, true, false),
	DRAGONSTONE_BRACELET("Dragonstone bracelet", 11115, 11126, 5, true, false),
	DRAGONSTONE_AMULET("Dragonstone amulet", 1702, 1704, 5, true, false),

	ONYX_RING("Onyx ring", 6575, 6583, 6, true, false),
	ONYX_NECKLACE("Onyx necklace", 6577, 11128, 6, true, false),
	ONYX_BRACELET("Onyx bracelet", 11130, 11133, 6, true, false),
	ONYX_AMULET("Onyx amulet", 6581, 6585, 6, true, false),

	ZENYTE_RING("Zenyte ring", 19538, 19550, 7, true, false),
	ZENYTE_NECKLACE("Zenyte necklace", 19535, 19547, 7, true, false),
	ZENYTE_BRACELET("Zenyte bracelet", 19492, 19544, 7, true, false),
	ZENYTE_AMULET("Zenyte amulet", 19541, 19553, 7, true, false),

	OPAL_BOLTS("Opal bolts", 879, 9236, 4,
		runes(Runes.AIR, 2, Runes.COSMIC, 1)),
	DRAGON_OPAL_BOLTS("Dragon opal bolts", 21955, 21932, 4,
		runes(Runes.AIR, 2, Runes.COSMIC, 1)),
	SAPPHIRE_BOLTS("Sapphire bolts", 9337, 9240, 7,
		runes(Runes.WATER, 1, Runes.COSMIC, 1, Runes.MIND, 1)),
	DRAGON_SAPPHIRE_BOLTS("Dragon sapphire bolts", 21963, 21940, 7,
		runes(Runes.WATER, 1, Runes.COSMIC, 1, Runes.MIND, 1)),
	JADE_BOLTS("Jade bolts", 9335, 9237, 14,
		runes(Runes.EARTH, 2, Runes.COSMIC, 1)),
	DRAGON_JADE_BOLTS("Dragon jade bolts", 21957, 21934, 14,
		runes(Runes.EARTH, 2, Runes.COSMIC, 1)),
	PEARL_BOLTS("Pearl bolts", 880, 9238, 24,
		runes(Runes.WATER, 2, Runes.COSMIC, 1)),
	DRAGON_PEARL_BOLTS("Dragon pearl bolts", 21959, 21936, 24,
		runes(Runes.WATER, 2, Runes.COSMIC, 1)),
	EMERALD_BOLTS("Emerald bolts", 9338, 9241, 27,
		runes(Runes.AIR, 3, Runes.COSMIC, 1, Runes.NATURE, 1)),
	DRAGON_EMERALD_BOLTS("Dragon emerald bolts", 21965, 21942, 27,
		runes(Runes.AIR, 3, Runes.COSMIC, 1, Runes.NATURE, 1)),
	TOPAZ_BOLTS("Topaz bolts", 9336, 9239, 29,
		runes(Runes.FIRE, 2, Runes.COSMIC, 1)),
	DRAGON_TOPAZ_BOLTS("Dragon topaz bolts", 21961, 21938, 29,
		runes(Runes.FIRE, 2, Runes.COSMIC, 1)),
	RUBY_BOLTS("Ruby bolts", 9339, 9242, 49,
		runes(Runes.FIRE, 5, Runes.BLOOD, 1, Runes.COSMIC, 1)),
	DRAGON_RUBY_BOLTS("Dragon ruby bolts", 21967, 21944, 49,
		runes(Runes.FIRE, 5, Runes.BLOOD, 1, Runes.COSMIC, 1)),
	DIAMOND_BOLTS("Diamond bolts", 9340, 9243, 57,
		runes(Runes.EARTH, 10, Runes.COSMIC, 1, Runes.LAW, 2)),
	DRAGON_DIAMOND_BOLTS("Dragon diamond bolts", 21969, 21946, 57,
		runes(Runes.EARTH, 10, Runes.COSMIC, 1, Runes.LAW, 2)),
	DRAGONSTONE_BOLTS("Dragonstone bolts", 9341, 9244, 68,
		runes(Runes.EARTH, 15, Runes.COSMIC, 1, Runes.SOUL, 1)),
	DRAGON_DRAGONSTONE_BOLTS("Dragon dragonstone bolts", 21971, 21948, 68,
		runes(Runes.EARTH, 15, Runes.COSMIC, 1, Runes.SOUL, 1)),
	ONYX_BOLTS("Onyx bolts", 9342, 9245, 87,
		runes(Runes.FIRE, 20, Runes.COSMIC, 1, Runes.DEATH, 1)),
	DRAGON_ONYX_BOLTS("Dragon onyx bolts", 21973, 21950, 87,
		runes(Runes.FIRE, 20, Runes.COSMIC, 1, Runes.DEATH, 1));

	public enum Type
	{
		JEWELLERY,
		BOLTS
	}

	private final String displayName;
	private final int inputId;
	private final int outputId;
	private final Type type;
	private final int tier;
	private final int magicLevel;
	private final int spellWidgetId;
	private final boolean membersOnly;
	private final boolean digsitePendantUnlock;
	private final Map<Runes, Integer> runes;
	private static final int[][] SPECIAL_JEWELLERY_INPUTS = {
		{},
		{1675, 21099, 7637, 7638},
		{1677, 21102, 6041, 22433},
		{1679, 21105, 24693},
		{1681, 33709},
		{1683},
		{6579},
		{19501}
	};
	private static final int[] MTA_INPUTS = {6898, 6899, 6900, 6901, 6903};

	Enchantment(String displayName, int inputId, int outputId, int tier,
		boolean membersOnly, boolean digsitePendantUnlock)
	{
		this.displayName = displayName;
		this.inputId = inputId;
		this.outputId = outputId;
		this.type = Type.JEWELLERY;
		this.tier = tier;
		this.magicLevel = jewelleryLevel(tier);
		this.spellWidgetId = jewelleryWidget(tier);
		this.membersOnly = membersOnly;
		this.digsitePendantUnlock = digsitePendantUnlock;
		this.runes = jewelleryRunes(tier);
	}

	Enchantment(String displayName, int inputId, int outputId, int magicLevel,
		Map<Runes, Integer> runes)
	{
		this.displayName = displayName;
		this.inputId = inputId;
		this.outputId = outputId;
		this.type = Type.BOLTS;
		this.tier = 0;
		this.magicLevel = magicLevel;
		this.spellWidgetId = InterfaceID.MagicSpellbook.XBOWS_ENCHANT;
		this.membersOnly = true;
		this.digsitePendantUnlock = false;
		this.runes = runes;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public int getInputId()
	{
		return inputId;
	}

	public int getOutputId()
	{
		return outputId;
	}

	public Type getType()
	{
		return type;
	}

	public int getTier()
	{
		return tier;
	}

	public int getMagicLevel()
	{
		return magicLevel;
	}

	public int getSpellWidgetId()
	{
		return spellWidgetId;
	}

	public boolean isMembersOnly()
	{
		return membersOnly;
	}

	public boolean requiresDigsitePendantUnlock()
	{
		return digsitePendantUnlock;
	}

	public Map<Runes, Integer> getRunes()
	{
		return runes;
	}

	boolean conflictsWithInput(int itemId)
	{
		if (!isJewellery() || itemId == inputId)
		{
			return false;
		}
		return Arrays.stream(values()).anyMatch(candidate -> candidate.isJewellery()
			&& candidate.tier == tier && candidate.inputId == itemId)
			|| Arrays.stream(SPECIAL_JEWELLERY_INPUTS[tier]).anyMatch(id -> id == itemId)
			|| Arrays.stream(MTA_INPUTS).anyMatch(id -> id == itemId);
	}

	public boolean isJewellery()
	{
		return type == Type.JEWELLERY;
	}

	public int effectsForInput(int inputQuantity)
	{
		return isJewellery() ? inputQuantity : (inputQuantity + 9) / 10;
	}

	public Map<Runes, Integer> runesForEffects(int effects)
	{
		EnumMap<Runes, Integer> required = new EnumMap<>(Runes.class);
		runes.forEach((rune, quantity) -> required.put(rune, Math.multiplyExact(quantity, effects)));
		return required;
	}

	@Override
	public String toString()
	{
		return displayName;
	}

	private static int jewelleryLevel(int tier)
	{
		return new int[]{0, 7, 27, 49, 57, 68, 87, 93}[tier];
	}

	private static int jewelleryWidget(int tier)
	{
		return new int[]{0,
			InterfaceID.MagicSpellbook.ENCHANT_1,
			InterfaceID.MagicSpellbook.ENCHANT_2,
			InterfaceID.MagicSpellbook.ENCHANT_3,
			InterfaceID.MagicSpellbook.ENCHANT_4,
			InterfaceID.MagicSpellbook.ENCHANT_5,
			InterfaceID.MagicSpellbook.ENCHANT_6,
			InterfaceID.MagicSpellbook.ENCHANT_7}[tier];
	}

	private static Map<Runes, Integer> jewelleryRunes(int tier)
	{
		switch (tier)
		{
			case 1:
				return runes(Runes.WATER, 1, Runes.COSMIC, 1);
			case 2:
				return runes(Runes.AIR, 3, Runes.COSMIC, 1);
			case 3:
				return runes(Runes.FIRE, 5, Runes.COSMIC, 1);
			case 4:
				return runes(Runes.EARTH, 10, Runes.COSMIC, 1);
			case 5:
				return runes(Runes.EARTH, 15, Runes.WATER, 15, Runes.COSMIC, 1);
			case 6:
				return runes(Runes.EARTH, 20, Runes.FIRE, 20, Runes.COSMIC, 1);
			case 7:
				return runes(Runes.BLOOD, 20, Runes.SOUL, 20, Runes.COSMIC, 1);
			default:
				throw new IllegalArgumentException("Unsupported enchantment tier: " + tier);
		}
	}

	private static Map<Runes, Integer> runes(Object... values)
	{
		EnumMap<Runes, Integer> runes = new EnumMap<>(Runes.class);
		for (int index = 0; index < values.length; index += 2)
		{
			runes.put((Runes) values[index], (Integer) values[index + 1]);
		}
		return Collections.unmodifiableMap(runes);
	}
}
