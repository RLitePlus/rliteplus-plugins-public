package net.runelite.client.plugins.microbot.efficientwalker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfigNode;
import net.runelite.client.plugins.microbot.efficientwalker.LocalTransportCatalog.Transport;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

final class FairyRings
{
	// Data: skretzo/shortest-path, src/main/resources/transports/fairy_rings.tsv.
	static final String UPSTREAM_REVISION = "6ca996a41a6a4b85d0fdb38dc6d56c66b747e29a";
	static final String RESOURCE = "/net/runelite/client/plugins/microbot/efficientwalker/fairy_rings.tsv";
	private static final String[] DIALS = {"ADCB", "ILKJ", "PSRQ"};
	private static final int[] VARBITS = {VarbitID.FAIRYRING_1, VarbitID.FAIRYRING_2, VarbitID.FAIRYRING_3};
	private static final Set<String> KOUREND = Set.of("AKR", "BLS", "CIR", "CIS", "DJR");
	private static final Set<String> VARLAMORE = Set.of("AIS", "AJP", "CKQ");
	static final int[] STAFFS = {ItemID.DRAMEN_STAFF, ItemID.LUNAR_MOONCLAN_LIMINAL_STAFF};
	private final Map<WorldPoint, String> codes = new LinkedHashMap<>();
	private final List<Transport> transports = new ArrayList<>();
	private int pendingChild;
	private int expectedDialValue;

	static FairyRings load(Reader input) throws IOException
	{
		FairyRings rings = new FairyRings();
		List<String[]> origins = new ArrayList<>();
		Map<WorldPoint, String[]> destinations = new LinkedHashMap<>();
		BufferedReader reader = new BufferedReader(input);
		String line;
		while ((line = reader.readLine()) != null)
		{
			if (line.isEmpty() || line.startsWith("#")) { continue; }
			String[] row = line.split("\t", -1);
			if (row.length != 8) { throw new IOException("Invalid fairy ring data row"); }
			if (!row[0].isEmpty())
			{
				if (row[2].startsWith("Configure Fairy ring ") && !row[2].endsWith(" 29228")) { origins.add(row); }
				continue;
			}
			String code = row[7].replace(" ", "");
			if (!validCode(code) || "DIQ".equals(code)) { continue; }
			WorldPoint destination = LocalTransportCatalog.parsePoint(row[1]);
			rings.codes.put(destination, code);
			destinations.put(destination, row);
		}
		Set<WorldPoint> seen = new HashSet<>();
		for (String[] origin : origins)
		{
			WorldPoint approach = LocalTransportCatalog.parsePoint(origin[0]);
			if (!seen.add(approach)) { continue; }
			for (Map.Entry<WorldPoint, String[]> destination : destinations.entrySet())
			{
				if (approach.equals(destination.getKey())) { continue; }
				String[] row = destination.getValue();
				String code = rings.codes.get(destination.getKey());
				boolean partialQuest = "CJQ".equals(code) && "Troubled Tortugans".equals(row[4])
					|| "BJR".equals(code) && "Holy Grail".equals(row[4]);
				Transport transport = LocalTransportCatalog.fairyRingTransport(approach, destination.getKey(),
					Integer.parseInt(origin[2].substring(origin[2].lastIndexOf(' ') + 1)), row[3],
					partialQuest ? "" : row[4], row[5], "BJR".equals(code) ? "5>8" : "");
				rings.transports.add(transport);
			}
		}
		return rings;
	}

	List<Transport> transports() { return Collections.unmodifiableList(transports); }

	static boolean isRing(Transport transport)
	{
		return transport != null && "Configure".equals(transport.action) && "Fairy ring".equals(transport.target);
	}

	boolean available(Client client, Transport transport)
	{
		String code = codes.get(transport.landing);
		return code != null && networkAvailable(client.getWorldType().contains(WorldType.MEMBERS), client.getVarbitValue(VarbitID.FAIRYRING_PERMISSION),
			client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE), staffEquipped(), staffCarried())
			&& destinationAvailable(code, client::getVarbitValue);
	}

	Map<Integer, Integer> bankSupplies(Client client, Transport transport, Map<Integer, Integer> stock)
	{
		String code = codes.get(transport.landing);
		if (code == null || !client.getWorldType().contains(WorldType.MEMBERS)
			|| client.getVarbitValue(VarbitID.FAIRYRING_PERMISSION) != 2
			|| !destinationAvailable(code, client::getVarbitValue))
		{
			return null;
		}
		if (client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE) == 1
			|| staffEquipped() || staffCarried())
		{
			return Collections.emptyMap();
		}
		for (int id : STAFFS)
		{
			if (stock.getOrDefault(id, 0) > 0) { return Map.of(id, 1); }
		}
		return null;
	}

	static boolean networkAvailable(boolean members, int permission, int diary, boolean equipped, boolean carried)
	{
		return members && permission == 2 && (diary == 1 || equipped || carried);
	}

	static boolean destinationAvailable(String code, IntUnaryOperator varbits)
	{
		if (!validCode(code) || "DIQ".equals(code)) { return false; }
		if (KOUREND.contains(code)) { return varbits.applyAsInt(VarbitID.ZEAH_PLAYERHASVISITED) == 1; }
		if (VARLAMORE.contains(code)) { return varbits.applyAsInt(VarbitID.VARLAMORE_VISITED) == 1; }
		// Require the completed Necropolis arrival cutscene, not a previous ring teleport.
		if ("AKP".equals(code)) { return varbits.applyAsInt(VarbitID.BCS) >= 14; }
		// Giving Floopa the bandages unlocks access before Troubled Tortugans is complete.
		if ("CJQ".equals(code)) { return varbits.applyAsInt(VarbitID.TT) >= 6; }
		return true;
	}

	static boolean staffEquipped()
	{
		return Rs2Equipment.isWearing("Dramen staff", true) || Rs2Equipment.isWearing("Lunar staff", true);
	}

	static int nextBankStaff(int emptySlots, boolean members, int permission, int diary,
		boolean equipped, IntUnaryOperator inventoryQuantity, IntUnaryOperator bankQuantity, Set<Integer> rejected)
	{
		if (emptySlots <= 0 || diary == 1 || equipped
			|| !networkAvailable(members, permission, diary, false, true)) { return -1; }
		for (int id : STAFFS)
		{
			if (inventoryQuantity.applyAsInt(id) > 0) { return -1; }
		}
		for (int id : STAFFS)
		{
			if (bankQuantity.applyAsInt(id) > 0 && !rejected.contains(id)) { return id; }
		}
		return -1;
	}

	private static boolean staffCarried()
	{
		return Rs2Inventory.count("Dramen staff", true) > 0 || Rs2Inventory.count("Lunar staff", true) > 0;
	}

	static String missingStaff(Client client)
	{
		if (client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE) == 1
			|| LocalTransportCatalog.equipmentItem(client, net.runelite.api.InventoryID.EQUIPMENT, "Dramen staff") != null
			|| LocalTransportCatalog.equipmentItem(client, net.runelite.api.InventoryID.EQUIPMENT, "Lunar staff") != null) { return null; }
		return LocalTransportCatalog.equipmentItem(client, net.runelite.api.InventoryID.INVENTORY, "Dramen staff") != null
			? "Dramen staff" : "Lunar staff";
	}

	void reset()
	{
		pendingChild = 0;
	}

	// Returns one permitted input, zero while awaiting its effect, or -1 for unsupported destination data.
	int nextAction(Client client, Transport transport)
	{
		if (pendingChild == 26) { return 0; }
		String code = codes.get(transport.landing);
		if (!validCode(code)) { return -1; }
		int[] values = {client.getVarbitValue(VARBITS[0]), client.getVarbitValue(VARBITS[1]), client.getVarbitValue(VARBITS[2])};
		if (pendingChild != 0)
		{
			if (values[(pendingChild - 19) / 2] != expectedDialValue) { return 0; }
			pendingChild = 0;
		}
		int child = nextDial(code, values);
		if (child < 0) { return 0; }
		Widget widget = client.getWidget(398, child);
		if (!permitted(client, widget)) { return 0; }
		pendingChild = child;
		if (child != 26)
		{
			expectedDialValue = (values[(child - 19) / 2] + (child % 2 == 1 ? 1 : 3)) % 4;
		}
		return child;
	}

	static boolean permitted(Client client, Widget widget)
	{
		if (widget == null || widget.isHidden() || widget.getActions() == null
			|| widget.getActions().length == 0 || widget.getActions()[0] == null) { return false; }
		WidgetConfigNode permission = client.getWidgetConfig(widget);
		return permission == null ? (widget.getClickMask() & 2) != 0 : (permission.getOpMask() & 1) != 0;
	}

	static boolean validCode(String code)
	{
		return code != null && code.length() == 3 && DIALS[0].indexOf(code.charAt(0)) >= 0
			&& DIALS[1].indexOf(code.charAt(1)) >= 0 && DIALS[2].indexOf(code.charAt(2)) >= 0;
	}

	static int nextDial(String code, int[] values)
	{
		if (!validCode(code) || values.length != 3) { return -1; }
		for (int i = 0; i < 3; i++) { if (values[i] < 0 || values[i] > 3) { return -1; } }
		for (int i = 0; i < 3; i++)
		{
			int difference = (DIALS[i].indexOf(code.charAt(i)) - values[i] + 4) % 4;
			if (difference != 0) { return 19 + i * 2 + (difference == 3 ? 1 : 0); }
		}
		return 26;
	}
}
