package net.runelite.client.plugins.microbot.huntersrumours;

import javax.inject.Inject;
import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.util.Text;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = PluginDescriptor.Default + "Hunters' Rumours",
	description = "Prepares and completes Hunter Guild rumours",
	tags = {"hunter", "rumours", "guild"},
	enabledByDefault = false,
	version = "1.0.0"
)
public final class HuntersRumoursPlugin extends Plugin
{
	@Inject
	private Client client;
	@Inject
	private PluginManager manager;
	@Inject
	private ClientThread clientThread;
	@Inject
	private HuntersRumoursConfig config;
	@Inject
	private ConfigManager configManager;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private HuntersRumoursOverlay overlay;
	private String historyProfile;
	private String savedHistory;
	private int startingHunterXp = -1;
	private volatile int hunterXpGained = -1;
	private volatile long elapsedSeconds;
	private HuntersRumoursRuntime runtime;

	@Provides
	HuntersRumoursConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HuntersRumoursConfig.class);
	}
	private String reportedError;
	private HuntersRumoursScript script;

	@Override
	protected void startUp()
	{
		reportedError = null;
		historyProfile = null;
		savedHistory = null;
		startingHunterXp = -1;
		hunterXpGained = -1;
		elapsedSeconds = 0;
		runtime = new HuntersRumoursRuntime(manager);
		runtime.goals(config.sessionTarget(), config.hunterTarget(), config.runtimeMinutes());
		HuntersRumoursConfig.HunterChoice selected = config.hunter();
		runtime.selectedHunter(selected.hunter());
		runtime.start();
		String savedHunter = configManager.getConfiguration("huntersrumours", "hunter");
		if (savedHunter != null && !savedHunter.equals(selected.name()))
			runtime.block("The saved hunter is unsupported. Select Gilman, Cervus, Ornus, Aco or Teco in the configuration and restart.");
		script = new HuntersRumoursScript();
		overlayManager.add(overlay);
		publishStatus();
	}

	@Override
	protected void shutDown()
	{
		saveHistory();
		overlayManager.remove(overlay);
		if (runtime != null) runtime.stop();
		if (script != null) script.shutdown();
		publishStatus();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (runtime == null) return;
		if (runtime.state() != HuntersRumoursRuntime.State.STOPPED
			&& runtime.state() != HuntersRumoursRuntime.State.GOAL_REACHED && runtime.error() == null)
			elapsedSeconds = runtime.elapsedSeconds();
		boolean ready = client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null
			&& client.getItemContainer(InventoryID.INVENTORY) != null
			&& client.getItemContainer(InventoryID.EQUIPMENT) != null
			&& (client.getTopLevelInterfaceId() == 548 || client.getTopLevelInterfaceId() == 161
				|| client.getTopLevelInterfaceId() == 164 || client.getTopLevelInterfaceId() == 601);
		if (ready)
		{
			String profile = configManager.getRSProfileKey();
			if (historyProfile != null && !historyProfile.equals(profile))
			{
				runtime.history.restore(null);
				hunterXpGained = -1;
				runtime.block("The account profile changed. Restart Hunters' Rumours for this account.");
				script.cancelInput();
				publishStatus();
				return;
			}
			if (historyProfile == null && profile != null)
			{
				historyProfile = profile;
				savedHistory = configManager.getRSProfileConfiguration("huntersrumours", "assignmentHistory");
				runtime.history.restore(savedHistory);
				try
				{
					script.restoreTraps(configManager.getRSProfileConfiguration("huntersrumours", "trapRecovery"), this::saveTraps);
					runtime.recoveringTraps(script.restoringTraps());
				}
				catch (IllegalArgumentException exception)
				{
					runtime.block("The saved trap recovery record is invalid. Inspect the hunting area and recover any remaining tools manually; automatic recovery is unavailable.");
				}
			}
			int xp = client.getSkillExperience(Skill.HUNTER);
			if (xp >= 0 && runtime.state() != HuntersRumoursRuntime.State.STOPPED
				&& runtime.state() != HuntersRumoursRuntime.State.GOAL_REACHED && runtime.error() == null)
			{
				if (startingHunterXp < 0) startingHunterXp = xp;
				hunterXpGained = Math.max(0, xp - startingHunterXp);
			}
			Widget speaker = client.getWidget(InterfaceID.ChatLeft.NAME);
			Widget dialogue = client.getWidget(InterfaceID.ChatLeft.TEXT);
			if (speaker != null && dialogue != null && !speaker.isHidden() && !dialogue.isHidden())
			{
				runtime.dialogue(speaker.getText(), dialogue.getText());
			}
		}
		if (ready) runtime.observeProgress(client.getRealSkillLevel(Skill.HUNTER), runtime.elapsedSeconds());
		runtime.tick(ready, client.getGameState() == GameState.LOADING);
		if (ready && historyProfile != null && !script.restoringTraps() && !Microbot.pauseAllScripts.get() && runtime.beginNextRumour())
		{
			script.shutdown();
			script = new HuntersRumoursScript();
			script.restoreTraps(null, this::saveTraps);
		}
		if (ready && historyProfile != null && script.restoringTraps() && !Microbot.pauseAllScripts.get() && runtime.mayAct())
		{
			script.prepareTask(runtime);
		}
		else if (ready && historyProfile != null && !Microbot.pauseAllScripts.get() && runtime.mayRequestAssignment())
		{
			script.requestAssignment(runtime, client.getTickCount());
		}
		else if (ready && historyProfile != null && !Microbot.pauseAllScripts.get() && runtime.mayAct() && runtime.assignment() != null)
		{
			script.prepareTask(runtime);
		}
		if (!ready || getError() != null || Microbot.pauseAllScripts.get()) script.cancelInput();
		if (ready) saveHistory();
		publishStatus();
	}

	private void saveTraps(String value)
	{
		if (historyProfile == null || !historyProfile.equals(configManager.getRSProfileKey()))
			throw new IllegalStateException("The account profile changed before saving trap recovery");
		configManager.setRSProfileConfiguration("huntersrumours", "trapRecovery", value);
	}

	private void saveHistory()
	{
		if (runtime != null && historyProfile != null && historyProfile.equals(configManager.getRSProfileKey()))
		{
			String history = runtime.history.encode();
			if (!history.equals(savedHistory))
			{
				configManager.setRSProfileConfiguration("huntersrumours", "assignmentHistory", history);
				savedHistory = history;
			}
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (runtime != null && event.getActor() != null && event.getActor() == client.getLocalPlayer())
		{
			if (script != null) script.cancelInput();
			runtime.playerDied();
			publishStatus();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (runtime != null && event.getGameState() != GameState.LOGGED_IN)
		{
			if (event.getGameState() != GameState.LOADING) runtime.history.loseContinuity();
			runtime.tick(false, event.getGameState() == GameState.LOADING);
			if (script != null) script.cancelInput();
			publishStatus();
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		int id = event.getGameObject().getId();
		if (script != null && (id == 9380 || id == 9345))
			script.boxPlaced(event.getGameObject().getWorldLocation(), id, client.getTickCount());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (script != null) script.butterflyDespawned(event.getNpc(), client.getTickCount());
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (script != null && event.getScriptId() == 5474 && event.getScriptEvent() != null)
			script.trapTimer(event.getScriptEvent().getArguments(), client.getTickCount());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (runtime != null && script != null && event.getType() == ChatMessageType.GAMEMESSAGE)
		{
			runtime.reminder(event.getMessage());
			script.falconMessage(event.getMessage());
			script.pitMessage(Text.removeTags(event.getMessage()), client.getTickCount());
			script.herbiboarMessage(Text.removeTags(event.getMessage()), client.getTickCount());
		}
		if (runtime == null || event.getType() != ChatMessageType.DIALOG) return;
		String message = event.getMessage();
		int separator = message == null ? -1 : message.indexOf('|');
		if (separator >= 0) runtime.dialogue(message.substring(0, separator), message.substring(separator + 1));
	}

	public void finishCurrentRumour()
	{
		if (runtime != null) runtime.finishCurrentRumour();
		publishStatus();
	}

	public void pause()
	{
		if (runtime != null) runtime.pause();
		if (script != null) script.cancelInput();
	}

	public void resume()
	{
		if (runtime != null) runtime.resume();
	}

	public String getState()
	{
		return runtime == null ? "STOPPED" : runtime.state().name();
	}

	public String getError()
	{
		return runtime == null ? null : runtime.error();
	}

	long elapsedSeconds()
	{
		return elapsedSeconds;
	}

	int hunterXpGained()
	{
		return hunterXpGained;
	}

	String huntingMethod()
	{
		return runtime == null || runtime.assignment() == null ? null : runtime.assignment().creature.huntingMethod();
	}

	String travelTarget()
	{
		return runtime == null ? null : runtime.travelTarget();
	}

	String goalReason()
	{
		return runtime == null ? null : runtime.goalReason();
	}

	public int getVerifiedCatches()
	{
		return script == null ? 0 : script.verifiedCatches();
	}

	public int getCompletedThisRun()
	{
		return runtime == null ? 0 : runtime.completedThisRun();
	}

	public int getCompletedTotal()
	{
		return runtime == null ? -1 : runtime.completedTotal();
	}

	public String getAssignedCreature()
	{
		return runtime == null || runtime.assignment() == null ? null : runtime.assignment().creature.name();
	}

	public String getAssignedHunter()
	{
		return runtime == null || runtime.assignment() == null ? null : runtime.assignment().hunter.name();
	}

	public java.util.Map<String, String> getAssignmentHistory()
	{
		return runtime == null ? java.util.Collections.emptyMap() : runtime.history.display();
	}

	private void publishStatus()
	{
		clientThread.invoke(this::publishStatusOnClientThread);
	}

	private void publishStatusOnClientThread()
	{
		String error = getError();
		Microbot.status = error == null ? "Hunters' Rumours: " + getState()
			: "Error: Hunters' Rumours: " + error;
		if (error == null && runtime != null && runtime.goalReason() != null)
			Microbot.status += " — " + runtime.goalReason()
				+ (runtime.assignment() == null ? "" : "; finishing current rumour");
		if (error != null && !error.equals(reportedError) && client.getGameState() == GameState.LOGGED_IN)
		{
			reportedError = error;
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff0000>Error: Hunters' Rumours: " + Text.removeTags(error) + "</col>", "");
		}
	}
}
