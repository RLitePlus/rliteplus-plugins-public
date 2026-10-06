package net.runelite.client.plugins.microbot.guardian;

import com.google.inject.Provides;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Skill;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.MenuAction;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

@PluginDescriptor(
	name = "Guardian",
	description = "Manages food, prayer restoration, and emergency bank escapes.",
	tags = {"safety", "food", "prayer", "bank"},
	enabledByDefault = true,
	version = "1.0.0"
)
public final class GuardianPlugin extends Plugin
{
	private static final String WALKER_CLASS = "net.runelite.client.plugins.microbot.efficientwalker.EfficientWalkerPlugin";

	@Inject private Client client;
	@Inject private PluginManager pluginManager;
	@Inject private GuardianConfig config;

	private Object walker;
	private Plugin walkerPlugin;
	private Method cancel;
	private Method walkToNearestBank;
	private Method getControlToken;
	private Method getControlStatus;
	private int lastActionTick = -100;
	private NPC reengageTarget;
	private boolean reengageAfterConsumable;
	private NPC preparedTarget;
	private NPC safeDefeatTarget;
	private NPC koscheiCombatTarget;
	private int koscheiCombatTick = -100;
	private int safeDefeatTick = -100;
	private boolean escaping;
	private boolean escapeBlocked;
	private boolean escapeCompleted;
	private Object escapeToken;
	private String escapeFailure;
	private String lastError;
	private String status = "Stopped";
	private Map<Integer, String> singleStylePrayers = Map.of();
	private Rs2PrayerEnum guardianPrayer;
	private Rs2PrayerEnum requestedPrayer;
	private boolean requestedPrayerOn;

	@Provides
	GuardianConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(GuardianConfig.class);
	}

	@Override
	protected void startUp()
	{
		loadSingleStylePrayers();
		bindWalker();
		if (singleStylePrayers.isEmpty())
		{
			status = "Error: single-style monster data unavailable";
		}
		else
		{
			status = "Watching supplies and single-style attacks";
		}
	}

	@Override
	protected void shutDown()
	{
		if (guardianPrayer != null && Rs2Prayer.isPrayerActive(guardianPrayer))
		{
			invokePrayer(guardianPrayer, false);
		}
		clearWalkerBinding();
		guardianPrayer = null;
		preparedTarget = null;
		koscheiCombatTarget = null;
		requestedPrayer = null;
		requestedPrayerOn = false;
		lastError = null;
		status = "Stopped";
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || event.getMenuOption() == null)
		{
			return;
		}
		if (manualDisengage(event))
		{
			reengageTarget = null;
			reengageAfterConsumable = false;
			preparedTarget = null;
			return;
		}
		if (!"attack".equalsIgnoreCase(event.getMenuOption()) || !npcOption(event.getMenuAction()))
		{
			return;
		}
		NPC target = client.getNpcs().stream()
			.filter(candidate -> candidate.getIndex() == event.getId())
			.findFirst().orElse(null);
		if (aliveTarget(target))
		{
			reengageTarget = null;
			reengageAfterConsumable = false;
			preparedTarget = target;
			armPrayer(target, client.getTickCount());
		}
	}

	private static boolean manualDisengage(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		return action == MenuAction.WALK
			|| action == MenuAction.GAME_OBJECT_FIRST_OPTION || action == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| action == MenuAction.GAME_OBJECT_THIRD_OPTION || action == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION
			|| (npcOption(action) && !"attack".equalsIgnoreCase(event.getMenuOption()));
	}

	private static boolean npcOption(MenuAction action)
	{
		return action == MenuAction.NPC_FIRST_OPTION || action == MenuAction.NPC_SECOND_OPTION
			|| action == MenuAction.NPC_THIRD_OPTION || action == MenuAction.NPC_FOURTH_OPTION
			|| action == MenuAction.NPC_FIFTH_OPTION;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN) { return; }
		preparedTarget = null;
		koscheiCombatTarget = null;
		reengageTarget = null;
		reengageAfterConsumable = false;
		requestedPrayer = null;
		lastActionTick = -100;
		if (event.getGameState() != GameState.LOADING) { clearWalkerBinding(); }
	}

	@Subscribe(priority = 200)
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		if (singleStylePrayers.isEmpty())
		{
			error("Single-style monster data unavailable.");
			return;
		}
		if (!walkerReady())
		{
			error("Enable exactly one compatible Efficient Walker.");
			return;
		}
		clearError("Enable exactly one compatible Efficient Walker.");
		if (safeDefeatActive())
		{
			status = "Allowing Koschei's verified fourth-phase safe defeat";
			return;
		}
		boolean escapePending = escapeInProgress();
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int maxHp = Math.max(1, client.getRealSkillLevel(Skill.HITPOINTS));
		int prayer = client.getBoostedSkillLevel(Skill.PRAYER);
		int maxPrayer = Math.max(1, client.getRealSkillLevel(Skill.PRAYER));
		List<Rs2ItemModel> food = Rs2Inventory.getInventoryFood();
		Rs2ItemModel restore = prayerRestore();
		boolean lowHealth = lowHealth(hp, maxHp);
		if (!lowHealth || !food.isEmpty())
		{
			if (escapeBlocked)
			{
				clearError(escapeFailure);
				escapeBlocked = false;
				escapeFailure = null;
				clearError("Emergency bank route is blocked; stop combat and resolve the Walker route.");
			}
			if (escapeCompleted)
			{
				escapeCompleted = false;
				clearError("Emergency bank route arrived without food; stop combat and restock.");
			}
		}
		if (lowHealth && food.isEmpty() && (escapeBlocked || escapeCompleted))
		{
			error(escapeCompleted
				? "Emergency bank route arrived without food; stop combat and restock."
				: escapeFailure == null ? "Emergency bank route is blocked; stop combat and resolve the Walker route." : escapeFailure);
		}
		manageSingleStylePrayer();
		if (client.getTickCount() - lastActionTick < 1)
		{
			return;
		}

		boolean protectionActive = protectionPrayerActive();
		if (lowHealth && food.isEmpty()
			&& (!protectionActive || (prayer == 0 && restore == null)))
		{
			if (!escapeBlocked && !escapeCompleted) { escape(); }
			return;
		}

		if (lowHealth && !food.isEmpty())
		{
			if (interactItem(food.get(0), "Eat"))
			{
				clearError("Food is present but its Eat action is unavailable; resolve the inventory action before resuming.");
				if (!escapePending) { queueReengage(); }
				lastActionTick = client.getTickCount();
				status = "Eating for safety";
			}
			else
			{
				error("Food is present but its Eat action is unavailable; resolve the inventory action before resuming.");
			}
			return;
		}
		if (lowPrayer(prayer, maxPrayer) && restore != null)
		{
			if (interactItem(restore, consumableAction(restore)))
			{
				clearError("Prayer restore is present but its action is unavailable; resolve the inventory action before resuming.");
				if (!escapePending) { queueReengage(); }
				lastActionTick = client.getTickCount();
				status = "Restoring prayer";
			}
			else
			{
				error("Prayer restore is present but its action is unavailable; resolve the inventory action before resuming.");
			}
		}
		if (!escapePending && safetyReady())
		{
			tryReengage(client.getTickCount());
		}
	}

	/**
	 * Lets a tick-sensitive encounter issue an ordinary combat input only after Guardian
	 * has confirmed its dependency and safety state. The protection prayer is armed here
	 * before the encounter sends its first attack.
	 */
	public boolean canEncounterAct()
	{
		return !singleStylePrayers.isEmpty()
			&& client.getGameState() == GameState.LOGGED_IN
			&& client.getLocalPlayer() != null && safetyReady();
	}

	/** Releases a target prepared by a stopped encounter controller. */
	public void releasePreparedCombat()
	{
		preparedTarget = null;
		safeDefeatTarget = null;
		koscheiCombatTarget = null;
	}

	public boolean prepareForCombat(NPC target)
	{
		if (singleStylePrayers.isEmpty()
			|| client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null
			|| !walkerReady() || !safetyReady() || !aliveTarget(target))
		{
			return false;
		}
		if (singleStylePrayers.containsKey(target.getId())
			&& client.getBoostedSkillLevel(Skill.PRAYER) <= 0 && !koscheiCombatActive(target))
		{
			return false;
		}
		if (armPrayer(target, client.getTickCount()))
		{
			preparedTarget = target;
			return false;
		}
		preparedTarget = target;
		return safetyReady();
	}

	/** Renews the food-backed plan for the first three trial forms only. */
	public boolean prepareKoscheiCombat(NPC target)
	{
		if (!koscheiCombatRound(target)) { koscheiCombatTarget=null; return false; }
		koscheiCombatTarget=target;koscheiCombatTick=client.getTickCount();
		return prepareForCombat(target);
	}

	private boolean koscheiCombatActive(NPC target)
	{
		return target==koscheiCombatTarget && client.getTickCount()-koscheiCombatTick<=1 && koscheiCombatRound(target);
	}

	private boolean koscheiCombatRound(NPC target)
	{
		if(client.getGameState()!=GameState.LOGGED_IN || client.getLocalPlayer()==null || target==null
			|| target.getId()<3897 || target.getId()>3899 || target.getWorldView()!=client.getTopLevelWorldView()
			|| !aliveTarget(target)){return false;}
		net.runelite.api.coords.WorldPoint p=client.getLocalPlayer().getWorldLocation();
		return p.getPlane()==2 && p.getX()>=2641 && p.getX()<=2672 && p.getY()>=10064 && p.getY()<=10099
			&& client.getVarpValue(347)>0 && client.getVarpValue(347)<8;
	}

	/** Short-lived opt-in for the quest's nonlethal fourth round; never permits ordinary death. */
	public boolean prepareKoscheiSafeDefeat(NPC target)
	{
		if (!koscheiFourthRound(target)) { return false; }
		safeDefeatTarget = target;
		safeDefeatTick = client.getTickCount();
		preparedTarget = null;
		koscheiCombatTarget = null;
		reengageTarget = null;
		reengageAfterConsumable = false;
		return true;
	}

	private boolean safeDefeatActive()
	{
		return client.getTickCount() - safeDefeatTick <= 1 && koscheiFourthRound(safeDefeatTarget);
	}

	private boolean koscheiFourthRound(NPC target)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null
			|| target == null || target.getId() != net.runelite.api.gameval.NpcID.VIKING_ENEMY4
			|| target.isDead() || target.getWorldView() != client.getTopLevelWorldView()
			|| target.getInteracting() != client.getLocalPlayer()) { return false; }
		net.runelite.api.coords.WorldPoint p = client.getLocalPlayer().getWorldLocation();
		return p.getPlane() == 2 && p.getX() >= 2641 && p.getX() <= 2672
			&& p.getY() >= 10064 && p.getY() <= 10099 && client.getVarpValue(347) > 0
			&& client.getVarpValue(347) < 8;
	}

	private void loadSingleStylePrayers()
	{
		try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream("/net/runelite/client/plugins/microbot/guardian/single_style_prayers.json")))
		{
			Type type = new TypeToken<Map<Integer, String>>() { }.getType();
			singleStylePrayers = new Gson().fromJson(reader, type);
			status = "Watching supplies and single-style attacks";
			clearError("Single-style monster data unavailable.");
		}
		catch (Exception exception)
		{
			// Startup runs on the Swing thread; report through the next game tick.
			status = "Error: single-style monster data unavailable";
		}
	}

	private void manageSingleStylePrayer()
	{
		int tick = client.getTickCount();
		if (requestedPrayer != null && Rs2Prayer.isPrayerActive(requestedPrayer) == requestedPrayerOn)
		{
			requestedPrayer = null;
			requestedPrayerOn = false;
		}
		NPC attacker = client.getNpcs().stream()
			.filter(this::aliveTarget)
			.filter(candidate -> candidate.getInteracting() == client.getLocalPlayer())
			.min(Comparator.comparingInt(candidate -> candidate.getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation())))
			.orElse(null);
		NPC playerTarget = client.getLocalPlayer().getInteracting() instanceof NPC
			? (NPC) client.getLocalPlayer().getInteracting() : null;
		if (playerTarget == null && client.getLocalPlayer().getAnimation() != -1)
		{
			playerTarget = client.getNpcs().stream()
				.filter(this::aliveTarget)
				.filter(candidate -> singleStylePrayers.containsKey(candidate.getId())
					&& candidate.getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation()) <= 2)
				.min(Comparator.comparingInt(candidate -> candidate.getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation())))
				.orElse(null);
		}
		boolean targetAlive = aliveTarget(playerTarget);
		if (targetAlive && playerTarget != preparedTarget)
		{
			preparedTarget = null;
		}
		NPC prepared = aliveTarget(preparedTarget) ? preparedTarget : null;
		if (prepared == null)
		{
			preparedTarget = null;
		}
		NPC npc = attacker != null ? attacker : targetAlive ? playerTarget : prepared;
		if (npc == null || npc.isDead())
		{
			if (!escaping)
			{
				clearGuardianPrayer(tick);
			}
			return;
		}

		if (armPrayer(npc, tick)) { return; }
	}

	private void tryReengage(int tick)
	{
		if (reengageAfterConsumable)
		{
			if (reengageTarget == null || !aliveTarget(reengageTarget))
			{
				reengageTarget = null;
				reengageAfterConsumable = false;
			}
			else if (client.getLocalPlayer().getInteracting() != reengageTarget && tick - lastActionTick >= 1)
			{
				reengageAfterConsumable = false;
				if (attackNpc(reengageTarget))
				{
					lastActionTick = tick;
					status = "Re-engaging attacker once";
				}
				reengageTarget = null;
			}
		}
	}

	private void queueReengage()
	{
		NPC target = lastCombatTarget();
		if (aliveTarget(target))
		{
			reengageTarget = target;
			reengageAfterConsumable = true;
		}
	}

	private NPC lastCombatTarget()
	{
		NPC target = client.getLocalPlayer().getInteracting() instanceof NPC
			? (NPC) client.getLocalPlayer().getInteracting() : null;
		return target != null ? target : client.getNpcs().stream()
			.filter(this::aliveTarget)
			.filter(candidate -> candidate.getInteracting() == client.getLocalPlayer())
			.min(Comparator.comparingInt(candidate -> candidate.getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation())))
			.orElse(null);
	}

	private boolean armPrayer(NPC npc, int tick)
	{
		String style = koscheiCombatActive(npc) && npc.getId()!=3899 ? null : singleStylePrayers.get(npc.getId());
		Rs2PrayerEnum prayer = "magic".equals(style) ? Rs2PrayerEnum.PROTECT_MAGIC
			: "ranged".equals(style) ? Rs2PrayerEnum.PROTECT_RANGE
			: "melee".equals(style) ? Rs2PrayerEnum.PROTECT_MELEE : null;
		if (guardianPrayer != null && guardianPrayer != prayer)
		{
			if (Rs2Prayer.isPrayerActive(guardianPrayer))
			{
				requestPrayer(guardianPrayer, false, tick);
				return true;
			}
			guardianPrayer = null;
		}
		if (prayer == null || client.getBoostedSkillLevel(Skill.PRAYER) <= 0
			|| Rs2Prayer.isPrayerActive(prayer) || tick - lastActionTick < 1)
		{
			return false;
		}
		requestPrayer(prayer, true, tick);
		guardianPrayer = prayer;
		status = "Protecting against " + style + " attacks";
		return true;
	}

	private boolean aliveTarget(NPC npc)
	{
		return npc != null && client.getNpcs().contains(npc) && !npc.isDead()
			&& npc.getWorldLocation() != null && npc.getHealthRatio() != 0;
	}

	private void clearGuardianPrayer(int tick)
	{
		if (guardianPrayer == null) { return; }
		if (Rs2Prayer.isPrayerActive(guardianPrayer))
		{
			requestPrayer(guardianPrayer, false, tick);
			return;
		}
		guardianPrayer = null;
	}

	private void requestPrayer(Rs2PrayerEnum prayer, boolean on, int tick)
	{
		if (Rs2Prayer.isPrayerActive(prayer) == on)
		{
			if (requestedPrayer == prayer)
			{
				requestedPrayer = null;
				requestedPrayerOn = false;
			}
			return;
		}
		if (requestedPrayer == prayer && requestedPrayerOn == on) { return; }
		invokePrayer(prayer, on);
		requestedPrayer = prayer;
		requestedPrayerOn = on;
		lastActionTick = tick;
	}

	private void invokePrayer(Rs2PrayerEnum prayer, boolean on)
	{
		String option = on ? "Activate" : "Deactivate";
		client.menuAction(-1, prayer.getIndex(), MenuAction.CC_OP, 1, -1, option, option);
	}

	private boolean interactItem(Rs2ItemModel item, String action)
	{
		if (item == null || item.getSlot() < 0)
		{
			return false;
		}
		Widget inventory = client.getWidget(ComponentID.INVENTORY_CONTAINER);
		Widget[] children = inventory == null ? null : inventory.getChildren();
		Widget itemWidget = children == null ? null : java.util.Arrays.stream(children)
			.filter(child -> child != null && child.getIndex() == item.getSlot())
			.findFirst().orElse(null);
		String[] actions = itemWidget == null ? null : itemWidget.getActions();
		if (actions == null)
		{
			return false;
		}
		for (int i = 0; i < actions.length; i++)
		{
			if (action.equalsIgnoreCase(actions[i]))
			{
				client.menuAction(item.getSlot(), ComponentID.INVENTORY_CONTAINER, MenuAction.CC_OP,
					i + 1, item.getId(), action, item.getName());
				return true;
			}
		}
		return false;
	}

	private boolean lowHealth(int hp, int maxHp)
	{
		return hp * 100 <= maxHp * config.eatHpPercent();
	}

	private boolean lowPrayer(int prayer, int maxPrayer)
	{
		return prayer * 100 <= maxPrayer * config.restorePrayerPercent();
	}

	private String consumableAction(Rs2ItemModel item)
	{
		Widget inventory = client.getWidget(ComponentID.INVENTORY_CONTAINER);
		Widget[] children = inventory == null ? null : inventory.getChildren();
		Widget itemWidget = children == null ? null : java.util.Arrays.stream(children)
			.filter(child -> child != null && child.getIndex() == item.getSlot())
			.findFirst().orElse(null);
		String[] actions = itemWidget == null ? null : itemWidget.getActions();
		if (actions != null)
		{
			for (String action : actions)
			{
				if ("Drink".equalsIgnoreCase(action)) { return "Drink"; }
				if ("Restore".equalsIgnoreCase(action)) { return "Restore"; }
			}
		}
		return "Drink";
	}

	private boolean attackNpc(NPC npc)
	{
		NPCComposition composition = client.getNpcDefinition(npc.getId());
		if (composition == null || composition.getActions() == null)
		{
			return false;
		}
		String[] actions = composition.getActions();
		for (int i = 0; i < actions.length && i < 5; i++)
		{
			if ("Attack".equalsIgnoreCase(actions[i]))
			{
				MenuAction menuAction;
				switch (i)
				{
					case 0: menuAction = MenuAction.NPC_FIRST_OPTION; break;
					case 1: menuAction = MenuAction.NPC_SECOND_OPTION; break;
					case 2: menuAction = MenuAction.NPC_THIRD_OPTION; break;
					case 3: menuAction = MenuAction.NPC_FOURTH_OPTION; break;
					default: menuAction = MenuAction.NPC_FIFTH_OPTION; break;
				}
				client.menuAction(0, 0, menuAction, npc.getIndex(), -1, "Attack", npc.getName());
				return true;
			}
		}
		return false;
	}

	private void escape()
	{
		if (escaping)
		{
			return;
		}
		try
		{
			reengageTarget = null;
			reengageAfterConsumable = false;
			preparedTarget = null;
			cancel.invoke(walker);
			if (!(Boolean) walkToNearestBank.invoke(walker))
			{
				blockEscape("Efficient Walker rejected the emergency bank route.");
				return;
			}
			escapeToken = getControlToken.invoke(walker);
			if (escapeToken == null)
			{
				blockEscape("Efficient Walker did not expose an emergency route control token.");
				return;
			}
			escapeBlocked = false;
			escapeCompleted = false;
			escapeFailure = null;
			escaping = true;
			lastActionTick = client.getTickCount();
			status = "Emergency bank route pending";
		}
		catch (ReflectiveOperationException exception)
		{
			blockEscape("Could not start the emergency bank route.");
		}
	}

	private boolean escapeInProgress()
	{
		if (!escaping)
		{
			return false;
		}
		try
		{
			Map<?, ?> control = (Map<?, ?>) getControlStatus.invoke(walkerPlugin, escapeToken);
			boolean owned = Boolean.TRUE.equals(control.get("owned"));
			if (owned && Boolean.TRUE.equals(control.get("active")))
			{
				status = "Emergency bank route pending";
				return true;
			}
			String state = String.valueOf(control.get("state"));
			escaping = false;
			escapeToken = null;
			if (owned && "ARRIVED".equals(state))
			{
				escapeCompleted = true;
				clearError("Emergency bank route stopped before arrival.");
				clearError("Could not verify the emergency bank route.");
				status = "Emergency bank route arrived";
				return false;
			}
			blockEscape(owned ? "Emergency bank route stopped before arrival." : "Emergency bank route was replaced by another Walker request.");
			return true;
		}
		catch (ReflectiveOperationException | RuntimeException exception)
		{
			error("Could not verify the emergency bank route.");
			return true;
		}
	}

	private void error(String message)
	{
		status = "Error: " + message;
		if (!message.equals(lastError))
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>Error: " + message + "</col>", "");
			lastError = message;
		}
	}

	private void blockEscape(String message)
	{
		escapeBlocked = true;
		escapeFailure = message;
		error(message);
	}

	private void clearError(String message)
	{
		if (message != null && message.equals(lastError))
		{
			lastError = null;
		}
	}

	private Rs2ItemModel prayerRestore()
	{
		return Rs2Inventory.getPotions().stream()
			.filter(item -> {
				String name = item.getName().toLowerCase();
				return name.contains("prayer") || name.contains("restore") || name.contains("renewal");
			})
			.findFirst().orElse(null);
	}

	private boolean protectionPrayerActive()
	{
		return Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MELEE)
			|| Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_RANGE)
			|| Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC);
	}

	private boolean safetyReady()
	{
		if (singleStylePrayers.isEmpty() || !walkerReady() || escaping || requestedPrayer != null)
		{
			return false;
		}
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int maxHp = Math.max(1, client.getRealSkillLevel(Skill.HITPOINTS));
		int prayer = client.getBoostedSkillLevel(Skill.PRAYER);
		int maxPrayer = Math.max(1, client.getRealSkillLevel(Skill.PRAYER));
		return !lowHealth(hp, maxHp)
			&& !(lowPrayer(prayer, maxPrayer) && prayerRestore() != null)
			&& client.getTickCount() - lastActionTick >= 1;
	}

	private boolean walkerReady()
	{
		List<Plugin> matches = pluginManager.getPlugins().stream()
			.filter(plugin -> plugin.getClass().getName().equals(WALKER_CLASS))
			.collect(Collectors.toList());
		if (matches.size() != 1 || !pluginManager.isActive(matches.get(0)))
		{
			clearWalkerBinding();
			return false;
		}

		Plugin currentPlugin = matches.get(0);
		try
		{
			Object currentWalker = currentPlugin.getClass().getMethod("getWalker").invoke(currentPlugin);
			if (currentWalker == null)
			{
				clearWalkerBinding();
				return false;
			}
			if (currentPlugin == walkerPlugin && currentWalker == walker
				&& compatibleWalker(cancel, walkToNearestBank, currentWalker)
				&& compatibleControl(getControlToken, getControlStatus, currentPlugin))
			{
				return true;
			}
			clearWalkerBinding();
			return bindWalker();
		}
		catch (ReflectiveOperationException | RuntimeException exception)
		{
			clearWalkerBinding();
			return false;
		}
	}

	private boolean bindWalker()
	{
		List<Plugin> matches = pluginManager.getPlugins().stream()
			.filter(plugin -> plugin.getClass().getName().equals(WALKER_CLASS))
			.collect(Collectors.toList());
		if (matches.size() != 1 || !pluginManager.isActive(matches.get(0)))
		{
			clearWalkerBinding();
			return false;
		}
		try
		{
			Object currentWalker = matches.get(0).getClass().getMethod("getWalker").invoke(matches.get(0));
			if (currentWalker == null)
			{
				clearWalkerBinding();
				return false;
			}
			Method currentCancel = currentWalker.getClass().getMethod("cancel");
			Method currentWalkToNearestBank = currentWalker.getClass().getMethod("walkToNearestBank");
			Method currentGetControlToken = currentWalker.getClass().getMethod("getControlToken");
			Method currentGetControlStatus = matches.get(0).getClass().getMethod("getControlStatus", Object.class);
			if (!compatibleWalker(currentCancel, currentWalkToNearestBank, currentWalker))
			{
				clearWalkerBinding();
				return false;
			}
			if (currentGetControlToken.getParameterCount() != 0
				|| currentGetControlToken.getReturnType() != Object.class
				|| currentGetControlStatus.getParameterCount() != 1
				|| currentGetControlStatus.getReturnType() != Map.class)
			{
				clearWalkerBinding();
				return false;
			}
			walkerPlugin = matches.get(0);
			walker = currentWalker;
			cancel = currentCancel;
			walkToNearestBank = currentWalkToNearestBank;
			getControlToken = currentGetControlToken;
			getControlStatus = currentGetControlStatus;
			return true;
		}
		catch (ReflectiveOperationException | RuntimeException exception)
		{
			clearWalkerBinding();
			return false;
		}
	}

	private static boolean compatibleWalker(Method cancel, Method walkToNearestBank, Object currentWalker)
	{
		return cancel != null && walkToNearestBank != null && currentWalker != null
			&& cancel.getDeclaringClass().isInstance(currentWalker)
			&& walkToNearestBank.getDeclaringClass().isInstance(currentWalker)
			&& cancel.getParameterCount() == 0 && cancel.getReturnType() == void.class
			&& walkToNearestBank.getParameterCount() == 0
			&& walkToNearestBank.getReturnType() == boolean.class;
	}

	private static boolean compatibleControl(Method getControlToken, Method getControlStatus, Plugin walkerPlugin)
	{
		return getControlToken != null && getControlStatus != null && walkerPlugin != null
			&& getControlToken.getParameterCount() == 0 && getControlToken.getReturnType() == Object.class
			&& getControlStatus.getDeclaringClass().isInstance(walkerPlugin)
			&& getControlStatus.getParameterCount() == 1 && getControlStatus.getReturnType() == Map.class;
	}

	private void clearWalkerBinding()
	{
		walkerPlugin = null;
		walker = null;
		cancel = null;
		walkToNearestBank = null;
		getControlToken = null;
		getControlStatus = null;
		escaping = false;
		escapeBlocked = false;
		escapeCompleted = false;
		escapeFailure = null;
		escapeToken = null;
	}

	public String getStatus()
	{
		return status;
	}
}
