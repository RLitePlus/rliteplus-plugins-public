package net.runelite.client.plugins.microbot.tithefarm;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Provider;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.plugins.microbot.util.mouse.BotEventGuard;

@PluginDescriptor(
	name = PluginDescriptor.Default + "Tithe Farm",
	description = "Runs Lazy and Moderate Tithe Farm rotations",
	tags = {"farming", "tithe", "microbot"},
	enabledByDefault = false,
	version = "1.2.1"
)
public final class TitheFarmPlugin extends Plugin
{
	@Inject
	private Provider<TitheFarmScript> scripts;
	@Inject
	private TitheFarmOverlay overlay;
	@Inject
	private OverlayManager overlays;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private KeyManager keyManager;
	private final UserInput userInput = new UserInput();
	private TitheFarmScript script;
	private long startedNanos;

	@com.google.inject.Provides
	TitheFarmConfig provideConfig(net.runelite.client.config.ConfigManager manager)
	{
		return manager.getConfig(TitheFarmConfig.class);
	}

	@Override
	protected void startUp()
	{
		if (script != null)
		{
			script.shutdown();
		}
		script = scripts.get();
		startedNanos = System.nanoTime();
		overlays.add(overlay);
		mouseManager.registerMouseListener(userInput);
		keyManager.registerKeyListener(userInput);
		script.start();
	}

	@Override
	protected void shutDown()
	{
		mouseManager.unregisterMouseListener(userInput);
		keyManager.unregisterKeyListener(userInput);
		overlays.remove(overlay);
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

	private final class UserInput extends MouseAdapter implements KeyListener
	{
		private void interrupt()
		{
			if (script != null && !BotEventGuard.isSynthetic()) { script.onUserInput(); }
		}

		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			interrupt();
			return event;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event) { interrupt(); return event; }
		@Override
		public void keyPressed(KeyEvent event) { interrupt(); }
		@Override
		public void keyReleased(KeyEvent event) { }
		@Override
		public void keyTyped(KeyEvent event) { }
	}

	public long getRuntimeSeconds() { return startedNanos == 0 ? 0 : (System.nanoTime() - startedNanos) / 1_000_000_000L; }
	public String getMode() { return script == null ? "Stopped" : script.getMode(); }
	public int getDeaths() { return script == null ? 0 : script.getDeaths(); }
	public int getCycles() { return script == null ? 0 : script.getCycles(); }
	public int getRefills() { return script == null ? 0 : script.getRefills(); }
	public int getWater() { return script == null ? 0 : script.getWater(); }
	public int getXpGained() { return script == null ? 0 : script.getXpGained(); }
	public int getPointsGained() { return script == null ? 0 : script.getPointsGained(); }
	public String getStatus() { return script == null ? "Stopped" : script.getStatus(); }
	public String getError() { return script == null ? null : script.getError(); }
	public int getWatered() { return script == null ? 0 : script.getWatered(); }
	public int getHarvested() { return script == null ? 0 : script.getHarvested(); }
	public int getDeposited() { return script == null ? 0 : script.getDeposited(); }
	public boolean isComplete() { return script != null && script.isComplete(); }
}
