package ix.core.controllers.v1;

import com.fasterxml.jackson.core.TreeNode;
import ix.core.NamedResource;
import ix.core.controllers.IxController;
import ix.core.controllers.EntityFactory;
import ix.core.controllers.search.SearchFactory;
import ix.core.models.Acl;
import ix.core.models.BeanViews;
import ix.core.models.Namespace;
import ix.core.models.Principal;
import ix.core.plugins.TextIndexerPlugin;
import ix.core.search.TextIndexer;
import ix.utils.CachedSupplier;
import ix.utils.Global;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.persistence.Id;

import play.Logger;
import play.Play;
import play.db.ebean.Model;
import play.mvc.Controller;
import play.mvc.Result;
import play.libs.Json;

import com.avaje.ebean.Expr;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class RouteFactory extends IxController {
    static final public Model.Finder<Long, Namespace> resFinder = 
        new Model.Finder(Long.class, Namespace.class);
    static final public Model.Finder<Long, Acl> aclFinder = 
        new Model.Finder(Long.class, Acl.class);
    static final public Model.Finder<Long, Principal> palFinder =
        new Model.Finder(Long.class, Principal.class);

    static final ConcurrentMap<String, Class> _registry = 
        new ConcurrentHashMap<String, Class>();
    static final Set<String> _uuid = new TreeSet<String>();

    static final CachedSupplier<TextIndexerPlugin> TEXT_INDEXER_PLUGIN_CACHED_SUPPLIER = CachedSupplier.of(new Supplier<TextIndexerPlugin>(){
        @Override
        public TextIndexerPlugin get() {
            return Play.application().plugin(TextIndexerPlugin.class);
        }
    });

    public static <T  extends EntityFactory> void register 
        (String context, Class<T> factory) {
        Class old = _registry.putIfAbsent(context, factory);
        if (old != null) {
            Logger.warn("Context \""+context
                        +"\" now maps to "+factory.getClass());
        }
        
        NamedResource named  = factory.getAnnotation(NamedResource.class);
        if (named != null) {
            try {
                Class cls = named.type();
                Field id = null;
                for (Field f : cls.getFields()) {
                    if (null != f.getAnnotation(Id.class)) {
                        id = f;
                    }
                }

                if (id == null) { // possible?
                    Logger.error("Fatal error: Entity "+cls.getName()
                                 +" for factory "+factory.getClass()
                                 +" doesn't have any Id annotation!");
                }
                else {
                    Class c = id.getType();
                    if (UUID.class.isAssignableFrom(c)) {
                        Logger.debug("## "+cls.getName()
                                     +" is globally unique!");
                        _uuid.add(context);
                    }
                }
            }
            catch (Exception ex) {
                Logger.error("Can't access named resource type", ex);
            }
        }
    }

    public static Result listResources () {
        Set<String> resources = new TreeSet<String>(_registry.keySet());
        List<String> urls = new ArrayList<String>();
        ObjectMapper mapper = new ObjectMapper ();      
        ArrayNode nodes = mapper.createArrayNode();
        for (String res : resources) {
            ObjectNode n = mapper.createObjectNode();
            NamedResource named  = (NamedResource)_registry
                .get(res).getAnnotation(NamedResource.class);
            n.put("name", res);
            n.put("kind", named.type().getName());
            n.put("href", Global.getHost()+request().uri()+"/"+res);
            n.put("description", named.description());
            nodes.add(n);
        }

        return ok (nodes);
    }

    public static Result get (String ns, String resource) {
        Namespace res = resFinder
            .where(Expr.eq("name", ns))
            .findUnique();
        if (res == null) {
            return badRequest ("No such namespace: "+ns);
        }
        
        // now see if this request has proper permission
        if (res.isPublic()) { // ok
        }
        else {
            return forbidden ("You don't have permission to access resource!");
        }
                  
        return ok ((JsonNode)Json.toJson(res));
    }

    static Method getMethod (String context, 
                             String method, Class<?>... types) {
        Class factory = _registry.get(context);
        if (factory != null) {
            try {
                return factory.getMethod(method, types);
            }
            catch (Exception ex) {
                Logger.trace("Unknown method \""+method
                             +"\" in class "+factory.getClass(), ex);
            }
        }
        return null;
    }

    
    public static Result count (String context) {
        try {
            Method m = getMethod (context, "count");
            if (m != null) 
                return (Result)m.invoke(null);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has not method count()",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }
    
    public static Result search (String context, String q, 
                                 int top, int skip, int fdim) {
        Class factory = _registry.get(context);
        if (factory != null) {
            NamedResource res = (NamedResource)factory.getAnnotation
                (NamedResource.class);
            return SearchFactory.search(res.type(), q, top, skip, fdim);
        }
        return badRequest ("Unknown Context: \""+context+"\"");
    }

    public static Result termVectors (String context, String field) {
        Class factory = _registry.get(context);
        if (factory != null) {
            NamedResource res = 
                (NamedResource)factory.getAnnotation(NamedResource.class);
            return SearchFactory.termVectors(res.type(), field);
        }
        return badRequest ("Unknown Context: \""+context+"\"");
    }
    
    public static Result get (String context, Long id, String expand) {
        try {
            Method m = getMethod (context, "get", Long.class, String.class);
            if (m != null)
                return (Result)m.invoke(null, id, expand);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method get(Long,String)",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }

    public static Result batchResolve(String context){
        JsonNode body = request().body().asJson();
        if(!body.isArray()){
            return badRequest("body must be JSON array");
        }
        ArrayNode array = (ArrayNode) body;
        try {
            Method m = getMethod (context, "batchResolveFunction");
            if (m != null) {
                Function<String, ?> function = (Function<String, ?>) m.invoke(null);

                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                for(JsonNode a : array){
                    String key = a.asText();

                    map.computeIfAbsent(key, k-> function.apply(k));
                }
                System.out.println("map = " + map);
                Class factory = _registry.get(context);
                Class kind = null;
                if (factory != null) {
                    NamedResource res = (NamedResource) factory.getAnnotation
                            (NamedResource.class);
                    kind = res.type();
                }
                 TextIndexer.SearchResult result = SearchFactory.search(TEXT_INDEXER_PLUGIN_CACHED_SUPPLIER.get().getIndexer(),kind, map.values(), "*:*", 10,0,10 , request().queryString());

                return ok(SearchFactory.convertToSearchResultJson(kind,"*:*", 10, 0, result));
//
//
//                TextIndexer.SearchResult result= TEXT_INDEXER_PLUGIN_CACHED_SUPPLIER.get().getIndexer().filter(map.values());
//
//                result.getMatchesAndWaitIfNotFinished();
////                return ok(result.getMatchesAndWaitIfNotFinished());
//                ObjectMapper mapper = new EntityFactory.EntityMapper(BeanViews.Compact.class);

//                return new CachableContent((JsonNode)mapper.valueToTree(result)).ok();
            }
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method batchResolveFunction()",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }
    public static Result resolve (String context, String name, String expand) {
        try {
            Method m = getResolveMethodFor(context);
            if (m != null) {
                return (Result) m.invoke(null, name, expand);
            }
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method resolve(String,String)",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }

    private static Method getResolveMethodFor(String context) {
        return getMethod (context, "resolve",
                                      String.class, String.class);
    }

    public static Result doc (String context, Long id) {
        try {
            Method m = getMethod (context, "doc", Long.class);
            if (m != null)
                return (Result)m.invoke(null, id);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method doc(Long)", context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }

    public static Result reindex (String context) {
        try {
            Method m = getMethod (context, "reindex");
            if (m != null)
                return (Result)m.invoke(null);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method reindex()", context);
        return badRequest ("Unknown Context: \""+context+"\"");     
    }

    public static Result getUUID (String context, String uuid, String expand) {
        try {
            Method m = getMethod (context, "get", UUID.class, String.class);
            if (m != null)
                return (Result)m.invoke(null, EntityFactory.toUUID(uuid),
                                        expand);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method get(UUID,String)",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }
    
    public static Result edits (String context, Long id) {
        try {
            Method m = getMethod (context, "edits", Long.class);
            if (m != null)
                return (Result)m.invoke(null, id);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.debug("Unknown context: "+context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }

    public static Result editsUUID (String context, String id) {
        try {
            Method m = getMethod (context, "edits", UUID.class);
            if (m != null)
                return (Result)m.invoke(null, EntityFactory.toUUID(id));
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method edits(UUID)",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }
    
    public static Result field (String context, Long id, String field) {
        try {
            Method m = getMethod (context, "field", Long.class, String.class);
            if (m != null)
                return (Result)m.invoke(null, id, field);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method field(Long,String)",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }

    public static Result fieldUUID (String context, String uuid, String field) {
        try {
            Method m = getMethod (context, "field", UUID.class, String.class);
            if (m != null) {
                return (Result)m.invoke
                    (null, EntityFactory.toUUID(uuid), field);
            }
            else {
                Logger.error
                    ("Context \""+context
                     +"\" doesn't have method \"field(UUID, String)\"!");
            }
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method field(UUID,String)",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }
    
    public static Result page (String context, int top,
                               int skip, String filter) {
        
        try {
            Method m = getMethod (context, "page", 
                                  int.class, int.class, String.class);
            System.out.println(m);
            if (m != null)
                return (Result)m.invoke(null, top, skip, filter);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method page(int,int,String)",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }

    public static Result create (String context) {
        String user = request().username();
        if (user == null) {
            return forbidden ("You're not authorized to access this resource!");
        }

        Logger.debug("user "+user+": create("+context+")");
        try {
            Method m = getMethod (context, "create"); 
            if (m != null)
                return (Result)m.invoke(null);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method create()",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }

    public static Result update (String context, Long id, String field) {
        String user = request().username();
        if (user == null) {
            return forbidden ("You're not authorized to access this resource!");
        }

        Logger.debug("user "+user+": update("+context+","+id+","+field);
        try {
            Method m = getMethod (context, "update", Long.class, String.class);
            if (m != null)
                return (Result)m.invoke(null, id, field);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method update(Long,String)",context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }

    public static Result updateUUID (String context, String id, String field) {
        String user = request().username();
        if (user == null) {
            return forbidden ("You're not authorized to access this resource!");
        }

        Logger.debug("user "+user+": update("+context+","+id+","+field);
        try {
            Method m = getMethod (context, "update", UUID.class, String.class);
            if (m != null)
                return (Result)m.invoke
                    (null, EntityFactory.toUUID(id), field);
        }
        catch (Exception ex) {
            Logger.trace("["+context+"]", ex);
            return internalServerError (context);
        }
        Logger.warn("Context {} has no method update(UUID,String)", context);
        return badRequest ("Unknown Context: \""+context+"\"");
    }

    public static Result _getUUID (String uuid, String expand) {
        for (String context : _uuid) {
            Result r = getUUID (context, uuid, expand);
            if (r.toScala().header().status() < 400)
                return r;
        }
        return notFound ("Unknown id "+uuid);
    }
    
    public static Result _fieldUUID (String uuid, String field) {
        for (String context : _uuid) {
            Result r = fieldUUID (context, uuid, field);
            if (r.toScala().header().status() < 400)
                return r;
        }
        return notFound ("Unknown id "+uuid);
    }
}
