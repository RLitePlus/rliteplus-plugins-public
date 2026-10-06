package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ObjectComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

final class QuestItemUse
{
	final int id;
	final int slot;
	final String name;

	QuestItemUse(int id, int slot, String name)
	{
		this.id = id;
		this.slot = slot;
		this.name = name;
	}

	static String instruction(Object step)
	{
		try
		{
			Object text = QuestStepTarget.call(step, "getText");
			if (text instanceof java.util.List && !((java.util.List<?>) text).isEmpty())
			{
				return Text.removeTags(String.valueOf(((java.util.List<?>) text).get(0))).trim();
			}
		}
		catch (ReflectiveOperationException exception)
		{
			throw manual("Cannot read the current instruction");
		}
		return "";
	}

	static QuestItemUse resolve(Client client, QuestStepTarget.Target target)
	{
		String text = instruction(target.step);
		if (!Pattern.compile("\\buse\\s+.+?\\s+(on|with)\\s+", Pattern.CASE_INSENSITIVE).matcher(text).find()) { return null; }
		Matcher clause = Pattern.compile("(?:^|[,;.!]\\s*(?:and\\s+)?)(?:right[- ]click\\s+)?use\\s+(.+?)\\s+(?:on|with)\\s+(.+?)(?=[,;.!]|$)", Pattern.CASE_INSENSITIVE).matcher(text);
		if (!clause.find()) { throw manual("The item-use instruction includes another action or target"); }
		String itemPhrase = normalize(clause.group(1));
		String targetPhrase = normalize(clause.group(2).replaceFirst("(?i)\\s+(?:and|then)\\s+.*$", ""));
		boolean alternatives = Pattern.compile("\\b(or|either)\\b|[,;]", Pattern.CASE_INSENSITIVE).matcher(clause.group(1)).find();
		if (Pattern.compile("\\b(and|then|all)\\b", Pattern.CASE_INSENSITIVE).matcher(clause.group(1)).find()
			|| Pattern.compile("\\b(or|all)\\b|[,;]", Pattern.CASE_INSENSITIVE).matcher(clause.group(2)).find())
		{
			throw manual("This instruction names multiple items or actions");
		}
		String targetName = target.liveNpc == null ? null : target.liveNpc.getName();
		if (target.liveObject != null)
		{
			ObjectComposition def = client.getObjectDefinition(target.liveObject.getId());
			if (def != null && def.getImpostorIds() != null) { def = def.getImpostor(); }
			targetName = def == null ? null : def.getName();
		}
		if (targetName == null || !targetPhrase.equals("it") && !contains(targetPhrase, normalize(targetName))
			&& !contains(normalize(targetName), targetPhrase))
		{
			throw manual("The instruction's item-use target does not match the highlighted target");
		}
		if (alternatives)
		{
			QuestItemUse highlighted = resolveHighlighted(client, target.step);
			if (highlighted != null) { return highlighted; }
			throw manual("This instruction names multiple inventory items without one highlighted choice");
		}
		return resolveItem(client, target.step, itemPhrase, true);
	}

	static QuestItemUse resolveHighlighted(Client client, Object step)
	{
		try
		{
			Collection<?> requirements = (Collection<?>) QuestStepTarget.call(step, "getRequirements");
			int icon = (int) QuestDialogue.field(step, "iconItemID");
			String highlightedName = null;
			for (Object requirement : requirements)
			{
				if (!requirement.getClass().getSimpleName().equals("ItemRequirement")
					|| !Boolean.TRUE.equals(QuestStepTarget.call(requirement, "isActualItem"))
					|| Boolean.TRUE.equals(QuestStepTarget.call(requirement, "mustBeEquipped"))) { continue; }
				Collection<?> ids = (Collection<?>) QuestStepTarget.call(requirement, "getAllIds");
				if (!ids.contains(icon) && !Boolean.TRUE.equals(requirement.getClass()
					.getMethod("shouldHighlightInInventory", Client.class).invoke(requirement, client))) { continue; }
				String name = String.valueOf(QuestStepTarget.call(requirement, "getName"));
				if (highlightedName != null) { throw manual("Multiple inventory items are highlighted"); }
				highlightedName = name;
			}
			return highlightedName == null ? null : resolveItem(client, step, highlightedName, true);
		}
		catch (ReflectiveOperationException | ClassCastException exception)
		{
			throw manual("Cannot read compatible Quest Helper item metadata");
		}
	}

	static String highlightedSignature(Client client, Object step)
	{
		try
		{
			TreeSet<Integer> ids = new TreeSet<>();
			for (Object requirement : (Collection<?>) QuestStepTarget.call(step, "getRequirements"))
			{
				if (requirement.getClass().getSimpleName().equals("ItemRequirement")
					&& Boolean.TRUE.equals(requirement.getClass()
						.getMethod("shouldHighlightInInventory", Client.class).invoke(requirement, client)))
				{
					for (Object id : (Collection<?>) QuestStepTarget.call(requirement, "getAllIds"))
					{
						ids.add(((Number) id).intValue());
					}
				}
			}
			return ids.toString();
		}
		catch (ReflectiveOperationException | ClassCastException exception)
		{
			throw manual("Cannot read compatible Quest Helper item metadata");
		}
	}

	static QuestItemUse resolveItem(Client client, Object step, String phrase, boolean requireHighlight)
	{
		String itemPhrase = normalize(phrase);
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null) { throw manual("Cannot read inventory"); }
		try
		{
			Collection<?> requirements = (Collection<?>) QuestStepTarget.call(step, "getRequirements");
			int icon = (int) QuestDialogue.field(step, "iconItemID");
			Map<Integer, QuestItemUse> matches = new LinkedHashMap<>();
			boolean namedRequirement = false;
			for (Object requirement : requirements)
			{
				if (!requirement.getClass().getSimpleName().equals("ItemRequirement")) { continue; }
				if (!Boolean.TRUE.equals(QuestStepTarget.call(requirement, "isActualItem"))
					|| Boolean.TRUE.equals(QuestStepTarget.call(requirement, "mustBeEquipped"))) { continue; }
				if (!Boolean.TRUE.equals(requirement.getClass().getMethod("shouldRenderItemHighlights", Client.class).invoke(requirement, client))) { continue; }
				Collection<?> ids = (Collection<?>) QuestStepTarget.call(requirement, "getAllIds");
				String name = String.valueOf(QuestStepTarget.call(requirement, "getName"));
				boolean named = containsItem(itemPhrase, normalize(name)) || containsItem(normalize(name), itemPhrase);
				for (Object value : ids)
				{
					int id = ((Number) value).intValue();
					if (id < 0) { continue; }
					ItemComposition definition = client.getItemDefinition(id);
					if (definition != null) { named |= containsItem(itemPhrase, normalize(definition.getName())); }
				}
				boolean highlighted = Boolean.TRUE.equals(requirement.getClass()
					.getMethod("shouldHighlightInInventory", Client.class).invoke(requirement, client));
				if (!named || (requireHighlight && !highlighted && !ids.contains(icon))) { continue; }
				namedRequirement = true;
				int quantity = ((Number) QuestStepTarget.call(requirement, "getQuantity")).intValue();
				if (quantity < 1) { throw manual("The required item quantity is unclear"); }
				int total = 0;
				for (Item item : inventory.getItems()) { if (item != null && ids.contains(item.getId())) { total += item.getQuantity(); } }
				if (total < quantity) { throw manual("Need " + (quantity - total) + " more " + name + " in inventory"); }
				for (int slot = 0; slot < inventory.getItems().length; slot++)
				{
					Item item = inventory.getItem(slot);
					if (item != null && ids.contains(item.getId()) && item.getQuantity() > 0)
					{
						ItemComposition definition = client.getItemDefinition(item.getId());
						if (definition != null && definition.getNote() == -1)
						{
							matches.putIfAbsent(item.getId(), new QuestItemUse(item.getId(), slot, definition.getName()));
						}
					}
				}
			}
			if (requireHighlight && requirements.isEmpty() && icon >= 0)
			{
				ItemComposition definition = client.getItemDefinition(icon);
				if (definition != null && definition.getNote() == -1 && itemPhrase.equals(normalize(definition.getName())))
				{
					for (int slot = 0; slot < inventory.getItems().length; slot++)
					{
						Item item = inventory.getItem(slot);
						if (item != null && item.getId() == icon && item.getQuantity() > 0)
						{
							matches.putIfAbsent(icon, new QuestItemUse(icon, slot, definition.getName()));
						}
					}
				}
			}
			if (matches.size() != 1) { throw manual(namedRequirement ? "Cannot choose one supported inventory item" : "Item metadata does not uniquely support the instruction"); }
			return matches.values().iterator().next();
		}
		catch (ReflectiveOperationException | ClassCastException exception)
		{
			throw manual("Cannot read compatible Quest Helper item metadata");
		}
	}

	static final class InventoryAction
	{
		final QuestItemUse source;
		final QuestItemUse target;
		final String action;

		InventoryAction(QuestItemUse source, QuestItemUse target, String action)
		{
			this.source = source;
			this.target = target;
			this.action = action;
		}
	}

	static boolean isDigStep(Object step)
	{
		for (Class<?> type = step.getClass(); type != null; type = type.getSuperclass())
		{
			if (type.getSimpleName().equals("DigStep")) { return true; }
		}
		return false;
	}

	static InventoryAction inventoryAction(Client client, Object step)
	{
		if (isDigStep(step)) { return new InventoryAction(resolveItem(client, step, "Spade", false), null, "Dig"); }
		String text = instruction(step).replaceFirst("(?i)^right[- ]click\\s+", "");
		if (Pattern.compile("\\b(and|or|then|all)\\b|[,;]", Pattern.CASE_INSENSITIVE).matcher(text).find())
		{
			throw manual("This inventory instruction contains multiple items or actions");
		}
		Matcher pair = Pattern.compile("^(?:use\\s+(.+?)\\s+on|combine\\s+(.+?)\\s+with|put\\s+(.+?)\\s+into)\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE).matcher(text);
		if (pair.matches())
		{
			String sourceName = pair.group(1) != null ? pair.group(1) : pair.group(2) != null ? pair.group(2) : pair.group(3);
			QuestItemUse source = resolveItem(client, step, sourceName, false);
			QuestItemUse target = resolveItem(client, step, pair.group(4), false);
			if (source.slot == target.slot) { throw manual("The two inventory items resolve to the same slot"); }
			return new InventoryAction(source, target, "Use");
		}
		InventoryAction highlightedPair = highlightedPair(client, step, text);
		if (highlightedPair != null) { return highlightedPair; }
		Matcher burn = Pattern.compile("^burn\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE).matcher(text);
		if (burn.matches())
		{
			return new InventoryAction(resolveItem(client, step, "Tinderbox", false),
				resolveItem(client, step, burn.group(1), false), "Use");
		}
		Matcher single = Pattern.compile("^([a-z]+(?:-[a-z]+)?)\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE).matcher(text);
		if (!single.matches() || single.group(1).equalsIgnoreCase("Use")) { throw manual("Unsupported inventory instruction"); }
		QuestItemUse item = resolveItem(client, step, single.group(2), false);
		return new InventoryAction(item, null, single.group(1));
	}

	private static InventoryAction highlightedPair(Client client, Object step, String text)
	{
		if (!Pattern.compile("^(create|make|mix)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) { return null; }
		try
		{
			Map<Integer, QuestItemUse> matches = new LinkedHashMap<>();
			for (Object requirement : (Collection<?>) QuestStepTarget.call(step, "getRequirements"))
			{
				if (!requirement.getClass().getSimpleName().equals("ItemRequirement")
					|| !Boolean.TRUE.equals(QuestStepTarget.call(requirement, "isActualItem"))
					|| Boolean.TRUE.equals(QuestStepTarget.call(requirement, "mustBeEquipped"))
					|| !Boolean.TRUE.equals(requirement.getClass()
						.getMethod("shouldHighlightInInventory", Client.class).invoke(requirement, client))) { continue; }
				QuestItemUse item = resolveItem(client, step, String.valueOf(QuestStepTarget.call(requirement, "getName")), true);
				matches.putIfAbsent(item.slot, item);
			}
			if (matches.size() != 2) { return null; }
			QuestItemUse[] items = matches.values().toArray(new QuestItemUse[0]);
			return new InventoryAction(items[0], items[1], "Use");
		}
		catch (ReflectiveOperationException | ClassCastException exception)
		{
			throw manual("Cannot read compatible Quest Helper item metadata");
		}
	}

	boolean isSelected(Client client)
	{
		Widget selected = client.getSelectedWidget();
		return client.isWidgetSelected() && selected != null && selected.getId() == InterfaceID.Inventory.ITEMS
			&& selected.getIndex() == slot && selected.getItemId() == id;
	}

	void cancel(Client client)
	{
		if (isSelected(client)) { client.setWidgetSelected(false); }
	}

	private static boolean containsItem(String text, String name)
	{
		if (contains(text, name)) { return true; }
		if (name.length() < 3 || name.endsWith("ss")) { return false; }
		return contains(text, name.endsWith("s") ? name.substring(0, name.length() - 1) : name + "s");
	}

	private static boolean contains(String text, String name)
	{
		return !name.isEmpty() && (" " + text + " ").contains(" " + name + " ");
	}

	private static String normalize(String text)
	{
		return text == null ? "" : Text.removeTags(text).toLowerCase(Locale.ROOT)
			.replaceAll("\\([^)]*\\)", " ").replace('-', ' ').replaceAll("\\b(the|a|an|your|some)\\b", " ")
			.replaceAll("\\s+", " ").trim();
	}

	private static IllegalStateException manual(String reason)
	{
		return new IllegalStateException(reason + ". Follow Quest Helper manually, then press Walk to quest step to resume.");
	}
}
