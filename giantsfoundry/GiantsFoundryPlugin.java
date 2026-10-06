package net.runelite.client.plugins.microbot.giantsfoundry;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Provider;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(name = PluginDescriptor.Default + "Giants' Foundry",
	description = "Forge swords from banked bars with verified temperature and progress",
	tags = {"smithing", "foundry", "microbot"}, enabledByDefault = false, version = "1.0.2")
public final class GiantsFoundryPlugin extends Plugin
{
	@Inject private Provider<GiantsFoundryScript> scriptProvider;
	@Inject private GiantsFoundryConfig config;
	@Inject private OverlayManager overlayManager;
	@Inject private GiantsFoundryOverlay overlay;
	@Inject private net.runelite.client.eventbus.EventBus eventBus;
	private GiantsFoundryScript script;
	private boolean restartRequested;
	private boolean stopping;

	@Provides
	GiantsFoundryConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(GiantsFoundryConfig.class);
	}

	@Override
	protected void startUp()
	{
		if (stopping) { restartRequested = true; return; }
		script = scriptProvider.get();
		eventBus.register(script);
		script.start(config);
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		restartRequested = false;
		if (script != null && !stopping)
		{
			stopping = true;
			GiantsFoundryScript stoppingScript = script;
			stoppingScript.requestStop(() -> {
				eventBus.unregister(stoppingScript);
				stoppingScript.shutdown();
				javax.swing.SwingUtilities.invokeLater(() -> {
					stopping = false;
					overlayManager.remove(overlay);
					if (restartRequested)
					{
						restartRequested = false;
						startUp();
					}
				});
			});
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (script == null || event.getScriptId() != 6122 || event.getScriptEvent() == null) return;
		Object[] arguments = event.getScriptEvent().getArguments();
		if (arguments != null && arguments.length == 2 && Integer.valueOf(2).equals(arguments[1]))
			script.onSweetSpotAccepted();
	}

	FoundryData.Snapshot snapshot()
	{
		return script == null ? null : script.latestSnapshot();
	}

	public String getStatus()
	{
		return script == null ? "Starting" : script.getStatus();
	}

	public String getState()
	{
		return script == null ? "STARTING" : script.getStateName();
	}

	public String getError()
	{
		return script == null ? null : script.error;
	}

	public long getSessionRuntimeMillis()
	{
		return script == null ? 0 : script.sessionRuntimeMillis;
	}

	public int getSmithingXpGained()
	{
		return script == null ? 0 : script.smithingXpGained;
	}

	public long getSmithingXpPerHour()
	{
		return getSmithingXpGained() * 3_600_000L / Math.max(1_000, getSessionRuntimeMillis());
	}

	public int getReputationGained()
	{
		return script == null ? 0 : script.reputationGained;
	}

	public int getCompletedBonuses()
	{
		return script == null ? 0 : script.completedBonuses;
	}

	public int getCompletedSwords()
	{
		return script == null ? 0 : script.completedSwords;
	}
}
