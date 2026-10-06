package net.runelite.client.plugins.microbot.efficientwalker;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.util.Text;

final class QuestDialogue
{
	private static final int[][] CONTINUE_WIDGETS = {
		{231, 5}, {217, 5}, {193, 0}, {193, 3}, {11, 4}, {229, 0}, {229, 2}, {229, 4}
	};
	private final Object quest;
	private String sentScreen;

	QuestDialogue(Object quest)
	{
		this.quest = quest;
	}

	static boolean visible(Client client)
	{
		return options(client) != null || continueWidget(client) != null;
	}

	static boolean continueReady(Client client)
	{
		return options(client) == null && continueWidget(client) != null;
	}

	boolean tick(Client client, PluginManager manager)
	{
		if (QuestStepTarget.selectedQuest(manager) != quest)
		{
			throw new IllegalStateException("Selected quest changed. Press Walk to quest step to resume.");
		}
		Widget options = options(client);
		if (options == null)
		{
			String fingerprint = pageFingerprint(client);
			if (continueReady(client) && Rs2Dialogue.hasContinue()
				&& fingerprint != null && !fingerprint.equals(sentScreen))
			{
				Rs2Dialogue.clickContinue();
				sentScreen = fingerprint;
			}
			return true;
		}
		if (options.getChildren() == null || options.getChildren().length == 0) { return true; }
		String fingerprint = pageFingerprint(client);
		if (fingerprint == null || fingerprint.equals(sentScreen)) { return true; }
		Widget choice = questStartChoice(quest, options);
		if (choice == null) { choice = choice(client, QuestStepTarget.currentStep(quest), options); }
		if (!Rs2Dialogue.keyPressForDialogueOption(choice.getText(), true)) { return true; }
		sentScreen = fingerprint;
		return true;
	}

	static String pageFingerprint(Client client)
	{
		Widget options = options(client);
		Widget prompt = options == null ? continueWidget(client) : null;
		if (options == null && prompt == null) { return null; }
		if (options != null && (options.getChildren() == null || options.getChildren().length == 0)) { return null; }
		StringBuilder screen = new StringBuilder();
		int group = options == null ? prompt.getId() >>> 16 : 219;
		for (int i = 0; i < 32; i++)
		{
			Widget widget = client.getWidget(group, i);
			if (widget != null && !widget.isHidden()) { screen.append(i).append(':').append(clean(widget.getText())).append('|'); }
		}
		if (options != null && options.getChildren() != null)
		{
			for (Widget option : options.getChildren())
			{
				if (option != null) { screen.append(clean(option.getText())).append('|'); }
			}
		}
		return group + ":" + screen;
	}

	static Widget questStartChoice(Object quest, Widget menu)
	{
		Widget[] widgets = menu.getChildren();
		if (widgets == null || widgets.length == 0 || widgets[0] == null || widgets[0].isHidden()) { return null; }
		try
		{
			String name = String.valueOf(QuestStepTarget.call(QuestStepTarget.call(quest, "getQuest"), "getName"));
			if (!clean(widgets[0].getText()).equals("Start the " + name + " quest?")) { return null; }
		}
		catch (ReflectiveOperationException exception) { return null; }
		Widget yes = null;
		int answers = 0;
		boolean no = false;
		for (int i = 1; i < widgets.length; i++)
		{
			Widget widget = widgets[i];
			if (widget == null || widget.isHidden() || clean(widget.getText()).isEmpty()) { continue; }
			answers++;
			if (clean(widget.getText()).equals("Yes.")) { yes = widget; }
			if (clean(widget.getText()).equals("No.")) { no = true; }
		}
		return answers == 2 && no ? yes : null;
	}

	static boolean matches(Client client, Object quest, Object step)
	{
		Widget menu = options(client);
		return menu != null && (questStartChoice(quest, menu) != null || matchingChoice(client, step, menu) != null);
	}

	static Widget choice(Client client, Object step, Widget menu)
	{
		Widget match = matchingChoice(client, step, menu);
		if (match != null) { return match; }
		throw new IllegalStateException("Quest Helper does not identify one dialogue answer. Select the answer manually, then press Walk to quest step to resume.");
	}

	private static Widget matchingChoice(Client client, Object step, Widget menu)
	{
		try
		{
			Widget[] widgets = menu.getChildren();
			Object choices = field(step, "choices");
			List<?> rules = (List<?>) field(choices, "choices");
			String previous = (String) field(step, "lastDialogSeen");
			Set<Widget> matches = new HashSet<>();
			for (Object rule : rules)
			{
				if (!rule.getClass().getSimpleName().equals("DialogChoiceStep"))
				{
					throw new IllegalStateException("This quest uses an unsupported dialogue rule. Select the answer manually, then resume.");
				}
				String expected = (String) field(rule, "expectedPreviousLine");
				if (expected != null && (previous == null || !previous.contains(expected))) { continue; }
				List<?> exclusions = (List<?>) field(rule, "excludedStrings");
				Widget excluded = client.getWidget((int) field(rule, "excludedGroupId"), (int) field(rule, "excludedChildId"));
				if (exclusions != null && excluded != null && excluded.getChildren() != null)
				{
					boolean skip = false;
					for (Widget widget : excluded.getChildren())
					{
						if (widget == null) { continue; }
						for (Object text : exclusions) { skip |= clean(widget.getText()).contains((String) text); }
					}
					if (skip) { continue; }
				}
				int index = (int) field(rule, "choiceById");
				String text = (String) field(rule, "choice");
				Pattern pattern = (Pattern) field(rule, "pattern");
				int varbit = (int) field(rule, "varbitId");
				if (index < 0 && varbit >= 0)
				{
					Map<?, ?> answers = (Map<?, ?>) field(rule, "varbitValueToAnswer");
					text = answers == null ? null : (String) answers.get(client.getVarbitValue(varbit));
				}
				if (widgets == null) { continue; }
				for (int i = 1; i < widgets.length; i++)
				{
					Widget widget = widgets[i];
					if (widget == null || widget.isHidden() || index >= 0 && index != i) { continue; }
					String actual = clean(widget.getText());
					if (text != null && text.equals(actual) || pattern != null && pattern.matcher(actual).find()
						|| index >= 0 && text == null && pattern == null)
					{
						matches.add(widget);
					}
				}
			}
			if (matches.isEmpty()) { return null; }
			if (matches.size() == 1) { return matches.iterator().next(); }
			throw new IllegalStateException("Quest Helper does not identify one dialogue answer. Select the answer manually, then press Walk to quest step to resume.");
		}
		catch (ReflectiveOperationException | ClassCastException exception)
		{
			throw new IllegalStateException("Could not read Quest Helper's dialogue rules. Update Quest Helper or answer manually.", exception);
		}
	}

	static Object field(Object object, String name) throws ReflectiveOperationException
	{
		if (object == null) { throw new NoSuchFieldException(name); }
		for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass())
		{
			try
			{
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(object);
			}
			catch (NoSuchFieldException ignored) { }
		}
		throw new NoSuchFieldException(name);
	}

	private static String clean(String text)
	{
		return text == null ? "" : Text.removeTags(text).replaceFirst("^(\\[\\d+\\]\\s*)+", "").trim();
	}

	private static Widget options(Client client)
	{
		Widget widget = client.getWidget(219, 1);
		return widget == null || widget.isHidden() ? null : widget;
	}

	private static Widget continueWidget(Client client)
	{
		for (int[] id : CONTINUE_WIDGETS)
		{
			Widget widget = continuePrompt(client.getWidget(id[0], id[1]));
			if (widget != null) { return widget; }
		}
		return null;
	}

	private static Widget continuePrompt(Widget widget)
	{
		if (widget == null || widget.isHidden()) { return null; }
		if (clean(widget.getText()).equalsIgnoreCase("Click here to continue")) { return widget; }
		Widget[] children = widget.getChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				Widget prompt = continuePrompt(child);
				if (prompt != null) { return prompt; }
			}
		}
		return null;
	}

}
