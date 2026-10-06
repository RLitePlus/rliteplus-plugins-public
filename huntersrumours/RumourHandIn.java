package net.runelite.client.plugins.microbot.huntersrumours;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;

final class RumourHandIn
{
	static boolean atGuild(WorldPoint position)
	{
		return position != null && position.getPlane() == 0
			&& position.getRegionID() == new WorldPoint(1559, 9452, 0).getRegionID();
	}

	private static final Pattern COUNT = Pattern.compile("our records suggest you ve managed to complete (\\d+) rumours? for us");

	static int completionCount(String text)
	{
		Matcher matcher = COUNT.matcher(RumourAssignment.normalize(text == null ? null : text.replace(",", "")));
		if (!matcher.matches()) return -1;
		try
		{
			return Integer.parseInt(matcher.group(1));
		}
		catch (NumberFormatException exception)
		{
			return -1;
		}
	}

	static boolean acknowledged(String text)
	{
		String normalized = RumourAssignment.normalize(text);
		return normalized.startsWith("thanks for that i ll mark off that report for you")
			|| normalized.equals("thanks for doing that for me here s your reward")
			|| normalized.equals("excellent thanks for getting that done here s your reward")
			|| normalized.equals("verity will be glad to have another one ticked off thanks here s your reward")
			|| normalized.equals("another one done you re really doing a lot for the guild");
	}

	static boolean rewardDialogue(String text, boolean playerSpeaking)
	{
		String normalized = RumourAssignment.normalize(text);
		if (playerSpeaking)
			return normalized.equals("no") || normalized.equals("never mind") || normalized.equals("i m good thanks");
		return acknowledged(text) || normalized.equals("would you like another rumour")
			|| normalized.equals("are you interested in taking on another one for me")
			|| normalized.equals("fancy taking on another") || normalized.equals("are you up for another");
	}

	static String exitOption(RumourAssignment.Hunter hunter)
	{
		return hunter == RumourAssignment.Hunter.GILMAN ? "I'm good, thanks." : "Never mind.";
	}

	static boolean countDialogue(String text)
	{
		String normalized = RumourAssignment.normalize(text);
		return normalized.equals("nilsal hunter anything to report") || normalized.equals("nothing abnormal today")
			|| normalized.equals("how many rumours have i completed") || normalized.equals("i have to go")
			|| completionCount(text) >= 0;
	}

	static boolean introductionDialogue(String text, boolean playerSpeaking)
	{
		String normalized = RumourAssignment.normalize(text);
		if (playerSpeaking)
		{
			return normalized.equals("yes please") || normalized.equals("reports")
				|| normalized.equals("i see thanks for the information");
		}
		return normalized.equals("oh hello there newcomer i m guild scribe verity do you need me to explain the work we do here")
			|| normalized.equals("so it s nice and simple just chat to any of the hunters in here to help them out they re all following reports that i ve split up amongst them")
			|| normalized.equals("indeed there are all sorts of interesting creatures to hunt out there sometimes we get reports of a particularly unique example when that happens one of the hunters will set out to find it")
			|| normalized.equals("if you do decide to take a job from any of the hunters they ll tell you what type of creature they re looking into hunt that creature until you encounter the unique specimen")
			|| normalized.equals("once you ve caught it bring some proof back to the hunter that sent you out in the first place i m sure they ll reward you for your time");
	}

	static boolean verified(int before, int after, boolean acknowledged, int parts, int sacksBefore, int sacksAfter)
	{
		return before >= 0 && after == before + 1 && acknowledged && parts == 0 && sacksAfter == sacksBefore + 1;
	}
}
