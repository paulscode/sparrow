package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.ApplicationDir;
import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.sparrow.AppServices;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Recognises wallet files that belong to upstream Sparrow, so they are copied here rather than opened in place.
 *
 * <p>Opening a wallet migrates its database to this build's schema, and this build's migrations are not upstream's: once
 * upstream ships its own migration of the same number, upstream can no longer open the file. Opening it here also writes
 * this chain's history and labels into a wallet that describes the other chain. A copy leaves upstream's file untouched.
 */
public final class UpstreamWallets {
    private static final Logger log = LoggerFactory.getLogger(UpstreamWallets.class);
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
        return isUpstreamFile(file, upstreamHomes(), ownWalletsDir().toPath());
    }

    /**
     * This build's own wallets folder under its home, not a configured one. Earlier builds offered to make upstream's
     * folder the configured one, and a wallet there is upstream's whatever this build was told.
     */
    public static File ownWalletsDir() {
        return new File(Storage.getDataDir(), Storage.WALLETS_DIR);
    }

    /**
     * Whether the file is in one of upstream's folders. Never this build's own wallets folder, which a home given with
     * --dir can put inside one of upstream's.
     */
    static boolean isUpstreamFile(File file, List<Path> upstreamHomes, Path ownWalletsDir) {
        Path path = resolve(file.toPath());
        if(ownWalletsDir != null && path.startsWith(resolve(ownWalletsDir))) {
            return false;
        }
        return upstreamHomes.stream().map(UpstreamWallets::resolve).anyMatch(path::startsWith);
    }

    /**
     * Offers to copy a wallet file of upstream Sparrow's into this build's wallets folder, and returns the copy, or null if
     * it is not to be opened. Opened in place, it would be migrated to this build's database schema, which upstream may
     * not be able to read, and filled with this chain's history. Shared by the desktop and the terminal, which open
     * wallets by different paths.
     */
    public static File offerCopy(File file) {
        ButtonType copy = new ButtonType("Copy and Open", ButtonBar.ButtonData.OK_DONE);
        Optional<ButtonType> optType = AppServices.showWarningDialog("Wallet from upstream Sparrow",
                file.getName() + " is in upstream Sparrow's folder.\n\nOpening it here would change it in ways upstream Sparrow may not be able to read, "
                        + "and fill it with the BLAKE2b chain's transactions. Sparrow (BLAKE2b) can open a copy instead, in its own wallets folder, "
                        + "and leave the original as it is.\n\nClose the wallet in upstream Sparrow first, so the copy is complete.", ButtonType.CANCEL, copy);
        if(optType.isEmpty() || optType.get() != copy) {
            //Otherwise a wallet reopened at startup would ask again every time, with nothing else open to replace it in the list
            List<File> recentWalletFiles = Config.get().getRecentWalletFiles();
            if(recentWalletFiles != null && recentWalletFiles.contains(file)) {
                List<File> remaining = new ArrayList<>(recentWalletFiles);
                remaining.remove(file);
                Config.get().setRecentWalletFiles(remaining);
            }
            return null;
        }

        //Earlier builds offered to make upstream's folder this one's, and a copy made there would be no copy at all
        if(Config.get().getWalletsDir() != null && isUpstreamFile(Config.get().getWalletsDir())) {
            Config.get().setWalletsDir(null);
        }

        try {
            return copyInto(file, Storage.getWalletsDir());
        } catch(IOException e) {
            log.error("Error copying " + file.getAbsolutePath(), e);
            AppServices.showErrorDialog("Could not copy the wallet", e.getMessage());
            return null;
        }
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
