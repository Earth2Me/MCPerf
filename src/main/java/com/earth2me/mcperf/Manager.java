package com.earth2me.mcperf;

import com.earth2me.mcperf.config.ConfigSetting;
import com.earth2me.mcperf.config.Configurable;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Server;
import org.bukkit.event.Listener;

import java.util.logging.Logger;

public abstract class Manager implements Listener, Configurable {
    @Getter
    @Setter
    @ConfigSetting
    private boolean enabled;
    @Getter
    private final Logger logger;
    @Getter
    private final Server server;
    @Getter
    private final MCPerfPlugin plugin;
    @Getter
    private final PluginCommandSender commandSender;
    private String configPathCache;

    public Manager(Server server, Logger logger, MCPerfPlugin plugin) {
        this(server, logger, plugin, true);
    }

    public Manager(Server server, Logger logger, MCPerfPlugin plugin, boolean enabled) {
        this.server = server;
        this.logger = logger;
        this.plugin = plugin;
        this.enabled = enabled;
        this.commandSender = new PluginCommandSender(server, getClass().getSimpleName());
    }

    public void dispatchCommand(String command) {
        getServer().dispatchCommand(getCommandSender(), command);
    }

    public void dispatchCommand(String format, Object... args) {
        dispatchCommand(String.format(format, args));
    }

    protected void println(String format, Object... args) {
        Util.println(getServer(), format, args);
    }

    protected void println(String message) {
        Util.println(getServer(), message);
    }

    protected void sendAlert(String format, Object... args) {
        Util.sendAlert(getServer(), format, args);
    }

    protected void sendAlert(String message) {
        Util.sendAlert(getServer(), message);
    }

    @Override
    public String getConfigPath() {
        if (configPathCache == null) {
            String name = getClass().getSimpleName();
            if (name.length() > 1) {
                configPathCache = Character.toLowerCase(name.charAt(0)) + name.substring(1);
            } else {
                assert !name.isEmpty();
                configPathCache = name.toLowerCase();
            }
        }

        return configPathCache;
    }
}
