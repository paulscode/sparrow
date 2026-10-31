package com.sparrowwallet.sparrow.io;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Upstream Sparrow's wallets are copied rather than opened in place: opening migrates the file to this build's schema,
 * which upstream may not be able to read once it ships a migration of its own with the same number.
 */
public class UpstreamWalletsTest {
    @TempDir
    Path home;

    @Test
    public void recognisesFilesUnderUpstreamsHomeOnly() throws IOException {
        Path upstream = Files.createDirectories(home.resolve(".sparrow/wallets"));
        Path ours = Files.createDirectories(home.resolve(".sparrowblake2b/wallets"));
        List<Path> upstreamHomes = List.of(home.resolve(".sparrow"));

        Assertions.assertTrue(UpstreamWallets.isUpstreamFile(upstream.resolve("hot.mv.db").toFile(), upstreamHomes));
        Assertions.assertTrue(UpstreamWallets.isUpstreamFile(upstream.toFile(), upstreamHomes), "the folder itself");
        Assertions.assertFalse(UpstreamWallets.isUpstreamFile(ours.resolve("hot.mv.db").toFile(), upstreamHomes), "a name that merely starts the same");
    }

    @Test
    public void theUpstreamHomesAreUpstreamsNotThisBuilds() {
        List<Path> homes = UpstreamWallets.upstreamHomes();
        Assertions.assertFalse(homes.isEmpty());
        for(Path upstreamHome : homes) {
            Assertions.assertFalse(upstreamHome.getFileName().toString().toLowerCase().contains("blake2b"), upstreamHome.toString());
        }
    }

    @Test
    public void copiesUnderANameNotAlreadyTaken() throws IOException {
        Path upstream = Files.createDirectories(home.resolve("upstream"));
        Path walletsDir = Files.createDirectories(home.resolve("wallets"));
        File source = Files.writeString(upstream.resolve("cold.mv.db"), "wallet").toFile();
        Files.writeString(walletsDir.resolve("cold.mv.db"), "already here");

        File copy = UpstreamWallets.copyInto(source, walletsDir.toFile());

        Assertions.assertEquals("cold-2.mv.db", copy.getName());
        Assertions.assertEquals("wallet", Files.readString(copy.toPath()));
        Assertions.assertEquals("already here", Files.readString(walletsDir.resolve("cold.mv.db")), "an existing wallet is never overwritten");
        Assertions.assertEquals("wallet", Files.readString(source.toPath()), "the original is left as it is");
        Assertions.assertEquals("encrypted", UpstreamWallets.destination(new File(upstream.toFile(), "encrypted"), walletsDir.toFile()).getName());
    }
}
