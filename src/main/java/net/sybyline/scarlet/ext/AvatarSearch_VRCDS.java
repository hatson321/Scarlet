package net.sybyline.scarlet.ext;

import java.io.IOException;

import net.sybyline.scarlet.util.HttpURLInputStream;

/**
 * https://nekosunevr.co.uk
 */
public interface AvatarSearch_VRCDS
{

    public static final String API_ROOT = AvatarSearch_NSVR.API_ROOT;
    /** The search endpoint moved hosts: the old {@code avtr.} subdomain no longer serves
     *  {@code /vrcx_search} directly (it responds with a redirect/notice that broke parsing). */
    public static final String SEARCH_ROOT = AvatarSearch_NSVR.API_ROOT_SEARCH;

    public static class PutAvatarExternalRequest
    {
        public PutAvatarExternalRequest(String id, String userid)
        {
            this.id = id;
            this.userid = userid;
        }
        public PutAvatarExternalRequest()
        {
        }
        public String id;
        public String userid;
    }

    static boolean putAvatarExternal(String id, String userid)
    {
        try (HttpURLInputStream in = HttpURLInputStream.post(API_ROOT+"/v1/vrchat/avatars/store/putavatarExternal", ExtendedUserAgent.init_conn, HttpURLInputStream.writeAsJson(null, null, PutAvatarExternalRequest.class, new PutAvatarExternalRequest(id, userid))))
        {
        }
        catch (IOException ioex)
        {
            ioex.printStackTrace();
            return false;
        }
        return true;
    }

}
