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
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.ProgressMonitor;
import javax.swing.SwingConstants;
import org.skycraft.updater.translate.TranslateManager;
import org.skycraft.updater.translate.TranslateMessage;

public final class Patcher implements Runnable {
	private final Logger logger;
	private final TranslateManager translateManager;
	private ProgressMonitor progressMonitor;
	private volatile boolean cancelled;
	private String serverIp;
	private int serverPort;
	private Path filesPath;
	private Map<Path, String> filesToRemove;
	private Map<Path, String> filesToUpdate;
	private Map<Path, Path> downloadedUpdates;

	public Patcher(Logger logger) {
		this.logger = logger;
		this.translateManager = TranslateManager.loadInternalLanguagePackages(logger);
	}

	@Override
	public void run() {
		JFrame mainFrame = new JFrame(TranslateMessage.of("patcher.main-frame.title").translate(translateManager));
		mainFrame.setMinimumSize(new Dimension(400, 150));
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		mainFrame.setLocation(screenSize.width / 2 - mainFrame.getSize().width / 2, screenSize.height / 2 - mainFrame.getSize().height / 2);
		mainFrame.setLayout(new BorderLayout());
		mainFrame.add(new JLabel(TranslateMessage.of("patcher.main-frame.message").translate(translateManager), SwingConstants.CENTER), BorderLayout.CENTER);
		mainFrame.pack();
		mainFrame.setVisible(true);
		mainFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		try {
			progressMonitor = new ProgressMonitor(
				mainFrame,
				TranslateMessage.of("patcher.main-frame.progress.message").translate(translateManager),
				"", 0, 100
			);
			if (!readInput()) {
				JOptionPane.showMessageDialog(
					mainFrame,
					TranslateMessage.of("patcher.input-read-error-dialog.message").translate(translateManager),
					TranslateMessage.of("patcher.input-read-error-dialog.title").translate(translateManager),
					JOptionPane.ERROR_MESSAGE
				);
				return;
			}
			startMonitor();
			logger.log(Level.INFO, TranslateMessage.of("patcher.log.waiting-minecraft").translate(translateManager));
			try {
				Thread.sleep(3000L);
			} catch (InterruptedException e) {
				if (cancelled) {
					logger.log(Level.INFO, TranslateMessage.of("patcher.log.update-cancelled").translate(translateManager));
					return;
				}
			}
			if (!downloadUpdates()) {
				if (!cancelled) JOptionPane.showMessageDialog(
					mainFrame,
					TranslateMessage.of("patcher.download-error-dialog.message").translate(translateManager),
					TranslateMessage.of("patcher.download-error-dialog.title").translate(translateManager),
					JOptionPane.INFORMATION_MESSAGE
				);
				return;
			}
			if (!applyUpdates()) {
				if (!cancelled) JOptionPane.showMessageDialog(
					mainFrame,
					TranslateMessage.of("patcher.apply-update-error-dialog.message").translate(translateManager),
					TranslateMessage.of("patcher.apply-update-error-dialog.title").translate(translateManager),
					JOptionPane.INFORMATION_MESSAGE
				);
				return;
			}
			logger.log(Level.INFO, TranslateMessage.of("patcher.updated-dialog.message").translate(translateManager));
			JOptionPane.showMessageDialog(
				mainFrame,
				TranslateMessage.of("patcher.updated-dialog.message").translate(translateManager),
				TranslateMessage.of("patcher.updated-dialog.title").translate(translateManager),
				JOptionPane.INFORMATION_MESSAGE
			);
		} finally {
			mainFrame.dispose();
		}
	}

	private boolean readInput() {
		try	(DataInputStream stream = new DataInputStream(System.in)) {
			serverIp = stream.readUTF();
			serverPort = stream.readInt();
			filesPath = Paths.get(stream.readUTF());
			int filesToRemoveSize = stream.readInt();
			filesToRemove = new HashMap<>();
			for (int i = 0; i < filesToRemoveSize; i++) {
				Path path = Paths.get(stream.readUTF());
				String hash = stream.readUTF();
				filesToRemove.put(path, hash);
			}
			int filesToUpdateSize = stream.readInt();
			filesToUpdate = new HashMap<>();
			for (int i = 0; i < filesToUpdateSize; i++) {
				Path path = Paths.get(stream.readUTF());
				String hash = stream.readUTF();
				filesToUpdate.put(path, hash);
			}
			return true;
		} catch (IOException | InvalidPathException e) {
			logger.log(Level.SEVERE, TranslateMessage.of("patcher.input-read-error-dialog.message").translate(translateManager), e);
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
		downloadedUpdates = new HashMap<>();
		progressMonitor.setNote(TranslateMessage.of("patcher.main-frame.progress.downloading-note")
			.with("progress", downloadedUpdates.size())
			.with("total", filesToUpdate.size())
			.translate(translateManager)
		);
		logger.log(Level.INFO, TranslateMessage.of("patcher.main-frame.progress.downloading-note")
			.with("progress", downloadedUpdates.size())
			.with("total", filesToUpdate.size())
			.translate(translateManager)
		);
		try {
			for (Map.Entry<Path, String> entry : filesToUpdate.entrySet()) {
				Path downloadPath = Files.createTempFile(entry.getValue() + "_", ".jar");
				if (!downloadUpdate(downloadPath, entry.getValue())) return false;
				downloadedUpdates.put(entry.getKey(), downloadPath);
				progressMonitor.setProgress((int) ((float) downloadedUpdates.size() / (float) filesToUpdate.size() * 50));
				logger.log(Level.INFO, TranslateMessage.of("patcher.main-frame.progress.downloading-note")
					.with("progress", downloadedUpdates.size())
					.with("total", filesToUpdate.size())
					.translate(translateManager)
				);
				progressMonitor.setNote(TranslateMessage.of("patcher.main-frame.progress.downloading-note")
					.with("progress", downloadedUpdates.size())
					.with("total", filesToUpdate.size())
					.translate(translateManager)
				);
			}
		}
		catch (IOException e) {
			logger.log(Level.SEVERE, TranslateMessage.of("patcher.log.download-failed").translate(translateManager), e);
			return false;
		}
		return true;
	}

	private boolean downloadUpdate(Path downloadPath, String hash) {
		try (OutputStream out = Files.newOutputStream(downloadPath)) {
			URLConnection connection = new URL("http", serverIp, serverPort, "/download?hash=" + URLEncoder.encode(hash, "UTF-8")).openConnection();
			if (!(connection instanceof HttpURLConnection)) {
				logger.log(Level.SEVERE, TranslateMessage.of("patcher.log.download-failed").translate(translateManager));
				return false;
			}
			HttpURLConnection http = (HttpURLConnection) connection;
			try {
				http.setRequestMethod("GET");
				http.connect();
				if (cancelled) {
					logger.log(Level.INFO, TranslateMessage.of("patcher.log.update-cancelled").translate(translateManager));
					return false;
				}
				if (http.getResponseCode() != 200) {
					logger.log(Level.SEVERE, TranslateMessage.of("patcher.log.download-failed-with-code")
						.with("code", http.getResponseCode())
						.translate(translateManager)
					);
					return false;
				}
				try (InputStream in = http.getInputStream()) {
					byte[] buffer = new byte[1024 * 64];
					int len;
					while ((len = in.read(buffer)) >= 0) {
						if (cancelled) {
							logger.log(Level.INFO, TranslateMessage.of("patcher.log.update-cancelled").translate(translateManager));
							return false;
						}
						out.write(buffer, 0, len);
						if (cancelled) {
							logger.log(Level.INFO, TranslateMessage.of("patcher.log.update-cancelled").translate(translateManager));
							return false;
						}
					}
				}
			} finally {
				http.disconnect();
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, TranslateMessage.of("patcher.log.download-failed").translate(translateManager), e);
			return false;
		}
		return true;
	}

	private boolean applyUpdates() {
		progressMonitor.setNote(TranslateMessage.of("patcher.main-frame.progress.apply-update-note")
			.with("progress", 0)
			.with("total", filesToRemove.size() + downloadedUpdates.size())
			.translate(translateManager)
		);
		logger.log(Level.INFO, TranslateMessage.of("patcher.main-frame.progress.apply-update-note")
			.with("progress", 0)
			.with("total", filesToRemove.size() + downloadedUpdates.size())
			.translate(translateManager)
		);

		int progress = 0;
		try {
			for (Map.Entry<Path, String> entry : filesToRemove.entrySet()) {
				Path path = filesPath.resolve(entry.getKey());
				logger.log(Level.INFO, TranslateMessage.of("patcher.log.removing-file")
						.with("file", entry.getKey())
						.translate(translateManager)
				);
				Files.deleteIfExists(path);
				progress++;
				progressMonitor.setProgress(50 + (int) ((float) progress / (float) (filesToRemove.size() + downloadedUpdates.size()) * 50));
				progressMonitor.setNote(TranslateMessage.of("patcher.main-frame.progress.apply-update-note")
					.with("progress", progress)
					.with("total", filesToRemove.size() + downloadedUpdates.size())
					.translate(translateManager)
				);
				logger.log(Level.INFO, TranslateMessage.of("patcher.main-frame.progress.apply-update-note")
					.with("progress", progress)
					.with("total", filesToRemove.size() + downloadedUpdates.size())
					.translate(translateManager)
				);
			}

			for (Map.Entry<Path, Path> entry : downloadedUpdates.entrySet()) {
				Path path = filesPath.resolve(entry.getKey());
				Path downloadedPath = filesPath.resolve(entry.getValue());
				logger.log(Level.INFO, TranslateMessage.of("patcher.log.updating-file")
						.with("file", entry.getKey())
						.translate(translateManager)
				);
				Path parent = path.getParent();
				if (parent != null) Files.createDirectories(parent);
				Files.copy(downloadedPath, path, StandardCopyOption.REPLACE_EXISTING);
				progress++;
				progressMonitor.setProgress(50 + (int) ((float) progress / (float) (filesToRemove.size() + downloadedUpdates.size()) * 50));
				progressMonitor.setNote(TranslateMessage.of("patcher.main-frame.progress.apply-update-note")
					.with("progress", progress)
					.with("total", filesToRemove.size() + downloadedUpdates.size())
					.translate(translateManager)
				);
				logger.log(Level.INFO, TranslateMessage.of("patcher.main-frame.progress.apply-update-note")
					.with("progress", progress)
					.with("total", filesToRemove.size() + downloadedUpdates.size())
					.translate(translateManager)
				);
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, TranslateMessage.of("patcher.log.apply-update-failed").translate(translateManager), e);
			return false;
		}
		return true;
	}

	public static void main(String[] args) {
		Logger logger = Logger.getLogger("Patcher");
		try {
			FileHandler fileHandler = new FileHandler("skycraft-updater-patcher.log");
			fileHandler.setFormatter(new SimpleFormatter());
			logger.addHandler(fileHandler);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		Patcher patcher = new Patcher(logger);
		patcher.run();
	}
}
