package org.skycraft.updater.translate;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class TranslateMessage {
	private final String language;
	private final String key;
	private final Map<String, String> parameters;

	private TranslateMessage(String language, String key) {
		Objects.requireNonNull(key);
		this.language = language;
		this.key = key;
		this.parameters = new HashMap<>();
	}

	public static TranslateMessage of(String key) {
		return new TranslateMessage(null, key);
	}

	public static TranslateMessage of(String language, String key) {
		return new TranslateMessage(language, key);
	}

	public TranslateMessage with(String parameter, Object value) {
		parameters.put(parameter, Objects.toString(value));
		return this;
	}

	public TranslateMessage with(String parameter, String value) {
		parameters.put(parameter, value);
		return this;
	}

	public String translate(TranslateManager translateManager) {
		if (language == null) {
			return translateManager.translateKey(key, parameters);
		} else {
			return translateManager.translateKey(language, key, parameters);
		}
	}
}
