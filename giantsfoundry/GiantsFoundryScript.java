package net.runelite.client.plugins.microbot.giantsfoundry;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.giantsfoundry.FoundryData.Snapshot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.walker.Rs2MiniMap;

final class GiantsFoundryScript extends StateMachineScript<GiantsFoundryScript.State>
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GiantsFoundryScript.class);
	static final String WALKER_CLASS =
		"net.runelite.client.plugins.microbot.efficientwalker.EfficientWalkerPlugin";
	@Inject private PluginManager pluginManager;
	private int processedTick = -1;
	private volatile Snapshot latest;
	private Snapshot current;
	private String lastAction;
	private long lastSignature;
	private int actionTick;
	private int attempts;
	private int progressTick;
	private WorldPoint lastPosition;
	private volatile boolean complete;
	private int mouldPart;
	private FoundryData.Intent refinementIntent;
	private int lastCompletion = -1;
	private int lastQuality = -1;
	private int refinementProgressTick;
	private String recoveryReason;
	private boolean handInArmed;
	private int handInXp;
	private int handInPoints;
	private int handInTick;
	private Plugin walkerPlugin;
	private Object boundWalker;
	private boolean ready;
	volatile String error;
	volatile int completedSwords;
	private final long sessionStartedAt = System.nanoTime();
	volatile long sessionRuntimeMillis;
	volatile int smithingXpGained;
	volatile int reputationGained;
	private int previousXp = -1;
	private int previousReputation = -1;
	volatile int completedBonuses;
	private int lastBonusAcceptedTick = -1;
	private boolean bonusAttempted;
	private boolean bonusPending;
	private int bonusTick;
	private boolean activityKnown;
	private Snapshot stopSnapshot;
	private int stopStableTicks;
	private int stopRequiredTicks;
	private int stopClickTick;
	private int stopAttempts;
	private int[] alloy;
	private boolean takeBucket;
	private volatile Runnable stopCleanup;
	private volatile long stopDeadline;
	private boolean useWaterBucket;
	private volatile boolean stopRequested;
	private int storeTick = -1;
	private boolean storeRequired;
	private boolean pickupPending;
	private volatile String status = "Starting";

	enum State { PREFLIGHT, PREPARE, REFINE, HAND_IN, STORE, DONE, ERROR }

	@Override
	protected State initialState()
	{
		return State.PREFLIGHT;
	}

	@Override
	protected List<Transition<State>> defineTransitions()
	{
		java.util.ArrayList<Transition<State>> transitions = new java.util.ArrayList<>();
		for (State from : State.values())
		{
			for (State to : State.values())
			{
				if (from != to)
				{
					transitions.add(Transition.from(from).when(() -> desiredState() == to, "Observed " + to)
						.because("Observed " + to).goTo(to));
				}
			}
		}
		return transitions;
	}

	private State desiredState()
	{
		return error != null ? State.ERROR : complete ? State.DONE : !ready ? State.PREFLIGHT
			: handInArmed || current.hasPreform() && current.bit(13949) == 1000 ? State.HAND_IN : recoveryReason != null || stopRequested ? State.STORE
			: current.hasPreform() ? State.REFINE : State.PREPARE;
	}

	void start(GiantsFoundryConfig config)
	{
		try { alloy = FoundryData.recipe(config); }
		catch (FoundryData.ValidationException exception)
		{
			log.warn("Foundry recipe validation failed", exception);
			fail(exception.getMessage());
			return;
		}
		useWaterBucket = config.coolingMethod() == GiantsFoundryConfig.CoolingMethod.WATER_BUCKET;
		mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(net.runelite.client.util.RunnableExceptionLogger.wrap(() ->
		{
			if (stopCleanup != null && (error != null || complete || System.nanoTime() >= stopDeadline))
			{
				if (error == null && !complete) fail("Could not finish stopping safely. Check whether you are still working and whether the sword is stored before restarting.");
				Runnable cleanup = stopCleanup;
				stopCleanup = null;
				cleanup.run();
				return;
			}
			if (error == null && !complete)
				sessionRuntimeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sessionStartedAt);
			if (latest == null || latest.tick == processedTick || error != null || complete)
			{
				return;
			}
			current = latest;
			processedTick = current.tick;
			validateRuntime();
			if (error == null && current.loggedIn)
			{
				step();
			}
		}), 0, 50, TimeUnit.MILLISECONDS);
	}

	@net.runelite.client.eventbus.Subscribe
	public void onGameTick(net.runelite.api.events.GameTick event)
	{
		Snapshot observed = new Snapshot(Microbot.getClient(), lastBonusAcceptedTick);
		if (error == null && !complete)
			updateSession(observed.smithingXp, observed.reputation, observed.loggedIn);
		latest = observed;
	}

	void updateSession(int xp, int reputation, boolean loggedIn)
	{
		if (!loggedIn) { previousXp = -1; previousReputation = -1; return; }
		if (previousXp >= 0) smithingXpGained += Math.max(0, xp - previousXp);
		if (previousReputation >= 0) reputationGained += Math.max(0, reputation - previousReputation);
		previousXp = xp;
		previousReputation = reputation;
	}

	void onSweetSpotAccepted()
	{
		lastBonusAcceptedTick = Microbot.getClient().getTickCount();
	}

	@Override
	protected void onState(State state)
	{
		if (bonusPending)
		{
			if (FoundryData.bonusApplied(true, current.bonusAcceptedTick >= bonusTick,
				current.bit(13949) - lastCompletion, current.bit(13939) >= lastQuality && current.bit(13939) > 0))
			{
				completedBonuses++;
				bonusPending = false;
			}
			else if (current.tick - bonusTick > 10 || !current.hasPreform()) bonusPending = false;
		}
		if (state == State.PREPARE) prepare();
		else if (state == State.REFINE) refine();
		else if (state == State.HAND_IN) handIn();
		else if (state == State.STORE) store();
	}

	private void validateRuntime()
	{
		if (error != null)
		{
			return;
		}
		try
		{
			if (current.setupError != null || alloy == null)
			{
				fail(current.setupError != null ? current.setupError : "Select an alloy recipe in the plugin settings, then restart.");
				return;
			}
			List<Plugin> candidates = pluginManager.getPlugins().stream()
				.filter(plugin -> WALKER_CLASS.equals(plugin.getClass().getName()))
				.collect(Collectors.toList());
			if (candidates.size() != 1 || !pluginManager.isActive(candidates.get(0)))
			{
				fail("Enable exactly one Efficient Walker instance, then restart Giants' Foundry.");
				return;
			}
			Plugin candidate = candidates.get(0);
			if (!compatibleRuntime(candidates.size(), pluginManager.isActive(candidate),
				walkerPlugin == null || candidate == walkerPlugin))
			{
				fail("Efficient Walker is no longer compatible. Enable exactly one instance, then restart Giants' Foundry.");
				return;
			}
			if (walkerPlugin == null)
			{
				Object walker = candidate.getClass().getMethod("getWalker").invoke(candidate);
				walker.getClass().getMethod("cancel");
				walker.getClass().getMethod("walkTo", net.runelite.api.coords.WorldPoint.class);
				boundWalker = walker;
				walkerPlugin = candidate;
			}
			if (candidate.getClass().getMethod("getWalker").invoke(candidate) != boundWalker)
			{
				fail("Efficient Walker changed during the run. Restart Efficient Walker and Giants' Foundry.");
				return;
			}
			ready = true;

		}
		catch (ReflectiveOperationException | RuntimeException exception)
		{
			log.warn("Efficient Walker connection failed", exception);
			fail("Could not connect to Efficient Walker. Enable exactly one instance, then restart Giants' Foundry.");
		}
	}

	private void refine()
	{
		if (!current.inside()) { recoveryReason = "You are no longer inside Giants' Foundry. Return inside before restarting."; return; }
		int progress = current.bit(13949);
		int quality = current.bit(13939);
		if (quality <= 0) { recoveryReason = "The sword has no remaining quality. Check it in-game before restarting."; return; }
		if (lastQuality >= 0 && quality < lastQuality) { recoveryReason = "The sword lost quality, so refinement stopped. Check the sword before restarting."; return; }
		lastQuality = quality;
		if (lastCompletion < 0) bonusAttempted = true;
		else if (current.sweetSpot == 0 || current.sweetSpot == 2) bonusAttempted = false;
		if (lastCompletion != progress)
		{
			lastCompletion = progress;
			refinementProgressTick = current.tick;
		}
		if (current.tick - refinementProgressTick > 200) { recoveryReason = "Refinement stopped making progress. Check the required workstation and sword temperature before restarting."; return; }
		try
		{
			refinementIntent = FoundryData.refinement(current.bit(13938), progress,
				Arrays.copyOfRange(current.bits, 38, 44), current.bit(13948), refinementIntent);
		}
		catch (FoundryData.ValidationException exception)
		{
			log.warn("Foundry refinement validation failed", exception);
			recoveryReason = exception.getMessage();
			return;
		}
		switch (refinementIntent)
		{
			case HEAT: object(44631, "Heat-preform"); break;
			case COOL: object(44632, "Cool-preform"); break;
			case DUNK: object(44631, "Dunk-preform"); break;
			case QUENCH: object(44632, "Quench-preform"); break;
			case HAMMER: work(44619); break;
			case GRIND: work(44620); break;
			case POLISH: work(44621); break;
			default: break;
		}
	}

	private void work(int id)
	{
		if (FoundryData.bonusAvailable(current.sweetSpot, bonusAttempted, ("Use:" + id).equals(lastAction),
			current.tick - refinementProgressTick))
		{
			bonusAttempted = true;
			bonusPending = true;
			bonusTick = current.tick;
			lastAction = null;
		}
		object(id, "Use");
	}

	private void handIn()
	{
		if (!handInArmed)
		{
			if (!current.hasPreform() || current.bit(13949) != 1000 || current.bit(13939) <= 0)
			{
				fail("The sword is not ready for Kovac. Check the remaining refinement stages before restarting.");
				return;
			}
			handInArmed = true;
			handInXp = current.smithingXp;
			handInPoints = current.reputation;
			handInTick = current.tick;
		}
		if (FoundryData.handedIn(handInArmed, current.hasPreform(), current.bit(13947) != 0,
			current.smithingXp, handInXp, current.reputation, handInPoints))
		{
			completedSwords++;
			handInArmed = false;
			lastCompletion = -1;
			bonusPending = false;
			activityKnown = false;
			lastQuality = -1;
			refinementIntent = null;
			lastAction = null;
			mouldPart = 0;
			complete = stopRequested;
			setStatus("Completed " + completedSwords + " swords" + (complete ? "; stopped" : ""));
			return;
		}
		if (current.tick - handInTick > 60) { fail("Could not confirm that Kovac accepted the sword. Speak to him to check before restarting."); return; }
		if (Rs2Dialogue.hasContinue())
		{
			action("Continue hand-in", () -> { Rs2Dialogue.clickContinue(); return true; });
			return;
		}
		if (current.hasPreform())
			action("Hand-in", () -> Microbot.getRs2NpcCache().query().withName("Kovac").interact("Hand-in"));
	}

	private void prepare()
	{
		if (!current.inside())
		{
			fail("Move inside Giants' Foundry, then restart.");
			return;
		}
		if (current.weapon > 0 || current.shield > 0 || useWaterBucket && current.coolingGloves())
		{
			prepareEquipment();
			return;
		}
		if (current.bit(13947) == 1)
		{
			object(44778, "Take-preform");
			return;
		}
		if (current.bit(13914) >= 2)
		{
			if (!(useWaterBucket ? current.count(FoundryData.WATER_BUCKET) > 0 : current.coolingGloves()))
			{
				prepareEquipment();
				return;
			}
			if (current.bankOpen) { action("Close bank", Rs2Bank::closeBank); return; }
			object(44777, "Pick-up");
			return;
		}
		if (current.bit(13907) == 0 || current.bit(13908) == 0)
		{
			if (current.bankOpen) { action("Close bank", Rs2Bank::closeBank); return; }
			if (Rs2Dialogue.hasContinue()) { action("Continue commission", () -> { Rs2Dialogue.clickContinue(); return true; }); return; }
			action("Commission", () -> Microbot.getRs2NpcCache().query().withName("Kovac").interact("Commission"));
			return;
		}
		if (current.bit(13914) == 0)
		{
			if (current.bankOpen) { action("Close bank", Rs2Bank::closeBank); return; }
			selectMould();
			return;
		}
		int[] deficits = FoundryData.deficits(alloy, current.crucible());
		if (current.smithingLevel < FoundryData.requiredLevel(deficits))
		{
			fail("This metal load requires " + FoundryData.requiredLevel(deficits) + " Smithing; your current level is "
				+ current.smithingLevel + ". Select a recipe you can use.");
			return;
		}
		if (Arrays.stream(deficits).sum() == 0)
		{
			if (current.bankOpen) { action("Close bank", Rs2Bank::closeBank); return; }
			object(44776, "Pour");
			return;
		}
		for (int id : current.inventory.keySet())
		{
			int metal = -1;
			for (int i = 0; i < FoundryData.BARS.length; i++) if (FoundryData.BARS[i] == id) metal = i;
			if (metal < 0 || current.count(id) > deficits[metal])
			{
				if (!current.bankOpen) { objectByName("Bank chest", "Use"); return; }
				action("Deposit " + id, () -> Rs2Bank.depositAll(id));
				return;
			}
		}
		for (int metal = 0; metal < 6; metal++)
		{
			int id = FoundryData.BARS[metal];
			int missing = deficits[metal] - current.count(id);
			if (missing <= 0) continue;
			if (!current.bankOpen) { objectByName("Bank chest", "Use"); return; }
			if (current.bankEpoch <= 0) return;
			if (current.bank.getOrDefault(id, 0) < missing)
			{
				fail(FoundryData.missingBars(deficits, current.inventory, current.bank));
				return;
			}
			if (Microbot.getVarbitValue(net.runelite.api.gameval.VarbitID.BANK_WITHDRAWNOTES) != 0)
			{
				action("Withdraw items", () -> clickWidget(InterfaceID.Bankmain.NOTE, -1, "Disable Notes"));
				return;
			}
			action("Withdraw " + id, () -> Rs2Bank.withdrawX(id, missing));
			return;
		}
		if (current.bankOpen) { action("Close bank", Rs2Bank::closeBank); return; }
		for (int metal = 0; metal < 6; metal++)
		{
			if (current.count(FoundryData.BARS[metal]) == 0) continue;
			String name = FoundryData.BAR_NAMES[metal];
			if (Rs2Widget.isWidgetVisible(InterfaceID.SKILLMULTI, 0))
			{
				action("Add " + name.substring(0, name.length() - 4), () -> smelt(name));
			}
			else
			{
				object(44776, "Fill");
			}
			return;
		}
	}

	private boolean smelt(String name)
	{
		int widgetId = Microbot.getClientThread().runOnClientThreadOptional(() -> {
			Widget root = Microbot.getClient().getWidget(InterfaceID.SKILLMULTI, 0);
			if (root == null || root.isHidden()) return -1;
			Widget all = Microbot.getClient().getWidget(InterfaceID.SKILLMULTI, 12);
			if (all != null && all.getActions() != null && Arrays.asList(all.getActions()).contains("All"))
				return all.getId();
			Widget choice = Rs2Widget.findWidget(name, List.of(root), true);
			return FoundryData.permits(choice, name, "Smelt") ? choice.getId() : -1;
		}).orElse(-1);
		return widgetId >= 0 && clickWidget(widgetId, -1, (widgetId & 65535) == 12 ? "All" : "Smelt");
	}

	private void prepareEquipment()
	{
		if (current.weapon > 0 || current.shield > 0 || useWaterBucket && current.coolingGloves())
		{
			if (current.occupiedSlots >= 27)
			{
				if (!current.bankOpen) { objectByName("Bank chest", "Use"); return; }
				action("Clear inventory", Rs2Bank::depositAll);
				return;
			}
			if (current.bankOpen) { action("Close bank", Rs2Bank::closeBank); return; }
			EquipmentInventorySlot slot = current.weapon > 0 ? EquipmentInventorySlot.WEAPON
				: current.shield > 0 ? EquipmentInventorySlot.SHIELD : EquipmentInventorySlot.GLOVES;
			action("Remove " + slot, () -> Rs2Equipment.unEquip(slot));
			return;
		}
		if (!useWaterBucket && (current.count(FoundryData.ICE_GLOVES) > 0 || current.count(FoundryData.SMITHS_ICE_GLOVES) > 0))
		{
			if (current.bankOpen) { action("Close bank", Rs2Bank::closeBank); return; }
			int id = current.count(FoundryData.ICE_GLOVES) > 0 ? FoundryData.ICE_GLOVES : FoundryData.SMITHS_ICE_GLOVES;
			action("Wear cooling gloves", () -> Rs2Inventory.interact(id, "Wear"));
			return;
		}
		if (takeBucket && current.count(FoundryData.BUCKET) == 0)
		{
			if (current.bankOpen) { action("Close bank", Rs2Bank::closeBank); return; }
			object(33309, "Take-from");
			return;
		}
		takeBucket = false;
		if (useWaterBucket && current.count(FoundryData.BUCKET) > 0)
		{
			if (current.bankOpen) { action("Close bank", Rs2Bank::closeBank); return; }
			action("Fill water bucket", () -> Rs2Inventory.useItemOnObject(FoundryData.BUCKET, 44632));
			return;
		}
		if (!current.bankOpen) { objectByName("Bank chest", "Use"); return; }
		if (current.bankEpoch <= 0) return;
		int gloves = current.bank.getOrDefault(FoundryData.ICE_GLOVES, 0) > 0
			? FoundryData.ICE_GLOVES : FoundryData.SMITHS_ICE_GLOVES;
		if (current.occupiedSlots >= 27) { action("Clear inventory", Rs2Bank::depositAll); return; }
		if (!useWaterBucket && current.bank.getOrDefault(gloves, 0) == 0)
		{
			fail("Could not obtain ice gloves. Supply ice gloves or select Bucket of water, then restart.");
			return;
		}
		int item = !useWaterBucket ? gloves
			: current.bank.getOrDefault(FoundryData.WATER_BUCKET, 0) > 0 ? FoundryData.WATER_BUCKET
			: current.bank.getOrDefault(FoundryData.BUCKET, 0) > 0 ? FoundryData.BUCKET : -1;
		if (item < 0) { takeBucket = true; return; }
		if (Microbot.getVarbitValue(net.runelite.api.gameval.VarbitID.BANK_WITHDRAWNOTES) != 0)
		{
			action("Withdraw items", () -> clickWidget(InterfaceID.Bankmain.NOTE, -1, "Disable Notes"));
			return;
		}
		action("Withdraw pickup supplies", () -> Rs2Bank.withdrawX(item, 1));
	}

	private void selectMould()
	{
		boolean open = Microbot.getClientThread().runOnClientThreadOptional(() -> {
			Widget w = Microbot.getClient().getWidget(InterfaceID.GiantsFoundryMould.CONTENT);
			return w != null && !w.isHidden();
		}).orElse(false);
		if (!open) { object(44777, "Setup"); return; }
		if (mouldPart == 3 && (current.bit(13910) == 0 || current.bit(13911) == 0 || current.bit(13912) == 0))
		{
			mouldPart = 0;
		}
		if (mouldPart < 3)
		{
			if (current.bit(13909) != mouldPart)
			{
				String tab = new String[]{"Forte", "Blades", "Tips"}[mouldPart];
				action("Mould tab " + tab, () -> clickText(tab));
				return;
			}
			int best = current.bestMould[mouldPart];
			if (best < 1) { fail("Could not select an available mould for this commission. Check the mould interface, then restart. If this repeats, report the issue."); return; }
			if (current.bit(13910 + mouldPart) == best)
			{
				mouldPart++;
				return;
			}
			String name = current.bestMouldName[mouldPart];
			action("Select mould " + name, () -> clickMould(name));
			return;
		}
		action("Set mould", () -> clickWidget(InterfaceID.GiantsFoundryMould.SET_BUTTON, -1, "Set"));
	}

	private boolean clickText(String text)
	{
		Rectangle bounds = Microbot.getClientThread().runOnClientThreadOptional(() -> {
			Widget menu = Microbot.getClient().getWidget(InterfaceID.GiantsFoundryMould.SIDE_MENU);
			Widget w = Rs2Widget.findWidget(text, menu == null ? List.of() : List.of(menu), true);
			return w == null || w.isHidden() ? null : w.getBounds();
		}).orElse(null);
		if (bounds == null || bounds.isEmpty()) return false;
		Microbot.getMouse().click(bounds);
		return true;
	}

	private boolean clickMould(String name)
	{
		int child = Microbot.getClientThread().runOnClientThreadOptional(() -> {
			Widget parent = Microbot.getClient().getWidget(InterfaceID.GiantsFoundryMould.CONTENT);
			return parent == null ? -1 : FoundryData.selectableMould(parent.getChildren(), name);
		}).orElse(-1);
		if (child < 0) return false;
		return clickWidget(InterfaceID.GiantsFoundryMould.CONTENT, child, "Select");
	}

	private boolean clickWidget(int id, int child, String action)
	{
		Rectangle[] bounds = Microbot.getClientThread().runOnClientThreadOptional(() -> {
			Widget parent = Microbot.getClient().getWidget(id);
			Widget w = parent == null ? null : child < 0 ? parent : parent.getChild(child);
			if (w == null || w.isHidden() || w.getActions() == null
				|| Arrays.stream(w.getActions()).filter(java.util.Objects::nonNull)
					.map(net.runelite.client.util.Text::removeTags).noneMatch(action::equalsIgnoreCase)) return null;
			return new Rectangle[]{w.getBounds(), parent.getBounds()};
		}).orElse(null);
		if (bounds == null || bounds[0].isEmpty()) return false;
		if (child >= 0 && !bounds[1].contains(bounds[0].getCenterX(), bounds[0].getCenterY()))
		{
			net.runelite.api.Point point = new net.runelite.api.Point((int) bounds[1].getCenterX(), (int) bounds[1].getCenterY());
			if (bounds[0].getCenterY() > bounds[1].getCenterY()) Microbot.getMouse().scrollDown(point);
			else Microbot.getMouse().scrollUp(point);
			return true;
		}
		Microbot.getMouse().click(bounds[0].intersection(bounds[1]));
		return true;
	}

	private void object(int id, String option)
	{
		action(option + ":" + id, () -> {
			Rs2TileObjectModel object = Microbot.getRs2TileObjectCache().query().withId(id).nearestOnClientThread();
			return clickObject(object, option);
		});
	}

	private void objectByName(String name, String option)
	{
		action(option + ":" + name, () -> {
			Rs2TileObjectModel object = Microbot.getRs2TileObjectCache().query().withName(name).nearestOnClientThread();
			return clickObject(object, option);
		});
	}

	private boolean clickObject(Rs2TileObjectModel object, String option)
	{
		if (object == null) return false;
		boolean allowed = Microbot.getClientThread().runOnClientThreadOptional(() -> {
			ObjectComposition c = object.getObjectComposition();
			if (c.getImpostorIds() != null) c = c.getImpostor();
			return c != null && c.getActions() != null && Arrays.stream(c.getActions()).anyMatch(option::equalsIgnoreCase);
		}).orElse(false);
		return allowed && object.click(option);
	}

	private void action(String key, BooleanSupplier dispatch)
	{
		if (current.hasPreform() && (stopSnapshot != null || !activityKnown || FoundryData.changingActivity(lastAction, key)))
		{
			if (!disengage()) return;
		}
		long signature = current.hasPreform() && key.startsWith("Use:")
			? current.bit(13949) * 1000L + current.bit(13939) : current.signature();
		if (!key.equals(lastAction))
		{
			lastAction = key;
			attempts = 0;
			actionTick = current.tick - 8;
			progressTick = current.tick;
			lastSignature = signature;
			lastPosition = current.position;
		}
		if (signature != lastSignature || !java.util.Objects.equals(lastPosition, current.position))
		{
			progressTick = current.tick;
			lastSignature = signature;
			lastPosition = current.position;
		}
		if (attempts > 0 && (current.tick - actionTick < 8 || current.tick - progressTick < 6)) return;
		if (attempts >= 3)
		{
			log.debug("Foundry action made no progress: {}", key);
			recover("Could not complete the action after repeated attempts: " + actionDescription(key)
				+ ". Check the game and the sword's status before restarting.");
			return;
		}
		attempts++;
		actionTick = current.tick;
		setStatus(actionDescription(key));
		dispatch.getAsBoolean();
	}

	private static String actionDescription(String key)
	{
		return "Use:44619".equals(key) ? "Hammering" : "Use:44620".equals(key) ? "Grinding"
			: "Use:44621".equals(key) ? "Polishing"
			: "Heat-preform:44631".equals(key) || "Dunk-preform:44631".equals(key) ? "Heating preform"
			: "Cool-preform:44632".equals(key) || "Quench-preform:44632".equals(key) ? "Cooling preform" : key.startsWith("Deposit ") ? "Depositing supplies"
			: key.matches("Withdraw [0-9]+") ? "Withdrawing supplies" : key.startsWith("Remove ") ? "Removing equipment" : key.split(":")[0].replace('-', ' ');
	}

	private boolean disengage()
	{
		if (stopSnapshot == null)
		{
			stopRequiredTicks = FoundryData.activityCadence(activityKnown ? lastAction : null);
			stopStableTicks = 0;
			stopAttempts = 0;
			stopClickTick = current.tick - stopRequiredTicks - 2;
		}
		else
		{
			boolean settled = FoundryData.activitySettled(current.tick - stopSnapshot.tick, current.animation,
				current.bit(13949) - stopSnapshot.bit(13949), current.bit(13939) - stopSnapshot.bit(13939),
				current.bit(13948) - stopSnapshot.bit(13948));
			stopStableTicks = settled ? stopStableTicks + 1 : 0;
			if (stopStableTicks >= stopRequiredTicks)
			{
				stopSnapshot = null;
				activityKnown = true;
				lastAction = null;
				return true;
			}
		}
		stopSnapshot = current;
		setStatus("Stopping current activity");
		if (current.tick - stopClickTick < stopRequiredTicks + 2) return false;
		if (stopAttempts >= 3) { fail("Could not stop the current activity. Stop working manually and check the sword before restarting."); return false; }
		net.runelite.api.Point point = Microbot.getClientThread().runOnClientThreadOptional(() -> {
			net.runelite.api.Point target = Rs2MiniMap.worldToMinimap(current.position);
			Widget minimap = Rs2MiniMap.getMinimapDrawWidget();
			return target != null && minimap != null && !minimap.isHidden()
				&& minimap.getBounds().contains(target.getX(), target.getY()) ? target : null;
		}).orElse(null);
		if (point == null) { fail("Could not use the minimap to stop the current activity. Show the minimap and check the sword before restarting."); return false; }
		stopAttempts++;
		stopClickTick = current.tick;
		Microbot.getMouse().click(point);
		return false;
	}

	@Override
	protected State onError(State state, Exception exception)
	{
		log.warn("Foundry failure in {}", state, exception);
		recover(exception instanceof FoundryData.ValidationException ? exception.getMessage()
			: "An unexpected error stopped the plugin. Check the sword's status before restarting. If this repeats, report the debug log.");
		return error == null ? State.STORE : State.ERROR;
	}

	static boolean compatibleRuntime(int instances, boolean active, boolean sameInstance)
	{
		return instances == 1 && active && sameInstance;
	}

	private synchronized void fail(String message)
	{
		if (error != null) return;
		log.debug("Stopped in {}: {}", getStateName(), message);
		error = message;
		String notice = "Error: Giants' Foundry: " + net.runelite.client.util.Text.removeTags(message);
		setStatus(notice);
		Microbot.getClientThread().invoke(() -> {
			Microbot.getClient().addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
				net.runelite.client.util.ColorUtil.wrapWithColorTag(notice, java.awt.Color.RED), null);
		});
	}

	String getStateName()
	{
		return error != null ? "ERROR" : complete ? "DONE" : getSnapshot() == null
			? "STARTING" : getSnapshot().currentState().name();
	}

	void requestStop(Runnable cleanup)
	{
		if (error != null || complete) { cleanup.run(); return; }
		stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
		stopRequested = true;
		stopCleanup = cleanup;
	}

	private void store()
	{
		if (!current.inside()) { fail("Could not store the sword because you are outside Giants' Foundry. Return inside and check the sword before restarting."); return; }
		if (storeTick < 0)
		{
			storeTick = current.tick;
			pickupPending = !current.hasPreform() && ("Pick-up:44777".equals(lastAction)
				|| "Take-preform:44778".equals(lastAction));
			storeRequired = current.hasPreform() || pickupPending;
		}
		storeRequired |= current.hasPreform();
		if (current.hasPreform()) pickupPending = false;
		if (FoundryData.stoppedSafely(current.hasPreform(), current.bit(13947) == 1,
			pickupPending, storeRequired, current.tick - storeTick))
		{
			if (recoveryReason != null) fail(recoveryReason + (current.bit(13947) == 1 ? " Your sword was safely stored." : " You are not carrying a sword."));
			else
			{
				complete = true;
				setStatus(current.bit(13947) == 1 ? "Stopped; preform stored" : "Stopped; materials preserved");
			}
			return;
		}
		if (current.tick - storeTick > 30) { fail("Could not confirm that the sword was stored. Check the storage area and your inventory before restarting."); return; }
		if (current.hasPreform()) object(44778, "Store-preform");
	}

	private void recover(String message)
	{
		if (current != null && current.hasPreform() && storeTick < 0) recoveryReason = message;
		else fail(message);
	}

	private void setStatus(String message)
	{
		status = message;
		Microbot.status = "Giants' Foundry: " + message;
	}

	String getStatus()
	{
		return !complete && error == null && stopRequested ? "Storing preform and stopping" : status;
	}

	Snapshot latestSnapshot()
	{
		return latest;
	}
}
