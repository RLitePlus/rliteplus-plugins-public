package net.runelite.client.plugins.microbot.efficientwalker;

import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.MenuAction;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.InventoryID;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.WorldType;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.efficientwalker.LocalTeleportCatalog.Teleport;
import net.runelite.client.plugins.microbot.efficientwalker.LocalTransportCatalog.Transport;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.death.Rs2Death;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.walker.Rs2MiniMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EfficientWalker
{
	private static final Logger log = LoggerFactory.getLogger(EfficientWalker.class);
	private static final int INTERACTION_QUEUE_STEPS = 4;
	private static final int MAX_ROOM_APPROACH_CANDIDATES = 64;
	private static final int WALK_QUEUE_STEPS = 4;
	private static final int TRANSITION_RETRY_TICKS = 3;
	private static final int MAX_TRANSITION_ATTEMPTS = 3;
	private static final int RUN_PREFLIGHT_THRESHOLD = 2_000;
	private static final int MAX_PREFLIGHT_ATTEMPTS = 3;
	private static final int TELEPORT_MINIMUM_SAVINGS = 10;
	private static final int TELEPORT_LANDING_TOLERANCE = 6;
	private static final int TELEPORT_BANK_RETRY_TICKS = 6;
	private static final int[] TELEPORT_BANK_RESOURCES =
	{
		ItemID.POH_TABLET_VARROCKTELEPORT,
		ItemID.POH_TABLET_ARDOUGNETELEPORT,
		ItemID.POH_TABLET_FALADORTELEPORT,
		ItemID.POH_TABLET_KOURENDTELEPORT,
		ItemID.LAWRUNE,
		ItemID.AIRRUNE,
		ItemID.WATERRUNE,
		ItemID.FIRERUNE,
		ItemID.EARTHRUNE
	};
	private static final int[] TELEPORT_BANK_AMOUNTS = {10, 10, 10, 10, 100, 250, 250, 100, 250};
	private static final int TAB_SWITCH_SCRIPT = 915;
	private static final int COMBAT_TAB_INDEX = 0;
	private static final int EQUIPMENT_TAB_INDEX = 4;
	private static final Request CANCEL_REQUEST = new Request(null, false, false, null);
	private static final MenuAction[] OBJECT_ACTIONS = {
		MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION,
		MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION
	};
	private static final MenuAction[] NPC_ACTIONS = {
		MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
		MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION,
		MenuAction.NPC_FIFTH_OPTION
	};

	public enum Status
	{
		IDLE,
		PLANNING,
		PREVIEW,
		WALKING,
		ARRIVED,
		BLOCKED
	}

	public enum PlanningFailure
	{
		NONE,
		TARGET_NOT_STANDABLE,
		ROUTE_UNSUPPORTED,
		SEARCH_LIMIT
	}

	private final Client client;
	private final EfficientWalkerConfig config;
	private final List<Transport> transports;
	private final FairyRings fairyRings = LocalTransportCatalog.loadFairyRings();
	private final Quetzals quetzals = LocalTransportCatalog.loadQuetzals();
	private final CharterShips charterShips = LocalTransportCatalog.loadCharterShips();
	private final SpiritTrees spiritTrees = LocalTransportCatalog.loadSpiritTrees();
	private CharterShips.Transit startingCharter;
	private final List<Teleport> teleports;
	private final Set<Integer> gatedBoundaryObjectIds;
	private final WorldPathfinder worldPathfinder;
	private volatile Request request;
	private volatile Object questRequest = new Object();
	private volatile Object controlToken = new Object();
	private volatile String controlFailure;
	private volatile Route route;
	private volatile List<List<WorldPoint>> pathSegments = Collections.emptyList();
	private volatile ObstacleMarker nextObstacle;
	private volatile Status status = Status.IDLE;
	private volatile PlanningFailure planningFailure = PlanningFailure.NONE;
	private String actionFailure;
	private BankTrip bankTrip;
	private List<WorldPoint> globalPath = Collections.emptyList();
	private List<Transport> globalSelectedTransitions;
	private int globalProgress;
	private Request activeRequest;
	private List<Route> plannedRoutes = Collections.emptyList();
	private List<Transport> plannedTransitions = Collections.emptyList();
	private Transport transition;
	private boolean transitionRoomRoute;
	private PendingTransition pendingTransition;
	private Teleport plannedTeleport;
	private WorldPoint plannedTeleportLanding;
	private PendingTeleport pendingTeleport;
	private final Set<Integer> rejectedWhistleSlots = new HashSet<>();
	private final Set<Integer> emptyWhistleSlots = new HashSet<>();
	private int whistleInventoryVersion;
	private int[] whistleInventory;
	private int teleportPreparationAt = -1;
	private int transitionLandedAt = -1;
	private int inputReadyTick = -1;
	private int routeSegment;
	private int transitionAttempts;
	private PendingDoor pendingDoor;
	private int walkClickedAt = -1;
	private boolean walkTargetMinimap;
	private int playerXAtWalkClick;
	private int playerYAtWalkClick;
	private WorldPoint walkTarget;
	private boolean walkHandoffPending;
	private boolean obstacleHandoffPending;
	private boolean preflightComplete;
	private int preflightTab = -1;
	private int runIntentAt = -1;
	private boolean preflightRetaliateSent;
	private QuestItemUse preflightEquipment;
	private String handoffDecision = "idle";
	private final Set<Transport> rejectedTransitions = new HashSet<>();
	private final Set<Teleport> rejectedTeleports = new HashSet<>();
	private final Set<Integer> rejectedTeleportBankResources = new HashSet<>();
	private int pendingTeleportBankResource = -1;
	private int pendingTeleportBankQuantity;
	private int pendingTeleportBankAt = -1;
	private final Executor suppliedPlanner;
	private final Executor completedPlanDispatcher;
	private ExecutorService ownedPlanner;
	private FutureTask<PlanningResult> pendingPlan;
	private PlanningSnapshot pendingPlanSnapshot;
	private PlanningSnapshot bankPreviewSnapshot;

	@Inject
	EfficientWalker(Client client, EfficientWalkerConfig config)
	{
		this(client, config, LocalTransportCatalog.load(), null, null,
			runnable -> Microbot.getClientThread().invokeLater(runnable));
	}

	private boolean bankAvailable(BankLocation bank)
	{
		return bank == BankLocation.ZANARIS
			? Rs2Player.getQuestState(Quest.LOST_CITY) == QuestState.FINISHED : bank.hasRequirements();
	}

	private boolean isTransportAvailable(Transport candidate)
	{
		if (!isTransportAllowed(candidate))
		{
			return false;
		}
		final boolean available = (candidate.isAlKharidGate() ? canUseAlKharidGate() : candidate.isAvailable())
			&& (!CharterShips.isCharter(candidate) || charterShips.available(client, candidate))
			&& (!candidate.isPrimio() || client.getWorldType().contains(WorldType.MEMBERS))
			&& (!FairyRings.isRing(candidate) || fairyRings.available(client, candidate))
			&& (!SpiritTrees.isTree(candidate) || spiritTrees.available(client, config, candidate))
			&& (!Quetzals.isQuetzal(candidate) || quetzals.available(client, candidate));
		if (!available || !StrongholdOfSecurity.isPortal(candidate.objectId))
		{
			return available;
		}
		final Player player = client.getLocalPlayer();
		return player != null && StrongholdOfSecurity.isPortalAvailable(
			candidate.objectId, player.getCombatLevel(), client::getVarbitValue);
	}

	boolean isTransportAllowed(Transport candidate)
	{
		return isTransportAllowed(config, candidate);
	}

	static boolean isTransportAllowed(EfficientWalkerConfig config, Transport candidate)
	{
		if (CharterShips.isCharter(candidate))
		{
			return config.useCharterShips();
		}
		if (SpiritTrees.isTree(candidate))
		{
			return config.useSpiritTrees();
		}
		if (FairyRings.isRing(candidate))
		{
			return config.useFairyRings();
		}
		return !candidate.isAgilityShortcut()
			|| config.allowAgilityShortcuts()
				&& (!candidate.isGrappleShortcut() || config.allowGrappleShortcuts());
	}

	EfficientWalker(Client client, EfficientWalkerConfig config, List<Transport> transports,
		WorldPathfinder worldPathfinder)
	{
		this(client, config, transports, worldPathfinder, Runnable::run);
	}

	EfficientWalker(Client client, EfficientWalkerConfig config, List<Transport> transports,
		WorldPathfinder worldPathfinder, Executor planner)
	{
		this(client, config, transports, worldPathfinder, planner, null);
	}

	EfficientWalker(Client client, EfficientWalkerConfig config, List<Transport> transports,
		WorldPathfinder worldPathfinder, Executor planner, Executor completedPlanDispatcher)
	{
		this.client = client;
		this.config = config;
		this.transports = transports;
		this.teleports = worldPathfinder == null ? new ArrayList<>(LocalTeleportCatalog.load()) : Collections.emptyList();
		if (worldPathfinder == null) { this.teleports.addAll(quetzals.whistleTeleports(client, rejectedWhistleSlots)); }
		this.gatedBoundaryObjectIds = gatedBoundaryObjectIds(transports);
		this.worldPathfinder = worldPathfinder == null
			? WorldPathfinder.load(transports, this::isTransportAvailable) : worldPathfinder;
		this.suppliedPlanner = planner;
		this.completedPlanDispatcher = completedPlanDispatcher;
	}

	private static Set<Integer> gatedBoundaryObjectIds(List<Transport> transports)
	{
		final Set<Integer> ids = transports.stream().filter(Transport::isGatedBoundary)
			.map(candidate -> candidate.objectId).collect(Collectors.toSet());
		if (transports.stream().anyMatch(Transport::isAlKharidGate))
		{
			ids.addAll(LocalTransportCatalog.AL_KHARID_GATE_IDS);
		}
		return ids;
	}

	public synchronized boolean walkTo(WorldPoint target)
	{
		if (target == null) { return false; }
		beginControl();
		return testWalk(target);
	}

	/** Route with carried supplies only, for activities which forbid additional equipment. */
	public synchronized boolean walkToWithInventory(WorldPoint target)
	{
		if (!walkTo(target)) { return false; }
		request.bankConsidered = true;
		return true;
	}

	public synchronized boolean walkToNearestBank()
	{
		beginControl();
		return requestNearestBank();
	}

	public synchronized boolean walkToGravestone()
	{
		if (!Rs2Death.hasGrave() || Rs2Death.getDeathWildernessLevel() > 0) { return false; }
		WorldPoint death = Rs2Death.getLastDeathLocation();
		if (death == null) { return false; }
		var grave = Rs2Death.getGrave();
		WorldPoint target = grave == null ? death : grave.getWorldLocation();
		List<WorldPoint> approaches = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (dx != 0 || dy != 0)
				{
					approaches.add(new WorldPoint(target.getX() + dx, target.getY() + dy, target.getPlane()));
				}
			}
		}
		beginControl();
		return walkToAny(approaches);
	}

	boolean requestNearestBank()
	{
		questRequest = new Object();
		request = new Request(null, true, true, null);
		status = Status.WALKING;
		planningFailure = PlanningFailure.NONE;
		return true;
	}

	boolean walkToAny(List<WorldPoint> destinations)
	{
		return walkToAny(destinations, false);
	}

	boolean walkToAny(List<WorldPoint> destinations, boolean escort)
	{
		if (destinations.isEmpty())
		{
			return false;
		}
		questRequest = new Object();
		request = new Request(destinations.get(0), true, false, null,
			Collections.unmodifiableList(new ArrayList<>(destinations)));
		request.escort = escort;
		status = Status.WALKING;
		planningFailure = PlanningFailure.NONE;
		return true;
	}

	/**
	 * Withdraws the highest-priority available teleport resources while a bank is open.
	 * Returns true once every free inventory slot has been considered.
	 */
	public boolean prepareTeleportResourcesAtBank()
	{
		if (!Rs2Bank.isOpen())
		{
			resetTeleportBankPreparation();
			return false;
		}

		int tick = client.getTickCount();
		if (pendingTeleportBankResource >= 0)
		{
			if (Rs2Inventory.itemQuantity(pendingTeleportBankResource) != pendingTeleportBankQuantity)
			{
				pendingTeleportBankResource = -1;
			}
			else if (tick - pendingTeleportBankAt < TELEPORT_BANK_RETRY_TICKS)
			{
				return false;
			}
			else
			{
				rejectedTeleportBankResources.add(pendingTeleportBankResource);
				pendingTeleportBankResource = -1;
			}
		}

		Map<Integer, Integer> bankResources = Rs2Bank.bankItems().stream()
			.collect(Collectors.toMap(item -> item.getId(), item -> item.getQuantity(), Integer::sum));
		int staffId = Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			bankResources.keySet().removeIf(id -> !LocalTeleportCatalog.itemAllowed(client, id));
			return FairyRings.nextBankStaff(Rs2Inventory.emptySlotCount(),
				client.getWorldType().contains(WorldType.MEMBERS), client.getVarbitValue(VarbitID.FAIRYRING_PERMISSION),
				client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE), FairyRings.staffEquipped(),
				Rs2Inventory::itemQuantity, id -> bankResources.getOrDefault(id, 0), rejectedTeleportBankResources);
		}).orElseThrow(() -> new IllegalStateException("Teleport bank prerequisites are unavailable"));
		int itemId = staffId >= 0 ? staffId : nextTeleportBankResource(Rs2Inventory.emptySlotCount(),
			Rs2Inventory::itemQuantity, id -> bankResources.getOrDefault(id, 0), rejectedTeleportBankResources);
		if (itemId < 0)
		{
			return true;
		}

		int amount = 1;
		if (staffId < 0 && Arrays.stream(Quetzals.WHISTLES).noneMatch(id -> id == itemId))
		{
			int index = 0;
			while (TELEPORT_BANK_RESOURCES[index] != itemId) { index++; }
			amount = Math.min(TELEPORT_BANK_AMOUNTS[index]
				- Rs2Inventory.itemQuantity(itemId), bankResources.get(itemId));
		}
		pendingTeleportBankResource = itemId;
		pendingTeleportBankQuantity = Rs2Inventory.itemQuantity(itemId);
		pendingTeleportBankAt = tick;
		if (!Rs2Bank.withdrawX(itemId, amount))
		{
			rejectedTeleportBankResources.add(itemId);
			pendingTeleportBankResource = -1;
		}
		return false;
	}

	static int nextTeleportBankResource(int emptySlots, IntUnaryOperator inventoryQuantity,
		IntUnaryOperator bankQuantity, Set<Integer> rejected)
	{
		if (emptySlots > 0 && Arrays.stream(Quetzals.WHISTLES).noneMatch(id -> inventoryQuantity.applyAsInt(id) > 0))
		{
			for (int id : Quetzals.WHISTLES)
			{
				if (bankQuantity.applyAsInt(id) > 0 && !rejected.contains(id)) { return id; }
			}
		}
		for (int index = 0; index < TELEPORT_BANK_RESOURCES.length; index++)
		{
			int itemId = TELEPORT_BANK_RESOURCES[index];
			int carried = inventoryQuantity.applyAsInt(itemId);
			if (carried < TELEPORT_BANK_AMOUNTS[index]
				&& (carried > 0 || emptySlots > 0)
				&& bankQuantity.applyAsInt(itemId) > 0
				&& !rejected.contains(itemId))
			{
				return itemId;
			}
		}
		return -1;
	}

	private void resetTeleportBankPreparation()
	{
		pendingTeleportBankResource = -1;
		pendingTeleportBankAt = -1;
		rejectedTeleportBankResources.clear();
	}

	boolean preview(WorldPoint target)
	{
		return request(target, false);
	}

	boolean testWalk(WorldPoint target)
	{
		return request(target, true);
	}

	boolean testPreviewedWalk(WorldPoint target)
	{
		if (!routeContainersReady()) { return request(target, true); }
		if (bankTrip != null && status == Status.PREVIEW && reusableBankPreview(target))
		{
			questRequest = new Object();
			request = new Request(target, true, false, currentLocation());
			request.bankConsidered = true;
			activeRequest = request;
			status = Status.WALKING;
			walkHandoffPending = true;
			obstacleHandoffPending = true;
			resetPreflight();
			bankPreviewSnapshot = null;
			Player player = client.getLocalPlayer();
			if (beginPlanning(request, player, true)) { return true; }
			bankTrip = null;
			return request(target, true);
		}
		if (bankTrip != null || status != Status.PREVIEW || !target.equals(getDestination())
			|| route == null && plannedTeleport == null)
		{
			return testWalk(target);
		}

		questRequest = new Object();
		request = new Request(target, true, false, currentLocation());
		activeRequest = request;
		status = Status.WALKING;
		walkHandoffPending = true;
		obstacleHandoffPending = true;
		resetPreflight();
		return true;
	}

	private boolean request(WorldPoint target, boolean walk)
	{
		if (target == null)
		{
			return false;
		}

		debug("request mode={} target={}", walk ? "walk" : "preview", target);
		bankPreviewSnapshot = null;
		questRequest = new Object();
		request = new Request(target, walk, false, null);
		status = walk ? Status.WALKING : Status.PREVIEW;
		planningFailure = PlanningFailure.NONE;
		return true;
	}

	/** Identifies one external intent across its internal route and quest steps. */
	public Object getControlToken() { return controlToken; }

	String controlFailure() { return controlFailure; }

	synchronized Object beginControl()
	{
		controlFailure = null;
		controlToken = new Object();
		return controlToken;
	}

	void failControl(String reason) { controlFailure = reason; }

	public synchronized void cancel()
	{
		beginControl();
		cancelCurrent();
	}

	void cancelCurrent()
	{
		questRequest = new Object();
		if (client.isClientThread())
		{
			clear();
			return;
		}
		request = CANCEL_REQUEST;
		status = Status.IDLE;
		planningFailure = PlanningFailure.NONE;
	}

	private void clear()
	{
		if (pendingTeleport != null && pendingTeleport.spellEntry != null
			&& Microbot.targetMenu == pendingTeleport.spellEntry) { Microbot.targetMenu = null; }

		debug("cancel destination={} status={}", getDestination(), status);
		startingCharter = null;
		request = null;
		activeRequest = null;
		bankTrip = null;
		bankPreviewSnapshot = null;
		route = null;
		clearGlobalRoute();
		globalProgress = 0;
		pathSegments = Collections.emptyList();
		nextObstacle = null;
		plannedRoutes = Collections.emptyList();
		plannedTransitions = Collections.emptyList();
		transition = null;
		transitionRoomRoute = false;
		pendingTransition = null;
		plannedTeleport = null;
		plannedTeleportLanding = null;
		pendingTeleport = null;
		teleportPreparationAt = -1;
		resetTeleportBankPreparation();
		resetPreflight();
		fairyRings.reset();
		routeSegment = 0;
		transitionAttempts = 0;
		pendingDoor = null;
		walkHandoffPending = false;
		obstacleHandoffPending = false;
		walkClickedAt = -1;
		walkTarget = null;
		rejectedTransitions.clear();
		rejectedTeleports.clear();
		rejectedWhistleSlots.retainAll(emptyWhistleSlots);
		cancelPendingPlan();
		status = Status.IDLE;
		planningFailure = PlanningFailure.NONE;
	}

	void close()
	{
		invalidateWhistleCharges();
		cancelPendingPlan();
		if (ownedPlanner != null)
		{
			ownedPlanner.shutdownNow();
			ownedPlanner = null;
		}
	}

	WorldPoint currentLocation()
	{
		return client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation();
	}

	List<WorldPoint> questDestinations(QuestStepTarget.Target target)
	{
		return QuestDestination.resolve(client, target, worldPathfinder);
	}

	/** Live target approaches retain the same footprint, occupancy and room checks as quest walking. */
	public synchronized boolean walkToQuestArea(WorldPoint point)
	{
		if (point == null || client.getTopLevelWorldView() == null) { return false; }
		List<WorldPoint> goals = QuestDestination.resolve(client,
			new QuestStepTarget.Target(null, point, false, false, null, null, null), worldPathfinder);
		if (goals.isEmpty() && point.distanceTo(currentLocation()) > 12 && !client.getTopLevelWorldView().isInstance())
		{
			goals = QuestNpcApproach.waypoints(point, 8);
			goals.removeIf(candidate -> !worldPathfinder.isStandable(candidate) || worldPathfinder.isWilderness(candidate));
		}
		if (goals.isEmpty()) { return false; }
		beginControl();
		return walkToAny(goals);
	}

	public synchronized boolean walkToNpc(NPC npc)
	{
		if (npc == null || npc.getWorldView() != client.getTopLevelWorldView()) { return false; }
		List<WorldPoint> goals = questDestinations(new QuestStepTarget.Target(null, npc.getWorldLocation(),
			true, false, npc.getWorldLocation(), npc, null));
		if (goals.isEmpty()) { return false; }
		beginControl();
		return walkToAny(goals);
	}

	public synchronized boolean walkToObject(TileObject object)
	{
		if (object == null || object.getWorldView() != client.getTopLevelWorldView()) { return false; }
		List<WorldPoint> goals = questDestinations(new QuestStepTarget.Target(null, object.getWorldLocation(),
			false, true, null, null, object));
		if (goals.isEmpty()) { return false; }
		beginControl();
		return walkToAny(goals);
	}

	public boolean atNpcApproach(NPC npc)
	{
		return npc != null && npc.getWorldView() == client.getTopLevelWorldView()
			&& questDestinations(new QuestStepTarget.Target(null, npc.getWorldLocation(),
				true, false, npc.getWorldLocation(), npc, null)).contains(currentLocation());
	}

	public boolean atObjectApproach(TileObject object)
	{
		return object != null && object.getWorldView() == client.getTopLevelWorldView()
			&& questDestinations(new QuestStepTarget.Target(null, object.getWorldLocation(),
				false, true, null, null, object)).contains(currentLocation());
	}

	boolean isStandable(WorldPoint point)
	{
		return worldPathfinder.isStandable(point);
	}

	WalkerError errorNotice(String reason)
	{
		Request requested = request;
		return new WalkerError(reason, requested == null ? null : requested.start, currentLocation(),
			requested == null ? null : requested.destination == null ? requested.target : requested.destination);
	}

	public WorldPoint getDestination()
	{
		final Request requested = request;
		return requested == null ? null : requested.destination;
	}

	List<WorldPoint> getPath()
	{
		if (!globalPath.isEmpty())
		{
			return globalPath;
		}
		final Route currentRoute = route;
		return currentRoute == null ? Collections.emptyList() : currentRoute.worldPath;
	}

	List<List<WorldPoint>> getPathSegments()
	{
		return pathSegments;
	}

	List<WorldPoint> getRemainingPath()
	{
		if (pathSegments.isEmpty())
		{
			return Collections.emptyList();
		}
		if (!globalPath.isEmpty())
		{
			return globalPath.subList(globalProgress, globalPath.size());
		}
		final List<WorldPoint> remaining = new ArrayList<>();
		for (int index = routeSegment; index < pathSegments.size(); index++)
		{
			final List<WorldPoint> segment = pathSegments.get(index);
			final int progress = index == routeSegment && route != null ? route.progress : 0;
			remaining.addAll(segment.subList(progress, segment.size()));
		}
		return remaining;
	}

	Set<WorldPoint> getActionTiles()
	{
		final Set<WorldPoint> tiles = new HashSet<>();
		if (nextObstacle != null)
		{
			tiles.add(nextObstacle.approach);
		}
		if (transition != null)
		{
			tiles.add(transition.approach);
			tiles.add(transition.landing);
		}
		for (Transport step : plannedTransitions)
		{
			tiles.add(step.approach);
			tiles.add(step.landing);
		}
		for (int index = Math.max(1, globalProgress); index < globalPath.size(); index++)
		{
			final WorldPoint from = globalPath.get(index - 1);
			final WorldPoint to = globalPath.get(index);
			if (from.distanceTo(to) > 1)
			{
				tiles.add(from);
				tiles.add(to);
			}
		}
		return tiles;
	}

	ObstacleMarker getNextObstacle()
	{
		return nextObstacle;
	}

	public Status getStatus()
	{
		return status;
	}

	public PlanningFailure getPlanningFailure()
	{
		return planningFailure;
	}

	WorldPoint questTravelDestination(Object step)
	{
		return quetzals.questDestination(client, QuestItemUse.instruction(step));
	}

	boolean questTravelPending()
	{
		return pendingTransition != null || pendingTeleport != null;
	}

	boolean questTargetReady(QuestStepTarget.Target target)
	{
		if (client.getLocalPlayer() == null) { return false; }
		Rectangle bounds;
		if (target.npc)
		{
			NPC npc = target.liveNpc;
			if (npc == null || npc.getWorldView() != client.getTopLevelWorldView()) { return false; }
			bounds = npc.getConvexHull() == null ? null : npc.getConvexHull().getBounds();
		}
		else if (target.object)
		{
			TileObject object = target.liveObject;
			if (object == null || object.getWorldView() != client.getTopLevelWorldView()) { return false; }
			bounds = Rs2UiHelper.getObjectClickbox(object);
		}
		else { return true; }
		return bounds != null && !bounds.isEmpty() && Rs2UiHelper.isRectangleWithinCanvas(bounds);
	}

	boolean isWalkInProgress()
	{
		final Request current = request;
		return current != null && current != CANCEL_REQUEST && current.walk
			&& status != Status.IDLE && status != Status.ARRIVED && status != Status.BLOCKED;
	}

	void onGameTick()
	{
		final Request requested = request;
		if (requested == CANCEL_REQUEST)
		{
			clear();
			return;
		}
		if (requested == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (!sceneReadyForInput()) { return; }
		if (!routeContainersReady())
		{
			invalidateLandingStability();
			return;
		}

		if (requested != activeRequest)
		{
			final Player requestPlayer = client.getLocalPlayer();
			// External callers submit intent; live state and preparation belong to this tick.
			requested.start = requestPlayer == null ? null : requestPlayer.getWorldLocation();
			resetTeleportBankPreparation();
			cancelPendingPlan();
			activeRequest = requested;
			bankTrip = null;
			bankPreviewSnapshot = null;
			actionFailure = null;
			rejectedTransitions.clear();
			rejectedTeleports.clear();
		rejectedWhistleSlots.retainAll(emptyWhistleSlots);
			route = null;
			pathSegments = Collections.emptyList();
			nextObstacle = null;
			plannedRoutes = Collections.emptyList();
			plannedTransitions = Collections.emptyList();
			transition = null;
			transitionRoomRoute = false;
			pendingTransition = null;
			plannedTeleport = null;
			plannedTeleportLanding = null;
			pendingTeleport = null;
			teleportPreparationAt = -1;
			fairyRings.reset();
			clearGlobalRoute();
			globalProgress = 0;
			routeSegment = 0;
			transitionAttempts = 0;
			pendingDoor = null;
			walkHandoffPending = requested.walk;
			obstacleHandoffPending = requested.walk;
			walkClickedAt = -1;
			resetPreflight();
			if (!beginPlanning(requested, requestPlayer, true))
			{
				pathSegments = Collections.emptyList();
				block("plan-unavailable");
				return;
			}
		}

		if (pendingPlan != null)
		{
			if (!finishPlanning(requested))
			{
				return;
			}
			if (status == Status.BLOCKED)
			{
				return;
			}
		}

		if (!requested.walk || status == Status.BLOCKED)
		{
			return;
		}

		final Player player = client.getLocalPlayer();
		if (player == null || player.getLocalLocation() == null || player.getWorldView() == null)
		{
			block("player-state-unavailable");
			return;
		}
		if (pendingTransition == null && pendingTeleport == null)
		{
			String reason = remainingRouteCoinError();
			if (reason != null) { failAction(reason); return; }
		}
		if (!preflightComplete && !runPreflight() || !maintainRun())
		{
			return;
		}
		if (bankTrip != null && (!config.useItemsFromBank() || bankTrip.option.teleport != null && !config.useTeleportations()))
		{
			failAction("Bank route supplies option was disabled. Retry the walk with your current settings.");
			return;
		}
		if (bankTrip != null && bankTrip.arrived)
		{
			try
			{
				if (bankTrip.tick(client, this))
				{
					bankTrip = null;
					Microbot.status = "Bank supplies ready; continuing the route.";
					resetPreflight();
					if (!beginPlanning(requested, player, true)) { block("plan-unavailable"); }
				}
			}
			catch (IllegalStateException exception) { failAction(exception.getMessage()); }
			return;
		}

		if (plannedTeleport != null)
		{
			handleTeleport(requested, player);
			return;
		}
		if (route == null)
		{
			block("route-unavailable-after-planning");
			return;
		}

		final WorldView worldView = player.getWorldView();
		final WorldPoint playerWorld = player.getWorldLocation();
		debug("tick={} status={} player={} moving={} destination={} global={}/{} route={}/{} "
			+ "transition={} pendingTransition={} pendingDoor={} decision={} walkTarget={}",
			client.getTickCount(), status, playerWorld,
			player.getPoseAnimation() != player.getIdlePoseAnimation(), requested.destination,
			globalProgress, globalPath.size(), route == null ? -1 : route.progress,
			route == null ? 0 : route.worldPath.size(), transportName(transition),
			pendingTransition != null, pendingDoor != null, handoffDecision, walkTarget);
		if (handleSwampConfirmation()) { return; }
		if (pendingTransition != null)
		{
			if (CharterShips.isCharter(transition))
			{
				try
				{
					if (pendingTransition.charter == null) { throw new IllegalStateException("Charter travel state is unavailable. Cancel and retry the walk."); }
					pendingTransition.charter.observeCoins(CharterShips.coins(client));
				}
				catch (IllegalStateException ex) { failAction(ex.getMessage()); return; }
			}
			if (SpiritTrees.isTree(transition) && !spiritTrees.available(client, config, transition))
			{
				rejectTransitionAndReplan(player);
				return;
			}
			if (transition.isPrimio() && !isTransportAvailable(transition))
			{
				failAction("Primio travel is no longer available. Use a members world, complete Children of the Sun "
					+ "and your first journey with Regulus Cento, then retry.");
				return;
			}
			final boolean transitionActive = player.getPoseAnimation() != player.getIdlePoseAnimation()
				|| player.getAnimation() != -1;
			final boolean transitionProgress = pendingTransition.observeProgress(client.getTickCount(), playerWorld,
				transitionActive);
			boolean landed = isAcceptedTransportLanding(transition, playerWorld, pendingTransition.effectObserved);
			if (CharterShips.isCharter(transition))
			{
				landed = pendingTransition.charter.landed(client.getTickCount(), landed && !transitionActive
					&& !CharterShips.menuOpen(client) && !QuestDialogue.visible(client)
					&& client.getVarbitValue(VarbitID.CUTSCENE_STATUS) == 0);
			}
			if (Quetzals.isQuetzal(transition) || transition.isPrimio())
			{
				if (transition.isPrimio()) { pendingTransition.quetzalSelectedAt = pendingTransition.clickedAt; }
				landed = pendingTransition.stableQuetzalLanding(client.getTickCount(), landed
					&& !transitionActive && !Quetzals.menuOpen(client) && !Quetzals.flightActive(client)
					&& client.getVarbitValue(VarbitID.CUTSCENE_STATUS) == 0, transitionProgress);
			}
			if (SpiritTrees.isTree(transition))
			{
				if (pendingTransition.spirit == null)
				{
					failAction("Spirit tree travel state is unavailable. Cancel and retry the walk.");
					return;
				}
				landed = pendingTransition.spirit.landed(client.getTickCount(), transition.landing.equals(playerWorld)
					&& !transitionActive && !SpiritTrees.menuOpen(client)
					&& client.getVarbitValue(VarbitID.CUTSCENE_STATUS) == 0);
			}
			if (landed)
			{
				if (CharterShips.isCharter(transition))
				{
					Microbot.status = "Charter arrived at " + charterShips.journey(transition).destination.name + ".";
				}
				debug("transition-landed expected={} actual={} distance={}", transition.landing,
					playerWorld, playerWorld.distanceTo2D(transition.landing));
				if (transitionActive)
				{
					return;
				}
				transitionLandedAt = client.getTickCount();
				markObstacleCleared();
				if (!globalPath.isEmpty())
				{
					pendingTransition = null;
					transitionAttempts = 0;
					walkClickedAt = -1;
					pendingDoor = null;
					final int landingIndex = indexOf(globalPath, playerWorld, globalProgress);
					if (landingIndex < 0)
					{
						handoffDecision = "replan-landing";
						rebuildCurrentSegment(worldView, playerWorld, route);
						return;
					}
					globalProgress = landingIndex;
					route = buildGlobalRoute(worldView, playerWorld);
					plannedRoutes = route == null
						? Collections.emptyList() : Collections.singletonList(route);
					if (route == null)
					{
						block("transition-landing-route-unavailable");
					}
					else
					{
						status = Status.WALKING;
					}
					return;
				}
				routeSegment++;
				route = plannedRoutes.get(routeSegment);
				transition = routeSegment < plannedTransitions.size()
					? plannedTransitions.get(routeSegment) : null;
				pendingTransition = null;
				transitionAttempts = 0;
				walkClickedAt = -1;
				pendingDoor = null;
				if (!playerWorld.equals(route.worldPath.get(0)))
				{
					handoffDecision = "replan-landing";
					rebuildCurrentSegment(worldView, playerWorld, route);
				}
				return;
			}
			if (Quetzals.isQuetzal(transition) && handleQuetzalTransition()) { return; }
			if (CharterShips.isCharter(transition)) { handleCharterTransition(); return; }
			if (SpiritTrees.isTree(transition)) { handleSpiritTreeTransition(); return; }
			if (transition.isPrimio())
			{
				handoffDecision = "primio-wait-landing";
				Microbot.status = "Waiting for Primio's flight to finish. Cancel walk stops the route.";
				return;
			}
			if (FairyRings.isRing(transition))
			{
				if (!isTransportAllowed(transition) || !fairyRings.available(client, transition)
					|| FairyRings.missingStaff(client) != null)
				{
					block("fairy-ring-access-lost");
					return;
				}
				Widget confirm = client.getWidget(398, 26);
				if (confirm != null && !confirm.isHidden())
				{
					int child = fairyRings.nextAction(client, transition);
					if (child < 0) { block("fairy-ring-selection-failed"); return; }
					if (child > 0)
					{
						Widget widget = client.getWidget(398, child);
						invoke(new NewMenuEntry().option(widget.getActions()[0]).target("")
							.identifier(1).type(MenuAction.CC_OP).param0(-1).param1(widget.getId()).itemId(-1),
							widget.getBounds(), true);
						pendingTransition.lastProgressAt = client.getTickCount();
					}
					handoffDecision = child == 0 ? "fairy-ring-wait-effect" : "fairy-ring-select";
					return;
				}
			}
			if (playerWorld.getPlane() != transition.approach.getPlane())
			{
				if (!unexpectedTransitionPlaneIsStable(client.getTickCount(),
					pendingTransition.lastProgressAt, transitionProgress))
				{
					return;
				}
				block("transition-unexpected-plane");
				return;
			}
			if (reprioritizeBlockingDoor(worldView, playerWorld))
			{
				return;
			}
			if (transitionProgress)
			{
				return;
			}
			final ObjectAction nextAction;
			if (transition.isNpc())
			{
				NPC npc = findNpc(transition);
				nextAction = npc == null ? null : transitionAction(npc, transition);
			}
			else
			{
				TileObject object = findTileObject(worldView, transition);
				nextAction = object == null ? null : transitionAction(object, transition);
			}
			if (nextAction == null)
			{
				if (resumeAfterPreparatoryTransition(worldView, playerWorld)) { return; }
				handoffDecision = "wait-transition-target";
				return;
			}
			if (!sameAction(pendingTransition.action, nextAction.action))
			{
				if (clickTransition(worldView, transition, true)) { transitionAttempts++; }
				handoffDecision = "transition-next-action";
				return;
			}
			handoffDecision = "wait-transition-effect";
			return;
		}
		if (!transitionRoomRoute && !globalPath.isEmpty() && !extendGlobalRoute(worldView, playerWorld))
		{
			block("global-route-extension-failed");
			return;
		}

		final Route currentRoute = route;
		if (currentRoute == null || worldView.getId() != currentRoute.worldView.getId()
			|| worldView.getPlane() != currentRoute.plane)
		{
			block("route-world-view-mismatch");
			return;
		}

		final LocalPoint playerPoint = player.getLocalLocation();
		final int startX = playerPoint.getSceneX();
		final int startY = playerPoint.getSceneY();
		final int[][] flags = collisionFlags(worldView, currentRoute.plane);
		if (flags == null || flags.length != currentRoute.width)
		{
			block("collision-unavailable");
			return;
		}

		if (currentRoute.worldPath.stream().skip(currentRoute.progress + 1L)
			.anyMatch(MotherlodeRockfalls.TILES::contains) && !MotherlodeRockfalls.hasPickaxe(client))
		{
			failAction("This route crosses Motherlode Mine rockfalls. Carry or equip a pickaxe, then retry the walk.");
			return;
		}
		final DoorMap doors = scanDoors(worldView, currentRoute.plane, flags.length, flags[0].length);
		final int progress = findProgress(currentRoute, playerWorld, worldView, flags, doors.edges,
			startX, startY);
		if (progress < 0)
		{
			handoffDecision = "replan-deviation";
			rebuildCurrentSegment(worldView, playerWorld, currentRoute);
			return;
		}
		currentRoute.progress = progress;
		refreshNextObstacle(currentRoute, doors, progress);
		handoffDecision = "evaluating";
		if (transition != null && progress == currentRoute.worldPath.size() - 1
			&& playerWorld.equals(currentRoute.worldPath.get(progress)))
		{
			if (prepareTransitionRoomRoute(worldView, playerWorld)) { return; }
			if (!clickTransition(worldView, transition, takeObstacleHandoff()))
			{
				if (status == Status.BLOCKED) { return; }
				if (transitionSceneMayBeLoading(client.getTickCount(), transitionLandedAt))
				{
					handoffDecision = "wait-transition-scene";
					return;
				}
				rejectTransitionAndReplan(player);
				return;
			}
			transitionAttempts = 1;
			status = Status.WALKING;
			return;
		}
		if (transition == null && progress == currentRoute.worldPath.size() - 1
			&& playerWorld.equals(currentRoute.worldPath.get(progress)))
		{
			if (!globalPath.isEmpty() && !playerWorld.equals(globalPath.get(globalPath.size() - 1)))
			{
				route = buildGlobalRoute(worldView, playerWorld);
				if (route == null)
				{
					block("next-global-segment-unavailable");
				}
				else
				{
					status = Status.WALKING;
				}
				return;
			}
			finish();
			return;
		}

		int[] localPath = new int[currentRoute.worldPath.size() - progress + 1];
		int localPathLength = 1;
		localPath[0] = ScenePathfinder.pack(startX, startY, flags.length);
		for (int i = progress; i < currentRoute.worldPath.size(); i++)
		{
			if (currentRoute.worldPath.get(i).equals(playerWorld))
			{
				continue;
			}
			final LocalPoint point = LocalPoint.fromWorld(worldView, currentRoute.worldPath.get(i));
			if (point == null)
			{
				break;
			}
			localPath[localPathLength++] = ScenePathfinder.pack(point.getSceneX(), point.getSceneY(), flags.length);
		}
		if (localPathLength < 2)
		{
			block("local-path-exhausted-before-arrival");
			return;
		}
		localPath = Arrays.copyOf(localPath, localPathLength);

		final int nextX = ScenePathfinder.unpackX(localPath[1], flags.length);
		final int nextY = ScenePathfinder.unpackY(localPath[1], flags.length);
		if (!ScenePathfinder.canStep(flags, doors.edges, startX, startY, nextX, nextY)
			&& player.getPoseAnimation() == player.getIdlePoseAnimation())
		{
			if (transitionLandedAt >= 0
				&& client.getTickCount() - transitionLandedAt <= TRANSITION_RETRY_TICKS)
			{
				handoffDecision = "wait-transition-settle";
				return;
			}
			handoffDecision = "replan-blocked-step";
			rebuildCurrentSegment(worldView, playerWorld, currentRoute);
			return;
		}

		if (pendingDoor != null)
		{
			if (handleStrongholdDialogue())
			{
				return;
			}
			if (!doors.edges.get(pendingDoor.edgeKey)
				|| ScenePathfinder.canStep(flags, new BitSet(), pendingDoor.fromX, pendingDoor.fromY,
					pendingDoor.toX, pendingDoor.toY))
			{
				pendingDoor = null;
				markObstacleCleared();
			}
			else if (pendingDoor.rockfall)
			{
				boolean active = player.getAnimation() != -1
					|| player.getPoseAnimation() != player.getIdlePoseAnimation();
				if (active || !pendingDoor.rockfallActive)
				{
					pendingDoor.rockfallActive |= player.getAnimation() != -1;
					handoffDecision = active ? "mining-rockfall" : "waiting-for-rockfall-interaction";
					return;
				}
				// An observed interaction ended while the current obstruction remains; reacquire it.
				pendingDoor = null;
			}
			else if (player.getPoseAnimation() != player.getIdlePoseAnimation())
			{
				handoffDecision = "pending-door-moving";
				return;
			}
			else
			{
				handoffDecision = "wait-door-effect";
				return;
			}
		}

		final DoorStep doorStep = doors.firstDoorOn(localPath, flags.length, 0);
		if (doorStep != null && doorStep.pathIndex <= INTERACTION_QUEUE_STEPS)
		{
			handoffDecision = "click-door";
			queueDoor(doorStep, localPath, flags.length, takeObstacleHandoff());
			return;
		}
		if (!transitionRoomRoute && shouldQueueTransition(transition,
			currentRoute.worldPath.size() - 1 - progress, playerWorld))
		{
			if (prepareTransitionRoomRoute(worldView, playerWorld)) { return; }
			if (!clickTransition(worldView, transition, takeObstacleHandoff()))
			{
				if (status == Status.BLOCKED) { return; }
				if (transitionSceneMayBeLoading(client.getTickCount(), transitionLandedAt))
				{
					handoffDecision = "wait-transition-scene";
					return;
				}
				rejectTransitionAndReplan(player);
				return;
			}
			transitionAttempts = 1;
			status = Status.WALKING;
			return;
		}
		final boolean moving = player.getPoseAnimation() != player.getIdlePoseAnimation();
		if (walkClickedAt >= 0)
		{
			final int targetProgress = walkTarget == null
				? -1 : currentRoute.worldPath.indexOf(walkTarget);
			if (moving && targetProgress > progress + WALK_QUEUE_STEPS)
			{
				handoffDecision = "wait-distance";
				return;
			}
			if (!moving && startX == playerXAtWalkClick && startY == playerYAtWalkClick)
			{
				handoffDecision = "wait-acceptance";
				return;
			}
		}

		final int lastWalkableIndex = doorStep == null ? localPath.length - 1 : doorStep.pathIndex;
		for (int i = lastWalkableIndex; i > 0; i--)
		{
			final int stepX = ScenePathfinder.unpackX(localPath[i], flags.length);
			final int stepY = ScenePathfinder.unpackY(localPath[i], flags.length);
			if (!isRouteClickCandidate(i,
				Math.max(Math.abs(stepX - startX), Math.abs(stepY - startY)))) { continue; }
			if (MotherlodeRockfalls.contains(worldView.getBaseX() + stepX,
				worldView.getBaseY() + stepY, currentRoute.plane)) { continue; }
			final LocalPoint step = LocalPoint.fromScene(
				stepX, stepY,
				worldView);
			// Nearby minimap pixels can round back to the current tile; use exact WALK coordinates.
			Point clickPoint = isCanvasFallbackStep(i) ? null : minimapPoint(step);
			final boolean minimapClick = clickPoint != null;
			if (!minimapClick && isCanvasFallbackStep(i))
			{
				clickPoint = Perspective.localToCanvas(client, step, worldView.getPlane());
				if (!isInViewport(clickPoint, client.getViewportXOffset(), client.getViewportYOffset(),
					client.getViewportWidth(), client.getViewportHeight()))
				{
					clickPoint = null;
				}
			}
			if (clickPoint != null)
			{
				final WorldPoint target = WorldPoint.fromLocal(worldView,
					step.getX(), step.getY(), currentRoute.plane);
				final boolean sameWalkTarget = walkClickedAt >= 0 && target.equals(walkTarget);
				final boolean preciseHandoff = sameWalkTarget && walkTargetMinimap && !minimapClick;
				if (moving && sameWalkTarget)
				{
					handoffDecision = "same-target";
					status = Status.WALKING;
					return;
				}
				final boolean immediateHandoff = takeWalkHandoff() || preciseHandoff;
				if (minimapClick)
				{
					handoffDecision = "click-minimap";
					Microbot.targetMenu = null;
					if (immediateHandoff)
					{
						clickImmediately(clickPoint);
					}
					else
					{
						Microbot.getMouse().move(clickPoint).click(clickPoint);
					}
				}
				else
				{
					handoffDecision = "click-canvas";
					clickWalkPoint(step, clickPoint, worldView, immediateHandoff);
				}
				markWalkClick(startX, startY, target, minimapClick);
				status = Status.WALKING;
				return;
			}
		}

		if (moving && walkClickedAt >= 0 && walkTarget != null)
		{
			handoffDecision = "wait-walk-target";
			status = Status.WALKING;
			return;
		}
		block("no-clickable-walk-target");
	}

	static boolean shouldQueueTransition(Transport step, int remainingSteps, WorldPoint player)
	{
		return step != null && step.approach.distanceTo2D(step.landing) > 0
			&& (remainingSteps <= INTERACTION_QUEUE_STEPS
				|| player != null && player.getPlane() == step.approach.getPlane()
					&& player.distanceTo2D(step.approach) <= INTERACTION_QUEUE_STEPS);
	}

	private void rebuildCurrentSegment(WorldView worldView, WorldPoint playerWorld, Route currentRoute)
	{
		debug("replan reason={} player={} destination={}", handoffDecision,
			playerWorld, getDestination());
		if (!globalPath.isEmpty())
		{
			final Player player = client.getLocalPlayer();
			transitionRoomRoute = false;
			clearGlobalRoute();
			globalProgress = 0;
			pathSegments = Collections.emptyList();
			route = null;
			plannedRoutes = Collections.emptyList();
			pendingDoor = null;
			walkClickedAt = -1;
			walkTarget = null;
			if (activeRequest == null || !beginPlanning(activeRequest, player, false))
			{
				block("global-replan-unavailable");
			}
			return;
		}
		route = buildRoute(worldView, playerWorld,
			currentRoute.worldPath.get(currentRoute.worldPath.size() - 1), currentRoute.plane);
		replacePathSegment(routeSegment, route);
		pendingDoor = null;
		walkHandoffPending = false;
		obstacleHandoffPending = false;
		walkClickedAt = -1;
		walkTarget = null;
		if (route == null)
		{
			block("local-replan-unavailable");
		}
		else
		{
			status = Status.WALKING;
			debug("replan ready routeLength={}", route.worldPath.size());
		}
	}

	private void clickWalkPoint(LocalPoint point, Point clickPoint, WorldView worldView,
		boolean immediateHandoff)
	{
		final NewMenuEntry entry = new NewMenuEntry()
			.param0(point.getSceneX())
			.param1(point.getSceneY())
			.type(MenuAction.WALK)
			.identifier(0)
			.itemId(-1)
			.option("Walk here")
			.setWorldViewId(worldView.getId());
		if (immediateHandoff)
		{
			Microbot.targetMenu = entry;
			clickImmediately(clickPoint);
		}
		else
		{
			// Pre-positioning makes the menu-aware click immediate without changing its coordinates.
			Microbot.getMouse().move(clickPoint).click(clickPoint, entry);
		}
	}

	private static void clickImmediately(Point point)
	{
		WalkerInput.click(net.runelite.client.plugins.microbot.Microbot.getClient(), point);
	}

	private void resetPreflight()
	{
		preflightComplete = false;
		preflightTab = -1;
		preflightRetaliateSent = false;
		runIntentAt = -1;
		preflightEquipment = null;
	}

	private boolean maintainRun()
	{
		if (shouldToggleRun(client.getEnergy(), client.getVarpValue(VarPlayerID.OPTION_RUN), request != null && request.escort, Rs2Player.isInCombat()))
		{
			if (runIntentAt >= 0)
			{
				return false;
			}
			final Widget run = client.getWidget(InterfaceID.Orbs.RUNBUTTON);
			if (run == null || run.isHidden()) { return true; }

			invokePreflightWidget(run);
			handoffDecision = request != null && request.escort ? "preflight-disable-run-for-escort" : "preflight-enable-run";
			runIntentAt = client.getTickCount();
			return false;
		}

		runIntentAt = -1;
		return true;
	}

	private boolean runPreflight()
	{
		if (preflightEquipment != null)
		{
			ItemContainer worn = client.getItemContainer(InventoryID.EQUIPMENT);
			if (worn == null || worn.getItems() == null || Arrays.stream(worn.getItems())
				.noneMatch(item -> item != null && item.getId() == preflightEquipment.id && item.getQuantity() > 0))
			{
				handoffDecision = "preflight-wait-equipment";
				return false;
			}
			preflightEquipment = null;
		}
		if (config.disableAutoRetaliate() && shouldDisableAutoRetaliate(client.getVarpValue(VarPlayerID.OPTION_NODEF)))
		{
			if (preflightRetaliateSent) { return false; }
			final Widget retaliate = client.getWidget(InterfaceID.CombatInterface.RETALIATE);
			if (retaliate == null || retaliate.isHidden())
			{
				openPreflightTab(COMBAT_TAB_INDEX);
				handoffDecision = "preflight-open-combat";
			}
			else
			{
				invokePreflightWidget(retaliate);
				preflightRetaliateSent = true;
				handoffDecision = "preflight-disable-auto-retaliate";
			}
			return false;
		}
		preflightRetaliateSent = false;
		if (preflightTab == COMBAT_TAB_INDEX) { preflightTab = -1; }

		if (routeTransitions().stream().anyMatch(candidate -> isTransportAllowed(candidate)
			&& (FairyRings.isRing(candidate) || !candidate.equipmentRequirements().isEmpty())))
		{
			for (InventoryID id : new InventoryID[]{InventoryID.INVENTORY, InventoryID.EQUIPMENT})
			{
				ItemContainer container = client.getItemContainer(id);
				if (container == null || container.getItems() == null || Arrays.stream(container.getItems())
					.anyMatch(item -> item != null && item.getId() >= 0 && item.getQuantity() > 0
						&& (client.getItemDefinition(item.getId()) == null
							|| client.getItemDefinition(item.getId()).getName() == null)))
				{
					handoffDecision = "preflight-wait-containers";
					return false;
				}
			}
		}

		final String requiredEquipment = missingRouteEquipment();
		if (requiredEquipment != null)
		{
			QuestItemUse item = LocalTransportCatalog.equipmentItem(client, InventoryID.INVENTORY, requiredEquipment);
			if (item == null)
			{
				rejectEquipmentAndReplan(requiredEquipment);
				return false;
			}
			Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
			if (inventory == null || inventory.isHidden())
			{
				openPreflightTab(3);
				handoffDecision = "preflight-open-inventory";
				return false;
			}
			Widget slot = inventory.getChild(item.slot);
			if (slot == null || slot.getActions() == null) { return false; }
			String action = null;
			for (String option : slot.getActions())
			{
				option = option == null ? null : net.runelite.client.util.Text.removeTags(option);
				if ("Wear".equalsIgnoreCase(option) || "Wield".equalsIgnoreCase(option))
				{
					if (action != null) { return false; }
					action = option;
				}
			}
			if (action != null && interactWithQuestInventory(new QuestItemUse.InventoryAction(item, null, action)))
			{
				preflightEquipment = item;
			}
			handoffDecision = "preflight-equip-" + requiredEquipment.toLowerCase();
			return false;
		}

		preflightComplete = true;
		preflightTab = -1;
		handoffDecision = "preflight-complete";
		return true;
	}

	private void openPreflightTab(int tab)
	{
		if (preflightTab != tab)
		{
			client.runScript(TAB_SWITCH_SCRIPT, tab);
			preflightTab = tab;
		}
	}

	private String missingRouteEquipment()
	{
		for (Transport candidate : routeTransitions())
		{
			if (!isTransportAllowed(candidate)) { continue; }
			final String requirement = FairyRings.isRing(candidate)
				? FairyRings.missingStaff(client) : candidate.missingEquipment(client);
			if (requirement != null)
			{
				return requirement;
			}
		}
		return null;
	}

	private List<Transport> routeTransitions()
	{
		if (globalPath.isEmpty())
		{
			return plannedTransitions;
		}
		final List<Transport> result = new ArrayList<>();
		for (int index = 1; index < globalPath.size(); index++)
		{
			final Transport candidate = transportBetween(globalPath.get(index - 1), globalPath.get(index));
			if (candidate != null && !result.contains(candidate))
			{
				result.add(candidate);
			}
		}
		return result;
	}

	private List<Transport> remainingCharterTransitions()
	{
		if (globalPath.isEmpty())
		{
			return plannedTransitions.subList(Math.min(routeSegment, plannedTransitions.size()), plannedTransitions.size())
				.stream().filter(CharterShips::isCharter).collect(Collectors.toList());
		}
		List<Transport> remaining = new ArrayList<>();
		for (int index = globalProgress + 1; index < globalPath.size(); index++)
		{
			if (charterShips.journey(globalPath.get(index - 1), globalPath.get(index)) == null) { continue; }
			Transport candidate = transportBetween(globalPath.get(index - 1), globalPath.get(index));
			if (CharterShips.isCharter(candidate)) { remaining.add(candidate); }
		}
		return remaining;
	}

	private int transportCoinCost(Transport candidate)
	{
		return CharterShips.isCharter(candidate) ? CharterShips.fare(client, charterShips.journey(candidate))
			: candidate.coinCost(quest -> quest.getState(client) == QuestState.FINISHED);
	}

	boolean sceneReadyForInput()
	{
		Widget fade=client.getWidget(InterfaceID.FadeOverlay.UNIVERSE);
		if(client.getVarbitValue(VarbitID.CUTSCENE_STATUS)!=0 || fade!=null && !fade.isHidden())
		{
			invalidateLandingStability();
			handoffDecision="wait-travel-scene";
			Microbot.status="Waiting for travel scene to finish. Cancel walk stops the route.";
			return false;
		}
		if(inputReadyTick<0){inputReadyTick=client.getTickCount();}
		return client.getTickCount()>inputReadyTick;
	}

	private boolean routeContainersReady()
	{
		for (InventoryID id : new InventoryID[]{InventoryID.INVENTORY, InventoryID.EQUIPMENT})
		{
			ItemContainer container = client.getItemContainer(id);
			if (container == null || container.getItems() == null)
			{
				handoffDecision = "wait-route-containers";
				Microbot.status = "Waiting for current inventory and equipment. Cancel walk stops the route.";
				return false;
			}
		}
		return true;
	}

	private String remainingRouteCoinError()
	{
		String charterError = charterShips.routeUnavailableReason(client, remainingCharterTransitions());
		if (charterError != null) { return charterError; }
		List<Transport> remaining = new ArrayList<>();
		if (globalPath.isEmpty())
		{
			remaining.addAll(plannedTransitions.subList(Math.min(routeSegment, plannedTransitions.size()), plannedTransitions.size()));
		}
		else if (globalSelectedTransitions != null)
		{
			for (int index = globalProgress + 1; index < globalPath.size(); index++)
			{
				Transport candidate = transportBetween(globalPath.get(index - 1), globalPath.get(index));
				if (candidate != null) { remaining.add(candidate); }
			}
		}
		long required = 0;
		for (Transport candidate : remaining)
		{
			int fare = transportCoinCost(candidate);
			if (fare < 0) { return null; }
			required += fare;
		}
		int wallet = CharterShips.coins(client);
		if (wallet < 0) { return null; }
		long deficit = required - wallet;
		return deficit > 0 ? "You need " + deficit + " more Coins for the remaining travel. Withdraw the missing Coins and retry the walk." : null;
	}

	private void rejectEquipmentAndReplan(String requirement)
	{
		for (Transport candidate : routeTransitions())
		{
			if (candidate.requiresEquipment(requirement)
				|| FairyRings.isRing(candidate) && ("Dramen staff".equals(requirement) || "Lunar staff".equals(requirement)))
			{
				rejectedTransitions.add(candidate);
			}
		}
		final Player player = client.getLocalPlayer();
		route = null;
		clearGlobalRoute();
		pathSegments = Collections.emptyList();
		plannedRoutes = Collections.emptyList();
		plannedTransitions = Collections.emptyList();
		transition = null;
		resetPreflight();
		if (activeRequest == null || !beginPlanning(activeRequest, player, true))
		{
			block("preflight-equipment-" + requirement.toLowerCase());
			return;
		}
		handoffDecision = "preflight-replan-equipment";
	}

	private void invokePreflightWidget(Widget widget)
	{
		final NewMenuEntry entry = new NewMenuEntry()
			.option("Toggle")
			.target("")
			.identifier(1)
			.type(MenuAction.CC_OP)
			.param0(-1)
			.param1(widget.getId())
			.itemId(-1);
		invoke(entry, widget.getBounds(), false);
	}

	static boolean shouldDisableAutoRetaliate(int optionNoDef)
	{
		return optionNoDef != 1;
	}

	static boolean shouldToggleRun(int energy, int runOption, boolean escort)
	{
		return shouldToggleRun(energy, runOption, escort, false);
	}

	static boolean shouldToggleRun(int energy, int runOption, boolean escort, boolean inCombat)
	{
		return escort ? runOption == 1
			: runOption != 1 && (energy >= RUN_PREFLIGHT_THRESHOLD || inCombat && energy >= 300);
	}

	static boolean shouldEnableRun(int energy, int runOption)
	{
		return energy >= RUN_PREFLIGHT_THRESHOLD && runOption != 1;
	}

	private void invoke(NewMenuEntry entry, Rectangle bounds, boolean immediateHandoff)
	{
		if (!immediateHandoff)
		{
			Microbot.doInvoke(entry, bounds);
			return;
		}
		final Rectangle clickArea = Rs2UiHelper.isRectangleWithinCanvas(bounds)
			? bounds : Rs2UiHelper.getDefaultRectangle();
		Microbot.targetMenu = entry;
		clickImmediately(Rs2UiHelper.getClickingPoint(clickArea, true));
	}

	private void markWalkClick(int playerX, int playerY, WorldPoint target, boolean minimapClick)
	{
		walkClickedAt = client.getTickCount();
		playerXAtWalkClick = playerX;
		playerYAtWalkClick = playerY;
		walkTarget = target;
		walkTargetMinimap = minimapClick;
		debug("walk-click type={} fromScene=({}, {}) target={}", handoffDecision,
			playerX, playerY, target);
	}

	private boolean reusableBankPreview(WorldPoint target)
	{
		final PlanningSnapshot snapshot = bankPreviewSnapshot;
		final Player player = client.getLocalPlayer();
		final WorldView worldView = player == null ? null : player.getWorldView();
		final InventoryState inventory = inventoryState();
		final Map<Integer, Integer> bankStock = cachedBankStock();
		final boolean worldUnchanged = snapshot != null && player != null && worldView != null
			&& snapshot.start.equals(player.getWorldLocation()) && snapshot.world == client.getWorld()
			&& snapshot.worldViewId == worldView.getId() && snapshot.baseX == worldView.getBaseX()
			&& snapshot.baseY == worldView.getBaseY();
		final boolean inventoryUnchanged = snapshot != null && inventory != null
			&& snapshot.inventoryOccupied == inventory.occupied
			&& snapshot.inventoryQuantities.equals(inventory.quantities);
		final boolean optionsUnchanged = snapshot != null && bankTrip != null && player != null
			&& snapshot.running == running()
			&& snapshot.coins == CharterShips.coins(client)
			&& snapshot.availableTransports.equals(transports.stream()
				.filter(this::isTransportAvailable).collect(Collectors.toSet()))
			&& availableTeleportsMatch(snapshot, player.getWorldLocation())
			&& snapshot.banks.equals(Arrays.stream(BankLocation.values()).filter(this::bankAvailable)
				.map(BankLocation::getWorldPoint).distinct().collect(Collectors.toList()))
			&& bankTrip.option.bankWithdrawals(client, bankStock) != null
			&& BankTrip.Option.fitsInventory(client, bankTrip.option.supplies);
		return canReuseBankPreview(target, snapshot == null ? null : snapshot.request.destination,
			worldUnchanged, snapshot != null && snapshot.routeSettings == routeSettings(),
			inventoryUnchanged, snapshot != null && snapshot.bankStock.equals(bankStock), optionsUnchanged);
	}

	static boolean canReuseBankPreview(WorldPoint target, WorldPoint previewTarget,
		boolean worldUnchanged, boolean settingsUnchanged, boolean inventoryUnchanged,
		boolean bankUnchanged, boolean optionsUnchanged)
	{
		return target != null && target.equals(previewTarget) && worldUnchanged && settingsUnchanged
			&& inventoryUnchanged && bankUnchanged && optionsUnchanged;
	}

	private boolean availableTeleportsMatch(PlanningSnapshot snapshot, WorldPoint start)
	{
		if (!snapshot.considerTeleports) { return snapshot.availableTeleports.isEmpty(); }
		List<TeleportLanding> current = new ArrayList<>();
		for (Teleport candidate : teleports)
		{
			WorldPoint landing = candidate.landing();
			if (!rejectedTeleports.contains(candidate) && candidate.isAvailable(start, landing))
			{
				current.add(new TeleportLanding(candidate, landing));
			}
		}
		if (snapshot.availableTeleports.size() != current.size()) { return false; }
		return current.stream().allMatch(candidate -> snapshot.availableTeleports.stream().anyMatch(expected ->
			expected.teleport == candidate.teleport && expected.landing.equals(candidate.landing)));
	}

	private int routeSettings()
	{
		return (config.useTeleportations() ? 1 : 0)
			| (config.useItemsFromBank() ? 1 << 1 : 0)
			| (config.useFairyRings() ? 1 << 2 : 0)
			| (config.useCharterShips() ? 1 << 3 : 0)
			| (config.useSpiritTrees() ? 1 << 4 : 0)
			| (config.allowAgilityShortcuts() ? 1 << 5 : 0)
			| (config.allowGrappleShortcuts() ? 1 << 6 : 0)
			| (config.spiritTreePortSarim() ? 1 << 7 : 0)
			| (config.spiritTreeEtceteria() ? 1 << 8 : 0)
			| (config.spiritTreeBrimhaven() ? 1 << 9 : 0)
			| (config.spiritTreeHosidius() ? 1 << 10 : 0)
			| (config.spiritTreeFarmingGuild() ? 1 << 11 : 0);
	}

	private boolean running()
	{
		return client.getEnergy() >= RUN_PREFLIGHT_THRESHOLD
			|| client.getEnergy() > 0 && client.getVarpValue(VarPlayerID.OPTION_RUN) == 1;
	}

	private InventoryState inventoryState()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null || inventory.getItems() == null) { return null; }
		Map<Integer, Integer> quantities = new HashMap<>();
		int occupied = 0;
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() >= 0)
			{
				occupied++;
				quantities.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return new InventoryState(occupied, quantities);
	}

	private static Map<Integer, Integer> cachedBankStock()
	{
		return Rs2Bank.bankItems().stream().collect(Collectors.toMap(
			item -> item.getId(), item -> item.getQuantity(), Integer::sum));
	}

	private Point minimapPoint(LocalPoint localPoint)
	{
		final Point point = Perspective.localToMinimap(client, localPoint);
		return point != null && Rs2MiniMap.isPointInsideMinimap(point) ? point : null;
	}

	private boolean beginPlanning(Request requested, Player player, boolean considerTeleports)
	{
		transitionRoomRoute = false;
		final long started = System.nanoTime();
		final boolean detouring = bankTrip != null;
		final WorldPoint destination = detouring ? bankTrip.bank : requested.destination;
		planningFailure = PlanningFailure.NONE;
		if (player == null || player.getWorldView() == null || player.getLocalLocation() == null)
		{
			planningFailure = PlanningFailure.ROUTE_UNSUPPORTED;
			debug("plan failed reason=player-state-unavailable target={}", destination);
			return false;
		}

		final WorldView worldView = player.getWorldView();
		final WorldPoint start = player.getWorldLocation();
		if (WorldPathfinder.isWilderness(start)
			|| !requested.nearestBank && WorldPathfinder.isWilderness(destination))
		{
			planningFailure = PlanningFailure.ROUTE_UNSUPPORTED;
			debug("plan failed reason=wilderness-route-rejected start={} target={}", start,
				destination);
			return false;
		}
		Route loadedRoute = null;
		PlanningFailure loadedFailure = null;
		if (!requested.nearestBank && start.getPlane() == destination.getPlane())
		{
			loadedRoute = buildRoute(worldView, start, destination, start.getPlane());
			if (loadedRoute == null)
			{
				final LocalPoint loadedTarget = LocalPoint.fromWorld(worldView,
					atWorldViewPlane(destination, worldView));
				if (loadedTarget != null)
				{
					loadedFailure = planningFailureForLoadedTarget(
						collisionFlags(worldView, start.getPlane()),
						loadedTarget.getSceneX(), loadedTarget.getSceneY());
					debug("plan local-miss reason=loaded-target-unreachable start={} target={} failure={}",
						start, destination, loadedFailure);
				}
			}
		}
		if (!detouring && requested.destinations != null)
		{
			loadedRoute = null;
			for (WorldPoint candidate : requested.destinations)
			{
				if (candidate.getPlane() != start.getPlane()) { continue; }
				Route route = buildRoute(worldView, start, candidate, start.getPlane());
				if (route != null && (loadedRoute == null || route.worldPath.size() < loadedRoute.worldPath.size()))
				{
					loadedRoute = route;
				}
			}
		}

		Set<Long> liveWalkEdges = Collections.emptySet();
		Set<Transport> availableTransports = Collections.emptySet();
		List<TeleportLanding> availableTeleports = Collections.emptyList();
		if (!worldView.isInstance())
		{
			liveWalkEdges = liveWalkEdges(worldView, start.getPlane());
			if (!liveWalkEdges.isEmpty())
			{
				debug("plan live-walk-edge-overrides={}", liveWalkEdges.size());
			}
			availableTransports = transports.stream()
				.filter(this::isTransportAvailable)
				.collect(Collectors.toSet());
			if (considerTeleports && !requested.escort && config.useTeleportations())
			{
				availableTeleports = new ArrayList<>();
				for (Teleport candidate : teleports)
				{
					final WorldPoint landing = candidate.landing();
					if (!rejectedTeleports.contains(candidate) && candidate.isAvailable(start, landing))
					{
						availableTeleports.add(new TeleportLanding(candidate, landing));
					}
				}
			}
		}

		final List<WorldPoint> destinations = detouring ? Collections.singletonList(bankTrip.bank) : requested.nearestBank
			? Arrays.stream(BankLocation.values()).filter(this::bankAvailable)
				.map(BankLocation::getWorldPoint).distinct().collect(Collectors.toList())
			: requested.destinations != null ? requested.destinations : Collections.singletonList(destination);
		if (destinations.isEmpty())
		{
			planningFailure = PlanningFailure.ROUTE_UNSUPPORTED;
			return false;
		}
		List<BankTrip.Option> bankOptions = new ArrayList<>();
		List<WorldPoint> banks = Collections.emptyList();
		Map<Integer, Integer> cachedBank = Collections.emptyMap();
		int bankCoins = 0;
		boolean considerBank = !detouring && !requested.bankConsidered && !requested.nearestBank
			&& !worldView.isInstance() && config.useItemsFromBank();
		if (considerBank)
		{
			cachedBank = cachedBankStock();
			bankCoins = BankTrip.Option.fitsInventory(client, Map.of(net.runelite.api.gameval.ItemID.COINS, 1))
				? cachedBank.getOrDefault(net.runelite.api.gameval.ItemID.COINS, 0) : 0;
			for (Teleport candidate : considerTeleports && config.useTeleportations() ? teleports : Collections.<Teleport>emptyList())
			{
				WorldPoint landing = candidate.landing();
				if (landing == null || rejectedTeleports.contains(candidate) || candidate.isAvailable(start, landing)) { continue; }
				bankOptions.addAll(candidate.bankOptions(client, cachedBank, landing).stream()
					.filter(option -> option.fitsInventory(client)).collect(Collectors.toList()));
			}
		}
		Set<Transport> fundedTransports = new HashSet<>(availableTransports);
		Map<Transport, Map<Integer, Integer>> bankTransportSupplies = new HashMap<>();
		if (bankCoins > 0)
		{
			transports.stream().filter(this::isTransportAllowed)
				.filter(candidate -> candidate.isAlKharidGate()
					|| CharterShips.isCharter(candidate) && charterShips.unlocked(client, candidate))
				.forEach(fundedTransports::add);
		}
		if (considerBank)
		{
			for (Transport candidate : transports)
			{
				if (fundedTransports.contains(candidate) || !isTransportAllowed(candidate)) { continue; }
				Map<Integer, Integer> supplies = FairyRings.isRing(candidate)
					? fairyRings.bankSupplies(client, candidate, cachedBank)
					: candidate.bankSupplies(client, cachedBank);
				if (supplies == null || supplies.isEmpty()
					|| !FairyRings.isRing(candidate) && !candidate.isAvailableIgnoringItems()) { continue; }
				fundedTransports.add(candidate);
				bankTransportSupplies.put(candidate, supplies);
			}
			if (!bankOptions.isEmpty() || bankCoins > 0 || !bankTransportSupplies.isEmpty())
			{
				banks = Arrays.stream(BankLocation.values()).filter(this::bankAvailable)
					.map(BankLocation::getWorldPoint).distinct().collect(Collectors.toList());
			}
		}
		final PlanningSnapshot snapshot = new PlanningSnapshot(requested, start,
			worldView.getId(), worldView.getBaseX(), worldView.getBaseY(), worldView.isInstance(),
			loadedRoute, loadedFailure, liveWalkEdges, availableTransports, availableTeleports,
			new HashSet<>(rejectedTransitions), destinations, considerTeleports, started);
		snapshot.world = client.getWorld();
		snapshot.routeSettings = routeSettings();
		snapshot.bankOptions = bankOptions;
		snapshot.bankCoinOptions = bankOptions.stream().filter(option -> option.withCoins(1, 0).fitsInventory(client))
			.collect(Collectors.toSet());
		snapshot.banks = banks;
		snapshot.coins = CharterShips.coins(client);
		snapshot.bankCoins = bankCoins;
		snapshot.bankAvailableTransports = Collections.unmodifiableSet(fundedTransports);
		snapshot.bankTransportSupplies = Collections.unmodifiableMap(new HashMap<>(bankTransportSupplies));
		snapshot.bankStock = Collections.unmodifiableMap(new HashMap<>(cachedBank));
		InventoryState inventory = inventoryState();
		snapshot.inventoryOccupied = inventory == null ? -1 : inventory.occupied;
		snapshot.inventoryQuantities = inventory == null ? Collections.emptyMap() : inventory.quantities;
		Set<Integer> stackable = new HashSet<>();
		for (int id : cachedBank.keySet())
		{
			if (client.getItemDefinition(id) != null && client.getItemDefinition(id).isStackable()) { stackable.add(id); }
		}
		snapshot.stackableItems = Collections.unmodifiableSet(stackable);
		Map<Transport, Integer> fares = new HashMap<>();
		for (Transport candidate : fundedTransports)
		{
			int fare = transportCoinCost(candidate);
			if (fare > 0) { fares.put(candidate, fare); }
		}
		snapshot.transportFares = Collections.unmodifiableMap(fares);
		snapshot.running = running();
		if (snapshot.instance || usesLoadedRouteImmediately(loadedRoute == null
			? Integer.MAX_VALUE : Math.max(0, loadedRoute.worldPath.size() - 1)))
		{
			applyPlanningResult(snapshot, new PlanningResult(null, null), player);
			return true;
		}
		pendingPlanSnapshot = snapshot;
		status = Status.PLANNING;
		final FutureTask<PlanningResult> plan = new FutureTask<PlanningResult>(() -> calculatePlan(snapshot))
		{
			@Override
			protected void done()
			{
				if (completedPlanDispatcher != null)
				{
					completedPlanDispatcher.execute(() -> finishPlanning(requested, this, snapshot));
				}
			}
		};
		pendingPlan = plan;
		plannerExecutor().execute(plan);
		return true;
	}

	private PlanningResult calculatePlan(PlanningSnapshot snapshot)
	{
		if (snapshot.instance)
		{
			return new PlanningResult(null, null);
		}
		final int loadedCost = snapshot.loadedRoute == null
			? Integer.MAX_VALUE : Math.max(0, snapshot.loadedRoute.worldPath.size() - 1);
		if (usesLoadedRouteImmediately(loadedCost))
		{
			return new PlanningResult(null, null);
		}
		final WorldPathfinder.SearchResult result = findBudgetedRoute(snapshot,
			Collections.singletonList(snapshot.start), snapshot.destinations, snapshot.liveWalkEdges, snapshot.coins);
		int baselineTicks = Math.min(BankTrip.travelTicks(result.path, snapshot.running),
			snapshot.loadedRoute == null ? Integer.MAX_VALUE : BankTrip.travelTicks(snapshot.loadedRoute.worldPath, snapshot.running));
		final TeleportRoute teleportRoute = snapshot.considerTeleports
			? bestTeleportRoute(snapshot, baselineTicks) : null;
		PlanningResult calculated = new PlanningResult(result, teleportRoute);
		if (!snapshot.banks.isEmpty())
		{
			if (teleportRoute != null)
			{
				baselineTicks = Math.min(baselineTicks, teleportRoute.cost);
			}
			WorldPathfinder.SearchResult bankRoute = findBudgetedRoute(snapshot, Collections.singletonList(snapshot.start),
				snapshot.banks, snapshot.liveWalkEdges, snapshot.coins);
			TeleportRoute bankTeleport = bestTeleportRoute(snapshot, snapshot.banks, BankTrip.travelTicks(bankRoute.path, snapshot.running));
			List<WorldPoint> bankPath = bankTeleport == null ? bankRoute.path : bankTeleport.result.path;
			int onwardWallet = snapshot.coins - Math.max(0, bankTeleport == null ? bankRoute.coinsSpent : bankTeleport.result.coinsSpent);
			if (!bankPath.isEmpty())
			{
				WorldPoint bank = bankPath.get(bankPath.size() - 1);
				WorldPathfinder.SearchResult onward = findBankFundedRoute(snapshot, List.of(bank), snapshot.destinations,
					Collections.emptySet(), (int) Math.min(Integer.MAX_VALUE, (long) onwardWallet + snapshot.bankCoins));
				Map<Integer, Integer> routeSupplies = transportSupplies(snapshot, onward.path);
				if (!onward.path.isEmpty() && (onward.coinsSpent > onwardWallet || !routeSupplies.isEmpty()))
				{
					BankTrip.Option option = BankTrip.Option.supplies(bank, routeSupplies)
						.withCoins(onward.coinsSpent, onwardWallet);
					int bankTicks = bankTeleport == null ? BankTrip.travelTicks(bankPath, snapshot.running) : bankTeleport.cost;
					int total = bankTicks + option.bankTicks() + BankTrip.travelTicks(onward.path, snapshot.running);
					if (bankOptionAvailable(snapshot, option) && BankTrip.worthwhile(baselineTicks, total))
					{
						calculated.bankTrip = new BankTrip(bank, option, baselineTicks, total);
						calculated.bankPreviewPath = bankPreviewPath(snapshot.start, bankPath, onward.path);
					}
				}
			}

			Map<Integer, List<BankTrip.Option>> groups = snapshot.bankOptions.stream()
				.collect(Collectors.groupingBy(option -> 2 * (option.bankTicks() + option.activationTicks())
					+ (snapshot.bankCoinOptions.contains(option) ? 1 : 0)));
			for (Map.Entry<Integer, List<BankTrip.Option>> group : groups.entrySet())
			{
				int availableBankCoins = group.getKey() % 2 == 1 ? snapshot.bankCoins : 0;
				WorldPathfinder.SearchResult onward = findBankFundedRoute(snapshot, group.getValue().stream()
					.map(option -> option.landing).collect(Collectors.toList()), snapshot.destinations,
					Collections.emptySet(), (int) Math.min(Integer.MAX_VALUE, (long) onwardWallet + availableBankCoins));
				if (bankPath.isEmpty() || onward.path.isEmpty()) { continue; }
				int bankTicks = bankTeleport == null ? BankTrip.travelTicks(bankPath, snapshot.running) : bankTeleport.cost;
				BankTrip.Option selected = group.getValue().stream()
					.filter(candidate -> candidate.landing.equals(onward.path.get(0))).findFirst().orElseThrow()
					.withSupplies(transportSupplies(snapshot, onward.path))
					.withCoins(Math.max(0, onward.coinsSpent), onwardWallet);
				int total = bankTicks + selected.bankTicks() + selected.activationTicks()
					+ BankTrip.travelTicks(onward.path, snapshot.running);
				if (bankOptionAvailable(snapshot, selected) && BankTrip.worthwhile(baselineTicks, total)
					&& (calculated.bankTrip == null || total < calculated.bankTrip.totalTicks))
				{
					calculated.bankTrip = new BankTrip(bankPath.get(bankPath.size() - 1), selected, baselineTicks, total);
					calculated.bankPreviewPath = bankPreviewPath(snapshot.start, bankPath, onward.path);
				}
			}
		}
		return calculated;
	}

	private static Map<Integer, Integer> transportSupplies(PlanningSnapshot snapshot,
		List<WorldPoint> path)
	{
		Map<Integer, Integer> supplies = new HashMap<>();
		for (int index = 1; index < path.size(); index++)
		{
			WorldPoint approach = path.get(index - 1);
			WorldPoint landing = path.get(index);
			for (Map.Entry<Transport, Map<Integer, Integer>> entry : snapshot.bankTransportSupplies.entrySet())
			{
				Transport transport = entry.getKey();
				if (transport.approach.equals(approach) && transport.landing.equals(landing))
				{
					entry.getValue().forEach((id, quantity) -> supplies.merge(id, quantity, Math::max));
				}
			}
		}
		return supplies;
	}

	private static boolean bankOptionAvailable(PlanningSnapshot snapshot, BankTrip.Option option)
	{
		for (Map.Entry<Integer, Integer> supply : option.supplies.entrySet())
		{
			int missing = Math.max(0, supply.getValue()
				- snapshot.inventoryQuantities.getOrDefault(supply.getKey(), 0));
			if (missing > snapshot.bankStock.getOrDefault(supply.getKey(), 0)) { return false; }
		}
		return BankTrip.Option.fitsInventory(snapshot.inventoryOccupied, snapshot.inventoryQuantities,
			snapshot.stackableItems, option.supplies);
	}

	private boolean finishPlanning(Request requested)
	{
		if (!routeContainersReady()) { return false; }
		if (!pendingPlan.isDone())
		{
			return false;
		}
		final PlanningSnapshot snapshot = pendingPlanSnapshot;
		final PlanningResult result;
		try
		{
			result = pendingPlan.get();
		}
		catch (InterruptedException exception)
		{
			Thread.currentThread().interrupt();
			cancelPendingPlan();
			block("planning-interrupted");
			return true;
		}
		catch (ExecutionException exception)
		{
			cancelPendingPlan();
			log.warn("Route planning worker failed", exception);
			block("planning-worker-error");
			return true;
		}
		pendingPlan = null;
		pendingPlanSnapshot = null;

		final Player player = client.getLocalPlayer();
		final WorldView worldView = player == null ? null : player.getWorldView();
		if (request != requested || activeRequest != requested)
		{
			return false;
		}
		if (player == null || worldView == null || !snapshot.start.equals(player.getWorldLocation())
			|| snapshot.worldViewId != worldView.getId() || snapshot.baseX != worldView.getBaseX()
			|| snapshot.baseY != worldView.getBaseY())
		{
			if (!beginPlanning(requested, player, snapshot.considerTeleports))
			{
				block("plan-unavailable");
			}
			return false;
		}
		applyPlanningResult(snapshot, result, player);
		return pendingPlan == null;
	}

	private void finishPlanning(Request requested, FutureTask<PlanningResult> plan,
		PlanningSnapshot snapshot)
	{
		if (pendingPlan == plan && pendingPlanSnapshot == snapshot)
		{
			finishPlanning(requested);
		}
	}

	private void applyPlanningResult(PlanningSnapshot snapshot, PlanningResult calculated, Player player)
	{
		final Request requested = snapshot.request;
		final WorldView worldView = player.getWorldView();
		if (calculated.bankTrip != null)
		{
			bankTrip = calculated.bankTrip;
			requested.bankConsidered = true;
			Microbot.status = "Bank detour for " + bankTrip.option.name();
			if (!requested.walk)
			{
				bankPreviewSnapshot = snapshot;
				route = null;
				clearGlobalRoute();
				plannedRoutes = Collections.emptyList();
				plannedTransitions = Collections.emptyList();
				transition = null;
				pathSegments = splitByPlane(calculated.bankPreviewPath);
				nextObstacle = new ObstacleMarker(bankTrip.bank, "Bank", bankTrip.option.name());
				status = Status.PREVIEW;
				debug("plan ready kind=bank-preview start={} bank={} target={} pathLength={} millis={}",
					snapshot.start, bankTrip.bank, requested.destination,
					calculated.bankPreviewPath.size(), elapsedMillis(snapshot.started));
				return;
			}
			if (!beginPlanning(requested, player, true)) { block("plan-unavailable"); }
			return;
		}
		final WorldPathfinder.SearchResult result = calculated.worldRoute;
		if (bankTrip == null && (requested.nearestBank || requested.destinations != null))
		{
			final WorldPathfinder.SearchResult selected = calculated.teleportRoute == null
				? result : calculated.teleportRoute.result;
			requested.destination = selected == null || selected.path.isEmpty()
				? snapshot.loadedRoute == null ? null
					: snapshot.loadedRoute.worldPath.get(snapshot.loadedRoute.worldPath.size() - 1)
				: selected.path.get(selected.path.size() - 1);
			if (requested.destination == null)
			{
				planningFailure = result == null ? PlanningFailure.ROUTE_UNSUPPORTED : result.failure;
				block(requested.nearestBank ? "nearest-bank-unavailable" : "quest-approach-unavailable");
				return;
			}
		}
		final PlanningFailure worldFailure = result == null
			? PlanningFailure.ROUTE_UNSUPPORTED : result.failure;
		final int loadedCost = snapshot.loadedRoute == null
			? Integer.MAX_VALUE : Math.max(0, snapshot.loadedRoute.worldPath.size() - 1);
		final TeleportRoute teleportRoute = calculated.teleportRoute;
		if (teleportRoute != null)
		{
			plannedTeleport = teleportRoute.teleport;
			plannedTeleportLanding = teleportRoute.landing;
			adoptGlobalRoute(teleportRoute.result);
			globalProgress = 0;
			route = null;
			plannedRoutes = Collections.emptyList();
			plannedTransitions = Collections.emptyList();
			pathSegments = splitByPlane(globalPath);
			nextObstacle = new ObstacleMarker(snapshot.start, "Teleport", plannedTeleport.name);
			debug("plan ready kind=teleport start={} target={} teleport={} landing={} estimatedTicks={} "
				+ "baselineCost={} millis={}", snapshot.start, requested.destination, plannedTeleport.name,
				plannedTeleportLanding, teleportRoute.cost,
				result == null ? loadedCost : Math.min(result.cost, loadedCost), elapsedMillis(snapshot.started));
			status = requested.walk ? Status.WALKING : Status.PREVIEW;
			return;
		}

		adoptGlobalRoute(result);
		if (!globalPath.isEmpty())
		{
			globalProgress = 0;
			route = buildGlobalRoute(worldView, snapshot.start);
			if (route != null)
			{
				plannedRoutes = Collections.singletonList(route);
				plannedTransitions = Collections.emptyList();
				pathSegments = splitByPlane(globalPath);
				debug("plan ready kind=global start={} target={} pathLength={} planeSegments={} "
					+ "firstTransition={} millis={}", snapshot.start, requested.destination,
					globalPath.size(), pathSegments.size(), transportName(transition),
					elapsedMillis(snapshot.started));
				refreshNextObstacle(worldView, route, 0);
				status = requested.walk ? Status.WALKING : Status.PREVIEW;
				return;
			}
			clearGlobalRoute();
		}
		if (snapshot.loadedRoute != null)
		{
			route = snapshot.loadedRoute;
			if (bankTrip == null && requested.destinations != null)
			{
				requested.destination = route.worldPath.get(route.worldPath.size() - 1);
			}
			plannedRoutes = Collections.singletonList(route);
			plannedTransitions = Collections.emptyList();
			transition = null;
			pathSegments = worldPaths(plannedRoutes);
			debug("plan ready kind=local-fallback start={} target={} pathLength={} millis={}", snapshot.start,
				requested.destination, route.worldPath.size(), elapsedMillis(snapshot.started));
			refreshNextObstacle(worldView, route, 0);
			status = requested.walk ? Status.WALKING : Status.PREVIEW;
			return;
		}
		WorldPoint routeTarget = bankTrip == null ? requested.destination : bankTrip.bank;
		if (snapshot.start.getPlane() == routeTarget.getPlane())
		{
			planningFailure = worldFailure == PlanningFailure.ROUTE_UNSUPPORTED && snapshot.loadedFailure != null
				? snapshot.loadedFailure : worldFailure;
			block("plan-unavailable");
			return;
		}

		final TransportPlan plan = prepareTransportPlan(worldView, snapshot.start, routeTarget);
		if (plan == null)
		{
			planningFailure = worldFailure;
			debug("plan failed reason=no-supported-route start={} target={} millis={}", snapshot.start,
				requested.destination, elapsedMillis(snapshot.started));
			block("plan-unavailable");
			return;
		}
		plannedRoutes = plan.routes;
		plannedTransitions = plan.transitions;
		route = plannedRoutes.get(0);
		transition = plannedTransitions.get(0);
		pathSegments = worldPaths(plannedRoutes);
		debug("plan ready kind=loaded-transports start={} target={} pathSegments={} transitions={} "
			+ "firstTransition={} millis={}", snapshot.start, requested.destination, plannedRoutes.size(),
			plannedTransitions.size(), transportName(transition), elapsedMillis(snapshot.started));
		refreshNextObstacle(worldView, route, 0);
		status = requested.walk ? Status.WALKING : Status.PREVIEW;
	}

	private TeleportRoute bestTeleportRoute(PlanningSnapshot snapshot, int baselineCost)
	{
		return bestTeleportRoute(snapshot, snapshot.destinations, baselineCost);
	}

	private WorldPathfinder.SearchResult findBudgetedRoute(PlanningSnapshot snapshot, Collection<WorldPoint> starts,
		Collection<WorldPoint> destinations, Set<Long> liveEdges, int wallet)
	{
		return snapshot.transportFares.isEmpty()
			? worldPathfinder.findNearestResult(starts, destinations, snapshot.rejectedTransitions,
				liveEdges, snapshot.availableTransports, snapshot.wilderness)
			: worldPathfinder.findNearestResult(starts, destinations, snapshot.rejectedTransitions, liveEdges,
				snapshot.availableTransports, snapshot.transportFares, wallet, snapshot.wilderness);
	}

	private WorldPathfinder.SearchResult findBankFundedRoute(PlanningSnapshot snapshot, Collection<WorldPoint> starts,
		Collection<WorldPoint> destinations, Set<Long> liveEdges, int wallet)
	{
		return worldPathfinder.findNearestResult(starts, destinations, snapshot.rejectedTransitions, liveEdges,
			snapshot.bankAvailableTransports, snapshot.transportFares, wallet, snapshot.wilderness);
	}

	private TeleportRoute bestTeleportRoute(PlanningSnapshot snapshot, List<WorldPoint> destinations, int baselineCost)
	{
		TeleportRoute best = null;
		Map<Integer, List<TeleportLanding>> groups = snapshot.availableTeleports.stream()
			.collect(Collectors.groupingBy(candidate -> candidate.teleport.activationTicks(),
				java.util.LinkedHashMap::new, Collectors.toList()));
		for (Map.Entry<Integer, List<TeleportLanding>> group : groups.entrySet())
		{
			WorldPathfinder.SearchResult result = findBudgetedRoute(snapshot,
				group.getValue().stream().map(candidate -> candidate.landing).collect(Collectors.toList()),
				destinations, Collections.emptySet(), snapshot.coins);
			if (result.path.isEmpty()) { continue; }
			int cost = BankTrip.travelTicks(result.path, snapshot.running) + group.getKey();
			if (!isTeleportWorthwhile(baselineCost, cost) || best != null && cost >= best.cost) { continue; }
			WorldPoint landing = result.path.get(0);
			TeleportLanding selected = group.getValue().stream()
				.filter(candidate -> candidate.landing.equals(landing)).findFirst().orElseThrow();
			best = new TeleportRoute(selected.teleport, landing, result, cost);
		}
		return best;
	}

	private Executor plannerExecutor()
	{
		if (suppliedPlanner != null)
		{
			return suppliedPlanner;
		}
		if (ownedPlanner == null || ownedPlanner.isShutdown())
		{
			ownedPlanner = Executors.newSingleThreadExecutor(runnable ->
			{
				final Thread thread = new Thread(runnable, "EfficientWalker-Planning");
				thread.setDaemon(true);
				return thread;
			});
		}
		return ownedPlanner;
	}

	private void cancelPendingPlan()
	{
		if (pendingPlan != null)
		{
			pendingPlan.cancel(true);
			pendingPlan = null;
			pendingPlanSnapshot = null;
		}
	}

	static boolean isTeleportWorthwhile(int baselineCost, int teleportCost)
	{
		return teleportCost <= Integer.MAX_VALUE - TELEPORT_MINIMUM_SAVINGS
			&& (baselineCost == Integer.MAX_VALUE
				|| teleportCost + TELEPORT_MINIMUM_SAVINGS < baselineCost);
	}

	static boolean usesLoadedRouteImmediately(int loadedCost)
	{
		return loadedCost <= TELEPORT_MINIMUM_SAVINGS;
	}

	private TransportPlan prepareTransportPlan(WorldView worldView, WorldPoint start, WorldPoint target)
	{
		if (worldView.isInstance())
		{
			return null;
		}

		final List<Transport> available = new ArrayList<>();
		for (Transport candidate : transports)
		{
			if (!rejectedTransitions.contains(candidate)
				&& !WorldPathfinder.isWilderness(candidate.approach)
				&& !WorldPathfinder.isWilderness(candidate.landing)
				&& isTransportAvailable(candidate)
				&& candidate.approach.getPlane() != candidate.landing.getPlane()
				&& LocalPoint.fromWorld(worldView, atWorldViewPlane(candidate.approach, worldView)) != null
				&& LocalPoint.fromWorld(worldView, atWorldViewPlane(candidate.landing, worldView)) != null
				&& hasTransitionTarget(worldView, candidate))
			{
				available.add(candidate);
			}
		}

		final int wallet = CharterShips.coins(client);
		final Map<Transport, Integer> fares = new HashMap<>();
		for (Transport candidate : available) { fares.put(candidate, transportCoinCost(candidate)); }
		final PriorityQueue<PlanNode> pending = new PriorityQueue<>(Comparator.comparingInt((PlanNode node) -> node.length)
			.thenComparingInt(node -> node.coinsSpent));
		final Map<WorldPoint, List<PlanNode>> bestAt = new HashMap<>();
		retainPlanNode(new PlanNode(start, Collections.emptyList(), Collections.emptyList(), 0, 0), bestAt, pending);
		TransportPlan bestPlan = null;
		int bestLength = Integer.MAX_VALUE;
		while (!pending.isEmpty())
		{
			final PlanNode node = pending.remove();
			if (node.length >= bestLength || !bestAt.get(node.position).contains(node))
			{
				continue;
			}

			if (node.position.getPlane() == target.getPlane())
			{
				final Route exit = buildRoute(worldView, node.position, target, target.getPlane());
				if (exit != null && node.length + exit.path.length < bestLength)
				{
					final List<Route> routes = new ArrayList<>(node.routes);
					routes.add(exit);
					bestLength = node.length + exit.path.length;
					bestPlan = new TransportPlan(routes, node.transitions);
				}
				continue;
			}

			for (Transport candidate : available)
			{
				int fare = fares.get(candidate);
				if (candidate.approach.getPlane() != node.position.getPlane() || fare > wallet - node.coinsSpent)
				{
					continue;
				}
				final Route approach = buildRoute(worldView, node.position, candidate.approach,
					node.position.getPlane());
				final int length = approach == null ? Integer.MAX_VALUE : node.length + approach.path.length;
				if (length >= bestLength)
				{
					continue;
				}
				final List<Route> routes = new ArrayList<>(node.routes);
				routes.add(approach);
				final List<Transport> steps = new ArrayList<>(node.transitions);
				steps.add(candidate);
				retainPlanNode(new PlanNode(candidate.landing, routes, steps, length, node.coinsSpent + fare), bestAt, pending);
			}
		}
		return bestPlan;
	}

	private static boolean retainPlanNode(PlanNode node, Map<WorldPoint, List<PlanNode>> bestAt, PriorityQueue<PlanNode> pending)
	{
		List<PlanNode> labels = bestAt.computeIfAbsent(node.position, ignored -> new ArrayList<>());
		for (PlanNode old : labels)
		{
			if (old.length <= node.length && old.coinsSpent <= node.coinsSpent) { return false; }
		}
		labels.removeIf(old -> node.length <= old.length && node.coinsSpent <= old.coinsSpent);
		labels.add(node);
		pending.add(node);
		return true;
	}

	private static List<List<WorldPoint>> worldPaths(List<Route> routes)
	{
		final List<List<WorldPoint>> paths = new ArrayList<>(routes.size());
		for (Route plannedRoute : routes)
		{
			paths.add(plannedRoute.worldPath);
		}
		return Collections.unmodifiableList(paths);
	}

	private static List<List<WorldPoint>> splitByPlane(List<WorldPoint> path)
	{
		final List<List<WorldPoint>> paths = new ArrayList<>();
		int start = 0;
		for (int i = 1; i < path.size(); i++)
		{
			if (path.get(i).getPlane() != path.get(i - 1).getPlane())
			{
				paths.add(Collections.unmodifiableList(new ArrayList<>(path.subList(start, i))));
				start = i;
			}
		}
		paths.add(Collections.unmodifiableList(new ArrayList<>(path.subList(start, path.size()))));
		return Collections.unmodifiableList(paths);
	}

	static List<WorldPoint> bankPreviewPath(WorldPoint start, List<WorldPoint> bankPath,
		List<WorldPoint> onwardPath)
	{
		final List<WorldPoint> path = new ArrayList<>(bankPath.size() + onwardPath.size() + 1);
		if (bankPath.isEmpty() || !start.equals(bankPath.get(0))) { path.add(start); }
		path.addAll(bankPath);
		int onwardStart = !path.isEmpty() && !onwardPath.isEmpty()
			&& path.get(path.size() - 1).equals(onwardPath.get(0)) ? 1 : 0;
		path.addAll(onwardPath.subList(onwardStart, onwardPath.size()));
		return Collections.unmodifiableList(path);
	}

	private boolean extendGlobalRoute(WorldView worldView, WorldPoint playerWorld)
	{
		final int progress = indexOf(globalPath, playerWorld, globalProgress);
		if (progress < 0)
		{
			return true;
		}
		globalProgress = progress;
		if (route == null)
		{
			return false;
		}

		final int currentEnd = indexOf(globalPath,
			route.worldPath.get(route.worldPath.size() - 1), globalProgress);
		final int availableEnd = furthestLoadedIndex(worldView, globalProgress);
		if (availableEnd <= currentEnd)
		{
			return true;
		}

		final Route extended = buildGlobalRoute(worldView, playerWorld);
		if (extended == null)
		{
			return false;
		}
		route = extended;
		plannedRoutes = Collections.singletonList(extended);
		return true;
	}

	private Route buildGlobalRoute(WorldView worldView, WorldPoint playerWorld)
	{
		final int startIndex = indexOf(globalPath, playerWorld, globalProgress);
		if (startIndex < 0)
		{
			return null;
		}
		globalProgress = startIndex;
		final int loadedEndIndex = furthestLoadedIndex(worldView, startIndex);
		Transport nextTransition = loadedEndIndex < globalPath.size() - 1
			? transportBetween(globalPath.get(loadedEndIndex), globalPath.get(loadedEndIndex + 1)) : null;
		if (loadedEndIndex <= startIndex && startIndex < globalPath.size() - 1 && nextTransition == null)
		{
			return null;
		}
		int endIndex = loadedEndIndex;
		Route liveRoute = null;
		while (endIndex >= startIndex && liveRoute == null)
		{
			liveRoute = buildRoute(worldView, playerWorld,
				globalPath.get(endIndex), playerWorld.getPlane());
			if (liveRoute == null)
			{
				endIndex--;
			}
		}
		if (liveRoute == null)
		{
			return null;
		}
		if (endIndex != loadedEndIndex - 1
			|| !canInteractFromAdjacentTile(nextTransition, globalPath.get(endIndex))
			|| !hasTransitionTarget(worldView, nextTransition))
		{
		nextTransition = endIndex < globalPath.size() - 1
			? transportBetween(globalPath.get(endIndex), globalPath.get(endIndex + 1)) : null;
		}
		if (endIndex <= startIndex && startIndex < globalPath.size() - 1 && nextTransition == null)
		{
			return null;
		}
		if (endIndex < loadedEndIndex)
		{
			debug("global live-route shortened loadedEnd={} reachableEnd={}",
				globalPath.get(loadedEndIndex), globalPath.get(endIndex));
		}
		globalPath = replaceRemainingGlobalPath(globalPath, endIndex, liveRoute.worldPath);
		globalProgress = 0;
		pathSegments = splitByPlane(globalPath);
		transition = nextTransition;
		return liveRoute;
	}

	static boolean canInteractFromAdjacentTile(Transport step, WorldPoint point)
	{
		return step != null && !step.isWalkEdge() && step.approach.getPlane() == point.getPlane()
			&& step.approach.distanceTo2D(point) == 1;
	}

	static List<WorldPoint> replaceRemainingGlobalPath(List<WorldPoint> path, int replacedEnd,
		List<WorldPoint> replacement)
	{
		final List<WorldPoint> remaining = new ArrayList<>(replacement.size()
			+ path.size() - replacedEnd - 1);
		remaining.addAll(replacement);
		remaining.addAll(path.subList(replacedEnd + 1, path.size()));
		return Collections.unmodifiableList(remaining);
	}

	private int furthestLoadedIndex(WorldView worldView, int startIndex)
	{
		int end = startIndex;
		for (int i = startIndex + 1; i < globalPath.size(); i++)
		{
			if (transportBetween(globalPath.get(i - 1), globalPath.get(i)) != null
				|| globalPath.get(i).getPlane() != globalPath.get(startIndex).getPlane()
				|| LocalPoint.fromWorld(worldView, globalPath.get(i)) == null)
			{
				break;
			}
			end = i;
		}
		return end;
	}

	private Transport transportBetween(WorldPoint from, WorldPoint to)
	{
		for (Transport candidate : globalSelectedTransitions == null ? transports : globalSelectedTransitions)
		{
			if (!rejectedTransitions.contains(candidate) && !candidate.isWalkEdge()
				&& candidate.approach.equals(from) && candidate.landing.equals(to))
			{
				return candidate;
			}
		}
		return null;
	}

	private void adoptGlobalRoute(WorldPathfinder.SearchResult result)
	{
		globalPath = result == null ? Collections.emptyList() : result.path;
		globalSelectedTransitions = result == null || result.coinsSpent < 0 ? null : result.transitions;
	}

	private void clearGlobalRoute()
	{
		globalPath = Collections.emptyList();
		globalSelectedTransitions = null;
	}

	private static int indexOf(List<WorldPoint> path, WorldPoint point, int startIndex)
	{
		for (int i = Math.max(0, startIndex); i < path.size(); i++)
		{
			if (path.get(i).equals(point))
			{
				return i;
			}
		}
		return -1;
	}

	private Route buildRoute(WorldView worldView, WorldPoint start, WorldPoint target, int plane)
	{
		if (start.getPlane() != plane || target.getPlane() != plane
			|| MotherlodeRockfalls.TILES.contains(target)
			|| WorldPathfinder.isWilderness(start) || WorldPathfinder.isWilderness(target)
			|| worldView.isInstance() && plane != worldView.getPlane())
		{
			return null;
		}

		final LocalPoint playerPoint = client.getLocalPlayer().getLocalLocation();
		final LocalPoint startPoint = resolveLocalPoint(worldView, atWorldViewPlane(start, worldView), playerPoint);
		final LocalPoint targetPoint = resolveLocalPoint(worldView, atWorldViewPlane(target, worldView), playerPoint);
		if (startPoint == null || targetPoint == null || targetPoint.getWorldView() != startPoint.getWorldView())
		{
			return null;
		}

		final int[][] flags = collisionFlags(worldView, plane);
		if (flags == null)
		{
			return null;
		}

		final DoorMap doors = scanDoors(worldView, plane, flags.length, flags[0].length);
		final int[] path = buildSegmentedPath(flags, doors,
			startPoint.getSceneX(), startPoint.getSceneY(), targetPoint.getSceneX(), targetPoint.getSceneY());
		if (path.length == 0)
		{
			return null;
		}

		final List<WorldPoint> worldPath = new ArrayList<>(path.length);
		for (int packed : path)
		{
			final LocalPoint localPoint = LocalPoint.fromScene(
				ScenePathfinder.unpackX(packed, flags.length),
				ScenePathfinder.unpackY(packed, flags.length),
				worldView);
			final WorldPoint point = WorldPoint.fromLocal(worldView, localPoint.getX(), localPoint.getY(), plane);
			if (WorldPathfinder.isWilderness(point))
			{
				return null;
			}
			worldPath.add(point);
		}
		return new Route(worldView, plane, path, flags.length, Collections.unmodifiableList(worldPath));
	}

	private static WorldPoint atWorldViewPlane(WorldPoint point, WorldView worldView)
	{
		return new WorldPoint(point.getX(), point.getY(), worldView.getPlane());
	}

	private static int[] buildSegmentedPath(int[][] flags, DoorMap doors,
		int startX, int startY, int targetX, int targetY)
	{
		final int width = flags.length;
		final List<Integer> result = new ArrayList<>();
		final BitSet openedDoors = new BitSet();
		int currentX = startX;
		int currentY = startY;

		for (int remaining = doors.edges.cardinality() + 1; remaining > 0; remaining--)
		{
			final int[] tail = ScenePathfinder.find(flags, openedDoors,
				currentX, currentY, targetX, targetY);
			if (tail.length > 0)
			{
				append(result, tail);
				return result.stream().mapToInt(Integer::intValue).toArray();
			}

			final int[] candidate = ScenePathfinder.find(flags, doors.edges,
				currentX, currentY, targetX, targetY);
			if (candidate.length == 0)
			{
				return new int[0];
			}

			final DoorStep nextDoor = doors.firstDoorOn(candidate, width, 0, openedDoors);
			if (nextDoor == null)
			{
				return new int[0];
			}

			final int from = candidate[nextDoor.pathIndex];
			final int to = candidate[nextDoor.pathIndex + 1];
			final int[] approach = ScenePathfinder.find(flags, openedDoors,
				currentX, currentY, ScenePathfinder.unpackX(from, width), ScenePathfinder.unpackY(from, width));
			if (approach.length == 0)
			{
				return new int[0];
			}
			append(result, approach);
			result.add(to);
			openedDoors.set(nextDoor.edgeKey);
			if (MotherlodeRockfalls.isRockfall(nextDoor.door.object.getId()))
			{
				openedDoors.set(ScenePathfinder.doorTileKey(width, flags[0].length,
					nextDoor.door.sceneX, nextDoor.door.sceneY));
				for (Map.Entry<Integer, Door> edge : doors.doors.entrySet())
				{
					if (edge.getValue().object == nextDoor.door.object) { openedDoors.set(edge.getKey()); }
				}
			}

			final int fromX = ScenePathfinder.unpackX(from, width);
			final int fromY = ScenePathfinder.unpackY(from, width);
			final int toX = ScenePathfinder.unpackX(to, width);
			final int toY = ScenePathfinder.unpackY(to, width);
			final int beyondX = toX + toX - fromX;
			final int beyondY = toY + toY - fromY;
			// ponytail: one straight exit tile models swinging doors; replace with predicted
			// open-state collision only if a door is found that needs more geometry.
			if ((toX != targetX || toY != targetY)
				&& ScenePathfinder.canStep(flags, openedDoors, toX, toY, beyondX, beyondY))
			{
				result.add(ScenePathfinder.pack(beyondX, beyondY, width));
				currentX = beyondX;
				currentY = beyondY;
			}
			else
			{
				currentX = toX;
				currentY = toY;
			}
		}
		return new int[0];
	}

	private static void append(List<Integer> result, int[] path)
	{
		for (int i = result.isEmpty() ? 0 : 1; i < path.length; i++)
		{
			result.add(path[i]);
		}
	}

	private static int findProgress(Route route, WorldPoint current, WorldView worldView,
		int[][] flags, BitSet doorEdges, int currentX, int currentY)
	{
		for (int i = route.progress; i < route.worldPath.size(); i++)
		{
			if (route.worldPath.get(i).equals(current))
			{
				return i;
			}
		}
		for (int i = route.progress; i < route.worldPath.size(); i++)
		{
			final WorldPoint point = route.worldPath.get(i);
			if (point.distanceTo2D(current) <= 1)
			{
				final LocalPoint local = LocalPoint.fromWorld(worldView, point);
				if (local != null && ScenePathfinder.canStep(flags, doorEdges,
					currentX, currentY, local.getSceneX(), local.getSceneY()))
				{
					return i;
				}
			}
		}
		return -1;
	}

	private LocalPoint resolveLocalPoint(WorldView worldView, WorldPoint target, LocalPoint playerPoint)
	{
		final LocalPoint direct = LocalPoint.fromWorld(worldView, target);
		if (direct != null)
		{
			return direct;
		}
		if (!worldView.isInstance())
		{
			return null;
		}

		LocalPoint nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		final Collection<WorldPoint> candidates = WorldPoint.toLocalInstance(worldView, target);
		for (WorldPoint candidate : candidates)
		{
			final LocalPoint localPoint = LocalPoint.fromWorld(worldView, candidate);
			if (localPoint == null)
			{
				continue;
			}

			final int distance = localPoint.distanceTo(playerPoint);
			if (distance < nearestDistance)
			{
				nearest = localPoint;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	private void replacePathSegment(int index, Route replacement)
	{
		if (replacement == null)
		{
			pathSegments = Collections.emptyList();
			return;
		}
		final List<List<WorldPoint>> updated = new ArrayList<>(pathSegments);
		updated.set(index, replacement.worldPath);
		pathSegments = Collections.unmodifiableList(updated);
	}

	private void finish()
	{
		if (request != null && request.nearestBank)
		{
			QuestStepTarget.Target bank = liveBankTarget();
			WorldPoint player = currentLocation();
			if (bank == null || player == null)
			{
				block("nearest-bank-unavailable");
				return;
			}
			if (!QuestDestination.roomApproaches(client, bank, worldPathfinder).contains(player))
			{
				List<WorldPoint> approaches = questDestinations(bank);
				if (approaches.isEmpty() || !walkToAny(approaches))
				{
					block("nearest-bank-unavailable");
				}
				return;
			}
		}
		if (bankTrip != null && !bankTrip.arrived)
		{
			QuestStepTarget.Target bank = liveBankTarget();
			WorldPoint player = currentLocation();
			if (bank == null || player == null)
			{
				failAction("No live bank target is visible. Move closer to a bank, then retry the walk.");
				return;
			}
			if (!QuestDestination.roomApproaches(client, bank, worldPathfinder).contains(player))
			{
				List<WorldPoint> approaches = questDestinations(bank);
				if (approaches.isEmpty())
				{
					failAction("Cannot verify a walkable tile in the bank's room. Retry from a visible bank entrance.");
					return;
				}
				bankTrip.bank = approaches.get(0);
				if (!beginPlanning(request, client.getLocalPlayer(), true)) { block("nearest-bank-unavailable"); }
				return;
			}
			Request retained = request;
			BankTrip trip = bankTrip;
			clear();
			request = retained;
			activeRequest = retained;
			bankTrip = trip;
			bankTrip.arrived = true;
			status = Status.WALKING;
			return;
		}
		debug("arrived destination={} player={} pathCleared=true", getDestination(),
			client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation());
		request = null;
		activeRequest = null;
		route = null;
		clearGlobalRoute();
		globalProgress = 0;
		plannedRoutes = Collections.emptyList();
		plannedTransitions = Collections.emptyList();
		transition = null;
		pendingTransition = null;
		plannedTeleport = null;
		plannedTeleportLanding = null;
		pendingTeleport = null;
		teleportPreparationAt = -1;
		pathSegments = Collections.emptyList();
		nextObstacle = null;
		pendingDoor = null;
		walkClickedAt = -1;
		rejectedTransitions.clear();
		rejectedTeleports.clear();
		rejectedWhistleSlots.retainAll(emptyWhistleSlots);
		status = Status.ARRIVED;
	}

	private void handleTeleport(Request requested, Player player)
	{
		final WorldPoint playerWorld = player.getWorldLocation();
		if (plannedTeleport.isQuetzalWhistle())
		{
			handleWhistleTeleport(requested, player);
			return;
		}
		if (pendingTeleport == null)
		{
			if (net.runelite.client.plugins.microbot.util.input.InputArbiter.isHuman())
			{
				handoffDecision = "teleport-wait-user-input";
				Microbot.status = "Waiting for your input to finish before teleporting.";
				return;
			}
			if (plannedTeleport.usesSpell())
			{
				Widget spell = plannedTeleport.spellWidget();
				if (spell == null || spell.isHidden() || !Rs2UiHelper.isRectangleWithinCanvas(spell.getBounds()))
				{
					if (!Rs2Tab.isCurrentTab(InterfaceTab.MAGIC) && teleportPreparationAt < 0)
					{
						client.runScript(TAB_SWITCH_SCRIPT, 6);
						teleportPreparationAt = client.getTickCount();
					}
					handoffDecision = "teleport-open-spellbook";
					Microbot.status = "Waiting for the teleport spell to be visible.";
					return;
				}
			}
			if (plannedTeleport.isHomeTeleport() && (player.getAnimation() != -1
				|| player.getPoseAnimation() != player.getIdlePoseAnimation()))
			{
				Microbot.status = "Waiting to stop moving before Home Teleport. Cancel walk stops the request.";
				return;
			}
			if (plannedTeleport.needsEquipmentTab()
				&& !Rs2Tab.isCurrentTab(InterfaceTab.EQUIPMENT))
			{
				if (teleportPreparationAt < 0)
				{
					client.runScript(TAB_SWITCH_SCRIPT, EQUIPMENT_TAB_INDEX);
					teleportPreparationAt = client.getTickCount();
					handoffDecision = "teleport-open-equipment";
					return;
				}
				Microbot.status = "Waiting for the Equipment tab. Cancel walk stops the request.";
				return;
			}
			teleportPreparationAt = -1;
			if (!plannedTeleport.isAvailable(playerWorld, plannedTeleportLanding))
			{
				rejectTeleportAndReplan(player, "activation-unavailable");
				return;
			}
			if (!plannedTeleport.activate(playerWorld, plannedTeleportLanding, (entry, bounds) ->
			{
				pendingTeleport = new PendingTeleport(client.getTickCount());
				pendingTeleport.spellEntry = entry;
				invoke(entry, bounds, true);
			}))
			{
				rejectTeleportAndReplan(player, "activation-unavailable");
				return;
			}
			if (pendingTeleport == null) { pendingTeleport = new PendingTeleport(client.getTickCount()); }
			if (plannedTeleport.isHomeTeleport()) { pendingTeleport.homeOrigin = playerWorld; }
			handoffDecision = pendingTeleport.spellEntry == null ? "teleport-dispatched" : "teleport-input-pending";
			debug("teleport input requested teleport={} start={} expected={}", plannedTeleport.name,
				playerWorld, plannedTeleportLanding);
			return;
		}
		if (pendingTeleport.spellEntry != null)
		{
			if (pendingTeleport.spellAction == null)
			{
				if (Microbot.targetMenu != pendingTeleport.spellEntry)
				{
					failAction("The teleport spell click was not accepted. Retry the walk when you are ready.");
				}
				else { Microbot.status = "Waiting for the teleport spell action to be accepted."; }
				return;
			}
			if (pendingTeleport.spellAction.isConsumed())
			{
				failAction("The teleport spell action was blocked. Check your active plugins, then retry the walk.");
				return;
			}
			handoffDecision = "teleport-dispatched";
			Microbot.status = "Waiting for " + plannedTeleport.name + " to finish.";
		}
		if (plannedTeleport.isChronicle() && pendingTeleport.chronicleCharges == 0)
		{
			rejectTeleportAndReplan(player, "chronicle-empty");
			return;
		}

		final boolean active = player.getPoseAnimation() != player.getIdlePoseAnimation()
			|| player.getAnimation() != -1;
		boolean atDestination = playerWorld.getPlane() == plannedTeleportLanding.getPlane()
			&& playerWorld.distanceTo2D(plannedTeleportLanding) <= TELEPORT_LANDING_TOLERANCE;
		if (pendingTeleport.landed(client.getTickCount(), playerWorld, atDestination && !active
			&& client.getVarbitValue(VarbitID.CUTSCENE_STATUS) == 0))
		{
			finishTeleportLanding(requested, player);
			return;
		}
		if (atDestination) { return; }
		if (plannedTeleport.isHomeTeleport())
		{
			if (!LocalTeleportCatalog.homeCombatReady(client)
				|| !playerWorld.equals(pendingTeleport.homeOrigin)
				|| pendingTeleport.homeAnimationStarted && player.getAnimation() == -1)
			{
				rejectTeleportAndReplan(player, "home-teleport-interrupted");
				return;
			}
			pendingTeleport.homeAnimationStarted |= player.getAnimation() != -1;
			Microbot.status = "Waiting for Home Teleport to finish. Cancel walk stops the request.";
			return;
		}
		Microbot.status = "Waiting for the teleport destination. Cancel walk stops the request.";
	}

	void onTeleportMenuAction(net.runelite.api.events.MenuOptionClicked event)
	{
		if (pendingTeleport == null || pendingTeleport.spellEntry == null || request != activeRequest) { return; }
		NewMenuEntry expected = pendingTeleport.spellEntry;
		if (event.getMenuAction() == expected.getType() && event.getParam1() == expected.getParam1()
			&& event.getParam0() == expected.getParam0() && event.getId() == expected.getIdentifier()
			&& expected.getOption().equals(event.getMenuOption()))
		{
			pendingTeleport.spellAction = event;
		}
	}

	void onWhistleInventoryChanged(net.runelite.api.ItemContainer inventory)
	{
		int[] current = inventory == null ? null : new int[56];
		Set<Integer> seen = new HashSet<>();
		boolean duplicate = false;
		if (inventory != null)
		{
			for (int slot = 0; slot < 28; slot++)
			{
				net.runelite.api.Item item = inventory.getItem(slot);
				if (item == null) { continue; }
				boolean whistle = java.util.Arrays.stream(Quetzals.WHISTLES).anyMatch(id -> id == item.getId());
				if (whistle) { duplicate |= !seen.add(item.getId()); }
				if (whistle)
				{
					current[slot * 2] = item.getId();
					current[slot * 2 + 1] = item.getQuantity();
				}
			}
		}
		// Identical whistles cannot be distinguished after an inventory update.
		if (inventory == null || duplicate || !java.util.Arrays.equals(whistleInventory, current))
		{
			invalidateWhistleCharges();
		}
		whistleInventory = current;
	}

	void invalidateWhistleCharges()
	{
		emptyWhistleSlots.clear();
		rejectedWhistleSlots.clear();
		whistleInventoryVersion++;
	}

	boolean rejectAction(String message)
	{
		String reason = ActionFailure.reason(message);
		if (reason == null || request == null || request != activeRequest || !request.walk
			|| status == Status.BLOCKED || status == Status.ARRIVED
			|| (pendingDoor == null && pendingTransition == null && pendingTeleport == null && walkClickedAt < 0
				&& preflightEquipment == null && !preflightRetaliateSent && runIntentAt < 0
				&& (bankTrip == null || !bankTrip.inputPending())))
		{
			return false;
		}
		failAction(reason);
		return true;
	}

	void failAction(String reason)
	{
		actionFailure = reason;
		block("action-rejected");
	}

	void onGameMessage(String message)
	{
		if (plannedTeleport != null && plannedTeleport.isChronicle() && pendingTeleport != null)
		{
			int chronicleCharges = LocalTeleportCatalog.chronicleCharges(message);
			if (chronicleCharges >= 0) { pendingTeleport.chronicleCharges = chronicleCharges; }
		}
		int charges = Quetzals.charges(message);
		if (charges > 0)
		{
			emptyWhistleSlots.clear();
			rejectedWhistleSlots.clear();
		}
		if (request != null && request == activeRequest && status == Status.WALKING
			&& client.getGameState() == GameState.LOGGED_IN
			&& plannedTeleport != null && plannedTeleport.isQuetzalWhistle() && pendingTeleport != null
			&& pendingTeleport.whistleCheckSent && pendingTeleport.whistleCharges < 0
			&& pendingTeleport.whistleSignalledAt < 0 && pendingTeleport.whistleSlot >= 0
			&& pendingTeleport.whistleInventoryVersion == whistleInventoryVersion)
		{
			if (charges >= 0) { pendingTeleport.whistleCharges = charges; }
			if (charges == 0)
			{
				emptyWhistleSlots.add(pendingTeleport.whistleSlot);
				rejectedWhistleSlots.add(pendingTeleport.whistleSlot);
			}
		}
	}

	void invalidateLandingStability()
	{
		inputReadyTick=-1;
		if (pendingTransition != null)
		{
			pendingTransition.quetzalLandedAt = -1;
			if (pendingTransition.charter != null) { pendingTransition.charter.landed(0, false); }
			if (pendingTransition.spirit != null) { pendingTransition.spirit.landed(0, false); }
		}
		if (pendingTeleport != null)
		{
			pendingTeleport.landed(0, null, false);
		}
	}

	void invalidateTeleportPreparation()
	{
		if (pendingTeleport != null && pendingTeleport.homeOrigin != null)
		{
			pendingTeleport = null;
		}
	}

	private void handleWhistleTeleport(Request requested, Player player)
	{
		int tick = client.getTickCount();
		if (pendingTeleport == null) { pendingTeleport = new PendingTeleport(tick); }
		PendingTeleport pending = pendingTeleport;
		if (pending.whistleSlot < 0)
		{
			if (Quetzals.menuOpen(client, Quetzals.WHISTLE_INTERFACE))
			{
				Widget close = client.getWidget(Quetzals.WHISTLE_INTERFACE, 18);
				if (!pending.whistleCloseSent && FairyRings.permitted(client, close)
					&& "Close".equals(close.getActions()[0]))
				{
					pending.whistleCloseSent = true;
					invoke(new NewMenuEntry().option("Close").target("").identifier(1).type(MenuAction.CC_OP)
						.param0(-1).param1(close.getId()).itemId(-1), close.getBounds(), true);
				}
				handoffDecision = "whistle-wait-map-close";
				return;
			}
			Widget inventoryWidget = client.getWidget(InterfaceID.Inventory.ITEMS);
			if (inventoryWidget == null || inventoryWidget.isHidden())
			{
				if (!pending.whistleTabSent)
				{
					pending.whistleTabSent = true;
					client.runScript(TAB_SWITCH_SCRIPT, 3);
				}
				handoffDecision = "whistle-wait-inventory";
				return;
			}
			ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
			if (inventory == null || inventory.getItems() == null)
			{
				handoffDecision = "whistle-wait-inventory";
				return;
			}
			if (whistleInventory == null) { onWhistleInventoryChanged(inventory); }
			if (!plannedTeleport.isAvailable(player.getWorldLocation(), plannedTeleportLanding))
			{
				rejectTeleportAndReplan(player, "whistle-unavailable");
				return;
			}
			int slot = Quetzals.whistleSlot(client, rejectedWhistleSlots);
			if (slot < 0) { rejectTeleportAndReplan(player, "whistle-missing"); return; }
			pending.whistleSlot = slot;
			pending.whistleInventoryVersion = whistleInventoryVersion;
			pending.whistleId = inventory.getItem(slot).getId();
			if (pending.whistleId == ItemID.HG_QUETZALWHISTLE_PERFECTED_INFINITE)
			{
				pending.whistleCharges = Integer.MAX_VALUE;
			}
		}
		if (pending.whistleSignalledAt < 0)
		{
			ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
			if (inventory == null) { handoffDecision = "whistle-wait-inventory"; return; }
			Item item = inventory.getItem(pending.whistleSlot);
			if (pending.whistleInventoryVersion != whistleInventoryVersion
				|| item == null || item.getId() != pending.whistleId)
			{
				if (pending.whistleCheckSent)
				{
					failAction("Cannot identify the checked quetzal whistle after its inventory or charge state changed. Cancel and retry the walk.");
				}
				else { pendingTeleport = null; }
				return;
			}
			if (pending.whistleCharges == 0)
			{
				rejectedWhistleSlots.add(pending.whistleSlot);
				pendingTeleport = null;
				handoffDecision = "whistle-skip-empty";
				return;
			}
			if (pending.whistleCharges < 0)
			{
				if (!pending.whistleCheckSent) { pending.whistleCheckSent = invokeWhistle("Check"); }
				handoffDecision = "whistle-check-charges";
				Microbot.status = "Waiting for the quetzal whistle charge check. Cancel walk stops the request.";
				return;
			}
			if (!plannedTeleport.isAvailable(player.getWorldLocation(), plannedTeleportLanding))
			{
				rejectTeleportAndReplan(player, "whistle-unavailable");
				return;
			}
			if (invokeWhistle("Signal")) { pending.whistleSignalledAt = tick; }
			handoffDecision = "whistle-open-map";
			return;
		}
		if (Quetzals.menuOpen(client, Quetzals.WHISTLE_INTERFACE))
		{
			pending.landed(0, null, false);
			if (!quetzals.whistleAvailable(client, plannedTeleportLanding))
			{
				block("quetzal-access-lost");
				return;
			}
			if (pending.whistleSelectedAt >= 0) { return; }
			Widget destination = quetzals.destinationWidget(client, plannedTeleportLanding, Quetzals.WHISTLE_INTERFACE);
			if (destination != null)
			{
				pending.whistleSelectedAt = tick;
				invoke(new NewMenuEntry().option(destination.getActions()[0]).target("")
					.identifier(1).type(MenuAction.CC_OP).param0(destination.getIndex())
					.param1(destination.getId()).itemId(-1), destination.getBounds(), true);
				handoffDecision = "whistle-select-destination";
			}
			return;
		}
		WorldPoint position = player.getWorldLocation();
		boolean ready = pending.whistleSelectedAt >= 0 && !Quetzals.flightActive(client)
			&& client.getVarbitValue(VarbitID.CUTSCENE_STATUS) == 0
			&& player.getAnimation() == -1 && player.getPoseAnimation() == player.getIdlePoseAnimation()
			&& position.distanceTo(plannedTeleportLanding) <= TELEPORT_LANDING_TOLERANCE;
		if (pending.landed(tick, position, ready)) { finishTeleportLanding(requested, player); }
		else { handoffDecision = "whistle-wait-landing"; }
	}

	private boolean invokeWhistle(String action)
	{
		ItemComposition definition = client.getItemDefinition(pendingTeleport.whistleId);
		if (definition == null || definition.getName() == null) { return false; }
		return interactWithQuestInventory(new QuestItemUse.InventoryAction(
			new QuestItemUse(pendingTeleport.whistleId, pendingTeleport.whistleSlot, definition.getName()), null, action));
	}

	private boolean finishTeleportLanding(Request requested, Player player)
	{
		final Teleport completed = plannedTeleport;
		final WorldPoint completedLanding = plannedTeleportLanding;
		final WorldPoint playerWorld = player.getWorldLocation();
		plannedTeleport = null;
		plannedTeleportLanding = null;
		pendingTeleport = null;
		teleportPreparationAt = -1;
		route = null;
		clearGlobalRoute();
		globalProgress = 0;
		plannedRoutes = Collections.emptyList();
		plannedTransitions = Collections.emptyList();
		pathSegments = Collections.emptyList();
		nextObstacle = null;
		markObstacleCleared();
		handoffDecision = "teleport-landed-planning";
		debug("teleport landed teleport={} expected={} actual={}", completed.name,
			completedLanding, playerWorld);
		if (!beginPlanning(requested, player, false))
		{
			block("teleport-landing-route-unavailable");
		}
		return true;
	}

	private void rejectTeleportAndReplan(Player player, String reason)
	{
		final Teleport rejected = plannedTeleport;
		if (rejected != null)
		{
			rejectedTeleports.add(rejected);
		}
		debug("teleport rejected teleport={} reason={} rejectedCount={} destination={}",
			rejected == null ? "none" : rejected.name, reason, rejectedTeleports.size(), getDestination());
		plannedTeleport = null;
		plannedTeleportLanding = null;
		pendingTeleport = null;
		teleportPreparationAt = -1;
		route = null;
		clearGlobalRoute();
		globalProgress = 0;
		plannedRoutes = Collections.emptyList();
		plannedTransitions = Collections.emptyList();
		pathSegments = Collections.emptyList();
		nextObstacle = null;
		handoffDecision = "replan-rejected-teleport";
		if (activeRequest == null || !beginPlanning(activeRequest, player, true))
		{
			block("rejected-teleport-route-unavailable");
			return;
		}
	}

	private void rejectTransitionAndReplan(Player player)
	{
		final Transport rejected = transition;
		transitionRoomRoute = false;
		rejectedTransitions.add(rejected);
		if (rejected.isAlKharidGate())
		{
			transports.stream().filter(Transport::isAlKharidGate).forEach(rejectedTransitions::add);
		}
		debug("transition-rejected step={} rejectedCount={} destination={}",
			transportName(rejected), rejectedTransitions.size(), getDestination());
		route = null;
		clearGlobalRoute();
		globalProgress = 0;
		plannedRoutes = Collections.emptyList();
		plannedTransitions = Collections.emptyList();
		transition = null;
		pendingTransition = null;
		transitionAttempts = 0;
		pendingDoor = null;
		walkClickedAt = -1;
		walkTarget = null;
		pathSegments = Collections.emptyList();
		nextObstacle = null;
		handoffDecision = "replan-rejected-transition";
		if (activeRequest == null || !beginPlanning(activeRequest, player, true))
		{
			block("rejected-transport-route-unavailable");
			return;
		}
	}

	private boolean prepareTransitionRoomRoute(WorldView worldView, WorldPoint player)
	{
		QuestStepTarget.Target target;
		if (transition.isNpc())
		{
			NPC npc = findNpc(transition);
			if (npc == null || npc.getWorldLocation() == null)
			{
				if (!transitionRoomRoute) { return false; }
				failAction("The transport NPC moved or disappeared. Retry the walk when it is visible.");
				return true;
			}
			WorldPoint point = npc.getWorldLocation();
			target = new QuestStepTarget.Target(null, point, true, false, point, npc, null);
		}
		else
		{
			TileObject object = findTileObject(worldView, transition);
			if (object == null || object.getWorldLocation() == null)
			{
				if (!transitionRoomRoute) { return false; }
				failAction("The transport object changed or disappeared. Retry the walk when it is visible.");
				return true;
			}
			WorldPoint point = object.getWorldLocation();
			target = new QuestStepTarget.Target(null, point, false, true, null, null, object);
		}
		if (!transition.isNpc() && atTransportApproach(target.liveObject)) { return false; }
		List<WorldPoint> approaches = QuestDestination.roomApproaches(client, target, worldPathfinder);
		if (approaches.contains(player)) { return false; }
		if (transitionRoomRoute)
		{
			failAction("The transport target is no longer in the verified room. Retry the walk from its current location.");
			return true;
		}
		Route closest = null;
		int considered = 0;
		for (WorldPoint approach : approaches)
		{
			// ponytail: bound repeated local path searches; raise only for a recorded larger target room.
			if (++considered > MAX_ROOM_APPROACH_CANDIDATES) { break; }
			closest = buildRoute(worldView, player, approach, player.getPlane());
			if (closest != null) { break; }
		}
		if (closest == null)
		{
			failAction("Cannot reach a walkable tile in the transport target's room. Choose another route.");
			return true;
		}
		route = closest;
		pathSegments = Collections.singletonList(closest.worldPath);
		nextObstacle = new ObstacleMarker(closest.worldPath.get(closest.worldPath.size() - 1),
			transition.action, transition.target);
		transitionRoomRoute = true;
		walkClickedAt = -1;
		walkTarget = null;
		pendingDoor = null;
		handoffDecision = "transport-room-approach";
		status = Status.WALKING;
		return true;
	}

	private boolean clickTransition(WorldView worldView, Transport step, boolean immediateHandoff)
	{
		startingCharter = null;
		if (SpiritTrees.isTree(step) && !spiritTrees.available(client, config, step)) { return false; }
		if (CharterShips.isCharter(step) && !charterShips.available(client, step))
		{
			actionFailure = charterShips.unavailableReason(client, step);
			return false;
		}
		if ((step.isPrimio() || step.isAlKharidGate()) && !isTransportAvailable(step)) { return false; }
		if (Quetzals.isQuetzal(step) && !quetzals.available(client, step)) { return false; }
		if (FairyRings.isRing(step))
		{
			if (!isTransportAllowed(step) || !fairyRings.available(client, step)
				|| FairyRings.missingStaff(client) != null) { return false; }
			fairyRings.reset();
		}
		if (step.isNpc())
		{
			return clickNpcTransition(worldView, step, immediateHandoff);
		}
		final TileObject object = findTileObject(worldView, step);
		final ObjectAction action = object == null ? null : transitionAction(object, step);
		if (object == null || action == null)
		{
			debug("transition unavailable step={} objectFound={} actionFound={}", transportName(step),
				object != null, action != null);
			return false;
		}
		try { clickObject(object, worldView, action, Rs2UiHelper.getObjectClickbox(object), immediateHandoff); }
		catch (IllegalStateException exception) { failAction(exception.getMessage()); return false; }
		transitionRoomRoute = false;
		pendingTransition = createPendingTransition(action);
		return true;
	}

	private void clickObject(TileObject object, WorldView worldView, ObjectAction action, Rectangle bounds, boolean immediateHandoff)
	{
		if (!sameRoomAs(object) && !atTransportApproach(object))
		{
			throw new IllegalStateException("Cannot verify a walkable tile in the object's room. Move into its room, then retry.");
		}
		final Point scene = object instanceof GameObject
			? ((GameObject) object).getSceneMinLocation()
			: new Point(object.getLocalLocation().getSceneX(), object.getLocalLocation().getSceneY());
		final NewMenuEntry entry = new NewMenuEntry()
			.param0(scene.getX())
			.param1(scene.getY())
			.opcode(action.menuAction.getId())
			.identifier(object.getId())
			.itemId(-1)
			.option(action.action)
			.target(action.target)
			.setWorldViewId(worldView.getId())
			.gameObject(object);
		invoke(entry, bounds, immediateHandoff);
	}

	private boolean sameRoomAs(TileObject object)
	{
		WorldPoint player = currentLocation();
		WorldPoint point = object == null ? null : object.getWorldLocation();
		return player != null && point != null && QuestDestination.roomApproaches(client,
			new QuestStepTarget.Target(null, point, false, true, null, null, object), worldPathfinder).contains(player);
	}

	private boolean atTransportApproach(TileObject object)
	{
		WorldPoint player = currentLocation();
		WorldPoint point = object == null ? null : object.getWorldLocation();
		return transition != null && point != null && player != null
			&& object.getId() == transition.objectId && player.equals(transition.approach)
			&& player.distanceTo(point) <= 3;
	}

	private boolean sameRoomAs(NPC npc)
	{
		WorldPoint player = currentLocation();
		WorldPoint point = npc == null ? null : npc.getWorldLocation();
		return player != null && point != null && QuestDestination.roomApproaches(client,
			new QuestStepTarget.Target(null, point, true, false, point, npc, null), worldPathfinder).contains(player);
	}

	boolean bankTripActive()
	{
		return bankTrip != null;
	}

	Object questRequestToken()
	{
		return questRequest;
	}

	boolean interactWithQuestWidget(Object step, QuestWidget choice)
	{
		QuestWidget live = QuestWidget.resolve(client, step);
		if (live == null || live.widget != choice.widget || !live.key().equals(choice.key())
			|| client.isWidgetSelected() || live.widget.getBounds().isEmpty()
			|| !Rs2UiHelper.isRectangleWithinCanvas(live.widget.getBounds())) { return false; }
		invoke(new NewMenuEntry().option(live.action).target(live.widget.getName()).identifier(live.operation)
			.type(live.operation > 5 ? MenuAction.CC_OP_LOW_PRIORITY : MenuAction.CC_OP)
			.param0(live.widget.getIndex()).param1(live.widget.getId()).itemId(live.widget.getItemId()),
			live.widget.getBounds(), true);
		return true;
	}

	boolean selectQuestItem(QuestItemUse item)
	{
		Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
		Widget slot = inventory == null ? null : inventory.getChild(item.slot);
		if (client.isWidgetSelected() || slot == null || inventory.isHidden() || slot.isHidden()
			|| slot.getItemId() != item.id || !Rs2UiHelper.isRectangleWithinCanvas(slot.getBounds())) { return false; }
		invoke(new NewMenuEntry().option("Use").target(item.name).identifier(0).type(MenuAction.WIDGET_TARGET)
			.param0(item.slot).param1(inventory.getId()).itemId(item.id), slot.getBounds(), true);
		return true;
	}

	boolean interactWithQuestInventory(QuestItemUse.InventoryAction action)
	{
		QuestItemUse item = action.target == null ? action.source : action.target;
		net.runelite.api.ItemContainer container = client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
		net.runelite.api.Item live = container == null ? null : container.getItem(item.slot);
		Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
		Widget slot = inventory == null ? null : inventory.getChild(item.slot);
		if (live == null || live.getId() != item.id || slot == null || inventory.isHidden() || slot.isHidden()
			|| slot.getItemId() != item.id || !Rs2UiHelper.isRectangleWithinCanvas(slot.getBounds())) { return false; }
		int identifier = 0;
		MenuAction opcode;
		String option = action.action;
		if (action.target != null)
		{
			if (!action.source.isSelected(client)) { return false; }
			opcode = MenuAction.WIDGET_TARGET_ON_WIDGET;
		}
		else
		{
			if (client.isWidgetSelected()) { return false; }
			String[] actions = slot.getActions();
			if (actions == null) { return false; }
			for (int i = 0; i < actions.length; i++)
			{
				if (actions[i] != null && net.runelite.client.util.Text.removeTags(actions[i]).equalsIgnoreCase(action.action))
				{
					if (identifier != 0) { return false; }
					identifier = i + 1;
					option = net.runelite.client.util.Text.removeTags(actions[i]);
				}
			}
			if (identifier == 0) { return false; }
			opcode = identifier > 5 ? MenuAction.CC_OP_LOW_PRIORITY : MenuAction.CC_OP;
		}
		invoke(new NewMenuEntry().option(option).target(item.name).identifier(identifier).type(opcode)
			.param0(item.slot).param1(inventory.getId()).itemId(item.id), slot.getBounds(), true);
		return true;
	}

	boolean useQuestItem(QuestItemUse item, QuestStepTarget.Target target)
	{
		if (!item.isSelected(client)) { return false; }
		if (target.liveNpc != null)
		{
			NPC npc = target.liveNpc;
			Rectangle bounds = npc.getConvexHull() == null ? null : npc.getConvexHull().getBounds();
			if (npc.getWorldView() != client.getTopLevelWorldView() || npc.getId() < 0 || bounds == null
				|| bounds.isEmpty() || !Rs2UiHelper.isRectangleWithinCanvas(bounds)) { return false; }
			clickNpc(npc, npc.getWorldView(), new ObjectAction("Use", MenuAction.WIDGET_TARGET_ON_NPC, npc.getName()), bounds, true);
			return true;
		}
		TileObject object = target.liveObject;
		Rectangle bounds = object == null ? null : Rs2UiHelper.getObjectClickbox(object);
		if (object == null || object.getWorldView() != client.getTopLevelWorldView() || bounds == null
			|| bounds.isEmpty() || !Rs2UiHelper.isRectangleWithinCanvas(bounds)) { return false; }
		ObjectComposition composition = currentComposition(object.getId());
		if (composition == null) { return false; }
		clickObject(object, object.getWorldView(), new ObjectAction("Use", MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, composition.getName()), bounds, true);
		return true;
	}

	String interactWithQuestObject(TileObject object, Object step)
	{
		if (object == null || object.getId() < 0 || object.getWorldView() != client.getTopLevelWorldView()) { return null; }
		ObjectComposition composition = currentComposition(object.getId());
		if (composition == null || composition.getActions() == null) { return null; }
		String[] actions = composition.getActions().clone();
		for (int i = 0; i < Math.min(5, actions.length); i++)
		{
			String override = object.getOpOverride(i);
			if (override != null) { actions[i] = override; }
		}
		int index = QuestStepTarget.questActionIndex(step, actions);
		if (index < 0) { return null; }
		Rectangle bounds = Rs2UiHelper.getObjectClickbox(object);
		if (bounds == null || bounds.isEmpty() || !Rs2UiHelper.isRectangleWithinCanvas(bounds)) { return null; }
		clickObject(object, object.getWorldView(), new ObjectAction(actions[index], menuAction(index), composition.getName()), bounds, true);
		return actions[index];
	}

	boolean interactWithPrayerObject(TileObject object, String action)
	{
		if (object == null || action == null || object.getId() < 0
			|| object.getWorldView() != client.getTopLevelWorldView()) { return false; }
		ObjectComposition composition = currentComposition(object.getId());
		if (composition == null || composition.getActions() == null) { return false; }
		String[] actions = composition.getActions().clone();
		for (int i = 0; i < Math.min(5, actions.length); i++)
		{
			if (object.getOpOverride(i) != null) { actions[i] = object.getOpOverride(i); }
		}
		for (int i = 0; i < Math.min(5, actions.length); i++)
		{
			if (actions[i] != null && actions[i].equalsIgnoreCase(action))
			{
				Rectangle bounds = Rs2UiHelper.getObjectClickbox(object);
				if (bounds == null || bounds.isEmpty() || !Rs2UiHelper.isRectangleWithinCanvas(bounds)) { return false; }
				clickObject(object, object.getWorldView(), new ObjectAction(actions[i], menuAction(i), composition.getName()), bounds, true);
				return true;
			}
		}
		return false;
	}

	boolean questObjectActionAvailable(TileObject object, Object step)
	{
		if (object == null || object.getId() < 0 || object.getWorldView() != client.getTopLevelWorldView()) { return false; }
		ObjectComposition composition = currentComposition(object.getId());
		if (composition == null || composition.getActions() == null) { return false; }
		String[] actions = composition.getActions().clone();
		for (int i = 0; i < Math.min(5, actions.length); i++)
		{
			String override = object.getOpOverride(i);
			if (override != null) { actions[i] = override; }
		}
		return QuestStepTarget.questNamedActionIndex(step, actions) >= 0;
	}

	private boolean clickNpcTransition(WorldView worldView, Transport step, boolean immediateHandoff)
	{
		final NPC npc = findNpc(step);
		final ObjectAction action = npc == null ? null : transitionAction(npc, step);
		if (npc == null || action == null)
		{
			debug("transition unavailable step={} npcFound={} actionFound={}", transportName(step),
				npc != null, action != null);
			return false;
		}
		debug("transition-click step={} liveNpcId={} liveNpc={} action={} attempt={} direct={}",
			transportName(step), npc.getId(), npc.getWorldLocation(), action.action,
			transitionAttempts + 1, immediateHandoff);
		if (CharterShips.isCharter(step))
		{
			startingCharter = new CharterShips.Transit(client.getTickCount(), CharterShips.coins(client),
				CharterShips.fare(client, charterShips.journey(step)), action.action.startsWith("Charter-to "));
		}
		try { clickNpc(npc, worldView, action, Rs2UiHelper.getActorClickbox(npc), immediateHandoff); }
		catch (IllegalStateException exception) { failAction(exception.getMessage()); return false; }
		transitionRoomRoute = false;
		pendingTransition = createPendingTransition(action);
		return true;
	}

	private QuestStepTarget.Target liveBankTarget()
	{
		WorldPoint player = currentLocation();
		if (player == null) { return null; }
		TileObject booth = net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject.findBank();
		if (booth == null) { booth = net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject.findGrandExchangeBooth(); }
		if (booth != null && booth.getWorldLocation() != null
			&& booth.getWorldView() == client.getTopLevelWorldView()
			&& booth.getWorldLocation().distanceTo(player) <= 8)
		{
			WorldPoint point = booth.getWorldLocation();
			return new QuestStepTarget.Target(null, point, false, true, null, null, booth);
		}
		NPC banker = net.runelite.client.plugins.microbot.util.npc.Rs2Npc.getBankerNPC();
		if (banker == null || banker.getWorldLocation() == null
			|| banker.getWorldView() != client.getTopLevelWorldView()
			|| banker.getWorldLocation().distanceTo(player) > 8) { return null; }
		WorldPoint point = banker.getWorldLocation();
		return new QuestStepTarget.Target(null, point, true, false, point, banker, null);
	}

	public boolean openRouteBank()
	{
		TileObject booth = net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject.findBank();
		if (booth == null) { booth = net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject.findGrandExchangeBooth(); }
		if (booth != null && booth.getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation()) <= 8)
		{
			ObjectComposition composition = currentComposition(booth.getId());
			String[] actions = composition == null ? null : composition.getActions();
			if (actions != null)
			{
				for (int i = 0; i < actions.length; i++)
				{
					if ("Bank".equalsIgnoreCase(actions[i])
						|| "Bank chest".equalsIgnoreCase(composition.getName()) && "Use".equalsIgnoreCase(actions[i]))
					{
						Rectangle bounds = Rs2UiHelper.getObjectClickbox(booth);
						if (bounds == null || !Rs2UiHelper.isRectangleWithinCanvas(bounds)) { break; }
						clickObject(booth, booth.getWorldView(), new ObjectAction(actions[i], menuAction(i), composition.getName()), bounds, true);
						return true;
					}
				}
			}
		}
		NPC npc = net.runelite.client.plugins.microbot.util.npc.Rs2Npc.getBankerNPC();
		if (npc == null || npc.getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation()) > 8) { return false; }
		NPCComposition composition = npc.getTransformedComposition();
		String[] actions = composition == null ? null : composition.getActions();
		if (actions == null) { return false; }
		for (int i = 0; i < actions.length; i++)
		{
			if ("Bank".equalsIgnoreCase(actions[i]))
			{
				Rectangle bounds = npc.getConvexHull() == null ? null : npc.getConvexHull().getBounds();
				if (bounds == null || !Rs2UiHelper.isRectangleWithinCanvas(bounds)) { return false; }
				clickNpc(npc, npc.getWorldView(), new ObjectAction("Bank", npcMenuAction(i), composition.getName()), bounds, true);
				return true;
			}
		}
		return false;
	}

	public boolean bankWidgetAction(Widget widget, String action)
	{
		if (widget == null || widget.isHidden() || widget.getActions() == null
			|| !Rs2UiHelper.isRectangleWithinCanvas(widget.getBounds())) { return false; }
		String[] actions = widget.getActions();
		for (int i = 0; i < actions.length; i++)
		{
			if (action.equalsIgnoreCase(actions[i]))
			{
				invoke(new NewMenuEntry().option(action).target("").identifier(i + 1)
					.type(MenuAction.CC_OP)
					.param0(widget.getIndex()).param1(widget.getId()).itemId(widget.getItemId()), widget.getBounds(), true);
				return true;
			}
		}
		return false;
	}

	public boolean submitBankAmount(int amount)
	{
		Widget prompt = client.getWidget(162, 43);
		String entered = client.getVarcStrValue(net.runelite.api.gameval.VarClientID.MESLAYERINPUT);
		if (amount <= 0 || prompt == null || prompt.isHidden()
			|| !"Enter amount:".equalsIgnoreCase(prompt.getText()) || entered != null && !entered.isEmpty()) { return false; }
		java.awt.Canvas canvas = client.getCanvas();
		if (canvas == null) { return false; }
		for (char digit : Integer.toString(amount).toCharArray())
		{
			net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard.keyPress(digit);
		}
		net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard.keyPress(java.awt.event.KeyEvent.VK_ENTER);
		return true;
	}

	String interactWithQuestNpc(NPC npc, Object step)
	{
		if (npc == null || npc.getId() < 0 || npc.getWorldView() != client.getTopLevelWorldView())
		{
			return null;
		}
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null) { return null; }
		String[] actions = composition.getActions();
		int index = QuestStepTarget.questActionIndex(step, actions);
		if (index < 0) { return null; }
		Rectangle bounds = npc.getConvexHull() == null ? null : npc.getConvexHull().getBounds();
		if (bounds == null || bounds.isEmpty() || !Rs2UiHelper.isRectangleWithinCanvas(bounds))
		{
			return null;
		}
		clickNpc(npc, npc.getWorldView(), new ObjectAction(actions[index], npcMenuAction(index), composition.getName()), bounds, true);
		return actions[index];
	}

	private void clickNpc(NPC npc, WorldView worldView, ObjectAction action, Rectangle bounds, boolean immediateHandoff)
	{
		if (!sameRoomAs(npc))
		{
			throw new IllegalStateException("Cannot verify a walkable tile in the NPC's room. Move into its room, then retry.");
		}
		final NewMenuEntry entry = new NewMenuEntry()
			.param0(0)
			.param1(0)
			.opcode(action.menuAction.getId())
			.identifier(npc.getIndex())
			.itemId(-1)
			.option(action.action)
			.target(action.target)
			.setWorldViewId(worldView.getId())
			.actor(npc);
		invoke(entry, bounds, immediateHandoff);
	}

	private PendingTransition createPendingTransition(ObjectAction action)
	{
		Player player = client.getLocalPlayer();
		PendingTransition pending = new PendingTransition(client.getTickCount(), player.getWorldLocation(),
			player.getPoseAnimation() != player.getIdlePoseAnimation() || player.getAnimation() != -1);
		pending.action = action.action;
		pending.charter = startingCharter;
		startingCharter = null;
		if (SpiritTrees.isTree(transition)) { pending.spirit = new SpiritTrees.Transit(); }
		return pending;
	}

	private void handleSpiritTreeTransition()
	{
		try
		{
			if (pendingTransition.spirit.selectedAt >= 0)
			{
				handoffDecision = "spirit-tree-wait-landing";
				return;
			}
			Widget destination = spiritTrees.destinationWidget(client, transition);
			if (destination != null && Rs2UiHelper.isRectangleWithinCanvas(destination.getBounds()))
			{
				pendingTransition.spirit.selectedAt = client.getTickCount();
				invoke(new NewMenuEntry().option("Continue").target("").identifier(0)
					.type(MenuAction.WIDGET_CONTINUE).param0(destination.getIndex())
					.param1(destination.getId()).itemId(-1), destination.getBounds(), true);
			}
			handoffDecision = "spirit-tree-select";
		}
		catch (SpiritTrees.DestinationUnavailable ex)
		{
			failAction(ex.getMessage() + " Update Player-grown spirit trees in the Efficient Walker config, then retry.");
		}
		catch (IllegalStateException ex) { failAction(ex.getMessage()); }
	}

	private void handleCharterTransition()
	{
		try
		{
			CharterShips.Transit pending = pendingTransition.charter;
			if (pending.paid)
			{
				handoffDecision = "charter-wait-landing";
				Microbot.status = "Charter fare paid. Waiting for verified arrival; cancel stops the walk.";
				return;
			}
			if (pending.confirmedAt >= 0)
			{
				handoffDecision = "charter-wait-payment";
				Microbot.status = "Waiting for the confirmed charter payment. Cancel walk stops the route.";
				return;
			}
			CharterShips.Journey journey = charterShips.journey(transition);
			if (CharterShips.coins(client) < 0 || journey != null && CharterShips.fare(client, journey) < 0)
			{
				handoffDecision = "charter-wait-supplies";
				return;
			}
			if (!charterShips.available(client, transition))
			{
				throw new IllegalStateException(charterShips.unavailableReason(client, transition));
			}
			if (CharterShips.fare(client, journey) != pending.fare)
			{
				throw new IllegalStateException("Charter access or fare changed. Check your quest unlocks, equipment and Coins, then retry.");
			}
			if (pending.selectedAt >= 0)
			{
				if (QuestDialogue.visible(client))
				{
					Widget confirmation = charterShips.confirmationWidget(client, transition, pending.fare);
					if (confirmation == null || !Rs2UiHelper.isRectangleWithinCanvas(confirmation.getBounds()))
					{
						handoffDecision = "charter-wait-confirmation";
						Microbot.status = "Waiting for the exact charter destination and fare confirmation. Cancel walk stops the route.";
						return;
					}
					if (Rs2Dialogue.clickOption("Yes.", true))
					{
						pending.confirmedAt = client.getTickCount();
					}
				}
				handoffDecision = "charter-wait-payment";
				return;
			}
			Widget destination = charterShips.destinationWidget(client, transition);
			if (destination != null && Rs2UiHelper.isRectangleWithinCanvas(destination.getBounds()))
			{
				pending.selectedAt = client.getTickCount();
				invoke(new NewMenuEntry().option(journey.destination.name).target("")
					.identifier(1).type(MenuAction.CC_OP).param0(destination.getIndex())
					.param1(destination.getId()).itemId(-1), destination.getBounds(), true);
			}
			handoffDecision = "charter-select";
		}
		catch (IllegalStateException ex) { failAction(ex.getMessage()); }
	}

	private boolean handleQuetzalTransition()
	{
		if (!quetzals.available(client, transition))
		{
			block("quetzal-access-lost");
			return true;
		}
		boolean menuOpen = Quetzals.menuOpen(client);
		if (!menuOpen && pendingTransition.quetzalSelectedAt < 0) { return false; }
		if (pendingTransition.quetzalSelectedAt >= 0)
		{
			handoffDecision = "quetzal-wait-landing";
			Microbot.status = "Waiting for the selected quetzal flight. Cancel walk stops the route.";
			return true;
		}
		Widget destination = quetzals.destinationWidget(client, transition);
		if (destination != null)
		{
			pendingTransition.quetzalSelectedAt = client.getTickCount();
			invoke(new NewMenuEntry().option(destination.getActions()[0]).target("")
				.identifier(1).type(MenuAction.CC_OP).param0(destination.getIndex())
				.param1(destination.getId()).itemId(-1), destination.getBounds(), true);
		}
		handoffDecision = "quetzal-select";
		return true;
	}

	private boolean resumeAfterPreparatoryTransition(WorldView worldView, WorldPoint playerWorld)
	{
		if (transition.approach.getPlane() != transition.landing.getPlane() || globalPath.isEmpty())
		{
			return false;
		}
		final int landingIndex = indexOf(globalPath, transition.landing, globalProgress);
		if (landingIndex < 0)
		{
			return false;
		}
		final int handoffIndex = furthestLoadedIndex(worldView, landingIndex);
		final Route openedRoute = buildRoute(worldView, playerWorld,
			globalPath.get(handoffIndex), playerWorld.getPlane());
		if (openedRoute == null)
		{
			return false;
		}

		final Transport prepared = transition;
		final List<WorldPoint> remaining = new ArrayList<>(openedRoute.worldPath);
		remaining.addAll(globalPath.subList(handoffIndex + 1, globalPath.size()));
		globalPath = Collections.unmodifiableList(remaining);
		globalProgress = 0;
		route = openedRoute;
		plannedRoutes = Collections.singletonList(openedRoute);
		plannedTransitions = Collections.emptyList();
		final int nextIndex = openedRoute.worldPath.size();
		transition = nextIndex < globalPath.size()
			? transportBetween(globalPath.get(nextIndex - 1), globalPath.get(nextIndex)) : null;
		pendingTransition = null;
		transitionAttempts = 0;
		pendingDoor = null;
		walkClickedAt = -1;
		walkTarget = null;
		pathSegments = splitByPlane(globalPath);
		markObstacleCleared();
		refreshNextObstacle(worldView, route, 0);
		status = Status.WALKING;
		debug("transition-prepared step={} player={} resumedPathLength={}",
			transportName(prepared), playerWorld, globalPath.size());
		return true;
	}

	private void refreshNextObstacle(WorldView worldView, Route currentRoute, int startIndex)
	{
		final int[][] flags = currentRoute == null ? null : collisionFlags(worldView, currentRoute.plane);
		if (flags == null)
		{
			nextObstacle = transition == null ? null
				: new ObstacleMarker(transition.approach, transition.action, transition.target);
			return;
		}
		refreshNextObstacle(currentRoute,
			scanDoors(worldView, currentRoute.plane, flags.length, flags[0].length), startIndex);
	}

	private void refreshNextObstacle(Route currentRoute, DoorMap doors, int startIndex)
	{
		final DoorStep door = doors.firstDoorOn(currentRoute.path, currentRoute.width, startIndex);
		if (door != null)
		{
			nextObstacle = new ObstacleMarker(currentRoute.worldPath.get(door.pathIndex),
				door.door.action, door.door.target);
			return;
		}
		nextObstacle = transition == null ? null
			: new ObstacleMarker(transition.approach, transition.action, transition.target);
	}

	private TileObject findTileObject(WorldView worldView, Transport step)
	{
		final Scene scene = worldView.getScene();
		final Tile[][][] tiles = scene == null ? null : scene.getTiles();
		final int plane = step.approach.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return null;
		}
		final LocalPoint approach = LocalPoint.fromWorld(worldView, atWorldViewPlane(step.approach, worldView));
		if (approach == null)
		{
			return null;
		}

		final int minX = Math.max(0, approach.getSceneX() - INTERACTION_QUEUE_STEPS);
		final int maxX = Math.min(tiles[plane].length - 1, approach.getSceneX() + INTERACTION_QUEUE_STEPS);
		for (int exactPass = 1; exactPass >= 0; exactPass--)
		{
			TileObject nearest = null;
			for (int x = minX; x <= maxX; x++)
			{
				final int minY = Math.max(0, approach.getSceneY() - INTERACTION_QUEUE_STEPS);
				final int maxY = Math.min(tiles[plane][x].length - 1, approach.getSceneY() + INTERACTION_QUEUE_STEPS);
				for (int y = minY; y <= maxY; y++)
				{
					final TileObject object = matchingObject(tiles[plane][x][y], step, exactPass == 1);
					if (object != null && (nearest == null || isCloserToApproach(step.approach,
						object.getWorldLocation(), nearest.getWorldLocation())))
					{
						nearest = object;
					}
				}
			}
			if (nearest != null)
			{
				return nearest;
			}
		}
		return null;
	}

	private boolean hasTransitionTarget(WorldView worldView, Transport step)
	{
		return step.isNpc() ? findNpc(step) != null : findTileObject(worldView, step) != null;
	}

	private NPC findNpc(Transport step)
	{
		NPC nearest = null;
		for (NPC npc : client.getNpcs())
		{
			final WorldPoint location = npc.getWorldLocation();
			if (!matchesTransportNpc(step, npc) || location == null
				|| location.getPlane() != step.approach.getPlane()
				|| location.distanceTo2D(step.approach) > INTERACTION_QUEUE_STEPS
				|| transitionAction(npc, step) == null)
			{
				continue;
			}
			if (nearest == null || isCloserToApproach(step.approach,
				location, nearest.getWorldLocation()))
			{
				nearest = npc;
			}
		}
		return nearest;
	}

	static boolean matchesTransportNpc(Transport step, NPC npc)
	{
		if (step.matchesNpc(npc.getId())) { return true; }
		NPCComposition composition = npc.getTransformedComposition();
		return composition != null && step.matchesNpc(composition.getId());
	}

	static boolean isCloserToApproach(WorldPoint approach, WorldPoint candidate, WorldPoint current)
	{
		return candidate.distanceTo2D(approach) < current.distanceTo2D(approach);
	}

	static boolean transitionRetryReady(int tick, int lastProgressAt)
	{
		return tick - lastProgressAt > TRANSITION_RETRY_TICKS;
	}

	static boolean transitionProgressObserved(WorldPoint previousPosition, WorldPoint position,
		boolean previousActive, boolean active)
	{
		return !position.equals(previousPosition) || active != previousActive;
	}

	static boolean unexpectedTransitionPlaneIsStable(int tick, int lastProgressAt, boolean progressObserved)
	{
		return !progressObserved && transitionRetryReady(tick, lastProgressAt);
	}

	static boolean transitionSceneMayBeLoading(int tick, int transitionLandedAt)
	{
		return transitionLandedAt >= 0
			&& tick - transitionLandedAt <= TRANSITION_RETRY_TICKS * MAX_TRANSITION_ATTEMPTS;
	}

	private TileObject matchingObject(Tile tile, Transport step, boolean exactId)
	{
		if (tile == null)
		{
			return null;
		}
		for (GameObject object : tile.getGameObjects())
		{
			if (matchesObject(object, step, exactId))
			{
				return object;
			}
		}
		final WallObject wall = tile.getWallObject();
		if (matchesObject(wall, step, exactId))
		{
			return wall;
		}
		final DecorativeObject decorative = tile.getDecorativeObject();
		if (matchesObject(decorative, step, exactId))
		{
			return decorative;
		}
		final GroundObject ground = tile.getGroundObject();
		return matchesObject(ground, step, exactId) ? ground : null;
	}

	private boolean matchesObject(TileObject object, Transport step, boolean exactId)
	{
		if (object == null || transitionAction(object, step) == null)
		{
			return false;
		}
		if (step.isAlKharidGate() && !LocalTransportCatalog.AL_KHARID_GATE_IDS.contains(object.getId()))
		{
			return false;
		}
		if (object.getId() == step.objectId)
		{
			return true;
		}
		final ObjectComposition composition = currentComposition(object.getId());
		return composition != null && (composition.getId() == step.objectId
			|| !exactId && step.target.equalsIgnoreCase(composition.getName()));
	}

	private int[][] collisionFlags(WorldView worldView, int plane)
	{
		final CollisionData[] maps = worldView.getCollisionMaps();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
		{
			return null;
		}
		int[][] flags = maps[plane].getFlags();
		if (worldView.isInstance() || plane != 0 || worldView.getBaseX() > 3771
			|| worldView.getBaseX() + flags.length < 3719 || worldView.getBaseY() > 5690
			|| worldView.getBaseY() + flags[0].length < 5638 || MotherlodeRockfalls.hasPickaxe(client)) { return flags; }
		int[][] restricted = flags.clone();
		for (WorldPoint point : MotherlodeRockfalls.TILES)
		{
			int x = point.getX() - worldView.getBaseX(), y = point.getY() - worldView.getBaseY();
			if (x < 0 || y < 0 || x >= flags.length || y >= flags[x].length) { continue; }
			if (restricted[x] == flags[x]) { restricted[x] = flags[x].clone(); }
			restricted[x][y] |= CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		}
		return restricted;
	}

	private DoorMap scanDoors(WorldView worldView, int plane, int width, int height)
	{
		final DoorMap doors = new DoorMap(width, height);
		final Scene scene = worldView.getScene();
		final Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return doors;
		}

		for (int x = 0; x < Math.min(width, tiles[plane].length); x++)
		{
			for (int y = 0; y < Math.min(height, tiles[plane][x].length); y++)
			{
				final Tile tile = tiles[plane][x][y];
				final WallObject wall = tile == null ? null : tile.getWallObject();
				if (wall != null
					&& !gatedBoundaryObjectIds.contains(wall.getId()))
				{
					final ObjectAction action = openAction(wall);
					if (action != null)
					{
						final Door door = new Door(wall, action.action, action.menuAction, action.target, x, y);
						ScenePathfinder.visitOrientationEdges(width, height, x, y, wall.getOrientationA(),
							key -> doors.add(key, door));
						ScenePathfinder.visitOrientationEdges(width, height, x, y, wall.getOrientationB(),
							key -> doors.add(key, door));
					}
				}

				if (tile == null)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					if (object != null && MotherlodeRockfalls.isRockfall(object.getId())
						&& MotherlodeRockfalls.hasPickaxe(client))
					{
						ObjectComposition composition = currentComposition(object.getId());
						if (composition != null && composition.getActions() != null
							&& Arrays.asList(composition.getActions()).contains("Mine"))
						{
							int index = Arrays.asList(composition.getActions()).indexOf("Mine");
							Door rock = new Door(object, "Mine", menuAction(index), composition.getName(), x, y);
							doors.allowBlockedTile(x, y);
							int[][] collision = worldView.getCollisionMaps()[plane].getFlags();
							for (int[] delta : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}})
							{
								int nx = x + delta[0], ny = y + delta[1];
								if (ScenePathfinder.canClearObjectStep(collision, x, y, nx, ny)
									&& ScenePathfinder.canClearObjectStep(collision, nx, ny, x, y))
								{
									doors.add(ScenePathfinder.edgeKey(width, height, x, y, nx, ny), rock);
								}
							}
						}
						continue;
					}
					final int orientation = object == null ? 0 : gameObjectDoorOrientation(object.getConfig());
					if (orientation == 0
						|| gatedBoundaryObjectIds.contains(object.getId()))
					{
						continue;
					}
					final ObjectAction action = openAction(object);
					if (action != null)
					{
						final Door door = new Door(object, action.action, action.menuAction, action.target, x, y);
						doors.allowBlockedTile(x, y);
						ScenePathfinder.visitOrientationEdges(width, height, x, y, orientation,
							key -> doors.add(key, door));
					}
				}
			}
		}
		return doors;
	}

	static int gameObjectDoorOrientation(int config)
	{
		if ((config & 31) != 9)
		{
			return 0;
		}
		return 1 | 2 | 4 | 8;
	}

	private Set<Long> liveWalkEdges(WorldView worldView, int plane)
	{
		if (worldView.isInstance())
		{
			return Collections.emptySet();
		}
		final int[][] flags = collisionFlags(worldView, plane);
		if (flags == null || flags.length == 0 || flags[0] == null)
		{
			return Collections.emptySet();
		}

		final DoorMap doors = scanDoors(worldView, plane, flags.length, flags[0].length);
		final Set<Long> edges = new HashSet<>();
		final int baseX = worldView.getBaseX();
		final int baseY = worldView.getBaseY();
		for (int x = 0; x < flags.length; x++)
		{
			for (int y = 0; y < flags[x].length; y++)
			{
				addLiveWalkEdge(flags, doors.edges, edges, baseX, baseY, plane,
					x, y, x + 1, y);
				addLiveWalkEdge(flags, doors.edges, edges, baseX, baseY, plane,
					x, y, x, y + 1);
			}
		}
		return edges;
	}

	private void addLiveWalkEdge(int[][] flags, BitSet doorEdges, Set<Long> edges,
		int baseX, int baseY, int plane, int fromX, int fromY, int toX, int toY)
	{
		if (!ScenePathfinder.canStep(flags, doorEdges, fromX, fromY, toX, toY))
		{
			return;
		}
		final int worldFromX = baseX + fromX;
		final int worldFromY = baseY + fromY;
		final int worldToX = baseX + toX;
		final int worldToY = baseY + toY;
		if (!worldPathfinder.canStep(worldFromX, worldFromY, plane, worldToX, worldToY))
		{
			edges.add(WorldPathfinder.edgeKey(worldFromX, worldFromY, plane, worldToX, worldToY));
		}
	}

	static boolean isInViewport(Point point, int x, int y, int width, int height)
	{
		return point != null && point.getX() >= x && point.getX() < x + width
			&& point.getY() >= y && point.getY() < y + height;
	}

	static boolean isCanvasFallbackStep(int pathIndex)
	{
		return pathIndex <= WALK_QUEUE_STEPS;
	}

	static boolean isRouteClickCandidate(int routeSteps, int directSteps)
	{
		return routeSteps <= directSteps + WALK_QUEUE_STEPS;
	}

	static boolean isAcceptedTransitionLanding(WorldPoint approach, WorldPoint landing, WorldPoint actual,
		boolean effectObserved)
	{
		return effectObserved && actual.getPlane() == landing.getPlane()
			&& (approach.getPlane() != landing.getPlane() || actual.distanceTo2D(landing) <= 1);
	}

	static PlanningFailure planningFailureForLoadedTarget(int[][] flags, int x, int y)
	{
		return flags != null && x >= 0 && x < flags.length && y >= 0 && y < flags[x].length
			&& (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0
			? PlanningFailure.TARGET_NOT_STANDABLE : PlanningFailure.ROUTE_UNSUPPORTED;
	}

	private ObjectAction openAction(TileObject object)
	{
		final ObjectComposition composition = currentComposition(object.getId());
		if (composition == null || composition.getActions() == null
			|| "Magic door".equalsIgnoreCase(composition.getName())
			|| object.getId() == net.runelite.api.gameval.ObjectID.ZANARISMAGICDOOR
			|| object.getId() == net.runelite.api.gameval.ObjectID.ZANARISMARKETDOOR)
		{
			return null;
		}

		final String[] actions = composition.getActions();
		for (int i = 0; i < actions.length; i++)
		{
			final String action = actions[i];
			if ("Open".equalsIgnoreCase(action) || "Pass".equalsIgnoreCase(action))
			{
				return new ObjectAction(action, menuAction(i), composition.getName());
			}
		}
		return null;
	}

	private boolean canUseAlKharidGate()
	{
		if (Quest.PRINCE_ALI_RESCUE.getState(client) == QuestState.FINISHED) { return true; }
		final ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null) { return false; }
		for (Item item : inventory.getItems())
		{
			if (item.getId() == ItemID.COINS && item.getQuantity() >= 10) { return true; }
		}
		return false;
	}

	private String alKharidGateAction()
	{
		return Quest.PRINCE_ALI_RESCUE.getState(client) == QuestState.FINISHED ? "Open" : "Pay-toll(10gp)";
	}

	static boolean isAcceptedTransportLanding(Transport step, WorldPoint actual, boolean effectObserved)
	{
		if (CharterShips.isCharter(step)) { return step.landing.equals(actual); }
		if (SpiritTrees.isTree(step)) { return step.landing.equals(actual); }
		return isAcceptedTransitionLanding(step.approach, step.landing, actual, effectObserved)
			&& (!step.isAlKharidGate() || (step.landing.getX() > step.approach.getX()
				? actual.getX() >= step.landing.getX() : actual.getX() <= step.landing.getX()));
	}

	private ObjectAction transitionAction(TileObject object, Transport step)
	{
		final ObjectComposition composition = currentComposition(object.getId());
		if (composition == null || composition.getActions() == null)
		{
			return null;
		}
		final String[] actions = composition.getActions().clone();
		for (int i = 0; i < actions.length; i++)
		{
			final String override = object.getOpOverride(i);
			actions[i] = override == null ? actions[i] : override;
		}
		final int action = step.isAlKharidGate() ? Arrays.asList(actions).indexOf(alKharidGateAction())
			: transitionActionIndex(step.action, step.target, composition.getName(), actions);
		return action < 0 ? null
			: new ObjectAction(actions[action], menuAction(action), composition.getName());
	}

	private ObjectAction transitionAction(NPC npc, Transport step)
	{
		final NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			return null;
		}
		final int action = CharterShips.isCharter(step) ? charterShips.actionIndex(step, composition)
			: transitionActionIndex(step.action, step.target, composition.getName(), composition.getActions());
		return action < 0 ? null
			: new ObjectAction(composition.getActions()[action], npcMenuAction(action), composition.getName());
	}

	static int transitionActionIndex(String intendedAction, String intendedTarget,
		String liveTarget, String[] actions)
	{
		if (actions == null)
		{
			return -1;
		}
		for (int i = 0; i < actions.length; i++)
		{
			if (sameAction(intendedAction, actions[i])
				|| ("Enter".equalsIgnoreCase(intendedAction) && "Use".equalsIgnoreCase(actions[i]))
				|| ("Use".equalsIgnoreCase(intendedAction) && "Enter".equalsIgnoreCase(actions[i])))
			{
				return i;
			}
		}
		if (!intendedTarget.equalsIgnoreCase(liveTarget))
		{
			return -1;
		}
		for (int i = 0; i < actions.length; i++)
		{
			if ("Open".equalsIgnoreCase(actions[i]) || "Pass".equalsIgnoreCase(actions[i]))
			{
				return i;
			}
		}
		return -1;
	}

	private static boolean sameAction(String expected, String actual)
	{
		return actual != null && expected.replace('-', ' ').equalsIgnoreCase(actual.replace('-', ' '));
	}

	private ObjectComposition currentComposition(int objectId)
	{
		ObjectComposition composition = client.getObjectDefinition(objectId);
		if (composition != null && composition.getImpostorIds() != null)
		{
			composition = composition.getImpostor();
		}
		return composition;
	}

	private void clickDoor(Door door, boolean immediateHandoff)
	{
		final NewMenuEntry entry = new NewMenuEntry()
			.param0(door.sceneX)
			.param1(door.sceneY)
			.opcode(door.menuAction.getId())
			.identifier(door.object.getId())
			.itemId(-1)
			.option(door.action)
			.target(door.target)
			.setWorldViewId(door.object.getWorldView().getId())
			.gameObject(door.object);
		debug("door-click location={} objectId={} action={}", door.object.getWorldLocation(),
			door.object.getId(), door.action);
		invoke(entry, Rs2UiHelper.getObjectClickbox(door.object), immediateHandoff);
	}

	private boolean reprioritizeBlockingDoor(WorldView worldView, WorldPoint playerWorld)
	{
		if (route == null)
		{
			return false;
		}

		final int[][] flags = collisionFlags(worldView, route.plane);
		if (flags == null)
		{
			return false;
		}
		final DoorMap doors = scanDoors(worldView, route.plane, flags.length, flags[0].length);
		final DoorStep doorStep = doors.firstDoorOn(route.path, route.width, route.progress);
		if (doorStep == null || doorStep.pathIndex - route.progress > INTERACTION_QUEUE_STEPS)
		{
			return false;
		}

			debug("transition-yield-door transition={} door={} player={}", transportName(transition),
				doorStep.door.object.getWorldLocation(), playerWorld);
		pendingTransition = null;
		transitionAttempts = 0;
		walkClickedAt = -1;
		walkTarget = null;
		handoffDecision = "transition-yield-door";
		queueDoor(doorStep, route.path, route.width, true);
		return true;
	}

	private void queueDoor(DoorStep doorStep, int[] path, int width, boolean immediateHandoff)
	{
		clickDoor(doorStep.door, immediateHandoff);
		pendingDoor = new PendingDoor(
			doorStep.edgeKey,
			StrongholdOfSecurity.isGate(doorStep.door.object.getId()),
			doorStep.door.object.getId() == 3506 || doorStep.door.object.getId() == 3507,
			ScenePathfinder.unpackX(path[doorStep.pathIndex], width),
			ScenePathfinder.unpackY(path[doorStep.pathIndex], width),
			ScenePathfinder.unpackX(path[doorStep.pathIndex + 1], width),
			ScenePathfinder.unpackY(path[doorStep.pathIndex + 1], width));
		pendingDoor.rockfall = MotherlodeRockfalls.isRockfall(doorStep.door.object.getId());
		status = Status.WALKING;
	}

	private boolean handleSwampConfirmation()
	{
		if (pendingDoor == null || !pendingDoor.swampGate) { return false; }
		Widget enter = client.getWidget(580, 17);
		if (enter == null || enter.isHidden()) { return false; }
		if (!"Enter the swamp.".equals(net.runelite.client.util.Text.removeTags(enter.getText()))
			|| enter.getActions() == null || enter.getActions().length == 0
			|| !"Yes".equals(enter.getActions()[0])
			|| !Rs2UiHelper.isRectangleWithinCanvas(enter.getBounds()))
		{
			failAction("Cannot identify the swamp entry confirmation. Select Enter the swamp manually, then retry the walk.");
			return true;
		}
		if (pendingDoor.swampConfirmed)
		{
			return true;
		}
		invoke(new NewMenuEntry().option("Yes").target("").identifier(1).type(MenuAction.CC_OP)
			.param0(-1).param1(enter.getId()).itemId(-1), enter.getBounds(), false);
		pendingDoor.swampConfirmed = true;
		handoffDecision = "swamp-entry-confirmation";
		return true;
	}

	private boolean handleStrongholdDialogue()
	{
		if (!pendingDoor.strongholdGate)
		{
			return false;
		}
		if (!Rs2Dialogue.hasContinue() && !Rs2Dialogue.hasSelectAnOption()) { return false; }
		String page = QuestDialogue.pageFingerprint(client);
		if (page == null || page.equals(pendingDoor.sentDialoguePage))
		{
			handoffDecision = "stronghold-wait-dialogue";
			return true;
		}
		if (Rs2Dialogue.hasContinue())
		{
			Rs2Dialogue.clickContinue();
			pendingDoor.sentDialoguePage = page;
			handoffDecision = "stronghold-dialogue-continue";
			return true;
		}
		if (!Rs2Dialogue.hasSelectAnOption())
		{
			return false;
		}

		final List<Widget> options = Rs2Dialogue.getDialogueOptions();
		if (options == null || options.isEmpty()) { return true; }
		final String answer = StrongholdOfSecurity.correctAnswer(options.stream()
			.map(Widget::getText)
			.collect(Collectors.toList()));
		if (answer == null)
		{
			block("stronghold-answer-unavailable");
			return true;
		}
		if (!Rs2Dialogue.clickOption(answer, true))
		{
			handoffDecision = "stronghold-wait-answer";
			return true;
		}
		pendingDoor.sentDialoguePage = page;
		handoffDecision = "stronghold-dialogue-answer";
		return true;
	}

	private void block(String reason)
	{
		walkHandoffPending = false;
		obstacleHandoffPending = false;
		handoffDecision = "blocked-" + reason;
		debug("blocked reason={} player={} destination={} transition={} attempts={}", reason,
			client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation(),
			getDestination(), transportName(transition), transitionAttempts);
		boolean report = status != Status.BLOCKED;
		WalkerError notice = errorNotice(blockedMessage(reason));
		PlanningFailure failure = planningFailure;
		clear();
		planningFailure = failure;
		status = Status.BLOCKED;
		failControl(notice.text());
		if (report)
		{
			Microbot.status = notice.text();
			client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
				notice.chat(), null);
		}
	}

	private String blockedMessage(String reason)
	{
		if (CharterShips.isCharter(transition) && actionFailure != null) { return actionFailure; }
		if ("action-rejected".equals(reason)) { return actionFailure; }
		if ("plan-unavailable".equals(reason))
		{
			WorldPoint start = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation();
			WorldPoint target = getDestination();
			if (start != null && target != null)
				return EfficientWalkerPlugin.planningFailureMessage(planningFailure, start, target);
		}
		return ActionFailure.blockedMessage(reason, getDestination(), transition == null ? null : transition.target);
	}

	private void markObstacleCleared()
	{
		walkHandoffPending = true;
		obstacleHandoffPending = true;
	}

	private boolean takeWalkHandoff()
	{
		final boolean pending = walkHandoffPending;
		walkHandoffPending = false;
		return pending;
	}

	private boolean takeObstacleHandoff()
	{
		final boolean pending = obstacleHandoffPending;
		obstacleHandoffPending = false;
		return pending;
	}

	private void debug(String message, Object... arguments)
	{
		if (config.enableDebugging())
		{
			log.info("[EfficientWalker] " + message, arguments);
		}
	}

	private static String transportName(Transport step)
	{
		return step == null ? "none" : step.action + " " + step.target + " "
			+ step.approach + " -> " + step.landing + " id=" + step.objectId;
	}

	private static long elapsedMillis(long started)
	{
		return (System.nanoTime() - started) / 1_000_000;
	}

	private static MenuAction menuAction(int index)
	{
		if (index < 0 || index >= OBJECT_ACTIONS.length)
		{
			throw new IllegalArgumentException("Unsupported object action index: " + index);
		}
		return OBJECT_ACTIONS[index];
	}

	private static MenuAction npcMenuAction(int index)
	{
		if (index < 0 || index >= NPC_ACTIONS.length)
		{
			throw new IllegalArgumentException("Unsupported NPC action index: " + index);
		}
		return NPC_ACTIONS[index];
	}

	private static final class DoorMap
	{
		private final int width;
		private final int height;
		private final BitSet edges;
		private final Map<Integer, Door> doors = new HashMap<>();

		private DoorMap(int width, int height)
		{
			this.width = width;
			this.height = height;
			this.edges = new BitSet(width * height * 2);
		}

		private void add(int edgeKey, Door door)
		{
			edges.set(edgeKey);
			doors.putIfAbsent(edgeKey, door);
		}

		private void allowBlockedTile(int x, int y)
		{
			edges.set(ScenePathfinder.doorTileKey(width, height, x, y));
		}

		private DoorStep firstDoorOn(int[] path, int pathWidth, int startIndex)
		{
			return firstDoorOn(path, pathWidth, startIndex, new BitSet());
		}

		private DoorStep firstDoorOn(int[] path, int pathWidth, int startIndex, BitSet ignoredEdges)
		{
			for (int i = startIndex; i < path.length - 1; i++)
			{
				final int fromX = ScenePathfinder.unpackX(path[i], pathWidth);
				final int fromY = ScenePathfinder.unpackY(path[i], pathWidth);
				final int toX = ScenePathfinder.unpackX(path[i + 1], pathWidth);
				final int toY = ScenePathfinder.unpackY(path[i + 1], pathWidth);
				final int dx = toX - fromX;
				final int dy = toY - fromY;
				if (dx != 0 && dy != 0)
				{
					DoorStep step = doorStep(i, fromX, fromY, fromX + dx, fromY, ignoredEdges);
					if (step == null)
					{
						step = doorStep(i, fromX, fromY, fromX, fromY + dy, ignoredEdges);
					}
					if (step == null)
					{
						step = doorStep(i, fromX + dx, fromY, toX, toY, ignoredEdges);
					}
					if (step == null)
					{
						step = doorStep(i, fromX, fromY + dy, toX, toY, ignoredEdges);
					}
					if (step != null)
					{
						return step;
					}
				}
				else
				{
					final DoorStep step = doorStep(i, fromX, fromY, toX, toY, ignoredEdges);
					if (step != null)
					{
						return step;
					}
				}
			}
			return null;
		}

		private DoorStep doorStep(int pathIndex, int fromX, int fromY, int toX, int toY, BitSet ignoredEdges)
		{
			final int key = ScenePathfinder.edgeKey(width, height, fromX, fromY, toX, toY);
			final Door door = doors.get(key);
			return door == null || ignoredEdges.get(key) ? null : new DoorStep(pathIndex, key, door);
		}
	}

	private static final class Request
	{
		private volatile WorldPoint destination;
		private WorldPoint start;
		private final WorldPoint target;
		private final boolean walk;
		private final boolean nearestBank;
		private boolean bankConsidered;
		private boolean escort;
		private final List<WorldPoint> destinations;

		private Request(WorldPoint destination, boolean walk, boolean nearestBank, WorldPoint start)
		{
			this(destination, walk, nearestBank, start, null);
		}

		private Request(WorldPoint destination, boolean walk, boolean nearestBank, WorldPoint start, List<WorldPoint> destinations)
		{
			this.destination = destination;
			this.target = destination;
			this.start = start;
			this.walk = walk;
			this.nearestBank = nearestBank;
			this.destinations = destinations;
		}
	}

	static final class ObstacleMarker
	{
		final WorldPoint approach;
		final String action;
		final String target;

		private ObstacleMarker(WorldPoint approach, String action, String target)
		{
			this.approach = approach;
			this.action = action;
			this.target = target;
		}
	}

	private static final class Route
	{
		private final WorldView worldView;
		private final int plane;
		private final int[] path;
		private final int width;
		private final List<WorldPoint> worldPath;
		private int progress;

		private Route(WorldView worldView, int plane, int[] path, int width, List<WorldPoint> worldPath)
		{
			this.worldView = worldView;
			this.plane = plane;
			this.path = path;
			this.width = width;
			this.worldPath = worldPath;
		}
	}

	private static final class TransportPlan
	{
		private final List<Route> routes;
		private final List<Transport> transitions;

		private TransportPlan(List<Route> routes, List<Transport> transitions)
		{
			this.routes = Collections.unmodifiableList(routes);
			this.transitions = Collections.unmodifiableList(new ArrayList<>(transitions));
		}
	}

	private static final class TeleportRoute
	{
		private final Teleport teleport;
		private final WorldPoint landing;
		private final WorldPathfinder.SearchResult result;
		private final int cost;

		private TeleportRoute(Teleport teleport, WorldPoint landing,
			WorldPathfinder.SearchResult result, int cost)
		{
			this.teleport = teleport;
			this.landing = landing;
			this.result = result;
			this.cost = cost;
		}
	}

	private static final class TeleportLanding
	{
		private final Teleport teleport;
		private final WorldPoint landing;

		private TeleportLanding(Teleport teleport, WorldPoint landing)
		{
			this.teleport = teleport;
			this.landing = landing;
		}
	}

	private static final class PlanningSnapshot
	{
		private final Request request;
		private final WorldPoint start;
		private final int worldViewId;
		private final int baseX;
		private final int baseY;
		private final boolean instance;
		private final Route loadedRoute;
		private final PlanningFailure loadedFailure;
		private final Set<Long> liveWalkEdges;
		private final Set<Transport> availableTransports;
		private final List<TeleportLanding> availableTeleports;
		private final Set<Transport> rejectedTransitions;
		private final List<WorldPoint> destinations;
		private final boolean considerTeleports;
		private final long started;
		private final Int2ByteOpenHashMap wilderness = new Int2ByteOpenHashMap();
		private int world;
		private int routeSettings;
		private List<BankTrip.Option> bankOptions = Collections.emptyList();
		private List<WorldPoint> banks = Collections.emptyList();
		private boolean running;
		private Map<Transport, Integer> transportFares = Collections.emptyMap();
		private int coins;
		private int bankCoins;
		private Set<BankTrip.Option> bankCoinOptions = Collections.emptySet();
		private Set<Transport> bankAvailableTransports = Collections.emptySet();
		private Map<Transport, Map<Integer, Integer>> bankTransportSupplies = Collections.emptyMap();
		private Map<Integer, Integer> bankStock = Collections.emptyMap();
		private Map<Integer, Integer> inventoryQuantities = Collections.emptyMap();
		private Set<Integer> stackableItems = Collections.emptySet();
		private int inventoryOccupied;

		private PlanningSnapshot(Request request, WorldPoint start, int worldViewId, int baseX, int baseY,
			boolean instance, Route loadedRoute, PlanningFailure loadedFailure, Set<Long> liveWalkEdges,
			Set<Transport> availableTransports, List<TeleportLanding> availableTeleports,
			Set<Transport> rejectedTransitions, List<WorldPoint> destinations,
			boolean considerTeleports, long started)
		{
			this.request = request;
			this.start = start;
			this.worldViewId = worldViewId;
			this.baseX = baseX;
			this.baseY = baseY;
			this.instance = instance;
			this.loadedRoute = loadedRoute;
			this.loadedFailure = loadedFailure;
			this.liveWalkEdges = Collections.unmodifiableSet(new HashSet<>(liveWalkEdges));
			this.availableTransports = Collections.unmodifiableSet(new HashSet<>(availableTransports));
			this.bankAvailableTransports = this.availableTransports;
			this.availableTeleports = Collections.unmodifiableList(new ArrayList<>(availableTeleports));
			this.rejectedTransitions = Collections.unmodifiableSet(new HashSet<>(rejectedTransitions));
			this.destinations = Collections.unmodifiableList(new ArrayList<>(destinations));
			this.considerTeleports = considerTeleports;
			this.started = started;
		}
	}

	private static final class InventoryState
	{
		private final int occupied;
		private final Map<Integer, Integer> quantities;

		private InventoryState(int occupied, Map<Integer, Integer> quantities)
		{
			this.occupied = occupied;
			this.quantities = Collections.unmodifiableMap(new HashMap<>(quantities));
		}
	}

	private static final class PlanningResult
	{
		private final WorldPathfinder.SearchResult worldRoute;
		private final TeleportRoute teleportRoute;
		private BankTrip bankTrip;
		private List<WorldPoint> bankPreviewPath = Collections.emptyList();

		private PlanningResult(WorldPathfinder.SearchResult worldRoute, TeleportRoute teleportRoute)
		{
			this.worldRoute = worldRoute;
			this.teleportRoute = teleportRoute;
		}
	}

	private static final class PlanNode
	{
		private final WorldPoint position;
		private final List<Route> routes;
		private final List<Transport> transitions;
		private final int length;
		private final int coinsSpent;

		private PlanNode(WorldPoint position, List<Route> routes, List<Transport> transitions, int length, int coinsSpent)
		{
			this.position = position;
			this.routes = routes;
			this.transitions = transitions;
			this.length = length;
			this.coinsSpent = coinsSpent;
		}
	}

	private static final class Door
	{
		private final TileObject object;
		private final String action;
		private final MenuAction menuAction;
		private final String target;
		private final int sceneX;
		private final int sceneY;

		private Door(TileObject object, String action, MenuAction menuAction, String target, int sceneX, int sceneY)
		{
			this.object = object;
			this.action = action;
			this.menuAction = menuAction;
			this.target = target;
			this.sceneX = sceneX;
			this.sceneY = sceneY;
		}
	}

	private static final class ObjectAction
	{
		private final String action;
		private final MenuAction menuAction;
		private final String target;

		private ObjectAction(String action, MenuAction menuAction, String target)
		{
			this.action = action;
			this.menuAction = menuAction;
			this.target = target;
		}
	}

	private static final class DoorStep
	{
		private final int pathIndex;
		private final int edgeKey;
		private final Door door;

		private DoorStep(int pathIndex, int edgeKey, Door door)
		{
			this.pathIndex = pathIndex;
			this.edgeKey = edgeKey;
			this.door = door;
		}
	}

	private static final class PendingDoor
	{
		private final int edgeKey;
		private final boolean strongholdGate;
		private final boolean swampGate;
		private boolean swampConfirmed;
		private String sentDialoguePage;
		private boolean rockfall;
		private boolean rockfallActive;
		private final int fromX;
		private final int fromY;
		private final int toX;
		private final int toY;

		private PendingDoor(int edgeKey, boolean strongholdGate, boolean swampGate,
			int fromX, int fromY, int toX, int toY)
		{
			this.edgeKey = edgeKey;
			this.strongholdGate = strongholdGate;
			this.swampGate = swampGate;
			this.fromX = fromX;
			this.fromY = fromY;
			this.toX = toX;
			this.toY = toY;
		}
	}

	private static final class PendingTransition
	{
		private String action;
		private CharterShips.Transit charter;
		private SpiritTrees.Transit spirit;
		private final int clickedAt;
		private int lastProgressAt;
		private WorldPoint lastPosition;
		private boolean lastActive;
		private boolean effectObserved;
		private int quetzalSelectedAt = -1;
		private int quetzalLandedAt = -1;

		private PendingTransition(int clickedAt, WorldPoint position, boolean active)
		{
			this.clickedAt = clickedAt;
			this.lastProgressAt = clickedAt;
			this.lastPosition = position;
			this.lastActive = active;
		}

		private boolean observeProgress(int tick, WorldPoint position, boolean active)
		{
			if (transitionProgressObserved(lastPosition, position, lastActive, active))
			{
				lastPosition = position;
				lastActive = active;
				lastProgressAt = tick;
				effectObserved = true;
				return true;
			}
			return false;
		}

		private boolean stableQuetzalLanding(int tick, boolean ready, boolean progress)
		{
			if (!ready || progress) { quetzalLandedAt = -1; }
			if (!ready || quetzalSelectedAt < 0) { return false; }
			if (quetzalLandedAt < 0) { quetzalLandedAt = tick; }
			return tick > quetzalLandedAt;
		}
	}

	private static final class PendingTeleport
	{
		private NewMenuEntry spellEntry;
		private net.runelite.api.events.MenuOptionClicked spellAction;
		private WorldPoint homeOrigin;
		private boolean homeAnimationStarted;
		private int chronicleCharges = -1;
		private final int clickedAt;
		private boolean whistleCloseSent;
		private boolean whistleTabSent;
		private boolean whistleCheckSent;
		private int whistleSlot = -1;
		private int whistleId = -1;
		private int whistleCharges = -1;
		private int whistleInventoryVersion;
		private int whistleSignalledAt = -1;
		private int whistleSelectedAt = -1;
		private int landedAt = -1;
		private WorldPoint landingPosition;

		private boolean landed(int tick, WorldPoint position, boolean ready)
		{
			if (!ready)
			{
				landedAt = -1;
				landingPosition = null;
				return false;
			}
			if (!position.equals(landingPosition))
			{
				landingPosition = position;
				landedAt = tick;
				return false;
			}
			return tick > landedAt;
		}

		private PendingTeleport(int clickedAt)
		{
			this.clickedAt = clickedAt;
		}
	}
}
