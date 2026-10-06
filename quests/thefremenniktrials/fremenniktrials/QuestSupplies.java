package net.runelite.client.plugins.microbot.fremenniktrials;

import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;

/** One observed transaction at a time. Travel is exclusively delegated to Walker. */
final class QuestSupplies
{
	enum Mode { TRAVEL, KOSCHEI, EMPTY }
	private final FremennikTrialsPlugin owner;
	private final Map<Integer,Integer> desired;
	private final Mode mode;
	private boolean arrived, planned, closed;
	private int amount;
	private boolean amountSent;
	private int revealItem = -1;
	private int revealScroll = -1;
	private int revealTab = -1;

	QuestSupplies(FremennikTrialsPlugin owner, Map<Integer,Integer> desired, Mode mode)
	{
		this.owner = owner;
		this.desired = new LinkedHashMap<>(desired);
		this.mode = mode;
	}

	boolean tick()
	{
		if (closed)
		{
			if (open()) { return false; }
			owner.supplied(mode);
			return true;
		}
		if (!arrived)
		{
			if (!owner.hasRoute()) { owner.startRoute("walkToNearestBank", owner.walker(), "Walking to nearest bank for quest supplies"); return false; }
			Map<?,?> state = owner.control();
			FremennikTrialsPlugin.require(Boolean.TRUE.equals(state.get("owned")), "Supply route ownership changed; inspect before resuming");
			FremennikTrialsPlugin.require(state.get("failure") == null && !"BLOCKED".equals(state.get("state")), "Walker cannot reach the supply bank");
			if (Boolean.TRUE.equals(state.get("active"))) { return false; }
			FremennikTrialsPlugin.require("ARRIVED".equals(state.get("state")), "Supply bank arrival was not confirmed");
			owner.cancelRoute(); arrived = true;
		}
		if (!open())
		{
			owner.send("Open supply bank", this::open, () -> FremennikTrialsPlugin.require(
				Boolean.TRUE.equals(FremennikTrialsPlugin.call(owner.walker(), "openRouteBank")), "No safe bank interaction at Walker's arrival"));
			return false;
		}
		if (owner.client.getItemContainer(InventoryID.BANK) == null || Rs2Bank.getBankLiveEpoch() <= 0)
		{
			owner.setStatus("Waiting for live bank contents"); return false;
		}
		if (!planned) { plan(); planned = true; }
		if ((mode != Mode.TRAVEL || desired.containsKey(1381)) && !owner.equipmentEmpty())
		{
			Widget deposit = owner.findWidget("Deposit worn items", null, 12);
			FremennikTrialsPlugin.require(deposit != null, "Bank equipment deposit control is unavailable");
			owner.send("Bank prohibited equipment", owner::equipmentEmpty, () -> owner.widgetAction(deposit, "Deposit worn items"));
			return false;
		}
		for (Item item : owner.items(InventoryID.INVENTORY))
		{
			if (item.getId() < 0 || keep(item.getId())) { continue; }
			Widget inventory = owner.client.getWidget(15, 3);
			Widget selected = findItem(inventory, item.getId());
			FremennikTrialsPlugin.require(selected != null, "Bank inventory is unavailable for " + owner.itemName(item.getId()));
			int id = item.getId();
			owner.send("Bank " + owner.itemName(id), () -> owner.quantity(InventoryID.INVENTORY,id) == 0,
				() -> owner.widgetAction(selected, "Deposit-All"));
			return false;
		}
		for (Map.Entry<Integer,Integer> entry : desired.entrySet())
		{
			int id = entry.getKey();
			int have = owner.quantity(InventoryID.INVENTORY,id) + (mode == Mode.TRAVEL ? owner.quantity(InventoryID.EQUIPMENT,id) : 0);
			int deficit = entry.getValue() - have;
			if (deficit <= 0) { continue; }
			int available = owner.quantity(InventoryID.BANK,id);
			FremennikTrialsPlugin.require(available >= deficit, "Need " + (deficit-available) + " more " + owner.itemName(id) + "; add owned supplies to the bank and resume");
			FremennikTrialsPlugin.require(owner.inventoryCount() < 28, "Inventory is full; bank unnecessary quest items and resume");
			FremennikTrialsPlugin.require(owner.client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES)==0, "Select Item withdrawals in the bank and resume");
			Widget selected = findItem(owner.client.getWidget(12,12),id);
			if (!onScreen(selected)) { reveal(id); return false; }
			int before = owner.quantity(InventoryID.INVENTORY,id), stored = available;
			amount = id==995 ? deficit : deficit>=10 ? 10 : deficit>=5 ? 5 : 1;
			amountSent = amount==1||amount==5||amount==10;
			String action = amountSent ? "Withdraw-"+amount : "Withdraw-X";
			owner.send("Withdraw " + amount + " " + owner.itemName(id), () ->
			{
				if (!amountSent)
				{
					Widget prompt=owner.client.getWidget(162,43);
					if (FremennikTrialsPlugin.visible(prompt) && "Enter amount:".equalsIgnoreCase(prompt.getText()))
					{
						FremennikTrialsPlugin.require(Boolean.TRUE.equals(FremennikTrialsPlugin.call(owner.walker(),"submitBankAmount",new Class<?>[]{int.class},amount)), "Cannot submit the bank amount");
						amountSent=true;
					}
					return false;
				}
				return owner.quantity(InventoryID.INVENTORY,id)>=before+amount && owner.quantity(InventoryID.BANK,id)<=stored-amount;
			}, () -> owner.widgetAction(selected,action));
			return false;
		}
		Widget close=owner.findWidget("Close",null,12);
		FremennikTrialsPlugin.require(close!=null,"Bank Close control is unavailable");
		owner.send("Close supply bank",()->!open(),()->owner.widgetAction(close,"Close"));
		closed=true;
		return false;
	}

	private void plan()
	{
		if (mode==Mode.EMPTY) { return; }
		// Owned high-heal food first; cakes remain valid for travel and the protected early rounds.
		int[] food = {385,373,379,7946,361,365,329,333,1891,2309};
		int needed=mode==Mode.KOSCHEI?24:10;
		if(mode==Mode.KOSCHEI)
		{
			desired.put(771,1);desired.put(946,1);
			int strength=-1;
			for(int id:new int[]{113,115,117,119})
			{if(owner.quantity(InventoryID.BANK,id)+owner.quantity(InventoryID.INVENTORY,id)>0){strength=id;break;}}
			FremennikTrialsPlugin.require(strength>=0,"Need one owned strength potion before Koschei");
			desired.put(strength,1);
			if(owner.quantity(InventoryID.BANK,2550)+owner.quantity(InventoryID.INVENTORY,2550)>0){desired.put(2550,1);}
			needed=28-desired.values().stream().mapToInt(Integer::intValue).sum();
		}
		for (int id:food)
		{
			int available=owner.quantity(InventoryID.BANK,id)+owner.quantity(InventoryID.INVENTORY,id);
			int take=Math.min(needed,available);
			if(take>0){desired.put(id,take);needed-=take;}
			if(needed==0){break;}
		}
		FremennikTrialsPlugin.require(needed==0,"Need "+needed+" more food items in the bank before this trial");
		if(mode==Mode.TRAVEL)
		{
			// Retain the single owned fairy-ring staff. Walker may equip it during transport.
			if(owner.quantity(InventoryID.INVENTORY,772)+owner.quantity(InventoryID.EQUIPMENT,772)+owner.quantity(InventoryID.BANK,772)>0){desired.put(772,1);}
		}
	}
	private boolean keep(int id)
	{
		return desired.containsKey(id) || mode==Mode.TRAVEL && FremennikTrialsPlugin.questItem(id);
	}
	private boolean open(){return FremennikTrialsPlugin.visible(owner.client.getWidget(12,0));}
	private static Widget findItem(Widget root,int id)
	{
		if(root==null||root.getChildren()==null){return null;}
		for(Widget widget:root.getChildren()){if(widget!=null&&!widget.isHidden()&&widget.getItemId()==id){return widget;}}
		return null;
	}
	private boolean onScreen(Widget widget)
	{
		Widget viewport=owner.client.getWidget(12,12);
		return FremennikTrialsPlugin.visible(widget)&&FremennikTrialsPlugin.visible(viewport)&&widget.getBounds()!=null&&viewport.getBounds().contains(widget.getBounds());
	}
	private void reveal(int id)
	{
		Item[] bank=owner.items(InventoryID.BANK);
		for(int slot=0;slot<bank.length;slot++)
		{
			if(bank[slot].getId()!=id){continue;}
			int tab=Rs2Bank.getItemTabForBankItem(slot);
			FremennikTrialsPlugin.require(tab>=0,"Bank item tab is unavailable");
			if(!Rs2Bank.isTabOpen(tab))
			{
				FremennikTrialsPlugin.require(revealItem!=id||revealTab!=tab,"Bank tab selection was ineffective; inspect bank search");
				revealItem=id;revealTab=tab;
				owner.send("Select supply bank tab",()->Rs2Bank.isTabOpen(tab),()->FremennikTrialsPlugin.require(Rs2Bank.openTab(tab),"Cannot select bank tab"));
				return;
			}
			FremennikTrialsPlugin.require(revealItem!=id||revealScroll!=slot,"Bank scrolling was ineffective; inspect bank search");
			revealItem=id;revealScroll=slot;
			owner.send("Reveal bank supply",()->onScreen(findItem(owner.client.getWidget(12,12),id)),()->FremennikTrialsPlugin.require(Rs2Bank.scrollBankToSlot(revealScroll),"Cannot reveal bank supply"));
			return;
		}
		throw new IllegalStateException("Supply disappeared from bank");
	}
}
