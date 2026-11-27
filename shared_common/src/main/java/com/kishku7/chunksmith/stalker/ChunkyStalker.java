/*
 * ChunkyStalker -- logout-driven nightly pre-generation, built on Chunksmith.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.kishku7.chunksmith.stalker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.platform.World;
import com.kishku7.chunksmith.util.AutoPause;
import com.kishku7.chunksmith.util.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ChunkyStalker: remembers where each player logged out and, during a nightly window while nobody is
 * online, pre-generates the area around each spot in turn.
 *
 * <p>It is driven entirely by {@link #tick()}, called from the server thread every tick by the
 * platform. The tick looks at the actual state of things (is our job running, did it finish, was it
 * paused) instead of tracking transitions, so it recovers correctly from anything that starts or stops
 * a task behind its back: {@code /cs pause}, {@code /cs continue}, continue-on-restart, auto-pause or
 * a crash.
 *
 * <p>Ground rules:
 * <ul>
 *   <li>One job at a time. Chunksmith allows one task per world, and a small server should not run
 *       several at once anyway.</li>
 *   <li>Never overwrite the operator's own work. A world that has a task running, or a paused task
 *       that is not ours, is skipped until it is free.</li>
 *   <li>Pausing is a pause, not a cancel. Progress is saved and the job resumes the next time it is
 *       allowed to run.</li>
 * </ul>
 */
public final class ChunkyStalker {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkyStalker");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** About every five seconds at 20 TPS. Logins short-circuit this so a join pauses at once. */
    private static final int CHECK_INTERVAL_TICKS = 100;

    private final Chunksmith chunky;
    private final Path file;
    private final Clock clock;
    private StalkerData data;

    private int ticksUntilCheck;
    private boolean hooked;
    /** Set by /cs stalker run: work now, ignoring the window and online players, until the queue is empty. */
    private boolean forced;
    /** Set by /cs stalker stop: stay idle until the current window has ended. */
    private boolean heldThisWindow;
    /** Why the last check did not run anything. Shown by /cs stalker status. */
    private String idleReason = "not checked yet";
    /** Spot keys already reported as blocked, so the log is not flooded every five seconds. */
    private final java.util.Set<String> reportedBlocked = new java.util.HashSet<>();

    public ChunkyStalker(Chunksmith chunky) {
        this(chunky, chunky.getConfig().getDirectory().resolve("stalker.json"), Clock.systemDefaultZone());
    }

    ChunkyStalker(Chunksmith chunky, Path file, Clock clock) {
        this.chunky = chunky;
        this.file = file;
        this.clock = clock;
        this.data = load(file);
        this.runner = chunky.getScheduler()::runTask;
    }

    /** Hands a task to a thread. Replaced in tests so nothing actually generates. */
    private java.util.function.Consumer<GenerationTask> runner;

    void setRunnerForTest(java.util.function.Consumer<GenerationTask> runner) {
        this.runner = runner;
    }

    /** Runs the scheduling decision immediately, for tests. */
    synchronized void checkNow() {
        check();
    }

    // ---------------------------------------------------------------------------------------------
    // Platform hooks
    // ---------------------------------------------------------------------------------------------

    /** Called once by a platform that drives {@link #tick()} and reports logouts. */
    public synchronized void markHooked() {
        this.hooked = true;
    }

    public synchronized boolean isHooked() {
        return hooked;
    }

    public synchronized void onLogout(String uuid, String name, String world, double x, double z) {
        boolean queued = StalkerSpots.recordLogout(data, uuid, name, world, x, z, clock.millis());
        LOGGER.info("ChunkyStalker: {} logged out in {} at {}, {}{}", name, world, Math.round(x), Math.round(z),
                queued ? " -- queued for tonight" : " -- area already pre-generated");
        save();
    }

    /** A player joining should pause a nightly run straight away, not up to five seconds later. */
    public synchronized void onLogin() {
        ticksUntilCheck = 0;
    }

    /** Server thread, every tick. */
    public synchronized void tick() {
        if (--ticksUntilCheck > 0) {
            return;
        }
        ticksUntilCheck = CHECK_INTERVAL_TICKS;
        try {
            check();
        } catch (RuntimeException e) {
            // A bug here must never take the server tick down with it.
            LOGGER.error("ChunkyStalker: scheduling check failed", e);
        }
    }

    public synchronized void disable() {
        save();
    }

    // ---------------------------------------------------------------------------------------------
    // The scheduling decision
    // ---------------------------------------------------------------------------------------------

    private void check() {
        long now = clock.millis();
        List<String> expired = StalkerSpots.pruneExpired(data, now);
        if (!expired.isEmpty()) {
            LOGGER.info("ChunkyStalker: forgot logout spots older than {} days: {}", data.settings.expiryDays, expired);
            save();
        }

        boolean inWindow = isInWindow();
        if (!inWindow) {
            heldThisWindow = false;
        }
        String blocker = blocker(inWindow);
        boolean allowed = blocker == null;

        StalkerData.Job job = data.active;
        if (job != null) {
            handleActiveJob(job, allowed, blocker);
            return;
        }
        if (!allowed) {
            idleReason = blocker;
            return;
        }
        startNextJob();
    }

    /** Null when a run is allowed right now, else the reason it is not. */
    private String blocker(boolean inWindow) {
        if (forced) {
            return null;
        }
        if (!data.settings.enabled) {
            return "disabled";
        }
        if (heldThisWindow) {
            return "stopped by an operator until the window ends";
        }
        if (!inWindow) {
            return "outside the window (" + data.settings.windowStart + "-" + data.settings.windowEnd + ")";
        }
        if (data.settings.pauseWhenPlayersOnline && !chunky.getServer().getPlayers().isEmpty()) {
            return "players are online";
        }
        return null;
    }

    private void handleActiveJob(StalkerData.Job job, boolean allowed, String blocker) {
        GenerationTask running = chunky.getGenerationTasks().get(job.world);
        if (running != null && matches(running.getSelection(), job)) {
            if (!allowed && !running.isStopping()) {
                running.stop(false);
                LOGGER.info("ChunkyStalker: pausing {}'s area ({}); progress is saved", job.name, blocker);
            }
            idleReason = allowed ? "working" : blocker;
            return;
        }
        if (running != null) {
            // Something else holds that world. Wait for it rather than fight it.
            idleReason = "waiting for another task in " + job.world;
            return;
        }
        Optional<World> world = chunky.getServer().getWorld(job.world);
        if (world.isEmpty()) {
            LOGGER.warn("ChunkyStalker: world {} no longer exists; dropping {}'s job", job.world, job.name);
            StalkerSpots.markJobDone(data, job);
            data.active = null;
            save();
            return;
        }
        Optional<GenerationTask> savedAny = chunky.getTaskLoader().loadTask(world.get());
        Optional<GenerationTask> saved = savedAny.filter(task -> matches(task.getSelection(), job));
        if (saved.isEmpty() && savedAny.filter(task -> !task.isCancelled()).isPresent()) {
            // Our saved progress was replaced by an operator task that is now paused. Starting ours
            // again would overwrite theirs, so wait until they continue or cancel it.
            idleReason = "waiting: " + job.world + " has a paused task of its own";
            return;
        }
        if (saved.isPresent() && saved.get().isCancelled()) {
            // Ran to the end (or an operator cancelled it, which we also take as "done with this spot").
            LOGGER.info("ChunkyStalker: finished {}'s area in {}", job.name, job.world);
            StalkerSpots.markJobDone(data, job);
            data.active = null;
            save();
            ticksUntilCheck = 0;
            return;
        }
        if (!allowed) {
            // Paused, as it should be. Make sure Chunksmith's auto-resume does not bring it back in the
            // middle of the day: a run it auto-paused at 05:58 would otherwise resume at 06:02.
            if (AutoPause.isAutoPaused() && job.world.equals(AutoPause.pausedWorld())) {
                AutoPause.clearAutoPaused();
            }
            idleReason = blocker;
            return;
        }
        GenerationTask task = saved.orElseGet(() -> new GenerationTask(chunky, selectionFor(world.get(), job)));
        launch(task, job, saved.isPresent() ? "resuming" : "starting");
    }

    private void startNextJob() {
        Optional<Map.Entry<String, StalkerData.Spot>> next = StalkerSpots.next(data, this::canWorkOn);
        if (next.isEmpty()) {
            if (forced) {
                forced = false;
                LOGGER.info("ChunkyStalker: queue is empty; the forced run is over");
            }
            idleReason = StalkerSpots.pending(data).isEmpty() ? "nothing queued" : "every queued world is busy";
            return;
        }
        String uuid = next.get().getKey();
        StalkerData.Spot spot = next.get().getValue();
        double radius = Math.min(StalkerSpots.radiusFor(data, uuid), chunky.getLimit());
        StalkerData.Job job = new StalkerData.Job(uuid, spot.name, spot.world, spot.x, spot.z, radius);
        World world = chunky.getServer().getWorld(spot.world).orElseThrow();
        data.active = job;
        save();
        launch(new GenerationTask(chunky, selectionFor(world, job)), job, "starting");
    }

    private boolean canWorkOn(StalkerData.Spot spot) {
        String key = spot.name + "@" + spot.world;
        Optional<World> world = chunky.getServer().getWorld(spot.world);
        if (world.isEmpty()) {
            if (reportedBlocked.add(key)) {
                LOGGER.warn("ChunkyStalker: skipping {}'s spot -- world {} is not loaded", spot.name, spot.world);
            }
            return false;
        }
        if (chunky.getGenerationTasks().containsKey(spot.world)) {
            return false;
        }
        // Chunksmith keeps ONE saved task per world. Starting ours would overwrite an operator's paused
        // pregen, and they would lose it. Leave that world alone until they continue or cancel it.
        boolean foreignPaused = chunky.getTaskLoader().loadTask(world.get())
                .filter(task -> !task.isCancelled())
                .isPresent();
        if (foreignPaused) {
            if (reportedBlocked.add(key)) {
                LOGGER.warn("ChunkyStalker: skipping {}'s spot -- {} has a paused task of its own."
                        + " Run /cs continue or /cs cancel for that world to free it.", spot.name, spot.world);
            }
            return false;
        }
        reportedBlocked.remove(key);
        return true;
    }

    private void launch(GenerationTask task, StalkerData.Job job, String verb) {
        chunky.getGenerationTasks().put(job.world, task);
        runner.accept(task);
        idleReason = "working";
        LOGGER.info("ChunkyStalker: {} {}'s area -- {} at {}, {} radius {}", verb, job.name, job.world,
                Math.round(job.x), Math.round(job.z), Math.round(job.radius));
    }

    private Selection selectionFor(World world, StalkerData.Job job) {
        return Selection.builder(chunky, world)
                .center(job.x, job.z)
                .radius(job.radius)
                .shape(data.settings.shape)
                .pattern(Parameter.of(data.settings.pattern))
                .build();
    }

    private static boolean matches(Selection selection, StalkerData.Job job) {
        return selection.world().getName().equals(job.world)
                && selection.centerX() == job.x
                && selection.centerZ() == job.z
                && selection.radiusX() == job.radius;
    }

    public boolean isInWindow() {
        Optional<LocalTime> start = StalkerWindow.parse(data.settings.windowStart);
        Optional<LocalTime> end = StalkerWindow.parse(data.settings.windowEnd);
        if (start.isEmpty() || end.isEmpty()) {
            return false;
        }
        return StalkerWindow.contains(start.get(), end.get(), LocalTime.now(clock.withZone(zone())));
    }

    public ZoneId zone() {
        String tz = data.settings.timezone;
        if (tz == null || tz.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Operator actions (from /cs stalker)
    // ---------------------------------------------------------------------------------------------

    public synchronized StalkerData data() {
        return data;
    }

    public synchronized String idleReason() {
        return idleReason;
    }

    public synchronized boolean isForced() {
        return forced;
    }

    public synchronized void forceRun() {
        forced = true;
        heldThisWindow = false;
        ticksUntilCheck = 0;
    }

    /** Pauses the current job and holds until the window ends. Returns true if a job was running. */
    public synchronized boolean stopForNow() {
        forced = false;
        heldThisWindow = true;
        ticksUntilCheck = 0;
        StalkerData.Job job = data.active;
        if (job == null) {
            return false;
        }
        GenerationTask running = chunky.getGenerationTasks().get(job.world);
        if (running != null && matches(running.getSelection(), job)) {
            running.stop(false);
            return true;
        }
        return false;
    }

    /** The running task for our job, if any, for progress display. */
    public synchronized Optional<GenerationTask> activeTask() {
        StalkerData.Job job = data.active;
        if (job == null) {
            return Optional.empty();
        }
        GenerationTask running = chunky.getGenerationTasks().get(job.world);
        return running != null && matches(running.getSelection(), job) ? Optional.of(running) : Optional.empty();
    }

    public synchronized void changed() {
        ticksUntilCheck = 0;
        save();
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------

    private static StalkerData load(Path file) {
        if (!Files.exists(file)) {
            return new StalkerData();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StalkerData loaded = GSON.fromJson(reader, StalkerData.class);
            if (loaded == null) {
                return new StalkerData();
            }
            if (loaded.settings == null) {
                loaded.settings = new StalkerData.Settings();
            }
            if (loaded.spots == null) {
                loaded.spots = new java.util.LinkedHashMap<>();
            }
            if (loaded.radii == null) {
                loaded.radii = new java.util.LinkedHashMap<>();
            }
            return loaded;
        } catch (IOException | JsonParseException e) {
            Path backup = file.resolveSibling(file.getFileName() + ".broken");
            LOGGER.error("ChunkyStalker: could not read {}; starting fresh and keeping the old file as {}", file, backup, e);
            try {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Nothing more we can do; the next save overwrites it.
            }
            return new StalkerData();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("ChunkyStalker: could not save {}", file, e);
        }
    }
}
