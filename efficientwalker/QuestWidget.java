package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfigNode;

final class QuestWidget
{
	final Widget widget;
	final int operation;
	final String action;

	private QuestWidget(Widget widget, int operation, String action)
	{
		this.widget = widget;
		this.operation = operation;
		this.action = action;
	}

	String key()
	{
		return widget.getId() + ":" + widget.getIndex() + ":" + widget.getItemId() + ":" + operation + ":" + action;
	}

	static QuestWidget resolve(Client client, Object step)
	{
		try
		{
			Map<String, QuestWidget> matches = new LinkedHashMap<>();
			for (Object rule : (List<?>) QuestStepTarget.call(step, "getWidgetsToHighlight"))
			{
				if (!rule.getClass().getSimpleName().equals("WidgetHighlight")) { continue; }
				Widget root = client.getWidget((int) QuestStepTarget.call(rule, "getInterfaceID"));
				if (root == null || root.isHidden()) { continue; }
				int child = (int) QuestStepTarget.call(rule, "getChildChildId");
				Widget selected = child < 0 ? root : root.getChild(child);
				collect(client, rule, selected, root, (boolean) QuestDialogue.field(rule, "checkChildren"), matches);
			}
			if (matches.size() > 1) { throw manual("More than one highlighted interface option is actionable"); }
			return matches.isEmpty() ? null : matches.values().iterator().next();
		}
		catch (ReflectiveOperationException | ClassCastException exception)
		{
			throw manual("Cannot read Quest Helper's interface highlights");
		}
	}

	private static void collect(Client client, Object rule, Widget widget, Widget root, boolean descendants,
		Map<String, QuestWidget> matches) throws ReflectiveOperationException
	{
		if (widget == null || widget.isHidden()) { return; }
		Integer item = (Integer) QuestStepTarget.call(rule, "getItemIdRequirement");
		Integer model = (Integer) QuestStepTarget.call(rule, "getModelIdRequirement");
		String text = (String) QuestStepTarget.call(rule, "getRequiredText");
		String name = (String) QuestStepTarget.call(rule, "getNameToCheckFor");
		if ((item == null || item == widget.getItemId()) && (model == null || model == widget.getModelId())
			&& (text == null || widget.getText() != null && widget.getText().contains(text))
			&& (name == null || widget.getName() != null && widget.getName().contains(name)))
		{
			Widget target = widget;
			while (target != null && !target.isHidden())
			{
				QuestWidget choice = actionable(client, target);
				if (choice != null)
				{
					matches.putIfAbsent(choice.key(), choice);
					break;
				}
				if (target == root) { break; }
				target = target.getParent();
			}
		}
		if (descendants)
		{
			Widget[][] children = {widget.getChildren(), widget.getStaticChildren()};
			for (Widget[] group : children)
			{
				if (group == null) { continue; }
				for (Widget child : group) { collect(client, rule, child, root, true, matches); }
			}
		}
	}

	private static QuestWidget actionable(Client client, Widget widget)
	{
		String[] actions = widget.getActions();
		if (actions == null) { return null; }
		WidgetConfigNode permission = client.getWidgetConfig(widget);
		int mask = permission == null ? widget.getClickMask() >>> 1 : permission.getOpMask();
		QuestWidget match = null;
		for (int i = 0; i < Math.min(10, actions.length); i++)
		{
			if (actions[i] == null || actions[i].trim().isEmpty()
				|| (mask & (1 << i)) == 0 && widget.getOnOpListener() == null) { continue; }
			if (match != null) { throw manual("The highlighted interface option has multiple permitted actions"); }
			match = new QuestWidget(widget, i + 1, actions[i]);
		}
		return match;
	}

	private static IllegalStateException manual(String reason)
	{
		return new IllegalStateException(reason + ". Select the highlighted option manually, then resume.");
	}
}
