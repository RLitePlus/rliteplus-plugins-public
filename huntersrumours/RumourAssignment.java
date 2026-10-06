package net.runelite.client.plugins.microbot.huntersrumours;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RumourAssignment
{
	private static final Pattern REFERENCED_HUNTER = Pattern.compile(
		"\\b(?:hunting for |following )(?:guild hunter |huntmaster )?(gilman|cervus|ornus|aco|teco|wolf)\\b");
	private static final Pattern WORDS = Pattern.compile("[^a-z0-9 ]");

	public enum Hunter
	{
		GILMAN(46, 13121, 29242, "Huntmaster Gilman (Novice)"),
		CERVUS(57, 13123, 29244, "Guild Hunter Cervus (Adept)"),
		ORNUS(57, 13122, 29244, "Guild Hunter Ornus (Adept)"),
		ACO(72, 13124, 29246, "Guild Hunter Aco (Expert)"),
		TECO(72, 13125, 29246, "Guild Hunter Teco (Expert)"),
		WOLF(91, 13126, 29248, "Guild Hunter Wolf (Master)");

		final int minimumLevel;
		final int npcId;
		final int sackId;
		final String npcName;

		Hunter(int minimumLevel, int npcId, int sackId, String npcName)
		{
			this.minimumLevel = minimumLevel;
			this.npcId = npcId;
			this.sackId = sackId;
			this.npcName = npcName;
		}
		@Override
		public String toString()
		{
			return npcName;
		}
	}

	enum Creature
	{
		TROPICAL_WAGTAIL(19, 29222, "tropical wagtail"),
		WILD_KEBBIT(23, 29223, "wild kebbit"),
		SAPPHIRE_GLACIALIS(25, 29224, "sapphire glacialis"),
		SWAMP_LIZARD(29, 29225, "swamp lizard"),
		SPINED_LARUPIA(31, 29226, "spined larupia"),
		BARB_TAILED_KEBBIT(33, 29223, "barb tailed kebbit"),
		SNOWY_KNIGHT(35, 29227, "snowy knight"),
		PRICKLY_KEBBIT(37, 29223, "prickly kebbit"),
		EMBERTAILED_JERBOA(39, 29228, "embertailed jerboa"),
		HORNED_GRAAHK(41, 29229, "horned graahk"),
		SPOTTED_KEBBIT(43, 29223, "spotted kebbit"),
		BLACK_WARLOCK(45, 29230, "black warlock"),
		ORANGE_SALAMANDER(47, 29231, "orange salamander"),
		RAZOR_BACKED_KEBBIT(49, 29223, "razor backed kebbit", "razorback kebbit"),
		SABRE_TOOTHED_KEBBIT(51, 29223, "sabre toothed kebbit"),
		GREY_CHINCHOMPA(53, 29221, "grey chinchompa", "chinchompa"),
		SABRE_TOOTHED_KYATT(55, 29232, "sabre toothed kyatt"),
		DARK_KEBBIT(57, 29223, "dark kebbit"),
		PYRE_FOX(57, 29233, "pyre fox"),
		RED_SALAMANDER(59, 29234, "red salamander"),
		WYRMSCRAIG_GOAT(60, 33835, "wyrmscraig goat"),
		RED_CHINCHOMPA(63, 29235, "red chinchompa", "carnivorous chinchompa"),
		DASHING_KEBBIT(69, 29223, "dashing kebbit"),
		SUNLIGHT_ANTELOPE(72, 29236, "sunlight antelope"),
		SUNLIGHT_MOTH(75, 29237, "sunlight moth"),
		TECU_SALAMANDER(79, 29238, "tecu salamander"),
		HERBIBOAR(80, 29239, "herbiboar"),
		MOONLIGHT_MOTH(85, 29240, "moonlight moth"),
		MOONLIGHT_ANTELOPE(91, 29241, "moonlight antelope");

		final int assignmentLevel;
		private final int rarePart;
		private final String[] names;

		Creature(int assignmentLevel, int rarePart, String... names)
		{
			this.assignmentLevel = assignmentLevel;
			this.rarePart = rarePart;
			this.names = names;
		}

		String huntingMethod()
		{
			if (butterflyId() > 0) return "Butterfly / moth catching";
			if (falconPreyId() > 0) return "Falconry";
			if (netCatchId() > 0) return "Net trapping";
			if (deadfallCatchId() > 0) return "Deadfall trapping";
			if (boxCatchId() > 0) return "Box trapping";
			if (HuntingSupplies.pit(this) || this == WYRMSCRAIG_GOAT) return "Pit trapping";
			if (this == RAZOR_BACKED_KEBBIT || this == HERBIBOAR) return "Tracking";
			if (this == TROPICAL_WAGTAIL) return "Bird snaring";
			return "Not implemented";
		}

		int butterflyId()
		{
			return this == SAPPHIRE_GLACIALIS ? 5555 : this == SNOWY_KNIGHT ? 5554 : this == BLACK_WARLOCK ? 5553 : this == SUNLIGHT_MOTH ? 12770 : this == MOONLIGHT_MOTH ? 12771 : 0;
		}

		boolean butterflyMatches(int id)
		{
			return butterflyId() > 0 && (id == butterflyId() || (this == MOONLIGHT_MOTH && id == 12772));
		}

		int butterflyLevel()
		{
			return this == SUNLIGHT_MOTH ? 65 : this == MOONLIGHT_MOTH ? 75 : assignmentLevel;
		}

		int falconPreyId()
		{
			return this == SPOTTED_KEBBIT ? 5531 : this == DARK_KEBBIT ? 5532 : this == DASHING_KEBBIT ? 5533 : 0;
		}

		int falconCatchId()
		{
			return this == SPOTTED_KEBBIT ? 1342 : this == DARK_KEBBIT ? 1344 : this == DASHING_KEBBIT ? 1343 : 0;
		}

		int netCatchId()
		{
			return this == SWAMP_LIZARD ? 10149 : this == RED_SALAMANDER ? 10147 : this == ORANGE_SALAMANDER ? 10146 : this == TECU_SALAMANDER ? 28831 : 0;
		}

		int keptNetCatchId()
		{
			return this == TECU_SALAMANDER ? 28834 : 0;
		}

		int deadfallCatchId()
		{
			return this == PYRE_FOX ? 29163 : this == PRICKLY_KEBBIT ? 10105 : this == BARB_TAILED_KEBBIT ? 10129 : this == WILD_KEBBIT ? 10113 : this == SABRE_TOOTHED_KEBBIT ? 10109 : 0;
		}

		int boxCatchId()
		{
			return this == EMBERTAILED_JERBOA ? 29166 : this == RED_CHINCHOMPA ? 10034 : this == GREY_CHINCHOMPA ? 10033 : 0;
		}

		int rarePartId()
		{
			return rarePart;
		}

	}

	final Hunter hunter;
	final Creature creature;

	private RumourAssignment(Hunter hunter, Creature creature)
	{
		this.hunter = hunter;
		this.creature = creature;
	}

	String levelBlocker(boolean members, int realHunterLevel)
	{
		if (!members) return "Hunters' Rumours require a members world. Return to a members world before restarting.";
		int required = Math.max(hunter.minimumLevel, creature.assignmentLevel);
		return realHunterLevel < required ? "This rumour requires " + required
			+ " Hunter without boosts. Keep the assignment and meet that requirement before restarting." : null;
	}

	static boolean isRarePart(int id)
	{
		for (Creature creature : Creature.values()) if (creature.rarePartId() == id) return true;
		return false;
	}

	static String normalize(String text)
	{
		if (text == null) return "";
		return WORDS.matcher(text.replaceAll("(?i)<br\\s*/?>", " ")
			.replaceAll("<[^>]*>", "").replace('-', ' ').toLowerCase(Locale.ROOT))
			.replaceAll(" ").replaceAll("\\s+", " ").trim();
	}

	static RumourAssignment fromDialogue(String speaker, String dialogue)
	{
		Hunter source = speaker(speaker);
		if (source == null || dialogue == null) return null;
		String text = normalize(dialogue);
		if (text.contains("would you prefer") || text.contains("would you like another")
			|| text.contains("stopped off for a bit of hunting first"))
		{
			return null;
		}
		boolean taskStatement = text.contains("bring back")
			|| text.contains("this rumour was about") || text.contains("you re hunting for");
		if (!taskStatement) return null;
		Creature target = null;
		for (Creature candidate : Creature.values())
		{
			for (String name : candidate.names)
			{
				if (Pattern.compile((name.equals("chinchompa") ? "(?<!red )(?<!carnivorous )(?<!black )" : "") + "\\b" + Pattern.quote(name) + "(?:es|s)?\\b").matcher(text).find())
				{
					if (target != null && target != candidate) return null;
					target = candidate;
				}
			}
		}
		if (target == null) return null;
		Matcher referenced = REFERENCED_HUNTER.matcher(text);
		Hunter owner = referenced.find()
			? Hunter.valueOf(referenced.group(1).toUpperCase(Locale.ROOT)) : source;
		return new RumourAssignment(owner, target);
	}

	static RumourAssignment fromReminder(String message)
	{
		String text = normalize(message);
		if (!text.startsWith("your current rumour target is ")) return null;
		for (Hunter hunter : Hunter.values())
		{
			if (text.endsWith(" " + hunter.name().toLowerCase(Locale.ROOT) + " was the source of the rumour"))
				return fromDialogue(hunter.npcName, text);
		}
		return null;
	}

	boolean matchesDialogue(String speaker, String dialogue)
	{
		RumourAssignment observed = fromDialogue(speaker, dialogue);
		return observed != null && observed.hunter == hunter && observed.creature == creature;
	}

	static boolean switchQuestion(String question, Hunter hunter)
	{
		return normalize(question).equals(normalize("Switch to " + hunter.npcName + "'s rumour?"));
	}

	static boolean switchContinuation(String dialogue, Hunter hunter)
	{
		String text = normalize(dialogue);
		return (hunter == Hunter.GILMAN && ((text.startsWith("i can see you re following ") && fromDialogue(hunter.npcName, dialogue) != null)
			|| (text.startsWith("i seem to remember sending you off to hunt ") && text.endsWith("would you prefer that one or a new one entirely"))))
			|| text.equals("unless you fancy hunting for me instead")
			|| (text.startsWith("i hear you re hunting for ") && fromDialogue(hunter.npcName, dialogue) != null);
	}

	private static Hunter speaker(String name)
	{
		String text = normalize(name);
		for (Hunter candidate : Hunter.values())
		{
			String prefix = candidate == Hunter.GILMAN ? "huntmaster " : "guild hunter ";
			String expected = prefix + candidate.name().toLowerCase(Locale.ROOT);
			if (text.equals(expected) || text.startsWith(expected + " ")) return candidate;
		}
		return null;
	}
}
