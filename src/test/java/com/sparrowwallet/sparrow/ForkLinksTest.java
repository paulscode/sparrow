package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Help menu, bug reports and connection hints pointed at sparrowwallet.com, whose pages describe the SHA256d chain and
 * whose maintainer does not support this build.
 */
public class ForkLinksTest {
    @Test
    public void aNewIssueCarriesItsDetailsEncoded() {
        String url = ForkLinks.newIssue("Version: 2.5.6-blake2b.8\nWhat happened:\n\n");
        Assertions.assertTrue(url.startsWith("https://github.com/paulscode/sparrow/issues/new?body="), url);
        Assertions.assertFalse(url.contains(" ") || url.contains("\n") || url.contains("+"), url);
        Assertions.assertEquals("body=Version: 2.5.6-blake2b.8\nWhat happened:\n\n", URI.create(url).getQuery());
    }

    @Test
    public void theReadmeHasTheSectionTheConnectionHintsLinkTo() throws Exception {
        Assertions.assertTrue(ForkLinks.CONNECTING.endsWith("#connecting-to-a-server"));
        Assertions.assertTrue(Files.readString(Path.of("README.md")).contains("\n## Connecting to a server\n"));
        Assertions.assertTrue(Files.exists(Path.of("docs/split-with-a-hardware-wallet.md")), "the split guide link must resolve");
    }

    @Test
    public void bugReportsGoToThisBuildsRepository() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/sparrowwallet/sparrow/AppController.java"));
        Assertions.assertFalse(controller.contains("sparrowwallet.com/submitbugreport"));
        Assertions.assertFalse(controller.contains("sparrowwallet.com/opensupport"));
        Assertions.assertTrue(AppController.bugReportDetails().startsWith("Version: " + SparrowWallet.APP_VERSION + SparrowWallet.APP_VERSION_SUFFIX));
    }
}
