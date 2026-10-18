package net.sybyline.scarlet.ext;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicReference;

import net.sybyline.scarlet.Scarlet;
import net.sybyline.scarlet.util.Func;

public class ExtendedUserAgent
{
    private static final String extendedUserAgent = Scarlet.USER_AGENT;
    private static final String avatarProviderUserAgent = "Hatson321-Scarlet/"+Scarlet.VERSION
        +" Discord:@hatson321/hatson321#0029 298959676094021632; https://discord.gg/CP3AyhypBF; "+Scarlet.GITHUB_URL;
    private static final AtomicReference<Identity> avatarIdentity = new AtomicReference<>(new Identity(null, null, 0L, false));

    private static final class Identity
    {
        final String groupId, userId;
        final long authenticationGeneration;
        final boolean authenticating;
        Identity(String groupId, String userId, long authenticationGeneration, boolean authenticating)
        {
            this.groupId = groupId;
            this.userId = userId;
            this.authenticationGeneration = authenticationGeneration;
            this.authenticating = authenticating;
        }
    }

    public static final Func.V1<IOException, HttpURLConnection>
        init_conn = conn -> conn.setRequestProperty("User-Agent", extendedUserAgent),
        // Only opted-in avatar-provider requests use the identifying header.
        init_avatar_provider_conn = conn -> conn.setRequestProperty("User-Agent", getAvatarProviderUserAgent()),
        init_conn_disable_redirects = conn -> {
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", extendedUserAgent);
        };
    public static void setCurrentGroupId(String groupId)
    {
        String valid = validatedId("grp", groupId);
        avatarIdentity.updateAndGet(identity -> new Identity(valid, identity.userId, identity.authenticationGeneration, identity.authenticating));
    }
    public static void setCurrentUserId(String userId)
    {
        String valid = validatedId("usr", userId);
        avatarIdentity.updateAndGet(identity -> new Identity(identity.groupId, valid, identity.authenticationGeneration + 1L, false));
    }

    /** Invalidate older authentication results before login/logout begins. */
    public static long beginPrimaryAuthentication()
    {
        return avatarIdentity.updateAndGet(identity -> new Identity(identity.groupId, null,
            identity.authenticationGeneration + 1L, true)).authenticationGeneration;
    }

    public static long currentAuthenticationGeneration()
    {
        return avatarIdentity.get().authenticationGeneration;
    }

    /** Read-only workers must not use captured credentials during a login transition. */
    public static boolean isPrimaryAuthenticationStable(long generation)
    {
        Identity identity = avatarIdentity.get();
        return !identity.authenticating && identity.authenticationGeneration == generation;
    }

    /** Complete login/logout and invalidate any probe begun during that transition. */
    public static boolean completePrimaryAuthentication(String userId, long generation)
    {
        String valid = validatedId("usr", userId);
        while (true)
        {
            Identity identity = avatarIdentity.get();
            if (identity.authenticationGeneration != generation || !identity.authenticating)
                return false;
            if (avatarIdentity.compareAndSet(identity, new Identity(identity.groupId, valid, generation + 1L, false)))
                return true;
        }
    }

    /** A late login/probe must not restore identity after logout, reset or a newer login. */
    public static boolean setCurrentUserIdIfUnchanged(String userId, long generation)
    {
        String valid = validatedId("usr", userId);
        while (true)
        {
            Identity identity = avatarIdentity.get();
            if (identity.authenticationGeneration != generation || identity.authenticating)
                return false;
            Identity updated = new Identity(identity.groupId, valid, valid == null ? generation + 1L : generation, false);
            if (avatarIdentity.compareAndSet(identity, updated))
                return true;
        }
    }

    private static String validatedId(String type, String id)
    {
        return id != null && id.matches(type+"_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            ? id : null;
    }

    /** Builds a fresh header from the configured group and authenticated primary account. */
    public static String getAvatarProviderUserAgent()
    {
        Identity identity = avatarIdentity.get();
        StringBuilder value = new StringBuilder(avatarProviderUserAgent);
        if (identity.groupId != null)
            value.append("; Group/").append(identity.groupId);
        if (identity.userId != null)
            value.append("; Operator/").append(identity.userId);
        return value.toString();
    }

    // Legacy callers never supply the operator for an avatar-provider request.
    public static String getExtendedUserAgent(String userId)
    {
        return extendedUserAgent;
    }
}
