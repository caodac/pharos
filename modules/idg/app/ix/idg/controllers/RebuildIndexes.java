package ix.idg.controllers;

import com.avaje.ebean.Expr;
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
import play.db.ebean.Model;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.*;

public class RebuildIndexes extends Controller implements Commons {

    static final Model.Finder<Long, Target> targetDb = 
        new Model.Finder(Long.class, Target.class);
    static final Model.Finder<Long, Disease> diseaseDb = 
        new Model.Finder(Long.class, Disease.class);
    static final Model.Finder<Long, Ligand> ligandDb = 
        new Model.Finder(Long.class, Ligand.class);
    static final Model.Finder<Long, Publication> pubDb = 
        new Model.Finder(Long.class, Publication.class);
    
    static final TextIndexer INDEXER = 
        Play.application().plugin(TextIndexerPlugin.class).getIndexer();
    static final SequenceIndexer SEQIDX = Play.application()
        .plugin(SequenceIndexerPlugin.class).getIndexer();
    static final StructureIndexer MOLIDX = Play.application()
        .plugin(StructureIndexerPlugin.class).getIndexer();

    static final ReentrantLock lock = new ReentrantLock ();
    static final AtomicInteger targetCount = new AtomicInteger ();
    static final AtomicInteger diseaseCount = new AtomicInteger ();
    static final AtomicInteger ligandCount = new AtomicInteger ();
    static final AtomicInteger pubCount = new AtomicInteger ();

    public static Result reindex () {
        if (Play.application().isProd()) {
            return redirect (routes.IDGApp.index());
        }
        
        if (lock.isLocked()) {
            return ok ("Index rebuilding...: targets="+targetCount.get()
                       +" diseases="+diseaseCount.get()
                       +" ligands="+ligandCount.get()
                       +" pubs="+pubCount.get());
        }
        
        lock.lock();
        try {
            try {
                long start = System.currentTimeMillis();
                buildIndexes ();
                return ok ("index rebuilding took "+String.format
                           ("%1$.3fs", (System.currentTimeMillis()-start)/1000.)
                           +"; targets="+targetCount.get()
                           +" diseases="+diseaseCount.get()
                           +" ligands="+ligandCount.get()
                           +" publications="+pubCount.get());
            }
            catch (Exception ex) {
                return internalServerError (ex.getMessage());
            }
        }
        finally {
            lock.unlock();
        }
    }

    public static void buildIndexes () throws Exception {
        Logger.debug("indexing targets...");
        for (Target t : targetDb.all()) {
            for (Value v : t.properties) {
                if (UNIPROT_SEQUENCE.equals(v.label)) {
                    Text seq = (Text)v;
                    SEQIDX.add(String.valueOf(seq.id), seq.text);
                }
            }
            INDEXER.add(t);
            Logger.debug("..."+t.accession);
            targetCount.getAndIncrement();
        }
        
        Logger.debug("indexing ligands...");
        for (Ligand l : ligandDb.all()) {
            for (XRef xref : l.links) {
                if (xref.kind.equals(Structure.class.getName())) {
                    Structure struc = (Structure)xref.deRef();
                    MOLIDX.add(null, struc.id.toString(), struc.molfile);
                }
            }
            INDEXER.add(l);
            Logger.debug("..."+l.name);
            ligandCount.getAndIncrement();
        }

        Logger.debug("indexing diseases...");
        for (Disease d : diseaseDb.all()) {
            INDEXER.add(d);
            Logger.debug("..."+d.name);
            diseaseCount.getAndIncrement();
        }

        Logger.debug("indexing publications...");
        for (Publication p : pubDb.all()) {
            INDEXER.add(p);
            Logger.debug("..."+p.pmid);
            pubCount.getAndIncrement();
        }
    }
}
