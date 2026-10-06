package net.runelite.client.plugins.microbot.enchanter;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.GameState;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.enchanter.EnchanterResources.BatchPlan;
import net.runelite.client.plugins.microbot.enchanter.EnchanterResources.Source;
import net.runelite.client.plugins.microbot.enchanter.EnchanterResources.StaffChoice;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.RunePouchType;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2RunePouch;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.RuneFilter;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.magic.Rs2Tome;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

final class EnchanterScript extends StateMachineScript<EnchanterScript.State>
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EnchanterScript.class);
	private static final String WALKER_PLUGIN_CLASS =
		"net.runelite.client.plugins.microbot.efficientwalker.EfficientWalkerPlugin";
	private static final int NO_PROGRESS_TICKS = 6;
	private static final int MAX_ACTION_ATTEMPTS = 3;
	private static final int EFFECT_TIMEOUT_TICKS = 10;
	private static final int MAX_EFFECT_RESELECTIONS = 3;
	private static final int TRAVEL_TIMEOUT_TICKS = 30;
	private static final int MAGIC_TAB_SCRIPT = 915;
	private static final int MAGIC_TAB_INDEX = 6;
	private static final int[] BOLT_OPTION_WIDGETS = {
		InterfaceID.Skillmulti.A,
		InterfaceID.Skillmulti.B,
		InterfaceID.Skillmulti.C,
		InterfaceID.Skillmulti.D,
		InterfaceID.Skillmulti.E,
		InterfaceID.Skillmulti.F,
		InterfaceID.Skillmulti.G,
		InterfaceID.Skillmulti.H,
		InterfaceID.Skillmulti.I,
		InterfaceID.Skillmulti.J,
		InterfaceID.Skillmulti.K,
		InterfaceID.Skillmulti.L,
		InterfaceID.Skillmulti.M,
		InterfaceID.Skillmulti.N,
		InterfaceID.Skillmulti.O,
		InterfaceID.Skillmulti.P,
		InterfaceID.Skillmulti.Q,
		InterfaceID.Skillmulti.R
	};
	private static final int[] POUCH_RUNE_VARBITS = {
		Varbits.RUNE_POUCH_RUNE1,
		Varbits.RUNE_POUCH_RUNE2,
		Varbits.RUNE_POUCH_RUNE3,
		Varbits.RUNE_POUCH_RUNE4
	};
	private static final int[] POUCH_AMOUNT_VARBITS = {
		Varbits.RUNE_POUCH_AMOUNT1,
		Varbits.RUNE_POUCH_AMOUNT2,
		Varbits.RUNE_POUCH_AMOUNT3,
		Varbits.RUNE_POUCH_AMOUNT4
	};
	private static final Set<Integer> RUNE_ITEM_IDS = runeItemIds();
	private static final EnumSet<WorldType> UNSUPPORTED_WORLD_TYPES = EnumSet.of(
		WorldType.PVP_ARENA,
		WorldType.QUEST_SPEEDRUNNING,
		WorldType.BETA_WORLD,
		WorldType.LEGACY_ONLY,
		WorldType.EOC_ONLY,
		WorldType.NOSAVE_MODE,
		WorldType.TOURNAMENT_WORLD,
		WorldType.FRESH_START_WORLD,
		WorldType.DEADMAN,
		WorldType.SEASONAL,
		WorldType.LAST_MAN_STANDING);

	@Inject
	private PluginManager pluginManager;

	private volatile int readyTick = -1;
	private volatile InventoryEvidence latestInventory = InventoryEvidence.empty();
	private long inventorySequence;
	private int processedTick = -1;
	private Enchantment target;
	private WalkerBridge walker;
	private volatile String error;
	private volatile boolean complete;
	private volatile int completedEffects;
	private boolean preflightPassed;
	private boolean bankReady;
	private boolean batchReady;
	private boolean spellSelected;
	private boolean dispatched;
	private boolean batchFinished;
	private boolean reselect;
	private boolean closingForBatch;
	private boolean initialInventoryCleaned;
	private boolean inventoryBanked;
	private boolean loadoutClean;
	private int bankedRunePouchId = -1;
	private Map<Runes, Integer> bankedRunePouchRunes = Collections.emptyMap();
	private int bankedAetherRunes;
	private BatchPlan batch;
	private PendingAction pendingAction;
	private String lastActionKey;
	private int actionAttempts;
	private int stableBankEpoch = -1;
	private long stableBankSignature;
	private int stableBankTicks;
	private boolean travelRequested;
	private String lastTravelStatus;
	private WorldPoint lastTravelPosition;
	private int lastTravelProgressTick;
	private Map<Integer, Integer> acceptedInventory;
	private long acceptedInventorySequence;
	private int lastEffectTick;
	private int effectReselections;
	private int boltInterfaceSeenTick = -1;
	private int boltProductWidgetId = -1;
	private int expectedBoltBatch;
	private boolean boltResetRequired;

	enum State
	{
		PREFLIGHT,
		REACH_BANK,
		PREPARE_BATCH,
		SELECT_SPELL,
		ENCHANT,
		WAIT_EFFECT,
		DONE,
		ERROR
	}

	@Override
	protected State initialState()
	{
		return State.PREFLIGHT;
	}

	@Override
	protected List<Transition<State>> defineTransitions()
	{
		return List.of(
			transition(State.PREFLIGHT, State.ERROR, () -> error != null, "Preflight failed"),
			transition(State.PREFLIGHT, State.REACH_BANK,
				() -> preflightPassed, "Preflight passed"),
			transition(State.REACH_BANK, State.ERROR, () -> error != null, "Bank travel failed"),
			transition(State.REACH_BANK, State.DONE, () -> complete, "No configured inputs remain"),
			transition(State.REACH_BANK, State.PREPARE_BATCH,
				() -> bankReady, "Stable bank snapshot received"),
			transition(State.PREPARE_BATCH, State.ERROR,
				() -> error != null, "Batch preparation failed"),
			transition(State.PREPARE_BATCH, State.DONE,
				() -> complete, "No configured inputs remain"),
			transition(State.PREPARE_BATCH, State.SELECT_SPELL,
				() -> batchReady, "Batch prepared"),
			transition(State.SELECT_SPELL, State.ERROR,
				() -> error != null, "Spell selection failed"),
			transition(State.SELECT_SPELL, State.ENCHANT,
				() -> spellSelected, "Exact enchantment selected"),
			transition(State.ENCHANT, State.ERROR,
				() -> error != null, "Enchantment dispatch failed"),
			transition(State.ENCHANT, State.WAIT_EFFECT,
				() -> dispatched, "Exact input targeted"),
			transition(State.WAIT_EFFECT, State.ERROR,
				() -> error != null, "Enchantment effect failed"),
			transition(State.WAIT_EFFECT, State.REACH_BANK,
				() -> batchFinished, "Current batch exhausted"),
			transition(State.WAIT_EFFECT, State.SELECT_SPELL,
				() -> reselect, "No effect; reselecting"));
	}

	private Transition<State> transition(
		State from, State to, BooleanSupplier condition, String reason)
	{
		return Transition.<State>from(from)
			.when(condition, reason)
			.because(reason)
			.goTo(to);
	}

	@Override
	protected void onState(State state)
	{
		if (state != State.PREFLIGHT && state != State.ERROR && state != State.DONE
			&& !validateRuntime())
		{
			return;
		}
		switch (state)
		{
			case PREFLIGHT:
				preflight();
				break;
			case REACH_BANK:
				reachBank();
				break;
			case PREPARE_BATCH:
				prepareBatch();
				break;
			case SELECT_SPELL:
				selectSpell();
				break;
			case ENCHANT:
				dispatchEnchantment();
				break;
			case WAIT_EFFECT:
				waitForEffect();
				break;
			case DONE:
				Microbot.status = "Enchanter: complete";
				break;
			case ERROR:
				Microbot.status = "Enchanter: " + error;
				break;
			default:
				fail("The plugin entered an unexpected state. Restart it. If this repeats, report the debug log.");
		}
	}

	@Override
	protected State onError(State state, Exception exception)
	{
		log.warn("Unexpected enchantment failure in {}", state, exception);
		fail("An unexpected error stopped enchanting. Check your inventory before restarting. If this repeats, report the debug log.");
		return State.ERROR;
	}

	boolean run(Enchantment frozenTarget)
	{
		target = frozenTarget;
		mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(net.runelite.client.util.RunnableExceptionLogger.wrap(() ->
		{
			int tick = readyTick;
			if (tick < 0 || tick == processedTick)
			{
				return;
			}
			processedTick = tick;
			step();
		}), 0, 50, TimeUnit.MILLISECONDS);
		return true;
	}

	void onGameTick(int tick)
	{
		readyTick = tick;
	}

	void onInventoryChanged(int tick, ItemContainer container)
	{
		latestInventory = new InventoryEvidence(++inventorySequence, tick, counts(container));
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

	private void preflight()
	{
		if (Microbot.getClient().getGameState() != GameState.LOGGED_IN
			|| Microbot.getClient().getLocalPlayer() == null)
		{
			return;
		}
		if (target == null)
		{
			fail("Select an item to enchant in the plugin settings, then restart.");
			return;
		}
		if (!requirementsStillHold())
		{
			return;
		}
		try
		{
			walker = WalkerBridge.bind(pluginManager);
		}
		catch (ReflectiveOperationException | IllegalStateException exception)
		{
			log.warn("Efficient Walker connection failed", exception);
			fail(exception instanceof IllegalStateException ? exception.getMessage()
				: "Could not connect to Efficient Walker. Enable exactly one compatible instance, then restart Enchanter.");
			return;
		}
		preflightPassed = true;
	}

	private boolean validateRuntime()
	{
		if (Microbot.getClient().getGameState() != GameState.LOGGED_IN
			|| Microbot.getClient().getLocalPlayer() == null)
		{
			return false;
		}
		if (!requirementsStillHold())
		{
			return false;
		}
		if (!walker.isAvailable())
		{
			fail("Efficient Walker became unavailable or duplicated. Enable exactly one instance, then restart Enchanter.");
			return false;
		}
		return true;
	}

	private boolean requirementsStillHold()
	{
		if (!worldSupported(Microbot.getClient().getWorldType()))
		{
			fail("This world type is unsupported. Switch to a normal world, then restart.");
		}
		else if (target.isMembersOnly()
			&& !Microbot.getClient().getWorldType().contains(WorldType.MEMBERS))
		{
			fail(target + " requires a members world. Switch worlds or select a free-to-play enchantment.");
		}
		else if (!Rs2Magic.isSpellbook(Rs2Spellbook.MODERN))
		{
			fail("Switch to the standard spellbook, then restart.");
		}
		else if (Rs2Player.getBoostedSkillLevel(Skill.MAGIC) < target.getMagicLevel())
		{
			fail(target + " requires " + target.getMagicLevel() + " Magic. Raise or boost your level, or select another enchantment.");
		}
		else if (target.requiresDigsitePendantUnlock()
			&& Microbot.getVarbitValue(VarbitID.VM_NECKLACE) != 1)
		{
			fail("Ruby necklaces require the Digsite pendant enchantment unlock. Unlock it or select another item.");
		}
		else
		{
			Integer conflict = inventoryCounts().keySet().stream()
				.filter(target::conflictsWithInput).findFirst().orElse(null);
			if (conflict != null)
			{
				fail("Your inventory contains " + itemName(conflict) + ", which uses the same enchantment. Bank it, then restart.");
			}
		}
		return error == null;
	}

	private void reachBank()
	{
		Microbot.status = "Enchanter: reaching bank";
		batchFinished = false;
		if (awaitAction())
		{
			return;
		}
		if (Rs2Bank.isOpen())
		{
			travelRequested = false;
			if (stableBankSnapshot())
			{
				bankReady = true;
			}
			return;
		}
		invalidateBankSnapshot();
		if (localBankLoaded())
		{
			int epoch = Rs2Bank.getBankLiveEpoch();
			dispatchAction("open-bank", "Opening the local bank",
				() -> Rs2Bank.isOpen() && Rs2Bank.getBankLiveEpoch() > epoch,
				Rs2Bank::openBank);
			return;
		}
		travelToNearestBank();
	}

	private void travelToNearestBank()
	{
		try
		{
			if (!travelRequested)
			{
				if (!walker.walkToNearestBank())
				{
					fail("Could not start travel to a bank. Check Efficient Walker's error message, then restart.");
					return;
				}
				travelRequested = true;
				lastTravelStatus = walker.status();
				lastTravelPosition = Rs2Player.getWorldLocation();
				lastTravelProgressTick = readyTick;
				return;
			}
			String status = walker.status();
			WorldPoint position = Rs2Player.getWorldLocation();
			if (status == null || position == null)
			{
				fail("Could not read Efficient Walker's travel status. Restart Efficient Walker and Enchanter.");
				return;
			}
			if (!status.equals(lastTravelStatus) || !position.equals(lastTravelPosition))
			{
				lastTravelStatus = status;
				lastTravelPosition = position;
				lastTravelProgressTick = readyTick;
			}
			if ("ARRIVED".equals(status))
			{
				walker.cancel();
				travelRequested = false;
				return;
			}
			if ("BLOCKED".equals(status))
			{
				log.debug("Efficient Walker blocked: {}", walker.planningFailure());
				fail("Travel to the bank stopped. Check Efficient Walker's error message before restarting.");
				return;
			}
			if (!"PLANNING".equals(status) && !"WALKING".equals(status))
			{
				log.debug("Unknown Efficient Walker state: {}", status);
				fail("Efficient Walker returned an unsupported travel status. Install a compatible Efficient Walker and restart Enchanter.");
				return;
			}
			if (readyTick - lastTravelProgressTick >= TRAVEL_TIMEOUT_TICKS)
			{
				fail("Could not make progress towards the bank. Check for an obstacle or open dialogue, then restart.");
			}
		}
		catch (ReflectiveOperationException exception)
		{
			log.warn("Efficient Walker call failed", exception);
			fail("Could not communicate with Efficient Walker. Restart both plugins. If this repeats, report the debug log.");
		}
	}

	private void prepareBatch()
	{
		Microbot.status = "Enchanter: preparing batch";
		if (awaitAction())
		{
			return;
		}
		if (closingForBatch)
		{
			if (Rs2Bank.isOpen())
			{
				fail("Could not close the bank to begin enchanting. Close it manually, then restart.");
				return;
			}
			if (batch == null || Rs2Inventory.itemQuantity(target.getInputId())
				!= batch.getInputQuantity() || !hasRunes(batch.getEffects()))
			{
				fail("Your supplies changed before enchanting started. Check your inventory, then restart.");
				return;
			}
			closingForBatch = false;
			batchReady = true;
			effectReselections = 0;
			return;
		}
		if (!Rs2Bank.isOpen())
		{
			fail("The bank closed before supplies were ready. Reopen it, then restart.");
			return;
		}
		if (!stableBankSnapshot())
		{
			return;
		}
		if (!inventoryBanked)
		{
			if (!bankInventory())
			{
				return;
			}
		}
		if (Microbot.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) == 1)
		{
			dispatchAction("withdraw-item-mode", "Selecting unnoted withdrawals",
				() -> Microbot.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) == 0,
				() -> invokeWidget(InterfaceID.Bankmain.NOTE, "", "",
					MenuAction.CC_OP, 1));
			return;
		}
		int carriedInput = Rs2Inventory.itemQuantity(target.getInputId());
		int bankInput = Rs2Bank.count(target.getInputId());
		if (carriedInput == 0 && bankInput == 0)
		{
			if (completedEffects == 0)
			{
				fail("No " + itemName(target.getInputId()) + " found for enchanting. Add them to your bank, then restart.");
				return;
			}
			if (!Rs2Inventory.isEmpty())
			{
				int epoch = Rs2Bank.getBankLiveEpoch();
				invalidateBankSnapshot();
				dispatchAction("deposit-finished-inventory", "Depositing remaining runes",
					() -> Rs2Inventory.isEmpty() && Rs2Bank.getBankLiveEpoch() > epoch,
					Rs2Bank::depositAll);
				return;
			}
			complete = true;
			return;
		}
		if (!prepareStaff())
		{
			return;
		}
		if (!prepareCleanLoadout(bankInput))
		{
			return;
		}
		Map<Runes, Integer> carriedRunes = effectiveRunes();
		if (carriedRunes == null)
		{
			return;
		}
		BatchPlan plan = EnchanterResources.planBatch(target, carriedInput, bankInput,
			Rs2Inventory.emptySlotCount(), carriedRunes, Rs2Inventory::itemQuantity,
			Rs2Bank::count);
		if (plan == null)
		{
			fail("Could not prepare supplies for " + target + ". Check the required runes and free inventory space, then restart.");
			return;
		}
		for (Map.Entry<Integer, Integer> withdrawal : plan.getWithdrawals().entrySet())
		{
			if (withdrawal.getValue() > 0)
			{
				withdraw(withdrawal.getKey(), withdrawal.getValue());
				return;
			}
		}
		batch = plan;
		closingForBatch = true;
		dispatchAction("close-bank", "Closing the bank for enchanting",
			() -> !Rs2Bank.isOpen(), Rs2Bank::closeBank);
	}

	private boolean prepareStaff()
	{
		int weaponId = equippedWeaponId();
		StaffChoice choice = EnchanterResources.selectStaff(target, isMembersWorld(),
			Rs2Player.getRealSkillLevel(Skill.ATTACK), Rs2Player.getRealSkillLevel(Skill.MAGIC),
			weaponId, Rs2Inventory::itemQuantity, Rs2Bank::count, equippedTomeRunes());
		if (choice == null || choice.getSource() == Source.EQUIPPED)
		{
			return true;
		}
		int staffId = choice.getStaff().getItemId();
		if (choice.getSource() == Source.BANK)
		{
			if (Rs2Inventory.emptySlotCount() == 0)
			{
				fail("No inventory space is available for " + itemName(staffId) + ". Free one slot, then restart.");
				return false;
			}
			withdraw(staffId, 1);
			return false;
		}
		dispatchAction("equip-staff-" + staffId, "Equipping " + itemName(staffId),
			() -> equippedWeaponId() == staffId,
			() -> Rs2Inventory.wield(staffId));
		return false;
	}

	private boolean bankInventory()
	{
		if (initialInventoryCleaned)
		{
			Rs2ItemModel output = Rs2Inventory.get(target.getOutputId());
			if (output != null)
			{
				depositItem(output);
				return false;
			}
			inventoryBanked = true;
			return true;
		}
		if (Rs2Inventory.isEmpty())
		{
			initialInventoryCleaned = true;
			inventoryBanked = true;
			return true;
		}
		captureRunePouch();
		if (error != null)
		{
			return false;
		}
		int epoch = Rs2Bank.getBankLiveEpoch();
		invalidateBankSnapshot();
		dispatchAction("deposit-carried-inventory", "Depositing carried inventory",
			() -> Rs2Inventory.isEmpty() && Rs2Bank.getBankLiveEpoch() > epoch,
			Rs2Bank::depositAll);
		return false;
	}

	private void captureRunePouch()
	{
		Rs2ItemModel pouch = Rs2Inventory.all().stream()
			.filter(item -> isRunePouch(item.getId())).findFirst().orElse(null);
		if (pouch == null)
		{
			return;
		}
		bankedRunePouchId = pouch.getId();
		EnumMap<Runes, Integer> runes = new EnumMap<>(Runes.class);
		runes.putAll(Rs2RunePouch.getRunes());
		bankedRunePouchRunes = Collections.unmodifiableMap(runes);
		bankedAetherRunes = aetherPouchQuantity();
	}

	private boolean prepareCleanLoadout(int bankInput)
	{
		if (loadoutClean)
		{
			return true;
		}
		boolean restorePouch = pouchImprovesBatch(bankInput);
		if (restorePouch && Rs2Inventory.itemQuantity(bankedRunePouchId) > 0)
		{
			loadoutClean = true;
			return true;
		}
		Rs2ItemModel item = Rs2Inventory.all().stream()
			.filter(candidate -> !RUNE_ITEM_IDS.contains(candidate.getId()))
			.filter(candidate -> !isRunePouch(candidate.getId()))
			.findFirst().orElse(null);
		if (item != null)
		{
			depositItem(item);
			return false;
		}
		if (restorePouch)
		{
			withdraw(bankedRunePouchId, 1);
			return false;
		}
		bankedRunePouchId = -1;
		bankedRunePouchRunes = Collections.emptyMap();
		bankedAetherRunes = 0;
		loadoutClean = true;
		return true;
	}

	private boolean pouchImprovesBatch(int bankInput)
	{
		if (bankedRunePouchId < 0)
		{
			return false;
		}
		Map<Runes, Integer> equipmentRunes = Rs2Magic.getRunes(RuneFilter.builder()
			.includeInventory(false).includeRunePouch(false).build());
		Map<Runes, Integer> withoutPouch = EnchanterResources.effectiveRunes(
			equipmentRunes, 0, 0, equippedWeaponId());
		Map<Runes, Integer> withPouch = EnchanterResources.effectiveRunes(
			withStoredPouchRunes(equipmentRunes, bankedRunePouchRunes),
			0, bankedAetherRunes, equippedWeaponId());
		BatchPlan without = EnchanterResources.planBatch(target, 0, bankInput, 28,
			withoutPouch, ignored -> 0, Rs2Bank::count);
		BatchPlan with = EnchanterResources.planBatch(target, 0, bankInput, 27,
			withPouch, ignored -> 0, Rs2Bank::count);
		return with != null && (without == null
			|| with.getInputQuantity() > without.getInputQuantity());
	}

	static Map<Runes, Integer> withStoredPouchRunes(Map<Runes, Integer> available,
		Map<Runes, Integer> pouch)
	{
		EnumMap<Runes, Integer> combined = new EnumMap<>(Runes.class);
		combined.putAll(available);
		pouch.forEach((rune, quantity) ->
		{
			combined.merge(rune, quantity, EnchanterResources::safeAdd);
			for (Runes base : rune.getBaseRunes())
			{
				combined.merge(base, quantity, EnchanterResources::safeAdd);
			}
		});
		return combined;
	}

	private static boolean isRunePouch(int itemId)
	{
		return Arrays.stream(RunePouchType.getPouchIds()).anyMatch(id -> id == itemId);
	}

	private Map<Runes, Integer> effectiveRunes()
	{
		int aetherPouch = aetherPouchQuantity();
		if (aetherPouch < 0)
		{
			return null;
		}
		return EnchanterResources.effectiveRunes(Rs2Magic.getRunes(),
			Rs2Inventory.itemQuantity(ItemID.AETHERRUNE), aetherPouch, equippedWeaponId());
	}

	private int aetherPouchQuantity()
	{
		if (!Rs2Inventory.hasRunePouch())
		{
			return 0;
		}
		int total = 0;
		for (int index = 0; index < POUCH_RUNE_VARBITS.length; index++)
		{
			int rune = Microbot.getVarbitValue(POUCH_RUNE_VARBITS[index]);
			int amount = Microbot.getVarbitValue(POUCH_AMOUNT_VARBITS[index]);
			if (rune < 0 || amount < 0)
			{
				fail("Could not read the rune pouch's contents. Check the pouch, then restart.");
				return -1;
			}
			if (rune == EnchanterResources.AETHER_RUNE_POUCH_VALUE)
			{
				total = EnchanterResources.safeAdd(total, amount);
			}
		}
		return total;
	}

	private void depositItem(Rs2ItemModel item)
	{
		int itemId = item.getId();
		int epoch = Rs2Bank.getBankLiveEpoch();
		invalidateBankSnapshot();
		dispatchAction("deposit-loadout-item-" + itemId,
			"Depositing " + itemName(itemId),
			() -> Rs2Inventory.itemQuantity(itemId) == 0
				&& Rs2Bank.getBankLiveEpoch() > epoch,
			() -> Rs2Bank.depositAll(itemId));
	}

	private void withdraw(int itemId, int quantity)
	{
		Rs2ItemModel bankItem = Rs2Bank.bankItems().stream()
			.filter(item -> item.getId() == itemId).findFirst().orElse(null);
		if (bankItem == null || bankItem.getQuantity() < quantity)
		{
			fail(itemName(itemId) + " is no longer available in the bank. Add it or change your setup, then restart.");
			return;
		}
		int tab = Microbot.getClientThread().runOnClientThreadOptional(
			() -> Rs2Bank.getItemTabForBankItem(bankItem.getSlot())).orElse(-1);
		if (tab < 0)
		{
			fail("Could not locate " + itemName(itemId) + " in the bank interface. Close and reopen the bank, then restart.");
			return;
		}
		if (!Rs2Bank.isTabOpen(tab))
		{
			dispatchAction("bank-tab-" + tab, "Opening the required bank tab",
				() -> Rs2Bank.isTabOpen(tab), () -> Rs2Bank.openTab(tab));
			return;
		}
		int inventoryBefore = Rs2Inventory.itemQuantity(itemId);
		int bankBefore = Rs2Bank.count(itemId);
		int expectedInventory = EnchanterResources.safeAdd(inventoryBefore, quantity);
		int expectedBank = bankBefore - quantity;
		int epoch = Rs2Bank.getBankLiveEpoch();
		invalidateBankSnapshot();
		BooleanSupplier completion = () -> Rs2Inventory.itemQuantity(itemId) == expectedInventory
			&& Rs2Bank.count(itemId) == expectedBank
			&& Rs2Bank.getBankLiveEpoch() > epoch;
		int identifier = withdrawIdentifier(quantity, bankBefore);
		if (identifier >= 0)
		{
			dispatchAction("withdraw-" + itemId + '-' + quantity,
				"Withdrawing " + quantity + " " + itemName(itemId), completion,
				() -> invokeBankItem(bankItem, identifier));
		}
		else
		{
			dispatchAction("withdraw-" + itemId + '-' + quantity,
				"Withdrawing " + quantity + " " + itemName(itemId), completion,
				() -> invokeBankItem(bankItem, 6), quantity);
		}
	}

	private void selectSpell()
	{
		Microbot.status = "Enchanter: selecting spell";
		if (!target.isJewellery())
		{
			selectBoltProduct();
			return;
		}
		if (awaitAction())
		{
			return;
		}
		int selectedWidget = selectedWidgetId();
		if (selectedWidget != 0)
		{
			if (selectedWidget != target.getSpellWidgetId())
			{
				fail("A different spell or item is selected. Clear the selection, then restart.");
				return;
			}
			spellSelected = true;
			reselect = false;
			return;
		}
		if (widgetVisible(target.getSpellWidgetId()))
		{
			if (!"Cast".equalsIgnoreCase(widgetTargetVerb(target.getSpellWidgetId())))
			{
				fail("The selected spell could not be verified. Clear the selection and check your spellbook, then restart.");
				return;
			}
			dispatchAction("select-spell-" + target.getTier(), "Selecting the enchantment",
				this::exactSpellSelected,
				() -> invokeWidget(target.getSpellWidgetId(), "Cast", target.toString(),
					MenuAction.WIDGET_TARGET, 1));
			return;
		}
		int category = InterfaceID.MagicSpellbook.ENCHANT_JEWELLERY;
		if (widgetVisible(category))
		{
			if (!widgetHasAction(category, "View"))
			{
				fail("Could not select the jewellery enchantment. Check your spellbook and close open dialogues, then restart.");
				return;
			}
			dispatchAction("open-enchantments", "Opening Jewellery Enchantments",
				() -> widgetVisible(target.getSpellWidgetId()),
				() -> invokeWidget(category, "View", "Jewellery Enchantments",
					MenuAction.CC_OP, 1));
			return;
		}
		dispatchAction("open-magic-tab", "Opening the Magic tab",
			() -> widgetVisible(InterfaceID.MagicSpellbook.ENCHANT_JEWELLERY),
			() ->
			{
				Microbot.getClientThread().invoke(() ->
					Microbot.getClient().runScript(MAGIC_TAB_SCRIPT, MAGIC_TAB_INDEX));
				return true;
			});
	}

	private void selectBoltProduct()
	{
		if (awaitAction())
		{
			return;
		}
		if (boltResetRequired && widgetVisible(InterfaceID.Skillmulti.UNIVERSE))
		{
			dispatchAction("close-bolt-interface", "Closing the completed bolt interface",
				() -> !widgetVisible(InterfaceID.Skillmulti.UNIVERSE), () ->
				{
					Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
					return true;
				});
			return;
		}
		boltResetRequired = false;
		if (widgetVisible(InterfaceID.Skillmulti.UNIVERSE))
		{
			if (boltInterfaceSeenTick < 0)
			{
				boltInterfaceSeenTick = readyTick;
				return;
			}
			if (readyTick == boltInterfaceSeenTick)
			{
				return;
			}
			if (!"How many sets of bolts to enchant?".equals(
				widgetText(InterfaceID.Skillmulti.TITLE)))
			{
				fail("Could not recognise the bolt-enchantment window. Close it and restart. If this repeats, report the issue.");
				return;
			}
			if (widgetHasAction(InterfaceID.Skillmulti._1, "1"))
			{
				dispatchAction("select-one-bolt-set", "Selecting one bolt set",
					EnchanterScript::quantityOneSelected,
					() -> invokeWidget(InterfaceID.Skillmulti._1, "1", "",
						MenuAction.CC_OP, 1));
				return;
			}
			if (!quantityOneSelected())
			{
				fail("Could not select the required bolt-enchantment quantity. Close the enchantment window, then restart.");
				return;
			}
			int product = boltProductWidget(target.getOutputId());
			if (product < 0 || !widgetHasAction(product, "Make sets:"))
			{
				fail("Could not find " + itemName(target.getOutputId()) + " in the enchantment window. Check the selected product, then restart.");
				return;
			}
			boltProductWidgetId = product;
			spellSelected = true;
			reselect = false;
			return;
		}
		boltInterfaceSeenTick = -1;
		if (selectedWidgetId() != 0)
		{
			fail("A spell or item remained selected before bolt enchanting. Clear the selection, then restart.");
			return;
		}
		if (widgetVisible(InterfaceID.MagicSpellbook.XBOWS_ENCHANT))
		{
			dispatchAction("open-bolt-enchantments", "Opening bolt enchantments",
				() -> widgetVisible(InterfaceID.Skillmulti.UNIVERSE),
				() -> invokeWidget(InterfaceID.MagicSpellbook.XBOWS_ENCHANT,
					"Cast", "Enchant Crossbow Bolt", MenuAction.CC_OP, 1));
			return;
		}
		if (widgetVisible(InterfaceID.MagicSpellbook.BACK_BUTTON))
		{
			dispatchAction("back-to-magic", "Returning to the Magic spellbook",
				() -> widgetVisible(InterfaceID.MagicSpellbook.XBOWS_ENCHANT),
				() -> invokeWidget(InterfaceID.MagicSpellbook.BACK_BUTTON,
					"Back", "", MenuAction.CC_OP, 1));
			return;
		}
		dispatchAction("open-magic-tab", "Opening the Magic tab",
			() -> widgetVisible(InterfaceID.MagicSpellbook.XBOWS_ENCHANT)
				|| widgetVisible(InterfaceID.MagicSpellbook.BACK_BUTTON),
			() ->
			{
				Microbot.getClientThread().invoke(() ->
					Microbot.getClient().runScript(MAGIC_TAB_SCRIPT, MAGIC_TAB_INDEX));
				return true;
			});
	}

	private void dispatchEnchantment()
	{
		if (target.isJewellery())
		{
			dispatchJewellery();
		}
		else
		{
			dispatchBolts();
		}
	}

	private void dispatchJewellery()
	{
		Microbot.status = "Enchanter: enchanting";
		if (!exactSpellSelected())
		{
			fail("The selected enchantment changed before casting. Clear the selection, then restart.");
			return;
		}
		int input = Rs2Inventory.itemQuantity(target.getInputId());
		if (input <= 0 || !hasRunes(input))
		{
			fail("The prepared items or runes are no longer available. Check your inventory, then restart.");
			return;
		}
		Rs2ItemModel item = Rs2Inventory.get(target.getInputId());
		if (item == null)
		{
			fail("Could not select " + itemName(target.getInputId()) + " in your inventory. Close open interfaces and check that the item is present, then restart.");
			return;
		}
		acceptedInventory = inventoryCounts();
		acceptedInventorySequence = latestInventory.sequence;
		lastEffectTick = readyTick;
		Rectangle bounds = Microbot.getClientThread().runOnClientThreadOptional(
			() -> Rs2Inventory.itemBounds(item)).orElse(null);
		Microbot.doInvoke(new NewMenuEntry()
			.option("Cast")
			.target(item.getName())
			.identifier(-1)
			.type(MenuAction.WIDGET_TARGET_ON_WIDGET)
			.param0(item.getSlot())
			.param1(InterfaceID.Inventory.ITEMS)
			.itemId(target.getInputId()), bounds == null ? new Rectangle(1, 1) : bounds);
		dispatched = true;
	}

	private void dispatchBolts()
	{
		Microbot.status = "Enchanter: enchanting bolts";
		int product = boltProductWidget(target.getOutputId());
		if (!widgetVisible(InterfaceID.Skillmulti.UNIVERSE)
			|| !quantityOneSelected() || product != boltProductWidgetId)
		{
			fail("The selected bolt product changed before enchanting. Close the enchantment window, then restart.");
			return;
		}
		int input = Rs2Inventory.itemQuantity(target.getInputId());
		if (input <= 0 || !hasRunes(1))
		{
			fail("The prepared bolts or runes are no longer available. Check your inventory, then restart.");
			return;
		}
		expectedBoltBatch = Math.min(10, input);
		acceptedInventory = inventoryCounts();
		acceptedInventorySequence = latestInventory.sequence;
		lastEffectTick = readyTick;
		invokeWidget(boltProductWidgetId, "Make sets:", target.toString(),
			MenuAction.CC_OP, 1);
		dispatched = true;
	}

	private void waitForEffect()
	{
		Microbot.status = "Enchanter: waiting for effect";
		InventoryEvidence evidence = latestInventory;
		boolean effectAccepted = false;
		if (evidence.sequence > acceptedInventorySequence && readyTick > evidence.tick)
		{
			Map<Integer, Integer> current = inventoryCounts();
			if (!current.equals(evidence.counts))
			{
				fail("Could not reliably read your inventory after casting. Check the items before restarting.");
				return;
			}
			if (!current.equals(acceptedInventory))
			{
				String mismatch = target.isJewellery()
					? effectMismatch(target, acceptedInventory, current, 1, EnchanterScript::itemName)
					: effectMismatch(target, acceptedInventory, current, expectedBoltBatch, EnchanterScript::itemName);
				if (mismatch != null)
				{
					fail(mismatch);
					return;
				}
				completedEffects++;
				acceptedInventory = current;
				lastEffectTick = readyTick;
				effectReselections = 0;
				effectAccepted = true;
			}
			acceptedInventorySequence = evidence.sequence;
		}
		if (acceptedInventory != null
			&& quantity(acceptedInventory, target.getInputId()) == 0)
		{
			resetForBankReturn();
			return;
		}
		if (effectAccepted && !target.isJewellery())
		{
			requestReselection();
			return;
		}
		int timeout = target.isJewellery() ? EFFECT_TIMEOUT_TICKS : NO_PROGRESS_TICKS;
		if (readyTick - lastEffectTick < timeout)
		{
			return;
		}
		if (effectReselections >= MAX_EFFECT_RESELECTIONS)
		{
			fail("No enchantment was confirmed after repeated attempts. Check your runes, Magic level, and spellbook, then restart.");
			return;
		}
		effectReselections++;
		requestReselection();
	}

	private void requestReselection()
	{
		spellSelected = false;
		dispatched = false;
		reselect = true;
		boltInterfaceSeenTick = -1;
		boltProductWidgetId = -1;
		boltResetRequired = !target.isJewellery();
		lastEffectTick = readyTick;
	}

	static String effectMismatch(Enchantment target, Map<Integer, Integer> before,
		Map<Integer, Integer> after, int expectedInputDelta, java.util.function.IntFunction<String> itemName)
	{
		int inputDelta = quantity(before, target.getInputId())
			- quantity(after, target.getInputId());
		int outputDelta = quantity(after, target.getOutputId())
			- quantity(before, target.getOutputId());
		if (inputDelta != expectedInputDelta || outputDelta != expectedInputDelta)
		{
			log.debug("Mismatched effect: input {}, output {}, expected {}", inputDelta, outputDelta, expectedInputDelta);
			return "The item changes did not match the expected enchantment. Check your inventory before restarting. If this repeats, report the debug log.";
		}
		Set<Integer> ids = new HashSet<>(before.keySet());
		ids.addAll(after.keySet());
		for (int itemId : ids)
		{
			if (itemId == target.getInputId() || itemId == target.getOutputId())
			{
				continue;
			}
			int oldQuantity = quantity(before, itemId);
			int newQuantity = quantity(after, itemId);
			if (oldQuantity != newQuantity
				&& (!RUNE_ITEM_IDS.contains(itemId) || newQuantity > oldQuantity))
			{
				log.debug("Unrelated inventory item {} changed from {} to {}", itemId, oldQuantity, newQuantity);
				return itemName.apply(itemId) + " changed unexpectedly during enchanting. Check your inventory before restarting.";
			}
		}
		return null;
	}

	private void resetForBankReturn()
	{
		bankReady = false;
		batchReady = false;
		spellSelected = false;
		dispatched = false;
		reselect = false;
		batchFinished = true;
		closingForBatch = false;
		inventoryBanked = false;
		loadoutClean = false;
		batch = null;
		acceptedInventory = null;
		boltInterfaceSeenTick = -1;
		boltProductWidgetId = -1;
		expectedBoltBatch = 0;
		boltResetRequired = false;
		invalidateBankSnapshot();
	}

	private boolean hasRunes(int effects)
	{
		Map<Runes, Integer> available = effectiveRunes();
		if (available == null)
		{
			return false;
		}
		return target.runesForEffects(effects).entrySet().stream()
			.allMatch(entry -> available.getOrDefault(entry.getKey(), 0) >= entry.getValue());
	}

	private boolean stableBankSnapshot()
	{
		int epoch = Rs2Bank.getBankLiveEpoch();
		if (epoch <= 0)
		{
			return false;
		}
		long signature = bankSignature();
		if (epoch == stableBankEpoch && signature == stableBankSignature)
		{
			stableBankTicks++;
		}
		else
		{
			stableBankEpoch = epoch;
			stableBankSignature = signature;
			stableBankTicks = 1;
		}
		return stableBankTicks >= 2;
	}

	private long bankSignature()
	{
		long signature = 1;
		for (Rs2ItemModel item : Rs2Bank.bankItems())
		{
			signature = 31 * signature + item.getSlot();
			signature = 31 * signature + item.getId();
			signature = 31 * signature + item.getQuantity();
		}
		return signature;
	}

	private void invalidateBankSnapshot()
	{
		stableBankEpoch = -1;
		stableBankSignature = 0;
		stableBankTicks = 0;
	}

	private boolean awaitAction()
	{
		if (pendingAction == null)
		{
			return false;
		}
		try
		{
			if (pendingAction.complete.getAsBoolean())
			{
				pendingAction = null;
				lastActionKey = null;
				actionAttempts = 0;
				return true;
			}
			if (pendingAction.amount > 0 && !pendingAction.amountEntered
				&& amountPromptVisible())
			{
				Rs2GrandExchange.setChatboxValue(pendingAction.amount);
				Rs2Keyboard.enter();
				pendingAction.amountEntered = true;
				pendingAction.startedAt = readyTick;
				return true;
			}
		}
		catch (RuntimeException exception)
		{
			log.warn("Action verification failed: {}", pendingAction.description, exception);
			fail("Could not confirm the action: " + pendingAction.description + ". Check the game before restarting. If this repeats, report the debug log.");
			return true;
		}
		if (readyTick - pendingAction.startedAt < NO_PROGRESS_TICKS)
		{
			return true;
		}
		String description = pendingAction.description;
		pendingAction = null;
		if (actionAttempts >= MAX_ACTION_ATTEMPTS)
		{
			fail("Could not complete the action after repeated attempts: " + description + ". Check for an open dialogue or missing supplies, then restart.");
		}
		return true;
	}

	private void dispatchAction(String key, String description,
		BooleanSupplier completion, BooleanSupplier intent)
	{
		dispatchAction(key, description, completion, intent, 0);
	}

	private void dispatchAction(String key, String description,
		BooleanSupplier completion, BooleanSupplier intent, int amount)
	{
		if (!key.equals(lastActionKey))
		{
			lastActionKey = key;
			actionAttempts = 0;
		}
		actionAttempts++;
		boolean issued;
		try
		{
			issued = intent.getAsBoolean();
		}
		catch (RuntimeException exception)
		{
			log.warn("Action failed: {}", description, exception);
			fail("An unexpected error occurred during the action: " + description + ". Restart the plugin. If this repeats, report the debug log.");
			return;
		}
		pendingAction = new PendingAction(description, readyTick, completion, amount);
		if (!issued && actionAttempts >= MAX_ACTION_ATTEMPTS)
		{
			fail("Could not start the action after repeated attempts: " + description + ". Close open dialogues and check your setup, then restart.");
		}
	}

	private int withdrawIdentifier(int quantity, int bankQuantity)
	{
		int selected = Microbot.getVarbitValue(VarbitID.BANK_QUANTITY_TYPE);
		if (quantity == 1)
		{
			return selected == 0 ? 1 : 2;
		}
		if (quantity == 5)
		{
			return selected == 1 ? 1 : 3;
		}
		if (quantity == 10)
		{
			return selected == 2 ? 1 : 4;
		}
		if (quantity == bankQuantity)
		{
			return selected == 4 ? 1 : 7;
		}
		int configured = Microbot.getVarbitValue(VarbitID.BANK_REQUESTEDQUANTITY);
		if (configured == quantity && configured > 0)
		{
			return selected == 3 ? 1 : 5;
		}
		return -1;
	}

	private static boolean invokeBankItem(Rs2ItemModel item, int identifier)
	{
		Rectangle bounds = Microbot.getClientThread().runOnClientThreadOptional(
			() -> Rs2Bank.getItemBounds(item.getSlot())).orElse(null);
		Microbot.doInvoke(new NewMenuEntry()
			.option("Withdraw")
			.target(item.getName())
			.identifier(identifier)
			.type(MenuAction.CC_OP)
			.param0(item.getSlot())
			.param1(Rs2Bank.BANK_ITEM_CONTAINER)
			.itemId(item.getId()), bounds == null ? new Rectangle(1, 1) : bounds);
		return true;
	}

	private static boolean amountPromptVisible()
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			Widget prompt = Microbot.getClient().getWidget(162, 43);
			return prompt != null && "Enter amount:".equalsIgnoreCase(prompt.getText());
		}).orElse(false);
	}

	private Set<Runes> equippedTomeRunes()
	{
		Rs2ItemModel shield = Rs2Equipment.get(EquipmentInventorySlot.SHIELD);
		if (shield == null)
		{
			return Collections.emptySet();
		}
		Rs2Tome tome = Rs2Magic.getRs2Tome(shield.getId());
		return tome == Rs2Tome.NONE ? Collections.emptySet() : new HashSet<>(tome.getRunes());
	}

	private int equippedWeaponId()
	{
		Rs2ItemModel weapon = Rs2Equipment.get(EquipmentInventorySlot.WEAPON);
		return weapon == null ? -1 : weapon.getId();
	}

	private boolean exactSpellSelected()
	{
		return selectedWidgetId() == target.getSpellWidgetId();
	}

	private boolean localBankLoaded()
	{
		return Rs2GameObject.findBank() != null
			|| Rs2GameObject.findGrandExchangeBooth() != null
			|| Rs2Npc.getBankerNPC() != null;
	}

	private boolean isMembersWorld()
	{
		return Microbot.getClient().getWorldType().contains(WorldType.MEMBERS);
	}

	private static boolean invokeWidget(int widgetId, String option, String target,
		MenuAction action, int identifier)
	{
		Rectangle bounds = Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			Widget widget = Microbot.getClient().getWidget(widgetId);
			return widget == null ? null : widget.getBounds();
		}).orElse(null);
		Microbot.doInvoke(new NewMenuEntry()
			.option(option)
			.target(target)
			.identifier(identifier)
			.type(action)
			.param0(-1)
			.param1(widgetId)
			.itemId(-1), bounds == null ? new Rectangle(1, 1) : bounds);
		return true;
	}

	private static boolean widgetVisible(int widgetId)
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			Widget widget = Microbot.getClient().getWidget(widgetId);
			return widget != null && !widget.isHidden();
		}).orElse(false);
	}

	private static boolean widgetHasAction(int widgetId, String expected)
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			Widget widget = Microbot.getClient().getWidget(widgetId);
			return hasAction(widget, expected);
		}).orElse(false);
	}

	private static boolean hasAction(Widget widget, String expected)
	{
		return widget != null && widget.getActions() != null
			&& Arrays.stream(widget.getActions()).anyMatch(action -> expected.equalsIgnoreCase(action));
	}

	private static String widgetTargetVerb(int widgetId)
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			Widget widget = Microbot.getClient().getWidget(widgetId);
			return widget == null ? null : widget.getTargetVerb();
		}).orElse(null);
	}

	private static String widgetText(int widgetId)
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			Widget widget = Microbot.getClient().getWidget(widgetId);
			return widget == null ? null : widget.getText();
		}).orElse(null);
	}

	private static boolean quantityOneSelected()
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			Widget one = Microbot.getClient().getWidget(InterfaceID.Skillmulti._1);
			Widget five = Microbot.getClient().getWidget(InterfaceID.Skillmulti._5);
			Widget ten = Microbot.getClient().getWidget(InterfaceID.Skillmulti._10);
			return one != null && !one.isHidden() && !hasAction(one, "1")
				&& five != null && hasAction(five, "5")
				&& ten != null && hasAction(ten, "10");
		}).orElse(false);
	}

	private static int boltProductWidget(int outputId)
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			for (int widgetId : BOLT_OPTION_WIDGETS)
			{
				Widget widget = Microbot.getClient().getWidget(widgetId);
				if (widget != null && !widget.isHidden() && containsItem(widget, outputId))
				{
					return widget.getId();
				}
			}
			return -1;
		}).orElse(-1);
	}

	private static boolean containsItem(Widget widget, int itemId)
	{
		if (widget == null)
		{
			return false;
		}
		if (widget.getItemId() == itemId)
		{
			return true;
		}
		return containsItem(widget.getStaticChildren(), itemId)
			|| containsItem(widget.getDynamicChildren(), itemId)
			|| containsItem(widget.getNestedChildren(), itemId);
	}

	private static boolean containsItem(Widget[] children, int itemId)
	{
		return children != null && Arrays.stream(children).anyMatch(child -> containsItem(child, itemId));
	}

	private static int selectedWidgetId()
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			if (!Microbot.getClient().isWidgetSelected())
			{
				return 0;
			}
			Widget selected = Microbot.getClient().getSelectedWidget();
			return selected == null ? -1 : selected.getId();
		}).orElse(-1);
	}

	private static Set<Integer> runeItemIds()
	{
		Set<Integer> ids = new HashSet<>();
		for (Runes rune : Runes.values())
		{
			ids.add(rune.getItemId());
		}
		ids.add(ItemID.AETHERRUNE);
		return Collections.unmodifiableSet(ids);
	}

	private static Map<Integer, Integer> inventoryCounts()
	{
		Map<Integer, Integer> counts = new TreeMap<>();
		Rs2Inventory.all().forEach(item ->
			counts.merge(item.getId(), item.getQuantity(), EnchanterResources::safeAdd));
		return Collections.unmodifiableMap(counts);
	}

	private static Map<Integer, Integer> counts(ItemContainer container)
	{
		Map<Integer, Integer> counts = new TreeMap<>();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
				{
					counts.merge(item.getId(), item.getQuantity(), EnchanterResources::safeAdd);
				}
			}
		}
		return Collections.unmodifiableMap(counts);
	}

	private static int quantity(Map<Integer, Integer> counts, int itemId)
	{
		return counts.getOrDefault(itemId, 0);
	}

	static boolean worldSupported(EnumSet<WorldType> worldTypes)
	{
		return worldTypes != null && worldTypes.stream().noneMatch(UNSUPPORTED_WORLD_TYPES::contains);
	}

	private static String itemName(int itemId)
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() -> {
			net.runelite.api.ItemComposition item = Microbot.getClient().getItemDefinition(itemId);
			String name = item == null ? null : item.getName();
			return name == null || name.isBlank() || "null".equals(name) ? "the required item" : name;
		}).orElse("the required item");
	}

	private synchronized void fail(String message)
	{
		if (error != null) return;
		log.debug("Stopped in {}: {}", getStateName(), message);
		error = message == null || message.isBlank() ? "The plugin stopped without a detailed reason. Report the debug log." : message;
		String notice = "Error: Enchanter: " + net.runelite.client.util.Text.removeTags(error);
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

	boolean isComplete()
	{
		return complete;
	}

	Enchantment getTarget()
	{
		return target;
	}

	private static final class PendingAction
	{
		private final String description;
		private int startedAt;
		private final BooleanSupplier complete;
		private final int amount;
		private boolean amountEntered;

		private PendingAction(String description, int startedAt,
			BooleanSupplier complete, int amount)
		{
			this.description = description;
			this.startedAt = startedAt;
			this.complete = complete;
			this.amount = amount;
		}
	}

	private static final class InventoryEvidence
	{
		private final long sequence;
		private final int tick;
		private final Map<Integer, Integer> counts;

		private InventoryEvidence(long sequence, int tick, Map<Integer, Integer> counts)
		{
			this.sequence = sequence;
			this.tick = tick;
			this.counts = counts;
		}

		private static InventoryEvidence empty()
		{
			return new InventoryEvidence(0, -1, Collections.emptyMap());
		}
	}

	private static final class WalkerBridge
	{
		private final Plugin plugin;
		private final PluginManager pluginManager;
		private final Object walker;
		private final Method walkToNearestBank;
		private final Method getStatus;
		private final Method getPlanningFailure;
		private final Method cancel;

		private WalkerBridge(Plugin plugin, PluginManager pluginManager, Object walker, Class<?> type)
			throws NoSuchMethodException
		{
			this.plugin = plugin;
			this.pluginManager = pluginManager;
			this.walker = walker;
			walkToNearestBank = type.getMethod("walkToNearestBank");
			getStatus = type.getMethod("getStatus");
			getPlanningFailure = type.getMethod("getPlanningFailure");
			cancel = type.getMethod("cancel");
		}

		static WalkerBridge bind(PluginManager pluginManager) throws ReflectiveOperationException
		{
			List<Plugin> matches = pluginManager.getPlugins().stream()
				.filter(plugin -> WALKER_PLUGIN_CLASS.equals(plugin.getClass().getName()))
				.collect(Collectors.toList());
			if (matches.size() != 1 || !pluginManager.isActive(matches.get(0)))
			{
				throw new IllegalStateException(
					"Enable exactly one Efficient Walker instance, then restart Enchanter.");
			}
			Plugin plugin = matches.get(0);
			Object walker = plugin.getClass().getMethod("getWalker").invoke(plugin);
			return new WalkerBridge(plugin, pluginManager, walker, walker.getClass());
		}

		boolean isAvailable()
		{
			long matches = pluginManager.getPlugins().stream()
				.filter(candidate -> WALKER_PLUGIN_CLASS.equals(candidate.getClass().getName()))
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

		boolean walkToNearestBank() throws ReflectiveOperationException
		{
			return Boolean.TRUE.equals(walkToNearestBank.invoke(walker));
		}

		String status() throws ReflectiveOperationException
		{
			Object value = getStatus.invoke(walker);
			return value instanceof Enum ? ((Enum<?>) value).name() : null;
		}

		String planningFailure() throws ReflectiveOperationException
		{
			Object value = getPlanningFailure.invoke(walker);
			return value instanceof Enum ? ((Enum<?>) value).name() : "UNKNOWN";
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
	}
}
