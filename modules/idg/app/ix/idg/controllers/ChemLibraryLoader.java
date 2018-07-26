package ix.idg.controllers;

import com.avaje.ebean.Expr;
import ix.core.chem.StructureProcessor;
import ix.core.controllers.KeywordFactory;
import ix.core.controllers.PredicateFactory;
import ix.core.models.*;
import ix.core.plugins.*;
import ix.core.search.TextIndexer;
import ix.idg.models.*;
import com.fasterxml.jackson.databind.JsonNode;

import play.Logger;
import play.Play;
import play.libs.Json;
import play.cache.Cache;
import play.data.DynamicForm;
import play.data.Form;
import play.db.ebean.Model;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.BodyParser;
import static play.mvc.Http.MultipartFormData;

import lychi.LyChIStandardizer;
import lychi.util.ChemUtil;
import chemaxon.struc.Molecule;
import chemaxon.util.MolHandler;
import chemaxon.formats.MolImporter;

import java.io.*;
import java.util.*;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

public class ChemLibraryLoader extends Controller implements Commons {
    static CharsetEncoder asciiEncoder = 
        Charset.forName("US-ASCII").newEncoder();
    static final TextIndexer INDEXER = 
        Play.application().plugin(TextIndexerPlugin.class).getIndexer();

    static abstract class MolRecord {
        final Molecule mol;
        MolRecord (Molecule mol) {
            this.mol = mol;
        }
        abstract String getId ();
        abstract String getUrl ();
    }
    
    public static Result selleck () {
        if (Play.application().isDev()) {
            return ok (ix.idg.views.html.selleck.render());
        }
        return redirect (routes.IDGApp.index());
    }

    public static Result mipe () {
        if (Play.application().isDev()) {
            return ok (ix.idg.views.html.mipe.render());
        }
        return redirect (routes.IDGApp.index());
    }
    
    static int process (Function<Molecule, MolRecord> mapfunc)
        throws Exception {
        MultipartFormData form = request().body().asMultipartFormData();
        MultipartFormData.FilePart part = form.getFile("molfile");
        if (part != null) {
            Map<String, String[]> params = form.asFormUrlEncoded();
            String libname = params.get("name")[0];
            String liburl = params.containsKey("url")
                ? params.get("url")[0] : null;
            String libdesc = params.containsKey("description")
                ? params.get("description")[0] : null;
            
            Logger.debug("process => molfile="+part.getFilename()
                         +" name="+libname+" url="+liburl
                         +" description="+libdesc);

            Value lib = KeywordFactory.registerIfAbsent
                (LIBRARY, libname, liburl);
            
            File file = part.getFile();
            try (final BufferedReader br = new BufferedReader
                 (new InputStreamReader (new FileInputStream (file)));
                 final PipedInputStream pis = new PipedInputStream ();
                 final PrintStream ps = new PrintStream
                 (new PipedOutputStream (pis))) {
                
                new Thread (new Runnable () {
                        public void run () {
                            try {
                                // use small buffer so that by the time
                                // molimporter starts we don't get dead stream
                                char[] buf = new char[1];
                                for (int nb; (nb = br.read
                                              (buf, 0, buf.length)) != -1;) {
                                    if (asciiEncoder.canEncode(buf[0])) {
                                        ps.print(buf[0]);
                                    }
                                }
                                ps.close();
                            }
                            catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                    }).start();
                
                LyChIStandardizer lychi = new LyChIStandardizer ();
                lychi.removeSaltOrSolvent(true);
                
                MolImporter mi = new MolImporter (pis);
                int matches = 0, total = 0;
                for (Molecule mol = new Molecule (); mi.read(mol); ) {
                    MolRecord mr = mapfunc.apply(mol);

                    try {
                        lychi.standardize(mol);
                        String[] hkeys = LyChIStandardizer.hashKeyArray(mol);
                        List<Ligand> ligands = LigandFactory.finder.where
                            (Expr.and(Expr.eq("properties.label", "LyChI_L4"),
                                      Expr.eq("properties.term", hkeys[3])))
                            .findList();
                        if (ligands.isEmpty()) {
                            Logger.warn("No structure matching "
                                        +mr.getId()+" L4 = "+hkeys[3]);
                        }
                        else {
                            ++matches;
                            if (ligands.size() > 1)
                                Logger.warn(hkeys[3]+" maps to "+ligands.size()
                                            +" ligands!");
                            for (Ligand l : ligands) {
                                Logger.debug(mr.getId()+" => ligand "+l.id);

                                Value kw = KeywordFactory.registerIfAbsent
                                    (libname, mr.getId(), mr.getUrl());
                                int nc = 0;
                                if (kw == l.addIfAbsent(kw)) ++nc;
                                
                                kw = l.addIfAbsent(lib);
                                if (kw == lib) ++nc;
                                if (nc > 0) {
                                    l.update();
                                    INDEXER.update(l);
                                }
                            }
                        }
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                        Logger.error("Can't process "+mr.getId(), ex);
                    }
                    ++total;
                }
                
                return matches;
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        
        return -1;
    }

    @BodyParser.Of(value = BodyParser.MultipartFormData.class,
                   maxLength = 100* 1024 * 1024)    
    public static Result loadSelleck () {
        try {
            int count = process (m -> new MolRecord (m) {
                    public String getId () {
                        return mol.getProperty("Catalog Number");
                    }
                    public String getUrl () {
                        return mol.getProperty("URL");
                    }
                });
            if (count < 0)
                return badRequest ("No molfile specified!");
            
            return ok (count+" structure(s) matched!");
        }
        catch (Exception ex) {
            return internalServerError
                ("Can't load selleck library: "+ex.getMessage());
        }
    }

    @BodyParser.Of(value = BodyParser.MultipartFormData.class,
                   maxLength = 100* 1024 * 1024)    
    public static Result loadMIPE () {
        try {
            int count = process (m -> new MolRecord (m) {
                    public String getId () {
                        return mol.getName();
                    }
                    public String getUrl () {
                        return "https://www.ncbi.nlm.nih.gov/pcsubstance?term="+getId();
                    }
                });
            if (count < 0)
                return badRequest ("No molfile specified!");
            
            return ok (count+" structure(s) matched!");
        }
        catch (Exception ex) {
            return internalServerError
                ("Can't load MIPE library: "+ex.getMessage());
        }
    }
}
