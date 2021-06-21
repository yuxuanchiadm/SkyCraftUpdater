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
		getConfig().options().copyHeader(true);
		getConfig().options().copyDefaults(true);
		saveConfig();
		getServer().getPluginManager().registerEvents(this, this);

		Path clientPath;
		try {
			clientPath = Paths.get(getConfig().getString("updater.clientPath"));
		} catch (InvalidPathException e) {
			getLogger().log(Level.SEVERE, "Invalid client path", e);
			return;
		}
		if (!Files.exists(clientPath)) {
			try {
				Files.createDirectories(clientPath);
			} catch (IOException e) {
				getLogger().log(Level.SEVERE, "Could not create client folder", e);
				return;
			}
		}
		if (!Files.isDirectory(clientPath)) {
			getLogger().log(Level.SEVERE, "Client folder is not directory");
			return;
		}

		Path manifestPath;
		try {
			manifestPath = Paths.get(getConfig().getString("updater.manifestPath"));
		} catch (InvalidPathException e) {
			getLogger().log(Level.SEVERE, "Invalid manifest path", e);
			return;
		}
		if (!Files.exists(manifestPath)) {
			try {
				Files.copy(getResource("client_manifest.json"), manifestPath);
			} catch (IOException e) {
				getLogger().log(Level.SEVERE, "Could not save default manifest", e);
				return;
			}
		}
		if (!Files.isRegularFile(manifestPath)) {
			getLogger().log(Level.SEVERE, "Manifest is not regular file");
			return;
		}

		if (getConfig().getBoolean("updater.enableProvider")) {
			provider = new Provider(
				getLogger(),
				clientPath,
				manifestPath,
				new InetSocketAddress(getConfig().getString("updater.listenIp"), getConfig().getInt("updater.listenPort"))
			);
			provider.run();
		}
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
		if (getConfig().getBoolean("updater.requireUpdater")) {
			Player player = event.getPlayer();
			if (!player.getListeningPluginChannels().contains("updater")) {
				player.kickPlayer(getConfig().getString("updater.kickMessage"));
			}
		}
	}
}
