package ix.idg.controllers;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import javax.sql.DataSource;

import play.*;
import play.db.DB;
import play.db.ebean.*;
import play.data.*;
import play.mvc.*;
import com.avaje.ebean.*;

import ix.core.models.*;
import ix.idg.models.*;
import ix.core.controllers.NamespaceFactory;
import ix.core.controllers.KeywordFactory;
import ix.utils.Global;
import ix.core.plugins.*;
import ix.core.search.TextIndexer;
import ix.core.search.SearchOptions;

import com.jolbox.bonecp.BoneCPDataSource;
    
public class TcrdRegistry extends Controller {
    public static final String DISEASE = "IDG Disease";
    public static final String CLASSIFICATION = Target.IDG_CLASSIFICATION;
    public static final String FAMILY = Target.IDG_FAMILY;
    public static final String DRUG = "IDG Drug";
    public static final String ZSCORE = "IDG Z-score";
    public static final String CONF = "IDG Confidence";
    public static final String ChEMBL = "IDG ChEMBL";
    public static final String GENERIF = "IDG GeneRIF";
    public static final String TARGET = "IDG Target";
        
    static final Model.Finder<Long, Target> targetDb = 
        new Model.Finder(Long.class, Target.class);
    static final Model.Finder<Long, Disease> diseaseDb = 
        new Model.Finder(Long.class, Disease.class);
    
    static final TextIndexer indexer = 
        Play.application().plugin(TextIndexerPlugin.class).getIndexer();

    static final ConcurrentMap<String, Disease> diseases =
        new ConcurrentHashMap<String, Disease>();

    public static final Namespace namespace = NamespaceFactory.registerIfAbsent
        ("TCRDv094", "https://pharos.nih.gov");

    static class TcrdTarget implements Comparable<TcrdTarget> {
        String acc;
        String family;
        String tdl;
        Long id;
        Long protein;

        TcrdTarget () {}
        TcrdTarget (String acc, String family, String tdl,
                    Long id, Long protein) {
            this.acc = acc;
            this.family = family;
            this.tdl = tdl;
            this.id = id;
            this.protein = protein;
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

    static TcrdTarget EMPTY = new TcrdTarget ();

    static class RegistrationWorker implements Callable<Integer> {
        BlockingQueue<TcrdTarget> queue;
        Connection con;
        PreparedStatement pstm, pstm2, pstm3, pstm4;
        Http.Context ctx;
        
        RegistrationWorker (Connection con, Http.Context ctx,
                            BlockingQueue<TcrdTarget> queue)
            throws SQLException {
            this.con = con;
            this.queue = queue;
            this.ctx = ctx;
            pstm = con.prepareStatement
                ("select * from target2disease where target_id = ?");
            pstm2 = con.prepareStatement
                ("select * from chembl_activity where target_id = ?");
            pstm3 = con.prepareStatement
                ("select * from drugdb_activity where target_id = ?");
            pstm4 = con.prepareStatement
                ("select * from generif where protein_id = ?");
        }

        public Integer call () throws Exception {
            Http.Context.current.set(ctx);
            Logger.debug("Thread "+Thread.currentThread()+" initialized!");
            int count = 0;
            for (TcrdTarget t; (t = queue.take()) != EMPTY; ) {
                try {
                    register (t);
                    ++count;
                }
                catch (Exception ex) {
                    Logger.trace("Can't register target "+t.acc, ex);
                }
            }
            Logger.debug(Thread.currentThread().getName()+": finished..."
                         +count+" target(s) processed!");

            try {
                pstm.close();
                pstm2.close();
                pstm3.close();
                pstm4.close();
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
            con.close();
            
            return count;
        }

        void register (TcrdTarget t) throws Exception {
            Logger.debug(Thread.currentThread().getName()
                         +": "+t.family+" "+t.tdl+" "+t.acc+" "+t.id);
            Target target = new Target ();
            target.idgFamily = t.family;
            target.idgClass = t.tdl;
            target.synonyms.add
                (new Keyword (TARGET, String.valueOf(t.id)));
            
            Logger.debug("...uniprot registration");
            UniprotRegistry uni = new UniprotRegistry ();
            uni.register(target, t.acc);
            
            Logger.debug("...retrieving disease links");
            pstm.setLong(1, t.id);
            addDiseaseRefs (target, namespace, pstm);
            
            Logger.debug("...retrieving ChEMBL links");
            pstm2.setLong(1, t.id);
            addChemblRefs (target, pstm2);
            
            Logger.debug("...retrieving Drug links");
            pstm3.setLong(1, t.id);
            addDrugDbRefs (target, pstm3);
            
            Logger.debug("...retrieving GeneRIF links");
            pstm4.setLong(1, t.protein);
            addGeneRIF (target, pstm4);
            
            // reindex this entity; we have to do this since
            // the target.update doesn't trigger the postUpdate
            // event.. need to figure exactly why.
            indexer.update(target);
        }
    }

    public static Result load () {
        DynamicForm requestData = Form.form().bindFromRequest();
        if (Play.isProd()) { // production..
            String secret = requestData.get("secret-code");
            if (secret == null || secret.length() == 0
                || !secret.equals(Play.application()
                                  .configuration().getString("ix.secret"))) {
                return unauthorized
                    ("You do not have permission to access resource!");
            }
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

        Http.MultipartFormData body = request().body().asMultipartFormData();
        Http.MultipartFormData.FilePart part = body.getFile("load-do-obo");
        if (part != null) {
            String name = part.getFilename();
            String content = part.getContentType();
            Logger.debug("file="+name+" content="+content);
            File file = part.getFile();
            DiseaseOntologyRegistry obo = new DiseaseOntologyRegistry ();
            try {
                diseases.putAll(obo.register(new FileInputStream (file)));
            }
            catch (IOException ex) {
                Logger.trace("Can't load obo file: "+file, ex);
            }
        }

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

    static int load (DataSource ds, int threads, int rows)
        throws Exception {

        Set<TcrdTarget> targets = new HashSet<TcrdTarget>();    

        Connection con = ds.getConnection();
        Statement stm = con.createStatement();
        int count = 0;
        try {
            ResultSet rset = stm.executeQuery
                ("select * from t2tc a, target b, protein c\n"+
                 "where a.target_id = b.id\n"+
                 "and a.protein_id = c.id "
                 +" order by c.id, c.uniprot "           
                 +(rows > 0 ? ("limit "+rows) : "")
                 );
            while (rset.next()) {
                long protId = rset.getLong("protein_id");
                if (rset.wasNull()) {
                    Logger.warn("Not a protein target: "
                                +rset.getLong("target_id"));
                    continue;
                }
                
                long id = rset.getLong("target_id");
                String fam = rset.getString("idgfam");
                String tdl = rset.getString("tdl");
                String acc = rset.getString("uniprot");
                List<Target> tlist = targetDb
                    .where().eq("synonyms.term", acc).findList();
                
                if (tlist.isEmpty()) {
                    TcrdTarget t =
                        new TcrdTarget (acc, fam, tdl, id, protId);
                    targets.add(t);
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
        
        if (!targets.isEmpty()) {
            threads = Math.max(threads, 1);
            Logger.debug("Preparing to register "
                         +targets.size()+" targets over "
                         +threads+" thread(s)!");
            ExecutorService pool = Executors.newCachedThreadPool();
            
            ArrayBlockingQueue<TcrdTarget> queue =
                new ArrayBlockingQueue<TcrdTarget>(targets.size());
            for (TcrdTarget t : targets) {
                try {
                    queue.put(t); // shouldn't block
                }
                catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }

            List<Future<Integer>> jobs = new ArrayList<Future<Integer>>();
            for (int i = 0; i < threads; ++i) {
                try {
                    jobs.add(pool.submit
                             (new RegistrationWorker
                              (ds.getConnection(), Http.Context.current(),
                               queue)));
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            
            for (int i = 0; i < threads; ++i) {
                try {
                    queue.put(EMPTY); // add as many as there are threads
                }
                catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
            
            Logger.debug("## waiting for threads to finish...");
            for (Future<Integer> f : jobs) {
                try {
                    count += f.get();
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            pool.shutdownNow();
        }
        return count;
    }

    static void addDiseaseRefs (Target target, Namespace namespace,
                                PreparedStatement pstm) throws Exception {
        ResultSet rs = pstm.executeQuery();
        try {
            XRef self = null;
            Keyword family = KeywordFactory.registerIfAbsent
                (Target.IDG_FAMILY, target.idgFamily, null);
            Keyword clazz = KeywordFactory.registerIfAbsent
                (Target.IDG_CLASSIFICATION, target.idgClass, null);

            Keyword name = KeywordFactory.registerIfAbsent
                (UniprotRegistry.TARGET, target.name, target.getSelf());

            int count = 0;
            Map<Long, Disease> neighbors = new HashMap<Long, Disease>();
            while (rs.next()) {
                String doid = rs.getString("doid");
                Disease disease = diseases.get(doid);
                if (disease == null) {
                    List<Disease> dl = DiseaseFactory.finder
                        .where(Expr.and(Expr.eq("synonyms.label",
                                                DiseaseOntologyRegistry.DOID),
                                        Expr.eq("synonyms.term", doid)))
                        .findList();
                    
                    if (dl.isEmpty()) {
                        Logger.warn("Target "+target.id+" references "
                                    +"unknown disease "+doid);
                        continue;
                    }
                    else if (dl.size() > 1) {
                        Logger.warn("Disease "+doid+" maps to "+dl.size()
                                    +" entries!");
                        for (Disease d : dl)
                            Logger.warn("..."+d.id+" "+d.name);

                    }
                    disease = dl.iterator().next();
                    diseases.putIfAbsent(doid, disease);
                }

                double zscore = rs.getDouble("zscore");
                double conf = rs.getDouble("conf");
                
                XRef xref = null;
                for (XRef ref : target.links) {
                    if (ref.kind.equals(disease.getClass().getName())
                        && ref.refid == disease.id) {
                        xref = ref;
                        break;
                    }
                }
                
                if (xref != null) {
                    Logger.warn("Disease "+disease.id+" ("
                                +disease.name+") is "
                                +"already linked with target "
                                +target.id+" ("+target.name+")");
                }
                else {
                    xref = new XRef (disease);
                    xref.namespace = namespace;
                    Keyword kw = KeywordFactory.registerIfAbsent
                        (DISEASE, disease.name, xref.getHRef());
                    xref.properties.add(kw);
                    xref.properties.add(new VNum (ZSCORE, zscore));
                    xref.properties.add(new VNum (CONF, conf));
                    target.links.add(xref);
                    
                    // now add all the unique parents of this disease node
                    getNeighbors (neighbors, disease.links);
                    
                    if (self == null) {
                        Transaction tx = Ebean.beginTransaction();
                        try {
                            self = new XRef (target);
                            self.namespace = namespace;
                            self.properties.add(family);
                            self.properties.add(clazz);
                            self.properties.add(name);
                            self.save();
                            tx.commit();
                        }
                        catch (Exception ex) {
                            Logger.trace("Can't persist XRef for target "
                                         +target.id, ex);
                        }
                        finally {
                            Ebean.endTransaction();
                        }
                    }
                    
                    // link the other way
                    Transaction tx = Ebean.beginTransaction();
                    try {
                        disease.links.add(self);
                        disease.update();
                        tx.commit();
                        indexer.update(disease);
                        ++count;
                    }
                    catch (Exception ex) {
                        Logger.warn("Disease "+disease.id+" ("
                                    +disease.name+")"
                                    +" is already link with target "
                                    +target.id+" ("+target.name+"): "
                                    +ex.getMessage());
                    }
                    finally {
                        Ebean.endTransaction();
                    }
                }
            }

            // TODO: fix this to properly add parent/neighbor relationships
            /*
            for (Disease neighbor : neighbors.values()) {
                neighbor.links.add(self);
                neighbor.update();
                indexer.update(neighbor);
                
                Keyword kw = KeywordFactory.registerIfAbsent
                    (DISEASE, neighbor.name, null);
                XRef xref = new XRef (neighbor);
                xref.namespace = namespace;
                xref.properties.add(kw);
                target.links.add(xref);
            }
            */
            Transaction tx = Ebean.beginTransaction();
            try {
                target.update();
                tx.commit();
                Logger.debug("...."+count+" disease xref(s) added!");
            }
            catch (Exception ex) {
                Logger.trace("Can't update target "+target.id, ex);
            }
            finally {
                Ebean.endTransaction();
            }
        }
        finally {
            rs.close();
        }
    }

    static void getNeighbors (Map<Long, Disease> neighbors, List<XRef> links) {
        for (XRef xr : links) {
            if (Disease.class.getName().equals(xr.kind)) {
                Disease neighbor = (Disease)xr.deRef();
                neighbors.put(neighbor.id, neighbor);
                // recurse
                getNeighbors (neighbors, neighbor.links);
            }
        }
    }

    /**
     * TODO: this should be using the Ligand class!
     */
    @Transactional
    static void addChemblRefs (Target target, PreparedStatement pstm)
        throws SQLException {
        ResultSet rs = pstm.executeQuery();
        try {
            /*
            Namespace ns = NamespaceFactory.registerIfAbsent
                ("ChEMBL", "https://www.ebi.ac.uk/chembl");
            */
            int count = 0;
            while (rs.next()) {
                String chemblId = rs.getString("cmpd_chemblid");
                Keyword kw = KeywordFactory.registerIfAbsent
                    (ChEMBL, chemblId,
                     "https://www.ebi.ac.uk/chembl/compound/inspect/"+chemblId);
                target.properties.add(kw);
                ++count;
            }
            if (count > 0) {
                Logger.debug("Updated target "+target.id+": "+target.name
                             +" with "+count+" ChEMBL references!");
                target.update();
            }       
        }
        finally {
            rs.close();
        }
    }
    
    /**
     * TODO: this should be using the Ligand class!
     */
    @Transactional
    static void addDrugDbRefs (Target target, PreparedStatement pstm)
        throws SQLException {
        ResultSet rs = pstm.executeQuery();
        try {
            int count = 0;
            while (rs.next()) {
                String drug = rs.getString("drug");
                String ref = rs.getString("reference");
                Keyword kw = KeywordFactory.registerIfAbsent
                    (DRUG, drug, ref != null
                     && ref.startsWith("http") ? ref : null);
                target.properties.add(kw);
                ++count;
            }
            if (count > 0) {
                Logger.debug("Updated target "+target.id+": "+target.name
                             +" with "+count+" TCRD Drug references!");
                target.update();
            }
        }
        finally {
            rs.close();
        }
    }

    @Transactional
    static void addGeneRIF (Target target, PreparedStatement pstm)
        throws SQLException {
        ResultSet rs = pstm.executeQuery();
        try {
            int count = 0;
            while (rs.next()) {
                long pmid = rs.getLong("pubmed_ids");
                String text = rs.getString("text");
                int updates = 0;
                for (XRef xref : target.links) {
                    if (Publication.class.getName().equals(xref.kind)) {
                        Publication pub = (Publication)xref.deRef();
                        if (pub == null) {
                            Logger.error("XRef "+xref.id+" reference a "
                                         +"bogus publication!");
                        }
                        else if (pmid == pub.pmid) {
                            xref.properties.add(new Text (GENERIF, text));
                            xref.update();
                            ++updates;
                        }
                    }
                }
                
                if (updates > 0) {
                    ++count;
                }
            }
            
            if (count > 0) {
                Logger.debug("Updated target "+target.id+": "+target.name
                             +" with "+count+" GeneRIF references!");
                target.update();
            }
        }
        finally {
            rs.close();
        }
    }
    
    public static Result index () {
        return ok (ix.idg.views.html.tcrd.render("IDG TCRD Loader"));
    }
}