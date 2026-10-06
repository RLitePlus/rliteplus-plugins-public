package net.runelite.client.plugins.microbot.efficientwalker;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.WorldType;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spells;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.magic.Spell;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.poh.data.HouseLocation;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

final class LocalTeleportCatalog
{
	private static final java.util.regex.Pattern CHRONICLE_CHARGES = java.util.regex.Pattern.compile(
		"Your book has ([0-9]{1,4}|1,000) charges left\\.");

	static int chronicleCharges(String message)
	{
		if ("Your book has run out of charges.".equals(message)
			|| "Your book does not have any charges. Purchase some Teleport Cards from Diango.".equals(message)) { return 0; }
		if ("You have one charge left in your book.".equals(message)) { return 1; }
		java.util.regex.Matcher match = CHRONICLE_CHARGES.matcher(message == null ? "" : message);
		if (!match.matches()) { return -1; }
		int charges = Integer.parseInt(match.group(1).replace(",", ""));
		return charges <= 1000 ? charges : -1;
	}

	private static final Spell APE_ATOLL_TELEPORT = new Spell()
	{
		@Override
		public MagicAction getMagicAction()
		{
			return MagicAction.TELEPORT_APE_ATOLL;
		}

		@Override
		public HashMap<Runes, Integer> getRequiredRunes()
		{
			return new HashMap<>(Map.of(Runes.FIRE, 2, Runes.WATER, 2, Runes.LAW, 2));
		}

		@Override
		public Rs2Spellbook getSpellbook()
		{
			return Rs2Spellbook.MODERN;
		}

		@Override
		public int getRequiredLevel()
		{
			return getMagicAction().getLevel();
		}
	};

	private static final int[] GAMES_NECKLACES = {
		ItemID.NECKLACE_OF_MINIGAMES_1, ItemID.NECKLACE_OF_MINIGAMES_2,
		ItemID.NECKLACE_OF_MINIGAMES_3, ItemID.NECKLACE_OF_MINIGAMES_4,
		ItemID.NECKLACE_OF_MINIGAMES_5, ItemID.NECKLACE_OF_MINIGAMES_6,
		ItemID.NECKLACE_OF_MINIGAMES_7, ItemID.NECKLACE_OF_MINIGAMES_8
	};
	private static final int[] RINGS_OF_DUELING = {
		ItemID.RING_OF_DUELING_1, ItemID.RING_OF_DUELING_2, ItemID.RING_OF_DUELING_3,
		ItemID.RING_OF_DUELING_4, ItemID.RING_OF_DUELING_5, ItemID.RING_OF_DUELING_6,
		ItemID.RING_OF_DUELING_7, ItemID.RING_OF_DUELING_8
	};
	private static final int[] COMBAT_BRACELETS = {
		ItemID.JEWL_BRACELET_OF_COMBAT_1, ItemID.JEWL_BRACELET_OF_COMBAT_2,
		ItemID.JEWL_BRACELET_OF_COMBAT_3, ItemID.JEWL_BRACELET_OF_COMBAT_4,
		ItemID.JEWL_BRACELET_OF_COMBAT_5, ItemID.JEWL_BRACELET_OF_COMBAT_6
	};
	private static final int[] SKILLS_NECKLACES = {
		ItemID.JEWL_NECKLACE_OF_SKILLS_1, ItemID.JEWL_NECKLACE_OF_SKILLS_2,
		ItemID.JEWL_NECKLACE_OF_SKILLS_3, ItemID.JEWL_NECKLACE_OF_SKILLS_4,
		ItemID.JEWL_NECKLACE_OF_SKILLS_5, ItemID.JEWL_NECKLACE_OF_SKILLS_6
	};
	private static final int[] AMULETS_OF_GLORY = {
		ItemID.AMULET_OF_GLORY_1, ItemID.AMULET_OF_GLORY_2, ItemID.AMULET_OF_GLORY_3,
		ItemID.AMULET_OF_GLORY_4, ItemID.AMULET_OF_GLORY_5, ItemID.AMULET_OF_GLORY_6,
		ItemID.TRAIL_AMULET_OF_GLORY_1, ItemID.TRAIL_AMULET_OF_GLORY_2,
		ItemID.TRAIL_AMULET_OF_GLORY_3, ItemID.TRAIL_AMULET_OF_GLORY_4,
		ItemID.TRAIL_AMULET_OF_GLORY_5, ItemID.TRAIL_AMULET_OF_GLORY_6,
		ItemID.AMULET_OF_GLORY_INF
	};
	private static final int[] RINGS_OF_WEALTH = {
		ItemID.RING_OF_WEALTH_1, ItemID.RING_OF_WEALTH_2, ItemID.RING_OF_WEALTH_3,
		ItemID.RING_OF_WEALTH_4, ItemID.RING_OF_WEALTH_5, ItemID.RING_OF_WEALTH_I1,
		ItemID.RING_OF_WEALTH_I2, ItemID.RING_OF_WEALTH_I3, ItemID.RING_OF_WEALTH_I4,
		ItemID.RING_OF_WEALTH_I5
	};
	private static final int[] SLAYER_RINGS = {
		ItemID.SLAYER_RING_1, ItemID.SLAYER_RING_2, ItemID.SLAYER_RING_3,
		ItemID.SLAYER_RING_4, ItemID.SLAYER_RING_5, ItemID.SLAYER_RING_6,
		ItemID.SLAYER_RING_7, ItemID.SLAYER_RING_8, ItemID.SLAYER_RING_ETERNAL
	};
	private static final int[] DIGSITE_PENDANTS = {
		ItemID.NECKLACE_OF_DIGSITE_1, ItemID.NECKLACE_OF_DIGSITE_2,
		ItemID.NECKLACE_OF_DIGSITE_3, ItemID.NECKLACE_OF_DIGSITE_4,
		ItemID.NECKLACE_OF_DIGSITE_5
	};
	private static final int[] RINGS_OF_RETURNING = {
		ItemID.RING_OF_RETURNING_1, ItemID.RING_OF_RETURNING_2, ItemID.RING_OF_RETURNING_3,
		ItemID.RING_OF_RETURNING_4, ItemID.RING_OF_RETURNING_5
	};
	private static final int[] NECKLACES_OF_PASSAGE = {
		ItemID.NECKLACE_OF_PASSAGE_1, ItemID.NECKLACE_OF_PASSAGE_2,
		ItemID.NECKLACE_OF_PASSAGE_3, ItemID.NECKLACE_OF_PASSAGE_4,
		ItemID.NECKLACE_OF_PASSAGE_5
	};

	private LocalTeleportCatalog()
	{
	}

	static boolean itemAllowed(Client client, int itemId)
	{
		ItemComposition item = client.getItemDefinition(itemId);
		return item != null && (!item.isMembers() || client.getWorldType().contains(WorldType.MEMBERS));
	}

	static boolean spellAllowed(Client client, Spell spell)
	{
		return spell != null && spell.getMagicAction() != null
			&& (!spell.getMagicAction().isMembers() || client.getWorldType().contains(WorldType.MEMBERS));
	}

	static boolean homeCooldownReady(int lastTeleportMinutes, long nowSeconds)
	{
		return lastTeleportMinutes >= 0 && nowSeconds >= (lastTeleportMinutes + 30L) * 60L;
	}

	static boolean homeCombatReady(Client client)
	{
		return client.getGameState() == net.runelite.api.GameState.LOGGED_IN
			&& client.getLocalPlayer() != null && client.getLocalPlayer().getHealthRatio() < 0
			&& !Rs2Player.isInCombat();
	}

	static List<Teleport> load()
	{
		final List<Teleport> teleports = new ArrayList<>(List.of(
			new Teleport("Lumbridge Home Teleport", new WorldPoint(3221, 3218, 0),
				Rs2Spells.LUMBRIDGE_HOME_TELEPORT, InterfaceID.MagicSpellbook.TELEPORT_HOME_STANDARD,
				"Cast", -1, (String) null),
			new Teleport("Chronicle", new WorldPoint(3200, 3355, 0),
				new int[]{ItemID.CHRONICLE}, "Teleport"),
			new Teleport("Varrock Teleport", LocalTeleportCatalog::varrockSpellLanding,
				Rs2Spells.VARROCK_TELEPORT, InterfaceID.MagicSpellbook.VARROCK_TELEPORT, "Cast",
				LocalTeleportCatalog::varrockSpellIdentifier, -1, -1, () -> null, null),
			new Teleport("Varrock Teleport", new WorldPoint(3213, 3424, 0),
				ItemID.POH_TABLET_VARROCKTELEPORT, LocalTeleportCatalog::varrockTabletAction),
			new Teleport("Lumbridge Teleport", new WorldPoint(3221, 3218, 0),
				Rs2Spells.LUMBRIDGE_TELEPORT, InterfaceID.MagicSpellbook.LUMBRIDGE_TELEPORT, "Cast",
				ItemID.POH_TABLET_LUMBRIDGETELEPORT, "Break"),
			new Teleport("Falador Teleport", new WorldPoint(2965, 3378, 0),
				Rs2Spells.FALADOR_TELEPORT, InterfaceID.MagicSpellbook.FALADOR_TELEPORT, "Cast",
				ItemID.POH_TABLET_FALADORTELEPORT, "Break"),
			new Teleport("Teleport to House", LocalTeleportCatalog::housePortal,
				Rs2Spells.TELEPORT_TO_HOUSE, InterfaceID.MagicSpellbook.TELEPORT_TO_YOUR_HOUSE,
				"Outside", () -> 2, -1, ItemID.POH_TABLET_TELEPORTTOHOUSE, () -> "Outside", null),
			new Teleport("Camelot Teleport", LocalTeleportCatalog::camelotSpellLanding,
				Rs2Spells.CAMELOT_TELEPORT, InterfaceID.MagicSpellbook.CAMELOT_TELEPORT, "Cast",
				LocalTeleportCatalog::camelotSpellIdentifier, -1, -1, () -> null, null),
			new Teleport("Camelot Teleport", new WorldPoint(2757, 3478, 0),
				ItemID.POH_TABLET_CAMELOTTELEPORT, LocalTeleportCatalog::camelotTabletAction),
			new Teleport("Kourend Castle Teleport", new WorldPoint(1643, 3672, 0),
				Rs2Spells.KOUREND_CASTLE_TELEPORT, InterfaceID.MagicSpellbook.KOUREND_TELEPORT, "Cast",
				ItemID.POH_TABLET_KOURENDTELEPORT, "Break", Quest.CLIENT_OF_KOUREND),
			new Teleport("Ardougne Teleport", new WorldPoint(2664, 3306, 0),
				Rs2Spells.ARDOUGNE_TELEPORT, InterfaceID.MagicSpellbook.ARDOUGNE_TELEPORT, "Cast",
				ItemID.POH_TABLET_ARDOUGNETELEPORT, "Break", Quest.PLAGUE_CITY),
			new Teleport("Civitas illa Fortis Teleport", new WorldPoint(1681, 3133, 0),
				Rs2Spells.CIVITAS_ILLA_FORTIS_TELEPORT, InterfaceID.MagicSpellbook.FORTIS_TELEPORT, "Cast",
				ItemID.POH_TABLET_FORTISTELEPORT, "Break", Quest.TWILIGHTS_PROMISE),
			new Teleport("Watchtower Teleport", LocalTeleportCatalog::watchtowerLanding,
				Rs2Spells.WATCHTOWER_TELEPORT, InterfaceID.MagicSpellbook.WATCHTOWER_TELEPORT, "Cast",
				LocalTeleportCatalog::configuredTeleportIdentifier, -1,
				ItemID.POH_TABLET_WATCHTOWERTELEPORT, () -> "Break", Quest.WATCHTOWER),
			new Teleport("Trollheim Teleport", new WorldPoint(2891, 3678, 0),
				Rs2Spells.TROLLHEIM_TELEPORT, InterfaceID.MagicSpellbook.TROLLHEIM_TELEPORT, "Cast",
				-1, null, Quest.EADGARS_RUSE),
			new Teleport("Ape Atoll Teleport", () -> new WorldPoint(2797, 2798, 1),
				APE_ATOLL_TELEPORT, InterfaceID.MagicSpellbook.APE_TELEPORT, "Cast", () -> 1,
				ItemID.BANANA, -1, () -> null, Quest.RECIPE_FOR_DISASTER__KING_AWOWOGEI)));
		teleports.addAll(jewellery());
		return List.copyOf(teleports);
	}

	static int[] jewelleryItemIds()
	{
		return Stream.of(GAMES_NECKLACES, RINGS_OF_DUELING, COMBAT_BRACELETS,
			SKILLS_NECKLACES, AMULETS_OF_GLORY, RINGS_OF_WEALTH, SLAYER_RINGS,
			DIGSITE_PENDANTS, RINGS_OF_RETURNING, NECKLACES_OF_PASSAGE)
			.flatMapToInt(Arrays::stream)
			.toArray();
	}

	private static List<Teleport> jewellery()
	{
		return List.of(
			new Teleport("Games necklace: Burthorpe", new WorldPoint(2898, 3554, 0),
				GAMES_NECKLACES, "Burthorpe"),
			new Teleport("Games necklace: Barbarian Outpost", new WorldPoint(2520, 3571, 0),
				GAMES_NECKLACES, "Barbarian Outpost"),
			new Teleport("Games necklace: Tears of Guthix", new WorldPoint(3245, 9500, 2),
				GAMES_NECKLACES, "Tears of Guthix", Quest.TEARS_OF_GUTHIX),
			new Teleport("Games necklace: Wintertodt Camp", new WorldPoint(1624, 3938, 0),
				GAMES_NECKLACES, "Wintertodt Camp",
				() -> Microbot.getVarbitValue(VarbitID.ZEAH_PLAYERHASVISITED) == 1),

			new Teleport("Ring of dueling: Emir's Arena", new WorldPoint(3315, 3235, 0),
				RINGS_OF_DUELING, "Emir's Arena"),
			new Teleport("Ring of dueling: Castle Wars", new WorldPoint(2441, 3091, 0),
				RINGS_OF_DUELING, "Castle Wars"),
			new Teleport("Ring of dueling: Fortis Colosseum", new WorldPoint(1793, 3107, 0),
				RINGS_OF_DUELING, "Fortis Colosseum",
				() -> Microbot.getClient().getVarpValue(VarPlayerID.COLOSSEUM_GLORY) >= 12_000),

			new Teleport("Combat bracelet: Warriors' Guild", new WorldPoint(2883, 3549, 0),
				COMBAT_BRACELETS, "Warriors' Guild"),
			new Teleport("Combat bracelet: Champions' Guild", new WorldPoint(3189, 3368, 0),
				COMBAT_BRACELETS, "Champions' Guild"),
			new Teleport("Combat bracelet: Edgeville Monastery", new WorldPoint(3053, 3487, 0),
				COMBAT_BRACELETS, "Monastery"),
			new Teleport("Combat bracelet: Ranging Guild", new WorldPoint(2654, 3441, 0),
				COMBAT_BRACELETS, "Ranging Guild"),

			new Teleport("Skills necklace: Fishing Guild", new WorldPoint(2613, 3390, 0),
				SKILLS_NECKLACES, "Fishing Guild"),
			new Teleport("Skills necklace: Mining Guild", new WorldPoint(3049, 9762, 0),
				SKILLS_NECKLACES, "Mining Guild"),
			new Teleport("Skills necklace: Crafting Guild", new WorldPoint(2934, 3294, 0),
				SKILLS_NECKLACES, "Crafting Guild"),
			new Teleport("Skills necklace: Cooking Guild", new WorldPoint(3145, 3438, 0),
				SKILLS_NECKLACES, "Cooking Guild"),
			new Teleport("Skills necklace: Woodcutting Guild", new WorldPoint(1662, 3505, 0),
				SKILLS_NECKLACES, "Woodcutting Guild"),
			new Teleport("Skills necklace: Farming Guild", new WorldPoint(1248, 3727, 0),
				SKILLS_NECKLACES, "Farming Guild",
				() -> Rs2Player.getRealSkillLevel(Skill.FARMING) >= 45),

			new Teleport("Amulet of glory: Edgeville", new WorldPoint(3087, 3496, 0),
				AMULETS_OF_GLORY, "Edgeville"),
			new Teleport("Amulet of glory: Karamja", new WorldPoint(2918, 3176, 0),
				AMULETS_OF_GLORY, "Karamja"),
			new Teleport("Amulet of glory: Draynor Village", new WorldPoint(3105, 3251, 0),
				AMULETS_OF_GLORY, "Draynor Village"),
			new Teleport("Amulet of glory: Al Kharid", new WorldPoint(3293, 3163, 0),
				AMULETS_OF_GLORY, "Al Kharid"),

			new Teleport("Ring of wealth: Miscellania", new WorldPoint(2535, 3862, 0),
				RINGS_OF_WEALTH, "Miscellania", Quest.THRONE_OF_MISCELLANIA),
			new Teleport("Ring of wealth: Grand Exchange", new WorldPoint(3162, 3480, 0),
				RINGS_OF_WEALTH, "Grand Exchange"),
			new Teleport("Ring of wealth: Falador Park", new WorldPoint(2995, 3375, 0),
				RINGS_OF_WEALTH, "Falador"),
			new Teleport("Ring of wealth: Dondakan", new WorldPoint(2831, 10165, 0),
				RINGS_OF_WEALTH, "Dondakan", Quest.BETWEEN_A_ROCK),

			new Teleport("Slayer ring: Stronghold", new WorldPoint(2433, 3421, 0),
				SLAYER_RINGS, "Stronghold", () -> true, false),
			new Teleport("Slayer ring: Slayer Tower", new WorldPoint(3423, 3536, 0),
				SLAYER_RINGS, "Slayer Tower", Quest.PRIEST_IN_PERIL, false),
			new Teleport("Slayer ring: Fremennik Dungeon", new WorldPoint(2800, 9998, 0),
				SLAYER_RINGS, "Fremennik Dungeon", () -> true, false),
			new Teleport("Slayer ring: Tarn's Lair", new WorldPoint(3187, 4601, 0),
				SLAYER_RINGS, "Tarn's Lair", Quest.HAUNTED_MINE, false),
			new Teleport("Slayer ring: Dark Beasts", new WorldPoint(2028, 4638, 0),
				SLAYER_RINGS, "Dark Beasts", Quest.MOURNINGS_END_PART_II, false),
			new Teleport("Slayer ring: Wyrmscraig Cavern", new WorldPoint(2773, 8633, 0),
				SLAYER_RINGS, "Wyrmscraig Cavern", Quest.FALLEN_FROM_GRACE, false),

			new Teleport("Digsite pendant: Digsite", new WorldPoint(3339, 3445, 0),
				DIGSITE_PENDANTS, "Digsite"),
			new Teleport("Digsite pendant: Fossil Island", new WorldPoint(3763, 3869, 1),
				DIGSITE_PENDANTS, "Fossil Island", Quest.BONE_VOYAGE),
			new Teleport("Digsite pendant: Lithkren", new WorldPoint(3547, 10456, 0),
				DIGSITE_PENDANTS, "Lithkren Dungeon",
				() -> QuestState.FINISHED.equals(Rs2Player.getQuestState(Quest.DRAGON_SLAYER_II))
					&& Microbot.getVarbitValue(VarbitID.LITHKREN_RUBY_NECKLACE_REDIRECT) == 1),

			new Teleport("Necklace of passage: Wizards' Tower", new WorldPoint(3114, 3181, 0),
				NECKLACES_OF_PASSAGE, "Wizards' Tower"),
			new Teleport("Necklace of passage: The Outpost", new WorldPoint(2431, 3348, 0),
				NECKLACES_OF_PASSAGE, "The Outpost"),
			new Teleport("Necklace of passage: Eagles' Eyrie", new WorldPoint(3406, 3157, 0),
				NECKLACES_OF_PASSAGE, "Eagles' Eyrie"),
			new Teleport("Necklace of passage: Wyrmscraig", new WorldPoint(2591, 2221, 0),
				NECKLACES_OF_PASSAGE, "Wyrmscraig", Quest.FALLEN_FROM_GRACE));
	}

	private static String varrockTabletAction()
	{
		return varrockTabletAction(Microbot.getVarbitValue(VarbitID.VARROCK_GE_TELEPORT));
	}

	static String varrockTabletAction(int grandExchangeDestination)
	{
		return grandExchangeDestination == 1 ? "Varrock" : "Break";
	}

	private static WorldPoint varrockSpellLanding()
	{
		return varrockSpellLanding(Microbot.getVarbitValue(VarbitID.VARROCK_GE_TELEPORT));
	}

	static WorldPoint varrockSpellLanding(int grandExchangeDestination)
	{
		return grandExchangeDestination == 1
			? new WorldPoint(3164, 3478, 0) : new WorldPoint(3213, 3424, 0);
	}

	private static int varrockSpellIdentifier()
	{
		return configuredSpellIdentifier(Microbot.getVarbitValue(VarbitID.VARROCK_GE_TELEPORT));
	}

	private static String camelotTabletAction()
	{
		return camelotTabletAction(Microbot.getVarbitValue(VarbitID.SEERS_CAMELOT_TELEPORT));
	}

	static String camelotTabletAction(int seersDestination)
	{
		return seersDestination == 1 ? "Camelot" : "Break";
	}

	private static WorldPoint camelotSpellLanding()
	{
		return camelotSpellLanding(Microbot.getVarbitValue(VarbitID.SEERS_CAMELOT_TELEPORT));
	}

	static WorldPoint camelotSpellLanding(int seersDestination)
	{
		return seersDestination == 1
			? new WorldPoint(2726, 3485, 0) : new WorldPoint(2757, 3478, 0);
	}

	private static int camelotSpellIdentifier()
	{
		return configuredSpellIdentifier(Microbot.getVarbitValue(VarbitID.SEERS_CAMELOT_TELEPORT));
	}

	private static WorldPoint housePortal()
	{
		final HouseLocation house = HouseLocation.getHouseLocation();
		return house == null ? null : house.getPortalLocation();
	}

	private static WorldPoint watchtowerLanding()
	{
		return watchtowerLanding(Microbot.getVarbitValue(VarbitID.YANILLE_TELEPORT_LOCATION));
	}

	static WorldPoint watchtowerLanding(int yanilleTeleportLocation)
	{
		return yanilleTeleportLocation == 1
			? new WorldPoint(2584, 3097, 0) : new WorldPoint(2931, 4711, 2);
	}

	private static int configuredTeleportIdentifier()
	{
		return configuredSpellIdentifier(Microbot.getVarbitValue(VarbitID.YANILLE_TELEPORT_LOCATION));
	}

	static int configuredSpellIdentifier(int configuredDestination)
	{
		return configuredDestination == 1 ? 3 : 1;
	}

	static final class Teleport
	{
		static final int ACTIVATION_COST = 4;

		final String name;
		private final Supplier<WorldPoint> landing;
		private final Spell spell;
		private final int spellWidgetId;
		private final String spellAction;
		private final IntSupplier spellIdentifier;
		private final int spellItemId;
		private final int[] itemIds;
		private final Supplier<String> itemAction;
		private final BooleanSupplier requirement;
		private BooleanSupplier bankRequirement;
		private final boolean equipmentSupported;

		Teleport(String name, WorldPoint landing, Spell spell, int spellWidgetId, String spellAction,
			int tabletId, String tabletAction)
		{
			this(name, () -> landing, spell, spellWidgetId, spellAction, () -> 1,
				-1, tabletId, () -> tabletAction, null);
		}

		Teleport(String name, WorldPoint landing, Spell spell, int spellWidgetId, String spellAction,
			int tabletId, Supplier<String> tabletAction)
		{
			this(name, () -> landing, spell, spellWidgetId, spellAction, () -> 1,
				-1, tabletId, tabletAction, null);
		}

		Teleport(String name, WorldPoint landing, Spell spell, int spellWidgetId, String spellAction,
			int tabletId, String tabletAction, Quest quest)
		{
			this(name, () -> landing, spell, spellWidgetId, spellAction, () -> 1,
				-1, tabletId, () -> tabletAction, quest);
		}

		Teleport(String name, Supplier<WorldPoint> landing, Spell spell, int spellWidgetId,
			String spellAction, IntSupplier spellIdentifier, int spellItemId, int tabletId,
			Supplier<String> tabletAction, Quest quest)
		{
			this(name, landing, spell, spellWidgetId, spellAction, spellIdentifier, spellItemId,
				tabletId < 0 ? new int[0] : new int[]{tabletId}, tabletAction,
				quest == null ? () -> true
					: () -> QuestState.FINISHED.equals(Rs2Player.getQuestState(quest)), true);
		}

		Teleport(String name, WorldPoint landing, int[] itemIds, String itemAction)
		{
			this(name, landing, itemIds, itemAction, () -> true);
		}

		Teleport(String name, WorldPoint landing, int itemId, Supplier<String> itemAction)
		{
			this(name, () -> landing, null, -1, null, () -> 1, -1, new int[]{itemId},
				itemAction, () -> true, true);
		}

		Teleport(String name, WorldPoint landing, int[] itemIds, String itemAction, Quest quest)
		{
			this(name, landing, itemIds, itemAction, quest, true);
		}

		Teleport(String name, WorldPoint landing, int[] itemIds, String itemAction, Quest quest,
			boolean equipmentSupported)
		{
			this(name, landing, itemIds, itemAction,
				() -> QuestState.FINISHED.equals(Rs2Player.getQuestState(quest)), equipmentSupported);
		}

		Teleport(String name, WorldPoint landing, int[] itemIds, String itemAction,
			BooleanSupplier requirement)
		{
			this(name, landing, itemIds, itemAction, requirement, true);
		}

		Teleport(String name, WorldPoint landing, int[] itemIds, String itemAction,
			BooleanSupplier requirement, boolean equipmentSupported)
		{
			this(name, () -> landing, null, -1, null, () -> 1, -1, itemIds,
				() -> itemAction, requirement, equipmentSupported);
		}

		Teleport(String name, WorldPoint landing, int[] itemIds, String itemAction,
			BooleanSupplier requirement, boolean equipmentSupported, BooleanSupplier bankRequirement)
		{
			this(name, landing, itemIds, itemAction, requirement, equipmentSupported);
			this.bankRequirement = bankRequirement;
		}

		private Teleport(String name, Supplier<WorldPoint> landing, Spell spell, int spellWidgetId,
			String spellAction, IntSupplier spellIdentifier, int spellItemId, int[] itemIds,
			Supplier<String> itemAction, BooleanSupplier requirement, boolean equipmentSupported)
		{
			this.name = name;
			this.landing = landing;
			this.spell = spell;
			this.spellWidgetId = spellWidgetId;
			this.spellAction = spellAction;
			this.spellIdentifier = spellIdentifier;
			this.spellItemId = spellItemId;
			this.itemIds = itemIds;
			this.itemAction = itemAction;
			this.requirement = requirement;
			this.bankRequirement = requirement;
			this.equipmentSupported = equipmentSupported;
		}

		WorldPoint landing()
		{
			return landing.get();
		}

		boolean isHomeTeleport()
		{
			return spell == Rs2Spells.LUMBRIDGE_HOME_TELEPORT;
		}

		int activationTicks()
		{
			return isHomeTeleport() ? 25 : ACTIVATION_COST;
		}

		int bankItem(Client client, Map<Integer, Integer> bank)
		{
			if (Rs2Player.isTeleBlocked() || !bankRequirement.getAsBoolean()) { return -1; }
			for (int id : itemIds)
			{
				if (bank.getOrDefault(id, 0) > 0 && itemAllowed(client, id)
					&& client.getItemDefinition(id).getNote() == -1)
				{
					return id;
				}
			}
			return -1;
		}

		List<BankTrip.Option> bankOptions(Client client, Map<Integer, Integer> bank, WorldPoint landing)
		{
			List<BankTrip.Option> options = new ArrayList<>();
			if (landing == null || Rs2Player.isTeleBlocked() || !bankRequirement.getAsBoolean()) { return options; }
			int item = bankItem(client, bank);
			if (item >= 0) { options.add(new BankTrip.Option(this, landing, item)); }
			Map<Integer, Integer> runes = bankWithdrawals(client, bank, -1);
			if (runes != null && !runes.isEmpty()) { options.add(new BankTrip.Option(this, landing, runes)); }
			return options;
		}

		Map<Integer, Integer> bankWithdrawals(Client client, Map<Integer, Integer> bank, int itemId)
		{
			if (Rs2Player.isTeleBlocked() || !bankRequirement.getAsBoolean()) { return null; }
			if (itemId >= 0)
			{
				if (!itemAllowed(client, itemId) || Arrays.stream(itemIds).noneMatch(id -> id == itemId)) { return null; }
				if (hasItem(itemId)) { return java.util.Collections.emptyMap(); }
				return bank.getOrDefault(itemId, 0) > 0 ? Map.of(itemId, 1) : null;
			}
			if (!spellAllowed(client, spell) || !spell.hasRequirements()) { return null; }
			Map<Integer, Integer> supplies = RuneSupplies.fromBank(Rs2Magic.getMissingRunes(spell), bank,
				id -> itemAllowed(client, id));
			if (supplies == null) { return null; }
			if (spellItemId >= 0 && !Rs2Inventory.hasItem(spellItemId))
			{
				if (!itemAllowed(client, spellItemId) || bank.getOrDefault(spellItemId, 0) < 1) { return null; }
				supplies.put(spellItemId, 1);
			}
			return supplies;
		}

		boolean isAvailable(WorldPoint from, WorldPoint expectedLanding)
		{
			return from != null && expectedLanding != null && !WorldPathfinder.isWilderness(expectedLanding)
				&& isAvailable(Rs2Player.isTeleBlocked(),
					WorldPathfinder.isWilderness(from) ? 1 : 0, this::canCast,
					this::hasItem, requirement.getAsBoolean());
		}

		boolean isAvailable(boolean teleBlocked, int wildernessLevel,
			Predicate<Spell> canCast, IntPredicate hasItem)
		{
			return isAvailable(teleBlocked, wildernessLevel, canCast, hasItem, true);
		}

		boolean isAvailable(boolean teleBlocked, int wildernessLevel,
			Predicate<Spell> canCast, IntPredicate hasItem, boolean requirementsMet)
		{
			final boolean spellAvailable = spell != null && canCast.test(spell)
				&& (spellItemId < 0 || hasItem.test(spellItemId));
			return !teleBlocked && wildernessLevel == 0 && requirementsMet
				&& (spellAvailable || Arrays.stream(itemIds).anyMatch(hasItem));
		}

		boolean usesSpell()
		{
			return spell != null && canCast(spell) && (spellItemId < 0 || Rs2Inventory.hasItem(spellItemId));
		}

		Widget spellWidget()
		{
			return Microbot.getClient().getWidget(spellWidgetId);
		}

		boolean activate(WorldPoint from, WorldPoint expectedLanding, BiConsumer<NewMenuEntry, Rectangle> dispatch)
		{
			if (!isAvailable(from, expectedLanding))
			{
				return false;
			}
			if (usesSpell() && castSpell(dispatch))
			{
				return true;
			}
			String action = itemAction.get();
			return interactItem(action, action);
		}

		boolean isChronicle()
		{
			return itemIds.length == 1 && itemIds[0] == ItemID.CHRONICLE;
		}

		private boolean interactItem(String inventoryAction, String equipmentAction)
		{
			for (int itemId : itemIds)
			{
				if (!itemAllowed(Microbot.getClient(), itemId)) { continue; }
				if (Rs2Inventory.hasItem(itemId))
				{
					return Rs2Inventory.interact(itemId, inventoryAction);
				}
				if (equipmentSupported && Rs2Equipment.isWearing(itemId))
				{
					return Rs2Equipment.interact(itemId, equipmentAction);
				}
			}
			return false;
		}

		private boolean castSpell(BiConsumer<NewMenuEntry, Rectangle> dispatch)
		{
			final Widget widget = Microbot.getClient().getWidget(spellWidgetId);
			final int identifier = spellIdentifier.getAsInt();
			if (!hasAction(widget, spellAction, identifier) || widget.isHidden()
				|| !net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper.isRectangleWithinCanvas(widget.getBounds()))
			{
				return false;
			}
			dispatch.accept(new NewMenuEntry()
				.option(spellAction)
				.target(spell.getMagicAction().getName())
				.identifier(identifier)
				.type(MenuAction.CC_OP)
				.param0(-1)
				.param1(spellWidgetId), new Rectangle(widget.getBounds()));
			return true;
		}

		static boolean hasAction(Widget widget, String action, int identifier)
		{
			final String[] actions = widget == null ? null : widget.getActions();
			return actions != null && identifier > 0 && identifier <= actions.length
				&& action.equals(actions[identifier - 1]);
		}

		boolean isQuetzalWhistle()
		{
			return !equipmentSupported && Arrays.equals(itemIds, Quetzals.WHISTLES);
		}

		boolean supportsEquippedItems()
		{
			return equipmentSupported;
		}

		boolean needsEquipmentTab()
		{
			if (spell != null && canCast(spell)
				&& (spellItemId < 0 || Rs2Inventory.hasItem(spellItemId)))
			{
				return false;
			}
			for (int itemId : itemIds)
			{
				if (!itemAllowed(Microbot.getClient(), itemId)) { continue; }
				if (Rs2Inventory.hasItem(itemId))
				{
					return false;
				}
				if (equipmentSupported && Rs2Equipment.isWearing(itemId))
				{
					return true;
				}
			}
			return false;
		}

		private boolean canCast(Spell candidate)
		{
			Client client = Microbot.getClient();
			return spellAllowed(client, candidate) && Rs2Magic.canCast(candidate)
				&& (!isHomeTeleport() || homeCombatReady(client)
					&& homeCooldownReady(client.getVarpValue(net.runelite.api.VarPlayer.LAST_HOME_TELEPORT),
						java.time.Instant.now().getEpochSecond()));
		}

		private boolean hasItem(int itemId)
		{
			return itemAllowed(Microbot.getClient(), itemId)
				&& (Rs2Inventory.hasItem(itemId) || equipmentSupported && Rs2Equipment.isWearing(itemId));
		}
	}
}
