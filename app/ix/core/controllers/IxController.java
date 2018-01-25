package ix.core.controllers;

import java.io.Serializable;
import java.util.concurrent.Callable;

import play.Play;
import play.Logger;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Results;
import play.Application;
import play.libs.Json;

import com.fasterxml.jackson.databind.JsonNode;

import ix.utils.Util;
import ix.core.plugins.*;
import ix.core.search.TextIndexer;
import ix.seqaln.SequenceIndexer;
import static ix.core.search.TextIndexer.*;
import tripod.chem.indexer.StructureIndexer;


public class IxController extends Controller {
    public static Application _app = Play.application();
    public static final IxContext _ix = _app.plugin(IxContext.class);
    public static final PersistenceQueue _pq =
        _app.plugin(PersistenceQueue.class);
    public static final TextIndexer _textIndexer = 
        _app.plugin(TextIndexerPlugin.class).getIndexer();
    public static final StructureIndexer _strucIndexer =
        _app.plugin(StructureIndexerPlugin.class).getIndexer();
    public static final SequenceIndexer _seqIndexer =
        _app.plugin(SequenceIndexerPlugin.class).getIndexer();
    public static final PayloadPlugin _payloader =
        _app.plugin(PayloadPlugin.class);

    static public class CachableContent 
        implements play.twirl.api.Content, Serializable {
        // change this value when the class evolve so as to invalidate
        // any cached instance
        static final long serialVersionUID = 0x2l;
        final String type;
        final String body;
        final String sha1;
        
        public CachableContent (play.twirl.api.Content c) {
            type = c.contentType();
            body = c.body();
            sha1 = Util.sha1(body);
        }

        public CachableContent (JsonNode json) {
            type = "application/json";
            body = Json.stringify(json);
            sha1 = Util.sha1(body);
        }
        
        public String contentType () { return type; }
        public String body () { return body; }
        public String etag () { return sha1; }
        public Result ok () {
            String ifNoneMatch = request().getHeader("If-None-Match");
            if (ifNoneMatch != null
                && (ifNoneMatch.equals(sha1) || "*".equals(ifNoneMatch)))
                return Results.status(304);
            
            response().setHeader(ETAG, sha1);
            return Results.ok(this);
        }
        
        static public Object wrapIfContent (Object obj) {
            if (obj instanceof play.twirl.api.Content) {
                obj = new CachableContent ((play.twirl.api.Content)obj);
            }
            return obj;
        }
        
        static public CachableContent wrap (play.twirl.api.Content c) {
            return new CachableContent (c);
        }
        
        static public CachableContent wrap (JsonNode json) {
            return new CachableContent
                (new play.twirl.api.JavaScript(json.toString()));
        }
    }

    public static Result getEtag (String key, Callable<Result> callable)
        throws Exception {
        String ifNoneMatch = request().getHeader("If-None-Match");
        if (ifNoneMatch != null
            && ifNoneMatch.equals(key) && IxCache.contains(key))
            return status (304);

        response().setHeader(ETAG, key);
        return getOrElse_ (key, callable);
    }
    
    public static <T> T getOrElse (String key, Callable<T> callable)
        throws Exception {
        return getOrElse (_textIndexer.lastModified(), key, callable);
    }

    public static <T> T getOrElse_ (String key, Callable<T> callable)
        throws Exception {
        String refresh = request().getQueryString("refresh");
        if (refresh != null
            && ("true".equalsIgnoreCase(refresh)
                || "yes".equalsIgnoreCase(refresh)
                || "y".equalsIgnoreCase(refresh))) {
            IxCache.remove(key);
        }
        
        return getOrElse (_textIndexer.lastModified(), key, callable);
    }
    
    public static <T> T getOrElse (long modified,
                                   String key, Callable<T> callable)
        throws Exception {
        return IxCache.getOrElse(modified, key, callable);
    }
}
