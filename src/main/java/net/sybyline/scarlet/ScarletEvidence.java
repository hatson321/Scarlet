package net.sybyline.scarlet;

import java.io.File;
import java.io.IOException;

import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.User;

import net.sybyline.scarlet.util.TemplateStrings;

public class ScarletEvidence
{

    public static class FormatParams
    {
        public String groupId = "groupId", groupName = "groupName", groupCode = "groupCode";
        public String actorId = "actorId", actorName = "actorName";
        public String targetId = "targetId", targetName = "targetName";
        public String fileName = "fileName", fileExt = "fileExt";
        public String auditId = "auditId";
        public String index = "0";
        private String prevFormat = null;
        public FormatParams incrementIndex()
        {
            this.index = Integer.toUnsignedString(Integer.parseUnsignedInt(this.index) + 1);
            return this;
        }
        public FormatParams group(ScarletVRChat vrc, String groupId, Group group)
        {
            this.groupId = groupId;
            if (group == null && vrc != null)
                group = vrc.getGroup(groupId);
            if (group != null)
            {
                this.groupName = group.getName();
                this.groupCode = group.getShortCode()+"."+group.getDiscriminator();
            }
            return this;
        }
        public FormatParams actor(ScarletVRChat vrc, String actorId, String actorDisplayName)
        {
            this.actorId = actorId;
            if (actorDisplayName == null && vrc != null)
            {
                User actor = vrc.getUser(actorId);
                actorDisplayName = actor == null ? actorId : actor.getDisplayName();
            }
            this.actorName = actorDisplayName;
            return this;
        }
        public FormatParams target(ScarletVRChat vrc, String targetId, String targetDisplayName)
        {
            this.targetId = targetId;
            if (targetDisplayName == null && vrc != null)
            {
                User target = vrc.getUser(targetId);
                targetDisplayName = target == null ? targetId : target.getDisplayName();
            }
            this.targetName = targetDisplayName;
            return this;
        }
        public FormatParams file(String fileNameExt)
        {
            if (fileNameExt != null)
            {
                fileNameExt = TemplateStrings.sanitizePathComponent(new File(fileNameExt).getName());
                int period = fileNameExt.lastIndexOf('.');
                this.fileName = period < 0 ? fileNameExt : fileNameExt.substring(0, period);
                this.fileExt = period < 0 ? "" : fileNameExt.substring(period + 1);
            }
            return this;
        }
        public FormatParams file(String fileName, String fileExt)
        {
            if (fileName != null)
                this.fileName = fileName;
            if (fileExt != null)
                this.fileExt = fileExt;
            return this;
        }
        public FormatParams audit(String auditId)
        {
            if (auditId != null)
                this.auditId = auditId;
            return this;
        }
        public FormatParams index(int index)
        {
            this.index = Integer.toUnsignedString(index);
            return this;
        }
        public String format(String format)
        {
            if (format == null || format.trim().isEmpty())
                format = "{targetId}/{fileName}.{fileExt}";
            String value = TemplateStrings.interpolateTemplate(format, this);
            this.prevFormat = value;
            return value;
        }
        public File nextFile(String evidenceRoot, String format) throws IOException
        {
            File root = new File(evidenceRoot).getAbsoluteFile();
            File file = new File(root, sanitizeRelativePath(this.format(format))).getAbsoluteFile();
            if (!file.toPath().startsWith(root.toPath()))
                throw new IOException("Refusing to write evidence outside configured evidence root");
            if (format != null && format.contains("{index}"))
            {
                int collisions = 0;
                while (java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                {
                    if (++collisions > 10000) throw new IOException("No free evidence filename available");
                    this.incrementIndex();
                    file = new File(root, sanitizeRelativePath(this.format(format))).getAbsoluteFile();
                    if (!file.toPath().startsWith(root.toPath()))
                        throw new IOException("Refusing to write evidence outside configured evidence root");
                }
            }
            return file;
        }
        private static String sanitizeRelativePath(String relativePath)
        {
            String[] parts = relativePath.replace('\\', '/').split("/");
            StringBuilder sb = new StringBuilder();
            for (String part : parts)
            {
                if (part == null || part.isEmpty())
                    continue;
                if (sb.length() != 0)
                    sb.append(File.separatorChar);
                sb.append(TemplateStrings.sanitizePathComponent(part));
            }
            return sb.length() == 0 ? "_" : sb.toString();
        }
        public String prevFormat()
        {
            return this.prevFormat;
        }
    }

}
