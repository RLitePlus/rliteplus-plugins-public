package net.runelite.client.plugins.microbot.madangel;

import java.util.function.Predicate;
import net.runelite.api.coords.WorldPoint;

final class MadAngelMechanics
{
	private MadAngelMechanics() { }

	static boolean emergencyHp(int hp, int realHp, int percent, int food) { return food == 0 && realHp > 0 && hp * 100L <= realHp * (long) percent; }

	static boolean eatHp(int hp, int realHp, int percent) { return realHp > 0 && hp * 100L < realHp * (long) percent; }

	static boolean boss(int id) { return id == 16305 || id == 16307 || id == 16309 || id == 16311; }
	static boolean dead(int id) { return id == 16308 || id == 16312; }
	static boolean smite(int animation) { return animation == 4590 || animation == 8543; }
	static boolean cleave(int animation) { return animation >= 14429 && animation <= 14442 && animation != 14432 && animation != 14436; }
	static boolean known(int animation)
	{
		return animation == -1 || animation == 1991 || animation == 3321 || animation == 4588
			|| animation == 4589 || smite(animation) || animation >= 14429 && animation <= 14443 || animation == 14448;
	}

	static boolean swingsLeft(int animation)
	{
		return animation == 14431 || animation == 14433 || animation == 14434
			|| animation == 14439 || animation == 14440 || animation == 14441;
	}

	static int cleaveTicks(int animation) { return animation >= 14437 ? 4 : 6; }
	static int smiteTicks(int animation) { return animation == 8543 ? 16 : 9; }

	static boolean safe(WorldPoint tile, WorldPoint boss, int orientation, int animation)
	{
		int direction = ((orientation + 256) & 2047) / 512;
		int[] fx = {0, -1, 0, 1}, fy = {-1, 0, 1, 0};
		int dx = tile.getX() - boss.getX() - 1, dy = tile.getY() - boss.getY() - 1;
		int forward = dx * fx[direction] + dy * fy[direction];
		int right = dx * fy[direction] - dy * fx[direction];
		boolean transition = animation == 14431 || animation == 14435 || animation == 14439 || animation == 14442;
		return forward < -1 && right == 0 || !transition && forward == 0 && (swingsLeft(animation) ? right > 0 : right < 0);
	}

	static WorldPoint reposition(WorldPoint boss, WorldPoint anchor, Predicate<WorldPoint> standable)
	{
		for (WorldPoint rear : new WorldPoint[]{boss.dx(-1).dy(1), boss.dx(3).dy(1), boss.dx(1).dy(-1), boss.dx(1).dy(3)})
		{
			if (!standable.test(rear)) { return anchor; }
		}
		return null;
	}

	static WorldPoint dodge(WorldPoint player, WorldPoint boss, int orientation, int animation,
		Predicate<WorldPoint> reachable)
	{
		if (safe(player, boss, orientation, animation) && reachable.test(player)) { return player; }
		WorldPoint best = null;
		int score = Integer.MAX_VALUE;
		for (int x = -1; x <= 3; x++)
		{
			for (int y = -1; y <= 3; y++)
			{
				WorldPoint candidate = new WorldPoint(boss.getX() + x, boss.getY() + y, boss.getPlane());
				if (!safe(candidate, boss, orientation, animation) || !reachable.test(candidate)) { continue; }
				int cost = player.distanceTo(candidate) * 10 + (x >= 0 && x <= 2 && y >= 0 && y <= 2 ? 1 : 0);
				if (cost < score) { best = candidate; score = cost; }
			}
		}
		return best;
	}
}
