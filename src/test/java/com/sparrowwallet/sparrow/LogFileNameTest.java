package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Help > Show Log File, and every error dialog that points at it, has to look for the file logback actually writes.
 * It looked for upstream's sparrow.log while logback wrote sparrowblake2b.log, so it only ever said the log could not be
 * found. Logback cannot read a Java constant, so the two names are pinned together here.
 */
public class LogFileNameTest {
    @Test
    public void theLogFileShownIsTheOneWritten() throws IOException {
        String logback = Files.readString(Path.of("src/main/resources/logback.xml"));
        Matcher file = Pattern.compile("<file>\\$\\{appDir}/([^<]+)</file>").matcher(logback);
        Assertions.assertTrue(file.find(), "logback.xml must write its log under ${appDir}");
        Assertions.assertEquals(file.group(1), AppController.LOG_FILE_NAME);

        Matcher appDir = Pattern.compile("<application>([^<]+)</application>").matcher(logback);
        Assertions.assertTrue(appDir.find());
        Assertions.assertEquals(SparrowWallet.APP_NAME.toLowerCase(java.util.Locale.ROOT), appDir.group(1),
                "logback must write to this application's state directory, which is where the app looks");
    }

    @Test
    public void nothingNamesUpstreamsLogFile() throws IOException {
        String controller = Files.readString(Path.of("src/main/java/com/sparrowwallet/sparrow/AppController.java"));
        Assertions.assertFalse(controller.contains("\"sparrow.log\""), "the log is " + AppController.LOG_FILE_NAME);
    }
}
