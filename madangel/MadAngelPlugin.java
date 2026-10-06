package net.runelite.client.plugins.microbot.madangel;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;

@PluginDescriptor(name = "Mad Angel", description = "Fights and loots Mad Angel repeatedly with food, prayers, and an emergency escape",
	tags = {"combat", "mad angel"}, enabledByDefault = false, version = "1.0.0")
public final class MadAngelPlugin extends Plugin
{
	@Inject
	private Client client;
	@Inject
	private PluginManager manager;
	@Inject
	private MadAngelConfig config;
	private MadAngelScript script;
	@Inject
	private MadAngelOverlay overlay;
	@Inject
	private net.runelite.client.ui.overlay.OverlayManager overlayManager;

	@Provides
	MadAngelConfig provideConfig(ConfigManager manager) { return manager.getConfig(MadAngelConfig.class); }

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		script = new MadAngelScript(client, manager);
		MadAngelScript current = script;
		net.runelite.client.plugins.microbot.Microbot.getClientThread().invoke(() -> { current.run(config); });
	}
	@Override
	protected void shutDown() { overlayManager.remove(overlay); if (script != null) { script.shutdown(); } }
	@Subscribe
	public void onGameTick(GameTick event) { if (script != null) { script.onGameTick(); } }
	@Subscribe
	public void onAnimationChanged(AnimationChanged event) { if (script != null) { script.onAnimationChanged(event); } }
	@Subscribe
	public void onProjectileMoved(ProjectileMoved event) { if (script != null) { script.onProjectileMoved(event); } }

	public String getStatus() { return script == null ? "Stopped" : script.getStatus(); }
	public String getError() { return script == null ? null : script.getError(); }
	public java.util.Map<String, Object> getTelemetry() { return script == null ? java.util.Collections.emptyMap() : script.getTelemetry(); }
}
