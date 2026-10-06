package net.runelite.client.plugins.microbot.planker;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.planker.PlankerConfig.PriceDirection;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PlankerExchange
{
	private static final Logger log = LoggerFactory.getLogger(PlankerExchange.class);
	private static final int WAIT_TICKS = 6;
	private static final int ABORT_WAIT_TICKS = WAIT_TICKS * 5;
	private static final int[] COLLECTIONS = {518, 519, 520, 521, 522, 523, 539, 540};
	private final Client client;
	private final PlankType type;
	private final boolean sell;
	private final int maximumQuantity;
	private final PriceDirection direction;
	private final int percent;
	private final int itemId;
	private final String itemName;
	private Phase phase = Phase.CREATE;
	private int slot = -1, noteId = -1, quantity, price, sentTick = -1, observedTick = -1, attempts, lastInputTick = -1;
	private int stalledTick = -1, receivedItems, receivedCoins, expectedItems, expectedCoins;
	private int targetQuantity, completedQuantity, legFilled, legSpent;
	private long placedAtNanos, offerWaitNanos;
	private volatile int sold;
	private boolean numericPrompt, quoted, aborting;
	private Input proposed, pending;
	private volatile boolean pendingOffer, complete;
	private volatile String status = "Preparing Grand Exchange offer";

	private enum Phase { CREATE, SELECT, QUANTITY, PRICE, CONFIRM, OFFER, COLLECT, COMPLETE }
	private enum Command { CREATE, SELECT, TYPE, QUANTITY, PRICE, ENTER, CONFIRM, ABORT, VIEW, COLLECT, BACK }

	PlankerExchange(Client client, PlankType type, boolean sell, int maximumQuantity,
		PriceDirection direction, int percent)
	{
		this(client, type, sell, maximumQuantity, direction, percent, 3);
	}

	PlankerExchange(Client client, PlankType type, boolean sell, int maximumQuantity,
		PriceDirection direction, int percent, int offerWaitMinutes)
	{
		if (client == null || type == null || maximumQuantity < 1 || direction == null || percent < 0
			|| offerWaitMinutes < 1 || offerWaitMinutes > 10
			|| (direction == PriceDirection.BELOW && percent >= 100))
			throw new IllegalArgumentException("Choose a positive trade quantity and a valid guide-price adjustment.");
		this.client = client;
		this.type = type;
		this.sell = sell;
		this.maximumQuantity = maximumQuantity;
		this.direction = direction;
		this.percent = percent;
		this.offerWaitNanos = TimeUnit.MINUTES.toNanos(offerWaitMinutes);
		itemId = sell ? type.getPlankId() : type.getLogId();
		itemName = sell ? type.getPlankName() : type.getLogName();
	}

	// Called only from the post-packet GameTick snapshot; dispatch never accounts effects.
	Input next()
	{
		if (complete) return null;
		View view = read();
		if (noteId == -1)
		{
			noteId = client.getItemDefinition(itemId).getLinkedNoteId();
			if (noteId <= 0) throw problem("The selected item has no verified noted form.");
		}
		if (pending != null)
		{
			if (observedTick >= 0)
			{
				if (!stableObserved(view)) throw problem("The observed Grand Exchange response did not remain stable.");
				if (view.tick <= observedTick) return null;
				log.debug("Planker exchange stable command={} observedTick={} stableTick={}", pending.command,
					observedTick, view.tick);
				pending = null;
				observedTick = -1;
				attempts = 0;
				stalledTick = -1;
				return null;
			}
			else if (observed(view))
			{
				observedTick = view.tick;
				log.debug("Planker exchange observed command={} tick={} attempts={}", pending.command, view.tick, attempts);
				return null;
			}
			else
			{
				if (view.tick - sentTick < (pending.command == Command.ABORT ? ABORT_WAIT_TICKS : WAIT_TICKS)) return null;
				if (pending.command == Command.CONFIRM || pending.command == Command.COLLECT || pending.command == Command.ABORT
					|| pending.command == Command.ENTER || attempts >= 3)
					throw problem("No verified response while " + status.toLowerCase(Locale.ROOT) + ".");
				if (!view.inventory.equals(pending.before.inventory)
					|| (pending.command == Command.TYPE && !view.text.equals(pending.before.text)))
					throw problem("Supplies or the input prompt changed unexpectedly.");
				proposed = pending;
				pending = null;
				log.debug("Planker exchange retry command={} tick={} attempt={}", proposed.command, view.tick, attempts + 1);
				return proposed;
			}
		}
		proposed = null;
		if (phase == Phase.CREATE)
		{
			checkOtherOffers(view);
			if (!view.index || view.selectedSlot != 0)
				throw problem("Return to the Grand Exchange offer list before starting a trade.");
			for (int i = 0; i < view.offers.length; i++)
			{
				if (slot >= 0 && i != slot) continue;
				if (!view.offers[i].empty()) continue;
				Input candidate = menu(view, Command.CREATE, InterfaceID.GeOffers.INDEX_0 + i, -1,
					sell ? "Create Sell offer" : "Create Buy offer");
				if (candidate != null) { slot = i; return offer(candidate, "Opening an empty offer slot"); }
			}
			throw problem("Free a Grand Exchange offer slot.");
		}
		if (phase.ordinal() < Phase.OFFER.ordinal())
		{
			checkOtherOffers(view);
			if (!view.offers[slot].empty()) throw problem("The reserved offer slot changed before confirmation.");
			if (!view.setup || view.selectedSlot != slot + 1 || view.offerType != (sell ? 1 : 0))
				return waitForUi(view, "Waiting for the selected offer setup");
			if (phase == Phase.SELECT)
			{
				if (view.selectedItem == itemId)
				{
					if (view.mode == 7 || view.mode == 14)
						return waitForUi(view, "Waiting for the selected item setup");
					if (!quoted)
					{
						int guide = guidePrice(view.guide);
						if (guide <= 0 || view.price != guide)
							return waitForUi(view, "Waiting for the selected item's guide price");
						try { price = PlankType.price(direction, guide, percent); }
						catch (IllegalArgumentException exception) { throw problem(exception.getMessage()); }
						int remaining = targetQuantity > completedQuantity ? targetQuantity - completedQuantity : maximumQuantity;
						quantity = sell ? remaining : purchaseQuantity(view, remaining);
						if (quantity == 0) throw purchaseDeficit(view);
						if ((long) price * quantity > Integer.MAX_VALUE)
							throw problem("This sale exceeds the Grand Exchange total-value limit. Reduce the sale price or selected plank stock.");
						targetQuantity = quantity;
						quoted = true;
					}
					phase = Phase.QUANTITY;
					stalledTick = -1;
				}
				else if (sell)
				{
					int remaining = quoted ? quantity : maximumQuantity;
					if (count(view.inventory, noteId) != remaining || count(view.inventory, itemId) != 0)
						throw problem("Withdraw exactly " + remaining + " noted " + itemName + " for this sale.");
					return offer(menu(view, Command.SELECT, InterfaceID.GeOffersSide.ITEMS, noteId, "Offer"),
						"Selecting " + itemName);
				}
				else
				{
					if (view.mode != 14) return waitForUi(view, "Waiting for the item search");
					if (!view.text.equalsIgnoreCase(itemName)) return type(view, itemName);
					return offer(searchResult(view), "Selecting " + itemName);
				}
			}
			if (view.selectedItem != itemId) throw problem("The selected offer item changed.");
			if (phase == Phase.QUANTITY || phase == Phase.PRICE)
			{
				int target = phase == Phase.QUANTITY ? quantity : price;
				int actual = phase == Phase.QUANTITY ? view.quantity : view.price;
				if (actual == target && view.mode != 7)
				{
					phase = phase == Phase.QUANTITY ? Phase.PRICE : Phase.CONFIRM;
					numericPrompt = false;
					stalledTick = -1;
					return null;
				}
				if (view.mode == 7)
				{
					if (!numericPrompt) throw problem("An unexpected numeric prompt is open.");
					String value = Integer.toString(target);
					if (!view.text.equals(value)) return type(view, value);
					return offer(new Input(phase, Command.ENTER, view, null, null, null, 0, 0), "Submitting offer value");
				}
				if (numericPrompt) return waitForUi(view, "Waiting for the offer value");
				return offer(menu(view, phase == Phase.QUANTITY ? Command.QUANTITY : Command.PRICE,
					InterfaceID.GeOffers.SETUP, -1, phase == Phase.QUANTITY ? "Enter quantity" : "Enter price"),
					phase == Phase.QUANTITY ? "Setting offer quantity" : "Setting fixed offer price");
			}
			if (phase == Phase.CONFIRM)
			{
				checkConfirmation(view);
				return offer(menu(view, Command.CONFIRM, InterfaceID.GeOffers.SETUP_CONFIRM, -1, "Confirm"),
					"Confirming " + (sell ? "sale" : "purchase") + " of " + quantity + " " + itemName);
			}
		}
		Offer owned = view.offers[slot];
		if (phase == Phase.OFFER)
		{
			checkOwned(owned);
			if (sell) sold = Math.max(sold, completedQuantity + owned.filled);
			long remainingNanos = offerWaitNanos - (System.nanoTime() - placedAtNanos);
			status = (sell ? "Selling " : "Buying ") + itemName + ": " + (completedQuantity + owned.filled) + "/" + targetQuantity
				+ " at " + price + " gp; reprice in " + Math.max(0, (remainingNanos + 999_999_999L) / 1_000_000_000L) + "s";
			if (!terminal(owned))
			{
				if (remainingNanos > 0) return null;
				replacementPrice();
				if (view.index && view.selectedSlot == 0)
					return offer(menu(view, Command.ABORT, InterfaceID.GeOffers.INDEX_0 + slot, -1, "Abort offer"),
						"Cancelling the unfilled offer before repricing");
				if (view.details && view.selectedSlot == slot + 1)
					return offer(menu(view, Command.ABORT, InterfaceID.GeOffers.DETAILS, -1, "Abort offer"),
						"Cancelling the unfilled offer before repricing");
				return waitForUi(view, "Waiting for the owned offer before repricing");
			}
			if (view.collection == null) return waitForUi(view, "Waiting for the finished offer's collection state");
			legFilled = owned.filled;
			legSpent = owned.spent;
			expectedItems = sell ? quantity - legFilled : legFilled;
			expectedCoins = sell ? count(view.collection, ItemID.COINS) : (int) ((long) quantity * price - owned.spent);
			Map<Integer, Integer> expected = new HashMap<>();
			if (expectedItems > 0) expected.put(itemId, expectedItems);
			if (expectedCoins > 0) expected.put(ItemID.COINS, expectedCoins);
			if (expectedCoins < 0 || (sell && (legFilled > 0 ? expectedCoins <= 0 : expectedCoins != 0)) || !view.collection.equals(expected))
				return waitForUi(view, "Waiting for exact finished-offer items and coins");
			phase = Phase.COLLECT;
			stalledTick = -1;
		}
		if (phase == Phase.COLLECT)
		{
			if (view.collection == null) return waitForUi(view, "Waiting for the owned collection container");
			if (receivedItems == expectedItems && receivedCoins == expectedCoins && view.collection.isEmpty())
			{
				if (!owned.empty()) return waitForUi(view, "Waiting for the collected offer slot to clear");
				if (!view.index || view.selectedSlot != 0)
				{
					if (view.selectedSlot != slot + 1) throw problem("The completed offer panel changed unexpectedly.");
					return offer(menu(view, Command.BACK, InterfaceID.GeOffers.BACK, -1, "Back"), "Returning to the offer list");
				}
				finishLeg(view);
				return null;
			}
			checkOwned(owned);
			if (!terminal(owned) || owned.filled != legFilled || owned.spent != legSpent)
				throw problem("The finished offer changed during collection.");
			if (!view.details || view.selectedSlot != slot + 1)
				return offer(menu(view, Command.VIEW, InterfaceID.GeOffers.INDEX_0 + slot, -1, "View offer"), "Opening the owned filled offer");
			int collectId = receivedItems < expectedItems ? itemId : ItemID.COINS;
			int amount = collectId == itemId ? expectedItems - receivedItems : expectedCoins - receivedCoins;
			if (amount <= 0 || count(view.collection, collectId) != amount)
				throw problem("The owned offer's remaining collection changed.");
			int inventoryId = collectId == itemId ? noteId : ItemID.COINS;
			if ((long) count(view.inventory, inventoryId) + amount > Integer.MAX_VALUE
				|| (count(view.inventory, inventoryId) == 0 && view.freeSlots == 0))
				throw problem("Free an inventory slot or coin-stack space, then collect the owned offer manually.");
			String action = collectId == ItemID.COINS ? "Collect" : amount == 1 ? "Collect-note" : "Collect-notes";
			Input collect = menu(view, Command.COLLECT, InterfaceID.GeOffers.DETAILS_COLLECT, collectId, action);
			if (collect != null)
				collect = new Input(phase, Command.COLLECT, view, collect.entry, collect.bounds, null, collectId, amount);
			return offer(collect, "Collecting owned " + (collectId == itemId ? itemName : "coins"));
		}
		return null;
	}

	boolean dispatch(Input input)
	{
		if (input == null || proposed == null || input.command != proposed.command || pending != null || input.phase != phase || complete) return false;
		View fresh = read();
		if (fresh.tick == lastInputTick || !fresh.inventory.equals(input.before.inventory)) return false;
		if (input.command == Command.CREATE && input.entry.getParam1() != InterfaceID.GeOffers.INDEX_0 + slot) return false;
		if (phase.ordinal() < Phase.OFFER.ordinal())
		{
			checkOtherOffers(fresh);
			if (!fresh.offers[slot].empty()) throw problem("The reserved slot is no longer empty.");
		}
		if (!sameScreen(input.before, fresh)) return false;
		if (input.command == Command.CONFIRM) checkConfirmation(fresh);
		if (input.command == Command.ABORT)
		{
			checkOwned(fresh.offers[slot]);
			if (aborting || terminal(fresh.offers[slot]) || System.nanoTime() - placedAtNanos < offerWaitNanos) return false;
			replacementPrice();
		}
		if (input.command == Command.COLLECT)
		{
			checkOwned(fresh.offers[slot]);
			if (!terminal(fresh.offers[slot]) || fresh.offers[slot].filled != legFilled || fresh.offers[slot].spent != legSpent
				|| fresh.collection == null || !fresh.collection.equals(input.before.collection)) return false;
		}
		if (input.command == Command.BACK && (!fresh.offers[slot].empty()
			|| fresh.collection == null || !fresh.collection.isEmpty())) return false;
		if (input.command == Command.SELECT && !sell)
		{
			Input selected = searchResult(fresh);
			if (selected == null || selected.entry.getParam0() != input.entry.getParam0()) return false;
		}
		NewMenuEntry entry = input.entry;
		if (entry != null)
		{
			Widget widget = client.getWidget(entry.getParam1());
			if (entry.getParam0() >= 0 && widget != null) widget = widget.getChild(entry.getParam0());
			Input current = widgetInput(fresh, input.command, widget, entry.getItemId(), entry.getOption());
			if (current == null || current.entry.getIdentifier() != entry.getIdentifier()) return false;
			if (input.command == Command.COLLECT && widget.getItemQuantity() != input.amount) return false;
		}
		else if (!fresh.text.equals(input.before.text) || (fresh.mode != 7 && fresh.mode != 14)) return false;
		lastInputTick = fresh.tick;
		sentTick = fresh.tick;
		attempts++;
		pending = input;
		observedTick = -1;
		proposed = null;
		if (input.command == Command.CONFIRM) pendingOffer = true;
		if (input.command == Command.ABORT) aborting = true;
		if (entry != null)
			client.menuAction(entry.getParam0(), entry.getParam1(), entry.getType(), entry.getIdentifier(),
				entry.getItemId(), entry.getOption(), entry.getTarget());
		else if (input.command == Command.ENTER) Rs2Keyboard.keyPress(KeyEvent.VK_ENTER);
		else for (char key : input.text.toCharArray()) Rs2Keyboard.keyPress(key);
		log.debug("Planker exchange dispatch command={} tick={} attempt={}", input.command, sentTick, attempts);
		return true;
	}

	private boolean stableObserved(View view)
	{
		switch (pending.command)
		{
			case CREATE:
				return view.setup && view.selectedSlot == slot + 1 && view.offerType == (sell ? 1 : 0);
			case SELECT:
				return view.setup && view.selectedSlot == slot + 1 && view.selectedItem == itemId
					&& view.offerType == (sell ? 1 : 0);
			case TYPE:
				return view.mode == pending.before.mode && view.text.equals(pending.before.text + pending.text);
			case QUANTITY:
			case PRICE:
				return view.mode == 7 && view.text.isEmpty() && view.selectedItem == itemId;
			case ENTER:
				return view.mode != 7 && (pending.phase == Phase.QUANTITY ? view.quantity == quantity : view.price == price);
			case CONFIRM:
				Offer placed = view.offers[slot];
				if (placed.empty()) return false;
				checkOwned(placed);
				return view.inventory.equals(adjusted(pending.before.inventory,
					sell ? noteId : ItemID.COINS, -(sell ? quantity : (long) quantity * price)));
			case ABORT:
				checkOwned(view.offers[slot]);
				return terminal(view.offers[slot]) && view.collection != null;
			case VIEW: return view.details && view.selectedSlot == slot + 1;
			case BACK: return view.index && view.selectedSlot == 0;
			case COLLECT:
				int receivedId = pending.collectId == itemId ? noteId : ItemID.COINS;
				return view.inventory.equals(adjusted(pending.before.inventory, receivedId, pending.amount))
					&& adjusted(pending.before.collection, pending.collectId, -pending.amount).equals(view.collection);
			default: return false;
		}
	}

	private boolean observed(View view)
	{
		switch (pending.command)
		{
			case CREATE:
				if (view.setup && view.selectedSlot == slot + 1 && view.offerType == (sell ? 1 : 0))
				{ phase = Phase.SELECT; return true; }
				return false;
			case SELECT:
				return view.tick > sentTick && view.setup && view.selectedSlot == slot + 1
					&& view.selectedItem == itemId && view.offerType == (sell ? 1 : 0);
			case TYPE: return view.mode == pending.before.mode && view.text.equals(pending.before.text + pending.text);
			case QUANTITY:
			case PRICE:
				if (view.mode == 7 && view.text.isEmpty() && view.selectedItem == itemId)
				{ numericPrompt = true; return true; }
				return false;
			case ENTER:
				if (view.mode != 7 && (phase == Phase.QUANTITY ? view.quantity == quantity : view.price == price))
				{ numericPrompt = false; phase = phase == Phase.QUANTITY ? Phase.PRICE : Phase.CONFIRM; return true; }
				return false;
			case CONFIRM:
				Offer placed = view.offers[slot];
				if (placed.empty()) return false;
				checkOwned(placed);
				Map<Integer, Integer> paid = adjusted(pending.before.inventory,
					sell ? noteId : ItemID.COINS, -(sell ? quantity : (long) quantity * price));
				if (!view.inventory.equals(paid)) return false;
				phase = Phase.OFFER;
				placedAtNanos = System.nanoTime();
				return true;
			case ABORT:
				checkOwned(view.offers[slot]);
				return terminal(view.offers[slot]);
			case VIEW: return view.details && view.selectedSlot == slot + 1;
			case BACK:
				return view.index && view.selectedSlot == 0;
			case COLLECT:
				int receivedId = pending.collectId == itemId ? noteId : ItemID.COINS;
				Map<Integer, Integer> received = adjusted(pending.before.inventory, receivedId, pending.amount);
				Map<Integer, Integer> remaining = adjusted(pending.before.collection, pending.collectId, -pending.amount);
				if (!view.inventory.equals(received) || !remaining.equals(view.collection)) return false;
				if (pending.collectId == itemId) receivedItems += pending.amount;
				else receivedCoins += pending.amount;
				return true;
			default: return false;
		}
	}

	void resetAfterReconnect()
	{
		pending = null;
		observedTick = -1;
		proposed = null;
		numericPrompt = false;
		stalledTick = -1;
		lastInputTick = -1;
		aborting = false;
		if (complete) return;
		if (pendingOffer)
		{
			phase = Phase.OFFER;
			return;
		}
		phase = Phase.CREATE;
		slot = -1;
		quoted = false;
	}

	private void checkConfirmation(View view)
	{
		if (!view.setup || view.selectedSlot != slot + 1 || view.selectedItem != itemId
			|| view.offerType != (sell ? 1 : 0) || view.quantity != quantity || view.price != price || view.mode == 7 || view.mode == 14)
			throw problem("The offer no longer matches the chosen item, quantity and fixed price.");
		if (sell)
		{
			if (count(view.inventory, noteId) != quantity || count(view.inventory, itemId) != 0)
				throw problem("The selected noted planks changed before sale confirmation.");
			if ((long) quantity * price + count(view.inventory, ItemID.COINS) > Integer.MAX_VALUE)
				throw problem("This sale would exceed coin-stack space. Reduce the selected plank stock, sale price or total coin holdings before restarting Planker.");
		}
		else if (purchaseQuantity(view, quantity) != quantity)
			throw problem("Coins changed before confirmation. Restore the purchase cost and all sawmill fees.");
	}

	private void checkOtherOffers(View view)
	{
		for (Offer offer : view.offers)
			if (!offer.empty() && (offer.itemId == type.getLogId() || offer.itemId == type.getPlankId()))
				throw problem("Cancel and collect existing offers for the selected logs and planks before restarting Planker.");
	}

	private void checkOwned(Offer offer)
	{
		if (offer.itemId != itemId || offer.quantity != quantity || offer.price != price
			|| !(offer.side(sell) || (aborting && offer.cancelled(sell))) || offer.filled < 0 || offer.filled > quantity
			|| (!sell && (offer.spent < 0 || offer.spent > (long) offer.filled * price)))
			throw problem("The owned offer was cancelled, replaced or changed.");
	}

	private boolean terminal(Offer offer)
	{
		return offer.full(sell) || (aborting && offer.cancelled(sell));
	}

	private int replacementPrice()
	{
		try { return PlankType.price(sell ? PriceDirection.BELOW : PriceDirection.ABOVE, price, 5); }
		catch (IllegalArgumentException exception)
		{
			throw problem("A further 5% " + (sell ? "decrease" : "increase")
				+ " would exceed the valid price range. Collect or cancel this offer manually.");
		}
	}

	private int purchaseQuantity(View view, int remaining)
	{
		long available = count(view.inventory, ItemID.COINS) - (long) completedQuantity * type.getFee();
		return PlankType.affordablePurchase(remaining, Math.max(0, available), price, type.getFee());
	}

	private IllegalStateException purchaseDeficit(View view)
	{
		long missing = (long) price + ((long) completedQuantity + 1) * type.getFee() - count(view.inventory, ItemID.COINS);
		return problem("Add " + missing + " coins to buy another " + itemName + " and cover all sawmill fees.");
	}

	private void finishLeg(View view)
	{
		completedQuantity += legFilled;
		pendingOffer = false;
		if (completedQuantity == targetQuantity) { finish(); return; }
		price = replacementPrice();
		quantity = targetQuantity - completedQuantity;
		if (!sell)
		{
			quantity = purchaseQuantity(view, quantity);
			if (quantity == 0)
			{
				if (completedQuantity > 0) { finish(); return; }
				throw purchaseDeficit(view);
			}
			targetQuantity = completedQuantity + quantity;
		}
		receivedItems = receivedCoins = expectedItems = expectedCoins = legFilled = legSpent = 0;
		aborting = numericPrompt = false;
		stalledTick = -1;
		phase = Phase.CREATE;
		status = "Repricing remaining " + quantity + " " + itemName + " at " + price + " gp";
	}

	private Input type(View view, String target)
	{
		if (target.length() > (view.mode == 14 ? 25 : 10)
			|| !target.toLowerCase(Locale.ROOT).startsWith(view.text.toLowerCase(Locale.ROOT)) || view.text.length() >= target.length())
			throw problem("The offer input changed unexpectedly. Clear the prompt before restarting Planker.");
		return offer(new Input(phase, Command.TYPE, view, null, null, target.substring(view.text.length()), 0, 0),
			phase == Phase.SELECT ? "Searching for " + itemName : "Entering the offer value");
	}

	private Input offer(Input input, String description)
	{
		status = description;
		if (input == null) return waitForUi(read(), description);
		stalledTick = -1;
		proposed = input;
		return input;
	}

	private Input waitForUi(View view, String description)
	{
		status = description;
		if (stalledTick < 0) stalledTick = view.tick;
		if (view.tick - stalledTick >= WAIT_TICKS * 3)
			throw problem("The expected Grand Exchange interface or collection state is unavailable.");
		return null;
	}

	private Input searchResult(View view)
	{
		Widget root = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		if (root == null || root.getDynamicChildren() == null) return null;
		Widget[] children = root.getDynamicChildren();
		for (int i = 2; i < children.length; i++)
			if (children[i] != null && children[i].getItemId() == itemId && children[i - 2] != null)
				return widgetInput(view, Command.SELECT, children[i - 2], -1, "Select");
		return null;
	}

	private Input menu(View view, Command command, int rootId, int wantedItem, String action)
	{
		Widget root = client.getWidget(rootId);
		if (root == null) return null;
		Deque<Widget> queue = new ArrayDeque<>();
		Set<Widget> visited = new HashSet<>();
		queue.add(root);
		while (!queue.isEmpty())
		{
			Widget widget = queue.removeFirst();
			if (!visited.add(widget) || widget.isHidden()) continue;
			Input found = widgetInput(view, command, widget, wantedItem, action);
			if (found != null) return found;
			for (Widget[] children : new Widget[][]{widget.getDynamicChildren(), widget.getStaticChildren(), widget.getNestedChildren()})
				if (children != null) for (Widget child : children) if (child != null) queue.add(child);
		}
		return null;
	}

	private Input widgetInput(View view, Command command, Widget widget, int wantedItem, String action)
	{
		if (widget == null || widget.isHidden() || (wantedItem != -1 && widget.getItemId() != wantedItem)) return null;
		Rectangle bounds = widget.getBounds();
		if (bounds == null || bounds.width <= 1 || bounds.height <= 1
			|| !new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight()).contains(bounds)) return null;
		String[] actions = widget.getActions();
		if (actions == null) return null;
		for (int i = 0; i < actions.length; i++)
			if (actions[i] != null && normalize(actions[i]).equals(normalize(action)))
				return new Input(phase, command, view, new NewMenuEntry().option(actions[i]).target(widget.getName())
					.identifier(i + 1).type(i < 5 ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY)
					.param0(widget.getIndex()).param1(widget.getId()).itemId(widget.getItemId()),
					bounds, null, 0, 0);
		return null;
	}

	private View read()
	{
		GrandExchangeOffer[] current = client.getGrandExchangeOffers();
		if (current == null || current.length != 8) throw problem("Grand Exchange offers are unavailable.");
		Offer[] offers = new Offer[current.length];
		for (int i = 0; i < current.length; i++)
		{
			if (current[i] == null || current[i].getState() == null) throw problem("Grand Exchange offer state is unavailable.");
			offers[i] = new Offer(current[i]);
		}
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null) throw problem("Inventory state is unavailable.");
		int occupied = 0;
		for (Item item : inventory.getItems()) if (item.getId() > 0 && item.getQuantity() > 0) occupied++;
		return new View(client.getTickCount(), offers, PlankerScript.items(inventory),
			slot < 0 ? null : PlankerScript.items(client.getItemContainer(COLLECTIONS[slot])), 28 - occupied,
			visible(InterfaceID.GeOffers.INDEX), visible(InterfaceID.GeOffers.SETUP), visible(InterfaceID.GeOffers.DETAILS),
			client.getVarbitValue(VarbitID.GE_SELECTEDSLOT), client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH),
			client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE), client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY),
			client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE), client.getVarcIntValue(VarClientInt.INPUT_TYPE),
			client.getVarcStrValue(VarClientStr.INPUT_TEXT), widgetText(InterfaceID.GeOffers.SETUP_MARKETPRICE));
	}

	private boolean visible(int id) { Widget widget = client.getWidget(id); return widget != null && !widget.isHidden(); }
	private String widgetText(int id) { Widget widget = client.getWidget(id); return widget == null ? "" : widget.getText(); }
	private static String normalize(String text) { return text == null ? "" : Text.removeTags(text).replace('-', ' ').trim().toLowerCase(Locale.ROOT); }
	static int guidePrice(String text)
	{
		String normalized = text == null ? "" : Text.removeTags(text).trim().toLowerCase(Locale.ROOT);
		if (!normalized.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?: (?:coins?|gp))?")) return 0;
		int suffix = normalized.indexOf(' ');
		try { return Integer.parseInt((suffix < 0 ? normalized : normalized.substring(0, suffix)).replace(",", "")); }
		catch (NumberFormatException exception) { return 0; }
	}
	private static int count(Map<Integer, Integer> items, int id) { return items.getOrDefault(id, 0); }
	static Map<Integer, Integer> adjusted(Map<Integer, Integer> before, int id, long delta)
	{
		long value = (long) count(before, id) + delta;
		if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalStateException("The expected item transfer is outside the stack limit.");
		Map<Integer, Integer> after = new HashMap<>(before);
		if (value == 0) after.remove(id); else after.put(id, (int) value);
		return after;
	}
	private static boolean sameScreen(View before, View after)
	{
		return before.index == after.index && before.setup == after.setup && before.details == after.details
			&& before.selectedSlot == after.selectedSlot && before.selectedItem == after.selectedItem
			&& before.offerType == after.offerType && before.quantity == after.quantity && before.price == after.price
			&& before.mode == after.mode;
	}
	private IllegalStateException problem(String reason)
	{
		return new IllegalStateException(reason + (pendingOffer ? " " + stopMessage() : " Check the Grand Exchange setup, then restart Planker."));
	}
	private void finish()
	{
		complete = true;
		pendingOffer = false;
		phase = Phase.COMPLETE;
		status = (sell ? "Sold " : "Bought ") + completedQuantity + " " + itemName;
	}
	int getSold() { return sold; }
	boolean isComplete() { return complete; }
	String status() { return status; }
	boolean hasPendingOffer() { return pendingOffer; }
	String stopMessage() { return pendingOffer ? "An offer may remain in Grand Exchange slot " + (slot + 1) + ". Cancel and collect it before restarting Planker." : ""; }

	static final class Input
	{
		final Rectangle bounds;
		private final Phase phase;
		private final Command command;
		private final View before;
		private final NewMenuEntry entry;
		private final String text;
		private final int collectId, amount;
		private Input(Phase phase, Command command, View before, NewMenuEntry entry, Rectangle bounds, String text, int collectId, int amount)
		{
			this.phase = phase; this.command = command; this.before = before; this.entry = entry;
			this.bounds = bounds == null ? null : new Rectangle(bounds); this.text = text;
			this.collectId = collectId; this.amount = amount;
		}
	}

	private static final class Offer
	{
		final int itemId, quantity, price, filled, spent;
		final GrandExchangeOfferState state;
		Offer(GrandExchangeOffer offer)
		{
			itemId = offer.getItemId(); quantity = offer.getTotalQuantity(); price = offer.getPrice();
			filled = offer.getQuantitySold(); spent = offer.getSpent(); state = offer.getState();
		}
		boolean empty() { return state == GrandExchangeOfferState.EMPTY; }
		boolean side(boolean sell) { return sell ? state == GrandExchangeOfferState.SELLING || state == GrandExchangeOfferState.SOLD
			: state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT; }
		boolean full(boolean sell) { return filled == quantity && state == (sell ? GrandExchangeOfferState.SOLD : GrandExchangeOfferState.BOUGHT); }
		boolean cancelled(boolean sell) { return state == (sell ? GrandExchangeOfferState.CANCELLED_SELL : GrandExchangeOfferState.CANCELLED_BUY); }
	}

	private static final class View
	{
		final int tick, freeSlots, selectedSlot, selectedItem, offerType, quantity, price, mode;
		final Offer[] offers;
		final Map<Integer, Integer> inventory, collection;
		final boolean index, setup, details;
		final String text, guide;
		View(int tick, Offer[] offers, Map<Integer, Integer> inventory, Map<Integer, Integer> collection, int freeSlots,
			boolean index, boolean setup, boolean details, int selectedSlot, int selectedItem, int offerType, int quantity,
			int price, int mode, String text, String guide)
		{
			this.tick = tick; this.offers = offers; this.inventory = inventory; this.collection = collection;
			this.freeSlots = freeSlots; this.index = index; this.setup = setup; this.details = details;
			this.selectedSlot = selectedSlot; this.selectedItem = selectedItem; this.offerType = offerType;
			this.quantity = quantity; this.price = price; this.mode = mode; this.text = text == null ? "" : text;
			this.guide = guide;
		}
	}
}
