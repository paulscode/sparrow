package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The installers are this fork's own packages, so that they install beside upstream Sparrow rather than over it.
 *
 * Under upstream's sparrowwallet name and /opt directory, the .deb and .rpm replaced upstream Sparrow; the .msi wrote the
 * shared link keys under upstream's component GUIDs, which Windows Installer treats as one shared set of resources. These
 * templates are merged from upstream, so these checks are what keeps a merge from quietly bringing those back.
 */
public class PackagingIdentityTest {
    private static final Path PACKAGE = Path.of("src/main/deploy/package");

    private static List<Path> linuxTemplates() throws IOException {
        try(Stream<Path> files = Stream.concat(Files.list(PACKAGE.resolve("linux")), Files.list(PACKAGE.resolve("linux-headless")))) {
            return files.filter(path -> !path.toString().endsWith(".png")).toList();
        }
    }

    @Test
    public void theLinuxTemplatesNameThisForksPackages() throws IOException {
        //Upstream's names, where not followed by this fork's suffix
        Pattern upstreamName = Pattern.compile("\\b(sparrowwallet|sparrowserver)\\b(?!-blake2b)");
        for(Path template : linuxTemplates()) {
            String text = Files.readString(template);
            Matcher matcher = upstreamName.matcher(text);
            Assertions.assertFalse(matcher.find(), template + " still names upstream's package: ..." + (matcher.hitEnd() ? "" : text.substring(Math.max(0, matcher.start() - 40), Math.min(text.length(), matcher.end() + 40))) + "...");
            Assertions.assertFalse(text.contains("Craig Raw") || text.contains("mail@sparrowwallet.com"), template + " names upstream's maintainer");
        }
    }

    @Test
    public void thePackageRevisionIsTheRelease() throws IOException {
        for(String control : List.of("linux/control", "linux-headless/control")) {
            Assertions.assertTrue(Files.readString(PACKAGE.resolve(control)).contains("Version: ${version}-${release}"), control);
        }
        for(String spec : List.of("linux/sparrowwallet-blake2b.spec", "linux-headless/sparrowserver-blake2b.spec")) {
            Assertions.assertTrue(Files.readString(PACKAGE.resolve(spec)).contains("Release: ${release}"), spec);
        }
    }

    /** jpackage picks a desktop template by launcher name, and the one launcher the image keeps is sparrow. */
    @Test
    public void theDesktopEntryRunsTheLauncherTheImageKeeps() throws IOException {
        String desktop = Files.readString(PACKAGE.resolve("linux/sparrow.desktop"));
        Assertions.assertTrue(desktop.contains("Exec=/opt/sparrowwallet-blake2b/bin/sparrow %U"), desktop);
        String postinst = Files.readString(PACKAGE.resolve("linux/postinst"));
        Assertions.assertTrue(postinst.contains("/opt/sparrowwallet-blake2b/lib/sparrowwallet-blake2b-sparrow.desktop"), "postinst must install the desktop file jpackage writes");
        Assertions.assertTrue(postinst.contains("/opt/sparrowwallet-blake2b/lib/sparrowwallet-blake2b-MimeInfo.xml"));
    }

    @Test
    public void theWindowsInstallerLeavesUpstreamsLinkKeysAlone() throws IOException {
        String wxs = Files.readString(PACKAGE.resolve("windows/main.wxs"));
        for(String scheme : List.of("auth47", "bitcoin", "lightning")) {
            Assertions.assertFalse(Pattern.compile("Root=\"HKCR\"\\s+Key=\"" + scheme + "\"").matcher(wxs).find(), "writes the shared " + scheme + " key");
            Assertions.assertTrue(wxs.contains("Key=\"Software\\Classes\\SparrowBLAKE2b." + scheme + "\""), "registers its own " + scheme + " handler");
            Assertions.assertTrue(wxs.contains("Name=\"" + scheme + "\" Value=\"SparrowBLAKE2b." + scheme + "\""), "offers its " + scheme + " handler through Default apps");
        }
        //Upstream's component GUIDs, which describe upstream's resources
        Assertions.assertFalse(wxs.toUpperCase().contains("206C911C-56EF-44B8-9257-5FD214427965"));
        Assertions.assertFalse(wxs.toUpperCase().contains("BE92DD88-F799-47CC-A256-4165F9183A46"));
    }

    @Test
    public void theWindowsUpgradeCodeIsPinned() throws IOException {
        String gradle = Files.readString(Path.of("build.gradle"));
        Assertions.assertTrue(gradle.contains("'--win-upgrade-uuid', '91bbc22c-8e08-4a33-980e-879bc5616a1b'"),
                "changing the upgrade code orphans every install of an earlier release");
    }
}
