package com.example.test;

import com.example.util.MigrationExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MigrationExecutorTest {

    private static final String BASE = "migration_test";

    private String url;
    private MigrationExecutor executor;

    @BeforeEach
    public void setUp() {
        url = TestSupport.jdbcUrl(BASE);
        executor = new MigrationExecutor(url, "sa", "");
    }

    @AfterEach
    public void tearDown() {
        TestSupport.detruireBase(null, BASE);
    }

    @Test
    public void appliqueLesMigrationsDansLOrdre() throws Exception {
        assertEquals(2, executor.migrate(MigrationExecutor.DEFAULT_SCRIPTS));
        assertEquals(Arrays.asList("1.0", "2.0"), executor.appliedVersions());

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            assertTrue(colonneExiste(c, "SALLES", "DESCRIPTION"), "v2.0 ajoute salles.description");
            assertTrue(indexExiste(c, "UTILISATEURS", "IDX_UTILISATEUR_EMAIL"), "v2.0 ajoute l'index unique sur email");
        }
    }

    @Test
    public void migrationIdempotente() throws Exception {
        executor.migrate(MigrationExecutor.DEFAULT_SCRIPTS);
        assertEquals(0, executor.migrate(MigrationExecutor.DEFAULT_SCRIPTS), "relancer ne doit rien re-appliquer");
        assertEquals(2, executor.appliedVersions().size());
    }

    @Test
    public void migrationIncrementale() throws Exception {
        assertTrue(executor.executeMigration("db/migration/v1_0_init_schema.sql"));
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            assertFalse(colonneExiste(c, "SALLES", "DESCRIPTION"));
        }
        assertEquals(1, executor.migrate(MigrationExecutor.DEFAULT_SCRIPTS), "seule la v2.0 reste a appliquer");
    }

    @Test
    public void echecAnnuleLaMigration() throws Exception {
        executor.executeMigration("db/migration/v1_0_init_schema.sql");
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement()) {
            // provoque l'echec de la v2.0 : la colonne existe deja
            st.execute("ALTER TABLE salles ADD COLUMN description VARCHAR(255)");
        }
        assertThrows(MigrationExecutor.MigrationException.class,
                () -> executor.executeMigration("db/migration/v2_0_add_description.sql"));
        assertEquals(Collections.singletonList("1.0"), executor.appliedVersions(), "la v2.0 n'est pas marquee appliquee");
    }

    @Test
    public void scriptIntrouvable() {
        MigrationExecutor.MigrationException ex = assertThrows(MigrationExecutor.MigrationException.class,
                () -> executor.executeMigration("db/migration/v9_9_inexistant.sql"));
        assertTrue(ex.getMessage().contains("introuvable"));
    }

    @Test
    public void decoupageDesInstructions() {
        String script = "-- commentaire ; ignore\n"
                + "CREATE TABLE t (a VARCHAR(10));\n"
                + "INSERT INTO t VALUES ('x;y'); -- fin de ligne\n"
                + "\n"
                + "UPDATE t SET a = 'z'";
        List<String> instructions = MigrationExecutor.splitStatements(script);
        assertEquals(3, instructions.size());
        assertEquals("INSERT INTO t VALUES ('x;y')", instructions.get(1));
    }

    private static boolean colonneExiste(Connection c, String table, String colonne) throws Exception {
        try (ResultSet rs = c.getMetaData().getColumns(null, null, table, colonne)) {
            return rs.next();
        }
    }

    private static boolean indexExiste(Connection c, String table, String index) throws Exception {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getIndexInfo(null, null, table, true, false)) {
            while (rs.next()) {
                if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
