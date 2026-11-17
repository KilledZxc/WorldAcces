package dev.worldaccess;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class WorldAccess extends JavaPlugin implements Listener, TabExecutor {

    private final Set<String> locked = new HashSet<>();
    private final Map<UUID, Long> msgCooldown = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        locked.addAll(getConfig().getStringList("locked-worlds"));
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("worldaccess");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    // ---------- логика ----------

    /** Главный мир (первый загруженный) закрыть нельзя. */
    private World mainWorld() {
        return Bukkit.getWorlds().get(0);
    }

    private boolean isLocked(World w) {
        return w != null && !w.equals(mainWorld()) && locked.contains(w.getName());
    }

    private void save() {
        getConfig().set("locked-worlds", new ArrayList<>(locked));
        saveConfig();
    }

    private List<World> resolve(String input) {
        for (World w : Bukkit.getWorlds()) {
            if (w.getName().equalsIgnoreCase(input)) return List.of(w);
        }
        World.Environment env = switch (input.toLowerCase()) {
            case "nether", "hell", "ад" -> World.Environment.NETHER;
            case "end", "the_end", "энд" -> World.Environment.THE_END;
            case "overworld", "normal" -> World.Environment.NORMAL;
            default -> null;
        };
        if (env == null) return List.of();
        return Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == env).collect(Collectors.toList());
    }

    private void evacuate(World w) {
        for (Player p : new ArrayList<>(w.getPlayers())) {
            p.teleport(mainWorld().getSpawnLocation());
            p.sendMessage(Component.text("This world is closed, you were sent back to spawn.", NamedTextColor.YELLOW));
        }
    }

    private void setLocked(World w, boolean lock) {
        if (w.equals(mainWorld())) return;
        if (lock) {
            locked.add(w.getName());
            evacuate(w);
        } else {
            locked.remove(w.getName());
        }
    }

    // ---------- события ----------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() == null) return;
        World to = e.getTo().getWorld();
        if (to == null || to.equals(e.getFrom().getWorld())) return;
        Player p = e.getPlayer();
        if (isLocked(to) && !p.hasPermission("worldaccess.bypass")) {
            e.setCancelled(true);
            long now = System.currentTimeMillis();
            if (now - msgCooldown.getOrDefault(p.getUniqueId(), 0L) > 2000) {
                msgCooldown.put(p.getUniqueId(), now);
                p.sendMessage(Component.text("This world is currently closed by an admin.", NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (isLocked(p.getWorld()) && !p.hasPermission("worldaccess.bypass")) {
            p.teleport(mainWorld().getSpawnLocation());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (isLocked(e.getRespawnLocation().getWorld())) {
            e.setRespawnLocation(mainWorld().getSpawnLocation());
        }
    }

    // ---------- команда ----------

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("list")) {
            s.sendMessage(Component.text("Worlds:", NamedTextColor.GOLD));
            for (World w : Bukkit.getWorlds()) {
                boolean closed = isLocked(w);
                String note = w.equals(mainWorld()) ? " (main, always open)" : "";
                s.sendMessage(Component.text(" " + w.getName() + " [" + w.getEnvironment() + "]: "
                        + (closed ? "CLOSED" : "open") + note,
                        closed ? NamedTextColor.RED : NamedTextColor.GREEN));
            }
            s.sendMessage(Component.text("Commands: /wa <world> on|off, /wa only <world>, /wa all", NamedTextColor.GRAY));
            return true;
        }

        if (a[0].equalsIgnoreCase("all")) {
            locked.clear();
            save();
            s.sendMessage(Component.text("All worlds are open.", NamedTextColor.GREEN));
            return true;
        }

        if (a[0].equalsIgnoreCase("only") && a.length >= 2) {
            List<World> keep = resolve(a[1]);
            if (keep.isEmpty()) {
                s.sendMessage(Component.text("World not found: " + a[1], NamedTextColor.RED));
                return true;
            }
            for (World w : Bukkit.getWorlds()) {
                setLocked(w, !keep.contains(w));
            }
            save();
            s.sendMessage(Component.text("Only open: main world + "
                    + keep.stream().map(World::getName).collect(Collectors.joining(", ")), NamedTextColor.GREEN));
            return true;
        }

        if (a.length >= 2) {
            List<World> targets = resolve(a[0]);
            if (targets.isEmpty()) {
                s.sendMessage(Component.text("World not found: " + a[0], NamedTextColor.RED));
                return true;
            }
            boolean on = a[1].equalsIgnoreCase("on");
            boolean off = a[1].equalsIgnoreCase("off");
            if (!on && !off) {
                s.sendMessage(Component.text("Specify on (open) or off (close).", NamedTextColor.RED));
                return true;
            }
            for (World w : targets) {
                if (w.equals(mainWorld())) {
                    s.sendMessage(Component.text("Main world " + w.getName() + " cannot be closed.", NamedTextColor.RED));
                    continue;
                }
                setLocked(w, off);
                s.sendMessage(Component.text("World " + w.getName() + ": " + (on ? "open" : "closed"),
                        on ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            save();
            return true;
        }

        s.sendMessage(Component.text("Usage: /wa list | /wa <world> on|off | /wa only <world> | /wa all", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("list", "all", "only", "nether", "end", "overworld"));
            out.addAll(worlds);
        } else if (a.length == 2) {
            if (a[0].equalsIgnoreCase("only")) {
                out.addAll(List.of("nether", "end", "overworld"));
                out.addAll(worlds);
            } else {
                out.addAll(List.of("on", "off"));
            }
        }
        String last = a[a.length - 1].toLowerCase();
        return out.stream().filter(x -> x.toLowerCase().startsWith(last)).collect(Collectors.toList());
    }
}
