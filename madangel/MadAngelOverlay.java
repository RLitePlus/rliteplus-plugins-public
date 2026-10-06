package net.runelite.client.plugins.microbot.madangel;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

final class MadAngelOverlay extends OverlayPanel
{
	private final MadAngelPlugin plugin;

	@Inject
	MadAngelOverlay(MadAngelPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(LineComponent.builder().left("Mad Angel").right(plugin.getStatus()).build());
		return super.render(graphics);
	}
}
