package net.runelite.client.plugins.microbot.enchanter;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

final class EnchanterOverlay extends OverlayPanel
{
	private final EnchanterPlugin plugin;
	private final EnchanterConfig config;

	@Inject
	EnchanterOverlay(EnchanterPlugin plugin, EnchanterConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
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
			.text("Enchanter")
			.color(Color.CYAN)
			.build());
		line("Target", plugin.getTarget().toString());
		line("State", plugin.getError() == null
			? plugin.getState().replace('_', ' ') : "ERROR: " + plugin.getError());
		line("Enchanted", Integer.toString(plugin.getCompletedEffects()));
		return super.render(graphics);
	}

	private void line(String left, String right)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(left)
			.right(right)
			.build());
	}
}
