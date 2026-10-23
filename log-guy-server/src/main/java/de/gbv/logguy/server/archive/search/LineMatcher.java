package de.gbv.logguy.server.archive.search;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;

/**
 * Finds the query in the text of a frame and checks single lines. Plain words are searched with
 * {@link String#indexOf(String, int)}, which is many times faster than a regular expression without a fixed start.
 */
sealed interface LineMatcher {

    /**
     * Prepares the search in the text of a frame, for one thread.
     *
     * @param text the lines of a frame
     * @return the search in this text
     */
    InText in(String text);

    /**
     * Whether a single line contains the query.
     *
     * @param line the line without line end
     * @return true on a match
     */
    boolean test(String line);

    /**
     * The search in one text.
     */
    interface InText {

        /**
         * Finds the next match.
         *
         * @param from position to start at
         * @return start of the match, -1 if there is none
         */
        int next(int from);

        /**
         * Whether a single line of the text contains the query.
         *
         * @param line the line without line end
         * @return true on a match
         */
        boolean test(String line);
    }

    /**
     * A regular expression.
     *
     * @param pattern the compiled expression
     */
    record Regex(Pattern pattern) implements LineMatcher {

        @Override
        public InText in(String text) {
            // RE2/J shares its matching machines per pattern, a pattern per frame lets the threads work in parallel,
            // measured 3.5 times faster with 8 threads
            Pattern own = Pattern.compile(pattern.pattern(), pattern.flags());
            Matcher matcher = own.matcher(text);
            return new InText() {

                @Override
                public int next(int from) {
                    return matcher.find(from) ? matcher.start() : -1;
                }

                @Override
                public boolean test(String line) {
                    return own.matcher(line).find();
                }
            };
        }

        @Override
        public boolean test(String line) {
            return pattern.matcher(line).find();
        }
    }

    /**
     * Plain words, optionally ignoring case. Case is folded character by character, so positions stay the same.
     *
     * @param words the words as given
     * @param ignoreCase whether case is ignored
     */
    record Literal(String words, boolean ignoreCase) implements LineMatcher {

        /**
         * Folds the words once.
         */
        public Literal {
            words = ignoreCase ? fold(words) : words;
        }

        @Override
        public InText in(String text) {
            String haystack = ignoreCase ? fold(text) : text;
            return new InText() {

                @Override
                public int next(int from) {
                    return haystack.indexOf(words, from);
                }

                @Override
                public boolean test(String line) {
                    return Literal.this.test(line);
                }
            };
        }

        @Override
        public boolean test(String line) {
            return (ignoreCase ? fold(line) : line).contains(words);
        }

        private static String fold(String text) {
            char[] chars = text.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                chars[i] = Character.toLowerCase(Character.toUpperCase(chars[i]));
            }
            return new String(chars);
        }
    }
}
