package net.sybyline.scarlet;

import java.io.IOException;
import java.io.InterruptedIOException;

import javax.net.ssl.SSLException;

import net.sybyline.scarlet.ext.ManagedVrcClient;

/** Closed diagnostic vocabulary: never log a remote body, join link or exception message. */
final class ScarletStartupFailure extends IOException
{
    private static final long serialVersionUID = 1L;

    enum Kind
    {
        INVALID_LOCATION("The instance location is invalid or belongs to a different group."),
        CANCELLED("The startup request was cancelled or is no longer allowed."),
        INVALID_RESPONSE("VRChat returned an invalid or incomplete response."),
        RESPONSE_TOO_LARGE("VRChat returned a response exceeding the startup size limit."),
        SECURE_NAME_MISSING("VRChat did not return usable secure join information."),
        REQUEST_FAILED("The startup request could not be prepared safely.");

        final String description;
        Kind(String description) { this.description = description; }
    }

    ScarletStartupFailure(Kind kind)
    {
        super(java.util.Objects.requireNonNull(kind, "kind").description);
    }

    private ScarletStartupFailure(int status)
    {
        super(status >= 100 && status <= 599
            ? "VRChat returned HTTP " + status + "."
            : "VRChat returned an unsuccessful HTTP response.");
    }

    static ScarletStartupFailure http(int status)
    {
        return new ScarletStartupFailure(status);
    }

    static String describe(Throwable failure)
    {
        if (failure instanceof ScarletStartupFailure) return failure.getMessage();
        if (failure instanceof ManagedVrcClient.LaunchFailure) return ((ManagedVrcClient.LaunchFailure) failure).description();
        if (failure instanceof InterruptedIOException) return "The request timed out or was interrupted.";
        if (failure instanceof SSLException) return "The secure connection could not be established.";
        if (failure instanceof IOException) return "An input/output operation failed.";
        return "An unexpected internal error occurred.";
    }
}
