/*
 * Copyright 2020 The Embulk project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.embulk.base.guess;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Optional;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigSource;
import org.embulk.spi.Buffer;
import org.embulk.spi.GuessPlugin;
import org.embulk.util.config.ConfigMapperFactory;
import org.embulk.util.file.ListFileInput;
import org.embulk.util.text.LineDelimiter;
import org.embulk.util.text.LineDecoder;
import org.embulk.util.text.Newline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a static method to guess a character set from {@link org.embulk.spi.Buffer}.
 *
 * <p>It reimplements {@code TextGuessPlugin} in {@code /embulk/guess_plugin.rb}.
 *
 * @see <a href="https://github.com/embulk/embulk/blob/v0.10.19/embulk-core/src/main/ruby/embulk/guess_plugin.rb">guess_plugin.rb</a>
 */
public abstract class TextGuessPlugin implements GuessPlugin {
    public TextGuessPlugin(final ConfigMapperFactory configMapperFactory) {
        this.configMapperFactory = configMapperFactory;
    }

    public abstract ConfigDiff guessText(ConfigSource config, String sampleText);

    /**
     * Guesses a character set from {@link org.embulk.spi.Buffer}.
     *
     * @param config  partial config to guess
     * @param sample  the byte sequence to be guessed
     * @param configMapperFactory  {@link org.embulk.util.config.ConfigMapperFactory} for a new {@link org.embulk.config.ConfigDiff}
     * @return {@link org.embulk.config.ConfigDiff} guessed
     */
    @Override
    public final Optional<String> guess(final ConfigSource config, final Buffer sample) {
        final ConfigSource parserConfig = config.getNestedOrGetEmpty("parser");
        if (parserConfig.getNestedOrGetEmpty("charset").isEmpty()) {
            return CharsetGuess.guess(sample, this.configMapperFactory);
        }

        final Optional<Charset> charset = getCharset(parserConfig);
        if (!charset.isPresent()) {
            return this.configMapperFactory.newConfigDiff();
        }

        final Optional<LineDelimiter> lineDelimiterRecognized = getLineDelimiterRecognized(parserConfig);
        if (!lineDelimiterRecognized.isPresent()) {
            return this.configMapperFactory.newConfigDiff();
        }

        final Optional<Newline> newline = getNewline(parserConfig);
        if (!newline.isPresent()) {
            return this.configMapperFactory.newConfigDiff();
        }

        final ArrayList<Buffer> listBuffer = new ArrayList<>();
        listBuffer.add(sample);
        final ArrayList<ArrayList<Buffer>> listListBuffer = new ArrayList<>();
        listListBuffer.add(listBuffer);
        final LineDecoder decoder = LineDecoder.of(new ListFileInput(listListBuffer), charset.get(), lineDelimiterRecognized.get());

        if (!decoder.nextFile()) {
            logger.warn("Found no FileInput unexpectedly.");
            return this.configMapperFactory.newConfigDiff();
        }

        final StringBuilder sampleText = new StringBuilder();
        boolean firstLine = true;
        while (true) {
            final String line = decoder.poll();
            if (line == null) {
                break;
            }

            if (firstLine) {
                firstLine = false;
            } else {
                sampleText.append(newline.get().getString());
            }
            sampleText.append(line);
        }

        return this.guessText(config, sampleText.toString());
    }

    private static Optional<Charset> getCharset(final ConfigSource parserConfig) {
        final String charsetString = parserConfig.get(String.class, "charset", "UTF-8");
        try {
            return Optional.of(Charset.forName(charsetString));
        } catch (final IllegalArgumentException ex) {
            logger.warn("Unrecognized charset: '" + charsetString + "'", ex);
            return Optional.empty();
        }
    }

    private static Optional<LineDelimiter> getLineDelimiterRecognized(final ConfigSource parserConfig) {
        final String lineDelimiterRecognizedString = parserConfig.get(String.class, "line_delimiter_recognized", null);

        if (lineDelimiterRecognizedString == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(LineDelimiter.valueOf(lineDelimiterRecognizedString));
        } catch (final IllegalArgumentException ex) {
            logger.warn("Unrecognized line delimiter: '" + lineDelimiterRecognizedString + "'", ex);
            return Optional.empty();
        }
    }

    private static Optional<Newline> getNewline(final ConfigSource parserConfig) {
        final String newlineString = parserConfig.get(String.class, "newline", "CRLF");

        if (newlineString == null) {
            return Optional.of(Newline.CRLF);
        }

        try {
            return Optional.of(Newline.valueOf(newlineString));
        } catch (final IllegalArgumentException ex) {
            logger.warn("Unrecognized newline: '" + newlineString + "'", ex);
            return Optional.empty();
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(TextGuessPlugin.class);

    private final ConfigMapperFactory configMapperFactory;
}
