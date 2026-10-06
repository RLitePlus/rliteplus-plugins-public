package net.runelite.client.plugins.microbot.enchanter;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Provider;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = PluginDescriptor.Default + "Enchanter",
	description = "Enchant verified jewellery and tipped bolts from the bank",
	tags = {"magic", "enchanting", "jewellery", "bolts", "microbot"},
	enabledByDefault = false,
	version = EnchanterPlugin.VERSION
)
public final class EnchanterPlugin extends Plugin
{
	public static final String VERSION = "1.0.3";

	@Inject
	private Provider<EnchanterScript> scriptProvider;

	@Inject
	private EnchanterConfig config;

	@Inject
	private EnchanterOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	private EnchanterScript script;

	@Provides
	EnchanterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EnchanterConfig.class);
	}

	@Override
	protected void startUp()
	{
		script = scriptProvider.get();
		overlayManager.add(overlay);
		script.run(config.target());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		if (script != null)
		{
			script.shutdown();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (script != null)
		{
			script.onGameTick(Microbot.getClient().getTickCount());
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (script != null && event.getContainerId() == InventoryID.INV)
		{
			script.onInventoryChanged(Microbot.getClient().getTickCount(), event.getItemContainer());
		}
	}

	public String getState()
	{
		return script == null ? "STARTING" : script.getStateName();
	}

	public String getError()
	{
		return script == null ? null : script.getError();
	}

	public int getCompletedEffects()
	{
		return script == null ? 0 : script.getCompletedEffects();
	}

	public boolean isComplete()
	{
		return script != null && script.isComplete();
	}

	public Enchantment getTarget()
	{
		return script == null ? config.target() : script.getTarget();
	}
}
