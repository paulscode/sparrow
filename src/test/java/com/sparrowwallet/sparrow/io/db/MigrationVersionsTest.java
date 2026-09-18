package com.sparrowwallet.sparrow.io.db;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two migrations may not claim the same version, and this fork is the reason that can happen.
 *
 * <p>Upstream's set ends at V10 and this fork adds V11 for the keystore's unified sighash mark. The numbers
 * are a shared sequence with no coordination between the two projects, so the first upstream release that
 * adds a V11 of its own produces two files claiming it.
 *
 * <p>Nothing about that is visible in a merge: neither file conflicts, because they have different names.
 * It fails when the wallet database is opened, for every user at once, which is the worst place to find it
 * and the reason this is checked at build time instead.
 */
public class MigrationVersionsTest {
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    public void noTwoMigrationsClaimTheSameVersion() throws Exception {
        Path sqlDir = Path.of("src/main/resources/com/sparrowwallet/sparrow/sql");
        Assertions.assertTrue(Files.isDirectory(sqlDir),
                "expected to run from the project directory, but " + sqlDir.toAbsolutePath() + " is not there");

        Map<Integer, List<String>> byVersion = new LinkedHashMap<>();
        try(var files = Files.list(sqlDir)) {
            for(Path file : files.sorted().toList()) {
                Matcher matcher = VERSIONED.matcher(file.getFileName().toString());
                if(matcher.matches()) {
                    byVersion.computeIfAbsent(Integer.parseInt(matcher.group(1)), v -> new ArrayList<>())
                            .add(file.getFileName().toString());
                }
            }
        }

        Assertions.assertFalse(byVersion.isEmpty(), "no migrations found, so this check is not looking where it thinks");

        List<String> clashes = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + ": " + e.getValue())
                .toList();

        Assertions.assertEquals(List.of(), clashes,
                "two migrations claim one version, which fails when the wallet database is opened rather than here. "
                        + "This fork's own migrations have to be renumbered above whatever upstream now ends at.");
    }
}
