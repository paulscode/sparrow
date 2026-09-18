package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.net.ServerFeatures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The cross check that would catch this build shipping the wrong activation height.
 *
 * <p>The decision reads a height the connected node reports and compares it with the one compiled in. Where
 * they disagree one of the two is wrong and there is no way to tell which, so the wallet declines to opt in:
 * a signature that does not opt in is always valid, while one made under the wrong schedule either fails to
 * verify or forgoes the protection it claims.
 *
 * <p>That check could not fire. {@code setNodeHardforkHeight} had no caller anywhere in the application, so
 * the field it writes was permanently null and every connection took the uncorroborated path, including one
 * to a server reporting exactly the right height. The value was already being read on the same connect, by
 * the chain identity check, and simply never handed over.
 *
 * <p>This covers the decision either side of that wiring. What it cannot reach is the connect path itself,
 * which needs a server; that is one line beside the chain check in ElectrumServer, and the dead-setter
 * failure is the reason this test names it.
 */
public class NodeActivationHeightWiringTest {
    private static final int MAINNET_ACTIVATION = 961640;

    @AfterEach
    public void tearDown() {
        AppServices.clearNodeHardforkHeight();
        Network.set(null);
    }

    private static ServerFeatures featuresReporting(Integer height) {
        ServerFeatures features = new ServerFeatures();
        if(height != null) {
            features.blake2b_fork = new ServerFeatures.Blake2bFork();
            features.blake2b_fork.height = height;
        }
        return features;
    }

    /** What ElectrumServer hands over on connect, in both shapes a server can present. */
    private static Integer reportedHeight(ServerFeatures features) {
        return features != null && features.blake2b_fork != null ? features.blake2b_fork.height : null;
    }

    /**
     * A server that agrees corroborates the shipped schedule, and the opt-in is taken outright rather than
     * with the caveat that nothing checked it.
     */
    @Test
    public void anAgreeingNodeCorroboratesTheShippedSchedule() {
        AppServices.setNodeHardforkHeight(reportedHeight(featuresReporting(MAINNET_ACTIVATION)));

        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN,
                AppServices.heightDecision(MAINNET_ACTIVATION, AppServices.getNodeHardforkHeight(), MAINNET_ACTIVATION + 10));
    }

    /**
     * The check this exists for. A node on a different schedule means one of the two is wrong, so the wallet
     * declines rather than guessing which.
     */
    @Test
    public void aDisagreeingNodeStopsTheOptIn() {
        AppServices.setNodeHardforkHeight(reportedHeight(featuresReporting(MAINNET_ACTIVATION + 1000)));

        Assertions.assertEquals(UnifiedSigHashDecision.SCHEDULE_MISMATCH,
                AppServices.heightDecision(MAINNET_ACTIVATION, AppServices.getNodeHardforkHeight(), MAINNET_ACTIVATION + 10));
    }

    /**
     * A server with no such field, which is every Fulcrum and every stock Electrum server. The opt-in is
     * still taken, since declining because a server cannot answer would forgo the protection on most
     * connections, but it is recorded as uncorroborated rather than as checked.
     */
    @Test
    public void aSilentNodeLeavesTheScheduleUncorroborated() {
        AppServices.setNodeHardforkHeight(reportedHeight(featuresReporting(null)));

        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED,
                AppServices.heightDecision(MAINNET_ACTIVATION, AppServices.getNodeHardforkHeight(), MAINNET_ACTIVATION + 10));
    }

    /**
     * A height must not outlive the server that reported it. Connecting to one that agrees and then to one
     * that says nothing has to fall back to uncorroborated, not keep the old corroboration.
     */
    @Test
    public void aReportedHeightDoesNotSurviveTheNextConnection() {
        AppServices.setNodeHardforkHeight(reportedHeight(featuresReporting(MAINNET_ACTIVATION)));
        Assertions.assertEquals(MAINNET_ACTIVATION, AppServices.getNodeHardforkHeight());

        //Reconnecting to a server that reports nothing
        AppServices.setNodeHardforkHeight(reportedHeight(featuresReporting(null)));

        Assertions.assertNull(AppServices.getNodeHardforkHeight(),
                "the previous server's height must not corroborate this one's silence");
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED,
                AppServices.heightDecision(MAINNET_ACTIVATION, AppServices.getNodeHardforkHeight(), MAINNET_ACTIVATION + 10));
    }

    /** And disconnecting clears it, so nothing is carried into the next connection at all. */
    @Test
    public void disconnectingClearsTheReportedHeight() {
        AppServices.setNodeHardforkHeight(MAINNET_ACTIVATION);
        AppServices.clearNodeHardforkHeight();

        Assertions.assertNull(AppServices.getNodeHardforkHeight());
    }

    /**
     * The wiring itself, which is what actually broke.
     *
     * <p>Every assertion above passed while this feature was doing nothing, because they exercise the
     * decision and the decision was always correct. What was missing was the one call that gives it a value,
     * and no behavioural test in this project could see that: the connect path needs a server.
     *
     * <p>So this reads the source instead. It is blunt, and it is the only thing here that would have caught
     * a setter with no caller, which is the second time that shape of bug has shipped.
     */
    @Test
    public void theConnectPathActuallyHandsTheHeightOver() throws Exception {
        java.nio.file.Path electrumServer =
                java.nio.file.Path.of("src/main/java/com/sparrowwallet/sparrow/net/ElectrumServer.java");
        Assertions.assertTrue(java.nio.file.Files.isDirectory(java.nio.file.Path.of("src/main/java")),
                "expected to run from the project directory");

        String source = java.nio.file.Files.readString(electrumServer);
        Assertions.assertTrue(source.contains("AppServices.setNodeHardforkHeight("),
                "nothing tells the opt-in decision what height the node reports, so the cross check that would "
                        + "catch a wrong activation height compiled into this build can never fire");
        Assertions.assertTrue(source.contains("AppServices.clearNodeHardforkHeight("),
                "a height reported by one server must not be carried into a connection to another");
    }
}
