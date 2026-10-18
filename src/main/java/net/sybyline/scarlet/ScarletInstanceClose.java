package net.sybyline.scarlet;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.api.InstancesApi;
import io.github.vrchatapi.model.Instance;

/** One close submission, with explicit outcomes and no automatic mutation retries. */
final class ScarletInstanceClose
{
    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/VRChat");
    enum Status { CLOSED, SCHEDULED, ALREADY_CLOSED, REJECTED, UNCERTAIN }

    static final class Result
    {
        final Status status;
        final String location;
        final OffsetDateTime closedAt;
        final Boolean requestedHardClose;
        final OffsetDateTime requestedClosedAt;
        final String diagnosticId;
        private final int httpCode;

        Result(Status status, String location, OffsetDateTime closedAt, int httpCode,
            Boolean requestedHardClose, OffsetDateTime requestedClosedAt, String diagnosticId)
        {
            this.status = status;
            this.location = location;
            this.closedAt = closedAt;
            this.httpCode = httpCode;
            this.requestedHardClose = requestedHardClose;
            this.requestedClosedAt = requestedClosedAt;
            this.diagnosticId = diagnosticId;
        }

        String message()
        {
            switch (this.status)
            {
            case CLOSED:
                return "Closed instance `" + this.location + "`.";
            case SCHEDULED:
                return "Instance closure scheduled for <t:" + this.closedAt.toEpochSecond() + ":F> (<t:"
                    + this.closedAt.toEpochSecond() + ":R>): `" + this.location + "`.";
            case ALREADY_CLOSED:
                return "This instance is already closed. No additional close was performed.";
            case REJECTED:
                if (this.httpCode == 401)
                    return this.withReference("VRChat rejected the close request because Scarlet is not authenticated. Check the logger's VRChat login.");
                if (this.httpCode == 403)
                    return this.withReference("VRChat refused this close request (HTTP 403). Check Scarlet's local log for privacy-filtered rejection details.");
                if (this.httpCode == 404)
                    return this.withReference("VRChat could not find this instance (HTTP 404). Check the full instance link.");
                if (this.httpCode == 429)
                    return this.withReference("VRChat rate-limited the close request (HTTP 429). Wait before trying again.");
                return this.withReference("VRChat rejected the close request (HTTP " + this.httpCode + "). Check Scarlet's local log for the status.");
            default:
                return this.withReference("Scarlet could not confirm the close request. The instance may already be closed or scheduled to close. "
                    + "Check its status before trying again. See Scarlet's local log for details.");
            }
        }

        private String withReference(String message)
        {
            return message + " Reference: `" + this.diagnosticId + "`.";
        }
    }

    private ScarletInstanceClose() { }

    static Result submit(ApiClient client, String worldId, String instanceId, Boolean hardClose, OffsetDateTime closedAt)
    {
        String location = worldId + ":" + instanceId;
        String diagnosticId = UUID.randomUUID().toString();
        // Record only local options here, never cookies, headers, or the pasted launch URL.
        LOG.info("Close-instance request [{}]: method=DELETE, requestedHardClose={}, requestedClosedAt={}",
            diagnosticId, hardCloseOption(hardClose), closeAtOption(closedAt));
        try
        {
            Instance response = new InstancesApi(client).closeInstance(worldId, instanceId, hardClose, closedAt);
            OffsetDateTime effectiveCloseAt = response != null && response.getClosedAt() != null ? response.getClosedAt() : closedAt;
            Status status = effectiveCloseAt != null && effectiveCloseAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))
                ? Status.SCHEDULED : Status.CLOSED;
            return new Result(status, location, effectiveCloseAt, 200, hardClose, closedAt, diagnosticId);
        }
        catch (ApiException ex)
        {
            LOG.warn("Close-instance response [{}]: HTTP {}, {}; no application-level retry was made",
                diagnosticId, ex.getCode(), ScarletCloseDiagnostics.describe(ex));
            if (ScarletCloseDiagnostics.isAlreadyClosed(ex))
                return new Result(Status.ALREADY_CLOSED, location, null, ex.getCode(), hardClose, closedAt, diagnosticId);
            Status status = ex.getCode() <= 0 || ex.getCode() >= 500 ? Status.UNCERTAIN : Status.REJECTED;
            return new Result(status, location, null, ex.getCode(), hardClose, closedAt, diagnosticId);
        }
        catch (RuntimeException ex)
        {
            // Parsing can fail after VRChat accepted the mutation. Do not retry or claim rejection.
            // Keep server payloads, headers and nested personal data out of the Discord reply and log.
            LOG.warn("Close-instance response [{}] could not be read ({}); outcome uncertain, no application-level retry was made",
                diagnosticId, ex.getClass().getSimpleName());
            return new Result(Status.UNCERTAIN, location, null, 0, hardClose, closedAt, diagnosticId);
        }
    }

    static String hardCloseOption(Boolean value)
    {
        return value == null ? "omitted (default false)" : value.toString();
    }

    static String closeAtOption(OffsetDateTime value)
    {
        return value == null ? "omitted (immediate)" : value.toString();
    }

}
