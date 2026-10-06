package net.runelite.client.plugins.microbot.fremenniktrials;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfigNode;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.questhelper.QuestHelperPlugin;
import net.runelite.client.plugins.microbot.questhelper.questhelpers.QuestHelper;
import net.runelite.client.plugins.microbot.questhelper.requirements.Requirement;
import net.runelite.client.plugins.microbot.questhelper.requirements.item.ItemRequirement;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import net.runelite.client.plugins.microbot.questhelper.steps.PuzzleWrapperStep;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import net.runelite.client.util.Text;

/** Resumable quest controller. Walker owns every route; server state owns progress. */
@PluginDescriptor(name = "Fremennik Trials", description = "Completes the seven trials and collects the quest reward",
	tags = {"quest", "fremennik"}, enabledByDefault = false, version = "1.0.0")
public final class FremennikTrialsPlugin extends Plugin
{
	static final String WALKER = "net.runelite.client.plugins.microbot.efficientwalker.EfficientWalkerPlugin";
	static final String GUARDIAN = "net.runelite.client.plugins.microbot.guardian.GuardianPlugin";
	static final String DRAUGEN = "net.runelite.client.plugins.microbot.draugen.DraugenPlugin";
	static final WorldPoint RELLEKKA = new WorldPoint(2658, 3654, 0);
	@Inject Client client;
	@Inject PluginManager pluginManager;
	@Inject ConfigManager configManager;
	private Plugin walkerPlugin, guardian, draugen;
	private Object walker, routeToken;
	private QuestHelper quest;
	private QuestStep step, delegatedStep;
	private boolean dialogueRoute;
	private int routeObservedAt;
	private String routeProgress;
	private String stepName = "", status = "Stopped", error, routePurpose;
	private boolean running, huntStarted, combatPrepared, emptyPrepared;
	private boolean journalSynced;
	private int questProgress;
	private volatile boolean startRequested;
	private Widget hoveredLegacy;
	private int hoverTick;
	private int stableTicks, sceneBaseX = -1, sceneBaseY = -1, scenePlane = -1;
	private int stepsCompleted, pendingAt;
	private BooleanSupplier pending;
	private String pendingDescription;
	private QuestSupplies supplies;
	private NPC pendingAttack;
	private int attackRequestedAt;
	private String lastCombatIdentity;
	private final Map<String, QuestStep> namedSteps = new LinkedHashMap<>();

	@Override protected void startUp() { startRequested = true; status = "Starting quest; waiting for a logged-in game tick"; }
	@Override protected void shutDown() { stop("Stopped"); }

	@Subscribe public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN) { return; }
		stableTicks = 0;
		if (e.getGameState() != GameState.LOADING && running) { stop("Stopped after login transition; resume after login"); }
	}

	@Subscribe public void onGameTick(GameTick tick)
	{
		if (startRequested)
		{
			startRequested=false;
			Map<String,Object> result=agentControl("start",Map.of());
			if (!Boolean.TRUE.equals(result.get("accepted"))) { fail(String.valueOf(result.get("error"))); }
			return;
		}
		if (!running) { return; }
		try { advance(); }
		catch (RuntimeException e) { fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
	}

	private void advance()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) { return; }
		bind();
		if (client.getLocalPlayer().isDead()) { fail("Player died; recover through Walker before resuming"); return; }
		if (Quest.THE_FREMENNIK_TRIALS.getState(client) == QuestState.FINISHED)
		{
			cancelRoute();
			if (pending != null) { if (pending.getAsBoolean()) { pending=null; } else { return; } }
			Widget close = findWidget("Close", null, 153);
			if (close != null) { send("Close quest reward", () -> !visible(client.getWidget(153, 0)), () -> widgetAction(close, "Close")); return; }
			stop("Complete: The Fremennik Trials reward confirmed");
			return;
		}
		if (questProgress != client.getVarpValue(347))
		{
			questProgress=client.getVarpValue(347);journalSynced=false;
			cancelRoute();delegatedStep=null;dialogueRoute=false;
		}
		if(inKoschei(client.getLocalPlayer().getWorldLocation()))
		{
			for(NPC actor:client.getNpcs()){if(revivedKoschei(actor)){actor.setDead(false);}}
		}
		NPC fourth = npc(NpcID.VIKING_ENEMY4);
		if (inKoschei(client.getLocalPlayer().getWorldLocation()) && fourth != null)
		{
			cancelRoute();
			if (!Boolean.TRUE.equals(call(guardian, "prepareKoscheiSafeDefeat", new Class<?>[]{NPC.class}, fourth)))
			{
				status = "Waiting for Koschei's fourth round to engage";
				attack(fourth, false);
			}
			else { status = "Fourth round: waiting for the trial's safe defeat and upstairs transition"; }
			return;
		}
		if(inKoschei(client.getLocalPlayer().getWorldLocation()))
		{ call(guardian,"prepareKoscheiCombat",new Class<?>[]{NPC.class},npc(NpcID.VIKING_ENEMY1,NpcID.VIKING_ENEMY2,NpcID.VIKING_ENEMY3)); }
		if (!Boolean.TRUE.equals(call(guardian, "canEncounterAct")))
		{
			status = "Waiting for Guardian safety";
			return;
		}
		if (!stable()) { status = "Waiting for two stable game ticks"; return; }
		if (!cameraReady()) { return; }
		if (pending != null)
		{
			if (!pending.getAsBoolean())
			{
				status = "Waiting: " + pendingDescription;
				if (client.getTickCount() - pendingAt > 100) { fail("Unconfirmed " + pendingDescription + "; inspect state before resuming (no retry sent)"); }
				return;
			}
			pending = null; pendingDescription = null;
			return;
		}
		QuestHelperPlugin helper = (QuestHelperPlugin) unique(QuestHelperPlugin.class.getName());
		if (helper.getSelectedQuest() == null)
		{
			send("Select Fremennik Trials helper", () -> helper.getSelectedQuest() != null,
				() -> require(helper.startQuestHelper("The Fremennik Trials"), "Cannot select Fremennik Trials"));
			return;
		}
		if (!helper.getSelectedQuest().getClass().getSimpleName().equals("TheFremennikTrials"))
		{
			fail("Another quest is selected; select The Fremennik Trials and resume"); return;
		}
		if (quest != helper.getSelectedQuest()) { quest = helper.getSelectedQuest(); indexSteps(); }
		require(quest.getConfig().solvePuzzles(), "Enable Quest Helper's Solve puzzles and resume");
		QuestStep current = active(quest.getCurrentStep());
		if (current == null) { status = "Waiting for Quest Helper state"; return; }
		if (current != step)
		{
			if (step != null) { stepsCompleted++; }
			step = current; stepName = nameOf(step); pendingAttack = null;
			if (huntStarted && !stepName.equals("huntDraugen")) { call(draugen, "stopEncounter"); huntStarted = false; }
		}
		if (supplies != null)
		{
			if (supplies.tick()) { supplies = null; }
			return;
		}
		if (routeToken != null)
		{
			Map<?, ?> state = control();
			if (!Boolean.TRUE.equals(state.get("owned"))) { fail("Walker ownership changed; inspect the manual or safety route before resuming"); return; }
			if (state.get("failure") != null || "BLOCKED".equals(state.get("state"))) { fail("Walker stopped: " + state.get("failure")); return; }
			if (Boolean.TRUE.equals(state.get("active")))
			{
				String progress=client.getLocalPlayer().getWorldLocation()+":"+stepName+":"+inventorySignature()+":"+dialogueVisible();
				if(!progress.equals(routeProgress)){routeProgress=progress;routeObservedAt=client.getTickCount();}
				if(client.getTickCount()-routeObservedAt>100){fail("No confirmed progress on "+routePurpose+"; inspect before resuming (no retry sent)");return;}
				status=routePurpose;return;
			}
			cancelRoute();
			if (delegatedStep != null && step == delegatedStep && !dialogueRoute) { fail("Quest step stopped without advancing; inspect " + stepName); return; }
			delegatedStep = null; dialogueRoute=false;
		}
		if (dialogueVisible())
		{
			startRoute("continueQuestDialogue",walkerPlugin,"Completing quest dialogue");
			delegatedStep=step;dialogueRoute=true;return;
		}
		if (inKoschei(client.getLocalPlayer().getWorldLocation())) { koschei(); return; }
		if (!journalSynced) { syncJournal(); return; }
		if (step.getClass().getSimpleName().equals("QuestSyncStep")) { syncJournal(); return; }
		if (huntStarted) { hunt(); return; }
		if (quantity(InventoryID.INVENTORY,ItemID.VIKING_FIRECRACKER_LIT)>0)
		{
			useObject(ItemID.VIKING_FIRECRACKER_LIT,ObjectID.VIKING_PIPE_END_LONGHALL,new WorldPoint(2663,3674,0));return;
		}
		if (stepName.equals("goDownLadderToKoschei"))
		{
			if (!prepareCombat()) { return; }
		}
		else if (stepName.equals("talkToPeer") || stepName.equals("enterPeerHouse"))
		{
			if (!prepareEmpty()) { return; }
		}
		else if (!inPeerHouse() && !emptyPrepared && !prepareSupplies()) { return; }
		if (stepName.equals("huntDraugen")) { hunt(); return; }
		if (step.getClass().getSimpleName().equals("CombinationPuzzle") && visible(client.getWidget(298, 43))) { combination(); return; }
		if (specialStep()) { return; }
		delegate();
	}

	private void delegate()
	{
		ensureFreeWalker();
		require(Boolean.parseBoolean(configManager.getConfiguration("efficientwalker", "handleInteractions")), "Enable Walker's Automate quest steps");
		require(Boolean.TRUE.equals(call(walkerPlugin, "walkToQuestStepOnce")), "Walker rejected the quest step");
		routeToken = call(walker, "getControlToken");
		routePurpose = "Quest: " + instruction(); delegatedStep = step; status = routePurpose;
	}

	private boolean prepareSupplies()
	{
		Map<Integer, Integer> needed = new LinkedHashMap<>();
		if (!finished("fremmytrialsfinishedmanni") && quantity(InventoryID.INVENTORY,ItemID.VIKING_LOW_ALCAHOL_BEERKEG)==0 && !finished("fremmytrialsplacedstrangeobject")) { needed.put(ItemID.COINS,250); }
		else if (finished("fremmytrialsfinishedsigli") && !finished("fremmytrialssigmundfinished") && !merchantNoteOwned()) { needed.put(ItemID.COINS,5000); }
		if (!finished("fremmytrialscompletedolaf"))
		{
			require(client.getRealSkillLevel(Skill.FLETCHING) >= 25 && client.getRealSkillLevel(Skill.CRAFTING) >= 40
				&& client.getRealSkillLevel(Skill.WOODCUTTING) >= 40, "Bard's trial needs 25 Fletching, 40 Crafting and 40 Woodcutting");
			needed.put(ItemID.KNIFE, 1);
		}
		if (!finished("fremmytrialsfinishedmanni")) { needed.put(ItemID.TINDERBOX, 1); }
		if (step instanceof DetailedQuestStep)
		{
			for (Requirement requirement : ((DetailedQuestStep) step).getRequirements())
			{
				if (!(requirement instanceof ItemRequirement)) { continue; }
				ItemRequirement item = (ItemRequirement) requirement;
				if (!item.isActualItem() || item.getQuantity() < 1 || item.check(client)) { continue; }
				List<Integer> ids = item.getAllIds();
				// Quest-generated items are obtained by their preceding steps, never bought or invented.
				if (ids.isEmpty()) { continue; }
				if (ids.stream().allMatch(id -> id < 0 || questItem(id)))
				{
					ids.stream().filter(id -> id>=0 && quantity(InventoryID.BANK,id)>=item.getQuantity()).findFirst()
						.ifPresent(id -> needed.put(id,item.getQuantity()));
					continue;
				}
				int id = ids.stream().filter(i -> quantity(InventoryID.BANK, i) >= item.getQuantity()).findFirst().orElse(ids.get(0));
				if (id >= 0) { needed.put(id, item.getQuantity()); }
			}
		}
		if (stepName.equals("huntDraugen"))
		{
			if (client.getRealSkillLevel(Skill.MAGIC) >= 41 && quantity(InventoryID.INVENTORY,1381)+quantity(InventoryID.EQUIPMENT,1381)+quantity(InventoryID.BANK,1381)>0)
			{
				needed.put(1381,1); needed.put(560,100);
			}
			else { needed.put(1331,1); }
		}
		boolean missing = needed.entrySet().stream().anyMatch(e -> quantity(InventoryID.INVENTORY, e.getKey()) + quantity(InventoryID.EQUIPMENT, e.getKey()) < e.getValue());
		if (missing || foodCount() < 5 || needed.containsKey(1381) && Arrays.stream(items(InventoryID.EQUIPMENT)).anyMatch(i->i.getId()>=0&&i.getId()!=1381&&i.getId()!=772))
		{
			supplies = new QuestSupplies(this, needed, QuestSupplies.Mode.TRAVEL);
			return false;
		}
		if (client.getBoostedSkillLevel(Skill.PRAYER) < Math.min(35, client.getRealSkillLevel(Skill.PRAYER)))
		{
			startRoute("walkToPrayerAltar", walkerPlugin, "Restoring prayer through Walk to altar"); return false;
		}
		return true;
	}

	private boolean prepareCombat()
	{
		if(!combatPrepared && equipmentEmpty() && inventoryAllowedForKoschei() && foodCount()>=24
			&& quantity(InventoryID.INVENTORY,ItemID.DRAMEN_BRANCH)>0 && quantity(InventoryID.INVENTORY,ItemID.KNIFE)>0
			&& strengthPotion()>=0 && client.getBoostedSkillLevel(Skill.PRAYER)>=43)
		{
			supplied(QuestSupplies.Mode.KOSCHEI);return false;
		}
		if (combatPrepared)
		{
			require(equipmentEmpty() && inventoryAllowedForKoschei(), "Koschei forbids the current gear; bank it before resuming");
			require(foodCount() >= 15 && client.getBoostedSkillLevel(Skill.PRAYER) >= 35, "Restock 15 food and 35 prayer before Koschei");
			require(quantity(InventoryID.INVENTORY,ItemID.DRAMEN_BRANCH)>0 && quantity(InventoryID.INVENTORY,ItemID.KNIFE)>0,
				"Bring a dramen branch and knife to make the weapon inside Koschei's arena");
			if(client.getBoostedSkillLevel(Skill.STRENGTH)<=client.getRealSkillLevel(Skill.STRENGTH))
			{
				int potion=strengthPotion();require(potion>=0,"Bring an owned strength potion before Koschei");itemAction(potion,"Drink");return false;
			}
			return true;
		}
		require(client.getRealSkillLevel(Skill.CRAFTING)>=31,"Making the dramen staff requires 31 Crafting");
		require(client.getRealSkillLevel(Skill.PRAYER) >= 43, "This combat plan needs 43 Prayer for Protect from Melee");
		if (client.getBoostedSkillLevel(Skill.PRAYER) < client.getRealSkillLevel(Skill.PRAYER))
		{
			startRoute("walkToPrayerAltar", walkerPlugin, "Restoring prayer before Koschei"); return false;
		}
		Map<Integer, Integer> desired = new LinkedHashMap<>();
		supplies = new QuestSupplies(this, desired, QuestSupplies.Mode.KOSCHEI);
		return false;
	}

	private boolean prepareEmpty()
	{
		if (emptyPrepared) { require(equipmentEmpty() && inventoryCount() == 0, "Peer's trial requires empty inventory and equipment"); return true; }
		supplies = new QuestSupplies(this, new LinkedHashMap<>(), QuestSupplies.Mode.EMPTY);
		return false;
	}

	void supplied(QuestSupplies.Mode mode)
	{
		if (mode == QuestSupplies.Mode.KOSCHEI) { combatPrepared = true; }
		if (mode == QuestSupplies.Mode.EMPTY) { emptyPrepared = true; }
		if (mode != QuestSupplies.Mode.TRAVEL)
		{
			walk(RELLEKKA, true, "Returning to Rellekka with the trial's allowed inventory");
		}
	}

	private void hunt()
	{
		draugen = unique(DRAUGEN);
		if (!huntStarted)
		{
			if (client.getBoostedSkillLevel(Skill.HITPOINTS) < client.getRealSkillLevel(Skill.HITPOINTS))
			{
				for (Item food : items(InventoryID.INVENTORY)) { if (food(food.getId())) { itemAction(food.getId(),"Eat"); return; } }
				require(false,"Heal before Draugen");
			}
			int weapon = quantity(InventoryID.INVENTORY,1381)+quantity(InventoryID.EQUIPMENT,1381)>0 && client.getRealSkillLevel(Skill.MAGIC)>=41 ? 1381 : 1331;
			if (quantity(InventoryID.EQUIPMENT,weapon)==0) { itemAction(weapon,"Wield"); return; }
			require(Boolean.TRUE.equals(call(draugen, "startHunt")), "Draugen controller rejected the hunt");
			huntStarted = true;
		}
		Map<?, ?> state = (Map<?, ?>) call(draugen, "agentControlStatus");
		if (state.get("error") != null) { fail("Draugen: " + state.get("error")); return; }
		status = "Draugen: " + state.get("status");
	}

	private void koschei()
	{
		combatPrepared = false;
		if(quantity(InventoryID.EQUIPMENT,ItemID.DRAMEN_STAFF)==0)
		{
			if(quantity(InventoryID.INVENTORY,ItemID.DRAMEN_STAFF)>0){itemAction(ItemID.DRAMEN_STAFF,"Wield");return;}
			require(quantity(InventoryID.INVENTORY,ItemID.DRAMEN_BRANCH)>0 && quantity(InventoryID.INVENTORY,ItemID.KNIFE)>0,"Missing dramen staff materials in arena");
			pair(ItemID.KNIFE,ItemID.DRAMEN_BRANCH);return;
		}
		if(client.getBoostedSkillLevel(Skill.STRENGTH)<=client.getRealSkillLevel(Skill.STRENGTH) && strengthPotion()>=0)
		{itemAction(strengthPotion(),"Drink");return;}
		if (quantity(InventoryID.INVENTORY, ItemID.RING_OF_RECOIL) > 0)
		{
			itemAction(ItemID.RING_OF_RECOIL, "Wear"); return;
		}
		NPC target = npc(NpcID.VIKING_ENEMY1, NpcID.VIKING_ENEMY2, NpcID.VIKING_ENEMY3);
		if (target == null) { status = "Waiting for Koschei's next form"; return; }
		require(client.getBoostedSkillLevel(Skill.HITPOINTS) > 0, "Koschei health state unavailable");
		if (!Boolean.TRUE.equals(call(guardian, "prepareKoscheiCombat", new Class<?>[]{NPC.class}, target))) { status = "Waiting for Guardian protection"; return; }
		attack(target, true);
	}

	private void attack(NPC target, boolean approach)
	{
		String identity = target.getIndex() + ":" + target.getId();
		if (!identity.equals(lastCombatIdentity)) { pendingAttack = null; lastCombatIdentity = identity; }
		if (client.getLocalPlayer().getInteracting() == target) { pendingAttack = null; status = "Fighting " + target.getName(); return; }
		if (pendingAttack != null)
		{
			require(client.getTickCount() - attackRequestedAt <= 100,
				"Koschei attack remains unconfirmed; inspect before resuming (no retry sent)");
			status = "Waiting for the dispatched attack to engage"; return;
		}
		if (!Boolean.TRUE.equals(call(walker, "atNpcApproach", new Class<?>[]{NPC.class}, target)))
		{
			if (approach) { targetRoute(target, "Approaching Koschei"); }
			return;
		}
		npcAction(target, "Attack"); pendingAttack = target; attackRequestedAt = client.getTickCount();
	}

	private boolean specialStep()
	{
		switch (stepName)
		{
			case "fletchLyre": pair(ItemID.KNIFE, ItemID.VIKING_MUSICAL_TREE_BRANCH); return true;
			case "makeLyre": pair(ItemID.VIKING_GOLDEN_WOOL, ItemID.VIKING_UNSTRUNG_LYRE); return true;
			case "useGoopOnDisk": pair(ItemID.VIKING_RED_SPLAT, ItemID.VIKING_UNCOLOURED_WOODEN_COIN); return true;
			case "useBucketOnJug1": pair(ItemID.VIKING_BUCKET_5, ItemID.VIKING_JUG_EMPTY); return true;
			case "useBucketOnJug2": pair(ItemID.VIKING_BUCKET_2, ItemID.VIKING_JUG_EMPTY); return true;
			case "useBucketOnJug3": pair(ItemID.VIKING_BUCKET_5, ItemID.VIKING_JUG_2); return true;
			case "useLidOnVase": pair(ItemID.VIKING_VASE_LID, ItemID.VIKING_AIRTIGHT_VASE_WATER); return true;
			case "useAlcoholFreeOnKeg": pair(ItemID.VIKING_LOW_ALCAHOL_BEERKEG, ItemID.VIKING_BEERKEG); return true;
			case "getStrangeObject": useNpc(firstOwned(ItemID.BEER,ItemID.VIKING_TANKARD_FULL),NpcID.VT_COUNCIL_WORKMEN,new WorldPoint(2654,3593,0)); return true;
			case "useStrangeObject": pair(ItemID.TINDERBOX, ItemID.VIKING_FIRECRACKER); return true;
			case "useStrangeObjectOnPipe": useObject(ItemID.VIKING_FIRECRACKER_LIT, ObjectID.VIKING_PIPE_END_LONGHALL, new WorldPoint(2663,3674,0)); return true;
			case "useOnion": useObject(ItemID.ONION, ObjectID.VIKING_TROLL_CAULDRON, new WorldPoint(2773,3624,0)); return true;
			case "useCabbage": useObject(ItemID.CABBAGE, ObjectID.VIKING_TROLL_CAULDRON, new WorldPoint(2773,3624,0)); return true;
			case "usePotato": useObject(ItemID.POTATO, ObjectID.VIKING_TROLL_CAULDRON, new WorldPoint(2773,3624,0)); return true;
			case "useRock": useObject(ItemID.VT_USELESS_ROCK, ObjectID.VIKING_TROLL_CAULDRON, new WorldPoint(2773,3624,0)); return true;
			case "enchantLyre": useObject(firstOwned(ItemID.RAW_SHARK, ItemID.RAW_MANTARAY, ItemID.RAW_SEATURTLE), ObjectID.VIKING_LAKE_SHRINE_ALTAR, new WorldPoint(2626,3598,0)); return true;
			case "cookHerring": useObject(ItemID.VIKING_RED_HERRING, ObjectID.VIKING_SEER_RANGE, new WorldPoint(2629,3664,2)); return true;
			case "useDiskAnyOnMural": useObject(ItemID.VIKING_RED_WOODEN_COIN_OLD, ObjectID.VIKING_SEERS_MURAL, new WorldPoint(2634,3663,0)); return true;
			case "useDiskOldOnMural": useObject(ItemID.VIKING_RED_WOODEN_COIN_OLD, ObjectID.VIKING_SEERS_MURAL, new WorldPoint(2634,3663,0)); return true;
			case "useDiskNewOnMural": useObject(ItemID.VIKING_RED_WOODEN_COIN, ObjectID.VIKING_SEERS_MURAL, new WorldPoint(2634,3663,0)); return true;
			case "useBucketOnTap1": useObject(ItemID.VIKING_BUCKET_EMPTY, ObjectID.VIKING_SEERS_TAP, new WorldPoint(2629,3661,2)); return true;
			case "useBucketOnTap2": useObject(ItemID.VIKING_BUCKET_EMPTY, ObjectID.VIKING_SEERS_TAP, new WorldPoint(2629,3661,2)); return true;
			case "useJugOnDrain1": useObject(ItemID.VIKING_JUG_3, ObjectID.VIKING_SEERS_DRAIN, new WorldPoint(2629,3662,2)); return true;
			case "useBucketOnScale": useObject(ItemID.VIKING_BUCKET_4, ObjectID.VIKING_SEER_CHEST_CLOSED_SCALES, new WorldPoint(2632,3665,2)); return true;
			case "fillVase": useObject(ItemID.VIKING_AIRTIGHT_VASE, ObjectID.VIKING_SEERS_TAP, new WorldPoint(2629,3661,2)); return true;
			case "useVaseOnTable": useObject(ItemID.VIKING_AIRTIGHT_VASE_WITH_LID_WATER, ObjectID.VIKING_SMALL_TABLE_FROZEN, new WorldPoint(2638,3665,2)); return true;
			case "useFrozenKeyOnRange": useObject(ItemID.VIKING_KEY_IN_ICE, ObjectID.VIKING_SEER_RANGE, new WorldPoint(2629,3664,2)); return true;
			case "warmFrozenVase": useObject(ItemID.VIKING_AIRTIGHT_VASE_FROZEN, ObjectID.VIKING_SEER_RANGE, new WorldPoint(2629,3664,2)); return true;
			case "spinWool": spin(); return true;
			case "performMusic": perform(); return true;
			case "pickUpBeer": takeGround(ItemID.VIKING_TANKARD_FULL, new WorldPoint(2658,3676,0)); return true;
			case "getKegOfBeer": takeGround(ItemID.VIKING_BEERKEG, new WorldPoint(2660,3676,0)); return true;
			case "prepareToUseStrangeObject": walk(new WorldPoint(2664,3674,0), false, "Approaching the longhall pipe"); return true;
			case "emptyJugAndBucket": emptyPuzzleVessels(); return true;
			case "takeLidOff": itemAction(ItemID.VIKING_AIRTIGHT_VASE_WITH_LID, "Remove-lid"); return true;
			default: return false;
		}
	}

	private void combination()
	{
		String text = instruction();
		java.util.regex.Matcher match = java.util.regex.Pattern.compile("solution ([A-Z]{4})").matcher(text);
		require(match.find(), "Cannot read Peer's verified riddle solution");
		String solution = match.group(1);
		for (int i = 0; i < 4; i++)
		{
			Widget slot = client.getWidget(298, 43 + i);
			require(visible(slot) && strip(slot.getText()).length() == 1, "Peer's combination slot is unavailable");
			char current = strip(slot.getText()).charAt(0), wanted = solution.charAt(i);
			if (current == wanted) { continue; }
			int button = combinationButton(i, current, wanted);
			Widget arrow = client.getWidget(298, button);
			if (!legacyReady(arrow)) { return; }
			String before = slot.getText();
			send("Peer combination letter " + (i + 1), () -> !Objects.equals(before, slot.getText()), () -> legacyClick(arrow));
			return;
		}
		Widget complete = client.getWidget(298, 56);
		if (!legacyReady(complete)) { return; }
		send("Confirm Peer's combination", () -> !visible(client.getWidget(298,43)), () -> legacyClick(complete));
	}

	private boolean legacyReady(Widget widget)
	{
		require(visible(widget), "Peer's combination control is unavailable");
		for (MenuEntry entry : client.getMenuEntries())
		{
			if (entry.getParam1() == widget.getId() && entry.getType() == MenuAction.WIDGET_TYPE_1) { hoveredLegacy=null; return true; }
		}
		if (hoveredLegacy == widget)
		{
			require(client.getTickCount()-hoverTick <= 3, "No permitted menu action on Peer's combination control");
			return false;
		}
		require(widget.getBounds()!=null && widget.getBounds().width>0 && widget.getBounds().height>0,"Combination control has no visible bounds");
		hoveredLegacy=widget; hoverTick=client.getTickCount();
		java.awt.Rectangle bounds=widget.getBounds();
		client.getCanvas().dispatchEvent(new java.awt.event.MouseEvent(client.getCanvas(),java.awt.event.MouseEvent.MOUSE_MOVED,
			System.currentTimeMillis(),0,(int)bounds.getCenterX(),(int)bounds.getCenterY(),0,false));
		return false;
	}

	private void legacyClick(Widget widget)
	{
		for (MenuEntry entry : client.getMenuEntries())
		{
			if (entry.getParam1()==widget.getId() && entry.getType()==MenuAction.WIDGET_TYPE_1)
			{
				client.menuAction(entry.getParam0(),entry.getParam1(),entry.getType(),entry.getIdentifier(),entry.getItemId(),entry.getOption(),entry.getTarget());
				return;
			}
		}
		throw new IllegalStateException("Combination control menu changed before dispatch");
	}

	static int combinationButton(int slot, char current, char wanted)
	{
		require(slot >= 0 && slot < 4 && current >= 'A' && current <= 'Z' && wanted >= 'A' && wanted <= 'Z', "Invalid combination state");
		// Re-read the server letter after every press; either alphabet direction converges within 25 changes.
		return 48 + slot * 2;
	}

	private void spin()
	{
		Widget make = findWidget(null, "Golden wool", 270);
		if (make != null) { int before = quantity(InventoryID.INVENTORY, ItemID.VIKING_GOLDEN_FLEECE); send("Spin golden wool", () -> quantity(InventoryID.INVENTORY, ItemID.VIKING_GOLDEN_FLEECE) < before, () -> onlyWidgetAction(make)); return; }
		// Seers' wheel is a normal Walker destination/transport, not a separate movement helper.
		useObject(ItemID.VIKING_GOLDEN_FLEECE, ObjectID.SPINNINGWHEEL, new WorldPoint(2710,3471,1));
	}

	private void perform()
	{
		WorldPoint p = client.getLocalPlayer().getWorldLocation();
		if (p.getPlane() == 0 && p.getX() >= 2657 && p.getX() <= 2662 && p.getY() >= 3681 && p.getY() <= 3685)
		{
			itemAction(ItemID.VIKING_ENCHANTED_STRUNG_LYRE, "Play"); return;
		}
		objectAction(ObjectID.VIKING_BARD_BACKSTAGE_DOOR, new WorldPoint(2662,3681,0), "Open");
	}

	private void emptyPuzzleVessels()
	{
		int id = firstOwnedOrNone(ItemID.VIKING_JUG_3, ItemID.VIKING_JUG_2, ItemID.VIKING_JUG_1,
			ItemID.VIKING_BUCKET_5, ItemID.VIKING_BUCKET_4, ItemID.VIKING_BUCKET_3, ItemID.VIKING_BUCKET_2, ItemID.VIKING_BUCKET_1);
		require(id >= 0, "Puzzle reset has no filled vessel; inspect Quest Helper state");
		useObject(id, ObjectID.VIKING_SEERS_DRAIN, new WorldPoint(2629,3662,2));
	}

	private void syncJournal()
	{
		if(visible(client.getWidget(12,0)))
		{
			Widget close=findWidget("Close",null,12);require(close!=null,"Close the bank before refreshing the quest journal");
			send("Close bank for quest journal",()->!visible(client.getWidget(12,0)),()->widgetAction(close,"Close"));return;
		}
		Widget ringClose=client.getWidget(398,27);
		if(visible(ringClose))
		{
			send("Close interrupted fairy-ring interface",()->!visible(client.getWidget(398,27)),()->widgetAction(ringClose,"Close"));return;
		}
		Widget journal = client.getWidget(InterfaceID.Questjournal.TEXTLAYER);
		if (visible(journal))
		{
			Widget title=client.getWidget(InterfaceID.Questjournal.TITLE);
			require(title!=null && strip(title.getText()).equalsIgnoreCase("The Fremennik Trials"), "A different quest journal is open; close it before resuming");
			Widget close=client.getWidget(InterfaceID.Questjournal.CLOSE);
			send("Close refreshed quest journal", () -> !visible(client.getWidget(InterfaceID.Questjournal.TEXTLAYER)), () -> widgetAction(close,"Close"));
			journalSynced=true;
			return;
		}
		Widget list = client.getWidget(399, 7);
		if (!visible(list)) { send("Open quest list", () -> visible(client.getWidget(399,7)), () -> client.runScript(915, 2)); return; }
		Widget entry = findWidget(null, "The Fremennik Trials", 399);
		require(entry != null, "The Fremennik Trials journal entry is unavailable");
		Widget viewport=client.getWidget(399,6);
		if (viewport!=null && !viewport.getBounds().contains(entry.getBounds()))
		{
			int scroll=Math.max(0,Math.min(viewport.getScrollHeight()-viewport.getHeight(),entry.getRelativeY()-viewport.getHeight()/2));
			send("Reveal quest journal entry", () -> viewport.getBounds().contains(entry.getBounds()),
				() -> client.runScript(ScriptID.UPDATE_SCROLLBAR, (399<<16)|5, (399<<16)|6, scroll));
			return;
		}
		if(!entry.isIf3() && !legacyReady(entry)){return;}
		send("Read quest journal", () -> visible(client.getWidget(InterfaceID.Questjournal.TEXTLAYER)), () -> { if(entry.isIf3()){widgetAction(entry,"Read journal:");}else{legacyClick(entry);} });
	}

	private void pair(int source, int target)
	{
		if (!selectItem(source)) { return; }
		Widget item = inventoryWidget(target);
		int beforeSource = quantity(InventoryID.INVENTORY, source), beforeTarget = quantity(InventoryID.INVENTORY, target);
		send("Use " + itemName(source) + " on " + itemName(target),
			() -> quantity(InventoryID.INVENTORY, source) != beforeSource || quantity(InventoryID.INVENTORY, target) != beforeTarget,
			() -> client.menuAction(item.getIndex(), item.getId(), MenuAction.WIDGET_TARGET_ON_WIDGET, 0, target, "Use", item.getName()));
	}

	private boolean selectItem(int id)
	{
		Widget inventory = client.getWidget(149,0);
		if (!visible(inventory)) { send("Open inventory", () -> visible(client.getWidget(149,0)), () -> client.runScript(915, 3)); return false; }
		Widget item = inventoryWidget(id);
		if (client.isWidgetSelected())
		{
			require(client.getSelectedWidget() != null && client.getSelectedWidget().getItemId() == id, "Another item or spell is selected; clear it before resuming");
			return true;
		}
		send("Select " + itemName(id), () -> client.isWidgetSelected() && client.getSelectedWidget() != null && client.getSelectedWidget().getItemId() == id,
			() -> client.menuAction(item.getIndex(), item.getId(), MenuAction.WIDGET_TARGET, 0, id, "Use", item.getName()));
		return false;
	}

	private void useNpc(int item, int npcId, WorldPoint point)
	{
		NPC target=npc(npcId);
		if(target==null){walkNear(point);return;}
		if(!Boolean.TRUE.equals(call(walker,"atNpcApproach",new Class<?>[]{NPC.class},target))){targetRoute(target,"Approaching quest recipient");return;}
		if(!selectItem(item)){return;}
		int before=quantity(InventoryID.INVENTORY,item);QuestStep previous=step;
		send("Give "+itemName(item)+" to "+target.getName(),()->quantity(InventoryID.INVENTORY,item)!=before||active(quest.getCurrentStep())!=previous||dialogueVisible(),
			()->client.menuAction(0,0,MenuAction.WIDGET_TARGET_ON_NPC,target.getIndex(),-1,"Use",target.getName()));
	}

	private void useObject(int item, int objectId, WorldPoint point)
	{
		TileObject object = object(objectId, point);
		if (object == null) { walkNear(point); return; }
		if (!atObject(object)) { targetRoute(object, "Approaching " + itemName(item) + " target"); return; }
		if (!selectItem(item)) { return; }
		int before = quantity(InventoryID.INVENTORY, item); QuestStep previous = step;
		send("Use " + itemName(item) + " on object " + objectId,
			() -> quantity(InventoryID.INVENTORY, item) != before || active(quest.getCurrentStep()) != previous || visible(client.getWidget(270,0)) || dialogueVisible(),
			() -> client.menuAction(object.getWorldLocation().getX() - client.getTopLevelWorldView().getBaseX(),
				object.getWorldLocation().getY() - client.getTopLevelWorldView().getBaseY(), MenuAction.WIDGET_TARGET_ON_GAME_OBJECT,
				object.getId(), -1, "Use", client.getObjectDefinition(object.getId()).getName()));
	}

	private void objectAction(int id, WorldPoint point, String action)
	{
		TileObject target = object(id, point);
		if (target == null) { walkNear(point); return; }
		if (!atObject(target)) { targetRoute(target, "Approaching " + action); return; }
		ObjectComposition def = client.getObjectDefinition(target.getId());
		if (def.getImpostorIds() != null) { def = def.getImpostor(); }
		int index = actionIndex(def == null ? null : def.getActions(), action);
		require(index >= 0 && index < 5, action + " unavailable on object " + id);
		MenuAction[] actions = {MenuAction.GAME_OBJECT_FIRST_OPTION,MenuAction.GAME_OBJECT_SECOND_OPTION,MenuAction.GAME_OBJECT_THIRD_OPTION,MenuAction.GAME_OBJECT_FOURTH_OPTION,MenuAction.GAME_OBJECT_FIFTH_OPTION};
		WorldPoint before = client.getLocalPlayer().getWorldLocation(); QuestStep previous = step;
		final String name = def.getName();
		send(action + " " + name, () -> !before.equals(client.getLocalPlayer().getWorldLocation()) || active(quest.getCurrentStep()) != previous || dialogueVisible(),
			() -> client.menuAction(target.getWorldLocation().getX()-client.getTopLevelWorldView().getBaseX(),target.getWorldLocation().getY()-client.getTopLevelWorldView().getBaseY(),actions[index],target.getId(),-1,action,name));
	}

	private void takeGround(int id, WorldPoint point)
	{
		if (client.getLocalPlayer().getWorldLocation().distanceTo(point) > 1) { walkNear(point); return; }
		WorldView view = client.getTopLevelWorldView(); int x = point.getX()-view.getBaseX(), y = point.getY()-view.getBaseY();
		Tile tile = view.getScene().getTiles()[point.getPlane()][x][y];
		boolean found = false;
		if (tile != null && tile.getGroundItems() != null) { for (TileItem item : tile.getGroundItems()) { if (item.getId() == id) { found = true; } } }
		require(found, "Required ground item is not present: " + itemName(id));
		int index = 2; // Default Take slot for the quest's beer and keg ground spawns.
		require(index >= 0 && index < 5, "Take unavailable for " + itemName(id));
		MenuAction[] options = {MenuAction.GROUND_ITEM_FIRST_OPTION,MenuAction.GROUND_ITEM_SECOND_OPTION,MenuAction.GROUND_ITEM_THIRD_OPTION,MenuAction.GROUND_ITEM_FOURTH_OPTION,MenuAction.GROUND_ITEM_FIFTH_OPTION};
		int before = quantity(InventoryID.INVENTORY,id);
		send("Take " + itemName(id), () -> quantity(InventoryID.INVENTORY,id) > before, () -> client.menuAction(x,y,options[index],id,-1,"Take",itemName(id)));
	}

	void itemAction(int id, String action)
	{
		Widget inventory = client.getWidget(149,0);
		if (!visible(inventory)) { send("Open inventory", () -> visible(client.getWidget(149,0)), () -> client.runScript(915,3)); return; }
		Widget item = inventoryWidget(id); int before = quantity(InventoryID.INVENTORY,id); QuestStep old = step;
		send(action + " " + itemName(id), () -> quantity(InventoryID.INVENTORY,id) != before || active(quest.getCurrentStep()) != old || dialogueVisible(), () -> widgetAction(item,action));
	}

	private void npcAction(NPC target, String action)
	{
		NPCComposition def = target.getTransformedComposition(); int index = actionIndex(def == null ? null : def.getActions(), action);
		require(index >= 0 && index < 5, action + " unavailable on " + target.getName());
		MenuAction[] options = {MenuAction.NPC_FIRST_OPTION,MenuAction.NPC_SECOND_OPTION,MenuAction.NPC_THIRD_OPTION,MenuAction.NPC_FOURTH_OPTION,MenuAction.NPC_FIFTH_OPTION};
		client.menuAction(0,0,options[index],target.getIndex(),-1,action,target.getName());
	}

	void send(String description, BooleanSupplier effect, Runnable input)
	{
		require(pending == null, "Another input is pending");
		pending = effect; pendingAt = client.getTickCount(); pendingDescription = description;
		input.run(); status = description;
	}

	void walk(WorldPoint point, boolean inventoryOnly, String purpose)
	{
		require(routeToken == null, "Another route is pending");
		ensureFreeWalker();
		require(Boolean.TRUE.equals(call(walker, inventoryOnly ? "walkToWithInventory" : "walkTo", new Class<?>[]{WorldPoint.class},point)), "Walker cannot provide the requested route");
		routeToken = call(walker,"getControlToken"); routePurpose = purpose; status = purpose;
	}
	private void walkNear(WorldPoint marker)
	{
		ensureFreeWalker();
		require(Boolean.TRUE.equals(call(walker,"walkToQuestArea",new Class<?>[]{WorldPoint.class},marker)),
			"Walker cannot provide a verified staging or approach tile for " + marker);
		routeToken=call(walker,"getControlToken");routePurpose="Approaching the quest area";
	}

	void startRoute(String method, Object owner, String purpose)
	{
		ensureFreeWalker(); require(Boolean.TRUE.equals(call(owner,method)), "Walker rejected " + purpose);
		routeToken = call(walker,"getControlToken"); routePurpose = purpose; status = purpose;
	}
	private void targetRoute(Object target, String purpose)
	{
		ensureFreeWalker(); boolean npc = target instanceof NPC;
		require(Boolean.TRUE.equals(call(walker,npc?"walkToNpc":"walkToObject",new Class<?>[]{npc?NPC.class:TileObject.class},target)), "Walker cannot provide a safe live-target approach");
		routeToken=call(walker,"getControlToken"); routePurpose=purpose;
	}
	Map<?, ?> control() { return (Map<?, ?>) call(walkerPlugin,"getControlStatus",new Class<?>[]{Object.class},routeToken); }
	boolean hasRoute() { return routeToken != null; }
	void cancelRoute()
	{
		if (routeToken != null && walkerPlugin != null) { call(walkerPlugin,"cancelWalk",new Class<?>[]{Object.class},routeToken); }
		routeToken=null; routePurpose=null;
	}
	private void ensureFreeWalker()
	{
		Map<?, ?> current=(Map<?, ?>)call(walkerPlugin,"getControlStatus",new Class<?>[]{Object.class},call(walker,"getControlToken"));
		require(!Boolean.TRUE.equals(current.get("active")), "Another Walker intent owns movement; stop it before starting this quest");
	}
	boolean atObject(TileObject object) { return Boolean.TRUE.equals(call(walker,"atObjectApproach",new Class<?>[]{TileObject.class},object)); }
	Object walker() { return walker; }

	private void bind()
	{
		Plugin candidate=unique(WALKER);
		Object current=call(candidate,"getWalker");
		if (walker != null && (candidate != walkerPlugin || current != walker)) { throw new IllegalStateException("Walker was replaced; resume after rebinding"); }
		walkerPlugin=candidate; walker=current; guardian=unique(GUARDIAN);
		for (String method : new String[]{"walkToQuestStepOnce","walkToPrayerAltar","continueQuestDialogue"}) { requireMethod(candidate,method); }
		requireMethod(walker,"walkToWithInventory",WorldPoint.class); requireMethod(walker,"walkToNearestBank");
		requireMethod(guardian,"prepareKoscheiSafeDefeat",NPC.class);
		requireMethod(guardian,"prepareKoscheiCombat",NPC.class);
	}
	private Plugin unique(String className)
	{
		List<Plugin> found=new ArrayList<>();
		for (Plugin plugin : pluginManager.getPlugins()) { if (plugin.getClass().getName().equals(className)) { found.add(plugin); } }
		require(found.size()==1 && pluginManager.isActive(found.get(0)), "Exactly one active " + className.substring(className.lastIndexOf('.')+1) + " is required");
		return found.get(0);
	}
	private boolean stable()
	{
		WorldView view=client.getTopLevelWorldView(); if(view==null){return false;}
		int plane=client.getLocalPlayer().getWorldLocation().getPlane();
		if(sceneBaseX!=view.getBaseX()||sceneBaseY!=view.getBaseY()||scenePlane!=plane){stableTicks=0;sceneBaseX=view.getBaseX();sceneBaseY=view.getBaseY();scenePlane=plane;}
		return ++stableTicks>=2;
	}
	private boolean cameraReady()
	{
		if(client.getCameraPitchTarget()!=3064){client.setCameraPitchTarget(3064);status="Setting overhead camera";return false;}
		if(client.getCameraYawTarget()!=0){client.setCameraYawTarget(0);return false;}
		int small=client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL_MIN),big=client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG_MIN);
		if(client.getVarcIntValue(VarClientID.CAMERA_ZOOM_SMALL)!=small||client.getVarcIntValue(VarClientID.CAMERA_ZOOM_BIG)!=big){client.runScript(ScriptID.CAMERA_DO_ZOOM,small,big);return false;}
		return client.getCameraPitch()==3064 && client.getCameraYaw()==0;
	}

	private void indexSteps()
	{
		namedSteps.clear();
		for(Field field:quest.getClass().getDeclaredFields())
		{
			if(!QuestStep.class.isAssignableFrom(field.getType())){continue;}
			try{field.setAccessible(true);QuestStep value=(QuestStep)field.get(quest);if(value!=null){namedSteps.put(field.getName(),value);}}
			catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot index quest steps",e);}
		}
	}
	private String nameOf(QuestStep current)
	{
		for(Map.Entry<String,QuestStep> entry:namedSteps.entrySet())
		{
			if(entry.getValue()==current||entry.getValue() instanceof PuzzleWrapperStep && ((PuzzleWrapperStep)entry.getValue()).getSolvingStep()==current){return entry.getKey();}
		}
		return current.getClass().getSimpleName();
	}
	static QuestStep active(QuestStep step){return step==null?null:step.getActiveStep();}
	private String instruction(){return step.getText()==null?stepName:String.join(" ",step.getText());}
	boolean finished(String key){return "true".equals(configManager.getRSProfileConfiguration("questhelpervars",key));}
	private boolean merchantNoteOwned(){for(int id=ItemID.VIKING_RARE_FLOWER;id<=ItemID.VIKING_PROMISSARY_NOTE3;id++){if(quantity(InventoryID.INVENTORY,id)>0){return true;}}return false;}
	static boolean questItem(int id){return id>=3688&&id<=3748;}
	// A transformed, walking form is alive even when the previous form's client death flag remains set.
	static boolean revivedKoschei(NPC n){return n!=null && n.getId()>=3898 && n.getId()<=3900 && n.isDead()
		&& n.getHealthRatio()!=0 && n.getAnimation()==-1 && n.getPoseAnimation()!=n.getIdlePoseAnimation();}
	static boolean inKoschei(WorldPoint p){return p!=null&&p.getPlane()==2&&p.getX()>=2641&&p.getX()<=2672&&p.getY()>=10064&&p.getY()<=10099;}
	private boolean inPeerHouse(){WorldPoint p=client.getLocalPlayer().getWorldLocation();return p.getX()>=2628&&p.getX()<=2639&&p.getY()>=3660&&p.getY()<=3667&&(p.getPlane()==2||p.getY()<3667);}
	static boolean strengthPotion(int id){return id==113||id==115||id==117||id==119;}
	private int strengthPotion(){for(Item item:items(InventoryID.INVENTORY)){if(strengthPotion(item.getId())){return item.getId();}}return -1;}

	boolean inventoryAllowedForKoschei(){for(Item item:items(InventoryID.INVENTORY)){if(item.getId()>=0&&!food(item.getId())&&item.getId()!=ItemID.RING_OF_RECOIL&&item.getId()!=ItemID.DRAMEN_BRANCH&&item.getId()!=ItemID.KNIFE&&item.getId()!=ItemID.VIAL_EMPTY&&!strengthPotion(item.getId())){return false;}}return true;}
	boolean equipmentEmpty(){return Arrays.stream(items(InventoryID.EQUIPMENT)).noneMatch(i->i.getId()>=0);}
	private String inventorySignature()
	{
		StringBuilder value=new StringBuilder();
		for(Item item:items(InventoryID.INVENTORY)){value.append(item.getId()).append(':').append(item.getQuantity()).append(';');}
		return value.toString();
	}

	int inventoryCount(){return (int)Arrays.stream(items(InventoryID.INVENTORY)).filter(i->i.getId()>=0).count();}
	int foodCount(){return (int)Arrays.stream(items(InventoryID.INVENTORY)).filter(i->i.getId()>=0&&food(i.getId())).count();}
	boolean food(int id){return id>=0&&actionIndex(client.getItemDefinition(id).getInventoryActions(),"Eat")>=0;}
	Item[] items(InventoryID container){ItemContainer c=client.getItemContainer(container);return c==null?new Item[0]:c.getItems();}
	int quantity(InventoryID container,int id){int total=0;for(Item item:items(container)){if(item.getId()==id){total+=item.getQuantity();}}return total;}
	String itemName(int id){ItemComposition def=client.getItemDefinition(id);return def==null?"item "+id:def.getName();}
	private int firstOwned(int... ids){int id=firstOwnedOrNone(ids);require(id>=0,"Required quest item is missing");return id;}
	private int firstOwnedOrNone(int... ids){for(int id:ids){if(quantity(InventoryID.INVENTORY,id)>0){return id;}}return -1;}
	private NPC npc(int... ids){WorldView view=client.getTopLevelWorldView();if(view==null||view.npcs()==null){return null;}for(NPC n:view.npcs()){if(n!=null&&!n.isDead()&&Arrays.stream(ids).anyMatch(id->id==n.getId())){return n;}}return null;}
	private TileObject object(int id,WorldPoint point)
	{
		WorldView v=client.getTopLevelWorldView(); TileObject best=null;
		for(Tile[] row:v.getScene().getTiles()[client.getPlane()]){for(Tile tile:row){if(tile==null){continue;}List<TileObject> all=new ArrayList<>();all.add(tile.getWallObject());all.add(tile.getDecorativeObject());all.add(tile.getGroundObject());if(tile.getGameObjects()!=null){all.addAll(Arrays.asList(tile.getGameObjects()));}for(TileObject obj:all){if(obj!=null&&obj.getId()==id&&(best==null||obj.getWorldLocation().distanceTo(point)<best.getWorldLocation().distanceTo(point))){best=obj;}}}}
		return best;
	}
	Widget inventoryWidget(int id){Widget root=client.getWidget(149,0);if(root!=null&&root.getChildren()!=null){for(Widget w:root.getChildren()){if(w!=null&&w.getItemId()==id){return w;}}}throw new IllegalStateException("Inventory action unavailable for "+itemName(id));}
	boolean dialogueVisible(){return visible(client.getWidget(231,0))||visible(client.getWidget(217,0))||visible(client.getWidget(219,0));}
	static boolean visible(Widget widget){return widget!=null&&!widget.isHidden();}
	static String strip(String text){return text==null?"":Text.removeTags(text);}
	static int actionIndex(String[] actions,String action){if(actions!=null){for(int i=0;i<actions.length;i++){if(action.equalsIgnoreCase(actions[i])){return i;}}}return -1;}
	void widgetAction(Widget widget,String action)
	{
		require(visible(widget),"Widget is unavailable for "+action);
		String[] actions=widget.getActions();
		if(actions==null && widget.getId()>>>16==149 && widget.getItemId()>=0)
		{
			actions=client.getItemDefinition(widget.getItemId()).getInventoryActions();
		}
		int index=actionIndex(actions,action);WidgetConfigNode permissions=client.getWidgetConfig(widget);int mask=permissions==null?widget.getClickMask()>>>1:permissions.getOpMask();
		require(index>=0&&index<10&&((mask&(1<<index))!=0||widget.getOnOpListener()!=null),"Widget does not permit "+action);
		client.menuAction(widget.getIndex(),widget.getId(),index<5?MenuAction.CC_OP:MenuAction.CC_OP_LOW_PRIORITY,index+1,widget.getItemId(),action,widget.getName());
	}
	private void onlyWidgetAction(Widget widget){require(visible(widget)&&widget.getActions()!=null,"No actionable widget");String action=null;for(String candidate:widget.getActions()){if(candidate!=null&&!candidate.isEmpty()){require(action==null,"Ambiguous widget actions");action=candidate;}}require(action!=null,"Widget has no action");widgetAction(widget,action);}
	Widget findWidget(String action,String text,int group)
	{
		for(int child=0;child<128;child++){Widget root=client.getWidget(group,child);Widget match=findWidget(root,action,text);if(match!=null){return match;}}return null;
	}
	private Widget findWidget(Widget root,String action,String text){if(!visible(root)){return null;}if((action==null||actionIndex(root.getActions(),action)>=0)&&(text==null||strip(root.getText()).equalsIgnoreCase(text)||strip(root.getName()).equalsIgnoreCase(text))&&(root.getActions()!=null||text!=null)){return root;}Widget[][] groups={root.getChildren(),root.getStaticChildren(),root.getNestedChildren()};for(Widget[] children:groups){if(children!=null){for(Widget child:children){Widget match=findWidget(child,action,text);if(match!=null){return match;}}}}return null;}
	static void require(boolean condition,String message){if(!condition){throw new IllegalStateException(message);}}
	static Object call(Object target,String name,Class<?>[] types,Object... args){try{return target.getClass().getMethod(name,types).invoke(target,args);}catch(ReflectiveOperationException e){throw new IllegalStateException("Required API failed: "+name,e);}}
	static Object call(Object target,String name){return call(target,name,new Class<?>[0]);}
	static void requireMethod(Object target,String name,Class<?>... types){try{target.getClass().getMethod(name,types);}catch(ReflectiveOperationException e){throw new IllegalStateException("Update required dependency: "+name,e);}}
	void setStatus(String message){status=message;}
	private void stop(String reason)
	{
		startRequested=false;running=false;
		try{cancelRoute();}catch(RuntimeException ignored){routeToken=null;}
		try{if(huntStarted&&draugen!=null){call(draugen,"stopEncounter");}}catch(RuntimeException ignored){ }
		try{if(guardian!=null){call(guardian,"releasePreparedCombat");}}catch(RuntimeException ignored){ }
		pending=null;pendingDescription=null;supplies=null;huntStarted=false;status=reason;
	}
	private void fail(String reason){stop("Error: "+reason);error=reason;client.addChatMessage(ChatMessageType.GAMEMESSAGE,"","<col=ff0000>Error: "+reason+"</col>","");}
	public Map<String,Object> agentControlCommands(){return Map.of("start",Map.of(),"stop",Map.of());}
	public Map<String,Object> agentControlStatus(){Map<String,Object> result=new LinkedHashMap<>();result.put("running",running);result.put("status",status);result.put("error",error);result.put("step",stepName);result.put("stepsCompleted",stepsCompleted);result.put("pending",pendingDescription);result.put("complete",status.startsWith("Complete:"));return result;}
	public Map<String,Object> agentControl(String command,Map<String,Object> args)
	{
		if(args==null||!args.isEmpty()){return Map.of("accepted",false,"error","No arguments are supported");}
		if("stop".equals(command)){stop("Stopped by request");return Map.of("accepted",true);}
		if(!"start".equals(command)||running){return Map.of("accepted",false,"error","Unknown command or already running");}
		try
		{
			walker=null;walkerPlugin=null;routeToken=null;bind();ensureFreeWalker();
			require(client.getGameState()==GameState.LOGGED_IN,"Log in before starting");
			require(client.getWorldType().contains(WorldType.MEMBERS),"The quest requires a members world");
			startRequested=false;running=true;error=null;pending=null;pendingDescription=null;stableTicks=0;
			quest=null;step=null;stepName="";delegatedStep=null;pendingAttack=null;lastCombatIdentity=null;
			combatPrepared=false;emptyPrepared=false;journalSynced=false;questProgress=client.getVarpValue(347);status="Preflight";
			return Map.of("accepted",true);
		}
		catch(RuntimeException e){error=e.getMessage();status="Error: "+error;return Map.of("accepted",false,"error",error);}
	}
}
