package ix.idg.controllers;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import chemaxon.formats.MolExporter;
import chemaxon.struc.MolAtom;
import chemaxon.struc.Molecule;
import chemaxon.util.MolHandler;
import com.avaje.ebean.Expr;
import com.avaje.ebean.QueryIterator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import controllers.AssetsBuilder;
import ix.core.controllers.EntityFactory;
import ix.core.controllers.KeywordFactory;
import ix.core.controllers.PayloadFactory;
import ix.core.controllers.PredicateFactory;
import ix.core.controllers.PublicationFactory;
import ix.core.controllers.search.SearchFactory;
import ix.core.models.*;
import ix.core.plugins.IxCache;
import ix.core.plugins.ThreadPoolPlugin;
import ix.core.search.SearchOptions;
import ix.core.search.TextIndexer;
import ix.idg.models.Assay;
import ix.idg.models.Compartment;
import ix.idg.models.Disease;
import ix.idg.models.Ligand;
import ix.idg.models.Target;
import ix.ncats.controllers.App;
import ix.seqaln.SequenceIndexer;
import ix.utils.Global;
import ix.utils.Util;
import org.apache.commons.lang3.StringUtils;
import play.Logger;
import play.Play;
import play.api.mvc.Action;
import play.api.mvc.AnyContent;
import play.cache.Cached;
import play.libs.Akka;
import play.mvc.BodyParser;
import play.mvc.Call;
import play.mvc.Result;
import play.mvc.Security;
import play.twirl.api.Content;
import play.libs.ws.*;
import tripod.chem.indexer.StructureIndexer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static ix.core.search.TextIndexer.Facet;
import static ix.core.search.TextIndexer.SearchResult;
import static ix.core.search.TextIndexer.TermVectors;
import static ix.core.search.TextIndexer.MatchFragment;
import static play.mvc.Http.MultipartFormData;

public class IDGApp extends App implements Commons {

    static final int MAX_SEARCH_RESULTS = 1000;
    public static final String IDG_RESOLVER = "IDG Resolver";

    public static final ThreadPoolPlugin _threadPool =
        Play.application().plugin(ThreadPoolPlugin.class);

    private static AssetsBuilder delegate = new AssetsBuilder();
    public static Action<AnyContent> asset(String path, String file) {
        return delegate.at(path, file, false);
    }

    public interface Filter<T extends EntityModel> {
        boolean accept (T e);
    }

    static class IDGSearchResultProcessor
        extends SearchResultProcessor<StructureIndexer.Result> {
        final public Set<Long> processed = new HashSet<Long>();
        int count;

        IDGSearchResultProcessor () throws IOException {
        }

        @Override
        protected Object instrument (final StructureIndexer.Result r)
            throws Exception {
            List<Ligand> ligands = getOrElse
                (getClass().getName()+"/structure/"+r.getId(),
                 new Callable<List<Ligand>> () {
                     public List<Ligand> call () {
                         return LigandFactory.finder
                         .where(Expr.and(Expr.eq("links.refid", r.getId()),
                                         Expr.eq("links.kind",
                                                 Structure.class.getName())))
                         .findList();
                     }
                 });
                 
            if (!ligands.isEmpty()) {
                Ligand lig = ligands.iterator().next();
                //Logger.debug("matched ligand: "+ligand.id+" "+r.getId());
                if (!processed.contains(lig.id)) {
                    processed.add(lig.id);
                    int[] amap = new int[r.getMol().getAtomCount()];
                    int i = 0, nmaps = 0;
                    for (MolAtom ma : r.getMol().getAtomArray()) {
                        amap[i] = ma.getAtomMap();
                        if (amap[i] > 0)
                            ++nmaps;
                        ++i;
                    }

                    if (nmaps > 0) {
                        IxCache.set("AtomMaps/"+getContext().getId()+"/"
                                    +r.getId(), amap);
                    }
                    
                    return lig;
                }
            }
            return null;
        }
    }

    public static class IDGSequenceResultProcessor
        extends SearchResultProcessor<SequenceIndexer.Result> {
        
        IDGSequenceResultProcessor() {
        }
            
        protected Object instrument (final SequenceIndexer.Result r)
            throws Exception {
            List<Target> targets = getOrElse
                (getClass().getName()+"/sequence/"+r.id,
                 new Callable<List<Target>> () {
                     public List<Target> call () {
                         return TargetFactory.finder.where
                         (Expr.and(Expr.eq("properties.label",
                                           UNIPROT_SEQUENCE),
                                   Expr.eq("properties.id", r.id)))
                         .findList();
                     }
                 });
            
            Target target = null;
            if (!targets.isEmpty()) {
                target = targets.iterator().next();
                // cache this alignment for show later
                Logger.debug("alignment "+getContext().getId()+" => "+r.id);
                IxCache.set("Alignment/"+getContext().getId()+"/"+r.id, r);
            }
            
            return target;
        }
    }

    public static class IDGTargetFilterProcessor
        extends SearchResultProcessor<Target> implements Enumeration<Target> {
        static final Target POISON = new Target ();
        BlockingQueue<Target> queue = new LinkedBlockingQueue<Target>();
        Target next;

        IDGTargetFilterProcessor (final String filter) {
            final String key = Util.sha1(filter);
            try {
                final List<Target> targets = getOrElse
                    (key, new Callable<List<Target>> () {
                            public List<Target> call () throws Exception {
                                return TargetFactory.getTargets
                                (Integer.MAX_VALUE, 0, filter);
                            }
                        });
                if (!targets.isEmpty()) {
                    _threadPool.submit(new Runnable () {
                            public void run () {
                                Logger.debug(Thread.currentThread()+": key="+key
                                             +" "+targets.size()+" target(s)!");
                                try {                           
                                    for (Target t : targets) {
                                        queue.put(t);
                                    }
                                    queue.put(POISON);
                                }
                                catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        });
                    next ();
                }
                else {
                    next = POISON;
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
                Logger.error("Can't retrieve targets for filter '"
                             +filter+"'", ex);
            }
        }
        
        void next () {
            try {
                next = queue.take();
            }
            catch (Exception ex) {
                ex.printStackTrace();
                next = POISON; // terminate
            }
        }

        public boolean hasMoreElements () {
            return next != POISON;
        }

        public Target nextElement () {
            Target current = next;
            next ();
            return current;
        }

        protected Object instrument (Target t) throws Exception {
            return t;
        }
    }

    public static class GeneRIF implements Comparable<GeneRIF>, Serializable {
        public final Long pmid;       
        public final String text;

        GeneRIF (Long pmid, String text) {
            this.pmid = pmid;
            StringBuilder sb = new StringBuilder ();
            for (int i = 0; i < text.length(); ++i) {
                char ch = text.charAt(i);
                switch (ch) {
                case '\\': sb.append("\\\\"); break;
                default:
                    sb.append(ch);
                }
            }
            this.text = sb.toString();
        }

        public int compareTo (GeneRIF gene) {
            if (gene.pmid > pmid) return 1;
            if (gene.pmid < pmid) return -1;
            return 0;
        }
    }
    
    public static class DiseaseRelevance
        implements Comparable<DiseaseRelevance>, Serializable {
        public Disease disease;
        public Double zscore = 0.;
        public Double conf = 0.;
        public Double tinxScore;
        public String comment;
        public Keyword omim;
        public Keyword uniprot;
        public List<DiseaseRelevance> lineage =
            new ArrayList<DiseaseRelevance>();

        DiseaseRelevance () {}
        public int compareTo (DiseaseRelevance dr) {
            return disease.name.compareTo(dr.disease.name);
        }
    }

    public static class LigandActivity implements Serializable {
        public final Target target;
        public final List<VNum> activities = new ArrayList<VNum>();
        public String mechanism;

        LigandActivity (XRef ref) {
            for (Value v : ref.properties) {
                if (ChEMBL_MECHANISM.equals(v.label)) {
                    mechanism = ((Text)v).text;
                }
                else if (PHARMALOGICAL_ACTION.equals(v.label)) {
                    mechanism = ((Keyword)v).term;
                }
                else if (v instanceof VNum) {
                    activities.add((VNum)v);
                }
            }
            target = (Target)ref.deRef();
        }
    }

    public static class DataSource implements Serializable {
        final public String name;
        public Integer targets;
        public Integer diseases;
        public Integer ligands;
        public String href;

        DataSource (String name) {
            if (name.startsWith("ChEMBL"))
                href = "https://www.ebi.ac.uk/chembl/";
            else if (name.equalsIgnoreCase("iuphar"))
                href = "http://www.guidetopharmacology.org/";
            else if (name.startsWith("TCRD"))
                href = "http://habanero.health.unm.edu";
            else if (name.startsWith("DiseaseOntology"))
                href = "http://www.disease-ontology.org";
            else if (name.equalsIgnoreCase("uniprot"))
                href = "http://www.uniprot.org";
            else if (name.equalsIgnoreCase("scientific literature")
                     || name.equalsIgnoreCase("drug label")) {
                // do nothing for this
            }
            else {
                List<Keyword> sources = KeywordFactory.finder.where
                    (Expr.and(Expr.eq("label", SOURCE),
                              Expr.eq("term", name))).findList();
                if (!sources.isEmpty()) {
                    Keyword source = sources.iterator().next();
                    href = source.href;
                }
            }
            this.name = name;
        }
    }

    static class IDGFacetDecorator extends FacetDecorator {
        IDGFacetDecorator (Facet facet) {
            super (facet, true, FACET_DIM);
            if (COLLECTION.equals(facet.getName())
                || LIBRARY.equals(facet.getName()))
                raw = true;
        }

        @Override
        public String name () {
            String name = super.name();
            if (name.startsWith("Compartment")) // sign, don't replace string
                return name;
            
            return name.replaceAll("IDG", "")
                .replaceAll("Term", "Phenotype")
                .replaceAll("Consensus", "IDG").trim();
        }

        @Override
        public String label (final int i) {
            final String label = super.label(i);
            final String name = super.name();
            if (name.equals(IDG_DEVELOPMENT)) {
                Target.TDL tdl = Target.TDL.fromString(label);
                if (tdl != null) {
                    return "<span class=\"label label-"+tdl.label+"\""
                        +" data-toggle=\"tooltip\" data-placement=\"right\""
                        +" data-html=\"true\" title=\"<p align='left'>"
                        +tdl.desc+"</p>\">"+tdl.name+"</span>";
                }
                assert false: "Unknown TDL label: "+label;
            }
            else if (name.equals(IDG_DRUG) || name.equals(IDG_LIGAND)) {
                StringBuilder url = new StringBuilder
                    ("<a href='"+routes.IDGApp.ligand(label)+"'");
                try {
                    List<Ligand> ligands = LigandResult.find(label);
                    if (!ligands.isEmpty()) {
                        Structure struc = getStructure (ligands.get(0));
                        if (struc != null) {
                            url.append(" tabindex='-1'");
                            url.append(" data-toggle='popover'");
                            url.append(" data-animation='true'");
                            url.append(" data-placement='top'");
                            url.append(" data-trigger='hover'");
                            url.append(" data-html='true'");
                            url.append(" data-content=\"<img src='");
                            url.append
                                (ix.ncats.controllers.routes.App.structure
                                 (struc.getId(),"svg",200,null).toString());
                            url.append("'>\"");
                        }
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.warn("Can't retrieve structure for ligand \""
                                +label+"\"!");
                }
                url.append(">"+label+"</a>");
                
                return url.toString();
            }
            else if (name.equals(IDG_TARGET) || name.equals(UNIPROT_GENE)) {
                try {
                    return "<a class='loader' href=\""
                        +routes.IDGApp.target(label)+"\">"+label+"</a>";
                }
                catch (Exception ex) {
                    Logger.error("Can't utf encode label: "+label, ex);
                    return "<a class='loader' href='"+routes.IDGApp.target(label)+"'>"+label+"</a>";
                }
            }
            else if (name.equals(IDG_DISEASE)) {
                try {
                    return "<a class='loader' href=\""
                        +routes.IDGApp.disease(label)+"\">"
                        +label+"</a>";
                }
                catch (Exception ex) {
                    Logger.error("Can't utf encode label: "+label, ex);
                    return "<a class='loader' href='"
                        +routes.IDGApp.disease(label)+"'>"+label+"</a>";
                }
            }
            else if (name.equals(WHO_ATC)) {
                final String key = WHO_ATC+":"+label;
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
            else if (name.equals(Target.IDG_FAMILY)) {
                if (label.equalsIgnoreCase("ogpcr")) {
                    return "<a href='https://en.wikipedia.org/wiki/Olfactory_receptor'>oGPCR</a> <i class='fa fa-external-link'></i>";
                }
                
                if (label.equalsIgnoreCase("gpcr")) {
                    return "<a href=\"http://en.wikipedia.org/wiki/G_protein%E2%80%93coupled_receptor\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("kinase")) {
                    return "<a href=\"http://en.wikipedia.org/wiki/Kinase\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("ion channel")) {
                    return "<a href=\"http://en.wikipedia.org/wiki/Ion_channel\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("nuclear receptor")) {
                    return "<a href=\"http://en.wikipedia.org/wiki/Nuclear_receptor\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("Transcription Factor")) {
                    return "<a href=\"https://en.wikipedia.org/wiki/Transcription_factor\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("epigenetic")) {
                    return "<a href=\"https://en.wikipedia.org/wiki/Epigenetics\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("transporter")) {
                    return "<a href=\"https://en.wikipedia.org/wiki/Membrane_transport_protein\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("enzyme")) {
                    return "<a href=\"https://en.wikipedia.org/wiki/Enzyme\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }

                if ("unknown".equalsIgnoreCase(label)) 
                    return "Non-IDG";
            }
            else if (name.equals(TECHDEV_PI)) {
                // TODO: sigh.. these should be in a config file somewhere!
                if (label.equalsIgnoreCase("Bryan Roth")) {
                    return "<a href=\"http://targetcentral.ws/TechDev2\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("Susumu Tomita")) {
                    return "<a href=\"http://targetcentral.ws/TechDev5\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("Steve Finkbeiner")) {
                    return "<a href=\"http://targetcentral.ws/TechDev6\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("Gary Johnson")) {
                    return "<a href=\"http://targetcentral.ws/TechDev1\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("Jing-Ruey Yeh")) {
                    return "<a href=\"http://targetcentral.ws/TechDev3\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("Michael McManus")) {
                    return "<a href=\"http://targetcentral.ws/TechDev4\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
                else if (label.equalsIgnoreCase("Jun Qin")) {
                    return "<a href=\"http://targetcentral.ws/TechDev7\">"+label+"</a> <i class='fa fa-external-link'></i>";
                }
            }
            else if (name.equals(COLLECTION) || name.equals(LIBRARY)) {
                List<Keyword> keywords = KeywordFactory.finder
                    .where(Expr.and(Expr.eq("label", name),
                                    Expr.eq("term", label)))
                    .findList();
                if (keywords != null && !keywords.isEmpty()) {
                    Keyword kw = keywords.get(0);
                    if (kw.href != null) {
                        if (kw.href.startsWith("http")) {
                            return "<a href='"+kw.href+"'>"+label
                                +"</a> <i class='fa fa-external-link'></i>";
                        }
                        else {
                            return "<span "
                                +" data-toggle=\"tooltip\" data-placement=\"right\""
                                +" data-html=\"true\" title=\"<p align='left'>"
                                +kw.href+"</p>\">"+label+"</span>";
                        }
                    }
                }
            }
            
            
            if (label.length() > 30) {
                return "<span data-toggle='tooltip' data-html='false'"
                    +" title='"+label.replaceAll("'","%27")+"'>"
                    +App.truncate(label,30)+"</span>";
            }
            return label;
        }
    }

    static class LigandTargetSelectivityFacetDecorator extends FacetDecorator {
        LigandTargetSelectivityFacetDecorator () throws IOException {
            super (_textIndexer.getFacet
                   (Ligand.class, IDG_TARGET, false, 100), true, 100);
        }

        @Override
        public String name () {
            return "Selectivity";
        }

        @Override
        public String label (int i) {
            final String label = super.label(i);
            try {
                return "<a class='loader' href=\""
                    +routes.IDGApp.target(label)+"\">"+label+"</a>";
            }
            catch (Exception ex) {
                Logger.error("Can't utf encode label: "+label, ex);
                return "<a class='loader' href='"
                    +routes.IDGApp.target(label)+"'>"+label+"</a>";
            }
        }
    }

    static class TargetCacheWarmer implements Runnable {
        final AtomicLong start = new AtomicLong ();
        final AtomicLong stop = new AtomicLong ();
        
        @JsonIgnore
        final ExecutorService threadPool;
        @JsonIgnore
        final Future task;

        @JsonIgnore
        final List<String> targets;
        final Map<String, Long> complete =
            new ConcurrentHashMap<String, Long>();
        
        TargetCacheWarmer (List<String> targets) {
            this.targets = targets;
            threadPool = Executors.newSingleThreadExecutor();
            task = threadPool.submit(this);
        }

        public void run () {
            Logger.debug
                (Thread.currentThread().getName()
                 +": preparing to warm cache for "+targets.size()+" targets!");
            start.set(System.currentTimeMillis());
            for (String name : targets) {
                long s = System.currentTimeMillis();
                try {
                    List<Target> tar = TargetResult.find(name);
                    for (Target t : tar) {
                        Map<String, List<DiseaseRelevance>> dr = 
                            getDiseases (t); // cache
                        Logger.debug(name+": id="+t.id
                                     +" #syns="+t.synonyms.size()
                                     +" #pubs="+t.publications.size()
                                     +" #links="+t.links.size()
                                     +" #props="+t.properties.size()
                                     +" #diseases="+dr.size()
                                     );
                    }
                    complete.put(name, System.currentTimeMillis()-s);
                }
                catch (Exception ex) {
                    Logger.debug(name+"...failed: "+ex.getMessage());
                }
            }
            stop.set(System.currentTimeMillis());
            Logger.debug
                (Thread.currentThread().getName()
                 +": target cache warmer complete..."+new java.util.Date());
            threadPool.shutdown();
        }

        public Map<String, Long> getComplete () { return complete; }
        public boolean isDone () { return task.isDone(); }
        public int getTotal () { return targets.size(); }
        public int getCount () { return complete.size(); }
        public long getStart () { return start.get(); }
        public long getStop () { return stop.get(); }
    }

    public static final String[] TARGET_FACETS = {
            IDG_DEVELOPMENT,
            //TECHDEV_PI,
            COLLECTION,
            IMPC_TERM,      
            IDG_DISEASE,
            IDG_TISSUE,
            IDG_FAMILY      
    };

    public static final String[] ALL_TARGET_FACETS = {
        
        IDG_DEVELOPMENT,
        IDG_FAMILY,
        TECHDEV_PI,
        COLLECTION,
        IDG_DISEASE,
        IDG_LIGAND,
        IDG_DRUG,

        IDG_TISSUE,
        UNIPROT_TISSUE,
        JENSEN_KB_TISSUE,
        JENSEN_TM_TISSUE,
        GTEx_TISSUE,
        HPM_TISSUE,
        HPA_RNA_TISSUE,
        HPA_PROTEIN_TISSUE,
        HPA_PROTEIN_CELL_TYPE,
        HCA_RNA_CELL_LINE,
        CSPA_CELL_LINE,
        
        GWAS_TRAIT,
        IMPC_TERM,
        MGI_TERM,
        OMIM_TERM,

        GO_COMPONENT,
        GO_PROCESS,
        GO_FUNCTION,

        COMPARTMENT_GOTERM,
        COMPARTMENT_EVIDENCE,
        COMPARTMENT_TYPE,

        "Ortholog Species",
        "Localization Location",
        "Localization Signal",
        
        "KEGG Pathway",
        "Reactome Pathway",
        "WikiPathways Pathway",
        "Panther Pathway",
        "BioCarta Pathway",
        "NCI-Nature Pathway",
        "HumanCyc Pathway",
        "UniProt Pathway",
        "PathwayCommons: ctd Pathway",
        "PathwayCommons: humancyc Pathway",
        "PathwayCommons: inoh Pathway",
        "PathwayCommons: mirtarbase Pathway",
        "PathwayCommons: netpath Pathway",
        "PathwayCommons: panther Pathway",
        "PathwayCommons: pid Pathway",
        "PathwayCommons: recon Pathway",
        "PathwayCommons: smpdb Pathway",
        "PathwayCommons: transfac Pathway",

        DTO_PROTEIN_CLASS+" (0)",
        DTO_PROTEIN_CLASS+" (1)",
        DTO_PROTEIN_CLASS+" (2)",
        DTO_PROTEIN_CLASS+" (3)",
        DTO_PROTEIN_CLASS+" (4)",
        DTO_PROTEIN_CLASS+" (5)",

        PANTHER_PROTEIN_CLASS+" (0)",
        PANTHER_PROTEIN_CLASS+" (1)",
        PANTHER_PROTEIN_CLASS+" (2)",
        PANTHER_PROTEIN_CLASS+" (3)",
        
        "Log Novelty",
        "Log PubTator",
        //"R01 Grant Count",
        "PubMed Count",
        "Antibody Count",
        "Monoclonal Count",
        "Patent Count",
        "PPI Count",
        "Jensen Score",

        PHARMALOGICAL_ACTION,
        "IMPC Mice Produced",
        "IMPC Mice In Progress",
        "Is Transcription Factor",
        PDB_ID,
        "DrugBank",
        LIGAND_ACTIVITY_SOURCE,
        LIGAND_ACTIVITY,
        MLP_ASSAY_TYPE,
        UNIPROT_KEYWORD,
        PROTEIN_FEATURE,
        IDG_TOOLS,
        "TechDev Grant",
        //GRANT_APPLICATION,
        //GRANT_FUNDING_IC,
        //GRANT_ACTIVITY,
        SOURCE  
    };

    public static final String[] DISEASE_FACETS = {
        IDG_DEVELOPMENT,
        IDG_FAMILY,
        IDG_DRUG,
        IDG_TARGET,
        SOURCE,
        DISGENET_SOURCE
    };

    public static final String[] LIGAND_FACETS = {
        //WHO_ATC,
        //IDG_DRUG,
        LIBRARY,
        IDG_DEVELOPMENT,
        IDG_FAMILY,
        IDG_TARGET,    
        PHARMALOGICAL_ACTION,
        LIGAND_ACTIVITY,
        LIGAND_ACTIVITY_SOURCE,        
        LIGAND_SOURCE
    };

    public static final String[] ALL_FACETS = {
        IDG_DEVELOPMENT,
        IDG_FAMILY,
        IDG_DISEASE,
        UNIPROT_GENE,
        IDG_DRUG,
        IDG_TISSUE,
        SOURCE
    };

    static public FacetDecorator[] decorate (Class kind, Facet... facets) {
        List<FacetDecorator> decors = new ArrayList<FacetDecorator>();
        // override decorator as needed here
        for (int i = 0; i < facets.length; ++i) {
            decors.add(new IDGFacetDecorator (facets[i]));
        }
        // now add hidden facet so as to not have them shown in the alert
        // box
        /*
        for (int i = 1; i <= 8; ++i) {
            IDGFacetDecorator f = new IDGFacetDecorator
                (new TextIndexer.Facet
                 (ChEMBL_PROTEIN_CLASS+" ("+i+")"));
            f.hidden = true;
            decors.add(f);
        }
        */
        
        // at most the dto is only 5 deep
        for (int i = 0; i < 6; ++i) {
            IDGFacetDecorator f = new IDGFacetDecorator
                (new TextIndexer.Facet
                 (DTO_PROTEIN_CLASS+" ("+i+")"));
            f.hidden = true;
            decors.add(f);
        }

        // panther
        for (int i = 0; i < 6; ++i) {
            IDGFacetDecorator f = new IDGFacetDecorator
                (new TextIndexer.Facet
                 (PANTHER_PROTEIN_CLASS+" ("+i+")"));
            f.hidden = true;
            decors.add(f);
        }
        
        { IDGFacetDecorator f = new IDGFacetDecorator
                (new TextIndexer.Facet(DiseaseOntologyRegistry.CLASS));
            f.hidden = true;
            decors.add(f);
        }

        if (kind != null) {
            //Logger.debug("+++");
            for (FacetDecorator f : decors) {
                if (!f.hidden) {
                    TermVectors tvs = SearchFactory.getTermVectors
                        (kind, f.facet.getName());
                    
                    if (Global.DEBUG(2))
                        Logger.debug("Facet "+f.facet.getName());
                    for (int i = 0; i < f.facet.size(); ++i) {
                        TextIndexer.FV fv = f.facet.getValue(i);
                        f.total[i] = tvs.getTermCount(fv.getLabel());
                        if (Global.DEBUG(2))
                            Logger.debug("  + "+fv.getLabel()+" "
                                         +fv.getCount()+"/"+f.total[i]);
                    }
                }
            }
        }
        
        return decors.toArray(new FacetDecorator[0]);
    }

    public static FacetDecorator[] decorate (Facet... facets) {
        return decorate (null, facets);
    }

    static FacetDecorator[][] toMatrix (int columns,
                                        FacetDecorator... facets) {
        List<FacetDecorator> fa = new ArrayList<FacetDecorator>();
        for (FacetDecorator f : facets) {
            if (f.size() > 0) 
                fa.add(f);
        }
        
        int rows = (fa.size()+columns-1)/columns;
        FacetDecorator[][] m = new FacetDecorator[rows][columns];
        for (int i = 0; i < fa.size(); ++i) {
            m[i/columns][i%columns] = fa.get(i);
        }
        return m;
    }

    public static Result sitemap () {
        File file  = Play.application().getFile("conf/sitemap.txt");
        if (file.exists()) {
            return ok (file);
        }
        else {
            return ok (sitemapFromDb ()).as("text/plain");
        }
    }

    @Cached(key="_sitemap", duration = Integer.MAX_VALUE)
    public static String sitemapFromDb () {
        StringBuilder sb = new StringBuilder();
        for (Target t : TargetFactory.finder.all()) {
            sb.append(Global.getHost()
                      +routes.IDGApp.target(getId(t))).append("\n");
        }
        for (Disease d : DiseaseFactory.finder.all()) {
            if (!getId(d).equals(""))
                sb.append(Global.getHost()
                          +routes.IDGApp.disease(getId(d))).append("\n");
        }
        return sb.toString();
    }

    @Cached(key="_discussion", duration= Integer.MAX_VALUE)
    public static Result discussion () {
        return ok (ix.idg.views.html.discussion.render());
    }

    @Cached(key="_apiswagger", duration=Integer.MAX_VALUE)
    public static Result apiswagger () {
        String h = Global.getHost();
        int p = h.indexOf(':');
        String s = h.substring(0, p);
        String u = h.substring(p+3);
        String c = Play.application()
            .configuration().getString("application.context");
        if (c == null) c = "";
        return ok (ix.idg.views.txt.apiswagger.render(s,u,c))
            .as("application/json");
    }

    @Cached(key="_faq", duration = Integer.MAX_VALUE)
    public static Result faq () throws IOException {
        return ok(ix.idg.views.html.faq.render());
    }
    
    @Cached(key="_help", duration= Integer.MAX_VALUE)
    public static Result help() {
        final String key = "idg/help";
        try {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        TextIndexer.Facet[] target =
                            getFacets (Target.class, "Namespace");
                        TextIndexer.Facet[] disease =
                            getFacets (Disease.class, "Namespace");
                        TextIndexer.Facet[] ligand =
                            getFacets (Ligand.class, "Namespace");
                        return ok (ix.idg.views.html.help2.render
                                   ("Pharos: Illuminating the Druggable Genome",
                                    target.length > 0 ? target[0] : null,
                                    disease.length > 0 ? disease[0] : null,
                                    ligand.length > 0 ? ligand[0]: null));
                    }
                });
        }
        catch (Exception ex) {
            Logger.error("Can't get about page", ex);
            return error (500, "Unable to fulfil request");
        }
    }

    @Cached(key="_pmscore", duration = Integer.MAX_VALUE)
    public static Result pmscore() {
        return ok(ix.idg.views.html.pmscore.render("Jensen Pubmed Score"));
    }

    @Cached(key="_impc", duration = Integer.MAX_VALUE)
    public static Result impc() throws IOException {

        TermVectors tvs = _textIndexer.getTermVectors(Target.class, IMPC_TERM);
        Map<String, Map> terms = tvs.getTerms();
        Map<Keyword,Integer> termCounts = new HashMap<>();
        for (Map.Entry<String, Map> term : terms.entrySet()) {
            termCounts.put(getKeywordByTerm(term.getKey()),
                           (Integer)term.getValue().get("nDocs"));
        }

        List<Map> docs = tvs.getDocs();
        List<Object[]> tmp = new ArrayList<>();
        for (Map doc : docs) {
            tmp.add(new Object[]{doc.get("doc"), ((Object[])doc.get("terms")).length});
        }
        Collections.sort(tmp, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] o1, Object[] o2) {
                Integer c1 = (Integer) o1[1];
                Integer c2 = (Integer) o2[1];
                int r = -1*c1.compareTo(c2);
                if (r == 0) {
                    Long l1 = (Long) o1[0];
                    Long l2 = (Long) o2[0];
                    r = l1.compareTo(l2);
                }
                return r;
            }
        });
        Map<Target,Integer> targetCounts = new HashMap<>();
        if (tmp.size() > 0) {
            int n = 0;
            while (true) {
                Target t = TargetFactory.finder.byId((Long) tmp.get(n)[0]);
                targetCounts.put(t, (Integer) tmp.get(n)[1]);
                n++;
                if (n == tmp.size() || n == 10) break;
            }
        }

        return ok(ix.idg.views.html.impc.render("IMPC Associated Data in IDG", termCounts, targetCounts));
    }


    @Cached(key="_about", duration = Integer.MAX_VALUE)
    public static Result about() {
        final String key = "idg/about";
        try {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        TextIndexer.Facet[] target =
                            getFacets (Target.class, "Namespace");
                        TextIndexer.Facet[] disease =
                            getFacets (Disease.class, "Namespace");
                        TextIndexer.Facet[] ligand =
                            getFacets (Ligand.class, "Namespace");
                        return ok(ix.idg.views.html.about2.render
                                ("Pharos: Illuminating the Druggable Genome",
                                        target.length > 0 ? target[0] : null,
                                        disease.length > 0 ? disease[0] : null,
                                        ligand.length > 0 ? ligand[0] : null));
                    }
                });
        }
        catch (Exception ex) {
            Logger.error("Can't get about page", ex);
            return error (500, "Unable to fulfil request");
        }
    }

    @Cached(key="_api", duration = Integer.MAX_VALUE)
    public static Result api() {
        return ok(ix.idg.views.html.api.render
                  ("Pharos: Illuminating the Druggable Genome"));
    }

    @Cached(key="_index", duration = Integer.MAX_VALUE)
    public static Result index () {
        response().setHeader("X-Frame-Options", "SAMEORIGIN");
        return ok (ix.idg.views.html.index3.render
                   ("Illuminating the Druggable Genome",
                    DiseaseFactory.finder.findRowCount(),
                    TargetFactory.finder.findRowCount(),
                    LigandFactory.finder.findRowCount()));
    }

    public static Result home () {
        return redirect (routes.IDGApp.index());
    }

    public static Result targetfacets (final String ctx) {
        final String key = Util.sha1
            (request (), "ctx", "q", "facet", "filter");
        try {
            // refresh version
            CachableContent content =
                getOrElse_ (key, new Callable<CachableContent> () {
                        public CachableContent call () throws Exception {
                            return CachableContent.wrap
                            (getTargetFacetsContent (ctx));
                        }
                    });
            return content != null ? content.ok() : _internalServerError
                ("Bad stuff is going on at 9800 medical center dr; "
                 +"please forgive us!");
        }
        catch (Exception ex) {
            Logger.error("Can't generate target facets content", ex);
            return _internalServerError (ex);
        }
    }

    public static Content getTargetFacetsContent (String ctx) {
        SearchResult result = null;
        if (ctx != null) {
            result = getSearchContext (ctx);
        }

        if (result == null) {
            if (ctx != null)
                Logger.warn("Can't retrieve SearchResult for context "+ctx);
            
            result = getSearchFacets (Target.class);
        }

        FacetDecorator[] decors = decorate
            (Target.class, filter (result.getFacets(), ALL_TARGET_FACETS));
        for (FacetDecorator fd : decors)
            fd.max = Math.min(10, fd.facet.size()); // default to 10 max
        
        SearchOptions opts = result.getOptions();
        if (!opts.facets.isEmpty()) {
            for (String f : opts.facets) {
                int pos = f.indexOf('/');
                if (pos > 0) {
                    String name = f.substring(0, pos);
                    String value = f.substring(pos+1);
                    for (FacetDecorator fd : decors) {
                        if (name.equals(fd.facet.getName())) {
                            for (int i = 0; i < fd.facet.size(); ++i) {
                                if (value.equals
                                    (fd.facet.getValue(i).getLabel())) {
                                    fd.selection[i] = true;
                                    if (i >= fd.max) fd.max = i+1;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        // everything other than q
        StringBuilder uri = new StringBuilder ();
        String type = request().getQueryString("type");
        if (type != null) {
            uri.append("type="+type);
        }
        for (Map.Entry<String, String[]> me
                 : request().queryString().entrySet()) {
            if ("facet".equals(me.getKey())) {
                for (String v : me.getValue()) {
                    if (v.startsWith(DTO_PROTEIN_CLASS)
                        || v.startsWith(PANTHER_PROTEIN_CLASS)) {
                        if (uri.length() > 0)
                            uri.append("&");
                        uri.append("facet="+v);
                    }
                }
            }
        }
        
        return ix.idg.views.html.targetfacets.render
            (request().getQueryString("q"),
             uri.toString(), toMatrix (3, decors));
    }

    @Cached(key="_kinome", duration = Integer.MAX_VALUE)
    public static Result kinome () {
        return ok (ix.idg.views.html.kinome.render());
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
    
    public static Result _internalServerError (String mesg) {
        return internalServerError
            (ix.idg.views.html.error.render
             (500, "Internal server error: "+mesg));    
    }
    
    public static Result _internalServerError (Throwable t) {
        t.printStackTrace();
        return _internalServerError (t.getMessage());
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

    public static String format (Double value) {
        if (value != null) {
            if (value < 0.)
                return String.format("%1$.5f", value);
            if (value < 0.001)
                return String.format("%1$.5f", value);
            if (value < 10.)
                return String.format("%1$.2f", value);
            return String.format("%1$.1f", value);
        }
        return "";
    }

    public static String format (Integer value) {
        return value != null ? value.toString() : "";
    }
    
    public static String getId (Target t) {
        Keyword kw = t.getSynonym(UNIPROT_ACCESSION);
        return kw != null ? kw.term : null;
    }

    public static Keyword getKeywordByTerm(String t) {
        List<Keyword> kwds = KeywordFactory.finder.where().eq("term", t).findList();
        if (kwds.size() == 0) return null;
        return kwds.get(0);
    }

    /**
     * return a list of all data sources
     */
    static DataSource[] _getDataSources () throws Exception {
        SearchOptions opts = new SearchOptions (null, 1, 0, 1000);           
        TextIndexer.SearchResult results = _textIndexer.search(opts, null);
        Set<String> labels = new TreeSet<String>();
        for (TextIndexer.Facet f : results.getFacets()) {
            if (f.getName().equals(SOURCE)) {
                for (TextIndexer.FV fv : f.getValues())
                    labels.add(fv.getLabel());
            }
        }

        Class[] entities = new Class[]{
            Disease.class, Target.class, Ligand.class
        };

        List<DataSource> sources = new ArrayList<DataSource>();
        for (String la : labels ) {
            DataSource ds = new DataSource (la);
            for (Class cls : entities) {
                opts = new SearchOptions (cls, 1, 0, 100);
                results = _textIndexer.search(opts, null);
                for (TextIndexer.Facet f : results.getFacets()) {
                    if (f.getName().equals(SOURCE)) {
                        for (TextIndexer.FV fv : f.getValues())
                            if (la.equals(fv.getLabel())) {
                                if (cls == Target.class)
                                    ds.targets = fv.getCount();
                                else if (cls == Disease.class)
                                    ds.diseases = fv.getCount();
                                else
                                    ds.ligands = fv.getCount();
                            }
                    }
                }
            }
            Logger.debug("DataSource: "+la);
            Logger.debug("  + targets: "+ds.targets);
            Logger.debug("  + ligands: "+ds.ligands);
            Logger.debug("  + diseases: "+ds.diseases);
            
            sources.add(ds);
        }

        return sources.toArray(new DataSource[0]);
    }

    public static List<XRef> getSortedPublications(Target t) {
        List<XRef> pubs = new ArrayList<XRef>();
        for (XRef xref : t.getLinks()) {
            if (xref.kind.equals("ix.core.models.Publication")) {
                pubs.add( (XRef) xref);
            }
        }
        Collections.sort(pubs, new Comparator<XRef>() {
            public int compare(XRef one, XRef other) {
                Publication p1 = (Publication) one.deRef();
                Publication p2 = (Publication) other.deRef();
                return p1.year.compareTo(p2.year);
            }
        });
        return pubs;
    }

    public static DataSource[] getDataSources () {
        final String key = "IDGApp/datasources";
        try {
            return getOrElse (key, new Callable<DataSource[]> () {
                    public DataSource[] call () throws Exception {
                        return _getDataSources ();
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        return new DataSource[0];
    }
    
    static final GetResult<Target> TargetResult =
        new GetResult<Target>(Target.class, TargetFactory.finder) {
        
            public Content getContent (List<Target> targets) throws Exception {
                return getTargetContent (targets);
            }

            @Override
            protected int getLabelRank (String label) {
                if (label.equalsIgnoreCase(UNIPROT_SHORTNAME)
                    || label.equalsIgnoreCase(PDB_ID))
                    return -1;
                return 1;
            }

            @Override protected Result notFound (String mesg) {
                return _notFound (mesg);
            }
            @Override protected Result error (Exception ex) {
                return _internalServerError (ex);
            }
        };

    public static Result target (final String name) throws Exception {
        String action = request().getQueryString("action");
        if (action != null && action.toLowerCase().equals("download")) {
            List<Target> targets = TargetFactory.finder
                .where().eq("synonyms.term", name).findList();
            if (targets == null || targets.size() != 1)
                return notFound("No target for " + name);
            byte[] targetDownload =
                DownloadEntities.downloadEntities(targets);
            String suffix = DownloadEntities
                .getDownloadMimeType(Target.class).endsWith("zip")
                ? ".zip" : ".csv";
            response().setHeader("Content-Disposition",
                                 "attachment;filename=export-target-"
                                 + name + suffix);
            return ok(targetDownload)
                .as(DownloadEntities.getDownloadMimeType(Target.class));
        }
        return TargetResult.get(name);
    }

    static Map<String, List<DiseaseRelevance>> getDiseases (final Target t)
        throws Exception {
        final String key = "targets/"+t.id+"/diseases";
        return getOrElse
            (key, new Callable<Map<String, List<DiseaseRelevance>>> () {
                public Map<String, List<DiseaseRelevance>> call ()
                    throws Exception {
                    return getDiseaseRelevances2 (t);
                }
            });
    }

    public static List<XRef> getDiseasesWithDifferentialExpression
        (final Target t) throws Exception {
        final String key = "targets/" + t.id + "/dysregulated";

        return getOrElse(key, new Callable<List<XRef>>() {
            @Override
            public List<XRef> call() throws Exception {
                List<XRef> ret = new ArrayList<XRef>();
                t.getLinks().stream().filter(xref -> xref.kind.equals(Disease.class.getName())).forEach(xref -> {
                    ret.addAll(xref.properties.stream().filter(v -> v.label.equals("Data Source")
                            && v.getValue().equals("Expression Atlas")).map(v -> xref).collect(Collectors.toList()));
                });
                return ret;

            }
        });
    }


    static Content getTargetContent (final List<Target> targets)
        throws Exception {
        Target t = targets.get(0);
        Map<String, List<DiseaseRelevance>> diseases = getDiseases (t);
        List<Keyword> breadcrumb = getBreadcrumb (t);
        return ix.idg.views.html
            .targetdetails.render(t, diseases, breadcrumb);
    }

    public static Result targetWarmCache (String secret) {
        if (secret == null || secret.length() == 0
            || !secret.equals(Play.application()
                              .configuration().getString("ix.idg.secret"))) {
            return unauthorized
                ("You do not have permission to access this resource!");
        }

        try {
            TargetCacheWarmer cache = getOrElse
                ("IDGApp.targetWarmCache", new Callable<TargetCacheWarmer> () {
                        public TargetCacheWarmer call () throws Exception {
                            Logger.debug("Warming up target cache...");
                            QueryIterator<Keyword> kiter = KeywordFactory
                            .finder.where()
                            .eq("label", UNIPROT_ACCESSION)
                            .findIterate();
                            
                            List<String> targets = new ArrayList<String>();
                            while (kiter.hasNext()) {
                                Keyword kw = kiter.next();
                                targets.add(kw.term);
                            }
                            
                            return new TargetCacheWarmer (targets);
                        }
                    });
            ObjectMapper mapper = new ObjectMapper ();
            return ok ((JsonNode)mapper.valueToTree(cache));
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't retrieve target cache", ex);
            return _internalServerError (ex);
        }
    }

    static void getBreadcrumb (List<Keyword> breadcrumb,
                               Target t, String prefix) {
        for (Value v : t.properties) {
            if (v.label != null && v.label.startsWith(prefix)) {
                try {
                    Keyword kw = (Keyword)v;
                    String url = ix.idg.controllers
                        .routes.IDGApp.targets(null, 10, 1).url();
                    kw.href = url + (url.indexOf('?') > 0 ? "&":"?")
                        +"facet="+kw.label+"/"
                        +URLEncoder.encode(kw.term, "utf8");
                    breadcrumb.add(kw);
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.error("Can't generate breadcrumb for "
                                 +getId (t), ex);
                }
            }
        }
    }

    static List<Keyword> getBreadcrumb (Target t) {
        List<Keyword> breadcrumb = new ArrayList<Keyword>();

        getBreadcrumb (breadcrumb, t, DTO_PROTEIN_CLASS);
        if (breadcrumb.isEmpty()) { // now try panther
            getBreadcrumb (breadcrumb, t, PANTHER_PROTEIN_CLASS);
        }
        
        // just make sure the order is correct
        Collections.sort(breadcrumb, new Comparator<Keyword>() {
                public int compare (Keyword kw1, Keyword kw2) {
                    return kw1.label.compareTo(kw2.label);
                }
            });
        return breadcrumb;
    }

    public static Double getMinimumLigandActivity(EntityModel e) {
        Double ret = -1.0;
        String[] actNames = {"Ki", "Kd", "EC50", "IC50", "ED50", "A2", "Kb"};
        for (String actName : actNames) {
            //System.out.println("actName = " + actName);
            for (Value v : getProperties(e, actName)) {
                double d = ((VNum) v).getNumval();
                System.out.println("  d = " + d);
                if (d > ret) ret = d; // since activity values are -log10 (molar units)
            }
        }
        return ret;
    }

    public static List<Ligand> getChemblLigands(EntityModel e) {
        List<Ligand> ret = new ArrayList<>();
        for (Ligand lig : getLigands(e)) {
            if (lig.getSynonym(IDG_DRUG) != null
                || lig.getProperty(LIGAND_DRUG) != null)
                ;
            else
                ret.add(lig);
        }
        return ret;
    }

    public static List<Ligand> getDrugLigands(EntityModel e) {
        List<Ligand> ret = new ArrayList<>();
        for (Ligand lig : getLigands(e)) {
            if (lig.getSynonym(IDG_DRUG) != null
                || lig.getProperty(LIGAND_DRUG) != null)
                ret.add(lig);
        }
        return ret;
    }

    public static List<Ligand> getLigands (EntityModel e) {
        return getLinkedObjects (e, Ligand.class);
    }

    public static List<Ligand> getLigandsWithActivity(EntityModel e) {
        List<Ligand> ligands = new ArrayList<Ligand>();
        for (XRef xref : e.getLinks()) {
            try {
                Class cls = Class.forName(xref.kind);
                if (Ligand.class.isAssignableFrom(cls)) {
                    Ligand lig = (Ligand) xref.deRef();

                    ligands.add((Ligand) xref.deRef());
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                Logger.error("Can't resolve XRef "
                        + xref.kind + ":" + xref.refid, ex);
            }
        }
        return ligands;
    }

    public static List<Value> getLinkedProperties
        (EntityModel e, Class kind, String label) {
        List<Value> props = new ArrayList<Value>();
        for (XRef ref : getLinks (e, kind)) {
            try {
                Class cls = Class.forName(ref.kind);
                if (kind.isAssignableFrom(cls)) {
                    for (Value p : ref.properties) {
                        if (p.label.equals(label))
                            props.add(p);
                    }
                }
            }
            catch (Exception ex) {
                Logger.error("Bogus XRef class: "+ref.kind, ex);
            }
        }
        return props;
    }

    public static List<Value> getProperties (EntityModel e, String label) {
        return getProperties (e, label, 0);
    }

    static Comparator<Value> CompareValues = new Comparator<Value>() {
            public int compare (Value v1, Value v2) {
                return v1.label.compareTo(v2.label);
            }
        };
    public static List<Value> getProperties
        (EntityModel e, String label, int dir) {
        List<Value> props = new ArrayList<Value>();
        
        if (dir < 0) { // prefix
            for (Value v : e.getProperties())
                if (v.label.startsWith(label))
                    props.add(v);
            Collections.sort(props, CompareValues);
        }
        else if (dir > 0) { // suffix
            for (Value v : e.getProperties())
                if (v.label.endsWith(label)) {
                    if (!v.label.equals("TM Count")) {
                        props.add(v);
                    }
                }
            Collections.sort(props, CompareValues);
        }
        else {
            for (Value v : e.getProperties()) 
                if (label.equalsIgnoreCase(v.label))
                    props.add(v);
        }
        
        return props;
    }

    public static List<XRef> getLinks (EntityModel e, Class klass) {
        List<XRef> links = new ArrayList<XRef>();
        for (XRef ref : e.getLinks()) {
            try {
                Class cls = Class.forName(ref.kind);
                if (klass.isAssignableFrom(cls)) {
                    links.add(ref);
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
                Logger.error("Bogus XRef class: "+ref.kind, ex);
            }
        }
        
        return links;
    }

    public static <T> List<T> getLinkedObjects (EntityModel e, Class<T> klass) {
        List<T> objects = new ArrayList<T>();
        for (XRef xref : getLinks (e, klass)) {
            try {
                objects.add((T)xref.deRef());
            }
            catch (Exception ex) {
                ex.printStackTrace();
                Logger.error("Can't resolve XRef "
                             +xref.kind+":"+xref.refid, ex);
            }
        }
        
        return objects;
    }

    public static List<Compartment> getCompartments (EntityModel e) {
        return getLinkedObjects (e, Compartment.class);
    }

    public static List<Keyword> getCompartmentGOTerms (EntityModel e) {
        Set<Keyword> terms = new TreeSet<Keyword>();
        for (XRef ref : getLinks (e, Compartment.class)) {
            for (Value v : ref.properties) {
                if (COMPARTMENT_GOTERM.equals(v.label)) {
                    terms.add((Keyword)v);
                }
            }
        }
        return new ArrayList<Keyword>(terms);
    }
    
    public static List<Mesh> getMesh (EntityModel e) {
        Map<String, Mesh> mesh = new TreeMap<String, Mesh>();
        for (Publication p : e.getPublications()) {
            for (Mesh m : p.mesh)
                mesh.put(m.heading, m);
        }
        return new ArrayList<Mesh>(mesh.values());
    }


    static Map<String, List<DiseaseRelevance>> 
        getDiseaseRelevances2 (Target t) throws Exception {
        Map<String, List<DiseaseRelevance>> diseases = new TreeMap<>();

        long start = System.currentTimeMillis();
        for (XRef xref : t.links) {
            if (Disease.class.getName().equals(xref.kind)) {
                DiseaseRelevance dr = new DiseaseRelevance ();
                dr.disease = (Disease)xref.deRef();

                String source = null;
                for (Value p : xref.properties) {
                    if (IDG_ZSCORE.equals(p.label))
                        dr.zscore = (Double)p.getValue();
                    else if (IDG_CONF.equals(p.label))
                        dr.conf = (Double)p.getValue();
                    else if (TINX_IMPORTANCE.equals(p.label))
                        dr.tinxScore = (Double)p.getValue();
                    else if (UNIPROT_DISEASE_RELEVANCE.equals(p.label)
                             || p.label.equals(dr.disease.name)) {
                        dr.comment = ((Text)p).text;
                    }
                    else if (SOURCE.equals(p.label))
                        source = ((Keyword)p).term;
                    else if ("pvalue".equals(p.label))
                        dr.conf = (Double)p.getValue();
                }

                if (source != null) {
                    List<DiseaseRelevance> list = diseases.get(source);
                    if (list == null)
                        diseases.put(source, list = new ArrayList<>());
                    list.add(dr);
                }
            }
        }

        for (Map.Entry<String, List<DiseaseRelevance>> me : 
                 diseases.entrySet()) {
            switch (me.getKey()) {
            case "Expression Atlas":
                Collections.sort
                    (me.getValue(), new Comparator<DiseaseRelevance> () {
                        public int compare (DiseaseRelevance dr1, 
                                            DiseaseRelevance dr2) {
                            if (dr1.conf < dr2.conf) return -1;
                            if (dr1.conf > dr2.conf) return 1;
                            return dr1.disease.name.compareTo(dr2.disease.name);
                        }
                    });
                break;

            default:
                Collections.sort
                    (me.getValue(), new Comparator<DiseaseRelevance> () {
                        public int compare (DiseaseRelevance dr1,
                                            DiseaseRelevance dr2) {
                            if (dr2.zscore > dr1.zscore) return 1;
                            if (dr2.zscore < dr1.zscore) return -1;
                            return dr1.disease.name.compareTo(dr2.disease.name);
                        }
                    });
            }
        }

        double elapsed = (System.currentTimeMillis()-start)*1e-3;
        Logger.debug("Elapsed time "+String.format("%1$.3fs", elapsed)
                     +" to retrieve disease relevance for target "+t.id);

        return diseases;
    }

    static List<DiseaseRelevance>
        getDiseaseRelevances (Target t) throws Exception {
        List<DiseaseRelevance> diseases = new ArrayList<DiseaseRelevance>();
        List<DiseaseRelevance> diseases2 = new ArrayList<DiseaseRelevance>();
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
                /*
                long s = System.currentTimeMillis();
                getLineage (lineage, dr.disease);
                Logger.debug("Retrieve lineage for disease "+dr.disease.id+"..."
                             +String.format("%1$dms", (System.currentTimeMillis()-s)));
                */
                
                /*
                { Disease d = dr.disease;
                    for (Disease parent : getLineage (d)) {
                        lineage.put(d.id, parent);
                        d = parent;
                    }
                }
                */
                String source = null;
                for (Value p : xref.properties) {
                    if (IDG_ZSCORE.equals(p.label))
                        dr.zscore = (Double)p.getValue();
                    else if (IDG_CONF.equals(p.label))
                        dr.conf = (Double)p.getValue();
                    else if (TINX_IMPORTANCE.equals(p.label))
                        dr.tinxScore = (Double)p.getValue();
                    else if (UNIPROT_DISEASE_RELEVANCE.equals(p.label)
                             || p.label.equals(dr.disease.name)) {
                        dr.comment = ((Text)p).text;
                    }
                    else if (SOURCE.equals(p.label))
                        source = ((Keyword)p).term;
                    else if ("pvalue".equals(p.label))
                        dr.conf = (Double)p.getValue();
                }

                if (dr.zscore != null)
                    diseases.add(dr);
                else
                    diseases2.add(dr);
                
                if (dr.comment != null) {
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

        Collections.sort(diseases2); // sort by name..
        // sort by zscore
        Collections.sort(diseases, new Comparator<DiseaseRelevance>() {
                public int compare (DiseaseRelevance dr1, 
                                    DiseaseRelevance dr2) {
                    double d = 0.;
                    if (dr1.zscore != null && dr2.zscore != null)
                        d = dr2.zscore - dr1.zscore;
                    else if (dr1.conf != null && dr2.conf != null)
                        d = dr2.conf - dr1.conf;
                    else if (dr1.zscore == null && dr2.zscore != null)
                        d = dr2.zscore;
                    else if (dr2.zscore == null && dr1.zscore != null)
                        d = -dr1.zscore;

                    if (d < 0) return -1;
                    if (d > 0) return 1;
                    return dr1.disease.name.compareTo(dr2.disease.name);
                }
            });
        diseases.addAll(diseases2);

        double elapsed = (System.currentTimeMillis()-start)*1e-3;
        Logger.debug("Elapsed time "+String.format("%1$.3fs", elapsed)
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

    static Result createTargetResult
            (final SearchResult result, final int rows, final int page) {
        try {
            if (result.finished()) {
                final String key = "createTargetResult/"+Util.sha1(request ()); 
                return ok (getOrElse_ (key, new Callable<Content>() {
                        public Content call () throws Exception {
                            return CachableContent.wrap
                                (createTargetContent (result, rows, page));
                        }
                    }));
            }
            return ok (createTargetContent (result, rows, page));
        }
        catch (Exception ex) {
            return _internalServerError (ex);
        }
    }

    static Content createTargetContent
        (SearchResult result, int rows, int page) {
        String[] reqf = request().queryString().get("facet");

        // Expand TARGET_FACETS to add in facets that were selected via
        //  the full facet
        // listing page but aren't part of the default set of facets
        List<String> facetFilter =
            new ArrayList<String>(Arrays.asList(TARGET_FACETS));
        if (reqf != null) {
            for (String af : reqf) {
                String fname = af.split("/")[0];
                if (!facetFilter.contains(fname))
                    facetFilter.add(fname);
            }
        }
        
        Facet[] facets = filter
            (result.getFacets(), facetFilter.toArray(new String[]{}));
        List<Target> targets = new ArrayList<Target>();
        int[] pages = new int[0];
        if (result.count() > 0) {
            rows = Math.min(result.count(), Math.max(1, rows));
            pages = paging (rows, page, result.count());
            result.copyTo(targets, (page-1)*rows, rows);
        }
        
        return ix.idg.views.html.targets.render
            (page, rows, result.count(), pages,
             decorate (Target.class, facets), targets, result.getKey());
    }

    static Result createLigandResult
        (final SearchResult result, final int rows, final int page) {
        try {
            if (result.finished()) {
                final String key = "createLigandResult/"+Util.sha1(request());
                return ok (getOrElse (key, new Callable<Content>() {
                        public Content call () throws Exception {
                            return CachableContent.wrap
                                (createLigandContent (result, rows, page));
                        }
                    }));
            }
            return ok (createLigandContent (result, rows, page));
        }
        catch (Exception ex) {
            return _internalServerError (ex);
        }
    }

    static Content createLigandContent
        (SearchResult result, int rows, int page) {
        TextIndexer.Facet[] facets = filter
                (result.getFacets(), LIGAND_FACETS);

        List<Ligand> ligands = new ArrayList<Ligand>();
        int[] pages = new int[0];
        if (result.count() > 0) {
            rows = Math.min(result.count(), Math.max(1, rows));
            pages = paging (rows, page, result.count());
            result.copyTo(ligands, (page-1)*rows, rows);
        }

        return ix.idg.views.html.ligandsgallery.render
            (page, rows, result.count(),
             pages, decorate (Ligand.class, facets), ligands, null);
    }

    static Result createDiseaseResult
            (final SearchResult result, final int rows, final int page) {
        try {
            if (result.finished()) {
                final String key =
                    "createDiseaseResult/"+Util.sha1(request ()); 
                return ok (getOrElse (key, new Callable<Content>() {
                        public Content call () throws Exception {
                            return CachableContent.wrap
                                (createDiseaseContent (result, rows, page));
                        }
                    }));
            }
            return ok (createDiseaseContent (result, rows, page));
        }
        catch (Exception ex) {
            return _internalServerError (ex);
        }
    }
    
    static Content createDiseaseContent
            (SearchResult result, int rows, int page) {
        Facet[] facets = filter
                (result.getFacets(), DISEASE_FACETS);

        List<Disease> diseases = new ArrayList<Disease>();
        int[] pages = new int[0];
        if (result.count() > 0) {
            rows = Math.min(result.count(), Math.max(1, rows));
            pages = paging (rows, page, result.count());
            result.copyTo(diseases, (page-1)*rows, rows);
        }

        return ix.idg.views.html.diseases.render
            (page, rows, result.count(),
             pages, decorate (facets), diseases);
    }

    public static Result targets (String q, final int rows, final int page) {
        try {
            if (q != null && q.trim().length() == 0)
                q = null;
            
            String type = request().getQueryString("type");
            // if URL contains an action we want to pass through to _targets
            boolean download = request().uri().contains("download");
            if (q != null && type != null && !download) {
                if (type.equalsIgnoreCase("sequence")) {
                    return sequences (q, rows, page);
                }
                else if (type.equalsIgnoreCase("batch")) {
                    return batchSearch (q, rows, page);
                }
                else if (type.equalsIgnoreCase("pmid")) {
                    return targetsForPublication
                        (Long.parseLong(q), rows, page);
                }
                else if (type.equalsIgnoreCase("filter")) {
                    return filter (q, rows, page);
                }
            }
            
            return _targets (q, rows, page);
        }
        catch (Exception ex) {
            Logger.error("Can't retrieve targets", ex);
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    static Result _targets (final String q, final int rows, final int page)
        throws Exception {
        
        final int total = TargetFactory.finder.findRowCount();
        Logger.debug("Targets: q="+q+" rows="+rows+" page="
                     +page+" total="+total);

        if (request().queryString().containsKey("facet") || q != null) {
            Map<String, String[]> query = getRequestQuery ();
            if (!query.containsKey("order") && q == null) {
                // only implicitly order based on novelty if it's not a
                // search
                //query.put("order", new String[]{"$novelty"});
            }
            
            final SearchResult result =
                getSearchResult (Target.class, q, total, query);
            
            String action = request().getQueryString("action");
            if (action != null && action.equalsIgnoreCase("download")
                && result.size() < 500) {
                return DownloadEntities.download(result);
            }
            
            return createTargetResult (result, rows, page);
        }
        else {
            // this is just paging..
            final String key = "targets/"+total+"/"+Util.sha1(request ());
            return ok (getOrElse_ (key+"/result", new Callable<Content> () {
                    public Content call () throws Exception {
                        return getTargetContent (total, rows, page);
                    }
                }));
        }
    }

    /*
     * this is different from createTargetContent in that it's just 
     * paging through (no facets and/or queries)
     */
    static Content getTargetContent (int total, int rows, int page) {
        SearchResult result = getSearchFacets(Target.class);    
        Facet[] facets = filter (result.getFacets(), TARGET_FACETS);
        rows = Math.min(total, Math.max(1, rows));
        int[] pages = paging (rows, page, total);
        
        List<Target> targets = TargetFactory.getTargets
            (rows, (page-1)*rows, null);
        
        return ix.idg.views.html.targets.render
            (page, rows, total, pages, decorate (facets),
             targets, result.getKey());
    }

    public static Result sequences (final String q,
                                    final int rows, final int page) {
        String param = request().getQueryString("identity");
        double identity = 0.5;
        if (param != null) {
            try {
                identity = Double.parseDouble(param);
            }
            catch (NumberFormatException ex) {
                Logger.error("Bogus identity value: "+param);
            }
        }
        
        String seq = App.getSequence(q);
        if (seq != null) {
            Logger.debug("sequence: "
                         +seq.substring(0, Math.min(seq.length(), 20))
                         +"; identity="+identity);
            return _sequences (seq, identity, rows, page);
        }
        
        return internalServerError ("Unable to retrieve sequence for "+q);
    }

    public static Result _sequences (final String seq, final double identity,
                                     final int rows, final int page) {
        try {
            SearchResultContext context = sequence
                (seq, identity, rows,
                 page, new IDGSequenceResultProcessor ());
            
            return App.fetchResult
                (context, rows, page, new DefaultResultRenderer<Target> () {
                        public Content getContent
                            (SearchResultContext context,
                             int page, int rows,
                             int total, int[] pages,
                             List<Facet> facets,
                             List<Target> targets) {
                            return ix.idg.views.html.targets.render
                                (page, rows, total,
                                 pages, decorate
                                 (Target.class,
                                  filter (facets, TARGET_FACETS)),
                                 targets, context.getId());
                        }
                    });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't perform sequence search", ex);
            return _internalServerError (ex);
        }
    }

    public static Result filter (final String q,
                                 final int rows, final int page) {
        try {
            final String key = "filter/result/"+Util.sha1(q);
            SearchResultContext context = getOrElse
                (key, new Callable<SearchResultContext> () {
                        public SearchResultContext call () throws Exception {
                            IDGTargetFilterProcessor processor =
                            new IDGTargetFilterProcessor (q);
                            processor.setResults(rows, processor);
                            return processor.getContext();
                        }
                    });
            
            return App.fetchResult
                (context, rows, page, new DefaultResultRenderer<Target> () {
                        public Content getContent
                            (SearchResultContext context,
                             int page, int rows,
                             int total, int[] pages,
                             List<Facet> facets,
                             List<Target> targets) {
                            return ix.idg.views.html.targets.render
                                (page, rows, total,
                                 pages, decorate
                                 (Target.class,
                                  filter (facets, TARGET_FACETS)),
                                 targets, context.getId());
                        }
                    });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't perform filter '"+q+"'", ex);
            return _internalServerError (ex);
        }           
    }

    @BodyParser.Of(value = BodyParser.FormUrlEncoded.class,
                   maxLength = 20000)
    public static Result sequence () {
        if (request().body().isMaxSizeExceeded()) {
            return badRequest ("Sequence is too large!");
        }
        
        Map<String, String[]> params = request().body().asFormUrlEncoded();
        String[] values = params.get("sequence");
        if (values != null && values.length > 0) {
            String seq = values[0];
            
            double iden = 0.5;
            String[] idparm = params.get("identity");
            if (idparm != null && idparm.length > 0) {
                try {
                    iden = Double.parseDouble(idparm[0]);
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            
            try {
                Payload payload = _payloader.createPayload
                    ("Sequence Search", "text/plain", seq);
                Call call = routes.IDGApp.targets(payload.id.toString(), 10, 1);
                return redirect (call.url()+"&type=sequence&identity="+iden);
            }
            catch (Exception ex) {
                ex.printStackTrace();
                return _internalServerError (ex);
            }
        }
        
        return badRequest ("Invalid \"sequence\" parameter specified!");
    }

    public static Keyword[] getAncestry (final String facet,
                                         final String predicate) {
        try {
            final String key = predicate+"/"+facet
                +"/"+signature (null, request().queryString());
            return getOrElse
                (key, new Callable<Keyword[]> () {
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
                    .add(Expr.eq("subject.refid", anchor.id.toString()))
                    .add(Expr.eq("subject.kind", anchor.getClass().getName()))
                    .add(Expr.eq("predicate", predicate))
                    .findList();
                if (!pred.isEmpty()) {
                    for (XRef ref : pred.iterator().next().objects) {
                        if (ref.kind.equals(anchor.getClass().getName())) {
                            Keyword kw = (Keyword)ref.deRef();
                            /*
                            String url = ix.idg.controllers
                                .routes.IDGApp.targets(null, 20, 1).url();
                            */
                            String url = App.url(App.removeIfMatch("facet", facet));
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
        return getAncestry (facet,
                            //ChEMBL_PROTEIN_ANCESTRY
                            DTO_PROTEIN_ANCESTRY
                            );
    }

    public static Keyword[] getATCAncestry (String facet) {
        List<Keyword> ancestry = new ArrayList<Keyword>();
        String[] toks = facet.split("/");
        if (toks[0].equals(WHO_ATC)) {
            Keyword kw;
            String atc = toks[1];
            try {
                int len = atc.length();
                switch (len) {
                case 1:
                    break;
                    
                case 7:
                    kw = getATC (atc.substring(0,5));
                    if (kw != null)
                        ancestry.add(kw);
                    // fall through
                case 5:
                    kw = getATC (atc.substring(0,4));
                    if (kw != null)
                        ancestry.add(kw);
                    // fall through
                case 4:
                    kw = getATC (atc.substring(0,3));
                    if (kw != null)
                        ancestry.add(kw);
                    // fall through
                case 3:
                    kw = getATC (atc.substring(0,1));
                    if (kw != null)
                        ancestry.add(kw);

                    if (!ancestry.isEmpty())
                        Collections.sort(ancestry, new Comparator<Keyword>() {
                                public int compare (Keyword kw1, Keyword kw2) {
                                    return kw1.label.compareTo(kw2.label);
                                }
                            });
                    
                    break;
                default:
                    Logger.warn("Not a valid ATC facet value: "+ atc);
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
                Logger.error("Can't get ATC", ex);
            }
        }
        return ancestry.toArray(new Keyword[0]);
    }

    public static Result search (String kind) {
        String ctx = request().getQueryString("ctx");
        if (ctx != null) {
            SearchResult result = getSearchContext(ctx);
            if (result != null) {
                List<Target> targets = filter
                    (Target.class, result.getMatches(), 999);
                List<Disease> diseases = filter
                    (Disease.class, result.getMatches(), 999);
                List<Ligand> ligands = filter
                    (Ligand.class, result.getMatches(), 999);
                List<Publication> pubs = filter
                    (Publication.class, result.getMatches(), 999);
                try {
                    byte[] bytes = DownloadEntities.downloadEntities
                        (targets, diseases, ligands, pubs);
                    response().setHeader
                        ("Content-Disposition",
                         "attachment;filename=search-results.zip");
                    return ok(bytes).as("application/zip");
                }
                catch (Exception e) {
                    return _internalServerError(e);
                }
            }
        }

        try {
            String q = request().getQueryString("q");
            String t = request().getQueryString("type");

            if (kind != null && !"".equals(kind)) {
                if (Target.class.getName().equals(kind))
                    return redirect (routes.IDGApp.targets(q, 10, 1));
                else if (Disease.class.getName().equals(kind))
                    return redirect (routes.IDGApp.diseases(q, 10, 1));
                else if (Ligand.class.getName().equals(kind))
                    return redirect (routes.IDGApp.ligands(q, 16, 1));
            }
            else if ("substructure".equalsIgnoreCase(t)) {
                String url = routes.IDGApp.ligands(q, 16, 1).url()
                    +"&type=substructure";
                return redirect (url);
            }
            else if ("similarity".equalsIgnoreCase(t)) {
                String cutoff = request().getQueryString("cutoff");
                if (cutoff == null) {
                    cutoff = "0.8";
                }
                String url = routes.IDGApp.ligands(q, 16, 1).url()
                    +"&type=similarity&cutoff="+cutoff;
                return redirect (url);
            }
            else if ("sequence".equalsIgnoreCase(t)) {
                String iden = request().getQueryString("identity");
                if (iden == null) {
                    iden = "0.5";
                }
                String url = routes.IDGApp.targets(q, 10, 1).url()
                    +"&type=sequence&identity="+iden;
                return redirect (url);
            }
            
            // generic entity search..
            return search (5);
        }
        catch (Exception ex) {
            Logger.debug("Can't resolve class: "+kind, ex);
        }
            
        return _badRequest("Invalid request: " + request().uri());
    }

    static <T> List<T> filter (Class<T> cls, List values, int max) {
        List<T> fv = new ArrayList<T>();
        for (Object v : values) {
            if (v != null && cls.isInstance(v)) {
                fv.add((T)v);
                if (fv.size() >= max)
                    break;
            }
        }
        return fv;
    }

    public static Result search (final int rows) {
        try {
            final String key = "search/"+Util.sha1(request ());
            return getOrElse (key, new Callable<Result> () {
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
        String searchkey = null;

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
            final String key =
                "search/facet/"+Util.sha1(queryString.get("facet"));
            searchkey = key;
            result = getOrElse
                (key, new Callable<TextIndexer.SearchResult>() {
                        public TextIndexer.SearchResult
                            call ()  throws Exception {
                            return SearchFactory.search
                            (_textIndexer, null, null, MAX_SEARCH_RESULTS,
                             0, FACET_DIM, queryString);
                        }
                    });
            double ellapsed = (System.currentTimeMillis()-start)*1e-3;
            Logger.debug
                ("1. Ellapsed time "+String.format("%1$.3fs", ellapsed));
        }

        if (result == null || result.count() == 0) {
            long start = System.currentTimeMillis();
            final String key =
                "search/facet/q/"+Util.sha1(request(), "facet", "q");
            searchkey = key;
            result = getOrElse
                (key, new Callable<TextIndexer.SearchResult>() {
                        public TextIndexer.SearchResult
                            call () throws Exception {
                            return SearchFactory.search
                            (_textIndexer, null, query, MAX_SEARCH_RESULTS, 0,
                             FACET_DIM, request().queryString());
                        }
                    });
            double ellapsed = (System.currentTimeMillis()-start)*1e-3;
            Logger.debug
                ("2. Ellapsed time "+String.format("%1$.3fs", ellapsed));
        }

        TextIndexer.Facet[] facets = filter (result.getFacets(), ALL_FACETS);
        final int max = Math.min(rows, Math.max(1, result.count()));
        
        int total = 0, totalTargets = 0, totalDiseases = 0,
            totalLigands = 0, totalPubs = 0;
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
                    else if (Publication.class.getName().equals(fv.getLabel())) {
                        totalPubs = fv.getCount();
                        total += totalPubs;
                    }
                }
            }
        }
        
        // don't filter on publications.. good idea?
        List<Publication> publications = filter
            (Publication.class, result.getMatches(), totalPubs);

        result = getSearchResult (Target.class, query, max);
        List<Target> targets = filter (Target.class, result.getMatches(), max); 

        result = getSearchResult (Disease.class, query, max);
        List<Disease> diseases = filter (Disease.class, result.getMatches(), max);

        result = getSearchResult (Ligand.class, query, max);    
        List<Ligand> ligands = filter (Ligand.class, result.getMatches(), max);

        return ok(ix.idg.views.html.search.render
                (query, total, decorate(facets),
                        targets, totalTargets,
                        ligands, totalLigands,
                        diseases, totalDiseases,
                        publications, totalPubs,searchkey));
    }

    public static Keyword getATC (final String term) throws Exception {
        final String key = WHO_ATC+" "+term;
        return getOrElse (0l, key, new Callable<Keyword>() {
                public Keyword call () {
                    List<Keyword> kws = KeywordFactory.finder.where()
                        .eq("label", key).findList();
                    if (!kws.isEmpty()) {
                        Keyword n = kws.iterator().next();
                        String url = routes.IDGApp.ligands(null, 16, 1).url();
                        n.term = n.term.toLowerCase();
                        n.href = url + (url.indexOf('?') > 0?"&":"?")
                            +"facet="+WHO_ATC+"/"+term;
                        return n;
                    }
                    return null;
                }
            });
    }

    public static Keyword getATC (final Keyword kw) throws Exception {
        if (kw.label.equals(WHO_ATC))
            return getATC (kw.term);
        Logger.warn("Not a valid ATC label: "+kw.label);
        return null;
    }
    
    public static Result ligands (String q, final int rows, final int page) {
        String type = request().getQueryString("type");
        if (q != null && q.trim().length() == 0)
            q = null;
        
        long start = System.currentTimeMillis();
        try {
            if (type != null && (type.equalsIgnoreCase("substructure")
                                 || type.equalsIgnoreCase("similarity"))) {
                // structure search
                String cutoff = request().getQueryString("cutoff");
                Logger.debug("Search: q="+q+" type="+type+" cutoff="+cutoff);
                if (type.equalsIgnoreCase("substructure")) {
                    return substructure (q, rows, page);
                }
                else {
                    return similarity
                        (q, Double.parseDouble(cutoff), rows, page);
                }
            }
            else {
                return _ligands (q, rows, page);
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
        finally {
            Logger.debug("ligands: q="+q+" rows="+rows+" page="+page
                         +"..."+String.format
                         ("%1$dms", System.currentTimeMillis()-start));
        }
    }

    static Result _ligands (final String q, final int rows, final int page)
        throws Exception {
        final String key = "ligands/"+Util.sha1(request ());
        Logger.debug("ligands: q="+q+" rows="+rows+" page="+page+" key="+key);
        
        final int total = LigandFactory.finder.findRowCount();
        if (request().queryString().containsKey("facet") || q != null) {
            final TextIndexer.SearchResult result =
                getSearchResult (Ligand.class, q, total, getRequestQuery ());
            
            String action = request().getQueryString("action");
            if (action == null) action = "";

            if (action.toLowerCase().equals("download")) {
                if (result.count() > 0) {
                    List<Ligand> ligands = new ArrayList<>();
                    for (int i = 0; i < result.count(); i++) {
                        ligands.add((Ligand) result.getMatches().get(i));
                    }
                    
                    byte[] contents =
                        DownloadEntities.downloadEntities(ligands);
                    response().setHeader
                        ("Content-Disposition",
                         "attachment;filename=export-ligand.csv");
                    return ok(contents).as(DownloadEntities
                                           .getDownloadMimeType(Ligand.class));
                }
            }

            return createLigandResult (result, rows, page);
        }
        else {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        Facet[] facets =
                            filter (getFacets (Ligand.class, FACET_DIM),
                                    LIGAND_FACETS);
            
                        int _rows = Math.max(1, Math.min(total, rows));
                        int[] pages = paging (_rows, page, total);
            
                        List<Ligand> ligands = LigandFactory.getLigands
                            (_rows, (page-1)*_rows, null);
                        
                        List<FacetDecorator> decorators = new ArrayList<>();
                        for (FacetDecorator deco : decorate (facets))
                            decorators.add(deco);
                        /*
                        try {
                            decorators.add
                                (3, // insert after IDG_TARGET
                                 new LigandTargetSelectivityFacetDecorator ());
                        }
                        catch (IOException ex) {
                            Logger.error
                                ("Can't create Target Selectivity facet!", ex);
                        }
                        */
                        return ok (ix.idg.views.html.ligandsgallery.render
                                   (page, _rows, total, pages,
                                    decorators.toArray(new FacetDecorator[0]),
                                    ligands, null));
                    }
                });
        }
    }

    static final GetResult<Ligand> LigandResult =
        new GetResult<Ligand>(Ligand.class, LigandFactory.finder) {
            public Content getContent (List<Ligand> ligands) throws Exception {
                return getLigandContent (ligands);
            }
            @Override protected Result notFound (String mesg) {
                return _notFound (mesg);
            }
            @Override protected Result error (Exception ex) {
                return _internalServerError (ex);
            }
        };

    public static String getDTOId(Target t) {
        if (t != null) {
            for (Keyword kw : t.synonyms) {
                if (kw.label.equals(DTO_ID))
                    return kw.term;
            }
        }
        return null;
    }

    public static String getGeneSymbol(Target t) {
        if (t != null) {
            for (Keyword kw : t.synonyms) {
                if (kw.label.equals(UNIPROT_GENE))
                    return kw.term;
            }
        }
        return null;
    }

    static Content getLigandContent (List<Ligand> ligands) throws Exception {
        // force it to show only one since it's possible that the provided
        // name isn't unique
        if (true || ligands.size() == 1) {
            Ligand ligand = ligands.iterator().next();
            
            List<Keyword> breadcrumb = new ArrayList<Keyword>();
            for (Keyword kw : ligand.synonyms) {
                if (kw.label.equals(WHO_ATC)
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
            
            return ix.idg.views.html
                .liganddetails.render(ligand, acts, breadcrumb);
        }
        else {
            TextIndexer indexer = _textIndexer.createEmptyInstance();
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
            indexer.shutdown();
        
            return ix.idg.views.html.ligands.render
                (1, result.count(), result.count(),
                 new int[0], decorate (facets), ligands);
        }
    }
    
    public static Result ligand (String name) {
        return LigandResult.get(name);
    }

    static boolean isSimpleName (String name) {
        boolean special = name.indexOf('<') > 0 || name.indexOf('&') > 0
            || name.indexOf('/') > 0;
        return !special;
    }
    
    /**
     * return the canonical/default ligand id
     */
    public static String getId (Ligand ligand) {
        String name = ligand.getName();
        if (!isSimpleName (name)) {
            for (Keyword kw : ligand.synonyms)
                if (isSimpleName (kw.term))
                    return kw.term;
            return ligand.getId().toString();
        }
        return name;
    }

    public static Keyword getPreferredSynonym (Ligand ligand, String label) {
        for (Keyword kw : ligand.synonyms)
            if (label.equalsIgnoreCase(kw.label))
                return kw;
        return ligand.synonyms.isEmpty() ? null : ligand.synonyms.get(0);
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
                    if (IDG_DEVELOPMENT.equals(v.label)) {
                        tdls.add(Target.TDL.fromString(((Keyword)v).term));
                    }
                }
            }
        }
        return tdls;
    }

    public static Set<String> getMechanisms (Ligand lig) {
        Set<String> moa = new TreeSet<String>();
        for (XRef ref : lig.links) {
            if (ref.kind.equals(Target.class.getName())) {
                for (Value v : ref.properties) {
                    if (ChEMBL_MECHANISM.equals(v.label))
                        moa.add(((Text)v).text);
                }
            }
        }
        return moa;
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

    public static Result fetchResult
        (final SearchResultContext context, int rows, int page)
        throws Exception {
        return App.fetchResult
            (context, rows, page, new DefaultResultRenderer<Ligand> () {
                    public Content getContent
                        (SearchResultContext context,
                         int page, int rows,
                         int total, int[] pages,
                         List<TextIndexer.Facet> facets,
                         List<Ligand> ligands) {
                        return ix.idg.views.html.ligandsgallery.render
                            (page, rows, total,
                             pages, decorate (filter
                                              (facets, LIGAND_FACETS)),
                             ligands, context.getId());
                    }
                });
    }

    public static Result similarity (final String query,
                                     final double threshold,
                                     final int rows,
                                     final int page) {
        try {
            SearchResultContext context = similarity
                (query, threshold, rows, page, new IDGSearchResultProcessor ());
            if (context != null) {
                return fetchResult (context, rows, page);
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't perform similarity search", ex);
        }
        return internalServerError
            (ix.idg.views.html.error.render
             (500, "Unable to perform similarity search: "+query));
    }
    
    public static Result substructure
        (final String query, final int rows, int page) {
        try {
            SearchResultContext context = substructure
                (query, rows, page, new IDGSearchResultProcessor ());
            if (context != null) {
                return fetchResult (context, rows, page);
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't perform substructure search", ex);
        }
        return internalServerError
            (ix.idg.views.html.error.render
             (500, "Unable to perform substructure search: "+query));
    }
    
    public static String getId (Disease d) {
        Keyword kw = d.getSynonym(DiseaseOntologyRegistry.DOID,
                                  DISGENET_DISEASE, UNIPROT_DISEASE);
        return kw != null ? kw.term : "IDG:D"+d.id;
    }
    
    static final GetResult<Disease> DiseaseResult =
        new GetResult<Disease>(Disease.class, DiseaseFactory.finder) {
            public Content getContent (List<Disease> diseases)
                throws Exception {
                return getDiseaseContent (diseases);
            }
            @Override protected Result notFound (String mesg) {
                return _notFound (mesg);
            }
            @Override protected Result error (Exception ex) {
                return _internalServerError (ex);
            }       
        };

    public static Result disease (final String name) {
        if (name.startsWith("IDG:D")) {
            try {
                long id = Long.parseLong(name.substring(5));
                Disease d = DiseaseFactory.getDisease(id);
                if (d != null)
                    return ok (getDiseaseContent (d));
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        return DiseaseResult.get(name);
    }

    static Content getDiseaseContent (final List<Disease> diseases)
        throws Exception {
        return getDiseaseContent (diseases.iterator().next());
    }

    static Content getDiseaseContent (final Disease d) throws Exception {
        // resolve the targets for this disease
        final String key = "diseases/"+d.id+"/targets";
        List<Target> targets = new ArrayList<>();
            /*
            getOrElse (key, new Callable<List<Target>> () {
                    public List<Target> call () throws Exception {
                        return DiseaseFactory.getTargets(d.id);
                    }
                });
            */

        final String ligkey = "diseases/"+d.id+"/ligands";
        List<Ligand> ligs = getOrElse(ligkey, new Callable<List<Ligand>> () {
            public List<Ligand> call () throws Exception {
                List<Ligand> ligands = new ArrayList<>();
                for (XRef ref : d.links) {
                    if (Ligand.class.isAssignableFrom(Class.forName(ref.kind))) {
                        Ligand l = (Ligand) ref.deRef();
                        ligands.add(l);
                    }
                }
                return ligands;
            }
        });
        
        return ix.idg.views.html.diseasedetails.render
            (d, ligs.toArray(new Ligand[0]),
             targets.toArray(new Target[0]), getBreadcrumb (d));
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
    
    public static Result diseases (String q, final int rows, final int page) {
        try {
            if (q != null && q.trim().length() == 0)
                q = null;
            return _diseases (q, rows, page);
        }
        catch (Exception ex) {
            return _internalServerError (ex);
        }
    }

    static Result _diseases (final String q, final int rows, final int page)
        throws Exception {
        final int total = DiseaseFactory.finder.findRowCount();
        final String key = "diseases/"+Util.sha1(request ());
        Logger.debug("Diseases: rows=" + rows + " page=" + page+" key="+key);
        
        if (request().queryString().containsKey("facet") || q != null) {
            final TextIndexer.SearchResult result =
                getSearchResult (Disease.class, q, total, getRequestQuery ());
            
            String action = request().getQueryString("action");
            if (action == null) action = "";

            if (action.toLowerCase().equals("download")) {
                List<Disease> diseases = new ArrayList<>();
                if (result.count() > 0) {
                    for (int i = 0; i < result.count(); i++) {
                        diseases.add((Disease) result.getMatches().get(i));
                    }
                    byte[] contents = DownloadEntities.downloadEntities(diseases);
                    response().setHeader("Content-Disposition", "attachment;filename=export-disease.csv");
                    return ok(contents).as(DownloadEntities.getDownloadMimeType(Disease.class));
                }
            }

            if (result.finished()) {
                // now we can cache the result
                return getOrElse
                        (key+"/result", new Callable<Result> () {
                            public Result call () throws Exception {
                                return createDiseaseResult
                                    (result, rows, page);
                            }
                        });
            }

            return createDiseaseResult (result, rows, page);
        }
        else {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        TextIndexer.Facet[] facets = filter
                            (getFacets (Disease.class, FACET_DIM),
                             DISEASE_FACETS);
                        int _rows = Math.min(total, Math.max(1, rows));
                        int[] pages = paging(_rows, page, total);
                        
                        List<Disease> diseases = DiseaseFactory.getDiseases
                            (_rows, (page - 1) * _rows, null);
            
                        return ok(ix.idg.views.html.diseases.render
                                  (page, _rows, total, pages,
                                   decorate (facets), diseases));
                    }
                });
        }
    }

    public static Result lastUnicorn (String url) {
        return _notFound ("Unknown resource: "+url);
    }

    public static Result getHierarchy (final String ctx, final String facet) {
        final SearchResult result = getSearchContext (ctx);
        if (result != null) {
            try {
                return getOrElse (ctx+"/hierarchy", new Callable<Result> () {
                        public Result call () throws Exception {
                            return ok (getHierarchyAsJson (result, facet));
                        }
                    });
            }
            catch (Exception ex) {
                ex.printStackTrace();
                Logger.trace("Can't retrieve hierarchy "+ctx+" for "+facet, ex);
                return _internalServerError (ex);
            }
        }
        return notFound ("Unknown search context "+ctx);
    }
    
    public static JsonNode getHierarchyAsJson
        (SearchResult result, String facet) {
        List<Facet> facets = new ArrayList<Facet>();
        for (Facet f : result.getFacets()) {
            if (f.getName().startsWith(facet)) {
                facets.add(f);
            }
        }

        if (facets.isEmpty()) {
            return null;
        }
        
        // order the facets from child to parent
        Collections.sort(facets, new Comparator<Facet>() {
                public int compare (Facet f1, Facet f2) {
                    return f2.getName().compareTo(f1.getName());
                }
            });

        /*
        for (Iterator<Facet> it = facets.iterator(); it.hasNext(); ) {
            Facet f = it.next();
            Logger.info("++ "+f.getName());
            for (TextIndexer.FV fv : f.getValues()) {
                Logger.info("++++ "+fv.getLabel()+" ("+fv.getCount()+")");
            }
        }
        */

        Iterator<Facet> nodeIter = facets.iterator();
        String predicate = "";
        if (DTO_PROTEIN_CLASS.equalsIgnoreCase(facet)) {
            predicate = DTO_PROTEIN_ANCESTRY;
            // should really fix this when we pull in the tcrd..
            nodeIter.next(); // skip level (4)
        }
        else if (PANTHER_PROTEIN_CLASS.equalsIgnoreCase(facet))
            predicate = PANTHER_PROTEIN_ANCESTRY;
        else if (ChEMBL_PROTEIN_CLASS.equalsIgnoreCase(facet))
            predicate = ChEMBL_PROTEIN_ANCESTRY;
        
        Map root = new TreeMap ();
        root.put("name", facet);
        root.put("children", new ArrayList<Map>());

        while (nodeIter.hasNext()) {
            Facet leaf = nodeIter.next();
            for (TextIndexer.FV fv : leaf.getValues()) {
                Keyword[] ancestors = getAncestry
                    (leaf.getName()+"/"+fv.getLabel(), predicate);
                
                Map node = root;
                for (int i = 0; i < ancestors.length; ++i) {
                    String name = ancestors[i].term;
                    List<Map> children = (List<Map>)node.get("children");
                    
                    Map child = null;
                    for (Map c : children) {
                        if (name.equalsIgnoreCase((String)c.get("name"))) {
                            child = c;
                            break;
                        }
                    }
                    
                    if (child == null) {
                        child = new HashMap ();
                        child.put("name", name);
                        child.put("children", new ArrayList<Map>());
                        children.add(child);
                    }
                    node = child;
                }
                
                // leaf
                List<Map> children = (List<Map>)node.get("children");
                Map child = null;
                for (Map c : children) {
                    if (fv.getLabel().equalsIgnoreCase((String)c.get("name"))) {
                        child = c;
                        break;
                    }
                }
                
                if (child == null) {
                    child = new HashMap ();
                    child.put("name", fv.getLabel());
                    child.put("size", fv.getCount());
                    children.add(child);
                }
            }
        }
        
        //Logger.debug(">>> "+ix.core.controllers.EntityFactory.getEntityMapper().toJson(root, true));
        
        ObjectMapper mapper = new ObjectMapper ();
        return mapper.valueToTree(root);
    }

    public static Sequence getSequence (final Target target) {
        try {
            return getOrElse ("targets/"+target.id+"/sequence", 
                              new Callable<Sequence> () {
                                  public Sequence call () throws Exception {
                                      return new Sequence (target);
                                  }
                              });
        }
        catch (Exception ex) {
            Logger.error("Can't retrieve sequence for target "+target.id, ex);
        }
        return null;
    }

    public static SequenceIndexer.Result
        getSeqAlignment (String context, Target target) {
        Value seq = target.getProperty(UNIPROT_SEQUENCE);
        SequenceIndexer.Result r = null;
        if (seq != null) {
            r = (SequenceIndexer.Result)IxCache.get
                ("Alignment/"+context+"/"+seq.id);
        }
        //Logger.debug("retrieving alignment "+context+" "+seq.id+" => "+r);
        return r;
    }

    public static MatchFragment[] getMatchFragments
        (String context, Object key) {
        SearchResult result = getSearchContext (context);
        MatchFragment[] frags = null;
        if (result != null) {
            frags = result.getFragments(key);
            if (frags != null) {
                Arrays.sort(frags, (a, b) -> {
                        int d = a.fragment.length() - b.fragment.length();
                        if (d == 0)
                            d = b.field.compareTo(a.field);
                        return d;
                    });
            }
        }
        return frags;
    }
    
    public static String getTargetTableHeader (String name, String field) {
        String order = request().getQueryString("order");
        String sort = "";
        if (order != null && field.equalsIgnoreCase(order.substring(1))) {
            char dir = order.charAt(0);
            if (dir == '^') { // ascending
                order = "$"+field;
                sort = "-asc";
            }
            else if (dir == '$') {
                order = "^"+field;
                sort = "-desc";
            }
            else {
                // default to descending
                order = "$"+field;
                sort = "-desc";
            }
        }
        else {
            // since novelty is the default..
            order = (order == null && field.equalsIgnoreCase("novelty")
                     ? "^":"$")+field;
        }
        String url = url ("order");
        if (url.indexOf('?') > 0) url += '&';
        else url += '?';
        
        return "<th><a class='loader' href='"+url+"order="+order+"'>"+name
            +"</a>&nbsp;<i class='fa fa-sort"+sort+"'></i></th>";
    }

    public static <T extends EntityModel> List<T> filter
        (SearchResult results, Filter<T> filter) {
        List<T> matches = new ArrayList<T>();
        for (Object obj : results.getMatches()) {
            T e = (T)obj;
            if (filter.accept(e)) {
                matches.add(e);
            }
        }
        return matches;
    }

    static JsonNode getKinases (SearchResult result) {
        ObjectMapper mapper = new ObjectMapper ();      
        ArrayNode node = mapper.createArrayNode();
        for (Target t : filter (result, new Filter<Target> () {
                public boolean accept (Target t) {
                    //Logger.debug(t.getName()+" \""+t.idgFamily+"\"");
                    return "kinase".equalsIgnoreCase(t.idgFamily);
                }
            })) {
            //Logger.debug("Kinase: "+t.getName());
            for (Keyword kw : t.synonyms) {
                if (UNIPROT_GENE.equalsIgnoreCase(kw.label)) {
                    ObjectNode n = mapper.createObjectNode();
                    n.put("name", kw.term);
                    n.put("tdl", t.idgTDL.toString());
                    node.add(n);
                    break;
                }
            }
        }
        Logger.info(node.size()+" kinase(s) fetched!");
        return node;
    }
    
    public static JsonNode _getKinases (final String q) throws Exception {
        final Map<String, String[]> query = getRequestQuery ();
        Logger.debug("** _getKinases: request");
        for (Map.Entry<String, String[]> me : query.entrySet()) {
            Logger.debug("["+me.getKey()+"]");
            String[] values = me.getValue();
            if (values != null) {
                for (String v : values)
                    Logger.debug(" "+v);
            }
        }
        
        String[] facets = query.get("facet");
        if (facets != null) {
            boolean hasFamily = false;
            for (String f : facets) {
                if (f.startsWith(IDG_FAMILY)) {
                    hasFamily = true;
                    break;
                }
            }
            
            if (!hasFamily) {
                // add kinase facet
                List<String> values = new ArrayList<String>();
                for (String f : facets)
                    values.add(f);
                values.add(IDG_FAMILY+"/Kinase");
                query.put("facet", values.toArray(new String[0]));
            }
            else {
                // leave it alone
            }
        }
        else {
            query.put("facet", new String[]{IDG_FAMILY+"/Kinase"});
        }
        List<String> args = new ArrayList<String>();
        facets = query.get("facet");
        if (facets != null) {
            for (String f : facets)
                args.add(f);
            Collections.sort(args);
        }
        if (q != null) args.add(q);
                
        final String key = "kinases/"+Util.sha1(args.toArray(new String[0]));
        final SearchResult result =
            getOrElse (key, new Callable<SearchResult> () {
                    public SearchResult call () throws Exception {
                        int total = TargetFactory.finder.findRowCount();        
                        return getSearchResult (Target.class, q, total, query);
                    }
                });
        for (String s : args) {
            Logger.debug(" ++ "+s);
        }
        Logger.debug("_getKinases: q="+q+" key="+key+" result="+result+" size="+result.size());
        
        if (result.finished()) {
            return getOrElse (key+"/json", new Callable<JsonNode>() {
                    public JsonNode call () throws Exception {
                        return getKinases (result);
                    }
                });
        }

        // not cached because it hasn't finished fetching..
        return getKinases (result);
    }

    public static Result getKinases (String q) {
        try {
            return ok (_getKinases (q));
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    public static Result _getBatchTargets (String q) throws Exception {
        Payload payload = createPayloadIfAbsent (q, Target.class.getName());
        if (payload != null) {
            response().setHeader(ETAG, payload.id.toString());
            q = PayloadFactory.getString(payload.id);
        }

        Map<Long, Target> found = new HashMap<Long, Target>();
        for (String tok : q.split("[\\s;,\n\t]")) {
            List<Target> targets = TargetFactory.finder.where().eq
                ("synonyms.term", tok).findList();
            for (Target t : targets)
                found.put(t.id, t);
        }
        Logger.debug("_resolve: "+found.size()+" unique entries resolved!");

        int top = found.size(), skip = 0, fdim = 100;
        SearchResult result = _textIndexer.search
            (new SearchOptions (Target.class, top, skip, fdim),
             null, found.values());
        
        return ok (SearchFactory.convertToSearchResultJson
                   (Target.class, q, top, skip, result));
    }

    public static Result getBatchTargets (final String q) {
        try {
            final String key = Util.sha1(q);
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        return _getBatchTargets (q);
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    @BodyParser.Of(value = BodyParser.Text.class, maxLength = 100000)
    public static Result resolveBatchTargets () {
        String type = request().getHeader("Content-Type");
        if (!"text/plain".equals(type))
            return badRequest ("Invalid Content-Type: "+type
                               +"; only \"text/plain\" is allowed!");
        
        if (request().body().isMaxSizeExceeded())
            return badRequest ("Input is too large!");

        return getBatchTargets (request().body().asText());
    }

    @BodyParser.Of(value = BodyParser.Text.class, maxLength = 100000)
    public static Result resolveBatch (String kind, String format) {
        String type = request().getHeader("Content-Type");
        if (!"text/plain".equals(type))
            return badRequest ("Invalid Content-Type: "+type
                               +"; only \"text/plain\" is allowed!");
        
        if (request().body().isMaxSizeExceeded())
            return badRequest ("Input is too large!");

        try {
            String text = request().body().asText();
            return resolve (text, format, kind);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    public static ArrayNode _resolveAsJson (String q, String kind) {
        ObjectMapper mapper = EntityFactory.getEntityMapper();
        ArrayNode nodes = mapper.createArrayNode();
        if (Target.class.getName().equalsIgnoreCase(kind)) {
            Map<Long, Target> found = new HashMap<Long, Target>();
            for (String tok : q.split("[\\s;,\n\t]")) {
                List<Target> targets = TargetFactory.finder.where().eq
                    ("synonyms.term", tok).findList();
                for (Target t : targets)
                    found.put(t.id, t);
            }
            Logger.debug("_resolve: "+found.size()+" unique entries resolved!");
            for (Target t : found.values()) {
                nodes.add(mapper.valueToTree(t));
            }
        }
        else if (Ligand.class.getName().equalsIgnoreCase(kind)) {
        }
        else if (Disease.class.getName().equalsIgnoreCase(kind)) {
        }
        return nodes;
    }
    
    public static ArrayNode resolveAsJson (final String q, final String kind)
        throws Exception {
        String view = request().getQueryString("view");
        final String key = "resolve/"+Util.sha1(q)+"/"+kind
            +(view != null ? "/"+view : "");
        return getOrElse (key, new Callable<ArrayNode>() {
                public ArrayNode call () throws Exception {
                    return _resolveAsJson (q, kind);
                }
            });
    }

    static class ResolveChunker {
        final public String text;
        final public String kind;
        final public String sep;
        final public PipedInputStream pis;
        
        ResolveChunker (String text, String kind, String sep,
                        PipedInputStream pis) {
            this.text = text;
            this.kind = kind;
            this.sep = sep;
            this.pis = pis;
        }
    }
    
    static class ResolveChunkActor extends UntypedActor {
        @Override
        public void onReceive (Object obj) {
            if (obj instanceof ResolveChunker) {
                ResolveChunker chunker = (ResolveChunker)obj;
                try {
                    PrintStream ps = new PrintStream
                        (new PipedOutputStream (chunker.pis));
                    resolveTarget (chunker.text, chunker.sep, ps);
                    ps.close();
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    static void resolveTarget (String text, String sep, PrintStream ps)
        throws Exception {
        DefaultTokenizer tokenizer = new DefaultTokenizer ();
        ps.println("ID"+sep+"Family"+sep+"TDL");
        for (Enumeration<String> en = tokenizer.tokenize(text);
             en.hasMoreElements();) {
            final String token = en.nextElement();
            
            Target t = getOrElse (Util.sha1(token)+"/resolver/target",
                                  new Callable<Target> () {
                    public Target call () throws Exception {
                        List<Target> targets = TargetFactory.finder
                        .select("idgFamily,idgTDL,novelty,antibodyCount,"
                                +"pubmedCount,patentCount,grantCount,"
                                +"r01Count,grantTotalCost")
                        .where().eq("synonyms.term", token)
                        .findList();
                        if (!targets.isEmpty())
                            return targets.iterator().next();
                        return null;
                    }
                });
            
            if (t != null) {
                String fam = t.idgFamily;
                if ("unknown".equalsIgnoreCase(fam))
                    fam = "Non-IDG";
                ps.println(token+sep+fam+sep+t.idgTDL);
            }
        }
    }
    
    public static InputStream resolveAsChunk
        (final String q, final String kind, final String sep)
        throws Exception {
        final PipedInputStream pis = new PipedInputStream ();
        ActorRef actor = Akka.system().actorOf
            (Props.create(ResolveChunkActor.class));
        actor.tell(new ResolveChunker (q, kind, sep, pis), ActorRef.noSender());
        return pis;
    }

    static Payload createPayloadIfAbsent (String q, String kind) {
        Payload payload = null;
        try {
            payload = PayloadFactory.getPayload(UUID.fromString(q));
            if (payload != null) {
                if (kind == null) {
                    // get kind from the
                    for (Value v : payload.properties) {
                        if (IDG_RESOLVER.equalsIgnoreCase(v.label)) {
                            kind = ((Keyword)v).term;
                            break;
                        }
                    }

                    if (kind == null) {
                        kind = Target.class.getName();                  
                        Logger.warn("Payload "+payload.id+" has no "
                                    +IDG_RESOLVER+" property; assuming "
                                    +kind);
                    }
                }
            }
        }
        catch (IllegalArgumentException ex) {
            // not a payload id
            Logger.warn("resolver: input is not a payload; treat as-is!");
            // first save the payload
            try {
                payload = _payloader.createPayload
                    ("Resolver Query", "text/plain", q);
                if (payload != null) {
                    payload.addIfAbsent
                        (KeywordFactory.registerIfAbsent
                         (IDG_RESOLVER, kind, null));
                    payload.update();
                    Logger.debug("resolver: kind="+kind
                                 +" => payload "+payload.id);
                }
            }
            catch (Exception exx) {
                exx.printStackTrace();
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        
        return payload;
    }
    
    public static Result resolve (String q, String format, String kind) {
        Logger.debug("resolve: q="+(q.length() < 40 ? q : q.substring(0,40))
                     +" kind="+kind+" format="+format);

        if ("target".equalsIgnoreCase(kind))
            kind = Target.class.getName();
        else if ("ligand".equalsIgnoreCase(kind))
            kind = Ligand.class.getName();
        else if ("disease".equalsIgnoreCase(kind))
            kind = Disease.class.getName();

        Payload payload = createPayloadIfAbsent (q, kind);
        if (payload != null) {
            response().setHeader(ETAG, payload.id.toString());
            q = PayloadFactory.getString(payload.id);
        }

        try {
            if ("json".equalsIgnoreCase(format)) {
                ArrayNode nodes = resolveAsJson (q, kind);
                if (nodes.size() > 0)
                    return ok (nodes);
            }
            else if ("csv".equalsIgnoreCase(format)) {
                return ok (resolveAsChunk (q, kind, ","));
            }
            else if ("txt".equalsIgnoreCase(format)) {
                return ok (resolveAsChunk (q, kind, "\t"));
            }
            return badRequest ("Unknown resolve format: "+format);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex.getMessage());
        }
    }
    
    static Payload getBatchPayload () throws Exception {
        Payload payload = null;
        Map<String, String[]> params = request().body().asFormUrlEncoded();
        String[] values = params.get("q");
        if (values != null && values.length > 0) {
            String content = values[0];
            payload = _payloader.createPayload
                ("Resolver Query", "text/plain", content);
            values = params.get("kind");
            String kind = null;
            if (values != null) {
                kind = values[0];
                if (Target.class.getName().equalsIgnoreCase(kind)
                    || Ligand.class.getName().equalsIgnoreCase(kind)
                    || Disease.class.getName().equalsIgnoreCase(kind)) {
                    payload.addIfAbsent
                        (KeywordFactory.registerIfAbsent
                                (IDG_RESOLVER, kind, null));
                    payload.update();
                }
                else {
                    Logger.debug("Bogus kind: "+kind);
                }
            }
            Logger.debug("batchResolver: kind="+kind
                         +" => payload "+payload.id);
        }
        return payload;
    }
    
    @BodyParser.Of(value = BodyParser.FormUrlEncoded.class, 
                   maxLength = 100000)
    public static Result batch () {
        if (request().body().isMaxSizeExceeded()) {
            return badRequest ("Input is too large!");
        }

        try {
            Payload payload = getBatchPayload ();
            Call call = routes.IDGApp.targets(payload.id.toString(), 10, 1);
            return redirect (call.url()+"&type=batch");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    static class PayloadTokenizer extends DefaultTokenizer {
        @Override
        public Enumeration<String> tokenize (String q) {
            String payload = PayloadFactory.getString(q);
            if (payload != null) {
                return super.tokenize(payload);
            }
            return Collections.emptyEnumeration();
        }
    }

    public static Result batchSearch (final String q,
                                      final int rows, final int page) {
        try {
            SearchResultContext context = batch
                (q, rows, new PayloadTokenizer (),
                 new SearchResultProcessor<String> () {
                        Map<Long, Target> found = new HashMap<Long, Target>();
                        protected Object instrument (String token)
                            throws Exception {
                            // assume for now we're only batch search on
                            // targets.. will need to generalize it to other
                            // entities.
                            List<Target> targets =
                                TargetFactory.finder.where().eq
                                ("synonyms.term", token).findList();
                            if (!targets.isEmpty()) {
                                Target t = targets.iterator().next();
                                if (!found.containsKey(t.id)) {
                                    found.put(t.id, t);
                                    return t;
                                }
                                // dup.. so ignore..
                            }
                            return null;
                        }
                    });


            return App.fetchResult
                (context, rows, page, new DefaultResultRenderer<Target> () {
                        public Content getContent
                            (SearchResultContext context,
                             int page, int rows,
                             int total, int[] pages,
                             List<Facet> facets,
                             List<Target> targets) {
                            return ix.idg.views.html.targets.render
                                (page, rows, total,
                                 pages, decorate
                                 (Target.class,
                                  filter (facets, TARGET_FACETS)),
                                 targets, context.getId());
                        }
                    });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't perform batch search", ex);
        }
        return internalServerError
            (ix.idg.views.html.error.render
             (500, "Unable to perform batch search: "+q));
    }

    static final String[] TISSUES  = new String[] {
        "GTEx Tissue Specificity Index",
        "HPM Protein Tissue Specificity Index",
        "HPA RNA Tissue Specificity Index",
        //"HPA Protein Tissue Specificity Index"
    };
    static JsonNode _targetTissue (final String name) throws Exception {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode nodes = mapper.createArrayNode();

        List<Target> targets = TargetResult.find(name);
        for (Target tar: targets) {
            ArrayNode axes = mapper.createArrayNode();
            for (String t: TISSUES) {
                ObjectNode n = mapper.createObjectNode();
                n.put("axis", t.replaceAll("Tissue Specificity Index",""));
                Value p = tar.getProperty(t);
                if (p != null) {
                    if (p instanceof VNum)
                        n.put("value", ((VNum)p).numval);
                    else if (p instanceof VInt)
                        n.put("value", ((VInt)p).intval);
                    else {
                        Logger.warn("Unknown tissue index property: "+p);
                        n.put("value", 0);
                    }
                }
                else {
                    n.put("value", 0);
                }
                axes.add(n);
            }

            ObjectNode node = mapper.createObjectNode();
            node.put("className", name);
            node.put("axes", axes);
            nodes.add(node);
        }
        
        return nodes;
    }
    
    public static Result targetTissue (final String name) {
        try {
            final String key = Util.sha1(name);
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        return ok (_targetTissue (name));
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    public static Result getHomunculusForTarget (String id, String source) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null)
                return ok (ExpressionApp.getHomunculusXml(t, source))
                    .as("image/svg+xml");
            return notFound ("Can't find target for \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

    public static Result structure (final String id,
                                    final String format, final int size,
                                    final String context) {
        //Logger.debug("Fetching structure");
        String atomMap = "";
        if (context != null) {
            int[] amap = (int[])IxCache.get("AtomMaps/"+context+"/"+id);
            //Logger.debug("AtomMaps/"+context+" => "+amap);
            if (amap != null && amap.length > 0) {
                StringBuilder sb = new StringBuilder ();
                sb.append(amap[0]);
                for (int i = 1; i < amap.length; ++i)
                    sb.append(","+amap[i]);
                atomMap = sb.toString();
            }
            else {
                atomMap = context;
            }
        }
        return App.structure(id, format, size, atomMap);        
    }

    public static List<String> getOmimPhenotypes(Target target) {
       List<String> phenos = new ArrayList<>();
        for (Value value : target.properties) {
            if (OMIM_TERM.equals(value.label)) {
                Object term = value.getValue();
                phenos.add(term.toString());
            }
        }
        return phenos;
    }

    public static List<GeneRIF> getGeneRIFs (Target target) {
        List<GeneRIF> generifs = new ArrayList<GeneRIF>();
        for (XRef ref : target.links) {
            if (Text.class.getName().equals(ref.kind)) {
                try {
                    Text text = (Text)ref.deRef();
                    if (IDG_GENERIF.equals(text.label)) {
                        for (Value val : ref.properties) {
                            if (PUBMED_ID.equals(val.label)) {
                                VInt pmid = (VInt)val;
                                generifs.add(new GeneRIF
                                             (pmid.intval, text.getValue()));
                            }
                        }
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.debug("Can't dereference "+ref.kind+":"+ref.refid);
                }
            }
        }
        
        Collections.sort(generifs);
        return generifs;
    }

    public static List<Assay> getAssays (EntityModel e) {
        List<Assay> assays = new ArrayList<Assay>();
        for (XRef ref : e.getLinks()) {
            if (Assay.class.getName().equals(ref.kind)) {
                try {
                    Assay a = (Assay)ref.deRef();
                    assays.add(a);
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.error("Can't resolve XRef "
                                 +ref.kind+":"+ref.refid, ex);
                }
            }
        }
        Collections.sort(assays, new Comparator<Assay> () {
                public int compare (Assay a1, Assay a2) {
                    VInt aid1 = (VInt)a1.getProperty(MLP_ASSAY_AID);
                    VInt aid2 = (VInt)a2.getProperty(MLP_ASSAY_AID);
                    if (aid1 != null && aid2 != null) {
                        return aid1.intval.compareTo(aid2.intval);
                    }
                    if (aid1 != null) return -1;
                    if (aid2 != null) return 1;
                    return 0;
                }
            });
        
        return assays;
    }

    public static List<Long> getPMIDs (Target target) {
        List<Long> pmids = new ArrayList<Long>();
        for (Value val : target.properties) {
            if (PUBMED_ID.equals(val.label)) {
                pmids.add(((VInt)val).intval);
            }
        }
        return pmids;
    }

    public static JsonNode getPatents (Target target) {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode nodes = mapper.createArrayNode();
        for (XRef ref : target.links) {
            if (Timeline.class.getName().equals(ref.kind)) {
                try {
                    Timeline tl = (Timeline)ref.deRef();
                    if ("Patent Count".equals(tl.name)) {
                        for (Event e : tl.events) {
                            ObjectNode n = mapper.createObjectNode();
                            n.put("year", e.start.toString());
                            n.put("count", e.end.toString());
                            nodes.add(n);
                        }
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.error("Can't dereference link "
                                 +ref.kind+":"+ref.refid);
                }
            }
        }
        return nodes;
    }

    public static Result getPatentsForTarget (String id) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                return ok (getPatents (t));
            }
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }        
    }
    
    public static JsonNode getPubTator (Target target) {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode nodes = mapper.createArrayNode();
        for (XRef ref : target.links) {
            if (Timeline.class.getName().equals(ref.kind)) {
                try {
                    Timeline tl = (Timeline)ref.deRef();
                    if ("PubTator".equals(tl.name)) {
                        // make sure the events are sorted
                        Collections.sort(tl.events, new Comparator<Event> () {
                                public int compare (Event e1, Event e2) {
                                    return (int)(e1.start - e2.start);
                                }
                            });
                        for (Event e : tl.events) {
                            ObjectNode n = null;
                            for (Value val : e.properties) {
                                if ("Score".equals(val.label)) {
                                    n = mapper.createObjectNode();
                                    n.put("year", e.start.toString());
                                    n.put("score", (Double)val.getValue());
                                }
                            }

                            if (n != null)
                                nodes.add(n);
                        }
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.error("Can't dereference link "
                                 +ref.kind+":"+ref.refid);
                }
            }
        }
        return nodes;
    }

    public static Result getPubTatorForTarget (String id) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                return ok (getPubTator (t));
            }
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }        
    }

    public static String getPatentSparkline (Target target) {
        ArrayList<Long> ent = new ArrayList<Long>();
        for (XRef ref : target.links) {
            if (Timeline.class.getName().equals(ref.kind)) {
                try {
                    Timeline tl = (Timeline)ref.deRef();
                    if ("Patent Count".equals(tl.name)) {
                        for (Event e : tl.events) {
                            ent.add(e.end);
                        }
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.error("Can't dereference link "
                            +ref.kind+":"+ref.refid);
                }
            }
        }

        //strip the brackets '[', ']' for sparkline
        String res = StringUtils.join(ent, ",");
        return res;
    }

    public static JsonNode getPubMedScore (Target target) {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode nodes = mapper.createArrayNode();
        for (XRef ref : target.links) {
            if (Timeline.class.getName().equals(ref.kind)) {
                try {
                    Timeline tl = (Timeline)ref.deRef();
                    if ("PubMed Score".equals(tl.name)) {
                        for (Event e : tl.events) {
                            double val = e.end/1000.;
                            ObjectNode n = mapper.createObjectNode();
                            n.put("year", String.valueOf(e.start));
                            n.put("score", val);
                            nodes.add(n);
                        }
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.error("Can't dereference link "
                            +ref.kind+":"+ref.refid);
                }
            }
        }

        return nodes;
    }

    public static Result getPubMedScoreForTarget (final String id) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                return ok (getPubMedScore (t));
            }
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }        
    }
    
    
    public static String getPubMedScoreSparkline (Target target) {
        StringBuilder sb = new StringBuilder ();
        for (XRef ref : target.links) {
            if (Timeline.class.getName().equals(ref.kind)) {
                try {
                    Timeline tl = (Timeline)ref.deRef();
                    if ("PubMed Score".equals(tl.name)) {
                        for (Event e : tl.events) {
                            if (sb.length() > 0)
                                sb.append(",");
                            sb.append(String.format("%1$d:%2$.3f", e.start,
                                                    e.end/1000.));
                        }
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.error("Can't dereference link "
                            +ref.kind+":"+ref.refid);
                }
            }
        }
        
        return sb.toString();
    }

    public static VNum getActivity (Target target, Ligand ligand) {
        for (XRef ref : target.getLinks()) {
            if (ref.kind.equals(ligand.getClass().getName())
                && ref.refid.equals(ligand.id.toString())) {
                for (Value v : ref.properties) {
                    // will we have multiple activity for a target?
                    if (v instanceof VNum)
                        return (VNum)v;
                }
            }
        }
        return null;
    }

    public static List<Publication> getPublications (final Target target)
        throws Exception {
        List<Publication> pubs = new ArrayList<Publication>();
        
        final String key = "targets/"+target.id+"/publications";
        Predicate pred = getOrElse (key, new Callable<Predicate> () {
                public Predicate call () throws Exception {
                    List<Predicate> preds = PredicateFactory.finder.where
                    (Expr.and(Expr.eq("subject.refid", target.id),
                              Expr.eq("predicate", IDG_PUBLICATIONS)))
                    .findList();
                    return preds.isEmpty() ? null : preds.iterator().next();
                }
            });

        if (pred != null) {
            for (XRef ref : pred.objects) {
                try {
                    pubs.add((Publication)ref.deRef());
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.trace("Can't retrieve XRef "+ref.kind+":"+ref.refid, ex);
                }
            }
        }
        return pubs;
    }

    public static List<Target> getPPI (Target target) {
        return getTargetsByPredicate (target, TARGET_PPI);
    }

    public static List<Target> getTargetsByPredicate
        (final Target target, final String predicate) {
        try {
            final String key = "targets/"+target.id+"/predicate/"+predicate;
            return getOrElse (key, new Callable<List<Target>> () {
                    public List<Target> call () throws Exception {
                        List<Predicate> preds = PredicateFactory.finder.where
                            (Expr.and(Expr.eq("subject.refid", target.id.toString()),
                                      Expr.eq("predicate", predicate)))
                            .findList();
                        List<Target> targets = new ArrayList<Target>();
                        for (Predicate p : preds) {
                            for (XRef ref : p.objects) {
                                try {
                                    // should use cache here
                                    targets.add((Target)ref.deRef());
                                }
                                catch (Exception ex) {
                                    ex.printStackTrace();
                                    Logger.trace("Can't dereference XRef "+
                                                 ref.kind+":"+ref.refid, ex);
                                }
                            }

                            Logger.debug(key+" => "+targets.size()+" targets!");
                        }
                        return targets;
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.trace("Can't retrieve "+predicate+" targets for target "
                         +target.id, ex);
        }
        return new ArrayList<Target>();
    }

    public static List<Target> getTargetsByPMIDOld (final long pmid)
        throws Exception {
        final String key = "publications/"+pmid+"/targets";
        return getOrElse (key, new Callable<List<Target>> () {
                public List<Target> call () throws Exception {
                    final Publication pub = PublicationFactory.byPMID(pmid);
                    List<Target> targets = new ArrayList<Target>();                 
                    if (pub != null) {
                        List<Predicate> preds = PredicateFactory.finder.where
                            (Expr.and(Expr.eq("predicate", IDG_PUBLICATIONS),
                                      Expr.eq("objects.refid", pub.id)))
                            .findList();

                        for (Predicate pred : preds) {
                            try {
                                targets.add((Target)pred.subject.deRef());
                            }
                            catch (Exception ex) {
                                ex.printStackTrace();
                                Logger.trace("Can't retrieve XRef "
                                             +pred.subject.kind+":"+pred.subject.refid, ex);
                            }
                        }
                    }
                    return targets;
                }
            });
    }

    public static List<Target> getTargetsByPMID (final long pmid) throws Exception {
        return TargetFactory.finder.where()
            .eq("publications.pmid", pmid).findList();
    }

    public static Result _ligandsForTarget(String name, String group) throws Exception {
        if (group == null)
            group = "chembl";
        if (!group.equals("chembl") && !group.equals("drugs"))
            return _badRequest("Invalid ligand group specified");

        List<Target> targets = TargetResult.find(name);
        if (targets.size() == 0) {
            return _notFound ("Unknown target: "+name);
        }

        if (targets.size() > 1) {
            Logger.debug("** \""+name+"\" resolves to "+targets.size()+" targets!");
        }
        Target t = targets.iterator().next();

        List<Ligand> ligands = null;
        if (group.equals("chembl"))
            ligands = getChemblLigands(t);
        else if (group.equals("drugs"))
            ligands = getDrugLigands(t);
        if (ligands == null || ligands.size() == 0)
            return _notFound("No ligands for "+IDGApp.getId(t));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MolExporter exporter = new MolExporter(baos, "sdf");
        for (Ligand l : ligands) {
            Structure s = getStructure(l);
            MolHandler molh = new MolHandler(s.molfile);
            Molecule mol = molh.getMolecule();

            mol.setProperty("Name", l.getName());
            mol.setProperty("Target_Uniprot", IDGApp.getId(t));
            for (Value v : l.getProperties()) {
                if (Commons.ChEMBL_INCHI_KEY.equals(v.label)) {
                    String val = (String) v.getValue();
                    if (val != null)
                        mol.setProperty(Commons.ChEMBL_INCHI_KEY, val);
                } else if (Commons.ChEMBL_SMILES.equals(v.label)) {
                    String val = (String) v.getValue();
                    if (val != null)
                        mol.setProperty(Commons.ChEMBL_SMILES, val);
                } else if (Commons.PUBMED_ID.equals(v.label)) {
                    Object val = v.getValue();
                    if (val != null)
                        mol.setProperty(Commons.PUBMED_ID, val.toString());
                }
            }
            exporter.write(mol);
        }
        exporter.close();
        byte[] contents = baos.toByteArray();
        response().setHeader("Content-Disposition", "attachment;filename="
                             +IDGApp.getId(t)+"-"+group+".sdf");
        return ok(contents).as("chemical/x-mdl-molfile");
    }

    public static Result _publicationsForTarget (String name, int top, int skip)
        throws Exception {
        Target t = TargetResult.findUnique(name);
        if (t != null) {
            List<Publication> pubs =
                Util.getPage(getPublications (t), top, skip);
            
            ObjectMapper mapper = EntityFactory.getEntityMapper();
            return ok ((JsonNode)mapper.valueToTree(pubs));
        }
        return notFound ("Can't find target for \""+name+"\"!");
    }

    public static Result ligandsForTarget (final String name,
                                           final String group) {
        try {
            final String key = "targets/"+name+"/ligands/"+group;
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        return _ligandsForTarget (name, group);
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

    public static Result publicationsForTarget
        (final String name, final int top, final int skip) {
        try {
            final String key = "targets/"+name+"/pubs/"+top+"/"+skip;
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        return _publicationsForTarget (name, top, skip);
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

    public static Result getLigandsForTarget
        (final String id, final int top, final int skip) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                List<Ligand> ligands =
                    Util.getPage(getChemblLigands (t), top, skip);
                
                ObjectMapper mapper = EntityFactory.getEntityMapper();
                return ok ((JsonNode)mapper.valueToTree(ligands));
            }
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

    public static Result getLigandsCountForTarget (final String id) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                List<Ligand> ligands = getChemblLigands (t);
                return ok (String.valueOf(ligands.size()));
            }
            return notFound ("Can't find target \""+id+"\"!");            
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

    public static Result getDrugsForTarget
        (final String id, final int top, final int skip) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                List<Ligand> ligands =
                    Util.getPage(getDrugLigands (t), top, skip);
                
                ObjectMapper mapper = EntityFactory.getEntityMapper();
                return ok ((JsonNode)mapper.valueToTree(ligands));
            }
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }        
    }

    public static Result getDrugsCountForTarget (final String id) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                List<Ligand> drugs = getDrugLigands (t);
                return ok (String.valueOf(drugs.size()));
            }
            return notFound ("Can't find target \""+id+"\"!");            
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }
    
    public static Result getDiseasesForTarget
        (final String id, final int top, final int skip) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                List<Disease> diseases =
                    Util.getPage(getLinkedObjects(t, Disease.class), top, skip);
                
                ObjectMapper mapper = EntityFactory.getEntityMapper();
                return ok ((JsonNode)mapper.valueToTree(diseases));
            }
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }        
    }

    public static Result getDiseasesCountForTarget (final String id) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                List<Disease> diseases = getLinkedObjects(t, Disease.class);
                return ok (String.valueOf(diseases.size()));
            }
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }        
    }
    
    public static Result getGeneRifsForTarget
        (final String id, final int top, final int skip) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                List<GeneRIF> generifs =
                    Util.getPage(getGeneRIFs (t), top, skip);
                
                ObjectMapper mapper = EntityFactory.getEntityMapper();
                return ok ((JsonNode)mapper.valueToTree(generifs));
            }
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

    public static Result getGeneRifsCountForTarget (final String id) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                List<GeneRIF> generifs = getGeneRIFs (t);
                return ok (String.valueOf(generifs.size()));
            }
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }
    
    public static Result getBreadcrumbForTarget (final String id) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null) {
                List<Keyword> breadcrumb = getBreadcrumb (t);
                
                ObjectMapper mapper = EntityFactory.getEntityMapper();
                return ok ((JsonNode)mapper.valueToTree(breadcrumb));
            }
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }
    
    public static Result getFieldForTarget (final String id,
                                            final String field) {
        try {
            Target t = TargetResult.findUnique(id);
            if (t != null)
                return TargetFactory.getField(t, field);
            return notFound ("Can't find target \""+id+"\"!");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

    public static Result targetsForPublication
        (final long pmid, final int rows, final int page) {
        try {
            final String key = "targets/"+pmid+"/pmid";
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        SearchResultProcessor<Target> processor =
                            new SearchResultProcessor<Target>() {
                            protected Object instrument (Target t)
                                throws Exception {
                                return t;
                            }
                        };

                        List<Target> targets = getTargetsByPMID (pmid);
                        processor.setResults
                            (targets.size(), Collections.enumeration(targets));
                        
                        return App.fetchResult
                            (processor.getContext(), rows, page,
                             new DefaultResultRenderer<Target> () {
                                public Content getContent
                                    (SearchResultContext context,
                                     int page, int rows,
                                     int total, int[] pages,
                                     List<Facet> facets,
                                     List<Target> targets) {
                                    return ix.idg.views.html.targets.render
                                        (page, rows, total,
                                         pages, decorate
                                         (Target.class,
                                          filter (facets, TARGET_FACETS)),
                                         targets, context.getId());
                                }
                            });
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    static Result _getTDLData (final String q, String format)
        throws Exception {
        int total = TargetFactory.finder.findRowCount();
        SearchResult result = getSearchResult (Target.class, q, total);
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream ();
        PrintStream ps = new PrintStream (bos);
        ps.println("family,tdl,novelty,antibody,monoclonal,"
                   +"pubmed,jensen,patent,grant,r01,cost");
        List matches = result.getMatches();
        for (int i = 0; i < result.size(); ++i) {
            Object obj = matches.get(i);
            if (obj instanceof Target) {
                Target t = (Target)obj;
                String fam = t.idgFamily;
                if ("unknown".equalsIgnoreCase(fam))
                    fam = "Non-IDG";
                ps.println(fam+","+t.idgTDL+","+format (t.novelty)+","
                           +format (t.antibodyCount)+","
                           +format (t.monoclonalCount)+","
                           +format (t.pubmedCount)+","
                           +format (t.jensenScore)+","
                           +format (t.patentCount)+","
                           +format (t.grantCount)+","
                           +format (t.r01Count)+","
                           +format (t.grantTotalCost));
            }
        }
        return ok (new String (bos.toByteArray()));
    }

    public static Result getTDLData (final String q, final String format) {
        final String key = "tdldata/"+format+"/"
            +signature (q, request().queryString());
        try {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        return _getTDLData (q, format);
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    public static Result compareTargets(final String q) throws Exception {
        if (q == null || q.trim().equals(""))
            return _badRequest("Must specify comma separated list of Uniprot IDs");
        final String key = "targets/compare/" + q + "/" + Util.sha1(request());
        return getOrElse(key, new Callable<Result>() {
            public Result call() throws Exception {
                String[] ids = q.split(",");
                List<Target> targets = new ArrayList<>();
                for (String id : ids) {
                    targets.addAll(TargetFactory.finder.where().eq("synonyms.term", id).findList());
                }
                return ok(ix.idg.views.html.targetcompare.render(targets));
            }
        });
    }

    public static Result compareTargets2 (final String q) {
        if (q == null || q.trim().equals(""))
            return _badRequest
                ("Must specify comma separated list of Uniprot IDs");
        try {
            final String key = "targets/compare/"
                + q + "/" + Util.sha1(request());
            
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        return _compareTargets (q);
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    static Result _compareTargets (final String q) throws Exception {
        String[] ids = q.split(",");
        List<Target> all = new ArrayList<>();
        for (String id : ids) {
            all.addAll(TargetResult.find(id));
        }
        int rows = (all.size()+1)/2; // a row has at most 2 targets
        Target[][] targets = new Target[rows][2];
        for (int i = 0; i < all.size(); ++i) {
            targets[i/2][i%2] = all.get(i);
        }

        return ok (ix.idg.views.html.targetcompare2.render(targets));
    }

    public static Result targetdescriptor (final String name) {
        try {
            final String key = "targets/descriptor/"+name;
            CachableContent content = getOrElse_
                (key, new Callable<CachableContent>() {
                        public CachableContent call () throws Exception {
                            SearchFactory.clearCaches
                            (Target.class, COLLECTION, IDG_DEVELOPMENT);
                            return CachableContent.wrap
                            (ix.idg.views.html.targetdescriptor.render
                             (DictVector.getInstance(Target.class, name),
                              DictVectorConditional.getInstance
                              (Target.class, name, IDG_DEVELOPMENT)));
                        }
                    });
            
            return content != null ? content.ok()
                : _badRequest ("Not a valid target descriptor: "+name);
        }
        catch (Exception ex) {
            Logger.error("Can't generate target descriptor content", ex);
            return _internalServerError (ex);
        }
    }

    public static Result sketcher (String s) {
        return ok (ix.idg.views.html.sketcher.render(s));
    }

    @Security.Authenticated(Secured.class)
    @BodyParser.Of(value = BodyParser.MultipartFormData.class,
                   maxLength = 1024 * 1024)    
    public static Result createCollection () {
        MultipartFormData form = request().body().asMultipartFormData();
        MultipartFormData.FilePart part = form.getFile("file");
        if (part != null) {
            Logger.debug("createCollection => "+part.getFilename());
            File file = part.getFile();
            try {
                ObjectMapper mapper = new ObjectMapper ();
                JsonNode json = mapper.readTree(file);
                CRUD.addCollection(json);
                
                return redirect (routes.IDGApp.index());
            }
            catch (Exception ex) {
                Logger.trace("Can't create collection from file "
                             +part.getFilename(), ex);
                return _internalServerError (ex);
            }
        }
        
        return badRequest ("No collection file!");
    }

    @Security.Authenticated(Secured.class)    
    public static Result editCollection () {
        return ok (ix.idg.views.html.editcollection.render());
    }

    @Security.Authenticated(Secured.class)    
    public static Result delCollection (String name) {
        try {
            if (CRUD.delCollection(name)) {
                return ok(routes.IDGApp.targets(null, 10, 1)+"?refresh=y");
            }
            
            return ok (routes.IDGApp.editCollection().url());
        }
        catch (Exception ex) {
            Logger.trace("Can't delete collection '"+name+"'", ex);
            return _internalServerError (ex);
        }
    }
    
    public static String[] getDescriptions (Target t) {
        List<String> desc = new ArrayList<String>();
        if (t.description != null && t.description.length() > 0) {
            desc.add(t.description);
        }
        
        Value val = t.getProperty("NCBI Gene Summary");
        if (val != null && val.getValue() != null) {
            String s = val.getValue().toString();
            if (s.length() > 0)
                desc.add(s);
        }
        
        return desc.toArray(new String[0]);
    }

    public static Result targetSimilarityForm () {
        return ok (ix.idg.views.html.targetsimilarity.render(null, null));
    }

    static final int TARGET_COUNT = TargetFactory.finder.findRowCount();
    @BodyParser.Of(value = BodyParser.FormUrlEncoded.class,
                   maxLength = 20000)
    public static Result targetSimilarity () {
        Map<String, String[]> params = request().body().asFormUrlEncoded();
        String action = params.get("action")[0];
        Logger.debug("action="+action);
        
        try {
            if ("random".equalsIgnoreCase(action)) {
                long t1 = Util.rand(1, TARGET_COUNT);
                long t2 = Util.rand(1, TARGET_COUNT);
                Target target1 = TargetFactory.finder.byId(t1);
                Target target2 = TargetFactory.finder.byId(t2);
                return ok (ix.idg.views.html.targetsimilarity.render
                           (target1, target2));
            }
            else {
                String target1 = params.get("target1")[0].trim();
                String target2 = params.get("target2")[0].trim();
                if (target1.length() == 0 || target2.length() == 0)
                    return _badRequest
                        ("One or both specified target is invalid!");
                
                
                List<Target> res1 = TargetResult.find(target1);
                if (res1.isEmpty())
                    return _badRequest
                        ("Unable to resolve target '"+target1+"'");
                
                List<Target> res2 = TargetResult.find(target2);
                if (res2.isEmpty())
                    return _badRequest
                        ("Unable to resolve target '"+target2+"'");
                
                return ok (ix.idg.views.html.targetsimilarity.render
                           (res1.get(0), res2.get(0)));
            }
        }
        catch (Exception ex) {
            return _internalServerError (ex);
        }
    }

    public static JsonNode getSampleTermValues (int size) {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode json = mapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        int iter = 0;
        
        Random rand = new Random ();
        do {
            int j = rand.nextInt(3);
            TermVectors tvs = null;
            String url = null;
            switch (j) {
            case 0:
                {  String facet = ALL_TARGET_FACETS
                        [rand.nextInt(ALL_TARGET_FACETS.length)];
                    tvs = SearchFactory.getTermVectors(Target.class, facet);
                    url = Global.getHost()+routes.IDGApp.targets(null,10,1);
                }
                break;
                
            case 1:
                { String facet = LIGAND_FACETS
                        [rand.nextInt(LIGAND_FACETS.length)];
                    tvs = SearchFactory.getTermVectors(Ligand.class, facet);
                    url = Global.getHost()+routes.IDGApp.ligands(null,20,1);
                }
                break;
                
            case 2:
                { String facet = DISEASE_FACETS
                        [rand.nextInt(DISEASE_FACETS.length)];
                    tvs = SearchFactory.getTermVectors(Disease.class, facet);
                    url = Global.getHost()+routes.IDGApp.diseases(null,10,1);
                }
            }

            if (tvs != null) {
                String[] terms = tvs.getTerms()
                    .keySet().toArray(new String[0]);
                if (terms.length > 0) {
                    String term = terms[rand.nextInt(terms.length)];
                    url += "?facet="+tvs.getField()+"/"+term;
                    
                    Integer cnt = tvs.getTermCount(term);
                    if (cnt != null && cnt < 5000 && !seen.contains(url)) {
                        double freq = cnt.doubleValue()
                            / tvs.getNumDocsWithTerms();
                        if (freq < .5) {
                            ObjectNode n = mapper.createObjectNode();
                            n.put("kind", tvs.getKind().getName());
                            n.put("name", tvs.getKind().getSimpleName());
                            n.put("field", tvs.getField());
                            n.put("term", term);
                            n.put("count", cnt);
                            n.put("total", tvs.getNumDocsWithTerms());
                            n.put("url", url);
                            json.add(n);
                            seen.add(url);
                        }
                    }
                }
            }
        }
        while (json.size() < size && ++iter < 1000);
        
        return json;
    }

    public static Result sampleTermValues (int size) {
        return ok (getSampleTermValues (size));
    }

    public static Result targetpviz (String name) {
        try {
            List<Target> targets = TargetResult.find(name);
            if (!targets.isEmpty()) {
                Target target = targets.get(0);
                Sequence seq = getSequence (target);
                if (seq != null) {
                    return ok (ix.idg.views.html.targetpviz.render(seq));
                }
            }
            return _notFound ("Unknown target: <code>"+name+"</code>");
        }
        catch (Exception ex) {
            return _internalServerError
                ("Unable to retrieve target for <code>"+name+"</code>");
        }
    }

    public static String tcrdVersion () {
        return tcrdVersion (true);
    }

    public static String tcrdVersion (boolean pretty) {
        String ver = _app.configuration().getString("tcrd.version", null);
        if (pretty && ver != null) {
            if (ver.length() == 3) {
                ver = ver.charAt(0)+"."+ver.charAt(1)+"."+ver.charAt(2);
            }
        }
        return ver;
    }

    public static Result rfa () {
        return ok (ix.idg.views.html.rfa.render());
    }

    public static Map<Keyword, Integer> getLibraries (Ligand lig) {
        Set<String> names = new TreeSet<>();
        for (Value v : lig.properties) {
            if (LIBRARY.equals(v.label)) {
                Keyword kw = (Keyword)v;
                names.add(kw.term);
            }
        }

        Map<Keyword, Integer> libs = new HashMap<>();
        for (Value v : lig.properties) {
            if (names.contains(v.label)) {
                Keyword kw = (Keyword)v;
                libs.put(kw, getTermCount (Ligand.class, LIBRARY, v.label));
            }
        }
        return libs;
    }

    public static List<Keyword> getPDBEntries (Target t) {
        String url = "https://www.rcsb.org/pdb/rest/customReport.csv";
        StringBuilder pdbs = new StringBuilder ();

        List<Keyword> entries = t.getSynonyms(PDB_ID);
        for (Keyword kw : entries) {
            if (pdbs.length() > 0)
                pdbs.append(",");
            pdbs.append(kw.term);
        }
        
        WSRequestHolder ws = WS.url(url)
            .setQueryParameter("reportName","Ligands")
            .setQueryParameter("service", "wsfile")
            .setQueryParameter("format", "csv")
            .setQueryParameter("pdbids", pdbs.toString());

        Set<String> ligands = new TreeSet<>();
        try {
            WSResponse wsres = ws.get().get(1000l);
            try (BufferedReader br = new BufferedReader
                 (new InputStreamReader (wsres.getBodyAsStream()))) {
                br.readLine(); // skip header
                for (String line; (line = br.readLine()) != null; ) {
                    String[] toks = line.split(",");
                    if (toks.length > 0) {
                        String id = toks[0].replaceAll("\"", "");
                        ligands.add(id);
                        ligands.add(id.toLowerCase());
                    }
                }
            }
            Logger.debug("Target "+t.accession
                         +" has pdb with ligands: "+ligands);
        }
        catch (Exception ex) {
            Logger.error("Unable to retrieve ligand info from PDB!", ex);
        }

        List<Keyword> all = new ArrayList<>();
        if (!ligands.isEmpty()) {
            List<Keyword> not = new ArrayList<>();
            for (Keyword kw : entries) {
                if (ligands.contains(kw.term)) {
                    all.add(kw);
                }
                else
                    not.add(kw);
            }
            all.addAll(not);
        }
        
        return all.isEmpty() ? entries : all;
    }
}
