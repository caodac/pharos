package ix.idg.controllers;

import ix.core.controllers.search.SearchFactory;
import ix.core.controllers.KeywordFactory;
import ix.core.controllers.PredicateFactory;
import ix.core.models.Keyword;
import ix.core.models.Text;
import ix.core.models.Value;
import ix.core.models.VNum;
import ix.core.models.XRef;
import ix.core.models.Predicate;
import ix.core.models.Structure;
import ix.core.models.Mesh;
import ix.core.models.EntityModel;
import ix.core.models.Publication;
import ix.core.search.TextIndexer;
import static ix.core.search.TextIndexer.*;
import ix.idg.models.Disease;
import ix.idg.models.Target;
import ix.idg.models.Ligand;
import ix.utils.Util;
import ix.core.plugins.TextIndexerPlugin;
import ix.ncats.controllers.App;

import tripod.chem.indexer.StructureIndexer;
import static tripod.chem.indexer.StructureIndexer.ResultEnumeration;

import play.Logger;
import play.cache.Cache;
import play.libs.ws.WS;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Call;
import play.db.ebean.Model;
import com.avaje.ebean.Expr;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class IDGApp extends App {
    static final int MAX_SEARCH_RESULTS = 1000;

    static final TextIndexer indexer = 
        play.Play.application().plugin(TextIndexerPlugin.class).getIndexer();


    public static class DiseaseRelevance
        implements Comparable<DiseaseRelevance> {
        public Disease disease;
        public Double zscore;
        public Double conf;
        public String comment;
        public Keyword omim;
        public Keyword uniprot;
        public List<DiseaseRelevance> lineage =
            new ArrayList<DiseaseRelevance>();

        DiseaseRelevance () {}
        public int compareTo (DiseaseRelevance dr) {
            double d = dr.zscore - zscore;
            if (d < 0) return -1;
            if (d > 0) return 1;
            return 0;
        }
    }

    public static class LigandActivity {
        public final Target target;
        public final List<VNum> activities = new ArrayList<VNum>();

        LigandActivity (XRef ref) {
            for (Value v : ref.properties) {
                if (v instanceof VNum) {
                    activities.add((VNum)v);
                }
            }
            target = (Target)ref.deRef();           
        }
    }

    static class IDGFacetDecorator extends FacetDecorator {
        IDGFacetDecorator (Facet facet) {
            super (facet, true, 6);
        }

        @Override
        public String name () {
            return super.name().replaceAll("IDG", "").trim();
        }
        
        @Override
        public String label (final int i) {
            final String label = super.label(i);
            final String name = super.name();
            if (name.equals(Target.IDG_DEVELOPMENT)) {
                Target.TDL tdl = Target.TDL.fromString(label);
                if (tdl != null) {
                    return "<span class=\"label label-"+tdl.label+"\""
                        +" data-toggle=\"tooltip\" data-placement=\"right\""
                        +" data-html=\"true\" title=\"<p align='left'>"
                        +tdl.desc+"</p>\">"+tdl.name+"</span>";
                }
                assert false: "Unknown TDL label: "+label;
            }
            else if (name.equals(ChemblRegistry.WHO_ATC)) {
                String key = ChemblRegistry.WHO_ATC+":"+label;
                try {
                    Keyword kw = getOrElse (0l, key, new Callable<Keyword>() {
                            public Keyword call () {
                                List<Keyword> kws = KeywordFactory.finder
                                .where().eq("label",name+" "+label)
                                .findList();
                                if (!kws.isEmpty()) {
                                    return kws.iterator().next();
                                }
                                return null;
                            }
                        });
                    if (kw != null)
                        return kw.term.toLowerCase()+" ("+label+")";
                }
                catch (Exception ex) {
                    Logger.error("Can't retrieve key "+key+" from cache", ex);
                    ex.printStackTrace();
                }
            }
            return label;
        }
    }

    static abstract class GetResult<T> {
        final Model.Finder<Long, T> finder;
        final Class<T> cls;
        GetResult (Class<T> cls, Model.Finder<Long, T> finder) {
            this.cls = cls;
            this.finder = finder;
        }

        public Result get (final String name) {
            try {
                long start = System.currentTimeMillis();        
                List<T> e = getOrElse
                    (cls.getName()+"/"+name, new Callable<List<T>> () {
                        public List<T> call () throws Exception {
                            List<T> values = finder.where()
                            .eq("synonyms.term", name).findList();
                            if (values.size() > 1) {
                                Logger.warn("\""+name+"\" yields "
                                            +values.size()+" matches!");
                            }
                            return values;
                        }
                    });
                double ellapsed = (System.currentTimeMillis()-start)*1e-3;
                Logger.debug("Ellapsed time "+String.format("%1$.3fs", ellapsed)
                             +" to retrieve "+e.size()+" matches for "+name);
                
                if (e.isEmpty()) {
                    return _notFound ("Unknown name: "+name);
                }
                return result (e);
            }
            catch (Exception ex) {
                Logger.error("Unable to generate Result for \""+name+"\"", ex);
                return _internalServerError (ex);
            }
        }
        
        public Result result (final List<T> e) {
            try {
                Result result = getOrElse
                    (cls.getName()+"/result/"+Util.sha1(request ()),
                     new Callable<Result> () {
                            public Result call () throws Exception {
                                return getResult (e);
                            }
                        });
                return result;
            }
            catch (Exception ex) {
                return _internalServerError (ex);
            }
        }

        abstract Result getResult (List<T> e) throws Exception;
    }
    
    public static final String[] TARGET_FACETS = {
        TcrdRegistry.DEVELOPMENT,
        TcrdRegistry.FAMILY,
        TcrdRegistry.DISEASE,
        "Ligand"
    };

    public static final String[] DISEASE_FACETS = {
        TcrdRegistry.DEVELOPMENT,
        TcrdRegistry.FAMILY,
        UniprotRegistry.TARGET
    };

    public static final String[] LIGAND_FACETS = {
        ChemblRegistry.WHO_ATC,
        TcrdRegistry.DEVELOPMENT,
        TcrdRegistry.FAMILY,
        UniprotRegistry.TARGET
    };

    public static final String[] ALL_FACETS = {
        TcrdRegistry.DEVELOPMENT,
        TcrdRegistry.FAMILY,
        TcrdRegistry.DISEASE,
        UniprotRegistry.TARGET,
        "Ligand"
    };

    static FacetDecorator[] decorate (Facet... facets) {
        List<FacetDecorator> decors = new ArrayList<FacetDecorator>();
        // override decorator as needed here
        for (int i = 0; i < facets.length; ++i) {
            decors.add(new IDGFacetDecorator (facets[i]));
        }
        // now add hidden facet so as to not have them shown in the alert
        // box
        for (int i = 1; i <= 8; ++i) {
            IDGFacetDecorator f = new IDGFacetDecorator
                (new TextIndexer.Facet
                 (ChemblRegistry.ChEMBL_PROTEIN_CLASS+" ("+i+")"));
            f.hidden = true;
            decors.add(f);
        }
        
        return decors.toArray(new FacetDecorator[0]);
    }
    
    public static Result about() {
        TextIndexer.Facet[] target = getFacets (Target.class, "Namespace");
        TextIndexer.Facet[] disease = getFacets (Disease.class, "Namespace");
        TextIndexer.Facet[] ligand = getFacets (Ligand.class, "Namespace");
        return ok (ix.idg.views.html.about.render
                   ("Pharos: Illuminating the Druggable Genome",
                    target.length > 0 ? target[0] : null,
                    disease.length > 0 ? disease[0] : null,
                    ligand.length > 0 ? ligand[0]: null));
    }

    public static Result index () {
        return ok (ix.idg.views.html.index2.render
                   ("Pharos: Illuminating the Druggable Genome",
                    DiseaseFactory.finder.findRowCount(),
                    TargetFactory.finder.findRowCount(),
                    LigandFactory.finder.findRowCount()));
    }

    public static Result error (int code, String mesg) {
        return ok (ix.idg.views.html.error.render(code, mesg));
    }

    public static Result _notFound (String mesg) {
        return notFound (ix.idg.views.html.error.render(404, mesg));
    }

    public static Result _badRequest (String mesg) {
        return badRequest (ix.idg.views.html.error.render(400, mesg));
    }

    public static Result _internalServerError (Throwable t) {
        t.printStackTrace();
        return internalServerError
            (ix.idg.views.html.error.render
             (500, "Internal server error: "+t.getMessage()));
    }

    static void getLineage (Map<Long, Disease> lineage, Disease d) {
        if (!lineage.containsKey(d.id)) {
            for (XRef ref : d.links) {
                if (Disease.class.getName().equals(ref.kind)) {
                    for (Value prop : ref.properties) {
                        if (prop.label.equals(DiseaseOntologyRegistry.IS_A)) {
                            Disease p = (Disease)ref.deRef();
                            lineage.put(d.id, p);
                            getLineage (lineage, p);
                            return;
                        }
                    }
                }
            }
        }
    }

    public static String novelty (Value value) {
        if (value != null) {
            VNum val = (VNum)value;
            if (val.numval < 0.)
                return String.format("%1$.5f", val.numval);
            if (val.numval < 10.)
                return String.format("%1$.3f", val.numval);
            return String.format("%1$.1f", val.numval);
        }
        return "";
    }
    
    public static String getId (Target t) {
        Keyword kw = t.getSynonym(UniprotRegistry.ACCESSION);
        return kw != null ? kw.term : null;
    }
    
    static final GetResult<Target> TargetResult =
        new GetResult<Target>(Target.class, TargetFactory.finder) {
            public Result getResult (List<Target> targets) throws Exception {
                return _getTargetResult (targets);
            }
        };
    
    public static Result target (final String name) {
        return TargetResult.get(name);
    }

    static Result _getTargetResult (final List<Target> targets)
        throws Exception {
        final Target t = targets.iterator().next(); // guarantee not empty
        List<DiseaseRelevance> diseases = getOrElse
            ("targets/"+t.id+"/diseases",
             new Callable<List<DiseaseRelevance>> () {
                 public List<DiseaseRelevance> call () throws Exception {
                     return getDiseaseRelevances (t);
                 }
             });
        
        List<Keyword> breadcrumb = new ArrayList<Keyword>();
        for (Value v : t.properties) {
            if (v.label.startsWith(ChemblRegistry.ChEMBL_PROTEIN_CLASS)) {
                Keyword kw = (Keyword)v;
                String url = ix.idg.controllers
                    .routes.IDGApp.targets(null, 30, 1).url();
                kw.href = url + (url.indexOf('?') > 0 ? "&":"?")
                    +"facet="+kw.label+"/"+kw.term;
                breadcrumb.add(kw);
            }
        }
        // just make sure the order is correct
        Collections.sort(breadcrumb, new Comparator<Keyword>() {
                public int compare (Keyword kw1, Keyword kw2) {
                    return kw1.label.compareTo(kw2.label);
                }
            });

        return ok (ix.idg.views.html
                   .targetdetails.render(t, diseases, breadcrumb));
    }

    static List<DiseaseRelevance>
        getDiseaseRelevances (Target t) throws Exception {
        List<DiseaseRelevance> diseases = new ArrayList<DiseaseRelevance>();
        List<DiseaseRelevance> uniprot = new ArrayList<DiseaseRelevance>();
        Map<Long, Disease> lineage = new HashMap<Long, Disease>();
        Map<Long, DiseaseRelevance> diseaseRel =
            new HashMap<Long, DiseaseRelevance>();
        long start = System.currentTimeMillis();
        for (XRef xref : t.links) {
            if (Disease.class.getName().equals(xref.kind)) {
                DiseaseRelevance dr = new DiseaseRelevance ();
                dr.disease = (Disease)xref.deRef();
                diseaseRel.put(dr.disease.id, dr);
                long s = System.currentTimeMillis();
                getLineage (lineage, dr.disease);
                Logger.debug("Retrieve lineage for disease "+dr.disease.id+"..."
                             +String.format("%1$dms", (System.currentTimeMillis()-s)));
                
                /*
                { Disease d = dr.disease;
                    for (Disease parent : getLineage (d)) {
                        lineage.put(d.id, parent);
                        d = parent;
                    }
                }
                */
                for (Value p : xref.properties) {
                    if (TcrdRegistry.ZSCORE.equals(p.label))
                        dr.zscore = (Double)p.getValue();
                    else if (TcrdRegistry.CONF.equals(p.label))
                        dr.conf = (Double)p.getValue();
                    else if (UniprotRegistry
                             .DISEASE_RELEVANCE.equals(p.label)
                             || p.label.equals(dr.disease.name)) {
                        dr.comment = ((Text)p).text;
                    }
                }
                if (dr.zscore != null || dr.conf != null)
                    diseases.add(dr);
                else if (dr.comment != null) {
                    for (Keyword kw : dr.disease.synonyms) {
                        if ("MIM".equals(kw.label)) {
                            dr.omim = kw;
                        }
                        else if ("UniProt".equals(kw.label))
                            dr.uniprot = kw;
                    }
                    uniprot.add(dr);
                }
            }
        }
        Collections.sort(diseases);
        double ellapsed = (System.currentTimeMillis()-start)*1e-3;
        Logger.debug("Ellapsed time "+String.format("%1$.3fs", ellapsed)
                     +" to retrieve disease relevance for target "+t.id);

        List<DiseaseRelevance> prune = new ArrayList<DiseaseRelevance>();       
        Set<Long> hasChildren = new HashSet<Long>();
        for (Disease d : lineage.values())
            hasChildren.add(d.id);

        for (DiseaseRelevance dr : diseases) {
            if (!hasChildren.contains(dr.disease.id)) {
                prune.add(dr);
                for (Disease p = lineage.get(dr.disease.id); p != null; ) {
                    DiseaseRelevance parent = diseaseRel.get(p.id);
                    if (parent == null) {
                        parent = new DiseaseRelevance ();
                        parent.disease = p;
                    }
                    dr.lineage.add(parent);
                    p = lineage.get(p.id);
                }
                Logger.debug("Disease "+dr.disease.id+" ["+dr.disease.name
                             +"] has "+dr.lineage.size()+" lineage!");
            }
        }
        prune.addAll(uniprot); // append uniprot diseases

        return prune;
    }

    public static Result targets (final String q,
                                  final int rows, final int page) {
        try {
            String sha1 = Util.sha1(request ());
            return getOrElse ("targets/"+sha1, new Callable<Result>() {
                    public Result call () throws Exception {
                        return _targets (q, rows, page);
                    }
                });
        }
        catch (Exception ex) {
            return _internalServerError (ex);
        }
    }
    
    static Result _targets (final String q, int rows, final int page)
        throws Exception {
        Logger.debug("Targets: q="+q+" rows="+rows+" page="+page);
        final int total = TargetFactory.finder.findRowCount();
        if (request().queryString().containsKey("facet") || q != null) {
            TextIndexer.SearchResult result =
                getSearchResult (Target.class, q, total);
            
            TextIndexer.Facet[] facets = filter
                (result.getFacets(), TARGET_FACETS);
            List<Target> targets = new ArrayList<Target>();
            int[] pages = new int[0];
            if (result.count() > 0) {
                rows = Math.min(result.count(), Math.max(1, rows));
                pages = paging (rows, page, result.count());
                
                for (int i = (page-1)*rows, j = 0; j < rows
                         && i < result.count(); ++j, ++i) {
                    targets.add((Target)result.getMatches().get(i));
                }
            }
            
            return ok (ix.idg.views.html.targets.render
                       (page, rows, result.count(),
                        pages, decorate (facets), targets));
        }
        else {
            TextIndexer.Facet[] facets =
                getFacets (Target.class, TARGET_FACETS);
            rows = Math.min(total, Math.max(1, rows));
            int[] pages = paging (rows, page, total);               
            
            List<Target> targets =
                TargetFactory.getTargets(rows, (page-1)*rows, null);
            
            return ok (ix.idg.views.html.targets.render
                       (page, rows, total, pages, decorate (facets), targets));
        }
    }

    public static Keyword[] getAncestry (final String facet,
                                         final String predicate) {
        try {
            return getOrElse
                (predicate+"/"+facet, new Callable<Keyword[]> () {
                        public Keyword[] call () throws Exception {
                            return _getAncestry (facet, predicate);
                        }
                    });
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        return new Keyword[0];
    }
    
    public static Keyword[] _getAncestry (String facet, String predicate) {
        List<Keyword> ancestry = new ArrayList<Keyword>();
        String[] toks = facet.split("/");
        if (toks.length == 2) {
            List<Keyword> terms = KeywordFactory.finder.where
                (Expr.and(Expr.eq("label", toks[0]),
                          Expr.eq("term", toks[1]))).
                findList();
            if (!terms.isEmpty()) {
                Keyword anchor = terms.iterator().next();
                List<Predicate> pred = PredicateFactory.finder
                    .where().conjunction()
                    .add(Expr.eq("subject.refid", anchor.id))
                    .add(Expr.eq("subject.kind", anchor.getClass().getName()))
                    .add(Expr.eq("predicate", predicate))
                    .findList();
                if (!pred.isEmpty()) {
                    for (XRef ref : pred.iterator().next().objects) {
                        if (ref.kind.equals(anchor.getClass().getName())) {
                            Keyword kw = (Keyword)ref.deRef();
                            String url = ix.idg.controllers
                                .routes.IDGApp.targets(null, 30, 1).url();
                            kw.href = url + (url.indexOf('?') > 0 ? "&":"?")
                                +"facet="+kw.label+"/"+kw.term;
                            ancestry.add(kw);
                        }
                    }
                    /* This sort is necessary to ensure the correct
                     * ordering of the nodes. This only works because
                     * the node labels have proper encoding for the level
                     * embedded in the label.
                     */
                    Collections.sort(ancestry, new Comparator<Keyword>() {
                            public int compare (Keyword kw1, Keyword kw2) {
                                return kw1.label.compareTo(kw2.label);
                            }
                        });
                }
                //ancestry.add(anchor);
            }
            else {
                Logger.warn("Uknown Keyword: label=\""+toks[0]+"\" term=\""
                            +toks[1]+"\"");
            }
        }
        return ancestry.toArray(new Keyword[0]);
    }


    public static Keyword[] getProteinAncestry (String facet) {
        return getAncestry (facet, ChemblRegistry.ChEMBL_PROTEIN_ANCESTRY);
    }

    public static Result search (String kind) {
        try {
            String q = request().getQueryString("q");
            String t = request().getQueryString("type");
            if (kind != null && !"".equals(kind)) {
                if (Target.class.getName().equals(kind))
                    return redirect (routes.IDGApp.targets(q, 30, 1));
                else if (Disease.class.getName().equals(kind))
                    return redirect (routes.IDGApp.diseases(q, 10, 1));
                else if (Ligand.class.getName().equals(kind))
                    return redirect (routes.IDGApp.ligands(q, 8, 1));
            }
            else if ("substructure".equalsIgnoreCase(t)) {
                String url = routes.IDGApp.ligands(q, 8, 1).url()
                    +"&type="+t;
                return redirect (url);
            }
            else if ("similarity".equalsIgnoreCase(t)) {
                String cutoff = request().getQueryString("cutoff");
                if (cutoff == null) {
                    cutoff = "0.8";
                }
                String url = routes.IDGApp.ligands(q, 8, 1).url()
                    +"&type="+t+"&cutoff="+cutoff;
                return redirect (url);
            }
            
            // generic entity search..
            return search (5);
        }
        catch (Exception ex) {
            Logger.debug("Can't resolve class: "+kind, ex);
        }
            
        return _badRequest ("Invalid request: "+request().uri());
    }

    static <T> List<T> filter (Class<T> cls, List values, int max) {
        List<T> fv = new ArrayList<T>();
        for (Object v : values) {
            if (cls.isAssignableFrom(v.getClass()) && fv.size() < max) {
                fv.add((T)v);
            }
        }
        return fv;
    }

    public static Result search (final int rows) {
        try {
            String sha1 = Util.sha1(request ());
            return getOrElse("search/"+sha1, new Callable<Result> () {
                    public Result call () throws Exception {
                        return _search (rows);
                    }
                });
        }
        catch (Exception ex) {
            return _internalServerError (ex);
        }
    }

    static Result _search (int rows) throws Exception {
        final String query = request().getQueryString("q");
        Logger.debug("Query: \""+query+"\"");

        TextIndexer.SearchResult result = null;            
        if (query.indexOf('/') > 0) { // use mesh facet
            final Map<String, String[]> queryString =
                new HashMap<String, String[]>();
            queryString.putAll(request().queryString());
            
            // append this facet to the list 
            List<String> f = new ArrayList<String>();
            f.add("MeSH/"+query);
            String[] ff = queryString.get("facet");
            if (ff != null) {
                for (String fv : ff)
                    f.add(fv);
            }
            queryString.put("facet", f.toArray(new String[0]));
            long start = System.currentTimeMillis();
            result = getOrElse
                ("search/facet/"+Util.sha1(queryString.get("facet")),
                 new Callable<TextIndexer.SearchResult>() {
                     public TextIndexer.SearchResult
                         call ()  throws Exception {
                         return SearchFactory.search
                         (null, null, MAX_SEARCH_RESULTS,
                          0, FACET_DIM, queryString);
                     }
                 });
            double ellapsed = (System.currentTimeMillis()-start)*1e-3;
            Logger.debug
                ("1. Ellapsed time "+String.format("%1$.3fs", ellapsed));
        }

        if (result == null || result.count() == 0) {
            long start = System.currentTimeMillis();                
            result = getOrElse
                ("search/facet/q/"+Util.sha1(request(), "facet", "q"),
                 new Callable<TextIndexer.SearchResult>() {
                     public TextIndexer.SearchResult
                                call () throws Exception {
                         return SearchFactory.search
                         (null, quote (query), MAX_SEARCH_RESULTS, 0,
                          FACET_DIM, request().queryString());
                     }
                 });
            double ellapsed = (System.currentTimeMillis()-start)*1e-3;
            Logger.debug
                ("2. Ellapsed time "+String.format("%1$.3fs", ellapsed));
        }
        
        TextIndexer.Facet[] facets = filter
            (result.getFacets(), ALL_FACETS);
        
        int max = Math.min(rows, Math.max(1,result.count()));
        int total = 0, totalTargets = 0, totalDiseases = 0, totalLigands = 0;
        for (TextIndexer.Facet f : result.getFacets()) {
            if (f.getName().equals("ix.Class")) {
                for (TextIndexer.FV fv : f.getValues()) {
                    if (Target.class.getName().equals(fv.getLabel())) {
                        totalTargets = fv.getCount();
                        total += totalTargets;
                    }
                    else if (Disease.class.getName()
                             .equals(fv.getLabel())) {
                        totalDiseases = fv.getCount();
                        total += totalDiseases;
                    }
                    else if (Ligand.class.getName().equals(fv.getLabel())) {
                        totalLigands = fv.getCount();
                        total += totalLigands;
                    }
                }
            }
        }
        
        List<Target> targets =
            filter (Target.class, result.getMatches(), max);
        List<Disease> diseases =
            filter (Disease.class, result.getMatches(), max);
        List<Ligand> ligands = filter (Ligand.class, result.getMatches(), max);
        
        return ok (ix.idg.views.html.search.render
                   (query, total, decorate (facets),
                    targets, totalTargets,
                    ligands, totalLigands,
                    diseases, totalDiseases));
    }

    public static Keyword getATC (final Keyword kw) throws Exception {
        final String key = kw.label+" "+kw.term;
        return getOrElse (0l, key, new Callable<Keyword>() {
                public Keyword call () {
                    List<Keyword> kws = KeywordFactory.finder.where()
                        .eq("label", key).findList();
                    if (!kws.isEmpty()) {
                        Keyword n = kws.iterator().next();
                        String url = ix.idg.controllers
                            .routes.IDGApp.ligands(null, 8, 1).url();
                        n.term = n.term.toLowerCase();
                        n.href = url + (url.indexOf('?') > 0?"&":"?")
                            +"facet="+kw.label+"/"+kw.term;
                        return n;
                    }
                    return null;
                }
            });
    }
    
    public static Result ligands (final String q,
                                  final int rows, final int page) {
        String sha1 = Util.sha1(request ());
        long start = System.currentTimeMillis();
        try {
            return getOrElse ("ligands/"+sha1, new Callable<Result>() {
                    public Result call () throws Exception {
                        return _ligands (q, rows, page);
                    }
                });
        }
        catch (Exception ex) {
            return _internalServerError (ex);
        }
        finally {
            Logger.debug("ligands: q="+q+" rows="+rows+" page="+page
                         +"..."+String.format
                         ("%1$dms", System.currentTimeMillis()-start));
        }
    }
    
    static Result _ligands (final String q, int rows, final int page)
        throws Exception {
        String type = request().getQueryString("type");
        
        Logger.debug("ligands: q="+q+" type="+type+" rows="+rows+" page="+page);
        if (type != null && (type.equalsIgnoreCase("substructure")
                             || type.equalsIgnoreCase("similarity"))) {
            // structure search
            String cutoff = request().getQueryString("cutoff");
            Logger.debug("Search: q="+q+" type="+type+" cutoff="+cutoff);
            try {
                if (type.equalsIgnoreCase("substructure")) {
                    return substructure (q, rows, page);
                }
                else {
                    return similarity
                        (q, Double.parseDouble(cutoff), rows, page);
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
            
            return notFound (ix.idg.views.html.error.render
                             (400, "Invalid search parameters: type=\""+type
                              +"\"; q=\""+q+"\" cutoff=\""+cutoff+"\"!"));
        }

        final int total = LigandFactory.finder.findRowCount();
        if (request().queryString().containsKey("facet") || q != null) {
            TextIndexer.SearchResult result =
                getSearchResult (Ligand.class, q, total);
            
            TextIndexer.Facet[] facets = filter
                (result.getFacets(), LIGAND_FACETS);
            List<Ligand> ligands = new ArrayList<Ligand>();
            int[] pages = new int[0];
            if (result.count() > 0) {
                rows = Math.min(result.count(), Math.max(1, rows));
                pages = paging (rows, page, result.count());
                
                for (int i = (page-1)*rows, j = 0; j < rows
                         && i < result.count(); ++j, ++i) {
                    ligands.add((Ligand)result.getMatches().get(i));
                }
            }
            
            return ok (ix.idg.views.html.ligands.render
                       (page, rows, result.count(),
                        pages, decorate (facets), ligands));
        }
        else {
            String cache = Ligand.class.getName()+".facets";
            TextIndexer.Facet[] facets = getOrElse
                (cache, new Callable<TextIndexer.Facet[]>() {
                        public TextIndexer.Facet[] call () {
                            return filter (getFacets (Ligand.class, FACET_DIM),
                                           LIGAND_FACETS);
                        }
                    });
            
            rows = Math.min(total, Math.max(1, rows));
            int[] pages = paging (rows, page, total);               
            
            List<Ligand> ligands =
                LigandFactory.getLigands(rows, (page-1)*rows, null);
            
            return ok (ix.idg.views.html.ligands.render
                       (page, rows, total, pages, decorate (facets), ligands));
        }
    }

    static final GetResult<Ligand> LigandResult =
        new GetResult<Ligand>(Ligand.class, LigandFactory.finder) {
            public Result getResult (List<Ligand> ligands) throws Exception {
                return _getLigandResult (ligands);
            }
        };

    static Result _getLigandResult (List<Ligand> ligands) throws Exception {
        // force it to show only one since it's possible that the provided
        // name isn't unique
        if (true || ligands.size() == 1) {
            Ligand ligand = ligands.iterator().next();
            
            List<Keyword> breadcrumb = new ArrayList<Keyword>();
            for (Keyword kw : ligand.synonyms) {
                if (kw.label.equals(ChemblRegistry.WHO_ATC)
                    // don't include the leaf node
                    && kw.term.length() < 7) {
                    breadcrumb.add(kw);
                }
            }
            
            if (!breadcrumb.isEmpty()) {
                Collections.sort(breadcrumb, new Comparator<Keyword>() {
                        public int compare (Keyword kw1, Keyword kw2) {
                            return kw1.term.compareTo(kw2.term);
                        }
                    });
                for (Keyword kw : breadcrumb) {
                    try {
                        Keyword atc = getATC (kw);
                        if (atc != null) {
                            kw.term = atc.term;
                            kw.href = atc.href;
                        }
                    }
                    catch (Exception ex) {
                        Logger.error("Can't retreive ATC "+kw.term, ex);
                        ex.printStackTrace();
                    }
                }
            }
            
            List<LigandActivity> acts = new ArrayList<LigandActivity>();
            for (XRef ref : ligand.getLinks()) {
                if (ref.kind.equals(Target.class.getName())) {
                    acts.add(new LigandActivity (ref));
                }
            }
            
            return ok (ix.idg.views.html
                       .liganddetails.render(ligand, acts, breadcrumb));
        }
        else {
            TextIndexer indexer = textIndexer.createEmptyInstance();
            for (Ligand lig : ligands)
                indexer.add(lig);
            
            TextIndexer.SearchResult result = SearchFactory.search
                (indexer, Ligand.class, null, indexer.size(), 0, FACET_DIM,
                 request().queryString());
            if (result.count() < ligands.size()) {
                ligands.clear();
                for (int i = 0; i < result.count(); ++i) {
                    ligands.add((Ligand)result.getMatches().get(i));
                }
            }
            TextIndexer.Facet[] facets = filter
                (result.getFacets(), LIGAND_FACETS);
        
            return ok (ix.idg.views.html.ligands.render
                       (1, result.count(), result.count(),
                        new int[0], decorate (facets), ligands));
        }
    }
    
    public static Result ligand (String name) {
        return LigandResult.get(name);
    }

    /**
     * return the canonical/default ligand id
     */
    public static String getId (Ligand ligand) {
        return ligand.getName();
    }
    public static Structure getStructure (Ligand ligand) {
        for (XRef xref : ligand.getLinks()) {
            if (xref.kind.equals(Structure.class.getName())) {
                return (Structure)xref.deRef();
            }
        }
        return null;
    }
    
    public static Set<Target.TDL> getTDL (EntityModel model) {
        Set<Target.TDL> tdls = EnumSet.noneOf(Target.TDL.class);
        for (XRef ref : model.getLinks()) {
            if (ref.kind.equals(Target.class.getName())) {
                for (Value v : ref.properties) {
                    if (v.label.equals(Target.IDG_DEVELOPMENT)) {
                        tdls.add(Target.TDL.fromString(((Keyword)v).term));
                    }
                }
            }
        }
        return tdls;
    }

    public static List<Mesh> getMajorTopics (EntityModel model) {
        List<Mesh> topics = new ArrayList<Mesh>();
        for (Publication pub : model.getPublications()) {
            for (Mesh m : pub.mesh) {
                if (m.majorTopic)
                    topics.add(m);
            }
        }
        return topics;
    }

    public static Result structureResult
        (TextIndexer indexer, int rows, int page) throws Exception {
        TextIndexer.SearchResult result = SearchFactory.search
            (indexer, Ligand.class, null, indexer.size(), 0, FACET_DIM,
             request().queryString());

        TextIndexer.Facet[] facets = filter (result.getFacets(), LIGAND_FACETS);
        List<Ligand> ligands = new ArrayList<Ligand>();
        int[] pages = new int[0];
        if (result.count() > 0) {
            rows = Math.min(result.count(), Math.max(1, rows));
            pages = paging (rows, page, result.count());
            
            for (int i = (page-1)*rows, j = 0; j < rows
                     && i < result.count(); ++j, ++i) {
                ligands.add((Ligand)result.getMatches().get(i));
            }
        }
        
        return ok (ix.idg.views.html.ligands.render
                   (page, rows, result.count(),
                    pages, decorate (facets), ligands));
    }

    static TextIndexer createIndexer (ResultEnumeration results)
        throws Exception {
        long start = System.currentTimeMillis();        
        TextIndexer indexer = textIndexer.createEmptyInstance();
        int count = 0;
        Set<Long> unique = new HashSet<Long>();
        while (results.hasMoreElements()) {
            StructureIndexer.Result r = results.nextElement();

            Logger.debug(r.getId()+" "+r.getSource()+" "
                         +r.getMol().toFormat("smiles"));

            List<Ligand> ligands = LigandFactory.finder
                .where(Expr.and(Expr.eq("links.kind",
                                        Structure.class.getName()),
                                Expr.eq("links.refid", r.getId())))
                .findList();
            for (Ligand ligand : ligands) {
                if (!unique.contains(ligand.id)) {
                    indexer.add(ligand);
                    unique.add(ligand.id);
                }
            }
            ++count;
        }
        
        double ellapsed = (System.currentTimeMillis() - start)*1e-3;
        Logger.debug(String.format("Ellapsed %1$.3fs to retrieve "
                                   +"%2$d structures...",
                                   ellapsed, count));
        return indexer;
    }

    public static Result similarity (final String query,
                                     final double threshold,
                                     int rows, int page) {
        try {
            String key = "similarity/"+Util.sha1(query)
                +"/"+String.format("%1$d", (int)(1000*threshold+.5));
            TextIndexer indexer = getOrElse
                (strucIndexer.lastModified(),
                 key, new Callable<TextIndexer> () {
                         public TextIndexer call () throws Exception {
                            ResultEnumeration results =
                                 strucIndexer.similarity(query, threshold, 0);
                            return createIndexer (results);
                         }
                     });
            
            return structureResult (indexer, rows, page);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't execute similarity search", ex);
        }
        
        return internalServerError
            (ix.idg.views.html.error.render
             (500, "Unable to perform similarity search: "+query));
    }
    
    public static Result substructure
        (final String query, int rows, int page) {
        Logger.debug("substructure: query="+query+" rows="+rows+" page="+page);
        try {
            String key = "substructure/"+Util.sha1(query);
            TextIndexer indexer = getOrElse
                (strucIndexer.lastModified(),
                 key, new Callable<TextIndexer> () {
                         public TextIndexer call () throws Exception {
                            ResultEnumeration results =
                                 strucIndexer.substructure(query, 0);
                            return createIndexer (results);
                         }
                     });
            
            return structureResult (indexer, rows, page);
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        
        return internalServerError
            (ix.idg.views.html.error.render
             (500, "Unable to perform substructure search: "+query));
    }
    
    public static String getId (Disease d) {
        Keyword kw = d.getSynonym(DiseaseOntologyRegistry.DOID, "UniProt");
        return kw != null ? kw.term : null;
    }
    
    static final GetResult<Disease> DiseaseResult =
        new GetResult<Disease>(Disease.class, DiseaseFactory.finder) {
            public Result getResult (List<Disease> diseases) throws Exception {
                return _getDiseaseResult (diseases);
            }
        };

    public static Result disease (final String name) {
        return DiseaseResult.get(name);
    }

    static Result _getDiseaseResult (final List<Disease> diseases)
        throws Exception {
        final Disease d = diseases.iterator().next();
        // resolve the targets for this disease
        List<Target> targets = getOrElse
            ("diseases/"+d.id+"/targets", new Callable<List<Target>> () {
                    public List<Target> call () throws Exception {
                        List<Target> targets = new ArrayList<Target>();
                        for (XRef ref : d.links) {
                            if (Target.class.isAssignableFrom
                                (Class.forName(ref.kind))) {
                                Target t = (Target) ref.deRef();
                                targets.add(t);
                            }
                        }
                        return targets;
                    }
                });

        
        return ok(ix.idg.views.html.diseasedetails.render
                  (d, targets.toArray(new Target[0]), getBreadcrumb (d)));
    }

    public static List<Keyword> getBreadcrumb (Disease d) {
        List<Keyword> breadcrumb = new ArrayList<Keyword>();
        for (Value prop : d.properties) {
            if (DiseaseOntologyRegistry.PATH.equals(prop.label)) {
                String[] path = ((Text)prop).text.split("/");
                for (String p : path) {
                    if (p.length() > 0) {
                        Keyword node = new Keyword (prop.label, p);
                        String url = ix.idg.controllers
                            .routes.IDGApp.diseases(null, 10, 1).url();
                        node.href = url + (url.indexOf('?') > 0 ? "&":"?")
                            +"facet="+DiseaseOntologyRegistry.CLASS+"/"+p;
                        breadcrumb.add(node);
                    }
                }
            }
        }
        return breadcrumb;
    }

    public static List<Keyword> getDiseaseAncestry (String name) {
        List<Disease> diseases =
            // probably should be more exact here?
            DiseaseFactory.finder.where().eq("name", name).findList();
        if (!diseases.isEmpty()) {
            if (diseases.size() > 1) {
                Logger.warn("Name \""+name+"\" maps to "+diseases.size()+
                            "diseases!");
            }
            return getBreadcrumb (diseases.iterator().next());
        }
        return new ArrayList<Keyword>();
    }
    
    public static Result diseases (final String q,
                                   final int rows, final int page) {
        try {
            String sha1 = Util.sha1(request ());
            return getOrElse("diseases/"+sha1, new Callable<Result>() {
                    public Result call () throws Exception {
                        return _diseases (q, rows, page);
                    }
                });
        }
        catch (Exception ex) {
            return _internalServerError (ex);
        }
    }
    
    static Result _diseases (String q, int rows, int page) throws Exception {
        Logger.debug("Diseases: rows=" + rows + " page=" + page);
        final int total = DiseaseFactory.finder.findRowCount();
        if (request().queryString().containsKey("facet") || q != null) {
            TextIndexer.SearchResult result =
                getSearchResult (Disease.class, q, total);
            
            TextIndexer.Facet[] facets = filter
                (result.getFacets(), DISEASE_FACETS);
            
            List<Disease> diseases = new ArrayList<Disease>();
            int[] pages = new int[0];
            if (result.count() > 0) {
                rows = Math.min(result.count(), Math.max(1, rows));
                pages = paging (rows, page, result.count());
                for (int i = (page - 1) * rows, j = 0; j < rows
                         && i < result.count(); ++j, ++i) {
                    diseases.add((Disease) result.getMatches().get(i));
                }
            }
            
            return ok(ix.idg.views.html.diseases.render
                      (page, rows, result.count(),
                       pages, decorate (facets), diseases));
        }
        else {
            TextIndexer.Facet[] facets = getOrElse
                (Disease.class.getName()+".facets",
                 new Callable<TextIndexer.Facet[]>() {
                     public TextIndexer.Facet[] call() {
                         return filter(getFacets(Disease.class, 30),
                                       DISEASE_FACETS);
                     }
                 });
            rows = Math.min(total, Math.max(1, rows));
            int[] pages = paging(rows, page, total);
            
            List<Disease> diseases =
                DiseaseFactory.getDiseases(rows, (page - 1) * rows, null);
            
            return ok(ix.idg.views.html.diseases.render
                      (page, rows, total, pages, decorate (facets), diseases));
        }
    }
}
