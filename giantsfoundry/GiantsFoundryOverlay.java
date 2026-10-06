package net.runelite.client.plugins.microbot.giantsfoundry;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

final class GiantsFoundryOverlay extends OverlayPanel
{
	private final GiantsFoundryPlugin plugin;

	@Inject
	GiantsFoundryOverlay(GiantsFoundryPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.BOTTOM_LEFT);
		panelComponent.setPreferredSize(new Dimension(240, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder().text("Giants' Foundry").color(Color.CYAN).build());
		line("State", plugin.getState().replace('_', ' '));
		line("Swords", Integer.toString(plugin.getCompletedSwords()));
		long seconds = plugin.getSessionRuntimeMillis() / 1_000;
		line("Runtime", String.format("%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60));
		line("Smithing XP", String.format("%,d · %,d/hr", plugin.getSmithingXpGained(),
			plugin.getSmithingXpPerHour()));
		line("Reputation", String.format("%,d", plugin.getReputationGained()));
		FoundryData.Snapshot snapshot = plugin.snapshot();
		if (snapshot != null && (snapshot.hasPreform() || snapshot.bit(13947) == 1))
		{
			line("Progress", snapshot.bit(13949) / 10 + "%");
			line("Quality", snapshot.bit(13939) + " / " + snapshot.bit(13950));
		}
		line(plugin.getStatus(), "");
		return super.render(graphics);
	}

	private void line(String left, String right)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).build());
	}
}
