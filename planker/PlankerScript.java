package net.runelite.client.plugins.microbot.planker;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientInt;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.planker.PlankerConfig.PriceDirection;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PlankerScript
{
	private static final Logger log = LoggerFactory.getLogger(PlankerScript.class);
	static final WorldPoint BANK = new WorldPoint(1413, 3353, 0);
	static final WorldPoint SAWMILL = new WorldPoint(1395, 3368, 0);
	static final WorldPoint EXCHANGE = new WorldPoint(3161, 3490, 0);
	private static final int[] CLERKS = {2148, 2149, 2150, 2151};
	private static final int[] BASKETS = {ItemID.LOG_BASKET_CLOSED, ItemID.LOG_BASKET_OPEN,
		ItemID.FORESTRY_BASKET_CLOSED, ItemID.FORESTRY_BASKET_OPEN};
	private static final int[] MODIFIERS = {ItemID.PLANK_SACK, ItemID.PLANK_SACK_DUMMY,
		ItemID.LOG_BASKET_CLOSED, ItemID.LOG_BASKET_OPEN, ItemID.FORESTRY_BASKET_CLOSED,
		ItemID.FORESTRY_BASKET_OPEN, ItemID.FORESTRY_SAWMILL_VOUCHER, ItemID.SAWMILL_COUPON,
		ItemID.SAWMILL_COUPON_OAK, ItemID.SAWMILL_COUPON_TEAK, ItemID.SAWMILL_COUPON_MAHOGANY,
		ItemID.SAWMILL_COUPON_CAMPHOR, ItemID.SAWMILL_COUPON_IRONWOOD};
	private static final int WAIT_TICKS = 6;
	private static final int SERVICE_RANGE = 6;
	private static final int[] STAMINA = {ItemID._1DOSESTAMINA, ItemID._2DOSESTAMINA,
		ItemID._3DOSESTAMINA, ItemID._4DOSESTAMINA};
	private static final int[] RINGS_OF_WEALTH = {ItemID.RING_OF_WEALTH, ItemID.RING_OF_WEALTH_1,
		ItemID.RING_OF_WEALTH_2, ItemID.RING_OF_WEALTH_3, ItemID.RING_OF_WEALTH_4, ItemID.RING_OF_WEALTH_5,
		ItemID.RING_OF_WEALTH_I, ItemID.RING_OF_WEALTH_I1, ItemID.RING_OF_WEALTH_I2,
		ItemID.RING_OF_WEALTH_I3, ItemID.RING_OF_WEALTH_I4, ItemID.RING_OF_WEALTH_I5};
	private static final int MAX_ATTEMPTS = 3;
	private static final int READY_SAMPLES = 3;
	private final PluginManager pluginManager;
	private final Client client;
	private volatile Run run;
	private volatile long bankEpoch;

	@Inject
	PlankerScript(PluginManager pluginManager, Client client)
	{
		this.pluginManager = pluginManager;
		this.client = client;
	}

	enum State { BANK, DRINK, TRAVEL, CONVERT, EXCHANGE, WAITING, STOPPED }

	enum Action { OPEN_BANK, CLOSE_BANK, NOTE, UNNOTE, DEPOSIT, BANK_VIEW, BANK_SCROLL, WITHDRAW, WITHDRAW_NOTED, INVENTORY, DRINK, OPEN_MAKE, CONVERT, OPEN_EXCHANGE, CLOSE_EXCHANGE }

	void start(PlankerConfig config)
	{
		shutdown();
		Run next = new Run();
		run = next;
		try
		{
			next.settings = Settings.from(config);
			next.walker = PlankerWalker.bind(pluginManager);
			next.running = true;
			next.sessionReady = true;
			next.accountProfile = Microbot.getConfigManager().getRSProfileKey();
			next.state = State.BANK;
			next.status = "Preparing at Auburnvale bank";
			next.latest = Microbot.getClientThread().runOnClientThreadOptional(() -> snapshot(next)).orElse(null);
			if (next.latest == null || !next.latest.ready)
			{
				fail(next, "Log in and enter gameplay, then restart Planker.");
				return;
			}
			if (next.accountProfile == null) next.accountProfile = Microbot.getConfigManager().getRSProfileKey();
			if (!next.latest.access)
			{
				fail(next, "Complete Children of the Sun on a members account, then restart Planker.");
				return;
			}
			String existingOffer = Microbot.getClientThread().runOnClientThreadOptional(() ->
				existingOfferError(next.settings.type, client.getGrandExchangeOffers())).orElse(
				"Grand Exchange offers are unavailable. Wait for gameplay to load, then restart Planker.");
			if (!existingOffer.isEmpty())
			{
				fail(next, existingOffer);
				return;
			}
			next.executor = Executors.newSingleThreadScheduledExecutor(task -> {
				Thread thread = new Thread(task, "Planker");
				thread.setDaemon(true);
				return thread;
			});
			next.executor.scheduleWithFixedDelay(() -> cycle(next), 0, 50, TimeUnit.MILLISECONDS);
		}
		catch (Exception exception)
		{
			fail(next, exception.getMessage());
		}
	}

	synchronized void shutdown()
	{
		Run current = run;
		if (current == null || current.stopped) return;
		if (current.stoppedAtNanos == 0) current.stoppedAtNanos = System.nanoTime();
		current.stopped = true;
		current.running = false;
		current.state = State.STOPPED;
		current.pending = null;
		if (current.error == null)
		{
			String offer = pendingOfferNotice(current);
			current.status = offer.isEmpty() ? "Stopped" : "Stopped. " + offer;
			if (!offer.isEmpty())
				Microbot.getClientThread().invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Planker: " + offer, null));
		}
		if (current.executor != null) current.executor.shutdownNow();
		try
		{
			cancelOwnedRoute(current);
		}
		catch (RuntimeException exception)
		{
			if (current.error == null)
			{
				current.error = "Could not cancel Planker's route. Stop Efficient Walker manually before restarting Planker.";
				current.status = "Error: " + current.error;
				chatError(current);
			}
		}
	}

	void onGameTick()
	{
		Run current = run;
		if (current == null || !current.running) return;
		try
		{
			if (current.sessionReady && current.world > 0 && client.getWorld() != current.world)
				suspendForReconnect(current);
			current.latest = snapshot(current);
			if (!current.sessionReady)
			{
				if (!current.latest.ready || client.getWorld() <= 0)
				{
					current.readySamples = 0;
					return;
				}
				String accountProfile = Microbot.getConfigManager().getRSProfileKey();
				if (current.accountProfile != null && accountProfile != null && !current.accountProfile.equals(accountProfile))
				{
					fail(current, "A different account logged in. Restart Planker for this account.");
					return;
				}
				if (current.reconnectWorld != client.getWorld())
				{
					current.reconnectWorld = client.getWorld();
					current.readySamples = 0;
				}
				if (++current.readySamples < READY_SAMPLES)
				{
					current.status = "Waiting for stable gameplay after login (" + current.readySamples + "/" + READY_SAMPLES + ")";
					return;
				}
				current.world = client.getWorld();
				if (current.accountProfile == null) current.accountProfile = accountProfile;
				current.sessionReady = true;
				current.readySamples = 0;
				current.processedTick = -1;
				current.lastActionTick = -1;
				current.bankFresh = false;
				current.bankReady = false;
				current.state = current.resumeState == null ? State.BANK : current.resumeState;
				current.resumeState = null;
				current.status = "Resuming after login";
			}
		}
		catch (IllegalStateException exception)
		{
			if (!current.sessionReady) return;
			fail(current, exception.getMessage());
		}
		catch (Exception exception)
		{
			if (!current.sessionReady) return;
			fail(current, "Could not read the current game state. Restart Planker after gameplay settles.");
		}
	}

	void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK.getId()) bankEpoch++;
	}

	void onGameStateChanged(GameStateChanged event)
	{
		Run current = run;
		if (current == null || !current.running) return;
		current.gameState = event.getGameState();
		if (current.gameState != GameState.LOGGED_IN)
		{
			suspendForReconnect(current);
		}
		else if (!current.sessionReady)
		{
			current.status = "Waiting for stable gameplay after login";
		}
	}

	private void suspendForReconnect(Run current)
	{
		if (current.sessionReady)
		{
			current.sessionReady = false;
			current.readySamples = 0;
			current.reconnectWorld = 0;
			if (current.state != State.WAITING)
			{
				current.resumeState = current.state == State.TRAVEL && current.afterTravel != null
					? current.afterTravel : current.state == State.TRAVEL ? State.BANK : current.state;
				current.state = State.WAITING;
			}
			current.pending = null;
			current.latest = null;
			current.processedTick = -1;
			current.lastActionTick = -1;
			current.bankFresh = false;
			current.bankReady = false;
			if (current.exchange != null) current.exchange.resetAfterReconnect();
			try
			{
				cancelOwnedRoute(current);
			}
			catch (RuntimeException exception)
			{
				fail(current, "Could not cancel Planker's route while waiting for login. Stop Efficient Walker manually, then restart Planker.");
				return;
			}
		}
		current.status = "Waiting for login";
	}

	private void cycle(Run current)
	{
		if (run != current || !current.running) return;
		try
		{
			if (!current.sessionReady || current.gameState != GameState.LOGGED_IN) return;
			if (!current.walker.isAvailable())
			{
				fail(current, "Efficient Walker became unavailable. Enable exactly one compatible instance, then restart Planker.");
				return;
			}
			if (!guardRoute(current)) return;
			Snapshot state = current.latest;
			if (Microbot.pauseAllScripts.get())
			{
				if (current.destination != null)
				{
					cancelOwnedRoute(current);
					current.state = current.afterTravel;
					current.bankReady = false;
				}
				current.status = "Paused";
				return;
			}
			if (state == null || state.tick == current.processedTick || current.gameState == GameState.LOADING
				|| Microbot.pauseAllScripts.get()) return;
			current.processedTick = state.tick;
			if (!state.ready || !state.access)
			{
				fail(current, "Gameplay with Auburnvale access is required. Check the account, then restart Planker.");
				return;
			}
			if (state.inventory == null || state.equipment == null)
			{
				fail(current, "Inventory or equipment is unavailable. Wait for gameplay to load, then restart Planker.");
				return;
			}
			int equippedBasket = firstPresent(state.equipment, BASKETS);
			if (equippedBasket != -1)
			{
				fail(current, "Unequip and bank your log basket or Forestry basket, then restart Planker.");
				return;
			}
			if (!state.bankOpen) current.bankFresh = false;
			if (current.pending != null && awaitPending(current, state)) return;
			switch (current.state)
			{
				case BANK: bank(current, state); break;
				case DRINK: drink(current, state); break;
				case TRAVEL:
					Microbot.getClientThread().runOnClientThreadOptional(() -> {
						try
						{
							travel(current, state);
							return true;
						}
						catch (RuntimeException exception)
						{
							fail(current, exception.getMessage());
							return false;
						}
					});
					break;
				case CONVERT: convert(current, state); break;
				case EXCHANGE: exchange(current, state); break;
				default: break;
			}
		}
		catch (Exception exception)
		{
			fail(current, exception.getMessage());
		}
	}

	private void bank(Run current, Snapshot state)
	{
		if (current.bankReady)
		{
			if (state.bankOpen) issue(current, state, Action.CLOSE_BANK, -1, 0);
			else beginTravel(current, state, SAWMILL, State.CONVERT);
			return;
		}
		if (!state.bankOpen && !state.interactions.contains(Action.OPEN_BANK) && state.position.distanceTo(BANK) > 2)
		{
			beginTravel(current, state, BANK, State.BANK);
			return;
		}
		if (!state.bankOpen)
		{
			issue(current, state, Action.OPEN_BANK, -1, 0);
			return;
		}
		if (!current.bankFresh || state.bank == null)
		{
			issue(current, state, Action.CLOSE_BANK, -1, 0);
			return;
		}
		PlankType type = current.settings.type;
		current.banked = state.bank.getOrDefault(type.getPlankId(), 0);
		int deposit = firstPresent(state.inventory, MODIFIERS);
		if (deposit == -1 && state.count(state.notedLogId) > 0) deposit = state.notedLogId;
		if (deposit == -1 && state.count(state.notedPlankId) > 0) deposit = state.notedPlankId;
		if (deposit == -1 && state.count(type.getPlankId()) > 0) deposit = type.getPlankId();
		if (deposit == -1 && state.count(ItemID.VIAL_EMPTY) > 0) deposit = ItemID.VIAL_EMPTY;
		if (deposit != -1)
		{
			issue(current, state, Action.DEPOSIT, deposit, state.count(deposit));
			return;
		}
		if (current.settings.threshold > 0 && remainingLogs(state, type) == 0 && remainingPlanks(state, type) > 0)
		{
			beginExchange(current, state);
			return;
		}
		int ring = firstPresent(state.inventory, RINGS_OF_WEALTH);
		if (ring != -1 && (state.count(type.getLogId()) > 0
			|| state.bank.getOrDefault(type.getLogId(), 0) > 0 || current.settings.batch == 0))
		{
			issue(current, state, Action.DEPOSIT, ring, state.count(ring));
			return;
		}
		if (state.noted)
		{
			issue(current, state, Action.UNNOTE, -1, 0);
			return;
		}
		int bankCoins = state.bank.getOrDefault(ItemID.COINS, 0);
		if (bankCoins > 0 && state.coins() < Integer.MAX_VALUE && (state.freeSlots > 0 || state.coins() > 0))
		{
			issue(current, state, Action.WITHDRAW, ItemID.COINS,
				Math.min(bankCoins, Integer.MAX_VALUE - state.coins()));
			return;
		}
		int logs = state.count(type.getLogId());
		int bankLogs = state.bank.getOrDefault(type.getLogId(), 0);
		int totalLogs = (int) Math.min(Integer.MAX_VALUE, (long) logs + bankLogs);
		boolean needsStamina = current.settings.useStamina && totalLogs > 0 && state.coins() >= type.getFee()
			&& shouldDrinkStamina(state.energy, state.staminaActive);
		int potion = firstPresent(state.inventory, STAMINA);
		current.staminaUnavailable = false;
		if (needsStamina && potion != -1)
		{
			current.staminaItem = potion;
			current.state = State.DRINK;
			return;
		}
		if (potion != -1)
		{
			issue(current, state, Action.DEPOSIT, potion, state.count(potion));
			return;
		}
		if (needsStamina)
		{
			potion = firstPresent(state.bank, STAMINA);
			if (potion != -1 && state.freeSlots > 0)
			{
				issue(current, state, Action.WITHDRAW, potion, 1);
				return;
			}
			current.staminaUnavailable = true;
		}
		int desired = PlankType.loadSize(state.freeSlots + logs, totalLogs, state.coins(), type.getFee());
		if (desired == 0)
		{
			if (logs == 0 && bankLogs == 0 && current.settings.batch > 0)
			{
				beginExchange(current, state);
				return;
			}
			if (totalLogs == 0)
				fail(current, "Add at least 1 " + type.getLogName() + " to the bank, then restart Planker.");
			else if ((long) state.coins() + bankCoins < type.getFee())
				fail(current, "Add " + (type.getFee() - (long) state.coins() - bankCoins)
					+ " coins to the bank to convert a log, then restart Planker.");
			else fail(current, "Free an inventory slot for coins and at least one log, then restart Planker.");
			return;
		}
		if (logs > desired)
		{
			issue(current, state, Action.DEPOSIT, type.getLogId(), logs);
			return;
		}
		if (logs < desired)
		{
			int available = Math.min(state.freeSlots, bankLogs);
			issue(current, state, Action.WITHDRAW, type.getLogId(), available == desired - logs ? available : 1);
			return;
		}
		current.bankReady = true;
		current.status = "Prepared " + logs + " " + type.getLogName();
	}

	private void drink(Run current, Snapshot state)
	{
		if (!shouldDrinkStamina(state.energy, state.staminaActive)) current.state = State.BANK;
		else if (state.bankOpen) issue(current, state, Action.CLOSE_BANK, -1, 0);
		else if (!Microbot.getClientThread().runOnClientThreadOptional(() ->
			visible(client.getWidget(InterfaceID.Inventory.ITEMS))).orElse(false))
			issue(current, state, Action.INVENTORY, -1, 0);
		else issue(current, state, Action.DRINK, current.staminaItem, 1);
	}

	static boolean shouldDrinkStamina(int energy, boolean active)
	{
		return energy >= 0 && energy <= 8000 && !active;
	}

	static boolean verifiedStamina(int itemId, Snapshot before, Snapshot after)
	{
		if (!after.staminaActive) return false;
		int index = -1;
		for (int i = 0; i < STAMINA.length; i++) if (STAMINA[i] == itemId) index = i;
		if (index < 0 || before.count(itemId) <= 0 || after.count(itemId) != before.count(itemId) - 1) return false;
		if (index > 0)
			return after.count(STAMINA[index - 1]) == before.count(STAMINA[index - 1]) + 1;
		int vialChange = after.count(ItemID.VIAL_EMPTY) - before.count(ItemID.VIAL_EMPTY);
		return vialChange == 0 || vialChange == 1;
	}

	private static boolean staminaChanged(int itemId, Snapshot before, Snapshot after)
	{
		if (before.staminaActive != after.staminaActive || before.count(ItemID.VIAL_EMPTY) != after.count(ItemID.VIAL_EMPTY))
			return true;
		for (int id : STAMINA) if (before.count(id) != after.count(id)) return true;
		return false;
	}

	private void beginExchange(Run current, Snapshot state)
	{
		current.staminaUnavailable = false;
		PlankType type = current.settings.type;
		int bankPlanks = state.bank.getOrDefault(type.getPlankId(), 0);
		current.saleQuantity = current.settings.threshold > 0 && remainingLogs(state, type) == 0 ? bankPlanks : 0;
		current.buyNeeded = current.settings.batch > 0 && remainingLogs(state, type) == 0;
		current.exchangePrepared = false;
		current.exchangeBanked = false;
		current.purchasedLogs = 0;
		current.purchaseBankTarget = -1;
		current.saleDone = current.saleQuantity == 0;
		current.buyDone = !current.buyNeeded;
		current.exchange = null;
		current.state = State.EXCHANGE;
		current.status = "Preparing a Grand Exchange trip";
	}

	private void prepareExchange(Run current, Snapshot state)
	{
		if (!state.bankOpen && !state.interactions.contains(Action.OPEN_BANK) && state.position.distanceTo(BANK) > 2)
		{
			beginTravel(current, state, BANK, State.EXCHANGE);
			return;
		}
		if (!state.bankOpen)
		{
			issue(current, state, Action.OPEN_BANK, -1, 0);
			return;
		}
		if (!current.bankFresh || state.bank == null)
		{
			issue(current, state, Action.CLOSE_BANK, -1, 0);
			return;
		}
		PlankType type = current.settings.type;
		current.banked = state.bank.getOrDefault(type.getPlankId(), 0);
		int logs = state.count(type.getLogId());
		int surplus = firstPresent(state.inventory, STAMINA);
		if (surplus == -1 && state.count(ItemID.VIAL_EMPTY) > 0) surplus = ItemID.VIAL_EMPTY;
		if (surplus != -1)
		{
			issue(current, state, Action.DEPOSIT, surplus, state.count(surplus));
			return;
		}
		if (logs > 0)
		{
			issue(current, state, Action.DEPOSIT, type.getLogId(), logs);
			return;
		}
		int bankCoins = state.bank.getOrDefault(ItemID.COINS, 0);
		if (bankCoins > 0 && state.coins() < Integer.MAX_VALUE)
		{
			if (state.freeSlots == 0 && state.coins() == 0)
			{
				fail(current, "Free an inventory slot for coins, then restart Planker.");
				return;
			}
			issue(current, state, Action.WITHDRAW, ItemID.COINS, Math.min(bankCoins, Integer.MAX_VALUE - state.coins()));
			return;
		}
		if (!current.saleDone)
		{
			if (state.notedPlankId < 0)
			{
				fail(current, "The selected planks cannot be withdrawn as notes. Check the plank type, then restart Planker.");
				return;
			}
			int carried = state.count(state.notedPlankId);
			if (current.banked > 0)
			{
				if (state.freeSlots == 0 && carried == 0)
				{
					fail(current, "Free an inventory slot for noted " + type.getPlankName() + ", then restart Planker.");
					return;
				}
				if ((long) current.banked + carried > Integer.MAX_VALUE)
				{
					fail(current, "Sell some " + type.getPlankName() + " manually so all remaining planks fit in one noted stack, then restart Planker.");
					return;
				}
				if (!state.noted) issue(current, state, Action.NOTE, -1, 0);
				else issue(current, state, Action.WITHDRAW_NOTED, type.getPlankId(), current.banked);
				return;
			}
			if (carried == 0)
			{
				fail(current, "The selected planks are no longer available to sell. Check your bank, then restart Planker.");
				return;
			}
			current.saleQuantity = carried;
		}
		if (state.noted)
		{
			issue(current, state, Action.UNNOTE, -1, 0);
			return;
		}
		current.buyNeeded = current.settings.batch > 0 && remainingLogs(state, type) == 0;
		current.buyDone = !current.buyNeeded;
		if (current.buyNeeded && current.saleDone && state.freeSlots == 0)
		{
			fail(current, "Free an inventory slot to collect purchased logs, then restart Planker.");
			return;
		}
		current.exchangePrepared = true;
	}

	private void exchange(Run current, Snapshot state)
	{
		if (!current.exchangePrepared)
		{
			prepareExchange(current, state);
			return;
		}
		if (state.bankOpen && (!current.saleDone || !current.buyDone))
		{
			issue(current, state, Action.CLOSE_BANK, -1, 0);
			return;
		}
		if ((!current.saleDone || !current.buyDone) && !state.exchangeOpen && !state.bankOpen
			&& !state.interactions.contains(Action.OPEN_EXCHANGE) && state.position.distanceTo(EXCHANGE) > 2)
		{
			beginTravel(current, state, EXCHANGE, State.EXCHANGE);
			return;
		}
		if (current.exchange == null && (!current.saleDone || !current.buyDone))
		{
			if (!state.exchangeOpen)
			{
				issue(current, state, Action.OPEN_EXCHANGE, -1, 0);
				return;
			}
			boolean sell = !current.saleDone;
			current.exchange = Microbot.getClientThread().runOnClientThreadOptional(() -> {
				if (!canDispatch(current)) return null;
				try
				{
					current.exchangeSold = 0;
					return new PlankerExchange(client, current.settings.type, sell,
						sell ? current.saleQuantity : current.settings.batch,
						sell ? current.settings.sellDirection : current.settings.buyDirection,
						sell ? current.settings.sellPercent : current.settings.buyPercent,
						current.settings.offerWaitMinutes);
				}
				catch (RuntimeException exception)
				{
					fail(current, exception.getMessage());
					return null;
				}
			}).orElse(null);
			return;
		}
		if (current.exchange != null && !state.exchangeComplete && !state.exchangeOpen)
		{
			if (state.position.distanceTo(EXCHANGE) > 2)
			{
				beginTravel(current, state, EXCHANGE, State.EXCHANGE);
				return;
			}
			issue(current, state, Action.OPEN_EXCHANGE, -1, 0);
			return;
		}
		if (current.exchange != null)
		{
			current.tradeStatus = state.exchangeStatus;
			current.status = state.exchangeStatus;
			if (state.exchangeComplete)
			{
				if (!current.saleDone) current.saleDone = true;
				else
				{
					long purchased = (long) state.count(state.notedLogId) + state.count(current.settings.type.getLogId());
					if (purchased < 1 || purchased > Integer.MAX_VALUE)
					{
						fail(current, "Purchased logs are unavailable for banking. Check the completed offer and inventory before restarting Planker.");
						return;
					}
					current.purchasedLogs = (int) purchased;
					current.buyDone = true;
				}
				current.exchange = null;
				return;
			}
			if (state.exchangeInput == null || !mayAct(current, state)) return;
			PlankerExchange helper = current.exchange;
			PlankerExchange.Input input = state.exchangeInput;
			if (input.bounds != null && Microbot.naturalMouse != null)
			{
				Point point = Rs2UiHelper.getClickingPoint(input.bounds, true);
				Microbot.naturalMouse.moveTo(point.getX(), point.getY());
			}
			Microbot.getClientThread().runOnClientThreadOptional(() -> {
				synchronized (PlankerScript.this)
				{
					if (!canDispatch(current) || current.exchange != helper || current.lastActionTick != state.tick) return false;
					try
					{
						if (!helper.dispatch(input)) return false;
						current.lastActionTick = client.getTickCount();
						return true;
					}
					catch (RuntimeException exception)
					{
						fail(current, exception.getMessage());
						return false;
					}
				}
			});
			return;
		}
		if (state.exchangeOpen)
		{
			issue(current, state, Action.CLOSE_EXCHANGE, -1, 0);
			return;
		}
		if (current.buyNeeded && !current.exchangeBanked)
		{
			if (!state.bankOpen)
			{
				if (!state.interactions.contains(Action.OPEN_BANK) && state.position.distanceTo(EXCHANGE) > 2)
				{
					beginTravel(current, state, EXCHANGE, State.EXCHANGE);
					return;
				}
				issue(current, state, Action.OPEN_BANK, -1, 0);
				return;
			}
			if (!current.bankFresh || state.bank == null)
			{
				issue(current, state, Action.CLOSE_BANK, -1, 0);
				return;
			}
			if (current.purchaseBankTarget < 0)
			{
				long carried = (long) state.count(state.notedLogId) + state.count(current.settings.type.getLogId());
				if (carried != current.purchasedLogs)
				{
					fail(current, "Purchased logs changed before banking. Check your inventory and bank before restarting Planker.");
					return;
				}
				long target = (long) state.bank.getOrDefault(current.settings.type.getLogId(), 0) + current.purchasedLogs;
				if (target > Integer.MAX_VALUE)
				{
					fail(current, "The bank log stack cannot hold the purchased logs. Free space in that stack before restarting Planker.");
					return;
				}
				current.purchaseBankTarget = (int) target;
			}
			int deposit = state.count(state.notedLogId) > 0 ? state.notedLogId : current.settings.type.getLogId();
			if (state.count(deposit) > 0)
			{
				issue(current, state, Action.DEPOSIT, deposit, state.count(deposit));
				return;
			}
			if (state.bank.getOrDefault(current.settings.type.getLogId(), 0) != current.purchaseBankTarget)
			{
				fail(current, "The bank did not retain the purchased logs. Check your inventory and bank before restarting Planker.");
				return;
			}
			current.exchangeBanked = true;
		}
		if (state.bankOpen)
		{
			issue(current, state, Action.CLOSE_BANK, -1, 0);
			return;
		}
		beginTravel(current, state, BANK, State.BANK);
	}

	private synchronized void beginTravel(Run current, Snapshot state, WorldPoint destination, State next)
	{
		if (current.state == State.TRAVEL && destination.equals(current.destination)) return;
		if (!mayAct(current, state)) return;
		if (serviceReady(current, state, destination))
		{
			current.state = next;
			current.bankReady = false;
			return;
		}
		current.state = State.TRAVEL;
		current.destination = destination;
		current.afterTravel = next;
		current.arrivalTicks = 0;
		current.status = destination.equals(BANK) ? "Walking to Auburnvale bank"
			: destination.equals(EXCHANGE) ? "Walking to the Grand Exchange" : "Walking to Auburnvale Sawmill";
		if (!current.walker.walkTo(destination))
			fail(current, "Efficient Walker could not start the route. Check its route requirements, then restart Planker.");
	}

	private void travel(Run current, Snapshot state)
	{
		if (!guardRoute(current)) return;
		if (state.position == null || current.destination == null) return;
		String walkerStatus = current.walker.status();
		boolean ready = serviceReady(current, state, current.destination);
		boolean arrived = "ARRIVED".equals(walkerStatus) && state.position.distanceTo(current.destination) <= 2;
		if (ready || arrived)
		{
			if (++current.arrivalTicks < 2) return;
			cancelOwnedRoute(current);
			current.state = current.afterTravel;
			current.bankReady = false;
			return;
		}
		current.arrivalTicks = 0;
	}

	private void convert(Run current, Snapshot state)
	{
		PlankType type = current.settings.type;
		if (firstPresent(state.inventory, MODIFIERS) != -1)
		{
			fail(current, "Bank all plank sacks, baskets, sawmill vouchers and coupons, then restart Planker.");
			return;
		}
		int logs = state.count(type.getLogId());
		if (logs == 0)
		{
			beginTravel(current, state, BANK, State.BANK);
			return;
		}
		long deficit = (long) logs * type.getFee() - state.coins();
		if (deficit > 0)
		{
			fail(current, "Add " + deficit + " coins to your inventory for these " + type.getLogName()
				+ ", then restart Planker.");
			return;
		}
		if (!state.makeOpen && !state.interactions.contains(Action.OPEN_MAKE) && state.position.distanceTo(SAWMILL) > 3)
		{
			beginTravel(current, state, SAWMILL, State.CONVERT);
			return;
		}
		issue(current, state, state.makeOpen ? Action.CONVERT : Action.OPEN_MAKE, type.getLogId(), logs);
	}

	private void issue(Run current, Snapshot state, Action action, int itemId, int amount)
	{
		current.pending = new Pending(action, itemId, amount, state);
		dispatch(current, state);
	}

	private boolean awaitPending(Run current, Snapshot state)
	{
		Pending pending = current.pending;
		boolean done = false;
		int completedAmount = 0;
		switch (pending.action)
		{
			case OPEN_BANK:
				done = state.bankOpen && state.bank != null && state.bankEpoch > pending.before.bankEpoch;
				break;
			case CLOSE_BANK: done = !state.bankOpen; break;
			case NOTE: done = state.noted; break;
			case UNNOTE: done = !state.noted; break;
			case OPEN_MAKE: done = state.makeOpen; break;
			case OPEN_EXCHANGE: done = state.exchangeOpen; break;
			case CLOSE_EXCHANGE: done = !state.exchangeOpen; break;
			case INVENTORY:
				done = Microbot.getClientThread().runOnClientThreadOptional(() ->
					visible(client.getWidget(InterfaceID.Inventory.ITEMS))).orElse(false);
				break;
			case DRINK:
				done = verifiedStamina(pending.itemId, pending.before, state);
				if (staminaChanged(pending.itemId, pending.before, state) && !pending.changed)
				{
					pending.changed = true;
					pending.lastProgressTick = state.tick;
					log.debug("Planker action progress action={} tick={}", pending.action, state.tick);
				}
				break;
			case BANK_VIEW:
			case BANK_SCROLL:
				done = state.bankOpen && Microbot.getClientThread().runOnClientThreadOptional(() -> {
					Action needed = withdrawalPreparation(pending.itemId);
					return pending.action == Action.BANK_VIEW ? needed != Action.BANK_VIEW : needed == Action.WITHDRAW;
				}).orElse(false);
				break;
			case DEPOSIT:
			case WITHDRAW:
			case WITHDRAW_NOTED:
				int receivedId = pending.action == Action.WITHDRAW_NOTED ? state.notedPlankId : pending.itemId;
				boolean transferChanged = state.count(receivedId) != pending.before.count(receivedId);
				if (state.bankOpen && state.bank != null && state.bankEpoch > pending.before.bankEpoch)
				{
					int baseId = pending.itemId == state.notedLogId ? current.settings.type.getLogId()
						: pending.itemId == state.notedPlankId ? current.settings.type.getPlankId() : pending.itemId;
					int change = pending.action == Action.DEPOSIT ? -pending.amount : pending.amount;
					done = (long) state.count(receivedId) - pending.before.count(receivedId) == change
						&& (long) state.bank.getOrDefault(baseId, 0) - pending.before.bank.getOrDefault(baseId, 0) == -change;
					transferChanged |= state.count(receivedId) != pending.before.count(receivedId)
						|| state.bank.getOrDefault(baseId, 0).intValue() != pending.before.bank.getOrDefault(baseId, 0).intValue();
				}
				if (transferChanged && !pending.changed)
				{
					pending.changed = true;
					pending.lastProgressTick = state.tick;
					log.debug("Planker action progress action={} tick={}", pending.action, state.tick);
				}
				break;
			case CONVERT:
				PlankType type = current.settings.type;
				int converted = verifiedConversion(type, pending.before.count(type.getLogId()),
					pending.before.count(type.getPlankId()), pending.before.coins(), state.count(type.getLogId()),
					state.count(type.getPlankId()), state.coins());
				if (converted > 0)
				{
					completedAmount = converted;
					done = true;
				}
				break;
			default: break;
		}
		if (done)
		{
			if (pending.observedAt < 0)
			{
				pending.observedAt = state.tick;
				pending.completedAmount = completedAmount;
				log.debug("Planker action observed action={} tick={} attempts={}", pending.action, state.tick, pending.attempts);
				return true;
			}
			if (state.tick <= pending.observedAt) return true;
			if (pending.action == Action.OPEN_BANK) current.bankFresh = true;
			if (pending.action == Action.DRINK) current.state = State.BANK;
			if (pending.action == Action.CONVERT)
			{
				current.produced += pending.completedAmount;
				current.status = "Made " + pending.completedAmount + " " + current.settings.type.getPlankName();
			}
			log.debug("Planker action stable action={} observedTick={} stableTick={}", pending.action,
				pending.observedAt, state.tick);
			current.pending = null;
			return true;
		}
		if (pending.observedAt >= 0)
		{
			fail(current, actionName(pending.action) + " did not remain stable. Check the interface and supplies, then restart Planker.");
			return true;
		}
		if (state.tick - Math.max(pending.sentTick, pending.lastProgressTick) < WAIT_TICKS) return true;
		if (pending.action == Action.DRINK)
		{
			if (pending.attempts < 1)
			{
				if (playerIdle()) dispatch(current, state);
				return true;
			}
			current.staminaUnavailable = true;
			current.state = State.BANK;
			current.pending = null;
			return true;
		}
		if (pending.changed)
		{
			fail(current, "Bank supplies changed without the expected transfer. Check your inventory and bank, then restart Planker.");
			return true;
		}
		if (pending.attempts >= MAX_ATTEMPTS)
		{
			fail(current, "No verified response while " + actionName(pending.action)
				+ ". Check the bank or sawmill interface and supplies, then restart Planker.");
			return true;
		}
		if (!playerIdle() && (pending.action == Action.OPEN_BANK || pending.action == Action.OPEN_MAKE
			|| pending.action == Action.OPEN_EXCHANGE)) return true;
		log.debug("Planker action retry action={} tick={} attempt={}", pending.action, state.tick, pending.attempts + 1);
		dispatch(current, state);
		return true;
	}

	private void dispatch(Run current, Snapshot state)
	{
		if (!mayAct(current, state)) return;
		if (current.pending.action == Action.WITHDRAW || current.pending.action == Action.WITHDRAW_NOTED)
		{
			int itemId = current.pending.itemId;
			Action preparation = Microbot.getClientThread().runOnClientThreadOptional(() ->
				canDispatch(current) ? withdrawalPreparation(itemId) : Action.WITHDRAW).orElse(Action.WITHDRAW);
			if (preparation != Action.WITHDRAW)
				current.pending = new Pending(preparation, itemId, current.pending.amount, state);
		}
		Pending pending = current.pending;
		pending.sentTick = state.tick;
		current.status = actionName(pending.action);
		if (pending.action == Action.INVENTORY)
		{
			Microbot.getClientThread().runOnClientThreadOptional(() -> {
				synchronized (PlankerScript.this)
				{
					if (!canDispatch(current)) return false;
					pending.attempts++;
					current.lastActionTick = client.getTickCount();
					pending.sentTick = current.lastActionTick;
					client.runScript(915, InterfaceTab.INVENTORY.getVarcIntIndex());
					log.debug("Planker action dispatch action={} tick={} attempt={}", pending.action,
						pending.sentTick, pending.attempts);
					return true;
				}
			});
			return;
		}
		if (pending.action == Action.BANK_SCROLL)
		{
			Microbot.getClientThread().runOnClientThreadOptional(() -> {
				synchronized (PlankerScript.this)
				{
					if (!canDispatch(current)) return false;
					Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
					Widget item = bankItem(pending.itemId);
					if (items == null || item == null || item.isHidden()) return false;
					pending.attempts++;
					int scroll = bankScrollTarget(items.getBounds(), item.getBounds(), items.getScrollY(), items.getScrollHeight());
					current.lastActionTick = client.getTickCount();
					pending.sentTick = current.lastActionTick;
					client.setVarcIntValue(VarClientInt.BANK_SCROLL, scroll);
					client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.Bankmain.SCROLLBAR, InterfaceID.Bankmain.ITEMS, scroll);
					items.setScrollY(scroll);
					log.debug("Planker action dispatch action={} tick={} attempt={}", pending.action,
						pending.sentTick, pending.attempts);
					return true;
				}
			});
			return;
		}
		Click click = Microbot.getClientThread().runOnClientThreadOptional(() -> {
			if (!canDispatch(current)) return null;
			return action(current, pending);
		}).orElse(null);
		if (click == null || click.entry == null || !current.running || run != current || !current.walker.isAvailable())
		{
			if (playerIdle() && pending.action != Action.DRINK) pending.attempts++;
			return;
		}
		if (click.bounds != null && Microbot.naturalMouse != null)
		{
			Point point = Rs2UiHelper.getClickingPoint(click.bounds, true);
			Microbot.naturalMouse.moveTo(point.getX(), point.getY());
		}
		Microbot.getClientThread().runOnClientThreadOptional(() -> {
			synchronized (PlankerScript.this)
			{
				if (!canDispatch(current)) return false;
				Click fresh = action(current, pending);
				if (fresh == null || fresh.entry == null) return false;
				NewMenuEntry entry = fresh.entry;
				pending.attempts++;
				current.lastActionTick = client.getTickCount();
				pending.sentTick = current.lastActionTick;
				client.menuAction(entry.getParam0(), entry.getParam1(), entry.getType(), entry.getIdentifier(),
					entry.getItemId(), entry.getOption(), entry.getTarget());
				log.debug("Planker action dispatch action={} tick={} attempt={}", pending.action,
					pending.sentTick, pending.attempts);
				return true;
			}
		});
	}

	private boolean canDispatch(Run current)
	{
		return run == current && current.running && current.sessionReady && current.walker.isAvailable() && !Microbot.pauseAllScripts.get()
			&& client.getGameState() == GameState.LOGGED_IN && client.getWorld() == current.world && guardRoute(current);
	}

	private boolean guardRoute(Run current)
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() -> {
			try
			{
				if (!current.running || run != current) return false;
				WorldPoint active = current.walker.destination();
				String status = current.walker.status();
				if (!routeAvailable(current.destination, active, status))
				{
					fail(current, "Efficient Walker's route changed. Stop the other route, then restart Planker.");
					return false;
				}
				if (current.destination != null && "BLOCKED".equals(status))
				{
					String target = current.destination.equals(BANK) ? "Auburnvale bank"
						: current.destination.equals(SAWMILL) ? "Auburnvale Sawmill" : "the Grand Exchange";
					String reason = current.walker.planningFailure();
					fail(current, "Efficient Walker stopped before reaching " + target
						+ ("NONE".equals(reason) ? "." : " (" + reason + ").")
						+ " Resolve Efficient Walker's reported error, then restart Planker.");
					return false;
				}
				return true;
			}
			catch (RuntimeException exception)
			{
				fail(current, exception.getMessage());
				return false;
			}
		}).orElse(false);
	}

	static boolean routeAvailable(WorldPoint owned, WorldPoint active, String status)
	{
		if (owned != null) return owned.equals(active)
			|| (active == null && ("ARRIVED".equals(status) || "BLOCKED".equals(status)));
		return active == null && !"PLANNING".equals(status) && !"PREVIEW".equals(status) && !"WALKING".equals(status);
	}

	private Click action(Run current, Pending pending)
	{
		switch (pending.action)
		{
			case OPEN_BANK: return PlankerInteraction.nearest(client, null, "Bank");
			case OPEN_MAKE: return PlankerInteraction.nearest(client, new int[]{14659}, "Buy-plank");
			case CLOSE_BANK: return findAction(client.getWidget(12, 0), -1, "Close");
			case NOTE: return findAction(client.getWidget(InterfaceID.Bankmain.NOTE), -1, "Enable Notes");
			case UNNOTE: return findAction(client.getWidget(InterfaceID.Bankmain.NOTE), -1, "Disable Notes");
			case BANK_VIEW: return findAction(client.getWidget(InterfaceID.Bankmain.TABS), -1, "View all items");
			case DEPOSIT:
				return findAction(client.getWidget(InterfaceID.Bankside.ITEMS), pending.itemId, "Deposit-All");
			case DRINK:
				if (visible(client.getWidget(InterfaceID.Bankmain.UNIVERSE))
					|| !shouldDrinkStamina(client.getEnergy(), client.getVarbitValue(VarbitID.STAMINA_ACTIVE) != 0)) return null;
				return findAction(client.getWidget(InterfaceID.Inventory.ITEMS), pending.itemId, "Drink");
			case WITHDRAW:
			case WITHDRAW_NOTED:
				if (pending.action == Action.WITHDRAW_NOTED && client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) != 1) return null;
				return findAction(client.getWidget(InterfaceID.Bankmain.ITEMS), pending.itemId,
					pending.amount == 1 ? "Withdraw-1" : "Withdraw-All");
			case CONVERT: return makeAction(current.settings.type.getLogId());
			case OPEN_EXCHANGE: return PlankerInteraction.nearest(client, CLERKS, "Exchange");
			case CLOSE_EXCHANGE: return findAction(client.getWidget(465, 0), -1, "Close");
			default: return null;
		}
	}

	private Action withdrawalPreparation(int itemId)
	{
		Widget item = bankItem(itemId);
		if (client.getVarbitValue(VarbitID.BANK_CURRENTTAB) != 0 || item == null || item.isHidden()) return Action.BANK_VIEW;
		return rawClick(item) == null ? Action.BANK_SCROLL : Action.WITHDRAW;
	}

	private Widget bankItem(int itemId)
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null || items.getDynamicChildren() == null) return null;
		for (Widget item : items.getDynamicChildren())
			if (item != null && item.getItemId() == itemId) return item;
		return null;
	}

	static int bankScrollTarget(Rectangle viewport, Rectangle item, int currentScroll, int contentHeight)
	{
		if (viewport == null || item == null || viewport.height <= 0 || item.height <= 0 || currentScroll < 0)
			throw new IllegalArgumentException("The bank item view is unavailable. Reopen the bank, then restart Planker.");
		long centered = (long) currentScroll + item.y - viewport.y - (viewport.height - item.height) / 2;
		return (int) Math.max(0, Math.min(Math.max(0, contentHeight - viewport.height), centered));
	}

	private boolean mayAct(Run current, Snapshot state)
	{
		if (!current.running || run != current || current.lastActionTick == state.tick) return false;
		if (!current.walker.isAvailable())
		{
			fail(current, "Efficient Walker became unavailable. Restore it, then restart Planker.");
			return false;
		}
		current.lastActionTick = state.tick;
		return true;
	}

	private boolean playerIdle()
	{
		if (client == null) return true;
		return Microbot.getClientThread().runOnClientThreadOptional(() -> {
			try
			{
				Player player = client.getLocalPlayer();
				return player == null || player.getPoseAnimation() == player.getIdlePoseAnimation();
			}
			catch (RuntimeException | Error ignored)
			{
				return true;
			}
		}).orElse(true);
	}

	private boolean serviceReady(Run current, Snapshot state, WorldPoint destination)
	{
		return playerIdle() && state.position != null && destination != null
			&& state.position.distanceTo(destination) <= SERVICE_RANGE
			&& state.interactions.contains(destinationAction(current, destination));
	}

	static int remainingLogs(Snapshot state, PlankType type)
	{
		long logs = (long) state.count(type.getLogId()) + state.count(state.notedLogId);
		if (state.bank != null) logs += state.bank.getOrDefault(type.getLogId(), 0);
		return (int) Math.min(Integer.MAX_VALUE, logs);
	}

	static int remainingPlanks(Snapshot state, PlankType type)
	{
		long planks = (long) state.count(type.getPlankId())
			+ (state.notedPlankId > 0 ? state.count(state.notedPlankId) : 0);
		if (state.bank != null) planks += state.bank.getOrDefault(type.getPlankId(), 0);
		return (int) Math.min(Integer.MAX_VALUE, planks);
	}

	private static Action destinationAction(Run current, WorldPoint destination)
	{
		if (destination.equals(SAWMILL)) return Action.OPEN_MAKE;
		if (destination.equals(BANK) || (current.saleDone && current.buyDone)) return Action.OPEN_BANK;
		return Action.OPEN_EXCHANGE;
	}

	private Set<Action> nearbyInteractions(WorldPoint position)
	{
		Set<Action> result = EnumSet.noneOf(Action.class);
		if (position == null) return result;
		boolean atBank = position.distanceTo(BANK) <= 12;
		boolean atExchange = position.distanceTo(EXCHANGE) <= 12;
		if ((atBank || atExchange) && PlankerInteraction.nearest(client, null, "Bank") != null) result.add(Action.OPEN_BANK);
		if (atExchange && PlankerInteraction.nearest(client, CLERKS, "Exchange") != null) result.add(Action.OPEN_EXCHANGE);
		if (position.distanceTo(SAWMILL) <= 12 && PlankerInteraction.nearest(client, new int[]{14659}, "Buy-plank") != null) result.add(Action.OPEN_MAKE);
		return result;
	}

	private Click makeAction(int logId)
	{
		Widget root = client.getWidget(270, 0);
		if (root == null) return null;
		Deque<Widget> queue = new ArrayDeque<>();
		Set<Widget> visited = new HashSet<>();
		queue.add(root);
		while (!queue.isEmpty())
		{
			Widget widget = queue.removeFirst();
			if (!visited.add(widget) || widget.isHidden()) continue;
			if (widget.getItemId() == logId)
			{
				Widget parent = widget.getParent();
				if (parent != null) return findAction(parent, -1, "Make");
			}
			addChildren(queue, widget.getDynamicChildren());
			addChildren(queue, widget.getStaticChildren());
			addChildren(queue, widget.getNestedChildren());
		}
		return null;
	}

	private Click findAction(Widget root, int itemId, String action)
	{
		if (root == null) return null;
		Deque<Widget> queue = new ArrayDeque<>();
		Set<Widget> visited = new HashSet<>();
		queue.add(root);
		while (!queue.isEmpty())
		{
			Widget widget = queue.removeFirst();
			if (!visited.add(widget) || widget.isHidden()) continue;
			String[] actions = widget.getActions();
			if ((itemId == -1 || widget.getItemId() == itemId) && actions != null)
			{
				for (int i = 0; i < actions.length; i++)
				{
					if (actions[i] == null || !normalize(actions[i]).equals(normalize(action))) continue;
					Click raw = rawClick(widget);
					if (raw != null) return new Click(new NewMenuEntry().option(actions[i]).target(widget.getName())
						.identifier(i + 1).type(i < 5 ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY)
						.param0(widget.getIndex()).param1(widget.getId()).itemId(widget.getItemId()).widget(widget), raw.bounds);
				}
			}
			addChildren(queue, widget.getDynamicChildren());
			addChildren(queue, widget.getStaticChildren());
			addChildren(queue, widget.getNestedChildren());
		}
		return null;
	}

	private static void addChildren(Deque<Widget> queue, Widget[] children)
	{
		if (children != null) for (Widget child : children) if (child != null) queue.add(child);
	}

	private Click rawClick(Widget widget)
	{
		if (widget == null || widget.isHidden()) return null;
		Rectangle bounds = widget.getBounds();
		Rectangle canvas = new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
		if (widget.getId() == InterfaceID.Bankmain.ITEMS && widget.getIndex() >= 0)
		{
			Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
			if (items == null || items.isHidden()) return null;
			canvas = canvas.intersection(items.getBounds());
		}
		if (bounds == null || bounds.width <= 1 || bounds.height <= 1 || !canvas.contains(bounds)) return null;
		return new Click(null, new Rectangle(bounds));
	}

	private Snapshot snapshot(Run current)
	{
		Map<Integer, Integer> inventory = items(client.getItemContainer(InventoryID.INVENTORY));
		Map<Integer, Integer> equipment = items(client.getItemContainer(InventoryID.EQUIPMENT));
		boolean bankOpen = visible(client.getWidget(InterfaceID.Bankmain.UNIVERSE));
		Map<Integer, Integer> bank = bankOpen ? items(client.getItemContainer(InventoryID.BANK)) : null;
		WorldPoint position = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation();
		boolean gameplay = visible(client.getWidget(548, 0)) || visible(client.getWidget(161, 0))
			|| visible(client.getWidget(164, 0)) || visible(client.getWidget(601, 0));
		if (current.world == 0) current.world = client.getWorld();
		boolean ready = client.getGameState() == GameState.LOGGED_IN && position != null && gameplay;
		current.gameState = client.getGameState();
		int occupied = 0;
		ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
		if (container != null) for (Item item : container.getItems()) if (item.getId() > 0 && item.getQuantity() > 0) occupied++;
		PlankType type = current.settings.type;
		PlankerExchange helper = current.exchange;
		boolean exchangeOpen = visible(client.getWidget(465, 0));
		PlankerExchange.Input exchangeInput = ready && current.sessionReady && exchangeOpen
			? nextExchangeInput(current, helper) : null;
		return new Snapshot(client.getTickCount(), ready,
			ready && client.getWorldType().contains(WorldType.MEMBERS)
				&& Quest.CHILDREN_OF_THE_SUN.getState(client) == QuestState.FINISHED,
			position, inventory, equipment, bankOpen, bank, bankEpoch,
			client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) == 1, visible(client.getWidget(270, 0)),
			client.getVarcIntValue(VarClientID.SKILLMULTI_QUANTITY), 28 - occupied,
			client.getItemDefinition(type.getLogId()).getLinkedNoteId(),
			client.getItemDefinition(type.getPlankId()).getLinkedNoteId(), exchangeOpen,
			exchangeInput, helper != null && helper.isComplete(), helper == null ? current.tradeStatus : helper.status(),
			ready ? nearbyInteractions(position) : Collections.emptySet(), client.getEnergy(),
			client.getVarbitValue(VarbitID.STAMINA_ACTIVE) != 0);
	}

	private PlankerExchange.Input nextExchangeInput(Run current, PlankerExchange helper)
	{
		if (helper == null) return null;
		try { return helper.next(); }
		finally
		{
			int sold = helper.getSold();
			current.sold += sold - current.exchangeSold;
			current.exchangeSold = sold;
		}
	}

	static Map<Integer, Integer> items(ItemContainer container)
	{
		if (container == null) return null;
		Map<Integer, Integer> result = new HashMap<>();
		for (Item item : container.getItems())
			if (item.getId() > 0 && item.getQuantity() > 0) result.merge(item.getId(), item.getQuantity(), Math::addExact);
		return Collections.unmodifiableMap(result);
	}

	private static boolean visible(Widget widget) { return widget != null && !widget.isHidden(); }
	private static String normalize(String action) { return Text.removeTags(action).replace('-', ' ').trim().toLowerCase(java.util.Locale.ROOT); }
	static int firstPresent(Map<Integer, Integer> items, int[] ids)
	{
		return Arrays.stream(ids).filter(id -> items.getOrDefault(id, 0) > 0).findFirst().orElse(-1);
	}

	static int verifiedConversion(PlankType type, int beforeLogs, int beforePlanks, long beforeCoins,
		int afterLogs, int afterPlanks, long afterCoins)
	{
		if (beforeLogs == afterLogs && beforePlanks == afterPlanks && beforeCoins == afterCoins) return 0;
		int converted = beforeLogs - afterLogs;
		if (converted <= 0 || afterLogs < 0 || beforePlanks < 0 || afterCoins < 0
			|| (long) afterPlanks - beforePlanks != converted || beforeCoins - afterCoins != (long) converted * type.getFee())
			throw new IllegalStateException("The sawmill changed supplies without the expected planks and fee. Check your inventory before restarting Planker.");
		return converted;
	}

	static String existingOfferError(PlankType type, GrandExchangeOffer[] offers)
	{
		if (offers == null || offers.length == 0)
			return "Grand Exchange offers are unavailable. Wait for gameplay to load, then restart Planker.";
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == null)
				return "Grand Exchange offer slot " + (slot + 1) + " is unavailable. Wait for gameplay to load, then restart Planker.";
			if (offer.getState() != GrandExchangeOfferState.EMPTY
				&& (offer.getItemId() == type.getLogId() || offer.getItemId() == type.getPlankId()))
				return "Complete or cancel the existing " + (offer.getItemId() == type.getLogId() ? type.getLogName() : type.getPlankName())
					+ " offer, collect Grand Exchange slot " + (slot + 1) + ", then restart Planker.";
		}
		return "";
	}

	private static String actionName(Action action)
	{
		switch (action)
		{
			case OPEN_BANK: return "Opening bank";
			case CLOSE_BANK: return "Closing the bank";
			case DRINK: return "Drinking a stamina potion";
			case INVENTORY: return "Opening the inventory";
			case NOTE: return "Selecting noted withdrawals";
			case UNNOTE: return "Selecting unnoted withdrawals";
			case DEPOSIT: return "Banking supplies";
			case WITHDRAW: return "Withdrawing supplies";
			case WITHDRAW_NOTED: return "Withdrawing all selected planks as notes";
			case BANK_VIEW: return "Showing all bank items";
			case BANK_SCROLL: return "Bringing bank supplies into view";
			case OPEN_MAKE: return "Opening the sawmill";
			case CONVERT: return "Converting logs to planks";
			case OPEN_EXCHANGE: return "Opening the Grand Exchange";
			case CLOSE_EXCHANGE: return "Closing the Grand Exchange";
			default: return "Waiting";
		}
	}

	private synchronized void fail(Run current, String message)
	{
		if (current.error != null || current.stopped || run != current) return;
		current.error = message == null || message.isBlank()
			? "Planker could not continue. Check your setup, then restart Planker." : Text.removeTags(message);
		String offer = pendingOfferNotice(current);
		if (!offer.isEmpty() && !current.error.contains(offer)) current.error += " " + offer;
		current.status = "Error: " + current.error;
		log.debug("Planker stopped state={} reason={}", current.state, current.error);
		current.stoppedAtNanos = System.nanoTime();
		current.running = false;
		current.state = State.STOPPED;
		current.pending = null;
		if (current.executor != null) current.executor.shutdownNow();
		try
		{
			cancelOwnedRoute(current);
		}
		catch (RuntimeException exception)
		{
			current.error += " Could not cancel the route; stop Efficient Walker manually.";
			current.status = "Error: " + current.error;
		}
		chatError(current);
	}

	private void chatError(Run current)
	{
		Microbot.getClientThread().invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			ColorUtil.wrapWithColorTag(current.status, Color.RED), null));
	}

	private void cancelOwnedRoute(Run current)
	{
		if (current.walker != null && current.destination != null
			&& current.destination.equals(current.walker.destination())) current.walker.cancel();
		current.destination = null;
	}

	private static String pendingOfferNotice(Run current)
	{
		return current.exchange != null && current.exchange.hasPendingOffer() ? current.exchange.stopMessage() : "";
	}

	String getState() { return run == null ? State.STOPPED.name() : run.state.name(); }
	String getError() { return run == null ? null : run.error; }
	String getStatus() { return run == null ? "Stopped" : run.status
		+ (run.running && run.staminaUnavailable ? " (continuing without stamina supplies or a free potion slot)" : ""); }
	String getTrade() { return run == null ? "None" : run.tradeStatus; }
	long getSold() { return run == null ? 0 : run.sold; }
	long getRuntimeMillis()
	{
		Run current = run;
		if (current == null) return 0;
		long stopped = current.stoppedAtNanos;
		return TimeUnit.NANOSECONDS.toMillis(Math.max(0, (stopped == 0 ? System.nanoTime() : stopped) - current.startedAtNanos));
	}
	String getPlankLabel()
	{
		Run current = run;
		if (current == null || current.settings == null) return "";
		return current.settings.type == PlankType.REGULAR ? "Regular" : current.settings.type.getPlankName().replace(" plank", "");
	}
	String getActivity()
	{
		Run current = run;
		if (current == null) return "Stopped";
		if (current.error != null || current.state == State.STOPPED) return "Stopped";
		if ("Paused".equals(current.status)) return "Paused";
		switch (current.state)
		{
			case BANK: return "Banking";
			case CONVERT: return "Making planks";
			case TRAVEL: return current.status.replace("Walking", "Travelling");
			case EXCHANGE: return current.exchange == null ? "Preparing GE trip" : current.saleDone ? "Buying logs" : "Selling planks";
			default: return current.status;
		}
	}
	String getTradeDetail()
	{
		Run current = run;
		PlankerExchange helper = current == null ? null : current.exchange;
		return helper == null ? "" : helper.status();
	}
	long getProduced() { return run == null ? 0 : run.produced; }
	int getBanked() { return run == null ? 0 : run.banked; }
	int getSellThreshold() { return run == null || run.settings == null ? 0 : run.settings.threshold; }

	static final class Settings
	{
		final PlankType type;
		final int batch;
		final PriceDirection buyDirection;
		final int buyPercent;
		final int threshold;
		final PriceDirection sellDirection;
		final int sellPercent;
		final boolean useStamina;
		final int offerWaitMinutes;

		private Settings(PlankerConfig config)
		{
			type = config.plankType(); batch = config.logsPerPurchase(); buyDirection = config.buyDirection();
			buyPercent = config.buyPercent(); threshold = config.sellThreshold();
			sellDirection = config.sellDirection(); sellPercent = config.sellPercent();
			useStamina = config.useStaminaPotions(); offerWaitMinutes = config.offerWaitMinutes();
			if (type == null || buyDirection == null || sellDirection == null || batch < 0 || threshold < 0
				|| buyPercent < 0 || sellPercent < 0 || offerWaitMinutes < 1 || offerWaitMinutes > 10
				|| (buyDirection == PriceDirection.BELOW && buyPercent >= 100)
				|| (sellDirection == PriceDirection.BELOW && sellPercent >= 100))
				throw new IllegalArgumentException("Choose a plank type and non-negative quantities and percentages; below-guide percentages must be under 100.");
		}

		static Settings from(PlankerConfig config)
		{
			if (config == null) throw new IllegalArgumentException("Open Planker configuration and choose a plank type.");
			return new Settings(config);
		}
	}

	static final class Snapshot
	{
		final int tick;
		final boolean ready, access, bankOpen, noted, makeOpen;
		final WorldPoint position;
		final Map<Integer, Integer> inventory, equipment, bank;
		final long bankEpoch;
		final int makeQuantity, freeSlots, notedLogId, notedPlankId;
		final boolean exchangeOpen, exchangeComplete;
		final PlankerExchange.Input exchangeInput;
		final String exchangeStatus;
		final Set<Action> interactions;
		final int energy;
		final boolean staminaActive;

		Snapshot(int tick, boolean ready, boolean access, WorldPoint position, Map<Integer, Integer> inventory,
			Map<Integer, Integer> equipment, boolean bankOpen, Map<Integer, Integer> bank, long bankEpoch,
			boolean noted, boolean makeOpen, int makeQuantity, int freeSlots, int notedLogId, int notedPlankId)
		{
			this(tick, ready, access, position, inventory, equipment, bankOpen, bank, bankEpoch,
				noted, makeOpen, makeQuantity, freeSlots, notedLogId, notedPlankId, false, null, false, "None");
		}

		Snapshot(int tick, boolean ready, boolean access, WorldPoint position, Map<Integer, Integer> inventory,
			Map<Integer, Integer> equipment, boolean bankOpen, Map<Integer, Integer> bank, long bankEpoch,
			boolean noted, boolean makeOpen, int makeQuantity, int freeSlots, int notedLogId, int notedPlankId,
			boolean exchangeOpen, PlankerExchange.Input exchangeInput, boolean exchangeComplete, String exchangeStatus)
		{
			this(tick, ready, access, position, inventory, equipment, bankOpen, bank, bankEpoch,
				noted, makeOpen, makeQuantity, freeSlots, notedLogId, notedPlankId, exchangeOpen,
				exchangeInput, exchangeComplete, exchangeStatus, Collections.emptySet());
		}

		Snapshot(int tick, boolean ready, boolean access, WorldPoint position, Map<Integer, Integer> inventory,
			Map<Integer, Integer> equipment, boolean bankOpen, Map<Integer, Integer> bank, long bankEpoch,
			boolean noted, boolean makeOpen, int makeQuantity, int freeSlots, int notedLogId, int notedPlankId,
			boolean exchangeOpen, PlankerExchange.Input exchangeInput, boolean exchangeComplete, String exchangeStatus,
			Set<Action> interactions)
		{
			this(tick, ready, access, position, inventory, equipment, bankOpen, bank, bankEpoch,
				noted, makeOpen, makeQuantity, freeSlots, notedLogId, notedPlankId, exchangeOpen,
				exchangeInput, exchangeComplete, exchangeStatus, interactions, 10000, false);
		}

		Snapshot(int tick, boolean ready, boolean access, WorldPoint position, Map<Integer, Integer> inventory,
			Map<Integer, Integer> equipment, boolean bankOpen, Map<Integer, Integer> bank, long bankEpoch,
			boolean noted, boolean makeOpen, int makeQuantity, int freeSlots, int notedLogId, int notedPlankId,
			boolean exchangeOpen, PlankerExchange.Input exchangeInput, boolean exchangeComplete, String exchangeStatus,
			Set<Action> interactions, int energy, boolean staminaActive)
		{
			this.energy = energy; this.staminaActive = staminaActive;
			this.interactions = Set.copyOf(interactions);
			this.tick = tick; this.ready = ready; this.access = access; this.position = position;
			this.inventory = inventory; this.equipment = equipment; this.bankOpen = bankOpen; this.bank = bank;
			this.bankEpoch = bankEpoch; this.noted = noted; this.makeOpen = makeOpen; this.makeQuantity = makeQuantity;
			this.freeSlots = freeSlots; this.notedLogId = notedLogId; this.notedPlankId = notedPlankId;
			this.exchangeOpen = exchangeOpen; this.exchangeInput = exchangeInput;
			this.exchangeComplete = exchangeComplete; this.exchangeStatus = exchangeStatus;
		}

		int count(int id) { return inventory.getOrDefault(id, 0); }
		int coins() { return count(ItemID.COINS); }
	}

	static final class Click
	{
		final NewMenuEntry entry;
		final Rectangle bounds;
		final int distance;
		Click(NewMenuEntry entry, Rectangle bounds) { this(entry, bounds, Integer.MAX_VALUE); }
		Click(NewMenuEntry entry, Rectangle bounds, int distance)
		{
			this.entry = entry; this.bounds = bounds; this.distance = distance;
		}
	}

	private static final class Pending
	{
		final Action action;
		final int itemId, amount;
		final Snapshot before;
		int sentTick, attempts, observedAt = -1, lastProgressTick = -1, completedAmount;
		boolean changed;
		Pending(Action action, int itemId, int amount, Snapshot before)
		{ this.action = action; this.itemId = itemId; this.amount = amount; this.before = before; }
	}

	private static final class Run
	{
		Settings settings;
		PlankerWalker walker;
		ScheduledExecutorService executor;
		volatile boolean running;
		boolean stopped;
		volatile State state = State.STOPPED;
		State resumeState;
		volatile String status = "Stopped", error;
		volatile String tradeStatus = "None";
		final long startedAtNanos = System.nanoTime();
		volatile long stoppedAtNanos;
		volatile long produced, sold;
		int exchangeSold;
		volatile int banked = -1;
		volatile Snapshot latest;
		volatile GameState gameState;
		volatile PlankerExchange exchange;
		int processedTick = -1, lastActionTick = -1, arrivalTicks, world, reconnectWorld, readySamples;
		String accountProfile;
		boolean sessionReady;
		boolean bankFresh, bankReady;
		int staminaItem = -1;
		volatile boolean staminaUnavailable;
		boolean exchangePrepared, exchangeBanked, buyNeeded, saleDone, buyDone;
		int saleQuantity, purchasedLogs, purchaseBankTarget = -1;
		Pending pending;
		WorldPoint destination;
		State afterTravel;
	}
}
