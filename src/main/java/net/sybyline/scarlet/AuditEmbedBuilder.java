package net.sybyline.scarlet;

import net.dv8tion.jda.api.EmbedBuilder;

/** Bounded Discord audit rendering; oversized API values must not abort delivery. */
final class AuditEmbedBuilder extends EmbedBuilder
{
    private boolean overflow;

    static String clip(String text, int maximum)
    {
        if (text == null || text.length() <= maximum) return text;
        String suffix = " [truncated]";
        int end = Math.max(0, maximum - suffix.length());
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + suffix.substring(0, Math.min(suffix.length(), maximum - end));
    }

    @Override public EmbedBuilder setTitle(String title, String url)
    {
        return super.setTitle(clip(title, 256), url);
    }

    @Override public EmbedBuilder addField(String name, String value, boolean inline)
    {
        if (this.overflow) return this;
        int remaining = 6000 - this.length();
        if (this.getFields().size() >= 24 || remaining < 400)
        {
            this.overflow = true;
            if (remaining >= 90 && this.getFields().size() < 25)
                super.addField("Additional data", "Further fields omitted to fit Discord's message limits.", false);
            return this;
        }
        String boundedName = clip(name, 256);
        int valueBudget = Math.min(1024, remaining - (boundedName == null ? 0 : boundedName.length()) - 100);
        return super.addField(boundedName, clip(value, Math.max(1, valueBudget)), inline);
    }
}
