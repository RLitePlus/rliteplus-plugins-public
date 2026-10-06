package net.runelite.client.plugins.microbot.efficientwalker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.worldmap.WorldMapOverlay;

final class EfficientWalkerMapOverlay extends Overlay
{
	private static final BasicStroke STROKE = new BasicStroke(2);

	private final Client client;
	private final EfficientWalker walker;
	private final WorldMapOverlay worldMapOverlay;

	@Inject
	private EfficientWalkerMapOverlay(Client client, EfficientWalker walker, WorldMapOverlay worldMapOverlay)
	{
		this.client = client;
		this.walker = walker;
		this.worldMapOverlay = worldMapOverlay;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		final List<List<WorldPoint>> paths = walker.getPathSegments();
		if (map == null || paths.isEmpty())
		{
			return null;
		}

		final Shape oldClip = graphics.getClip();
		graphics.clip(map.getBounds());
		graphics.setColor(Color.ORANGE);
		graphics.setStroke(STROKE);
		for (List<WorldPoint> path : paths)
		{
			if (path.size() < 2)
			{
				continue;
			}
			Point previous = worldMapOverlay.mapWorldPointToGraphicsPoint(path.get(0));
			for (int i = 1; i < path.size(); i++)
			{
				final Point current = worldMapOverlay.mapWorldPointToGraphicsPoint(path.get(i));
				if (previous != null && current != null)
				{
					graphics.drawLine(previous.getX(), previous.getY(), current.getX(), current.getY());
				}
				previous = current;
			}
		}
		graphics.setClip(oldClip);
		return null;
	}
}
