package ai.pollinations.minecraft;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Runs work on the right thread on Paper, Folia and Spigot.
 * Paper and Folia have region/entity schedulers; plain Spigot falls back to the Bukkit scheduler.
 */
public final class Tasks {

    private static final boolean REGION_SCHEDULERS = hasRegionSchedulers();

    private final Plugin plugin;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
    }

    private static boolean hasRegionSchedulers() {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Runs on the thread that owns the entity (its region on Folia, the main thread elsewhere). */
    public void entity(Entity entity, Runnable task) {
        if (REGION_SCHEDULERS) {
            entity.getScheduler().run(plugin, scheduled -> task.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Runs on the thread that owns the location's region. */
    public void region(Location location, Runnable task) {
        if (REGION_SCHEDULERS) {
            Bukkit.getRegionScheduler().run(plugin, location, scheduled -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Runs on the global thread (main thread on Paper/Spigot). */
    public void global(Runnable task) {
        if (REGION_SCHEDULERS) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Repeats a task on the global thread every {@code periodTicks}. */
    public void repeatGlobal(Runnable task, long periodTicks) {
        if (REGION_SCHEDULERS) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> task.run(), periodTicks, periodTicks);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, periodTicks, periodTicks);
        }
    }

    public void cancelAll() {
        if (REGION_SCHEDULERS) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }
}
