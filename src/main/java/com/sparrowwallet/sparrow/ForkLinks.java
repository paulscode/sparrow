package com.sparrowwallet.sparrow;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Where this build's own documentation and issues live. Upstream's sparrowwallet.com pages describe the chain that kept
 * SHA256d, its public servers and its fee sources, and upstream cannot act on this build's bugs.
 */
public final class ForkLinks {
    public static final String REPOSITORY = "https://github.com/paulscode/sparrow";
    public static final String README = REPOSITORY + "#readme";
    public static final String CONNECTING = REPOSITORY + "#connecting-to-a-server";
    public static final String ISSUES = REPOSITORY + "/issues";
    public static final String SPLIT_GUIDE = REPOSITORY + "/blob/blake2b/docs/split-with-a-hardware-wallet.md";
    public static final String UPSTREAM_DOCS = "https://sparrowwallet.com/docs";

    /** A new issue, with its body filled in. */
    public static String newIssue(String body) {
        return ISSUES + "/new?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private ForkLinks() {
    }
}
