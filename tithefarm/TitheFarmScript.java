package net.runelite.client.plugins.microbot.tithefarm;

import java.awt.Color;
import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.tithefarm.TitheFarmCycle.Action;
import net.runelite.client.plugins.microbot.tithefarm.TitheFarmCycle.Crop;
import net.runelite.client.plugins.microbot.tithefarm.TitheFarmCycle.Snapshot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.util.ColorUtil;

final class TitheFarmScript extends Script
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TitheFarmScript.class);
	private static final String WALKER_CLASS =
		"net.runelite.client.plugins.microbot.efficientwalker.EfficientWalkerPlugin";
	@Inject
	private PluginManager pluginManager;
	private volatile boolean active;
	private volatile boolean userInput;
	private volatile int readyTick = -1;
	private int processedTick = -1;
	private int world;
	private int baseX;
	private int baseY;
	private Crop selectedCrop;
	private TitheFarmRotation rotation;
	private TitheFarmCombo combo;
	private volatile WorldPoint ownedMove;
	private Method walkerDestination;
	private boolean warningEmitted;
	private TitheFarmConfig.Mode mode = TitheFarmConfig.Mode.LAZY;
	private WorldPoint[] route;
	@Inject
	private TitheFarmConfig config;
	private volatile int completedCycles;
	private volatile int refillCount;
	private volatile int remainingWater;
	private volatile int xpGained;
	private volatile int pointsGained;
	private int startingXp;
	private int startingPoints;
	private Plugin dependency;
	private Object walker;
	private Method walkerGetter;
	private Method walkerStatus;
	private volatile String error;
	private volatile String status = "Checking setup";
	private volatile int watered;
	private volatile int harvested;
	private volatile int deposited;
	private volatile boolean complete;

	void onUserInput()
	{
		userInput = true;
	}

	boolean start()
	{
		userInput = false;
		active = true;
		return true;
	}

	void onGameTick(int tick)
	{
		// Hooks increments the client tick after posting GameTick, before queued reads.
		readyTick = tick + 1;
		if (!active) { return; }
		Microbot.getClientThread().invokeLater(() ->
		{
			try
			{
				Intent intent = prepare();
				if (intent != null) { scheduledExecutorService.execute(() -> dispatch(intent)); }
			}
			catch (Exception exception) { inputFailed(exception); }
		});
	}

	private void dispatch(Intent intent)
	{
		try
		{
			if (!active || Thread.currentThread().isInterrupted()) { return; }
			boolean allowed = Microbot.getClientThread().runOnClientThreadOptional(() ->
				active && validRuntime() && Microbot.getClient().getTickCount() == intent.tick).orElse(false);
			if (!allowed) { return; }
			if (intent.destination != null)
			{
				ownedMove = intent.destination;
				if (!Boolean.TRUE.equals(walker.getClass().getMethod("walkTo", WorldPoint.class)
					.invoke(walker, intent.destination)))
				{
					throw new IllegalStateException("Walker rejected approach");
				}
			}
			else { Microbot.doInvoke(intent.entry, intent.bounds); }
		}
		catch (Exception exception) { inputFailed(exception); }
	}

	private void inputFailed(Exception exception)
	{
		Microbot.getClientThread().invoke(() ->
		{
			if (!active || error != null) { return; }
			log.warn("Tithe Farm state or input failed in {}", status, exception);
			fail("Tithe Farm could not read or act on the current state. Check the setup and restart.");
		});
	}

	private Intent prepare()
	{
		if (!active || error != null || complete)
		{
			return null;
		}
		if (!validRuntime())
		{
			return null;
		}
		if (readyTick <= processedTick || readyTick != Microbot.getClient().getTickCount())
		{
			return null;
		}
		processedTick = readyTick;
		if (selectedCrop == null && !setup())
		{
			return null;
		}
		if (world != Microbot.getClient().getWorld() || baseX != Microbot.getClient().getBaseX()
			|| baseY != Microbot.getClient().getBaseY())
		{
			fail("The world or scene changed. Check any remaining crop before restarting Tithe Farm.");
			return null;
		}
		Snapshot[] snapshots = new Snapshot[route.length];
		Rs2TileObjectModel[] objects = new Rs2TileObjectModel[route.length];
		for (int i = 0; i < route.length; i++)
		{
			WorldPoint location = route[i];
			objects[i] = Microbot.getRs2TileObjectCache().query()
				.where(candidate -> location.equals(candidate.getWorldLocation())
					&& (candidate.getId() == TitheFarmCycle.EMPTY_PATCH || selectedCrop.contains(candidate.getId())))
				.first();
			snapshots[i] = snapshot(objects[i] == null ? -1 : objects[i].getId());
		}
		int capacity = (int) Rs2Inventory.items().filter(item -> ordinaryCan(item.getId())).count() * 8;
		boolean gricoller = Rs2Inventory.contains(TitheFarmCycle.GRICOLLERS_CAN);
		if (gricoller) { capacity += TitheFarmCycle.GRICOLLERS_CAPACITY; }
		int can = gricoller ? TitheFarmCycle.GRICOLLERS_CAN
			: Rs2Inventory.items().filter(item -> ordinaryCan(item.getId()) && item.getId() != 5340)
			.mapToInt(item -> item.getId()).findFirst().orElse(-1);
		Action action;
		int index;
		if (combo == null)
		{
			action = rotation.next(snapshots, capacity, Microbot.getClient().getEnergy() / 100,
				Microbot.getClient().getVarpValue(173) == 1, can, false, 0);
			index = rotation.index;
			watered = rotation.watered; harvested = rotation.harvested; deposited = rotation.deposited;
			completedCycles = rotation.cycles; refillCount = rotation.refills;
			complete = rotation.phase == TitheFarmRotation.Phase.COMPLETE;
			status = complete ? "Complete: " + deposited + " fruit deposited"
				: rotation.phase == TitheFarmRotation.Phase.PREPARE ? "Refilling / recovering run energy"
				: rotation.phase.name() + " - patch " + (index + 1) + "/" + route.length;
			if (rotation.error != null) { fail(rotation.error); return null; }
		}
		else
		{
			String movement;
			try { movement = String.valueOf(walkerStatus.invoke(walker)); }
			catch (ReflectiveOperationException exception)
			{
				log.warn("Could not read Efficient Walker status", exception);
				fail("Efficient Walker is unavailable. Check the crop before restarting.");
				return null;
			}
			if (ownedMove != null && "BLOCKED".equals(movement))
			{
				combo.movementFailed(); cancelOwnedMove(); return null;
			}
			WorldPoint player = Microbot.getClient().getLocalPlayer().getWorldLocation();
			LocalPoint localPlayer = LocalPoint.fromWorld(Microbot.getClient().getLocalPlayer().getWorldView(), player);
			if (localPlayer == null) { fail("The player left the loaded scene. Check the crop before restarting."); return null; }
			WorldPoint template = WorldPoint.fromLocalInstance(Microbot.getClient(), localPlayer);
			int charged = Rs2Inventory.items().filter(item -> item.getId() >= 5333 && item.getId() <= 5340)
				.mapToInt(item -> item.getId()).findFirst().orElse(-1);
			action = combo.next(snapshots, capacity, Microbot.getClient().getEnergy() / 100,
				Microbot.getClient().getVarpValue(173) == 1, can, charged,
				false, 0, template.getRegionX(), template.getRegionY(),
				"ARRIVED".equals(movement));
			if (ownedMove != null && "ARRIVED".equals(movement)) { ownedMove = null; }
			if (combo.phase == TitheFarmCombo.Phase.RECOVER && ownedMove != null) { cancelOwnedMove(); return null; }
			index = combo.index;
			watered = combo.watered; harvested = combo.harvested; deposited = combo.deposited;
			completedCycles = combo.cycles; refillCount = combo.refills;
			complete = combo.phase == TitheFarmCombo.Phase.COMPLETE;
			status = complete ? "Complete: " + deposited + " fruit deposited"
				: combo.phase.name() + " - patch " + (index + 1) + "/" + route.length;
			if (combo.warning != null)
			{
				status = "Error: " + combo.warning + " " + status;
				if (!warningEmitted)
				{
					warningEmitted = true;
					Microbot.getClient().addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
						ColorUtil.wrapWithColorTag("Error: " + combo.warning, Color.RED), null);
				}
			}
			if (combo.error != null) { fail(combo.error); return null; }
		}
		Snapshot snapshot = snapshots[index];
		Rs2TileObjectModel object = objects[index];
		remainingWater = snapshot.water;
		xpGained = snapshot.xp - startingXp;
		pointsGained = Microbot.getClient().getVarbitValue(TitheFarmCycle.POINTS) - startingPoints;
		Microbot.status = "Tithe Farm " + mode + ": " + status;
		if (action == Action.MOVE)
		{
			java.util.Collection<WorldPoint> destinations = WorldPoint.toLocalInstance(Microbot.getClient(),
				new WorldPoint(1792 + combo.moveX, 3456 + combo.moveY, 0));
			if (destinations.size() != 1) { fail("The approach tile is ambiguous. Check the scene before restarting."); return null; }
			return new Intent(readyTick, destinations.iterator().next());
		}
		if (action == Action.NONE)
		{
			return null;
		}
		if (action == Action.ENABLE_RUN)
		{
			Widget orb = Microbot.getClient().getWidget(net.runelite.api.widgets.WidgetInfo.MINIMAP_TOGGLE_RUN_ORB);
			if (orb == null || orb.isHidden() || orb.getActions() == null)
			{
				fail("The run toggle is unavailable. Enable run and restart Tithe Farm.");
				return null;
			}
			int optionIndex = Arrays.asList(orb.getActions()).indexOf("Toggle Run");
			if (optionIndex < 0)
			{
				fail("The run toggle action changed. Enable run and restart Tithe Farm.");
				return null;
			}
			return new Intent(readyTick, new NewMenuEntry().option("Toggle Run").target("")
				.param0(-1).param1(orb.getId()).identifier(optionIndex + 1).type(MenuAction.CC_OP), orb.getBounds());
		}
		if (action == Action.DESELECT)
		{
			return new Intent(readyTick, new NewMenuEntry().option("Cancel").target("")
				.param0(0).param1(0).type(MenuAction.CANCEL).identifier(0), new Rectangle(1, 1));
		}
		if (action == Action.SELECT_SEED || action == Action.SELECT_CAN)
		{
			if (Microbot.getClient().isWidgetSelected())
			{
				fail("Another item or spell is selected. Clear the selection and restart Tithe Farm.");
				return null;
			}
			int selectedId = action == Action.SELECT_CAN ? selectedCan() : selectedCrop.seed;
			Widget inventory = Microbot.getClient().getWidget(ComponentID.INVENTORY_CONTAINER);
			Widget seed = inventory == null || inventory.getDynamicChildren() == null ? null
				: Arrays.stream(inventory.getDynamicChildren())
					.filter(widget -> widget != null && widget.getItemId() == selectedId)
					.findFirst().orElse(null);
			if (seed == null || seed.isHidden())
			{
				fail("Open the inventory tab with your seeds and watering cans visible, then restart.");
				return null;
			}
			return new Intent(readyTick, new NewMenuEntry().option("Use").target(seed.getName())
				.param0(seed.getIndex()).param1(ComponentID.INVENTORY_CONTAINER)
				.type(MenuAction.WIDGET_TARGET).identifier(0).itemId(selectedId), seed.getBounds());
		}
		if (action == Action.DEPOSIT || action == Action.REFILL)
		{
			object = Microbot.getRs2TileObjectCache().query().withIds(action == Action.REFILL ? new int[]{5598} : new int[]{27431, 27432})
				.nearest(Microbot.getClient().getLocalPlayer().getWorldLocation(), 60);
		}
		if (object == null || object.getWorldView() == null)
		{
			fail("The patch or fruit sack is unavailable. Check the scene before restarting Tithe Farm.");
			return null;
		}
		boolean itemTarget = action == Action.PLANT || action == Action.REFILL || action == Action.MANUAL_WATER;
		String option = itemTarget ? "Use" : action == Action.WATER ? "Water"
			: action == Action.HARVEST ? "Harvest" : action == Action.CLEAR ? "Clear" : "Deposit";
		MenuAction opcode = MenuAction.WIDGET_TARGET_ON_GAME_OBJECT;
		if (itemTarget)
		{
			if (snapshot.selectedItem != (action == Action.REFILL || action == Action.MANUAL_WATER ? selectedCan() : selectedCrop.seed))
			{
				fail("The seed selection changed. Select the correct seed before restarting Tithe Farm.");
				return null;
			}
		}
		else
		{
			String[] actions = object.getObjectComposition().getActions();
			int actionIndex = actions == null ? -1 : Arrays.asList(actions).indexOf(option);
			if (actionIndex < 0 || Microbot.getClient().isWidgetSelected())
			{
				fail("The expected " + option + " action is unavailable. Clear any selection and check the crop.");
				return null;
			}
			opcode = MenuAction.of(actionIndex == 4 ? MenuAction.GAME_OBJECT_FIFTH_OPTION.getId()
				: MenuAction.GAME_OBJECT_FIRST_OPTION.getId() + actionIndex);
		}
		LocalPoint local = LocalPoint.fromWorld(object.getWorldView(), object.getWorldLocation());
		if (local == null)
		{
			fail("The target left the loaded scene. Check the crop before restarting Tithe Farm.");
			return null;
		}
		return new Intent(readyTick, new NewMenuEntry().option(option).target(object.getName())
			.param0(local.getSceneX()).param1(local.getSceneY()).type(opcode)
			.identifier(object.getId()).itemId(-1).setWorldViewId(object.getWorldView().getId()),
			Rs2UiHelper.getObjectClickbox(object));
	}

	private Snapshot snapshot(int objectId)
	{
		Widget selected = Microbot.getClient().getSelectedWidget();
		return new Snapshot(readyTick, objectId,
			Rs2Inventory.itemQuantity(selectedCrop.seed), Rs2Inventory.itemQuantity(selectedCrop.fruit), water(),
			Microbot.getClient().getVarbitValue(TitheFarmCycle.SACK_AMOUNT),
			Microbot.getClient().getSkillExperience(Skill.FARMING),
			Microbot.getClient().isWidgetSelected() && selected != null ? selected.getItemId() : -1);
	}

	private boolean validRuntime()
	{
		try
		{
			List<Plugin> matches = pluginManager.getPlugins().stream()
				.filter(plugin -> WALKER_CLASS.equals(plugin.getClass().getName()))
				.collect(java.util.stream.Collectors.toList());
			if (matches.size() != 1 || !pluginManager.isActive(matches.get(0)))
			{
				fail("Enable exactly one compatible Efficient Walker instance, then restart Tithe Farm.");
				return false;
			}
			Plugin found = matches.get(0);
			if (dependency == null)
			{
				walkerGetter = found.getClass().getMethod("getWalker");
				walker = walkerGetter.invoke(found);
				walker.getClass().getMethod("walkTo", WorldPoint.class);
				walker.getClass().getMethod("cancel");
				walkerStatus = walker.getClass().getMethod("getStatus");
				dependency = found;
			}
			if (dependency != found || walkerGetter.invoke(found) != walker)
			{
				fail("Efficient Walker changed during the run. Check the crop and restart Tithe Farm.");
				return false;
			}
			String movement = String.valueOf(walkerStatus.invoke(walker));
			if (ownedMove != null)
			{
				if ("ARRIVED".equals(movement) && !ownedMove.equals(Microbot.getClient().getLocalPlayer().getWorldLocation())
					|| !Arrays.asList("ARRIVED", "BLOCKED").contains(movement)
						&& !ownedMove.equals(walkerDestination.invoke(walker)))
				{
					fail("The Tithe approach route changed. Check the crop before restarting.");
					return false;
				}
			}
			else if (!Arrays.asList("IDLE", "ARRIVED", "BLOCKED").contains(movement))
			{
				fail("Another route is active. Stop that route before restarting Tithe Farm.");
				return false;
			}
		}
		catch (ReflectiveOperationException | RuntimeException exception)
		{
			log.warn("Efficient Walker runtime check failed", exception);
			fail("Efficient Walker is incompatible or unavailable. Install and enable a compatible Efficient Walker, then restart Tithe Farm.");
			return false;
		}
		if (Microbot.getClient().getGameState() != GameState.LOGGED_IN
			|| Microbot.getClient().getLocalPlayer() == null || Rs2Inventory.inventory() == null)
		{
			fail("The game state is unavailable. Log in and check the crop before restarting Tithe Farm.");
			return false;
		}
		if (Microbot.pauseAllScripts.get() || userInput)
		{
			fail("Input was paused or taken over. Tend any remaining crop before restarting Tithe Farm.");
			return false;
		}
		return true;
	}

	private boolean setup()
	{
		mode = config.mode();
		if (mode == TitheFarmConfig.Mode.EFFICIENT)
		{
			fail("Efficient mode is not available yet. Choose Moderate (23 plants) or Lazy (20 plants), then restart.");
			return false;
		}

		int cans = TitheFarmRotation.cans(mode);
		if (!Microbot.getClient().isInInstancedRegion())
		{
			fail("Enter Tithe Farm and stand beside an empty patch before starting.");
			return false;
		}
		Rs2TileObjectModel empty = Microbot.getRs2TileObjectCache().query()
			.withId(TitheFarmCycle.EMPTY_PATCH)
			.nearest(Microbot.getClient().getLocalPlayer().getWorldLocation(), 10);
		if (empty == null || templateOrigin(empty) == null || templateOrigin(empty).getRegionID() != 7222)
		{
			fail("Stand within 10 tiles of an empty Tithe patch, then restart.");
			return false;
		}
		if (!checkTools()) { return false; }
		if (!Rs2Inventory.contains(TitheFarmCycle.GRICOLLERS_CAN)
			&& Rs2Inventory.items().filter(item -> ordinaryCan(item.getId())).count() < cans)
		{
			fail("Missing " + (cans - Rs2Inventory.items().filter(item -> ordinaryCan(item.getId())).count())
				+ " ordinary watering cans. Bring " + cans + " cans or Gricoller's can before starting " + mode + " mode.");
			return false;
		}
		if (Rs2Inventory.isFull() || Arrays.stream(Crop.values()).anyMatch(crop -> Rs2Inventory.contains(crop.fruit)))
		{
			fail("Deposit existing Tithe fruit and leave one inventory slot free, then restart.");
			return false;
		}
		boolean occupied = Microbot.getRs2TileObjectCache().query()
			.where(object -> Arrays.stream(Crop.values()).anyMatch(crop -> crop.contains(object.getId())))
			.first() != null;
		if (occupied)
		{
			fail("Finish or clear the existing Tithe crop before starting " + mode + " mode.");
			return false;
		}
		Crop selected = null;
		for (Crop crop : Crop.values())
		{
			if (Rs2Inventory.contains(crop.seed)
				&& Microbot.getClient().getRealSkillLevel(Skill.FARMING) >= crop.level
				&& Microbot.getClient().getBoostedSkillLevel(Skill.FARMING) >= crop.level)
			{
				selected = crop;
			}
		}
		if (selected == null)
		{
			fail("Bring a Tithe Farm seed you can plant without a boost, then restart.");
			return false;
		}
		int[][] patches = TitheFarmRotation.patches(mode);
		route = new WorldPoint[patches.length];
		for (int i = 0; i < route.length; i++)
		{
			int[] xy = patches[i];
			WorldPoint template = new WorldPoint(1792 + xy[0], 3456 + xy[1], 0);
			Rs2TileObjectModel plot = Microbot.getRs2TileObjectCache().query().withId(TitheFarmCycle.EMPTY_PATCH)
				.where(candidate -> template.equals(templateOrigin(candidate)))
				.first();
			if (plot == null)
			{
				fail(mode + " route patch " + (i + 1) + " is unavailable. Start inside a cleared Tithe Farm instance.");
				return false;
			}
			route[i] = plot.getWorldLocation();
		}
		startingXp = Microbot.getClient().getSkillExperience(Skill.FARMING);
		startingPoints = Microbot.getClient().getVarbitValue(TitheFarmCycle.POINTS);
		if (mode == TitheFarmConfig.Mode.EFFICIENT) { combo = new TitheFarmCombo(selected); }
		else { rotation = new TitheFarmRotation(selected, mode); }
		world = Microbot.getClient().getWorld();
		baseX = Microbot.getClient().getBaseX();
		baseY = Microbot.getClient().getBaseY();
		selectedCrop = selected;
		return true;
	}

	private boolean checkTools()
	{
		boolean needsDibber = !Rs2Inventory.contains(5343)
			&& Microbot.getClient().getVarbitValue(VarbitID.BRUT_FARMING_PLANTING) != 3;
		if (!Rs2Inventory.contains(952) || needsDibber)
		{
			String missing = !Rs2Inventory.contains(952) ? "1 Spade" : "";
			if (needsDibber)
			{
				missing += missing.isEmpty() ? "1 Seed dibber" : " and 1 Seed dibber";
			}
			fail("Missing " + missing + " in inventory. Bring the missing tools, then restart Tithe Farm.");
			return false;
		}
		return true;
	}

	static WorldPoint templateOrigin(Rs2TileObjectModel object)
	{
		LocalPoint origin = LocalPoint.fromWorld(object.getWorldView(), object.getWorldLocation());
		return origin == null ? null : WorldPoint.fromLocalInstance(Microbot.getClient(), origin);
	}

	private static boolean ordinaryCan(int id)
	{
		return id == 5331 || id >= 5333 && id <= 5340;
	}

	private static int water()
	{
		int charges = Rs2Inventory.contains(TitheFarmCycle.GRICOLLERS_CAN)
			? Microbot.getClient().getVarbitValue(TitheFarmCycle.GRICOLLERS_CHARGES) : 0;
		if (charges < 0 || charges > TitheFarmCycle.GRICOLLERS_CAPACITY)
		{
			throw new IllegalStateException("Gricoller's can charge state is unavailable");
		}
		return charges + Rs2Inventory.items().filter(item -> item.getId() >= 5333 && item.getId() <= 5340)
			.mapToInt(item -> (item.getId() - 5332) * item.getQuantity()).sum();
	}

	private int selectedCan() { return combo == null ? rotation.selectedCan : combo.selectedCan; }

	private void cancelOwnedMove()
	{
		if (ownedMove == null) { return; }
		try { walker.getClass().getMethod("cancel").invoke(walker); }
		catch (ReflectiveOperationException exception) { log.warn("Could not cancel the Tithe Farm approach", exception); }
		ownedMove = null;
	}

	private void fail(String reason)
	{
		if (!active || error != null)
		{
			return;
		}
		cancelOwnedMove();
		error = reason;
		status = "Error: " + reason;
		Microbot.status = status;
		if (!warningEmitted || combo == null || !reason.equals(combo.warning))
		{
			Microbot.getClient().addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
				ColorUtil.wrapWithColorTag(status, Color.RED), null);
		}
	}

	@Override
	public void shutdown()
	{
		active = false;
		cancelOwnedMove();
		scheduledExecutorService.shutdownNow();
	}

	String getMode() { return mode.toString(); }
	int getDeaths() { return combo == null ? 0 : combo.deaths; }
	int getCycles() { return completedCycles; }
	int getRefills() { return refillCount; }
	int getWater() { return remainingWater; }
	int getXpGained() { return xpGained; }
	int getPointsGained() { return pointsGained; }
	String getStatus() { return status; }
	String getError() { return error; }
	int getWatered() { return watered; }
	int getHarvested() { return harvested; }
	int getDeposited() { return deposited; }
	boolean isComplete() { return complete; }

	private static final class Intent
	{
		final int tick;
		final NewMenuEntry entry;
		final Rectangle bounds;
		final WorldPoint destination;

		Intent(int tick, WorldPoint destination)
		{
			this.tick = tick; this.destination = destination; this.entry = null; this.bounds = null;
		}

		Intent(int tick, NewMenuEntry entry, Rectangle bounds)
		{
			this.destination = null;
			this.tick = tick;
			this.entry = entry;
			this.bounds = bounds;
		}
	}
}
