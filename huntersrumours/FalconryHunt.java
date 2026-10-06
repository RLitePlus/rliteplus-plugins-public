package net.runelite.client.plugins.microbot.huntersrumours;

final class FalconryHunt
{
	enum Action { RENT, CATCH, RETRIEVE, WAIT, VERIFIED, BLOCKED }
	private int productsBefore;
	private boolean renting;
	private boolean launched;
	private boolean retrieving;
	private boolean released;
	private boolean lost;

	synchronized void message(String text)
	{
		if (RumourAssignment.normalize(text).equals("your falcon has left its prey you see it heading back toward the falconer")) lost = true;
	}

	synchronized boolean recovering()
	{
		return lost;
	}

	synchronized void recovered()
	{
		lost = renting = launched = retrieving = released = false;
	}

	private static boolean lostDialogue(String normalized)
	{
		return normalized.equals("hi er your falcon ran away")
			|| normalized.equals("falcons aren t domesticated you know you can t expect them to just automatically return to you fortunately for you they can learn to associate areas with food he ll probably be back on his post")
			|| normalized.equals("do you want me to fetch him for you");
	}

	static boolean recoveryDialogue(String text)
	{
		String normalized = RumourAssignment.normalize(text);
		return lostDialogue(normalized)
			|| normalized.equals("yes please")
			|| normalized.equals("the falconer brings a bird over to land on your glove")
			|| normalized.equals("right try to be a bit more careful this time these birds take a long time to raise i d rather not lose one");
	}

	synchronized boolean inFlight()
	{
		return launched || retrieving;
	}

	synchronized boolean awaitingCatch()
	{
		return retrieving;
	}

	static boolean rentalConfirmation(String text, boolean held)
	{
		return held && RumourAssignment.normalize(text).equals(
			"the falconer gives you a large leather glove and brings one of the smaller birds over to land on it");
	}

	static boolean releaseDialogue(String text)
	{
		String normalized = RumourAssignment.normalize(text);
		return lostDialogue(normalized) || normalized.equals("hello again") || normalized.equals("it s certainly harder than it looks")
			|| normalized.matches("ah you re back how are you getting along with (him|her|them) then")
			|| normalized.equals("sorry but i was talking to the falcon not you but yes it is have you had enough yet")
			|| normalized.equals("i think i ll leave it for now")
			|| normalized.equals("you give the falcon and glove back to matthias")
			|| normalized.equals("actually i think i ll leave it for now")
			|| normalized.equals("you give the falcon glove back to matthias");
	}

	synchronized Action next(int tick, boolean held, boolean ownedCatch, int products)
	{
		if (retrieving)
		{
			if (held && !ownedCatch && products > productsBefore)
			{
				renting = launched = retrieving = released = false;
				return Action.VERIFIED;
			}
			return Action.WAIT;
		}
		if (ownedCatch)
		{
			return Action.RETRIEVE;
		}
		if (held)
		{
			renting = false;
			if (launched && !released) return Action.WAIT;
			return Action.CATCH;
		}
		if (launched)
		{
			released = true;
			return Action.WAIT;
		}
		if (renting) return Action.WAIT;
		return Action.RENT;
	}

	synchronized void submitted(Action action, int tick, int products)
	{
		if (action == Action.RETRIEVE)
		{
			if (!launched) productsBefore = products;
			retrieving = true;
		}
		else if (action == Action.CATCH)
		{
			launched = true;
			released = false;
			productsBefore = products;
		}
		else if (action == Action.RENT) renting = true;
		else return;
	}

}
