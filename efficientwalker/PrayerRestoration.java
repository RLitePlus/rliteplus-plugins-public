package net.runelite.client.plugins.microbot.efficientwalker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

final class PrayerRestoration
{
	static final String RESOURCE = "/net/runelite/client/plugins/microbot/efficientwalker/prayer_restoration.tsv";
	private static final int RADIUS = 2;
	private static final int LOCAL_SCAN_RADIUS = 8;
	private static final Set<String> PRAY_ACTIONS = Set.of("pray", "pray-at", "pray at");
	private static final Set<String> POOL_NAMES = Set.of(
		"pool of refreshment", "rejuvenation pool", "fancy rejuvenation pool", "ornate rejuvenation pool");
	private static final List<Area> AREAS = load();

	private PrayerRestoration() { }

	static List<WorldPoint> destinations(Client client, EfficientWalker walker)
	{
		boolean members = client.getWorldType().contains(net.runelite.api.WorldType.MEMBERS);
		Set<WorldPoint> points = new LinkedHashSet<>();
		for (Area area : AREAS)
		{
			if (area.members && !members || area.wilderness || WorldPathfinder.isWilderness(area.center)
				|| area.damage && !safeToUse(client, area.center))
			{
				continue;
			}
			for (int dx = -RADIUS; dx <= RADIUS; dx++)
			{
				for (int dy = -RADIUS; dy <= RADIUS; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) == 0
						|| Math.max(Math.abs(dx), Math.abs(dy)) == 1) { continue; }
					WorldPoint point = area.center.dx(dx).dy(dy);
					if (walker.isStandable(point)) { points.add(point); }
				}
			}
		}
		Interaction local = find(client);
		if (local != null) { return approaches(walker, local); }
		return Collections.unmodifiableList(new ArrayList<>(points));
	}

	static List<WorldPoint> approaches(EfficientWalker walker, Interaction interaction)
	{
		WorldPoint point = interaction.object.getWorldLocation();
		if (point == null) { return Collections.emptyList(); }
		return walker.questDestinations(new QuestStepTarget.Target(null, point, false, true, null, null, interaction.object));
	}

	static Interaction find(Client client)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null || client.getLocalPlayer() == null || client.getLocalPlayer().getWorldLocation() == null
			|| view.getScene() == null || view.getScene().getTiles() == null)
		{
			return null;
		}
		WorldPoint origin = client.getLocalPlayer().getWorldLocation();
		Tile[][][] tiles = view.getScene().getTiles();
		int plane = origin.getPlane();
		if (plane < 0 || plane >= tiles.length) { return null; }
		int sceneX = origin.getX() - view.getBaseX();
		int sceneY = origin.getY() - view.getBaseY();
		Interaction nearest = null;
		for (int x = Math.max(0, sceneX - LOCAL_SCAN_RADIUS); x <= Math.min(tiles[plane].length - 1, sceneX + LOCAL_SCAN_RADIUS); x++)
		{
			for (int y = Math.max(0, sceneY - LOCAL_SCAN_RADIUS); y <= Math.min(tiles[plane][x].length - 1, sceneY + LOCAL_SCAN_RADIUS); y++)
			{
				Tile tile = tiles[plane][x][y];
				if (tile == null) { continue; }
				List<TileObject> objects = new ArrayList<>();
				if (tile.getGameObjects() != null) { for (GameObject object : tile.getGameObjects()) { if (object != null) { objects.add(object); } } }
				if (tile.getWallObject() != null) { objects.add(tile.getWallObject()); }
				if (tile.getDecorativeObject() != null) { objects.add(tile.getDecorativeObject()); }
				if (tile.getGroundObject() != null) { objects.add(tile.getGroundObject()); }
				for (TileObject object : objects)
				{
					String action = action(client, object);
					if (action == null || object.getWorldLocation() == null || !safeToUse(client, object.getWorldLocation())) { continue; }
					if (nearest == null || object.getWorldLocation().distanceTo2D(origin) < nearest.object.getWorldLocation().distanceTo2D(origin))
					{
						nearest = new Interaction(object, action);
					}
				}
			}
		}
		return nearest;
	}

	static boolean safeToUse(Client client, WorldPoint point)
	{
		return point == null || !isDarkmeyer(point) || client.getBoostedSkillLevel(Skill.HITPOINTS) > 3;
	}

	private static boolean isDarkmeyer(WorldPoint point)
	{
		return point.getPlane() == 0 && point.distanceTo2D(new WorldPoint(3604, 3354, 0)) <= 5;
	}

	private static String action(Client client, TileObject object)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition != null && composition.getImpostorIds() != null) { composition = composition.getImpostor(); }
		if (composition == null || composition.getActions() == null) { return null; }
		String name = Text.removeTags(composition.getName() == null ? "" : composition.getName()).toLowerCase(Locale.ROOT);
		String[] actions = composition.getActions().clone();
		for (int i = 0; i < Math.min(5, actions.length); i++)
		{
			if (object.getOpOverride(i) != null) { actions[i] = object.getOpOverride(i); }
		}
		for (String candidate : actions)
		{
			if (candidate == null) { continue; }
			String normalized = Text.removeTags(candidate).trim().toLowerCase(Locale.ROOT);
			if (PRAY_ACTIONS.contains(normalized)) { return Text.removeTags(candidate); }
			if ("bask".equals(normalized) && object.getId() == 52405 && "shrine of ralos".equals(name)) { return Text.removeTags(candidate); }
			if ("drink".equals(normalized) && POOL_NAMES.contains(name)) { return Text.removeTags(candidate); }
		}
		return null;
	}

	private static List<Area> load()
	{
		List<Area> areas = new ArrayList<>();
		try (Reader input = LocalTransportCatalog.open(RESOURCE); BufferedReader reader = new BufferedReader(input))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (line.isEmpty() || line.startsWith("#")) { continue; }
				String[] row = line.split("\\t", -1);
				if (row.length != 6) { throw new IllegalArgumentException("Invalid prayer restoration row"); }
				WorldPoint point = new WorldPoint(Integer.parseInt(row[0]), Integer.parseInt(row[1]), Integer.parseInt(row[2]));
				areas.add(new Area(point, Boolean.parseBoolean(row[3]), Boolean.parseBoolean(row[4]), Boolean.parseBoolean(row[5])));
			}
			return Collections.unmodifiableList(areas);
		}
		catch (IOException | RuntimeException ex)
		{
			throw new IllegalStateException("Could not load prayer restoration data", ex);
		}
	}

	static final class Interaction
	{
		final TileObject object;
		final String action;

		private Interaction(TileObject object, String action)
		{
			this.object = object;
			this.action = action;
		}
	}

	private static final class Area
	{
		final WorldPoint center;
		final boolean members;
		final boolean wilderness;
		final boolean damage;

		private Area(WorldPoint center, boolean members, boolean wilderness, boolean damage)
		{
			this.center = center;
			this.members = members;
			this.wilderness = wilderness;
			this.damage = damage;
		}
	}
}
