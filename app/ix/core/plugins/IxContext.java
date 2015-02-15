package ix.core.plugins;

import java.io.File;
import java.io.IOException;
import java.sql.DatabaseMetaData;

import play.Logger;
import play.Plugin;
import play.Application;
import play.db.DB;

public class IxContext extends Plugin {
    static final String IX_HOME = "ix.home";
    static final String IX_DEBUG = "ix.debug";
    static final String IX_CACHE = "ix.cache";
    static final String IX_CACHE_BASE = IX_CACHE+".base";
    static final String IX_CACHE_TIME = IX_CACHE+".time";
    static final String IX_TEXT = "ix.text";
    static final String IX_TEXT_BASE = IX_TEXT+".base";
    static final String IX_H2 = "ix.h2";
    static final String IX_H2_BASE = IX_H2+".base";
    static final String IX_STORAGE = "ix.storage";
    static final String IX_STORAGE_BASE = IX_STORAGE+".base";
    static final String IX_PAYLOAD = "ix.payload";
    static final String IX_PAYLOAD_BASE = IX_PAYLOAD+".base";
    

    private final Application app;
    public File home = new File (".");
    public File cache;
    public File text;
    public File h2;
    public File payload;
    
    private int debug;
    private int cacheTime;
    private String context;
    private String api;
    private String host;

    public IxContext (Application app) {
        this.app = app;
    }

    private void init () throws Exception {
        String h = app.configuration().getString(IX_HOME);
        if (h != null) {
            home = new File (h);
            if (!home.exists())
                home.mkdirs();
        }

        if (!home.exists())
            throw new IllegalArgumentException
                (IX_HOME+" \""+h+"\" is not accessible!");
        Logger.info("## "+IX_HOME+": \""+home.getCanonicalPath()+"\"");

        cache = getFile (IX_CACHE_BASE, "cache");
        cacheTime = app.configuration().getInt(IX_CACHE_TIME, 60);
        Logger.info("## "+IX_CACHE_TIME+": "+cacheTime+"s");

        text = getFile (IX_TEXT_BASE, "text");
        h2 = getFile (IX_H2_BASE, "h2");
        payload = getFile (IX_PAYLOAD_BASE, "payload");
        
        Integer level = app.configuration().getInt(IX_DEBUG);
        if (level != null)
            this.debug = level;
        Logger.info("## "+IX_DEBUG+": "+debug); 

        DatabaseMetaData meta = DB.getConnection().getMetaData();
        Logger.info("## Database vendor: "+meta.getDatabaseProductName()
                    +" "+meta.getDatabaseProductVersion());

        host = app.configuration().getString("application.host");
        if (host == null || host.length() == 0) {
            host = null;
        }
        else {
            int pos = host.length();
            while (--pos > 0 && host.charAt(pos) == '/')
                ;
            host = host.substring(0, pos+1);
        }
        Logger.info("## Application host: "+host);

        context = app.configuration().getString("application.context");
        if (context == null) {
            context = "";
        }
        else {
            int pos = context.length();
            while (--pos > 0 && context.charAt(pos) == '/')
                ;
            context = context.substring(0, pos+1);
        }
        Logger.info("## Application context: "+context);

        api = app.configuration().getString("application.api");
        if (api == null)
            api = "/api";
        else if (api.charAt(0) != '/')
            api = "/"+api;
        Logger.info("## Application api context: "
                    +((host != null ? host : "") + context+api));
    }

    File getFile (String var, String def) throws IOException {
        String name = app.configuration().getString(var);
        File f = null;
        if (name != null) {
            f = new File (name);
        }
        else {
            f = new File (home, def);
        }
        f.mkdirs();
        Logger.info("## "+var+": \""+f.getCanonicalPath()+"\"");
        return f;
    }

    public void onStart () {
        Logger.info("Loading plugin "+getClass().getName()+"...");        
        try {
            init ();
        }
        catch (Exception ex) {
            Logger.trace("Can't initialize app", ex);
        }
    }

    public void onStop () {
        Logger.info("Stopping plugin "+getClass().getName());
    }

    public boolean enabled () { return true; }
    
    public File home () { return home; }
    public File cache () { return cache; }
    public File text () { return text; }
    public File h2 () { return h2; }
    public File payload () { return payload; }
    
    public int cacheTime () { return cacheTime; }
    public boolean debug (int level) { return debug >= level; }
    public String context () { return context; }
    public String api () { return api; }
    public String host () { return host; }
}
