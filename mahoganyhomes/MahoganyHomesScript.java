package net.runelite.client.plugins.microbot.mahoganyhomes;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.questhelper.collections.ItemCollections;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;

public final class MahoganyHomesScript extends StateMachineScript<MahoganyHomesScript.State>
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MahoganyHomesScript.class);
	private static final String WALKER_CLASS =
		"net.runelite.client.plugins.microbot.efficientwalker.EfficientWalkerPlugin";
	private static final int[] HOTSPOT_VARBITS =
		{10554, 10555, 10556, 10557, 10558, 10559, 10560, 10561};
	private static final int NO_PROGRESS_TICKS = 6;
	private static final int MAX_ATTEMPTS = 3;
	private static final Pattern COMPLETION_MESSAGE = Pattern.compile(
		"You have completed [\\d,]+ contracts with a total of [\\d,]+ points?\\.");
	private static final Pattern CONTRACT_HOME = Pattern.compile(
		"(?i)\\bgo\\s+see\\s+([a-z][a-z'-]*)\\b.*\\byou can get another job once you have furnished\\s+"
			+ "(?:his|her)\\s+home\\.?");
	private static final Pattern CONTRACT_TIER = Pattern.compile(
		"(?i)currently on an? (Beginner|Novice|Adept|Expert) Contract");
	private static final String ASSIGNED_HOME_KEY = "assignedHomeowner";
	private static final String ASSIGNED_TIER_KEY = "assignedTier";
	@Inject
	private PluginManager pluginManager;

	@Inject
	private ConfigManager configManager;

	private volatile int readyTick = -1;
	private volatile int completedAtTick = -1;
	private volatile boolean complete;
	private boolean nextContractReady;
	private volatile String error;
	private volatile int completedEffects;
	private volatile int[] hotspotValues = new int[HOTSPOT_VARBITS.length];
	private int processedTick = -1;
	private MahoganyHomesConfig config;
	private MahoganyHomesData setupTier;
	private int setupPlanks;
	private int setupSteelBars;
	private boolean currentContract;
	private boolean currentContractAtHomeowner;
	private boolean setupReady;
	private boolean teleportResourcesPrepared;
	private boolean contractAcquired;
	private boolean contractDialogueClosed;
	private String assignedHomeowner;
	private MahoganyHomesData assignedTier;
	private MahoganyHomesContractorData contractor;
	private int contractRequestTick;
	private int contractAttempts;
	private String lastContractDialogue = "";
	private boolean contractArrived;
	private boolean contractValidated;
	private int contractArrivalWaitTick = -1;
	private int pendingBankItemId = -1;
	private int pendingBankItemQuantity;
	private int pendingBankActionTick;
	private int lastBankActionItemId = -1;
	private int bankActionAttempts;
	private int pendingSackQuantity = -1;
	private int pendingSackActionTick;
	private int lastSackQuantity = -1;
	private int sackActionAttempts;
	private int pendingSawItemId = -1;
	private int pendingSawItemQuantity;
	private int pendingSawActionTick;
	private int lastSawItemId = -1;
	private int sawActionAttempts;
	private boolean needsTravel;
	private boolean travelArrived;
	private boolean travelRequested;
	private State travelReturnState;
	private WorldPoint travelTarget;
	private boolean travelToNearestBank;
	private WorldPoint lastTravelPosition;
	private int lastTravelProgressTick;
	private Work pending;
	private Work activeWork;
	private int pendingValue;
	private int lastWorkProgressTick;
	private int workAttempts;
	private int missingObjectSince = -1;
	private boolean turnInRequested;
	private int turnInAttemptTick;
	private int turnInAttempts;
	private WalkerBridge walker;

	enum State
	{
		GET_SETUP,
		GET_CONTRACT,
		READY,
		GO_TO_CONTRACT,
		VALIDATE_CONTRACT,
		DO_CONTRACT,
		TRAVEL,
		TURN_IN_CONTRACT,
		DONE,
		ERROR
	}

	@Override
	protected State initialState()
	{
		return State.GET_CONTRACT;
	}

	@Override
	protected List<Transition<State>> defineTransitions()
	{
		return List.of(
			transition(State.GET_SETUP, State.ERROR, () -> error != null, "Setup failed"),
			transition(State.GET_SETUP, State.TRAVEL, () -> needsTravel, "Travelling to the setup bank"),
			transition(State.GET_SETUP, State.VALIDATE_CONTRACT,
				() -> setupReady && currentContractAtHomeowner, "Current contract setup is ready"),
			transition(State.GET_SETUP, State.READY,
				() -> setupReady && currentContract && !currentContractAtHomeowner,
				"Saved contract setup is ready"),
			transition(State.GET_SETUP, State.GET_CONTRACT,
				() -> setupReady && !currentContract, "Next contract setup is ready"),
			transition(State.GET_CONTRACT, State.ERROR, () -> error != null, "Contract acquisition failed"),
			transition(State.GET_CONTRACT, State.TRAVEL, () -> needsTravel, "Travelling to a contractor"),
			transition(State.GET_CONTRACT, State.GET_SETUP,
				() -> contractAcquired && contractDialogueClosed && !setupReady,
				"Preparing for the assigned contract"),
			transition(State.GET_CONTRACT, State.READY,
				() -> contractAcquired && contractDialogueClosed && setupReady,
				"Contract assignment confirmed"),
			transition(State.READY, State.GET_CONTRACT,
				() -> assignedHomeowner == null, "No contract assignment is known"),
			transition(State.READY, State.GO_TO_CONTRACT,
				() -> assignedHomeowner != null, "Contract assignment is ready for travel"),
			transition(State.GO_TO_CONTRACT, State.ERROR, () -> error != null, "Contract travel failed"),
			transition(State.GO_TO_CONTRACT, State.TRAVEL, () -> needsTravel,
				"Travelling to the assigned homeowner"),
			transition(State.GO_TO_CONTRACT, State.VALIDATE_CONTRACT,
				() -> contractArrived, "Assigned contract reached"),
			transition(State.VALIDATE_CONTRACT, State.ERROR, () -> error != null, "Validation failed"),
			transition(State.VALIDATE_CONTRACT, State.DO_CONTRACT,
				() -> contractValidated, "Contract verified"),
			transition(State.DO_CONTRACT, State.ERROR, () -> error != null, "Work failed"),
			transition(State.DO_CONTRACT, State.TRAVEL, () -> needsTravel,
				"Another floor has active work"),
			transition(State.DO_CONTRACT, State.TURN_IN_CONTRACT, this::allFixed,
				"Every active hotspot has an authoritative effect"),
			transition(State.TRAVEL, State.ERROR, () -> error != null, "Efficient Walker failed"),
			transition(State.TRAVEL, State.GET_SETUP,
				() -> travelArrived && travelReturnState == State.GET_SETUP, "Setup bank reached"),
			transition(State.TRAVEL, State.GET_CONTRACT,
				() -> travelArrived && travelReturnState == State.GET_CONTRACT, "Contractor reached"),
			transition(State.TRAVEL, State.GO_TO_CONTRACT,
				() -> travelArrived && travelReturnState == State.GO_TO_CONTRACT,
				"Assigned home reached"),
			transition(State.TRAVEL, State.DO_CONTRACT,
				() -> travelArrived && travelReturnState == State.DO_CONTRACT, "Work floor reached"),
			transition(State.TRAVEL, State.TURN_IN_CONTRACT,
				() -> travelArrived && travelReturnState == State.TURN_IN_CONTRACT,
				"Homeowner reached"),
			transition(State.TURN_IN_CONTRACT, State.ERROR, () -> error != null, "Turn-in failed"),
			transition(State.TURN_IN_CONTRACT, State.TRAVEL, () -> needsTravel,
				"Returning to the homeowner"),
			transition(State.TURN_IN_CONTRACT, State.DONE,
				() -> complete && !Rs2Dialogue.isInDialogue(),
				"Contract completion message observed and dialogue closed"),
			transition(State.DONE, State.GET_CONTRACT, () -> nextContractReady, "Getting the next contract"),
			transition(State.ERROR, State.VALIDATE_CONTRACT, () -> error == null, "The error was cleared")
		);
	}

	private Transition<State> transition(
		State from, State to, java.util.function.BooleanSupplier condition, String reason)
	{
		return Transition.<State>from(from)
			.when(condition, reason)
			.because(reason)
			.goTo(to);
	}

	@Override
	protected void onState(State state)
	{
		if (walker == null && state != State.ERROR)
		{
			try
			{
				walker = WalkerBridge.bind(pluginManager);
			}
			catch (ReflectiveOperationException | IllegalStateException exception)
			{
				log.warn("Efficient Walker connection failed", exception);
				fail("Enable exactly one compatible Efficient Walker instance, then restart Mahogany Homes.");
				return;
			}
		}
		if (walker != null && !walker.isAvailable())
		{
			fail("Efficient Walker became unavailable. Enable exactly one compatible instance, then restart Mahogany Homes.");
			return;
		}
		switch (state)
		{
			case GET_SETUP:
				getSetup();
				break;
			case GET_CONTRACT:
				getContract();
				break;
			case READY:
				ready();
				break;
			case GO_TO_CONTRACT:
				goToContract();
				break;
			case VALIDATE_CONTRACT:
				validateContract();
				break;
			case DO_CONTRACT:
				doContract();
				break;
			case TRAVEL:
				travel();
				break;
			case TURN_IN_CONTRACT:
				turnIn();
				break;
			case DONE:
				Microbot.status = "Mahogany Homes: contract complete";
				if (readyForNextContract(readyTick, completedAtTick))
				{
					nextContractReady = true;
				}
				break;
			case ERROR:
				Microbot.status = "Mahogany Homes: " + error;
				break;
			default:
				fail("The plugin entered an unexpected state. Restart it. If this repeats, report the debug log.");
		}
	}

	@Override
	protected State onError(State state, Exception exception)
	{
		log.warn("Unexpected contract failure in {}", state, exception);
		fail("An unexpected error stopped the plugin. Check your current contract before restarting. If this repeats, report the debug log.");
		return State.ERROR;
	}

	public boolean run(MahoganyHomesConfig config)
	{
		this.config = config;
		loadAssignment();
		mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(net.runelite.client.util.RunnableExceptionLogger.wrap(() ->
		{
			int tick = readyTick;
			if (tick == processedTick)
			{
				return;
			}
			processedTick = tick;
			step();
		}), 0, 50, TimeUnit.MILLISECONDS);
		return true;
	}

	@Override
	public void shutdown()
	{
		if (walker != null)
		{
			walker.cancel();
		}
		super.shutdown();
	}

	void onGameTick(int tick)
	{
		readyTick = tick;
	}

	void onGameMessage(String message)
	{
		String text = Rs2UiHelper.stripTagsToSpace(message).trim();
		if (isCompletionMessage(text))
		{
			complete = true;
			completedAtTick = readyTick;
			clearAssignment();
		}
	}

	static boolean readyForNextContract(int currentTick, int completionTick)
	{
		return completionTick >= 0 && currentTick - completionTick >= 2;
	}

	private void resetCompletedContract()
	{
		complete = false;
		nextContractReady = false;
		completedAtTick = -1;
		completedEffects = 0;
		error = null;
		setupTier = null;
		setupPlanks = 0;
		setupSteelBars = 0;
		currentContract = false;
		currentContractAtHomeowner = false;
		setupReady = false;
		teleportResourcesPrepared = false;
		contractor = null;
		contractRequestTick = -1;
		contractAttempts = 0;
		lastContractDialogue = "";
		needsTravel = false;
		travelArrived = false;
		travelRequested = false;
		travelReturnState = null;
		travelTarget = null;
		travelToNearestBank = false;
		lastTravelPosition = null;
		pending = null;
		activeWork = null;
		workAttempts = 0;
		missingObjectSince = -1;
		turnInRequested = false;
		turnInAttemptTick = 0;
		turnInAttempts = 0;
		clearBankAction();
		clearSackAction();
		clearSawAction();
		if (walker != null)
		{
			walker.cancel();
		}
	}

	private void getSetup()
	{
		if (completedAtTick >= 0)
		{
			resetCompletedContract();
		}
		refreshHotspots();
		WorldPoint position = Rs2Player.getWorldLocation();
		MahoganyHomesData preferredTier = assignedTier == null ? config.contractTier() : assignedTier;
		MahoganyHomesContractData assignedContract =
			MahoganyHomesContractData.forHomeowner(assignedHomeowner);
		if (assignedContract == null && assignedHomeowner == null
			&& isActiveContractLayout(hotspotValues))
		{
			assignedContract = MahoganyHomesContractData.forLocation(position);
			if (assignedContract != null)
			{
				rememberAssignment(assignedContract.homeowner, preferredTier);
			}
		}
		boolean assignedAtHome = assignedContract != null && assignedContract.contains(position)
			&& isLoadedContractLayout(hotspotValues);
		Setup setup = assignedAtHome
			? new Setup(preferredTier, assignedContract.requiredPlanks(hotspotValues),
				assignedContract.requiredSteelBars(hotspotValues), true)
			: selectSetup(preferredTier, false, hotspotValues);
		setupTier = setup.tier;
		setupPlanks = setup.planks;
		setupSteelBars = setup.steelBars;
		currentContract = assignedHomeowner != null || setup.currentContract;
		Rs2NpcModel setupHomeowner = assignedContract == null ? null
			: Microbot.getRs2NpcCache().query().withId(assignedContract.npcId).nearest();
		currentContractAtHomeowner = isCurrentContractAtHomeowner(assignedContract, false,
			setupHomeowner == null ? null : setupHomeowner.getWorldLocation(), position,
			Rs2Tile.getReachableTilesFromTile(position).keySet());

		if (Rs2Player.getRealSkillLevel(Skill.CONSTRUCTION) < setupTier.getConstructionLevel())
		{
			fail(setupTier.getName() + " contracts require "
				+ setupTier.getConstructionLevel() + " Construction. Select a lower contract tier.");
			return;
		}
		if (Rs2Bank.isOpen() && shouldPrepareSackAtBank(setup))
		{
			prepareAtBank(setup);
			return;
		}
		if (hasSetup(setup))
		{
			if (shouldPrepareTeleportResources(Rs2Bank.isOpen(), teleportResourcesPrepared))
			{
				setupReady = false;
				if (!walker.prepareTeleportResourcesAtBank())
				{
					return;
				}
				teleportResourcesPrepared = true;
			}
			if (Rs2Bank.isOpen())
			{
				Rs2Bank.closeBank();
				return;
			}
			if (!prepareAmysSaw())
			{
				return;
			}
			setupReady = true;
			Microbot.status = "Mahogany Homes: " + setupTier.getName() + " setup ready";
			return;
		}
		setupReady = false;
		if (!Rs2Bank.isOpen())
		{
			if (!travelArrived || !travelToNearestBank)
			{
				requestNearestBank(State.GET_SETUP);
				return;
			}
			if (!Rs2Bank.openBank())
			{
				fail("Could not open the bank. Move within reach of a banker or bank booth, then restart.");
			}
			return;
		}
		prepareAtBank(setup);
	}

	static boolean shouldPrepareTeleportResources(boolean bankOpen, boolean resourcesPrepared)
	{
		return bankOpen && !resourcesPrepared;
	}

	static boolean isCurrentContractAtHomeowner(MahoganyHomesContractData contract,
		boolean leelaContract, WorldPoint homeowner, WorldPoint position, Set<WorldPoint> reachable)
	{
		return leelaContract || contract != null
			&& isHomeownerReachable(position,
				homeowner == null ? contract.destination : homeowner, reachable);
	}

	private void prepareAtBank(Setup setup)
	{
		if (awaitBankAction() || awaitSackAction())
		{
			return;
		}
		if (Rs2Inventory.contains(setup.tier.getNotedPlankId()))
		{
			trackBankAction(setup.tier.getNotedPlankId());
			if (!Rs2Bank.depositAll(setup.tier.getNotedPlankId()))
			{
				clearBankAction();
				fail("Could not deposit noted " + setup.tier.getPlankName() + ". Deposit them manually, then restart.");
			}
			return;
		}
		if (Rs2Inventory.contains(ItemID.Cert.STEEL_BAR))
		{
			trackBankAction(ItemID.Cert.STEEL_BAR);
			if (!Rs2Bank.depositAll(ItemID.Cert.STEEL_BAR))
			{
				clearBankAction();
				fail("Could not deposit noted steel bars. Deposit them manually, then restart.");
			}
			return;
		}
		if (!PlankSack.isCarried() && Rs2Bank.hasBankItem(ItemID.PLANK_SACK, 1))
		{
			if (Rs2Inventory.emptySlotCount() == 0 && depositFirstSurplus(setup.tier))
			{
				return;
			}
			withdraw(ItemID.PLANK_SACK, 1, "plank sack");
			return;
		}
		int wrongPlank = PlankSack.firstWrongInventoryPlank(setup.tier);
		if (wrongPlank >= 0)
		{
			trackBankAction(wrongPlank);
			if (!Rs2Bank.depositAll(wrongPlank))
			{
				clearBankAction();
				fail("Could not deposit planks that do not match your contract tier. Deposit them manually, then restart.");
			}
			return;
		}
		if (PlankSack.isCarried() && PlankSack.hasWrongContents(setup.tier))
		{
			if (Rs2Inventory.emptySlotCount() == 0)
			{
				if (!depositFirstNonSack())
				{
					fail("Could not make room to empty the plank sack. Free inventory space at the bank, then restart.");
				}
				return;
			}
			trackSackAction();
			if (!PlankSack.empty())
			{
				clearSackAction();
				fail("Could not empty the plank sack. Check its contents and your free inventory space, then restart.");
			}
			return;
		}
		if (PlankSack.isCarried() && PlankSack.freeCapacity() > 0)
		{
			int loosePlanks = Rs2Inventory.count(setup.tier.getPlankId());
			if (loosePlanks > 0)
			{
				trackSackAction();
				if (!PlankSack.fill())
				{
					clearSackAction();
					fail("Could not fill the plank sack. Check the planks in your inventory and the sack's contents, then restart.");
				}
				return;
			}
			int fillWithdrawal = PlankSack.fillWithdrawal(PlankSack.count(setup.tier), 0,
				Rs2Bank.count(setup.tier.getPlankId()));
			if (fillWithdrawal > 0)
			{
				if (Rs2Inventory.emptySlotCount() < fillWithdrawal
					&& depositFirstSurplus(setup.tier))
				{
					return;
				}
				withdraw(setup.tier.getPlankId(), fillWithdrawal, setup.tier.getPlankName());
				return;
			}
		}
		int loosePlanks = Rs2Inventory.count(setup.tier.getPlankId());
		int availableLoosePlanks = Math.min(Math.max(0, setup.planks - loosePlanks),
			Rs2Bank.count(setup.tier.getPlankId()));
		int missingSlots = availableLoosePlanks
			+ Math.max(0, setup.steelBars - Rs2Inventory.count(ItemID.STEEL_BAR))
			+ (hasTool(ItemCollections.HAMMER) ? 0 : 1)
			+ (hasTool(ItemCollections.SAW) ? 0 : 1);
		if (Rs2Inventory.emptySlotCount() < missingSlots)
		{
			if (!depositFirstSurplus(setup.tier))
			{
				fail("Could not clear your inventory for supplies. Open the bank and deposit unneeded items, then restart.");
			}
			return;
		}
		if (!hasTool(ItemCollections.HAMMER))
		{
			withdrawTool(ItemCollections.HAMMER, "hammer");
			return;
		}
		if (!hasTool(ItemCollections.SAW))
		{
			withdrawTool(AmysSaw.preferredSawIds(), "saw");
			return;
		}
		int missingLoosePlanks = setup.planks - Rs2Inventory.count(setup.tier.getPlankId());
		int withdrawablePlanks = Math.min(Math.max(0, missingLoosePlanks),
			Rs2Bank.count(setup.tier.getPlankId()));
		if (withdrawablePlanks > 0)
		{
			withdraw(setup.tier.getPlankId(), withdrawablePlanks, setup.tier.getPlankName());
			return;
		}
		if (PlankSack.available(setup.tier) < setup.planks)
		{
			fail("Missing " + (setup.planks - PlankSack.available(setup.tier)) + " " + setup.tier.getPlankName()
				+ ". Add them to your bank, then restart.");
			return;
		}
		int missingBars = setup.steelBars - Rs2Inventory.count(ItemID.STEEL_BAR);
		if (missingBars > 0)
		{
			withdraw(ItemID.STEEL_BAR, missingBars, "steel bars");
		}
	}

	private void withdrawTool(ItemCollections collection, String name)
	{
		withdrawTool(collection.getItems().stream().mapToInt(Integer::intValue).toArray(), name);
	}

	private void withdrawTool(int[] ids, String name)
	{
		for (int id : ids)
		{
			if (Rs2Bank.hasBankItem(id, 1))
			{
				trackBankAction(id);
				if (!Rs2Bank.withdrawOne(id))
				{
					clearBankAction();
					fail("Could not withdraw " + name + ". Open the bank and check that the item is available, then restart.");
				}
				return;
			}
		}
		fail("No " + name + " found in your bank. Add it, then restart.");
	}

	private boolean shouldPrepareSackAtBank(Setup setup)
	{
		if (!PlankSack.isCarried())
		{
			return Rs2Bank.hasBankItem(ItemID.PLANK_SACK, 1);
		}
		if (PlankSack.hasWrongContents(setup.tier)
			|| PlankSack.firstWrongInventoryPlank(setup.tier) >= 0)
		{
			return true;
		}
		int bankedPlanks = Rs2Bank.count(setup.tier.getPlankId());
		return PlankSack.freeCapacity() > 0
			&& (Rs2Inventory.count(setup.tier.getPlankId()) > 0 || bankedPlanks > 0)
			|| Rs2Inventory.count(setup.tier.getPlankId()) < setup.planks && bankedPlanks > 0;
	}

	private boolean depositFirstNonSack()
	{
		int id = Rs2Inventory.items(item -> item.getId() != ItemID.PLANK_SACK)
			.mapToInt(item -> item.getId()).findFirst().orElse(-1);
		return deposit(id, "inventory item");
	}

	private boolean depositFirstSurplus(MahoganyHomesData tier)
	{
		int id = Rs2Inventory.items(item -> !isSetupItem(item.getId(), tier))
			.mapToInt(item -> item.getId()).findFirst().orElse(-1);
		return deposit(id, "surplus inventory item");
	}

	private boolean deposit(int id, String name)
	{
		if (id < 0)
		{
			return false;
		}
		trackBankAction(id);
		if (Rs2Bank.depositAll(id))
		{
			return true;
		}
		clearBankAction();
		fail("Could not deposit " + name + ". Deposit it manually, then restart.");
		return false;
	}

	private static boolean isSetupItem(int id, MahoganyHomesData tier)
	{
		return id == ItemID.PLANK_SACK || id == tier.getPlankId() || id == ItemID.STEEL_BAR
			|| ItemCollections.HAMMER.getItems().contains(id)
			|| ItemCollections.SAW.getItems().contains(id);
	}

	private boolean prepareAmysSaw()
	{
		if (awaitSawAction())
		{
			return false;
		}
		if (AmysSaw.isEquipped())
		{
			return true;
		}
		int id = AmysSaw.inventoryItemId();
		if (id < 0)
		{
			return true;
		}
		trackSawAction(id);
		if (!AmysSaw.prepare(id))
		{
			clearSawAction();
			fail("Could not prepare Amy's saw. Put it in your inventory or equip it, then restart.");
		}
		return false;
	}

	private boolean awaitSackAction()
	{
		if (pendingSackQuantity < 0)
		{
			return false;
		}
		if (bankActionChanged(pendingSackQuantity, PlankSack.total()))
		{
			clearSackAction();
			return false;
		}
		if (readyTick - pendingSackActionTick >= NO_PROGRESS_TICKS)
		{
			if (bankActionFailed(readyTick - pendingSackActionTick, sackActionAttempts))
			{
				fail("Could not confirm that the plank sack's contents changed. Check its contents before restarting.");
			}
			else
			{
				pendingSackQuantity = -1;
			}
		}
		return pendingSackQuantity >= 0;
	}

	private void trackSackAction()
	{
		int quantity = PlankSack.total();
		sackActionAttempts = lastSackQuantity == quantity ? sackActionAttempts + 1 : 1;
		lastSackQuantity = quantity;
		pendingSackQuantity = quantity;
		pendingSackActionTick = readyTick;
	}

	private void clearSackAction()
	{
		pendingSackQuantity = -1;
		lastSackQuantity = -1;
		sackActionAttempts = 0;
	}

	private boolean awaitSawAction()
	{
		if (pendingSawItemId < 0)
		{
			return false;
		}
		if (bankActionChanged(pendingSawItemQuantity, Rs2Inventory.count(pendingSawItemId)))
		{
			clearSawAction();
			return false;
		}
		if (readyTick - pendingSawActionTick >= NO_PROGRESS_TICKS)
		{
			if (bankActionFailed(readyTick - pendingSawActionTick, sawActionAttempts))
			{
				fail("Could not confirm that Amy's saw was equipped or unequipped. Check your tools before restarting.");
			}
			else
			{
				pendingSawItemId = -1;
			}
		}
		return pendingSawItemId >= 0;
	}

	private void trackSawAction(int itemId)
	{
		sawActionAttempts = lastSawItemId == itemId ? sawActionAttempts + 1 : 1;
		lastSawItemId = itemId;
		pendingSawItemId = itemId;
		pendingSawItemQuantity = Rs2Inventory.count(itemId);
		pendingSawActionTick = readyTick;
	}

	private void clearSawAction()
	{
		pendingSawItemId = -1;
		lastSawItemId = -1;
		sawActionAttempts = 0;
	}

	private void withdraw(int id, int amount, String name)
	{
		if (!Rs2Bank.hasBankItem(id, amount))
		{
			int missing = amount - Rs2Bank.count(id);
			fail(missing > 0 ? "Missing " + missing + " " + name + ". Add them to your bank, then restart."
				: "Could not read your bank contents. Close and reopen the bank, then restart.");
			return;
		}
		trackBankAction(id);
		boolean withdrawn = amount == 1 ? Rs2Bank.withdrawOne(id) : Rs2Bank.withdrawX(id, amount);
		if (!withdrawn)
		{
			clearBankAction();
			fail("Could not withdraw " + amount + " " + name + ". Check the available amount and free inventory space, then restart.");
		}
	}

	private boolean awaitBankAction()
	{
		if (pendingBankItemId < 0)
		{
			return false;
		}
		if (bankActionChanged(pendingBankItemQuantity, Rs2Inventory.count(pendingBankItemId)))
		{
			clearBankAction();
			return false;
		}
		if (readyTick - pendingBankActionTick >= NO_PROGRESS_TICKS)
		{
			if (bankActionFailed(readyTick - pendingBankActionTick, bankActionAttempts))
			{
				fail("Could not confirm the bank transfer. Check your inventory and bank before restarting.");
			}
			else
			{
				releaseBankAction();
			}
		}
		return true;
	}

	private void trackBankAction(int itemId)
	{
		bankActionAttempts = lastBankActionItemId == itemId ? bankActionAttempts + 1 : 1;
		lastBankActionItemId = itemId;
		pendingBankItemId = itemId;
		pendingBankItemQuantity = Rs2Inventory.count(itemId);
		pendingBankActionTick = readyTick;
	}

	static boolean bankActionChanged(int previousSlots, int currentSlots)
	{
		return previousSlots != currentSlots;
	}

	static boolean bankActionFailed(int elapsedTicks, int attempts)
	{
		return elapsedTicks >= NO_PROGRESS_TICKS && attempts >= MAX_ATTEMPTS;
	}

	private void clearBankAction()
	{
		releaseBankAction();
		lastBankActionItemId = -1;
		bankActionAttempts = 0;
	}

	private void releaseBankAction()
	{
		pendingBankItemId = -1;
	}

	private static boolean hasSetup(Setup setup)
	{
		return hasTool(ItemCollections.HAMMER) && hasTool(ItemCollections.SAW)
			&& PlankSack.available(setup.tier) >= setup.planks
			&& Rs2Inventory.count(ItemID.STEEL_BAR) >= setup.steelBars;
	}

	private void getContract()
	{
		if (completedAtTick >= 0)
		{
			resetCompletedContract();
		}
		setupTier = tierForContractRequest(setupTier, config.contractTier());
		contractDialogueClosed = contractAcquired && !Rs2Dialogue.isInDialogue();
		if (contractDialogueClosed)
		{
			return;
		}

		WorldPoint position = Rs2Player.getWorldLocation();
		if (contractor == null)
		{
			contractor = MahoganyHomesContractorData.nearest(position);
		}
		if (position.getPlane() != contractor.location.getPlane()
			|| position.distanceTo(contractor.location) > 8)
		{
			requestTravel(contractor.location, State.GET_CONTRACT);
			return;
		}

		String dialogue = Rs2Dialogue.getDialogueText();
		if (!dialogue.equals(lastContractDialogue))
		{
			lastContractDialogue = dialogue;
			contractRequestTick = readyTick;
		}
		Assignment assignment = parseAssignment(dialogue, setupTier);
		if (assignment != null)
		{
			rememberAssignment(assignment.homeowner, assignment.tier);
			contractAcquired = true;
			if (Rs2Dialogue.hasContinue())
			{
				Rs2Dialogue.clickContinue();
			}
			return;
		}
		if (Rs2Dialogue.hasSelectAnOption())
		{
			if (!Rs2Dialogue.hasDialogueOption(setupTier.getContractOption()))
			{
				if (contractOptionTimedOut(readyTick, contractRequestTick))
				{
					fail("Could not select " + setupTier.getContractOption() + ". Close the dialogue and restart.");
				}
				return;
			}
			Rs2Dialogue.clickOption(setupTier.getContractOption());
			return;
		}
		if (Rs2Dialogue.hasContinue())
		{
			Rs2Dialogue.clickContinue();
			return;
		}
		if (contractAttempts > 0 && readyTick - contractRequestTick < NO_PROGRESS_TICKS)
		{
			return;
		}
		if (contractAttempts >= MAX_ATTEMPTS)
		{
			fail("Could not confirm a new contract from " + contractor.name + ". Speak to them to check your assignment, then restart.");
			return;
		}

		Rs2NpcModel npc = Microbot.getRs2NpcCache().query().withName(contractor.name).nearest();
		if (npc == null || !npc.click("Contract"))
		{
			fail("Could not find " + contractor.name + " to request a contract. Move near them, then restart.");
			return;
		}
		contractRequestTick = readyTick;
		contractAttempts++;
		Microbot.status = "Mahogany Homes: requesting a " + setupTier.getName() + " contract";
	}

	static boolean contractOptionTimedOut(int tick, int requestedAt)
	{
		return requestedAt >= 0 && tick - requestedAt >= NO_PROGRESS_TICKS;
	}

	static MahoganyHomesData tierForContractRequest(
		MahoganyHomesData currentTier, MahoganyHomesData configuredTier)
	{
		return currentTier == null ? configuredTier : currentTier;
	}

	private void ready()
	{
		Microbot.status = "Mahogany Homes: " + assignedTier.getName()
			+ " contract assigned to " + assignedHomeowner;
	}

	private void goToContract()
	{
		MahoganyHomesContractData contract = MahoganyHomesContractData.forHomeowner(assignedHomeowner);
		if (contract == null)
		{
			fail("Travel to " + assignedHomeowner + "'s home is not supported. Report this assignment.");
			return;
		}
		if (contractArrived)
		{
			Microbot.status = "Mahogany Homes: arrived at " + assignedHomeowner + "'s contract";
			return;
		}

		WorldPoint position = Rs2Player.getWorldLocation();
		Set<WorldPoint> reachable = Rs2Tile.getReachableTilesFromTile(position).keySet();
		Rs2NpcModel homeowner = Microbot.getRs2NpcCache().query().withId(contract.npcId).nearest();
		if (homeowner != null)
		{
			WorldPoint homeownerLocation = homeowner.getWorldLocation();
			if (isHomeownerReachable(position, homeownerLocation, reachable))
			{
				walker.cancel();
				needsTravel = false;
				contractArrived = true;
				Microbot.status = "Mahogany Homes: arrived at " + assignedHomeowner + "'s contract";
				return;
			}
			contractArrivalWaitTick = -1;
			requestTravel(homeownerApproach(position, homeownerLocation,
				Rs2Tile.getReachableTilesFromTile(homeownerLocation, 1).keySet()), State.GO_TO_CONTRACT);
			return;
		}
		if (position.getPlane() == contract.destination.getPlane()
			&& position.distanceTo(contract.destination) <= 3)
		{
			if (contractArrivalWaitTick < 0)
			{
				contractArrivalWaitTick = readyTick;
			}
			else if (readyTick - contractArrivalWaitTick >= NO_PROGRESS_TICKS)
			{
				fail("Could not find " + assignedHomeowner + " at the assigned home. Check the building and floor, then restart.");
			}
			return;
		}

		contractArrivalWaitTick = -1;
		requestTravel(contract.destination, State.GO_TO_CONTRACT);
	}

	static boolean isHomeownerInRange(WorldPoint player, WorldPoint homeowner)
	{
		return player != null && homeowner != null && player.getPlane() == homeowner.getPlane()
			&& player.distanceTo(homeowner) <= 8;
	}

	static boolean isHomeownerReachable(WorldPoint player, WorldPoint homeowner,
		Set<WorldPoint> reachable)
	{
		return isHomeownerInRange(player, homeowner) && reachable.contains(homeowner);
	}

	static WorldPoint homeownerApproach(WorldPoint player, WorldPoint homeowner,
		Set<WorldPoint> homeownerArea)
	{
		return homeownerArea.stream()
			.filter(tile -> !tile.equals(homeowner))
			.min(Comparator.comparingInt((WorldPoint tile) -> tile.distanceTo2D(player))
				.thenComparingInt(WorldPoint::getX)
				.thenComparingInt(WorldPoint::getY))
			.orElse(homeowner);
	}

	static Assignment parseAssignment(String dialogue, MahoganyHomesData fallbackTier)
	{
		if (dialogue == null)
		{
			return null;
		}
		String text = Rs2UiHelper.stripTagsToSpace(dialogue).trim();
		Matcher homeMatcher = CONTRACT_HOME.matcher(text);
		if (!homeMatcher.find())
		{
			return null;
		}
		Matcher tierMatcher = CONTRACT_TIER.matcher(text);
		MahoganyHomesData tier = tierMatcher.find()
			? MahoganyHomesData.fromName(tierMatcher.group(1)) : fallbackTier;
		if (tier == null)
		{
			return null;
		}
		String homeowner = homeMatcher.group(1);
		return new Assignment(Character.toUpperCase(homeowner.charAt(0)) + homeowner.substring(1), tier);
	}

	private void loadAssignment()
	{
		String homeowner = configManager.getRSProfileConfiguration(
			MahoganyHomesConfig.GROUP, ASSIGNED_HOME_KEY);
		String tierName = configManager.getRSProfileConfiguration(
			MahoganyHomesConfig.GROUP, ASSIGNED_TIER_KEY);
		MahoganyHomesData tier = tierName == null ? null : MahoganyHomesData.fromName(tierName);
		if (homeowner == null || homeowner.isBlank() || tier == null)
		{
			clearAssignment();
			return;
		}
		assignedHomeowner = homeowner;
		assignedTier = tier;
	}

	private void rememberAssignment(String homeowner, MahoganyHomesData tier)
	{
		if (!setupMatchesAssignment(setupTier, tier))
		{
			setupReady = false;
		}
		if (homeowner.equals(assignedHomeowner) && tier == assignedTier)
		{
			return;
		}
		assignedHomeowner = homeowner;
		assignedTier = tier;
		contractArrived = false;
		contractValidated = false;
		contractArrivalWaitTick = -1;
		configManager.setRSProfileConfiguration(MahoganyHomesConfig.GROUP, ASSIGNED_HOME_KEY, homeowner);
		configManager.setRSProfileConfiguration(MahoganyHomesConfig.GROUP, ASSIGNED_TIER_KEY, tier.getName());
	}

	static boolean setupMatchesAssignment(MahoganyHomesData setup, MahoganyHomesData assignment)
	{
		return setup != null && setup == assignment;
	}

	private void clearAssignment()
	{
		assignedHomeowner = null;
		assignedTier = null;
		contractAcquired = false;
		contractDialogueClosed = false;
		contractArrived = false;
		contractValidated = false;
		contractArrivalWaitTick = -1;
		configManager.unsetRSProfileConfiguration(MahoganyHomesConfig.GROUP, ASSIGNED_HOME_KEY);
		configManager.unsetRSProfileConfiguration(MahoganyHomesConfig.GROUP, ASSIGNED_TIER_KEY);
	}

	private void validateContract()
	{
		refreshHotspots();
		MahoganyHomesContractData contract = MahoganyHomesContractData.forHomeowner(assignedHomeowner);
		if (contract == null)
		{
			fail("The contract for " + assignedHomeowner + " is not supported. Report this assignment.");
			return;
		}
		validateAssignedContract(contract);
	}

	private void validateAssignedContract(MahoganyHomesContractData contract)
	{
		WorldPoint position = Rs2Player.getWorldLocation();
		Set<WorldPoint> reachable = Rs2Tile.getReachableTilesFromTile(position).keySet();
		Rs2NpcModel homeowner = Microbot.getRs2NpcCache().query().withId(contract.npcId).nearest();
		if (homeowner == null || !isHomeownerReachable(position,
			homeowner.getWorldLocation(), reachable))
		{
			fail("Could not speak to " + contract.homeowner + " to check the contract. Move near them, then restart.");
			return;
		}
		if (!isLoadedContractLayout(hotspotValues))
		{
			fail("Could not recognise the repair layout for " + contract.homeowner + ". Report this assignment and debug log.");
			return;
		}
		if (!hasTool(ItemCollections.HAMMER) || !hasTool(ItemCollections.SAW))
		{
			fail("Missing a usable " + (!hasTool(ItemCollections.HAMMER) ? (!hasTool(ItemCollections.SAW) ? "hammer and saw" : "hammer") : "saw")
				+ ". Put the missing tool in your inventory or equip a supported alternative, then restart.");
			return;
		}
		int planks = contract.requiredPlanks(hotspotValues);
		if (PlankSack.available(assignedTier) < planks)
		{
			fail("Missing " + (planks - PlankSack.available(assignedTier)) + " " + assignedTier.getPlankName()
				+ " for this contract. Withdraw them, then restart.");
			return;
		}
		int bars = contract.requiredSteelBars(hotspotValues);
		if (Rs2Inventory.count(ItemID.STEEL_BAR) < bars)
		{
			fail("Missing " + (bars - Rs2Inventory.count(ItemID.STEEL_BAR))
				+ " steel bars for this contract. Withdraw them, then restart.");
			return;
		}
		contractValidated = true;
		Microbot.status = "Mahogany Homes: " + contract.homeowner + " contract verified";
	}

	private void doContract()
	{
		refreshHotspots();
		MahoganyHomesContractData contract = MahoganyHomesContractData.forHomeowner(assignedHomeowner);
		if (contract == null)
		{
			fail("The contract for " + assignedHomeowner + " is not supported. Report this assignment.");
			return;
		}
		List<Work> work = contract.work;
		if (!isActiveOrFixedLayout(hotspotValues))
		{
			fail("The repair state at " + contract.homeowner + "'s home was not recognised. Check the contract and report the issue if it repeats.");
			return;
		}
		if (allFixed())
		{
			Microbot.status = "Mahogany Homes: " + contract.homeowner
				+ " contract furniture complete";
			return;
		}
		if (pending != null)
		{
			observePendingWork();
			return;
		}

		WorldPoint position = Rs2Player.getWorldLocation();
		Set<WorldPoint> reachable = Rs2Tile.getReachableTilesFromTile(position).keySet();
		Work next = workAttempts > 0 && activeWork != null
			? activeWork : nextWork(work, hotspotValues, position, reachable);
		if (next == null)
		{
			Work otherFloor = nextWork(work, hotspotValues, 1 - position.getPlane());
			if (otherFloor != null)
			{
				WorldPoint landing = contract.landing(otherFloor.location.getPlane());
				requestTravel(landing, State.DO_CONTRACT);
			}
			return;
		}
		boolean accessReachable = next.access == null || reachable.contains(next.access);
		if (needsWorkAccess(position, next, workAttempts, accessReachable))
		{
			requestTravel(next.access, State.DO_CONTRACT);
			return;
		}

		String action = actionFor(hotspotValues[next.hotspot]);
		Rs2TileObjectModel object = exactObject(next);
		if (object == null || !hasAction(object.getObjectComposition(), action))
		{
			if (missingObjectSince < 0)
			{
				missingObjectSince = readyTick;
			}
			else if (readyTick - missingObjectSince >= NO_PROGRESS_TICKS)
			{
				fail("Could not find or select the next furniture repair. Check the assigned building and floor, then restart.");
			}
			return;
		}

		missingObjectSince = -1;
		pending = next;
		activeWork = next;
		pendingValue = hotspotValues[next.hotspot];
		lastWorkProgressTick = readyTick;
		workAttempts++;
		object.click(action);
		Microbot.status = "Mahogany Homes: " + action + " " + object.getName();
	}

	private void observePendingWork()
	{
		int value = hotspotValues[pending.hotspot];
		if (value != pendingValue)
		{
			completedEffects++;
			pending = null;
			activeWork = null;
			workAttempts = 0;
			lastWorkProgressTick = readyTick;
			return;
		}
		if (Rs2Player.isMoving() || Rs2Player.isAnimating())
		{
			lastWorkProgressTick = readyTick;
			return;
		}
		if (readyTick - lastWorkProgressTick < NO_PROGRESS_TICKS)
		{
			return;
		}
		if (workAttempts >= MAX_ATTEMPTS)
		{
			fail("Could not confirm the furniture repair. Check your supplies and the unfinished furniture before restarting.");
			return;
		}
		pending = null;
	}

	private void requestTravel(WorldPoint target, State returnState)
	{
		travelTarget = target;
		travelToNearestBank = false;
		prepareTravel(returnState);
	}

	private void requestNearestBank(State returnState)
	{
		travelTarget = null;
		travelToNearestBank = true;
		prepareTravel(returnState);
	}

	private void prepareTravel(State returnState)
	{
		travelReturnState = returnState;
		travelRequested = false;
		travelArrived = false;
		needsTravel = true;
		lastTravelPosition = Rs2Player.getWorldLocation();
		lastTravelProgressTick = readyTick;
	}

	private void travel()
	{
		WorldPoint position = Rs2Player.getWorldLocation();
		if (hasTravelArrived(travelRequested, walker.status()))
		{
			walker.cancel();
			needsTravel = false;
			travelArrived = true;
			return;
		}
		if (!position.equals(lastTravelPosition))
		{
			lastTravelPosition = position;
			lastTravelProgressTick = readyTick;
		}
		if (!travelRequested)
		{
			boolean accepted = travelToNearestBank
				? walker.walkToNearestBank() : walker.walkTo(travelTarget);
			if (!accepted)
			{
				fail("Could not start travel to " + travelDescription() + ". Check Efficient Walker's error message, then restart.");
				return;
			}
			travelRequested = true;
			return;
		}
		if ("BLOCKED".equals(walker.status()))
		{
			log.debug("Efficient Walker blocked: {}", walker.planningFailure());
			fail("Travel to " + travelDescription() + " stopped. Check Efficient Walker's error message before restarting.");
		}
		else if (readyTick - lastTravelProgressTick >= 30)
		{
			fail("Could not make progress towards " + travelDescription() + ". Check for an obstacle or open dialogue, then restart.");
		}
	}

	private String travelDescription()
	{
		return travelToNearestBank ? "a bank" : travelReturnState != State.GET_CONTRACT && assignedHomeowner != null
			? assignedHomeowner + "'s home" : "the contractor";
	}

	static boolean hasTravelArrived(boolean requested, String walkerStatus)
	{
		return requested && "ARRIVED".equals(walkerStatus);
	}

	private void turnIn()
	{
		refreshHotspots();
		if (!allFixed())
		{
			fail("The contract still has unfinished furniture. Check the remaining repairs, then restart.");
			return;
		}
		if (Rs2Dialogue.hasContinue())
		{
			Rs2Dialogue.clickContinue();
			return;
		}
		if (Rs2Dialogue.hasSelectAnOption())
		{
			if (!Rs2Dialogue.hasDialogueOption("Yes, I'd love a cuppa."))
			{
				fail("An unexpected dialogue appeared while finishing the contract. Close it and speak to " + assignedHomeowner + " to check the contract.");
				return;
			}
			Rs2Dialogue.clickOption("Yes, I'd love a cuppa.");
			return;
		}
		if (complete || Rs2Dialogue.isInDialogue())
		{
			return;
		}

		MahoganyHomesContractData contract = MahoganyHomesContractData.forHomeowner(assignedHomeowner);
		if (contract == null)
		{
			fail("The contract for " + assignedHomeowner + " is not supported. Report this assignment.");
			return;
		}
		String homeownerName = contract.homeowner;
		int homeownerId = contract.npcId;
		WorldPoint target = contract.destination;
		WorldPoint position = Rs2Player.getWorldLocation();
		if (position.getPlane() != target.getPlane() || position.distanceTo(target) > 8)
		{
			requestTravel(target, State.TURN_IN_CONTRACT);
			return;
		}
		Rs2NpcModel homeowner = Microbot.getRs2NpcCache().query().withId(homeownerId).nearest();
		if (homeowner == null)
		{
			fail("Could not find " + homeownerName + " to finish the contract. Move near them, then restart.");
			return;
		}
		Set<WorldPoint> reachable = Rs2Tile.getReachableTilesFromTile(position).keySet();
		WorldPoint homeownerLocation = homeowner.getWorldLocation();
		if (!isHomeownerReachable(position, homeownerLocation, reachable))
		{
			requestTravel(homeownerApproach(position, homeownerLocation,
				Rs2Tile.getReachableTilesFromTile(homeownerLocation, 1).keySet()),
				State.TURN_IN_CONTRACT);
			return;
		}
		if (turnInRequested && readyTick - turnInAttemptTick < NO_PROGRESS_TICKS)
		{
			return;
		}
		if (turnInAttempts >= MAX_ATTEMPTS)
		{
			fail("Could not confirm completion of " + homeownerName + "'s contract. Speak to them to check its status before restarting.");
			return;
		}
		if (!homeowner.click("Talk-to"))
		{
			fail("Could not find " + homeownerName + " to finish the contract. Move near them, then restart.");
			return;
		}
		turnInRequested = true;
		turnInAttemptTick = readyTick;
		turnInAttempts++;
		Microbot.status = "Mahogany Homes: turning in " + homeownerName + "'s contract";
	}

	static boolean isCompletionMessage(String message)
	{
		return message != null && COMPLETION_MESSAGE.matcher(message).matches();
	}

	private void refreshHotspots()
	{
		hotspotValues = Arrays.stream(HOTSPOT_VARBITS).map(Microbot::getVarbitValue).toArray();
	}

	private Rs2TileObjectModel exactObject(Work work)
	{
		return Microbot.getRs2TileObjectCache().query().withId(work.objectId).toList().stream()
			.filter(object -> work.location.equals(object.getWorldLocation()))
			.findFirst()
			.orElse(null);
	}

	private static boolean hasAction(ObjectComposition composition, String action)
	{
		return composition != null && action != null && composition.getActions() != null
			&& Arrays.stream(composition.getActions()).anyMatch(action::equalsIgnoreCase);
	}

	private static boolean hasTool(ItemCollections collection)
	{
		int[] ids = collection.getItems().stream().mapToInt(Integer::intValue).toArray();
		return Rs2Inventory.contains(ids) || Rs2Equipment.isWearing(ids);
	}

	static boolean isSupportedCurrentContract(int[] values)
	{
		return isSupportedLayout(values)
			&& Arrays.stream(values).anyMatch(MahoganyHomesScript::isActionable);
	}

	static boolean isActiveContractLayout(int[] values)
	{
		return values != null && values.length == HOTSPOT_VARBITS.length
			&& Arrays.stream(values).allMatch(MahoganyHomesScript::isKnownHotspotValue)
			&& Arrays.stream(values).anyMatch(MahoganyHomesScript::isActionable);
	}

	static boolean isActiveOrFixedLayout(int[] values)
	{
		return values != null && values.length == HOTSPOT_VARBITS.length
			&& Arrays.stream(values).allMatch(MahoganyHomesScript::isKnownHotspotValue);
	}

	static boolean isLoadedContractLayout(int[] values)
	{
		return isActiveOrFixedLayout(values) && Arrays.stream(values).anyMatch(value -> value != 0);
	}

	static boolean isSupportedLayout(int[] values)
	{
		if (values == null || values.length != HOTSPOT_VARBITS.length || values[7] != 0)
		{
			return false;
		}
		return Arrays.stream(values).allMatch(MahoganyHomesScript::isKnownHotspotValue);
	}

	static int requiredPlanks(int[] values)
	{
		return MahoganyHomesContractData.LEELA.requiredPlanks(values);
	}

	static int requiredSteelBars(int[] values)
	{
		return MahoganyHomesContractData.LEELA.requiredSteelBars(values);
	}

	static Setup selectSetup(MahoganyHomesData configuredTier, boolean atCurrentHome, int[] values)
	{
		if (atCurrentHome && isSupportedCurrentContract(values))
		{
			return new Setup(configuredTier, requiredPlanks(values), requiredSteelBars(values), true);
		}
		return new Setup(configuredTier, configuredTier.getSetupPlanks(),
			configuredTier.getSetupSteelBars(), false);
	}

	static boolean allFixed(int[] values)
	{
		return values != null && Arrays.stream(values).noneMatch(MahoganyHomesScript::isActionable);
	}

	private boolean allFixed()
	{
		return allFixed(hotspotValues);
	}

	static Work nextWork(int[] values, int plane)
	{
		return nextWork(MahoganyHomesContractData.LEELA.work, values, plane);
	}

	static Work nextWork(List<Work> workItems, int[] values, int plane)
	{
		return workItems.stream()
			.filter(work -> work.location.getPlane() == plane && isActionable(values[work.hotspot]))
			.findFirst()
			.orElse(null);
	}

	static Work nextWork(List<Work> workItems, int[] values, WorldPoint position)
	{
		return workItems.stream()
			.filter(work -> work.location.getPlane() == position.getPlane()
				&& isActionable(values[work.hotspot]))
			.min(Comparator.comparingInt(work -> position.distanceTo2D(work.location)))
			.orElse(null);
	}

	static Work nextWork(List<Work> workItems, int[] values, WorldPoint position,
		Set<WorldPoint> reachable)
	{
		Work nearestReachable = workItems.stream()
			.filter(work -> work.location.getPlane() == position.getPlane()
				&& isActionable(values[work.hotspot])
				&& (work.access == null || reachable.contains(work.access)))
			.min(Comparator.comparingInt(work -> position.distanceTo2D(work.location)))
			.orElse(null);
		return nearestReachable == null ? nextWork(workItems, values, position) : nearestReachable;
	}

	static boolean needsWorkAccess(WorldPoint position, Work work, int attempts,
		boolean accessReachable)
	{
		return work.access != null && !position.equals(work.access)
			&& (attempts > 0 || !accessReachable);
	}

	static String actionFor(int value)
	{
		switch (value)
		{
			case 1:
				return "Repair";
			case 3:
				return "Remove";
			case 4:
				return "Build";
			default:
				return null;
		}
	}

	private static boolean isActionable(int value)
	{
		return value == 1 || value == 3 || value == 4;
	}

	private static boolean isKnownHotspotValue(int value)
	{
		return value >= 0 && value <= 8;
	}

	private synchronized void fail(String message)
	{
		if (error != null) return;
		log.debug("Stopped in {}: {}", getStateName(), message);
		error = message == null || message.isBlank() ? "The plugin stopped without a detailed reason. Report the debug log." : message;
		String notice = "Error: Mahogany Homes: " + net.runelite.client.util.Text.removeTags(error);
		Microbot.status = notice;
		Microbot.getClientThread().invoke(() -> {
			Microbot.getClient().addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
				net.runelite.client.util.ColorUtil.wrapWithColorTag(notice, java.awt.Color.RED), null);
		});
		if (walker != null)
		{
			walker.cancel();
		}
	}

	String getStateName()
	{
		return getSnapshot() == null ? "STARTING" : getSnapshot().currentState().name();
	}

	String getError()
	{
		return error;
	}

	int getCompletedEffects()
	{
		return completedEffects;
	}

	int[] getHotspotValues()
	{
		return hotspotValues.clone();
	}

	int getCurrentRequiredPlanks()
	{
		MahoganyHomesContractData contract = MahoganyHomesContractData.forHomeowner(assignedHomeowner);
		return contract == null ? requiredPlanks(hotspotValues) : contract.requiredPlanks(hotspotValues);
	}

	int getCurrentRequiredSteelBars()
	{
		MahoganyHomesContractData contract = MahoganyHomesContractData.forHomeowner(assignedHomeowner);
		return contract == null ? requiredSteelBars(hotspotValues) : contract.requiredSteelBars(hotspotValues);
	}

	boolean isComplete()
	{
		return complete;
	}

	String getAssignedHomeowner()
	{
		return assignedHomeowner;
	}

	MahoganyHomesData getAssignedTier()
	{
		return assignedTier;
	}

	boolean isContractArrived()
	{
		return contractArrived;
	}

	boolean isContractValidated()
	{
		return contractValidated;
	}

	WorldPoint getContractDestination()
	{
		MahoganyHomesContractData contract = MahoganyHomesContractData.forHomeowner(assignedHomeowner);
		return contract == null ? null : contract.destination;
	}

	static final class Assignment
	{
		final String homeowner;
		final MahoganyHomesData tier;

		private Assignment(String homeowner, MahoganyHomesData tier)
		{
			this.homeowner = homeowner;
			this.tier = tier;
		}
	}

	static final class Work
	{
		final int hotspot;
		final int objectId;
		final WorldPoint location;
		final WorldPoint access;

		Work(int hotspot, int objectId, WorldPoint location)
		{
			this(hotspot, objectId, location, null);
		}

		Work(int hotspot, int objectId, WorldPoint location, WorldPoint access)
		{
			this.hotspot = hotspot;
			this.objectId = objectId;
			this.location = location;
			this.access = access;
		}
	}

	static final class Setup
	{
		final MahoganyHomesData tier;
		final int planks;
		final int steelBars;
		final boolean currentContract;

		private Setup(MahoganyHomesData tier, int planks, int steelBars, boolean currentContract)
		{
			this.tier = tier;
			this.planks = planks;
			this.steelBars = steelBars;
			this.currentContract = currentContract;
		}
	}

	private static final class WalkerBridge
	{
		private final Plugin plugin;
		private final PluginManager pluginManager;
		private final Object walker;
		private final Method walkTo;
		private final Method walkToNearestBank;
		private final Method prepareTeleportResourcesAtBank;
		private final Method cancel;
		private final Method getStatus;
		private final Method getPlanningFailure;

		private WalkerBridge(Plugin plugin, PluginManager pluginManager, Object walker, Class<?> type)
			throws NoSuchMethodException
		{
			this.plugin = plugin;
			this.pluginManager = pluginManager;
			this.walker = walker;
			walkTo = type.getMethod("walkTo", WorldPoint.class);
			walkToNearestBank = type.getMethod("walkToNearestBank");
			prepareTeleportResourcesAtBank = type.getMethod("prepareTeleportResourcesAtBank");
			cancel = type.getMethod("cancel");
			getStatus = type.getMethod("getStatus");
			getPlanningFailure = type.getMethod("getPlanningFailure");
		}

		static WalkerBridge bind(PluginManager pluginManager) throws ReflectiveOperationException
		{
			List<Plugin> matches = pluginManager.getPlugins().stream()
				.filter(plugin -> WALKER_CLASS.equals(plugin.getClass().getName()))
				.collect(java.util.stream.Collectors.toList());
			if (matches.size() != 1 || !pluginManager.isActive(matches.get(0)))
			{
				throw new IllegalStateException("Enable exactly one compatible Efficient Walker instance, then restart Mahogany Homes.");
			}
			Plugin plugin = matches.get(0);
			Object walker = plugin.getClass().getMethod("getWalker").invoke(plugin);
			return new WalkerBridge(plugin, pluginManager, walker, walker.getClass());
		}

		boolean isAvailable()
		{
			long matches = pluginManager.getPlugins().stream()
				.filter(candidate -> WALKER_CLASS.equals(candidate.getClass().getName()))
				.count();
			if (matches != 1 || !pluginManager.getPlugins().contains(plugin) || !pluginManager.isActive(plugin))
			{
				return false;
			}
			try
			{
				return plugin.getClass().getMethod("getWalker").invoke(plugin) == walker;
			}
			catch (ReflectiveOperationException | RuntimeException exception)
			{
				log.warn("Efficient Walker runtime check failed", exception);
				return false;
			}
		}

		boolean walkTo(WorldPoint point)
		{
			return invokeBoolean(walkTo, point);
		}

		boolean walkToNearestBank()
		{
			return invokeBoolean(walkToNearestBank);
		}

		boolean prepareTeleportResourcesAtBank()
		{
			return invokeBoolean(prepareTeleportResourcesAtBank);
		}

		private boolean invokeBoolean(Method method, Object... arguments)
		{
			try
			{
				return (boolean) method.invoke(walker, arguments);
			}
			catch (ReflectiveOperationException exception)
			{
				log.warn("Efficient Walker request failed", exception);
				return false;
			}
		}

		void cancel()
		{
			try
			{
				cancel.invoke(walker);
			}
			catch (ReflectiveOperationException exception)
			{
				log.warn("Could not cancel Efficient Walker during cleanup", exception);
			}
		}

		String status()
		{
			return invoke(getStatus);
		}

		String planningFailure()
		{
			return invoke(getPlanningFailure);
		}

		private String invoke(Method method)
		{
			try
			{
				return method.invoke(walker).toString();
			}
			catch (ReflectiveOperationException exception)
			{
				log.warn("Efficient Walker {} query failed", method.getName(), exception);
				return "UNKNOWN";
			}
		}
	}
}
