package net.runelite.client.plugins.microbot.efficientwalker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.efficientwalker.LocalTransportCatalog.Transport;

final class SpiritTrees
{
	static final String RESOURCE = "/net/runelite/client/plugins/microbot/efficientwalker/spirit_trees.tsv";
	static final String UPSTREAM_REVISION = "b56866cbffbd42eef10e51411eb0e5f932b5009b";
	private static final Pattern LABEL = Pattern.compile("<col=(?:735a28|ffffff)>([1-9A-E])</col>: (.*)");
	private final Map<WorldPoint, Destination> destinations = new LinkedHashMap<>();
	private final Map<WorldPoint, Destination> origins = new HashMap<>();
	private final List<Transport> transports = new ArrayList<>();

	static SpiritTrees load(Reader input) throws IOException
	{
		SpiritTrees trees = new SpiritTrees();
		List<String[]> approaches = new ArrayList<>();
		BufferedReader reader = new BufferedReader(input);
		String line;
		while ((line = reader.readLine()) != null)
		{
			if (line.isEmpty() || line.startsWith("#")) { continue; }
			String[] row = line.split("\t", -1);
			if (row.length != 8) { throw new IOException("Invalid spirit tree data row"); }
			if (!row[0].isEmpty()) { approaches.add(row); }
			else if (!row[1].isEmpty())
			{
				String[] label = row[6].split(": ", 2);
				if (label.length != 2 || label[0].length() != 1) { throw new IOException("Invalid spirit tree destination"); }
				WorldPoint landing = LocalTransportCatalog.parsePoint(row[1]);
				if (trees.destinations.put(landing, new Destination(label[0], label[1], landing)) != null)
				{
					throw new IOException("Duplicate spirit tree destination");
				}
			}
		}
		if (trees.destinations.size() != 14) { throw new IOException("Incomplete spirit tree network"); }
		for (String[] row : approaches)
		{
			WorldPoint approach = LocalTransportCatalog.parsePoint(row[0]);
			Destination origin = trees.destinations.values().stream()
				.filter(d -> d.landing.distanceTo(approach) <= 6).findFirst().orElse(null);
			if (origin == null) { throw new IOException("Unknown spirit tree origin"); }
			// The upstream house point is a template, not a routable world coordinate.
			if (origin.key.equals("C")) { continue; }
			trees.origins.put(approach, origin);
			int id = Integer.parseInt(row[2].substring(row[2].lastIndexOf(' ') + 1));
			if (origin.key.equals("E")) { id = 26263; }
			for (Destination destination : trees.destinations.values())
			{
				if (destination == origin || destination.key.equals("C")) { continue; }
				trees.transports.add(LocalTransportCatalog.transport(approach, destination.landing,
					"Travel", "Spirit tree", id));
			}
		}
		return trees;
	}

	List<Transport> transports() { return Collections.unmodifiableList(transports); }

	static boolean isTree(Transport transport)
	{
		return transport != null && "Travel".equals(transport.action) && "Spirit tree".equals(transport.target);
	}

	boolean available(Client client, EfficientWalkerConfig config, Transport transport)
	{
		Destination origin = origins.get(transport.approach);
		Destination destination = destinations.get(transport.landing);
		return client.getWorldType().contains(WorldType.MEMBERS) && client.getVarpValue(111) >= 9
			&& origin != null && destination != null && unlocked(client, config, origin)
			&& unlocked(client, config, destination) && (!origin.key.equals("2") || client.getVarpValue(150) >= 160);
	}

	private boolean unlocked(Client client, EfficientWalkerConfig config, Destination destination)
	{
		switch (destination.key)
		{
			case "1": case "2": case "3": case "4": case "5": return true;
			case "6": return Quest.SONG_OF_THE_ELVES.getState(client) == QuestState.FINISHED;
			case "7": return config.spiritTreePortSarim();
			case "8": return config.spiritTreeEtceteria();
			case "9": return config.spiritTreeBrimhaven();
			case "A": return config.spiritTreeHosidius();
			case "B": return config.spiritTreeFarmingGuild();
			case "C": return false;
			case "D": return client.getVarbitValue(15288) >= 37;
			case "E": return client.getBoostedSkillLevel(Skill.SAILING) >= 58;
			default: return false;
		}
	}

	Widget destinationWidget(Client client, Transport transport)
	{
		Destination destination = destinations.get(transport.landing);
		Map<String, Widget> menu = menu(client);
		if (menu == null || destination == null) { return null; }
		Widget widget = menu.get(destination.key);
		if (widget == null || disabled(widget.getText()))
		{
			throw new DestinationUnavailable("Spirit tree to " + destination.name + " is unavailable.");
		}
		return widget;
	}

	private Map<String, Widget> menu(Client client)
	{
		for (int[] component : new int[][]{{187, 3}, {947, 9}})
		{
			Widget container = client.getWidget(component[0], component[1]);
			if (container == null || container.isHidden() || container.getDynamicChildren() == null) { continue; }
			Map<String, Widget> rows = new HashMap<>();
			for (Widget child : container.getDynamicChildren())
			{
				if (child == null || child.isHidden() || child.getText() == null) { continue; }
				Matcher label = LABEL.matcher(child.getText());
				if (!label.matches()) { continue; }
				String key = label.group(1);
				Destination destination = destinations.values().stream().filter(d -> d.key.equals(key)).findFirst().orElse(null);
				String name = label.group(2).replaceAll("<[^>]*>", "");
				if (destination == null)
				{
					if ("F".equals(key) && "Cancel".equals(name)) { continue; }
					return null;
				}
				if (!(destination.name.equals(name) || "C".equals(key) && name.startsWith("Your house ("))) { return null; }
				if (rows.put(key, child) != null) { return null; }
			}
			if (rows.size() == destinations.size()) { return rows; }
		}
		return null;
	}

	static boolean menuOpen(Client client)
	{
		Widget old = client.getWidget(187, 3), modern = client.getWidget(947, 9);
		return old != null && !old.isHidden() || modern != null && !modern.isHidden();
	}

	private static boolean disabled(String text) { return text.toLowerCase(java.util.Locale.ROOT).contains("<col=5f5f5f>"); }

	static final class Transit
	{
		int selectedAt = -1;
		private int landedAt = -1;
		boolean landed(int tick, boolean ready)
		{
			if (!ready || selectedAt < 0) { landedAt = -1; return false; }
			if (landedAt < 0) { landedAt = tick; }
			return tick > landedAt;
		}
	}

	static final class DestinationUnavailable extends IllegalStateException
	{
		DestinationUnavailable(String message) { super(message); }
	}

	private static final class Destination
	{
		final String key;
		final String name;
		final WorldPoint landing;
		Destination(String key, String name, WorldPoint landing)
		{
			this.key = key;
			this.name = name;
			this.landing = landing;
		}
	}
}
