package ix.core.controllers;

import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.*;
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
    public static final FirebaseStore _firebase =
        _app.plugin(FirebaseStore.class);

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
    } // CachableContent 

    public static class SearchResultContext /*implements Serializable*/ {
        public enum Status {
            Pending,
            Running,
            Done,
            Failed
        }

        public Status status = Status.Pending;
        public String mesg;
        public Long start;
        public Long stop;
        public List results = new CopyOnWriteArrayList ();
        public String id = randvar (10);
        public Integer total;

        transient Set<String> keys = new HashSet<String>();
        transient ReentrantLock lock = new ReentrantLock ();

        public SearchResultContext () {
        }

        public SearchResultContext (SearchResult result) {
            id = result.getKey();
            start = result.getTimestamp();          
            total = result.count();
            if (result.finished()) {
                stop = result.getStopTime();
                setStatus (Status.Done);
            }
            else if (result.size() > 0)
                status = Status.Running;
            
            // prevent setStatus from caching this context with results
            // set
            results = result.getMatches();            
            if (status != Status.Done) {
                mesg = String.format
                    ("Loading...%1$d%%",
                     (int)(100.*result.size()/result.count()+0.5));
            }
        }

        public String getId () { return id; }
        public Status getStatus () { return status; }
        public void setStatus (Status status) { 
            this.status = status; 
            if (status == Status.Done) {
                if (total == null)
                    total = getCount ();
                // update cache
                for (String k : keys)
                    IxCache.set(k, this);
                
                // only update the cache if the instance in the cache
                //  is stale
                IxCache.setIfNewer(id, this, start);
            }
        }
        public String getMessage () { return mesg; }
        public void setMessage (String mesg) { this.mesg = mesg; }
        public Integer getCount () { return results.size(); }
        public Integer getTotal () { return total; }
        public Long getStart () { return start; }
        public Long getStop () { return stop; }
        public boolean finished () {
            return status == Status.Done || status == Status.Failed;
        }
        public void updateCacheWhenComplete (String... keys) {
            for (String k : keys)
                this.keys.add(k);
        }
        
        @com.fasterxml.jackson.annotation.JsonIgnore
        public List getResults () { return results; }
        public void add (Object obj) {
            lock.lock();
            try {
                results.add(obj);
            }
            finally {
                lock.unlock();
            }
        }

        private void writeObject(java.io.ObjectOutputStream out)
            throws IOException {
            lock.lock();
            try {
                out.defaultWriteObject();
            }
            finally {
                lock.unlock();
            }
        }
        
        private void readObject(java.io.ObjectInputStream in)
            throws IOException, ClassNotFoundException {
            if (lock == null)
                lock = new ReentrantLock ();
            
            lock.lock();
            try {
                in.defaultReadObject();
            }
            finally {
                lock.unlock();
            }
        }
        
        private void readObjectNoData() throws ObjectStreamException {
        }
    } // SearchResultContext

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

    public static String randvar (int size) {
        return Util.randvar(size, request ());
    }

    public static String randvar () {
        return randvar (5);
    }
}
