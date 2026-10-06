package net.runelite.client.plugins.microbot.efficientwalker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPCComposition;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.efficientwalker.LocalTransportCatalog.Transport;
import net.runelite.client.util.Text;

final class CharterShips
{
	static final String UPSTREAM_REVISION = "b56866cbffbd42eef10e51411eb0e5f932b5009b";
	static final String CACHE_REVISION = "1c258b475480e5363e3895e7acda05f41ba89432";
	static final String RESOURCE_PREFIX = "/net/runelite/client/plugins/microbot/efficientwalker/charter_";
	static final String TARGET = "Trader Stan / Trader Crewmember";
	static final int MAP_INTERFACE = 72;
	static final int LIST_INTERFACE = 885;
	private static final int SAILING_CONTENT_RESTRICTION = 18166;
	private static final Set<String> SAILING_PORTS = Set.of("The Pandemonium", "The Summer Shore",
		"Red Rock", "Deepfin Point", "Port Roberts");
	private final Map<WorldPoint, Port> ports = new LinkedHashMap<>();
	private final Map<WorldPoint, Map<WorldPoint, Journey>> journeys = new LinkedHashMap<>();
	private final List<Transport> transports = new ArrayList<>();

	static CharterShips load(Reader portData, Reader npcData, Reader routeData) throws IOException
	{
		CharterShips ships = new CharterShips();
		for (String[] row : rows(portData))
		{
			if (row.length != 6 || row[0].isEmpty() || row[3].isEmpty()) { throw new IOException("Invalid charter port"); }
			WorldPoint upstreamDock = LocalTransportCatalog.parsePoint(row[1]);
			Port port = new Port(row[0], LocalTransportCatalog.parsePoint(row[5]),
				LocalTransportCatalog.parsePoint(row[2]), row[3], row[4]);
			if (upstreamDock.getPlane() != 0 || port.dock.getPlane() != 0 || port.arrival.getPlane() != 1
				|| ships.ports.putIfAbsent(upstreamDock, port) != null) { throw new IOException("Duplicate or invalid charter port"); }
		}
		List<Integer> npcIds = new ArrayList<>();
		for (String[] row : rows(npcData))
		{
			if (row.length != 1) { throw new IOException("Invalid charter NPC"); }
			int id = Integer.parseInt(row[0]);
			if (id < 0 || npcIds.contains(id)) { throw new IOException("Duplicate or invalid charter NPC"); }
			npcIds.add(id);
		}
		if (ships.ports.isEmpty() || npcIds.isEmpty()) { throw new IOException("Empty charter data"); }
		int[] ids = npcIds.stream().mapToInt(Integer::intValue).toArray();
		for (String[] row : rows(routeData))
		{
			if (row.length != 8 || !"Charter Trader Crewmember 1330".equals(row[2])
				|| !row[3].matches("COINS=[1-9][0-9]*")) { throw new IOException("Invalid charter route"); }
			Port origin = ships.ports.get(LocalTransportCatalog.parsePoint(row[0]));
			Port destination = ships.ports.get(LocalTransportCatalog.parsePoint(row[1]));
			if (origin == null || destination == null || origin == destination
				|| !destination.previous.equals(row[7]) || !connectionAllowed(origin.name, destination.name))
			{
				throw new IOException("Unknown or prohibited charter connection");
			}
			int baseFare = Integer.parseInt(row[3].substring(6));
			// The upstream table already includes Cabin Fever's discount on Mos Le'Harmless routes.
			if (mosLeHarmless(origin.name) || mosLeHarmless(destination.name)) { baseFare = Math.multiplyExact(baseFare, 2); }
			Journey journey = new Journey(origin, destination, baseFare);
			if (ships.journeys.computeIfAbsent(origin.dock, ignored -> new LinkedHashMap<>())
				.putIfAbsent(destination.arrival, journey) != null) { throw new IOException("Duplicate charter route"); }
			ships.transports.add(LocalTransportCatalog.npcTransport(origin.dock, destination.arrival, "Charter", TARGET, ids));
		}
		return ships;
	}

	private static List<String[]> rows(Reader input) throws IOException
	{
		List<String[]> rows = new ArrayList<>();
		BufferedReader reader = new BufferedReader(input);
		String line;
		while ((line = reader.readLine()) != null)
		{
			if (!line.trim().isEmpty() && !line.startsWith("#")) { rows.add(line.split("\t", -1)); }
		}
		return rows;
	}

	List<Transport> transports() { return Collections.unmodifiableList(transports); }

	static boolean isCharter(Transport transport)
	{
		return transport != null && "Charter".equals(transport.action) && TARGET.equals(transport.target);
	}

	Journey journey(Transport transport)
	{
		return isCharter(transport) ? journey(transport.approach, transport.landing) : null;
	}

	Journey journey(WorldPoint origin, WorldPoint arrival)
	{
		Map<WorldPoint, Journey> destinations = journeys.get(origin);
		return destinations == null ? null : destinations.get(arrival);
	}

	boolean available(Client client, Transport transport)
	{
		Journey journey = journey(transport);
		int fare = fare(client, journey);
		return unlocked(client, transport) && fare >= 0 && coins(client) >= fare;
	}

	boolean unlocked(Client client, Transport transport)
	{
		Journey journey = journey(transport);
		return journey != null && client.getWorldType().contains(WorldType.MEMBERS)
			&& journey.unlocked(client::getVarbitValue, client::getVarpValue);
	}

	String unavailableReason(Client client, Transport transport)
	{
		Journey journey = journey(transport);
		if (journey == null) { return "Charter route data is unavailable. Cancel and retry with current Walker data."; }
		if (!client.getWorldType().contains(WorldType.MEMBERS)) { return "Charter travel requires a members world. Switch worlds and retry."; }
		if (client.getVarbitValue(SAILING_CONTENT_RESTRICTION) != 0
			&& (SAILING_PORTS.contains(journey.origin.name) || SAILING_PORTS.contains(journey.destination.name)))
		{
			return "This charter route is blocked by the server's Sailing content restriction. Choose another destination or retry when the restriction is lifted.";
		}
		if (!journey.unlocked(client::getVarbitValue, client::getVarpValue))
		{
			return "Charter travel to " + journey.destination.name + " is locked. Complete the required quests or first visit, then retry.";
		}
		int fare = fare(client, journey);
		int wallet = coins(client);
		if (fare < 0 || wallet < 0) { return "Waiting for current inventory and equipment to verify charter supplies."; }
		int deficit = fare - wallet;
		return deficit > 0 ? "You need " + deficit + " more Coins to charter to " + journey.destination.name
			+ ". Withdraw the missing Coins and retry." : "Charter travel is unavailable. Check the dialogue and retry.";
	}

	String routeUnavailableReason(Client client, List<Transport> remaining)
	{
		long required = 0;
		for (Transport transport : remaining)
		{
			if (!isCharter(transport)) { continue; }
			Journey journey = journey(transport);
			if (journey == null || !client.getWorldType().contains(WorldType.MEMBERS)
				|| !journey.unlocked(client::getVarbitValue, client::getVarpValue))
			{
				return unavailableReason(client, transport);
			}
			int fare = fare(client, journey);
			if (fare < 0) { return null; }
			required += fare;
		}
		int wallet = coins(client);
		if (wallet < 0) { return null; }
		long deficit = required - wallet;
		return deficit > 0 ? "You need " + deficit + " more Coins for the remaining charter journeys. "
			+ "Withdraw the missing Coins and retry the walk." : null;
	}

	static int coins(Client client)
	{
		return quantity(client.getItemContainer(InventoryID.INVENTORY), 995);
	}

	static int fare(Client client, Journey journey)
	{
		int rings = quantity(client.getItemContainer(InventoryID.EQUIPMENT), 6465);
		return journey == null || rings < 0 ? -1 : journey.fare(client.getVarpValue(655) >= 140, rings > 0);
	}

	private static int quantity(ItemContainer container, int id)
	{
		return container == null || container.getItems() == null ? -1 : Arrays.stream(container.getItems()).filter(item -> item != null && item.getId() == id)
			.mapToInt(item -> item.getQuantity()).sum();
	}

	int actionIndex(Transport transport, NPCComposition composition)
	{
		Journey journey = journey(transport);
		if (journey == null || composition == null || composition.getActions() == null
			|| !("Trader Stan".equals(composition.getName()) || "Trader Crewmember".equals(composition.getName()))) { return -1; }
		List<String> actions = Arrays.asList(composition.getActions());
		int previous = actions.indexOf("Charter-to " + journey.destination.previous);
		return previous >= 0 ? previous : actions.indexOf("Charter");
	}

	Widget destinationWidget(Client client, Transport transport)
	{
		Journey journey = journey(transport);
		if (journey == null) { return null; }
		Widget match = null;
		// Cache scripts 8941 and 7336 author the same exact action in both menus.
		for (int[] container : new int[][]{{MAP_INTERFACE, 3}, {LIST_INTERFACE, 4}})
		{
			Widget parent = client.getWidget(container[0], container[1]);
			if (parent == null || parent.isHidden() || parent.getChildren() == null) { continue; }
			for (Widget child : parent.getChildren())
			{
				if (child == null || child.isHidden() || child.getActions() == null) { continue; }
				if (Arrays.asList(child.getActions()).contains(journey.destination.name))
				{
					if (match != null) { return null; }
					match = child;
				}
			}
		}
		return match;
	}

	static boolean menuOpen(Client client)
	{
		Widget map = client.getWidget(MAP_INTERFACE, 0);
		Widget list = client.getWidget(LIST_INTERFACE, 0);
		return map != null && !map.isHidden() || list != null && !list.isHidden();
	}

	Widget confirmationWidget(Client client, Transport transport, int expectedFare)
	{
		Journey journey = journey(transport);
		Widget menu = client.getWidget(219, 1);
		if (journey == null || menu == null || menu.isHidden()) { return null; }
		Widget[] children = menu.getChildren();
		if (children == null || children.length == 0 || children[0] == null || children[0].isHidden()
			|| children[0].getText() == null) { return null; }
		String quote = "Sail to " + journey.destination.name + " for "
			+ NumberFormat.getIntegerInstance(Locale.US).format(expectedFare) + " coins?";
		if (!quote.equals(Text.removeTags(children[0].getText()))) { return null; }
		List<String> answers = new ArrayList<>(Arrays.asList("Yes.", "Choose again.", "Yes, and don't ask again.", "Cancel."));
		Widget yes = null;
		for (int i = 1; i < children.length; i++)
		{
			Widget child = children[i];
			if (child == null || child.isHidden() || child.getText() == null || child.getText().isEmpty()) { continue; }
			String answer = Text.removeTags(child.getText());
			if (!answers.remove(answer)) { return null; }
			if (answer.equals("Yes.")) { yes = child; }
		}
		return answers.isEmpty() ? yes : null;
	}

	static final class Transit
	{
		final int coinsBefore;
		final int fare;
		int selectedAt;
		int confirmedAt = -1;
		boolean paid;
		int landedAt = -1;

		Transit(int tick, int coins, int fare, boolean shortcut)
		{
			if (fare <= 0 || coins < fare) { throw new IllegalStateException("Not enough Coins for charter travel. Withdraw the fare and retry."); }
			coinsBefore = coins;
			this.fare = fare;
			selectedAt = shortcut ? tick : -1;
		}

		void observeCoins(int coins)
		{
			if (coins < 0) { landedAt = -1; return; }
			int spent = coinsBefore - coins;
			if (spent == fare && selectedAt >= 0) { paid = true; }
			else if (paid || spent == fare)
			{
				throw new IllegalStateException("Charter payment state changed unexpectedly. Check your Coins and position before retrying.");
			}
			else if (spent != 0)
			{
				throw new IllegalStateException("Coins changed unexpectedly during charter travel. Check your inventory and position before retrying.");
			}
		}

		boolean landed(int tick, boolean ready)
		{
			if (!ready || !paid || selectedAt < 0) { landedAt = -1; return false; }
			if (landedAt < 0) { landedAt = tick; }
			return tick > landedAt;
		}

	}

	static boolean connectionAllowed(String origin, String destination)
	{
		if (origin.equals(destination)) { return false; }
		if ("Port Sarim".equals(origin) || "Port Sarim".equals(destination))
		{
			String other = "Port Sarim".equals(origin) ? destination : origin;
			if (List.of("Musa Point", "The Pandemonium", "Port Piscarilius", "Land's End").contains(other)) { return false; }
		}
		return !pair(origin, destination, "Musa Point", "The Pandemonium")
			&& !pair(origin, destination, "Port Piscarilius", "Land's End")
			&& !pair(origin, destination, "Mos Le'Harmless", "Port Phasmatys")
			&& !pair(origin, destination, "Aldarin", "Sunset Coast");
	}

	private static boolean pair(String origin, String destination, String first, String second)
	{
		return first.equals(origin) && second.equals(destination) || second.equals(origin) && first.equals(destination);
	}

	private static boolean mosLeHarmless(String name) { return "Mos Le'Harmless".equals(name); }

	static final class Journey
	{
		final Port origin;
		final Port destination;
		final int baseFare;

		Journey(Port origin, Port destination, int baseFare)
		{
			this.origin = origin;
			this.destination = destination;
			this.baseFare = baseFare;
		}

		int fare(boolean cabinFever, boolean activatedCharos) { return baseFare / (cabinFever ? 2 : 1) / (activatedCharos ? 2 : 1); }
		boolean unlocked(IntUnaryOperator varbit, IntUnaryOperator varp)
		{
			return origin.unlocked(varbit, varp) && destination.unlocked(varbit, varp);
		}
	}

	static final class Port
	{
		final String name;
		final WorldPoint dock;
		final WorldPoint arrival;
		final String previous;
		private final String checks;

		Port(String name, WorldPoint dock, WorldPoint arrival, String previous, String checks) throws IOException
		{
			if (!checks.isEmpty() && !checks.matches("[bp][0-9]+>=[0-9]+(;[bp][0-9]+>=[0-9]+)*"))
			{
				throw new IOException("Invalid charter unlock checks");
			}
			this.name = name;
			this.dock = dock;
			this.arrival = arrival;
			this.previous = previous;
			this.checks = checks;
		}

		boolean unlocked(IntUnaryOperator varbit, IntUnaryOperator varp)
		{
			if (SAILING_PORTS.contains(name) && varbit.applyAsInt(SAILING_CONTENT_RESTRICTION) != 0) { return false; }
			if (checks.isEmpty()) { return true; }
			for (String check : checks.split(";"))
			{
				String[] parts = check.substring(1).split(">=");
				if ((check.charAt(0) == 'b' ? varbit : varp).applyAsInt(Integer.parseInt(parts[0])) < Integer.parseInt(parts[1])) { return false; }
			}
			return true;
		}
	}
}
