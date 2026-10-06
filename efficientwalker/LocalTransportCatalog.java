package net.runelite.client.plugins.microbot.efficientwalker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

final class LocalTransportCatalog
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalTransportCatalog.class);
	private static final String TRANSPORTS = "/net/runelite/client/plugins/microbot/efficientwalker/transports.tsv";
	private static final String AGILITY_SHORTCUTS =
		"/net/runelite/client/plugins/microbot/efficientwalker/agility_shortcuts.tsv";
	static final Set<Integer> AL_KHARID_GATE_IDS = Set.of(44050, 44051, 44052, 44053, 44598, 44599);
	private static final Set<String> DIRECT_SKILL_ACTIONS = Set.of(
		"Climb-down", "Climb-over", "Enter", "Jump", "Jump-over", "Jump-to", "Mine", "Open");
	private static final Set<String> DIRECT_QUEST_ACTIONS = Set.of(
		"Climb-down", "Climb-up", "Enter", "Exit", "Exit-through", "Open", "Pass",
		"Pass-through", "Push");

	private LocalTransportCatalog()
	{
	}

	static List<Transport> load()
	{
		final List<Transport> transports = new ArrayList<>();
		try (Reader ordinary = open(TRANSPORTS); Reader agility = open(AGILITY_SHORTCUTS))
		{
			transports.addAll(parse(ordinary));
			transports.add(transport(new WorldPoint(2630, 2997, 0), new WorldPoint(2647, 9378, 0),
				"Enter", "Cave entrance", 3379));
			transports.add(transport(new WorldPoint(2647, 9378, 0), new WorldPoint(2630, 2997, 0),
				"Walk through", "Cave exit", 3381));
			transports.addAll(rockfallTransports());
			transports.addAll(parseAgility(agility));
			transports.addAll(StrongholdOfSecurity.portalTransports());
			transports.addAll(kourendBoatTransports());
			transports.addAll(primioTransports());
			transports.addAll(loadFairyRings().transports());
			transports.addAll(loadQuetzals().transports());
			transports.addAll(loadCharterShips().transports());
			transports.addAll(loadSpiritTrees().transports());
			return Collections.unmodifiableList(transports);
		}
		catch (IOException ex)
		{
			log.warn("Could not load transport data; transport routes are unavailable", ex);
			return Collections.emptyList();
		}
	}

	private static List<Transport> rockfallTransports()
	{
		List<Transport> result = new ArrayList<>();
		for (WorldPoint tile : MotherlodeRockfalls.TILES.stream()
			.sorted(java.util.Comparator.comparingInt(WorldPoint::getX).thenComparingInt(WorldPoint::getY))
			.collect(java.util.stream.Collectors.toList()))
		{
			for (int[] delta : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}})
			{
				WorldPoint neighbour = new WorldPoint(tile.getX() + delta[0], tile.getY() + delta[1], tile.getPlane());
				for (boolean entering : new boolean[]{true, false})
				{
					result.add(new Transport(entering ? neighbour : tile, entering ? tile : neighbour,
						"Mine", "Rockfall", 26679, Collections.emptyList(),
						List.of(new ItemRequirement(List.of(new ItemAmount("PICKAXE", 1)))),
						Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false, new int[0]));
				}
			}
		}
		return result;
	}

	static FairyRings loadFairyRings()
	{
		try (Reader reader = open(FairyRings.RESOURCE)) { return FairyRings.load(reader); }
		catch (IOException | IllegalArgumentException ex) { throw new IllegalStateException("Could not load fairy ring data", ex); }
	}

	static Quetzals loadQuetzals()
	{
		try (Reader reader = open(Quetzals.RESOURCE)) { return Quetzals.load(reader); }
		catch (IOException | IllegalArgumentException ex) { throw new IllegalStateException("Could not load quetzal data", ex); }
	}

	static CharterShips loadCharterShips()
	{
		try (Reader ports = open(CharterShips.RESOURCE_PREFIX + "ports.tsv");
			Reader npcs = open(CharterShips.RESOURCE_PREFIX + "npcs.tsv");
			Reader routes = open(CharterShips.RESOURCE_PREFIX + "ships.tsv"))
		{
			return CharterShips.load(ports, npcs, routes);
		}
		catch (IOException | IllegalArgumentException ex) { throw new IllegalStateException("Could not load charter ship data", ex); }
	}

	static SpiritTrees loadSpiritTrees()
	{
		try (Reader reader = open(SpiritTrees.RESOURCE)) { return SpiritTrees.load(reader); }
		catch (IOException | IllegalArgumentException ex) { throw new IllegalStateException("Could not load spirit tree data", ex); }
	}

	static Transport fairyRingTransport(WorldPoint approach, WorldPoint landing, int objectId,
		String skills, String quests, String varbits, String varplayers)
	{
		return new Transport(approach, landing, "Configure", "Fairy ring", objectId,
			parseSkills(skills), Collections.emptyList(), parseRequirements(varbits),
			parseRequirements(varplayers), parseQuests(quests), false, new int[0]);
	}

	static Reader open(String resource) throws IOException
	{
		final InputStream input = resourceInput(resource);
		if (input != null)
		{
			return new InputStreamReader(input, StandardCharsets.UTF_8);
		}
		// ponytail: Agent deployments compile Java only; remove this fallback when they copy resources.
		Path source = Path.of("runelite-client/src/main/resources" + resource);
		if (!Files.isRegularFile(source))
		{
			source = Path.of("src/main/resources" + resource);
		}
		return Files.newBufferedReader(source, StandardCharsets.UTF_8);
	}

	static InputStream resourceInput(String resource)
	{
		InputStream input = LocalTransportCatalog.class.getResourceAsStream(resource);
		return input != null ? input : Microbot.class.getResourceAsStream(resource);
	}

	static List<Transport> parse(Reader reader) throws IOException
	{
		return parse(reader, false);
	}

	static List<Transport> parseAgility(Reader reader) throws IOException
	{
		return parse(reader, true);
	}

	static Transport transport(WorldPoint approach, WorldPoint landing, String action,
		String target, int objectId)
	{
		return new Transport(approach, landing, action, target, objectId,
			Collections.emptyList(), Collections.emptyList(),
			Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false,
			new int[0]);
	}

	private static List<Transport> kourendBoatTransports()
	{
		return List.of(
			npcTransport(new WorldPoint(1824, 3691, 0), new WorldPoint(3055, 3242, 1),
				"Port Sarim", "Veos / Cabin Boy Herbert", 10726, 10727, 10932),
			npcTransport(new WorldPoint(3055, 3245, 0), new WorldPoint(1824, 3695, 1),
				"Port Piscarilius", "Veos / Cabin Boy Herbert", 8630, 10724, 10933));
	}

	static Transport npcTransport(WorldPoint approach, WorldPoint landing, String action,
		String target, int... npcIds)
	{
		return new Transport(approach, landing, action, target, -1,
			Collections.emptyList(), Collections.emptyList(),
			Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false, npcIds);
	}

	static List<Transport> primioTransports()
	{
		return List.of(
			primioTransport(new WorldPoint(3280, 3412, 0), new WorldPoint(1700, 3141, 0),
				NpcID.VMQ2_QUETZAL_OP),
			primioTransport(new WorldPoint(1703, 3140, 0), new WorldPoint(3280, 3412, 0),
				NpcID.VMQ2_QUETZAL_FORTIS));
	}

	private static Transport primioTransport(WorldPoint approach, WorldPoint landing, int npcId)
	{
		return new Transport(approach, landing, "Travel", "Primio", -1,
			Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
			Collections.emptyList(), List.of(Quest.CHILDREN_OF_THE_SUN), false, new int[]{npcId});
	}

	private static List<Transport> parse(Reader reader, boolean agility) throws IOException
	{
		final List<Transport> transports = new ArrayList<>();
		final BufferedReader lines = new BufferedReader(reader);
		String line;
		while ((line = lines.readLine()) != null)
		{
			final Transport transport = parseLine(line, agility);
			if (transport != null)
			{
				transports.add(transport);
			}
		}
		markGatedBoundaries(transports);
		return Collections.unmodifiableList(transports);
	}

	private static void markGatedBoundaries(List<Transport> transports)
	{
		final Set<Integer> gatedObjectIds = transports.stream()
			.filter(transport -> transport.isBoundaryEdge() && !transport.isUnconditional())
			.map(transport -> transport.objectId)
			.collect(java.util.stream.Collectors.toSet());
		for (Transport transport : transports)
		{
			transport.gatedBoundary = gatedObjectIds.contains(transport.objectId)
				&& transport.isBoundaryEdge();
		}
	}

	private static Transport parseLine(String line, boolean agility)
	{
		if (line.isEmpty() || line.charAt(0) == '#')
		{
			return null;
		}

		final String[] columns = line.split("\t", -1);
		if (columns.length < 8)
		{
			return null;
		}

		final String object = columns[2];
		final int firstSpace = object.indexOf(' ');
		final int lastSpace = object.lastIndexOf(' ');
		if (firstSpace <= 0 || lastSpace <= firstSpace)
		{
			return null;
		}
		final String action = object.substring(0, firstSpace);
		if (!agility && !hasSupportedOrdinaryRequirements(columns, action))
		{
			return null;
		}

		try
		{
			final List<SkillRequirement> skills = parseSkills(columns[3]);
			final List<ItemRequirement> items = parseItems(columns[4]);
			return new Transport(
				parsePoint(columns[0]),
				parsePoint(columns[1]),
				action,
				object.substring(firstSpace + 1, lastSpace),
				Integer.parseInt(object.substring(lastSpace + 1)),
				skills,
				items,
				parseRequirements(columns[agility ? 5 : 6]),
				parseRequirements(columns[agility ? 6 : 7]),
				parseQuests(agility ? columns.length > 8 ? columns[8] : "" : columns[5]),
				agility || skills.stream().anyMatch(requirement -> requirement.skill == Skill.AGILITY),
				new int[0]);
		}
		catch (IllegalArgumentException ex)
		{
			log.debug("Skipping unsupported transport row: {} ({})", line, ex.getMessage());
			return null;
		}
	}

	private static List<SkillRequirement> parseSkills(String value)
	{
		if (value.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<SkillRequirement> requirements = new ArrayList<>();
		for (String token : value.split(";"))
		{
			final int separator = token.indexOf(' ');
			if (separator <= 0 || separator == token.length() - 1)
			{
				throw new IllegalArgumentException("Invalid skill requirement");
			}
			requirements.add(new SkillRequirement(
				Skill.valueOf(token.substring(separator + 1).toUpperCase()),
				Integer.parseInt(token.substring(0, separator))));
		}
		return Collections.unmodifiableList(requirements);
	}

	private static List<ItemRequirement> parseItems(String value)
	{
		if (value.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<ItemRequirement> requirements = new ArrayList<>();
		for (String andPart : value.replace(" ", "").split("&"))
		{
			final List<ItemAmount> alternatives = new ArrayList<>();
			for (String orPart : andPart.split("\\|"))
			{
				final int separator = orPart.lastIndexOf('=');
				if (separator <= 0 || separator == orPart.length() - 1)
				{
					throw new IllegalArgumentException("Invalid item requirement");
				}
				alternatives.add(new ItemAmount(orPart.substring(0, separator).toUpperCase(),
					Integer.parseInt(orPart.substring(separator + 1))));
			}
			requirements.add(new ItemRequirement(Collections.unmodifiableList(alternatives)));
		}
		return Collections.unmodifiableList(requirements);
	}

	private static boolean hasSupportedOrdinaryRequirements(String[] columns, String action)
	{
		if (!columns[4].isEmpty())
		{
			return false;
		}
		return (columns[3].isEmpty() || DIRECT_SKILL_ACTIONS.contains(action))
			&& (columns[5].isEmpty() || DIRECT_QUEST_ACTIONS.contains(action));
	}

	private static List<ValueRequirement> parseRequirements(String value)
	{
		if (value.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<ValueRequirement> requirements = new ArrayList<>();
		for (String token : value.split(";"))
		{
			if (token.isEmpty())
			{
				continue;
			}
			final int separator = Math.max(token.indexOf('='), token.indexOf('>'));
			if (separator <= 0 || separator == token.length() - 1)
			{
				throw new IllegalArgumentException("Invalid transport requirement");
			}
			requirements.add(new ValueRequirement(
				Integer.parseInt(token.substring(0, separator)),
				Integer.parseInt(token.substring(separator + 1)), token.charAt(separator)));
		}
		return Collections.unmodifiableList(requirements);
	}

	private static List<Quest> parseQuests(String value)
	{
		if (value.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<Quest> requirements = new ArrayList<>();
		for (String name : value.split(";"))
		{
			Quest match = null;
			for (Quest quest : Quest.values())
			{
				if (quest.getName().equals(name))
				{
					match = quest;
					break;
				}
			}
			if (match == null)
			{
				throw new IllegalArgumentException("Unknown quest requirement");
			}
			requirements.add(match);
		}
		return Collections.unmodifiableList(requirements);
	}

	static WorldPoint parsePoint(String value)
	{
		final String[] coordinates = value.split(" ");
		if (coordinates.length != 3)
		{
			throw new IllegalArgumentException("Invalid transport coordinate");
		}
		return new WorldPoint(
			Integer.parseInt(coordinates[0]),
			Integer.parseInt(coordinates[1]),
			Integer.parseInt(coordinates[2]));
	}

	static final class Transport
	{
		final WorldPoint approach;
		final WorldPoint landing;
		final String action;
		final String target;
		final int objectId;
		private final List<ValueRequirement> varbits;
		private final List<ValueRequirement> varplayers;
		private final List<SkillRequirement> skills;
		private final List<ItemRequirement> items;
		private final List<Quest> quests;
		private final boolean agilityShortcut;
		private final int[] npcIds;
		private boolean gatedBoundary;

		private Transport(WorldPoint approach, WorldPoint landing, String action, String target, int objectId,
			List<SkillRequirement> skills, List<ItemRequirement> items,
			List<ValueRequirement> varbits, List<ValueRequirement> varplayers, List<Quest> quests,
			boolean agilityShortcut, int[] npcIds)
		{
			this.approach = approach;
			this.landing = landing;
			this.action = action;
			this.target = target;
			this.objectId = objectId;
			this.skills = skills;
			this.items = items;
			this.varbits = varbits;
			this.varplayers = varplayers;
			this.quests = quests;
			this.agilityShortcut = agilityShortcut;
			this.npcIds = npcIds.clone();
		}

		boolean isNpc()
		{
			return npcIds.length > 0;
		}

		boolean isPrimio()
		{
			return "Travel".equals(action) && "Primio".equals(target)
				&& (matchesNpc(NpcID.VMQ2_QUETZAL_OP) || matchesNpc(NpcID.VMQ2_QUETZAL_FORTIS));
		}

		boolean matchesNpc(int npcId)
		{
			for (int candidate : npcIds)
			{
				if (candidate == npcId)
				{
					return true;
				}
			}
			return false;
		}

		boolean isAvailable()
		{
			return isAvailable(Rs2Player::getRealSkillLevel, LocalTransportCatalog::hasItem,
				Microbot::getVarbitValue, Microbot::getVarbitPlayerValue,
				quest -> QuestState.FINISHED.equals(Rs2Player.getQuestState(quest)));
		}

		boolean isAvailable(IntUnaryOperator varbitValue, IntUnaryOperator varplayerValue)
		{
			return isAvailable(ignored -> Integer.MAX_VALUE, (ignored, quantity) -> true,
				varbitValue, varplayerValue);
		}

		boolean isAvailableIgnoringItems()
		{
			return isAvailable(Rs2Player::getRealSkillLevel, (ignored, quantity) -> true,
				Microbot::getVarbitValue, Microbot::getVarbitPlayerValue,
				quest -> QuestState.FINISHED.equals(Rs2Player.getQuestState(quest)));
		}

		boolean isAvailable(ToIntFunction<Skill> skillLevel, BiPredicate<String, Integer> itemAvailable,
			IntUnaryOperator varbitValue, IntUnaryOperator varplayerValue)
		{
			return isAvailable(skillLevel, itemAvailable, varbitValue, varplayerValue, ignored -> true);
		}

		boolean isAvailable(ToIntFunction<Skill> skillLevel, BiPredicate<String, Integer> itemAvailable,
			IntUnaryOperator varbitValue, IntUnaryOperator varplayerValue, Predicate<Quest> questCompleted)
		{
			// Primio's Travel action only appears after Regulus's first journey is complete.
			final int firstTravel = isPrimio() ? varbitValue.applyAsInt(VarbitID.VMQ2_FIRST_TRAVEL) : 0;
			// Market entry consumes a diamond; exclude its exit shortcut until toll handling is verified.
			return objectId != net.runelite.api.gameval.ObjectID.FAIRY_MUSHROOM_RING3
				&& !hasIncorrectLocalLanding()
				&& (!isAlKharidGate() || questCompleted.test(Quest.PRINCE_ALI_RESCUE)
				|| itemAvailable.test("COINS", 10))
				&& skills.stream().allMatch(requirement -> requirement.matches(skillLevel))
				&& items.stream().allMatch(requirement -> requirement.matches(itemAvailable))
				&& varbits.stream().allMatch(requirement -> requirement.matches(varbitValue))
				&& varplayers.stream().allMatch(requirement -> requirement.matches(varplayerValue))
				&& quests.stream().allMatch(questCompleted)
				&& (!isPrimio() || firstTravel == 3 || firstTravel == 4);
		}

		boolean isAlKharidGate()
		{
			return AL_KHARID_GATE_IDS.contains(objectId) && isBoundaryEdge();
		}

		int coinCost(Predicate<Quest> questCompleted)
		{
			return isAlKharidGate() && !questCompleted.test(Quest.PRINCE_ALI_RESCUE) ? 10 : 0;
		}

		boolean isWalkEdge()
		{
			return isRockfall() || isBoundaryEdge() && !isGatedBoundary();
		}

		boolean isRockfall()
		{
			return MotherlodeRockfalls.isRockfall(objectId) && "Mine".equals(action) && "Rockfall".equals(target);
		}

		private boolean isBoundaryEdge()
		{
			return approach.getPlane() == landing.getPlane()
				&& approach.distanceTo2D(landing) == 1
				&& ("Open".equalsIgnoreCase(action) || "Pass".equalsIgnoreCase(action));
		}

		boolean isGatedBoundary()
		{
			return gatedBoundary || isAlKharidGate() || hasIncorrectLocalLanding();
		}

		private boolean hasIncorrectLocalLanding()
		{
			// These entrances can enter the banquet instance, not the imported adjacent tile.
			return isBoundaryEdge() && (objectId == ObjectID.HUNDRED_LUMBRIDGE_DOOR
				|| objectId == ObjectID.HUNDRED_LUMBRIDGE_DOUBLEDOORL
				|| objectId == ObjectID.HUNDRED_LUMBRIDGE_DOUBLEDOORR);
		}

		boolean isUnconditional()
		{
			return !CharterShips.isCharter(this) && !FairyRings.isRing(this) && !SpiritTrees.isTree(this)
				&& !hasIncorrectLocalLanding()
				&& skills.isEmpty() && items.isEmpty() && varbits.isEmpty() && varplayers.isEmpty()
				&& quests.isEmpty();
		}

		boolean isAgilityShortcut()
		{
			return agilityShortcut;
		}

		boolean isGrappleShortcut()
		{
			return agilityShortcut && ("Grapple".equalsIgnoreCase(action)
				|| requiresEquipment("CROSSBOW") || requiresEquipment("MITH_GRAPPLE"));
		}

		List<String> equipmentRequirements()
		{
			final List<String> requirements = new ArrayList<>();
			for (ItemRequirement requirement : items)
			{
				for (ItemAmount alternative : requirement.alternatives)
				{
					if (isEquipmentRequirement(alternative.name) && !requirements.contains(alternative.name))
					{
						requirements.add(alternative.name);
					}
				}
			}
			return Collections.unmodifiableList(requirements);
		}

		String missingEquipment(net.runelite.api.Client client)
		{
			for (ItemRequirement requirement : items)
			{
				if (requirement.matches((item, quantity) -> hasReadyRequirementItem(client, item, quantity)))
				{
					continue;
				}
				for (ItemAmount alternative : requirement.alternatives)
				{
					if (isEquipmentRequirement(alternative.name)
						&& equipmentItem(client, net.runelite.api.InventoryID.INVENTORY, alternative.name) != null)
					{
						return alternative.name;
					}
				}
				for (ItemAmount alternative : requirement.alternatives)
				{
					if (isEquipmentRequirement(alternative.name))
					{
						return alternative.name;
					}
				}
			}
			return null;
		}

		boolean requiresEquipment(String requirement)
		{
			for (ItemRequirement itemRequirement : items)
			{
				for (ItemAmount alternative : itemRequirement.alternatives)
				{
					if (requirement.equals(alternative.name))
					{
						return true;
					}
				}
			}
			return false;
		}

		Map<Integer, Integer> bankSupplies(net.runelite.api.Client client, Map<Integer, Integer> stock)
		{
			Map<Integer, Integer> supplies = new LinkedHashMap<>();
			for (ItemRequirement requirement : items)
			{
				if (requirement.matches(LocalTransportCatalog::hasItem)) { continue; }
				Integer selected = null;
				int quantity = 0;
				for (ItemAmount alternative : requirement.alternatives)
				{
					selected = bankItem(client, stock, alternative.name, alternative.quantity);
					if (selected != null) { quantity = alternative.quantity; break; }
				}
				if (selected == null) { return null; }
				supplies.merge(selected, quantity, Math::max);
			}
			return Collections.unmodifiableMap(supplies);
		}
	}

	private static Integer bankItem(net.runelite.api.Client client, Map<Integer, Integer> stock,
		String requirement, int quantity)
	{
		String expected = requirement.replace('_', ' ');
		for (Map.Entry<Integer, Integer> item : stock.entrySet())
		{
			if (item.getValue() < quantity) { continue; }
			net.runelite.api.ItemComposition definition = client.getItemDefinition(item.getKey());
			if (definition == null || definition.getName() == null) { continue; }
			if (isEquipmentRequirement(requirement)
				? matchesEquipmentRequirement(definition, requirement)
				: definition.getName().equalsIgnoreCase(expected))
			{
				return item.getKey();
			}
		}
		return null;
	}

	private static boolean hasItem(String item, int quantity)
	{
		if ("PICKAXE".equals(item)) { return quantity <= 1 && MotherlodeRockfalls.hasPickaxe(Microbot.getClient()); }
		if (isEquipmentRequirement(item))
		{
			return quantity <= 1 && (equipmentItem(Microbot.getClient(), net.runelite.api.InventoryID.EQUIPMENT, item) != null
				|| equipmentItem(Microbot.getClient(), net.runelite.api.InventoryID.INVENTORY, item) != null);
		}
		final String name = item.replace('_', ' ');
		return Rs2Inventory.count(name, true)
			+ (Rs2Equipment.isWearing(name, true) ? 1 : 0) >= quantity;
	}

	private static boolean isEquipmentRequirement(String item)
	{
		return "CLIMBING_BOOTS".equals(item) || "CROSSBOW".equals(item)
			|| "MITH_GRAPPLE".equals(item);
	}

	private static boolean hasReadyRequirementItem(net.runelite.api.Client client, String item, int quantity)
	{
		if (isEquipmentRequirement(item))
		{
			return quantity <= 1 && equipmentItem(client, net.runelite.api.InventoryID.EQUIPMENT, item) != null;
		}
		return hasItem(item, quantity);
	}

	static QuestItemUse equipmentItem(net.runelite.api.Client client, net.runelite.api.InventoryID containerId,
		String requirement)
	{
		net.runelite.api.ItemContainer container = client.getItemContainer(containerId);
		if (container == null || container.getItems() == null) { return null; }
		net.runelite.api.Item[] items = container.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			net.runelite.api.Item item = items[slot];
			if (item == null || item.getId() < 0 || item.getQuantity() <= 0) { continue; }
			net.runelite.api.ItemComposition definition = client.getItemDefinition(item.getId());
			if (matchesEquipmentRequirement(definition, requirement))
			{
				return new QuestItemUse(item.getId(), slot, definition.getName());
			}
		}
		return null;
	}

	private static boolean matchesEquipmentRequirement(net.runelite.api.ItemComposition definition, String requirement)
	{
		if (definition == null || definition.getNote() != -1 || definition.getName() == null) { return false; }
		String name = definition.getName();
		if (!("CROSSBOW".equals(requirement)
			? definition.getId() != net.runelite.api.gameval.ItemID.PRIDE23_CROSSBOW
				&& name.toLowerCase(java.util.Locale.ROOT).contains("crossbow")
			: name.equalsIgnoreCase(requirement.replace('_', ' ')))) { return false; }
		String[] actions = definition.getInventoryActions();
		return actions != null && java.util.Arrays.stream(actions)
			.anyMatch(action -> "Wear".equalsIgnoreCase(action) || "Wield".equalsIgnoreCase(action));
	}

	private static final class SkillRequirement
	{
		private final Skill skill;
		private final int level;

		private SkillRequirement(Skill skill, int level)
		{
			this.skill = skill;
			this.level = level;
		}

		private boolean matches(ToIntFunction<Skill> levels)
		{
			return levels.applyAsInt(skill) >= level;
		}
	}

	private static final class ItemRequirement
	{
		private final List<ItemAmount> alternatives;

		private ItemRequirement(List<ItemAmount> alternatives)
		{
			this.alternatives = alternatives;
		}

		private boolean matches(BiPredicate<String, Integer> available)
		{
			return alternatives.stream().anyMatch(item -> available.test(item.name, item.quantity));
		}
	}

	private static final class ItemAmount
	{
		private final String name;
		private final int quantity;

		private ItemAmount(String name, int quantity)
		{
			this.name = name;
			this.quantity = quantity;
		}
	}

	private static final class ValueRequirement
	{
		private final int id;
		private final int expected;
		private final char operator;

		private ValueRequirement(int id, int expected, char operator)
		{
			this.id = id;
			this.expected = expected;
			this.operator = operator;
		}

		private boolean matches(IntUnaryOperator values)
		{
			final int actual = values.applyAsInt(id);
			return operator == '=' ? actual == expected : actual > expected;
		}
	}
}
