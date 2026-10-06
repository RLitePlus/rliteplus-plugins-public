package net.runelite.client.plugins.microbot.huntersrumours;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

final class HuntersRumoursOverlay extends OverlayPanel
{
	private final HuntersRumoursPlugin plugin;

	@Inject
	HuntersRumoursOverlay(HuntersRumoursPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Pause", "Hunters' Rumours", entry -> plugin.pause());
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Resume", "Hunters' Rumours", entry -> plugin.resume());
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Finish rumour and stop", "Hunters' Rumours", entry -> plugin.finishCurrentRumour());
		panelComponent.setPreferredSize(new Dimension(240, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder().text("Hunters' Rumours").color(Color.GREEN).build());
		line("Status", readable(plugin.getState()));
		line("Rumour", plugin.getAssignedHunter() == null ? "Awaiting assignment"
			: readable(plugin.getAssignedHunter()) + ": " + readable(plugin.getAssignedCreature()));
		line("Completed", Integer.toString(plugin.getCompletedThisRun()));
		long seconds = plugin.elapsedSeconds();
		line("Runtime", String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60));
		int xp = plugin.hunterXpGained();
		if (xp >= 0)
		{
			line("Hunter XP", String.format(Locale.ROOT, "%,d", xp));
			line("XP/hr", seconds > 0 ? String.format(Locale.ROOT, "%,d", xp * 3600L / seconds) : "—");
		}
		if (plugin.goalReason() != null) line("Goal", plugin.goalReason());
		if (plugin.getError() != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Error: " + plugin.getError()).leftColor(Color.RED).build());
		}
		return super.render(graphics);
	}

	private void line(String left, String right)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).build());
	}

	private static String readable(String value)
	{
		return value == null ? "Unknown" : value.replace('_', ' ').toLowerCase(Locale.ROOT);
	}
}
