package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.IntConsumer;
import net.runelite.api.CollisionDataFlag;

final class ScenePathfinder
{
	private static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
	private static final int[] DY = {0, 0, -1, 1, -1, -1, 1, 1};
	private static final int BLOCK_WEST = 0x1240108;
	private static final int BLOCK_EAST = 0x1240180;
	private static final int BLOCK_SOUTH = 0x1240102;
	private static final int BLOCK_NORTH = 0x1240120;
	private static final int BLOCK_SOUTH_WEST = 0x124010e;
	private static final int BLOCK_SOUTH_EAST = 0x1240183;
	private static final int BLOCK_NORTH_WEST = 0x1240138;
	private static final int BLOCK_NORTH_EAST = 0x12401e0;

	private ScenePathfinder()
	{
	}

	static int[] find(int[][] flags, BitSet doorEdges, int startX, int startY, int targetX, int targetY)
	{
		if (!inBounds(flags, startX, startY) || !inBounds(flags, targetX, targetY))
		{
			return new int[0];
		}

		final int width = flags.length;
		final int height = flags[0].length;
		final int start = pack(startX, startY, width);
		final int target = pack(targetX, targetY, width);
		final int[] previous = new int[width * height];
		final int[] queue = new int[width * height];
		Arrays.fill(previous, -2);
		previous[start] = -1;

		int head = 0;
		int tail = 0;
		queue[tail++] = start;
		while (head < tail && previous[target] == -2)
		{
			final int current = queue[head++];
			final int x = unpackX(current, width);
			final int y = unpackY(current, width);
			for (int i = 0; i < DX.length; i++)
			{
				final int nextX = x + DX[i];
				final int nextY = y + DY[i];
				if (!inBounds(flags, nextX, nextY))
				{
					continue;
				}

				final int next = pack(nextX, nextY, width);
				if (previous[next] != -2 || !canStep(flags, doorEdges, x, y, nextX, nextY))
				{
					continue;
				}

				previous[next] = current;
				queue[tail++] = next;
			}
		}

		if (previous[target] == -2)
		{
			return new int[0];
		}

		int length = 1;
		for (int point = target; point != start; point = previous[point])
		{
			length++;
		}

		final int[] path = new int[length];
		int point = target;
		for (int i = length - 1; i >= 0; i--)
		{
			path[i] = point;
			point = previous[point];
		}
		return path;
	}

	static boolean canStep(int[][] flags, BitSet doorEdges, int fromX, int fromY, int toX, int toY)
	{
		final int dx = toX - fromX;
		final int dy = toY - fromY;
		if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || dx == 0 && dy == 0)
		{
			return false;
		}
		if (dx == 0 || dy == 0)
		{
			return canCrossCardinal(flags, doorEdges, fromX, fromY, toX, toY);
		}

		final int diagonalMask;
		if (dx < 0)
		{
			diagonalMask = dy < 0 ? BLOCK_SOUTH_WEST : BLOCK_NORTH_WEST;
		}
		else
		{
			diagonalMask = dy < 0 ? BLOCK_SOUTH_EAST : BLOCK_NORTH_EAST;
		}
		final int horizontalEdge = edgeKey(flags.length, flags[0].length,
			fromX, fromY, fromX + dx, fromY);
		final int verticalEdge = edgeKey(flags.length, flags[0].length,
			fromX, fromY, fromX, fromY + dy);
		if (doorEdges.get(horizontalEdge) || doorEdges.get(verticalEdge))
		{
			return false;
		}
		return (flags[toX][toY] & diagonalMask) == 0
			&& canCrossCardinal(flags, doorEdges, fromX, fromY, fromX + dx, fromY)
			&& canCrossCardinal(flags, doorEdges, fromX, fromY, fromX, fromY + dy);
	}

	static boolean canClearObjectStep(int[][] flags, int fromX, int fromY, int toX, int toY)
	{
		if (!inBounds(flags, fromX, fromY) || !inBounds(flags, toX, toY)
			|| Math.abs(toX - fromX) + Math.abs(toY - fromY) != 1) { return false; }
		int mask = toX > fromX ? BLOCK_EAST : toX < fromX ? BLOCK_WEST
			: toY > fromY ? BLOCK_NORTH : BLOCK_SOUTH;
		return (flags[toX][toY] & (mask & ~CollisionDataFlag.BLOCK_MOVEMENT_OBJECT)) == 0;
	}

	static int edgeKey(int width, int height, int fromX, int fromY, int toX, int toY)
	{
		final int dx = toX - fromX;
		final int dy = toY - fromY;
		if (Math.abs(dx) + Math.abs(dy) != 1)
		{
			return -1;
		}

		final int edgeX = dx < 0 ? toX : fromX;
		final int edgeY = dy < 0 ? toY : fromY;
		if (edgeX < 0 || edgeY < 0 || edgeX >= width || edgeY >= height)
		{
			return -1;
		}
		if (dx != 0 && edgeX >= width - 1 || dy != 0 && edgeY >= height - 1)
		{
			return -1;
		}
		return (pack(edgeX, edgeY, width) << 1) | (dy != 0 ? 1 : 0);
	}

	static int doorTileKey(int width, int height, int x, int y)
	{
		return width * height * 2 + pack(x, y, width);
	}

	static void visitOrientationEdges(int width, int height, int x, int y, int orientation, IntConsumer consumer)
	{
		for (int bit = 1; bit <= 128; bit <<= 1)
		{
			if ((orientation & bit) == 0)
			{
				continue;
			}

			switch (bit)
			{
				case 1:
					acceptEdge(width, height, x, y, x - 1, y, consumer);
					break;
				case 2:
					acceptEdge(width, height, x, y, x, y + 1, consumer);
					break;
				case 4:
					acceptEdge(width, height, x, y, x + 1, y, consumer);
					break;
				case 8:
					acceptEdge(width, height, x, y, x, y - 1, consumer);
					break;
				case 16:
					acceptEdge(width, height, x, y, x - 1, y, consumer);
					acceptEdge(width, height, x, y, x, y + 1, consumer);
					break;
				case 32:
					acceptEdge(width, height, x, y, x, y + 1, consumer);
					acceptEdge(width, height, x, y, x + 1, y, consumer);
					break;
				case 64:
					acceptEdge(width, height, x, y, x + 1, y, consumer);
					acceptEdge(width, height, x, y, x, y - 1, consumer);
					break;
				case 128:
					acceptEdge(width, height, x, y, x, y - 1, consumer);
					acceptEdge(width, height, x, y, x - 1, y, consumer);
					break;
				default:
					break;
			}
		}
	}

	static int pack(int x, int y, int width)
	{
		return y * width + x;
	}

	static int unpackX(int point, int width)
	{
		return point % width;
	}

	static int unpackY(int point, int width)
	{
		return point / width;
	}

	private static boolean canCrossCardinal(int[][] flags, BitSet doorEdges, int fromX, int fromY, int toX, int toY)
	{
		if (!inBounds(flags, fromX, fromY) || !inBounds(flags, toX, toY))
		{
			return false;
		}

		final int movementMask;
		if (toX > fromX)
		{
			movementMask = BLOCK_EAST;
		}
		else if (toX < fromX)
		{
			movementMask = BLOCK_WEST;
		}
		else if (toY > fromY)
		{
			movementMask = BLOCK_NORTH;
		}
		else
		{
			movementMask = BLOCK_SOUTH;
		}

		if ((flags[toX][toY] & movementMask) == 0)
		{
			return true;
		}

		final int key = edgeKey(flags.length, flags[0].length, fromX, fromY, toX, toY);
		return key >= 0 && doorEdges.get(key)
			&& isOpenableDoorTile(flags, doorEdges, fromX, fromY)
			&& isOpenableDoorTile(flags, doorEdges, toX, toY);
	}

	private static boolean isOpenableDoorTile(int[][] flags, BitSet doorEdges, int x, int y)
	{
		return (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0
			|| doorEdges.get(doorTileKey(flags.length, flags[0].length, x, y));
	}

	private static void acceptEdge(int width, int height, int fromX, int fromY, int toX, int toY, IntConsumer consumer)
	{
		final int key = edgeKey(width, height, fromX, fromY, toX, toY);
		if (key >= 0)
		{
			consumer.accept(key);
		}
	}

	private static boolean inBounds(int[][] flags, int x, int y)
	{
		return flags != null && flags.length > 0 && flags[0] != null
			&& x >= 0 && x < flags.length && y >= 0 && y < flags[0].length;
	}
}
