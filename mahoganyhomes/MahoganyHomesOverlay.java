package net.runelite.client.plugins.microbot.mahoganyhomes;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

final class MahoganyHomesOverlay extends OverlayPanel
{
	private final MahoganyHomesPlugin plugin;
	private final MahoganyHomesConfig config;

	@Inject
	MahoganyHomesOverlay(MahoganyHomesPlugin plugin, MahoganyHomesConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPreferredSize(new Dimension(ComponentConstants.STANDARD_WIDTH * 5 / 4, 0));
		panelComponent.setPreferredSize(
			new Dimension(ComponentConstants.STANDARD_WIDTH * 5 / 4, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (config.hideOverlay())
		{
			return null;
		}
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Mahogany Homes")
			.color(Color.GREEN)
			.build());
		line("State", plugin.getError() == null
			? plugin.getState().replace('_', ' ') : "ERROR: " + plugin.getError());

		MahoganyHomesData tier = plugin.getAssignedTier();
		String homeowner = plugin.getAssignedHomeowner();
		line("Contract", homeowner == null ? "None"
			: homeowner + (tier == null ? "" : " · " + tier.getName()));
		line("Session", plugin.getSessionContracts() + " contracts");
		line("Runtime", formatRuntime(plugin.getSessionRuntimeMillis()));
		line("XP", String.format("%,d · %,d/hr", plugin.getConstructionXpGained(),
			plugin.getConstructionXpPerHour()));
		return super.render(graphics);
	}

	private void line(String left, String right)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(left)
			.right(right)
			.build());
	}

	private static String formatRuntime(long milliseconds)
	{
		long seconds = milliseconds / 1_000;
		return String.format("%02d:%02d:%02d", seconds / 3_600,
			seconds / 60 % 60, seconds % 60);
	}
}
