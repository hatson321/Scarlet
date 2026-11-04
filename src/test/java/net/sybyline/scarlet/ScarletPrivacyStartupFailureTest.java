package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ScarletPrivacyStartupFailureTest
{
    @Test void recognizesWrappedPolicyFailureWithoutDisclosingItsContents()
    {
        Throwable failure = new ExceptionInInitializerError(
            new ScarletSecretStaffList.PersistenceException("synthetic-private-staff-or-path"));
        String message = Scarlet.privacyPolicyStartupFailureMessage(failure);
        assertNotNull(message);
        assertTrue(message.contains("stopped to protect secret-staff privacy"));
        assertTrue(message.contains("secret_staff_list.json"));
        assertTrue(message.contains("older backup"));
        assertFalse(message.contains("synthetic-private-staff-or-path"));
    }

    @Test void unrelatedFailuresDoNotReceiveMisleadingPrivacyGuidance()
    {
        assertNull(Scarlet.privacyPolicyStartupFailureMessage(new IllegalStateException("other")));
        assertNull(Scarlet.privacyPolicyStartupFailureMessage(null));
    }

    @Test void causeCyclesDoNotHangFailureReporting()
    {
        Exception first = new Exception(), second = new Exception();
        first.initCause(second);
        second.initCause(first);
        assertNull(Scarlet.privacyPolicyStartupFailureMessage(first));
    }
}
