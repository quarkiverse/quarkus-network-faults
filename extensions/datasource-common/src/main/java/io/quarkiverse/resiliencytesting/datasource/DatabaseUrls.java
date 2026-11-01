package io.quarkiverse.resiliencytesting.datasource;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkiverse.resiliencytesting.spi.AddressRewriter;
import io.quarkiverse.resiliencytesting.spi.AddressUtil;
import io.quarkiverse.resiliencytesting.spi.Upstream;

/**
 * What the JDBC and the reactive URLs of a datasource have in common: the port each kind of database listens on
 * unless told otherwise, and Oracle's {@code oracle:thin:@host:port/service} form, which is not a URL.
 */
public final class DatabaseUrls {

    /**
     * Each database's own port, by the name a URL gives the database.
     */
    public static final Map<String, Integer> DEFAULT_PORTS = Map.ofEntries(
            Map.entry("postgresql", 5432), Map.entry("postgres", 5432), Map.entry("pgsql", 5432),
            Map.entry("mysql", 3306), Map.entry("mariadb", 3306),
            Map.entry("sqlserver", 1433), Map.entry("mssql", 1433),
            Map.entry("db2", 50000), Map.entry("h2", 9092), Map.entry("oracle", 1521));

    private static final Pattern ORACLE = Pattern.compile("^((?:jdbc:)?oracle:thin:@(?://)?)([^/:,]+)(?::(\\d+))?(.*)$",
            Pattern.CASE_INSENSITIVE);

    private DatabaseUrls() {
    }

    /**
     * Oracle's {@code oracle:thin:@host:port/service} form, with or without the {@code jdbc:} prefix, pointed at a
     * proxy; null when {@code url} is not in that form.
     */
    public static String rewriteOracle(String url, AddressRewriter.Context context) {
        Matcher m = ORACLE.matcher(url);
        if (!m.matches()) {
            return null;
        }
        int port = m.group(3) != null ? Integer.parseInt(m.group(3)) : DEFAULT_PORTS.get("oracle");
        return m.group(1) + AddressUtil.PROXY_HOST + ":" + context.proxyPort(Upstream.of(m.group(2), port))
                + m.group(4);
    }
}
