package net.runelite.client.plugins.microbot.efficientwalker;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.QuestState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;

final class QuestAutomation
{
	private final Object quest;
	private final Object onlyStep;
	private final boolean dialogueOnly;
	private final WorldPoint start;
	private WorldPoint errorTarget;
	private Object step;
	private int inventoryOpenedAt = -1;
	private String instruction;
	private WorldPoint marker;
	private Object ownedRequest;
	private List<WorldPoint> destinations = Collections.emptyList();
	private QuestDialogue dialogue;
	private QuestItemUse pendingItem;
	private boolean awaitingStep;
	private boolean actionPending;
	private boolean actionAnimationSeen;
	private int actionRequestedAt = -1;
	private boolean targetSeen;
	private boolean dialogueHandoff;
	private String selectedWidget;
	private String actionState;
	private int stepReadyAt = -1;

	QuestAutomation(Object quest, EfficientWalker walker)
	{
		this(quest, walker, false);
	}

	QuestAutomation(Object quest, EfficientWalker walker, boolean singleStep)
	{
		this(quest, walker, singleStep, false);
	}

	QuestAutomation(Object quest, EfficientWalker walker, boolean singleStep, boolean dialogueOnly)
	{
		this.quest = quest;
		this.dialogueOnly = dialogueOnly;
		onlyStep = singleStep ? QuestStepTarget.currentStep(quest) : null;
		if (singleStep && onlyStep == null) { throw new IllegalStateException("Wait for Quest Helper's current step."); }
		start = walker.currentLocation();
		ownedRequest = walker.questRequestToken();
	}

	WalkerError errorNotice(String reason, EfficientWalker walker)
	{
		return new WalkerError(reason, start, walker.currentLocation(), errorTarget);
	}

	boolean tick(Client client, PluginManager manager, EfficientWalker walker)
	{
		if (client.getGameState() != GameState.LOGGED_IN) { return true; }
		if (walker.questRequestToken() != ownedRequest)
		{
			cancel(client);
			Microbot.status = "Quest automation stopped for another walk.";
			return false;
		}
		try
		{
			if (quest.getClass().getMethod("getState", Client.class).invoke(quest, client) == QuestState.FINISHED)
			{
				cancel(client);
				walker.cancelCurrent();
				Microbot.status = "Quest complete. Automation stopped.";
				return false;
			}
		}
		catch (ReflectiveOperationException exception)
		{
			throw new IllegalStateException("Cannot read Quest Helper's quest state. Update Quest Helper, then resume.");
		}
		if (QuestStepTarget.selectedQuest(manager) != quest)
		{
			throw new IllegalStateException("Selected quest changed. Press Walk to quest step to start the selected quest.");
		}
		if (walker.bankTripActive()) { return true; }
		if (walker.questTravelPending())
		{
			Microbot.status = "Waiting for travel to finish. Cancel walk stops automation.";
			return true;
		}
		boolean cutscene = client.getVarbitValue(net.runelite.api.gameval.VarbitID.CUTSCENE_STATUS) != 0;
		if (cutscene && !QuestDialogue.visible(client)) { return true; }
		if (cutscene) { dialogueHandoff = false; }
		Object current = QuestStepTarget.currentStep(quest);
		if (onlyStep != null && current != onlyStep)
		{
			cancel(client);
			walker.cancelCurrent();
			Microbot.status = "Quest step changed; returning control to the quest controller.";
			return false;
		}
		if (current == null)
		{
			cancel(client);
			walker.cancelCurrent();
			ownedRequest = walker.questRequestToken();
			destinations = Collections.emptyList();
			Microbot.status = "Waiting for Quest Helper's next step. Cancel walk stops automation.";
			return true;
		}
		if (dialogueOnly && !QuestDialogue.visible(client))
		{
			cancel(client); walker.cancelCurrent();
			return false;
		}
		String text = QuestItemUse.instruction(current);
		WorldPoint authoredPoint = QuestStepTarget.optionalPoint(current);
		errorTarget = authoredPoint;
		boolean dialogueVisible = QuestDialogue.visible(client);
		if (!dialogueVisible) { dialogueHandoff = false; }
		if (current != step || !Objects.equals(marker, authoredPoint) || !Objects.equals(instruction, text))
		{
			stepReadyAt = stepReadyAt(marker, authoredPoint, client.getTickCount());
			dialogueHandoff = !cutscene && dialogueVisible && authoredPoint != null
				&& (step == null ? authoredPoint.distanceTo(client.getLocalPlayer().getWorldLocation()) > 2
					: !Objects.equals(marker, authoredPoint))
				&& !QuestDialogue.matches(client, quest, current);
			cancel(client);
			walker.cancelCurrent();
			ownedRequest = walker.questRequestToken();
			destinations = Collections.emptyList();
			step = current;
			marker = authoredPoint;
			instruction = text;
			awaitingStep = false;
			actionPending = false;
			actionAnimationSeen = false;
			actionRequestedAt = -1;
			actionState = null;
			targetSeen = false;
			dialogue = null;
			selectedWidget = null;
		}
		if (stepReadyAt >= client.getTickCount())
		{
			Microbot.status = "Waiting for the new floor to finish loading. Cancel walk stops automation.";
			return true;
		}
		if (dialogueVisible && (!dialogueHandoff || dialogueOnly))
		{
			if (!destinations.isEmpty())
			{
				walker.cancelCurrent();
				ownedRequest = walker.questRequestToken();
				destinations = Collections.emptyList();
			}
			if (pendingItem != null) { pendingItem.cancel(client); pendingItem = null; }
			if (dialogue == null) { dialogue = new QuestDialogue(quest); }
			dialogue.tick(client, manager);
			Microbot.status = "Automating quest dialogue.";
			return true;
		}
		QuestWidget widget = cutscene ? null : QuestWidget.resolve(client, current);
		if (widget != null)
		{
			if (!widget.key().equals(selectedWidget))
			{
				if (!destinations.isEmpty())
				{
					walker.cancelCurrent();
					ownedRequest = walker.questRequestToken();
					destinations = Collections.emptyList();
				}
				if (!walker.interactWithQuestWidget(current, widget))
				{
					throw new IllegalStateException("Cannot select the highlighted interface option. Make it visible, then resume.");
				}
				selectedWidget = widget.key();
				actionPending = true;
				awaitingStep = true;
			}
			Microbot.status = "Highlighted interface option selected. Waiting for quest progress.";
			return true;
		}
		if (awaitingStep)
		{
			if (isEntityStep(current))
			{
				boolean interacting = client.getLocalPlayer() != null
					&& client.getLocalPlayer().getInteracting() != null;
				if (client.getLocalPlayer() != null && client.getLocalPlayer().getAnimation() != -1)
				{
					actionAnimationSeen = true;
				}
				QuestStepTarget.Target refreshed = QuestStepTarget.inspect(manager, client);
				String refreshedState = actionState(client, refreshed);
				if (actionState != null && refreshedState != null && !actionState.equals(refreshedState))
				{
					awaitingStep = false;
					actionPending = false;
					actionState = null;
					actionAnimationSeen = false;
					return true;
				}
				if (entityAttemptFinished(actionAnimationSeen, client.getLocalPlayer() == null
					? -1 : client.getLocalPlayer().getAnimation(), interacting))
				{
					awaitingStep = false;
					actionPending = false;
					actionState = null;
					actionAnimationSeen = false;
					return true;
				}
			}
			Microbot.status = "Waiting for the quest step to advance. Cancel walk stops automation.";
			return true;
		}
		WorldPoint travelDestination = walker.questTravelDestination(current);
		if (travelDestination != null)
		{
			errorTarget = travelDestination;
			if (!destinations.isEmpty() && walker.getStatus() == EfficientWalker.Status.BLOCKED)
			{
				throw new IllegalStateException("Quest travel is blocked. Resolve the walking error, then resume.");
			}
			List<WorldPoint> travelGoals = Collections.singletonList(travelDestination);
			if (!travelGoals.equals(destinations))
			{
				destinations = travelGoals;
				walker.walkToAny(travelGoals, false);
				ownedRequest = walker.questRequestToken();
			}
			if (walker.getStatus() == EfficientWalker.Status.ARRIVED)
			{
				awaitingStep = true;
				Microbot.status = "Quest travel complete. Waiting for Quest Helper's next step.";
			}
			return true;
		}
		if (authoredPoint == null && !isEntityStep(current))
		{
			if (text.matches("(?is)^wait\\b.*"))
			{
				Microbot.status = text + " Cancel walk stops automation.";
				return true;
			}
			return inventoryAction(client, walker, current);
		}
		QuestStepTarget.Target target = QuestStepTarget.inspect(manager, client);
		errorTarget = target.livePoint == null ? target.point : target.livePoint;
		if (!destinations.isEmpty() && walker.getStatus() == EfficientWalker.Status.BLOCKED)
		{
			throw new IllegalStateException("Quest route is blocked. Resolve the walking error, then press Walk to quest step.");
		}
		boolean entity = target.npc || target.object;
		if (entity && target.livePoint == null && targetSeen)
		{
			cancel(client);
			walker.cancelCurrent();
			ownedRequest = walker.questRequestToken();
			destinations = Collections.emptyList();
			Microbot.status = "Waiting for the quest target to reappear.";
			return true;
		}
		targetSeen |= target.livePoint != null;
		List<WorldPoint> goals = QuestItemUse.isDigStep(current)
			? Collections.singletonList(authoredPoint) : walker.questDestinations(target);
		if (goals.isEmpty()) { throw new IllegalStateException("Cannot find a safe quest approach tile. Move closer, then resume."); }
		if ((!entity || target.livePoint != null) && QuestDestination.loaded(client, target)
			&& goals.contains(client.getLocalPlayer().getWorldLocation()))
		{
			if (walker.isWalkInProgress() || !walker.questTargetReady(target))
			{
				Microbot.status = "Waiting for travel and the quest target to be ready. Cancel walk stops automation.";
				return true;
			}
			if (!destinations.isEmpty())
			{
				walker.cancelCurrent();
				ownedRequest = walker.questRequestToken();
				destinations = Collections.emptyList();
			}
			if (!entity)
			{
				if (QuestItemUse.isDigStep(current)) { return inventoryAction(client, walker, current); }
				awaitingStep = true;
				Microbot.status = "Quest point reached. Waiting for the next step; follow any manual instructions.";
				return true;
			}
		QuestItemUse item = QuestItemUse.resolve(client, target);
		if (item == null && target.object && !walker.questObjectActionAvailable(target.liveObject, target.step))
		{
			item = QuestItemUse.resolveHighlighted(client, target.step);
		}
			if (item != null)
			{
				if (!inventoryReady(client)) { return true; }
				if (pendingItem == null)
				{
					if (!walker.selectQuestItem(item)) { throw new IllegalStateException("Open inventory and clear any selected item or spell, then resume quest automation."); }
					pendingItem = item;
					Microbot.status = "Selecting " + item.name + " for the quest target.";
					return true;
				}
				if (item.id != pendingItem.id || item.slot != pendingItem.slot)
				{
					throw new IllegalStateException("The quest item changed before use. Check inventory, then resume.");
				}
				if (!pendingItem.isSelected(client))
				{
					if (client.isWidgetSelected()) { throw new IllegalStateException("Another item or spell was selected. Clear it, then resume."); }
					Microbot.status = "Waiting for quest item selection. Cancel walk stops automation.";
					return true;
				}
				if (!walker.useQuestItem(pendingItem, target)) { throw new IllegalStateException("Cannot use the quest item on this visible target. Interact manually, then resume."); }
				Microbot.status = "Use " + item.name + " requested. Waiting for quest progress.";
				pendingItem = null;
			}
			else if (target.object)
			{
				String action = walker.interactWithQuestObject(target.liveObject, target.step);
				if (action == null) { throw new IllegalStateException("Cannot interact with the quest object. Make it visible, then resume."); }
				Microbot.status = action + " requested. Waiting for quest progress.";
			}
			else
			{
				String action = walker.interactWithQuestNpc(target.liveNpc, target.step);
				if (action == null) { throw new IllegalStateException("Cannot interact with the quest NPC. Make it visible, then resume."); }
				Microbot.status = action + " requested. Waiting for quest progress.";
			}
			actionState = actionState(client, target);
			actionRequestedAt = client.getTickCount();
			actionPending = true;
			awaitingStep = true;
			return true;
		}
		if (pendingItem != null) { cancel(client); }
		if (!goals.equals(destinations))
		{
			destinations = goals;
			walker.walkToAny(goals, QuestStepTarget.escort(client, target.step));
			ownedRequest = walker.questRequestToken();
		}
		return true;
	}

	private static String actionState(Client client, QuestStepTarget.Target target)
	{
		if (target.liveObject != null)
		{
			return "object:" + target.liveObject.getId() + ':' + QuestItemUse.highlightedSignature(client, target.step);
		}
		if (target.liveNpc != null)
		{
			return "npc:" + target.liveNpc.getId() + ':' + QuestItemUse.highlightedSignature(client, target.step);
		}
		return null;
	}

	static boolean entityAttemptFinished(boolean animationSeen, int animation, boolean interacting)
	{
		return animationSeen && animation == -1 && !interacting;
	}

	static int stepReadyAt(WorldPoint previous, WorldPoint current, int tick)
	{
		return previous != null && current != null && previous.getPlane() != current.getPlane() ? tick + 5 : -1;
	}

	void inventoryChanged()
	{
		if (!awaitingStep || !actionPending) { return; }
		awaitingStep = false;
		actionPending = false;
		actionState = null;
		actionAnimationSeen = false;
		actionRequestedAt = -1;
	}

	private boolean inventoryReady(Client client)
	{
		net.runelite.api.widgets.Widget inventory = client.getWidget(net.runelite.api.gameval.InterfaceID.Inventory.ITEMS);
		if (inventory != null && !inventory.isHidden()) { inventoryOpenedAt = -1; return true; }
		if (inventoryOpenedAt < 0)
		{
			client.runScript(915, 3);
			inventoryOpenedAt = client.getTickCount();
		}
		else if (client.getTickCount() - inventoryOpenedAt >= 8)
		{
			throw new IllegalStateException("Cannot open the inventory tab. Open it manually, then resume quest automation.");
		}
		Microbot.status = "Opening inventory for the quest action. Cancel walk stops automation.";
		return false;
	}

	private boolean inventoryAction(Client client, EfficientWalker walker, Object current)
	{
		QuestItemUse.InventoryAction action = QuestItemUse.inventoryAction(client, current);
		if (!inventoryReady(client)) { return true; }
		if (action.target != null)
		{
			if (pendingItem == null)
			{
				if (!walker.selectQuestItem(action.source)) { throw new IllegalStateException("Open inventory and clear any selected item or spell, then resume."); }
				pendingItem = action.source;
				Microbot.status = "Selecting " + pendingItem.name + " for the quest combination.";
				return true;
			}
			if (pendingItem.id != action.source.id || pendingItem.slot != action.source.slot)
			{
				throw new IllegalStateException("The inventory combination changed before use. Check inventory, then resume.");
			}
			if (!pendingItem.isSelected(client))
			{
				if (client.isWidgetSelected()) { throw new IllegalStateException("Another item or spell was selected. Clear it, then resume."); }
				Microbot.status = "Waiting for inventory item selection. Cancel walk stops automation.";
				return true;
			}
		}
		if (!walker.interactWithQuestInventory(action))
		{
			throw new IllegalStateException("Cannot send the requested inventory action. Open inventory and follow Quest Helper manually, then resume.");
		}
		pendingItem = null;
		actionPending = true;
		awaitingStep = true;
		Microbot.status = action.action + " " + action.source.name + " requested. Waiting for quest progress.";
		return true;
	}

	private static boolean isEntityStep(Object step)
	{
		for (Class<?> type = step.getClass(); type != null; type = type.getSuperclass())
		{
			if (type.getSimpleName().equals("NpcStep") || type.getSimpleName().equals("ObjectStep")) { return true; }
		}
		return false;
	}

	boolean handingOffDialogue()
	{
		return dialogueHandoff;
	}

	boolean pauseForCutscene(Client client, EfficientWalker walker)
	{
		if (walker.questTravelPending()) { return walker.questRequestToken() == ownedRequest; }
		cancel(client);
		if (walker.questRequestToken() != ownedRequest) { return false; }
		if (!destinations.isEmpty())
		{
			walker.cancelCurrent();
			ownedRequest = walker.questRequestToken();
			destinations = Collections.emptyList();
		}
		return true;
	}

	boolean rejectsAction(String message, EfficientWalker walker)
	{
		if (!actionPending || walker.questRequestToken() != ownedRequest || ActionFailure.reason(message) == null) { return false; }
		Object current = QuestStepTarget.currentStep(quest);
		if (current != step || !Objects.equals(instruction, QuestItemUse.instruction(current))) { return false; }
		// An explicitly requested attempt may intentionally encounter a locked quest door.
		String text = instruction == null ? "" : instruction.toLowerCase(java.util.Locale.ROOT);
		return !(ActionFailure.reason(message).contains("unlock access")
			&& (text.startsWith("try to ") || text.startsWith("attempt to ")));
	}

	void cancel(Client client)
	{
		inventoryOpenedAt = -1;
		if (pendingItem != null) { pendingItem.cancel(client); pendingItem = null; }
	}
}
