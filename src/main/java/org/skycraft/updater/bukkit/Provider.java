package org.skycraft.updater.bukkit;

import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.bukkit.plugin.java.JavaPlugin;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public final class Provider extends JavaPlugin {
	private Path clientModsPath;
	private Map<String, Path> mods;
	private String precachedHashes;
	private Server server;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		if (!precacheHashes()) return;
		if (!startServer()) return;
		getLogger().log(Level.INFO, "Updater provider successfully started");
	}

	@Override
	public void onDisable() {
		clientModsPath = null;
		mods = null;
		precachedHashes = null;
		if (server != null) {
			try {
				if (server.isRunning()) server.stop();
			} catch (Exception e) {
				getLogger().log(Level.SEVERE, "Could not stop http server", e);
			}
			server = null;
		}
	}

	private boolean precacheHashes() {
		try {
			clientModsPath = Paths.get(getConfig().getString("updater.clientModsPath", "clientmods"));
		} catch (InvalidPathException e) {
			getLogger().log(Level.SEVERE, "Invalid client mods path", e);
			return false;
		}
		if (!Files.exists(clientModsPath)) {
			try {
				Files.createDirectories(clientModsPath);
			} catch (IOException e) {
				getLogger().log(Level.SEVERE, "Could not create client mods folder", e);
				return false;
			}
		}
		if (!Files.isDirectory(clientModsPath)) {
			getLogger().log(Level.SEVERE, "Client mods folder is not directory");
			return false;
		}
		getLogger().log(Level.INFO, "Precaching mod hashes...");
		HashMap<String, Path> mods = new HashMap<>();
		StringWriter writer = new StringWriter();
		try (JsonWriter json = new JsonWriter(writer)) {
			json.setIndent("    ");
			json.beginArray();
			for (Path path : (Iterable<? extends Path>) Files.walk(clientModsPath)::iterator) {
				if (!path.getFileName().toString().endsWith(".jar")) continue;
				if (!Files.isRegularFile(path)) continue;
				String hash;
				try (InputStream stream = Files.newInputStream(path)) {
					hash = DigestUtils.md5Hex(stream);
				} catch (IOException e) {
					getLogger().log(Level.WARNING, "Could not calculate mod hash", e);
					continue;
				}
				mods.put(hash, path);
				json.beginObject();
				json.name("path").value(clientModsPath.relativize(path).toString());
				json.name("hash").value(hash);
				json.endObject();
				getLogger().log(Level.INFO, "Found mod file \"" + clientModsPath.relativize(path) + "\" with hash \"" + hash + "\"");
			}
			json.endArray();
		} catch (IOException e) {
			getLogger().log(Level.SEVERE, "Error occurred while precaching mod hashes", e);
			return false;
		}
		this.mods = mods;
		this.precachedHashes = writer.toString();
		getLogger().log(Level.INFO, "Precached mod hashes");
		return true;
	}

	private boolean startServer() {
		Log.setLog(new NoLog());
		server = new Server(new InetSocketAddress(
			getConfig().getString("updater.listenIp", "0.0.0.0"),
			getConfig().getInt("updater.listenPort", 80)
		));
		ServletContextHandler handler = new ServletContextHandler();
		handler.setErrorHandler(new ErrorHandler() {
			@Override
			public void doError(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) {

			}
		});
		handler.addServlet(new ServletHolder(new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
				if (precachedHashes == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				getLogger().log(Level.INFO, "Sending mod hashes to client " + req.getRemoteAddr());
				resp.setContentType("application/json; charset=utf-8");
				try (PrintWriter out = resp.getWriter()) {
					out.write(precachedHashes);
				}
			}
		}), "/hashes");
		handler.addServlet(new ServletHolder(new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
				if (mods == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				String hash = req.getParameter("hash");
				if (hash == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				Path path = mods.get(hash);
				if (path == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				getLogger().log(Level.INFO, "Sending mod \"" + clientModsPath.relativize(path) + "\" with hash \"" + hash + "\" to client " + req.getRemoteAddr());
				resp.setContentType("application/octet-stream");
				resp.setHeader("Content-Disposition", "attachment; filename=" + StringEscapeUtils.escapeJava(path.getFileName().toString()));
				try (InputStream in = Files.newInputStream(path); OutputStream out = resp.getOutputStream()) {
					IOUtils.copy(in, out);
				}
			}
		}), "/download");
		server.setHandler(handler);
		try {
			server.start();
		} catch (Exception e) {
			getLogger().log(Level.SEVERE, "Could not start http server", e);
			return false;
		}
		return true;
	}

	private static class NoLog implements Logger {
		@Override public String getName() { return "NoLog"; }
		@Override public void warn(String msg, Object... args) { }
		@Override public void warn(Throwable thrown) { }
		@Override public void warn(String msg, Throwable thrown) { }
		@Override public void info(String msg, Object... args) { }
		@Override public void info(Throwable thrown) { }
		@Override public void info(String msg, Throwable thrown) { }
		@Override public boolean isDebugEnabled() { return false; }
		@Override public void setDebugEnabled(boolean enabled) { }
		@Override public void debug(String msg, Object... args) { }
		@Override public void debug(String msg, long value) { }
		@Override public void debug(Throwable thrown) { }
		@Override public void debug(String msg, Throwable thrown) { }
		@Override public Logger getLogger(String name) { return this; }
		@Override public void ignore(Throwable ignored) { }
	}
}
