package net.runelite.client.plugins.microbot.mahoganyhomes;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Provider;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = PluginDescriptor.Default + "Mahogany Homes",
	description = "Prepares and completes verified Mahogany Homes contracts",
	tags = {"mahogany homes", "construction", "microbot"},
	enabledByDefault = false,
	version = MahoganyHomesPlugin.VERSION
)
public final class MahoganyHomesPlugin extends Plugin
{
	public static final String VERSION = "1.0.3";

	@Inject
	Provider<MahoganyHomesScript> scriptProvider;

	private MahoganyHomesScript script;

	@Inject
	private MahoganyHomesConfig config;

	@Inject
	private MahoganyHomesOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	private volatile int sessionContracts;
	private volatile int startingConstructionXp;
	private volatile long sessionStartedAt;

	@Provides
	MahoganyHomesConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MahoganyHomesConfig.class);
	}

	@Override
	protected void startUp()
	{
		script = newScript();
		sessionContracts = 0;
		startingConstructionXp = Microbot.getClient().getSkillExperience(Skill.CONSTRUCTION);
		sessionStartedAt = System.currentTimeMillis();
		overlayManager.add(overlay);
		script.run(config);
	}

	MahoganyHomesScript newScript()
	{
		return scriptProvider.get();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		script.shutdown();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		script.onGameTick(Microbot.getClient().getTickCount());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.GAMEMESSAGE)
		{
			boolean alreadyComplete = script.isComplete();
			script.onGameMessage(event.getMessage());
			if (!alreadyComplete && script.isComplete())
			{
				sessionContracts++;
			}
		}
	}

	public String getState()
	{
		return script.getStateName();
	}

	public String getError()
	{
		return script.getError();
	}

	public int getCompletedEffects()
	{
		return script.getCompletedEffects();
	}

	public int[] getHotspotValues()
	{
		return script.getHotspotValues();
	}

	public int getCurrentRequiredPlanks()
	{
		return script.getCurrentRequiredPlanks();
	}

	public int getCurrentRequiredSteelBars()
	{
		return script.getCurrentRequiredSteelBars();
	}

	public boolean isComplete()
	{
		return script.isComplete();
	}

	public String getAssignedHomeowner()
	{
		return script.getAssignedHomeowner();
	}

	public MahoganyHomesData getAssignedTier()
	{
		return script.getAssignedTier();
	}

	public boolean isContractArrived()
	{
		return script.isContractArrived();
	}

	public boolean isContractValidated()
	{
		return script.isContractValidated();
	}

	public WorldPoint getContractDestination()
	{
		return script.getContractDestination();
	}

	public int getSessionContracts()
	{
		return sessionContracts;
	}

	public int getConstructionXpGained()
	{
		return Math.max(0, Microbot.getClient().getSkillExperience(Skill.CONSTRUCTION)
			- startingConstructionXp);
	}

	public int getConstructionXpPerHour()
	{
		long elapsed = Math.max(1, System.currentTimeMillis() - sessionStartedAt);
		return (int) (getConstructionXpGained() * 3_600_000L / elapsed);
	}

	public long getSessionRuntimeMillis()
	{
		return Math.max(0, System.currentTimeMillis() - sessionStartedAt);
	}
}
