package net.runelite.client.plugins.microbot.efficientwalker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

final class EfficientWalkerOverlay extends Overlay
{
	private static final Color OBSTACLE_COLOR = new Color(205, 128, 255);
	private static final Color OBSTACLE_FILL = new Color(205, 128, 255, 46);
	private static final BasicStroke STROKE = new BasicStroke(2);
	private static final Font LABEL_FONT = FontManager.getRunescapeSmallFont().deriveFont(12f);
	private static final int MAX_LABEL_CHARACTERS = 10;

	private final Client client;
	private final EfficientWalker walker;

	@Inject
	private EfficientWalkerOverlay(Client client, EfficientWalker walker)
	{
		this.client = client;
		this.walker = walker;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final Player player = client.getLocalPlayer();
		final WorldView worldView = player == null ? null : player.getWorldView();
		if (worldView == null)
		{
			return null;
		}

		final List<WorldPoint> path = walker.getRemainingPath();
		final int[] remaining = remainingTiles(path);
		final Set<WorldPoint> actionTiles = walker.getActionTiles();
		for (int index = 0; index < path.size(); index++)
		{
			final WorldPoint point = path.get(index);
			if (point.getPlane() != worldView.getPlane())
			{
				continue;
			}
			final LocalPoint localPoint = LocalPoint.fromWorld(worldView, point);
			final Polygon polygon = localPoint == null ? null
				: Perspective.getCanvasTilePoly(client, localPoint);
			if (polygon != null)
			{
				final Color color = pathColor(remaining[0] - remaining[index]);
				OverlayUtil.renderPolygon(graphics, polygon, color,
					new Color(color.getRed(), color.getGreen(), color.getBlue(), 35), STROKE);
			}
		}
		renderNextObstacle(graphics, worldView);
		// Draw counts after every tile, including the transport marker.
		final Font originalFont = graphics.getFont();
		graphics.setFont(LABEL_FONT);
		for (int index = 0; index < path.size(); index++)
		{
			final WorldPoint point = path.get(index);
			if (!showCount(remaining[index]) || point.getPlane() != worldView.getPlane()
				|| actionTiles.contains(point))
			{
				continue;
			}
			final LocalPoint localPoint = LocalPoint.fromWorld(worldView, point);
			if (localPoint != null && Perspective.getCanvasTilePoly(client, localPoint) != null)
			{
				renderLabel(graphics, localPoint, Integer.toString(remaining[index]), 0, Color.WHITE);
			}
		}
		graphics.setFont(originalFont);
		return null;
	}

	static int[] remainingTiles(List<WorldPoint> path)
	{
		final int[] remaining = new int[path.size()];
		for (int index = path.size() - 2; index >= 0; index--)
		{
			// Transport jumps do not add walking tiles to the countdown.
			remaining[index] = remaining[index + 1]
				+ (path.get(index).distanceTo(path.get(index + 1)) == 1 ? 1 : 0);
		}
		return remaining;
	}

	static boolean showCount(int remaining)
	{
		return remaining <= 5 || remaining % 5 == 0;
	}

	static Color pathColor(int tilesAhead)
	{
		return Color.getHSBColor((120f - 90f * Math.min(25, Math.max(0, tilesAhead)) / 25f) / 360f,
			0.8f, 1f);
	}

	private void renderNextObstacle(Graphics2D graphics, WorldView worldView)
	{
		final EfficientWalker.ObstacleMarker obstacle = walker.getNextObstacle();
		if (obstacle == null || obstacle.approach.getPlane() != worldView.getPlane())
		{
			return;
		}

		final LocalPoint localPoint = LocalPoint.fromWorld(worldView, obstacle.approach);
		final Polygon polygon = localPoint == null ? null : Perspective.getCanvasTilePoly(client, localPoint);
		if (polygon == null)
		{
			return;
		}

		OverlayUtil.renderPolygon(graphics, polygon, OBSTACLE_COLOR, OBSTACLE_FILL, STROKE);
		final Font originalFont = graphics.getFont();
		graphics.setFont(LABEL_FONT);
		final FontMetrics metrics = graphics.getFontMetrics();
		final int firstBaselineOffset = -metrics.getHeight() + metrics.getAscent();
		renderLabel(graphics, localPoint, displayAction(obstacle.action), firstBaselineOffset, OBSTACLE_COLOR);
		renderLabel(graphics, localPoint, displayObject(obstacle.action, obstacle.target),
			firstBaselineOffset + metrics.getHeight(), OBSTACLE_COLOR);
		graphics.setFont(originalFont);
	}

	private void renderLabel(Graphics2D graphics, LocalPoint localPoint, String label, int yOffset, Color color)
	{
		final Point location = Perspective.getCanvasTextLocation(client, graphics, localPoint, label, 0);
		if (location != null)
		{
			final int x = location.getX();
			final int y = location.getY() + yOffset;
			graphics.setColor(Color.BLACK);
			graphics.drawString(label, x - 1, y);
			graphics.drawString(label, x + 1, y);
			graphics.drawString(label, x, y - 1);
			graphics.drawString(label, x, y + 1);
			graphics.setColor(color);
			graphics.drawString(label, x, y);
		}
	}

	static String displayAction(String action)
	{
		return action.length() <= MAX_LABEL_CHARACTERS ? action : "Interact";
	}

	static String displayObject(String target)
	{
		if (target.length() <= MAX_LABEL_CHARACTERS)
		{
			return target;
		}
		final String type = target.substring(target.lastIndexOf(' ') + 1);
		return type.length() <= MAX_LABEL_CHARACTERS
			? Character.toUpperCase(type.charAt(0)) + type.substring(1) : "Obstacle";
	}

	static String displayObject(String action, String target)
	{
		if ("Teleport".equals(action))
		{
			if (target.endsWith(" Teleport"))
			{
				target = target.substring(0, target.length() - " Teleport".length());
			}
			else if (target.startsWith("Teleport to "))
			{
				target = target.substring("Teleport to ".length());
			}
		}
		return displayObject(target);
	}
}
