package net.runelite.client.plugins.microbot.draugen;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;

/** Finds and fights Draugen with tick-local decisions and Walker-owned movement. */
@PluginDescriptor(
	name = "Draugen Encounter",
	description = "Runs the prepared Draugen encounter with local tick decisions",
	tags = {"combat", "quest", "draugen"},
	enabledByDefault = false,
	version = "1.0.0")
public final class DraugenPlugin extends Plugin
{
	private static final String WALKER = "net.runelite.client.plugins.microbot.efficientwalker.EfficientWalkerPlugin";
	private static final String GUARDIAN = "net.runelite.client.plugins.microbot.guardian.GuardianPlugin";
	private static final int DRAUGEN = 3922;
	private static final int BUTTERFLY = 3923;
	private static final int UNCHARGED = 3697;
	private static final int CHARGED = 3696;
	private static final int ADAMANT_SCIMITAR = 1331;

	@Inject private Client client;
	@Inject private PluginManager pluginManager;

	private Phase phase = Phase.IDLE;
	private String status = "Stopped";
	private String error;
	private WorldPoint candidateTile;
	private NPC locateButterfly;
	private NPC combatTarget;
	private NPC pendingAttack;
	private WorldPoint walkTarget;
	private Object walker;
	private Method walkTo;
	private Method walkerStatus;
	private Object walkerPlugin;
	private Method walkerControlToken;
	private Method walkerCancelToken;
	private Object ownWalkerPlugin;
	private Object ownRouteToken;
	private Method ownWalkerCancel;
	private Object guardian;
	private Method guardianCanEncounterAct;
	private Method prepareForCombat;
	private Method releasePreparedCombat;
	private boolean armed;
	private boolean magicCombat;
	private boolean spellSelected;
	private boolean castPending;
	private boolean castAnimationSeen;
	private int castRunes;
	private boolean magicTabRequested;
	private boolean hunting;
	private boolean locatePending;
	private int[] direction;
	private WorldPoint lastLocateAt;
	private NPC rejectedButterfly;
	private WorldPoint rejectedTile;
	private int locateInputs;
	private Method walkerControlStatus;
	private boolean errorReported;
	private int stableTicks;
	private int stableRegion = -1;
	private int stablePlane = -1;

	enum Phase { IDLE, PREFLIGHT, LOCATE, ENGAGE, FIGHT, CONFIRM, COMPLETE, ERROR }

	@Override
	protected void startUp()
	{
		armed = false;
		phase = Phase.IDLE;
		status = "Stopped; set a verified butterfly tile and start the encounter";
		error = null;
		stableTicks = 0;
		stableRegion = -1;
		stablePlane = -1;
	}

	@Override
	protected void shutDown()
	{
		stopEncounter();
	}

	public synchronized boolean setCandidateTile(int x, int y, int plane)
	{
		if (armed) { return false; }
		hunting = false;
		candidateTile = new WorldPoint(x, y, plane);
		status = "Verified butterfly tile set to " + candidateTile;
		return true;
	}

	public synchronized boolean startEncounter()
	{
		if (armed || (!hunting && candidateTile == null)) { return false; }
		armed = true;
		magicCombat = false;
		spellSelected = false; castPending = false; castAnimationSeen = false; magicTabRequested = false;
		phase = Phase.PREFLIGHT;
		status = "Preflight";
		error = null;
		errorReported = false;
		locateButterfly = null;
		combatTarget = null;
		pendingAttack = null;
		walkTarget = null;
		locatePending = false; direction = null; lastLocateAt = null;
		rejectedButterfly = null; rejectedTile = null; locateInputs = 0;
		return true;
	}

	public synchronized boolean startHunt()
	{
		if (armed) { return false; }
		hunting = true;
		candidateTile = null;
		return startEncounter();
	}

	public synchronized boolean startEncounter(int x, int y, int plane)
	{
		return setCandidateTile(x, y, plane) && startEncounter();
	}

	public synchronized void stopEncounter()
	{
		cancelOwnedWalk();
		releaseGuardianTarget();
		armed = false;
		phase = Phase.IDLE;
		status = "Stopped";
		candidateTile = null;
		clearTargets();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (!armed || event.getGameState() == GameState.LOGGED_IN) { return; }
		if (event.getGameState() == GameState.LOADING)
		{
			invalidateScene();
			stableTicks = 0;
			stableRegion = -1;
			stablePlane = -1;
		}
		else { stopAfterTransition("Stopped after login transition"); }
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!armed) { return; }
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			stopAfterTransition("Stopped after login transition");
			status = "Waiting for logged-in player";
			return;
		}
		WorldPoint location = client.getLocalPlayer().getWorldLocation();
		if (hasItem(CHARGED)) { complete(); return; }
		if (!stableScene(location)) { status = "Waiting for stable scene"; return; }
		if (!bindWalker()) { fail("Exactly one compatible Efficient Walker is required"); return; }
		if (!bindGuardian()) { fail("Guardian safety API is unavailable"); return; }

		if (!guardianCanAct())
		{
			if (phase != Phase.ERROR) { status = "Waiting for Guardian safety"; }
			return;
		}
		if (phase == Phase.ENGAGE || phase == Phase.FIGHT)
		{
			NPC target = currentDraugen();
			if (target != null && !guardianReady(target))
			{
				if (phase != Phase.ERROR)
				{
					status = "Waiting for Guardian safety";
				}
				return;
			}
		}
		switch (phase)
		{
			case PREFLIGHT: preflight(); break;
			case LOCATE: locate(); break;
			case ENGAGE: engage(); break;
			case FIGHT: fight(); break;
			case CONFIRM: confirm(); break;
			default: break;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!armed || phase != Phase.LOCATE || !locatePending || event.getMessage() == null
			|| event.getType() != ChatMessageType.GAMEMESSAGE) { return; }
		String message = event.getMessage();
		int[] bearing = talismanDirection(message);
		if (bearing != null)
		{
			direction = bearing;
			locatePending = false;
			status = message;
		}
		else if (message.contains("Draugen has moved elsewhere"))
		{
			if (!hunting) { fail("Locate failed; verify the butterfly tile and select the current butterfly"); return; }
			rejectedButterfly = locateButterfly;
			rejectedTile = candidateTile;
			locateButterfly = null;
			// The direction follows this message; keep the request pending until that response or a spawn.
			status = "Draugen moved; waiting for the talisman's direction";
		}
	}

	private static int[] talismanDirection(String message)
	{
		String prefix = "The talisman guides you ";
		if (!message.startsWith(prefix) || !message.endsWith(".")) { return null; }
		switch (message.substring(prefix.length(), message.length() - 1))
		{
			case "north": return new int[]{0, 1};
			case "north-east": return new int[]{1, 1};
			case "east": return new int[]{1, 0};
			case "south-east": return new int[]{1, -1};
			case "south": return new int[]{0, -1};
			case "south-west": return new int[]{-1, -1};
			case "west": return new int[]{-1, 0};
			case "north-west": return new int[]{-1, 1};
			default: return null;
		}
	}

	private boolean supplied()
	{
		int food = 0;
		var inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null) { return false; }
		for (Item item : inventory.getItems())
		{
			if (item == null || item.getId() < 0) { continue; }
			var definition = client.getItemDefinition(item.getId());
			String[] actions = definition == null ? null : definition.getInventoryActions();
			if (actions != null && java.util.Arrays.asList(actions).contains("Eat")) { food++; }
		}
		return client.getBoostedSkillLevel(Skill.PRAYER) >= 35 && food >= 5
			&& client.getBoostedSkillLevel(Skill.HITPOINTS) >= client.getRealSkillLevel(Skill.HITPOINTS);
	}

	private void preflight()
	{
		magicCombat = equipped(1381);
		if (!weaponReady()) { fail("Equip an adamant scimitar, or a staff of air with 41 Magic and death runes for Wind Blast"); return; }
		if (!supplied())
		{
			fail("Restore full health, at least 35 prayer points, and carry at least five food items before locating Draugen");
			return;
		}
		WorldPoint player = client.getLocalPlayer().getWorldLocation();
		if (!hunting && (player.getPlane() != candidateTile.getPlane() || player.getRegionID() != candidateTile.getRegionID()))
		{
			fail("Verified butterfly tile must be in the current local encounter region");
			return;
		}
		if (!hasItem(UNCHARGED) && !hasItem(CHARGED))
		{
			fail("Carry the uncharged strange talisman before locating Draugen");
			return;
		}
		if (hasItem(CHARGED)) { phase = Phase.CONFIRM; return; }
		phase = Phase.LOCATE;
		status = hunting ? "Searching for Draugen" : "Waiting for the verified butterfly tile";
	}

	private void locate()
	{
		NPC draugen = currentDraugen();
		if (draugen != null)
		{
			cancelOwnedWalk();
			finishWalk();
			combatTarget = draugen;
			locateButterfly = null;
			locatePending = false;
			phase = Phase.ENGAGE;
			status = "Draugen located; preparing combat";
			return;
		}
		if (locatePending) { return; }
		NPC butterfly = hunting ? visibleButterfly() : currentButterfly();
		if (!armed) { return; }
		if (hunting && butterfly != null)
		{
			WorldPoint currentTile = butterfly.getWorldLocation();
			if (!currentTile.equals(candidateTile))
			{
				cancelOwnedWalk();
				finishWalk();
				candidateTile = currentTile;
			}
		}
		else if (hunting && candidateTile != null)
		{
			cancelOwnedWalk();
			finishWalk();
			candidateTile = null;
		}
		if (walkTarget != null)
		{
			if (!walkArrived()) { return; }
			finishWalk();
		}
		WorldPoint player = client.getLocalPlayer().getWorldLocation();
		if (candidateTile != null && !candidateTile.equals(player))
		{
			if (requestWalk(candidateTile)) { status = "Following the verified Draugen butterfly"; }
			return;
		}
		if (hunting && butterfly == null && direction != null)
		{
			WorldPoint destination = searchDestination(player, direction);
			direction = null;
			if (destination == null) { fail("No visible standable tile in the talisman direction; inspect the route"); return; }
			if (requestWalk(destination)) { status = "Following the talisman direction through Efficient Walker"; }
			return;
		}
		if (!hunting && butterfly == null) { status = "Waiting for the verified butterfly at " + candidateTile; return; }
		if (player.equals(lastLocateAt))
		{
			fail("Locate produced no new eligible tile; inspect the talisman response before resuming");
			return;
		}
		if (!weaponReady()) { fail("Restore the prepared Draugen weapon and spell supplies before summoning"); return; }
		if (butterfly != null && !supplied()) { fail("Restore full health, 35 prayer, and five food items before summoning Draugen"); return; }
		locateButterfly = butterfly;
		locatePending = true;
		if (inventoryAction(UNCHARGED, "Locate"))
		{
			lastLocateAt = player;
			locateInputs++;
			status = butterfly == null ? "Locate dispatched; waiting for direction" : "Locate dispatched on the butterfly tile; waiting for Draugen";
		}
		else { locatePending = false; fail("Locate input was not available on the uncharged talisman"); }
	}

	private NPC visibleButterfly()
	{
		NPC found = null;
		for (NPC npc : client.getNpcs())
		{
			if (npc == null || npc.getId() != BUTTERFLY || npc.getWorldLocation() == null
				|| npc.getWorldLocation().getPlane() != client.getLocalPlayer().getWorldLocation().getPlane()
				|| (npc == rejectedButterfly && npc.getWorldLocation().equals(rejectedTile))) { continue; }
			if (found != null) { fail("Multiple Draugen butterflies are visible; resolve the target before resuming"); return null; }
			found = npc;
		}
		return found;
	}

	private WorldPoint searchDestination(WorldPoint origin, int[] bearing)
	{
		WorldView view = client.getTopLevelWorldView();
		CollisionData[] maps = view == null ? null : view.getCollisionMaps();
		int plane = origin.getPlane();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null) { return null; }
		int[][] flags = maps[plane].getFlags();
		// ponytail: short observed waypoints; Walker owns routing around obstacles and transports.
		for (int distance = 16; distance > 0; distance--)
		{
			WorldPoint point = new WorldPoint(origin.getX() + bearing[0] * distance,
				origin.getY() + bearing[1] * distance, plane);
			int x = point.getX() - view.getBaseX(), y = point.getY() - view.getBaseY();
			if (inBounds(flags, x, y) && (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0) { return point; }
		}
		return null;
	}

	private void engage()
	{
		NPC current = currentDraugen();
		if (current == null) { status = "Waiting for the located Draugen"; return; }
		if (combatTarget != null && current != combatTarget)
		{
			fail("Draugen target changed before combat; re-resolve the encounter");
			return;
		}
		combatTarget = current;
		if (walkTarget != null)
		{
			if (!walkArrived()) { return; }
			finishWalk();
		}
		if (!canAttackFromCurrent(current))
		{
			WorldPoint approach = approachTile(current);
			if (approach == null) { fail("No conservative adjacent tile is available for Draugen"); return; }
			if (requestWalk(approach)) { status = "Walking to Draugen through Efficient Walker"; }
			return;
		}
		phase = Phase.FIGHT;
		status = "Ready to attack the located Draugen";
	}

	private void fight()
	{
		NPC current = currentDraugen();
		if (current == null) { fail("Draugen disappeared before the charged talisman was observed"); return; }
		if (current != combatTarget) { fail("Draugen target changed during combat; stale target rejected"); return; }
		if (magicCombat) { castWindBlast(current); return; }
		if (pendingAttack != null)
		{
			if (interacting(current)) { pendingAttack = null; status = "Fighting Draugen"; }
			return;
		}
		if (interacting(current)) { status = "Fighting Draugen"; return; }
		if (!canAttackFromCurrent(current)) { phase = Phase.ENGAGE; return; }
		if (npcAction(current, "Attack"))
		{
			pendingAttack = current;
			status = "Attack dispatched; waiting for combat";
		}
		else { fail("Draugen Attack input was not available"); }
	}

	private boolean weaponReady()
	{
		return magicCombat ? equipped(1381) && client.getBoostedSkillLevel(Skill.MAGIC) >= 41 && runeCount() > 0
			&& client.getVarbitValue(net.runelite.api.gameval.VarbitID.SPELLBOOK) == 0 : equipped(ADAMANT_SCIMITAR);
	}

	private int runeCount()
	{
		var inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null) { return 0; }
		for (Item item : inventory.getItems()) { if (item.getId() == 560) { return item.getQuantity(); } }
		return 0;
	}

	private void castWindBlast(NPC target)
	{
		if (castPending)
		{
			castAnimationSeen |= client.getLocalPlayer().getAnimation() != -1;
			if (runeCount() < castRunes && castAnimationSeen && client.getLocalPlayer().getAnimation() == -1)
			{
				castPending = false; castAnimationSeen = false;
			}
			return;
		}
		if (!weaponReady()) { fail("Wind Blast supplies or equipment changed; restock before another fight"); return; }
		if (!canAttackFromCurrent(target)) { phase = Phase.ENGAGE; return; }
		Widget spell = client.getWidget(218, 35);
		if (spell == null || spell.isHidden())
		{
			if (!magicTabRequested) { client.runScript(915, 6); magicTabRequested = true; }
			status = "Opening the standard spellbook for Wind Blast";
			return;
		}
		magicTabRequested = false;
		if (!client.isWidgetSelected())
		{
			if (!spellSelected)
			{
				int mask = client.getWidgetConfig(spell) == null ? spell.getClickMask() : client.getWidgetConfig(spell).getClickMask();
				if ((mask & (1 << 12)) == 0) { fail("Wind Blast is not permitted to target NPCs"); return; }
				client.menuAction(-1, spell.getId(), MenuAction.WIDGET_TARGET, 0, -1, "Cast", "Wind Blast");
				spellSelected = true;
			}
			return;
		}
		if (client.getSelectedWidget() == null || client.getSelectedWidget().getId() != spell.getId())
		{
			fail("Another spell or item owns selection; inspect before resuming"); return;
		}
		castRunes = runeCount();
		client.menuAction(0, 0, MenuAction.WIDGET_TARGET_ON_NPC, target.getIndex(), -1, "Cast", target.getName());
		castPending = true; spellSelected = false;
		status = "Wind Blast dispatched; waiting for rune consumption and completed casting animation";
	}

	private void confirm()
	{
		if (!hasItem(CHARGED)) { fail("Encounter ended without an observed charged strange talisman"); return; }
		complete();
	}

	private void complete()
	{
		releaseGuardianTarget();
		armed = false;
		phase = Phase.COMPLETE;
		status = "Complete: charged strange talisman observed";
		clearTargets();
	}

	private NPC currentButterfly()
	{
		return client.getNpcs().stream().filter(n -> n != null && n.getId() == BUTTERFLY)
			.filter(n -> candidateTile.equals(n.getWorldLocation())).findFirst().orElse(null);
	}

	private NPC currentDraugen()
	{
		return client.getNpcs().stream().filter(n -> n != null && n.getId() == DRAUGEN && !n.isDead())
			.filter(n -> n.getWorldLocation() != null).findFirst().orElse(null);
	}

	private boolean guardianReady(NPC target)
	{
		try { return (Boolean) prepareForCombat.invoke(guardian, target) && guardianCanAct(); }
		catch (ReflectiveOperationException | ClassCastException exception)
		{
			fail("Guardian safety gate failed");
			return false;
		}
	}

	private boolean interacting(NPC target)
	{
		return client.getLocalPlayer().getInteracting() == target;
	}

	private WorldPoint approachTile(NPC target)
	{
		WorldPoint npc = target.getWorldLocation();
		WorldPoint player = client.getLocalPlayer().getWorldLocation();
		if (npc == null || player == null || npc.equals(player) || npc.getPlane() != player.getPlane()) { return null; }
		int dx = Integer.compare(player.getX(), npc.getX());
		int dy = Integer.compare(player.getY(), npc.getY());
		return dx == 0 ? new WorldPoint(npc.getX(), npc.getY() + dy, npc.getPlane())
			: new WorldPoint(npc.getX() + dx, npc.getY(), npc.getPlane());
	}

	private boolean canAttackFromCurrent(NPC target)
	{
		WorldPoint player = client.getLocalPlayer().getWorldLocation();
		WorldPoint npc = target.getWorldLocation();
		if (player == null || npc == null || player.getPlane() != npc.getPlane()
			|| Math.abs(player.getX() - npc.getX()) + Math.abs(player.getY() - npc.getY()) != 1) { return false; }
		WorldView view = client.getTopLevelWorldView();
		CollisionData[] maps = view == null ? null : view.getCollisionMaps();
		if (maps == null || npc.getPlane() < 0 || npc.getPlane() >= maps.length || maps[npc.getPlane()] == null) { return false; }
		int[][] flags = maps[npc.getPlane()].getFlags();
		int px = player.getX() - view.getBaseX(), py = player.getY() - view.getBaseY();
		int nx = npc.getX() - view.getBaseX(), ny = npc.getY() - view.getBaseY();
		if (!inBounds(flags, px, py) || !inBounds(flags, nx, ny)
			|| (flags[px][py] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0
			|| (flags[nx][ny] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) { return false; }
		int playerEdge = player.getX() < npc.getX() ? CollisionDataFlag.BLOCK_MOVEMENT_EAST
			: CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		int npcEdge = player.getX() < npc.getX() ? CollisionDataFlag.BLOCK_MOVEMENT_WEST
			: CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		if (player.getX() == npc.getX())
		{
			playerEdge = player.getY() < npc.getY() ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH : CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
			npcEdge = player.getY() < npc.getY() ? CollisionDataFlag.BLOCK_MOVEMENT_SOUTH : CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		}
		int playerSight = player.getX() < npc.getX() ? CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST
			: CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST;
		int npcSight = player.getX() < npc.getX() ? CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST
			: CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST;
		if (player.getX() == npc.getX())
		{
			playerSight = player.getY() < npc.getY() ? CollisionDataFlag.BLOCK_LINE_OF_SIGHT_NORTH : CollisionDataFlag.BLOCK_LINE_OF_SIGHT_SOUTH;
			npcSight = player.getY() < npc.getY() ? CollisionDataFlag.BLOCK_LINE_OF_SIGHT_SOUTH : CollisionDataFlag.BLOCK_LINE_OF_SIGHT_NORTH;
		}
		return (flags[px][py] & (playerEdge | playerSight)) == 0
			&& (flags[nx][ny] & (npcEdge | npcSight)) == 0;
	}

	private static boolean inBounds(int[][] flags, int x, int y)
	{
		return flags != null && x >= 0 && x < flags.length && flags[x] != null && y >= 0 && y < flags[x].length;
	}

	private boolean requestWalk(WorldPoint destination)
	{
		if (destination == null || walkTarget != null) { return false; }
		try
		{
			Map<?, ?> control = (Map<?, ?>) walkerControlStatus.invoke(walkerPlugin, walkerControlToken.invoke(walker));
			if (Boolean.TRUE.equals(control.get("active"))) { fail("Another Walker command owns movement; stop it before resuming the hunt"); return false; }
			if (!(Boolean) walkTo.invoke(walker, destination)) { fail("Efficient Walker rejected the encounter approach"); return false; }
			ownWalkerPlugin = walkerPlugin;
			ownRouteToken = walkerControlToken.invoke(walker);
			if (ownRouteToken == null) { fail("Efficient Walker did not return a route control token"); return false; }
			ownWalkerCancel = walkerCancelToken;
			walkTarget = destination;
			return true;
		}
		catch (ReflectiveOperationException exception)
		{
			fail("Efficient Walker approach API failed");
			return false;
		}
	}

	private boolean walkArrived()
	{
		try
		{
			Map<?, ?> control = (Map<?, ?>) walkerControlStatus.invoke(ownWalkerPlugin, ownRouteToken);
			if (!Boolean.TRUE.equals(control.get("owned"))) { fail("Encounter route ownership changed; inspect the safety or manual route"); return false; }
			if (walkTarget.equals(client.getLocalPlayer().getWorldLocation())) { cancelOwnedWalk(); return true; }
			String state = String.valueOf(walkerStatus.invoke(walker));
			if ("BLOCKED".equals(state) || "IDLE".equals(state)) { fail("Efficient Walker stopped before reaching the encounter tile"); }
		}
		catch (ReflectiveOperationException exception) { fail("Efficient Walker status API failed"); }
		return false;
	}

	private void finishWalk()
	{
		walkTarget = null;
		ownWalkerPlugin = null;
		ownRouteToken = null;
		ownWalkerCancel = null;
	}

	private boolean inventoryAction(int id, String action)
	{
		var container = client.getItemContainer(InventoryID.INVENTORY);
		Widget inventory = client.getWidget(ComponentID.INVENTORY_CONTAINER);
		Widget[] children = inventory == null ? null : inventory.getChildren();
		if (container == null || children == null) { return false; }
		Item[] items = container.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			if (items[slot] == null || items[slot].getId() != id) { continue; }
			Widget widget = null;
			for (Widget child : children) if (child != null && child.getIndex() == slot) { widget = child; break; }
			if (widget == null || widget.getActions() == null) { continue; }
			String[] actions = widget.getActions();
			for (int index = 0; index < actions.length; index++)
			{
				if (action.equalsIgnoreCase(actions[index]))
				{
					client.menuAction(slot, ComponentID.INVENTORY_CONTAINER, MenuAction.CC_OP,
						index + 1, id, action, widget.getName());
					return true;
				}
			}
		}
		return false;
	}

	private boolean npcAction(NPC npc, String action)
	{
		NPCComposition composition = client.getNpcDefinition(npc.getId());
		String[] actions = composition == null ? null : composition.getActions();
		if (actions == null) { return false; }
		for (int index = 0; index < actions.length && index < 5; index++)
		{
			if (!action.equalsIgnoreCase(actions[index])) { continue; }
			MenuAction menuAction;
			switch (index)
			{
				case 0: menuAction = MenuAction.NPC_FIRST_OPTION; break;
				case 1: menuAction = MenuAction.NPC_SECOND_OPTION; break;
				case 2: menuAction = MenuAction.NPC_THIRD_OPTION; break;
				case 3: menuAction = MenuAction.NPC_FOURTH_OPTION; break;
				default: menuAction = MenuAction.NPC_FIFTH_OPTION; break;
			}
			client.menuAction(0, 0, menuAction, npc.getIndex(), -1, action, npc.getName());
			return true;
		}
		return false;
	}

	private boolean hasItem(int id)
	{
		var container = client.getItemContainer(InventoryID.INVENTORY);
		if (container == null || container.getItems() == null) { return false; }
		for (Item item : container.getItems()) if (item != null && item.getId() == id) { return true; }
		return false;
	}

	private boolean equipped(int id)
	{
		var container = client.getItemContainer(InventoryID.EQUIPMENT);
		if (container == null || container.getItems() == null) { return false; }
		for (Item item : container.getItems()) if (item != null && item.getId() == id) { return true; }
		return false;
	}

	private boolean bindWalker()
	{
		try
		{
			List<Plugin> matches = pluginManager.getPlugins().stream()
				.filter(p -> p.getClass().getName().equals(WALKER) && pluginManager.isActive(p))
				.collect(Collectors.toList());
			if (matches.size() != 1) { return false; }
			Plugin currentPlugin = matches.get(0);
			Object current = currentPlugin.getClass().getMethod("getWalker").invoke(currentPlugin);
			if (current == null) { return false; }
			if (current != walker)
			{
				walker = current;
				walkerPlugin = currentPlugin;
				walkTo = current.getClass().getMethod("walkTo", WorldPoint.class);
				walkerStatus = current.getClass().getMethod("getStatus");
				walkerControlToken = current.getClass().getMethod("getControlToken");
				walkerCancelToken = currentPlugin.getClass().getMethod("cancelWalk", Object.class);
				walkerControlStatus = currentPlugin.getClass().getMethod("getControlStatus", Object.class);
			}
			return true;
		}
		catch (ReflectiveOperationException exception)
		{
			walker = null; walkerPlugin = null; walkTo = null; walkerStatus = null;
			walkerControlToken = null; walkerCancelToken = null;
			return false;
		}
	}

	private boolean bindGuardian()
	{
		try
		{
			List<Plugin> matches = pluginManager.getPlugins().stream()
				.filter(p -> p.getClass().getName().equals(GUARDIAN) && pluginManager.isActive(p))
				.collect(Collectors.toList());
			if (matches.size() != 1) { return false; }
			Object current = matches.get(0);
			if (current != guardian)
			{
				guardian = current;
				guardianCanEncounterAct = current.getClass().getMethod("canEncounterAct");
				prepareForCombat = current.getClass().getMethod("prepareForCombat", NPC.class);
				releasePreparedCombat = current.getClass().getMethod("releasePreparedCombat");
			}
			return true;
		}
		catch (ReflectiveOperationException exception)
		{
			guardian = null; guardianCanEncounterAct = null; prepareForCombat = null; releasePreparedCombat = null;
			return false;
		}
	}

	private boolean guardianCanAct()
	{
		try { return (Boolean) guardianCanEncounterAct.invoke(guardian); }
		catch (ReflectiveOperationException | ClassCastException exception)
		{
			fail("Guardian safety gate failed");
			return false;
		}
	}

	private void releaseGuardianTarget()
	{
		if (guardian == null || releasePreparedCombat == null || combatTarget == null) { return; }
		try { releasePreparedCombat.invoke(guardian); }
		catch (ReflectiveOperationException | IllegalArgumentException ignored) { }
	}

	private boolean stableScene(WorldPoint location)
	{
		if (location == null) { stableTicks = 0; return false; }
		int region = (location.getX() >> 6) << 8 | (location.getY() >> 6);
		if (region != stableRegion || location.getPlane() != stablePlane)
		{
			if (stableRegion != -1) { invalidateScene(); }
			stableRegion = region;
			stablePlane = location.getPlane();
			stableTicks = 1;
			return false;
		}
		return ++stableTicks >= 2;
	}

	private void invalidateScene()
	{
		cancelOwnedWalk();
		releaseGuardianTarget();
		locateButterfly = null;
		combatTarget = null;
		pendingAttack = null;
		walkTarget = null;
		locatePending = false; direction = null;
		if (hunting) { candidateTile = null; rejectedButterfly = null; rejectedTile = null; }
		if (armed && phase != Phase.COMPLETE && phase != Phase.ERROR) { phase = Phase.PREFLIGHT; }
	}

	private void cancelOwnedWalk()
	{
		if (walkTarget == null || ownWalkerPlugin == null || ownRouteToken == null || ownWalkerCancel == null) { return; }
		try { ownWalkerCancel.invoke(ownWalkerPlugin, ownRouteToken); }
		catch (ReflectiveOperationException | IllegalArgumentException ignored) { }
		ownWalkerPlugin = null;
		ownRouteToken = null;
		ownWalkerCancel = null;
	}

	private void stopAfterTransition(String message)
	{
		cancelOwnedWalk();
		releaseGuardianTarget();
		armed = false;
		phase = Phase.IDLE;
		candidateTile = null;
		clearTargets();
		stableTicks = 0;
		stableRegion = -1;
		stablePlane = -1;
		status = message;
	}

	private void clearTargets()
	{
		locateButterfly = null; combatTarget = null; pendingAttack = null; walkTarget = null;
		locatePending = false; direction = null; lastLocateAt = null; rejectedButterfly = null; rejectedTile = null;
		spellSelected = false; castPending = false; castAnimationSeen = false; magicTabRequested = false;
		ownWalkerPlugin = null; ownRouteToken = null; ownWalkerCancel = null;
	}

	private void fail(String reason)
	{
		if (phase == Phase.ERROR || phase == Phase.COMPLETE) { return; }
		cancelOwnedWalk();
		releaseGuardianTarget();
		armed = false;
		phase = Phase.ERROR;
		error = reason;
		status = "Error: " + reason;
		if (!errorReported)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>Error: " + reason + "</col>", "");
			errorReported = true;
		}
	}

	/** Fixed, opt-in surface used by Agent Server; the server never invokes arbitrary plugin methods. */
	public synchronized Map<String, Object> agentControlCommands()
	{
		Map<String, Object> commands = new java.util.LinkedHashMap<>();
		commands.put("setCandidateTile", coordinateArguments());
		commands.put("startEncounter", coordinateArguments());
		commands.put("startHunt", java.util.Collections.emptyMap());
		commands.put("stopEncounter", java.util.Collections.emptyMap());
		return commands;
	}

	public synchronized Map<String, Object> agentControlStatus()
	{
		Map<String, Object> result = new java.util.LinkedHashMap<>();
		result.put("state", phase.name());
		result.put("status", status);
		result.put("error", error);
		result.put("armed", armed);
		result.put("hunting", hunting);
		result.put("locatePending", locatePending);
		result.put("locateInputs", locateInputs);
		result.put("complete", phase == Phase.COMPLETE);
		if (candidateTile != null)
		{
			Map<String, Object> tile = new java.util.LinkedHashMap<>();
			tile.put("x", candidateTile.getX());
			tile.put("y", candidateTile.getY());
			tile.put("plane", candidateTile.getPlane());
			result.put("candidateTile", tile);
		}
		return result;
	}

	public synchronized Map<String, Object> agentControl(String command, Map<String, Object> args)
	{
		if (command == null || args == null)
		{
			return controlError("command and args are required");
		}
		try
		{
			switch (command)
			{
				case "setCandidateTile":
					if (!hasCoordinateKeys(args)) return controlError("setCandidateTile requires only x, y, and plane");
					return controlResult(setCandidateTile(coordinate(args, "x"), coordinate(args, "y"), coordinate(args, "plane")),
						"Candidate tile is already armed");
				case "startEncounter":
					if (!hasCoordinateKeys(args)) return controlError("startEncounter requires only x, y, and plane");
					return controlResult(startEncounter(coordinate(args, "x"), coordinate(args, "y"), coordinate(args, "plane")),
						"Encounter is already armed or the candidate tile is invalid");
				case "startHunt":
					if (!args.isEmpty()) return controlError("startHunt does not accept args");
					return controlResult(startHunt(), "Encounter is already armed");
				case "stopEncounter":
					if (!args.isEmpty()) return controlError("stopEncounter does not accept args");
					stopEncounter();
					return controlResult(true, null);
				default:
					return controlError("Unsupported control command: " + command);
			}
		}
		catch (IllegalArgumentException exception)
		{
			return controlError(exception.getMessage());
		}
	}

	private static Map<String, Object> coordinateArguments()
	{
		Map<String, Object> arguments = new java.util.LinkedHashMap<>();
		arguments.put("x", coordinateSpec());
		arguments.put("y", coordinateSpec());
		Map<String, Object> plane = new java.util.LinkedHashMap<>();
		plane.put("type", "integer");
		plane.put("min", 0);
		plane.put("max", 3);
		arguments.put("plane", plane);
		return arguments;
	}

	private static Map<String, Object> coordinateSpec()
	{
		Map<String, Object> coordinate = new java.util.LinkedHashMap<>();
		coordinate.put("type", "integer");
		coordinate.put("min", 0);
		coordinate.put("max", 16383);
		return coordinate;
	}

	private static boolean hasCoordinateKeys(Map<String, Object> args)
	{
		return args.size() == 3 && args.containsKey("x") && args.containsKey("y") && args.containsKey("plane");
	}

	private static int coordinate(Map<String, Object> args, String key)
	{
		Object value = args.get(key);
		if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be an integer");
		double number = ((Number) value).doubleValue();
		if (!Double.isFinite(number) || number != Math.rint(number))
		{
			throw new IllegalArgumentException(key + " must be an integer");
		}
		int result = (int) number;
		int max = "plane".equals(key) ? 3 : 16383;
		if (result < 0 || result > max) throw new IllegalArgumentException(key + " is outside the allowed range");
		return result;
	}

	private static Map<String, Object> controlResult(boolean success, String failure)
	{
		Map<String, Object> result = new java.util.LinkedHashMap<>();
		result.put("success", success);
		if (!success) result.put("error", failure);
		return result;
	}

	private static Map<String, Object> controlError(String message)
	{
		return controlResult(false, message == null ? "Invalid control request" : message);
	}

	public String getState() { return phase.name(); }
	public String getStatus() { return status; }
	public String getError() { return error; }
	public boolean isArmed() { return armed; }
	public boolean isComplete() { return phase == Phase.COMPLETE; }
}
