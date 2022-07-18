package org.skycraft.updater.core;

import com.google.gson.stream.JsonReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.skycraft.updater.core.protocol.ProtocolHandler;

public final class Provider implements Runnable, Closeable {
	private final Logger logger;
	private final Path clientPath;
	private final Path manifestPath;
	private final InetSocketAddress address;
	private Server server;

	public Provider(Logger logger, Path clientPath, Path manifestPath, InetSocketAddress address) {
		this.logger = logger;
		this.clientPath = clientPath;
		this.manifestPath = manifestPath;
		this.address = address;
	}

	public Logger getLogger() {
		return logger;
	}

	public Path getClientPath() {
		return clientPath;
	}

	public Path getManifestPath() {
		return manifestPath;
	}

	@Override
	public void run() {
		logger.log(Level.INFO, "Starting updater provider...");
		if (!precacheManifest()) return;
		if (!precacheHashes()) return;
		if (!startServer()) return;
		logger.log(Level.INFO, "Updater provider successfully started");
	}

	@Override
	public void close() {
		logger.log(Level.INFO, "Stopping updater provider...");
		if (server != null) {
			try {
				if (server.isRunning()) server.stop();
			} catch (Exception e) {
				logger.log(Level.SEVERE, "Could not stop http server", e);
			}
			server = null;
		}
		logger.log(Level.INFO, "Updater provider successfully stopped");
	}

	private boolean precacheManifest() {
		logger.log(Level.INFO, "Precaching provider manifest");
		for (Protocol protocol : Protocol.values()) {
			ProtocolHandler protocolHandler = protocol.getHandler();
			try (JsonReader reader = new JsonReader(new InputStreamReader(Files.newInputStream(manifestPath), StandardCharsets.UTF_8))) {
				if (!protocolHandler.precacheManifest(this, reader)) return false;
			} catch (IOException | IllegalStateException e) {
				logger.log(Level.SEVERE, "Could not load provider manifest", e);
				return false;
			}
		}
		logger.log(Level.INFO, "Manifest successfully precached");
		return true;
	}

	private boolean precacheHashes() {
		logger.log(Level.INFO, "Precaching manifest hashes...");
		for (Protocol protocol : Protocol.values()) {
			ProtocolHandler protocolHandler = protocol.getHandler();
			if (!protocolHandler.precacheHashes(this)) return false;
		}
		logger.log(Level.INFO, "Manifest hashes successfully precached");
		return true;
	}

	private boolean startServer() {
		Log.setLog(new NoLog());
		server = new Server(new InetSocketAddress(
			address.getHostString(),
			address.getPort()
		));
		ServletContextHandler handler = new ServletContextHandler();
		handler.setErrorHandler(new ErrorHandler() {
			@Override
			public void doError(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) {

			}
		});
		for (Protocol protocol : Protocol.values()) {
			ProtocolHandler protocolHandler = protocol.getHandler();
			handler.addServlet(new ServletHolder(new HttpServlet() {
				@Override
				protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
					protocolHandler.handleManifest(Provider.this, req, resp);
				}
			}), protocolHandler.getProtocolURL("manifest"));
			handler.addServlet(new ServletHolder(new HttpServlet() {
				@Override
				protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
					protocolHandler.handleHashes(Provider.this, req, resp);
				}
			}), protocolHandler.getProtocolURL("hashes"));
			handler.addServlet(new ServletHolder(new HttpServlet() {
				@Override
				protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
					protocolHandler.handleDownload(Provider.this, req, resp);
				}
			}), protocolHandler.getProtocolURL("download"));
		}
		server.setHandler(handler);
		try {
			server.start();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Could not start http server", e);
			return false;
		}
		return true;
	}

	private static class NoLog implements org.eclipse.jetty.util.log.Logger {
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
		@Override public org.eclipse.jetty.util.log.Logger getLogger(String name) { return this; }
		@Override public void ignore(Throwable ignored) { }
	}

	public static void main(String[] args) {
		if (args.length < 3) {
			System.err.println("Arguments: <clientPath> <manifestPath> <serverIp> <serverPort>");
			return;
		}
		Logger logger = Logger.getLogger("Provider");
		try {
			FileHandler fileHandler = new FileHandler("skycraft-updater-provider.log");
			fileHandler.setFormatter(new SimpleFormatter());
			logger.addHandler(fileHandler);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Path clientPath;
		try {
			clientPath = Paths.get(args[0]);
		} catch (InvalidPathException e) {
			logger.log(Level.SEVERE, "Invalid client path", e);
			return;
		}
		Path manifestPath;
		try {
			manifestPath = Paths.get(args[1]);
		} catch (InvalidPathException e) {
			logger.log(Level.SEVERE, "Invalid manifest path", e);
			return;
		}
		String serverIp = args[2];
		int serverPort;
		try {
			serverPort = Integer.parseInt(args[3]);
		} catch (NumberFormatException e) {
			logger.log(Level.SEVERE, "Invalid server port", e);
			return;
		}

		Provider provider = new Provider(
			logger,
			clientPath,
			manifestPath,
			new InetSocketAddress(serverIp, serverPort)
		);
		provider.run();
	}
}
