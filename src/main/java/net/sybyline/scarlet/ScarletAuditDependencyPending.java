package net.sybyline.scarlet;

/** An expected delivery prerequisite, not a failed Discord request. */
final class ScarletAuditDependencyPending extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    final String parentEventId;

    ScarletAuditDependencyPending(String parentEventId)
    {
        super("The associated moderation thread is still being prepared");
        if (parentEventId == null || !parentEventId.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IllegalArgumentException("Invalid audit dependency identifier");
        this.parentEventId = parentEventId;
    }
}
