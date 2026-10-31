package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.ApplicationDir;
import com.sparrowwallet.drongo.OsType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Recognises wallet files that belong to upstream Sparrow, so they are copied here rather than opened in place.
 *
 * <p>Opening a wallet migrates its database to this build's schema, and this build's migrations are not upstream's: once
 * upstream ships its own migration of the same number, upstream can no longer open the file. Opening it here also writes
 * this chain's history and labels into a wallet that describes the other chain. A copy leaves upstream's file untouched.
 */
public final class UpstreamWallets {
    private static final String UPSTREAM_APP_NAME = "Sparrow";

    private UpstreamWallets() {
    }

    /** Where upstream Sparrow keeps its files: its home folder, and its XDG data folder where the platform has one. */
    public static List<Path> upstreamHomes() {
        List<Path> homes = new ArrayList<>();
        homes.add(ApplicationDir.getDefaultDir(UPSTREAM_APP_NAME).toPath());
        if(OsType.getCurrent() != OsType.WINDOWS) {
            String xdgDataHome = System.getenv("XDG_DATA_HOME");
            Path base = xdgDataHome != null && xdgDataHome.startsWith("/") ? Path.of(xdgDataHome) : Path.of(System.getProperty("user.home"), ".local", "share");
            homes.add(base.resolve(UPSTREAM_APP_NAME.toLowerCase()));
        }
        return homes;
    }

    public static boolean isUpstreamFile(File file) {
        return isUpstreamFile(file, upstreamHomes());
    }

    static boolean isUpstreamFile(File file, List<Path> upstreamHomes) {
        Path path = resolve(file.toPath());
        return upstreamHomes.stream().map(UpstreamWallets::resolve).anyMatch(path::startsWith);
    }

    /** Copies the file into the given wallets folder, under a name not already taken there, and returns the copy. */
    public static File copyInto(File source, File walletsDir) throws IOException {
        File destination = destination(source, walletsDir);
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        return destination;
    }

    static File destination(File source, File walletsDir) {
        String name = source.getName();
        String extension = "";
        for(PersistenceType persistenceType : PersistenceType.values()) {
            String suffix = "." + persistenceType.getExtension();
            if(name.endsWith(suffix)) {
                extension = suffix;
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }

        File destination = new File(walletsDir, name + extension);
        for(int i = 2; destination.exists(); i++) {
            destination = new File(walletsDir, name + "-" + i + extension);
        }
        return destination;
    }

    private static Path resolve(Path path) {
        try {
            return path.toRealPath();
        } catch(IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
