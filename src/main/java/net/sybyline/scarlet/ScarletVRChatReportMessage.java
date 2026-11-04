package net.sybyline.scarlet;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.sybyline.scarlet.util.VRChatHelpDeskURLs;

/** Builds the private report-button reply without submitting a report or opening a connection. */
final class ScarletVRChatReportMessage
{
    private ScarletVRChatReportMessage() {}

    static MessageCreateData create(String email, String targetId, String subject,
                                    String simpleHtml, String templateHtml)
    {
        String reportHtml = templateHtml == null || templateHtml.trim().isEmpty() ? simpleHtml : templateHtml;
        String plainReport = reportHtmlToPlain(reportHtml);
        String displayName = subject != null ? subject : targetId != null ? targetId : "user";
        String heading = MarkdownSanitizer.escape(displayName).replace('`', '\u02cb');
        String preview = plainReport.replace("`", "`\u200b");
        boolean needsFullText = preview.length() > 1500 || !preview.equals(plainReport);
        String content = "**Copy-paste report for " + shorten(heading, 100) + "**\n"
            + "Use the template below or open the autofilled form. Review the details before submitting.\n"
            + "```\n" + shorten(preview, 1500) + "\n```"
            + (needsFullText ? "\nPreview shortened or escaped; the complete copyable report is attached as `vrchat-report.txt`." : "");

        MessageCreateBuilder message = new MessageCreateBuilder()
            .setContent(content)
            .setAllowedMentions(Collections.emptySet());
        if (needsFullText)
            message.addFiles(FileUpload.fromData(plainReport.getBytes(StandardCharsets.UTF_8), "vrchat-report.txt"));

        addFormLink(message, "Autofilled report form (configured template)",
            "Open autofilled VRChat report form",
            VRChatHelpDeskURLs.newModerationRequest_account_other(email, targetId, subject, reportHtml),
            "vrchat-templated-form-link.txt");
        addFormLink(message, "Simple report form (notes and tags)",
            "Open simple VRChat report form",
            VRChatHelpDeskURLs.newModerationRequest_account_other(email, targetId, subject, simpleHtml),
            "vrchat-simple-form-link.txt");
        return message.build();
    }

    private static void addFormLink(MessageCreateBuilder message, String title, String label,
                                    String url, String filename)
    {
        String guidance = "\nSign in to the VRChat Help Desk, then reopen this link if needed. "
            + "For this user-report form, attach supporting evidence; use in-app reporting for general reports.";
        String description = "[" + label + "](<" + url + ">)" + guidance;
        // Two descriptions plus titles must fit Discord's combined 6,000-character embed budget.
        // Never shorten a prefilled URL: doing so silently loses or corrupts the report fields.
        if (description.length() > 2800)
        {
            description = "The full autofill link exceeds Discord's display limit. "
                + "Copy the complete URL from the attached `" + filename + "` into your browser." + guidance;
            message.addFiles(FileUpload.fromData(url.getBytes(StandardCharsets.UTF_8), filename));
        }
        message.addEmbeds(new EmbedBuilder().setTitle(title).setDescription(description).build());
    }

    private static String shorten(String value, int maxLength)
    {
        if (value.length() <= maxLength)
            return value;
        int end = maxLength - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1)))
            end--;
        return value.substring(0, end) + "\u2026";
    }

    /** Converts the template's HTML into the plain-text copy/paste alternative. */
    static String reportHtmlToPlain(String html)
    {
        if (html == null)
            return "";
        String out = html.replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)<li>", "\n- ")
            .replaceAll("(?i)</li>", "\n")
            .replaceAll("(?i)</?ul>", "")
            .replaceAll("<[^>]+>", "")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace("&amp;", "&");
        return out.replaceAll("\n{2,}(?=- )", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }
}
