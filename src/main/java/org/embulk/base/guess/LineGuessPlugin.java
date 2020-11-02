package org.embulk.guess;

import java.util.ArrayList;
import java.util.Collections;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigSource;
import org.embulk.spi.Buffer;
import org.embulk.spi.Exec;
import org.embulk.spi.GuessPlugin;
import org.embulk.spi.util.LineDecoder;
import org.embulk.spi.util.ListFileInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class LineGuessPlugin implements GuessPlugin {
    @Override
    public final ConfigDiff guess(final ConfigSource config, final Buffer sample) {
        final ConfigSource parserConfig = config.getNestedOrGetEmpty("parser");
        if (parserConfig.getNestedOrGetEmpty("charset").isEmpty()) {
            return (new CharsetGuessPlugin()).guess(config, sample);
        }

        if (parserConfig.getNestedOrGetEmpty("newline").isEmpty()) {
            return (new NewlineGuessPlugin()).guess(config, sample);
        }

        final LineDecoder.DecoderTask parserTask;
        try {
            parserTask = parserConfig.loadConfig(LineDecoder.DecoderTask.class);
        } catch (final Exception ex) {
            logger.warn(ex.getMessage(), ex);
            return Exec.newConfigDiff();
        }

        final ArrayList<Buffer> listBuffer = new ArrayList<>();
        listBuffer.add(sample);
        final ArrayList<ArrayList<Buffer>> listListBuffer = new ArrayList<>();
        listListBuffer.add(listBuffer);

        final LineDecoder decoder = new LineDecoder(new ListFileInput(listListBuffer), parserTask);

        // TODO: Unsafe to call Buffer#array().
        final boolean endsWithNewline = endsWith(sample.array(), parserTask.getNewline().getString());

        final ArrayList<String> sampleLines = new ArrayList<>();
        while (decoder.nextFile()) {  // TODO: Confirm decoder contains only one, and stop looping.
            while (true) {
                final String line = decoder.poll();
                if (line == null) {
                    break;
                }
                sampleLines.add(line);
            }
            if (!endsWithNewline && !sampleLines.isEmpty()) {
                sampleLines.remove(sampleLines.size() - 1);  // last line is partial
            }
        }
        return this.guessLines(config, Collections.unmodifiableList(sampleLines));
    }

    public abstract ConfigDiff guessLines(final ConfigSource config, final Iterable<String> sampleText);

    private static boolean endsWith(final byte[] array, final String target) {
        // TODO: Implement.
        return false;
    }

    private static final Logger logger = LoggerFactory.getLogger(TextGuessPlugin.class);
}
