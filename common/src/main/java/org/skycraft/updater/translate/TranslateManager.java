package org.skycraft.updater.translate;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TranslateManager {
	private static final String DEFAULT_LANGUAGE_PACKAGE = "zh-CN";
	private static final String[] INTERNAL_LANGUAGE_PACKAGES = new String[] { "en-US", "zh-CN" };

	private final String defaultLanguage;
	private final Map<String, LanguagePackage> langPakMap;

	public TranslateManager(String defaultLanguage, Map<String, LanguagePackage> langPakMap) {
		Objects.requireNonNull(defaultLanguage);
		Objects.requireNonNull(langPakMap);
		this.defaultLanguage = defaultLanguage;
		this.langPakMap = new HashMap<>(langPakMap);
	}

	public Optional<LanguagePackage> getLanguagePackage(String language) {
		return Optional.ofNullable(Optional.ofNullable(langPakMap.get(language)).orElseGet(() -> langPakMap.get(defaultLanguage)));
	}

	public String translateKey(String key) {
		return translateKey(key, Collections.emptyMap());
	}

	public String translateKey(String key, String... parameters) {
		Map<String, String> map = new HashMap<>();
		for (int i = 0; i < parameters.length; i++) {
			map.put(Integer.toString(i + 1), parameters[i]);
		}
		return translateKey(Locale.getDefault().toLanguageTag(), key, map);
	}

	public String translateKey(String key, Map<String, String> parameters) {
		return translateKey(Locale.getDefault().toLanguageTag(), key, parameters);
	}

	public String translateKey(String language, String key, Map<String, String> parameters) {
		return getLanguagePackage(language).map(langPak -> langPak.translateKey(key, parameters)).orElse(key);
	}

	public static TranslateManager loadInternalLanguagePackages(Logger logger) {
		return loadInternalLanguagePackages(DEFAULT_LANGUAGE_PACKAGE, logger);
	}

	public static TranslateManager loadInternalLanguagePackages(String defaultLanguage, Logger logger) {
		Map<String, LanguagePackage> langPakMap = new HashMap<>();
		for (String language : INTERNAL_LANGUAGE_PACKAGES) {
			InputStream inputStream = TranslateManager.class.getClassLoader().getResourceAsStream("lang/" + language + ".lang");
			if (inputStream != null) {
				try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
					LanguagePackage.loadLanguagePackage(logger, language, reader).ifPresent(langPak -> langPakMap.put(language, langPak));
				} catch (IOException e) {
					logger.log(Level.WARNING, "Error occurred while loading language file", e);
				}
			} else {
				logger.log(Level.WARNING, "Could not find internal language file");
			}
		}
		return new TranslateManager(defaultLanguage, langPakMap);
	}
}