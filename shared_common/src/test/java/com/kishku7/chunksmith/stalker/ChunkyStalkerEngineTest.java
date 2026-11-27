package com.kishku7.chunksmith.stalker;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.Selection;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.Player;
import com.kishku7.chunksmith.platform.Server;
import com.kishku7.chunksmith.platform.World;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Walks ChunkyStalker through a night against a real Chunksmith instance built on proxy fakes.
 * Tasks are captured instead of run, and "finishing" or "pausing" a task is simulated the way
 * GenerationTask itself ends: save to the TaskLoader, then leave the task map.
 */
public class ChunkyStalkerEngineTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private Path dir;
    private final List<Player> online = new ArrayList<>();
    private final List<GenerationTask> launched = new ArrayList<>();
    private MutableClock clock;
    private Chunksmith chunky;
    private World world;
    private ChunkyStalker stalker;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("stalker-test");
        world = fake(World.class, (name, args) -> switch (name) {
            case "getName", "getKey" -> OVERWORLD;
            case "getUUID" -> UUID.nameUUIDFromBytes(OVERWORLD.getBytes());
            default -> NONE;
        });
        Config config = fake(Config.class, (name, args) -> name.equals("getDirectory") ? dir : NONE);
        Server server = fake(Server.class, (name, args) -> switch (name) {
            case "getWorlds" -> List.of(world);
            case "getWorld" -> OVERWORLD.equals(args[0]) ? Optional.of(world) : Optional.empty();
            case "getPlayers" -> (Collection<Player>) List.copyOf(online);
            case "getConfig" -> null;
            default -> NONE;
        });
        chunky = new Chunksmith(server, config);
        clock = new MutableClock(Instant.parse("2026-11-26T14:00:00Z"));
        stalker = new ChunkyStalker(chunky, dir.resolve("stalker.json"), clock);
        stalker.setRunnerForTest(launched::add);
        stalker.data().settings.timezone = "UTC";
        stalker.markHooked();
    }

    @After
    public void tearDown() {
        chunky.getGenerationTasks().clear();
    }

    private void at(String utc) {
        clock.now = Instant.parse("2026-11-27T" + utc + ":00Z");
    }

    /** What GenerationTask does when its run loop ends. */
    private void end(GenerationTask task) {
        chunky.getTaskLoader().saveTask(task);
        chunky.getGenerationTasks().remove(OVERWORLD);
    }

    @Test
    public void fullNight() {
        stalker.onLogout("u1", "Alex", OVERWORLD, 1200.5, -340.25);

        // Afternoon: nothing happens.
        stalker.checkNow();
        assertTrue(launched.isEmpty());
        assertTrue(stalker.idleReason().startsWith("outside the window"));

        // 02:00, nobody online: starts, centred on the logout spot, with the default radius.
        at("02:00");
        stalker.checkNow();
        assertEquals(1, launched.size());
        Selection sel = launched.get(0).getSelection();
        assertEquals(1200.5, sel.centerX(), 0);
        assertEquals(-340.25, sel.centerZ(), 0);
        assertEquals(1000, sel.radiusX(), 0);
        assertEquals("circle", sel.shape());
        assertEquals("concentric", sel.pattern().getType());
        assertNotNull(stalker.data().active);

        // 02:30 someone joins: our task is asked to stop, and nothing new starts.
        at("02:30");
        online.add(fake(Player.class, (n, a) -> NONE));
        stalker.checkNow();
        assertTrue(launched.get(0).isStopping());
        assertEquals("players are online", stalker.idleReason());
        end(launched.get(0));
        stalker.checkNow();
        assertEquals(1, launched.size());

        // They leave at 03:00: the same job resumes from saved progress (a fresh task object).
        at("03:00");
        online.clear();
        stalker.checkNow();
        assertEquals(2, launched.size());
        assertEquals(1200.5, launched.get(1).getSelection().centerX(), 0);

        // It finishes: the spot is marked done and the queue is empty.
        launched.get(1).stop(true);
        end(launched.get(1));
        stalker.checkNow();
        assertNull(stalker.data().active);
        assertTrue(stalker.data().spots.get("u1").completed);
        stalker.checkNow();
        assertEquals(2, launched.size());
        assertEquals("nothing queued", stalker.idleReason());
    }

    @Test
    public void windowEndingPausesAndNextNightResumes() {
        stalker.onLogout("u1", "Alex", OVERWORLD, 0, 0);
        at("05:59");
        stalker.checkNow();
        assertEquals(1, launched.size());
        at("06:00");
        stalker.checkNow();
        assertTrue(launched.get(0).isStopping());
        end(launched.get(0));
        at("12:00");
        stalker.checkNow();
        assertEquals(1, launched.size());
        clock.now = Instant.parse("2026-11-28T02:01:00Z");
        stalker.checkNow();
        assertEquals(2, launched.size());
    }

    @Test
    public void somethingElseRestartingOurJobOutsideTheWindowGetsPausedAgain() {
        // e.g. continue-on-restart or /cs continue at noon.
        stalker.onLogout("u1", "Alex", OVERWORLD, 0, 0);
        at("02:00");
        stalker.checkNow();
        end(launched.get(0));
        at("13:00");
        GenerationTask restarted = chunky.getTaskLoader().loadTask(world).orElseThrow();
        chunky.getGenerationTasks().put(OVERWORLD, restarted);
        stalker.checkNow();
        assertTrue(restarted.isStopping());
    }

    @Test
    public void neverOverwritesAnOperatorsPausedTask() {
        GenerationTask manual = new GenerationTask(chunky, Selection.builder(chunky, world).center(5000, 5000).radius(3000).build());
        chunky.getTaskLoader().saveTask(manual); // paused, not cancelled
        stalker.onLogout("u1", "Alex", OVERWORLD, 0, 0);
        at("02:00");
        stalker.checkNow();
        assertTrue(launched.isEmpty());
        assertNull(stalker.data().active);
        // The operator cancels theirs; now the world is free.
        manual.stop(true);
        chunky.getTaskLoader().saveTask(manual);
        stalker.checkNow();
        assertEquals(1, launched.size());
    }

    @Test
    public void waitsForARunningTaskInTheSameWorld() {
        GenerationTask manual = new GenerationTask(chunky, Selection.builder(chunky, world).center(5000, 5000).radius(3000).build());
        chunky.getGenerationTasks().put(OVERWORLD, manual);
        stalker.onLogout("u1", "Alex", OVERWORLD, 0, 0);
        at("02:00");
        stalker.checkNow();
        assertTrue(launched.isEmpty());
        assertFalse(manual.isStopping());
    }

    @Test
    public void forcedRunIgnoresWindowAndPlayers() {
        online.add(fake(Player.class, (n, a) -> NONE));
        stalker.onLogout("u1", "Alex", OVERWORLD, 0, 0);
        stalker.forceRun();
        stalker.checkNow();
        assertEquals(1, launched.size());
        launched.get(0).stop(true);
        end(launched.get(0));
        stalker.checkNow();
        stalker.checkNow();
        assertFalse(stalker.isForced());
    }

    @Test
    public void stopHoldsUntilTheWindowEnds() {
        stalker.onLogout("u1", "Alex", OVERWORLD, 0, 0);
        at("02:00");
        stalker.checkNow();
        assertTrue(stalker.stopForNow());
        assertTrue(launched.get(0).isStopping());
        end(launched.get(0));
        at("03:00");
        stalker.checkNow();
        assertEquals(1, launched.size());
        at("07:00");
        stalker.checkNow();
        clock.now = Instant.parse("2026-11-28T02:00:00Z");
        stalker.checkNow();
        assertEquals(2, launched.size());
    }

    @Test
    public void perPlayerRadiusIsUsed() {
        stalker.onLogout("u1", "Alex", OVERWORLD, 0, 0);
        stalker.data().radii.put("u1", 2500d);
        at("02:00");
        stalker.checkNow();
        assertEquals(2500, launched.get(0).getSelection().radiusX(), 0);
    }

    @Test
    public void disabledDoesNothing() {
        stalker.data().settings.enabled = false;
        stalker.onLogout("u1", "Alex", OVERWORLD, 0, 0);
        at("02:00");
        stalker.checkNow();
        assertTrue(launched.isEmpty());
    }

    @Test
    public void stateSurvivesARestart() {
        stalker.onLogout("u1", "Alex", OVERWORLD, 42, 43);
        stalker.data().radii.put("u1", 1500d);
        stalker.changed();
        ChunkyStalker reloaded = new ChunkyStalker(chunky, dir.resolve("stalker.json"), clock);
        assertEquals(42, reloaded.data().spots.get("u1").x, 0);
        assertEquals(1500, reloaded.data().radii.get("u1"), 0);
        assertEquals("UTC", reloaded.data().settings.timezone);
    }

    @Test
    public void brokenFileIsSetAsideNotFatal() throws Exception {
        Path f = dir.resolve("broken.json");
        Files.writeString(f, "{ not json");
        ChunkyStalker s = new ChunkyStalker(chunky, f, clock);
        assertTrue(s.data().spots.isEmpty());
        assertTrue(Files.exists(dir.resolve("broken.json.broken")));
    }

    // ---------------------------------------------------------------------------------------------

    private static final Object NONE = new Object();

    private interface Answer {
        Object answer(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return type.getSimpleName() + "@fake";
                default:
                    break;
            }
            Object value = answer.answer(method.getName(), args);
            return value == NONE ? defaultFor(method.getReturnType()) : value;
        });
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        if (type == String.class) return "";
        if (type == Optional.class) return Optional.empty();
        if (type == List.class || type == Collection.class) return List.of();
        if (type == Map.class) return Map.of();
        return null;
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return self.withZone(z);
                }

                @Override
                public Instant instant() {
                    return self.now;
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
