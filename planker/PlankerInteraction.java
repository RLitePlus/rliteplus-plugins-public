package net.runelite.client.plugins.microbot.planker;

import java.awt.Rectangle;
import java.awt.Shape;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.planker.PlankerScript.Click;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;

final class PlankerInteraction
{
	private PlankerInteraction() { }

	static Click nearest(Client client, int[] ids, String action)
	{
		if (client.getLocalPlayer() == null) return null;
		WorldPoint position = client.getLocalPlayer().getWorldLocation();
		Stream<Rs2NpcModel> npcs = (ids == null ? Microbot.getRs2NpcCache().query().withName("Banker")
			: Microbot.getRs2NpcCache().query().withIds(ids)).fromWorldView().within(position, 6).toList().stream();
		Stream<Rs2TileObjectModel> objects = Microbot.getRs2TileObjectCache().query().fromWorldView()
			.within(position, 6).withNames("Bank booth", "Bank chest", "Grand Exchange booth").toList().stream();
		return nearest(Stream.concat(npcs.map(npc -> npc(client, npc, position, action)),
			objects.map(object -> object(client, object, position, action))));
	}

	static Click nearest(Stream<Click> candidates)
	{
		return candidates.filter(Objects::nonNull).min(Comparator.comparingInt((Click click) -> click.distance)
			.thenComparingInt(click -> click.entry.getType().getId())
			.thenComparingInt(click -> click.entry.getIdentifier())
			.thenComparingInt(click -> click.entry.getParam0())
			.thenComparingInt(click -> click.entry.getParam1())).orElse(null);
	}

	static Click npc(Client client, Rs2NpcModel model, WorldPoint position, String action)
	{
		NPCComposition definition = model.getNpc().getTransformedComposition();
		int option = option(definition == null ? null : definition.getActions(), action);
		if (option < 0 || !inRange(client, position, model.getWorldLocation())) return null;
		Shape shape = model.getNpc().getConvexHull();
		Rectangle bounds = bounds(client, shape == null ? null : shape.getBounds());
		return new Click(new NewMenuEntry().option(action).target(model.getName()).identifier(model.getIndex())
			.opcode(MenuAction.NPC_FIRST_OPTION.getId() + option).param0(0).param1(0).itemId(-1)
			.actor(model.getNpc()), bounds, position.distanceTo(model.getWorldLocation()));
	}

	static Click object(Client client, Rs2TileObjectModel model, WorldPoint position, String action)
	{
		ObjectComposition definition = model.getObjectComposition();
		int option = option(definition == null ? null : definition.getActions(), action);
		if (option < 0 || !inRange(client, position, model.getWorldLocation())) return null;
		Shape shape = model.getClickbox();
		Rectangle bounds = bounds(client, shape == null ? null : shape.getBounds());
		int opcode = option == 4 ? MenuAction.GAME_OBJECT_FIFTH_OPTION.getId()
			: MenuAction.GAME_OBJECT_FIRST_OPTION.getId() + option;
		WorldPoint location = model.getWorldLocation();
		return new Click(new NewMenuEntry().option(action).target(definition.getName()).identifier(model.getId())
			.opcode(opcode).param0(location.getX() - model.getWorldView().getBaseX())
			.param1(location.getY() - model.getWorldView().getBaseY()).itemId(-1),
			bounds, position.distanceTo(location));
	}

	private static boolean inRange(Client client, WorldPoint position, WorldPoint target)
	{
		return position != null && target != null && position.distanceTo(target) <= 6
			&& client.getTopLevelWorldView() != null
			&& position.toWorldArea().hasLineOfSightTo(client.getTopLevelWorldView(), target);
	}

	private static int option(String[] actions, String action)
	{
		if (actions != null) for (int i = 0; i < Math.min(5, actions.length); i++)
			if (action.equalsIgnoreCase(actions[i])) return i;
		return -1;
	}

	private static Rectangle bounds(Client client, Rectangle bounds)
	{
		if (bounds == null || bounds.width <= 1 || bounds.height <= 1) return null;
		Rectangle visible = bounds.intersection(new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight()));
		return visible.width > 1 && visible.height > 1 ? visible : null;
	}
}
