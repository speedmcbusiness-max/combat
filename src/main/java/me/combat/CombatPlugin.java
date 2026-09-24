package me.combat;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class CombatPlugin extends JavaPlugin implements Listener, TabExecutor {

    /** player -> time (ms) when their combat tag expires */
    private final Map<UUID, Long> tagEnd = new HashMap<>();
    /** player -> the last player they fought with */
    private final Map<UUID, UUID> opponent = new HashMap<>();

    private final Set<String> whitelist = new HashSet<>();
    private final Set<String> blacklist = new HashSet<>();

    private int combatTime;
    private boolean killOnLog;
    private boolean blockUnlisted;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("combat") != null) {
            getCommand("combat").setExecutor(this);
            getCommand("combat").setTabCompleter(this);
        }

        // Runs every 0.5s: shows timer + expires tags
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(this, 10L, 10L);
    }

    @Override
    public void onDisable() {
        tagEnd.clear();
        opponent.clear();
    }

    // ------------------------------------------------------------------ config

    private void loadSettings() {
        reloadConfig();
        combatTime = Math.max(1, getConfig().getInt("combat-time", 15));
        killOnLog = getConfig().getBoolean("kill-on-combat-log", true);
        blockUnlisted = getConfig().getBoolean("block-unlisted-commands", false);

        whitelist.clear();
        blacklist.clear();
        for (String s : getConfig().getStringList("whitelist")) whitelist.add(normalize(s));
        for (String s : getConfig().getStringList("blacklist")) blacklist.add(normalize(s));
    }

    private void saveLists() {
        getConfig().set("whitelist", new ArrayList<>(new TreeSet<>(whitelist)));
        getConfig().set("blacklist", new ArrayList<>(new TreeSet<>(blacklist)));
        saveConfig();
    }

    private String msg(String key) {
        String prefix = getConfig().getString("messages.prefix", "");
        String m = getConfig().getString("messages." + key, key);
        if (key.equals("actionbar")) return color(m);
        return color(prefix + m);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String normalize(String cmd) {
        String c = cmd.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("/")) c = c.substring(1);
        int colon = c.indexOf(':');
        if (colon != -1) c = c.substring(colon + 1); // strip "minecraft:" etc.
        return c;
    }

    // ------------------------------------------------------------------ tagging

    private boolean isTagged(Player p) {
        return tagEnd.containsKey(p.getUniqueId());
    }

    private void tag(Player p, Player other) {
        UUID id = p.getUniqueId();
        boolean already = tagEnd.containsKey(id);
        // Every hit resets the timer -> while fighting it never runs out
        tagEnd.put(id, System.currentTimeMillis() + combatTime * 1000L);
        opponent.put(id, other.getUniqueId());
        if (!already) {
            p.sendMessage(msg("tagged").replace("%time%", String.valueOf(combatTime)));
        }
    }

    private void untag(UUID id, boolean notify) {
        boolean was = tagEnd.remove(id) != null;
        opponent.remove(id);
        if (was && notify) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(msg("untagged"));
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = tagEnd.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            UUID id = e.getKey();
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                it.remove();
                opponent.remove(id);
                continue;
            }
            long left = e.getValue() - now;
            if (left <= 0) {
                it.remove();
                opponent.remove(id);
                p.sendMessage(msg("untagged"));
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(color("&aCombat ended")));
            } else {
                long secs = (left + 999) / 1000;
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        new TextComponent(msg("actionbar").replace("%time%", String.valueOf(secs))));
            }
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player p) {
            attacker = p;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;

        tag(attacker, victim);
        tag(victim, attacker);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        UUID deadId = dead.getUniqueId();

        // Remove the dead player's cooldown
        boolean wasTagged = tagEnd.containsKey(deadId);
        untag(deadId, false);
        if (wasTagged) dead.sendMessage(msg("killed-cleared"));

        // Remove the cooldown of anyone who was fighting this player
        List<UUID> toClear = new ArrayList<>();
        for (Map.Entry<UUID, UUID> en : opponent.entrySet()) {
            if (en.getValue().equals(deadId)) toClear.add(en.getKey());
        }
        for (UUID id : toClear) untag(id, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!isTagged(p)) return;

        UUID id = p.getUniqueId();
        if (killOnLog) {
            Bukkit.broadcastMessage(msg("combat-log-broadcast").replace("%player%", p.getName()));
            p.setHealth(0.0); // fires PlayerDeathEvent -> drops items, clears opponent tag
        }
        untag(id, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isTagged(p) || p.hasPermission("combat.bypass")) return;

        String label = normalize(e.getMessage().split("\\s+")[0]);

        // Collect label + real name + aliases so aliases can't dodge the lists
        Set<String> names = new HashSet<>();
        names.add(label);
        Command cmd = Bukkit.getPluginCommand(label);
        if (cmd != null) {
            names.add(cmd.getName().toLowerCase(Locale.ROOT));
            for (String a : cmd.getAliases()) names.add(a.toLowerCase(Locale.ROOT));
        }

        boolean blocked;
        if (containsAny(whitelist, names)) {
            blocked = false;                     // whitelist: allowed in combat
        } else if (containsAny(blacklist, names)) {
            blocked = true;                      // blacklist: never allowed in combat
        } else {
            blocked = blockUnlisted;
        }

        if (blocked) {
            e.setCancelled(true);
            p.sendMessage(msg("command-blocked").replace("%command%", label));
        }
    }

    private boolean containsAny(Set<String> set, Set<String> names) {
        for (String n : names) if (set.contains(n)) return true;
        return false;
    }

    // ------------------------------------------------------------------ /combat command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("combat.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(color("&e/combat reload &7| &e/combat status [player]"));
            sender.sendMessage(color("&e/combat whitelist <add|remove|list> [command] &7- usable in combat"));
            sender.sendMessage(color("&e/combat blacklist <add|remove|list> [command] &7- blocked in combat"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage(color("&aCombatTag config reloaded."));
            }
            case "status" -> {
                Player t = args.length > 1 ? Bukkit.getPlayer(args[1]) : (sender instanceof Player pl ? pl : null);
                if (t == null) {
                    sender.sendMessage(color("&cPlayer not found."));
                } else if (!isTagged(t)) {
                    sender.sendMessage(color("&e" + t.getName() + " &7is not in combat."));
                } else {
                    long left = Math.max(0, (tagEnd.get(t.getUniqueId()) - System.currentTimeMillis()) / 1000);
                    sender.sendMessage(color("&e" + t.getName() + " &7is in combat for &e" + left + "s&7."));
                }
            }
            case "whitelist" -> handleList(sender, args, whitelist, blacklist, "whitelist");
            case "blacklist" -> handleList(sender, args, blacklist, whitelist, "blacklist");
            default -> sender.sendMessage(color("&cUnknown subcommand."));
        }
        return true;
    }

    private void handleList(CommandSender s, String[] args, Set<String> list, Set<String> other, String name) {
        if (args.length < 2) {
            s.sendMessage(color("&cUsage: /combat " + name + " <add|remove|list> [command]"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            s.sendMessage(color("&e" + name + ": &f" + (list.isEmpty() ? "(empty)" : String.join(", ", new TreeSet<>(list)))));
            return;
        }
        if (args.length < 3) {
            s.sendMessage(color("&cSpecify a command, e.g. /combat " + name + " " + action + " spawn"));
            return;
        }
        String cmd = normalize(args[2]);
        if (action.equals("add")) {
            list.add(cmd);
            other.remove(cmd); // a command can only be in one list
            saveLists();
            s.sendMessage(color("&aAdded &e/" + cmd + " &ato the " + name + "."));
        } else if (action.equals("remove")) {
            if (list.remove(cmd)) {
                saveLists();
                s.sendMessage(color("&aRemoved &e/" + cmd + " &afrom the " + name + "."));
            } else {
                s.sendMessage(color("&c/" + cmd + " is not in the " + name + "."));
            }
        } else {
            s.sendMessage(color("&cUse add, remove or list."));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("combat.admin")) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("reload", "status", "whitelist", "blacklist"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("whitelist") || args[0].equalsIgnoreCase("blacklist"))) {
            out.addAll(List.of("add", "remove", "list"));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            out.addAll(args[0].equalsIgnoreCase("whitelist") ? whitelist : blacklist);
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
