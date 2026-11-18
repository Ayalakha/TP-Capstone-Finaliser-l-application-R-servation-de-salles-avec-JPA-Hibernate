package com.example.util;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;

/**
 * Point unique de configuration de la connexion.
 * <p>
 * Par defaut : H2 en memoire. Pour MySQL :
 * <pre>
 * -Ddb.url=jdbc:mysql://localhost:3306/reservation?createDatabaseIfNotExist=true
 * -Ddb.user=root -Ddb.password=password
 * </pre>
 * Toute propriete systeme commencant par "hibernate." est aussi transmise
 * (ex. -Dhibernate.show_sql=true).
 */
public final class JpaUtil {

    public static final String PERSISTENCE_UNIT = "ReservationPU";
    public static final String DEFAULT_URL = "jdbc:h2:mem:reservation;DB_CLOSE_DELAY=-1;MODE=MySQL";

    private JpaUtil() {
    }

    public static String getJdbcUrl() {
        return System.getProperty("db.url", DEFAULT_URL);
    }

    public static String getUser() {
        return System.getProperty("db.user", "sa");
    }

    public static String getPassword() {
        return System.getProperty("db.password", "");
    }

    public static EntityManagerFactory createEntityManagerFactory() {
        return createEntityManagerFactory(getJdbcUrl(), getUser(), getPassword());
    }

    public static EntityManagerFactory createEntityManagerFactory(String url, String user, String password) {
        Map<String, Object> props = new HashMap<>();
        props.put("javax.persistence.jdbc.url", url);
        props.put("javax.persistence.jdbc.user", user);
        props.put("javax.persistence.jdbc.password", password);
        props.put("javax.persistence.jdbc.driver", driverFor(url));
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("hibernate.")) {
                props.put(key, System.getProperty(key));
            }
        }
        return Persistence.createEntityManagerFactory(PERSISTENCE_UNIT, props);
    }

    static String driverFor(String url) {
        if (url.startsWith("jdbc:mysql:")) {
            return "com.mysql.cj.jdbc.Driver";
        }
        if (url.startsWith("jdbc:postgresql:")) {
            return "org.postgresql.Driver";
        }
        return "org.h2.Driver";
    }
}
