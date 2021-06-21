package org.skycraft.updater.bukkit;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.skycraft.updater.core.Provider;

public final class BukkitMain extends JavaPlugin implements Listener {
	private Provider provider;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		getServer().getPluginManager().registerEvents(this, this);

		Path clientModsPath;
		try {
			clientModsPath = Paths.get(getConfig().getString("updater.clientModsPath", "clientmods"));
		} catch (InvalidPathException e) {
			getLogger().log(Level.SEVERE, "Invalid client mods path", e);
			return;
		}
		if (!Files.exists(clientModsPath)) {
			try {
				Files.createDirectories(clientModsPath);
			} catch (IOException e) {
				getLogger().log(Level.SEVERE, "Could not create client mods folder", e);
				return;
			}
		}
		if (!Files.isDirectory(clientModsPath)) {
			getLogger().log(Level.SEVERE, "Client mods folder is not directory");
			return;
		}

		provider = new Provider(
			getLogger(),
			clientModsPath,
			new InetSocketAddress(getConfig().getString("updater.listenIp", "0.0.0.0"), getConfig().getInt("updater.listenPort", 80))
		);
		provider.run();
	}

	@Override
	public void onDisable() {
		if (provider != null) {
			provider.close();
			provider = null;
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		if (getConfig().getBoolean("updater.requireUpdater", true)) {
			Player player = event.getPlayer();
			if (!player.getListeningPluginChannels().contains("updater")) {
				player.kickPlayer(getConfig().getString("updater.kickMessage", "SkyCraft Updater is required to enter this server"));
			}
		}
	}
}
