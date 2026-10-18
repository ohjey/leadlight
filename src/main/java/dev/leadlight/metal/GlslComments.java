package dev.leadlight.metal;

/**
 * Takes the comments out of GLSL text the way the compiler would, for Metallum, which strips them
 * before it compiles a stage.
 * <p>
 * Metallum does that with two regular expressions, block comments first and line comments after,
 * and a pair of expressions cannot tell a comment from text that only looks like one: a line comment
 * holding {@code /*} swallows the code up to the next {@code *&#47;} anywhere below it, and a pack's
 * file is full of commented-out code that does exactly that. Packs read through Iris never meet the
 * question because the driver's own preprocessor reads them. This is a scan of the text in one pass,
 * so each comment ends where the compiler would end it.
 * <p>
 * A block comment leaves its newlines behind, so the compiler's line numbers still match the text
 * that was handed in, and a line comment ending on a backslash goes on onto the next line, as it
 * does in the compiler, which joins lines before it reads comments.
 */
public final class GlslComments {

	private GlslComments() {
	}

	public static String strip(String source) {
		StringBuilder out = new StringBuilder(source.length());
		int length = source.length();
		int i = 0;
		while (i < length) {
			char c = source.charAt(i);
			char next = i + 1 < length ? source.charAt(i + 1) : '\0';
			if (c == '/' && next == '/') {
				i += 2;
				while (i < length) {
					char d = source.charAt(i);
					if (d == '\n') {
						boolean continued = i > 0 && (source.charAt(i - 1) == '\\'
								|| (source.charAt(i - 1) == '\r' && i > 1 && source.charAt(i - 2) == '\\'));
						if (!continued) {
							break;
						}
						out.append('\n');
					}
					i++;
				}
				out.append(' ');
			} else if (c == '/' && next == '*') {
				i += 2;
				while (i < length && !(source.charAt(i) == '*' && i + 1 < length && source.charAt(i + 1) == '/')) {
					if (source.charAt(i) == '\n') {
						out.append('\n');
					}
					i++;
				}
				i = Math.min(length, i + 2);
				out.append(' ');
			} else {
				out.append(c);
				i++;
			}
		}

		return out.toString();
	}
}
