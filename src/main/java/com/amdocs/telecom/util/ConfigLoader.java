package com.amdocs.telecom.util;

import com.amdocs.telecom.exception.ConfigurationException;
import com.amdocs.telecom.exception.ErrorCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Single access point for {@code application.properties}.
 *
 * <p>Implemented with the initialisation-on-demand holder idiom, which gives a
 * lazily created, thread safe singleton without any synchronisation cost on
 * reads. This is the first of the design patterns the case study asks for.</p>
 *
 * <p>A file sitting next to the jar wins over the copy bundled on the
 * classpath, so an operator can retune a deployment without a rebuild.</p>
 */
public final class ConfigLoader {

    private static final String CONFIG_FILE_NAME = "application.properties";
    private static final String PLACEHOLDER = "CHANGE_ME";

    private final Properties properties;
    private final String sourceDescription;

    private ConfigLoader() {
        Properties loaded = new Properties();
        String source;

        File external = new File(CONFIG_FILE_NAME);
        if (external.isFile()) {
            source = external.getAbsolutePath();
            readFrom(loaded, external);
        } else {
            source = "classpath:" + CONFIG_FILE_NAME;
            readFromClasspath(loaded);
        }

        this.properties = loaded;
        this.sourceDescription = source;
    }

    /** Holder is not loaded until {@link #getInstance()} is first called. */
    private static final class Holder {
        private static final ConfigLoader INSTANCE = new ConfigLoader();
    }

    public static ConfigLoader getInstance() {
        return Holder.INSTANCE;
    }

    private static void readFrom(Properties target, File file) {
        try (InputStream input = new FileInputStream(file);
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            target.load(reader);
        } catch (IOException cause) {
            throw new ConfigurationException(ErrorCode.CONFIG_LOAD_FAILED,
                    "Could not read configuration file " + file.getAbsolutePath(), cause);
        }
    }

    private static void readFromClasspath(Properties target) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(CONFIG_FILE_NAME)) {
            if (input == null) {
                throw new ConfigurationException(ErrorCode.CONFIG_NOT_FOUND,
                        CONFIG_FILE_NAME + " was not found on the classpath or in the working directory");
            }
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                target.load(reader);
            }
        } catch (IOException cause) {
            throw new ConfigurationException(ErrorCode.CONFIG_LOAD_FAILED,
                    "Could not read " + CONFIG_FILE_NAME + " from the classpath", cause);
        }
    }

    /**
     * Where the active configuration was loaded from, for the startup banner.
     */
    public String getSourceDescription() {
        return sourceDescription;
    }

    /**
     * Returns a property, or empty when absent or blank.
     */
    public Optional<String> find(String key) {
        String value = System.getenv(key.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_'));
        if (value == null) value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    /**
     * Returns a property, or the supplied fallback when absent.
     */
    public String getString(String key, String defaultValue) {
        return find(key).orElse(defaultValue);
    }

    /**
     * Returns a property that the application cannot start without.
     *
     * @throws ConfigurationException when the key is missing or blank
     */
    public String getRequired(String key) {
        return find(key).orElseThrow(() -> new ConfigurationException(
                ErrorCode.CONFIG_MISSING_PROPERTY,
                "Required property '" + key + "' is missing from " + sourceDescription));
    }

    public int getInt(String key, int defaultValue) {
        Optional<String> raw = find(key);
        if (!raw.isPresent()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.get());
        } catch (NumberFormatException cause) {
            throw new ConfigurationException(ErrorCode.CONFIG_INVALID_VALUE,
                    "Property '" + key + "' must be a whole number but was '" + raw.get() + "'", cause);
        }
    }

    public long getLong(String key, long defaultValue) {
        Optional<String> raw = find(key);
        if (!raw.isPresent()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.get());
        } catch (NumberFormatException cause) {
            throw new ConfigurationException(ErrorCode.CONFIG_INVALID_VALUE,
                    "Property '" + key + "' must be a whole number but was '" + raw.get() + "'", cause);
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return find(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    /**
     * Whether a property still holds the shipped placeholder value, which the
     * startup checks use to give a precise instruction instead of a bare
     * connection failure.
     */
    public boolean isPlaceholder(String key) {
        return find(key).map(PLACEHOLDER::equalsIgnoreCase).orElse(false);
    }

    /**
     * Sorted view of every configured key, used by the diagnostics banner.
     */
    public Set<String> keys() {
        return Collections.unmodifiableSet(new TreeSet<String>(properties.stringPropertyNames()));
    }
}
