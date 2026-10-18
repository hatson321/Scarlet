package net.sybyline.scarlet.ui;

import java.awt.GraphicsEnvironment;

import javax.swing.JOptionPane;

import net.sybyline.scarlet.util.Platform;

/** Shared disclosure for local and Discord avatar-provider permission controls. */
public final class AvatarProviderConsentDialog
{
    private AvatarProviderConsentDialog() {}

    public static final String TITLE = "Avatar database permission";
    public static final String DISCLOSURE =
        "Using external avatar databases requires your permission to share Scarlet's version, "
        + "your configured VRChat group ID and the signed-in primary VRChat account's user ID "
        + "with the selected avatar database providers. These identify the logger operator, "
        + "not the person being searched, so providers can investigate database abuse or excessive traffic.\n\n"
        + "Manual and automatic lookups also send lookup information such as avatar names and creator/file IDs. "
        + "Providers see your public IP address and the maintainer's public support contact. "
        + "Passwords, login cookies and Discord bot tokens are not included in this identification header.\n\n"
        + "Only enable this if you agree to that sharing. You can turn permission off in Avatar Search settings "
        + "to stop future lookups; work already in progress may finish. Cancelling does not enable lookups.";

    public static boolean confirm()
    {
        if (Platform.forceHeadlessUi() || GraphicsEnvironment.isHeadless())
            return false;
        return Boolean.TRUE.equals(Swing.getWait(() -> JOptionPane.showOptionDialog(null,
            Swing.dialogMessage(DISCLOSURE), TITLE, JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE, null,
            new Object[]{"Allow sharing and enable", "Keep disabled"}, "Keep disabled") == 0));
    }
}
