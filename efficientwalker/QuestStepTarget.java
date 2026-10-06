package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.Collection;
import java.lang.reflect.Field;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

final class QuestStepTarget
{
	private QuestStepTarget()
	{
	}

	static Target inspect(PluginManager manager, Client client)
	{
		Object step = currentStep(selectedQuest(manager));
		WorldPoint target = optionalPoint(step);
		boolean authored = target != null;
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || client.getLocalPlayer() == null
			|| client.getLocalPlayer().getWorldView() != worldView)
		{
			throw new IllegalStateException("Return to the main game world, then retry the quest walk.");
		}
		if (!authored) { target = client.getLocalPlayer().getWorldLocation(); }
		if (authored && worldView.isInstance())
		{
			Collection<WorldPoint> points = WorldPoint.toLocalInstance(worldView, target);
			if (points.size() != 1)
			{
				throw new IllegalStateException("Quest destination is outside this instance or ambiguous. Leave the instance and retry.");
			}
			target = points.iterator().next();
		}
		Class<?> npcType = null;
		Class<?> objectType = null;
		for (Class<?> type = step.getClass(); type != null; type = type.getSuperclass())
		{
			if (type.getName().equals("com.questhelper.steps.NpcStep")
				|| type.getName().equals("net.runelite.client.plugins.microbot.questhelper.steps.NpcStep"))
			{
				npcType = type;
				break;
			}
			if (type.getName().equals("com.questhelper.steps.ObjectStep")
				|| type.getName().equals("net.runelite.client.plugins.microbot.questhelper.steps.ObjectStep"))
			{
				objectType = type;
				break;
			}
		}
		WorldPoint npcPoint = null;
		NPC liveNpc = null;
		if (npcType != null)
		{
			try
			{
				Field field = npcType.getDeclaredField("npcs");
				field.setAccessible(true);
				java.lang.reflect.Method focused = npcType.getDeclaredMethod("passesFocusChecks", NPC.class);
				focused.setAccessible(true);
				List<NPC> candidates = new java.util.ArrayList<>();
				for (Object value : (Collection<?>) field.get(step)) { candidates.add((NPC) value); }
				if (candidates.isEmpty())
				{
					int id = (int) QuestDialogue.field(step, "npcID");
					net.runelite.api.NPCComposition expected = client.getNpcDefinition(id);
					for (NPC npc : worldView.npcs())
					{
						if (matchesNpcFamily(npc, expected)) { candidates.add(npc); }
					}
				}
				for (Object value : candidates)
				{
					NPC npc = (NPC) value;
					if (npc.getId() < 0 || npc.getWorldView() != worldView
						|| !Boolean.TRUE.equals(focused.invoke(step, npc)))
					{
						continue;
					}
					WorldPoint point = npc.getWorldLocation();
					if (point != null && (npcPoint == null || point.distanceTo(target) < npcPoint.distanceTo(target)))
					{
						npcPoint = point;
						liveNpc = npc;
					}
				}
			}
			catch (ReflectiveOperationException | RuntimeException exception)
			{
				throw new IllegalStateException("Could not read Quest Helper's NPC. Update Quest Helper, then retry.", exception);
			}
		}
		TileObject liveObject = null;
		if (objectType != null)
		{
			try
			{
				Field field = objectType.getDeclaredField("objects");
				field.setAccessible(true);
				for (Object value : (Collection<?>) field.get(step))
				{
					TileObject object = (TileObject) value;
					if (object.getWorldView() != worldView || object.getWorldLocation() == null) { continue; }
					if (liveObject == null || object.getWorldLocation().distanceTo(target)
						< liveObject.getWorldLocation().distanceTo(target))
					{
						liveObject = object;
					}
				}
			}
			catch (ReflectiveOperationException | RuntimeException exception)
			{
				throw new IllegalStateException("Could not read Quest Helper's object. Update Quest Helper, then retry.", exception);
			}
		}
		if (!authored)
		{
			target = liveObject == null ? npcPoint : liveObject.getWorldLocation();
			if (target == null) { throw new IllegalStateException("The current quest step has no walking destination or visible target. Follow Quest Helper manually, then resume."); }
		}
		return new Target(step, target, npcType != null, objectType != null, npcPoint, liveNpc, liveObject);
	}

	static boolean matchesNpcFamily(NPC npc, net.runelite.api.NPCComposition expected)
	{
		if (npc == null || npc.isDead() || expected == null || expected.getName() == null
			|| !expected.getName().equals(npc.getName()) || npc.getComposition() == null) { return false; }
		int[] variants = npc.getComposition().getConfigs();
		return variants != null && java.util.Arrays.stream(variants).anyMatch(id -> id == expected.getId());
	}

	static boolean escort(Client client, Object step)
	{
		String text = QuestItemUse.instruction(step).toLowerCase(java.util.Locale.ROOT);
		return client.getVarpValue(net.runelite.api.gameval.VarPlayerID.FOLLOWER_NPC) > 0
			&& text.matches("(?s).*\\b(?:lead|escort)\\b.*");
	}

	static final class Target
	{
		final Object step;
		final WorldPoint point;
		final boolean npc;
		final NPC liveNpc;
		final boolean object;
		final TileObject liveObject;
		final WorldPoint livePoint;

		Target(Object step, WorldPoint point, boolean npc, boolean object, WorldPoint npcPoint, NPC liveNpc, TileObject liveObject)
		{
			this.step = step;
			this.point = point;
			this.npc = npc;
			this.liveNpc = liveNpc;
			this.object = object;
			this.liveObject = liveObject;
			this.livePoint = liveObject == null ? npcPoint : liveObject.getWorldLocation();
		}
	}

	static Object selectedQuest(PluginManager manager)
	{
		List<Plugin> helpers = manager.getPlugins().stream()
			.filter(plugin -> plugin.getClass().getName().equals("com.questhelper.QuestHelperPlugin")
				|| plugin.getClass().getName().equals(
					"net.runelite.client.plugins.microbot.questhelper.QuestHelperPlugin"))
			.collect(Collectors.toList());
		if (helpers.isEmpty())
		{
			throw new IllegalStateException("Install Quest Helper, enable it, and select a quest.");
		}
		List<Plugin> active = helpers.stream().filter(manager::isActive).collect(Collectors.toList());
		if (active.isEmpty())
		{
			throw new IllegalStateException("Enable Quest Helper and select a quest, then retry.");
		}
		if (active.size() != 1)
		{
			throw new IllegalStateException("Enable only one Quest Helper instance, then retry.");
		}
		try
		{
			Object quest = call(active.get(0), "getSelectedQuest");
			if (quest == null) { throw new IllegalStateException("Select a quest in Quest Helper, then retry."); }
			return quest;
		}
		catch (ReflectiveOperationException exception)
		{
			throw new IllegalStateException("Could not read Quest Helper. Update it, then retry.", exception);
		}
	}

	static Object currentStep(Object quest)
	{
		try
		{
			Object current = call(quest, "getCurrentStep");
			return current == null ? null : call(current, "getActiveStep");
		}
		catch (ReflectiveOperationException exception)
		{
			throw new IllegalStateException("Could not read Quest Helper's current step. Update it, then retry.", exception);
		}
	}

	static int questActionIndex(Object step, String[] actions)
	{
		return questActionIndex(step, actions, true);
	}

	static int questNamedActionIndex(Object step, String[] actions)
	{
		return questActionIndex(step, actions, false);
	}

	private static int questActionIndex(Object step, String[] actions, boolean allowFallback)
	{
		String instruction;
		try
		{
			Object text = call(step, "getText");
			if (!(text instanceof List) || ((List<?>) text).isEmpty()
				|| !(((List<?>) text).get(0) instanceof String))
			{
				throw new IllegalStateException("Quest Helper has no interaction instruction. Interact manually.");
			}
			instruction = net.runelite.client.util.Text.removeTags((String) ((List<?>) text).get(0))
				.toLowerCase(java.util.Locale.ROOT).replace('-', ' ').replaceAll("\\s+", " ").trim();
		}
		catch (ReflectiveOperationException exception)
		{
			throw new IllegalStateException("Could not read the interaction instruction. Interact manually using Quest Helper.", exception);
		}
		if (instruction.isEmpty()) { throw new IllegalStateException("Quest Helper has no interaction instruction. Interact manually."); }
		if (instruction.matches("(?s).*\\buse\\s+.+\\s+(on|with)\\s+.+"))
		{
			throw new IllegalStateException("This step requires using an item on the quest target. Follow Quest Helper manually, then press Walk to quest step.");
		}
		int fallback = -1;
		int match = -1;
		int length = 0;
		boolean ambiguous = false;
		if (actions == null) { return -1; }
		for (int i = 0; i < Math.min(5, actions.length); i++)
		{
			if (actions[i] == null || actions[i].trim().isEmpty() || "Examine".equalsIgnoreCase(actions[i].trim())) { continue; }
			if (fallback < 0) { fallback = i; }
			String action = actions[i].toLowerCase(java.util.Locale.ROOT).replace('-', ' ').replaceAll("\\s+", " ").trim();
			if (instruction.matches("(?s).*\\b" + java.util.regex.Pattern.quote(action) + "\\b.*"))
			{
				if (action.length() > length)
				{
					match = i;
					length = action.length();
					ambiguous = false;
				}
				else if (action.length() == length) { ambiguous = true; }
			}
		}
		if (ambiguous) { throw new IllegalStateException("More than one interaction action matches this step. Interact manually using Quest Helper."); }
		return match < 0 ? allowFallback ? fallback : -1 : match;
	}

	static WorldPoint optionalPoint(Object step)
	{
		try
		{
			if (step != null)
			{
				Object defined = call(step, "getDefinedPoint");
				Object point = defined == null ? null : call(defined, "getWorldPoint");
				if (point instanceof WorldPoint)
				{
					return (WorldPoint) point;
				}
			}
		}
		catch (NoSuchMethodException exception)
		{
			// Instruction-only steps do not expose a destination.
		}
		catch (ReflectiveOperationException | SecurityException exception)
		{
			throw new IllegalStateException("Could not read Quest Helper. Update Quest Helper and restart the quest, then retry.", exception);
		}
		return null;
	}

	static Object call(Object target, String method) throws ReflectiveOperationException
	{
		return target.getClass().getMethod(method).invoke(target);
	}
}
