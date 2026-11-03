import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** Java 8 command-line tool. Compile first; requires the MySQL JDBC driver at runtime. */
public final class EnsureIndexes {
    private static final class Index {
        private final String table;
        private final String name;
        private final List<String> columns;
        private Index(String table, String name, List<String> columns) {
            this.table = table; this.name = name; this.columns = columns;
        }
        private String table() { return table; }
        private String name() { return name; }
        private List<String> columns() { return columns; }
    }
    private static final List<Index> INDEXES = Arrays.asList(
        new Index("qc_task", "idx_task_project_time", Arrays.asList("project_id", "created_at", "id")),
        new Index("qc_record", "idx_record_project_time", Arrays.asList("kind", "project_id", "created_at", "id"))
    );
    private static String phase = "VALIDATION";

    public static void main(String[] arguments) {
        try {
            if (arguments.length != 0) throw new SafeFailure("No arguments are accepted. Use BIDCHECK_MYSQL_URL, BIDCHECK_MYSQL_USER and BIDCHECK_MYSQL_PASSWORD.");
            String url = required("BIDCHECK_MYSQL_URL");
            if (!url.startsWith("jdbc:mysql:")) throw new SafeFailure("BIDCHECK_MYSQL_URL must be a MySQL JDBC URL.");
            String user = required("BIDCHECK_MYSQL_USER");
            String password = required("BIDCHECK_MYSQL_PASSWORD");
            phase = "CONNECT";
            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                phase = "CHECK_DATABASE";
                if (!"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                    throw new SafeFailure("The connected database is not MySQL; no indexes were changed.");
                }
                String catalog = connection.getCatalog();
                if (catalog == null || catalog.trim().isEmpty()) throw new SafeFailure("Select an existing database in BIDCHECK_MYSQL_URL.");
                // Validate both targets before any DDL. Never create tables or replace an index.
                for (Index index : INDEXES) {
                    phase = "VALIDATE_" + index.table();
                    requireTable(connection, catalog, index.table());
                    existing(connection, catalog, index);
                }
                for (Index index : INDEXES) ensure(connection, catalog, index);
            }
            System.out.println("ENSURE_INDEXES_SUCCESS indexes=2");
        } catch (Exception failure) {
            // JDBC messages and stack traces may contain connection details. Emit only safe diagnostics.
            System.err.println("ENSURE_INDEXES_FAILED phase=" + phase + " type=" + failure.getClass().getSimpleName());
            if (failure instanceof SafeFailure) System.err.println(failure.getMessage());
            if (failure instanceof SQLException) {
                SQLException sql = (SQLException) failure;
                String state = sql.getSQLState();
                System.err.println("sqlState=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "unknown") + " vendorCode=" + sql.getErrorCode());
            }
            System.err.println("Existing records were not changed. DDL may already have completed; rerun this tool to verify indexes safely.");
            System.exit(1);
        }
    }

    private static String required(String name) throws SafeFailure {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) throw new SafeFailure("Missing required environment variable: " + name);
        return value;
    }

    private static void requireTable(Connection connection, String catalog, String table) throws SQLException, SafeFailure {
        try (ResultSet rows = connection.getMetaData().getTables(catalog, null, table, new String[]{"TABLE"})) {
            while (rows.next()) if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return;
        }
        throw new SafeFailure("Required table is missing: " + table + ". Create the application schema before running this tool.");
    }

    private static boolean existing(Connection connection, String catalog, Index index) throws SQLException, SafeFailure {
        TreeMap<Integer, String> columns = new TreeMap<>();
        boolean found = false;
        try (ResultSet rows = connection.getMetaData().getIndexInfo(catalog, null, index.table(), false, false)) {
            while (rows.next()) {
                if (rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic || !index.name().equalsIgnoreCase(rows.getString("INDEX_NAME"))) continue;
                found = true;
                String column = rows.getString("COLUMN_NAME");
                if (column == null) throw new SafeFailure("An incompatible index already uses name " + index.name() + "; no index was replaced.");
                columns.put(rows.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
            }
        }
        if (found && !new ArrayList<>(columns.values()).equals(index.columns())) {
            throw new SafeFailure("An index with different columns already uses name " + index.name() + "; no index was replaced.");
        }
        return found;
    }

    private static void ensure(Connection connection, String catalog, Index index) throws SQLException, SafeFailure {
        phase = "ENSURE_" + index.name();
        if (existing(connection, catalog, index)) {
            System.out.println("INDEX_VERIFIED " + index.name());
            return;
        }
        // Every SQL identifier is a fixed constant above, never derived from input or environment.
        String columns = String.join("`,`", index.columns());
        String sql = "CREATE INDEX `" + index.name() + "` ON `" + index.table() + "` (`" + columns + "`) ALGORITHM=INPLACE LOCK=NONE";
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(300);
            statement.executeUpdate(sql);
        } catch (SQLException failure) {
            // A concurrent invocation may create the same index after our metadata read.
            if (failure.getErrorCode() != 1061 || !existing(connection, catalog, index)) throw failure;
        }
        if (!existing(connection, catalog, index)) throw new SafeFailure("Index creation could not be verified: " + index.name());
        System.out.println("INDEX_VERIFIED " + index.name());
    }

    private static final class SafeFailure extends Exception {
        SafeFailure(String message) { super(message); }
    }
}
