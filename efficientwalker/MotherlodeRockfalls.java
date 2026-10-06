package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.coords.WorldPoint;

final class MotherlodeRockfalls
{
	// Cache bridge placements are on runtime plane zero, including the upper level.
	static final Set<WorldPoint> TILES = Set.of(
		new WorldPoint(3719, 5664, 0),
		new WorldPoint(3726, 5654, 0),
		new WorldPoint(3727, 5683, 0),
		new WorldPoint(3728, 5688, 0),
		new WorldPoint(3733, 5680, 0),
		new WorldPoint(3748, 5689, 0),
		new WorldPoint(3759, 5690, 0),
		new WorldPoint(3762, 5652, 0),
		new WorldPoint(3762, 5687, 0),
		new WorldPoint(3766, 5670, 0),
		new WorldPoint(3768, 5674, 0),
		new WorldPoint(3768, 5679, 0),
		new WorldPoint(3769, 5642, 0),
		new WorldPoint(3770, 5659, 0),
		new WorldPoint(3748, 5684, 0),
		new WorldPoint(3757, 5677, 0),
		new WorldPoint(3720, 5665, 0),
		new WorldPoint(3726, 5643, 0),
		new WorldPoint(3727, 5652, 0),
		new WorldPoint(3728, 5651, 0),
		new WorldPoint(3731, 5683, 0),
		new WorldPoint(3744, 5640, 0),
		new WorldPoint(3745, 5689, 0),
		new WorldPoint(3755, 5640, 0),
		new WorldPoint(3756, 5639, 0),
		new WorldPoint(3765, 5688, 0),
		new WorldPoint(3766, 5639, 0),
		new WorldPoint(3766, 5647, 0),
		new WorldPoint(3769, 5658, 0),
		new WorldPoint(3769, 5680, 0),
		new WorldPoint(3771, 5638, 0),
		new WorldPoint(3762, 5668, 0));
	private static final Set<String> PICKAXES = Set.of(
		"Bronze pickaxe", "Iron pickaxe", "Steel pickaxe", "Black pickaxe", "Mithril pickaxe",
		"Adamant pickaxe", "Rune pickaxe", "Gilded pickaxe", "Dragon pickaxe", "Dragon pickaxe (or)",
		"Dragon pickaxe (upgraded)", "3rd age pickaxe", "Infernal pickaxe", "Infernal pickaxe (or)",
		"Infernal pickaxe (uncharged)", "Infernal pickaxe (or) (uncharged)", "Crystal pickaxe");

	private MotherlodeRockfalls() { }

	static boolean isRockfall(int id) { return id == 26679 || id == 26680; }

	static boolean contains(int x, int y, int plane)
	{
		return plane == 0 && x >= 3719 && x <= 3771 && y >= 5638 && y <= 5690
			&& TILES.contains(new WorldPoint(x, y, plane));
	}

	static boolean hasPickaxe(Client client)
	{
		return hasPickaxe(client, client.getItemContainer(InventoryID.INVENTORY))
			|| hasPickaxe(client, client.getItemContainer(InventoryID.EQUIPMENT));
	}

	private static boolean hasPickaxe(Client client, ItemContainer container)
	{
		if (container == null || container.getItems() == null) { return false; }
		for (Item item : container.getItems())
		{
			if (item == null || item.getId() < 0 || item.getQuantity() <= 0) { continue; }
			ItemComposition definition = client.getItemDefinition(item.getId());
			if (definition != null && definition.getNote() == -1
				&& definition.getPlaceholderTemplateId() == -1 && PICKAXES.contains(definition.getName())) { return true; }
		}
		return false;
	}
}
