package net.runelite.client.plugins.microbot.madangel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.util.walker.Rs2MiniMap;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

final class MadAngelScript extends Script
{
	private static final String BANK_REQUIRED = "Build the Wyrmscraig bank chest before starting Mad Angel.";
	private static final String WALKER = "net.runelite.client.plugins.microbot.efficientwalker.EfficientWalkerPlugin";
	private static final WorldPoint BANK_TILE = new WorldPoint(2587, 2259, 0);
	private static final int[] PASSAGE_NECKLACES = {21146, 21149, 21151, 21153, 21155};
	private static final int[] SLAYER_RINGS = {21268, 11866, 11867, 11868, 11869, 11870, 11871, 11872, 11873};
	private static final int[] COMBAT_POTIONS = {12701, 12699, 12697, 12695};
	private final Client client;
	private final PluginManager manager;
	private final AtomicBoolean busy = new AtomicBoolean();
	private MadAngelConfig config;
	private Plugin dependency;
	private Object walker;
	private volatile boolean stopped, finished, dying;
	private int completedKills, lootStart = -1, emptyLootTicks, pendingLoot = -1, pendingLootBefore, pendingLootTick;
	private WorldPoint deathPosition;
	private boolean deathStopped;
	private final Map<Integer, Integer> collectedLoot = new LinkedHashMap<>();
	private volatile String status = "Stopped", error;
	private volatile Map<String, Object> telemetry = Collections.emptyMap();
	private int cleaveAnimation = -1;
	private int animation = -1, animationTick = -100, facing, lastAction = -100;
	private int lastEat = -100, lastDrink = -100, fightStart = -1, missingSince = -1, escapeStart = -1;
	private int prayerRequest = -100, prayerAttempts, actions, foods, drinks, observedCleave, observedSmite, observedBomb;
	private int pendingFood = -1, pendingFoodCount, pendingDrink = -1, pendingDrinkCount, pietyAttempts, preparationAttempts, lastSpec = -100, specAttempts, specEnergyBefore;
	private int confirmedFood, confirmedDrinks;
	private int smiteUntil = -1, cleaveUntil = -1, bombEnd = -1, busySince;
	private WorldPoint cleaveOrigin, bombTile, movingTo, lastPosition;
	private int moveTick, noProgressSince;
	private boolean fightSeen, fightWon, prepared, previousMelee, previousMagic, previousPiety;

	private boolean restockAfterEscape, banking, routeOwned, bankWasOpen;
	private int bankStart, bankProgress, bankEpoch, bankOpenAttempts, bankPending = -1, bankPendingId, bankExpectedInventory, bankExpectedBank, bankActionTick;
	private WorldPoint bankPosition;

	private boolean returning, recoveryFood, recoveryStarted;
	private int recoveryItem = -1, recoveryAttempts;
	private int returnStart, returnAttempts, returnProgress;
	private String returnAction;
	private WorldPoint returnPosition;

	MadAngelScript(Client client, PluginManager manager) { this.client = client; this.manager = manager; }

	boolean run(MadAngelConfig config)
	{
		if (stopped) { return false; }
		this.config = config;
		try
		{
			if (config.escapePercent() < 1 || config.escapePercent() > 60 || config.eatPercent() < 1 || config.eatPercent() > 100 || config.food() == null || config.prayerPotion() == null
				|| config.foodAmount() < 1 || config.foodAmount() > 26 || config.prayerAmount() < 1 || config.prayerAmount() > 26 || config.foodAmount() + config.prayerAmount() > 25)
			{
				throw new IllegalStateException("Set valid HP percentages and supply amounts totalling at most 25 slots, leaving room for the Royal seed pod, Slayer ring, and Necklace of passage.");
			}
			List<Plugin> matches = dependencies();
			if (matches.size() != 1 || !manager.isActive(matches.get(0))) { throw new IllegalStateException("Enable exactly one Efficient Walker, then restart Mad Angel."); }
			dependency = matches.get(0);
			walker = dependency.getClass().getMethod("getWalker").invoke(dependency);
			walker.getClass().getMethod("walkTo", WorldPoint.class);
			walker.getClass().getMethod("cancel");
			walker.getClass().getMethod("getStatus");
			if (client.getGameState() != GameState.LOGGED_IN) { throw new IllegalStateException("Log in before starting Mad Angel."); }
			if (client.getVarbitValue(15774) != 1) { throw new IllegalStateException(BANK_REQUIRED); }
			previousMelee = prayer(Rs2PrayerEnum.PROTECT_MELEE);
			previousMagic = prayer(Rs2PrayerEnum.PROTECT_MAGIC);
			previousPiety = prayer(Rs2PrayerEnum.PIETY);
			status = "Waiting for Mad Angel";
			return true;
		}
		catch (ReflectiveOperationException | RuntimeException ex) { fail(ex.getMessage()); return false; }
	}

	private List<Plugin> dependencies()
	{
		return manager.getPlugins().stream().filter(p -> WALKER.equals(p.getClass().getName()))
			.collect(java.util.stream.Collectors.toList());
	}

	private boolean dependencyActive()
	{
		try
		{
			List<Plugin> matches = dependencies();
			return matches.size() == 1 && matches.get(0) == dependency && manager.isActive(dependency)
				&& dependency.getClass().getMethod("getWalker").invoke(dependency) == walker;
		}
		catch (ReflectiveOperationException | RuntimeException ex) { return false; }
	}

	void onAnimationChanged(AnimationChanged event)
	{
		if (!(event.getActor() instanceof NPC)) { return; }
		NPC npc = (NPC) event.getActor();
		if (!MadAngelMechanics.boss(npc.getId()) || client.getLocalPlayer() == null
			|| npc.getWorldView() != client.getLocalPlayer().getWorldView()) { return; }
		animation = npc.getAnimation();
		animationTick = client.getTickCount();
		if (animation == 14448) { dying = true; deathPosition = npc.getWorldArea().toWorldPoint(); lastAction = -100; }
		facing = npc.getOrientation();
		if (animation == 4589 || MadAngelMechanics.cleave(animation)) { smiteUntil = -1; }
		if (MadAngelMechanics.smite(animation))
		{
			smiteUntil = animationTick + MadAngelMechanics.smiteTicks(animation);
			observedSmite++;
		}
		if (MadAngelMechanics.cleave(animation))
		{
			cleaveAnimation = animation;
			cleaveUntil = animationTick + MadAngelMechanics.cleaveTicks(animation);
			cleaveOrigin = npc.getWorldArea().toWorldPoint();
			observedCleave++;
		}
	}

	void trackCleave(NPC npc)
	{
		if (MadAngelMechanics.cleave(npc.getAnimation()))
		{
			cleaveAnimation = npc.getAnimation();
			facing = npc.getOrientation();
			cleaveOrigin = npc.getWorldArea().toWorldPoint();
		}
	}

	void onProjectileMoved(ProjectileMoved event)
	{
		if (event.getPosition() == null || event.getProjectile().getId() != 4015 || event.getProjectile().getTargetActor() instanceof NPC) { return; }
		WorldPoint destination = WorldPoint.fromLocal(client, event.getPosition());
		if (bombEnd != event.getProjectile().getEndCycle() || !destination.equals(bombTile)) { observedBomb++; }
		bombTile = destination;
		bombEnd = event.getProjectile().getEndCycle();
	}

	void onGameTick()
	{
		if (config == null || stopped || finished) { return; }
		try
		{
			if (!dependencyActive()) { fail("Efficient Walker became unavailable. Enable one compatible instance and restart Mad Angel."); return; }
			if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) { fail("Login was lost. Log in safely, then restart Mad Angel."); return; }
			if (client.getBoostedSkillLevel(Skill.HITPOINTS) <= 0) { fail("You died. Recover your items and review the escape threshold before restarting Mad Angel."); return; }
			if (client.getVarbitValue(15774) != 1) { fail(BANK_REQUIRED); return; }
			int tick = client.getTickCount();
			Player player = client.getLocalPlayer();
			WorldPoint position = player.getWorldLocation();
			WorldPoint mapped = WorldPoint.fromLocalInstance(client, player.getLocalLocation());
			Rs2NpcModel boss = Microbot.getRs2NpcCache().query().where(n -> MadAngelMechanics.boss(n.getId())
				&& n.getNpc().getWorldView() == player.getWorldView()).first();
			Map<String, Object> report = new LinkedHashMap<>();
			report.put("tick", tick); report.put("world", client.getWorld()); report.put("hp", client.getBoostedSkillLevel(Skill.HITPOINTS));
			report.put("prayer", client.getBoostedSkillLevel(Skill.PRAYER)); report.put("position", position.toString()); report.put("mappedPosition", mapped.toString());
			report.put("animation", boss == null ? -1 : boss.getAnimation()); report.put("animationTick", animationTick);
			report.put("bossId", boss == null ? -1 : boss.getId()); report.put("bossHealth", boss == null ? -1 : boss.getHealthRatio());
			report.put("cleaves", observedCleave); report.put("smites", observedSmite); report.put("bombs", observedBomb);
			report.put("foodRequests", foods); report.put("drinkRequests", drinks); report.put("actions", actions);
			report.put("protectMagic", prayer(Rs2PrayerEnum.PROTECT_MAGIC)); report.put("protectMelee", prayer(Rs2PrayerEnum.PROTECT_MELEE));
			report.put("confirmedFood", confirmedFood); report.put("confirmedDrinks", confirmedDrinks);
			report.put("bossPosition", boss == null ? null : boss.getWorldLocation().toString());
			report.put("movingTo", String.valueOf(movingTo)); report.put("cleaveUntil", cleaveUntil);
			report.put("orientation", facing); report.put("bombTile", String.valueOf(bombTile)); report.put("bombEnd", bombEnd); report.put("cycle", client.getGameCycle());
			report.put("kills", completedKills); report.put("loot", new LinkedHashMap<>(collectedLoot));
			report.put("banking", banking); report.put("returning", returning);
			report.put("status", status); report.put("quest", client.getVarbitValue(15759));
			telemetry = Collections.unmodifiableMap(report);
			if (Microbot.pauseAllScripts.get()) { status = "Paused"; return; }
			if (client.getItemContainer(InventoryID.INVENTORY) == null) { fail("Inventory state is unavailable. Restore the connection before restarting."); return; }
			if (banking) { restock(tick, position); return; }
			if (returning) { returnToBoss(tick, mapped, boss != null); return; }
			if (!prepared && !fightSeen && boss == null && (Math.abs(mapped.getX() - 2534) > 40 || Math.abs(mapped.getY() - 2215) > 40))
			{
				beginBank(tick); return;
			}
			if (escapeStart < 0 && (fightSeen || completedKills > 0 || boss != null)
				&& MadAngelMechanics.emergencyHp(client.getBoostedSkillLevel(Skill.HITPOINTS), client.getRealSkillLevel(Skill.HITPOINTS), config.escapePercent(), count(foodChoice().id)))
			{
				restockAfterEscape = true; escape("Food is exhausted and health is at or below " + config.escapePercent() + "% HP. Restock before restarting."); return;
			}
			if (busy.get())
			{
				if (tick - busySince > 3 && fightSeen) { error = "A game input stalled. Escaping; restart after checking the connection."; }
				return;
			}
			if (escapeStart >= 0)
			{
				if (Math.abs(mapped.getX() - 2534) > 40 || Math.abs(mapped.getY() - 2215) > 40)
				{
					if (restockAfterEscape) { beginBank(tick); } else { finish("Escaped: " + error); } return;
				}
				if (tick - escapeStart >= 12) { fail("Emergency teleport did not complete. Take control and teleport out."); return; }
				if (tick - lastAction >= 3) { submit(() -> Rs2Inventory.interact(19564, "Commune")); }
				return;
			}
			if (fightSeen && !fightWon && (client.getVarbitValue(15759) == 17 || Microbot.getRs2NpcCache().query()
				.where(n -> MadAngelMechanics.dead(n.getId()) && n.getNpc().getWorldView() == player.getWorldView()).first() != null))
			{
				fightWon = true; dying = true; completedKills++; lootStart = tick;
				if (pendingFood != -1) { confirmedFood += Math.max(0, pendingFoodCount - count(pendingFood)); pendingFood = -1; }
				if (pendingDrink != -1) { if (count(pendingDrink) < pendingDrinkCount) { confirmedDrinks++; } pendingDrink = -1; }
				if (deathPosition == null) { deathPosition = position; }
			}
			if (fightWon) { loot(tick, position); return; }
			if (dying)
			{
				status = "Mad Angel is dying; waiting for drops";
				if (!deathStopped) { deathStopped = true; submit(() -> move(position)); }
				return;
			}
			if (!fightSeen && !prepared)
			{
				String problem = suppliesProblem();
				if (problem != null) { if (boss == null) { fail(problem); } else { escape(problem); } return; }
				if (client.getVarpValue(172) == 1 && Rs2Player.isRunEnabled()) { prepared = true; }
				else
				{
					if (++preparationAttempts > 3) { if (boss == null) { fail("Enable run and disable auto-retaliate, then restart Mad Angel."); } else { escape("Combat setup did not apply. Enable run and disable auto-retaliate before restarting."); } return; }
					submit(() -> { if (allowed()) { Rs2Combat.setAutoRetaliate(false); } if (allowed()) { Rs2Player.toggleRunEnergy(true); } });
					return;
				}
			}
			if (boss == null)
			{
				if (!fightSeen) { status = "Ready; enter and wake Mad Angel"; return; }
				if (missingSince < 0) { missingSince = tick; }
				if (tick - missingSince >= 5) { escape("Boss state was lost. Check the encounter before restarting."); }
				return;
			}
			missingSince = -1;
			trackCleave(boss.getNpc());
			if (!fightSeen)
			{
				String problem = suppliesProblem();
				if (problem != null) { escape(problem); return; }
				fightSeen = true; fightStart = tick;
			}
			confirmConsumption(tick);
			if (escapeStart >= 0) { return; }
			if (error != null || tick - fightStart > 800) { escape(error == null ? "Fight timed out. Restock before trying again." : error); return; }
			int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
			if (client.getBoostedSkillLevel(Skill.PRAYER) <= 5 && firstPrayerPotion() == -1)
			{
				restockAfterEscape = true; escape("Prayer is nearly empty and no " + prayerChoice().name + " remains. Restock before restarting."); return;
			}
			if (!MadAngelMechanics.known(boss.getAnimation())) { escape("Unknown boss animation " + boss.getAnimation() + ". Review the encounter before restarting."); return; }
			boolean magic = tick < smiteUntil;
			Rs2PrayerEnum protection = magic ? Rs2PrayerEnum.PROTECT_MAGIC : Rs2PrayerEnum.PROTECT_MELEE;
			boolean changePrayer = !prayer(protection);
			if (changePrayer)
			{
				if (tick - prayerRequest > 2) { prayerAttempts = 0; }
				if (++prayerAttempts > 3) { escape("Protection prayer did not activate. Check prayer setup before restarting."); return; }
				prayerRequest = tick;
			}
			else { prayerAttempts = 0; }
			WorldPoint destination = null;
			boolean bounce = bombTile != null && client.getGameCycle() <= bombEnd;
			boolean cleave = cleaveOrigin != null && MadAngelMechanics.cleave(boss.getAnimation());
			if (bounce) { destination = bombTile; status = "Return the light orb"; }
			else if (cleave)
			{
				destination = MadAngelMechanics.dodge(position, cleaveOrigin, facing, cleaveAnimation, this::reachable);
				if (destination == null) { escape("No safe cleave tile is reachable. Reposition before restarting."); return; }
				status = "Dodge sword cleave";
			}
			else
			{
				WorldPoint anchor = new WorldPoint(position.getX() - mapped.getX() + 2533, position.getY() - mapped.getY() + 2216, position.getPlane());
				destination = MadAngelMechanics.reposition(boss.getWorldArea().toWorldPoint(), anchor, this::standable);
				status = destination != null ? "Bring Mad Angel away from the pews" : magic ? "Protect from Magic" : "Attack Mad Angel";
			}
			if (destination != null && !reachable(destination)) { escape("The required mechanic tile is blocked. Reposition before restarting."); return; }
			if (position.equals(lastPosition))
			{
				if (movingTo != null && !position.equals(movingTo) && tick - noProgressSince > 3) { escape("Movement did not progress. Check the route before restarting."); return; }
			}
			else { lastPosition = position; noProgressSince = tick; }
			int foodId = MadAngelMechanics.eatHp(hp, client.getRealSkillLevel(Skill.HITPOINTS), config.eatPercent()) && pendingFood == -1 && canEat(tick, lastEat, lastDrink) ? foodChoice().id : -1;
			int food = foodId != -1 && count(foodId) > 0 ? foodId : -1;
			int potion = pendingDrink == -1 && tick - lastDrink >= 3 && client.getBoostedSkillLevel(Skill.PRAYER) < 25 ? firstPrayerPotion() : -1;
			if (potion == -1 && pendingDrink == -1 && tick - lastDrink >= 3 && client.getBoostedSkillLevel(Skill.STRENGTH) <= client.getRealSkillLevel(Skill.STRENGTH) + 4)
			{
				potion = firstOwned(COMBAT_POTIONS);
			}
			boolean turnPietyOn = config.piety() && !prayer(Rs2PrayerEnum.PIETY) && client.getBoostedSkillLevel(Skill.PRAYER) > 25;
			if (!turnPietyOn) { pietyAttempts = 0; }
			if (client.getVarpValue(301) == 1 || client.getVarpValue(300) < specEnergyBefore) { specAttempts = 0; }
			boolean special = !cleave && !bounce && food == -1 && client.getVarpValue(300) >= 250
				&& client.getVarpValue(301) == 0 && tick - lastSpec >= 4 && specAttempts < 3;
			WorldPoint next = destination;
			int drink = potion, index = boss.getIndex();
			boolean attack = next == null || position.equals(next) && boss.getWorldArea().isInMeleeDistance(player.getWorldArea());
			boolean attackNeeded = attack && tick - lastAction >= 2 && (player.getInteracting() == null || player.getInteracting().getName() == null
				|| !"Mad Angel".equals(player.getInteracting().getName()));
			boolean hold = next != null && position.equals(next) && !boss.getWorldArea().isInMeleeDistance(player.getWorldArea())
				&& (player.getInteracting() != null || client.getLocalDestinationLocation() != null);
			boolean moveNeeded = next != null && (hold || !position.equals(next)) && (!next.equals(movingTo) || tick - moveTick >= 2);
			Action selected = chooseAction(changePrayer, moveNeeded, food != -1, drink != -1, turnPietyOn, special, attackNeeded);
			if (selected == Action.NONE) { return; }
			if (selected == Action.EAT) { lastEat = tick; pendingFood = food; pendingFoodCount = count(food); }
			if (selected == Action.DRINK) { lastDrink = tick; pendingDrink = drink; pendingDrinkCount = count(drink); }
			if (selected == Action.MOVE) { if (movingTo == null || !movingTo.equals(next)) { noProgressSince = tick; } movingTo = next; moveTick = tick; }
			if (next == null || position.equals(next)) { movingTo = null; }
			if (selected == Action.SPECIAL) { lastSpec = tick; specAttempts++; specEnergyBefore = client.getVarpValue(300); }
			if (selected == Action.PIETY && ++pietyAttempts > 3) { escape("Piety did not activate. Unlock it or disable Use Piety before restarting."); return; }

			submit(() ->
			{
				if (dying) { return; }
				if (selected == Action.PRAYER && allowed()) { Rs2Prayer.toggle(protection); }
				if (selected == Action.MOVE) { move(next); }
				if (selected == Action.EAT && allowed() && Rs2Inventory.interact(food, "Eat")) { foods++; }
				if (selected == Action.DRINK && allowed() && Rs2Inventory.interact(drink, "Drink")) { drinks++; }
				if (selected == Action.PIETY && allowed()) { Rs2Prayer.toggle(Rs2PrayerEnum.PIETY); }
				if (selected == Action.SPECIAL && allowed())
				{
					Microbot.getClientThread().invoke(() ->
					{
						if (allowedOnClientThread(tick) && client.getTickCount() - lastSpec <= 1) { Rs2Combat.setSpecState(true, 250); }
					});
				}
				if (selected == Action.ATTACK && allowed())
				{
					Rs2NpcModel current = Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getRs2NpcCache().query()
						.where(n -> n.getIndex() == index && MadAngelMechanics.boss(n.getId())).first()).orElse(null);
					if (current != null && !dying && current.getAnimation() != 14448 && current.getAnimation() != 3321 && allowed()) { current.click("Attack"); }
				}
			});
		}
		catch (RuntimeException ex) { if (fightSeen && dependencyActive()) { escape("Fight controller failed: " + ex.getClass().getSimpleName() + ". Inspect the encounter before restarting."); } else { fail("Fight controller failed. Inspect setup before restarting."); } }
	}

	void confirmConsumption(int tick)
	{
		if (pendingFood != -1)
		{
			if (count(pendingFood) < pendingFoodCount) { confirmedFood += pendingFoodCount - count(pendingFood); pendingFood = -1; lastEat = tick; }
			else if (tick - lastEat >= 4) { escape("Eating did not consume food. Check the inventory before restarting."); return; }
		}
		if (pendingDrink != -1)
		{
			if (count(pendingDrink) < pendingDrinkCount) { confirmedDrinks++; pendingDrink = -1; lastDrink = tick; }
			else if (tick - lastDrink >= 4) { escape("Drinking did not consume a dose. Check the inventory before restarting."); return; }
		}
	}

	static boolean canEat(int tick, int ate, int drank) { return tick - ate >= 3 && tick - drank >= 3; }

	enum Action { PRAYER, MOVE, EAT, DRINK, PIETY, SPECIAL, ATTACK, NONE }

	static Action chooseAction(boolean prayer, boolean move, boolean eat, boolean drink, boolean piety, boolean special, boolean attack)
	{
		return prayer ? Action.PRAYER : move ? Action.MOVE : eat ? Action.EAT : drink ? Action.DRINK
			: piety ? Action.PIETY : special ? Action.SPECIAL : attack ? Action.ATTACK : Action.NONE;
	}

	private void loot(int tick, WorldPoint position)
	{
		status = "Collecting Mad Angel drops";
		if (client.getItemContainer(InventoryID.INVENTORY) == null) { escape("Inventory unavailable while looting."); return; }
		if (pendingLoot != -1)
		{
			int gained = count(pendingLoot) - pendingLootBefore;
			if (gained > 0) { collectedLoot.merge(pendingLoot, gained, Integer::sum); pendingLoot = -1; }
			else if (tick - pendingLootTick >= 5) { escape("Drop pickup was not confirmed. Check the remaining loot."); return; }
			else { return; }
		}
		if (tick - lootStart >= 12) { escape("Looting timed out. Check the remaining drops."); return; }
		Rs2TileItemModel item = Microbot.getRs2TileItemCache().query()
			.where(t -> present(t) && t.isOwned() && t.getWorldLocation().distanceTo(deathPosition) <= 6).first();
		if (item == null)
		{
			if (++emptyLootTicks < 2) { return; }
			fightWon = false; fightSeen = false; dying = false; deathStopped = false; deathPosition = null;
			lootStart = -1; emptyLootTicks = 0; missingSince = -1; fightStart = -1;
			cleaveUntil = -1; cleaveOrigin = null; bombTile = null; bombEnd = -1; smiteUntil = -1; movingTo = null;
			status = "Loot collected; ready for the next Mad Angel"; return;
		}
		emptyLootTicks = 0;
		if (!reachable(item.getWorldLocation())) { escape("The drop tile is blocked. Check the remaining loot."); return; }
		boolean hasSpace = client.getItemContainer(InventoryID.INVENTORY).getItems().length < 28;
		for (net.runelite.api.Item slot : client.getItemContainer(InventoryID.INVENTORY).getItems()) { if (slot.getId() < 0) { hasSpace = true; break; } }
		if (!hasSpace && !(item.isStackable() && count(item.getId()) > 0)) { restockAfterEscape = false; escape("Inventory is full and loot remains. Check the drops and bank before restarting."); return; }
		pendingLoot = item.getId(); pendingLootBefore = count(pendingLoot); pendingLootTick = tick;
		int id = pendingLoot; WorldPoint tile = item.getWorldLocation();
		submit(() ->
		{
			Rs2TileItemModel current = Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getRs2TileItemCache().query()
				.where(t -> present(t) && t.getId() == id && t.isOwned() && t.getWorldLocation().equals(tile)).first()).orElse(null);
			if (current != null && allowed()) { current.pickup(); }
		});
	}

	static boolean present(Rs2TileItemModel item)
	{
		List<net.runelite.api.TileItem> items = item.getTile().getGroundItems();
		return items != null && items.contains(item.getTileItem());
	}

	private void beginBank(int tick)
	{
		cancelRoute(); resetEncounter();
		banking = true; returning = false; restockAfterEscape = false; prepared = false;
		bankOpenAttempts = 0; bankPending = -1; recoveryItem = -1; recoveryStarted = false; recoveryAttempts = 0; escapeStart = -1; error = null;
		bankStart = bankProgress = tick; bankPosition = client.getLocalPlayer().getWorldLocation();
		bankWasOpen = Rs2Bank.isOpen(); bankEpoch = Rs2Bank.getBankLiveEpoch();
		status = "Travelling to the Wyrmscraig bank chest";
	}

	private void restock(int tick, WorldPoint position)
	{
		if (busy.get())
		{
			if (tick - busySince > 20) { fail("Bank input stalled. Check the bank interface, then restart."); }
			return;
		}
		if (error != null) { fail(error); return; }
		if (pendingFood != -1 || pendingDrink != -1)
		{
			confirmConsumption(tick);
			if (finished || pendingFood != -1 || pendingDrink != -1) { return; }
		}
		if (recoveryStarted)
		{
			recoveryItem = -1; recoveryStarted = false; recoveryAttempts = 0;
			bankWasOpen = false; bankEpoch = Rs2Bank.getBankLiveEpoch();
		}
		if (recoveryItem != -1)
		{
			if (Rs2Bank.isOpen())
			{
				if (++recoveryAttempts > 3) { fail("Could not close the bank to restore stats. Close it, then restart."); return; }
				submit(Rs2Bank::closeBank); return;
			}
			if (count(recoveryItem) == 0) { fail("Recovery item disappeared. Check the inventory, then restart."); return; }
			if (recoveryFood && !canEat(tick, lastEat, lastDrink) || !recoveryFood && tick - lastDrink < 3) { return; }
			int id = recoveryItem;
			if (recoveryFood) { pendingFood = id; pendingFoodCount = count(id); lastEat = tick; }
			else { pendingDrink = id; pendingDrinkCount = count(id); lastDrink = tick; }
			recoveryStarted = true;
			submit(() -> Rs2Inventory.interact(id, recoveryFood ? "Eat" : "Drink")); return;
		}
		Rs2PrayerEnum disable = !previousMelee && prayer(Rs2PrayerEnum.PROTECT_MELEE) ? Rs2PrayerEnum.PROTECT_MELEE
			: !previousMagic && prayer(Rs2PrayerEnum.PROTECT_MAGIC) ? Rs2PrayerEnum.PROTECT_MAGIC
			: !previousPiety && prayer(Rs2PrayerEnum.PIETY) ? Rs2PrayerEnum.PIETY : null;
		if (disable != null) { submit(() -> Rs2Prayer.toggle(disable)); return; }
		if (tick - bankStart > 600) { fail("Banking timed out. Move to the Wyrmscraig bank chest, then restart."); return; }
		if (!atBank(position, client.getTopLevelWorldView().isInstance()))
		{
			if (Rs2Bank.isOpen()) { submit(Rs2Bank::closeBank); return; }
			if (!position.equals(bankPosition)) { bankPosition = position; bankProgress = tick; }
			if (position.distanceTo(BANK_TILE) > 200 && firstOwned(PASSAGE_NECKLACES) == -1)
			{
				fail("Carry a charged Necklace of passage to reach the Wyrmscraig bank chest, then restart Mad Angel."); return;
			}
			try
			{
				if (!routeOwned)
				{
					if (!(Boolean) walker.getClass().getMethod("walkTo", WorldPoint.class).invoke(walker, BANK_TILE)) { fail("Wyrmscraig bank route was rejected. Check Efficient Walker, then restart."); return; }
					routeOwned = true; return;
				}
				String route = walker.getClass().getMethod("getStatus").invoke(walker).toString();
				if ("BLOCKED".equals(route) || "IDLE".equals(route) || "ARRIVED".equals(route) || tick - bankProgress > 60)
				{
					fail("Wyrmscraig bank route stopped before the chest. Check Efficient Walker, then restart.");
				}
			}
			catch (ReflectiveOperationException ex) { fail("Bank travel API failed. Enable a compatible Efficient Walker, then restart."); }
			return;
		}
		if (!Rs2Bank.isOpen())
		{
			status = "Opening the Wyrmscraig bank chest";
			if (++bankOpenAttempts > 3) { fail("Could not open the Wyrmscraig bank chest. Move beside it, then restart."); return; }
			submit(() -> Rs2GameObject.interact(62390, "Use")); return;
		}
		cancelRoute();
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null || !Rs2Bank.verifyBankMirrorAfterOpen(bankWasOpen, bankEpoch))
		{
			status = "Waiting for current bank contents";
			if (tick - bankStart > 30 && bankOpenAttempts > 0) { fail("Current bank contents were not received. Reopen the bank, then restart."); }
			return;
		}
		if (bankPending != -1)
		{
			if (count(bankPending) == bankExpectedInventory && bank.count(bankPendingId) == bankExpectedBank) { bankPending = -1; }
			else if (tick - bankActionTick > 12) { fail("Bank transfer was not confirmed. Check inventory and bank contents, then restart."); return; }
			else { return; }
		}
		if (!Rs2Bank.hasWithdrawAsItem())
		{
			if (++bankOpenAttempts > 6) { fail("Could not select unnoted bank withdrawals. Select Item in the bank, then restart."); return; }
			submit(() -> { if (!Rs2Bank.hasWithdrawAsItem()) { net.runelite.client.plugins.microbot.util.widget.Rs2Widget.clickWidget(net.runelite.api.gameval.InterfaceID.Bankmain.NOTE); } }); return;
		}
		if (client.getBoostedSkillLevel(Skill.PRAYER) < 25)
		{
			status = "Restoring Prayer at the bank";
			int id = bankPrayerPotion(bank);
			if (id == -1) { fail("Add 1 " + prayerChoice().name + " to the bank to restore Prayer before returning."); return; }
			if (count(id) == 0)
			{
				trackBankTransfer(id, 1, bank.count(id) - 1, tick); submit(() -> Rs2Bank.withdrawX(id, 1)); return;
			}
			recoveryItem = id; recoveryFood = false; return;
		}
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		Map<Integer, Integer> wanted = restockTargets(config, inventory.getItems());
		for (int[] variants : new int[][]{SLAYER_RINGS, PASSAGE_NECKLACES})
		{
			if (firstOwned(variants) == -1) { for (int id : variants) { if (bank.count(id) > 0) { wanted.remove(variants == SLAYER_RINGS ? 11866 : variants[0]); wanted.put(id, 1); break; } } }
		}
		if (wanted.values().stream().mapToInt(Integer::intValue).sum() > 28) { fail("Supplies and retained travel items exceed 28 slots. Reduce the food or potion amount, then restart."); return; }
		status = "Depositing loot and restocking";
		for (net.runelite.api.Item item : inventory.getItems())
		{
			int id = item.getId();
			if (id > 0 && count(id) > wanted.getOrDefault(id, 0))
			{
				trackBankTransfer(id, 0, bank.count(bankItemId(client.getItemDefinition(id))) + count(id), tick);
				submit(() -> Rs2Bank.depositAll(id)); return;
			}
		}
		for (Map.Entry<Integer, Integer> item : wanted.entrySet())
		{
			int id = item.getKey(), deficit = item.getValue() - count(id);
			if (deficit <= 0) { continue; }
			if (bank.count(id) < deficit)
			{
				String name = id == foodChoice().id ? foodChoice().name : id == prayerChoice().ids[0] ? prayerChoice().name + "(4)" : id == 19564 ? "Royal seed pod" : java.util.Arrays.stream(PASSAGE_NECKLACES).anyMatch(n -> n == id) ? "charged Necklace of passage" : "charged Slayer ring";
				fail("Add " + (deficit - bank.count(id)) + " " + name + " to the bank, then restart Mad Angel."); return;
			}
			trackBankTransfer(id, item.getValue(), bank.count(id) - deficit, tick);
			submit(() -> Rs2Bank.withdrawX(id, deficit)); return;
		}
		if (client.getBoostedSkillLevel(Skill.HITPOINTS) < client.getRealSkillLevel(Skill.HITPOINTS))
		{
			status = "Recovering Hitpoints at the bank";
			recoveryItem = foodChoice().id; recoveryFood = true;
			return;
		}
		if (client.getVarbitValue(15759) != 20) { fail("Complete Fallen From Grace before using automatic return to Mad Angel."); return; }
		banking = false; returning = true; returnStart = returnProgress = tick; returnAttempts = 0; returnAction = null;
		returnPosition = position; status = "Restocked; returning to Mad Angel";
	}

	void resetEncounter()
	{
		fightSeen = false; fightWon = false; dying = false; deathStopped = false; deathPosition = null;
		pendingFood = pendingDrink = pendingLoot = -1; lootStart = -1; emptyLootTicks = 0;
		fightStart = missingSince = -1; cleaveUntil = smiteUntil = bombEnd = -1;
		cleaveOrigin = bombTile = movingTo = lastPosition = null;
		prayerAttempts = pietyAttempts = preparationAttempts = specAttempts = 0;
		lastEat = lastDrink = lastSpec = -100;
	}

	enum ReturnStep { TELEPORT, GOLEM, STAIRS, PEW, WAKE }

	static ReturnStep returnStep(WorldPoint p, boolean instance)
	{
		if (p.getPlane() == 0 && p.getX() >= 2520 && p.getX() <= 2550 && p.getY() >= 2205 && p.getY() <= 2228)
		{
			return instance ? ReturnStep.WAKE : ReturnStep.PEW;
		}
		if (p.getPlane() == 0 && p.getX() >= 2550 && p.getX() <= 2610 && p.getY() >= 8620 && p.getY() <= 8670)
		{
			return p.getY() >= 8651 ? ReturnStep.STAIRS : ReturnStep.GOLEM;
		}
		return ReturnStep.TELEPORT;
	}

	private void returnToBoss(int tick, WorldPoint mapped, boolean activeBoss)
	{
		if (activeBoss)
		{
			cancelRoute(); returning = false; prepared = false; status = "Returned; starting Mad Angel"; return;
		}
		if (busy.get()) { if (tick - busySince > 20) { fail("Return input stalled. Check the interface, then restart."); } return; }
		if (error != null) { fail(error); return; }
		if (tick - returnStart > 300) { fail("Return to Mad Angel timed out. Check the route and teleport, then restart."); return; }
		if (!mapped.equals(returnPosition)) { returnPosition = mapped; returnProgress = tick; }
		if (tick - lastAction < 3 || client.getLocalPlayer().getAnimation() != -1) { return; }
		if (Rs2Bank.isOpen()) { returnInput("Close bank", Rs2Bank::closeBank); return; }
		ReturnStep step = returnStep(mapped, client.getTopLevelWorldView().isInstance());
		if (step == ReturnStep.TELEPORT)
		{
			if (Rs2Dialogue.hasDialogueOption("Wyrmscraig Cavern")) { returnInput("Teleport to Wyrmscraig Cavern", () -> Rs2Dialogue.clickOption("Wyrmscraig Cavern")); }
			else if (Rs2Dialogue.hasDialogueOption("More options")) { returnInput("More Slayer ring destinations", () -> Rs2Dialogue.clickOption("More options")); }
			else if (Rs2Dialogue.hasSelectAnOption() || Rs2Dialogue.hasContinue()) { fail("Unexpected Slayer ring dialogue. Check the destination unlock, then restart."); }
			else
			{
				int ring = firstOwned(SLAYER_RINGS);
				if (ring == -1) { fail("Carry a charged Slayer ring before returning to Mad Angel."); return; }
				returnInput("Open Slayer ring teleport", () -> Rs2Inventory.interact(ring, "Teleport"));
			}
			return;
		}
		if (step == ReturnStep.GOLEM && mapped.distanceTo(new WorldPoint(2573, 8649, 0)) > 1)
		{
			status = "Walking to the cavern golem";
			try
			{
				if (!routeOwned)
				{
					if (!(Boolean) walker.getClass().getMethod("walkTo", WorldPoint.class).invoke(walker, new WorldPoint(2573, 8649, 0))) { fail("Cavern route was rejected. Check Efficient Walker, then restart."); return; }
					routeOwned = true;
				}
				String route = walker.getClass().getMethod("getStatus").invoke(walker).toString();
				if ("BLOCKED".equals(route) || "IDLE".equals(route) || tick - returnProgress > 60) { fail("Cavern route stopped. Check Efficient Walker, then restart."); }
			}
			catch (ReflectiveOperationException ex) { fail("Cavern travel API failed. Enable a compatible Efficient Walker, then restart."); }
			return;
		}
		cancelRoute();
		if (Rs2Dialogue.hasContinue()) { returnInput("Continue cathedral entry", Rs2Dialogue::clickContinue); return; }
		if (step == ReturnStep.GOLEM) { returnInput("Pass the cavern golem", () -> Rs2Npc.interact(16322, "Investigate")); }
		else if (step == ReturnStep.STAIRS) { returnInput("Climb cathedral stairs", () -> Rs2GameObject.interact(62255, "Climb-up")); }
		else if (step == ReturnStep.PEW) { returnInput("Climb church pew", () -> Rs2GameObject.interact(62250, "Climb")); }
		else
		{
			Rs2NpcModel dormant = Microbot.getRs2NpcCache().query().where(n -> n.getId() == 16306
				&& n.getNpc().getWorldView() == client.getLocalPlayer().getWorldView()).first();
			if (dormant != null) { returnInput("Wake Mad Angel", () -> Rs2Npc.interact(16306, "Wake")); }
		}
	}

	private void returnInput(String action, Runnable input)
	{
		if (!action.equals(returnAction)) { returnAction = action; returnAttempts = 0; }
		if (++returnAttempts > 3) { fail(action + " was not confirmed. Check the route or dialogue, then restart."); return; }
		status = action; submit(input);
	}

	static Map<Integer, Integer> restockTargets(MadAngelConfig config, net.runelite.api.Item[] items)
	{
		Map<Integer, Integer> wanted = new LinkedHashMap<>();
		wanted.put(config.food().id, config.foodAmount());
		wanted.put(config.prayerPotion().ids[0], config.prayerAmount());
		wanted.put(19564, 1);
		for (int[] variants : new int[][]{SLAYER_RINGS, PASSAGE_NECKLACES})
		{
			int selected = variants == SLAYER_RINGS ? 11866 : variants[0];
			for (int candidate : variants)
			{
				if (java.util.Arrays.stream(items).anyMatch(item -> item.getId() == candidate && item.getQuantity() > 0)) { selected = candidate; break; }
			}
			wanted.put(selected, 1);
		}
		for (net.runelite.api.Item item : items)
		{
			int id = item.getId();
			if (id == 12701 || id == 12699 || id == 12697 || id == 12695
				|| id == 12625 || id == 12627 || id == 12629 || id == 12631) { wanted.merge(id, item.getQuantity(), Integer::sum); }
		}
		return wanted;
	}

	static boolean atBank(WorldPoint position, boolean instance) { return !instance && position.distanceTo(BANK_TILE) <= 2; }

	static int bankItemId(net.runelite.api.ItemComposition item)
	{
		return item.getNote() == -1 ? item.getId() : item.getLinkedNoteId();
	}

	private void trackBankTransfer(int id, int inventory, int bank, int tick)
	{
		bankPending = id; bankPendingId = bankItemId(client.getItemDefinition(id)); bankExpectedInventory = inventory; bankExpectedBank = bank; bankActionTick = tick;
	}

	private void cancelRoute()
	{
		if (!routeOwned) { return; }
		routeOwned = false;
		try { walker.getClass().getMethod("cancel").invoke(walker); }
		catch (ReflectiveOperationException ex) { error = "Could not cancel the bank route. Stop Efficient Walker before restarting."; }
	}

	private MadAngelConfig.Food foodChoice() { return config == null ? MadAngelConfig.Food.SHARK : config.food(); }
	private MadAngelConfig.PrayerPotion prayerChoice() { return config == null ? MadAngelConfig.PrayerPotion.PRAYER_POTION : config.prayerPotion(); }
	private int foodTarget() { return config == null ? 20 : config.foodAmount(); }
	private int prayerTarget() { return config == null ? 3 : config.prayerAmount(); }
	private int firstPrayerPotion()
	{
		int[] ids = prayerChoice().ids;
		for (int i = ids.length - 1; i >= 0; i--) { if (count(ids[i]) > 0) { return ids[i]; } }
		return -1;
	}

	int bankPrayerPotion(ItemContainer bank)
	{
		int[] ids = prayerChoice().ids;
		for (int i = ids.length - 1; i >= 0; i--) { if (count(ids[i]) > 0 || bank.count(ids[i]) > 0) { return ids[i]; } }
		return -1;
	}

	private int count(int id)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		return inventory == null ? 0 : inventory.count(id);
	}

	private int firstOwned(int[] ids) { for (int id : ids) { if (count(id) > 0) { return id; } } return -1; }
	private boolean prayer(Rs2PrayerEnum prayer) { return client.getVarbitValue(prayer.getVarbit()) == 1; }

	String suppliesProblem()
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null || client.getItemContainer(InventoryID.INVENTORY) == null)
		{
			return "Inventory or equipment state is unavailable. Wait for both containers to load, then restart Mad Angel.";
		}
		if (!equipment.contains(1434)) { return "Equip a dragon mace on crush before starting Mad Angel."; }
		if (client.getVarpValue(43) != 1) { return "Select Pummel (aggressive crush) on the combat tab before starting Mad Angel."; }
		if (config != null && config.piety() && !Rs2Prayer.isPietyUnlocked()) { return "Unlock Piety or disable Use Piety before starting Mad Angel."; }
		if (count(19564) < 1) { return "Withdraw 1 Royal seed pod before starting Mad Angel."; }
		if (completedKills == 0 && count(foodChoice().id) < foodTarget()) { return "Withdraw " + (foodTarget() - count(foodChoice().id)) + " more " + foodChoice().name + " before starting Mad Angel."; }
		if (completedKills == 0 && count(prayerChoice().ids[0]) < prayerTarget()) { return "Withdraw " + (prayerTarget() - count(prayerChoice().ids[0])) + " more " + prayerChoice().name + "(4) before starting Mad Angel."; }
		return null;
	}

	private boolean standable(WorldPoint point)
	{
		LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), point);
		CollisionData[] maps = client.getTopLevelWorldView().getCollisionMaps();
		return local != null && maps != null && maps[point.getPlane()] != null
			&& (maps[point.getPlane()].getFlags()[local.getSceneX()][local.getSceneY()] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
	}

	private boolean reachable(WorldPoint point)
	{
		WorldPoint current = client.getLocalPlayer().getWorldLocation();
		if (!standable(point) || current.distanceTo(point) > 10) { return false; }
		java.util.ArrayDeque<WorldPoint> queue = new java.util.ArrayDeque<>();
		Map<WorldPoint, Integer> distance = new java.util.HashMap<>();
		queue.add(current); distance.put(current, 0);
		while (!queue.isEmpty())
		{
			WorldPoint from = queue.remove();
			if (from.equals(point)) { return true; }
			int steps = distance.get(from);
			if (steps >= 10) { continue; }
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					WorldPoint next = from.dx(dx).dy(dy);
					if (distance.containsKey(next) || !standable(next)
						|| !new WorldArea(from, 1, 1).canTravelInDirection(client.getTopLevelWorldView(), dx, dy)) { continue; }
					distance.put(next, steps + 1); queue.add(next);
				}
			}
		}
		return false;
	}

	private void move(WorldPoint point)
	{
		int requestTick = lastAction;
		Microbot.getClientThread().invoke(() ->
		{
			if (!allowedOnClientThread(requestTick)) { return; }
			if (!reachable(point))
			{
				error = "The mechanic tile could not be reached. Escaping.";
				return;
			}
			LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), point);
			Point minimap = Perspective.localToMinimap(client, local);
			if (minimap == null || !Rs2MiniMap.isPointInsideMinimap(minimap))
			{
				error = "The mechanic tile is outside the minimap. Escaping; restore the minimap before restarting.";
				return;
			}
			Microbot.getMouse().move(minimap).click(minimap);
		});
	}

	private boolean allowedOnClientThread(int requestTick)
	{
		return requestTick == lastAction && allowedOnClientThread();
	}

	private boolean allowedOnClientThread()
	{
		return !stopped && !finished && !Microbot.pauseAllScripts.get()
			&& dependencyActive() && client.getGameState() == GameState.LOGGED_IN && client.getVarbitValue(15774) == 1 && client.getBoostedSkillLevel(Skill.HITPOINTS) > 0 && client.getTickCount() - lastAction <= 1;
	}

	private boolean allowed()
	{
		return Microbot.getClientThread().runOnClientThreadOptional(this::allowedOnClientThread).orElse(false);
	}

	private void submit(Runnable action)
	{
		if (!busy.compareAndSet(false, true)) { return; }
		busySince = client.getTickCount(); lastAction = busySince;
		scheduledExecutorService.execute(() ->
		{
			try
			{
				if (allowed()) { action.run(); actions++; }
			}
			catch (RuntimeException ex) { error = "An input failed. Escaping; inspect the encounter before restarting."; }
			finally { busy.set(false); }
		});
	}

	private void escape(String reason)
	{
		if (banking) { fail(reason); return; }
		error = reason; status = "Escaping: " + reason;
		if (escapeStart < 0)
		{
			escapeStart = client.getTickCount();
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>Error: " + reason + "</col>", "");
			cancelMovement();
		}
		submit(() -> Rs2Inventory.interact(19564, "Commune"));
	}

	private void finish(String message)
	{
		status = message;
		cancelMovement();
		submit(() ->
		{
			if (allowed() && !previousMelee && Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MELEE)) { Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE); }
			if (allowed() && !previousMagic && Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC)) { Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC); }
			if (allowed() && !previousPiety && Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PIETY)) { Rs2Prayer.toggle(Rs2PrayerEnum.PIETY); }
			finished = true;
		});
	}

	private void fail(String message)
	{
		error = message; status = "Error: " + message; finished = true;
		cancelRoute();
		cancelMovement();
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>Error: " + message + "</col>", "");
	}

	private void cancelMovement() { movingTo = null; }

	@Override
	public void shutdown()
	{
		stopped = true;
		cancelRoute();
		scheduledExecutorService.shutdownNow();
	}

	String getStatus() { return status; }
	String getError() { return error; }
	Map<String, Object> getTelemetry() { return new LinkedHashMap<>(telemetry); }
}
