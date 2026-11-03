import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Standalone Java 8 command-line tool. Never starts Spring or reads application configuration files. */
public class MigrateLocalData {
    private static final List<String> TABLES = Arrays.asList("qc_record", "qc_task");
    private static final long MAX_BYTES = 30L * 1024 * 1024;
    private static final String BUCKET = "bidcheck";
    private static String phase = "VALIDATION";

    public static void main(String[] arguments) {
        try {
            migrate(arguments);
        } catch (Exception failure) {
            // JDBC/SDK error messages can include URLs, credentials or SQL parameters. Never print them.
            System.err.println("MIGRATION FAILED phase=" + phase + " type=" + failure.getClass().getSimpleName());
            if (failure instanceof SafeFailure) System.err.println(failure.getMessage());
            System.err.println("No remote objects were deleted. If COMMIT was interrupted, verify target row counts before retrying.");
            System.exit(1);
        }
    }

    private static void migrate(String[] arguments) throws Exception {
        if (arguments.length < 2 || arguments.length > 3) throw new SafeFailure("Usage: MigrateLocalData <backed-up-H2-base-path> <source-files-directory> [schema-mysql.sql]");
        Path h2Base = Paths.get(arguments[0]).toAbsolutePath().normalize();
        if (h2Base.toString().contains(";") || h2Base.toString().contains("\n") || h2Base.toString().endsWith(".mv.db")) {
            throw new SafeFailure("Use the H2 database base path without .mv.db or JDBC options.");
        }
        if (!Files.isRegularFile(Paths.get(h2Base + ".mv.db"), LinkOption.NOFOLLOW_LINKS)) throw new SafeFailure("The backed-up H2 database file was not found.");
        Path sourceFiles = Paths.get(arguments[1]).toRealPath();
        if (!Files.isDirectory(sourceFiles)) throw new SafeFailure("The original files directory was not found.");
        String mysqlUrl = env("BIDCHECK_MYSQL_URL", false);
        if (!mysqlUrl.startsWith("jdbc:mysql:")) throw new SafeFailure("BIDCHECK_MYSQL_URL must be a MySQL JDBC URL.");
        String mysqlUser = env("BIDCHECK_MYSQL_USER", false);
        String mysqlPassword = env("BIDCHECK_MYSQL_PASSWORD", true);
        String endpoint = env("BIDCHECK_STORAGE_ENDPOINT", false);
        String accessKey = env("BIDCHECK_STORAGE_ACCESS_KEY", false);
        String secretKey = env("BIDCHECK_STORAGE_SECRET_KEY", false);
        String h2Url = "jdbc:h2:file:" + h2Base.toString().replace('\\', '/')
                + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;FILE_LOCK=NO;TRACE_LEVEL_FILE=0";

        phase = "OPEN_DATABASES";
        try (Connection source = DriverManager.getConnection(h2Url, "sa", "");
             Connection target = DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPassword);
             MinioClient minio = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build()) {
            source.setReadOnly(true);
            minio.setTimeout(5000, 60000, 60000);
            phase = "CHECK_TARGET_EMPTY";
            for (String table : TABLES) {
                if (tableExists(target, table) && count(target, table) != 0) throw new SafeFailure("Target qc_record or qc_task is not empty; migration refused without overwriting data.");
            }
            if (arguments.length == 3) createSchema(target, Paths.get(arguments[2]));
            for (String table : TABLES) {
                if (!tableExists(target, table)) throw new SafeFailure("Target schema is incomplete; create schema-mysql.sql or pass its path as the third argument.");
                requireTransactionalTable(target, table);
            }

            phase = "VALIDATE_ORIGINALS";
            Map<String, Original> originals = originals(source, sourceFiles);
            Map<String, Long> sourceCounts = counts(source);
            target.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            target.setAutoCommit(false);
            try {
                // Both applications should be stopped. Empty-range locks also block concurrent inserts during migration.
                for (String table : TABLES) {
                    try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery("SELECT id FROM " + table + " FOR UPDATE")) {
                        if (rows.next()) throw new SafeFailure("Target became non-empty; migration refused.");
                    }
                }
                phase = "COPY_DATABASE_ROWS";
                for (String table : TABLES) copyTable(source, target, table);
                if (!counts(target).equals(sourceCounts)) throw new SafeFailure("Database row counts differ before upload; migration rolled back.");

                phase = "UPLOAD_ORIGINALS";
                if (!originals.isEmpty()) ensureBucket(minio);
                int uploaded = 0;
                int reused = 0;
                for (Original original : originals.values()) {
                    String key = "documents/" + original.id() + "/original.bin";
                    if (remoteExists(minio, key)) {
                        if (!remoteDigest(minio, key).equals(original.sha256())) throw new SafeFailure("An existing remote UUID object differs from the source; it was not overwritten.");
                        reused++;
                    } else {
                        minio.uploadObject(UploadObjectArgs.builder().bucket(BUCKET).object(key)
                                .filename(original.path().toString()).contentType("application/octet-stream").build());
                        if (!remoteDigest(minio, key).equals(original.sha256())) throw new SafeFailure("Uploaded original failed SHA-256 verification; target database was not committed.");
                        uploaded++;
                    }
                }

                phase = "VERIFY_COUNTS";
                Map<String, Long> destinationCounts = counts(target);
                if (!sourceCounts.equals(destinationCounts)) throw new SafeFailure("Database row counts differ; migration rolled back.");
                phase = "COMMIT";
                target.commit();
                phase = "VERIFY_COMMITTED_COUNTS";
                destinationCounts = counts(target);
                if (!sourceCounts.equals(destinationCounts)) throw new SafeFailure("Committed row counts differ; stop the application and inspect the target before retrying.");
                System.out.println("MIGRATION SUCCESS sourceCount=" + sourceCounts + " destCount=" + destinationCounts
                        + " originals=" + originals.size() + " uploaded=" + uploaded + " reused=" + reused);
            } catch (Exception failure) {
                try { target.rollback(); } catch (Exception ignored) { }
                throw failure;
            }
        }
    }

    private static Map<String, Original> originals(Connection source, Path directory) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Original> result = new LinkedHashMap<>();
        try (PreparedStatement statement = source.prepareStatement("SELECT id,kind,body FROM qc_record WHERE kind IN ('document','upload') ORDER BY id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                JsonNode body = mapper.readTree(rows.getString("body"));
                String id = uuid(body.path("id").asText(""));
                String storedId = rows.getString("id");
                String kind = rows.getString("kind");
                if (("document".equals(kind) && !id.equalsIgnoreCase(storedId))
                        || ("upload".equals(kind) && !("upload:" + id).equalsIgnoreCase(storedId))) {
                    throw new SafeFailure("A registered original has inconsistent document identifiers.");
                }
                String expectedHash = body.path("sha256").asText("").toLowerCase(Locale.ROOT);
                if (!expectedHash.isEmpty() && !expectedHash.matches("[0-9a-f]{64}")) throw new SafeFailure("A registered original has an invalid SHA-256 value.");
                Original known = result.get(id);
                if (known != null) {
                    if (!expectedHash.isEmpty() && !known.sha256().equals(expectedHash)) throw new SafeFailure("Duplicate registered document identifiers have conflicting hashes.");
                    continue;
                }
                Path file = directory.resolve(id + ".bin").normalize();
                if (!file.getParent().equals(directory) || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new SafeFailure("A registered original is missing or is not a regular file; no database rows were committed.");
                }
                long size = Files.size(file);
                if (size < 1 || size > MAX_BYTES) throw new SafeFailure("A registered original is empty or exceeds the 30 MB limit.");
                String actualHash;
                try (InputStream input = Files.newInputStream(file)) { actualHash = digest(input); }
                if (!expectedHash.isEmpty() && !expectedHash.equals(actualHash)) throw new SafeFailure("A source original does not match its registered SHA-256 value.");
                result.put(id, new Original(id, file, actualHash));
            }
        }
        return result;
    }

    private static void copyTable(Connection source, Connection target, String table) throws Exception {
        checkTable(table);
        Map<String, Column> sourceColumns = columns(source, table);
        Map<String, Column> destinationColumns = columns(target, table);
        List<Column> common = new ArrayList<>();
        for (Column column : sourceColumns.values()) if (destinationColumns.containsKey(column.name())) common.add(column);
        if (common.isEmpty() || common.stream().noneMatch(column -> column.name().equals("id"))) throw new SafeFailure("A migration table has no compatible primary key columns.");
        String names = String.join(",", common.stream().map(Column::name).collect(Collectors.toList()));
        String placeholders = String.join(",", java.util.Collections.nCopies(common.size(), "?"));
        String destinationNames = String.join(",", common.stream().map(column -> "`" + column.name() + "`").collect(Collectors.toList()));
        try (Statement select = source.createStatement();
             ResultSet rows = select.executeQuery("SELECT " + names + " FROM " + table + " ORDER BY id");
             PreparedStatement insert = target.prepareStatement("INSERT INTO " + table + " (" + destinationNames + ") VALUES (" + placeholders + ")")) {
            int pending = 0;
            while (rows.next()) {
                for (int i = 0; i < common.size(); i++) {
                    int type = common.get(i).type();
                    Object value;
                    switch (type) {
                        case Types.CLOB: case Types.NCLOB: case Types.LONGVARCHAR: case Types.LONGNVARCHAR:
                        case Types.VARCHAR: case Types.NVARCHAR: case Types.CHAR: case Types.NCHAR:
                            value = rows.getString(i + 1); break;
                        default: value = rows.getObject(i + 1);
                    }
                    insert.setObject(i + 1, value);
                }
                insert.addBatch();
                if (++pending == 200) { insert.executeBatch(); pending = 0; }
            }
            if (pending != 0) insert.executeBatch();
        }
    }

    private static Map<String, Column> columns(Connection connection, String table) throws Exception {
        checkTable(table);
        Map<String, Column> columns = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                String name = metadata.getColumnName(i).toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z][a-z0-9_]*")) throw new SafeFailure("A migration table contains an unexpected column identifier.");
                columns.put(name, new Column(name, metadata.getColumnType(i)));
            }
        }
        return columns;
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        checkTable(table);
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            while (tables.next()) if (table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    private static void requireTransactionalTable(Connection connection, String table) throws Exception {
        checkTable(table);
        try (PreparedStatement statement = connection.prepareStatement("SELECT ENGINE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
            statement.setString(1, table);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !"InnoDB".equalsIgnoreCase(row.getString(1))) throw new SafeFailure("Target tables must use InnoDB so a failed migration can roll back safely.");
            }
        }
    }

    private static long count(Connection connection, String table) throws Exception {
        checkTable(table);
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Map<String, Long> counts(Connection connection) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) counts.put(table, count(connection, table));
        return counts;
    }

    private static void createSchema(Connection connection, Path schema) throws Exception {
        phase = "CREATE_EMPTY_SCHEMA";
        String text = new String(Files.readAllBytes(schema), StandardCharsets.UTF_8);
        if (text.length() > 32_000) throw new SafeFailure("Schema file is unexpectedly large.");
        for (String part : text.split(";")) {
            String sql = part.trim();
            if (sql.isEmpty()) continue;
            if (!sql.matches("(?is)^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+(qc_record|qc_task)\\s*\\(.*")
                    || sql.contains("--") || sql.contains("/*")) throw new SafeFailure("Schema may contain only CREATE TABLE IF NOT EXISTS for qc_record and qc_task.");
            try (Statement statement = connection.createStatement()) { statement.execute(sql); }
        }
    }

    private static void ensureBucket(MinioClient minio) throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            try { minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build()); }
            catch (ErrorResponseException failure) { if (!"BucketAlreadyOwnedByYou".equals(failure.errorResponse().code())) throw failure; }
        }
    }

    private static boolean remoteExists(MinioClient minio, String key) throws Exception {
        try {
            minio.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build());
            return true;
        } catch (ErrorResponseException failure) {
            if (Arrays.asList("NoSuchKey", "NoSuchObject").contains(failure.errorResponse().code())) return false;
            throw failure;
        }
    }

    private static String remoteDigest(MinioClient minio, String key) throws Exception {
        try (InputStream input = minio.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).build())) { return digest(input); }
    }

    private static String digest(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) throw new SafeFailure("An original exceeds the 30 MB limit.");
            digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static String uuid(String value) throws SafeFailure {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new SafeFailure("A registered document identifier is not a valid UUID.");
        return UUID.fromString(value).toString();
    }

    private static void checkTable(String table) throws SafeFailure {
        if (!TABLES.contains(table)) throw new SafeFailure("Migration table is not permitted.");
    }

    private static String env(String name, boolean allowEmpty) throws SafeFailure {
        String value = System.getenv(name);
        if (value == null || (!allowEmpty && value.trim().isEmpty())) throw new SafeFailure("Missing required environment variable " + name);
        return value;
    }

    private static final class Column {
        private final String name;
        private final int type;
        private Column(String name, int type) { this.name = name; this.type = type; }
        private String name() { return name; }
        private int type() { return type; }
    }
    private static final class Original {
        private final String id;
        private final Path path;
        private final String sha256;
        private Original(String id, Path path, String sha256) {
            this.id = id; this.path = path; this.sha256 = sha256;
        }
        private String id() { return id; }
        private Path path() { return path; }
        private String sha256() { return sha256; }
    }
    private static final class SafeFailure extends Exception { private SafeFailure(String message) { super(message); } }
}
