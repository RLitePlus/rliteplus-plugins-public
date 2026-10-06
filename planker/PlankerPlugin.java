package net.runelite.client.plugins.microbot.planker;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Provider;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = PluginDescriptor.Default + "Planker",
	description = "Converts logs at Auburnvale Sawmill and manages supplies at the Grand Exchange",
	tags = {"planks", "sawmill", "grand exchange", "microbot"},
	enabledByDefault = false,
	version = PlankerPlugin.VERSION
)
public final class PlankerPlugin extends Plugin
{
	public static final String VERSION = "1.0.5";

	@Inject
	private Provider<PlankerScript> scriptProvider;

	@Inject
	private PlankerConfig config;

	@Inject
	private PlankerOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	private volatile PlankerScript script;

	private volatile PlankerScript stoppedScript;

	@Provides
	PlankerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PlankerConfig.class);
	}

	@Override
	protected void startUp()
	{
		if (script != null)
		{
			return;
		}
		script = scriptProvider.get();
		stoppedScript = null;
		try
		{
			script.start(config);
			overlayManager.add(overlay);
		}
		catch (RuntimeException exception)
		{
			try
			{
				shutDown();
			}
			catch (RuntimeException cleanupFailure)
			{
				exception.addSuppressed(cleanupFailure);
			}
			throw exception;
		}
	}

	@Override
	protected void shutDown()
	{
		PlankerScript current = script;
		script = null;
		try
		{
			if (current != null)
			{
				stoppedScript = current;
				current.shutdown();
			}
		}
		finally
		{
			overlayManager.remove(overlay);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		PlankerScript current = script;
		if (current != null)
		{
			current.onGameTick();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		PlankerScript current = script;
		if (current != null)
		{
			current.onItemContainerChanged(event);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		PlankerScript current = script;
		if (current != null)
		{
			current.onGameStateChanged(event);
		}
	}

	public String getState()
	{
		PlankerScript current = getOverlayScript();
		return current == null ? "STOPPED" : current.getState();
	}

	public String getError()
	{
		PlankerScript current = getOverlayScript();
		return current == null ? null : current.getError();
	}

	public String getStatus()
	{
		PlankerScript current = getOverlayScript();
		return current == null ? "Stopped" : current.getStatus();
	}

	public String getTrade()
	{
		PlankerScript current = getOverlayScript();
		return current == null ? "" : current.getTrade();
	}

	public long getProduced()
	{
		PlankerScript current = getOverlayScript();
		return current == null ? 0 : current.getProduced();
	}

	public long getSold()
	{
		PlankerScript current = getOverlayScript();
		return current == null ? 0 : current.getSold();
	}

	PlankerScript getOverlayScript()
	{
		PlankerScript current = script;
		return current == null ? stoppedScript : current;
	}

	public int getBanked()
	{
		PlankerScript current = getOverlayScript();
		return current == null ? 0 : current.getBanked();
	}

	public int getSellThreshold()
	{
		PlankerScript current = getOverlayScript();
		return current == null ? 0 : current.getSellThreshold();
	}
}
