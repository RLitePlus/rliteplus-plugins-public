package net.runelite.client.plugins.microbot.huntersrumours;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.TileItem;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spells;
import net.runelite.client.plugins.microbot.util.magic.Spell;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;

final class HuntersRumoursScript extends Script
{
	private Future<?> input;
	private final AtomicLong inputGeneration = new AtomicLong();
	private static final WorldPoint GUILD = new WorldPoint(1559, 9452, 0);
	private static final WorldPoint FALCONRY = new WorldPoint(2373, 3604, 0);
	private static final WorldPoint HUNTING_AREA = new WorldPoint(2378, 3596, 0);
	private volatile boolean travelling;
	private WorldPoint travelDestination;
	private String pendingEffect;
	private final FalconryHunt hunt = new FalconryHunt();
	private final ButterflyHunt butterfly = new ButterflyHunt();
	private final GoatPit goats = new GoatPit();
	private WorldPoint goatApproach;
	private int goatBankWeapon = -1;
	private volatile NPC butterflyTarget;
	private volatile HerbiboarTrail.Receipt herbiboarReceipt;
	private int[] trackingBefore;
	private int trackingExperience;
	private int trackingProducts;
	private boolean trackingAttack;
	private boolean atFalconry;
	private boolean falconryBanking;
	private volatile boolean recoveryTalkSubmitted;
	private boolean releasing;
	private boolean atHuntingArea;
	private boolean handingIn;
	private int handInPart = -1;
	private int beforeCount = -1;
	private int beforeSacks = -1;
	private boolean handInSubmitted;
	private boolean handInAcknowledged;
	private boolean countRead;
	private boolean bankArrived;
	private int bankEpoch = -1;
	private int logsTarget = -1;
	private int logId = 1511;
	private boolean trapsPrepared;
	private volatile boolean teleportsPrepared;
	private static final WorldPoint DEADFALL_AREA = new WorldPoint(2721, 3791, 0);
	private boolean atDeadfall;
	private final Map<WorldPoint, DeadfallHunt> deadfalls = new java.util.concurrent.ConcurrentHashMap<>();
	private int verifiedCatches;
	private enum PitPhase { SETUP, SELECT, TEASING, GUIDE, CROSSING, HARVEST, RESUPPLY, RETREAT, CLEANUP }
	private PitPhase pitPhase = PitPhase.SETUP;
	private final PitTrap[] pitTraps = new PitTrap[5];
	private int pitGuideSteps;
	private PitLure pitLure;
	private int pitSlot = 1;
	private int pitSwitches;
	private WorldPoint pitWaypoint;
	private String pitStopReason;


	private final Map<WorldPoint, NetTrapHunt> netTraps = new LinkedHashMap<>();
	private final Map<WorldPoint, BoxTrapHunt> boxTraps = new LinkedHashMap<>();
	private WorldPoint boxDestination;
	private boolean snareBanking;
	private boolean atNetArea;
	private int netLootId;
	private int netLootCount;
	private int netLootBankBefore = -1;
	private boolean collectingTools;
	private boolean collectingLogs;
	private static final WorldPoint LOG_SUPPLY_APPROACH = new WorldPoint(2959, 3204, 0);
	private static final WorldPoint LOG_SUPPLY = new WorldPoint(2959, 3205, 0);
	private boolean collectingBoxes;
	private static final WorldPoint BOX_CRATE = new WorldPoint(1505, 3436, 0);
	private static final WorldPoint BOX_CRATE_APPROACH = new WorldPoint(1505, 3435, 0);
	private int supplyTarget;
	private int supplyLocation;
	private static final WorldPoint ROPE_AREA = new WorldPoint(1569, 3123, 0);
	private static final WorldPoint NET_SUPPLY_AREA = new WorldPoint(3244, 3157, 0);
	private static final WorldPoint RED_NET_AREA = new WorldPoint(2476, 3240, 0);
	private static final WorldPoint ORANGE_NET_AREA = new WorldPoint(3111, 2519, 0);

	private TrapRecovery trapRecovery;
	private boolean restoringTraps;
	private java.util.function.Consumer<String> saveTrapRecovery = value -> { };

	void restoreTraps(String value, java.util.function.Consumer<String> save)
	{
		trapRecovery = TrapRecovery.restore(value);
		restoringTraps = trapRecovery != null;
		saveTrapRecovery = java.util.Objects.requireNonNull(save);
	}

	boolean restoringTraps()
	{
		return restoringTraps;
	}

	private Runnable checkpointPlacement(HuntersRumoursRuntime runtime, RumourAssignment.Creature creature, WorldPoint site, Runnable action)
	{
		if (action == null) return null;
		long generation = inputGeneration.get();
		return () ->
		{
			boolean saved = Microbot.getClientThread().runOnClientThreadOptional(() ->
			{
				if (generation != inputGeneration.get() || !runtime.mayAct() || Microbot.pauseAllScripts.get()) return false;
				Client client = Microbot.getClient();
				if (client.getGameState() != GameState.LOGGED_IN || client.getItemContainer(InventoryID.INVENTORY) == null) return false;
				if (trapRecovery == null)
				{
					boolean net = creature.netCatchId() > 0;
					int tool = net ? 303 : creature == RumourAssignment.Creature.TROPICAL_WAGTAIL ? 10006 : 10008;
					trapRecovery = new TrapRecovery(client.getWorld(), creature, itemCount(tool), net ? itemCount(954) : 0, System.currentTimeMillis());
				}
				if (trapRecovery.world != client.getWorld() || trapRecovery.creature != creature)
					throw new IllegalStateException("Unresolved traps from another task");
				trapRecovery.placed(site, System.currentTimeMillis());
				saveTrapRecovery.accept(trapRecovery.encode());
				return true;
			}).orElse(false);
			if (saved && generation == inputGeneration.get() && !Thread.currentThread().isInterrupted()
				&& runtime.mayAct() && !Microbot.pauseAllScripts.get()) action.run();
		};
	}

	private void trapCleared(WorldPoint site)
	{
		if (trapRecovery == null) return;
		trapRecovery.sites.remove(site);
		if (trapRecovery.sites.isEmpty()) trapRecovery = null;
		saveTrapRecovery.accept(trapRecovery == null ? "" : trapRecovery.encode());
	}

	private static WorldPoint trapArea(RumourAssignment.Creature creature)
	{
		switch (creature)
		{
			case TECU_SALAMANDER: return new WorldPoint(1474, 3094, 0);
			case SWAMP_LIZARD: return new WorldPoint(3535, 3446, 0);
			case RED_SALAMANDER: return RED_NET_AREA;
			case ORANGE_SALAMANDER: return ORANGE_NET_AREA;
			case TROPICAL_WAGTAIL: return new WorldPoint(2513, 2913, 0);
			case EMBERTAILED_JERBOA: return new WorldPoint(1518, 3046, 0);
			case RED_CHINCHOMPA: return new WorldPoint(2501, 2906, 0);
			case GREY_CHINCHOMPA: return new WorldPoint(2339, 3593, 0);
			default: throw new IllegalArgumentException("No trap area for this creature");
		}
	}

	private Runnable recoverTraps(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		if (!runtime.mayAct() || Microbot.pauseAllScripts.get() || client.getGameState() != GameState.LOGGED_IN
			|| client.getLocalPlayer() == null || client.getItemContainer(InventoryID.INVENTORY) == null) return null;
		String blocker = trapRecovery.blocker(client.getWorld(), System.currentTimeMillis());
		if (blocker != null)
		{
			runtime.block(blocker);
			return null;
		}
		WorldPoint position = client.getLocalPlayer().getWorldLocation();
		if (travelling || trapRecovery.sites.stream().anyMatch(site -> site.distanceTo(position) > 32))
			return travelStep(runtime, trapArea(trapRecovery.creature));
		int tick = client.getTickCount();
		runtime.taskState(HuntersRumoursRuntime.State.RECOVERING_TRAPS);
		int tool = trapRecovery.toolId();
		Rs2TileItemModel dropped = Microbot.getRs2TileItemCache().query().withIds(tool, 954)
			.where(item -> item.getOwnership() == TileItem.OWNERSHIP_SELF && trapRecovery.nearby(item.getWorldLocation())
				&& (item.getId() == tool ? itemCount(tool) < trapRecovery.tools
					: trapRecovery.ropes > 0 && itemCount(954) < trapRecovery.ropes)).nearest();
		if (dropped != null)
		{
			if (huntingBusy()) return null;
			if (HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY)) < 1)
			{
				runtime.block("Free an inventory slot to recover your trap tools, then restart.");
				return null;
			}
			return trapAction(runtime, dropped.getWorldLocation(),
				awaitEffect(runtime, "restart-trap:" + dropped.getWorldLocation() + ":" + dropped.getId() + ":" + itemCount(dropped.getId()), tick, dropped::pickup));
		}
		boolean occupied = Microbot.getRs2TileObjectCache().query().where(object ->
		{
			if (!trapRecovery.nearby(object.getWorldLocation())) return false;
			if (trapRecovery.ropes == 0) return new BoxTrapHunt(object.getWorldLocation(), trapRecovery.creature).known(object.getId());
			NetTrapHunt trap = new NetTrapHunt(object.getWorldLocation(), trapRecovery.creature);
			return trap.knownId(object.getId()) && object.getId() != trap.emptyId();
		}).first() != null;
		if (occupied || huntingBusy()) return null;
		if (!trapRecovery.returned(itemCount(tool), itemCount(954)))
		{
			WorldPoint site = trapRecovery.sites.iterator().next();
			if (travelling || position.distanceTo(site) > 3) return approachTrapSite(runtime, site);
			if (!emptyTrapSite(trapRecovery.creature, site)) return null;
			trapCleared(site);
			if (trapRecovery != null) return null;
		}
		saveTrapRecovery.accept("");
		trapRecovery = null;
		restoringTraps = false;
		pendingEffect = null;
		runtime.recoveringTraps(false);
		return null;
	}

	private boolean emptyTrapSite(RumourAssignment.Creature creature, WorldPoint site)
	{
		Client client = Microbot.getClient();
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null
			|| client.getLocalPlayer().getWorldLocation().distanceTo(site) > 3
			|| client.getTopLevelWorldView() == null || client.getTopLevelWorldView().getScene() == null
			|| net.runelite.client.plugins.microbot.util.tile.Rs2Tile.getTile(site.getX(), site.getY()) == null) return false;
		boolean net = creature.netCatchId() > 0;
		int tool = net ? 303 : creature == RumourAssignment.Creature.TROPICAL_WAGTAIL ? 10006 : 10008;
		if (Microbot.getRs2TileItemCache().query().withIds(tool, net ? 954 : tool)
			.where(item -> item.getWorldLocation().distanceTo(site) <= (net ? 1 : 0)).first() != null) return false;
		NetTrapHunt netTrap = net ? new NetTrapHunt(site, creature) : null;
		if (Microbot.getRs2TileObjectCache().query().where(object ->
			object.getWorldLocation().distanceTo(site) <= (net ? 1 : 0)
				&& (net ? netTrap.knownId(object.getId()) && object.getId() != netTrap.emptyId()
					: new BoxTrapHunt(site, creature).known(object.getId()))).first() != null) return false;
		return !net || Microbot.getRs2TileObjectCache().query().withId(netTrap.emptyId())
			.where(object -> object.getWorldLocation().equals(site)).first() != null;
	}

	private Runnable inspectMissingTrap(HuntersRumoursRuntime runtime, RumourAssignment.Creature creature, WorldPoint site)
	{
		if (huntingBusy()) return null;
		if (travelling || Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(site) > 3)
			return approachTrapSite(runtime, site);
		if (!emptyTrapSite(creature, site)) return null;
		netTraps.remove(site);
		boxTraps.remove(site);
		trapCleared(site);
		pendingEffect = null;
		if (netTraps.isEmpty() && boxTraps.isEmpty())
		{
			trapsPrepared = bankArrived = atNetArea = teleportsPrepared = false;
			bankEpoch = -1;
		}
		return null;
	}

	private Runnable approachTrapSite(HuntersRumoursRuntime runtime, WorldPoint site)
	{
		WorldPoint approach = net.runelite.client.plugins.microbot.util.tile.Rs2Tile.getNearestWalkableTile(site);
		if (approach != null) return travelStep(runtime, approach);
		runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_TARGET);
		return null;
	}

	private Runnable trapAction(HuntersRumoursRuntime runtime, WorldPoint site, Runnable action)
	{
		if (action == null || huntingBusy()) return null;
		if (travelling || Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(site) > 1)
			return approachTrapSite(runtime, site);
		return action;
	}

	void prepareTask(HuntersRumoursRuntime runtime)
	{
		submitInput(runtime, false);
	}

	private void submitInput(HuntersRumoursRuntime runtime, boolean assignment)
	{
		if (input != null && !input.isDone()) return;
		long generation = inputGeneration.get();
		input = scheduledExecutorService.submit(() ->
		{
			try
			{
				Runnable action = Microbot.getClientThread().runOnClientThreadOptional(() ->
					prepareInput(runtime, generation, assignment)).orElse(null);
				if (action != null && generation == inputGeneration.get() && !Thread.currentThread().isInterrupted()
					&& !Microbot.pauseAllScripts.get() && (assignment ? runtime.mayRequestAssignment() : runtime.mayAct())) action.run();
			}
			catch (RuntimeException exception)
			{
				if (generation == inputGeneration.get() && !Thread.currentThread().isInterrupted()) runtime.block(assignment
					? "The Guild interaction failed. Check the game state and restart Hunters' Rumours."
					: "Hunting preparation failed. Check the game state and restart.");
			}
		});
	}

	private Runnable prepareInput(HuntersRumoursRuntime runtime, long generation, boolean assignment)
	{
		if (generation != inputGeneration.get()) return null;
		return restoringTraps ? recoverTraps(runtime) : assignment ? prepareAssignmentRequest(runtime) : taskStep(runtime);
	}

	private Runnable taskStep(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		if (!runtime.mayAct() || Microbot.pauseAllScripts.get() || client.getGameState() != GameState.LOGGED_IN
			|| client.getLocalPlayer() == null) return null;
		RumourAssignment assignment = runtime.assignment();
		if (assignment == null) return null;
		if (assignment.hunter != runtime.selectedHunter())
		{
			if (runtime.maySwitchHunter()) return switchHunter(runtime);
			runtime.block("The active rumour differs from the selected hunter. Check the Hunter setting and restart.");
			return null;
		}
		String partBlocker = HuntingSupplies.partBlocker(client.getItemContainer(InventoryID.INVENTORY), assignment.creature.rarePartId());
		if (partBlocker != null)
		{
			runtime.block(partBlocker);
			return null;
		}
		if (netLootId > 0) return bankNetLoot(runtime);
		if (hunt.recovering()) return recoverFalcon(runtime);
		if (handingIn) return handInStep(runtime);
		if (assignment.creature.rarePartId() > 0 && itemCount(assignment.creature.rarePartId()) > 0
			&& deadfalls.isEmpty() && netTraps.isEmpty() && boxTraps.isEmpty() && !hasOwnedPits()
			&& (assignment.creature.keptNetCatchId() == 0 || itemCount(assignment.creature.keptNetCatchId()) == 0)
			&& assignment.creature != RumourAssignment.Creature.WYRMSCRAIG_GOAT
			&& assignment.creature != RumourAssignment.Creature.HERBIBOAR
			&& pitPhase == PitPhase.SETUP && !hunt.awaitingCatch() && !butterfly.pending() && trackingBefore == null)
		{
			if (assignment.creature.netCatchId() > 0 && itemCount(assignment.creature.netCatchId()) > 0)
				return releaseNetCatch(runtime, assignment.creature.netCatchId());
			return handInStep(runtime);
		}
		if (!countRead || (Rs2Dialogue.isInDialogue() && RumourHandIn.countDialogue(dialogueText())))
		{
			if (hasFalcon(client) || (releasing && Rs2Dialogue.isInDialogue())) return releaseFalcon(runtime);
			if (travelling) return travelStep(runtime, GUILD);
			return readCountBeforeAssignment(runtime);
		}
		String levelBlocker = assignment.levelBlocker(client.getWorldType().contains(WorldType.MEMBERS), client.getRealSkillLevel(Skill.HUNTER));
		if (levelBlocker != null)
		{
			runtime.block(levelBlocker);
			return null;
		}
		if (netTraps.isEmpty() && assignment.creature.keptNetCatchId() > 0
			&& itemCount(assignment.creature.keptNetCatchId()) > 0) return bankNetLoot(runtime);
		if (assignment.creature == RumourAssignment.Creature.WYRMSCRAIG_GOAT)
		{
			if (client.getRealSkillLevel(Skill.SAILING) < 62
				|| net.runelite.api.Quest.SHEEP_HERDER.getState(client) != net.runelite.api.QuestState.FINISHED)
			{
				runtime.block("Wyrmscraig goats require 62 Sailing and completed Sheep Herder. Meet these requirements, then restart.");
				return null;
			}
			if (net.runelite.api.Quest.FALLEN_FROM_GRACE.getState(client) != net.runelite.api.QuestState.FINISHED)
			{
				runtime.block("Supported Wyrmscraig travel requires completed Fallen From Grace for the Necklace of passage destination. Complete it, then restart.");
				return null;
			}
			if (Rs2Dialogue.hasContinue() && GoatPit.emptyDialogue(Rs2Dialogue.getDialogueText())
				&& client.getVarbitValue(15725) == 0 && !goats.pending())
				return awaitEffect(runtime, "goat-empty-dialogue", client.getTickCount(), Rs2Dialogue::clickContinue);
			return prepareTraps(runtime, false);
		}
		if (assignment.creature == RumourAssignment.Creature.HERBIBOAR)
		{
			if (net.runelite.api.Quest.BONE_VOYAGE.getState(client) != net.runelite.api.QuestState.FINISHED
				|| client.getRealSkillLevel(Skill.HERBLORE) < 31)
			{
				runtime.block("Herbiboar requires completed Bone Voyage and 31 Herblore. Meet these requirements, then restart.");
				return null;
			}
			return prepareTraps(runtime, false);
		}
		if (assignment.creature.netCatchId() > 0)
		{
			if (assignment.creature == RumourAssignment.Creature.SWAMP_LIZARD && client.getVarpValue(VarPlayerID.PRIESTPERIL) < 60)
			{
				runtime.block("Swamp lizard hunting requires completed Priest in Peril. Complete the quest, then restart.");
				return null;
			}
			if (assignment.creature == RumourAssignment.Creature.ORANGE_SALAMANDER && client.getVarbitValue(VarbitID.TT) < 6)
			{
				runtime.block("Orange salamander hunting currently uses the Great Conch. Give Floopa the bandages during Troubled Tortugans to unlock access, then restart.");
				return null;
			}
			return prepareTraps(runtime, true);
		}
		if (assignment.creature == RumourAssignment.Creature.RAZOR_BACKED_KEBBIT || assignment.creature == RumourAssignment.Creature.TROPICAL_WAGTAIL || assignment.creature.butterflyId() > 0) return prepareTraps(runtime, false);
		if (assignment.creature.boxCatchId() > 0)
		{
			if (client.getVarbitValue(VarbitID.EAGLEPEAK_QUEST) < 40)
			{
				runtime.block("Box trapping currently requires completed Eagles' Peak. Complete the quest, then restart.");
				return null;
			}
			return prepareTraps(runtime, false);
		}
		if (assignment.creature.deadfallCatchId() > 0
			|| HuntingSupplies.pit(assignment.creature)) return prepareTraps(runtime, false);
		if (assignment.creature.falconPreyId() == 0)
		{
			runtime.block("This assignment's hunting method is not implemented yet. Keep the assignment and stop here.");
			return null;
		}
		if (hunt.awaitingCatch()) return huntStep(runtime, assignment.creature, client.getTickCount());
		if (falconryBanking) return prepareFalconrySupplies(runtime);
		String blocker = FalconryPreparation.blocker(client.getItemContainer(InventoryID.INVENTORY),
			client.getItemContainer(InventoryID.EQUIPMENT));
		if (blocker != null)
		{
			if (client.getItemContainer(InventoryID.INVENTORY) == null
				|| client.getItemContainer(InventoryID.EQUIPMENT) == null || hunt.inFlight())
			{
				runtime.block(blocker);
				return null;
			}
			runtime.finishRoute();
			travelling = bankArrived = trapsPrepared = teleportsPrepared = false;
			bankEpoch = -1;
			pendingEffect = null;
			falconryBanking = true;
			return prepareFalconrySupplies(runtime);
		}
		int tick = client.getTickCount();
		if (!travelling && Rs2Dialogue.isInDialogue())
		{
			if (rentalConfirmation(client))
				return awaitEffect(runtime, "rental-confirmation", tick, Rs2Dialogue::clickContinue);
			Widget name = client.getWidget(InterfaceID.ChatLeft.NAME);
			Widget text = client.getWidget(InterfaceID.ChatLeft.TEXT);
			if (name != null && text != null && !text.isHidden()
				&& RumourAssignment.fromDialogue(name.getText(), text.getText()) != null && Rs2Dialogue.hasContinue())
			{
				return awaitEffect(runtime, "dialogue:" + text.getText(), tick, Rs2Dialogue::clickContinue);
			}
			runtime.block("Finish the open dialogue without changing stored rumours, then restart.");
			return null;
		}
		if (!atFalconry && (travelling || client.getLocalPlayer().getWorldLocation().distanceTo(FALCONRY) > 3))
		{
			runtime.taskState(HuntersRumoursRuntime.State.TRAVELLING_FALCONRY);
			return travelStep(runtime, FALCONRY);
		}
		runtime.taskState(HuntersRumoursRuntime.State.PREPARING_FALCONRY);
		for (EquipmentInventorySlot slot : FalconryPreparation.CLEAR_SLOTS)
		{
			Item item = client.getItemContainer(InventoryID.EQUIPMENT).getItem(slot.getSlotIdx());
			if (item == null || item.getId() < 0 || item.getId() == 10023 || item.getId() == 10024) continue;
			if (!Rs2Tab.isCurrentTab(InterfaceTab.EQUIPMENT))
				return awaitEffect(runtime, "equipment-tab", tick, () -> Rs2Tab.switchTo(InterfaceTab.EQUIPMENT));
			int component = slot == EquipmentInventorySlot.WEAPON ? InterfaceID.Wornitems.SLOT3
				: slot == EquipmentInventorySlot.SHIELD ? InterfaceID.Wornitems.SLOT5 : InterfaceID.Wornitems.SLOT9;
			Widget widget = client.getWidget(component);
			if (widget == null || widget.isHidden()) return null;
			NewMenuEntry entry = new NewMenuEntry().param0(-1).param1(component).opcode(MenuAction.CC_OP.getId())
				.identifier(1).itemId(-1).option("Remove");
			Rectangle bounds = widget.getBounds();
			return awaitEffect(runtime, "remove:" + slot + ":" + item.getId(), tick, () -> Microbot.doInvoke(entry, bounds));
		}
		pendingEffect = null;
		atFalconry = true;
		runtime.taskState(HuntersRumoursRuntime.State.READY_TO_HUNT);
		return huntStep(runtime, assignment.creature, tick);
	}

	private Runnable huntStep(HuntersRumoursRuntime runtime, RumourAssignment.Creature creature, int tick)
	{
		Client client = Microbot.getClient();
		Item weapon = client.getItemContainer(InventoryID.EQUIPMENT).getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		boolean held = weapon != null && weapon.getId() == 10024;
		if (held && !atHuntingArea)
		{
			if (travelling || client.getLocalPlayer().getWorldLocation().distanceTo(HUNTING_AREA) > 2)
			{
				runtime.taskState(HuntersRumoursRuntime.State.TRAVELLING_FALCONRY);
				return travelStep(runtime, HUNTING_AREA);
			}
			atHuntingArea = true;
		}
		int products = 0;
		for (Item item : client.getItemContainer(InventoryID.INVENTORY).getItems())
		{
			if (item != null && (item.getId() == 526 || item.getId() == 10127 || item.getId() == 29107
				|| item.getId() == 10115 || item.getId() == 10125 || item.getId() == 29223)) products += item.getQuantity();
		}
		NPC arrow = client.getHintArrowNpc();
		boolean owned = arrow != null && arrow.getId() == creature.falconCatchId();
		Rs2NpcModel target = Microbot.getRs2NpcCache().query().withId(creature.falconPreyId()).nearest();
		FalconryHunt.Action action = hunt.next(tick, held, owned, products);
		if (action == FalconryHunt.Action.VERIFIED)
		{
			verifiedCatches++;
			runtime.taskState(HuntersRumoursRuntime.State.CATCH_VERIFIED);
			return null;
		}
		if (action == FalconryHunt.Action.BLOCKED)
		{
			runtime.block("The falcon did not return with a verified catch. Recover it from Matthias, then restart.");
			return null;
		}
		if (action == FalconryHunt.Action.WAIT || huntingBusy()) return null;
		if (action == FalconryHunt.Action.CATCH && target == null)
		{
			runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_TARGET);
			return null;
		}
		int productsBefore = products;
		if (action == FalconryHunt.Action.RETRIEVE)
			return onDispatch(runtime, () -> hunt.submitted(action, tick, productsBefore), npcAction(arrow, "Retrieve"));
		Rs2NpcModel model = action == FalconryHunt.Action.RENT
			? Microbot.getRs2NpcCache().query().withIds(1340, 1341).nearest()
			: target;
		if (model == null)
		{
			runtime.block("The falconer or assigned kebbit is unavailable. Check the falconry area and restart.");
			return null;
		}
		return onDispatch(runtime, () -> hunt.submitted(action, tick, productsBefore),
			npcAction(model.getNpc(), action == FalconryHunt.Action.RENT ? "Quick-falcon" : "Catch"));
	}

	private Runnable prepareFalconrySupplies(HuntersRumoursRuntime runtime)
	{
		if (hasFalcon(Microbot.getClient()) || (releasing && Rs2Dialogue.isInDialogue())) return releaseFalcon(runtime);
		releasing = false;
		return prepareTraps(runtime, false);
	}

	private boolean bankReady()
	{
		return !travelling && Rs2Bank.isOpen() && bankEpoch >= 0 && Rs2Bank.getBankLiveEpoch() > bankEpoch
			&& Microbot.getClient().getItemContainer(InventoryID.BANK) != null;
	}

	private Runnable prepareBank(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		if (travelling) return travelStep(runtime, null);
		if (!Rs2Bank.isOpen())
		{
			if (!bankArrived) return travelStep(runtime, null);
			Rs2TileObjectModel bank = Microbot.getRs2TileObjectCache().query().within(8)
				.where(object -> bankOption(object.getObjectComposition()) != null).nearest();
			if (bank == null)
			{
				runtime.block("No bank object is reachable at the bank destination. Move beside a bank chest or booth and restart.");
				return null;
			}
			if (bankEpoch < 0) bankEpoch = Rs2Bank.getBankLiveEpoch();
			return awaitEffect(runtime, "open-bank", tick, objectAction(bank, bankOption(bank.getObjectComposition())));
		}
		if (bankEpoch < 0) bankEpoch = 0;
		if (Rs2Bank.getBankLiveEpoch() <= bankEpoch || client.getItemContainer(InventoryID.BANK) == null)
			return awaitEffect(runtime, "bank-snapshot", tick, null);
		return null;
	}

	private Runnable prepareTraps(HuntersRumoursRuntime runtime, boolean net)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		RumourAssignment.Creature creature = runtime.assignment().creature;
		boolean flutter = creature.butterflyId() > 0;
		boolean tracking = creature == RumourAssignment.Creature.RAZOR_BACKED_KEBBIT;
		boolean goat = creature == RumourAssignment.Creature.WYRMSCRAIG_GOAT;
		boolean herbiboar = creature == RumourAssignment.Creature.HERBIBOAR;
		boolean bird = creature == RumourAssignment.Creature.TROPICAL_WAGTAIL;
		boolean box = creature.boxCatchId() > 0 || bird;
		int boxTool = bird ? 10006 : 10008;
		boolean pit = HuntingSupplies.pit(creature);
		boolean falconry = creature.falconPreyId() > 0;
		if (collectingTools || collectingLogs) return collectFreeTools(runtime);
		if (collectingBoxes) return collectFreeBox(runtime);
		if (trapsPrepared)
		{
			if (Rs2Bank.isOpen()) return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
			if (falconry)
			{
				falconryBanking = trapsPrepared = atFalconry = atHuntingArea = false;
				pendingEffect = null;
				return null;
			}
			runtime.taskState(pit ? HuntersRumoursRuntime.State.PIT_PREPARED : bird ? HuntersRumoursRuntime.State.SNARE_PREPARED : box ? HuntersRumoursRuntime.State.BOX_PREPARED : net ? HuntersRumoursRuntime.State.NET_PREPARED : HuntersRumoursRuntime.State.DEADFALL_PREPARED);
			if (goat) return goatStep(runtime);
			if (herbiboar) return herbiboarStep(runtime);
			if (tracking) return trackingStep(runtime);
			if (flutter) return butterflyStep(runtime);
			if (pit) return pitStep(runtime);
			if (net) return netStep(runtime, itemCount(creature.rarePartId()) > 0
				|| (creature.keptNetCatchId() > 0 && itemCount(creature.keptNetCatchId()) > 0));
			return box ? boxStep(runtime, itemCount(creature.rarePartId()) > 0) : deadfallStep(runtime, itemCount(creature.rarePartId()) > 0);
		}
		runtime.taskState(herbiboar ? HuntersRumoursRuntime.State.PREPARING_HERBIBOAR : goat ? HuntersRumoursRuntime.State.PREPARING_GOATS : tracking ? HuntersRumoursRuntime.State.PREPARING_TRACKING : flutter ? HuntersRumoursRuntime.State.PREPARING_BUTTERFLIES : falconry ? HuntersRumoursRuntime.State.PREPARING_FALCONRY : pit ? HuntersRumoursRuntime.State.PREPARING_PITS : bird ? HuntersRumoursRuntime.State.PREPARING_SNARES : box ? HuntersRumoursRuntime.State.PREPARING_BOXES : net ? HuntersRumoursRuntime.State.PREPARING_NETS : HuntersRumoursRuntime.State.PREPARING_DEADFALL);
		if (Rs2Dialogue.hasContinue())
		{
			Widget speaker = client.getWidget(InterfaceID.ChatLeft.NAME);
			if (speaker != null && !speaker.isHidden() && RumourAssignment.fromDialogue(speaker.getText(), dialogueText()) != null)
				return awaitEffect(runtime, "task-dialogue:" + dialogueText(), tick, Rs2Dialogue::clickContinue);
			runtime.block("Finish the open dialogue before banking for hunting supplies, then restart.");
			return null;
		}
		if (!bankReady()) return prepareBank(runtime);
		for (Item item : client.getItemContainer(InventoryID.INVENTORY).getItems())
		{
			if (item != null && HuntingSupplies.bankBeforeTraps(item.getId(), creature))
				return awaitEffect(runtime, "deposit:" + item.getId() + ":" + item.getQuantity(), tick,
					() -> Rs2Bank.depositAll(item.getId()));
		}
		int traps = pit ? HuntingSupplies.pitTrapLimit(creature, client.getRealSkillLevel(Skill.HUNTER))
			: HuntingSupplies.netTrapLimit(client.getRealSkillLevel(Skill.HUNTER));
		if (!teleportsPrepared)
		{
			if (!Rs2Bank.hasWithdrawAsItem()) return awaitEffect(runtime, "withdraw-items", tick, Rs2Bank::setWithdrawAsItem);
			int reserved = falconry ? FalconryPreparation.requiredSlots(client.getItemContainer(InventoryID.EQUIPMENT)) + (itemCount(995) == 0 ? 1 : 0)
				: HuntingSupplies.trapSlots(client.getItemContainer(InventoryID.INVENTORY),
					client.getItemContainer(InventoryID.BANK), creature, traps);
			Map<Integer, Integer> travel = HuntingTravel.plan(client.getItemContainer(InventoryID.INVENTORY),
				client.getItemContainer(InventoryID.BANK), client.getItemContainer(InventoryID.EQUIPMENT), reserved,
				client.getVarbitValue(VarbitID.FAIRYRING_PERMISSION) == 2
					&& client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE) != 1,
				HuntersRumoursScript::travelUnlocked, spell -> travelSpellAvailable(client, spell));
			if (travel == null)
			{
				runtime.block("Travel inventory, equipment or bank state is unavailable. Reopen the bank and restart.");
				return null;
			}
			for (Item item : client.getItemContainer(InventoryID.INVENTORY).getItems())
				if (item != null && HuntingTravel.managed(item.getId()) && !travel.containsKey(item.getId()))
					return awaitEffect(runtime, "bank-travel:" + item.getId() + ":" + item.getQuantity(), tick,
						() -> Rs2Bank.depositAll(item.getId()));
			for (Map.Entry<Integer, Integer> supply : travel.entrySet())
			{
				int id = supply.getKey(), count = itemCount(id);
				if (count < supply.getValue())
					return awaitEffect(runtime, "withdraw-travel:" + id + ":" + count, tick,
						() -> Rs2Bank.withdrawX(id, supply.getValue() - count));
			}
			teleportsPrepared = true;
			pendingEffect = null;
		}
		if (herbiboar)
		{
			int deficit = HuntingSupplies.herbiboarSlots(client.getItemContainer(InventoryID.INVENTORY),
				client.getItemContainer(InventoryID.EQUIPMENT)) - HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY));
			if (deficit > 0)
			{
				runtime.block("Free " + deficit + " more inventory slots for Herbiboar herbs, the rare part and a possible pet, then restart.");
				return null;
			}
			trapsPrepared = true;
			return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
		}
		if (goat) return prepareGoatSupplies(runtime);
		if (tracking)
		{
			ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
			if (equipment == null)
			{
				runtime.block("Equipment is unavailable. Reopen your equipment and restart.");
				return null;
			}
			int owned = HuntingSupplies.count(equipment, 10150) + itemCount(10150);
			if (owned == 0 && HuntingSupplies.count(client.getItemContainer(InventoryID.BANK), 10150) == 0)
			{
				runtime.block("Missing 1 Noose wand in equipment, inventory and bank. Add a Noose wand, then restart.");
				return null;
			}
			int needed = HuntingSupplies.count(equipment, 10150) > 0 ? 4 : 6;
			int deficit = needed - HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY));
			if (deficit > 0)
			{
				runtime.block("Free " + deficit + " more inventory slots for the Noose wand and catch outputs, then restart.");
				return null;
			}
			if (owned == 0)
			{
				if (!Rs2Bank.hasWithdrawAsItem()) return awaitEffect(runtime, "withdraw-items", tick, Rs2Bank::setWithdrawAsItem);
				return awaitEffect(runtime, "withdraw-noose-wand", tick, () -> Rs2Bank.withdrawOne(10150));
			}
			trapsPrepared = true;
			return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
		}

		if (flutter)
		{
			ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
			if (equipment == null)
			{
				runtime.block("Equipment is unavailable. Reopen the equipment tab and restart.");
				return null;
			}
			boolean barehanded = client.getBoostedSkillLevel(Skill.HUNTER) >= creature.butterflyLevel() + 10;
			int netTool = 0;
			if (!barehanded)
			{
				for (int id : new int[]{11259, 10010})
					if (HuntingSupplies.count(equipment, id) + itemCount(id)
						+ HuntingSupplies.count(client.getItemContainer(InventoryID.BANK), id) > 0) { netTool = id; break; }
				if (netTool == 0)
				{
					runtime.block("Missing 1 Butterfly net in equipment, inventory and bank. Add a Butterfly net or Magic butterfly net, then restart.");
					return null;
				}
			}
			int selectedNet = netTool;
			for (int id : new int[]{10010, 11259})
				if (id != selectedNet && itemCount(id) > 0)
					return awaitEffect(runtime, "bank-unused-net:" + id, tick, () -> Rs2Bank.depositAll(id));
			int needed = 1 + (selectedNet > 0 && HuntingSupplies.count(equipment, selectedNet) == 0 && itemCount(selectedNet) == 0 ? 1 : 0);
			int missingSlots = needed - HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY));
			if (missingSlots > 0)
			{
				runtime.block("Free " + missingSlots + " more inventory slots for butterfly equipment and the rare wing, then restart.");
				return null;
			}
			if (selectedNet > 0 && HuntingSupplies.count(equipment, selectedNet) == 0 && itemCount(selectedNet) == 0)
			{
				if (!Rs2Bank.hasWithdrawAsItem()) return awaitEffect(runtime, "withdraw-items", tick, Rs2Bank::setWithdrawAsItem);
				return awaitEffect(runtime, "withdraw-butterfly-net", tick, () -> Rs2Bank.withdrawOne(selectedNet));
			}
			trapsPrepared = true;
			return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
		}
		if (falconry)
		{
			String blocker = FalconryPreparation.bankBlocker(client.getItemContainer(InventoryID.INVENTORY),
				client.getItemContainer(InventoryID.EQUIPMENT), client.getItemContainer(InventoryID.BANK));
			if (blocker != null)
			{
				runtime.block(blocker);
				return null;
			}
			int fee = FalconryPreparation.feeNeeded(client.getItemContainer(InventoryID.INVENTORY), client.getItemContainer(InventoryID.EQUIPMENT));
			if (fee > 0)
				return awaitEffect(runtime, "withdraw-falcon-fee:" + itemCount(995), tick, () -> Rs2Bank.withdrawX(995, fee));
			trapsPrepared = true;
			return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
		}
		if (box && !bird && itemCount(10008) + HuntingSupplies.count(client.getItemContainer(InventoryID.BANK), 10008) < traps)
		{
			if (itemCount(10008) > 0)
				return awaitEffect(runtime, "bank-free-box:" + itemCount(10008), tick, () -> Rs2Bank.depositAll(10008));
			if (HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY)) < 1)
			{
				runtime.block("Free 1 inventory slot to collect a Box trap, then restart.");
				return null;
			}
			collectingBoxes = true;
			return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
		}
		String deficit = pit ? HuntingSupplies.pitDeficit(creature, client.getItemContainer(InventoryID.INVENTORY), client.getItemContainer(InventoryID.BANK), traps)
			: box ? HuntingSupplies.boxDeficit(client.getItemContainer(InventoryID.INVENTORY), client.getItemContainer(InventoryID.BANK), traps, creature)
			: net ? HuntingSupplies.netSpaceBlocker(client.getItemContainer(InventoryID.INVENTORY), traps)
			: HuntingSupplies.deficit(client.getItemContainer(InventoryID.INVENTORY), client.getItemContainer(InventoryID.BANK));
		if (deficit != null)
		{
			runtime.block(deficit);
			return null;
		}
		if (!Rs2Bank.hasWithdrawAsItem()) return awaitEffect(runtime, "withdraw-items", tick, Rs2Bank::setWithdrawAsItem);
		if (box)
		{
			if (itemCount(boxTool) < traps)
				return awaitEffect(runtime, "withdraw-trap:" + boxTool + ":" + itemCount(boxTool), tick, () -> Rs2Bank.withdrawOne(boxTool));
			trapsPrepared = true;
			return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
		}
		if (net)
		{
			for (int id : new int[]{954, 303})
				if (itemCount(id) < traps && HuntingSupplies.count(client.getItemContainer(InventoryID.BANK), id) > 0)
					return awaitEffect(runtime, "withdraw-trap:" + id + ":" + itemCount(id), tick,
					() -> Rs2Bank.withdrawOne(id));
			if (itemCount(954) < traps || itemCount(303) < traps)
			{
				supplyTarget = traps;
				supplyLocation = 0;
				collectingTools = true;
			}
			else trapsPrepared = true;
			return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
		}
		if (pit)
		{
			for (int[] supply : new int[][]{{10029, 1}, {385, 4}})
				if (itemCount(supply[0]) < supply[1])
					return awaitEffect(runtime, "withdraw-pit:" + supply[0] + ":" + itemCount(supply[0]), tick,
						() -> Rs2Bank.withdrawOne(supply[0]));
			if (!HuntingSupplies.knife(client.getItemContainer(InventoryID.INVENTORY)))
			{
				int knife = HuntingSupplies.count(client.getItemContainer(InventoryID.BANK), 946) > 0 ? 946 : 31043;
				return awaitEffect(runtime, "withdraw-knife", tick, () -> Rs2Bank.withdrawOne(knife));
			}
			int logs = HuntingSupplies.logs(client.getItemContainer(InventoryID.INVENTORY));
			int pitLogs = traps;
			if (logs < pitLogs)
			{
				for (int id : new int[]{1511, 1521, 1519})
					if (HuntingSupplies.count(client.getItemContainer(InventoryID.BANK), id) > (id == 1519 ? 1 : 0))
						return awaitEffect(runtime, "withdraw-pit-log:" + logs, tick, () -> Rs2Bank.withdrawOne(id));
				collectingLogs = true;
				supplyTarget = pitLogs;
				supplyLocation = 0;
				return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
			}
			trapsPrepared = true;
			return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
		}
		int free = HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY));
		if (!HuntingSupplies.knife(client.getItemContainer(InventoryID.INVENTORY)))
		{
			if (free < 4)
			{
				runtime.block("Free 4 inventory slots for a knife and catch outputs, then restart.");
				return null;
			}
			int knife = HuntingSupplies.count(client.getItemContainer(InventoryID.BANK), 946) > 0 ? 946 : 31043;
			return awaitEffect(runtime, "withdraw-knife", tick, () -> Rs2Bank.withdrawOne(knife));
		}
		if (logsTarget < 0) logId = HuntingSupplies.logId(client.getItemContainer(InventoryID.INVENTORY), client.getItemContainer(InventoryID.BANK));
		for (int id : new int[]{1511, 1521, 1519})
			if (id != logId && itemCount(id) > 0)
				return awaitEffect(runtime, "bank-unused-log:" + id, tick, () -> Rs2Bank.depositAll(id));
		int logs = itemCount(logId);
		if (logsTarget < 0) logsTarget = HuntingSupplies.deadfallLogTarget(client.getItemContainer(InventoryID.INVENTORY),
			client.getItemContainer(InventoryID.BANK), creature, logId);
		if (logs > logsTarget)
			return awaitEffect(runtime, "bank-excess-logs:" + logs, tick, () -> Rs2Bank.depositX(logId, logs - logsTarget));
		if (logsTarget < 1 || free < HuntingSupplies.deadfallOutputSlots(creature) + 1)
		{
			runtime.block("Free enough inventory space for Logs and " + (HuntingSupplies.deadfallOutputSlots(creature) + 1) + " catch outputs, then restart.");
			return null;
		}
		if (logs < logsTarget) return awaitEffect(runtime, "withdraw-logs:" + logs, tick, () -> Rs2Bank.withdrawOne(logId));
		trapsPrepared = true;
		return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
	}

	private static boolean travelUnlocked(Spell spell)
	{
		Quest quest = spell == Rs2Spells.CIVITAS_ILLA_FORTIS_TELEPORT ? Quest.TWILIGHTS_PROMISE
			: spell == Rs2Spells.ARDOUGNE_TELEPORT ? Quest.PLAGUE_CITY
			: spell == Rs2Spells.KOUREND_CASTLE_TELEPORT ? Quest.CLIENT_OF_KOUREND : null;
		return quest == null || Rs2Player.getQuestState(quest) == QuestState.FINISHED;
	}

	private static boolean travelSpellAvailable(Client client, Spell spell)
	{
		return client.getRealSkillLevel(Skill.MAGIC) >= spell.getRequiredLevel() && spell.hasRequiredSpellbook()
			&& (spell != Rs2Spells.VARROCK_TELEPORT || client.getVarbitValue(VarbitID.VARROCK_GE_TELEPORT) == 0)
			&& (spell != Rs2Spells.CAMELOT_TELEPORT || client.getVarbitValue(VarbitID.SEERS_CAMELOT_TELEPORT) == 0);
	}

	private Runnable collectFreeBox(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		runtime.taskState(HuntersRumoursRuntime.State.COLLECTING_TOOLS);
		if (Rs2Bank.isOpen()) return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
		if (itemCount(10008) > 0)
		{
			if (Rs2Dialogue.hasContinue())
			{
				if ("you find a box trap".equals(RumourAssignment.normalize(dialogueText())))
					return awaitEffect(runtime, "box-receipt", tick, Rs2Dialogue::clickContinue);
				runtime.block("Finish the open dialogue before banking the collected Box trap, then restart.");
				return null;
			}
			collectingBoxes = bankArrived = false;
			bankEpoch = -1;
			teleportsPrepared = false;
			pendingEffect = null;
			return null;
		}
		if (travelling || client.getLocalPlayer().getWorldLocation().distanceTo(BOX_CRATE_APPROACH) > 1)
			return travelStep(runtime, BOX_CRATE_APPROACH);
		Rs2TileObjectModel crate = Microbot.getRs2TileObjectCache().query().withId(29732)
			.where(object -> object.getWorldLocation().equals(BOX_CRATE)).first();
		if (crate == null)
		{
			runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_TARGET);
			return null;
		}
		return awaitEffect(runtime, "free-box", tick, objectAction(crate, "Search"));
	}

	private Runnable collectFreeTools(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		runtime.taskState(HuntersRumoursRuntime.State.COLLECTING_TOOLS);
		if (Rs2Bank.isOpen()) return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
		String space = collectingLogs
			? (HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY)) < Math.max(0, supplyTarget - HuntingSupplies.logs(client.getItemContainer(InventoryID.INVENTORY)))
				? "Free enough inventory slots to collect the remaining pit logs, then restart." : null)
			: HuntingSupplies.netSpaceBlocker(client.getItemContainer(InventoryID.INVENTORY), supplyTarget);
		if (space != null)
		{
			runtime.block(space);
			return null;
		}
		int id = collectingLogs ? 1511 : itemCount(954) < supplyTarget ? 954 : 303;
		int count = collectingLogs ? HuntingSupplies.logs(client.getItemContainer(InventoryID.INVENTORY)) : itemCount(id);
		if (collectingLogs ? count >= supplyTarget : itemCount(954) >= supplyTarget && itemCount(303) >= supplyTarget)
		{
			if (collectingLogs)
			{
				runtime.finishRoute();
				collectingLogs = travelling = bankArrived = teleportsPrepared = false;
				bankEpoch = -1;
				pendingEffect = null;
				return null;
			}
			collectingTools = false;
			trapsPrepared = true;
			pendingEffect = null;
			runtime.taskState(HuntersRumoursRuntime.State.NET_PREPARED);
			return null;
		}
		WorldPoint destination = collectingLogs ? LOG_SUPPLY_APPROACH : id == 954 ? ROPE_AREA : NET_SUPPLY_AREA;
		if (supplyLocation != id)
		{
			if (travelling || (collectingLogs ? !client.getLocalPlayer().getWorldLocation().equals(destination)
				: client.getLocalPlayer().getWorldLocation().distanceTo(destination) > 3))
				return travelStep(runtime, destination);
			supplyLocation = id;
		}
		Runnable action;
		if (collectingLogs)
		{
			Rs2TileItemModel log = Microbot.getRs2TileItemCache().query().withId(1511)
				.where(item -> item.getWorldLocation().equals(LOG_SUPPLY) && item.getOwnership() == TileItem.OWNERSHIP_NONE).first();
			if (log == null) return null;
			action = log::pickup;
		}
		else if (id == 954)
		{
			Rs2TileItemModel rope = Microbot.getRs2TileItemCache().query().withId(954)
				.where(item -> item.getWorldLocation().distanceTo(ROPE_AREA) <= 24 && item.getOwnership() == TileItem.OWNERSHIP_NONE).nearest();
			if (rope == null) return null;
			action = rope::pickup;
		}
		else
		{
			Rs2TileObjectModel net = Microbot.getRs2TileObjectCache().query().withId(674).within(12).nearest();
			if (net == null) return null;
			action = objectAction(net, "Take");
		}
		return awaitEffect(runtime, "free-tool:" + id + ":" + count, tick, action);
	}

	void boxPlaced(WorldPoint site, int id, int tick)
	{
		BoxTrapHunt trap = boxTraps.get(site);
		if (trap != null) trap.placed(site, id, tick);
	}

	void trapTimer(Object[] args, int tick)
	{
		for (Map.Entry<WorldPoint, DeadfallHunt> entry : deadfalls.entrySet())
			entry.getValue().confirmPlacement(args, entry.getKey(), tick);
		for (NetTrapHunt trap : netTraps.values()) trap.timer(args, tick);
	}

	int verifiedCatches()
	{
		return verifiedCatches;
	}

	private WorldPoint pitPoint(int x, int y)
	{
		return new WorldPoint(x, y, 0);
	}

	static PitTrap kyattPit(int slot, int limit)
	{
		if (slot == 0) return new PitTrap(19253, new WorldPoint(2700, 3795, 0));
		if (slot == 1) return new PitTrap(19254, new WorldPoint(2700, 3785, 0));
		if (slot == 2 && limit < 4) return new PitTrap(19255, new WorldPoint(2706, 3789, 0));
		if (slot == 2) return new PitTrap(19256, new WorldPoint(2730, 3791, 0));
		if (slot == 3) return new PitTrap(19257, new WorldPoint(2737, 3784, 0));
		if (slot == 4) return new PitTrap(19258, new WorldPoint(2730, 3780, 0));
		throw new IllegalArgumentException("Invalid kyatt pit slot");
	}

	static PitTrap sunlightPit(int slot)
	{
		if (slot == 0) return new PitTrap(51674, new WorldPoint(1744, 3010, 0));
		if (slot == 1) return new PitTrap(51673, new WorldPoint(1749, 3014, 0));
		if (slot == 2) return new PitTrap(51675, new WorldPoint(1751, 3009, 0));
		throw new IllegalArgumentException("Invalid sunlight pit slot");
	}

	int alternatePit(int current, WorldPoint position, RumourAssignment.Creature creature)
	{
		return alternatePit(current, position, creature, false);
	}

	private int alternatePit(int current, WorldPoint position, RumourAssignment.Creature creature, boolean waitForApproach)
	{
		int nearest = -1, prepared = -1;
		java.util.function.Predicate<WorldPoint> clear = null;
		for (int slot = 0; slot < pitTraps.length; slot++)
		{
			PitTrap pit = pitTraps[slot];
			if (slot == current || pit == null || !pit.prepared()
				|| Microbot.getClient().getVarbitValue(pitVarbit(pit)) != 1
				|| pitLure != null && !pitLure.canJump(pitLure.prey(), pit, 1)) continue;
			if (prepared < 0 || pit.location.distanceTo(position) < pitTraps[prepared].location.distanceTo(position)) prepared = slot;
			if (pitLure != null)
			{
				if (clear == null) clear = pitClearance(pitLure.prey());
				if (PitLure.approach(Microbot.getClient().getTopLevelWorldView(), position, pitLure.prey().getWorldArea(),
					pitTakeoff(slot, creature), pitLanding(slot, creature), clear) == null) continue;
			}
			if (nearest < 0 || pit.location.distanceTo(position) < pitTraps[nearest].location.distanceTo(position)) nearest = slot;
		}
		return nearest >= 0 ? nearest : waitForApproach ? prepared : -1;
	}

	private java.util.function.Predicate<WorldPoint> pitClearance(NPC prey)
	{
		java.util.Set<WorldPoint> occupied = new java.util.HashSet<>();
		Microbot.getRs2NpcCache().getStream().filter(other -> other.getNpc() != prey)
			.forEach(other -> occupied.addAll(other.getNpc().getWorldArea().toWorldPointList()));
		return point -> !occupied.contains(point);
	}

	private WorldPoint pitTakeoff(int slot, RumourAssignment.Creature creature)
	{
		if (creature == RumourAssignment.Creature.SUNLIGHT_ANTELOPE
			|| creature == RumourAssignment.Creature.SABRE_TOOTHED_KYATT && slot >= 2)
			return pitTraps[slot].baseId == 19255 || pitTraps[slot].baseId == 19258 || pitTraps[slot].baseId == 51675
				? pitTraps[slot].location.dy(-1) : pitTraps[slot].location.dx(-1);
		if (creature == RumourAssignment.Creature.HORNED_GRAAHK) return slot == 0 ? pitPoint(2762, 3004) : pitPoint(2770, 3004);
		if (creature == RumourAssignment.Creature.SPINED_LARUPIA) return slot == 0 ? pitPoint(2543, 2907) : pitPoint(2551, 2904);
		return slot == 0 ? pitPoint(2699, 3795) : pitPoint(2700, 3787);
	}

	private WorldPoint pitLanding(int slot, RumourAssignment.Creature creature)
	{
		if (creature == RumourAssignment.Creature.SUNLIGHT_ANTELOPE
			|| creature == RumourAssignment.Creature.SABRE_TOOTHED_KYATT && slot >= 2)
			return pitTraps[slot].baseId == 19255 || pitTraps[slot].baseId == 19258 || pitTraps[slot].baseId == 51675
				? pitTraps[slot].location.dy(2) : pitTraps[slot].location.dx(2);
		if (creature == RumourAssignment.Creature.HORNED_GRAAHK) return slot == 0 ? pitPoint(2762, 3007) : pitPoint(2773, 3004);
		if (creature == RumourAssignment.Creature.SPINED_LARUPIA) return slot == 0 ? pitPoint(2543, 2910) : pitPoint(2554, 2904);
		return slot == 0 ? pitPoint(2702, 3795) : pitPoint(2700, 3784);
	}

	boolean hasOwnedPits()
	{
		for (PitTrap pit : pitTraps) if (pit != null && pit.ownsTrap()) return true;
		return false;
	}

	void pitMessage(String message, int tick)
	{
		if (pitLure != null) pitLure.message(message, tick);
	}

	private Rs2TileObjectModel pitObject(PitTrap pit)
	{
		return Microbot.getRs2TileObjectCache().query().withId(pit.baseId)
			.where(object -> object.getWorldLocation().equals(pit.location)).first();
	}

	private Runnable pitTrapInput(HuntersRumoursRuntime runtime, PitTrap pit, PitTrap.Action action, int logs, int products)
	{
		if (huntingBusy()) return null;
		Rs2TileObjectModel object = pitObject(pit);
		if (object == null) return pitFailure(runtime, "The owned pit is unavailable. Check the hunting area and restart.");
		Runnable input = objectAction(object, action == PitTrap.Action.SET ? "Trap" : "Dismantle");
		if (input == null) return pitFailure(runtime, "The pit's required action is unavailable. Check the trap and restart.");
		int tick = Microbot.getClient().getTickCount();
		return onDispatch(runtime, () -> pit.submitted(action, tick, logs, products), input);
	}

	private Runnable pitFailure(HuntersRumoursRuntime runtime, String reason)
	{
		if (pitPhase == PitPhase.CLEANUP)
		{
			runtime.block(reason + " Recover any remaining owned pits manually before restarting.");
			return null;
		}
		pitStopReason = reason;
		runtime.finishRoute();
		travelling = bankArrived = false;
		pitPhase = pitLure == null ? PitPhase.CLEANUP : PitPhase.RETREAT;
		return null;
	}

	private Runnable restockPits(HuntersRumoursRuntime runtime)
	{
		runtime.finishRoute();
		pitLure = null;
		pitWaypoint = null;
		pitSwitches = 0;
		pitPhase = PitPhase.RESUPPLY;
		travelling = bankArrived = trapsPrepared = teleportsPrepared = false;
		bankEpoch = -1;
		pendingEffect = null;
		return null;
	}

	private void pitGuides()
	{
		pitWaypoint = null;
		pitGuideSteps = 0;
		pitPhase = PitPhase.GUIDE;
	}

	private Runnable pitStep(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		RumourAssignment.Creature creature = runtime.assignment().creature;
		boolean sunlight = creature == RumourAssignment.Creature.SUNLIGHT_ANTELOPE;
		boolean larupia = creature == RumourAssignment.Creature.SPINED_LARUPIA;
		boolean graahk = creature == RumourAssignment.Creature.HORNED_GRAAHK;
		String animal = sunlight ? "sunlight antelope" : larupia ? "larupia" : graahk ? "graahk" : "kyatt";
		int preyId = sunlight ? 13133 : larupia ? 2908 : graahk ? 2909 : 2907;
		int limit = HuntingSupplies.pitTrapLimit(creature, client.getRealSkillLevel(Skill.HUNTER));
		if (pitPhase == PitPhase.SETUP && !hasOwnedPits()) pitSlot = Math.min(pitSlot, limit - 1);
		WorldPoint position = client.getLocalPlayer().getWorldLocation();
		if (pitPhase == PitPhase.RESUPPLY)
		{
			int nearest = -1;
			for (int slot = 0; slot < pitTraps.length; slot++)
				if (pitTraps[slot] != null && (nearest < 0
					|| pitTraps[slot].location.distanceTo(position) < pitTraps[nearest].location.distanceTo(position))) nearest = slot;
			if (nearest >= 0)
			{
				WorldPoint destination = pitTakeoff(nearest, creature);
				if (travelling || position.distanceTo(destination) > 3) return travelStep(runtime, destination);
				pitSlot = nearest;
			}
			pitPhase = PitPhase.SETUP;
			return null;
		}
		int logs = HuntingSupplies.logs(client.getItemContainer(InventoryID.INVENTORY));
		int products = sunlight ? itemCount(532) + itemCount(29116) + itemCount(29168) + itemCount(29177) + itemCount(28924) + itemCount(29236)
			: graahk ? itemCount(532) + itemCount(10097) + itemCount(10099) + itemCount(29119) + itemCount(29229)
			: larupia ? itemCount(532) + itemCount(10095) + itemCount(10093) + itemCount(29122) + itemCount(29226)
			: itemCount(532) + itemCount(10101) + itemCount(10103) + itemCount(29125) + itemCount(29232);
		if (pitPhase == PitPhase.SETUP || pitPhase == PitPhase.SELECT || pitPhase == PitPhase.TEASING || pitPhase == PitPhase.GUIDE)
		{
			boolean selectedExpired = false;
			for (int slot = 0; slot < pitTraps.length; slot++)
			{
				PitTrap pit = pitTraps[slot];
				if (pit != null && pit.prepared() && client.getVarbitValue(pitVarbit(pit)) == 0
					&& pit.next(tick, 0, logs, products, false) == PitTrap.Action.CLEARED)
				{
					pitTraps[slot] = null;
					selectedExpired |= slot == pitSlot;
				}
			}
			if (selectedExpired && pitPhase != PitPhase.SETUP)
			{
				runtime.finishRoute();
				travelling = bankArrived = false;
				pitWaypoint = null;
				int alternate = alternatePit(pitSlot, position, creature, true);
				if (alternate >= 0)
				{
					pitSlot = alternate;
					pitGuides();
					if (pitLure == null) pitPhase = PitPhase.SELECT;
				}
				else if (pitLure == null && logs > 0) pitPhase = PitPhase.SETUP;
				else return restockPits(runtime);
				return null;
			}
		}
		boolean awaitingPitAction = pitPhase == PitPhase.CROSSING || java.util.Arrays.stream(pitTraps)
			.anyMatch(pit -> pit != null && pit.ownsTrap() && !pit.prepared());
		if (!awaitingPitAction && pitPhase != PitPhase.RETREAT && pitPhase != PitPhase.CLEANUP
			&& client.getBoostedSkillLevel(Skill.HITPOINTS) < 65 && itemCount(385) > 0)
		{
			String food = "pit-food:" + itemCount(385);
			if (client.getVarbitValue(VarbitID.BUSY) != 0)
				return null;
			return awaitEffect(runtime, food, tick, () -> Rs2Inventory.interact(385, "Eat"));
		}
		if (!awaitingPitAction && pitPhase != PitPhase.RETREAT && pitPhase != PitPhase.CLEANUP
			&& client.getBoostedSkillLevel(Skill.HITPOINTS) < 65 && itemCount(385) == 0)
			return restockPits(runtime);
		if (pitPhase == PitPhase.RETREAT)
		{
			if (!bankArrived || travelling) return travelStep(runtime, null);
			pitPhase = PitPhase.CLEANUP;
		}
		if (pitPhase == PitPhase.CLEANUP)
		{
			for (int slot = 0; slot < pitTraps.length; slot++)
			{
				PitTrap pit = pitTraps[slot];
				if (pit == null) continue;
				if (travelling || position.distanceTo(pitTakeoff(slot, creature)) > 3) return travelStep(runtime, pitTakeoff(slot, creature));
				PitTrap.Action action = pit.next(tick, client.getVarbitValue(pitVarbit(pit)), logs, products, true);
				if (action == PitTrap.Action.WAIT) return null;
				if (action == PitTrap.Action.CAUGHT || action == PitTrap.Action.CLEARED)
				{
					if (action == PitTrap.Action.CAUGHT) verifiedCatches++;
					pitTraps[slot] = null;
					return null;
				}
				if (action == PitTrap.Action.CHECK || action == PitTrap.Action.DISMANTLE)
					return pitTrapInput(runtime, pit, action, logs, products);
				return pitFailure(runtime, "Pit cleanup could not be verified.");
			}
			pitLure = null;
			pitPhase = PitPhase.SETUP;
			pitSlot = 1;
			pitSwitches = 0;
			trapsPrepared = bankArrived = teleportsPrepared = false;
			bankEpoch = -1;
			pendingEffect = null;
			if (pitStopReason != null)
			{
				String reason = pitStopReason;
				pitStopReason = null;
				runtime.block(reason);
			}
			return null;
		}
		if (pitPhase == PitPhase.SETUP)
		{
			if (pitTraps[pitSlot] == null && logs == 0)
			{
				int alternate = alternatePit(pitSlot, position, creature);
				if (alternate >= 0) pitSlot = alternate;
				else return restockPits(runtime);
				pitPhase = PitPhase.SELECT;
				return null;
			}
			if (pitTraps[pitSlot] == null)
			{
				pitTraps[pitSlot] = sunlight ? sunlightPit(pitSlot)
					: graahk ? new PitTrap(19265 + pitSlot, pitSlot == 0 ? pitPoint(2762, 3005) : pitPoint(2771, 3004))
					: larupia ? new PitTrap(19260 + pitSlot, pitSlot == 0 ? pitPoint(2543, 2908) : pitPoint(2552, 2904))
					: kyattPit(pitSlot, limit);
			}
			if (travelling || position.distanceTo(pitTakeoff(pitSlot, creature)) > 3)
			{
				return travelStep(runtime, pitTakeoff(pitSlot, creature));
			}
			PitTrap pit = pitTraps[pitSlot];
			PitTrap.Action action = pit.next(tick, client.getVarbitValue(pitVarbit(pitTraps[pitSlot])), logs, products, false);
			if (action == PitTrap.Action.SET)
			{
				if (HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY)) < HuntingSupplies.pitOutputSlots(runtime.assignment().creature, client.getItemContainer(InventoryID.INVENTORY)))
					return restockPits(runtime);
				return pitTrapInput(runtime, pit, action, logs, products);
			}
			if (action == PitTrap.Action.READY)
			{
				int next = 0;
				while (next < limit && pitTraps[next] != null && pitTraps[next].prepared()) next++;
				if (next < limit) pitSlot = next;
				else pitPhase = PitPhase.SELECT;
				return null;
			}
			if (action == PitTrap.Action.WAIT) return null;
			if (!pit.ownsTrap() && client.getVarbitValue(pitVarbit(pit)) >= 0
				&& client.getVarbitValue(pitVarbit(pit)) <= 4)
			{
				if (logs == 0) return restockPits(runtime);
				runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_TARGET);
				return null;
			}
			return pitFailure(runtime, "The selected pit could not be prepared. Check supplies or competition and restart.");
		}
		if (pitPhase == PitPhase.HARVEST)
		{
			PitTrap pit = pitTraps[pitSlot];
			PitTrap.Action action = pit.next(tick, client.getVarbitValue(pitVarbit(pit)), logs, products, false);
			if (action == PitTrap.Action.WAIT) return null;
			if (action == PitTrap.Action.CHECK) return pitTrapInput(runtime, pit, action, logs, products);
			if (action != PitTrap.Action.CAUGHT) return pitFailure(runtime, "The pit catch could not be collected and verified. Recover the pits and restart.");
			verifiedCatches++;
			pitTraps[pitSlot] = null;
			pitLure = null;
			pitSwitches = 0;
			pendingEffect = null;
			if (itemCount(creature.rarePartId()) > 0) pitPhase = PitPhase.CLEANUP;
			else if (HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY)) < HuntingSupplies.pitOutputSlots(creature, client.getItemContainer(InventoryID.INVENTORY))
				|| itemCount(385) == 0) return restockPits(runtime);
			else pitPhase = PitPhase.SETUP;
			return null;
		}
		Rs2NpcModel model = pitLure == null ? null : Microbot.getRs2NpcCache().query().withId(preyId)
			.where(candidate -> candidate.getNpc() == pitLure.prey()).first();
		NPC prey = model == null ? null : model.getNpc();
		if (pitPhase == PitPhase.SELECT)
		{
			if (travelling || position.distanceTo(pitTakeoff(pitSlot, creature)) > 3)
				return travelStep(runtime, pitTakeoff(pitSlot, creature));
			Rs2NpcModel candidate = Microbot.getRs2NpcCache().query().withId(preyId).within(12)
				.where(npc -> PitLure.available(npc.getNpc(), client.getLocalPlayer())).nearest();
			if (candidate == null)
			{
				runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_TARGET);
				return null;
			}
			Runnable tease = npcAction(candidate.getNpc(), "Tease");
			if (tease == null) return pitFailure(runtime, "The " + animal + "'s Tease action is unavailable. Check the target and restart.");
			return onDispatch(runtime, () ->
			{
				pitLure = new PitLure(candidate.getNpc());
				pitGuides();
				pitPhase = PitPhase.TEASING;
			}, tease);
		}
		if (pitPhase == PitPhase.CROSSING)
		{
			PitLure.Outcome result = pitLure.observe(tick, prey, pitTraps[pitSlot].baseId, client.getVarbitValue(pitVarbit(pitTraps[pitSlot])),
				prey == null ? -1 : prey.getAnimation(), prey == null ? null : prey.getWorldLocation());
			if (result == PitLure.Outcome.COLLECT)
			{
				pitPhase = PitPhase.HARVEST;
				return null;
			}
			if (result == PitLure.Outcome.SWITCH_PIT || result == PitLure.Outcome.EXPIRED)
			{
				if (result == PitLure.Outcome.SWITCH_PIT && (sunlight || ++pitSwitches >= 12))
					return pitFailure(runtime, "The " + animal + " pit catch could not be confirmed. Check the setup and restart.");
				int alternate = alternatePit(pitSlot, position, creature, true);
				if (alternate < 0) return restockPits(runtime);
				pitSlot = alternate;
				pitGuides();
				return null;
			}
			if (result == PitLure.Outcome.BLOCKED) return pitFailure(runtime, "The " + animal + "'s pit crossing could not be confirmed. Check the hunting area and restart.");
			return null;
		}
		if (!PitLure.available(prey, client.getLocalPlayer()))
		{
			runtime.finishRoute();
			travelling = false;
			pitLure = null;
			pitWaypoint = null;
			pitPhase = PitPhase.SELECT;
			return null;
		}
		boolean following = prey.getInteracting() == client.getLocalPlayer();
		if (pitPhase == PitPhase.TEASING)
		{
			if (following)
			{
				pitPhase = PitPhase.GUIDE;
				return null;
			}
			runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_RESULT);
			return null;
		}
		if (!following)
		{
			runtime.finishRoute();
			travelling = false;
			pitWaypoint = null;
			Runnable tease = npcAction(prey, "Tease");
			if (tease == null) return pitFailure(runtime, "The " + animal + " cannot be teased. Recover the pits and restart.");
			return onDispatch(runtime, () -> { pitPhase = PitPhase.TEASING; }, tease);
		}
		java.util.function.Predicate<WorldPoint> clear = pitClearance(prey);
		if (travelling)
		{
			if (!position.equals(pitWaypoint) && PitLure.canTravel(client.getTopLevelWorldView(), position, pitWaypoint,
				point -> clear.test(point) && !prey.getWorldArea().contains(point))) return travelStep(runtime, pitWaypoint);
			runtime.finishRoute();
			travelling = false;
			pitWaypoint = null;
		}
		WorldPoint takeoff = pitTakeoff(pitSlot, creature), landing = pitLanding(pitSlot, creature);
		Runnable jump = pitJump(runtime, pitLure, pitTraps[pitSlot], takeoff, landing);
		if (jump != null) return onDispatch(runtime, () -> { pitPhase = PitPhase.CROSSING; }, jump);
		WorldPoint step = PitLure.approach(client.getTopLevelWorldView(), position, prey.getWorldArea(), takeoff, landing, clear);
		if (step == null)
		{
			int alternate = alternatePit(pitSlot, position, creature);
			if (alternate >= 0) pitSlot = alternate;
			return null;
		}
		if (step.equals(position)) return null;
		return onDispatch(runtime, () -> { pitWaypoint = step; pitGuideSteps++; }, travelStep(runtime, step));
	}

	static int pitVarbit(PitTrap pit)
	{
		if (pit == null) return -1;
		if (pit.baseId == 19265 || pit.baseId == 19266) return 2970 + pit.baseId - 19265;
		if (pit.baseId == 19260 || pit.baseId == 19261) return 2965 + pit.baseId - 19260;
		if (pit.baseId >= 19253 && pit.baseId <= 19258) return 2958 + pit.baseId - 19253;
		return pit.baseId >= 51673 && pit.baseId <= 51675 ? 9767 + pit.baseId - 51673 : -1;
	}

	private Runnable pitJump(HuntersRumoursRuntime runtime, PitLure lure, PitTrap pit, WorldPoint takeoff, WorldPoint landing)
	{
		Client client = Microbot.getClient();
		if (lure == null || pit == null || pitVarbit(pit) < 0 || !pit.crossingPads(takeoff, landing) || client.getLocalPlayer() == null) return null;
		Rs2NpcModel model = Microbot.getRs2NpcCache().query()
			.where(npc -> npc.getNpc() == lure.prey()).first();
		NPC prey = model == null ? null : model.getNpc();
		int state = client.getVarbitValue(pitVarbit(pit));
		if (!lure.canJump(prey, pit, state) || prey.getInteracting() != client.getLocalPlayer()
			|| !PitTrap.aligned(client.getLocalPlayer().getWorldLocation(), prey.getWorldLocation(),
				prey.getWorldArea().getWidth(), takeoff, landing)) return null;
		Rs2TileObjectModel object = Microbot.getRs2TileObjectCache().query().withId(pit.baseId)
			.where(candidate -> candidate.getWorldLocation().equals(pit.location)).first();
		if (object == null || object.getObjectComposition() == null || object.getObjectComposition().getId() != 19228) return null;
		int tick = client.getTickCount();
		return onDispatch(runtime, () -> lure.submitted(pit, tick, takeoff, landing), objectAction(object, "Jump"));
	}

	private Runnable deadfallStep(HuntersRumoursRuntime runtime, boolean leave)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		RumourAssignment.Creature creature = runtime.assignment().creature;
		boolean wild = creature == RumourAssignment.Creature.WILD_KEBBIT;
		boolean barbed = creature == RumourAssignment.Creature.BARB_TAILED_KEBBIT;
		boolean prickly = creature == RumourAssignment.Creature.PRICKLY_KEBBIT;
		boolean pyre = creature == RumourAssignment.Creature.PYRE_FOX;
		WorldPoint destination = wild ? new WorldPoint(2317, 3522, 0) : barbed ? new WorldPoint(2574, 2930, 0)
			: prickly ? new WorldPoint(2323, 3642, 0) : pyre ? new WorldPoint(1613, 2996, 0) : DEADFALL_AREA;
		if (!atDeadfall)
		{
			if (travelling || client.getLocalPlayer().getWorldLocation().distanceTo(destination) > 3)
				return travelStep(runtime, destination);
			atDeadfall = true;
		}
		int logs = itemCount(1511) + itemCount(1521) + itemCount(1519);
		int products = itemCount(creature.deadfallCatchId());
		int free = HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY));
		int outputs = HuntingSupplies.deadfallOutputSlots(creature);
		boolean busy = huntingBusy();
		ArrayList<Map.Entry<WorldPoint, DeadfallHunt>> traps = new ArrayList<>(deadfalls.entrySet());
		traps.sort((a, b) -> Boolean.compare(b.getValue().pending(), a.getValue().pending()));
		for (Map.Entry<WorldPoint, DeadfallHunt> entry : traps)
		{
			DeadfallHunt hunt = entry.getValue();
			Rs2TileObjectModel trap = Microbot.getRs2TileObjectCache().query().where(object -> hunt.knownId(object.getId()))
				.where(object -> object.getWorldLocation().equals(entry.getKey())).first();
			DeadfallHunt.Action action = hunt.next(tick, trap == null ? -1 : trap.getId(), logs, products, leave);
			if (action == DeadfallHunt.Action.CAUGHT || action == DeadfallHunt.Action.CLEARED)
			{
				if (action == DeadfallHunt.Action.CAUGHT) verifiedCatches++;
				deadfalls.remove(entry.getKey());
				pendingEffect = null;
				return null;
			}
			if (action == DeadfallHunt.Action.BLOCKED)
			{
				runtime.block("The deadfall result or ownership was not verified. Leave other players' traps alone and inspect your trap before restarting.");
				return null;
			}
			if (action == DeadfallHunt.Action.CHECK && free < outputs + (leave ? 0 : 1))
			{
				runtime.block("Free " + (outputs + (leave ? 0 : 1)) + " inventory slots to collect your deadfall catch safely, then resume.");
				return null;
			}
			if (action == DeadfallHunt.Action.CHECK || action == DeadfallHunt.Action.DISMANTLE)
				return busy || trap == null ? null : trapAction(runtime, entry.getKey(), onDispatch(runtime, () -> hunt.submitted(action, tick, products),
					objectAction(trap, action == DeadfallHunt.Action.CHECK ? "Check" : "Dismantle")));
			if (hunt.pending()) return null;
		}
		if (leave || busy || deadfalls.size() >= 2) return null;
		if (logs < 1 || free < (deadfalls.size() + 1) * outputs + 1)
		{
			if (!deadfalls.isEmpty()) return null;
			trapsPrepared = bankArrived = atDeadfall = teleportsPrepared = false;
			bankEpoch = logsTarget = -1;
			pendingEffect = null;
			return null;
		}
		WorldPoint[] sites = wild ? new WorldPoint[]{new WorldPoint(2316, 3523, 0), new WorldPoint(2306, 3542, 0)}
			: barbed ? new WorldPoint[]{new WorldPoint(2572, 2930, 0), new WorldPoint(2576, 2925, 0)}
			: prickly ? new WorldPoint[]{new WorldPoint(2322, 3643, 0), new WorldPoint(2327, 3636, 0)}
			: pyre ? new WorldPoint[]{new WorldPoint(1612, 2997, 0), new WorldPoint(1613, 3002, 0)}
			: new WorldPoint[]{new WorldPoint(2714, 3779, 0), new WorldPoint(2710, 3777, 0)};
		Rs2TileObjectModel empty = Microbot.getRs2TileObjectCache().query().withId(19215)
			.where(object -> Arrays.asList(sites).contains(object.getWorldLocation()) && !deadfalls.containsKey(object.getWorldLocation())).nearest();
		if (empty == null)
		{
			if (deadfalls.isEmpty()) runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_TARGET);
			return null;
		}
		DeadfallHunt hunt = new DeadfallHunt(creature);
		WorldPoint site = empty.getWorldLocation();
		return trapAction(runtime, site, onDispatch(runtime, () ->
		{
			hunt.submitted(DeadfallHunt.Action.SET, tick, products);
			deadfalls.put(site, hunt);
		}, objectAction(empty, "Set-trap")));
	}

	private Runnable bankNetLoot(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		if (netLootId == 0)
		{
			netLootId = runtime.assignment().creature.keptNetCatchId();
			netLootCount = itemCount(netLootId);
			if (netLootId <= 0 || netLootCount <= 0)
			{
				runtime.block("The valuable salamander inventory is unavailable. Preserve the catch and restart after checking inventory.");
				return null;
			}
			bankArrived = atNetArea = false;
			bankEpoch = -1;
			pendingEffect = null;
		}
		if (netLootCount == 0)
		{
			if (Rs2Bank.isOpen()) return awaitEffect(runtime, "close-net-loot-bank", tick, Rs2Bank::closeBank);
			netLootId = 0;
			netLootBankBefore = -1;
			pendingEffect = null;
			return null;
		}
		if (!bankReady()) return prepareBank(runtime);
		int banked = HuntingSupplies.count(client.getItemContainer(InventoryID.BANK), netLootId);
		if (netLootBankBefore < 0) netLootBankBefore = banked;
		if (itemCount(netLootId) == 0 && banked == netLootBankBefore + netLootCount)
		{
			netLootCount = 0;
			pendingEffect = null;
			return null;
		}
		int item = netLootId;
		return awaitEffect(runtime, "bank-valuable-salamander", tick,
			itemCount(item) == netLootCount ? () -> Rs2Bank.depositAll(item) : null);
	}

	private Runnable releaseNetCatch(HuntersRumoursRuntime runtime, int catchId)
	{
		if (runtime.assignment() == null || catchId <= 0 || catchId != runtime.assignment().creature.netCatchId())
		{
			runtime.block("The salamander is not the assigned releasable catch. Keep it and verify the assignment before restarting.");
			return null;
		}
		Client client = Microbot.getClient();
		if (huntingBusy()) return null;
		int tick = client.getTickCount();
		if (!Rs2Tab.isCurrentTab(InterfaceTab.INVENTORY))
			return awaitEffect(runtime, "release-inventory-tab", tick, () -> Rs2Tab.switchTo(InterfaceTab.INVENTORY));
		return awaitEffect(runtime, "release-salamander:" + catchId + ":" + itemCount(catchId), tick, () -> Rs2Inventory.interact(catchId, "Release"));
	}

	private Runnable netStep(HuntersRumoursRuntime runtime, boolean leave)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		RumourAssignment.Creature creature = runtime.assignment().creature;
		boolean red = creature == RumourAssignment.Creature.RED_SALAMANDER;
		boolean swamp = creature == RumourAssignment.Creature.SWAMP_LIZARD;
		boolean tecu = creature == RumourAssignment.Creature.TECU_SALAMANDER;
		WorldPoint destination = trapArea(creature);
		if (!atNetArea)
		{
			if (travelling || (collectingLogs ? !client.getLocalPlayer().getWorldLocation().equals(destination)
				: client.getLocalPlayer().getWorldLocation().distanceTo(destination) > 3))
				return travelStep(runtime, destination);
			atNetArea = true;
		}
		runtime.taskState(HuntersRumoursRuntime.State.HUNTING_NETS);
		int catches = HuntingSupplies.netCatchCount(client.getItemContainer(InventoryID.INVENTORY), creature);
		int kept = creature.keptNetCatchId() > 0 ? itemCount(creature.keptNetCatchId()) : 0;
		int ropes = itemCount(954);
		int nets = itemCount(303);
		boolean busy = huntingBusy();
		boolean pending = netTraps.values().stream().anyMatch(NetTrapHunt::pending);
		if (!pending && itemCount(creature.netCatchId()) > 0) return releaseNetCatch(runtime, creature.netCatchId());
		boolean fillSlots = !leave && !busy && !pending && ropes > 0 && nets > 0;
		if (fillSlots && netTraps.size() < HuntingSupplies.netTrapLimit(client.getRealSkillLevel(Skill.HUNTER))
			&& netTraps.values().stream().allMatch(NetTrapHunt::ownsTrap))
		{
			Rs2TileObjectModel tree = Microbot.getRs2TileObjectCache().query().withId(tecu ? 50721 : swamp ? 9341 : red ? 8990 : 8732).within(18)
				.where(candidate -> candidate.getWorldLocation().distanceTo(destination) <= (swamp ? 6 : 18))
				.where(candidate -> netTraps.keySet().stream().noneMatch(site -> site.distanceTo(candidate.getWorldLocation()) <= 2)).nearest();
			if (tree != null) netTraps.put(tree.getWorldLocation(), new NetTrapHunt(tree.getWorldLocation(), creature));
		}
		ArrayList<NetTrapHunt> traps = new ArrayList<>(netTraps.values());
		traps.sort((a, b) ->
		{
			int receipt = Boolean.compare(b.pending(), a.pending());
			return receipt != 0 || !fillSlots ? receipt : Boolean.compare(a.ownsTrap(), b.ownsTrap());
		});
		for (NetTrapHunt trap : traps)
		{
			Rs2TileObjectModel object = Microbot.getRs2TileObjectCache().query()
				.where(candidate -> trap.knownId(candidate.getId()) && candidate.getWorldLocation().equals(trap.trapLocation())).first();
			if (object == null) object = Microbot.getRs2TileObjectCache().query()
				.where(candidate -> trap.knownId(candidate.getId()) && candidate.getWorldLocation().equals(trap.tree)).first();
			int free = HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY));
			if (!trap.pending() && free < 4)
			{
				runtime.block("Free 4 inventory slots to recover your net trap, catch and rare part. Keep the trap supplies and resume safely.");
				return null;
			}
			boolean droppedTools = Microbot.getRs2TileItemCache().query().withIds(954, 303)
				.where(candidate -> candidate.getOwnership() == TileItem.OWNERSHIP_SELF
					&& candidate.getWorldLocation().distanceTo(trap.tree) <= 1).first() != null;
			NetTrapHunt.Action action = trap.next(tick, object == null ? -1 : object.getId(), ropes, nets, catches, leave, busy, droppedTools);
			if (action == NetTrapHunt.Action.CAUGHT || action == NetTrapHunt.Action.CLEARED)
			{
				if (action == NetTrapHunt.Action.CAUGHT) verifiedCatches++;
				netTraps.remove(trap.tree);
				trapCleared(trap.tree);
				pendingEffect = null;
				return null;
			}
			if (action == NetTrapHunt.Action.BLOCKED)
			{
				runtime.block("The net trap's ownership, catch or returned supplies were not verified. Inspect your traps before restarting; leave other players' traps alone.");
				return null;
			}
			if (action == NetTrapHunt.Action.RECOVER)
			{
				Rs2TileItemModel item = Microbot.getRs2TileItemCache().query().withIds(954, 303)
					.where(candidate -> candidate.getOwnership() == TileItem.OWNERSHIP_SELF
						&& candidate.getWorldLocation().distanceTo(trap.tree) <= 1
						&& trap.needsTool(candidate.getId(), itemCount(candidate.getId()))).nearest();
				if (item == null) return inspectMissingTrap(runtime, creature, trap.tree);
				return trapAction(runtime, item.getWorldLocation(),
					awaitEffect(runtime, "recover-net:" + trap.tree + ":" + item.getId() + ":" + itemCount(item.getId()), tick, item::pickup));
			}
			if (action == NetTrapHunt.Action.SET || action == NetTrapHunt.Action.CHECK || action == NetTrapHunt.Action.DISMANTLE)
				return object == null ? null : trapAction(runtime, object.getWorldLocation(), onDispatch(runtime, () -> trap.submitted(action, tick, ropes, nets, catches),
					action == NetTrapHunt.Action.SET ? checkpointPlacement(runtime, creature, trap.tree, objectAction(object, "Set-trap"))
						: objectAction(object, action == NetTrapHunt.Action.CHECK ? "Check" : "Dismantle")));
			if (trap.pending()) return null;
		}
		if (leave) return netTraps.isEmpty() ? (kept > 0 ? bankNetLoot(runtime) : handInStep(runtime)) : null;
		if (itemCount(954) < 1 || itemCount(303) < 1)
		{
			if (!netTraps.isEmpty()) return null;
			trapsPrepared = bankArrived = atNetArea = teleportsPrepared = false;
			bankEpoch = -1;
			return null;
		}
		if (netTraps.isEmpty()) runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_TARGET);
		return null;
	}

	void butterflyDespawned(NPC npc, int tick)
	{
		if (npc != null && npc == butterflyTarget) butterfly.despawned(npc.getIndex(), tick);
	}

	private Runnable prepareGoatSupplies(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			runtime.block("Equipment is unavailable. Reopen your equipment and restart.");
			return null;
		}
		if (goatBankWeapon >= 0)
		{
			if (itemCount(goatBankWeapon) > 0)
			{
				int id = goatBankWeapon;
				return awaitEffect(runtime, "bank-unused-goat-weapon:" + id, tick, () -> Rs2Bank.depositAll(id));
			}
			if (HuntingSupplies.count(equipment, goatBankWeapon) > 0)
				return awaitEffect(runtime, "remove-goat-weapon:" + goatBankWeapon, tick, null);
			goatBankWeapon = -1;
		}
		if (HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY)) < 6)
		{
			runtime.block("Free " + (6 - HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY)))
				+ " more inventory slots for goat tools and catch outputs, then restart.");
			return null;
		}
		Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon != null && weapon.getId() >= 0 && weapon.getId() != 278)
		{
			int id = weapon.getId();
			return onDispatch(runtime, () -> goatBankWeapon = id,
				() -> net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment.unEquip(id));
		}
		int[] necklaces = {21155, 21153, 21151, 21149, 21146};
		boolean passage = Arrays.stream(necklaces).anyMatch(id -> itemCount(id) + HuntingSupplies.count(equipment, id) > 0);
		if (!passage)
		{
			int id = Arrays.stream(necklaces).filter(item -> HuntingSupplies.count(client.getItemContainer(InventoryID.BANK), item) > 0).findFirst().orElse(-1);
			if (id < 0)
			{
				runtime.block("Missing 1 charged Necklace of passage in equipment, inventory and bank. Add one for Wyrmscraig travel, then restart.");
				return null;
			}
			if (!Rs2Bank.hasWithdrawAsItem()) return awaitEffect(runtime, "withdraw-items", tick, Rs2Bank::setWithdrawAsItem);
			return awaitEffect(runtime, "withdraw-goat-passage", tick, () -> Rs2Bank.withdrawOne(id));
		}
		if (itemCount(278) + HuntingSupplies.count(equipment, 278) == 0
			&& HuntingSupplies.count(client.getItemContainer(InventoryID.BANK), 278) > 0)
			return awaitEffect(runtime, "withdraw-cattleprod", tick, () -> Rs2Bank.withdrawOne(278));
		trapsPrepared = true;
		return awaitEffect(runtime, "close-bank", tick, Rs2Bank::closeBank);
	}

	private Runnable goatStep(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (inventory == null || equipment == null)
		{
			runtime.block("Goat hunting supplies are unavailable. Reopen inventory and equipment, then restart.");
			return null;
		}
		runtime.taskState(HuntersRumoursRuntime.State.HUNTING_GOATS);
		int count = client.getVarbitValue(15725), spikes = itemCount(34016);
		int experience = client.getSkillExperience(Skill.HUNTER), level = client.getRealSkillLevel(Skill.HUNTER);
		int products = itemCount(9735) + itemCount(34017) + itemCount(33835);
		boolean pending = goats.pending();
		GoatPit.Action step = goats.next(tick, client.getVarbitValue(15724), count, client.getVarbitValue(15726),
			spikes, experience, products, level, HuntingSupplies.freeSlots(inventory), itemCount(33835) > 0);
		if (pending && !goats.pending())
		{
			pendingEffect = null;
		}
		if (step == GoatPit.Action.CAUGHT) { verifiedCatches += goats.caught(); return null; }
		if (step == GoatPit.Action.WAIT) return null;
		if (step == GoatPit.Action.BLOCKED)
		{
			runtime.block("The goat pit did not produce a verified result or has an unknown state. Inspect the pit and restart; remaining goats and supplies have been preserved.");
			return null;
		}
		if (Rs2Dialogue.isInDialogue())
		{
			if (Rs2Dialogue.hasContinue() && count == 0 && GoatPit.emptyDialogue(Rs2Dialogue.getDialogueText()))
				return awaitEffect(runtime, "goat-empty-dialogue", tick, Rs2Dialogue::clickContinue);
			runtime.block("Finish the open dialogue before continuing goat hunting, then restart.");
			return null;
		}
		if (step == GoatPit.Action.FINISHED) return handInStep(runtime);
		if (huntingBusy()) return null;
		if (client.getBoostedSkillLevel(Skill.HUNTER) < 60)
		{
			runtime.block("Restore Hunter to level 60 before hunting Wyrmscraig goats, then restart.");
			return null;
		}
		if (step == GoatPit.Action.BANK)
		{
			trapsPrepared = bankArrived = false;
			bankEpoch = -1;
			pendingEffect = null;
			return null;
		}
		if (travelling) return travelStep(runtime, goatApproach);
		WorldPoint player = client.getLocalPlayer().getWorldLocation();
		if (player.distanceTo(GoatPit.LOCATION) > 18)
			return goatTravel(runtime, new WorldPoint(2572, 2192, 0));
		if (HuntingSupplies.count(equipment, 278) == 0)
		{
			if (itemCount(278) > 0) return awaitEffect(runtime, "equip-cattleprod", tick, () -> Rs2Inventory.interact(278, "Equip"));
			return goatObject(runtime, 62350, "Take", false, step, count, spikes, experience, products, level);
		}
		if (step == GoatPit.Action.TAKE_SPIKES)
			return goatObject(runtime, 62349, "Take", false, step, count, spikes, experience, products, level);
		if (step == GoatPit.Action.LINE || step == GoatPit.Action.HARVEST)
			return goatObject(runtime, 62343, step == GoatPit.Action.LINE ? "Line" : "Clear", true, step, count, spikes, experience, products, level);
		net.runelite.api.CollisionData[] maps = client.getCollisionMaps();
		int[][] flags = maps == null || maps[client.getPlane()] == null ? null : maps[client.getPlane()].getFlags();
		if (flags == null)
		{
			runtime.block("Goat approach collision data is unavailable. Wait for the area to load, then restart.");
			return null;
		}
		Rs2NpcModel target = Microbot.getRs2NpcCache().query().withId(16298).toListOnClientThread().stream()
			.filter(npc -> GoatPit.behind(npc.getWorldLocation(), flags, client.getBaseX(), client.getBaseY()) != null)
			.min(java.util.Comparator.comparingInt(npc -> GoatPit.behind(npc.getWorldLocation()).distanceTo(player))).orElse(null);
		if (target == null) return null;
		WorldPoint position = target.getWorldLocation(), approach = GoatPit.behind(position);
		if (!player.equals(approach)) return goatTravel(runtime, approach);
		Runnable prod = npcAction(target.getNpc(), "Prod");
		if (prod == null)
		{
			runtime.block("The selected goat's Prod action is unavailable. Inspect the goat and restart.");
			return null;
		}
		return onDispatch(runtime, () -> { }, () ->
		{
			Runnable verified = Microbot.getClientThread().runOnClientThreadOptional(() ->
			{
				Rs2NpcModel current = Microbot.getRs2NpcCache().query().withId(16298)
					.where(npc -> npc.getNpc() == target.getNpc()).nearest();
				if (current == null || !position.equals(current.getWorldLocation())
					|| client.getLocalPlayer() == null || !approach.equals(client.getLocalPlayer().getWorldLocation())
					|| client.getVarbitValue(15725) != count || client.getVarbitValue(15724) != 1) return null;
				int dispatchedTick = client.getTickCount();
				return onDispatch(runtime, () -> goats.submitted(step, dispatchedTick, count, spikes, experience, products, level), prod);
			}).orElse(null);
			if (verified != null) verified.run();
		});
	}

	private Runnable goatTravel(HuntersRumoursRuntime runtime, WorldPoint destination)
	{
		goatApproach = destination;
		return travelStep(runtime, destination);
	}

	private Runnable goatObject(HuntersRumoursRuntime runtime, int id, String option, boolean track,
		GoatPit.Action step, int count, int spikes, int experience, int products, int level)
	{
		Client client = Microbot.getClient();
		Rs2TileObjectModel object = Microbot.getRs2TileObjectCache().query().withId(id).nearest();
		if (object == null)
		{
			runtime.block("The goat pit or its supply object is unavailable. Check the hunting area and restart.");
			return null;
		}
		if (client.getLocalPlayer().getWorldLocation().distanceTo(object.getWorldLocation()) > 8)
			return goatTravel(runtime, id == 62343 ? new WorldPoint(2572, 2192, 0) : new WorldPoint(2576, 2202, 0));
		Runnable action = objectAction(object, option);
		if (action == null)
		{
			runtime.block("The goat pit or supply object's " + option + " action is unavailable. Inspect it and restart.");
			return null;
		}
		int tick = client.getTickCount();
		return track ? onDispatch(runtime, () -> goats.submitted(step, tick, count, spikes, experience, products, level), action)
			: awaitEffect(runtime, "goat-supply:" + id, tick, action);
	}

	void herbiboarMessage(String text, int tick)
	{
		HerbiboarTrail.Receipt receipt = herbiboarReceipt;
		if (receipt != null) receipt.message(text, tick);
	}

	private Runnable herbiboarStep(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null || client.getItemContainer(InventoryID.EQUIPMENT) == null)
		{
			runtime.block("Herbiboar inventory or equipment is unavailable. Wait for it to load, then restart.");
			return null;
		}
		runtime.taskState(HuntersRumoursRuntime.State.HUNTING_HERBIBOAR);
		int[] snapshot = herbiboarSnapshot(client);
		int products = HuntingSupplies.herbiboarProducts(inventory);
		if (herbiboarReceipt != null)
		{
			HerbiboarTrail.Result result = herbiboarReceipt.observe(snapshot, tick, products);
			if (result == HerbiboarTrail.Result.BLOCKED)
				runtime.block("The Herbiboar action did not produce a verified result. Inspect the current trail or waiting creature, then restart; no further actions will be sent.");
			if (result == HerbiboarTrail.Result.CHANGED || result == HerbiboarTrail.Result.HARVESTED)
			{
				if (result == HerbiboarTrail.Result.HARVESTED) verifiedCatches++;
				herbiboarReceipt = null;
				pendingEffect = null;
			}
			return null;
		}
		if (snapshot[25] == 0 && itemCount(29239) > 0) return handInStep(runtime);
		if (huntingBusy()) return null;
		WorldPoint player = client.getLocalPlayer().getWorldLocation();
		HerbiboarTrail.Step step = HerbiboarTrail.next(Arrays.copyOf(snapshot, 24), snapshot[24], snapshot[25], snapshot[26], player);
		if (step.action == HerbiboarTrail.Action.BLOCKED)
		{
			runtime.block("The Herbiboar trail has no unambiguous next target. Inspect the current trail, then restart; it has not been reset.");
			return null;
		}
		if (step.action == HerbiboarTrail.Action.START && client.getBoostedSkillLevel(Skill.HUNTER) < 80)
		{
			runtime.block("Restore Hunter to level 80 before starting another Herbiboar trail, then restart.");
			return null;
		}
		if (step.action == HerbiboarTrail.Action.HARVEST && client.getBoostedSkillLevel(Skill.HERBLORE) < 31)
		{
			runtime.block("Restore Herblore to level 31 to harvest the waiting Herbiboar, then restart.");
			return null;
		}
		if (HuntingSupplies.freeSlots(inventory) < HuntingSupplies.herbiboarSlots(inventory, client.getItemContainer(InventoryID.EQUIPMENT))
			|| itemCount(13226) > 0 || itemCount(24478) > 0 || itemCount(33135) > 0 || itemCount(33137) > 0)
		{
			trapsPrepared = bankArrived = false;
			bankEpoch = -1;
			pendingEffect = null;
			return null;
		}
		if (travelling) return travelStep(runtime, null);
		WorldPoint centre = new WorldPoint(3709, 3846, 0);
		if (player.distanceTo(centre) > 90) return travelStep(runtime, centre);
		if (player.distanceTo(step.target) > 8)
		{
			net.runelite.api.CollisionData[] maps = client.getCollisionMaps();
			int[][] flags = maps == null || maps[client.getPlane()] == null ? null : maps[client.getPlane()].getFlags();
			WorldPoint approach = TrackingTrail.approach(step.target, player, flags, client.getBaseX(), client.getBaseY());
			if (approach == null)
			{
				if (player.distanceTo(centre) > 8) return travelStep(runtime, centre);
				runtime.block("No walkable approach to the Herbiboar target is available. Inspect the loaded hunting area, then restart.");
				return null;
			}
			return travelStep(runtime, approach);
		}
		return onDispatch(runtime, () -> { }, () ->
		{
			Runnable action = Microbot.getClientThread().runOnClientThreadOptional(() ->
			{
				if (!Arrays.equals(snapshot, herbiboarSnapshot(client)) || client.getGameState() != GameState.LOGGED_IN
					|| client.getLocalPlayer() == null || client.getLocalPlayer().getWorldLocation().distanceTo(step.target) > 8
					|| huntingBusy() || !runtime.mayAct()
					|| (step.action == HerbiboarTrail.Action.START && client.getBoostedSkillLevel(Skill.HUNTER) < 80)
					|| (step.action == HerbiboarTrail.Action.HARVEST && client.getBoostedSkillLevel(Skill.HERBLORE) < 31)) return (Runnable) null;
				ItemContainer current = client.getItemContainer(InventoryID.INVENTORY);
				if (current == null || HuntingSupplies.freeSlots(current)
					< HuntingSupplies.herbiboarSlots(current, client.getItemContainer(InventoryID.EQUIPMENT))) return (Runnable) null;
				Runnable input = herbiboarAction(step);
				if (input == null)
				{
					runtime.block("The selected Herbiboar target or its required action is unavailable. Inspect the current trail or waiting creature, then restart.");
					return (Runnable) null;
				}
				int dispatchedTick = client.getTickCount();
				int loot = HuntingSupplies.herbiboarProducts(current);
				return onDispatch(runtime, () -> herbiboarReceipt = new HerbiboarTrail.Receipt(step.action, snapshot, dispatchedTick, loot), input);
			}).orElse(null);
			if (action != null) action.run();
		});
	}

	private int[] herbiboarSnapshot(Client client)
	{
		int[] snapshot = new int[27];
		for (int i = 0; i < HerbiboarTrail.trailCount(); i++) snapshot[i] = client.getVarbitValue(HerbiboarTrail.varbit(i));
		snapshot[24] = client.getVarbitValue(5766);
		snapshot[25] = client.getVarbitValue(5943);
		snapshot[26] = client.getVarbitValue(5767);
		return snapshot;
	}

	private Runnable herbiboarAction(HerbiboarTrail.Step step)
	{
		if (step.action == HerbiboarTrail.Action.HARVEST)
		{
			Rs2NpcModel target = Microbot.getRs2NpcCache().query().where(npc ->
			{
				NPCComposition composition = npc.getNpc().getTransformedComposition();
				return composition != null && step.matchesNpc(npc.getNpc().getId(), composition.getId(), npc.getWorldLocation());
			}).nearest();
			return target == null ? null : npcAction(target.getNpc(), "Harvest");
		}
		else
		{
			Rs2TileObjectModel target = Microbot.getRs2TileObjectCache().query()
				.where(object -> step.matchesObject(object.getId(), object.getWorldLocation())).first();
			return target == null ? null : objectAction(target, step.action == HerbiboarTrail.Action.ATTACK ? "Attack" : "Inspect");
		}
	}

	private Runnable trackingStep(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (inventory == null || equipment == null)
		{
			runtime.block("Tracking supplies are unavailable. Reopen your inventory and equipment, then restart.");
			return null;
		}
		runtime.taskState(HuntersRumoursRuntime.State.HUNTING_TRACKING);
		int[] trail = new int[21];
		for (int i = 0; i < trail.length; i++) trail[i] = client.getVarbitValue(2974 + i);
		TrackingTrail.Step step = TrackingTrail.next(trail);
		int experience = client.getSkillExperience(Skill.HUNTER);
		int products = itemCount(10107) + itemCount(runtime.assignment().creature.rarePartId());
		if (trackingBefore != null)
		{
			if (trackingAttack ? step.action == TrackingTrail.Action.START && experience - trackingExperience >= 348
				&& products > trackingProducts : !Arrays.equals(trail, trackingBefore))
			{
				if (trackingAttack) verifiedCatches++;
				trackingBefore = null;
				pendingEffect = null;
				return null;
			}
			runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_RESULT);
			return null;
		}
		if (huntingBusy()) return null;
		if (client.getBoostedSkillLevel(Skill.HUNTER) < 49)
		{
			runtime.block("Restore Hunter to level 49 before tracking Razor-backed kebbits, then restart.");
			return null;
		}
		if (HuntingSupplies.freeSlots(inventory) < 4)
		{
			trapsPrepared = bankArrived = false;
			bankEpoch = -1;
			pendingEffect = null;
			return null;
		}
		WorldPoint centre = new WorldPoint(2355, 3610, 0);
		if (travelling) return travelStep(runtime, null);
		if (client.getLocalPlayer().getWorldLocation().distanceTo(centre) > 14) return travelStep(runtime, centre);
		if (HuntingSupplies.count(equipment, 10150) == 0)
		{
			if (itemCount(10150) > 0) return awaitEffect(runtime, "equip-noose-wand", tick, () -> Rs2Inventory.interact(10150, "Wield"));
			runtime.block("Equip a Noose wand before tracking Razor-backed kebbits, then restart.");
			return null;
		}
		if (step.action == TrackingTrail.Action.BLOCKED)
		{
			runtime.block("The tracking trail has no unambiguous next clue. Inspect the current trail and restart; it has not been reset.");
			return null;
		}
		Rs2TileObjectModel target;
		if (step.action == TrackingTrail.Action.START)
		{
			target = Microbot.getRs2TileObjectCache().query().where(object -> object.getWorldLocation().getRegionID() == 9272
				&& (object.getId() == 19438 || object.getId() == 19579 || object.getId() == 19580)).nearest();
		}
		else
		{
			int[][] clues = {{19356,2362,3598},{19357,2355,3598},{19358,2347,3603},{19359,2358,3599},
				{19360,2352,3603},{19361,2358,3603},{19362,2363,3602},{19363,2358,3607},{19364,2355,3608},{19365,2351,3608},
				{0,0,0},{19372,2363,3617},{19373,2349,3620},{19374,2356,3620},{19375,2344,3612},{19376,2352,3612},
				{19377,2349,3617},{19378,2352,3618},{19379,2362,3614},{19380,2360,3618}};
			int[][] ends = {{2358,3620},{2351,3619},{2362,3615},{2354,3609},{2357,3607},{2349,3604},{2360,3602},{2355,3601}};
			int[] location = step.action == TrackingTrail.Action.ATTACK ? ends[step.target - 1] : clues[step.target - 2974];
			int id = step.action == TrackingTrail.Action.ATTACK ? 19428 : location[0];
			WorldPoint point = step.action == TrackingTrail.Action.ATTACK ? new WorldPoint(location[0], location[1], 0)
				: new WorldPoint(location[1], location[2], 0);
			target = Microbot.getRs2TileObjectCache().query().withId(id).where(object -> object.getWorldLocation().equals(point)).nearest();
		}
		if (target == null)
		{
			runtime.block("The current tracking target is unavailable. Check the woodland hunting area and restart.");
			return null;
		}
		if (client.getLocalPlayer().getWorldLocation().distanceTo(target.getWorldLocation()) > 8)
		{
			net.runelite.api.CollisionData[] maps = client.getCollisionMaps();
			int[][] flags = maps == null || maps[client.getPlane()] == null ? null : maps[client.getPlane()].getFlags();
			WorldPoint approach = TrackingTrail.approach(target.getWorldLocation(), client.getLocalPlayer().getWorldLocation(),
				flags, client.getBaseX(), client.getBaseY());
			if (approach == null)
			{
				runtime.block("No walkable approach to the tracking target is available. Inspect the area and restart.");
				return null;
			}
			return travelStep(runtime, approach);
		}
		Runnable action = objectAction(target, step.action == TrackingTrail.Action.ATTACK ? "Attack" : "Inspect");
		if (action == null)
		{
			runtime.block("The tracking target's required action is unavailable. Inspect the trail and restart.");
			return null;
		}
		return onDispatch(runtime, () ->
		{
			trackingBefore = trail;
			trackingExperience = experience;
			trackingProducts = products;
			trackingAttack = step.action == TrackingTrail.Action.ATTACK;
		}, action);
	}

	private Runnable butterflyStep(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		RumourAssignment.Creature creature = runtime.assignment().creature;
		int tick = client.getTickCount();
		runtime.taskState(HuntersRumoursRuntime.State.HUNTING_BUTTERFLIES);
		int experience = client.getSkillExperience(Skill.HUNTER);
		ButterflyHunt.Action action = butterfly.next(tick, experience, creature.butterflyLevel() + 9,
			huntingBusy());
		if (action == ButterflyHunt.Action.WAIT) return null;
		if (action == ButterflyHunt.Action.CAUGHT)
		{
			verifiedCatches++;
			butterflyTarget = null;
			return null;
		}
		if (action == ButterflyHunt.Action.BLOCKED)
		{
			runtime.block("The butterfly catch was not verified. Check the target, Hunter level and equipment, then restart.");
			return null;
		}
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null || client.getItemContainer(InventoryID.INVENTORY) == null)
		{
			runtime.block("Butterfly supplies are unavailable. Reopen your inventory and equipment, then restart.");
			return null;
		}
		if (client.getBoostedSkillLevel(Skill.HUNTER) < creature.assignmentLevel)
		{
			runtime.block("Restore your Hunter level before catching the assigned butterfly, then restart.");
			return null;
		}
		if (HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY)) < 1 || itemCount(10012) > 0)
		{
			runtime.block("Bank empty Butterfly jars and free 1 inventory slot for the rare wing, then restart.");
			return null;
		}
		WorldPoint centre = creature == RumourAssignment.Creature.MOONLIGHT_MOTH ? new WorldPoint(1562, 9441, 0)
			: creature == RumourAssignment.Creature.SUNLIGHT_MOTH ? new WorldPoint(1557, 3091, 0)
			: creature == RumourAssignment.Creature.BLACK_WARLOCK ? new WorldPoint(2532, 2905, 0)
			: creature == RumourAssignment.Creature.SNOWY_KNIGHT ? new WorldPoint(2712, 3797, 0) : new WorldPoint(2700, 3782, 0);
		if (travelling) return travelStep(runtime, null);
		if (client.getLocalPlayer().getWorldLocation().distanceTo(centre) > 12) return travelStep(runtime, centre);
		if (client.getBoostedSkillLevel(Skill.HUNTER) < creature.butterflyLevel() + 10
			&& HuntingSupplies.count(equipment, 10010) + HuntingSupplies.count(equipment, 11259) == 0)
		{
			for (int id : new int[]{11259, 10010})
				if (itemCount(id) > 0) return awaitEffect(runtime, "equip-butterfly-net:" + id, tick, () -> Rs2Inventory.interact(id, "Wield"));
			runtime.block("Equip a Butterfly net or restore the Hunter level needed for barehanded catching, then restart.");
			return null;
		}
		Rs2NpcModel candidate = Microbot.getRs2NpcCache().query().where(npc -> creature.butterflyMatches(npc.getId()))
			.where(npc -> npc.getWorldLocation().distanceTo(centre) <= 16).nearest();
		if (candidate == null)
		{
			if (client.getLocalPlayer().getWorldLocation().distanceTo(centre) > 3)
				return travelStep(runtime, centre);
			runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_TARGET);
			return null;
		}
		if (candidate.getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation()) > 8)
			return travelStep(runtime, candidate.getWorldLocation());
		NPC selected = candidate.getNpc();
		int index = selected.getIndex();
		return onDispatch(runtime, () ->
		{
			butterflyTarget = selected;
			butterfly.submitted(index, tick, experience);
		}, npcAction(selected, "Catch"));
	}

	private Runnable boxStep(HuntersRumoursRuntime runtime, boolean leave)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		RumourAssignment.Creature creature = runtime.assignment().creature;
		boolean bird = creature == RumourAssignment.Creature.TROPICAL_WAGTAIL;
		int tool = bird ? 10006 : 10008;
		String toolName = bird ? "Bird snare" : "Box trap";
		boolean red = creature == RumourAssignment.Creature.RED_CHINCHOMPA;
		WorldPoint centre = trapArea(creature);
		WorldPoint position = client.getLocalPlayer().getWorldLocation();
		if (travelling) return travelStep(runtime, boxDestination);
		if (position.distanceTo(centre) > 8)
		{
			boxDestination = centre;
			return travelStep(runtime, boxDestination);
		}
		runtime.taskState(bird ? HuntersRumoursRuntime.State.HUNTING_SNARES : HuntersRumoursRuntime.State.HUNTING_BOXES);
		int tools = itemCount(tool);
		int catches = itemCount(bird ? 9978 : creature.boxCatchId());
		boolean busy = huntingBusy();
		int limit = HuntingSupplies.netTrapLimit(client.getRealSkillLevel(Skill.HUNTER));
		if (bird && !leave && boxTraps.size() < limit && tools > 0
			&& HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY))
				< HuntingSupplies.trapSlots(client.getItemContainer(InventoryID.INVENTORY), null, creature, boxTraps.size() + 1))
			snareBanking = true;
		leave |= snareBanking;
		boolean fillSlots = !leave && !busy && tools > 0
			&& boxTraps.values().stream().noneMatch(BoxTrapHunt::pending)
			&& boxTraps.values().stream().filter(BoxTrapHunt::ownsTrap).allMatch(trap ->
				Microbot.getRs2TileObjectCache().query().where(object -> trap.known(object.getId())
					&& object.getWorldLocation().equals(trap.site)).first() != null)
			&& Microbot.getRs2TileItemCache().query().withId(tool)
				.where(item -> item.getOwnership() == TileItem.OWNERSHIP_SELF
					&& boxTraps.containsKey(item.getWorldLocation())).first() == null;
		if (fillSlots && boxTraps.size() < limit && boxTraps.values().stream().allMatch(BoxTrapHunt::ownsTrap))
		{
			for (WorldPoint site : boxSites(creature))
			{
				if (boxTraps.containsKey(site)) continue;
				if (Microbot.getRs2TileObjectCache().query().where(object -> boxSiteOccupied(object, site)).first() != null) continue;
				if (Microbot.getRs2TileItemCache().query().withId(tool).where(item -> item.getWorldLocation().equals(site)).first() != null) continue;
				if (Microbot.getRs2NpcCache().query().withId(bird ? 5548 : creature == RumourAssignment.Creature.EMBERTAILED_JERBOA ? 13139 : red ? 2911 : 2910)
					.where(npc -> npc.getWorldLocation().distanceTo(centre) <= 8).first() == null) continue;
				boxTraps.put(site, new BoxTrapHunt(site, creature));
				return null;
			}
		}
		ArrayList<BoxTrapHunt> traps = new ArrayList<>(boxTraps.values());
		traps.sort((a, b) ->
		{
			int pending = Boolean.compare(b.pending(), a.pending());
			return pending != 0 || !fillSlots ? pending : Boolean.compare(a.ownsTrap(), b.ownsTrap());
		});
		for (BoxTrapHunt trap : traps)
		{
			if (!trap.ownsTrap() && Microbot.getRs2TileObjectCache().query().where(object -> boxSiteOccupied(object, trap.site)).first() != null)
			{
				boxTraps.remove(trap.site);
				return null;
			}
			Rs2TileObjectModel object = Microbot.getRs2TileObjectCache().query()
				.where(candidate -> trap.known(candidate.getId())
					&& candidate.getWorldLocation().equals(trap.site)).first();
			Rs2TileItemModel dropped = Microbot.getRs2TileItemCache().query().withId(tool)
				.where(candidate -> candidate.getOwnership() == TileItem.OWNERSHIP_SELF
					&& candidate.getWorldLocation().equals(trap.site)).first();
			boolean wasPending = trap.pending();
			BoxTrapHunt.Action action = trap.next(tick, object == null ? -1 : object.getId(), tools, catches, leave, busy, dropped != null);
			if (action == BoxTrapHunt.Action.WAIT && trap.missing() && dropped == null)
				return inspectMissingTrap(runtime, creature, trap.site);
			if (wasPending && !trap.pending() && action == BoxTrapHunt.Action.WAIT) return null;
			if (action == BoxTrapHunt.Action.CAUGHT || action == BoxTrapHunt.Action.CLEARED)
			{
				if (action == BoxTrapHunt.Action.CAUGHT) verifiedCatches++;
				boxTraps.remove(trap.site);
				trapCleared(trap.site);
				pendingEffect = null;
				return null;
			}
			if (action == BoxTrapHunt.Action.BLOCKED)
			{
				runtime.block("The " + toolName + " ownership or returned supplies were not verified. Inspect your trap before restarting; leave other players' traps alone.");
				return null;
			}
			if (action != BoxTrapHunt.Action.WAIT)
			{
				if (HuntingSupplies.freeSlots(client.getItemContainer(InventoryID.INVENTORY))
					< HuntingSupplies.trapSlots(client.getItemContainer(InventoryID.INVENTORY), null, creature, bird && action == BoxTrapHunt.Action.CHECK ? 1 : 0))
				{
					runtime.block("Free inventory space for your " + toolName + ", catch outputs and rare part, then resume safely.");
					return null;
				}
				if (action == BoxTrapHunt.Action.RECOVER)
					return dropped == null ? null : trapAction(runtime, trap.site, awaitEffect(runtime, "recover-box:" + trap.site + ":" + tools, tick,
						onDispatch(runtime, () -> trap.submitted(action, tick, tools, catches), dropped::pickup)));
				if (action == BoxTrapHunt.Action.SET && !position.equals(trap.site))
				{
					boxDestination = trap.site;
					return travelStep(runtime, boxDestination);
				}
				Runnable input = action == BoxTrapHunt.Action.SET ? () -> Rs2Inventory.interact(tool, "Lay")
					: object == null ? null : objectAction(object, action == BoxTrapHunt.Action.CHECK ? "Check" : "Dismantle");
				return trapAction(runtime, trap.site, onDispatch(runtime, () -> trap.submitted(action, tick, tools, catches),
					action == BoxTrapHunt.Action.SET ? checkpointPlacement(runtime, creature, trap.site, input) : input));
			}
			if (trap.pending()) return null;
		}
		if (snareBanking && boxTraps.isEmpty())
		{
			snareBanking = trapsPrepared = bankArrived = teleportsPrepared = false;
			bankEpoch = -1;
			pendingEffect = null;
			return null;
		}
		if (leave || busy) return null;
		if (boxTraps.containsKey(position))
		{
			boxDestination = centre;
			return travelStep(runtime, boxDestination);
		}
		if (boxTraps.size() >= limit) return null;
		if (tools == 0)
		{
			if (!boxTraps.isEmpty()) return null;
			trapsPrepared = bankArrived = teleportsPrepared = false;
			bankEpoch = -1;
			return null;
		}

		if (boxTraps.isEmpty()) runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_TARGET);
		return null;
	}

	static boolean boxSiteOccupied(Rs2TileObjectModel object, WorldPoint site)
	{
		if (object.getTileObjectType() != net.runelite.client.plugins.microbot.api.tileobject.models.TileObjectType.GAME) return false;
		WorldPoint origin = object.getWorldLocation();
		if (origin.getPlane() != site.getPlane()) return false;
		ObjectComposition composition = object.getObjectComposition();
		int width = composition == null ? 1 : Math.max(1, composition.getSizeX());
		int height = composition == null ? 1 : Math.max(1, composition.getSizeY());
		int dx = site.getX() - origin.getX();
		int dy = site.getY() - origin.getY();
		return dx >= 0 && dy >= 0 && ((dx < width && dy < height) || (dx < height && dy < width));
	}

	static WorldPoint[] boxSites(RumourAssignment.Creature creature)
	{
		if (creature == RumourAssignment.Creature.TROPICAL_WAGTAIL)
			return new WorldPoint[]{new WorldPoint(2512, 2912, 0), new WorldPoint(2514, 2912, 0),
				new WorldPoint(2512, 2914, 0), new WorldPoint(2514, 2914, 0),
				new WorldPoint(2511, 2913, 0), new WorldPoint(2513, 2911, 0)};
		if (creature == RumourAssignment.Creature.EMBERTAILED_JERBOA)
			return new WorldPoint[]{new WorldPoint(1517, 3045, 0), new WorldPoint(1517, 3046, 0),
				new WorldPoint(1517, 3047, 0), new WorldPoint(1518, 3045, 0), new WorldPoint(1518, 3047, 0),
				new WorldPoint(1519, 3045, 0), new WorldPoint(1519, 3046, 0), new WorldPoint(1519, 3047, 0)};
		boolean red = creature == RumourAssignment.Creature.RED_CHINCHOMPA;
		return red ? new WorldPoint[]{new WorldPoint(2499, 2905, 0), new WorldPoint(2499, 2906, 0), new WorldPoint(2499, 2907, 0),
			new WorldPoint(2499, 2908, 0), new WorldPoint(2500, 2904, 0), new WorldPoint(2500, 2905, 0),
			new WorldPoint(2500, 2906, 0), new WorldPoint(2500, 2907, 0), new WorldPoint(2500, 2908, 0),
			new WorldPoint(2501, 2905, 0), new WorldPoint(2501, 2907, 0), new WorldPoint(2501, 2908, 0),
			new WorldPoint(2502, 2906, 0), new WorldPoint(2502, 2907, 0), new WorldPoint(2502, 2908, 0),
			new WorldPoint(2503, 2906, 0), new WorldPoint(2503, 2907, 0), new WorldPoint(2503, 2908, 0)}
			: new WorldPoint[]{new WorldPoint(2337, 3591, 0), new WorldPoint(2338, 3591, 0), new WorldPoint(2338, 3592, 0),
			new WorldPoint(2338, 3595, 0), new WorldPoint(2339, 3591, 0), new WorldPoint(2339, 3592, 0),
			new WorldPoint(2339, 3594, 0), new WorldPoint(2339, 3595, 0), new WorldPoint(2340, 3591, 0),
			new WorldPoint(2340, 3592, 0), new WorldPoint(2340, 3593, 0), new WorldPoint(2340, 3594, 0),
			new WorldPoint(2340, 3595, 0), new WorldPoint(2341, 3591, 0), new WorldPoint(2341, 3592, 0),
			new WorldPoint(2341, 3593, 0), new WorldPoint(2341, 3594, 0), new WorldPoint(2341, 3595, 0)};
	}

	static String bankOption(ObjectComposition composition)
	{
		if (objectActionIndex(composition, "Bank") >= 0) return "Bank";
		return composition != null && "Bank chest".equals(composition.getName())
			&& objectActionIndex(composition, "Use") >= 0 ? "Use" : null;
	}

	static int objectActionIndex(ObjectComposition composition, String option)
	{
		if (composition == null || composition.getActions() == null) return -1;
		int index = Arrays.asList(composition.getActions()).indexOf(option);
		return index <= 3 ? index : -1;
	}

	private Runnable objectAction(Rs2TileObjectModel object, String option)
	{
		ObjectComposition composition = object.getObjectComposition();
		int index = objectActionIndex(composition, option);
		if (index < 0 || index > 3) return null;
		WorldPoint location = object.getWorldLocation();
		NewMenuEntry entry = new NewMenuEntry().param0(location.getX() - object.getWorldView().getBaseX())
			.param1(location.getY() - object.getWorldView().getBaseY())
			.opcode(MenuAction.GAME_OBJECT_FIRST_OPTION.getId() + index).identifier(object.getId()).itemId(-1)
			.option(option).target(composition.getName()).setWorldViewId(object.getWorldView().getId()).gameObject(object);
		Rectangle bounds = Rs2UiHelper.getObjectClickbox(object);
		return () -> Microbot.doInvoke(entry, bounds);
	}

	private Runnable npcAction(NPC npc, String option)
	{
		NPCComposition composition = npc == null ? null : npc.getTransformedComposition();
		String[] actions = composition == null ? null : composition.getActions();
		if (actions == null) return null;
		for (int i = 0; i < actions.length; i++)
		{
			if (!option.equals(actions[i]) || (i != 0 && i != 2)) continue;
			NewMenuEntry entry = new NewMenuEntry().param0(0).param1(0)
				.opcode((i == 0 ? MenuAction.NPC_FIRST_OPTION : MenuAction.NPC_THIRD_OPTION).getId())
				.identifier(npc.getIndex()).itemId(-1).target(composition.getName()).actor(npc).option(option);
			Rectangle bounds = Rs2UiHelper.getActorClickbox(npc);
			return () -> Microbot.doInvoke(entry, bounds);
		}
		return null;
	}

	private boolean rentalConfirmation(Client client)
	{
		if (client.getItemContainer(InventoryID.EQUIPMENT) == null || !Rs2Dialogue.hasContinue()) return false;
		Item weapon = client.getItemContainer(InventoryID.EQUIPMENT).getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return FalconryHunt.rentalConfirmation(Rs2Dialogue.getDialogueText(), weapon != null && weapon.getId() == 10024);
	}

	private boolean hasFalcon(Client client)
	{
		if (client.getItemContainer(InventoryID.EQUIPMENT) == null) return false;
		Item weapon = client.getItemContainer(InventoryID.EQUIPMENT).getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return weapon != null && (weapon.getId() == 10023 || weapon.getId() == 10024);
	}

	void falconMessage(String text)
	{
		hunt.message(text);
	}

	private Runnable recoverFalcon(HuntersRumoursRuntime runtime)
	{
		runtime.taskState(HuntersRumoursRuntime.State.RECOVERING_FALCON);
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		Item weapon = equipment == null ? null : equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null || (weapon.getId() != 10023 && weapon.getId() != 10024))
		{
			runtime.block("The falconry glove is unavailable after the falcon was lost. Check equipment and restart.");
			return null;
		}
		if (client.getHintArrowNpc() != null)
			return awaitEffect(runtime, "falcon-loss-hint", tick, null);
		if (recoveryTalkSubmitted && weapon.getId() == 10024 && !Rs2Dialogue.isInDialogue())
		{
			hunt.recovered();
			recoveryTalkSubmitted = atHuntingArea = false;
			pendingEffect = null;
			return null;
		}
		if (Rs2Dialogue.hasSelectAnOption())
		{
			if (recoveryTalkSubmitted && Rs2Dialogue.getDialogueOption("Yes, please.", true) != null)
				return awaitEffect(runtime, "recover-option", tick,
					() -> Rs2Dialogue.keyPressForDialogueOption("Yes, please.", true));
			runtime.block("The free falcon recovery option is unavailable. Reclaim the falcon from Matthias and restart.");
			return null;
		}
		if (Rs2Dialogue.hasContinue())
		{
			String text = dialogueText();
			if (recoveryTalkSubmitted && FalconryHunt.recoveryDialogue(text))
				return awaitEffect(runtime, "recover-dialogue:" + text, tick, Rs2Dialogue::clickContinue);
			runtime.block("The falcon recovery dialogue was not recognised. Reclaim the falcon from Matthias and restart.");
			return null;
		}
		if (recoveryTalkSubmitted) return awaitEffect(runtime, "recover-response", tick, null);
		if (travelling || client.getLocalPlayer().getWorldLocation().distanceTo(FALCONRY) > 3)
			return travelStep(runtime, FALCONRY);
		Rs2NpcModel matthias = Microbot.getRs2NpcCache().query().withIds(1340, 1341).nearest();
		if (matthias == null)
		{
			runtime.block("Matthias is unavailable. Stay inside the falconry area, reclaim the falcon and restart.");
			return null;
		}
		Runnable talk = npcAction(matthias.getNpc(), "Talk-to");
		return awaitEffect(runtime, "recover-talk", tick, talk == null ? null : () ->
		{
			recoveryTalkSubmitted = true;
			talk.run();
		});
	}

	private Runnable releaseFalcon(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		int tick = client.getTickCount();
		if (rentalConfirmation(client))
			return awaitEffect(runtime, "rental-confirmation", tick, Rs2Dialogue::clickContinue);
		if (Rs2Dialogue.hasSelectAnOption())
		{
			for (String option : new String[]{"I think I'll leave it for now.", "Actually, I think I'll leave it for now."})
			{
				if (releasing && Rs2Dialogue.getDialogueOption(option, true) != null)
					return awaitEffect(runtime, "release-option", tick,
						() -> Rs2Dialogue.keyPressForDialogueOption(option, true));
			}
			runtime.block("The falcon release option is unavailable. Return the falcon to Matthias and restart.");
			return null;
		}
		if (Rs2Dialogue.hasContinue())
		{
			String text = Rs2Dialogue.getDialogueText();
			Widget playerText = client.getWidget(InterfaceID.ChatRight.TEXT);
			if (playerText != null && !playerText.isHidden()) text = playerText.getText();
			if (releasing && FalconryHunt.releaseDialogue(text))
				return awaitEffect(runtime, "release-dialogue:" + text, tick, Rs2Dialogue::clickContinue);
			runtime.block("The falcon release dialogue was not recognised. Return the falcon to Matthias and restart.");
			return null;
		}
		if (releasing) return awaitEffect(runtime, "release-response", tick, null);
		if (travelling || client.getLocalPlayer().getWorldLocation().distanceTo(FALCONRY) > 3)
			return travelStep(runtime, FALCONRY);
		Rs2NpcModel matthias = Microbot.getRs2NpcCache().query().withIds(1340, 1341).nearest();
		if (matthias == null)
		{
			runtime.block("Matthias is unavailable. Return the falcon before travelling and restart.");
			return null;
		}
		Runnable talk = npcAction(matthias.getNpc(), "Talk-to");
		return awaitEffect(runtime, "release-talk", tick, talk == null ? null : () ->
		{
			releasing = true;
			talk.run();
		});
	}

	private Runnable travelStep(HuntersRumoursRuntime runtime, WorldPoint destination)
	{
		Client client = Microbot.getClient();
		WorldPoint position = client.getLocalPlayer().getWorldLocation();
		try
		{
			String status = runtime.walkStatus();
			if (travelling && ("CANCELLED".equals(status) || "IDLE".equals(status))) travelling = false;
			if (travelling)
			{
				if ("ARRIVED".equals(status) && (travelDestination == null || position.distanceTo(travelDestination) <= 3))
				{
					runtime.finishRoute();
					travelling = false;
					pendingEffect = null;
					if (travelDestination == null) bankArrived = true;
					return null;
				}
				if ("BLOCKED".equals(status))
					runtime.block("The route stopped progressing. Check transport access and restart.");
				return null;
			}
		}
		catch (ReflectiveOperationException exception)
		{
			runtime.block("Could not verify the route status. Restart Walker and Hunters' Rumours.");
			return null;
		}
		return () ->
		{
			try
			{
				travelDestination = destination;
				travelling = destination == null ? runtime.nearestBank() : runtime.walkTo(destination);
				if (!travelling) runtime.block("Efficient Walker rejected the route. Check transport access and restart.");
			}
			catch (ReflectiveOperationException exception)
			{
				runtime.block("Could not request the route. Restart Walker and Hunters' Rumours.");
			}
		};
	}

	private boolean huntingBusy()
	{
		Client client = Microbot.getClient();
		return client.getLocalPlayer() == null || client.getVarbitValue(VarbitID.BUSY) != 0
			|| client.getLocalPlayer().getAnimation() != -1 || Rs2Player.isMoving();
	}

	private Runnable awaitEffect(HuntersRumoursRuntime runtime, String effect, int tick, Runnable action)
	{
		if (effect.equals(pendingEffect) || action == null)
		{
			runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_RESULT);
			return null;
		}
		return onDispatch(runtime, () ->
		{
			pendingEffect = effect;
		}, action);
	}

	private Runnable onDispatch(HuntersRumoursRuntime runtime, Runnable submitted, Runnable action)
	{
		if (action == null) return null;
		long generation = inputGeneration.get();
		return () ->
		{
			if (generation != inputGeneration.get() || Thread.currentThread().isInterrupted()
				|| Microbot.pauseAllScripts.get() || !runtime.mayAct()) return;
			submitted.run();
			action.run();
		};
	}

	void requestAssignment(HuntersRumoursRuntime runtime, int tick)
	{
		if (!runtime.mayRequestAssignment()) return;
		submitInput(runtime, true);
	}

	private Runnable switchHunter(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		if (!runtime.maySwitchHunter()) return null;
		if (runtime.selectedHunter() == RumourAssignment.Hunter.WOLF
			&& net.runelite.api.Quest.AT_FIRST_LIGHT.getState(client) != net.runelite.api.QuestState.FINISHED)
		{
			runtime.block("Wolf requires At First Light. Complete the quest and restart.");
			return null;
		}
		if (!client.getWorldType().contains(WorldType.MEMBERS) || client.getRealSkillLevel(Skill.HUNTER) < runtime.selectedHunter().minimumLevel)
		{
			runtime.block(runtime.selectedHunter() + " requires a members world and " + runtime.selectedHunter().minimumLevel + " Hunter without boosts. Meet those requirements and restart.");
			return null;
		}
		if (!deadfalls.isEmpty() || !netTraps.isEmpty() || !boxTraps.isEmpty() || hasOwnedPits()
			|| hunt.awaitingCatch() || butterfly.pending() || trackingBefore != null || herbiboarReceipt != null)
		{
			runtime.block("An unfinished hunt is still tracked. Recover its equipment and finish it before switching hunters.");
			return null;
		}
		if (client.getItemContainer(InventoryID.INVENTORY) == null) return null;
		for (Item item : client.getItemContainer(InventoryID.INVENTORY).getItems())
		{
			if (item != null && item.getQuantity() > 0 && RumourAssignment.isRarePart(item.getId()))
			{
				runtime.block("A rare creature part is carried. Hand it to its assigning hunter before switching hunters.");
				return null;
			}
		}
		if (hasFalcon(client) || (releasing && Rs2Dialogue.isInDialogue())) return releaseFalcon(runtime);
		if (travelling || !RumourHandIn.atGuild(client.getLocalPlayer().getWorldLocation())) return travelStep(runtime, GUILD);
		int tick = client.getTickCount();
		String switchOption = runtime.selectedHunter() == RumourAssignment.Hunter.GILMAN ? "Yes, go back to his old one." : "Yes.";
		if (Rs2Dialogue.hasSelectAnOption())
		{
			if (hunterSwitchQuestion(runtime.selectedHunter()) && Rs2Dialogue.getDialogueOption(switchOption, true) != null)
				return awaitEffect(runtime, "hunter-switch-confirmation", tick, () ->
				{
					boolean allowed = Microbot.getClientThread().runOnClientThreadOptional(() ->
						runtime.maySwitchHunter() && hunterSwitchQuestion(runtime.selectedHunter())
							&& HuntingSupplies.partBlocker(Microbot.getClient().getItemContainer(InventoryID.INVENTORY), -1) == null).orElse(false);
					if (allowed) Rs2Dialogue.keyPressForDialogueOption(switchOption, true);
				});
		}
		String text = dialogueText();
		Widget speaker = client.getWidget(InterfaceID.ChatLeft.NAME);
		if (speaker != null && !speaker.isHidden() && runtime.assignment().matchesDialogue(speaker.getText(), text)
			&& Rs2Dialogue.hasContinue())
			return awaitEffect(runtime, "current-rumour-dialogue:" + text, tick, Rs2Dialogue::clickContinue);
		if (speakingTo(runtime.selectedHunter().npcName) && RumourAssignment.switchContinuation(text, runtime.selectedHunter()) && Rs2Dialogue.hasContinue())
			return awaitEffect(runtime, "hunter-switch:" + text, tick, Rs2Dialogue::clickContinue);
		if (Rs2Dialogue.isInDialogue())
		{
			runtime.block("The open dialogue is not the selected hunter's verified switch confirmation. Finish or close it without resetting rumours, then restart.");
			return null;
		}
		Rs2NpcModel hunter = Microbot.getRs2NpcCache().query().withId(runtime.selectedHunter().npcId).nearest();
		if (hunter == null)
			return travelStep(runtime, GUILD);
		return awaitEffect(runtime, "hunter-switch-request", tick, npcAction(hunter.getNpc(), "Rumour"));
	}

	private boolean hunterSwitchQuestion(RumourAssignment.Hunter hunter)
	{
		Widget options = Microbot.getClient().getWidget(InterfaceID.Chatmenu.OPTIONS);
		Widget[] children = options == null || options.isHidden() ? null : options.getDynamicChildren();
		return children != null && children.length > 0 && children[0] != null
			&& RumourAssignment.switchQuestion(children[0].getText(), hunter);
	}

	private Runnable prepareAssignmentRequest(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		if (!runtime.mayRequestAssignment() || Microbot.pauseAllScripts.get()
			|| client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null
			|| client.isWidgetSelected()) return null;
		if (handingIn) return handInStep(runtime);
		if (HuntingSupplies.partBlocker(client.getItemContainer(InventoryID.INVENTORY), -1) != null)
		{
			for (int whistle : new int[]{29271, 29273, 29275, 33120})
			{
				if (itemCount(whistle) < 1) continue;
				int tick = client.getTickCount();
				if (!runtime.assignmentRequestReady(tick)) return null;
				return onDispatch(runtime, () -> { }, () ->
				{
					if (runtime.beginAssignmentRequest(tick)) Rs2Inventory.interact(whistle, "Rumour");
				});
			}
			runtime.block("A rare part is carried but its current assignment is unknown. Open the assigning hunter's existing rumour dialogue, then restart.");
			return null;
		}
		if (hasFalcon(client) || (releasing && Rs2Dialogue.isInDialogue())) return releaseFalcon(runtime);
		if (travelling) return travelStep(runtime, GUILD);
		releasing = false;
		if (!countRead || (Rs2Dialogue.isInDialogue() && RumourHandIn.countDialogue(dialogueText())))
			return readCountBeforeAssignment(runtime);
		if (Rs2Dialogue.isInDialogue())
		{
			if (rentalConfirmation(client))
				return awaitEffect(runtime, "rental-confirmation", client.getTickCount(), Rs2Dialogue::clickContinue);
			runtime.block("Finish the open dialogue without changing stored rumours, then restart.");
			return null;
		}
		if (runtime.selectedHunter() == RumourAssignment.Hunter.WOLF
			&& net.runelite.api.Quest.AT_FIRST_LIGHT.getState(client) != net.runelite.api.QuestState.FINISHED)
		{
			runtime.block("Wolf requires At First Light. Complete the quest and restart.");
			return null;
		}
		if (!client.getWorldType().contains(WorldType.MEMBERS) || client.getRealSkillLevel(Skill.HUNTER) < runtime.selectedHunter().minimumLevel)
		{
			runtime.block(runtime.selectedHunter() + " requires a members world and " + runtime.selectedHunter().minimumLevel + " Hunter. Meet those requirements before restarting.");
			return null;
		}
		Rs2NpcModel model = Microbot.getRs2NpcCache().query().withId(runtime.selectedHunter().npcId).nearest();
		if (model == null)
		{
			return travelStep(runtime, GUILD);
		}
		int tick = client.getTickCount();
		if (!runtime.assignmentRequestReady(tick)) return null;
		NPC npc = model.getNpc();
		NPCComposition composition = npc.getTransformedComposition();
		String[] actions = composition == null ? null : composition.getActions();
		if (actions == null || actions.length < 3 || !"Rumour".equals(actions[2]))
		{
			runtime.block("The selected hunter's Rumour action is unavailable. Finish any introduction and restart.");
			return null;
		}
		NewMenuEntry entry = new NewMenuEntry().param0(0).param1(0)
			.opcode(MenuAction.NPC_THIRD_OPTION.getId()).identifier(npc.getIndex()).itemId(-1)
			.target(composition.getName()).actor(npc).option("Rumour");
		Rectangle bounds = Rs2UiHelper.getActorClickbox(npc);
		return onDispatch(runtime, () -> { }, () ->
		{
			if (runtime.beginAssignmentRequest(tick)) Microbot.doInvoke(entry, bounds);
		});
	}

	private Runnable readCountBeforeAssignment(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		if (!RumourHandIn.atGuild(client.getLocalPlayer().getWorldLocation())) return travelStep(runtime, GUILD);
		int tick = client.getTickCount();
		String text = dialogueText();
		int total = speakingTo("Guild Scribe Verity") ? RumourHandIn.completionCount(text) : -1;
		if (total >= 0)
		{
			countRead = true;
			runtime.observeTotal(total);
			if (!runtime.mayAct()) return null;
		}
		Widget speaker = client.getWidget(InterfaceID.ChatLeft.NAME);
		boolean assignmentDialogue = speaker != null && !speaker.isHidden()
			&& RumourAssignment.fromDialogue(speaker.getText(), text) != null;
		Widget playerDialogue = client.getWidget(InterfaceID.ChatRight.TEXT);
		boolean playerSpeaking = playerDialogue != null && !playerDialogue.isHidden();
		boolean introduction = (playerSpeaking || speakingTo("Guild Scribe Verity"))
			&& RumourHandIn.introductionDialogue(text, playerSpeaking);
		boolean rewardDialogue = (playerSpeaking || speakingTo(runtime.selectedHunter().npcName))
			&& RumourHandIn.rewardDialogue(text, playerSpeaking);
		if (Rs2Dialogue.hasContinue() && (RumourHandIn.countDialogue(text) || assignmentDialogue || introduction || rewardDialogue))
			return awaitEffect(runtime, "initial-count:" + text, tick, Rs2Dialogue::clickContinue);
		if (Rs2Dialogue.hasSelectAnOption()
			&& RumourAssignment.normalize(Rs2Dialogue.getQuestion()).equals("get another rumour")
			&& Rs2Dialogue.getDialogueOption("No.", true) != null)
			return awaitEffect(runtime, "initial-decline-rumour", tick,
				() -> Rs2Dialogue.keyPressForDialogueOption("No.", true));
		if (Rs2Dialogue.hasSelectAnOption()
			&& Rs2Dialogue.getDialogueOption("How many rumours have I completed?", true) != null)
			return awaitEffect(runtime, "initial-count-option", tick,
				() -> Rs2Dialogue.keyPressForDialogueOption("How many rumours have I completed?", true));
		String exit = RumourHandIn.exitOption(runtime.selectedHunter());
		if (Rs2Dialogue.hasSelectAnOption() && Rs2Dialogue.getDialogueOption(exit, true) != null)
			return awaitEffect(runtime, "initial-exit", tick, () -> Rs2Dialogue.keyPressForDialogueOption(exit, true));
		if (Rs2Dialogue.isInDialogue())
		{
			runtime.block("Finish the open dialogue without changing stored rumours, then restart.");
			return null;
		}
		Rs2NpcModel verity = Microbot.getRs2NpcCache().query().withId(13127).nearest();
		if (verity == null)
		{
			runtime.block("Verity is unavailable to read the lifetime total. Check the Burrow and restart.");
			return null;
		}
		return awaitEffect(runtime, "initial-count-talk", tick, npcAction(verity.getNpc(), "Talk-to"));
	}

	private int itemCount(int id)
	{
		return HuntingSupplies.count(Microbot.getClient().getItemContainer(InventoryID.INVENTORY), id);
	}

	private String dialogueText()
	{
		Client client = Microbot.getClient();
		Widget playerText = client.getWidget(InterfaceID.ChatRight.TEXT);
		if (playerText != null && !playerText.isHidden()) return playerText.getText();
		Widget itemText = client.getWidget(InterfaceID.Objectbox.TEXT);
		if (itemText != null && !itemText.isHidden()) return itemText.getText();
		return Rs2Dialogue.getDialogueText();
	}

	private boolean speakingTo(String name)
	{
		Widget speaker = Microbot.getClient().getWidget(InterfaceID.ChatLeft.NAME);
		return speaker != null && !speaker.isHidden() && speaker.getText().equals(name);
	}

	private Runnable handInStep(HuntersRumoursRuntime runtime)
	{
		Client client = Microbot.getClient();
		if (!handingIn)
		{
			handInPart = runtime.assignment() == null ? -1 : runtime.assignment().creature.rarePartId();
			if (handInPart <= 0 || itemCount(handInPart) < 1)
			{
				runtime.block("The current assignment's rare part is not verified. Preserve carried parts and confirm the assignment before restarting.");
				return null;
			}
			if (beforeCount < 0 && runtime.completedTotal() >= 0)
			{
				beforeCount = runtime.completedTotal();
				beforeSacks = itemCount(runtime.selectedHunter().sackId);
			}
		}
		handingIn = true;
		runtime.taskState(HuntersRumoursRuntime.State.HANDING_IN);
		if (runtime.assignment() != null && runtime.assignment().hunter != runtime.selectedHunter())
		{
			runtime.block("The active rumour belongs to another hunter. Keep the rare part and its assignment.");
			return null;
		}
		if (hasFalcon(client) || (releasing && Rs2Dialogue.isInDialogue())) return releaseFalcon(runtime);
		if (travelling) return travelStep(runtime, GUILD);
		releasing = false;
		if (!RumourHandIn.atGuild(client.getLocalPlayer().getWorldLocation())) return travelStep(runtime, GUILD);
		int tick = client.getTickCount();
		String text = dialogueText();
		int count = speakingTo("Guild Scribe Verity") ? RumourHandIn.completionCount(text) : -1;
		if (beforeCount < 0 && count >= 0)
		{
			beforeCount = count;
			runtime.observeTotal(count);
			beforeSacks = itemCount(runtime.selectedHunter().sackId);
			return awaitEffect(runtime, "count-before:" + text, tick, Rs2Dialogue::clickContinue);
		}
		if (speakingTo(runtime.selectedHunter().npcName) && RumourHandIn.acknowledged(text)) handInAcknowledged = true;
		boolean received = handInAcknowledged && itemCount(handInPart) == 0 && itemCount(runtime.selectedHunter().sackId) == beforeSacks + 1;
		if (received && count >= 0)
		{
			if (RumourHandIn.verified(beforeCount, count, handInAcknowledged, itemCount(handInPart), beforeSacks, itemCount(runtime.selectedHunter().sackId)))
				runtime.completeRumour(count);
			else runtime.block("The Guild completion count did not match this hand-in. Check Verity's records before restarting.");
			return null;
		}
		if (Rs2Dialogue.hasSelectAnOption())
		{
			String option = null;
			Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
			Widget[] children = options == null ? null : options.getDynamicChildren();
			String question = children == null || children.length == 0 ? "" : RumourAssignment.normalize(children[0].getText());
			if (handInAcknowledged && question.equals("get another rumour")) option = "No.";
			else if (handInAcknowledged && Rs2Dialogue.getDialogueOption(RumourHandIn.exitOption(runtime.selectedHunter()), true) != null)
				option = RumourHandIn.exitOption(runtime.selectedHunter());
			else if ((beforeCount < 0 || received) && Rs2Dialogue.getDialogueOption("How many rumours have I completed?", true) != null)
				option = "How many rumours have I completed?";
			if (option == null)
			{
				runtime.block("The expected hand-in or count option was unavailable. Preserve the current rumours and check the dialogue.");
				return null;
			}
			String selected = option;
			return awaitEffect(runtime, "hand-in-option:" + selected + ":" + received, tick,
				() -> Rs2Dialogue.keyPressForDialogueOption(selected, true));
		}
		if (Rs2Dialogue.hasContinue())
		{
			Widget playerDialogue = client.getWidget(InterfaceID.ChatRight.TEXT);
			boolean playerSpeaking = playerDialogue != null && !playerDialogue.isHidden();
			if (RumourHandIn.countDialogue(text) || handInSubmitted
				&& (playerSpeaking || speakingTo(runtime.selectedHunter().npcName)) && RumourHandIn.rewardDialogue(text, playerSpeaking))
			{
				String key = !handInSubmitted && count >= 0 ? "count-before:" + text : "hand-in-dialogue:" + text + ":" + received;
				return awaitEffect(runtime, key, tick, Rs2Dialogue::clickContinue);
			}
			runtime.block("The hand-in dialogue was not recognised. Preserve the current rumours and check the dialogue.");
			return null;
		}
		if (beforeCount < 0 || received)
		{
			Rs2NpcModel verity = Microbot.getRs2NpcCache().query().withId(13127).nearest();
			if (verity == null)
			{
				runtime.block("Verity is unavailable to verify the completion count. Check the Burrow and restart.");
				return null;
			}
			return awaitEffect(runtime, "count-talk:" + received, tick, npcAction(verity.getNpc(), "Talk-to"));
		}
		if (handInSubmitted)
		{
			runtime.taskState(HuntersRumoursRuntime.State.WAITING_FOR_RESULT);
			return null;
		}
		Rs2NpcModel hunter = Microbot.getRs2NpcCache().query().withId(runtime.selectedHunter().npcId).nearest();
		if (hunter == null)
		{
			runtime.block("The selected hunter is unavailable for the hand-in. Preserve the rare part and check the Burrow.");
			return null;
		}
		return onDispatch(runtime, () ->
		{
			handInSubmitted = true;
		}, npcAction(hunter.getNpc(), "Rumour"));
	}

	void cancelInput()
	{
		inputGeneration.incrementAndGet();
		if (input != null) input.cancel(true);
	}

	@Override
	public void shutdown()
	{
		cancelInput();
		scheduledExecutorService.shutdownNow();
	}
}
