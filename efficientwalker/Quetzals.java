package net.runelite.client.plugins.microbot.efficientwalker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.efficientwalker.LocalTransportCatalog.Transport;

final class Quetzals
{
	// Data: skretzo/shortest-path, src/main/resources/transports/quetzals.tsv.
	static final String UPSTREAM_REVISION = "7e76a5e2cda3da3c772ddd347d75d89eae0139eb";
	static final String RESOURCE = "/net/runelite/client/plugins/microbot/efficientwalker/quetzals.tsv";
	static final int INTERFACE = 874;
	static final int ICONS = 12;
	static final int WHISTLE_INTERFACE = 949;
	static final int[] WHISTLES = {ItemID.HG_QUETZALWHISTLE_PERFECTED_INFINITE,
		ItemID.HG_QUETZALWHISTLE_PERFECTED, ItemID.HG_QUETZALWHISTLE_ENHANCED,
		ItemID.HG_QUETZALWHISTLE_BASIC};
	private static final Pattern CHARGES = Pattern.compile("Your quetzal whistle has (\\d+) charges? remaining\\.");
	private final Map<WorldPoint, Integer> sites = new LinkedHashMap<>();
	private final List<Transport> transports = new ArrayList<>();

	static Quetzals load(Reader input) throws IOException
	{
		Quetzals quetzals = new Quetzals();
		Map<WorldPoint, Integer> origins = new LinkedHashMap<>();
		BufferedReader reader = new BufferedReader(input);
		String line;
		while ((line = reader.readLine()) != null)
		{
			if (line.isEmpty() || line.startsWith("#")) { continue; }
			String[] row = line.split("\t", -1);
			if (row.length != 7 || row[0].isEmpty() == row[1].isEmpty()
				|| !"Twilight's Promise".equals(row[3]))
			{
				throw new IOException("Invalid quetzal data row");
			}
			boolean origin = !row[0].isEmpty();
			if (origin ? !"Travel Renu 13350".equals(row[2]) : !row[2].isEmpty() || row[5].isEmpty())
			{
				throw new IOException("Unsupported quetzal action");
			}
			WorldPoint point = LocalTransportCatalog.parsePoint(row[origin ? 0 : 1]);
			if (point.getPlane() != 0 || point.getX() <= 0 || point.getX() >= 16384
				|| point.getY() <= 0 || point.getY() >= 16384)
			{
				throw new IOException("Invalid quetzal location");
			}
			int mask = 0;
			if (!row[6].isEmpty())
			{
				if (!row[6].startsWith("4182&")) { throw new IOException("Unsupported quetzal requirement"); }
				mask = Integer.parseInt(row[6].substring(5));
				if (mask <= 0 || Integer.bitCount(mask) != 1) { throw new IOException("Invalid quetzal unlock bit"); }
			}
			if ((origin ? origins : quetzals.sites).putIfAbsent(point, mask) != null)
			{
				throw new IOException("Duplicate quetzal location");
			}
		}
		if (origins.size() < 2 || !origins.equals(quetzals.sites))
		{
			throw new IOException("Quetzal origins and destinations do not match");
		}
		for (WorldPoint origin : origins.keySet())
		{
			for (WorldPoint destination : quetzals.sites.keySet())
			{
				if (origin.equals(destination)) { continue; }
				quetzals.transports.add(LocalTransportCatalog.npcTransport(origin, destination, "Travel", "Renu",
					NpcID.QUETZAL_CHILD_GREEN, NpcID.QUETZAL_CHILD_ORANGE, NpcID.QUETZAL_CHILD_BLUE,
					NpcID.QUETZAL_CHILD_CYAN, NpcID.QUETZAL_CHILD_GREEN_ORANGE));
			}
		}
		return quetzals;
	}

	List<Transport> transports() { return Collections.unmodifiableList(transports); }

	List<LocalTeleportCatalog.Teleport> whistleTeleports(Client client, Set<Integer> rejectedSlots)
	{
		List<LocalTeleportCatalog.Teleport> result = new ArrayList<>();
		for (WorldPoint site : sites.keySet())
		{
			result.add(new LocalTeleportCatalog.Teleport("Quetzal whistle " + site, site, WHISTLES,
				"Signal", () -> whistleAvailable(client, site) && whistleSlot(client, rejectedSlots) >= 0, false,
				() -> whistleAvailable(client, site)));
		}
		return result;
	}

	boolean whistleAvailable(Client client, WorldPoint destination)
	{
		return whistleAvailable(client.getWorldType().contains(WorldType.MEMBERS),
			client.getVarpValue(VarPlayerID.QUETZALS_UNLOCKED),
			client.getVarbitValue(VarbitID.SETTINGS_QUETZALWHISTLE_DEFAULT_TP), destination);
	}

	boolean whistleAvailable(boolean members, int unlocked, int directGuild, WorldPoint destination)
	{
		return members && unlocked >= 0 && directGuild == 0 && siteAvailable(destination, unlocked);
	}

	static int whistleSlot(Client client, Set<Integer> rejected)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		return inventory == null ? -1 : whistleSlot(slot ->
		{
			Item item = inventory.getItem(slot);
			return item == null ? -1 : item.getId();
		}, rejected);
	}

	static int whistleSlot(IntUnaryOperator itemAt, Set<Integer> rejected)
	{
		for (int id : WHISTLES)
		{
			for (int slot = 0; slot < 28; slot++)
			{
				if (!rejected.contains(slot) && itemAt.applyAsInt(slot) == id) { return slot; }
			}
		}
		return -1;
	}

	static boolean whistleRecharged(String message)
	{
		return "Soar Leader Pitri|There you go. Some whistle charges for you!".equals(message)
			|| "Soar Leader Pitri|Looks like the birds are all full for now. Make them work a bit before feeding them again!".equals(message);
	}

	static int charges(String message)
	{
		Matcher match = CHARGES.matcher(message);
		if (!match.matches()) { return -1; }
		try { return Integer.parseInt(match.group(1)); }
		catch (NumberFormatException ex) { return -1; }
	}

	static boolean isQuetzal(Transport transport)
	{
		return transport != null && transport.isNpc() && "Travel".equals(transport.action)
			&& "Renu".equals(transport.target);
	}

	boolean available(Client client, Transport transport)
	{
		return available(client.getWorldType().contains(WorldType.MEMBERS), client.getVarbitValue(VarbitID.VMQ2),
			client.getVarpValue(VarPlayerID.QUETZALS_UNLOCKED), transport);
	}

	boolean available(boolean members, int questProgress, int unlocked, Transport transport)
	{
		// Renu is usable after feeding her during Twilight's Promise, before quest completion.
		return members && questProgress >= 38 && unlocked >= 0 && isQuetzal(transport)
			&& siteAvailable(transport.approach, unlocked) && siteAvailable(transport.landing, unlocked);
	}

	private boolean siteAvailable(WorldPoint point, int unlocked)
	{
		Integer mask = sites.get(point);
		return mask != null && (unlocked & mask) == mask;
	}

	static boolean menuOpen(Client client)
	{
		return menuOpen(client, INTERFACE);
	}

	static boolean menuOpen(Client client, int interfaceId)
	{
		Widget icons = client.getWidget(interfaceId, ICONS);
		return icons != null && !icons.isHidden();
	}

	static boolean flightActive(Client client)
	{
		Widget fade = client.getWidget(174, 0);
		return fade != null && !fade.isHidden();
	}

	WorldPoint questDestination(Client client, String instruction)
	{
		if (instruction == null) { return null; }
		String text = net.runelite.client.util.Text.removeTags(instruction).trim()
			.toLowerCase(java.util.Locale.ROOT).replaceAll("[.!]$", "");
		String prefix = "travel with renu to ";
		if (!text.startsWith(prefix)) { return null; }
		WorldPoint selected = null;
		for (WorldPoint site : sites.keySet())
		{
			String name = destinationName(client, site);
			if (name != null && text.substring(prefix.length()).replaceFirst("^the ", "")
				.equals(name.toLowerCase(java.util.Locale.ROOT).replaceFirst("^the ", "")))
			{
				if (selected != null) { throw new IllegalStateException("More than one quetzal destination matches the quest. Select it manually, then resume."); }
				selected = site;
			}
		}
		if (selected == null) { throw new IllegalStateException("Cannot identify the quest's quetzal destination. Select it manually, then resume."); }
		return selected;
	}

	Widget destinationWidget(Client client, Transport transport)
	{
		return available(client, transport) ? destinationWidget(client, transport.landing, INTERFACE) : null;
	}

	Widget destinationWidget(Client client, WorldPoint landing, int interfaceId)
	{
		if (!siteAvailable(landing, client.getVarpValue(VarPlayerID.QUETZALS_UNLOCKED))
			|| !menuOpen(client, interfaceId)) { return null; }
		String name = destinationName(client, landing);
		Widget[] icons = client.getWidget(interfaceId, ICONS).getDynamicChildren();
		if (name == null || icons == null) { return null; }
		Widget selected = null;
		for (Widget icon : icons)
		{
			if (FairyRings.permitted(client, icon) && icon.getIndex() >= 0
				&& name.equals(icon.getActions()[0]))
			{
				if (selected != null) { return null; }
				selected = icon;
			}
		}
		return selected;
	}

	private static String destinationName(Client client, WorldPoint destination)
	{
		List<Integer> rows = client.getDBTableRows(DBTableID.Quetzal.ID);
		if (rows == null) { return null; }
		String selected = null;
		for (int row : rows)
		{
			Object[] coord = client.getDBTableField(row, DBTableID.Quetzal.COL_COORD, 0);
			if (coord == null || coord.length != 1 || !(coord[0] instanceof Integer)
				|| (Integer) coord[0] < 0 || !destination.equals(WorldPoint.fromCoord((Integer) coord[0]))) { continue; }
			Object[] name = client.getDBTableField(row, DBTableID.Quetzal.COL_NAME, 0);
			if (selected != null || name == null || name.length != 1 || !(name[0] instanceof String)) { return null; }
			selected = (String) name[0];
		}
		return selected;
	}
}
