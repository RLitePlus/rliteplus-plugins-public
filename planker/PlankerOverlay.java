package net.runelite.client.plugins.microbot.planker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

final class PlankerOverlay extends OverlayPanel
{
	private final PlankerPlugin plugin;

	@Inject
	PlankerOverlay(PlankerPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
		setDynamicFont(false);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPreferredSize(new Dimension(ComponentConstants.STANDARD_WIDTH * 7 / 4, 0));
		panelComponent.setPreferredSize(new Dimension(ComponentConstants.STANDARD_WIDTH * 7 / 4, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		panelComponent.getChildren().clear();
		PlankerScript script = plugin.getOverlayScript();
		String type = script == null ? "" : script.getPlankLabel();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Planker" + (type.isEmpty() ? "" : " · " + type))
			.color(Color.GREEN)
			.build());
		line(script == null ? "Stopped" : script.getActivity(), "");
		long elapsed = script == null ? 0 : script.getRuntimeMillis();
		long produced = script == null ? 0 : script.getProduced();
		line("Made", made(produced, elapsed));
		int threshold = script == null ? 0 : script.getSellThreshold();
		int banked = script == null ? -1 : script.getBanked();
		line("Banked", (banked < 0 ? "Unknown" : number(banked))
			+ (threshold > 0 ? " · sell all" : " · selling off"));
		line("Sold", number(script == null ? 0 : script.getSold()));
		line("Runtime", runtime(elapsed));
		String error = script == null ? null : script.getError();
		if (error != null && !error.isEmpty())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Error: " + error)
				.leftColor(Color.RED)
				.build());
		}
		else if (script != null && "EXCHANGE".equals(script.getState()))
		{
			String trade = script.getTradeDetail();
			int colon = trade.indexOf(": ");
			if (colon >= 0) trade = trade.substring(colon + 2);
			for (String detail : trade.split("; "))
				if (!detail.isEmpty()) line(detail, "");
		}
		return super.render(graphics);
	}

	static String made(long produced, long elapsedMillis)
	{
		long rate = elapsedMillis <= 0 ? 0 : Math.round(produced * 3_600_000.0 / elapsedMillis);
		return number(produced) + " · " + number(rate) + "/hr";
	}

	static String runtime(long elapsedMillis)
	{
		long seconds = Math.max(0, elapsedMillis) / 1000;
		return seconds < 3600 ? String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
			: String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
	}

	private static String number(long value) { return String.format(Locale.US, "%,d", value); }

	private void line(String left, String right)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).build());
	}
}
