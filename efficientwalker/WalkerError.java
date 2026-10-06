package net.runelite.client.plugins.microbot.efficientwalker;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;

final class WalkerError
{
	private final String reason;
	private final String coordinates;

	WalkerError(String message, WorldPoint start, WorldPoint current, WorldPoint target)
	{
		reason = "Error: Efficient Walker v"
			+ EfficientWalkerPlugin.class.getAnnotation(PluginDescriptor.class).version() + ": " + message;
		List<String> tiles = new ArrayList<>();
		if (start == null || !start.equals(current)) { tiles.add(tile("start", start)); }
		tiles.add(tile("current", current));
		tiles.add(tile("target", target));
		coordinates = " [" + String.join(", ", tiles) + "]";
	}

	private static String tile(String label, WorldPoint point)
	{
		if (point == null) { return label + "=unknown"; }
		return label + "=(" + point.getX() + ", " + point.getY() + ", " + point.getPlane() + ")";
	}

	String text() { return reason + coordinates; }

	String chat() { return ColorUtil.wrapWithColorTag(reason, Color.RED) + coordinates; }
}
