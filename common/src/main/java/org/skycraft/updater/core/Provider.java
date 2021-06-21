package org.skycraft.updater.core;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;

public final class Provider implements Runnable, Closeable {
	private static final String DEFAULT_CATEGORY = "mods";

	private final Logger logger;
	private final Path clientPath;
	private final Path manifestPath;
	private final InetSocketAddress address;
	private Map<String, ManifestEntry> manifest;
	private Map<String, ManifestCategory> categories;
	private String precachedManifest;
	private Server server;

	public Provider(Logger logger, Path clientPath, Path manifestPath, InetSocketAddress address) {
		this.logger = logger;
		this.clientPath = clientPath;
		this.manifestPath = manifestPath;
		this.address = address;
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
		manifest = null;
		categories = null;
		precachedManifest = null;
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
		Map<String, ManifestEntry> manifest = new HashMap<>();
		StringWriter out = new StringWriter();
		try (JsonReader reader = new JsonReader(new InputStreamReader(Files.newInputStream(manifestPath), StandardCharsets.UTF_8)); JsonWriter writer = new JsonWriter(out)) {
			writer.setIndent("    ");
			reader.beginArray();
			writer.beginArray();
			while (reader.hasNext()) {
				reader.beginObject();
				writer.beginObject();
				String category = null;
				Path path = null;
				while (reader.hasNext()) {
					switch (reader.nextName()) {
					case "category":
						category = reader.nextString();
						writer.name("category").value(category);
						break;
					case "path":
						try {
							path = Paths.get(reader.nextString());
						} catch (InvalidPathException e) {
							logger.log(Level.WARNING, "Illegal manifest format", e);
							return false;
						}
						writer.name("path").value(path.toString());
						break;
					default:
						logger.log(Level.WARNING, "Illegal manifest format");
						return false;
					}
				}
				if (category == null || path == null) {
					logger.log(Level.WARNING, "Illegal manifest format");
					return false;
				}
				ManifestEntry entry = new ManifestEntry(category, path);
				manifest.put(category, entry);
				writer.endObject();
				reader.endObject();
			}
			writer.endArray();
			reader.endArray();
		} catch (IOException | IllegalStateException e) {
			logger.log(Level.SEVERE, "Could not load provider manifest", e);
			return false;
		}
		this.manifest = manifest;
		this.precachedManifest = out.toString();
		logger.log(Level.INFO, "Manifest successfully precached");
		return true;
	}

	private boolean precacheHashes() {
		logger.log(Level.INFO, "Precaching manifest hashes...");
		Map<String, ManifestCategory> categories = new HashMap<>();
		for (Map.Entry<String, ManifestEntry> entry : manifest.entrySet()) {
			HashMap<String, Path> files = new HashMap<>();
			StringWriter out = new StringWriter();
			try (JsonWriter writer = new JsonWriter(out)) {
				writer.setIndent("    ");
				writer.beginArray();
				Path categoryPath = clientPath.resolve(entry.getValue().path);
				if (Files.exists(categoryPath)) {
					for (Path path : (Iterable<? extends Path>) Files.walk(categoryPath)::iterator) {
						if (!Files.isRegularFile(path)) continue;
						String hash;
						try (InputStream stream = Files.newInputStream(path)) {
							hash = DigestUtils.md5Hex(stream);
						} catch (IOException e) {
							logger.log(Level.WARNING, "Could not calculate file hash", e);
							continue;
						}
						files.put(hash, path);
						writer.beginObject();
						writer.name("path").value(categoryPath.relativize(path).toString());
						writer.name("hash").value(hash);
						writer.endObject();
						logger.log(Level.INFO, "Found file \"" + path + "\" with hash \"" + hash + "\"");
					}
				}
				writer.endArray();
			} catch (IOException e) {
				logger.log(Level.SEVERE, "Error occurred while precaching file hashes", e);
				return false;
			}
			ManifestCategory category = new ManifestCategory(entry.getValue(), files, out.toString());
			categories.put(entry.getKey(), category);
		}
		this.categories = categories;
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
		handler.addServlet(new ServletHolder(new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
				if (precachedManifest == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				logger.log(Level.INFO, "Sending manifest to client " + req.getRemoteAddr());
				resp.setContentType("application/json; charset=utf-8");
				try (PrintWriter out = resp.getWriter()) {
					out.write(precachedManifest);
				}
			}
		}), "/manifest");
		handler.addServlet(new ServletHolder(new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
				if (categories == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				String categoryName = Optional.ofNullable(req.getParameter("category")).orElse(DEFAULT_CATEGORY);
				ManifestCategory category = categories.get(categoryName);
				if (category == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				logger.log(Level.INFO, "Sending category \"" + category.entry.category + "\" hashes to client " + req.getRemoteAddr());
				resp.setContentType("application/json; charset=utf-8");
				try (PrintWriter out = resp.getWriter()) {
					out.write(category.precachedHashes);
				}
			}
		}), "/hashes");
		handler.addServlet(new ServletHolder(new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
				if (categories == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				String categoryName = Optional.ofNullable(req.getParameter("category")).orElse(DEFAULT_CATEGORY);
				ManifestCategory category = categories.get(categoryName);
				if (category == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				String hash = req.getParameter("hash");
				if (hash == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				Path path = category.files.get(hash);
				if (path == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
				Path categoryPath = clientPath.resolve(category.entry.path);
				logger.log(Level.INFO, "Sending category \"" + category.entry.category + "\" file \"" + categoryPath.relativize(path) + "\" with hash \"" + hash + "\" to client " + req.getRemoteAddr());
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

	public static final class ManifestEntry {
		private final String category;
		private final Path path;

		public ManifestEntry(String category, Path path) {
			this.category = category;
			this.path = path;
		}
	}

	public static final class ManifestCategory {
		private final ManifestEntry entry;
		private final Map<String, Path> files;
		private final String precachedHashes;

		public ManifestCategory(ManifestEntry entry, Map<String, Path> files, String precachedHashes) {
			this.entry = entry;
			this.files = files;
			this.precachedHashes = precachedHashes;
		}
	}
}
