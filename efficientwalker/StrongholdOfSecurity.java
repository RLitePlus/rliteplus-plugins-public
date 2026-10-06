package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.efficientwalker.LocalTransportCatalog.Transport;
import net.runelite.client.util.Text;

final class StrongholdOfSecurity
{
	private static final Set<Integer> CLOSED_GATE_IDS = Set.of(
		17009, 17100,
		19206, 19207,
		23653, 23654,
		23727, 23728);
	private static final int WAR_PORTAL = 20786;
	private static final int FAMINE_PORTAL = 19005;
	private static final int PESTILENCE_PORTAL = 23707;
	private static final int DEATH_PORTAL = 23922;

	private static final Set<String> CORRECT_ANSWERS = answers(
		"No.",
		"Me.",
		"Nobody.",
		"Talk to any banker.",
		"Nothing, it's a fake.",
		"Delete it - it's a fake!",
		"Don't give them my password.",
		"Report the player for phishing.",
		"Use the Account Recovery system.",
		"No way! I'm reporting you to Jagex!",
		"No, you should never buy an account.",
		"Secure my device and reset my password.",
		"Decline the offer and report that player.",
		"The birthday of a famous person or event.",
		"Only on the Old School RuneScape website.",
		"Read the text and follow the advice given.",
		"Virus scan my device then change my password.",
		"Report the incident and do not click any links.",
		"Don't share your information and report the player.",
		"Set up two-factor authentication with my email provider.",
		"No, you should never allow anyone to level your account.",
		"No, you should never allow anyone to use your account.",
		"Authenticator and two-step login on my registered email.",
		"Through account settings on oldschool.runescape.com.",
		"No way! You'll just take my gold for your own! Reported!",
		"Don't type in my password backwards and report the player.",
		"Don't give them the information and send an 'Abuse report'.",
		"Don't tell them anything and click the 'Report Abuse' button.",
		"Politely tell them no and then use the 'Report Abuse' button.",
		"Politely tell them no, then use the 'Report Abuse' button.",
		"Don't give out your password to anyone. Not even close friends.",
		"Do not visit the website and report the player who messaged you.",
		"Report the stream as a scam. Real Jagex streams have a 'verified' mark.",
		"Two-factor authentication on your account and your registered email.",
		"Nope, you're tricking me into going somewhere dangerous.",
		"It's never used on other websites or accounts.",
		"It's never reused on other websites or accounts.");

	private StrongholdOfSecurity()
	{
	}

	static boolean isGate(int objectId)
	{
		return CLOSED_GATE_IDS.contains(objectId);
	}

	static List<Transport> portalTransports()
	{
		return List.of(
			LocalTransportCatalog.transport(new WorldPoint(1863, 5239, 0),
				new WorldPoint(1914, 5222, 0), "Use", "Portal", WAR_PORTAL),
			LocalTransportCatalog.transport(new WorldPoint(2040, 5240, 0),
				new WorldPoint(2021, 5223, 0), "Use", "Portal", FAMINE_PORTAL),
			LocalTransportCatalog.transport(new WorldPoint(2120, 5257, 0),
				new WorldPoint(2146, 5287, 0), "Use", "Portal", PESTILENCE_PORTAL),
			LocalTransportCatalog.transport(new WorldPoint(2364, 5212, 0),
				new WorldPoint(2341, 5219, 0), "Use", "Portal", DEATH_PORTAL));
	}

	static boolean isPortal(int objectId)
	{
		return objectId == WAR_PORTAL || objectId == FAMINE_PORTAL
			|| objectId == PESTILENCE_PORTAL || objectId == DEATH_PORTAL;
	}

	static boolean isPortalAvailable(int objectId, int combatLevel, IntUnaryOperator varbitValue)
	{
		switch (objectId)
		{
			case WAR_PORTAL:
				return combatLevel >= 26 || varbitValue.applyAsInt(VarbitID.SOS_EMOTE_FLAP) == 1;
			case FAMINE_PORTAL:
				return combatLevel >= 51 || varbitValue.applyAsInt(VarbitID.SOS_EMOTE_DOH) == 1;
			case PESTILENCE_PORTAL:
				return combatLevel >= 76 || varbitValue.applyAsInt(VarbitID.SOS_EMOTE_IDEA) == 1;
			case DEATH_PORTAL:
				return varbitValue.applyAsInt(VarbitID.SOS_EMOTE_STAMP) == 1;
			default:
				return true;
		}
	}

	static String correctAnswer(List<String> options)
	{
		String answer = null;
		for (String option : options)
		{
			if (!CORRECT_ANSWERS.contains(normalize(option)))
			{
				continue;
			}
			if (answer != null)
			{
				return null;
			}
			answer = option;
		}
		return answer;
	}

	private static Set<String> answers(String... values)
	{
		return Arrays.stream(values).map(StrongholdOfSecurity::normalize).collect(Collectors.toUnmodifiableSet());
	}

	private static String normalize(String value)
	{
		return Text.sanitizeMultilineText(value)
			.replace('\u2018', '\'')
			.replace('\u2019', '\'')
			.trim()
			.toLowerCase(Locale.ROOT);
	}
}
