package net.sybyline.scarlet;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import net.sybyline.scarlet.util.Location;
import net.sybyline.scarlet.util.VrcIds;

/** Local-only parsing for a destructive command; never guesses or follows a pasted URL. */
final class ScarletCloseInstanceInput
{
    static final String INVALID = "Please provide the full VRChat instance launch link, including both worldId and instanceId, or the full worldId:instanceId location. A world page or short link alone is not enough.";
    private static final Pattern INSTANCE = Pattern.compile("[-_a-zA-Z0-9]+(?:~[a-zA-Z]+(?:\\([-_a-zA-Z0-9]+\\))?)*");

    private ScarletCloseInstanceInput() { }

    static Location parse(String input, String configuredGroupId)
    {
        Location location;
        try
        {
            if (input == null) throw new IllegalArgumentException();
            String value = input.trim();
            if ((value.startsWith("<") && value.endsWith(">")) || (value.startsWith("`") && value.endsWith("`")))
                value = value.substring(1, value.length() - 1).trim();
            String world, instance;
            if (value.regionMatches(true, 0, "https:", 0, 6) || value.regionMatches(true, 0, "vrcx:", 0, 5))
            {
                URI uri = URI.create(value);
                if (uri.getRawUserInfo() != null || uri.getRawFragment() != null)
                    throw new IllegalArgumentException();
                if ("https".equalsIgnoreCase(uri.getScheme()) && "vrchat.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && "/home/launch".equals(uri.getPath()))
                {
                    Map<String, String> parameters = query(uri.getRawQuery());
                    world = parameters.get("worldId");
                    instance = parameters.get("instanceId");
                }
                else if ("vrcx".equalsIgnoreCase(uri.getScheme()) && "world".equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1 && uri.getRawQuery() == null && uri.getRawPath() != null)
                {
                    String raw = decode(uri.getRawPath());
                    if (!raw.startsWith("/")) throw new IllegalArgumentException();
                    String[] pair = raw.substring(1).split(":", -1);
                    if (pair.length != 2) throw new IllegalArgumentException();
                    world = pair[0];
                    instance = pair[1];
                }
                else throw new IllegalArgumentException();
            }
            else
            {
                String[] pair = value.split(":", -1);
                if (pair.length != 2) throw new IllegalArgumentException();
                world = pair[0];
                instance = pair[1];
            }
            if (world == null || !VrcIds.id_world.matcher(world).matches()
                || instance == null || !INSTANCE.matcher(instance).matches())
                throw new IllegalArgumentException();
            location = Location.of(world, instance);
        }
        catch (RuntimeException ex)
        {
            throw new IllegalArgumentException(INVALID);
        }
        if (configuredGroupId == null || !VrcIds.id_group.matcher(configuredGroupId).matches()
            || !configuredGroupId.equals(location.owner)
            || (location.type != Location.Type.GROUP && location.type != Location.Type.GROUP_PLUS
                && location.type != Location.Type.GROUP_PUBLIC))
            throw new IllegalArgumentException("This command can only close instances belonging to Scarlet's configured VRChat group.");
        return location;
    }

    private static Map<String, String> query(String raw)
    {
        if (raw == null) throw new IllegalArgumentException();
        Map<String, String> result = new HashMap<>();
        for (String field : raw.split("&", -1))
        {
            int equals = field.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException();
            String key = decode(field.substring(0, equals));
            String value = decode(field.substring(equals + 1));
            if (result.put(key, value) != null) throw new IllegalArgumentException();
        }
        return result;
    }

    private static String decode(String value)
    {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8.name()); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
