package io.quarkiverse.resiliencytesting.datasource;

import java.util.Optional;

import io.quarkiverse.resiliencytesting.spi.TargetName;

/**
 * The names of the two connections of a datasource, {@code datasource:<name>:jdbc} and
 * {@code datasource:<name>:reactive}. The Agroal and reactive datasource modules declare these targets and the
 * Hibernate modules resolve persistence units and repositories to them, so every one of them spells the names here.
 */
public final class DatasourceTargets {

    private static final String FAMILY = "datasource";

    private DatasourceTargets() {
    }

    public static TargetName jdbc(String datasource) {
        return TargetName.of(FAMILY, datasource, "jdbc");
    }

    public static TargetName reactive(String datasource) {
        return TargetName.of(FAMILY, datasource, "reactive");
    }

    /**
     * The reactive or the JDBC connection of a datasource.
     */
    public static TargetName of(String datasource, boolean reactive) {
        return reactive ? reactive(datasource) : jdbc(datasource);
    }

    /**
     * The datasource a JDBC or reactive connection name belongs to, the reverse of {@link #of}; empty for any other
     * name.
     */
    public static Optional<String> datasourceOf(TargetName name, boolean reactive) {
        if (name.instance() == 0 && FAMILY.equals(name.family()) && name.segments().size() == 2
                && name.segments().get(1).equals(reactive ? "reactive" : "jdbc")) {
            return Optional.of(name.segments().get(0));
        }
        return Optional.empty();
    }
}
