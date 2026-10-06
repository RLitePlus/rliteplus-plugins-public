package net.runelite.client.plugins.microbot.huntersrumours;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

final class WalkerAccess
{
	private static final String PLUGIN_CLASS =
		"net.runelite.client.plugins.microbot.efficientwalker.EfficientWalkerPlugin";
	private final PluginManager manager;
	private final Plugin plugin;
	private final Object walker;
	private final Method walkTo;
	private final Method nearestBank;
	private final Method cancel;
	private final Method status;
	private boolean requested;

	private WalkerAccess(PluginManager manager, Plugin plugin) throws ReflectiveOperationException
	{
		this.manager = manager;
		this.plugin = plugin;
		walker = plugin.getClass().getMethod("getWalker").invoke(plugin);
		if (walker == null)
		{
			throw new IllegalStateException("Efficient Walker has no active runtime.");
		}
		Class<?> type = walker.getClass();
		walkTo = type.getMethod("walkTo", WorldPoint.class);
		nearestBank = type.getMethod("walkToNearestBank");
		cancel = type.getMethod("cancel");
		status = type.getMethod("getStatus");
		if (walkTo.getReturnType() != boolean.class || nearestBank.getReturnType() != boolean.class
			|| cancel.getReturnType() != void.class)
		{
			throw new IllegalStateException("Efficient Walker has an incompatible movement API.");
		}
	}

	static WalkerAccess bind(PluginManager manager) throws ReflectiveOperationException
	{
		List<Plugin> matches = matches(manager);
		if (matches.size() != 1 || !manager.isActive(matches.get(0)))
		{
			throw new IllegalStateException("Enable exactly one compatible Efficient Walker instance.");
		}
		return new WalkerAccess(manager, matches.get(0));
	}

	private static List<Plugin> matches(PluginManager manager)
	{
		return manager.getPlugins().stream()
			.filter(candidate -> PLUGIN_CLASS.equals(candidate.getClass().getName()))
			.collect(Collectors.toList());
	}

	boolean available()
	{
		List<Plugin> matches = matches(manager);
		if (matches.size() != 1 || matches.get(0) != plugin || !manager.isActive(plugin))
		{
			return false;
		}
		try
		{
			return plugin.getClass().getMethod("getWalker").invoke(plugin) == walker;
		}
		catch (ReflectiveOperationException | RuntimeException exception)
		{
			return false;
		}
	}

	boolean walkTo(WorldPoint point) throws ReflectiveOperationException
	{
		if (!available()) return false;
		boolean accepted = (boolean) walkTo.invoke(walker, point);
		requested |= accepted;
		return accepted;
	}

	boolean nearestBank() throws ReflectiveOperationException
	{
		if (!available()) return false;
		boolean accepted = (boolean) nearestBank.invoke(walker);
		requested |= accepted;
		return accepted;
	}

	String status() throws ReflectiveOperationException
	{
		return available() ? String.valueOf(status.invoke(walker)) : "UNAVAILABLE";
	}

	void cancel() throws ReflectiveOperationException
	{
		if (requested)
		{
			requested = false;
			cancel.invoke(walker);
		}
	}
}
