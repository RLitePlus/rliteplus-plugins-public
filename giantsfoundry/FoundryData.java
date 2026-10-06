package net.runelite.client.plugins.microbot.giantsfoundry;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;

final class FoundryData
{
	static final class ValidationException extends IllegalArgumentException
	{
		ValidationException(String message)
		{
			super(message);
		}
	}

	static final int BUCKET = 1925;
	static final int WATER_BUCKET = 1929;
	static final int PREFORM = 27010;
	static final int ICE_GLOVES = 1580;
	static final int SMITHS_ICE_GLOVES = 27031;
	static final int[] BARS = {2349, 2351, 2353, 2359, 2361, 2363};
	static final String[] BAR_NAMES = {"Bronze bar", "Iron bar", "Steel bar", "Mithril bar", "Adamantite bar", "Runite bar"};

	static int[] recipe(GiantsFoundryConfig config)
	{
		GiantsFoundryConfig.Alloy alloy = config.alloy();
		if (alloy == null) throw new ValidationException("Select an alloy recipe in the plugin settings, then restart.");
		int[] result = new int[6];
		result[alloy.firstMetal] = alloy.firstCount;
		result[alloy.secondMetal] += 28 - alloy.firstCount;
		return result;
	}

	static int[] deficits(int[] recipe, int[] crucible)
	{
		if (recipe == null || recipe.length != 6 || crucible.length != 6
			|| Arrays.stream(recipe).anyMatch(count -> count < 0 || count > 28)
			|| Arrays.stream(recipe).sum() != 28)
		{
			throw new ValidationException("Could not read the recipe or crucible contents. Check both before restarting.");
		}
		int[] result = new int[6];
		for (int i = 0; i < 6; i++)
		{
			if (crucible[i] < 0 || crucible[i] > recipe[i])
			{
				throw new ValidationException("The crucible contains metals that do not match your selected recipe. Its contents were preserved. Select a compatible recipe before restarting.");
			}
			result[i] = recipe[i] - crucible[i];
		}
		return result;
	}

	static String missingBars(int[] deficits, Map<Integer, Integer> inventory, Map<Integer, Integer> bank)
	{
		java.util.List<String> missing = new java.util.ArrayList<>();
		for (int metal = 0; metal < BARS.length; metal++)
		{
			int count = deficits[metal] - inventory.getOrDefault(BARS[metal], 0) - bank.getOrDefault(BARS[metal], 0);
			if (count > 0) missing.add(count + " " + BAR_NAMES[metal] + (count == 1 ? "" : "s"));
		}
		return "Missing " + String.join(", ", missing) + ". Add them to your bank, then restart. Crucible contents were preserved.";
	}

	static int requiredLevel(int[] deficits)
	{
		int[] levels = {1, 15, 30, 50, 70, 85};
		int required = 1;
		for (int i = 0; i < 6; i++) if (deficits[i] > 0) required = Math.max(required, levels[i]);
		return required;
	}

	static int mouldScore(int[] attributes, int firstWord, int secondWord)
	{
		if (firstWord < 1 || firstWord > 6 || secondWord < 1 || secondWord > 6)
		{
			throw new ValidationException("Could not read Kovac's commission. Check the commission before restarting.");
		}
		int score = 0;
		for (int i = 0; i < attributes.length; i++)
		{
			int word = i + (attributes[i] < 0 ? 1 : 4);
			if (word == firstWord || word == secondWord)
			{
				score += Math.abs(attributes[i]);
			}
		}
		return score;
	}

	enum Intent { HEAT, COOL, DUNK, QUENCH, HAMMER, GRIND, POLISH, FINISHED }

	static int sections(int difficulty)
	{
		if (difficulty < 0 || difficulty > 130) throw new ValidationException("Could not read the sword's difficulty. Check the sword's status before restarting. If this repeats, report the debug log.");
		return difficulty < 20 ? 3 : difficulty < 60 ? 4 : difficulty < 90 ? 5 : difficulty < 120 ? 6 : 7;
	}

	static int[] heatBand(int difficulty, int tool)
	{
		sections(difficulty);
		if (tool < 0 || tool > 2) throw new ValidationException("Could not identify the required workstation. Check the Foundry progress display before restarting.");
		int width = 333 - difficulty * 167 / 130;
		int low = 166 + 333 * (2 - tool) - width / 2;
		return new int[]{low, low + width};
	}

	static Intent refinement(int difficulty, int progress, int[] seeds, int heat, Intent previous)
	{
		if (progress < 0 || progress > 1000 || heat < 0 || heat > 1000 || seeds.length != 6)
			throw new ValidationException("Could not read the sword's refinement stage. Check the Foundry progress display before restarting.");
		int count = sections(difficulty);
		if (progress == 1000) return Intent.FINISHED;
		int section = progress * count / 1000;
		int tool = section == 0 ? 0 : seeds[section - 1];
		int[] band = heatBand(difficulty, tool);
		int target = tool == 1 ? band[0] + 60 : band[1] - 60;
		if (previous == Intent.HEAT && heat < target) return Intent.HEAT;
		if (previous == Intent.COOL && heat > target) return Intent.COOL;
		Intent heating = target - heat > 150 ? Intent.DUNK : Intent.HEAT;
		Intent cooling = heat - target > 150 ? Intent.QUENCH : Intent.COOL;
		if (previous == Intent.DUNK && heat < target) return heating;
		if (previous == Intent.QUENCH && heat > target) return cooling;
		if (heat < band[0] + 45) return heating;
		if (heat > band[1] - 35) return cooling;
		return new Intent[]{Intent.HAMMER, Intent.GRIND, Intent.POLISH}[tool];
	}

	static boolean changingActivity(String previous, String next)
	{
		return !java.util.Objects.equals(previous, next) && java.util.Set.of(
			"Use:44619", "Use:44620", "Use:44621", "Heat-preform:44631", "Cool-preform:44632",
			"Dunk-preform:44631", "Quench-preform:44632").contains(previous == null ? "" : previous);
	}

	static int activityCadence(String action)
	{
		return action == null || "Use:44619".equals(action) ? 5 : 2;
	}

	static boolean activitySettled(int elapsedTicks, int animation, int progressChange, int qualityChange, int heatChange)
	{
		return elapsedTicks == 1 && animation == -1 && progressChange == 0 && qualityChange == 0 && Math.abs(heatChange) <= 1;
	}

	static int sweetSpotState(net.runelite.api.widgets.Widget layer)
	{
		if (layer == null || layer.isHidden()) return -1;
		net.runelite.api.widgets.Widget border = layer.getChild(0);
		if (border == null) return 0;
		if (border.isHidden()) return -1;
		return border.getTextColor() == 0xfcd703 ? 1 : border.getTextColor() == 0x00dd00 ? 2 : -1;
	}

	static boolean bonusAvailable(int state, boolean attempted, boolean sameMachine, int ticksSinceProgress)
	{
		return state == 1 && !attempted && sameMachine && ticksSinceProgress >= 0 && ticksSinceProgress <= 5;
	}

	static boolean bonusApplied(boolean pending, boolean accepted, int progressGain, boolean qualityPreserved)
	{
		return pending && accepted && progressGain >= 50 && qualityPreserved;
	}

	static boolean stoppedSafely(boolean equipped, boolean stored, boolean pickupPending, boolean expectedPreform, int settledTicks)
	{
		return !equipped && !pickupPending && (stored || !expectedPreform && settledTicks >= 3);
	}

	static boolean handedIn(boolean armed, boolean equipped, boolean stored, int xp, int baselineXp,
		int points, int baselinePoints)
	{
		return armed && !equipped && !stored && xp > baselineXp && points > baselinePoints;
	}

	static boolean permits(net.runelite.api.widgets.Widget widget, String name, String action)
	{
		return widget != null && !widget.isHidden()
			&& name.equalsIgnoreCase(net.runelite.client.util.Text.removeTags(widget.getName()))
			&& widget.getActions() != null && Arrays.asList(widget.getActions()).contains(action);
	}

	static int selectableMould(net.runelite.api.widgets.Widget[] children, String name)
	{
		if (children == null || name == null) return -1;
		for (net.runelite.api.widgets.Widget child : children)
		{
			if (child != null && name.equalsIgnoreCase(net.runelite.client.util.Text.removeTags(child.getName()))
				&& child.getActions() != null && Arrays.asList(child.getActions()).contains("Select"))
			{
				return child.getIndex();
			}
		}
		return -1;
	}

	static final class Snapshot
	{
		final int tick;
		final boolean loggedIn;
		final String setupError;
		final WorldPoint position;
		final int animation;
		final int smithingXp;
		final int smithingLevel;
		final int reputation;
		final int sweetSpot;
		final int bonusAcceptedTick;
		final int[] bits;
		final Map<Integer, Integer> inventory;
		final Map<Integer, Integer> bank;
		final int occupiedSlots;
		final int weapon;
		final int shield;
		final int gloves;
		final boolean bankOpen;
		final int bankEpoch;
		final int[] bestMould = new int[3];
		final String[] bestMouldName = new String[3];

		Snapshot(Client client, int bonusAcceptedTick)
		{
			this.bonusAcceptedTick = bonusAcceptedTick;
			sweetSpot = sweetSpotState(client.getWidget(net.runelite.api.gameval.InterfaceID.GiantsFoundryHud.SWEETSPOT_LAYER));
			tick = client.getTickCount();
			loggedIn = client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null;
			setupError = !loggedIn ? "Log in on a normal members world, then restart."
				: !client.getWorldType().contains(WorldType.MEMBERS) ? "Giants' Foundry requires a members world. Switch worlds, then restart."
				: client.getWorldType().contains(WorldType.SEASONAL) || client.getWorldType().contains(WorldType.DEADMAN)
					? "This world type is unsupported. Switch to a normal members world, then restart."
				: Quest.SLEEPING_GIANTS.getState(client) != QuestState.FINISHED ? "Complete Sleeping Giants before starting this plugin." : null;
			position = loggedIn ? client.getLocalPlayer().getWorldLocation() : null;
			animation = loggedIn ? client.getLocalPlayer().getAnimation() : -1;
			smithingXp = client.getSkillExperience(Skill.SMITHING);
			smithingLevel = client.getBoostedSkillLevel(Skill.SMITHING);
			reputation = client.getVarpValue(3436);
			bits = new int[49];
			for (int i = 0; i < bits.length; i++)
			{
				bits[i] = client.getVarbitValue(13902 + i);
			}
			ItemContainer carried = client.getItemContainer(InventoryID.INV);
			inventory = counts(carried);
			occupiedSlots = carried == null ? 0 : (int) Arrays.stream(carried.getItems())
				.filter(item -> item != null && item.getId() > 0).count();
			ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
			weapon = equipped(equipment, EquipmentInventorySlot.WEAPON);
			shield = equipped(equipment, EquipmentInventorySlot.SHIELD);
			gloves = equipped(equipment, EquipmentInventorySlot.GLOVES);
			bankOpen = Rs2Bank.isOpen();
			bankEpoch = bankOpen && client.getItemContainer(InventoryID.BANK) != null ? Rs2Bank.getBankLiveEpoch() : -1;
			bank = bankOpen ? counts(client.getItemContainer(InventoryID.BANK)) : Map.of();
			if (bit(13907) > 0 && bit(13908) > 0)
			{
				int part = bit(13909);
				if (part >= 0 && part < 3)
				{
					int bestScore = -1;
					for (int index : client.getEnum(4373 + part).getKeys())
					{
						int structId = client.getEnum(4373 + part).getIntValue(index);
						if (structId < 0)
						{
							continue;
						}
						StructComposition mould = client.getStructComposition(structId);
						net.runelite.api.widgets.Widget content = client.getWidget(
							net.runelite.api.gameval.InterfaceID.GiantsFoundryMould.CONTENT);
						boolean available = content != null && !content.isHidden()
							&& (selectableMould(content.getChildren(), mould.getStringValue(1621)) >= 0
								|| bit(13910 + part) == index);
						if (!available) continue;
						if (mould.getIntValue(1622) > client.getBoostedSkillLevel(Skill.SMITHING))
						{
							continue;
						}
						int score = mouldScore(new int[]{mould.getIntValue(1625),
							mould.getIntValue(1626), mould.getIntValue(1627)}, bit(13907), bit(13908));
						if (score > bestScore)
						{
							bestScore = score;
							bestMould[part] = index;
							bestMouldName[part] = mould.getStringValue(1621);
						}
					}
				}
			}
		}

		int bit(int id) { return bits[id - 13902]; }
		int count(int id) { return inventory.getOrDefault(id, 0); }
		int[] crucible() { return Arrays.copyOfRange(bits, 29, 35); }
		boolean hasPreform() { return weapon == PREFORM; }
		boolean coolingGloves() { return gloves == ICE_GLOVES || gloves == SMITHS_ICE_GLOVES; }
		boolean inside() { return position != null && position.getPlane() == 0
			&& position.getX() >= 3340 && position.getX() <= 3390
			&& position.getY() >= 11470 && position.getY() <= 11530; }

		long signature()
		{
			return Arrays.hashCode(bits) * 31L + inventory.hashCode() * 7L
				+ weapon * 3L + shield + gloves + bankEpoch;
		}
	}

	private static int equipped(ItemContainer items, EquipmentInventorySlot slot)
	{
		Item item = items == null ? null : items.getItem(slot.getSlotIdx());
		return item == null ? -1 : item.getId();
	}

	private static Map<Integer, Integer> counts(ItemContainer items)
	{
		Map<Integer, Integer> result = new HashMap<>();
		if (items != null)
		{
			for (Item item : items.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					result.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		return Map.copyOf(result);
	}
}
