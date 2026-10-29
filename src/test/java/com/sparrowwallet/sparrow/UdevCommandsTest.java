package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The udev dialog named upstream's /opt/sparrowwallet, which this build never creates. It is released as an archive
 * extracted wherever the user likes, into a directory whose name has a space in it.
 */
public class UdevCommandsTest {
    @Test
    public void pointsAtTheDirectoryTheLauncherRunsFrom() {
        String commands = AppController.udevCommands("/home/someone/apps/Sparrow BLAKE2b/bin/sparrow");
        Assertions.assertTrue(commands.startsWith("sudo install -m 644 \"/home/someone/apps/Sparrow BLAKE2b/lib/runtime/conf/udev\"/*.rules /etc/udev/rules.d"), commands);
        Assertions.assertFalse(commands.contains("/opt/sparrowwallet"), commands);
    }

    @Test
    public void runFromSourceItLeavesThePathToFillIn() {
        Assertions.assertTrue(AppController.udevCommands(null).contains("\"/path/to/Sparrow BLAKE2b/lib/runtime/conf/udev\"/*.rules"));
    }
}
