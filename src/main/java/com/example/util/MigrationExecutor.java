package com.example.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Execute les scripts SQL de migration via JDBC.
 * <ul>
 *     <li>chaque script est decoupe en instructions (separateur ';', commentaires '--' ignores) ;</li>
 *     <li>les versions deja appliquees sont memorisees dans la table schema_version, ce qui rend
 *     l'execution idempotente (on peut relancer l'application sans erreur) ;</li>
 *     <li>chaque script est execute dans une transaction (rollback en cas d'erreur, sauf DDL MySQL
 *     qui commite implicitement).</li>
 * </ul>
 */
public class MigrationExecutor {

    private static final Logger LOGGER = Logger.getLogger(MigrationExecutor.class.getName());

    /** Scripts de migration, dans l'ordre d'application. */
    public static final List<String> DEFAULT_SCRIPTS = Collections.unmodifiableList(Arrays.asList(
            "db/migration/v1_0_init_schema.sql",
            "db/migration/v2_0_add_description.sql"
    ));

    private static final Pattern VERSION_PATTERN = Pattern.compile("v(\\d+)_(\\d+)_[^/]*\\.sql$");

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MigrationExecutor(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /** Applique, dans l'ordre, les scripts pas encore appliques. Retourne le nombre de scripts executes. */
    public int migrate(List<String> scriptPaths) throws MigrationException {
        int applied = 0;
        for (String script : scriptPaths) {
            if (executeMigration(script)) {
                applied++;
            }
        }
        LOGGER.info("Migration terminee : " + applied + " script(s) applique(s), "
                + (scriptPaths.size() - applied) + " deja a jour.");
        return applied;
    }

    /**
     * Execute un script de migration s'il n'a pas deja ete applique.
     *
     * @return true si le script a ete execute, false s'il etait deja applique
     */
    public boolean executeMigration(String scriptPath) throws MigrationException {
        String version = versionOf(scriptPath);
        List<String> statements = splitStatements(readScript(scriptPath));

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            ensureVersionTable(conn);
            if (isApplied(conn, version)) {
                LOGGER.fine("Migration " + version + " deja appliquee, ignoree : " + scriptPath);
                return false;
            }

            LOGGER.info("Application de la migration " + version + " (" + statements.size() + " instruction(s)) : " + scriptPath);
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            String current = null;
            try (Statement stmt = conn.createStatement()) {
                for (String sql : statements) {
                    current = sql;
                    stmt.execute(sql);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO schema_version (version, script) VALUES (?, ?)")) {
                    ps.setString(1, version);
                    ps.setString(2, scriptPath);
                    ps.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw new MigrationException("Echec de la migration " + version + " sur l'instruction :\n" + current, e);
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new MigrationException("Erreur JDBC pendant la migration " + scriptPath + " : " + e.getMessage(), e);
        }
    }

    /** Liste des versions deja appliquees (ordre d'application). */
    public List<String> appliedVersions() throws MigrationException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            ensureVersionTable(conn);
            List<String> versions = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT version FROM schema_version ORDER BY applied_at, version")) {
                while (rs.next()) {
                    versions.add(rs.getString(1));
                }
            }
            return versions;
        } catch (SQLException e) {
            throw new MigrationException("Lecture de schema_version impossible", e);
        }
    }

    private void ensureVersionTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + " version VARCHAR(20) NOT NULL PRIMARY KEY,"
                    + " script VARCHAR(255) NOT NULL,"
                    + " applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private boolean isApplied(Connection conn, String version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM schema_version WHERE version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private String readScript(String scriptPath) throws MigrationException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(scriptPath);
        if (is == null) {
            throw new MigrationException("Script de migration introuvable dans le classpath : " + scriptPath, null);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sql = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sql.append(line).append('\n');
            }
            return sql.toString();
        } catch (IOException e) {
            throw new MigrationException("Lecture impossible du script " + scriptPath, e);
        }
    }

    /** "db/migration/v2_0_add_description.sql" -> "2.0" */
    static String versionOf(String scriptPath) {
        Matcher m = VERSION_PATTERN.matcher(scriptPath);
        if (!m.find()) {
            throw new IllegalArgumentException("Nom de script invalide (attendu vX_Y_nom.sql) : " + scriptPath);
        }
        return m.group(1) + "." + m.group(2);
    }

    /**
     * Decoupe un script en instructions SQL. Ignore les commentaires "--" (hors chaines)
     * et ne coupe pas sur un ';' situe dans une chaine entre apostrophes.
     */
    public static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        int i = 0;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (!inString && c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i < script.length() && script.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '\'') {
                inString = !inString;
            }
            if (c == ';' && !inString) {
                addIfNotBlank(statements, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
            i++;
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder sb) {
        String sql = sb.toString().trim();
        if (!sql.isEmpty()) {
            statements.add(sql);
        }
    }

    /** Erreur de migration (checked : l'appelant doit decider d'arreter ou non l'application). */
    public static class MigrationException extends Exception {
        public MigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
