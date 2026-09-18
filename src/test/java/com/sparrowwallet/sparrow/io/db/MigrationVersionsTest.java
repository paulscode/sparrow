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
 *
 * <p>The remedy has to be applied to upstream's file rather than this fork's, and the reason is the same
 * one that keeps this warning out of the .sql file itself: Flyway checksums every migration and validates
 * it against what is recorded, with validateOnMigrate left at its default. Editing V11 at all, even to add
 * a comment, changes its checksum and every existing wallet fails to open with a validation error.
 * V11__UnifiedSigHash.sql is frozen. This test is the note.
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
                "Two migrations claim one version. Flyway refuses to resolve that, so every wallet fails to open "
                        + "with \"Failed to open wallet file\" rather than failing here.\n\n"
                        + "THE FIX IS TO RENUMBER THE INCOMING UPSTREAM MIGRATION, NOT THIS FORK'S.\n"
                        + "V11__UnifiedSigHash.sql shipped in 2.5.5-blake2b.2 and is recorded as version 11 in every "
                        + "existing wallet's flyway_schema_history. Renaming it would make Flyway treat it as new and "
                        + "re-run it against a column that already exists. Upstream's new migration has been applied "
                        + "by nobody using this fork, so moving it to the next free version is safe and is the whole "
                        + "remedy.\n\n"
                        + "Clashes: " + clashes);
    }

    /**
     * An applied migration is frozen, comments included.
     *
     * <p>Flyway checksums every migration file and validates it against what the wallet recorded, with
     * validateOnMigrate left at its default. Change one byte of a migration that has already run and every
     * existing wallet fails to open with a validation error. That includes editing a comment, which is an
     * easy and entirely reasonable-looking thing to do, and this test exists because it was very nearly
     * done to V11 while writing the warning above it.
     *
     * <p>Pinned by content hash rather than by rule, because there is no rule: the file simply must not
     * change. A migration that has never shipped can be edited freely, and its hash is updated here.
     */
    @Test
    public void appliedMigrationsAreFrozen() throws Exception {
        //Every migration this fork has shipped. Upstream's V1 to V10 are here for the same reason: this
        //fork's users have applied them too, and an upstream merge that rewrites one would break them.
        Map<String, String> pinned = Map.ofEntries(
                Map.entry("V1__Initial.sql", "a6fe835d6ac1c7f227d290679315f46f82d2bfdf44fd3d092ad8ce2c01ace030"),
                Map.entry("V2__Whirlpool.sql", "b1c48e293ebe4469ec7a50cb84f688f2b60daa42881ed26c2ed4ac1d881ec04c"),
                Map.entry("V3__Account.sql", "3793d63d3200c9fed7262206a409ea0238f7781657e9e76cf223956302c6eec0"),
                Map.entry("V4__Watch.sql", "153ff3de679953b9a806ceb5fe33a46d757055d66d425ba9b0c4369c5626a3b3"),
                Map.entry("V5__DetachedLabel.sql", "054ccee3db31754d2daad1b8604e88506b1fac1113ed9331d29dc13cd3ae2812"),
                Map.entry("V6__PaymentCode.sql", "ae70ff9760690dee0186e2898320fed7dd6ffd377b440733deaeb19d37e1e454"),
                Map.entry("V7__AddressData.sql", "8d4ed4498187604108ec77b65155fd2a0c289ffbb2fc14a54ee57b5b0d5262bf"),
                Map.entry("V8__WalletConfig.sql", "9deec6ff43c67a9aa0dc555d94e6ebf662a234bec0f94320694c694497fcd28b"),
                Map.entry("V9__WalletTable.sql", "452b921d6780a1e3ad23f086a1b0088c87999e289930815cdc5c9f1d4860b3dd"),
                Map.entry("V10__SilentPayments.sql", "a5e754ba763a55af07985b097077b6be259ae2f4a77bb42c293e2f03578cbf81"),
                Map.entry("V11__UnifiedSigHash.sql", "232fa16c9a73c5fe3396ca13a82325790505a3e757a96a7e2d5783e40b89c11b"));

        Path sqlDir = Path.of("src/main/resources/com/sparrowwallet/sparrow/sql");
        List<String> changed = new ArrayList<>();
        for(Map.Entry<String, String> entry : pinned.entrySet()) {
            Path file = sqlDir.resolve(entry.getKey());
            if(!Files.exists(file)) {
                changed.add(entry.getKey() + " is gone, and a migration a wallet has applied cannot be removed");
                continue;
            }
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for(byte b : digest.digest(Files.readAllBytes(file))) {
                hex.append(String.format("%02x", b));
            }
            if(!hex.toString().equals(entry.getValue())) {
                changed.add(entry.getKey() + " changed (now " + hex + ")");
            }
        }

        Assertions.assertEquals(List.of(), changed,
                "A migration that has already run was edited. Flyway validates its checksum on every open, so this "
                        + "would fail every existing wallet with a validation error, not just new ones. Revert the "
                        + "file. If the change is genuinely needed, it has to be a new migration with a new version.");
    }
}
