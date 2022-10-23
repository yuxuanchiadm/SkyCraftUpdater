package org.skycraft.updater.velocity;

import ch.jalu.configme.SettingsManager;
import ch.jalu.configme.SettingsManagerBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.skycraft.updater.core.Provider;

@Plugin(id = "skycraftupdater", name = "SkyCraftUpdater")
public class VelocityMain {
    private final ProxyServer proxy;
    private final Logger logger;

    private SettingsManager settings;
	private Provider provider;

    @Inject
    public VelocityMain(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
		File configFile = new File("./plugins/SkyCraftUpdater/config.yml");
        settings = SettingsManagerBuilder.withYamlFile(configFile)
            .useDefaultMigrationService()
            .configurationData(VelocityConfigProperties.class)
            .create();

		Path clientPath;
		try {
            clientPath = Paths.get(settings.getProperty(VelocityConfigProperties.CLIENT_PATH));
		} catch (InvalidPathException e) {
            logger.log(Level.SEVERE, "Invalid client path", e);
			return;
		}
		if (!Files.exists(clientPath)) {
			try {
				Files.createDirectories(clientPath);
			} catch (IOException e) {
                logger.log(Level.SEVERE, "Could not create client folder", e);
				return;
			}
		}
		if (!Files.isDirectory(clientPath)) {
            logger.log(Level.SEVERE, "Client folder is not directory");
			return;
		}

		Path manifestPath;
		try {
            manifestPath = Paths.get(settings.getProperty(VelocityConfigProperties.MANIFEST_PATH));
		} catch (InvalidPathException e) {
            logger.log(Level.SEVERE, "Invalid manifest path", e);
			return;
		}
		if (!Files.exists(manifestPath)) {
			try {
				Files.copy(Objects.requireNonNull(VelocityMain.class.getClassLoader().getResource("client_manifest.json")).openConnection().getInputStream(), manifestPath);
			} catch (IOException e) {
                logger.log(Level.SEVERE, "Could not save default manifest", e);
				return;
			}
		}
		if (!Files.isRegularFile(manifestPath)) {
            logger.log(Level.SEVERE, "Manifest is not regular file");
			return;
		}

        if (settings.getProperty(VelocityConfigProperties.ENABLE_PROVIDER)) {
            provider = new Provider(
                logger,
				clientPath,
				manifestPath,
				new InetSocketAddress(settings.getProperty(VelocityConfigProperties.LISTEN_IP), settings.getProperty(VelocityConfigProperties.LISTEN_PORT))
			);
			provider.run();
		}
    }
    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
		if (provider != null) {
			provider.close();
			provider = null;
		}
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPlayerConnectedToServer(final ServerConnectedEvent event) {
    	String serverName = event.getServer().getServerInfo().getName();
    	if (settings.getProperty(VelocityConfigProperties.CHECK_SERVERS).stream().noneMatch(serverName::equalsIgnoreCase)) {
			return;
		}

		if (settings.getProperty(VelocityConfigProperties.REQUIRE_UPDATER)) {
            Player player = event.getPlayer();
			if (!player.getModInfo().filter(modInfo -> modInfo.getMods().stream().anyMatch(mod -> mod.getId().equalsIgnoreCase("updater"))).isPresent()) {
                Component component = Component.text(settings.getProperty(VelocityConfigProperties.KICK_MESSAGE)).color(NamedTextColor.RED);
				player.disconnect(component);
			}
		}
    }
}
