package net.runelite.client.plugins.microbot.efficientwalker;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapOverlay;
import net.runelite.client.util.Text;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.HotkeyListener;

@PluginDescriptor(
	name = "Efficient Walker",
	description = "Efficient Microbot webwalker",
	tags = {"walking", "pathfinding"},
	version = "1.15.0"
)
public class EfficientWalkerPlugin extends Plugin
{
	private static final String DRY_RUN = "Dry-run walk";
	private static final String BEGIN_WALK = "Begin walk";
	private static final String STOP_WALK = "Stop walk";
	private static final String DRY_RUN_COLORED = ColorUtil.wrapWithColorTag(DRY_RUN, Color.ORANGE);
	private static final String BEGIN_WALK_COLORED = ColorUtil.wrapWithColorTag(BEGIN_WALK, Color.ORANGE);
	private static final String STOP_WALK_COLORED = ColorUtil.wrapWithColorTag(STOP_WALK, Color.ORANGE);

	@Inject
	private Client client;

	@Inject
	private EfficientWalker walker;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private EfficientWalkerOverlay overlay;

	@Inject
	private EfficientWalkerMapOverlay mapOverlay;

	@Inject
	private WorldMapOverlay worldMapOverlay;

	private WorldPoint pendingWorldMapWalk;
	private Object questStep;
	private WorldPoint questWalkStart;
	private WorldPoint questWalkTarget;
	private QuestAutomation questAutomation;
	private List<WorldPoint> questDestinations = Collections.emptyList();
	private boolean questSpaceHeld;
	private boolean questContinueCanceled;
	private boolean questTargetSeen;

	@Inject
	private EfficientWalkerConfig config;

	@Inject
	private KeyManager keyManager;

	@Inject
	private PluginManager pluginManager;

	private enum HotkeyAction { BANK, PRAYER, QUEST, QUEST_ONCE, QUEST_DIALOGUE, CANCEL }

	private static final class HotkeyCommand
	{
		private final HotkeyAction action;
		private final Object token;
		private HotkeyCommand(HotkeyAction action, Object token) { this.action = action; this.token = token; }
	}

	private final AtomicReference<HotkeyCommand> pendingHotkey = new AtomicReference<>();
	private Object automationControl;
	private final HotkeyListener bankHotkey = hotkey(() -> config.walkToNearestBankHotkey(), HotkeyAction.BANK);
	private final HotkeyListener prayerHotkey = hotkey(() -> config.walkToPrayerAltarHotkey(), HotkeyAction.PRAYER);
	private final HotkeyListener questHotkey = hotkey(() -> config.walkToQuestStepHotkey(), HotkeyAction.QUEST);
	private final HotkeyListener cancelHotkey = hotkey(() -> config.cancelWalkHotkey(), HotkeyAction.CANCEL);
	private boolean prayerWalk;
	private int prayerBefore = -1;
	private boolean prayerDispatched;
	private Object prayerRequest;
	private int prayerStableTicks;
	private WorldPoint prayerWalkStart;
	private WorldPoint prayerWalkTarget;

	private HotkeyListener hotkey(Supplier<Keybind> keybind, HotkeyAction action)
	{
		return new HotkeyListener(keybind)
		{
			@Override
			public void hotkeyPressed()
			{
				queueHotkey(action);
			}
		};
	}

	public boolean walkToQuestStep()
	{
		if (!pluginManager.isActive(this) || pluginManager.getPlugins().stream()
			.filter(plugin -> plugin.getClass().getName().equals(getClass().getName())).count() != 1)
		{
			throw new IllegalStateException("Enable exactly one Efficient Walker instance, then retry.");
		}
		QuestStepTarget.selectedQuest(pluginManager);
		queueHotkey(HotkeyAction.QUEST);
		return true;
	}

	/** Runs the current helper step, yielding before a different step can issue input. */
	public boolean walkToQuestStepOnce()
	{
		if (!config.handleInteractions()) { throw new IllegalStateException("Enable Automate quest steps."); }
		QuestStepTarget.selectedQuest(pluginManager);
		queueHotkey(HotkeyAction.QUEST_ONCE);
		return true;
	}

	/** Finish the current dialogue without issuing the following world interaction. */
	public boolean continueQuestDialogue()
	{
		if (!config.handleInteractions()) { throw new IllegalStateException("Enable Automate quest steps."); }
		QuestStepTarget.selectedQuest(pluginManager);
		queueHotkey(HotkeyAction.QUEST_DIALOGUE);
		return true;
	}

	/** Uses the same entry point as the configured Walk to altar hotkey. */
	public boolean walkToPrayerAltar()
	{
		queueHotkey(HotkeyAction.PRAYER);
		return true;
	}

	private void queueHotkey(HotkeyAction action)
	{
		synchronized (walker)
		{
			pendingHotkey.set(new HotkeyCommand(action, walker.beginControl()));
		}
	}

	public java.util.Map<String, Object> getControlStatus(Object token)
	{
		synchronized (walker)
		{
			boolean owned = token == walker.getControlToken();
			HotkeyCommand command = pendingHotkey.get();
			boolean pending = owned && command != null && command.token == token;
			boolean automation = owned && automationControl == token
				&& (prayerWalk || questAutomation != null || questStep != null || pendingWorldMapWalk != null);
			java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
			state.put("owned", owned);
			state.put("pending", pending);
			state.put("active", owned && (pending || automation || walker.isWalkInProgress()));
			state.put("state", pending ? "QUEUED" : walker.getStatus().name());
			state.put("failure", owned && !pending ? walker.controlFailure() : null);
			return state;
		}
	}

	public boolean cancelWalk(Object token)
	{
		synchronized (walker)
		{
			if (token != walker.getControlToken()) { return false; }
			cancelWalk();
			return true;
		}
	}

	public void cancelWalk()
	{
		synchronized (walker)
		{
			walker.beginControl();
			stopCurrentWalk();
		}
	}

	@Provides
	@Singleton
	EfficientWalkerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EfficientWalkerConfig.class);
	}

	@Provides
	@Singleton
	EfficientWalker provideWalker(Client client, EfficientWalkerConfig config)
	{
		return new EfficientWalker(client, config);
	}

	public EfficientWalker getWalker()
	{
		return walker;
	}

	@Override
	protected void startUp()
	{
		pendingHotkey.set(null);
		keyManager.registerKeyListener(bankHotkey);
		keyManager.registerKeyListener(prayerHotkey);
		keyManager.registerKeyListener(questHotkey);
		keyManager.registerKeyListener(cancelHotkey);
		overlayManager.add(overlay);
		overlayManager.add(mapOverlay);
	}

	@Override
	protected void shutDown()
	{
		releaseQuestSpace();
		clearPrayerWalk();
		clearQuestWalk();
		keyManager.unregisterKeyListener(bankHotkey);
		keyManager.unregisterKeyListener(prayerHotkey);
		keyManager.unregisterKeyListener(questHotkey);
		keyManager.unregisterKeyListener(cancelHotkey);
		bankHotkey.focusLost();
		prayerHotkey.focusLost();
		questHotkey.focusLost();
		cancelHotkey.focusLost();
		pendingHotkey.set(null);
		overlayManager.remove(overlay);
		overlayManager.remove(mapOverlay);
		pendingWorldMapWalk = null;
		walker.cancel();
		walker.close();
	}

	@Subscribe
	public void onClientTick(net.runelite.api.events.ClientTick event)
	{
		if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN || !QuestDialogue.continueReady(client)
			|| questAutomation != null && questAutomation.handingOffDialogue())
		{
			releaseQuestSpace();
			questContinueCanceled = false;
			return;
		}
		if (questContinueCanceled || !pluginManager.isActive(this)
			|| pluginManager.getPlugins().stream().filter(plugin -> plugin.getClass().getName().equals(getClass().getName())).count() != 1)
		{
			releaseQuestSpace();
			return;
		}
		try
		{
			Object quest = QuestStepTarget.selectedQuest(pluginManager);
			if (quest.getClass().getMethod("getState", Client.class).invoke(quest, client) == net.runelite.api.QuestState.FINISHED)
			{
				releaseQuestSpace();
				return;
			}
		}
		catch (ReflectiveOperationException | IllegalStateException exception)
		{
			releaseQuestSpace();
			return;
		}
		questSpaceHeld = true;
		// Synthetic key-down events do not receive operating-system auto-repeat.
		net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard.keyHold(java.awt.event.KeyEvent.VK_SPACE);
	}

	private void releaseQuestSpace()
	{
		if (!questSpaceHeld) { return; }
		questSpaceHeld = false;
		net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard.keyRelease(java.awt.event.KeyEvent.VK_SPACE);
	}

	@Subscribe(priority = -100)
	public void onGameTick(GameTick event)
	{
		synchronized (walker) { processGameTick(); }
	}

	private void processGameTick()
	{
		if (automationControl != null && automationControl != walker.getControlToken())
		{
			releaseQuestSpace();
			clearPrayerWalk();
			clearQuestWalk();
			pendingWorldMapWalk = null;
			automationControl = null;
		}
		if (client.getVarbitValue(net.runelite.api.gameval.VarbitID.CUTSCENE_STATUS) != 0)
		{
			if (pendingHotkey.get() != null && pendingHotkey.get().action == HotkeyAction.CANCEL) { processHotkey(); return; }
			if (questAutomation != null && !questAutomation.pauseForCutscene(client, walker)) { questAutomation = null; }
			if (questStep != null && !questDestinations.isEmpty() && !walker.questTravelPending())
			{
				walker.cancelCurrent();
				questDestinations = Collections.emptyList();
			}
			if (questAutomation != null || questStep != null || pendingHotkey.get() != null || walker.isWalkInProgress())
			{
				Microbot.status = "Waiting for the cutscene to finish. Cancel walk stops automation.";
			}
			if (questAutomation != null && QuestDialogue.visible(client)) { updateQuestWalk(); }
			return;
		}

		processHotkey();
		if (walker.getStatus() == EfficientWalker.Status.BLOCKED)
		{
			clearPrayerWalk();
			clearQuestWalk();
			pendingWorldMapWalk = null;
			return;
		}
		if (handleActionFailure(ActionFailure.visibleMessage(client))) { return; }
		updateQuestWalk();
		if (questAutomation != null && questAutomation.handingOffDialogue()) { releaseQuestSpace(); }
		walker.onGameTick();
		if (walker.getStatus() == EfficientWalker.Status.BLOCKED)
		{
			clearPrayerWalk();
			clearQuestWalk();
			pendingWorldMapWalk = null;
			return;
		}
		updatePrayerWalk();
		if (pendingWorldMapWalk != null)
		{
			if (walker.getStatus() == EfficientWalker.Status.PLANNING)
			{
				return;
			}
			if (walker.getStatus() != EfficientWalker.Status.PREVIEW)
			{
				pendingWorldMapWalk = null;
				return;
			}
			final Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
			if (map != null && !map.isHidden())
			{
				closeWorldMap();
				return;
			}

			final WorldPoint target = pendingWorldMapWalk;
			pendingWorldMapWalk = null;
			walker.testPreviewedWalk(target);
			return;
		}
	}

	private void processHotkey()
	{
		HotkeyCommand command = pendingHotkey.getAndSet(null);
		if (command == null || command.token != walker.getControlToken())
		{
			return;
		}
		HotkeyAction action = command.action;
		automationControl = command.token;
		if (action == HotkeyAction.CANCEL)
		{
			stopCurrentWalk();
			Microbot.status = "Walk cancelled.";
			return;
		}
		try
		{
			if (!pluginManager.isActive(this) || pluginManager.getPlugins().stream()
				.filter(plugin -> plugin.getClass().getName().equals(getClass().getName())).count() != 1)
			{
				throw new IllegalStateException("Enable exactly one Efficient Walker instance, then retry.");
			}
			if (action != HotkeyAction.PRAYER) { clearPrayerWalk(); }
			if ((action == HotkeyAction.QUEST || action == HotkeyAction.QUEST_ONCE || action == HotkeyAction.QUEST_DIALOGUE) && config.handleInteractions())
			{
				stopCurrentWalk();
				questAutomation = new QuestAutomation(QuestStepTarget.selectedQuest(pluginManager), walker, action != HotkeyAction.QUEST, action == HotkeyAction.QUEST_DIALOGUE);
				Microbot.status = "Quest automation started. Cancel walk stops it.";
				return;
			}
			QuestStepTarget.Target target = action == HotkeyAction.QUEST ? QuestStepTarget.inspect(pluginManager, client) : null;
			pendingWorldMapWalk = null;
			clearQuestWalk();
			if (action == HotkeyAction.BANK)
			{
				walker.requestNearestBank();
			}
			else if (action == HotkeyAction.PRAYER)
			{
				clearPrayerWalk();
				walker.cancelCurrent();
				if (client.getBoostedSkillLevel(net.runelite.api.Skill.PRAYER)
					>= client.getRealSkillLevel(net.runelite.api.Skill.PRAYER))
				{
					Microbot.status = "Prayer is already full.";
					return;
				}
				List<WorldPoint> destinations = PrayerRestoration.destinations(client, walker);
				if (destinations.isEmpty()) { throw new IllegalStateException("No safe prayer restoration location is available."); }
				prayerWalk = true;
				prayerBefore = client.getBoostedSkillLevel(net.runelite.api.Skill.PRAYER);
				prayerWalkStart = walker.currentLocation();
				prayerWalkTarget = destinations.get(0);
				if (!walker.walkToAny(destinations)) { throw new IllegalStateException("Efficient Walker rejected the prayer restoration route."); }
				prayerRequest = walker.questRequestToken();
			}
			else
			{
				walker.cancelCurrent();
				questStep = target.step;
				questWalkStart = walker.currentLocation();
				questWalkTarget = target.livePoint == null ? target.point : target.livePoint;
			}
			Microbot.status = action == HotkeyAction.BANK ? "Preparing walk to nearest bank."
				: action == HotkeyAction.PRAYER ? "Preparing walk to the nearest prayer restoration."
				: "Preparing walk to quest step.";
		}
		catch (IllegalStateException exception)
		{
			questError(exception.getMessage());
		}
	}

	private void updatePrayerWalk()
	{
		if (!prayerWalk) { return; }
		if (prayerRequest != walker.questRequestToken())
		{
			clearPrayerWalk();
			return;
		}
		int current = client.getBoostedSkillLevel(net.runelite.api.Skill.PRAYER);
		if (prayerDispatched && current > prayerBefore)
		{
			clearPrayerWalk();
			Microbot.status = "Prayer restored.";
			return;
		}
		if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN || ++prayerStableTicks < 2) { return; }
		if (walker.getStatus() != EfficientWalker.Status.ARRIVED) { return; }
		if (prayerDispatched)
		{
			Microbot.status = "Waiting for prayer restoration. Cancel walk stops automation.";
			return;
		}
		PrayerRestoration.Interaction interaction = PrayerRestoration.find(client);
		if (interaction == null)
		{
			prayerError("No usable prayer restoration object is visible at the destination.");
			return;
		}
		List<WorldPoint> approaches = PrayerRestoration.approaches(walker, interaction);
		if (approaches.isEmpty())
		{
			prayerError("Cannot verify a reachable tile in the prayer object's room.");
			return;
		}
		if (!approaches.contains(walker.currentLocation()))
		{
			if (!walker.walkToAny(approaches))
			{
				prayerError("Cannot reach a tile in the prayer object's room.");
				return;
			}
			prayerRequest = walker.questRequestToken();
			prayerWalkTarget = approaches.get(0);
			prayerStableTicks = 0;
			Microbot.status = "Walking into the prayer object's room.";
			return;
		}
		if (!walker.interactWithPrayerObject(interaction.object, interaction.action))
		{
			prayerError("No usable prayer restoration object is visible at the destination.");
			return;
		}
		prayerDispatched = true;
		Microbot.status = "Using the prayer restoration object.";
	}

	private void clearPrayerWalk()
	{
		prayerWalk = false;
		prayerBefore = -1;
		prayerDispatched = false;
		prayerRequest = null;
		prayerStableTicks = 0;
		prayerWalkStart = null;
		prayerWalkTarget = null;
	}

	private void prayerError(String reason)
	{
		WalkerError notice = new WalkerError(reason, prayerWalkStart, walker.currentLocation(), prayerWalkTarget);
		clearPrayerWalk();
		walker.cancelCurrent();
		walker.failControl(notice.text());
		Microbot.status = notice.text();
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", notice.chat(), null);
	}

	private void updateQuestWalk()
	{
		if (questAutomation != null)
		{
			try
			{
				if (!config.handleInteractions())
				{
					cancelWalk();
					Microbot.status = "Quest automation disabled.";
					return;
				}
				if (!pluginManager.isActive(this) || pluginManager.getPlugins().stream()
					.filter(plugin -> plugin.getClass().getName().equals(getClass().getName())).count() != 1)
				{
					throw new IllegalStateException("Enable exactly one Efficient Walker instance, then resume.");
				}
				if (!questAutomation.tick(client, pluginManager, walker)) { questAutomation = null; }
			}
			catch (IllegalStateException exception) { questError(exception.getMessage()); }
			return;
		}

		if (questStep == null) { return; }
		if (walker.questTravelPending())
		{
			Microbot.status = "Waiting for travel to finish. Cancel walk stops automation.";
			return;
		}
		try
		{
			if (!pluginManager.isActive(this))
			{
				throw new IllegalStateException("Enable Efficient Walker, then retry the quest walk.");
			}
			QuestStepTarget.Target target = QuestStepTarget.inspect(pluginManager, client);
			questWalkTarget = target.livePoint == null ? target.point : target.livePoint;
			if (target.step != questStep)
			{
				cancelWalk();
				Microbot.status = "Quest step changed. Press Walk to quest step for the new step.";
				return;
			}
			if (!questDestinations.isEmpty() && (walker.getStatus() == EfficientWalker.Status.BLOCKED
				|| walker.getStatus() == EfficientWalker.Status.IDLE
				|| walker.getStatus() != EfficientWalker.Status.ARRIVED
					&& !questDestinations.contains(walker.getDestination())))
			{
				clearQuestWalk();
				return;
			}
			if ((target.npc || target.object) && target.livePoint == null && questTargetSeen)
			{
				walker.cancelCurrent();
				questDestinations = Collections.emptyList();
				Microbot.status = "Waiting for the quest target to reappear.";
				return;
			}
			List<WorldPoint> destinations = walker.questDestinations(target);
			questTargetSeen |= target.livePoint != null;
			if (destinations.isEmpty())
			{
				throw new IllegalStateException("Cannot verify a safe tile beside the quest target. Move closer or enter its room, then retry.");
			}
			if (QuestDestination.loaded(client, target)
				&& destinations.contains(client.getLocalPlayer().getWorldLocation())
				&& !walker.isWalkInProgress())
			{
				stopCurrentWalk();
				Microbot.status = target.object ? "Reached the quest object. Interact with it to continue." : "Reached the quest NPC.";

				return;
			}
			if (!destinations.equals(questDestinations))
			{
				questDestinations = destinations;
				walker.walkToAny(destinations, QuestStepTarget.escort(client, target.step));
			}
			if (target.livePoint == null && walker.getStatus() == EfficientWalker.Status.ARRIVED)
			{
				Microbot.status = "Waiting for Quest Helper's target near the quest marker.";
			}
		}
		catch (IllegalStateException exception)
		{
			questError(exception.getMessage());
		}
	}

	private void clearQuestWalk()
	{
		if (questAutomation != null) { questAutomation.cancel(client); }
		questAutomation = null;
		questStep = null;
		questWalkStart = null;
		questWalkTarget = null;
		questDestinations = Collections.emptyList();
		questTargetSeen = false;
	}

	private void questError(String reason)
	{
		WalkerError notice = questAutomation != null ? questAutomation.errorNotice(reason, walker)
			: questStep != null ? new WalkerError(reason, questWalkStart, walker.currentLocation(), questWalkTarget)
			: walker.errorNotice(reason);
		stopCurrentWalk();
		walker.failControl(notice.text());
		Microbot.status = notice.text();
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", notice.chat(), null);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == net.runelite.api.InventoryID.INVENTORY.getId())
		{
			walker.onWhistleInventoryChanged(event.getItemContainer());
			if (questAutomation != null) { questAutomation.inventoryChanged(); }
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == net.runelite.api.GameState.LOADING)
		{
			walker.invalidateLandingStability();
		}
		if (event.getGameState() != net.runelite.api.GameState.LOGGED_IN)
		{
			releaseQuestSpace();
			prayerStableTicks = 0;
		}
		if (event.getGameState() != net.runelite.api.GameState.LOGGED_IN
			&& event.getGameState() != net.runelite.api.GameState.LOADING)
		{
			pendingHotkey.set(null);
			clearPrayerWalk();
			clearQuestWalk();
			pendingWorldMapWalk = null;
			walker.cancel();
			walker.invalidateWhistleCharges();
			walker.invalidateTeleportPreparation();
		}
	}

	private boolean handleActionFailure(String message)
	{
		if (client.getVarbitValue(net.runelite.api.gameval.VarbitID.CUTSCENE_STATUS) != 0) { return false; }
		if (prayerWalk && prayerDispatched && prayerRequest == walker.questRequestToken())
		{
			String reason = ActionFailure.reason(message);
			if (reason != null)
			{
				prayerError(reason);
				return true;
			}
		}
		boolean questRejected = questAutomation != null && questAutomation.rejectsAction(message, walker);
		if (!questRejected && !walker.rejectAction(message)) { return false; }
		clearQuestWalk();
		pendingWorldMapWalk = null;
		if (questRejected) { walker.failAction(ActionFailure.reason(message)); }
		return true;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.DIALOG
			&& Quetzals.whistleRecharged(Text.removeTags(event.getMessage())))
		{
			walker.invalidateWhistleCharges();
		}
		if (event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM
			|| event.getType() == ChatMessageType.DIALOG)
		{
			walker.onGameMessage(Text.removeTags(event.getMessage()));
			handleActionFailure(event.getMessage());
		}
	}

	@Subscribe
	public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked event)
	{
		if (walker != null) { walker.onTeleportMenuAction(event); }
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		final Point mouse = client.getMouseCanvasPosition();
		final Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map != null && map.getBounds().contains(mouse.getX(), mouse.getY()))
		{
			addWalkEntries(mapPoint(mouse), true);
			return;
		}

		if (event.getMenuEntry().getType() != MenuAction.WALK)
		{
			return;
		}

		final WorldView worldView = client.getWorldView(event.getMenuEntry().getWorldViewId());
		final Tile tile = worldView == null ? null : worldView.getSelectedSceneTile();
		addWalkEntries(tile == null ? null : tile.getWorldLocation(), false);
	}

	private void addWalkEntries(WorldPoint target, boolean worldMap)
	{
		if (target == null || hasWalkEntries())
		{
			return;
		}

		client.createMenuEntry(0)
			.setOption(DRY_RUN_COLORED)
			.setTarget("Tile")
			.setType(MenuAction.RUNELITE)
			.setDeprioritized(true)
			.onClick(entry -> dryRun(target));
		client.createMenuEntry(0)
			.setOption(BEGIN_WALK_COLORED)
			.setTarget("Tile")
			.setType(MenuAction.RUNELITE)
			.setDeprioritized(true)
			.onClick(entry -> testWalk(target, worldMap));
		if (walker.isWalkInProgress())
		{
			client.createMenuEntry(0)
				.setOption(STOP_WALK_COLORED)
				.setTarget("Tile")
				.setType(MenuAction.RUNELITE)
				.setDeprioritized(true)
				.onClick(entry -> cancelWalk());
		}
	}

	private void stopCurrentWalk()
	{
		if (prayerWalk && prayerRequest == walker.questRequestToken())
		{
			Microbot.status = prayerDispatched && client.getBoostedSkillLevel(net.runelite.api.Skill.PRAYER) > prayerBefore
				? "Prayer restored." : "Walk cancelled.";
		}
		questContinueCanceled = QuestDialogue.continueReady(client);
		releaseQuestSpace();
		clearPrayerWalk();
		clearQuestWalk();
		pendingHotkey.set(null);
		pendingWorldMapWalk = null;
		walker.cancelCurrent();
	}

	private void testWalk(WorldPoint target, boolean worldMap)
	{
		automationControl = walker.beginControl();
		pendingHotkey.set(null);
		clearQuestWalk();
		if (worldMap)
		{
			if (!walker.preview(target))
			{
				return;
			}
			pendingWorldMapWalk = target;
			return;
		}
		pendingWorldMapWalk = null;
		walker.testPreviewedWalk(target);
	}

	private void dryRun(WorldPoint target)
	{
		automationControl = walker.beginControl();
		pendingHotkey.set(null);
		clearQuestWalk();
		walker.preview(target);
	}

	private void closeWorldMap()
	{
		final Widget close = client.getWidget(InterfaceID.Worldmap.CLOSE);
		if (close == null)
		{
			return;
		}
		final Rectangle bounds = Rs2UiHelper.isRectangleWithinCanvas(close.getBounds())
			? close.getBounds() : Rs2UiHelper.getDefaultRectangle();
		final Point point = Rs2UiHelper.getClickingPoint(bounds, true);
		WalkerInput.click(net.runelite.client.plugins.microbot.Microbot.getClient(), point);
	}

	static String planningFailureMessage(EfficientWalker.PlanningFailure failure,
		WorldPoint start, WorldPoint target)
	{
		if (failure == EfficientWalker.PlanningFailure.TARGET_NOT_STANDABLE)
		{
			return "Cannot stand on the selected tile. Choose a nearby walkable tile.";
		}
		if (failure == EfficientWalker.PlanningFailure.SEARCH_LIMIT)
		{
			return "Route calculation timed out. Try again or choose a closer destination.";
		}
		return "Could not find a supported route. Choose another destination or move closer and retry";
	}

	private boolean hasWalkEntries()
	{
		return Arrays.stream(client.getMenuEntries())
			.map(MenuEntry::getOption)
			.map(Text::removeTags)
			.anyMatch(option -> DRY_RUN.equals(option) || BEGIN_WALK.equals(option) || STOP_WALK.equals(option));
	}

	private WorldPoint mapPoint(Point point)
	{
		final WorldMap worldMap = client.getWorldMap();
		if (worldMap == null || worldMap.getWorldMapData() == null)
		{
			return null;
		}

		final Point position = worldMap.getWorldMapPosition();
		final WorldPoint center = new WorldPoint(position.getX(), position.getY(), 0);
		final Point graphicsCenter = worldMapOverlay.mapWorldPointToGraphicsPoint(center);
		final float zoom = worldMap.getWorldMapZoom();
		if (graphicsCenter == null || zoom <= 0)
		{
			return null;
		}

		final int dx = (int) ((point.getX() - graphicsCenter.getX()) / zoom);
		final int dy = (int) (-(point.getY() - graphicsCenter.getY()) / zoom);
		return center.dx(dx).dy(dy);
	}
}
