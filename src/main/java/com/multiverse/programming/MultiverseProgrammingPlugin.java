// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class MultiverseProgrammingPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private RecipeManager recipeManager;
    private ComputerListener computerListener;
    private com.multiverse.programming.blueprint.BlueprintManager blueprintManager;
    private com.multiverse.programming.turtle.TurtleManager turtleManager;
    private com.multiverse.programming.turtle.TurtleListener turtleListener;
    private com.multiverse.programming.web.WebServerManager webServerManager;
    private com.multiverse.programming.protection.ProtectionManager protectionManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        configManager = new ConfigManager(this);
        configManager.load();
        LuaRunner.configureConcurrency(configManager.getMaxConcurrentPrograms());

        protectionManager = new com.multiverse.programming.protection.ProtectionManager(this);

        recipeManager = new RecipeManager(this);
        recipeManager.registerAll(
                configManager.getComputerBlock(),
                configManager.getAdvancedComputerBlock(),
                configManager.isEnableComputerRecipe()
        );

        computerListener = new ComputerListener(
                this,
                configManager.getComputerBlock(),
                configManager.getAdvancedComputerBlock()
        );
        computerListener.loadAdvancedDisks();
        getServer().getPluginManager().registerEvents(computerListener, this);

        // Initialize Blueprints, Turtles, and Web Server
        blueprintManager = new com.multiverse.programming.blueprint.BlueprintManager(this);
        blueprintManager.loadAll();

        turtleManager = new com.multiverse.programming.turtle.TurtleManager(this);
        turtleManager.loadAll();
        turtleListener = new com.multiverse.programming.turtle.TurtleListener(this);
        getServer().getPluginManager().registerEvents(turtleListener, this);

        webServerManager = new com.multiverse.programming.web.WebServerManager(this);
        webServerManager.start();

        getServer().getPluginManager().registerEvents(new ItemSecurityListener(this), this);

        ComputerCommand commandHandler = new ComputerCommand(this);
        PluginCommand mvprogCommand = getCommand("mvprog");
        if (mvprogCommand != null) {
            mvprogCommand.setExecutor(commandHandler);
            mvprogCommand.setTabCompleter(commandHandler);
        }

        getLogger().info("MultiverseProgramming enabled (computer: " + configManager.getComputerBlock().name()
                + ", advanced: " + configManager.getAdvancedComputerBlock().name()
                + ", turtle: " + configManager.getTurtleBlock().name() + ").");
    }

    @Override
    public void onDisable() {
        if (webServerManager != null) {
            webServerManager.stop();
        }
        if (turtleManager != null) {
            turtleManager.cancelAll();
        }
        if (computerListener != null) {
            computerListener.saveAdvancedDisks();
        }
        ComputerListener.cancelAll();
        com.multiverse.programming.peripheral.MapDisplay.removeAll();
        if (recipeManager != null) {
            recipeManager.unregisterAll();
        }
        LuaRunner.shutdownPool();
        getLogger().info("MultiverseProgramming disabled.");
    }

    /**
     * Safely reloads plugin configuration, updates event listeners and re-registers crafting recipes.
     */
    public void reloadPluginConfig() {
        if (configManager != null) {
            configManager.load();
            LuaRunner.configureConcurrency(configManager.getMaxConcurrentPrograms());
            if (computerListener != null) {
                computerListener.updateMaterials(
                        configManager.getComputerBlock(),
                        configManager.getAdvancedComputerBlock()
                );
            }
            if (recipeManager != null) {
                recipeManager.registerAll(
                        configManager.getComputerBlock(),
                        configManager.getAdvancedComputerBlock(),
                        configManager.isEnableComputerRecipe()
                );
            }
            if (webServerManager != null) {
                webServerManager.stop();
                webServerManager.start();
            }
            getLogger().info("Configuration reloaded (computer: " + configManager.getComputerBlock().name()
                    + ", advanced: " + configManager.getAdvancedComputerBlock().name() + ").");
        }
    }

    public String getPrefix() {
        return "§8[§bComputer§8]";
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public RecipeManager getRecipeManager() {
        return recipeManager;
    }

    public ComputerListener getComputerListener() {
        return computerListener;
    }

    public long getTimeoutMs() {
        return configManager != null ? configManager.getTimeoutMs() : 3000L;
    }

    public long getAdvancedTimeoutMs() {
        return configManager != null ? configManager.getAdvancedTimeoutMs() : 0L;
    }

    public com.multiverse.programming.blueprint.BlueprintManager getBlueprintManager() {
        return blueprintManager;
    }

    public com.multiverse.programming.turtle.TurtleManager getTurtleManager() {
        return turtleManager;
    }

    public com.multiverse.programming.web.WebServerManager getWebServerManager() {
        return webServerManager;
    }

    public com.multiverse.programming.protection.ProtectionManager getProtectionManager() {
        return protectionManager;
    }
}