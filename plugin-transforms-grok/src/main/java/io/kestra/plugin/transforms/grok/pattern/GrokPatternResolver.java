package io.kestra.plugin.transforms.grok.pattern;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class GrokPatternResolver {

    private static final Logger LOG = LoggerFactory.getLogger(GrokPatternResolver.class);

    private static final Pattern DEFINITION = Pattern.compile("^(?<NAME>[A-Z0-9_]+)(\\s)*(?<PATTERN>.*)");

    private static final String PATTERNS_PATH = "patterns";
    private static final String TAB = "\t ";
    private static final String NEWLINE = "\n";
    private static final String SPACE = " ";

    private final Logger logger;
    private final Map<String, String> definitions;

    /**
     * Creates a new {@link GrokPatternResolver} instance.
     */
    public GrokPatternResolver() {
        this(LOG, Collections.emptyMap(), Collections.emptyList());
    }

    /**
     * Creates a new {@link GrokPatternResolver} instance.
     *
     * @param patternDefinitions a list of pattern-definitions
     * @param patternsDir        a list pattern directories to load.
     */
    public GrokPatternResolver(final Logger logger,
                               final Map<String, String> patternDefinitions,
                               final List<File> patternsDir) {
        this.logger = logger;
        this.definitions = new LinkedHashMap<>();
        loadPredefinedPatterns();
        loadUserDefinedPatterns(patternsDir);
        this.definitions.putAll(patternDefinitions);
    }

    private void loadUserDefinedPatterns(final Collection<File> patternsDir) {
        if (patternsDir != null) {
            for (File dir : patternsDir) {
                if (!dir.exists() || !dir.canRead()) {
                    logger.error(
                        "Can't read pattern from user directory {} - directory doesn't exist or is readable",
                        patternsDir);
                    return;
                }

                if (!dir.isDirectory()) {
                    logger.error("Can't read pattern from {} - not a directory", patternsDir);
                    return;
                }

                try {
                    List<Path> paths = Files.list(dir.toPath()).toList();
                    loadPatternDefinitions(paths);
                } catch (IOException e) {
                    logger.error("Unexpected error occurred while reading user defined patterns", e);
                }
            }
        }
    }

    private void loadPredefinedPatterns() {
        logger.debug("Looking for pre-defined patterns definitions from : {}", PATTERNS_PATH);
        try {
            ClassLoader cl = getClassLoader();
            URL url = cl.getResource(PATTERNS_PATH);
            if (url != null) {
                final String protocol = url.getProtocol();
                if (protocol != null && protocol.equals("jar")) {
                    try (FileSystem fs = getFileSystemFor(url)) {
                        final List<Path> paths = Files.walk(fs.getPath(PATTERNS_PATH)).filter(Files::isRegularFile).toList();
                        loadPatternDefinitions(paths);
                    }
                } else {
                    final List<Path> paths = Files.list(Paths.get(url.toURI())).toList();
                    loadPatternDefinitions(paths);
                }
            } else {
                logger.error("Failed to load pre-defined patterns definitions : {}", PATTERNS_PATH);
            }
        } catch (IOException | URISyntaxException e) {
            logger.error("Unexpected error occurred while reading pre-defined patterns", e);
        }
    }

    private FileSystem getFileSystemFor(final URL url) throws URISyntaxException, IOException {
        FileSystem fs;
        try {
            fs = FileSystems.getFileSystem(url.toURI());
        } catch (FileSystemNotFoundException e) {
            fs = FileSystems.newFileSystem(url.toURI(), Collections.emptyMap());
        }
        return fs;
    }

    private void loadPatternDefinitions(final List<Path> paths) throws IOException {
        for (final Path path : paths) {
            Map<String, String> patternDefinitions = readPatternDefinitionsFrom(path);
            definitions.putAll(patternDefinitions);
            logger.debug("Loaded patterns definitions from : {}", path.toUri());
        }
    }

    private static ClassLoader getClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            return GrokPatternResolver.class.getClassLoader();
        }
        return cl;
    }

    public String resolve(final String syntax) {
        if (!definitions.containsKey(syntax)) {
            throw new GrokException("No pattern definition found for syntax : " + syntax);
        }

        return definitions.get(syntax);
    }

    private Map<String, String> readPatternDefinitionsFrom(final Path path) throws GrokException, IOException {
        final InputStream is = Files.newInputStream(path, StandardOpenOption.READ);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            List<String> definitions = br.lines().collect(Collectors.toList());
            return readPatterns(definitions);
        } catch (IOException e) {
            throw new GrokException("Unexpected error while reading pattern definition : " + path);
        }
    }

    private Map<String, String> readPatterns(final Collection<String> definitions) {
        return definitions.stream()
            .map(s -> {
                KeyValue<String, String> result = null;
                Matcher matcher = DEFINITION.matcher(s);
                if (matcher.matches()) {
                    String name = matcher.group("NAME");
                    String pattern = matcher.group("PATTERN");
                    result = new KeyValue<>(name, pattern);
                }
                return result;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(KeyValue::key, KeyValue::value));
    }

    @VisibleForTesting
    void print() {
        StringBuilder sb = new StringBuilder();
        sb.append("Defined pattern definitions list : \n");
        definitions.forEach((k, v) -> {
            sb.append(TAB)
                .append(k)
                .append(SPACE)
                .append(v)
                .append(NEWLINE);

        });
        LOG.info("{}", sb);

    }

    @VisibleForTesting
    boolean isEmpty() {
        return definitions.isEmpty();
    }

    @VisibleForTesting
    public Map<String, String> definitions() {
        return definitions;
    }

    private record KeyValue<K, V>(
        K key,
        V value
    ) {

        KeyValue {
            Objects.requireNonNull(key, "key can't be null");
            Objects.requireNonNull(value, "value can't be null");
        }
    }
}