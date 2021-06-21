package org.skycraft.updater.core;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.ProgressMonitor;
import javax.swing.SwingConstants;

public final class Patcher implements Runnable {
	private final Logger logger;
	private ProgressMonitor progressMonitor;
	private volatile boolean cancelled;
	private String serverIp;
	private int serverPort;
	private Path modsPath;
	private Map<Path, String> modsToRemove;
	private Map<Path, String> modsToUpdate;
	private Map<Path, Path> downloadedUpdates;

	public Patcher(Logger logger) {
		this.logger = logger;
	}

	@Override
	public void run() {
		JFrame mainFrame = new JFrame("SkyCraft Updater");
		mainFrame.setMinimumSize(new Dimension(400, 150));
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		mainFrame.setLocation(screenSize.width / 2 - mainFrame.getSize().width / 2, screenSize.height / 2 - mainFrame.getSize().height / 2);
		mainFrame.setLayout(new BorderLayout());
		mainFrame.add(new JLabel("SkyCraft updater is updating your mods...", SwingConstants.CENTER), BorderLayout.CENTER);
		mainFrame.pack();
		mainFrame.setVisible(true);
		mainFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		try {
			progressMonitor = new ProgressMonitor(mainFrame, "Updating mods...", "", 0, 100);
			if (!readInput()) {
				JOptionPane.showMessageDialog(mainFrame, "Could not read patcher input. Please report this bug!", "SkyCraft Updater", JOptionPane.ERROR_MESSAGE);
				return;
			}
			startMonitor();
			logger.log(Level.INFO, "Waiting 3 seconds for minecraft process to stop...");
			try {
				Thread.sleep(3000L);
			} catch (InterruptedException e) {
				if (cancelled) {
					logger.log(Level.INFO, "Update cancelled");
					return;
				}
			}
			if (!downloadUpdates()) {
				if (!cancelled) JOptionPane.showMessageDialog(mainFrame, "Mods update failed. Please report this bug!", "SkyCraft Updater", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			applyUpdates();
			logger.log(Level.INFO, "Mods successfully updated. Please restart your game!");
			JOptionPane.showMessageDialog(mainFrame, "Mods successfully updated. Please restart your game!", "SkyCraft Updater", JOptionPane.INFORMATION_MESSAGE);
		} finally {
			mainFrame.dispose();
		}
	}

	private boolean readInput() {
		try	(DataInputStream stream = new DataInputStream(System.in)) {
			serverIp = stream.readUTF();
			serverPort = stream.readInt();
			modsPath = Paths.get(stream.readUTF());
			int modsToRemoveSize = stream.readInt();
			modsToRemove = new HashMap<>();
			for (int i = 0; i < modsToRemoveSize; i++) {
				Path path = Paths.get(stream.readUTF());
				String hash = stream.readUTF();
				modsToRemove.put(path, hash);
			}
			int modsToUpdateSize = stream.readInt();
			modsToUpdate = new HashMap<>();
			for (int i = 0; i < modsToUpdateSize; i++) {
				Path path = Paths.get(stream.readUTF());
				String hash = stream.readUTF();
				modsToUpdate.put(path, hash);
			}
			return true;
		} catch (IOException | InvalidPathException e) {
			logger.log(Level.SEVERE, "Could not read patcher input");
			return false;
		}
	}

	private void startMonitor() {
		Thread patcherThread = Thread.currentThread();
		Thread monitorThread = new Thread(() -> {
			progressMonitor.setProgress(0);
			try {
				while (patcherThread.isAlive() || !progressMonitor.isCanceled())
					Thread.yield();
			} finally {
				if (progressMonitor.isCanceled()) {
					cancelled = true;
					patcherThread.interrupt();
				}
				progressMonitor.close();
			}
		});
		monitorThread.setDaemon(true);
		monitorThread.start();
	}

	private boolean downloadUpdates() {
		progressMonitor.setNote("Downloading updates (0/" + modsToUpdate.size() + ")...");
		logger.log(Level.INFO, "Downloading updates (0/" + modsToUpdate.size() + ")...");

		downloadedUpdates = new HashMap<>();
		try {
			for (Map.Entry<Path, String> entry : modsToUpdate.entrySet()) {
				Path downloadPath = Files.createTempFile(entry.getValue() + "_", ".jar");
				if (!downloadUpdate(downloadPath, entry.getValue())) return false;
				downloadedUpdates.put(entry.getKey(), downloadPath);
				progressMonitor.setProgress((int) ((float) downloadedUpdates.size() / (float) modsToUpdate.size() * 50));
				logger.log(Level.INFO, "Downloading updates (" + downloadedUpdates.size() + "/" + modsToUpdate.size() + ")...");
				progressMonitor.setNote("Downloading updates (" + downloadedUpdates.size() + "/" + modsToUpdate.size() + ")...");
			}
		}
		catch (IOException e) {
			logger.log(Level.SEVERE, "Could not download updates", e);
			return false;
		}

		return true;
	}

	private boolean downloadUpdate(Path downloadPath, String hash) {
		try (OutputStream out = Files.newOutputStream(downloadPath)) {
			URLConnection connection = new URL("http", serverIp, serverPort, "/download?hash=" + URLEncoder.encode(hash, "UTF-8")).openConnection();
			if (!(connection instanceof HttpURLConnection)) {
				logger.log(Level.SEVERE, "Could not download updates from server");
				return false;
			}
			HttpURLConnection http = (HttpURLConnection) connection;
			try {
				http.setRequestMethod("GET");
				http.connect();
				if (cancelled) {
					logger.log(Level.INFO, "Update cancelled");
					return false;
				}
				if (http.getResponseCode() != 200) {
					logger.log(Level.SEVERE, "Could not download updates from server, response code = " + http.getResponseCode());
					return false;
				}
				try (InputStream in = http.getInputStream()) {
					byte[] buffer = new byte[1024 * 64];
					int len;
					while ((len = in.read(buffer)) >= 0) {
						if (cancelled) {
							logger.log(Level.INFO, "Update cancelled");
							return false;
						}
						out.write(buffer, 0, len);
						if (cancelled) {
							logger.log(Level.INFO, "Update cancelled");
							return false;
						}
					}
				}
			} finally {
				http.disconnect();
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Could not download updates from server", e);
			return false;
		}
		return true;
	}

	private void applyUpdates() {
		progressMonitor.setNote("Applying updates (0/" + (modsToRemove.size() + downloadedUpdates.size()) + ")...");
		logger.log(Level.INFO, "Applying updates (0/" + (modsToRemove.size() + downloadedUpdates.size()) + ")...");

		int progress = 0;
		try {
			for (Map.Entry<Path, String> entry : modsToRemove.entrySet()) {
				Path path = modsPath.resolve(entry.getKey());
				logger.log(Level.INFO, "Removing " + entry.getKey() + "...");
				Files.deleteIfExists(path);
				progress++;
				progressMonitor.setProgress(50 + (int) ((float) progress / (float) (modsToRemove.size() + downloadedUpdates.size()) * 50));
				progressMonitor.setNote("Applying updates (" + progress + "/" + (modsToRemove.size() + downloadedUpdates.size()) + ")...");
				logger.log(Level.INFO, "Applying updates (" + progress + "/" + (modsToRemove.size() + downloadedUpdates.size()) + ")...");
			}

			for (Map.Entry<Path, Path> entry : downloadedUpdates.entrySet()) {
				Path path = modsPath.resolve(entry.getKey());
				Path downloadedPath = modsPath.resolve(entry.getValue());
				logger.log(Level.INFO, "Updating " + entry.getKey() + "...");
				Path parent = path.getParent();
				if (parent != null) Files.createDirectories(parent);
				Files.copy(downloadedPath, path, StandardCopyOption.REPLACE_EXISTING);
				progress++;
				progressMonitor.setProgress(50 + (int) ((float) progress / (float) (modsToRemove.size() + downloadedUpdates.size()) * 50));
				progressMonitor.setNote("Applying updates (" + progress + "/" + (modsToRemove.size() + downloadedUpdates.size()) + ")...");
				logger.log(Level.INFO, "Applying updates (" + progress + "/" + (modsToRemove.size() + downloadedUpdates.size()) + ")...");
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Could not apply updates to client", e);
		}
	}

	public static void main(String[] args) {
		Logger logger = Logger.getLogger("Patcher");

		Patcher patcher = new Patcher(logger);
		patcher.run();
	}
}
