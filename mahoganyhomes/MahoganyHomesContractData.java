package net.runelite.client.plugins.microbot.mahoganyhomes;

import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

enum MahoganyHomesContractData
{
	LARRY("Larry", 10418, new WorldPoint(3038, 3366, 0),
		new WorldArea(3033, 3360, 10, 9, 0),
		new int[]{0, 2, 2, 3, 1, 3, 1, 0}, new int[]{1, 0, 0, 0, 0, 0, 0, 0},
		new WorldPoint(3036, 3363, 0), new WorldPoint(3035, 3364, 1), List.of(
			new MahoganyHomesScript.Work(0, 40297, new WorldPoint(3039, 3367, 0)),
			new MahoganyHomesScript.Work(1, 40095, new WorldPoint(3041, 3365, 0)),
			new MahoganyHomesScript.Work(2, 40096, new WorldPoint(3041, 3364, 0)),
			new MahoganyHomesScript.Work(3, 40097, new WorldPoint(3038, 3364, 0)),
			new MahoganyHomesScript.Work(4, 40298, new WorldPoint(3041, 3362, 0)),
			new MahoganyHomesScript.Work(5, 40098, new WorldPoint(3038, 3363, 1)),
			new MahoganyHomesScript.Work(6, 40099, new WorldPoint(3034, 3364, 1)))),
	LEELA("Leela", 10423, new WorldPoint(1784, 3590, 0),
		new WorldArea(1781, 3589, 9, 8, 0),
		new int[]{2, 2, 0, 3, 3, 1, 2, 0}, new int[]{0, 0, 1, 0, 0, 0, 0, 0},
		new WorldPoint(1787, 3592, 0), new WorldPoint(1787, 3592, 1), List.of(
			new MahoganyHomesScript.Work(0, 40007, new WorldPoint(1784, 3594, 0)),
			new MahoganyHomesScript.Work(1, 40008, new WorldPoint(1782, 3592, 0)),
			new MahoganyHomesScript.Work(2, 40290, new WorldPoint(1786, 3594, 0)),
			new MahoganyHomesScript.Work(3, 40291, new WorldPoint(1782, 3590, 0)),
			new MahoganyHomesScript.Work(4, 40009, new WorldPoint(1782, 3590, 1)),
			new MahoganyHomesScript.Work(5, 40010, new WorldPoint(1786, 3590, 1)),
			new MahoganyHomesScript.Work(6, 40292, new WorldPoint(1788, 3590, 1)))),
	NORMAN("Norman", 3266, new WorldPoint(3036, 3345, 1),
		new WorldArea(3033, 3340, 10, 10, 0),
		new int[]{0, 1, 3, 3, 2, 2, 2, 0}, new int[]{1, 0, 0, 0, 0, 0, 0, 0},
		new WorldPoint(3035, 3345, 0), new WorldPoint(3035, 3345, 1), List.of(
			new MahoganyHomesScript.Work(0, 40296, new WorldPoint(3039, 3346, 0)),
			new MahoganyHomesScript.Work(1, 40089, new WorldPoint(3035, 3346, 0)),
			new MahoganyHomesScript.Work(2, 40090, new WorldPoint(3036, 3342, 0)),
			new MahoganyHomesScript.Work(3, 40091, new WorldPoint(3039, 3346, 1)),
			new MahoganyHomesScript.Work(4, 40092, new WorldPoint(3035, 3346, 1)),
			new MahoganyHomesScript.Work(5, 40093, new WorldPoint(3035, 3347, 1)),
			new MahoganyHomesScript.Work(6, 40094, new WorldPoint(3040, 3345, 1)))),
	BARBARA("Barbara", 10424, new WorldPoint(1747, 3538, 0),
		new WorldArea(1740, 3531, 15, 15, 0),
		new int[]{1, 0, 2, 3, 2, 1, 1, 0}, new int[]{0, 1, 0, 0, 0, 0, 0, 0},
		new WorldPoint(1747, 3538, 0), new WorldPoint(1747, 3538, 1), List.of(
			new MahoganyHomesScript.Work(0, 40011, new WorldPoint(1749, 3536, 0)),
			new MahoganyHomesScript.Work(1, 40293, new WorldPoint(1747, 3532, 0)),
			new MahoganyHomesScript.Work(2, 40012, new WorldPoint(1750, 3535, 0)),
			new MahoganyHomesScript.Work(3, 40294, new WorldPoint(1754, 3535, 0)),
			new MahoganyHomesScript.Work(4, 40013, new WorldPoint(1748, 3538, 0)),
			new MahoganyHomesScript.Work(5, 40014, new WorldPoint(1751, 3534, 0)),
			new MahoganyHomesScript.Work(6, 40015, new WorldPoint(1750, 3532, 0)))),
	SARAH("Sarah", 10416, new WorldPoint(3235, 3383, 0),
		new WorldArea(3229, 3376, 13, 15, 0),
		new int[]{3, 2, 2, 2, 0, 2, 0, 0}, new int[]{0, 0, 0, 0, 1, 0, 0, 0},
		new WorldPoint(3235, 3383, 0), new WorldPoint(3235, 3383, 1), List.of(
			new MahoganyHomesScript.Work(0, 39997, new WorldPoint(3237, 3384, 0)),
			new MahoganyHomesScript.Work(1, 39998, new WorldPoint(3233, 3384, 0)),
			new MahoganyHomesScript.Work(2, 39999, new WorldPoint(3233, 3386, 0)),
			new MahoganyHomesScript.Work(3, 40000, new WorldPoint(3233, 3382, 0)),
			new MahoganyHomesScript.Work(4, 40286, new WorldPoint(3236, 3382, 0),
				new WorldPoint(3236, 3383, 0)),
			new MahoganyHomesScript.Work(5, 40001, new WorldPoint(3234, 3382, 0)))),
	MARIAH("Mariah", 10422, new WorldPoint(1767, 3622, 0),
		new WorldArea(1762, 3617, 12, 12, 0),
		new int[]{3, 0, 2, 2, 2, 2, 2, 1}, new int[]{0, 1, 0, 0, 0, 0, 0, 0},
		new WorldPoint(1766, 3620, 0), new WorldPoint(1766, 3620, 1), List.of(
			new MahoganyHomesScript.Work(0, 40002, new WorldPoint(1768, 3622, 0)),
			new MahoganyHomesScript.Work(1, 40287, new WorldPoint(1763, 3621, 0)),
			new MahoganyHomesScript.Work(2, 40003, new WorldPoint(1766, 3619, 0)),
			new MahoganyHomesScript.Work(3, 40288, new WorldPoint(1763, 3620, 0)),
			new MahoganyHomesScript.Work(4, 40004, new WorldPoint(1764, 3621, 1)),
			new MahoganyHomesScript.Work(5, 40005, new WorldPoint(1764, 3620, 1)),
			new MahoganyHomesScript.Work(6, 40006, new WorldPoint(1770, 3623, 1),
				new WorldPoint(1769, 3622, 1)),
			new MahoganyHomesScript.Work(7, 40289, new WorldPoint(1764, 3623, 0)))),
	NOELLA("Noella", 10419, new WorldPoint(2659, 3322, 0),
		new WorldArea(2652, 3317, 15, 8, 0),
		new int[]{2, 2, 1, 1, 2, 3, 3, 1}, new int[8],
		new WorldPoint(2661, 3321, 0), new WorldPoint(2665, 3321, 1), List.of(
			new MahoganyHomesScript.Work(0, 40156, new WorldPoint(2661, 3323, 1),
				new WorldPoint(2665, 3321, 1)),
			new MahoganyHomesScript.Work(1, 40157, new WorldPoint(2661, 3321, 1),
				new WorldPoint(2665, 3321, 1)),
			new MahoganyHomesScript.Work(2, 40158, new WorldPoint(2665, 3318, 1),
				new WorldPoint(2665, 3321, 1)),
			new MahoganyHomesScript.Work(3, 40159, new WorldPoint(2661, 3318, 1),
				new WorldPoint(2665, 3321, 1)),
			new MahoganyHomesScript.Work(4, 40160, new WorldPoint(2657, 3322, 1),
				new WorldPoint(2655, 3321, 1)),
			new MahoganyHomesScript.Work(5, 40161, new WorldPoint(2654, 3319, 1),
				new WorldPoint(2655, 3321, 1)),
			new MahoganyHomesScript.Work(6, 40162, new WorldPoint(2656, 3318, 1),
				new WorldPoint(2655, 3321, 1)),
			new MahoganyHomesScript.Work(7, 40163, new WorldPoint(2653, 3322, 1),
				new WorldPoint(2655, 3321, 1)))),
	ROSS("Ross", 10420, new WorldPoint(2614, 3318, 0),
		new WorldArea(2610, 3314, 9, 6, 0),
		new int[]{0, 2, 2, 3, 1, 2, 1, 0}, new int[]{1, 0, 0, 0, 0, 0, 0, 0},
		new WorldPoint(2616, 3315, 0), new WorldPoint(2616, 3315, 1), List.of(
			new MahoganyHomesScript.Work(0, 40164, new WorldPoint(2617, 3317, 0)),
			new MahoganyHomesScript.Work(1, 40165, new WorldPoint(2611, 3318, 0)),
			new MahoganyHomesScript.Work(2, 40166, new WorldPoint(2611, 3315, 0)),
			new MahoganyHomesScript.Work(3, 40167, new WorldPoint(2613, 3317, 1),
				new WorldPoint(2614, 3316, 1)),
			new MahoganyHomesScript.Work(4, 40168, new WorldPoint(2615, 3316, 1)),
			new MahoganyHomesScript.Work(5, 40169, new WorldPoint(2617, 3319, 1),
				new WorldPoint(2617, 3318, 1)),
			new MahoganyHomesScript.Work(6, 40170, new WorldPoint(2618, 3317, 1),
				new WorldPoint(2617, 3318, 1)))),
	JESS("Jess", 10421, new WorldPoint(2620, 3292, 1),
		new WorldArea(2610, 3289, 15, 9, 0),
		new int[]{2, 2, 2, 2, 3, 3, 1, 0}, new int[]{0, 0, 0, 0, 0, 0, 0, 1},
		new WorldPoint(2622, 3291, 0), new WorldPoint(2622, 3291, 1), List.of(
			new MahoganyHomesScript.Work(0, 40171, new WorldPoint(2612, 3296, 1),
				new WorldPoint(2616, 3294, 1)),
			new MahoganyHomesScript.Work(1, 40172, new WorldPoint(2620, 3291, 1),
				new WorldPoint(2620, 3292, 1)),
			new MahoganyHomesScript.Work(2, 40173, new WorldPoint(2621, 3295, 1),
				new WorldPoint(2622, 3294, 1)),
			new MahoganyHomesScript.Work(3, 40174, new WorldPoint(2622, 3295, 1),
				new WorldPoint(2622, 3294, 1)),
			new MahoganyHomesScript.Work(4, 40175, new WorldPoint(2614, 3295, 1),
				new WorldPoint(2616, 3294, 1)),
			new MahoganyHomesScript.Work(5, 40176, new WorldPoint(2612, 3293, 1),
				new WorldPoint(2616, 3294, 1)),
			new MahoganyHomesScript.Work(6, 40177, new WorldPoint(2612, 3295, 1),
				new WorldPoint(2616, 3294, 1)),
			new MahoganyHomesScript.Work(7, 40299, new WorldPoint(2615, 3291, 1),
				new WorldPoint(2615, 3292, 1)))),
	TAU("Tau", 10417, new WorldPoint(3047, 3343, 0),
		new WorldArea(3039, 3338, 15, 12, 0),
		new int[]{0, 3, 3, 2, 2, 2, 1, 0}, new int[]{1, 0, 0, 0, 0, 0, 0, 0},
		new WorldPoint(3047, 3343, 0), new WorldPoint(3047, 3343, 1), List.of(
			new MahoganyHomesScript.Work(0, 40083, new WorldPoint(3051, 3347, 0)),
			new MahoganyHomesScript.Work(1, 40084, new WorldPoint(3048, 3344, 0)),
			new MahoganyHomesScript.Work(2, 40085, new WorldPoint(3048, 3349, 0)),
			new MahoganyHomesScript.Work(3, 40086, new WorldPoint(3044, 3345, 0)),
			new MahoganyHomesScript.Work(4, 40087, new WorldPoint(3047, 3341, 0)),
			new MahoganyHomesScript.Work(5, 40088, new WorldPoint(3048, 3341, 0)),
			new MahoganyHomesScript.Work(6, 40295, new WorldPoint(3046, 3341, 0)))),
	BOB("Bob", 10414, new WorldPoint(3237, 3484, 0),
		new WorldArea(3233, 3481, 12, 11, 0),
		new int[]{4, 1, 2, 2, 2, 2, 2, 2}, new int[8],
		new WorldPoint(3238, 3489, 0), new WorldPoint(3242, 3489, 1), List.of(
			new MahoganyHomesScript.Work(0, 39981, new WorldPoint(3237, 3486, 0)),
			new MahoganyHomesScript.Work(1, 39982, new WorldPoint(3235, 3483, 0)),
			new MahoganyHomesScript.Work(2, 39983, new WorldPoint(3235, 3486, 0)),
			new MahoganyHomesScript.Work(3, 39984, new WorldPoint(3235, 3487, 0)),
			new MahoganyHomesScript.Work(4, 39985, new WorldPoint(3235, 3484, 0)),
			new MahoganyHomesScript.Work(5, 39986, new WorldPoint(3235, 3489, 0)),
			new MahoganyHomesScript.Work(6, 39987, new WorldPoint(3242, 3487, 1)),
			new MahoganyHomesScript.Work(7, 39988, new WorldPoint(3242, 3486, 1)))),
	JEFF("Jeff", 10415, new WorldPoint(3242, 3452, 0),
		new WorldArea(3236, 3445, 11, 12, 0),
		new int[]{3, 2, 2, 3, 2, 2, 1, 1}, new int[8],
		new WorldPoint(3238, 3446, 0), new WorldPoint(3239, 3447, 1), List.of(
			new MahoganyHomesScript.Work(0, 39989, new WorldPoint(3238, 3452, 0)),
			new MahoganyHomesScript.Work(1, 39990, new WorldPoint(3242, 3454, 0)),
			new MahoganyHomesScript.Work(2, 39991, new WorldPoint(3241, 3447, 0),
				new WorldPoint(3238, 3446, 0)),
			new MahoganyHomesScript.Work(3, 39992, new WorldPoint(3239, 3453, 1)),
			new MahoganyHomesScript.Work(4, 39993, new WorldPoint(3242, 3454, 1)),
			new MahoganyHomesScript.Work(5, 39994, new WorldPoint(3241, 3446, 1)),
			new MahoganyHomesScript.Work(6, 39995, new WorldPoint(3238, 3454, 1)),
			new MahoganyHomesScript.Work(7, 39996, new WorldPoint(3242, 3447, 1))));

	final String homeowner;
	final int npcId;
	final WorldPoint destination;
	final WorldArea area;
	final int[] plankCosts;
	final int[] steelBarCosts;
	final WorldPoint groundLanding;
	final WorldPoint upperLanding;
	final List<MahoganyHomesScript.Work> work;

	MahoganyHomesContractData(String homeowner, int npcId, WorldPoint destination, WorldArea area,
		int[] plankCosts, int[] steelBarCosts, WorldPoint groundLanding,
		WorldPoint upperLanding, List<MahoganyHomesScript.Work> work)
	{
		this.homeowner = homeowner;
		this.npcId = npcId;
		this.destination = destination;
		this.area = area;
		this.plankCosts = plankCosts.clone();
		this.steelBarCosts = steelBarCosts.clone();
		this.groundLanding = groundLanding;
		this.upperLanding = upperLanding;
		this.work = List.copyOf(work);
	}

	boolean contains(WorldPoint point)
	{
		return point != null && area.contains(new WorldPoint(point.getX(), point.getY(), area.getPlane()));
	}

	WorldPoint landing(int plane)
	{
		return plane == 0 ? groundLanding : upperLanding;
	}

	int requiredPlanks(int[] hotspotValues)
	{
		return required(hotspotValues, plankCosts);
	}

	int requiredSteelBars(int[] hotspotValues)
	{
		return required(hotspotValues, steelBarCosts);
	}

	private static int required(int[] values, int[] costs)
	{
		if (values == null || values.length != costs.length)
		{
			return -1;
		}
		int total = 0;
		for (int index = 0; index < values.length; index++)
		{
			if (values[index] == 1 || values[index] == 3 || values[index] == 4)
			{
				total += costs[index];
			}
		}
		return total;
	}

	static MahoganyHomesContractData forHomeowner(String homeowner)
	{
		for (MahoganyHomesContractData contract : values())
		{
			if (contract.homeowner.equalsIgnoreCase(homeowner))
			{
				return contract;
			}
		}
		return null;
	}

	static MahoganyHomesContractData forLocation(WorldPoint point)
	{
		for (MahoganyHomesContractData contract : values())
		{
			if (contract.contains(point))
			{
				return contract;
			}
		}
		return null;
	}
}
