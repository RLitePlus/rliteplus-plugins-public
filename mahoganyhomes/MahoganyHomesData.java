package net.runelite.client.plugins.microbot.mahoganyhomes;

import net.runelite.api.gameval.ItemID;

public enum MahoganyHomesData
{
	BEGINNER("Beginner", "Beginner Contract", 1, ItemID.WOODPLANK, ItemID.Cert.WOODPLANK,
		"regular planks", 14),
	NOVICE("Novice", "Novice Contract", 20, ItemID.PLANK_OAK, ItemID.Cert.PLANK_OAK,
		"oak planks", 14),
	ADEPT("Adept", "Adept Contract", 50, ItemID.PLANK_TEAK, ItemID.Cert.PLANK_TEAK,
		"teak planks", 17),
	EXPERT("Expert", "Expert Contract", 70, ItemID.PLANK_MAHOGANY, ItemID.Cert.PLANK_MAHOGANY,
		"mahogany planks", 17);

	private final String name;
	private final String contractOption;
	private final int constructionLevel;
	private final int plankId;
	private final int notedPlankId;
	private final String plankName;
	private final int setupPlanks;

	MahoganyHomesData(
		String name, String contractOption, int constructionLevel, int plankId, int notedPlankId,
		String plankName, int setupPlanks)
	{
		this.name = name;
		this.contractOption = contractOption;
		this.constructionLevel = constructionLevel;
		this.plankId = plankId;
		this.notedPlankId = notedPlankId;
		this.plankName = plankName;
		this.setupPlanks = setupPlanks;
	}

	String getName()
	{
		return name;
	}

	String getContractOption()
	{
		return contractOption;
	}

	static MahoganyHomesData fromName(String name)
	{
		for (MahoganyHomesData tier : values())
		{
			if (tier.name.equalsIgnoreCase(name))
			{
				return tier;
			}
		}
		return null;
	}

	int getConstructionLevel()
	{
		return constructionLevel;
	}

	int getPlankId()
	{
		return plankId;
	}

	int getNotedPlankId()
	{
		return notedPlankId;
	}

	String getPlankName()
	{
		return plankName;
	}

	int getSetupPlanks()
	{
		return setupPlanks;
	}

	int getSetupSteelBars()
	{
		return 1;
	}

	@Override
	public String toString()
	{
		return name + " (level " + constructionLevel + ")";
	}
}
