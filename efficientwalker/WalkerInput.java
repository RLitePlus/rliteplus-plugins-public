package net.runelite.client.plugins.microbot.efficientwalker;

import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.util.mouse.BotEventGuard;

final class WalkerInput
{
	private WalkerInput() { }

	// Handoffs must reach the canvas before this tick returns; the stock mouse queues client-thread clicks.
	static void click(Client client, Point point)
	{
		Canvas canvas = client.getCanvas();
		int x = point.getX();
		int y = point.getY();
		if (client.isStretchedEnabled())
		{
			Dimension real = client.getRealDimensions();
			Dimension stretched = client.getStretchedDimensions();
			if (real != null && stretched != null && real.width > 0 && real.height > 0)
			{
				x = (int) ((long) x * stretched.width / real.width);
				y = (int) ((long) y * stretched.height / real.height);
			}
		}
		boolean guardFocus = canvas.isFocusable() && !canvas.isFocusOwner();
		if (guardFocus) { canvas.setFocusable(false); }
		BotEventGuard.begin();
		try
		{
			long now = System.currentTimeMillis();
			if (client.getMouseCanvasPosition().getX() < 0)
			{
				canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, now, 0, x, y, 0, false));
			}
			canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, now, 0, x, y, 0, false));
			canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
			canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
			canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
		}
		finally
		{
			BotEventGuard.end();
			if (guardFocus) { canvas.setFocusable(true); }
		}
	}
}
