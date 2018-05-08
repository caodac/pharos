package ix.core.controllers;

import java.io.*;
import java.security.*;
import java.util.*;
import java.net.URLDecoder;
import java.util.regex.*;
import java.util.concurrent.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.lang.reflect.ParameterizedType;
import javax.persistence.Entity;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.*;

import play.libs.Json;
import play.*;
import play.db.ebean.*;
import play.data.*;
import play.mvc.*;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;

import com.avaje.ebean.*;
import com.avaje.ebean.annotation.Transactional;
import com.avaje.ebean.event.BeanPersistListener;

import ix.core.models.ETag;
import ix.core.models.ETagRef;
import ix.core.models.Edit;
import ix.core.models.Principal;
import ix.core.models.BeanViews;
import ix.core.models.Curation;
import ix.core.models.Value;
import ix.core.models.Keyword;
import ix.core.models.XRef;
import ix.core.models.Publication;
import ix.core.search.TextIndexer;
import ix.core.plugins.TextIndexerPlugin;
import ix.core.adapters.EntityPersistAdapter;
import ix.core.adapters.BeanInterceptor;
import ix.utils.Util;
import ix.utils.Global;

public class EntityFactory extends IxController {
    static final SecureRandom rand = new SecureRandom ();

    static final ExecutorService _threadPool = 
        Executors.newCachedThreadPool();

    static final Model.Finder<Long, Principal> _principalFinder = 
        new Model.Finder(Long.class, Principal.class);

    static protected class Reindexer<K,T> implements BeanInterceptor {
        static final ReentrantLock lock = new ReentrantLock ();
        static Reindexer reindexer;

        long start;
        long total;
        AtomicLong count = new AtomicLong ();
        String current;
        Model.Finder<K,T> finder;

        protected Reindexer (Model.Finder<K,T> finder) {
            total = finder.findRowCount();
            start = System.currentTimeMillis();
            Logger.debug(EntityFactory.class.getName()
                         +": Reindexing started on "+new java.util.Date()
                         +" for "+total+" entities!");
            this.finder = finder;
        }

        public static <K,T> Reindexer<K,T>
            getInstance (Model.Finder<K,T> finder) {
            if (!lock.isLocked()) { // can only run once per session!
                lock.lock();
                reindexer = new Reindexer (finder);
                _threadPool.submit(new Runnable () {
                        public void run () {
                            try {
                                reindexer.reindex();
                            }
                            catch (Exception ex) {
                                Logger.error(Thread.currentThread()
                                             +": reindex error!", ex);
                            }
                        }
                    });
            }
            return reindexer;
        }

        /*
        @Override
        public void postLoad (Object bean) {
            try {
            }
            catch (Exception ex) {
                Logger.error("Can't entity id "+bean, ex);
            }
        }
        */
        
        public long count () { return count.get(); }
        public long start () { return start; }
        public long total () { return total; }
        public String current () { return current; }

        void reindex () throws Exception {
            //EntityPersistAdapter.getInstance().add(this);
            for (T e : finder.all()) {
                try {
                    // work being done inside postLoad..
                    _textIndexer.add(e);
                    count.getAndIncrement();
                    current = e.getClass().getName()+":"+Util.getId(e);
                    Logger.info("Reindexing entity "+current+"..."+count.get());
                }
                catch (Exception ex) {
                    Logger.error("Can't reindex entity "
                                 +e.getClass().getName()+" "+Util.getId(e), ex);
                }
            }
            //EntityPersistAdapter.getInstance().remove(this);
            _textIndexer.flush();
        }
    } // Reindexer
    
    public static class FetchOptions {
        public int top;
        public int skip;
        public String filter;
        public List<String> expand = new ArrayList<String>();
        public List<String> order = new ArrayList<String>();
        public List<String> select = new ArrayList<String>();

        // only in the context of a request
                        
        public FetchOptions () {
            for (Map.Entry<String, String[]> me
                     : request().queryString().entrySet()) {
                String param = me.getKey();
                if ("order".equalsIgnoreCase(param)) {
                    for (String s : me.getValue()) {
                        if (Util.isValidFieldName(s))
                            order.add(s);
                        else 
                            Logger.warn("Bogus order field: "+s);
                    }
                }
                else if ("expand".equalsIgnoreCase(param)) {
                    for (String s : me.getValue()) {
                        if (Util.isValidFieldName(s))
                            expand.add(s);
                        else
                            Logger.warn("Bogus expand field: "+s);
                    }
                }
                else if ("select".equalsIgnoreCase(param)) {
                    for (String s : me.getValue()) {
                        if (Util.isValidFieldName(s))
                            select.add(s);
                        else
                            Logger.warn("Bogus select field: "+s);
                    }
                }
                else if ("top".equalsIgnoreCase(param)) {
                    String n = me.getValue()[0];
                    try {
                        top = Integer.parseInt(n);
                    }
                    catch (NumberFormatException ex) {
                        Logger.trace("Bogus top value: "+n, ex);
                    }
                }
                else if ("skip".equalsIgnoreCase(param)) {
                    String n = me.getValue()[0];
                    try {
                        skip = Integer.parseInt(n);
                    }
                    catch (NumberFormatException ex) {
                        Logger.trace("Bogus skip value:"+n, ex);
                    }
                }
                else if ("filter".equalsIgnoreCase(param)) {
                    filter = me.getValue()[0];
                }
            }
        }
        
        public FetchOptions (int top, int skip, String filter) {
            try {
                for (Map.Entry<String, String[]> me
                         : request().queryString().entrySet()) {
                    String param = me.getKey();
                    if ("order".equalsIgnoreCase(param)) {
                        for (String s : me.getValue()) {
                            if (Util.isValidFieldName(s))
                                order.add(s);
                            else
                                Logger.warn("Bogus order field: "+s);
                        }
                    }
                    else if ("expand".equalsIgnoreCase(me.getKey())) {
                        for (String s : me.getValue()) {
                            if (Util.isValidFieldName(s))
                                expand.add(s);
                            else
                                Logger.warn("Bogus expand field: "+s);
                        }
                    }
                }
            } 
            catch (Exception ex) {
                ex.printStackTrace();
            }
            this.top = top;
            this.skip = skip;
            this.filter = filter;
        }

        public String toString () {
            return "FetchOptions{top="+top+",skip="+skip
                +",expand="+expand.size()
                +",filter="+filter+",order="+order.size()+"}";
        }
    }

    protected static class JsonldListSerializer extends StdSerializer<List> {
        JsonldListSerializer () {
            this (null);
        }
        
        JsonldListSerializer (Class<List> t) {
            super (t);
        }
        
        @Override
        public void serialize (List values, JsonGenerator jgen,
                               SerializerProvider provider) 
            throws IOException {
            Map<String, List<Value>> groups = new TreeMap<>();
            Map<String, List<XRef>> links = new TreeMap<>();
            Map<String, List<Publication>> pubs = new TreeMap<>();
            for (Object v : values) {
                if (v instanceof Value) {
                    Value val = (Value)v;
                    List<Value> g = groups.get(val.label);
                    if (g == null)
                        groups.put(val.label, g = new ArrayList<>());
                    g.add(val);
                }
                else if (v instanceof XRef) {
                    // links
                    XRef ref = (XRef)v;
                    List<XRef> refs = links.get(ref.kind);
                    if (refs == null)
                        links.put(ref.kind, refs = new ArrayList<>());
                    refs.add(ref);
                }
                else if (v instanceof Publication) {
                    Publication pub = (Publication)v;
                    List<Publication> pl = pubs.get(pub.title);
                    if (pl == null)
                        pubs.put(pub.title, pl = new ArrayList<>());
                    pl.add(pub);
                }
            }
            
            if (groups.isEmpty()) {
                if (!links.isEmpty())
                    serializeLinks (links, jgen, provider);
                else if (!pubs.isEmpty())
                    serializePubs (pubs, jgen, provider);
                else if (!values.isEmpty()) {
                    //Logger.debug("DEFAULT SERIALIZATION "+values);
                    provider.defaultSerializeValue(values, jgen);
                }
                else {
                    jgen.writeStartArray();
                    jgen.writeEndArray();
                }
            }
            else {
                serialize (groups, jgen, provider);
            }
        }

        void serializeLinks (Map<String, List<XRef>> links, JsonGenerator jgen,
                             SerializerProvider provider) throws IOException {
            jgen.writeStartObject();            
            for (Map.Entry<String, List<XRef>> me : links.entrySet()) {
                jgen.writeFieldName(me.getKey());
                List<XRef> refs = me.getValue();
                if (refs.size() == 1) {
                    serializeXRef (refs.get(0), jgen, provider); 
                }
                else {
                    jgen.writeStartArray();
                    for (XRef xref : refs) {
                        serializeXRef (xref, jgen, provider);
                    }
                    jgen.writeEndArray();
                }
            }
            jgen.writeEndObject();
        }

        void serializeXRef (XRef xref, JsonGenerator jgen,
                            SerializerProvider provider) throws IOException {
            Map<String, List<Value>> groups = new TreeMap<>();
            for (Value v : xref.properties) {
                List<Value> vals = groups.get(v.label);
                if (vals == null)
                    groups.put(v.label, vals = new ArrayList<>());
                vals.add(v);
            }
            jgen.writeStartObject();
            jgen.writeNumberField("id", xref.id);
            jgen.writeNumberField("version", xref.version);
            jgen.writeStringField("refid", xref.refid);
            jgen.writeBooleanField("deprecated", xref.deprecated);
            jgen.writeFieldName("properties");
            serialize (groups, jgen, provider);
            jgen.writeStringField("href", xref.getHRef());
            jgen.writeEndObject();
        }

        void serializePubs (Map<String, List<Publication>> pubs,
                            JsonGenerator jgen,
                            SerializerProvider provider) throws IOException {
            jgen.writeStartObject();            
            for (Map.Entry<String, List<Publication>> me : pubs.entrySet()) {
                jgen.writeFieldName(me.getKey());
                List<Publication> publist = me.getValue();
                if (publist.size() == 1) {
                    serializePub (publist.get(0), jgen, provider);
                }
                else {
                    jgen.writeStartArray();
                    // avoid recursive serialization by provide condensed
                    // version
                    for (Publication p : publist) {
                        serializePub (p, jgen, provider);
                    }
                    jgen.writeEndArray();
                }
            }
            jgen.writeEndObject();
        }

        void serializePub (Publication p, JsonGenerator jgen,
                           SerializerProvider provider) throws IOException {
            jgen.writeStartObject();
            jgen.writeNumberField("id", p.id);
            if (p.pmid != null)
                jgen.writeNumberField("pmid", p.pmid);
            if (p.year != null)
                jgen.writeNumberField("year", p.year);
            if (p.abstractText != null)
                jgen.writeStringField("abstractText", p.abstractText);
            jgen.writeEndObject();  
        }
        
        void serialize (Map<String, List<Value>> groups,
                        JsonGenerator jgen, SerializerProvider provider)
            throws IOException {
            //Logger.debug(getClass().getName()+": "+groups);
            jgen.writeStartObject();
            for (Map.Entry<String, List<Value>> me : groups.entrySet()) {
                jgen.writeFieldName(me.getKey());
                List<Value> vals = me.getValue();
                if (vals.size() == 1) {
                    provider.defaultSerializeValue(vals.get(0), jgen);
                }
                else {
                    jgen.writeStartArray();
                    for (Value v : vals) {
                        provider.defaultSerializeValue(v, jgen);
                    }
                    jgen.writeEndArray();
                }
            }
            jgen.writeEndObject();
        }
    }
    
    protected static class JsonldValueSerializer extends StdSerializer<Value> {
        JsonldValueSerializer () {
            this (null);
        }
        
        JsonldValueSerializer (Class<Value> t) {
            super (t);
        }
        
        @Override
        public void serialize
            (Value value, JsonGenerator jgen, SerializerProvider provider) 
            throws IOException {
            jgen.writeStartObject();
            jgen.writeNumberField("id", value.id);
            if (value instanceof Keyword) {
                Keyword kw = (Keyword)value;
                jgen.writeStringField("term", kw.term);
                if (kw.href != null)
                    jgen.writeStringField("href", kw.href);
            }
            else {
                Object val = value.getValue();
                if (val == null)
                    ;
                else if (val instanceof Number) {
                    if (val instanceof Float || val instanceof Double)
                        jgen.writeNumberField
                            ("value", ((Number)val).doubleValue());
                    else
                        jgen.writeNumberField
                            ("value", ((Number)val).longValue());
                }
                else if (val instanceof String)
                    jgen.writeStringField("value", val.toString());
                else {
                    jgen.writeFieldName("value");
                    jgen.writeStartObject();
                    jgen.writeObject(value.getValue());
                    jgen.writeEndObject();
                }
            }
            jgen.writeEndObject();
        }
    }

    protected static <K,T> List<T> all (Model.Finder<K, T> finder) {
        return finder.all();
    }


    static public class EntityMapper extends ObjectMapper {
        public EntityMapper (Class<?>... views) {
            configure (MapperFeature.DEFAULT_VIEW_INCLUSION, true);
            _serializationConfig = getSerializationConfig();
            for (Class v : views) {
                _serializationConfig = _serializationConfig.withView(v);
            }
        }
        
        public EntityMapper () {
        }

        public String toJson (Object obj) {
            return toJson (obj, false);
        }
        
        public String toJson (Object obj, boolean pretty) {
            try {
                return pretty
                    ? writerWithDefaultPrettyPrinter().writeValueAsString(obj)
                    : writeValueAsString (obj);
            }
            catch (Exception ex) {
                Logger.trace("Can't write Json", ex);
            }
            return null;
        }
    }

    protected static <K,T> List<T> filter (FetchOptions options,
                                           Model.Finder<K, T> finder) {

        try {
            Logger.debug(request().uri()+": "+options);
        } catch (Exception e) {
                Logger.debug("non-request-bound: "+options);
        }
        Query<T> query = finder.query();
        
        for (String path : options.expand) {
            Logger.debug("  -> fetch "+path);
            query = query.fetch(path);
        }
        query = query.where(options.filter);

        if (!options.order.isEmpty()) {
            for (String order : options.order) {
                Logger.debug("  -> order "+order);
                char ch = order.charAt(0);
                if (ch == '$') { // desc
                    query = query.order(order.substring(1)+" desc");
                }
                else {
                    if (ch == '^') {
                        order = order.substring(1);
                    }
                    // default to asc
                    query = query.order(order+" asc");
                }
            }
        }
        else {
            //query = query.orderBy("id asc");
        }

        try {
            long start = System.currentTimeMillis();
            List<T> results = query
                .setFirstRow(options.skip)
                .setMaxRows(options.top)
                .findList();
            Logger.debug(" => "+results.size()
                         +" in "+(System.currentTimeMillis()-start)+"ms");

            return results;
        }
        catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException ("Can't execute query "+ex.getMessage());
        }
    }

    protected static <K,T> Result page (final int top, final int skip, 
                                        final String filter,
                                        final Model.Finder<K, T> finder) {
        final String key = Util.sha1(request());
        try {
            CachableContent content = getOrElse 
                (key, new Callable<CachableContent> () {
                    public CachableContent call () throws Exception {
                        Logger.debug("** page: cache created "+key+" **");
                        return pageContent (top, skip, filter, finder);
                    }
                });
            return content.ok();
        }
        catch (Exception ex) {
            return internalServerError ("Unable to page: "
                                        +Global.getHost()+request().uri());
        }
    }

    protected static <K,T> CachableContent pageContent
        (int top, int skip, String filter, final Model.Finder<K, T> finder) {
        return new CachableContent (pageJson (top, skip, filter, finder));
    }

    protected static <K,T> JsonNode pageJson 
        (int top, int skip, String filter, final Model.Finder<K, T> finder) {
        //if (select != null) finder.select(select);
        final FetchOptions options = new FetchOptions (top, skip, filter);
        List<T> results = filter (options, finder);

        final ETag etag = new ETag ();
        etag.top = options.top;
        etag.skip = options.skip;
        etag.query = canonicalizeQuery (request ());
        etag.count = results.size();
        etag.uri = Global.getHost()+request().uri();
        etag.path = request().path();
        // only include query parameters that fundamentally alters the
        // number of results
        etag.sha1 = Util.sha1(request(), "filter");
        etag.method = request().method();
        etag.filter = options.filter;

        Logger.debug("** pageJson: "+etag.uri);

        if (options.filter == null)
            etag.total = finder.findRowCount();
        else {
            Model.Finder<Long, ETag> eFinder = 
                new Model.Finder(Long.class, ETag.class);
            List<ETag> etags = eFinder
                .where().eq("sha1", etag.sha1)
                .orderBy("modified desc").setMaxRows(1).findList();

            if (!etags.isEmpty()) {
                ETag e = etags.iterator().next();
                Logger.debug(">> cached "+etag.sha1+" from ETag "+e.etag);
                etag.total = e.total;
            }
            else {
                // TODO: Need to use Akka here!
                // execute in the background to determine the actual number
                // of rows that this query should return
                _threadPool.submit(new Runnable () {
                        public void run () {
                            FutureIds<T> future = 
                                finder.where(options.filter).findFutureIds();
                            try {
                                List<Object> ids = future.get();
                                etag.total = ids.size();
                                etag.save();
                                
                                for (Object id : ids) {
                                    ETagRef ref = new ETagRef (etag, (Long)id);
                                    ref.save();
                                }
                                Logger.debug(Thread.currentThread().getName()
                                             +": "+options.filter+" => "
                                             +ids.size());
                            }
                            catch (Exception ex) {
                                ex.printStackTrace();
                                Logger.trace(Thread.currentThread().getName()
                                             +": ETag "+etag.id, ex);
                            }
                        }
                    });
            }
        }
        
        try{
            etag.save();
        }
        catch (Exception e) {
            Logger.error
                ("Error saving etag. This sometimes happens on empty DB");
        }

        ObjectMapper mapper = getEntityMapper ();
        ObjectNode obj = (ObjectNode)mapper.valueToTree(etag);
        obj.put("content", mapper.valueToTree(results));

        return obj;
    }

    static String canonicalizeQuery (Http.Request req) {
        Map<String, String[]> queries = req.queryString();
        Set<String> keys = new TreeSet<String>(queries.keySet());
        StringBuilder q = new StringBuilder ();
        for (String key : keys) {
            if (q.length() > 0)
                q.append('&');

            String[] values = queries.get(key);
            Arrays.sort(values);
            if (values != null && values.length > 0) {
                q.append(key+"="+values[0]);
            }
        }
        return q.toString();
    }

    static public EntityMapper getEntityMapper () {
        List<Class> views = new ArrayList<Class>();

        Map<String, String[]> params = request().queryString();
        String[] args = params.get("view");
        if (args != null) {
            Class[] classes = BeanViews.class.getClasses();
            for (String a : args) {
                int matches = 0;
                for (Class c : classes) {
                    if (a.equalsIgnoreCase(c.getSimpleName())) {
                        views.add(c);
                        ++matches;
                    }
                }

                if (matches == 0)
                    Logger.warn("Unsupported view: "+a);
            }
        }
        else {
            views.add(BeanViews.Compact.class);
        }

        EntityMapper mapper = new EntityMapper (views.toArray(new Class[0]));
        args = params.get("format");
        if (args != null && ("jsonld".equalsIgnoreCase(args[0])
                             || "json-ld".equalsIgnoreCase(args[0]))) {
            SimpleModule module = new SimpleModule ();
            module.addSerializer(Value.class, new JsonldValueSerializer ());
            module.addSerializer(List.class, new JsonldListSerializer());
            mapper.registerModule(module);
        }
        return mapper;
    }

    protected static <K,T> T getEntity (K id, Model.Finder<K, T> finder) {
        return finder.byId(id);
    }
    
    protected static <K,T> List<T> filter (String filter, 
                                           Model.Finder<K, T> finder) {
        return finder.where(filter).findList();
    }

    /**
     * filter by example
     */
    protected static <T> List<T> filter (T instance, 
                                         Model.Finder<Long, T> finder) {
        if (instance == null)
            throw new IllegalArgumentException ("Instance is null");

        Map<String, Object> cons = new HashMap<String, Object>();
        for (Field f : instance.getClass().getFields()) {
            try {
                Object value = f.get(instance);
                Class type = f.getType();
                if (value != null && type.isPrimitive()) {
                    cons.put(f.getName(), value);
                }
            }
            catch (Exception ex) {
                Logger.trace("Unable to retrieve field "+f.getName(), ex);
            }
        }

        List results = new ArrayList ();
        if (cons.isEmpty()) {
            Logger.warn("Can't filter by example because "+instance.getClass()
                        +" doesn't contain any primitive field!");
        }
        else {
            results = finder.where().allEq(cons).findList();
        }
        return results;
    }

    protected static <K,T> List<T> filter (JsonNode json, int top, int skip,
                                           Model.Finder<K, T> finder) {
        if (json == null)
            throw new IllegalArgumentException ("Json is null");

        Map<String, Object> cons = new HashMap<String, Object>();
        for (Iterator<String> it = json.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            JsonNode n = json.get(field);
            if (n != null && n.isValueNode() && !n.isNull()) {
                if (n.isNumber()) cons.put(field, n.numberValue());
                else if (n.isTextual()) cons.put(field, n.textValue());
            }
        }

        List results = new ArrayList ();
        if (cons.isEmpty()) {
            Logger.warn("Can't filter by example because JSON"
                        +" doesn't contain any primitive field!");
        }
        else {
            results = finder.where()
                .allEq(cons)
                //.orderBy("id asc")
                .setFirstRow(skip)
                .setMaxRows(top)
                .findList();
        }
        return results;
    }

    protected static <K,T> Result get (K id, Model.Finder<K, T> finder) {
        return get (id, null, finder);
    }

    protected static <K,T> Result doc (K id, Model.Finder<K, T> finder) {
        try {
            T inst = finder.byId(id);
            if (inst != null) {
                return ok (_textIndexer.getDocJson(inst));
            }
            return notFound ("Bad request: "+request().uri());
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError
                (request().uri()+": "+ex.getMessage());
        }
    }

    protected static <K,T> Result get (K id, String expand, 
                                       Model.Finder<K, T> finder) {
        final String key = Util.sha1(request ());
        try {
            CachableContent content = getOrElse
                (key, new Callable<CachableContent> () {
                    public CachableContent call () throws Exception {
                        ObjectMapper mapper = getEntityMapper ();
                        JsonNode data = null;
                        if (expand != null && !"".equals(expand)) {
                            Query<T> query = finder.query();
                            Logger.debug(request().uri()+": expand="+expand);
                            
                            StringBuilder path = new StringBuilder ();
                            for (String p : expand.split("\\.")) {
                                if (path.length() > 0)
                                    path.append('.');
                                path.append(p);
                                Logger.debug("  -> fetch "+path);
                                query = query.fetch(path.toString());
                            }
                            
                            T inst = query.setId(id).findUnique();
                            if (inst != null) {
                                data = mapper.valueToTree(inst);
                            }
                        }
                        else {
                            T inst = finder.byId(id);
                            if (inst != null) {
                                data = mapper.valueToTree(inst);
                            }
                        }
                        
                        return data != null ? new CachableContent (data) : null;
                    }
                });

            return content != null ? content.ok() 
                : notFound ("Bad request: "+request().uri());
        }
        catch (Exception ex) {
            Logger.error(request().uri()+": can't fetch entity", ex);
            return internalServerError 
                ("Unable to retrieve entity: "+request().uri());
        }
    }

    protected static <K,T> Result resolve (final Expression filter, 
                                           final String expand,
                                           final Model.Finder<K,T> finder) {
        final String key = Util.sha1(request ());
        try {
            CachableContent content = getOrElse 
                (key, new Callable<CachableContent> () {
                    public CachableContent call () throws Exception {
                        ObjectMapper mapper = getEntityMapper ();
                        JsonNode data = null;
                        Logger.debug("## Resolving..."+request().uri());

                        if (expand != null && !"".equals(expand)) {
                            Query<T> query = finder.query();
                            Logger.debug(request().uri()+": expand="+expand);
                            
                            StringBuilder path = new StringBuilder ();
                            for (String p : expand.split("\\.")) {
                                if (path.length() > 0)
                                    path.append('.');
                                path.append(p);
                                Logger.debug("  -> fetch "+path);
                                query = query.fetch(path.toString());
                            }
                            
                            T inst = query.where(filter)
                                .setMaxRows(1).findUnique();
                            if (inst != null) {
                                data = mapper.valueToTree(inst);
                            }
                        }
                        else {
                            T inst = finder.where(filter)
                                .setMaxRows(1).findUnique();
                            if (inst != null) {
                                data = mapper.valueToTree(inst);
                            }
                        }

                        return data != null ? new CachableContent (data) : null;
                    }
                });
        
            return content != null ? content.ok() 
                : notFound ("Bad request: "+request().uri());
        }
        catch (Exception ex) {
            Logger.trace(request().uri()+": can't fetch entity!", ex);
            return internalServerError
                ("Unable to fetch entity "+request().uri());
        }
    }

    protected static <K,T> Result count (Model.Finder<K, T> finder) {
        try {
            return ok (String.valueOf(getCount (finder)));
        }
        catch (Exception ex) {
            return internalServerError
                (request().uri()+": can't get count");
        }
    }

    protected static <K,T> Integer getCount (Model.Finder<K, T> finder) 
        throws InterruptedException, ExecutionException {
        FutureRowCount<T> count = finder.findFutureRowCount();
        return count.get();
    }
    
//    protected static Integer getCount () 
//            throws InterruptedException, ExecutionException {
//            //FutureRowCount<T> count = finder.findFutureRowCount();
//            return 0;
//    }

    protected static <K,T> Result field (K id, String field, 
                                         Model.Finder<K, T> finder) {
        try {
            Logger.debug("id: "+id+" field: "+field);
            T inst = finder.byId(id);
            //query.setId(id).findUnique();
            if (inst == null) {
                return notFound ("Bad request: "+request().uri());
            }
            return field (inst, field);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

    static boolean eval (Object inst, String field, String value) {
        try {
            Field f = inst.getClass().getField(field);
            Object val = f.get(inst);
            if (Number.class.isAssignableFrom(val.getClass())) {
                return ((Number)val).longValue() == Long.parseLong(value);
            }
            else {
                String s = val.toString();
                int index = value.indexOf('*');
                if (index >= 0) {
                    // regex
                    String regex = value.substring(0, index);
                    regex = regex + ".*";
                    if (index+1 < value.length()) {
                        regex +=  value.substring(index+1);
                    }
                    //Logger.debug("regex: "+field+"="+regex);
                    Pattern p = Pattern.compile
                        (regex, Pattern.CASE_INSENSITIVE);
                    Matcher m = p.matcher(s);
                    return m.find();
                }
                return value.equalsIgnoreCase(s);
            }
        }
        catch (Exception ex) {
            return false;
        }
    }
    
    protected static <T> Result field (Object inst, String field) {
        /*
        Query<T> query = finder.query();
        int depth = 0;
        if (field != null) {
            StringBuilder path = new StringBuilder ();
            for (String p : field.split("[\\(0-9\\)\\/]+")) {
                if (path.length() > 0) path.append('.');
                path.append(p);
                ++depth;
            }
            if (depth > 1) {
                Logger.debug
                    (request().uri()+": field="+field+" => path="+path);
                query = query.fetch(path.toString());
            }
        }
        */
        String[] paths = field.split("/");
        // properties(0)
        Pattern regex1 = Pattern.compile("([^\\(]+)\\((-?\\d+)\\)");
        // properties(label=GO Function)
        Pattern regex2 = Pattern.compile("([^\\(]+)\\(([^=]+)=([^\\)]+)\\)");   
        StringBuilder uri = new StringBuilder ();

        boolean isRaw = paths[paths.length-1].charAt(0) == '$'; 
        int i = 0;
        Field f = null;
        Object obj = inst;
        
        for (; i < paths.length && obj != null; ++i) {
            String pname = paths[i]; // field name
            Integer pindex = null; // field index if field is a list
            String pcon = null, pval = null;
            
            Matcher matcher = regex1.matcher(pname);
            if (matcher.find()) {
                pname = matcher.group(1);
                pindex = Integer.parseInt(matcher.group(2));
            }
            else {
                matcher = regex2.matcher(pname);
                if (matcher.find()) {
                    pname = matcher.group(1);
                    pcon = matcher.group(2);
                    String v = matcher.group(3);
                    try {
                        pval = URLDecoder.decode(v, "utf-8");
                        pval = pval.replaceAll("['\"]","");
                    }
                    catch (Exception ex) {
                        Logger.error("Invalid value: '"+v+"'", ex);
                    }
                }
            }

            if (pname.charAt(0) == '$')
                pname = pname.substring(1);

            Logger.debug("obj="+obj+"["+obj.getClass()
                         +"] pname="+pname+" pindex="+pindex
                         +" pcon="+pcon+" pval="+pval);
            
            try {
                uri.append("/"+paths[i]);

                /**
                 * TODO: check for JsonProperty annotation!
                 */                
                f = obj.getClass().getField(pname);
                String fname = f.getName();
                Class<?> ftype = f.getType();
                
                Object val = f.get(obj);
                if (val != null) {
                    if (ftype.isArray()) {
                        if (pindex != null) {
                            if (pindex >= Array.getLength(val) || pindex < 0) {
                                return badRequest
                                    (uri+": array index out of bound "
                                     +pindex);
                            }
                            val = Array.get(val, pindex);
                        }
                        else if (pcon != null && pval != null) {
                            List vals = new ArrayList ();
                            for (int k = 0; k < Array.getLength(val); ++k) {
                                Object a = Array.get(val, k);
                                if (eval (a, pcon, pval))
                                    vals.add(a);
                            }
                            val = /*vals.size()==1? vals.get(0) :*/ vals;
                        }
                    }
                    else if (Collection.class.isAssignableFrom(ftype)) {
                        if (pindex != null) {
                            if (pindex >= ((Collection)val).size()
                                || pindex < 0)
                                return badRequest
                                    (uri+": list index out bound "+pindex);
                            val = ((Collection)val).toArray()[pindex];
                        }
                        else if (pcon != null && pval != null) {
                            List vals = new ArrayList ();
                            for (Object a : (Collection)val) {
                                if (eval (a, pcon, pval))
                                    vals.add(a);
                            }
                            
                            val = /*vals.size()==1 ?vals.get(0) :*/ vals;
                        }
                    }
                }
                obj = val;
            }
            catch (NoSuchFieldException ex) {
                // now try method..
                String method = "get"+pname;
                /*
                Logger.debug("Can't lookup field \""+pname
                             +"\"; trying method \""+method+"\"...");
                */
                Object old = obj;
                /**
                 * TODO: check for JsonProperty annotation!
                 */
                for (Method m : obj.getClass().getMethods()) {
                    if (m.getName().equalsIgnoreCase(method)) {
                        try {
                            Object val = m.invoke(obj);
                            if (val != null && pindex != null) {
                                Class<?> ftype = val.getClass();
                                if (ftype.isArray()) {
                                    if (pindex >= Array.getLength(val)) {
                                        return badRequest
                                            (uri+": array index out of bound "
                                             +pindex);
                                    }
                                    val = Array.get(val, pindex);
                                }
                                else if (Collection.class
                                         .isAssignableFrom(ftype)) {
                                    if (pindex >= ((Collection)val).size()
                                        || pindex < 0)
                                        return badRequest
                                            (uri+": list index out bound "
                                             +pindex);
                                    Iterator it = ((Collection)val).iterator();
                                    for (int k = 0; it.hasNext() 
                                             && k < pindex; ++k)
                                        ;
                                    val = it.next();
                                }
                            }
                            obj = val;
                            break; // don't 
                        }
                        catch (Exception e) {
                        }
                    }
                }

                if (old == obj) {
                    // last resort.. serialize as json and iterate from there
                    ObjectMapper mapper = new ObjectMapper ();
                    JsonNode node = mapper.valueToTree(obj).get(pname);
                    if (node == null) {
                        Logger.error(uri.toString()
                                     +": No method or field matching "
                                     +"requested path");
                        return notFound ("Invalid field path: "+uri);
                    }
                    
                    while (++i < paths.length && node != null) {
                        if (pindex != null) {
                            node = node.isArray() && pindex < node.size()
                                ? node.get(pindex) : null;
                        }
                        else {
                            uri.append("/"+paths[i]);
                            pname = paths[i]; // field name
                            pindex = null; // field index if field is a list
            
                            matcher = regex1.matcher(pname);
                            if (matcher.find()) {
                                pname = matcher.group(1);
                                pindex = Integer.parseInt(matcher.group(2));
                            }
                            else {
                                matcher = regex2.matcher(pname);
                                if (matcher.find()) {
                                    pname = matcher.group(1);
                                    pcon = matcher.group(2);
                                    pval = matcher.group(3);
                                }
                            }
                            
                            if (pname.charAt(0) == '$')
                                pname = pname.substring(1);
                            
                            node = node.get(pname);
                            if (node == null) {
                                Logger.error(uri.toString()
                                             +": No method or field matching "
                                             +"requested path");
                                return notFound ("Invalid field path: "+uri);
                            }
                        }
                    }

                    if (node == null)
                        return isRaw ? noContent () : ok ("null");
                    
                    return isRaw && !node.isContainerNode()
                        ? ok (node.asText()) : ok (node);
                }
            }
            catch (Exception ex) {
                Logger.error(uri.toString(), ex);
                return notFound ("Invalid field path: "+uri);
            }
        }
        
        if (i < paths.length) {
            return badRequest ("Path "+uri+" is null");
        }

        if (obj == null) {
            return isRaw ? noContent () : ok ("null");
        }
        
        ObjectMapper mapper = getEntityMapper ();
        JsonNode node = mapper.valueToTree(obj);
        if (isRaw && !node.isContainerNode()) {
            // make sure the content type is properly set if the
            // value is a json string
            JsonDeserialize json = (JsonDeserialize)
                f.getAnnotation(JsonDeserialize.class);
            Results.Status status = ok (node.asText());
            if (json != null && json.as() == JsonNode.class) {
                status.as("application/json");
            }
            
            return status;
        }
        return ok (node);
    }

    @Transactional
    protected static <K, T extends Model> 
        Result create (Class<T> type, Model.Finder<K, T> finder) {
        if (!request().method().equalsIgnoreCase("POST")) {
            return badRequest ("Only POST is accepted!");
        }

        String content = request().getHeader("Content-Type");
        if (content == null || (content.indexOf("application/json") < 0
                                && content.indexOf("text/json") < 0)) {
            return badRequest ("Mime type \""+content+"\" not supported!");
        }

        try {
            ObjectMapper mapper = new ObjectMapper ();
            mapper.addHandler(new DeserializationProblemHandler () {
                    public boolean handleUnknownProperty
                        (DeserializationContext ctx, JsonParser parser,
                         JsonDeserializer deser, Object bean, String property) {
                        try {
                            Logger.warn("Unknown property \""
                                        +property+"\" (token="
                                        +parser.getCurrentToken()
                                        +") while parsing "
                                        +bean+"; skipping it..");
                            parser.skipChildren();
                        }
                        catch (IOException ex) {
                            ex.printStackTrace();
                            Logger.error
                                ("Unable to handle unknown property!", ex);
                            return false;
                        }
                        return true;
                    }
                });
            
            JsonNode node = request().body().asJson();
            T inst = mapper.treeToValue(node, type);
            inst.save();

            return created ((JsonNode)mapper.valueToTree(inst));
        }
        catch (Exception ex) {
            return internalServerError (ex.getMessage());
        }
    }

    protected static <K,T extends Model> 
                                Result delete (K id, 
                                               Model.Finder<K, T> finder) {
        T inst = finder.ref(id);
        if (inst != null) {
            ObjectMapper mapper = getEntityMapper ();
            JsonNode node = mapper.valueToTree(inst);
            inst.delete();
            return ok (node);
        }
        return notFound (request().uri()+" not found");
    }

    protected static Result edits (Object id, Class<?>... cls) {
        for (Class<?> c : cls) {
            List<Edit> edits = EditFactory.finder.where
                (Expr.and(Expr.eq("refid", id.toString()),
                          Expr.eq("kind", c.getName())))
                .findList();
            if (!edits.isEmpty()) {
                ObjectMapper mapper = getEntityMapper ();
                return ok ((JsonNode)mapper.valueToTree(edits));
            }
        }

        return notFound (request().uri()+": No edit history found!");
    }

    protected static <K, T extends Model> Result update 
        (K id, String field, Class<T> type, Model.Finder<K, T> finder) {

        if (!request().method().equalsIgnoreCase("PUT")) {
            return badRequest ("Only PUT is accepted!");
        }

        String content = request().getHeader("Content-Type");
        if (content == null || (content.indexOf("application/json") < 0
                                && content.indexOf("text/json") < 0)) {
            return badRequest ("Mime type \""+content+"\" not supported!");
        }

        T obj = finder.ref(id);
        if (obj == null)
            return notFound ("Not a valid entity id="+id);

        Principal principal = null;
        if (request().username() != null) {
            principal = _principalFinder
                .where().eq("name", request().username())
                .findUnique();
            // create new user if doesn't exist
            /*
            if (principal == null) {
                principal = new Principal (request().username());
                principal.save();
            }
            */
        }

        /**
         * TODO: have a mechanism whereby the principal is verified
         * to her permission to update this field
         */

        Logger.debug("Updating "+obj.getClass()+": id="+id+" field="+field);
        try {
            ObjectMapper mapper = getEntityMapper ();
            JsonNode value = request().body().asJson();

            String[] paths = field.split("/");
            if (paths.length == 1 
                && (paths[0].equals("_") || paths[0].equals("*"))) {
                T inst = mapper.treeToValue(value, type);
                try {
                    Method m = inst.getClass().getMethod("setId");
                    m.invoke(obj, id);
                    inst.update();
                }
                catch (NoSuchMethodException ex) {
                    return internalServerError (ex.getMessage());
                }

                return created ((JsonNode)mapper.valueToTree(inst));
            }
            else {
                Object inst = obj;
                StringBuilder uri = new StringBuilder ();

                Pattern regex = Pattern.compile("([^\\(]+)\\((-?\\d+)\\)");

                List<Object[]> changes = new ArrayList<Object[]>();
                for (int i = 0; i < paths.length; ++i) {
                    Logger.debug("paths["+i+"/"+paths.length+"]:"+paths[i]);

                    String pname = paths[i]; // field name
                    Integer pindex = null; // field index if field is a list

                    Matcher matcher = regex.matcher(pname);
                    if (matcher.find()) {
                        pname = matcher.group(1);
                        pindex = Integer.parseInt(matcher.group(2));
                    }
                    Logger.debug("pname="+pname+" pindex="+pindex);
                    
                    try {
                        uri.append("/"+paths[i]);

                        Field f = inst.getClass().getField(pname);
                        String fname = f.getName();
                        String bname = getBeanName (fname);
                        Class<?> ftype = f.getType();

                        Object val = f.get(inst);
                        if (pindex != null) {
                            if (val == null) {
                                return internalServerError 
                                    ("Property \""+fname+"\" is null!");
                            }

                            if (ftype.isArray()) {
                                if (pindex >= Array.getLength(val) 
                                    || pindex < 0) {
                                    return badRequest
                                        (uri+": array index out of bound "
                                         +pindex);
                                }

                                val = Array.get(val, pindex);
                            }
                            else if (Collection
                                     .class.isAssignableFrom(ftype)) {
                                if (pindex >= ((Collection)val).size()
                                    || pindex < 0)
                                    return badRequest
                                        (uri+": list index out bound "
                                         +pindex);
                                Iterator it = ((Collection)val).iterator();
                                for (int k = 0; it.hasNext() 
                                         && k < pindex; ++k)
                                    ;
                                val = it.next();
                            }
                            else {
                                return badRequest 
                                    ("Property \""
                                     +fname+"\" is not an array or list");
                            }
                            Logger.debug(fname+"["+pindex+"] = "+val);
                        }
                        else {
                            /*
                              try {
                              Method m = inst.getClass()
                              .getMethod("get"+bname);
                              val = m.invoke(inst);
                              }
                              catch (NoSuchMethodException ex) {
                              Logger.warn("Can't find bean getter for "
                              +"field \""+fname+"\" in class "
                              +inst.getClass());
                              val = f.get(inst);
                              }
                            */
                            String oldVal = mapper.writeValueAsString(val);
                            
                            if (i+1 < paths.length) {
                                if (val == null) {
                                    // create new instance
                                    Logger.debug
                                        (uri+": new instance "+f.getType());
                                    val = f.getType().newInstance();
                                }
                            }
                            else {
                                // check to see if it references an existing 
                                // entity
                                if (value != null && !value.isNull()) {
                                    try {
                                        val = getJsonValue 
                                            (val, value, f, pindex);
                                    }
                                    catch (Exception ex) {
                                        Logger.trace
                                            ("Can't retrieve value", ex);
                                        
                                        return internalServerError
                                            (ex.getMessage());
                                    }
                                }
                                else {
                                    val = null;
                                }
                                
                                Logger.debug("Updating "+f.getDeclaringClass()
                                             +"."+f.getName()+" = new:"
                                             + (val != null ? 
                                                (val.getClass()+" "+val)
                                                : "null")
                                             +" old:"+oldVal);
                            }
                            
                            /*
                             * We can't use f.set(inst, val) here since it
                             * doesn't generate proper notifications to ebean
                             * for update
                             */
                            try {
                                Method set = inst.getClass().getMethod
                                    ("set"+bname, ftype);
                                set.invoke(inst, val);
                                changes.add(new Object[]{
                                                uri.toString(), 
                                                oldVal,
                                                val});
                            }
                            catch (Exception ex) {
                                Logger.error
                                    ("Can't find bean setter for field \""
                                     +fname+"\" in class "
                                     +inst.getClass(),
                                     ex);
                                
                                return internalServerError
                                    ("Unable to map path "+uri+"!");
                            }
                        }

                        if (val != null) {
                            ftype = val.getClass();
                            if (null != ftype.getAnnotation(Entity.class)) {
                                try {
                                    f = ftype.getField("id");
                                    /**
                                     * Record edit history on the lowest 
                                     * Entity class
                                     */
                                    type = (Class<T>)ftype;
                                    id = (K)f.get(val);
                                }
                                catch (NoSuchFieldException ex) {
                                    try {
                                        f = ftype.getField("uuid");
                                        type = (Class<T>)ftype;
                                        id = (K)f.get(val);
                                    }
                                    catch (NoSuchFieldException exx) {
                                        Logger.warn
                                            (ftype.getName()
                                             +": Entity doesn't have field id");
                                    }
                                }
                            }
                        }
                        inst = val;
                    }
                    catch (NoSuchFieldException ex) {
                        Logger.error(uri.toString(), ex);
                        return notFound ("Invalid field path: "+uri);
                    }
                }
                obj.update();

                for (Object[] c : changes) {
                    Edit e = new Edit (type, id);
                    e.path = (String)c[0];
                    e.editor = principal;
                    e.oldValue = (String)c[1];
                    e.newValue = mapper.writeValueAsString(c[2]);
                    Transaction tx = Ebean.beginTransaction();
                    try {
                        e.save();
                        tx.commit();
                        //Logger.debug("Edit "+e.id+" kind:"+e.kind+" old:"+e.oldValue+" new:"+e.newValue);
                    }
                    catch (Exception ex) {
                        Logger.trace
                            ("Can't persist Edit for "+type+":"+id, ex);
                    }
                    finally {
                        Ebean.endTransaction();
                    }   
                }

                return ok ((JsonNode)mapper.valueToTree(obj));
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Instance "+id, ex);
            return internalServerError (ex.getMessage());
        }
    } // update ()

    protected static Object getJsonValue 
        (Object val, JsonNode value, Field field, Integer index) 
        throws Exception {

        JsonNode node = value.get("id");
        Class<?> ftype = field.getType();

        Logger.debug("node: "+node+" "+ftype.getName()+"; val="+val);
        ObjectMapper mapper = getEntityMapper ();
        if (node != null && !node.isNull()) {
            long id = node.asLong();

            Model.Finder finder = new Model.Finder(Long.class, ftype);
            val = finder.byId(id);
        }
        else if (value.isArray()) {
            // if index is null, we replace the list
            // if index >= 0, then we append as the index position
            if (ftype.isArray()) {
                Class<?> c = ftype.getComponentType();
                if (val == null || index == null) {
                    val = Array.newInstance(c, value.size());
                    for (int k = 0; k < value.size(); ++k) {
                        Array.set(val, k, mapper.treeToValue(value.get(k), c));
                    }
                }
                else {
                    int len = Math.max(0, index);
                    if (len > Array.getLength(val)) {
                        throw new IllegalArgumentException
                            ("Invalid array index: "+len);
                    }
                    Object newval = Array.newInstance(c, value.size()+len);
                    int k = 0;
                    for (; k < len; ++k)
                        Array.set(newval, k, Array.get(val, k));
                    for (int i = 0; i < value.size(); ++i, ++k)
                        Array.set(newval, k, 
                                  mapper.treeToValue(value.get(i), c));
                    val = newval;
                }
                Logger.debug("Converted Json to Array (size="
                             +Array.getLength(val)+") of "+c);
            }
            else if (Collection.class.isAssignableFrom(ftype)) {
                if (val == null || index == null) {
                    val = new ArrayList ();
                    index = -1;
                }
                else if (index > ((Collection)val).size())
                    throw new IllegalArgumentException
                        ("Invalid list index: "+index);

                Collection vals = (Collection)val;
                Class cls = getComponentType (field.getGenericType());

                /* for now we only handle append or replace... no
                 * insert in a specific position 
                 */
                if (index < 0) {
                    for (Object obj : vals) {
                        if (obj instanceof Model) {
                            ((Model)obj).delete();
                        }
                    }
                }
                
                for (int k = 0; k < value.size(); ++k) {
                    vals.add(mapper.treeToValue(value.get(k), cls));
                }

                /*
                else {
                    ArrayList copy = new ArrayList ();
                    Iterator it = vals.iterator();
                    for (int k = 0; k < index && it.hasNext(); ++k)
                        copy.add(it.next());

                    for (int i = 0; i < value.size(); ++i)
                        copy.add(mapper.treeToValue(value.get(i), cls));

                    while (it.hasNext())
                        copy.add(it.next());

                    vals.addAll(copy);
                }
                */
                Logger.debug("Converted Json to List (size="
                             +((Collection)val).size()+") of "+cls);
            }
            else {
                Logger.error("Json is of type array "
                             +"but "+field.getDeclaringClass()+"."
                             +field.getName()+" is of type "+ftype);
            }
        }
        else if (ftype.isArray()) {
            Class<?> c = ftype.getComponentType();

            if (index == null 
                || index < 0 
                || index >= Array.getLength(val)) {
                if (val == null) {
                    val = Array.newInstance(c, 1);
                    index = 0;
                }
                else {
                    index = Array.getLength(val);
                    Object newval = Array.newInstance(c, index+1);
                    // copy array
                    for (int i = 0; i < index; ++i)
                        Array.set(newval, i, Array.get(val, i));
                    val = newval;
                }
            }
            else {
                int len = Array.getLength(val);
                Object newval = Array.newInstance(c, len);
                // copy array
                for (int i = 0; i < len; ++i)
                    Array.set(newval, i, Array.get(val, i));
                val = newval;
            }
            Array.set(val, index, mapper.treeToValue(value, c));
        }
        else if (Collection.class.isAssignableFrom(ftype)) {
            Class<?> c = getComponentType (field.getGenericType());
            if (index == null || index < 0 
                || index >= ((Collection)val).size()) {
                if (val == null) {
                    val = new ArrayList ();
                }
                else {
                    ArrayList newval = new ArrayList ();
                    newval.addAll((Collection)val);
                    val = newval;
                }
                ((Collection)val).add(mapper.treeToValue(value, c));
            }
            else {
                ArrayList newval = new ArrayList ();
                newval.addAll((Collection)val);
                Object el = newval.get(index);
                // now update this object
                if (el != null) {
                    updateBean (el, value);
                    Logger.debug("Updating "+el+" with "+value);
                }
                val = newval;
            }
        }
        else {
            val = mapper.treeToValue(value, ftype);
        }

        return val;
    } // getJsonValue ()

    static Class<?> getComponentType (Type type) {
        Class cls;
        if (type instanceof ParameterizedType) {
            Type[] types = ((ParameterizedType)type)
                .getActualTypeArguments();
            
            if (types.length != 1) {
                throw new IllegalStateException
                    ("Nested generic types not supported!");
            }
            cls = (Class)types[0];
        }
        else {
            cls = (Class)type;
        }
        return cls;
    }

    static String getBeanName (String field) {
        return Character.toUpperCase(field.charAt(0))+field.substring(1);
    }

    static void updateBean (Object bean, JsonNode value) {
        ObjectMapper mapper = new ObjectMapper ();
        Iterator<Map.Entry<String, JsonNode>> it = value.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> jf = it.next();
            String set = "set"+getBeanName (jf.getKey());
            Class<?> type = null;
            try {
                Field bf = bean.getClass().getField(jf.getKey());
                type = bf.getType();
                Method m = bean.getClass().getMethod(set, type);
                m.invoke(bean, mapper.treeToValue(jf.getValue(), type));
            }
            catch (NoSuchFieldException ex) {
                Logger.debug("Ignore field '"+jf.getKey()+"'");
            }
            catch (NoSuchMethodException ex) {
                Logger.error("No such method '"
                             +set+"("+type+")' for class "
                             +bean.getClass());
            }
            catch (Exception ex) {
                Logger.trace("Can't update bean '"+set+"'", ex);
            }
        }
    }

    public static UUID toUUID (String id) {
        if (id.length() == 32) { // without -'s
            id = id.substring(0,8)+"-"+id.substring(8,12)+"-"
                +id.substring(12,16)+"-"+id.substring(16,20)+"-"
                +id.substring(20);
        }
        return UUID.fromString(id);
    }

    public static <K,T> Result reindex (Model.Finder<K, T> finder) {
        Reindexer idx = Reindexer.getInstance(finder);
        ObjectMapper mapper = new ObjectMapper ();
        ObjectNode json = mapper.createObjectNode();
        json.put("start", new java.util.Date(idx.start()).toString());
        json.put("total", idx.total());
        json.put("count", idx.count());
        json.put("current", idx.current());
        if (idx.total() > 0) {
            long pct = (long)(idx.count()*100.0 / idx.total() + 0.5);
            json.put("percent", pct);
        }
        return ok (json);
    }
}
