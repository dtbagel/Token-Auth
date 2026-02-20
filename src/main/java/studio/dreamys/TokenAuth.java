package studio.dreamys;

/**
 * Shared mod constants and cross-class state.
 * Lives in src/main so it can be referenced from both main and client source sets.
 */
public final class TokenAuth {

    public static final String MOD_ID = "tokenauth";

    /**
     * Stores the player's original session before the first token swap,
     * so it can be restored later.
     * Fields: username, uuid, accessToken, xuid, clientId, accountType
     */
    public static String[] originalSession = null;

    private TokenAuth() {}
}
