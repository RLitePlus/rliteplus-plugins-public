package net.runelite.client.plugins.microbot.huntersrumours;

import net.runelite.client.plugins.PluginManager;
import net.runelite.api.coords.WorldPoint;

final class HuntersRumoursRuntime
{
	enum State
	{
		STOPPED, RECOVERING_TRAPS, GOAL_REACHED, WAITING_FOR_GAME, WAITING_FOR_ASSIGNMENT, WAITING_FOR_TARGET, WAITING_FOR_RESULT, ASSIGNED,
		TRAVELLING_FALCONRY, PREPARING_FALCONRY, RECOVERING_FALCON, READY_TO_HUNT, CATCH_VERIFIED,
		HANDING_IN, RUMOUR_COMPLETE, PREPARING_DEADFALL, DEADFALL_PREPARED, PREPARING_NETS, COLLECTING_TOOLS, NET_PREPARED, HUNTING_NETS, PREPARING_BOXES, BOX_PREPARED, HUNTING_BOXES, PREPARING_SNARES, SNARE_PREPARED, HUNTING_SNARES, PREPARING_BUTTERFLIES, HUNTING_BUTTERFLIES, PREPARING_TRACKING, HUNTING_TRACKING, PREPARING_HERBIBOAR, HUNTING_HERBIBOAR, PREPARING_GOATS, HUNTING_GOATS, PREPARING_PITS, PIT_PREPARED, PAUSED, ERROR
	}

	private final PluginManager manager;
	private WalkerAccess walker;
	private volatile State state = State.STOPPED;
	private volatile String error;
	private volatile RumourAssignment assignment;
	final RumourHistory history = new RumourHistory();
	private volatile boolean paused;
	private volatile boolean running;
	private boolean recoveringTraps;
	private int assignmentRequestTick = -1;
	private volatile State taskState = State.ASSIGNED;
	private volatile int completedTotal = -1;
	private int completedThisRun;
	private int sessionTarget = 10;
	private RumourAssignment.Hunter selectedHunter = RumourAssignment.Hunter.ACO;
	private int hunterTarget;
	private int runtimeMinutes;
	private long startedNanos;
	private volatile String goalReason;
	private volatile String travelTarget;

	void selectedHunter(RumourAssignment.Hunter hunter)
	{
		selectedHunter = java.util.Objects.requireNonNull(hunter);
	}

	RumourAssignment.Hunter selectedHunter()
	{
		return selectedHunter;
	}

	boolean maySwitchHunter()
	{
		return mayAct() && assignment != null && assignment.hunter != selectedHunter;
	}

	void goals(int session, int hunter, int minutes)
	{
		sessionTarget = session;
		hunterTarget = hunter;
		runtimeMinutes = minutes;
	}

	long elapsedSeconds()
	{
		return startedNanos == 0 ? 0 : Math.max(0, (System.nanoTime() - startedNanos) / 1000000000L);
	}

	void observeProgress(int hunterLevel, long elapsedSeconds)
	{
		if (!running || error != null || goalReason != null) return;
		if (sessionTarget > 0 && completedThisRun >= sessionTarget) goalReason = "Session completion target reached";
		else if (hunterTarget > 0 && hunterLevel >= hunterTarget && hunterLevel <= 99) goalReason = "Hunter level target reached";
		else if (runtimeMinutes > 0 && elapsedSeconds >= runtimeMinutes * 60L) goalReason = "Runtime limit reached";
	}

	void finishCurrentRumour()
	{
		if (!running || error != null) return;
		if (goalReason == null) goalReason = "Stop requested";
		stopAtGoalBoundary();
	}

	String goalReason()
	{
		return goalReason;
	}

	private boolean stopAtGoalBoundary()
	{
		if (goalReason == null || assignment != null || assignmentRequestTick >= 0 || recoveringTraps) return false;
		taskState = State.GOAL_REACHED;
		state = State.GOAL_REACHED;
		cancelRoute();
		return true;
	}

	HuntersRumoursRuntime(PluginManager manager)
	{
		this.manager = manager;
	}

	void recoveringTraps(boolean recovering)
	{
		recoveringTraps = recovering;
		if (!recovering) taskState = State.ASSIGNED;
	}

	void start()
	{
		if (running) return;
		running = true;
		startedNanos = System.nanoTime();
		if (sessionTarget < 0 || sessionTarget > 1000000 || hunterTarget < 0 || hunterTarget > 99
			|| runtimeMinutes < 0 || runtimeMinutes > 1000000)
		{
			fail("Set valid goal limits: completion counts and minutes 0–1000000, Hunter level 0–99. Use 0 to disable a limit, then restart.");
			return;
		}
		try
		{
			walker = WalkerAccess.bind(manager);
			state = State.WAITING_FOR_GAME;
		}
		catch (ReflectiveOperationException | RuntimeException exception)
		{
			fail("Enable exactly one compatible Efficient Walker instance, then restart Hunters' Rumours.");
		}
	}

	void tick(boolean gameplayReady)
	{
		tick(gameplayReady, false);
	}

	void tick(boolean gameplayReady, boolean sceneLoading)
	{
		if (!running || error != null) return;
		if (walker == null || !walker.available())
		{
			fail("Efficient Walker became unavailable. Enable exactly one compatible instance, then restart Hunters' Rumours.");
			return;
		}
		if (!gameplayReady && !sceneLoading)
		{
			cancelRoute();
			if (error != null) return;
		}
		if (gameplayReady && !paused && stopAtGoalBoundary()) return;
		state = !gameplayReady ? State.WAITING_FOR_GAME : paused ? State.PAUSED
			: taskState == State.RUMOUR_COMPLETE ? taskState
			: recoveringTraps ? State.RECOVERING_TRAPS : assignment == null ? State.WAITING_FOR_ASSIGNMENT : taskState;
	}

	void dialogue(String speaker, String text)
	{
		if (!running || error != null) return;
		RumourAssignment observed = RumourAssignment.fromDialogue(speaker, text);
		if (observed != null)
		{
			history.observe(observed);
			assignment = observed;
		}
	}

	void reminder(String message)
	{
		if (!running || error != null || assignment != null || assignmentRequestTick < 0) return;
		RumourAssignment observed = RumourAssignment.fromReminder(message);
		if (observed != null)
		{
			history.observe(observed);
			assignment = observed;
		}
	}

	void pause()
	{
		if (!running || error != null) return;
		paused = true;
		cancelRoute();
		if (error == null) state = State.PAUSED;
	}

	void resume()
	{
		paused = false;
	}

	void stop()
	{
		running = false;
		cancelRoute();
		if (error == null) state = State.STOPPED;
	}

	boolean mayRequestAssignment()
	{
		return running && !paused && error == null && assignment == null
			&& state == State.WAITING_FOR_ASSIGNMENT && walker != null && walker.available();
	}

	boolean mayAct()
	{
		return running && !paused && error == null && state != State.WAITING_FOR_GAME && state != State.RUMOUR_COMPLETE && state != State.GOAL_REACHED
			&& walker != null && walker.available();
	}

	void observeTotal(int total)
	{
		if (!mayAct() || total < 0) return;
		completedTotal = total;
		observeProgress(-1, elapsedSeconds());
		stopAtGoalBoundary();
	}

	boolean beginNextRumour()
	{
		if (!running || paused || error != null || state != State.RUMOUR_COMPLETE
			|| completedTotal < 0 || walker == null || !walker.available()) return false;
		observeProgress(-1, elapsedSeconds());
		if (stopAtGoalBoundary()) return false;
		assignmentRequestTick = -1;
		taskState = State.ASSIGNED;
		state = State.WAITING_FOR_ASSIGNMENT;
		return true;
	}

	int completedThisRun()
	{
		return completedThisRun;
	}

	void completeRumour(int total)
	{
		history.complete(assignment);
		completedThisRun++;
		completedTotal = total;
		assignmentRequestTick = -1;
		observeProgress(-1, elapsedSeconds());
		taskState = State.RUMOUR_COMPLETE;
		assignment = null;
	}

	int completedTotal()
	{
		return completedTotal;
	}

	void taskState(State next)
	{
		if (mayAct()) taskState = next;
	}

	boolean walkTo(WorldPoint target) throws ReflectiveOperationException
	{
		if (!mayAct()) return false;
		travelTarget = target.getX() + ", " + target.getY() + " (plane " + target.getPlane() + ")";
		return walker.walkTo(target);
	}

	boolean nearestBank() throws ReflectiveOperationException
	{
		if (!mayAct()) return false;
		travelTarget = "Nearest reachable bank";
		return walker.nearestBank();
	}

	String walkStatus() throws ReflectiveOperationException
	{
		return walker == null ? "UNAVAILABLE" : walker.status();
	}

	void finishRoute()
	{
		cancelRoute();
	}

	boolean assignmentRequestReady(int tick)
	{
		if (!mayRequestAssignment()) return false;
		return assignmentRequestTick < 0;
	}

	boolean beginAssignmentRequest(int tick)
	{
		if (!assignmentRequestReady(tick)) return false;
		assignmentRequestTick = tick;
		return true;
	}

	void block(String reason)
	{
		if (running && !paused) fail(reason);
	}

	void playerDied()
	{
		if (running)
		{
			fail("You died. Recover your items and restart Hunters' Rumours when ready.");
			stop();
		}
	}

	private void fail(String reason)
	{
		if (error != null) return;
		error = reason;
		state = State.ERROR;
		cancelRoute();
	}

	String travelTarget()
	{
		return travelTarget;
	}

	private void cancelRoute()
	{
		if (walker == null) return;
		try
		{
			walker.cancel();
			travelTarget = null;
		}
		catch (ReflectiveOperationException | RuntimeException exception)
		{
			if (error == null)
			{
				error = "Could not cancel the current route. Stop Efficient Walker before restarting Hunters' Rumours.";
				state = State.ERROR;
			}
		}
	}

	State state()
	{
		return state;
	}

	String error()
	{
		return error;
	}

	RumourAssignment assignment()
	{
		return assignment;
	}
}
