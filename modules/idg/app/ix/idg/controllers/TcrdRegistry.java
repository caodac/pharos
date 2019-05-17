package ix.idg.controllers;

import com.avaje.ebean.Expr;
import com.jolbox.bonecp.BoneCPDataSource;
import ix.core.chem.StructureProcessor;
import ix.core.controllers.KeywordFactory;
import ix.core.controllers.PredicateFactory;
import ix.core.controllers.PublicationFactory;
import ix.core.models.*;
import ix.core.plugins.*;
import ix.core.search.TextIndexer;
import ix.idg.models.*;
import ix.seqaln.SequenceIndexer;
import tripod.chem.indexer.StructureIndexer;
import com.fasterxml.jackson.databind.JsonNode;

import play.Logger;
import play.Play;
import play.libs.Json;
import play.cache.Cache;
import play.data.DynamicForm;
import play.data.Form;
import play.db.DB;
import play.db.ebean.Model;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
    
public class TcrdRegistry extends Controller implements Commons {

    static final Model.Finder<Long, Target> targetDb = 
        new Model.Finder(Long.class, Target.class);
    static final Model.Finder<Long, Disease> diseaseDb = 
        new Model.Finder(Long.class, Disease.class);
    
    static final TextIndexer INDEXER = 
        Play.application().plugin(TextIndexerPlugin.class).getIndexer();
    static final StructureProcessorPlugin PROCESSOR =
        Play.application().plugin(StructureProcessorPlugin.class);
    static final PersistenceQueue PQ =
        Play.application().plugin(PersistenceQueue.class);
    static final SequenceIndexer SEQIDX = Play.application()
        .plugin(SequenceIndexerPlugin.class).getIndexer();
    static final StructureIndexer MOLIDX = Play.application()
        .plugin(StructureIndexerPlugin.class).getIndexer();

    static final DrugTargetOntology dto = new DrugTargetOntology();

    public static Namespace namespace;
    static public class LigandStructureReceiver implements StructureReceiver {
        final Ligand ligand;
        final Keyword source;
        
        public LigandStructureReceiver (Keyword source, Ligand ligand) {
            this.ligand = ligand;
            this.source = source;
        }

        public String getSource () { return source.term; }
        public void receive (Status status, String mesg, Structure struc) {
            //Logger.debug(status+": ligand "+ligand.getName()+" struc "+struc);
            if (status == Status.OK) {
                try {
                    if (struc != null) {
                        struc.properties.add(source);
                        //struc.save();
                        
                        XRef xref = new XRef (struc);
                        xref.properties.add(source);
                        for (Value v : struc.properties)
                            xref.properties.add(v);
                        xref.save();
                        ligand.links.add(xref);
                        ligand.update();
                        INDEXER.update(ligand);
                    }
                    Logger.debug
                        (status+": Ligand "+ligand.id+" "+ligand.getName());
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            else {
                Logger.error(status+": "+ligand.getName()+": "+mesg);
            }
        }
    }
    
    static class TcrdTarget implements Comparable<TcrdTarget> {
        String acc;
        String gene;
        String family;
        String tdl;
        Long id;
        Long protein;
        Double novelty;
        Keyword source;
        DTOParser.Node dtoNode;
        DTOParser dto;
        boolean idg2;

        TcrdTarget () {}
        TcrdTarget (String acc, String gene, String family, String tdl,
                    Long id, Long protein, Double novelty,
                    Keyword source) {
            if (family == null || family.equals("")) 
                family = "Non-IDG";
            if (tdl == null || tdl.equals(""))
                tdl = "Other";

            this.acc = acc;
            this.gene = gene;
            if ("nr".equalsIgnoreCase(family))
                this.family = "Nuclear Receptor";
            else if ("ic".equalsIgnoreCase(family))
                this.family = "Ion Channel";
            else if ("tf".equalsIgnoreCase(family))
                this.family = "Transcription Factor";
            else if ("TF; Epigenetic".equalsIgnoreCase(family))
                this.family = "TF/Epigenetic";
            else 
                this.family = family;

            this.tdl = tdl;
            this.id = id;
            this.protein = protein;
            this.novelty = novelty;
            this.source = source;
        }

        public int hashCode () {
            return acc == null ? 1 : acc.hashCode();
        }
        public boolean equals (Object obj) {
            if (obj instanceof TcrdTarget) {
                return acc.equals(((TcrdTarget)obj).acc);
            }
            return false;
        }
        public int compareTo (TcrdTarget t) {
            return acc.compareTo(t.acc);
        }
    }

    static class IDGCollection {
        final String name;
        final String family;
        final Set<String> targets = new HashSet<>();

        IDGCollection (JsonNode json) {
            name = json.get("name").asText();
            family = json.get("family").asText();
            JsonNode node = json.get("targets");
            for (int i = 0; i < node.size(); ++i)
                targets.add(node.get(i).asText());
        }

        public boolean remove (String t) {
            return targets.remove(t);
        }
        public int size () { return targets.size(); }
    }

    static class PersistRegistration
        extends PersistenceQueue.AbstractPersistenceContext {
        final Connection con;
        final Http.Context ctx;
        final Collection<TcrdTarget> targets;
        PreparedStatement pstm, pstm2, pstm3, pstm4,
            pstm5, pstm6, pstm7, pstm8, pstm9, pstm10, pstm10b,
            pstm11, pstm12, pstm13, pstm14, pstm15,
            pstm16, pstm17, pstm18, pstm19, pstm20, pstm20b,
            pstm21, pstm22, pstm23, pstm24, pstm25,
            pstm26, pstm27, pstm28, pstm29, pstm30,
            pstm31, pstm32, pstm33, pstm34;
        Map<String, Keyword> datasources = new HashMap<String, Keyword>();

        // xrefs for the current target
        Map<String, List<String>> xrefs =
            new HashMap<String, List<String>>();
        //Map<String, Keyword> phenotypeSource = new HashMap<String, Keyword>();

        Map<Target.TDL, Keyword> tdlKeywords =
            new EnumMap<Target.TDL,Keyword>(Target.TDL.class);
        Map<String, Keyword> famKeywords = new HashMap<String, Keyword>();
        Map<String, Map<String, Keyword>> keywords =
            new HashMap<String, Map<String, Keyword>>();
        List<IDGCollection> collections = new ArrayList<>();
        
        PersistRegistration (Connection con, Http.Context ctx,
                             Collection<TcrdTarget> targets)
            throws SQLException {
            this.con = con;
            this.ctx = ctx;
            this.targets = targets;

            File file = Play.application()
                .getFile("conf/idg-collections.json");
            if (file.exists()) {
                try {
                    JsonNode json = Json.parse(new FileInputStream (file));
                    for (int i = 0; i < json.size(); ++i) {
                        JsonNode node = json.get(i);
                        collections.add(new IDGCollection (node));
                    }
                }
                catch (Exception ex) {
                    Logger.error("Can't parse json file: "+file, ex);
                }
            }

            List<Keyword> keywords = KeywordFactory.finder
                .where().eq("label", IDG_DEVELOPMENT).findList();
            for (Keyword kw : keywords) {
                tdlKeywords.put(Target.TDL.valueOf(kw.term), kw);
            }
            Logger.debug(tdlKeywords.size()+" "+IDG_DEVELOPMENT+" loaded!");

            keywords = KeywordFactory.finder
                .where().eq("label", IDG_FAMILY).findList();
            for (Keyword kw : keywords) {
                famKeywords.put(kw.term, kw);
            }
            Logger.debug(famKeywords.size()+" "+IDG_FAMILY+" loaded!");
            
            keywords = KeywordFactory.finder
                .where().eq("label", SOURCE).findList();
            for (Keyword kw : keywords)
                datasources.put(kw.term, kw);
            Logger.debug(datasources.size()+" "+SOURCE+" loaded!");
            
            // For some reason it is possible that a target has entries in target2disease and tinx_novelty
            // but no entries in tinx_importance. Example is Q8WXS5. As a result the SQL below would not
            // return anything
            pstm = con.prepareStatement
                    ("select distinct " +
                     "a.target_id, d.doid, d.name, d.score as diseaseNovelty, c.score as importance,  " +
                     "e.uniprot, f.score as targetNovelty " +
                     "from disease a, tinx_disease d, tinx_importance c, protein e, tinx_novelty f " +
                     "where a.target_id = ? " +
                     "and a.did = d.doid " +
                     "and c.protein_id = a.target_id " +
                     "and c.disease_id = d.id " +
                     "and e.id = a.target_id " +
                     "and f.protein_id = a.target_id");
            pstm2 = con.prepareStatement
                ("select * from cmpd_activity where target_id = ?");
            pstm3 = con.prepareStatement
                ("select * from drug_activity where target_id = ?");
            pstm4 = con.prepareStatement
                ("select * from generif where protein_id = ?");
            pstm5 = con.prepareStatement
                ("select * from dto_classification "
                 +"where protein_id = ? order by id");
            pstm6 = con.prepareStatement
                ("select * from tdl_info where protein_id = ? or target_id = ?");
            pstm7 = con.prepareStatement
                ("select * from phenotype where protein_id = ?");
            pstm8 = con.prepareStatement
                ("select * from expression where protein_id = ? ");
            pstm9 = con.prepareStatement
                ("select a.*, uniprot from goa a, protein b where a.protein_id = ? and a.protein_id = b.id");
            pstm10 = con.prepareStatement
                ("select * from panther_class a, p2pc b "
                 +"where a.id = b.panther_class_id and b.protein_id = ?");
            pstm10b = con.prepareStatement
                ("select * from panther_class where pcid = ?");
            pstm11 = con.prepareStatement
                ("select * from pathway where protein_id = ? or target_id = ?");
            pstm12 = con.prepareStatement
                ("select * from xref where protein_id = ?");
            pstm13 = con.prepareStatement
                ("select * from patent_count where "
                 +"protein_id = ? order by year");
            pstm14 = con.prepareStatement
                ("select * from protein where id = ?");
            pstm15 = con.prepareStatement
                ("select * from alias where protein_id = ?");
            pstm16 = con.prepareStatement
                ("select * from cmpd_activity where target_id = ?");
            pstm17 = con.prepareStatement
                ("select * from drug_activity where target_id = ?");
            pstm18 = con.prepareStatement("select p.sym, p.uniprot, hg.*, gat.* " +
                    "from target t, t2tc, protein p, hgram_cdf hg, gene_attribute_type gat " +
                    "WHERE t.id = t2tc.target_id AND t2tc.protein_id = p.id AND p.id = hg.protein_id " +
                    "AND gat.name = hg.type and hg.protein_id = ?");

            pstm19 = con.prepareStatement
                ("select * from `grant` where target_id = ?");

            pstm20 = con.prepareStatement
                ("select * from disease where target_id = ? ");
            pstm20b = con.prepareStatement
                ("select distinct a.name \n"
                 +"from disease a, t2tc b, drug_activity c\n"
                 +"where dtype='DrugCentral Indication'\n"
                 +"and a.target_id = ?\n"
                 +"and a.target_id = b.target_id\n"
                 +"and a.target_id = c.target_id\n"
                 +"and a.drug_name = c.drug and c.has_moa = 1");

            pstm21 = con.prepareStatement
                ("select * from mlp_assay_info where protein_id = ? order by aid");

            pstm22 = con.prepareStatement
                ("select * from protein2pubmed a, pubmed b "
                 +"where a.pubmed_id = b.id and a.protein_id = ?");

            pstm23 = con.prepareStatement
                ("select distinct cmpd_chemblid "
                 +"from drug_activity where drug = ?");

            pstm24 = con.prepareStatement
                ("select distinct cmpd_name_in_src "
                 +"from cmpd_activity where cmpd_id_in_src = ?");

            pstm25 = con.prepareStatement
                ("select * from pmscore where protein_id = ? order by year");

            pstm26 = con.prepareStatement
                ("select * from ppi where protein1_id = ?");

            pstm27 = con.prepareStatement
                ("select * from compartment where protein_id = ?");

            pstm28 = con.prepareStatement
                ("select * from techdev_contact a, techdev_info b where "
                 +"a.id = b.contact_id and b.protein_id = ?");

            pstm29 = con.prepareStatement
                ("select * from ptscore where protein_id = ?");

            pstm30 = con.prepareStatement
                ("select * from feature where protein_id = ? ");
            pstm31 = con.prepareStatement
                ("select * from locsig where protein_id = ?");
            pstm32 = con.prepareStatement
                ("select a.name as ortho_name,a.db_id,a.species,"
                 +"a.geneid,a.symbol,a.mod_url,a.sources,"
                 +"a.id as ortholog_id,b.score,c.* "
                 +"from ortholog a left join (ortholog_disease b, disease c) "
                 +"on b.ortholog_id = a.id and b.did = c.did and "
                 +"b.protein_id = c.protein_id where a.protein_id = ?");
            pstm33 = con.prepareStatement
                ("select * from drgc_resource where target_id = ?");
            pstm34 = con.prepareStatement("select * from uberon where uid = ?");
        }

        Keyword getTdlKw (Target.TDL tdl) {
            Keyword kw = tdlKeywords.get(tdl);
            if (kw == null) {
                kw = KeywordFactory.registerIfAbsent
                    (IDG_DEVELOPMENT, tdl.name, null);
                tdlKeywords.put(tdl, kw);
            }
            return kw;
        }

        Keyword getFamKw (String fam) {
            Keyword kw = famKeywords.get(fam);
            if (kw == null) {
                kw = KeywordFactory.registerIfAbsent(IDG_FAMILY, fam, null);
                famKeywords.put(fam, kw);
            }
            return kw;
        }
        
        Keyword getKeyword (String label, String value) {
            return getKeyword (label, value, null);
        }
        
        Keyword getKeyword (String label, String value, String href) {
            /*
            Map<String, Keyword> keys = keywords.get(label);
            if (keys == null) {
                keywords.put(label, keys = new HashMap<String, Keyword>());
            }
            
            Keyword kw = keys.get(value);
            if (kw == null) {
                keys.put(value, kw = KeywordFactory.registerIfAbsent
                         (label, value, href));
            }
            */
            return KeywordFactory.registerIfAbsent(label, value, href);
        }

        public void persists () throws Exception {
            java.util.Date start = new java.util.Date();
            for (TcrdTarget t : targets) {
                persists (t);
            }
            
            for (Ligand l : LigandFactory.finder.all()) {
                try {
                    INDEXER.update(l);
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            for (Disease d : DiseaseFactory.finder.all()) {
                try {
                    Value drug = d.getProperty(IDG_DRUG);
                    if (drug != null) {
                        Keyword kw = (Keyword)drug;
                        List<Ligand> ligands = LigandFactory.finder.where()
                            .eq("synonyms.term", kw.term).findList();
                        if (!ligands.isEmpty()) {
                            XRef ref = d.addIfAbsent(new XRef (ligands.get(0)));
                            if (ref.id == null)
                                ref.save();
                            d.update();
                        }
                        else {
                            Logger.warn("Can't find drug \""
                                        +kw.term+"\" for disease "
                                        +d.id+" "+d.name);
                        }
                    }
                    //d.update();             
                    INDEXER.update(d);              
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            for (IDGCollection ic : collections) {
                if (ic.size() > 0) {
                    Logger.warn("!! Not all targets for collection \""+ic.name
                                +"\" have been accounted for: "+ic.targets);
                }
            }
            
            Logger.debug("\n#### PERSISTENCE STARTED ON "+start+" ####"
                         +"\n#### AND COMPLETE AT "
                         +new java.util.Date()+"! #####");
        }

        public void shutdown () throws SQLException {
            pstm.close();
            pstm2.close();
            pstm3.close();
            pstm4.close();
            pstm5.close();
            pstm6.close();
            pstm7.close();
            pstm8.close();
            pstm9.close();
            pstm10.close();
            pstm10b.close();
            pstm11.close();
            pstm12.close();
            pstm13.close();
            pstm14.close();
            pstm15.close();
            pstm16.close();
            pstm17.close();
            pstm18.close();
            pstm19.close();
            pstm20.close();
            pstm20b.close();
            pstm21.close();
            pstm22.close();
            pstm23.close();
            pstm24.close();
            pstm25.close();
            pstm26.close();
            pstm27.close();
            pstm28.close();
            pstm29.close();
            pstm30.close();
            pstm31.close();
            pstm32.close();
            pstm33.close();
            pstm34.close();
        }

        void instrument (Target target, TcrdTarget t) throws Exception {
            //Logger.debug("... instrumenting target " +t.id);
            target.synonyms.add(new Keyword (IDG_TARGET, "TCRD:"+t.id));
            target.properties.add(t.source);
            
            if (t.novelty != null) {
                // log10
                target.novelty = Math.log10(t.novelty);
            }
            
            String value;
            
            xrefs.clear();
            Map<String, Integer> counts = new TreeMap<String, Integer>();
            pstm12.setLong(1, t.protein);
            try (ResultSet rset = pstm12.executeQuery()) {
                while (rset.next()) {
                    String xtype = rset.getString("xtype");
                    value = rset.getString("value");
                    if (value == null || value.trim().equals("")) {
                        continue;
                    }
                    value = value.trim();
                
                    //Logger.info("  + "+xtype+": "+value);
                    Integer c = counts.get(xtype);
                    counts.put(xtype, c==null?1:(c+1));
                    if ("uniprot keyword".equalsIgnoreCase(xtype)) {
                        String term = rset.getString("xtra");
                        if (term != null) {
                            Keyword kw = KeywordFactory.registerIfAbsent
                                (UNIPROT_KEYWORD, term.replaceAll("/","-"),
                                 "http://www.uniprot.org/keywords/"+value);

                            target.addIfAbsent((Value)kw);
                        }
                    }
                    else if ("pdb".equalsIgnoreCase(xtype)) {
                        Keyword kw = KeywordFactory.registerIfAbsent
                            (PDB_ID, value,
                             "http://www.rcsb.org/pdb/explore/"
                             +"explore.do?structureId="+value);
                        target.addIfAbsent(kw);
                    }
                    else if ("pubmed".equalsIgnoreCase(xtype)) {
                        /*
                          Publication pub = PublicationFactory.registerIfAbsent
                          (Long.parseLong(value));
                          if (pub != null)
                          target.addIfAbsent(pub);
                        */
                        target.addIfAbsent
                            (new VInt (PUBMED_ID, Long.parseLong(value)));
                    }
                    else {
                        List<String> names = xrefs.get(xtype);
                        if (names == null) {
                            xrefs.put(xtype, names = new ArrayList<String>());
                        }
                        names.add(value);
                        target.addIfAbsent(new Keyword (xtype, value));
                    }
                }
            }

            Keyword source =  KeywordFactory.registerIfAbsent
                (SOURCE, "UniProt", "http://www.uniprot.org");
            datasources.put("UniProt", source);

            if (!collections.isEmpty()) {
                for (IDGCollection ic : collections) {
                    if (ic.remove(target.gene)) {
                        target.addIfAbsent
                            ((Value)KeywordFactory.registerIfAbsent
                             (COLLECTION, ic.name, null));
                        break;
                    }
                }
            }
            else if (t.idg2) {
                Keyword collection = null;
                if ("gpcr".equalsIgnoreCase(t.family)) {
                    collection = KeywordFactory.registerIfAbsent
                        (COLLECTION, "Eligible non-olfactory GPCR Proteins",
                         // abuse url with the description.. 
                         "The non-olfactory GPCRs that constitute the initial list of candidate protein targets eligible to be studied in the Implementation Phase of the Common Fund IDG program as per RFA-RM-16-026");
                }
                else if ("kinase".equalsIgnoreCase(t.family)) {
                    collection = KeywordFactory.registerIfAbsent
                        (COLLECTION, "Eligible Kinase Proteins",
                         "The Kinases that constitute the initial list of candidate protein targets eligible to be studied in the Implementation Phase of the Common Fund IDG program as per RFA-RM-16-026");
                }
                else if ("ion channel".equalsIgnoreCase(t.family)) {
                    collection = KeywordFactory.registerIfAbsent
                        (COLLECTION, "Eligible Ion Channel Proteins",
                         "The ion channel proteins that constitute the initial list of candidate protein targets eligible to be studied in the Implementation Phase of the Common Fund IDG program as per RFA-RM-16-026");
                }
                
                if (collection != null) {
                    target.addIfAbsent((Value)collection);
                }
            }

            pstm14.setLong(1, t.protein);
            try (ResultSet rset = pstm14.executeQuery()) {
                while (rset.next()) {
                    target.name = rset.getString("description");
                    value = rset.getString("uniprot");
                
                    Keyword kw = new Keyword (UNIPROT_ACCESSION, value);
                    kw.href = "http://www.uniprot.org/uniprot/"+value;
                    Keyword _kw = target.addIfAbsent(kw);
                    if (_kw == kw)
                        kw.save();

                    kw = new Keyword (STRING_ID, rset.getString("stringid"));
                    _kw = target.addIfAbsent(kw);
                    if (_kw == kw)
                        kw.save();

                    kw = new Keyword (UNIPROT_NAME, rset.getString("name"));
                    _kw = target.addIfAbsent(kw);
                    if (_kw == kw)
                        kw.save();

                    //Gene gene = GeneFactory.registerIfAbsent(rset.getString("sym"));
                    //target.links.add(new XRef (gene));
                    value = rset.getString("sym");
                    if (value != null) {
                        kw = new Keyword (UNIPROT_GENE, value);
                        kw.href = "http://www.genenames.org/"
                            +"cgi-bin/gene_symbol_report?match="+value;
                        _kw = target.addIfAbsent(kw);
                        if (_kw == kw)
                            kw.save();
                    }
                
                    value = String.valueOf(rset.getLong("geneid"));
                    kw = new Keyword (ENTREZ_GENE, value);
                    kw.href = "https://www.ncbi.nlm.nih.gov/gene/"+value;
                    _kw = target.addIfAbsent(kw);
                    if (_kw == kw)
                        kw.save();

                    value = rset.getString("dtoid");
                    if (value != null) {
                        kw = new Keyword (DTO_ID, value);
                        kw.href = "http://drugtargetontology.org/"+value;
                        _kw = target.addIfAbsent(kw);
                        if (_kw == kw)
                            kw.save();
                    }
                    
                    Text seq = new Text (UNIPROT_SEQUENCE,
                                         rset.getString("seq"));
                    seq.save();
                
                    SEQIDX.add(String.valueOf(seq.id), seq.text);
                    target.properties.add(seq);
                }
            }

            pstm15.setLong(1, t.protein);
            try (ResultSet rset = pstm15.executeQuery()) {
                while (rset.next()) {
                    String type = rset.getString("type");
                    value = rset.getString("value");
                    if ("uniprot".equalsIgnoreCase(type)) {
                        Keyword kw = new Keyword (UNIPROT_ACCESSION, value);
                        kw.href = "http://www.uniprot.org/uniprot/"+value;
                        Keyword _kw = target.addIfAbsent(kw);
                        if (_kw == kw)
                            kw.save();
                    }
                    else if ("symbol".equalsIgnoreCase(type)) {
                        Keyword kw = new Keyword (UNIPROT_SHORTNAME, value);
                        Keyword _kw = target.addIfAbsent(kw);
                        if (_kw == kw)
                            kw.save();
                    }
                    Logger.info("  + "+type+": "+value);
                }
            }
            
            target.properties.add(source);
            Logger.debug("Target "+target.id+" xtype: "+counts);
        }
                         
        void addPatent (Target target, long protein) throws Exception {
            Timeline timeline = null;
            int np = 0;
            
            pstm13.setLong(1, protein);
            try (ResultSet rset = pstm13.executeQuery()) {
                while (rset.next()) {
                    long year = rset.getLong("year");
                    long count = rset.getLong("count");
                    if (timeline == null) {
                        timeline = new Timeline ("Patent Count");
                    }
                    Event event = new Event ();
                    event.start = year;
                    event.end = count; // abusing notation
                    event.unit = Event.Resolution.YEARS;
                    timeline.events.add(event);
                    ++np;
                }
            }
            
            if (timeline != null) {
                timeline.save();
                target.links.add(new XRef (timeline));
            }
            Logger.debug("Target "+target.id+" has "+np+" patent(s)!");
        }

        void addPubTator (Target target, long protein) throws Exception {
            pstm29.setLong(1, protein);
            try (ResultSet rset = pstm29.executeQuery()) {
                Timeline timeline = null;
                int count = 0;
                while (rset.next()) {
                    long year = rset.getInt("year");
                    double score = rset.getDouble("score");
                    if (timeline == null)
                        timeline = new Timeline ("PubTator");
                    Event ev = new Event ();
                    ev.start = ev.end = year;
                    ev.unit = Event.Resolution.YEARS;
                    ev.properties.add(new VNum ("Score", score));
                    timeline.events.add(ev);
                    ++count;
                }
                
                if (timeline != null) {
                    timeline.save();
                    target.links.add(new XRef (timeline));
                }
                Logger.debug("Target "+target.id+" has "
                             +count+" pubtator entries!");
            }
        }

        void addPMScore (Target target, long protein) throws Exception {
            Timeline timeline = null;
            
            pstm25.setLong(1, protein);
            try (ResultSet rset = pstm25.executeQuery()) {
                while (rset.next()) {
                    long year = rset.getLong("year");
                    double score = rset.getDouble("score");
                    if (timeline == null) {
                        timeline = new Timeline ("PubMed Score");
                    }
                    Event event = new Event ();
                    event.start = year;
                    event.end = (long)(score * 1000 + 0.5); // abusing notation
                    event.unit = Event.Resolution.YEARS;
                    timeline.events.add(event);
                }
            }
            
            if (timeline != null) {
                timeline.save();
                target.links.add(new XRef (timeline));
            }
        }

        void addResources (Target target, long tid) throws Exception {
            pstm33.setLong(1, tid);
            try (ResultSet rset = pstm33.executeQuery()) {
                while (rset.next()) {
                    String type = rset.getString("resource_type");
                    String json = rset.getString("json");
                    Text text = new Text (type, json);
                    text.save();
                    XRef ref = new XRef (text);
                    ref.properties.add(KeywordFactory.registerIfAbsent
                                       (IDG_RESOURCES, type, null));
                    ref.save();
                    target.links.add(ref);
                }
            }
        }
        
        void addAssay (Target target, long protein) throws Exception {
            int count = 0;            
            pstm21.setLong(1, protein);
            try (ResultSet rset = pstm21.executeQuery()) {
                while (rset.next()) {
                    Assay assay = new Assay (rset.getString("assay_name"));
                    assay.type = rset.getString("method");
                    assay.properties.add
                        (new VInt (MLP_ASSAY_ACTIVE, rset.getLong("active_sids")));
                    assay.properties.add
                        (new VInt (MLP_ASSAY_INACTIVE,
                                   rset.getLong("inactive_sids")));
                    assay.properties.add
                        (new VInt (MLP_ASSAY_INCONCLUSIVE,
                                   rset.getLong("iconclusive_sids")));
                    assay.properties.add
                        (new VInt (MLP_ASSAY_AID, rset.getLong("aid")));
                    assay.save();
                
                    XRef tref = assay.addIfAbsent(new XRef (target));
                    tref.addIfAbsent((Value)getTdlKw (target.idgTDL));
                    tref.addIfAbsent((Value)getFamKw (target.idgFamily));
                
                    XRef aref = target.addIfAbsent(new XRef (assay));
                    aref.addIfAbsent((Value)KeywordFactory.registerIfAbsent
                                     (MLP_ASSAY_TYPE, assay.type, null));
                    // 
                    aref.properties.add(new Text (MLP_ASSAY, assay.name));

                    try {
                        tref.save();
                        aref.save();
                        assay.update();
                        //target.update();
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    ++count;
                }
            }
            Logger.debug("Target " + target.id + " has " + count +" assay(s)!");
        }

        void addGrant (Target target, long tid) throws Exception {
            pstm19.setLong(1, tid);
            ResultSet rset = pstm19.executeQuery();
            Set<String> fundingICs = new HashSet<String>();
            Map<String, Integer> activity = new HashMap<String, Integer>();
            int count = 0;
            double cost = 0;
            while (rset.next()) {
                String act = rset.getString("activity");
                if (act != null) {
                    Integer c = activity.get(act);
                    activity.put(act, c != null ? (c+1) : 1);
                }
                String ics = rset.getString("funding_ics");
                if (ics != null) {
                    for (int pos, p = 0; (pos = ics.indexOf('\\', p)) > p; ) {
                        String s = ics.substring(p, pos);
                        String[] toks = s.split(":");
                        if (toks.length == 2) {
                            fundingICs.add(toks[0]);
                        }
                        p = pos;
                    }
                }
                cost += rset.getDouble("cost");
                long appid = rset.getLong("appid");
                if (!rset.wasNull()) {
                    Keyword grant = new Keyword 
                        (GRANT_APPLICATION, rset.getString("full_project_num"));
                    grant.href = "https://projectreporter.nih.gov/"
                        +"project_info_description.cfm?aid="+appid;
                    target.properties.add(grant);
                }
                ++count;
            }
            rset.close();

            for (String a : activity.keySet()) {
                Keyword kw = KeywordFactory.registerIfAbsent
                    (GRANT_ACTIVITY, a, null);
                target.properties.add(kw);
            }
            target.r01Count = activity.get("R01");

            for (String ic : fundingICs) {
                Keyword kw = KeywordFactory.registerIfAbsent
                    (GRANT_FUNDING_IC, ic, null);
                target.properties.add(kw);
            }
            
            if (count > 0)
                target.grantCount = count;
            
            if (cost > 0.)
                target.grantTotalCost = cost;
        }
        
        void addPathway (Target target, long protein) throws Exception {
            Map<String, Integer> counts = new TreeMap<String, Integer>();
            pstm11.setLong(1, protein);
            pstm11.setLong(2, protein); // target_id = protein_id
            
            try (ResultSet rset = pstm11.executeQuery()) {
                while (rset.next()) {
                    String source = rset.getString("pwtype");
                    String name = rset.getString("name");
                    if (name.length() > 255) {
                        name = name.substring(0, 246)+"..."
                            +name.substring(name.length()-6);
                    }
                    Keyword term = KeywordFactory.registerIfAbsent
                        (source+" Pathway", name, rset.getString("url"));
                    target.addIfAbsent((Value)term);
                    /*
                      Logger.debug("Target "+target.id
                      +" pathway ("+source+"): "+term.term);
                    */
                
                    if ("Reactome".equals(source)) {
                        List<String> refs = xrefs.get("Reactome");
                        if (refs != null) {
                            String id = refs.iterator().next();
                            Keyword kw = KeywordFactory.registerIfAbsent
                                (REACTOME_REF, id,
                                 "http://www.reactome.org/content/"
                                 +"query?cluster=true&q="+id);
                            target.addIfAbsent((Value)kw);
                        }
                    }
                    Integer c = counts.get(source);
                    counts.put(source, c==null?1:(c+1));
                }
            }
            Logger.debug("Target "+target.id+" pathway: "+counts);
        }

        void addHarmonogram(Target target, long protein) throws Exception {
            int n = 0;
            double cdfSum = 0;
            
            pstm18.setLong(1, protein);
            try (ResultSet rset = pstm18.executeQuery()) {
                while (rset.next()) {
                    HarmonogramCDF hg = new HarmonogramCDF();
                    hg.attrGroup = rset.getString("attribute_group");
                    hg.attrType = rset.getString("attribute_type");
                    hg.cdf = rset.getDouble("attr_cdf");
                    cdfSum += hg.cdf;
                    hg.dataSource = rset.getString("name");
                    hg.dataSourceUrl = rset.getString("url");
                    //hg.dataSourceDescription = rset.getString("description");
                    hg.dataType  =rset.getString("resource_group");
                    hg.IDGFamily = target.idgFamily;
                    hg.TDL = target.idgTDL.name;
                    hg.uniprotId = rset.getString("uniprot");
                    hg.symbol = rset.getString("sym");
                    hg.save();
                    n++;
                }
            }
            target.knowledgeAvailability = cdfSum;
            Logger.debug("Target "+target.id+" has "+n+" harmonogram entries!");
        }

        void addGO (Target target, long protein) throws Exception {
            Map<String, Integer> counts = new TreeMap<String, Integer>();
            pstm9.setLong(1, protein);
            try (ResultSet rset = pstm9.executeQuery()) {
                while (rset.next()) {
                    String term = rset.getString("go_term");
                    String id = rset.getString("go_id");
                    String uniprot = rset.getString("uniprot");
                
                    Keyword go = null;
                    char kind = term.charAt(0);
                    term = term.substring(term.indexOf(':')+1)
                        .replaceAll("/","-");
                    String href = "http://amigo.geneontology.org/amigo/search/annotation?q=*:*&fq=bioentity:\"UniProtKB:"+uniprot+"\"&sfq=document_category:\"annotation\"&fq=regulates_closure:\""+id+"\"";
                
                    switch (kind) {
                    case 'C': // component
                        go = KeywordFactory.registerIfAbsent
                            (GO_COMPONENT, term, href);
                        break;
                    
                    case 'F': // function
                        go = KeywordFactory.registerIfAbsent
                            (GO_FUNCTION, term, href);
                        break;
                    
                    case 'P': // process
                        go = KeywordFactory.registerIfAbsent
                            (GO_PROCESS, term, href);
                        break;
                    
                    default:
                        Logger.warn("Unknown GO term \""+term+"\"!");
                    }

                    if (go != null && !target.properties.contains(go)) {
                        target.properties.add(go);
                        //Logger.debug("Target "+target.id+" GO: "+term);
                    }
                    Integer c = counts.get(""+kind);
                    counts.put(""+kind,c==null?1:(c+1));
                }
            }
            Logger.debug("Target "+target.id+" GO: "+counts);
        }
        
        void addExpression (Target target, long protein) throws Exception {
            Map<String, Integer> counts = new TreeMap<String, Integer>();
            Map<String, Keyword> sources = new HashMap<String, Keyword>();
            pstm8.setLong(1, protein);
            try (ResultSet rset = pstm8.executeQuery()) {
                while (rset.next()) {
                    String qv = rset.getString("qual_value");
                    String ev = rset.getString("evidence");
                    if ("Not detected".equalsIgnoreCase(qv)
                        && !"CURATED".equalsIgnoreCase(ev))
                        continue;

                    Expression expr = new Expression();
                    expr.proteinId = protein;
                    expr.source = rset.getString("etype");
                    expr.tissue = rset.getString("tissue");
                    expr.confidence = rset.getDouble("conf");
                    expr.qualValue = qv;
                    expr.numberValue = rset.getDouble("number_value");
                    expr.evidence = ev;
                    expr.uberonid = rset.getString("uberon_id");

                    String sourceUrl = "";
                    Keyword tissue = null;
                    if (expr.source.startsWith("GTEx")) {
                        sourceUrl = "http://www.gtexportal.org/";
                        expr.sourceid = GTEx_EXPR;
                        tissue = KeywordFactory.registerIfAbsent
                            (GTEx_TISSUE, expr.tissue, null);
                        target.addIfAbsent((Value)tissue);
                    }
                    else if (expr.source.startsWith("Consensus")) {
                        sourceUrl = "https://druggablegenome.net";
                        expr.sourceid = IDG_EXPR;
                        tissue = KeywordFactory.registerIfAbsent
                            (IDG_TISSUE, expr.tissue, null);
                        target.addIfAbsent((Value)tissue);
                    }
                    else if (expr.source.startsWith("HPM Gene")) {
                    }
                    else if (expr.source.startsWith("HPM Protein")) {
                        sourceUrl = "http://www.humanproteomemap.org";
                        expr.sourceid = HPM_EXPR;
                        tissue = KeywordFactory.registerIfAbsent
                            (HPM_TISSUE, expr.tissue, null);
                        target.addIfAbsent((Value)tissue);
                    }
                    else if (expr.source.startsWith("JensenLab Text Mining")) {
                        sourceUrl = "http://tissues.jensenlab.org";
                        expr.sourceid = JENSEN_TM_EXPR;
                        // this is specific to each protein
                        tissue = new Keyword (JENSEN_TM_TISSUE, expr.tissue);
                        tissue.href = rset.getString("url");
                        tissue.save();
                        target.properties.add(tissue);
                    }
                    else if (expr.source.startsWith
                             ("JensenLab Knowledge UniProtKB-RC")) {
                        sourceUrl = "http://tissues.jensenlab.org";
                        expr.sourceid = JENSEN_KB_EXPR;
                        tissue = KeywordFactory.registerIfAbsent
                            (JENSEN_KB_TISSUE, expr.tissue, null);
                        target.addIfAbsent((Value)tissue);
                    }
                    else if (expr.source.equals("UniProt Tissue")) {
                        tissue = KeywordFactory.registerIfAbsent
                            (expr.source, expr.tissue, null);
                        expr.sourceid = UNIPROT_EXPR;
                        target.addIfAbsent((Value)tissue);              
                    }
                    else if (expr.source.startsWith("HPA")) {
                        sourceUrl = "http://tissues.jensenlab.org";
                        expr.sourceid = expr.source+" Expression";
                        tissue = KeywordFactory.registerIfAbsent
                            (expr.source+" Tissue", expr.tissue, null);
                        target.addIfAbsent((Value)tissue);

                        String toks[] = expr.tissue.split("-");
                        if (toks.length == 2) {
                            expr.cellType = toks[1].trim();
                            Keyword cellType = KeywordFactory.registerIfAbsent
                                (expr.source+" Cell Type",
                                 expr.cellType, null);
                            target.addIfAbsent((Value)cellType);
                        }
                    }
                    else if (expr.source.startsWith("JensenLab Experiment")) {
                        sourceUrl = "http://tissues.jensenlab.org";
                        String t = expr.source
                            .substring("JensenLab Experiment".length()+1).trim();
                        expr.sourceid = t+" Expression";
                    }
                    else if (expr.source.equals("HCA RNA")) {
                        sourceUrl = "https://www.humancellatlas.org";
                        expr.sourceid = expr.source+" Cell Line";
                        // remove "Cell Line" prefix
                        if (expr.tissue.startsWith("Cell Line"))
                            expr.tissue = expr.tissue.substring(9).trim();
                        tissue = KeywordFactory.registerIfAbsent
                            (HCA_RNA_CELL_LINE, expr.tissue, null);
                        target.addIfAbsent((Value)tissue);
                    }
                    else if (expr.source.equals("Cell Surface Protein Atlas")) {
                        sourceUrl = "http://wlab.ethz.ch/cspa/";
                        expr.sourceid = CSPA_CELL_LINE;
                        if (expr.tissue.startsWith("Cell Line"))
                            expr.tissue = expr.tissue.substring(9).trim();
                        tissue = KeywordFactory.registerIfAbsent
                            (CSPA_CELL_LINE, expr.tissue, null);
                        target.addIfAbsent((Value)tissue);                    
                    }
                    else
                        Logger.warn("Unknown expression \""+expr.source
                                    +"\" for target "+target.id);

                    expr.save();
                    XRef ref = target.addIfAbsent(new XRef(expr));
                    if (tissue != null)
                        ref.properties.add(tissue);

                    if (expr.uberonid != null) {
                        String uberid = expr.uberonid.replace(':', '_');
                        Keyword uberon = KeywordFactory.registerIfAbsent
                            (UBERON_TISSUE, expr.uberonid,
                             "http://purl.obolibrary.org/obo/"+uberid);
                        ref.properties.add(uberon);
                        
                        pstm34.setString(1, expr.uberonid);
                        try (ResultSet rs = pstm34.executeQuery()) {
                            if (rs.next()) {
                                String name = rs.getString("name");
                                String def = rs.getString("def");
                                // abusing Keyword
                                Keyword kw = KeywordFactory.registerIfAbsent
                                    (expr.uberonid, name, def);
                                ref.properties.add(kw);
                            }
                        }
                    }
                
                    if (ref.id == null)
                        ref.save();
                
                    //Logger.debug("Target "+target.id+" "+expr.source+": "+expr.tissue);
                    Integer c = counts.get(expr.source);
                    counts.put(expr.source, c==null?1:(c+1));

                    Keyword source = datasources.get(expr.source);
                    if (source == null) {
                        source = KeywordFactory.registerIfAbsent
                            (SOURCE, expr.source, sourceUrl);
                        datasources.put(expr.source, source);
                    }
                    sources.put(expr.source, source);
                }
            }

            for (Keyword source: sources.values()) {
                target.addIfAbsent((Value)source);
            }

            List<String> ids = xrefs.get("STRING");
            if (ids != null && !ids.isEmpty()) {
                // 9606.ENSP00000000442
                String id = ids.iterator().next();
                if (id.startsWith("9606.")) {
                    id = id.substring(5);
                    Keyword kw = new Keyword (IDG_TISSUE_REF, id);
                    kw.href = "http://tissues.jensenlab.org/Entity?figures=tissues_body_human&knowledge=10&experiments=10&textmining=10&type1=9606&type2=-25&id1="+id;
                    target.properties.add(kw);
                }
            }
            Logger.debug("Target "+target.id+" expression: "+counts);
        }

        static Pattern OmimRegex = Pattern.compile("([^\\s]+)\\s\\(([1-4])\\)");
        static void parseOMIMPhenotype (String trait, Target target) {
            String[] tokens  = trait.split(";");
            int pos = tokens[0].indexOf(':');
            if (pos > 0) {
                String mim = tokens[0].substring(pos+1).trim();
                Keyword kw = KeywordFactory.registerIfAbsent
                    (OMIM_GENE, "MIM:"+mim,"http://omim.org/entry/"+mim);
                target.addIfAbsent(kw);
            }

            /*
             * MIM Number: 114208; Disorder: Hypokalemic periodic paralysis, type 1, 170400 (3); {Malignant hyperthermia susceptibility 5}, 601887 (3); {Thyrotoxic periodic paralysis, susceptibility to, 1}, 188580 (3); Comments: in mouse, mutation causes muscular dysgenesis
             */
            
            for (int i = 1; i < tokens.length; ++i) {
                String disorder = tokens[i].trim();
                if (disorder.startsWith("Comments:")) {
                    // do nothing..
                }
                else {
                    if (disorder.startsWith("Disorder:")) {
                        disorder = disorder.substring(9);
                    }
                    pos = disorder.lastIndexOf(',');
                    if (pos > 0) {
                        String pheno = disorder.substring(pos+1);
                        if (disorder.charAt(0) == '{') {
                            pos = disorder.indexOf('}');
                            disorder = pos < 0
                                ? disorder.substring(1)
                                : disorder.substring(1, pos);
                        }
                        else {
                            disorder = disorder.substring(0, pos);
                        }
                        
                        Matcher m = OmimRegex.matcher(pheno);
                        if (m.find()) {
                            String id = m.group(1);
                            String key = m.group(2);
                            Logger.debug
                                ("OMIM: "+disorder+" ["+id+"] ("+key+")");
                            if (key.charAt(0) == '3') {
                                disorder = disorder.replaceAll("/", "-");
                                Keyword kw = KeywordFactory.registerIfAbsent
                                    (OMIM_TERM, disorder,
                                     "http://omim.org/entry/"+id);
                                target.addIfAbsent((Value)kw);
                            }
                        }
                    }
                }
            }
        }
        
        void addPhenotype (Target target, long protein) throws Exception {
            Set<Keyword> terms = new HashSet<>();
            Map<String, Keyword> sources = new TreeMap<String, Keyword>();
            int phenoCount = 0;
            pstm7.setLong(1, protein);
            try (ResultSet rset = pstm7.executeQuery()) {
                while (rset.next()) {
                    String type = rset.getString("ptype");
                    if ("impc".equalsIgnoreCase(type)) {
                        Keyword source = datasources.get(type);
                        if (source == null) {
                            source = KeywordFactory.registerIfAbsent
                                (SOURCE, type,
                                 "http://www.mousephenotype.org/data/secondaryproject/idg");
                            datasources.put(type, source);
                        }
                        sources.put(type, source);
                        String term = rset.getString("term_name");
                        String termId = rset.getString("term_id");
                        if (term != null) {
                            Keyword kw = KeywordFactory.registerIfAbsent
                                (IMPC_TERM, term.replaceAll("/","-"),
                                 "http://www.informatics.jax.org/searches/Phat.cgi?id=" + termId);
                            terms.add(kw);
                        }
                    }
                    else if ("gwas catalog".equalsIgnoreCase(type)) {
                        Keyword source = datasources.get(type);
                        if (source == null) {
                            source = KeywordFactory.registerIfAbsent
                                (SOURCE, type,
                                 "https://www.genome.gov/26525384");
                            datasources.put(type, source);
                        }
                        String trait = rset.getString("trait");
                        if (trait != null) {
                            sources.put(type, source);                      
                            trait = trait.replaceAll("/", "-");
                            Keyword gwas = KeywordFactory.registerIfAbsent
                                (GWAS_TRAIT, trait, null);
                            XRef ref = target.addIfAbsent(new XRef (gwas));
                            ref.addIfAbsent(source);
                        
                            long pmid = rset.getLong("pmid");
                            if (!rset.wasNull()) {
                                ref.properties.add(new VInt (PUBMED_ID, pmid));
                                /*
                                  Publication pub =
                                  PublicationFactory.registerIfAbsent(pmid);
                                  if (pub != null) {
                                  XRef ref = target.getLink(pub);
                                  Keyword t = KeywordFactory.registerIfAbsent
                                  (GWAS_TRAIT, trait, null);
                                  if (ref == null) {
                                  ref = new XRef (pub);
                                  ref.properties.add(source);
                                  ref.properties.add(t);
                                  ref.save();
                                  target.links.add(ref);
                                  }
                                  else {
                                  boolean add = true;
                                  if (!ref.properties.contains(t)) {
                                  ref.properties.add(t);
                                  ref.update();
                                  }
                                  }
                                  target.addIfAbsent(pub);
                                  }
                                  else {
                                  Logger.warn("Can't retrieve publication "+pmid+
                                  "for target "+target.id);
                                  }
                                */
                            }
                        
                            if (ref.id == null) {
                                ref.save();
                            }
                        
                            // also add this as a property
                            target.addIfAbsent((Value)gwas);
                        }
                    }
                    else if ("JAX/MGI Human Ortholog Phenotype"
                             .equalsIgnoreCase(type)) {
                        Keyword source = datasources.get(type);             
                        if (source == null) {
                            source = KeywordFactory.registerIfAbsent
                                (SOURCE, type,
                                 "http://www.informatics.jax.org/");
                            datasources.put(type, source);
                        }
                        String pheno = rset.getString("term_name");
                        String termId = rset.getString("term_id");
                        if (pheno != null) {
                            pheno = pheno.replaceAll("/", "-");
                            sources.put(type, source);
                            Keyword kw = KeywordFactory.registerIfAbsent
                                (MGI_TERM, pheno,
                                 "http://www.informatics.jax.org/searches/Phat.cgi?id="+termId);
                            target.addIfAbsent((Value)kw);
                            ++phenoCount;
                        }
                    }
                    else if ("OMIM".equalsIgnoreCase(type)) {
                        Keyword source = datasources.get(type);             
                        if (source == null) {
                            source = KeywordFactory.registerIfAbsent
                                (SOURCE, type, "http://omim.org/");
                            datasources.put(type, source);
                        }
                        String trait = rset.getString("trait");
                        if (trait != null) {
                            sources.put(type, source);
                            parseOMIMPhenotype (trait, target);
                        }
                    }
                    else {
                        Logger.warn("Unknown phenotype \""+type
                                    +"\" for target "+target.id);
                    }
                }
            }

            if (!terms.isEmpty()) {
                for (Keyword term : terms) {
                    target.properties.add(term);
                }
                Logger.debug("Target "+target.id+" has "
                             +terms.size() +" phenotype(s)!");
            }
            
            for (Keyword source: sources.values()) {
                target.addIfAbsent((Value)source);
            }

            if (!terms.isEmpty() || phenoCount > 0) {
                Keyword pheno = KeywordFactory.registerIfAbsent
                    (IDG_TOOLS, IDG_TOOLS_PHENOTYPES, null);
                target.addIfAbsent((Value)pheno);
            }
        }
        
        void addTDL (Target target, long protein) throws Exception {
            int selective = 0;
            Keyword mice = KeywordFactory.registerIfAbsent
                ("IMPC Mice Produced", "YES", null);
            Map<String, Integer> counts = new TreeMap<String, Integer>();

            pstm6.setLong(1, protein);
            pstm6.setLong(2, protein);
            try (ResultSet rset = pstm6.executeQuery()) {
                while (rset.next()) {
                    String type = rset.getString("itype");
                
                    Value val = null;
                    if (type.equals("IMPC Mice Produced")) {
                        int bv = rset.getInt("boolean_value");
                        if (bv != 0)
                            val = mice;
                    }
                    else {
                        for (String field : new String[]{"string_value",
                                                         "number_value",
                                                         "integer_value",
                                                         "date_value",
                                                         "boolean_value"}) {
                            if (field.equals("string_value")) {
                                String str = rset.getString(field);
                                if (str != null)
                                    val = new Text (type, str);
                            }
                            else if (field.equals("number_value")) {
                                double num = rset.getDouble(field);
                                if (!rset.wasNull())
                                    val = new VNum (type, num);
                            }
                            else if (field.equals("boolean_value")) {
                                int b = rset.getInt(field);
                                if (!rset.wasNull() && b != 0) {
                                    val = KeywordFactory.registerIfAbsent
                                        (type, "YES", null);
                                }
                            }
                            else {
                                long num = rset.getLong(field);
                                if (!rset.wasNull())
                                    val = new VInt (type, num);
                            }
                        }
                    }
                
                    if (val != null) {
                        //Logger.debug("Target "+target.id+": "+type);
                        Integer c = counts.get(type);
                        counts.put(type, c==null?1:(c+1));
                        if (type.equalsIgnoreCase("UniProt Function")) {
                            target.description = (String)val.getValue();
                        }
                        else if (type.equalsIgnoreCase("PubTator Score")) {
                            target.pubTatorScore = Math.log10
                                (((Number)val.getValue()).doubleValue());
                        }
                        else if (type.equalsIgnoreCase("Ab Count")) {
                            target.antibodyCount =
                                ((Number)val.getValue()).intValue();
                        }
                        else if (type.equalsIgnoreCase("MAb Count")) {
                            target.monoclonalCount =
                                ((Number)val.getValue()).intValue();
                        }
                        else if (type.equalsIgnoreCase("PubMed Count")) {
                            target.pubmedCount =
                                ((Number)val.getValue()).intValue();
                        }
                        else if (type.equalsIgnoreCase
                                 ("JensenLab PubMed Score")) {
                            target.jensenScore =
                                ((Number)val.getValue()).doubleValue();
                        }
                        else if (type.equalsIgnoreCase
                                 ("EBI Total Patent Count")) {
                            target.patentCount =
                                ((Number)val.getValue()).intValue();
                        }
                        else {
                            if (type.equalsIgnoreCase
                                (IDG_TOOLS_SELECTIVE_COMPOUNDS)) {
                                String xv = (String)val.getValue();
                                if (xv != null) {
                                    int pos = xv.indexOf('|');
                                    String chemblid = null, smiles = null;
                                    if (pos > 0) {
                                        chemblid = xv.substring(0, pos);
                                        smiles = xv.substring(pos+1);
                                    }

                                    if (chemblid != null) {
                                        Keyword kw = KeywordFactory
                                            .registerIfAbsent
                                            (type, chemblid, smiles);
                                        target.properties.add(kw);
                                    }
                                }
                                ++selective;
                            }
                        
                            target.addIfAbsent(val);
                        }
                    }
                    else {
                        Logger.warn("TDL info \""+type+"\" for target "
                                    +target.id+" is null!");
                    }
                }
            }

            if (selective > 0) {
                Keyword kw = KeywordFactory.registerIfAbsent
                    (IDG_TOOLS, IDG_TOOLS_SELECTIVE_COMPOUNDS, null);
                target.properties.add(kw);
            }
            
            if ((target.antibodyCount != null && target.antibodyCount > 0)
                || (target.monoclonalCount != null
                    && target.monoclonalCount > 0)) {
                Keyword kw = KeywordFactory.registerIfAbsent
                    (IDG_TOOLS, IDG_TOOLS_ANTIBODIES, null);
                target.properties.add(kw);
            }

            Logger.debug("Target "+target.id+" tdl info: "+counts);
        }

        void addUberon (Target target, long protein) throws Exception {
            
        }
        
        void addPanther (Target target, long protein) throws Exception {
            pstm10.setLong(1, protein);
            Map<String, String> parents = new HashMap<String, String>();
            Map<String, String> panther = new HashMap<String, String>();
            try (ResultSet rset = pstm10.executeQuery()) {
                while (rset.next()) {
                    String pcid = rset.getString("pcid");
                    String name = rset.getString("name");
                    String ancestor = rset.getString("parent_pcids");
                    for (String p : ancestor.split("\\|")) {
                        if (!"PC00000".equals(p)) {
                            String old = parents.put(pcid, p);
                            if (old != null && !old.equals(p)) {
                                Logger.warn("Target "+target.accession
                                            +" has two Panther parents: "
                                            +p+" and "+old+"; keeping "+p+"!");
                            }
                        }
                    }
                    panther.put(pcid, name);
                }

                if (panther.size() == 1) {
                    // sigh.. tcrd didn't explicitly specify the parent chain
                    // so we have to manually
                    String pcid = panther.keySet().iterator().next();
                    while (!"PC00000".equals(pcid)) {
                        pstm10b.setString(1, pcid);
                        try (ResultSet rs = pstm10b.executeQuery()) {
                            if (rs.next()) {
                                pcid = rs.getString("pcid");
                                String name = rs.getString("name");
                                String ancestor =
                                    rs.getString("parent_pcids");
                                for (String p : ancestor.split("\\|")) {
                                    if (!"PC00000".equals(p)) {
                                        String old = parents.put(pcid, p);
                                        if (old != null && !old.equals(p)) {
                                            Logger.warn
                                                ("Target "+target.accession
                                                 +" has two Panther parents: "
                                                 +p+" and "+old+"; keeping "
                                                 +p+"!");
                                        }
                                    }
                                }
                                panther.put(pcid, name);
                                pcid = parents.get(pcid);
                            }
                            else {
                                Logger.warn("Something's rotten in new "
                                            +"mexico; no data for panther "
                                            +"class "+pcid+"!");
                                break;
                            }
                        }
                    }
                }
            }

            Keyword[] path = new Keyword[panther.size()];
            for (Map.Entry<String, String> me : panther.entrySet()) {
                int d = 0;
                for (String p = parents.get(me.getKey()); p != null; ++d)
                    p = parents.get(p);
                //Logger.debug("PANTHER "+me.getKey()+" "+d);

                if (d >= path.length) {
                }
                else if (path[d] != null) {
                    // ignore this lineage!
                }
                else {
                    Keyword kw = KeywordFactory.registerIfAbsent
                        (PANTHER_PROTEIN_CLASS + " ("+d+")",
                         me.getValue(), "http://pantherdb.org/panther/category.do?categoryAcc="+me.getKey());
                    target.properties.add(kw);
                    path[d] = kw;
                }
            }
            
            for (int k = path.length; --k >= 0; ) {
                Keyword node = path[k];
                if (node != null) {
                    List<Predicate> predicates = PredicateFactory.finder.where
                        (Expr.and(Expr.eq("subject.refid", node.id.toString()),
                                  Expr.eq("predicate",
                                          PANTHER_PROTEIN_ANCESTRY)))
                        .findList();
                    if (predicates.isEmpty()) {
                        try {
                            Predicate pred = new Predicate
                                (PANTHER_PROTEIN_ANCESTRY);
                            pred.subject = new XRef (node);
                            for (int j = k; --j >= 0; ) {
                                pred.objects.add(new XRef (path[j]));
                            }
                            pred.save();
                        }
                        catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }
                }
            }
        }
        
        void addDTO (Target target, long protein, DTOParser.Node dtoNode)
            throws Exception {
            List<Keyword> path = new ArrayList<Keyword>();
            Logger.debug("Target "+IDGApp.getId(target)+" "
                         +target.idgFamily+" DTO");

            if (dtoNode != null) {
                dtoNode.url = routes.IDGApp.target
                    (IDGApp.getId(target)).url();
                dtoNode.tdl = target.idgTDL.toString();
                dtoNode.size = 100;
                if (target.novelty != null) {
                    // this assumes novelty is expressed as log10, so that
                    //  "novel" targets are bigger
                    dtoNode.size += (int)(10*target.novelty + 0.5);
                }
                dtoNode.name = IDGApp.getGeneSymbol(target);
                dtoNode.fullname = target.name;
                
                List<DTOParser.Node> nodes = new ArrayList<DTOParser.Node>();
                for (DTOParser.Node node = dtoNode.parent;
                     node != null
                         && !node.id.equals("DTO_00200000") // Gene
                         && !node.id.equals("DTO_00100000") // Protein
                         ; node = node.parent) {
                    nodes.add(node);
                }
                
                Collections.reverse(nodes);
                String enhanced = Play.application()
                    .configuration().getString("ix.idg.dto.enhanced");
                if (enhanced != null) {
                    File file = new File (enhanced);
                    DTOParser.Node node = nodes.iterator().next();
                    DTOParser.writeJson(file, node.parent);
                }
                
                for (DTOParser.Node n : nodes) {
                    Keyword kw = KeywordFactory.registerIfAbsent
                        (DTO_PROTEIN_CLASS+" ("+path.size()+")",
                         n.name.replaceAll("/", "-"),
                         // not a real url.. 
                         "http://drugtargetontology.org/"+n.id);

                    StringBuilder sb = new StringBuilder ();
                    for (int i = 0; i < path.size(); ++i)
                        sb.append("\t");
                    Logger.debug(kw.id+" "+kw.label+" \""+kw.term+"\" "+kw.href);
                    if (n.url == null)
                        n.url = routes.IDGApp.targets(null, 10, 1).url()
                            +"?facet="+kw.label+"/"+kw.term;
                    
                    target.properties.add(kw);
                    path.add(kw);
                }
            }
            else if (false) { // this is the older version
                Keyword kw = KeywordFactory.registerIfAbsent
                    (DTO_PROTEIN_CLASS + " (0)", target.idgFamily, null);
                target.properties.add(kw);
                path.add(kw);
                
                pstm5.setLong(1, protein);
                try (ResultSet rset = pstm5.executeQuery()) {
                    while (rset.next()) {
                        String label = rset.getString("name").trim();
                        if (target.idgFamily.equals("GPCR")) {
                            if (label.equalsIgnoreCase("Ligand Type"))
                                break; // we're done
                        }
                        else if (target.idgFamily.equals("Ion Channel")) {
                            if (label.equalsIgnoreCase("Transporter Protein Type"))
                                break;
                        }
                        else if (target.idgFamily.equals("Kinase")) {
                            if (label.equalsIgnoreCase("Pseudokinase"))
                                break;
                        }
                        else if (target.idgFamily.equals("Nuclear Receptor")) {
                            // nothing to check
                        }
                    
                        String value = rset.getString("value");
                        if (value.equals(""))
                            break; // we're done
                        //value = value.replaceAll("/", "-");
                        Logger.debug("  name=\""+label+"\" value="+value);
                    
                        kw = KeywordFactory.registerIfAbsent
                            (DTO_PROTEIN_CLASS+" ("+path.size()+")", value, null);
                        target.properties.add(kw);
                        path.add(kw);
                    }
                }
            }
            else {
                
            }

            if (path.isEmpty()) {
                Logger.warn("!!! Target "+protein+"/"
                            +IDGApp.getId(target)+" has no DTO entry !!!");
            }
            
            for (int k = path.size(); --k >= 0; ) {
                Keyword node = path.get(k);
                List<Predicate> predicates = PredicateFactory.finder.where
                    (Expr.and(Expr.eq("subject.refid", node.id.toString()),
                              Expr.eq("predicate",
                                      DTO_PROTEIN_ANCESTRY)))
                    .findList();
                if (predicates.isEmpty()) {
                    try {
                        Predicate pred = new Predicate
                            (DTO_PROTEIN_ANCESTRY);
                        pred.subject = new XRef (node);
                        for (int j = k; --j >= 0; ) {
                            pred.objects.add(new XRef (path.get(j)));
                        }
                        pred.save();
                    }
                    catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            }
        }

        int addDrugs (Target target, long tid, Keyword tcrd)
            throws Exception {
            int count = 0;            
            pstm17.setLong(1, tid);
            try (ResultSet rset = pstm17.executeQuery()) {
                while (rset.next()) {
                    String chemblId = rset.getString("cmpd_chemblid");
                    String drug = rset.getString("drug");
                    String ref = rset.getString("reference");
                    String source = rset.getString("source");
                    String info = rset.getString("nlm_drug_info");
                    Long dcid = rset.getLong("dcid");
                    if (rset.wasNull())
                        dcid = null;
                    Long cid = rset.getLong("cmpd_pubchem_cid");
                    if (rset.wasNull())
                        cid = null;
                
                    List<Ligand> ligands = LigandFactory.finder.where()
                        .in("synonyms.term", drug, chemblId).findList();

                    Ligand ligand = null;
                    if (ligands.isEmpty()) {
                    }
                    else {
                        Set<Long> uniq = new HashSet<>();
                        for (Ligand lig : ligands) {
                            uniq.add(lig.id);
                            if (drug.equalsIgnoreCase(lig.name))
                                ligand = lig;
                        }
                    
                        if (uniq.size() > 1) {
                            Logger.warn
                                ("Drug \""+drug+"\" and ligand \""+chemblId
                                 +"\" are different instances: "+uniq);
                        }
                        if (ligand == null)
                            ligand = ligands.get(0); // just get one
                    }

                    if (ligand == null) {
                        // new ligand
                        String smiles = rset.getString("smiles");
                    
                        ligand = new Ligand (drug);
                        ligand.addIfAbsent(KeywordFactory.registerIfAbsent
                                           (IDG_DRUG, drug, ref));
                        
                        ligand.properties.add(tcrd);
                        ligand.description = info;

                        if (smiles != null) {
                            ligand.properties.add
                                (new Text (ChEMBL_SMILES, smiles));
                            Structure struc = StructureProcessor.instrument
                                (smiles, null, false);
                            struc.save();
                            XRef xref = new XRef (struc);
                            for (Value v : struc.properties)
                                ligand.addIfAbsent(v);
                            xref.save();
                            ligand.links.add(xref);
                            MOLIDX.add(null, struc.id.toString(), struc.molfile);
                        }

                        pstm23.setString(1, drug);
                        ResultSet rs = pstm23.executeQuery();
                        while (rs.next()) {
                            String syn = rs.getString(1);
                            if (syn != null) {
                                if (syn.startsWith("CHEMBL")) {
                                    ligand.addIfAbsent
                                        (KeywordFactory.registerIfAbsent
                                         (ChEMBL_ID, syn,
                                          "https://www.ebi.ac.uk/chembl/compound/inspect/" +syn));
                                }
                                else {
                                    ligand.addIfAbsent
                                        (KeywordFactory.registerIfAbsent
                                         (ChEMBL_SYNONYM, syn, null));
                                }
                            }
                        }
                        rs.close();
                    
                        ligand.save();
                    
                        Logger.debug("New drug "+ligand.id+" "
                                     +ligand.getName()+" added!");
                    }
                    else if (ligand.name.startsWith("CHEMBL")) {
                        ligand.description = info;
                        ligand.name = drug;
                        ligand.addIfAbsent
                            (KeywordFactory.registerIfAbsent(IDG_DRUG, drug, ref));
                    }
                
                    if (source != null) {
                        Keyword ds = datasources.get(source);
                        if (ds == null) {
                            ds = KeywordFactory.registerIfAbsent
                                (SOURCE, source, source.equalsIgnoreCase
                                 (ChEMBL) ? "https://www.ebi.ac.uk/chembl"
                                 : null);
                            datasources.put(source, ds);
                        }
                        ligand.addIfAbsent((Value)ds);
                    
                        // add as property
                        Keyword kw = KeywordFactory.registerIfAbsent
                            (LIGAND_SOURCE, source, null);
                        kw.href = ref;
                        ligand.addIfAbsent((Value)kw);
                        Logger.debug(ligand.getName()+" drug source: "+source);
                    }

                    ligand.addIfAbsent((Value)KeywordFactory.registerIfAbsent
                                       (LIGAND_DRUG, "YES", null));

                    if (dcid != null) {
                        ligand.addIfAbsent
                            (KeywordFactory.registerIfAbsent
                             (DRUGCENTRAL_ID, "DrugCentral"+dcid,
                              "http://drugcentral.org/drugcard/"+dcid));
                        
                        Keyword ds = datasources.get(DRUGCENTRAL);
                        if (ds == null) {
                            ds = KeywordFactory.registerIfAbsent
                                (SOURCE, DRUGCENTRAL, "http://drugcentral.org");
                            datasources.put(DRUGCENTRAL, ds);
                        }
                        ligand.addIfAbsent((Value)ds);
                    }
                    
                    if (cid != null) {
                        ligand.addIfAbsent
                            (KeywordFactory.registerIfAbsent
                             (PUBCHEM_CID, "CID"+cid,
                              "https://pubchem.ncbi.nlm.nih.gov/compound/"
                              +cid));
                    }
                    
                    if (chemblId != null) {
                        Keyword kw = KeywordFactory.registerIfAbsent
                            (ChEMBL_ID, chemblId,
                             "https://www.ebi.ac.uk/chembl/compound/inspect/"
                             +chemblId);
                        ligand.addIfAbsent(kw);

                        Keyword ds = datasources.get(ChEMBL);
                        if (ds == null) {
                            ds = KeywordFactory.registerIfAbsent
                                (SOURCE, ChEMBL,
                                 "https://www.ebi.ac.uk/chembl");
                            datasources.put(ChEMBL, ds);
                        }
                        ligand.addIfAbsent((Value)ds);
                    }

                    XRef tref = ligand.addIfAbsent(new XRef (target));
                    tref.addIfAbsent((Value)getTdlKw (target.idgTDL));
                    tref.addIfAbsent((Value)getFamKw (target.idgFamily));
                    Keyword acc = target.getSynonym(UNIPROT_GENE);
                    if (acc != null)
                        tref.addIfAbsent((Value)getKeyword
                                         (IDG_TARGET, acc.term, acc.href));
                
                    XRef lref = target.addIfAbsent(new XRef (ligand));
                    lref.addIfAbsent((Value)getKeyword
                                     (IDG_LIGAND, ligand.getName()));
                    // transfer lychies over
                    for (Value v : ligand.properties)
                        if (v.getLabel().startsWith("LyChI"))
                            lref.addIfAbsent(v);

                    String actType = rset.getString("act_type");
                    if (actType != null) {
                        double act = rset.getDouble("act_value");
                        VNum val = new VNum (actType, act);
                        tref.properties.add(val);
                        lref.properties.add(val);
                    }

                    String action = rset.getString("action_type");
                    if (action != null) {
                        Keyword kw = KeywordFactory.registerIfAbsent
                            (PHARMALOGICAL_ACTION, action, ref);
                    
                        tref.addIfAbsent((Value)kw);
                        lref.addIfAbsent((Value)kw);
                    }

                    try {
                        tref.save();
                        lref.save();
                        ligand.update();
                        //target.update();
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    ++count;
                }
                Logger.debug("Target " + target.id + " has "
                             + count +" drug(s)!");
            }
            return count;
        }

        void addTINX(Target target, long tid) throws Exception {
            pstm.setLong(1, tid);
            try (ResultSet rs = pstm.executeQuery()) {
                int n = 0;
                while (rs.next()) {
                    String doid = rs.getString("doid");
                    double tinx = rs.getDouble("importance");
                    double diseaseNovelty = rs.getDouble("diseaseNovelty");
                    String uniprot = rs.getString("uniprot");
                    double targetNovelty = rs.getDouble("targetNovelty");
                    TINX tinxe = new TINX
                        (uniprot, doid, targetNovelty, tinx, diseaseNovelty);
                    tinxe.save();
                    n++;
                }
                rs.close();
                Logger.debug("Target " + target.id + " has "
                             + n +" TINX entries!");
            }
        }
        
        int addLigands (Target target, long tid, Keyword tcrd)
            throws Exception {
            
            int count = 0;            
            pstm16.setLong(1, tid);
            try (ResultSet rset = pstm16.executeQuery()) {
                Set<String> seen = new HashSet<String>();
                long start = System.currentTimeMillis();        
                while (rset.next()) {
                    String cmpdId = rset.getString("cmpd_id_in_src");
                    if (seen.contains(cmpdId))
                        continue;

                    seen.add(cmpdId);
                    String syn = rset.getString("cmpd_name_in_src");
                    String catype = rset.getString("catype");
                    boolean isChembl = catype.equalsIgnoreCase("ChEMBL");
                    
                    Keyword source = datasources.get(catype);
                    if (source == null) {
                        source = KeywordFactory.registerIfAbsent
                            (SOURCE, catype, null);
                        datasources.put(catype, source);
                    }

                    List<Ligand> ligands = LigandFactory.finder.where()
                        .eq("synonyms.term", isChembl
                            ? cmpdId : ("IUPHAR"+cmpdId)).findList();
                
                    Ligand ligand = null;
                    if (ligands.isEmpty()) {
                        if (isChembl) {
                            ligand = new Ligand (cmpdId);
                            ligand.synonyms.add
                                (KeywordFactory.registerIfAbsent
                                 (ChEMBL_SYNONYM, cmpdId,
                                  "https://www.ebi.ac.uk/chembl/compound/inspect/"
                                  +cmpdId));
                            
                        }
                        else {
                            ligand = new Ligand (syn);
                            ligand.synonyms.add
                                (KeywordFactory.registerIfAbsent
                                 (IUPHAR_SYNONYM, "IUPHAR"+cmpdId,
                                  "http://www.guidetopharmacology.org/GRAC/LigandDisplayForward?ligandId="+cmpdId));
                        }
                        ligand.properties.add(source);
                        ligand.properties.add(tcrd);
                    
                        Keyword kw = new Keyword (LIGAND_SOURCE, source.term);
                        kw.href = source.href;
                        ligand.properties.add(kw);
                    
                        String smiles = rset.getString("smiles");
                        if (smiles != null && smiles.length() > 0) {
                            long t0 = System.currentTimeMillis();
                            ligand.properties.add
                                (new Text (ChEMBL_SMILES, smiles));
                            Structure struc = StructureProcessor.instrument
                                (smiles, null, false);
                            struc.save();
                            XRef xref = new XRef (struc);
                            for (Value v : struc.properties)
                                ligand.addIfAbsent(v);
                            xref.save();
                            ligand.links.add(xref);
                            // now index the structure for searching

                            try {
                                MOLIDX.add(null,
                                           struc.id.toString(), struc.molfile);
                            }
                            catch (IllegalArgumentException e) {
                                Logger.debug(e.toString());
                            }
                            /*
                              Logger.debug("... "+cmpdId+": structure "
                              +struc.id+" indexed in "
                              +(System.currentTimeMillis()-t0)+"ms");
                            */
                        }

                        pstm24.setString(1, cmpdId);
                        ResultSet rs = pstm24.executeQuery();
                        while (rs.next()) {
                            String s = rs.getString(1);
                            if (s != null && s.length() <= 255) {
                                if (isChembl) {
                                    ligand.addIfAbsent
                                        (KeywordFactory.registerIfAbsent
                                         (ChEMBL_SYNONYM, s,
                                          "https://www.ebi.ac.uk/chembl/compound/inspect/"+cmpdId));
                                }
                                else {
                                    ligand.addIfAbsent
                                        (KeywordFactory.registerIfAbsent
                                         (IUPHAR_SYNONYM, s,
                                          "http://www.guidetopharmacology.org/GRAC/LigandDisplayForward?ligandId="+cmpdId));
                                }
                            }
                        }
                        rs.close();
                        ligand.save();
                    }
                    else {
                        if (ligands.size() > 1)
                            Logger.warn("Ligand "+cmpdId+" has "+ligands.size()
                                        +"instances!");
                        ligand = ligands.get(0);
                    }

                    if (syn != null && syn.length() <= 255) {
                        Keyword found = null;
                        for (Keyword kw : ligand.getSynonyms())
                            if (syn.equalsIgnoreCase(kw.term)) {
                                found = kw;
                                break;
                            }
                    
                        if (found == null) {
                            Keyword kw;
                            if (isChembl) {
                                kw = getKeyword 
                                    (ChEMBL_SYNONYM, syn,
                                     "https://www.ebi.ac.uk/chembl/compound/inspect/"
                                     +cmpdId);
                            }
                            else {
                                kw = getKeyword
                                    (IUPHAR_SYNONYM, syn,
                                     "http://www.guidetopharmacology.org/GRAC/LigandDisplayForward?ligandId="+cmpdId);
                            }
                            ligand.addIfAbsent(kw);
                        }
                    }

                    Value actsrc = KeywordFactory.registerIfAbsent
                        (LIGAND_ACTIVITY_SOURCE, catype, null);
                    ligand.addIfAbsent(actsrc);
                    
                    VNum act = new VNum (rset.getString("act_type"),
                                         rset.getDouble("act_value"));
                    act.save();

                    String pmids = rset.getString("pubmed_ids");
                    if (pmids != null) {
                        /*
                          Publication pub = PublicationFactory.registerIfAbsent(pmid);
                          XRef ref = new XRef (pub);
                          ref.properties.add(act);
                          ligand.addIfAbsent(ref);
                          ligand.addIfAbsent(pub);
                        */
                        for (String p : pmids.split("\\|")) {
                            try {
                                ligand.properties.add
                                    (new VInt (PUBMED_ID, Long.parseLong(p)));
                            }
                            catch (NumberFormatException ex) {
                                Logger.error("Bogus PMID: "+p, ex);
                            }
                        }
                    }

                    Keyword endpoint = getKeyword (LIGAND_ACTIVITY, act.label);
                    XRef tref = ligand.addIfAbsent(new XRef (target));
                    tref.addIfAbsent((Value)getTdlKw (target.idgTDL));
                    tref.addIfAbsent((Value)getFamKw (target.idgFamily));
                    Keyword acc = target.getSynonym(UNIPROT_GENE);
                    if (acc != null)
                        tref.addIfAbsent((Value)getKeyword
                                         (IDG_TARGET, acc.term, acc.href));
                    tref.addIfAbsent(endpoint);
                    tref.addIfAbsent(actsrc);
                
                    XRef lref = target.addIfAbsent(new XRef (ligand));
                    lref.addIfAbsent(getKeyword (IDG_LIGAND, ligand.getName()));
                    lref.addIfAbsent(endpoint);
                    lref.addIfAbsent(actsrc);
                    
                    // transfer lychies over
                    for (Value v : ligand.properties)
                        if (v.getLabel().startsWith("LyChI"))
                            lref.addIfAbsent(v);                    
                
                    tref.properties.add(act);
                    lref.properties.add(act);
                
                    try {
                        if (tref.id == null)
                            tref.save();
                        else
                            tref.update();
                        ligand.update();
                    
                        if (lref.id == null) {
                            lref.save();
                            if (((count+1) % 100) == 0)
                                target.update();
                        }
                        else
                            lref.update();
                    
                        Logger.debug("..."+count+" ligand "
                                     +ligand.name+" "+act.label+"="+act.numval);
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    ++count;
                }
                
                Logger.debug("Target "+target.id+" has "+count+" ligand(s)... "
                             +(System.currentTimeMillis()-start)+"ms!");
            }
            return count;
        }

        Disease registerDiseaseIfAbsent
            (String name, String type, Keyword tcrd, ResultSet rset)
            throws Exception {
            List<Disease> diseases = DiseaseFactory
                .finder.where().eq("name", name).findList();
            
            String did = rset.getString("did");
            if (did != null)
                did = did.trim();

            if (did == null || did.equals(""))
                did = "TCRD:"+rset.getLong("id");
            
            Disease d = null;
            if (diseases.isEmpty()) {
                d = new Disease();
                d.name = name;
                d.description = rset.getString("description");
                //d.properties.add(datasources.get(type));                
                d.properties.add(tcrd);
                
                String drugName = rset.getString("drug_name");
                if (drugName != null) {
                    // add this temporary for now and we
                    //  resolve it later..
                    d.properties.add
                        (KeywordFactory.registerIfAbsent
                         (IDG_DRUG, drugName, null));
                }
                
                if (did != null && did.length() > 0) {
                    if (did.startsWith("DOI")) {
                        d.synonyms.add
                            (KeywordFactory.registerIfAbsent
                             ("DOID", did,
                              "http://www.disease-ontology.org/term/" + did));
                    }
                    else if (did.startsWith("MIM")) {
                        d.synonyms.add
                            (KeywordFactory.registerIfAbsent
                             (UNIPROT_DISEASE, did,
                              "http://omim.org/entry/"
                              + did.substring(did.indexOf(':') + 1)));
                    }
                    else if (did.startsWith("umls")) {
                        d.synonyms.add
                            (KeywordFactory.registerIfAbsent
                             (DISGENET_DISEASE, did,
                              "http://linkedlifedata.com/resource/umls/id/"
                              + did.substring(did.indexOf(':') + 1)));
                    }
                    else {
                        String href = null;
                        if (did.startsWith("OMIM")) {
                            int pos = did.indexOf(':');
                            if (pos > 0) pos = pos+1;
                            else pos = 0;
                            href = "http://omim.org/entry/"+did.substring(pos);
                        }
                        d.synonyms.add(KeywordFactory.registerIfAbsent
                                       (type, did, href));
                    }
                }
                else { // this is obsoleted as of tcrd v3.1.4
                    String ref = rset.getString("reference");
                    if (ref != null) {
                        // UniProt Disease
                        did = ref.replaceAll("[\\s]+", "");
                        d.synonyms.add
                            (KeywordFactory.registerIfAbsent
                             (UNIPROT_DISEASE, did,
                              "http://omim.org/entry/"
                              + did.substring(did.indexOf(':') + 1)));
                        Keyword kw = datasources.get("OMIM");
                        if (kw != null)
                            d.addIfAbsent((Value) kw);
                        kw = datasources.get("UniProt");
                        if (kw != null)
                            d.addIfAbsent((Value) kw);
                    }
                    else { // DrugCentral
                        did = null;
                        Keyword kw = datasources.get("DrugCentral");
                        if (kw == null) {
                            kw = KeywordFactory.registerIfAbsent
                                (SOURCE, "DrugCentral", null);
                            datasources.put("DrugCentral", kw);
                        }
                        d.addIfAbsent((Value)kw);
                    }
                }
                d.save();
                if (did == null) {
                    did = "IDG:D"+d.id;
                }
            }
            else {
                d = diseases.iterator().next();
                if (did != null) {
                    d.addIfAbsent(KeywordFactory.registerIfAbsent
                                  (type, did, null));
                    d.update();
                }
            }

            return d;
        }
        
        void addDisease (Target target, long tid, final Keyword tcrd)
            throws Exception {
            final String type = "DiseaseOntology";
            Keyword ds = datasources.get(type);
            if (ds == null) {
                ds = KeywordFactory.registerIfAbsent
                    (SOURCE, type, "http://www.disease-ontology.org");
                datasources.put(type, ds);
            }

            /*
            Set<String> indications = new HashSet<>();
            pstm20b.setLong(1, tid);
            try (ResultSet rset = pstm20b.executeQuery()) {
                while (rset.next()) {
                    indications.add(rset.getString(1));
                }
                Logger.debug("Target "+target.id+" has "+indications.size()
                             +" indcations!");
            }
            */
            
            pstm20.setLong(1, tid);
            try (ResultSet rset = pstm20.executeQuery()) {
                int cnt = 0;
                while (rset.next()) {
                    final String name = rset.getString("name");

                    String dtype = rset.getString("dtype");
                    /*
                    if ("DrugCentral Indication".equals(dtype)
                        && !indications.contains(name)) {
                        // make sure it's an indication and nothing else
                        Logger.warn("Not an indication \""+name+"\"...");
                        continue;
                    }
                    */
                    
                    Keyword source = datasources.get(dtype);
                    if (source == null) {
                        String url = null;
                        if (dtype.equalsIgnoreCase
                            ("JensenLab Experiment DistiLD")) {
                            url = "http://distild.jensenlab.org";
                        }
                        else if ("JensenLab Knowledge UniProtKB-KW"
                                 .equalsIgnoreCase(dtype)) {
                            url = "http://diseases.jensenlab.org";
                        }
                        else if ("JensenLab Text Mining"
                                 .equalsIgnoreCase(dtype)) {
                            url = "http://diseases.jensenlab.org";
                        }
                        else if ("DisGeNET".equalsIgnoreCase(dtype))
                            url = "http://www.disgenet.org";
                        else if ("Expression Atlas".equalsIgnoreCase(dtype))
                            url = "https://www.ebi.ac.uk/gxa/";
                        else if ("DrugCentral Indication"
                                 .equalsIgnoreCase(dtype)) {
                            url = "http://drugcentral.org/";
                        }
                        else if ("CTD".equalsIgnoreCase(dtype)) {
                            url = "https://ctdbase.org/";
                        }

                        source = KeywordFactory.registerIfAbsent
                            (SOURCE, dtype, url);
                        datasources.put(dtype, source);
                    }
                    Disease d = registerDiseaseIfAbsent
                        (name, type, tcrd, rset);
                    d.addIfAbsent((Value)source);
                    
                    XRef xref = new XRef (d);
                    if ("JensenLab Knowledge UniProtKB-KW"
                        .equalsIgnoreCase(dtype)) {
                        xref.addIfAbsent(KeywordFactory.registerIfAbsent
                                         (IDG_DISEASE, d.name, null));
                    }
                    else if ("Expression Atlas".equalsIgnoreCase(dtype)) {
                        double val = rset.getDouble("log2foldchange");
                        if (!rset.wasNull())
                            xref.properties.add
                                (new VNum ("log2foldchange", val));

                        val = rset.getDouble("pvalue");
                        if (!rset.wasNull())
                            xref.properties.add(new VNum ("pvalue", val));
                    }
                    else if ("DisGeNET".equalsIgnoreCase(dtype)) {
                        String sources = rset.getString("source");
                        if (sources != null) {
                            for (String s : sources.split(",")) {
                                Keyword kw = KeywordFactory.registerIfAbsent
                                    (DISGENET_SOURCE, s, null);
                                d.addIfAbsent((Value)kw);
                            }
                        }
                    }
                    
                    xref.addIfAbsent(source);
                    double zscore = rset.getDouble("zscore");
                    if (!rset.wasNull()) {
                        xref.properties.add(new VNum (IDG_ZSCORE, zscore));
                    }
                    double conf = rset.getDouble("conf");
                    if (!rset.wasNull()) {
                        xref.properties.add(new VNum (IDG_CONF, conf));
                    }
                    String evidence = rset.getString("evidence");
                    if (evidence != null) {
                        xref.properties.add(new Text (IDG_EVIDENCE, evidence));
                    }

                    Keyword kw = KeywordFactory.registerIfAbsent
                        (IDG_DISEASE, d.name, xref.getHRef());
                    xref.addIfAbsent(kw);
                    
                    try {
                        if (xref.id == null) {
                            xref.save();
                            //target.update();
                        }
                        else {
                            xref.update();
                        }
                        target.links.add(xref);
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    xref = d.addIfAbsent(new XRef (target));
                    xref.addIfAbsent(getTdlKw (target.idgTDL));
                    xref.addIfAbsent(getFamKw(target.idgFamily));
                    xref.addIfAbsent(source);

                    try {
                        if (xref.id == null) {
                            xref.save();
                            d.update();
                        }
                        else {
                            xref.update();
                        }
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    ++cnt;
                }

                Logger.debug("Target "+target.id+" has "+cnt+" disease(s)!");
            }
        }

        void addGeneRIF (Target target, long protein) throws Exception {
            pstm4.setLong(1, protein);
            try (ResultSet rset = pstm4.executeQuery()) {
                while (rset.next()) {
                    String desc = rset.getString("text");
                    Text text = new Text (IDG_GENERIF, desc);
                    text.save();
                    XRef ref = new XRef (text);
                    String pmids = rset.getString("pubmed_ids");
                    if (pmids != null) {
                        for (String t : pmids.split("\\|")) {
                            ref.properties.add(new VInt
                                               (PUBMED_ID, Long.parseLong(t)));
                        }
                    }
                    target.links.add(ref);
                }
            }
        }

        void addCompartment (Target target, long protein) throws Exception {
            pstm27.setLong(1, protein);
            try (ResultSet rset = pstm27.executeQuery()) {
                int count = 0;
                long start = System.currentTimeMillis();
                while (rset.next()) {
                    Compartment comp = new Compartment ();
                    comp.type = rset.getString("ctype");
                    comp.goId = rset.getString("go_id");
                    comp.goTerm = rset.getString("go_term");
                    comp.zscore = rset.getDouble("zscore");
                    if (rset.wasNull())
                        comp.zscore = null;
                    comp.evidence = rset.getString("evidence");
                    comp.conf = rset.getDouble("conf");
                    if (rset.wasNull())
                        comp.conf = null;
                    comp.url = rset.getString("url");
                    try {
                        comp.save();
                        XRef ref = new XRef (comp);
                        Keyword kw = getKeyword
                            (COMPARTMENT_GOTERM, comp.goTerm,
                             "http://amigo.geneontology.org/amigo/term/"
                             +comp.goId);
                        ref.properties.add(kw);
                              
                        kw = getKeyword (COMPARTMENT_EVIDENCE, 
                                         comp.evidence, comp.url);
                        ref.properties.add(kw);

                        kw = getKeyword (COMPARTMENT_TYPE, comp.type, null);
                        ref.properties.add(kw);

                        ref.save();
                        target.links.add(ref);
                    }
                    catch (Exception ex) {
                        Logger.error("Can't persist compartment for protein "
                                     +protein, ex);
                    }
                    ++count;
                }
                Logger.debug("Target "+target.id+" has "
                             +count+" compartments..."+String.format
                             ("%1$dms!", System.currentTimeMillis()-start));
            }
        }

        void addLocalization (Target target, long protein) throws Exception {
            pstm31.setLong(1, protein);
            try (ResultSet rset = pstm31.executeQuery()) {
                int count = 0;
                long start= System.currentTimeMillis();
                while (rset.next()) {
                    Keyword signal = KeywordFactory.registerIfAbsent
                        ("Localization Signal", rset.getString("signal"),
                         "http://genome.unmc.edu/LocSigDB");
                    XRef ref = new XRef (signal);
                    String location = rset.getString("location");
                    if (location != null) {
                        Keyword loc = KeywordFactory.registerIfAbsent
                            ("Localization Location", location, null);
                        ref.properties.add(loc);
                    }
                    String pubs = rset.getString("pmids");
                    if (pubs != null) {
                        for (String p : pubs.split("\\|")) {
                            try {
                                ref.properties.add
                                    (new VInt ("Localization Publication", 
                                               Long.parseLong(p)));
                            }
                            catch (NumberFormatException ex) {
                                Logger.warn("Bogus PMID "+p+" for localization");
                            }
                        }
                    }
                    try {
                        ref.save();
                        target.properties.add(signal);
                        target.links.add(ref);
                        ++count;
                    }
                    catch (Exception ex) {
                        Logger.error("Can't persist localization for protein "
                                     +protein, ex);
                    }
                }
                Logger.debug("Target "+target.id+" has "
                             +count+" localization..."
                             +String.format("%1$dms!",
                                            System.currentTimeMillis()-start));
            }
        }

        void addPublication (Target target, long protein) throws Exception {
            pstm22.setLong(1, protein);
            try (ResultSet rset = pstm22.executeQuery()) {
                int count = 0;
                long start = System.currentTimeMillis();
                while (rset.next()) {
                    long pmid = rset.getLong("id");
                    Publication pub = PublicationFactory.byPMID(pmid);
                    if (pub == null) {
                        pub = new Publication ();
                        pub.pmid = pmid;
                        pub.title = rset.getString("title");
                        pub.abstractText = rset.getString("abstract");

                        String date = rset.getString("date");
                        Integer year = null;
                        if (date != null) {
                            year = Integer.parseInt(date.split("-")[0]);
                        }
                        pub.year = year;
                        pub.save();
                    }

                    target.addIfAbsent(pub);
                    ++count;
                }
                
                Logger.debug("Target "+target.id+" has "
                             +count+" publications..."
                             +String.format("%1$dms!",
                                            System.currentTimeMillis()-start));
            }
        }

        void addPPI (Target target, long protein) throws Exception {
            pstm26.setLong(1, protein);
            try (ResultSet rset = pstm26.executeQuery()) {
                TcrdRegistry.addPPI(rset, target, protein);
            }
        }

        void addFeatures (Target target, long protein) throws Exception {
            pstm30.setLong(1, protein);
            try (ResultSet rset = pstm30.executeQuery()) {
                Timeline features = new Timeline ("Protein Features");
                Map<String, Long> counts = new TreeMap<>();
                while (rset.next()) {
                    String t = rset.getString("type");
                    Long c = counts.get(t);
                    counts.put(t, c==null ? 1 : c+1);
                    long start = rset.getLong("begin");
                    if (!rset.wasNull()) {
                        Event ev = new Event (t);
                        ev.start = start;
                        ev.end = rset.getLong("end");
                        ev.description = rset.getString("description");
                        features.events.add(ev);
                    }
                }
                features.save();

                XRef ref = new XRef (features);
                for (Map.Entry<String, Long> f : counts.entrySet()) {
                    ref.properties.add
                        (new VInt ("Feature: "+f.getKey(), f.getValue()));
                    ref.properties.add
                        (getKeyword (PROTEIN_FEATURE, f.getKey()));
                }
                ref.save();
                target.links.add(ref);
                Logger.debug("Target "+target.id+" protein features "+counts);
            }
        }

        void addTechdev (Target target, long protein) throws Exception {
            pstm28.setLong(1, protein);
            try (ResultSet rset = pstm28.executeQuery()) {
                while (rset.next()) {
                    Techdev dev = new Techdev ();
                    dev.pi = rset.getString("pi");
                    dev.grantNum = rset.getString("grant_number");
                    dev.comment = rset.getString("comment");
                    dev.pmcid = rset.getString("publication_pcmid");
                    dev.pmid = rset.getLong("publication_pmid");
                    if (rset.wasNull())
                        dev.pmid = null;
                    dev.resourceUrl = rset.getString("resource_url");
                    dev.dataUrl = rset.getString("data_url");
                    dev.target = target;
                    try {
                        dev.save();

                        XRef ref = new XRef (dev);
                        // add these for target facets
                        ref.properties.add(KeywordFactory.registerIfAbsent
                                           (TECHDEV_PI, dev.pi, null));
                        ref.properties.add(KeywordFactory.registerIfAbsent
                                           (TECHDEV_GRANT, dev.grantNum, null));
                        ref.save();
                        target.links.add(ref);
                    }
                    catch (Exception ex) {
                        Logger.error("Can't persist Techdev "
                                     +rset.getLong("a.id")+" for protein "
                                     +protein, ex);
                    }
                }
            }
        }

        void addOrtholog (Target target, long protein, Keyword tcrd)
            throws Exception {
            pstm32.setLong(1, protein);
            try (ResultSet rset = pstm32.executeQuery()) {
                Map<Long, Ortholog> orthologs = new HashMap<>();
                Map<Long, String[]> sources = new HashMap<>();
                while (rset.next()) {
                    long id = rset.getLong("ortholog_id");
                    Ortholog ortho = orthologs.get(id);
                    if (ortho == null) {
                        ortho = new Ortholog ();
                        orthologs.put(id, ortho);
                        ortho.refid = rset.getString("db_id");
                        ortho.species = rset.getString("species");
                        ortho.geneId = rset.getLong("geneid");
                        if (rset.wasNull())
                            ortho.geneId = null;
                        ortho.symbol = rset.getString("symbol");
                        ortho.name = rset.getString("ortho_name");
                        ortho.url = rset.getString("mod_url");
                        
                        String srcs = rset.getString("sources");
                        if (srcs != null) {
                            List<String> toks = new ArrayList<>();
                            for (String t : srcs.split(",")) {
                                toks.add(t.trim());
                            }
                            sources.put(id, toks.toArray(new String[0]));
                        }
                    }
                    
                    String dtype = rset.getString("dtype");
                    if (dtype != null) {
                        String name = rset.getString("name");
                        Disease d = registerDiseaseIfAbsent
                            (name, dtype, tcrd, rset);
                        XRef ref = new XRef (d);
                        VNum score = new VNum
                            ("Ortholog Disease Score", rset.getDouble("score"));
                        ref.properties.add(score);
                        ref.addIfAbsent(KeywordFactory.registerIfAbsent
                                        (SOURCE, dtype, null));
                        ref.addIfAbsent(KeywordFactory.registerIfAbsent
                                        (IDG_DISEASE, name,
                                         rset.getString("did")));
                        
                        ref.save();
                        ortho.links.add(ref);
                    }
                }

                for (Map.Entry<Long, Ortholog> me : orthologs.entrySet()) {
                    Ortholog ortho = me.getValue();
                    try {
                        ortho.save();
                        XRef ref = new XRef (ortho);

                        String[] srcs = sources.get(me.getKey());
                        if (srcs != null) {
                            for (String source : srcs) {
                                Keyword kw = KeywordFactory.registerIfAbsent
                                    (SOURCE, source, null);
                                ref.properties.add(kw);
                                datasources.put(source, kw);
                            }
                        }
                        
                        ref.properties.add
                            (KeywordFactory.registerIfAbsent
                             ("Ortholog Species", ortho.species, null));
                        
                        ref.save();
                        target.links.add(ref);
                    }
                    catch (Exception ex) {
                        Logger.error("Can't persist Ortholog "
                                     +rset.getLong("id")+" for protein "
                                     +protein, ex);
                    }
                }
                
                Logger.debug("Target "+target.id+" has "+orthologs.size()
                             +" ortholog(s)");
            }
        }

        void persists (TcrdTarget t) throws Exception {
            long start = System.currentTimeMillis();

            Http.Context.current.set(ctx);
            Logger.debug("####### Processing target... "+t.acc+" "+t.id
                         +" \""+t.family+"\' "+t.tdl+" #######");
            
            final Target target = new Target ();
            target.idgFamily = t.family;
            target.accession = t.acc;
            target.gene = t.gene;
            for (Target.TDL tdl : EnumSet.allOf(Target.TDL.class)) {
                if (t.tdl.equals(tdl.name)) 
                    target.idgTDL = tdl;
            }
            assert target.idgTDL != null
                : "Unknown TDL "+t.tdl;
            
            //Logger.debug("...uniprot registration");
            //UniprotRegistry uni = new UniprotRegistry ();
            //uni.register(target, t.acc);
            try {
                instrument (target, t);         
                target.save();
            }
            catch (Exception ex) {
                ex.printStackTrace();
                Logger.debug("Can't persist target "+t.id
                             +" (protein: "+t.protein+")");
            }

            if (t.dtoNode == null) {
                for (Keyword kw : target.getSynonyms()) {
                    if (kw.term != null) {
                        t.dtoNode = t.dto.get(kw.term);
                        if (t.dtoNode != null)
                            break;
                    }
                }
            }

            try {
                addDTO (target, t.protein, t.dtoNode);
                addTDL (target, t.protein);
                addResources (target, t.id);
                addPhenotype (target, t.protein);
                addExpression (target, t.protein);
                addGO (target, t.protein);
                addPathway (target, t.protein);
                addPanther (target, t.protein);
                addPatent (target, t.protein);
                addPubTator (target, t.protein);
                addPMScore (target, t.protein);
                //addGrant (target, t.id);
                long count = addDrugs (target, t.id, t.source);
                count += addLigands (target, t.id, t.source);
                if (count > 0) {
                    target.properties.add
                        (new VInt (LIGAND_COUNT, count));
                }
                else {
                    switch (target.idgTDL) {
                    case Tclin:
                    case Tchem:
                        Logger.warn(target.idgTDL +" target ("
                                    +target.accession
                                    +") has not ligand activities!");
                        break;
                    }
                }
                addAssay (target, t.protein);
                addDisease (target, t.id, t.source);
                addHarmonogram (target, t.protein);
                addGeneRIF (target, t.protein);
                addTINX (target, t.id);
                addPublication (target, t.protein);
                addCompartment (target, t.protein);
                addLocalization (target, t.protein);
                addTechdev (target, t.protein);
                addFeatures (target, t.protein);
                addOrtholog (target, t.protein, t.source);
            }
            catch (Exception ex) {
                Logger.error("Can't parse target "+IDGApp.getId(target)+"!");
                ex.printStackTrace();
            }

            try {
                target.update();
            }
            catch (Exception ex) {
                Logger.error("Can't update target "+target.id+" ("
                             +IDGApp.getId(target)+")", ex);
                ex.printStackTrace();
            }

            Logger.debug("####### Target "+t.acc+" processed in "
                         +String.format("%1$dms!", 
                                        System.currentTimeMillis()-start)
                         +" ########");
        }
    }

    static void loadChemblUniprotMapping
        (Map<String, Set<String>> uniprotMap, File file) {
        try {
            BufferedReader br = new BufferedReader (new FileReader (file));
            for (String line; (line = br.readLine()) != null; ) {
                if (line.charAt(0) == '#')
                    continue;
                String[] toks = line.split("[\\s\t]+");
                if (2 == toks.length) {
                    Set<String> set = uniprotMap.get(toks[0]);
                    if (set == null) {
                        uniprotMap.put
                            (toks[0], set = new TreeSet<String>());
                    }
                    set.add(toks[1]);
                }
            }
            br.close();
        }
        catch (IOException ex) {
            Logger.trace("Can't load uniprot mapping file: "+file, ex);
        }
    }
    
    public static Result load () {
        DynamicForm requestData = Form.form().bindFromRequest();
        if (Play.isProd()) { // production..
            /*
            String secret = requestData.get("secret-code");
            if (secret == null || secret.length() == 0
                || !secret.equals(Play.application()
                                  .configuration().getString("ix.idg.secret"))) {
                return unauthorized
                    ("You do not have permission to access resource!");
            }
            */
            return redirect (routes.IDGApp.index());
        }
        
        String jdbcUrl = requestData.get("jdbcUrl");
        String jdbcUsername = requestData.get("jdbc-username");
        String jdbcPassword = requestData.get("jdbc-password");
        Logger.debug("JDBC: "+jdbcUrl);

        DataSource ds = null;
        if (jdbcUrl == null || jdbcUrl.equals("")) {
            //return badRequest ("No JDBC URL specified!");
            ds = DB.getDataSource("tcrd");
        }
        else {
            BoneCPDataSource bone = new BoneCPDataSource ();
            bone.setJdbcUrl(jdbcUrl);
            bone.setUsername(jdbcUsername);
            bone.setPassword(jdbcPassword);
            ds = bone;
        }

        if (ds == null) {
            return badRequest ("Neither DataSource \"tcrd\" found "
                               +"nor jdbc url is specified!");
        }

        String maxRows = requestData.get("max-rows");
        Logger.debug("Max Rows: "+maxRows);

        int count = 0;
        try {
            int rows = 0;
            if (maxRows != null && maxRows.length() > 0) {
                try {
                    rows = Integer.parseInt(maxRows);
                }
                catch (NumberFormatException ex) {
                    Logger.warn("Bogus maxRows \""+maxRows+"\"; default to 0!");
                }
            }
            count = load (ds, 1, rows);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }

        return redirect (routes.IDGApp.index());
    }

    static int load (DataSource ds, int threads, int rows) throws Exception {

        Set<TcrdTarget> targets = new HashSet<TcrdTarget>();    
        Keyword source = null;
        Connection con = ds.getConnection();
        Statement stm = con.createStatement();
        int count = 0;
        try {
            ResultSet rset = stm.executeQuery("select * from dbinfo");
            if (rset.next()) {
                source = KeywordFactory.registerIfAbsent
                    (SOURCE, "TCRDv"+rset.getString("data_ver"),
                     "https://druggablegenome.net");
            }
            rset.close();

            String sql = 
                ("select *\n"
                 +"from t2tc a "
                 +"     join (target b, protein c)\n"
                 +"on (a.target_id = b.id and a.protein_id = c.id)\n"
                 +"left join tinx_novelty d\n"
                 +"    on d.protein_id = a.protein_id \n"
                 //+"where c.id in (18204,862,74,6571)\n"
                 /*
                 +"where a.target_id in (2025,2364,2365,2529,2863,"
                 +"2972,3004,3114,3137,3138,3141,3148,3150,3522,4889,"
                 +"5169,8765,16887)\n"
                 */
                 //+"where c.uniprot = 'P01375'\n"
                 //+"where b.tdl in ('Tclin','Tchem')\n"
                 //+"where b.idgfam = 'kinase'\n"
                 //+" where c.uniprot in ('Q96K76','Q6PEY2')\n"
                 //+"where b.idg2=1\n"
                 +"order by d.score desc, c.id\n"
                 +(rows > 0 ? ("limit "+rows) : "")
                 );

            Logger.debug("Executing sql..."+sql);
            rset = stm.executeQuery(sql);           

            DTOParser dto = null;
            String enhanced = Play.application()
                .configuration().getString("ix.idg.dto.enhanced");
            if (enhanced != null) {
                File file = new File (enhanced);
                if (file.exists()) {
                    // load the dto from this file
                    Logger.debug("Loading enhanced DTO file..."+file);
                    try {
                        dto = DTOParser.readJson(file);
                    }
                    catch (Exception ex) {
                        Logger.warn("File '"+file
                                    +"' exists but its content is crap!", ex);
                    }
                }
            }
                            
            if (dto == null) {
                try {
                    dto = new DTOParser ();
                    dto.load(con);
                }
                catch (SQLException ex) {
                    Logger.error("Can't load DTO from TCRD; "
                                 +"revert to json file!", ex);
                    String dtofile = Play.application()
                        .configuration().getString("ix.idg.dto.basic");
                    File file = new File (dtofile);
                    if (file.exists()) {
                        Logger.debug("Loading basic DTO file..."+dtofile);
                        try {
                            dto = new DTOParser ();
                            dto.parse(file);
                        }
                        catch (IOException exx) {
                            exx.printStackTrace();
                        }
                    }
                    else {
                        Logger.warn("!!!! NO DTO AVAILABLE !!!!");
                    }
                }
            }
                 
            while (rset.next()) {
                long protId = rset.getLong("protein_id");
                if (rset.wasNull()) {
                    Logger.warn("Not a protein target: "
                                +rset.getLong("target_id"));
                    continue;
                }
                
                long id = rset.getLong("target_id");
                String fam = rset.getString("fam");
                String tdl = rset.getString("tdl");
                String acc = rset.getString("uniprot");
                String dtoid = rset.getString("dtoid");
                String sym = rset.getString("c.sym");
                Double novelty = rset.getDouble("d.score");
                if (rset.wasNull())
                    novelty = null;
                List<Target> tlist = targetDb
                    .where().eq("synonyms.term", acc).findList();
                
                if (tlist.isEmpty()) {
                    //Logger.debug("Adding "+acc);
                    TcrdTarget t =
                        new TcrdTarget (acc, sym, fam, tdl, id, protId,
                                        novelty, source);
                    t.dto = dto;
                    t.dtoNode = dto.get(dtoid);
                    t.idg2 = 1 == rset.getInt("idg2");
                    Logger.debug("queuing "+acc+" DTO "+dtoid);
                    targets.add(t);
                }
                else {
                    Logger.debug("Skipping "+acc);
                }
            }
            rset.close();
            stm.close();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        finally {
            con.close();
        }

        Logger.debug("Preparing to process "+targets.size()+" targets...");
        PersistRegistration regis = new PersistRegistration
            (ds.getConnection(), Http.Context.current(), targets);
        PQ.submit(regis);
        //regis.persists();
        
        return count;
    }

    static int addPPI (ResultSet rset, Target target, long protein)
        throws Exception {
        Predicate pred = null;
        while (rset.next()) {
            if (pred == null) {
                pred = new Predicate (TARGET_PPI);
                pred.subject = new XRef (target);
            }
            
            long p2 = rset.getLong("protein2_id");
            if (p2 != protein) {  // don't store self-link
                List<Target> targets = TargetFactory.finder
                    .where().eq("synonyms.term", "TCRD:"+p2)
                    .findList();
                if (targets.isEmpty()) {    
                    Logger.error
                        ("Can't find target with protein id="+p2
                         +" from database!");
                }
                else {
                    Target t = targets.get(0);
                    String type = rset.getString("ppitype");
                    Keyword source = KeywordFactory.registerIfAbsent
                        (SOURCE, type, null);
                    target.addIfAbsent((Value)source);
                            
                    XRef ref = new XRef (t);
                    ref.properties.add
                        (new VNum ("p_int", rset.getDouble("p_int")));
                    ref.properties.add
                        (new VNum ("p_ni", rset.getDouble("p_ni")));
                    ref.properties.add
                        (new VNum ("p_wrong",
                                   rset.getDouble("p_wrong")));
                    ref.save();
                    pred.addIfAbsent(ref);
                }
            }
        }
                
        if (pred != null && !pred.objects.isEmpty()) {
            pred.save();
            target.ppiCount = pred.objects.size();
            Logger.debug("Target "+target.id+" has "
                         +target.ppiCount
                         +" protein-protein interactions!");
            
            return pred.objects.size();
        }
        
        return -1;
    }
    
    static int updatePPI (DataSource ds) throws Exception {
        int cnt = 0;    
        try (Connection con = ds.getConnection();
             PreparedStatement pstm = con.prepareStatement
             ("select * from ppi where protein1_id = ?")) {
            for (Target t : TargetFactory.finder.all()) {
                Keyword kw = t.getSynonym(IDG_TARGET);
                if (kw != null) {
                    Long id = null;
                    try {
                        int pos = kw.term.indexOf(':');
                        if (pos > 0) {
                            id = Long.parseLong(kw.term.substring(pos+1));
                        }
                    }
                    catch (NumberFormatException ex) {
                        Logger.warn("Bogus target \""+t.name+"\"; no TCRD ID");
                    }

                    if (id != null) {
                        try {
                            pstm.setLong(1, id);
                            ResultSet rset = pstm.executeQuery();
                            if (addPPI (rset, t, id) > 0) {
                                t.update();
                                INDEXER.update(t);
                                ++cnt;
                            }
                        }
                        catch (Exception ex) {
                            Logger.error("Can't update target "+t.id, ex);
                        }
                    }
                }
            }
            Logger.debug(cnt+" targets with PPI updated!");
        }
        return cnt;
    }

    public static Result ppi () {
        if (Play.isProd())
            return redirect (routes.IDGApp.index());
        try {
            int cnt = updatePPI (DB.getDataSource("tcrd"));
            return ok (cnt+" targets with PPI updated!");
        }
        catch (Exception ex) {
            return internalServerError (ex.getMessage());
        }
    }
    
    public static Result index () {
        if (Play.isProd()) {
            return redirect (routes.IDGApp.index());
        }
        return ok (ix.idg.views.html.tcrd.render("IDG TCRD Loader"));
    }
}
