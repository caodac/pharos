package ix.core.controllers.search;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.Callable;

import play.*;
import play.db.ebean.*;
import play.data.*;
import play.mvc.*;
import play.libs.Json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.avaje.ebean.*;
import com.avaje.ebean.event.BeanPersistListener;

import ix.core.models.ETag;
import ix.core.models.ETagRef;
import ix.core.models.Edit;
import ix.core.models.Principal;

import ix.utils.Global;
import ix.utils.Util;
import ix.core.plugins.*;
import ix.core.search.TextIndexer;
import static ix.core.search.TextIndexer.*;
import ix.core.search.SearchOptions;
import ix.core.controllers.EntityFactory;

public class SearchFactory extends EntityFactory {
    static final Model.Finder<Long, ETag> etagDb = 
        new Model.Finder(Long.class, ETag.class);

    public static SearchOptions parseSearchOptions
        (SearchOptions options, Map<String, String[]> queryParams) {
        if (options == null) {
            options = new SearchOptions ();
        }
        options.parse(queryParams);
        return options;
    }

    public static SearchResult
        search (Class kind, String q, int top, int skip, int fdim,
                Map<String, String[]> queryParams,
                SearchOptions.FacetRange... rangeFacets) throws IOException {
        return search (_textIndexer, kind, null, q, top,
                       skip, fdim, queryParams, rangeFacets);
    }

    public static SearchResult
        search (Collection subset, String q, int fdim,
                Map<String, String[]> queryParams,
                SearchOptions.FacetRange... rangeFacets) throws IOException {
        return search (_textIndexer, null, subset,
                       q, subset != null ? subset.size() : 0,
                       0, fdim, queryParams, rangeFacets);
    }

    public static SearchResult search (int top, int skip, int fdim,
                                       Map<String, String[]> queryParams)
        throws IOException {
        return search (_textIndexer, null, null, null,
                       top, skip, fdim, queryParams);
    }

    public static SearchResult
        search (String q, int top, int skip, int fdim,
                Map<String, String[]> queryParams,
                SearchOptions.FacetRange... rangeFacets) throws IOException {
        return search (_textIndexer, null, null, q, top,
                       skip, fdim, queryParams, rangeFacets);
    }
    
    public static SearchResult
        search (Collection subset, String q, int top, int skip, int fdim,
                Map<String, String[]> queryParams,
                SearchOptions.FacetRange... rangeFacets) throws IOException {
        return search (_textIndexer, null, subset, q, top,
                       skip, fdim, queryParams, rangeFacets);
    }
    
    public static SearchResult
        search (TextIndexer indexer, Class kind,
                String q, int top, int skip, int fdim,
                Map<String, String[]> queryParams,
                SearchOptions.FacetRange... rangeFacets) throws IOException {
        return search (indexer, kind, null, q, top,
                       skip, fdim, queryParams, rangeFacets);
    }
    
    public static SearchResult
        search (TextIndexer indexer, Class kind, Collection subset,
                String q, int top, int skip, int fdim,
                Map<String, String[]> queryParams,
                SearchOptions.FacetRange... rangeFacets) throws IOException {
        SearchOptions options = new SearchOptions (kind, top, skip, fdim);
        if (rangeFacets != null) {
            for (SearchOptions.FacetRange fr : rangeFacets)
                options.addFacet(fr);
        }
        
        StringBuilder filter = new StringBuilder ();
        if (queryParams != null) {
            parseSearchOptions (options, queryParams);
            for (String f : options.facets) {
                if (filter.length() > 0)
                    filter.append("&");
                filter.append("facet="+f);
            }
        }
        
        if (q == null || "".equals(q)) {
        }
        else if (q.startsWith("etag:") || q.startsWith("ETag:")) {
            String id = q.substring(5, 21);
            try {
                ETag etag = etagDb.where().eq("etag", id).findUnique();
                if (etag.query != null) {
                    if (etag.filter != null) {
                        String[] facets = etag.filter.split("&");
                        for (int i = facets.length; --i >= 0; ) {
                            if (facets[i].length() > 0) {
                                filter.insert(0, facets[i]+"&");
                                options.facets.add
                                    (0, facets[i].replaceAll("facet=", ""));
                            }
                        }
                    }
                    q = etag.query; // rewrite the query
                }
                else {
                    Logger.warn("ETag "+id+" is not a search!");
                }
            }
            catch (Exception ex) {
                Logger.trace("Can't find ETag "+id, ex);
            }
        }
        
        return indexer.search(options, q, subset);
    }
        
    public static Result search (String q, int top, int skip, int fdim) {
        return search (null, q, top, skip, fdim);
    }

    public static Result search (final Class kind, final String q, 
                                 final int top, final int skip,
                                 final int fdim) {
        if (Global.DEBUG(1)) {
            Logger.debug("SearchFactory.search: kind="
                         +(kind != null ? kind.getName():"")+" q="
                         +q+" top="+top+" skip="+skip+" fdim="+fdim);
        }

        try {
            JsonNode json = _search (kind, q, top, skip, fdim);
            return ok (json);
        }
        catch (Exception ex) {
            Logger.error("Can't perform search with parameters "
                         +request().uri(), ex);
            return internalServerError ("Unable to perform search!");
        }
    }
        
    public static JsonNode _search 
        (final Class kind, String q, int top, int skip, int fdim)
        throws Exception {
        final String key = Util.sha1(request());
        SearchResult result = getOrElse (key, new Callable<SearchResult> () {
                public SearchResult call () throws Exception {
                    return search (kind, q, top, skip, 
                                   fdim, request().queryString());
                }
            });

        return convertToSearchResultJson(kind, q, top, skip, result);
    }

    public static JsonNode convertToSearchResultJson(Class kind, String q, int top, int skip, SearchResult result) {
        SearchOptions options = result.getOptions();
        ObjectMapper mapper = getEntityMapper ();
        ArrayNode nodes = mapper.createArrayNode();
        int added=0;
        for (Object obj : result.getMatches()) {
            if (obj != null) {
                try {
                    ObjectNode node = (ObjectNode)mapper.valueToTree(obj);
                    node.put("kind", kind == null
                             ? obj.getClass().getName() : kind.getName());
                    MatchFragment[] frags =
                        result.getFragments(obj);
                    if (frags != null && frags.length > 0) {
                        ArrayNode fragments = mapper.createArrayNode();
                        for (MatchFragment mf : frags) {
                            fragments.add(mapper.valueToTree(mf));
                        }
                        node.put("fragments", fragments);
                    }

                    //if(added>=skip)
                    nodes.add(node);
                    added++;
                    //Logger.debug("Using search function");
                }
                catch (Exception ex) {
                    Logger.trace("Unable to serialize object to Json", ex);
                }
            }
        }

        /*
         * TODO: setup etag right here!
         */
        ETag etag = new ETag ();
        etag.top = top;
        etag.skip = skip;
        etag.count = nodes.size();
        etag.total = result.count();
        etag.uri = Global.getHost()+request().uri();
        etag.path = request().path();
        etag.sha1 = Util.sha1(request(), "q", "facet");
        etag.query = q;
        etag.method = request().method();
        etag.filter = options.filter;
        etag.save();

        ObjectNode obj = (ObjectNode)mapper.valueToTree(etag);
        obj.put(options.sideway ? "sideway" : "drilldown",
                mapper.valueToTree(options.facets));
        obj.put("facets", mapper.valueToTree(result.getFacets()));
        obj.put("content", nodes);

        return obj;
    }

    public static Result facetFields () {
        return ok (_textIndexer.getFacetsConfig());
    }

    public static Result indexFields () {
        try {
            return ok (_textIndexer.getIndexFields());
        }
        catch (Exception ex) {
            return internalServerError (ex.getMessage());
        }
    }

    static String getTermVectorCacheKey (Class kind, String field) {
        return SearchFactory.class.getName()+"/termVectors/"
            +kind.getName()+"/"+field;
    }
        
    public static TermVectors getTermVectors
        (final Class kind, final String field) {
        try {
            final String key = getTermVectorCacheKey (kind, field);
            return getOrElse (key, new Callable<TermVectors> () {
                    public TermVectors call () throws Exception {
                        return _textIndexer.getTermVectors(kind, field);
                    }
                });
        }
        catch (Exception ex) {
            Logger.error("Can't generate termVectors for "+kind+"/"+field, ex);
            return null;
        }
    }

    static String getConditionalTermVectorCacheKey (Class kind, String field,
                                                    String conditional) {
        return SearchFactory.class.getName()+"/termVectors/"
            +kind.getName()+"/"+field+"/"+conditional;
    }

    public static int clearCaches (Class kind, String... fields) {
        List keys = IxCache.getKeys();
        int caches = 0;
        for (Object key : keys) {
            String k = key.toString();
            if (k.startsWith(SearchFactory.class.getName())
                && k.indexOf("/"+kind.getName()) > 0) {
                for (String f : fields) {
                    if (k.indexOf("/"+f) > 0) {
                        Logger.debug("removing cache..."+k);
                        IxCache.remove(k);
                        ++caches;
                    }
                }
            }
        }
        return caches;
    }
    
    public static Map<String, TermVectors>
        getConditionalTermVectors (final Class kind,
                                   final String field,
                                   final String conditional) {
        try {
            final String key = getConditionalTermVectorCacheKey
                (kind, field, conditional);
            return getOrElse (key, new Callable<Map<String, TermVectors>>() {
                    public Map<String, TermVectors> call () throws Exception {
                        TermVectors tv = getTermVectors (kind, conditional);
                        Map<String, TermVectors> result = null;
                        if (tv != null) {
                            result = new TreeMap<String, TermVectors>();
                            Map<String, String> cond =
                                new HashMap<String, String>();
                            for (String term : tv.getTerms().keySet()) {
                                cond.put(conditional, term);
                                TermVectors ctv = getConditionalTermVectors
                                    (kind, field, cond);
                                result.put(term, ctv);
                            }
                        }
                        return result;
                    }
                });
        }
        catch (Exception ex) {
            Logger.error("Can't generate conditional termVectors("
                         +field+"|"+conditional+","+kind+")", ex);
        }
        return null;    
    }
    
    public static TermVectors getConditionalTermVectors
        (final Class kind, final String field,
         final Map<String, String> conditionals) {
        if (conditionals == null || conditionals.isEmpty())
            throw new IllegalArgumentException
                ("Can't get conditional term vectors with empty constraints!");

        try {
            List<String> params = new ArrayList<String>();
            for (Map.Entry<String, String> me : conditionals.entrySet())
                params.add(me.getKey()+"/"+me.getValue());
            Collections.sort(params);
            
            final String key = SearchFactory.class.getName()
                +"/termVectors/"+kind.getName()+"/"+field+"/"
                +Util.sha1(params.toArray(new String[0]));
            return IxCache.getOrElse
                (_textIndexer.lastModified(), key, new Callable<TermVectors> () {
                        public TermVectors call ()
                            throws Exception {
                            return _textIndexer.getTermVectors
                                (kind, field, conditionals);
                        }
                    });
        }
        catch (Exception ex) {
            Logger.error("Can't generate termVectors for "+kind+"/"+field
                         +" conditioned on "+conditionals, ex);
            return null;            
        }
    }

    public static Result termVectors (Class kind, String field) {
        String[] facets = request().queryString().get("facet");
        Object result = null;
        if (facets != null && facets.length > 0) {
            Map<String, String> filters = new TreeMap<String, String>();
            String conditional = null;
            for (String f : facets) {
                // syntax of a facet: FIELD/VALUE
                int pos = f.indexOf('/');
                if (pos > 0) {
                    filters.put(f.substring(0, pos), f.substring(pos+1));
                }
                else {
                    conditional = f;
                }
            }

            if (conditional != null) {
                result = getConditionalTermVectors(kind, field, conditional);
            }
            else if (!filters.isEmpty()) {
                result = getConditionalTermVectors (kind, field, filters);
            }
            else {
                result = getTermVectors (kind, field);
            }
        }
        else {
            result = getTermVectors (kind, field);
        }
        
        return result != null ? ok ((JsonNode)Json.toJson(result))
            : notFound ("Can't find termVectors for "+kind+"/"+field);
    }
    
    public static Result suggest (String q, int max) {
        return suggestField (null, q, max);
    }

    public static Result suggestField (String field, String q, int max) {
        try {
            if (field != null) {
                List<TextIndexer.SuggestResult> results = 
                    _textIndexer.suggest(field, q, max);
                return ok ((JsonNode)Json.toJson(results));
            }

            ObjectNode node = Json.newObject();
            for (String f : _textIndexer.getSuggestFields()) {
                List<TextIndexer.SuggestResult> results = 
                    _textIndexer.suggest(f, q, max);
                if (!results.isEmpty())
                    node.put(f, Json.toJson(results));
            }
            Logger.info(node.toString());
            return ok (node);
        }
        catch (Exception ex) {
            return internalServerError (ex.getMessage());
        }
    }

    public static Result suggestFields () {
        return ok ((JsonNode)Json.toJson(_textIndexer.getSuggestFields()));
    }

    public static List<Facet> getFacets (final Class kind) {
        return getFacets (kind, 100);
    }
    
    public static List<Facet> getFacets (final Class kind, final int fdim) {
        final String sha1 = Util.sha1(SearchFactory.class.getName()
                                      +"/facets/"+kind.getName()+"/"+fdim);
        try {
            return getOrElse (sha1, new Callable<List<Facet>>() {
                    public List<Facet> call () throws Exception {
                        SearchResult result = search
                            (kind, null, 0, 0, fdim, null);
                        return result.getFacets();
                    }
                });
        }
        catch (Exception ex) {
            Logger.trace("Can't retrieve facets for "+kind, ex);
        }
        return null;
    }
}
