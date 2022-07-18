package org.skycraft.updater.translate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LanguagePackage {
	private final String language;
	private final Map<String, String> translateMap;

	public LanguagePackage(String language, Map<String, String> translateMap) {
		Objects.requireNonNull(language);
		Objects.requireNonNull(translateMap);
		this.language = language;
		this.translateMap = new HashMap<>(translateMap);
	}

	public String getLanguage() {
		return language;
	}

	public static Optional<LanguagePackage> loadLanguagePackage(Logger logger, String language, Reader reader) {
		Map<String, String> translateMap = new HashMap<>();
		try (BufferedReader bufferedReader = new BufferedReader(reader)) {
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				if (line.isEmpty() || line.startsWith("#")) continue;
				int separator = line.indexOf('=');
				if (separator == -1) {
					logger.log(Level.WARNING, "Invalid language file");
					return Optional.empty();
				}
				translateMap.put(line.substring(0, separator), line.substring(separator + 1));
			}
		} catch (IOException e) {
			logger.log(Level.WARNING, "Error occurred while loading language file", e);
			return Optional.empty();
		}
		LanguagePackage langPak = new LanguagePackage(language, translateMap);
		return Optional.of(langPak);
	}

	public String translateKey(String key, Map<String, String> parameters) {
		return Optional.ofNullable(translateMap.get(key)).map(message -> {
			for (Map.Entry<String, String> entry : parameters.entrySet()) {
				message = message.replace("%{" + entry.getKey() + "}", entry.getValue());
			}
			return message;
		}).orElse(key);
	}
}