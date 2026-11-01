package net.sybyline.scarlet.log;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

class ScarletLoggerFactory implements ILoggerFactory
{
    private static final String
        LEGACY_SCARLET_LOG_ROOT = "Scarlet",
        SCARLET_LOG_ROOT = "Hatson321/Scarlet Logger",
        SCARLET_PACKAGE_PREFIX = "net.sybyline.scarlet.";

    ScarletLoggerFactory()
    {
    }

    @Override
    public Logger getLogger(String name)
    {
        if (name.startsWith("net.dv8tion.jda.api."))
        {
            name = "JDA/" + name.substring(20).replace('.', '/');
        }
        else if (name.startsWith("net.dv8tion.jda.internal."))
        {
            name = "JDA-I/" + name.substring(25).replace('.', '/');
        }
        else if (LEGACY_SCARLET_LOG_ROOT.equals(name))
        {
            name = SCARLET_LOG_ROOT;
        }
        else if (name.startsWith(LEGACY_SCARLET_LOG_ROOT + "/"))
        {
            name = SCARLET_LOG_ROOT + name.substring(LEGACY_SCARLET_LOG_ROOT.length());
        }
        else if (name.startsWith(SCARLET_PACKAGE_PREFIX))
        {
            name = SCARLET_LOG_ROOT + "/" + name.substring(SCARLET_PACKAGE_PREFIX.length()).replace('.', '/');
        }
        return new ScarletLogger(name);
    }

}
