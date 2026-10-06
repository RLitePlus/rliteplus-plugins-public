package net.runelite.client.plugins.microbot.planker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

final class PlankerWalker
{
	private static final String PLUGIN_CLASS =
		"net.runelite.client.plugins.microbot.efficientwalker.EfficientWalkerPlugin";
	private final PluginManager pluginManager;
	private final Plugin plugin;
	private final Object walker;
	private final Method getWalker;
	private final Method walkTo;
	private final Method getStatus;
	private final Method getPlanningFailure;
	private final Method getDestination;
	private final Method cancel;

	private PlankerWalker(PluginManager pluginManager, Plugin plugin) throws ReflectiveOperationException
	{
		this.pluginManager = pluginManager;
		this.plugin = plugin;
		getWalker = plugin.getClass().getMethod("getWalker");
		walker = invoke(getWalker, plugin);
		if (walker == null)
		{
			throw new IllegalStateException("Efficient Walker is unavailable. Enable it, then restart Planker.");
		}
		Class<?> type = walker.getClass();
		walkTo = type.getMethod("walkTo", WorldPoint.class);
		getStatus = type.getMethod("getStatus");
		getPlanningFailure = type.getMethod("getPlanningFailure");
		getDestination = type.getMethod("getDestination");
		cancel = type.getMethod("cancel");
		if (walkTo.getReturnType() != boolean.class || getDestination.getReturnType() != WorldPoint.class
			|| cancel.getReturnType() != void.class || !getStatus.getReturnType().isEnum()
			|| !getPlanningFailure.getReturnType().isEnum())
		{
			throw new IllegalStateException("Efficient Walker is incompatible. Update it, then restart Planker.");
		}
	}

	static PlankerWalker bind(PluginManager pluginManager) throws ReflectiveOperationException
	{
		List<Plugin> matches = pluginManager.getPlugins().stream()
			.filter(candidate -> PLUGIN_CLASS.equals(candidate.getClass().getName()))
			.collect(Collectors.toList());
		if (matches.size() != 1 || !pluginManager.isActive(matches.get(0)))
		{
			throw new IllegalStateException("Enable exactly one compatible Efficient Walker, then restart Planker.");
		}
		PlankerWalker bridge = new PlankerWalker(pluginManager, matches.get(0));
		String status = bridge.status();
		if ("PLANNING".equals(status) || "PREVIEW".equals(status) || "WALKING".equals(status))
		{
			throw new IllegalStateException("Efficient Walker already has a route. Stop that route, then restart Planker.");
		}
		return bridge;
	}

	boolean isAvailable()
	{
		long matches = pluginManager.getPlugins().stream()
			.filter(candidate -> PLUGIN_CLASS.equals(candidate.getClass().getName()))
			.count();
		return matches == 1 && pluginManager.getPlugins().contains(plugin)
			&& pluginManager.isActive(plugin) && invoke(getWalker, plugin) == walker;
	}

	boolean walkTo(WorldPoint target)
	{
		return (boolean) invoke(walkTo, walker, target);
	}

	String status()
	{
		return ((Enum<?>) invoke(getStatus, walker)).name();
	}

	String planningFailure()
	{
		return ((Enum<?>) invoke(getPlanningFailure, walker)).name();
	}

	WorldPoint destination()
	{
		return (WorldPoint) invoke(getDestination, walker);
	}

	void cancel()
	{
		invoke(cancel, walker);
	}

	private static Object invoke(Method method, Object target, Object... arguments)
	{
		try
		{
			return method.invoke(target, arguments);
		}
		catch (ReflectiveOperationException exception)
		{
			Throwable cause = exception instanceof InvocationTargetException
				? ((InvocationTargetException) exception).getTargetException() : exception;
			throw new IllegalStateException("Efficient Walker " + method.getName()
				+ " failed. Check Efficient Walker, then restart Planker.", cause);
		}
	}
}
