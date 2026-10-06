package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;

final class QuestDestination
{
	private static final int[] DX = {-1, 1, 0, 0};
	private static final int[] DY = {0, 0, -1, 1};
	private static final int[] WALL = {CollisionDataFlag.BLOCK_MOVEMENT_WEST,
		CollisionDataFlag.BLOCK_MOVEMENT_EAST, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH,
		CollisionDataFlag.BLOCK_MOVEMENT_NORTH};

	private QuestDestination() { }

	static boolean loaded(Client client, QuestStepTarget.Target target)
	{
		WorldPoint anchor = target.livePoint == null ? target.point : target.livePoint;
		WorldView view = client.getTopLevelWorldView();
		if (view == null) { return false; }
		int plane = anchor.getPlane(), x = anchor.getX() - view.getBaseX(), y = anchor.getY() - view.getBaseY();
		CollisionData[] maps = view.getCollisionMaps();
		Tile[][][] scene = view.getScene() == null ? null : view.getScene().getTiles();
		return maps != null && plane >= 0 && plane < maps.length && maps[plane] != null
			&& scene != null && plane < scene.length && !unavailable(maps[plane].getFlags(), x, y)
			&& tile(scene[plane], x, y) != null;
	}

	private static boolean unavailable(int[][] flags, int x, int y)
	{
		return !inside(flags, x, y) || flags[x][y] == 0xFFFFFF || (flags[x][y] & 0x1000000) != 0;
	}

	static List<WorldPoint> resolve(Client client, QuestStepTarget.Target target, WorldPathfinder cached)
	{
		if ((target.npc || target.object) && target.livePoint == null
			&& client.getLocalPlayer() != null && client.getTopLevelWorldView() != null && !client.getTopLevelWorldView().isInstance()
			&& target.point.distanceTo(client.getLocalPlayer().getWorldLocation()) > 12)
		{
			// Staging only: load the target area, then re-resolve its live footprint and room.
			List<WorldPoint> staging = QuestNpcApproach.waypoints(target.point, 8);
			staging.removeIf(point -> !cached.isStandable(point) || cached.isWilderness(point));
			staging.sort(Comparator.comparingInt(point -> point.distanceTo(client.getLocalPlayer().getWorldLocation())));
			return staging;
		}
		return resolve(client, target, cached, true);
	}

	static List<WorldPoint> roomApproaches(Client client, QuestStepTarget.Target target, WorldPathfinder cached)
	{
		return resolve(client, target, cached, false);
	}

	private static List<WorldPoint> resolve(Client client, QuestStepTarget.Target target,
		WorldPathfinder cached, boolean closestOnly)
	{
		WorldPoint reference = target.point;
		WorldPoint anchor = target.livePoint == null ? reference : target.livePoint;
		WorldView view = client.getTopLevelWorldView();
		if (view == null) { return Collections.emptyList(); }
		int plane = anchor.getPlane();
		CollisionData[] maps = view.getCollisionMaps();
		Tile[][][] scene = view.getScene() == null ? null : view.getScene().getTiles();
		byte[][][] settings = view.getTileSettings();
		int x = anchor.getX() - view.getBaseX(), y = anchor.getY() - view.getBaseY();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null
			|| scene == null || plane >= scene.length || settings == null || plane >= settings.length)
		{
			return Collections.emptyList();
		}
		int[][] flags = maps[plane].getFlags();
		boolean pendingScene = unavailable(flags, x, y);
		if (pendingScene && view.isInstance()) { return Collections.emptyList(); }
		Tile origin = tile(scene[plane], x, y);
		if (origin == null && !pendingScene) { return Collections.emptyList(); }

		int minX = x, maxX = x, minY = y, maxY = y;
		TileObject object = target.liveObject;
		if (target.npc && target.liveNpc == null || target.object && target.liveObject == null)
		{
			return Collections.emptyList();
		}
		if (object == null && target.liveNpc == null && !pendingScene && origin.getGameObjects() != null)
		{
			for (GameObject candidate : origin.getGameObjects())
			{
				if (candidate != null) { object = candidate; break; }
			}
		}
		if (object instanceof GameObject)
		{
			Point min = ((GameObject) object).getSceneMinLocation();
			Point max = ((GameObject) object).getSceneMaxLocation();
			if (min == null || max == null) { return Collections.emptyList(); }
			minX = min.getX(); minY = min.getY(); maxX = max.getX(); maxY = max.getY();
		}
		else if (target.liveNpc != null && target.liveNpc.getComposition() != null)
		{
			int size = Math.max(1, target.liveNpc.getComposition().getSize());
			maxX += size - 1; maxY += size - 1;
		}
		if (pendingScene)
		{
			return Collections.emptyList();
		}

		boolean entity = object != null || target.npc || target.object || occupied(view, origin, x, y, plane);
		int footprintMask = target.liveNpc == null && target.liveObject == null ? CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
			: CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		RoomDoors doors = new RoomDoors(client, scene[plane], flags);
		if ((!entity && !clear(flags[x][y])) || x < minX || x > maxX || y < minY || y > maxY
			|| !inside(flags, minX, minY) || !inside(flags, maxX, maxY)
			|| !roomFloor(flags, x, y, minX, maxX, minY, maxY, footprintMask)
			|| roof(settings[plane], x, y) < 0 || roomBoundary(client, origin, object, doors))
		{
			return Collections.emptyList();
		}
		Set<WorldPoint> room = fill(client, view, flags, scene[plane], settings[plane], anchor,
			minX, maxX, minY, maxY, footprintMask, object, doors);
		for (int nx = minX; nx <= maxX; nx++)
		{
			for (int ny = minY; ny <= maxY; ny++)
			{
				if (!room.contains(new WorldPoint(view.getBaseX() + nx, view.getBaseY() + ny, plane)))
				{
					return Collections.emptyList();
				}
			}
		}
		List<WorldPoint> candidates = new ArrayList<>();
		int roomRoof = -1;
		for (WorldPoint point : room)
		{
			int nx = point.getX() - view.getBaseX(), ny = point.getY() - view.getBaseY();
			if (entity && nx >= minX && nx <= maxX && ny >= minY && ny <= maxY) { continue; }
			int floorRoof = roof(settings[plane], nx, ny);
			if (roomRoof >= 0 && roomRoof != floorRoof) { return Collections.emptyList(); }
			roomRoof = floorRoof;
			Tile floor = tile(scene[plane], nx, ny);
			if (clear(flags[nx][ny]) && floor != null && !occupied(view, floor, nx, ny, plane))
			{
				candidates.add(point);
			}
		}
		candidates.sort(Comparator.comparingLong(point -> distance(point, reference)));
		if (closestOnly && !candidates.isEmpty())
		{
			long closest = distance(candidates.get(0), reference);
			candidates.removeIf(point -> distance(point, reference) != closest);
		}
		return candidates;
	}

	private static Set<WorldPoint> fill(Client client, WorldView view, int[][] flags, Tile[][] tiles,
		byte[][] settings, WorldPoint start, int minX, int maxX, int minY, int maxY, int footprintMask,
		TileObject target, RoomDoors doors)
	{
		Set<WorldPoint> room = new LinkedHashSet<>();
		ArrayDeque<WorldPoint> queue = new ArrayDeque<>();
		queue.add(start); room.add(start);
		while (!queue.isEmpty())
		{
			WorldPoint point = queue.removeFirst();
			int x = point.getX() - view.getBaseX(), y = point.getY() - view.getBaseY();
			if (doors.unknown.get(x + y * flags.length)) { return Collections.emptySet(); }
			for (int i = 0; i < DX.length; i++)
			{
				int nx = x + DX[i], ny = y + DY[i];
				WorldPoint next = point.dx(DX[i]).dy(DY[i]);
				Tile floor = tile(tiles, nx, ny);
				if (!room.contains(next) && roomFloor(flags, nx, ny, minX, maxX, minY, maxY, footprintMask)
					&& (flags[x][y] & WALL[i]) == 0 && (flags[nx][ny] & WALL[i ^ 1]) == 0
					&& !doors.edges.get(ScenePathfinder.edgeKey(flags.length, flags[0].length, x, y, nx, ny))
					&& (roof(settings, x, y) == roof(settings, nx, ny)
						|| target != null && (footprintWithoutFloor(tiles, x, y, minX, maxX, minY, maxY)
							|| footprintWithoutFloor(tiles, nx, ny, minX, maxX, minY, maxY)))
					&& floor != null)
				{
					if (doors.unknown.get(nx + ny * flags.length)) { continue; }
					if (roomBoundary(client, floor, target, doors)) { continue; }
					room.add(next); queue.add(next);
				}
			}
		}
		return room;
	}

	private static boolean footprintWithoutFloor(Tile[][] tiles, int x, int y,
		int minX, int maxX, int minY, int maxY)
	{
		if (x < minX || x > maxX || y < minY || y > maxY)
		{
			return false;
		}
		Tile floor = tile(tiles, x, y);
		return floor != null && floor.getSceneTilePaint() == null && floor.getSceneTileModel() == null;
	}

	private static boolean roomFloor(int[][] flags, int x, int y, int minX, int maxX, int minY, int maxY,
		int footprintMask)
	{
		if (unavailable(flags, x, y)) { return false; }
		int value = flags[x][y];
		// A live entity may occupy unstandable floor; this mask never removes walls or permits player destinations.
		if (x >= minX && x <= maxX && y >= minY && y <= maxY)
		{
			value &= ~footprintMask;
		}
		return clear(value);
	}

	private static boolean occupied(WorldView view, Tile tile, int x, int y, int plane)
	{
		if (tile.getGameObjects() != null)
		{
			for (GameObject object : tile.getGameObjects()) { if (object != null) { return true; } }
		}
		for (NPC npc : view.npcs())
		{
			if (npc == null || npc.getWorldLocation() == null || npc.getWorldLocation().getPlane() != plane) { continue; }
			WorldPoint point = npc.getWorldLocation();
			int size = npc.getComposition() == null ? 1 : Math.max(1, npc.getComposition().getSize());
			int nx = point.getX() - view.getBaseX(), ny = point.getY() - view.getBaseY();
			if (x >= nx && x < nx + size && y >= ny && y < ny + size) { return true; }
		}
		return false;
	}

	private static boolean roomBoundary(Client client, Tile tile, TileObject target, RoomDoors doors)
	{
		if (!doors.walls.contains(tile.getWallObject()) && roomBoundary(client, tile.getWallObject(), target)
			|| roomBoundary(client, tile.getDecorativeObject(), target))
		{
			return true;
		}
		if (tile.getGameObjects() != null)
		{
			for (GameObject object : tile.getGameObjects())
			{
				if (roomBoundary(client, object, target)) { return true; }
			}
		}
		return false;
	}

	private static final class RoomDoors
	{
		private static final int[] ORIENTATION = {1, 4, 8, 2};
		private final BitSet edges = new BitSet();
		private final BitSet unknown = new BitSet();
		private final Set<WallObject> walls = new LinkedHashSet<>();

		private RoomDoors(Client client, Tile[][] tiles, int[][] flags)
		{
			int width = flags.length, height = flags[0].length;
			for (int x = 0; x < tiles.length; x++)
			{
				for (int y = 0; y < tiles[x].length; y++)
				{
					Tile tile = tiles[x][y];
					WallObject wall = tile == null ? null : tile.getWallObject();
					if (wall == null || !roomBoundary(client, wall, null)) { continue; }
					net.runelite.api.ObjectComposition definition = client.getObjectDefinition(wall.getId());
					if (definition != null && definition.getImpostorIds() != null) { definition = definition.getImpostor(); }
					if (definition == null || definition.getActions() == null) { continue; }
					List<String> actions = Arrays.asList(definition.getActions());
					boolean open = actions.contains("Close");
					int direction = -1;
					for (int i = 0; i < ORIENTATION.length; i++)
					{
						if (wall.getOrientationA() == ORIENTATION[i] && wall.getOrientationB() == 0) { direction = i; }
					}
					if (direction >= 0 && !open && actions.contains("Open"))
					{
						ScenePathfinder.visitOrientationEdges(width, height, x, y, wall.getOrientationA(), edges::set);
						walls.add(wall);
						continue;
					}
					if (!open) { continue; }
					BitSet gaps = new BitSet();
					if (direction >= 0)
					{
						// A swung panel meets its doorway at one endpoint, on either side of the panel.
						for (int side = 0; side < 2; side++)
						{
							int sx = x + side * DX[direction], sy = y + side * DY[direction];
							for (int i = 0; i < DX.length; i++)
							{
								if (i / 2 == direction / 2) { continue; }
								int nx = sx + DX[i], ny = sy + DY[i];
								if (unavailable(flags, sx, sy) || unavailable(flags, nx, ny)
									|| (flags[sx][sy] & WALL[i]) != 0 || (flags[nx][ny] & WALL[i ^ 1]) != 0) { continue; }
								if (solidEdge(client, tiles, sx + DY[i], sy + DX[i], i)
									&& solidEdge(client, tiles, sx - DY[i], sy - DX[i], i))
								{
									gaps.set(ScenePathfinder.edgeKey(width, height, sx, sy, nx, ny));
								}
							}
						}
					}
					if (gaps.cardinality() == 1)
					{
						edges.or(gaps);
						walls.add(wall);
					}
					else
					{
						// ponytail: single-panel gaps only; reject other hinges until their geometry is verified.
						unknown.set(x + y * width);
						for (int i = 0; i < DX.length; i++)
						{
							if ((wall.getOrientationA() & ORIENTATION[i]) != 0 && inside(flags, x + DX[i], y + DY[i]))
							{
								unknown.set(x + DX[i] + (y + DY[i]) * width);
							}
						}
					}
				}
			}
		}

		private static boolean solidEdge(Client client, Tile[][] tiles, int x, int y, int direction)
		{
			return solidWall(client, tile(tiles, x, y), ORIENTATION[direction])
				|| solidWall(client, tile(tiles, x + DX[direction], y + DY[direction]), ORIENTATION[direction ^ 1]);
		}

		private static boolean solidWall(Client client, Tile tile, int orientation)
		{
			WallObject wall = tile == null ? null : tile.getWallObject();
			return wall != null && !roomBoundary(client, wall, null)
				&& ((wall.getOrientationA() | wall.getOrientationB()) & orientation) != 0;
		}
	}

	private static boolean roomBoundary(Client client, TileObject object, TileObject target)
	{
		if (object == null) { return false; }
		if (object == target || target != null && object.getId() == target.getId()
			&& Objects.equals(object.getWorldLocation(), target.getWorldLocation())) { return false; }
		net.runelite.api.ObjectComposition definition = client.getObjectDefinition(object.getId());
		if (definition == null) { return true; }
		if (definition.getImpostorIds() != null) { definition = definition.getImpostor(); }
		if (definition == null) { return false; }
		String name = definition.getName();
		return name == null || name.toLowerCase(java.util.Locale.ROOT).contains("door")
			|| name.toLowerCase(java.util.Locale.ROOT).contains("gate");
	}

	private static boolean clear(int flags) { return (flags & (CollisionDataFlag.BLOCK_MOVEMENT_FULL | 0x1000000)) == 0; }
	private static boolean inside(int[][] flags, int x, int y) { return x >= 0 && y >= 0 && x < flags.length && y < flags[x].length; }
	private static Tile tile(Tile[][] tiles, int x, int y) { return x >= 0 && y >= 0 && x < tiles.length && y < tiles[x].length ? tiles[x][y] : null; }
	private static int roof(byte[][] settings, int x, int y) { return x >= 0 && y >= 0 && x < settings.length && y < settings[x].length ? settings[x][y] & Constants.TILE_FLAG_UNDER_ROOF : -1; }
	private static long distance(WorldPoint a, WorldPoint b) { long x = a.getX() - b.getX(), y = a.getY() - b.getY(); return x * x + y * y; }
}
