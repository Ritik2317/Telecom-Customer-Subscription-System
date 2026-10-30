package com.amdocs.telecom.util;

import com.amdocs.telecom.exception.DataAccessException;
import com.amdocs.telecom.exception.ErrorCode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@code .sql} file from the classpath and executes it over JDBC.
 *
 * <p>JDBC has no notion of the {@code DELIMITER} directive that MySQL clients
 * use to define routines whose bodies contain semicolons, so this runner
 * implements it. Statements are split by a character level scan that is aware
 * of string literals, quoted identifiers and both comment styles, which means
 * a semicolon inside a literal is never mistaken for a statement terminator.</p>
 */
public final class SqlScriptRunner {

    private static final String DEFAULT_DELIMITER = ";";

    private final Connection connection;

    public SqlScriptRunner(Connection connection) {
        this.connection = connection;
    }

    /**
     * Loads, substitutes and runs a script.
     *
     * @param resourcePath  classpath location, for example {@code db/01_schema.sql}
     * @param substitutions tokens to replace, keyed without the {@code ${}} wrapper
     * @return how many statements were executed
     */
    public int runScript(String resourcePath, Map<String, String> substitutions) {
        String script = readResource(resourcePath);
        script = substitute(script, substitutions);

        List<String> statements = parse(script);
        int executed = 0;

        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                try {
                    statement.execute(sql);
                    executed++;
                } catch (SQLException cause) {
                    throw new DataAccessException(ErrorCode.DB_QUERY_FAILED,
                            "Failed in " + resourcePath + " at statement " + (executed + 1)
                            + "; SQL state " + cause.getSQLState()
                                    + ", vendor code " + cause.getErrorCode());
                }
            }
        } catch (SQLException cause) {
            throw new DataAccessException(ErrorCode.DB_QUERY_FAILED,
                    "Could not create a statement for " + resourcePath, cause);
        }

        AppLogger.info(SqlScriptRunner.class,
                "Executed " + executed + " statement(s) from " + resourcePath);
        return executed;
    }

    private static String readResource(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new DataAccessException(ErrorCode.DB_QUERY_FAILED,
                        "SQL script '" + resourcePath + "' was not found on the classpath");
            }
            StringBuilder builder = new StringBuilder(8192);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            }
            return builder.toString();
        } catch (IOException cause) {
            throw new DataAccessException(ErrorCode.DB_QUERY_FAILED,
                    "Could not read SQL script '" + resourcePath + "'", cause);
        }
    }

    private static String substitute(String script, Map<String, String> substitutions) {
        if (substitutions == null || substitutions.isEmpty()) {
            return script;
        }
        String result = script;
        for (Map.Entry<String, String> entry : substitutions.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * Splits a script into executable statements.
     *
     * <p>{@code DELIMITER} directives are honoured, comments are stripped, and
     * the scanner tracks quoting so that a terminator inside a literal is
     * treated as ordinary text.</p>
     */
    static List<String> parse(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder(512);

        String delimiter = DEFAULT_DELIMITER;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        boolean inBlockComment = false;

        String[] lines = script.split("\n", -1);

        for (String rawLine : lines) {
            String line = rawLine;

            // A DELIMITER directive is only meaningful between statements and
            // is never part of one.
            if (!inBlockComment && !inSingleQuote && !inDoubleQuote && !inBacktick
                    && current.toString().trim().isEmpty()) {
                String trimmed = line.trim();
                if (trimmed.regionMatches(true, 0, "DELIMITER", 0, 9)) {
                    String candidate = trimmed.substring(9).trim();
                    if (!candidate.isEmpty()) {
                        delimiter = candidate;
                        current.setLength(0);
                        continue;
                    }
                }
            }

            int index = 0;
            while (index < line.length()) {
                char character = line.charAt(index);

                if (inBlockComment) {
                    if (character == '*' && index + 1 < line.length() && line.charAt(index + 1) == '/') {
                        inBlockComment = false;
                        index += 2;
                    } else {
                        index++;
                    }
                    continue;
                }

                if (inSingleQuote) {
                    current.append(character);
                    if (character == '\\' && index + 1 < line.length()) {
                        current.append(line.charAt(index + 1));
                        index += 2;
                        continue;
                    }
                    if (character == '\'') {
                        inSingleQuote = false;
                    }
                    index++;
                    continue;
                }

                if (inDoubleQuote) {
                    current.append(character);
                    if (character == '\\' && index + 1 < line.length()) {
                        current.append(line.charAt(index + 1));
                        index += 2;
                        continue;
                    }
                    if (character == '"') {
                        inDoubleQuote = false;
                    }
                    index++;
                    continue;
                }

                if (inBacktick) {
                    current.append(character);
                    if (character == '`') {
                        inBacktick = false;
                    }
                    index++;
                    continue;
                }

                // Outside any quoted region: comments first.
                if (character == '-' && index + 1 < line.length() && line.charAt(index + 1) == '-') {
                    boolean properComment = index + 2 >= line.length()
                            || Character.isWhitespace(line.charAt(index + 2));
                    if (properComment) {
                        break;
                    }
                }
                if (character == '#') {
                    break;
                }
                if (character == '/' && index + 1 < line.length() && line.charAt(index + 1) == '*') {
                    inBlockComment = true;
                    index += 2;
                    continue;
                }

                // Statement terminator.
                if (line.startsWith(delimiter, index)) {
                    addStatement(statements, current);
                    index += delimiter.length();
                    continue;
                }

                if (character == '\'') {
                    inSingleQuote = true;
                } else if (character == '"') {
                    inDoubleQuote = true;
                } else if (character == '`') {
                    inBacktick = true;
                }

                current.append(character);
                index++;
            }

            current.append('\n');
        }

        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder buffer) {
        String candidate = buffer.toString().trim();
        if (!candidate.isEmpty()) {
            statements.add(candidate);
        }
        buffer.setLength(0);
    }

    private static String abbreviate(String sql) {
        String flattened = sql.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 160 ? flattened : flattened.substring(0, 157) + "...";
    }
}
