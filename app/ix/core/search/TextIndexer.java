package ix.core.search;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ix.core.ObjectFactory;
import ix.core.models.DynamicFacet;
import ix.core.models.Indexable;
import ix.core.models.Value;
import ix.core.plugins.IxCache;
import ix.utils.Global;
import ix.utils.Util;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.FloatField;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.DrillSideways;
import org.apache.lucene.facet.FacetField;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.range.DoubleRange;
import org.apache.lucene.facet.range.DoubleRangeFacetCounts;
import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.facet.range.LongRangeFacetCounts;
import org.apache.lucene.facet.taxonomy.FastTaxonomyFacetCounts;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexableFieldType;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queries.ChainedFilter;
import org.apache.lucene.queries.TermFilter;
import org.apache.lucene.queries.TermsFilter;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.FieldCacheRangeFilter;
import org.apache.lucene.search.FieldCacheTermsFilter;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.NumericRangeFilter;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.suggest.DocumentDictionary;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.search.vectorhighlight.FastVectorHighlighter;
import org.apache.lucene.search.vectorhighlight.FieldQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.apache.lucene.util.NumericUtils;

import org.reflections.Reflections;
import play.Logger;

import javax.persistence.Entity;
import javax.persistence.Id;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.apache.lucene.document.Field.Store.NO;
import static org.apache.lucene.document.Field.Store.YES;

/**
 * Singleton class that responsible for all entity indexing
 */
public class TextIndexer {
    protected static final String STOP_WORD = ""; //" THE_STOP";
    protected static final String START_WORD = ""; //"THE_START ";
    protected static final String GIVEN_STOP_WORD = "$";
    protected static final String GIVEN_START_WORD = "^";

    @Indexable
    static final class DefaultIndexable {}
    static final Indexable defaultIndexable = 
        (Indexable)DefaultIndexable.class.getAnnotation(Indexable.class);

    /**
     * well known fields
     */
    public static final String FIELD_KIND = "__kind";
    public static final String FIELD_ID = "id";

    /**
     * these default parameters should be configurable!
     */
    public static final int FETCH_TIMEOUT = 60*60*2; // 2hours
    public static final int FETCH_WORKERS = 4; // number of fetch workers

    /**
     * Make sure to properly update the code when upgrading version
     */
    static final Version LUCENE_VERSION = Version.LATEST;
    static final String FACETS_CONFIG_FILE = "facet_conf.json";
    static final String SUGGEST_CONFIG_FILE = "suggest_conf.json";
    static final String SORTER_CONFIG_FILE = "sorter_conf.json";
    static final String DIM_CLASS = "ix.Class";

    static final int DEFAULT_RANGE_BINS = 50;

    static final DateFormat YEAR_DATE_FORMAT = new SimpleDateFormat ("yyyy");
    static final Object POISON_PILL = new Object ();

    static final FieldType TermVectorFieldType = new FieldType ();
    static {
        TermVectorFieldType.setIndexed(true);
        TermVectorFieldType.setTokenized(false);
        TermVectorFieldType.setStoreTermVectors(true);
        TermVectorFieldType.freeze();
    }

    static class TermVectorField extends org.apache.lucene.document.Field {
        TermVectorField (String field, String value) {
            super (field, value, TermVectorFieldType);
        }
    }

    static final FieldType HighlightFieldType =
        new FieldType (TextField.TYPE_STORED);
    static {
        HighlightFieldType.setIndexed(true);
        HighlightFieldType.setStoreTermVectors(true);
        HighlightFieldType.setStoreTermVectorPositions(true);
        //HighlightFieldType.setStoreTermVectorPayloads(true);
        HighlightFieldType.setStoreTermVectorOffsets(true);
        HighlightFieldType.freeze(); 
    }

    static class HighlightField extends org.apache.lucene.document.Field {
        HighlightField (String field, String value) {
            super (field, value, HighlightFieldType);
        }
    }

    public static class TermVectors implements java.io.Serializable {
        static private final long serialVersionUID = 0x192464a5d08ea528l;
        
        Class kind;     
        String field;
        int numDocs;
        List<Map> docs = new ArrayList<Map>();
        Map<String, Map> terms = new TreeMap<String,Map>();
        Map<String, String> filters = new TreeMap<String, String>();
        
        TermVectors (Class kind, String field) {
            this.kind = kind;
            this.field = field;
        }

        public Class getKind () { return kind; }
        public String getField () { return field; }

        public Map<String, String> getFilters () { return filters; }
        public Map<String, Map> getTerms () { return terms; }
        public List<Map> getDocs () { return docs; }    
        public int getNumDocs () { return numDocs; }
        public int getNumDocsWithTerms () { return docs.size(); }
        public int getNumTerms () { return terms.size(); }
        public Integer getTermCount (String term) {
            Map map = terms.get(term);
            Integer count = null;
            if (map != null) {
                count = (Integer)map.get("nDocs");
            }
            return count;
        }
    }

    class TermVectorsCollector extends Collector {
        private int docBase;
        private TermsEnum termsEnum;
        private IndexReader reader;     
        private Class idType;
        private TermVectors tvec;
        private Map<String, Set> counts;
        
        TermVectorsCollector (Class kind, String field) throws IOException {
            this (kind, field, new Term[0]);
        }

        TermVectorsCollector (Class kind, String field, Term... terms)
            throws IOException {
            tvec = new TermVectors (kind, field);
            counts = new TreeMap<String, Set>();
            
            idType = String.class;
            for (Field f : kind.getFields()) {
                if (null != f.getAnnotation(Id.class)) {
                    idType = f.getType();
                    break;
                }
            }
            
            IndexSearcher searcher = getSearcher ();        
            this.reader = searcher.getIndexReader();

            TermQuery tq = new TermQuery
                (new Term (FIELD_KIND, kind.getName()));
            
            Filter filter = null;
            if (terms != null && terms.length > 0) {
                if (terms.length == 1) {
                    filter = new TermFilter (terms[0]);
                    tvec.filters.put(terms[0].field(), terms[0].text());
                }
                else {
                    Filter[] filters = new TermFilter[terms.length];
                    for (int i = 0; i < terms.length; ++i) {
                        filters[i] = new TermFilter (terms[i]);
                        tvec.filters.put(terms[i].field(), terms[i].text());
                    }
                    filter = new ChainedFilter (filters, ChainedFilter.AND);
                }
            }
            searcher.search(tq, filter, this);

            Collections.sort(tvec.docs, new Comparator<Map>() {
                    public int compare (Map m1, Map m2) {
                        Integer c1 = (Integer)m1.get("nTerms");
                        Integer c2 = (Integer)m2.get("nTerms");
                        return c2 - c1;
                    }
                });

            for (Map.Entry<String, Set> me : counts.entrySet()) {
                Map map = new HashMap ();
                map.put("docs", me.getValue().toArray(new Object[0]));
                map.put("nDocs", me.getValue().size());
                tvec.terms.put(me.getKey(), map);
            }
            counts = null;
            releaseSearcher (searcher);
        }
        
        public void setScorer (Scorer scorer) {
        }
        
        public boolean acceptsDocsOutOfOrder () { return true; }
        public void collect (int doc) {
            int docId = docBase + doc;
            try {
                Terms docterms = reader.getTermVector(docId, tvec.field);
                if (docterms != null) {
                    Document d = reader.document(docId);                    
                    Object id = d.get(tvec.kind.getName()+"._id");
                    if (Long.class.isAssignableFrom(idType)) {
                        try {
                            id = Long.parseLong(id.toString());
                        }
                        catch (NumberFormatException ex) {
                            Logger.error("Bogus id: "+id, ex);
                        }
                    }
                    
                    //Logger.debug("+++++ doc "+docId+" +++++");
                    List<String> terms = new ArrayList<String>();
                    for (TermsEnum en = docterms.iterator(termsEnum);
                         en.next() != null;) {
                        String term = en.term().utf8ToString();
                        Set docs = counts.get(term);
                        if (docs == null) {
                            counts.put(term, docs = new HashSet ());
                        }
                        docs.add(id);
                        terms.add(term);
                    }
                    
                    Map map = new HashMap ();
                    map.put("doc", id);
                    map.put("terms", terms.toArray(new String[0]));
                    map.put("nTerms", terms.size());
                    tvec.docs.add(map);
                }
                else {
                    //Logger.debug("No term vector for field \""+field+"\"!");
                }
                ++tvec.numDocs;
            }
            catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        
        public void setNextReader (AtomicReaderContext ctx) {
            docBase = ctx.docBase;
        }

        public TermVectors termVectors () { return tvec; }
    }
    
    public static class FV implements java.io.Serializable {
        String label;
        Integer count;

        FV (String label, Integer count) {
            this.label = label;
            this.count = count;
        }
        public String getLabel () { return label; }
        public Integer getCount () { return count; }
    }

    public interface FacetFilter {
        boolean accepted (FV fv);
    }

    public static class Facet implements java.io.Serializable {
        String name;
        List<FV> values = new ArrayList<FV>();

        public Facet (String name) { this.name = name; }
        public String getName () { return name; }
        public List<FV> getValues () {
            return values; 
        }
        public int size () { return values.size(); }

        public FV getValue (int index) { return values.get(index); }
        public String getLabel (int index) {
            return values.get(index).getLabel();
        }
        public Integer getCount (int index) {
            return values.get(index).getCount();
        }
        public Integer getCount (String label) {
            for (FV fv : values)
                if (fv.label.equalsIgnoreCase(label))
                    return fv.count;
            return null;
        }
        
        public void sort () {
            sortCounts (true);
        }

        public Facet filter (FacetFilter filter) {
            Facet filtered = new Facet (name);
            for (FV fv : values)
                if (filter.accepted(fv))
                    filtered.values.add(fv);
            return filtered;
        }

        public void sortLabels (final boolean desc) {
            Collections.sort(values, new Comparator<FV>() {
                    public int compare (FV v1, FV v2) {
                        return desc ? v2.label.compareTo(v1.label)
                            : v1.label.compareTo(v2.label);
                    }
                });
        }
        
        public void sortCounts (final boolean desc) {
            Collections.sort(values, new Comparator<FV> () {
                    public int compare (FV v1, FV v2) {
                        int d = desc ? (v2.count - v1.count)
                            : (v1.count-v2.count);
                        if (d == 0)
                            d = v1.label.compareTo(v2.label);
                        return d;
                    }
                });
        }

        @JsonIgnore
        public ArrayList<String> getLabelString () {
            ArrayList<String> strings = new ArrayList<String>();
            for (int i = 0; i<values.size(); i++) {
                String label = values.get(i).getLabel();
                strings.add(label);
            }
            return strings;
        }
        
        @JsonIgnore
        public ArrayList <Integer> getLabelCount () {
            ArrayList<Integer> counts = new ArrayList<Integer>();
            for(int i = 0; i<values.size(); i++){
                int count = values.get(i).getCount();
                counts.add(count);
            }
            return counts;
        }
    }

    static public abstract class AbstractRangeField<T extends Number> {
        T min, max;
        final String field;
        final Class kind;
        final int nbins;
        int[] counts;
        
        AbstractRangeField (TextIndexer indexer, Class kind,
                            String field, int nbins) throws IOException {
            for (AtomicReaderContext arc : indexer.indexReader.leaves()) {
                Terms terms = arc.reader().terms(field);
                if (terms != null) {
                    T mi = getMin (terms);
                    T ma = getMax (terms);
                    if (min == null || compare (min, mi) > 0)
                        min = mi;
                    if (max == null || compare (max, ma) < 0)
                        max = ma;
                }
            }

            if (min == null || max == null)
                throw new IllegalArgumentException
                    ("Field \""+field+"\" is not a numeric field!");
            this.kind = kind;
            this.field = field;
            this.nbins = nbins;
            Logger.debug("Numeric field \""+field+"\": min="+min+" max="+max);
        }

        public String getField () { return field; }
        public int[] getCounts () { return counts; }
        public int getCount (int bin) { return counts[bin]; }
        abstract public T getStart (int bin);
        abstract public T getEnd (int bin);
        abstract Query getQuery (int bin);
        abstract Filter getFilter (int bin);
        abstract <T extends Number> T getMin (Terms terms) throws IOException;
        abstract <T extends Number> T getMax (Terms terms) throws IOException;
        abstract int compare (T a, T b);
    }

    public static class LongRangeField extends AbstractRangeField<Long> {
        final long gap;
        LongRangeField (TextIndexer indexer, Class kind, String field)
            throws IOException {
            this (indexer, kind, field, DEFAULT_RANGE_BINS);
        }
        LongRangeField (TextIndexer indexer, Class kind, String field,
                        int nbins) throws IOException {
            super (indexer, kind, field, nbins);
            gap = Math.max((long)((double)(max - min)/nbins+0.5), 1);
            int[] counts = new int[nbins];
            
            IndexSearcher searcher = indexer.getSearcher();
            Filter filter = null;
            if (kind != null)
                filter = new TermFilter(new Term (FIELD_KIND, kind.getName()));

            int i = 0;
            for (long s = min, e; s < max; ++i) {
                e = i+1 >= nbins ? max : s+gap;
                NumericRangeQuery<Long> rq =
                    NumericRangeQuery.newLongRange(field, s, e, true, e >= max);
                TopDocs hits = searcher.search(rq, filter, max.intValue());
                counts[i] = hits.totalHits;
                s = e;
            }
            
            this.counts = new int[i];
            for (int j = 0; j < i; ++j)
                this.counts[j] = counts[j];
        }

        Long getMin (Terms terms) throws IOException {
            return NumericUtils.getMinLong(terms);
        }
        Long getMax (Terms terms) throws IOException {
            return NumericUtils.getMaxLong(terms);
        }
        int compare (Long n1, Long n2) {
            return n1.compareTo(n2);
        }

        public Long getStart (int bin) {
            return min + bin*gap;
        }
        
        public Long getEnd (int bin) {
            return Math.min(max, getStart(bin) + gap);
        }

        Query getQuery (int bin) {
            if (bin < 0 || bin >= counts.length)
                throw new IllegalArgumentException
                    ("Invalid bin "+bin+" for LongRangeField");
            
            return NumericRangeQuery.newLongRange
                (field, getStart (bin), getEnd (bin),
                 true, bin+1 >= counts.length);
        }
        
        Filter getFilter (int bin) {
            if (bin < 0 || bin >= counts.length)
                throw new IllegalArgumentException
                    ("Invalid bin "+bin+" for LongRangeField");
            
            return NumericRangeFilter.newLongRange
                (field, getStart (bin), getEnd (bin),
                 true, bin+1 >= counts.length);            
        }
    }

    public static class IntRangeField extends AbstractRangeField<Integer> {
        final int gap;
        IntRangeField (TextIndexer indexer, Class kind, String field)
            throws IOException {
            this (indexer, kind, field, DEFAULT_RANGE_BINS);
        }
        IntRangeField (TextIndexer indexer, Class kind, String field,
                       int nbins) throws IOException {
            super (indexer, kind, field, nbins);
            gap = Math.max((int)((double)(max - min)/nbins+0.5), 1);
            int[] counts = new int[nbins];
            
            IndexSearcher searcher = indexer.getSearcher();
            Filter filter = new TermFilter
                (new Term (FIELD_KIND, kind.getName()));

            int i = 0;
            for (int s = min, e; s < max; ++i) {
                e = i+1 >= nbins ? max : s+gap;
                NumericRangeQuery<Integer> rq =
                    NumericRangeQuery.newIntRange(field, s, e, true, e >= max);
                TopDocs hits = searcher.search(rq, filter, max.intValue());
                counts[i] = hits.totalHits;
                s = e;
            }
            
            this.counts = new int[i];
            for (int j = 0; j < i; ++j)
                this.counts[j] = counts[j];
        }

        Integer getMin (Terms terms) throws IOException {
            return NumericUtils.getMinInt(terms);
        }
        Integer getMax (Terms terms) throws IOException {
            return NumericUtils.getMaxInt(terms);
        }
        int compare (Integer n1, Integer n2) {
            return n1.compareTo(n2);
        }

        public Integer getStart (int bin) {
            return min + bin*gap;
        }
        
        public Integer getEnd (int bin) {
            return Math.min(max, getStart(bin) + gap);
        }

        Query getQuery (int bin) {
            if (bin < 0 || bin >= counts.length)
                throw new IllegalArgumentException
                    ("Invalid bin "+bin+" for LongRangeField");
            
            return NumericRangeQuery.newIntRange
                (field, getStart (bin), getEnd (bin),
                 true, bin+1 >= counts.length);
        }
        
        Filter getFilter (int bin) {
            if (bin < 0 || bin >= counts.length)
                throw new IllegalArgumentException
                    ("Invalid bin "+bin+" for LongRangeField");
            
            return NumericRangeFilter.newIntRange
                (field, getStart (bin), getEnd (bin),
                 true, bin+1 >= counts.length);            
        }
    }

    public static class MatchFragment {
        public final String field;
        public final String fragment;

        protected MatchFragment (String field, String fragment) {
            this.field = field;
            this.fragment = fragment;
        }

        public String toString () {
            return "MatchFragment{field="+field+",fragment="+fragment+"}";
        }
    }
    
    public static class SearchResult /*implements java.io.Serializable*/ {

        String key;
        String query;
        List<Facet> facets = new ArrayList<Facet>();
        //final List matches = new CopyOnWriteArrayList ();
        final BlockingQueue matches = new LinkedBlockingQueue ();
        int count;
        SearchOptions options;
        final long timestamp = System.currentTimeMillis();
        AtomicLong stop = new AtomicLong ();

        transient ReentrantLock lock = new ReentrantLock ();
        transient ReentrantLock wlck = new ReentrantLock (); // write lock
        transient Condition finished = lock.newCondition();
        transient Set<String> keys = new HashSet<String>();
        
        static final Pattern fragRe = Pattern.compile("<b>([^(<\\/)]+)</b>");
        Map<Object, MatchFragment[]> fragments = new ConcurrentHashMap<>();

        SearchResult () {}
        SearchResult (SearchOptions options, String query) {
            this.options = options;
            this.query = query;            
        }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getQuery () { return query; }
        public SearchOptions getOptions () { return options; }
        public List<Facet> getFacets () { return facets; }
        public Facet getFacet (String name) {
            for (Facet f : facets) {
                if (name.equalsIgnoreCase(f.getName()))
                    return f;
            }
            return null;
        }
        public int size () { return matches.size(); }
        public Object get (int index) {
            throw new UnsupportedOperationException
                ("get(index) is no longer supported; please use copyTo()");
        }
        
        protected static MatchFragment parseFragment (String fragment) {
            MatchFragment frag = null;
            for (String f : fragment.split("\n")) {
                Matcher m = fragRe.matcher(f);
                if (m.find()) {
                    // extract the field name [NAME]<b>...</b>[/NAME]
                    int pe = m.start(1);
                    while (--pe >= 0 && f.charAt(pe) != ']')
                        ;
                    String field = null;
                    if (pe > 0) {
                        int ps = pe;
                        while (ps > 0 && f.charAt(--ps) != '[')
                            ;
                        String s = f.substring(Math.max(0, ps+1), pe);
                        if (f.charAt(ps) == '[')
                            field = s; // we have field name in its entirety
                    }
                        
                    int qs = m.end(1);
                    while (++qs < f.length() && f.charAt(qs) != '[')
                        ;
                    if (qs < f.length() && f.charAt(qs+1) == '/') {
                        int qe = ++qs; // skip over /
                        while (qe < f.length() && f.charAt(qe) != ']')
                            ++qe;
                        String t = f.substring(qs+1, qe);
                        if (f.charAt(qe) == ']') {
                            if (field == null)
                                field = t; // we have field name in its entirety
                            else if (!field.equals(t))
                                Logger.warn("Expect field name \""+field+"\""
                                            +" but got \""+t+"\"!");
                        }
                    }

                    if (field == null) {
                        // the fragment doesn't capture the field name
                        Logger.warn("Fragment doesn't capture field name: "+f);
                    }
                    MatchFragment mf = new MatchFragment
                        (field, f.substring(pe+1, qs-1));

                    if (frag == null
                        || (frag.field == null && mf.field != null)) {
                        frag = mf;
                    }
                }
            }
            
            return frag;
        }
        
        // fill the given list with value starting at start up to start+count
        public int copyTo (List list, int start, int count) {
            wlck.lock();
            try {
                if (start >= matches.size()) {
                    return 0;
                }
                
                Iterator it = matches.iterator();
                int i = 0;
                for (; i < start && it.hasNext(); ++i)
                    it.next(); // skip
                
                for (i = 0; i < count && it.hasNext(); ++i) {
                    list.add(it.next());
                }
                return i;
            }
            finally {
                wlck.unlock();
            }
        }
        
        public List getMatches () {
            wlck.lock();
            try {
                return new ArrayList (matches);
            }
            finally {
                wlck.unlock();
            }
        }
        public List getMatchesAndWaitIfNotFinished () {
            lock.lock();
            try {
                while (!finished())
                    finished.await();
                return getMatches ();
            }
            catch (InterruptedException ex) {
                ex.printStackTrace();
                return getMatches ();
            }
            finally {
                lock.unlock();
            }
        }

        public void updateCacheWhenComplete (String... keys) {
            for (String key : keys) {
                this.keys.add(key);
            }
        }
        
        public boolean isEmpty () { return matches.isEmpty(); }
        public int count () { return count; }
        public long getTimestamp () { return timestamp; }
        public long elapsed () { return stop.get() - timestamp; }
        public long getStopTime () { return stop.get(); }
        public boolean finished () { return stop.get() >= timestamp; }

        public String toString () { return getClass().getName()+"@"+key; }
        public SearchContextAnalyzer getSearchContextAnalyzer(){
            return null;
        }

        protected void add (Object obj, MatchFragment... fragments) {
            wlck.lock();
            try {
                matches.add(obj);
                this.fragments.put(obj, fragments);
            }
            finally {
                wlck.unlock();
            }
        }

        public MatchFragment[] getFragments (Object obj) {
            return fragments.get(obj);
        }
        
        protected void done () {
            lock.lock();
            try {
                stop.set(System.currentTimeMillis());
                for (String key : keys) {
                    IxCache.set(key, this);
                }
                finished.signal();
            }
            finally {
                lock.unlock();
            }
        }

        private void writeObject(java.io.ObjectOutputStream out)
            throws IOException {
            wlck.lock();
            try {
                out.defaultWriteObject();
            }
            finally {
                wlck.unlock();
            }
        }
        
        private void readObject(java.io.ObjectInputStream in)
            throws IOException, ClassNotFoundException {
            if (wlck == null) {
                lock = new ReentrantLock ();
                wlck = new ReentrantLock ();
                finished = lock.newCondition();
                keys = new HashSet<String>();
            }
            
            wlck.lock();
            try {
                in.defaultReadObject();
            }
            finally {
                wlck.unlock();
            }
        }
        
        private void readObjectNoData() throws ObjectStreamException {
        }
    }

    public static class SuggestResult {
        CharSequence key, highlight;
        SuggestResult (CharSequence key, CharSequence highlight) {
            this.key = key;
            this.highlight = highlight;
        }

        public CharSequence getKey () { return key; }
        public CharSequence getHighlight () { return highlight; }
    }

    class SuggestLookup {
        String name;
        File dir;
        AtomicInteger dirty = new AtomicInteger ();
        AnalyzingInfixSuggester lookup;
        long lastRefresh;

        SuggestLookup (File dir) throws IOException {
            boolean isNew = false;
            if (!dir.exists()) {
                dir.mkdirs();
                isNew = true;
            }
            else if (!dir.isDirectory()) 
                throw new IllegalArgumentException ("Not a directory: "+dir);

            lookup = new AnalyzingInfixSuggester 
                (LUCENE_VERSION, new NIOFSDirectory 
                 (dir, NoLockFactory.getNoLockFactory()), indexAnalyzer);
            
            //If there's an error getting the index count, it probably wasn't 
            //saved properly. Treat it as new if an error is thrown.
            if (!isNew) {
                try{
                    lookup.getCount();
                }
                catch (Exception e) {
                    isNew=true;
                    Logger.warn("Error building lookup " + dir.getName()
                                + " will reinitialize");
                }
            }
            
            if (isNew) {
                Logger.debug("Initializing lookup "+dir.getName());
                build ();
            }
            else {
                Logger.debug(lookup.getCount()
                             +" entries loaded for "+dir.getName());
            }

            this.dir = dir;
            this.name = dir.getName();
        }

        SuggestLookup (String name) throws IOException {
            this (new File (suggestDir, name));
        }

        void add (BytesRef text, Set<BytesRef> contexts, 
                  long weight, BytesRef payload) throws IOException { 
            lookup.update(text, contexts, weight, payload);
            incr ();
        }

        void add (String text) throws IOException {
            BytesRef ref = new BytesRef (text);
            lookup.update(ref, null, 0, ref);
            incr ();
        }

        void incr ()  {
            dirty.incrementAndGet();
        }

        synchronized void refresh () throws IOException {
            long start = System.currentTimeMillis();
            lookup.refresh();
            lastRefresh = System.currentTimeMillis();
            Logger.debug(lookup.getClass().getName()
                         +" refreshs "+lookup.getCount()+" entries in "
                         +String.format("%1$.2fs", 
                                        1e-3*(lastRefresh - start)));
            dirty.set(0);
        }

        void close () throws IOException {
            lookup.close();
        }

        long build () throws IOException {
            IndexReader reader = DirectoryReader.open(indexWriter, true);
            // now weight field
            long start = System.currentTimeMillis();
            lookup.build(new DocumentDictionary (reader, name, null));
            long count = lookup.getCount();
            Logger.debug(lookup.getClass().getName()
                         +" builds "+count+" entries in "
                         +String.format("%1$.2fs", 
                                        1e-3*(System.currentTimeMillis()
                                              - start)));
            return count;
        }

        List<SuggestResult> suggest (CharSequence key, int max)
            throws IOException {
            if (dirty.get() > 0)
                refresh ();

            List<Lookup.LookupResult> results = lookup.lookup
                (key, null, false, max);

            List<SuggestResult> m = new ArrayList<SuggestResult>();
            for (Lookup.LookupResult r : results) {
                m.add(new SuggestResult (r.payload.utf8ToString(), r.key));
            }

            return m;
        }
    }

    final SearchResultPayload POISON_PAYLOAD = new SearchResultPayload ();    
    class SearchResultPayload implements Comparable<SearchResultPayload> {
        SearchResult result;
        TopDocs hits;
        IndexSearcher searcher;
        SearchOptions options;
        int total, offset, requeued = 0;
        final long epoch = System.currentTimeMillis();
        FastVectorHighlighter fvh;
        FieldQuery fq;
        
        SearchResultPayload () {}
        
        SearchResultPayload (SearchResult result, TopDocs hits,
                             IndexSearcher searcher,
                             Query query) throws IOException {
            this.result = result;
            this.hits = hits;
            this.searcher = searcher;
            this.options = result.options;
            result.count = hits.totalHits; 
            total = Math.max(0, Math.min(options.max(), result.count));
            offset = Math.min(options.skip, total);
            fvh = new FastVectorHighlighter ();
            fq = fvh.getFieldQuery(query, searcher.getIndexReader());
        }

        void fetch () throws Exception {
            String thread = Thread.currentThread().getName();
            try {
                int size = offset + fetch (total);
                // FIXME: THIS IS THE HARD LIMIT THAT SHOULD BE IN A CONFIG FILE!!!
                int limit = 1000; 

                if (size > limit) {
                    Logger.warn("Search results ("+size+") exceed allowed size: "+limit);
                    result.done();
                }
                else if (size < total) {
                    // FIXME: make this configurable
                    if (true || requeued < 20) {
                        // requeue this payload
                        Logger.warn(thread
                                    +": unable to fetch payload "+result
                                    +" within alotted time; requeuing ("
                                    +(requeued+1)+") this "
                                    +"payload "+result.size()+"!");
                        ++requeued;
                        if (!fetchQueue.offer(this, 5, TimeUnit.SECONDS)) {
                            Logger.warn
                                (thread+": payload "+result+" fails "
                                 +"to load fully due to fetchQueue timeout!");
                            result.done();
                        }
                    }
                    else {
                        Logger.warn(thread
                                    +": payload "+result+" has been requeued "
                                    +requeued+" times; it's time to move on "
                                    +"dude!");
                        result.done();
                    }
                }
                if (DEBUG (1)) {
                    Logger.debug(thread
                                 +": **** fetchQueue size = "
                                 +fetchQueue.size()+" ("+result.getKey()
                                 +") ****");
                    if (!fetchQueue.isEmpty()) {
                        SearchResultPayload res = fetchQueue.peek();
                        Logger.debug(thread+": **** next in queue: "
                                     +res.result.getKey()+" ("
                                     +res.result.size()+"/"
                                     +res.result.count+") ****");
                    }
                }
            }
            finally {
                if (result.size() == total) {
                    result.done();
                }

                if (result.finished())
                    releaseSearcher (searcher);
            }
        }

        Object findObject (IndexableField kind, IndexableField id)
            throws Exception {
            
            Number n = id.numericValue();
            Object value = ObjectFactory.get
                (Class.forName(kind.stringValue()),
                 n != null ? n.longValue() : id.stringValue(),
                 options.expand.toArray(new String[0]));
            
            if (value == null) {
                Logger.warn
                    (kind.stringValue()+":"+id
                     +" not available in persistence store!");
            }

            return value;
        }
        
            
        int fetch (int size)  throws IOException {
            size = Math.min(options.top, Math.min(total - offset, size));
            int i = result.size();
            long start = System.currentTimeMillis();
            // give each payload a small amount of time to do its business..
            // if it isn't done by then, we push it back to the end of the
            // queue!
            // FIXME: make this configurable!
            for (long elapsed = 0l; i < size && elapsed < 500l; ++i) {
                int docId = hits.scoreDocs[i+offset].doc;
                Document doc = searcher.doc(docId);
                
                final IndexableField kind = doc.getField(FIELD_KIND);
                if (kind != null) {
                    String field = kind.stringValue()+"._id";
                    final IndexableField id = doc.getField(field);
                    if (id != null) {
                        if (DEBUG (2)) {
                            Logger.debug("++ matched doc "
                                         +field+"="+id.stringValue());
                        }
                                                
                        try {
                            Object value = findObject (kind, id);
                            if (value != null) {
                                String[] fragments = fvh.getBestFragments
                                    (fq, searcher.getIndexReader(),
                                     docId, "text", 2000, 10);
                                List<MatchFragment> lmf = new ArrayList<>();
                                if (fragments != null && fragments.length > 0) {
                                    Logger.debug("## found "+fragments.length
                                                 +" fragment(s) in document "
                                                 +id+"!");
                                    for (String f : fragments) {
                                        //Logger.debug(">>> "+f);
                                        MatchFragment mf =
                                            SearchResult.parseFragment(f);
                                        if (mf != null) {
                                            Logger.debug(">>> "+mf);
                                            lmf.add(mf);
                                        }
                                    }
                                }
                                
                                result.add(value,
                                           lmf.toArray(new MatchFragment[0]));
                            }
                        }
                        catch (Exception ex) {
                            Logger.trace("Can't locate object "
                                         +field+":"+id.stringValue(), ex);
                        }
                    }
                    else {
                        Logger.error("Index corrupted; document "
                                     +"doesn't have field "+field);
                    }
                }
                elapsed = System.currentTimeMillis() - start;
            }
            
            if (DEBUG (1)) {
                Logger.debug(Thread.currentThread().getName()+": fetched "+i
                             +"/"+size+" for payload "+result+" in "
                             +(System.currentTimeMillis()-start)+"ms!");
            }
            
            return i;
        }

        public int compareTo (SearchResultPayload res) {
            int d0 = result.count - result.size();
            int d1 = res.result.count - res.result.size();
            return d0 - d1;
        }
    }
        
    class FetchWorker implements Runnable {
        FetchWorker () {
        }

        public void run () {
            Logger.debug(Thread.currentThread()
                         +": FetchWorker started at "+new Date ());
            try {
                for (SearchResultPayload payload; (payload = fetchQueue.take())
                         != POISON_PAYLOAD
                         && !Thread.currentThread().isInterrupted(); ) {
                    try {
                        long start = System.currentTimeMillis();
                        Logger.debug(Thread.currentThread()
                                     +": fetching payload "
                                     +payload.hits.totalHits
                                     +" for "+payload.options);
                        payload.fetch();
                        Logger.debug(Thread.currentThread()+": ## fetched "
                                     +payload.result.size()
                                     +" for result "+payload.result
                                     +" in "+String.format
                                     ("%1$dms", 
                                      System.currentTimeMillis()-start));
                    }
                    catch (IOException ex) {
                        Logger.error("Error in processing payload", ex);
                    }
                }
                Logger.debug(Thread.currentThread()
                             +": FetchWorker stopped at "+new Date());
            }
            catch (Exception ex) {
                //ex.printStackTrace();
                Logger.trace(Thread.currentThread()+" stopped", ex);
            }
        }
    }

    class FetchCleaner implements Runnable {
        FetchCleaner () {}

        public void run () {
            String thread = Thread.currentThread().getName();
            try {
                int n = sweep ();
                Logger.debug(thread+": "+new Date()+": FetchCleaner swept "
                             +n+" expired payload");
            }
            catch (Exception ex) {
                Logger.error(thread+": stopped", ex);
            }
        }

        int sweep () throws Exception {
            List<SearchResultPayload> expired = new ArrayList<>();
            long current = System.currentTimeMillis();
            for (SearchResultPayload res : fetchQueue) {
                double elapsed = (current - res.epoch)/1000.;
                if (elapsed > FETCH_TIMEOUT && res != POISON_PAYLOAD)
                    expired.add(res);
            }

            for (SearchResultPayload res : expired) {
                Logger.debug("Fetch payload "+res.result.getKey()
                             +" is expired ("
                             +String.format("%1$.1fs",(current-res.epoch)/1000.)
                             +"); removing from fetchQueue!");
                fetchQueue.remove(res);
                res.result.done();
                releaseSearcher (res.searcher);
            }
            return expired.size();
        }
    }
    
    private File baseDir;
    private File suggestDir;
    private Directory indexDir;
    private Directory taxonDir;
    private IndexWriter indexWriter;
    private DirectoryReader indexReader;
    private Analyzer indexAnalyzer;
    private DirectoryTaxonomyWriter taxonWriter;
    private FacetsConfig facetsConfig;
    private ConcurrentMap<String, SuggestLookup> lookups;
    private ConcurrentMap<String, SortField.Type> sorters;
    private AtomicLong lastModified = new AtomicLong ();
    
    private ExecutorService threadPool = Executors.newCachedThreadPool();
    private ScheduledExecutorService scheduleThreadPool =
        Executors.newSingleThreadScheduledExecutor();
    
    private Future[] fetchWorkers = new Future[0];
    private BlockingQueue<SearchResultPayload> fetchQueue =
        new LinkedBlockingQueue<>();
    private SearcherManager searcherManager;
        
    static ConcurrentMap<File, TextIndexer> indexers = 
        new ConcurrentHashMap<File, TextIndexer>();

    private Reflections reflections = new Reflections("ix");
    private Map<Class<?>, String[]> subTypes = new ConcurrentHashMap<>();

    public IndexReader getIndexReader() {
        return indexReader;
    }

    public static TextIndexer getInstance (File baseDir) throws IOException {
        if (indexers.containsKey(baseDir)) 
            return indexers.get(baseDir);

        try {
            TextIndexer indexer = new TextIndexer (baseDir);
            TextIndexer old = indexers.putIfAbsent(baseDir, indexer);
            return old == null ? indexer : old;
        }
        catch (IOException ex) {
            return indexers.get(baseDir);
        }
    }

    private TextIndexer () {
        setFetchWorkers (FETCH_WORKERS);
    }
    
    public TextIndexer (File dir) throws IOException {
        if (!dir.isDirectory())
            throw new IllegalArgumentException ("Not a directory: "+dir);

        File index = new File (dir, "index");
        if (!index.exists())
            index.mkdirs();
        indexDir = new NIOFSDirectory 
            (index, NoLockFactory.getNoLockFactory());

        try {
            IndexInput input = indexDir.openInput
                ("lastModified", IOContext.DEFAULT);
            lastModified.set(input.readLong());
            input.close();
            Logger.debug("Index "+index+": lastModified "
                         +new Date(lastModified.get()));
        }
        catch (Exception ex) {
        }

        File taxon = new File (dir, "facet");
        if (!taxon.exists())
            taxon.mkdirs();
        taxonDir = new NIOFSDirectory
            (taxon, NoLockFactory.getNoLockFactory());

        indexAnalyzer = createIndexAnalyzer ();
        IndexWriterConfig conf = new IndexWriterConfig 
            (LUCENE_VERSION, indexAnalyzer);
        indexWriter = new IndexWriter (indexDir, conf);
        searcherManager = new SearcherManager (indexWriter, true, null);
        indexReader = DirectoryReader.open(indexWriter, true);  
        taxonWriter = new DirectoryTaxonomyWriter (taxonDir);

        facetsConfig = loadFacetsConfig (new File (dir, FACETS_CONFIG_FILE));
        if (facetsConfig == null) {
            int size = taxonWriter.getSize();
            if (size > 0) {
                Logger.warn("There are "+size+" dimensions in "
                            +"taxonomy but no facet\nconfiguration found; "
                            +"facet searching might not work properly!");
            }
            facetsConfig = new FacetsConfig ();
            facetsConfig.setMultiValued(DIM_CLASS, true);
            facetsConfig.setRequireDimCount(DIM_CLASS, true);
        }

        suggestDir = new File (dir, "suggest");
        if (!suggestDir.exists())
            suggestDir.mkdirs();

        // load saved lookups
        lookups = new ConcurrentHashMap<String, SuggestLookup>();
        for (File f : suggestDir.listFiles()) {
            if (f.isDirectory()) {
                try {
                    lookups.put(f.getName(), new SuggestLookup (f));
                }
                catch (IOException ex) {
                    Logger.error("Unable to load lookup from "+f, ex);
                }
            }
        }
        Logger.info("## "+suggestDir+": "
                    +lookups.size()+" lookups loaded!");

        sorters = loadSorters (new File (dir, SORTER_CONFIG_FILE));
        Logger.info("## "+sorters.size()+" sort fields defined!");

        this.baseDir = dir;
        setFetchWorkers (FETCH_WORKERS);

        scheduleThreadPool.scheduleAtFixedRate
            (new FetchCleaner (), FETCH_TIMEOUT,
             FETCH_TIMEOUT/2, TimeUnit.SECONDS);
    }
    
    public void setFetchWorkers (int n) {
        if (n < 1)
            throw new IllegalArgumentException
                ("Number of workers can't be < 1!");
        
        if (n != fetchWorkers.length) {
            for (int i = 0; i < fetchWorkers.length; ++i) {
                try {
                    fetchQueue.put(POISON_PAYLOAD);
                }
                catch (Exception ex) {
                    Logger.error("Can't stop thread", ex);
                }
            }
            
            for (Future f : fetchWorkers)
                if (f != null)
                    f.cancel(true);
        
            fetchWorkers = new Future[n];
            for (int i = 0; i < fetchWorkers.length; ++i)
                fetchWorkers[i] = threadPool.submit(new FetchWorker ());
        }
    }
    
    protected synchronized DirectoryReader getReader () throws IOException {
        DirectoryReader reader = DirectoryReader.openIfChanged(indexReader);
        if (reader != null) {
            indexReader.close();
            indexReader = reader;
        }
        return indexReader;
    }

    protected IndexSearcher getSearcher () throws IOException {
        //return new IndexSearcher (getReader ());
        return searcherManager.acquire();
    }

    protected void releaseSearcher (IndexSearcher searcher) throws IOException {
        searcherManager.release(searcher);
        searcherManager.maybeRefresh();
    }

    static boolean DEBUG (int level) {
        Global g = Global.getInstance();
        if (g != null)
            return g.debug(level);
        return false;
    }

    static Analyzer createIndexAnalyzer () {
        Map<String, Analyzer> fields = new HashMap<String, Analyzer>();
        fields.put(FIELD_ID, new KeywordAnalyzer ());
        fields.put(FIELD_KIND, new KeywordAnalyzer ());
        return  new PerFieldAnalyzerWrapper 
            (new StandardAnalyzer (LUCENE_VERSION), fields);
    }

    /**
     * Create a empty RAM instance. This is useful for searching/filtering
     * of a subset of the documents stored.
     */
    public TextIndexer createEmptyInstance () throws IOException {
        TextIndexer indexer = new TextIndexer ();
        indexer.indexDir = new RAMDirectory ();
        indexer.taxonDir = new RAMDirectory ();
        return config (indexer);
    }

    public TextIndexer createEmptyInstance (File dir) throws IOException {
        TextIndexer indexer = new TextIndexer ();

        File index = new File (dir, "index");
        if (!index.exists())
            index.mkdirs();
        indexer.indexDir = new NIOFSDirectory
            (index, NoLockFactory.getNoLockFactory());
        
        File taxon = new File (dir, "facet");
        if (!taxon.exists())
            taxon.mkdirs();
        indexer.taxonDir = new NIOFSDirectory
            (taxon, NoLockFactory.getNoLockFactory());
        
        return config (indexer);
    }

    public Map<String,Integer> getFacetLabelCounts(String facetName)
        throws IOException {
        IndexSearcher searcher = new IndexSearcher(indexReader);
        TaxonomyReader taxoReader = new DirectoryTaxonomyReader(taxonDir);
        FacetsCollector fc = new FacetsCollector();
        FacetsCollector.search(searcher, new MatchAllDocsQuery(), 1000000, fc);
        Facets facets = new FastTaxonomyFacetCounts(taxoReader, facetsConfig, fc);
        List<FacetResult> frs = facets.getAllDims(999999);
        Map<String,Integer> facetValues = new HashMap<>();
        for (FacetResult fr : frs) {
            if (fr.dim.equals(facetName)) {
                LabelAndValue[] lavs = fr.labelValues;
                for (LabelAndValue lav : lavs)
                    facetValues.put(lav.label, lav.value.intValue());
            }
        }
        return facetValues;
    }

    protected TextIndexer config (TextIndexer indexer) throws IOException {
        indexer.indexAnalyzer = createIndexAnalyzer ();
        IndexWriterConfig conf = new IndexWriterConfig 
            (LUCENE_VERSION, indexer.indexAnalyzer);
        indexer.indexWriter = new IndexWriter (indexer.indexDir, conf);
        indexer.indexReader = DirectoryReader.open(indexer.indexWriter, true);
        indexer.taxonWriter = new DirectoryTaxonomyWriter (indexer.taxonDir);
        indexer.facetsConfig = new FacetsConfig ();
        for (Map.Entry<String, FacetsConfig.DimConfig> me
                 : facetsConfig.getDimConfigs().entrySet()) {
            String dim = me.getKey();
            FacetsConfig.DimConfig dconf = me.getValue();
            indexer.facetsConfig.setHierarchical(dim, dconf.hierarchical);
            indexer.facetsConfig.setMultiValued(dim, dconf.multiValued);
            indexer.facetsConfig.setRequireDimCount
                (dim, dconf.requireDimCount);
        }
        // shouldn't be using for any 
        indexer.lookups = new ConcurrentHashMap<String, SuggestLookup>();
        indexer.sorters = new ConcurrentHashMap<String, SortField.Type>();
        indexer.sorters.putAll(sorters);
        return indexer;
    }

    public List<SuggestResult> suggest 
        (String field, CharSequence key, int max) throws IOException {
        SuggestLookup lookup = lookups.get(field);
        if (lookup == null) {
            Logger.debug("Unknown suggest field \""+field+"\"");
            return new ArrayList ();
        }
        
        return lookup.suggest(key, max);
    }

    public LongRangeField getLongRangeField (Class kind, String field)
        throws IOException {
        // should allow the number of bins specified!
        return new LongRangeField (this, kind, field);
    }

    public IntRangeField getIntRangeField (Class kind, String field)
        throws IOException {
        return new IntRangeField (this, kind, field);
    }

    public Collection<String> getSuggestFields () {
        return Collections.unmodifiableCollection(lookups.keySet());
    }

    public int size () {
        try {
            return getReader().numDocs();
        }
        catch (IOException ex) {
            Logger.trace("Can't retrieve NumDocs", ex);
        }
        return -1;
    }

    public SearchResult search (String text, int size) throws IOException {
        return search (new SearchOptions (null, size, 0, 10), text);
    }

    public SearchResult search 
        (SearchOptions options, String text) throws IOException {
        return search (options, text, null);
    }
    
    public SearchResult search 
        (SearchOptions options, String text, Collection subset)
        throws IOException {
        //this is a quick and dirty way to have a cleaner-looking
        //query for display
        String qtext = text;
        SearchResult searchResult = new SearchResult (options, text);

        Query query = null;
        if (text == null) {
            query = new MatchAllDocsQuery ();
        }
        else {
            try {
                QueryParser parser = new QueryParser ("text", indexAnalyzer);
                query = parser.parse(qtext);
            }
            catch (ParseException ex) {
                Logger.warn("Can't parse query expression: "+qtext, ex);
            }
        }

        if (query != null) {
            Filter f = null;
            if (subset != null) {
                Set<String> kinds = new HashSet<>();

                List<Term> terms = getTerms (subset, kinds);
                //Logger.debug("Filter terms "+subset.size());
                if (!terms.isEmpty()) {
                    if (kinds.size() == 1) {
                        // add an extra security if we have a homogeneous list
                        f = new ChainedFilter (new Filter[] { 
                                new TermsFilter (terms), new TermFilter 
                                (new Term (FIELD_KIND, 
                                           kinds.iterator().next()))
                            }, ChainedFilter.AND);
                    }
                    else {
                        f = new TermsFilter (terms);
                    }
                }
            }
            else if (options.kind != null) {

                f = new FieldCacheTermsFilter (FIELD_KIND, getSubTypesOf(options.kind));
            }
            search (searchResult, query, f);
        }
        
        return searchResult;
    }
    private <T> String[] getSubTypesOf(Class<T> c){
       return subTypes.computeIfAbsent(c, k ->{
           Set<String> kinds = new TreeSet<>();
           kinds.add(k.getName());

           for (Class klass : reflections.getSubTypesOf(k)) {
               kinds.add(klass.getName());
           }
           return kinds.toArray(new String[kinds.size()]);
       });
    }

    public SearchResult filter (Collection subset)  throws IOException {
        SearchOptions options = new SearchOptions
            (null, subset.size(), 0, subset.size()/2);
        return filter (options, subset);
    }

    protected List<Term> getTerms (Collection subset) {
        return getTerms (subset, null);
    }

    protected List<Term> getTerms (Collection subset, Set<String> kinds) {
        List<Term> terms = new ArrayList<Term>();
        for (Iterator it = subset.iterator(); it.hasNext(); ) {
            Object obj = it.next();
            Term term = getTerm (obj);
            if (term != null) {
                if (kinds != null)
                    kinds.add(obj.getClass().getName());
                terms.add(term);
            }
        }
        return terms;
    }
    
    protected TermsFilter getTermsFilter (Collection subset) {
        return new TermsFilter (getTerms (subset));
    }
    
    public SearchResult filter (SearchOptions options, Collection subset)
        throws IOException {
        return filter (options, getTermsFilter (subset));
    }

    public TermVectors getTermVectors (Class kind, String field)
        throws IOException {
        return new TermVectorsCollector (kind, field).termVectors();
    }
    
    public TermVectors getTermVectors (Class kind, String field,
                                       Map<String, String> filters)
        throws IOException {
        Term[] terms = new Term[0];
        if (filters != null && !filters.isEmpty()) {
            terms = new Term[filters.size()];
            int i = 0;
            for (Map.Entry<String, String> me : filters.entrySet()) {
                terms[i++] = new Term (me.getKey(), me.getValue());
            }
        }
        return new TermVectorsCollector(kind, field, terms).termVectors();
    }

    Facet toFacet (final TermVectors tvs, final boolean desc, int topK)
        throws IOException {
        Facet facet = new Facet (tvs.getField());
        Map<String, Integer> terms = new TreeMap<>((t1, t2) -> {
                Integer n1 = tvs.getTermCount(t1);
                Integer n2 = tvs.getTermCount(t2);
                if (n1 != null && n2 != null) {
                    if (n1 != n2)
                        return desc ? n2 - n1 : n1 - n2;
                }
                return desc ? t2.compareTo(t1) : t1.compareTo(t2);
            });
        // sort the terms
        for (Map.Entry<String, Map> me : tvs.getTerms().entrySet()) {
            terms.put(me.getKey(), (Integer)me.getValue().get("nDocs"));
        }
        
        if (topK < 1)
            topK = terms.size();
        
        int k = 0;
        for (Map.Entry<String, Integer> me : terms.entrySet()) {
            if (k++ < topK)
                facet.values.add(new FV (me.getKey(), me.getValue()));
        }
        return facet;
    }

    public Facet getFacet (Class kind, String field) throws IOException {
        return getFacet (kind, field, true, 100);
    }
    
    public Facet getFacet (Class kind, String field, boolean desc, int topK)
        throws IOException {
        TermVectors tvs = getTermVectors (kind, field);
        Facet facet = null;
        if (tvs != null) {
            facet = toFacet (tvs, desc, topK);
        }
        return facet;
    }
    
    public SearchResult range (SearchOptions options, String field,
                               Long min, Long max) throws IOException {
        Query query = NumericRangeQuery.newLongRange
            (field, min, max, true /* minInclusive?*/, false/*maxInclusive?*/);
        
        Filter filter = null;
        if (options.kind != null) {
            filter = new TermFilter
                (new Term (FIELD_KIND, options.kind.getName()));
        }
        
        return search (getSearcher (), new SearchResult (options, null),
                       query, filter);
    }

    public SearchResult range (SearchOptions options, String field,
                               Double min, Double max) throws IOException {
        Query query = NumericRangeQuery.newDoubleRange
            (field, min, max, true /* minInclusive?*/, true/*maxInclusive?*/);
        
        Filter filter = null;
        if (options.kind != null) {
            filter = new TermFilter
                (new Term (FIELD_KIND, options.kind.getName()));
        }
        
        return search (getSearcher (), new SearchResult (options, null),
                       query, filter);
    }
    
    protected SearchResult filter (SearchOptions options, Filter filter)
        throws IOException {
        return search (getSearcher (), new SearchResult (options, null),
                       new MatchAllDocsQuery (), filter);
    }

    protected SearchResult search (SearchResult searchResult, 
                                   Query query, Filter filter)
        throws IOException {
        return search (getSearcher (), searchResult, query, filter);
    }
    
    protected SearchResult search (IndexSearcher searcher,
                                   SearchResult searchResult, 
                                   Query query, Filter filter)
        throws IOException {
        SearchOptions options = searchResult.getOptions();
        
        if (DEBUG (1)) {
            Logger.debug("## Query: "
                         +query+"["+query.getClass().getName()+"] Filter: "
                         //+(filter!=null?filter:"none")
                         +" Options:"+options);
        }

        long start = System.currentTimeMillis();
            
        FacetsCollector fc = new FacetsCollector ();
        TaxonomyReader taxon = new DirectoryTaxonomyReader (taxonWriter);
        TopDocs hits = null;
        
        try {
            Sort sorter = null;
            if (!options.order.isEmpty()) {
                List<SortField> fields = new ArrayList<SortField>();
                for (String f : options.order) {
                    boolean rev = false;
                    if (f.charAt(0) == '^') {
                        // sort in reverse
                        f = f.substring(1);
                    }
                    else if (f.charAt(0) == '$') {
                        f = f.substring(1);
                        rev = true;
                    }
                    
                    SortField.Type type = sorters.get(f);
                    if (type != null) {
                        SortField sf = new SortField (f, type, rev);
                        Logger.debug("Sort field (rev="+rev+"): "+sf);
                        fields.add(sf);
                    }
                    else {
                        Logger.warn("Unknown sort field: \""+f+"\"");
                    }
                }
                
                if (!fields.isEmpty())
                    sorter = new Sort (fields.toArray(new SortField[0]));
            }
            
            List<String> drills = options.facets;
            // remove all range facets
            Map<String, List<Filter>> filters =
                new HashMap<String, List<Filter>>();
            
            List<String> remove = new ArrayList<String>();
            for (String f : drills) {
                int pos = f.indexOf('/');
                if (pos > 0) {
                    String facet = f.substring(0, pos);
                    String value = f.substring(pos+1);
                    Logger.warn("facet="+facet+" value="+value);
                    if (facet.charAt(0) == '@') {
                        // numeric range via syntax @field/INDEX; currently
                        // only handle Long range
                        // should really cache this !
                        AbstractRangeField arf = null;
                        try {
                            arf = new LongRangeField
                                (this, options.kind, facet.substring(1));
                        }
                        catch (NumberFormatException ex) {
                            try {
                                arf = new IntRangeField
                                    (this, options.kind, facet.substring(1));
                            }
                            catch (NumberFormatException exx) {
                                //
                                Logger.error("Can't identify field \""
                                             +facet+"\" type!", ex);
                            }
                        }

                        if (arf != null) {
                            List<Filter> fl = filters.get(arf.getField());
                            if (fl == null) {
                                filters.put(arf.getField(),
                                            fl = new ArrayList<>());
                            }
                            
                            try {
                                int bin = Integer.parseInt(value);
                                fl.add(arf.getFilter(bin));
                                Logger.debug("Range filter \""+arf.getField()
                                             +"\": ["+arf.getStart(bin)+","
                                             +arf.getEnd(bin)+") = "
                                             +arf.getCount(bin));
                            }
                            catch (NumberFormatException ex) {
                                Logger.error("Bogus bin index: "+value, ex);
                            }
                        }
                        remove.add(f);
                    }
                    else {
                        for (SearchOptions.FacetRange fr
                                 : options.rangeFacets) {
                            if (facet.equals(fr.field)) {
                                List<Filter> fl = new ArrayList<>();
                                createRangeFilters (fr, value, fl);
                                if (!fl.isEmpty()) {
                                    List<Filter> old = filters.get(facet);
                                    if (old != null)
                                        old.addAll(fl);
                                    else
                                        filters.put(facet, fl);
                                    remove.add(f);
                                }
                            }
                        }
                    }
                }
            }

            drills.removeAll(remove);
            if (!filters.isEmpty()) {
                List<Filter> all = new ArrayList<Filter>();
                if (filter != null)
                    all.add(filter);
                
                for (Map.Entry<String, List<Filter>> me : filters.entrySet()) {
                    ChainedFilter cf = new ChainedFilter
                        (me.getValue().toArray(new Filter[0]),
                         ChainedFilter.OR);
                    all.add(cf);
                }

                filter = new ChainedFilter (all.toArray(new Filter[0]),
                                            ChainedFilter.AND);
            }
            
            int max = Math.max(100, options.max());            
            if (drills.isEmpty()) {
                hits = sorter != null 
                    ? (FacetsCollector.search
                       (searcher, query, filter, max, sorter, fc))
                    : (FacetsCollector.search
                       (searcher, query, filter, max, fc));
                
                Facets facets = new FastTaxonomyFacetCounts
                    (taxon, facetsConfig, fc);
                
                List<FacetResult> facetResults =
                    facets.getAllDims(options.fdim);
                if (DEBUG (1)) {
                    Logger.info("## "+facetResults.size()
                                +" facet dimension(s)");
                }
                
                for (FacetResult result : facetResults) {
                    Facet f = new Facet (result.dim);
                    if (DEBUG (2)) {
                        Logger.info(" + ["+result.dim+"]");
                    }
                    for (int i = 0; i < result.labelValues.length; ++i) {
                        LabelAndValue lv = result.labelValues[i];
                        if (DEBUG (2)) {
                            Logger.info("     \""+lv.label+"\": "+lv.value);
                        }
                        f.values.add(new FV (lv.label, lv.value.intValue()));
                    }
                    searchResult.facets.add(f);
                }
            }
            else {
                DrillDownQuery ddq = new DrillDownQuery (facetsConfig, query);
                // the first term is the drilldown dimension
                for (String dd : options.facets) {
                    int pos = dd.indexOf('/');
                    if (pos > 0) {
                        String facet = dd.substring(0, pos);
                        String value = dd.substring(pos+1);
                        ddq.add(facet, value/*.split("/")*/);
                    }
                    else {
                        Logger.warn("Bogus drilldown syntax: "+dd);
                    }
                }

                Facets facets;
                if (options.sideway) {
                    DrillSideways sideway = new DrillSideways 
                        (searcher, facetsConfig, taxon);
                    DrillSideways.DrillSidewaysResult swResult = 
                        sideway.search(ddq, filter, null, 
                                       max, sorter, false, false);
                    // collector
                    FacetsCollector.search(searcher, ddq, filter, max, fc);
                    
                    facets = swResult.facets;
                    hits = swResult.hits;
                }
                else { // drilldown
                    hits = sorter != null 
                        ? (FacetsCollector.search
                           (searcher, ddq, filter, max, sorter, fc))
                        : (FacetsCollector.search
                           (searcher, ddq, filter, max, fc));
                    
                    facets = new FastTaxonomyFacetCounts
                        (taxon, facetsConfig, fc);
                }
                
                List<FacetResult> facetResults =
                    facets.getAllDims(options.fdim);
                if (DEBUG (1)) {
                    Logger.info("## Drilled "
                                +(options.sideway ? "sideway" : "down")
                                +" "+facetResults.size()
                                +" facets and "+hits.totalHits
                                +" hits; sorter="+sorter);
                }
                
                for (FacetResult result : facetResults) {
                    if (result != null) {
                        if (DEBUG (2)) {
                            Logger.info(" + ["+result.dim+"]");
                        }
                        Facet f = new Facet (result.dim);
                        
                        // make sure the facet value is returned                
                        String label = null; 
                        for (String d : drills) {
                            int pos = d.indexOf('/');
                            if (pos > 0) {
                                if (result.dim.equals(d.substring(0, pos)))
                                    label = d.substring(pos+1);
                            }
                        }
                        
                        for (int i = 0; i < result.labelValues.length; ++i) {
                            LabelAndValue lv = result.labelValues[i];
                            if (DEBUG (2)) {
                                Logger.info
                                    ("     \""+lv.label+"\": "+lv.value);
                            }
                            if (lv.label.equals(label)) {
                                // got it
                                f.values.add(0, new FV (lv.label, 
                                                        lv.value.intValue()));
                                label = null;
                            }
                            else {
                                f.values.add(new FV (lv.label, 
                                                     lv.value.intValue()));
                            }
                        }
                        
                        if (label != null) {
                            Number value =
                                facets.getSpecificValue(result.dim, label);
                            if (value != null) {
                                f.values.add(0, new FV
                                             (label, value.intValue()));
                            }
                            else {
                                Logger.warn
                                    ("Facet \""+result.dim+"\" doesn't any "
                                     +"value for label \""+label+"\"!");
                            }
                        }
                        
                        f.sort();
                        searchResult.facets.add(f);
                    }
                }
            } // facets is empty

            collectRangeFacets (fc, searchResult);
        }
        finally {
            taxon.close();
        }

        if (DEBUG (1)) {
            Logger.debug("## Query executes in "
                         +String.format
                         ("%1$.3fs", 
                          (System.currentTimeMillis()-start)*1e-3)
                         +"..."+hits.totalHits+" hit(s) found!");
        }

        if (options.top > 0) {
            try {
                SearchResultPayload payload = new SearchResultPayload
                    (searchResult, hits, searcher, query);
                if (options.fetch <= 0) {
                    payload.fetch();
                }
                else {
                    // we first block until we have enough result to show;
                    // simulate with a random number of fetch
                    int fetch = 20 + new Random().nextInt(options.fetch);
                    payload.fetch(fetch);
                    
                    if (hits.totalHits > fetch) {
                        if (DEBUG (1)) {
                            Logger.debug("## Fetching remaining "
                                         +(hits.totalHits-fetch)
                                         +" in the background; fetchQueue "
                                         +"size is "+fetchQueue.size());
                        }
                        // now queue the payload so the remainder is fetched in
                        // the background
                        fetchQueue.put(payload);
                    }
                    else {
                        payload.fetch();
                    }
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
                Logger.trace("Can't queue fetch results!", ex);
            }
        }
        
        return searchResult;
    }

    protected void collectRangeFacets (FacetsCollector fc,
                                       SearchResult searchResult)
        throws IOException {
        SearchOptions options = searchResult.getOptions();
        for (SearchOptions.FacetRange frange : options.rangeFacets) {
            if (!frange.range.isEmpty()) {
                Facets facets = createLongRangeFacets (fc, frange);
                if (facets == null)
                    facets = createDoubleRangeFacets (fc, frange);
                
                if (facets != null) {
                    FacetResult result = facets.getTopChildren
                        (frange.range.size(), frange.field);
                    Facet f = new Facet (result.dim);
                    if (DEBUG (1)) {
                        Logger.info(" + ["+result.dim+"]");
                    }
                    for (int i = 0; i < result.labelValues.length; ++i) {
                        LabelAndValue lv = result.labelValues[i];
                        if (DEBUG (1)) {
                            Logger.info("     \""+lv.label+"\": "+lv.value);
                        }
                        f.values.add(new FV (lv.label, lv.value.intValue()));
                    }
                    searchResult.facets.add(f);
                }
            }
        }
    }

    static Facets createLongRangeFacets (FacetsCollector fc,
                                         SearchOptions.FacetRange frange)
        throws IOException {
        Logger.debug("[Range facet: \""+frange.field+"\"");
        
        LongRange[] lrange = new LongRange[frange.range.size()];
        int i = 0;
        for (SearchOptions.Range range : frange.range) {
            String name = range.getName();
            Object r = range.getRange();
            if (r instanceof long[]) {
                long[] lr = (long[])r;
                lrange[i++] = new LongRange (name, lr[0], true, lr[1], true);
            }
            else if (r instanceof int[]) {
                int[] ir = (int[])r;
                long[] lr = new long[ir.length];
                for (int k = 0; k < ir.length; ++k)
                    lr[k] = ir[k];
                lrange[i++] = new LongRange (name, lr[0], true, lr[1], true);
            }
            else
                return null;
        }
        
        return new LongRangeFacetCounts (frange.field, fc, lrange);
    }

    static Facets createDoubleRangeFacets (FacetsCollector fc,
                                           SearchOptions.FacetRange frange)
        throws IOException {
        Logger.debug("[Range facet: \""+frange.field+"\"");
        
        DoubleRange[] drange = new DoubleRange[frange.range.size()];
        int i = 0;
        for (SearchOptions.Range range : frange.range) {
            String name = range.getName();
            Object r = range.getRange();
            if (r instanceof double[]) {
                double[] dr = (double[])r;
                drange[i++] = new DoubleRange (name, dr[0], true, dr[1], true);
            }
            else if (r instanceof float[]) {
                float[] fr = (float[])r;
                double[] dr = new double[fr.length];
                for (int k = 0; k < fr.length; ++k)
                    dr[k] = fr[k];
                drange[i++] = new DoubleRange (name, dr[0], true, dr[1], true);
            }
            else
                return null;
        }
        
        return new DoubleRangeFacetCounts (frange.field, fc, drange);
    }
    
    static void createRangeFilters
        (SearchOptions.FacetRange frange, String value, List<Filter> filters) {
        for (SearchOptions.Range range : frange.range) {
            if (value.equals(range.getName())) {
                Object r = range.getRange();
                if (r != null) {
                    if (r instanceof long[]) {
                        long[] lr = (long[])r;
                        // add this as filter..
                        Logger.debug("adding range filter \""
                                     +value+"\": "+lr[0]+" "+lr[1]);
                        filters.add(FieldCacheRangeFilter.newLongRange
                                    (frange.field, lr[0], lr[1], true, true));
                    }
                    else if (r instanceof int[]) {
                        int[] ir = (int[])r;
                        filters.add(FieldCacheRangeFilter.newIntRange
                                    (frange.field, ir[0], ir[1], true, true));
                    }
                    else if (r instanceof float[]) {
                        float[] fr = (float[])r;
                        filters.add(FieldCacheRangeFilter.newFloatRange
                                    (frange.field, fr[0], fr[1], true, true));
                    }
                    else if (r instanceof double[]) {
                        double[] dr = (double[])r;
                        filters.add(FieldCacheRangeFilter.newDoubleRange
                                    (frange.field, dr[0], dr[1], true, true));
                    }
                    else {
                        throw new IllegalArgumentException
                            ("Bad range type: "+r.getClass());
                    }
                }
            }
        }
    }
        
    protected Term getTerm (Object entity) {
        Term term = null;
        if (entity != null) {
            try {
                Object id = Util.getFieldValue(entity, Id.class);
                if (id == null) {
                    Logger.warn("Entity "+entity+"["
                                +entity.getClass()+"] has no Id field!");
                }
                else {
                    term = new Term (entity.getClass().getName()+".id",
                                     id.toString());
                }
            }
            catch (Exception ex) {
                Logger.error("Can't retrieve @Id value for "+entity, ex);
            }
        }
        return term;
    }
    
    public Document getDoc (Object entity) throws Exception {
        Document doc = null;
        Term term = getTerm (entity);
        if (term != null) {
            IndexSearcher searcher = getSearcher ();
            TopDocs docs = searcher.search(new TermQuery (term), 1);
            //Logger.debug("TermQuery: term="+term+" => "+docs.totalHits);
            if (docs.totalHits > 0)
                doc = searcher.doc(docs.scoreDocs[0].doc);
            releaseSearcher (searcher);
        }
        return doc;
    }

    public JsonNode getDocJson (Object entity) throws Exception {
        Document _doc = getDoc (entity);
        if (_doc == null) {
            return null;
        }
        List<IndexableField> _fields = _doc.getFields();
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode fields = mapper.createArrayNode();
        for (IndexableField f : _fields) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", f.name());
            if (null != f.numericValue()) {
                node.put("value", f.numericValue().doubleValue());
            }
            else {
                node.put("value", f.stringValue());
            }

            ObjectNode n = mapper.createObjectNode();
            IndexableFieldType type = f.fieldType();
            if (type.docValueType() != null)
                n.put("docValueType", type.docValueType().toString());
            n.put("indexed", type.indexed());
            n.put("indexOptions", type.indexOptions().toString());
            n.put("omitNorms", type.omitNorms());
            n.put("stored", type.stored());
            n.put("storeTermVectorOffsets", type.storeTermVectorOffsets());
            n.put("storeTermVectorPayloads", type.storeTermVectorPayloads());
            n.put("storeTermVectorPositions", type.storeTermVectorPositions());
            n.put("storeTermVectors", type.storeTermVectors());
            n.put("tokenized", type.tokenized());
            
            node.put("options", n);
            fields.add(node);
        }
        
        ObjectNode doc = mapper.createObjectNode();
        doc.put("num_fields", _fields.size());
        doc.put("fields", fields);
        return doc;
    }
    
    /**
     * recursively index any object annotated with Entity
     */
    public void add (Object entity) throws IOException {
        if (entity == null 
            || !entity.getClass().isAnnotationPresent(Entity.class)) {
            return;
        }

        Indexable indexable = 
            (Indexable)entity.getClass().getAnnotation(Indexable.class);

        if (indexable != null && !indexable.indexed()) {
            if (DEBUG (2)) {
                Logger.debug(">>> Not indexable "+entity);
            }

            return;
        }

        if (DEBUG (2))
            Logger.debug(">>> Indexing "+entity+"...");
        
        List<IndexableField> fields = new ArrayList<IndexableField>();
        fields.add(new StringField
                   (FIELD_KIND, entity.getClass().getName(), YES));

        instrument (new LinkedList<String>(), new HashSet (), entity, fields);

        Document doc = new Document ();
        for (IndexableField f : fields) {
            if ((f instanceof TextField)
                || (f instanceof StringField)
                || (f instanceof FacetField)
                || (f instanceof TermVectorField)) {
                String text = f.stringValue();
                if (text != null) {
                    if (DEBUG (2))
                        Logger.debug(".."+f.name()+":"
                                     +text+" ["+f.getClass().getName()+"]");
                    
                    //doc.add(new TextField ("text", text, NO));
                    doc.add(new HighlightField
                            ("text", "\n["+f.name()+"]"
                             +text+"[/"+f.name()+"]"));
                }
            }
            
            //Logger.debug(f.name()+": "+f.getClass()+" "+f.fieldType());
            if (!"text".equals(f.name())) {
                doc.add(f);
            }
        }
        
        // now index
        addDoc (doc);
        if (DEBUG (2))
            Logger.debug("<<< "+entity);
    }

    public void addDoc (Document doc) throws IOException {
        doc = facetsConfig.build(taxonWriter, doc);
        if (DEBUG (2))
            Logger.debug("++ adding document "+doc);
        
        indexWriter.addDocument(doc);
        lastModified.set(System.currentTimeMillis());   
    }

    public long lastModified () {
        /*
        try {
            if (!indexReader.isCurrent()) {
                lastModified.set(System.currentTimeMillis());
            }
        }
        catch (IOException ex) {
            Logger.error("Can't check IndexReader status", ex);
        }
        */
        return lastModified.get();
    }

    public void update (Object entity) throws IOException {
        if (!entity.getClass().isAnnotationPresent(Entity.class)) {
            return;
        }

        if (DEBUG (2))
            Logger.debug(">>> Updating "+entity+"...");

        try {
            Object id = null;
            for (Field f : entity.getClass().getFields()) {
                if (f.getAnnotation(Id.class) != null) {
                    id = f.get(entity);
                }
            }

            if (id != null) {
                String field = entity.getClass().getName()+".id";
                indexWriter.deleteDocuments
                    (new Term (field, id.toString()));
                
                if (DEBUG (2))
                    Logger.debug("++ Updating "+field+"="+id);
                
                // now reindex .. there isn't an IndexWriter.update 
                // that takes a Query
                add (entity);
            }
        }
        catch (Exception ex) {
            Logger.trace("Unable to update index for "+entity, ex);
        }

        if (DEBUG (2))
            Logger.debug("<<< "+entity);
    }

    public void remove (Object entity) throws Exception {
        Class cls = entity.getClass();
        if (cls.isAnnotationPresent(Entity.class)) {
            Field[] fields = cls.getFields();
            Object id = null;       
            for (Field f : fields) {
                if (f.getAnnotation(Id.class) != null) {
                    id = f.get(entity);
                }
            }
            if (id != null) {
                String field = entity.getClass().getName()+".id";
                if (DEBUG (2))
                    Logger.debug("Deleting document "+field+"="+id+"...");
                indexWriter.deleteDocuments
                    (new Term (field, id.toString()));
                lastModified.set(System.currentTimeMillis());           
            }
            else {
                Logger.warn("Entity "+cls+"'s Id field is null!");
            }
        }
        else {
            throw new IllegalArgumentException
                ("Object is not of type Entity");
        }
    }

    public void remove (String text) throws Exception {
        try {
            QueryParser parser = new QueryParser 
                (LUCENE_VERSION, "text", indexAnalyzer);
            Query query = parser.parse(text);
            Logger.debug("## removing documents: "+query);
            indexWriter.deleteDocuments(query);
            lastModified.set(System.currentTimeMillis());
        }
        catch (ParseException ex) {
            Logger.warn("Can't parse query expression: "+text, ex);
            throw new IllegalArgumentException
                ("Can't parse query: "+text, ex);
        }
    }

    protected void instrument (LinkedList<String> path,
                               Set indexed, Object entity, 
                               List<IndexableField> ixFields) {
        if (indexed.contains(entity))
            return;

        indexed.add(entity);
        try {
            Class cls = entity.getClass();
            ixFields.add(new FacetField (DIM_CLASS, cls.getName()));

            DynamicFacet dyna = 
                (DynamicFacet)cls.getAnnotation(DynamicFacet.class);
            String facetLabel = null;
            String facetValue = null;

            Field[] fields = cls.getFields();
            for (Field f : fields) {
                Indexable indexable = 
                    (Indexable)f.getAnnotation(Indexable.class);
                if (indexable == null) {
                    indexable = defaultIndexable;
                }

                int mods = f.getModifiers();
                if (!indexable.indexed()
                    || Modifier.isStatic(mods)
                    || Modifier.isTransient(mods)) {
                    //Logger.debug("** skipping field "+f.getName()+"["+cls.getName()+"]");
                    continue;
                }

                path.push(f.getName());
                try {
                    Class type = f.getType();
                    Object value = f.get(entity);

                    if (DEBUG (2)) {
                        Logger.debug
                            ("++ "+toPath (path)+": type="+type
                             +" value="+value);
                    }

                    if (f.getAnnotation(Id.class) != null) {
                        //Logger.debug("+ Id: "+value);
                        if (value != null) {
                            // the hidden _id field stores the field's value
                            // in its native type whereas the display field id
                            // is used for indexing purposes and as such is
                            // represented as a string
                            String kind = entity.getClass().getName();
                            if (value instanceof Long) {
                                ixFields.add(new LongField 
                                             (kind+"._id", 
                                              (Long)value, YES));
                            }
                            else {
                                ixFields.add(new StringField 
                                             (kind+"._id", 
                                              value.toString(), YES));
                            }
                            ixFields.add
                                (new StringField (kind+".id", 
                                                  value.toString(), NO));
                        }
                        else {
                            if (DEBUG (2))
                                Logger.warn("Id field "+f+" is null");
                        }
                    }
                    else if (value == null) {
                        // do nothing
                    }
                    else if (dyna != null 
                             && f.getName().equals(dyna.label())) {
                        facetLabel = value.toString();
                    }
                    else if (dyna != null
                             && f.getName().equals(dyna.value())) {
                        facetValue = value.toString();
                    }
                    else if (type.isPrimitive()) {
                        indexField (ixFields, indexable, path, value);
                    }
                    else if (type.isArray()) {
                        int len = Array.getLength(value);
                        // recursively evaluate each element in the array
                        for (int i = 0; i < len; ++i) {
                            path.push(String.valueOf(i));
                            instrument (path, indexed,
                                        Array.get(value, i), ixFields); 
                            path.pop();
                        }
                    }
                    else if (Collection.class.isAssignableFrom(type)) {
                        Iterator it = ((Collection)value).iterator();
                        for (int i = 0; it.hasNext(); ++i) {
                            path.push(String.valueOf(i));
                            instrument (path, indexed, it.next(), ixFields);
                            path.pop();
                        }
                    }
                    // why isn't this the same as using type?
                    else if (value.getClass()
                             .isAnnotationPresent(Entity.class)) {
                        // composite type; recurse
                        instrument (path, indexed, value, ixFields);
                    }
                    else { // treat as string
                        indexField (ixFields, indexable, path, value);
                    }
                }
                catch (Exception ex) {
                    if (DEBUG (3)) {
                        Logger.warn(entity.getClass()
                                    +": Field "+f+" is not indexable due to "
                                    +ex.getMessage());
                    }
                }
                path.pop();
            } // foreach field

            // dynamic facet if available
            if (facetLabel != null && facetValue != null) {
                facetsConfig.setMultiValued(facetLabel, true);
                facetsConfig.setRequireDimCount(facetLabel, true);
                ixFields.add(new FacetField (facetLabel, facetValue));
                // allow searching of this field
                ixFields.add(new TextField // for field searching
                             (escapeFieldName (facetLabel), facetValue, NO));
                // for term vector count
                ixFields.add(new TermVectorField (facetLabel, facetValue));
                // all dynamic facets are suggestable???
                suggestField (facetLabel, facetValue);
            }
            else if (Value.class.isAssignableFrom(cls)) {
                Value v = (Value)entity;
                path.push(v.label);
                indexField (ixFields, defaultIndexable, path, v.getValue());
                path.pop();
            }
            
            Method[] methods = entity.getClass().getMethods();
            for (Method m: methods) {
                Indexable indexable = 
                    (Indexable)m.getAnnotation(Indexable.class);
                if (indexable != null && indexable.indexed()) {
                    // we only index no arguments methods
                    Class[] args = m.getParameterTypes();
                    if (args.length == 0) {
                        Object value = m.invoke(entity);
                        if (value != null) {
                            String name = m.getName();
                            if (name.startsWith("get"))
                                name = name.substring(3);
                            indexField (ixFields, indexable, 
                                        Arrays.asList(name), value);
                        }
                    }
                    else {
                        Logger.warn("Indexable is annotated for non-zero "
                                    +"arguments method \""+m.getName()+"\""); 
                    }
                }
            }

            /*
            if (cls.isAssignableFrom(XRef.class)) {
                // traverse the link.. can be dangerous!
                XRef xref = (XRef)entity;
                instrument (path, indexed, xref.deRef(), ixFields);
            }
            */
        }
        catch (Exception ex) {
            Logger.trace("Fetching entity fields", ex);
        }
    }

    void suggestField (String name, String value) {
        try {
            name = name.replaceAll("[\\s/]","_");
            SuggestLookup lookup = lookups.get(name);
            if (lookup == null) {
                lookups.put(name, lookup = new SuggestLookup (name));
            }
            lookup.add(value);
        }
        catch (Exception ex) { // 
            Logger.trace("Can't create Lookup!", ex);
        }
    }
    
    void indexField (List<IndexableField> fields, 
                     Collection<String> path, Object value) {
        indexField (fields, null, path, value, NO);
    }

    void indexField (List<IndexableField> fields, Indexable indexable, 
                     Collection<String> path, Object value) {
        indexField (fields, indexable, path, value, NO);
    }

    /*
     * by convention all field names that can be used in query parser
     * are prefixed with $; e.g., $IDG_Development_Level:tchem
     */
    static String escapeFieldName (String name) {
        return "$"+name.replaceAll("[\\/\\s]+", "_")
            .replaceAll("[\\(\\):]+","");
    }
        
    void indexField (List<IndexableField> fields, Indexable indexable, 
                     Collection<String> path, Object value, 
                     org.apache.lucene.document.Field.Store store) {
        String name = path.iterator().next();
        String full = toPath (path);
        String fname =
            "".equals(indexable.name()) ? name : indexable.name();
        
        boolean asText = false;
        if (value instanceof Long) {
            //fields.add(new NumericDocValuesField (full, (Long)value));
            Long lval = (Long)value;
            fields.add(new LongField (full, lval, NO));
            //asText = indexable.facet();
            if (!asText && !name.equals(full)) 
                fields.add(new LongField (escapeFieldName (name), lval, store));
            
            if (indexable.sortable())
                sorters.put(full, SortField.Type.LONG);

            FacetField ff = getRangeFacet (fname, indexable.ranges(), lval);
            if (ff != null) {
                facetsConfig.setMultiValued(fname, true);
                facetsConfig.setRequireDimCount(fname, true);
                fields.add(ff);
            }
        }
        else if (value instanceof Integer) {
            //fields.add(new IntDocValuesField (full, (Integer)value));
            Integer ival = (Integer)value;
            fields.add(new IntField (full, ival, NO));
            //asText = indexable.facet();
            if (!asText && !name.equals(full))
                fields.add(new IntField (escapeFieldName (name), ival, store));
            if (indexable.sortable())
                sorters.put(full, SortField.Type.INT);

            FacetField ff = getRangeFacet 
                (fname, indexable.ranges(), ival);
            if (ff != null) {
                facetsConfig.setMultiValued(fname, true);
                facetsConfig.setRequireDimCount(fname, true);
                fields.add(ff);
            }
        }
        else if (value instanceof Float) {
            //fields.add(new FloatDocValuesField (full, (Float)value));
            Float fval = (Float)value;
            fields.add(new FloatField (name, fval, store));
            if (!full.equals(name))
                fields.add(new FloatField (full, fval, NO));
            if (indexable.sortable())
                sorters.put(full, SortField.Type.FLOAT);
            
            FacetField ff = getRangeFacet 
                (fname, indexable.dranges(), fval, indexable.format());
            if (ff != null) {
                facetsConfig.setMultiValued(fname, true);
                facetsConfig.setRequireDimCount(fname, true);
                fields.add(ff);
            }
        }
        else if (value instanceof Double) {
            //fields.add(new DoubleDocValuesField (full, (Double)value));
            Double dval = (Double)value;
            fields.add(new DoubleField (name, dval, store));
            if (!full.equals(name))
                fields.add(new DoubleField (full, dval, NO));
            if (indexable.sortable())
                sorters.put(full, SortField.Type.DOUBLE);

            FacetField ff = getRangeFacet 
                (fname, indexable.dranges(), dval, indexable.format());
            if (ff != null) {
                facetsConfig.setMultiValued(fname, true);
                facetsConfig.setRequireDimCount(fname, true);
                fields.add(ff);
            }
        }
        else if (value instanceof java.util.Date) {
            long date = ((Date)value).getTime();
            fields.add(new LongField (escapeFieldName (name), date, store));
            if (!full.equals(name))
                fields.add(new LongField (full, date, NO));
            if (indexable.sortable())
                sorters.put(full, SortField.Type.LONG);
            asText = indexable.facet();
            if (asText) {
                value = YEAR_DATE_FORMAT.format(date);
            }
        }
        else
            asText = true;

        if (asText) {
            String text = value.toString();
            String dim = indexable.name();
            if ("".equals(dim))
                dim = toPath (path, true);

            if (indexable.facet() || indexable.taxonomy()) {
                facetsConfig.setMultiValued(dim, true);
                facetsConfig.setRequireDimCount(dim, true);
                
                if (indexable.taxonomy()) {
                    facetsConfig.setHierarchical(dim, true);
                    fields.add
                        (new FacetField
                         (dim, text.split(indexable.pathsep())));
                }
                else {
                    fields.add(new FacetField (dim, text));
                }
                fields.add(new TermVectorField (dim, text));
                fields.add(new TextField (escapeFieldName (dim), text, store));
            }

            if (indexable.suggest()) {
                // also index the corresponding text field with the 
                //   dimension name
                //fields.add(new TextField (dim, text, NO));
                if (indexable.facet() || indexable.taxonomy()) {
                }
                else {
                    fields.add(new TermVectorField (dim, text));
                    fields.add(new TextField
                               (escapeFieldName (dim), text, store));
                }
                suggestField (dim, text);
            }

            if (!(value instanceof Number)) {
                if (!name.equals(full))
                    fields.add(new TextField (full, text, NO));
            }

            if (indexable.sortable() && !sorters.containsKey(name))
                sorters.put(name, SortField.Type.STRING);
            fields.add(new TextField (escapeFieldName (name), text, store));
        }
    }

    static FacetField getRangeFacet (String name, long[] ranges, long value) {
        if (ranges.length == 0) 
            return null;

        if (value < ranges[0]) {
            return new FacetField (name, "<"+ranges[0]);
        }

        int i = 1;
        for (; i < ranges.length; ++i) {
            if (value < ranges[i])
                break;
        }

        if (i == ranges.length) {
            return new FacetField (name, ">"+ranges[i-1]);
        }

        return new FacetField (name, ranges[i-1]+":"+ranges[i]);
    }

    static FacetField getRangeFacet
        (String name, double[] ranges, double value, String format) {
        if (ranges.length == 0) 
            return null;

        if (value < ranges[0]) {
            return new FacetField (name, "<"+String.format(format, ranges[0]));
        }

        int i = 1;
        for (; i < ranges.length; ++i) {
            if (value < ranges[i])
                break;
        }

        if (i == ranges.length) {
            return new FacetField (name, ">"+String.format(format,ranges[i-1]));
        }

        return new FacetField (name, String.format(format, ranges[i-1])
                               +":"+String.format(format, ranges[i]));
    }
    
    static void setFieldType (FieldType ftype) {
        ftype.setIndexed(true);
        ftype.setTokenized(true);
        ftype.setStoreTermVectors(true);
        ftype.setIndexOptions
            (IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    }

    static String toPath (Collection<String> path) {
        return toPath (path, /*false*/true);
    }

    static String toPath (Collection<String> path, boolean noindex) {
        StringBuilder sb = new StringBuilder ();
        List<String> rev = new ArrayList<String>(path);
        Collections.reverse(rev);

        for (Iterator<String> it = rev.iterator(); it.hasNext(); ) {
            String p = it.next();

            boolean append = true;
            if (noindex) {
                try {
                    Integer.parseInt(p);
                    append = false;
                }
                catch (NumberFormatException ex) {
                }
            }

            if (append) {
                sb.append(escapeFieldName(p).substring(1));
                if (it.hasNext())
                    sb.append('_');
            }
        }
        return sb.toString();
    }

    public JsonNode getFacetsConfig () {
        return setFacetsConfig (facetsConfig);
    }

    public JsonNode getIndexFields () throws IOException {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode json = mapper.createArrayNode();
        for (AtomicReaderContext ctx : indexReader.leaves()) {
            AtomicReader reader = ctx.reader();
            for (Iterator<String> it = reader.fields().iterator();
                 it.hasNext(); ) {
                String f = it.next();
                ObjectNode n = mapper.createObjectNode();
                n.put("field", f);
                Terms terms = reader.terms(f);
                if (terms != null) {
                    BytesRef ref = terms.getMin();
                    if (ref != null)
                        n.put("min", ref.utf8ToString());
                    ref = terms.getMax();
                    if (ref != null)
                        n.put("max", ref.utf8ToString());
                    n.put("size", terms.size());
                    n.put("count", terms.getDocCount());
                }
                json.add(n);
            }           
        }
        return json;
    }

    static FacetsConfig getFacetsConfig (JsonNode node) {
        if (!node.isContainerNode())
            throw new IllegalArgumentException
                ("Not a valid json node for FacetsConfig!");

        String text = node.get("version").asText();
        Version ver = Version.parseLeniently(text);
        if (!ver.equals(LUCENE_VERSION)) {
            Logger.warn("Facets configuration version ("+ver+") doesn't "
                        +"match index version ("+LUCENE_VERSION+")");
        }

        FacetsConfig config = null;
        ArrayNode array = (ArrayNode)node.get("dims");
        if (array != null) {
            config = new FacetsConfig ();
            for (int i = 0; i < array.size(); ++i) {
                ObjectNode n = (ObjectNode)array.get(i);
                String dim = n.get("dim").asText();
                config.setHierarchical
                    (dim, n.get("hierarchical").asBoolean());
                config.setIndexFieldName
                    (dim, n.get("indexFieldName").asText());
                config.setMultiValued(dim, n.get("multiValued").asBoolean());
                config.setRequireDimCount
                    (dim, n.get("requireDimCount").asBoolean());
            }
        }

        return config;
    }

    static JsonNode setFacetsConfig (FacetsConfig config) {
        ObjectMapper mapper = new ObjectMapper ();
        ObjectNode node = mapper.createObjectNode();
        node.put("created", new java.util.Date().getTime());
        node.put("version", LUCENE_VERSION.toString());
        node.put("warning", "AUTOMATICALLY GENERATED FILE; DO NOT EDIT");
        Map<String, FacetsConfig.DimConfig> dims = config.getDimConfigs();
        node.put("size", dims.size());
        ArrayNode array = node.putArray("dims");
        for (Map.Entry<String, FacetsConfig.DimConfig> me : dims.entrySet()) {
            FacetsConfig.DimConfig c = me.getValue();
            ObjectNode n = mapper.createObjectNode();
            n.put("dim", me.getKey());
            n.put("hierarchical", c.hierarchical);
            n.put("indexFieldName", c.indexFieldName);
            n.put("multiValued", c.multiValued);
            n.put("requireDimCount", c.requireDimCount);
            array.add(n);
        }
        return node;
    }

    static void saveFacetsConfig (File file, FacetsConfig facetsConfig) {
        JsonNode node = setFacetsConfig (facetsConfig);
        ObjectMapper mapper = new ObjectMapper ();
        try {
            FileOutputStream out = new FileOutputStream (file);
            mapper.writerWithDefaultPrettyPrinter().writeValue(out, node);
            out.close();
        }
        catch (IOException ex) {
            Logger.trace("Can't persist facets config!", ex);
        }
    }

    static FacetsConfig loadFacetsConfig (File file) {
        FacetsConfig config = null;
        if (file.exists()) {
            ObjectMapper mapper = new ObjectMapper ();
            try {
                JsonNode conf = mapper.readTree(new FileInputStream (file));
                config = getFacetsConfig (conf);
                Logger.info("## FacetsConfig loaded with "
                            +config.getDimConfigs().size()
                            +" dimensions!");
            }
            catch (Exception ex) {
                Logger.trace("Can't read file "+file, ex);
            }
        }
        return config;
    }

    static ConcurrentMap<String, SortField.Type> loadSorters (File file) {
        ConcurrentMap<String, SortField.Type> sorters = 
            new ConcurrentHashMap<String, SortField.Type>();
        if (file.exists()) {
            ObjectMapper mapper = new ObjectMapper ();
            try {
                JsonNode conf = mapper.readTree(new FileInputStream (file));
                ArrayNode array = (ArrayNode)conf.get("sorters");
                if (array != null) {
                    for (int i = 0; i < array.size(); ++i) {
                        ObjectNode node = (ObjectNode)array.get(i);
                        String field = node.get("field").asText();
                        String type = node.get("type").asText();
                        sorters.put(field, SortField.Type.valueOf
                                    (SortField.Type.class, type));
                    }
                }
            }
            catch (Exception ex) {
                Logger.trace("Can't read file "+file, ex);
            }
        }
        return sorters;
    }

    static void saveSorters (File file, Map<String, SortField.Type> sorters) {
        ObjectMapper mapper = new ObjectMapper ();

        ObjectNode conf = mapper.createObjectNode();
        conf.put("created", new java.util.Date().getTime());
        ArrayNode node = mapper.createArrayNode();
        for (Map.Entry<String, SortField.Type> me : sorters.entrySet()) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("field", me.getKey());
            obj.put("type", me.getValue().toString());
            node.add(obj);
        }
        conf.put("sorters", node);

        try {
            FileOutputStream fos = new FileOutputStream (file);
            mapper.writerWithDefaultPrettyPrinter().writeValue(fos, conf);
            fos.close();
        }
        catch (IOException ex) {
            Logger.trace("Can't persist sorter config!", ex);
        }
    }

    public void flush () {
        try {            
            if (indexWriter.hasPendingMerges()) {
                indexWriter.waitForMerges();
                indexWriter.commit();
            }

            if (!indexReader.isCurrent()) {
                lastModified.set(System.currentTimeMillis());
            }
            
            IndexOutput output = indexDir.createOutput
                ("lastModified", IOContext.DEFAULT);
            output.writeLong(lastModified.get());
            output.close();
            
            saveFacetsConfig (new File (baseDir, FACETS_CONFIG_FILE), 
                              facetsConfig);
            saveSorters (new File (baseDir, SORTER_CONFIG_FILE), sorters);
        }
        catch (Exception ex) {
            Logger.error("Can't flush text indexer", ex);
        }
    }

    public void shutdown () {
        flush ();
        
        try {
            for (int i = 0; i < fetchWorkers.length; ++i)
                fetchQueue.put(POISON_PAYLOAD);
            
            for (SuggestLookup look : lookups.values()) {
                look.close();
            }

            searcherManager.close();
            if (indexReader != null)
                indexReader.close();
            if (indexWriter != null)
                indexWriter.close();
            if (taxonWriter != null)
                taxonWriter.close();
            indexDir.close();
            taxonDir.close();
        }
        catch (Exception ex) {
            //ex.printStackTrace();
            Logger.trace("Closing index", ex);
        }
        finally {
            indexers.remove(baseDir);
            threadPool.shutdown();
            scheduleThreadPool.shutdown();
        }
    }
}
