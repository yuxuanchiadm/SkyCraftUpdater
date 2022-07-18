package org.skycraft.updater.utils;

import java.util.PrimitiveIterator;
import java.util.regex.Pattern;

public final class PathUtils {
	private PathUtils() {

	}

	public static boolean matchWildcard(String path, String pattern) {
		StringBuilder plainString = new StringBuilder();
		StringBuilder regexBuilder = new StringBuilder();
		Parser patternParser = new Parser(pattern);
		boolean first = true;
		while (patternParser.hasNext()) {
			try {
				patternParser.next();
				if (first) {
					if (patternParser.isSlash()) {
						continue;
					} else {
						regexBuilder.append(".*");
					}
				}
				if (!patternParser.isPlain() && plainString.length() > 0) {
					regexBuilder.append(Pattern.quote(plainString.toString()));
					plainString.setLength(0);
				}
				if (patternParser.isWildCard()) {
					regexBuilder.append("[^/\\\\]");
				} else if (patternParser.isAsterisk()) {
					if (!first && patternParser.peek() == '*') {
						patternParser.next();
						regexBuilder.append(".*");
					} else {
						regexBuilder.append("[^/\\\\]*");
					}
				} else if (patternParser.isSlash()) {
					if (patternParser.hasNext()) {
						regexBuilder.append("[/\\\\]");
					} else {
						regexBuilder.append("[/\\\\].*");
					}
				} else {
					plainString.appendCodePoint(patternParser.get());
				}
			} finally {
				first = false;
			}
		}
		if (plainString.length() > 0) {
			regexBuilder.append(Pattern.quote(plainString.toString()));
			plainString.setLength(0);
			regexBuilder.append("([/\\\\].*)?");
		}
		return Pattern.compile(regexBuilder.toString()).matcher(path).matches();
	}

	private static final class Parser {
		PrimitiveIterator.OfInt input;
		int current;
		boolean needNext;
		int next;

		Parser(String input) {
			this.input = input.codePoints().iterator();
			this.current = -1;
			this.needNext = true;
			this.next = -1;
		}

		void checkNext() {
			if (needNext) {
				next = input.hasNext() ? input.next() : -1;
				needNext = false;
			}
		}

		boolean hasNext() {
			checkNext();
			return next >= 0;
		}

		boolean next() {
			checkNext();
			current = next;
			needNext = true;
			return current != -1;
		}

		int peek() {
			checkNext();
			return next;
		}

		int get() {
			return current;
		}

		boolean isPlain() {
			return !isAsterisk() && !isWildCard() && !isSlash();
		}

		boolean isAsterisk() {
			return current == '*';
		}

		boolean isWildCard() {
			return current == '?';
		}

		boolean isSlash() {
			return current == '/' || current == '\\';
		}
	}
}
