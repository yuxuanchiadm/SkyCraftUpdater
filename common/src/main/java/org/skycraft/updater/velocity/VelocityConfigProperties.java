package org.skycraft.updater.velocity;

import ch.jalu.configme.SettingsHolder;
import ch.jalu.configme.properties.Property;

import static ch.jalu.configme.properties.PropertyInitializer.newListProperty;
import static ch.jalu.configme.properties.PropertyInitializer.newProperty;
import java.util.List;

public class VelocityConfigProperties implements SettingsHolder {
    public static final Property<Boolean> ENABLE_PROVIDER = newProperty("updater.enableProvider", true);
    public static final Property<String> CLIENT_PATH = newProperty("updater.clientPath", "client");
    public static final Property<String> MANIFEST_PATH = newProperty("updater.manifestPath", "client_manifest.json");
    public static final Property<String> LISTEN_IP = newProperty("updater.listenIp", "0.0.0.0");
    public static final Property<Integer> LISTEN_PORT = newProperty("updater.listenPort", 80);
    public static final Property<Boolean> REQUIRE_UPDATER = newProperty("updater.requireUpdater", true);
    public static final Property<String> KICK_MESSAGE = newProperty("updater.kickMessage", "SkyCraft Updater is required to enter this server");
    public static final Property<List<String>> CHECK_SERVERS = newListProperty("updater.checkServers", "lobby", "login");
}
