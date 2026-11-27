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

package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.GenerationTask;
import com.kishku7.chunksmith.iterator.PatternType;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.shape.ShapeType;
import com.kishku7.chunksmith.stalker.ChunkyStalker;
import com.kishku7.chunksmith.stalker.StalkerData;
import com.kishku7.chunksmith.stalker.StalkerSpots;
import com.kishku7.chunksmith.stalker.StalkerWindow;
import com.kishku7.chunksmith.util.Input;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * {@code /cs stalker ...} -- inspect and control ChunkyStalker.
 *
 * <p>Messages are plain English rather than lang keys: Translator falls back to the key text itself,
 * and every message goes through {@link #say} so a stray {@code %} can never reach String.format.
 */
public class StalkerCommand implements ChunksmithCommand {
    public static final List<String> ACTIONS = List.of(
            "status", "list", "run", "stop", "radius", "reset", "remove", "set", "help");
    public static final List<String> SETTINGS = List.of(
            "enabled", "start", "end", "timezone", "radius", "expiry", "pauseonjoin", "shape", "pattern");

    private final Chunksmith chunky;

    public StalkerCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        ChunkyStalker stalker = chunky.getStalker();
        String action = arguments.next().orElse("status").toLowerCase();
        switch (action) {
            case "status" -> status(sender, stalker);
            case "list" -> list(sender, stalker);
            case "run" -> {
                stalker.forceRun();
                CommandAudit.note(chunky, sender, "forced", "a ChunkyStalker run");
                say(sender, "&2Running the queue now&r, ignoring the window and online players, until it is empty."
                        + " Use &2/cs stalker stop&r to halt.");
            }
            case "stop" -> {
                boolean wasRunning = stalker.stopForNow();
                CommandAudit.note(chunky, sender, "stopped", "ChunkyStalker until the window ends");
                say(sender, (wasRunning ? "Paused the current area. " : "")
                        + "ChunkyStalker stays idle until the current window ends. Progress is kept.");
            }
            case "radius" -> radius(sender, stalker, arguments);
            case "reset" -> reset(sender, stalker, arguments);
            case "remove" -> remove(sender, stalker, arguments);
            case "set" -> set(sender, stalker, arguments);
            default -> help(sender);
        }
    }

    private void status(Sender sender, ChunkyStalker stalker) {
        StalkerData data = stalker.data();
        StalkerData.Settings s = data.settings;
        ZoneId zone = stalker.zone();
        StringBuilder out = new StringBuilder("&2ChunkyStalker&r");
        if (!stalker.isHooked()) {
            out.append("\n&cThis platform does not report logouts or drive the schedule -- ChunkyStalker is inactive here.&r");
        }
        out.append("\nEnabled: ").append(s.enabled ? "&ayes&r" : "&cno&r")
                .append("   Window: ").append(s.windowStart).append("-").append(s.windowEnd)
                .append(" (").append(zone.getId()).append(")")
                .append(stalker.isInWindow() ? " &a[open]&r" : " [closed]");
        out.append("\nDefault radius: ").append(Math.round(s.defaultRadius))
                .append("   Forget after: ").append(s.expiryDays > 0 ? s.expiryDays + " days" : "never")
                .append("   Pause when players online: ").append(s.pauseWhenPlayersOnline ? "yes" : "no");
        out.append("\nQueued spots: ").append(StalkerSpots.pending(data).size())
                .append(" of ").append(data.spots.size());
        if (stalker.isForced()) {
            out.append("   &e[forced run]&r");
        }
        StalkerData.Job job = data.active;
        if (job != null) {
            out.append("\nCurrent: ").append(job.name).append(" -- ").append(job.world).append(" at ")
                    .append(Math.round(job.x)).append(", ").append(Math.round(job.z))
                    .append(" radius ").append(Math.round(job.radius));
            Optional<GenerationTask> task = stalker.activeTask();
            if (task.isPresent()) {
                GenerationTask.Progress p = task.get().getProgress();
                out.append(String.format(" -- %.1f%% at %.1f chunks/s", p.getPercentComplete(), p.getRate()));
            } else {
                out.append(" (paused)");
            }
        }
        out.append("\nState: ").append(stalker.idleReason());
        say(sender, out.toString());
    }

    private void list(Sender sender, ChunkyStalker stalker) {
        StalkerData data = stalker.data();
        if (data.spots.isEmpty()) {
            say(sender, "No logout spots recorded yet.");
            return;
        }
        long now = System.currentTimeMillis();
        StringBuilder out = new StringBuilder("&2Logout spots&r (most recent first)");
        data.spots.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().loggedOutAt, a.getValue().loggedOutAt))
                .forEach(entry -> {
                    StalkerData.Spot spot = entry.getValue();
                    boolean custom = data.radii.containsKey(entry.getKey());
                    out.append("\n").append(spot.completed ? "&a[done]&r " : "&e[queued]&r ")
                            .append(spot.name).append(" -- ").append(spot.world).append(" ")
                            .append(Math.round(spot.x)).append(", ").append(Math.round(spot.z))
                            .append(" r=").append(Math.round(StalkerSpots.radiusFor(data, entry.getKey())))
                            .append(custom ? "*" : "")
                            .append(" -- ").append(ago(now - spot.loggedOutAt));
                });
        say(sender, out.toString());
    }

    private void radius(Sender sender, ChunkyStalker stalker, CommandArguments arguments) {
        Optional<String> name = arguments.next();
        Optional<String> value = arguments.next();
        if (name.isEmpty() || value.isEmpty()) {
            say(sender, "Usage: /cs stalker radius <player> <blocks|default>");
            return;
        }
        StalkerData data = stalker.data();
        Optional<String> uuid = StalkerSpots.findByName(data, name.get());
        if (uuid.isEmpty()) {
            say(sender, "No logout spot recorded for " + name.get() + ". They need to log out once first.");
            return;
        }
        double before = StalkerSpots.radiusFor(data, uuid.get());
        if ("default".equalsIgnoreCase(value.get())) {
            data.radii.remove(uuid.get());
        } else {
            Optional<Double> blocks = Input.tryDoubleSuffixed(value.get()).filter(r -> r > 0 && !Input.isPastWorldLimit(r));
            if (blocks.isEmpty()) {
                say(sender, "Radius must be a positive number of blocks, e.g. 1500 or 2k.");
                return;
            }
            data.radii.put(uuid.get(), blocks.get());
        }
        double after = StalkerSpots.radiusFor(data, uuid.get());
        StalkerData.Spot spot = data.spots.get(uuid.get());
        if (after > before && spot.completed) {
            // A bigger circle around a finished spot has new ground in it.
            spot.completed = false;
        }
        stalker.changed();
        say(sender, "Radius for " + spot.name + " is now " + Math.round(after) + " blocks.");
    }

    private void reset(Sender sender, ChunkyStalker stalker, CommandArguments arguments) {
        Optional<String> name = arguments.next();
        if (name.isEmpty()) {
            say(sender, "Usage: /cs stalker reset <player|all>");
            return;
        }
        StalkerData data = stalker.data();
        if ("all".equalsIgnoreCase(name.get())) {
            data.spots.values().forEach(spot -> spot.completed = false);
            stalker.changed();
            say(sender, "Re-queued every logout spot (" + data.spots.size() + ").");
            return;
        }
        Optional<String> uuid = StalkerSpots.findByName(data, name.get());
        if (uuid.isEmpty()) {
            say(sender, "No logout spot recorded for " + name.get() + ".");
            return;
        }
        data.spots.get(uuid.get()).completed = false;
        stalker.changed();
        say(sender, "Re-queued " + data.spots.get(uuid.get()).name + "'s spot.");
    }

    private void remove(Sender sender, ChunkyStalker stalker, CommandArguments arguments) {
        Optional<String> name = arguments.next();
        if (name.isEmpty()) {
            say(sender, "Usage: /cs stalker remove <player>");
            return;
        }
        StalkerData data = stalker.data();
        Optional<String> uuid = StalkerSpots.findByName(data, name.get());
        if (uuid.isEmpty()) {
            say(sender, "No logout spot recorded for " + name.get() + ".");
            return;
        }
        StalkerData.Spot spot = data.spots.remove(uuid.get());
        data.radii.remove(uuid.get());
        stalker.changed();
        say(sender, "Forgot " + spot.name + "'s logout spot and radius."
                + (data.active != null && uuid.get().equals(data.active.uuid) ? " The area already in progress will still finish." : ""));
    }

    private void set(Sender sender, ChunkyStalker stalker, CommandArguments arguments) {
        Optional<String> key = arguments.next().map(String::toLowerCase);
        List<String> rest = arguments.remaining();
        if (key.isEmpty() || rest.isEmpty()) {
            say(sender, "Usage: /cs stalker set <" + String.join("|", SETTINGS) + "> <value>");
            return;
        }
        String value = String.join(" ", rest).trim();
        StalkerData.Settings s = stalker.data().settings;
        switch (key.get()) {
            case "enabled" -> {
                Optional<Boolean> b = Input.tryBoolean(value);
                if (b.isEmpty()) {
                    say(sender, "enabled must be true or false.");
                    return;
                }
                s.enabled = b.get();
            }
            case "start", "end" -> {
                Optional<java.time.LocalTime> t = StalkerWindow.parse(value);
                if (t.isEmpty()) {
                    say(sender, "Time must look like 2, 02:00 or 0230 (24-hour clock).");
                    return;
                }
                if (key.get().equals("start")) {
                    s.windowStart = StalkerWindow.format(t.get());
                } else {
                    s.windowEnd = StalkerWindow.format(t.get());
                }
            }
            case "timezone" -> {
                if (value.equalsIgnoreCase("server") || value.equalsIgnoreCase("system")) {
                    s.timezone = "";
                } else {
                    try {
                        s.timezone = ZoneId.of(value).getId();
                    } catch (RuntimeException e) {
                        say(sender, "Unknown time zone. Use an id like America/New_York, Europe/London, UTC, or 'server'.");
                        return;
                    }
                }
            }
            case "radius" -> {
                Optional<Double> r = Input.tryDoubleSuffixed(value).filter(v -> v > 0 && !Input.isPastWorldLimit(v));
                if (r.isEmpty()) {
                    say(sender, "Radius must be a positive number of blocks, e.g. 1000 or 2k.");
                    return;
                }
                s.defaultRadius = r.get();
            }
            case "expiry" -> {
                Optional<Integer> d = Input.tryInteger(value).filter(v -> v >= 0);
                if (d.isEmpty()) {
                    say(sender, "expiry is a number of days; 0 means never forget.");
                    return;
                }
                s.expiryDays = d.get();
            }
            case "pauseonjoin" -> {
                Optional<Boolean> b = Input.tryBoolean(value);
                if (b.isEmpty()) {
                    say(sender, "pauseonjoin must be true or false.");
                    return;
                }
                s.pauseWhenPlayersOnline = b.get();
            }
            case "shape" -> {
                Optional<String> shape = Input.tryShape(value);
                if (shape.isEmpty()) {
                    say(sender, "Unknown shape. Try: " + String.join(", ", ShapeType.all()));
                    return;
                }
                s.shape = shape.get();
            }
            case "pattern" -> {
                String p = value.toLowerCase();
                if (!List.of(PatternType.CONCENTRIC, PatternType.LOOP, PatternType.SPIRAL, PatternType.REGION).contains(p)) {
                    say(sender, "pattern must be concentric, loop, spiral or region.");
                    return;
                }
                s.pattern = p;
            }
            default -> {
                say(sender, "Unknown setting. Settings: " + String.join(", ", SETTINGS));
                return;
            }
        }
        stalker.changed();
        CommandAudit.note(chunky, sender, "set", "ChunkyStalker " + key.get() + " to " + value);
        say(sender, "ChunkyStalker " + key.get() + " set to " + value + ". It applies from the next job.");
    }

    private void help(Sender sender) {
        say(sender, "&2ChunkyStalker&r -- pre-generates around where players logged out, overnight."
                + "\n&2/cs stalker status&r - schedule, queue and current job"
                + "\n&2/cs stalker list&r - every recorded logout spot"
                + "\n&2/cs stalker run&r - work through the queue now"
                + "\n&2/cs stalker stop&r - pause until the current window ends"
                + "\n&2/cs stalker radius <player> <blocks|default>&r - per-player radius"
                + "\n&2/cs stalker reset <player|all>&r - queue a spot again"
                + "\n&2/cs stalker remove <player>&r - forget a player's spot"
                + "\n&2/cs stalker set <setting> <value>&r - " + String.join(", ", SETTINGS));
    }

    private static String ago(long millis) {
        Duration d = Duration.ofMillis(Math.max(0, millis));
        if (d.toDays() > 0) {
            return d.toDays() + "d ago";
        }
        if (d.toHours() > 0) {
            return d.toHours() + "h ago";
        }
        return Math.max(1, d.toMinutes()) + "m ago";
    }

    /** Sends literal text. Translator runs String.format on it, so % must be escaped. */
    private static void say(Sender sender, String text) {
        sender.sendMessagePrefixed(text.replace("%", "%%"));
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
        if (arguments.size() == 1) {
            return ACTIONS;
        }
        if (arguments.size() == 2) {
            String action = arguments.next().orElse("");
            if (action.equals("set")) {
                return SETTINGS;
            }
            if (action.equals("radius") || action.equals("reset") || action.equals("remove")) {
                return chunky.getStalker().data().spots.values().stream().map(spot -> spot.name).toList();
            }
        }
        return List.of();
    }
}
