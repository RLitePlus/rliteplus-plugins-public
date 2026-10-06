package net.runelite.client.plugins.microbot.tithefarm;

final class TitheFarmCycle
{
	static final int EMPTY_PATCH = 27383;
	static final int SACK_AMOUNT = 4900;
	static final int POINTS = 4893;
	static final int GRICOLLERS_CAN = 13353;
	static final int GRICOLLERS_CHARGES = 4908;
	static final int GRICOLLERS_CAPACITY = 1000;
	private static final int EFFECT_TIMEOUT = 20;
	private static final int GROWTH_TIMEOUT = 150;

	enum Crop
	{
		GOLOVANOVA(34, 13423, 13426, 27384),
		BOLOGANO(54, 13424, 13427, 27395),
		LOGAVANO(74, 13425, 13428, 27406);

		final int level;
		final int seed;
		final int fruit;
		final int firstDry;

		Crop(int level, int seed, int fruit, int firstDry)
		{
			this.level = level;
			this.seed = seed;
			this.fruit = fruit;
			this.firstDry = firstDry;
		}

		boolean contains(int id)
		{
			return id >= firstDry && id <= firstDry + 10;
		}

		boolean dry(int id)
		{
			return id >= firstDry && id <= firstDry + 6 && (id - firstDry) % 3 == 0;
		}

		boolean wet(int id)
		{
			return id >= firstDry + 1 && id <= firstDry + 7 && (id - firstDry) % 3 == 1;
		}
	}

	enum Action
	{
		NONE, SELECT_SEED, PLANT, WATER, HARVEST, DEPOSIT, SELECT_CAN, REFILL, ENABLE_RUN, MOVE, MANUAL_WATER, DESELECT, CLEAR
	}

	enum State
	{
		PLANT, TEND, DEPOSIT, COMPLETE, ERROR
	}

	static final class Snapshot
	{
		final int tick;
		final int objectId;
		final int seeds;
		final int fruit;
		final int water;
		final int sack;
		final int xp;
		final int selectedItem;

		Snapshot(int tick, int objectId, int seeds, int fruit, int water, int sack, int xp, int selectedItem)
		{
			this.tick = tick;
			this.objectId = objectId;
			this.seeds = seeds;
			this.fruit = fruit;
			this.water = water;
			this.sack = sack;
			this.xp = xp;
			this.selectedItem = selectedItem;
		}
	}

	final Crop crop;
	private final boolean depositFruit;
	State state = State.PLANT;
	String error;
	int watered;
	int harvested;
	int deposited;
	private Action pending = Action.NONE;
	private Snapshot baseline;
	private int lastTick = -1;
	private int lastObject = -1;
	private int changedTick;

	TitheFarmCycle(Crop crop)
	{
		this(crop, true);
	}

	TitheFarmCycle(Crop crop, boolean depositFruit)
	{
		this.crop = crop;
		this.depositFruit = depositFruit;
	}

	Action next(Snapshot now)
	{
		if (state == State.ERROR || state == State.COMPLETE || now.tick <= lastTick)
		{
			return Action.NONE;
		}
		lastTick = now.tick;
		if (now.objectId != lastObject)
		{
			lastObject = now.objectId;
			changedTick = now.tick;
		}
		if (pending != Action.NONE)
		{
			boolean effect = false;
			switch (pending)
			{
				case SELECT_SEED:
					effect = now.selectedItem == crop.seed;
					break;
				case PLANT:
					effect = now.seeds == baseline.seeds - 1 && now.objectId == crop.firstDry;
					if (effect)
					{
						state = State.TEND;
					}
					break;
				case WATER:
					effect = now.objectId == baseline.objectId + 1 && now.water == baseline.water - 1;
					if (effect)
					{
						watered++;
					}
					break;
				case HARVEST:
					effect = now.objectId == EMPTY_PATCH && now.fruit == baseline.fruit + 1;
					if (effect)
					{
						harvested++;
						state = depositFruit ? State.DEPOSIT : State.COMPLETE;
					}
					break;
				case DEPOSIT:
					effect = now.fruit == 0 && baseline.fruit == 1
						&& depositAdvanced(baseline.sack, now.sack, 1) && now.xp > baseline.xp;
					if (effect)
					{
						deposited++;
						state = State.COMPLETE;
					}
					break;
				default:
					break;
			}
			if (!effect)
			{
				if (now.tick - baseline.tick >= EFFECT_TIMEOUT)
				{
					fail("Could not confirm " + pending.name().toLowerCase().replace('_', ' ')
						+ ". Check the patch and supplies before restarting Tithe Farm.");
				}
				return Action.NONE;
			}
			pending = Action.NONE;
		}
		Action next = Action.NONE;
		if (state == State.PLANT)
		{
			if (now.objectId != EMPTY_PATCH || now.seeds < 1 || now.water < 3 || depositFruit && now.fruit != 0)
			{
				fail("Setup changed. Start beside an empty patch with a seed, three water charges, and no fruit.");
			}
			else
			{
				next = now.selectedItem == crop.seed ? Action.PLANT : Action.SELECT_SEED;
			}
		}
		else if (state == State.TEND)
		{
			if (crop.dry(now.objectId) && (now.objectId - crop.firstDry) / 3 == watered)
			{
				if (now.water < 3 - watered)
				{
					fail("Missing " + (3 - watered - now.water)
						+ " water charges. Refill a watering can and tend the remaining crop.");
				}
				else
				{
					next = Action.WATER;
				}
			}
			else if (now.objectId == crop.firstDry + 9 && watered == 3)
			{
				next = Action.HARVEST;
			}
			else if (!crop.wet(now.objectId) || now.tick - changedTick >= GROWTH_TIMEOUT)
			{
				fail("The crop state or growth timing changed unexpectedly. Check the crop before restarting.");
			}
		}
		else if (state == State.DEPOSIT)
		{
			if (now.fruit != 1)
			{
				fail("The harvested fruit is no longer in inventory. Check the inventory before restarting.");
			}
			else
			{
				next = Action.DEPOSIT;
			}
		}
		if (next != Action.NONE)
		{
			baseline = now;
			pending = next;
		}
		return next;
	}

	boolean awaitingFirstWater()
	{
		return pending == Action.WATER && watered == 0 && state == State.TEND;
	}

	static boolean depositAdvanced(int before, int after, int amount)
	{
		return before >= 0 && before < 100 && after >= 0 && after <= 100
			&& amount > 0 && before + amount <= 100
			&& (after == before + amount || before + amount == 100 && after == 0);
	}

	void fail(String reason)
	{
		if (state != State.ERROR)
		{
			error = reason;
			state = State.ERROR;
			pending = Action.NONE;
		}
	}
}
