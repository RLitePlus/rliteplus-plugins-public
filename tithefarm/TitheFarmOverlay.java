package net.runelite.client.plugins.microbot.tithefarm;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

final class TitheFarmOverlay extends OverlayPanel
{
	private final TitheFarmPlugin plugin;

	@Inject
	TitheFarmOverlay(TitheFarmPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
		panelComponent.setPreferredSize(new Dimension(240, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder().text("Tithe Farm - " + plugin.getMode())
			.color(plugin.getError() == null ? Color.GREEN : Color.RED).build());
		String status = plugin.getStatus();
		String[] parts = status.split(" - patch ", 2);
		String action;
		switch (parts[0])
		{
			case "PLANT": action = "Planting"; break;
			case "WATER_SECOND": case "WATER_THIRD": action = "Watering"; break;
			case "HARVEST": action = "Harvesting"; break;
			case "DEPOSIT": action = "Depositing"; break;
			case "Refilling / recovering run energy": action = "Refilling / resting"; break;
			default: action = parts[0]; break;
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left(action + (parts.length == 2 ? " · " + parts[1] : ""))
			.leftColor(plugin.getError() == null ? Color.WHITE : Color.RED).build());
		long seconds = plugin.getRuntimeSeconds();
		panelComponent.getChildren().add(LineComponent.builder().left("Runtime")
			.right(String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)).build());
		panelComponent.getChildren().add(LineComponent.builder().left("Fruit deposited")
			.right(String.format(Locale.US, "%,d", plugin.getDeposited())).build());
		panelComponent.getChildren().add(LineComponent.builder().left("Water remaining")
			.right(String.format(Locale.US, "%,d", plugin.getWater())).build());
		panelComponent.getChildren().add(LineComponent.builder().left("XP gained")
			.right(String.format(Locale.US, "%,d", plugin.getXpGained())).build());
		panelComponent.getChildren().add(LineComponent.builder().left("XP / hour")
			.right(String.format(Locale.US, "%,d", seconds == 0 ? 0 : plugin.getXpGained() * 3600L / seconds)).build());
		panelComponent.getChildren().add(LineComponent.builder().left("Points gained")
			.right(String.format(Locale.US, "%,d", plugin.getPointsGained())).build());
		return super.render(graphics);
	}
}
