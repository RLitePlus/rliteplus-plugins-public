package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.efficientwalker.LocalTeleportCatalog.Teleport;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;

final class BankTrip
{
	static final int BANK_TICKS = 10;
	WorldPoint bank;
	final Option option;
	final int baselineTicks;
	final int totalTicks;
	boolean arrived;
	private boolean openSent;
	private boolean withdrawSent;
	private boolean closeSent;
	private int openingEpoch;
	private int inventoryBefore;
	private int bankBefore;
	private int withdrawnId;
	private int withdrawnAmount;
	private boolean amountPrompt;
	private boolean amountSubmitted;
	private int revealAttempts;
	private int revealedId = -1;

	BankTrip(WorldPoint bank, Option option, int baselineTicks, int totalTicks)
	{
		this.bank = bank;
		this.option = option;
		this.baselineTicks = baselineTicks;
		this.totalTicks = totalTicks;
	}

	static boolean worthwhile(int baselineTicks, int totalTicks)
	{
		return totalTicks >= 0 && totalTicks < Integer.MAX_VALUE
			&& (baselineTicks == Integer.MAX_VALUE
				|| (long) baselineTicks - totalTicks >= Math.max(25L, (baselineTicks + 4L) / 5));
	}

	static int travelTicks(List<WorldPoint> path, boolean running)
	{
		if (path.isEmpty()) { return Integer.MAX_VALUE; }
		int ticks = 0;
		int steps = 0;
		for (int i = 1; i < path.size(); i++)
		{
			if (path.get(i - 1).distanceTo(path.get(i)) == 1) { steps++; }
			else
			{
				ticks += (running ? (steps + 1) / 2 : steps) + Teleport.ACTIVATION_COST;
				steps = 0;
			}
		}
		return ticks + (running ? (steps + 1) / 2 : steps);
	}

	boolean inputPending()
	{
		return openSent || withdrawSent || closeSent;
	}

	boolean tick(Client client, EfficientWalker walker)
	{
		if (option.itemId >= 0 && !LocalTeleportCatalog.itemAllowed(client, option.itemId))
		{
			throw new IllegalStateException("The planned bank teleport item is unavailable on this world. Retry the walk.");
		}
		Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		boolean open = root != null && !root.isHidden();
		if (closeSent) { return !open; }
		if (!open)
		{
			if (withdrawSent) { throw new IllegalStateException("The bank closed before withdrawal was verified. Check inventory, then retry."); }
			if (!openSent)
			{
				openingEpoch = Rs2Bank.getBankLiveEpoch();
				if (!walker.openRouteBank()) { throw new IllegalStateException("No visible bank is accessible here. Open a bank manually, then retry the walk."); }
				openSent = true;
			}
			Microbot.status = "Waiting for the bank to open. Complete any bank PIN prompt; Cancel walk stops the detour.";
			return false;
		}
		if (Rs2Bank.getBankLiveEpoch() <= (openSent ? openingEpoch : 0))
		{
			Microbot.status = "Waiting for a live bank inventory.";
			return false;
		}
		ItemContainer carried = client.getItemContainer(InventoryID.INVENTORY);
		ItemContainer storedItems = client.getItemContainer(InventoryID.BANK);
		if (carried == null || storedItems == null || carried.getItems() == null || storedItems.getItems() == null) { return false; }
		if (withdrawSent)
		{
			if (amountPrompt && !amountSubmitted)
			{
				Widget prompt = client.getWidget(162, 43);
				if (prompt == null || prompt.isHidden() || !"Enter amount:".equalsIgnoreCase(prompt.getText())) { return false; }
				if (!walker.submitBankAmount(withdrawnAmount))
				{
					throw new IllegalStateException("Cannot enter the bank withdrawal amount. Clear the amount prompt, then retry the walk.");
				}
				amountSubmitted = true;
				return false;
			}
			if (quantity(carried, withdrawnId) < inventoryBefore + withdrawnAmount
				|| quantity(storedItems, withdrawnId) > bankBefore - withdrawnAmount)
			{
				Microbot.status = "Waiting to confirm the route supplies withdrawal. Cancel walk stops the detour.";
				return false;
			}
			withdrawSent = false;
		}
		Map<Integer, Integer> bankStock = new LinkedHashMap<>();
		for (Item item : storedItems.getItems())
		{
			if (item != null && item.getId() >= 0) { bankStock.merge(item.getId(), item.getQuantity(), Integer::sum); }
		}
		int coinDeficit = Math.max(0, option.coinsRequired - CharterShips.coins(client));
		int coinShortage = coinDeficit - bankStock.getOrDefault(net.runelite.api.gameval.ItemID.COINS, 0);
		if (coinShortage > 0)
		{
			throw new IllegalStateException("You need " + coinShortage
				+ " more Coins in the bank for this route. Deposit the missing Coins or choose another route.");
		}
		Map<Integer, Integer> missing = option.bankWithdrawals(client, bankStock);
		if (missing == null)
		{
			throw new IllegalStateException("The bank stock or requirements for " + option.name()
				+ " changed. Check its supplies and requirements, then retry the walk.");
		}
		if (!missing.isEmpty())
		{
			if (client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) != 0)
			{
				throw new IllegalStateException("Bank withdrawals are set to Note. Select Item in the bank, then retry the walk.");
			}
			if (!Option.fitsInventory(client, missing))
			{
				throw new IllegalStateException("No inventory space for the route supplies. Bank unused items, then retry.");
			}
			Map.Entry<Integer, Integer> next = missing.entrySet().iterator().next();
			int id = next.getKey();
			int amount = id == net.runelite.api.gameval.ItemID.COINS ? next.getValue() : withdrawalAmount(next.getValue());
			Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
			Widget selected = null;
			if (items != null && items.getDynamicChildren() != null)
			{
				for (Widget child : items.getDynamicChildren())
				{
					if (child != null && child.getItemId() == id && !child.isHidden()) { selected = child; break; }
				}
			}
			if (!walker.bankWidgetAction(selected, (amount != 1 && amount != 5 && amount != 10) ? "Withdraw-X" : "Withdraw-" + amount))
			{
				if (revealBankItem(storedItems, id))
				{
					Microbot.status = "Selecting the bank tab containing the route supplies.";
					return false;
				}
				throw new IllegalStateException("Cannot select the bank route supplies. Clear bank search, then retry.");
			}
			revealAttempts = 0;
			revealedId = -1;
			withdrawnId = id;
			withdrawnAmount = amount;
			inventoryBefore = quantity(carried, id);
			bankBefore = quantity(storedItems, id);
			withdrawSent = true;
			amountPrompt = amount != 1 && amount != 5 && amount != 10;
			amountSubmitted = false;
			return false;
		}
		Widget frame = client.getWidget(InterfaceID.Bankmain.FRAME);
		if (frame != null && frame.getDynamicChildren() != null)
		{
			for (Widget child : frame.getDynamicChildren())
			{
				if (walker.bankWidgetAction(child, "Close")) { closeSent = true; return false; }
			}
		}
		throw new IllegalStateException("Cannot close the bank. Close it manually, then retry the walk.");
	}

	private boolean revealBankItem(ItemContainer bank, int id)
	{
		if (revealedId != id) { revealedId = id; revealAttempts = 0; }
		if (++revealAttempts > 3) { return false; }
		Item[] items = bank.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			if (items[slot] == null || items[slot].getId() != id) { continue; }
			int tab = Rs2Bank.getItemTabForBankItem(slot);
			if (tab < 0) { return false; }
			if (!Rs2Bank.isTabOpen(tab)) { return Rs2Bank.openTab(tab); }
			return Rs2Bank.scrollBankToSlot(slot);
		}
		return false;
	}

	private static int quantity(ItemContainer container, int id)
	{
		if (container == null || container.getItems() == null) { return -1; }
		int count = 0;
		for (Item item : container.getItems()) { if (item != null && item.getId() == id) { count += item.getQuantity(); } }
		return count;
	}

	static int withdrawalAmount(int deficit)
	{
		return deficit >= 10 ? 10 : deficit >= 5 ? 5 : 1;
	}

	static final class Option
	{
		final Teleport teleport;
		final WorldPoint landing;
		final int itemId;
		final Map<Integer, Integer> supplies;
		final int coinsRequired;

		Option(Teleport teleport, WorldPoint landing, int itemId)
		{
			this(teleport, landing, itemId, Map.of(itemId, 1));
		}

		Option(Teleport teleport, WorldPoint landing, Map<Integer, Integer> supplies)
		{
			this(teleport, landing, -1, supplies);
		}

		private Option(Teleport teleport, WorldPoint landing, int itemId, Map<Integer, Integer> supplies)
		{
			this(teleport, landing, itemId, supplies, 0);
		}

		private Option(Teleport teleport, WorldPoint landing, int itemId, Map<Integer, Integer> supplies, int coinsRequired)
		{
			this.coinsRequired = coinsRequired;
			this.teleport = teleport;
			this.landing = landing;
			this.itemId = itemId;
			this.supplies = Collections.unmodifiableMap(new LinkedHashMap<>(supplies));
		}

		static Option coins(WorldPoint bank, int required, int wallet)
		{
			return new Option(null, bank, -1, Map.of(), 0).withCoins(required, wallet);
		}

		static Option supplies(WorldPoint bank, Map<Integer, Integer> supplies)
		{
			return new Option(null, bank, -1, supplies, 0);
		}

		Option withSupplies(Map<Integer, Integer> additions)
		{
			Map<Integer, Integer> combined = new LinkedHashMap<>(supplies);
			additions.forEach((id, quantity) -> combined.merge(id, quantity, Math::max));
			return new Option(teleport, landing, itemId, combined, coinsRequired);
		}

		Option withCoins(int required, int wallet)
		{
			Map<Integer, Integer> combined = new LinkedHashMap<>(supplies);
			int deficit = Math.max(0, required - wallet);
			if (deficit > 0) { combined.put(net.runelite.api.gameval.ItemID.COINS, deficit); }
			return new Option(teleport, landing, itemId, combined, required);
		}

		String name()
		{
			return teleport != null ? teleport.name
				: supplies.keySet().stream().anyMatch(id -> id != net.runelite.api.gameval.ItemID.COINS)
					? "route supplies" : "charter fares";
		}

		int activationTicks() { return teleport == null ? 0 : teleport.activationTicks(); }

		Map<Integer, Integer> bankWithdrawals(Client client, Map<Integer, Integer> stock)
		{
			ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
			if (inventory == null || inventory.getItems() == null) { return null; }
			Map<Integer, Integer> missing = teleport == null ? new LinkedHashMap<>()
				: teleport.bankWithdrawals(client, stock, itemId);
			if (missing == null) { return null; }
			missing = new LinkedHashMap<>(missing);
			for (Map.Entry<Integer, Integer> supply : supplies.entrySet())
			{
				if (supply.getKey() == net.runelite.api.gameval.ItemID.COINS) { continue; }
				int deficit = Math.max(0, supply.getValue() - quantity(inventory, supply.getKey()));
				if (deficit > stock.getOrDefault(supply.getKey(), 0)) { return null; }
				if (deficit > 0) { missing.merge(supply.getKey(), deficit, Math::max); }
			}
			int deficit = Math.max(0, coinsRequired - CharterShips.coins(client));
			if (deficit > stock.getOrDefault(net.runelite.api.gameval.ItemID.COINS, 0)) { return null; }
			if (deficit > 0) { missing.put(net.runelite.api.gameval.ItemID.COINS, deficit); }
			return missing;
		}

		int bankTicks()
		{
			int inputs = 0;
			for (Map.Entry<Integer, Integer> supply : supplies.entrySet())
			{
				int quantity = supply.getValue();
				if (supply.getKey() == net.runelite.api.gameval.ItemID.COINS) { inputs += quantity != 1 && quantity != 5 && quantity != 10 ? 2 : 1; }
				else { while (quantity > 0) { quantity -= withdrawalAmount(quantity); inputs++; } }
			}
			return BANK_TICKS + Math.max(0, inputs - 1) * 2 + (teleport != null && teleport.isQuetzalWhistle() ? 4 : 0);
		}

		boolean fitsInventory(Client client) { return fitsInventory(client, supplies); }

		static boolean fitsInventory(Client client, Map<Integer, Integer> supplies)
		{
			ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
			if (inventory == null || inventory.getItems() == null) { return false; }
			Map<Integer, Integer> carried = new LinkedHashMap<>();
			int occupied = 0;
			for (Item item : inventory.getItems())
			{
				if (item != null && item.getId() >= 0) { occupied++; carried.merge(item.getId(), item.getQuantity(), Integer::sum); }
			}
			Set<Integer> stackable = new java.util.HashSet<>();
			for (int id : supplies.keySet())
			{
				if (client.getItemDefinition(id) == null) { return false; }
				if (client.getItemDefinition(id).isStackable()) { stackable.add(id); }
			}
			return fitsInventory(occupied, carried, stackable, supplies);
		}

		static boolean fitsInventory(int occupied, Map<Integer, Integer> carried,
			Set<Integer> stackable, Map<Integer, Integer> supplies)
		{
			int needed = 0;
			for (Map.Entry<Integer, Integer> supply : supplies.entrySet())
			{
				int deficit = Math.max(0, supply.getValue() - carried.getOrDefault(supply.getKey(), 0));
				if (deficit == 0) { continue; }
				needed += stackable.contains(supply.getKey()) ? 1 : deficit;
			}
			return occupied + needed <= 28;
		}
	}
}
